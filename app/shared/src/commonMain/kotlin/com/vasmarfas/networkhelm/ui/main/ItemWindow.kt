package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ClipboardCopy
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Files
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.SquareTerminal
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Zap
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.journal.EditKind
import com.vasmarfas.networkhelm.journal.addCommands
import com.vasmarfas.networkhelm.journal.command
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.app.HiddenValue
import com.vasmarfas.networkhelm.app.LocalSettings
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.LINK_LOST
import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.lacks
import com.vasmarfas.networkhelm.model.sensitive
import com.vasmarfas.networkhelm.packs.CHANGELOG_FIELD
import com.vasmarfas.networkhelm.packs.GROUP_LINE
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.PackAction
import com.vasmarfas.networkhelm.packs.PackCondition
import com.vasmarfas.networkhelm.packs.PackForm
import com.vasmarfas.networkhelm.packs.PackFormTab
import com.vasmarfas.networkhelm.packs.PackLink
import com.vasmarfas.networkhelm.packs.PackMonitor
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.packs.hides
import com.vasmarfas.networkhelm.packs.withDefaults
import com.vasmarfas.networkhelm.packs.withRadios
import com.vasmarfas.networkhelm.routeros.RouterOsVersion
import com.vasmarfas.networkhelm.routeros.highlight
import com.vasmarfas.networkhelm.routeros.schema.label
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.onTheSecond
import com.vasmarfas.networkhelm.session.rows
import com.vasmarfas.networkhelm.session.watchSettings
import com.vasmarfas.networkhelm.ui.chart.Chart
import com.vasmarfas.networkhelm.ui.chart.Legend
import com.vasmarfas.networkhelm.ui.chart.Ring
import com.vasmarfas.networkhelm.ui.chart.Series
import com.vasmarfas.networkhelm.ui.chart.bits
import com.vasmarfas.networkhelm.ui.chart.display
import com.vasmarfas.networkhelm.ui.chart.limit
import com.vasmarfas.networkhelm.ui.form.FieldEditor
import com.vasmarfas.networkhelm.ui.form.FormState
import com.vasmarfas.networkhelm.ui.form.LocalScripts
import com.vasmarfas.networkhelm.ui.form.NegationToggle
import com.vasmarfas.networkhelm.ui.form.ScriptSupport
import com.vasmarfas.networkhelm.ui.form.formTitle
import com.vasmarfas.networkhelm.ui.form.hint
import com.vasmarfas.networkhelm.ui.form.misfits
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.Checkbox
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.LocalSubmit
import com.vasmarfas.networkhelm.ui.kit.TabStrip
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.kit.ToolbarButton
import com.vasmarfas.networkhelm.ui.kit.rememberCopier
import com.vasmarfas.networkhelm.ui.kit.sunken
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.theme.on
import com.vasmarfas.networkhelm.ui.theme.tintOf
import com.vasmarfas.networkhelm.ui.workspace.VerticalScrollbar
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.command_question
import networkhelm.app.shared.generated.resources.copy_item_command
import networkhelm.app.shared.generated.resources.copy_list
import networkhelm.app.shared.generated.resources.copy_value
import networkhelm.app.shared.generated.resources.form_actions
import networkhelm.app.shared.generated.resources.form_apply
import networkhelm.app.shared.generated.resources.form_close
import networkhelm.app.shared.generated.resources.form_command
import networkhelm.app.shared.generated.resources.form_command_question
import networkhelm.app.shared.generated.resources.form_configuration
import networkhelm.app.shared.generated.resources.form_confirm
import networkhelm.app.shared.generated.resources.form_copy
import networkhelm.app.shared.generated.resources.form_expected
import networkhelm.app.shared.generated.resources.form_invalid
import networkhelm.app.shared.generated.resources.form_mismatch
import networkhelm.app.shared.generated.resources.form_new
import networkhelm.app.shared.generated.resources.form_ok
import networkhelm.app.shared.generated.resources.form_on_router
import networkhelm.app.shared.generated.resources.form_only_with
import networkhelm.app.shared.generated.resources.form_reset
import networkhelm.app.shared.generated.resources.form_reset_question
import networkhelm.app.shared.generated.resources.form_set
import networkhelm.app.shared.generated.resources.form_unset
import networkhelm.app.shared.generated.resources.list_cancel
import networkhelm.app.shared.generated.resources.list_remove
import networkhelm.app.shared.generated.resources.list_remove_question
import networkhelm.app.shared.generated.resources.output_copy
import networkhelm.app.shared.generated.resources.window_loading

