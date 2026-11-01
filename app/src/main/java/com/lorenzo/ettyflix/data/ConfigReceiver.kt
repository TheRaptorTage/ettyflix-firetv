package com.lorenzo.ettyflix.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/** Codice di 8 caratteri con l'indirizzo della TV (stesso formato dell'Apple TV, così il Mac lo capisce). */
object ShareCode {
    const val port = 8765
    private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray()
    private const val mix: Long = 0xA53C9E71B2L

    fun encode(ip: String): String? {
        val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) v = (v shl 8) or p.toLong()
        val check = ((parts[0] * 3 + parts[1] * 5 + parts[2] * 7 + parts[3] * 11 + 29) and 0xFF).toLong()
        var value = ((v shl 8) or check) xor mix
        val chars = CharArray(8)
        for (i in 7 downTo 0) { chars[i] = alphabet[(value and 31).toInt()]; value = value shr 5 }
        val s = String(chars)
        return s.take(4) + "-" + s.takeLast(4)
    }
}

fun localIPAddress(): String? = runCatching {
    val list = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
    val ordered = list.sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
    ordered.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
}.getOrNull()

/** Riceve la configurazione dal Mac ("Condividi configurazione") sulla rete di casa. */
object ConfigReceiver {
    var address by mutableStateOf("")
        private set
    var code by mutableStateOf("—")
        private set
    var running by mutableStateOf(false)
        private set
    var message by mutableStateOf("")

