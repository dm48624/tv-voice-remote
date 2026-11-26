package com.masselli.tvremote

import android.content.Context
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** An error with a plain-English message that can be shown and spoken. */
class TvException(message: String) : Exception(message)

class Prefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("tvremote", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = "") = p.getString(key, def) ?: def
    private fun put(key: String, v: String) = p.edit().putString(key, v).apply()

    var samsungIp: String
        get() = str("samsungIp")
        set(v) { put("samsungIp", v.trim()) }
    var samsungToken: String
        get() = str("samsungToken")
        set(v) { put("samsungToken", v) }
    var samsungMac: String
        get() = str("samsungMac")
        set(v) { put("samsungMac", v) }
    var rokuIp: String
        get() = str("rokuIp")
        set(v) { put("rokuIp", v.trim()) }
    var rokuHdmi: String
        get() = str("rokuHdmi")
        set(v) { put("rokuHdmi", v.trim()) }
    /** What the arrow/play buttons currently control: "tv" or "roku". */
    var mode: String
        get() = str("mode", "tv")
        set(v) { put("mode", v) }
    var channelsText: String
        get() = str("channels", DEFAULT_CHANNELS)
        set(v) { put("channels", v) }
    var lastSearch: String
        get() = str("lastSearch")
        set(v) { put("lastSearch", v) }
    /** Buttons that open Samsung's search screen from anywhere. */
    var samsungSearchKeys: String
        get() = str("samsungSearchKeys", DEFAULT_SEARCH_KEYS)
        set(v) { put("samsungSearchKeys", v) }
    /** Switch to a show automatically when it's on now (otherwise ask first). */
    var autoTune: Boolean
        get() = p.getBoolean("autoTune", true)
        set(v) { p.edit().putBoolean("autoTune", v).apply() }
    var pendingChannel: String
        get() = str("pendingChannel")
        set(v) { put("pendingChannel", v) }
    var sdUser: String
        get() = str("sdUser")
        set(v) { put("sdUser", v.trim()) }
    /** Only the SHA-1 of the password is kept, which is what Schedules Direct asks for. */
    var sdPasswordHash: String
        get() = str("sdPass")
        set(v) { put("sdPass", v) }
    var sdZip: String
        get() = str("sdZip", "48624")
        set(v) { put("sdZip", v.trim()) }
    var sdLineup: String
        get() = str("sdLineup")
        set(v) { put("sdLineup", v) }
    var sdToken: String
        get() = str("sdToken")
        set(v) { put("sdToken", v) }
    var sdTokenExpires: Long
        get() = p.getLong("sdTokenExpires", 0L)
        set(v) { p.edit().putLong("sdTokenExpires", v).apply() }
    /** No automatic Schedules Direct logins before this time (ms). Tapping Connect ignores it. */
    var sdHoldUntil: Long
        get() = p.getLong("sdHoldUntil", 0L)
        set(v) { p.edit().putLong("sdHoldUntil", v).apply() }
    var sdLastError: String
        get() = str("sdLastError")
        set(v) { put("sdLastError", v) }
    var speak: Boolean
        get() = p.getBoolean("speak", true)
        set(v) { p.edit().putBoolean("speak", v).apply() }

    companion object {
        const val DEFAULT_SEARCH_KEYS = "HOME, wait, LEFT*12, RIGHT*1, ENTER, wait"
        val DEFAULT_CHANNELS = """
            # One channel per line:   number = name, another name, another name
            # Use the number you'd press on the remote (9.1 for channel 9-1).
            # Samsung TV Plus channels have numbers too (often 1000 and up) -
            # check the TV's guide and add the ones you watch.
            # Lines starting with # are notes and are ignored.
            9.1 = 9&10, 9 and 10, nine and ten, 9&10 News, WWTV
        """.trimIndent()
    }
}

object Net {
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** Plain HTTP on the home network (Roku, TV info). */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Samsung TVs use a self-signed certificate on their remote-control port,
     * so this client (used only for the TV's address) accepts it.
     */
    val tvSocket: OkHttpClient by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}