@Composable
fun ItemForm(
    session: DeviceSession,
    spec: ItemSpec,
    onTitle: (String) -> Unit,
    onClose: () -> Unit,
    onCopy: (Map<String, String>) -> Unit,
    onOpenWindow: (MenuEntry) -> Unit,
    onFit: (Dp) -> Unit = {},
) {
    val palette = Theme.palette
    val scope = rememberCoroutineScope()
    // a row opened from a list shows what the list read until the router answers the window; a window that comes back into sight goes on with its edits
    val form = remember(spec) {
        spec.edits ?: FormState(if (spec.listed.isEmpty()) emptyMap() else derived(spec.shown, spec.listed)).apply { spec.prefill.forEach { (name, value) -> edit(name, value) } }.also { spec.edits = it }
    }
    var schema by remember { mutableStateOf<MenuSchema?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val confirming = remember(spec) { mutableStateMapOf<String, String>() }
    var removing by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<PackAction?>(null) }
    var output by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showing by remember { mutableStateOf(false) }
    val copier = rememberCopier()
    var have by remember { mutableIntStateOf(0) }
    var need by remember { mutableIntStateOf(0) }
    var buttons by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(have, need, buttons, onFit) {
        if (have > 0) onFit(with(density) { (maxOf(need, buttons) - have).toDp() })
    }
    // what the dialog asks of the router stops with the link and starts again when the link is back
    val linked = remember(session) { session.link }.collectAsState(LinkState.Up).value == LinkState.Up
    LaunchedEffect(linked) { if (linked && problem == LINK_LOST) problem = null }
    LaunchedEffect(session, spec.path, linked) {
        if (!linked) return@LaunchedEffect
        try {
            val described = session.describe(spec.path).withDefaults(spec.shown?.defaults.orEmpty()).withRadios(spec.shown?.radios.orEmpty())
            if (spec.id == null && spec.name == null && spec.prefill.isEmpty()) described.prefill.forEach { (field, value) -> if (!form.touched(field)) form.edit(field, value) }
            schema = described
        } catch (e: DeviceException) {
            problem = e.message
        }
    }
    val kind = schema?.kind
    val id = spec.id
    val lookup = spec.name
    val confirm = LocalSettings.current.confirmRemoval
    fun remove() {
        val element = id ?: return
        scope.launch {
            try {
                session.remove(spec.path, listOf(element))
                onClose()
            } catch (e: DeviceException) {
                problem = e.message
            }
        }
    }
    fun reset() {
        scope.launch {
            problem = try {
                session.run(spec.path, "reset", listOfNotNull(id?.takeIf { it.isNotEmpty() }?.let { "numbers" to it }).toMap()).collect()
                null
            } catch (e: DeviceException) {
                e.message
            }
        }
    }
    // RouterOS describes the fields of a menu in alphabetical order and prints them in its own
    val ordered = spec.form == null && session.device.platform == Platform.RouterOs
    val fresh = remember(spec) { spec.id == null && spec.name == null }
    var printed by remember(spec) { mutableStateOf(spec.listed.keys.toList().takeIf { it.isNotEmpty() }) }
    // kept over a watch that starts again: a row that went while the link was down closes the dialog as well
    var seen by remember(spec) { mutableStateOf(spec.listed.isNotEmpty()) }
    LaunchedEffect(session, spec.path, id, kind == NodeKind.Settings, linked) {
        val updates = when {
            !linked -> return@LaunchedEffect
            kind == NodeKind.Settings -> session.watchSettings(spec.path).map { listOf(it) }
            id != null -> session.watch(spec.path, Filter.Equals(".id", id)).rows().drop(1)
            lookup != null -> session.watch(spec.path, Filter.Equals("name", lookup)).rows().drop(1)
            else -> return@LaunchedEffect
        }
        try {
            updates.onTheSecond().collect { rows ->
                val item = rows.firstOrNull()
                if (printed == null) printed = item?.values?.keys?.toList().orEmpty()
                if (item != null) {
                    seen = true
                    if (spec.id == null) spec.id = item.id
                    form.update(derived(spec.shown, item.values))
                } else if (seen) {
                    onClose()
                }
            }
        } catch (e: DeviceException) {
            printed = printed ?: emptyList()
            problem = e.message
        }
    }

    // a field of this kind goes to the router when it changes, and stays as typed until the router says the same
    val autoset = spec.shown?.autoset.orEmpty()
    LaunchedEffect(form, autoset) {
        if (autoset.isEmpty()) return@LaunchedEffect
        snapshotFlow { form.changes().filterKeys { it in autoset } }.filter { it.isNotEmpty() }.collect { changes ->
            problem = try {
                session.set(spec.path, if (schema?.kind == NodeKind.Settings) null else spec.id, changes)
                null
            } catch (e: DeviceException) {
                val now = form.changes()
                form.discard(changes.filter { (name, sent) -> now[name] == sent }.keys)
                e.message
            }
        }
    }
    LaunchedEffect(form.router) { form.settle(autoset) }

    val related = remember(spec) { spec.form?.tabs.orEmpty().mapNotNull { it.path }.distinct().associateWith { Related(MenuPath.parse(it)) } }
    val name = form.value("name")
    for (source in related.values) {
        LaunchedEffect(source, name, linked) {
            if (name == null || !linked) return@LaunchedEffect
            try {
                source.schema = session.describe(source.path)
                session.watch(source.path, Filter.Equals("name", name)).rows().collect { rows ->
                    rows.firstOrNull()?.let {
                        source.id = it.id
                        source.form.update(it.values)
                    }
                }
            } catch (e: DeviceException) {
                problem = e.message
            }
        }
    }
    // listen says nothing when counters move, so the read-only fields are printed again every second
    val formTabs = spec.form?.tabs.orEmpty()
    val counted = formTabs.filter { it.path == null && it.monitor == null }.flatMap { it.fields }.filter { it in spec.shown?.readonly.orEmpty() }
    LaunchedEffect(session, spec.path, id, counted, linked) {
        val element = id ?: return@LaunchedEffect
        if (counted.isEmpty() || !linked) return@LaunchedEffect
        try {
            session.counters(spec.path, counted, element.takeIf { it.isNotEmpty() }?.let { Filter.Equals(".id", it) }).onTheSecond()
                .collect { round -> round[element]?.let { form.update(form.router + it) } }
        } catch (e: DeviceException) {
            problem = e.message
        }
    }
    val watched = formTabs.firstOrNull { it.monitor != null && "status" in it.fields }
    val monitored = watched?.monitor?.takeIf { id != null || it.argument == null }?.let { monitor -> reported(session, monitor, name ?: id, linked) { problem = it } }.orEmpty()
    for ((path, source) in related) {
        val fields = formTabs.filter { it.path == path }.flatMap { it.properties }.filter { source.schema?.argument(it)?.readOnly != false }
        LaunchedEffect(source, source.id, fields, linked) {
            val element = source.id ?: return@LaunchedEffect
            if (fields.isEmpty() || !linked) return@LaunchedEffect
            try {
                session.counters(source.path, fields, Filter.Equals(".id", element)).onTheSecond()
                    .collect { round -> round[element]?.let { source.form.update(source.form.router + it) } }
            } catch (e: DeviceException) {
                problem = e.message
            }
        }
    }

    val template = spec.form?.title?.let { title -> spec.shown?.element?.let { title.replaceBefore(" <", it, it) } ?: title }
    val title = when {
        kind == NodeKind.Settings -> spec.title
        id == null && lookup == null -> stringResource(Res.string.form_new, template?.substringBefore(" <") ?: singular(spec.title))
        template != null -> formTitle(template, form::value)
        else -> "${singular(spec.title)} <${form.value("name") ?: form.value("address") ?: id}>"
    }
    LaunchedEffect(title) { onTitle(title) }

    val order = if (fresh && kind != NodeKind.Settings) (spec.prefill.keys + spec.order).toList() else printed
    val current = schema
    if (current == null || ordered && order == null) {
        Text(problem?.let { deviceMessage(it) } ?: stringResource(Res.string.window_loading), Modifier.padding(12.dp).testTag(if (problem != null) "problem" else "note"), color = if (problem != null) palette.danger else palette.muted)
        return
    }

    fun changed() = form.changes().isNotEmpty() || related.values.any { it.id != null && it.form.changes().isNotEmpty() }

    val creating = id == null && lookup == null && kind != NodeKind.Settings
    val pairNames = spec.shown?.pairs.orEmpty().keys

    // only what the user typed stops the dialog: a value the router sent stays as it is
    fun misfit() = current.arguments.any { argument ->
        argument.name !in pairNames && form.touched(argument.name) && argument.applies(form, current) && form.value(argument.name)?.let(argument::misfits) == true
    }

    val mismatch = stringResource(Res.string.form_mismatch)
    fun unconfirmed() = spec.form?.confirm.orEmpty().any { name -> name in form.changes() && confirming[name].orEmpty() != form.value(name).orEmpty() }

    fun defaults() = current.arguments.filter { argument ->
        !argument.default.isNullOrEmpty() && argument.type != ValueType.Flag && argument.name !in spec.shown?.defaults.orEmpty() &&
            form.value(argument.name) == null && argument.applies(form, current)
    }.associate { it.name to it.default.orEmpty() }

    val platform = session.device.platform
    fun commands(): String = buildList {
        val changes = form.changes()
        val target = spec.id
        when {
            changes.isEmpty() -> Unit
            current.kind == NodeKind.Settings -> add(command(platform, spec.path, EditKind.Set, emptyList(), changes))
            target != null -> add(command(platform, spec.path, EditKind.Set, listOf(target), changes))
            else -> add(command(platform, spec.path, EditKind.Add, emptyList(), defaults() + changes.filterValues { it != null }))
        }
        for (source in related.values) {
            val other = source.form.changes()
            val id = source.id ?: continue
            if (other.isNotEmpty()) add(command(platform, source.path, EditKind.Set, listOf(id), other))
        }
    }.joinToString("\n")

    fun copyCommand() {
        val values = current.arguments.printedFirst(order.orEmpty()) { it.name }.mapNotNull { argument -> form.value(argument.name)?.let { argument.name to it } }.toMap()
        copier.copy(addCommands(platform, spec.path, current, listOf(Item(spec.id.orEmpty(), values))))
    }

    suspend fun apply(): Boolean {
        // a second press that came before the first one took the buttons away
        if (busy) return false
        if (!changed() && !(creating && spec.id == null)) return true
        if (unconfirmed()) {
            problem = mismatch
            return false
        }
        busy = true
        return try {
            val changes = form.changes()
            val target = spec.id
            when {
                changes.isEmpty() && !(creating && target == null) -> Unit
                current.kind == NodeKind.Settings -> session.set(spec.path, null, changes)
                target != null -> session.set(spec.path, target, changes)
                else -> spec.id = session.add(spec.path, defaults() + changes.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())
            }
            form.applied(changes)
            for (source in related.values) {
                val other = source.form.changes()
                if (other.isEmpty()) continue
                session.set(source.path, source.id ?: continue, other)
                source.form.applied(other)
            }
            problem = null
            true
        } catch (e: DeviceException) {
            problem = e.message
            false
        } finally {
            busy = false
        }
    }

    fun run(action: PackAction) {
        scope.launch {
            problem = try {
                val property = action.output
                if (property != null) output = action.title to session.output(spec.path, action, listOfNotNull(id), property)
                else for (arguments in action.runs(listOfNotNull(id))) session.run(spec.path, action.command, arguments).collect()
                null
            } catch (e: DeviceException) {
                e.message
            }
        }
    }

    fun act(action: PackAction) {
        if (action.confirm) asking = action else run(action)
    }

    val possible = tabsOf(spec, current, form, order.takeIf { ordered }.orEmpty(), session.device.version).filter { !it.readonly || current.kind == NodeKind.Settings || id != null || lookup != null }
    val tabs = possible.filter { tab -> tab.monitor != null || tab.path != null || tab.fields.any { current.argument(it)?.applies(form, current) ?: (it in form.router) } }.ifEmpty { possible }
    var selected by remember { mutableIntStateOf(0) }
    var also by remember { mutableStateOf(emptySet<Int>()) }
    val stacked = tabs.isNotEmpty() && tabs.all { it.inline }
    val shown = if (stacked) tabs.indices.toList() else (setOf(selected.coerceIn(tabs.indices)) + also.filter { it in tabs.indices }).sorted()
    val inList = current.kind == NodeKind.List && id != null
    val writes = if (current.kind == NodeKind.Settings || id != null || lookup != null) "set" else "add"
    val locked = form.router["dynamic"] == "true" || !session.device.canWrite || session.device.lacks(spec.path).isNotEmpty() || writes !in current.commands
    // a window of the object model has no console command to show
    val native = spec.shown?.native != null
    // a window whose editable fields all go to the router at once has nothing left for OK, Apply and Cancel
    val saves = !locked && (autoset.isEmpty() || tabs.any { tab -> tab.fields.any { name -> name !in autoset && current.argument(name)?.readOnly == false } })

    val scripts = remember(session) {
        ScriptSupport(if (session.device.platform == Platform.RouterOs && session.capabilities.menuContents) { text -> highlight(session, text) } else null)
    }
    val labelColumn = remember(spec) { LabelColumn() }
    val accept by rememberUpdatedState<() -> Unit> { if (!busy && !misfit()) scope.launch { if (apply()) onClose() } }
    val submit = remember(spec) { { accept() } }
    val firstFocus = remember(spec) { FirstFocus() }
    CompositionLocalProvider(LocalScripts provides scripts, LocalLabelColumn provides labelColumn, LocalSubmit provides submit) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(if (Theme.classic) palette.raised else palette.surface)) {
                if (tabs.size > 1 && !stacked) {
                    TabStrip(
                        tabs.map { it.title }, selected, {
                            selected = it
                            also = emptySet()
                        },
                        Modifier.fillMaxWidth().background(palette.raised), also, onAdd = { also = if (it in also) also - it else also + it }, under = palette.surface,
                    )
                }
                val compact = LocalCompact.current
                val classic = Theme.classic && !compact
                val split: @Composable (Modifier, @Composable (Modifier) -> Unit) -> Unit = { modifier, content ->
                    if (compact) {
                        Column(modifier) { content(Modifier.weight(1f)) }
                    } else {
                        Row(modifier) { content(Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
                split(Modifier.weight(1f).fillMaxWidth()) { fields ->
                    val actions = spec.shown?.actions.orEmpty().filter { it.command in current.commands && (!it.selection || id != null) && it.depends.hold(form, current) && (it.tabs.isEmpty() || shown.any { index -> tabs[index].title in it.tabs }) }
                    val links = present(session, spec.shown?.links.orEmpty()).filter { it.depends.hold(form, current) }
                    fun open(link: PackLink) {
                        val element = form.value("name") ?: id
                        onOpenWindow(MenuEntry(link.title, link.path, window = link.window, arguments = link.argument?.let { argument -> element?.let { mapOf(argument to it) } }.orEmpty()))
                    }
                    fun besideOf(field: String): (@Composable () -> Unit)? {
                        val own = actions.filter { it.beside == field }
                        val opens = links.filter { it.beside == field }
                        if (own.isEmpty() && opens.isEmpty()) return null
                        return { PlacedButtons(own, opens, session.device.canWrite, ::act, ::open) }
                    }
                    @Composable
                    fun below(field: String) {
                        val own = actions.filter { it.below == field }
                        val opens = links.filter { it.below == field }
                        val rows = (own.map { it.row } + opens.map { it.row }).distinct().sorted()
                        rows.forEachIndexed { at, row ->
                            if (at > 0 && row - rows[at - 1] > 1) ButtonsLine()
                            PlacedButtons(own.filter { it.row == row }, opens.filter { it.row == row }, session.device.canWrite, ::act, ::open, Modifier.padding(start = fieldStart(), end = 12.dp))
                        }
                    }
                    // the scroll hands its own height down as the least, and the fields would never seem to want less
                    val scrolled = rememberScrollState()
                    Box(fields.onSizeChanged { have = it.height }) {
                        Column(Modifier.fillMaxWidth().verticalScroll(scrolled).wrapContentHeight(Alignment.Top).onSizeChanged { need = it.height }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            var above = false
                            var line = false
                            var paired = false
                            if (0 in shown) {
                                current.argument("disabled")?.let { disabled ->
                                    val enabled = form.value("disabled") !in ValueType.Flag.on
                                    val toggle = { on: Boolean -> form.edit("disabled", disabled.word(!on)) }
                                    val editable = !locked && !disabled.readOnly
                                    if (classic) {
                                        FieldLine("", form.touched("disabled"), false) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(enabled, toggle, enabled = editable)
                                                Text("Enabled", Modifier.padding(start = 6.dp).clickable(enabled = editable) { toggle(!enabled) }, style = Theme.type.body)
                                            }
                                        }
                                    } else {
                                        FieldLine("Enabled", form.touched("disabled"), false) { Checkbox(enabled, toggle, enabled = editable) }
                                    }
                                }
                                current.argument("comment")?.let { if (locked || it.readOnly) ReadOnlyRow(it.name, spec.labels, form.value(it.name)) else FieldRow(it, spec.labels, form) }
                                above = current.argument("disabled") != null || current.argument("comment") != null
                                line = above
                            }
                            for (index in shown) {
                                val tab = tabs[index]
                                if (stacked) {
                                    SectionHeading(tab.title)
                                    above = false
                                    line = false
                                } else if (shown.size > 1) {
                                    Text(tab.title, Modifier.padding(start = 12.dp, top = 6.dp), style = Theme.type.strong, color = palette.muted)
                                    above = false
                                }
                                val source = tab.path?.let(related::get)
                                val owner = source?.form ?: form
                                val reported = if (tab === watched) monitored else tab.monitor?.let { monitor -> reported(session, monitor, name ?: id, linked) { problem = it } }.orEmpty()
                                if (tab.chart) Rates(listOf(reported["tx-bits-per-second"], reported["rx-bits-per-second"]), listOf("Tx", "Rx"))
                                tab.graph?.let { graph ->
                                    val halves = spec.shown?.pairs?.get(graph).orEmpty().map { spec.labels[it] ?: label(it) }
                                    Rates(owner.value(graph)?.split('/').orEmpty(), halves)
                                }
                                val prefix = tab.title.lowercase() + "."
                                // an old name of a field reads as the new one: address of an IP service before 7.24 is Available From
                                val renamed = spec.form?.renamed.orEmpty().mapValues { (_, new) -> spec.labels[new] ?: label(new) }
                                val labels = tab.fields.filter { it.startsWith(prefix) }.associateWith { label(it.removePrefix(prefix)) } + renamed + spec.labels
                                fun live(field: String) = (reported[field] ?: owner.value(field))?.let { display(field, it) }
                                val described = if (source != null) source.schema else current
                                val gates = spec.form?.gates { described?.argument(it) != null || owner.value(it) != null }.orEmpty()
                                for ((field, rx) in if (tab.monitor != null) txRx(tab.fields) else tab.fields.map { it to null }) {
                                    if (field == GROUP_LINE) {
                                        line = true
                                        continue
                                    }
                                    if (field == CHANGELOG_FIELD && source == null) {
                                        if (above && line) GroupLine()
                                        above = true
                                        line = false
                                        ChangelogBox(owner.value(field), owner.value("latest-version"), fetchable = !native)
                                        continue
                                    }
                                    val argument = described?.argument(field)
                                    if (argument?.applies(owner, described) == false) continue
                                    if (gates.hides(field, owner::value)) continue
                                    val limit = spec.shown?.gauges?.get(field)
                                    val editable = argument != null && !argument.readOnly && !(argument.fixed && id != null) && !tab.readonly && !locked
                                    if (limit == null && rx == null && tab.monitor == null && !editable && owner.value(field) == null) continue
                                    if (above && line) GroupLine()
                                    above = true
                                    line = false
                                    val halves = spec.shown?.pairs?.get(field)?.takeIf { pair ->
                                        limit == null && rx == null && tab.monitor == null && (editable || owner.value(field)?.split('/')?.size == pair.size)
                                    }
                                    if (halves != null && !paired) PairHeader(halves, labels)
                                    paired = halves != null
                                    when {
                                        limit != null -> GaugeRow(field, labels, owner.value(field), owner.value(limit), (current.argument(limit)?.type as? ValueType.Integer)?.unit)
                                        rx != null -> {
                                            val tx = labels[field] ?: label(field)
                                            val values = listOf(live(field), live(rx))
                                            ReadOnlyRow(field, labels, values.takeIf { it.any { value -> value != null } }?.joinToString(" / ") { it.orEmpty() }, title = tx.replaceFirst(Regex("\\bTx\\b"), "Tx/Rx"))
                                        }
                                        tab.monitor != null -> ReadOnlyRow(field, labels, live(field))
                                        editable -> {
                                            val limit = field in spec.shown?.limits.orEmpty()
                                            val waits = waitsFor(gates, field, owner::value, labels)
                                            val open = fresh && field in spec.shown?.columns.orEmpty()
                                            if (halves != null) PairRow(argument, halves, labels, owner, limit) else FieldRow(argument, labels, owner, limit, waits, open, firstFocus.takeIf { !compact && source == null && !locked }, besideOf(field))
                                            if (source == null && field in spec.form?.confirm.orEmpty() && field in form.changes()) {
                                                val typed = confirming[field]
                                                ConfirmRow(argument, labels[field] ?: argument.label ?: label(field), typed.orEmpty(), typed != null && typed != form.value(field).orEmpty(), mismatch) {
                                                    confirming[field] = it
                                                }
                                            }
                                        }
                                        owner.value(field) != null -> if (halves != null) {
                                            val values = owner.value(field).orEmpty().split('/')
                                            ReadOnlyPairRow(field, halves, labels, halves.mapIndexed { index, half -> formatted(spec.shown, half, values[index]) })
                                        } else {
                                            ReadOnlyRow(field, labels, owner.value(field)?.let { formatted(spec.shown, field, it) }, argument?.type, extra = besideOf(field))
                                        }
                                    }
                                    below(field)
                                }
                            }
                        }
                        VerticalScrollbar(scrolled, Modifier.align(Alignment.CenterEnd))
                    }
                    val panelActions = actions.filter { it.beside == null && it.below == null }
                    val panelLinks = links.filter { it.beside == null && it.below == null }
                    val canCopy = inList && "add" in current.commands
                    val canRemove = inList && "remove" in current.commands && form.value("default") != "true"
                    val canReset = autoset.isEmpty() && "reset" in current.commands && (current.kind == NodeKind.Settings || id != null && form.value("default") == "true")
                    val typing = compact && WindowInsets.ime.getBottom(LocalDensity.current) > 0
                    if (!typing && (canCopy || canRemove || canReset || panelActions.isNotEmpty() || panelLinks.isNotEmpty() || classic)) Buttons(compact, { buttons = it }) {
                        val wide = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                        val plain = classic && !compact
                        @Composable
                        fun command(title: String, icon: ImageVector, tint: Color? = null, tag: String? = null, onClick: () -> Unit) {
                            val marked = tag?.let { Modifier.testTag(it) } ?: Modifier
                            if (plain) Button(title, onClick, wide.then(marked)) else ToolbarButton(title, icon, onClick, marked, tint = tint)
                        }
                        if (classic) {
                            if (saves) Button(stringResource(Res.string.form_ok), { scope.launch { if (apply()) onClose() } }, wide, enabled = !busy && !unconfirmed() && !misfit(), primary = true)
                            Button(stringResource(if (saves) Res.string.list_cancel else Res.string.form_close), onClose, wide)
                            if (saves) {
                                Button(stringResource(Res.string.form_apply), { scope.launch { apply() } }, wide, enabled = !busy && (changed() || creating) && !unconfirmed() && !misfit())
                                if (!native) Button(stringResource(Res.string.form_command), { showing = true }, wide, enabled = !busy && changed())
                            }
                        }
                        if (canCopy) {
                            command(stringResource(Res.string.form_copy), Lucide.Copy) {
                                onCopy(current.arguments.printedFirst(order.orEmpty()) { it.name }.mapNotNull { argument -> form.value(argument.name)?.let { argument.name to it } }.toMap())
                            }
                            command(stringResource(Res.string.copy_item_command), Lucide.ClipboardCopy) { copyCommand() }
                        }
                        if (canRemove && !locked) {
                            command(stringResource(Res.string.list_remove), Lucide.X) { if (confirm) removing = true else remove() }
                        }
                        if (canReset && !locked) command(stringResource(Res.string.form_reset), Lucide.RotateCcw) { resetting = true }
                        if (panelActions.isNotEmpty()) {
                            if (!compact && !plain) {
                                Row(Modifier.padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(Res.string.form_actions), style = Theme.type.strong)
                                }
                            }
                            for (action in panelActions) {
                                val icon = Theme.icons["command/${action.command}"]
                                command(action.title, icon?.vector ?: Lucide.Zap, tint = palette.tintOf(icon), tag = "action-${action.command}") { act(action) }
                            }
                        }
                        if (panelLinks.isNotEmpty()) {
                            if (!compact && !plain) Text(stringResource(Res.string.form_configuration), Modifier.padding(top = 10.dp, bottom = 2.dp), style = Theme.type.strong)
                            for (link in panelLinks) command(link.title, Lucide.Settings, tag = "link-${link.title}") { open(link) }
                        }
                    }
                }
                @Composable
                fun dialogButtons(each: Modifier) {
                    val command = stringResource(Res.string.form_command)
                    if (saves && !native) {
                        if (compact) ToolbarButton(command, Lucide.SquareTerminal, { showing = true }, enabled = !busy && changed(), iconOnly = true)
                        else Button(command, { showing = true }, enabled = !busy && changed())
                    }
                    Button(stringResource(if (saves) Res.string.list_cancel else Res.string.form_close), onClose, each)
                    if (saves) {
                        Button(stringResource(Res.string.form_apply), { scope.launch { apply() } }, each, enabled = !busy && (changed() || creating) && !unconfirmed() && !misfit())
                        Button(stringResource(Res.string.form_ok), { scope.launch { if (apply()) onClose() } }, each, enabled = !busy && !unconfirmed() && !misfit(), primary = true)
                    }
                }
                val flagged = spec.shown.letters().map { it.first }.filter { form.router[it] == "true" }
                val status = (form.value("status")?.takeIf { "status" in spec.shown?.readonly.orEmpty() && formTabs.none { "status" in it.fields } } ?: monitored["status"])
                    ?.let { "${spec.labels["status"] ?: label("status")}: $it" }
                val standing = form.router[".inactive"]?.let { if (it == "true") "INACTIVE" else "ACTIVE" }
                if (classic) {
                    StateCells(flagged, current.argument("disabled") != null, problem, status, standing)
                } else Column(Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    problem?.let { Text(deviceMessage(it), Modifier.fillMaxWidth().testTag("problem"), color = palette.danger, style = Theme.type.small, maxLines = 3) }
                    if (!compact || flagged.isNotEmpty() || status != null || standing != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            standing?.let { state ->
                                val up = state == "ACTIVE"
                                Text(state, Modifier.background(if (up) palette.success else palette.badge, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp).testTag("standing"), style = Theme.type.small, color = if (up) palette.on(palette.success) else palette.muted)
                            }
                            for (property in flagged) {
                                Text(
                                    property.uppercase(),
                                    Modifier.background(palette.badge, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp),
                                    style = Theme.type.small,
                                    color = palette.muted,
                                )
                            }
                            status?.let { Text(it, Modifier.testTag("status"), style = Theme.type.small, color = palette.muted, maxLines = 1) }
                            Box(Modifier.weight(1f))
                            if (!compact) dialogButtons(Modifier)
                        }
                    }
                    if (compact) {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) { dialogButtons(Modifier) }
                    }
                }
            }
            if (showing) {
                Confirm(stringResource(Res.string.form_command_question), stringResource(Res.string.form_apply), onCancel = { showing = false }, content = {
                    SelectionContainer { Text(commands(), style = Theme.type.mono) }
                }, alternative = stringResource(Res.string.output_copy) to { copier.copy(commands()) }) {
                    showing = false
                    scope.launch { apply() }
                }
            }
            if (removing && id != null) {
                Confirm(pluralStringResource(Res.plurals.list_remove_question, 1, 1), stringResource(Res.string.list_remove), onCancel = { removing = false }) {
                    removing = false
                    remove()
                }
            }
            if (resetting) {
                Confirm(stringResource(Res.string.form_reset_question, title), stringResource(Res.string.form_reset), onCancel = { resetting = false }) {
                    resetting = false
                    reset()
                }
            }
            asking?.let { action ->
                Confirm(stringResource(Res.string.command_question, action.title), action.title, onCancel = { asking = null }) {
                    asking = null
                    run(action)
                }
            }
            output?.let { (heading, reply) -> Output(heading, reply) { output = null } }
        }
    }
}

