package com.lorenzo.ettyflix.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lorenzo.ettyflix.EttyFlixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

// Gusti: come sul Mac (Taste.swift). Tutto il calcolo lo fa l'app da sola; l'IA riceve solo un riassunto.

/** Caratteristiche di un titolo (da TMDB) usate per capire i gusti. */
data class TasteFeatures(
    val genres: List<Int>,
    /** id parola chiave → nome (in inglese, come su TMDB) */
    val keywords: Map<String, String>,
    /** "a123" = attore, "d456" = regista o creatore → nome */
    val people: Map<String, String>,
    val year: Int,
    val vote: Double,
    val votes: Int,
    val runtime: Int
) {
    fun toJson(): JSONObject = JSONObject()
        .put("genres", org.json.JSONArray(genres))
        .put("keywords", JSONObject(keywords as Map<*, *>))
        .put("people", JSONObject(people as Map<*, *>))
        .put("year", year).put("vote", vote).put("votes", votes).put("runtime", runtime)

    companion object {
        fun from(o: JSONObject): TasteFeatures {
            fun map(k: String): Map<String, String> {
                val m = o.optJSONObject(k) ?: return emptyMap()
                return m.keys().asSequence().associateWith { m.optString(it) }
            }
            return TasteFeatures(o.ints("genres"), map("keywords"), map("people"),
                o.int("year") ?: 0, o.dbl("vote") ?: 0.0, o.int("votes") ?: 0, o.int("runtime") ?: 0)
        }
    }
}

/** Archivio delle caratteristiche (comune a tutti i profili, salvato su disco). */
object TasteFeatureStore {
    private const val FILE = "gusti_titoli.json"
    private val map = HashMap<String, TasteFeatures>()
    private val pending = HashSet<String>()
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        DataDir.read(FILE)?.let { t ->
            runCatching {
                val o = JSONObject(t)
                for (k in o.keys()) o.optJSONObject(k)?.let { map[k] = TasteFeatures.from(it) }
            }
        }
    }

    fun get(id: String): TasteFeatures? { load(); return map[id] }
    val all: Map<String, TasteFeatures> get() { load(); return map }

    /** Scarica quelle che mancano (al massimo `limit` per volta, 6 alla volta). */
    suspend fun fetch(list: List<Pair<String, Int>>, limit: Int = 40) {
        load()
        val todo = list.asSequence()
            .filter { it.first == "movie" || it.first == "tv" }
            .map { "${it.first}-${it.second}" to it }
            .distinctBy { it.first }
            .filter { it.first !in map && it.first !in pending }
            .take(limit).toList()
        if (todo.isEmpty()) return
        todo.forEach { pending += it.first }
        for (chunk in todo.chunked(6)) {
            val got = coroutineScope { chunk.map { (k, p) -> async { k to download(p.first, p.second) } }.awaitAll() }
            for ((k, f) in got) { pending -= k; if (f != null) map[k] = f }
        }
        val text = JSONObject().apply { map.forEach { (k, f) -> put(k, f.toJson()) } }.toString()
        withContext(Dispatchers.IO) { DataDir.write(FILE, text) }
    }

    private suspend fun download(type: String, id: Int): TasteFeatures? {
        val j = TMDB.get("$type/$id", mapOf("append_to_response" to "keywords,credits")) ?: return null
        val kwList = if (type == "movie") j.obj("keywords").arr("keywords") else j.obj("keywords").arr("results")
        val kw = LinkedHashMap<String, String>()
        for (k in kwList.take(25)) { val kid = k.int("id") ?: continue; kw[kid.toString()] = k.str("name") ?: continue }
        val people = LinkedHashMap<String, String>()
        for (c in j.obj("credits").arr("cast").take(6)) { val pid = c.int("id") ?: continue; people["a$pid"] = c.str("name") ?: continue }
        if (type == "movie") {
            for (c in j.obj("credits").arr("crew")) if (c.str("job") == "Director") {
                val pid = c.int("id") ?: continue; people["d$pid"] = c.str("name") ?: continue
            }
        } else {
            for (c in j.arr("created_by")) { val pid = c.int("id") ?: continue; people["d$pid"] = c.str("name") ?: continue }
        }
        val date = j.str("release_date") ?: j.str("first_air_date") ?: ""
        val runtime = j.int("runtime") ?: j.ints("episode_run_time").firstOrNull() ?: 0
        return TasteFeatures(j.arr("genres").mapNotNull { it.int("id") }, kw, people,
            date.take(4).toIntOrNull() ?: 0, j.dbl("vote_average") ?: 0.0, j.int("vote_count") ?: 0, runtime)
    }
}

