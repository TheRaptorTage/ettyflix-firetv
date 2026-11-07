package com.lorenzo.ettyflix.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lorenzo.ettyflix.EttyFlixApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Client Trakt: liste pubbliche e account del profilo (accesso con codice). */
object Trakt {
    private const val base = "https://api.trakt.tv"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var clientID by mutableStateOf(EttyFlixApp.prefs.getString("trakt_client_id", "") ?: "")
        private set
    var accessToken by mutableStateOf<String?>(null)
        private set
    var username by mutableStateOf<String?>(null)
        private set
    var userCode by mutableStateOf<String?>(null)
        private set
    var verificationURL by mutableStateOf("https://trakt.tv/activate")
        private set
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set

    private var refreshToken: String? = null
    private var expiresAt: Long = 0
    private var loginJob: Job? = null
    private val lastScrobble = HashMap<String, Long>()
    private val listCache = HashMap<String, List<MediaItem>>()
    private var authBase: String = EttyFlixApp.prefs.getString("trakt_auth_base", "https://auth.trakt.tv") ?: "https://auth.trakt.tv"

    val isConfigured get() = clientID.isNotEmpty()
    val isLoggedIn get() = accessToken != null

    init { reloadAccount() }

    fun reloadAccount() {
        val p = EttyFlixApp.prefs
        accessToken = p.getString(Profiles.key("trakt_access_token"), null)
        refreshToken = p.getString(Profiles.key("trakt_refresh_token"), null)
        expiresAt = p.getLong(Profiles.key("trakt_expires_at_ms"), 0)
        username = p.getString(Profiles.key("trakt_username"), null)
        lastScrobble.clear()
        // la sincronizzazione del profilo precedente (se in corso) non scrive più niente
        syncGen++
        busy = false
    }

    /** Cambia a ogni cambio di profilo: una sincronizzazione non scrive mai nei dati di un altro profilo. */
    @Volatile private var syncGen = 0

    fun saveClientID(id: String) {
        clientID = id.trim()
        EttyFlixApp.prefs.edit().putString("trakt_client_id", clientID).apply()
        listCache.clear()
    }

    private fun storeTokens(j: JSONObject) {
        accessToken = j.str("access_token")
        refreshToken = j.str("refresh_token")
        val created = j.dbl("created_at") ?: (System.currentTimeMillis() / 1000.0)
        expiresAt = ((created + (j.dbl("expires_in") ?: 7_776_000.0)) * 1000).toLong()
        EttyFlixApp.prefs.edit()
            .putString(Profiles.key("trakt_access_token"), accessToken)
            .putString(Profiles.key("trakt_refresh_token"), refreshToken)
            .putLong(Profiles.key("trakt_expires_at_ms"), expiresAt).apply()
    }

    fun logout() {
        loginJob?.cancel()
        accessToken = null; refreshToken = null; username = null; userCode = null
        val e = EttyFlixApp.prefs.edit()
        for (k in listOf("trakt_access_token", "trakt_refresh_token", "trakt_expires_at_ms", "trakt_username")) e.remove(Profiles.key(k))
        e.apply()
        status = "Disconnesso da Trakt."
    }

    private suspend fun send(path: String, method: String = "GET", body: Any? = null, auth: Boolean = false, host: String? = null): HttpResult {
        val h = mutableMapOf("Content-Type" to "application/json", "trakt-api-version" to "2", "trakt-api-key" to clientID)
        if (auth) accessToken?.let { h["Authorization"] = "Bearer $it" }
        return Http.request((host ?: base) + path, method, body?.toString(), h)
    }

    private suspend fun json(path: String, auth: Boolean = false): Any? {
        if (auth) ensureFreshToken()
        val r = send(path, auth = auth)
        if (!r.ok) return null
        return runCatching { if (r.body.trimStart().startsWith("[")) JSONArray(r.body) else JSONObject(r.body) }.getOrNull()
    }

    private suspend fun ensureFreshToken() {
        val rt = refreshToken ?: return
        if (expiresAt == 0L || expiresAt > System.currentTimeMillis() + 86_400_000) return
        val body = JSONObject().put("refresh_token", rt).put("client_id", clientID)
            .put("redirect_uri", "http://localhost").put("grant_type", "refresh_token")
        val r = send("/oauth/token", "POST", body, host = authBase)
        if (r.ok) r.json()?.let { storeTokens(it) }
    }

    // MARK: Accesso con codice

