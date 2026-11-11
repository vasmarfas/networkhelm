package com.vasmarfas.networkhelm.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.UnstyledDropdownMenu
import com.composeunstyled.UnstyledIcon
import com.vasmarfas.networkhelm.routeros.schema.label
import com.vasmarfas.networkhelm.ui.kit.ListScrollBar
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.MenuPanel
import com.vasmarfas.networkhelm.ui.kit.arrow
import com.vasmarfas.networkhelm.ui.table.RowTone
import com.vasmarfas.networkhelm.ui.table.TableState
import com.vasmarfas.networkhelm.ui.table.marked
import com.vasmarfas.networkhelm.ui.table.select
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.theme.tintOf
import com.vasmarfas.networkhelm.ui.workspace.resizeIcon
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

private val INDENT = 16.dp
private val CHEVRON = 18.dp
private val SIZE_WIDTH = 96.dp
private val MODIFIED_WIDTH = 150.dp
private val TYPE_WIDTH = 130.dp

@Composable
internal fun TreeTable(
    rows: List<TreeRow>,
    state: TableState<String>,
    highlight: String,
    loading: Set<String>,
    dropEnabled: Boolean,
    held: String?,
    onHold: (String, Boolean) -> Unit,
    onToggle: (TreeRow) -> Unit,
    onOpen: (TreeRow) -> Unit,
    onKey: (KeyEvent) -> Boolean,
    onDropOnto: (TreeRow, List<LocalFile>) -> Unit,
    dragOut: (() -> DragAndDropTransferData?)?,
    menu: (@Composable (close: () -> Unit) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val palette = Theme.palette
    val metrics = Theme.metrics
    val compact = LocalCompact.current
    val density = LocalDensity.current
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val currentRows by rememberUpdatedState(rows)
    val order = remember(rows) { rows.map { it.entry.path } }
    val currentOrder by rememberUpdatedState(order)
    val toggle by rememberUpdatedState(onToggle)
    val open by rememberUpdatedState(onOpen)
    val withMenu by rememberUpdatedState(menu != null)
    val phone by rememberUpdatedState(compact)
    val rowHeight = if (compact) 40.dp else metrics.row + 2.dp
    val mark = SpanStyle(background = palette.warning.copy(alpha = 0.35f))
    fun choose(selection: Set<String>, anchor: String?) {
        state.selected = selection
        state.anchor = anchor
    }
    LaunchedEffect(order) {
        val present = order.toSet()
        if (!present.containsAll(state.selected)) choose(state.selected.filterTo(LinkedHashSet()) { it in present }, state.anchor?.takeIf { it in present })
    }
    LaunchedEffect(state.anchor) {
        val at = order.indexOf(state.anchor).takeIf { it >= 0 } ?: return@LaunchedEffect
        val shown = list.layoutInfo.visibleItemsInfo
        val first = shown.firstOrNull()?.index ?: return@LaunchedEffect
        val last = shown.lastOrNull()?.index ?: return@LaunchedEffect
        if (at <= first) list.scrollToItem(at) else if (at >= last) list.scrollToItem((at - (last - first) + 1).coerceAtLeast(0))
    }
    val top = with(density) { metrics.row.roundToPx() }
    BoxWithConstraints(modifier.background(palette.surface)) {
        val sizeWidth = state.widths["size"] ?: SIZE_WIDTH
        val modifiedWidth = if (compact) 0.dp else state.widths["last-modified"] ?: MODIFIED_WIDTH
        val typeWidth = if (compact) 0.dp else state.widths["type"] ?: TYPE_WIDTH
        val nameWidth = state.widths["name"] ?: (maxWidth - sizeWidth - modifiedWidth - typeWidth).coerceAtLeast(160.dp)
        Column(
            Modifier.fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val ctrl = event.isCtrlPressed && !event.isAltPressed || event.isMetaPressed
                    when {
                        ctrl && event.key == Key.A -> choose(order.toSet(), order.firstOrNull())
                        event.key == Key.DirectionDown || event.key == Key.DirectionUp -> {
                            val at = order.indexOf(state.anchor) + if (event.key == Key.DirectionDown) 1 else -1
                            order.getOrNull(at.coerceIn(0, (order.size - 1).coerceAtLeast(0)))?.let { choose(setOf(it), it) }
                        }
                        else -> return@onPreviewKeyEvent onKey(event)
                    }
                    true
                }
                .focusRequester(focus)
                .focusable()
                .testTag("tree"),
        ) {
            Row(Modifier.fillMaxWidth().height(metrics.row).background(palette.raised).drawBehind {
                drawLine(palette.line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
            }, verticalAlignment = Alignment.CenterVertically) {
                HeaderCell("name", label("name"), nameWidth, state)
                HeaderCell("size", label("size"), sizeWidth, state)
                if (!compact) {
                    HeaderCell("last-modified", label("last-modified"), modifiedWidth, state)
                    HeaderCell("type", label("type"), typeWidth, state)
                }
            }
            LazyColumn(
                Modifier.fillMaxSize()
                    .arrow()
                    .then(if (dragOut != null) Modifier.dragAndDropSource({}) { _: Offset -> dragOut() } else Modifier)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type != PointerEventType.Press || !event.buttons.isSecondaryPressed || !withMenu) continue
                                val press = event.changes.firstOrNull() ?: continue
                                val y = press.position.y.roundToInt()
                                val index = list.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.index
                                val id = index?.let(currentOrder::getOrNull) ?: continue
                                press.consume()
                                focus.requestFocus()
                                if (id !in state.selected) choose(setOf(id), id)
                                menuAt = press.position
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        var lastPress = 0L
                        var lastRow: String? = null
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val touch = down.type == PointerType.Touch
                            if (!touch && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                            val y = down.position.y.roundToInt()
                            val index = list.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.index
                            val row = index?.let(currentRows::getOrNull) ?: return@awaitEachGesture
                            val id = row.entry.path
                            val arrow = INDENT.toPx() * row.depth + 4.dp.toPx()
                            if (row.entry.directory && down.position.x >= arrow && down.position.x < arrow + CHEVRON.toPx()) {
                                down.consume()
                                focus.requestFocus()
                                toggle(row)
                                return@awaitEachGesture
                            }
                            if (touch) {
                                var scrolled = false
                                val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    waitForUpOrCancellation().also { if (it == null) scrolled = true }
                                }
                                when {
                                    up != null -> {
                                        up.consume()
                                        focus.requestFocus()
                                        val again = id == lastRow && up.uptimeMillis - lastPress < 400
                                        lastPress = up.uptimeMillis
                                        lastRow = id
                                        choose(setOf(id), id)
                                        if (phone || again) open(row)
                                    }
                                    !scrolled -> {
                                        choose(if (id in state.selected) state.selected - id else state.selected + id, id)
                                        waitForUpOrCancellation()?.consume()
                                    }
                                }
                                return@awaitEachGesture
                            }
                            down.consume()
                            focus.requestFocus()
                            val again = id == lastRow && down.uptimeMillis - lastPress < 400
                            lastPress = down.uptimeMillis
                            lastRow = id
                            if (again) {
                                open(row)
                                return@awaitEachGesture
                            }
                            val modifiers = currentEvent.keyboardModifiers
                            val ctrl = modifiers.isCtrlPressed || modifiers.isMetaPressed
                            val shift = modifiers.isShiftPressed
                            val keep = !ctrl && !shift && id in state.selected && state.selected.size > 1
                            if (!keep) select(state.selected, state.anchor, id, currentOrder, ctrl, shift).let { (selection, anchor) -> choose(selection, anchor) }
                            var dragged = false
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                if (abs(change.position.y - down.position.y) >= viewConfiguration.touchSlop) dragged = true
                            }
                            if (keep && !dragged) choose(setOf(id), id)
                        }
                    },
                state = list,
            ) {
                itemsIndexed(rows, key = { _, row -> row.entry.path }) { _, row ->
                    val entry = row.entry
                    val selected = entry.path in state.selected
                    val tone = if (entry.name.startsWith(".")) RowTone.Faded else RowTone.Normal
                    val color = if (tone == RowTone.Faded) palette.faint else palette.text
                    val background = when {
                        held == entry.path -> palette.accent.copy(alpha = 0.22f)
                        selected -> palette.selection
                        else -> palette.surface
                    }
                    Row(
                        Modifier.fillMaxWidth().height(rowHeight).testTag("row-${entry.path}").background(background)
                            .then(
                                if (entry.directory && dropEnabled) {
                                    Modifier.fileDrop({ onDropOnto(row, it) }) { entered -> onHold(entry.path, entered) }
                                } else {
                                    Modifier
                                },
                            )
                            .drawBehind { drawLine(palette.line.copy(alpha = 0.4f), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(Modifier.width(nameWidth).padding(start = 4.dp + INDENT * row.depth), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(CHEVRON, rowHeight).testTag("toggle-${entry.path}"), contentAlignment = Alignment.Center) {
                                if (entry.directory) UnstyledIcon(if (row.open) Lucide.ChevronDown else Lucide.ChevronRight, null, Modifier.size(14.dp), tint = palette.muted)
                            }
                            val icon = entry.kind.icon(row.open)
                            UnstyledIcon(icon.vector, null, Modifier.padding(end = 6.dp).size(metrics.icon), tint = palette.tintOf(icon)?.takeIf { color == palette.text } ?: color)
                            Text(marked(entry.name, highlight, mark), Modifier.weight(1f, fill = false), color = color, singleLine = true, overflow = TextOverflow.Ellipsis)
                            when {
                                entry.path in loading -> Text("…", Modifier.padding(start = 6.dp), color = palette.muted)
                                row.count != null && row.count > 0 -> Text("${row.count}", Modifier.padding(start = 6.dp), style = Theme.type.small, color = palette.faint)
                            }
                        }
                        Cell(if (entry.directory) sizeText(row.size?.takeIf { it > 0 }) else sizeText(entry.size), sizeWidth, if (entry.directory) palette.muted else color)
                        if (!compact) {
                            Cell(entry.modified, modifiedWidth, color)
                            Cell(entry.type, typeWidth, if (color == palette.text) palette.muted else color)
                        }
                    }
                }
            }
        }
        ListScrollBar(list, Modifier.align(Alignment.CenterEnd).padding(top = metrics.row))
        val at = menuAt
        if (at != null && menu != null) {
            Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt() + top) }) {
                UnstyledDropdownMenu(
                    expanded = true,
                    onExpandedChange = { if (!it) menuAt = null },
                    panel = { MenuPanel { menu { menuAt = null } } },
                    anchor = { Box(Modifier.size(1.dp)) },
                )
            }
        }
    }
}

