package com.masselli.tvremote

import android.util.Base64
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Talks to a Samsung Tizen smart TV over its local remote-control connection
 * (secure WebSocket on port 8002). The first time, the TV shows an "Allow" box;
 * after that it hands back a token that we keep so it won't ask again.
 */
class SamsungTv(private val prefs: Prefs) {
    @Volatile private var ws: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var lastError: String? = null
    @Volatile private var connectedIp = ""

    private fun ip(): String {
        val ip = prefs.samsungIp
        if (ip.isEmpty()) throw TvException("I don't know the TV's address yet. Open Settings and tap Find devices.")
        return ip
    }

    fun reset() {
        connected = false
        ws?.cancel()
        ws = null
    }

    @Synchronized
    fun ensureConnected(timeoutSec: Long = 35) {
        val ip = ip()
        if (connected && ws != null && ip == connectedIp) return
        reset()
        val name = Base64.encodeToString("TV Voice Remote".toByteArray(), Base64.NO_WRAP)
        var url = "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$name"
        if (prefs.samsungToken.isNotEmpty()) url += "&token=${prefs.samsungToken}"
        val latch = CountDownLatch(1)
        lastError = null
        val socket = Net.tvSocket.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val o = JSONObject(text)
                    when (o.optString("event")) {
                        "ms.channel.connect" -> {
                            val tok = o.optJSONObject("data")?.optString("token").orEmpty()
                            if (tok.isNotEmpty()) prefs.samsungToken = tok
                            connected = true
                            latch.countDown()
                        }
                        "ms.channel.unauthorized" -> {
                            lastError = "The TV didn't allow the connection. On the TV go to Settings, General, External Device Manager, Device Connection Manager, and allow TV Voice Remote."
                            latch.countDown()
                        }
                        "ms.channel.timeOut" -> {
                            lastError = "No one chose Allow on the TV in time. Try again and pick Allow on the TV."
                            latch.countDown()
                        }
                    }
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                if (ws === webSocket) ws = null
                if (lastError == null) lastError = "I couldn't reach the TV at $ip. Make sure it's on and the tablet is on the same Wi-Fi."
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                if (ws === webSocket) ws = null
            }
        })
        ws = socket
        connectedIp = ip
        latch.await(timeoutSec, TimeUnit.SECONDS)
        if (!connected) {
            socket.cancel()
            ws = null
            throw TvException(lastError ?: "The TV didn't answer. If a box popped up on the TV asking to allow access, choose Allow and try again.")
        }
    }

    fun key(k: String) {
        ensureConnected()
        val msg = JSONObject()
            .put("method", "ms.remote.control")
            .put("params", JSONObject()
                .put("Cmd", "Click")
                .put("DataOfCmd", k)
                .put("Option", "false")
                .put("TypeOfRemote", "SendRemoteKey"))
            .toString()
        if (ws?.send(msg) != true) {
            reset()
            ensureConnected()
            if (ws?.send(msg) != true) throw TvException("Lost the connection to the TV.")
        }
    }

    private fun sendRaw(msg: String) {
        ensureConnected()
        if (ws?.send(msg) != true) {
            reset()
            ensureConnected()
            if (ws?.send(msg) != true) throw TvException("Lost the connection to the TV.")
        }
    }

    /** Types text into whatever on-screen keyboard the TV is showing. */
    fun typeText(text: String) {
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendRaw(JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
            .put("Cmd", b64).put("DataOfCmd", "base64").put("TypeOfRemote", "SendInputString")).toString())
        Thread.sleep(400)
        sendRaw(JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
            .put("TypeOfRemote", "SendInputEnd")).toString())
    }

    /**
     * Runs a button recipe like "HOME, wait, LEFT*12, RIGHT, ENTER".
     * Names are remote buttons without the KEY_ prefix; "*n" repeats; "wait" pauses a second.
     */
    fun runRecipe(recipe: String) {
        for (raw in recipe.split(',')) {
            val part = raw.trim().uppercase()
            if (part.isEmpty()) continue
            if (part == "WAIT") { Thread.sleep(1200); continue }
            val m = Regex("^([A-Z0-9_]+)\\s*(?:[*X]\\s*(\\d+))?$").matchEntire(part)
                ?: throw TvException("I don't understand \"$raw\" in the Samsung search button list in Settings.")
            val name = m.groupValues[1].removePrefix("KEY_")
            val times = m.groupValues[2].toIntOrNull() ?: 1
            repeat(times.coerceIn(1, 30)) { key("KEY_$name"); Thread.sleep(250) }
        }
    }

    fun keys(list: List<String>, gapMs: Long = 300) {
        for (k in list) {
            key(k)
            Thread.sleep(gapMs)
        }
    }

    /**
     * Returns null if the TV can't be reached (fully off or asleep),
     * otherwise its power state ("on" or "standby"). Also remembers its network ID for wake-up.
     */
    fun status(): String? {
        val ip = ip()
        return try {
            Net.http.newCall(Request.Builder().url("http://$ip:8001/api/v2/").build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val dev = JSONObject(r.body?.string() ?: "{}").optJSONObject("device") ?: return "on"
                val mac = dev.optString("wifiMac")
                if (mac.isNotEmpty()) prefs.samsungMac = mac
                dev.optString("PowerState").ifEmpty { "on" }.lowercase()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun deviceName(): String? = try {
        Net.http.newCall(Request.Builder().url("http://${ip()}:8001/api/v2/").build()).execute().use { r ->
            JSONObject(r.body?.string() ?: "{}").optJSONObject("device")?.optString("name")
        }
    } catch (e: Exception) {
        null
    }

    /** Wake-on-LAN "magic packet". Needs the TV's "Power On with Mobile" setting turned on. */
    fun wake() {
        val mac = prefs.samsungMac.replace(Regex("[^0-9A-Fa-f]"), "")
        if (mac.length != 12) throw TvException("I can't turn the TV on yet. Turn it on with the remote once while this app is open, then I'll be able to wake it next time.")
        val macBytes = ByteArray(6) { i -> mac.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 6 until packet.size) packet[i] = macBytes[(i - 6) % 6]
        DatagramSocket().use { s ->
            s.broadcast = true
            val targets = listOf(InetAddress.getByName("255.255.255.255"), InetAddress.getByName(ip()))
            repeat(3) {
                for (t in targets) for (port in listOf(9, 7)) {
                    try { s.send(DatagramPacket(packet, packet.size, t, port)) } catch (_: Exception) {}
                }
                Thread.sleep(100)
            }
        }
    }
}
