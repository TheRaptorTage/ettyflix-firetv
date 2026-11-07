package com.lorenzo.ettyflix.data

import com.lorenzo.ettyflix.EttyFlixApp
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/** Trailer ufficiali dallo store iTunes italiano: link diretto al video (si riproduce anche qui). */
object AppleTrailers {
    private val cache = HashMap<String, String>()
    private const val KEY = "apple_trailers_v1"

    init {
        runCatching {
            val o = JSONObject(EttyFlixApp.prefs.getString(KEY, "{}") ?: "{}")
            for (k in o.keys()) cache[k] = o.getString(k)
        }
    }

    suspend fun url(m: MediaItem): String? {
        cache[m.id]?.let { return it.ifEmpty { null } }
        val found = runCatching { search(m) }.getOrNull()
        cache[m.id] = found ?: ""
        if (cache.size > 600) { val keep = cache.entries.toList().takeLast(300); cache.clear(); keep.forEach { cache[it.key] = it.value } }
        val o = JSONObject(); cache.forEach { (k, v) -> o.put(k, v) }
        EttyFlixApp.prefs.edit().putString(KEY, o.toString()).apply()
        return found
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ITALIAN), Normalizer.Form.NFD).filter { it.isLetterOrDigit() }

    private suspend fun search(m: MediaItem): String? {
        val movie = m.mediaType == "movie"
        val titles = mutableListOf(m.title)
        TMDB.get("${m.mediaType}/${m.tmdbID}")?.let { j ->
            val o = j.optString(if (movie) "original_title" else "original_name")
            if (o.isNotEmpty() && o != m.title) titles += o
        }
        val year = m.year.take(4).toIntOrNull()
        for (t in titles) {
            val url = "https://itunes.apple.com/search?term=" + URLEncoder.encode(t, "UTF-8") +
                "&country=it&media=" + (if (movie) "movie" else "tvShow") + "&entity=" + (if (movie) "movie" else "tvSeason") + "&limit=15"
            val r = Http.get(url)
            if (!r.ok) continue
            val res = runCatching { JSONObject(r.body).optJSONArray("results") }.getOrNull() ?: continue
            val want = norm(t)
            var best: Pair<Int, String>? = null
            for (i in 0 until res.length()) {
                val o = res.getJSONObject(i)
                val p = o.optString("previewUrl").takeIf { it.startsWith("http") } ?: continue
                val name = norm(if (movie) o.optString("trackName") else o.optString("artistName").ifEmpty { o.optString("collectionName") })
                var score = when {
                    name == want -> 10
                    name.startsWith(want) || want.startsWith(name) -> 5
                    else -> continue
                }
                val ry = o.optString("releaseDate").take(4).toIntOrNull()
                if (year != null && ry != null) {
                    if (movie) score += when (kotlin.math.abs(ry - year)) { 0 -> 6; 1 -> 3; else -> -6 }
                    else if (ry >= year) score += 2
                }
                if (!movie && ry != null) score += maxOf(0, ry - 2000) / 10
                if (score > (best?.first ?: 0)) best = score to p
            }
            best?.takeIf { it.first >= 8 }?.let { return it.second }
        }
        return null
    }
}