/** Un titolo che dice qualcosa sui gusti, con il suo peso (positivo = piace). */
data class TasteSeed(val id: String, val type: String, val tmdbID: Int, val title: String, val weight: Double, val rating: Int?)

/** I gusti di un profilo: pesi per generi, temi, persone ed epoche. */
class TasteVector(
    val genres: Map<Int, Double> = emptyMap(),
    val keywords: Map<String, Double> = emptyMap(),
    val keywordNames: Map<String, String> = emptyMap(),
    val people: Map<String, Double> = emptyMap(),
    val peopleCount: Map<String, Int> = emptyMap(),
    val peopleNames: Map<String, String> = emptyMap(),
    val decades: Map<Int, Double> = emptyMap(),
    val seeds: List<TasteSeed> = emptyList(),
    val seen: Set<String> = emptySet()
) {
    private val maxG = max(0.5, genres.values.maxOfOrNull { abs(it) } ?: 1.0)
    private val maxK = max(0.5, keywords.values.maxOfOrNull { abs(it) } ?: 1.0)
    private val maxP = max(0.5, people.values.maxOfOrNull { abs(it) } ?: 1.0)
    private val maxD = max(0.5, decades.values.maxOfOrNull { abs(it) } ?: 1.0)

    /** servono almeno 4 titoli che piacciono per dare percentuali sensate */
    val usable: Boolean get() = seeds.count { it.weight > 0 } >= 4

    fun normGenre(g: Int) = (genres[g] ?: 0.0) / maxG

    /** Affinità grezza da -1 a 1 (null se non so abbastanza del titolo). */
    fun raw(item: MediaItem, f: TasteFeatures?): Double? {
        val gs = f?.genres ?: item.genreIDs
        if (gs.isEmpty()) return null
        var sum = 0.0; var wsum = 0.0
        fun add(v: Double, w: Double) { sum += v.coerceIn(-1.0, 1.0) * w; wsum += w }
        add(gs.map { (genres[it] ?: 0.0) / maxG }.average(), 0.40)
        if (f != null && f.keywords.isNotEmpty()) {
            val k = f.keywords.keys.sumOf { (keywords[it] ?: 0.0) / maxK }
            add(k / max(2.0, sqrt(f.keywords.size.toDouble())) * 2.5, 0.25)
        }
        if (f != null && f.people.isNotEmpty()) add(f.people.keys.sumOf { (people[it] ?: 0.0) / maxP } * 1.5, 0.12)
        val year = f?.year ?: item.year.take(4).toIntOrNull() ?: 0
        if (year > 1900) add((decades[year / 10 * 10] ?: 0.0) / maxD, 0.08)
        val vote = if ((f?.vote ?: 0.0) > 0) f!!.vote else item.rating
        if (vote > 0) add((vote - 6.5) / 2, 0.15)
        return if (wsum > 0) sum / wsum else null
    }

    /** Percentuale "per te" (1–99). */
    fun percent(item: MediaItem, f: TasteFeatures?): Int? {
        if (!usable) return null
        val r = raw(item, f) ?: return null
        return Math.round(50 + 49 * tanh(2.2 * r)).toInt().coerceIn(1, 99)
    }

    val topGenres: List<Int> get() = genres.filter { it.value > 0 }.entries.sortedByDescending { it.value }.map { it.key }
    val badGenres: List<Int> get() = genres.filter { it.value < -0.3 }.entries.sortedBy { it.value }.map { it.key }

    companion object {
        fun build(seeds: List<TasteSeed>, seen: Set<String>, features: Map<String, TasteFeatures>): TasteVector {
            val g = HashMap<Int, Double>(); val k = HashMap<String, Double>(); val kn = HashMap<String, String>()
            val p = HashMap<String, Double>(); val pc = HashMap<String, Int>(); val pn = HashMap<String, String>()
            val d = HashMap<Int, Double>()
            for (s in seeds) {
                val f = features[s.id] ?: continue
                val w = s.weight
                val gn = max(1.0, sqrt(f.genres.size.toDouble()))
                for (x in f.genres) g[x] = (g[x] ?: 0.0) + w / gn
                val knn = max(1.0, sqrt(f.keywords.size.toDouble()))
                for ((id, n) in f.keywords) { k[id] = (k[id] ?: 0.0) + w / knn; kn[id] = n }
                for ((id, n) in f.people) {
                    p[id] = (p[id] ?: 0.0) + w * (if (id.startsWith("d")) 1.0 else 0.7)
                    pn[id] = n
                    if (w > 0) pc[id] = (pc[id] ?: 0) + 1
                }
                if (f.year > 1900) { val dec = f.year / 10 * 10; d[dec] = (d[dec] ?: 0.0) + w }
            }
            return TasteVector(g, k, kn, p, pc, pn, d, seeds, seen)
        }
    }
}

