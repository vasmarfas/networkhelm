package com.vasmarfas.networkhelm.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import com.vasmarfas.networkhelm.ui.workspace.Workspace
import com.vasmarfas.networkhelm.ui.workspace.WorkspaceState
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TerminalKeyboardTest {
    private class Typed : ShellChannel {
        val text = StringBuffer()
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = awaitCancellation()
        override suspend fun write(bytes: ByteArray) {
            text.append(bytes.decodeToString())
        }
        override fun resize(columns: Int, rows: Int) = Unit
        override fun close() = Unit
    }

    // the keys reach the console on a thread of its own
    private fun Typed.got(wanted: String): Boolean {
        val end = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < end) {
            if (wanted in text) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun ComposeUiTest.console(shell: Typed) {
        val workspace = WorkspaceState()
        workspace.open("Terminal", DpOffset(20.dp, 20.dp), DpSize(600.dp, 400.dp))
        workspace.open("Other", DpOffset(640.dp, 20.dp), DpSize(300.dp, 300.dp))
        setContent {
            NetworkHelmTheme(dark = true) {
                Workspace(workspace) { window -> if (window.title == "Terminal") BossTerminal(shell, Modifier.fillMaxSize().testTag("console")) }
            }
        }
        waitForIdle()
    }

    @Test
    fun aNewConsoleTakesTheKeyboard() = runDesktopComposeUiTest(1000, 700) {
        val shell = Typed()
        console(shell)
        Thread.sleep(1500)
        onRoot().performKeyInput { pressKey(Key.A) }
        assertTrue(shell.got("a"), "typed: ${shell.text}")
    }

    @Test
    fun theFirstClickIntoAConsoleBelowAnotherWindowGivesItTheKeyboard() = runDesktopComposeUiTest(1000, 700) {
        val shell = Typed()
        console(shell)
        onNodeWithText("Other").performMouseInput { click() }
        waitForIdle()
        onNodeWithTag("console").performMouseInput { click(center) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.B) }
        assertTrue(shell.got("b"), "typed: ${shell.text}")
    }

    @Test
    fun aPressOnTheTitleLeavesTheKeyboardWithTheConsole() = runDesktopComposeUiTest(1000, 700) {
        val shell = Typed()
        console(shell)
        onNodeWithTag("console").performMouseInput { click(center) }
        waitForIdle()
        onNodeWithText("Terminal").performMouseInput { click() }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.C) }
        assertTrue(shell.got("c"), "typed: ${shell.text}")
    }
}
