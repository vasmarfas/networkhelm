package com.vasmarfas.networkhelm.ui.main

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.ui.chart.bits
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LinuxVpnPanelTest {

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private val key = "rdTestClientPublic".padEnd(43, 'A') + "="

    private fun router(): FakeRouter {
        val now = System.currentTimeMillis() / 1000
        return FakeRouter(
            rows = mapOf(
                "vpn/service" to listOf(
                    Item(
                        "wireguard/wg0",
                        mapOf("kind" to "WireGuard", "name" to "wg0", "state" to "active", "port" to "51820", "protocol" to "udp", "sessions" to "1", "rx-rate" to "8000", "tx-rate" to "2000"),
                    ),
                    Item(
                        "openvpn/server",
                        mapOf("kind" to "OpenVPN", "name" to "server", "state" to "failed", "port" to "1194", "protocol" to "udp", "sessions" to "0", "rx-rate" to "", "tx-rate" to ""),
                    ),
                ),
                "vpn/session" to listOf(
                    Item(
                        "wireguard/wg0/$key",
                        mapOf(
                            "kind" to "WireGuard", "service" to "wg0", "user" to "lab-client", "public-key" to key, "endpoint" to "192.168.10.249:59837", "address" to "10.66.0.2/32",
                            "rx" to "8448", "tx" to "2592", "rx-rate" to "8000", "tx-rate" to "2000", "last-handshake" to "32s", "peer" to "wg0/$key",
                        ),
                    ),
                    Item(
                        "openvpn/server/0",
                        mapOf(
                            "kind" to "OpenVPN", "service" to "server", "user" to "laptop", "endpoint" to "198.51.100.20:40112", "address" to "10.8.0.2", "since" to "2026-11-17 21:01:36",
                            "uptime" to "10m", "rx" to "123456", "tx" to "654321", "rx-rate" to "0", "tx-rate" to "0", "cipher" to "AES-256-GCM", "peer" to "",
                        ),
                    ),
                    Item(
                        "amneziawg/awg0/$key",
                        mapOf(
                            "kind" to "AmneziaWG", "service" to "awg0", "user" to "awg-phone", "public-key" to key, "endpoint" to "203.0.113.7:40000", "address" to "10.8.0.2/32",
                            "rx" to "1024", "tx" to "2048", "rx-rate" to "", "tx-rate" to "", "last-handshake" to "6s", "peer" to "awg0/$key",
                        ),
                    ),
                ),
                "vpn/history" to listOf(
                    Item("server/${now - 600}/laptop/198.51.100.20:40112", mapOf("user" to "laptop", "service" to "server", "connected" to "rd-test-start", "start" to "${now - 600}", "seconds" to "")),
                    Item(
                        "server/${now - 7200}/tablet/203.0.113.9:51000",
                        mapOf("user" to "tablet", "service" to "server", "connected" to "rd-test-earlier", "disconnected" to "rd-test-end", "reason" to "remote-exit", "start" to "${now - 7200}", "seconds" to "1800"),
                    ),
                    Item("server/${now - 400_000}/tablet/203.0.113.9:51001", mapOf("user" to "tablet", "service" to "server", "connected" to "rd-test-days-ago", "start" to "${now - 400_000}", "seconds" to "60")),
                ),
                "vpn/collector" to listOf(Item("collector", mapOf("installed" to "false", "runner" to "none", "size" to "0"))),
            ),
            output = mapOf(
                "vpn/wireguard/peer/client-config" to listOf(Item("", mapOf("conf" to "[Interface]\nAddress = 10.66.0.2/32\n\n[Peer]\nEndpoint = 192.168.10.250:51820"))),
                "vpn/amneziawg/peer/client-config" to listOf(Item("", mapOf("conf" to "[Interface]\nAddress = 10.8.0.2/32\nJc = 4\n\n[Peer]\nEndpoint = 192.168.10.250:51821"))),
            ),
            platform = Platform.Ssh,
        )
    }

    @Test
    fun thePanelShowsTheServicesAndSessionsAndEndsOrConfiguresTheChosenOne() = runDesktopComposeUiTest(1200, 1300) {
        Locale.setDefault(Locale.ENGLISH)
        val router = router()
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { LinuxVpnPanelView(tab) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("lab-client")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, onAllNodesWithTag("vpn-tile").fetchSemanticsNodes().size)
        // the stopped server has no rate, and the chart goes on with wg0
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Now ${bits(8000.0)}", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, onAllNodes(hasText("rd-test-earlier")).fetchSemanticsNodes().size, "a session of today is in the log of the day")
        assertEquals(0, onAllNodes(hasText("rd-test-days-ago")).fetchSemanticsNodes().size, "one of four days ago is not")
        picture("vpn-panel")

        onNodeWithText("lab-client").performClick()
        onNodeWithTag("vpn-client-config").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("output")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("vpn/wireguard/peer/client-config" to mapOf("number" to "wg0/$key"), router.ran.single())
        onNodeWithTag("output-cross").performClick()

        onNodeWithTag("vpn-disconnect").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("confirm").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("confirm").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { router.ran.size == 2 }
        assertEquals("vpn/session/disconnect" to mapOf("numbers" to "wireguard/wg0/$key"), router.ran.last())

        onNodeWithText("Month").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rd-test-days-ago")).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag("vpn-collector-install").performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.size == 3 }
        assertEquals("vpn/collector/install" to emptyMap<String, String>(), router.ran.last())

        // the peer of a session of AmneziaWG is one of its own window
        onNodeWithText("awg-phone").performClick()
        onNodeWithTag("vpn-client-config").performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.size == 4 }
        assertEquals("vpn/amneziawg/peer/client-config" to mapOf("number" to "awg0/$key"), router.ran.last())
    }
}
