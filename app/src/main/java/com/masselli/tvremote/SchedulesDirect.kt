package com.masselli.tvremote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Antenna TV listings from Schedules Direct (schedulesdirect.org JSON service).
 * Downloads about two days of schedules for the account's over-the-air lineup and keeps
 * a small copy on the tablet. Refreshed at most every 12 hours, as the service asks.
 */
class SchedulesDirect(private val prefs: Prefs, private val dir: File) {
    companion object {
        private const val BASE = "https://json.schedulesdirect.org/20141201"
        private const val UA = "TVVoiceRemote/1.0 (personal, github.com/dm48624/tv-voice-remote)"
        private const val REFRESH_MS = 12 * 3600 * 1000L
        private val JSON = "application/json".toMediaType()

        fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private val http: OkHttpClient = Net.http.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    private val cacheFile get() = File(dir, "antenna-listings.json")
    @Volatile private var cache: List<ListedChannel>? = null
    @Volatile private var cacheMtime = 0L

    val configured get() = prefs.sdUser.isNotEmpty() && prefs.sdPasswordHash.isNotEmpty()

    fun ageText(): String {
        if (!cacheFile.exists()) return "not downloaded yet"
        val mins = (System.currentTimeMillis() - cacheFile.lastModified()) / 60000
        return when {
            mins < 2 -> "just now"
            mins < 120 -> "$mins minutes ago"
            else -> "${mins / 60} hours ago"
        }
    }

    /** Cached antenna channels; downloads first if missing or older than 12 hours. */
    @Synchronized
    fun channels(allowDownload: Boolean = true): List<ListedChannel> {
        if (!configured) return emptyList()
        val stale = !cacheFile.exists() || System.currentTimeMillis() - cacheFile.lastModified() > REFRESH_MS
        // After any failure, don't keep retrying by ourselves: Schedules Direct locks accounts
        // (and can block them) for repeated failed requests. The Connect button retries on purpose.
        if (stale && allowDownload && System.currentTimeMillis() >= prefs.sdHoldUntil &&
            !(prefs.sdLastError.contains("code 40") || prefs.sdLastError.contains("accept"))) {
            // Keep using the old copy if there is one, but remember what went wrong.
            try { refresh() } catch (e: Exception) {
                prefs.sdLastError = (e as? TvException)?.message ?: "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        if (!cacheFile.exists()) return emptyList()
        if (cache == null || cacheMtime != cacheFile.lastModified()) {
            cache = load()
            cacheMtime = cacheFile.lastModified()
        }
        return cache ?: emptyList()
    }

    // ---------- service calls ----------

    private fun check(text: String, httpCode: Int): String {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{")) {
            val o = JSONObject(trimmed)
            val code = o.optInt("code", 0)
            if (code != 0 && !o.has("map")) {
                val msg = o.optString("message", "error $code")
                // Show Schedules Direct's own words and code rather than guessing what they mean.
                if (code in 4000..4999) prefs.sdToken = ""
                throw TvException("Schedules Direct said: $msg (code $code)")
            }
        } else if (httpCode !in 200..299) {
            throw TvException("Schedules Direct answered with error $httpCode.")
        }
        return text
    }

    private fun call(method: String, path: String, body: String? = null, token: String? = null): String {
        val b = Request.Builder().url(BASE + path).header("User-Agent", UA)
        if (token != null) b.header("token", token)
        when (method) {
            "GET" -> b.get()
            "PUT" -> b.put((body ?: "").toRequestBody(JSON))
            else -> b.post((body ?: "").toRequestBody(JSON))
        }
        try {
            http.newCall(b.build()).execute().use { r ->
                return check(r.body?.string() ?: "", r.code)
            }
        } catch (e: java.io.IOException) {
            throw TvException("I couldn't reach Schedules Direct. Check the tablet's internet connection.")
        }
    }

    private fun token(): String {
        val now = System.currentTimeMillis() / 1000
        if (prefs.sdToken.isNotEmpty() && prefs.sdTokenExpires > now + 600) return prefs.sdToken
        val body = JSONObject().put("username", prefs.sdUser).put("password", prefs.sdPasswordHash).toString()
        val o = JSONObject(call("POST", "/token", body))
        val tok = o.optString("token")
        if (tok.isEmpty()) throw TvException("Schedules Direct didn't give me a login token.")
        prefs.sdToken = tok
        prefs.sdTokenExpires = o.optLong("tokenExpires", now + 20 * 3600)
        return tok
    }

    /** Makes sure the account has the antenna lineup for the ZIP code; returns its ID. */
    private fun ensureLineup(tok: String): String {
        val zip = prefs.sdZip.ifEmpty { throw TvException("Enter your ZIP code in Settings first.") }
        val status = JSONObject(call("GET", "/status", token = tok))
        val have = status.optJSONArray("lineups") ?: JSONArray()
        for (i in 0 until have.length()) {
            val id = have.getJSONObject(i).optString("lineup")
            if (id.contains("-OTA-") && id.endsWith(zip)) { prefs.sdLineup = id; return id }
        }
        val headends = JSONArray(call("GET", "/headends?country=USA&postalcode=$zip", token = tok))
        var lineupId = ""
        for (i in 0 until headends.length()) {
            val h = headends.getJSONObject(i)
            if (!h.optString("transport").equals("Antenna", ignoreCase = true)) continue
            val ls = h.optJSONArray("lineups") ?: continue
            if (ls.length() > 0) { lineupId = ls.getJSONObject(0).optString("lineup"); break }
        }
        if (lineupId.isEmpty()) lineupId = "USA-OTA-$zip"
        call("PUT", "/lineups/$lineupId", token = tok)
        prefs.sdLineup = lineupId
        return lineupId
    }

    /** Logs in, finds the antenna lineup, downloads two days of listings. Returns a summary. */
    @Synchronized
    fun refresh(): String = try {
        val r = doRefresh()
        prefs.sdHoldUntil = 0L
        r
    } catch (e: TvException) {
        prefs.sdLastError = e.message ?: "unknown error"
        // Login problems wait for you to fix them and tap Connect; other problems retry in an hour.
        val loginProblem = (e.message ?: "").contains("code 40")
        prefs.sdHoldUntil = System.currentTimeMillis() + if (loginProblem) 365L * 24 * 3600_000 else 3600_000L
        throw e
    } catch (e: Exception) {
        val msg = "Problem reading the Schedules Direct data (${e.javaClass.simpleName}: ${e.message})"
        prefs.sdLastError = msg
        prefs.sdHoldUntil = System.currentTimeMillis() + 3600_000L
        throw TvException(msg)
    }

    private fun doRefresh(): String {
        if (!configured) throw TvException("Enter your Schedules Direct username and password in Settings first.")
        val tok = token()
        val lineupId = ensureLineup(tok)

        val lineup = JSONObject(call("GET", "/lineups/$lineupId", token = tok))
        val stationInfo = HashMap<String, Pair<String, String>>() // id -> (callsign, affiliate)
        val stationFull = HashMap<String, String>() // id -> longer station name
        val stations = lineup.optJSONArray("stations") ?: JSONArray()
        for (i in 0 until stations.length()) {
            val s = stations.getJSONObject(i)
            val name = s.optString("callsign").ifEmpty { s.optString("name") }
            stationInfo[s.getString("stationID")] = name to s.optString("affiliate")
            stationFull[s.getString("stationID")] = s.optString("name")
        }
        val numbers = LinkedHashMap<String, String>() // stationID -> "9.1"
        val map = lineup.optJSONArray("map") ?: JSONArray()
        for (i in 0 until map.length()) {
            val m = map.getJSONObject(i)
            val id = m.optString("stationID")
            val num = when {
                m.has("atscMajor") -> "${m.getInt("atscMajor")}.${m.optInt("atscMinor", 1)}"
                m.has("channel") -> m.getString("channel").trimStart('0').replace('-', '.')
                else -> continue
            }
            if (num.isNotEmpty() && id !in numbers) numbers[id] = num
        }
        if (numbers.isEmpty()) throw TvException("Schedules Direct has no antenna channels for ZIP ${prefs.sdZip}.")

        // Schedules: UTC dates covering now through about two days ahead.
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val nowMs = System.currentTimeMillis()
        val dates = JSONArray()
        listOf(-6L, 0L, 24L, 48L).map { fmt.format(Date(nowMs + it * 3600_000)) }.distinct().forEach { dates.put(it) }
        val schedReq = JSONArray()
        for (id in numbers.keys) schedReq.put(JSONObject().put("stationID", id).put("date", dates))
        val sched = JSONArray(call("POST", "/schedules", schedReq.toString(), tok))

        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val nowSec = nowMs / 1000
        data class Slot(val station: String, val start: Long, val end: Long, val programId: String)
        val slots = ArrayList<Slot>()
        for (i in 0 until sched.length()) {
            val s = sched.optJSONObject(i) ?: continue
            val station = s.optString("stationID")
            val progs = s.optJSONArray("programs") ?: continue
            for (j in 0 until progs.length()) {
                val p = progs.getJSONObject(j)
                val start = (iso.parse(p.getString("airDateTime"))?.time ?: continue) / 1000
                val end = start + p.optLong("duration", 1800)
                if (end < nowSec - 3600) continue
                slots.add(Slot(station, start, end, p.getString("programID")))
            }
        }

        // Program titles, up to 5000 IDs per request.
        val titles = HashMap<String, String>()
        val ids = slots.map { it.programId }.distinct()
        for (chunk in ids.chunked(4000)) {
            val req = JSONArray().apply { chunk.forEach { put(it) } }
            val arr = JSONArray(call("POST", "/programs", req.toString(), tok))
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val t = p.optJSONArray("titles")?.optJSONObject(0)?.optString("title120").orEmpty()
                if (t.isNotEmpty()) titles[p.optString("programID")] = t
            }
        }

        // Save a compact copy: [{n, name, aff, a:[[start,end,title],...]}]
        val out = JSONArray()
        for ((id, num) in numbers) {
            val info = stationInfo[id] ?: (id to "")
            val a = JSONArray()
            slots.filter { it.station == id }.sortedBy { it.start }.forEach { s ->
                titles[s.programId]?.let { a.put(JSONArray().put(s.start).put(s.end).put(it)) }
            }
            out.put(JSONObject().put("n", num).put("name", info.first).put("aff", info.second)
                .put("full", stationFull[id].orEmpty()).put("a", a))
        }
        val tmp = File(dir, "antenna-listings.tmp")
        tmp.writeText(out.toString())
        tmp.renameTo(cacheFile)
        cache = null
        val shows = slots.count { titles.containsKey(it.programId) }
        prefs.sdLastError = ""
        return "Connected to Schedules Direct. Got $shows listings for ${numbers.size} antenna channels in ZIP ${prefs.sdZip}."
    }

    private fun load(): List<ListedChannel> = try {
        val arr = JSONArray(cacheFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val a = o.getJSONArray("a")
            val airings = (0 until a.length()).map { j ->
                val x = a.getJSONArray(j)
                Airing(x.getLong(0), x.getLong(1), x.getString(2))
            }
            ListedChannel(o.getString("n"), o.getString("name"), airings, antenna = true,
                affiliate = o.optString("aff"), altName = o.optString("full").takeIf { it != o.getString("name") }.orEmpty())
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Names Schedules Direct gives a channel (call letters, network, longer name). */
    fun namesFor(c: ListedChannel): List<String> {
        val base = listOf(c.name, c.altName, c.affiliate, c.affiliate.removeSuffix(" Affiliate").trim()).filter { it.isNotBlank() }
        return (base + base.flatMap { shortNames(it) }).distinctBy { it.lowercase() }
    }

    /** The short names people say for networks Schedules Direct spells out in full. */
    private fun shortNames(full: String): List<String> {
        val out = ArrayList<String>()
        // "ION: Independent Television" -> "ION"
        full.substringBefore(':', "").trim().takeIf { it.isNotEmpty() && it.length <= 12 }?.let { out.add(it) }
        val f = full.lowercase()
        val known = listOf(
            "american broadcasting" to "ABC", "columbia broadcasting" to "CBS", "national broadcasting" to "NBC",
            "fox" to "Fox", "public broadcasting" to "PBS", "cw television" to "CW", "me tv" to "MeTV",
            "metv" to "MeTV", "ion" to "ION", "court tv" to "Court TV", "home shopping" to "HSN"
        )
        for ((needle, short) in known) {
            if (Regex("\\b" + Regex.escape(needle) + "\\b").containsMatchIn(f) && !full.equals(short, true)) out.add(short)
        }
        return out
    }

    fun allChannels(): List<ListedChannel> = channels(allowDownload = false)
        .sortedWith(compareBy({ it.number.substringBefore('.').toIntOrNull() ?: 0 }, { it.number.substringAfter('.', "0").toIntOrNull() ?: 0 }))

    /** Channel-list lines ("9.1 = WWTV, CBS") for antenna channels the user's list doesn't have yet. */
    fun suggestedChannelLines(existing: List<Channel>): List<String> {
        val have = existing.map { it.number }.toSet() + prefs.hiddenSet
        return channels(allowDownload = false)
            .filter { it.number !in have }
            .sortedWith(compareBy({ it.number.substringBefore('.').toIntOrNull() ?: 0 }, { it.number.substringAfter('.', "0").toIntOrNull() ?: 0 }))
            .map { c ->
                val names = listOf(c.name, c.altName, c.affiliate.removeSuffix(" Affiliate").trim())
                    .filter { it.isNotBlank() }.distinctBy { it.lowercase() }
                "${c.number} = " + names.joinToString(", ")
            }
    }
}
