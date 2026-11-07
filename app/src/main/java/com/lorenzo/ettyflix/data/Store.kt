package com.lorenzo.ettyflix.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lorenzo.ettyflix.EttyFlixApp
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONObject
import java.io.File

/** Cartella dei dati dell'app (stessi nomi di file del Mac). */
object DataDir {
    val dir: File by lazy { File(EttyFlixApp.ctx.filesDir, "StrandMac").apply { mkdirs() } }
    fun file(name: String) = File(dir, name)
    fun read(name: String): String? = runCatching { file(name).takeIf { it.exists() }?.readText() }.getOrNull()
    fun write(name: String, text: String) { runCatching { file(name).writeText(text) } }
}

// MARK: Profili

object Profiles {
    var list by mutableStateOf(listOf<Profile>())
        private set
    var currentID by mutableStateOf("main")
        private set
    /** vero dopo aver scelto il profilo all'avvio */
    var chosen by mutableStateOf(false)

    val palette: List<Pair<Long, Long>> = listOf(
        0xFFF24040 to 0xFF990D1A, 0xFF408CFF to 0xFF1A3399, 0xFFFFB333 to 0xFFD9590D,
        0xFF4DD98C to 0xFF0D734D, 0xFFCC66FF to 0xFF661AA6, 0xFFFF73B3 to 0xFFB31A66
    )

    init {
        val p = EttyFlixApp.prefs
        val saved = p.getString("profiles", null)?.let {
            runCatching { AppJson.decodeFromString(ListSerializer(Profile.serializer()), it) }.getOrNull()
        }
        list = if (!saved.isNullOrEmpty()) saved else listOf(Profile("main", "Lorenzo", 1), Profile("mamma", "Mamma", 5))
        val cur = p.getString("profile_current", "main") ?: "main"
        currentID = if (list.any { it.id == cur }) cur else list.first().id
        save()
    }

    val current: Profile get() = list.firstOrNull { it.id == currentID } ?: list.first()

    fun prefix(id: String) = if (id == "main") "" else "p_${id}_"
    fun key(base: String) = prefix(currentID) + base
    fun file(name: String) = prefix(currentID) + name

    private fun save() {
        EttyFlixApp.prefs.edit()
            .putString("profiles", AppJson.encodeToString(ListSerializer(Profile.serializer()), list))
            .putString("profile_current", currentID).apply()
    }

    fun replaceList(l: List<Profile>) {
        if (l.isEmpty()) return
        list = l
        if (list.none { it.id == currentID }) currentID = list.first().id
        save()
    }

    fun add(name: String) {
        val n = name.trim()
        if (n.isEmpty() || list.size >= 6) return
        val used = list.map { it.color }.toSet()
        val color = palette.indices.firstOrNull { it !in used } ?: (list.size % palette.size)
        list = list + Profile(java.util.UUID.randomUUID().toString().take(8), n, color)
        save()
    }

    /** Cambia profilo e ricarica i dati personali. */
    fun select(p: Profile) {
        currentID = p.id
        save()
        WatchProgress.reload()
        Store.reloadProfileData()
        AI.reloadProfile()
        Trakt.reloadAccount()
        chosen = true
    }
}

// MARK: Stato generale

object Store {
    var shelves by mutableStateOf(listOf<Shelf>())
    var server by mutableStateOf<MediaServer?>(null)
    var watchlist by mutableStateOf(listOf<MediaItem>())
    var genreNames by mutableStateOf(mapOf<Int, String>())
    var reloadToken by mutableIntStateOf(0)
    var recents by mutableStateOf(listOf<String>())

    init {
        val text = DataDir.read("config.json") ?: EttyFlixApp.prefs.getString("config_backup", null)
        if (text != null) shelves = runCatching { StrandConfig.parse(text) }.getOrDefault(emptyList())
        server = EttyFlixApp.prefs.getString("jf_server", null)?.let {
            runCatching { AppJson.decodeFromString(MediaServer.serializer(), it) }.getOrNull()
        }
        reloadProfileData()
    }

    val hasKey: Boolean get() = TMDB.hasKey

    fun reloadProfileData() {
        watchlist = loadList(Profiles.file("watchlist.json"))
        recents = EttyFlixApp.prefs.getString(Profiles.key("recents"), "")!!.split("\n").filter { it.isNotBlank() }
    }