private fun tabsOf(spec: ItemSpec, schema: MenuSchema, form: FormState, order: List<String>, version: String): List<PackFormTab> {
    val skip = setOf("disabled", "comment")
    spec.form?.let { layout ->
        val running = RouterOsVersion.parse(version)
        val older = layout.renamed.entries.filter { (_, new) -> schema.argument(new) == null }.associate { (old, new) -> new to old }
        val tabs = layout.tabs.filter { running?.within(it.since, it.until) != false }
            .map { PackFormTab(it.title, it.fields.map { field -> older[field] ?: field } - skip, it.readonly, path = it.path, monitor = it.monitor, chart = it.chart, graph = it.graph, inline = it.inline) }
        val placed = layout.tabs.filter { it.path == null }.flatMap { it.fields }.toSet() + layout.renamed.keys
        val others = schema.arguments.map { it.name }.filter { it !in placed && it !in skip && !it.startsWith(".") } + unlisted(spec.shown, layout, schema, form.router.keys)
        return tabs + listOfNotNull(others.takeIf { it.isNotEmpty() }?.let { PackFormTab("Other", it, inline = tabs.isNotEmpty() && tabs.all { tab -> tab.inline }) })
    }
    val fields = (schema.arguments.map { it.name } - skip).printedFirst(order) { it }
    val status = form.router.keys.filter { it !in fields && it !in skip && !it.startsWith(".") && spec.shown.letters().none { (flag, _) -> flag == it } }
    val state = status.takeIf { it.isNotEmpty() }?.let { PackFormTab("Status", it, readonly = true) }
    if (fields.isEmpty() && state != null) return listOf(state)
    val tabs = schema.arguments.mapNotNull { it.tab }.distinct()
    if (tabs.isEmpty()) return listOfNotNull(PackFormTab("General", fields), state)
    val placed = fields.groupBy { schema.argument(it)?.tab ?: tabs.first() }
    return tabs.mapNotNull { tab -> placed[tab]?.let { PackFormTab(tab, it) } } + listOfNotNull(state)
}

