package com.masselli.tvremote

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/** Roku External Control Protocol: plain HTTP on port 8060. */
class Roku(private val prefs: Prefs) {
    private var appsCache: List<Pair<String, String>>? = null
    private var appsCacheIp = ""

    private fun base(): String {
        val ip = prefs.rokuIp
        if (ip.isEmpty()) throw TvException("I don't know the Roku's address yet. Open Settings and tap Find devices.")
        return "http://$ip:8060"
    }

    private val blockedMsg = "The Roku is blocking the app. With the Roku remote go to Settings, System, Advanced system settings, Control by mobile apps, and set Network access to Permissive."

    private fun post(path: String) {
        val req = Request.Builder().url(base() + path).post("".toRequestBody(null)).build()
        try {
            Net.http.newCall(req).execute().use { r ->
                if (r.code == 403) throw TvException(blockedMsg)
                if (!r.isSuccessful) throw TvException("The Roku answered with error ${r.code}.")
            }
        } catch (e: IOException) {
            throw TvException("I couldn't reach the Roku at ${prefs.rokuIp}. Is it plugged in and on the same Wi-Fi?")
        }
    }

    private fun get(path: String): String = try {
        Net.http.newCall(Request.Builder().url(base() + path).build()).execute().use { r ->
            if (r.code == 403) throw TvException(blockedMsg)
            if (!r.isSuccessful) throw TvException("The Roku answered with error ${r.code}.")
            r.body?.string() ?: ""
        }
    } catch (e: IOException) {
        throw TvException("I couldn't reach the Roku at ${prefs.rokuIp}.")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun key(k: String) = post("/keypress/$k")
    fun launch(appId: String) = post("/launch/$appId")
    fun search(query: String) = post("/search/browse?keyword=${enc(query)}")

    /** Installed Roku channels/apps as (id, name). */
    fun apps(): List<Pair<String, String>> {
        val ip = prefs.rokuIp
        appsCache?.let { if (appsCacheIp == ip) return it }
        val xml = get("/query/apps")
        val list = Regex("<app id=\"([^\"]+)\"[^>]*>([^<]*)</app>").findAll(xml)
            .map { it.groupValues[1] to unescape(it.groupValues[2]) }
            .toList()
        appsCache = list
        appsCacheIp = ip
        return list
    }

    fun deviceName(): String? = try {
        val xml = get("/query/device-info")
        Regex("<user-device-name>([^<]*)</user-device-name>").find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: Regex("<model-name>([^<]*)</model-name>").find(xml)?.groupValues?.get(1)
    } catch (e: Exception) {
        null
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">")
}