    fun startLogin() {
        if (!isConfigured) { status = "Manca il Client ID di Trakt: invia la configurazione dal Mac."; return }
        loginJob?.cancel()
        loginJob = scope.launch { runLogin() }
    }

    fun cancelLogin() { loginJob?.cancel(); userCode = null; status = "" }

    private suspend fun runLogin() {
        status = "Richiedo il codice…"
        // Flusso "dispositivo" come da documentazione Trakt: niente PKCE, segreto facoltativo,
        // e il token si chiede allo STESSO indirizzo che ha dato il codice (auth.trakt.tv per primo).
        var found: JSONObject? = null
        var codeHost = ""
        var firstError = ""
        for (host in listOf("https://auth.trakt.tv", "https://api.trakt.tv")) {
            val r = send("/oauth/device/code", "POST", JSONObject().put("client_id", clientID), host = host)
            val j = r.json()
            if (r.ok && j?.str("device_code") != null) {
                found = j; codeHost = host; authBase = host
                EttyFlixApp.prefs.edit().putString("trakt_auth_base", host).apply()
                break
            } else if (firstError.isEmpty()) firstError = "errore ${r.code}"
        }
        val j = found ?: run { status = "Impossibile ottenere il codice (${firstError.ifEmpty { "rete" }}): controlla il Client ID."; return }
        val device = j.str("device_code")!!
        userCode = j.str("user_code")
        verificationURL = j.str("verification_url") ?: "https://trakt.tv/activate"
        status = "Inserisci il codice sul sito di Trakt."
        var interval = (j.int("interval") ?: 5).toLong().coerceAtLeast(1)
        val deadline = System.currentTimeMillis() + (j.int("expires_in") ?: 600) * 1000L
        val clientSecret = EttyFlixApp.prefs.getString("trakt_client_secret", "") ?: ""
        var useSecret = clientSecret.isNotEmpty()
        while (System.currentTimeMillis() < deadline && scope.isActive) {
            delay(interval * 1000)
            val body = JSONObject().put("code", device).put("client_id", clientID)
            if (useSecret) body.put("client_secret", clientSecret)
            val r = send("/oauth/device/token", "POST", body, host = codeHost)
            when (r.code) {
                200, 201 -> {
                    val tj = r.json()
                    if (tj?.str("access_token") == null) { userCode = null; status = "Trakt ha risposto senza token. Riprova."; return }
                    storeTokens(tj)
                    userCode = null
                    (json("/users/settings", auth = true) as? JSONObject)?.let { me ->
                        username = me.obj("user").str("username")
                        EttyFlixApp.prefs.edit().putString(Profiles.key("trakt_username"), username).apply()
                    }
                    status = "Collegato" + (username?.let { " come $it" } ?: "") + ". Sincronizzo…"
                    syncAll()
                    return
                }
                400, 0 -> continue                         // codice non ancora inserito / rete
                429 -> interval += 1
                401, 403 -> if (useSecret) { useSecret = false; continue } else {
                    userCode = null; status = "Accesso non riuscito [${r.code}]. Riprova."; return
                }
                else -> {
                    userCode = null
                    val why = when (r.code) {
                        404 -> "codice non valido"; 409 -> "codice già usato"; 410 -> "codice scaduto"
                        418 -> "accesso negato su Trakt (se l'app Trakt è in modalità Development, aggiungi questo account come Tester)"
                        else -> "errore ${r.code}"
                    }
                    status = "Accesso non riuscito: $why. Riprova."
                    return
                }
            }
        }
        userCode = null
        status = "Codice scaduto: riprova."
    }

    // MARK: Liste pubbliche

    suspend fun listItems(listID: Int, user: String, page: Int): List<MediaItem> {
        if (!isConfigured || listID <= 0) return emptyList()
        val key = "$listID-$page"
        listCache[key]?.let { return it }
        val path = if (user.isEmpty()) "/lists/$listID/items?page=$page&limit=40" else "/users/$user/lists/$listID/items?page=$page&limit=40"
        val arr = json(path) as? JSONArray ?: return emptyList()
        val out = mutableListOf<MediaItem>()
        for (it in arr.objects()) {
            val type = it.str("type") ?: ""
            val tmdbType = if (type == "movie") "movie" else if (type == "show") "tv" else continue
            val tid = it.obj(if (type == "movie") "movie" else "show").obj("ids").int("tmdb") ?: continue
            TMDB.get("$tmdbType/$tid")?.let { t -> MediaItem.from(t, tmdbType)?.let { m -> out += m } }
        }
        listCache[key] = out
        return out
    }

