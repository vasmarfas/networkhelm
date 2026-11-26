package com.vasmarfas.networkhelm.ui.main

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InterfacesTest {

    private val interfaces = listOf(
        Item(
            "*1",
            mapOf(
                "name" to "ether1", "type" to "ether", "actual-mtu" to "1500", "l2mtu" to "1514", "running" to "true",
                "tx-byte" to "36279223", "rx-byte" to "10933992", "last-link-up-time" to "2026-10-24 09:27:56", "link-downs" to "0",
                "tx-packet" to "512", "rx-packet" to "640", "tx-drop" to "2", "rx-drop" to "7", "tx-queue-drop" to "1", "tx-error" to "0", "rx-error" to "3",
            ),
        ),
        Item("*2", mapOf("name" to "bridge1", "type" to "bridge", "actual-mtu" to "1500", "running" to "true")),
    )
    private val ethernet = listOf(Item("*1", mapOf("name" to "ether1", "default-name" to "ether1", "mtu" to "1500", "arp" to "enabled", "mac-address" to "00:00:5E:00:53:ED")))
    private val fastPath = mapOf("fp-tx-packets-per-second" to "0", "fp-rx-packets-per-second" to "206")
    private val traffic = (0..1).map { round ->
        listOf(
            Item("", mapOf("name" to "ether1", "tx-bits-per-second" to "151880", "rx-bits-per-second" to "102400", ".section" to "$round") + fastPath),
            Item("", mapOf("name" to "bridge1", "tx-bits-per-second" to "2688", "rx-bits-per-second" to "0", ".section" to "$round") + fastPath),
        )
    }.flatten()

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    @Test
    fun ratesComeFromTheMonitorAndAnEthernetRowOpensItsOwnDialog() = runDesktopComposeUiTest(1280, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val ethernetSchema = MenuSchema(
            MenuPath.parse("interface/ethernet"), NodeKind.List, emptyList(), listOf("print", "set", "unset"),
            listOf(Argument("name", ValueType.Text()), Argument("mtu", ValueType.Integer(0, 65536)), Argument("arp", ValueType.Enumeration(), listOf("disabled", "enabled", "proxy-arp"))),
        )
        val router = FakeRouter(
            mapOf("interface" to interfaces, "interface/ethernet" to ethernet),
            schemas = mapOf("interface/ethernet" to ethernetSchema),
            output = mapOf(
                "interface/monitor-traffic" to traffic,
                "interface/ethernet/monitor" to listOf(Item("", mapOf("name" to "ether1", "status" to "link-ok", "rate" to "1Gbps"))),
            ),
        )
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Interfaces", "interface"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("151.8 kbps")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("interface/monitor-traffic" to mapOf("interface" to "ether1,bridge1"), router.ran.first())
        assertEquals(interfaces.size, onAllNodes(hasTestTag("cell-icon")).fetchSemanticsNodes().size)
        picture("interfaces")

        onNodeWithText("ether1").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Interface <ether1>")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("00:00:5E:00:53:ED")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("status")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("status").assertTextEquals("Status: link-ok")
        picture("interfaces-dialog")
        onAllNodes(hasText("Traffic")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("2.6 kbps / 0 bps")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Tx/Rx").assertExists()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("34.6 MiB / 10.4 MiB")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Tx/Rx Bytes").assertExists()
        // the rows of the router's own description: drops and errors are counters of the interface, as WinBox shows them
        for ((title, value) in listOf("FP Tx/Rx Packet (p/s)" to "0 / 206", "Tx/Rx Packets" to "512 / 640", "Tx/Rx Drops" to "2 / 7", "Tx Queue Drops" to "1", "Tx/Rx Errors" to "0 / 3")) {
            onNodeWithText(title).assertExists()
            onAllNodes(hasText(value)).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "$title $value") }
        }
        picture("interfaces-traffic")
        onAllNodes(hasText("Status")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("2026-10-24 09:27:56")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Cancel").performClick()

        onNodeWithText("Ethernet").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("ether")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("R")).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithContentDescription("All tabs").performClick()
        picture("interfaces-tab-list")
        onAllNodes(hasText("LTE")).onLast().performClick()
        val spec = tab.specs.values.single()
        assertEquals("LTE", spec.tabs[spec.selected].title)
        waitUntil(timeoutMillis = 5_000) { onNodeWithText("LTE").fetchSemanticsNode().boundsInRoot.width > 0f }
        picture("interfaces-lte")
    }

    @Test
    fun thePppWindowListsOnlyPppInterfacesAndAddsThemByKind() = runDesktopComposeUiTest(1280, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val listOnly = MenuSchema(MenuPath.parse("interface"), NodeKind.List, emptyList(), listOf("print", "set"), listOf(Argument("disabled", ValueType.YesNo)))
        val client = Item("*3", mapOf("name" to "rd-test-sstp", "type" to "sstp-out", "disabled" to "true"))
        val router = FakeRouter(mapOf("interface" to interfaces + client), schemas = mapOf("interface" to listOnly))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = false) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("PPP", "ppp/secret"))
        onNodeWithText("Interface").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test-sstp")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("ether1")).fetchSemanticsNodes().isEmpty(), "only PPP interfaces")
        assertEquals(1, onAllNodes(hasTestTag("cell-icon")).fetchSemanticsNodes().size)

        onNodeWithText("New").performClick()
        onNodeWithText("SSTP Client").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.items.isNotEmpty() }
        assertEquals(MenuPath.parse("interface/sstp-client"), tab.items.values.single().path)
        picture("ppp-new-client")

        tab.open(MenuEntry("Interfaces", "interface"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("ether1")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(setOf("PPP", "Interfaces"), tab.specs.values.map { it.title }.toSet())
    }
}
