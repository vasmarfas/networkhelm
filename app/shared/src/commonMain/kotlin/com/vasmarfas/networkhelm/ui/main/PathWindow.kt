package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp


import com.composables.icons.lucide.ClipboardCopy
import com.composables.icons.lucide.ClipboardPaste
import com.composables.icons.lucide.Columns3
import com.composables.icons.lucide.Funnel
import com.composables.icons.lucide.Group
import com.composables.icons.lucide.LayoutList
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare


import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RefreshCw

import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Zap
import com.composeunstyled.Text
import com.composeunstyled.UnstyledDropdownMenu
import com.composeunstyled.UnstyledIcon
import com.vasmarfas.networkhelm.app.HiddenValue
import com.vasmarfas.networkhelm.app.LocalAppSettings
import com.vasmarfas.networkhelm.app.LocalSettings
import com.vasmarfas.networkhelm.journal.EditKind
import com.vasmarfas.networkhelm.journal.addCommands
import com.vasmarfas.networkhelm.journal.addScript
import com.vasmarfas.networkhelm.journal.command
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.LINK_LOST
import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.closedBy
import com.vasmarfas.networkhelm.model.lacks
import com.vasmarfas.networkhelm.model.sensitive
import com.vasmarfas.networkhelm.offline.addsOf
import com.vasmarfas.networkhelm.openwrt.RequestOutcomeUnknownException
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.PackAction
import com.vasmarfas.networkhelm.packs.PackFill
import com.vasmarfas.networkhelm.packs.PackLink
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.packs.comboCounts
import com.vasmarfas.networkhelm.packs.withCombos
import com.vasmarfas.networkhelm.packs.withDefaults
import com.vasmarfas.networkhelm.packs.withRadios
import com.vasmarfas.networkhelm.routeros.schema.label
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.atMostEvery
import com.vasmarfas.networkhelm.session.onTheSecond
import com.vasmarfas.networkhelm.session.rounds
import com.vasmarfas.networkhelm.session.rows
import com.vasmarfas.networkhelm.ui.chart.bitRate
import com.vasmarfas.networkhelm.ui.chart.byteCount
import com.vasmarfas.networkhelm.ui.chart.display
import com.vasmarfas.networkhelm.ui.chart.grouped
import com.vasmarfas.networkhelm.ui.chart.halves
import com.vasmarfas.networkhelm.ui.chart.inMilliseconds
import com.vasmarfas.networkhelm.ui.chart.kibiCount
import com.vasmarfas.networkhelm.ui.chart.limit
import com.vasmarfas.networkhelm.ui.files.FilesView
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.Checkbox
import com.vasmarfas.networkhelm.ui.kit.CountPill
import com.vasmarfas.networkhelm.ui.kit.DropdownItem
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.MenuPanel
import com.vasmarfas.networkhelm.ui.kit.MenuSeparator
import com.vasmarfas.networkhelm.ui.kit.Select
import com.vasmarfas.networkhelm.ui.kit.TabStrip
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.kit.ToolbarButton
import com.vasmarfas.networkhelm.ui.kit.copyClipboard
import com.vasmarfas.networkhelm.ui.kit.dialogPanel
import com.vasmarfas.networkhelm.ui.kit.hand
import com.vasmarfas.networkhelm.ui.kit.readText
import com.vasmarfas.networkhelm.ui.kit.rememberCopier
import com.vasmarfas.networkhelm.ui.kit.scrim
import com.vasmarfas.networkhelm.ui.log.LogView
import com.vasmarfas.networkhelm.ui.log.isLog
import com.vasmarfas.networkhelm.ui.table.CommentMode
import com.vasmarfas.networkhelm.ui.table.DataTable
import com.vasmarfas.networkhelm.ui.table.FilterBar
import com.vasmarfas.networkhelm.ui.table.FilterLine
import com.vasmarfas.networkhelm.ui.table.RowTone
import com.vasmarfas.networkhelm.ui.table.SavedSort
import com.vasmarfas.networkhelm.ui.table.SortKey
import com.vasmarfas.networkhelm.ui.table.TableColumn
import com.vasmarfas.networkhelm.ui.table.TableLayout
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.table.TableState
import com.vasmarfas.networkhelm.ui.table.naturalOrder
import com.vasmarfas.networkhelm.ui.table.rowsAsCsv
import com.vasmarfas.networkhelm.ui.table.rowsAsJson
import com.vasmarfas.networkhelm.ui.table.rowsAsTabs
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.theme.ThemeIcon
import com.vasmarfas.networkhelm.ui.theme.tintOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import networkhelm.app.shared.generated.resources.link_lost
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.error_request_outcome_unknown
import networkhelm.app.shared.generated.resources.command_question
import networkhelm.app.shared.generated.resources.command_total
import networkhelm.app.shared.generated.resources.copy_cell
import networkhelm.app.shared.generated.resources.copy_command
import networkhelm.app.shared.generated.resources.copy_csv
import networkhelm.app.shared.generated.resources.copy_json
import networkhelm.app.shared.generated.resources.copy_script
import networkhelm.app.shared.generated.resources.copy_text
import networkhelm.app.shared.generated.resources.filter_all
import networkhelm.app.shared.generated.resources.filter_toggle
import networkhelm.app.shared.generated.resources.form_actions
import networkhelm.app.shared.generated.resources.form_configuration
import networkhelm.app.shared.generated.resources.form_ok
import networkhelm.app.shared.generated.resources.list_cancel
import networkhelm.app.shared.generated.resources.list_categories
import networkhelm.app.shared.generated.resources.list_columns
import networkhelm.app.shared.generated.resources.list_comment
import networkhelm.app.shared.generated.resources.list_comments
import networkhelm.app.shared.generated.resources.list_comments_above
import networkhelm.app.shared.generated.resources.list_comments_column
import networkhelm.app.shared.generated.resources.list_comments_hidden
import networkhelm.app.shared.generated.resources.list_copy
import networkhelm.app.shared.generated.resources.list_detail
import networkhelm.app.shared.generated.resources.list_disable
import networkhelm.app.shared.generated.resources.list_do_not_ask
import networkhelm.app.shared.generated.resources.list_enable
import networkhelm.app.shared.generated.resources.list_find
import networkhelm.app.shared.generated.resources.list_find_next
import networkhelm.app.shared.generated.resources.list_find_previous
import networkhelm.app.shared.generated.resources.list_items
import networkhelm.app.shared.generated.resources.list_items_of
import networkhelm.app.shared.generated.resources.list_live
import networkhelm.app.shared.generated.resources.list_loading_rows
import networkhelm.app.shared.generated.resources.list_move_down
import networkhelm.app.shared.generated.resources.list_move_up
import networkhelm.app.shared.generated.resources.list_new
import networkhelm.app.shared.generated.resources.list_not_loaded
import networkhelm.app.shared.generated.resources.list_open
import networkhelm.app.shared.generated.resources.list_paste
import networkhelm.app.shared.generated.resources.list_paste_failed
import networkhelm.app.shared.generated.resources.list_paste_none
import networkhelm.app.shared.generated.resources.list_paste_question
import networkhelm.app.shared.generated.resources.list_paused
import networkhelm.app.shared.generated.resources.list_remove
import networkhelm.app.shared.generated.resources.list_remove_question
import networkhelm.app.shared.generated.resources.list_retry
import networkhelm.app.shared.generated.resources.list_selected
import networkhelm.app.shared.generated.resources.window_device_mode
import networkhelm.app.shared.generated.resources.window_loading
import networkhelm.app.shared.generated.resources.window_missing
import networkhelm.app.shared.generated.resources.window_not_ready
import networkhelm.app.shared.generated.resources.window_policy
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun PathWindow(
    session: DeviceSession,
    spec: WindowSpec,
    tables: TableLayouts,
    onOpenItem: (String, MenuPath, String?, String?, Map<String, String>, List<String>) -> Unit,
    onOpenWindow: (MenuEntry) -> Unit,
    tabsInTitle: Boolean = false,
    onFit: (Dp) -> Unit = {},
    onClose: () -> Unit,
) {
    var settings by remember(spec) { mutableStateOf(false) }
    val fits by produceState(false, session, spec, spec.tabs, settings) {
        value = settings && spec.tabs.all { tab ->
            try {
                session.outline(tab.path).kind == NodeKind.Settings
            } catch (e: DeviceException) {
                false
            }
        }
    }
    Column(Modifier.fillMaxSize().background(if (Theme.classic) Theme.palette.raised else Theme.palette.surface)) {
        if (spec.selector && !tabsInTitle) Row(Modifier.fillMaxWidth().background(Theme.palette.raised).padding(horizontal = 8.dp, vertical = 4.dp)) { ModeSelect(spec) }
        else if (spec.tabs.size > 1 && !tabsInTitle) TabStrip(spec.tabs.map { it.title }, spec.selected, { spec.selected = it }, Modifier.fillMaxWidth().background(Theme.palette.raised))
        val shown = spec.tabs[spec.selected]
        key(shown.path) { PathView(session, shown, tables, onOpenItem, onOpenWindow, onClose, { settings = true }, if (fits) onFit else NoFit) }
    }
}

