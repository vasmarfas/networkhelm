package com.vasmarfas.networkhelm.ui.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import com.vasmarfas.networkhelm.FakeClipboard
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.HiddenValue
import com.vasmarfas.networkhelm.app.LocalSettings
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.Settings
import com.vasmarfas.networkhelm.journal.EditKind
import com.vasmarfas.networkhelm.journal.EditRecord
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.LINK_LOST
import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.kit.readText
import com.vasmarfas.networkhelm.ui.table.CommentMode
import com.vasmarfas.networkhelm.ui.table.RowTone
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
class ListWindowTest {

    private val rules = listOf(
        Item("*1", mapOf("chain" to "forward", "action" to "accept", "protocol" to "tcp", "dst-port" to "443", "comment" to "web")),
        Item("*2", mapOf("chain" to "input", "action" to "drop", "disabled" to "true")),
        Item("*3", mapOf("chain" to "forward", "action" to "reject", "invalid" to "true", "dynamic" to "true")),
    )

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun SkikoComposeUiTest.top(text: String) = onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    private fun SkikoComposeUiTest.drawn(tag: String): String {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return layouts.firstOrNull()?.layoutInput?.text?.text.orEmpty()
    }

    @Test
    fun aListStillLoadingIsNotEmpty() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(mapOf("ip/firewall/filter" to rules))
        val waiting = object : DeviceSession by router {
            override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> = flow { awaitCancellation() }
        }
        val storage = MemoryStorage()
        val tab = RouterTab(waiting, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Firewall", "ip/firewall/filter"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("New")).fetchSemanticsNodes().isNotEmpty() }
        picture("list-loading")
        assertTrue(onAllNodes(hasText("Loading…")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodes(hasText("0 items")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aBigTableCountsItsRowsWhileTheyCome() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(mapOf("ip/firewall/filter" to rules))
        val finish = CompletableDeferred<Unit>()
        val portions = object : DeviceSession by router {
            override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> = flow {
                emit(ListEvent.Progress(2_000, 35_000))
                finish.await()
                emit(ListEvent.Snapshot(rules))
                awaitCancellation()
            }
        }
        val storage = MemoryStorage()
        val tab = RouterTab(portions, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Firewall", "ip/firewall/filter"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Loading… 2000 of 35000")).fetchSemanticsNodes().isNotEmpty() }
        finish.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("${rules.size} items")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("Loading…", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aListThatLostTheLinkOffersToAskAgainAndAsksByItselfWhenTheLinkIsBack() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(mapOf("ip/firewall/filter" to rules))
        var broken = true
        val failing = object : DeviceSession by router {
            override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> = flow {
                emit(ListEvent.Snapshot(rules))
                if (broken) throw DeviceException(ErrorCategory.ConnectionLost, LINK_LOST)
                awaitCancellation()
            }
        }
        val storage = MemoryStorage()
        val tab = RouterTab(failing, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Firewall", "ip/firewall/filter"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("retry")).fetchSemanticsNodes().isNotEmpty() }
        broken = false
        router.link.value = LinkState.Reconnecting
        waitForIdle()
        router.link.value = LinkState.Up
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("retry")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aListThatFailedToLoadSaysSoAndIsAskedAgain() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(mapOf("ip/firewall/filter" to rules))
        var broken = true
        val failing = object : DeviceSession by router {
            override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> = flow {
                if (broken) throw DeviceException(ErrorCategory.Api, "the console fell over")
                emit(ListEvent.Snapshot(rules))
                awaitCancellation()
            }
        }
        val storage = MemoryStorage()
        val tab = RouterTab(failing, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Firewall", "ip/firewall/filter"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("the console fell over")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("Not loaded")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodes(hasText("Loading…")).fetchSemanticsNodes().isEmpty())
        broken = false
        onNodeWithTag("retry").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("drop")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("the console fell over")).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasTestTag("retry")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aReadOnlyUserGetsNoButtonsForChangesAndAClosedMenuSaysWhy() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(mapOf("ip/firewall/filter" to rules), policies = setOf("read", "test"), deviceMode = mapOf("traffic-gen" to "false"))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Firewall", "ip/firewall/filter"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("drop")).fetchSemanticsNodes().isNotEmpty() }
        for (button in listOf("New", "Enable", "Disable", "Remove", "Comment")) {
            assertTrue(onAllNodes(hasText(button)).fetchSemanticsNodes().isEmpty(), "no $button")
        }
        assertTrue(onAllNodes(hasTestTag("safe-mode")).fetchSemanticsNodes().isEmpty() && onAllNodes(hasTestTag("undo")).fetchSemanticsNodes().isEmpty())
        onNodeWithText("accept").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Close")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("OK")).fetchSemanticsNodes().isEmpty(), "the rule is only shown")

        tab.open(MenuEntry("Traffic Generator", "tool/traffic-generator"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("device-mode: traffic-gen=false", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        picture("list-read-only")
    }

    @Test
    fun whatTheGroupLacksIsMarkedInTheMenuAndNotOffered() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val ping = MenuSchema(MenuPath.parse("tool/ping"), NodeKind.Command, emptyList(), emptyList(), listOf(Argument("address", ValueType.Text())))
        val tools = MenuSchema(MenuPath.parse("tool"), NodeKind.Directory, listOf("ping", "torch"), emptyList(), emptyList())
        val users = listOf(Item("*1", mapOf("name" to "rd-test-admin", "group" to "full")))
        val router = FakeRouter(mapOf("user" to users), schemas = mapOf("tool" to tools, "tool/ping" to ping), policies = setOf("read", "write", "sniff"))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        waitUntil(timeoutMillis = 5_000) { tab.menu?.isNotEmpty() == true }
        onNodeWithTag("menu-search").performTextInput("torch")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasContentDescription("The user group lacks test")).fetchSemanticsNodes().isNotEmpty() }
        picture("menu-locked")

        tab.open(MenuEntry("Ping", "tool/ping"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("lacks the policies test", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("Start")).fetchSemanticsNodes().isEmpty(), "ping is not offered")

        tab.open(MenuEntry("Users", "user"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test-admin")).fetchSemanticsNodes().isNotEmpty() }
        for (button in listOf("New", "Remove")) assertTrue(onAllNodes(hasText(button)).fetchSemanticsNodes().isEmpty(), "no $button")
    }

    @Test
    fun aColumnTheReleaseLacksGoesAndAnOldNameStandsForTheNew() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val services = MenuPath.parse("ip/service")
        val arguments = listOf(Argument("port", ValueType.Integer()), Argument("address", ValueType.Text()))
        val schema = MenuSchema(services, NodeKind.List, emptyList(), listOf("print", "set"), arguments, lacks = setOf("available-from", "vrf", "max-sessions"))
        val rows = listOf(Item("*1", mapOf("name" to "api", "port" to "8728", "address" to "192.0.2.0/24")))
        val router = FakeRouter(mapOf(services.toString() to rows), schemas = mapOf(services.toString() to schema))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Services", services.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("192.0.2.0/24")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("Available From")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodes(hasText("VRF")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aColumnTheMonitorFillsInStaysThoughTheListLacksIt() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val interfaces = MenuPath.parse("interface")
        val lacks = setOf("tx-bits-per-second", "rx-bits-per-second")
        val schema = MenuSchema(interfaces, NodeKind.List, emptyList(), listOf("print", "set"), listOf(Argument("name", ValueType.Text())), lacks = lacks)
        val rows = listOf(Item("*1", mapOf("name" to "ether1", "type" to "ether")))
        val router = FakeRouter(mapOf(interfaces.toString() to rows), schemas = mapOf(interfaces.toString() to schema))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Interfaces", interfaces.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("ether1")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("Tx")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aRuleTableSortsSearchesAndRemovesWithConfirmation() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(mapOf("ip/firewall/filter" to rules))
        val storage = MemoryStorage()
        val tables = TableLayouts(storage)
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, tables)
        setContent {
            LaunchedEffect(Unit) { tables.keep() }
            NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) }
        }
        tab.open(MenuEntry("Firewall", "ip/firewall/filter"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("drop")).fetchSemanticsNodes().isNotEmpty() }
        picture("list-rules")

        assertTrue(top("input") < top("reject"), "the router order first")
        onNodeWithText("Chain").performClick()
        waitUntil(timeoutMillis = 5_000) { top("reject") < top("input") }
        assertEquals(listOf("chain"), tables["ip/firewall/filter"]?.sort?.map { it.column })
        waitUntil(timeoutMillis = 5_000) { runBlocking { storage.read("tables.json") }?.contains("\"chain\"") == true }
        val again = TableLayouts(storage)
        runBlocking { withTimeoutOrNull(300) { again.keep() } }
        assertEquals(listOf("chain"), again["ip/firewall/filter"]?.sort?.map { it.column })

        onNodeWithTag("find").performTextInput("web")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("1 of 3 items")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("accept").performClick()
        onNodeWithTag("table").performKeyInput { pressKey(Key.Delete) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Remove 1 item from the router?")).fetchSemanticsNodes().isNotEmpty() }
        picture("list-remove")
        onAllNodes(hasText("Remove"))[1].performClick()
        waitUntil(timeoutMillis = 5_000) { router.removed.isNotEmpty() }
        assertEquals(listOf(listOf("*1")), router.removed)
    }

    @Test
    fun aDefaultEntryOfTheRouterStays() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val profiles = listOf(
            Item("*0", mapOf("name" to "rd-test-default", "default" to "true")),
            Item("*1", mapOf("name" to "rd-test-profile", "default" to "false")),
        )
        val router = FakeRouter(mapOf("ppp/profile" to profiles))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { CompositionLocalProvider(LocalSettings provides Settings(confirmRemoval = false)) { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } } }
        tab.open(MenuEntry("Profiles", "ppp/profile"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test-profile")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("rd-test-default").performClick()
        onNodeWithText("Remove").assertIsNotEnabled()
        onNodeWithTag("table").performKeyInput { pressKey(Key.Delete) }
        onNodeWithText("rd-test-default").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Copy")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, onAllNodes(hasText("Remove")).fetchSemanticsNodes().size, "the dialog of the default has no Remove")
        onNodeWithText("Cancel").performClick()
        onNodeWithText("rd-test-profile").performClick()
        onNodeWithTag("table").performKeyInput { pressKey(Key.Delete) }
        waitUntil(timeoutMillis = 5_000) { router.removed.isNotEmpty() }
        assertEquals(listOf(listOf("*1")), router.removed)
    }

    @Test
    fun theSettingsOfTablesAndRemovalAreFollowed() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(mapOf("ip/firewall/filter" to rules))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        val settings = Settings(confirmRemoval = false, columnLines = true, resourceBar = false)
        setContent { CompositionLocalProvider(LocalSettings provides settings) { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } } }
        tab.open(MenuEntry("Firewall", "ip/firewall/filter"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("accept")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("x86_64 / CHR", substring = true)).fetchSemanticsNodes().isEmpty(), "the resource bar is off")
        picture("list-separators")
        onNodeWithText("accept").performClick()
        onNodeWithTag("table").performKeyInput { pressKey(Key.Delete) }
        waitUntil(timeoutMillis = 5_000) { router.removed.isNotEmpty() }
        assertEquals(listOf(listOf("*1")), router.removed)
        assertTrue(onAllNodes(hasText("Remove 1 item from the router?")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun rulesKeepTheirNumbersWhenFiltered() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(mapOf("ip/firewall/filter" to rules)), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Firewall", "ip/firewall/filter"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("3 items")).fetchSemanticsNodes().isNotEmpty() }
        fun numbers() = onAllNodes(hasTestTag("number")).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString() }
        assertEquals(listOf("0", "1", "2"), numbers())
        onNodeWithTag("quick").performClick()
        onAllNodes(hasText("forward")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("2 of 3 items")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("0", "2"), numbers())
    }

    @Test
    fun theQuickFilterAndFilterLinesNarrowTheRows() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(mapOf("ip/firewall/filter" to rules)), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Firewall", "ip/firewall/filter"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("3 items")).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag("quick").performClick()
        onAllNodes(hasText("forward")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("2 of 3 items")).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag("filter").performClick()
        onNodeWithTag("filter-column").performClick()
        onAllNodes(hasText("Protocol")).onLast().performClick()
        onNodeWithTag("filter-value").performTextInput("tcp")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("1 of 3 items")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("find").performTextInput("cc")
        waitForIdle()
        picture("list-filtered")

        onNodeWithTag("filter").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("1 of 3 items")).fetchSemanticsNodes().isNotEmpty() && onAllNodes(hasTestTag("filter-value")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun rowsAndColumnsMoveByDraggingAndTheViewModesStay() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(mapOf("ip/firewall/filter" to rules))
        val storage = MemoryStorage()
        val tables = TableLayouts(storage)
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, tables)
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Firewall", "ip/firewall/filter"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("drop")).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("drop").performMouseInput {
            moveTo(center)
            press()
            repeat(5) { moveBy(Offset(0f, -12f)) }
            release()
        }
        waitUntil(timeoutMillis = 5_000) { router.moved.isNotEmpty() }
        assertEquals<Pair<List<String>, String?>>(listOf("*2") to "*1", router.moved.single(), "the dragged rule goes before the first one")

