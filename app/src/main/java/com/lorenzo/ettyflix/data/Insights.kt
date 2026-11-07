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

/** secondi dal 1/1/2001 (come sul Mac) → millisecondi */
private fun refToMillis(ref: Double): Long = ((ref + 978_307_200.0) * 1000).toLong()
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
        WatchProgress.history.filter { it.mediaType == "tv" }.forEach { ids.add(it.tmdbID) }
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

data class StatCount(val label: String, val value: Double)
data class StatTop(val id: Int, val title: String, val posterPath: String?, val count: Int, val minutes: Double)

data class StatsSummary(
    val minutes: Double = 0.0, val movies: Int = 0, val episodes: Int = 0, val series: Int = 0,
    val activeDays: Int = 0, val streak: Int = 0,
    val monthly: List<StatCount> = emptyList(), val genres: List<StatCount> = emptyList(),
    val topSeries: List<StatTop> = emptyList(), val recentMovies: List<StatTop> = emptyList()
) {
    val hoursText: String get() = if (minutes >= 60) "${(minutes / 60).toInt()}" else "${minutes.toInt()}"
    val hoursUnit: String get() = if (minutes >= 60) (if ((minutes / 60).toInt() == 1) "ora" else "ore") else "minuti"
}

/** Statistiche di visione del profilo attivo. */
object StatsModel {
    private data class Meta(val runtime: Int, val genres: List<String>)
    private val meta = HashMap<String, Meta>()
    var version by mutableStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    private const val KEY = "stats_meta_v1"

    init {
        runCatching {
            val o = JSONObject(EttyFlixApp.prefs.getString(KEY, "{}") ?: "{}")
            for (k in o.keys()) {
                val m = o.getJSONObject(k)
                val g = m.optJSONArray("g")
                meta[k] = Meta(m.optInt("r"), (0 until (g?.length() ?: 0)).map { g!!.getString(it) })
            }
        }
    }

    private fun idOf(e: WatchEntry) = "${e.mediaType}-${e.tmdbID}"

    suspend fun loadMeta() {
        val missing = WatchProgress.entries.values.map { idOf(it) }.toSet().filter { it !in meta }.take(400)
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
                            id to Meta(rt, genres)
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            found.forEach { (k, v) -> meta[k] = v }
            val o = JSONObject()
            meta.forEach { (k, v) -> o.put(k, JSONObject().put("r", v.runtime).put("g", org.json.JSONArray(v.genres))) }
            EttyFlixApp.prefs.edit().putString(KEY, o.toString()).apply()
            version++
        } finally {
            loading = false
        }
    }

    private fun minutes(e: WatchEntry): Double {
        if (e.duration > 60) return (if (e.finished) e.duration else minOf(e.position, e.duration)) / 60
        if (!e.finished) return e.position / 60
        return (meta[idOf(e)]?.runtime ?: if (e.mediaType == "tv") 45 else 110).toDouble()
    }

    fun summary(period: StatsPeriod): StatsSummary {
        val start: Long? = when (period) {
            StatsPeriod.MONTH -> Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
            StatsPeriod.YEAR -> Calendar.getInstance().apply { set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
            StatsPeriod.ALL -> null
        }
        val watched = WatchProgress.entries.values.filter { it.finished || it.position > 60 }
        val inPeriod = watched.filter { start == null || refToMillis(it.updated) >= start }
        var total = 0.0
        var movies = 0
        var episodes = 0
        val days = HashSet<String>()
        val series = HashSet<Int>()
        val genreMin = HashMap<String, Double>()
        val seriesCount = HashMap<Int, Triple<WatchEntry, Int, Double>>()
        for (e in inPeriod) {
            val m = minutes(e)
            total += m
            days.add(isoDayOf(refToMillis(e.updated)))
            if (e.mediaType == "movie") { if (e.finished) movies++ } else {
                if (e.finished) episodes++
                series.add(e.tmdbID)
                val cur = seriesCount[e.tmdbID]
                seriesCount[e.tmdbID] = Triple(cur?.first ?: e, (cur?.second ?: 0) + if (e.finished) 1 else 0, (cur?.third ?: 0.0) + m)
            }
            meta[idOf(e)]?.genres?.forEach { g -> genreMin[g] = (genreMin[g] ?: 0.0) + m }
        }
        // ultimi 12 mesi
        val monthF = SimpleDateFormat("MMM", Locale.ITALIAN)
        val monthly = (11 downTo 0).map { back ->
            val c0 = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                add(Calendar.MONTH, -back)
            }
            val c1 = (c0.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            val h = watched.filter { refToMillis(it.updated) in c0.timeInMillis until c1.timeInMillis }.sumOf { minutes(it) } / 60
            StatCount(monthF.format(c0.time).replace(".", "").replaceFirstChar { it.uppercase() }, h)
        }
        // giorni di fila
        val allDays = watched.map { isoDayOf(refToMillis(it.updated)) }.toSet()
        var streak = 0
        val d = Calendar.getInstance()
        if (isoDayOf(d.timeInMillis) !in allDays) d.add(Calendar.DAY_OF_MONTH, -1)
        while (isoDayOf(d.timeInMillis) in allDays) { streak++; d.add(Calendar.DAY_OF_MONTH, -1) }
        return StatsSummary(
            minutes = total, movies = movies, episodes = episodes, series = series.size,
            activeDays = days.size, streak = streak, monthly = monthly,
            genres = genreMin.entries.sortedByDescending { it.value }.take(6).map { StatCount(it.key, it.value / 60) },
            topSeries = seriesCount.values.sortedWith(compareByDescending<Triple<WatchEntry, Int, Double>> { it.third }.thenByDescending { it.second })
                .take(8).map { StatTop(it.first.tmdbID, it.first.title, it.first.posterPath, it.second, it.third) },
            recentMovies = inPeriod.filter { it.mediaType == "movie" && it.finished }.sortedByDescending { it.updated }.take(12)
                .map { StatTop(it.tmdbID, it.title, it.posterPath, 1, minutes(it)) }
        )
    }
}
