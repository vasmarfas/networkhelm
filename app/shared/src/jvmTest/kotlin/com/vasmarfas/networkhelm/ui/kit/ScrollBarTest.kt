package com.vasmarfas.networkhelm.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ScrollBarTest {

    @Test
    fun theThumbOfAListKeepsItsDragOverRowsOfDifferentHeights() = runDesktopComposeUiTest(300, 200) {
        val state = LazyListState()
        setContent {
            NetworkHelmTheme {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), state) { items(60) { Box(Modifier.fillMaxWidth().height(if (it < 10) 20.dp else 60.dp)) } }
                    ListScrollBar(state, Modifier.align(Alignment.CenterEnd))
                }
            }
        }
        onNodeWithTag("list-scroll-thumb").performMouseInput {
            moveTo(center)
            press()
        }
        onNodeWithTag("list-scroll-bar").performMouseInput { moveBy(Offset(0f, 10f)) }
        waitForIdle()
        val first = state.firstVisibleItemIndex
        repeat(4) { onNodeWithTag("list-scroll-bar").performMouseInput { moveBy(Offset(0f, 10f)) } }
        onNodeWithTag("list-scroll-bar").performMouseInput { release() }
        waitForIdle()
        assertTrue(first > 0, "the first step moved the rows to $first")
        assertTrue(state.firstVisibleItemIndex > first, "the rows stopped at ${state.firstVisibleItemIndex} after the step that brought them to $first")
    }

    @Test
    fun theThumbOfALongListComposesOnlyTheRowsItBringsIntoSight() = runDesktopComposeUiTest(300, 200) {
        val state = LazyListState()
        var composed = 0
        setContent {
            NetworkHelmTheme {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), state) {
                        items(40_000) {
                            SideEffect { composed++ }
                            Box(Modifier.fillMaxWidth().height(20.dp))
                        }
                    }
                    ListScrollBar(state, Modifier.align(Alignment.CenterEnd))
                }
            }
        }
        onNodeWithTag("list-scroll-thumb").performMouseInput {
            moveTo(center)
            press()
        }
        waitForIdle()
        composed = 0
        repeat(4) { onNodeWithTag("list-scroll-bar").performMouseInput { moveBy(Offset(0f, 20f)) } }
        onNodeWithTag("list-scroll-bar").performMouseInput { release() }
        waitForIdle()
        val thumb = onNodeWithTag("list-scroll-thumb").fetchSemanticsNode().positionInRoot.y
        assertTrue(state.firstVisibleItemIndex > 15_000, "the drag brought the rows to ${state.firstVisibleItemIndex}")
        assertTrue(thumb in 70f..90f, "the thumb stands at $thumb after a drag of 80")
        assertTrue(composed < 100, "the drag composed $composed rows")
    }
}
