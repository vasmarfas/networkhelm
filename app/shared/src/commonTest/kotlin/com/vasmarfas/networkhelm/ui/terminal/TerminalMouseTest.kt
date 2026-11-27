package com.vasmarfas.networkhelm.ui.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalMouseTest {
    private fun TerminalMouse.feed(text: String) = text.encodeToByteArray().let { read(it, it.size) }

    @Test
    fun htopAsksForTheMouseInTheSgrFormAndGetsAClickThere() {
        val mouse = TerminalMouse()
        assertFalse(mouse.wanted)
        mouse.feed("\u001b[?1049h\u001b[22;0;0t\u001b[?1h\u001b=\u001b[?1000h\u001b[?1006h  PID USER")
        assertTrue(mouse.wanted)
        assertEquals("\u001b[<0;12;3M\u001b[<0;12;3m", mouse.click(12, 3).decodeToString())
        mouse.feed("\u001b[?1006l\u001b[?1000l\u001b[?1049l")
        assertFalse(mouse.wanted)
    }

    @Test
    fun aSequenceCutBetweenTwoReadsCountsOnceItEnds() {
        val mouse = TerminalMouse()
        mouse.feed("top\u001b[?10")
        assertFalse(mouse.wanted)
        mouse.feed("02;1006h")
        assertTrue(mouse.wanted)
        assertEquals("\u001b[<0;1;1M\u001b[<0;1;1m", mouse.click(1, 1).decodeToString())
    }

    @Test
    fun withoutSgrTheClickGoesInTheOldFormOfOneBytePerNumber() {
        val mouse = TerminalMouse()
        mouse.feed("\u001b[?1000h")
        assertContentEquals(byteArrayOf(0x1b, 0x5b, 0x4d, 32, 42, 37, 0x1b, 0x5b, 0x4d, 35, 42, 37), mouse.click(10, 5))
        assertEquals(255.toByte(), mouse.click(400, 300)[4])
    }

    @Test
    fun otherPrivateModesLeaveTheMouseAlone() {
        val mouse = TerminalMouse()
        mouse.feed("\u001b[?25l\u001b[?2004h\u001b[?1049h")
        assertFalse(mouse.wanted)
        assertEquals("\u001b[A", mouse.cursorKey('A'))
    }

    @Test
    fun theCursorKeysFollowTheModeOfTheProgram() {
        val modes = TerminalMouse()
        assertEquals(listOf("\u001b[A", "\u001b[B", "\u001b[C", "\u001b[D", "\u001b[H", "\u001b[F"), "ABCDHF".map(modes::cursorKey))
        // mc 4.8.29 on Debian 12 as it starts: ESC > of the keypad after ESC [ ? 1 h leaves the cursor keys set
        modes.feed("\u001b[?1049h\u001b[?1049l\u001b[?1002h\u001b[?1006h\u001b[?2004h\u001b[?1049h\u001b[?1h\u001b=\u001b[?1h\u001b=\u001b>\u001b>\u001b>\u001b>")
        assertTrue(modes.cursorKeys)
        assertEquals(listOf("\u001bOA", "\u001bOB", "\u001bOC", "\u001bOD", "\u001bOH", "\u001bOF"), "ABCDHF".map(modes::cursorKey))
        // and as it ends
        modes.feed("\u001b[?2004l\u001b[?1006l\u001b[?1002l\u001b[?1l\u001b>\u001b[?1049l")
        assertEquals("\u001b[B", modes.cursorKey('B'))
        modes.read("\u001b[?1;")
        modes.read("1000h")
        assertEquals("\u001bOB", modes.cursorKey('B'))
        assertTrue(modes.wanted)
    }
}
