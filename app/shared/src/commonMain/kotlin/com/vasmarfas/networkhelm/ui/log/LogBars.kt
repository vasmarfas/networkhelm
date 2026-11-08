package com.vasmarfas.networkhelm.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowDownToLine
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.ClipboardCopy
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Funnel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PanelBottom
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Tag
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledDropdownMenu
import com.composeunstyled.UnstyledIcon
import com.vasmarfas.networkhelm.ui.kit.CountPill
import com.vasmarfas.networkhelm.ui.kit.DropdownItem
import com.vasmarfas.networkhelm.ui.kit.Hint
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.MenuPanel
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.kit.ToolbarButton
import com.vasmarfas.networkhelm.ui.kit.hand
import com.vasmarfas.networkhelm.ui.theme.Palette
import com.vasmarfas.networkhelm.ui.theme.Theme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.list_find
import networkhelm.app.shared.generated.resources.list_live
import networkhelm.app.shared.generated.resources.list_paused
import networkhelm.app.shared.generated.resources.list_selected
import networkhelm.app.shared.generated.resources.log_ago_days
import networkhelm.app.shared.generated.resources.log_ago_hours
import networkhelm.app.shared.generated.resources.log_ago_minutes
import networkhelm.app.shared.generated.resources.log_ago_now
import networkhelm.app.shared.generated.resources.log_ago_seconds
import networkhelm.app.shared.generated.resources.log_chip_hidden
import networkhelm.app.shared.generated.resources.log_chip_message
import networkhelm.app.shared.generated.resources.log_clear_filters
import networkhelm.app.shared.generated.resources.log_collapse
import networkhelm.app.shared.generated.resources.log_copy
import networkhelm.app.shared.generated.resources.log_copy_csv
import networkhelm.app.shared.generated.resources.log_copy_json
import networkhelm.app.shared.generated.resources.log_copy_lines
import networkhelm.app.shared.generated.resources.log_copy_messages
import networkhelm.app.shared.generated.resources.log_details
import networkhelm.app.shared.generated.resources.log_latest
import networkhelm.app.shared.generated.resources.log_latest_new
import networkhelm.app.shared.generated.resources.log_level_critical
import networkhelm.app.shared.generated.resources.log_level_debug
import networkhelm.app.shared.generated.resources.log_level_error
import networkhelm.app.shared.generated.resources.log_level_info
import networkhelm.app.shared.generated.resources.log_level_warning
import networkhelm.app.shared.generated.resources.log_lines
import networkhelm.app.shared.generated.resources.log_lines_of
import networkhelm.app.shared.generated.resources.log_new
import networkhelm.app.shared.generated.resources.log_next
import networkhelm.app.shared.generated.resources.log_no_hits
import networkhelm.app.shared.generated.resources.log_only_matches
import networkhelm.app.shared.generated.resources.log_panel
import networkhelm.app.shared.generated.resources.log_position
import networkhelm.app.shared.generated.resources.log_previous
import networkhelm.app.shared.generated.resources.log_save_csv
import networkhelm.app.shared.generated.resources.log_save_text
import networkhelm.app.shared.generated.resources.log_select_all
import networkhelm.app.shared.generated.resources.log_severity
import networkhelm.app.shared.generated.resources.log_severity_all
import networkhelm.app.shared.generated.resources.log_severity_critical
import networkhelm.app.shared.generated.resources.log_severity_debug
import networkhelm.app.shared.generated.resources.log_severity_error
import networkhelm.app.shared.generated.resources.log_severity_info
import networkhelm.app.shared.generated.resources.log_severity_problems
import networkhelm.app.shared.generated.resources.log_severity_warning
import networkhelm.app.shared.generated.resources.log_span_15m
import networkhelm.app.shared.generated.resources.log_span_5m
import networkhelm.app.shared.generated.resources.log_span_any
import networkhelm.app.shared.generated.resources.log_span_day
import networkhelm.app.shared.generated.resources.log_span_hour
import networkhelm.app.shared.generated.resources.log_span_today
import networkhelm.app.shared.generated.resources.log_time
import networkhelm.app.shared.generated.resources.log_time_full
import networkhelm.app.shared.generated.resources.log_time_relative
import networkhelm.app.shared.generated.resources.log_time_short
import networkhelm.app.shared.generated.resources.log_topics
import networkhelm.app.shared.generated.resources.log_topics_all
import networkhelm.app.shared.generated.resources.log_view
import networkhelm.app.shared.generated.resources.log_wrap
import networkhelm.app.shared.generated.resources.window_loading

enum class CopyKind { Lines, Messages, Csv, Json }