@Composable
internal fun ModeSelect(spec: WindowSpec) {
    Select(spec.selected, spec.tabs.indices.toList(), { spec.tabs[it].title }, { spec.selected = it }, Modifier.width(150.dp).testTag("mode"))
}

private val NoFit: (Dp) -> Unit = {}

private const val RECALLED_ROWS = 2000

private class Described(val schema: MenuSchema?, val error: String?, val missing: Boolean = false)

@Composable
private fun PathView(
    session: DeviceSession,
    tab: WindowTab,
    tables: TableLayouts,
    onOpenItem: (String, MenuPath, String?, String?, Map<String, String>, List<String>) -> Unit,
    onOpenWindow: (MenuEntry) -> Unit,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onFit: (Dp) -> Unit,
) {
    val path = tab.path
    session.device.closedBy(path)?.let { property ->
        Text(stringResource(Res.string.window_device_mode, property), Modifier.padding(12.dp), color = Theme.palette.warning)
        return
    }
    val recall = tab.recall
    val defaults = tab.shown?.defaults.orEmpty()
    val combos = tab.shown?.combos.orEmpty()
    val radios = tab.shown?.radios.orEmpty()
    val described by produceState<Described?>(recall?.schemas?.get(path)?.let { Described(it.withDefaults(defaults).withRadios(radios), null) }, session, path) {
        value = try {
            val schema = session.describe(path).withCombos(combos, session.comboCounts(combos))
            recall?.schemas?.set(path, schema)
            Described(schema.withDefaults(defaults).withRadios(radios), null)
        } catch (e: DeviceException) {
            Described(null, e.message, e.category == ErrorCategory.MissingItem && session.device.platform == Platform.RouterOs)
        }
    }
    val result = described
    val schema = result?.schema
    val lacking = session.device.lacks(path)
    when {
        result == null -> Note(stringResource(Res.string.window_loading))
        lacking.isNotEmpty() && (result.missing || schema?.kind == NodeKind.Command) ->
            Text(stringResource(Res.string.window_policy, lacking.joinToString(", ")), Modifier.padding(12.dp), color = Theme.palette.warning)
        result.missing -> Note(stringResource(Res.string.window_missing, path.cli), error = true)
        result.error != null -> Note(result.error, error = true)
        schema?.kind == NodeKind.List && tab.shown.isLog -> LogView(session, tab, tables)
        schema?.kind == NodeKind.List && tab.shown?.files == true -> FilesView(session, tab, schema, tables, onOpenItem, onOpenWindow)
        schema?.kind == NodeKind.List -> ListView(session, tab, schema, tables, onOpenItem, onOpenWindow)
        schema?.kind == NodeKind.Settings -> {
            val spec = remember(tab) { ItemSpec(tab.title, path, null, tab.shown, tab.form, tab.labels) }
            LaunchedEffect(Unit) { onSettings() }
            ItemForm(session, spec, onTitle = {}, onClose = onClose, onCopy = {}, onOpenWindow = onOpenWindow, onFit = onFit)
        }
        schema?.kind == NodeKind.Command -> CommandView(session, tab, schema, onClose)
        else -> Note(stringResource(Res.string.window_not_ready))
    }
}

internal fun formatted(pack: PackPath?, property: String, value: String): String {
    val pairs = pack?.pairs.orEmpty()
    val source = pairs.entries.firstOrNull { property in it.value }?.key ?: property
    return when (source) {
        in pack?.bits.orEmpty() -> halves(value) { it.toDoubleOrNull()?.let(::bitRate) }
        in pack?.limits.orEmpty() -> halves(value) { it.toLongOrNull()?.let(::limit) }
        in pack?.bytes.orEmpty() -> halves(value) { it.toDoubleOrNull()?.let(::byteCount) }
        in pack?.kibi.orEmpty() -> halves(value) { it.toDoubleOrNull()?.let(::kibiCount) }
        in pack?.groups.orEmpty() -> grouped(value)
        in pack?.millis.orEmpty() -> inMilliseconds(value)
        !in pairs -> display(source, value)
        else -> display(source, value).takeIf { it != value } ?: value.replace("/", " / ")
    }
}

internal fun cell(pack: PackPath?, property: String, value: String) = when (value) {
    "true" -> "yes"
    "false" -> "no"
    else -> formatted(pack, property, value).replace("\n", ", ")
}

