package com.vasmarfas.networkhelm.ui.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class UpdateWindowTest {
    private val update = MenuPath.parse("system/package/update")

    private val schema = MenuSchema(
        update, NodeKind.Settings, emptyList(), listOf("set", "download", "install", "check-for-updates", "cancel"),
        listOf(
            Argument("channel", ValueType.Enumeration(closed = true), listOf("development", "long-term", "stable", "testing")),
            Argument("mode", ValueType.Enumeration(closed = true), listOf("https", "http")),
            Argument("check-certificate", ValueType.Enumeration(closed = true), listOf("no", "yes", "yes-without-crl")),
            Argument("ip-version", ValueType.Enumeration(closed = true), listOf("any", "ipv4", "ipv6")),
            Argument("installed-version", ValueType.Text(), readOnly = true),
            Argument("latest-version", ValueType.Text(), readOnly = true),
            Argument("status", ValueType.Text(), readOnly = true),
        ),
    )

    private val versions = mapOf("development" to "7.26beta5", "long-term" to "7.24.6", "stable" to "7.25.1", "testing" to "7.25.1")

    // keeps the state of a router the way a real one does: a write starts a check, and the check ends a moment later
    private inner class UpdateRouter(status: String = "New version is available", private val withChangelog: Boolean = true) : DeviceSession by FakeRouter() {
        private val other = FakeRouter()
        val written = mutableListOf<Map<String, String?>>()
        val ran = mutableListOf<String>()
        var refuse: String? = null

        private fun checked(channel: String, status: String) = buildMap {
            put("channel", channel)
            put("mode", "https")
            put("check-certificate", "yes")
            put("ip-version", "any")
            put("installed-version", "7.26beta1")
            put("latest-version", versions.getValue(channel))
            put("status", status)
            if (withChangelog) put("changelog", "What's new in ${versions.getValue(channel)}:\n\n*) first change;\n*) second change;")
        }

        val state = MutableStateFlow(checked("development", status))

        fun finish() {
            state.value = checked(state.value.getValue("channel"), "New version is available")
        }

        override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> =
            if (path == update) state.map { ListEvent.Snapshot(listOf(Item("", it))) } else other.watch(path, filter, properties)

        override suspend fun describe(path: MenuPath): MenuSchema = if (path == update) schema else other.describe(path)

        override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) {
            refuse?.let { throw DeviceException(ErrorCategory.General, it) }
            written += changes
            val now = state.value + changes.mapNotNull { (name, value) -> value?.let { name to it } }
            state.value = (now - "latest-version") + ("status" to "finding out latest version...")
        }

        override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> {
            ran += command
            return other.run(path, command, arguments)
        }
    }

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun SkikoComposeUiTest.opened(router: UpdateRouter, changelog: (suspend (String) -> String?)? = null): RouterTab {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { CompositionLocalProvider(LocalChangelog provides changelog) { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } } }
        runOnIdle { tab.open(MenuEntry("Check For Updates", update.toString())) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Installed Version")).fetchSemanticsNodes().isNotEmpty() }
        return tab
    }

    private fun SkikoComposeUiTest.buttons() = listOf("download", "install", "check-for-updates", "cancel").filter { onAllNodesWithTag("action-$it").fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aChannelChosenInTheWindowGoesToTheRouterAtOnceAndTheWindowFollowsTheCheck() = runDesktopComposeUiTest(1100, 800) {
        val router = UpdateRouter()
        opened(router)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("7.26beta5")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("download", "install"), buttons())
        onNodeWithTag("router-update").assertExists()
        picture("update-window")
        onNodeWithTag("field-channel").performClick()
        onNodeWithText("long-term").performClick()
        waitUntil(timeoutMillis = 5_000) { router.written.isNotEmpty() }
        assertEquals(listOf<Map<String, String?>>(mapOf("channel" to "long-term")), router.written)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Status: finding out latest version...")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("check-for-updates"), buttons())
        onNodeWithText("long-term").assertExists()
        onNodeWithText("Latest Version").assertExists()
        picture("update-window-checking")
        router.finish()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("7.24.6")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("What's new in 7.24.6:", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("download", "install"), buttons())
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("router-update").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, router.written.size)
        onAllNodesWithText("OK").assertCountEquals(0)
        onAllNodesWithText("Apply").assertCountEquals(0)
        onNodeWithText("Close").assertExists()
        picture("update-window-checked")
    }

    @Test
    fun aValueTheRouterRefusesGoesBackToWhatTheRouterHasAndTellsWhy() = runDesktopComposeUiTest(1100, 800) {
        val router = UpdateRouter().apply { refuse = "not permitted" }
        opened(router)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("7.26beta5")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("field-channel").performClick()
        onNodeWithText("stable").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("not permitted", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(router.written.isEmpty())
        onAllNodesWithText("stable").assertCountEquals(0)
        onNodeWithText("development").assertExists()
    }

    @Test
    fun theButtonsAreThoseOfTheStateTheStatusTellsAndAllOfThemWhenItTellsNothingKnown() = runDesktopComposeUiTest(1100, 800) {
        val states = mapOf(
            "System is already up to date" to listOf("check-for-updates"),
            "finding out latest version..." to listOf("check-for-updates"),
            "getting changelog..." to listOf("download", "install"),
            "downloading" to listOf("cancel"),
            "ERROR: could not connect" to listOf("download", "install", "check-for-updates", "cancel"),
        )
        val router = UpdateRouter()
        opened(router)
        for ((status, expected) in states) {
            router.state.value = router.state.value + ("status" to status)
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Status: $status")).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(expected, buttons(), status)
        }
    }

    @Test
    fun aButtonOfTheWindowRunsItsCommandOnTheRouter() = runDesktopComposeUiTest(1100, 800) {
        val router = UpdateRouter()
        opened(router)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("7.26beta5")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("action-download").performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.isNotEmpty() }
        assertEquals(listOf("download"), router.ran)
    }

    @Test
    fun theButtonThatRestartsTheRouterAsksFirstAndRunsOnlyAfterTheAnswer() = runDesktopComposeUiTest(1100, 800) {
        val router = UpdateRouter()
        opened(router)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("7.26beta5")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("action-install").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Run Download & Install on the router?")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(emptyList(), router.ran)
        onNodeWithTag("confirm").performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("confirm").fetchSemanticsNodes().isEmpty() }
        assertEquals(emptyList(), router.ran)

        onNodeWithTag("action-install").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("confirm").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("confirm").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { router.ran.isNotEmpty() }
        assertEquals(listOf("install"), router.ran)
    }

    @Test
    fun theListOfChangesIsTheOnesTheRouterSendsAndTheVendorsOnlyForARouterThatSendsNone() = runDesktopComposeUiTest(1100, 800) {
        val asked = mutableListOf<String>()
        val vendor: suspend (String) -> String? = { version ->
            asked += version
            "Vendor text for $version"
        }
        opened(UpdateRouter(withChangelog = true), vendor)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("What's new in 7.26beta5:", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        assertEquals(emptyList(), asked)
    }

    @Test
    fun aRouterThatSendsNoListGetsTheVendorsTextOfItsLatestVersionOnceAndNothingWhenTheVendorSaysNothing() = runDesktopComposeUiTest(1100, 800) {
        val asked = mutableListOf<String>()
        val vendor: suspend (String) -> String? = { version ->
            asked += version
            "Vendor text for $version"
        }
        opened(UpdateRouter(withChangelog = false), vendor)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Vendor text for 7.26beta5", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        assertEquals(listOf("7.26beta5"), asked)
        picture("update-window-vendor-changelog")
    }

    @Test
    fun withoutAServiceOrAnAnswerTheBoxStaysEmptyAndNothingIsSaid() = runDesktopComposeUiTest(1100, 800) {
        opened(UpdateRouter(withChangelog = false), { null })
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("7.26beta5")).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        onNodeWithTag("changelog").assertExists()
        onAllNodesWithTag("problem").assertCountEquals(0)
    }
}