internal fun Severity.color(palette: Palette): Color = when (this) {
    Severity.Critical, Severity.Error -> palette.danger
    Severity.Warning -> palette.warning
    Severity.Info -> palette.text
    Severity.Debug -> palette.faint
}

private fun Severity.resource(): StringResource = when (this) {
    Severity.Critical -> Res.string.log_severity_critical
    Severity.Error -> Res.string.log_severity_error
    Severity.Warning -> Res.string.log_severity_warning
    Severity.Info -> Res.string.log_severity_info
    Severity.Debug -> Res.string.log_severity_debug
}

@Composable
internal fun Severity.label(): String = stringResource(resource())

@Composable
internal fun Severity.level(): String = stringResource(
    when (this) {
        Severity.Critical -> Res.string.log_level_critical
        Severity.Error -> Res.string.log_level_error
        Severity.Warning -> Res.string.log_level_warning
        Severity.Info -> Res.string.log_level_info
        Severity.Debug -> Res.string.log_level_debug
    },
)

@Composable
private fun Span.label(): String = stringResource(
    when (this) {
        Span.Any -> Res.string.log_span_any
        Span.Minutes5 -> Res.string.log_span_5m
        Span.Minutes15 -> Res.string.log_span_15m
        Span.Hour -> Res.string.log_span_hour
        Span.Day -> Res.string.log_span_day
        Span.Today -> Res.string.log_span_today
    },
)

@Composable
internal fun agoText(seconds: Long): String {
    val ago = ago(seconds)
    return when (ago.unit) {
        AgoUnit.Now -> stringResource(Res.string.log_ago_now)
        AgoUnit.Seconds -> stringResource(Res.string.log_ago_seconds, ago.count)
        AgoUnit.Minutes -> stringResource(Res.string.log_ago_minutes, ago.count)
        AgoUnit.Hours -> stringResource(Res.string.log_ago_hours, ago.count)
        AgoUnit.Days -> stringResource(Res.string.log_ago_days, ago.count)
    }
}

