package com.vasmarfas.networkhelm.ui.table

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class DataTableDragTest {

    @Test
    fun aPressUnderTheDragSourceStillSelectsTheRow() = runDesktopComposeUiTest(600, 400) {
        val state = TableState<String>()
        setContent {
            NetworkHelmTheme {
                DataTable(listOf("backup.rsc", "notes.txt"), listOf(TableColumn<String>("name", "Name", 200.dp) { it }), key = { it }, state = state, dragOut = { null })
            }
        }
        onNodeWithText("notes.txt").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, 80f))
            release()
        }
        waitForIdle()
        assertEquals(setOf("notes.txt"), state.selected)
    }

    @Test
    fun aColumnWidensByTheWholeDrag() = runDesktopComposeUiTest(600, 400) {
        val state = TableState<String>()
        setContent {
            NetworkHelmTheme {
                DataTable(listOf("backup.rsc"), listOf(TableColumn<String>("name", "Name", 200.dp) { it }, TableColumn<String>("size", "Size", 80.dp) { "" }), key = { it }, state = state)
            }
        }
        onNodeWithTag("resize-name").performMouseInput {
            moveTo(center)
            press()
            repeat(3) {
                moveBy(Offset(20f, 0f))
                advanceEventTime(16)
            }
            release()
        }
        waitForIdle()
        assertEquals(260.dp, state.widths["name"])
    }

    @Test
    fun aColumnWithoutAWidthFitsItsLongestValue() = runDesktopComposeUiTest(800, 300) {
        val long = "fe80::5054:ff:fe12:3456/64"
        setContent {
            NetworkHelmTheme {
                DataTable(listOf("::1/128", long), listOf(TableColumn<String>("address", "Address", null) { it }), key = { it })
            }
        }
        waitForIdle()
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText(long).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertFalse(layouts.single().hasVisualOverflow)
    }

    @Test
    fun aColumnHasRoomForTheWidestValueItIsToGet() = runDesktopComposeUiTest(800, 300) {
        var rate by mutableStateOf("")
        setContent {
            NetworkHelmTheme {
                DataTable(listOf("ether1"), listOf(TableColumn<String>("tx", "Tx", null, { rate }, widest = "99.9 Mbps") { it }), key = { it })
            }
        }
        waitForIdle()
        rate = "99.9 Mbps"
        waitForIdle()
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText("99.9 Mbps").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertFalse(layouts.single().hasVisualOverflow)
    }

    @Test
    fun aRowHeldAtTheEdgeScrollsTheList() = runDesktopComposeUiTest(600, 300) {
        val rows = (0 until 60).map { "rd-test-%02d".format(it) }
        var moved: Pair<List<String>, String?>? = null
        setContent {
            NetworkHelmTheme {
                DataTable(rows, listOf(TableColumn<String>("name", "Name", 200.dp) { it }), key = { it }, onMove = { ids, before -> moved = ids to before })
            }
        }
        onNodeWithText("rd-test-00").performMouseInput {
            moveTo(center)
            press()
        }
        onNodeWithTag("table").performMouseInput { moveTo(Offset(centerX, bottom - 2f)) }
        waitForIdle()
        onNodeWithText("rd-test-59").assertExists()
        onNodeWithTag("table").performMouseInput { release() }
        waitForIdle()
        assertEquals(listOf("rd-test-00") to null, moved)
    }
}
