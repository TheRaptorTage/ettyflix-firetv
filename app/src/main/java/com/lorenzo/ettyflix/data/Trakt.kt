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
import java.security.MessageDigest
import android.util.Base64

/** Client Trakt: liste pubbliche e account del profilo (accesso con codice). */
object Trakt {
    private const val base = "https://api.trakt.tv"
    private val authHosts = listOf("https://api.trakt.tv", "https://auth.trakt.tv")
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
    private var authBase: String = EttyFlixApp.prefs.getString("trakt_auth_base", "https://api.trakt.tv") ?: "https://api.trakt.tv"

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
    }

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
        val verifier = (1..64).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".random() }.joinToString("")
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        var found: JSONObject? = null
        for (host in listOf(authBase) + authHosts.filter { it != authBase }) {
            val body = JSONObject().put("client_id", clientID).put("code_challenge", challenge).put("code_challenge_method", "S256")
            val r = send("/oauth/device/code", "POST", body, host = host)
            val j = r.json()
            if (r.ok && j?.str("device_code") != null) {
                found = j; authBase = host
                EttyFlixApp.prefs.edit().putString("trakt_auth_base", host).apply()
                break
            }
        }
        val j = found ?: run { status = "Impossibile ottenere il codice: controlla il Client ID."; return }
        val device = j.str("device_code")!!
        userCode = j.str("user_code")
        verificationURL = j.str("verification_url") ?: "https://trakt.tv/activate"
        status = "Inserisci il codice sul sito di Trakt."
        var interval = (j.int("interval") ?: 5).toLong()
        val deadline = System.currentTimeMillis() + (j.int("expires_in") ?: 600) * 1000L
        while (System.currentTimeMillis() < deadline && scope.isActive) {
            delay(interval * 1000)
            val body = JSONObject().put("code", device).put("client_id", clientID).put("code_verifier", verifier)
            val r = send("/oauth/device/token", "POST", body, host = authBase)
            when (r.code) {
                200 -> {
                    r.json()?.let { storeTokens(it) }
                    userCode = null
                    (json("/users/settings", auth = true) as? JSONObject)?.let { me ->
                        username = me.obj("user").str("username")
                        EttyFlixApp.prefs.edit().putString(Profiles.key("trakt_username"), username).apply()
                    }
                    status = "Collegato. Sincronizzo…"
                    syncAll()
                    return
                }
                400 -> continue
                429 -> interval += 1
                0 -> continue
                else -> { userCode = null; status = if (r.code == 418) "Accesso rifiutato su Trakt." else "Codice scaduto o non valido: riprova."; return }
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
    suspend fun quickSync() {
        if (!isLoggedIn || busy) return
        val stamp = (json("/sync/last_activities", auth = true) as? JSONObject)?.str("all") ?: return
        if (stamp != syncedStamp) syncAll()
    }

    suspend fun syncAll() {
        if (!isLoggedIn || busy) return
        busy = true
        try {
            (json("/sync/last_activities", auth = true) as? JSONObject)?.str("all")?.let { syncedStamp = it }
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
            (json("/sync/watched/movies", auth = true) as? JSONArray)?.objects()?.forEach { m ->
                val id = m.obj("movie").obj("ids").int("tmdb") ?: return@forEach
                val d = detail("movie", id)
                val dur = ((d?.runtime ?: 100) * 60).toDouble()
                imported += WatchEntry(WatchEntry.key(id, "movie", null, null), id, "movie", d?.item?.title ?: m.obj("movie").str("title") ?: "",
                    d?.item?.posterPath, d?.item?.backdropPath, d?.logoPath, null, null, "", null, dur, dur, isoToRef(m.str("last_watched_at")), true)
            }
            status = "Trakt: serie viste…"
            (json("/sync/watched/shows", auth = true) as? JSONArray)?.objects()?.forEach { sh ->
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
            (json("/sync/playback", auth = true) as? JSONArray)?.objects()?.forEach { p ->
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
            withContext(Dispatchers.Main) { WatchProgress.importEntries(imported) }
            status = "Trakt: watchlist…"
            val wl = mutableListOf<MediaItem>()
            for ((path, type, key) in listOf(Triple("/sync/watchlist/movies", "movie", "movie"), Triple("/sync/watchlist/shows", "tv", "show"))) {
                (json(path, auth = true) as? JSONArray)?.objects()?.forEach { it ->
                    val id = it.obj(key).obj("ids").int("tmdb") ?: return@forEach
                    detail(type, id)?.let { d -> wl += d.item }
                }
            }
            withContext(Dispatchers.Main) { Store.mergeWatchlist(wl) }
            status = "Sincronizzato: ${imported.size} elementi, ${wl.size} in watchlist."
        } finally {
            busy = false
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

    fun pushProgress(e: WatchEntry) {
        if (!isLoggedIn || e.finished || e.duration <= 0) return
        val last = lastScrobble[e.id] ?: 0
        if (System.currentTimeMillis() - last < 30_000) return
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
