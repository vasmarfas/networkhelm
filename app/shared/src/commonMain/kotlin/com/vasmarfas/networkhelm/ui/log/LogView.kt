package com.vasmarfas.networkhelm.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.UnstyledDropdownMenu
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.atMostEvery
import com.vasmarfas.networkhelm.session.rows
import com.vasmarfas.networkhelm.ui.files.LocalFileExchange
import com.vasmarfas.networkhelm.ui.kit.AgainOnReturn
import com.vasmarfas.networkhelm.ui.kit.ListScrollBar
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.MenuPanel
import com.vasmarfas.networkhelm.ui.kit.arrow
import com.vasmarfas.networkhelm.ui.kit.rememberCopier
import com.vasmarfas.networkhelm.ui.main.WindowTab
import com.vasmarfas.networkhelm.ui.table.TableLayout
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.table.select
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.workspace.resizeIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.log_empty
import networkhelm.app.shared.generated.resources.log_none_match
import networkhelm.app.shared.generated.resources.log_repeats
import networkhelm.app.shared.generated.resources.log_save_failed
import networkhelm.app.shared.generated.resources.log_select_all
import networkhelm.app.shared.generated.resources.log_topic_hide
import networkhelm.app.shared.generated.resources.log_topic_only
import networkhelm.app.shared.generated.resources.log_copy_csv
import networkhelm.app.shared.generated.resources.log_copy_json
import networkhelm.app.shared.generated.resources.log_copy_lines
import networkhelm.app.shared.generated.resources.log_copy_messages
import networkhelm.app.shared.generated.resources.log_details
import networkhelm.app.shared.generated.resources.log_open_window
import networkhelm.app.shared.generated.resources.log_same_message
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private sealed interface Line {
    val key: String

    // a router that lost its clock and found it again writes the same day twice, so a heading is told by the row under it
    class Day(val date: String, row: String) : Line {
        override val key: String = "day:$date:$row"
    }

    class Entry(val row: LogRow) : Line {
        override val key: String get() = row.key
    }
}

internal val PackPath?.isLog: Boolean get() = this?.log == true

