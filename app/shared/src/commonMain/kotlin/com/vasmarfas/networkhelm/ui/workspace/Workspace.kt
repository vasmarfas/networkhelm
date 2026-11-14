package com.vasmarfas.networkhelm.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.ui.kit.hand
import com.vasmarfas.networkhelm.ui.theme.Theme

@Composable
fun Workspace(
    state: WorkspaceState,
    modifier: Modifier = Modifier,
    onDetach: ((WindowState) -> Unit)? = null,
    detachable: (WindowState) -> Boolean = { true },
    inTitle: (@Composable (WindowState, Color) -> Unit)? = null,
    content: @Composable (WindowState) -> Unit,
) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val focus = remember { FocusRequester() }
    Column(
        modifier
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val active = state.active
                // AltGr comes as Ctrl with Alt, and with it the key is a character of the layout
                val alt = event.isAltPressed && !event.isCtrlPressed
                when {
                    alt && event.key == Key.S -> state.switchToPrevious()
                    alt && event.key == Key.A -> state.raiseBottom()
                    active != null && ((event.isCtrlPressed && event.key == Key.F4) || (alt && event.key == Key.W)) -> state.close(active)
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            // Escape goes first to what has the keyboard: a list with a chosen line, an open find bar
            .onKeyEvent { event ->
                val active = state.active
                if (event.type != KeyEventType.KeyDown || event.key != Key.Escape || active == null) return@onKeyEvent false
                state.close(active)
                true
            },
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewport = DpSize(maxWidth, maxHeight)
            SideEffect { state.viewport = viewport }
            val extent = state.windows
                .filter { !it.minimized && !it.maximized }
                .fold(viewport) { acc, w -> DpSize(max(acc.width, w.position.x + w.size.width), max(acc.height, w.position.y + w.size.height)) }
            Box(Modifier.fillMaxSize().horizontalScroll(horizontal).verticalScroll(vertical)) {
                // clickable would merge the semantics of every window, detectTapGestures takes the touch a list in a window waits on
                Box(
                    Modifier.size(extent).pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            if (waitForUpOrCancellation() != null) focus.requestFocus()
                        }
                    },
                ) {
                    for (window in state.windows) {
                        key(window.id) {
                            if (!window.minimized) {
                                InternalWindow(
                                    window = window,
                                    active = state.active === window,
                                    viewport = viewport,
                                    scroll = { horizontal.value to vertical.value },
                                    onFocus = {
                                        state.bringToFront(window)
                                        focus.requestFocus()
                                    },
                                    onMinimize = { state.minimize(window) },
                                    onDetach = onDetach?.takeIf { detachable(window) }?.let { detach -> { detach(window) } },
                                    onClose = { state.close(window) },
                                    inTitle = inTitle,
                                    content = content,
                                )
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(vertical, Modifier.align(Alignment.CenterEnd))
            HorizontalScrollbar(horizontal, Modifier.align(Alignment.BottomCenter))
        }
        val minimized = state.windows.filter { it.minimized }
        if (minimized.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(Theme.palette.raised).padding(2.dp)) {
                for (window in minimized) {
                    key(window.id) {
                        Text(
                            window.title,
                            Modifier
                                .padding(horizontal = 2.dp)
                                .background(Theme.palette.surface)
                                .clickable { state.bringToFront(window) }
                                .hand()
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            style = Theme.type.small,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
