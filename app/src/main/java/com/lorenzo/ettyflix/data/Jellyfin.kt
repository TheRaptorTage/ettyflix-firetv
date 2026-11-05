package com.lorenzo.ettyflix.data

import com.lorenzo.ettyflix.EttyFlixApp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Locale

/** Codec audio scritto nella descrizione (DD+, AC3, DTS…), per quando il server non lo dichiara. */
fun guessAudioCodec(raw: String): String {
    val t = raw.lowercase()
    fun has(p: String) = Regex(p).containsMatchIn(t)
    return when {
        has("truehd") -> "TRUEHD"
        has("(dd\\+|ddp|e-?ac-?3|dolby digital plus)") -> "EAC3"
        has("dts") -> "DTS"
        has("(\\bac-?3\\b|\\bdd[ .]?[0-9]|\\bdd\\b|dolby digital)") -> "AC3"
        has("\\baac") -> "AAC"
        has("\\bopus\\b") -> "OPUS"
        has("\\bflac\\b") -> "FLAC"
        else -> ""
    }
}

/** Canali scritti nella descrizione (7.1, 5.1, 2.0). */
fun guessChannels(raw: String): Int {
    val t = raw.lowercase()
    fun has(ch: String) = Regex("(?<![0-9.])$ch(?![0-9]| ?(gb|mb|mbps))").containsMatchIn(t)
    return when {
        has("7[ .]1") -> 8
        has("5[ .]1") -> 6
        has("2[ .]0") || t.contains("stereo") -> 2
        else -> 0
    }
}