/** Un consiglio scartato con "Non fa per me". */
@Serializable
data class AIRejection(val item: MediaItem, val reason: String, val date: Double)

enum class RejectReason(val label: String) {
    SEEN("L'ho già visto"), GENRE("Genere che non mi piace"), MEH("Non mi ispira"), LONG("Troppo lungo");
    companion object { fun of(s: String) = entries.firstOrNull { it.label == s } ?: SEEN }
}

/** Gusti del profilo attivo. */
object TasteProfile {
    var mine by mutableStateOf(TasteVector())
        private set
    var version by mutableIntStateOf(0)
        private set
    var rejections by mutableStateOf(listOf<AIRejection>())
        private set

    val rejectedIDs: Set<String> get() = rejections.map { it.item.id }.toSet()

    suspend fun rebuild() {
        val owner = Profiles.currentID
        loadRejections()
        val entries = WatchProgress.entries.values.toList()
        val seeds = seeds(entries, Store.ratings, Store.disliked, Store.abandoned, rejections)
        TasteFeatureStore.fetch(seeds.map { it.type to it.tmdbID }, 80)
        if (Profiles.currentID != owner) return   // nel frattempo hai cambiato profilo
        mine = TasteVector.build(seeds, entries.map { "${it.mediaType}-${it.tmdbID}" }.toSet(), TasteFeatureStore.all)
        version++
    }

    fun featuresChanged() { version++ }

    /** Gusti di un altro profilo (per "Da vedere insieme"), letti dai suoi file. */
    suspend fun vector(profileID: String): TasteVector {
        val pre = Profiles.prefix(profileID)
        fun list(n: String): List<MediaItem> = DataDir.read(pre + n)?.let {
            runCatching { AppJson.decodeFromString(ListSerializer(MediaItem.serializer()), it) }.getOrNull()
        } ?: emptyList()
        val ratings = DataDir.read(pre + "ratings.json")?.let { t ->
            runCatching { val o = JSONObject(t); o.keys().asSequence().associateWith { o.getInt(it) } }.getOrNull()
        } ?: emptyMap()
        val rej = DataDir.read(pre + "consigli_scartati.json")?.let {
            runCatching { AppJson.decodeFromString(ListSerializer(AIRejection.serializer()), it) }.getOrNull()
        } ?: emptyList()
        val entries = WatchProgress.readEntries(profileID)
        val seeds = seeds(entries, ratings, list("disliked.json"), list("abandoned.json"), rej)
        TasteFeatureStore.fetch(seeds.map { it.type to it.tmdbID }, 80)
        return TasteVector.build(seeds, entries.map { "${it.mediaType}-${it.tmdbID}" }.toSet(), TasteFeatureStore.all)
    }

