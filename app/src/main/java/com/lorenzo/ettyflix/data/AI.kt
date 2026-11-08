package com.lorenzo.ettyflix.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lorenzo.ettyflix.EttyFlixApp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONArray
import org.json.JSONObject

@Serializable
data class AIPick(val item: MediaItem, val reason: String) { val id get() = item.id }

@Serializable
private data class AICache(val signature: String, val date: Long, val picks: List<AIPick>)

/** Consigli con Google Gemini (chiave personale arrivata dal Mac). */
object AI {
    private const val keyName = "gemini_api_key"
    private val fallbackModels = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-flash-latest", "gemini-2.0-flash")

    var moviePicks by mutableStateOf(listOf<AIPick>())
    var tvPicks by mutableStateOf(listOf<AIPick>())
    var loadingTypes by mutableStateOf(setOf<String>())
    var typeStatus by mutableStateOf(mapOf<String, String>())

    val apiKey: String get() = EttyFlixApp.prefs.getString(keyName, "") ?: ""
    val hasKey get() = apiKey.isNotEmpty()

    fun saveKey(k: String) { EttyFlixApp.prefs.edit().putString(keyName, k.trim()).apply() }

    fun reloadProfile() { moviePicks = emptyList(); tvPicks = emptyList(); typeStatus = emptyMap(); qualifiedShows = emptySet() }

    fun typedPicks(type: String) = if (type == "movie") moviePicks else tvPicks
    private fun setPicks(type: String, p: List<AIPick>) { if (type == "movie") moviePicks = p else tvPicks = p }

    // MARK: Cronologia valida (almeno il 75%)

    private var qualifiedShows: Set<Int> = emptySet()
    private val airedTotals = HashMap<Int, Pair<Int, Long>>()

    private fun counts(e: WatchEntry) = if (e.mediaType == "tv") e.tmdbID in qualifiedShows else e.finished || e.fraction >= 0.75

    suspend fun updateQualifiedShows() {
        val watched = HashMap<Int, MutableSet<String>>()
        for (e in WatchProgress.entries.values) {
            if (e.mediaType != "tv" || !(e.finished || e.fraction >= 0.75)) continue
            val sn = e.season ?: continue; val ep = e.episode ?: continue
            if (sn > 0) watched.getOrPut(e.tmdbID) { mutableSetOf() } += "${sn}x$ep"
        }
        val ok = HashSet<Int>()
        for ((id, eps) in watched) {
            val total = airedEpisodes(id)
            if (total > 0 && eps.size >= total * 0.75) ok += id
        }
        qualifiedShows = ok
    }

    private suspend fun airedEpisodes(id: Int): Int {
        airedTotals[id]?.let { if (System.currentTimeMillis() - it.second < 86_400_000) return it.first }
        val j = TMDB.get("tv/$id") ?: return airedTotals[id]?.first ?: 0
        val last = j.obj("last_episode_to_air")
        val lastSeason = last.int("season_number") ?: 0
        var total = 0
        for (s in j.arr("seasons")) {
            val n = s.int("season_number") ?: continue
            if (n <= 0 || (lastSeason != 0 && n > lastSeason)) continue
            total += if (n == lastSeason && last.int("episode_number") != null) last.int("episode_number")!! else (s.int("episode_count") ?: 0)
        }
        if (total == 0) total = j.int("number_of_episodes") ?: 0
        airedTotals[id] = total to System.currentTimeMillis()
        return total
    }

    private data class H(val id: Int, val type: String, val title: String)

    private fun history(limit: Int = 40): List<H> {
        val best = HashMap<String, WatchEntry>()
        for (e in WatchProgress.entries.values) {
            if (!counts(e)) continue
            val k = "${e.mediaType}-${e.tmdbID}"
            val cur = best[k]
            if (cur != null && cur.updated >= e.updated) continue
            best[k] = e
        }
        return best.values.sortedByDescending { it.updated }.take(limit).map { H(it.tmdbID, it.mediaType, it.title) }
    }

    private val seenKeys: Set<String> get() = WatchProgress.entries.values.map { "${it.mediaType}-${it.tmdbID}" }.toSet()

