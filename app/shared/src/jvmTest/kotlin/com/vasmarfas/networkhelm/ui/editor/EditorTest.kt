package com.vasmarfas.networkhelm.ui.editor

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.awaitCancellation
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EditorTest {

    private fun editor(text: String) = EditorState(checkNotNull(decodeText(text.encodeToByteArray())), languageOf("note.txt"))

    private fun SkikoComposeUiTest.opened(state: EditorState, controls: EditorControls = EditorControls(), onSave: () -> Unit = {}) {
        Locale.setDefault(Locale.ENGLISH)
        setContent { NetworkHelmTheme { TextEditor(state, controls, readOnly = false, onSave = onSave) } }
        onNodeWithTag("editor-text").requestFocus()
    }

    private fun SkikoComposeUiTest.ctrl(key: Key) {
        onNodeWithTag("editor").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(key) } }
        waitForIdle()
    }

    @Test
    fun aTextDeletedWhileItsMatchesArePaintedLeavesTheEditorWorking() = runDesktopComposeUiTest(900, 500) {
        val state = editor("first line\nthe last word is wan")
        opened(state)
        ctrl(Key.F)
        onNodeWithTag("find-query").performTextInput("wan")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("1 of 1")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("editor-text").requestFocus()
        ctrl(Key.A)
        onNodeWithTag("editor").performKeyInput { pressKey(Key.Delete) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("No matches")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("", state.snapshot())
    }

    @Test
    fun altGrWithALetterIsNotAShortcut() = runDesktopComposeUiTest(900, 500) {
        var saves = 0
        val controls = EditorControls()
        opened(editor("ether1"), controls) { saves++ }
        onNodeWithTag("editor").performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                withKeyDown(Key.AltRight) {
                    pressKey(Key.S)
                    pressKey(Key.F)
                }
            }
        }
        waitForIdle()
        assertEquals(0, saves)
        assertFalse(controls.find.open)
        ctrl(Key.S)
        ctrl(Key.F)
        assertEquals(1, saves)
        assertTrue(controls.find.open)
    }

    @Test
    fun tabInAFieldOfTheFindBarStaysOutOfTheText() = runDesktopComposeUiTest(900, 500) {
        val state = editor("ether1")
        opened(state)
        onNodeWithTag("editor").performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        assertEquals("\tether1", state.snapshot())
        ctrl(Key.H)
        onNodeWithTag("find-query").performTextInput("eth")
        onNodeWithTag("editor").performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        assertEquals("\tether1", state.snapshot())
        ctrl(Key.G)
        onNodeWithTag("goto-line").requestFocus()
        onNodeWithTag("editor").performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        assertEquals("\tether1", state.snapshot())
    }

    @Test
    fun replaceGoesOnToTheNextMatchWhenTheNewTextHoldsTheOldOne() = runDesktopComposeUiTest(900, 500) {
        val state = editor("ether1 ether1 ether1")
        opened(state)
        ctrl(Key.H)
        onNodeWithTag("find-query").performTextInput("ether1")
        onNodeWithTag("find-with").performTextInput("ether1-wan")
        repeat(3) { onNodeWithText("Replace").performClick() }
        waitForIdle()
        assertEquals("ether1-wan ether1-wan ether1-wan", state.snapshot())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun theKeyboardIsToldNotToCorrectTheText() = runDesktopComposeUiTest(900, 500) {
        val state = editor("ether1")
        val controls = EditorControls()
        var corrects: Boolean? = null
        setContent {
            InterceptPlatformTextInput({ request, _ ->
                corrects = request.imeOptions.autoCorrect
                awaitCancellation()
            }) { NetworkHelmTheme { TextEditor(state, controls, readOnly = false) } }
        }
        onNodeWithTag("editor-text").performClick()
        waitForIdle()
        assertEquals(false, corrects)
    }
}
