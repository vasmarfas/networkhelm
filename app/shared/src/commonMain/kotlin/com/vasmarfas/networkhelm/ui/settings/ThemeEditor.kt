package com.vasmarfas.networkhelm.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composeunstyled.Text
import com.composeunstyled.UnstyledDropdownMenu
import com.vasmarfas.networkhelm.ui.files.LocalFileExchange
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.Checkbox
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.MenuPanel
import com.vasmarfas.networkhelm.ui.kit.ScrollArea
import com.vasmarfas.networkhelm.ui.kit.Select
import com.vasmarfas.networkhelm.ui.kit.TabStrip
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.kit.ToolbarButton
import com.vasmarfas.networkhelm.ui.kit.dialogPanel
import com.vasmarfas.networkhelm.ui.kit.hand
import com.vasmarfas.networkhelm.ui.kit.scrim
import com.vasmarfas.networkhelm.ui.theme.BundledThemes
import com.vasmarfas.networkhelm.ui.theme.ClassicIcons
import com.vasmarfas.networkhelm.ui.theme.DefaultIcons
import com.vasmarfas.networkhelm.ui.theme.IconSet
import com.vasmarfas.networkhelm.ui.theme.IconTint
import com.vasmarfas.networkhelm.ui.theme.Metrics
import com.vasmarfas.networkhelm.ui.theme.PICTURE
import com.vasmarfas.networkhelm.ui.theme.Palette
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.theme.ThemeFile
import com.vasmarfas.networkhelm.ui.theme.ThemeFont
import com.vasmarfas.networkhelm.ui.theme.hex
import com.vasmarfas.networkhelm.ui.theme.lit
import com.vasmarfas.networkhelm.ui.theme.onSnack
import com.vasmarfas.networkhelm.ui.theme.panel
import com.vasmarfas.networkhelm.ui.theme.parseColor
import com.vasmarfas.networkhelm.ui.theme.pictureSpec
import com.vasmarfas.networkhelm.ui.theme.shaded
import com.vasmarfas.networkhelm.ui.theme.snack
import com.vasmarfas.networkhelm.ui.theme.themeIcon
import com.vasmarfas.networkhelm.ui.theme.tintOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.colour_accent
import networkhelm.app.shared.generated.resources.colour_badge
import networkhelm.app.shared.generated.resources.colour_band
import networkhelm.app.shared.generated.resources.colour_container
import networkhelm.app.shared.generated.resources.colour_danger
import networkhelm.app.shared.generated.resources.colour_divider
import networkhelm.app.shared.generated.resources.colour_faint
import networkhelm.app.shared.generated.resources.colour_frame
import networkhelm.app.shared.generated.resources.colour_grid
import networkhelm.app.shared.generated.resources.colour_highlight
import networkhelm.app.shared.generated.resources.colour_inactive_title
import networkhelm.app.shared.generated.resources.colour_inverse
import networkhelm.app.shared.generated.resources.colour_line
import networkhelm.app.shared.generated.resources.colour_muted
import networkhelm.app.shared.generated.resources.colour_on_accent
import networkhelm.app.shared.generated.resources.colour_on_inverse
import networkhelm.app.shared.generated.resources.colour_raised
import networkhelm.app.shared.generated.resources.colour_selection
import networkhelm.app.shared.generated.resources.colour_shadow
import networkhelm.app.shared.generated.resources.colour_success
import networkhelm.app.shared.generated.resources.colour_surface
import networkhelm.app.shared.generated.resources.colour_tab
import networkhelm.app.shared.generated.resources.colour_text
import networkhelm.app.shared.generated.resources.colour_title
import networkhelm.app.shared.generated.resources.colour_warning
import networkhelm.app.shared.generated.resources.colour_workspace
import networkhelm.app.shared.generated.resources.form_ok
import networkhelm.app.shared.generated.resources.list_cancel
import networkhelm.app.shared.generated.resources.list_comment
import networkhelm.app.shared.generated.resources.list_new
import networkhelm.app.shared.generated.resources.list_remove
import networkhelm.app.shared.generated.resources.size_control
import networkhelm.app.shared.generated.resources.size_gap
import networkhelm.app.shared.generated.resources.size_icon
import networkhelm.app.shared.generated.resources.size_menu_item
import networkhelm.app.shared.generated.resources.size_padding
import networkhelm.app.shared.generated.resources.size_radius
import networkhelm.app.shared.generated.resources.size_row
import networkhelm.app.shared.generated.resources.size_title
import networkhelm.app.shared.generated.resources.size_toolbar
import networkhelm.app.shared.generated.resources.theme_add_palette
import networkhelm.app.shared.generated.resources.theme_cancel
import networkhelm.app.shared.generated.resources.theme_classic
import networkhelm.app.shared.generated.resources.theme_copy
import networkhelm.app.shared.generated.resources.theme_dark
import networkhelm.app.shared.generated.resources.theme_font
import networkhelm.app.shared.generated.resources.theme_font_inter
import networkhelm.app.shared.generated.resources.theme_font_size
import networkhelm.app.shared.generated.resources.theme_font_system
import networkhelm.app.shared.generated.resources.theme_icon_none
import networkhelm.app.shared.generated.resources.theme_icon_not_picture
import networkhelm.app.shared.generated.resources.theme_icon_picture
import networkhelm.app.shared.generated.resources.theme_icon_place
import networkhelm.app.shared.generated.resources.theme_icons
import networkhelm.app.shared.generated.resources.theme_icons_buttons
import networkhelm.app.shared.generated.resources.theme_icons_commands
import networkhelm.app.shared.generated.resources.theme_icons_menu
import networkhelm.app.shared.generated.resources.theme_icons_values
import networkhelm.app.shared.generated.resources.theme_light
import networkhelm.app.shared.generated.resources.theme_material
import networkhelm.app.shared.generated.resources.theme_name
import networkhelm.app.shared.generated.resources.theme_no_palette
import networkhelm.app.shared.generated.resources.theme_preview
import networkhelm.app.shared.generated.resources.theme_remove_palette
import networkhelm.app.shared.generated.resources.theme_save
import networkhelm.app.shared.generated.resources.theme_sizes