        onNodeWithText("Chain").performMouseInput {
            moveTo(center)
            press()
            repeat(5) { moveBy(Offset(-25f, 0f)) }
            release()
        }
        waitUntil(timeoutMillis = 5_000) { tables["ip/firewall/filter"]?.columns?.take(2) == listOf("chain", "action") }
        assertTrue(tables["ip/firewall/filter"]?.sort.isNullOrEmpty(), "dragging a header does not sort by it")

        onNodeWithTag("categories").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("input")).fetchSemanticsNodes().size == 2 && top("reject") < top("drop") }
        picture("list-categories")

        onNodeWithTag("detail").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Dst. Port 443")).fetchSemanticsNodes().isNotEmpty() }
        picture("list-detail")
        waitUntil(timeoutMillis = 5_000) { tables["ip/firewall/filter"]?.let { it.detail && it.categories } == true }
    }

    @Test
    fun theColumnsMenuMovesAShownColumnRightAndLeft() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tables = TableLayouts(storage)
        val tab = RouterTab(FakeRouter(mapOf("ip/firewall/filter" to rules)), "192.0.2.1", bundledPacks().values, storage, tables)
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Firewall", "ip/firewall/filter"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("drop")).fetchSemanticsNodes().isNotEmpty() }
        fun arrow(tag: String) {
            // the button stays in the corner of the header however wide the columns are
            if (onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()) onNodeWithTag("columns").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(tag).performClick()
        }

        arrow("right-action")
        waitUntil(timeoutMillis = 5_000) { tables["ip/firewall/filter"]?.columns?.take(2) == listOf("chain", "action") }
        assertTrue(tables["ip/firewall/filter"]?.sort.isNullOrEmpty(), "an arrow does not sort by the column")
        arrow("left-action")
        waitUntil(timeoutMillis = 5_000) { tables["ip/firewall/filter"]?.columns?.take(2) == listOf("action", "chain") }
    }

    @Test
    fun commentsStandAboveTheRowsInAColumnOrAway() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val path = MenuPath.parse("ip/firewall/filter")
        val schema = MenuSchema(path, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"), listOf(Argument("comment", ValueType.Text()), Argument("disabled", ValueType.YesNo)))
        val storage = MemoryStorage()
        val tables = TableLayouts(storage)
        val tab = RouterTab(FakeRouter(mapOf(path.toString() to rules), schemas = mapOf(path.toString() to schema)), "192.0.2.1", bundledPacks().values, storage, tables)
        setContent {
            LaunchedEffect(Unit) { tables.keep() }
            NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) }
        }
        tab.open(MenuEntry("Firewall", path.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("drop")).fetchSemanticsNodes().isNotEmpty() }
        val column = hasText("Comment") and hasAnyAncestor(hasTestTag("table"))
        fun choose(mode: String) {
            onNodeWithTag("comments").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(mode)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(mode).performClick()
        }

        assertTrue(top("web") < top("accept"), "a line above its row")
        assertTrue(onAllNodes(column).fetchSemanticsNodes().isEmpty())
        choose("In a column")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(column).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(top("accept"), top("web"), "a cell of its row")
        choose("Hidden")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("web")).fetchSemanticsNodes().isEmpty() }
        assertTrue(onAllNodes(column).fetchSemanticsNodes().isEmpty())
        onNodeWithTag("find").performTextInput("web")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("1 of 3 items")).fetchSemanticsNodes().isNotEmpty() }

        waitUntil(timeoutMillis = 5_000) { runBlocking { storage.read("tables.json") }?.contains("Hidden") == true }
        val again = TableLayouts(storage)
        runBlocking { withTimeoutOrNull(300) { again.keep() } }
        assertEquals(CommentMode.Hidden, again[path.toString()]?.comments)
    }

    @Test
    fun aTableShowsCommentsAsTheSettingsSayUntilItGetsAModeOfItsOwn() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val path = MenuPath.parse("ip/firewall/filter")
        val schema = MenuSchema(path, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"), listOf(Argument("comment", ValueType.Text()), Argument("disabled", ValueType.YesNo)))
        val storage = MemoryStorage()
        val tables = TableLayouts(storage)
        val tab = RouterTab(FakeRouter(mapOf(path.toString() to rules), schemas = mapOf(path.toString() to schema)), "192.0.2.1", bundledPacks().values, storage, tables)
        var settings by mutableStateOf(Settings(comments = CommentMode.InColumn))
        setContent { CompositionLocalProvider(LocalSettings provides settings) { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } } }
        tab.open(MenuEntry("Firewall", path.toString()))
        val column = hasText("Comment") and hasAnyAncestor(hasTestTag("table"))
        fun choose(mode: String) {
            onNodeWithTag("comments").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(mode)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(mode).performClick()
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(column).fetchSemanticsNodes().isNotEmpty() }
        settings = Settings(comments = CommentMode.Hidden)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(column).fetchSemanticsNodes().isEmpty() && onAllNodes(hasText("web")).fetchSemanticsNodes().isEmpty() }

        choose("In a column")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(column).fetchSemanticsNodes().isNotEmpty() }
        settings = Settings(comments = CommentMode.Above)
        waitForIdle()
        assertTrue(onAllNodes(column).fetchSemanticsNodes().isNotEmpty(), "the mode of the table stands")
        assertEquals(CommentMode.InColumn, tables[path.toString()]?.comments)

        choose("Above rows")
        waitUntil(timeoutMillis = 5_000) { tables[path.toString()]?.comments == null }
        settings = Settings(comments = CommentMode.Hidden)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("web")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aDisabledRowFadesThoughTheRouterCallsItInvalidToo() {
        val switch = Argument("disabled", ValueType.YesNo)
        assertEquals(RowTone.Faded, tone(Item("*1", mapOf("disabled" to "true", "invalid" to "true")), switch))
        assertEquals(RowTone.Normal, tone(Item("*2", mapOf("disabled" to "false", "invalid" to "true")), switch))
    }

    @Test
    fun countersComeLiveAndALostLinkIsShown() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(mapOf("ip/firewall/filter" to rules))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Firewall", "ip/firewall/filter"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("drop")).fetchSemanticsNodes().isNotEmpty() }

        assertEquals(listOf(Triple("ip/firewall/filter", listOf("bytes", "packets"), null)), router.polled.toList())
        tab.openItem("Filter Rules", MenuPath.parse("ip/firewall/filter"), "*1")
        waitUntil(timeoutMillis = 5_000) { router.polled.size == 2 }
        assertEquals(Triple("ip/firewall/filter", listOf("bytes", "packets"), Filter.Equals(".id", "*1")), router.polled.last())
        tab.workspace.close(tab.workspace.windows.last())
        assertTrue(tab.items.isEmpty(), "the dialog closed leaves nothing behind")

        router.counted.tryEmit(mapOf("*1" to mapOf("bytes" to "123456", "packets" to "789")))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("120.6 KiB")).fetchSemanticsNodes().isNotEmpty() }
        router.counted.tryEmit(mapOf("*1" to mapOf("bytes" to "1536000", "packets" to "790")))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("1500.0 KiB")).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Live").performClick()
        router.counted.tryEmit(mapOf("*1" to mapOf("bytes" to "1600000", "packets" to "791")))
        Thread.sleep(300)
        waitForIdle()
        assertTrue(onAllNodes(hasText("791")).fetchSemanticsNodes().isEmpty(), "held while paused")
        picture("list-paused")
        onNodeWithText("Paused").performClick()
        router.counted.tryEmit(mapOf("*1" to mapOf("bytes" to "1700000", "packets" to "792")))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("792")).fetchSemanticsNodes().isNotEmpty() }

        val polled = router.polled.size
        router.link.value = LinkState.Reconnecting
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("The connection to the router is lost, reconnecting…")).fetchSemanticsNodes().isNotEmpty() }
        picture("list-link-lost")
        router.link.value = LinkState.Up
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("The connection to the router is lost, reconnecting…")).fetchSemanticsNodes().isEmpty() }
        waitUntil(timeoutMillis = 5_000) { router.polled.size > polled }
    }

    @Test
    fun listsWhereNothingMovesAreNotPrintedAgain() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val arp = listOf(Item("*1", mapOf("address" to "192.0.2.7", "mac-address" to "02:00:00:00:00:07", "interface" to "ether1", "status" to "reachable")))
        val log = listOf(Item("*2", mapOf("time" to "10:00:00", "topics" to "system,info", "message" to "link up")))
        val router = FakeRouter(mapOf("ip/arp" to arp, "log" to log))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("ARP", "ip/arp"))
        tab.open(MenuEntry("Log", "log"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("192.0.2.7")).fetchSemanticsNodes().isNotEmpty() && onAllNodes(hasText("link up")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(emptyList(), router.polled.toList())
    }

    @Test
    fun actionsOfThePackRunOnTheSelectedRows() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val path = MenuPath.parse("ip/firewall/filter")
        val schema = MenuSchema(path, NodeKind.List, emptyList(), listOf("print", "remove", "reset-counters", "reset-counters-all", "set"), emptyList())
        val router = FakeRouter(mapOf(path.toString() to rules), schemas = mapOf(path.toString() to schema))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Firewall", path.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("drop")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("accept").performClick()
        onNodeWithText("Actions").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Reset Counters")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Reset Counters").performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.isNotEmpty() }
        assertEquals(listOf("ip/firewall/filter/reset-counters" to mapOf("numbers" to "*1")), router.ran.toList())
    }

    private fun SkikoComposeUiTest.rulesToCopy(clip: FakeClipboard): FakeRouter {
        Locale.setDefault(Locale.ENGLISH)
        val path = MenuPath.parse("ip/firewall/filter")
        val arguments = listOf(
            Argument("chain", ValueType.Text()), Argument("action", ValueType.Enumeration()), Argument("protocol", ValueType.Text()),
            Argument("dst-port", ValueType.PortList), Argument("comment", ValueType.Text()), Argument("disabled", ValueType.YesNo),
        )
        val schema = MenuSchema(path, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"), arguments)
        val router = FakeRouter(mapOf(path.toString() to rules), schemas = mapOf(path.toString() to schema))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { CompositionLocalProvider(LocalClipboard provides clip) { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } } }
        tab.open(MenuEntry("Firewall", path.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("drop")).fetchSemanticsNodes().isNotEmpty() }
        return router
    }

    @Test
    fun aCopiedRowPastesAsANewOne() = runDesktopComposeUiTest(1100, 700) {
        val clip = FakeClipboard()
        val router = rulesToCopy(clip)
        onNodeWithText("accept").performClick()
        onNodeWithTag("table").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertEquals("/ip firewall filter add chain=forward protocol=tcp dst-port=443 comment=web", runBlocking { clip.readText() })

        onNodeWithTag("table").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Add 1 item from the clipboard to the router?")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Paste").performClick()
        waitUntil(timeoutMillis = 5_000) { router.added.isNotEmpty() }
        assertEquals(mapOf("chain" to "forward", "protocol" to "tcp", "dst-port" to "443", "comment" to "web"), router.added.single())
    }

    @Test
    fun aRowHasAMenuOfWhatTheKeysDo() = runDesktopComposeUiTest(1100, 700) {
        val clip = FakeClipboard()
        rulesToCopy(clip)
        onNodeWithText("drop").performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Ctrl+C")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(listOf("Enter", "Ctrl+V", "Ctrl+E", "Ctrl+M", "Del").all { onAllNodes(hasText(it)).fetchSemanticsNodes().isNotEmpty() })
        assertTrue(onAllNodes(hasText("Ctrl+Shift+E")).fetchSemanticsNodes().isEmpty(), "the rule is off already")
        picture("list-row-menu")
        onNodeWithText("Copy as command").performClick()
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertEquals("/ip firewall filter add chain=input action=drop disabled=true", runBlocking { clip.readText() })
        assertTrue(onAllNodes(hasText("Ctrl+C")).fetchSemanticsNodes().isEmpty(), "the menu closes")
    }

    @Test
    fun theClientConfigOfAPeerShowsWithItsKeyHidden() = runDesktopComposeUiTest(1000, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val path = MenuPath.parse("interface/wireguard/peers")
        val schema = MenuSchema(path, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set", "show-client-config"), emptyList())
        val peers = listOf(Item("*1", mapOf("name" to "rd-test-peer", "interface" to "wg1")))
        val conf = "[Interface]\nPrivateKey = client-private\nAddress = 192.0.2.2/32\n\n[Peer]\nPublicKey = router-public\nEndpoint = 192.0.2.1:51820"
        val clients = mapOf("$path/show-client-config" to listOf(Item("", mapOf("conf" to "\n$conf\n"))))
        val router = FakeRouter(mapOf(path.toString() to peers), schemas = mapOf(path.toString() to schema), output = clients)
        val clip = FakeClipboard()
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { CompositionLocalProvider(LocalClipboard provides clip) { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } } }
        tab.open(MenuEntry("Peers", path.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test-peer")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("rd-test-peer").performClick()
        onNodeWithText("Client Config").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("output")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("$path/show-client-config" to mapOf("number" to "*1")), router.ran.toList())
        assertTrue(onAllNodes(hasText("PrivateKey = $HiddenValue", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodes(hasText("PublicKey = router-public", substring = true)).fetchSemanticsNodes().isNotEmpty())
        picture("output-hidden")
        onNodeWithContentDescription("Show the value").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("PrivateKey = client-private", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        picture("output-shown")
        onNodeWithText("Copy").performClick()
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertEquals(conf, runBlocking { clip.readText() })
        onNodeWithText("Close").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("output")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aLoneActionIsAButtonOfItsOwn() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val path = MenuPath.parse("ip/hotspot/user")
        val schema = MenuSchema(path, NodeKind.List, emptyList(), listOf("print", "remove", "reset-counters", "set"), emptyList())
        val users = listOf(Item("*1", mapOf("name" to "rd-test", "server" to "all")), Item("*2", mapOf("name" to "rd-test-2", "server" to "all")))
        val router = FakeRouter(mapOf(path.toString() to users), schemas = mapOf(path.toString() to schema))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Users", path.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test-2")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("Actions")).fetchSemanticsNodes().isEmpty(), "no list behind a button for one command")
        onNodeWithText("Reset Counters").assertIsNotEnabled()
        onNodeWithText("rd-test-2").performClick()
        onNodeWithText("Reset Counters").performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.isNotEmpty() }
        assertEquals(listOf("ip/hotspot/user/reset-counters" to mapOf("numbers" to "*2")), router.ran.toList())
    }

    @Test
    fun theMenuOfARowHasTheCommandsOfThePath() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val path = MenuPath.parse("ip/hotspot/user")
        val schema = MenuSchema(path, NodeKind.List, emptyList(), listOf("print", "remove", "reset-counters", "set"), emptyList())
        val users = listOf(Item("*1", mapOf("name" to "rd-test", "server" to "all")), Item("*2", mapOf("name" to "rd-test-2", "server" to "all")))
        val router = FakeRouter(mapOf(path.toString() to users), schemas = mapOf(path.toString() to schema))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Users", path.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test-2")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("rd-test-2").performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Reset Counters")).fetchSemanticsNodes().size == 2 }
        onAllNodes(hasText("Reset Counters")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.isNotEmpty() }
        assertEquals(listOf("ip/hotspot/user/reset-counters" to mapOf("numbers" to "*2")), router.ran.toList())
    }

    @Test
    fun passwordsStandAsDotsUntilTheyAreAskedFor() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val path = MenuPath.parse("ppp/secret")
        val schema = MenuSchema(path, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"), listOf(Argument("name", ValueType.Text()), Argument("password", ValueType.Text())))
        val secrets = listOf(Item("*1", mapOf("name" to "rd-test", "password" to "rd-test-word")))
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(mapOf(path.toString() to secrets), schemas = mapOf(path.toString() to schema)), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        var settings by mutableStateOf(Settings())
        setContent { CompositionLocalProvider(LocalSettings provides settings) { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } } }
        tab.open(MenuEntry("PPP", path.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("detail").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Password $HiddenValue")).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Name rd-test").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-password")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("field-password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertTrue("rd-test-word" !in drawn("field-password"), "hidden in the dialog")
        picture("dialog-hidden-password")
        onNodeWithContentDescription("Show the value").performClick()
        waitUntil(timeoutMillis = 5_000) { drawn("field-password") == "rd-test-word" }
        assertTrue(onAllNodes(hasContentDescription("Hide the value")).fetchSemanticsNodes().isNotEmpty())

        tab.journal.append(EditRecord(Clock.System.now(), "192.0.2.1", path, EditKind.Set, listOf("*1"), mapOf("password" to "rd-test-word"), mapOf("password" to "rd-test-new")))
        tab.openJournal("Journal")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("/ppp secret set *1 password=\"$HiddenValue\"")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("rd-test-new", substring = true)).fetchSemanticsNodes().isEmpty(), "hidden in the journal")
        picture("list-hidden-passwords")

        settings = Settings(hidePasswords = false)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Password rd-test-word")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("/ppp secret set *1 password=rd-test-new")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).fetchSemanticsNodes().isEmpty(), "an ordinary line in the dialog")
    }

    @Test
    fun theFlagsOfAListWithoutAPackTakeNoColumns() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val path = MenuPath.parse("ip/vrf")
        val router = FakeRouter(mapOf(path.toString() to listOf(Item("*0", mapOf("dynamic" to "true", "invalid" to "false", "name" to "main", "interfaces" to "all")))))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("VRF", path.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("main")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("Interfaces")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodes(hasText("Dynamic")).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasText("Invalid")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun servicesComeSortedByName() = runDesktopComposeUiTest(1000, 620) {
        Locale.setDefault(Locale.ENGLISH)
        val path = MenuPath.parse("ip/service")
        val router = FakeRouter(mapOf(path.toString() to listOf(Item("*1", mapOf("name" to "www", "port" to "80")), Item("*2", mapOf("name" to "ftp", "port" to "21")))))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Services", path.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("www")).fetchSemanticsNodes().isNotEmpty() && onAllNodes(hasText("ftp")).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        assertTrue(onNodeWithText("ftp").fetchSemanticsNode().boundsInRoot.top < onNodeWithText("www").fetchSemanticsNode().boundsInRoot.top)
    }
}
