package com.lorenzo.ettyflix.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.lorenzo.ettyflix.EttyFlixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Suoni d'avvio originali (gli stessi di Mac, Apple TV e iPhone), a tempo con l'animazione "Isola di vetro". */
enum class StartupSoundStyle(val id: String, val title: String) {
    NESSUNO("nessuno", "Nessun suono"), PROFONDO("profondo", "Profondo"), ABISSO("abisso", "Abisso"), ABISSO2("abisso2", "Abisso 2.0")
}

object StartupSound {
    private const val SR = 44100
    private var track: AudioTrack? = null

    /** Prepara il suono in anticipo (così parte nell'istante esatto dell'animazione). */
    suspend fun prepare(style: StartupSoundStyle, border: Boolean): AudioTrack? = withContext(Dispatchers.Default) {
        if (style == StartupSoundStyle.NESSUNO) return@withContext null
        runCatching {
            val file = File(EttyFlixApp.ctx.cacheDir, "ettyflix_sound_v3_${style.id}_${if (border) "b" else "r"}.pcm")
            val data: FloatArray = if (file.exists() && file.length() > 0) {
                val bb = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(bb.remaining() / 4) { bb.getFloat() }
            } else {
                val (l, r) = render(style, border)
                FloatArray(l.size * 2) { if (it % 2 == 0) l[it / 2] else r[it / 2] }.also { inter ->
                    val bb = ByteBuffer.allocate(inter.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    inter.forEach { bb.putFloat(it) }
                    runCatching { file.writeBytes(bb.array()) }
                }
            }
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 4)
                .build().also { it.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING) }
        }.getOrNull()
    }

    fun start(t: AudioTrack?) {
        t ?: return
        track?.let { runCatching { it.stop(); it.release() } }
        track = t
        runCatching { t.play() }
    }

    // MARK: mattoncini del suono

    private fun midi(m: Double) = 440.0 * 2.0.pow((m - 69) / 12)

    private class Buf(n: Int) { val L = FloatArray(n); val R = FloatArray(n) }

    private fun note(b: Buf, t0: Double, f: Double, dur: Double, amp: Double, partials: List<Pair<Double, Double>>,
                     attack: Double, decay: Double, pan: Double = 0.0, detune: Double = 0.0) {
        val s0 = (t0 * SR).toInt(); val n = (dur * SR).toInt()
        val gl = sqrt((1 - pan) / 2); val gr = sqrt((1 + pan) / 2)
        for (i in 0 until n) {
            val k = s0 + i
            if (k < 0 || k >= b.L.size) continue
            val t = i.toDouble() / SR
            val env = if (t < attack) sin(PI / 2 * t / attack) else exp(-decay * (t - attack))
            val ff = f * (1 + detune)
            var v = 0.0
            for ((m, a) in partials) v += a * sin(2 * PI * ff * m * t)
            val s = v * env * amp
            b.L[k] += (s * gl).toFloat(); b.R[k] += (s * gr).toFloat()
        }
    }

    private fun fmBell(b: Buf, t0: Double, f: Double, amp: Double, ratio: Double = 3.5, index: Double = 2.2,
                       decay: Double = 2.2, pan: Double = 0.0, dur: Double = 3.0) {
        val s0 = (t0 * SR).toInt(); val n = (dur * SR).toInt()
        val gl = sqrt((1 - pan) / 2); val gr = sqrt((1 + pan) / 2)
        for (i in 0 until n) {
            val k = s0 + i
            if (k < 0 || k >= b.L.size) continue
            val t = i.toDouble() / SR
            val env = min(1.0, t / 0.002) * exp(-decay * t)
            val mod = index * exp(-decay * 1.6 * t) * sin(2 * PI * f * ratio * t)
            val s = sin(2 * PI * f * t + mod) * env * amp
            b.L[k] += (s * gl).toFloat(); b.R[k] += (s * gr).toFloat()
        }
    }

    private fun whoosh(b: Buf, t0: Double, t1: Double, amp: Double) {
        val s0 = (t0 * SR).toInt(); val n = ((t1 - t0 + 0.25) * SR).toInt()
        var seed = 12345L
        var lpL = 0.0; var lpR = 0.0
        fun rnd(): Double { seed = (seed * 1664525L + 1013904223L) and 0xFFFFFFFFL; return seed.toInt().toDouble() / Int.MAX_VALUE }
        for (i in 0 until n) {
            val k = s0 + i
            if (k < 0 || k >= b.L.size) continue
            val x = i.toDouble() / n
            val rise = min(1.0, i / ((t1 - t0) * SR))
            val env = if (x < 0.8) rise.pow(2.2) else rise.pow(2.2) * (1 - (x - 0.8) / 0.2)
            val cut = 0.01 + 0.25 * rise
            val a = rnd(); val c = rnd()
            lpL += cut * (a - lpL); lpR += cut * (c - lpR)
            b.L[k] += (lpL * env * amp).toFloat(); b.R[k] += (lpR * env * amp).toFloat()
        }
    }

    private fun sub(b: Buf, t0: Double, f: Double, amp: Double, decay: Double = 1.6, pan: Double = 0.0) {
        val s0 = (t0 * SR).toInt(); val n = (3.2 * SR).toInt()
        val gl = sqrt((1 - pan) / 2) * 1.414; val gr = sqrt((1 + pan) / 2) * 1.414
        for (i in 0 until n) {
            val k = s0 + i
            if (k < 0 || k >= b.L.size) continue
            val t = i.toDouble() / SR
            val env = min(1.0, t / 0.01) * exp(-decay * t)
            val ph = 2 * PI * f * (1 - 0.08 * min(1.0, t)) * t
            val s = (sin(ph) + 0.35 * sin(2 * ph)) * env * amp
            b.L[k] += (s * gl).toFloat(); b.R[k] += (s * gr).toFloat()
        }
    }

    private fun glide(b: Buf, t0: Double, t1: Double, m0: Double, m1: Double, amp: Double) {
        val s0 = (t0 * SR).toInt(); val n = ((t1 - t0) * SR).toInt()
        var ph = 0.0
        for (i in 0 until n) {
            val k = s0 + i
            if (k < 0 || k >= b.L.size) continue
            val x = i.toDouble() / n
            val f = 440.0 * 2.0.pow((m0 + (m1 - m0) * x * x - 69) / 12)
            ph += 2 * PI * f / SR
            val env = x.pow(1.5) * (if (x > 0.97) (1 - x) / 0.03 else 1.0)
            val s = (sin(ph) + 0.3 * sin(2 * ph)) * env * amp
            b.L[k] += s.toFloat(); b.R[k] += s.toFloat()
        }
    }

    private fun reverb(x: FloatArray, mix: Float, size: Double): FloatArray {
        val out = x.copyOf()
        val delays = intArrayOf(1557, 1617, 1491, 1422, 1277, 1356).map { (it * size).toInt() }
        delays.forEachIndexed { j, d ->
            val buf = FloatArray(d); var idx = 0
            val fb = 0.78f - j * 0.02f
            for (i in x.indices) {
                val y = buf[idx]
                buf[idx] = x[i] + y * fb
                idx = (idx + 1) % d
                out[i] += y * mix / delays.size
            }
        }
        return out
    }

    // MARK: i suoni

    private fun render(style: StartupSoundStyle, border: Boolean): Pair<FloatArray, FloatArray> {
        val b = Buf((4.6 * SR).toInt())
        val hit = 1.05; val shine = 1.85
        val glass = listOf(1.0 to 1.0, 2.0 to 0.25, 3.0 to 0.08)
        val pad = listOf(1.0 to 1.0, 2.0 to 0.42, 3.0 to 0.18, 4.0 to 0.07)

        fun sparkle(notes: List<Double>, amp: Double, ratio: Double = 3.5) {
            if (border) {
                notes.forEachIndexed { i, m ->
                    val x = i.toDouble() / max(1, notes.size - 1)
                    fmBell(b, shine + 1.3 * x, midi(m), amp, ratio, 1.4, 3.0, sin(x * 2 * PI) * 0.8)
                }
            } else {
                notes.take(3).forEachIndexed { i, m ->
                    note(b, shine, midi(m), 1.8, amp * 0.8, glass, 0.6, 2.2, listOf(-0.6, 0.0, 0.6)[i % 3])
                }
            }
        }
        fun soft(m: Double, amp: Double = 0.09, attack: Double = 0.015) {
            note(b, 0.0, midi(m), 2.2, amp, listOf(1.0 to 1.0, 2.0 to 0.18, 3.0 to 0.05), attack, 2.4, -0.15)
            note(b, 0.0, midi(m), 2.2, amp * 0.6, listOf(1.0 to 1.0, 2.0 to 0.18), attack, 2.4, 0.15, 0.003)
        }

        when (style) {
            StartupSoundStyle.NESSUNO -> {}
            StartupSoundStyle.PROFONDO -> {
                soft(48.0)
                whoosh(b, 0.3, hit, 0.12)
                sub(b, hit, midi(29.0), 0.5)
                sub(b, hit, midi(41.0), 0.18)
                note(b, hit + 0.05, midi(77.0), 3.0, 0.04, glass, 0.4, 0.8)
                sparkle(listOf(65.0, 72.0, 77.0, 84.0, 89.0, 96.0), 0.04, 1.41)
            }
            StartupSoundStyle.ABISSO -> {
                soft(36.0, 0.12, 0.03)
                whoosh(b, 0.25, hit, 0.14)
                sub(b, hit, midi(24.0), 0.5)
                sub(b, hit, midi(36.0), 0.2)
                fmBell(b, hit, midi(43.0), 0.08, 2.0, 0.8, 0.8, -0.4, 3.4)
                fmBell(b, hit + 0.02, midi(48.0), 0.07, 2.0, 0.8, 0.8, 0.4, 3.4)
                note(b, hit + 0.1, midi(60.0), 3.0, 0.03, glass, 0.5, 0.7)
                sparkle(listOf(60.0, 67.0, 72.0, 74.0, 79.0, 84.0), 0.04, 1.41)
            }
            StartupSoundStyle.ABISSO2 -> {
                sub(b, 0.0, midi(31.0), 0.22, 4.0)
                soft(43.0, 0.07, 0.02)
                glide(b, hit - 0.5, hit, 19.0, 31.0, 0.22)
                whoosh(b, hit - 0.5, hit, 0.13)
                sub(b, hit, midi(24.0), 0.55, 1.3)
                sub(b, hit, midi(36.0), 0.18, 1.8)
                fmBell(b, hit, midi(43.0), 0.08, 2.0, 0.8, 0.8, -0.6, 3.4)
                fmBell(b, hit + 0.02, midi(48.0), 0.07, 2.0, 0.8, 0.8, 0.6, 3.4)
                note(b, hit, midi(55.0), 3.0, 0.035, pad, 0.3, 0.6)
                if (border) {
                    for (i in 0 until 5) {
                        val x = i / 4.0
                        sub(b, shine + 1.3 * x, midi(36.0), 0.12, 5.0, sin(x * 2 * PI) * 0.8)
                    }
                    sparkle(listOf(60.0, 64.0, 67.0, 72.0, 76.0, 79.0), 0.03, 2.0)
                } else {
                    note(b, shine, midi(36.0), 1.8, 0.1, pad, 0.6, 2.2)
                    note(b, shine, midi(48.0), 1.8, 0.05, pad, 0.6, 2.2, -0.5)
                    note(b, shine, midi(55.0), 1.8, 0.04, pad, 0.6, 2.2, 0.5)
                }
            }
        }

        val L = reverb(b.L, 0.55f, 1.0)
        val R = reverb(b.R, 0.55f, 1.07)
        var peak = 0f
        for (i in L.indices) peak = max(peak, max(abs(L[i]), abs(R[i])))
        val gain = if (peak > 0.85f) 0.85f / peak else 1f
        val fadeStart = (3.7 * SR).toInt()
        for (i in L.indices) {
            var g = gain
            if (i > fadeStart) g *= max(0f, 1 - (i - fadeStart).toFloat() / (L.size - fadeStart))
            L[i] *= g; R[i] *= g
        }
        return L to R
    }
}