    // MARK: Sincronizzazione

    private var syncedStamp: String
        get() = EttyFlixApp.prefs.getString(Profiles.key("trakt_synced_stamp"), "") ?: ""
        set(v) { EttyFlixApp.prefs.edit().putString(Profiles.key("trakt_synced_stamp"), v).apply() }

    /** Controllo leggero: se su Trakt è cambiato qualcosa rifà la sincronizzazione completa. */
    private var pausedStamp: String
        get() = EttyFlixApp.prefs.getString(Profiles.key("trakt_paused_stamp"), "") ?: ""
        set(v) { EttyFlixApp.prefs.edit().putString(Profiles.key("trakt_paused_stamp"), v).apply() }
    private var otherStamp: String
        get() = EttyFlixApp.prefs.getString(Profiles.key("trakt_other_stamp"), "") ?: ""
        set(v) { EttyFlixApp.prefs.edit().putString(Profiles.key("trakt_other_stamp"), v).apply() }

    /** "Visti / watchlist" e "in pausa" da last_activities. */
    private fun stamps(j: JSONObject): Pair<String, String> {
        fun g(a: String, b: String) = j.optJSONObject(a)?.optString(b, "") ?: ""
        val paused = g("movies", "paused_at") + "|" + g("episodes", "paused_at")
        val other = listOf(g("movies", "watched_at"), g("episodes", "watched_at"), g("movies", "watchlisted_at"),
            g("shows", "watchlisted_at"), g("movies", "collected_at"), g("episodes", "collected_at")).joinToString("|")
        return other to paused
    }

    /** Controllo leggero: se altrove ti sei solo fermato a metà scarica subito le posizioni, altrimenti tutto. */
    suspend fun quickSync() {
        if (!isLoggedIn || busy) return
        val la = json("/sync/last_activities", auth = true) as? JSONObject ?: return
        val stamp = la.str("all") ?: return
        if (stamp == syncedStamp) return
        val (other, paused) = stamps(la)
        if (other == otherStamp) {
            if (paused != pausedStamp) { playbackSync(); pausedStamp = paused }
            syncedStamp = stamp
        } else syncAll()
    }

    /** Solo le posizioni a metà: riapri l'app su un altro dispositivo e riprendi dal punto esatto. */
    suspend fun playbackSync() {
        if (!isLoggedIn || busy) return
        val gen = syncGen
        val owner = Profiles.currentID
        val list = json("/sync/playback", auth = true) as? JSONArray ?: return
        val out = mutableListOf<WatchEntry>()
        list.objects().forEach { p ->
            val pct = (p.dbl("progress") ?: 0.0) / 100
            if (pct <= 0) return@forEach
            val whenRef = isoToRef(p.str("paused_at"))
            val movie = p.str("type") == "movie"
            val id = (if (movie) p.obj("movie") else p.obj("show")).obj("ids").int("tmdb") ?: return@forEach
            val sn = if (movie) null else (p.obj("episode").int("season") ?: return@forEach)
            val en = if (movie) null else (p.obj("episode").int("number") ?: return@forEach)
            val type = if (movie) "movie" else "tv"
            val key = WatchEntry.key(id, type, sn, en)
            val local = WatchProgress.entries[key]
            if (local != null && local.updated >= whenRef - 1) return@forEach
            var dur = if ((local?.duration ?: 0.0) > 60) local!!.duration else 0.0
            var title = local?.title ?: ""
            var poster = local?.posterPath; var backdrop = local?.backdropPath; var logo = local?.logoPath; var still = local?.stillPath
            if (local == null || dur == 0.0) {
                TMDB.detail(type, id)?.let { d ->
                    if (title.isEmpty()) title = d.item.title
                    poster = poster ?: d.item.posterPath; backdrop = backdrop ?: d.item.backdropPath; logo = logo ?: d.logoPath
                    if (movie) dur = ((d.runtime ?: 100) * 60).toDouble()
                }
                if (!movie && sn != null && en != null) {
                    val ep = TMDB.episodes(id, sn).firstOrNull { it.number == en }
                    still = still ?: ep?.stillPath
                    if (dur == 0.0) dur = ((ep?.runtime ?: 45) * 60).toDouble()
                }
                if (title.isEmpty()) title = (if (movie) p.obj("movie") else p.obj("show")).str("title") ?: ""
            }
            out += WatchEntry(key, id, type, title, poster, backdrop, logo, sn, en,
                local?.episodeTitle ?: (p.obj("episode").str("title") ?: ""), still, dur * pct, dur, whenRef, false)
        }
        if (gen != syncGen || Profiles.currentID != owner || out.isEmpty()) return
        withContext(Dispatchers.Main) { WatchProgress.importEntries(out) }
    }

