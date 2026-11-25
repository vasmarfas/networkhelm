package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.chart.Chart
import com.vasmarfas.networkhelm.ui.chart.Legend
import com.vasmarfas.networkhelm.ui.chart.Ring
import com.vasmarfas.networkhelm.ui.chart.Series
import com.vasmarfas.networkhelm.ui.chart.byteSize
import com.vasmarfas.networkhelm.ui.files.LocalFileExchange
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.Checkbox
import com.vasmarfas.networkhelm.ui.kit.ScrollArea
import com.vasmarfas.networkhelm.ui.kit.Segments
import com.vasmarfas.networkhelm.ui.kit.TabStrip
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.kit.hand
import com.vasmarfas.networkhelm.ui.kit.rememberCopier
import com.vasmarfas.networkhelm.ui.terminal.LocalTerminal
import com.vasmarfas.networkhelm.ui.terminal.TerminalView
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.docker_copy
import networkhelm.app.shared.generated.resources.docker_files_empty
import networkhelm.app.shared.generated.resources.docker_files_up
import networkhelm.app.shared.generated.resources.docker_files_upload
import networkhelm.app.shared.generated.resources.docker_log_all
import networkhelm.app.shared.generated.resources.docker_log_filter
import networkhelm.app.shared.generated.resources.docker_log_timestamps
import networkhelm.app.shared.generated.resources.docker_open
import networkhelm.app.shared.generated.resources.docker_refresh
import networkhelm.app.shared.generated.resources.docker_save
import networkhelm.app.shared.generated.resources.docker_stats_cpu
import networkhelm.app.shared.generated.resources.docker_stats_disk
import networkhelm.app.shared.generated.resources.docker_stats_memory
import networkhelm.app.shared.generated.resources.docker_stats_network
import networkhelm.app.shared.generated.resources.docker_stats_read
import networkhelm.app.shared.generated.resources.docker_stats_received
import networkhelm.app.shared.generated.resources.docker_stats_sent
import networkhelm.app.shared.generated.resources.docker_stats_waiting
import networkhelm.app.shared.generated.resources.docker_stats_written
import networkhelm.app.shared.generated.resources.docker_tab_changes
import networkhelm.app.shared.generated.resources.docker_tab_files
import networkhelm.app.shared.generated.resources.docker_tab_inspect
import networkhelm.app.shared.generated.resources.docker_tab_logs
import networkhelm.app.shared.generated.resources.docker_tab_processes
import networkhelm.app.shared.generated.resources.docker_tab_stats
import networkhelm.app.shared.generated.resources.docker_tab_terminal
import networkhelm.app.shared.generated.resources.docker_terminal_any
import networkhelm.app.shared.generated.resources.docker_terminal_hint
import networkhelm.app.shared.generated.resources.docker_terminal_none
import networkhelm.app.shared.generated.resources.docker_terminal_shell
import networkhelm.app.shared.generated.resources.docker_terminal_user
import networkhelm.app.shared.generated.resources.list_retry
import networkhelm.app.shared.generated.resources.window_loading
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val CONTAINER = MenuPath.parse("container")
private const val MAX_LOG_LINES = 20_000
private const val SAMPLES = 120

// a window of a container: the container by its name, and the tab it opens on, as the window of the menu of its row is named
internal class ContainerCall(val container: String, val tab: String)

// the tabs of the window by the name the menu of the table opens them with
private val CONTAINER_TABS = listOf(
    "logs" to Res.string.docker_tab_logs,
    "terminal" to Res.string.docker_tab_terminal,
    "stats" to Res.string.docker_tab_stats,
    "processes" to Res.string.docker_tab_processes,
    "inspect" to Res.string.docker_tab_inspect,
    "changes" to Res.string.docker_tab_changes,
    "files" to Res.string.docker_tab_files,
)

