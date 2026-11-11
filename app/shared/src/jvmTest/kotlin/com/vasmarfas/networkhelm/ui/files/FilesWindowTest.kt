package com.vasmarfas.networkhelm.ui.files

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.RemoteFile
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshReach
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.ui.main.MainScreen
import com.vasmarfas.networkhelm.ui.main.RouterTab
import com.vasmarfas.networkhelm.ui.table.TableLayout
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FilesWindowTest {

    private class Sftp(private val contents: Map<String, ByteArray> = emptyMap()) : SftpClient {
        val written = mutableMapOf<String, String>()
        val writing = CompletableDeferred<Unit>()
        var finish: CompletableDeferred<Unit>? = null
        override suspend fun list(path: String) = emptyList<RemoteFile>()
        override suspend fun read(path: String) = contents[path] ?: "contents of $path".encodeToByteArray()
        override suspend fun write(path: String, bytes: ByteArray) {
            writing.complete(Unit)
            finish?.await()
            written[path] = bytes.decodeToString()
        }
        override suspend fun remove(path: String) = Unit
        override fun close() = Unit
    }

    private class Ssh(val sftp: Sftp, var answer: SshReach = SshReach.Open) : SshSession {
        override suspend fun reach(fresh: Boolean) = answer
        override suspend fun exec(command: String) = ExecResult("", "", 0)
        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp() = sftp
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    private class Writing(private val router: FakeRouter) : DeviceSession by router {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var writes = 0
        override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) {
            writes++
            started.complete(Unit)
            finish.await()
            router.set(path, id, changes)
        }
    }

    private val files = listOf(
        Item("*1", mapOf("name" to "rc.local", "type" to ".local file", "size" to "127")),
        Item("*2", mapOf("name" to "flash", "type" to "directory")),
        Item("*3", mapOf("name" to "flash/skins", "type" to "directory")),
        Item("*4", mapOf("name" to "flash/skins/dark.json", "type" to ".json file", "size" to "12")),
    )

    private fun ComposeUiTest.window(router: DeviceSession, waitFor: String = "rc.local"): RouterTab {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(waitFor)).fetchSemanticsNodes().isNotEmpty() }
        return tab
    }

    @Test
    fun aFileIsRenamedThroughTheCommandsOfTheRouter() = runDesktopComposeUiTest(1100, 700) {
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(Sftp()))
        window(router)
        onNodeWithText("rc.local").performClick()
        onNodeWithTag("files-rename").performClick()
        onNodeWithTag("files-name").performTextReplacement("start.local")
        onAllNodes(hasText("Rename")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { router.changed.isNotEmpty() }
        assertEquals("*1" to mapOf<String, String?>("name" to "start.local"), router.changed.single())
    }

    @Test
    fun aNameInUseIsNotTaken() = runDesktopComposeUiTest(1100, 700) {
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(Sftp()))
        window(router)
        onNodeWithTag("files-new").performClick()
        onNodeWithText("New folder").performClick()
        onNodeWithTag("files-name").performTextInput("flash")
        onAllNodes(hasText("New")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("flash already exists here.")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(router.added.isEmpty())
    }

    @Test
    fun aFolderIsMadeByTheAddCommandOfTheRouter() = runDesktopComposeUiTest(1100, 700) {
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(Sftp()))
        window(router)
        onNodeWithTag("files-new").performClick()
        onNodeWithText("New folder").performClick()
        onNodeWithTag("files-name").performTextInput("rd-test-dir")
        onAllNodes(hasText("New")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { router.added.isNotEmpty() }
        assertEquals(mapOf("name" to "rd-test-dir", "type" to "directory"), router.added.single())
    }

    @Test
    fun aFolderGoesWithWhatIsInItAndTheWindowAsksFirst() = runDesktopComposeUiTest(1100, 700) {
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(Sftp()))
        window(router)
        onNodeWithText("flash").performClick()
        onNodeWithTag("files-delete").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("A folder goes with everything in it.")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(router.removed.isEmpty(), "nothing goes before the answer")
        onAllNodes(hasText("Delete")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { router.removed.isNotEmpty() }
        assertEquals(listOf("*4", "*3", "*2"), router.removed.single())
    }

    private fun ComposeUiTest.shown(text: String, substring: Boolean = false) = onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.press(key: Key) {
        onNodeWithTag("tree").performKeyInput {
            keyDown(key)
            keyUp(key)
        }
    }

    @Test
    fun theTypedPathOpensTheWayToItAndAFolderCanBeShownAlone() = runDesktopComposeUiTest(1100, 700) {
        window(FakeRouter(mapOf("file" to files), ssh = Ssh(Sftp())))
        onNodeWithTag("files-crumbs").performClick()
        onNodeWithTag("files-path").performTextReplacement("/flash/skins")
        onNodeWithTag("files-path").performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        waitUntil(timeoutMillis = 5_000) { shown("dark.json") }
        assertTrue(shown("rc.local"), "the tree keeps the rest in view")
        onNodeWithTag("files-crumbs").performClick()
        onNodeWithTag("files-path").performTextReplacement("/nowhere")
        onNodeWithTag("files-path").performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        waitUntil(timeoutMillis = 5_000) { shown("There is no folder /nowhere.") }

        onNodeWithTag("row-flash").performMouseInput { rightClick() }
        onNodeWithText("Show only this folder").performClick()
        waitUntil(timeoutMillis = 5_000) { !shown("rc.local") }
        onNodeWithTag("files-crumbs").assert(hasAnyDescendant(hasText("flash")))
        assertTrue(shown("dark.json"))
        onNodeWithTag("folder-up").performClick()
        waitUntil(timeoutMillis = 5_000) { shown("rc.local") }
    }

    @Test
    fun foldersAreClosedAtFirstAndOpenWithTheArrowTheDoubleClickAndTheKeys() = runDesktopComposeUiTest(1100, 700) {
        window(FakeRouter(mapOf("file" to files), ssh = Ssh(Sftp())))
        assertTrue(!shown("skins") && !shown("dark.json"), "closed at first")
        onNodeWithTag("toggle-flash").performClick()
        waitUntil(timeoutMillis = 5_000) { shown("skins") }
        assertTrue(!shown("dark.json"), "the folder inside is closed as well")
        onNodeWithTag("row-flash/skins").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { shown("dark.json") }

        onNodeWithTag("row-flash").performClick()
        press(Key.DirectionLeft)
        waitUntil(timeoutMillis = 5_000) { !shown("skins") }
        press(Key.DirectionRight)
        waitUntil(timeoutMillis = 5_000) { shown("skins") && shown("dark.json") }
        press(Key.DirectionRight)
        press(Key.Backspace)
        press(Key.DirectionLeft)
        waitUntil(timeoutMillis = 5_000) { !shown("skins") }

        onNodeWithTag("files-collapse-all").performClick()
        onNodeWithTag("files-expand-all").performClick()
        waitUntil(timeoutMillis = 5_000) { shown("dark.json") }
        onNodeWithTag("files-collapse-all").performClick()
        waitUntil(timeoutMillis = 5_000) { !shown("skins") }
        onNodeWithTag("row-flash").performClick()
        press(Key.NumPadMultiply)
        waitUntil(timeoutMillis = 5_000) { shown("dark.json") }
    }

    @Test
    fun aFolderShowsHowManyItemsAreInItAndTheOpenOnesAreKeptForTheRouter() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tables = TableLayouts(storage)
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(Sftp()))
        val key = "files-tree/${router.device.id}"
        tables[key] = TableLayout(expanded = listOf("flash"))
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, tables)
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Files", "file"))
        waitUntil(timeoutMillis = 5_000) { shown("skins") }
        assertTrue(!shown("dark.json"))
        onNodeWithTag("row-flash").assert(hasAnyDescendant(hasText("2")))
        onNodeWithTag("toggle-flash/skins").performClick()
        waitUntil(timeoutMillis = 5_000) { tables[key]?.expanded == listOf("flash", "flash/skins") }
        onNodeWithTag("toggle-flash").performClick()
        waitUntil(timeoutMillis = 5_000) { tables[key]?.expanded == listOf("flash/skins") }
    }

    @Test
    fun aDiskAndAMountPointAreNeitherRenamedNorRemoved() = runDesktopComposeUiTest(1100, 700) {
        val rows = listOf(
            Item("*1", mapOf("name" to "disk1", "type" to "disk")),
            Item("*2", mapOf("name" to "usb1", "type" to "read-only mountpoint")),
            Item("*3", mapOf("name" to "disk1/a.txt", "type" to ".txt file")),
        )
        window(FakeRouter(mapOf("file" to rows), ssh = Ssh(Sftp())), "disk1")
        onNodeWithTag("row-disk1").performClick()
        onNodeWithTag("files-rename").assertIsNotEnabled()
        onNodeWithTag("files-delete").assertIsNotEnabled()
        onNodeWithTag("row-usb1").performClick()
        onNodeWithTag("files-delete").assertIsNotEnabled()
        onNodeWithTag("toggle-disk1").performClick()
        waitUntil(timeoutMillis = 5_000) { shown("a.txt") }
        onNodeWithTag("row-disk1/a.txt").performClick()
        onNodeWithTag("files-delete").assertIsEnabled()
    }

    @Test
    fun aListOfSixHundredRowsLaysOutOnlyWhatIsInView() = runDesktopComposeUiTest(1100, 700) {
        val many = (0 until 600).map { Item("*${it + 1}", mapOf("name" to "log-%03d.txt".format(it), "type" to ".txt file", "size" to "${it + 1}")) }
        window(FakeRouter(mapOf("file" to many), ssh = Ssh(Sftp())), "log-000.txt")
        val laidOut = onAllNodes(SemanticsMatcher("a row of the tree") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("row-") == true }).fetchSemanticsNodes().size
        assertTrue(laidOut in 10..60, "$laidOut rows are laid out")
        waitUntil(timeoutMillis = 5_000) { shown("600 items") }
    }

    @Test
    fun theFilterShowsMatchesWithTheWayToThemOpen() = runDesktopComposeUiTest(1100, 700) {
        window(FakeRouter(mapOf("file" to files), ssh = Ssh(Sftp())))
        onNodeWithTag("find").performTextInput("dark")
        waitUntil(timeoutMillis = 5_000) { shown("dark.json") }
        assertTrue(shown("flash") && shown("skins") && !shown("rc.local"))
        waitUntil(timeoutMillis = 5_000) { shown("1 of 4 items") }
    }

    @Test
    fun aFileOpensInTheEditorAndGoesBackWhereItCameFrom() = runDesktopComposeUiTest(1100, 700) {
        val sftp = Sftp()
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(sftp), fileTexts = mapOf("*1" to "contents of /rc.local"))
        val tab = window(router)
        onNodeWithText("rc.local").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("contents of /rc.local")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("file-save").assertIsNotEnabled()
        onNodeWithTag("editor-text").performTextInput("rd-test ")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Modified")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("file-save").performClick()
        waitUntil(timeoutMillis = 5_000) { router.changed.isNotEmpty() }
        assertEquals("*1" to mapOf<String, String?>("contents" to "rd-test contents of /rc.local"), router.changed.single())
        assertEquals(emptyMap(), sftp.written.toMap(), "a small text is written by the commands of the router, not over SFTP")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Saved")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, tab.files.size)
        onNodeWithTag("file-via").assertExists()
    }

    @Test
    fun aSmallTextIsEditedAndSavedWithSshOff() = runDesktopComposeUiTest(1100, 700) {
        val sftp = Sftp()
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(sftp, SshReach.Off), fileTexts = mapOf("*1" to "contents of /rc.local"))
        window(router)
        onNodeWithText("rc.local").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { shown("contents of /rc.local") }
        onNodeWithTag("editor-text").performTextInput("rd-test ")
        onNodeWithTag("file-save").performClick()
        waitUntil(timeoutMillis = 5_000) { router.changed.isNotEmpty() }
        assertEquals("*1" to mapOf<String, String?>("contents" to "rd-test contents of /rc.local"), router.changed.single())
        waitUntil(timeoutMillis = 5_000) { shown("via API") }
        assertEquals(1, onAllNodes(hasTestTag("files-bulk-note")).fetchSemanticsNodes().size, "the manager says it, the window of a small text does not")
        assertEquals(emptyMap(), sftp.written.toMap())
    }

    @Test
    fun aBigFileWithSshOffSaysWhyAndSshIsTurnedOnFromTheWindow() = runDesktopComposeUiTest(1100, 700) {
        val big = "line of a long log\n".repeat(40_000).encodeToByteArray()
        val ssh = Ssh(Sftp(mapOf("/big.log" to big)), SshReach.Off)
        val rows = listOf(Item("*1", mapOf("name" to "big.log", "type" to ".log file", "size" to "${big.size}")))
        val services = listOf(Item("*9", mapOf("name" to "ssh", "port" to "22", "disabled" to "true")))
        val router = FakeRouter(mapOf("file" to rows, "ip/service" to services), ssh = ssh)
        window(router, "big.log")
        onNodeWithText("big.log").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { shown("Large files go over SFTP, and SSH is turned off on the router.") }
        onNodeWithTag("file-needs-bulk").assertExists()
        ssh.answer = SshReach.Open
        onAllNodes(hasTestTag("files-enable-ssh")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { shown("Turn on the SSH service of the router?", substring = true) }
        onAllNodes(hasText("Turn on SSH on the router")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { router.changed.isNotEmpty() }
        assertEquals("*9" to mapOf<String, String?>("disabled" to "no"), router.changed.single())
        onNodeWithTag("file-reload").performClick()
        waitUntil(timeoutMillis = 10_000) { shown("Read only: the file is", substring = true) }
    }

    @Test
    fun textThatWasNotSavedStaysWithAMinimizedWindowAndKeepsItFromClosing() = runDesktopComposeUiTest(1100, 700) {
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(Sftp()), fileTexts = mapOf("*1" to "contents of /rc.local"))
        val tab = window(router)
        onNodeWithText("rc.local").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { shown("contents of /rc.local") }
        onNodeWithTag("editor-text").performTextInput("typed and ")
        waitUntil(timeoutMillis = 5_000) { shown("Modified") }
        val editor = tab.workspace.windows.last()
        runOnIdle { tab.workspace.minimize(editor) }
        waitUntil(timeoutMillis = 5_000) { !shown("Modified") }
        runOnIdle { tab.workspace.close(editor) }
        waitUntil(timeoutMillis = 5_000) { shown("typed and ", substring = true) }
        assertTrue(shown("Modified"), "a close meant for the minimized window brings it back with its text still to be saved")
        assertEquals(1, tab.files.size)
    }

    @Test
    fun aWindowWithChangesNotSavedAsksBeforeItCloses() = runDesktopComposeUiTest(1100, 700) {
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(Sftp()), fileTexts = mapOf("*1" to "contents of /rc.local"))
        val tab = window(router)
        onNodeWithText("rc.local").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("contents of /rc.local")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("editor-text").performTextInput("x")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Modified")).fetchSemanticsNodes().isNotEmpty() }
        runOnIdle { tab.workspace.close(tab.workspace.windows.last()) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Save the changes to rc.local?")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, tab.files.size, "the window stays while the question is open")
        onNodeWithText("Discard").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.files.isEmpty() }
    }

    @Test
    fun saveAsMakesLaterSavesWriteTheNewPathAndLeavesTheOriginalAlone() = runDesktopComposeUiTest(1100, 700) {
        val sftp = Sftp()
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(sftp), fileTexts = mapOf("*1" to "original"))
        window(router)
        onNodeWithText("rc.local").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { shown("original") && onAllNodes(hasTestTag("file-save-as")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("file-save-as").performClick()
        onNodeWithTag("file-save-as-name").performTextReplacement("new.txt")
        onAllNodes(hasText("Save")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { shown("/new.txt") }
        assertEquals("original", sftp.written["/new.txt"])
        onNodeWithTag("editor-text").performTextReplacement("updated")
        onNodeWithTag("file-save").performClick()
        waitUntil(timeoutMillis = 5_000) { sftp.written["/new.txt"] == "updated" }
        assertTrue(router.changed.isEmpty())
        assertEquals("original", router.fileText["*1"])
        assertEquals(setOf("/new.txt"), sftp.written.keys)
    }

    @Test
    fun saveAsOverAFileOfTheRouterAsksBeforeItIsReplaced() = runDesktopComposeUiTest(1100, 700) {
        val sftp = Sftp()
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(sftp), fileTexts = mapOf("*1" to "original"))
        window(router)
        onNodeWithText("rc.local").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { shown("original") && onAllNodes(hasTestTag("file-save-as")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("file-save-as").performClick()
        onNodeWithTag("file-save-as-name").performTextReplacement("flash/skins/dark.json")
        onAllNodes(hasText("Save")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { shown("The router already has /flash/skins/dark.json. Replace?") }
        assertEquals(emptyMap(), sftp.written.toMap(), "nothing is written before the answer")
        onAllNodes(hasText("Replace")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { shown("/flash/skins/dark.json") && sftp.written.isNotEmpty() }
        assertEquals(mapOf("/flash/skins/dark.json" to "original"), sftp.written.toMap())
        assertEquals("original", router.fileText["*1"])
    }

    @Test
    fun textTypedDuringSavingStaysModifiedAndAnotherSaveDoesNotStart() = runDesktopComposeUiTest(1100, 700) {
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(Sftp()), fileTexts = mapOf("*1" to "original"))
        val writing = Writing(router)
        window(writing)
        onNodeWithText("rc.local").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { shown("original") }
        onNodeWithTag("editor-text").performTextReplacement("sent")
        onNodeWithTag("file-save").performClick()
        waitUntil(timeoutMillis = 5_000) { writing.started.isCompleted }
        onNodeWithTag("file-save").assertIsNotEnabled()
        onNodeWithTag("editor-text").performTextReplacement("sent and edited")
        onNodeWithTag("editor-text").performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.S)
            keyUp(Key.S)
            keyUp(Key.CtrlLeft)
        }
        runOnIdle { assertEquals(1, writing.writes) }
        writing.finish.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { shown("Saved") }
        assertEquals("sent", router.fileText["*1"])
        onNodeWithTag("file-modified").assertExists()
        onNodeWithTag("file-save").assertIsEnabled()
    }

    @Test
    fun saveAsKeepsTextTypedDuringTheWriteModifiedAtTheNewPath() = runDesktopComposeUiTest(1100, 700) {
        val finish = CompletableDeferred<Unit>()
        val sftp = Sftp().also { it.finish = finish }
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(sftp), fileTexts = mapOf("*1" to "original"))
        window(router)
        onNodeWithText("rc.local").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { shown("original") && onAllNodes(hasTestTag("file-save-as")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("editor-text").performTextReplacement("sent")
        onNodeWithTag("file-save-as").performClick()
        onNodeWithTag("file-save-as-name").performTextReplacement("new.txt")
        onAllNodes(hasText("Save")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { sftp.writing.isCompleted }
        onNodeWithTag("editor-text").performTextReplacement("sent and edited")
        finish.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { shown("/new.txt") }
        assertEquals("sent", sftp.written["/new.txt"])
        assertEquals("original", router.fileText["*1"])
        onNodeWithTag("file-modified").assertExists()
    }

    @Test
    fun saveAndCloseAsksAgainWhenTextChangesDuringTheWrite() = runDesktopComposeUiTest(1100, 700) {
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(Sftp()), fileTexts = mapOf("*1" to "original"))
        val writing = Writing(router)
        val tab = window(writing)
        onNodeWithText("rc.local").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { shown("original") }
        onNodeWithTag("editor-text").performTextReplacement("sent")
        runOnIdle { tab.workspace.close(tab.workspace.windows.last()) }
        waitUntil(timeoutMillis = 5_000) { shown("Save the changes to rc.local?") }
        onAllNodes(hasText("Save")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { writing.started.isCompleted }
        onNodeWithTag("editor-text").performTextReplacement("sent and edited")
        writing.finish.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { shown("Save the changes to rc.local?") }
        assertEquals(1, tab.files.size)
        assertEquals("sent", router.fileText["*1"])
        onNodeWithTag("file-modified").assertExists()
    }

    @Test
    fun closingDuringAWriteStillAsksWhenTheTextWasChangedBackToTheOriginal() = runDesktopComposeUiTest(1100, 700) {
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(Sftp()), fileTexts = mapOf("*1" to "original"))
        val writing = Writing(router)
        val tab = window(writing)
        onNodeWithText("rc.local").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { shown("original") }
        onNodeWithTag("editor-text").performTextReplacement("sent")
        onNodeWithTag("file-save").performClick()
        waitUntil(timeoutMillis = 5_000) { writing.started.isCompleted }
        onNodeWithTag("editor-text").performTextReplacement("original")
        runOnIdle { tab.workspace.close(tab.workspace.windows.last()) }
        waitUntil(timeoutMillis = 5_000) { shown("Save the changes to rc.local?") }
        assertEquals(1, tab.files.size)
        writing.finish.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { router.fileText["*1"] == "sent" }
        onNodeWithTag("file-modified").assertExists()
    }

    @Test
    fun aFailedWriteKeepsTheEditorModifiedAndShowsTheFailure() = runDesktopComposeUiTest(1100, 700) {
        val router = FakeRouter(mapOf("file" to files), ssh = Ssh(Sftp()), fileTexts = mapOf("*1" to "original"))
        val failing = object : DeviceSession by router {
            override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) {
                throw IOException("write refused")
            }
        }
        window(failing)
        onNodeWithText("rc.local").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { shown("original") }
        onNodeWithTag("editor-text").performTextReplacement("changed")
        onNodeWithTag("file-save").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("file-problem")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("original", router.fileText["*1"])
        onNodeWithTag("file-modified").assertExists()
        onNodeWithTag("file-save").assertIsEnabled()
    }

    @Test
    fun aTextTooLongForTheEditorIsReadOnlyAndBytesAreADump() = runDesktopComposeUiTest(1100, 700) {
        val big = "line of a long log\n".repeat(40_000).encodeToByteArray()
        val binary = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x00, 0x01, 0x02)
        val sftp = Sftp(mapOf("/big.log" to big, "/tool.bin" to binary))
        val rows = listOf(
            Item("*1", mapOf("name" to "big.log", "type" to ".log file", "size" to "${big.size}")),
            Item("*2", mapOf("name" to "tool.bin", "type" to "file", "size" to "${binary.size}")),
        )
        window(FakeRouter(mapOf("file" to rows), ssh = Ssh(sftp), fileTexts = mapOf("*2" to "\u007fELF\u0000\u0001\u0002")), "big.log")
        onNodeWithText("big.log").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Read only: the file is", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("viewer").assertExists()
        onNodeWithText("tool.bin").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("hex")).fetchSemanticsNodes().isNotEmpty() }
    }
}
