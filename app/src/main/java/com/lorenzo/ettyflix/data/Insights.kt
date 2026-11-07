package com.lorenzo.ettyflix.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lorenzo.ettyflix.EttyFlixApp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val isoDay = SimpleDateFormat("yyyy-MM-dd", Locale.US)
fun isoDayOf(ms: Long): String = synchronized(isoDay) { isoDay.format(Date(ms)) }
fun todayIso(): String = isoDayOf(System.currentTimeMillis())

// MARK: - Calendario

data class CalendarEpisode(
    val show: MediaItem, val season: Int, val episode: Int, val name: String, val overview: String,
    val airDate: String, val stillPath: String?, val isPremiere: Boolean, val isFinale: Boolean
) {
    val id: String get() = "${show.tmdbID}-$season-$episode"
    val code: String get() = String.format(Locale.US, "S%02dE%02d", season, episode)
}

/** Prossima uscita di una serie seguita: l'episodio (se la data è confermata) oppure "in arrivo". */
data class UpcomingShow(val show: MediaItem, val next: CalendarEpisode?)

/** Uscite delle serie della watchlist e di quelle guardate (dal profilo attivo). */
object CalendarModel {
    var episodes by mutableStateOf(listOf<CalendarEpisode>())
        private set
    var upcoming by mutableStateOf(listOf<UpcomingShow>())
        private set
    var loading by mutableStateOf(false)
        private set
    private var loadedFor = ""
    private var loadedAt = 0L

    private class ShowInfo(val show: MediaItem?, val episodes: List<CalendarEpisode>, val continuing: Boolean)

    suspend fun load(force: Boolean = false) {
        val ids = LinkedHashSet<Int>()
        Store.watchlist.filter { it.mediaType == "tv" }.forEach { ids.add(it.tmdbID) }
        // serie di cui hai visto davvero qualcosa: almeno un episodio al 75% (non 2 minuti di prova)
        WatchProgress.entries.values.filter { it.mediaType == "tv" && (it.finished || it.fraction >= 0.75) }.forEach { ids.add(it.tmdbID) }
        val key = Profiles.currentID + ":" + ids.joinToString(",")
        if (!force && key == loadedFor && System.currentTimeMillis() - loadedAt < 3_600_000) return
        loading = true
        try {
            val sem = Semaphore(6)
            val results = coroutineScope {
                ids.take(150).map { id -> async { sem.withPermit { info(id) } } }.awaitAll()
            }
            val seen = HashSet<String>()
            episodes = results.flatMap { it.episodes }.filter { seen.add(it.id) }
                .sortedWith(compareBy({ it.airDate }, { it.show.title }))
            val today = todayIso()
            val dated = ArrayList<UpcomingShow>()
            val tbd = ArrayList<UpcomingShow>()
            for (r in results) {
                val show = r.show ?: continue
                val next = r.episodes.filter { it.airDate >= today }
                    .minWithOrNull(compareBy({ it.airDate }, { it.season }, { it.episode }))
                if (next != null) dated.add(UpcomingShow(show, next)) else if (r.continuing) tbd.add(UpcomingShow(show, null))
            }
            upcoming = dated.sortedWith(compareBy({ it.next?.airDate ?: "" }, { it.show.title })) + tbd.sortedBy { it.show.title }
            loadedFor = key
            loadedAt = System.currentTimeMillis()
        } finally {
            loading = false
        }
    }

    private suspend fun info(showID: Int): ShowInfo {
        val j = TMDB.get("tv/$showID") ?: return ShowInfo(null, emptyList(), false)
        val show = MediaItem.from(j, "tv") ?: return ShowInfo(null, emptyList(), false)
        val status = j.optString("status")
        val next = j.optJSONObject("next_episode_to_air")?.optInt("season_number", -1)?.takeIf { it > 0 }
        val last = j.optJSONObject("last_episode_to_air")?.optInt("season_number", -1)?.takeIf { it > 0 }
        val continuing = next != null || status in listOf("Returning Series", "In Production", "Planned")
        if (next == null && status in listOf("Ended", "Canceled")) {
            val limit = isoDayOf(System.currentTimeMillis() - 90L * 86_400_000)
            if (j.optString("last_air_date") < limit) return ShowInfo(show, emptyList(), continuing)
        }
        val out = ArrayList<CalendarEpisode>()
        for (s in listOfNotNull(last, next).distinct()) {
            val sj = TMDB.get("tv/$showID/season/$s") ?: continue
            val eps = sj.optJSONArray("episodes") ?: continue
            var maxN = 0
            for (i in 0 until eps.length()) maxN = maxOf(maxN, eps.getJSONObject(i).optInt("episode_number"))
            for (i in 0 until eps.length()) {
                val e = eps.getJSONObject(i)
                val n = e.optInt("episode_number", -1)
                val date = e.optString("air_date")
                if (n <= 0 || date.isEmpty() || date == "null") continue
                out.add(CalendarEpisode(show, s, n, e.optString("name"), e.optString("overview"), date,
                    e.optString("still_path").takeIf { it.isNotEmpty() && it != "null" }, n == 1, n == maxN && maxN > 1))
            }
        }
        return ShowInfo(show, out, continuing)
    }