data class StreamSource(
    val id: String,
    val itemID: String,
    val title: String,
    val size: Long,
    val container: String,
    val videoCodec: String,
    val height: Int,
    val hdr: Boolean,
    val dolbyVision: Boolean,
    val audioLangs: List<String>,
    val subLangs: List<String>,
    val fileName: String,
    val audioCodec: String,
    val audioChannels: Int,
    val url: String?,
    /** da dove arriva la fonte ("Addon di riserva" = dal server di riserva) */
    val origin: String = ""
) {
    val isBackup: Boolean get() = origin.contains("riserva")

    private val text: String get() = "$title $fileName".lowercase()
    private fun has(p: String) = Regex(p).containsMatchIn(text)

    val isReady: Boolean get() = title.contains("⚡") || text.contains("cached") || text.contains("[tb+]") || text.contains("instant")
    val isDolbyVision: Boolean get() = dolbyVision || has("\\b(dv|dovi|dolby ?vision)\\b")
    val isHDR: Boolean get() = hdr || isDolbyVision || has("\\b(hdr|hdr10|hdr10\\+|hlg)\\b")
    val isAtmos: Boolean get() = text.contains("atmos") || audioCodec.lowercase().contains("atmos")
    val isSurround: Boolean get() = audioChannels >= 6 || has("\\b(5[ .]1|7[ .]1)\\b")

    val releaseRank: Int get() {
        val t = text
        return when {
            t.contains("remux") -> 0
            t.contains("bluray") || t.contains("blu-ray") || t.contains("bdrip") || t.contains("brrip") -> 1
            t.contains("web-dl") || t.contains("webdl") || t.contains("web dl") -> 2
            t.contains("webrip") || t.contains("web-rip") -> 3
            t.contains("hdtv") -> 4
            t.contains("dvdrip") || t.contains("dvd") -> 5
            has("\\b(cam|hdcam|ts|telesync|tc)\\b") -> 7
            else -> 6
        }
    }

    private val textHeight: Int? get() = when {
        has("\\b(2160p|4k|uhd)\\b") -> 2160
        has("\\b1440p\\b") -> 1440
        has("\\b1080[pi]\\b") -> 1080
        has("\\b720p\\b") -> 720
        has("\\b(576p|480p|sd)\\b") -> 480
        else -> null
    }

    val mbps: Double? get() = Regex("([0-9]+([.,][0-9]+)?) ?mbps").find(text)?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()

    val isPack: Boolean get() {
        val m = mbps ?: return false
        if (size <= 0 || m <= 0) return false
        return size * 8 / (m * 1_000_000) / 3600 > 4
    }

    val effectiveHeight: Int get() {
        textHeight?.let { return it }
        if (isPack) return 0
        if (height >= 2000) {
            val m = mbps
            if (m != null && m < 6) return 0
            if (size in 1 until 2_500_000_000) return 0
        }
        return height
    }

    val resolutionLabel: String get() { val h = effectiveHeight; return if (h >= 2000) "4K" else if (h > 0) "${h}p" else "" }

    val sizeLabel: String get() = if (size <= 0) "" else {
        val gb = size / 1_000_000_000.0
        if (gb >= 1) String.format(Locale.ITALIAN, "%.1f GB", gb) else String.format(Locale.ITALIAN, "%.0f MB", size / 1_000_000.0)
    }

    val channelsLabel: String get() = when (audioChannels) { 0 -> ""; 1 -> "1.0"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; else -> "${audioChannels}ch" }

    val hasItalianAudio: Boolean get() {
        if (audioLangs.any { it.lowercase() == "it" || it.lowercase().startsWith("ita") }) return true
        val t = "$title $fileName"
        return Regex("\\b(ITA|ITALIAN|ITALIANO)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) || t.contains("🇮🇹")
    }

    /** Sottotitoli in italiano (anche forced): tracce del file o riga 📝 della descrizione. */
    val hasItalianSubs: Boolean get() {
        if (subLangs.any { it.lowercase() == "it" || it.lowercase().startsWith("ita") }) return true
        for (line in title.lines()) {
            if (!line.contains("📝")) continue
            val after = line.substringAfter("📝")
            if (Regex("\\b(ita|italian|italiano)\\b", RegexOption.IGNORE_CASE).containsMatchIn(after)) return true
        }
        return Regex("\\b(sub|subs|subbed|forced)[ ._-]?ita\\b", RegexOption.IGNORE_CASE).containsMatchIn("$title $fileName")
    }

    /** Nome dell'addon che ha trovato la fonte (Meteor, Comet, MediaFusion…), preso dalla descrizione. */
    val addonName: String get() = addonNameOf(title)

    /** Velocità che serve (Mbps): quella media del file con un margine del 25%. */
    fun neededMbps(runtime: Int?): Int? {
        if (isPack) return null
        var avg = mbps
        if (avg == null && size > 0 && runtime != null && runtime > 0) avg = size * 8.0 / (runtime * 60.0) / 1_000_000
        val a = avg ?: return null
        if (a <= 0) return null
        return maxOf(1, kotlin.math.ceil(a * 1.25).toInt())
    }

    val videoBadge: String? get() {
        val c = videoCodec.lowercase()
        return when {
            c.isEmpty() -> null
            c.contains("hevc") || c.contains("265") -> "HEVC"
            c.contains("264") || c.contains("avc") -> "H.264"
            c.contains("av1") -> "AV1"
            else -> videoCodec.uppercase()
        }
    }

    val audioBadge: String? get() {
        val c = audioCodec.lowercase()
        return when {
            c.isEmpty() -> null
            c.contains("truehd") -> "TrueHD"
            c.contains("dts") -> if (text.contains("dts-hd") || text.contains("dts hd") || text.contains("dts-x")) "DTS HD" else "DTS"
            c.contains("eac3") -> "EAC3"
            c.contains("ac3") -> "AC3"
            c.contains("aac") -> "AAC"
            c.contains("flac") -> "FLAC"
            c.contains("opus") -> "Opus"
            else -> audioCodec.uppercase()
        }
    }

    /** Fonte d'errore finta inviata dal server (es. "429 Too Many Requests"). */
    val isError: Boolean get() = title.contains("❌") || listOf("no stream", "no results", "too many requests", "nessuna fonte").any { text.contains(it) }

    fun toPlayer() = PlayerSource(url ?: "", resolutionLabel, videoCodec, sizeLabel, fileName, title)
}

object Jellyfin {
    val server: MediaServer? get() = Store.server

    private val deviceID: String by lazy {
        val p = EttyFlixApp.prefs
        p.getString("jf_device_id", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("jf_device_id", it).apply() }
    }

    private fun header(token: String?): String {
        var h = "MediaBrowser Client=\"StrandMac\", Device=\"Fire TV\", DeviceId=\"$deviceID\", Version=\"1.0\""
        if (token != null) h += ", Token=\"$token\""
        return h
    }

    private suspend fun send(path: String, method: String = "GET", query: Map<String, String> = emptyMap(), body: String? = null): HttpResult {
        val s = server ?: return HttpResult(0, "")
        val q = if (query.isEmpty()) "" else "?" + query.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value.enc()}" }
        return Http.request(s.url + path + q, method, body,
            mapOf("Authorization" to header(s.token), "X-Emby-Token" to s.token, "Accept" to "application/json"))
    }

    private suspend fun json(path: String, query: Map<String, String> = emptyMap()): JSONObject? {
        val r = send(path, query = query)
        return if (r.ok) r.json() else null
    }

    suspend fun check(): String {
        if (server == null) return "Nessun server configurato."
        val r1 = send("/System/Info/Public")
        if (r1.code == 0) return "Server non raggiungibile."
        if (!r1.ok) return "Il server risponde con errore ${r1.code}."
        val r2 = send("/Users/${server!!.userID}")
        return when {
            r2.ok -> "Connessione e accesso riusciti."
            r2.code == 401 || r2.code == 403 -> "Server raggiungibile ma l'accesso è scaduto: rimanda la configurazione dal Mac."
            else -> "Server raggiungibile, ma l'utente risponde con errore ${r2.code}."
        }
    }

    // MARK: Ricerca delle fonti

    private suspend fun findAllItems(m: MediaItem): List<JSONObject> {
        val s = server ?: return emptyList()
        val type = if (m.mediaType == "movie") "Movie" else "Series"
        val out = mutableListOf<JSONObject>()
        val seen = HashSet<String>()
        fun add(l: List<JSONObject>) { for (it in l) { val id = it.str("Id") ?: continue; if (seen.add(id)) out += it } }
        val base = mapOf("Recursive" to "true", "IncludeItemTypes" to type, "Fields" to "ProviderIds", "Limit" to "50")
        for (key in listOf("tmdb.${m.tmdbID}", "Tmdb.${m.tmdbID}")) {
            json("/Users/${s.userID}/Items", base + ("AnyProviderIdEquals" to key))?.let { add(it.arr("Items")) }
        }
        json("/Users/${s.userID}/Items", base + ("SearchTerm" to m.title))?.let { j ->
            val items = j.arr("Items")
            add(items.filter { it.obj("ProviderIds").str("Tmdb") == m.tmdbID.toString() })
            if (out.isEmpty()) add(items.filter { (it.str("Name") ?: "").equals(m.title, true) })
            if (out.isEmpty()) items.firstOrNull()?.let { add(listOf(it)) }
        }
        return out
    }

    private suspend fun findEpisode(seriesID: String, season: Int, episode: Int): JSONObject? {
        val s = server ?: return null
        json("/Shows/$seriesID/Episodes", mapOf("UserId" to s.userID, "Season" to "$season"))?.arr("Items")
            ?.firstOrNull { it.int("ParentIndexNumber") == season && it.int("IndexNumber") == episode }?.let { return it }
        return json("/Shows/$seriesID/Episodes", mapOf("UserId" to s.userID))?.arr("Items")
            ?.firstOrNull { it.int("ParentIndexNumber") == season && it.int("IndexNumber") == episode }
    }

    private suspend fun playbackInfo(itemID: String): List<JSONObject> {
        val s = server ?: return emptyList()
        val body = JSONObject().put("UserId", s.userID).put("AutoOpenLiveStream", false).toString()
        val r = send("/Items/$itemID/PlaybackInfo", "POST", mapOf("UserId" to s.userID), body)
        return if (r.ok) r.json()?.arr("MediaSources") ?: emptyList() else emptyList()
    }

    private suspend fun detailSources(itemID: String): List<JSONObject> {
        val s = server ?: return emptyList()
        return json("/Users/${s.userID}/Items/$itemID", mapOf("Fields" to "MediaSources"))?.arr("MediaSources") ?: emptyList()
    }

    private suspend fun sourcesFor(itemID: String, type: String, season: Int?, episode: Int?, fresh: Boolean): List<StreamSource> = coroutineScope {
        var target = itemID
        if (type == "tv" && season != null && episode != null) {
            target = findEpisode(itemID, season, episode)?.str("Id") ?: return@coroutineScope emptyList()
        }
        val s = server ?: return@coroutineScope emptyList()
        val a = async { playbackInfo(target) }
        val b = async { if (fresh) emptyList() else detailSources(target) }
        val seen = HashSet<String>()
        (a.await() + b.await()).mapIndexedNotNull { i, e ->
            val src = parse(e, target, s, i) ?: return@mapIndexedNotNull null
            val key = e.str("Id") ?: e.str("Path") ?: src.id
            if (seen.add(key)) src else null
        }
    }

    /** Tutte le fonti di un film o episodio. [progress] riceve i risultati man mano. */
    /** Il principale prima; se non trova nessuna fonte (es. è giù) chiedo all'addon di riserva.
     *  Ogni ricerca riparte dal principale, quindi appena torna a funzionare si usa di nuovo lui. */
    suspend fun allSources(m: MediaItem, season: Int?, episode: Int?, fresh: Boolean = false,
                           progress: (suspend (List<StreamSource>) -> Unit)? = null): List<StreamSource> {
        val first = runCatching { allSourcesOnServer(m, season, episode, fresh, progress) }.getOrDefault(emptyList())
        if (first.any { !it.isError } || !BackupAddon.isConfigured) return first
        val backup = runCatching { BackupAddon.sources(m, season, episode) }.getOrDefault(emptyList())
        if (backup.isEmpty()) return first
        progress?.invoke(backup)
        return backup
    }

    private suspend fun allSourcesOnServer(m: MediaItem, season: Int?, episode: Int?, fresh: Boolean,
                           progress: (suspend (List<StreamSource>) -> Unit)?): List<StreamSource> = coroutineScope {
        var items = findAllItems(m)
        val strict = items.filter { it.obj("ProviderIds").str("Tmdb") == m.tmdbID.toString() }
        items = if (strict.isEmpty()) items.take(1) else strict.take(6)
        val all = mutableListOf<StreamSource>()
        val seen = HashSet<String>()
        val lock = Mutex()
        items.mapNotNull { it.str("Id") }.map { id ->
            async {
                val list = sourcesFor(id, m.mediaType, season, episode, fresh)
                lock.withLock {
                    for (src in list) {
                        val key = src.url?.substringBefore("api_key=") ?: src.id
                        if (seen.add(key)) all += src
                    }
                    progress?.invoke(all.toList())
                }
            }
        }.forEach { it.await() }
        all.toList()
    }

    private fun parse(s: JSONObject, itemID: String, server: MediaServer, index: Int): StreamSource? {
        val sid = s.str("Id") ?: "$itemID-$index"
        val name = s.str("Name") ?: ""
        var vcodec = ""; var height = 0; var hdr = false; var dv = false
        val audio = mutableListOf<String>(); val subs = mutableListOf<String>()
        var aCodec = ""; var aCh = 0
        for (st in s.arr("MediaStreams")) {
            when (st.str("Type")) {
                "Video" -> if (vcodec.isEmpty()) {
                    vcodec = (st.str("Codec") ?: "").uppercase()
                    height = st.int("Height") ?: 0
                    val range = (st.str("VideoRangeType") ?: st.str("VideoRange") ?: "").uppercase()
                    if (range.contains("DOVI")) { dv = true; hdr = true } else if (range.contains("HDR") || range.contains("HLG")) hdr = true
                }
                "Audio" -> {
                    st.str("Language")?.let { audio += it }
                    if (aCodec.isEmpty() || st.bool("IsDefault") == true) {
                        aCodec = (st.str("Codec") ?: "").uppercase(); aCh = st.int("Channels") ?: aCh
                    }
                }
                "Subtitle" -> st.str("Language")?.let { subs += it }
            }
        }
        val path = s.str("Path") ?: ""
        if (height == 0) height = guessHeight("$name $path")
        var file = path.substringBefore("?").substringAfterLast("/")
        file = runCatching { java.net.URLDecoder.decode(file, "UTF-8") }.getOrDefault(file)
        if (file.length < 4) file = name.lineSequence().firstOrNull() ?: name
        // se il server non dichiara l'audio, lo ricavo dal testo della fonte (es. "DD+", "5.1")
        if (aCodec.isEmpty()) aCodec = guessAudioCodec("$name $file")
        if (aCh == 0) aCh = guessChannels("$name $file")
        return StreamSource(sid, itemID, name, (s.dbl("Size") ?: 0.0).toLong(), (s.str("Container") ?: "").uppercase(),
            vcodec, height, hdr, dv, audio, subs, file, aCodec, aCh, streamURL(s, itemID, sid, server))
    }

    private fun guessHeight(t: String): Int {
        val x = t.lowercase()
        return when { x.contains("2160") || x.contains("4k") || x.contains("uhd") -> 2160; x.contains("1080") -> 1080
            x.contains("720") -> 720; x.contains("480") -> 480; else -> 0 }
    }

    /** Link diretto (es. TorBox) quando c'è: veloce come in Stremio. Altrimenti si passa dal server. */
    private fun streamURL(s: JSONObject, itemID: String, sid: String, server: MediaServer): String {
        val d = s.str("DirectStreamUrl")
        if (d != null && d.startsWith("http")) return d
        val path = s.str("Path")
        if ((d == null || d.isEmpty()) && path != null && path.startsWith("http")) return path
        var full = if (d != null && d.isNotEmpty()) server.url + (if (d.startsWith("/")) d else "/$d")
                   else server.url + "/Videos/$itemID/stream?static=true&MediaSourceId=" + sid.enc()
        if (!full.contains("api_key=")) full += (if (full.contains("?")) "&" else "?") + "api_key=" + server.token
        return full
    }

    /** Ordine come in Strand: italiano, pronta subito, risoluzione, HDR, DV, release, dimensione. */
    fun sorted(input: List<StreamSource>): List<StreamSource> {
        // Lo stesso file arriva da più addon: se uno sa codec audio e canali e gli altri no, li completo.
        val list = input.map { s ->
            if (s.size <= 0 || (s.audioCodec.isNotEmpty() && s.audioChannels > 0)) return@map s
            val twin = input.firstOrNull { o -> o.id != s.id && kotlin.math.abs(o.size - s.size) < 2_000_000 &&
                o.effectiveHeight == s.effectiveHeight && (o.audioCodec.isNotEmpty() || o.audioChannels > 0) } ?: return@map s
            s.copy(audioCodec = s.audioCodec.ifEmpty { twin.audioCodec },
                   audioChannels = if (s.audioChannels == 0) twin.audioChannels else s.audioChannels)
        }
        return sortedRaw(list)
    }

    private fun sortedRaw(list: List<StreamSource>): List<StreamSource> = list.withIndex().sortedWith { x, y ->
        val a = x.value; val b = y.value
        when {
            a.isError != b.isError -> if (a.isError) 1 else -1
            a.hasItalianAudio != b.hasItalianAudio -> if (a.hasItalianAudio) -1 else 1
            a.isPack != b.isPack -> if (!a.isPack) -1 else 1
            a.isReady != b.isReady -> if (a.isReady) -1 else 1
            a.effectiveHeight != b.effectiveHeight -> b.effectiveHeight.compareTo(a.effectiveHeight)
            // a parità di qualità, prima quella con i sottotitoli in italiano (anche forced)
            a.hasItalianSubs != b.hasItalianSubs -> if (a.hasItalianSubs) -1 else 1
            a.isHDR != b.isHDR -> if (a.isHDR) -1 else 1
            a.isDolbyVision != b.isDolbyVision -> if (a.isDolbyVision) -1 else 1
            a.releaseRank != b.releaseRank -> a.releaseRank.compareTo(b.releaseRank)
            a.size != b.size -> when { a.size == 0L -> 1; b.size == 0L -> -1; else -> a.size.compareTo(b.size) }
            else -> x.index.compareTo(y.index)
        }
    }.map { it.value }
}

