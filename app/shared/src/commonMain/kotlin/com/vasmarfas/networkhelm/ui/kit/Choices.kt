package com.vasmarfas.networkhelm.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composeunstyled.DropdownMenuScope
import com.composeunstyled.LocalTextStyle
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledDropdownMenu
import com.composeunstyled.UnstyledIcon
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.theme.lit
import com.vasmarfas.networkhelm.ui.theme.on
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.form_choices
import networkhelm.app.shared.generated.resources.list_find
import networkhelm.app.shared.generated.resources.search_nothing
import networkhelm.app.shared.generated.resources.tabs_all
import networkhelm.app.shared.generated.resources.tabs_earlier
import networkhelm.app.shared.generated.resources.tabs_later

@Composable
fun <T> Segments(value: T, choices: List<T>, label: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val palette = Theme.palette
    val material = Theme.material
    val shape = if (material) RoundedCornerShape(50) else RoundedCornerShape(Theme.metrics.radius)
    val edge = if (material) palette.faint else palette.line
    val least = if (LocalCompact.current) 40.dp else 0.dp
    Row(modifier.height(Theme.metrics.control).clip(shape).border(1.dp, edge, shape)) {
        choices.forEachIndexed { index, choice ->
            val chosen = choice == value
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(edge))
            Box(
                Modifier
                    .widthIn(min = least)
                    .fillMaxHeight()
                    .background((if (!chosen) palette.surface else if (material) palette.selection else palette.accent).eased())
                    .selectable(selected = chosen, interactionSource = null, indication = LocalIndication.current, role = Role.RadioButton, onClick = { onSelect(choice) })
                    .hand()
                    .padding(horizontal = if (material) 16.dp else 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                DisableSelection {
                    Text(
                        label(choice),
                        style = if (material) Theme.type.strong else LocalTextStyle.current,
                        color = if (chosen && !material) palette.on(palette.accent) else palette.text,
                        singleLine = true,
                    )
                }
            }
        }
    }
}

@Composable
fun <T> Radios(value: T, choices: List<T>, label: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val palette = Theme.palette
    val dot = if (Theme.classic) palette.text else palette.accent
    val material = Theme.material
    val edge = if (material) palette.muted else palette.line
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for (choice in choices) {
            val chosen = choice == value
            Row(
                Modifier.height(Theme.metrics.control).selectable(selected = chosen, interactionSource = null, indication = null, role = Role.RadioButton, onClick = { onSelect(choice) }).hand(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (material) 8.dp else 5.dp),
            ) {
                Box(
                    Modifier.size(if (material) 20.dp else 16.dp).clip(CircleShape).background(palette.surface)
                        .border(if (material) 2.dp else 1.dp, (if (chosen && !Theme.classic) palette.accent else edge).eased(), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (chosen) Box(Modifier.size(if (material) 10.dp else 8.dp).clip(CircleShape).background(dot))
                }
                DisableSelection { Text(label(choice), color = palette.text, singleLine = true) }
            }
        }
    }
}