private class Colour(val label: StringResource, val read: (Palette) -> Color, val write: (Palette, Color) -> Palette)

private val Colours = listOf(
    Colour(Res.string.colour_workspace, { it.workspace }) { p, c -> p.copy(workspace = c) },
    Colour(Res.string.colour_surface, { it.surface }) { p, c -> p.copy(surface = c) },
    Colour(Res.string.colour_raised, { it.raised }) { p, c -> p.copy(raised = c) },
    Colour(Res.string.colour_line, { it.line }) { p, c -> p.copy(line = c) },
    Colour(Res.string.colour_text, { it.text }) { p, c -> p.copy(text = c) },
    Colour(Res.string.colour_muted, { it.muted }) { p, c -> p.copy(muted = c) },
    Colour(Res.string.colour_faint, { it.faint }) { p, c -> p.copy(faint = c) },
    Colour(Res.string.colour_accent, { it.accent }) { p, c -> p.copy(accent = c) },
    Colour(Res.string.colour_on_accent, { it.onAccent }) { p, c -> p.copy(onAccent = c) },
    Colour(Res.string.colour_selection, { it.selection }) { p, c -> p.copy(selection = c) },
    Colour(Res.string.colour_badge, { it.badge }) { p, c -> p.copy(badge = c) },
    Colour(Res.string.colour_danger, { it.danger }) { p, c -> p.copy(danger = c) },
    Colour(Res.string.colour_success, { it.success }) { p, c -> p.copy(success = c) },
    Colour(Res.string.colour_warning, { it.warning }) { p, c -> p.copy(warning = c) },
    Colour(Res.string.colour_title, { it.title ?: it.accent }) { p, c -> p.copy(title = c) },
    Colour(Res.string.colour_grid, { it.grid ?: it.line }) { p, c -> p.copy(grid = c) },
    Colour(Res.string.colour_frame, { it.frame ?: it.line }) { p, c -> p.copy(frame = c) },
    Colour(Res.string.colour_inactive_title, { it.inactiveTitle ?: it.raised }) { p, c -> p.copy(inactiveTitle = c) },
    Colour(Res.string.colour_tab, { it.tab ?: it.raised }) { p, c -> p.copy(tab = c) },
    Colour(Res.string.colour_divider, { it.divider ?: it.line }) { p, c -> p.copy(divider = c) },
    Colour(Res.string.colour_band, { it.band ?: it.accent }) { p, c -> p.copy(band = c) },
    Colour(Res.string.colour_highlight, { it.lit }) { p, c -> p.copy(highlight = c) },
    Colour(Res.string.colour_shadow, { it.shaded }) { p, c -> p.copy(shadow = c) },
    Colour(Res.string.colour_container, { it.panel }) { p, c -> p.copy(container = c) },
    Colour(Res.string.colour_inverse, { it.snack }) { p, c -> p.copy(inverse = c) },
    Colour(Res.string.colour_on_inverse, { it.onSnack }) { p, c -> p.copy(onInverse = c) },
)

