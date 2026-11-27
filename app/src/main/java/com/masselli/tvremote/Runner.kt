package com.masselli.tvremote

/** Carries out commands on the TV and Roku. Call from a background thread. */
class Runner(private val prefs: Prefs, filesDir: java.io.File) {
    val tv = SamsungTv(prefs)
    val roku = Roku(prefs)
    val listings = Listings(filesDir, prefs)

    fun parser() = CommandParser(CommandParser.parseChannels(prefs.channelsText))

    /** Speech recognition gives several guesses; use the first one we understand. */
    fun handleSpeech(guesses: List<String>): Pair<String, String> {
        val p = parser()
        val pick = guesses.firstOrNull { p.parse(it) !is Command.Unknown } ?: guesses.firstOrNull().orEmpty()
        return pick to run(p.parse(pick))
    }

    fun handleText(text: String): String = run(parser().parse(text))

    fun testSamsungSearch(): String = try { samsungSearch("NCIS") } catch (e: Exception) { e.message ?: "Something went wrong." }

    fun connectSchedulesDirect(): String = try {
        listings.antenna.refresh()
    } catch (e: TvException) {
        e.message ?: "Couldn't connect to Schedules Direct."
    }

    fun listingsStatus(): String = try {
        val chans = listings.tvPlusChannels(forceRefresh = true)
        val nowSec = System.currentTimeMillis() / 1000
        val sample = chans.filter { it.number.toInt() in 1100..1199 }.take(3)
            .joinToString("; ") { c -> "${c.number} ${c.name}: ${c.airings.firstOrNull { it.startSec <= nowSec && nowSec < it.endSec }?.title ?: "?"}" }
        val ant = if (listings.antenna.configured) {
            val a = listings.antenna.channels(allowDownload = false)
            val shows = a.sumOf { it.airings.size }
            val err = prefs.sdLastError.takeIf { it.isNotEmpty() }?.let { "Last problem: $it. " } ?: ""
            "Antenna listings: ${a.size} channels, $shows shows, updated ${listings.antenna.ageText()}. $err"
        } else "Antenna listings: not set up. "
        ant + "Samsung TV Plus listings: ${chans.size} channels, updated ${listings.ageText()}. Right now: $sample"
    } catch (e: Exception) {
        e.message ?: "Couldn't load the listings."
    }

    fun run(c: Command): String = try {
        exec(c)
    } catch (e: TvException) {
        e.message ?: "Something went wrong."
    } catch (e: Exception) {
        "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun exec(c: Command): String = when (c) {
        is Command.Volume -> {
            repeat(c.steps) {
                tv.key(if (c.up) "KEY_VOLUP" else "KEY_VOLDOWN")
                Thread.sleep(150)
            }
            if (c.up) "Volume up." else "Volume down."
        }
        is Command.Mute -> { tv.key("KEY_MUTE"); "Mute." }
        is Command.ChannelStep -> {
            ensureLiveTv()
            tv.key(if (c.up) "KEY_CHUP" else "KEY_CHDOWN")
            if (c.up) "Channel up." else "Channel down."
        }
        is Command.PreviousChannel -> { ensureLiveTv(); tv.key("KEY_PRECH"); "Back to the last channel." }
        is Command.Tune -> {
            ensureLiveTv()
            typeChannel(c.number)
            "Going to " + (c.name ?: "channel ${c.number}") + "."
        }
        is Command.Key -> nav(c)
        is Command.SwitchInput -> when (c.source) {
            Source.ROKU -> { switchToRoku(force = true); "Switched to the Roku." }
            Source.ANTENNA -> { ensureLiveTv(force = true); "Switched to live TV." }
        }
        is Command.Search -> if (c.onRoku) rokuSearch(c.query) else showSearch(c.query)
        is Command.Confirm -> {
            val ch = prefs.pendingChannel
            if (ch.isEmpty()) "There's nothing waiting to put on." else {
                prefs.pendingChannel = ""
                tuneTo(ch)
                "Putting on channel $ch."
            }
        }
        is Command.SearchRokuAgain -> {
            val q = prefs.lastSearch
            if (q.isEmpty()) "What should I look for? Say something like: find NCIS on the Roku." else rokuSearch(q)
        }
        is Command.Go -> go(c.target)
        is Command.Power -> power(c.on)
        is Command.Listings -> listingsAnswer(c.question)
        is Command.HideChannel -> {
            val n = c.number ?: prefs.lastSuggested
            if (n.isEmpty()) "Which channel? Say something like: we don't get channel 14.1." else {
                if (n !in prefs.hiddenSet) prefs.hiddenChannels = (prefs.hiddenSet + n).joinToString(", ")
                prefs.lastSuggested = ""
                val again = prefs.lastSearch
                "OK, I won't use channel $n anymore. " + if (c.number == null && again.isNotEmpty()) showSearch(again) else ""
            }
        }
        is Command.Unknown -> "Sorry, I didn't understand \"${c.text}\"."
    }

