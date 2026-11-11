package com.vasmarfas.networkhelm.ui.table

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DataTableKeysTest {

    private fun SkikoComposeUiTest.press(key: Key) {
        onNodeWithTag("table").performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.top(text: String) = onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    @Test
    fun homeEndAndThePagesMoveTheSelectionAndScrollToIt() = runDesktopComposeUiTest(600, 300) {
        val state = TableState<String>()
        val rows = List(60) { "row-%02d".format(it) }
        setContent {
            NetworkHelmTheme {
                DataTable(rows, listOf(TableColumn<String>("name", "Name", 200.dp) { it }), key = { it }, state = state)
            }
        }
        onNodeWithText("row-02").performClick()
        waitForIdle()
        assertEquals(setOf("row-02"), state.selected)

        press(Key.MoveEnd)
        assertEquals(setOf("row-59"), state.selected)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("row-59")).fetchSemanticsNodes().isNotEmpty() }

        press(Key.MoveHome)
        assertEquals(setOf("row-00"), state.selected)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("row-00")).fetchSemanticsNodes().isNotEmpty() }

        press(Key.PageDown)
        val afterPage = rows.indexOf(state.anchor)
        assertTrue(afterPage in 4..30, "a page is a screenful of rows, got $afterPage")
        press(Key.PageUp)
        assertTrue(rows.indexOf(state.anchor) <= 1, "a page back from the first screen is the top")
    }

    @Test
    fun spaceTakesTheFocusedRowOutOfTheSelectionAndBack() = runDesktopComposeUiTest(600, 300) {
        val state = TableState<String>()
        val rows = listOf("rd-test-a", "rd-test-b", "rd-test-c")
        setContent {
            NetworkHelmTheme {
                DataTable(rows, listOf(TableColumn<String>("name", "Name", 200.dp) { it }), key = { it }, state = state)
            }
        }
        onNodeWithText("rd-test-b").performClick()
        waitForIdle()
        press(Key.Spacebar)
        assertEquals(emptySet(), state.selected)
        assertEquals("rd-test-b", state.anchor)
        press(Key.Spacebar)
        assertEquals(setOf("rd-test-b"), state.selected)
    }

    @Test
    fun aLetterTypedWithAltGrDoesNotSelectAllTheRows() = runDesktopComposeUiTest(600, 300) {
        val state = TableState<String>()
        val rows = listOf("rd-test-a", "rd-test-b", "rd-test-c")
        setContent {
            NetworkHelmTheme {
                DataTable(rows, listOf(TableColumn<String>("name", "Name", 200.dp) { it }), key = { it }, state = state)
            }
        }
        onNodeWithText("rd-test-b").performClick()
        waitForIdle()
        onNodeWithTag("table").performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltRight) { pressKey(Key.A) } } }
        waitForIdle()
        assertEquals(setOf("rd-test-b"), state.selected)
        onNodeWithTag("table").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.A) } }
        waitForIdle()
        assertEquals(rows.toSet(), state.selected)
    }

    @Test
    fun aHeaderSortsByValueAscendingDescendingAndAgain() = runDesktopComposeUiTest(600, 300) {
        val state = TableState<String>()
        val rows = listOf("10M", "100k", "9M", "1500", "")
        setContent {
            NetworkHelmTheme {
                DataTable(rows, listOf(TableColumn<String>("limit", "Limit", 200.dp) { it }), key = { it.ifEmpty { "none" } }, state = state)
            }
        }
        fun shown() = listOf("1500", "100k", "9M", "10M").sortedBy { top(it) }
        fun expect(order: List<String>) = waitUntil(timeoutMillis = 5_000) { shown() == order }

        onNodeWithText("Limit").performClick()
        expect(listOf("1500", "100k", "9M", "10M"))
        assertTrue(onAllNodes(hasText("Limit ▲")).fetchSemanticsNodes().isNotEmpty())

        onNodeWithText("Limit ▲").performClick()
        expect(listOf("10M", "9M", "100k", "1500"))
        assertTrue(onAllNodes(hasText("Limit ▼")).fetchSemanticsNodes().isNotEmpty())

        onNodeWithText("Limit ▼").performClick()
        expect(listOf("1500", "100k", "9M", "10M"))
        assertTrue(onAllNodes(hasText("Limit ▲")).fetchSemanticsNodes().isNotEmpty(), "never back to unsorted")
    }

    @Test
    fun aDoubleClickOnTheBorderOfAHeaderFitsTheColumnAgain() = runDesktopComposeUiTest(600, 300) {
        val state = TableState<String>()
        setContent {
            NetworkHelmTheme {
                DataTable(listOf("rd-test-a"), listOf(TableColumn<String>("name", "Name", null) { it }), key = { it }, state = state)
            }
        }
        state.widths["name"] = 300.dp
        waitForIdle()
        onNodeWithTag("resize-name").performMouseInput { doubleClick() }
        waitForIdle()
        assertNull(state.widths["name"])
    }
}