private enum class IconGroup(val prefix: String, val label: StringResource) {
    Menu("menu/", Res.string.theme_icons_menu),
    Buttons("button/", Res.string.theme_icons_buttons),
    Commands("command/", Res.string.theme_icons_commands),
    Values("value/", Res.string.theme_icons_values),
}

private val Tints = listOf(
    null to Res.string.theme_icon_place,
    IconTint.Text to Res.string.colour_text,
    IconTint.Muted to Res.string.colour_muted,
    IconTint.Accent to Res.string.colour_accent,
    IconTint.Success to Res.string.colour_success,
    IconTint.Warning to Res.string.colour_warning,
    IconTint.Danger to Res.string.colour_danger,
)

private class Size(val label: StringResource, val read: (Metrics) -> Dp, val write: (Metrics, Dp) -> Metrics)

private val Sizes = listOf(
    Size(Res.string.size_row, { it.row }) { m, d -> m.copy(row = d) },
    Size(Res.string.size_menu_item, { it.menuItem }) { m, d -> m.copy(menuItem = d) },
    Size(Res.string.size_control, { it.control }) { m, d -> m.copy(control = d) },
    Size(Res.string.size_title, { it.title }) { m, d -> m.copy(title = d) },
    Size(Res.string.size_toolbar, { it.toolbar }) { m, d -> m.copy(toolbar = d) },
    Size(Res.string.size_icon, { it.icon }) { m, d -> m.copy(icon = d) },
    Size(Res.string.size_gap, { it.gap }) { m, d -> m.copy(gap = d) },
    Size(Res.string.size_padding, { it.padding }) { m, d -> m.copy(padding = d) },
    Size(Res.string.size_radius, { it.radius }) { m, d -> m.copy(radius = d) },
)

@Composable
fun ThemeEditor(source: ThemeFile, onCancel: () -> Unit, onSave: (ThemeFile) -> Unit) {
    val palette = Theme.palette
    val copyName = stringResource(Res.string.theme_copy, source.name)
    val name = remember(source) { TextFieldState(if (source in BundledThemes) copyName else source.name) }
    // the page of a browser gets its strings after the first composition
    LaunchedEffect(copyName) { if (source in BundledThemes && name.text.isEmpty()) name.setTextAndPlaceCursorAtEnd(copyName) }
    var theme by remember(source) { mutableStateOf(source) }
    var dark by remember(source) { mutableIntStateOf(if (source.light == null) 1 else 0) }
    var group by remember { mutableStateOf(IconGroup.Menu) }
    val compact = LocalCompact.current
    Box(Modifier.fillMaxSize().scrim(palette.workspace.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 980.dp).heightIn(max = 720.dp).padding(16.dp).dialogPanel(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(Res.string.theme_name), color = palette.muted)
                TextField(name, Modifier.width(260.dp).testTag("theme-name"))
            }
            val fields = @Composable { modifier: Modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TabStrip(listOf(stringResource(Res.string.theme_light), stringResource(Res.string.theme_dark)), dark, { dark = it })
                    val shown = if (dark == 1) theme.dark else theme.light
                    val other = if (dark == 1) theme.light else theme.dark
                    fun set(edited: Palette?) {
                        theme = if (dark == 1) theme.copy(dark = edited) else theme.copy(light = edited)
                    }
                    if (shown == null) {
                        Text(stringResource(Res.string.theme_no_palette), color = palette.muted)
                        if (other != null) ToolbarButton(stringResource(Res.string.theme_add_palette), Lucide.Plus, { set(other) })
                    } else {
                        key(dark) {
                            for (colour in Colours) ColourField(colour, shown) { picked -> (if (dark == 1) theme.dark else theme.light)?.let { set(colour.write(it, picked)) } }
                        }
                        if (other != null) ToolbarButton(stringResource(Res.string.theme_remove_palette), Lucide.Trash2, { set(null) })
                    }
                    Text(stringResource(Res.string.theme_sizes), Modifier.padding(top = 8.dp), style = Theme.type.strong)
                    for (size in Sizes) {
                        NumberField(stringResource(size.label), size.read(theme.metrics).value) { value -> theme = theme.copy(metrics = size.write(theme.metrics, value.dp)) }
                    }
                    NumberField(stringResource(Res.string.theme_font_size), theme.fontSize) { value -> theme = theme.copy(fontSize = value.coerceIn(9f, 20f)) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Checkbox(theme.classic, { theme = theme.copy(classic = it) }, Modifier.testTag("theme-classic"))
                        Text(stringResource(Res.string.theme_classic), Modifier.clickable { theme = theme.copy(classic = !theme.classic) }.hand())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Checkbox(theme.material, { theme = theme.copy(material = it) }, Modifier.testTag("theme-material"))
                        Text(stringResource(Res.string.theme_material), Modifier.clickable { theme = theme.copy(material = !theme.material) }.hand())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(Res.string.theme_font), Modifier.width(LABEL), color = palette.muted)
                        Select(theme.font, ThemeFont.entries, { fontLabel(it) }, { theme = theme.copy(font = it) }, Modifier.width(180.dp))
                    }
                    Text(stringResource(Res.string.theme_icons), Modifier.padding(top = 8.dp), style = Theme.type.strong)
                    Select(group, IconGroup.entries, { stringResource(it.label) }, { group = it }, Modifier.width(240.dp).testTag("icon-group"))
                    for (key in DefaultIcons.keys.filter { it.startsWith(group.prefix) }) {
                        val default = (if (theme.classic) ClassicIcons[key] else null) ?: DefaultIcons.getValue(key)
                        IconField(key.removePrefix(group.prefix), theme.icons[key] ?: default) { spec ->
                            theme = theme.copy(icons = if (spec == default) theme.icons - key else theme.icons + (key to spec))
                        }
                    }
                }
            }
            val preview = @Composable { modifier: Modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(Res.string.theme_preview), color = palette.muted)
                    NetworkHelmTheme(theme, dark = dark == 1) { Sample() }
                }
            }
            if (compact) {
                ScrollArea(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    preview(Modifier.fillMaxWidth())
                    fields(Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ScrollArea(Modifier.width(420.dp)) { fields(Modifier.fillMaxWidth()) }
                    preview(Modifier.weight(1f))
                }
            }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(stringResource(Res.string.theme_cancel), onCancel)
                Button(stringResource(Res.string.theme_save), {
                    onSave(theme.copy(name = name.text.toString().trim().ifEmpty { copyName }))
                }, Modifier.testTag("theme-save"), primary = true)
            }
        }
    }
}

