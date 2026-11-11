package com.vasmarfas.networkhelm.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CaseSensitive
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Regex
import com.composables.icons.lucide.ReplaceAll
import com.composables.icons.lucide.WholeWord
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.theme.Palette
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.file_find
import networkhelm.app.shared.generated.resources.file_find_next
import networkhelm.app.shared.generated.resources.file_find_previous
import networkhelm.app.shared.generated.resources.file_goto
import networkhelm.app.shared.generated.resources.file_goto_go
import networkhelm.app.shared.generated.resources.file_match_case
import networkhelm.app.shared.generated.resources.file_matches
import networkhelm.app.shared.generated.resources.file_no_matches
import networkhelm.app.shared.generated.resources.file_regex
import networkhelm.app.shared.generated.resources.file_replace
import networkhelm.app.shared.generated.resources.file_replace_all
import networkhelm.app.shared.generated.resources.file_replace_with
import networkhelm.app.shared.generated.resources.file_whole_word
import networkhelm.app.shared.generated.resources.output_close

@Stable
class FindState {
    var open by mutableStateOf(false)
    var replace by mutableStateOf(false)
    val query = TextFieldState()
    val with = TextFieldState()
    var matchCase by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    var regex by mutableStateOf(false)
    var current by mutableIntStateOf(0)
    var focusRequests by mutableIntStateOf(0)

    fun options() = FindOptions(query.text.toString(), matchCase, wholeWord, regex)

    fun show(replacing: Boolean) {
        open = true
        replace = replacing
        focusRequests++
    }
}

@Stable
class EditorControls {
    var wrap by mutableStateOf(true)
    var zoom by mutableFloatStateOf(1f)
    var goto by mutableStateOf(false)
    val find = FindState()

    fun zoomBy(step: Float) {
        zoom = (zoom + step).coerceIn(0.6f, 2.5f)
    }
}

@Stable
class EditorState(val document: TextDocument, val language: Language) {
    val text = TextFieldState(document.text, TextRange.Zero)
    private var saved by mutableStateOf(document.text)
    private var savedEnding by mutableStateOf(document.ending)
    private var savedCp1251 by mutableStateOf(document.cp1251)
    val dirty by derivedStateOf { text.text.length != saved.length || text.text.toString() != saved || ending != savedEnding || cp1251 != savedCp1251 }
    var layout by mutableStateOf<TextLayoutResult?>(null)
    var viewport by mutableIntStateOf(0)
    var ending by mutableStateOf(document.ending)
    var cp1251 by mutableStateOf(document.cp1251)
    val starts: IntArray by derivedStateOf { lineStarts(text.text) }

    fun snapshot(): String = text.text.toString()

    @OptIn(ExperimentalFoundationApi::class)
    val canUndo: Boolean get() = text.undoState.canUndo

    @OptIn(ExperimentalFoundationApi::class)
    val canRedo: Boolean get() = text.undoState.canRedo

    @OptIn(ExperimentalFoundationApi::class)
    fun undo() = text.undoState.undo()

    @OptIn(ExperimentalFoundationApi::class)
    fun redo() = text.undoState.redo()

    fun saveSnapshot(): EditorSnapshot {
        val current = snapshot()
        return EditorSnapshot(current, ending, cp1251, document.encode(current, ending, cp1251))
    }

    fun markSaved(snapshot: EditorSnapshot) {
        saved = snapshot.text
        savedEnding = snapshot.ending
        savedCp1251 = snapshot.cp1251
    }

    fun select(range: IntRange) {
        text.edit { selection = TextRange(range.first, range.last + 1) }
    }

    fun place(offset: Int) {
        text.edit { selection = TextRange(offset.coerceIn(0, length)) }
    }

    fun replaceRange(range: IntRange, with: String) {
        text.edit { replace(range.first, range.last + 1, with) }
    }

    fun replaceEverything(with: String) {
        text.edit { replace(0, length, with) }
    }

    fun lineStart(line: Int): Int = starts[line.coerceIn(0, starts.lastIndex)]
}

class EditorSnapshot(val text: String, val ending: LineEnding, val cp1251: Boolean, val bytes: ByteArray)

fun Palette.toneColor(tone: Tone): Color = when (tone) {
    Tone.Comment -> muted
    Tone.String -> warning
    Tone.Keyword, Tone.Name, Tone.Path -> accent
    Tone.Number, Tone.Variable -> success
    Tone.Error -> danger
    Tone.Warning -> warning
}

// every key press lays the whole text out again, and the styles add to that by their number and by the length of the text:
// about 30 ms on a desktop for as many of them as these two allow, which is an export of some 20 KB
internal const val STYLES_MOST = 2_400
internal const val CHARS_A_STYLE = 100

// the matches next to the current one are painted in a text of any length
internal const val MATCHES_LEAST = 100

