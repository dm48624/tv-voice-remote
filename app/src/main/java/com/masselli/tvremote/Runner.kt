package com.masselli.tvremote

/** Carries out commands on the TV and Roku. Call from a background thread. */
class Runner(private val prefs: Prefs) {
    val tv = SamsungTv(prefs)
    val roku = Roku(prefs)

    fun parser() = CommandParser(CommandParser.parseChannels(prefs.channelsText))

    /** Speech recognition gives several guesses; use the first one we understand. */
    fun handleSpeech(guesses: List<String>): Pair<String, String> {
        val p = parser()
        val pick = guesses.firstOrNull { p.parse(it) !is Command.Unknown } ?: guesses.firstOrNull().orEmpty()
        return pick to run(p.parse(pick))
    }

    fun handleText(text: String): String = run(parser().parse(text))

    fun testSamsungSearch(): String = run(Command.Search("NCIS"))

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
        is Command.Search -> if (c.onRoku) rokuSearch(c.query) else samsungSearch(c.query)
        is Command.SearchRokuAgain -> {
            val q = prefs.lastSearch
            if (q.isEmpty()) "What should I look for? Say something like: find NCIS on the Roku." else rokuSearch(q)
        }
        is Command.Go -> go(c.target)
        is Command.Power -> power(c.on)
        is Command.Listings -> {
            ensureLiveTv()
            tv.key("KEY_GUIDE")
            "I can't look up TV listings yet, so I opened the TV guide for you."
        }
        is Command.Unknown -> "Sorry, I didn't understand \"${c.text}\"."
    }

    private fun typeChannel(number: String) {
        val keys = number.mapNotNull { ch ->
            when {
                ch.isDigit() -> "KEY_$ch"
                ch == '.' -> "KEY_PLUS100" // the "-" sub-channel button
                else -> null
            }
        }
        tv.keys(keys, 350)
        tv.key("KEY_ENTER")
    }

    private fun ensureLiveTv(force: Boolean = false) {
        if (force || prefs.mode != "tv") {
            tv.key("KEY_TV")
            prefs.mode = "tv"
            Thread.sleep(1500)
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

    private fun samsungSearch(query: String): String {
        prefs.lastSearch = query
        tv.runRecipe(prefs.samsungSearchKeys)
        tv.typeText(query)
        prefs.mode = "tv"
        return "Searching the Samsung for $query. If it's not there, say: try the Roku."
    }

    private fun rokuSearch(query: String): String {
        prefs.lastSearch = query
        switchToRoku()
        roku.search(query)
        return "Searching the Roku for $query."
    }

    private fun go(target: String): String {
        val apps = try { roku.apps() } catch (e: TvException) { emptyList() }
        val t = Text.core(Text.normalize(target))
        val match = apps.filter { (_, name) ->
            val n = Text.core(Text.normalize(name))
            n.isNotEmpty() && (n == t || n.startsWith("$t ") || t.startsWith("$n ") || (t.length >= 3 && " $n ".contains(" $t ")))
        }.minByOrNull { it.second.length }
        if (match == null) return samsungSearch(target)
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
            when (state) {
                "on" -> return "The TV is already on."
                "standby" -> { tv.reset(); tv.key("KEY_POWER"); return "Turning the TV on." }
            }
            tv.wake()
            tv.reset()
            repeat(20) {
                Thread.sleep(1000)
                if (tv.status() == "on") return "Turning the TV on."
            }
            return "I sent the wake-up signal, but the TV didn't answer. Check that Power On with Mobile is turned on in the TV's settings."
        } else {
            if (state == null || state == "standby") return "The TV already looks like it's off."
            tv.key("KEY_POWER")
            tv.reset()
            prefs.mode = "tv"
            return "Turning the TV off."
        }
    }
}
