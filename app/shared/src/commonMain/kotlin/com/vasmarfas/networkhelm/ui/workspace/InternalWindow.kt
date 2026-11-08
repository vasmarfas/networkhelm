package com.vasmarfas.networkhelm.ui.workspace

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.Minimize2
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.vasmarfas.networkhelm.ui.kit.hand
import com.vasmarfas.networkhelm.ui.theme.Theme

private val ResizeGrip = 6.dp

@Composable
internal fun InternalWindow(
    window: WindowState,
    active: Boolean,
    viewport: DpSize,
    scroll: () -> Pair<Int, Int>,
    onFocus: () -> Unit,
    onMinimize: () -> Unit,
    onDetach: (() -> Unit)?,
    onClose: () -> Unit,
    inTitle: (@Composable (WindowState, Color) -> Unit)?,
    content: @Composable (WindowState) -> Unit,
) {
    val density = LocalDensity.current
    val palette = Theme.palette
    val material = Theme.material
    val radius = if (material) 16.dp else Theme.metrics.radius
    val inactiveTitle = palette.inactiveTitle
    val titleColor = if ((!material || palette.title == null) && (active || inactiveTitle != null)) palette.onAccent else palette.text
    val size = if (window.maximized) viewport else window.size
    Box(
        Modifier
            .offset {
                if (window.maximized) {
                    val (x, y) = scroll()
                    IntOffset(x, y)
                } else {
                    IntOffset(window.position.x.roundToPx(), window.position.y.roundToPx())
                }
            }
            .size(size)
            .shadow(if (active) 8.dp else 2.dp, RoundedCornerShape(radius))
            .background(if (Theme.classic) palette.raised else palette.surface, RoundedCornerShape(radius))
            .then(if (material) Modifier else Modifier.border(1.dp, palette.frame ?: if (active && palette.title == null) palette.accent else palette.line, RoundedCornerShape(radius)))
            .pointerInput(window) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    onFocus()
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(Theme.metrics.title)
                    .background(if (active) palette.title ?: palette.accent else inactiveTitle ?: palette.raised, RoundedCornerShape(topStart = radius, topEnd = radius))
                    .pointerInput(window) {
                        detectTapGestures(onDoubleTap = { window.maximized = !window.maximized })
                    }
                    .pointerInput(window) {
                        detectDragGestures { change, drag ->
                            if (window.maximized) return@detectDragGestures
                            change.consume()
                            val moved = window.position + with(density) { DpOffset(drag.x.toDp(), drag.y.toDp()) }
                            window.position = DpOffset(moved.x.coerceAtLeast(0.dp), moved.y.coerceAtLeast(0.dp))
                        }
                    }
                    .padding(start = if (material) 16.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val titleWidth = if (inTitle == null) Modifier.weight(1f) else Modifier.padding(end = 8.dp).widthIn(max = size.width / 3)
                val titleStyle = when {
                    Theme.classic -> Theme.type.body
                    material -> Theme.type.strong.copy(fontSize = 16.sp, lineHeight = 24.sp)
                    else -> Theme.type.strong
                }
                DisableSelection { Text(window.title, titleWidth, style = titleStyle, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                inTitle?.let { Row(Modifier.weight(1f).fillMaxHeight()) { DisableSelection { it(window, titleColor) } } }
                onDetach?.let { TitleButton(Lucide.ExternalLink, titleColor, it) }
                TitleButton(Lucide.Minus, titleColor, onMinimize)
                TitleButton(if (window.maximized) Lucide.Minimize2 else Lucide.Maximize2, titleColor) { window.maximized = !window.maximized }
                TitleButton(Lucide.X, titleColor, onClose)
            }
            Box(Modifier.fillMaxWidth().weight(1f)) { SelectionContainer { content(window) } }
        }
        if (!window.maximized) {
            ResizeHandle(Modifier.align(Alignment.CenterStart).width(ResizeGrip).fillMaxHeight(), window, x = -1, y = 0)
            ResizeHandle(Modifier.align(Alignment.CenterEnd).width(ResizeGrip).fillMaxHeight(), window, x = 1, y = 0)
            ResizeHandle(Modifier.align(Alignment.BottomCenter).height(ResizeGrip).fillMaxWidth(), window, x = 0, y = 1)
            ResizeHandle(Modifier.align(Alignment.BottomStart).size(ResizeGrip * 2), window, x = -1, y = 1)
            ResizeHandle(Modifier.align(Alignment.BottomEnd).size(ResizeGrip * 2), window, x = 1, y = 1)
        }
    }
}

internal expect fun resizeIcon(x: Int, y: Int): PointerIcon

@Composable
private fun TitleButton(icon: ImageVector, tint: Color, onClick: () -> Unit) {
    UnstyledButton(onClick = onClick, indication = LocalIndication.current, modifier = Modifier.widthIn(min = 28.dp).fillMaxHeight().hand()) {
        UnstyledIcon(icon, null, Modifier.size(Theme.metrics.icon), tint = tint)
    }
}

@Composable
private fun ResizeHandle(modifier: Modifier, window: WindowState, x: Int, y: Int) {
    val density = LocalDensity.current
    Spacer(
        modifier.pointerHoverIcon(resizeIcon(x, y)).pointerInput(window) {
            detectDragGestures { change, drag ->
                change.consume()
                with(density) { window.resize(x, y, drag.x.toDp(), drag.y.toDp()) }
            }
        },
    )
}
