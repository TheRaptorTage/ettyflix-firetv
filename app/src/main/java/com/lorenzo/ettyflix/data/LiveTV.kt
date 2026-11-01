package com.lorenzo.ettyflix.data

import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lorenzo.ettyflix.EttyFlixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

@Serializable
data class IPTVProvider(val id: String, val name: String, val url: String, val epg: List<String>)

@Serializable
data class LiveChannel(val id: String, val tvgID: String, val name: String, val logo: String?, val group: String,
                       val urls: List<String>, val geo: Boolean = false)

@Serializable
data class LiveProgramme(val title: String, val desc: String, val start: Long, val end: Long, val category: String) {
    fun isNow(t: Long = System.currentTimeMillis()) = start <= t && end > t
    fun progress(t: Long = System.currentTimeMillis()): Float =
        if (end <= start) 0f else ((t - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    val timeRange: String get() {
        val f = SimpleDateFormat("HH:mm", Locale.ITALIAN)
        return f.format(start) + " – " + f.format(end)
    }
}

object LiveTV {
    var provider by mutableStateOf<IPTVProvider?>(null)
        private set
    var channels by mutableStateOf(listOf<LiveChannel>())
        private set
    var guide by mutableStateOf(mapOf<String, List<LiveProgramme>>())
        private set
    var favorites by mutableStateOf(listOf<String>())
        private set
    var hidden by mutableStateOf(setOf<String>())
        private set
    var loading by mutableStateOf(false)
        private set
    var status by mutableStateOf("")

    private val prefs get() = EttyFlixApp.prefs

    init {
        provider = prefs.getString("iptv_provider", null)?.let { runCatching { AppJson.decodeFromString(IPTVProvider.serializer(), it) }.getOrNull() }
        favorites = prefs.getString("iptv_favorites", "")!!.split("\n").filter { it.isNotBlank() }
        hidden = prefs.getString("iptv_hidden", "")!!.split("\n").filter { it.isNotBlank() }.toSet()
        DataDir.read("channels.json")?.let { t ->
            channels = runCatching { AppJson.decodeFromString(ListSerializer(LiveChannel.serializer()), t) }.getOrDefault(emptyList())
        }
        DataDir.read("guide_android.json")?.let { t ->
            guide = runCatching {
                AppJson.decodeFromString(MapSerializer(String.serializer(), ListSerializer(LiveProgramme.serializer())), t)
            }.getOrDefault(emptyMap())
        }
    }

    private fun save() {
        val e = prefs.edit()
        provider?.let { e.putString("iptv_provider", AppJson.encodeToString(IPTVProvider.serializer(), it)) }
        e.putString("iptv_favorites", favorites.joinToString("\n"))
        e.putString("iptv_hidden", hidden.joinToString("\n"))
        e.apply()
    }

    fun configure(obj: JSONObject) {
        val list = obj.arr("iptvProviders")
        val p = list.firstOrNull { it.bool("isEnabled") ?: true } ?: list.firstOrNull() ?: return
        val url = p.str("url") ?: return
        provider = IPTVProvider(p.str("id") ?: java.util.UUID.randomUUID().toString(), p.str("name") ?: "Playlist", url,
            listOfNotNull(p.str("epgURL"), p.str("epgURL2")))
        val setup = obj.optJSONObject("channelSetup")
        if (setup != null) {
            fun flagged(k: String) = setup.arr(k).filter { it.bool("h") == true }.mapNotNull { it.str("k") }
            val favs = flagged("favoriteChannels")
            val order = setup.strings("favoriteChannelOrder")
            favorites = order.filter { it in favs } + favs.filter { it !in order }
            hidden = flagged("hiddenChannels").toSet()
        }
        save()
        channels = emptyList(); guide = emptyMap()
        prefs.edit().remove("iptv_channels_date").remove("iptv_guide_date_ms").apply()
    }

    val visibleChannels get() = channels.filter { it.id !in hidden }
    val favoriteChannels get() = favorites.mapNotNull { id -> channels.firstOrNull { it.id == id } }
    val groups: List<String> get() = visibleChannels.map { it.group }.distinct()

    fun isFavorite(c: LiveChannel) = c.id in favorites
    fun toggleFavorite(c: LiveChannel) { favorites = if (isFavorite(c)) favorites - c.id else favorites + c.id; save() }

    fun current(c: LiveChannel): LiveProgramme? = guide[c.id]?.firstOrNull { it.isNow() }
    fun next(c: LiveChannel): LiveProgramme? { val now = System.currentTimeMillis(); return guide[c.id]?.firstOrNull { it.start > now } }

    suspend fun loadIfNeeded() {
        if (provider == null) return
        val chDate = prefs.getLong("iptv_channels_date", 0)
        if (channels.isEmpty() || System.currentTimeMillis() - chDate > 24 * 3_600_000L) refreshChannels()
        val gDate = prefs.getLong("iptv_guide_date_ms", 0)
        if (guide.isEmpty() || System.currentTimeMillis() - gDate > 6 * 3_600_000L) refreshGuide()
    }

    suspend fun refresh() { refreshChannels(); refreshGuide() }

    private val ua = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android) EttyFlix")

    suspend fun refreshChannels() {
        val p = provider ?: return
        if (loading) return
        loading = true
        status = "Scarico la playlist…"
        try {
            val data = Http.bytes(p.url, ua) ?: run { status = "Playlist non raggiungibile."; return }
            val list = withContext(Dispatchers.Default) { parseM3U(String(data), p.id) }
            channels = list
            DataDir.write("channels.json", AppJson.encodeToString(ListSerializer(LiveChannel.serializer()), list))
            prefs.edit().putLong("iptv_channels_date", System.currentTimeMillis()).apply()
            status = "${list.size} canali caricati."
        } finally { loading = false }
    }

    suspend fun refreshGuide() {
        val p = provider ?: return
        if (channels.isEmpty() || loading) return
        loading = true
        try {
            val ids = channels.filter { it.tvgID.isNotEmpty() }.map { it.tvgID.lowercase() }.toSet()
            val names = channels.filter { it.tvgID.isEmpty() }.map { it.name.lowercase() }.toSet()
            val from = System.currentTimeMillis() - 6 * 3_600_000L
            val to = System.currentTimeMillis() + 36 * 3_600_000L
            val merged = HashMap<String, List<LiveProgramme>>()
            p.epg.forEachIndexed { i, url ->
                status = "Scarico la guida TV (${i + 1}/${p.epg.size})…"
                val raw = Http.bytes(url, ua) ?: return@forEachIndexed
                val (progs, nameMap) = withContext(Dispatchers.Default) { parseXMLTV(raw, ids, names, from, to) }
                for (c in channels) {
                    if (merged.containsKey(c.id)) continue
                    val key = if (c.tvgID.isEmpty()) nameMap[c.name.lowercase()] else c.tvgID.lowercase()
                    val l = key?.let { progs[it] }
                    if (!l.isNullOrEmpty()) merged[c.id] = l.sortedBy { it.start }
                }
            }
            if (merged.isNotEmpty()) {
                guide = merged
                DataDir.write("guide_android.json",
                    AppJson.encodeToString(MapSerializer(String.serializer(), ListSerializer(LiveProgramme.serializer())), merged))
                prefs.edit().putLong("iptv_guide_date_ms", System.currentTimeMillis()).apply()
                status = "${channels.size} canali · guida per ${merged.size} canali."
            } else status = "Guida TV non disponibile al momento."
        } finally { loading = false }
    }

    // MARK: Lettura playlist

    private fun parseM3U(text: String, pid: String): List<LiveChannel> {
        val attr = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")
        val out = mutableListOf<LiveChannel>()
        val index = HashMap<String, Int>()
        var pending: Map<String, String>? = null
        var pendingName = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                pending = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                pendingName = line.substringAfterLast(",", pending["tvg-name"] ?: "")
                continue
            }
            if (line.isEmpty() || line.startsWith("#")) continue
            val a = pending ?: continue
            pending = null
            val lower = line.lowercase()
            if (lower.contains("/movie/") || lower.contains("/series/")) continue
            if (listOf(".mkv", ".mp4", ".avi").any { lower.endsWith(it) }) continue
            val rawName = pendingName.ifEmpty { a["tvg-name"] ?: "Canale" }
            val geo = rawName.contains("Ⓖ")
            val name = rawName.filter { it !in "ⒼⓈⓎⓉ" }.replace("  ", " ").trim()
            val tvg = a["tvg-id"] ?: ""
            val key = pid + "_" + tvg.ifEmpty { name }
            val i = index[key]
            if (i != null) out[i] = out[i].copy(urls = out[i].urls + line)
            else {
                index[key] = out.size
                out += LiveChannel(key, tvg, name, a["tvg-logo"]?.ifEmpty { null }, a["group-title"] ?: "Altri", listOf(line), geo)
            }
        }
        return out
    }

    // MARK: Guida XMLTV

    private fun parseXMLTV(raw: ByteArray, ids: Set<String>, names: Set<String>, from: Long, to: Long):
            Pair<Map<String, List<LiveProgramme>>, Map<String, String>> {
        val data = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
            runCatching { GZIPInputStream(ByteArrayInputStream(raw)).readBytes() }.getOrNull() ?: return emptyMap<String, List<LiveProgramme>>() to emptyMap()
        else raw
        val fmtTZ = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val fmtPlain = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        fun date(s: String?): Long? = s?.let { runCatching { fmtTZ.parse(it)?.time }.getOrNull() ?: runCatching { fmtPlain.parse(it.take(14))?.time }.getOrNull() }
        val progs = HashMap<String, MutableList<LiveProgramme>>()
        val nameToID = HashMap<String, String>()
        val matched = HashSet<String>()
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(data), null)
        var channelID: String? = null
        var progCh: String? = null
        var ps = 0L; var pe = 0L
        var title = ""; var desc = ""; var cat = ""
        var text = StringBuilder()
        try {
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        text = StringBuilder()
                        when (p.name) {
                            "channel" -> channelID = p.getAttributeValue(null, "id")?.lowercase()
                            "programme" -> {
                                val ch = p.getAttributeValue(null, "channel")?.lowercase()
                                val s = date(p.getAttributeValue(null, "start"))
                                val e = date(p.getAttributeValue(null, "stop"))
                                progCh = if (ch != null && (ch in ids || ch in matched) && s != null && e != null && e > from && s < to) {
                                    ps = s; pe = e; title = ""; desc = ""; cat = ""; ch
                                } else null
                            }
                        }
                    }
                    XmlPullParser.TEXT -> text.append(p.text)
                    XmlPullParser.END_TAG -> {
                        val t = text.toString().trim()
                        when (p.name) {
                            "display-name" -> channelID?.let { id -> val n = t.lowercase(); nameToID[n] = id; if (n in names) matched += id }
                            "channel" -> channelID = null
                            "title" -> if (progCh != null && title.isEmpty()) title = t
                            "desc" -> if (progCh != null && desc.isEmpty()) desc = t
                            "category" -> if (progCh != null && cat.isEmpty()) cat = t
                            "programme" -> { progCh?.let { progs.getOrPut(it) { mutableListOf() } += LiveProgramme(title, desc, ps, pe, cat) }; progCh = null }
                        }
                        text = StringBuilder()
                    }
                }
                ev = p.next()
            }
        } catch (_: Exception) { }
        return progs to nameToID
    }
}