internal fun halved(row: Item, pairs: Map<String, List<String>>): Item {
    val halves = pairs.flatMap { (pair, names) -> row[pair]?.split('/')?.takeIf { it.size == names.size }?.let(names::zip).orEmpty() }
    return if (halves.isEmpty()) row else row.copy(values = row.values + halves)
}

// a row named as a parent by another is left out: in HTB only the queues at the ends of the tree hold packets
internal fun total(rows: List<Item>, property: String): String? {
    val parents = rows.mapNotNullTo(HashSet()) { it["parent"] }
    val values = rows.filter { it["name"] !in parents }.map { row -> row[property]?.split('/')?.map { it.toLongOrNull() ?: return null } ?: return null }
    val width = values.firstOrNull()?.size ?: return null
    if (values.any { it.size != width }) return null
    return (0 until width).joinToString("/") { half -> values.sumOf { it[half] }.toString() }
}

// listen sends nothing when counters, rates, gauges or timers move, but it reports a changed state
private val moving = Regex(
    "(.*[.-])?(bytes?|packets?|errors?|drops?|dropped|bad|hits|count|total|sent|received|resends|retransmits|changes|messages|tests|" +
        "counter|sect|rates?|bits|second|average|current|load|signal|bw|used|available|free|queues|loss|rtt|jitter|avg|dev|download|upload)" +
        "([.-].*)?|(.+\\.)?(age|uptime|idle-time|last-seen|last-activity|last-handshake|timeout|ttl|time-remaining|session-time-left|" +
        "expires-in|(.+-)?expires-after|http-resp-time|tcp-connect-time)",
)

internal val flagLetters = listOf(
    "disabled" to 'X', "invalid" to 'I', "inactive" to 'I', "dynamic" to 'D', "active" to 'A', "connect" to 'C', "complete" to 'C',
    "static" to 's', "dhcp" to 'd', "published" to 'P', "running" to 'R', "slave" to 'S', "local" to 'L', "external" to 'E',
    "hw-offloaded" to 'H', "link-local" to 'L',
    "bgp" to 'b', "ospf" to 'o', "rip" to 'r', "ecmp" to '+',
    "private-key" to 'K', "crl" to 'L', "authority" to 'A', "issued" to 'I', "revoked" to 'R', "expired" to 'E',
    "paused" to 'P', "blocked" to 'B', "limited" to 'L',
)

internal fun PackPath?.letters(): List<Pair<String, Char>> = this?.flags?.takeIf { it.isNotEmpty() }?.map { it.property to it.letter.first() } ?: flagLetters

internal fun emptyColumns(arguments: List<Argument>): List<String> = arguments.filter { it.name != "comment" && it.name != "disabled" }
    .sortedBy { if (it.name == "name") 0 else if (it.optional) 2 else 1 }
    .map { it.name }
    .take(8)

internal fun switchOf(schema: MenuSchema): Argument? = schema.argument("disabled") ?: schema.argument("enabled")?.takeIf { it.type == ValueType.Flag }

private fun off(item: Item, switch: Argument?) = when (switch?.name) {
    "enabled" -> (item["enabled"] ?: switch.default ?: "1") !in ValueType.Flag.on
    else -> item["disabled"] in ValueType.Flag.on
}

private fun flags(item: Item, switch: Argument?, letters: List<Pair<String, Char>>) =
    letters.filter { (name, _) -> if (name == "disabled") off(item, switch) else item[name] in ValueType.Flag.on }.map { it.second }.joinToString("")

internal fun tone(item: Item, switch: Argument?) = if (off(item, switch)) RowTone.Faded else RowTone.Normal

@Composable
private fun <T> T.heldWhile(paused: Boolean): T {
    val held = remember(paused) { this }
    return if (paused) held else this
}

