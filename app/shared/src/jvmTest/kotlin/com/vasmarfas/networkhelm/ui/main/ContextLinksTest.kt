package com.vasmarfas.networkhelm.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackCombo
import com.vasmarfas.networkhelm.packs.PackFill
import com.vasmarfas.networkhelm.packs.PackForm
import com.vasmarfas.networkhelm.packs.PackFormTab
import com.vasmarfas.networkhelm.packs.PackLink
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.packs.PackTab
import com.vasmarfas.networkhelm.packs.PackWindow
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.terminal.LocalTerminal
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.awaitCancellation
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ContextLinksTest {

    // a console of RouterOS greets with its prompt, and a line typed before it would lose its Enter
    private class Console : ShellChannel {
        val written = StringBuilder()
        private var greeted = false
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (greeted) awaitCancellation()
            greeted = true
            val prompt = "\r\n[admin@CHR] > ".encodeToByteArray()
            prompt.copyInto(buffer, offset)
            return prompt.size
        }
        override suspend fun write(bytes: ByteArray) {
            written.append(bytes.decodeToString())
        }
        override fun resize(columns: Int, rows: Int) = Unit
        override fun close() = Unit
    }

    private class Ssh : SshSession {
        val consoles = mutableListOf<Console>()
        override suspend fun exec(command: String): ExecResult = error("no exec")
        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = Console().also { consoles += it }
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    private val reading: @Composable (ShellChannel, Modifier) -> Unit = { channel, modifier ->
        LaunchedEffect(channel) {
            val buffer = ByteArray(256)
            do {
                val read = channel.read(buffer, 0, buffer.size)
            } while (read > 0)
        }
        Text("console", modifier)
    }

    private val gadgets = Pack(
        menu = listOf("Gadgets"),
        window = PackWindow("Gadgets", listOf(PackTab("Gadgets", "ip/gadget"))),
        paths = mapOf(
            "ip/gadget" to PackPath(
                columns = listOf("name", "address", "interface", "mac-address"),
                labels = mapOf("address" to "IP Address"),
                menu = listOf(
                    PackLink("Probe", "tool/probe", fill = listOf(PackFill("Probe To", "IP Address"), PackFill("Link", "Interface"), PackFill("Name")), autostart = true),
                    PackLink("Make Copy", "ip/gadget", autostart = true, fill = listOf(PackFill("Name", "Name"), PackFill("IP Address", "IP Address")), item = true),
                    PackLink("Edit Copy", "ip/gadget", fill = listOf(PackFill("Name", "Name"), PackFill("IP Address", "IP Address")), item = true),
                    PackLink("Refresh", "tool/probe", global = true),
                    PackLink("Lost", "tool/lost"),
                    PackLink("Odd", "tool/probe", fill = listOf(PackFill("Probe To", "Nothing Of That Name"))),
                    PackLink("Telnet", "", "telnet", fill = listOf(PackFill("Address", "IP Address")), autostart = true),
                    PackLink("MAC Telnet", "", "mac-telnet", fill = listOf(PackFill("MAC Address", "MAC Address")), autostart = true),
                    PackLink("Shell", "ip/gadget", "terminal", fill = listOf(PackFill("name", "Name"), PackFill("command", ".id", "/ip/gadget/shell {}"))),
                    PackLink("Lines", "ip/gadget/line", fill = listOf(PackFill("Gadget", "Name"))),
                ),
            ),
            "ip/gadget/line" to PackPath(columns = listOf("gadget", "message"), readonly = listOf("gadget", "message"), quick = "gadget"),
        ),
    )

    private val probe = Pack(
        menu = listOf("Tools", "Probe"),
        window = PackWindow("Probe", listOf(PackTab("Probe", "tool/probe"))),
        paths = mapOf("tool/probe" to PackPath(form = "probe", labels = mapOf("address" to "Probe To", "interface" to "Link"), combos = mapOf("name" to PackCombo(listOf("all"), "system/resource:cpu-count", "cpu#")))),
        forms = mapOf("probe" to PackForm(tabs = listOf(PackFormTab("General", listOf("address", "interface", "name"))))),
    )

    private val rows = listOf(
        Item("*1", mapOf("name" to "gad1", "address" to "192.0.2.5", "interface" to "ether1", "mac-address" to "02:00:00:00:00:01")),
        Item("*2", mapOf("name" to "gad2", "address" to "192.0.2.6", "interface" to "ether2", "mac-address" to "02:00:00:00:00:02")),
    )

    private val schemas = mapOf(
        "ip/gadget" to MenuSchema(
            MenuPath.parse("ip/gadget"), NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"),
            listOf(Argument("name", ValueType.Text()), Argument("address", ValueType.Text()), Argument("interface", ValueType.Text()), Argument("mac-address", ValueType.Text())),
        ),
        "tool/probe" to MenuSchema(
            MenuPath.parse("tool/probe"), NodeKind.Command, emptyList(), emptyList(),
            listOf(Argument("address", ValueType.Text()), Argument("interface", ValueType.Text()), Argument("name", ValueType.Text())),
        ),
    )

    private fun SkikoComposeUiTest.opened(): Pair<RouterTab, FakeRouter> {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(mapOf("ip/gadget" to rows), schemas = schemas, lacking = setOf("tool/lost"), settings = mapOf("system/resource" to Item("*", mapOf("cpu-count" to "2"))))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", listOf(gadgets, probe), storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Gadgets", "ip/gadget"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("gad2")).fetchSemanticsNodes().isNotEmpty() }
        return tab to router
    }

    private fun SkikoComposeUiTest.counts(vararg titles: String) = titles.map { onAllNodes(hasText(it)).fetchSemanticsNodes().size }

    @Test
    fun anItemOfTheMenuOfARowOpensTheWindowFilledFromTheRowAndRunsIt() = runDesktopComposeUiTest(1000, 620) {
        val (tab, router) = opened()
        onNodeWithText("gad1").performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Open")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(1, 1, 1, 0, 0, 0, 0), counts("Probe", "Make Copy", "Refresh", "Lost", "Odd", "Telnet", "MAC Telnet"), "a button that needs no row stands in the toolbar and not in the menu of a row, and telnet needs a console")
        onNodeWithText("Probe").performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.isNotEmpty() }
        assertEquals(listOf("tool/probe" to mapOf("address" to "192.0.2.5", "interface" to "ether1", "name" to "gad1")), router.ran.toList())
        val window = tab.specs.values.single { it.title == "Probe" }.tabs.single()
        assertEquals(mapOf("address" to "192.0.2.5", "interface" to "ether1", "name" to "gad1"), window.prefill)
        assertTrue(window.start)
    }

    @Test
    fun anItemThatFillsItsOwnTableAndStartsByItselfAddsTheRowAtOnceAndAnotherOpensTheNewRowToBeFinished() = runDesktopComposeUiTest(1000, 620) {
        val (tab, router) = opened()
        onNodeWithText("gad2").performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Make Copy")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Make Copy").performClick()
        waitUntil(timeoutMillis = 5_000) { router.added.isNotEmpty() }
        assertEquals(listOf(mapOf("name" to "gad2", "address" to "192.0.2.6")), router.added.toList())
        assertTrue(tab.items.isEmpty(), "no dialog stands between the item and the row")
        onNodeWithText("gad2").performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Edit Copy")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Edit Copy").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.items.isNotEmpty() }
        val item = tab.items.values.single()
        assertEquals(mapOf("name" to "gad2", "address" to "192.0.2.6"), item.prefill)
        assertEquals(null, item.id)
        assertEquals(1, router.added.size)
    }

    @Test
    fun aButtonThatNeedsNoRowOpensItsWindowEmptyAndDoesNotRunIt() = runDesktopComposeUiTest(1000, 620) {
        val (tab, router) = opened()
        onNodeWithText("Refresh").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.specs.values.any { it.title == "Probe" } }
        val window = tab.specs.values.single { it.title == "Probe" }.tabs.single()
        assertEquals(emptyMap(), window.prefill)
        assertEquals(false, window.start)
        assertEquals(emptyList(), router.ran.toList())
    }

    @Test
    fun anItemWhoseWindowTheRouterLacksOrWhoseColumnIsNotInTheTableIsNotOffered() = runDesktopComposeUiTest(1000, 620) {
        opened()
        onNodeWithText("gad1").performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Probe")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(0, 0), counts("Lost", "Odd"))
    }

    @Test
    fun withTwoRowsChosenTheMenuOffersNoItemOfARow() = runDesktopComposeUiTest(1000, 620) {
        opened()
        onNodeWithText("gad1").performClick()
        onNodeWithTag("table").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.A) } }
        onNodeWithText("gad2").performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Copy as command")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(0, 0), counts("Probe", "Make Copy"))
    }

    @Test
    fun theFieldsOfALinkAreFoundByTheLabelsOfTheWindowAndAWindowOpenedAgainTakesTheNewValues() = runDesktopComposeUiTest(1000, 620) {
        val (tab, _) = opened()
        tab.open(MenuEntry("Probe", "tool/probe", fill = mapOf("Probe To" to "192.0.2.5", "link" to "ether1", "Name" to "gad1", "Nothing" to "x")))
        waitUntil(timeoutMillis = 5_000) { tab.specs.values.any { it.title == "Probe" } }
        assertEquals(mapOf("address" to "192.0.2.5", "interface" to "ether1", "name" to "gad1"), tab.specs.values.single { it.title == "Probe" }.tabs.single().prefill)
        tab.open(MenuEntry("Probe", "tool/probe", fill = mapOf("Probe To" to "192.0.2.6")))
        waitUntil(timeoutMillis = 5_000) { tab.specs.values.single { it.title == "Probe" }.tabs.single().prefill.size == 1 }
        assertEquals(mapOf("address" to "192.0.2.6"), tab.specs.values.single { it.title == "Probe" }.tabs.single().prefill)
        tab.open(MenuEntry("Probe", "tool/probe"))
        assertEquals(1, tab.specs.values.count { it.title == "Probe" })
        assertEquals(mapOf("address" to "192.0.2.6"), tab.specs.values.single { it.title == "Probe" }.tabs.single().prefill)
    }

    @Test
    fun aValueThatIsTheLabelOfAChoiceTakesTheValueOfThatChoice() = runDesktopComposeUiTest(1000, 620) {
        val (tab, router) = opened()
        tab.open(MenuEntry("Probe", "tool/probe", fill = mapOf("Name" to "cpu1", "Link" to "ether9"), start = true))
        waitUntil(timeoutMillis = 5_000) { router.ran.isNotEmpty() }
        assertEquals(listOf("tool/probe" to mapOf("name" to "1", "interface" to "ether9")), router.ran.toList())
    }

    @Test
    fun aTelnetItemConnectsFromTheRouterToTheAddressOfTheRowAndAMacTelnetItemToItsMacAddress() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val ssh = Ssh()
        val router = FakeRouter(mapOf("ip/gadget" to rows), schemas = schemas, ssh = ssh)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", listOf(gadgets, probe), storage, TableLayouts(storage))
        setContent {
            NetworkHelmTheme(dark = true) {
                CompositionLocalProvider(LocalTerminal provides reading) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        tab.open(MenuEntry("Gadgets", "ip/gadget"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("gad2")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("gad1").performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Telnet")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Telnet").performClick()
        waitUntil(timeoutMillis = 5_000) { ssh.consoles.singleOrNull()?.written?.isNotEmpty() == true }
        assertEquals("/system telnet 192.0.2.5\r", ssh.consoles.single().written.toString())
        tab.workspace.windows.toList().forEach(tab.workspace::close)
        tab.open(MenuEntry("Gadgets", "ip/gadget"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("gad2")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("gad2").performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("MAC Telnet")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("MAC Telnet").performClick()
        waitUntil(timeoutMillis = 5_000) { ssh.consoles.size == 2 && ssh.consoles.last().written.isNotEmpty() }
        assertEquals("/tool mac-telnet 02:00:00:00:00:02\r", ssh.consoles.last().written.toString())
    }

    @Test
    fun aShellItemTypesTheCommandWithTheIdOfTheRow() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val ssh = Ssh()
        val router = FakeRouter(mapOf("ip/gadget" to rows), schemas = schemas, ssh = ssh)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", listOf(gadgets, probe), storage, TableLayouts(storage))
        setContent {
            NetworkHelmTheme(dark = true) {
                CompositionLocalProvider(LocalTerminal provides reading) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        tab.open(MenuEntry("Gadgets", "ip/gadget"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("gad2")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("gad2").performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Shell")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Shell").performClick()
        waitUntil(timeoutMillis = 5_000) { ssh.consoles.singleOrNull()?.written?.isNotEmpty() == true }
        assertEquals("/ip/gadget/shell *2\r", ssh.consoles.single().written.toString())
        assertTrue(tab.workspace.windows.any { it.title == "Shell: gad2" })
    }

    @Test
    fun aTableOpenedFromARowShowsTheRowsOfThatRow() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val lines = listOf(
            Item("*1", mapOf("gadget" to "gad1", "message" to "first of gad1")),
            Item("*2", mapOf("gadget" to "gad2", "message" to "first of gad2")),
            Item("*3", mapOf("gadget" to "gad1", "message" to "second of gad1")),
        )
        val router = FakeRouter(mapOf("ip/gadget" to rows, "ip/gadget/line" to lines), schemas = schemas)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", listOf(gadgets, probe), storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Gadgets", "ip/gadget"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("gad2")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("gad1").performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Lines")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Lines").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("second of gad1")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(1, 0), counts("first of gad1", "first of gad2"))
        assertEquals(mapOf("gadget" to "gad1"), tab.specs.values.single { it.tabs.any { tab -> tab.path == MenuPath.parse("ip/gadget/line") } }.tabs.single { it.path == MenuPath.parse("ip/gadget/line") }.prefill)
    }

    @Test
    fun theLabelsOfWinBoxFindTheFieldsOfTheBundledTorchAndPingWindows() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Torch", "tool/torch", fill = mapOf("Interface" to "ether1", "Src. Address" to "10.0.2.2", "MAC Protocol" to "ip", "Src. Address6" to "fe80::1")))
        tab.open(MenuEntry("Ping", "tool/ping", fill = mapOf("Ping To" to "10.0.2.2")))
        val opened = tab.specs.values.associate { it.title to it.tabs.first().prefill }
        assertEquals(mapOf("interface" to "ether1", "src-address" to "10.0.2.2", "mac-protocol" to "ip", "src-address6" to "fe80::1"), opened["Torch"])
        assertEquals(mapOf("address" to "10.0.2.2"), opened["Ping"])
    }
}
