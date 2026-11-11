package com.vasmarfas.networkhelm.ui.main

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
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
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackFallback
import com.vasmarfas.networkhelm.packs.PackForm
import com.vasmarfas.networkhelm.packs.PackFormTab
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.packs.PackNativeField
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.packs.PackQuery
import com.vasmarfas.networkhelm.packs.PackTab
import com.vasmarfas.networkhelm.packs.PackWindow
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.QUERY_END
import com.vasmarfas.networkhelm.session.QUERY_PASS
import com.vasmarfas.networkhelm.session.TextStore
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class QueryViewTest {
    private val key = "native/22/q1"

    private val request = listOf(PackNativeField("address", 0x16, "addr", letter = "m", writable = true), PackNativeField("count", 0x11, "number", letter = "u", writable = true))

    private val schema = MenuSchema(
        MenuPath.parse(key), NodeKind.Command, emptyList(), emptyList(),
        listOf(Argument("address", ValueType.Text()), Argument("count", ValueType.Integer(), optional = true)),
    )

    private fun pack(action: Boolean = false, risky: Boolean = false, confirm: String? = null, close: Boolean = false, fallback: PackFallback? = null, notes: Map<String, String> = emptyMap(), fasttrack: Boolean = false) = Pack(
        menu = listOf("Echo"),
        window = PackWindow("Echo", listOf(PackTab("Echo", key))),
        paths = mapOf(
            key to PackPath(
                columns = if (action) emptyList() else listOf("seq", "host"),
                form = key,
                labels = mapOf("address" to "Echo To", "seq" to "Seq #", "summary" to "Summary", "lost" to "Lost Packets", "rate" to "Tx/Rx Current", "uptime" to "Uptime"),
                bits = listOf("rate"),
                confirm = risky,
                fasttrack = fasttrack,
                native = PackNative(
                    listOf(22),
                    emptyList(),
                    query = PackQuery(
                        action, request, 0xFE000F, 0xFE0011, if (action) 0xFE0010 else 0xFE0004,
                        uptime = "uptime".takeIf { action }, required = listOf("address"), optional = listOf("count"),
                        results = if (action) listOf("lost", "rate", "uptime") else emptyList(), status = listOf("summary"), confirm = confirm,
                        notes = notes, close = close, fallback = fallback,
                    ),
                ),
            ),
        ),
        forms = mapOf(key to PackForm(tabs = listOf(PackFormTab("General", listOf("address", "count") + notes.keys)))),
    )

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun SkikoComposeUiTest.type(name: String, text: String) =
        onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-$name"))).onFirst().performTextInput(text)

    private fun SkikoComposeUiTest.show(router: FakeRouter): RouterTab {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Echo")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Echo").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Start")).fetchSemanticsNodes().isNotEmpty() }
        return tab
    }

    private fun SkikoComposeUiTest.open(router: FakeRouter, entry: MenuEntry) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Echo")).fetchSemanticsNodes().isNotEmpty() }
        tab.open(entry)
    }

    private fun router(made: Pack, output: List<Item>, endless: Boolean = true, refused: Set<String> = emptySet(), shown: MenuSchema = schema, gone: Set<String> = emptySet()) =
        FakeRouter(schemas = mapOf(key to shown), made = listOf(made), output = mapOf("$key/start" to output), endless = endless, refused = refused, gone = gone)

    @Test
    fun aToolThatTurnsFastTrackOffSaysSoBeforeItStarts() = runDesktopComposeUiTest(1100, 700) {
        show(router(pack(fasttrack = true), emptyList()))
        onNodeWithText("FastTrack", substring = true).assertIsDisplayed()
    }

    @Test
    fun aQueryStartsWhenTheRequiredFieldIsGivenAndSendsOnlyWhatWasTouched() = runDesktopComposeUiTest(1100, 700) {
        val rows = listOf(
            Item("*0", mapOf("seq" to "0", "host" to "10.0.2.9", "summary" to "1 of 1 packets received")),
            Item("*1", mapOf("seq" to "1", "host" to "10.0.2.9", "summary" to "2 of 2 packets received")),
        )
        val router = router(pack(), rows)
        show(router)
        onNodeWithTag("query-start").assertIsNotEnabled()
        onNodeWithTag("query-stop").assertIsNotEnabled()
        type("address", "10.0.2.2")
        onNodeWithTag("query-start").assertIsEnabled()
        onNodeWithTag("query-start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("2 of 2 packets received", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("$key/start" to mapOf("address" to "10.0.2.2")), router.ran)
        onNodeWithTag("query-count").assertTextEquals("2 items")
        onNodeWithTag("query-status").assertTextEquals("Summary: 2 of 2 packets received")
        onNodeWithTag("query-stop").assertIsEnabled()
        onNodeWithTag("query-start").assertIsNotEnabled()
        picture("query-view")
        onNodeWithTag("query-stop").performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("query-start").assertIsEnabled() }.isSuccess }
    }

    @Test
    fun aRouterThatRefusesTheQueryGivesTheWindowBackToTheConsoleCommandOnceInsteadOfAnError() = runDesktopComposeUiTest(1100, 700) {
        val console = PackFallback(PackPath(), PackForm(tabs = listOf(PackFormTab("General", listOf("address")))), mapOf("address" to "Console Address"))
        val router = router(pack(fallback = console), emptyList(), refused = setOf("$key/start"))
        show(router)
        type("address", "10.0.2.2")
        onNodeWithTag("query-start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Console Address")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, onAllNodes(hasTestTag("problem")).fetchSemanticsNodes().size)
        assertEquals(1, router.ran.size)
    }

    @Test
    fun aWindowOpenedByALinkOfAMenuStartsOnItsOwn() = runDesktopComposeUiTest(1100, 700) {
        val router = router(pack(), emptyList())
        open(router, MenuEntry("Echo", key, arguments = mapOf("address" to "10.0.2.2"), start = true))
        waitUntil(timeoutMillis = 5_000) { router.ran.isNotEmpty() }
        assertEquals(listOf("$key/start" to mapOf("address" to "10.0.2.2")), router.ran)
    }

    @Test
    fun aStartThatTheRouterRefusesGivesTheConsoleWindowTheStartOfTheLinkToo() = runDesktopComposeUiTest(1100, 700) {
        val console = PackFallback(PackPath(), PackForm(tabs = listOf(PackFormTab("General", listOf("address")))), mapOf("address" to "Console Address"))
        val router = router(pack(fallback = console), emptyList(), refused = setOf("$key/start"))
        open(router, MenuEntry("Echo", key, arguments = mapOf("address" to "10.0.2.2"), start = true))
        waitUntil(timeoutMillis = 5_000) { router.ran.size == 2 }
        assertEquals(listOf("$key/start", "native/22/q1"), router.ran.map { it.first })
    }

    @Test
    fun aWindowOfAQueryThatTheRouterRefusedBeforeIsTheConsoleOneWhenItOpensAgain() = runDesktopComposeUiTest(1100, 700) {
        val console = PackFallback(PackPath(), PackForm(tabs = listOf(PackFormTab("General", listOf("address")))), mapOf("address" to "Console Address"))
        show(router(pack(fallback = console), emptyList(), gone = setOf(key)))
        onNodeWithText("Console Address").assertExists()
        assertEquals(0, onAllNodes(hasTestTag("query-start")).fetchSemanticsNodes().size)
    }

    @Test
    fun aWindowOpenedBeforeTheDescriptionsOfTheRouterCameTakesTheFieldsOfTheQuery() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val console = MenuSchema(MenuPath.parse(key), NodeKind.Command, emptyList(), emptyList(), listOf(Argument("host", ValueType.Text())))
        val described = CompletableDeferred<Unit>()
        val router = router(pack(), emptyList())
        val late = object : DeviceSession by router {
            override suspend fun packs(store: TextStore?): List<Pack> {
                described.await()
                return router.packs(store)
            }

            override suspend fun describe(path: MenuPath): MenuSchema = if (described.isCompleted) router.describe(path) else console
        }
        val storage = MemoryStorage()
        val tab = RouterTab(late, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.open(MenuEntry("Echo", key))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-host")).fetchSemanticsNodes().isNotEmpty() }
        described.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-address")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, onAllNodes(hasTestTag("field-host")).fetchSemanticsNodes().size)
    }

    @Test
    fun aRouterThatRefusesAQueryWithoutAConsoleWindowShowsItsAnswer() = runDesktopComposeUiTest(1100, 700) {
        show(router(pack(), emptyList(), refused = setOf("$key/start")))
        type("address", "10.0.2.2")
        onNodeWithTag("query-start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("problem")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aWindowThatClosesItselfIsClosedWhenTheActionIsOver() = runDesktopComposeUiTest(1100, 700) {
        val state = listOf(Item("#0", mapOf("lost" to "0", QUERY_PASS to "1")), Item(QUERY_END, mapOf(QUERY_PASS to "1")))
        val tab = show(router(pack(action = true, close = true), state, endless = false))
        type("address", "10.0.2.2")
        assertEquals(1, tab.workspace.windows.size)
        onNodeWithTag("query-start").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.workspace.windows.isEmpty() }
    }

    @Test
    fun aLabelOfTheRequestIsALineOfTextInTheFormAndAChoiceOfTheSchemaIsAButton() = runDesktopComposeUiTest(1100, 700) {
        val choosing = MenuSchema(
            MenuPath.parse(key), NodeKind.Command, emptyList(), emptyList(),
            listOf(Argument("address", ValueType.Enumeration(closed = true), choices = listOf("ether1", "bridge1"), labels = mapOf("ether1" to "ether1", "bridge1" to "bridge1")), Argument("count", ValueType.Integer(), optional = true)),
        )
        val router = router(pack(notes = mapOf("do-not-turn-it-off" to "Do not turn it off.")), emptyList(), shown = choosing)
        show(router)
        onNodeWithText("Do not turn it off.").assertExists()
        onNodeWithTag("query-start").assertIsNotEnabled()
        onNodeWithText("bridge1").performClick()
        onNodeWithTag("query-start").assertIsEnabled()
        onNodeWithTag("query-start").performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.size == 1 }
        assertEquals(mapOf("address" to "bridge1"), router.ran.single().second)
    }

    @Test
    fun aTableOfPassesReplacesTheRowsOfThePassBefore() = runDesktopComposeUiTest(1100, 700) {
        fun item(id: String, host: String, pass: String) = Item(id, mapOf("seq" to id, "host" to host, QUERY_PASS to pass))
        val passes = listOf(
            item("*1", "10.0.2.31", "1"), item("*2", "10.0.2.32", "1"), Item(QUERY_END, mapOf(QUERY_PASS to "1")),
            item("*1", "10.0.2.33", "2"), Item(QUERY_END, mapOf(QUERY_PASS to "2")),
        )
        show(router(pack(), passes))
        type("address", "10.0.2.2")
        onNodeWithTag("query-start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("10.0.2.33")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, onAllNodes(hasText("10.0.2.31")).fetchSemanticsNodes().size)
        onNodeWithTag("query-count").assertTextEquals("1 item")
    }

    @Test
    fun aWindowThatRisksDataAsksBeforeItStartsAndUsesTheTextOfTheRouter() = runDesktopComposeUiTest(1100, 700) {
        val router = router(pack(risky = true, confirm = "All data will be lost, are you sure?"), emptyList())
        show(router)
        type("address", "10.0.2.2")
        onNodeWithTag("query-start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("All data will be lost, are you sure?")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(router.ran.isEmpty())
        onNodeWithText("Cancel").performClick()
        assertTrue(router.ran.isEmpty())
        onNodeWithTag("query-start").performClick()
        onAllNodes(hasText("Start")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { router.ran.size == 1 }
        assertEquals(mapOf("address" to "10.0.2.2"), router.ran.single().second)
    }

    @Test
    fun aWindowThatRisksDataWithoutTheTextOfTheRouterAsksInItsOwnWords() = runDesktopComposeUiTest(1100, 700) {
        show(router(pack(risky = true), emptyList()))
        type("address", "10.0.2.2")
        onNodeWithTag("query-start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Echo changes data or settings of the router and cannot be undone. Start it?")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun anActionShowsItsFieldsTheRateOfItsTupleAndTheTimeItRuns() = runDesktopComposeUiTest(1100, 700) {
        val state = listOf(
            Item("#0", mapOf("lost" to "17", "rate" to "3000000/5000000", "summary" to "running...", QUERY_PASS to "1")),
            Item(QUERY_END, mapOf(QUERY_PASS to "1")),
        )
        show(router(pack(action = true), state))
        type("address", "10.0.2.2")
        onNodeWithTag("query-start").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("17")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("3.0 Mbps / 5.0 Mbps")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Lost Packets").assertExists()
        onNodeWithText("Uptime").assertExists()
        onNodeWithTag("query-status").assertTextEquals("Summary: running...")
        picture("query-action")
        onNodeWithTag("query-stop").performClick()
    }
}