// MARK: Bollini di qualità

data class QualityBadges(
    val uhd: Boolean = false, val fullHD: Boolean = false, val hd: Boolean = false,
    val dolbyVision: Boolean = false, val hdr: Boolean = false, val atmos: Boolean = false,
    val surround: Boolean = false, val noSources: Boolean = false
) {
    val isEmpty get() = !(uhd || fullHD || hd || dolbyVision || hdr || atmos || surround || noSources)

    companion object {
        fun from(raw: List<StreamSource>, complete: Boolean = true): QualityBadges {
            val s = raw.filter { it.url != null && !it.isError }
            return QualityBadges(
                uhd = s.any { it.effectiveHeight >= 2000 },
                fullHD = s.any { it.effectiveHeight in 1000..1999 },
                hd = s.any { it.effectiveHeight in 700..999 },
                dolbyVision = s.any { it.isDolbyVision },
                hdr = s.any { it.isHDR && !it.isDolbyVision },
                atmos = s.any { it.isAtmos },
                surround = s.none { it.isAtmos } && s.any { it.isSurround },
                noSources = complete && s.isEmpty()
            )
        }
    }
}

/** Fonti già trovate (15 minuti) e bollini calcolati. */
object SourceCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<StreamSource>>>()
    fun key(m: MediaItem, s: Int?, e: Int?) = "${m.id}-${s ?: 0}-${e ?: 0}"
    fun get(m: MediaItem, s: Int?, e: Int?): List<StreamSource>? {
        val c = cache[key(m, s, e)] ?: return null
        return if (System.currentTimeMillis() - c.first < 4 * 60_000 && c.second.isNotEmpty()) c.second else null   // i link scadono: solo fonti recenti
    }
    fun put(m: MediaItem, s: Int?, e: Int?, list: List<StreamSource>) {
        if (list.isNotEmpty()) cache[key(m, s, e)] = System.currentTimeMillis() to list
    }
    fun clear(m: MediaItem, s: Int?, e: Int?) { cache.remove(key(m, s, e)) }
}