    private var server: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        val ip = localIPAddress()
        address = ip?.let { "http://$it:${ShareCode.port}" } ?: "Rete non disponibile"
        code = ip?.let { ShareCode.encode(it) } ?: "—"
        if (server != null) return
        try {
            val s = ServerSocket(ShareCode.port)
            s.reuseAddress = true
            server = s
            running = true
            scope.launch {
                while (!s.isClosed) {
                    val c = runCatching { s.accept() }.getOrNull() ?: break
                    launch { handle(c) }
                }
            }
        } catch (e: Exception) {
            message = "Impossibile avviare la ricezione: ${e.message}"
        }
    }

    fun stop() { runCatching { server?.close() }; server = null; running = false }

    private suspend fun handle(sock: Socket) {
        sock.use { c ->
            c.soTimeout = 20_000
            val input = c.getInputStream()
            val buf = ByteArrayOutputStream()
            val tmp = ByteArray(65536)
            var headerEnd = -1
            var length = 0
            var method = "GET"
            var path = "/"
            while (true) {
                val n = runCatching { input.read(tmp) }.getOrDefault(-1)
                if (n <= 0) break
                buf.write(tmp, 0, n)
                val bytes = buf.toByteArray()
                if (headerEnd < 0) {
                    val idx = indexOf(bytes, "\r\n\r\n".toByteArray())
                    if (idx >= 0) {
                        headerEnd = idx + 4
                        val head = String(bytes, 0, idx)
                        val lines = head.split("\r\n")
                        val first = lines.firstOrNull()?.split(" ") ?: emptyList()
                        method = first.getOrNull(0) ?: "GET"
                        path = first.getOrNull(1) ?: "/"
                        for (l in lines.drop(1)) {
                            val kv = l.split(":", limit = 2)
                            if (kv.size == 2 && kv[0].trim().equals("content-length", true)) length = kv[1].trim().toIntOrNull() ?: 0
                        }
                    }
                }
                if (headerEnd >= 0 && buf.size() - headerEnd >= length) break
                if (buf.size() > 30_000_000) return
            }
            if (headerEnd < 0) return
            val body = buf.toByteArray().copyOfRange(headerEnd, minOf(buf.size(), headerEnd + length))
            val (codeOut, type, out) = withContext(Dispatchers.Main) { respond(method, path, String(body)) }
            val head = "HTTP/1.1 $codeOut ${if (codeOut == 200) "OK" else "Error"}\r\nContent-Type: $type\r\n" +
                "Content-Length: ${out.size}\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
            runCatching { c.getOutputStream().apply { write(head.toByteArray()); write(out); flush() } }
        }
    }

    private fun indexOf(a: ByteArray, b: ByteArray): Int {
        outer@ for (i in 0..a.size - b.size) { for (j in b.indices) if (a[i + j] != b[j]) continue@outer; return i }
        return -1
    }

    private fun respond(method: String, path: String, body: String): Triple<Int, String, ByteArray> {
        if (method == "POST" && path.startsWith("/upload")) {
            val obj = runCatching { JSONObject(body) }.getOrNull()
                ?: return Triple(400, "application/json", "{\"ok\":false,\"error\":\"Dati non validi\"}".toByteArray())
            val done = mutableListOf<String>()
            obj.str("tmdb")?.takeIf { it.isNotBlank() }?.let { Store.saveKey(it); done += "chiave TMDB" }
            obj.str("trakt")?.takeIf { it.isNotBlank() }?.let { Trakt.saveClientID(it); done += "Client ID Trakt" }
            obj.str("omdb")?.takeIf { it.isNotBlank() }?.let {
                com.lorenzo.ettyflix.EttyFlixApp.prefs.edit().putString("omdb_api_key", it.trim()).apply(); done += "chiave OMDb"
            }
            obj.str("gemini")?.takeIf { it.isNotBlank() }?.let { AI.saveKey(it); done += "chiave Gemini" }
            obj.str("strand")?.takeIf { it.isNotEmpty() }?.let {
                try { Store.importStrand(it); done += "configurazione Strand" }
                catch (e: Exception) { return Triple(400, "application/json", "{\"ok\":false,\"error\":\"File .strand non valido\"}".toByteArray()) }
            }
            val profilesText = obj.str("profiles")
            val list = profilesText?.let { runCatching { AppJson.decodeFromString(ListSerializer(Profile.serializer()), it) }.getOrNull() }
            if (!list.isNullOrEmpty()) {
                Profiles.replaceList(list)
                obj.optJSONObject("profileData")?.let { data ->
                    for (id in data.keys()) {
                        val v = data.optJSONObject(id) ?: continue
                        v.str("watchlist")?.let { t ->
                            runCatching { AppJson.decodeFromString(ListSerializer(MediaItem.serializer()), t) }.getOrNull()
                                ?.takeIf { it.isNotEmpty() }?.let { Store.writeWatchlist(it, id) }
                        }
                        v.str("progress")?.let { t ->
                            runCatching { AppJson.decodeFromString(ListSerializer(WatchEntry.serializer()), t) }.getOrNull()
                                ?.takeIf { it.isNotEmpty() }?.let { WatchProgress.writeEntries(it, id) }
                        }
                    }
                    WatchProgress.reload()
                    Store.reloadProfileData()
                }
                done += "profili (${list.joinToString(", ") { it.name }})"
            }
            Store.reloadAll()
            scope.launch { runCatching { LiveTV.loadIfNeeded() } }
            message = if (done.isEmpty()) "Nessun dato ricevuto." else "Ricevuto: " + done.joinToString(", ") + " ✓"
            return Triple(200, "application/json", JSONObject().put("ok", true).put("message", message).toString().toByteArray())
        }
        return Triple(200, "text/html; charset=utf-8", page.toByteArray())
    }

    private val page = """
        <!doctype html><html lang="it"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>EttyFlix · Fire TV</title>
        <style>body{font-family:-apple-system,system-ui,sans-serif;background:#0d0d12;color:#eee;max-width:560px;margin:40px auto;padding:0 16px}
        input{width:100%;padding:10px;border-radius:8px;border:1px solid #333;background:#1b1b22;color:#eee;box-sizing:border-box}
        label{display:block;margin:18px 0 6px;font-weight:600}button{margin-top:24px;width:100%;padding:12px;border:0;border-radius:10px;background:#e9a23b;font-weight:700}</style></head><body>
        <h1>Invia la configurazione alla Fire TV</h1>
        <label>File .strand</label><input type="file" id="file" accept=".strand,.json">
        <label>Chiave API TMDB</label><input id="tmdb" autocomplete="off">
        <button id="send">Invia</button><p id="out"></p>
        <script>document.getElementById('send').onclick=async()=>{const o=document.getElementById('out');o.textContent='Invio…';
        const f=document.getElementById('file').files[0];const strand=f?await f.text():'';
        try{const r=await fetch('/upload',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({strand,tmdb:document.getElementById('tmdb').value})});
        const j=await r.json();o.textContent=j.ok?j.message:'Errore: '+j.error;}catch(e){o.textContent='Errore: '+e;}};</script></body></html>
    """.trimIndent()
}
