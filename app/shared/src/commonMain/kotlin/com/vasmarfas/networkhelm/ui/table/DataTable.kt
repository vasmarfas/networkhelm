package com.vasmarfas.networkhelm.ui.table

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.UnstyledDropdownMenu
import com.composeunstyled.UnstyledIcon
import com.vasmarfas.networkhelm.ui.kit.HorizontalScrollBar
import com.vasmarfas.networkhelm.ui.kit.ListScrollBar
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.MenuPanel
import com.vasmarfas.networkhelm.ui.kit.arrow
import com.vasmarfas.networkhelm.ui.kit.hand
import com.vasmarfas.networkhelm.ui.theme.Palette
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.theme.ThemeIcon
import com.vasmarfas.networkhelm.ui.theme.lit
import com.vasmarfas.networkhelm.ui.theme.tintOf
import com.vasmarfas.networkhelm.ui.workspace.resizeIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

class TableColumn<T>(
    val key: String,
    val title: String,
    val width: Dp?,
    val shown: (String) -> String = { it },
    val widest: String? = null,
    val icon: (T, String) -> ThemeIcon? = { _, _ -> null },
    val value: (T) -> String,
)

class SortKey(val column: String, val ascending: Boolean)

enum class RowTone { Normal, Faded }

@Stable
class TableState<K> {
    var sort by mutableStateOf<List<SortKey>>(emptyList())
    var selected by mutableStateOf<Set<K>>(emptySet())
    var anchor by mutableStateOf<K?>(null)
    val widths = mutableStateMapOf<String, Dp>()

    fun toggle(column: String, add: Boolean) {
        val next = SortKey(column, sort.firstOrNull { it.column == column }?.ascending != true)
        val others = if (add) sort.filterNot { it.column == column } else emptyList()
        val at = sort.indexOfFirst { it.column == column }.takeIf { add && it >= 0 } ?: others.size
        sort = others.toMutableList().also { it.add(at.coerceAtMost(it.size), next) }
    }
}

