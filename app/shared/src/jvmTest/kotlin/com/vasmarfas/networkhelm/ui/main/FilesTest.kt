package com.vasmarfas.networkhelm.ui.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.App
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.Shell
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.session.SshHostSession
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.LazySsh
import com.vasmarfas.networkhelm.ssh.RemoteFile
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshReach
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustKind
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.ui.files.FileExchange
import com.vasmarfas.networkhelm.ui.files.LocalFolder
import com.vasmarfas.networkhelm.ui.files.LocalFile
import com.vasmarfas.networkhelm.ui.files.LocalFileExchange
import com.vasmarfas.networkhelm.ui.files.transferLimit
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import java.net.ConnectException
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope

@OptIn(ExperimentalTestApi::class)
class FilesTest {

    private class Sftp(private val folders: Map<String, List<RemoteFile>> = emptyMap()) : SftpClient {
        val written = mutableMapOf<String, String>()
        val renamed = mutableListOf<Pair<String, String>>()
        val made = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val listed = mutableListOf<String>()
        override suspend fun list(path: String): List<RemoteFile> {
            listed += path
            return folders[path].orEmpty()
        }
        val reads = mutableListOf<String>()
        override suspend fun read(path: String): ByteArray {
            reads += path
            return "contents of $path".encodeToByteArray()
        }
        override suspend fun write(path: String, bytes: ByteArray) {
            written[path] = bytes.decodeToString()
        }
        override suspend fun remove(path: String) {
            removed += path
        }
        override suspend fun rename(from: String, to: String) {
            renamed += from to to
        }
        override suspend fun mkdir(path: String) {
            made += path
        }
        override fun close() = Unit
    }

    private class Ssh(val sftp: Sftp, private val output: String = "", private val answer: SshReach = SshReach.Open) : SshSession {
        override suspend fun reach(fresh: Boolean) = answer
        override suspend fun exec(command: String) = ExecResult(output, "", 0)
        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp() = sftp
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    private class Exchange(private val accepted: Boolean = true) : FileExchange {
        val saved = mutableListOf<Pair<String, String>>()
        override suspend fun pick() = listOf(LocalFile("rd-test.auto.rsc") { ":log info rd-test".encodeToByteArray() })
        override suspend fun save(name: String, bytes: ByteArray): Boolean {
            saved += name to bytes.decodeToString()
            return accepted
        }
    }

    @Test
    fun filesGoUpAfterTheWarningAndComeDownAsTheyAre() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val sftp = Sftp()
        val exchange = Exchange()
        val files = listOf(
            Item("*1", mapOf("name" to "rc.local", "type" to ".local file", "size" to "127")),
            Item("*2", mapOf("name" to "skins", "type" to "directory")),
        )
        val resource = Item("", mapOf("free-hdd-space" to "37748736", "total-hdd-space" to "134217728"))
        val router = FakeRouter(mapOf("file" to files, "system/resource" to listOf(resource)), ssh = Ssh(sftp), fileTexts = mapOf("*1" to "contents of /rc.local"))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        tab.open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rc.local")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("92.0 MiB of 128.0 MiB used")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("via SFTP")).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Upload").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("as soon as the file arrives", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(emptyMap(), sftp.written.toMap(), "nothing goes before the answer")
        onNodeWithText("Send").performClick()
        waitUntil(timeoutMillis = 5_000) { sftp.written.isNotEmpty() }
        assertEquals(mapOf("/rd-test.auto.rsc" to ":log info rd-test"), sftp.written.toMap())

        onNodeWithText("rc.local").performClick()
        onNodeWithText("Download").performClick()
        waitUntil(timeoutMillis = 5_000) { exchange.saved.isNotEmpty() }
        assertEquals("rc.local" to "contents of /rc.local", exchange.saved.single())
        assertEquals(emptyList(), sftp.reads, "a small text needs no SFTP")

        onNodeWithText("Export").performClick()
        waitUntil(timeoutMillis = 5_000) { exchange.saved.size == 2 }
        assertEquals("rd-test.rsc", exchange.saved[1].first)
    }