    fun seeds(entries: List<WatchEntry>, ratings: Map<String, Int>, disliked: List<MediaItem>,
              abandoned: List<MediaItem>, rejections: List<AIRejection>): List<TasteSeed> {
        val title = HashMap<String, String>()
        val weights = HashMap<String, Double>()
        val lastSeen = HashMap<String, Double>()
        val tvEpisodes = HashMap<String, Int>()
        for (e in entries) {
            val k = "${e.mediaType}-${e.tmdbID}"
            title[k] = e.title
            lastSeen[k] = max(lastSeen[k] ?: 0.0, e.updated)
            if (e.mediaType == "movie") { if (e.finished || e.fraction >= 0.75) weights[k] = 0.8 }
            else if (e.finished || e.fraction >= 0.75) tvEpisodes[k] = (tvEpisodes[k] ?: 0) + 1
        }
        for ((k, n) in tvEpisodes) if (n >= 2) weights[k] = if (n >= 6) 1.0 else 0.6
        for (r in rejections) {
            title[r.item.id] = r.item.title
            when (RejectReason.of(r.reason)) {
                RejectReason.GENRE -> weights[r.item.id] = -1.0
                RejectReason.MEH -> weights[r.item.id] = -0.4
                else -> {}
            }
        }
        for (m in abandoned) { title[m.id] = m.title; weights[m.id] = -1.2 }
        for (m in disliked) { title[m.id] = m.title; weights[m.id] = -2.0 }
        // il voto vince su tutto
        for ((k, r) in ratings) weights[k] = when {
            r >= 9 -> 2.2; r == 8 -> 1.6; r == 7 -> 1.0; r == 6 -> 0.4; r == 5 -> -0.3; else -> -1.6
        }
        val out = ArrayList<TasteSeed>()
        for (k in weights.keys.sortedByDescending { lastSeen[it] ?: 0.0 }) {
            val w = weights[k] ?: continue
            if (w == 0.0) continue
            if (out.size >= 200 && ratings[k] == null) continue
            val parts = k.split("-")
            val id = parts.getOrNull(1)?.toIntOrNull() ?: continue
            out += TasteSeed(k, parts[0], id, title[k] ?: "", w, ratings[k])
        }
        return out
    }

    /** "94% per te" (null: non so abbastanza, già visto, votato o scartato). */
    fun percent(item: MediaItem): Int? {
        val v = mine
        if (!v.usable || item.id in v.seen || Store.ratings.containsKey(item.id) || item.id in Store.notInterestedIDs) return null
        return v.percent(item, TasteFeatureStore.get(item.id))
    }

    /** Ordina per "quanto ti piacerà" (quelli senza percentuale in fondo, nel loro ordine). */
    fun sortedForYou(list: List<MediaItem>): List<MediaItem> {
        val score = list.associate { it.id to (percent(it) ?: -1) }
        return list.withIndex().sortedWith(compareByDescending<IndexedValue<MediaItem>> { score[it.value.id] ?: -1 }.thenBy { it.index })
            .map { it.value }
    }

