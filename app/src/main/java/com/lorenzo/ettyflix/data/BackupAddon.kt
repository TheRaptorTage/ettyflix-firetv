package com.lorenzo.ettyflix.data

import com.lorenzo.ettyflix.EttyFlixApp

/**
 * Server di riserva sotto forma di addon (il link di installazione che finisce con manifest.json, come in Stremio).
 * Si usa solo quando il server principale non trova nessuna fonte: ogni ricerca riparte sempre dal principale.
 */
object BackupAddon {
    private const val KEY = "backup_addon_url"
    private val imdbCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    var manifestURL: String
        get() = EttyFlixApp.prefs.getString(KEY, "") ?: ""
        set(v) { EttyFlixApp.prefs.edit().putString(KEY, v.trim()).apply() }

    private val base: String? get() {
        var s = manifestURL
        if (s.isEmpty()) return null
        if (s.startsWith("stremio://")) s = "https://" + s.removePrefix("stremio://")
        s = s.removeSuffix("/manifest.json").trimEnd('/')
        return if (s.startsWith("http")) s else null
    }

    val isConfigured: Boolean get() = base != null

    private suspend fun imdbID(m: MediaItem): String? {
        val movie = m.mediaType == "movie"
        val k = (if (movie) "m" else "t") + m.tmdbID
        imdbCache[k]?.let { return it }
        val j = TMDB.get((if (movie) "movie" else "tv") + "/${m.tmdbID}/external_ids") ?: return null
        val id = j.str("imdb_id")?.takeIf { it.isNotEmpty() } ?: return null
        imdbCache[k] = id
        return id
    }

    /** Fonti di un film o di un episodio chieste direttamente all'addon. */
    suspend fun sources(m: MediaItem, season: Int?, episode: Int?): List<StreamSource> {
        val b = base ?: return emptyList()
        val imdb = imdbID(m) ?: return emptyList()
        val path = if (m.mediaType == "tv") {
            if (season == null || episode == null) return emptyList()
            "/stream/series/$imdb:$season:$episode.json"
        } else "/stream/movie/$imdb.json"
        val r = Http.get(b + path, timeoutSec = 40)
        val j = if (r.ok) r.json() else null
        j ?: return emptyList()
        return j.arr("streams").mapIndexedNotNull { i, st ->
            val link = st.str("url")?.takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
            val name = st.str("name") ?: ""
            val desc = st.str("description") ?: st.str("title") ?: ""
            val text = "$name\n$desc"
            val hints = st.obj("behaviorHints")
            val file = hints.str("filename") ?: ""
            var size = hints.optLong("videoSize", 0L)
            if (size == 0L) size = sizeFromText(desc)
            val low = "$text $file".lowercase()
            val vcodec = when {
                Regex("\\b(hevc|x265|h\\.?265)\\b").containsMatchIn(low) -> "hevc"
                Regex("\\b(avc|x264|h\\.?264)\\b").containsMatchIn(low) -> "h264"
                Regex("\\bav1\\b").containsMatchIn(low) -> "av1"
                else -> ""
            }
            StreamSource(
                id = "addon-$i-${link.hashCode()}", itemID = "", title = text, size = size, container = "",
                videoCodec = vcodec, height = 0, hdr = false, dolbyVision = false, audioLangs = emptyList(),
                subLangs = emptyList(), fileName = file, audioCodec = guessAudioCodec("$text $file"),
                audioChannels = guessChannels("$text $file"), url = link, origin = "Addon di riserva"
            )
        }
    }

    private fun sizeFromText(t: String): Long {
        val m = Regex("([0-9]+([.,][0-9]+)?) ?(GB|MB|GiB|MiB)", RegexOption.IGNORE_CASE).find(t) ?: return 0
        val num = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return 0
        val gb = m.groupValues[3].lowercase().startsWith("g")
        return (num * if (gb) 1_000_000_000 else 1_000_000).toLong()
    }
}
