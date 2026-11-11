package com.vasmarfas.networkhelm.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledMenu
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.session.SshHostSession
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.openTcp
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.terminal.LocalTerminal
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TerminalTest {

    private class Console : ShellChannel {
        var closed = false
        val written = StringBuilder()
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = awaitCancellation()
        override suspend fun write(bytes: ByteArray) {
            written.append(bytes.decodeToString())
        }
        override fun resize(columns: Int, rows: Int) = Unit
        override fun close() {
            closed = true
        }
    }

    private class Ssh : SshSession {
        val consoles = mutableListOf<Pair<String, Console>>()
        override suspend fun exec(command: String): ExecResult = error("no exec")
        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = Console().also { consoles += term to it }
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    private class SocketSsh(private val port: Int) : SshSession {
        override suspend fun exec(command: String): ExecResult = error("no exec")
        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel {
            val stream = openTcp("127.0.0.1", port)
            return object : ShellChannel {
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = stream.read(buffer, offset, length)
                override suspend fun write(bytes: ByteArray) = stream.write(bytes)
                override fun resize(columns: Int, rows: Int) = Unit
                override fun close() = stream.close()
            }
        }
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    // reads as the terminal of Android does, catching nothing
    @Composable
    private fun Reading(shell: ShellChannel, modifier: Modifier) {
        var shown by remember(shell) { mutableStateOf("") }
        LaunchedEffect(shell) {
            val buffer = ByteArray(8192)
            while (true) {
                val read = shell.read(buffer, 0, buffer.size)
                if (read < 0) break
                shown += buffer.decodeToString(0, read)
            }
        }
        Text(shown, modifier)
    }

    private fun tab(ssh: SshSession?, schemas: Map<String, MenuSchema> = emptyMap()): RouterTab {
        val storage = MemoryStorage()
        return RouterTab(FakeRouter(ssh = ssh, schemas = schemas), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
    }

    private val tools = mapOf(
        "" to MenuSchema(MenuPath.Root, NodeKind.Directory, listOf("tool"), emptyList(), emptyList()),
        "tool" to MenuSchema(MenuPath.parse("tool"), NodeKind.Directory, listOf("torch"), emptyList(), emptyList()),
        "tool/torch" to MenuSchema(MenuPath.parse("tool/torch"), NodeKind.List, emptyList(), emptyList(), emptyList()),
    )

    @Test
    fun everyNewTerminalIsAConsoleOfItsOwnThatEndsWithItsWindow() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val ssh = Ssh()
        val tab = tab(ssh)
        setContent {
            NetworkHelmTheme(dark = true) {
                CompositionLocalProvider(LocalTerminal provides { _, modifier -> Text("console", modifier) }) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("New Terminal")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("New Terminal").performClick()
        onNodeWithText("New Terminal").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("console")).fetchSemanticsNodes().size == 2 }
        assertEquals(listOf("xterm-256color", "xterm-256color"), ssh.consoles.map { it.first })
        assertEquals(listOf("Terminal", "Terminal"), tab.workspace.windows.map { it.title })
        tab.workspace.close(tab.workspace.windows.last())
        waitUntil(timeoutMillis = 5_000) { ssh.consoles.last().second.closed }
        assertFalse(ssh.consoles.first().second.closed, "the other console goes on")
    }

    @Test
    fun aConsoleWhoseSocketDropsSaysSoInItsWindowAndDoesNotEndTheApp() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val escaped = CopyOnWriteArrayList<Throwable>()
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> escaped += e }
        try {
            ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
                val tab = tab(SocketSsh(server.localPort))
                setContent {
                    NetworkHelmTheme(dark = true) {
                        CompositionLocalProvider(LocalTerminal provides { shell, modifier -> Reading(shell, modifier) }) { MainScreen(tab, onDisconnect = {}) }
                    }
                }
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("New Terminal")).fetchSemanticsNodes().isNotEmpty() }
                val first = CompletableFuture.supplyAsync { server.accept() }
                onNodeWithText("New Terminal").performClick()
                waitUntil(timeoutMillis = 5_000) { first.isDone }
                first.get().getOutputStream().write("[rd-test@MikroTik] > ".encodeToByteArray())
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("MikroTik", substring = true)).fetchSemanticsNodes().isNotEmpty() }
                first.get().apply { setSoLinger(true, 0) }.close()
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("The connection to the router is closed")).fetchSemanticsNodes().isNotEmpty() }
                val second = CompletableFuture.supplyAsync { server.accept() }
                onNodeWithTag("terminal-reopen").performClick()
                waitUntil(timeoutMillis = 5_000) { second.isDone }
                second.get().close()
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
        assertEquals(emptyList(), escaped.toList())
    }

    @Test
    fun aConsoleOverTheConnectionOfTheSessionThatCannotOpenTellsTheLostConnectionAndNotSsh() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val tab = tab(object : SshSession by SocketSsh(closed) {
            override val canForward = false
        })
        setContent {
            NetworkHelmTheme(dark = true) {
                CompositionLocalProvider(LocalTerminal provides { shell, modifier -> Reading(shell, modifier) }) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("New Terminal")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("New Terminal").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Could not open the console", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Could not open the console: The connection to the router is lost, reconnecting…").assertExists()
    }

    @Test
    fun theTelnetWindowAsksForAnAddressAndRunsTheTelnetClientOfTheRouterInAConsole() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val ssh = Ssh()
        val tab = tab(ssh)
        setContent {
            NetworkHelmTheme(dark = true) {
                CompositionLocalProvider(LocalTerminal provides { _, modifier -> Text("console", modifier) }) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        waitUntil(timeoutMillis = 5_000) { tab.menu != null }
        tab.open(MenuEntry("Telnet", window = "telnet"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("telnet-address")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("telnet-connect").assertIsNotEnabled()
        onNodeWithTag("telnet-address").performTextInput("192.0.2.9; /system reboot")
        onNodeWithTag("telnet-connect").assertIsNotEnabled()
        onNodeWithTag("telnet-address").performTextReplacement("192.0.2.9")
        onNodeWithTag("telnet-connect").performClick()
        waitUntil(timeoutMillis = 5_000) { ssh.consoles.size == 1 }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("console")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("/system telnet 192.0.2.9\r", ssh.consoles.single().second.written.toString())
    }

    @Test
    fun telnetIsInTheToolsMenuOnlyWhereARouterCanOpenAConsole() {
        fun entries(tab: RouterTab) = runBlocking {
            tab.loadMenu(bundledMenu())
            tab.menu.orEmpty().first { it.title == "Tools" }.items.map { it.title }
        }
        assertEquals(listOf("Telnet", "Torch"), entries(tab(Ssh(), tools)))
        assertEquals(listOf("Torch"), entries(tab(null, tools)))
    }

    @Test
    fun aHostOverSshAloneHasTheConsoleAndTheTunnels() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val ssh = Ssh()
        val storage = MemoryStorage()
        val device = DeviceInfo(Platform.Ssh, "rd-test-host", "Linux 6.6.86", "x86_64", "", emptySet(), emptySet())
        val tab = RouterTab(SshHostSession(ssh, device), "192.0.2.50", bundledPacks().values, storage, TableLayouts(storage))
        setContent {
            NetworkHelmTheme(dark = true) {
                CompositionLocalProvider(LocalTerminal provides { _, modifier -> Text("console", modifier) }) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("New Terminal")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("Files", "New Terminal", "SSH Tunnels"), tab.menu?.map { it.title })
        onNodeWithText("New Terminal").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("console")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("xterm-256color"), ssh.consoles.map { it.first })
    }

    @Test
    fun withoutSshOrATerminalThereIsNoNewTerminal() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val noSsh = tab(null)
        val noTerminal = tab(Ssh())
        setContent {
            NetworkHelmTheme(dark = true) {
                CompositionLocalProvider(LocalTerminal provides { _, modifier -> Text("console", modifier) }) { MainScreen(noSsh, onDisconnect = {}) }
                MainScreen(noTerminal, onDisconnect = {})
            }
        }
        waitUntil(timeoutMillis = 5_000) { noSsh.menu != null && noTerminal.menu != null }
        waitForIdle()
        assertTrue(onAllNodes(hasText("New Terminal")).fetchSemanticsNodes().isEmpty())
    }
}
