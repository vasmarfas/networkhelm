package com.vasmarfas.networkhelm.ui.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
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
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.ui.kit.HintHost
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

    // a console of a window is kept by Consoles, so its shell comes in a KeptShell
    private object NoSsh : SshSession {
        override suspend fun exec(command: String): ExecResult = error("no exec")
        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    // the program holds all of its screen in HintHost, a SelectionContainer among its layers
    private fun ComposeUiTest.console(shell: ShellChannel): WorkspaceState {
        val workspace = WorkspaceState()
        workspace.open("Terminal", DpOffset(20.dp, 20.dp), DpSize(600.dp, 400.dp))
        workspace.open("Other", DpOffset(640.dp, 20.dp), DpSize(300.dp, 300.dp))
        setContent {
            NetworkHelmTheme(dark = true) {
                HintHost { Workspace(workspace) { window -> if (window.title == "Terminal") BossTerminal(shell, Modifier.fillMaxSize().testTag("console")) } }
            }
        }
        waitForIdle()
        return workspace
    }

    @Test
    fun aNewConsoleTakesTheKeyboard() = runDesktopComposeUiTest(1000, 700) {
        val shell = Typed()
        console(shell)
        Thread.sleep(1500)
        onRoot().performKeyInput { pressKey(Key.A) }
        assertTrue(shell.got("a"), "typed: ${shell.text}")
    }

    // mc 4.8.29 sets the cursor keys as it starts and takes an arrow only as ESC O B then, while the ESC > it sends after them
    // turns the cursor keys of BossTerm back to ESC [ B
    @Test
    fun theArrowsGoAsTheProgramSetTheCursorKeys() = runDesktopComposeUiTest(1000, 700) {
        val shell = Typed("\u001b[?1049h\u001b[?1049l\u001b[?1002h\u001b[?1006h\u001b[?2004h\u001b[?1049h\u001b[?1h\u001b=\u001b[?1h\u001b=\u001b>\u001b>\u001b>\u001b>")
        console(KeptShell(shell, NoSsh))
        Thread.sleep(1500)
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        assertTrue(shell.got("\u001bOB"), "typed: ${shell.text.map { it.code }}")
        onRoot().performKeyInput { pressKey(Key.MoveHome) }
        assertTrue(shell.got("\u001bOH"), "typed: ${shell.text.map { it.code }}")
        assertTrue("\u001b[B" !in shell.text, "typed: ${shell.text.map { it.code }}")
    }

    @Test
    fun aConsoleKeepsTheKeyboardWhenItsWindowIsResized() = runDesktopComposeUiTest(1000, 700) {
        val shell = Typed()
        val workspace = console(shell)
        Thread.sleep(1500)
        // the right edge of the window at 20, 20 of 600 by 400 dp, dragged to the left
        onRoot().performMouseInput {
            moveTo(Offset(617f, 220f))
            press()
            moveBy(Offset(-60f, 0f))
            moveBy(Offset(-60f, 0f))
            release()
        }
        waitForIdle()
        Thread.sleep(1500)
        assertTrue(workspace.windows.first { it.title == "Terminal" }.size.width < 600.dp, "the window is ${workspace.windows.first().size}")
        onRoot().performKeyInput { pressKey(Key.D) }
        assertTrue(shell.got("d"), "typed: ${shell.text}")
    }

    @Test
    fun aConsoleKeepsTheKeyboardThroughTheCornerAndTheButtonsOfItsWindow() = runDesktopComposeUiTest(1000, 700) {
        val shell = Typed()
        val workspace = console(shell)
        val window = workspace.windows.first { it.title == "Terminal" }
        Thread.sleep(1500)
        onRoot().performMouseInput {
            moveTo(Offset(615f, 415f))
            press()
            repeat(6) { moveBy(Offset(-20f, -10f)) }
            release()
        }
        waitForIdle()
        Thread.sleep(1000)
        assertTrue(window.size.height < 400.dp, "the window is ${window.size}")
        onRoot().performKeyInput { pressKey(Key.E) }
        assertTrue(shell.got("e"), "typed after the corner: ${shell.text}")
        onRoot().performMouseInput { doubleClick(Offset(200f, 32f)) }
        waitForIdle()
        Thread.sleep(1000)
        assertTrue(window.maximized, "a double click on the title maximizes")
        onRoot().performKeyInput { pressKey(Key.F) }
        assertTrue(shell.got("f"), "typed after maximize: ${shell.text}")
    }

    // a mouse takes an edge with a press that does not move, or moves a few pixels before the drag starts, where the
    // SelectionContainer of the program used to start a selection and take the keyboard
    @Test
    fun aConsoleKeepsTheKeyboardThroughAPressOnTheEdgeOfItsWindow() = runDesktopComposeUiTest(1000, 700) {
        val shell = Typed()
        val workspace = console(shell)
        val window = workspace.windows.first { it.title == "Terminal" }
        Thread.sleep(1500)
        onRoot().performMouseInput {
            moveTo(Offset(617f, 220f))
            press()
            release()
        }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.K) }
        assertTrue(shell.got("k"), "typed after a press on the edge: ${shell.text}")
        onRoot().performMouseInput {
            moveTo(Offset(617f, 220f))
            press()
            repeat(5) { moveBy(Offset(-2f, 0f)) }
            repeat(4) { moveBy(Offset(-20f, 0f)) }
            release()
        }
        waitForIdle()
        Thread.sleep(1000)
        assertTrue(window.size.width < 600.dp, "the window is ${window.size}")
        onRoot().performKeyInput { pressKey(Key.L) }
        assertTrue(shell.got("l"), "typed after a slow drag of the edge: ${shell.text}")
    }

    // the program swaps the layout of a computer for that of a phone as its window gets narrower than 600 dp or lower than 480, and
    // the console it keeps comes into the other layout
    @Test
    fun aConsoleKeepsTheKeyboardWhenTheLayoutSwaps() = runDesktopComposeUiTest(1000, 700) {
        val shell = Typed()
        val kept = KeptShell(shell, NoSsh)
        var phone by mutableStateOf(false)
        setContent {
            NetworkHelmTheme(dark = true) {
                if (phone) Column(Modifier.fillMaxSize()) { BossTerminal(kept, Modifier.fillMaxSize()) } else Row(Modifier.fillMaxSize()) { BossTerminal(kept, Modifier.fillMaxSize()) }
            }
        }
        Thread.sleep(1500)
        onRoot().performKeyInput { pressKey(Key.G) }
        assertTrue(shell.got("g"), "typed: ${shell.text}")
        phone = true
        waitForIdle()
        Thread.sleep(1500)
        onRoot().performKeyInput { pressKey(Key.H) }
        assertTrue(shell.got("h"), "typed in the layout of a phone: ${shell.text}")
        phone = false
        waitForIdle()
        Thread.sleep(1500)
        onRoot().performKeyInput { pressKey(Key.J) }
        assertTrue(shell.got("j"), "typed back in the layout of a computer: ${shell.text}")
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