// the typed text is a value as soon as read makes one of it; Esc brings back the value the line had when it got the focus
@Composable
fun <T> Combo(
    value: T,
    choices: List<T>,
    label: @Composable (T) -> String,
    read: (String) -> T?,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    wrong: String? = null,
    apart: (T) -> Boolean = { false },
) {
    val palette = Theme.palette
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(value) }
    var width by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val shown = label(value)
    val before = label(entered)
    val state = remember { TextFieldState(shown) }
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val reading by rememberUpdatedState(read)
    val bad = state.text.isNotEmpty() && read(state.text.toString()) == null
    LaunchedEffect(value) {
        if (reading(state.text.toString()) != value) state.setTextAndPlaceCursorAtEnd(shown)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { text -> reading(text)?.let { if (it != current) change(it) } }
    }
    UnstyledDropdownMenu(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
        panel = {
            ChoiceList(choices, value, label, { choice ->
                onChange(choice)
                expanded = false
            }, apart = apart, minWidth = width)
        },
        anchor = {
            Column(Modifier.onSizeChanged { width = with(density) { it.width.toDp() } }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    CompositionLocalProvider(LocalSubmit provides null) {
                        TextField(
                            state,
                            Modifier.weight(1f)
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when {
                                        event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                            read(state.text.toString())?.let { if (it != value) onChange(it) }
                                            expanded = false
                                        }
                                        event.key == Key.Escape && (expanded || value != entered || state.text.toString() != before) -> {
                                            state.setTextAndPlaceCursorAtEnd(before)
                                            if (value != entered && read(before) == null) onChange(entered)
                                            expanded = false
                                        }
                                        else -> return@onPreviewKeyEvent false
                                    }
                                    true
                                }
                                .onFocusChanged {
                                    if (it.isFocused && !focused) entered = value
                                    if (!it.isFocused && focused && read(state.text.toString()) == null) state.setTextAndPlaceCursorAtEnd(shown)
                                    focused = it.isFocused
                                },
                            error = bad,
                        )
                    }
                    IconButton(Lucide.ChevronDown, stringResource(Res.string.form_choices), { expanded = !expanded })
                }
                if (bad && wrong != null) Text(wrong, Modifier.padding(top = 2.dp), color = palette.danger, style = Theme.type.small)
            }
        },
    )
}

@Composable
fun <T> Select(
    value: T,
    choices: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    dimmed: (T) -> Boolean = { false },
    hint: @Composable (T) -> String? = { null },
    apart: (T) -> Boolean = { false },
) {
    var expanded by remember { mutableStateOf(false) }
    val palette = Theme.palette
    val material = Theme.material
    val shape = RoundedCornerShape(Theme.metrics.radius)
    UnstyledDropdownMenu(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
        panel = {
            ChoiceList(choices, value, label, { choice ->
                onSelect(choice)
                expanded = false
            }, dimmed, hint, apart)
        },
        anchor = {
            UnstyledButton(
                onClick = { expanded = true },
                indication = LocalIndication.current,
                contentPadding = PaddingValues(start = if (material) 12.dp else 8.dp, end = if (material) 8.dp else 6.dp),
                modifier = Modifier.height(Theme.metrics.control).clip(shape).background(palette.surface).border(1.dp, if (material) palette.faint else palette.line, shape).hand(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DisableSelection { Text(label(value), Modifier.weight(1f), color = if (dimmed(value)) palette.faint else palette.text, singleLine = true, overflow = TextOverflow.Ellipsis) }
                    UnstyledIcon(Lucide.ChevronDown, null, Modifier.size(14.dp), tint = palette.muted)
                }
            }
        },
    )
}