    /** Gruppi: usciti questa settimana, poi un gruppo per giorno, poi "in arrivo". */
    fun groups(): List<Pair<String, List<UpcomingShow>>> {
        val today = todayIso()
        val weekAgo = isoDayOf(System.currentTimeMillis() - 7L * 86_400_000)
        val out = ArrayList<Pair<String, List<UpcomingShow>>>()
        val seen = HashSet<Int>()
        val recent = episodes.filter { it.airDate >= weekAgo && it.airDate < today }.sortedByDescending { it.airDate }
            .filter { seen.add(it.show.tmdbID) }.map { UpcomingShow(it.show, it) }
        if (recent.isNotEmpty()) out.add("Usciti questa settimana" to recent)
        upcoming.filter { it.next != null }.groupBy { it.next!!.airDate }.toSortedMap()
            .forEach { (d, l) -> out.add(longDate(d).replaceFirstChar { it.uppercase() } to l) }
        val tbd = upcoming.filter { it.next == null }
        if (tbd.isNotEmpty()) out.add("In arrivo · data da confermare" to tbd)
        return out
    }

    fun longDate(s: String): String {
        val today = todayIso()
        if (s == today) return "Oggi"
        if (s == isoDayOf(System.currentTimeMillis() + 86_400_000)) return "Domani"
        val d = runCatching { synchronized(isoDay) { isoDay.parse(s) } }.getOrNull() ?: return s
        return SimpleDateFormat("EEEE d MMMM", Locale.ITALIAN).format(d)
    }
}

// MARK: - Statistiche

enum class StatsPeriod(val title: String) { MONTH("Questo mese"), YEAR("Quest'anno"), ALL("Sempre") }

data class StatCount(val label: String, val value: Double, val detail: String = "")

/** ore precise in parole: "5 ore e 20 minuti", "45 minuti" */
fun statExact(hours: Double): String {
    val total = Math.round(hours * 60).toInt()
    if (total <= 0) return "Niente"
    val h = total / 60; val m = total % 60
    val hs = if (h == 1) "1 ora" else "$h ore"
    val ms = if (m == 1) "1 minuto" else "$m minuti"
    return when { h == 0 -> ms; m == 0 -> hs; else -> "$hs e $ms" }
}

/** ore brevi: "5 h 20 min", "45 min" */
fun statShort(hours: Double): String {
    val total = Math.round(hours * 60).toInt()
    if (total <= 0) return "0 min"
    val h = total / 60; val m = total % 60
    return when { h == 0 -> "$m min"; m == 0 -> "$h h"; else -> "$h h $m min" }
}
data class StatTop(val id: Int, val title: String, val posterPath: String?, val count: Int, val minutes: Double)

data class StatsSummary(
    val minutes: Double = 0.0, val movies: Int = 0, val episodes: Int = 0, val series: Int = 0,
    val activeDays: Int = 0, val streak: Int = 0,
    val monthly: List<StatCount> = emptyList(), val genres: List<StatCount> = emptyList(),
    val allGenres: List<StatCount> = emptyList(), val weekdays: List<StatCount> = emptyList(),
    val topSeries: List<StatTop> = emptyList(), val recentMovies: List<StatTop> = emptyList()
) {
    val hoursText: String get() = if (minutes >= 60) "${(minutes / 60).toInt()}" else "${minutes.toInt()}"
    val hoursUnit: String get() = if (minutes >= 60) (if ((minutes / 60).toInt() == 1) "ora" else "ore") else "minuti"
}

/** Una visione (film o episodio), dalla cronologia di Trakt o dai progressi. */
data class StatsPlay(val type: String, val tmdbID: Int, val season: Int?, val episode: Int?, val title: String,
                     val ms: Long, val minutes: Double, val finished: Boolean) {
    val metaID get() = "$type-$tmdbID"
    val playKey get() = if (type == "movie") "m$tmdbID" else "e$tmdbID-${season ?: 0}-${episode ?: 0}"
}