    fun saveKey(k: String) {
        EttyFlixApp.prefs.edit().putString("tmdb_api_key_fallback", k.trim()).apply()
    }

    suspend fun bootstrap() {
        if (hasKey && genreNames.isEmpty()) genreNames = TMDB.loadGenres()
    }

    fun reloadAll() { genreNames = emptyMap(); reloadToken++ }

    /** Importa una configurazione .strand (sezioni, server, TV in diretta). */
    fun importStrand(text: String) {
        val obj = JSONObject(text)
        val keep = JSONObject()
        if (obj.has("shelves")) keep.put("shelves", obj.get("shelves")) else throw IllegalArgumentException("File non valido")
        if (obj.has("heroConfiguration")) keep.put("heroConfiguration", obj.get("heroConfiguration"))
        val out = keep.toString()
        DataDir.write("config.json", out)
        EttyFlixApp.prefs.edit().putString("config_backup", out).apply()
        importServer(obj)
        LiveTV.configure(obj)
        shelves = StrandConfig.parse(out)
        reloadToken++
    }

    private fun importServer(obj: JSONObject) {
        val first = obj.arr("mediaServers").firstOrNull() ?: return
        val cfg = first.obj("config")
        val token = first.str("accessToken") ?: cfg.str("accessToken") ?: ""
        val uid = first.str("userId") ?: cfg.str("userId") ?: ""
        val url = (cfg.str("serverUrl") ?: "").trimEnd('/')
        if (token.isEmpty() || uid.isEmpty() || url.isEmpty()) return
        saveServer(MediaServer(cfg.str("name") ?: "Jellyfin", url, uid, token, cfg.str("username") ?: ""))
    }

    fun saveServer(s: MediaServer?) {
        server = s
        val e = EttyFlixApp.prefs.edit()
        if (s != null) e.putString("jf_server", AppJson.encodeToString(MediaServer.serializer(), s)) else e.remove("jf_server")
        e.apply()
    }

    fun genreLabel(m: MediaItem): String {
        val overrides = mapOf(878 to "Sci-Fi", 10765 to "Sci-Fi", 10759 to "Azione", 10762 to "Kids")
        for (g in m.genreIDs) {
            overrides[g]?.let { return it }
            genreNames[g]?.let { return it }
        }
        return if (m.mediaType == "movie") "Film" else "Serie TV"
    }

    // MARK: Ricerche recenti

    fun addRecent(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        recents = (listOf(t) + recents.filter { !it.equals(t, true) }).take(10)
        EttyFlixApp.prefs.edit().putString(Profiles.key("recents"), recents.joinToString("\n")).apply()
    }

    // MARK: Watchlist

    fun inWatchlist(m: MediaItem) = watchlist.any { it.id == m.id }

    fun toggleWatchlist(m: MediaItem) {
        val adding = !inWatchlist(m)
        watchlist = if (adding) listOf(m) + watchlist else watchlist.filter { it.id != m.id }
        saveList(watchlist, Profiles.file("watchlist.json"))
        Trakt.pushWatchlist(m, adding)
    }

    /** Watchlist uguale a quella dell'account Trakt del profilo (una volta sola). */
    fun replaceWatchlist(items: List<MediaItem>) {
        val l = items.distinctBy { it.id }
        watchlist = l; saveList(l, Profiles.file("watchlist.json"))
    }

    fun mergeWatchlist(items: List<MediaItem>) {
        val cur = watchlist.toMutableList()
        var changed = false
        for (m in items) if (cur.none { it.id == m.id }) { cur += m; changed = true }
        if (changed) { watchlist = cur; saveList(cur, Profiles.file("watchlist.json")) }
    }

    fun writeWatchlist(items: List<MediaItem>, profileID: String) {
        val name = Profiles.prefix(profileID) + "watchlist.json"
        val cur = loadList(name).toMutableList()
        for (m in items) if (cur.none { it.id == m.id }) cur += m
        saveList(cur, name)
    }

    private fun loadList(name: String): List<MediaItem> = DataDir.read(name)?.let {
        runCatching { AppJson.decodeFromString(ListSerializer(MediaItem.serializer()), it) }.getOrNull()
    } ?: emptyList()

    private fun saveList(list: List<MediaItem>, name: String) =
        DataDir.write(name, AppJson.encodeToString(ListSerializer(MediaItem.serializer()), list))
}

// MARK: Avanzamento di visione

