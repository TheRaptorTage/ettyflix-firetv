package com.lorenzo.ettyflix.data

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Episodi visti e usciti di una serie (senza gli speciali), come su Mac e Apple TV: "21/55" accanto alle
 * stagioni nella scheda e in alto a destra sulle copertine di Continua a guardare.
 * Gli usciti arrivano da TMDB (una volta al giorno), i visti dai progressi del profilo.
 */
object EpisodeCounts {
    private val aired = mutableStateMapOf<Int, Int>()
    private val loadedAt = HashMap<Int, Long>()
    private val loading = HashSet<Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun ensure(id: Int) {
        if (id <= 0 || loading.contains(id)) return
        val t = loadedAt[id]
        if (t != null && System.currentTimeMillis() - t < 86_400_000) return
        loading.add(id)
        scope.launch {
            runCatching {
                val j = TMDB.get("tv/$id")
                if (j != null) {
                    val last = j.obj("last_episode_to_air")
                    val lastS = last.int("season_number") ?: 0
                    val lastE = last.int("episode_number") ?: 0
                    if (lastS > 0) {
                        var count = 0
                        for (s in j.arr("seasons")) {
                            val n = s.int("season_number") ?: continue
                            if (n <= 0 || n > lastS) continue
                            count += if (n == lastS) lastE else (s.int("episode_count") ?: 0)
                        }
                        if (count > 0) { aired[id] = count; loadedAt[id] = System.currentTimeMillis() }
                    }
                }
            }
            loading.remove(id)
        }
    }

    /** episodi visti fino alla fine (una volta sola ciascuno, senza gli speciali) */
    fun watched(id: Int): Int =
        WatchProgress.entries.values
            .filter { it.tmdbID == id && it.mediaType == "tv" && it.finished && (it.season ?: 0) > 0 }
            .map { "${it.season}-${it.episode}" }.toSet().size

    /** (visti, usciti) se so quanti sono usciti */
    fun counts(id: Int): Pair<Int, Int>? {
        val a = aired[id] ?: return null
        if (a <= 0) return null
        return minOf(watched(id), a) to a
    }
}

/** "21/55": capsula scura (Continua a guardare) o chiara (accanto alle stagioni). */
@Composable
fun EpisodesBadge(tmdbID: Int, size: TextUnit = 12.sp, dark: Boolean = true, modifier: Modifier = Modifier) {
    LaunchedEffect(tmdbID) { EpisodeCounts.ensure(tmdbID) }
    @Suppress("UNUSED_VARIABLE") val v = WatchProgress.version   // si aggiorna quando cambiano i progressi
    val c = EpisodeCounts.counts(tmdbID) ?: return
    Box(modifier.clip(RoundedCornerShape(50))
        .background(if (dark) Color(0x8C000000) else Color(0x26FFFFFF))
        .padding(horizontal = 9.dp, vertical = 4.dp)) {
        Text("${c.first}/${c.second}", color = Color.White, fontSize = size, fontWeight = FontWeight.Bold)
    }
}
