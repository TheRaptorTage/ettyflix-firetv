package com.lorenzo.ettyflix.data

import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Secondi dal 1/1/2001 (le date del Mac usano questo riferimento). */
fun nowRef(): Double = System.currentTimeMillis() / 1000.0 - 978_307_200.0
fun refToMillis(ref: Double): Long = ((ref + 978_307_200.0) * 1000).toLong()
fun isoToRef(s: String?): Double {
    if (s.isNullOrEmpty()) return 0.0
    val fmts = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX")
    for (f in fmts) {
        runCatching {
            val d = SimpleDateFormat(f, Locale.US).parse(s)
            if (d != null) return d.time / 1000.0 - 978_307_200.0
        }
    }
    return 0.0
}

fun todayString(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

fun prettyDate(s: String): String = runCatching {
    val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s) ?: return s
    SimpleDateFormat("d MMMM yyyy", Locale.ITALIAN).format(d)
}.getOrDefault(s)

fun shortDate(s: String): String = runCatching {
    val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s) ?: return s
    SimpleDateFormat("d MMM yyyy", Locale.ITALIAN).format(d)
}.getOrDefault(s)

fun formatTime(t: Double): String {
    if (t.isNaN() || t < 0) return "0:00"
    val s = t.toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

object TMDBImage {
    fun url(path: String?, size: String = "w500"): String? =
        if (path.isNullOrEmpty()) null else "https://image.tmdb.org/t/p/$size$path"
}

@Serializable
data class MediaItem(
    val tmdbID: Int,
    val mediaType: String,
    val title: String = "",
    val overview: String = "",
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val year: String = "",
    val rating: Double = 0.0,
    val popularity: Double = 0.0,
    val genreIDs: List<Int> = emptyList()
) {
    val id: String get() = "$mediaType-$tmdbID"

    companion object {
        fun from(j: JSONObject, defaultType: String?): MediaItem? {
            val id = j.int("id") ?: return null
            var type = j.str("media_type") ?: defaultType ?: ""
            if (type.isEmpty()) type = if (j.has("title")) "movie" else "tv"
            if (type != "movie" && type != "tv") return null
            val date = j.str("release_date") ?: j.str("first_air_date") ?: ""
            return MediaItem(
                tmdbID = id, mediaType = type,
                title = j.str("title") ?: j.str("name") ?: "",
                overview = j.str("overview") ?: "",
                posterPath = j.str("poster_path"), backdropPath = j.str("backdrop_path"),
                year = date.take(4), rating = j.dbl("vote_average") ?: 0.0,
                popularity = j.dbl("popularity") ?: 0.0, genreIDs = j.ints("genre_ids")
            )
        }
    }
}

data class PersonInfo(val id: Int, val name: String, val profilePath: String?, val department: String)

data class PersonDetail(
    val id: Int, val name: String, val biography: String, val profilePath: String?,
    val birthday: String, val placeOfBirth: String, val department: String,
    val credits: List<MediaItem>, val biographyInEnglish: Boolean
)

data class CastMember(val id: Int, val name: String, val character: String, val profilePath: String?)
data class SeasonInfo(val id: Int, val number: Int, val name: String, val episodeCount: Int)

data class Episode(
    val id: Int, val number: Int, val season: Int, val name: String, val overview: String,
    val stillPath: String?, val runtime: Int?, val rating: Double, val airDate: String
)

data class InfoRow(val label: String, val value: String)

data class MediaDetail(
    val item: MediaItem,
    val tagline: String,
    val overview: String,
    val genres: List<String>,
    val runtime: Int?,
    val certification: String,
    val logoPath: String?,
    val cast: List<CastMember>,
    val directors: List<CastMember>,
    val similar: List<MediaItem>,
    val recommendations: List<MediaItem>,
    val seasons: List<SeasonInfo>,
    val info: List<InfoRow>,
    val collectionID: Int?,
    val collectionName: String,
    val trailerKey: String?
)

@Serializable
data class Profile(val id: String, val name: String, val color: Int = 1)

@Serializable
data class WatchEntry(
    val id: String,
    val tmdbID: Int,
    val mediaType: String,
    val title: String = "",
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val logoPath: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String = "",
    val stillPath: String? = null,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    /** secondi dal 1/1/2001, come sul Mac */
    val updated: Double = 0.0,
    val finished: Boolean = false
) {
    val fraction: Double get() = if (duration > 0) minOf(position / duration, 1.0) else 0.0
    val remainingMinutes: Int get() = (maxOf(duration - position, 0.0) / 60).toInt()
    val code: String? get() = if (season != null && episode != null) String.format(Locale.US, "S%02dE%02d", season, episode) else null
    val media: MediaItem get() = MediaItem(tmdbID, mediaType, title, "", posterPath, backdropPath)

    companion object {
        fun key(tmdbID: Int, mediaType: String, season: Int?, episode: Int?): String =
            if (mediaType == "tv" && season != null && episode != null) "tv-$tmdbID-s${season}e$episode" else "$mediaType-$tmdbID"
    }
}

/** Sorgente di contenuti (discover TMDB, lista TMDB, lista Trakt, endpoint). */
data class SourceDef(
    val kind: String,          // discover, tmdbList, traktList, endpoint
    val name: String,
    val mediaType: String,
    val params: Map<String, String> = emptyMap(),
    val listID: Int? = null,
    val path: String = ""
) {
    val key: String get() = "$kind|$mediaType|$name|$path|${listID ?: 0}|" + params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }

    companion object {
        fun endpoint(name: String, path: String, type: String) = SourceDef("endpoint", name, type, path = path)

        fun parse(j: JSONObject): SourceDef? {
            val key = j.keys().asSequence().firstOrNull() ?: return null
            val v = j.obj(key).obj("_0")
            return when (key) {
                "tmdbDiscover" -> {
                    val type = v.str("mediaType") ?: "movie"
                    val p = mutableMapOf<String, String>()
                    fun ids(k: String, sep: String): String? = v.ints(k).takeIf { it.isNotEmpty() }?.joinToString(sep)
                    ids("genres", ",")?.let { p["with_genres"] = it }
                    ids("excludeGenres", ",")?.let { p["without_genres"] = it }
                    ids("keywords", ",")?.let { p["with_keywords"] = it }
                    ids("excludeKeywords", ",")?.let { p["without_keywords"] = it }
                    ids("companies", ",")?.let { p["with_companies"] = it }
                    ids("networks", "|")?.let { p["with_networks"] = it }
                    ids("watchProviders", "|")?.let { p["with_watch_providers"] = it }
                    v.str("watchRegion")?.let { p["watch_region"] = it }
                    v.str("originalLanguage")?.let { p["with_original_language"] = it }
                    v.int("voteCountGte")?.let { p["vote_count.gte"] = it.toString() }
                    v.str("releaseDateGte")?.let { p[if (type == "movie") "primary_release_date.gte" else "first_air_date.gte"] = it }
                    var sort = v.str("sortBy") ?: "popularity.desc"
                    sort = if (type == "movie") sort.replace("first_air_date", "primary_release_date")
                           else sort.replace("primary_release_date", "first_air_date")
                    p["sort_by"] = sort
                    SourceDef("discover", v.str("name") ?: "Scopri", type, p)
                }
                "tmdbList" -> SourceDef("tmdbList", v.str("listName") ?: "Lista", v.str("mediaType") ?: "movie", listID = v.int("listId"))
                "traktList" -> SourceDef("traktList", v.str("listName") ?: "Lista Trakt", "",
                    listID = v.str("listId")?.toIntOrNull() ?: v.int("listId"), path = v.str("username") ?: "")
                else -> null
            }
        }
    }
}