/** Abbina la fonte dell'episodio successivo a quella che stavi guardando (stesso provider/gruppo). */
object SourceMatcher {
    private val providers = listOf("corsaroviola", "corsaro viola", "ilcorsaronero", "corsaronero", "corsaro nero", "torrentio",
        "mediafusion", "comet", "knaben", "1337x", "thepiratebay", "tpb", "uindex", "yts", "eztv", "nyaa", "torrentgalaxy", "tgx",
        "streamingcommunity", "guardaserie", "mammamia", "animeworld", "peerflix", "jackett", "zilean", "torbox", "meteor",
        "realdebrid", "real-debrid", "alldebrid", "premiumize", "easynews", "usenet", "mircrew", "rlsbb")

    fun provider(text: String): String? {
        // stesso nome che si vede nell'elenco delle fonti (Meteor, CorsaroViola, Knaben…)
        val name = addonNameOf(text)
        if (name != "Jellyfin" && name != "TorBox") return name.lowercase()
        val t = text.lowercase(); return providers.firstOrNull { t.contains(it) }?.replace(" ", "")
    }

    fun group(fileName: String): String? =
        Regex("-([A-Za-z0-9]{2,20})$").find(fileName.substringBeforeLast("."))?.groupValues?.get(1)?.lowercase()

