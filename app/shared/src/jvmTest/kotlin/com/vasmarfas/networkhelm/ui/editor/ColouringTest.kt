package com.vasmarfas.networkhelm.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import com.vasmarfas.networkhelm.ui.theme.DarkPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalFoundationApi::class)
class ColouringTest {

    private fun TextFieldBuffer.colour(language: Language, matches: List<IntRange> = emptyList(), current: Int = 0) {
        with(Colouring(language, DarkPalette, matches, current)) { transformOutput() }
    }

    private fun TextFieldBuffer.painted() = getSpanStyles(TextRange(0, length)).filter { it.spanStyle.background != Color.Unspecified }.map { it.textRange }

    @Test
    fun aMatchPastTheEndOfTheTextIsLeftOut() {
        TextFieldState("to wan").edit {
            colour(Language.Plain, listOf(3..5, 40..42))
            assertEquals(listOf(TextRange(3, 6)), painted())
        }
        TextFieldState("").edit {
            colour(Language.Plain, listOf(3..5))
            assertEquals(0, changes.changeCount)
        }
    }

    @Test
    fun aTextOfAnyNumberOfSpansIsOneChangeOfTheBuffer() {
        TextFieldState("add address=192.0.2.1 list=blocked\n".repeat(200)).edit {
            colour(Language.RouterOs)
            val spans = getSpanStyles(TextRange(0, length)).size
            assertTrue(spans > 600, "$spans spans")
            assertEquals(1, changes.changeCount)
        }
    }

    @Test
    fun aPlainTextWithoutMatchesIsLeftAsItIs() {
        TextFieldState("no colours here").edit {
            colour(Language.Plain)
            assertEquals(0, changes.changeCount)
        }
    }

    @Test
    fun aTextOfMoreSpansThanItsLengthAllowsStaysPlain() {
        TextFieldState("1 ".repeat(STYLES_MOST / 2)).edit {
            colour(Language.Json)
            assertEquals(STYLES_MOST / 2, getSpanStyles(TextRange(0, length)).count { it.spanStyle.color != Color.Unspecified })
        }
        TextFieldState("1 ".repeat(STYLES_MOST)).edit {
            colour(Language.Json)
            assertEquals(0, changes.changeCount)
        }
        TextFieldState("1" + " ".repeat(STYLES_MOST * CHARS_A_STYLE)).edit {
            colour(Language.Json)
            assertEquals(0, changes.changeCount)
        }
    }

    @Test
    fun onlyTheMatchesAroundTheCurrentOneArePainted() {
        for (text in listOf("wan ".repeat(5_000), ("wan " + "z".repeat(36)).repeat(5_000))) {
            val matches = findAll(text, FindOptions("wan")).ranges
            assertEquals(5_000, matches.size)
            for (current in listOf(0, 2_500, 4_999)) {
                TextFieldState(text).edit {
                    colour(Language.Plain, matches, current)
                    val painted = painted()
                    assertTrue(painted.size in MATCHES_LEAST..STYLES_MOST, "${painted.size} matches are painted in ${text.length} characters")
                    assertTrue(TextRange(matches[current].first, matches[current].last + 1) in painted, "match $current is not among the painted")
                }
            }
        }
    }
}