@Composable
fun <T, K : Any> DataTable(
    rows: List<T>,
    columns: List<TableColumn<T>>,
    key: (T) -> K,
    modifier: Modifier = Modifier,
    state: TableState<K> = remember { TableState() },
    onOpen: (T) -> Unit = {},
    onOpenColumn: ((T, String) -> Unit)? = null,
    onSelection: (Set<K>) -> Unit = {},
    caption: (T) -> String? = { null },
    flags: ((T) -> String)? = null,
    tone: (T) -> RowTone = { RowTone.Normal },
    highlight: String = "",
    follow: Boolean = false,
    detail: Boolean = false,
    category: ((T) -> String)? = null,
    onMove: ((List<K>, K?) -> Unit)? = null,
    onMoveColumn: ((String, Int) -> Unit)? = null,
    onKey: (KeyEvent) -> Boolean = { false },
    picker: (@Composable () -> Unit)? = null,
    dragOut: (() -> DragAndDropTransferData?)? = null,
    separators: Boolean = false,
    numbers: ((T) -> String)? = null,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
    onCell: (K, String?) -> Unit = { _, _ -> },
    focus: FocusRequester = remember { FocusRequester() },
) {
    val palette = Theme.palette
    val metrics = Theme.metrics
    val grid = palette.grid ?: palette.line
    val divider = if (separators) Modifier.drawBehind { drawLine(grid, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f) } else Modifier
    val currentColumns by rememberUpdatedState(columns)
    val currentCategory by rememberUpdatedState(category)
    val sort = state.sort
    val ordered by produceState(rows, rows, sort, columns.map { it.key }, category != null) {
        value = withContext(Dispatchers.Default) {
            val group = currentCategory
            val sorted = sorted(rows, currentColumns, sort)
            if (group == null) sorted else sorted.sortedWith(compareBy(naturalOrder) { group(it) })
        }
    }
    val shown = if (sort.isEmpty() && category == null) rows else ordered
    val order = remember(shown) { shown.map(key) }
    val measurer = rememberTextMeasurer()
    val body = Theme.type.body
    val density = LocalDensity.current
    val fitted = remember(columns.map { it.key }, rows.isEmpty()) {
        val sample = rows.take(100)
        columns.filter { it.width == null }.associate { column ->
            val values = sample.map { column.value(it) }
            val longest = values.map(column.shown).maxByOrNull { it.length }.orEmpty()
            val px = listOfNotNull(column.title + " ▲", longest, column.widest).maxOf { measurer.measure(it, body, maxLines = 1, softWrap = false).size.width }
            val icon = if (sample.any { column.icon(it, column.value(it)) != null }) metrics.icon + 4.dp else 0.dp
            column.key to (with(density) { px.toDp() } + 16.dp + icon).coerceIn(56.dp, 360.dp)
        }
    }
    fun width(column: TableColumn<T>) = state.widths[column.key] ?: column.width ?: fitted.getValue(column.key)
    val notify by rememberUpdatedState(onSelection)
    fun choose(selection: Set<K>, anchor: K?) {
        state.selected = selection
        state.anchor = anchor
        notify(selection)
    }
    LaunchedEffect(order) {
        if (state.selected.isEmpty()) return@LaunchedEffect
        val present = order.toSet()
        if (!present.containsAll(state.selected)) choose(state.selected.filterTo(LinkedHashSet()) { it in present }, state.anchor?.takeIf { it in present })
    }
    fun dropColumn(index: Int, shift: Dp) {
        var at = index
        var rest = shift
        while (at < columns.lastIndex && rest > width(columns[at + 1]) / 2) {
            at++
            rest -= width(columns[at])
        }
        while (at > 0 && -rest > width(columns[at - 1]) / 2) {
            at--
            rest += width(columns[at])
        }
        if (at != index) onMoveColumn?.invoke(columns[index].key, at)
    }
    val flagWidth = 38.dp
    val numberWidth = if (onMove != null) 52.dp else 40.dp
    val lead = (if (flags != null) flagWidth else 0.dp) + if (numbers != null) numberWidth else 0.dp
    val total = columns.fold(lead) { sum, column -> sum + width(column) } + 28.dp
    var room by remember { mutableStateOf(0.dp) }
    val mark = SpanStyle(background = palette.warning.copy(alpha = 0.35f))
    val list = rememberLazyListState()
    var dragY by remember { mutableStateOf<Float?>(null) }
    val dropAt by remember { derivedStateOf { dragY?.let { gap(list, it) } } }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val withMenu by rememberUpdatedState(menu != null)
    val pointed by rememberUpdatedState(onCell)
    val columnAt by rememberUpdatedState { x: Float ->
        val ends = columns.runningFold(lead) { end, column -> end + width(column) }.drop(1)
        if (detail) null else columns.getOrNull(ends.indexOfFirst { with(density) { it.toPx() } > x })?.key
    }
    val rowPx = with(density) { metrics.row.toPx() }
    LaunchedEffect(list, rowPx) {
        snapshotFlow { dragY }.collectLatest { y ->
            val band = rowPx * 1.5f
            val height = list.layoutInfo.viewportSize.height
            val depth = when {
                y == null -> return@collectLatest
                y < band -> y - band
                y > height - band -> y - (height - band)
                else -> return@collectLatest
            }
            val step = (depth / band).coerceIn(-2f, 2f) * rowPx / 4
            while (list.scrollBy(step) != 0f) withFrameNanos {}
        }
    }
    var seen by remember { mutableIntStateOf(0) }
    LaunchedEffect(shown.size) {
        val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (follow && shown.isNotEmpty() && last >= seen - 2) list.scrollToItem(shown.lastIndex)
        seen = shown.size
    }
    val currentRows by rememberUpdatedState(shown)
    val currentOrder by rememberUpdatedState(order)
    val move by rememberUpdatedState(onMove)
    val open by rememberUpdatedState(onOpen)
    val openColumn by rememberUpdatedState(onOpenColumn)
    fun openRow(row: T, cells: Boolean, x: Float) {
        val take = openColumn
        val column = if (take != null && cells && !detail) columnAt(x) else null
        if (take != null && column != null) take(row, column) else open(row)
    }
    val phone by rememberUpdatedState(LocalCompact.current)
    val sideways = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    fun step(to: Int) = order.getOrNull(to.coerceIn(0, (order.size - 1).coerceAtLeast(0)))?.let { choose(setOf(it), it) }
    fun page() = (list.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
    LaunchedEffect(state.anchor) {
        val at = order.indexOf(state.anchor)
        val visible = list.layoutInfo.visibleItemsInfo
        val top = visible.firstOrNull()?.index ?: return@LaunchedEffect
        val bottom = visible.lastOrNull()?.index ?: return@LaunchedEffect
        if (at in 0 until top) list.scrollToItem(at)
        if (at >= bottom) list.scrollToItem((at - (bottom - top) + 1).coerceAtLeast(0))
    }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(
                Modifier.fillMaxSize()
                    .background(palette.surface)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        // AltGr comes as Ctrl with Alt, and the letter it types is no shortcut
                        val ctrl = event.isCtrlPressed && !event.isAltPressed || event.isMetaPressed
                        when {
                            ctrl && event.key == Key.A -> choose(order.toSet(), order.firstOrNull())
                            event.key == Key.DirectionDown -> step(order.indexOf(state.anchor) + 1)
                            event.key == Key.DirectionUp -> step(order.indexOf(state.anchor) - 1)
                            event.key == Key.PageDown -> step(order.indexOf(state.anchor) + page())
                            event.key == Key.PageUp -> step(order.indexOf(state.anchor) - page())
                            event.key == Key.MoveHome -> step(0)
                            event.key == Key.MoveEnd -> step(order.lastIndex)
                            event.key == Key.Spacebar -> state.anchor?.let { anchor -> choose(if (anchor in state.selected) state.selected - anchor else state.selected + anchor, anchor) }
                            event.key == Key.Enter -> shown.firstOrNull { key(it) == state.anchor }?.let(onOpen)
                            else -> return@onPreviewKeyEvent onKey(event)
                        }
                        true
                    }
                    .focusRequester(focus)
                    .focusable()
                    .testTag("table")
                    .onSizeChanged {
                        room = with(density) { it.width.toDp() }
                        viewport = it.width
                    }
                    .then(if (detail) Modifier else Modifier.horizontalScroll(sideways)),
            ) {
                Column(if (detail) Modifier.fillMaxWidth() else Modifier.width(maxOf(room, total))) {
                    if (!detail) {
                        val classic = Theme.classic
                        val lines = Modifier.drawWithContent {
                            drawContent()
                            drawLine(palette.line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
                            if (!classic) drawLine(palette.line, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f)
                        }
                        val headerDivider = when {
                            classic -> Modifier.sunkenEnd(palette)
                            separators -> Modifier.drawBehind { drawLine(palette.line, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f) }
                            else -> Modifier
                        }
                        Row(Modifier.fillMaxWidth().height(metrics.row).background(palette.raised).then(lines), verticalAlignment = Alignment.CenterVertically) {
                            if (numbers != null) {
                                Box(
                                    Modifier.width(numberWidth).fillMaxHeight().then(headerDivider).clickable { state.sort = emptyList() }.hand().padding(horizontal = 6.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Text(if (sort.isEmpty()) "# ▲" else "#", color = palette.muted, singleLine = true)
                                }
                            }
                            if (flags != null) {
                                Box(Modifier.width(flagWidth).fillMaxHeight().then(headerDivider), contentAlignment = Alignment.Center) {
                                    if (!classic) UnstyledIcon(Lucide.Flag, null, Modifier.size(12.dp), tint = palette.muted)
                                }
                            }
                            columns.forEachIndexed { index, column ->
                                key(column.key) {
                                    DisableSelection { Header(column, width(column), state, separators, { state.widths[column.key] = it }, onMoveColumn?.let { { shift -> dropColumn(index, shift) } }) }
                                }
                            }
                        }
                    }
                    LazyColumn(
                        // empty drag picture: the default one draws the list through a recording that leaves the rows out
                        Modifier.fillMaxSize().arrow().then(if (dragOut != null && onMove == null) Modifier.dragAndDropSource({}) { _: Offset -> dragOut() } else Modifier).pointerInput(Unit) {
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
                                    pointed(id, columnAt(press.position.x))
                                    menuAt = press.position
                                }
                            }
                        }.pointerInput(Unit) {
                            var lastPress = 0L
                            var lastRow: Any? = null
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                val touch = down.type == PointerType.Touch
                                if (!touch && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                                val y = down.position.y.roundToInt()
                                val item = list.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size } ?: return@awaitEachGesture
                                val index = item.index
                                val id = currentOrder.getOrNull(index) ?: return@awaitEachGesture
                                val cells = y >= item.offset + item.size - rowPx
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
                                            // the rows may have moved while the finger was down
                                            if (phone || again) currentRows.getOrNull(currentOrder.indexOf(id))?.let { openRow(it, cells, down.position.x) }
                                        }
                                        !scrolled -> {
                                            choose(if (id in state.selected) state.selected - id else state.selected + id, id)
                                            waitForUpOrCancellation()?.consume()
                                        }
                                    }
                                    return@awaitEachGesture
                                }
                                // consumed, or the workspace behind takes the keyboard back on release
                                down.consume()
                                focus.requestFocus()
                                val again = id == lastRow && down.uptimeMillis - lastPress < 400
                                lastPress = down.uptimeMillis
                                lastRow = id
                                if (again) {
                                    currentRows.getOrNull(index)?.let { openRow(it, cells, down.position.x) }
                                    return@awaitEachGesture
                                }
                                val modifiers = currentEvent.keyboardModifiers
                                val ctrl = modifiers.isCtrlPressed || modifiers.isMetaPressed
                                val shift = modifiers.isShiftPressed
                                val keep = !ctrl && !shift && id in state.selected && state.selected.size > 1
                                if (!keep) select(state.selected, state.anchor, id, currentOrder, ctrl, shift).let { (selection, anchor) -> choose(selection, anchor) }
                                val moving = move
                                var dragged = false
                                while (true) {
                                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    if (moving == null || (!dragged && abs(change.position.y - down.position.y) < viewConfiguration.touchSlop)) continue
                                    dragged = true
                                    change.consume()
                                    dragY = change.position.y
                                }
                                val target = dropAt
                                dragY = null
                                when {
                                    dragged && target != null -> moveTo(currentOrder, state.selected, target)?.let { (ids, before) -> moving?.invoke(ids, before) }
                                    keep -> choose(setOf(id), id)
                                }
                            }
                        },
                        state = list,
                    ) {
                        itemsIndexed(shown, key = { _, row -> key(row) }) { index, row ->
                            val selected = key(row) in state.selected
                            val color = when (tone(row)) {
                                RowTone.Normal -> palette.text
                                RowTone.Faded -> palette.faint
                            }
                            val group = category?.invoke(row)
                            Column(
                                Modifier.drawWithContent {
                                    drawContent()
                                    drawLine(grid, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
                                    val at = dropAt ?: return@drawWithContent
                                    val line = 2.dp.toPx()
                                    if (at == index) drawRect(palette.accent, Offset.Zero, Size(size.width, line))
                                    if (at == index + 1 && index == shown.lastIndex) drawRect(palette.accent, Offset(0f, size.height - line), Size(size.width, line))
                                },
                            ) {
                                if (group != null && (index == 0 || category(shown[index - 1]) != group)) {
                                    Text(group.ifEmpty { "—" }, Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 6.dp, vertical = 3.dp), style = Theme.type.strong)
                                }
                                val background = if (selected) palette.selection else palette.surface
                                caption(row)?.let {
                                    Text(
                                        marked(it, highlight, mark),
                                        Modifier.fillMaxWidth().background(background).padding(start = 6.dp, top = 3.dp, bottom = 1.dp),
                                        style = Theme.type.strong,
                                        color = if (selected) palette.text else palette.muted,
                                    )
                                }
                                if (detail) {
                                    Row(Modifier.fillMaxWidth().heightIn(min = metrics.row).background(background).padding(vertical = 3.dp)) {
                                        if (flags != null) Flags(flags(row), flagWidth)
                                        FlowRow(Modifier.weight(1f).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                            for (column in columns) {
                                                val value = column.shown(column.value(row))
                                                if (value.isEmpty()) continue
                                                val text = buildAnnotatedString {
                                                    withStyle(SpanStyle(color = palette.muted)) { append(column.title + " ") }
                                                    append(marked(value, highlight, mark))
                                                }
                                                Text(text, color = color)
                                            }
                                        }
                                    }
                                } else {
                                    Row(Modifier.fillMaxWidth().height(metrics.row).background(background), verticalAlignment = Alignment.CenterVertically) {
                                        if (numbers != null) {
                                            Row(Modifier.width(numberWidth).fillMaxHeight().then(divider).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                if (onMove != null) UnstyledIcon(Lucide.GripVertical, null, Modifier.size(12.dp), tint = palette.faint)
                                                Text(numbers(row), Modifier.weight(1f).testTag("number"), color = color, singleLine = true, textAlign = TextAlign.End)
                                            }
                                        }
                                        if (flags != null) Flags(flags(row), flagWidth, Modifier.fillMaxHeight().then(divider))
                                        for (column in columns) {
                                            val value = column.value(row)
                                            val icon = column.icon(row, value)
                                            Row(Modifier.width(width(column)).fillMaxHeight().then(divider).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                if (icon != null) UnstyledIcon(icon.vector, null, Modifier.padding(end = 4.dp).size(metrics.icon).testTag("cell-icon"), tint = palette.tintOf(icon)?.takeIf { color == palette.text } ?: color)
                                                Text(marked(column.shown(value), highlight, mark), color = color, singleLine = true, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val at = menuAt
                if (at != null && menu != null) {
                    val top = if (detail) 0 else with(density) { metrics.row.roundToPx() }
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
            ListScrollBar(list, Modifier.align(Alignment.CenterEnd).padding(top = if (detail) 0.dp else metrics.row))
            // the button of the columns stays at the end of the header while the columns scroll under it
            if (picker != null && !detail) {
                val classic = Theme.classic
                Box(
                    Modifier.align(Alignment.TopEnd).height(metrics.row).background(palette.raised).drawWithContent {
                        drawContent()
                        drawLine(palette.line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
                        if (!classic) drawLine(palette.line, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f)
                    },
                    contentAlignment = Alignment.Center,
                ) { picker() }
            }
        }
        if (!detail) HorizontalScrollBar(sideways, viewport)
    }
}

// a descending first key reads the rows backwards, so that equal ones come out reversed too, as the reverse of the ascending order
internal fun <T> sorted(rows: List<T>, columns: List<TableColumn<T>>, sort: List<SortKey>): List<T> {
    val keyed = sort.mapNotNull { key ->
        columns.firstOrNull { it.key == key.column }?.let { column -> key.ascending to rows.map { cellKey(column.value(it)) } }
    }
    val first = keyed.firstOrNull() ?: return rows
    val indices = if (first.first) rows.indices.toList() else rows.indices.reversed()
    val comparator = Comparator<Int> { a, b ->
        var result = 0
        for ((ascending, cells) in keyed) {
            result = cells[a].compareTo(cells[b])
            if (result != 0) {
                if (!ascending) result = -result
                break
            }
        }
        result
    }
    return indices.sortedWith(comparator).map { rows[it] }
}

private fun gap(list: LazyListState, y: Float): Int {
    val items = list.layoutInfo.visibleItemsInfo
    val item = items.firstOrNull { y < it.offset + it.size } ?: return (items.lastOrNull()?.index ?: -1) + 1
    return if (y < item.offset + item.size / 2f) item.index else item.index + 1
}

private fun Modifier.sunkenEnd(palette: Palette): Modifier = drawWithContent {
    drawContent()
    val px = 1.dp.toPx()
    drawLine(palette.line, Offset(size.width - px * 1.5f, px * 2), Offset(size.width - px * 1.5f, size.height - px * 2), px)
    drawLine(palette.lit, Offset(size.width - px / 2, px * 2), Offset(size.width - px / 2, size.height - px * 2), px)
}

@Composable
private fun <T> Header(column: TableColumn<T>, width: Dp, state: TableState<*>, separators: Boolean, onResize: (Dp) -> Unit, onDrop: ((Dp) -> Unit)?) {
    val palette = Theme.palette
    val density = LocalDensity.current
    val position = state.sort.indexOfFirst { it.column == column.key }
    val mark = when {
        position < 0 -> ""
        else -> (if (state.sort[position].ascending) " ▲" else " ▼") + if (state.sort.size > 1) "${position + 1}" else ""
    }
    var shift by remember { mutableStateOf(false) }
    var dragged by remember { mutableFloatStateOf(0f) }
    val drop by rememberUpdatedState(onDrop)
    val classic = Theme.classic
    val divider = when {
        classic -> Modifier.sunkenEnd(palette)
        separators -> Modifier.drawWithContent {
            drawContent()
            drawLine(palette.line, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f)
        }
        else -> Modifier
    }
    Box(Modifier.width(width).fillMaxHeight().zIndex(if (dragged != 0f) 1f else 0f).then(divider)) {
        Text(
            column.title + mark,
            Modifier
                .fillMaxSize()
                .pointerInput(column.key) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press) shift = event.keyboardModifiers.isShiftPressed
                        }
                    }
                }
                .pointerInput(column.key, onDrop != null) {
                    if (onDrop == null) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            drop?.invoke(with(density) { dragged.toDp() })
                            dragged = 0f
                        },
                        onDragCancel = { dragged = 0f },
                    ) { change, amount ->
                        change.consume()
                        dragged += amount
                    }
                }
                .clickable { state.toggle(column.key, shift) }
                .hand()
                // after the pointer handlers, so that only the drawing follows the pointer and the drag keeps its own frame
                .offset { IntOffset(dragged.roundToInt(), 0) }
                .background(if (dragged != 0f) palette.selection else palette.raised)
                .padding(horizontal = 6.dp, vertical = if (classic) 0.dp else 3.dp),
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
                .testTag("resize-${column.key}")
                .pointerInput(column.key) { detectTapGestures(onDoubleTap = { state.widths.remove(column.key) }) }
                .pointerInput(column.key) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val start = current
                        var moved = 0f
                        horizontalDrag(down.id) { change ->
                            moved += change.positionChange().x
                            change.consume()
                            onResize((start + with(density) { moved.toDp() }).coerceAtLeast(40.dp))
                        }
                    }
                },
        )
    }
}

@Composable
private fun Flags(letters: String, width: Dp, modifier: Modifier = Modifier) {
    Box(modifier.width(width), contentAlignment = Alignment.Center) {
        if (letters.isNotEmpty()) {
            Text(
                letters,
                Modifier.background(Theme.palette.badge, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
                style = Theme.type.small,
                color = Theme.palette.muted,
                singleLine = true,
            )
        }
    }
}

internal fun marked(text: String, highlight: String, mark: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    if (highlight.isEmpty()) return@buildAnnotatedString
    var at = text.indexOf(highlight, ignoreCase = true)
    while (at >= 0) {
        addStyle(mark, at, at + highlight.length)
        at = text.indexOf(highlight, at + highlight.length, ignoreCase = true)
    }
}
