package com.vasmarfas.networkhelm.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.app.AppSettings
import com.vasmarfas.networkhelm.app.BarItem
import com.vasmarfas.networkhelm.app.BrowserAccess
import com.vasmarfas.networkhelm.app.Languages
import com.vasmarfas.networkhelm.app.canSetLanguage
import com.vasmarfas.networkhelm.ui.files.LocalFileExchange
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.Labeled
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.ScrollArea
import com.vasmarfas.networkhelm.ui.kit.Select
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.kit.Toggle
import com.vasmarfas.networkhelm.ui.kit.dialogPanel
import com.vasmarfas.networkhelm.ui.kit.scrim
import com.vasmarfas.networkhelm.ui.terminal.terminalScrollback
import com.vasmarfas.networkhelm.ui.theme.BundledThemes
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.theme.ThemeFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.access_open_at
import networkhelm.app.shared.generated.resources.settings_bar_clock
import networkhelm.app.shared.generated.resources.settings_bar_cpu
import networkhelm.app.shared.generated.resources.settings_bar_disk
import networkhelm.app.shared.generated.resources.settings_bar_down
import networkhelm.app.shared.generated.resources.settings_bar_interface
import networkhelm.app.shared.generated.resources.settings_bar_interface_default
import networkhelm.app.shared.generated.resources.settings_bar_memory
import networkhelm.app.shared.generated.resources.settings_bar_router
import networkhelm.app.shared.generated.resources.settings_bar_traffic
import networkhelm.app.shared.generated.resources.settings_bar_up
import networkhelm.app.shared.generated.resources.settings_bar_uptime
import networkhelm.app.shared.generated.resources.settings_browser_access
import networkhelm.app.shared.generated.resources.settings_check_updates
import networkhelm.app.shared.generated.resources.settings_check_updates_hint
import networkhelm.app.shared.generated.resources.settings_close
import networkhelm.app.shared.generated.resources.settings_confirm_removal
import networkhelm.app.shared.generated.resources.settings_delete
import networkhelm.app.shared.generated.resources.settings_edit
import networkhelm.app.shared.generated.resources.settings_export
import networkhelm.app.shared.generated.resources.settings_hide_passwords
import networkhelm.app.shared.generated.resources.settings_hide_passwords_hint
import networkhelm.app.shared.generated.resources.settings_import
import networkhelm.app.shared.generated.resources.settings_language
import networkhelm.app.shared.generated.resources.settings_lines
import networkhelm.app.shared.generated.resources.settings_look
import networkhelm.app.shared.generated.resources.settings_look_dark
import networkhelm.app.shared.generated.resources.settings_look_light
import networkhelm.app.shared.generated.resources.settings_look_system
import networkhelm.app.shared.generated.resources.settings_not_theme
import networkhelm.app.shared.generated.resources.settings_probe
import networkhelm.app.shared.generated.resources.settings_probe_hint
import networkhelm.app.shared.generated.resources.settings_resource_bar
import networkhelm.app.shared.generated.resources.settings_rows
import networkhelm.app.shared.generated.resources.settings_rows_theme
import networkhelm.app.shared.generated.resources.settings_scale
import networkhelm.app.shared.generated.resources.settings_separators
import networkhelm.app.shared.generated.resources.settings_terminal
import networkhelm.app.shared.generated.resources.settings_theme
import networkhelm.app.shared.generated.resources.settings_title
import networkhelm.app.shared.generated.resources.settings_unreadable
import kotlin.math.abs
import kotlin.math.roundToInt

private val Scales = listOf(0.67f, 0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f)

private val RowSpacings = listOf(-2, 0, 2, 4, 6, 8)

private val TerminalLines = listOf(1000, 5000, 10000, 50000, 100000)

