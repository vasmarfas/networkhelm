package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.duration
import com.vasmarfas.networkhelm.packs.GROUP_LINE
import com.vasmarfas.networkhelm.packs.PackFallback
import com.vasmarfas.networkhelm.packs.hides
import com.vasmarfas.networkhelm.routeros.RouterOsVersion
import com.vasmarfas.networkhelm.routeros.schema.label
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.QUERY_END
import com.vasmarfas.networkhelm.session.QUERY_PASS
import com.vasmarfas.networkhelm.ui.form.FormState
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.CountPill
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.SplitState
import com.vasmarfas.networkhelm.ui.kit.TabStrip
import com.vasmarfas.networkhelm.ui.kit.VerticalSplit
import com.vasmarfas.networkhelm.ui.table.DataTable
import com.vasmarfas.networkhelm.ui.table.TableColumn
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.workspace.VerticalScrollbar
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import networkhelm.app.shared.generated.resources.command_fasttrack
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.command_start
import networkhelm.app.shared.generated.resources.command_stop
import networkhelm.app.shared.generated.resources.list_items
import networkhelm.app.shared.generated.resources.query_risky

private class Section(val title: String, val fields: List<String>, val inline: Boolean)

private const val START = "start"
private const val LOG_ROWS = 1000

// the console window that a query took the place of, as the window of the tab
internal fun consoleTab(tab: WindowTab, console: PackFallback) =
    WindowTab(tab.title, tab.path, console.path, console.labels + console.path.labels, console.form, tab.prefill, tab.recall, tab.start)

// a router that refuses the query once gives the window back to the console command that the query took the place of
@Composable
internal fun QueryView(session: DeviceSession, tab: WindowTab, schema: MenuSchema, onClose: () -> Unit) {
    var console by remember(tab.path) { mutableStateOf<Pair<WindowTab, MenuSchema>?>(null) }
    val given = console
    if (given != null) ConsoleCommandView(session, given.first, given.second) else QueryBody(session, tab, schema, onClose) { console = it }
}