@Composable
internal fun TopicTag(word: String, tint: Color? = null, modifier: Modifier = Modifier) {
    val palette = Theme.palette
    Text(
        word,
        modifier.background((tint ?: palette.badge).copy(alpha = if (tint != null) 0.16f else 1f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        style = Theme.type.small,
        color = tint ?: palette.muted,
        singleLine = true,
    )
}

@Composable
internal fun LogToolbar(
    state: LogState,
    query: TextFieldState,
    find: FocusRequester,
    severities: Map<Severity, Int>,
    topics: List<Pair<String, Int>>,
    stamped: Boolean,
    hits: Int,
    position: Int,
    fresh: Int,
    onStep: (Int) -> Unit,
    onLatest: () -> Unit,
    onCopy: (CopyKind) -> Unit,
    onSave: (CopyKind) -> Unit,
    onSelectAll: () -> Unit,
    onSearchKey: (KeyEvent) -> Boolean,
) {
    val palette = Theme.palette
    val compact = LocalCompact.current
    FlowRow(
        Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ToolbarButton(
            stringResource(if (state.live) Res.string.list_live else Res.string.list_paused),
            if (state.live) Lucide.Play else Lucide.Pause,
            { state.live = !state.live },
            Modifier.testTag("log-live"),
            active = !state.live,
            iconOnly = compact,
            tint = if (state.live) palette.success else palette.warning,
        )
        ToolbarButton(
            if (fresh > 0) stringResource(Res.string.log_latest_new, fresh) else stringResource(Res.string.log_latest),
            Lucide.ArrowDownToLine,
            onLatest,
            Modifier.testTag("log-latest"),
            iconOnly = compact,
        )
        TextField(
            query,
            Modifier.then(if (compact) Modifier.fillMaxWidth() else Modifier.width(200.dp)).focusRequester(find).onPreviewKeyEvent(onSearchKey).testTag("log-find"),
            placeholder = stringResource(Res.string.list_find),
        )
        if (query.text.isNotEmpty()) {
            IconButton(Lucide.ChevronUp, stringResource(Res.string.log_previous), { onStep(-1) }, Modifier.testTag("log-previous"), enabled = hits > 0)
            IconButton(Lucide.ChevronDown, stringResource(Res.string.log_next), { onStep(1) }, Modifier.testTag("log-next"), enabled = hits > 0)
            Box(Modifier.height(Theme.metrics.toolbar).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (hits == 0) stringResource(Res.string.log_no_hits) else stringResource(Res.string.log_position, position.coerceAtLeast(1), hits),
                    Modifier.testTag("log-hits"),
                    style = Theme.type.small,
                    color = palette.muted,
                    singleLine = true,
                )
            }
        }
        BarMenu(stringResource(Res.string.log_severity), Lucide.Funnel, state.severities.isNotEmpty(), compact, "log-severity") { close ->
            for (severity in listOf(Severity.Critical, Severity.Error, Severity.Warning, Severity.Info, Severity.Debug)) {
                CheckLine(severity.label(), severities[severity]?.toString(), severity in state.severities, tint = severity.color(palette)) { state.toggle(severity) }
            }
            MenuLine(stringResource(Res.string.log_severity_problems), null) {
                close()
                state.severities = setOf(Severity.Critical, Severity.Error, Severity.Warning)
            }
            MenuLine(stringResource(Res.string.log_severity_all), null) {
                close()
                state.severities = emptySet()
            }
        }
        BarMenu(stringResource(Res.string.log_topics), Lucide.Tag, state.topics.isNotEmpty() || state.hidden.isNotEmpty(), compact, "log-topics") { close ->
            MenuLine(stringResource(Res.string.log_topics_all), null) {
                close()
                state.topics = emptySet()
                state.hidden = emptySet()
            }
            for ((topic, count) in topics) CheckLine(topic, count.toString(), topic in state.topics) { state.toggleTopic(topic) }
        }
        BarMenu(stringResource(Res.string.log_time), Lucide.Clock, state.span != Span.Any, true, "log-span") { close ->
            for (span in Span.entries) {
                CheckLine(span.label(), null, span == state.span, enabled = stamped || span == Span.Any) {
                    close()
                    state.span = span
                }
            }
        }
        BarMenu(stringResource(Res.string.log_view), Lucide.SlidersHorizontal, false, true, "log-view") { close ->
            for ((style, label) in listOf(TimeStyle.Short to Res.string.log_time_short, TimeStyle.Full to Res.string.log_time_full, TimeStyle.Relative to Res.string.log_time_relative)) {
                CheckLine(stringResource(label), null, state.times == style) {
                    close()
                    state.times = style
                }
            }
            CheckLine(stringResource(Res.string.log_wrap), null, state.wrap) { state.wrap = !state.wrap }
            CheckLine(stringResource(Res.string.log_collapse), null, state.collapse) { state.collapse = !state.collapse }
            CheckLine(stringResource(Res.string.log_only_matches), null, state.onlyMatches) { state.onlyMatches = !state.onlyMatches }
        }
        BarMenu(stringResource(Res.string.log_copy), Lucide.ClipboardCopy, false, true, "log-copy") { close ->
            fun pick(action: () -> Unit) {
                close()
                action()
            }
            MenuLine(stringResource(Res.string.log_copy_lines), "Ctrl+C") { pick { onCopy(CopyKind.Lines) } }
            MenuLine(stringResource(Res.string.log_copy_messages), "Ctrl+Shift+C") { pick { onCopy(CopyKind.Messages) } }
            MenuLine(stringResource(Res.string.log_copy_csv), null) { pick { onCopy(CopyKind.Csv) } }
            MenuLine(stringResource(Res.string.log_copy_json), null) { pick { onCopy(CopyKind.Json) } }
            MenuLine(stringResource(Res.string.log_save_text), null) { pick { onSave(CopyKind.Lines) } }
            MenuLine(stringResource(Res.string.log_save_csv), null) { pick { onSave(CopyKind.Csv) } }
            MenuLine(stringResource(Res.string.log_select_all), "Ctrl+A") { pick(onSelectAll) }
        }
        ToolbarButton(stringResource(Res.string.log_panel), Lucide.PanelBottom, { state.details = !state.details }, Modifier.testTag("log-details-toggle"), active = state.details, iconOnly = true)
    }
}

@Composable
private fun BarMenu(title: String, icon: ImageVector, active: Boolean, iconOnly: Boolean, tag: String, content: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    UnstyledDropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        panel = { MenuPanel { content { open = false } } },
        anchor = { ToolbarButton(title, icon, { open = true }, Modifier.testTag(tag), active = active, iconOnly = iconOnly) },
    )
}

@Composable
private fun CheckLine(label: String, count: String?, checked: Boolean, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    val palette = Theme.palette
    DropdownItem(onClick = { if (enabled) onClick() }) {
        Row(Modifier.widthIn(min = 220.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(14.dp)) { if (checked) UnstyledIcon(Lucide.Check, null, Modifier.size(14.dp), tint = palette.accent) }
            Text(label, Modifier.weight(1f), color = if (!enabled) palette.faint else tint ?: palette.text, singleLine = true)
            if (count != null) Text(count, color = palette.muted, style = Theme.type.small)
        }
    }
}