    suspend fun syncAll() {
        if (!isLoggedIn || busy) return
        busy = true
        val gen = syncGen
        val owner = Profiles.currentID
        fun same() = gen == syncGen && Profiles.currentID == owner
        var complete = true
        try {
            (json("/sync/last_activities", auth = true) as? JSONObject)?.let { la ->
                la.str("all")?.let { syncedStamp = it }
                val (o, pz) = stamps(la)
                otherStamp = o; pausedStamp = pz
            }
            val imported = mutableListOf<WatchEntry>()
            val meta = HashMap<String, MediaDetail?>()
            suspend fun detail(type: String, id: Int): MediaDetail? {
                val k = "$type-$id"
                if (meta.containsKey(k)) return meta[k]
                val d = TMDB.detail(type, id)
                meta[k] = d
                return d
            }
            status = "Trakt: film visti…"
            val moviesJ = json("/sync/watched/movies", auth = true) as? JSONArray
            if (moviesJ == null) complete = false
            if (!same()) return
            moviesJ?.objects()?.forEach { m ->
                val id = m.obj("movie").obj("ids").int("tmdb") ?: return@forEach
                val d = detail("movie", id)
                val dur = ((d?.runtime ?: 100) * 60).toDouble()
                imported += WatchEntry(WatchEntry.key(id, "movie", null, null), id, "movie", d?.item?.title ?: m.obj("movie").str("title") ?: "",
                    d?.item?.posterPath, d?.item?.backdropPath, d?.logoPath, null, null, "", null, dur, dur, isoToRef(m.str("last_watched_at")), true)
            }
            status = "Trakt: serie viste…"
            val showsJ = json("/sync/watched/shows", auth = true) as? JSONArray
            if (showsJ == null) complete = false
            if (!same()) return
            showsJ?.objects()?.forEach { sh ->
                val id = sh.obj("show").obj("ids").int("tmdb") ?: return@forEach
                val d = detail("tv", id)
                val dur = ((d?.runtime ?: 45) * 60).toDouble()
                for (season in sh.arr("seasons")) {
                    val sn = season.int("number") ?: continue
                    for (ep in season.arr("episodes")) {
                        val en = ep.int("number") ?: continue
                        imported += WatchEntry(WatchEntry.key(id, "tv", sn, en), id, "tv", d?.item?.title ?: sh.obj("show").str("title") ?: "",
                            d?.item?.posterPath, d?.item?.backdropPath, d?.logoPath, sn, en, "", null, dur, dur, isoToRef(ep.str("last_watched_at")), true)
                    }
                }
            }
            status = "Trakt: progressi…"
            val playbackJ = json("/sync/playback", auth = true) as? JSONArray
            if (playbackJ == null) complete = false
            if (!same()) return
            playbackJ?.objects()?.forEach { p ->
                val pct = (p.dbl("progress") ?: 0.0) / 100
                val whenRef = isoToRef(p.str("paused_at"))
                if (p.str("type") == "movie") {
                    val id = p.obj("movie").obj("ids").int("tmdb") ?: return@forEach
                    val d = detail("movie", id)
                    val dur = ((d?.runtime ?: 100) * 60).toDouble()
                    imported += WatchEntry(WatchEntry.key(id, "movie", null, null), id, "movie", d?.item?.title ?: "",
                        d?.item?.posterPath, d?.item?.backdropPath, d?.logoPath, null, null, "", null, dur * pct, dur, whenRef, false)
                } else if (p.str("type") == "episode") {
                    val id = p.obj("show").obj("ids").int("tmdb") ?: return@forEach
                    val sn = p.obj("episode").int("season") ?: return@forEach
                    val en = p.obj("episode").int("number") ?: return@forEach
                    val d = detail("tv", id)
                    val dur = ((d?.runtime ?: 45) * 60).toDouble()
                    imported += WatchEntry(WatchEntry.key(id, "tv", sn, en), id, "tv", d?.item?.title ?: p.obj("show").str("title") ?: "",
                        d?.item?.posterPath, d?.item?.backdropPath, d?.logoPath, sn, en, p.obj("episode").str("title") ?: "", null,
                        dur * pct, dur, whenRef, false)
                }
            }
            if (!same()) return
            status = "Trakt: watchlist…"
            val wl = mutableListOf<MediaItem>()
            for ((path, type, key) in listOf(Triple("/sync/watchlist/movies", "movie", "movie"), Triple("/sync/watchlist/shows", "tv", "show"))) {
                val listJ = json(path, auth = true) as? JSONArray
                if (listJ == null) complete = false
                listJ?.objects()?.forEach { it ->
                    val id = it.obj(key).obj("ids").int("tmdb") ?: return@forEach
                    detail(type, id)?.let { d -> wl += d.item }
                }
            }
            if (!same()) return
            // Una volta per profilo: dati riallineati al suo account Trakt (spariscono i titoli finiti per
            // errore nel profilo sbagliato). Solo se la sincronizzazione è riuscita tutta.
            val repairKey = Profiles.key("trakt_profile_repair_v1")
            val repair = complete && !EttyFlixApp.prefs.getBoolean(repairKey, false)
            withContext(Dispatchers.Main) {
                if (repair) {
                    WatchProgress.replaceEntries(imported)
                    Store.replaceWatchlist(wl)
                    EttyFlixApp.prefs.edit().putBoolean(repairKey, true).apply()
                } else {
                    WatchProgress.importEntries(imported)
                    Store.mergeWatchlist(wl)
                }
            }
            status = "Sincronizzato: ${imported.size} elementi, ${wl.size} in watchlist."
        } finally {
            if (gen == syncGen) busy = false
        }
    }