private val flagged = setOf("comment", "disabled", "default")

// a property a newer release or a package brought, which print shows and inspect does not list
internal fun unlisted(shown: PackPath?, layout: PackForm, schema: MenuSchema, printed: Collection<String>): List<String> {
    val known = layout.tabs.flatMap { it.fields }.toSet() + layout.renamed.keys + layout.renamed.values + schema.arguments.map { it.name } +
        shown?.columns.orEmpty() + shown?.readonly.orEmpty() + shown?.pairs.orEmpty().flatMap { (pair, halves) -> listOf(pair) + halves } +
        shown?.gauges.orEmpty().flatMap { (field, limit) -> listOf(field, limit) }
    return printed.filter { it !in known && !it.startsWith(".") && it !in flagged && shown.letters().none { (flag, _) -> flag == it } }
}

private fun derived(shown: PackPath?, values: Map<String, String>): Map<String, String> {
    if (shown == null) return values
    val empty = shown.blank.filter { it !in values }.associateWith { "" }
    return values + empty + shown.derived.mapNotNull { (name, rule) ->
        val text = values[rule.of]?.lowercase() ?: return@mapNotNull null
        rule.values.entries.firstOrNull { (_, words) -> words.any { it in text } }?.let { name to it.key }
    }
}

private fun Argument.applies(form: FormState, schema: MenuSchema?) = depends.hold(form, schema)

