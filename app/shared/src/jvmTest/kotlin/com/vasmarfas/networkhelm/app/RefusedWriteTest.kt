package com.vasmarfas.networkhelm.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.ui.table.TableLayout
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.terminal.TerminalView
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RefusedWriteTest {

    private class Console : ShellChannel {
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = awaitCancellation()
        override suspend fun write(bytes: ByteArray) = Unit
        override fun resize(columns: Int, rows: Int) = Unit
        override fun close() = Unit
    }

    private class Ssh : SshSession {
        override suspend fun exec(command: String): ExecResult = error("no exec")
        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = Console()
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    @Test
    fun layoutsOfTablesThatCouldNotBeWrittenGoToTheFileWithTheNextChange() = runDesktopComposeUiTest(400, 300) {
        val storage = RefusingStorage("tables.json")
        val tables = TableLayouts(storage)
        setContent { LaunchedEffect(Unit) { tables.keep() } }
        runOnIdle { tables["ip/route"] = TableLayout(detail = true) }
        waitUntil(timeoutMillis = 5_000) { storage.refusals == 1 }
        waitForIdle()
        storage.refused = null
        runOnIdle { tables["ip/address"] = TableLayout() }
        waitUntil(timeoutMillis = 5_000) { runBlocking { storage.read("tables.json") }?.contains("ip/address") == true }
        assertTrue("ip/route" in runBlocking { storage.read("tables.json") }.orEmpty())
    }

    @Test
    fun thePlaceOfTheWindowThatCouldNotBeWrittenIsWrittenWithItsNextMove() = runDesktopComposeUiTest(400, 300) {
        val storage = RefusingStorage("window.json")
        var frame: WindowState? = null
        setContent { frame = rememberFrameState(storage) }
        val state = checkNotNull(frame)
        runOnIdle { state.position = WindowPosition(120.dp, 80.dp) }
        waitUntil(timeoutMillis = 5_000) { storage.refusals == 1 }
        waitForIdle()
        storage.refused = null
        runOnIdle { state.position = WindowPosition(140.dp, 80.dp) }
        waitUntil(timeoutMillis = 5_000) { runBlocking { storage.read("window.json") }?.contains("140.0") == true }
    }

    @Test
    fun snippetsThatCouldNotBeWrittenKeepTheirEditorOpenAndTellWhyInTheWordsOfTheSystem() = runDesktopComposeUiTest(1000, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val snippets = Snippets(RefusingStorage(Snippets.FILE))
        setContent {
            NetworkHelmTheme(dark = true) {
                CompositionLocalProvider(LocalSnippets provides snippets) {
                    TerminalView(Ssh()) { _, modifier -> Text("console", modifier) }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("snippets")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("snippets").performClick()
        onNodeWithText("Edit snippets…").performClick()
        onNodeWithTag("snippet-add").performClick()
        onNodeWithTag("snippet-name").performTextInput("Routes")
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("snippets.json is used by another process")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("snippet-add").assertExists()
        assertTrue(snippets.all.value.isEmpty())
    }
}