// one container in a window of its own, opened from its row: its log, a terminal in it, its load, processes, the settings as the
// engine tells them, what it wrote over its image and its files; every tab asks through the session, so the gateway serves it too
@Composable
internal fun LinuxDockerContainerView(tab: RouterTab, window: Long, container: String, opened: String, sudo: String) {
    val palette = Theme.palette
    var shown by remember(window) { mutableIntStateOf(CONTAINER_TABS.indexOfFirst { it.first == opened }.coerceAtLeast(0)) }
    // kept while another tab is shown, as the console of the window is
    var terminal by remember(window) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(palette.surface).testTag("docker-container")) {
        TabStrip(CONTAINER_TABS.map { stringResource(it.second) }, shown, { shown = it })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (CONTAINER_TABS[shown].first) {
                "logs" -> ContainerLog(tab.session, container)
                "terminal" -> ContainerTerminal(tab, window, container, terminal, sudo, instant = opened == "terminal") { terminal = it }
                "stats" -> ContainerStats(tab.session, container)
                "processes" -> ContainerRows(tab.session, container, "top", 3.seconds)
                "inspect" -> DockerInspect(tab.session, CONTAINER, container)
                "changes" -> ContainerRows(tab.session, container, "changes", null)
                else -> ContainerFiles(tab.session, container)
            }
        }
    }
}

// the log follows the container; the filter keeps the lines with its text, and the lines shown can be saved
@Composable
private fun ContainerLog(session: DeviceSession, container: String) {
    val palette = Theme.palette
    val lines = remember(container) { mutableStateListOf<Item>() }
    var stamped by remember { mutableStateOf(true) }
    var tail by remember { mutableStateOf("500") }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val filter = remember { TextFieldState() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val exchange = LocalFileExchange.current
    LaunchedEffect(container, stamped, tail, attempt) {
        lines.clear()
        problem = null
        try {
            session.run(CONTAINER, "log", mapOf("id" to container, "tail" to tail, "timestamps" to "$stamped")).collect { line ->
                lines += line
                if (lines.size > MAX_LOG_LINES + 1_000) lines.subList(0, lines.size - MAX_LOG_LINES).clear()
            }
        } catch (e: DeviceException) {
            problem = e.message
        }
    }
    val text = filter.text.toString()
    val shown = if (text.isEmpty()) lines else lines.filter { it["message"].orEmpty().contains(text, ignoreCase = true) }
    // a log scrolled up by the user stays where it is
    val atEnd = !list.canScrollForward
    LaunchedEffect(shown.size) { if (atEnd && shown.isNotEmpty()) list.scrollToItem(shown.lastIndex) }
    fun written(line: Item) = listOfNotNull(line["time"]?.takeIf { stamped }, line["message"]).joinToString(" ")
    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(filter, Modifier.width(220.dp).testTag("docker-log-filter"), placeholder = stringResource(Res.string.docker_log_filter))
            Segments(tail, listOf("100", "500", "5000", "all"), { if (it == "all") stringResource(Res.string.docker_log_all) else it }, { tail = it })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(stamped, { stamped = it })
                Text(stringResource(Res.string.docker_log_timestamps), style = Theme.type.small)
            }
            Button(stringResource(Res.string.docker_save), { scope.launch { exchange.save("$container.log", shown.joinToString("\n", transform = ::written).encodeToByteArray()) } })
        }
        problem?.let { Problem(it) { attempt++ } }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = list) {
                items(shown.size) { index ->
                    val line = shown[index]
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (stamped) Text(line["time"].orEmpty().take(23).replace('T', ' '), style = Theme.type.mono, color = palette.muted)
                        Text(line["message"].orEmpty(), style = Theme.type.mono, color = if (line["stream"] == "stderr") palette.warning else palette.text)
                    }
                }
            }
        }
    }
}