private fun List<Map<String, String>>.hold(form: FormState, schema: MenuSchema?) =
    isEmpty() || any { values -> values.all { (name, value) -> (form.value(name) ?: schema?.argument(name)?.default.orEmpty()) == value } }

private fun <T> List<T>.printedFirst(order: List<String>, name: (T) -> String) = sortedBy { order.indexOf(name(it)).takeIf { at -> at >= 0 } ?: order.size }

@Stable
private class Related(val path: MenuPath) {
    val form = FormState()
    var schema by mutableStateOf<MenuSchema?>(null)
    var id by mutableStateOf<String?>(null)
}

// after the first row a monitor sends only what changed, so the rows are laid over each other
@Composable
private fun reported(session: DeviceSession, monitor: PackMonitor, name: String?, linked: Boolean, onError: (String?) -> Unit): Map<String, String> {
    val values by produceState(emptyMap<String, String>(), session, monitor, name, linked) {
        val argument = monitor.argument
        if (!linked || argument != null && name == null) return@produceState
        val command = MenuPath.parse(monitor.command)
        val arguments = if (argument == null || name == null) emptyMap() else mapOf(argument to name)
        try {
            session.run(command.parent ?: MenuPath.Root, command.segments.last(), arguments).onTheSecond().collect { value = value + (it.values - ".section") }
        } catch (e: DeviceException) {
            onError(e.message)
        }
    }
    return values
}