/** Statistiche di visione del profilo attivo: con Trakt uso tutta la cronologia dell'account,
 *  così i numeri sono identici su Mac, Apple TV, iPhone e Fire Stick. */
object StatsModel {
    private data class Meta(val runtime: Int, val genres: List<String>, val poster: String?)
    private val meta = HashMap<String, Meta>()
    var version by mutableStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    private const val KEY = "stats_meta_v1"
    private var history: List<StatsPlay> = emptyList()
    private var historyProfile = ""
    private var historyAt = 0L

    init {
        runCatching {
            val o = JSONObject(EttyFlixApp.prefs.getString(KEY, "{}") ?: "{}")
            for (k in o.keys()) {
                val m = o.getJSONObject(k)
                val g = m.optJSONArray("g")
                meta[k] = Meta(m.optInt("r"), (0 until (g?.length() ?: 0)).map { g!!.getString(it) },
                    m.optString("p", "").ifEmpty { null })
            }
        }
    }

    private fun historyFile() = java.io.File(EttyFlixApp.instance.filesDir, Profiles.key("stats_history.json"))

    private fun loadSavedHistory() {
        if (historyProfile == Profiles.currentID) return
        historyProfile = Profiles.currentID
        historyAt = 0L
        history = runCatching {
            val arr = org.json.JSONArray(historyFile().readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                StatsPlay(o.getString("t"), o.getInt("i"), o.optInt("s", -1).takeIf { it >= 0 }, o.optInt("e", -1).takeIf { it >= 0 },
                    o.optString("n"), o.getLong("d"), o.optDouble("m", 0.0), true)
            }
        }.getOrDefault(emptyList())
        version++
    }

    suspend fun refreshHistory(force: Boolean = false) {
        loadSavedHistory()
        if (!Trakt.isLoggedIn) return
        if (!force && System.currentTimeMillis() - historyAt < 90_000) return
        historyAt = System.currentTimeMillis()
        val pid = Profiles.currentID
        val raw = Trakt.fullHistory() ?: return
        if (pid != Profiles.currentID) return
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val out = ArrayList<StatsPlay>()
        for (h in raw) {
            val ms = runCatching { fmt.parse(h.optString("watched_at").take(19))!!.time }.getOrNull() ?: continue
            if (h.optString("type") == "movie") {
                val m = h.optJSONObject("movie") ?: continue
                val id = m.optJSONObject("ids")?.optInt("tmdb", 0) ?: 0
                if (id > 0) out.add(StatsPlay("movie", id, null, null, m.optString("title"), ms, m.optInt("runtime", 0).toDouble(), true))
            } else if (h.optString("type") == "episode") {
                val sh = h.optJSONObject("show") ?: continue
                val ep = h.optJSONObject("episode") ?: continue
                val id = sh.optJSONObject("ids")?.optInt("tmdb", 0) ?: 0
                if (id > 0) out.add(StatsPlay("tv", id, ep.optInt("season"), ep.optInt("number"), sh.optString("title"), ms,
                    ep.optInt("runtime", 0).toDouble(), true))
            }
        }
        history = out
        runCatching {
            val arr = org.json.JSONArray()
            out.forEach { p ->
                arr.put(JSONObject().put("t", p.type).put("i", p.tmdbID).put("s", p.season ?: -1).put("e", p.episode ?: -1)
                    .put("n", p.title).put("d", p.ms).put("m", p.minutes))
            }
            historyFile().writeText(arr.toString())
        }
        version++
    }

    private fun fromEntry(e: WatchEntry) = StatsPlay(e.mediaType, e.tmdbID, e.season, e.episode, e.title, refToMillis(e.updated),
        if (e.finished) 0.0 else (if (e.duration > 60) minOf(e.position, e.duration) else e.position) / 60, e.finished)

    /** cronologia di Trakt + quello che stai guardando a metà + quello finito qui che Trakt non ha ancora */
    private fun plays(): List<StatsPlay> {
        val h = if (historyProfile == Profiles.currentID) history else emptyList()
        val entries = WatchProgress.entries.values
        if (!Trakt.isLoggedIn || h.isEmpty()) return entries.filter { it.finished || it.position > 60 }.map { fromEntry(it) }
        val known = h.map { it.playKey }.toHashSet()
        val out = ArrayList(h)
        for (e in entries) {
            val p = fromEntry(e)
            if (!e.finished && e.position > 60) out.add(p)
            else if (e.finished && p.playKey !in known) out.add(p)
        }
        return out
    }