// the shell of the login types the command of the engine for it: exec with a shell and a user, or attach to the process of the container,
// and a window opened by Terminal of the row starts the shell of the container at once
@Composable
private fun ContainerTerminal(tab: RouterTab, window: Long, container: String, started: String?, sudo: String, instant: Boolean, onStart: (String) -> Unit) {
    val palette = Theme.palette
    val ssh = tab.session.ssh
    val terminal = LocalTerminal.current
    if (ssh == null || terminal == null) {
        Text(stringResource(Res.string.docker_terminal_none), Modifier.padding(12.dp), color = palette.muted)
        return
    }
    if (started != null) {
        TerminalView(ssh, tab.consoles, window, startup = started, sudo = sudo, terminal = terminal)
        return
    }
    val shell = remember { TextFieldState() }
    val user = remember { TextFieldState() }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun open() {
        problem = try {
            val arguments = mapOf("id" to container, "shell" to shell.text.toString().trim(), "user" to user.text.toString().trim())
            tab.session.run(CONTAINER, "terminal", arguments).toList().singleOrNull()?.get("command")?.let(onStart)
            null
        } catch (e: DeviceException) {
            e.message
        }
    }
    LaunchedEffect(container) { if (instant) open() }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.docker_terminal_shell), color = palette.muted)
            TextField(shell, Modifier.width(160.dp).testTag("docker-shell"), placeholder = stringResource(Res.string.docker_terminal_any), mono = true)
            Text(stringResource(Res.string.docker_terminal_user), color = palette.muted)
            TextField(user, Modifier.width(140.dp).testTag("docker-user"), mono = true)
            Button(stringResource(Res.string.docker_open), { scope.launch { open() } }, Modifier.testTag("docker-terminal-open"), primary = true)
        }
        Text(stringResource(Res.string.docker_terminal_hint), color = palette.muted, style = Theme.type.small)
        problem?.let { Text(deviceMessage(it), color = palette.danger, style = Theme.type.small) }
    }
}