    // MARK: Invio a Trakt

    private fun ids(id: Int) = JSONObject().put("ids", JSONObject().put("tmdb", id))

    fun pushWatched(list: List<WatchEntry>, watched: Boolean) {
        if (!isLoggedIn || list.isEmpty()) return
        val movies = JSONArray()
        val shows = HashMap<Int, HashMap<Int, MutableList<Int>>>()
        for (e in list) {
            if (e.mediaType == "movie") movies.put(ids(e.tmdbID))
            else if (e.season != null && e.episode != null) shows.getOrPut(e.tmdbID) { HashMap() }.getOrPut(e.season) { mutableListOf() } += e.episode
        }
        val showArr = JSONArray()
        shows.forEach { (id, seasons) ->
            val sArr = JSONArray()
            seasons.forEach { (s, eps) ->
                sArr.put(JSONObject().put("number", s).put("episodes", JSONArray().apply { eps.forEach { put(JSONObject().put("number", it)) } }))
            }
            showArr.put(ids(id).put("seasons", sArr))
        }
        val body = JSONObject().put("movies", movies).put("shows", showArr)
        scope.launch { ensureFreshToken(); send(if (watched) "/sync/history" else "/sync/history/remove", "POST", body, auth = true) }
    }

    fun pushProgress(e: WatchEntry, urgent: Boolean = false) {
        if (!isLoggedIn || e.finished || e.duration <= 0) return
        val last = lastScrobble[e.id] ?: 0
        // in pausa / alla chiusura parte sempre subito; durante la visione al massimo ogni 30 secondi
        if (!urgent && System.currentTimeMillis() - last < 30_000) return
        lastScrobble[e.id] = System.currentTimeMillis()
        val body = JSONObject().put("progress", e.fraction * 100)
        if (e.mediaType == "movie") body.put("movie", ids(e.tmdbID))
        else if (e.season != null && e.episode != null) {
            body.put("show", ids(e.tmdbID)); body.put("episode", JSONObject().put("season", e.season).put("number", e.episode))
        }
        scope.launch { ensureFreshToken(); send("/scrobble/pause", "POST", body, auth = true) }
    }

    /** Invia subito la posizione (alla chiusura del player). */
    fun pushProgressNow(e: WatchEntry) { lastScrobble.remove(e.id); pushProgress(e) }

    fun pushWatchlist(m: MediaItem, add: Boolean) {
        if (!isLoggedIn) return
        val entry = JSONArray().put(ids(m.tmdbID))
        val body = if (m.mediaType == "movie") JSONObject().put("movies", entry) else JSONObject().put("shows", entry)
        scope.launch { ensureFreshToken(); send(if (add) "/sync/watchlist" else "/sync/watchlist/remove", "POST", body, auth = true) }
    }
}
