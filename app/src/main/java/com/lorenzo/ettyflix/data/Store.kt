package com.lorenzo.ettyflix.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lorenzo.ettyflix.EttyFlixApp
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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

    /** Nomi e immagini arrivati dal Mac: aggiorna quelli che ci sono, aggiunge i nuovi, non cancella niente. */
    fun mergeLooks(incoming: List<Profile>) {
        var l = list
        var changed = false
        for (p in incoming) {
            val i = l.indexOfFirst { it.id == p.id }
            if (i >= 0) {
                val old = l[i]
                if (old.name != p.name || old.avatar != p.avatar || old.photo != p.photo) {
                    l = l.toMutableList().also { it[i] = old.copy(name = p.name, avatar = p.avatar, photo = p.photo) }
                    changed = true
                }
            } else if (l.size < 6) { l = l + p; changed = true }
        }
        if (changed) { list = l; save() }
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
    /** voti 1–10 dati dall'utente (chiave = MediaItem.id, es. "movie-603") */
    var ratings by mutableStateOf(mapOf<String, Int>())
    /** titoli segnati "Non mi piace" */
    var disliked by mutableStateOf(listOf<MediaItem>())
    /** titoli abbandonati a metà */
    var abandoned by mutableStateOf(listOf<MediaItem>())
    /** titoli votati (nome e locandina), per le Statistiche "I tuoi voti" */
    var ratedItems by mutableStateOf(mapOf<String, MediaItem>())
    /** voti delle singole stagioni: "tv-123-s4" → 1…10 (la domanda di fine stagione) */
    var seasonRatings by mutableStateOf(mapOf<String, Int>())

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
        disliked = loadList(Profiles.file("disliked.json"))
        abandoned = loadList(Profiles.file("abandoned.json"))
        ratings = DataDir.read(Profiles.file("ratings.json"))?.let { t ->
            runCatching { val o = JSONObject(t); o.keys().asSequence().associateWith { o.getInt(it) } }.getOrNull()
        } ?: emptyMap()
        seasonRatings = DataDir.read(Profiles.file("season_ratings.json"))?.let { t ->
            runCatching { val o = JSONObject(t); o.keys().asSequence().associateWith { o.getInt(it) } }.getOrNull()
        } ?: emptyMap()
        ratedItems = DataDir.read(Profiles.file("rated_items.json"))?.let { t ->
            runCatching { AppJson.decodeFromString(MapSerializer(String.serializer(), MediaItem.serializer()), t) }.getOrNull()
        } ?: emptyMap()
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

    /** "Da guardare": la watchlist senza i titoli già iniziati e senza quelli "Non mi piace"/abbandonati. */
    val toWatch: List<MediaItem>
        get() {
            val started = WatchProgress.startedIDs   // un clic per sbaglio non conta (vedi WatchProgress.counts)
            val skip = notInterestedIDs
            return watchlist.filter { it.tmdbID !in started && it.id !in skip }
        }

    // MARK: Voti, Non mi piace, Abbandonati

    fun rating(m: MediaItem): Int = ratings[m.id] ?: 0

    /** auto: voto della serie calcolato dalla media delle stagioni (finché non la voti tu) */
    fun setRating(m: MediaItem, value: Int, auto: Boolean = false) {
        val r = ratings.toMutableMap()
        if (value in 1..10) r[m.id] = value else r.remove(m.id)
        ratings = r
        saveRatings()
        val autoSet = autoSeries().toMutableSet()
        if (auto && value in 1..10) autoSet += m.id else autoSet -= m.id
        EttyFlixApp.prefs.edit().putString(autoSeriesKey, autoSet.joinToString("\n")).apply()
        if (value in 1..10) {
            // ricordo nome e locandina (se il titolo arriva senza, tengo quelli che avevo)
            val keep = if (m.posterPath == null) ratedItems[m.id] ?: m else m
            ratedItems = ratedItems + (m.id to keep)
            saveRatedItems()
        }
        Trakt.pushRating(m, if (value in 1..10) value else null)   // il voto va anche su Trakt
    }

    private fun saveRatings() {
        val o = JSONObject(); ratings.forEach { (k, v) -> o.put(k, v) }
        DataDir.write(Profiles.file("ratings.json"), o.toString())
    }

    private fun saveRatedItems() {
        DataDir.write(Profiles.file("rated_items.json"), AppJson.encodeToString(MapSerializer(String.serializer(), MediaItem.serializer()), ratedItems))
    }

    // MARK: Voti delle stagioni

    private val autoSeriesKey get() = Profiles.key("series_rating_auto")
    private fun autoSeries(): Set<String> =
        (EttyFlixApp.prefs.getString(autoSeriesKey, "") ?: "").split("\n").filter { it.isNotBlank() }.toSet()
    fun seasonKey(m: MediaItem, season: Int) = "${m.id}-s$season"
    fun seasonRating(m: MediaItem, season: Int): Int = seasonRatings[seasonKey(m, season)] ?: 0
    /** il voto della serie è la media delle stagioni? (false se l'hai votata tu) */
    fun seriesRatingIsAuto(m: MediaItem) = m.id in autoSeries()

    /** Voto di una stagione. Se la serie non l'hai votata tu, il suo voto diventa la media delle stagioni. */
    fun setSeasonRating(m: MediaItem, season: Int, value: Int, push: Boolean = true) {
        val k = seasonKey(m, season)
        val r = seasonRatings.toMutableMap()
        if (value in 1..10) r[k] = value else r.remove(k)
        seasonRatings = r
        val o = JSONObject(); seasonRatings.forEach { (kk, v) -> o.put(kk, v) }
        DataDir.write(Profiles.file("season_ratings.json"), o.toString())
        if (push) Trakt.pushSeasonRating(m, season, if (value in 1..10) value else null)
        if (rating(m) != 0 && !seriesRatingIsAuto(m)) return
        val mine = seasonRatings.filterKeys { it.startsWith(m.id + "-s") }.values
        if (mine.isEmpty()) setRating(m, 0)
        else {
            val avg = Math.round(mine.sum().toDouble() / mine.size).toInt()
            if (rating(m) != avg || !seriesRatingIsAuto(m)) setRating(m, avg, auto = true)
        }
    }

    /** Voti di stagione arrivati da Trakt: riempiono quelli che mancano. */
    fun mergeSeasonRatings(found: Map<String, Int>, items: Map<String, MediaItem>) {
        for ((k, v) in found) {
            if (k in seasonRatings || v !in 1..10) continue
            val i = k.lastIndexOf("-s"); if (i < 0) continue
            val s = k.substring(i + 2).toIntOrNull() ?: continue
            val m = items[k.substring(0, i)] ?: continue
            setSeasonRating(m, s, v, push = false)
        }
    }

    /** Voti arrivati da Trakt: riempiono solo quelli che mancano (quelli dati nell'app restano). */
    fun mergeRatings(found: Map<String, Int>, items: Map<String, MediaItem>) {
        val add = found.filter { it.key !in ratings && it.value in 1..10 }
        if (add.isNotEmpty()) { ratings = ratings + add; saveRatings() }
        val newItems = items.filter { it.key !in ratedItems }
        if (newItems.isNotEmpty()) { ratedItems = ratedItems + newItems; saveRatedItems() }
    }

    /** Completa nome e locandina dei titoli votati che ne sono senza (es. voti arrivati da Trakt). */
    suspend fun fillRatedItems() {
        val missing = ratings.keys.filter { ratedItems[it]?.posterPath == null }.take(40)
        if (missing.isEmpty()) return
        val add = HashMap<String, MediaItem>()
        for (k in missing) {
            val type = k.substringBefore("-")
            val id = k.substringAfter("-").toIntOrNull() ?: continue
            val j = TMDB.get("$type/$id") ?: continue
            val m = MediaItem.from(j, type) ?: continue
            add[k] = m.copy(genreIDs = j.arr("genres").mapNotNull { it.int("id") })
        }
        if (add.isNotEmpty()) { ratedItems = ratedItems + add; saveRatedItems() }
    }

    fun isDisliked(m: MediaItem) = disliked.any { it.id == m.id }
    fun isAbandoned(m: MediaItem) = abandoned.any { it.id == m.id }
    fun isNotInterested(m: MediaItem) = isDisliked(m) || isAbandoned(m)
    val notInterestedIDs: Set<String> get() = (disliked.map { it.id } + abandoned.map { it.id }).toSet()
    /** Categoria della Libreria "Non mi piace / Abbandonato". */
    val notInterested: List<MediaItem> get() = (disliked + abandoned).distinctBy { it.id }

    fun toggleDisliked(m: MediaItem) {
        disliked = if (isDisliked(m)) disliked.filter { it.id != m.id } else listOf(m) + disliked
        saveList(disliked, Profiles.file("disliked.json"))
        if (isDisliked(m)) {
            WatchProgress.hide(m.tmdbID)
            if (inWatchlist(m)) toggleWatchlist(m)
        }
    }

    /** Abbandona: via da "Continua a guardare" e dalla watchlist, finisce in "Non mi piace / Abbandonato". */
    fun markAbandoned(m: MediaItem) {
        if (!isAbandoned(m)) { abandoned = listOf(m) + abandoned; saveList(abandoned, Profiles.file("abandoned.json")) }
        WatchProgress.hide(m.tmdbID)
        if (inWatchlist(m)) toggleWatchlist(m)
    }

    fun clearNotInterested(m: MediaItem) {
        disliked = disliked.filter { it.id != m.id }; saveList(disliked, Profiles.file("disliked.json"))
        abandoned = abandoned.filter { it.id != m.id }; saveList(abandoned, Profiles.file("abandoned.json"))
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

    /** Un titolo conta come "iniziato" solo se l'hai guardato davvero: un film almeno 10 minuti (o metà, se è più corto),
     *  un episodio almeno 30 minuti (o il 60%, se è più corto), oppure finito. Prima bastava 1 minuto: un clic per sbaglio
     *  lo metteva in "Visti di recente", lo toglieva da "Da guardare" e faceva sparire la percentuale "per te". */
    fun counts(e: WatchEntry): Boolean {
        if (e.finished) return true
        val movie = e.mediaType == "movie"
        val need = if (movie) 600.0 else 1800.0
        val limit = if (e.duration > 0) minOf(need, e.duration * (if (movie) 0.5 else 0.6)) else need
        return e.position >= limit
    }

    /** tmdbID dei titoli davvero iniziati */
    val startedIDs: Set<Int> get() = entries.values.filter { counts(it) }.map { it.tmdbID }.toSet()
    /** "tipo-id" dei titoli davvero iniziati */
    val startedKeys: Set<String> get() = entries.values.filter { counts(it) }.map { "${it.mediaType}-${it.tmdbID}" }.toSet()

    val continueWatching: List<WatchEntry>
        get() {
            val started = startedIDs
            val best = HashMap<Int, WatchEntry>()
            for (e in entries.values) {
                if (e.tmdbID in hiddenFromContinue || e.tmdbID !in started) continue
                val cur = best[e.tmdbID]
                if (cur != null && cur.updated >= e.updated) continue
                best[e.tmdbID] = e
            }
            return best.values.filter { e ->
                if (e.mediaType == "movie") !e.finished
                else {
                    // serie finita (tutti gli episodi usciti visti) e nessun episodio a metà: fuori
                    val aired = CompletedModel.knownAired(e.tmdbID)
                    !(e.finished && aired != null && aired > 0 && EpisodeCounts.watched(e.tmdbID) >= aired)
                }
            }.sortedByDescending { it.updated }
        }

    val history: List<WatchEntry>
        get() {
            val started = startedIDs
            val best = HashMap<Int, WatchEntry>()
            for (e in entries.values) {
                if (e.tmdbID !in started) continue
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
        // anche se era già vista (es. segnata da Trakt): alla chiusura del player chiede "Ti è piaciuto?"
        if (finished) RatingPrompt.finished(e)
        // primo episodio di una nuova stagione: chiede il voto della stagione prima
        if (!finished && e.episode == 1 && position < 600) RatingPrompt.seasonStarted(e)
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

    fun hide(tmdbID: Int) {
        if (tmdbID in hiddenFromContinue) return
        hiddenFromContinue = hiddenFromContinue + tmdbID
        save()
    }

    fun isInProgress(tmdbID: Int) = continueWatching.any { it.tmdbID == tmdbID }

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
    // serie di cui hai visto tutti gli episodi usciti: niente "prossimo episodio" (torna quando esce il nuovo)
    val aired = CompletedModel.airedCount(e.tmdbID)
    if (aired != null && aired > 0 && EpisodeCounts.watched(e.tmdbID) >= aired) return null
    // il primo episodio dopo l'ultimo visto che NON hai ancora visto (salta quelli già visti), fino a 3 stagioni avanti
    fun seen(sn: Int, en: Int) = WatchProgress.entries[WatchEntry.key(e.tmdbID, "tv", sn, en)]?.finished == true
    var next: Episode? = null
    for (sn in e.season..(e.season + 3)) {
        val list = TMDB.episodes(e.tmdbID, sn)
        if (list.isEmpty()) { if (sn == e.season) break else continue }
        next = list.firstOrNull { (sn > e.season || it.number > e.episode) && !seen(sn, it.number) }
        if (next != null) break
    }
    val n = next ?: return null
    if (n.airDate.isNotEmpty() && n.airDate > todayString()) return null
    return ContinueTarget(e, n.season, n.number, n.name, n.stillPath, 0.0, true)
}