    private fun tuneTo(number: String) {
        ensureLiveTv()
        typeChannel(number)
    }

    private fun spokenChannel(c: ListedChannel): String {
        val mine = CommandParser.parseChannels(prefs.channelsText).firstOrNull { it.number == c.number }?.names?.firstOrNull()
        return "${mine ?: c.name}, channel ${c.number}"
    }

    /** Download listings in the background so the first question is quick. */
    fun warmUp() {
        try { listings.channels() } catch (_: Exception) {}
    }

    /** "Find NCIS": look in the TV Plus listings first, then fall back to searching the Samsung's apps. */
    private fun showSearch(query: String): String {
        prefs.lastSearch = query
        val hits = try { listings.search(query) } catch (e: TvException) {
            return samsungSearch(query, "I couldn't get the TV listings, so I'm searching the Samsung's apps. ")
        }
        val nowSec = System.currentTimeMillis() / 1000
        val onNow = hits.filter { it.onNow(nowSec) }.distinctBy { it.channel.number }
        if (onNow.isNotEmpty()) {
            val h = onNow.first()
            val also = if (onNow.size > 1) " It's also on " + onNow.drop(1).take(2).joinToString(" and ") { "channel ${it.channel.number}" } + "." else ""
            val what = "${h.airing.title} is on ${spokenChannel(h.channel)}, until ${Listings.timeText(h.airing.endSec)}."
            prefs.lastSuggested = h.channel.number
            return if (prefs.autoTune) {
                tuneTo(h.channel.number)
                "$what Putting it on. If you don't get that channel, say: we don't get that channel.$also"
            } else {
                prefs.pendingChannel = h.channel.number
                "$what Want me to put it on? Say yes.$also"
            }
        }
        // Not a show on right now; maybe it's a channel name: "Find Danger TV" / "Find ION". / "Find ION": a channel name, not a show.
        listings.channelNamed(query)?.let { c ->
            tuneTo(c.number)
            prefs.lastSuggested = c.number
            val now = listings.nowOn(c)
            return "Going to ${spokenChannel(c)}." + (now?.let { " ${it.title} is on." } ?: "")
        }
        val next = hits.firstOrNull()
        if (next != null) {
            prefs.pendingChannel = next.channel.number
            prefs.lastSuggested = next.channel.number
            return "${next.airing.title} isn't on right now. It's next on ${spokenChannel(next.channel)} at ${Listings.timeText(next.airing.startSec)}. Say yes to go to that channel now."
        }
        // Not on any channel in the user's list: look at every antenna channel before giving up.
        if (prefs.onlyMyChannels) {
            val other = try { listings.search(query, ignoreMyList = true) } catch (e: TvException) { emptyList() }
            val h = other.firstOrNull()
            if (h != null) {
                prefs.pendingChannel = h.channel.number
                prefs.lastSuggested = h.channel.number
                val time = if (h.onNow(nowSec)) "is on now" else "starts at ${Listings.timeText(h.airing.startSec)}"
                return "${h.airing.title} $time on ${spokenChannel(h.channel)}, which isn't in your channel list. " +
                    "Say yes to go there, or say: we don't get that channel."
            }
        }
        val all = try { listings.channels() } catch (e: TvException) { emptyList() }
        val antCount = all.count { it.antenna }
        val plusCount = all.size - antCount
        val where = when {
            !listings.antenna.configured -> "on Samsung TV Plus in the next few hours, and your antenna channels aren't in my listings,"
            antCount == 0 -> "anywhere. The antenna listings didn't download" +
                (prefs.sdLastError.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "") + ","
            else -> "in the listings for your $antCount antenna channels or $plusCount TV Plus channels,"
        }
        return samsungSearch(query, "I don't see $query $where so I'm searching the Samsung's apps. ")
    }

