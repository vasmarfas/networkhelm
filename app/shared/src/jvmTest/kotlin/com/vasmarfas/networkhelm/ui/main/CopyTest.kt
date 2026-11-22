package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.center
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.FakeClipboard
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.ui.kit.Copier
import com.vasmarfas.networkhelm.ui.kit.HintHost
import com.vasmarfas.networkhelm.ui.kit.TextMenus
import com.vasmarfas.networkhelm.ui.kit.copyClipboard
import com.vasmarfas.networkhelm.ui.kit.readText
import com.vasmarfas.networkhelm.ui.kit.rememberCopier
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CopyTest {

    private val path = MenuPath.parse("ip/firewall/filter")

    private val rules = listOf(
        Item("*1", mapOf("chain" to "forward", "action" to "accept", "protocol" to "tcp", "dst-port" to "443", "comment" to "web")),
        Item("*2", mapOf("chain" to "input", "action" to "drop", "disabled" to "true")),
        Item("*3", mapOf("chain" to "forward", "action" to "reject", "invalid" to "true", "dynamic" to "true")),
    )

    private val schema = MenuSchema(
        path, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"),
        listOf(
            Argument("chain", ValueType.Text()), Argument("action", ValueType.Enumeration()), Argument("protocol", ValueType.Text()),
            Argument("dst-port", ValueType.PortList), Argument("comment", ValueType.Text()), Argument("disabled", ValueType.YesNo),
        ),
    )

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun SkikoComposeUiTest.field(name: String) = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("field-$name")))

    private fun SkikoComposeUiTest.rulesToCopy(clip: FakeClipboard): FakeRouter {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(mapOf(path.toString() to rules), schemas = mapOf(path.toString() to schema))
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
        setContent { CompositionLocalProvider(LocalClipboard provides clip) { NetworkHelmTheme(dark = true) { HintHost { MainScreen(tab, onDisconnect = {}) } } } }
        tab.open(MenuEntry("Firewall", path.toString()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("drop")).fetchSemanticsNodes().isNotEmpty() }
        return router
    }

    private fun SkikoComposeUiTest.copiedBy(clip: FakeClipboard, item: String, over: String = "accept"): String {
        clip.entry = null
        onNodeWithText(over).performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(item)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText(item).performClick()
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        return runBlocking { clip.readText() }.orEmpty()
    }

    private fun cell(text: String, title: String, separator: Char = '\t') = text.lines().let { it[1].split(separator)[it[0].split(separator).indexOf(title)] }

    private val titles = listOf("Action", "Chain", "Protocol", "Dst. Port")

    @Test
    fun theMenuOfARowCopiesItInEveryForm() = runDesktopComposeUiTest(1100, 700) {
        val clip = FakeClipboard()
        rulesToCopy(clip)
        onNodeWithText("drop").performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Copy as command")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(listOf("Copy as script", "Copy as text", "Copy cell", "Copy as CSV", "Copy as JSON", "Ctrl+Shift+C").all { onAllNodes(hasText(it)).fetchSemanticsNodes().isNotEmpty() })
        picture("copy-row-menu")
        onNodeWithText("Copy as command").performClick()
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertEquals("/ip firewall filter add chain=input action=drop disabled=true", runBlocking { clip.readText() })

        assertEquals("/ip firewall filter add chain=forward protocol=tcp dst-port=443 comment=web", copiedBy(clip, "Copy as command"))
        assertEquals("/ip firewall filter\nadd chain=forward protocol=tcp dst-port=443 comment=web", copiedBy(clip, "Copy as script"))
        val text = copiedBy(clip, "Copy as text")
        assertEquals(2, text.lines().size)
        assertEquals(listOf("accept", "forward", "tcp", "443"), titles.map { cell(text, it) })
        assertEquals("tcp", copiedBy(clip, "Copy cell", over = "tcp"))
        val csv = copiedBy(clip, "Copy as CSV")
        assertEquals(listOf("accept", "forward", "tcp", "443"), titles.map { cell(csv, it, ',') })
        val json = copiedBy(clip, "Copy as JSON")
        assertTrue("\"protocol\": \"tcp\"" in json && "\"chain\": \"forward\"" in json, json)
    }

    @Test
    fun theKeysCopyCommandsAndText() = runDesktopComposeUiTest(1100, 700) {
        val clip = FakeClipboard()
        rulesToCopy(clip)
        onNodeWithText("accept").performClick()
        onNodeWithTag("table").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertEquals("/ip firewall filter add chain=forward protocol=tcp dst-port=443 comment=web", runBlocking { clip.readText() })

        clip.entry = null
        onNodeWithTag("table").performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.C) } } }
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertEquals(listOf("accept", "forward", "tcp", "443"), titles.map { cell(runBlocking { clip.readText() }.orEmpty(), it) })
    }

    @Test
    fun aRowTheRouterMadeItselfGoesAsTextOnly() = runDesktopComposeUiTest(1100, 700) {
        val clip = FakeClipboard()
        rulesToCopy(clip)
        onNodeWithText("reject").performMouseInput { rightClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Copy")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasText("Copy as command")).fetchSemanticsNodes().isEmpty(), "a dynamic rule cannot be added")
        onNodeWithText("Copy").performClick()
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertEquals(listOf("reject", "forward", "", ""), titles.map { cell(runBlocking { clip.readText() }.orEmpty(), it) })
    }

    @Test
    fun theDialogOfAnElementCopiesItsCommandAndTheOnePending() = runDesktopComposeUiTest(1200, 800) {
        val clip = FakeClipboard()
        rulesToCopy(clip)
        onNodeWithText("accept").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Copy command")).fetchSemanticsNodes().isNotEmpty() }
        picture("copy-dialog")
        onNodeWithText("Copy command").performClick()
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertEquals("/ip firewall filter add chain=forward protocol=tcp dst-port=443 comment=web", runBlocking { clip.readText() })

        clip.entry = null
        field("comment").performTextReplacement("web 2")
        onNodeWithText("Command").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("/ip firewall filter set *1", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        picture("copy-dialog-command")
        onAllNodes(hasText("Copy")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertEquals("/ip firewall filter set *1 comment=\"web 2\"", runBlocking { clip.readText() })
    }

    @Test
    fun aValueOfAReadOnlyFieldHasACopyButtonUnderThePointer() = runDesktopComposeUiTest(1200, 800) {
        val clip = FakeClipboard()
        rulesToCopy(clip)
        onNodeWithText("reject").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("forward")).fetchSemanticsNodes().size > 2 }
        assertTrue(onAllNodes(hasContentDescription("Copy value"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        onAllNodes(hasText("forward")).onLast().performMouseInput { moveTo(center) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasContentDescription("Copy value"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        picture("copy-value")
        onNodeWithContentDescription("Copy value", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertEquals("forward", runBlocking { clip.readText() })
    }

    @Test
    fun textOfAWindowIsSelectedAndCopiedWithTheKeys() = runDesktopComposeUiTest(500, 200) {
        val clip = FakeClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboard provides clip) {
                NetworkHelmTheme(dark = true) { HintHost { Text("no such item *7f", Modifier.padding(20.dp).testTag("said")) } }
            }
        }
        onNodeWithTag("said").performMouseInput {
            moveTo(Offset(1f, height / 2f))
            press()
            moveTo(Offset(width - 1f, height / 2f))
            release()
        }
        val copyKey = if ("mac" in System.getProperty("os.name").lowercase()) Key.MetaLeft else Key.CtrlLeft
        onNodeWithTag("said").performKeyInput { withKeyDown(copyKey) { pressKey(Key.C) } }
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertEquals("no such item *7f", runBlocking { clip.readText() })
    }

    @Test
    fun theMenuOfSelectedTextCopiesIt() = runDesktopComposeUiTest(500, 200) {
        val clip = FakeClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboard provides clip) {
                NetworkHelmTheme(dark = true) {
                    TextMenus(dark = true) {
                        HintHost { Text("no such item xyz", Modifier.padding(20.dp).testTag("said")) }
                    }
                }
            }
        }
        val copy = hasText("Copy") or hasText("Копировать")
        onNodeWithTag("said").performMouseInput { doubleClick(Offset(width - 4f, height / 2f)) }
        onNodeWithTag("said").performMouseInput { rightClick(Offset(width - 4f, height / 2f)) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(copy).fetchSemanticsNodes().isNotEmpty() }
        onNode(copy).performMouseInput { moveTo(center) }
        picture("copy-text-menu")
        onNode(copy).performClick()
        waitUntil(timeoutMillis = 5_000) { clip.entry != null }
        assertEquals("xyz", runBlocking { clip.readText() })
    }

    @Test
    fun theMenuOfNestedSelectionStaysInItsOwnLayout() = runDesktopComposeUiTest(500, 200) {
        setContent {
            NetworkHelmTheme(dark = true) {
                TextMenus(dark = true) {
                    HintHost { SelectionContainer { Text("no such item xyz", Modifier.padding(20.dp).testTag("said")) } }
                }
            }
        }
        val copy = hasText("Copy") or hasText("Копировать")
        onNodeWithTag("said").performMouseInput { doubleClick(Offset(width - 4f, height / 2f)) }
        onNodeWithTag("said").performMouseInput { rightClick(Offset(width - 4f, height / 2f)) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(copy).fetchSemanticsNodes().isNotEmpty() }
        onNode(copy).performMouseInput { moveTo(center) }
        waitForIdle()
        onNodeWithTag("said").assertExists()
    }

    @Test
    fun theNoticeOfACopyShowsAndGoes() = runDesktopComposeUiTest(500, 200) {
        Locale.setDefault(Locale.ENGLISH)
        val clip = FakeClipboard()
        var copier: Copier? = null
        setContent {
            CompositionLocalProvider(LocalClipboard provides clip) {
                NetworkHelmTheme(dark = true) {
                    HintHost {
                        copier = rememberCopier()
                        Text("rows", Modifier.padding(20.dp))
                    }
                }
            }
        }
        mainClock.autoAdvance = false
        checkNotNull(copier).copy("text", 3)
        waitUntil(timeoutMillis = 5_000) {
            mainClock.advanceTimeByFrame()
            onAllNodes(hasTestTag("copy-notice")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("copy-notice").assertTextEquals("Copied 3 rows")
        picture("copy-notice")
        assertNotNull(clip.entry)
        mainClock.advanceTimeBy(2_500)
        onNodeWithTag("copy-notice").assertDoesNotExist()
    }

    @Test
    fun textFieldsKeepTheClipboardOfThePlatform() = runDesktopComposeUiTest(500, 200) {
        val clip = FakeClipboard()
        var fields: Clipboard? = null
        var copies: Clipboard? = null
        setContent {
            CompositionLocalProvider(LocalClipboard provides clip) {
                NetworkHelmTheme(dark = true) {
                    HintHost {
                        fields = LocalClipboard.current
                        copies = copyClipboard()
                    }
                }
            }
        }
        waitForIdle()
        assertSame(clip, fields)
        assertNotSame(clip, copies)
    }
}