internal class Colouring(
    val language: Language,
    val palette: Palette,
    val matches: List<IntRange>,
    val current: Int,
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val most = STYLES_MOST - length / CHARS_A_STYLE
        val spans = highlight(asCharSequence(), language).takeIf { it.size <= most }.orEmpty()
        val shown = maxOf(most, MATCHES_LEAST)
        val first = (current - shown / 2).coerceIn(0, maxOf(0, matches.size - shown))
        // the matches come with the composition, which lags behind the text of the buffer
        val painted = matches.subList(first, minOf(first + shown, matches.size)).takeWhile { it.last < length }
        if (spans.isEmpty() && painted.isEmpty()) return
        // the buffer records every style as a change and walks through all the changes before it;
        // one change over the whole text takes the later ones in, and the walk stays a single step
        addStyle(SpanStyle(), 0, length)
        for (span in spans) {
            addStyle(SpanStyle(color = palette.toneColor(span.tone), fontStyle = if (span.tone == Tone.Comment) FontStyle.Italic else null), span.start, span.end)
        }
        for ((index, range) in painted.withIndex()) {
            addStyle(SpanStyle(background = palette.warning.copy(alpha = if (first + index == current) 0.65f else 0.28f)), range.first, range.last + 1)
        }
    }
}

@Composable
fun TextEditor(state: EditorState, controls: EditorControls, readOnly: Boolean, modifier: Modifier = Modifier, onSave: () -> Unit = {}) {
    val palette = Theme.palette
    val base = Theme.type.mono
    val style = base.copy(color = palette.text, fontSize = base.fontSize * controls.zoom)
    val scroll = rememberScrollState()
    val horizontal = rememberScrollState()
    val find = controls.find
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val starts = state.starts
    val options = find.options()
    val found by remember(state, find) { derivedStateOf { if (find.open) findAll(state.text.text, find.options()) else Matches(emptyList()) } }
    val colouring = remember(state.language, palette, found.ranges, find.current) { Colouring(state.language, palette, found.ranges, find.current) }
    val caret = state.text.selection.start
    val caretLine = lineOf(starts, caret)

    fun reveal(offset: Int) {
        val layout = state.layout ?: return
        val line = layout.getLineForOffset(offset.coerceIn(0, layout.layoutInput.text.length))
        val middle = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        scope.launch { scroll.animateScrollTo((middle - state.viewport / 2f).toInt().coerceIn(0, scroll.maxValue)) }
    }

    fun go(step: Int) {
        val ranges = found.ranges
        if (ranges.isEmpty()) return
        find.current = (find.current + step).mod(ranges.size)
        state.select(ranges[find.current])
        reveal(ranges[find.current].first)
    }

    LaunchedEffect(found.ranges, options.query) {
        if (found.ranges.isEmpty()) return@LaunchedEffect
        val from = state.text.selection.min
        find.current = found.ranges.indexOfFirst { it.first >= from }.takeIf { it >= 0 } ?: 0
    }

    fun replaceCurrent() {
        val ranges = found.ranges
        if (readOnly || ranges.isEmpty()) return
        val at = ranges[find.current.coerceIn(0, ranges.lastIndex)]
        val with = find.with.text.toString()
        val text = state.snapshot()
        val replaced = replaceAll(text.substring(at.first, at.last + 1), options, with)?.first ?: return
        state.replaceRange(at, replaced)
        val after = at.first + replaced.length
        find.current = found.ranges.indexOfFirst { it.first >= after }.coerceAtLeast(0)
        go(0)
    }

    fun replaceEverything() {
        if (readOnly) return
        val result = replaceAll(state.snapshot(), options, find.with.text.toString()) ?: return
        if (result.second > 0) state.replaceEverything(result.first)
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        // AltGr comes as Ctrl with Alt, and the letter it types is no shortcut
        val ctrl = event.isCtrlPressed && !event.isAltPressed || event.isMetaPressed
        return when {
            ctrl && event.key == Key.S && !readOnly -> true.also { onSave() }
            ctrl && event.key == Key.F -> true.also { find.show(false) }
            ctrl && event.key == Key.H && !readOnly -> true.also { find.show(true) }
            ctrl && event.key == Key.G -> true.also { controls.goto = true }
            ctrl && (event.key == Key.Equals || event.key == Key.Plus || event.key == Key.NumPadAdd) -> true.also { controls.zoomBy(0.1f) }
            ctrl && (event.key == Key.Minus || event.key == Key.NumPadSubtract) -> true.also { controls.zoomBy(-0.1f) }
            ctrl && event.key == Key.Zero -> true.also { controls.zoom = 1f }
            event.key == Key.F3 -> true.also { go(if (event.isShiftPressed) -1 else 1) }
            event.key == Key.Escape && (find.open || controls.goto) -> true.also {
                find.open = false
                controls.goto = false
                focus.requestFocus()
            }
            else -> false
        }
    }

    Column(modifier.onPreviewKeyEvent(::onKey).testTag("editor")) {
        if (find.open) {
            FindBar(find, found, readOnly, ::go, ::replaceCurrent, ::replaceEverything) {
                find.open = false
                focus.requestFocus()
            }
        }
        if (controls.goto) {
            GotoBar(starts.size, caretLine + 1) { line ->
                controls.goto = false
                if (line != null) {
                    val offset = state.lineStart(line - 1)
                    state.place(offset)
                    reveal(offset)
                }
                focus.requestFocus()
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Gutter(state, scroll, starts, caretLine, style)
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().onSizeChanged { state.viewport = it.height }) {
                val fill = maxWidth
                DisableSelection {
                    BasicTextField(
                        state = state.text,
                        modifier = (if (controls.wrap) Modifier.fillMaxSize() else Modifier.fillMaxHeight().widthIn(min = fill).horizontalScroll(horizontal))
                            .padding(horizontal = 6.dp)
                            .focusRequester(focus)
                            .testTag("editor-text"),
                        readOnly = readOnly,
                        textStyle = style,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        cursorBrush = SolidColor(palette.accent),
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        scrollState = scroll,
                        outputTransformation = colouring,
                        onTextLayout = { layout -> state.layout = layout() },
                    )
                }
            }
        }
    }
}

