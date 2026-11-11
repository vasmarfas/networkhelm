package com.vasmarfas.networkhelm.ui.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.App
import com.vasmarfas.networkhelm.FakeClipboard
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.SavedRouters
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.ui.connect.ConnectScreen
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.readText
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import com.vasmarfas.networkhelm.ui.theme.TouchMetrics
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PhoneTest {

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    @Test
    fun aPhoneShowsTheMenuThenOneWindowAtATime() = runDesktopComposeUiTest(400, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val addresses = listOf(
            Item("*1", mapOf("address" to "192.0.2.1/24", "network" to "192.0.2.0", "interface" to "ether1")),
            Item("*2", mapOf("address" to "198.51.100.1/24", "network" to "198.51.100.0", "interface" to "bridge")),
        )
        val tab = RouterTab(FakeRouter(rows = mapOf("ip/address" to addresses)), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme { CompositionLocalProvider(LocalCompact provides true) { MainScreen(tab, onDisconnect = {}) } } }
        waitUntil(timeoutMillis = 10_000) { tab.menu != null }
        tab.menu = listOf(
            MenuEntry("Interfaces", "interface"),
            MenuEntry("IP", items = listOf(MenuEntry("Addresses", "ip/address"), MenuEntry("Routes", "ip/route"))),
            MenuEntry("Log", "log"),
        )
        waitForIdle()
        onNodeWithText("IP").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Addresses")).fetchSemanticsNodes().isNotEmpty() }
        picture("phone-menu")
        onNodeWithText("Addresses").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("192.0.2.1/24", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        picture("phone-window")
        tab.openItem("Address", MenuPath.parse("ip/address"), "*1")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Interface")).fetchSemanticsNodes().isNotEmpty() }
        picture("phone-item")
        onNodeWithTag("back").performClick()
        waitForIdle()
        assertEquals(listOf("Addresses"), tab.workspace.windows.map { it.title })
        onNodeWithTag("back").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("IP")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(tab.workspace.windows.isEmpty())
    }

    @Test
    fun whatWasTypedIntoAFormIsStillThereWhenTheWindowOverItIsClosed() = runDesktopComposeUiTest(400, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val path = MenuPath.parse("ip/firewall/filter")
        val schema = MenuSchema(path, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"), listOf(Argument("chain", ValueType.Text()), Argument("comment", ValueType.Text())))
        val router = FakeRouter(mapOf(path.toString() to listOf(Item("*1", mapOf("chain" to "input")))), schemas = mapOf(path.toString() to schema))
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme { CompositionLocalProvider(LocalCompact provides true) { MainScreen(tab, onDisconnect = {}) } } }
        waitUntil(timeoutMillis = 10_000) { tab.menu != null }
        tab.openItem("Firewall Rule", path, "*1")
        val chain = hasSetTextAction() and hasAnyAncestor(hasTestTag("field-chain"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(chain).fetchSemanticsNodes().isNotEmpty() }
        onNode(chain).performTextReplacement("forward")
        tab.open(MenuEntry("Log", "log"))
        waitUntil(timeoutMillis = 5_000) { tab.workspace.windows.size == 2 && onAllNodes(chain).fetchSemanticsNodes().isEmpty() }
        onNodeWithTag("back").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("forward")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aPhoneOnItsSideScrollsAWindowUnderTheBars() = runDesktopComposeUiTest(880, 320) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val addresses = listOf(Item("*1", mapOf("address" to "192.0.2.1/24", "network" to "192.0.2.0", "interface" to "ether1")))
        val tab = RouterTab(FakeRouter(rows = mapOf("ip/address" to addresses)), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme { CompositionLocalProvider(LocalCompact provides true) { TouchMetrics(true) { MainScreen(tab, onDisconnect = {}) } } } }
        waitUntil(timeoutMillis = 10_000) { tab.menu != null }
        tab.open(MenuEntry("Addresses", "ip/address"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("192.0.2.1/24", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onNode(hasText("192.0.2.1/24", substring = true)).performScrollTo().assertIsDisplayed()
        picture("phone-side-window")
    }

    @Test
    fun theToolbarOfAPhoneKeepsEveryActionInReach() = runDesktopComposeUiTest(360, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        var searched = false
        setContent { NetworkHelmTheme { CompositionLocalProvider(LocalCompact provides true) { MainScreen(tab, onDisconnect = {}, onSearch = { searched = true }) } } }
        waitUntil(timeoutMillis = 10_000) { tab.menu != null }
        for (tag in listOf("windows", "more", "disconnect")) {
            val bounds = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width > 0f && bounds.right <= 360f, "$tag stays on the screen: $bounds")
        }
        onNodeWithTag("more").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("About")).fetchSemanticsNodes().isNotEmpty() }
        picture("phone-toolbar-more")
        assertTrue(onAllNodes(hasText("Settings")).fetchSemanticsNodes().isNotEmpty())
        onNodeWithText("Search the routers").performClick()
        waitForIdle()
        assertTrue(searched)
    }

    @Test
    fun aPhoneMovesTheChosenRuleWithArrows() = runDesktopComposeUiTest(360, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val rules = listOf(
            Item("*1", mapOf("chain" to "forward", "action" to "accept")),
            Item("*2", mapOf("chain" to "input", "action" to "drop")),
            Item("*3", mapOf("chain" to "forward", "action" to "reject")),
        )
        val router = FakeRouter(mapOf("ip/firewall/filter" to rules))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme { CompositionLocalProvider(LocalCompact provides true) { MainScreen(tab, onDisconnect = {}) } } }
        tab.open(MenuEntry("Firewall", "ip/firewall/filter"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("drop", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Move up").assertIsNotEnabled()
        onAllNodes(hasText("drop", substring = true))[0].performTouchInput { longClick() }
        waitForIdle()
        picture("phone-move")
        onNodeWithContentDescription("Move up").performClick()
        waitUntil(timeoutMillis = 5_000) { router.moved.isNotEmpty() }
        assertEquals<Pair<List<String>, String?>>(listOf("*2") to "*1", router.moved.last(), "the rule goes before the one above it")
        onNodeWithContentDescription("Move down").performClick()
        waitUntil(timeoutMillis = 5_000) { router.moved.size == 2 }
        assertEquals<Pair<List<String>, String?>>(listOf("*2") to null, router.moved.last())
    }

    @Test
    fun aPhoneCopiesAndPastesARuleWithButtons() = runDesktopComposeUiTest(360, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val path = MenuPath.parse("ip/firewall/filter")
        val schema = MenuSchema(
            path, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"),
            listOf(Argument("chain", ValueType.Text()), Argument("action", ValueType.Enumeration()), Argument("disabled", ValueType.YesNo)),
        )
        val router = FakeRouter(mapOf(path.toString() to listOf(Item("*1", mapOf("chain" to "input", "action" to "drop")))), schemas = mapOf(path.toString() to schema))
        val clip = FakeClipboard()
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent {
            NetworkHelmTheme {
                CompositionLocalProvider(LocalCompact provides true, LocalClipboard provides clip) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        tab.open(MenuEntry("Firewall", path.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("drop", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Copy").assertIsNotEnabled()
        onAllNodes(hasText("drop", substring = true))[0].performTouchInput { longClick() }
        onNodeWithContentDescription("Copy").performClick()
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertEquals("/ip firewall filter add chain=input action=drop", runBlocking { clip.readText() })
        onNodeWithContentDescription("Paste").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Add 1 item from the clipboard to the router?")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Paste").performClick()
        waitUntil(timeoutMillis = 5_000) { router.added.isNotEmpty() }
        assertEquals(mapOf("chain" to "input", "action" to "drop"), router.added.single())
    }

    @Test
    fun theConnectionFormFitsAPhone() = runDesktopComposeUiTest(380, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val services = Services(MemoryStorage(), MemoryTrustStore(), neighbors = null)
        setContent {
            NetworkHelmTheme {
                CompositionLocalProvider(LocalCompact provides true) {
                    ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> }
                }
            }
        }
        onNodeWithTag("address").performTextInput("192.0.2.1")
        picture("phone-connect")
        val field = onNodeWithTag("address").fetchSemanticsNode().boundsInRoot
        assertTrue(field.width > 300f, "the address takes the width of the screen: ${field.width}")
    }

    @Test
    fun aPhoneOnItsSideKeepsThePhoneLayout() = runDesktopComposeUiTest(880, 360) {
        Locale.setDefault(Locale.ENGLISH)
        setContent { App(Services(MemoryStorage(), MemoryTrustStore(), neighbors = null)) }
        val field = onNodeWithTag("address").fetchSemanticsNode().boundsInRoot
        assertTrue(field.width > 800f, "the address takes the width of the screen: ${field.width}")
        onNodeWithTag("about").performScrollTo().assertIsDisplayed()
        picture("phone-landscape")
    }
}