@Composable
private fun Rates(values: List<String?>, names: List<String>) {
    val palette = Theme.palette
    val series = remember(names) { names.zip(listOf(palette.accent, palette.success)) { name, color -> Series(name, color, Ring(90)) } }
    LaunchedEffect(values) {
        for ((value, line) in values.zip(series)) value?.toDoubleOrNull()?.let(line.ring::add)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Chart(series, Modifier.fillMaxWidth().height(120.dp), format = ::bits)
        Legend(series, ::bits)
    }
}

@Composable
private fun Buttons(compact: Boolean, onHeight: (Int) -> Unit, content: @Composable () -> Unit) {
    val palette = Theme.palette
    if (compact) {
        FlowRow(Modifier.fillMaxWidth().background(palette.raised).padding(6.dp), verticalArrangement = Arrangement.Center) { content() }
    } else {
        Box(Modifier.width(200.dp).fillMaxHeight().background(palette.raised)) {
            Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged { onHeight(it.height) }.padding(6.dp)) { content() }
        }
    }
}

internal class FirstFocus {
    private var taken = false

    fun claim(): Boolean {
        val free = !taken
        taken = true
        return free
    }
}

@Stable
internal class LabelColumn {
    var width by mutableStateOf(110.dp)
        private set

    fun offer(wanted: Dp) {
        val bounded = wanted.coerceIn(110.dp, 300.dp)
        if (bounded > width) width = bounded
    }
}

internal val LocalLabelColumn = compositionLocalOf<LabelColumn?> { null }

@Composable
private fun ConfirmRow(argument: Argument, title: String, value: String, wrong: Boolean, mismatch: String, onChange: (String) -> Unit) {
    FieldLine(stringResource(Res.string.form_confirm, title), true, false) {
        Column {
            FieldEditor(argument, value, onChange, Modifier.fillMaxWidth().testTag("confirm-${argument.name}"))
            if (wrong) Text(mismatch, Modifier.padding(top = 2.dp), style = Theme.type.small, color = Theme.palette.warning)
        }
    }
}

