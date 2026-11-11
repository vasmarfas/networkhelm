package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.App
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.Shell
import com.vasmarfas.networkhelm.ShellTheme
import com.vasmarfas.networkhelm.TitleBarContent
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.appVersion
import com.vasmarfas.networkhelm.journal.EditJournal
import com.vasmarfas.networkhelm.journal.JournalingSession
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.packs.Menu
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.NativeMenu
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.ui.kit.HintHost
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.update.Release
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ToolbarTest {

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private class FakeSafeMode : SafeMode {
        val on = MutableStateFlow(false)
        val calls = mutableListOf<String>()
        override val active = on
        override val actions = flowOf(3)

        override suspend fun enter(): Boolean {
            calls += "enter"
            on.value = true
            return true
        }

        override suspend fun commit() {
            calls += "commit"
            on.value = false
        }

        override suspend fun rollback() {
            calls += "rollback"
            on.value = false
        }

        val left = MutableStateFlow(false)
        override val stranded = left

        override suspend fun settle(keep: Boolean) {
            calls += if (keep) "release" else "unroll"
            left.value = false
        }
    }

    @Test
    fun changesOfALostSafeModeWaitToBeRolledBackOrKept() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val safeMode = FakeSafeMode().apply { left.value = true }
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(safeMode = safeMode), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("The connection was lost while Safe Mode held changes", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        picture("safe-stranded")
        onNodeWithText("Roll back changes").performClick()
        waitUntil(timeoutMillis = 5_000) { safeMode.calls == listOf("unroll") }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Roll back changes")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun onAPhoneTheWordsOfALostSafeModeStandAboveItsButtonsAtTheirFullWidth() = runDesktopComposeUiTest(375, 812) {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        val safeMode = FakeSafeMode().apply { left.value = true }
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(safeMode = safeMode), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { CompositionLocalProvider(LocalCompact provides true) { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Соединение пропало", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val words = onNode(hasText("Соединение пропало", substring = true)).fetchSemanticsNode().boundsInRoot
        val button = onNodeWithText("Откатить изменения").fetchSemanticsNode().boundsInRoot
        assertTrue(words.width > 300f, "the words take the width of the window: ${words.width}")
        assertTrue(button.top >= words.bottom, "the buttons stand under the words")
        picture("safe-stranded-phone")
        Locale.setDefault(Locale.ENGLISH)
    }

    private fun updateRouter(installed: String, latest: String) = FakeRouter(
        rows = mapOf("system/package/update" to listOf(Item("", mapOf("installed-version" to installed, "latest-version" to latest, "status" to "New version is available")))),
    )

    @Test
    fun aNewerRouterOsOnTheRouterShowsAButtonThatOpensTheUpdateWindow() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(updateRouter("7.26", "7.27beta2"), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("RouterOS 7.27beta2")).fetchSemanticsNodes().isNotEmpty() }
        picture("router-update")
        onNodeWithTag("router-update").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("router-update-card")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(tab.workspace.windows.isEmpty(), "the chip opens the card and no window")
        onNodeWithText("RouterOS version 7.27beta2 available").assertExists()
        onNodeWithText("Installed version: 7.26").assertExists()
        picture("router-update-card")
        onNodeWithTag("router-update-open").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.workspace.windows.size == 1 }
        assertEquals("Check For Updates", tab.specs.getValue(tab.workspace.windows.single().id).title)
        assertTrue(onAllNodes(hasTestTag("router-update-card")).fetchSemanticsNodes().isEmpty(), "the card goes away with the click")
    }

    @Test
    fun theButtonWithTheListInTheCardOpensTheSameWindow() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(updateRouter("7.26", "7.27beta2"), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        waitUntil(timeoutMillis = 5_000) { tab.routerUpdate != null }
        onNodeWithTag("router-update").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("router-update-changes")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("router-update-changes").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.workspace.windows.size == 1 }
        assertEquals("Check For Updates", tab.specs.getValue(tab.workspace.windows.single().id).title)
    }

    @Test
    fun theUpdateButtonsGiveWayToTheRightEndOfTheToolbar() = runDesktopComposeUiTest(1100, 500) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(updateRouter("7.26", "7.27beta2"), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        val release = Release("99.0.0", "https://github.com/example/app/releases/tag/v99.0.0")
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}, release = release) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("RouterOS 7.27beta2")).fetchSemanticsNodes().isNotEmpty() }
        picture("both-updates")
        val both = onNodeWithTag("app-update").fetchSemanticsNode().boundsInRoot
        assertTrue(both.right < onNodeWithTag("disconnect").fetchSemanticsNode().boundsInRoot.left, "both buttons show on a window of 1100")
        assertTrue(both.width > 80f, "the second button keeps its width: $both")
    }

    @Test
    fun aNarrowWindowKeepsTheRightEndOfTheToolbarAndDropsTheUpdateButtonsFirst() = runDesktopComposeUiTest(820, 500) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(updateRouter("7.26", "7.27beta2"), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        val release = Release("99.0.0", "https://github.com/example/app/releases/tag/v99.0.0")
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}, release = release) } }
        waitUntil(timeoutMillis = 5_000) { tab.routerUpdate != null }
        waitForIdle()
        picture("both-updates-narrow")
        val disconnect = onNodeWithTag("disconnect").fetchSemanticsNode().boundsInRoot
        assertTrue(disconnect.right <= 820f && disconnect.width > 20f, "Disconnect stays inside the window: $disconnect")
    }

    @Test
    fun onAPhoneTheUpdateButtonsAreIconsThatStayInReach() = runDesktopComposeUiTest(400, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(updateRouter("7.26", "7.27beta2"), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        val release = Release("99.0.0", "https://github.com/example/app/releases/tag/v99.0.0")
        setContent { NetworkHelmTheme(dark = true) { CompositionLocalProvider(LocalCompact provides true) { MainScreen(tab, onDisconnect = {}, release = release) } } }
        waitUntil(timeoutMillis = 5_000) { tab.routerUpdate != null }
        waitForIdle()
        picture("both-updates-phone")
        val router = onNodeWithTag("router-update").fetchSemanticsNode().boundsInRoot
        val app = onNodeWithTag("app-update").fetchSemanticsNode().boundsInRoot
        val disconnect = onNodeWithTag("disconnect").fetchSemanticsNode().boundsInRoot
        assertTrue(router.right <= app.left && app.right <= disconnect.left && disconnect.right <= 400f, "icons $router $app and Disconnect $disconnect fit a width of 400")
    }

    @Test
    fun aRouterThatIsUpToDateShowsNoUpdateButton() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(updateRouter("7.26", "7.26"), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        waitUntil(timeoutMillis = 5_000) { tab.menu != null }
        waitForIdle()
        assertTrue(onAllNodes(hasTestTag("router-update")).fetchSemanticsNodes().isEmpty())
        assertEquals(null, tab.routerUpdate)
    }

    @Test
    fun aNewerReleaseOfTheAppShowsAButtonThatOpensItsPage() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        val opened = mutableListOf<String>()
        val browser = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        val release = Release("99.0.0", "https://github.com/example/app/releases/tag/v99.0.0")
        setContent { CompositionLocalProvider(LocalUriHandler provides browser) { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}, release = release) } } }
        onNodeWithText("NetworkHelm 99.0.0").assertExists()
        picture("app-update")
        onNodeWithTag("app-update").performClick()
        assertEquals(listOf("https://github.com/example/app/releases/tag/v99.0.0"), opened)
    }

    @Test
    fun disconnectingInSafeModeAsksToKeepTheChanges() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val safeMode = FakeSafeMode()
        var disconnected = false
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(safeMode = safeMode), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = { disconnected = true }) } }
        onNodeWithTag("safe-mode").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Safe Mode 3")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Disconnect").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Keep changes")).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(disconnected, "nothing closes before the answer")
        onNodeWithText("Keep changes").performClick()
        waitUntil(timeoutMillis = 5_000) { disconnected }
        assertEquals(listOf("enter", "commit"), safeMode.calls)
    }

    @Test
    fun anAppThatIsBeingClosedAsksAboutSafeModeAsDisconnectDoes() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val safeMode = FakeSafeMode().also { it.on.value = true }
        var leaves by mutableStateOf(false)
        var disconnected = false
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(safeMode = safeMode), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = { disconnected = true }, leaves = leaves, onStay = { leaves = false }) } }
        leaves = true
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Keep changes")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Cancel").performClick()
        waitUntil(timeoutMillis = 5_000) { !leaves }
        waitForIdle()
        assertFalse(disconnected)
        leaves = true
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Roll back changes")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Roll back changes").performClick()
        waitUntil(timeoutMillis = 5_000) { disconnected }
        assertEquals(listOf("rollback"), safeMode.calls)
    }

    @Test
    fun onAPhoneTheMarkOfAnUnencryptedConnectionLeavesDisconnectOnTheBar() = runDesktopComposeUiTest(360, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(encrypted = false, safeMode = FakeSafeMode()), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { CompositionLocalProvider(LocalCompact provides true) { MainScreen(tab, onDisconnect = {}) } } }
        waitForIdle()
        picture("unencrypted-phone")
        val mark = onNodeWithTag("unencrypted").fetchSemanticsNode().boundsInRoot
        val more = onNodeWithTag("more").fetchSemanticsNode().boundsInRoot
        val disconnect = onNodeWithTag("disconnect").fetchSemanticsNode().boundsInRoot
        assertTrue(mark.right <= more.left && more.right <= disconnect.left && disconnect.right <= 360f, "the mark $mark, More $more and Disconnect $disconnect fit a width of 360")
        assertTrue(disconnect.width > 20f, "Disconnect keeps its width: $disconnect")
    }

    @Test
    fun anUnencryptedConnectionIsMarkedAndPreparesTheRouter() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val services = listOf(
            Item("*5", mapOf("name" to "api-ssl", "disabled" to "true")),
            Item("*1", mapOf("name" to "api")),
            Item("*22", mapOf("name" to "api-ssl", "dynamic" to "true", "connection" to "true", "remote" to "192.0.2.9:50000")),
            Item("*23", mapOf("name" to "api", "dynamic" to "true", "connection" to "true", "remote" to "192.0.2.10:50001")),
        )
        val router = FakeRouter(mapOf("ip/service" to services), encrypted = false)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        var secured = false
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}, onSecure = { secured = true }) } }
        onNodeWithTag("unencrypted").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("/certificate add name=networkhelm common-name=rd-test days-valid=3650", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        picture("prepare")
        onNodeWithText("Apply").performClick()
        waitUntil(timeoutMillis = 5_000) { router.added.size == 2 }
        assertEquals(listOf("name" to "networkhelm", "common-name" to "rd-test", "days-valid" to "3650").toMap(), router.added[0])
        assertEquals("networkhelm", router.added[1]["name"])
        assertEquals("certificate/sign" to mapOf("number" to "networkhelm"), router.ran.single())
        assertEquals(listOf<Pair<String?, Map<String, String?>>>("*5" to mapOf("certificate" to "networkhelm", "disabled" to "no")), router.changed.toList())
        onNodeWithTag("prepare-reconnect").performClick()
        waitUntil(timeoutMillis = 5_000) { secured }
    }

    @Test
    fun theListOfWindowsShowsWhereEachStandsAndClosesThemAll() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        fun directory(path: String, vararg children: String) = path to MenuSchema(MenuPath.parse(path), NodeKind.Directory, children.toList(), emptyList(), emptyList())
        val router = FakeRouter(schemas = mapOf(directory("ip", "firewall", "address"), directory("ip/firewall", "filter", "nat")))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        waitUntil(timeoutMillis = 5_000) { tab.menu?.isNotEmpty() == true }
        tab.open(MenuEntry("Firewall", "ip/firewall/nat"))
        tab.open(MenuEntry("Addresses", "ip/address"))
        onNodeWithText("Windows 2").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("IP › Firewall › NAT")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("IP › Addresses")).fetchSemanticsNodes().isNotEmpty())
        picture("window-list")
        onNodeWithText("Close all").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.workspace.windows.isEmpty() }
    }

    @Test
    fun theButtonBesideTheListClosesEveryWindow() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        fun directory(path: String, vararg children: String) = path to MenuSchema(MenuPath.parse(path), NodeKind.Directory, children.toList(), emptyList(), emptyList())
        val router = FakeRouter(schemas = mapOf(directory("ip", "firewall", "address"), directory("ip/firewall", "filter", "nat")))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        waitUntil(timeoutMillis = 5_000) { tab.menu?.isNotEmpty() == true }
        onNodeWithTag("close-all").assertIsNotEnabled()
        val undo = onNodeWithTag("undo").fetchSemanticsNode().boundsInRoot
        tab.open(MenuEntry("Firewall", "ip/firewall/nat"))
        tab.open(MenuEntry("Addresses", "ip/address"))
        assertEquals(undo, onNodeWithTag("undo").fetchSemanticsNode().boundsInRoot, "Undo stands where it stood without windows")
        onNodeWithTag("close-all").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.workspace.windows.isEmpty() }
        onNodeWithTag("close-all").assertIsNotEnabled()
        assertEquals(undo, onNodeWithTag("undo").fetchSemanticsNode().boundsInRoot, "a second tap on the place of the button does not land on Undo")
    }

    @Test
    fun theTabsOfTheRoutersStandInTheTitleBar() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val shell = Shell(Services(MemoryStorage(), MemoryTrustStore(), neighbors = null), TestScope())
        setContent {
            Column {
                ShellTheme(shell) { Box(Modifier.fillMaxWidth().height(36.dp).background(Theme.palette.raised)) { TitleBarContent(shell, Modifier, Modifier) } }
                Box(Modifier.weight(1f)) { App(shell, tabsInTitle = true) }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("NetworkHelm")).fetchSemanticsNodes().isNotEmpty() }
        shell.add(FakeRouter(), "192.0.2.1")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Connect to another router")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Disconnect")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, onAllNodes(hasText("Connect to another router")).fetchSemanticsNodes().size)
        picture("titlebar-tabs")
    }

    @Test
    fun theAboutWindowShowsTheVersionAndTheLicences() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val shell = Shell(Services(MemoryStorage(), MemoryTrustStore(), neighbors = null), TestScope())
        shell.add(FakeRouter(), "192.0.2.1")
        setContent { App(shell) }
        onNodeWithTag("about").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("NetworkHelm $appVersion")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("PolyForm Noncommercial License 1.0.0", substring = true)).fetchSemanticsNodes().size >= 2, "the summary and the licence")
        onNodeWithText("THIRD-PARTY-NOTICES.md").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("KSafe", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        picture("about")
        onNodeWithText("LGPL-3.0.txt").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("GNU LESSER GENERAL PUBLIC LICENSE", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("GPL-3.0.txt")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun reloadingTheMenusMakesTheRouterForgetWhatItToldOfThem() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter()
        var forgotten = 0
        val session = object : DeviceSession by router {
            override suspend fun forgetStructure() {
                forgotten++
            }
        }
        val shell = Shell(Services(MemoryStorage(), MemoryTrustStore(), neighbors = null), TestScope())
        shell.add(session, "192.0.2.1")
        setContent { App(shell) }
        onNodeWithTag("reload-structure").performClick()
        waitUntil(timeoutMillis = 5_000) { forgotten == 1 }
    }

    @Test
    fun undoAndRedoRunOnTheRouterAndTheJournalShowsEditsAsCommands() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val list = MenuPath.parse("ip/firewall/address-list")
        val router = FakeRouter(mapOf(list.toString() to listOf(Item("*1", mapOf("list" to "rd-test", "address" to "192.0.2.1")))))
        val journal = EditJournal()
        val storage = MemoryStorage()
        val tab = RouterTab(JournalingSession(router, journal, "192.0.2.1"), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage), journal)
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        onNodeWithTag("undo").performClick()
        onNodeWithTag("redo").performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.size == 2 }
        assertEquals(listOf("undo", "redo"), router.ran.map { it.first })

        runBlocking { tab.session.set(list, "*1", mapOf("disabled" to "yes")) }
        onNodeWithText("Journal").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("/ip firewall address-list set *1 disabled=yes")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun undoAndRedoNameTheActionsTheyWouldTake() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val history = listOf(
            Item("*5", mapOf("action" to "address list entry removed", "by" to "rd-test", "redoable" to "true")),
            Item("*4", mapOf("action" to "address list entry changed", "by" to "rd-test", "redoable" to "true")),
            Item("*3", mapOf("action" to "filter rule added", "by" to "admin", "undoable" to "true")),
            Item("*2", mapOf("action" to "filter rule changed", "by" to "admin", "undoable" to "true")),
        )
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(mapOf("system/history" to history)), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { HintHost { MainScreen(tab, onDisconnect = {}) } } }
        onNodeWithTag("undo").performMouseInput { moveTo(center) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Undo: filter rule added (admin)")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("redo").performMouseInput { moveTo(center) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Redo: address list entry changed (rd-test)")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun safeModeSaysWhatALostConnectionDoes() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(safeMode = FakeSafeMode()), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { HintHost { MainScreen(tab, onDisconnect = {}) } } }
        onNodeWithTag("safe-mode").performMouseInput { moveTo(center) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("rolled back if the connection to the router is lost", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun SkikoComposeUiTest.behind(text: String): Int {
        val bounds = onNodeWithText(text).fetchSemanticsNode().boundsInWindow
        return captureToImage().toAwtImage().getRGB(bounds.left.toInt() + 4, bounds.center.y.toInt())
    }

    @Test
    fun theViewOfOpenWrtTurnsToLuciAndIsKept() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        fun directory(path: String, vararg children: String) = path to MenuSchema(MenuPath.parse(path), NodeKind.Directory, children.toList(), emptyList(), emptyList())
        val luci = Menu(platform = "openwrt", items = listOf(MenuEntry("Network", items = listOf(MenuEntry("Interfaces", "network/interface"))), MenuEntry("Log", "status/log")))
        val schemas = mapOf(directory("", "network", "status"), directory("network", "interface"), directory("status", "log"))
        val router = FakeRouter(schemas = schemas, platform = Platform.OpenWrt, native = NativeMenu(luci, emptyList()))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        waitUntil(timeoutMillis = 5_000) { tab.menu != null }
        assertEquals(MenuView.WinBox, tab.view)
        onNodeWithTag("view").performClick()
        listOf("The configs of UCI the router keeps", "The menu and the forms of LuCI", "The menus and windows of RouterOS worked out over UCI").forEach { hint ->
            onNodeWithText(hint, substring = true).assertExists()
        }
        onNodeWithText("LuCI view").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.menu?.map { it.title } == listOf("Network", "Log") }
        assertEquals(MenuView.Native, tab.view)

        val again = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        runBlocking { again.loadMenu() }
        assertEquals(MenuView.Native, again.view)
    }

    @Test
    fun openWrtWithoutLuciOffersNoViewOfIt() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val root = MenuSchema(MenuPath.Root, NodeKind.Directory, emptyList(), emptyList(), emptyList())
        val storage = MemoryStorage()
        val tab = RouterTab(FakeRouter(schemas = mapOf("" to root), platform = Platform.OpenWrt), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        waitUntil(timeoutMillis = 5_000) { tab.menu != null }
        onNodeWithTag("view").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Unified view")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("LuCI view")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aPageOfLuciWithNothingToReadOpensInTheBrowser() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val luci = Menu(platform = "openwrt", items = listOf(MenuEntry("Services", items = listOf(MenuEntry("Mihomo", page = "admin/services/mihomo")))))
        val root = MenuSchema(MenuPath.Root, NodeKind.Directory, emptyList(), emptyList(), emptyList())
        val router = FakeRouter(schemas = mapOf("" to root), platform = Platform.OpenWrt, native = NativeMenu(luci, emptyList()))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        val opened = mutableListOf<String>()
        val browser = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        setContent { CompositionLocalProvider(LocalUriHandler provides browser) { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } } }
        waitUntil(timeoutMillis = 5_000) { tab.menu != null }
        onNodeWithTag("view").performClick()
        onNodeWithText("LuCI view").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.menu?.map { it.title } == listOf("Services") }
        onNodeWithText("Services").performClick()
        onNodeWithText("Mihomo").performClick()
        assertEquals(listOf("http://192.0.2.1/cgi-bin/luci/admin/services/mihomo"), opened)
        assertEquals("http://[2001:db8::1]/cgi-bin/luci/admin/x", RouterTab(router, "[2001:db8::1]:22", bundledPacks().values, storage, TableLayouts(storage)).webPage("admin/x"))
    }

    @Test
    fun theOpenGroupStaysMarkedAndTheEntryUnderThePointerLightsUp() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        fun directory(path: String, vararg children: String) = path to MenuSchema(MenuPath.parse(path), NodeKind.Directory, children.toList(), emptyList(), emptyList())
        val router = FakeRouter(schemas = mapOf(directory("", "interface", "ip"), directory("ip", "address", "arp")))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("IP")).fetchSemanticsNodes().isNotEmpty() }
        val plain = behind("IP")
        onNodeWithText("IP").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Addresses")).fetchSemanticsNodes().isNotEmpty() }
        assertNotEquals(plain, behind("IP"), "the group of the open list is marked")
        assertEquals(plain, behind("Interfaces"))
        val still = behind("Addresses")
        onNodeWithText("Addresses").performMouseInput { moveTo(center) }
        waitForIdle()
        assertNotEquals(still, behind("Addresses"), "the entry under the pointer is tinted")
        assertEquals(still, behind("ARP"))
        picture("menu-hover")
    }
}
