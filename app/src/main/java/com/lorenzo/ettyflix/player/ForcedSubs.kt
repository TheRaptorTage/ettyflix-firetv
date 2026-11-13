package com.lorenzo.ettyflix.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.SubtitleView
import com.lorenzo.ettyflix.EttyFlixApp
import com.lorenzo.ettyflix.data.AI
import com.lorenzo.ettyflix.data.Http
import com.lorenzo.ettyflix.data.enc
import com.lorenzo.ettyflix.data.int
import com.lorenzo.ettyflix.data.obj
import com.lorenzo.ettyflix.data.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Forced tradotti, tutto da solo (come su Mac, Apple TV e iPhone).
 * Se il video ha un forced in un'altra lingua (di solito "English forced", che ha TUTTE le frasi in
 * spagnolo ecc.), parte quello e ogni frase viene mostrata tradotta in italiano, con lo stesso stile
 * dei sottotitoli normali (la scritta inglese non si vede mai). Altrimenti parte il forced italiano.
 * Se scegli tu un sottotitolo dal menu, la tua scelta resta com'è.
 */
@OptIn(UnstableApi::class)
class ForcedSubsController(private val player: ExoPlayer, private val scope: CoroutineScope) : Player.Listener {
    /** il riquadro dei sottotitoli del player */
    var view: SubtitleView? = null
    private var userPicked = false
    private var decidedFor: String? = null
    /** lingua da tradurre (null = mostro i sottotitoli così come sono) */
    private var translateLang: String? = null
    private var lastCues: List<Cue> = emptyList()
    private val pending = HashSet<String>()
    private var retryAfter = 0L

    fun attach() { player.addListener(this); ForcedSubs.active = this }
    fun detach() { player.removeListener(this); if (ForcedSubs.active === this) ForcedSubs.active = null }

    /** dal menu: l'utente ha scelto lui (da qui in poi niente scelte automatiche né traduzioni) */
    fun userSelected() {
        userPicked = true
        translateLang = null
    }

    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
        decidedFor = null; userPicked = false; translateLang = null
    }

    override fun onTracksChanged(tracks: Tracks) { decide(tracks) }

    override fun onCues(cueGroup: CueGroup) {
        lastCues = cueGroup.cues
        render()
    }

    private fun isForced(g: Tracks.Group, i: Int): Boolean {
        val f = g.getTrackFormat(i)
        val label = (f.label ?: "").lowercase()
        return (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 || "forced" in label || "forzat" in label || "foreign" in label
    }

    private fun isItalian(g: Tracks.Group, i: Int): Boolean {
        val f = g.getTrackFormat(i)
        return (f.language ?: "").lowercase().startsWith("it") || "ital" in (f.label ?: "").lowercase()
    }

    private fun decide(tracks: Tracks) {
        if (userPicked) return
        val key = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        if (key == decidedFor) return
        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (text.isEmpty()) return                            // tracce non ancora pronte
        decidedFor = key
        var foreign: Pair<Tracks.Group, Int>? = null
        var foreignScore = -1
        var italian: Pair<Tracks.Group, Int>? = null
        for (g in text) for (i in 0 until g.length) {
            if (!g.isTrackSupported(i) || !isForced(g, i)) continue
            val f = g.getTrackFormat(i)
            if (isItalian(g, i)) { if (italian == null) italian = g to i; continue }
            val mime = f.sampleMimeType ?: ""
            if (mime.contains("pgs") || mime.contains("dvb") || mime.contains("vobsub")) continue   // immagini: non si traducono
            val lang = (f.language ?: "").lowercase()
            val score = if (lang.startsWith("en") || "english" in (f.label ?: "").lowercase()) 10 else 1
            if (score > foreignScore) { foreign = g to i; foreignScore = score }
        }
        val pick = foreign ?: italian ?: return
        translateLang = if (foreign != null) (pick.first.getTrackFormat(pick.second).language ?: "en") else null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(pick.first.mediaTrackGroup, pick.second)).build()
    }

    /** Mostra le frasi: tradotte se serve (quelle non ancora tradotte non si vedono finché non arriva la traduzione). */
    private fun render() {
        val v = view ?: return
        val lang = translateLang
        val cues = lastCues
        if (lang == null) { v.setCues(cues); return }
        val shown = ArrayList<Cue>()
        for (c in cues) {
            val src = c.text?.toString()?.trim().orEmpty()
            if (src.isEmpty()) { shown += c; continue }
            val t = ForcedSubs.cached(src, lang)
            if (t != null) { shown += c.buildUpon().setText(t).build(); continue }
            val k = ForcedSubs.key(src, lang)
            if (k in pending || System.currentTimeMillis() < retryAfter) continue
            pending += k
            scope.launch {
                val out = ForcedSubs.translate(src, lang)
                pending -= k
                if (out == null) retryAfter = System.currentTimeMillis() + 3000
                if (lastCues === cues) render()
            }
        }
        v.setCues(shown)
    }
}

object ForcedSubs {
    var active: ForcedSubsController? = null

    private val file get() = File(EttyFlixApp.instance.cacheDir, "forced-tradotti.json")
    private var saved: JSONObject? = null

    fun key(text: String, lang: String) = code(lang) + "|" + text.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()

    private fun store(): JSONObject {
        saved?.let { return it }
        val j = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        saved = j
        return j
    }

    fun cached(text: String, lang: String): String? = store().optString(key(text, lang)).ifEmpty { null }

    private fun remember(text: String, lang: String, out: String) {
        val j = store()
        if (j.length() > 20_000) { saved = JSONObject() }
        store().put(key(text, lang), out)
        runCatching { file.writeText(store().toString()) }
    }

    fun code(lang: String): String {
        val l = lang.lowercase()
        return mapOf("eng" to "en", "spa" to "es", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de",
            "por" to "pt", "rus" to "ru", "jpn" to "ja", "kor" to "ko", "chi" to "zh", "zho" to "zh", "ara" to "ar")[l]
            ?: l.substringBefore('-').ifEmpty { "en" }
    }

    /** Traduce una frase: prima Gemini (se c'è la chiave), poi MyMemory (gratis, senza chiave). */
    suspend fun translate(text: String, lang: String): String? {
        cached(text, lang)?.let { return it }
        val clean = text.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()
        val out = AI.translateSubtitle(clean) ?: myMemory(clean, code(lang))
        if (!out.isNullOrBlank()) remember(text, lang, out.trim())
        return out
    }

    private suspend fun myMemory(text: String, src: String): String? {
        val r = Http.get("https://api.mymemory.translated.net/get?q=${text.take(480).enc()}&langpair=${"$src|it".enc()}", timeoutSec = 10)
        val j = r.json() ?: return null
        if ((j.int("responseStatus") ?: 200) != 200) return null
        return j.obj("responseData").str("translatedText")
            ?.replace("&#39;", "'")?.replace("&quot;", "\"")?.ifBlank { null }
    }
}
