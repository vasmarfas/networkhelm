package com.vasmarfas.networkhelm.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.ui.kit.copyClipboard
import com.vasmarfas.networkhelm.ui.kit.writeText
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap

@Composable
fun LineViewer(text: String, language: Language, controls: EditorControls, modifier: Modifier = Modifier) {
    val palette = Theme.palette
    val base = Theme.type.mono
    val style = base.copy(color = palette.text, fontSize = base.fontSize * controls.zoom)
    val lines = remember(text) { text.split('\n') }
    val starts = remember(text) { lineStarts(text) }
    val list = rememberLazyListState()
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()
    val clipboard = copyClipboard()
    val find = controls.find
    val focus = remember { FocusRequester() }
    var from by remember(text) { mutableStateOf(-1) }
    var to by remember(text) { mutableStateOf(-1) }
    val found by remember(text, find) { derivedStateOf { if (find.open) findAll(text, find.options()) else Matches(emptyList()) } }
    val digits = lines.size.toString().length
    val gutter = with(LocalDensity.current) { (digits * base.fontSize.toPx() * controls.zoom * 0.62f).toDp() } + 14.dp

    fun go(step: Int) {
        val ranges = found.ranges
        if (ranges.isEmpty()) return
        find.current = (find.current + step).mod(ranges.size)
        val line = lineOf(starts, ranges[find.current].first)
        from = line
        to = line
        scope.launch { list.scrollToItem((line - 3).coerceAtLeast(0)) }
    }

    LaunchedEffect(found.ranges, find.query.text.toString()) {
        if (found.ranges.isEmpty()) return@LaunchedEffect
        find.current = find.current.coerceIn(0, found.ranges.lastIndex)
    }

    fun copy() {
        if (from < 0) return
        val first = minOf(from, to)
        val last = maxOf(from, to)
        scope.launch { clipboard.writeText(lines.subList(first, last + 1).joinToString("\n")) }
    }

    fun onKey(event: KeyEvent, preview: Boolean): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        // AltGr comes as Ctrl with Alt, and the letter it types is no shortcut
        val ctrl = event.isCtrlPressed && !event.isAltPressed || event.isMetaPressed
        return when {
            // the fields of the bars copy and select their own text, the lines take what the fields let through
            ctrl && !preview && event.key == Key.C -> true.also { copy() }
            ctrl && !preview && event.key == Key.A -> true.also {
                from = 0
                to = lines.lastIndex
            }
            ctrl && event.key == Key.F -> true.also { find.show(false) }
            ctrl && event.key == Key.G -> true.also { controls.goto = true }
            ctrl && (event.key == Key.Equals || event.key == Key.Plus || event.key == Key.NumPadAdd) -> true.also { controls.zoomBy(0.1f) }
            ctrl && (event.key == Key.Minus || event.key == Key.NumPadSubtract) -> true.also { controls.zoomBy(-0.1f) }
            event.key == Key.F3 -> true.also { go(if (event.isShiftPressed) -1 else 1) }
            event.key == Key.Escape && find.open -> true.also { find.open = false }
            else -> false
        }
    }

    Column(modifier.onPreviewKeyEvent { onKey(it, true) }.onKeyEvent { onKey(it, false) }.focusRequester(focus).focusable().testTag("viewer")) {
        if (find.open) FindBar(find, found, true, ::go, {}, {}) { find.open = false }
        if (controls.goto) {
            GotoBar(lines.size, (if (from < 0) 0 else from) + 1) { line ->
                controls.goto = false
                if (line != null) {
                    from = line - 1
                    to = line - 1
                    scope.launch { list.scrollToItem((line - 4).coerceAtLeast(0)) }
                }
                focus.requestFocus()
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val room = maxWidth
            Box(Modifier.fillMaxSize().then(if (controls.wrap) Modifier else Modifier.horizontalScroll(horizontal))) {
                LazyColumn(Modifier.then(if (controls.wrap) Modifier.fillMaxSize() else Modifier.widthIn(min = room).fillMaxSize()), state = list) {
                    itemsIndexed(lines) { index, line ->
                        val chosen = from >= 0 && index in minOf(from, to)..maxOf(from, to)
                        val matchesHere = found.ranges.filter { it.first >= starts[index] && it.first <= starts[index] + line.length }
                        val coloured = buildAnnotatedString {
                            append(line)
                            for (span in highlight(line, language)) addStyle(SpanStyle(color = palette.toneColor(span.tone), fontStyle = if (span.tone == Tone.Comment) FontStyle.Italic else null), span.start, span.end)
                            for (range in matchesHere) addStyle(SpanStyle(background = palette.warning.copy(alpha = 0.4f)), (range.first - starts[index]).coerceAtLeast(0), (range.last + 1 - starts[index]).coerceAtMost(line.length))
                        }
                        Row(
                            Modifier.fillMaxWidth().background(if (chosen) palette.selection else palette.surface)
                                .clickable {
                                    focus.requestFocus()
                                    from = index
                                    to = index
                                },
                        ) {
                            Text("${index + 1}", Modifier.width(gutter).background(palette.raised).padding(end = 8.dp), style = style.copy(color = palette.faint), textAlign = TextAlign.End, singleLine = true)
                            Text(coloured, Modifier.padding(horizontal = 6.dp), style = style, singleLine = !controls.wrap)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HexViewer(bytes: ByteArray, controls: EditorControls, modifier: Modifier = Modifier) {
    val palette = Theme.palette
    val base = Theme.type.mono
    val style = base.copy(color = palette.text, fontSize = base.fontSize * controls.zoom)
    val rows = (bytes.size + 15) / 16
    LazyColumn(modifier.fillMaxSize().background(palette.surface).testTag("hex")) {
        itemsIndexed(List(rows) { it }) { _, row ->
            val start = row * 16
            val end = minOf(start + 16, bytes.size)
            val hex = (start until end).joinToString(" ") { (bytes[it].toInt() and 0xFF).toString(16).padStart(2, '0') }
            val ascii = (start until end).joinToString("") { index -> bytes[index].toInt().let { if (it in 32..126) it.toChar().toString() else "." } }
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(start.toString(16).padStart(8, '0'), style = style.copy(color = palette.faint))
                Text(hex.padEnd(47), style = style)
                Text(ascii, style = style.copy(color = palette.muted))
            }
        }
    }
}

@Composable
fun ImageViewer(bytes: ByteArray, scale: Float?, onSize: (Int, Int) -> Unit, modifier: Modifier = Modifier, failure: @Composable () -> Unit) {
    val bitmap: ImageBitmap? = remember(bytes) {
        try {
            bytes.decodeToImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
    if (bitmap == null) {
        failure()
        return
    }
    LaunchedEffect(bitmap) { onSize(bitmap.width, bitmap.height) }
    val density = LocalDensity.current
    Box(modifier.fillMaxSize().background(Theme.palette.raised).testTag("image"), contentAlignment = Alignment.Center) {
        if (scale == null) {
            Image(bitmap, null, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Inside, filterQuality = FilterQuality.Medium)
        } else {
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                with(density) { Image(bitmap, null, Modifier.size((bitmap.width * scale).toDp(), (bitmap.height * scale).toDp()), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Medium) }
            }
        }
    }
}