    /** Perché te lo consiglio: titolo simile che ti è piaciuto, persone e generi in comune. */
    fun reason(item: MediaItem): String? {
        val v = mine
        val f = TasteFeatureStore.get(item.id) ?: return null
        if (!v.usable) return null
        val parts = ArrayList<String>()
        var best: TasteSeed? = null
        var bestScore = 0.0
        for (s in v.seeds) {
            if (s.weight < 1.0 || s.id == item.id) continue
            val sf = TasteFeatureStore.get(s.id) ?: continue
            val score = f.genres.intersect(sf.genres.toSet()).size + 2.0 * f.keywords.keys.intersect(sf.keywords.keys).size +
                3.0 * f.people.keys.intersect(sf.people.keys).size + s.weight * 0.5
            if (score >= 4 && score > bestScore) { best = s; bestScore = score }
        }
        best?.takeIf { it.title.isNotEmpty() }?.let { s ->
            parts += if (s.rating != null) "Simile a «${s.title}», che hai votato ${s.rating}" else "Simile a «${s.title}», che hai visto"
        }
        f.people.keys.filter { (v.people[it] ?: 0.0) > 0.5 }.maxByOrNull { v.people[it] ?: 0.0 }?.let { p ->
            val name = f.people[p] ?: ""
            parts += if (p.startsWith("d")) (if (item.mediaType == "movie") "regia di $name" else "di $name") else "con $name"
        }
        val liked = f.genres.filter { (v.genres[it] ?: 0.0) > 0 }.sortedByDescending { v.genres[it] ?: 0.0 }
            .mapNotNull { Store.genreNames[it]?.lowercase() }.take(2)
        if (liked.isNotEmpty()) parts += "generi che ami: " + liked.joinToString(", ")
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /** Profilo dei gusti in poche righe, per l'IA. */
    fun promptSummary(): String {
        val v = mine
        if (!v.usable) return ""
        val names = Store.genreNames
        var out = "\nProfilo dei gusti (calcolato dai suoi voti e da cosa ha visto):"
        v.topGenres.take(5).mapNotNull { names[it] }.takeIf { it.isNotEmpty() }?.let { out += "\n- Generi preferiti: " + it.joinToString(", ") }
        v.badGenres.take(4).mapNotNull { names[it] }.takeIf { it.isNotEmpty() }?.let { out += "\n- Generi da evitare: " + it.joinToString(", ") }
        v.keywords.filter { it.value > 0.4 }.entries.sortedByDescending { it.value }.take(12).mapNotNull { v.keywordNames[it.key] }
            .takeIf { it.isNotEmpty() }?.let { out += "\n- Temi che ama: " + it.joinToString(", ") }
        v.keywords.filter { it.value < -0.4 }.entries.sortedBy { it.value }.take(8).mapNotNull { v.keywordNames[it.key] }
            .takeIf { it.isNotEmpty() }?.let { out += "\n- Temi che non gli piacciono: " + it.joinToString(", ") }
        v.people.filter { it.value > 0.8 && (v.peopleCount[it.key] ?: 0) >= 2 }.entries.sortedByDescending { it.value }.take(8)
            .mapNotNull { v.peopleNames[it.key] }.takeIf { it.isNotEmpty() }?.let { out += "\n- Attori, registi e autori che segue: " + it.joinToString(", ") }
        v.decades.filter { it.value > 0 }.entries.sortedByDescending { it.value }.take(2).map { "anni ${it.key}" }
            .takeIf { it.isNotEmpty() }?.let { out += "\n- Epoche preferite: " + it.joinToString(", ") }
        val rej = rejections.take(15).map { "- ${it.item.title} (${it.reason.lowercase()})" }
        if (rej.isNotEmpty()) out += "\nConsigli che ha scartato (non riproporli e non proporre niente di troppo simile):\n" + rej.joinToString("\n")
        if (rejections.count { it.reason == RejectReason.LONG.label } >= 2)
            out += "\nPreferisce titoli non troppo lunghi (film sotto le 2 ore e un quarto, serie brevi)."
        return out
    }

    // "Non fa per me"

    private val rejFile get() = Profiles.file("consigli_scartati.json")

    fun loadRejections() {
        rejections = DataDir.read(rejFile)?.let {
            runCatching { AppJson.decodeFromString(ListSerializer(AIRejection.serializer()), it) }.getOrNull()
        } ?: emptyList()
    }

    fun reject(item: MediaItem, reason: RejectReason) {
        rejections = (listOf(AIRejection(item, reason.label, System.currentTimeMillis() / 1000.0)) +
            rejections.filter { it.item.id != item.id }).take(300)
        DataDir.write(rejFile, AppJson.encodeToString(ListSerializer(AIRejection.serializer()), rejections))
        AI.removePick(item.id)
        ForYouModel.remove(item.id)
    }
}

// MARK: Nuove righe della Home

data class ForYouRow(val id: String, val title: String, val items: List<MediaItem>, val captions: Map<String, String> = emptyMap())

object ForYouModel {
    var rows by mutableStateOf(listOf<ForYouRow>())
        private set
    var weekend by mutableStateOf<ForYouRow?>(null)
        private set
    private var builtKey = ""
    private var builtAt = 0L
    private var gen = 0