private val LABEL = 170.dp

@Composable
private fun ColourField(colour: Colour, palette: Palette, onPick: (Color) -> Unit) {
    val value = colour.read(palette)
    val text = remember(colour) { TextFieldState(value.hex()) }
    LaunchedEffect(text) { snapshotFlow { text.text.toString() }.collect { typed -> parseColor(typed)?.let(onPick) } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(colour.label), Modifier.width(LABEL), color = Theme.palette.muted)
        Box(Modifier.size(20.dp).clip(RoundedCornerShape(3.dp)).background(value).border(1.dp, Theme.palette.line, RoundedCornerShape(3.dp)))
        TextField(text, Modifier.width(110.dp).testTag("colour-${colour.label.key}"), mono = true, error = parseColor(text.text.toString()) == null)
    }
}

@Composable
private fun NumberField(label: String, value: Float, onChange: (Float) -> Unit) {
    val text = remember(label) { TextFieldState(value.toString().removeSuffix(".0")) }
    LaunchedEffect(text) { snapshotFlow { text.text.toString() }.collect { typed -> typed.trim().toFloatOrNull()?.takeIf { it in 0f..200f }?.let(onChange) } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(LABEL), color = Theme.palette.muted)
        TextField(text, Modifier.width(80.dp), mono = true, error = text.text.toString().trim().toFloatOrNull()?.takeIf { it in 0f..200f } == null)
    }
}

@Composable
private fun IconField(label: String, spec: String, onChange: (String) -> Unit) {
    val icon = remember(spec) { themeIcon(spec) }
    val base = spec.split(':').let { parts -> if (parts[0] == PICTURE) parts.take(3).joinToString(":") else parts[0] }
    fun specOf(base: String, tint: IconTint?) = if (tint == null) base else "$base:${tint.name.lowercase()}"
    val exchange = LocalFileExchange.current
    val scope = rememberCoroutineScope()
    var refused by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(LABEL), color = Theme.palette.muted, singleLine = true, overflow = TextOverflow.Ellipsis)
        var open by remember { mutableStateOf(false) }
        fun pick(picked: String) {
            open = false
            refused = null
            onChange(picked)
        }
        UnstyledDropdownMenu(
            expanded = open,
            onExpandedChange = { open = it },
            panel = {
                MenuPanel {
                    FlowRow(Modifier.width(336.dp).padding(horizontal = 4.dp)) {
                        for ((other, vector) in IconSet) IconButton(vector, other, { pick(specOf(other, icon?.tint)) })
                    }
                    Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(stringResource(Res.string.theme_icon_picture), {
                            open = false
                            scope.launch {
                                val file = exchange.pick().firstOrNull() ?: return@launch
                                // a file the system does not give is no picture this platform reads either
                                val picture = try {
                                    pictureSpec(file.read())
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    null
                                }
                                if (picture == null) refused = file.name else pick(specOf(picture, icon?.tint))
                            }
                        }, Modifier.testTag("icon-picture"))
                        Button(stringResource(Res.string.theme_icon_none), { pick("") })
                    }
                }
            },
            anchor = {
                if (icon != null) IconButton(icon.vector, base.takeUnless { it.startsWith(PICTURE) } ?: label, { open = true }, Modifier.testTag("icon-$label"))
                else Button(stringResource(Res.string.theme_icon_none), { open = true }, Modifier.testTag("icon-$label"))
            },
        )
        if (icon != null) Select(icon.tint, Tints.map { it.first }, { tint -> stringResource(Tints.first { it.first == tint }.second) }, { onChange(specOf(base, it)) }, Modifier.width(170.dp))
        refused?.let { Text(stringResource(Res.string.theme_icon_not_picture, it), color = Theme.palette.danger, style = Theme.type.small) }
    }
}