// a query or an action of the router: the request form above, the table of a query or the fields of an action below, and the status of the run in the bar
@Composable
private fun QueryBody(session: DeviceSession, tab: WindowTab, schema: MenuSchema, onClose: () -> Unit, onFallback: (Pair<WindowTab, MenuSchema>) -> Unit) {
    val palette = Theme.palette
    val pack = tab.shown ?: return
    val query = pack.native?.query ?: return
    val scope = rememberCoroutineScope()
    val form = remember(tab.path) { FormState().apply { tab.prefill.forEach { (name, value) -> edit(name, value) } } }
    var rows by remember { mutableStateOf<List<Item>>(emptyList()) }
    val pending = remember { mutableListOf<Item>() }
    var running by remember { mutableStateOf<Job?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    var began by remember { mutableStateOf<TimeMark?>(null) }
    var seconds by remember { mutableLongStateOf(0) }

    fun start() {
        val arguments = form.changes().mapNotNull { (name, value) -> value?.takeIf { it.isNotEmpty() }?.let { name to it } }.toMap()
        rows = emptyList()
        pending.clear()
        problem = null
        seconds = 0
        began = TimeSource.Monotonic.markNow()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                session.run(tab.path, START, arguments).collect { item ->
                    when {
                        item.id == QUERY_END -> {
                            rows = pending.toList()
                            pending.clear()
                        }
                        item[QUERY_PASS] != null -> pending += item
                        else -> rows = (rows + item).takeLast(query.limit ?: LOG_ROWS)
                    }
                }
                if (query.close) onClose()
            } catch (e: DeviceException) {
                val console = query.fallback
                // a window that asks before it starts is not run again by the console behind the back of the one who confirmed it
                if (e.category == ErrorCategory.Unsupported && console != null && !pack.confirm && rows.isEmpty()) {
                    try {
                        onFallback(consoleTab(tab, console) to session.describe(tab.path))
                    } catch (again: DeviceException) {
                        problem = again.message
                    }
                } else {
                    problem = e.message
                }
            } finally {
                seconds = began?.elapsedNow()?.inWholeSeconds ?: 0
                if (running === coroutineContext.job) running = null
            }
        }
        running = job
        job.start()
    }

    LaunchedEffect(running) {
        while (running != null) {
            seconds = began?.elapsedNow()?.inWholeSeconds ?: 0
            delay(1_000)
        }
    }

    val release = RouterOsVersion.parse(session.device.version)
    val sections = (tab.form?.tabs?.filter { release?.within(it.since, it.until) != false } ?: emptyList())
        .map { Section(it.title, it.fields.filter { field -> field == GROUP_LINE || field in query.notes || schema.argument(field) != null }, it.inline) }
        .filter { section -> section.fields.any { it != GROUP_LINE } }
    val stacked = sections.isNotEmpty() && sections.all { it.inline }
    var section by remember(tab.path) { mutableIntStateOf(0) }
    val columns = pack.columns
    val last = rows.lastOrNull()
    val state = last.takeIf { query.action }
    val split = remember(tab.path) { SplitState() }
    val scrolled = rememberScrollState()
    val density = LocalDensity.current
    val labelColumn = remember(tab.path) { LabelColumn() }
    val ready = query.required.all { !form.value(it).isNullOrEmpty() }
    LaunchedEffect(tab.path) { if (tab.start && ready && !pack.confirm) start() }

    fun valueOf(name: String) = form.value(name) ?: pack.defaults[name]

    val inputs: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(scrolled).wrapContentHeight(Alignment.Top)
                    .onSizeChanged { split.natural = with(density) { it.height.toDp() } }.padding(vertical = 6.dp).testTag("query-fields"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompositionLocalProvider(LocalLabelColumn provides labelColumn) {
                    val gates = tab.form?.gates { schema.argument(it) != null || it in query.notes }.orEmpty()
                    for (shown in if (stacked) sections else listOfNotNull(sections.getOrNull(section))) {
                        if (stacked) Text(shown.title, Modifier.padding(horizontal = 12.dp).padding(top = 4.dp).testTag("query-section"), color = palette.muted)
                        var above = false
                        var line = false
                        for (field in shown.fields) {
                            val note = query.notes[field]
                            if (note != null) {
                                if (!gates.hides(field, ::valueOf)) Text(note, Modifier.padding(horizontal = 12.dp, vertical = 2.dp).testTag("query-note"), style = Theme.type.small, color = palette.warning)
                                continue
                            }
                            val argument = schema.argument(field)?.let { it.copy(default = pack.defaults[it.name]) }
                            if (argument == null) {
                                line = true
                                continue
                            }
                            if (gates.hides(field, ::valueOf)) continue
                            if (above && line) GroupLine()
                            above = true
                            line = false
                            FieldRow(argument, tab.labels, form, waits = waitsFor(gates, field, ::valueOf, tab.labels))
                        }
                    }
                }
            }
            VerticalScrollbar(scrolled, Modifier.align(Alignment.CenterEnd))
            if (running != null) Box(Modifier.matchParentSize().background(palette.surface.copy(alpha = 0.5f)).pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            })
        }
    }

    val outputs: @Composable () -> Unit = {
        if (query.action) {
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxSize().testTag("query-results")) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompositionLocalProvider(LocalLabelColumn provides labelColumn) {
                        for (name in query.results) {
                            val value = if (name == query.uptime) began?.let { duration(seconds) } else state?.get(name)?.let { formatted(pack, name, it) }
                            ReadOnlyRow(name, tab.labels, value)
                        }
                    }
                }
                VerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd))
            }
        } else {
            val marks = TableColumn<IndexedValue<Item>>("flags", "", 36.dp) { row -> pack.flags.filter { row.value[it.property] == "true" }.joinToString("") { it.letter } }
            DataTable(
                rows = rows.withIndex().toList(),
                columns = listOfNotNull(marks.takeIf { pack.flags.isNotEmpty() }) + columns.map { column ->
                    TableColumn<IndexedValue<Item>>(column, tab.labels[column] ?: label(column), null, { cell(pack, column, it) }) { row -> row.value[column].orEmpty() }
                },
                key = { it.index },
                follow = true,
                modifier = Modifier.fillMaxSize().testTag("query-results"),
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (sections.size > 1 && !stacked) {
                TabStrip(sections.map { it.title }, section.coerceAtMost(sections.lastIndex), { section = it }, Modifier.fillMaxWidth().background(palette.raised), under = palette.surface)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    sections.isEmpty() -> outputs()
                    query.action && query.results.isEmpty() -> inputs()
                    else -> VerticalSplit(split, Modifier.fillMaxSize(), first = inputs, second = outputs)
                }
            }
            val line = query.status.mapNotNull { name -> last?.get(name)?.let { value -> tab.labels[name]?.let { "$it: " }.orEmpty() + formatted(pack, name, value) } }.joinToString("   ")
            val compact = LocalCompact.current
            val notice = @Composable { lines: Int ->
                val failed = problem
                when {
                    failed != null -> Text(deviceMessage(failed), color = palette.danger, style = Theme.type.small, maxLines = lines, modifier = Modifier.testTag("problem"))
                    line.isNotEmpty() -> Text(line, style = Theme.type.small, maxLines = lines, modifier = Modifier.testTag("query-status"))
                    pack.fasttrack -> Text(stringResource(Res.string.command_fasttrack), color = palette.warning, style = Theme.type.small, maxLines = lines)
                }
            }
            if (compact) Box(Modifier.fillMaxWidth().background(palette.raised).padding(start = 8.dp, end = 8.dp, top = 4.dp)) { notice(Int.MAX_VALUE) }
            Row(
                Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!query.action) {
                    CountPill(pluralStringResource(Res.plurals.list_items, rows.size, rows.size), Modifier.testTag("query-count"))
                }
                Box(Modifier.weight(1f)) {
                    if (!compact) notice(2)
                }
                Button(stringResource(Res.string.command_stop), { running?.cancel() }, Modifier.testTag("query-stop"), enabled = running != null, danger = true)
                Button(stringResource(Res.string.command_start), { if (pack.confirm) asking = true else start() }, Modifier.testTag("query-start"), enabled = running == null && ready, primary = true)
            }
        }
        if (asking) {
            Confirm(query.confirm ?: stringResource(Res.string.query_risky, tab.title), stringResource(Res.string.command_start), onCancel = { asking = false }) {
                asking = false
                start()
            }
        }
    }
}