// the search line has the focus as the list opens, but not on a phone, where the keyboard would cover the list
@Composable
fun <T> DropdownMenuScope.ChoiceList(
    choices: List<T>,
    current: T?,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
    dimmed: (T) -> Boolean = { false },
    hint: @Composable (T) -> String? = { null },
    apart: (T) -> Boolean = { false },
    minWidth: Dp = 0.dp,
) {
    val palette = Theme.palette
    val labels = choices.map { label(it) }
    val long = choices.size > 15
    val typed = remember { TextFieldState() }
    val text = typed.text.toString().trim()
    val shown = choices.indices.filter { text.isEmpty() || labels[it].contains(text, ignoreCase = true) }
    val start = choices.indexOf(current)
    var marked by remember(text) { mutableIntStateOf(if (text.isEmpty()) start else 0) }
    val places = remember(shown.size) { List(shown.size) { BringIntoViewRequester() } }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val search = remember { FocusRequester() }
    val opened = remember { mutableStateOf(false) }
    fun mark(position: Int) {
        marked = position
        scope.launch { places.getOrNull(position)?.bringIntoView() }
    }
    LaunchedEffect(text) { if (text.isNotEmpty()) scroll.scrollTo(0) }
    val line: @Composable () -> Unit = {
        CompositionLocalProvider(LocalSubmit provides null) {
            TextField(
                typed,
                Modifier.widthIn(min = 160.dp).fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp).focusRequester(search).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> mark((marked + 1).coerceAtMost(shown.lastIndex.coerceAtLeast(0)))
                        Key.DirectionUp -> mark((marked - 1).coerceAtLeast(0))
                        Key.Enter, Key.NumPadEnter -> onPick(choices[shown.getOrNull(marked) ?: return@onPreviewKeyEvent false])
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
                placeholder = stringResource(Res.string.list_find),
            )
        }
    }
    MenuPanel(scroll, if (long && !LocalCompact.current) search else null, if (long) line else null, minWidth) {
        if (shown.isEmpty()) Text(stringResource(Res.string.search_nothing), Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = palette.faint)
        shown.forEachIndexed { position, index ->
            val choice = choices[index]
            if (position > 0 && apart(choice)) Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(palette.line))
            val place = Modifier.bringIntoViewRequester(places[position]).then(
                if (index == start) Modifier.onPlaced {
                    if (!opened.value) {
                        opened.value = true
                        scope.launch { scroll.scrollTo(it.positionInParent().y.roundToInt() - scroll.viewportSize / 3) }
                    }
                } else Modifier,
            )
            DropdownItem({ onPick(choice) }, place, selected = if (long) position == marked else index == start) {
                val note = hint(choice)
                if (note == null) {
                    Text(labels[index], color = if (dimmed(choice)) palette.faint else palette.text)
                } else {
                    Column {
                        Text(labels[index], color = if (dimmed(choice)) palette.faint else palette.text)
                        Text(note, Modifier.widthIn(max = 280.dp), color = palette.muted, style = Theme.type.small)
                    }
                }
            }
        }
    }
}

