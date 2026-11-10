package com.vasmarfas.networkhelm.ui.main

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.Legacy
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ItemDialogTest {

    private val path = MenuPath.parse("ip/firewall/filter")

    private val schema = MenuSchema(
        path, NodeKind.List, emptyList(), listOf("add", "move", "print", "remove", "reset-counters", "set", "unset"),
        listOf(
            Argument("chain", ValueType.Text(), choices = listOf("forward", "input", "output")),
            Argument("action", ValueType.Enumeration(), choices = listOf("accept", "drop", "reject")),
            Argument("src-address", ValueType.Ip4Range, negatable = true, optional = true),
            Argument("dst-address", ValueType.Ip4Range, negatable = true, optional = true),
            Argument("protocol", ValueType.Enumeration(), choices = listOf("tcp", "udp"), negatable = true, optional = true),
            Argument("dst-port", ValueType.PortList, negatable = true, optional = true),
            Argument("connection-state", ValueType.Enumeration(multiple = true), listOf("invalid", "established", "related", "new"), negatable = true, optional = true),
            Argument("comment", ValueType.Text()),
            Argument("disabled", ValueType.YesNo),
        ),
    )

    private val rule = Item(
        "*1",
        mapOf(
            "chain" to "forward", "action" to "accept", "src-address" to "!192.0.2.0/24", "protocol" to "tcp", "dst-port" to "8080",
            "connection-state" to "established,related", "disabled" to "true", "comment" to "web", "bytes" to "0",
        ),
    )

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun SkikoComposeUiTest.field(name: String) = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-$name")))

    private fun SkikoComposeUiTest.firewall(router: DeviceSession): RouterTab {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Firewall", path.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("accept")).fetchSemanticsNodes().isNotEmpty() }
        return tab
    }

    @Test
    fun aRuleOpensWithWhatTheListReadBeforeTheRouterAnswersItsWindow() = runDesktopComposeUiTest(1200, 800) {
        val router = FakeRouter(mapOf(path.toString() to listOf(rule)), schemas = mapOf(path.toString() to schema))
        val silent = object : DeviceSession by router {
            override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> =
                if (filter == null) router.watch(path, null, properties) else flow { awaitCancellation() }
        }
        firewall(silent)
        onNodeWithText("accept").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Firewall Rule <!192.0.2.0/24->8080>")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("8080") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun onlyTheTouchedFieldsOfARuleGoToTheRouter() = runDesktopComposeUiTest(1200, 800) {
        val router = FakeRouter(mapOf(path.toString() to listOf(rule)), schemas = mapOf(path.toString() to schema))
        firewall(router)
        onNodeWithText("accept").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Firewall Rule <!192.0.2.0/24->8080>")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("DISABLED")).fetchSemanticsNodes().isNotEmpty() }
        picture("dialog-rule")

        onNodeWithTag("unset-src-address").performClick()
        onNodeWithTag("set-dst-address").performClick()
        field("dst-address").performTextInput("10.0.0.0/8")
        field("dst-port").performTextReplacement("8080,8443")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Firewall Rule <->10.0.0.0/8:8080,8443>")).fetchSemanticsNodes().isNotEmpty() }
        picture("dialog-edited")
        onNodeWithText("Command").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("/ip firewall filter unset *1 value-name=src-address", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, onAllNodes(hasText("/ip firewall filter set *1 ", substring = true) and hasText("dst-address=10.0.0.0/8", substring = true)).fetchSemanticsNodes().size)
        picture("dialog-command")
        onAllNodes(hasText("Cancel")).onLast().performClick()
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 5_000) { router.changed.isNotEmpty() }
        assertEquals(listOf("*1" to mapOf("src-address" to null, "dst-address" to "10.0.0.0/8", "dst-port" to "8080,8443")), router.changed.map { it.first to it.second })
    }

    @Test
    fun aLineStandsOnlyBetweenGroupsThatShow() = runDesktopComposeUiTest(1200, 800) {
        val router = FakeRouter(mapOf(path.toString() to listOf(rule)), schemas = mapOf(path.toString() to schema))
        firewall(router)
        onNodeWithText("accept").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Firewall Rule <!192.0.2.0/24->8080>")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(3, onAllNodes(hasTestTag("group-line")).fetchSemanticsNodes().size)
        picture("dialog-lines")
    }

    @Test
    fun aRuleWithoutActionShowsTheActionTheRouterTakes() = runDesktopComposeUiTest(1200, 800) {
        val bare = Item("*2", mapOf("chain" to "forward", "protocol" to "tcp", "dst-port" to "65003"))
        val router = FakeRouter(mapOf(path.toString() to listOf(bare)), schemas = mapOf(path.toString() to schema))
        firewall(router)
        onNodeWithText("65003").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Firewall Rule <->65003>")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("Action")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("accept")).fetchSemanticsNodes().size == 2 }
        picture("dialog-default-action")
        onNodeWithText("OK").performClick()
        waitForIdle()
        assertTrue(router.changed.isEmpty())
    }

    @Test
    fun aClassicSwitchHasItsNameBesideTheBox() = runDesktopComposeUiTest(1200, 800) {
        val logged = MenuSchema(path, NodeKind.List, emptyList(), schema.commands, schema.arguments + Argument("log", ValueType.YesNo))
        val router = FakeRouter(mapOf(path.toString() to listOf(rule)), schemas = mapOf(path.toString() to logged))
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(Legacy, dark = false) { MainScreen(tab, onDisconnect = {}) } }
        tab.openItem("Firewall", path, "*1")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Action")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Action").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Log")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("Log:")).fetchSemanticsNodes().isEmpty())
        onNodeWithText("disabled").fetchSemanticsNode()
        picture("dialog-classic-switch")
        onNodeWithText("Log").performClick()
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 5_000) { router.changed.isNotEmpty() }
        assertEquals(mapOf("log" to "true"), router.changed.single().second)
    }

    @Test
    fun aClassicListHasTheToolbarOfWinBox3() = runDesktopComposeUiTest(1200, 800) {
        val counted = MenuSchema(path, NodeKind.List, emptyList(), schema.commands + "reset-counters-all", schema.arguments)
        val router = FakeRouter(mapOf(path.toString() to listOf(rule)), schemas = mapOf(path.toString() to counted))
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(Legacy, dark = false) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Firewall", path.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(";;; web")).fetchSemanticsNodes().isNotEmpty() }
        fun left(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().boundsInRoot.left
        assertTrue(left(onNodeWithContentDescription("Remove")) < left(onNodeWithContentDescription("Enable")))
        assertTrue(left(onNodeWithTag("filter")) < left(onNodeWithText("Reset Counters")))
        assertTrue(left(onNodeWithTag("find")) < left(onNodeWithTag("quick")))
        onNodeWithText("Reset All Counters").fetchSemanticsNode()
        picture("legacy-firewall")
    }

    @Test
    fun aClassicListHasAButtonForEachWindowItOpens() = runDesktopComposeUiTest(1200, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(Legacy, dark = false) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("DHCP Server", "ip/dhcp-server"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("DHCP Setup")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("DHCP Config").fetchSemanticsNode()
        assertTrue(onAllNodes(hasText("Configuration")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aPasswordGoesOutTypedTwiceTheSame() = runDesktopComposeUiTest(1200, 800) {
        val users = MenuPath.parse("user")
        val arguments = listOf(
            Argument("name", ValueType.Text()), Argument("group", ValueType.Text()), Argument("password", ValueType.Secret),
            Argument("comment", ValueType.Text()), Argument("disabled", ValueType.YesNo),
        )
        val router = FakeRouter(mapOf(users.toString() to emptyList()), schemas = mapOf(users.toString() to MenuSchema(users, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"), arguments)))
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.openItem("Users", users, null)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-password")).fetchSemanticsNodes().isNotEmpty() }
        field("name").performTextInput("rd-test")
        onNodeWithTag("field-password").performTextInput("first")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("confirm-password")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("OK").assertIsNotEnabled()
        onNodeWithTag("confirm-password").performTextInput("firts")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Does not match")).fetchSemanticsNodes().isNotEmpty() }
        picture("dialog-confirm")
        onNodeWithTag("confirm-password").performTextReplacement("first")
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 5_000) { router.added.isNotEmpty() }
        assertEquals("first", router.added.single()["password"])
    }

    @Test
    fun aRuleOfNatIsCalledSo() = runDesktopComposeUiTest(1200, 800) {
        val nat = MenuPath.parse("ip/firewall/nat")
        val router = FakeRouter(mapOf(nat.toString() to listOf(rule)), schemas = mapOf(nat.toString() to MenuSchema(nat, NodeKind.List, emptyList(), schema.commands, schema.arguments)))
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.openItem("NAT", nat, "*1")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("NAT Rule <!192.0.2.0/24->8080>")).fetchSemanticsNodes().isNotEmpty() }
        tab.openItem("NAT", nat, null)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("New NAT Rule")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aPortWaitsForAProtocolWithPorts() = runDesktopComposeUiTest(1200, 800) {
        val router = FakeRouter(mapOf(path.toString() to listOf(rule)), schemas = mapOf(path.toString() to schema))
        firewall(router)
        val waits = "Only with Protocol: tcp, udp, udp-lite, dccp, sctp"
        onNodeWithText("accept").performClick()
        onNodeWithTag("table").performKeyInput { pressKey(Key.Insert) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("New Firewall Rule")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("set-dst-port").assertIsNotEnabled()
        onNodeWithContentDescription(waits).assertExists()
        onNode(hasClickAction() and hasAnyAncestor(hasTestTag("field-protocol"))).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("udp")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("udp")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-dst-port")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("Cancel")).onLast().performClick()

        onNodeWithText("accept").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Firewall Rule <!192.0.2.0/24->8080>")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText(waits)).fetchSemanticsNodes().isEmpty(), "tcp has ports")
        onNodeWithTag("unset-protocol").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(waits)).fetchSemanticsNodes().isNotEmpty() }
        picture("dialog-port-waits")
    }

    @Test
    fun newRulesCopiesAndCommentsGoOutAsTheUserGaveThem() = runDesktopComposeUiTest(1200, 800) {
        val router = FakeRouter(mapOf(path.toString() to listOf(rule)), schemas = mapOf(path.toString() to schema))
        firewall(router)

        onNodeWithText("accept").performClick()
        onNodeWithTag("table").performKeyInput { pressKey(Key.Insert) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("New Firewall Rule")).fetchSemanticsNodes().isNotEmpty() }
        field("chain").performTextInput("input")
        picture("dialog-new")
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 5_000) { router.added.isNotEmpty() }
        assertEquals(mapOf("chain" to "input"), router.added.single())

        onNodeWithText("accept").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Copy")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Copy").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("New Firewall Rule")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("OK")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { router.added.size == 2 }
        assertEquals(rule.values - "bytes", router.added[1], "a copy carries every property the rule has, but not its counters")
        onNodeWithText("Cancel").performClick()

        mainClock.advanceTimeBy(500)
        onNodeWithText("accept").performClick()
        onNodeWithTag("table").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.M) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("comment")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("comment").performTextReplacement("web server")
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 5_000) { router.changed.isNotEmpty() }
        assertEquals(listOf("*1" to mapOf<String, String?>("comment" to "web server")), router.changed.toList())
    }

    @Test
    fun theDialogTakesTheHeightOfItsTab() = runDesktopComposeUiTest(1200, 800) {
        val router = FakeRouter(mapOf(path.toString() to listOf(rule)), schemas = mapOf(path.toString() to schema))
        val tab = firewall(router)
        onNodeWithText("accept").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Firewall Rule <!192.0.2.0/24->8080>")).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        val dialog = tab.workspace.windows.last()
        val general = dialog.size.height
        onNodeWithText("Statistics").performClick()
        waitForIdle()
        assertTrue(dialog.size.height < general, "${dialog.size.height} after $general")
        onNodeWithText("General").performClick()
        waitForIdle()
        assertEquals(general, dialog.size.height)

        dialog.resize(0, 1, 0.dp, 40.dp)
        onNodeWithText("Statistics").performClick()
        waitForIdle()
        assertEquals(general + 40.dp, dialog.size.height)
    }
}