@Composable
private fun Cell(text: String, width: Dp, color: Color) {
    Text(text, Modifier.width(width).padding(horizontal = 6.dp), color = color, singleLine = true, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun HeaderCell(key: String, title: String, width: Dp, state: TableState<String>) {
    val palette = Theme.palette
    val density = LocalDensity.current
    val position = state.sort.indexOfFirst { it.column == key }
    val mark = when {
        position < 0 -> ""
        state.sort[position].ascending -> " ▲"
        else -> " ▼"
    }
    Box(Modifier.width(width).fillMaxHeight()) {
        Text(
            title + mark,
            Modifier.fillMaxSize().pointerInput(key) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val up = waitForUpOrCancellation()
                    if (up != null) state.toggle(key, false)
                    down.consume()
                }
            }.padding(horizontal = 6.dp),
            color = palette.muted,
            singleLine = true,
            overflow = TextOverflow.Ellipsis,
        )
        val current by rememberUpdatedState(width)
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(8.dp)
                .fillMaxHeight()
                .pointerHoverIcon(resizeIcon(1, 0))
                .testTag("resize-$key")
                .pointerInput(key) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val start = current
                        var moved = 0f
                        horizontalDrag(down.id) { change ->
                            moved += change.positionChange().x
                            change.consume()
                            state.widths[key] = (start + with(density) { moved.toDp() }).coerceAtLeast(40.dp)
                        }
                    }
                },
        )
    }
}