@Composable
private fun ListView(
    session: DeviceSession,
    tab: WindowTab,
    schema: MenuSchema,
    tables: TableLayouts,
    onOpenItem: (String, MenuPath, String?, String?, Map<String, String>, List<String>) -> Unit,
    onOpenWindow: (MenuEntry) -> Unit,
) {
    val palette = Theme.palette
    val path = tab.path
    val pack = tab.shown
    val older = tab.form?.renamed.orEmpty().filterValues { it in schema.lacks }
    val lacking = if (pack?.join != null || pack?.monitor != null) emptySet() else schema.lacks
    fun named(property: String): String = older[property]?.let { named(it) } ?: tab.labels[property] ?: schema.argument(property)?.label ?: label(property)
    val scope = rememberCoroutineScope()
    val name = path.toString()
    var refresh by remember { mutableIntStateOf(0) }
    val filter = pack?.where?.takeIf { it.isNotEmpty() }?.map { (property, values) -> Filter.Or(values.map { Filter.Equals(property, it) }) }?.let(Filter::And)
    val fixed = pack?.where.orEmpty().filterValues { it.size == 1 }.mapValues { it.value.single() }
    var paused by remember(path) { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var reading by remember(path) { mutableStateOf<ListEvent.Progress?>(null) }
    val up = remember(session) { session.link }.collectAsState(LinkState.Up).value == LinkState.Up
    LaunchedEffect(up) {
        if (up && problem == LINK_LOST) {
            problem = null
            refresh++
        }
    }
    val recall = tab.recall
    val recalled = "$name|$filter"
    val arrived = remember(session, path, refresh) {
        session.watch(path, filter).onEach { reading = it as? ListEvent.Progress }.rows().drop(1).atMostEvery(150.milliseconds).catch {
            reading = null
            (it as? DeviceException)?.let { e -> problem = e.message }
        }
    }.collectAsState(recall?.rows?.get(recalled)).value
    if (arrived != null && arrived.size <= RECALLED_ROWS) SideEffect { recall?.rows?.set(recalled, arrived) }
    val rows = arrived.orEmpty().heldWhile(paused)
    val saved = remember(name) { tables[name] }
    val state = remember(name) {
        TableState<String>().apply {
            sort = saved?.sort?.map { SortKey(it.column, it.ascending) } ?: listOfNotNull(pack?.sort?.let { SortKey(it, true) })
            saved?.widths?.forEach { (column, width) -> widths[column] = width.dp }
        }
    }
    val first = rows.firstOrNull()?.values?.keys.orEmpty()
    var chosen by remember(name) { mutableStateOf(saved?.columns?.takeIf { it.isNotEmpty() }) }
    val visible = chosen ?: pack?.columns?.map { column -> older.entries.firstOrNull { it.value == column }?.key ?: column }?.filter { it !in lacking }?.takeIf { it.isNotEmpty() }
        ?: first.filter { name -> name != "comment" && pack.letters().none { it.first == name } }.take(8).ifEmpty { emptyColumns(schema.arguments) }
    val pairs = pack?.pairs.orEmpty()
    val available = (visible + pack?.columns.orEmpty() + pack?.readonly.orEmpty() + first + schema.arguments.map { it.name } + pairs.values.flatten())
        .distinct().filter { it !in lacking }
    val compact = LocalCompact.current
    val classic = Theme.classic
    var detail by remember(name) { mutableStateOf(saved?.detail ?: compact) }
    var categories by remember(name) { mutableStateOf(saved?.categories == true) }
    var comments by remember(name) { mutableStateOf(saved?.comments ?: CommentMode.Above) }
    LaunchedEffect(name) {
        snapshotFlow {
            TableLayout(chosen.orEmpty(), state.widths.mapValues { it.value.value }, state.sort.map { SavedSort(it.column, it.ascending) }, detail, categories, comments)
        }.drop(1).collect { tables[name] = it }
    }
    val query = remember { TextFieldState() }
    val find = remember { FocusRequester() }
    val table = remember { FocusRequester() }
    var removing by remember { mutableStateOf<List<String>?>(null) }
    var commenting by remember { mutableStateOf<TextFieldState?>(null) }
    var confirming by remember { mutableStateOf<Pair<PackAction, List<String>>?>(null) }
    var output by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pasting by remember { mutableStateOf<List<Map<String, String>>?>(null) }
    val clipboard = copyClipboard()
    val copier = rememberCopier()
    var pointed by remember { mutableStateOf<Pair<String, String>?>(null) }
    val join = pack?.join
    val joined = remember(session, join) {
        if (join == null) flowOf(emptyList()) else session.watch(MenuPath.parse(join)).rows().atMostEvery(150.milliseconds).catch { }
    }.collectAsState(emptyList()).value.heldWhile(paused)
    val monitor = pack?.monitor
    val names = if (monitor == null) emptyList() else rows.mapNotNull { it["name"] }
    var monitored by remember(path) { mutableStateOf<Map<String, Map<String, String>>>(emptyMap()) }
    LaunchedEffect(session, monitor, names, up) {
        val argument = monitor?.argument
        if (monitor == null || argument == null || names.isEmpty() || !up) return@LaunchedEffect
        val command = MenuPath.parse(monitor.command)
        try {
            session.run(command.parent ?: MenuPath.Root, command.segments.last(), mapOf(argument to names.joinToString(",")))
                .rounds { it["name"] }.onTheSecond().collect { if (!paused) monitored = it }
        } catch (e: DeviceException) {
            problem = e.message
        }
    }
    val halfOf = pairs.flatMap { (pair, halves) -> halves.map { it to pair } }.toMap()
    val counters = (visible.map { halfOf[it] ?: it } + pack?.totals.orEmpty()).distinct()
        .filter { it in pack?.readonly.orEmpty() && moving.matches(it) && (monitor == null || it !in monitored.values.firstOrNull().orEmpty()) }
    var live by remember(path) { mutableStateOf<Map<String, Map<String, String>>>(emptyMap()) }
    LaunchedEffect(session, path, counters, up) {
        if (counters.isEmpty() || !up) return@LaunchedEffect
        try {
            session.counters(path, counters, filter).onTheSecond().collect { if (!paused) live = it }
        } catch (e: DeviceException) {
            problem = e.message
        }
    }
    val quick = pack?.quick
    var picked by remember(name) { mutableStateOf<String?>(null) }
    val lines = remember(name) { mutableStateListOf<FilterLine>() }
    val conditions = lines.map { it.condition() }
    val text = query.text.toString().trim()
    fun merged(): List<Item> {
        val byName = joined.associateBy { it["name"] }
        return rows.map { row ->
            val name = row["name"] ?: row[".name"]
            val extra = name?.let { byName[it]?.values }.orEmpty()
            val now = live[row.id].orEmpty() + name?.let { monitored[it] }.orEmpty()
            halved(if (extra.isEmpty() && now.isEmpty()) row else row.copy(values = extra + row.values + now), pairs)
        }.filter { row ->
            (quick == null || picked == null || row[quick]?.split(',')?.contains(picked) == true) &&
                conditions.all { it.matches(row[it.column]) } &&
                (text.isEmpty() || (visible + "comment").any { column ->
                    row[column]?.let { value -> value.contains(text, ignoreCase = true) || schema.argument(column)?.labels?.get(value)?.contains(text, ignoreCase = true) == true } == true
                })
        }
    }
    val filtered = if (rows.size <= 1000) {
        remember(rows, joined, live, monitored, picked, conditions, text, visible) { merged() }
    } else {
        produceState(rows, rows, joined, live, monitored, picked, conditions, text, visible) { value = withContext(Dispatchers.Default) { merged() } }.value
    }
    val shown = if (pairs.isEmpty() && joined.isEmpty() && live.isEmpty() && monitored.isEmpty() && picked == null && conditions.isEmpty() && text.isEmpty()) rows else filtered

    fun findStep(direction: Int) {
        if (shown.isEmpty()) return
        val at = shown.indexOfFirst { it.id == state.anchor }
        val row = shown[if (at < 0) (if (direction > 0) 0 else shown.lastIndex) else (at + direction).mod(shown.size)]
        state.selected = setOf(row.id)
        state.anchor = row.id
    }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            problem = try {
                block()
                null
            } catch (e: DeviceException) {
                e.message
            }
        }
    }
    val canWrite = session.device.canWrite && session.device.lacks(tab.path).isEmpty()
    val switch = switchOf(schema)
    val canSwitch = canWrite && switch?.readOnly == false
    val canRemove = canWrite && "remove" in schema.commands
    val canAdd = canWrite && "add" in schema.commands
    val kinds by produceState(emptyList<String>(), session, pack) {
        if (canAdd || !canWrite) return@produceState
        val offered = pack?.open?.paths?.values.orEmpty().distinct()
        val adds = offered.map { target ->
            async {
                try {
                    "add" in session.outline(MenuPath.parse(target)).commands
                } catch (e: DeviceException) {
                    e.category != ErrorCategory.MissingItem
                }
            }
        }.awaitAll()
        value = offered.filterIndexed { at, _ -> adds[at] }
    }
    val canComment = canWrite && schema.argument("comment")?.readOnly == false
    val icons = Theme.icons
    val hide = LocalSettings.current.hidePasswords
    val tableColumns = (if (detail && !compact) available - "comment" - pack.letters().map { it.first }.toSet() else visible)
        .let { listed -> if (comments == CommentMode.InColumn && "comment" !in listed && rows.any { !it["comment"].isNullOrEmpty() }) listed + "comment" else listed }
        .mapIndexed { place, column ->
            val argument = schema.argument(column)
            val hidden = hide && (argument?.sensitive() ?: sensitive(column))
            val rate = column.endsWith("bits-per-second") || column in pack?.bits.orEmpty()
            // a row without the option has the default of its form: a rule of fw4 without enabled is enabled
            val flag = argument?.type == ValueType.Flag
            TableColumn<Item>(column, named(column), null, {
                when {
                    hidden && it.isNotEmpty() -> HiddenValue
                    flag && it.isNotEmpty() -> if (it == argument.choices.firstOrNull() || it in ValueType.Flag.on) "yes" else "no"
                    else -> argument?.labels?.get(it) ?: cell(pack, column, it)
                }
            }, widest = if (rate) "1999.9 Mbps" else null, icon = { row, value ->
                icons["value/$column=$value"]?.takeIf { column != pack?.iconBy } ?: pack?.iconBy?.takeIf { place == 0 }?.let { property -> row[property]?.let { icons["value/$property=$it"] } }
            }) { it[column] ?: argument?.default?.takeIf { flag || argument.type is ValueType.Enumeration }.orEmpty() }
        }

    fun comment() {
        commenting = TextFieldState(rows.firstOrNull { it.id == state.anchor }?.get("comment").orEmpty())
    }
    val grouping = pack?.category?.takeIf { categories }
    val ordered = pack?.ordered == true || "move" in schema.commands
    val movable = canWrite && ordered && state.sort.isEmpty() && grouping == null && shown.size == rows.size
    val places = remember(rows) { rows.withIndex().associate { (index, row) -> row.id to "$index" } }
    fun enable(on: Boolean) {
        val switch = switch ?: return
        act { for (id in state.selected) session.set(path, id, mapOf(switch.name to switch.word(if (switch.name == "disabled") !on else on))) }
    }
    val confirm = LocalSettings.current.confirmRemoval
    fun remove(ids: List<String>) {
        if (confirm) removing = ids else act { session.remove(path, ids) }
    }
    val removable = state.selected.isNotEmpty() && rows.none { it.id in state.selected && it["default"] == "true" }

    fun chosenRows() = shown.filter { it.id in state.selected }

    fun copyRows(format: (List<TableColumn<Item>>, List<Item>) -> String) = chosenRows().let { copier.copy(format(tableColumns, it), it.size) }

    fun commandsOf(selection: List<Item>) = addCommands(session.device.platform, path, schema, selection)

    fun copy() {
        val selection = chosenRows()
        val commands = commandsOf(selection)
        if (commands.isEmpty()) copyRows(::rowsAsTabs) else copier.copy(commands, selection.size)
    }

    fun copyScript() {
        val selection = chosenRows()
        copier.copy(addScript(session.device.platform, path, schema, selection), selection.size)
    }

    fun paste() {
        scope.launch {
            val text = try {
                clipboard.readText().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = getString(Res.string.list_paste_failed)
                return@launch
            }
            val adds = addsOf(text).filter { it.first == path }.map { it.second }
            if (adds.isEmpty()) problem = getString(Res.string.list_paste_none) else pasting = adds
        }
    }

    fun reload() {
        problem = null
        refresh++
    }

    fun onKey(event: KeyEvent): Boolean {
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        when {
            ctrl && event.isShiftPressed && event.key == Key.C && state.selected.isNotEmpty() -> copyRows(::rowsAsTabs)
            ctrl && event.key == Key.C && state.selected.isNotEmpty() -> copy()
            ctrl && event.key == Key.V && canAdd && session.device.platform == Platform.RouterOs -> paste()
            event.key == Key.Delete && canRemove && removable -> remove(state.selected.toList())
            (event.key == Key.Insert || ctrl && event.key == Key.N) && canAdd -> onOpenItem(tab.title, path, null, null, fixed, first.toList())
            ctrl && event.key == Key.M && canComment && state.selected.isNotEmpty() -> comment()
            ctrl && event.key == Key.E && canSwitch -> enable(!event.isShiftPressed)
            ctrl && event.key == Key.F -> find.requestFocus()
            event.key == Key.F5 -> reload()
            else -> return false
        }
        return true
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            @Composable
            fun finder(field: Modifier) {
                val keys = Modifier.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Enter || event.key == Key.NumPadEnter -> findStep(if (event.isShiftPressed) -1 else 1)
                        event.key == Key.Escape && text.isNotEmpty() -> {
                            query.clearText()
                            table.requestFocus()
                        }
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
                TextField(query, field.focusRequester(find).then(keys).testTag("find"), placeholder = stringResource(Res.string.list_find))
                if (text.isNotEmpty()) {
                    IconButton(Lucide.ChevronDown, stringResource(Res.string.list_find_next), { findStep(1) }, Modifier.testTag("find-next"), enabled = shown.isNotEmpty())
                    IconButton(Lucide.ChevronUp, stringResource(Res.string.list_find_previous), { findStep(-1) }, Modifier.testTag("find-previous"), enabled = shown.isNotEmpty())
                }
            }
            val search: @Composable (Modifier) -> Unit = { field ->
                if (classic) finder(field)
                if (quick != null) {
                    val values = listOf(null) + rows.flatMap { it[quick]?.split(',').orEmpty() }.filter { it.isNotEmpty() }.distinct().sortedWith(naturalOrder)
                    Select(picked, values, { it ?: stringResource(Res.string.filter_all) }, { picked = it }, Modifier.padding(horizontal = 4.dp).width(130.dp).testTag("quick"))
                }
                if (!classic) finder(field)
            }
            @Composable
            fun removeButton() {
                if (canRemove) ToolbarButton(stringResource(Res.string.list_remove), icons["button/remove"]?.vector, { remove(state.selected.toList()) }, enabled = removable, iconOnly = classic || compact, tint = palette.tintOf(icons["button/remove"]))
            }
            @Composable
            fun filterButton() = IconButton(icons["button/filter"]?.vector ?: Lucide.Funnel, stringResource(Res.string.filter_toggle), {
                if (lines.isEmpty()) lines += FilterLine(visible.firstOrNull() ?: "comment") else lines.clear()
            }, Modifier.testTag("filter"), active = lines.isNotEmpty())
            val actions = pack?.actions.orEmpty().filter { canWrite && it.command in schema.commands }
            fun run(action: PackAction) {
                val ids = state.selected.toList()
                val property = action.output
                when {
                    action.confirm -> confirming = action to ids
                    property != null -> act { output = action.title to session.output(path, action, ids, property) }
                    else -> act { for (arguments in action.runs(ids)) session.run(path, action.command, arguments).collect() }
                }
            }
            fun available(action: PackAction) = !action.selection || state.selected.isNotEmpty()
            val linked = present(session, pack?.links.orEmpty())
            val tools = present(session, pack?.menu.orEmpty()).filter { it.window == null || session.ssh != null }
            val labelled = remember(available, schema, tab.labels) { available.groupBy { named(it).lowercase() } }
            fun filled(link: PackLink) = link.fill.all { fill -> fill.from?.let { labelled[it.lowercase()]?.size == 1 } != false }
            fun valueOf(fill: PackFill, row: Item): String? {
                val from = fill.from ?: return row["name"] ?: row.id
                return labelled[from.lowercase()]?.singleOrNull()?.let { row[it] }
            }
            fun openLink(link: PackLink) {
                val row = chosenRows().singleOrNull()
                val arguments = link.argument?.let { argument -> row?.let { mapOf(argument to (it["name"] ?: it.id)) } }.orEmpty()
                val fill = link.fill.mapNotNull { fill -> row?.let { valueOf(fill, it) }?.takeIf { it.isNotEmpty() }?.let { fill.to to it } }.toMap()
                val start = link.autostart && !link.item && (row != null || link.argument == null && link.fill.isEmpty())
                // an item of this table that starts by itself adds the row at once, as WinBox does, where every field has its value and a column
                val columns = fill.mapNotNull { (label, value) -> labelled[label.lowercase()]?.singleOrNull()?.let { it to value } }
                if (row != null && link.item && link.autostart && canAdd && link.path == name && fill.size == link.fill.size && columns.size == fill.size) {
                    act { session.add(path, columns.toMap()) }
                    return
                }
                onOpenWindow(MenuEntry(link.title, link.path.ifEmpty { null }, window = link.window, arguments = arguments, fill = fill, start = start, item = link.item))
            }
            Row(
                Modifier.fillMaxWidth().background(palette.raised).then(if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canAdd) ToolbarButton(stringResource(Res.string.list_new), icons["button/new"]?.vector, { onOpenItem(tab.title, path, null, null, fixed, first.toList()) }, iconOnly = classic || compact, tint = palette.tintOf(icons["button/new"]))
                if (kinds.isNotEmpty()) {
                    ActionsMenu(stringResource(Res.string.list_new), icons["button/new"]?.vector, kinds, { true }, { label(it.substringAfterLast('/')) }, iconOnly = classic || compact, tint = palette.tintOf(icons["button/new"])) { kind ->
                        onOpenItem(label(kind.substringAfterLast('/')), MenuPath.parse(kind), null, null, emptyMap(), emptyList())
                    }
                }
                if (classic) removeButton()
                if (canSwitch) {
                    ToolbarButton(stringResource(Res.string.list_enable), icons["button/enable"]?.vector, { enable(true) }, enabled = state.selected.isNotEmpty(), iconOnly = classic || compact, tint = palette.tintOf(icons["button/enable"]))
                    ToolbarButton(stringResource(Res.string.list_disable), icons["button/disable"]?.vector, { enable(false) }, enabled = state.selected.isNotEmpty(), iconOnly = classic || compact, tint = palette.tintOf(icons["button/disable"]))
                }
                if (!classic) removeButton()
                if (canComment) ToolbarButton(stringResource(Res.string.list_comment), icons["button/comment"]?.vector, ::comment, enabled = state.selected.isNotEmpty(), iconOnly = classic || compact, tint = palette.tintOf(icons["button/comment"]))
                if (classic) filterButton()
                if (compact && movable) {
                    val at = shown.indices.filter { shown[it].id in state.selected }
                    ToolbarButton(stringResource(Res.string.list_move_up), Lucide.ArrowUp, {
                        act { session.move(path, at.map { shown[it].id }, shown[at.first() - 1].id) }
                    }, enabled = at.isNotEmpty() && at.first() > 0, iconOnly = true)
                    ToolbarButton(stringResource(Res.string.list_move_down), Lucide.ArrowDown, {
                        act { session.move(path, at.map { shown[it].id }, shown.getOrNull(at.last() + 2)?.id) }
                    }, enabled = at.isNotEmpty() && at.last() < shown.lastIndex, iconOnly = true)
                }
                if (compact) {
                    ToolbarButton(stringResource(Res.string.list_copy), Lucide.ClipboardCopy, { copy() }, enabled = state.selected.isNotEmpty(), iconOnly = true)
                    if (canAdd && session.device.platform == Platform.RouterOs) ToolbarButton(stringResource(Res.string.list_paste), Lucide.ClipboardPaste, { paste() }, iconOnly = true)
                }
                when {
                    actions.isEmpty() -> {}
                    classic && !compact -> for (action in actions) {
                        val icon = icons["command/${action.command}"]
                        ToolbarButton(action.title, icon?.vector, { run(action) }, enabled = available(action), tint = palette.tintOf(icon))
                    }
                    actions.size == 1 -> ToolbarButton(actions[0].title, icons["command/${actions[0].command}"]?.vector ?: Lucide.Zap, { run(actions[0]) }, enabled = available(actions[0]), iconOnly = compact)
                    else -> ActionsMenu(stringResource(Res.string.form_actions), Lucide.Zap, actions, ::available, { it.title }, iconOnly = compact, iconOf = { icons["command/${it.command}"] }, onRun = ::run)
                }
                val links = linked + tools.filter { it.global && filled(it) }
                val open: (PackLink) -> Unit = ::openLink
                when {
                    links.isEmpty() -> {}
                    classic && !compact -> for (link in links) ToolbarButton(link.title, null, { open(link) })
                    links.size == 1 -> ToolbarButton(links[0].title, Lucide.Settings, { open(links[0]) }, iconOnly = compact)
                    else -> ActionsMenu(stringResource(Res.string.form_configuration), Lucide.Settings, links, { true }, { it.title }, iconOnly = compact, onRun = open)
                }
                if (!compact) Spacer(Modifier.weight(1f))
                if (!classic) filterButton()
                IconButton(Lucide.LayoutList, stringResource(Res.string.list_detail), { detail = !detail }, Modifier.testTag("detail"), active = detail)
                if (pack?.category != null) {
                    IconButton(Lucide.Group, stringResource(Res.string.list_categories), { categories = !categories }, Modifier.testTag("categories"), active = categories)
                }
                if (schema.argument("comment") != null) {
                    val modes = mapOf(
                        CommentMode.Above to stringResource(Res.string.list_comments_above),
                        CommentMode.InColumn to stringResource(Res.string.list_comments_column),
                        CommentMode.Hidden to stringResource(Res.string.list_comments_hidden),
                    )
                    ActionsMenu(stringResource(Res.string.list_comments), Lucide.MessageSquare, CommentMode.entries, { true }, { modes.getValue(it) }, Modifier.testTag("comments"), active = comments != CommentMode.Above, iconOnly = true, selected = { it == comments }) { comments = it }
                }
                if (!compact) search(Modifier.width(200.dp))
            }
            if (compact) {
                Row(Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) { search(Modifier.weight(1f)) }
            }
            if (lines.isNotEmpty()) FilterBar(lines, available, ::named) { column -> rows.firstNotNullOfOrNull { it[column]?.takeIf(String::isNotEmpty) } }
            // a lambda, not a local function, which Compose would keep with the rows of the composition it was made in
            val openRow: (Item) -> Unit = { row ->
                val owner = pack?.owner
                val target = pack?.open?.let { open -> row[open.by]?.let(open.paths::get) }
                when {
                    owner != null -> row[owner.by]?.let { onOpenItem(owner.title, MenuPath.parse(owner.path), it, null, emptyMap(), emptyList()) }
                    target != null -> onOpenItem(tab.title, MenuPath.parse(target), null, row["name"], emptyMap(), emptyList())
                    else -> onOpenItem(tab.title, path, row.id, null, emptyMap(), emptyList())
                }
            }
            DataTable(
                rows = shown,
                separators = LocalSettings.current.columnLines,
                numbers = if (ordered) { row -> places[row.id].orEmpty() } else null,
                columns = tableColumns,
                key = { it.id },
                state = state,
                onOpen = openRow,
                flags = { flags(it, switch, pack.letters()) },
                tone = { tone(it, switch) },
                caption = { row -> row["comment"]?.takeIf { it.isNotEmpty() && comments == CommentMode.Above }?.let { if (classic) ";;; $it" else it } },
                highlight = text,
                follow = pack?.follow == true,
                detail = detail,
                category = grouping?.let { column -> { row: Item -> row[column].orEmpty() } },
                onMove = if (movable) { ids, before -> act { session.move(path, ids, before) } } else null,
                // the comment column of the in-a-column mode is drawn but not in visible, so a drop onto it points one past the end
                onMoveColumn = { column, at -> chosen = (visible - column).toMutableList().apply { add(at.coerceAtMost(size), column) } },
                onKey = ::onKey,
                onCell = { id, column -> pointed = column?.let { id to it } },
                focus = table,
                picker = { ColumnPicker(available, visible, ::named) { chosen = it } },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                menu = { close ->
                    fun pick(action: () -> Unit) {
                        close()
                        action()
                    }
                    shown.singleOrNull { it.id in state.selected }?.let { row -> MenuLine(stringResource(Res.string.list_open), "Enter") { pick { openRow(row) } } }
                    val offered = if (state.selected.size == 1) (tools.filter { !it.global && filled(it) } + linked.filter { it.argument != null }).distinctBy { it.title to it.path } else emptyList()
                    for (link in offered) MenuLine(link.title, "") { pick { openLink(link) } }
                    if (offered.isNotEmpty()) MenuSeparator()
                    if (commandsOf(chosenRows()).isNotEmpty()) {
                        MenuLine(stringResource(Res.string.copy_command), "Ctrl+C") { pick(::copy) }
                        MenuLine(stringResource(Res.string.copy_script), "") { pick(::copyScript) }
                        MenuLine(stringResource(Res.string.copy_text), "Ctrl+Shift+C") { pick { copyRows(::rowsAsTabs) } }
                    } else {
                        MenuLine(stringResource(Res.string.list_copy), "Ctrl+C") { pick(::copy) }
                    }
                    pointed?.let { (id, column) ->
                        val content = tableColumns.firstOrNull { it.key == column }?.let { target -> shown.firstOrNull { it.id == id }?.let { target.shown(target.value(it)) } }
                        if (!content.isNullOrEmpty()) MenuLine(stringResource(Res.string.copy_cell), "") { pick { copier.copy(content) } }
                    }
                    MenuLine(stringResource(Res.string.copy_csv), "") { pick { copyRows(::rowsAsCsv) } }
                    MenuLine(stringResource(Res.string.copy_json), "") { pick { copyRows(::rowsAsJson) } }
                    val pastes = canAdd && session.device.platform == Platform.RouterOs
                    if (pastes || canSwitch || canComment || canRemove && removable || actions.any(::available)) MenuSeparator()
                    if (pastes) MenuLine(stringResource(Res.string.list_paste), "Ctrl+V") { pick(::paste) }
                    if (canSwitch) {
                        val off = shown.filter { it.id in state.selected }.map { off(it, switch) }.toSet()
                        if (true in off) MenuLine(stringResource(Res.string.list_enable), "Ctrl+E") { pick { enable(true) } }
                        if (false in off) MenuLine(stringResource(Res.string.list_disable), "Ctrl+Shift+E") { pick { enable(false) } }
                    }
                    if (canComment) MenuLine(stringResource(Res.string.list_comment), "Ctrl+M") { pick(::comment) }
                    if (canRemove && removable) MenuLine(stringResource(Res.string.list_remove), "Del") { pick { remove(state.selected.toList()) } }
                    for (action in actions.filter(::available)) MenuLine(action.title, "") { pick { run(action) } }
                },
            )
            problem?.let {
                Row(Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(deviceMessage(it), Modifier.weight(1f).testTag("problem"), style = Theme.type.small, color = palette.danger)
                    if (arrived == null || it == LINK_LOST) ToolbarButton(stringResource(Res.string.list_retry), Lucide.RefreshCw, ::reload, Modifier.testTag("retry"))
                }
            }
            DisableSelection {
                Row(
                    Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val count = when {
                        reading != null -> reading?.let { stringResource(Res.string.list_loading_rows, it.read, it.total) }.orEmpty()
                        arrived == null -> stringResource(if (problem == null) Res.string.window_loading else Res.string.list_not_loaded)
                        shown.size == rows.size -> pluralStringResource(Res.plurals.list_items, shown.size, shown.size)
                        else -> pluralStringResource(Res.plurals.list_items_of, rows.size, shown.size, rows.size)
                    }
                    CountPill(count)
                    if (state.selected.isNotEmpty()) Text(stringResource(Res.string.list_selected, state.selected.size), style = Theme.type.small, color = palette.muted)
                    if (!compact) {
                        for (property in pack?.totals.orEmpty()) {
                            val sum = total(shown, property) ?: continue
                            Text(stringResource(Res.string.command_total, named(property), formatted(pack, property, sum)), Modifier.testTag("total-$property"), style = Theme.type.small, color = palette.muted)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (!session.capabilities.offline) {
                        val live = stringResource(if (paused) Res.string.list_paused else Res.string.list_live)
                        Row(
                            Modifier.toggleable(!paused, role = Role.Switch) { paused = !it }.hand().padding(horizontal = 2.dp)
                                .then(if (compact) Modifier.size(40.dp).semantics { contentDescription = live } else Modifier),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = if (compact) Arrangement.Center else Arrangement.spacedBy(4.dp),
                        ) {
                            UnstyledIcon(if (paused) Lucide.Pause else Lucide.Play, null, Modifier.size(if (compact) 14.dp else 10.dp), tint = if (paused) palette.warning else palette.success)
                            if (!compact) Text(live, style = Theme.type.small, color = if (paused) palette.warning else palette.muted, singleLine = true)
                        }
                    }
                }
            }
        }
        removing?.let { ids ->
            var quiet by remember(ids) { mutableStateOf(false) }
            val store = LocalAppSettings.current
            Confirm(pluralStringResource(Res.plurals.list_remove_question, ids.size, ids.size), stringResource(Res.string.list_remove), onCancel = {
                removing = null
                table.requestFocus()
            }, content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(quiet, { quiet = it }, Modifier.testTag("ask-again"))
                    Text(stringResource(Res.string.list_do_not_ask), Modifier.padding(start = 6.dp).clickable { quiet = !quiet }.hand())
                }
            }) {
                removing = null
                if (quiet) scope.launch { store?.change { it.copy(confirmRemoval = false) } }
                table.requestFocus()
                act { session.remove(path, ids) }
            }
        }
        confirming?.let { (action, ids) ->
            Confirm(stringResource(Res.string.command_question, action.title), action.title, onCancel = { confirming = null }) {
                confirming = null
                act { for (arguments in action.runs(ids)) session.run(path, action.command, arguments).collect() }
            }
        }
        output?.let { (heading, reply) -> Output(heading, reply) { output = null } }
        pasting?.let { adds ->
            Confirm(pluralStringResource(Res.plurals.list_paste_question, adds.size, adds.size), stringResource(Res.string.list_paste), onCancel = { pasting = null }, content = {
                Box(Modifier.heightIn(max = 320.dp).widthIn(max = 640.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(adds.joinToString("\n") { command(session.device.platform, path, EditKind.Add, emptyList(), it) }, style = Theme.type.mono)
                    }
                }
            }) {
                pasting = null
                act { for (values in adds) session.add(path, values) }
            }
        }
        commenting?.let { comment ->
            Confirm(stringResource(Res.string.list_comment), stringResource(Res.string.form_ok), onCancel = { commenting = null }, content = {
                TextField(comment, Modifier.width(320.dp).testTag("comment"))
            }) {
                val ids = state.selected.toList()
                val text = comment.text.toString()
                commenting = null
                act { for (id in ids) session.set(path, id, mapOf("comment" to text)) }
            }
        }
    }
}