@Composable
internal fun LogView(session: DeviceSession, tab: WindowTab, tables: TableLayouts) {
    val palette = Theme.palette
    val compact = LocalCompact.current
    val path = tab.path
    val layoutKey = path.toString()
    val saved = remember(layoutKey) { tables[layoutKey]?.log }
    val state = remember(path) { LogState().also { created -> saved?.let(created::restore) } }
    val scope = rememberCoroutineScope()
    val copier = rememberCopier()
    val exchange = LocalFileExchange.current
    val windows = LocalLogWindows.current
    var refresh by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    var lost by remember(session, path, refresh) { mutableStateOf(false) }
    val arrived by remember(session, path, refresh) {
        session.watch(path).rows().drop(1).atMostEvery(150.milliseconds).catch {
            (it as? DeviceException)?.let { e ->
                problem = e.message
                lost = e.category == ErrorCategory.ConnectionLost
            }
        }
    }.collectAsState(null)
    AgainOnReturn(lost) {
        problem = null
        refresh++
    }
    val clock by produceState(localClock(), session) {
        while (true) {
            value = readClock(session)
            delay(60.seconds)
        }
    }
    val tick by produceState(0) {
        while (true) {
            delay(15.seconds)
            value += 1
        }
    }
    val query = rememberTextFieldState()
    val find = remember { FocusRequester() }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    var paneHeight by remember { mutableStateOf((saved?.pane ?: 200f).dp) }
    LaunchedEffect(layoutKey) {
        snapshotFlow { state.layout(paneHeight.value) }.drop(1).collect { tables[layoutKey] = TableLayout(log = it) }
    }
    var jump by remember { mutableIntStateOf(0) }
    var held by remember { mutableStateOf<LogRow?>(null) }

    val text = query.text.toString().trim()
    val terms = remember(text) { text.split(' ').filter { it.isNotEmpty() } }
    // a line is read once: a list sends the same objects again, and a router may hold thousands of lines
    val read = remember(clock.today) { HashMap<String, Pair<Item, LogEntry>>() }
    val entries = remember(arrived, clock.today) {
        val items = arrived.orEmpty()
        val parsed = items.map { item -> read[item.id]?.takeIf { it.first === item }?.second ?: item.toEntry(clock.today).also { read[item.id] = item to it } }
        if (read.size > 2 * items.size + 100) read.keys.retainAll(items.mapTo(HashSet()) { it.id })
        parsed
    }
    val frozen = remember(state.live) { entries }
    val source = if (state.live) entries else frozen
    val fresh = if (state.live) 0 else remember(entries, frozen) { frozen.mapTo(HashSet()) { it.id }.let { seen -> entries.count { it.id !in seen } } }
    val now = remember(tick, clock) { clock.now }
    val stamped = remember(source) { source.any { it.stamp != null } }
    val shown = remember(source, state.severities, state.topics, state.hidden, state.message, state.span, text, state.onlyMatches, now.takeIf { state.span != Span.Any }) {
        source.filtered(LogFilter(state.severities, state.topics, state.hidden, if (state.onlyMatches) text else "", state.span, state.message, now))
    }
    val rows = remember(shown, state.collapse) { if (state.collapse) shown.collapsed() else shown.map { LogRow(listOf(it)) } }
    val order = remember(rows) { rows.map { it.key } }
    val keys = remember(order) { order.toSet() }
    val byKey = remember(rows) { rows.associateBy { it.key } }
    val lines = remember<List<Line>>(rows, state.times) {
        if (state.times != TimeStyle.Short) {
            rows.map { Line.Entry(it) }
        } else {
            buildList {
                var day: String? = null
                for (row in rows) {
                    val date = row.last.stamp?.date
                    if (date != null && date != day) {
                        add(Line.Day(date, row.key))
                        day = date
                    }
                    add(Line.Entry(row))
                }
            }
        }
    }
    val place = remember(lines) { lines.withIndex().associate { (index, line) -> line.key to index } }
    val day by remember(lines) {
        derivedStateOf {
            when (val top = lines.getOrNull(list.firstVisibleItemIndex)) {
                is Line.Day -> top.date
                is Line.Entry -> top.row.last.stamp?.date
                null -> null
            }
        }
    }
    val severityList = remember(source) { source.severityCounts() }
    val topicList = remember(source) { source.topicCounts() }

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val mono = Theme.type.mono
    val small = Theme.type.small
    val wordWidths = remember(small) { HashMap<String, Int>() }
    val timeWidth = steady(
        remember(rows, state.times, mono) {
            if (state.times == TimeStyle.Relative) {
                96.dp
            } else {
                val sample = rows.takeLast(300).map { if (state.times == TimeStyle.Short) it.last.stamp?.clock ?: it.last.time else it.last.time }
                val widest = sample.maxByOrNull { it.length }.orEmpty().ifEmpty { "00:00:00" }
                with(density) { measurer.measure(widest, mono, maxLines = 1, softWrap = false).size.width.toDp() } + 4.dp
            }
        },
        state.times,
    )
    val topicsWidth = steady(
        remember(rows, small) {
            val pad = with(density) { 10.dp.roundToPx() }
            val gap = with(density) { 3.dp.roundToPx() }
            val widest = rows.takeLast(300).maxOfOrNull { row ->
                val words = row.last.tags
                words.sumOf { word -> wordWidths.getOrPut(word) { measurer.measure(word, small, maxLines = 1, softWrap = false).size.width } + pad } + gap * (words.size - 1).coerceAtLeast(0)
            } ?: 0
            with(density) { widest.toDp() }.coerceIn(0.dp, 260.dp) + 4.dp
        },
        small,
    )

    val caretRow = state.caret?.let { byKey[it] }
    LaunchedEffect(caretRow) { if (caretRow != null) held = caretRow }
    val detail = caretRow ?: held
    val showDetails = state.details && detail != null
    val context = remember(source, detail) { detail?.let { source.around(it.last) }.orEmpty() }

    val probe = remember(text) { LogFilter(query = text) }
    val hits = remember(rows, probe, text, state.onlyMatches) {
        when {
            text.isEmpty() -> emptyList()
            state.onlyMatches -> order
            else -> rows.filter { row -> row.entries.any(probe::passes) }.map { it.key }
        }
    }

    var target by remember { mutableStateOf<String?>(null) }

    fun show(key: String) {
        place[key]?.let { index -> scope.launch { list.reveal(index) } }
    }

    fun go(key: String) {
        state.choose(setOf(key), key)
        show(key)
    }

    fun pick(key: String, ctrl: Boolean, shift: Boolean) {
        val (selection, anchor) = select(state.selected, state.anchor, key, order, ctrl, shift)
        state.choose(selection, anchor, key)
    }

    fun move(delta: Int, extend: Boolean) {
        if (order.isEmpty()) return
        val at = order.indexOf(state.caret)
        val next = order[(if (at < 0) (if (delta > 0) delta - 1 else order.size + delta) else at + delta).coerceIn(0, order.lastIndex)]
        if (extend) pick(next, ctrl = false, shift = true) else state.choose(setOf(next), next)
        show(next)
    }

    fun step(delta: Int) {
        if (hits.isEmpty()) return
        val at = hits.indexOf(state.caret)
        go(hits[if (at < 0) (if (delta > 0) 0 else hits.lastIndex) else (at + delta).mod(hits.size)])
    }

    fun latest() {
        state.live = true
        jump++
    }

    LaunchedEffect(state.live, jump) {
        if (state.live && lines.isNotEmpty()) list.scrollToItem(lines.lastIndex)
    }
    // the view follows the end while it stands at the line that was the newest or the one before it; the lines are told by their keys,
    // since a full buffer trades its oldest lines for new ones and keeps its size
    var newest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lines.size, lines.lastOrNull()?.key) {
        val shown = list.layoutInfo.visibleItemsInfo.lastOrNull()?.key as? String
        val before = place[newest]
        val atEnd = shown == null || before == null || place[shown]?.let { before - it <= 1 } == true
        if (state.live && target == null && lines.isNotEmpty() && atEnd) list.scrollToItem(lines.lastIndex)
        newest = lines.lastOrNull()?.key
    }
    LaunchedEffect(target, place) {
        val key = target ?: return@LaunchedEffect
        val index = place[key]
        if (index == null) {
            if (arrived != null) target = null
            return@LaunchedEffect
        }
        snapshotFlow { list.layoutInfo.totalItemsCount }.first { it == lines.size }
        list.reveal(index)
        target = null
    }

    fun chosen() = rows.filter { it.key in state.selected }.flatMap { it.entries }
    fun exported() = chosen().ifEmpty { rows.flatMap { it.entries } }
    fun formed(kind: CopyKind, from: List<LogEntry>) = when (kind) {
        CopyKind.Lines -> from.asLines()
        CopyKind.Messages -> from.asMessages()
        CopyKind.Csv -> from.asCsv()
        CopyKind.Json -> from.asJson()
    }
    fun copy(copied: String) {
        if (copied.isNotEmpty()) copier.copy(copied)
    }
    fun save(kind: CopyKind) {
        val name = if (kind == CopyKind.Csv) "log.csv" else "log.txt"
        val bytes = formed(kind, exported()).encodeToByteArray()
        scope.launch {
            try {
                exchange.save(name, bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = getString(Res.string.log_save_failed, e.message ?: e.toString())
            }
        }
    }
    fun selectAll() = state.choose(keys, order.firstOrNull(), order.lastOrNull())
    fun showAll() {
        target = state.caret
        state.clearNarrowing()
        query.edit { replace(0, length, "") }
    }

    fun openWindow(row: LogRow?) {
        if (row == null) return
        val at = rows.indexOfFirst { it.key == row.key }
        windows?.open(if (at >= 0) LogEntryWindow(path, rows, source, at, clock) else LogEntryWindow(path, listOf(row), source, 0, clock))
    }

    fun openLine(row: LogRow?) {
        if (compact || windows == null) state.details = true else if (!state.details) openWindow(row)
    }

    val request = windows?.request
    LaunchedEffect(request) {
        if (windows == null || request == null || request.path != path) return@LaunchedEffect
        windows.request = null
        target = request.id
        state.clearNarrowing()
        query.edit { replace(0, length, "") }
        state.choose(setOf(request.id), request.id)
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        // AltGr comes as Ctrl with Alt on Windows, and it types letters
        val ctrl = event.isCtrlPressed && !event.isAltPressed || event.isMetaPressed
        val shift = event.isShiftPressed
        val page = (list.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
        when {
            ctrl && event.key == Key.A -> selectAll()
            ctrl && event.key == Key.C -> copy(formed(if (shift) CopyKind.Messages else CopyKind.Lines, chosen()))
            ctrl && event.key == Key.F -> find.requestFocus()
            event.key == Key.DirectionDown -> move(1, shift)
            event.key == Key.DirectionUp -> move(-1, shift)
            event.key == Key.PageDown -> move(page, shift)
            event.key == Key.PageUp -> move(-page, shift)
            event.key == Key.MoveHome -> move(-order.size, shift)
            event.key == Key.MoveEnd -> if (state.live) move(order.size, shift) else latest()
            ctrl && event.key == Key.Enter -> openWindow(caretRow)
            event.key == Key.Enter -> if (state.caret != null) openLine(caretRow)
            event.key == Key.Escape -> when {
                state.details -> state.details = false
                state.selected.isNotEmpty() -> state.choose(emptySet(), null)
                else -> return false
            }
            event.key == Key.Spacebar -> state.live = !state.live
            event.key == Key.F3 -> step(if (shift) -1 else 1)
            event.key == Key.F5 -> refresh++
            else -> return false
        }
        return true
    }

    fun onSearchKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return when (event.key) {
            Key.Enter -> {
                step(if (event.isShiftPressed) -1 else 1)
                true
            }
            Key.Escape -> {
                query.edit { replace(0, length, "") }
                focus.requestFocus()
                true
            }
            Key.DirectionDown -> {
                focus.requestFocus()
                move(1, false)
                true
            }
            else -> false
        }
    }

    val index = order.indexOf(state.caret)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val limit = maxHeight * 0.5f
        Column(Modifier.fillMaxSize().background(palette.surface)) {
            LogToolbar(
                state, query, find, severityList, topicList, stamped, hits.size, hits.indexOf(state.caret) + 1, fresh,
                onStep = ::step,
                onLatest = ::latest,
                onCopy = { kind -> copy(formed(kind, exported())) },
                onSave = ::save,
                onSelectAll = ::selectAll,
                onSearchKey = ::onSearchKey,
            )
            LogChips(state)
            val details: @Composable (Modifier) -> Unit = { modifier ->
                val row = detail
                if (row != null) {
                    LogDetails(
                        row, context, now,
                        onPrevious = if (index > 0) ({ move(-1, false) }) else null,
                        onNext = if (index in 0 until order.lastIndex) ({ move(1, false) }) else null,
                        onCopy = ::copy,
                        modifier = modifier,
                        chosen = order.count { it in state.selected },
                        onCopyChosen = { copy(chosen().asLines()) },
                        onTopic = state::toggleTopic,
                        onSameMessage = { state.message = row.last.message },
                        onShow = if (state.narrowed) ({ showAll() }) else null,
                        onWindow = if (windows != null) ({ openWindow(row) }) else null,
                        onClose = { state.details = false },
                    )
                }
            }
            if (showDetails && compact) {
                details(Modifier.weight(1f))
            } else {
                LogList(
                    lines, keys, list, state, terms, now, timeWidth, topicsWidth, focus,
                    if (state.narrowed || text.isNotEmpty()) stringResource(Res.string.log_none_match) else if (arrived == null) "" else stringResource(Res.string.log_empty),
                    onPick = ::pick,
                    onOpen = { key -> openLine(byKey[key]) },
                    onKey = ::onKey,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { close ->
                    fun done(action: () -> Unit) {
                        close()
                        action()
                    }
                    val words = byKey[state.caret]?.last?.words.orEmpty().filter { severityOf(it) == null }.take(3)
                    MenuLine(stringResource(Res.string.log_copy_lines), "Ctrl+C") { done { copy(formed(CopyKind.Lines, chosen())) } }
                    MenuLine(stringResource(Res.string.log_copy_messages), "Ctrl+Shift+C") { done { copy(formed(CopyKind.Messages, chosen())) } }
                    MenuLine(stringResource(Res.string.log_copy_csv), null) { done { copy(formed(CopyKind.Csv, chosen())) } }
                    MenuLine(stringResource(Res.string.log_copy_json), null) { done { copy(formed(CopyKind.Json, chosen())) } }
                    if (!state.details) MenuLine(stringResource(Res.string.log_details), null) { done { state.details = true } }
                    if (windows != null) MenuLine(stringResource(Res.string.log_open_window), "Ctrl+Enter") { done { openWindow(caretRow) } }
                    byKey[state.caret]?.last?.message?.let { message -> MenuLine(stringResource(Res.string.log_same_message), null) { done { state.message = message } } }
                    for (word in words) {
                        MenuLine(stringResource(Res.string.log_topic_only, word), null) { done { state.toggleTopic(word) } }
                        MenuLine(stringResource(Res.string.log_topic_hide, word), null) { done { state.toggleHidden(word) } }
                    }
                    MenuLine(stringResource(Res.string.log_select_all), "Ctrl+A") { done(::selectAll) }
                }
                if (showDetails) {
                    Box(
                        Modifier.fillMaxWidth().height(5.dp).background(palette.line).pointerHoverIcon(resizeIcon(0, 1)).testTag("log-splitter").pointerInput(Unit) {
                            detectVerticalDragGestures { change, drag ->
                                change.consume()
                                paneHeight = (paneHeight - drag.toDp()).coerceIn(120.dp, 560.dp)
                            }
                        },
                    )
                    details(Modifier.height(paneHeight.coerceAtMost(limit)))
                }
            }
            problem?.let { Text(it, Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 2.dp), style = Theme.type.small, color = palette.danger) }
            LogStatus(state, shown.size, source.size, severityList, order.count { it in state.selected }, arrived == null, fresh, day.takeIf { state.times == TimeStyle.Short })
        }
    }
}

