package com.vasmarfas.networkhelm.ui.terminal

import kotlin.concurrent.Volatile

// whether the program on the screen asked for the mouse, as htop and mc do, read from what the host writes: DECSET and DECRST of
// the modes 1000, 1002 and 1003 turn the reports on and off, 1006 picks the SGR form; a terminal without mouse of its own
// sends a tap as a click of the left button. Mode 1 is the cursor keys of the program: with it set, mc and htop take an arrow
// only as ESC O A and ignore ESC [ A. ESC = and ESC > of the keypad leave it as it is
class TerminalMouse {
    private val tracking = mutableSetOf<Int>()
    private var sgr = false
    private var rest = ""

    @Volatile
    var cursorKeys = false
        private set

    val wanted get() = tracking.isNotEmpty()

    // A up, B down, C right, D left, H home, F end
    fun cursorKey(final: Char): String = if (cursorKeys) "\u001bO$final" else "\u001b[$final"

    fun read(bytes: ByteArray, count: Int) = read(bytes.decodeToString(0, count))

    fun read(written: String) {
        val text = rest + written
        var end = 0
        for (match in MODE.findAll(text)) {
            val on = match.groupValues[2] == "h"
            for (mode in match.groupValues[1].split(';').mapNotNull { it.toIntOrNull() }) {
                when (mode) {
                    in TRACKING -> if (on) tracking += mode else tracking -= mode
                    SGR -> sgr = on
                    CURSOR_KEYS -> cursorKeys = on
                }
            }
            end = match.range.last + 1
        }
        // a sequence cut between two reads waits for its end; anything longer is no mode sequence
        val open = text.lastIndexOf('\u001b').takeIf { it >= end && text.length - it <= MAX_SEQUENCE }
        rest = if (open == null) "" else text.substring(open)
    }

    // column and row count from 1, as the reports do
    fun click(column: Int, row: Int): ByteArray = if (sgr) {
        "\u001b[<0;$column;${row}M\u001b[<0;$column;${row}m".encodeToByteArray()
    } else {
        // the old form carries each number in one byte past 32, so a cell beyond 223 cannot be told
        val x = (32 + column.coerceAtMost(223)).toByte()
        val y = (32 + row.coerceAtMost(223)).toByte()
        byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), 32, x, y, 0x1b, '['.code.toByte(), 'M'.code.toByte(), 35, x, y)
    }

    private companion object {
        val MODE = Regex("\u001b\\[\\?([0-9;]+)([hl])")
        val TRACKING = setOf(1000, 1002, 1003)
        const val SGR = 1006
        const val CURSOR_KEYS = 1
        const val MAX_SEQUENCE = 32
    }
}