@Composable
fun SettingsDialog(
    settings: AppSettings,
    access: BrowserAccess? = null,
    onBrowserAccess: () -> Unit = {},
    themeEditor: MutableState<ThemeFile?> = remember { mutableStateOf(null) },
    onClose: () -> Unit,
) {
    val palette = Theme.palette
    val scope = rememberCoroutineScope()
    val exchange = LocalFileExchange.current
    var editing by themeEditor
    var problem by remember { mutableStateOf<String?>(null) }
    val current = settings.current
    val theme = settings.theme
    fun act(action: suspend () -> Unit) = scope.launch {
        problem = null
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = e.message ?: e::class.simpleName
        }
    }
    Box(Modifier.fillMaxSize().scrim(palette.workspace.copy(alpha = 0.6f), onClose.takeIf { editing == null }), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 620.dp).padding(16.dp).dialogPanel(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.settings_title), Modifier.weight(1f), style = Theme.type.strong)
                IconButton(Lucide.X, stringResource(Res.string.settings_close), onClose, Modifier.testTag("settings-cross"))
            }
            ScrollArea(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (canSetLanguage) {
                    Labeled(stringResource(Res.string.settings_language)) {
                        Select(current.language, listOf(null) + Languages.keys, { tag -> tag?.let(Languages::getValue) ?: stringResource(Res.string.settings_look_system) }, { picked ->
                            act { settings.change { it.copy(language = picked) } }
                        }, Modifier.width(240.dp).testTag("language"))
                    }
                }
                Labeled(stringResource(Res.string.settings_theme)) {
                    Select(theme, settings.all, { it.name }, { picked -> act { settings.change { it.copy(theme = picked.name) } } }, Modifier.width(240.dp).testTag("theme"))
                }
                if (theme.light != null && theme.dark != null) {
                    Labeled(stringResource(Res.string.settings_look)) {
                        val looks = if (theme.followsSystem) listOf(null, false, true) else listOf(false, true)
                        Select(if (theme.followsSystem) current.dark else current.dark ?: false, looks, { look(it) }, { dark -> act { settings.change { it.copy(dark = dark) } } }, Modifier.width(240.dp).testTag("look"))
                    }
                }
                Labeled(stringResource(Res.string.settings_scale)) {
                    Select(current.scale, Scales, { "${(it * 100).roundToInt()} %" }, { scale -> act { settings.change { it.copy(scale = scale) } } }, Modifier.width(240.dp))
                }
                Labeled("") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(stringResource(Res.string.settings_edit), { editing = theme })
                        Button(stringResource(Res.string.settings_import), {
                            act {
                                for (file in exchange.pick()) {
                                    val read = try {
                                        AppSettings.json.decodeFromString(ThemeFile.serializer(), file.read().decodeToString())
                                    } catch (e: SerializationException) {
                                        problem = getString(Res.string.settings_not_theme, file.name, e.message.orEmpty())
                                        continue
                                    }
                                    val kept = settings.keep(read)
                                    settings.change { it.copy(theme = kept.name) }
                                }
                            }
                        }, Modifier.testTag("import-theme"))
                        Button(stringResource(Res.string.settings_export), {
                            act { exchange.save("${theme.name}.json", AppSettings.json.encodeToString(ThemeFile.serializer(), theme).encodeToByteArray()) }
                        }, Modifier.testTag("export-theme"))
                        if (theme !in BundledThemes) Button(stringResource(Res.string.settings_delete), { act { settings.remove(theme) } })
                    }
                }
                Labeled(stringResource(Res.string.settings_rows)) {
                    val theme = stringResource(Res.string.settings_rows_theme)
                    Select(current.rowSpacing, RowSpacings, { if (it == 0) theme else "${if (it > 0) "+" else "−"}${abs(it)} dp" }, { spacing ->
                        act { settings.change { it.copy(rowSpacing = spacing) } }
                    }, Modifier.width(240.dp).testTag("rows"))
                }
                if (terminalScrollback) {
                    Labeled(stringResource(Res.string.settings_terminal)) {
                        Select(current.terminalLines, TerminalLines, { pluralStringResource(Res.plurals.settings_lines, it, it) }, { lines ->
                            act { settings.change { it.copy(terminalLines = lines) } }
                        }, Modifier.width(240.dp).testTag("terminal-lines"))
                    }
                }
                Toggle(current.columnLines, stringResource(Res.string.settings_separators), null, "separators") { on -> act { settings.change { it.copy(columnLines = on) } } }
                Toggle(current.confirmRemoval, stringResource(Res.string.settings_confirm_removal), null, "confirm-removal") { on ->
                    act { settings.change { it.copy(confirmRemoval = on) } }
                }
                Toggle(current.resourceBar, stringResource(Res.string.settings_resource_bar), null, "resource-bar") { on -> act { settings.change { it.copy(resourceBar = on) } } }
                if (current.resourceBar) {
                    val interfaceName = remember { TextFieldState(current.barInterface) }
                    LaunchedEffect(interfaceName) {
                        snapshotFlow { interfaceName.text.toString() }.drop(1).collectLatest { text ->
                            delay(400)
                            act { settings.change { it.copy(barInterface = text.trim()) } }
                        }
                    }
                    Column(Modifier.padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        current.barOrder.forEachIndexed { index, item ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.weight(1f)) {
                                    Toggle(item !in current.barHidden, barLabel(item), null, "bar-${item.name}") { on ->
                                        act { settings.change { it.copy(barHidden = if (on) it.barHidden - item else it.barHidden + item) } }
                                    }
                                }
                                val arrow = if (LocalCompact.current) Modifier.size(44.dp, 40.dp) else Modifier
                                IconButton(Lucide.ChevronUp, stringResource(Res.string.settings_bar_up), { act { settings.change { it.copy(barOrder = it.barOrder.swapped(index, index - 1)) } } }, arrow.testTag("bar-up-${item.name}"), enabled = index > 0)
                                IconButton(Lucide.ChevronDown, stringResource(Res.string.settings_bar_down), { act { settings.change { it.copy(barOrder = it.barOrder.swapped(index, index + 1)) } } }, arrow.testTag("bar-down-${item.name}"), enabled = index < current.barOrder.lastIndex)
                            }
                        }
                        Labeled(stringResource(Res.string.settings_bar_interface)) {
                            TextField(interfaceName, Modifier.width(200.dp).testTag("bar-interface"), placeholder = stringResource(Res.string.settings_bar_interface_default))
                        }
                    }
                }
                Toggle(current.hidePasswords, stringResource(Res.string.settings_hide_passwords), stringResource(Res.string.settings_hide_passwords_hint), "hide-passwords") { on ->
                    act { settings.change { it.copy(hidePasswords = on) } }
                }
                Toggle(current.probe, stringResource(Res.string.settings_probe), stringResource(Res.string.settings_probe_hint), "probe") { on ->
                    act { settings.change { it.copy(probe = on) } }
                }
                Toggle(current.checkUpdates, stringResource(Res.string.settings_check_updates), stringResource(Res.string.settings_check_updates_hint), "check-updates") { on ->
                    act { settings.change { it.copy(checkUpdates = on) } }
                }
                if (access != null) {
                    Labeled("") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(stringResource(Res.string.settings_browser_access), onBrowserAccess, Modifier.testTag("browser-access"))
                            if (access.open) Text(stringResource(Res.string.access_open_at, access.port), color = palette.accent, style = Theme.type.small)
                        }
                    }
                }
            }
            settings.failure?.let { Text(stringResource(Res.string.settings_unreadable, it), color = palette.danger, style = Theme.type.small) }
            problem?.let { Text(it, Modifier.testTag("settings-problem"), color = palette.danger, style = Theme.type.small) }
            Row(Modifier.align(Alignment.End)) { Button(stringResource(Res.string.settings_close), onClose, primary = true) }
        }
        editing?.let { source ->
            ThemeEditor(source, onCancel = { editing = null }) { edited ->
                act {
                    val kept = settings.keep(edited)
                    settings.change { it.copy(theme = kept.name) }
                    editing = null
                }
            }
        }
    }
}

@Composable
private fun barLabel(item: BarItem) = stringResource(
    when (item) {
        BarItem.Router -> Res.string.settings_bar_router
        BarItem.Cpu -> Res.string.settings_bar_cpu
        BarItem.Memory -> Res.string.settings_bar_memory
        BarItem.Disk -> Res.string.settings_bar_disk
        BarItem.Traffic -> Res.string.settings_bar_traffic
        BarItem.Uptime -> Res.string.settings_bar_uptime
        BarItem.Clock -> Res.string.settings_bar_clock
    },
)

private fun <T> List<T>.swapped(index: Int, other: Int) = toMutableList().also {
    it[index] = this[other]
    it[other] = this[index]
}

@Composable
private fun look(dark: Boolean?) = stringResource(
    when (dark) {
        null -> Res.string.settings_look_system
        false -> Res.string.settings_look_light
        true -> Res.string.settings_look_dark
    },
)