    private val QUESTION_WORDS = setOf(
        "is", "are", "there", "anything", "any", "something", "whats", "what", "on", "now", "right", "tonight",
        "later", "playing", "good", "a", "an", "the", "listings", "schedule", "when", "time", "it", "today", "show", "shows"
    )

    /** Now/next on one channel, if the topic names a channel. Null if it isn't a channel. */
    private fun channelInfo(topic: String): String? {
        val mine = CommandParser.parseChannels(prefs.channelsText)
        val core = Text.core(topic)
        val number = when {
            core.matches(Regex("^\\d+(\\.\\d+)?$")) -> core
            else -> CommandParser(mine).matchChannel(topic)?.number
                ?: listings.channelNamed(topic)?.number
                ?: return null
        }
        val all = try { listings.channels(ignoreMyList = true) } catch (e: TvException) { return e.message }
        val c = all.firstOrNull { it.number == number }
            ?: all.firstOrNull { !number.contains('.') && it.number == "$number.1" }
            ?: return "I don't have any listings for channel $number. Schedules Direct may list that station under a different number, or not at all."
        val nowSec = System.currentTimeMillis() / 1000
        val upcoming = c.airings.filter { it.endSec > nowSec }.take(4)
        if (upcoming.isEmpty()) return "I have channel ${c.number} (${c.name}) but no upcoming shows for it."
        prefs.pendingChannel = c.number
        return "On ${spokenChannel(c)}: " + upcoming.joinToString("; ") {
            (if (it.startSec <= nowSec) "now, " else Listings.timeText(it.startSec) + ", ") + it.title
        } + ". Say yes to go there."
    }

    /** For Settings: every listing that matches, with channel and time, to see what the listings hold. */
    fun lookUp(query: String): String = try {
        val nowSec = System.currentTimeMillis() / 1000
        val mine = CommandParser.parseChannels(prefs.channelsText).map { it.number }.toSet()
        val all = listings.channels(ignoreMyList = true)
        val ant = all.filter { it.antenna }
        val q = Text.normalize(query).split(' ').filter { it.isNotEmpty() && it != "the" }
        val found = ArrayList<Pair<ListedChannel, Airing>>()
        for (c in all) for (a in c.airings) {
            if (a.endSec <= nowSec) continue
            val t = Text.normalize(a.title)
            if (q.all { " $t ".contains(" $it ") }) found.add(c to a)
        }
        // Soonest first, so whatever is on now is at the top.
        val rows = found.sortedBy { it.second.startSec }.map { (c, a) ->
            val tag = when { c.number in prefs.hiddenSet -> " [skipped]"; c.number in mine -> " [in your list]"; else -> "" }
            val time = if (a.startSec <= nowSec) "NOW (since ${Listings.timeText(a.startSec)})" else Listings.timeText(a.startSec)
            "$time  ch ${c.number} ${c.name}$tag: ${a.title}"
        }
        val antAirings = ant.flatMap { it.airings }
        val span = if (antAirings.isEmpty()) "No antenna listings loaded." else
            "Antenna listings run from ${Listings.timeText(antAirings.minOf { it.startSec })} to ${Listings.timeText(antAirings.maxOf { it.endSec })} " +
                "(${antAirings.count { it.startSec <= nowSec && nowSec < it.endSec }} shows on right now)."
        "Searched ${ant.size} antenna and ${all.size - ant.size} TV Plus channels. $span\n" +
            (if (rows.isEmpty()) "No matches for \"$query\"." else "${rows.size} matches:\n" + rows.take(15).joinToString("\n"))
    } catch (e: Exception) {
        e.message ?: "Lookup failed."
    }

