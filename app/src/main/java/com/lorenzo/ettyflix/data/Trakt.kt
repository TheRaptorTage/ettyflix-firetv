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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    private suspend fun send(path: String, method: String = "GET", body: Any? = null, auth: Boolean = false, host: String? = null,
                             timeoutSec: Long = 0): HttpResult {
        val h = mutableMapOf("Content-Type" to "application/json", "trakt-api-version" to "2", "trakt-api-key" to clientID)
        if (auth) accessToken?.let { h["Authorization"] = "Bearer $it" }
        return Http.request((host ?: base) + path, method, body?.toString(), h, timeoutSec)
    }

    private suspend fun json(path: String, auth: Boolean = false, timeoutSec: Long = 0): Any? {
        if (auth) ensureFreshToken()
        val r = send(path, auth = auth, timeoutSec = timeoutSec)
        if (!r.ok) return null
        return runCatching { if (r.body.trimStart().startsWith("[")) JSONArray(r.body) else JSONObject(r.body) }.getOrNull()
    }

    /** Per le liste grosse (tutte le serie viste, che Trakt manda lentamente sugli account pieni):
     *  attesa lunga e fino a 3 tentativi. Prima andavano in timeout e si perdevano le spunte "Visto". */
    private suspend fun jsonPatient(path: String): Any? {
        for (attempt in 0 until 3) {
            json(path, auth = true, timeoutSec = 120)?.let { return it }
            kotlinx.coroutines.delay((2 + attempt * 3) * 1000L)
        }
        return null
    }

    /** Liste che Trakt ora manda A PAGINE (da luglio 2026: senza "page" arriva solo la prima, 100 elementi).
     *  Chiede tutte le pagine e le unisce. null se una pagina non arriva (meglio niente che una lista a metà). */
    private suspend fun jsonAllPages(path: String, limit: Int = 100, patient: Boolean = true): JSONArray? {
        val all = JSONArray()
        val sep = if ("?" in path) "&" else "?"
        fun sig(x: JSONObject?): String {
            if (x == null) return ""
            return listOf(x.opt("id"), x.optJSONObject("movie")?.optJSONObject("ids")?.opt("trakt"),
                x.optJSONObject("show")?.optJSONObject("ids")?.opt("trakt"),
                x.optJSONObject("season")?.optJSONObject("ids")?.opt("trakt"),
                x.optJSONObject("episode")?.optJSONObject("ids")?.opt("trakt")).joinToString("-")
        }
        var prevFirst = ""
        for (page in 1..400) {
            val url = "$path${sep}page=$page&limit=$limit"
            val arr = (if (patient) jsonPatient(url) else json(url, auth = true, timeoutSec = 60)) as? JSONArray ?: return null
            // stessa pagina di prima: Trakt ignora le pagine per questa lista (l'ho già tutta)
            val first = sig(arr.optJSONObject(0))
            if (page > 1 && first.isNotEmpty() && first == prevFirst) break
            prevFirst = first
            for (i in 0 until arr.length()) all.put(arr.get(i))
            // pagina non piena = ultima; più lunga del limite = Trakt ha mandato tutto in una volta
            if (arr.length() != limit) break
        }
        return all
    }

    // MARK: "Continua a guardare": titoli tolti, uguali su tutti i dispositivi

    /** Togli (o rimetti) un titolo da "Continua a guardare" anche sugli altri dispositivi: le serie vanno
     *  nell'elenco "nascoste" di Trakt; per film ed episodi a metà cancello anche la posizione su Trakt. */
    fun pushHidden(tmdbID: Int, mediaType: String, hidden: Boolean) {
        if (!isLoggedIn) return
        scope.launch {
            ensureFreshToken()
            if (mediaType == "tv") {
                val body = JSONObject().put("shows", JSONArray().put(ids(tmdbID)))
                runCatching { send(if (hidden) "/users/hidden/progress_watched" else "/users/hidden/progress_watched/remove", "POST", body, auth = true) }
            }
            if (hidden) jsonAllPages("/sync/playback", patient = false)?.let { deletePlayback(it.objects(), setOf(tmdbID)) }
        }
    }

    private suspend fun deletePlayback(list: List<JSONObject>, ids: Set<Int>) {
        for (p in list) {
            val isMovie = p.str("type") == "movie"
            val id = (if (isMovie) p.obj("movie") else p.obj("show")).obj("ids").int("tmdb") ?: continue
            if (id !in ids) continue
            val pid = p.optLong("id", 0L)
            if (pid > 0) runCatching { send("/sync/playback/$pid", "DELETE", auth = true) }
        }
    }

    /** Dopo una sincronizzazione completa: allinea i titoli tolti da "Continua a guardare". */
    private suspend fun syncHidden(playback: List<JSONObject>) {
        val pushKey = Profiles.key("trakt_hidden_push_v1")
        if (!EttyFlixApp.prefs.getBoolean(pushKey, false)) {
            val hidden = WatchProgress.hiddenFromContinue
            val shows = JSONArray()
            for (id in hidden) if (WatchProgress.latest(id)?.mediaType == "tv") shows.put(ids(id))
            if (shows.length() > 0) runCatching { send("/users/hidden/progress_watched", "POST", JSONObject().put("shows", shows), auth = true) }
            deletePlayback(playback, hidden)
            EttyFlixApp.prefs.edit().putBoolean(pushKey, true).apply()
        }
        jsonAllPages("/users/hidden/progress_watched?type=show", patient = false)?.let { h ->
            val ids = h.objects().mapNotNull { it.obj("show").obj("ids").int("tmdb") }.toSet()
            withContext(Dispatchers.Main) { WatchProgress.applyRemoteHidden(ids) }
        }
        val onTrakt = playback.filter { it.str("type") == "movie" }.mapNotNull { it.obj("movie").obj("ids").int("tmdb") }.toSet()
        withContext(Dispatchers.Main) { WatchProgress.hideStaleMovies(onTrakt) }
    }

    /** Ricostruisce film e serie viste (stesso formato di /sync/watched) dalla cronologia completa. */
    private fun watchedFromHistory(items: List<JSONObject>): Pair<JSONArray, JSONArray> {
        val movies = LinkedHashMap<Int, JSONObject>()
        val shows = LinkedHashMap<Int, Pair<JSONObject, HashMap<Int, HashMap<Int, String>>>>()
        for (h in items) {
            val at = h.str("watched_at") ?: ""
            if (h.str("type") == "movie") {
                val m = h.obj("movie"); val id = m.obj("ids").int("tmdb") ?: continue
                val cur = movies[id]
                if (cur == null || (cur.str("last_watched_at") ?: "") < at) movies[id] = JSONObject().put("movie", m).put("last_watched_at", at)
            } else if (h.str("type") == "episode") {
                val sh = h.obj("show"); val id = sh.obj("ids").int("tmdb") ?: continue
                val sn = h.obj("episode").int("season") ?: continue
                val en = h.obj("episode").int("number") ?: continue
                val seasons = shows.getOrPut(id) { sh to HashMap() }.second
                val eps = seasons.getOrPut(sn) { HashMap() }
                if ((eps[en] ?: "") < at) eps[en] = at
            }
        }
        val showsArr = JSONArray()
        for ((_, pair) in shows) {
            val seasonsArr = JSONArray()
            for ((sn, eps) in pair.second) {
                val epArr = JSONArray()
                for ((en, at) in eps) epArr.put(JSONObject().put("number", en).put("last_watched_at", at))
                seasonsArr.put(JSONObject().put("number", sn).put("episodes", epArr))
            }
            showsArr.put(JSONObject().put("show", pair.first).put("seasons", seasonsArr))
        }
        return JSONArray(movies.values.toList()) to showsArr
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
        // una volta per profilo dopo questo aggiornamento: sincronizzazione completa
        // v3: rifatta dopo la correzione delle serie viste che andavano in timeout (ripristina le spunte
        // "Visto" perse). Si segna come fatta SOLO se la sincronizzazione è riuscita tutta.
        // v4: Trakt ora manda le liste a pagine (prima arrivava solo la prima pagina: serie viste sparite)
        val fullKey = Profiles.key("trakt_full_sync_v4")
        if (!EttyFlixApp.prefs.getBoolean(fullKey, false)) {
            if (syncAll()) EttyFlixApp.prefs.edit().putBoolean(fullKey, true).apply()
            return
        }
        // una volta per profilo: porto nell'app i voti che hai già su Trakt
        if (!EttyFlixApp.prefs.getBoolean(Profiles.key("trakt_ratings_import_v1"), false)) runCatching { importRatings() }
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
        val list = jsonAllPages("/sync/playback", patient = false) ?: return
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

    /** Tutta la cronologia dell'account (per le statistiche: uguale su tutti i dispositivi). */
    suspend fun fullHistory(): List<JSONObject>? {
        if (!isLoggedIn) return null
        val out = ArrayList<JSONObject>()
        for (page in 1..400) {
            val arr = json("/sync/history?extended=full&limit=500&page=$page", auth = true) as? JSONArray
                ?: return if (page == 1) null else out
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
            if (arr.length() < 100) break
        }
        return out
    }

    /** Vero se è riuscita tutta. */
    suspend fun syncAll(): Boolean {
        if (!isLoggedIn || busy) return false
        busy = true
        val gen = syncGen
        val owner = Profiles.currentID
        fun same() = gen == syncGen && Profiles.currentID == owner
        var complete = true
        try {
            status = "Trakt: scarico…"
            // tutte le liste di Trakt insieme (in parallelo). Film e serie viste sono le più pesanti:
            // attesa lunga e più tentativi (prima andavano in timeout e venivano perse in silenzio).
            val all = coroutineScope {
                listOf("/sync/last_activities", "/sync/watched/movies", "/sync/watched/shows", "/sync/playback",
                    "/sync/watchlist/movies", "/sync/watchlist/shows")
                    .map { path -> async {
                        when {
                            path == "/sync/last_activities" -> json(path, auth = true, timeoutSec = 60)
                            // Trakt ora manda queste liste a pagine: le chiedo tutte
                            "/watched/" in path -> jsonAllPages(path)
                            else -> jsonAllPages(path, patient = false)
                        }
                    } }.awaitAll()
            }
            val la = all[0] as? JSONObject
            val lists = all.drop(1).map { it as? JSONArray }
            var moviesJ = lists[0]; var showsJ = lists[1]; val playbackJ = lists[2]
            val wlLists = listOf(Triple(lists[3], "movie", "movie"), Triple(lists[4], "tv", "show"))
            if (!same()) return false
            // serie mandate senza stagioni (nuove impostazioni predefinite di Trakt): gli episodi li prendo dalla cronologia
            showsJ?.let { sj ->
                if (sj.length() > 0 && sj.objects().none { (it.optJSONArray("seasons")?.length() ?: 0) > 0 }) showsJ = null
            }
            // Cronologia completa (la stessa delle Statistiche): se Trakt non ha mandato film o serie viste, e una volta
            // per profilo comunque, per rimettere TUTTO quello che hai visto (anche le serie perse prima di questa correzione).
            val historyKey = Profiles.key("trakt_history_merge_v1")
            val historyOnce = !EttyFlixApp.prefs.getBoolean(historyKey, false)
            var watchedOK = moviesJ != null && showsJ != null
            var usedHistory = false
            if (!watchedOK || historyOnce) {
                status = "Trakt: recupero la cronologia completa…"
                val hist = fullHistory()
                if (!same()) return false
                if (hist != null) {
                    val (hm, hs) = watchedFromHistory(hist)
                    moviesJ = moviesJ?.let { m -> JSONArray().also { out -> for (i in 0 until m.length()) out.put(m.get(i)); for (i in 0 until hm.length()) out.put(hm.get(i)) } } ?: hm
                    showsJ = showsJ?.let { m -> JSONArray().also { out -> for (i in 0 until m.length()) out.put(m.get(i)); for (i in 0 until hs.length()) out.put(hs.get(i)) } } ?: hs
                    watchedOK = true
                    usedHistory = true
                }
            }
            complete = watchedOK && playbackJ != null && lists[3] != null && lists[4] != null
            // quello che c'è già qui non lo richiedo a TMDB
            val local = HashMap<String, WatchEntry>()
            for (e in WatchProgress.entries.values) {
                val k = "${e.mediaType}-${e.tmdbID}"
                val cur = local[k]
                if (cur == null || cur.duration <= 60) local[k] = e
            }
            val localWL = HashMap<String, MediaItem>()
            for (m in Store.watchlist) localWL["${m.mediaType}-${m.tmdbID}"] = m
            val need = LinkedHashSet<Pair<String, Int>>()
            moviesJ?.objects()?.forEach { m -> m.obj("movie").obj("ids").int("tmdb")?.let { if ("movie-$it" !in local) need += "movie" to it } }
            showsJ?.objects()?.forEach { m -> m.obj("show").obj("ids").int("tmdb")?.let { if ("tv-$it" !in local) need += "tv" to it } }
            playbackJ?.objects()?.forEach { p ->
                if (p.str("type") == "movie") p.obj("movie").obj("ids").int("tmdb")?.let { need += "movie" to it }
                else p.obj("show").obj("ids").int("tmdb")?.let { need += "tv" to it }
            }
            for ((l, type, key) in wlLists) l?.objects()?.forEach { it.obj(key).obj("ids").int("tmdb")?.let { id -> if ("$type-$id" !in localWL) need += type to id } }
            val meta = HashMap<String, MediaDetail?>()
            if (need.isNotEmpty()) status = "Trakt: ${need.size} titoli nuovi…"
            val sem = Semaphore(8)
            coroutineScope {
                need.map { (t, id) -> async { sem.withPermit { "$t-$id" to TMDB.detail(t, id) } } }.awaitAll()
            }.forEach { (k, d) -> meta[k] = d }
            if (!same()) return false
            fun detail(type: String, id: Int): MediaDetail? = meta["$type-$id"]
            val imported = mutableListOf<WatchEntry>()
            moviesJ?.objects()?.forEach { m ->
                val id = m.obj("movie").obj("ids").int("tmdb") ?: return@forEach
                val l = local["movie-$id"]; val d = detail("movie", id)
                val dur = if ((l?.duration ?: 0.0) > 60) l!!.duration else ((d?.runtime ?: 100) * 60).toDouble()
                imported += WatchEntry(WatchEntry.key(id, "movie", null, null), id, "movie",
                    l?.title ?: d?.item?.title ?: m.obj("movie").str("title") ?: "",
                    l?.posterPath ?: d?.item?.posterPath, l?.backdropPath ?: d?.item?.backdropPath, l?.logoPath ?: d?.logoPath,
                    null, null, "", null, dur, dur, isoToRef(m.str("last_watched_at")), true)
            }
            showsJ?.objects()?.forEach { sh ->
                val id = sh.obj("show").obj("ids").int("tmdb") ?: return@forEach
                val l = local["tv-$id"]; val d = detail("tv", id)
                val dur = if ((l?.duration ?: 0.0) > 60) l!!.duration else ((d?.runtime ?: 45) * 60).toDouble()
                val title = l?.title ?: d?.item?.title ?: sh.obj("show").str("title") ?: ""
                for (season in sh.arr("seasons")) {
                    val sn = season.int("number") ?: continue
                    for (ep in season.arr("episodes")) {
                        val en = ep.int("number") ?: continue
                        val key = WatchEntry.key(id, "tv", sn, en)
                        val mine = WatchProgress.entries[key]
                        imported += WatchEntry(key, id, "tv", title,
                            l?.posterPath ?: d?.item?.posterPath, l?.backdropPath ?: d?.item?.backdropPath, l?.logoPath ?: d?.logoPath,
                            sn, en, mine?.episodeTitle ?: "", mine?.stillPath, dur, dur, isoToRef(ep.str("last_watched_at")), true)
                    }
                }
            }
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
            if (!same()) return false
            val wl = mutableListOf<MediaItem>()
            for ((listJ, type, key) in wlLists) {
                listJ?.objects()?.forEach { it ->
                    val id = it.obj(key).obj("ids").int("tmdb") ?: return@forEach
                    (localWL["$type-$id"] ?: detail(type, id)?.item)?.let { m -> wl += m }
                }
            }
            if (!same()) return false
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
            if (complete && playbackJ != null) runCatching { syncHidden(playbackJ.objects()) }
            // segno l'ultima attività solo a lavoro finito
            if (complete && la != null) {
                la.str("all")?.let { syncedStamp = it }
                val (o, pz) = stamps(la)
                otherStamp = o; pausedStamp = pz
            }
            status = if (complete) "Sincronizzato: ${imported.size} elementi, ${wl.size} in watchlist."
                else "Sincronizzazione parziale (${imported.size} elementi): Trakt non ha risposto a tutto, riprovo alla prossima apertura."
            if (complete && usedHistory) EttyFlixApp.prefs.edit().putBoolean(historyKey, true).apply()
            if (complete) runCatching { importRatings() }   // voti dati su Trakt da altre app
            return complete
        } finally {
            if (gen == syncGen) busy = false
        }
    }

    // MARK: Invio a Trakt

    private fun ids(id: Int) = JSONObject().put("ids", JSONObject().put("tmdb", id))

    /** Voto 1–10 anche su Trakt, così resta uguale sugli altri dispositivi (null = togli il voto). */
    fun pushRating(m: MediaItem, value: Int?) {
        if (!isLoggedIn || (m.mediaType != "movie" && m.mediaType != "tv")) return
        val entry = ids(m.tmdbID)
        if (value != null) entry.put("rating", value)
        val body = JSONObject().put(if (m.mediaType == "movie") "movies" else "shows", JSONArray().put(entry))
        scope.launch {
            ensureFreshToken()
            runCatching { send(if (value == null) "/sync/ratings/remove" else "/sync/ratings", "POST", body, auth = true) }
        }
    }

    /** Voto di una sola stagione (su Trakt resta separato da quello della serie). */
    fun pushSeasonRating(m: MediaItem, season: Int, value: Int?) {
        if (!isLoggedIn || m.mediaType != "tv") return
        val se = JSONObject().put("number", season)
        if (value != null) se.put("rating", value)
        val body = JSONObject().put("shows", JSONArray().put(ids(m.tmdbID).put("seasons", JSONArray().put(se))))
        scope.launch {
            ensureFreshToken()
            runCatching { send(if (value == null) "/sync/ratings/remove" else "/sync/ratings", "POST", body, auth = true) }
        }
    }

    /** Voti già dati su Trakt: riempiono quelli che mancano nell'app, così i consigli partono subito. */
    suspend fun importRatings() {
        if (!isLoggedIn) return
        val owner = Profiles.currentID
        val mJ = jsonAllPages("/sync/ratings/movies", patient = false)
        val sJ = jsonAllPages("/sync/ratings/shows", patient = false)
        if (Profiles.currentID != owner || (mJ == null && sJ == null)) return
        val found = HashMap<String, Int>()
        val items = HashMap<String, MediaItem>()
        for ((arr, key, type) in listOf(Triple(mJ, "movie", "movie"), Triple(sJ, "show", "tv"))) {
            for (x in arr?.objects() ?: emptyList()) {
                val o = x.obj(key)
                val id = o.obj("ids").int("tmdb") ?: continue
                val r = x.int("rating") ?: continue
                found["$type-$id"] = r
                items["$type-$id"] = MediaItem(id, type, o.str("title") ?: "", year = o.int("year")?.toString() ?: "")
            }
        }
        Store.mergeRatings(found, items)
        // voti delle stagioni
        val seJ = jsonAllPages("/sync/ratings/seasons", patient = false)
        if (seJ != null && Profiles.currentID == owner) {
            val sFound = HashMap<String, Int>()
            val sItems = HashMap<String, MediaItem>()
            for (x in seJ.objects()) {
                val o = x.obj("show")
                val id = o.obj("ids").int("tmdb") ?: continue
                val n = x.obj("season").int("number") ?: continue
                val r = x.int("rating") ?: continue
                if (n <= 0) continue
                sFound["tv-$id-s$n"] = r
                sItems["tv-$id"] = items["tv-$id"] ?: MediaItem(id, "tv", o.str("title") ?: "", year = o.int("year")?.toString() ?: "")
            }
            Store.mergeSeasonRatings(sFound, sItems)
        }
        EttyFlixApp.prefs.edit().putBoolean(Profiles.key("trakt_ratings_import_v1"), true).apply()
    }

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