    // MARK: Consigli per la Home

    suspend fun refreshTyped(type: String, force: Boolean = false) {
        if (!hasKey || type in loadingTypes) return
        updateQualifiedShows()
        val cacheKey = Profiles.key("ai_like_$type")
        val h = history()
        val mine = h.filter { it.type == type }
        val signature = h.take(10).joinToString(",") { "${it.type}${it.id}" }
        val cached = EttyFlixApp.prefs.getString(cacheKey, null)?.let {
            runCatching { AppJson.decodeFromString(AICache.serializer(), it) }.getOrNull()
        }
        if (cached != null && cached.picks.isNotEmpty()) setPicks(type, cached.picks.filter { it.id !in seenKeys })
        if (!force && cached != null && cached.signature == signature && System.currentTimeMillis() - cached.date < 86_400_000 && cached.picks.isNotEmpty()) return
        if (h.isEmpty()) {
            typeStatus = typeStatus + (type to "Guarda almeno il 75% di qualcosa e qui compariranno i consigli.")
            return
        }
        loadingTypes = loadingTypes + type
        typeStatus = typeStatus - type
        try {
            val kind = if (type == "movie") "film" else "serie TV"
            val all = h.joinToString("\n") { "- ${it.title} (${if (it.type == "movie") "film" else "serie TV"})" }
            val wl = Store.watchlist.filter { it.mediaType == type }.take(15).joinToString("\n") { "- ${it.title}" }
            val prompt = """
                Sei un esperto di cinema e serie TV. Ecco cosa ha guardato l'utente (dal più recente):
                $all
                ${if (wl.isEmpty()) "" else "\nNella sua watchlist ha già:\n$wl"}
                ${if (mine.isEmpty()) "" else "\nDai più peso a: " + mine.take(8).joinToString(", ") { it.title } + "."}

                Capisci i suoi gusti (generi, tono, temi) e consiglia 20 $kind che NON ha già visto e che non sono nella watchlist.
                Preferisci titoli di buona qualità, anche meno famosi. Rispondi SOLO con un array JSON di oggetti con i campi:
                "title" (titolo originale), "year" (numero), "type" ("$type"),
                "reason" (in italiano, massimo 12 parole, perché potrebbe piacergli, citando se utile un titolo che ha visto).
            """.trimIndent()
            val raw = ask(prompt)
            if (raw == null) {
                if (typedPicks(type).isEmpty()) typeStatus = typeStatus + (type to "L'IA non ha risposto. Riprova più tardi.")
                return
            }
            val found = resolve(raw).filter { it.item.mediaType == type }
            if (found.isEmpty()) {
                typeStatus = typeStatus + (type to "L'IA ha risposto ma non ho trovato i titoli su TMDB.")
                return
            }
            setPicks(type, found)
            EttyFlixApp.prefs.edit().putString(cacheKey,
                AppJson.encodeToString(AICache.serializer(), AICache(signature, System.currentTimeMillis(), found))).apply()
        } finally {
            loadingTypes = loadingTypes - type
        }
    }

    /** Ultimi titoli visti di un tipo (per "Perché hai visto…"). */
    fun recentSeeds(type: String, n: Int): List<MediaItem> {
        val best = HashMap<Int, WatchEntry>()
        for (e in WatchProgress.entries.values) {
            if (e.mediaType != type || !counts(e)) continue
            val cur = best[e.tmdbID]
            if (cur != null && cur.updated >= e.updated) continue
            best[e.tmdbID] = e
        }
        return best.values.sortedByDescending { it.updated }.take(n).map { it.media }
    }

    // MARK: Gemini