@Composable
private fun StateCells(flagged: List<String>, switchable: Boolean, problem: String?, status: String?, standing: String? = null) {
    val palette = Theme.palette
    val first = when {
        "dynamic" in flagged -> "dynamic"
        "disabled" in flagged -> "disabled"
        switchable -> "enabled"
        else -> null
    }
    val states = listOfNotNull(first) + flagged.filter { it != "dynamic" && it != "disabled" }.map { if (it == "connect") "connected" else it } + listOfNotNull(standing)
    if (states.isEmpty() && problem == null && status == null) return
    Column(Modifier.fillMaxWidth().background(palette.raised).padding(2.dp)) {
        problem?.let { Text(deviceMessage(it), Modifier.padding(4.dp).testTag("problem"), color = palette.danger, style = Theme.type.small, maxLines = 2) }
        Row(Modifier.fillMaxWidth().height(18.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (state in states + List((2 - states.size).coerceAtLeast(0)) { "" } + listOfNotNull(status)) {
                Text(state, Modifier.weight(1f).fillMaxHeight().sunken(palette).padding(horizontal = 4.dp, vertical = 1.dp), style = Theme.type.small, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun GroupLine() {
    val start = if (Theme.classic && !LocalCompact.current) 8.dp else fieldStart()
    Box(Modifier.fillMaxWidth().padding(start = start, end = 12.dp, top = 4.dp, bottom = 4.dp).height(1.dp).background(Theme.palette.line).testTag("group-line"))
}

@Composable
private fun ButtonsLine() {
    Box(Modifier.fillMaxWidth().padding(start = fieldStart(), end = 12.dp, top = 4.dp, bottom = 4.dp).height(1.dp).background(Theme.palette.line).testTag("buttons-line"))
}

// where the editors of a form begin
@Composable
private fun fieldStart() = if (LocalCompact.current) 12.dp else 8.dp + (LocalLabelColumn.current?.width ?: 170.dp)

@Composable
private fun SectionHeading(title: String) {
    val palette = Theme.palette
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line).testTag("section-line"))
        Text(title, Modifier.padding(top = 6.dp, bottom = 2.dp).testTag("section"), color = palette.muted)
    }
}

@Composable
private fun PlacedButtons(actions: List<PackAction>, links: List<PackLink>, enabled: Boolean, onAction: (PackAction) -> Unit, onLink: (PackLink) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (action in actions) Button(action.title, { onAction(action) }, Modifier.testTag("action-${action.command}"), enabled = enabled)
        for (link in links) Button(link.title, { onLink(link) }, Modifier.testTag("link-${link.title}"))
    }
}

@Composable
private fun FieldLine(title: String, touched: Boolean, changed: Boolean, faded: Boolean = false, wrong: Boolean = false, focused: Boolean = false, content: @Composable () -> Unit) {
    val palette = Theme.palette
    val color = when {
        wrong -> palette.danger
        changed -> palette.warning
        touched || focused -> palette.accent
        faded -> palette.faint
        else -> palette.text
    }
    if (LocalCompact.current) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DisableSelection { Text(title, style = Theme.type.strong, color = color, maxLines = 1) }
            content()
        }
        return
    }
    val classic = Theme.classic
    val text = if (classic && title.isNotEmpty()) "$title:" else title
    val style = if (classic) Theme.type.body else Theme.type.strong
    val column = LocalLabelColumn.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val wanted = remember(text, style, density) { with(density) { measurer.measure(text, style).size.width.toDp() } + 11.dp }
    SideEffect { column?.offer(wanted) }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.Top) {
        DisableSelection {
            Text(
                text,
                Modifier.width(column?.width ?: 170.dp).padding(top = if (classic) 2.dp else 5.dp, end = 10.dp),
                textAlign = TextAlign.End,
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
internal fun FieldRow(argument: Argument, labels: Map<String, String>, form: FormState, limit: Boolean = false, waits: String? = null, open: Boolean = false, firstFocus: FirstFocus? = null, extra: (@Composable () -> Unit)? = null) {
    val name = argument.name
    val value = form.value(name)
    val changed = form.changedOnRouter(name)
    val wrong = value != null && argument.misfits(value)
    val hidden = LocalSettings.current.hidePasswords && argument.sensitive()
    val title = labels[name] ?: argument.label ?: label(name)
    val beside = title.takeIf { Theme.classic && !LocalCompact.current && argument.type in switches && !(value == null && argument.optional) && waits == null }
    val placed = extra
    var inside by remember { mutableStateOf(false) }
    FieldLine(if (beside != null) "" else title, form.touched(name), changed, faded = waits != null && value == null, wrong = wrong, focused = inside) {
        Column(Modifier.onFocusChanged { inside = it.hasFocus }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    value == null && argument.optional && (!open || waits != null || argument.type in switches) ->
                        IconButton(Lucide.Plus, waits ?: stringResource(Res.string.form_set), { form.edit(name, "") }, Modifier.testTag("set-$name"), enabled = waits == null)
                    else -> {
                        val negated = argument.negatable && value.orEmpty().startsWith("!")
                        val text = if (argument.negatable) value.orEmpty().removePrefix("!") else value.orEmpty()
                        if (argument.negatable) NegationToggle(negated) { form.edit(name, (if (it) "!" else "") + text) }
                        val shown = if (limit && !form.touched(name)) typed(text) else text
                        val focus = remember { FocusRequester() }
                        if (firstFocus != null && !argument.radio) LaunchedEffect(Unit) { if (firstFocus.claim()) focus.requestFocus() }
                        FieldEditor(argument, shown, { form.edit(name, (if (negated) "!" else "") + it) }, Modifier.weight(1f).testTag("field-$name").focusRequester(focus), beside)
                        if (argument.optional && value != null) {
                            IconButton(Lucide.Minus, stringResource(Res.string.form_unset), { form.edit(name, null) }, Modifier.testTag("unset-$name"))
                        }
                    }
                }
                if (placed != null) placed()
            }
            if (wrong) {
                Text(
                    hint(argument.type)?.let { stringResource(Res.string.form_expected, it) } ?: stringResource(Res.string.form_invalid),
                    Modifier.padding(top = 2.dp).testTag("mismatch-$name"),
                    style = Theme.type.small,
                    color = Theme.palette.danger,
                )
            }
            if (changed) {
                Text(
                    stringResource(Res.string.form_on_router, form.router[name]?.let { if (hidden) HiddenValue else it } ?: "—"),
                    Modifier.padding(top = 2.dp),
                    style = Theme.type.small,
                    color = Theme.palette.warning,
                )
            }
            if (waits != null && value != null) Text(waits, Modifier.padding(top = 2.dp), style = Theme.type.small, color = Theme.palette.warning)
        }
    }
}

private val switches = setOf(ValueType.YesNo, ValueType.Flag, ValueType.Switch)

private fun typed(value: String) = value.toLongOrNull()?.let(::limit) ?: value

private val protocolNumber = Regex("""0x\p{XDigit}+|\d+""")

@Composable
internal fun waitsFor(only: List<PackCondition>, field: String, value: (String) -> String?, labels: Map<String, String>): String? {
    val (property, values) = only.filter { field in it.fields }.flatMap { it.where.entries }
        .firstOrNull { (property, values) -> !PackCondition.matches(values, value(property).orEmpty()) } ?: return null
    val named = values.filterNot { protocolNumber.matches(it) || it.isEmpty() || it == "*" }.map {
        when (it) {
            "true" -> "yes"
            "false" -> "no"
            else -> it
        }
    }
    return stringResource(Res.string.form_only_with, labels[property] ?: label(property), named.distinct().joinToString(", "))
}

internal fun singular(title: String): String {
    val word = title.substringAfterLast(' ')
    val one = when {
        word.uppercase() == word -> word
        word.endsWith("ies") -> word.dropLast(3) + "y"
        listOf("iases", "sses", "shes", "ches", "xes").any(word::endsWith) -> word.dropLast(2)
        listOf("ss", "us", "is").any(word::endsWith) -> word
        word.endsWith("s") -> word.dropLast(1)
        else -> word
    }
    return title.dropLast(word.length) + one
}

@Composable
private fun PairRow(argument: Argument, halves: List<String>, labels: Map<String, String>, form: FormState, limit: Boolean) {
    val name = argument.name
    val values = form.value(name).orEmpty().split('/').map { if (limit && !form.touched(name)) typed(it) else it }
    FieldLine(labels[name] ?: argument.label ?: label(name), form.touched(name), form.changedOnRouter(name)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((index, half) in halves.withIndex()) {
                FieldEditor(argument, values.getOrElse(index) { "" }, { text ->
                    val both = halves.indices.map { if (it == index) text else values.getOrElse(it) { "" } }
                    form.edit(name, both.dropLastWhile { it.isEmpty() }.joinToString("/"))
                }, Modifier.weight(1f).testTag("field-$half"))
            }
        }
    }
}