// Servers of the NTP client and Adlist of DNS are not on RouterOS 6
@Composable
internal fun present(session: DeviceSession, links: List<PackLink>): List<PackLink> {
    val missing by produceState(emptySet<String>(), session, links) {
        value = links.filter { it.window == null }.map { link ->
            async {
                try {
                    session.outline(MenuPath.parse(link.path))
                    null
                } catch (e: DeviceException) {
                    link.path.takeIf { e.category == ErrorCategory.MissingItem }
                }
            }
        }.awaitAll().filterNotNull().toSet()
    }
    return links.filter { it.path !in missing }
}

@Composable
internal fun <T> ActionsMenu(
    title: String,
    icon: ImageVector?,
    items: List<T>,
    available: (T) -> Boolean,
    named: (T) -> String = { it.toString() },
    modifier: Modifier = Modifier,
    active: Boolean = false,
    iconOnly: Boolean = false,
    tint: Color? = null,
    iconOf: (T) -> ThemeIcon? = { null },
    selected: (T) -> Boolean = { false },
    onRun: (T) -> Unit,
) {
    val palette = Theme.palette
    var open by remember { mutableStateOf(false) }
    UnstyledDropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        panel = {
            MenuPanel {
                for (item in items) {
                    val enabled = available(item)
                    DropdownItem(onClick = {
                        open = false
                        if (enabled) onRun(item)
                    }, selected = selected(item)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val shown = iconOf(item)
                            val colour = if (enabled) palette.text else palette.faint
                            if (shown != null) UnstyledIcon(shown.vector, null, Modifier.padding(end = 8.dp).size(Theme.metrics.icon), tint = palette.tintOf(shown).takeIf { enabled } ?: colour)
                            Text(named(item), color = colour)
                        }
                    }
                }
            }
        },
        anchor = { ToolbarButton(title, icon, { open = true }, modifier, active = active, iconOnly = iconOnly, tint = tint) },
    )
}

