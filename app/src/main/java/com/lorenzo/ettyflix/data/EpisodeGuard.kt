package com.lorenzo.ettyflix.data

import com.lorenzo.ettyflix.EttyFlixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Fonti che danno l'episodio sbagliato (come su Mac e Apple TV): mentre guardi un episodio, quando l'app
 * cerca le fonti del successivo, chiedo a ogni fonte pronta il nome vero del file (solo la risposta, non
 * scarica il video). Se è un altro episodio, quella fonte non viene più mostrata né scelta.
 */
object EpisodeGuard {
    private const val prefKey = "episode_guard_bad_v1"
    private val bad: MutableSet<String> by lazy {
        (EttyFlixApp.prefs.getString(prefKey, "") ?: "").split("\n").filter { it.isNotEmpty() }.toMutableSet()
    }

    private fun key(s: StreamSource, t: Int, season: Int, ep: Int) = "$t|$season|$ep|${s.addonName}|${s.fileName}|${s.size}"

    fun filter(list: List<StreamSource>, tmdbID: Int, season: Int?, episode: Int?): List<StreamSource> {
        if (season == null || episode == null || bad.isEmpty()) return list
        return list.filter { key(it, tmdbID, season, episode) !in bad }
    }

    private fun markBad(s: StreamSource, t: Int, season: Int, ep: Int) {
        if (!bad.add(key(s, t, season, ep))) return
        EttyFlixApp.prefs.edit().putString(prefKey, bad.toList().takeLast(2000).joinToString("\n")).apply()
    }

    /** controlla le fonti pronte (al massimo 10, 4 alla volta) e restituisce l'elenco senza quelle sbagliate */
    suspend fun check(list: List<StreamSource>, tmdbID: Int, season: Int, episode: Int): List<StreamSource> {
        val todo = list.filter { it.isReady && !it.url.isNullOrEmpty() && key(it, tmdbID, season, episode) !in bad }.take(10)
        if (todo.isNotEmpty()) {
            val gate = Semaphore(4)
            val results = coroutineScope {
                todo.map { s -> async { gate.withPermit { s to realName(s.url!!) } } }.awaitAll()
            }
            for ((s, name) in results) {
                val found = name?.let { episodeIn(it) } ?: continue
                if (found.first != season || episode !in found.second) markBad(s, tmdbID, season, episode)
            }
        }
        return filter(list, tmdbID, season, episode)
    }

    /** il nome vero del file: chiedo solo il primo byte e leggo la risposta */
    private suspend fun realName(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
            c.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().use { r ->
                r.header("Content-Disposition")?.let { cd ->
                    val i = cd.indexOf("filename=")
                    if (i >= 0) {
                        val raw = cd.substring(i + 9).trim('"', '\'', ' ', ';')
                        if (raw.isNotEmpty()) return@use runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                    }
                }
                val last = r.request.url.pathSegments.lastOrNull()?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                if (last != null && Regex("\\.(mkv|mp4|avi|m4v|ts|webm)$", RegexOption.IGNORE_CASE).containsMatchIn(last)) last else null
            }
        }.getOrNull()
    }

    /** stagione ed episodi nel nome ("S02E03", "S02E03E04", "S02E03-04", "2x03") */
    fun episodeIn(name: String): Pair<Int, List<Int>>? {
        Regex("(?i)(?<![a-z0-9])s(\\d{1,2})[ ._-]?e(\\d{1,3})(?:[-_]?e?(\\d{1,3}))?(?![0-9])").find(name)?.let { m ->
            val s = m.groupValues[1].toInt(); val a = m.groupValues[2].toInt()
            val b = m.groupValues[3].toIntOrNull()
            return s to (if (b != null && b > a && b - a < 4) (a..b).toList() else listOf(a))
        }
        Regex("(?<![0-9])(\\d{1,2})x(\\d{2,3})(?![0-9])").find(name)?.let { m ->
            return m.groupValues[1].toInt() to listOf(m.groupValues[2].toInt())
        }
        return null
    }
}
