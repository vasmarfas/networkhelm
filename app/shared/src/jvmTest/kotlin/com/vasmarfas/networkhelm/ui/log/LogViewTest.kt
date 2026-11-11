package com.vasmarfas.networkhelm.ui.log

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.FakeClipboard
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.files.FileExchange
import com.vasmarfas.networkhelm.ui.files.LocalFile
import com.vasmarfas.networkhelm.ui.files.LocalFileExchange
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.readText
import com.vasmarfas.networkhelm.ui.main.MainScreen
import com.vasmarfas.networkhelm.ui.main.RouterTab
import com.vasmarfas.networkhelm.ui.main.SavedWindow
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.Legacy
import com.vasmarfas.networkhelm.ui.theme.Modern
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import com.vasmarfas.networkhelm.ui.theme.ThemeFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LogViewTest {

    private fun line(id: String, time: String, topics: String, message: String) = Item(id, mapOf("time" to time, "topics" to topics, "message" to message))

    private val log = listOf(
        line("*1", "2026-10-26 23:58:40", "system,info,account", "user admin logged in from 192.0.2.5 via winbox"),
        line("*2", "2026-10-27 09:00:05", "firewall,info", "input: in:ether1 out:(unknown 0), src-mac 00:00:5e:00:53:01, proto TCP (SYN), 192.0.2.77:51514->192.0.2.1:22, len 60"),
        line("*3", "2026-10-27 09:01:10", "system,error", "login failure for user root from 192.0.2.77 via ssh"),
        line("*4", "2026-10-27 09:01:11", "system,error", "login failure for user root from 192.0.2.77 via ssh"),
        line("*5", "2026-10-27 09:01:12", "wireless,debug", "ap1: 00:00:5E:00:53:01 connected, signal strength -61"),
        line("*6", "2026-10-27 09:30:00", "dhcp,warning", "dhcp1 deassigned 192.0.2.88 from 00:00:5E:00:53:44"),
        line("*7", "2026-10-27 09:40:00", "system,error,critical", "router rebooted because of a kernel failure"),
    )

    private val failure = "login failure for user root from 192.0.2.77 via ssh"

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun SemanticsNodeInteraction.written() = fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun SkikoComposeUiTest.drawn(tag: String) = onNodeWithTag(tag).written()

    private fun SkikoComposeUiTest.present(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun SkikoComposeUiTest.texts(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private fun SkikoComposeUiTest.inList(text: String) = onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag("log-list"))).onFirst()

    private fun SkikoComposeUiTest.listed(text: String) = onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag("log-list"))).fetchSemanticsNodes().size

    private fun SkikoComposeUiTest.inWindow(id: Long, tag: String) = onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("log-entry-$id")))

    private fun hasTextLike(pattern: Regex) = SemanticsMatcher("has text like $pattern") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.let(pattern::matches) == true
    }

    private fun SkikoComposeUiTest.press(key: Key, ctrl: Boolean = false) {
        onNodeWithTag("log-list").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("log-list").performKeyInput { if (ctrl) withKeyDown(Key.CtrlLeft) { pressKey(key) } else pressKey(key) }
    }

    private fun RouterTab.shownLines() = logEntries.entries.sortedBy { it.key }.map { it.value.row.first.id }

    private fun SkikoComposeUiTest.opened(
        session: DeviceSession,
        clip: Clipboard = FakeClipboard(),
        compact: Boolean = false,
        dark: Boolean = true,
        theme: ThemeFile = Modern,
        exchange: FileExchange? = null,
        tables: TableLayouts = TableLayouts(MemoryStorage()),
        storage: MemoryStorage = MemoryStorage(),
    ): RouterTab {
        Locale.setDefault(Locale.ENGLISH)
        val tab = RouterTab(session, "192.0.2.1", bundledPacks().values, storage, tables)
        val locals = listOfNotNull(LocalClipboard provides clip, LocalCompact provides compact, exchange?.let { LocalFileExchange provides it })
        setContent {
            CompositionLocalProvider(*locals.toTypedArray()) { NetworkHelmTheme(theme, dark) { MainScreen(tab, onDisconnect = {}) } }
        }
        tab.open(MenuEntry("Log", "log"))
        waitUntil(timeoutMillis = 10_000) { present("log-list") && !present("log-empty") }
        return tab
    }

    private fun SkikoComposeUiTest.opened(
        rows: List<Item> = log,
        clip: Clipboard = FakeClipboard(),
        compact: Boolean = false,
        dark: Boolean = true,
        theme: ThemeFile = Modern,
        exchange: FileExchange? = null,
        tables: TableLayouts = TableLayouts(MemoryStorage()),
        storage: MemoryStorage = MemoryStorage(),
    ) = opened(FakeRouter(mapOf("log" to rows)), clip, compact, dark, theme, exchange, tables, storage)

    private fun live(rows: List<Item> = log): Pair<DeviceSession, MutableSharedFlow<ListEvent>> {
        val router = FakeRouter(mapOf("log" to rows))
        val updates = MutableSharedFlow<ListEvent>(extraBufferCapacity = 16)
        val session = object : DeviceSession by router {
            override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> =
                flow {
                    emit(ListEvent.Snapshot(rows))
                    emitAll(updates)
                }
        }
        return session to updates
    }

    @Test
    fun aLogComesAsLinesDrawnBySeverityWithTheirCountAndDays() = runDesktopComposeUiTest(1100, 640) {
        opened()
        assertEquals("7 lines", drawn("log-count"))
        assertEquals("2026-10-26", drawn("log-day"))
        assertTrue(texts("2026-10-26") > 0 && texts("2026-10-27") > 0, "a heading for each day")
        inList("user admin logged in from 192.0.2.5 via winbox").assertExists()
        assertEquals(2, onAllNodes(hasText(failure) and hasAnyAncestor(hasTestTag("log-list"))).fetchSemanticsNodes().size)
        picture("log-window-dark")
    }

    @Test
    fun theLightThemeAndTheTimeAsTheRouterWritesIt() = runDesktopComposeUiTest(1100, 640) {
        opened(dark = false)
        onNodeWithTag("log-view").performClick()
        waitUntil(timeoutMillis = 5_000) { texts("Time as the router writes it") > 0 }
        onAllNodes(hasText("Time as the router writes it")).onFirst().performClick()
        waitUntil(timeoutMillis = 5_000) { texts("2026-10-27 09:01:10") > 0 }
        picture("log-window-light")
    }

    @Test
    fun theSearchNarrowsTheLinesToThoseWithItsWords() = runDesktopComposeUiTest(1100, 640) {
        opened()
        onNodeWithTag("log-find").performTextInput("login 192.0.2.77")
        waitUntil(timeoutMillis = 5_000) { drawn("log-count") == "2 of 7 lines" }
        assertEquals("1 of 2", drawn("log-hits"))
        assertEquals(0, onAllNodes(hasText("dhcp1 deassigned 192.0.2.88 from 00:00:5E:00:53:44")).fetchSemanticsNodes().size)
        picture("log-search")
    }

    @Test
    fun aSeverityChosenInItsMenuNarrowsTheListAndLeavesAChip() = runDesktopComposeUiTest(1100, 640) {
        opened()
        onNodeWithTag("log-severity").performClick()
        waitUntil(timeoutMillis = 5_000) { texts("Warnings") > 0 }
        onAllNodes(hasText("Warnings")).onFirst().performClick()
        waitUntil(timeoutMillis = 5_000) { drawn("log-count") == "1 of 7 lines" }
        assertTrue(present("log-clear"), "the chip bar offers to clear the narrowing")
        onNodeWithTag("log-clear").performClick()
        waitUntil(timeoutMillis = 5_000) { drawn("log-count") == "7 lines" }
    }

    @Test
    fun linesChosenCopyAsTheConsolePrintsThemAndTheMenuOffersTheOtherForms() = runDesktopComposeUiTest(1100, 640) {
        val clip = FakeClipboard()
        opened(clip = clip)
        inList("dhcp1 deassigned 192.0.2.88 from 00:00:5E:00:53:44").performClick()
        onNodeWithTag("log-list").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertEquals("2026-10-27 09:30:00 dhcp,warning dhcp1 deassigned 192.0.2.88 from 00:00:5E:00:53:44", runBlocking { clip.readText() })

        onNodeWithTag("log-list").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.A) } }
        inList(failure).performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) { texts("Copy as CSV") > 0 }
        assertTrue(listOf("Copy lines", "Copy messages", "Copy as JSON", "Select all", "Ctrl+Shift+C").all { texts(it) > 0 })
        picture("log-row-menu")
        onAllNodes(hasText("Copy messages")).onFirst().performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { clip.readText() }.orEmpty().lines().size == 7 }
        assertEquals(log.joinToString("\n") { it["message"].orEmpty() }, runBlocking { clip.readText() })
    }

    private class HeldClipboard : Clipboard {
        var refused = 0

        override suspend fun getClipEntry(): ClipEntry? = null

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            refused++
            throw IllegalStateException("cannot open system clipboard")
        }
    }

    @Test
    fun aClipboardHeldByAnotherProgramLeavesTheLogAndTheWindowOfALineOpen() = runDesktopComposeUiTest(1100, 640) {
        val clip = HeldClipboard()
        val tab = opened(clip = clip)
        inList(failure).performClick()
        press(Key.C, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { clip.refused == 1 }
        press(Key.Enter, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.size == 1 }
        val id = tab.logEntries.keys.single()
        waitUntil(timeoutMillis = 5_000) { present("log-entry-$id") }
        inWindow(id, "log-copy-line").performClick()
        waitUntil(timeoutMillis = 5_000) { clip.refused == 2 }
        waitForIdle()
        assertTrue(present("log-list") && present("log-entry-$id"))
    }

    @Test
    fun aLetterTypedWithAltGrIsNotTakenForItsShortcut() = runDesktopComposeUiTest(1100, 640) {
        opened()
        onNodeWithTag("log-list").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("log-list").performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltRight) { pressKey(Key.A) } } }
        waitForIdle()
        assertEquals(0, texts("7 selected"))
        press(Key.A, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { texts("7 selected") > 0 }
    }

    private fun SkikoComposeUiTest.panelOn() = onNodeWithTag("log-details-toggle").performClick()

    @Test
    fun theLineChosenIsShownInThePanelWithTheMessageWholeTheTopicsAndTheLinesAround() = runDesktopComposeUiTest(1100, 640) {
        val clip = FakeClipboard()
        opened(clip = clip)
        panelOn()
        inList("input: in:ether1 out:(unknown 0), src-mac 00:00:5e:00:53:01, proto TCP (SYN), 192.0.2.77:51514->192.0.2.1:22, len 60").performClick()
        waitUntil(timeoutMillis = 5_000) { present("log-details") }
        assertEquals("input: in:ether1 out:(unknown 0), src-mac 00:00:5e:00:53:01, proto TCP (SYN), 192.0.2.77:51514->192.0.2.1:22, len 60", drawn("log-message"))
        assertEquals("2026-10-27 09:00:05", drawn("log-time"))
        assertTrue(present("log-topic-firewall") && present("log-context") && present("log-previous-line") && present("log-next-line"))
        picture("log-details")

        onNodeWithTag("log-copy-line").performClick()
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertTrue(runBlocking { clip.readText() }.orEmpty().startsWith("2026-10-27 09:00:05 firewall,info input: in:ether1"))

        onNodeWithTag("log-next-line").performClick()
        waitUntil(timeoutMillis = 5_000) { drawn("log-message") == failure }
        onNodeWithTag("log-topic-system").performClick()
        waitUntil(timeoutMillis = 5_000) { drawn("log-count") == "4 of 7 lines" }
        onNodeWithTag("log-close").performClick()
        waitUntil(timeoutMillis = 5_000) { !present("log-details") }
    }

    @Test
    fun withThePanelOffADoubleClickAndEnterOpenTheLineInAWindowAndWithItOnTheyDoNot() = runDesktopComposeUiTest(1100, 640) {
        val tab = opened()
        inList(failure).performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.size == 1 }
        assertEquals(listOf("*3"), tab.shownLines())
        assertFalse(present("log-window"), "no panel under the list")
        press(Key.DirectionDown)
        press(Key.Enter)
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.size == 2 }
        assertEquals(listOf("*3", "*4"), tab.shownLines())

        panelOn()
        inList("dhcp1 deassigned 192.0.2.88 from 00:00:5E:00:53:44").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { present("log-window") }
        press(Key.Enter)
        waitForIdle()
        assertEquals(2, tab.logEntries.size, "a line is read in the panel while it is on")
    }

    @Test
    fun theButtonOfThePanelIsAsHighAsTheOtherButtonsOfTheBarInTheLegacyLook() = runDesktopComposeUiTest(1100, 640) {
        opened(dark = false, theme = Legacy)
        val bar = onNodeWithTag("log-view").fetchSemanticsNode().boundsInRoot
        val panel = onNodeWithTag("log-details-toggle").fetchSemanticsNode().boundsInRoot
        assertEquals(bar.height, panel.height, 0.5f)
        assertEquals(bar.top, panel.top, 0.5f)
        picture("log-toolbar-legacy")
    }

    @Test
    fun theStatusLineCountsTheErrorsAndTheWarningsAndNarrowsTheLogToThem() = runDesktopComposeUiTest(1100, 640) {
        opened()
        assertEquals("3", drawn("log-quick-errors"))
        assertEquals("1", drawn("log-quick-warnings"))
        onNodeWithTag("log-quick-errors").performClick()
        waitUntil(timeoutMillis = 5_000) { drawn("log-count") == "3 of 7 lines" }
        onNodeWithTag("log-quick-errors").performClick()
        waitUntil(timeoutMillis = 5_000) { drawn("log-count") == "7 lines" }
        picture("log-status-counts")
    }

    @Test
    fun theLinesAreSavedAsTextOrAsCsvThroughTheFileExchange() = runDesktopComposeUiTest(1100, 640) {
        val saved = mutableListOf<Pair<String, String>>()
        val exchange = object : FileExchange {
            override suspend fun pick() = emptyList<LocalFile>()

            override suspend fun save(name: String, bytes: ByteArray): Boolean {
                saved += name to bytes.decodeToString()
                return true
            }
        }
        opened(exchange = exchange)
        onNodeWithTag("log-copy").performClick()
        waitUntil(timeoutMillis = 5_000) { texts("Save as CSV…") > 0 }
        onAllNodes(hasText("Save as CSV…")).onFirst().performClick()
        waitUntil(timeoutMillis = 5_000) { saved.isNotEmpty() }
        assertEquals("log.csv", saved.single().first)
        assertTrue(saved.single().second.startsWith("time,topics,message\r\n2026-10-26 23:58:40,\"system,info,account\",user admin logged in"), saved.single().second)
        assertEquals(8, saved.single().second.lines().size)
    }

    @Test
    fun theViewOfTheLogIsKeptWithTheLayoutsOfTheTables() = runDesktopComposeUiTest(1100, 640) {
        val tables = TableLayouts(MemoryStorage())
        opened(tables = tables)
        onNodeWithTag("log-view").performClick()
        waitUntil(timeoutMillis = 5_000) { texts("Wrap long lines") > 0 }
        onAllNodes(hasText("Wrap long lines")).onFirst().performClick()
        waitUntil(timeoutMillis = 5_000) { tables["log"]?.log?.wrap == true }
        assertEquals(TimeStyle.Short, tables["log"]?.log?.times)
        assertEquals(true, tables["log"]?.log?.onlyMatches)
    }

    @Test
    fun repeatsFoldIntoOneRowWithTheirNumber() = runDesktopComposeUiTest(1100, 640) {
        opened()
        onNodeWithTag("log-view").performClick()
        waitUntil(timeoutMillis = 5_000) { texts("Collapse repeated lines") > 0 }
        onAllNodes(hasText("Collapse repeated lines")).onFirst().performClick()
        waitUntil(timeoutMillis = 5_000) { texts("×2") > 0 }
        assertEquals(1, onAllNodes(hasText(failure) and hasAnyAncestor(hasTestTag("log-list"))).fetchSemanticsNodes().size)
    }

    @Test
    fun aHeldListKeepsItsLinesAndCountsTheNewOnes() = runDesktopComposeUiTest(1100, 640) {
        val (session, updates) = live()
        opened(session)
        onNodeWithTag("log-live").performClick()
        waitUntil(timeoutMillis = 5_000) { texts("Paused") > 0 }
        updates.tryEmit(ListEvent.Upsert(line("*8", "2026-10-27 09:41:00", "system,info", "a line that came while the list was held")))
        waitUntil(timeoutMillis = 5_000) { texts("+1 new") > 0 }
        assertEquals("7 lines", drawn("log-count"))
        assertEquals(0, texts("a line that came while the list was held"))
        onNodeWithTag("log-live").performClick()
        waitUntil(timeoutMillis = 5_000) { drawn("log-count") == "8 lines" }
        assertTrue(texts("a line that came while the list was held") > 0)
    }

    private fun busy(): List<Item> {
        val kinds = listOf(
            "system,info,account" to "user admin logged in from 192.0.2.100 via winbox",
            "firewall,info" to "forward: in:ether1 out:bridge1, src-mac 00:00:5e:00:53:12, proto UDP, 192.0.2.12:5353->198.51.100.251:5353, len 87",
            "dhcp,info" to "dhcp1 assigned 192.0.2.101 to 04:7C:16:AA:BB:CC",
            "wireless,info" to "04:7C:16:AA:BB:CC@wlan1: connected, signal strength -58",
            "interface,info" to "ether5 link up (speed 1G, full duplex)",
            "system,error,critical" to "login failure for user admin from 192.0.2.55 via ssh",
            "system,warning" to "disk is almost full: 3.1 MiB free",
            "script,error" to "script error: no such item (line 12 column 14)",
            "ipsec,debug,packet" to "ike2 exchange with 198.51.100.9 retransmitted: 3 packets",
        )
        return List(80) { index ->
            val (topics, message) = kinds[(index * 7 + index / 5) % kinds.size]
            val minutes = 22 * 60 + index * 9
            val day = if (minutes >= 24 * 60) "2026-10-27" else "2026-10-26"
            val clock = minutes % (24 * 60)
            line("*${(index + 1).toString(16).uppercase()}", "$day ${(clock / 60).toString().padStart(2, '0')}:${(clock % 60).toString().padStart(2, '0')}:${(index * 13 % 60).toString().padStart(2, '0')}", topics, message)
        }
    }

    @Test
    fun aBusyLogIsFollowedToItsEndAndKeepsItsColumnsApart() = runDesktopComposeUiTest(1280, 760) {
        opened(busy())
        waitForIdle()
        assertEquals("80 lines", drawn("log-count"))
        picture("log-window-busy")
        onNodeWithTag("log-view").performClick()
        waitUntil(timeoutMillis = 5_000) { texts("Wrap long lines") > 0 }
        onAllNodes(hasText("Wrap long lines")).onFirst().performClick()
        waitForIdle()
        picture("log-window-wrapped")
    }

    private fun added(count: Int) = List(count) { line("*N$it", "2026-10-27 10:0$it:00", "system,info", "the line $it of those that came later") }

    @Test
    fun aFullBufferThatTradesItsOldestLinesForNewOnesIsFollowedToItsEnd() = runDesktopComposeUiTest(1280, 760) {
        val first = busy()
        val (session, updates) = live(first)
        opened(session)
        waitForIdle()
        updates.tryEmit(ListEvent.Snapshot(first.drop(3) + added(3)))
        waitUntil(timeoutMillis = 5_000) { listed("the line 2 of those that came later") > 0 }
        assertEquals("80 lines", drawn("log-count"))
    }

    @Test
    fun aLogScrolledAwayFromItsEndStaysWhereItIsWhenNewLinesCome() = runDesktopComposeUiTest(1280, 760) {
        val first = listOf(line("*0", "2026-10-26 21:00:00", "system,info", "the oldest line of the buffer")) + busy()
        val (session, updates) = live(first)
        opened(session)
        waitForIdle()
        press(Key.MoveHome)
        waitUntil(timeoutMillis = 5_000) { listed("the oldest line of the buffer") > 0 }
        updates.tryEmit(ListEvent.Snapshot(first + added(3)))
        waitUntil(timeoutMillis = 5_000) { drawn("log-count") == "84 lines" }
        waitForIdle()
        assertEquals(0, listed("the line 2 of those that came later"))
        assertTrue(listed("the oldest line of the buffer") > 0)
    }

    @Test
    fun aDayThatComesAgainAfterAnotherOneHasAHeadingOfItsOwn() = runDesktopComposeUiTest(1100, 640) {
        opened(
            listOf(
                line("*1", "2026-10-27 08:59:50", "system,info", "router rebooted"),
                line("*2", "1970-01-02 00:00:12", "interface,info", "ether1 link up (speed 1G, full duplex)"),
                line("*3", "2026-10-27 09:00:20", "system,info", "the clock is set by ntp"),
            ),
        )
        assertEquals(2, listed("2026-10-27"))
        assertEquals(1, listed("1970-01-02"))
        assertEquals("3 lines", drawn("log-count"))
    }

    @Test
    fun theLegacyThemeDrawsTheListAndTheDetails() = runDesktopComposeUiTest(1100, 640) {
        opened(busy(), dark = false, theme = Legacy)
        waitForIdle()
        picture("log-window-legacy")
        panelOn()
        inList("login failure for user admin from 192.0.2.55 via ssh").performClick()
        waitUntil(timeoutMillis = 5_000) { present("log-details") }
        picture("log-window-legacy-details")
    }

    @Test
    fun aPhoneShowsTheLineInTwoRowsAndTheDetailsOnTheWholeWindow() = runDesktopComposeUiTest(400, 780) {
        opened(compact = true)
        picture("log-phone")
        inList(failure).performTouchInput { click() }
        waitUntil(timeoutMillis = 5_000) { present("log-details") }
        assertEquals(failure, drawn("log-message"))
        picture("log-phone-details")
        onNodeWithTag("log-close").performClick()
        waitUntil(timeoutMillis = 5_000) { !present("log-details") }
    }

    private fun SkikoComposeUiTest.copiedAfter(key: Key, rows: List<Item>): String {
        val clip = FakeClipboard()
        opened(rows, clip = clip)
        press(key)
        press(Key.C, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        return runBlocking { clip.readText() }.orEmpty()
    }

    @Test
    fun endGoesToTheLastLineFromNoLineChosen() = runDesktopComposeUiTest(1100, 640) {
        val rows = busy()
        assertEquals("${rows.last()["time"]} ${rows.last()["topics"]} ${rows.last()["message"]}", copiedAfter(Key.MoveEnd, rows))
    }

    @Test
    fun homeGoesToTheFirstLineFromNoLineChosen() = runDesktopComposeUiTest(1100, 640) {
        val rows = busy()
        assertEquals("${rows.first()["time"]} ${rows.first()["topics"]} ${rows.first()["message"]}", copiedAfter(Key.MoveHome, rows))
    }

    @Test
    fun escapeClosesThePaneThenClearsTheChoiceAndThenTheWindow() = runDesktopComposeUiTest(1100, 640) {
        val tab = opened()
        panelOn()
        inList(failure).performClick()
        waitUntil(timeoutMillis = 5_000) { present("log-details") }
        press(Key.Escape)
        waitUntil(timeoutMillis = 5_000) { !present("log-details") }
        assertTrue(texts("1 selected") > 0)
        press(Key.Escape)
        waitUntil(timeoutMillis = 5_000) { texts("1 selected") == 0 }
        assertTrue(present("log-list"))
        press(Key.Escape)
        waitUntil(timeoutMillis = 5_000) { !present("log-list") }
        assertTrue(tab.workspace.windows.isEmpty())
    }

    @Test
    fun theButtonOfTheDetailsOpensTheLineInAWindowWithItsMessageTimeTopicsAndLinesAround() = runDesktopComposeUiTest(1100, 640) {
        val clip = FakeClipboard()
        val tab = opened(clip = clip)
        panelOn()
        inList(failure).performClick()
        waitUntil(timeoutMillis = 5_000) { present("log-window") }
        onNodeWithTag("log-window").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.size == 1 }
        val id = tab.logEntries.keys.single()
        waitUntil(timeoutMillis = 5_000) { present("log-entry-$id") }
        assertEquals(failure, inWindow(id, "log-message").written())
        assertEquals("2026-10-27 09:01:10", inWindow(id, "log-time").written())
        assertTrue(texts("09:01:10 login failure for user root from 192.0.2.77 via…") > 0, "the window is titled by the time and the first words of the message")
        inWindow(id, "log-topic-system").assertExists()
        inWindow(id, "log-topic-error").assertExists()
        onAllNodes(hasText("Error") and hasAnyAncestor(hasTestTag("log-entry-$id"))).assertCountEquals(1)
        onAllNodes(hasTextLike(Regex("""just now|\d+ (s|min|h|d) ago""")) and hasAnyAncestor(hasTestTag("log-entry-$id"))).assertCountEquals(1)
        onAllNodes(hasText("dhcp1 deassigned 192.0.2.88 from 00:00:5E:00:53:44") and hasAnyAncestor(hasTestTag("log-entry-$id"))).assertCountEquals(1)
        listOf("log-same-message", "log-close", "log-window").forEach { inWindow(id, it).assertDoesNotExist() }

        inWindow(id, "log-copy-message").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { clip.readText() } == failure }
        inWindow(id, "log-copy-line").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { clip.readText() } == "2026-10-27 09:01:10 system,error $failure" }
        inWindow(id, "log-copy-fields").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { clip.readText() } == ".id: *3\ntime: 2026-10-27 09:01:10\ntopics: system,error\nmessage: $failure" }
        picture("log-entry-window-dark")
    }

    @Test
    fun theMenuOfARowAndCtrlEnterOpenWindowsAndEnterOpensTheLineToo() = runDesktopComposeUiTest(1100, 640) {
        val tab = opened(dark = false)
        inList(failure).performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) { texts("Open in a window") > 0 }
        assertTrue(texts("Ctrl+Enter") > 0)
        onAllNodes(hasText("Open in a window")).onFirst().performClick()
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.size == 1 }
        assertEquals(listOf("*3"), tab.shownLines())
        assertEquals(1, onAllNodes(hasTestTag("log-details")).fetchSemanticsNodes().size, "the pane stays closed, the window has its own details")

        press(Key.DirectionDown)
        press(Key.Enter, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.size == 2 }
        assertEquals(listOf("*3", "*4"), tab.shownLines())

        press(Key.DirectionDown)
        press(Key.Enter)
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.size == 3 }
        assertEquals(listOf("*3", "*4", "*5"), tab.shownLines())
        assertFalse(present("log-window"), "no panel under the list")
        picture("log-entry-windows-light")
    }

    @Test
    fun theLegacyThemeDrawsTheWindowOfALine() = runDesktopComposeUiTest(1100, 640) {
        val tab = opened(busy(), dark = false, theme = Legacy)
        inList("login failure for user admin from 192.0.2.55 via ssh").performClick()
        press(Key.Enter, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.size == 1 }
        waitUntil(timeoutMillis = 5_000) { present("log-entry-${tab.logEntries.keys.single()}") }
        picture("log-entry-window-legacy")
    }

    @Test
    fun severalLinesAreReadSideBySideAndTheNinthWindowTakesThePlaceOfTheOldest() = runDesktopComposeUiTest(1280, 760) {
        val rows = busy()
        val tab = opened(rows)
        repeat(9) {
            press(Key.DirectionUp)
            press(Key.Enter, ctrl = true)
        }
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.size == 8 && tab.shownLines().last() == rows[71].id }
        assertEquals((78 downTo 71).map { rows[it].id }, tab.shownLines())
        assertEquals(9, tab.workspace.windows.size, "the log and eight lines")
        picture("log-entry-windows")

        val older = tab.workspace.windows.first { tab.logEntries[it.id]?.row?.first?.id == rows[75].id }
        runOnIdle { tab.workspace.bringToFront(older) }
        press(Key.Enter, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { tab.logEntries[tab.workspace.windows.last().id]?.row?.first?.id == rows[71].id }
        assertEquals(8, tab.logEntries.size, "a line already shown comes to the front instead of a second window")
    }

    @Test
    fun aLineAlreadyShownComesToTheFrontAndTheNinthWindowClosesTheOldest() {
        Locale.setDefault(Locale.ENGLISH)
        val tab = RouterTab(FakeRouter(), "192.0.2.1", bundledPacks().values, MemoryStorage(), TableLayouts(MemoryStorage()))
        val entries = busy().map { it.toEntry("2026-10-27") }
        val rows = entries.map { LogRow(listOf(it)) }
        fun open(index: Int) = tab.openLogEntry(LogEntryWindow(MenuPath.parse("log"), rows, entries, index, RouterClock("2026-10-27", 0)))
        repeat(8) { open(it) }
        val oldest = tab.workspace.windows.first()
        open(3)
        assertEquals(8, tab.logEntries.size)
        assertEquals(rows[3].key, tab.logEntries.getValue(tab.workspace.windows.last().id).row.first.id)
        open(8)
        assertEquals((1..8).map { rows[it].key }, tab.shownLines())
        assertFalse(oldest in tab.workspace.windows)
    }

    @Test
    fun theArrowsOfAWindowGoOverTheLinesOfTheMomentItWasOpenedAndALineStaysAfterTheRouterDropsIt() = runDesktopComposeUiTest(1100, 640) {
        val (session, updates) = live()
        val tab = opened(session)
        inList(failure).performClick()
        press(Key.Enter, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.size == 1 }
        val id = tab.logEntries.keys.single()
        val window = tab.workspace.windows.last()
        waitUntil(timeoutMillis = 5_000) { present("log-entry-$id") }

        inWindow(id, "log-previous-line").performClick()
        waitUntil(timeoutMillis = 5_000) { inWindow(id, "log-time").written() == "2026-10-27 09:00:05" }
        inWindow(id, "log-previous-line").performClick()
        waitUntil(timeoutMillis = 5_000) { window.title == "23:58:40 user admin logged in from 192.0.2.5 via winbox" }
        assertEquals("user admin logged in from 192.0.2.5 via winbox", inWindow(id, "log-message").written())
        inWindow(id, "log-previous-line").assertIsNotEnabled()

        repeat(6) { inWindow(id, "log-next-line").performClick() }
        val rebooted = "router rebooted because of a kernel failure"
        waitUntil(timeoutMillis = 5_000) { inWindow(id, "log-message").written() == rebooted }
        inWindow(id, "log-next-line").assertIsNotEnabled()

        updates.tryEmit(ListEvent.Upsert(line("*8", "2026-10-27 09:41:00", "system,info", "a line that came after the window was opened")))
        waitUntil(timeoutMillis = 5_000) { drawn("log-count") == "8 lines" }
        inWindow(id, "log-next-line").assertIsNotEnabled()
        updates.tryEmit(ListEvent.Removed("*7"))
        waitUntil(timeoutMillis = 5_000) { drawn("log-count") == "7 lines" }
        assertEquals(rebooted, inWindow(id, "log-message").written())
        onAllNodes(hasText(rebooted) and hasAnyAncestor(hasTestTag("log-context")) and hasAnyAncestor(hasTestTag("log-entry-$id"))).assertCountEquals(1)
    }

    @Test
    fun aFoldedRowOpensInAWindowThatTellsHowOftenItRepeated() = runDesktopComposeUiTest(1100, 640) {
        val tab = opened()
        onNodeWithTag("log-view").performClick()
        waitUntil(timeoutMillis = 5_000) { texts("Collapse repeated lines") > 0 }
        onAllNodes(hasText("Collapse repeated lines")).onFirst().performClick()
        waitUntil(timeoutMillis = 5_000) { texts("×2") > 0 }
        inList(failure).performClick()
        press(Key.Enter, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.size == 1 }
        val id = tab.logEntries.keys.single()
        waitUntil(timeoutMillis = 5_000) { present("log-entry-$id") }
        assertEquals("2026-10-27 09:01:11", inWindow(id, "log-time").written())
        onAllNodes(hasText("Repeated 2 times, 2026-10-27 09:01:10 to 2026-10-27 09:01:11") and hasAnyAncestor(hasTestTag("log-entry-$id"))).assertCountEquals(1)
        inWindow(id, "log-next-line").performClick()
        waitUntil(timeoutMillis = 5_000) { inWindow(id, "log-time").written() == "2026-10-27 09:01:12" }
    }

    @Test
    fun showingTheLineInTheWholeLogRaisesTheLogWindowClearsTheNarrowingAndChoosesTheLine() = runDesktopComposeUiTest(1280, 760) {
        val unique = "the line the window was opened for"
        val rows = busy().mapIndexed { index, item -> if (index == 60) line(item.id, "2026-10-27 07:00:00", "system,info", unique) else item }
        val clip = FakeClipboard()
        val tab = opened(rows, clip = clip)
        val log = tab.workspace.windows.single()
        repeat(20) { press(Key.DirectionUp) }
        waitUntil(timeoutMillis = 5_000) { listed(unique) == 1 }
        press(Key.Enter, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.size == 1 }
        val id = tab.logEntries.keys.single()
        press(Key.MoveEnd)
        onNodeWithTag("log-find").performTextInput("login")
        waitUntil(timeoutMillis = 5_000) { drawn("log-count") != "80 lines" && listed(unique) == 0 }

        inWindow(id, "log-show").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.workspace.windows.last().id == log.id }
        waitUntil(timeoutMillis = 5_000) { drawn("log-count") == "80 lines" }
        waitUntil(timeoutMillis = 5_000) { listed(unique) == 1 }
        assertTrue(texts("1 selected") > 0)
        press(Key.C, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertEquals("2026-10-27 07:00:00 system,info $unique", runBlocking { clip.readText() })

        runOnIdle { tab.workspace.close(log) }
        waitUntil(timeoutMillis = 5_000) { !present("log-list") }
        inWindow(id, "log-show").performClick()
        waitUntil(timeoutMillis = 10_000) { present("log-list") && texts("1 selected") > 0 }
        assertEquals(2, tab.workspace.windows.size)
        assertEquals("80 lines", drawn("log-count"))
    }

    @Test
    fun aWindowOfALineClosesAloneAndIsNotKeptInTheSavedLayout() = runDesktopComposeUiTest(1100, 640) {
        val storage = MemoryStorage()
        val tab = opened(storage = storage)
        inList(failure).performClick()
        press(Key.Enter, ctrl = true)
        press(Key.DirectionDown)
        press(Key.Enter, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.size == 2 }
        val (first, second) = tab.logEntries.keys.sorted()
        runOnIdle { tab.workspace.close(tab.workspace.windows.first { it.id == first }) }
        waitUntil(timeoutMillis = 5_000) { !present("log-entry-$first") }
        assertEquals(listOf(second), tab.logEntries.keys.toList())
        assertTrue(present("log-entry-$second") && present("log-list"))

        val log = tab.workspace.windows.first { it.id in tab.specs }
        runOnIdle { log.position = DpOffset(120.dp, 60.dp) }
        waitUntil(timeoutMillis = 5_000) { runBlocking { storage.read("workspace-rd-test-id.json") }?.contains("\"x\":120.0") == true }
        val saved = Json.decodeFromString(ListSerializer(SavedWindow.serializer()), runBlocking { storage.read("workspace-rd-test-id.json") }.orEmpty())
        assertEquals(listOf("log"), saved.flatMap { window -> window.tabs.map { it.path } })
    }

    @Test
    fun escapeClosesTheWindowOfALineWhenItHasTheKeyboard() = runDesktopComposeUiTest(1100, 640) {
        val tab = opened()
        inList(failure).performClick()
        press(Key.Enter, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.size == 1 }
        val id = tab.logEntries.keys.single()
        waitUntil(timeoutMillis = 5_000) { present("log-entry-$id") }
        inWindow(id, "log-message").performClick()
        onNodeWithTag("log-entry-$id").performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.isEmpty() }
        assertTrue(present("log-list"))
    }

    @Test
    fun aPhoneShowsTheWindowOfALineOnTheWholeScreenAndBackLeavesToTheLog() = runDesktopComposeUiTest(400, 780) {
        val tab = opened(compact = true)
        inList(failure).performTouchInput { click() }
        waitUntil(timeoutMillis = 5_000) { present("log-window") }
        onNodeWithTag("log-window").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.size == 1 }
        val id = tab.logEntries.keys.single()
        waitUntil(timeoutMillis = 5_000) { present("log-entry-$id") }
        assertFalse(present("log-list"), "the log stands behind the window")
        assertEquals(failure, inWindow(id, "log-message").written())
        assertTrue(texts("09:01:10 login failure for user root from 192.0.2.77 via…") > 0, "the bar of the phone names the line")
        picture("log-entry-phone")

        inWindow(id, "log-previous-line").performClick()
        waitUntil(timeoutMillis = 5_000) { inWindow(id, "log-time").written() == "2026-10-27 09:00:05" }
        onNodeWithTag("back").performClick()
        waitUntil(timeoutMillis = 5_000) { tab.logEntries.isEmpty() && present("log-list") }
    }
}
