package com.lorenzo.ettyflix.data

import com.lorenzo.ettyflix.EttyFlixApp
import org.json.JSONObject

/**
 * "Sottotitolato" affidabile, come su Mac e Apple TV: quando un file viene aperto nel player guardo le
 * sue tracce vere e mi ricordo se ha un forced italiano o inglese (anche quando il file non lo segna:
 * audio italiano con due sottotitoli italiani = uno dei due è il forced). Da lì in poi conta quello.
 */
object SubsKnowledge {
    private const val prefKey = "subs_forced_known_v2"
    private val map: MutableMap<String, Boolean> by lazy {
        val out = HashMap<String, Boolean>()
        runCatching {
            val j = JSONObject(EttyFlixApp.prefs.getString(prefKey, "{}") ?: "{}")
            for (k in j.keys()) out[k] = j.getBoolean(k)
        }
        out
    }

    /** nome del file; se è un nome generico senza estensione (es. CorsaroViola) aggiungo il peso */
    fun key(fileName: String, sizeLabel: String): String? {
        val x = fileName.trim().lowercase()
        if (x.length <= 8 || x.startsWith("magnet:")) return null
        if (Regex("\\.(mkv|mp4|avi|m4v|ts|webm|mov)$").containsMatchIn(x)) return x
        return if (sizeLabel.isNotEmpty()) "$x|$sizeLabel" else null
    }

    fun known(fileName: String, sizeLabel: String): Boolean? = key(fileName, sizeLabel)?.let { map[it] }

    fun record(fileName: String, sizeLabel: String, found: Boolean) {
        val k = key(fileName, sizeLabel) ?: return
        if (map[k] == found) return
        map[k] = found
        if (map.size > 3000) map.keys.take(map.size - 3000).forEach { map.remove(it) }
        EttyFlixApp.prefs.edit().putString(prefKey, JSONObject(map as Map<*, *>).toString()).apply()
    }
}
