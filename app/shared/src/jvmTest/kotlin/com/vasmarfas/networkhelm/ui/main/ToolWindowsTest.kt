package com.vasmarfas.networkhelm.ui.main

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ToolWindowsTest {

    private fun SkikoComposeUiTest.show(router: DeviceSession, entry: MenuEntry) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(entry)
    }

    private fun SkikoComposeUiTest.present(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun SkikoComposeUiTest.start() {
        waitUntil(timeoutMillis = 5_000) { present("Start") }
        onNodeWithText("Start").performClick()
    }

    @Test
    fun aCommandWithoutParametersSaysWhatStartDoesAndThatItWentThrough() = runDesktopComposeUiTest(1100, 700) {
        val reboot = MenuPath.parse("system/reboot")
        val router = FakeRouter(schemas = mapOf(reboot.toString() to MenuSchema(reboot, NodeKind.Command, emptyList(), emptyList(), emptyList())))
        show(router, MenuEntry("Reboot", reboot.toString()))
        waitUntil(timeoutMillis = 5_000) { present("Reboot takes no parameters. Start runs it on the router.") }
        start()
        waitUntil(timeoutMillis = 5_000) { present("Run Reboot?") }
        assertTrue(router.ran.isEmpty(), "nothing runs before the answer")
        onNode(hasText("Reboot") and hasAnyAncestor(hasTestTag("confirm"))).performClick()
        waitUntil(timeoutMillis = 5_000) { present("Done") }
        assertEquals(listOf("system/reboot"), router.ran.map { it.first })
    }

    @Test
    fun thePingTableTitlesItsTimeInMillisecondsAndItsSizeAsTheReplySizeWhileTheFormKeepsPacketSize() = runDesktopComposeUiTest(1100, 700) {
        val ping = MenuPath.parse("tool/ping")
        val schema = MenuSchema(ping, NodeKind.Command, emptyList(), emptyList(), listOf(Argument("address", ValueType.Ip4), Argument("size", ValueType.Integer(28, 65535))))
        val replies = (0..1).map {
            Item("", mapOf("seq" to "$it", "host" to "127.0.0.1", "time" to "${64 + it}us", "size" to "56", "ttl" to "64", "sent" to "${it + 1}", "received" to "${it + 1}", "min-rtt" to "64us", "avg-rtt" to "65us", "max-rtt" to "1ms396us"))
        }
        show(FakeRouter(schemas = mapOf(ping.toString() to schema), output = mapOf(ping.toString() to replies)), MenuEntry("Ping", ping.toString()))
        waitUntil(timeoutMillis = 5_000) { present("Start") }
        onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-address"))).performTextInput("127.0.0.1")
        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { present("0.065") }
        for (shown in listOf("Time (ms)", "Reply Size", "0.064", "Min: 0.064 ms", "Avg: 0.065 ms", "Max: 1.396 ms")) assertTrue(present(shown), shown)
        assertFalse(present("Packet Size"), "the form shows it on its second tab")
        onNodeWithText("Advanced").performClick()
        waitUntil(timeoutMillis = 5_000) { present("Packet Size") }
    }

    @Test
    fun theTableOfTracerouteHasHopAndHostAndItsFormAsksForTracerouteTo() = runDesktopComposeUiTest(1100, 700) {
        val traceroute = MenuPath.parse("tool/traceroute")
        val schema = MenuSchema(traceroute, NodeKind.Command, emptyList(), emptyList(), listOf(Argument("address", ValueType.Text())))
        val hops = listOf("192.0.2.1", "198.51.100.1").map { Item("", mapOf("address" to it, "loss" to "0", ".section" to "0")) }
        show(FakeRouter(schemas = mapOf(traceroute.toString() to schema), output = mapOf(traceroute.toString() to hops)), MenuEntry("Traceroute", traceroute.toString()))
        waitUntil(timeoutMillis = 5_000) { present("Traceroute To") }
        start()
        waitUntil(timeoutMillis = 5_000) { present("198.51.100.1") }
        for (shown in listOf("Hop", "Host")) assertTrue(present(shown), shown)
        assertFalse(present("Address"))
    }

    @Test
    fun torchStartsOnTheInterfaceOfTheFirstNumberAsWinBoxDoes() = runDesktopComposeUiTest(1100, 700) {
        val torch = MenuPath.parse("tool/torch")
        val schema = MenuSchema(torch, NodeKind.Command, emptyList(), emptyList(), listOf(Argument("interface", ValueType.Text())))
        val interfaces = listOf(Item("*2", mapOf("name" to "ether1")), Item("*B", mapOf("name" to "vlan1")), Item("*1", mapOf("name" to "lo")))
        val flows = listOf(Item("", mapOf("ip-protocol" to "tcp", "src-address" to "192.0.2.10", "tx" to "1000", "rx" to "0", "tx-packets" to "1", "rx-packets" to "0", ".section" to "1")))
        val router = FakeRouter(mapOf("interface" to interfaces), schemas = mapOf(torch.toString() to schema), output = mapOf(torch.toString() to flows))
        show(router, MenuEntry("Torch", torch.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-interface")) and hasText("lo")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.isNotEmpty() }
        assertEquals(listOf("tool/torch" to mapOf("interface" to "lo")), router.ran.toList())
    }
}