    /** Parole del nome della release senza la parte che cambia tra episodi (S01E05, numeri). */
    fun tokens(text: String): Set<String> {
        val t = text.lowercase().replace(Regex("s\\d{1,2}\\s?e\\d{1,3}"), " ").replace(Regex("\\b\\d{1,2}x\\d{1,3}\\b"), " ")
        return t.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 && it.toIntOrNull() == null }.toSet()
    }

    fun pickIndex(list: List<StreamSource>, current: PlayerSource?): Int {
        if (current == null || list.isEmpty()) return 0
        val curText = (current.title ?: "") + " " + current.fileName
        val curProv = provider(curText)
        val curGroup = group(current.fileName)
        val curTokens = tokens(curText)
        var best = 0; var bestScore = 0
        list.forEachIndexed { i, s ->
            var score = 0
            // stessa fonte (es. Meteor) prima di tutto: vince sempre su una release simile di un'altra fonte
            if (curProv != null && provider(s.title + " " + s.fileName) == curProv) score += 30
            if (curGroup != null && group(s.fileName) == curGroup) score += 6
            if (current.resolution.isNotEmpty() && s.resolutionLabel == current.resolution) score += 4
            if (current.codec.isNotEmpty() && s.videoCodec == current.codec) score += 2
            score += minOf(curTokens.intersect(tokens(s.title + " " + s.fileName)).size, 10)
            if (score > bestScore) { bestScore = score; best = i }
        }
        return best
    }
}