    fun remove(id: String) {
        rows = rows.map { it.copy(items = it.items.filter { m -> m.id != id }) }
        weekend = weekend?.let { it.copy(items = it.items.filter { m -> m.id != id }) }
    }

    /** Costruisce le righe (al massimo ogni 6 ore, o subito se cambi profilo). */
    suspend fun load(force: Boolean = false) {
        val v = TasteProfile.mine
        if (!Store.hasKey || !v.usable) return
        val owner = Profiles.currentID
        val key = owner + "|" + todayString()
        val now = System.currentTimeMillis()
        if (!force && key == builtKey && now - builtAt < 6 * 3_600_000L && rows.isNotEmpty()) return
        if (owner != builtKey.substringBefore("|")) { rows = emptyList(); weekend = null }
        val myGen = ++gen
        val skip = Store.notInterestedIDs + TasteProfile.rejectedIDs
        fun fresh(list: List<MediaItem>): List<MediaItem> {
            val ids = HashSet<String>()
            return list.filter { it.id !in v.seen && it.id !in skip && it.posterPath != null && ids.add(it.id) }
        }
        suspend fun ranked(list: List<MediaItem>, keep: Int = 20): List<MediaItem> {
            val first = fresh(list).sortedByDescending { v.raw(it, null) ?: -2.0 }.take(keep + 8)
            TasteFeatureStore.fetch(first.map { it.mediaType to it.tmdbID }, 28)
            return first.sortedByDescending { v.raw(it, TasteFeatureStore.get(it.id)) ?: -2.0 }.take(keep)
        }
        val out = ArrayList<ForYouRow>()

        // 1) Perché ti è piaciuto X (solo voti 8–10; cambiano ogni giorno)
        val loved = v.seeds.filter { it.weight >= 1.6 }
        if (loved.isNotEmpty()) {
            val start = (Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) % loved.size
            val picked = listOfNotNull(loved[start], if (loved.size > 1) loved[(start + 1) % loved.size] else null)
            for (s in picked) {
                val items = ranked(runCatching { TMDB.recommendations(s.type, s.tmdbID) }.getOrDefault(emptyList()))
                if (items.size >= 4) out += ForYouRow("loved-${s.id}",
                    if (s.rating != null) "Perché hai votato ${s.rating} «${s.title}»" else "Perché ti è piaciuto «${s.title}»", items)
            }
        }

        // 2) Gemme nascoste: ottimi voti, poco famose, nei tuoi generi
        val good = v.topGenres.take(3).joinToString("|")
        val bad = v.badGenres.take(4).joinToString(",")
        if (good.isNotEmpty()) {
            val gems = ArrayList<MediaItem>()
            for ((type, minV, maxV) in listOf(Triple("movie", "150", "2500"), Triple("tv", "80", "1500"))) {
                val p = mutableMapOf("with_genres" to good, "vote_average.gte" to "7.2", "vote_count.gte" to minV,
                    "vote_count.lte" to maxV, "sort_by" to "vote_average.desc")
                if (bad.isNotEmpty()) p["without_genres"] = bad
                gems += TMDB.items(SourceDef("discover", "Gemme", type, p), 2)
            }
            val items = ranked(gems)
            if (items.size >= 4) out += ForYouRow("gems", "Gemme nascoste per te", items)
        }

        // 3) In arrivo per te (prossimi 4 mesi)
        if (good.isNotEmpty()) {
            val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val from = f.format(java.util.Date())
            val to = f.format(java.util.Date(now + 120L * 86_400_000L))
            val items = ArrayList<MediaItem>()
            val dates = HashMap<String, String>()
            for (type in listOf("movie", "tv")) {
                val dk = if (type == "movie") "primary_release_date" else "first_air_date"
                val p = mutableMapOf("with_genres" to good, "$dk.gte" to from, "$dk.lte" to to, "sort_by" to "popularity.desc",
                    "include_adult" to "false", "page" to "1")
                if (bad.isNotEmpty()) p["without_genres"] = bad
                for (x in TMDB.get("discover/$type", p)?.arr("results") ?: emptyList()) {
                    val m = MediaItem.from(x, type) ?: continue
                    if (16 in m.genreIDs) continue
                    items += m
                    dates[m.id] = x.str("release_date") ?: x.str("first_air_date") ?: ""
                }
            }
            val sorted = fresh(items).sortedByDescending { v.raw(it, null) ?: -2.0 }.take(20)
            if (sorted.size >= 4) {
                val outF = SimpleDateFormat("d MMMM", Locale.ITALIAN)
                val caps = HashMap<String, String>()
                for (m in sorted) {
                    val d = dates[m.id]?.let { runCatching { f.parse(it) }.getOrNull() } ?: continue
                    caps[m.id] = (if (m.mediaType == "movie") "Esce il " else "Debutta il ") + outF.format(d)
                }
                out += ForYouRow("upcoming", "In arrivo per te", sorted.sortedBy { dates[it.id] ?: "" }, caps)
            }
        }

        // 4) Da vedere insieme (con l'altro profilo). Calcolo simmetrico: la stessa lista nei due profili.
        val other = Profiles.list.firstOrNull { it.id != Profiles.currentID }
        if (other != null) {
            val ov = TasteProfile.vector(other.id)
            if (ov.usable) {
                val both = v.genres.filter { it.value > 0 }.keys.intersect(ov.genres.filter { it.value > 0 }.keys)
                val shared = both.sortedWith(compareByDescending<Int> { min(v.normGenre(it), ov.normGenre(it)) }.thenBy { it })
                    .take(3).joinToString("|")
                val cands = ArrayList<MediaItem>()
                if (shared.isNotEmpty()) for (type in listOf("movie", "tv")) {
                    cands += TMDB.items(SourceDef("discover", "Insieme", type, mapOf("with_genres" to shared,
                        "vote_average.gte" to "7", "vote_count.gte" to "300", "sort_by" to "popularity.desc")), 2)
                }
                for (x in listOf(v, ov)) {
                    x.seeds.filter { it.weight >= 1.6 }.sortedWith(compareByDescending<TasteSeed> { it.weight }.thenBy { it.id })
                        .firstOrNull()?.let { cands += runCatching { TMDB.recommendations(it.type, it.tmdbID) }.getOrDefault(emptyList()) }
                }
                val ids = HashSet<String>()
                val pool = cands.filter { it.id !in v.seen && it.id !in ov.seen && it.id !in skip && it.posterPath != null && ids.add(it.id) }
                    .sortedBy { it.id }
                fun pre(m: MediaItem) = min(v.raw(m, null) ?: -2.0, ov.raw(m, null) ?: -2.0)
                val first = pool.sortedWith(compareByDescending<MediaItem> { pre(it) }.thenBy { it.id }).take(28)
                TasteFeatureStore.fetch(first.map { it.mediaType to it.tmdbID }, 28)
                fun pair(m: MediaItem): Pair<Int, Int> {
                    val f = TasteFeatureStore.get(m.id)
                    return (v.percent(m, f) ?: 0) to (ov.percent(m, f) ?: 0)
                }
                val items = first.sortedWith(compareByDescending<MediaItem> { min(pair(it).first, pair(it).second) }.thenBy { it.id }).take(20)
                if (items.size >= 4) {
                    val me = Profiles.current
                    val meFirst = Profiles.list.indexOfFirst { it.id == me.id } <= Profiles.list.indexOfFirst { it.id == other.id }
                    val caps = items.associate { m ->
                        val p = pair(m)
                        m.id to (if (meFirst) "${me.name} ${p.first}% · ${other.name} ${p.second}%" else "${other.name} ${p.second}% · ${me.name} ${p.first}%")
                    }
                    out += ForYouRow("together", "Da vedere con ${other.name}", items, caps)
                }
            }
        }

        val wk = weekendRow(out)
        if (myGen != gen || Profiles.currentID != owner) return
        rows = out
        weekend = wk
        builtKey = key
        builtAt = now
        TasteProfile.featuresChanged()
    }

