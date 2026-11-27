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
    // screen: what the host writes first, then it writes nothing more
    private class Typed(private val screen: String = "") : ShellChannel {
        val text = StringBuffer()
        private var shown = screen.isEmpty()
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (shown) awaitCancellation()
            shown = true
            val bytes = screen.encodeToByteArray()
            bytes.copyInto(buffer, offset)
            return bytes.size
        }
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

    // mc and htop set the cursor keys of the program and take an arrow only as ESC O B then
    @Test
    fun theArrowsGoAsTheProgramSetTheCursorKeys() = runDesktopComposeUiTest(1000, 700) {
        val shell = Typed("\u001b[?1049h\u001b[?1h\u001b=")
        console(shell)
        Thread.sleep(1500)
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        assertTrue(shell.got("\u001bOB"), "typed: ${shell.text.map { it.code }}")
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