@Composable
private fun MenuLine(title: String, keys: String, onClick: () -> Unit) {
    DropdownItem(onClick) {
        Row(Modifier.widthIn(min = 220.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Text(keys, Modifier.padding(start = 16.dp), color = Theme.palette.muted, style = Theme.type.small)
        }
    }
}

@Composable
private fun ColumnPicker(available: List<String>, visible: List<String>, named: (String) -> String, onChange: (List<String>) -> Unit) {
    val palette = Theme.palette
    var open by remember { mutableStateOf(false) }
    UnstyledDropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        panel = {
            MenuPanel {
                for (column in available) {
                    DropdownItem(onClick = { onChange(if (column in visible) visible - column else visible + column) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(14.dp)) { if (column in visible) UnstyledIcon(Lucide.Check, null, Modifier.size(14.dp), tint = palette.accent) }
                            Text(named(column))
                        }
                    }
                }
            }
        },
        anchor = {
            IconButton(Lucide.Columns3, stringResource(Res.string.list_columns), { open = true }, Modifier.testTag("columns"))
        },
    )
}

@Composable
internal fun Confirm(
    question: String,
    action: String,
    onCancel: () -> Unit,
    content: (@Composable () -> Unit)? = null,
    alternative: Pair<String, () -> Unit>? = null,
    onConfirm: () -> Unit,
) {
    val palette = Theme.palette
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val keys = Modifier.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (event.key) {
            Key.Escape -> onCancel()
            Key.Enter, Key.NumPadEnter -> onConfirm()
            else -> return@onKeyEvent false
        }
        true
    }
    Box(Modifier.fillMaxSize().scrim(palette.workspace.copy(alpha = 0.5f)).then(keys).focusRequester(focus).focusable().testTag("confirm"), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp).dialogPanel(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(question)
            content?.invoke()
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(stringResource(Res.string.list_cancel), onCancel)
                alternative?.let { (text, onClick) -> Button(text, onClick) }
                Button(action, onConfirm, primary = true)
            }
        }
    }
}

@Composable
internal fun deviceMessage(text: String): String = when (text) {
    RequestOutcomeUnknownException.MESSAGE -> stringResource(Res.string.error_request_outcome_unknown)
    LINK_LOST -> stringResource(Res.string.link_lost)
    else -> text
}

@Composable
internal fun Note(text: String, error: Boolean = false) {
    Text(deviceMessage(text), Modifier.padding(12.dp).height(Theme.metrics.row).testTag(if (error) "problem" else "note"), color = if (error) Theme.palette.danger else Theme.palette.muted)
}