@Composable
private fun PairHeader(halves: List<String>, labels: Map<String, String>) {
    val words = halves.map { (labels[it] ?: label(it)).split(' ') }
    val shared = words.first().toSet() intersect words.last().toSet()
    FieldLine("", false, false) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (half in words) Text((half - shared).joinToString(" "), Modifier.weight(1f), style = Theme.type.small, color = Theme.palette.muted)
        }
    }
}

@Composable
private fun GaugeRow(name: String, labels: Map<String, String>, value: String?, limit: String?, unit: String?) {
    val palette = Theme.palette
    val share = value?.toDoubleOrNull()?.let { used -> limit?.toDoubleOrNull()?.takeIf { it > 0 }?.let { (used / it).coerceIn(0.0, 1.0).toFloat() } }
    FieldLine(labels[name] ?: label(name), false, false) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ValueBox(value?.let { listOfNotNull(it, unit).joinToString(" ") }, Modifier.fillMaxWidth())
            if (share != null) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(palette.line).testTag("gauge-$name")) {
                    Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(palette.accent))
                }
            }
        }
    }
}

private fun txRx(fields: List<String>): List<Pair<String, String?>> = buildList {
    var at = 0
    while (at < fields.size) {
        val field = fields[at]
        val rx = fields.getOrNull(at + 1)?.takeIf { "tx-" in field && it == field.replaceFirst("tx-", "rx-") }
        add(field to rx)
        at += if (rx == null) 1 else 2
    }
}

@Composable
internal fun ReadOnlyRow(name: String, labels: Map<String, String>, value: String?, type: ValueType? = null, title: String = labels[name] ?: label(name), extra: (@Composable () -> Unit)? = null) {
    val shown = if (!value.isNullOrEmpty() && LocalSettings.current.hidePasswords && sensitive(name, type)) HiddenValue else value
    val flag = shown == "true" || shown == "false"
    val beside = flag && Theme.classic && !LocalCompact.current
    FieldLine(if (beside) "" else title, false, false) {
        if (flag) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(shown == "true", {}, enabled = false)
                if (beside) Text(title, Modifier.padding(start = 6.dp), style = Theme.type.body)
            }
            return@FieldLine
        }
        if (shown.orEmpty().contains('\n')) {
            val palette = Theme.palette
            SelectionContainer {
                Text(
                    shown.orEmpty().trimEnd(),
                    Modifier.fillMaxWidth().background(palette.raised, RoundedCornerShape(Theme.metrics.radius)).padding(horizontal = 6.dp, vertical = 4.dp),
                    style = Theme.type.mono,
                )
            }
        } else {
            val elements = shown?.let { elementsOf(name, it, type) }
            val placed = extra
            when {
                elements != null -> ValueList(elements.map { it to readable(it, labels) })
                placed != null -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ValueBox(shown, Modifier.weight(1f))
                    placed()
                }
                else -> ValueBox(shown, Modifier.fillMaxWidth())
            }
        }
    }
}

internal fun elementsOf(name: String, value: String, type: ValueType?): List<String>? {
    val elements = when {
        type is ValueType.Many -> value.split(type.separator)
        type is ValueType.Enumeration && type.multiple -> value.split(',')
        type == null && name != "comment" -> value.split(',')
        else -> return null
    }
    return elements.takeIf { it.size > 1 && it.none { element -> element.isEmpty() || type == null && element.any(Char::isWhitespace) } }
}

private val MEDIA = Regex("[0-9.]+[kKMGT]?-base[A-Za-z0-9]+(-[A-Za-z0-9]+)*")

internal fun readable(element: String, labels: Map<String, String>) = labels[element] ?: if (MEDIA.matches(element)) element.replace('-', ' ') else element

@Composable
private fun ValueList(elements: List<Pair<String, String>>) {
    val over = remember { MutableInteractionSource() }
    val hovered by over.collectIsHoveredAsState()
    Column(Modifier.fillMaxWidth().hoverable(over), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        elements.forEachIndexed { index, (element, shown) ->
            ValueBox(shown, Modifier.fillMaxWidth(), copied = element, list = if (index == 0 && hovered) elements.joinToString(",") { it.first } else null)
        }
    }
}

@Composable
private fun ReadOnlyPairRow(name: String, halves: List<String>, labels: Map<String, String>, values: List<String>) {
    FieldLine(labels[name] ?: label(name), false, false) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((index, half) in halves.withIndex()) ValueBox(values[index], Modifier.weight(1f), tag = "value-$half")
        }
    }
}

@Composable
private fun ValueBox(shown: String?, modifier: Modifier, tag: String? = null, copied: String? = shown, list: String? = null) {
    val state = remember(shown) { TextFieldState(shown.orEmpty()) }
    val over = remember { MutableInteractionSource() }
    val hovered by over.collectIsHoveredAsState()
    val copier = rememberCopier()
    Box(modifier.hoverable(over)) {
        TextField(state, Modifier.fillMaxWidth().height(Theme.metrics.control).then(if (tag != null) Modifier.testTag(tag) else Modifier), readOnly = true)
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 2.dp)) {
            if (list != null) IconButton(Lucide.Files, stringResource(Res.string.copy_list), { copier.copy(list) })
            if (hovered && !copied.isNullOrEmpty()) IconButton(Lucide.Copy, stringResource(Res.string.copy_value), { copier.copy(copied) })
        }
    }
}