// the load as docker stats shows it, over the last two minutes or so: the network and the disk as the bytes of a second
@Composable
private fun ContainerStats(session: DeviceSession, container: String) {
    val palette = Theme.palette
    val cpuName = stringResource(Res.string.docker_stats_cpu)
    val memoryName = stringResource(Res.string.docker_stats_memory)
    val receivedName = stringResource(Res.string.docker_stats_received)
    val sentName = stringResource(Res.string.docker_stats_sent)
    val readName = stringResource(Res.string.docker_stats_read)
    val writtenName = stringResource(Res.string.docker_stats_written)
    val cpu = remember(container) { Series(cpuName, palette.accent, Ring(SAMPLES)) }
    val memory = remember(container) { Series(memoryName, palette.success, Ring(SAMPLES)) }
    val received = remember(container) { Series(receivedName, palette.accent, Ring(SAMPLES)) }
    val sent = remember(container) { Series(sentName, palette.success, Ring(SAMPLES)) }
    val read = remember(container) { Series(readName, palette.accent, Ring(SAMPLES)) }
    val written = remember(container) { Series(writtenName, palette.warning, Ring(SAMPLES)) }
    var limit by remember(container) { mutableStateOf<Double?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(container, attempt) {
        problem = null
        var before: Item? = null
        try {
            session.run(CONTAINER, "stats", mapOf("id" to container)).collect { sample ->
                sample["cpu"]?.toDoubleOrNull()?.let(cpu.ring::add)
                sample["memory"]?.toDoubleOrNull()?.let(memory.ring::add)
                limit = sample["memory-limit"]?.toDoubleOrNull()
                val previous = before
                val seconds = previous?.let { elapsed(it["time"], sample["time"]) }
                if (previous != null && seconds != null && seconds > 0) {
                    fun rate(key: String): Double? {
                        val now = sample[key]?.toDoubleOrNull() ?: return null
                        val then = previous[key]?.toDoubleOrNull() ?: return null
                        return (now - then).coerceAtLeast(0.0) / seconds
                    }
                    rate("rx-byte")?.let(received.ring::add)
                    rate("tx-byte")?.let(sent.ring::add)
                    rate("read-byte")?.let(read.ring::add)
                    rate("write-byte")?.let(written.ring::add)
                }
                before = sample
            }
        } catch (e: DeviceException) {
            problem = e.message
        }
    }
    val perSecond = { value: Double -> byteSize(value) + "/s" }
    // the axis of an idle container steps by tenths of a percent
    val percent = { value: Double -> (value * 10).roundToLong().let { if (it % 10 == 0L) "${it / 10} %" else "${it / 10.0} %" } }
    Column(Modifier.fillMaxSize()) {
        problem?.let { Problem(it) { attempt++ } }
        ScrollArea(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (cpu.ring.size == 0) Text(stringResource(Res.string.docker_stats_waiting), color = palette.muted, style = Theme.type.small)
            Text(cpuName, style = Theme.type.strong)
            Legend(listOf(cpu)) { "${(it * 100).roundToLong() / 100.0} %" }
            Chart(listOf(cpu), Modifier.fillMaxWidth().height(110.dp), format = percent)
            // the engine gives a container without a limit the memory of the host as one: the chart follows the use, the legend tells the limit
            Text(memoryName, style = Theme.type.strong)
            Legend(listOf(memory)) { used -> byteSize(used) + limit?.let { " / ${byteSize(it)}" }.orEmpty() }
            Chart(listOf(memory), Modifier.fillMaxWidth().height(110.dp), format = ::byteSize)
            Text(stringResource(Res.string.docker_stats_network), style = Theme.type.strong)
            Legend(listOf(received, sent), perSecond)
            Chart(listOf(received, sent), Modifier.fillMaxWidth().height(110.dp), format = perSecond)
            Text(stringResource(Res.string.docker_stats_disk), style = Theme.type.strong)
            Legend(listOf(read, written), perSecond)
            Chart(listOf(read, written), Modifier.fillMaxWidth().height(110.dp), format = perSecond)
        }
    }
}

private fun elapsed(from: String?, to: String?): Double? {
    val start = Instant.parseOrNull(from ?: return null) ?: return null
    val end = Instant.parseOrNull(to ?: return null) ?: return null
    return (end - start).inWholeMilliseconds / 1000.0
}

// the processes of docker top under the titles ps gave them, read again every few seconds, or what the container changed over its
// image, marked as docker diff marks it
@Composable
private fun ContainerRows(session: DeviceSession, container: String, command: String, every: Duration?) {
    val palette = Theme.palette
    var rows by remember(container, command) { mutableStateOf<List<Item>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(container, command, attempt) {
        while (true) {
            problem = try {
                rows = session.run(CONTAINER, command, mapOf("id" to container)).toList()
                null
            } catch (e: DeviceException) {
                e.message
            }
            delay(every ?: return@LaunchedEffect)
        }
    }
    val shown = rows
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Button(stringResource(Res.string.docker_refresh), { attempt++ })
        }
        problem?.let { Problem(it) { attempt++ } }
        when {
            shown == null -> Text(stringResource(Res.string.window_loading), Modifier.padding(12.dp), color = palette.muted, style = Theme.type.small)
            command == "changes" -> SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    items(shown.size) { index ->
                        val change = shown[index]
                        val (mark, color) = when (change["kind"]) {
                            "added" -> "A" to palette.success
                            "deleted" -> "D" to palette.danger
                            else -> "C" to palette.warning
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(mark, style = Theme.type.mono, color = color)
                            Text(change["path"].orEmpty(), style = Theme.type.mono)
                        }
                    }
                }
            }
            else -> {
                val columns = shown.firstOrNull()?.values?.keys?.toList().orEmpty()
                SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        item { Cells(columns.map { it.uppercase() }, palette.muted) }
                        items(shown.size) { index -> Cells(columns.map { shown[index][it].orEmpty() }, palette.text) }
                    }
                }
            }
        }
    }
}

// the last cell takes what is left of the row: the command of a process
@Composable
private fun Cells(cells: List<String>, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.forEachIndexed { index, cell ->
            val last = index == cells.lastIndex
            Text(cell, if (last) Modifier.weight(1f) else Modifier.width(84.dp), style = Theme.type.mono, color = color, maxLines = if (last) Int.MAX_VALUE else 1)
        }
    }
}

// an element as the engine reports it, a container with its secret variables masked
@Composable
internal fun DockerInspect(session: DeviceSession, path: MenuPath, id: String) {
    val palette = Theme.palette
    var text by remember(id) { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val copier = rememberCopier()
    LaunchedEffect(id, attempt) {
        problem = try {
            text = session.run(path, "inspect", mapOf("id" to id)).toList().singleOrNull()?.get("json").orEmpty()
            null
        } catch (e: DeviceException) {
            e.message
        }
    }
    val shown = text
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            Button(stringResource(Res.string.docker_copy), { shown?.let(copier::copy) }, enabled = !shown.isNullOrEmpty())
            Button(stringResource(Res.string.docker_refresh), { attempt++ })
        }
        problem?.let { Problem(it) { attempt++ } }
        ScrollArea(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
            if (shown == null) Text(stringResource(Res.string.window_loading), color = palette.muted, style = Theme.type.small)
            else SelectionContainer { Text(shown, style = Theme.type.mono) }
        }
    }
}