    /** Venerdì, sabato e domenica: nuove puntate della settimana + i film che potrebbero piacerti di più. */
    private suspend fun weekendRow(rows: List<ForYouRow>): ForYouRow? {
        val wd = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        if (wd != Calendar.FRIDAY && wd != Calendar.SATURDAY && wd != Calendar.SUNDAY) return null
        runCatching { CalendarModel.load() }
        val cal = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY; set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) }
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val from = f.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, 6)
        val to = f.format(cal.time)
        val eps = CalendarModel.episodes.filter { it.airDate in from..to }
        val started = WatchProgress.entries.values.map { it.tmdbID }.toSet()
        val pool = Store.watchlist.filter { it.mediaType == "movie" && it.tmdbID !in started } +
            AI.moviePicks.map { it.item } + rows.filter { it.id != "upcoming" }.flatMap { it.items }.filter { it.mediaType == "movie" }
        val movies = pool.distinctBy { it.id }.filter { !Store.isNotInterested(it) && it.id !in TasteProfile.rejectedIDs }
            .mapNotNull { m -> TasteProfile.percent(m)?.let { m to it } }.sortedByDescending { it.second }.take(5).map { it.first }
        if (eps.isEmpty() && movies.isEmpty()) return null
        val items = ArrayList<MediaItem>()
        val caps = HashMap<String, String>()
        for (e in eps) if (items.none { it.id == e.show.id }) { items += e.show; caps[e.show.id] = "Nuova puntata ${e.code}" }
        for (m in movies) if (items.none { it.id == m.id }) { items += m; TasteProfile.percent(m)?.let { caps[m.id] = "$it% per te" } }
        return ForYouRow("weekend", "Il tuo weekend", items, caps)
    }
}

