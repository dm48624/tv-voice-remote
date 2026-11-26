package com.masselli.tvremote

/** Remote-control buttons a spoken command can press. */
enum class Nav { HOME, BACK, OK, UP, DOWN, LEFT, RIGHT, PLAY, PAUSE, STOP, REWIND, FAST_FORWARD, INFO, GUIDE, EXIT, MENU, CAPTIONS, REPLAY }

enum class Source { ROKU, ANTENNA }

sealed class Command {
    data class Key(val nav: Nav, val times: Int = 1) : Command()
    data class Volume(val up: Boolean, val steps: Int) : Command()
    object Mute : Command()
    data class ChannelStep(val up: Boolean) : Command()
    object PreviousChannel : Command()
    data class Tune(val number: String, val name: String?) : Command()
    /** "Put on X" where X isn't a channel: open a Roku app named X, or search for X. */
    data class Go(val target: String) : Command()
    data class Search(val query: String, val onRoku: Boolean = false) : Command()
    /** "Try the Roku": repeat the last search on the Roku. */
    object SearchRokuAgain : Command()
    /** "Yes" after the app offers to put a show on. */
    object Confirm : Command()
    /** "I don't get that channel": never suggest it again. number == null means the last one suggested. */
    data class HideChannel(val number: String?) : Command()
    data class SwitchInput(val source: Source) : Command()
    data class Power(val on: Boolean) : Command()
    data class Listings(val question: String) : Command()
    data class Unknown(val text: String) : Command()
}

data class Channel(val number: String, val names: List<String>)