@Composable
private fun steady(measured: Dp, reset: Any?): Dp {
    var kept by remember(reset) { mutableStateOf(0.dp) }
    LaunchedEffect(measured) { if (measured > kept) kept = measured }
    return maxOf(measured, kept)
}

private suspend fun LazyListState.reveal(index: Int) {
    val visible = layoutInfo.visibleItemsInfo
    val first = visible.firstOrNull()?.index ?: return
    val last = visible.lastOrNull()?.index ?: return
    when {
        index <= first -> scrollToItem(index)
        index >= last -> scrollToItem((index - (last - first) + 1).coerceAtLeast(0))
    }
}

@Composable
private fun LogList(
    lines: List<Line>,
    keys: Set<String>,
    list: LazyListState,
    state: LogState,
    terms: List<String>,
    now: Long,
    timeWidth: Dp,
    topicsWidth: Dp,
    focus: FocusRequester,
    empty: String,
    onPick: (String, Boolean, Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onKey: (KeyEvent) -> Boolean,
    modifier: Modifier,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    val palette = Theme.palette
    val compact = LocalCompact.current
    val pick by rememberUpdatedState(onPick)
    val open by rememberUpdatedState(onOpen)
    val phone by rememberUpdatedState(compact)
    val present by rememberUpdatedState(keys)
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    fun keyAt(y: Float): String? = list.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.key as? String
    Box(modifier.background(palette.surface).onPreviewKeyEvent(onKey).focusRequester(focus).focusable().testTag("log-list")) {
        if (lines.isEmpty()) {
            Text(empty, Modifier.align(Alignment.Center).padding(16.dp).testTag("log-empty"), color = palette.muted)
        } else {
            LazyColumn(
                Modifier.fillMaxSize().arrow().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type != PointerEventType.Press || !event.buttons.isSecondaryPressed) continue
                            val press = event.changes.firstOrNull() ?: continue
                            val key = keyAt(press.position.y)?.takeIf { it in present } ?: continue
                            press.consume()
                            focus.requestFocus()
                            if (key !in state.selected) pick(key, false, false)
                            menuAt = press.position
                        }
                    }
                }.pointerInput(Unit) {
                    var lastKey: String? = null
                    var lastTime = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        // a pen touches with no button held, like a finger
                        val touch = down.type != PointerType.Mouse
                        if (!touch && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                        val key = keyAt(down.position.y)?.takeIf { it in present } ?: return@awaitEachGesture
                        if (touch) {
                            var scrolled = false
                            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                waitForUpOrCancellation().also { if (it == null) scrolled = true }
                            }
                            when {
                                up != null -> {
                                    up.consume()
                                    focus.requestFocus()
                                    pick(key, false, false)
                                    if (phone) open(key)
                                }
                                !scrolled -> {
                                    pick(key, true, false)
                                    waitForUpOrCancellation()?.consume()
                                }
                            }
                            return@awaitEachGesture
                        }
                        down.consume()
                        focus.requestFocus()
                        val again = key == lastKey && down.uptimeMillis - lastTime < 400
                        lastKey = key
                        lastTime = down.uptimeMillis
                        val modifiers = currentEvent.keyboardModifiers
                        pick(key, modifiers.isCtrlPressed || modifiers.isMetaPressed, modifiers.isShiftPressed)
                        if (again) open(key)
                    }
                },
                state = list,
            ) {
                items(lines, key = { it.key }) { line ->
                    DisableSelection {
                        when (line) {
                            is Line.Day -> DayHeading(line.date)
                            is Line.Entry -> {
                                val row = line.row
                                LogLine(row, row.key in state.selected, timeText(row.last, state.times, now), timeWidth, topicsWidth, terms, state.wrap, compact)
                            }
                        }
                    }
                }
            }
            ListScrollBar(list, Modifier.align(Alignment.CenterEnd))
        }
        val at = menuAt
        if (at != null) {
            Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
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
private fun timeText(entry: LogEntry, style: TimeStyle, now: Long): String = when (style) {
    TimeStyle.Short -> entry.stamp?.clock ?: entry.time
    TimeStyle.Full -> entry.time
    TimeStyle.Relative -> entry.stamp?.let { agoText(now - it.seconds) } ?: entry.time
}

@Composable
private fun DayHeading(date: String) {
    val palette = Theme.palette
    Text(date, Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 10.dp, vertical = 3.dp), style = Theme.type.strong, color = palette.muted, singleLine = true)
}

