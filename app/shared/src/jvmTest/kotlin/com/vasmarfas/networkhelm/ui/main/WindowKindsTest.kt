package com.vasmarfas.networkhelm.ui.main

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
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
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WindowKindsTest {

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun SkikoComposeUiTest.show(router: DeviceSession, entry: MenuEntry, tablesAsTabs: Boolean = false): RouterTab {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage)) { tablesAsTabs }
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(entry)
        return tab
    }

    private fun SkikoComposeUiTest.lines(name: String) = onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-$name")))

    @Test
    fun settingsAreAFormAndListsOfValuesAreLines() = runDesktopComposeUiTest(1100, 700) {
        val dns = MenuPath.parse("ip/dns")
        val schema = MenuSchema(
            dns, NodeKind.Settings, listOf("static"), listOf("print", "set"),
            listOf(Argument("servers", ValueType.Many(ValueType.Ip4)), Argument("allow-remote-requests", ValueType.YesNo), Argument("cache-size", ValueType.Integer(64, 4294967295, "KiB"))),
        )
        val settings = Item("", mapOf("servers" to "1.1.1.1,8.8.8.8", "allow-remote-requests" to "false", "cache-size" to "2048", "cache-used" to "40"))
        val router = FakeRouter(mapOf(dns.toString() to listOf(settings)), schemas = mapOf(dns.toString() to schema))
        show(router, MenuEntry("DNS", dns.toString()))
        waitUntil(timeoutMillis = 5_000) { lines("servers").fetchSemanticsNodes().size == 2 }
        onNode(hasText("KiB") and hasAnyAncestor(hasTestTag("field-cache-size"))).assertExists()
        onNodeWithText("40 KiB").assertExists()
        onNodeWithTag("gauge-cache-used").assertExists()

        onAllNodes(hasContentDescription("Add a line"))[0].performClick()
        waitUntil(timeoutMillis = 5_000) { lines("servers").fetchSemanticsNodes().size == 3 }
        lines("servers")[1].performTextInput("9.9.9.9")
        onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-cache-size"))).performTextReplacement("4096")
        waitForIdle()
        picture("window-settings")
        onNodeWithText("Apply").performClick()
        waitUntil(timeoutMillis = 5_000) { router.changed.isNotEmpty() }
        assertEquals(listOf<Pair<String?, Map<String, String?>>>(null to mapOf("servers" to "1.1.1.1,9.9.9.9,8.8.8.8", "cache-size" to "4096")), router.changed.toList())
    }

    @Test
    fun aLinkToAMenuTheRouterLacksIsNotOffered() = runDesktopComposeUiTest(1100, 700) {
        val dns = MenuPath.parse("ip/dns")
        val schema = MenuSchema(dns, NodeKind.Settings, emptyList(), listOf("print", "set"), listOf(Argument("servers", ValueType.Text())))
        val router = FakeRouter(mapOf(dns.toString() to listOf(Item("", mapOf("servers" to "")))), schemas = mapOf(dns.toString() to schema))
        val older = object : DeviceSession by router {
            override suspend fun describe(path: MenuPath): MenuSchema =
                if (path.segments.lastOrNull() in setOf("adlist", "forwarders")) throw DeviceException(ErrorCategory.MissingItem, "no such command prefix") else router.describe(path)

            override suspend fun outline(path: MenuPath): MenuSchema = describe(path)
        }
        show(older, MenuEntry("DNS", dns.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Static")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Adlist")).fetchSemanticsNodes().isEmpty() }
        assertTrue(onAllNodes(hasText("Forwarders")).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasText("Cache")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun theTablesOfDnsAreTabsOfItsWindowWhenTheSettingsSaySo() = runDesktopComposeUiTest(1100, 700) {
        val dns = MenuPath.parse("ip/dns")
        val static = MenuPath.parse("ip/dns/static")
        val router = FakeRouter(
            mapOf(dns.toString() to listOf(Item("", mapOf("servers" to ""))), static.toString() to listOf(Item("*1", mapOf("name" to "nas.lan", "address" to "192.168.88.10")))),
            schemas = mapOf(
                dns.toString() to MenuSchema(dns, NodeKind.Settings, emptyList(), listOf("print", "set"), listOf(Argument("servers", ValueType.Text()))),
                static.toString() to MenuSchema(static, NodeKind.List, emptyList(), listOf("print", "add", "set", "remove"), listOf(Argument("name", ValueType.Text()), Argument("address", ValueType.Text()))),
            ),
            lacking = setOf("ip/dns/adlist", "ip/dns/forwarders"),
        )
        val shown = show(router, MenuEntry("DNS", dns.toString()), tablesAsTabs = true)
        fun tab(title: String) = onAllNodes(hasText(title) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        waitUntil(timeoutMillis = 5_000) { tab("Static").fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { tab("Adlist").fetchSemanticsNodes().isEmpty() }
        assertTrue(tab("Cache").fetchSemanticsNodes().isNotEmpty())
        assertTrue(tab("Forwarders").fetchSemanticsNodes().isEmpty())
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-servers")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasTestTag("link-Static")).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasTestTag("link-Cache")).fetchSemanticsNodes().isEmpty())
        assertEquals(1, shown.workspace.windows.size)
        tab("Static")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("nas.lan")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theButtonsOfDnsOpenOneWindowWithATabForEachTable() = runDesktopComposeUiTest(1100, 700) {
        val dns = MenuPath.parse("ip/dns")
        val router = FakeRouter(
            mapOf(dns.toString() to listOf(Item("", mapOf("servers" to "")))),
            schemas = mapOf(dns.toString() to MenuSchema(dns, NodeKind.Settings, emptyList(), listOf("print", "set"), listOf(Argument("servers", ValueType.Text())))),
        )
        val shown = show(router, MenuEntry("DNS", dns.toString()))
        fun tab(title: String) = onAllNodes(hasText(title) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("link-Static")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("link-Static").performClick()
        waitUntil(timeoutMillis = 5_000) { tab("Forwarders").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("DNS Settings", "DNS"), shown.workspace.windows.map { it.title })
        tab("Static")[0].assertIsSelected()
        assertTrue(tab("All").fetchSemanticsNodes().isEmpty())
        shown.open(MenuEntry("Cache", "ip/dns/cache"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Cache") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, shown.workspace.windows.size)
    }

    @Test
    fun aFlushedCacheIsReadAgainThoughItsWatchSaysNothing() = runDesktopComposeUiTest(1100, 700) {
        val cache = MenuPath.parse("ip/dns/cache")
        val router = FakeRouter(
            schemas = mapOf(cache.toString() to MenuSchema(cache, NodeKind.List, emptyList(), listOf("print", "flush"), listOf(Argument("name", ValueType.Text()), Argument("type", ValueType.Text())))),
        )
        val cached = Item("*1", mapOf("name" to "example.com", "type" to "A"))
        var watches = 0
        val quiet = object : DeviceSession by router {
            override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> =
                if (path == cache) flowOf(ListEvent.Snapshot(if (++watches == 1) listOf(cached) else emptyList())) else router.watch(path, filter, properties)
        }
        show(quiet, MenuEntry("Cache", cache.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("example.com")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Flush Cache").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("example.com")).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("ip/dns/cache/flush" to emptyMap<String, String>()), router.ran.toList())
    }

    @Test
    fun theFieldsGoOnTheTabsOfLuciAndShowWithTheirProtocol() = runDesktopComposeUiTest(1100, 700) {
        val interfaces = MenuPath.parse("network/interface")
        val general = "General Settings"
        val schema = MenuSchema(
            interfaces, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"),
            listOf(
                Argument("proto", ValueType.Enumeration(), listOf("static", "dhcp"), label = "Protocol", tab = general),
                Argument("ipaddr", ValueType.Text(), label = "IPv4 address", tab = general, depends = listOf(mapOf("proto" to "static"))),
                Argument("hostname", ValueType.Text(), label = "Hostname to send", tab = general, depends = listOf(mapOf("proto" to "dhcp"))),
                Argument("mtu", ValueType.Text(), label = "Override MTU", tab = "Advanced Settings"),
                Argument("ptpaddr", ValueType.Text(), tab = "Other"),
            ),
        )
        val lan = Item("lan", mapOf("proto" to "static", "ipaddr" to "192.0.2.1"))
        val tab = show(FakeRouter(mapOf(interfaces.toString() to listOf(lan)), schemas = mapOf(interfaces.toString() to schema)), MenuEntry("Interfaces", interfaces.toString()))
        tab.openItem("Interfaces", interfaces, "lan")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-ipaddr")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(listOf(general, "Advanced Settings", "Other").all { onAllNodes(hasText(it)).fetchSemanticsNodes().isNotEmpty() })
        assertTrue(onAllNodes(hasTestTag("field-hostname")).fetchSemanticsNodes().isEmpty())
        onNode(hasClickAction() and hasAnyAncestor(hasTestTag("field-proto"))).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("dhcp")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("dhcp")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-hostname")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasTestTag("field-ipaddr")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aFlagARowLacksReadsAsItsDefault() = runDesktopComposeUiTest(1100, 700) {
        val rules = MenuPath.parse("rdtest/rule")
        val schema = MenuSchema(
            rules, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"),
            listOf(Argument("name", ValueType.Text()), Argument("enabled", ValueType.Flag, default = "1")),
        )
        val rows = listOf(Item("off", mapOf("name" to "Allow-Off", "enabled" to "0")), Item("ping", mapOf("name" to "Allow-Ping")))
        show(FakeRouter(mapOf(rules.toString() to rows), schemas = mapOf(rules.toString() to schema)), MenuEntry("Rules", rules.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Allow-Off")).fetchSemanticsNodes().isNotEmpty() }
        picture("window-flag-default")
        assertEquals(1, onAllNodes(hasText("yes")).fetchSemanticsNodes().size)
        assertEquals(1, onAllNodes(hasText("no")).fetchSemanticsNodes().size)
    }

    @Test
    fun aChoiceOfLuciReadsInTheListAsItsFormNamesIt() = runDesktopComposeUiTest(1100, 700) {
        val services = MenuPath.parse("rdtest/service")
        val schema = MenuSchema(
            services, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"),
            listOf(
                Argument("lookup_host", ValueType.Text()),
                Argument("use_ipv6", ValueType.Enumeration(), listOf("0", "1"), default = "0", labels = mapOf("0" to "IPv4-Address", "1" to "IPv6-Address")),
                Argument("service_name", ValueType.Enumeration(), listOf("", "dyndns.org"), labels = mapOf("" to "custom")),
            ),
        )
        val rows = listOf(
            Item("home", mapOf("lookup_host" to "rd-test-home.example.com", "use_ipv6" to "1", "service_name" to "dyndns.org")),
            Item("office", mapOf("lookup_host" to "rd-test-office.example.com")),
        )
        show(FakeRouter(mapOf(services.toString() to rows), schemas = mapOf(services.toString() to schema)), MenuEntry("Services", services.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test-home.example.com")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(listOf("IPv6-Address", "IPv4-Address", "custom", "dyndns.org").all { onAllNodes(hasText(it)).fetchSemanticsNodes().size == 1 })
        onNodeWithTag("find").performTextInput("IPv6")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test-office.example.com")).fetchSemanticsNodes().isEmpty() }
        onNodeWithText("rd-test-home.example.com").assertExists()
    }

    @Test
    fun aSectionOfUciIsDisabledByOne() = runDesktopComposeUiTest(1100, 700) {
        val networks = MenuPath.parse("rdtest/wifi-iface")
        val schema = MenuSchema(
            networks, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"),
            listOf(Argument("ssid", ValueType.Text()), Argument("disabled", ValueType.Flag)),
        )
        val router = FakeRouter(mapOf(networks.toString() to listOf(Item("uplink", mapOf("ssid" to "rd-test-uplink", "disabled" to "1")))), schemas = mapOf(networks.toString() to schema))
        show(router, MenuEntry("Networks", networks.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test-uplink")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("X")).fetchSemanticsNodes().isNotEmpty())
        onNodeWithText("rd-test-uplink").performClick()
        onNodeWithText("Enable").performClick()
        waitUntil(timeoutMillis = 5_000) { router.changed.isNotEmpty() }
        assertEquals(listOf<Pair<String?, Map<String, String?>>>("uplink" to mapOf("disabled" to "0")), router.changed.toList())
    }

    @Test
    fun aRuleOfUciIsSwitchedByEnabled() = runDesktopComposeUiTest(1100, 700) {
        val rules = MenuPath.parse("rdtest/rule")
        val schema = MenuSchema(
            rules, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"),
            listOf(Argument("name", ValueType.Text()), Argument("enabled", ValueType.Flag, default = "1")),
        )
        val rows = listOf(Item("ping", mapOf("name" to "rd-test-ping")), Item("off", mapOf("name" to "rd-test-off", "enabled" to "0")))
        val router = FakeRouter(mapOf(rules.toString() to rows), schemas = mapOf(rules.toString() to schema))
        show(router, MenuEntry("Rules", rules.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test-ping")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, onAllNodes(hasText("X")).fetchSemanticsNodes().size)
        onNodeWithText("rd-test-ping").performClick()
        onNodeWithText("Disable").performClick()
        waitUntil(timeoutMillis = 5_000) { router.changed.isNotEmpty() }
        assertEquals(listOf<Pair<String?, Map<String, String?>>>("ping" to mapOf("enabled" to "0")), router.changed.toList())
    }

    @Test
    fun aNewElementGetsTheDefaultsOfLuci() = runDesktopComposeUiTest(1100, 700) {
        val redirects = MenuPath.parse("rdtest/redirect")
        val schema = MenuSchema(
            redirects, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"),
            listOf(
                Argument("name", ValueType.Text()),
                Argument("target", ValueType.Enumeration(), listOf("DNAT", "SNAT"), default = "DNAT"),
                Argument("src", ValueType.Text(), default = "wan"),
                Argument("proto", ValueType.Text(), default = "tcp udp"),
                Argument("src_dip", ValueType.Text(), default = "192.0.2.1", depends = listOf(mapOf("target" to "SNAT"))),
                Argument("reflection", ValueType.Flag, default = "1"),
            ),
        )
        val router = FakeRouter(mapOf(redirects.toString() to emptyList()), schemas = mapOf(redirects.toString() to schema))
        val tab = show(router, MenuEntry("Port Forwards", redirects.toString()))
        tab.openItem("Port Forwards", redirects, null)
        waitUntil(timeoutMillis = 5_000) { lines("name").fetchSemanticsNodes().isNotEmpty() }
        lines("name").onLast().performTextInput("rd-test-web")
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 5_000) { router.added.isNotEmpty() }
        assertEquals(mapOf("name" to "rd-test-web", "target" to "DNAT", "src" to "wan", "proto" to "tcp udp"), router.added.single())
    }

    @Test
    fun aNewElementStartsWithWhatTheFormsGiveOne() = runDesktopComposeUiTest(1100, 700) {
        val rules = MenuPath.parse("rdtest/rule")
        val schema = MenuSchema(
            rules, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"),
            listOf(Argument(".name", ValueType.Text(), fixed = true), Argument("src", ValueType.Text()), Argument("dest", ValueType.Text())),
            prefill = mapOf("src" to "wan", "dest" to "lan"),
        )
        val router = FakeRouter(mapOf(rules.toString() to listOf(Item("ping", mapOf(".name" to "ping", "src" to "lan")))), schemas = mapOf(rules.toString() to schema))
        val tab = show(router, MenuEntry("Rules", rules.toString()))
        tab.openItem("Rules", rules, "ping")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-src")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasTestTag("field-.name")).fetchSemanticsNodes().isEmpty())
        onNodeWithText("Cancel").performClick()
        tab.openItem("Rules", rules, null)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-.name")).fetchSemanticsNodes().isNotEmpty() }
        lines(".name").onLast().performTextInput("rd_test_web")
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 5_000) { router.added.isNotEmpty() }
        assertEquals(mapOf(".name" to "rd_test_web", "src" to "wan", "dest" to "lan"), router.added.single())
    }

    @Test
    fun aNewElementHasTheFieldsOfItsColumnsOpen() = runDesktopComposeUiTest(1100, 700) {
        val routes = MenuPath.parse("ip/route")
        val schema = MenuSchema(
            routes, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set", "unset"),
            listOf(
                Argument("dst-address", ValueType.Ip4Prefix),
                Argument("gateway", ValueType.Text(), optional = true),
                Argument("pref-src", ValueType.Ip4, optional = true),
                Argument("check-gateway", ValueType.Enumeration(), listOf("arp", "bfd", "ping"), optional = true),
                Argument("blackhole", ValueType.YesNo, optional = true),
            ),
        )
        val router = FakeRouter(mapOf(routes.toString() to emptyList()), schemas = mapOf(routes.toString() to schema))
        val tab = show(router, MenuEntry("Routes", routes.toString()))
        tab.openItem("Routes", routes, null)
        waitUntil(timeoutMillis = 5_000) { lines("gateway").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasTestTag("set-gateway")).fetchSemanticsNodes().isEmpty())
        assertTrue(listOf("check-gateway", "blackhole").all { onAllNodes(hasTestTag("set-$it")).fetchSemanticsNodes().isNotEmpty() })
        lines("dst-address").onLast().performTextInput("192.0.2.0/24")
        lines("gateway").onLast().performTextInput("192.0.2.1")
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 5_000) { router.added.isNotEmpty() }
        assertEquals(mapOf("dst-address" to "192.0.2.0/24", "gateway" to "192.0.2.1"), router.added.single())
    }

    @Test
    fun aFieldThePackPlacesNowhereIsOnOther() = runDesktopComposeUiTest(1100, 700) {
        val entries = MenuPath.parse("ip/dns/static")
        val arguments = listOf(Argument("name", ValueType.Text()), Argument("address", ValueType.Ip, optional = true), Argument("rd-test-new", ValueType.Text()))
        val schema = MenuSchema(entries, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"), arguments)
        val tab = show(FakeRouter(mapOf(entries.toString() to emptyList()), schemas = mapOf(entries.toString() to schema)), MenuEntry("DNS Static", entries.toString()))
        tab.openItem("DNS Static", entries, null)
        val other = hasText("Other") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(other).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("RD Test New")).fetchSemanticsNodes().isEmpty(), "not on General")
        onNode(other).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("RD Test New")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theStateOfAClientStaysAtTheBottom() = runDesktopComposeUiTest(1100, 700) {
        val clients = MenuPath.parse("ip/dhcp-client")
        val schema = MenuSchema(
            clients, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"),
            listOf(Argument("interface", ValueType.Text()), Argument("use-peer-dns", ValueType.YesNo), Argument("disabled", ValueType.YesNo)),
        )
        val client = Item("*1", mapOf("interface" to "ether1", "use-peer-dns" to "true", "status" to "bound", "address" to "192.0.2.15/24", "disabled" to "false"))
        val tab = show(FakeRouter(mapOf(clients.toString() to listOf(client)), schemas = mapOf(clients.toString() to schema)), MenuEntry("DHCP Client", clients.toString()))
        tab.openItem("DHCP Client", clients, "*1")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("status")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("status").assertTextEquals("Status: bound")
        onNode(hasText("Status") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Use Peer DNS")).fetchSemanticsNodes().size == 1 }
        onNodeWithTag("status").assertTextEquals("Status: bound")
    }

    @Test
    fun theFieldsOfADnsEntryGoWithItsType() = runDesktopComposeUiTest(1100, 700) {
        val entries = MenuPath.parse("ip/dns/static")
        val schema = MenuSchema(
            entries, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set", "unset"),
            listOf(
                Argument("name", ValueType.Text()),
                Argument("type", ValueType.Enumeration(closed = true), listOf("A", "AAAA", "CNAME", "FWD", "MX", "NS", "NXDOMAIN", "SRV", "TXT")),
                Argument("address", ValueType.Ip, optional = true),
                Argument("cname", ValueType.Text(), optional = true),
            ),
        )
        val router = FakeRouter(mapOf(entries.toString() to emptyList()), schemas = mapOf(entries.toString() to schema))
        val tab = show(router, MenuEntry("DNS Static", entries.toString()))
        tab.openItem("DNS Static", entries, null)
        fun absent(name: String) = onAllNodes(hasTestTag("set-$name") or hasTestTag("field-$name")).fetchSemanticsNodes().isEmpty()
        waitUntil(timeoutMillis = 5_000) { lines("address").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(absent("cname"), "the name of a CNAME on an A entry")
        onNode(hasClickAction() and hasAnyAncestor(hasTestTag("field-type"))).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("CNAME")).fetchSemanticsNodes().size > 1 }
        onAllNodes(hasText("CNAME")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { lines("cname").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(absent("address"), "the address of a CNAME")
    }

    @Test
    fun theChoicesOfLuciReadAsItsFormsNameThem() = runDesktopComposeUiTest(1100, 700) {
        val services = MenuPath.parse("rdtest/service")
        val schema = MenuSchema(
            services, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"),
            listOf(
                Argument("lookup_host", ValueType.Text()),
                Argument("use_ipv6", ValueType.Enumeration(), listOf("0", "1"), default = "0", labels = mapOf("0" to "IPv4-Address", "1" to "IPv6-Address")),
                Argument("service_name", ValueType.Enumeration(), listOf("", "dyndns.org", "no-ip.com", "cloudflare.com-v4", "duckdns.org"), labels = mapOf("" to "custom")),
                Argument("update_url", ValueType.Text(), depends = listOf(mapOf("service_name" to ""))),
                Argument("ip_url", ValueType.Text(), depends = listOf(mapOf("use_ipv6" to "0"))),
            ),
        )
        val rows = listOf(Item("home", mapOf("lookup_host" to "rd-test.example.com", "service_name" to "dyndns.org")))
        val router = FakeRouter(mapOf(services.toString() to rows), schemas = mapOf(services.toString() to schema))
        val tab = show(router, MenuEntry("Services", services.toString()))
        tab.openItem("Services", services, "home")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-ip_url")).fetchSemanticsNodes().isNotEmpty() }
        onNode(hasText("IPv4-Address") and hasAnyAncestor(hasTestTag("field-use_ipv6"))).assertExists()
        assertTrue(onAllNodes(hasTestTag("field-update_url")).fetchSemanticsNodes().isEmpty())
        onNode(hasClickAction() and hasAnyAncestor(hasTestTag("field-service_name"))).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("custom")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("custom")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-update_url")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 5_000) { router.changed.isNotEmpty() }
        assertEquals(listOf<Pair<String?, Map<String, String?>>>("home" to mapOf("service_name" to "")), router.changed.toList())
    }

    @Test
    fun aWindowOfSettingsTakesTheHeightOfItsFields() = runDesktopComposeUiTest(1100, 700) {
        val identity = MenuPath.parse("system/identity")
        val sniffer = MenuPath.parse("tool/sniffer")
        val schemas = mapOf(identity to "name", sniffer to "file-name").entries.associate { (path, field) ->
            path.toString() to MenuSchema(path, NodeKind.Settings, emptyList(), listOf("print", "set"), listOf(Argument(field, ValueType.Text())))
        }
        val rows = mapOf(identity.toString() to listOf(Item("", mapOf("name" to "MikroTik"))), sniffer.toString() to listOf(Item("", mapOf("file-name" to "sniff.pcap"))))
        val tab = show(FakeRouter(rows, schemas = schemas), MenuEntry("Identity", identity.toString()))
        waitUntil(timeoutMillis = 5_000) { tab.workspace.windows.single().size.height < 200.dp }
        tab.open(MenuEntry("Packet Sniffer", sniffer.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-file-name")).fetchSemanticsNodes().isNotEmpty() }
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertEquals(440.dp, tab.workspace.windows.last().size.height)
    }

    @Test
    fun settingsThatShareTheWindowWithTablesMakeItTaller() = runDesktopComposeUiTest(1100, 900) {
        val dns = MenuPath.parse("ip/dns")
        val fields = listOf(
            "servers", "dynamic-servers", "use-doh-server", "mdns-repeat-ifaces", "allow-remote-requests", "vrf", "max-udp-packet-size", "query-server-timeout",
            "query-total-timeout", "max-concurrent-queries", "max-concurrent-tcp-sessions", "cache-size", "cache-max-ttl", "address-list-extra-time",
        )
        val schema = MenuSchema(dns, NodeKind.Settings, emptyList(), listOf("print", "set"), fields.map { Argument(it, ValueType.Text()) })
        val router = FakeRouter(mapOf(dns.toString() to listOf(Item("", fields.associateWith { "1" }))), schemas = mapOf(dns.toString() to schema))
        val tab = show(router, MenuEntry("DNS", dns.toString()), tablesAsTabs = true)
        waitUntil(timeoutMillis = 5_000) { tab.workspace.windows.single().size.height > 440.dp }
    }

    @Test
    fun aCommandRunsWithTheArgumentsGivenAndShowsWhatComesBack() = runDesktopComposeUiTest(1100, 700) {
        val ping = MenuPath.parse("tool/ping")
        val schema = MenuSchema(
            ping, NodeKind.Command, emptyList(), emptyList(),
            listOf(Argument("address", ValueType.Ip4), Argument("count", ValueType.Integer(1, 4294967295)), Argument("do-not-fragment", ValueType.Switch)),
        )
        val replies = (0..2).map { Item("", mapOf("seq" to "$it", "host" to "127.0.0.1", "time" to "64us", "sent" to "${it + 1}", "received" to "${it + 1}")) }
        val router = FakeRouter(schemas = mapOf(ping.toString() to schema), output = mapOf(ping.toString() to replies))
        show(router, MenuEntry("Ping", ping.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Start")).fetchSemanticsNodes().isNotEmpty() }
        onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-address"))).performTextInput("127.0.0.1")
        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("127.0.0.1")).fetchSemanticsNodes().size == 4 }
        picture("window-command")
        onNodeWithText("Advanced").performClick()
        for (times in 2..3) {
            onNode(hasClickAction() and hasAnyAncestor(hasTestTag("field-do-not-fragment"))).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Start") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Start").performClick()
            waitUntil(timeoutMillis = 5_000) { router.ran.size == times }
        }
        assertEquals(
            listOf(mapOf("address" to "127.0.0.1"), mapOf("address" to "127.0.0.1", "do-not-fragment" to "yes"), mapOf("address" to "127.0.0.1")),
            router.ran.map { it.second },
        )
        onNodeWithText("General").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Ping To")).fetchSemanticsNodes().isNotEmpty() && onAllNodes(hasText("Sent: 3")).fetchSemanticsNodes().isNotEmpty() }
    }

    // export-certificate takes file-name only since 6.43
    @Test
    fun aCommandShowsTheSectionOfItsRelease() = runDesktopComposeUiTest(1100, 700) {
        val export = MenuPath.parse("certificate/export-certificate")
        val arguments = listOf("numbers", "type", "export-passphrase", "file-name").map { Argument(it, ValueType.Text()) }
        val router = FakeRouter(schemas = mapOf(export.toString() to MenuSchema(export, NodeKind.Command, emptyList(), emptyList(), arguments)), version = "6.49.23")
        show(router, MenuEntry("Export", export.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Start")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("General")).fetchSemanticsNodes().isEmpty(), "one section and no tabs")
        assertTrue(lines("file-name").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aCommandOpenedForAnElementStartsWithIt() = runDesktopComposeUiTest(1100, 700) {
        val wifi = MenuPath.parse("interface/wifi")
        val scan = MenuPath.parse("interface/wifi/scan")
        val schemas = mapOf(
            wifi.toString() to MenuSchema(wifi, NodeKind.List, emptyList(), listOf("add", "print", "set"), listOf(Argument("name", ValueType.Text()))),
            scan.toString() to MenuSchema(scan, NodeKind.Command, emptyList(), emptyList(), listOf(Argument("number", ValueType.Text()), Argument("duration", ValueType.Time))),
        )
        val router = FakeRouter(mapOf(wifi.toString() to listOf(Item("*1", mapOf("name" to "wifi1")), Item("*2", mapOf("name" to "wifi2")))), schemas = schemas)
        val tab = show(router, MenuEntry("WiFi", wifi.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("wifi2")).fetchSemanticsNodes().isNotEmpty() }
        fun number() = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-number")))
        onNodeWithText("wifi2").performClick()
        onNode(hasText("Configuration") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        onNodeWithText("Scan").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-number"))).fetchSemanticsNodes().isNotEmpty() }
        number().assertTextEquals("wifi2")
        tab.workspace.windows.toList().drop(1).forEach(tab.workspace::close)

        onNodeWithText("wifi1").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("WiFi <wifi1>")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("Scan")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-number"))).fetchSemanticsNodes().isNotEmpty() }
        number().assertTextEquals("wifi1")
    }

    @Test
    fun aLoneLinkIsAButtonOfItsOwn() = runDesktopComposeUiTest(1100, 700) {
        val hosts = MenuPath.parse("ip/hotspot/host")
        val binding = MenuPath.parse("ip/hotspot/host/make-binding")
        val schemas = mapOf(
            hosts.toString() to MenuSchema(hosts, NodeKind.List, emptyList(), listOf("print", "remove"), emptyList()),
            binding.toString() to MenuSchema(binding, NodeKind.Command, emptyList(), emptyList(), listOf(Argument("numbers", ValueType.Text()), Argument("type", ValueType.Text()))),
        )
        val router = FakeRouter(mapOf(hosts.toString() to listOf(Item("*A", mapOf("mac-address" to "02:00:00:00:00:01", "address" to "192.0.2.20")))), schemas = schemas)
        show(router, MenuEntry("Hosts", hosts.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("192.0.2.20")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("Configuration")).fetchSemanticsNodes().isEmpty(), "no list behind a button for one window")
        onNodeWithText("192.0.2.20").performClick()
        onNodeWithText("Make Binding").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-numbers"))).fetchSemanticsNodes().isNotEmpty() }
        onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-numbers"))).assertTextEquals("*A")
    }

    @Test
    fun torchSaysThatItTurnsFastTrackOff() = runDesktopComposeUiTest(1100, 700) {
        val torch = MenuPath.parse("tool/torch")
        val schema = MenuSchema(torch, NodeKind.Command, emptyList(), emptyList(), listOf(Argument("interface", ValueType.Text()), Argument("port", ValueType.Text())))
        val flows = listOf(
            Item("", mapOf("ip-protocol" to "tcp", "src-address" to "192.0.2.10", "tx" to "127280", "rx" to "19040", "tx-packets" to "11", "rx-packets" to "9", ".section" to "1")),
            Item("", mapOf("ip-protocol" to "udp", "src-address" to "192.0.2.11", "tx" to "2720", "rx" to "960", "tx-packets" to "2", "rx-packets" to "1", ".section" to "1")),
        )
        show(FakeRouter(schemas = mapOf(torch.toString() to schema), output = mapOf(torch.toString() to flows)), MenuEntry("Torch", torch.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Start")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, onAllNodes(hasText("FastTrack", substring = true)).fetchSemanticsNodes().size)
        assertEquals(1, onAllNodes(hasText("Filters")).fetchSemanticsNodes().size)
        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("127.2 kbps")).fetchSemanticsNodes().isNotEmpty() }
        for (total in listOf("Total Tx Rate: 130.0 kbps", "Total Rx Rate: 20.0 kbps", "Total Tx Packet: 13", "Total Rx Packet: 10")) {
            assertEquals(1, onAllNodes(hasText(total)).fetchSemanticsNodes().size, total)
        }
        picture("window-torch")
    }

    @Test
    fun profileStartsWhenItsWindowOpensAndAsksForTheCpuAlone() = runDesktopComposeUiTest(1100, 700) {
        val profile = MenuPath.parse("tool/profile")
        val schema = MenuSchema(profile, NodeKind.Command, emptyList(), emptyList(), listOf(Argument("cpu", ValueType.Text()), Argument("duration", ValueType.Text())))
        val table = listOf(
            Item("", mapOf("name" to "total", "usage" to "1.5", ".section" to "0")),
            Item("", mapOf("name" to "ipc", "usage" to "0.7", ".section" to "0")),
        )
        val resource = mapOf("system/resource" to Item("", mapOf("cpu-count" to "2")))
        val router = FakeRouter(schemas = mapOf(profile.toString() to schema), output = mapOf(profile.toString() to table), endless = true, settings = resource)
        show(router, MenuEntry("Profile", profile.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("ipc")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, onAllNodes(hasText("0.7")).fetchSemanticsNodes().size)
        assertEquals(1, onAllNodes(hasTestTag("field-cpu")).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodes(hasTestTag("field-duration")).fetchSemanticsNodes().size)
        assertEquals(listOf("tool/profile" to emptyMap<String, String>()), router.ran)
        onNode(hasContentDescription("Variants") and hasAnyAncestor(hasTestTag("field-cpu"))).performClick()
        onNodeWithText("cpu0").assertExists()
        onNodeWithText("cpu1").performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.size == 2 }
        assertEquals(mapOf("cpu" to "1"), router.ran.last().second)
        onNode(hasText("cpu1") and hasAnyAncestor(hasTestTag("field-cpu"))).assertExists()
        onNodeWithText("Stop").performClick()
    }

    @Test
    fun theProtocolOfTheBandwidthTestStandsAsRadioButtons() = runDesktopComposeUiTest(1100, 700) {
        val test = MenuPath.parse("tool/bandwidth-test")
        val schema = MenuSchema(test, NodeKind.Command, emptyList(), emptyList(), listOf(Argument("address", ValueType.Text()), Argument("protocol", ValueType.Enumeration(closed = true), listOf("udp", "tcp"))))
        show(FakeRouter(schemas = mapOf(test.toString() to schema), output = mapOf(test.toString() to emptyList<Item>())), MenuEntry("Bandwidth Test", test.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-protocol")).fetchSemanticsNodes().isNotEmpty() }
        onNode(hasText("udp") and hasAnyAncestor(hasTestTag("field-protocol"))).assertIsSelected()
        onNode(hasText("tcp") and hasAnyAncestor(hasTestTag("field-protocol"))).performClick()
        waitForIdle()
        onNode(hasText("tcp") and hasAnyAncestor(hasTestTag("field-protocol"))).assertIsSelected()
        onNode(hasText("udp") and hasAnyAncestor(hasTestTag("field-protocol"))).assertIsNotSelected()
    }

    @Test
    fun theFormAndTheResultsOfACommandShareItsWindowByABarThatMoves() = runDesktopComposeUiTest(1100, 700) {
        val test = MenuPath.parse("tool/bandwidth-test")
        val names = listOf("address", "protocol", "local-udp-tx-size", "remote-udp-tx-size", "direction", "connection-count", "local-tx-speed", "remote-tx-speed", "random-data", "user", "password")
        val schema = MenuSchema(test, NodeKind.Command, emptyList(), emptyList(), names.map { Argument(it, ValueType.Text()) })
        val rounds = listOf(Item("", mapOf("status" to "running", "tx-current" to "9000000", "rx-current" to "1000000", ".section" to "0")))
        show(FakeRouter(schemas = mapOf(test.toString() to schema), output = mapOf(test.toString() to rounds)), MenuEntry("Bandwidth Test", test.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("split-bar")).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        fun form() = onNodeWithTag("command-fields").fetchSemanticsNode().size.height
        fun results() = onNodeWithTag("command-results").fetchSemanticsNode().size.height
        fun drag(by: Float) = onNodeWithTag("split-bar").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, by))
            release()
        }
        val start = form()
        drag(80f)
        waitForIdle()
        assertTrue(form() - start in 40..90, "the form took the distance of the drag: $start to ${form()}")
        drag(3000f)
        waitForIdle()
        assertTrue(results() >= 60 && form() > start, "the results keep their least: ${results()}")
        drag(-3000f)
        waitForIdle()
        assertTrue(form() in 60..90, "the form keeps its least: ${form()}")
        onNodeWithTag("split-bar").performMouseInput { doubleClick() }
        waitForIdle()
        assertEquals(start, form(), "a double tap on the bar gives the height of the content back")
        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("running")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("command-count").assertTextEquals("1 item")
    }

    @Test
    fun aFlowTorchNoLongerReportsStaysWithoutItsRates() = runDesktopComposeUiTest(1100, 700) {
        val torch = MenuPath.parse("tool/torch")
        val schema = MenuSchema(torch, NodeKind.Command, emptyList(), emptyList(), listOf(Argument("interface", ValueType.Text())))
        fun flow(section: String, protocol: String, source: String, tx: String) =
            Item("", mapOf("ip-protocol" to protocol, "src-address" to source, "tx" to tx, "rx" to "0", "tx-packets" to "1", "rx-packets" to "0", ".section" to section))
        val tables = listOf(flow("1", "tcp", "192.0.2.10", "127280"), flow("1", "udp", "192.0.2.11", "2720"), flow("2", "tcp", "192.0.2.10", "64000"))
        show(FakeRouter(schemas = mapOf(torch.toString() to schema), output = mapOf(torch.toString() to tables)), MenuEntry("Torch", torch.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Start")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("64.0 kbps")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, onAllNodes(hasText("192.0.2.11")).fetchSemanticsNodes().size, "the udp flow keeps its line")
        assertEquals(0, onAllNodes(hasText("2.7 kbps")).fetchSemanticsNodes().size, "without the rate the first table gave")
        assertEquals(1, onAllNodes(hasText("Total Tx Rate: 64.0 kbps")).fetchSemanticsNodes().size)
        picture("window-torch-lingering")
    }

    @Test
    fun theFlowsOfTorchFadeWhenItFallsQuiet() = runDesktopComposeUiTest(1100, 700) {
        val torch = MenuPath.parse("tool/torch")
        val schema = MenuSchema(torch, NodeKind.Command, emptyList(), emptyList(), listOf(Argument("interface", ValueType.Text())))
        val table = listOf(Item("", mapOf("ip-protocol" to "tcp", "src-address" to "192.0.2.10", "tx" to "127280", "rx" to "0", "tx-packets" to "1", "rx-packets" to "0", ".section" to "1")))
        show(FakeRouter(schemas = mapOf(torch.toString() to schema), output = mapOf(torch.toString() to table), endless = true), MenuEntry("Torch", torch.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Start")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("127.2 kbps")).fetchSemanticsNodes().isNotEmpty() }
        mainClock.advanceTimeBy(2_500)
        assertEquals(0, onAllNodes(hasText("127.2 kbps")).fetchSemanticsNodes().size, "a rate two seconds old is no rate of now")
        assertEquals(1, onAllNodes(hasText("192.0.2.10")).fetchSemanticsNodes().size)
        mainClock.advanceTimeBy(3_000)
        assertEquals(0, onAllNodes(hasText("192.0.2.10")).fetchSemanticsNodes().size)
        onNodeWithText("Stop").performClick()
    }

    @Test
    fun theHopsOfTracerouteAreNumbered() = runDesktopComposeUiTest(1100, 700) {
        val traceroute = MenuPath.parse("tool/traceroute")
        val schema = MenuSchema(traceroute, NodeKind.Command, emptyList(), emptyList(), listOf(Argument("address", ValueType.Text())))
        val hops = listOf("192.0.2.1", "198.51.100.1", "203.0.113.9").map { Item("", mapOf("address" to it, "loss" to "0", ".section" to "0")) }
        show(FakeRouter(schemas = mapOf(traceroute.toString() to schema), output = mapOf(traceroute.toString() to hops)), MenuEntry("Traceroute", traceroute.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Start")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("203.0.113.9")).fetchSemanticsNodes().isNotEmpty() }
        for (hop in listOf("1", "2", "3")) assertEquals(1, onAllNodes(hasText(hop)).fetchSemanticsNodes().size, "hop $hop")
        assertEquals(0, onAllNodes(hasText("4")).fetchSemanticsNodes().size)
    }

    @Test
    fun thePortOfTracerouteWaitsForUdp() = runDesktopComposeUiTest(1100, 700) {
        val traceroute = MenuPath.parse("tool/traceroute")
        val arguments = listOf(
            Argument("address", ValueType.Text()), Argument("protocol", ValueType.Enumeration(closed = true), listOf("icmp", "udp")),
            Argument("port", ValueType.Integer(0, 65535), optional = true),
        )
        show(FakeRouter(schemas = mapOf(traceroute.toString() to MenuSchema(traceroute, NodeKind.Command, emptyList(), emptyList(), arguments))), MenuEntry("Traceroute", traceroute.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("set-port")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("set-port").assertIsNotEnabled()
        onNode(hasText("udp", ignoreCase = true) and hasAnyAncestor(hasTestTag("field-protocol"))).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("set-port") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aBandwidthTestIsDrawn() = runDesktopComposeUiTest(1100, 700) {
        val test = MenuPath.parse("tool/bandwidth-test")
        val arguments = listOf(
            Argument("address", ValueType.Text()), Argument("direction", ValueType.Text()), Argument("connection-count", ValueType.Integer(1, 255)),
            Argument("user", ValueType.Text()),
        )
        val schema = MenuSchema(test, NodeKind.Command, emptyList(), emptyList(), arguments)
        val rounds = listOf(9_000_000 to 1_000_000, 9_400_000 to 1_200_000).mapIndexed { round, (tx, rx) ->
            Item("", mapOf("status" to "running", "tx-current" to "$tx", "rx-current" to "$rx", ".section" to "$round"))
        }
        show(FakeRouter(schemas = mapOf(test.toString() to schema), output = mapOf(test.toString() to rounds)), MenuEntry("Bandwidth Test", test.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Start")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, onAllNodes(hasTestTag("group-line")).fetchSemanticsNodes().size)
        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Tx: 9.4 Mbps")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, onAllNodes(hasText("Rx: 1.2 Mbps")).fetchSemanticsNodes().size)
    }

    @Test
    fun trafficOfAnInterfaceIsDrawn() = runDesktopComposeUiTest(1100, 700) {
        val monitor = MenuPath.parse("interface/monitor-traffic")
        val schema = MenuSchema(monitor, NodeKind.Command, emptyList(), emptyList(), listOf(Argument("interface", ValueType.Text())))
        val rounds = listOf(102400 to 151880, 75040 to 66400, 61456 to 24320).mapIndexed { round, (rx, tx) ->
            Item("", mapOf("name" to "ether1", "rx-bits-per-second" to "$rx", "tx-bits-per-second" to "$tx", ".section" to "$round"))
        }
        val router = FakeRouter(schemas = mapOf(monitor.toString() to schema), output = mapOf(monitor.toString() to rounds))
        show(router, MenuEntry("Traffic", monitor.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Start")).fetchSemanticsNodes().isNotEmpty() }
        onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-interface"))).performTextInput("ether1")
        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("ether1 Tx: 24.3 kbps")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, onAllNodes(hasText("61.4 kbps")).fetchSemanticsNodes().size, "the table keeps only the last round, rates written as RouterOS writes them")
        picture("window-traffic")
    }

    @Test
    fun aMenuTheRouterLacksSaysSo() = runDesktopComposeUiTest(1100, 700) {
        show(FakeRouter(lacking = setOf("interface/vxlan")), MenuEntry("VXLAN", "interface/vxlan"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("This router has no /interface vxlan", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("no such command prefix", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aMenuHiddenByPoliciesNamesThem() = runDesktopComposeUiTest(1100, 700) {
        show(FakeRouter(lacking = setOf("tool/torch"), policies = setOf("read", "write")), MenuEntry("Torch", "tool/torch"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("lacks the policies test, sniff", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("This router has no", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aLongLabelIsNotCut() = runDesktopComposeUiTest(1100, 700) {
        val nd = MenuPath.parse("ipv6/nd/settings")
        val schema = MenuSchema(
            nd, NodeKind.Settings, emptyList(), listOf("print", "set"),
            listOf(Argument("managed-address-configuration", ValueType.YesNo), Argument("ra-interval", ValueType.Text())),
        )
        val router = FakeRouter(mapOf(nd.toString() to listOf(Item("", mapOf("managed-address-configuration" to "false", "ra-interval" to "3m20s-10m")))), schemas = mapOf(nd.toString() to schema))
        show(router, MenuEntry("ND Settings", nd.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Managed Address Configuration")).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText("Managed Address Configuration").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertFalse(layouts.single().hasVisualOverflow)
        picture("window-long-label")
    }

    @Test
    fun theFieldsGoInTheOrderTheRouterPrintsThem() = runDesktopComposeUiTest(1100, 700) {
        val settings = MenuPath.parse("tool/sms")
        val schema = MenuSchema(
            settings, NodeKind.Settings, emptyList(), listOf("print", "set"),
            listOf(Argument("channel", ValueType.Text()), Argument("port", ValueType.Text()), Argument("receive-enabled", ValueType.Text()), Argument("secret", ValueType.Text())),
        )
        val values = mapOf("receive-enabled" to "no", "port" to "lte1", "channel" to "0")
        val router = FakeRouter(mapOf(settings.toString() to listOf(Item("", values))), schemas = mapOf(settings.toString() to schema))
        show(router, MenuEntry("SMS", settings.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Channel")).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        val tops = listOf("Receive Enabled", "Port", "Channel", "Secret").map { onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        assertEquals(4, tops.distinct().size)
    }

    @Test
    fun theFieldsOfAnElementGoInTheOrderTheRouterPrintsThem() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val packing = MenuPath.parse("ip/packing")
        val schema = MenuSchema(
            packing, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"),
            listOf(
                Argument("aggregated-size", ValueType.Integer()), Argument("interface", ValueType.Text()), Argument("packing", ValueType.Text()),
                Argument("unpacking", ValueType.Text()),
            ),
        )
        val rule = Item("*1", mapOf("interface" to "ether1", "packing" to "simple", "unpacking" to "simple", "aggregated-size" to "1442"))
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(mapOf(packing.toString() to listOf(rule)), schemas = mapOf(packing.toString() to schema)), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.openItem("Packing", packing, "*1")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Aggregated Size")).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        val tops = listOf("Interface", "Packing", "Unpacking", "Aggregated Size").map { onAllNodes(hasText(it)).onLast().fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        assertEquals(4, tops.distinct().size)

        tab.workspace.windows.toList().forEach(tab.workspace::close)
        tab.open(MenuEntry("Packing", packing.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("1442")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("New").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-interface")).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        val fresh = listOf("interface", "packing", "unpacking", "aggregated-size").map { onNodeWithTag("field-$it").fetchSemanticsNode().boundsInRoot.top }
        assertEquals(fresh.sorted(), fresh)
        assertEquals(4, fresh.distinct().size)

        onAllNodes(hasText("Cancel")).onLast().performClick()
        waitForIdle()
        tab.openItem("Packing", packing, "*1")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Copy")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("Copy")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { tab.items.values.any { it.id == null && it.prefill.isNotEmpty() } }
        assertEquals(listOf("interface", "packing", "unpacking", "aggregated-size"), tab.items.values.single { it.id == null && it.prefill.isNotEmpty() }.prefill.keys.toList())
    }

    @Test
    fun theMonitorOfSettingsRunsWithoutAnArgument() = runDesktopComposeUiTest(1100, 700) {
        val incoming = MenuPath.parse("radius/incoming")
        val schema = MenuSchema(
            incoming, NodeKind.Settings, emptyList(), listOf("monitor", "print", "reset-counters", "set"),
            listOf(Argument("accept", ValueType.YesNo), Argument("port", ValueType.Integer(1, 65535))),
        )
        val counters = Item("", mapOf("requests" to "5", "bad-requests" to "0", "acks" to "4", "naks" to "1"))
        val router = FakeRouter(
            mapOf(incoming.toString() to listOf(Item("", mapOf("accept" to "true", "port" to "3799")))),
            schemas = mapOf(incoming.toString() to schema),
            output = mapOf("radius/incoming/monitor" to listOf(counters)),
        )
        show(router, MenuEntry("Incoming", incoming.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Status")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("Status")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Naks")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(mapOf<String, String>(), router.ran.single { it.first == "radius/incoming/monitor" }.second)
        assertTrue(onAllNodes(hasText("4")).fetchSemanticsNodes().isNotEmpty(), "the acks the monitor gave")
    }

    @Test
    fun resetReturnsTheSettingsToTheDefaultsOfTheRouter() = runDesktopComposeUiTest(1100, 700) {
        val client = MenuPath.parse("system/ntp/client")
        val schema = MenuSchema(
            client, NodeKind.Settings, listOf("servers"), listOf("print", "reset", "set"),
            listOf(Argument("enabled", ValueType.YesNo), Argument("servers", ValueType.Many(ValueType.Text()))),
        )
        val router = FakeRouter(mapOf(client.toString() to listOf(Item("", mapOf("enabled" to "true", "servers" to "192.0.2.123")))), schemas = mapOf(client.toString() to schema))
        show(router, MenuEntry("NTP Client", client.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Reset")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Reset").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Return the fields of NTP Client to the defaults of RouterOS?")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(emptyList(), router.ran.toList(), "nothing runs before the answer")
        picture("window-reset")
        onAllNodes(hasText("Reset")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.isNotEmpty() }
        assertEquals(listOf("system/ntp/client/reset" to emptyMap<String, String>()), router.ran.toList())
    }

    @Test
    fun aBuiltInElementResetsToTheDefaultsOfTheRouter() = runDesktopComposeUiTest(1100, 700) {
        val types = MenuPath.parse("queue/type")
        val arguments = listOf(Argument("name", ValueType.Text()), Argument("kind", ValueType.Text()), Argument("pfifo-limit", ValueType.Integer()))
        val schema = MenuSchema(types, NodeKind.List, emptyList(), listOf("add", "print", "remove", "reset", "set"), arguments)
        val rows = listOf(
            Item("*1", mapOf("name" to "default", "kind" to "pfifo", "pfifo-limit" to "40", "default" to "true")),
            Item("*2", mapOf("name" to "rd-test-type", "kind" to "pfifo", "pfifo-limit" to "10")),
        )
        val router = FakeRouter(mapOf(types.toString() to rows), schemas = mapOf(types.toString() to schema))
        show(router, MenuEntry("Queue Types", types.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test-type")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("rd-test-type").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Cancel")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("Reset")).fetchSemanticsNodes().isEmpty(), "no Reset for a type of one's own")
        onNodeWithText("Cancel").performClick()
        onAllNodes(hasText("default"))[0].performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Reset")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Reset").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Reset")).fetchSemanticsNodes().size > 1 }
        onAllNodes(hasText("Reset")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.isNotEmpty() }
        assertEquals(listOf("queue/type/reset" to mapOf("numbers" to "*1")), router.ran.toList())
    }

    @Test
    fun rebootAsksBeforeItRuns() = runDesktopComposeUiTest(1100, 700) {
        val reboot = MenuPath.parse("system/reboot")
        val router = FakeRouter(schemas = mapOf(reboot.toString() to MenuSchema(reboot, NodeKind.Command, emptyList(), emptyList(), emptyList())))
        show(router, MenuEntry("Reboot", reboot.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Start")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Run Reboot on the router?")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(emptyList(), router.ran.toList(), "nothing runs before the answer")
        onNodeWithText("Cancel").performClick()
        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Run Reboot on the router?")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("Reboot")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.isNotEmpty() }
        assertEquals(listOf("system/reboot" to emptyMap<String, String>()), router.ran.toList())
    }

    @Test
    fun resetConfigurationShowsItsFieldsAndAsksBeforeItRuns() = runDesktopComposeUiTest(1100, 700) {
        val reset = MenuPath.parse("system/reset-configuration")
        val flags = listOf("caps-mode", "force-v6-to-v7-configuration-upgrade", "keep-apps", "keep-users", "no-defaults", "shutdown", "skip-backup")
        val arguments = flags.map { Argument(it, ValueType.YesNo) } + Argument("run-after-reset", ValueType.Text())
        val router = FakeRouter(schemas = mapOf(reset.toString() to MenuSchema(reset, NodeKind.Command, emptyList(), emptyList(), arguments)))
        show(router, MenuEntry("Reset Configuration", reset.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Start")).fetchSemanticsNodes().isNotEmpty() }
        val top = listOf("Keep Users", "CAPS Mode", "Skip Backup", "Force v6 to v7 Configuration Upgrade").map { onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(top.sorted(), top)
        assertTrue(top.last() < onNodeWithText("Start").fetchSemanticsNode().boundsInRoot.top)
        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Run Reset Configuration on the router?")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(emptyList(), router.ran.toList(), "nothing runs before the answer")
        onAllNodes(hasText("Reset Configuration")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.isNotEmpty() }
        assertEquals(listOf("system/reset-configuration" to emptyMap<String, String>()), router.ran.toList())
    }

    @Test
    fun theLogFiltersByOneTopicOfMany() = runDesktopComposeUiTest(1100, 700) {
        val log = listOf(
            Item("*1", mapOf("time" to "10:00:01", "topics" to "system,info", "message" to "router rebooted")),
            Item("*2", mapOf("time" to "10:00:02", "topics" to "firewall,info", "message" to "input: in:ether1")),
            Item("*3", mapOf("time" to "10:00:03", "topics" to "system,error,critical", "message" to "login failure")),
        )
        show(FakeRouter(mapOf("log" to log)), MenuEntry("Log", "log"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("3 lines")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("log-topics").performClick()
        onAllNodes(hasText("system")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("2 of 3 lines")).fetchSemanticsNodes().isNotEmpty() }
        picture("window-log")
    }

    private fun SkikoComposeUiTest.service(fields: List<String>) {
        Locale.setDefault(Locale.ENGLISH)
        val services = MenuPath.parse("ip/service")
        val schema = MenuSchema(services, NodeKind.List, emptyList(), listOf("print", "set"), fields.map { Argument(it, ValueType.Text()) })
        val api = Item("*1", mapOf("name" to "api", "port" to "8728"))
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(mapOf(services.toString() to listOf(api)), schemas = mapOf(services.toString() to schema)), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.openItem("Services", services, "*1")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Available From")).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        assertEquals(1, onAllNodes(hasText("Available From")).fetchSemanticsNodes().size)
        assertTrue(onAllNodes(hasText("Other")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aPropertyTheRouterPrintsAndNothingNamesStandsReadOnlyOnOther() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val services = MenuPath.parse("ip/service")
        val schema = MenuSchema(services, NodeKind.List, emptyList(), listOf("print", "set"), listOf(Argument("port", ValueType.Text()), Argument("available-from", ValueType.Text())))
        val api = Item("*1", mapOf("name" to "api", "port" to "8728", "x-patch-level" to "6"))
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(mapOf(services.toString() to listOf(api)), schemas = mapOf(services.toString() to schema)), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.openItem("Services", services, "*1")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Other")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Other").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("X Patch Level")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("6")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodes(hasTestTag("field-x-patch-level")).fetchSemanticsNodes().isEmpty(), "it is shown, not edited")
        picture("dialog-unlisted")
    }

    // before 7.24 the field is address, which stands in place of available-from
    @Test
    fun anOldNameOfAFieldStandsInPlaceOfTheNewOne() = runDesktopComposeUiTest(1100, 700) {
        service(listOf("address", "port"))
        onNodeWithTag("field-address").assertExists()
    }

    @Test
    fun anOldNameIsNotShownBesideTheNewOne() = runDesktopComposeUiTest(1100, 700) {
        service(listOf("address", "available-from", "port"))
        onNodeWithTag("field-available-from").assertExists()
        assertTrue(onAllNodes(hasTestTag("field-address")).fetchSemanticsNodes().isEmpty())
    }
}