/** Nome dell'addon (Meteor, CorsaroViola…) da una descrizione qualsiasi. */
fun addonNameOf(title: String): String {
    val known = listOf(
        "meteor" to "Meteor", "comet" to "Comet", "mediafusion" to "MediaFusion", "torrentio" to "Torrentio",
        "jackettio" to "Jackettio", "stremthru" to "StremThru", "peerflix" to "Peerflix",
        "corsaroviola" to "CorsaroViola", "corsaro viola" to "CorsaroViola", "corsaronero" to "Il Corsaro Nero",
        "corsaro nero" to "Il Corsaro Nero", "knightcrawler" to "KnightCrawler", "orion" to "Orion",
        "easynews" to "Easynews", "debridio" to "Debridio", "zilean" to "Zilean", "sootio" to "Sootio",
        "streamingcommunity" to "StreamingCommunity", "mammamia" to "MammaMia", "animeworld" to "AnimeWorld",
        "guardaserie" to "GuardaSerie", "mircrew" to "MIRCrew", "knaben" to "Knaben"
    )
    val t = title.lowercase()
    known.mapNotNull { (k, n) -> t.indexOf(k).takeIf { it >= 0 }?.let { it to n } }.minByOrNull { it.first }?.let { return it.second }
    if (t.contains("torbox")) return "TorBox"
    var first = title.lines().firstOrNull() ?: ""
    first = first.replace(Regex("\\[[^\\]]*\\]"), " ").substringBefore("|")
    val clean = first.filter { it.isLetterOrDigit() || it == ' ' }
    val drop = setOf("tb", "rd", "ad", "pm", "dl", "ed", "oc", "cached", "instant", "uncached",
        "2160p", "1440p", "1080p", "1080i", "720p", "576p", "480p", "4k", "uhd", "hd", "sd", "p")
    val name = clean.split(" ").filter { it.isNotBlank() && it.lowercase() !in drop }.take(3).joinToString(" ")
    return if (name.isEmpty()) "Jellyfin" else name.take(28)
}