/** Turns whatever speech recognition hands us into a predictable lowercase form. */
object Text {
    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    fun normalize(input: String): String {
        var s = input.lowercase()
            .replace("&", " and ")
            .replace("+", " plus ")
            .replace('’', '\'')
        // "9-1" means sub-channel 9.1
        s = s.replace(Regex("(\\d)\\s*-\\s*(\\d)"), "$1.$2")
        // "tv9" -> "tv 9", "9news" -> "9 news"
        s = s.replace(Regex("([a-z])(\\d)"), "$1 $2").replace(Regex("(\\d)([a-z])"), "$1 $2")
        s = s.replace("'", "")
        s = s.replace(Regex("[^a-z0-9. ]"), " ")
        val tokens = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val tens = TENS[t]
            if (tens != null) {
                val unit = tokens.getOrNull(i + 1)?.let { UNITS[it] }
                if (unit != null && unit in 1..9) {
                    out.add((tens + unit).toString()); i += 2; continue
                }
                out.add(tens.toString()); i++; continue
            }
            val u = UNITS[t]
            out.add(u?.toString() ?: t)
            i++
        }
        s = out.joinToString(" ")
        s = s.replace(Regex("(\\d+)\\s*(?:\\.|point|dot|dash)\\s*(\\d+)"), "$1.$2")
        s = s.replace(Regex("(?<!\\d)\\.|\\.(?!\\d)"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private val FILLER = setOf("tv", "channel", "channels", "station", "the", "on", "please", "network", "now", "app")

    /** Drop words that don't help pick a channel or app ("the", "tv", "channel"...). */
    fun core(normalized: String): String =
        normalized.split(' ').filter { it.isNotEmpty() && it !in FILLER }.joinToString(" ")
}

class CommandParser(private val channels: List<Channel>) {

    companion object {
        fun parseChannels(text: String): List<Channel> = text.lines().mapNotNull { line ->
            val l = line.substringBefore('#').trim()
            if (l.isEmpty()) return@mapNotNull null
            val parts = l.split("=", limit = 2)
            if (parts.size < 2) return@mapNotNull null
            val num = parts[0].trim().replace('-', '.')
            if (!num.matches(Regex("\\d+(\\.\\d+)?"))) return@mapNotNull null
            val names = parts[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            Channel(num, names)
        }

        private val POLITE_START = Regex("^(please|can you|could you|would you|will you|hey|ok|okay|alright|all right|now|and|so|um|uh)\\s+")
        private val POLITE_END = Regex("\\s+(please|for me|thanks|thank you|now)$")

        private val POWER_OFF = listOf(
            Regex("^(turn|switch|shut|power) off( the)?( tv| television)?$"),
            Regex("^(turn|switch|shut|power) (it|the tv|the television|tv|television) off$"),
            Regex("^(tv|television) off$"),
            Regex("^(power down|shut down|good ?night)$")
        )
        private val POWER_ON = listOf(
            Regex("^(turn|switch|power) on( the)?( tv| television)?$"),
            Regex("^(turn|switch|power) (it|the tv|the television|tv|television) on$"),
            Regex("^(tv|television) on$"),
            Regex("^(wake up|power on|wake up the tv)$")
        )

        private val LISTING_HINTS = listOf(
            "whats on", "what is on", "anything on", "is there", "is anything", " on now", "on right now",
            "on tonight", "on later", "listings", "schedule", "whats playing", "what is playing", "when is", "what time is"
        )

        private val NAV = linkedMapOf(
            Nav.HOME to listOf("home", "go home", "home screen", "main menu", "go to home", "go to the home screen"),
            Nav.BACK to listOf("back", "go back", "return", "previous screen"),
            Nav.OK to listOf("ok", "okay", "select", "enter", "click", "choose that", "pick that", "that one", "select that", "choose it", "open it", "play that", "click it"),
            Nav.UP to listOf("up", "move up", "go up"),
            Nav.DOWN to listOf("down", "move down", "go down"),
            Nav.LEFT to listOf("left", "move left", "go left"),
            Nav.RIGHT to listOf("right", "move right", "go right"),
            Nav.PLAY to listOf("play", "resume", "continue", "unpause", "keep playing", "start it", "play it"),
            Nav.PAUSE to listOf("pause", "hold on", "wait", "pause it", "pause that", "pause the show"),
            Nav.STOP to listOf("stop", "stop it", "stop playing"),
            Nav.REWIND to listOf("rewind", "back up", "go back a bit"),
            Nav.FAST_FORWARD to listOf("fast forward", "forward", "skip ahead", "skip", "skip this"),
            Nav.REPLAY to listOf("replay", "instant replay", "what did they say", "what did he say", "what did she say"),
            Nav.INFO to listOf("info", "information", "details", "whats this"),
            Nav.GUIDE to listOf("guide", "tv guide", "channel guide", "show the guide", "open the guide", "show guide", "show me the guide"),
            Nav.EXIT to listOf("exit", "close", "close that", "close it", "get out", "get rid of that"),
            Nav.MENU to listOf("menu", "settings", "open settings"),
            Nav.CAPTIONS to listOf("captions", "closed captions", "closed captioning", "subtitles", "cc",
                "turn on captions", "turn on the captions", "turn off captions", "turn off the captions",
                "turn on subtitles", "turn off subtitles", "captions on", "captions off")
        )

        private val FIND = Regex("^(search roku for|search the roku for|find|search for|search|look for|look up|where can i watch|where can i find|where can i see|where is|i want to find|find me)\\s+(.+)$")
        private val ROKU_AGAIN = Regex("^(try|search|check|look on|search on|look at|use) (the )?roku( instead| too| now)?$|^(try|search) on (the )?roku$|^not there$|^try somewhere else$")
        private val GO = Regex("^(go to|put on|turn to|switch to|switch over to|change to|tune to|change the channel to|change channel to|go to channel|i want to watch|lets watch|let me watch|can we watch|watch|play|open up|open|launch|start|bring up|show me|show|turn on|switch on|channel|put it on|put)\\s+(.+)$")
    }

    fun parse(raw: String): Command {
        var s = Text.normalize(raw)
        var prev: String
        do {
            prev = s
            s = s.replace(POLITE_START, "").replace(POLITE_END, "").trim()
        } while (s != prev)
        if (s.isBlank()) return Command.Unknown(raw)

        if (s.matches(Regex("^(yes|yeah|yep|yup|yes please|sure|go ahead|do it|please do|put it on|ok put it on|okay put it on|yes put it on|lets watch it|watch it)$"))) return Command.Confirm
        Regex("^(?:i|we) (?:dont|do not|cant|cannot|can not) (?:get|receive|pick up|watch) (?:that|this|it|that one|that station|(?:that |the )?channel(?: (\\d+(?:\\.\\d+)?))?)(?: channel)?$").matchEntire(s)?.let {
            return Command.HideChannel(it.groupValues[1].ifEmpty { null })
        }
        Regex("^(?:skip|hide|remove|forget|never use|dont use) (?:that|this|that one|(?:that |the )?channel(?: (\\d+(?:\\.\\d+)?))?)(?: channel)?$").matchEntire(s)?.let {
            return Command.HideChannel(it.groupValues[1].ifEmpty { null })
        }
        if (s.matches(Regex("^(that|this) channel (doesnt|does not|wont|will not) (work|come in)$"))) return Command.HideChannel(null)
        if (POWER_OFF.any { it.matches(s) }) return Command.Power(false)
        if (POWER_ON.any { it.matches(s) }) return Command.Power(true)

        if (LISTING_HINTS.any { " $s".contains(it) }) return Command.Listings(raw)

        volume(s)?.let { return it }
        if (s.matches(Regex("^(mute|unmute|mute it|unmute it|mute the tv|mute the sound|sound off|sound on|be quiet|quiet|silence)$"))) return Command.Mute

        channelStep(s)?.let { return it }
        nav(s)?.let { return it }

        if (ROKU_AGAIN.matches(s)) return Command.SearchRokuAgain
        FIND.matchEntire(s)?.let { m ->
            val raw2 = m.groupValues[2]
            val onRoku = m.groupValues[1].startsWith("search") && m.groupValues[1].endsWith("roku for") || Regex("\\s+on (the )?roku$").containsMatchIn(raw2)
            // Keep "the" in show titles ("The Middle"); only trim "on the Roku".
            val q = raw2.replace(Regex("\\s+on (the )?(roku|tv|television)$"), "").trim()
            return resolve(cleanTarget(q)) ?: Command.Search(q, onRoku)
        }
        GO.matchEntire(s)?.let { m ->
            val t = cleanTarget(m.groupValues[2])
            return resolve(t) ?: Command.Go(t)
        }
        resolve(s)?.let { return it }
        return Command.Unknown(raw)
    }

    private fun cleanTarget(t: String): String =
        t.replace(Regex("\\s+on (the )?(roku|tv|television)$"), "")
            .replace(Regex("^(the|a|an)\\s+"), "")
            .trim()

    /** Channel, number or input; null if the phrase isn't one of those. */
    private fun resolve(target: String): Command? {
        val t = target.trim()
        if (t.matches(Regex("^(the )?(roku|roku stick|streaming|streaming stick|streaming apps|apps)$"))) return Command.SwitchInput(Source.ROKU)
        if (t.matches(Regex("^(the )?(antenna|live tv|regular tv|normal tv|over the air|local channels|tv|television|live television|cable)$"))) return Command.SwitchInput(Source.ANTENNA)
        matchChannel(t)?.let { return Command.Tune(it.number, it.names.firstOrNull()) }
        val core = Text.core(t)
        if (core.matches(Regex("^\\d+(\\.\\d+)?$"))) {
            val known = channels.firstOrNull { it.number == core }
                ?: if (!core.contains('.')) channels.firstOrNull { it.number.startsWith("$core.") } else null
            return if (known != null) Command.Tune(known.number, known.names.firstOrNull())
            else Command.Tune(core, null)
        }
        return null
    }

    fun matchChannel(target: String): Channel? {
        val t = Text.core(Text.normalize(target))
        if (t.isEmpty()) return null
        var best: Channel? = null
        var bestLen = 0
        for (c in channels) for (n in c.names) {
            val a = Text.core(Text.normalize(n))
            if (a.isEmpty()) continue
            val hit = t == a || " $t ".contains(" $a ") || (t.length >= 3 && " $a ".contains(" $t "))
            if (hit && a.length > bestLen) { best = c; bestLen = a.length }
        }
        return best
    }

    private fun volume(s: String): Command? {
        val words = listOf("volume", "louder", "quieter", "softer", "turn it up", "turn it down", "turn up", "turn down",
            "too loud", "cant hear", "cannot hear", "turn the sound", "sound up", "sound down", "lower the sound", "raise the sound")
        if (words.none { s.contains(it) }) return null
        if (s.contains("channel")) return null
        val up = Regex("\\b(up|louder|raise|increase|higher|cant hear|cannot hear)\\b").containsMatchIn(s)
        val down = Regex("\\b(down|quieter|softer|lower|decrease|too loud)\\b").containsMatchIn(s)
        if (up == down) return null
        val n = Regex("\\b(\\d{1,2})\\b").find(s)?.groupValues?.get(1)?.toInt()
        val steps = when {
            n != null && n in 1..50 -> n
            Regex("\\b(little|bit|tad|touch|notch)\\b").containsMatchIn(s) -> 1
            Regex("\\b(lot|way|much)\\b").containsMatchIn(s) -> 8
            else -> 3
        }
        return Command.Volume(up, steps)
    }

    private fun channelStep(s: String): Command? {
        if (s.matches(Regex("^(previous channel|last channel|prev channel|go back a channel|go back to the last channel|go back to the previous channel|flip back|switch back)$"))) return Command.PreviousChannel
        if (s.matches(Regex("^(channel up|up a channel|up 1 channel|next channel|next|next 1|flip|flip up|go up a channel)$"))) return Command.ChannelStep(true)
        if (s.matches(Regex("^(channel down|down a channel|down 1 channel|flip down|go down a channel)$"))) return Command.ChannelStep(false)
        return null
    }

    private fun nav(input: String): Command? {
        var s = input.replace(Regex("^(press|hit|push|tap)\\s+(the\\s+)?"), "").replace(Regex("\\s+(button|key)$"), "")
        var times = 1
        val countMatch = Regex("^(.*?)\\s+(\\d{1,2})( times)?$").matchEntire(s)
        when {
            s.endsWith(" twice") -> { times = 2; s = s.removeSuffix(" twice") }
            countMatch != null -> { times = countMatch.groupValues[2].toInt(); s = countMatch.groupValues[1] }
        }
        for ((nav, phrases) in NAV) {
            if (s in phrases) {
                val many = nav in setOf(Nav.UP, Nav.DOWN, Nav.LEFT, Nav.RIGHT, Nav.BACK, Nav.REWIND, Nav.FAST_FORWARD)
                return Command.Key(nav, if (many) times.coerceIn(1, 20) else 1)
            }
        }
        return null
    }
}
