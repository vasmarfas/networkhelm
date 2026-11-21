package com.vasmarfas.networkhelm.ui.main

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.HiddenValue
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LinuxVpnWindowsTest {

    private val server = "vpn/wireguard/server"

    // the fields and the proposal the host gives the window of a new server, cut to one field a tab
    private fun router() = FakeRouter(
        schemas = mapOf(
            server to MenuSchema(
                MenuPath.parse(server), NodeKind.Settings, emptyList(), listOf("print", "create"),
                listOf(
                    Argument("name", ValueType.Text(), label = "Name", tab = "general"),
                    Argument("client-endpoint", ValueType.Text(), optional = true, label = "Address for Clients", tab = "general"),
                    Argument("address", ValueType.Text(), label = "Address", tab = "network"),
                    Argument("nat", ValueType.YesNo, label = "NAT for Clients", tab = "nat"),
                ),
            ),
        ),
        settings = mapOf(server to Item("", mapOf("name" to "wg1", "client-endpoint" to "192.168.10.250", "address" to "10.9.0.1/24", "nat" to "true"))),
        output = mapOf("$server/create" to listOf(Item("wg1", mapOf("name" to "wg1", "conf" to "[Interface]\nAddress = 10.9.0.2/32\n\n[Peer]\nEndpoint = 192.168.10.250:51821")))),
        platform = Platform.Ssh,
    )

    @Test
    fun aNewServerComesFilledInAndGivesTheConfigurationOfItsClientAtOnce() = runDesktopComposeUiTest(900, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val router = router()
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { LinuxVpnView(tab, VpnCall(server)) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("field-name").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, onAllNodesWithTag("vpn-form-private").fetchSemanticsNodes().size, "192.168.10.250 is out of reach of clients on the internet")
        onNodeWithText("Network").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("field-address").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag("vpn-form-run").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("output")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("$server/create" to mapOf("name" to "wg1", "client-endpoint" to "192.168.10.250", "address" to "10.9.0.1/24", "nat" to "true"), router.ran.single())
        assertEquals(1, onAllNodesWithTag("vpn-form-done").fetchSemanticsNodes().size)
    }

    @Test
    fun theProfilesOfANewServerOfIkev2OpenOneAfterAnotherUnderTheNamesOfTheirFiles() = runDesktopComposeUiTest(900, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val ikev2 = "vpn/ipsec/server"
        val router = FakeRouter(
            schemas = mapOf(
                ikev2 to MenuSchema(MenuPath.parse(ikev2), NodeKind.Settings, emptyList(), listOf("print", "create"), listOf(Argument("name", ValueType.Text(), label = "Name", tab = "general"))),
            ),
            settings = mapOf(ikev2 to Item("", mapOf("name" to "ikev2"))),
            output = mapOf(
                "$ikev2/create" to listOf(
                    Item("ikev2", mapOf("name" to "ikev2", "conf" to "ikev2: IKEv2, EAP-MSCHAPv2\nserver: 192.0.2.1\nlogin: client1\npassword: rd-test")),
                    Item("ikev2", mapOf("name" to "ikev2", "file" to "ikev2.sswan", "conf" to "{\"type\":\"ikev2-eap\"}")),
                ),
            ),
            platform = Platform.Ssh,
        )
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { LinuxVpnView(tab, VpnCall(ikev2)) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("field-name").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("vpn-form-run").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Client Config").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("password: $HiddenValue", substring = true)).fetchSemanticsNodes().isNotEmpty(), "the login shows with its password hidden")
        assertTrue(onAllNodes(hasText("rd-test", substring = true)).fetchSemanticsNodes().isEmpty())
        onNodeWithTag("output-cross").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("ikev2.sswan").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("output-cross").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("output")).fetchSemanticsNodes().isEmpty() }
    }
}
