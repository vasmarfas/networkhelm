package com.vasmarfas.networkhelm.ui.kit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composeunstyled.UnstyledIcon
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun HorizontalScrollBar(state: ScrollState, viewport: Int, modifier: Modifier = Modifier) {
    val span = state.maxValue
    // maxValue is Int.MAX_VALUE until the content is measured
    if (span <= 0 || span == Int.MAX_VALUE || viewport <= 0) return
    val palette = Theme.palette
    val classic = Theme.classic
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val step = with(density) { 96.dp.toPx() }
    val thickness = if (classic) 17.dp else 14.dp
    var track by remember { mutableIntStateOf(0) }
    val thumb = max(with(density) { 24.dp.toPx() }.roundToInt(), (track.toLong() * viewport / (viewport + span)).toInt()).coerceAtMost(track)
    val travel = track - thumb
    val start = if (travel > 0) (state.value.toLong() * travel / span).toInt() else 0
    val thumbAt by rememberUpdatedState(start to thumb)
    val page by rememberUpdatedState(viewport * 0.9f)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var dragged by remember { mutableStateOf(false) }

    @Composable
    fun Arrow(icon: ImageVector, direction: Int, tag: String) {
        Box(
            Modifier.width(thickness).fillMaxHeight().testTag(tag)
                .then(if (classic) Modifier.background(palette.raised).bevel(palette) else Modifier)
                .hand()
                .pointerInput(state) {
                    awaitEachGesture {
                        awaitFirstDown()
                        scope.launch { state.animateScrollBy(direction * step) }
                        val held = scope.launch {
                            delay(350)
                            while (true) {
                                state.scrollBy(direction * step / 5)
                                delay(30)
                            }
                        }
                        waitForUpOrCancellation()
                        held.cancel()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            UnstyledIcon(icon, null, Modifier.size(if (classic) 9.dp else 10.dp), tint = palette.muted)
        }
    }

    Row(
        modifier.fillMaxWidth().height(thickness).testTag("scroll-bar")
            .background(if (classic) palette.raised else palette.surface)
            .drawBehind { drawLine(palette.line, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Arrow(Lucide.ChevronLeft, -1, "scroll-left")
        Box(
            Modifier.weight(1f).fillMaxHeight().onSizeChanged { track = it.width }
                .pointerInput(state) {
                    detectTapGestures { press ->
                        val (from, width) = thumbAt
                        if (press.x < from || press.x > from + width) {
                            scope.launch { state.animateScrollBy(if (press.x < from) -page else page) }
                        }
                    }
                },
        ) {
            Box(
                Modifier.offset { IntOffset(start, 0) }.width(with(density) { thumb.toDp() }).fillMaxHeight().testTag("scroll-thumb")
                    .padding(vertical = if (classic) 0.dp else 3.dp)
                    .background(
                        if (classic) palette.raised else if (hovered || dragged) palette.faint else palette.line,
                        RoundedCornerShape(Theme.metrics.radius),
                    )
                    .then(if (classic) Modifier.bevel(palette) else Modifier)
                    .hoverable(source)
                    .hand()
                    .pointerInput(state, span, travel) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragged = true },
                            onDragEnd = { dragged = false },
                            onDragCancel = { dragged = false },
                        ) { change, amount ->
                            change.consume()
                            if (travel > 0) state.dispatchRawDelta(amount * span / travel)
                        }
                    },
            )
        }
        Arrow(Lucide.ChevronRight, 1, "scroll-right")
    }
}

@Composable
fun ListScrollBar(state: LazyListState, modifier: Modifier = Modifier) {
    val info = state.layoutInfo
    val shown = info.visibleItemsInfo
    if (shown.isEmpty()) return
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val average = shown.sumOf { it.size }.toFloat() / shown.size
    val content = average * info.totalItemsCount
    if (viewport <= 0f || content <= viewport + 1f) return
    val palette = Theme.palette
    val classic = Theme.classic
    val density = LocalDensity.current
    var track by remember { mutableIntStateOf(0) }
    val thumb = max(with(density) { 24.dp.toPx() }.roundToInt(), (track * viewport / content).roundToInt()).coerceAtMost(track)
    val travel = track - thumb
    val position = shown.first().let { it.index * average - it.offset }
    val start = if (travel > 0) (travel * position / (content - viewport)).coerceIn(0f, travel.toFloat()).roundToInt() else 0
    // the content is an estimate from the rows in sight and changes as they scroll
    val perPixel by rememberUpdatedState(if (travel > 0) (content - viewport) / travel else 0f)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var dragged by remember { mutableStateOf(false) }
    Box(modifier.width(if (classic) 17.dp else 10.dp).fillMaxHeight().onSizeChanged { track = it.height }.testTag("list-scroll-bar")) {
        Box(
            Modifier.offset { IntOffset(0, start) }.height(with(density) { thumb.toDp() }).fillMaxWidth().testTag("list-scroll-thumb")
                .padding(horizontal = if (classic) 0.dp else 2.dp)
                .background(
                    if (classic) palette.raised else if (hovered || dragged) palette.muted else palette.faint,
                    RoundedCornerShape(Theme.metrics.radius),
                )
                .then(if (classic) Modifier.bevel(palette) else Modifier)
                .hoverable(source)
                .hand()
                .pointerInput(state) {
                    detectVerticalDragGestures(
                        onDragStart = { dragged = true },
                        onDragEnd = { dragged = false },
                        onDragCancel = { dragged = false },
                    ) { change, amount ->
                        change.consume()
                        state.dispatchRawDelta(amount * perPixel)
                    }
                },
        )
    }
}