    private fun listingsAnswer(question: String): String {
        val words = Text.normalize(question).split(' ').filter { it.isNotEmpty() }
        val local = "local" in words
        val haveAntenna = listings.antenna.configured
        var topic = words.filter { it !in QUESTION_WORDS && it != "local" }.joinToString(" ")
        if (local && topic.isEmpty()) topic = "news"
        if ((local && !haveAntenna) || topic.isEmpty()) {
            ensureLiveTv()
            tv.key("KEY_GUIDE")
            return if (local) "Your antenna channels aren't in my listings yet, so I opened the TV guide for you."
            else "Here's the TV guide."
        }
        // "What's on channel 9.2?" / "What's on 9&10?"
        channelInfo(topic)?.let { return it }
        val hits = try { listings.search(topic, alsoChannelNames = !local, antennaOnly = local) } catch (e: TvException) {
            return e.message ?: "I couldn't get the listings."
        }
        val nowSec = System.currentTimeMillis() / 1000
        val onNow = hits.filter { it.onNow(nowSec) }.distinctBy { it.channel.number }
        if (onNow.isEmpty()) {
            val next = hits.firstOrNull()
                ?: return "I don't see any ${if (local) "local " else ""}$topic in the listings for the next while." + if (haveAntenna) "" else " Your antenna channels aren't in my listings yet."
            prefs.pendingChannel = next.channel.number
            prefs.lastSuggested = next.channel.number
            return "Nothing right now. ${next.airing.title} starts at ${Listings.timeText(next.airing.startSec)} on ${spokenChannel(next.channel)}. Say yes to go there."
        }
        prefs.pendingChannel = onNow.first().channel.number
        prefs.lastSuggested = onNow.first().channel.number
        val list = onNow.take(3).joinToString("; ") { "${it.airing.title} on ${spokenChannel(it.channel)}" }
        return "Yes. $list. Say yes to put on the first one."
    }

    private fun typeChannel(number: String) {
        // Slow and steady: the TV sometimes drops a press that arrives too quickly,
        // which turned 5.1 into 51 or left it stuck at "5-".
        for (ch in number) {
            when {
                ch.isDigit() -> { tv.key("KEY_$ch"); Thread.sleep(650) }
                ch == '.' -> { tv.key("KEY_PLUS100"); Thread.sleep(900) } // the "-" sub-channel button
            }
        }
        Thread.sleep(300)
        tv.key("KEY_ENTER")
    }

    private fun ensureLiveTv(force: Boolean = false) {
        if (force || prefs.mode != "tv") {
            tv.key("KEY_TV")
            prefs.mode = "tv"
            Thread.sleep(2500)
        }
    }

    private fun switchToRoku(force: Boolean = false) {
        if (!force && prefs.mode == "roku") return
        val hdmi = prefs.rokuHdmi
        if (hdmi.isNotEmpty()) {
            tv.key("KEY_HDMI$hdmi")
        } else {
            // Waking the Roku makes the TV switch to it if Anynet+ (HDMI-CEC) is on.
            roku.key("Home")
        }
        prefs.mode = "roku"
        Thread.sleep(1500)
    }

    private fun samsungSearch(query: String, prefix: String = ""): String {
        prefs.lastSearch = query
        tv.runRecipe(prefs.samsungSearchKeys)
        tv.typeText(query)
        prefs.mode = "tv"
        return prefix + "Searching the Samsung for $query. If it's not there, say: try the Roku."
    }

    private fun rokuSearch(query: String): String {
        prefs.lastSearch = query
        switchToRoku()
        roku.search(query)
        return "Searching the Roku for $query."
    }

    private fun go(target: String): String {
        listings.channelNamed(target)?.let { c ->
            tuneTo(c.number)
            val now = listings.nowOn(c)
            return "Going to ${spokenChannel(c)}." + (now?.let { " ${it.title} is on." } ?: "")
        }
        val apps = try { roku.apps() } catch (e: TvException) { emptyList() }
        val t = Text.core(Text.normalize(target))
        val match = apps.filter { (_, name) ->
            val n = Text.core(Text.normalize(name))
            n.isNotEmpty() && (n == t || n.startsWith("$t ") || t.startsWith("$n ") || (t.length >= 3 && " $n ".contains(" $t ")))
        }.minByOrNull { it.second.length }
        if (match == null) return showSearch(target)
        switchToRoku()
        roku.launch(match.first)
        return "Opening ${match.second}."
    }