// the folders of the container are listed by ls run inside it; a file comes as it is, a folder as a tar archive, and files of this
// computer go into the folder shown
@Composable
internal fun ContainerFiles(session: DeviceSession, container: String, start: String = "/") {
    val palette = Theme.palette
    val typed = remember { TextFieldState(start) }
    var folder by remember(container) { mutableStateOf(start) }
    var entries by remember(container) { mutableStateOf<List<Item>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val exchange = LocalFileExchange.current
    LaunchedEffect(container, folder, attempt) {
        typed.setTextAndPlaceCursorAtEnd(folder)
        entries = null
        problem = try {
            entries = session.run(CONTAINER, "ls", mapOf("id" to container, "path" to folder)).toList()
            null
        } catch (e: DeviceException) {
            entries = emptyList()
            e.message
        }
    }
    fun inside(name: String) = folder.trimEnd('/') + "/" + name
    fun download(name: String) = scope.launch {
        problem = try {
            val file = session.run(CONTAINER, "download", mapOf("id" to container, "path" to inside(name))).toList().single()
            exchange.save(file["name"].orEmpty(), Base64.decode(file["content"].orEmpty()))
            null
        } catch (e: DeviceException) {
            e.message
        }
    }
    fun upload() = scope.launch {
        problem = try {
            for (file in exchange.pick()) {
                session.run(CONTAINER, "upload", mapOf("id" to container, "path" to folder, "name" to file.name, "content" to Base64.encode(file.read()))).collect()
            }
            attempt++
            null
        } catch (e: DeviceException) {
            e.message
        }
    }
    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Button(stringResource(Res.string.docker_files_up), { folder = folder.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" } }, enabled = folder != "/" && folder != start)
            TextField(typed, Modifier.width(260.dp).testTag("docker-files-path"), mono = true)
            Button(stringResource(Res.string.docker_open), { folder = typed.text.toString().trim().ifEmpty { "/" } })
            Button(stringResource(Res.string.docker_refresh), { attempt++ })
            Button(stringResource(Res.string.docker_files_upload), { upload() })
        }
        problem?.let { Problem(it) { attempt++ } }
        val shown = entries
        when {
            shown == null -> Text(stringResource(Res.string.window_loading), Modifier.padding(12.dp), color = palette.muted, style = Theme.type.small)
            shown.isEmpty() -> Text(stringResource(Res.string.docker_files_empty), Modifier.padding(12.dp), color = palette.muted, style = Theme.type.small)
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                items(shown.size) { index ->
                    val entry = shown[index]
                    val name = entry["name"].orEmpty()
                    val folderLike = entry["type"] == "directory" || entry["type"] == "link"
                    val target = entry["target"].orEmpty()
                    Row(
                        Modifier.fillMaxWidth().clickable { if (folderLike) folder = inside(name) else download(name) }.hand().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            if (target.isNotEmpty()) "$name → $target" else if (folderLike) "$name/" else name, Modifier.weight(1f), style = Theme.type.mono,
                            color = if (folderLike) palette.accent else palette.text,
                        )
                        Text(entry["size"]?.toDoubleOrNull()?.let(::byteSize).orEmpty(), Modifier.width(90.dp), style = Theme.type.small, color = palette.muted)
                        Text(entry["modified"].orEmpty(), Modifier.width(120.dp), style = Theme.type.small, color = palette.muted)
                    }
                }
            }
        }
    }
}

@Composable
internal fun Problem(text: String, onRetry: () -> Unit) {
    val palette = Theme.palette
    Row(Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(deviceMessage(text), Modifier.weight(1f), style = Theme.type.small, color = palette.danger)
        Button(stringResource(Res.string.list_retry), onRetry)
    }
}
