package com.masselli.tvremote

import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream

data class Airing(val startSec: Long, val endSec: Long, val title: String)
data class ListedChannel(
    val number: String,
    val name: String,
    val airings: List<Airing>,
    val antenna: Boolean = false,
    val affiliate: String = "",
    /** Schedules Direct's longer station name, which sometimes names the network (e.g. "Cozi TV"). */
    val altName: String = ""
)

data class Hit(val channel: ListedChannel, val airing: Airing, val score: Int, val inMyList: Boolean = false) {
    fun onNow(nowSec: Long) = airing.startSec <= nowSec && nowSec < airing.endSec
}

/**
 * Samsung TV Plus channel numbers and schedules (about the next 5 hours), from the free
 * listings file published at i.mjh.nz. Downloaded to the tablet and refreshed every few hours.
 */
class Listings(private val dir: File, private val prefs: Prefs) {
    val antenna = SchedulesDirect(prefs, dir)
    companion object {
        private val URLS = listOf(
            "https://raw.githubusercontent.com/matthuisman/i.mjh.nz/master/SamsungTVPlus/.channels.json.gz",
            "https://i.mjh.nz/SamsungTVPlus/.channels.json.gz"
        )
        private const val MAX_AGE_MS = 2 * 3600 * 1000L
        private val SKIP_WORDS = setOf("the", "a", "an", "show", "episode")

        fun timeText(sec: Long): String {
            val d = Date(sec * 1000)
            val t = SimpleDateFormat("h:mm a", Locale.US).format(d)
            val c = Calendar.getInstance().apply { time = d }
            val today = Calendar.getInstance()
            val diff = c.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)
            return when (diff) { 0 -> t; 1 -> "$t tomorrow"; -1 -> "$t yesterday"; else -> SimpleDateFormat("EEE h:mm a", Locale.US).format(d) }
        }
    }

    private val file get() = File(dir, "tvplus-channels.json.gz")
    @Volatile private var cache: List<ListedChannel>? = null
    @Volatile private var cacheTime = 0L

    /** Antenna channels (if Schedules Direct is set up) plus Samsung TV Plus channels. */
    fun channels(forceRefresh: Boolean = false, ignoreMyList: Boolean = false): List<ListedChannel> {
        val ant = antenna.channels()
        val plus = try { tvPlusChannels(forceRefresh) } catch (e: TvException) {
            if (ant.isEmpty()) throw e
            emptyList()
        }
        val hidden = prefs.hiddenSet
        val mine = CommandParser.parseChannels(prefs.channelsText).map { it.number }.toSet()
        // If the user has listed their antenna channels, stick to those.
        val antKept = if (!ignoreMyList && prefs.onlyMyChannels && ant.any { it.number in mine }) ant.filter { it.number in mine } else ant
        return (antKept + plus).filter { it.number !in hidden }
    }

    @Synchronized
    fun tvPlusChannels(forceRefresh: Boolean = false): List<ListedChannel> {
        val now = System.currentTimeMillis()
        if (!forceRefresh) cache?.let { if (now - cacheTime < MAX_AGE_MS) return it }
        if (forceRefresh || !file.exists() || now - file.lastModified() > MAX_AGE_MS) download()
        if (!file.exists()) throw TvException("I couldn't download the Samsung TV Plus listings. Check the tablet's internet connection.")
        val parsed = parse(GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() })
        cache = parsed
        cacheTime = now
        return parsed
    }

    fun ageText(): String {
        if (!file.exists()) return "not downloaded yet"
        val mins = (System.currentTimeMillis() - file.lastModified()) / 60000
        return if (mins < 2) "just now" else "$mins minutes ago"
    }

    private fun download() {
        for (u in URLS) {
            try {
                Net.http.newCall(Request.Builder().url(u).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val body = r.body ?: return@use
                    val tmp = File(dir, "tvplus.tmp")
                    body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (tmp.length() > 10_000) {
                        tmp.renameTo(file)
                        return
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun parse(json: String): List<ListedChannel> {
        val chans = JSONObject(json).getJSONObject("regions").getJSONObject("us").getJSONObject("channels")
        val out = ArrayList<ListedChannel>()
        val keys = chans.keys()
        while (keys.hasNext()) {
            val c = chans.getJSONObject(keys.next())
            val chno = c.optInt("chno", 0)
            if (chno <= 0) continue
            val progs = c.optJSONArray("programs")
            val airings = ArrayList<Airing>()
            if (progs != null) {
                for (i in 0 until progs.length()) {
                    val p = progs.getJSONArray(i)
                    val start = p.getLong(0)
                    val end = if (i + 1 < progs.length()) progs.getJSONArray(i + 1).getLong(0) else start + 3600
                    airings.add(Airing(start, end, p.getString(1)))
                }
            }
            out.add(ListedChannel(chno.toString(), c.optString("name"), airings))
        }
        return out.sortedBy { it.number.toInt() }
    }

    private fun words(s: String) = Text.normalize(s).split(' ').filter { it.isNotEmpty() && it !in SKIP_WORDS }

    /** Shows whose title matches, best first: on now, then soonest. */
    fun search(query: String, alsoChannelNames: Boolean = false, antennaOnly: Boolean = false, ignoreMyList: Boolean = false): List<Hit> {
        val nowSec = System.currentTimeMillis() / 1000
        val q = words(query)
        if (q.isEmpty()) return emptyList()
        val qs = q.joinToString(" ")
        val hits = ArrayList<Hit>()
        val mine = CommandParser.parseChannels(prefs.channelsText).map { it.number }.toSet()
        for (c in channels(ignoreMyList = ignoreMyList)) {
            if (antennaOnly && !c.antenna) continue
            val nameWords = words(c.name)
            val nameHit = alsoChannelNames && q.all { it in nameWords }
            for (a in c.airings) {
                if (a.endSec <= nowSec) continue
                val t = words(a.title).joinToString(" ")
                var score = when {
                    t == qs -> 3
                    t.startsWith("$qs ") -> 2
                    q.all { " $t ".contains(" $it ") } -> 1
                    else -> 0
                }
                if (score == 0 && nameHit && a.startSec <= nowSec) score = 1
                if (score > 0) hits.add(Hit(c, a, score, c.number in mine))
            }
        }
        // An exact title ("The Middle") beats a partial one ("Malcolm in the Middle").
        val best = hits.maxOfOrNull { it.score } ?: return emptyList()
        return hits.filter { it.score == best }.sortedWith(
            compareByDescending<Hit> { it.onNow(nowSec) }.thenByDescending { it.score }.thenByDescending { it.inMyList }.thenByDescending { it.channel.antenna }.thenBy { it.airing.startSec }
        )
    }

    /**
     * A channel whose name (or network, like ION) is what was said: "Danger TV", "ION", "CBS News 24/7".
     * Prefers channels in the user's list, then antenna channels, then Samsung TV Plus.
     */
    fun channelNamed(target: String): ListedChannel? {
        val t = Text.core(Text.normalize(target))
        if (t.length < 2) return null
        val all = try { channels() } catch (e: TvException) { return null }
        val mine = CommandParser.parseChannels(prefs.channelsText).map { it.number }.toSet()
        fun rawNames(c: ListedChannel) = listOf(c.name, c.altName, c.affiliate.replace(Regex("(?i)\\s*affiliate$"), ""))
            .filter { it.isNotBlank() }
        fun names(c: ListedChannel) = rawNames(c).map { Text.core(Text.normalize(it)) }.filter { it.isNotEmpty() }
        // "DangerTV" vs "Danger TV": also compare with spaces (and a trailing "tv") squeezed out.
        fun squash(x: String) = Text.normalize(x).replace(" ", "").removeSuffix("tv").removeSuffix("channel")
        val tSquash = squash(target)
        fun rank(c: ListedChannel) = (if (c.number in mine) 2 else 0) + (if (c.antenna) 1 else 0)
        val exact = all.filter { c -> names(c).any { it == t } || (tSquash.length >= 3 && rawNames(c).any { squash(it) == tSquash }) }
        if (exact.isNotEmpty()) return exact.maxByOrNull { rank(it) }
        if (t.length < 4) return null
        val starts = all.filter { c -> names(c).any { it.startsWith("$t ") } }
        return starts.maxByOrNull { rank(it) }
    }

    fun nowOn(c: ListedChannel): Airing? {
        val nowSec = System.currentTimeMillis() / 1000
        return c.airings.firstOrNull { it.startSec <= nowSec && nowSec < it.endSec }
    }
}