object WatchProgress {
    val entries = mutableStateMapOf<String, WatchEntry>()
    var hiddenFromContinue by mutableStateOf(setOf<Int>())
    /** cambia ogni volta che i progressi cambiano (per aggiornare le righe) */
    var version by mutableIntStateOf(0)

    init { reload() }

    private val fileName get() = Profiles.file("progress.json")

    fun reload() {
        entries.clear()
        readEntries(Profiles.currentID).forEach { entries[it.id] = it }
        hiddenFromContinue = (EttyFlixApp.prefs.getString(Profiles.key("continue_hidden"), "") ?: "")
            .split(",").mapNotNull { it.toIntOrNull() }.toSet()
        version++
    }

    private fun save() {
        DataDir.write(fileName, AppJson.encodeToString(ListSerializer(WatchEntry.serializer()), entries.values.toList()))
        EttyFlixApp.prefs.edit().putString(Profiles.key("continue_hidden"), hiddenFromContinue.joinToString(",")).apply()
        version++
    }

    fun readEntries(profileID: String): List<WatchEntry> =
        DataDir.read(Profiles.prefix(profileID) + "progress.json")?.let {
            runCatching { AppJson.decodeFromString(ListSerializer(WatchEntry.serializer()), it) }.getOrNull()
        } ?: emptyList()

    fun writeEntries(list: List<WatchEntry>, profileID: String) {
        val map = readEntries(profileID).associateBy { it.id }.toMutableMap()
        for (e in list) {
            val cur = map[e.id]
            if (cur != null && cur.updated >= e.updated) continue
            map[e.id] = e
        }
        DataDir.write(Profiles.prefix(profileID) + "progress.json",
            AppJson.encodeToString(ListSerializer(WatchEntry.serializer()), map.values.toList()))
    }

    fun entry(tmdbID: Int, type: String, season: Int? = null, episode: Int? = null) =
        entries[WatchEntry.key(tmdbID, type, season, episode)]

    fun isWatched(tmdbID: Int, type: String, season: Int? = null, episode: Int? = null) =
        entry(tmdbID, type, season, episode)?.finished ?: false

    fun resumePosition(tmdbID: Int, type: String, season: Int? = null, episode: Int? = null): Double? {
        val e = entry(tmdbID, type, season, episode) ?: return null
        return if (!e.finished && e.position > 60) e.position else null
    }

    fun latest(tmdbID: Int): WatchEntry? = entries.values.filter { it.tmdbID == tmdbID }.maxByOrNull { it.updated }

    val continueWatching: List<WatchEntry>
        get() {
            val best = HashMap<Int, WatchEntry>()
            for (e in entries.values) {
                if (e.tmdbID in hiddenFromContinue) continue
                val cur = best[e.tmdbID]
                if (cur != null && cur.updated >= e.updated) continue
                best[e.tmdbID] = e
            }
            return best.values.filter { e ->
                if (e.mediaType == "movie") !e.finished && e.position > 60 else e.finished || e.position > 60
            }.sortedByDescending { it.updated }
        }

    val history: List<WatchEntry>
        get() {
            val best = HashMap<Int, WatchEntry>()
            for (e in entries.values) {
                if (!(e.finished || e.position > 60)) continue
                val cur = best[e.tmdbID]
                if (cur != null && cur.updated >= e.updated) continue
                best[e.tmdbID] = e
            }
            return best.values.sortedByDescending { it.updated }
        }

    fun update(req: PlayRequest, position: Double, duration: Double, urgent: Boolean = true) {
        val m = req.media
        if (m.tmdbID <= 0 || duration <= 60 || position <= 5) return
        val key = WatchEntry.key(m.tmdbID, m.mediaType, req.season, req.episode)
        val finished = position / duration >= 0.9
        val wasFinished = entries[key]?.finished ?: false
        val e = WatchEntry(key, m.tmdbID, m.mediaType, m.title, m.posterPath, m.backdropPath, req.logoPath,
            req.season, req.episode, req.episodeTitle, req.stillPath,
            if (finished) duration else position, duration, nowRef(), finished)
        entries[key] = e
        hiddenFromContinue = hiddenFromContinue - m.tmdbID
        save()
        if (finished && !wasFinished) Trakt.pushWatched(listOf(e), true)
        if (!finished) Trakt.pushProgress(e, urgent)
    }

