package com.vasmarfas.networkhelm.ui.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.packs.PackTab
import com.vasmarfas.networkhelm.packs.PackWindow
import com.vasmarfas.networkhelm.packs.bundledMenu
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.TextStore
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LayoutTest {

    @Test
    fun windowsComeBackForTheSameRouterAtAnyAddress() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val packs = bundledPacks().values
        val tables = TableLayouts(storage)
        val first = RouterTab(FakeRouter(), "192.0.2.1", packs, storage, tables)
        var shown by mutableStateOf(first)
        setContent { NetworkHelmTheme { MainScreen(shown, onDisconnect = {}) } }
        waitForIdle()
        first.open(MenuEntry("Firewall", "ip/firewall/nat"))
        first.open(MenuEntry("Addresses", "ip/address"))
        val firewall = first.workspace.windows.first()
        firewall.position = DpOffset(120.dp, 60.dp)
        first.specs.getValue(firewall.id).selected = 2
        waitUntil(timeoutMillis = 5_000) { runBlocking { storage.read("workspace-rd-test-id.json") }?.contains("\"x\":120.0") == true }

        val second = RouterTab(FakeRouter(), "router.lan", packs, storage, tables)
        shown = second
        waitUntil(timeoutMillis = 5_000) { second.workspace.windows.size == 2 }
        val restored = second.workspace.windows.first()
        val spec = assertNotNull(second.specs[restored.id])
        assertEquals("Firewall", spec.title)
        assertEquals(2, spec.selected)
        assertEquals(DpOffset(120.dp, 60.dp), restored.position)
        assertTrue(spec.tabs.first().shown != null, "a restored pack window keeps the pack's columns")

        shown = first
        waitForIdle()
        shown = second
        waitForIdle()
        assertEquals(listOf("Firewall", "Addresses"), first.workspace.windows.map { it.title })
        assertEquals(listOf("Firewall", "Addresses"), second.workspace.windows.map { it.title })
    }

    @Test
    fun aWindowSavedTwiceComesBackOnce() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val addresses = SavedWindow("Addresses", listOf(SavedTab("Addresses", "ip/address")), 0, 40f, 40f, 760f, 440f)
        val nat = SavedWindow("Firewall", listOf(SavedTab("NAT", "ip/firewall/nat")), 0, 80f, 80f, 760f, 440f)
        runBlocking {
            storage.write("workspace-rd-test-id.json", Json.encodeToString(ListSerializer(SavedWindow.serializer()), listOf(addresses, nat, addresses, addresses)))
        }
        val tab = RouterTab(FakeRouter(), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme { MainScreen(tab, onDisconnect = {}) } }
        waitUntil(timeoutMillis = 5_000) { tab.workspace.windows.isNotEmpty() }
        waitForIdle()
        assertEquals(listOf("Addresses", "Firewall"), tab.workspace.windows.map { it.title })
    }

    @Test
    fun namedWorkspacesKeepTheirOwnWindows() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val packs = bundledPacks().values
        val tables = TableLayouts(storage)
        val first = RouterTab(FakeRouter(), "192.0.2.1", packs, storage, tables)
        var shown by mutableStateOf(first)
        setContent { NetworkHelmTheme { MainScreen(shown, onDisconnect = {}) } }
        waitForIdle()
        first.open(MenuEntry("Addresses", "ip/address"))
        waitUntil(timeoutMillis = 5_000) { runBlocking { storage.read("workspace-rd-test-id.json") }?.contains("ip/address") == true }

        onNodeWithTag("windows").performClick()
        onNodeWithText("New workspace…").performClick()
        onNodeWithTag("workspace-name").performTextInput("Фаервол")
        onNodeWithText("Create").performClick()
        waitUntil(timeoutMillis = 5_000) { first.workspaceName == "Фаервол" && first.workspace.windows.isEmpty() }
        first.open(MenuEntry("Firewall", "ip/firewall/filter"))
        waitUntil(timeoutMillis = 5_000) { runBlocking { storage.read("workspaces-rd-test-id.json") }?.contains("ip/firewall/filter") == true }

        onNodeWithTag("windows").performClick()
        onNodeWithText("Main workspace").performClick()
        waitUntil(timeoutMillis = 5_000) { first.workspaceName.isEmpty() && first.workspace.windows.map { it.title } == listOf("Addresses") }
        onNodeWithTag("windows").performClick()
        waitForIdle()
        ImageIO.write(captureToImage().toAwtImage(), "png", File(File("build/screenshots").also { it.mkdirs() }, "workspaces.png"))
        onNodeWithText("Фаервол").performClick()
        waitUntil(timeoutMillis = 5_000) { first.workspace.windows.map { it.title } == listOf("Firewall") }

        val second = RouterTab(FakeRouter(), "192.0.2.1", packs, storage, tables)
        shown = second
        waitUntil(timeoutMillis = 5_000) { second.workspace.windows.map { it.title } == listOf("Firewall") }
        assertEquals("Фаервол" to listOf("Фаервол"), second.workspaceName to second.workspaceNames)

        onNodeWithTag("windows").performClick()
        onNodeWithText("Remove workspace Фаервол").performClick()
        waitUntil(timeoutMillis = 5_000) { second.workspaceName.isEmpty() && second.workspace.windows.map { it.title } == listOf("Addresses") }
        assertEquals(emptyList(), second.workspaceNames)
    }

    @Test
    fun aWorkspaceIsCopiedAndRenamed() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme { MainScreen(tab, onDisconnect = {}) } }
        waitForIdle()
        tab.open(MenuEntry("Addresses", "ip/address"))
        waitUntil(timeoutMillis = 5_000) { tab.workspace.windows.isNotEmpty() }

        onNodeWithTag("windows").performClick()
        onNodeWithText("Copy workspace…").performClick()
        onNodeWithTag("workspace-name").performTextInput("Сети")
        onNodeWithText("Create").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.workspaceName == "Сети" }
        assertEquals(listOf("Addresses"), tab.workspace.windows.map { it.title })

        onNodeWithTag("windows").performClick()
        onNodeWithText("Rename workspace Сети…").performClick()
        onNodeWithTag("workspace-name").performTextReplacement("Адреса")
        onNodeWithText("Rename").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.workspaceName == "Адреса" }
        assertEquals(listOf("Адреса"), tab.workspaceNames)
        assertEquals(listOf("Addresses"), tab.workspace.windows.map { it.title })

        onNodeWithTag("windows").performClick()
        onNodeWithText("Main workspace").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.workspaceName.isEmpty() && tab.workspace.windows.map { it.title } == listOf("Addresses") }
    }

    @Test
    fun theMenuOfARouterIsReadOnceForItsTabAndAgainAfterAReadTheLinkCutShort() {
        val router = FakeRouter()
        var reads = 0
        var lost = false
        val counting = object : DeviceSession by router {
            override suspend fun packs(store: TextStore?): List<Pack> {
                reads++
                if (lost) throw DeviceException(ErrorCategory.ConnectionLost, "the link is lost")
                return router.packs(store)
            }
        }
        val storage = MemoryStorage()
        runBlocking {
            val tab = RouterTab(counting, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
            tab.openMenu()
            tab.openMenu()
            assertEquals(1, reads, "a return to the tab reads nothing")
            tab.reloadStructure()
            assertEquals(2, reads)

            lost = true
            val cut = RouterTab(counting, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
            cut.openMenu()
            lost = false
            cut.openMenu()
            cut.openMenu()
            assertEquals(4, reads, "the read that the link cut short is made again, once")
        }
    }

    @Test
    fun aWindowLeavesOutTheTabsItsRouterVersionLacks() {
        val storage = MemoryStorage()
        fun tabs(version: String): List<String> {
            val tab = RouterTab(FakeRouter(version = version), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
            tab.open(MenuEntry("WiFi", "interface/wifi"))
            return tab.specs.values.single().tabs.map { it.title }
        }
        assertTrue("Network" !in tabs("7.21.3"))
        assertTrue("Network" in tabs("7.99beta1"))
    }

    @Test
    fun aWindowLeavesOutTheTabsItsRouterLacks() {
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(lacking = setOf("interface/vxlan", "interface/veth")), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        tab.open(MenuEntry("Bonding", "interface/bonding"))
        val spec = tab.specs.values.single()
        runBlocking { tab.expand(spec) }
        val titles = spec.tabs.map { it.title }
        assertTrue("VXLAN" !in titles && "VETH" !in titles && "VLAN" in titles, titles.toString())
        assertEquals("Bonding", spec.tabs[spec.selected].title)
    }

    private fun directory(path: String, vararg children: String) = path to MenuSchema(MenuPath.parse(path), NodeKind.Directory, children.toList(), emptyList(), emptyList())

    private fun node(path: String, kind: NodeKind, vararg children: String) = path to MenuSchema(MenuPath.parse(path), kind, children.toList(), emptyList(), emptyList())

    @Test
    fun aWindowTakesTheListsAndSettingsTheRouterHasBesideItsTabs() {
        val schemas = mapOf(
            node("interface", NodeKind.List, "ethernet", "vlan", "bridge", "6to4", "tunnel-x", "wizard", "tools-x"),
            node("interface/wizard", NodeKind.Command),
            node("interface/tools-x", NodeKind.Directory, "run"),
            node("interface/tunnel-x", NodeKind.Settings),
        )
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(schemas = schemas), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { tab.loadMenu(bundledMenu()) }
        tab.open(MenuEntry("Interfaces", "interface"))
        val spec = tab.specs.values.single()
        val own = spec.tabs.map { it.title }
        runBlocking { tab.expand(spec) }
        val titles = spec.tabs.map { it.title }
        assertEquals(own, titles.take(own.size), "the tabs of the pack come first")
        assertEquals(listOf("6to4", "Tunnel X"), titles.drop(own.size))
        assertEquals("Interface", spec.tabs[spec.selected].title)
        runBlocking { tab.expand(spec) }
        assertEquals(titles, spec.tabs.map { it.title }, "a tab joins once")
    }

    @Test
    fun aWindowOfOneTabTakesItsOwnListsToo() {
        val storage = MemoryStorage()
        val schemas = mapOf(node("ip/service", NodeKind.List, "webserver"), node("ip/service/webserver", NodeKind.Settings))
        val tab = RouterTab(FakeRouter(schemas = schemas), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        tab.open(MenuEntry("Services", "ip/service"))
        val spec = tab.specs.values.single()
        runBlocking { tab.expand(spec) }
        assertEquals(listOf("Services", "Webserver"), spec.tabs.map { it.title })
    }

    @Test
    fun aRouterThatCannotSayWhatAMenuHoldsGetsNoTabsAndNoEntriesOfItsOwn() {
        val schemas = mapOf(
            node("interface", NodeKind.List, "ethernet", "6to4"),
            directory("", "system"),
            directory("system", "clock", "hardware"),
        )
        val router = FakeRouter(schemas = schemas)
        val old = object : DeviceSession by router {
            override val capabilities = router.capabilities.copy(menuContents = false)
        }
        val storage = MemoryStorage()
        val tab = RouterTab(old, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { tab.loadMenu(bundledMenu()) }
        tab.open(MenuEntry("Interfaces", "interface"))
        val spec = tab.specs.values.single()
        val before = spec.tabs.map { it.title }
        runBlocking { tab.expand(spec) }
        assertEquals(before, spec.tabs.map { it.title })
        assertTrue(tab.menu.orEmpty().flatMap { it.items }.none { it.title == "Hardware" })
    }

    @Test
    fun aMenuTheRouterHasUnderAGroupJoinsItByItsPlaceInTheAlphabet() {
        val schemas = mapOf(
            directory("", "system", "routing"),
            directory("system", "clock", "hardware", "dashboard", "rtrace", "default-configuration", "reboot-now"),
            node("system/hardware", NodeKind.Settings),
            directory("system/dashboard", "settings"),
            node("system/dashboard/settings", NodeKind.Settings),
            node("system/rtrace", NodeKind.Directory),
            directory("system/default-configuration", "script"),
            node("system/default-configuration/script", NodeKind.Command),
            node("system/reboot-now", NodeKind.Command),
            directory("routing", "rule", "fantasy", "stats"),
            directory("routing/stats", "process"),
            node("routing/stats/process", NodeKind.List),
        )
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(schemas = schemas), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { tab.loadMenu(bundledMenu()) }
        val groups = tab.menu.orEmpty().filter { it.items.isNotEmpty() }.associate { it.title to it.items }
        assertEquals(listOf("Clock" to "system/clock", "Dashboard" to "system/dashboard", "Hardware" to "system/hardware"), groups.getValue("System").map { it.title to it.path })
        assertEquals(listOf("Rules" to "routing/rule", "Stats" to "routing/stats"), groups.getValue("Routing").map { it.title to it.path })
    }

    @Test
    fun aWindowOpensWideEnoughForItsTabs() = runDesktopComposeUiTest(1400, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("OSPF", "routing/ospf/instance"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("LSA")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("LSA").assertIsDisplayed()
    }

    @Test
    fun anAutomaticWindowTakesTheListsAroundItsPath() {
        fun directory(path: String, vararg children: String) = path to MenuSchema(MenuPath.parse(path), NodeKind.Directory, children.toList(), emptyList(), emptyList())
        val wireguard = MenuSchema(MenuPath.parse("interface/wireguard"), NodeKind.List, listOf("peers"), listOf("add", "print", "remove", "set"), emptyList())
        val schemas = mapOf(
            directory("routing/isis", "instance", "interface-template", "neighbor"),
            directory("container", "envs", "mounts"),
            directory("system/ntp", "client", "key", "server"),
            "interface/wireguard" to wireguard,
        )
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(schemas = schemas), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        fun tabs(title: String, path: String): List<String> {
            tab.open(MenuEntry(title, path))
            val spec = tab.specs.values.single { it.title == title }
            runBlocking { tab.expand(spec) }
            return spec.tabs.map { it.title }
        }
        assertEquals(listOf("Instance", "Interface Template", "Neighbor"), tabs("ISIS", "routing/isis/instance"))
        assertEquals(listOf("WireGuard", "Peers"), tabs("WireGuard", "interface/wireguard"))
        assertEquals(listOf("Envs", "Mounts"), tabs("Container", "container"))
        assertEquals(listOf("Key"), tabs("NTP Keys", "system/ntp/key"))
        assertEquals(listOf("Identity"), tabs("Identity", "system/identity"))
    }

    @Test
    fun aTopLevelMenuTheMenuDoesNotNameComesInTheGroupAtTheEnd() {
        val root = MenuSchema(MenuPath.Root, NodeKind.Directory, listOf("ip", "gps", "console", "suggestion"), emptyList(), emptyList())
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(schemas = mapOf("" to root)), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { tab.loadMenu(bundledMenu()) }
        val top = tab.menu.orEmpty()
        assertEquals("More", top.last().title)
        assertEquals(listOf("GPS" to "gps", "Suggestion" to "suggestion"), top.last().items.map { it.title to it.path })
        assertTrue(top.dropLast(1).none { it.path == "gps" || it.path == "suggestion" }, "a menu stands once")
        assertTrue(top.flatMap { it.items }.none { it.path == "console" }, "the console is not a window")
    }

    @Test
    fun aWindowOpenedBeforeTheDescriptionsOfTheRouterComeTakesTheirLayout() {
        val menu = listOf("IP", "Gadgets")
        val bundled = Pack(menu = menu, window = PackWindow("Gadgets", listOf(PackTab("Gadgets", "ip/gadget"))), paths = mapOf("ip/gadget" to PackPath(columns = listOf("name"))))
        val described = Pack(menu = menu, window = PackWindow("Gadgets", listOf(PackTab("Gadgets", "ip/gadget"))), paths = mapOf("ip/gadget" to PackPath(columns = listOf("address", "name"))))
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(made = listOf(described)), "192.0.2.1", listOf(bundled), storage, TableLayouts(storage))
        tab.open(MenuEntry("Gadgets", "ip/gadget"))
        assertEquals(listOf("name"), tab.specs.values.single().tabs.single().shown?.columns)
        runBlocking { tab.loadMenu(bundledMenu()) }
        assertEquals(listOf("address", "name"), tab.specs.values.single().tabs.single().shown?.columns)
    }

    @Test
    fun theControllerIsAGroupAndGrowsWithWhatCmrGains() {
        val schemas = mapOf(
            directory("", "cmr", "system"),
            directory("system", "clock"),
            directory("cmr", "alert", "device", "topology", "wifi"),
            node("cmr/alert", NodeKind.List),
            node("cmr/device", NodeKind.List),
            node("cmr/topology", NodeKind.List),
            node("cmr/wifi", NodeKind.List),
        )
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(schemas = schemas, packages = setOf("routeros", "cmr")), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { tab.loadMenu(bundledMenu()) }
        val top = tab.menu.orEmpty()
        assertEquals("Controller", top.first().title)
        assertEquals(
            listOf("Dashboard" to "cmr/device", "Devices" to "cmr/device", "Alerts" to "cmr/alert", "Topology" to "cmr/topology", "WiFi" to "cmr/wifi", "Settings" to "cmr"),
            top.first().items.map { it.title to it.path },
        )
        assertTrue(top.none { it.title == "More" }, "cmr is no menu the menu does not name")
    }

    @Test
    fun theControllerStaysOutOfARouterWithoutTheCmrPackageThoughItHasTheMenuOfTheClient() {
        val schemas = mapOf(directory("", "cmr", "system"), directory("system", "clock"), directory("cmr", "client"), node("cmr/client", NodeKind.Settings))
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(schemas = schemas), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { tab.loadMenu(bundledMenu()) }
        val top = tab.menu.orEmpty()
        assertTrue(top.none { it.title == "Controller" })
        assertTrue(top.none { it.title == "More" })
        assertEquals("cmr/client", top.single { it.title == "System" }.items.single { it.title == "CMR Client" }.path)
    }

    private fun capsMan(menu: List<String>) = Pack(menu = menu, window = PackWindow("CAPsMAN", listOf(PackTab("Provisioning", "native/88/154"))), paths = mapOf("native/88/154" to PackPath(columns = listOf("name"))))

    private val capsManRouter = mapOf(
        directory("", "caps-man", "interface"),
        directory("caps-man", "interface"),
        node("caps-man/interface", NodeKind.List),
        directory("interface", "wireless"),
        node("interface/wireless", NodeKind.List),
    )

    @Test
    fun anEntryOfTheTopLevelThatTheRoutersDescriptionPutsIntoAGroupStandsOnlyThere() {
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(schemas = capsManRouter, made = listOf(capsMan(listOf("Wireless", "CAPsMAN")))), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { tab.loadMenu(bundledMenu()) }
        val top = tab.menu.orEmpty()
        assertTrue(top.none { it.title == "CAPsMAN" })
        assertEquals("native/88/154", top.single { it.title == "Wireless" }.items.single { it.title == "CAPsMAN" }.path)
    }

    @Test
    fun aWindowThatAButtonOfAGroupedEntryOpensLeavesTheEntryOnlyInItsGroup() {
        val scanner = Pack(
            menu = listOf("Wireless", "CAPsMAN", "CAPs Scanner"),
            window = PackWindow("CAPs Scanner", listOf(PackTab("CAPs Scanner", "native/88/153/q4"))),
            paths = mapOf("native/88/153/q4" to PackPath(columns = listOf("address"))),
            hidden = true,
        )
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(schemas = capsManRouter, made = listOf(capsMan(listOf("Wireless", "CAPsMAN")), scanner)), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { tab.loadMenu(bundledMenu()) }
        val top = tab.menu.orEmpty()
        assertTrue(top.none { it.title == "CAPsMAN" })
        assertEquals(listOf("native/88/154"), top.single { it.title == "Wireless" }.items.filter { it.title == "CAPsMAN" }.map { it.path })
    }

    @Test
    fun anEntryTheRoutersDescriptionKeepsAtTheTopLevelStays() {
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(schemas = capsManRouter, made = listOf(capsMan(listOf("CAPsMAN")))), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { tab.loadMenu(bundledMenu()) }
        assertEquals(1, tab.menu.orEmpty().count { it.title == "CAPsMAN" })
    }

    @Test
    fun anEntryOfTheMenuWhoseWindowTheRouterDescribesAsTablesOfTheObjectModelOpensThatWindow() {
        val schemas = mapOf(directory("", "cmr"), directory("cmr", "device", "wifi"), node("cmr/device", NodeKind.List), node("cmr/wifi", NodeKind.List))
        val wifi = Pack(
            menu = listOf("Controller", "WiFi"),
            window = PackWindow("WiFi", listOf(PackTab("Interfaces", "native/167/34"), PackTab("Radios", "native/167/33"))),
            paths = mapOf("native/167/34" to PackPath(columns = listOf("interface")), "native/167/33" to PackPath(columns = listOf("labels"))),
        )
        val ports = Pack(menu = listOf("Controller", "Ports"), window = PackWindow("Ports", listOf(PackTab("Ports", "native/167/32"))), paths = mapOf("native/167/32" to PackPath(columns = listOf("interface"))))
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(schemas = schemas, made = listOf(wifi, ports), packages = setOf("routeros", "cmr")), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { tab.loadMenu(bundledMenu()) }
        val items = tab.menu.orEmpty().first { it.title == "Controller" }.items
        assertEquals("native/167/34", items.single { it.title == "WiFi" }.path)
        assertEquals("native/167/32", items.single { it.title == "Ports" }.path)
        tab.open(items.single { it.title == "WiFi" })
        assertEquals(listOf("Interfaces", "Radios"), tab.specs.values.single().tabs.map { it.title })
    }

    @Test
    fun aWindowOfTheObjectModelWithoutAGroupIsAnEntryOfTheTopLevelAndNotAGroupOfItself() {
        val about = Pack(menu = listOf("Patch About"), window = PackWindow("Patch About", listOf(PackTab("Patch About", "native/24/2"))), paths = mapOf("native/24/2" to PackPath(columns = listOf("uptime"))))
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(schemas = mapOf(directory("", "cmr")), made = listOf(about)), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { tab.loadMenu(bundledMenu()) }
        val entry = tab.menu.orEmpty().single { it.title == "Patch About" }
        assertEquals("native/24/2", entry.path)
        assertEquals(emptyList(), entry.items)
    }

    @Test
    fun anEntryWithoutAMenuOfTheConsoleStandsWhereThePriorityOfTheRoutersDescriptionPutsIt() {
        val schemas = mapOf(
            directory("", "interface", "ppp"),
            directory("interface", "bridge", "mesh"),
            node("interface/bridge", NodeKind.List),
            node("interface/mesh", NodeKind.List),
            directory("ppp", "secret"),
            node("ppp/secret", NodeKind.List),
        )
        val quick = Pack(menu = listOf("Quick Set"), window = PackWindow("Quick Set", listOf(PackTab("Quick Set", "native/120/6"))), paths = mapOf("native/120/6" to PackPath(columns = listOf("mode"))), prio = 10)
        val vlan = Pack(menu = listOf("Switch", "VLAN"), window = PackWindow("VLAN", listOf(PackTab("VLAN", "native/20/53"))), paths = mapOf("native/20/53" to PackPath(columns = listOf("vlan-id"))), prio = 101)
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(schemas = schemas, made = listOf(quick, vlan)), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { tab.loadMenu(bundledMenu()) }
        val titles = tab.menu.orEmpty().map { it.title }
        assertEquals(listOf("Quick Set", "Interfaces", "Bridge", "PPP", "Switch", "Mesh"), titles.take(6))
        assertEquals("native/20/53", tab.menu.orEmpty().single { it.title == "Switch" }.items.single().path)
    }

    @Test
    fun figmanStandsInTheSystemMenu() {
        fun directory(path: String, vararg children: String) = path to MenuSchema(MenuPath.parse(path), NodeKind.Directory, children.toList(), emptyList(), emptyList())
        val storage = MemoryStorage()
        val schemas = mapOf(directory("", "system", "figman"), directory("system", "clock"), directory("figman", "connect", "peer"))
        val tab = RouterTab(FakeRouter(schemas = schemas), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { tab.loadMenu(bundledMenu()) }
        val top = tab.menu.orEmpty()
        assertTrue(top.none { it.path == "figman" }, "Figman is not a menu of its own")
        assertEquals("figman", top.first { it.title == "System" }.items.single { it.title == "Figman" }.path)
    }

    @Test
    fun aConfigATabOfAWindowInTheMenuShowsHasNoEntryOfItsOwn() {
        val root = MenuSchema(MenuPath.Root, NodeKind.Directory, listOf("network", "upnpd", "adblock"), emptyList(), emptyList())
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(schemas = mapOf("" to root), platform = Platform.OpenWrt), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { tab.loadMenu(bundledMenu(Platform.OpenWrt)) }
        val paths = tab.menu.orEmpty().map { it.path }
        assertTrue("adblock" in paths)
        assertTrue("upnpd" !in paths)
    }
}