// MARK: Voto a fine visione

data class RatingAsk(val item: MediaItem, val subtitle: String, val key: String)

object RatingPrompt {
    var current by mutableStateOf<RatingAsk?>(null)
    private var candidate: WatchEntry? = null
    private val askedKey get() = Profiles.key("rating_prompt_asked")

    /** Chiamata quando un film o un episodio arriva alla fine (90%). */
    fun finished(e: WatchEntry) { candidate = e }

    /** Chiamata quando il player si chiude: se hai finito un film o una stagione, chiede il voto. */
    suspend fun playerClosed() {
        val e = candidate ?: return
        candidate = null
        if (!EttyFlixApp.prefs.getBoolean("rating_prompt", true)) return
        val item = e.media
        if (Store.ratings.containsKey(item.id) || Store.isNotInterested(item)) return
        val asked = (EttyFlixApp.prefs.getString(askedKey, "") ?: "").split("\n").filter { it.isNotBlank() }
        var key = item.id
        var subtitle = "Hai finito il film"
        if (e.mediaType == "tv") {
            val s = e.season ?: return
            val n = e.episode ?: return
            if (s <= 0) return
            val j = TMDB.get("tv/${e.tmdbID}") ?: return
            val count = j.arr("seasons").firstOrNull { it.int("season_number") == s }?.int("episode_count") ?: 0
            val last = j.obj("last_episode_to_air")
            val lastAired = last.int("season_number") == s && last.int("episode_number") == n
            if (!((count > 0 && n >= count) || lastAired)) return
            key = "${item.id}-s$s"
            subtitle = "Hai finito la stagione $s"
        }
        if (key in asked) return
        EttyFlixApp.prefs.edit().putString(askedKey, (asked + key).takeLast(500).joinToString("\n")).apply()
        delay(1200)
        val ask = RatingAsk(item, subtitle, key)
        current = ask
        delay(45_000)
        if (current == ask) current = null   // se non rispondi sparisce da solo (non te lo richiede)
    }

    fun rate(ask: RatingAsk, value: Int?) {
        if (value != null) Store.setRating(ask.item, value)
        if (current == ask) current = null
    }
}
