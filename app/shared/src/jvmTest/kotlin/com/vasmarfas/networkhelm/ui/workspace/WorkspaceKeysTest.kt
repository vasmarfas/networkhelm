package com.vasmarfas.networkhelm.ui.workspace

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WorkspaceKeysTest {

    private fun WorkspaceState.order() = windows.joinToString(" ") { it.title }

    @Test
    fun altSwitchesTheWindowsAndAKeyOfAltGrIsLeftToTheLayout() = runDesktopComposeUiTest(900, 600) {
        val state = WorkspaceState()
        for (title in listOf("a", "b")) state.open(title, DpOffset.Zero, DpSize(200.dp, 100.dp))
        setContent { NetworkHelmTheme { Workspace(state, Modifier.testTag("workspace")) { } } }
        onNodeWithTag("workspace").requestFocus()
        onNodeWithTag("workspace").performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltRight) { pressKey(Key.S) } } }
        waitForIdle()
        assertEquals("a b", state.order())
        onNodeWithTag("workspace").performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.S) } }
        waitForIdle()
        assertEquals("b a", state.order())
    }
}