data class Tile(
    val id: String, val title: String, val cover: String?, val background: String?,
    val hideTitle: Boolean, val showsSourceRows: Boolean, val sources: List<SourceDef>
)

data class Shelf(
    val id: String, val title: String, val enabled: Boolean, val order: Int,
    val kind: String, val hideTitle: Boolean, val tiles: List<Tile>
)

object StrandConfig {
    fun parse(text: String): List<Shelf> {
        val root = JSONObject(text)
        val out = mutableListOf<Shelf>()
        for (s in root.arr("shelves")) {
            val src = s.obj("source")
            val kind = src.keys().asSequence().firstOrNull() ?: ""
            val tiles = mutableListOf<Tile>()
            var hide = false
            if (kind == "group") {
                val g = src.obj("group").obj("_0")
                hide = g.bool("hideTitle") ?: false
                for (t in g.arr("contentSources")) {
                    tiles += Tile(
                        id = t.str("id") ?: java.util.UUID.randomUUID().toString(),
                        title = t.str("title") ?: "",
                        cover = t.str("coverImageURL"),
                        background = t.str("backgroundImageURL"),
                        hideTitle = t.bool("hideTitle") ?: false,
                        showsSourceRows = t.bool("showsSourceRows") ?: false,
                        sources = t.arr("sources").mapNotNull { SourceDef.parse(it) }
                    )
                }
            }
            out += Shelf(
                id = s.str("id") ?: java.util.UUID.randomUUID().toString(),
                title = s.str("title") ?: "",
                enabled = s.bool("isEnabled") ?: true,
                order = s.int("sortOrder") ?: 999,
                kind = kind, hideTitle = hide, tiles = tiles
            )
        }
        return out
    }
}

@Serializable
data class MediaServer(val name: String, val url: String, val userID: String, val token: String, val username: String = "")

/** Una fonte da riprodurre (come PlayerSourceOption del Mac). */
data class PlayerSource(
    val url: String,
    val resolution: String,
    val codec: String,
    val size: String,
    val fileName: String,
    val title: String?
)

/** Cosa riprodurre: titolo, episodio e da dove partire. */
data class PlayRequest(
    val media: MediaItem,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String = "",
    val stillPath: String? = null,
    val logoPath: String? = null,
    val runtime: Int? = null,
    val startAt: Double = 0.0
)
