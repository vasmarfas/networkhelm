package com.vasmarfas.networkhelm.ui.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import com.vasmarfas.networkhelm.FakeClipboard
import com.vasmarfas.networkhelm.ui.kit.readText
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ViewerTest {

    private val text = "first line\nsecond line\nthird line"

    private fun SkikoComposeUiTest.opened(clip: FakeClipboard = FakeClipboard()): EditorControls {
        Locale.setDefault(Locale.ENGLISH)
        val controls = EditorControls()
        setContent { CompositionLocalProvider(LocalClipboard provides clip) { NetworkHelmTheme { LineViewer(text, Language.Plain, controls) } } }
        onNodeWithText("second line").performClick()
        return controls
    }

    private fun SkikoComposeUiTest.ctrl(key: Key) {
        onNodeWithTag("viewer").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(key) } }
        waitForIdle()
    }

    @Test
    fun theLinesAreCopiedAndSelectedWhileTheyHoldTheKeyboard() = runDesktopComposeUiTest(900, 500) {
        val clip = FakeClipboard()
        opened(clip)
        ctrl(Key.C)
        waitUntil(timeoutMillis = 5_000) { runBlocking { clip.readText() } == "second line" }
        ctrl(Key.A)
        ctrl(Key.C)
        waitUntil(timeoutMillis = 5_000) { runBlocking { clip.readText() } == text }
    }

    @Test
    fun theFieldOfTheFindBarCopiesAndSelectsItsOwnText() = runDesktopComposeUiTest(900, 500) {
        val clip = FakeClipboard()
        val controls = opened(clip)
        ctrl(Key.F)
        onNodeWithTag("find-query").performTextInput("line")
        ctrl(Key.A)
        ctrl(Key.C)
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertEquals(TextRange(0, 4), controls.find.query.selection)
        assertEquals("line", runBlocking { clip.readText() })
    }

    @Test
    fun altGrWithALetterIsNotAShortcut() = runDesktopComposeUiTest(900, 500) {
        val controls = opened()
        onNodeWithTag("viewer").performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltRight) { pressKey(Key.F) } } }
        waitForIdle()
        assertFalse(controls.find.open)
        ctrl(Key.F)
        assertTrue(controls.find.open)
    }
}
