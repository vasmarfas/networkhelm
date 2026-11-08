package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.packs.GROUP_LINE
import com.vasmarfas.networkhelm.packs.PackFormTab
import com.vasmarfas.networkhelm.packs.hides
import com.vasmarfas.networkhelm.routeros.RouterOsVersion
import com.vasmarfas.networkhelm.routeros.schema.label
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.chart.Chart
import com.vasmarfas.networkhelm.ui.chart.Legend
import com.vasmarfas.networkhelm.ui.chart.Ring
import com.vasmarfas.networkhelm.ui.chart.Series
import com.vasmarfas.networkhelm.ui.chart.bits
import com.vasmarfas.networkhelm.ui.form.FormState
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.CountPill
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.SplitState
import com.vasmarfas.networkhelm.ui.kit.TabStrip
import com.vasmarfas.networkhelm.ui.kit.VerticalSplit
import com.vasmarfas.networkhelm.ui.table.DataTable
import com.vasmarfas.networkhelm.ui.table.RowTone
import com.vasmarfas.networkhelm.ui.table.TableColumn
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.workspace.VerticalScrollbar
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.command_fasttrack
import networkhelm.app.shared.generated.resources.command_question
import networkhelm.app.shared.generated.resources.command_start
import networkhelm.app.shared.generated.resources.command_stop
import networkhelm.app.shared.generated.resources.command_total
import networkhelm.app.shared.generated.resources.list_items

@Composable
internal fun CommandView(session: DeviceSession, tab: WindowTab, schema: MenuSchema, onClose: () -> Unit = {}) {
    val query = tab.shown?.native?.query
    val console = query?.fallback
    when {
        query == null -> ConsoleCommandView(session, tab, schema)
        console != null && !session.nativeStands(tab.path) -> ConsoleCommandView(session, consoleTab(tab, console), schema)
        else -> QueryView(session, tab, schema, onClose)
    }
}

