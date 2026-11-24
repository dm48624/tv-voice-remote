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
    var speak: Boolean
        get() = p.getBoolean("speak", true)
        set(v) { p.edit().putBoolean("speak", v).apply() }

    companion object {
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