    private suspend fun models(): List<String> {
        val p = EttyFlixApp.prefs
        val cached = p.getString("gemini_models", "")!!.split(",").filter { it.isNotBlank() }
        if (cached.isNotEmpty() && System.currentTimeMillis() - p.getLong("gemini_models_date", 0) < 7 * 86_400_000L) return cached
        val r = Http.get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", mapOf("x-goog-api-key" to apiKey), 15)
        val j = r.json() ?: return fallbackModels
        val names = j.arr("models").mapNotNull { m ->
            val full = m.str("name") ?: return@mapNotNull null
            if ("generateContent" !in m.strings("supportedGenerationMethods")) return@mapNotNull null
            val n = full.removePrefix("models/")
            val low = n.lowercase()
            if (!low.startsWith("gemini") || !low.contains("flash")) return@mapNotNull null
            if (listOf("image", "tts", "audio", "live", "embedding", "thinking", "exp", "preview-0").any { low.contains(it) }) return@mapNotNull null
            n
        }
        fun version(n: String) = n.split("-").firstNotNullOfOrNull { it.toDoubleOrNull() } ?: if (n.contains("latest")) 99.0 else 0.0
        val sorted = names.sortedWith(compareBy<String> { it.contains("lite") }.thenByDescending { version(it) }.thenBy { it.length }).take(6)
        if (sorted.isEmpty()) return fallbackModels
        p.edit().putString("gemini_models", sorted.joinToString(",")).putLong("gemini_models_date", System.currentTimeMillis()).apply()
        return sorted
    }

    private suspend fun ask(prompt: String, jsonMode: Boolean = true): String? {
        if (!hasKey) return null
        val deadline = System.currentTimeMillis() + 120_000
        for (model in models()) {
            var thinkingOff = model.contains("2.5")
            var attempt = 0
            while (attempt < 3) {
                if (System.currentTimeMillis() > deadline) return null
                val gen = JSONObject().put("temperature", if (jsonMode) 0.8 else 0.2)
                if (jsonMode) gen.put("responseMimeType", "application/json")
                if (thinkingOff) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", gen)
                val r = Http.request("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", "POST",
                    body.toString(), mapOf("x-goog-api-key" to apiKey, "Content-Type" to "application/json"), 75)
                when {
                    r.code == 0 || r.code == 404 -> break
                    r.code == 429 || r.code >= 500 -> { attempt++; if (attempt < 2) { delay(2000); continue } else break }
                    r.code == 400 && thinkingOff && r.body.lowercase().contains("thinking") -> { thinkingOff = false; continue }
                    r.code == 400 || r.code == 403 -> return null
                    !r.ok -> break
                }
                val text = r.json()?.arr("candidates")?.firstOrNull()?.obj("content")?.arr("parts")
                    ?.joinToString("") { it.str("text") ?: "" } ?: ""
                if (text.isEmpty()) break
                return text
            }
        }
        return null
    }

    private suspend fun resolve(raw: String): List<AIPick> = coroutineScope {
        var text = raw.trim()
        if (text.startsWith("```")) text = text.replace("```json", "").replace("```", "").trim()
        val arr: List<JSONObject> = runCatching { JSONArray(text).objects() }.getOrElse {
            runCatching {
                val o = JSONObject(text)
                o.keys().asSequence().mapNotNull { o.optJSONArray(it) }.firstOrNull()?.objects() ?: emptyList()
            }.getOrDefault(emptyList())
        }
        val seen = seenKeys
        val found = arr.mapIndexed { i, x ->
            async {
                val t = x.str("title") ?: return@async null
                val type = if ((x.str("type") ?: "movie").lowercase().contains("tv")) "tv" else "movie"
                val m = TMDB.findOne(t, x.int("year"), type) ?: return@async null
                if (m.posterPath == null) null else i to AIPick(m, x.str("reason") ?: "")
            }
        }.awaitAll().filterNotNull().sortedBy { it.first }.map { it.second }
        val ids = HashSet<String>()
        found.filter { it.item.id !in seen && ids.add(it.item.id) }
    }

    // MARK: Traduzioni (biografie)

    suspend fun translateToItalian(text: String, cacheKey: String): String? {
        val p = EttyFlixApp.prefs
        p.getString("it_tr_$cacheKey", null)?.let { return it }
        if (!hasKey || text.isEmpty()) return null
        val out = ask("Traduci in italiano naturale questo testo. Rispondi solo con la traduzione, senza commenti.\n\n$text", jsonMode = false)?.trim()
        if (!out.isNullOrEmpty()) p.edit().putString("it_tr_$cacheKey", out).apply()
        return out
    }