@Composable
internal fun ConsoleCommandView(session: DeviceSession, tab: WindowTab, schema: MenuSchema) {
    val palette = Theme.palette
    val pack = tab.shown
    val scope = rememberCoroutineScope()
    val form = remember(tab.path) {
        FormState().apply { tab.prefill.forEach { (name, value) -> edit(name, schema.argument(name)?.takeIf { value !in it.choices }?.labels?.entries?.firstOrNull { it.value == value }?.key ?: value) } }
    }
    fun title(property: String) = pack?.columnLabels?.get(property) ?: tab.labels[property] ?: label(property)
    LaunchedEffect(tab.path) {
        for ((name, source) in pack?.preselect.orEmpty()) {
            if (name in tab.prefill || schema.argument(name) == null) continue
            val rows = try {
                session.watch(MenuPath.parse(source)).filterIsInstance<ListEvent.Snapshot>().first().items
            } catch (e: DeviceException) {
                continue
            }
            val first = rows.minByOrNull { it.id.removePrefix("*").toLongOrNull(16) ?: Long.MAX_VALUE }?.get("name")
            if (first != null && !form.touched(name)) form.edit(name, first)
        }
    }
    var rows by remember { mutableStateOf<List<Item>>(emptyList()) }
    var running by remember { mutableStateOf<Job?>(null) }
    var runWith by remember { mutableStateOf(emptyMap<String, String>()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    // torch sends nothing in a second without traffic, so the quiet seconds count as tables without flows
    var quiet by remember { mutableIntStateOf(0) }
    LaunchedEffect(rows, running) {
        quiet = 0
        if (running == null || pack?.flows.isNullOrEmpty()) return@LaunchedEffect
        while (true) {
            delay(1_000)
            quiet++
        }
    }
    val traffic = remember(tab.path) { mutableStateListOf<Series>() }
    val history = remember(tab.path) { HashMap<Int, MutableList<String>>() }
    val colors = listOf(palette.accent, palette.success, palette.warning, palette.danger)

    fun sample(item: Item) {
        for ((property, direction) in listOf("tx-bits-per-second" to "Tx", "rx-bits-per-second" to "Rx", "tx-current" to "Tx", "rx-current" to "Rx")) {
            val value = item[property]?.toDoubleOrNull() ?: continue
            val name = listOfNotNull(item["name"], direction).joinToString(" ")
            val line = traffic.firstOrNull { it.name == name } ?: Series(name, colors[traffic.size % colors.size], Ring(90)).also { traffic += it }
            line.ring.add(value)
        }
    }

    fun arguments() = form.changes().mapNotNull { (name, value) -> value?.takeIf { it.isNotEmpty() }?.let { name to it } }.toMap()

    fun start() {
        val arguments = arguments()
        runWith = arguments
        rows = emptyList()
        traffic.clear()
        history.clear()
        problem = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                session.run(tab.path.parent ?: MenuPath.Root, tab.path.segments.last(), arguments).collect { reply ->
                    val item = pack?.history?.let { withHistory(rows, reply, it, history) } ?: reply
                    rows = collected(rows, item, pack?.flows.orEmpty())
                    sample(reply)
                }
            } catch (e: DeviceException) {
                problem = e.message
            } finally {
                if (running === coroutineContext.job) running = null
            }
        }
        running = job
        job.start()
    }

    LaunchedEffect(tab.path) { if (pack?.autostart == true || tab.start) start() }
    val wanted = arguments()
    LaunchedEffect(wanted) {
        if (pack?.autostart != true || running == null || wanted == runWith) return@LaunchedEffect
        delay(RESTART_MILLIS)
        running?.cancel()
        start()
    }

    val release = RouterOsVersion.parse(session.device.version)
    val sections = (tab.form?.tabs?.filter { release?.within(it.since, it.until) != false } ?: listOf(PackFormTab("", schema.arguments.map { it.name } - consoleOnly)))
        .map { it.title to it.fields.filter { field -> field == GROUP_LINE || schema.argument(field) != null } }
        .filter { (_, fields) -> fields.any { it != GROUP_LINE } }
    var section by remember(tab.path) { mutableIntStateOf(0) }
    val fieldsOnly = pack?.columns.isNullOrEmpty() && rows.isEmpty()
    val columns = pack?.columns?.takeIf { it.isNotEmpty() } ?: rows.firstOrNull()?.values?.keys.orEmpty().filter { it != ".section" }
    val flows = pack?.flows.orEmpty()
    val latest = rows.mapNotNull { it[".section"]?.toIntOrNull() }.maxOrNull() ?: 0
    fun age(row: Item) = latest - (row[".section"]?.toIntOrNull() ?: latest) + (quiet - 1).coerceAtLeast(0)
    fun lingering(row: Item) = flows.isNotEmpty() && age(row) > 0
    val visible = if (flows.isEmpty()) rows else rows.filter { age(it) <= 3 }
    val split = remember(tab.path) { SplitState() }
    val scrolled = rememberScrollState()
    val density = LocalDensity.current

    val inputs: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            // the scroll hands its own height down as the least, so what the fields want is measured after the wrap
            Column(
                Modifier.fillMaxWidth().verticalScroll(scrolled).wrapContentHeight(Alignment.Top)
                    .onSizeChanged { split.natural = with(density) { it.height.toDp() } }.padding(vertical = 6.dp).testTag("command-fields"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompositionLocalProvider(LocalLabelColumn provides remember(tab.path) { LabelColumn() }) {
                    var above = false
                    var line = false
                    val gates = tab.form?.gates { schema.argument(it) != null }.orEmpty()
                    for (field in sections.getOrNull(section)?.second.orEmpty()) {
                        val argument = schema.argument(field)
                        if (argument == null) {
                            line = true
                            continue
                        }
                        if (gates.hides(field, form::value)) continue
                        if (above && line) GroupLine()
                        above = true
                        line = false
                        FieldRow(argument, tab.labels, form, waits = waitsFor(gates, field, form::value, tab.labels))
                    }
                }
            }
            VerticalScrollbar(scrolled, Modifier.align(Alignment.CenterEnd))
        }
    }

    val outputs: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize().testTag("command-results")) {
            val chartHeight = (maxHeight * 0.4f).coerceAtMost(120.dp)
            Column(Modifier.fillMaxSize()) {
                val last = rows.lastOrNull()
                if (last != null && pack?.summary.orEmpty().isNotEmpty()) {
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (property in pack?.summary.orEmpty()) {
                            last[property]?.let { value ->
                                val text = formatted(pack, property, value)
                                Text("${title(property)}: $text" + if (property in pack?.millis.orEmpty() && text != value) " ms" else "", style = Theme.type.small)
                            }
                        }
                    }
                }
                if (traffic.isNotEmpty() && chartHeight >= 48.dp) {
                    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Chart(traffic, Modifier.fillMaxWidth().height(chartHeight), format = ::bits)
                        Legend(traffic, ::bits)
                    }
                }
                val number = TableColumn<IndexedValue<Item>>("#", pack?.columnLabels?.get("#") ?: "#", 40.dp) { "${it.index + 1}" }
                if (columns.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth())
                } else DataTable(
                    rows = visible.withIndex().toList(),
                    columns = listOfNotNull(number.takeIf { pack?.numbered == true }) + columns.map { column ->
                        val width = 180.dp.takeIf { column == "history" && pack?.history != null }
                        TableColumn<IndexedValue<Item>>(column, title(column), width, { cell(pack, column, it) }) { row ->
                            if (lingering(row.value) && column !in flows) "" else row.value[column].orEmpty()
                        }
                    },
                    key = { it.index },
                    tone = { if (lingering(it.value)) RowTone.Faded else RowTone.Normal },
                    follow = true,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                val totals = pack?.totals.orEmpty().mapNotNull { property -> total(visible.filterNot(::lingering), property)?.let { property to it } }
                if (visible.isNotEmpty() && totals.isNotEmpty()) {
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("command-totals"), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for ((property, total) in totals) {
                            Text(stringResource(Res.string.command_total, title(property), formatted(pack, property, total)), style = Theme.type.small)
                        }
                    }
                }
            }
        }
    }

    val compact = LocalCompact.current
    val notice = @Composable { lines: Int ->
        val failed = problem
        if (failed != null) {
            Text(deviceMessage(failed), color = palette.danger, style = Theme.type.small, maxLines = lines)
        } else if (pack?.fasttrack == true) {
            Text(stringResource(Res.string.command_fasttrack), color = palette.warning, style = Theme.type.small, maxLines = lines)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (sections.size > 1) {
                TabStrip(sections.map { it.first }, section.coerceAtMost(sections.lastIndex), { section = it }, Modifier.fillMaxWidth().background(palette.raised), under = palette.surface)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (fieldsOnly) inputs() else VerticalSplit(split, Modifier.fillMaxSize(), first = inputs, second = outputs)
            }
            if (compact) Box(Modifier.fillMaxWidth().background(palette.raised).padding(start = 8.dp, end = 8.dp, top = 4.dp)) { notice(Int.MAX_VALUE) }
            Row(
                Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (columns.isNotEmpty()) {
                    CountPill(pluralStringResource(Res.plurals.list_items, visible.size, visible.size), Modifier.testTag("command-count"))
                }
                Box(Modifier.weight(1f)) {
                    if (!compact) notice(2)
                }
                Button(stringResource(Res.string.command_stop), { running?.cancel() }, enabled = running != null)
                Button(stringResource(Res.string.command_start), { if (tab.shown?.confirm == true) asking = true else start() }, enabled = running == null, primary = true)
            }
        }
        if (asking) {
            Confirm(stringResource(Res.string.command_question, tab.title), tab.title, onCancel = { asking = false }) {
                asking = false
                start()
            }
        }
    }
}

