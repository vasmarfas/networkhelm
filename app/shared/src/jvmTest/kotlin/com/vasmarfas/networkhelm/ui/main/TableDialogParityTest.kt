package com.vasmarfas.networkhelm.ui.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.vasmarfas.networkhelm.FakeClipboard
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.AppSettings
import com.vasmarfas.networkhelm.app.LocalAppSettings
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.kit.readText
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TableDialogParityTest {

    private val path = MenuPath.parse("ip/firewall/filter")

    private val schema = MenuSchema(
        path, NodeKind.List, emptyList(), listOf("add", "move", "print", "remove", "set", "unset"),
        listOf(
            Argument("chain", ValueType.Text(), choices = listOf("forward", "input", "output")),
            Argument("action", ValueType.Enumeration(), choices = listOf("accept", "drop", "reject")),
            Argument("src-address", ValueType.Ip4Range, negatable = true, optional = true),
            Argument("comment", ValueType.Text()),
            Argument("disabled", ValueType.YesNo),
        ),
    )

    private val rules = listOf(
        Item("*1", mapOf("chain" to "forward", "action" to "accept", "comment" to "web-a")),
        Item("*2", mapOf("chain" to "input", "action" to "drop", "comment" to "other")),
        Item("*3", mapOf("chain" to "forward", "action" to "reject", "comment" to "web-b")),
        Item("*4", mapOf("chain" to "output", "action" to "accept", "comment" to "web-c")),
    )

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun SkikoComposeUiTest.field(name: String) = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-$name")))

    private fun top(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().positionInRoot.y

    private fun SemanticsNodeInteraction.focus() = performSemanticsAction(SemanticsActions.RequestFocus) { it.invoke() }

    private fun SkikoComposeUiTest.seen(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun SkikoComposeUiTest.rulesList(router: FakeRouter, clip: FakeClipboard, store: AppSettings) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent {
            CompositionLocalProvider(LocalClipboard provides clip, LocalAppSettings provides store) {
                NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) }
            }
        }
        tab.open(MenuEntry("Firewall", path.toString()))
        waitUntil(timeoutMillis = 5_000) { seen("web-b") }
    }

    private fun SkikoComposeUiTest.newRule(session: DeviceSession) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tab = RouterTab(session, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        tab.openItem("Firewall", path, null)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-chain")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun router() = FakeRouter(mapOf(path.toString() to rules), schemas = mapOf(path.toString() to schema))

    @Test
    fun enterInTheFindFieldGoesToTheNextMatchAndEscapeClearsTheField() = runDesktopComposeUiTest(1100, 700) {
        val clip = FakeClipboard()
        rulesList(router(), clip, AppSettings(MemoryStorage()))
        fun copied(): String {
            clip.entry = null
            onNodeWithTag("table").focus()
            onNodeWithTag("table").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
            waitUntil(timeoutMillis = 5_000) { clip.entry != null }
            return runBlocking { clip.readText() }.orEmpty()
        }
        fun step(backwards: Boolean = false) {
            onNodeWithTag("find").focus()
            onNodeWithTag("find").performKeyInput {
                if (backwards) withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } else pressKey(Key.Enter)
            }
        }

        onNodeWithTag("find").performTextInput("web")
        waitUntil(timeoutMillis = 5_000) { seen("3 of 4 items") }
        assertTrue(onAllNodes(hasTestTag("find-next")).fetchSemanticsNodes().isNotEmpty())
        step()
        assertTrue("web-a" in copied(), "the first match")
        step()
        assertTrue("web-b" in copied(), "the next one")
        step(backwards = true)
        assertTrue("web-a" in copied(), "and back")
        step(backwards = true)
        assertTrue("web-c" in copied(), "before the first is the last")
        onNodeWithTag("find-next").performClick()
        assertTrue("web-a" in copied(), "the button goes forward too")

        onNodeWithTag("find").focus()
        onNodeWithTag("find").performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) { seen("other") }
        assertTrue(onAllNodes(hasTestTag("table")).fetchSemanticsNodes().isNotEmpty(), "the window stays")
        assertTrue(onAllNodes(hasTestTag("find-next")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun escapeCancelsARemovalEnterConfirmsItAndTheBoxStopsTheQuestion() = runDesktopComposeUiTest(1100, 700) {
        val router = router()
        val store = AppSettings(MemoryStorage())
        rulesList(router, FakeClipboard(), store)
        val question = "Remove 1 item from the router?"

        onNodeWithText("drop").performClick()
        onNodeWithTag("table").performKeyInput { pressKey(Key.Delete) }
        waitUntil(timeoutMillis = 5_000) { seen(question) }
        picture("list-remove-asking")
        onNodeWithTag("confirm").performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) { !seen(question) }
        assertTrue(onAllNodes(hasTestTag("table")).fetchSemanticsNodes().isNotEmpty(), "Escape cancels the question and keeps the window")
        assertTrue(router.removed.isEmpty())

        onNodeWithTag("table").performKeyInput { pressKey(Key.Delete) }
        waitUntil(timeoutMillis = 5_000) { seen(question) }
        onNodeWithTag("confirm").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { router.removed.isNotEmpty() }
        assertEquals(listOf(listOf("*2")), router.removed)
        assertTrue(store.current.confirmRemoval)

        onNodeWithTag("table").performKeyInput { pressKey(Key.Delete) }
        waitUntil(timeoutMillis = 5_000) { seen(question) }
        onNodeWithTag("ask-again").performClick()
        onAllNodes(hasText("Remove")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { router.removed.size == 2 && !store.current.confirmRemoval }
    }

    @Test
    fun theCommentOfARowIsTypedAtOnceAndEnterWritesIt() = runDesktopComposeUiTest(1100, 700) {
        val router = router()
        rulesList(router, FakeClipboard(), AppSettings(MemoryStorage()))
        onNodeWithText("drop").performClick()
        onNodeWithTag("table").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.M) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("comment")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onNodeWithTag("comment").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true }
        onNodeWithTag("comment").performTextReplacement("guests")
        onNodeWithTag("comment").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { router.changed.isNotEmpty() }
        assertEquals(listOf("*2" to mapOf<String, String?>("comment" to "guests")), router.changed.toList())
    }

    @Test
    fun theCommentOfSeveralRowsStartsFromTheFirstOfThem() = runDesktopComposeUiTest(1100, 700) {
        rulesList(router(), FakeClipboard(), AppSettings(MemoryStorage()))
        onNodeWithText("drop").performClick()
        onNodeWithTag("table").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.A) } }
        onNodeWithTag("table").performKeyInput { pressKey(Key.Spacebar) }
        onNodeWithTag("table").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.M) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("comment")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("comment").assertTextEquals("other")
    }

    @Test
    fun aLetterTypedWithAltGrIsNoShortcutOfTheList() = runDesktopComposeUiTest(1100, 700) {
        val router = router()
        rulesList(router, FakeClipboard(), AppSettings(MemoryStorage()))
        onNodeWithText("drop").performClick()
        onNodeWithTag("table").performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltRight) { pressKey(Key.E) } } }
        waitForIdle()
        assertTrue(router.changed.isEmpty())
        onNodeWithTag("table").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.E) } }
        waitUntil(timeoutMillis = 5_000) { router.changed.isNotEmpty() }
    }

    @Test
    fun aValueTheClientRejectsShowsItsReasonUnderTheFieldAndHoldsTheButtons() = runDesktopComposeUiTest(1200, 800) {
        val router = router()
        newRule(router)
        field("src-address").performTextInput("not-an-address")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("mismatch-src-address")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("mismatch-src-address").assertTextEquals("Expected: A.B.C.D/M | A.B.C.D-A.B.C.D")
        onNodeWithText("OK").assertIsNotEnabled()
        onNodeWithText("Apply").assertIsNotEnabled()
        picture("dialog-mismatch")

        field("src-address").performTextReplacement("192.0.2.0/24")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("mismatch-src-address")).fetchSemanticsNodes().isEmpty() }
        onNodeWithText("OK").assertIsEnabled()
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 5_000) { router.added.isNotEmpty() }
        assertEquals(mapOf("src-address" to "192.0.2.0/24"), router.added.single())
    }

    @Test
    fun enterInATextFieldIsOkAndEscapeClosesTheDialogWithoutAsking() = runDesktopComposeUiTest(1200, 800) {
        val router = router()
        newRule(router)
        field("chain").performTextInput("input")
        field("chain").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { router.added.isNotEmpty() }
        assertEquals(mapOf("chain" to "input"), router.added.single())
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-chain")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun enterWithAValueTheClientRejectsSendsNothingAndKeepsTheDialog() = runDesktopComposeUiTest(1200, 800) {
        val router = router()
        newRule(router)
        field("src-address").performTextInput("not-an-address")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("mismatch-src-address")).fetchSemanticsNodes().isNotEmpty() }
        field("src-address").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertTrue(router.added.isEmpty())
        assertTrue(onAllNodes(hasTestTag("field-src-address")).fetchSemanticsNodes().isNotEmpty(), "the dialog stays")
    }

    @Test
    fun escapeClosesADialogWithUnsavedChanges() = runDesktopComposeUiTest(1200, 800) {
        val router = router()
        newRule(router)
        field("chain").performTextInput("input")
        field("chain").performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-chain")).fetchSemanticsNodes().isEmpty() }
        assertTrue(router.added.isEmpty())
    }

    @Test
    fun okOfAnUntouchedNewRuleCreatesItWithTheDefaultsAndApplyKeepsTheDialog() = runDesktopComposeUiTest(1200, 800) {
        val router = router()
        newRule(router)
        onNodeWithText("Apply").assertIsEnabled()
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 5_000) { router.added.isNotEmpty() }
        assertEquals(emptyMap(), router.added.single())
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-chain")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun applyOfANewRuleCreatesItAndTheDialogBecomesTheDialogOfTheRecord() = runDesktopComposeUiTest(1200, 800) {
        val router = router()
        newRule(router)
        assertTrue(seen("New Firewall Rule"))
        assertTrue(!seen("Remove"))
        field("chain").performTextInput("input")
        onNodeWithText("Apply").performClick()
        waitUntil(timeoutMillis = 5_000) { router.added.isNotEmpty() && seen("Remove") }
        assertEquals(mapOf("chain" to "input"), router.added.single())
        assertTrue(!seen("New Firewall Rule"), "the title is the record's now")
        onNodeWithText("Apply").assertIsNotEnabled()
        assertTrue(onAllNodes(hasTestTag("field-chain")).fetchSemanticsNodes().isNotEmpty(), "the dialog stays")
    }

    private class SlowRouter(private val fake: FakeRouter) : DeviceSession by fake {
        val answer = CompletableDeferred<Unit>()
        var asked = 0

        override suspend fun add(path: MenuPath, values: Map<String, String>): String {
            asked++
            answer.await()
            return fake.add(path, values)
        }
    }

    @Test
    fun twoPressesOfOkBeforeTheRouterAnswersAddOneRule() = runDesktopComposeUiTest(1200, 800) {
        val fake = router()
        val slow = SlowRouter(fake)
        newRule(slow)
        field("chain").performTextInput("input")
        onNodeWithText("OK").performMouseInput {
            moveTo(center)
            press()
            release()
            press()
            release()
        }
        waitUntil(timeoutMillis = 5_000) { slow.asked > 0 }
        waitForIdle()
        assertEquals(1, slow.asked)
        slow.answer.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("field-chain")).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(mapOf("chain" to "input")), fake.added.toList())
    }

    @Test
    fun whatIsTypedWhileTheRouterAnswersStaysForTheNextApply() = runDesktopComposeUiTest(1200, 800) {
        val fake = router()
        val slow = SlowRouter(fake)
        newRule(slow)
        field("chain").performTextInput("input")
        onNodeWithText("Apply").performClick()
        waitUntil(timeoutMillis = 5_000) { slow.asked == 1 }
        field("chain").performTextReplacement("output")
        waitForIdle()
        slow.answer.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { fake.added.isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("Apply").assertIsEnabled() }.isSuccess }
        assertTrue(onAllNodes(hasText("output") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty(), "the newer text stays in the field")
        assertTrue(onAllNodes(hasText("On the router", substring = true)).fetchSemanticsNodes().isEmpty(), "the value just written is not one the router changed")
        onNodeWithText("Apply").performClick()
        waitUntil(timeoutMillis = 5_000) { fake.changed.isNotEmpty() }
        assertEquals(listOf(mapOf("chain" to "input")), fake.added.toList())
        assertEquals(listOf("*99" to mapOf<String, String?>("chain" to "output")), fake.changed.toList())
    }

    @Test
    fun aRefusalOfTheRouterStandsAboveTheButtonsAndLeavesTheDialogOpen() = runDesktopComposeUiTest(1200, 800) {
        val fake = router()
        val refusing = object : DeviceSession by fake {
            override suspend fun add(path: MenuPath, values: Map<String, String>): String = throw DeviceException(ErrorCategory.MissingItem, "already have such address")
        }
        newRule(refusing)
        field("chain").performTextInput("input")
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("problem")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("problem").assertTextEquals("already have such address")
        assertTrue(top(onNodeWithTag("problem")) < top(onNodeWithText("OK")), "a line above the buttons")
        onNodeWithText("OK").assertIsEnabled()
        onNodeWithText("Apply").assertIsEnabled()
        picture("dialog-error-line")
    }

    @Test
    fun theFirstEditableFieldHasTheFocusWhenTheDialogOpens() = runDesktopComposeUiTest(1200, 800) {
        newRule(router())
        waitUntil(timeoutMillis = 5_000) { field("chain").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true }
        waitForIdle()
        picture("dialog-focus")
    }
}
