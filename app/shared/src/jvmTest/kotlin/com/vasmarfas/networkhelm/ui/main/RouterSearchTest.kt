package com.vasmarfas.networkhelm.ui.main

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.vasmarfas.networkhelm.App
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.Shell
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.search.SearchLimit
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.test.TestScope
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class RouterSearchTest {

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    @Test
    fun theFieldOfTheSearchHasTheKeyboardWhenTheDialogOpens() = runDesktopComposeUiTest(900, 600) {
        Locale.setDefault(Locale.ENGLISH)
        setContent { NetworkHelmTheme { RouterSearchDialog(RouterSearch(TestScope()), emptyList(), { _, _ -> }) {} } }
        waitForIdle()
        onNode(isFocused()).assert(hasTestTag("router-search-query") or hasAnyAncestor(hasTestTag("router-search-query")))
    }

    @Test
    fun anAddressIsFoundOnEveryRouterAndOpensWhereItWasFound() = runDesktopComposeUiTest(1200, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val office = FakeRouter(
            id = "rd-test-office",
            rows = mapOf(
                "ip/address" to listOf(Item("*1", mapOf("address" to "192.168.88.1/24", "interface" to "bridge"))),
                "ip/arp" to listOf(
                    Item("*2", mapOf("address" to "192.168.88.10", "mac-address" to "B8:69:F4:12:34:56", "interface" to "bridge")),
                    Item("*3", mapOf("address" to "192.168.88.11", "mac-address" to "B8:69:F4:12:34:57", "interface" to "bridge")),
                ),
                "ip/route" to listOf(
                    Item("*4", mapOf("dst-address" to "192.168.88.0/24", "gateway" to "bridge", "dynamic" to "true")),
                    Item("*5", mapOf("dst-address" to "10.10.0.0/16", "gateway" to "192.168.88.10", "dynamic" to "false", "comment" to "rd-test")),
                ),
            ),
        )
        val home = FakeRouter(
            id = "rd-test-home",
            rows = mapOf(
                "ip/dhcp-server/lease" to listOf(Item("*A", mapOf("address" to "192.168.88.10", "mac-address" to "B8:69:F4:12:34:56", "host-name" to "printer"))),
                "ip/firewall/address-list" to List(SearchLimit + 1) { Item("*${it + 1}", mapOf("list" to "rd-test", "address" to "198.51.100.$it")) },
            ),
        )
        lateinit var shell: Shell
        setContent {
            val scope = rememberCoroutineScope()
            shell = remember { Shell(Services(MemoryStorage(), MemoryTrustStore(), neighbors = null), scope) }
            App(shell)
        }
        runOnIdle {
            shell.add(office, "192.0.2.1")
            shell.add(home, "192.0.2.2")
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("menu-search")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("menu-search").performClick()
        onNodeWithTag("menu-search").performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("router-search-query")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("router-search-query").performTextInput("192.168.88.10")
        onNodeWithTag("router-search-query").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("router-search-status") and hasText("Found: 4")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(4, onAllNodes(hasTestTag("router-search-hit")).fetchSemanticsNodes().size)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("IP › Firewall › Address Lists: 20001 entries, too many to search")).fetchSemanticsNodes().isNotEmpty() }
        picture("router-search")
        repeat(3) { onNodeWithTag("router-search-query").performKeyInput { pressKey(Key.DirectionDown) } }
        onNodeWithTag("router-search-query").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertFalse(shell.searchShown)
        assertEquals(1, shell.selected)
        val opened = shell.routers[1].items.values.single()
        assertEquals(MenuPath.parse("ip/dhcp-server/lease") to "*A", opened.path to opened.id)
    }
}
