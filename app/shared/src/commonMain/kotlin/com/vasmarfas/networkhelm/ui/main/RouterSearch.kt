package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.routeros.schema.label
import com.vasmarfas.networkhelm.search.Finding
import com.vasmarfas.networkhelm.search.Lookup
import com.vasmarfas.networkhelm.search.find
import com.vasmarfas.networkhelm.search.lookup
import com.vasmarfas.networkhelm.search.places
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.kit.dialogPanel
import com.vasmarfas.networkhelm.ui.kit.hand
import com.vasmarfas.networkhelm.ui.kit.scrim
import com.vasmarfas.networkhelm.ui.theme.Palette
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.search_find
import networkhelm.app.shared.generated.resources.search_found
import networkhelm.app.shared.generated.resources.search_hint
import networkhelm.app.shared.generated.resources.search_nothing
import networkhelm.app.shared.generated.resources.search_placeholder
import networkhelm.app.shared.generated.resources.search_running
import networkhelm.app.shared.generated.resources.search_skipped
import networkhelm.app.shared.generated.resources.search_title
import networkhelm.app.shared.generated.resources.settings_close

@Stable
class RouterFinds(val tab: RouterTab) {
    val hits = mutableStateListOf<Finding.Hit>()
    val skipped = mutableStateListOf<Finding.TooBig>()
    var problem by mutableStateOf<String?>(null)
    var done by mutableStateOf(false)

    suspend fun collect(lookup: Lookup) {
        try {
            find(tab.session, lookup).collect { finding ->
                when (finding) {
                    is Finding.Hit -> hits += finding
                    is Finding.TooBig -> skipped += finding
                }
            }
        } catch (e: DeviceException) {
            problem = e.message
        } finally {
            done = true
        }
    }
}

@Stable
class RouterSearch(private val scope: CoroutineScope) {
    val query = TextFieldState()
    var searched by mutableStateOf<String?>(null)
        private set
    var finds by mutableStateOf(emptyList<RouterFinds>())
        private set
    private var job: Job? = null

    val running: Boolean get() = finds.any { !it.done }

    fun start(routers: List<RouterTab>) {
        val text = query.text.toString().trim()
        val lookup = lookup(text) ?: return
        job?.cancel()
        searched = text
        finds = routers.map(::RouterFinds)
        job = scope.launch { for (found in finds) launch { found.collect(lookup) } }
    }
}

@Composable
fun RouterSearchDialog(search: RouterSearch, routers: List<RouterTab>, onOpen: (RouterTab, Finding.Hit) -> Unit, onClose: () -> Unit) {
    val palette = Theme.palette
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val text = search.query.text.toString().trim()
    val lines = buildList {
        for (found in search.finds.filter { it.tab in routers }) {
            add(Line.Router(found))
            for (skipped in found.skipped) add(Line.Skipped(found.tab, skipped))
            val order = places(found.tab.session.device.platform)
            for (hit in found.hits.sortedBy { order.indexOf(it.place) }) add(Line.Hit(found.tab, hit))
        }
    }
    val hits = lines.filterIsInstance<Line.Hit>()
    var chosen by remember(search.finds) { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    LaunchedEffect(chosen) {
        val line = lines.indexOf(hits.getOrNull(chosen) ?: return@LaunchedEffect)
        if (line >= 0) list.animateScrollToItem(line)
    }
    val answered = text == search.searched && hits.isNotEmpty()
    Box(Modifier.fillMaxSize().scrim(palette.workspace.copy(alpha = 0.6f), onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 860.dp).fillMaxHeight(0.85f).padding(16.dp).dialogPanel(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.search_title), Modifier.weight(1f), style = Theme.type.strong)
                IconButton(Lucide.X, stringResource(Res.string.settings_close), onClose, Modifier.testTag("router-search-close"))
            }
            Text(stringResource(Res.string.search_hint), color = palette.muted, style = Theme.type.small)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    search.query,
                    Modifier.weight(1f).focusRequester(focus).testTag("router-search-query").onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> chosen = (chosen + 1).coerceAtMost(hits.lastIndex.coerceAtLeast(0))
                            Key.DirectionUp -> chosen = (chosen - 1).coerceAtLeast(0)
                            Key.Enter, Key.NumPadEnter -> {
                                val line = hits.getOrNull(chosen)
                                if (answered && line != null) onOpen(line.tab, line.hit) else search.start(routers)
                            }
                            Key.Escape -> onClose()
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    },
                    placeholder = stringResource(Res.string.search_placeholder),
                )
                Button(stringResource(Res.string.search_find), { search.start(routers) }, enabled = lookup(text) != null, primary = true)
            }
            val status = when {
                search.running -> stringResource(Res.string.search_running, hits.size)
                search.searched == null -> null
                hits.isEmpty() -> stringResource(Res.string.search_nothing)
                else -> stringResource(Res.string.search_found, hits.size)
            }
            status?.let { Text(it, Modifier.testTag("router-search-status"), color = palette.muted, style = Theme.type.small) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), list) {
                items(lines.size) { index ->
                    when (val line = lines[index]) {
                        is Line.Router -> RouterLine(line.found)
                        is Line.Skipped -> Text(
                            stringResource(Res.string.search_skipped, line.tab.placeOf(line.skipped.place.path).joinToString(" › "), line.skipped.count),
                            Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                            color = palette.warning,
                            style = Theme.type.small,
                        )
                        is Line.Hit -> HitLine(line.tab, line.hit, hits.indexOf(line) == chosen) { onOpen(line.tab, line.hit) }
                    }
                }
            }
        }
    }
}

private sealed interface Line {
    class Router(val found: RouterFinds) : Line

    class Skipped(val tab: RouterTab, val skipped: Finding.TooBig) : Line

    class Hit(val tab: RouterTab, val hit: Finding.Hit) : Line
}

@Composable
private fun RouterLine(found: RouterFinds) {
    val palette = Theme.palette
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${found.tab.session.device.identity} (${found.tab.address})", style = Theme.type.strong, maxLines = 1)
        found.problem?.let { Text(deviceMessage(it), color = palette.danger, style = Theme.type.small, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (!found.done) Text("…", color = palette.muted)
        Spacer(Modifier.weight(1f))
        Text("${found.hits.size}", color = palette.muted, style = Theme.type.small)
    }
}

@Composable
private fun HitLine(tab: RouterTab, hit: Finding.Hit, chosen: Boolean, onClick: () -> Unit) {
    val palette = Theme.palette
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Theme.metrics.radius)).background(if (chosen) palette.selection else palette.surface)
            .clickable(onClick = onClick).hand().padding(horizontal = 12.dp, vertical = 5.dp).testTag("router-search-hit"),
    ) {
        Text(trail(tab.placeOf(hit.place.path), palette.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(fields(hit, palette), maxLines = 2, overflow = TextOverflow.Ellipsis, style = Theme.type.small)
    }
}

private fun fields(hit: Finding.Hit, palette: Palette): AnnotatedString = buildAnnotatedString {
    var first = true
    for (field in hit.place.fields) {
        val value = hit.item[field]?.takeIf { it.isNotBlank() } ?: continue
        if (!first) withStyle(SpanStyle(color = palette.faint)) { append("  ·  ") }
        first = false
        withStyle(SpanStyle(color = palette.muted)) { append(if (field == ".name") "Name" else label(field)) }
        append(' ')
        // the lists of UCI come one value a line
        val shown = value.lines().joinToString(", ")
        if (field == hit.field) withStyle(SpanStyle(color = palette.accent, fontWeight = FontWeight.SemiBold)) { append(shown) } else append(shown)
    }
}
