package com.vasmarfas.networkhelm.ui.table

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.ui.theme.Legacy
import com.vasmarfas.networkhelm.ui.theme.Palette
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class DataTableWidthTest {

    @Test
    fun theHeaderTheChosenRowAndTheLinesReachTheEdgeOfTheWindow() = runDesktopComposeUiTest(800, 200) {
        lateinit var palette: Palette
        var row = 0
        setContent {
            NetworkHelmTheme(Legacy, dark = false) {
                palette = Theme.palette
                row = Theme.metrics.row.value.toInt()
                DataTable(
                    listOf("a", "b"),
                    listOf(TableColumn<String>("name", "Name", 120.dp) { it }),
                    key = { it },
                    modifier = Modifier.fillMaxSize(),
                    picker = { Box(Modifier.size(28.dp).testTag("picker")) },
                )
            }
        }
        waitForIdle()
        val edge = 790
        val open = captureToImage().toPixelMap()
        assertEquals(palette.raised, open[edge, row / 2], "the header")
        assertEquals(palette.grid, open[edge, 2 * row - 1], "the line under the first row")
        assertEquals(800f, onNodeWithTag("picker").fetchSemanticsNode().boundsInRoot.right, 1f)

        onNodeWithText("a").performClick()
        waitForIdle()
        assertEquals(palette.selection, captureToImage().toPixelMap()[edge, row + row / 2], "the chosen row")
    }

    private fun SemanticsNodeInteraction.cut(): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().hasVisualOverflow
    }

    @Test
    fun aColumnTakesTheWidthOfItsWidestValueNotOfItsLongest() = runDesktopComposeUiTest(800, 200) {
        setContent {
            NetworkHelmTheme {
                DataTable(
                    listOf("iiiiiiiiii", "WWWWWWWW"),
                    listOf(TableColumn<String>("name", "N", null) { it }, TableColumn<String>("rest", "R", 60.dp) { "" }),
                    key = { it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        waitForIdle()
        assertFalse(onNodeWithText("WWWWWWWW").cut())
    }

    @Test
    fun theLastColumnTakesTheRoomTheOthersLeave() = runDesktopComposeUiTest(800, 200) {
        val long = "a message of the router that runs on far past the width the first rows gave its column"
        setContent {
            NetworkHelmTheme {
                DataTable(
                    listOf("short", long),
                    listOf(TableColumn<String>("time", "Time", 80.dp) { "09:41:55" }, TableColumn<String>("message", "Message", 200.dp) { it }),
                    key = { it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        waitForIdle()
        assertFalse(onNodeWithText(long).cut())
    }
}
