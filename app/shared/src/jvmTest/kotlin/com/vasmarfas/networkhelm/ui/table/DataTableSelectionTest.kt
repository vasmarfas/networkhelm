package com.vasmarfas.networkhelm.ui.table

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DataTableSelectionTest {

    @Test
    fun aRowGoneFromTheListLeavesTheSelection() = runDesktopComposeUiTest(600, 300) {
        val state = TableState<String>()
        var rows by mutableStateOf(listOf("rd-test-a", "rd-test-b"))
        setContent {
            NetworkHelmTheme {
                DataTable(rows, listOf(TableColumn<String>("comment", "Comment", 200.dp) { it }), key = { it }, state = state)
            }
        }
        onNodeWithText("rd-test-b").performClick()
        waitForIdle()
        assertEquals(setOf("rd-test-b"), state.selected)
        rows = listOf("rd-test-a")
        waitForIdle()
        assertEquals(emptySet(), state.selected)
        assertEquals(null, state.anchor)
    }

    @Test
    fun aTouchOpensTheRowItWentDownOnThoughAnotherCameAboveIt() = runDesktopComposeUiTest(600, 300) {
        var rows by mutableStateOf(listOf("rd-test-a", "rd-test-b"))
        val opened = mutableListOf<String>()
        setContent {
            NetworkHelmTheme {
                CompositionLocalProvider(LocalCompact provides true) {
                    DataTable(rows, listOf(TableColumn<String>("comment", "Comment", 200.dp) { it }), key = { it }, onOpen = { opened += it })
                }
            }
        }
        onNodeWithText("rd-test-b").performTouchInput { down(center) }
        rows = listOf("rd-test-new", "rd-test-a", "rd-test-b")
        waitForIdle()
        onNodeWithText("rd-test-b").performTouchInput { up() }
        waitForIdle()
        assertEquals(listOf("rd-test-b"), opened)
    }
}
