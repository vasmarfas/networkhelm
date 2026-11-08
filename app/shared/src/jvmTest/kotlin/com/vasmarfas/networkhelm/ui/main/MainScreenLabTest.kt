package com.vasmarfas.networkhelm.ui.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.connectOpenWrtWithForms
import com.vasmarfas.networkhelm.miknative.nativeSide
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.openwrt.OpenWrtLogin
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledMenu
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.routeros.RouterOsProtocol
import com.vasmarfas.networkhelm.routeros.connectRouterOs
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.connectWithSsh
import com.vasmarfas.networkhelm.session.rows
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.ui.files.FileExchange
import com.vasmarfas.networkhelm.ui.files.LocalFile
import com.vasmarfas.networkhelm.ui.files.LocalFileExchange
import com.vasmarfas.networkhelm.ui.kit.HintHost
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.terminal.BossTerminal
import com.vasmarfas.networkhelm.ui.terminal.TerminalView
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import java.io.File
import java.util.Locale
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MainScreenLabTest {

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun lab(): Properties {
        val file = System.getProperty("networkhelm.lab")?.let(::File)?.takeIf { it.isFile }
        assumeTrue("lab.properties is missing", file != null)
        return Properties().apply { file?.inputStream()?.use(::load) }
    }

    private fun labRouter(prefix: String = "chr", test: (DeviceSession, String) -> Unit) {
        val lab = lab()
        val host = lab.getProperty("$prefix.host").orEmpty()
        assumeTrue("$prefix.host is empty", host.isNotEmpty())
        Locale.setDefault(Locale.ENGLISH)
        val scope = CoroutineScope(Dispatchers.Default)
        val protocol = if (prefix == "chr6") RouterOsProtocol.Api else RouterOsProtocol.ApiSsl
        val login = RouterOsLogin(host, lab.getProperty("$prefix.user"), lab.getProperty("$prefix.password"), protocol)
        val session = runBlocking { connectWithSsh(login, TrustPolicy(MemoryTrustStore()) { true }, scope, native = login.nativeSide(scope)) }
        try {
            test(session, host)
        } finally {
            session.close()
            scope.cancel()
        }
    }

    private fun labOpenWrt(prefix: String, test: (DeviceSession, String) -> Unit) {
        val lab = lab()
        val host = lab.getProperty("$prefix.host").orEmpty()
        assumeTrue("$prefix.host is empty", host.isNotEmpty())
        Locale.setDefault(Locale.ENGLISH)
        val key = lab.getProperty("$prefix.ssh.hostKey")
        val login = OpenWrtLogin(host, lab.getProperty("$prefix.user"), lab.getProperty("$prefix.password"))
        val session = runBlocking { connectOpenWrtWithForms(login, TrustPolicy(MemoryTrustStore()) { it.fingerprint == key }) }
        try {
            test(session, host)
        } finally {
            session.close()
        }
    }

    private suspend fun DeviceSession.has(path: String) = try {
        describe(MenuPath.parse(path))
        true
    } catch (e: DeviceException) {
        false
    }

    private fun eachOpenWrt(test: (String) -> Unit) {
        val lab = lab()
        for (prefix in listOf("openwrt", "openwrt24").filter { lab.getProperty("$it.host").orEmpty().isNotBlank() }) {
            try {
                test(prefix)
            } catch (e: AssumptionViolatedException) {
                throw e
            } catch (e: Throwable) {
                throw AssertionError("on $prefix: $e", e)
            }
        }
    }

    private fun labRouter6(test: (DeviceSession, String) -> Unit) {
        val lab = lab()
        val host = lab.getProperty("chr6.host").orEmpty()
        assumeTrue("chr6.host is empty", host.isNotEmpty())
        Locale.setDefault(Locale.ENGLISH)
        val scope = CoroutineScope(Dispatchers.Default)
        val login = RouterOsLogin(host, lab.getProperty("chr6.user"), lab.getProperty("chr6.password"), RouterOsProtocol.Api)
        val session = runBlocking { connectRouterOs(login, TrustPolicy(MemoryTrustStore()) { true }, scope) }
        try {
            test(session, host)
        } finally {
            session.close()
            scope.cancel()
        }
    }

    @Test
    fun routerOs6ShowsItsOwnMenuAndLiveWindows() = runDesktopComposeUiTest(1280, 800) {
        labRouter6 { session, host ->
            val storage = MemoryStorage()
            val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            waitUntil(timeoutMillis = 60_000) { tab.menu != null }
            fun titles(entries: List<MenuEntry>): List<String> = entries.flatMap { listOf(it.title) + titles(it.items) }
            val menu = titles(tab.menu.orEmpty())
            assertTrue("Wireless" in menu && "Addresses" in menu, menu.toString())
            assertTrue(menu.none { it in setOf("WiFi", "WireGuard", "Container") }, "menus of RouterOS 7 only: $menu")
            onNodeWithText("IP").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Addresses")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Addresses").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("$host/24")).fetchSemanticsNodes().isNotEmpty() }
            picture("lab6-addresses")
            assertTrue(listOf("BGP", "OSPFv3", "Prefix Lists", "RIPng").all { it in menu }, "routing menus of RouterOS 6: $menu")
            onNodeWithText("IP").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Routes")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Routes").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("0.0.0.0/0")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(onAllNodes(hasText("Nexthops")).fetchSemanticsNodes().isNotEmpty(), "the routes window of RouterOS 6")
            picture("lab6-routes")
            onNodeWithText("Routing").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("OSPF")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("OSPF").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("NBMA Neighbors")).fetchSemanticsNodes().isNotEmpty() && onAllNodes(hasText("0.0.0.0")).fetchSemanticsNodes().isNotEmpty() }
            picture("lab6-ospf")
        }
    }

    @Test
    fun openWrtShowsTheMenuOfLuci() = eachOpenWrt { prefix ->
        runDesktopComposeUiTest(1280, 800) {
            Locale.setDefault(Locale.ENGLISH)
            labOpenWrt(prefix) { session, host ->
                val storage = MemoryStorage()
                val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
                setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
                waitUntil(timeoutMillis = 60_000) { tab.menu != null }
                onNodeWithTag("view").performClick()
                onNodeWithText("LuCI view").performClick()
                waitUntil(timeoutMillis = 60_000) { tab.menu?.any { it.title == "Network" } == true }
                onNodeWithText("Network").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Interfaces")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Interfaces").performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("wan")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Status").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Processes")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Routing").performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("default")).fetchSemanticsNodes().isNotEmpty() }
                picture("$prefix-luci-routing")
                onNodeWithText("Status").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Processes")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Processes").performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("/sbin/procd", substring = true)).fetchSemanticsNodes().isNotEmpty() }
                picture("$prefix-luci-processes")
                onNodeWithText("System").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Scheduled Tasks")).fetchSemanticsNodes().isNotEmpty() }
                picture("$prefix-native")
            }
        }
    }

    @Test
    fun openWrtShowsTheMenusOfRouterOs() = eachOpenWrt { prefix ->
        runDesktopComposeUiTest(1280, 800) {
            Locale.setDefault(Locale.ENGLISH)
            val lan = lab().getProperty("$prefix.lan").orEmpty()
            labOpenWrt(prefix) { session, host ->
                val storage = MemoryStorage()
                val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
                setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
                waitUntil(timeoutMillis = 60_000) { tab.menu != null }
                onNodeWithTag("view").performClick()
                onNodeWithText("Unified view").performClick()
                waitUntil(timeoutMillis = 60_000) { tab.view == MenuView.RouterOs && tab.menu != null }
                fun titles(entries: List<MenuEntry>): List<String> = entries.flatMap { listOf(it.title) + titles(it.items) }
                val menu = titles(tab.menu.orEmpty())
                assertTrue(
                    listOf("Interfaces", "Bridge", "Addresses", "Routes", "DNS", "DHCP Server", "Firewall", "Identity", "Log", "Ping", "IPv6", "Neighbors", "Clock", "NTP Client", "Files")
                        .all { it in menu },
                    menu.toString(),
                )
                assertTrue(listOf("WiFi", "Wireless").none { it in menu }, menu.toString())
                val queues = CoroutineScope(Dispatchers.Default).async { session.has("sqm/queue") }
                waitUntil(timeoutMillis = 120_000) { queues.isCompleted }
                assertEquals(runBlocking { queues.await() }, "Queues" in menu, menu.toString())
                onNodeWithText("IP").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Addresses")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Addresses").performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText(lan)).fetchSemanticsNodes().isNotEmpty() }
                assertTrue(onAllNodes(hasText(lan.substringBeforeLast('.') + ".0")).fetchSemanticsNodes().isNotEmpty(), "the network of an address is worked out")
                picture("$prefix-routeros")
                onNodeWithTag("view").performClick()
                onNodeWithText("OpenWrt view").performClick()
                waitUntil(timeoutMillis = 60_000) { tab.view == MenuView.WinBox && tab.menu != null }
                assertTrue(tab.workspace.windows.none { it.title == "Addresses" })
            }
        }
    }

    @Test
    fun openWrtShowsItsOwnMenuAndWindows() = eachOpenWrt { prefix ->
        runDesktopComposeUiTest(1280, 800) {
            labOpenWrt(prefix) { session, host ->
                val storage = MemoryStorage()
                val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
                setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
                waitUntil(timeoutMillis = 60_000) { tab.menu != null }
                fun titles(entries: List<MenuEntry>): List<String> = entries.flatMap { listOf(it.title) + titles(it.items) }
                val menu = titles(tab.menu.orEmpty())
                assertTrue(listOf("Interfaces", "Addresses", "Firewall", "DHCP and DNS", "General", "Startup", "Log").all { it in menu }, menu.toString())
                assertTrue("Wireless" !in menu, "the lab router has no radio: $menu")
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Apply 0")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Interfaces").performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("wan")).fetchSemanticsNodes().isNotEmpty() }
                picture("$prefix-interfaces")
                onNodeWithText("IP").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Firewall")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Firewall").performClick()
                waitUntil(timeoutMillis = 30_000) {
                    onAllNodes(hasText("Port Forwards")).fetchSemanticsNodes().isNotEmpty() && onAllNodes(hasText("reject")).fetchSemanticsNodes().isNotEmpty()
                }
                picture("$prefix-firewall")
                onNodeWithText("Log").performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasTestTag("log-find")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("log-find").performTextInput("authpriv.info")
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("authpriv") and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
                picture("$prefix-log")
                onNodeWithText("Files").performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("tmp")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("tmp").performMouseInput { doubleClick() }
                onAllNodesWithTag("find").onLast().performTextInput("dhcp.leases")
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("dhcp.leases")).fetchSemanticsNodes().isNotEmpty() }
                picture("$prefix-files")
                onNodeWithText("System").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Resources")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Resources").performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText(session.device.version)).fetchSemanticsNodes().isNotEmpty() }
                picture("$prefix-resources")
                onNodeWithText("System").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Packages")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Packages").performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("busybox")).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithTag("find").onLast().performTextInput("dnsmasq")
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("dnsmasq") and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
                picture("$prefix-packages")
                onNodeWithText("System").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Password")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Password").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Confirm New Password")).fetchSemanticsNodes().isNotEmpty() }
                picture("$prefix-password")
            }
        }
    }

    @Test
    fun openWrtFilesOpenFolderByFolder() = eachOpenWrt { prefix ->
        runDesktopComposeUiTest(1280, 800) {
            Locale.setDefault(Locale.ENGLISH)
            labOpenWrt(prefix) { session, host ->
                val storage = MemoryStorage()
                val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
                setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
                waitUntil(timeoutMillis = 60_000) { tab.menu != null }
                tab.open(MenuEntry("Files", "status/files"))
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("tmp")).fetchSemanticsNodes().isNotEmpty() }
                assertTrue(onAllNodes(hasText("dhcp.leases")).fetchSemanticsNodes().isEmpty(), "closed at first")
                onNodeWithTag("toggle-tmp").performClick()
                onNodeWithTag("find").performTextInput("dhcp.leases")
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("dhcp.leases")).fetchSemanticsNodes().isNotEmpty() }
                assertTrue(onAllNodes(hasTestTag("files-problem")).fetchSemanticsNodes().isEmpty())
                picture("$prefix-files-tree")
            }
        }
    }

    @Test
    fun anApplicationOfLuciGetsAWindowInBothViews() = runDesktopComposeUiTest(1280, 800) {
        labOpenWrt("openwrt") { session, host ->
            val storage = MemoryStorage()
            val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            fun titles(entries: List<MenuEntry>): List<String> = entries.flatMap { listOf(it.title) + titles(it.items) }
            waitUntil(timeoutMillis = 30_000) { "Attended Sysupgrade" in titles(tab.menu.orEmpty()) }
            assertTrue("Attendedsysupgrade" !in titles(tab.menu.orEmpty()))
            onNodeWithText("System").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Attended Sysupgrade")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Attended Sysupgrade").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("https://sysupgrade.openwrt.org")).fetchSemanticsNodes().isNotEmpty() }
            picture("openwrt-luci-app")
            tab.workspace.windows.toList().forEach(tab.workspace::close)
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("https://sysupgrade.openwrt.org")).fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("view").performClick()
            onNodeWithText("LuCI view").performClick()
            waitUntil(timeoutMillis = 60_000) { tab.menu?.any { it.title == "Network" } == true }
            onNodeWithText("System").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Attended Sysupgrade")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Attended Sysupgrade").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("https://sysupgrade.openwrt.org")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun mixomoHasAWindowOfItsOwn() = runDesktopComposeUiTest(1280, 800) {
        assumeTrue("the lab file does not say that openwrt has mixomo", lab().getProperty("openwrt.mixomo").orEmpty().isNotBlank())
        labOpenWrt("openwrt") { session, host ->
            val storage = MemoryStorage()
            val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            waitUntil(timeoutMillis = 60_000) { tab.menu?.any { it.title == "Mixomo" } == true }
            onNodeWithText("Mixomo").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Backup")).fetchSemanticsNodes().isNotEmpty() }
            onAllNodes(hasText("Mihomo")).onFirst().performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("default")).fetchSemanticsNodes().isNotEmpty() }
            picture("openwrt-mixomo-profiles")
            onAllNodes(hasText("Routing")).onFirst().performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Configuration")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Configuration").performClick()
            onNodeWithText("Advanced").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Proxy Router Traffic")).fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("redir-tproxy")).fetchSemanticsNodes().isNotEmpty() }
            picture("openwrt-mixomo-routing")
        }
    }

    @Test
    fun openWrtWithWiFiShowsItsRadiosAndNetworks() = runDesktopComposeUiTest(1400, 900) {
        labOpenWrt("openwrtwifi") { session, host ->
            val storage = MemoryStorage()
            val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            waitUntil(timeoutMillis = 60_000) { tab.menu != null }
            onNodeWithText("Wireless").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("default_radio1")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(onAllNodes(hasText("Client")).fetchSemanticsNodes().isNotEmpty(), "the uplinks of travelmate are networks in station mode")
            picture("openwrtwifi-networks")
            onNodeWithText("Radios").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("5g")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("radio1").performMouseInput { doubleClick() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Cancel")).fetchSemanticsNodes().isNotEmpty() }
            picture("openwrtwifi-radio")
            onNodeWithText("Cancel").performClick()
            onNodeWithText("Clients").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText(" item", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            picture("openwrtwifi-clients")
        }
    }

    @Test
    fun theRouterOffersWhatThePacksDoNotName() = labRouter { session, host ->
        val storage = MemoryStorage()
        val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { tab.loadMenu(bundledMenu()) }
        fun entries(items: List<MenuEntry>): List<MenuEntry> = items.flatMap { listOf(it) + entries(it.items) }
        val bundled = entries(bundledMenu().items).mapNotNull { it.path }.toSet()
        val shown = entries(tab.menu.orEmpty()).filter { it.path != null && it.window == null }
        val paths = shown.mapNotNull { it.path }
        assertEquals(paths.size, paths.toSet().size, "an entry stands once: $paths")
        println("DYNAMIC entries beyond the bundled menu: ${paths.filter { it !in bundled }}")
        val grown = mutableListOf<String>()
        for (entry in shown) {
            tab.open(entry)
            val window = tab.workspace.windows.last()
            val spec = tab.specs.getValue(window.id)
            val before = spec.tabs.map { it.path.toString() }
            runBlocking { tab.expand(spec) }
            val after = spec.tabs.map { it.path.toString() }
            assertEquals(after.size, after.toSet().size, "${entry.title} has a tab twice: $after")
            if (after.size > before.size) grown += "${entry.title} ${after.filter { it !in before }}"
            tab.workspace.close(window)
        }
        println("DYNAMIC tabs beyond the packs: $grown")
    }

    @Test
    fun theLabRouterWithCmrHasTheControllerFirstAndTheRestInMore() = labRouter { session, host ->
        assumeTrue("the lab router has no cmr", runBlocking { session.has("cmr") })
        val storage = MemoryStorage()
        val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { tab.loadMenu(bundledMenu()) }
        val top = tab.menu.orEmpty()
        println("MENU top: ${top.map { it.title }}")
        println("MENU controller: ${top.first().items.map { it.title }}")
        println("MENU more: ${top.last().items.map { it.title }}")
        assertEquals("Controller", top.first().title)
        assertTrue(top.first().items.map { it.title }.containsAll(listOf("Devices", "Alerts", "Settings")))
        assertEquals("More", top.last().title)
        runBlocking { tab.reloadStructure() }
        assertEquals(top.map { it.title }, tab.menu.orEmpty().map { it.title })
    }

    @Test
    fun theControllerWindowsAreMadeFromTheDescriptionsOfTheLabRouter() = runDesktopComposeUiTest(1400, 800) {
        labRouter { session, host ->
            assumeTrue("the lab router has no cmr", runBlocking { session.has("cmr") })
            val storage = MemoryStorage()
            val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
            runBlocking { tab.loadMenu(bundledMenu()) }
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            tab.open(MenuEntry("Devices", "cmr/device"))
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Identity")).fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Loading…")).fetchSemanticsNodes().isEmpty() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Available Version")).fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("paired")).fetchSemanticsNodes().isNotEmpty() }
            picture("lab-controller-devices")
            assertTrue(onAllNodes(hasText("Pairing Status")).fetchSemanticsNodes().isNotEmpty(), "the column only WinBox has")
            assertTrue(onAllNodes(hasText("LU")).fetchSemanticsNodes().isNotEmpty(), "the flags of WinBox: L for the controller, U for the update")
            onNodeWithText("Resources").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Loading…")).fetchSemanticsNodes().isEmpty() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText(session.device.identity)).fetchSemanticsNodes().size > 1 }
            picture("lab-controller-resources")
            assertTrue(onAllNodes(hasText("HDD")).fetchSemanticsNodes().isNotEmpty(), "the columns of the resources of the devices")
            tab.open(MenuEntry("Alerts", "cmr/alert"))
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("cpu>95%")).fetchSemanticsNodes().isNotEmpty() }
            picture("lab-controller-alerts")
        }
    }

    @Test
    fun theDashboardAndTheTopologyAreDrawnFromTheControllerOfTheLabRouter() = runDesktopComposeUiTest(1500, 950) {
        labRouter { session, host ->
            assumeTrue("the lab router has no cmr", runBlocking { session.has("cmr") })
            val storage = MemoryStorage()
            val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
            runBlocking { tab.loadMenu(bundledMenu()) }
            val controller = tab.menu.orEmpty().first { it.title == "Controller" }.items
            assertEquals("Dashboard", controller.first().title)
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            tab.open(controller.first { it.title == "Dashboard" })
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasTestTag("dashboard-resource")).fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasTestTag("dashboard-rates") and hasText("Now", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(onAllNodes(hasText(session.device.identity)).fetchSemanticsNodes().isNotEmpty(), "the lab router stands in the resources of the devices")
            picture("lab-controller-dashboard")
            tab.open(controller.first { it.title == "Topology" })
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasTestTag("topology-node")).fetchSemanticsNodes().isNotEmpty() }
            picture("lab-controller-topology")
        }
    }

    @Test
    fun theBundledWindowsTakeTheLayoutOfTheLabRouter() = runDesktopComposeUiTest(1300, 800) {
        labRouter { session, host ->
            val storage = MemoryStorage()
            val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
            runBlocking { tab.loadMenu(bundledMenu()) }
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            for ((name, title, path) in listOf(
                Triple("dhcp", "DHCP Server", "ip/dhcp-server"), Triple("services", "Services", "ip/service"), Triple("queues", "Queues", "queue/simple"),
                Triple("firewall", "Firewall", "ip/firewall/filter"), Triple("users", "Users", "user"), Triple("wifi", "WiFi", "interface/wifi"),
                Triple("dns", "DNS", "ip/dns"), Triple("wireguard", "WireGuard", "interface/wireguard"), Triple("bridge", "Bridge", "interface/bridge"),
                Triple("ppp", "PPP", "interface"), Triple("files", "Files", "file"), Triple("mpls", "MPLS", "mpls/ldp"), Triple("hotspot", "Hotspot", "ip/hotspot"),
                Triple("sniffer", "Packet Sniffer", "tool/sniffer"), Triple("certificates", "Certificates", "certificate"), Triple("bgp", "BGP", "routing/bgp/instance"),
                Triple("resources", "Resources", "system/resource"), Triple("interfaces", "Interfaces", "interface"),
            )) {
                for (window in tab.workspace.windows.toList()) tab.workspace.close(window)
                tab.open(MenuEntry(title, path))
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Loading…")).fetchSemanticsNodes().isEmpty() }
                picture("lab-merged-$name")
            }
        }
    }

    @Test
    fun theUpdateDialogOfTheLabRouterIsOnePageWithTheVersions() = runDesktopComposeUiTest(900, 700) {
        labRouter { session, host ->
            val storage = MemoryStorage()
            val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
            runBlocking { tab.loadMenu(bundledMenu()) }
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            tab.open(MenuEntry("Check For Updates", "system/package/update"))
            try {
                waitUntil(timeoutMillis = 20_000) { onAllNodes(hasText("Installed Version")).fetchSemanticsNodes().isNotEmpty() }
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Loading…")).fetchSemanticsNodes().isEmpty() }
            } finally {
                picture("lab-update")
            }
        }
    }

    @Test
    fun theInterfacesWindowOfTheLabRouterTakesTheTunnelsThePackLacks() = runDesktopComposeUiTest(1400, 900) {
        labRouter { session, host ->
            val storage = MemoryStorage()
            val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            tab.open(MenuEntry("Interfaces", "interface"))
            val spec = tab.specs.values.single()
            val packed = spec.tabs.size
            waitUntil(timeoutMillis = 30_000) { spec.tabs.size > packed }
            println("DYNAMIC tabs of Interfaces: ${spec.tabs.map { it.title }}")
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("ether1")).fetchSemanticsNodes().isNotEmpty() }
            picture("lab-interfaces-dynamic")
        }
    }

    @Test
    fun menuOpensLiveWindowsOfTheLabRouter() = runDesktopComposeUiTest(1280, 800) {
        labRouter { session, host ->
            val storage = MemoryStorage()
            val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            waitUntil(timeoutMillis = 30_000) { tab.menu != null }
            assertTrue(tab.menu.orEmpty().any { it.title == "Container" }, "the lab CHR has the container package")
            assertTrue(tab.menu.orEmpty().none { it.title == "Wireless" }, "the old wireless menu is gone on 7.13 and later")
            onNodeWithText("Interfaces").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("ether1")).fetchSemanticsNodes().isNotEmpty() }
            picture("main-interfaces")
            onNodeWithText("IP").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Firewall")).fetchSemanticsNodes().isNotEmpty() }
            picture("main-ip-menu")
            onNodeWithText("Firewall").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Filter Rules")).fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("input")).fetchSemanticsNodes().isNotEmpty() }
            picture("main-firewall")
        }
    }

    @Test
    fun aListEntryIsDisabledAndRemovedFromTheTable() = runDesktopComposeUiTest(1280, 800) {
        labRouter { session, host ->
            val path = MenuPath.parse("ip/firewall/address-list")
            val id = runBlocking { session.add(path, mapOf("list" to "rd-test-list", "address" to "192.0.2.10")) }
            try {
                val storage = MemoryStorage()
                val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
                setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
                tab.open(MenuEntry("Address Lists", path.toString()))
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("192.0.2.10")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("find").performTextInput("rd-test-list")
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("1 item") or hasText("1 of ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("192.0.2.10").performClick()
                onNodeWithText("Disable").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("X")).fetchSemanticsNodes().isNotEmpty() }
                assertEquals("true", runBlocking { session.get(path, id) }?.get("disabled"))
                picture("lab-address-disabled")
                onNodeWithText("192.0.2.10").performClick()
                onNodeWithTag("table").performKeyInput { pressKey(Key.Delete) }
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Remove 1 item from the router?")).fetchSemanticsNodes().isNotEmpty() }
                onAllNodes(hasText("Remove"))[1].performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("192.0.2.10")).fetchSemanticsNodes().isEmpty() }
                assertNull(runBlocking { session.get(path, id) })
            } finally {
                runBlocking { if (session.get(path, id) != null) session.remove(path, listOf(id)) }
            }
        }
    }

    @Test
    fun aRuleDialogUnsetsAndChangesFieldsOnTheRouter() = runDesktopComposeUiTest(1280, 800) {
        labRouter { session, host ->
            val path = MenuPath.parse("ip/firewall/filter")
            val rule = mapOf(
                "chain" to "forward", "action" to "accept", "disabled" to "yes", "protocol" to "tcp", "dst-port" to "8080",
                "src-address" to "!192.0.2.0/24", "comment" to "rd-test-dialog",
            )
            val id = runBlocking { session.add(path, rule) }
            try {
                val storage = MemoryStorage()
                val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
                setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
                tab.openItem("Filter Rules", path, id)
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Firewall Rule <!192.0.2.0/24->8080>")).fetchSemanticsNodes().isNotEmpty() }
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("unset-src-address")).fetchSemanticsNodes().isNotEmpty() }
                picture("lab-dialog")
                onNodeWithTag("unset-src-address").performClick()
                onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-dst-port"))).performTextReplacement("8443")
                onNodeWithText("Apply").performClick()
                waitUntil(timeoutMillis = 10_000) { runBlocking { session.get(path, id) }?.let { it["src-address"] == null && it["dst-port"] == "8443" } == true }
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Firewall Rule <->8443>")).fetchSemanticsNodes().isNotEmpty() }
                picture("lab-dialog-applied")
                val after = runBlocking { session.get(path, id) }
                assertEquals(rule - "src-address" + ("dst-port" to "8443") + ("disabled" to "true"), after?.values.orEmpty().filterKeys { it in rule }, "nothing else changed")
            } finally {
                runBlocking { if (session.get(path, id) != null) session.remove(path, listOf(id)) }
            }
        }
    }

    @Test
    fun settingsCommandsAndTheLogOfTheLabRouter() = runDesktopComposeUiTest(1280, 800) {
        labRouter { session, host ->
            val storage = MemoryStorage()
            val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            tab.open(MenuEntry("DNS", "ip/dns"))
            waitUntil(timeoutMillis = 30_000) {
                onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-cache-size"))).fetchSemanticsNodes().any { node ->
                    node.config.getOrNull(SemanticsProperties.EditableText)?.text?.isNotEmpty() == true
                }
            }
            picture("lab-dns")
            onNodeWithText("Cancel").performClick()
            waitUntil(timeoutMillis = 5_000) { tab.workspace.windows.isEmpty() }

            tab.open(MenuEntry("Ping", "tool/ping"))
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasTestTag("field-address")).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-address"))).performTextInput("127.0.0.1")
            onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-count"))).performTextInput("3")
            onNodeWithText("Start").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("127.0.0.1")).fetchSemanticsNodes().size == 4 }
            picture("lab-ping")

            tab.workspace.windows.toList().forEach(tab.workspace::close)
            tab.open(MenuEntry("Traffic", "interface/monitor-traffic"))
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasTestTag("field-interface")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("field-interface").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("ether1")).fetchSemanticsNodes().isNotEmpty() }
            onAllNodes(hasText("ether1")).onLast().performClick()
            onAllNodes(hasText("Start")).onLast().performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("ether1 Rx:", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            picture("lab-traffic")
            onAllNodes(hasText("Stop")).onLast().performClick()

            tab.open(MenuEntry("Log", "log"))
            waitUntil(timeoutMillis = 30_000) {
                onAllNodes(hasText(" line", substring = true)).fetchSemanticsNodes().any { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text?.startsWith("0 ") == false
                }
            }
            picture("lab-log")
        }
    }

    @Test
    fun interfacesOfTheLabRouterShowRatesAndTheirDialog() = runDesktopComposeUiTest(1400, 900) {
        labRouter { session, host ->
            val storage = MemoryStorage()
            val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            tab.open(MenuEntry("Interfaces", "interface"))
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("bps", substring = true)).fetchSemanticsNodes().size >= 4 }
            picture("lab-interfaces")

            onNodeWithText("ether2").performMouseInput { doubleClick() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Interface <ether2>")).fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Status")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Status").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("link-ok")).fetchSemanticsNodes().isNotEmpty() }
            picture("lab-interface-status")
            onNodeWithText("Traffic").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Tx: ", substring = true) and hasText("bps", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            picture("lab-interface-traffic")
            onNodeWithText("Cancel").performClick()
        }
    }

    @Test
    fun bridgeAndItsPortsShowTheirStatus() = runDesktopComposeUiTest(1400, 900) {
        labRouter { session, host ->
            val storage = MemoryStorage()
            val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            tab.open(MenuEntry("Bridge", "interface/bridge"))
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("bridge1")).fetchSemanticsNodes().isNotEmpty() }
            picture("lab-bridge")

            onNodeWithText("bridge1").performMouseInput { doubleClick() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Status")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Status").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("0x8000.", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Link Downs")).fetchSemanticsNodes().isNotEmpty() }
            picture("lab-bridge-status")
            onNodeWithText("Cancel").performClick()

            onNodeWithText("Ports").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("ether2")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("ether2").performMouseInput { doubleClick() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Bridge Port <ether2>")).fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Status")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Status").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("designated-port")).fetchSemanticsNodes().isNotEmpty() }
            picture("lab-bridge-port-status")
            onNodeWithText("Cancel").performClick()
        }
    }

    @Test
    fun ipWindowsOfTheLabRouter() = runDesktopComposeUiTest(1400, 900) {
        labRouter { session, host ->
            val storage = MemoryStorage()
            val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            fun show(title: String, path: String, expected: String) {
                tab.workspace.windows.toList().forEach(tab.workspace::close)
                tab.open(MenuEntry(title, path))
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText(expected)).fetchSemanticsNodes().isNotEmpty() }
            }
            show("Addresses", "ip/address", "bridge1")
            show("Pool", "ip/pool", "pool1")
            show("ARP", "ip/arp", "ether1")
            show("Services", "ip/service", "api-ssl")
            show("Neighbors", "ip/neighbor", "ether1")
            show("DHCP Client", "ip/dhcp-client", "client1")
            show("DHCP Server", "ip/dhcp-server", "server1")
            show("Identity", "system/identity", "Identity")
            show("Users", "user", "full")
            show("Packages", "system/package", "routeros")
            show("Resources", "system/resource", "x86_64")
            show("Scripts", "system/script", "Run Count")
            show("Files", "file", "skins")
            show("Firewall", "ipv6/firewall/filter", "Filter Rules")
            assertTrue(listOf("NAT", "Mangle", "Raw", "Connections", "Address Lists").all { onAllNodes(hasText(it)).fetchSemanticsNodes().isNotEmpty() })
            picture("lab-ipv6-firewall")
            show("WireGuard", "interface/wireguard", "Peers")
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("IoT")).fetchSemanticsNodes().isNotEmpty() }
            tab.workspace.windows.toList().forEach(tab.workspace::close)
            tab.open(MenuEntry("Traffic Generator", "tool/traffic-generator"))
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("device-mode: traffic-gen=false", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            show("DNS", "ip/dns", "Dynamic Servers")
            onNodeWithText("Cache").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Data")).fetchSemanticsNodes().isNotEmpty() }
            picture("lab-dns-cache")

            show("Routes", "ip/route", "DAC")
            picture("lab-routes")
            val made = runBlocking {
                session.watch(MenuPath.parse("ip/route"), Filter.Equals("dynamic", "true")).filterIsInstance<ListEvent.Snapshot>().first().items.first()
            }["dst-address"].orEmpty()
            onNodeWithText(made).performMouseInput { doubleClick() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Route <$made->", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("DYNAMIC")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(onAllNodes(hasText("OK")).fetchSemanticsNodes().isEmpty(), "a dynamic route is not edited")
            picture("lab-route-dialog")
            onNodeWithText("Close").performClick()
        }
    }

    @Test
    fun pppAndQueueWindowsOfTheLabRouter() = runDesktopComposeUiTest(1400, 900) {
        labRouter { session, host ->
            val sstp = MenuPath.parse("interface/sstp-client")
            val client = runBlocking { session.add(sstp, mapOf("name" to "rd-test-sstp", "connect-to" to "192.0.2.1", "user" to "rd-test", "disabled" to "yes")) }
            try {
                val storage = MemoryStorage()
                val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
                setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
                tab.open(MenuEntry("PPP", "ppp/secret"))
                onNodeWithText("Profiles").performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("default-encryption")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Interface").performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("sstp-out")).fetchSemanticsNodes().isNotEmpty() }
                assertTrue(onAllNodes(hasText("ether1")).fetchSemanticsNodes().isEmpty(), "only PPP interfaces")
                picture("lab-ppp-interface")
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("New")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("New").performClick()
                onNodeWithText("SSTP Client").performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Dial Out")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Dial Out").performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Connect To")).fetchSemanticsNodes().isNotEmpty() }
                picture("lab-ppp-new-client")
                onNodeWithText("Cancel").performClick()

                tab.open(MenuEntry("Queues", "queue/simple"))
                onNodeWithText("Queue Types").performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("ethernet-default")).fetchSemanticsNodes().isNotEmpty() }
                picture("lab-queue-types")
            } finally {
                runBlocking { session.remove(sstp, listOf(client)) }
            }
        }
    }

    @Test
    fun theIpsecWindowShowsALiveTunnel() = runDesktopComposeUiTest(1400, 900) {
        val lab = lab()
        val near = lab.getProperty("chr717.segmentAddress").orEmpty()
        val far = lab.getProperty("chr642.segmentAddress").orEmpty()
        assumeTrue("chr717.segmentAddress or chr642.segmentAddress is empty", near.isNotEmpty() && far.isNotEmpty())
        Locale.setDefault(Locale.ENGLISH)
        val scope = CoroutineScope(Dispatchers.Default)
        val trust = TrustPolicy(MemoryTrustStore()) { true }
        fun connect(prefix: String) = runBlocking {
            connectRouterOs(RouterOsLogin(lab.getProperty("$prefix.host"), lab.getProperty("$prefix.user"), lab.getProperty("$prefix.password"), RouterOsProtocol.Api), trust, scope)
        }
        val a = connect("chr717")
        val b = connect("chr642")
        val test = mapOf("comment" to "rd-test")
        try {
            runBlocking {
                a.add(MenuPath.parse("interface/bridge"), test + ("name" to "rd-test-ipsec"))
                a.add(MenuPath.parse("ip/address"), test + mapOf("address" to "10.78.1.1/24", "interface" to "rd-test-ipsec"))
                a.add(MenuPath.parse("ip/route"), test + mapOf("dst-address" to "10.78.2.0/24", "gateway" to far))
                a.add(MenuPath.parse("ip/ipsec/peer"), test + mapOf("name" to "rd-test-peer", "address" to "$far/32", "local-address" to near, "exchange-mode" to "main"))
                a.add(MenuPath.parse("ip/ipsec/identity"), test + mapOf("peer" to "rd-test-peer", "auth-method" to "pre-shared-key", "secret" to "rd-test-ipsec-key"))
                a.add(MenuPath.parse("ip/ipsec/policy"), test + mapOf("peer" to "rd-test-peer", "src-address" to "10.78.1.0/24", "dst-address" to "10.78.2.0/24", "tunnel" to "yes", "action" to "encrypt"))
                b.add(MenuPath.parse("interface/bridge"), test + ("name" to "rd-test-ipsec"))
                b.add(MenuPath.parse("ip/address"), test + mapOf("address" to "10.78.2.1/24", "interface" to "rd-test-ipsec"))
                b.add(MenuPath.parse("ip/route"), test + mapOf("dst-address" to "10.78.1.0/24", "gateway" to near))
                b.add(MenuPath.parse("ip/ipsec/peer"), test + mapOf("address" to "$near/32", "local-address" to far, "exchange-mode" to "main", "auth-method" to "pre-shared-key", "secret" to "rd-test-ipsec-key"))
                b.add(
                    MenuPath.parse("ip/ipsec/policy"),
                    test + mapOf("src-address" to "10.78.2.0/24", "dst-address" to "10.78.1.0/24", "sa-src-address" to far, "sa-dst-address" to near, "tunnel" to "yes", "action" to "encrypt"),
                )
                a.run(MenuPath.Root, "ping", mapOf("address" to "10.78.2.1", "src-address" to "10.78.1.1", "count" to "3")).toList()
            }
            val storage = MemoryStorage()
            val tab = RouterTab(a, lab.getProperty("chr717.host"), bundledPacks().values, storage, TableLayouts(storage))
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            tab.open(MenuEntry("IPsec", "ip/ipsec/policy"))
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("established")).fetchSemanticsNodes().isNotEmpty() }
            picture("lab-ipsec-policies")
            onNodeWithText("Active Peers").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText(far)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Installed SAs").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("0x", substring = true)).fetchSemanticsNodes().size >= 2 }
            picture("lab-ipsec-sas")
        } finally {
            runBlocking {
                for (session in listOf(a, b)) {
                    for (menu in listOf("ip/ipsec/policy", "ip/ipsec/identity", "ip/ipsec/peer", "ip/route", "ip/address", "interface/bridge")) {
                        if (session === b && menu == "ip/ipsec/identity") continue
                        val path = MenuPath.parse(menu)
                        val ids = session.watch(path, Filter.Equals("comment", "rd-test")).filterIsInstance<ListEvent.Snapshot>().first().items.map { it.id }
                        if (ids.isNotEmpty()) session.remove(path, ids)
                    }
                }
            }
            a.close()
            b.close()
            scope.cancel()
        }
    }

    @Test
    fun theBgpWindowShowsALiveSession() = runDesktopComposeUiTest(1400, 900) {
        val lab = lab()
        val near = lab.getProperty("chr717.segmentAddress").orEmpty()
        val far = lab.getProperty("chr642.segmentAddress").orEmpty()
        assumeTrue("chr717.segmentAddress or chr642.segmentAddress is empty", near.isNotEmpty() && far.isNotEmpty())
        Locale.setDefault(Locale.ENGLISH)
        val scope = CoroutineScope(Dispatchers.Default)
        val trust = TrustPolicy(MemoryTrustStore()) { true }
        fun connect(prefix: String) = runBlocking {
            connectRouterOs(RouterOsLogin(lab.getProperty("$prefix.host"), lab.getProperty("$prefix.user"), lab.getProperty("$prefix.password"), RouterOsProtocol.Api), trust, scope)
        }
        val a = connect("chr717")
        val b = connect("chr642")
        val test = mapOf("comment" to "rd-test")
        try {
            val remote = runBlocking {
                val farAs = assertNotNull(b.get(MenuPath.parse("routing/bgp/instance"), null)?.get("as"))
                b.add(MenuPath.parse("routing/bgp/peer"), test + mapOf("name" to "rd-test-bgp", "remote-address" to near, "remote-as" to "65017"))
                a.add(
                    MenuPath.parse("routing/bgp/connection"),
                    test + mapOf("name" to "rd-test-bgp", "as" to "65017", "local.role" to "ebgp", "remote.address" to far, "remote.as" to farAs),
                )
                farAs
            }
            val storage = MemoryStorage()
            val tab = RouterTab(a, lab.getProperty("chr717.host"), bundledPacks().values, storage, TableLayouts(storage))
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            tab.open(MenuEntry("BGP", "routing/bgp/connection"))
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("rd-test-bgp")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(onAllNodes(hasText("Instances")).fetchSemanticsNodes().isEmpty())
            onNodeWithText("Sessions").performClick()
            waitUntil(timeoutMillis = 60_000) { onAllNodes(hasText("Prefix Count")).fetchSemanticsNodes().isNotEmpty() && onAllNodes(hasText(far)).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasText(remote)).assertExists()
            picture("lab-bgp-sessions")
        } finally {
            runBlocking {
                for ((session, menu) in listOf(a to "routing/bgp/connection", b to "routing/bgp/peer")) {
                    val path = MenuPath.parse(menu)
                    val ids = session.watch(path, Filter.Equals("comment", "rd-test")).filterIsInstance<ListEvent.Snapshot>().first().items.map { it.id }
                    if (ids.isNotEmpty()) session.remove(path, ids)
                }
            }
            a.close()
            b.close()
            scope.cancel()
        }
    }

    @Test
    fun theOspfWindowShowsALiveNeighbor() = runDesktopComposeUiTest(1400, 900) {
        val lab = lab()
        val far = lab.getProperty("chr642.segmentAddress").orEmpty()
        assumeTrue("chr642.segmentAddress is empty", far.isNotEmpty())
        Locale.setDefault(Locale.ENGLISH)
        val scope = CoroutineScope(Dispatchers.Default)
        val trust = TrustPolicy(MemoryTrustStore()) { true }
        fun connect(prefix: String) = runBlocking {
            connectRouterOs(RouterOsLogin(lab.getProperty("$prefix.host"), lab.getProperty("$prefix.user"), lab.getProperty("$prefix.password"), RouterOsProtocol.Api), trust, scope)
        }
        val a = connect("chr717")
        val b = connect("chr642")
        val test = mapOf("comment" to "rd-test")
        try {
            runBlocking {
                val address = b.watch(MenuPath.parse("ip/address")).rows().first { it.isNotEmpty() }.single { it["address"]?.substringBefore('/') == far }
                val segment = "${address["network"]}/${address["address"]?.substringAfter('/')}"
                a.add(MenuPath.parse("routing/ospf/instance"), test + mapOf("name" to "rd-test-ospf", "router-id" to lab.getProperty("chr717.segmentAddress")))
                a.add(MenuPath.parse("routing/ospf/area"), test + mapOf("name" to "rd-test-area", "instance" to "rd-test-ospf", "area-id" to "0.0.0.0"))
                val timers = mapOf("hello-interval" to "2s", "dead-interval" to "8s")
                a.add(MenuPath.parse("routing/ospf/interface-template"), test + timers + mapOf("area" to "rd-test-area", "networks" to segment, "type" to "ptp"))
                b.add(MenuPath.parse("routing/ospf/interface"), test + timers + mapOf("interface" to address["interface"].orEmpty(), "network-type" to "point-to-point"))
                b.add(MenuPath.parse("routing/ospf/network"), test + mapOf("network" to segment, "area" to "backbone"))
            }
            val storage = MemoryStorage()
            val tab = RouterTab(a, lab.getProperty("chr717.host"), bundledPacks().values, storage, TableLayouts(storage))
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            tab.open(MenuEntry("OSPF", "routing/ospf/instance"))
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("rd-test-ospf")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Neighbors").performClick()
            waitUntil(timeoutMillis = 90_000) { onAllNodes(hasText("Full")).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasText(far)).assertExists()
            picture("lab-ospf-neighbors")
            onNodeWithText("LSA").performScrollTo().performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("router")).fetchSemanticsNodes().isNotEmpty() }
            onAllNodes(hasText("router")).onLast().performMouseInput { doubleClick() }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("LSA <router ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            picture("lab-ospf-lsa")
        } finally {
            runBlocking {
                for ((session, menu) in listOf(a to "routing/ospf/interface-template", a to "routing/ospf/area", a to "routing/ospf/instance", b to "routing/ospf/network", b to "routing/ospf/interface")) {
                    val path = MenuPath.parse(menu)
                    val ids = session.watch(path, Filter.Equals("comment", "rd-test")).filterIsInstance<ListEvent.Snapshot>().first().items.map { it.id }
                    if (ids.isNotEmpty()) session.remove(path, ids)
                }
            }
            a.close()
            b.close()
            scope.cancel()
        }
    }

    @Test
    fun aSimpleQueueShowsAndChangesUploadAndDownloadApart() = runDesktopComposeUiTest(1400, 900) {
        labRouter { session, host ->
            val path = MenuPath.parse("queue/simple")
            val id = runBlocking { session.add(path, mapOf("name" to "rd-test-queue", "target" to "192.0.2.0/24", "max-limit" to "10M/20M", "comment" to "rd-test")) }
            try {
                val storage = MemoryStorage()
                val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
                setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
                tab.open(MenuEntry("Queues", path.toString()))
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("rd-test-queue")).fetchSemanticsNodes().isNotEmpty() }
                for (cell in listOf("10M", "20M")) assertTrue(onAllNodes(hasText(cell)).fetchSemanticsNodes().isNotEmpty(), cell)
                onNodeWithTag("total-queued-bytes").fetchSemanticsNode()
                picture("lab-simple-queues")
                onNodeWithText("rd-test-queue").performMouseInput { doubleClick() }
                val download = hasSetTextAction() and hasAnyAncestor(hasTestTag("field-download-max-limit"))
                waitUntil(timeoutMillis = 30_000) { onAllNodes(download).fetchSemanticsNodes().isNotEmpty() }
                picture("lab-simple-queue-dialog")
                onNode(download).performTextReplacement("30M")
                onNodeWithText("Apply").performClick()
                waitUntil(timeoutMillis = 10_000) { runBlocking { session.get(path, id) }?.get("max-limit") == "10000000/30000000" }
                onNodeWithText("Traffic").performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Upload Avg. Rate: 0 bps")).fetchSemanticsNodes().isNotEmpty() }
                picture("lab-simple-queue-traffic")
            } finally {
                runBlocking { if (session.get(path, id) != null) session.remove(path, listOf(id)) }
            }
        }
    }

    @Test
    fun safeModeFromTheToolbarRollsTheChangesBack() = runDesktopComposeUiTest(1400, 900) {
        labRouter { session, host ->
            val list = MenuPath.parse("ip/firewall/address-list")
            val entries = Filter.Equals("list", "rd-test-safe")
            val storage = MemoryStorage()
            val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            onNodeWithTag("safe-mode").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Safe Mode 0")).fetchSemanticsNodes().isNotEmpty() }
            runBlocking { session.add(list, mapOf("list" to "rd-test-safe", "address" to "192.0.2.83", "comment" to "rd-test")) }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Safe Mode 1")).fetchSemanticsNodes().isNotEmpty() }
            picture("lab-safe-mode")
            onNodeWithTag("safe-mode").performClick()
            onNodeWithText("Roll back changes").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Safe Mode")).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, runBlocking { session.count(list, entries) })
        }
    }

    @Test
    fun theHintOfUndoNamesTheActionItWouldTakeBack() = runDesktopComposeUiTest(1280, 800) {
        labRouter { session, host ->
            val list = MenuPath.parse("ip/firewall/address-list")
            val entry = runBlocking { session.add(list, mapOf("list" to "rd-test-undo", "address" to "192.0.2.86", "comment" to "rd-test")) }
            try {
                val storage = MemoryStorage()
                val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
                setContent { NetworkHelmTheme(dark = true) { HintHost { MainScreen(tab, onDisconnect = {}) } } }
                onNodeWithTag("undo").performMouseInput { moveTo(center) }
                val hint = "Undo: address list entry added (${lab().getProperty("chr.user")})"
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText(hint)).fetchSemanticsNodes().isNotEmpty() }
            } finally {
                runBlocking { session.remove(list, listOf(entry)) }
            }
        }
    }

    @Test
    fun aFileGoesUpAndComesDownOverSftp() = runDesktopComposeUiTest(1400, 900) { labRouter { session, host -> fileRoundTrip(session, host, "lab-files") } }

    @Test
    fun aFileGoesUpAndComesDownOverSftpOnRouterOs6() = runDesktopComposeUiTest(1400, 900) { labRouter("chr6") { session, host -> fileRoundTrip(session, host, "lab6-files") } }

    private fun SkikoComposeUiTest.fileRoundTrip(session: DeviceSession, host: String, shot: String) {
        val name = "rd-test-upload.txt"
        val saved = mutableListOf<Pair<String, String>>()
        val exchange = object : FileExchange {
            override suspend fun pick() = listOf(LocalFile(name) { "rd-test 22.10".encodeToByteArray() })
            override suspend fun save(name: String, bytes: ByteArray): Boolean {
                saved += name to bytes.decodeToString()
                return true
            }
        }
        val storage = MemoryStorage()
        val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        val files = MenuPath.parse("file")
        try {
            tab.open(MenuEntry("Files", "file"))
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("skins")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Upload").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText(name)).fetchSemanticsNodes().size >= 2 }
            onAllNodes(hasText(name)).onFirst().performClick()
            onNodeWithText("Download").performClick()
            waitUntil(timeoutMillis = 30_000) { saved.isNotEmpty() }
            assertEquals(name to "rd-test 22.10", saved.single())
            picture(shot)
            onNodeWithText("Clear finished").performClick()
            onNodeWithTag("files-delete").performClick()
            onAllNodes(hasText("Delete")).onLast().performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText(name)).fetchSemanticsNodes().isEmpty() }
        } finally {
            if (runBlocking { session.count(files, Filter.Equals("name", name)) } > 0) {
                runBlocking { session.run(files, "remove", mapOf("numbers" to name)).toList() }
            }
        }
    }

    @Test
    fun pingAndTracerouteOfTheLabRouterToItself() = runDesktopComposeUiTest(1400, 900) {
        labRouter { session, host ->
            val storage = MemoryStorage()
            val tab = RouterTab(session, host, bundledPacks().values, storage, TableLayouts(storage))
            setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
            fun run(title: String, path: String, count: String) {
                tab.workspace.windows.toList().forEach(tab.workspace::close)
                tab.open(MenuEntry(title, path))
                waitUntil(timeoutMillis = 30_000) { onAllNodes(hasTestTag("field-address")).fetchSemanticsNodes().isNotEmpty() }
                onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-address"))).performTextInput("127.0.0.1")
                val advanced = onAllNodes(hasTestTag("field-count")).fetchSemanticsNodes().isEmpty()
                if (advanced) onNodeWithText("Advanced").performClick()
                onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-count"))).performTextInput(count)
                if (advanced) onNodeWithText("Basic").performClick()
                onNodeWithText("Start").performClick()
            }
            run("Ping", "tool/ping", "3")
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Sent: 3")).fetchSemanticsNodes().isNotEmpty() }
            picture("lab-ping")
            run("Traceroute", "tool/traceroute", "1")
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("127.0.0.1")).fetchSemanticsNodes().size >= 2 }
            picture("lab-traceroute")
        }
    }

    private class Recorded(private val ssh: SshSession) : SshSession by ssh {
        val output = StringBuffer()
        val input = StringBuffer()
        val sizes = mutableListOf<Pair<Int, Int>>()

        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel {
            val channel = ssh.shell(term, columns, rows)
            return object : ShellChannel by channel {
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    channel.read(buffer, offset, length).also { if (it > 0) output.append(buffer.decodeToString(offset, offset + it)) }

                override suspend fun write(bytes: ByteArray) {
                    input.append(bytes.decodeToString())
                    channel.write(bytes)
                }

                override fun resize(columns: Int, rows: Int) {
                    sizes += columns to rows
                    channel.resize(columns, rows)
                }
            }
        }
    }

    @Test
    fun bossTermAnswersTheConsoleOfTheLabRouter() = runDesktopComposeUiTest(1100, 700) { labRouter { session, _ -> console(session, "lab-terminal") } }

    @Test
    fun bossTermAnswersTheConsoleOfRouterOs6() = runDesktopComposeUiTest(1100, 700) { labRouter("chr6") { session, _ -> console(session, "lab6-terminal") } }

    @Test
    fun theTerminalOpensAgainAndAgainOnOneConnection() {
        labRouter { session, _ ->
            repeat(6) { round ->
                val ssh = Recorded(assertNotNull(session.ssh))
                runDesktopComposeUiTest(1100, 700) {
                    val started = System.currentTimeMillis()
                    setContent { NetworkHelmTheme(dark = true) { TerminalView(ssh) { channel, modifier -> BossTerminal(channel, modifier) } } }
                    waitUntil(timeoutMillis = 25_000) { "] > " in Regex("\u001b\\[[0-9;?]*[A-Za-z]").replace(ssh.output, "") }
                    assertTrue(System.currentTimeMillis() - started < 8_000, "the prompt of round $round came late")
                    assertTrue(Regex("\u001b\\[\\d+;\\d+R").containsMatchIn(ssh.input), "round $round: the terminal answers the cursor queries")
                }
            }
        }
    }

    @Test
    fun aTerminalEchoesWhatIsTyped() = runDesktopComposeUiTest(1100, 700) {
        labRouter { session, _ ->
            val ssh = Recorded(assertNotNull(session.ssh))
            setContent { NetworkHelmTheme(dark = true) { TerminalView(ssh) { channel, modifier -> BossTerminal(channel, modifier) } } }
            waitUntil(timeoutMillis = 25_000) { "] > " in Regex("\u001b\\[[0-9;?]*[A-Za-z]").replace(ssh.output, "") }
            val heard = ssh.output.length
            onRoot().performKeyInput {
                keyDown(Key.D)
                keyUp(Key.D)
                keyDown(Key.E)
                keyUp(Key.E)
            }
            waitUntil(timeoutMillis = 15_000) { ssh.input.endsWith("de") && ssh.output.length > heard + 1 }
        }
    }

    private fun SkikoComposeUiTest.console(session: DeviceSession, shot: String) {
        val ssh = Recorded(assertNotNull(session.ssh))
        val started = System.currentTimeMillis()
        setContent { NetworkHelmTheme(dark = true) { TerminalView(ssh) { channel, modifier -> BossTerminal(channel, modifier) } } }
        waitUntil(timeoutMillis = 25_000) { "] > " in Regex("\u001b\\[[0-9;?]*[A-Za-z]").replace(ssh.output, "") }
        val prompt = System.currentTimeMillis() - started
        assertTrue(prompt < 8_000, "the prompt came after $prompt ms")
        assertTrue(Regex("\u001b\\[\\d+;\\d+R").containsMatchIn(ssh.input), "the terminal answers the cursor queries itself")
        assertTrue(ssh.sizes.isNotEmpty(), "the terminal sizes the console to its grid")
        picture(shot)
    }
}