@Composable
private fun fontLabel(font: ThemeFont) = stringResource(if (font == ThemeFont.Inter) Res.string.theme_font_inter else Res.string.theme_font_system)

@Composable
private fun Sample() {
    val palette = Theme.palette
    val metrics = Theme.metrics
    val shape = RoundedCornerShape(metrics.radius)
    Box(Modifier.fillMaxWidth().background(palette.workspace).padding(16.dp)) {
        Column(Modifier.fillMaxWidth().clip(shape).background(palette.surface).border(1.dp, palette.frame ?: if (palette.title == null) palette.accent else palette.line, shape)) {
            Row(Modifier.fillMaxWidth().height(metrics.title).background(palette.title ?: palette.accent).padding(horizontal = metrics.padding), verticalAlignment = Alignment.CenterVertically) {
                Text("Addresses", color = if (Theme.material && palette.title != null) palette.text else palette.onAccent, style = Theme.type.strong)
            }
            TabStrip(listOf("Addresses", "Routes", "ARP"), 0, {}, Modifier.fillMaxWidth().background(palette.raised))
            Row(Modifier.fillMaxWidth().height(metrics.toolbar).background(palette.raised), verticalAlignment = Alignment.CenterVertically) {
                val icons = Theme.icons
                for ((key, label) in listOf("new" to Res.string.list_new, "remove" to Res.string.list_remove, "comment" to Res.string.list_comment)) {
                    val icon = icons["button/$key"]
                    ToolbarButton(stringResource(label), icon?.vector, {}, tint = palette.tintOf(icon))
                }
            }
            Row(Modifier.fillMaxWidth().height(metrics.row).background(palette.raised).padding(horizontal = metrics.padding), verticalAlignment = Alignment.CenterVertically) {
                Text("Address", Modifier.weight(1f), color = palette.muted)
                Text("Interface", Modifier.weight(1f), color = palette.muted)
            }
            SampleRow("", "192.0.2.1/24", "ether1", palette.surface, palette.text)
            SampleRow("D", "198.51.100.7/24", "bridge", palette.selection, palette.text)
            SampleRow("X", "203.0.113.1/24", "ether2", palette.surface, palette.faint)
            SampleRow("I", "192.0.2.254/24", "vlan10", palette.surface, palette.danger)
            FlowRow(
                Modifier.fillMaxWidth().padding(metrics.padding),
                horizontalArrangement = Arrangement.spacedBy(metrics.gap),
                verticalArrangement = Arrangement.spacedBy(metrics.gap),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(remember { TextFieldState("192.0.2.1/24") }, Modifier.width(160.dp), mono = true)
                Button(stringResource(Res.string.form_ok), {}, primary = true)
                Button(stringResource(Res.string.list_cancel), {})
                Text("running", color = palette.success, style = Theme.type.small)
                Text("link down", color = palette.warning, style = Theme.type.small)
            }
        }
    }
}

@Composable
private fun SampleRow(flag: String, address: String, port: String, background: Color, text: Color) {
    val palette = Theme.palette
    val metrics = Theme.metrics
    val grid = palette.grid ?: palette.line
    Row(
        Modifier.fillMaxWidth().height(metrics.row).background(background)
            .drawBehind { drawLine(grid, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
            .padding(horizontal = metrics.padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(22.dp)) {
            if (flag.isNotEmpty()) Text(flag, Modifier.clip(RoundedCornerShape(2.dp)).background(palette.badge).padding(horizontal = 3.dp), color = palette.muted, style = Theme.type.small)
        }
        Text(address, Modifier.weight(1f), color = text, style = Theme.type.mono)
        Text(port, Modifier.weight(1f), color = text)
    }
}