@Composable
private fun Gutter(state: EditorState, scroll: ScrollState, starts: IntArray, caretLine: Int, style: TextStyle) {
    val palette = Theme.palette
    val measurer = rememberTextMeasurer()
    val digits = starts.size.toString().length
    val widest = remember(digits, style) { measurer.measure("9".repeat(digits), style).size.width }
    val width = with(LocalDensity.current) { widest.toDp() } + 14.dp
    Canvas(Modifier.width(width).fillMaxHeight().background(palette.raised)) {
        val layout = state.layout ?: return@Canvas
        val text = layout.layoutInput.text
        val top = scroll.value.toFloat()
        val first = layout.getLineForVerticalPosition(top)
        val last = layout.getLineForVerticalPosition(top + size.height)
        for (row in first..last) {
            val start = layout.getLineStart(row)
            if (start != 0 && text[start - 1] != '\n') continue
            val number = lineOf(starts, start)
            val label = measurer.measure((number + 1).toString(), style.copy(color = if (number == caretLine) palette.accent else palette.faint))
            drawText(label, topLeft = Offset(size.width - label.size.width - 8.dp.toPx(), layout.getLineTop(row) - top))
        }
    }
}

@Composable
internal fun FindBar(
    find: FindState,
    found: Matches,
    readOnly: Boolean,
    go: (Int) -> Unit,
    replaceCurrent: () -> Unit,
    replaceEverything: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = Theme.palette
    val focus = remember { FocusRequester() }
    LaunchedEffect(find.focusRequests) { focus.requestFocus() }
    Column(Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextField(find.query, Modifier.widthIn(min = 160.dp, max = 280.dp).weight(1f, fill = false).focusRequester(focus).testTag("find-query"), placeholder = stringResource(Res.string.file_find), error = found.invalid)
            IconButton(Lucide.ChevronUp, stringResource(Res.string.file_find_previous), { go(-1) }, enabled = found.ranges.isNotEmpty())
            IconButton(Lucide.ChevronDown, stringResource(Res.string.file_find_next), { go(1) }, enabled = found.ranges.isNotEmpty())
            IconButton(Lucide.CaseSensitive, stringResource(Res.string.file_match_case), { find.matchCase = !find.matchCase }, active = find.matchCase)
            IconButton(Lucide.WholeWord, stringResource(Res.string.file_whole_word), { find.wholeWord = !find.wholeWord }, active = find.wholeWord)
            IconButton(Lucide.Regex, stringResource(Res.string.file_regex), { find.regex = !find.regex }, active = find.regex)
            val count = when {
                find.query.text.isEmpty() -> ""
                found.ranges.isEmpty() -> stringResource(Res.string.file_no_matches)
                else -> stringResource(Res.string.file_matches, find.current.coerceIn(0, found.ranges.lastIndex) + 1, found.ranges.size)
            }
            Text(count, Modifier.testTag("find-count"), style = Theme.type.small, color = if (found.invalid || found.ranges.isEmpty() && count.isNotEmpty()) palette.danger else palette.muted)
            IconButton(Lucide.X, stringResource(Res.string.output_close), onClose)
        }
        if (find.replace && !readOnly) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextField(find.with, Modifier.widthIn(min = 160.dp, max = 280.dp).weight(1f, fill = false).testTag("find-with"), placeholder = stringResource(Res.string.file_replace_with))
                Button(stringResource(Res.string.file_replace), replaceCurrent, enabled = found.ranges.isNotEmpty())
                IconButton(Lucide.ReplaceAll, stringResource(Res.string.file_replace_all), replaceEverything, enabled = found.ranges.isNotEmpty())
            }
        }
    }
}

@Composable
internal fun GotoBar(lines: Int, current: Int, onDone: (Int?) -> Unit) {
    val field = remember { TextFieldState(current.toString()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().background(Theme.palette.raised).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(Res.string.file_goto, lines), color = Theme.palette.muted)
        TextField(
            field,
            Modifier.width(100.dp).focusRequester(focus).testTag("goto-line").onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) onDone(field.text.toString().trim().toIntOrNull()?.coerceIn(1, lines)).let { true } else false
            },
        )
        Button(stringResource(Res.string.file_goto_go), { onDone(field.text.toString().trim().toIntOrNull()?.coerceIn(1, lines)) })
        IconButton(Lucide.X, stringResource(Res.string.output_close), { onDone(null) })
    }
}
