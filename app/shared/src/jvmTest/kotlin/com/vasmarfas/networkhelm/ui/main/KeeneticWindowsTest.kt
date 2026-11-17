package com.vasmarfas.networkhelm.ui.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.LocalSettings
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.Settings
import com.vasmarfas.networkhelm.demo.demoRouter
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackTab
import com.vasmarfas.networkhelm.packs.PackWindow
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.packs.packName
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class KeeneticWindowsTest {

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    @Test
    fun clientsShowAsTheWebInterfaceNamesThem() = runDesktopComposeUiTest(1280, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val properties = listOf("name", "ip", "mac", "segment", "connection", "online", "uptime", "rxbytes", "txbytes", "access", "static-ip")
        val schema = MenuSchema(MenuPath.parse("clients"), NodeKind.List, emptyList(), listOf("print"), properties.map { Argument(it, ValueType.Text(), readOnly = true) })
        val clients = listOf(
            Item("02:00:5e:10:00:01", mapOf("name" to "rd-test", "ip" to "192.168.1.33", "mac" to "02:00:5e:10:00:01", "segment" to "Home", "connection" to "rd-test-wifi (2.4 GHz)", "online" to "true", "rxbytes" to "123456", "txbytes" to "412809221", "uptime" to "1h2m5s", "access" to "permit")),
            Item("02:00:5e:10:00:02", mapOf("name" to "rd-test tv", "ip" to "192.168.1.40", "mac" to "02:00:5e:10:00:02", "segment" to "Home", "connection" to "Port 2", "online" to "false", "access" to "deny", "static-ip" to "192.168.1.40")),
        )
        val router = FakeRouter(mapOf("clients" to clients), schemas = mapOf("clients" to schema), platform = Platform.Keenetic, policies = setOf("read"))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Clients", "clients"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test tv")).fetchSemanticsNodes().isNotEmpty() }
        for (column in listOf("IP Address", "MAC Address", "Segment", "Connected Via", "Internet Access", "Permanent IP", "393.7 MiB")) onAllNodes(hasText(column)).onFirst().assertExists()
        assertTrue(onAllNodes(hasText("New")).fetchSemanticsNodes().isEmpty())
        picture("keenetic-clients")
    }

    @Test
    fun theTagsOfAUserAreNamedOneByOne() = runDesktopComposeUiTest(1280, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val labels = mapOf("cli" to "Command line", "http" to "Web interface", "readonly" to "Read only")
        val arguments = listOf(Argument("name", ValueType.Text()), Argument("tag", ValueType.Enumeration(multiple = true), labels.keys.toList(), labels = labels))
        val schema = MenuSchema(MenuPath.parse("user"), NodeKind.List, emptyList(), listOf("print"), arguments)
        val users = listOf(Item("family", mapOf("name" to "family", "tag" to "http,readonly,vpn-oc")))
        val router = FakeRouter(mapOf("user" to users), schemas = mapOf("user" to schema), platform = Platform.Keenetic, policies = setOf("read"))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Users and Access", "user"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Web interface, Read only, vpn-oc")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theDashboardShowsTheInternetTheNetworksThePortsAndTheSystem() = runDesktopComposeUiTest(1400, 900) {
        Locale.setDefault(Locale.ENGLISH)
        val session = assertNotNull(demoRouter("keenetic.demo"))
        val storage = MemoryStorage()
        val tab = RouterTab(session, "keenetic.demo", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Dashboard", "status", window = "dashboard"))
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("CPU")).fetchSemanticsNodes().isNotEmpty() }
        for (text in listOf("Network Ports", "1000 FDX", "Home network", "Demo-2.4", "203.0.113.120", "Keenetic Giga (KN-1011)", "Wi-Fi Monitor", "Channel 44", "WireGuard VPN", "Traffic Monitor", "nas", "Serial Number")) {
            onAllNodes(hasText(text, substring = true)).onFirst().assertExists()
        }
        picture("keenetic-dashboard")
    }

    @Test
    fun aGroupOfTheMenuOpensDownwardWhenTheSettingsSaySo() = runDesktopComposeUiTest(1280, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val root = MenuSchema(MenuPath.parse(""), NodeKind.Directory, listOf("clients", "segments"), emptyList(), emptyList())
        val router = FakeRouter(schemas = mapOf("" to root), platform = Platform.Keenetic, policies = setOf("read"))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { CompositionLocalProvider(LocalSettings provides Settings(menuBelow = true)) { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("My Networks")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("My Networks").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Segments")).fetchSemanticsNodes().isNotEmpty() }
        val group = onNodeWithText("My Networks").getBoundsInRoot()
        val child = onNodeWithText("Segments").getBoundsInRoot()
        assertTrue(child.left < group.right && child.top >= group.bottom, "$child is not under $group")
        onNodeWithText("My Networks").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Segments")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theMenuInTheWordsOfTheRouterKeepsTheIconsOfItsEntries() = runDesktopComposeUiTest(1280, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val root = MenuSchema(MenuPath.parse(""), NodeKind.Directory, listOf("clients", "segments"), emptyList(), emptyList())
        val all = Pack(platform = Platform.Keenetic.packName, menu = listOf("Все настройки", "Интерфейсы"), window = PackWindow("Интерфейсы", listOf(PackTab("Bridge0", "rc/interface/Bridge0")), selector = true))
        val words = mapOf("My Networks" to "Мои сети и Wi-Fi", "All Settings" to "Все настройки", "Interfaces" to "Интерфейсы")
        val router = FakeRouter(schemas = mapOf("" to root), platform = Platform.Keenetic, made = listOf(all), words = words)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        waitUntil(timeoutMillis = 5_000) { tab.menu?.any { it.title == "Все настройки" } == true }
        val icons = tab.menu.orEmpty().associate { it.title to it.icon }
        assertEquals("My Networks", icons["Мои сети и Wi-Fi"])
        assertEquals("All Settings", icons["Все настройки"])
        assertEquals("Interfaces", tab.menu.orEmpty().single { it.title == "Все настройки" }.items.single().icon)
    }
}