@Composable
internal fun MenuLine(title: String, keys: String?, onClick: () -> Unit) {
    DropdownItem(onClick) {
        Row(Modifier.widthIn(min = 220.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), singleLine = true)
            if (keys != null) Text(keys, Modifier.padding(start = 16.dp), color = Theme.palette.muted, style = Theme.type.small)
        }
    }
}

@Composable
internal fun LogChips(state: LogState) {
    if (!state.narrowed) return
    val palette = Theme.palette
    FlowRow(
        Modifier.fillMaxWidth().background(palette.surface).padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (severity in state.severities) Chip(severity.label(), severity.color(palette)) { state.toggle(severity) }
        for (topic in state.topics) Chip(topic, null) { state.toggleTopic(topic) }
        for (topic in state.hidden) Chip(stringResource(Res.string.log_chip_hidden, topic), null) { state.toggleHidden(topic) }
        state.message?.let { message -> Chip(stringResource(Res.string.log_chip_message, message.take(48)), null) { state.message = null } }
        if (state.span != Span.Any) Chip(state.span.label(), null) { state.span = Span.Any }
        Text(
            stringResource(Res.string.log_clear_filters),
            Modifier.clickable { state.clearNarrowing() }.hand().padding(vertical = 2.dp).testTag("log-clear"),
            style = Theme.type.small,
            color = palette.accent,
            singleLine = true,
        )
    }
}

@Composable
private fun Chip(label: String, tint: Color?, onRemove: () -> Unit) {
    val palette = Theme.palette
    Row(
        Modifier.background(palette.raised, RoundedCornerShape(50)).clickable(onClick = onRemove).hand().padding(start = 8.dp, end = 5.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = Theme.type.small, color = tint ?: palette.text, singleLine = true)
        UnstyledIcon(Lucide.X, null, Modifier.size(11.dp), tint = palette.muted)
    }
}

@Composable
internal fun LogStatus(state: LogState, shown: Int, all: Int, counts: Map<Severity, Int>, selected: Int, loading: Boolean, fresh: Int, day: String?) {
    val palette = Theme.palette
    val compact = LocalCompact.current
    DisableSelection {
        Row(
            Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val count = when {
                loading -> stringResource(Res.string.window_loading)
                shown == all -> pluralStringResource(Res.plurals.log_lines, shown, shown)
                else -> pluralStringResource(Res.plurals.log_lines_of, all, shown, all)
            }
            CountPill(count, Modifier.testTag("log-count"))
            if (selected > 0) Text(stringResource(Res.string.list_selected, selected), style = Theme.type.small, color = palette.muted)
            val problems = (counts[Severity.Critical] ?: 0) + (counts[Severity.Error] ?: 0)
            if (problems > 0 && !compact) {
                val both = setOf(Severity.Critical, Severity.Error)
                QuickCount(palette.danger, problems, state.severities.containsAll(both), Severity.Error.label(), "log-quick-errors") {
                    state.severities = if (state.severities.containsAll(both)) state.severities - both else state.severities + both
                }
            }
            val warnings = counts[Severity.Warning] ?: 0
            if (warnings > 0 && !compact) QuickCount(palette.warning, warnings, Severity.Warning in state.severities, Severity.Warning.label(), "log-quick-warnings") { state.toggle(Severity.Warning) }
            Spacer(Modifier.weight(1f))
            if (day != null) Text(day, Modifier.testTag("log-day"), style = Theme.type.small, color = palette.muted, singleLine = true)
            if (!state.live && fresh > 0) Text(stringResource(Res.string.log_new, fresh), style = Theme.type.small, color = palette.warning)
            Row(Modifier.clickable { state.live = !state.live }.hand().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val live = state.live
                UnstyledIcon(if (live) Lucide.Play else Lucide.Pause, null, Modifier.size(if (compact) 14.dp else 10.dp), tint = if (live) palette.success else palette.warning)
                if (!compact) Text(stringResource(if (live) Res.string.list_live else Res.string.list_paused), style = Theme.type.small, color = if (live) palette.muted else palette.warning, singleLine = true)
            }
        }
    }
}

@Composable
private fun QuickCount(tint: Color, count: Int, active: Boolean, label: String, tag: String, onClick: () -> Unit) {
    val palette = Theme.palette
    Hint(label) {
        Row(
            Modifier.background(if (active) palette.selection else Color.Transparent, RoundedCornerShape(50)).clickable(onClick = onClick).hand().padding(horizontal = 6.dp, vertical = 1.dp).testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.size(7.dp).background(tint, CircleShape))
            Text(count.toString(), style = Theme.type.small, color = palette.text, singleLine = true)
        }
    }
}
