package com.vasmarfas.networkhelm.ui.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.BarItem
import com.vasmarfas.networkhelm.app.LocalSettings
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.Settings
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.LINK_LOST
import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ResourceBarTest {

    private fun SkikoComposeUiTest.bar(
        resource: Map<String, String>,
        compact: Boolean = false,
        settings: Settings = Settings(),
        router: DeviceSession = FakeRouter(mapOf("system/resource" to listOf(Item("", resource)))),
    ) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent {
            NetworkHelmTheme(dark = true) {
                CompositionLocalProvider(LocalCompact provides compact, LocalSettings provides settings) { ResourceBar(tab) }
            }
        }
    }

    @Test
    fun theShareOfMemoryStandsWhereNoAmountIsTold() = runDesktopComposeUiTest(1100, 60) {
        bar(mapOf("cpu-load" to "11", "memory-usage" to "13", "uptime" to "7h21m11s"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Memory 13 %")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("CPU 11 %").assertExists()
        onNodeWithText("Uptime\u00A07h21m11s").assertExists()
    }

    @Test
    fun theLoadAverageStandsBesideTheMemory() = runDesktopComposeUiTest(1100, 60) {
        bar(mapOf("load" to "0.5%", "free-memory" to "1369366528", "total-memory" to "2077253632", "uptime" to "7h49m8s"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Load 0.5%")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Memory 1305.9 MiB / 675.1 MiB / 1981.0 MiB").assertExists()
        onAllNodes(hasText("CPU", substring = true)).assertCountEquals(0)
    }

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    @Test
    fun theWholeBarShowsEveryItem() = runDesktopComposeUiTest(1300, 60) {
        val router = FakeRouter(
            mapOf(
                "system/resource" to listOf(Item("", mapOf("cpu-load" to "11", "free-memory" to "1369366528", "total-memory" to "2077253632", "free-hdd-space" to "36540416", "total-hdd-space" to "88150016", "uptime" to "4d17h58m40s"))),
                "system/clock" to listOf(Item("", mapOf("date" to "2026-10-29", "time" to "09:19:11"))),
                "ip/route" to listOf(Item("*1", mapOf("dst-address" to "0.0.0.0/0", "immediate-gw" to "192.0.2.254%ether1", "active" to "true"))),
            ),
            output = mapOf("interface/monitor-traffic" to listOf(Item("", mapOf("name" to "ether1", "rx-bits-per-second" to "23400000", "tx-bits-per-second" to "1850000")))),
        )
        bar(emptyMap(), router = router)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("ether1 ↓ 23.4 Mbps ↑ 1.9 Mbps")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("2026-10-29 09:19:11")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("CPU 11 %").assertIsDisplayed()
        onNodeWithText("Memory 1305.9 MiB / 675.1 MiB / 1981.0 MiB").assertIsDisplayed()
        onNodeWithText("Disk 34.8 MiB / 84.1 MiB").assertIsDisplayed()
        onNodeWithText("Uptime\u00A04d17h58m40s").assertIsDisplayed()
        picture("resource-bar")
    }

    @Test
    fun theDiskStandsBesideTheMemory() = runDesktopComposeUiTest(1100, 60) {
        bar(mapOf("cpu-load" to "3", "free-memory" to "1369366528", "total-memory" to "2077253632", "free-hdd-space" to "36540416", "total-hdd-space" to "88150016", "uptime" to "1d"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Disk 34.8 MiB / 84.1 MiB")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Memory 1305.9 MiB / 675.1 MiB / 1981.0 MiB").assertExists()
    }

    @Test
    fun theSettingsChooseAndOrderTheItems() = runDesktopComposeUiTest(1100, 60) {
        val resource = mapOf("cpu-load" to "11", "memory-usage" to "13", "uptime" to "7h21m11s")
        bar(resource, settings = Settings(barOrder = listOf(BarItem.Uptime, BarItem.Memory, BarItem.Cpu), barHidden = setOf(BarItem.Memory)))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("CPU 11 %")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("Memory", substring = true)).assertCountEquals(0)
        assertTrue(onNodeWithText("Uptime\u00A07h21m11s").fetchSemanticsNode().boundsInRoot.left < onNodeWithText("CPU 11 %").fetchSemanticsNode().boundsInRoot.left)
    }

    @Test
    fun theTrafficIsThatOfTheInterfaceOfTheDefaultRoute() = runDesktopComposeUiTest(1100, 60) {
        val router = FakeRouter(
            mapOf(
                "system/resource" to listOf(Item("", mapOf("cpu-load" to "3"))),
                "ip/route" to listOf(
                    Item("*1", mapOf("dst-address" to "0.0.0.0/0", "gateway" to "192.0.2.254", "immediate-gw" to "192.0.2.254%ether1", "active" to "true")),
                    Item("*2", mapOf("dst-address" to "0.0.0.0/0", "gateway" to "198.51.100.1", "immediate-gw" to "198.51.100.1%ether2")),
                ),
            ),
            output = mapOf("interface/monitor-traffic" to listOf(Item("", mapOf("name" to "ether1", "rx-bits-per-second" to "1200000", "tx-bits-per-second" to "300000")))),
        )
        bar(emptyMap(), router = router)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("ether1 ↓ 1.2 Mbps ↑ 300 kbps")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(mapOf("interface" to "ether1"), router.ran.single { it.first == "interface/monitor-traffic" }.second)
    }

    @Test
    fun theTrafficIsThatOfTheInterfaceTheSettingsName() = runDesktopComposeUiTest(1100, 60) {
        val router = FakeRouter(
            mapOf("system/resource" to listOf(Item("", mapOf("cpu-load" to "3")))),
            output = mapOf("interface/monitor-traffic" to listOf(Item("", mapOf("name" to "ether5", "rx-bits-per-second" to "800", "tx-bits-per-second" to "0")))),
        )
        bar(emptyMap(), settings = Settings(barInterface = "ether5"), router = router)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("ether5 ↓ 800 bps ↑ 0 bps")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(mapOf("interface" to "ether5"), router.ran.single { it.first == "interface/monitor-traffic" }.second)
    }

    @Test
    fun theTrafficThatEndedWithTheLinkComesBackWithIt() = runDesktopComposeUiTest(1100, 60) {
        val router = FakeRouter(
            mapOf("system/resource" to listOf(Item("", mapOf("cpu-load" to "3")))),
            output = mapOf("interface/monitor-traffic" to listOf(Item("", mapOf("name" to "ether5", "rx-bits-per-second" to "800", "tx-bits-per-second" to "0")))),
        )
        var asked = 0
        val dropping = object : DeviceSession by router {
            override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> =
                if (asked++ == 0) flow { throw DeviceException(ErrorCategory.ConnectionLost, LINK_LOST) } else router.run(path, command, arguments)
        }
        bar(emptyMap(), settings = Settings(barInterface = "ether5"), router = dropping)
        waitUntil(timeoutMillis = 5_000) { asked == 1 }
        waitForIdle()
        router.link.value = LinkState.Reconnecting
        waitForIdle()
        router.link.value = LinkState.Up
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("ether5 ↓ 800 bps ↑ 0 bps")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theLastItemsGiveWayOnANarrowBar() = runDesktopComposeUiTest(330, 60) {
        bar(mapOf("cpu-load" to "11", "free-memory" to "1369366528", "total-memory" to "2077253632", "free-hdd-space" to "36540416", "total-hdd-space" to "88150016", "uptime" to "7h21m11s"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("CPU 11 %")).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        onNodeWithText("CPU 11 %").assertIsDisplayed()
        onNodeWithText("Uptime\u00A07h21m11s").assertIsNotDisplayed()
    }

    private fun SkikoComposeUiTest.firstLine(text: String): String {
        val layout = mutableListOf<TextLayoutResult>()
        onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        return text.substring(0, layout.single().getLineEnd(0, visibleEnd = true))
    }

    @Test
    fun aPhoneKeepsTheLoadTheMemoryAndTheUptimeInOneLine() = runDesktopComposeUiTest(360, 60) {
        bar(mapOf("cpu-load" to "3", "free-memory" to "1369366528", "total-memory" to "2077253632", "uptime" to "2w3d10h20m30s"), compact = true)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Memory 34 %")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("CPU 3 %").assertExists()
        assertEquals("Uptime\u00A02w3d10h20m30s", firstLine("Uptime\u00A02w3d10h20m30s"))
    }

    @Test
    fun theEndOfTheUptimeGivesWayOnANarrowPhone() = runDesktopComposeUiTest(340, 60) {
        bar(mapOf("cpu-load" to "100", "free-memory" to "0", "total-memory" to "2077253632", "uptime" to "52w6d23h59m59s"), compact = true)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Memory 100 %")).fetchSemanticsNodes().isNotEmpty() }
        val shown = firstLine("Uptime\u00A052w6d23h59m59s")
        assertTrue(shown.startsWith("Uptime\u00A052w"), shown)
    }
}