    @Test
    fun cancellingTheSaveDialogDoesNotShowTheDownloadAsDone() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val exchange = Exchange(accepted = false)
        val files = listOf(Item("*1", mapOf("name" to "rc.local", "type" to ".local file", "size" to "8")))
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(Sftp()), fileTexts = mapOf("*1" to "original"))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        tab.open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rc.local")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("rc.local").performClick()
        onNodeWithTag("files-download").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Cancelled")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, exchange.saved.size)
        assertTrue(onAllNodes(hasText("8 B in", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aFileBeyondTheLimitOfATransferIsNeitherReadNorSent() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val sftp = Sftp()
        var read = false
        val exchange = object : FileExchange {
            override suspend fun pick() = listOf(LocalFile("rd-test.img", transferLimit * 2) { read = true; ByteArray(0) })
            override suspend fun save(name: String, bytes: ByteArray) = true
        }
        val files = listOf(Item("*1", mapOf("name" to "rd-test.tar", "type" to ".tar file", "size" to "${transferLimit * 2}")))
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(mapOf("file" to files), ssh = Ssh(sftp)), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        tab.open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test.tar")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Upload")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("rd-test.tar").performClick()
        onNodeWithTag("files-download").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test.tar is", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Upload").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test.img is", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(emptyList(), sftp.reads)
        assertEquals(emptyMap(), sftp.written.toMap())
        assertTrue(!read, "the file of the computer is not read either")
    }

    @Test
    fun aFileWithTheNameOfOneOnTheRouterGoesUpOnlyAfterTheQuestion() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val sftp = Sftp()
        val exchange = object : FileExchange {
            override suspend fun pick() = listOf(LocalFile("rc.local") { "rd-test".encodeToByteArray() })
            override suspend fun save(name: String, bytes: ByteArray) = false
        }
        val files = listOf(Item("*1", mapOf("name" to "rc.local", "type" to ".local file", "size" to "127")))
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(mapOf("file" to files), ssh = Ssh(sftp)), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        tab.open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rc.local")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Upload")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Upload").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("The router already has rc.local. Replace?")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(emptyMap(), sftp.written.toMap(), "nothing goes before the answer")
        onNodeWithText("Replace").performClick()
        waitUntil(timeoutMillis = 5_000) { sftp.written.isNotEmpty() }
        assertEquals(mapOf("/rc.local" to "rd-test"), sftp.written.toMap())
    }

    @Test
    fun aFolderOfAHostThatIsNotOpenIsListedBeforeAFileGoesIntoIt() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val sftp = Sftp(
            mapOf(
                "/home" to listOf(RemoteFile("rd-test", 0, true, 1_800_000_000), RemoteFile("shared", 0, true, 1_800_000_000)),
                "/home/shared" to listOf(RemoteFile("notes.txt", 12, false, 1_800_000_000)),
                "/tmp" to emptyList(),
            ),
        )
        val exchange = object : FileExchange {
            override suspend fun pick() = listOf(LocalFile("notes.txt") { "rd-test".encodeToByteArray() })
            override suspend fun save(name: String, bytes: ByteArray) = false
        }
        val device = DeviceInfo(Platform.Ssh, "rd-test-host", "", "", "", emptySet(), setOf("read", "write"))
        val storage = MemoryStorage()
        val tab = RouterTab(SshHostSession(Ssh(sftp, "/home/rd-test\n+0000\n"), device), "192.0.2.50", bundledPacks().values, storage, TableLayouts(storage))
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        tab.open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("home")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("home").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("shared")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("shared").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Upload")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("/home/shared" !in sftp.listed, "the folder is closed in the tree")
        onNodeWithText("Upload").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("The router already has notes.txt. Replace?")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(emptyMap(), sftp.written.toMap(), "nothing goes before the answer")
        onNodeWithText("Replace").performClick()
        waitUntil(timeoutMillis = 5_000) { sftp.written.isNotEmpty() }
        assertEquals(setOf("/home/shared/notes.txt"), sftp.written.keys)
    }

    @Test
    fun aClipboardHeldByAnotherProgramLeavesTheWindowStanding() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val held = object : Clipboard {
            override suspend fun getClipEntry(): ClipEntry? = null
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                throw IllegalStateException("cannot open system clipboard")
            }
        }
        val files = listOf(Item("*1", mapOf("name" to "rc.local", "type" to ".local file", "size" to "127")))
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(mapOf("file" to files), ssh = Ssh(Sftp())), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent {
            CompositionLocalProvider(LocalClipboard provides held) {
                NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        tab.open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rc.local")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("rc.local").performMouseInput { rightClick() }
        onNodeWithText("Copy path").performClick()
        waitForIdle()
        onNodeWithTag("tree").assertExists()
    }

    @Test
    fun withoutFtpFilesStayOnTheRouter() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(mapOf("file" to listOf(Item("*1", mapOf("name" to "rc.local", "type" to ".local file")))), ssh = Ssh(Sftp()), policies = setOf("read", "write"))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rc.local")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("files-bulk-note").assertExists()
        assertTrue(onAllNodes(hasText("Upload")).fetchSemanticsNodes().isEmpty(), "no Upload")
        onNodeWithTag("files-download").assertIsNotEnabled()
        onNodeWithText("Export").assertExists()
    }

    @Test
    fun withSshOffTheManagerKeepsTheCommandsAndOffersToTurnSshOn() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val files = listOf(Item("*1", mapOf("name" to "rc.local", "type" to ".local file", "size" to "127")))
        val services = listOf(Item("*9", mapOf("name" to "ssh", "port" to "22", "disabled" to "true")))
        val router = FakeRouter(mapOf("file" to files, "ip/service" to services), ssh = Ssh(Sftp(), answer = SshReach.Off), fileTexts = mapOf("*1" to "text"))
        val storage = MemoryStorage()
        val exchange = Exchange()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        tab.open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Large files go over SFTP, and SSH is turned off on the router.")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("files-enable-ssh").assertExists()
        assertTrue(onAllNodes(hasText("Upload")).fetchSemanticsNodes().isEmpty(), "no Upload without SFTP")
        onNodeWithText("via API").assertExists()
        onNodeWithText("rc.local").performClick()
        onNodeWithTag("files-download").performClick()
        waitUntil(timeoutMillis = 5_000) { exchange.saved.isNotEmpty() }
        assertEquals("rc.local" to "text", exchange.saved.single())
        onNodeWithTag("files-rename").performClick()
        onNodeWithTag("files-name").performTextReplacement("start.local")
        onAllNodes(hasText("Rename")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { router.changed.isNotEmpty() }
        assertEquals("*1" to mapOf<String, String?>("name" to "start.local"), router.changed.single())
    }

    @Test
    fun restoreOfABackupAsksBeforeItRuns() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val load = MenuSchema(MenuPath.parse("system/backup/load"), NodeKind.Command, emptyList(), emptyList(), listOf(Argument("name", ValueType.Text()), Argument("password", ValueType.Text())))
        val router = FakeRouter(mapOf("file" to listOf(Item("*1", mapOf("name" to "rd-test.backup", "type" to "backup")))), schemas = mapOf("system/backup/load" to load), ssh = Ssh(Sftp()))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test.backup")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Configuration").performClick()
        onNodeWithText("Backup").assertExists()
        onNodeWithText("Restore").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Start")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Run Restore on the router?")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(router.ran.isEmpty(), "nothing runs before the answer")
        onAllNodes(hasText("Restore")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.isNotEmpty() }
        assertEquals("system/backup/load", router.ran.single().first)
    }

    @Test
    fun aFolderGoesUpAndFilesComeDownIntoOne() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val sftp = Sftp()
        val written = mutableMapOf<String, String>()
        val folder = LocalFolder("scripts", listOf(LocalFile("a.rsc") { "/log info a".encodeToByteArray() }, LocalFile("b.rsc") { "/log info b".encodeToByteArray() })) { name, bytes ->
            written[name] = bytes.decodeToString()
        }
        val exchange = object : FileExchange {
            override suspend fun pick() = emptyList<LocalFile>()
            override suspend fun save(name: String, bytes: ByteArray) = false
            override val folders = true
            override suspend fun pickFolder() = folder
        }
        val files = listOf(
            Item("*1", mapOf("name" to "rc.local", "type" to ".local file", "size" to "127")),
            Item("*2", mapOf("name" to "pub/notes.txt", "type" to ".txt file", "size" to "12")),
        )
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(sftp), fileTexts = mapOf("*2" to "contents of /pub/notes.txt"))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        tab.open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rc.local")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Upload a folder")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Upload a folder").performClick()
        waitUntil(timeoutMillis = 5_000) { sftp.written.size == 2 }
        assertEquals(mapOf("/a.rsc" to "/log info a", "/b.rsc" to "/log info b"), sftp.written.toMap())

        onNodeWithText("pub").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("notes.txt")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("notes.txt").performClick()
        onNodeWithText("Download to a folder").performClick()
        waitUntil(timeoutMillis = 5_000) { written.isNotEmpty() }
        assertEquals(mapOf("notes.txt" to "contents of /pub/notes.txt"), written.toMap())
    }

    @Test
    fun theHostKeyOfSshOpeningForFilesIsAskedOverTheRouter() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val sftp = Sftp()
        val exchange = Exchange()
        val trust = MemoryTrustStore()
        val shell = Shell(Services(MemoryStorage(), trust, neighbors = null), TestScope())
        val policy = TrustPolicy(trust, shell.questions::ask)
        val ssh = LazySsh {
            if (!policy.confirm("192.0.2.1", 22, TrustKind.Ssh, "SHA256:rd-test", null)) error("declined")
            Ssh(sftp)
        }
        shell.add(FakeRouter(mapOf("file" to listOf(Item("*1", mapOf("name" to "rc.local", "type" to ".local file")))), ssh = ssh), "192.0.2.1")
        setContent { CompositionLocalProvider(LocalFileExchange provides exchange) { App(shell) } }
        shell.routers.single().open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rc.local")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("rc.local").performClick()
        onNodeWithText("Download").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("SHA256:rd-test")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Trust").performClick()
        waitUntil(timeoutMillis = 5_000) { exchange.saved.isNotEmpty() }
        assertEquals("SHA256:rd-test", runBlocking { trust.get("192.0.2.1", 22, TrustKind.Ssh) })
    }

    @Test
    fun aClosedSshPortIsToldInWords() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val refused = ConnectException("failed to connect to /192.0.2.1 (port 22): ECONNREFUSED (Connection refused)")
        val ssh = LazySsh { throw Exception(refused.toString(), refused) }
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(mapOf("file" to listOf(Item("*1", mapOf("name" to "rc.local", "type" to ".local file")))), ssh = ssh), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent {
            CompositionLocalProvider(LocalFileExchange provides Exchange()) {
                NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        tab.open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rc.local")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("rc.local").performClick()
        onNodeWithText("Download").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("File transfer failed: the router does not accept SSH connections", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("ConnectException", substring = true)).fetchSemanticsNodes().isEmpty(), "no text of the socket error")
    }

    @Test
    fun aHostOverSshTakesFilesIntoTheSelectedFolder() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val sftp = Sftp(
            mapOf(
                "/home" to listOf(RemoteFile("rd-test", 0, true, 1_800_000_000)),
                "/home/rd-test" to listOf(RemoteFile("notes.txt", 12, false, 1_800_000_000)),
                "/tmp" to emptyList(),
            ),
        )
        val exchange = object : FileExchange {
            override suspend fun pick() = listOf(LocalFile("rd-test.txt") { "rd-test".encodeToByteArray() })
            override suspend fun save(name: String, bytes: ByteArray) = false
        }
        val device = DeviceInfo(Platform.Ssh, "rd-test-host", "", "", "", emptySet(), setOf("read", "write"))
        val storage = MemoryStorage()
        val tab = RouterTab(SshHostSession(Ssh(sftp, "/home/rd-test\n+0000\n"), device), "192.0.2.50", bundledPacks().values, storage, TableLayouts(storage))
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        tab.open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("home")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("Export")).fetchSemanticsNodes().isEmpty(), "a host over SSH has no export")

        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Upload")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Upload").performClick()
        waitUntil(timeoutMillis = 5_000) { sftp.written.isNotEmpty() }
        assertEquals(setOf("/tmp/rd-test.txt"), sftp.written.keys)

        onNodeWithText("home").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("rd-test").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Upload")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Upload").performClick()
        waitUntil(timeoutMillis = 5_000) { sftp.written.size == 2 }
        assertEquals("rd-test", sftp.written["/home/rd-test/rd-test.txt"])
    }

    @Test
    fun aHostListsAFolderOnlyWhenItIsOpened() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val sftp = Sftp(
            mapOf(
                "/home" to listOf(RemoteFile("rd-test", 0, true, 1_800_000_000)),
                "/home/rd-test" to listOf(RemoteFile("docs", 0, true, 1_800_000_000), RemoteFile("notes.txt", 12, false, 1_800_000_000)),
                "/home/rd-test/docs" to listOf(RemoteFile("guide.md", 99, false, 1_800_000_000)),
                "/tmp" to emptyList(),
            ),
        )
        val device = DeviceInfo(Platform.Ssh, "rd-test-host", "", "", "", emptySet(), setOf("read", "write"))
        val storage = MemoryStorage()
        val tab = RouterTab(SshHostSession(Ssh(sftp, "/home/rd-test\n+0000\n"), device), "192.0.2.50", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("home")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("/home" !in sftp.listed, "nothing is read before the arrow")
        onNodeWithTag("toggle-home").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("toggle-home/rd-test").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("docs")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("/home/rd-test/docs" !in sftp.listed, "a folder inside is read when it is opened")
        onNodeWithTag("toggle-home/rd-test/docs").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("guide.md")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("/home/rd-test/docs" in sftp.listed)
    }

    @Test
    fun aFolderOpensWithADoubleClickAndTakesTheUpload() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val sftp = Sftp()
        val exchange = object : FileExchange {
            override suspend fun pick() = listOf(LocalFile("rd-test.txt") { "rd-test".encodeToByteArray() })
            override suspend fun save(name: String, bytes: ByteArray) = false
        }
        val files = listOf(
            Item("*1", mapOf("name" to "flash", "type" to "directory")),
            Item("*2", mapOf("name" to "flash/skins", "type" to "directory")),
            Item("*3", mapOf("name" to "flash/skins/rd-test.json", "type" to ".json file", "size" to "12")),
            Item("*4", mapOf("name" to "rc.local", "type" to ".local file", "size" to "127")),
        )
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(mapOf("file" to files), ssh = Ssh(sftp)), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        tab.open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rc.local")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("skins")).fetchSemanticsNodes().isEmpty() && onAllNodes(hasText("flash/skins")).fetchSemanticsNodes().isEmpty(), "the top only")

        onNodeWithTag("row-flash").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("skins")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("rc.local")).fetchSemanticsNodes().isNotEmpty(), "the rest of the tree stays in view")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Upload")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Upload").performClick()
        waitUntil(timeoutMillis = 5_000) { sftp.written.isNotEmpty() }
        assertEquals(setOf("/flash/rd-test.txt"), sftp.written.keys)

        onNodeWithTag("row-flash/skins").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test.json")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("row-flash/skins").performMouseInput { rightClick() }
        onNodeWithText("Show only this folder").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rc.local")).fetchSemanticsNodes().isEmpty() }
        onNodeWithTag("files-crumbs").assert(hasAnyDescendant(hasText("skins")))
        onNodeWithTag("folder-up").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("skins")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("folder-up").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rc.local")).fetchSemanticsNodes().isNotEmpty() }
    }
}