    /** Ricerca "a parole": l'IA propone titoli che corrispondono alla descrizione. */
    suspend fun searchByDescription(q: String): List<AIPick> {
        val prompt = """
            Un utente cerca film o serie TV con questa richiesta: "$q".
            Proponi fino a 15 titoli adatti. Rispondi SOLO con un array JSON di oggetti con i campi:
            "title" (titolo originale), "year" (numero), "type" ("movie" oppure "tv"), "reason" (in italiano, massimo 12 parole).
        """.trimIndent()
        val raw = ask(prompt) ?: return emptyList()
        return resolve(raw)
    }
}

// MARK: Voti IMDb (Cinemeta) e OMDb per stagione

object Ratings {
    private val imdb = HashMap<String, Double?>()
    /** voti IMDb già letti (restano tra un avvio e l'altro): per le copertine */
    private val saved: MutableMap<String, Double> by lazy {
        val out = HashMap<String, Double>()
        runCatching {
            val o = JSONObject(EttyFlixApp.prefs.getString("imdb_ratings_v1", "{}") ?: "{}")
            for (k in o.keys()) out[k] = o.getDouble(k)
        }
        out
    }
    fun cached(m: MediaItem): Double? = saved[m.id] ?: imdb[m.id]
    private fun remember(id: String, v: Double) {
        saved[id] = v
        val o = JSONObject(); saved.forEach { (k, x) -> o.put(k, x) }
        EttyFlixApp.prefs.edit().putString("imdb_ratings_v1", o.toString()).apply()
    }
    private val seasonsCinemeta = HashMap<String, Map<Int, Double>>()

    suspend fun imdbRating(m: MediaItem): Double? {
        saved[m.id]?.let { return it }
        if (imdb.containsKey(m.id)) return imdb[m.id]
        val id = TMDB.imdbID(m.mediaType, m.tmdbID)
        if (id == null) { imdb[m.id] = null; return null }
        val r = Http.get("https://v3-cinemeta.strem.io/meta/${if (m.mediaType == "movie") "movie" else "series"}/$id.json")
        val meta = r.json()?.obj("meta") ?: return null
        val sums = HashMap<Int, Pair<Double, Int>>()
        meta.optJSONArray("videos")?.objects()?.forEach { v ->
            val se = v.int("season") ?: return@forEach
            if (se <= 0) return@forEach
            val value = v.dbl("rating") ?: v.dbl("imdbRating") ?: return@forEach
            if (value <= 0) return@forEach
            val cur = sums[se] ?: (0.0 to 0)
            sums[se] = (cur.first + value) to (cur.second + 1)
        }
        if (sums.isNotEmpty()) seasonsCinemeta[m.id] = sums.mapValues { it.value.first / it.value.second }
        val v = meta.dbl("imdbRating")?.takeIf { it > 0 }
        imdb[m.id] = v
        if (v != null) remember(m.id, v)
        return v
    }

    /** Voto di ogni stagione: prima IMDb (OMDb, poi Cinemeta), altrimenti TMDB. */
    suspend fun seasonRatings(m: MediaItem): Pair<List<Pair<Int, Double>>, String> {
        imdbRating(m)
        val key = EttyFlixApp.prefs.getString("omdb_api_key", "")!!.trim()
        if (key.isNotEmpty()) {
            val id = TMDB.imdbID("tv", m.tmdbID)
            val count = TMDB.get("tv/${m.tmdbID}")?.int("number_of_seasons") ?: 0
            if (id != null && count > 0) {
                val out = coroutineScope {
                    (1..minOf(count, 40)).map { se ->
                        async {
                            val eps = Http.get("https://www.omdbapi.com/?i=$id&Season=$se&apikey=$key").json()?.arr("Episodes") ?: emptyList()
                            val vals = eps.mapNotNull { it.str("imdbRating")?.toDoubleOrNull() }
                            if (vals.isEmpty()) null else se to vals.average()
                        }
                    }.awaitAll().filterNotNull()
                }
                if (out.isNotEmpty()) return out to "IMDb"
            }
        }
        seasonsCinemeta[m.id]?.takeIf { it.isNotEmpty() }?.let { return it.toSortedMap().map { e -> e.key to e.value } to "IMDb" }
        val t = TMDB.get("tv/${m.tmdbID}")?.arr("seasons")?.mapNotNull { s ->
            val n = s.int("season_number") ?: return@mapNotNull null
            val v = s.dbl("vote_average") ?: return@mapNotNull null
            if (n > 0 && v > 0) n to v else null
        } ?: emptyList()
        return t to "TMDB"
    }
}

