package com.masselli.tvremote

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import okhttp3.Request
import org.json.JSONObject

/** Finds Samsung TVs and Rokus on the home Wi-Fi using SSDP (the same way phone apps do). */
object Discovery {
    data class Found(val ip: String, val kind: String, var label: String)

    fun scan(context: Context, listenMs: Long = 4000): List<Found> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("tvremote")
        lock.setReferenceCounted(false)
        lock.acquire()
        val found = LinkedHashMap<String, Found>()
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 400
                val group = InetAddress.getByName("239.255.255.250")
                val targets = listOf(
                    "roku:ecp",
                    "urn:samsung.com:device:RemoteControlReceiver:1",
                    "urn:dial-multiscreen-org:service:dial:1"
                )
                repeat(2) {
                    for (st in targets) {
                        val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n").toByteArray()
                        sock.send(DatagramPacket(msg, msg.size, group, 1900))
                    }
                }
                val end = System.currentTimeMillis() + listenMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val text = String(p.data, 0, p.length).lowercase()
                    val ip = p.address?.hostAddress ?: continue
                    val kind = when {
                        "roku" in text -> "roku"
                        "samsung" in text -> "samsung"
                        else -> continue
                    }
                    found.getOrPut("$kind@$ip") { Found(ip, kind, ip) }
                }
            }
        } finally {
            lock.release()
        }

        // Confirm each one and get a friendly name.
        val result = ArrayList<Found>()
        for (f in found.values) {
            val name = if (f.kind == "roku") rokuName(f.ip) else samsungName(f.ip)
            if (name == null) continue
            f.label = (if (f.kind == "roku") "Roku: " else "Samsung TV: ") + name + "  (" + f.ip + ")"
            result.add(f)
        }
        return result
    }

    private fun fetch(url: String): String? = try {
        Net.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    fun rokuName(ip: String): String? {
        val xml = fetch("http://$ip:8060/query/device-info") ?: return null
        return Regex("<user-device-name>([^<]*)</user-device-name>").find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: Regex("<model-name>([^<]*)</model-name>").find(xml)?.groupValues?.get(1)
            ?: "Roku"
    }

    fun samsungName(ip: String): String? {
        val json = fetch("http://$ip:8001/api/v2/") ?: return null
        return try {
            JSONObject(json).optJSONObject("device")?.optString("name")?.takeIf { it.isNotBlank() } ?: "Samsung TV"
        } catch (e: Exception) {
            null
        }
    }
}
