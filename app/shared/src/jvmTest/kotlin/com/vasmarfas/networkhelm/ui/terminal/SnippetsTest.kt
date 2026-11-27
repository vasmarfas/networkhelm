package com.vasmarfas.networkhelm.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.app.LocalSnippets
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.Snippet
import com.vasmarfas.networkhelm.app.Snippets
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SnippetsTest {

    // screen: what the host writes first, then it writes nothing more
    private class Console(private val screen: String = "") : ShellChannel {
        val written = mutableListOf<String>()

        @Volatile
        var shown = screen.isEmpty()

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (shown) awaitCancellation()
            val bytes = screen.encodeToByteArray()
            bytes.copyInto(buffer, offset)
            shown = true
            return bytes.size
        }
        override suspend fun write(bytes: ByteArray) {
            written += bytes.decodeToString()
        }
        override fun resize(columns: Int, rows: Int) = Unit
        override fun close() = Unit
    }

    private class Ssh(private val console: Console) : SshSession {
        override suspend fun exec(command: String): ExecResult = error("no exec")
        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = console
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun SkikoComposeUiTest.showTerminal(
        console: Console,
        compact: Boolean,
        terminal: @Composable (ShellChannel, Modifier) -> Unit = { _, modifier -> Text("console", modifier) },
    ) {
        val snippets = Snippets(MemoryStorage())
        setContent {
            NetworkHelmTheme(dark = true) {
                CompositionLocalProvider(LocalSnippets provides snippets, LocalCompact provides compact) {
                    TerminalView(Ssh(console), remember { Consoles() }, 1L, terminal = terminal)
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("snippets")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theEditorKeepsWhatAnotherCopyOfTheAppAddedAndRemovedMeanwhile() = runBlocking {
        val storage = MemoryStorage()
        val mine = Snippets(storage)
        val other = Snippets(storage)
        mine.save(emptyList(), listOf(Snippet("a", "1"), Snippet("b", "2")))
        other.load()
        val opened = mine.all.value
        other.save(other.all.value, listOf(Snippet("a", "1"), Snippet("c", "3")))
        mine.save(opened, listOf(Snippet("a", "1 changed"), Snippet("b", "2"), Snippet("d", "4")))
        assertEquals(listOf(Snippet("a", "1 changed"), Snippet("d", "4"), Snippet("c", "3")), mine.all.value)
    }

    @Test
    fun aSnippetIsTypedIntoTheConsoleAndTheEditorKeepsThem() = runDesktopComposeUiTest(1000, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val snippets = Snippets(storage)
        runBlocking { snippets.save(emptyList(), listOf(Snippet("Interfaces", "/interface print\n"))) }
        val console = Console()
        setContent {
            NetworkHelmTheme(dark = true) {
                CompositionLocalProvider(LocalSnippets provides snippets) {
                    TerminalView(Ssh(console), remember { Consoles() }, 1L) { _, modifier -> Text("console", modifier) }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("snippets")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("snippets").performClick()
        onNodeWithText("Interfaces").performClick()
        waitUntil(timeoutMillis = 5_000) { console.written.isNotEmpty() }
        assertEquals(listOf("/interface print\r"), console.written)

        onNodeWithTag("snippets").performClick()
        onNodeWithText("Edit snippets…").performClick()
        onNodeWithTag("snippet-add").performClick()
        onAllNodesWithTag("snippet-name")[1].performTextInput("Routes")
        onAllNodesWithTag("snippet-text")[1].performTextInput("/ip route print")
        picture("snippets")
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 5_000) { snippets.all.value.size == 2 }
        assertEquals(Snippet("Routes", "/ip route print"), snippets.all.value[1])
        assertTrue("Routes" in runBlocking { storage.read(Snippets.FILE) }.orEmpty())
    }

    @Test
    fun theKeysBarOfAPhoneTypesTabEscapeArrowsAndInterruptIntoTheConsole() = runDesktopComposeUiTest(400, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val console = Console()
        showTerminal(console, compact = true)
        picture("terminal-keys")
        val keys = listOf(
            Triple("key-tab", "Tab, complete the command", "\t"),
            Triple("key-esc", "Escape", "\u001b"),
            Triple("key-up", "Arrow up", "\u001b[A"),
            Triple("key-down", "Arrow down", "\u001b[B"),
            Triple("key-left", "Arrow left", "\u001b[D"),
            Triple("key-right", "Arrow right", "\u001b[C"),
            Triple("key-ctrl-c", "Ctrl+C, break the running command", "\u0003"),
        )
        for ((tag, description, _) in keys) onNodeWithTag(tag).assert(hasContentDescription(description))
        for ((tag, _, _) in keys) onNodeWithTag(tag).performClick()
        waitUntil(timeoutMillis = 5_000) { console.written.size == keys.size }
        assertEquals(keys.map { it.third }, console.written)
    }

    @Test
    fun theArrowsOfTheKeysBarFollowTheCursorKeysTheProgramSet() = runDesktopComposeUiTest(400, 700) {
        Locale.setDefault(Locale.ENGLISH)
        // the modes mc 4.8.29 sets as it starts: the mouse, the alternate screen, the cursor keys, and ESC > of the keypad after them
        val console = Console("\u001b[?1049h\u001b[?1049l\u001b[?1002h\u001b[?1006h\u001b[?2004h\u001b[?1049h\u001b[?1h\u001b=\u001b[?1h\u001b=\u001b>\u001b>\u001b>\u001b>")
        showTerminal(console, compact = true) { shell, modifier ->
            LaunchedEffect(shell) {
                val buffer = ByteArray(256)
                while (true) {
                    if (shell.read(buffer, 0, buffer.size) < 0) break
                }
            }
            Text("console", modifier)
        }
        waitUntil(timeoutMillis = 5_000) { console.shown }
        for (tag in listOf("key-up", "key-down", "key-left", "key-right")) onNodeWithTag(tag).performClick()
        waitUntil(timeoutMillis = 5_000) { console.written.size == 4 }
        assertEquals(listOf("\u001bOA", "\u001bOB", "\u001bOD", "\u001bOC"), console.written)
    }

    @Test
    fun aWideWindowHasNoKeysBar() = runDesktopComposeUiTest(1000, 700) {
        Locale.setDefault(Locale.ENGLISH)
        showTerminal(Console(), compact = false)
        assertTrue(onAllNodes(hasTestTag("key-tab")).fetchSemanticsNodes().isEmpty())
    }
}