    suspend fun loadMeta() {
        refreshHistory()
        val missing = plays().map { it.metaID }.toSet().filter { it !in meta }.take(600)
        if (missing.isEmpty()) return
        loading = true
        try {
            val sem = Semaphore(6)
            val found = coroutineScope {
                missing.map { id ->
                    async {
                        sem.withPermit {
                            val (type, tmdb) = id.split("-").let { it[0] to it.getOrElse(1) { "" } }
                            val j = TMDB.get("$type/$tmdb") ?: return@withPermit null
                            val ga = j.optJSONArray("genres")
                            val genres = (0 until (ga?.length() ?: 0)).map { ga!!.getJSONObject(it).optString("name") }
                            var rt = if (type == "tv") (j.optJSONArray("episode_run_time")?.optInt(0, 0) ?: 0)
                                .takeIf { it > 0 } ?: (j.optJSONObject("last_episode_to_air")?.optInt("runtime", 45) ?: 45)
                            else j.optInt("runtime", 0)
                            if (rt <= 0) rt = if (type == "tv") 45 else 110
                            id to Meta(rt, genres, j.optString("poster_path", "").ifEmpty { null })
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            found.forEach { (k, v) -> meta[k] = v }
            val o = JSONObject()
            meta.forEach { (k, v) -> o.put(k, JSONObject().put("r", v.runtime).put("g", org.json.JSONArray(v.genres)).put("p", v.poster ?: "")) }
            EttyFlixApp.prefs.edit().putString(KEY, o.toString()).apply()
            version++
        } finally {
            loading = false
        }
    }

    private fun minutes(p: StatsPlay): Double {
        if (p.minutes > 0) return p.minutes
        if (!p.finished) return 0.0
        meta[p.metaID]?.runtime?.let { return it.toDouble() }
        return if (p.type == "tv") 45.0 else 110.0
    }

    private fun poster(type: String, id: Int): String? = meta["$type-$id"]?.poster
        ?: WatchProgress.entries.values.firstOrNull { it.tmdbID == id && it.mediaType == type }?.posterPath

    fun summary(period: StatsPeriod): StatsSummary {
        val start: Long? = when (period) {
            StatsPeriod.MONTH -> Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
            StatsPeriod.YEAR -> Calendar.getInstance().apply { set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
            StatsPeriod.ALL -> null
        }
        val all = plays()
        val inPeriod = all.filter { start == null || it.ms >= start }
        var total = 0.0
        val movieIDs = HashSet<Int>()
        val episodeKeys = HashSet<String>()
        val days = HashSet<String>()
        val series = HashSet<Int>()
        val genreMin = HashMap<String, Double>()
        val seriesTitle = HashMap<Int, String>()
        val seriesEps = HashMap<Int, HashSet<String>>()
        val seriesMin = HashMap<Int, Double>()
        val lastMovie = HashMap<Int, StatsPlay>()
        val weekday = DoubleArray(7)
        for (p in inPeriod) {
            val m = minutes(p)
            total += m
            days.add(isoDayOf(p.ms))
            val dow = Calendar.getInstance().apply { timeInMillis = p.ms }.get(Calendar.DAY_OF_WEEK)
            weekday[(dow + 5) % 7] += m / 60   // 0 = lunedì
            if (p.type == "movie") {
                if (p.finished) {
                    movieIDs.add(p.tmdbID)
                    val cur = lastMovie[p.tmdbID]
                    if (cur == null || cur.ms < p.ms) lastMovie[p.tmdbID] = p
                }
            } else {
                series.add(p.tmdbID)
                seriesTitle[p.tmdbID] = p.title
                if (p.finished) { episodeKeys.add(p.playKey); seriesEps.getOrPut(p.tmdbID) { HashSet() }.add(p.playKey) }
                seriesMin[p.tmdbID] = (seriesMin[p.tmdbID] ?: 0.0) + m
            }
            meta[p.metaID]?.genres?.forEach { g -> genreMin[g] = (genreMin[g] ?: 0.0) + m }
        }
        // ultimi 12 mesi
        val monthF = SimpleDateFormat("MMM", Locale.ITALIAN)
        val monthFull = SimpleDateFormat("LLLL yyyy", Locale.ITALIAN)
        val monthly = (11 downTo 0).map { back ->
            val c0 = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                add(Calendar.MONTH, -back)
            }
            val c1 = (c0.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            val h = all.filter { it.ms in c0.timeInMillis until c1.timeInMillis }.sumOf { minutes(it) } / 60
            StatCount(monthF.format(c0.time).replace(".", "").replaceFirstChar { it.uppercase() }, h,
                monthFull.format(c0.time).replaceFirstChar { it.uppercase() })
        }
        // giorni di fila
        val allDays = all.map { isoDayOf(it.ms) }.toSet()
        var streak = 0
        val d = Calendar.getInstance()
        if (isoDayOf(d.timeInMillis) !in allDays) d.add(Calendar.DAY_OF_MONTH, -1)
        while (isoDayOf(d.timeInMillis) in allDays) { streak++; d.add(Calendar.DAY_OF_MONTH, -1) }
        val sortedGenres = genreMin.entries.sortedByDescending { it.value }.map { StatCount(it.key, it.value / 60) }
        return StatsSummary(
            minutes = total, movies = movieIDs.size, episodes = episodeKeys.size, series = series.size,
            activeDays = days.size, streak = streak, monthly = monthly,
            genres = sortedGenres.take(6), allGenres = sortedGenres,
            weekdays = listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom").zip(
                listOf("Lunedì", "Martedì", "Mercoledì", "Giovedì", "Venerdì", "Sabato", "Domenica")
            ).mapIndexed { i, (short, full) -> StatCount(short, weekday[i], full) },
            topSeries = series.sortedWith(compareByDescending<Int> { seriesMin[it] ?: 0.0 }.thenByDescending { seriesEps[it]?.size ?: 0 })
                .take(8).map { StatTop(it, seriesTitle[it] ?: "", poster("tv", it), seriesEps[it]?.size ?: 0, seriesMin[it] ?: 0.0) },
            recentMovies = lastMovie.values.sortedByDescending { it.ms }.take(12)
                .map { StatTop(it.tmdbID, it.title, poster("movie", it.tmdbID), 1, minutes(it)) }
        )
    }
}

/** "Completati": film visti fino alla fine e serie con tutti gli episodi usciti visti. */
object CompletedModel {
    var items by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    private const val KEY = "completed_aired_v1"
    private val aired = HashMap<Int, Pair<Int, Long>>()

    init {
        runCatching {
            val o = JSONObject(EttyFlixApp.prefs.getString(KEY, "{}") ?: "{}")
            for (k in o.keys()) { val a = o.getJSONObject(k); aired[k.toInt()] = a.getInt("c") to a.getLong("t") }
        }
    }

    private suspend fun airedCount(id: Int): Int? {
        aired[id]?.let { if (System.currentTimeMillis() - it.second < 86_400_000) return it.first }
        val j = TMDB.get("tv/$id") ?: return aired[id]?.first
        val last = j.optJSONObject("last_episode_to_air") ?: return null
        val lastS = last.optInt("season_number", 0)
        val lastE = last.optInt("episode_number", 0)
        if (lastS <= 0) return null
        var count = 0
        val seasons = j.optJSONArray("seasons")
        for (i in 0 until (seasons?.length() ?: 0)) {
            val s = seasons!!.getJSONObject(i)
            val n = s.optInt("season_number", 0)
            if (n in 1..lastS) count += if (n == lastS) lastE else s.optInt("episode_count", 0)
        }
        aired[id] = count to System.currentTimeMillis()
        val o = JSONObject()
        aired.forEach { (k, v) -> o.put(k.toString(), JSONObject().put("c", v.first).put("t", v.second)) }
        EttyFlixApp.prefs.edit().putString(KEY, o.toString()).apply()
        return count
    }

    suspend fun refresh() {
        val entries = WatchProgress.entries.values.toList()
        val done = ArrayList<Pair<MediaItem, Double>>()
        val seen = HashSet<Int>()
        entries.filter { it.mediaType == "movie" && it.finished }.sortedByDescending { it.updated }
            .forEach { if (seen.add(it.tmdbID)) done.add(it.media to it.updated) }
        val eps = HashMap<Int, HashSet<String>>()
        val latest = HashMap<Int, WatchEntry>()
        for (e in entries) {
            if (e.mediaType != "tv" || !e.finished || (e.season ?: 0) <= 0) continue
            eps.getOrPut(e.tmdbID) { HashSet() }.add("${e.season}-${e.episode}")
            val cur = latest[e.tmdbID]
            if (cur == null || cur.updated < e.updated) latest[e.tmdbID] = e
        }
        if (eps.isNotEmpty()) loading = true
        for ((id, set) in eps) {
            val total = airedCount(id) ?: continue
            if (total > 0 && set.size >= total) latest[id]?.let { done.add(it.media to it.updated) }
        }
        loading = false
        items = done.sortedByDescending { it.second }.map { it.first }
    }
}