private fun highlighted(text: String, terms: List<String>, mark: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    for (term in terms) {
        var at = text.indexOf(term, ignoreCase = true)
        while (at >= 0) {
            addStyle(mark, at, at + term.length)
            at = text.indexOf(term, at + term.length, ignoreCase = true)
        }
    }
}

@Composable
private fun LogLine(row: LogRow, selected: Boolean, time: String, timeWidth: Dp, topicsWidth: Dp, terms: List<String>, wrap: Boolean, compact: Boolean) {
    val palette = Theme.palette
    val entry = row.last
    val severity = entry.severity
    val tint = severity.color(palette)
    val mark = SpanStyle(background = palette.warning.copy(alpha = 0.35f))
    val bar = if (severity >= Severity.Warning) tint else Color.Transparent
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val background = when {
        selected -> palette.selection
        hovered -> palette.raised
        severity >= Severity.Error -> palette.danger.copy(alpha = 0.07f)
        else -> Color.Transparent
    }
    val message = highlighted(entry.message, terms, mark)
    val grid = palette.grid ?: palette.line
    val frame = Modifier.fillMaxWidth().hoverable(hover).background(background).drawBehind {
        drawLine(grid, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
        if (bar != Color.Transparent) drawRect(bar, Offset.Zero, Size(3.dp.toPx(), size.height))
    }
    val style: TextStyle = if (severity == Severity.Critical) Theme.type.strong else Theme.type.body
    @Composable
    fun Words() {
        for (word in entry.tags) TopicTag(word, severityOf(word)?.color(palette))
    }
    @Composable
    fun Repeats() {
        if (row.count > 1) TopicTag(stringResource(Res.string.log_repeats, row.count))
    }
    if (compact) {
        Column(frame.heightIn(min = Theme.metrics.row).padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(highlighted(time, terms, mark), style = Theme.type.mono, color = palette.muted, singleLine = true)
                Words()
                Repeats()
            }
            Text(message, style = style, color = tint, maxLines = if (wrap) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    val tall = Modifier.heightIn(min = Theme.metrics.row).padding(vertical = 3.dp)
    Row(
        frame.then(if (wrap) tall else Modifier.height(Theme.metrics.row)).padding(start = 10.dp, end = 8.dp),
        verticalAlignment = if (wrap) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(highlighted(time, terms, mark), Modifier.width(timeWidth), style = Theme.type.mono, color = palette.muted, singleLine = true)
        Row(Modifier.width(topicsWidth).clipToBounds(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) { Words() }
        Text(message, Modifier.weight(1f), style = style, color = tint, maxLines = if (wrap) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
        Repeats()
    }
}