private const val RESTART_MILLIS = 400L

private val consoleOnly =setOf("append", "as-value", "do", "file", "freeze-frame-interval", "proplist", "without-paging")

// traceroute and torch send their whole table again under a new .section, ping and the like a row at a time;
// a flow the newer tables lack stays for three tables, three seconds of torch
internal fun collected(rows: List<Item>, item: Item, flows: List<String> = emptyList()): List<Item> {
    val section = item[".section"] ?: return (rows + item).takeLast(1000)
    if (flows.isEmpty()) return if (rows.lastOrNull()?.get(".section") == section) rows + item else listOf(item)
    val now = section.toIntOrNull() ?: return listOf(item)
    val at = rows.indexOfFirst { row -> flows.all { row[it] == item[it] } }
    val updated = if (at >= 0) rows.toMutableList().apply { set(at, item) } else rows + item
    return updated.filter { row -> row[".section"]?.toIntOrNull()?.let { now - it <= 3 } ?: false }
}

internal fun withHistory(rows: List<Item>, item: Item, property: String, kept: MutableMap<Int, MutableList<String>>): Item {
    val place = rows.count { it[".section"] == item[".section"] }
    val values = kept.getOrPut(place) { mutableListOf() }
    item[property]?.let { values += it }
    if (values.size > 20) values.removeAt(0)
    return item.copy(values = item.values + ("history" to bars(values)))
}

internal fun bars(values: List<String>): String {
    val top = values.mapNotNull { it.toDoubleOrNull() }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    return values.joinToString("") { value -> value.toDoubleOrNull()?.let { levels[(it / top * (levels.length - 1)).roundToInt()].toString() } ?: "×" }
}

private const val levels = "▁▂▃▄▅▆▇█"
