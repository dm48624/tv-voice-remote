package com.masselli.tvremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {
    private val channels = CommandParser.parseChannels(
        """
        # comment line
        9.1 = 9&10, nine and ten, 9&10 News, WWTV
        5.1 = TV5, WNEM
        19.1 = Delta PBS, PBS
        1001 = Samsung TV Plus News  # TV Plus example
        """.trimIndent()
    )
    private val p = CommandParser(channels)

    private fun tune(n: String) = p.parse(n).let { (it as? Command.Tune)?.number }

    @Test fun channelsParse() {
        assertEquals(4, channels.size)
        assertEquals("1001", channels[3].number)
    }

    @Test fun exactNameWins() {
        val q = CommandParser(CommandParser.parseChannels("5.4 = WNEMDT4, ION\n1510 = TV Plus ION, Samsung ION"))
        assertEquals(Command.Tune("5.4", "WNEMDT4"), q.parse("go to ION"))
        assertEquals(Command.Tune("1510", "TV Plus ION"), q.parse("go to TV Plus ION"))
        assertEquals(Command.Tune("1510", "TV Plus ION"), q.parse("put on Samsung ION"))
    }

    @Test fun forgivingChannelLines() {
        assertEquals(Channel("9.3", listOf("MeTV")), CommandParser.parseChannelLine("9.3 = MeTV"))
        assertEquals(Channel("9.3", listOf("MeTV")), CommandParser.parseChannelLine("9-3 MeTV"))
        assertEquals(Channel("9.3", listOf("MeTV", "Me TV")), CommandParser.parseChannelLine("9,3: MeTV, Me TV"))
        assertEquals(Channel("9.3", listOf("MeTV")), CommandParser.parseChannelLine("Ch 9.3 - MeTV"))
        assertEquals(Channel("9.3", emptyList()), CommandParser.parseChannelLine("9.3"))
        assertEquals(Channel("9.1", listOf("9&10", "nine and ten")), CommandParser.parseChannelLine("9.1 = 9&10, nine and ten"))
        assertEquals(Channel("1001", listOf("News")), CommandParser.parseChannelLine("1001 = News"))
        assertEquals(null, CommandParser.parseChannelLine("# a note"))
    }

    @Test fun goToNineAndTen() {
        assertEquals("9.1", tune("go to TV9&10"))
        assertEquals("9.1", tune("Go to TV 9 & 10"))
        assertEquals("9.1", tune("go to TV 9 and 10"))
        assertEquals("9.1", tune("nine and ten"))
        assertEquals("9.1", tune("put on 9&10 news"))
        assertEquals("9.1", tune("watch channel 9"))
        assertEquals("9.1", tune("channel nine point one"))
        assertEquals("9.1", tune("please turn to channel 9-1"))
        assertEquals("5.1", tune("go to TV5"))
        assertEquals("19.1", tune("put on PBS"))
        assertEquals("7.2", tune("channel 7.2"))
    }

    @Test fun find() {
        assertEquals(Command.Search("ncis"), p.parse("find NCIS"))
        assertEquals(Command.Search("the middle"), p.parse("find The Middle"))
        assertEquals(Command.Search("silverado"), p.parse("find Silverado"))
        assertEquals(Command.Search("ncis", onRoku = true), p.parse("Find NCIS on Roku"))
        assertEquals(Command.SearchRokuAgain, p.parse("try the Roku"))
        assertEquals(Command.Search("ncis", onRoku = true), p.parse("search Roku for NCIS"))
        assertEquals(Command.SearchRokuAgain, p.parse("search Roku instead"))
        assertEquals(Command.Search("the price is right"), p.parse("search for the price is right"))
        assertEquals(Command.Go("paramount plus"), p.parse("open Paramount+"))
        assertEquals(Command.Go("paramount plus"), p.parse("put on paramount plus"))
    }

    @Test fun listings() {
        assertTrue(p.parse("Is there local news on now?") is Command.Listings)
        assertTrue(p.parse("what's on right now") is Command.Listings)
    }

    @Test fun volumeAndPower() {
        assertEquals(Command.Volume(true, 3), p.parse("volume up"))
        assertEquals(Command.Volume(true, 5), p.parse("turn it up by five"))
        assertEquals(Command.Volume(false, 1), p.parse("a little quieter"))
        assertEquals(Command.Volume(false, 3), p.parse("it's too loud"))
        assertEquals(Command.Mute, p.parse("mute"))
        assertEquals(Command.Power(false), p.parse("turn off the TV"))
        assertEquals(Command.Power(true), p.parse("turn the TV on"))
    }

    @Test fun navAndInputs() {
        assertEquals(Command.ChannelStep(true), p.parse("channel up"))
        assertEquals(Command.ChannelStep(false), p.parse("Channel down."))
        assertEquals(Command.PreviousChannel, p.parse("last channel"))
        assertEquals(Command.Key(Nav.PAUSE), p.parse("Pause"))
        assertEquals(Command.Key(Nav.DOWN, 3), p.parse("down 3 times"))
        assertEquals(Command.Key(Nav.LEFT, 2), p.parse("left twice"))
        assertEquals(Command.Key(Nav.OK), p.parse("select"))
        assertEquals(Command.Key(Nav.CAPTIONS), p.parse("turn on captions"))
        assertEquals(Command.SwitchInput(Source.ROKU), p.parse("switch to the Roku"))
        assertEquals(Command.SwitchInput(Source.ANTENNA), p.parse("go to live TV"))
        assertTrue(p.parse("blah blah") is Command.Unknown)
        assertEquals(Command.Confirm, p.parse("Yes please"))
        assertEquals(Command.HideChannel(null), p.parse("I don't get that channel"))
        assertEquals(Command.HideChannel("14.1"), p.parse("we don't get channel 14.1"))
        assertEquals(Command.HideChannel("14.1"), p.parse("skip channel fourteen point one"))
        assertEquals(Command.HideChannel(null), p.parse("that channel doesn't work"))
        assertEquals(Command.Key(Nav.OK), p.parse("OK"))
    }
}