// MARK: Salta intro (IntroDB)

data class SkipSegment(val kind: String, val start: Double, val end: Double) {
    val label get() = when (kind) { "recap" -> "Salta riassunto"; "outro" -> "Salta titoli di coda"; else -> "Salta intro" }
}

/** Tempi di riassunto, sigla e titoli di coda da tre archivi gratuiti: IntroDB, TheIntroDB (anche film) e SkipDB. */
object IntroService {
    private val cache = HashMap<String, List<SkipSegment>>()

    fun merge(primary: List<SkipSegment>, extra: List<SkipSegment>): List<SkipSegment> {
        val out = primary.toMutableList()
        for (e in extra) if (out.none { it.kind == e.kind }) out += e
        return out.sortedBy { it.start }
    }

    private fun seg(kind: String, o: org.json.JSONObject?): SkipSegment? {
        if (o == null) return null
        val start = o.dbl("start_sec") ?: o.dbl("start_ms")?.div(1000)
        val end = o.dbl("end_sec") ?: o.dbl("end_ms")?.div(1000)
        if (start == null && end == null) return null
        val a = start ?: 0.0; val b = end ?: Double.MAX_VALUE
        return if (b > a) SkipSegment(kind, a, b) else null
    }

    private suspend fun fetch(url: String) = Http.get(url, mapOf("Accept" to "application/json"), 10).let { if (it.ok) it.json() else null }

    /** Serie: season ed episode; film: entrambi null. */
    suspend fun segments(tmdbID: Int, season: Int?, episode: Int?): List<SkipSegment> = kotlinx.coroutines.coroutineScope {
        val movie = season == null || episode == null
        val key = "${if (movie) "m" else "t"}$tmdbID-${season ?: 0}-${episode ?: 0}"
        cache[key]?.let { return@coroutineScope it }
        val imdb = TMDB.imdbID(if (movie) "movie" else "tv", tmdbID)
        val ep = if (movie) "" else "&season=$season&episode=$episode"
        val a = async {
            if (movie || imdb == null) emptyList() else fetch("https://api.introdb.app/segments?imdb_id=$imdb$ep")
                ?.let { j -> listOf("recap", "intro", "outro").mapNotNull { seg(it, j.optJSONObject(it)) } } ?: emptyList()
        }
        val b = async {
            fetch("https://api.theintrodb.org/v3/media?tmdb_id=$tmdbID$ep")?.let { j ->
                listOf("recap" to "recap", "intro" to "intro", "credits" to "outro").mapNotNull { (field, kind) ->
                    val arr = j.optJSONArray(field) ?: return@mapNotNull null
                    (0 until arr.length()).firstNotNullOfOrNull { seg(kind, arr.optJSONObject(it)) }
                }
            } ?: emptyList()
        }
        val c = async {
            if (imdb == null) emptyList() else fetch("https://skipdb.tv/api/segments?imdb_id=$imdb$ep")?.let { j ->
                val root = j.optJSONObject("segments")
                listOf("recap", "intro", "outro").mapNotNull { seg(it, root?.optJSONObject(it)) ?: seg(it, j.optJSONObject(it)) }
            } ?: emptyList()
        }
        val out = merge(merge(a.await(), b.await()), c.await())
        cache[key] = out
        out
    }

    suspend fun segments(tmdbID: Int, season: Int, episode: Int): List<SkipSegment> =
        segments(tmdbID, season as Int?, episode as Int?)
}