@Composable
fun TabStrip(
    titles: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    also: Set<Int> = emptySet(),
    onAdd: ((Int) -> Unit)? = null,
    titleText: Color? = null,
    under: Color? = null,
) {
    val palette = Theme.palette
    val classic = Theme.classic
    val material = Theme.material
    val boxed = !classic && (titleText != null || under != null)
    val chosenBox = under ?: palette.raised
    val otherBox = if (titleText != null) palette.surface else palette.line
    var shift by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // maxValue is Int.MAX_VALUE until the strip is measured
    val overflow = scroll.maxValue in 1 until Int.MAX_VALUE
    fun page(sign: Int) = scope.launch { scroll.animateScrollBy(sign * scroll.viewportSize * 0.8f) }
    val places = remember(titles.size) { List(titles.size) { BringIntoViewRequester() } }
    val tabShape = RoundedCornerShape(topStart = Theme.metrics.radius, topEnd = Theme.metrics.radius)
    Row(modifier.height(Theme.metrics.toolbar).then(if (boxed && titleText == null) Modifier.padding(start = 4.dp) else Modifier)) {
        if (titleText != null) {
            TabList(titles, selected) { index ->
                onSelect(index)
                scope.launch { places[index].bringIntoView() }
            }
        }
        if (overflow) StripArrow(Lucide.ChevronLeft, stringResource(Res.string.tabs_earlier), scroll.canScrollBackward, titleText) { page(-1) }
        Row(
            Modifier.weight(1f, fill = false).fillMaxHeight().horizontalScroll(scroll).pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) shift = event.keyboardModifiers.isShiftPressed
                    }
                }
            },
            horizontalArrangement = Arrangement.spacedBy(if (boxed) 2.dp else 0.dp),
        ) {
            titles.forEachIndexed { index, title ->
                val active = index == selected || index in also
                val mark by animateFloatAsState(if (active) 1f else 0f, tween(if (material) MOTION_MILLIS else 0))
                Box(
                    Modifier
                        .fillMaxHeight()
                        .bringIntoViewRequester(places[index])
                        .then(if (classic && !active) Modifier.padding(top = 2.dp) else Modifier)
                        .then(if (boxed) Modifier.padding(top = 4.dp).clip(tabShape).background(if (active) chosenBox else otherBox) else Modifier)
                        .selectable(selected = active, interactionSource = null, indication = LocalIndication.current, role = Role.Tab, onClick = {
                            if (shift && onAdd != null) onAdd(index) else onSelect(index)
                            scope.launch { places[index].bringIntoView() }
                        })
                        .hand()
                        .drawBehind {
                            if (classic) {
                                val px = 1.dp.toPx()
                                if (!active) drawRect(palette.tab ?: palette.raised)
                                drawLine(palette.line, Offset(px / 2, 0f), Offset(px / 2, size.height), px)
                                drawLine(palette.line, Offset(0f, px / 2), Offset(size.width, px / 2), px)
                                drawLine(palette.line, Offset(size.width - px / 2, 0f), Offset(size.width - px / 2, size.height), px)
                                drawLine(palette.lit, Offset(px * 1.5f, px), Offset(px * 1.5f, size.height), px)
                                if (!active) drawLine(palette.line, Offset(0f, size.height - px / 2), Offset(size.width, size.height - px / 2), px)
                            } else if (material) {
                                val thick = 3.dp.toPx()
                                val inset = if (boxed) 0f else 16.dp.toPx()
                                val width = (size.width - inset * 2) * mark
                                drawRoundRect(palette.accent, Offset((size.width - width) / 2, if (boxed) 0f else size.height - thick), Size(width, thick), CornerRadius(thick))
                            } else if (active) {
                                val top = if (boxed) 0f else size.height - 2.dp.toPx()
                                drawRect(palette.accent, Offset(0f, top), Size(size.width, 2.dp.toPx()))
                            }
                        }
                        .padding(horizontal = if (classic) 6.dp else if (material) 16.dp else 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val color = if (active && material && !boxed) palette.accent else if (active || classic || boxed) palette.text else palette.muted
                    DisableSelection { Text(title, style = if (active && !classic) Theme.type.strong else Theme.type.body, color = color) }
                }
            }
        }
        if (overflow) StripArrow(Lucide.ChevronRight, stringResource(Res.string.tabs_later), scroll.canScrollForward, titleText) { page(1) }
    }
}

@Composable
private fun TabList(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    UnstyledDropdownMenu(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        panel = {
            ChoiceList(titles.indices.toList(), selected, { titles[it] }, { index ->
                onSelect(index)
                expanded = false
            })
        },
        anchor = {
            UnstyledButton(
                onClick = { expanded = true },
                indication = LocalIndication.current,
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(top = 4.dp, end = 2.dp)
                    .clip(RoundedCornerShape(topStart = Theme.metrics.radius, topEnd = Theme.metrics.radius))
                    .background(Theme.palette.surface)
                    .width(22.dp)
                    .hand(),
            ) {
                UnstyledIcon(Lucide.ChevronDown, stringResource(Res.string.tabs_all), Modifier.size(14.dp), tint = Theme.palette.text)
            }
        },
    )
}

@Composable
private fun StripArrow(icon: ImageVector, description: String, enabled: Boolean, tint: Color?, onClick: () -> Unit) {
    UnstyledButton(onClick = onClick, enabled = enabled, indication = LocalIndication.current, modifier = Modifier.width(if (LocalCompact.current) 40.dp else 24.dp).fillMaxHeight().hand()) {
        val colour = tint?.let { if (enabled) it else it.copy(alpha = 0.4f) } ?: if (enabled) Theme.palette.text else Theme.palette.faint
        UnstyledIcon(icon, description, Modifier.size(14.dp), tint = colour)
    }
}