    /** Progressi uguali a quelli dell'account Trakt del profilo (una volta sola). Restano quelli delle ultime 2 ore. */
    fun replaceEntries(list: List<WatchEntry>) {
        val recent = nowRef() - 2 * 3600
        val map = HashMap<String, WatchEntry>()
        for (e in entries.values) if (e.updated > recent) map[e.id] = e
        for (e in list) {
            val cur = map[e.id]
            if (cur != null && cur.updated >= e.updated) continue
            map[e.id] = e
        }
        entries.clear(); entries.putAll(map)
        save()
    }

    fun importEntries(list: List<WatchEntry>) {
        for (e in list) {
            val cur = entries[e.id]
            if (cur != null && cur.updated >= e.updated) continue
            if (cur != null && cur.finished && !e.finished) continue
            entries[e.id] = e
        }
        save()
    }

    fun setWatched(m: MediaItem, season: Int?, episode: Episode?, watched: Boolean) {
        val key = WatchEntry.key(m.tmdbID, m.mediaType, season, episode?.number)
        if (watched) {
            val dur = ((episode?.runtime ?: 1) * 60).toDouble()
            val e = WatchEntry(key, m.tmdbID, m.mediaType, m.title, m.posterPath, m.backdropPath, null,
                season, episode?.number, episode?.name ?: "", episode?.stillPath, dur, dur, nowRef(), true)
            entries[key] = e
            Trakt.pushWatched(listOf(e), true)
        } else {
            entries.remove(key)?.let { Trakt.pushWatched(listOf(it), false) }
        }
        save()
    }

    fun setSeason(m: MediaItem, eps: List<Episode>, watched: Boolean) {
        val today = todayString()
        val changed = mutableListOf<WatchEntry>()
        eps.forEachIndexed { i, ep ->
            val key = WatchEntry.key(m.tmdbID, m.mediaType, ep.season, ep.number)
            if (watched) {
                if (ep.airDate.isNotEmpty() && ep.airDate > today) return@forEachIndexed
                val dur = ((ep.runtime ?: 1) * 60).toDouble()
                val e = WatchEntry(key, m.tmdbID, m.mediaType, m.title, m.posterPath, m.backdropPath, null,
                    ep.season, ep.number, ep.name, ep.stillPath, dur, dur, nowRef() + i * 0.001, true)
                entries[key] = e
                changed += e
            } else {
                entries.remove(key)?.let { changed += it }
            }
        }
        save()
        Trakt.pushWatched(changed, watched)
    }

    /** Segna (o toglie) come visti tutti gli episodi usciti di tutte le stagioni. */
    suspend fun setSeries(m: MediaItem, watched: Boolean) {
        if (!watched) {
            val removed = entries.values.filter { it.tmdbID == m.tmdbID }
            if (removed.isEmpty()) return
            removed.forEach { entries.remove(it.id) }
            save()
            Trakt.pushWatched(removed, false)
            return
        }
        val seasons = TMDB.get("tv/${m.tmdbID}")?.arr("seasons")?.mapNotNull { it.int("season_number") }?.filter { it > 0 } ?: return
        val all = seasons.flatMap { TMDB.episodes(m.tmdbID, it) }
        if (all.isNotEmpty()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { setSeason(m, all, true) }
    }

    fun removeFromContinue(e: WatchEntry) {
        hiddenFromContinue = hiddenFromContinue + e.tmdbID
        save()
    }
}

/** Cosa riprodurre per una voce di "Continua a guardare". */
data class ContinueTarget(
    val entry: WatchEntry, val season: Int?, val episode: Int?, val episodeTitle: String,
    val stillPath: String?, val startAt: Double, val isNext: Boolean
)

suspend fun resolveContinue(e: WatchEntry): ContinueTarget? {
    if (!e.finished) return ContinueTarget(e, e.season, e.episode, e.episodeTitle, e.stillPath, e.position, false)
    if (e.mediaType != "tv" || e.season == null || e.episode == null) return null
    var next = TMDB.episodes(e.tmdbID, e.season).firstOrNull { it.number == e.episode + 1 }
    if (next == null) next = TMDB.episodes(e.tmdbID, e.season + 1).firstOrNull()
    val n = next ?: return null
    if (n.airDate.isNotEmpty() && n.airDate > todayString()) return null
    return ContinueTarget(e, n.season, n.number, n.name, n.stillPath, 0.0, true)
}
