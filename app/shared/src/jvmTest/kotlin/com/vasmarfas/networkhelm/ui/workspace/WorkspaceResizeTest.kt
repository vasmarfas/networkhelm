package com.vasmarfas.networkhelm.ui.workspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WorkspaceResizeTest {

    private fun SkikoComposeUiTest.window(position: DpOffset): WindowState {
        val workspace = WorkspaceState()
        val window = workspace.open("rd-test-window", position, DpSize(300.dp, 200.dp))
        setContent { NetworkHelmTheme { Workspace(workspace) { } } }
        waitForIdle()
        return window
    }

    private fun SkikoComposeUiTest.drag(from: Offset, by: Offset) {
        onRoot().performTouchInput {
            down(from)
            moveBy(by / 2f)
            moveBy(by / 2f)
            up()
        }
        waitForIdle()
    }

    @Test
    fun theTopEdgeMakesTheWindowTallerAndKeepsItsBottom() = runDesktopComposeUiTest(900, 600) {
        val window = window(DpOffset(100.dp, 100.dp))
        drag(Offset(250f, 102f), Offset(0f, -40f))
        assertTrue(window.size.height > 200.dp, "the window stayed ${window.size}")
        assertEquals(300.dp, window.position.y + window.size.height)
        assertEquals(DpOffset(100.dp, window.position.y), window.position)
        assertEquals(300.dp, window.size.width)
    }

    @Test
    fun theTopCornersMoveTheirTwoEdges() = runDesktopComposeUiTest(900, 600) {
        val left = window(DpOffset(100.dp, 100.dp))
        drag(Offset(102f, 102f), Offset(-30f, -30f))
        assertTrue(left.size.width > 300.dp && left.size.height > 200.dp, "the window stayed ${left.size}")
        assertEquals(400.dp, left.position.x + left.size.width)
        assertEquals(300.dp, left.position.y + left.size.height)
    }

    @Test
    fun theTopRightCornerKeepsTheLeftEdge() = runDesktopComposeUiTest(900, 600) {
        val right = window(DpOffset(100.dp, 100.dp))
        drag(Offset(398f, 102f), Offset(30f, -30f))
        assertTrue(right.size.width > 300.dp && right.size.height > 200.dp, "the window stayed ${right.size}")
        assertEquals(100.dp, right.position.x)
        assertEquals(300.dp, right.position.y + right.size.height)
    }

    @Test
    fun theTopEdgeStopsAtTheTopOfTheWorkspace() = runDesktopComposeUiTest(900, 600) {
        val window = window(DpOffset(100.dp, 20.dp))
        drag(Offset(250f, 22f), Offset(0f, -100f))
        assertEquals(0.dp, window.position.y)
        assertEquals(220.dp, window.size.height)
    }

    @Test
    fun theTitleBelowTheEdgeStillMovesTheWindow() = runDesktopComposeUiTest(900, 600) {
        val window = window(DpOffset(100.dp, 100.dp))
        drag(Offset(200f, 115f), Offset(60f, 40f))
        assertTrue(window.position.x > 100.dp && window.position.y > 100.dp, "the window stayed at ${window.position}")
        assertEquals(DpSize(300.dp, 200.dp), window.size)
    }
}
