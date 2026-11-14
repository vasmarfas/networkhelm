package com.vasmarfas.networkhelm.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.App
import com.vasmarfas.networkhelm.Shell
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.nettools.MacRegistry
import com.vasmarfas.networkhelm.nettools.WellKnownPorts
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.test.TestScope
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ToolsDialogTest {

    private fun ComposeUiTest.showPane(pane: @Composable () -> Unit) = setContent {
        NetworkHelmTheme {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { pane() }
        }
    }

    private fun ComposeUiTest.shown(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.shown(tag: String, text: String) = onAllNodes(hasTestTag(tag) and hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theConnectScreenOpensTheToolsAndTheCalculatorAnswersAsTheNetworkIsTyped() = runDesktopComposeUiTest(1000, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val shell = Shell(Services(MemoryStorage(), MemoryTrustStore(), neighbors = null), TestScope())
        setContent { App(shell) }
        onNodeWithTag("tools").performClick()
        waitUntil(timeoutMillis = 5_000) { shell.toolsShown }
        onNodeWithTag("tool-Subnet").performClick()
        onNodeWithTag("subnet-input").performTextClearance()
        onNodeWithTag("subnet-input").performTextInput("10.1.2.3/20")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("10.1.0.0/20")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("255.255.240.0").assertExists()
        onNodeWithText("10.1.15.255").assertExists()
        onNodeWithText("4096").assertExists()
        onNodeWithTag("tools-close").performClick()
        waitUntil(timeoutMillis = 5_000) { !shell.toolsShown }
    }

    @Test
    fun theMacLookupNamesTheVendorFromTheRegistryItIsGiven() = runDesktopComposeUiTest(1000, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val registry = MacRegistry.parse("#2026-09-25\n4C5E0C\tRouterboard.com\tLV\n")
        setContent { NetworkHelmTheme { ToolsDialog(vendors = { registry }) {} } }
        onNodeWithTag("tool-Mac").performClick()
        onNodeWithTag("mac-input").performTextInput("4c:5e:0c:11:22:33")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Routerboard.com")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("LV").assertExists()
        onNodeWithText("MA-L 4C:5E:0C:00:00:00 – 4C:5E:0C:FF:FF:FF").assertExists()
        onNodeWithText("IEEE registry of 2026-09-25").assertExists()
        onNodeWithTag("mac-input").performTextClearance()
        onNodeWithTag("mac-input").performTextInput("02:00:00:00:00:01")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("none: the address is random or locally administered")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theCalculatorTakesARangeAndGivesItsBlocksAndTheNetworkAroundIt() = runDesktopComposeUiTest(1000, 800) {
        Locale.setDefault(Locale.ENGLISH)
        showPane { SubnetPane() }
        onNodeWithTag("subnet-input").performTextClearance()
        onNodeWithTag("subnet-input").performTextInput("192.168.1.10-192.168.5.110")
        waitUntil(timeoutMillis = 5_000) { shown("192.168.0.0/21") }
        onNodeWithText("192.168.1.10").assertExists()
        onNodeWithText("192.168.5.110").assertExists()
        onNodeWithText("1125").assertExists()
        onNodeWithText("255.255.248.0").assertExists()
        onNodeWithText("192.168.7.255").assertExists()
        onNode(hasTestTag("subnet-blocks") and hasText("192.168.2.0/23", substring = true)).assertExists()
        onNode(hasTestTag("subnet-blocks") and hasText("192.168.5.110/32", substring = true)).assertExists()
        onNodeWithTag("subnet-input").performTextClearance()
        onNodeWithTag("subnet-input").performTextInput("192.168.1.10-100")
        waitUntil(timeoutMillis = 5_000) { shown("192.168.1.100") }
        onNodeWithText("91").assertExists()
        onNodeWithText("192.168.1.0/25").assertExists()
        onNodeWithText("192.168.1.127").assertExists()
        onNodeWithTag("subnet-input").performTextClearance()
        onNodeWithTag("subnet-input").performTextInput("192.168.1.10-5")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("subnet-blocks")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theSplitterCutsANetworkByCountAndByPrefix() = runDesktopComposeUiTest(1000, 800) {
        Locale.setDefault(Locale.ENGLISH)
        showPane { SplitPane() }
        waitUntil(timeoutMillis = 5_000) { shown("192.168.0.64/26") }
        onNodeWithText("192.168.0.192/26").assertExists()
        onNodeWithText("4 × /26, hosts in each: 62").assertExists()
        onNodeWithTag("split-number").performTextClearance()
        onNodeWithTag("split-number").performTextInput("5")
        waitUntil(timeoutMillis = 5_000) { shown("192.168.0.224/27") }
        onNodeWithText("8 × /27, hosts in each: 30").assertExists()
        onNodeWithTag("split-number").performTextClearance()
        onNodeWithTag("split-number").performTextInput("300")
        waitUntil(timeoutMillis = 5_000) { shown("The network has fewer addresses than parts.") }
        onNodeWithText("By prefix").performClick()
        onNodeWithTag("split-number").performTextClearance()
        onNodeWithTag("split-number").performTextInput("25")
        waitUntil(timeoutMillis = 5_000) { shown("192.168.0.128/25") }
        onNodeWithText("2 × /25, hosts in each: 126").assertExists()
        onNodeWithTag("split-number").performTextClearance()
        onNodeWithTag("split-number").performTextInput("23")
        waitUntil(timeoutMillis = 5_000) { shown("The prefix must be from 24 to 32.") }
    }

    @Test
    fun theSplitterAllocatesBlocksByHostsAndTakesARangeForTheNetwork() = runDesktopComposeUiTest(1000, 800) {
        Locale.setDefault(Locale.ENGLISH)
        showPane { SplitPane() }
        onNodeWithText("VLSM").performClick()
        waitUntil(timeoutMillis = 5_000) { shown("192.168.0.224/30") }
        onNodeWithText("192.168.0.0/25").assertExists()
        onNodeWithText("192.168.0.128/26").assertExists()
        onNodeWithText("192.168.0.192/27").assertExists()
        onNodeWithText("228 / 256").assertExists()
        onNodeWithTag("split-network").performTextClearance()
        onNodeWithTag("split-network").performTextInput("10.0.0.10-10.0.0.127")
        waitUntil(timeoutMillis = 5_000) { shown("The network is too small for these hosts.") }
        onNodeWithTag("split-hosts").performTextClearance()
        onNodeWithTag("split-hosts").performTextInput("50")
        waitUntil(timeoutMillis = 5_000) { shown("10.0.0.64/26") }
        onNodeWithText("64 / 118").assertExists()
    }

    @Test
    fun theSplitterSummarisesAListAndNamesTheEntryItCannotRead() = runDesktopComposeUiTest(1000, 800) {
        Locale.setDefault(Locale.ENGLISH)
        showPane { SplitPane() }
        onNodeWithText("Summarize").performClick()
        waitUntil(timeoutMillis = 5_000) { shown("10.0.0.0/22") }
        onNodeWithText("Entries: 3, blocks: 1").assertExists()
        onNodeWithTag("split-list").performTextClearance()
        onNodeWithTag("split-list").performTextInput("10.0.0.0/24, 10.0.1.5-10.0.1.255, nonsense")
        waitUntil(timeoutMillis = 5_000) { shown("nonsense is not a network or a range.") }
        onNodeWithTag("split-list").performTextClearance()
        onNodeWithTag("split-list").performTextInput("10.0.0.0/24, 10.0.1.5-10.0.1.255")
        waitUntil(timeoutMillis = 5_000) { shown("Entries: 2, blocks: 8") }
        onNodeWithText("10.0.0.0/24").assertExists()
        onNodeWithText("10.0.1.5/32").assertExists()
    }

    @Test
    fun thePortReferenceFindsAPortByServiceAndByWordsOfItsDescription() = runDesktopComposeUiTest(1000, 800) {
        Locale.setDefault(Locale.ENGLISH)
        showPane { PortsReferencePane() }
        onNodeWithTag("ports-ref-filter").performTextInput("winbox")
        waitUntil(timeoutMillis = 5_000) { shown("8291") }
        onNodeWithText("MikroTik management").assertExists()
        assertTrue(onAllNodes(hasText("ssh")).fetchSemanticsNodes().isEmpty())
        onNodeWithTag("ports-ref-filter").performTextClearance()
        onNodeWithTag("ports-ref-filter").performTextInput("mail transfer")
        waitUntil(timeoutMillis = 5_000) { shown("Mail transfer between servers") }
        onNodeWithText("smtp").assertExists()
        onNodeWithTag("ports-ref-filter").performTextClearance()
        onNodeWithTag("ports-ref-filter").performTextInput("zzzz")
        waitUntil(timeoutMillis = 5_000) { shown("Nothing found") }
    }

    @Test
    fun theHexPaneWritesAnAddressForAnOptionAndReadsTheOptionBack() = runDesktopComposeUiTest(1000, 800) {
        Locale.setDefault(Locale.ENGLISH)
        showPane { HexPane() }
        waitUntil(timeoutMillis = 5_000) { shown("0x0A6700FA") }
        onNodeWithTag("hex-code").performTextInput("1")
        waitUntil(timeoutMillis = 5_000) { shown("0x01040A6700FA") }
        onNodeWithText("With sub-option 1").assertExists()
        onNodeWithTag("hex-input").performTextClearance()
        onNodeWithTag("hex-input").performTextInput("0x01040A6700FA")
        waitUntil(timeoutMillis = 5_000) { shown("1: 10.103.0.250") }
        assertTrue(onAllNodes(hasText("IP addresses")).fetchSemanticsNodes().isEmpty())
        onNodeWithTag("hex-input").performTextClearance()
        onNodeWithTag("hex-input").performTextInput("0A6700FA 0A000001")
        waitUntil(timeoutMillis = 5_000) { shown("10.103.0.250, 10.0.0.1") }
    }

    @Test
    fun everyPortOfTheReferenceHasItsDescription() {
        assertEquals(WellKnownPorts.all.map { it.port }, PortDescriptions.keys.toList())
    }

    @Test
    fun theTransferPaneGivesTheThirdOfSizeSpeedAndTime() = runDesktopComposeUiTest(1000, 800) {
        Locale.setDefault(Locale.ENGLISH)
        showPane { TransferPane() }
        waitUntil(timeoutMillis = 5_000) { shown("transfer-time-result", "1 min 20 s") }
        onNodeWithText("13 min 20 s").assertExists()
        onNodeWithText("8 s").assertExists()
        onNodeWithTag("transfer-speed").performTextClearance()
        onNodeWithTag("transfer-speed").performTextInput("1000")
        waitUntil(timeoutMillis = 5_000) { shown("transfer-time-result", "8 s") }
        onNode(hasText("Speed") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 5_000) { shown("transfer-speed-result", "100") }
        onNode(hasText("Size") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 5_000) { shown("transfer-size-result", "10") }
        onNodeWithTag("transfer-overhead").performTextClearance()
        onNodeWithTag("transfer-overhead").performTextInput("20")
        waitUntil(timeoutMillis = 5_000) { shown("transfer-size-result", "8") }
    }
}
