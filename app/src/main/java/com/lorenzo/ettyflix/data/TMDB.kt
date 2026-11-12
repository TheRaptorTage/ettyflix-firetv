package com.lorenzo.ettyflix.data

import com.lorenzo.ettyflix.EttyFlixApp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object TMDB {
    private const val base = "https://api.themoviedb.org/3/"
    private val cache = ConcurrentHashMap<String, String>()

    val apiKey: String get() = EttyFlixApp.prefs.getString("tmdb_api_key_fallback", "") ?: ""
    val hasKey: Boolean get() = apiKey.isNotEmpty()

    suspend fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject? {
        if (apiKey.isEmpty()) return null
        val q = query.toMutableMap()
        q["api_key"] = apiKey
        if (!q.containsKey("language")) q["language"] = "it-IT"
        val url = base + path + "?" + q.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value.enc()}" }
        cache[url]?.let { return runCatching { JSONObject(it) }.getOrNull() }
        val r = Http.get(url)
        if (!r.ok) return null
        if (cache.size > 600) cache.clear()
        cache[url] = r.body
        return r.json()
    }

    private fun parse(list: List<JSONObject>, type: String?): List<MediaItem> = list.mapNotNull { MediaItem.from(it, type) }

    // MARK: Sorgenti

    /** Niente animazione nelle liste, tranne quando la chiedi tu (genere Animazione o riga "Anime e animazione"). */
    private fun withoutAnimation(list: List<MediaItem>, q: Map<String, String>, name: String): List<MediaItem> {
        val wanted = name.lowercase().contains("anim") ||
            (q["with_genres"] ?: "").split(',', '|').contains("16") ||
            (q["with_keywords"] ?: "").contains("210024") ||
            (q["with_original_language"] == "ja" && (q["with_genres"] ?: "").isEmpty())
        return if (wanted) list else list.filter { 16 !in it.genreIDs }
    }

    suspend fun fetchPage(src: SourceDef, page: Int): List<MediaItem> = when (src.kind) {
        "traktList" -> Trakt.listItems(src.listID ?: 0, src.path, page)
        "discover" -> {
            val q = src.params.toMutableMap()
            q["page"] = page.toString()
            if (!q.containsKey("include_adult")) q["include_adult"] = "false"
            withoutAnimation(parse(get("discover/${src.mediaType}", q)?.arr("results") ?: emptyList(), src.mediaType), q, src.name)
        }
        "tmdbList" -> {
            val id = src.listID
            if (id == null) emptyList() else parse(get("list/$id", mapOf("page" to page.toString()))?.arr("items") ?: emptyList(), src.mediaType)
        }
        else -> {
            val q = src.params.toMutableMap()
            q["page"] = page.toString()
            withoutAnimation(parse(get(src.path, q)?.arr("results") ?: emptyList(), src.mediaType.ifEmpty { null }), q, src.name)
        }
    }

    suspend fun items(src: SourceDef, pages: Int): List<MediaItem> = coroutineScope {
        if (src.kind == "traktList" && !Trakt.isConfigured) return@coroutineScope emptyList()
        val lists = (1..maxOf(1, pages)).map { p -> async { runCatching { fetchPage(src, p) }.getOrDefault(emptyList()) } }.awaitAll()
        val seen = HashSet<String>()
        lists.flatten().filter { seen.add(it.id) }
    }

    suspend fun loadGenres(): Map<Int, String> {
        val map = HashMap<Int, String>()
        for (t in listOf("movie", "tv")) {
            get("genre/$t/list")?.arr("genres")?.forEach { g -> g.int("id")?.let { id -> map[id] = g.str("name") ?: "" } }
        }
        return map
    }

    // MARK: Banner (nuove stagioni + serie in onda)

    val newSeasons = ConcurrentHashMap<Int, Int>()

    suspend fun newSeasonItems(): List<MediaItem> = coroutineScope {
        val lastSeason = HashMap<Int, Int>()
        val lastDate = HashMap<Int, Double>()
        for (e in WatchProgress.entries.values) {
            if (e.mediaType != "tv") continue
            lastSeason[e.tmdbID] = maxOf(lastSeason[e.tmdbID] ?: 0, e.season ?: 0)
            lastDate[e.tmdbID] = maxOf(lastDate[e.tmdbID] ?: 0.0, e.updated)
        }
        val ids = lastDate.entries.sortedByDescending { it.value }.take(40).map { it.key }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = System.currentTimeMillis()
        val results = ids.map { id ->
            async {
                val watched = lastSeason[id] ?: 0
                val j = get("tv/$id") ?: return@async null
                val item = MediaItem.from(j, "tv") ?: return@async null
                if (item.backdropPath == null) return@async null
                var best: Pair<Int, Long>? = null
                for (s in j.arr("seasons")) {
                    val n = s.int("season_number") ?: continue
                    if (n <= 0) continue
                    val d = s.str("air_date")?.let { runCatching { fmt.parse(it)?.time }.getOrNull() } ?: continue
                    if (best == null || n > best.first) best = n to d
                }
                val next = j.obj("next_episode_to_air")
                val nn = next.int("season_number")
                if (nn != null && next.int("episode_number") == 1) {
                    val d = next.str("air_date")?.let { runCatching { fmt.parse(it)?.time }.getOrNull() }
                    if (d != null && (best == null || nn >= best.first)) best = nn to d
                }
                val b = best ?: return@async null
                if (b.first <= watched) return@async null
                val days = (b.second - now) / 86_400_000.0
                if (days < -60 || days > 14) return@async null
                Triple(item, b.first, b.second)
            }
        }.awaitAll().filterNotNull()
        newSeasons.clear()
        results.forEach { newSeasons[it.first.tmdbID] = it.second }
        results.sortedByDescending { it.third }.map { it.first }
    }

    /** serie del banner davvero in onda (per l'etichetta) */
    private val onAirIDs = HashSet<Int>()

    /** Fascia di 3 ore: quando cambia, il banner della Home pesca titoli diversi. */
    fun heroSlot(): Long = System.currentTimeMillis() / (3 * 3600 * 1000L)

    /** Serie belle e attuali da cui pescare a caso (in onda, di tendenza, popolari), escluse quelle che stai guardando. */
    suspend fun heroPool(): List<MediaItem> {
        val bad = setOf(16, 10762, 10763, 10764, 10766, 10767)
        val known = WatchProgress.entries.values.map { it.tmdbID }.toSet()
        val out = mutableListOf<MediaItem>()
        val sources = listOf("tv/on_the_air" to mapOf("page" to "1"), "tv/on_the_air" to mapOf("page" to "2"),
            "tv/on_the_air" to mapOf("page" to "3"), "trending/tv/day" to emptyMap<String, String>(), "trending/tv/week" to emptyMap<String, String>(),
            "tv/popular" to emptyMap<String, String>())
        for ((path, q) in sources) {
            val items = parse(get(path, q)?.arr("results") ?: emptyList(), "tv")
            if (path == "tv/on_the_air") onAirIDs += items.map { it.tmdbID }
            out += items
        }
        val ids = HashSet<Int>()
        return out.filter {
            it.backdropPath != null && it.overview.isNotEmpty() && it.rating >= 6.8 && it.genreIDs.none { g -> g in bad } &&
                it.tmdbID !in known && ids.add(it.tmdbID)
        }
    }

    suspend fun heroItems(): List<MediaItem> = heroPool().take(5)

    /** Banner: al massimo 3 nuove stagioni delle tue serie + serie a caso; cambia ogni 3 ore. */
    suspend fun heroWithNewSeasons(): List<MediaItem> = coroutineScope {
        val rnd = java.util.Random(heroSlot() * 2654435761L + 97)
        val mine = async { newSeasonItems() }
        val pool = async { heroPool() }
        // niente titoli usciti prima del 2013 (a volte comparivano serie vecchissime)
        fun recent(m: MediaItem) = (m.year.take(4).toIntOrNull() ?: 9999) >= 2013
        val fresh = mine.await().filter { recent(it) }.shuffled(rnd).take(3)
        val seen = fresh.map { it.tmdbID }.toSet()
        (fresh + pool.await().filter { it.tmdbID !in seen && recent(it) }.shuffled(rnd)).take(10)
    }

    fun heroLabel(m: MediaItem): String = newSeasons[m.tmdbID]?.let { "Nuova stagione $it" }
        ?: if (m.tmdbID in onAirIDs) "In onda questa settimana" else "Serie da scoprire"

    // MARK: Ricerca

    suspend fun findOne(title: String, year: Int?, type: String): MediaItem? {
        val q = mutableMapOf("query" to title)
        if (year != null) q[if (type == "movie") "primary_release_year" else "first_air_date_year"] = year.toString()
        parse(get("search/$type", q)?.arr("results") ?: emptyList(), type).firstOrNull()?.let { return it }
        if (year != null) return parse(get("search/$type", mapOf("query" to title))?.arr("results") ?: emptyList(), type).firstOrNull()
        return null
    }

    suspend fun search(q: String): Triple<List<MediaItem>, List<MediaItem>, List<PersonInfo>> = coroutineScope {
        val m = async { get("search/movie", mapOf("query" to q)) }
        val t = async { get("search/tv", mapOf("query" to q)) }
        val p = async { get("search/person", mapOf("query" to q)) }
        val people = (p.await()?.arr("results") ?: emptyList()).mapNotNull { j ->
            val id = j.int("id") ?: return@mapNotNull null
            PersonInfo(id, j.str("name") ?: "", j.str("profile_path"), j.str("known_for_department") ?: "")
        }
        Triple(parse(m.await()?.arr("results") ?: emptyList(), "movie"), parse(t.await()?.arr("results") ?: emptyList(), "tv"), people)
    }

    suspend fun recommendations(type: String, id: Int): List<MediaItem> =
        parse(get("$type/$id/recommendations")?.arr("results") ?: emptyList(), type)

    // MARK: Dettaglio

    suspend fun detail(type: String, id: Int): MediaDetail? {
        val append = if (type == "movie") "credits,videos,similar,recommendations,images,release_dates,external_ids"
                     else "credits,videos,similar,recommendations,images,content_ratings,external_ids"
        val j = get("$type/$id", mapOf("append_to_response" to append, "include_image_language" to "it,en,null",
            "include_video_language" to "it")) ?: return null
        val base = MediaItem.from(j, type) ?: return null
        val genres = j.arr("genres").mapNotNull { it.str("name") }
        var runtime = j.int("runtime")
        if (runtime == null || runtime == 0) runtime = j.optJSONArray("episode_run_time")?.let { if (it.length() > 0) it.optInt(0) else null }

        var cert = ""
        for (country in listOf("IT", "US")) {
            if (cert.isNotEmpty()) break
            if (type == "movie") {
                for (r in j.obj("release_dates").arr("results")) if (r.str("iso_3166_1") == country)
                    for (rd in r.arr("release_dates")) if (cert.isEmpty()) cert = rd.str("certification") ?: ""
            } else {
                for (r in j.obj("content_ratings").arr("results")) if (r.str("iso_3166_1") == country && cert.isEmpty())
                    cert = r.str("rating") ?: ""
            }
        }

        val logos = j.obj("images").arr("logos")
        fun logo(lang: String) = logos.firstOrNull { it.str("iso_639_1") == lang }?.str("file_path")
        val logoPath = logo("it") ?: logo("en") ?: logos.firstOrNull()?.str("file_path")

        val cast = j.obj("credits").arr("cast").take(24).mapNotNull { c ->
            val cid = c.int("id") ?: return@mapNotNull null
            CastMember(cid, c.str("name") ?: "", c.str("character") ?: "", c.str("profile_path"))
        }
        val directors = if (type == "movie") j.obj("credits").arr("crew").filter { it.str("job") == "Director" }
            .mapNotNull { c -> c.int("id")?.let { CastMember(it, c.str("name") ?: "", "Regista", c.str("profile_path")) } }.distinctBy { it.id }
        else j.arr("created_by").mapNotNull { c -> c.int("id")?.let { CastMember(it, c.str("name") ?: "", "Ideatore", c.str("profile_path")) } }

        // solo trailer in italiano; se TMDB non ne ha, li chiedo all'addon dei trailer (es. Streailer)
        val vids = j.obj("videos").arr("results").filter { it.str("site") == "YouTube" && it.str("iso_639_1") == "it" }
        var trailer = (vids.filter { it.str("type") == "Trailer" } + vids.filter { it.str("type") == "Teaser" }).firstOrNull()?.str("key")
        if (trailer == null && TrailerAddon.isConfigured) {
            val imdb = j.str("imdb_id") ?: j.obj("external_ids").str("imdb_id")
            if (!imdb.isNullOrEmpty()) trailer = runCatching { TrailerAddon.firstTrailer(imdb, type) }.getOrNull()
        }

        val seasons = if (type == "tv") j.arr("seasons").mapNotNull { s ->
            val sid = s.int("id") ?: return@mapNotNull null
            val n = s.int("season_number") ?: return@mapNotNull null
            SeasonInfo(sid, n, if (n == 0) "Speciali" else (s.str("name") ?: "Stagione $n"), s.int("episode_count") ?: 0)
        } else emptyList()

        val info = mutableListOf<InfoRow>()
        fun add(l: String, v: String) { if (v.isNotEmpty()) info += InfoRow(l, v) }
        fun statusIT(s: String) = when (s) {
            "Released" -> "Uscito"; "Ended" -> "Conclusa"; "Returning Series" -> "In corso (confermata)"
            "Canceled", "Cancelled" -> "Cancellata"; "In Production" -> "In produzione"
            "Post Production" -> "In post-produzione"; "Planned" -> "Annunciata"; "Pilot" -> "Episodio pilota"
            else -> s
        }
        fun money(v: Double?): String {
            if (v == null || v <= 0) return ""
            return if (v >= 1_000_000) "$ " + String.format(Locale.ITALIAN, "%.1f", v / 1_000_000).removeSuffix(",0") + " milioni"
                   else "$ " + String.format(Locale.ITALIAN, "%,.0f", v)
        }
        val itLoc = Locale.ITALIAN
        if (type == "movie") {
            add("Regia", directors.joinToString(", ") { it.name })
            add("Stato", statusIT(j.str("status") ?: ""))
            add("Data di uscita", prettyDate(j.str("release_date") ?: ""))
            runtime?.takeIf { it > 0 }?.let { r -> add("Durata", if (r >= 60) "${r / 60} h ${r % 60} min" else "$r min") }
            add("Budget", money(j.dbl("budget")))
            add("Incassi nel mondo", money(j.dbl("revenue")))
        } else {
            add("Ideatori", directors.joinToString(", ") { it.name })
            add("Stato", statusIT(j.str("status") ?: ""))
            val ns = j.int("number_of_seasons") ?: 0
            val ne = j.int("number_of_episodes") ?: 0
            if (ns > 0) add("Stagioni ed episodi", "$ns ${if (ns == 1) "stagione" else "stagioni"} · $ne episodi")
            add("Prima messa in onda", prettyDate(j.str("first_air_date") ?: ""))
            val next = j.obj("next_episode_to_air").str("air_date")
            if (!next.isNullOrEmpty()) add("Prossimo episodio", prettyDate(next)) else add("Ultima messa in onda", prettyDate(j.str("last_air_date") ?: ""))
            add("Rete", j.arr("networks").mapNotNull { it.str("name") }.take(3).joinToString(", "))
        }
        j.str("original_language")?.let { code ->
            val n = Locale(code).getDisplayLanguage(itLoc)
            if (n.isNotEmpty() && n != code) add("Lingua originale", n.replaceFirstChar { it.uppercase() })
        }
        val countries = j.arr("production_countries").mapNotNull { c -> c.str("iso_3166_1")?.let { Locale("", it).getDisplayCountry(itLoc) } ?: c.str("name") }
        add(if (countries.size > 1) "Paesi di produzione" else "Paese di produzione", countries.joinToString(", "))
        add("Produzione", j.arr("production_companies").mapNotNull { it.str("name") }.take(4).joinToString(", "))

        return MediaDetail(
            item = base, tagline = j.str("tagline") ?: "", overview = j.str("overview") ?: "",
            genres = genres, runtime = runtime, certification = cert, logoPath = logoPath,
            cast = cast, directors = directors,
            similar = parse(j.obj("similar").arr("results"), type),
            recommendations = parse(j.obj("recommendations").arr("results"), type),
            seasons = seasons, info = info,
            collectionID = j.obj("belongs_to_collection").int("id"),
            collectionName = j.obj("belongs_to_collection").str("name") ?: "",
            trailerKey = trailer
        )
    }

    suspend fun collection(id: Int): List<MediaItem> {
        val parts = get("collection/$id")?.arr("parts") ?: return emptyList()
        return parse(parts.sortedByDescending { (it.str("release_date") ?: "9999").ifEmpty { "9999" } }, "movie")
    }

    suspend fun episodes(tvID: Int, season: Int): List<Episode> {
        val j = get("tv/$tvID/season/$season") ?: return emptyList()
        return j.arr("episodes").mapNotNull { e ->
            val id = e.int("id") ?: return@mapNotNull null
            val n = e.int("episode_number") ?: return@mapNotNull null
            Episode(id, n, season, e.str("name") ?: "Episodio $n", e.str("overview") ?: "", e.str("still_path"),
                e.int("runtime"), e.dbl("vote_average") ?: 0.0, e.str("air_date") ?: "")
        }
    }

    suspend fun person(id: Int): PersonDetail? {
        val j = get("person/$id", mapOf("append_to_response" to "combined_credits")) ?: return null
        var bio = j.str("biography") ?: ""
        var english = false
        if (bio.isEmpty()) {
            bio = get("person/$id", mapOf("language" to "en-US"))?.str("biography") ?: ""
            english = bio.isNotEmpty()
        }
        val seen = HashSet<String>()
        val cc = j.obj("combined_credits")
        val all = cc.arr("cast") + cc.arr("crew").filter { it.str("job") in setOf("Director", "Creator", "Writer", "Screenplay") }
        val credits = all.mapNotNull { MediaItem.from(it, null) }
            .filter { it.posterPath != null && seen.add(it.id) && it.genreIDs.none { g -> g in setOf(10767, 10763, 10764) } }
            .sortedByDescending { it.popularity }
        return PersonDetail(id, j.str("name") ?: "", bio, j.str("profile_path"), j.str("birthday") ?: "",
            j.str("place_of_birth") ?: "", j.str("known_for_department") ?: "", credits, english)
    }

    suspend fun imdbID(type: String, id: Int): String? =
        get("$type/$id/external_ids")?.str("imdb_id")?.takeIf { it.startsWith("tt") }

    suspend fun findByIMDb(imdb: String, type: String): MediaItem? {
        val j = get("find/$imdb", mapOf("external_source" to "imdb_id")) ?: return null
        return parse(j.arr(if (type == "movie") "movie_results" else "tv_results"), type).firstOrNull()
    }
}