    private fun nav(c: Command.Key): String {
        if (c.nav == Nav.GUIDE) { ensureLiveTv(); tv.key("KEY_GUIDE"); return "Here's the guide." }
        if (c.nav == Nav.CAPTIONS) { tv.key("KEY_CAPTION"); return "Captions." }
        val label = c.nav.name.lowercase().replace('_', ' ')
        if (prefs.mode == "roku") {
            val k = when (c.nav) {
                Nav.HOME -> "Home"; Nav.BACK -> "Back"; Nav.OK -> "Select"
                Nav.UP -> "Up"; Nav.DOWN -> "Down"; Nav.LEFT -> "Left"; Nav.RIGHT -> "Right"
                Nav.PLAY, Nav.PAUSE -> "Play"; Nav.STOP -> "Back"
                Nav.REWIND -> "Rev"; Nav.FAST_FORWARD -> "Fwd"; Nav.REPLAY -> "InstantReplay"
                Nav.INFO, Nav.MENU -> "Info"; Nav.EXIT -> "Home"
                else -> "Select"
            }
            repeat(c.times) { roku.key(k); Thread.sleep(200) }
        } else {
            val k = when (c.nav) {
                Nav.HOME -> "KEY_HOME"; Nav.BACK -> "KEY_RETURN"; Nav.OK -> "KEY_ENTER"
                Nav.UP -> "KEY_UP"; Nav.DOWN -> "KEY_DOWN"; Nav.LEFT -> "KEY_LEFT"; Nav.RIGHT -> "KEY_RIGHT"
                Nav.PLAY -> "KEY_PLAY"; Nav.PAUSE -> "KEY_PAUSE"; Nav.STOP -> "KEY_STOP"
                Nav.REWIND, Nav.REPLAY -> "KEY_REWIND"; Nav.FAST_FORWARD -> "KEY_FF"
                Nav.INFO -> "KEY_INFO"; Nav.EXIT -> "KEY_EXIT"; Nav.MENU -> "KEY_MENU"
                else -> "KEY_ENTER"
            }
            tv.keys(List(c.times) { k }, 200)
        }
        return label.replaceFirstChar { it.uppercase() } + "."
    }

    private fun power(on: Boolean): String {
        val state = tv.status()
        if (on) {
            if (state == "on") return "The TV is on."
            // Keep sending the network wake-up signal while also trying the Power button
            // (quick press, then held), never waiting long on a TV that isn't answering.
            val methods = listOf<() -> Unit>({ tv.key("KEY_POWER") }, { tv.hold("KEY_POWER", 3000) })
            val firstMethod = when {
                prefs.powerOnMethod in methods.indices -> prefs.powerOnMethod
                prefs.powerOffMethod == 2 -> 1
                else -> 0
            }
            val order = listOf(firstMethod) + methods.indices.filter { it != firstMethod }
            val tryAfterSec = listOf(2L, 9L)   // when to try each Power method
            var nextMethod = 0
            var lastTried = -1
            var lastWake = 0L
            tv.connectTimeoutSec = 4
            val startMs = System.currentTimeMillis()
            try {
                while (System.currentTimeMillis() - startMs < 30_000) {
                    val elapsedSec = (System.currentTimeMillis() - startMs) / 1000
                    if (System.currentTimeMillis() - lastWake >= 3000) {
                        try { tv.wake() } catch (_: TvException) {}
                        lastWake = System.currentTimeMillis()
                    }
                    if (nextMethod < order.size && elapsedSec >= tryAfterSec[nextMethod]) {
                        val idx = order[nextMethod]
                        nextMethod++
                        if (tv.status() == "standby") {
                            tv.reset()
                            try { methods[idx]() } catch (_: TvException) {}
                            lastTried = idx
                        }
                    }
                    Thread.sleep(1000)
                    if (tv.status() == "on") {
                        if (lastTried >= 0) prefs.powerOnMethod = lastTried
                        return "The TV is on."
                    }
                }
            } finally {
                tv.connectTimeoutSec = 35
                tv.reset()
            }
            return "I sent the wake-up signal, but the TV didn't answer. Check that Power On with Mobile is turned on in the TV's settings."
        } else {
            if (state == null || state == "standby") return "The TV already looks like it's off."
            prefs.mode = "tv"
            // Different Samsung models want different things; try each and check whether it worked.
            val all = listOf<Pair<String, () -> Unit>>(
                "Power button" to { tv.key("KEY_POWER") },
                "power-off code" to { tv.key("KEY_POWEROFF") },
                "held Power button" to { tv.hold("KEY_POWER", 3000) }
            )
            // Start with whichever method worked last time.
            val first = prefs.powerOffMethod.coerceIn(0, all.size - 1)
            val order = listOf(first) + all.indices.filter { it != first }
            for (idx in order) {
                val (name, action) = all[idx]
                try { action() } catch (e: TvException) { /* connection drops when the TV turns off */ }
                tv.reset()
                for (i in 0 until 6) {
                    Thread.sleep(1000)
                    val st = tv.status()
                    if (st == null || st == "standby") {
                        prefs.powerOffMethod = idx
                        return "The TV is off."
                    }
                }
                android.util.Log.i("TvVoiceRemote", "Power off with $name didn't work")
            }
            return "I tried to turn the TV off three different ways, but it still reports that it's on. Tell Claude about this."
        }
    }
}
