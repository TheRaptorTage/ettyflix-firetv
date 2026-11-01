package com.lorenzo.ettyflix.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Risposta HTTP semplice. */
class HttpResult(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
    fun json(): JSONObject? = runCatching { JSONObject(body) }.getOrNull()
    fun jsonArray(): JSONArray? = runCatching { JSONArray(body) }.getOrNull()
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 0
    ): HttpResult = withContext(Dispatchers.IO) {
        try {
            val b = Request.Builder().url(url)
            headers.forEach { (k, v) -> b.header(k, v) }
            when (method) {
                "GET" -> b.get()
                else -> b.method(method, (body ?: "").toRequestBody(jsonType))
            }
            val c = if (timeoutSec > 0) client.newBuilder().callTimeout(timeoutSec, TimeUnit.SECONDS).build() else client
            c.newCall(b.build()).execute().use { r -> HttpResult(r.code, r.body?.string() ?: "") }
        } catch (e: Exception) {
            HttpResult(0, e.message ?: "errore di rete")
        }
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutSec: Long = 0) =
        request(url, "GET", null, headers, timeoutSec)

    suspend fun bytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val b = Request.Builder().url(url)
            headers.forEach { (k, v) -> b.header(k, v) }
            client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
                .newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        } catch (e: Exception) { null }
    }
}

/** JSON compatibile con quello del Mac (campi mancanti = valori predefiniti). */
val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

// MARK: piccoli aiuti per org.json

fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

fun JSONObject.int(key: String): Int? =
    if (has(key) && !isNull(key)) (opt(key) as? Number)?.toInt() ?: optString(key).toIntOrNull() else null

fun JSONObject.dbl(key: String): Double? =
    if (has(key) && !isNull(key)) (opt(key) as? Number)?.toDouble() ?: optString(key).toDoubleOrNull() else null

fun JSONObject.bool(key: String): Boolean? = if (has(key) && !isNull(key)) optBoolean(key) else null

fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()

fun JSONObject.arr(key: String): List<JSONObject> {
    val a = optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}

fun JSONObject.ints(key: String): List<Int> {
    val a = optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).mapNotNull { (a.opt(it) as? Number)?.toInt() }
}

fun JSONObject.strings(key: String): List<String> {
    val a = optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.opt(it) as? String }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun String.enc(): String = java.net.URLEncoder.encode(this, "UTF-8")
