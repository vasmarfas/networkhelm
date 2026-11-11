package com.vasmarfas.networkhelm.ui.main

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackAction
import com.vasmarfas.networkhelm.packs.PackForm
import com.vasmarfas.networkhelm.packs.PackFormTab
import com.vasmarfas.networkhelm.packs.PackLink
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.packs.PackTab
import com.vasmarfas.networkhelm.packs.PackWindow
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class QuickSetFormTest {
    private val path = "native/120/6"

    private val schema = MenuSchema(
        MenuPath.parse(path), NodeKind.Settings, emptyList(), listOf("set", "11", "13"),
        listOf(Argument("mode", ValueType.Enumeration(closed = true), listOf("Router", "Bridge")), Argument("ip-address", ValueType.Text(), readOnly = true), Argument("nat", ValueType.YesNo), Argument("identity", ValueType.Text())),
    )

    private val pack = Pack(
        menu = listOf("Quick Set"),
        window = PackWindow("Quick Set", listOf(PackTab("Ethernet", path), PackTab("CPE", "native/120/1")), selector = true),
        paths = mapOf(
            path to PackPath(
                readonly = listOf("ip-address"),
                radios = listOf("mode"),
                form = path,
                actions = listOf(PackAction("Renew", "11", beside = "ip-address"), PackAction("Reconnect", "13", below = "identity", row = 1)),
                links = listOf(
                    PackLink("Reboot", "system/reboot", below = "identity", row = 1),
                    PackLink("Password...", "password", below = "identity", row = 3),
                    PackLink("Port Mapping", "native/120/105", below = "nat", depends = listOf(mapOf("nat" to "true"))),
                ),
                labels = mapOf("ip-address" to "IP Address"),
                native = PackNative(listOf(120, 6), emptyList(), single = true),
            ),
        ),
        forms = mapOf(
            path to PackForm(
                tabs = listOf(
                    PackFormTab("Configuration", listOf("mode"), inline = true),
                    PackFormTab("Internet", listOf("ip-address", "nat"), inline = true),
                    PackFormTab("System", listOf("identity"), inline = true),
                ),
            ),
        ),
        prio = 10,
    )

    private val mapping = Pack(
        menu = listOf("Mapping"),
        window = PackWindow("Mapping", listOf(PackTab("Mapping", "native/120/105"))),
        paths = mapOf("native/120/105" to PackPath(native = PackNative(listOf(120, 105), emptyList()))),
        hidden = true,
    )

    private fun ComposeUiTest.opened(inactive: String, nat: String = "true", quick: Pack = pack): FakeRouter {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val record = Item("0", mapOf("mode" to "Router", "ip-address" to "192.0.2.1", "nat" to nat, "identity" to "lab", ".inactive" to inactive))
        val router = FakeRouter(rows = mapOf(path to listOf(record)), schemas = mapOf(path to schema), made = listOf(quick, mapping))
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Quick Set")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Quick Set").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("192.0.2.1")).fetchSemanticsNodes().isNotEmpty() }
        return router
    }

    @Test
    fun theSectionsOfAWindowStandOneUnderTheOtherAndItsButtonsStandWhereTheDescriptionPutsThem() = runDesktopComposeUiTest(1100, 800) {
        val router = opened("false")
        onAllNodesWithTag("section").assertCountEquals(3)
        onAllNodesWithTag("section-line").assertCountEquals(3)
        onNodeWithTag("mode").assertExists()
        onNodeWithText("Router").assertIsSelected()
        onNodeWithText("Bridge").assertIsNotSelected()
        onNodeWithText("Ethernet").assertExists()
        onNodeWithTag("action-13").assertExists()
        onNodeWithTag("link-Reboot").assertExists()
        onNodeWithText("Password...").assertExists()
        onAllNodesWithTag("buttons-line").assertCountEquals(1)
        val reconnect = onNodeWithTag("action-13").getBoundsInRoot()
        val reboot = onNodeWithTag("link-Reboot").getBoundsInRoot()
        val password = onNodeWithTag("link-Password...").getBoundsInRoot()
        assertEquals(reconnect.top, reboot.top)
        assertTrue(password.top > reboot.bottom, "the button of the second toolbar stands under the first row")
        onAllNodesWithText("Command").assertCountEquals(0)
        onAllNodesWithText("Renew").assertCountEquals(1)
        onNodeWithTag("action-11").performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.isNotEmpty() }
        assertEquals(listOf(MenuPath.parse(path).child("11").toString()), router.ran.map { it.first })
        assertTrue(router.ran.all { it.second.isEmpty() })
    }

    @Test
    fun aButtonUnderAFieldThatAsksFirstRunsOnlyAfterTheAnswer() = runDesktopComposeUiTest(1100, 800) {
        val asking = pack.paths.getValue(path).copy(actions = listOf(PackAction("Reconnect", "13", confirm = true, below = "identity", row = 1)))
        val router = opened("false", quick = Pack(menu = pack.menu, window = pack.window, paths = mapOf(path to asking), forms = pack.forms, prio = pack.prio))
        onNodeWithTag("action-13").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Run Reconnect on the router?")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(router.ran.isEmpty())
        onAllNodes(hasText("Reconnect")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.isNotEmpty() }
        assertEquals(listOf(MenuPath.parse(path).child("13").toString()), router.ran.map { it.first })
    }

    @Test
    fun aHiddenWindowIsNoEntryOfTheMenuAndTheButtonThatNamesItOpensIt() = runDesktopComposeUiTest(1100, 800) {
        opened("false")
        onAllNodesWithText("Mapping").assertCountEquals(0)
        onNodeWithTag("link-Port Mapping").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Mapping")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theButtonThatNeedsNatIsGoneWithoutIt() = runDesktopComposeUiTest(1100, 800) {
        opened("false", nat = "false")
        onNodeWithTag("action-13").assertExists()
        onAllNodesWithTag("link-Port Mapping").assertCountEquals(0)
    }

    @Test
    fun theLabelOfAWindowSaysWhetherItsRecordIsTheActiveOne() = runDesktopComposeUiTest(1100, 800) {
        opened("false")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("standing")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("ACTIVE").assertExists()
        onAllNodesWithText("INACTIVE").assertCountEquals(0)
    }

    @Test
    fun aRecordThatIsNotTheActiveOneIsLabelledInactive() = runDesktopComposeUiTest(1100, 800) {
        opened("true")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("standing")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("INACTIVE").assertExists()
    }
}
