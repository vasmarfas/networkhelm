package com.vasmarfas.networkhelm.ui.workspace

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.ui.table.DataTable
import com.vasmarfas.networkhelm.ui.table.TableColumn
import com.vasmarfas.networkhelm.ui.table.TableState
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WorkspaceTouchTest {

    @Test
    fun aTouchPicksARowOfAListInAWindow() = runDesktopComposeUiTest(900, 600) {
        val workspace = WorkspaceState()
        workspace.open("list", DpOffset.Zero, DpSize(500.dp, 300.dp))
        val table = TableState<String>()
        setContent {
            NetworkHelmTheme {
                Workspace(workspace) {
                    DataTable(listOf("rd-test-a", "rd-test-b"), listOf(TableColumn<String>("comment", "Comment", 200.dp) { it }), key = { it }, state = table)
                }
            }
        }
        onNodeWithText("rd-test-b").performTouchInput { click() }
        waitForIdle()
        assertEquals(setOf("rd-test-b"), table.selected)
    }

    @Test
    fun aTitleStartsToDragWhileTheListsWaitLonger() = runDesktopComposeUiTest(900, 600) {
        val workspace = WorkspaceState()
        val window = workspace.open("list", DpOffset(100.dp, 100.dp), DpSize(300.dp, 200.dp))
        setContent {
            NetworkHelmTheme {
                val base = LocalViewConfiguration.current
                CompositionLocalProvider(LocalViewConfiguration provides object : ViewConfiguration by base { override val touchSlop = 400f }) {
                    Workspace(workspace) { }
                }
            }
        }
        onNodeWithText("list").performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            moveBy(Offset(60f, 0f))
            up()
        }
        waitForIdle()
        assertTrue(window.position.x > 100.dp, "the window stayed at ${window.position}")
    }
}
