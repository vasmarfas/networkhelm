package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.chart.byteSize
import com.vasmarfas.networkhelm.ui.editor.EditorControls
import com.vasmarfas.networkhelm.ui.editor.EditorState
import com.vasmarfas.networkhelm.ui.editor.Language
import com.vasmarfas.networkhelm.ui.editor.LineEnding
import com.vasmarfas.networkhelm.ui.editor.TextDocument
import com.vasmarfas.networkhelm.ui.editor.TextEditor
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.Labeled
import com.vasmarfas.networkhelm.ui.kit.ScrollArea
import com.vasmarfas.networkhelm.ui.kit.SecretField
import com.vasmarfas.networkhelm.ui.kit.Segments
import com.vasmarfas.networkhelm.ui.kit.Select
import com.vasmarfas.networkhelm.ui.kit.TabStrip
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.kit.hand
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.docker_daemon_done
import networkhelm.app.shared.generated.resources.docker_daemon_hint
import networkhelm.app.shared.generated.resources.docker_daemon_question
import networkhelm.app.shared.generated.resources.docker_daemon_save
import networkhelm.app.shared.generated.resources.docker_disk_active
import networkhelm.app.shared.generated.resources.docker_disk_count
import networkhelm.app.shared.generated.resources.docker_disk_reclaimable
import networkhelm.app.shared.generated.resources.docker_disk_size
import networkhelm.app.shared.generated.resources.docker_events_day
import networkhelm.app.shared.generated.resources.docker_events_filter
import networkhelm.app.shared.generated.resources.docker_events_hour
import networkhelm.app.shared.generated.resources.docker_events_none_day
import networkhelm.app.shared.generated.resources.docker_events_none_filter
import networkhelm.app.shared.generated.resources.docker_events_none_hour
import networkhelm.app.shared.generated.resources.docker_info_api_version
import networkhelm.app.shared.generated.resources.docker_info_architecture
import networkhelm.app.shared.generated.resources.docker_info_cgroup
import networkhelm.app.shared.generated.resources.docker_info_containers
import networkhelm.app.shared.generated.resources.docker_info_cpus
import networkhelm.app.shared.generated.resources.docker_info_host
import networkhelm.app.shared.generated.resources.docker_info_images
import networkhelm.app.shared.generated.resources.docker_info_kernel
import networkhelm.app.shared.generated.resources.docker_info_memory
import networkhelm.app.shared.generated.resources.docker_info_os
import networkhelm.app.shared.generated.resources.docker_info_paused
import networkhelm.app.shared.generated.resources.docker_info_root_dir
import networkhelm.app.shared.generated.resources.docker_info_rootless
import networkhelm.app.shared.generated.resources.docker_info_running
import networkhelm.app.shared.generated.resources.docker_info_stopped
import networkhelm.app.shared.generated.resources.docker_info_storage_driver
import networkhelm.app.shared.generated.resources.docker_info_swarm
import networkhelm.app.shared.generated.resources.docker_info_version
import networkhelm.app.shared.generated.resources.docker_kind_build_cache
import networkhelm.app.shared.generated.resources.docker_kind_containers
import networkhelm.app.shared.generated.resources.docker_kind_images
import networkhelm.app.shared.generated.resources.docker_kind_networks
import networkhelm.app.shared.generated.resources.docker_kind_volumes
import networkhelm.app.shared.generated.resources.docker_load_top
import networkhelm.app.shared.generated.resources.docker_refresh
import networkhelm.app.shared.generated.resources.docker_registries_add
import networkhelm.app.shared.generated.resources.docker_registries_host_hint
import networkhelm.app.shared.generated.resources.docker_registries_host_login
import networkhelm.app.shared.generated.resources.docker_registries_keep
import networkhelm.app.shared.generated.resources.docker_registries_login
import networkhelm.app.shared.generated.resources.docker_registries_none
import networkhelm.app.shared.generated.resources.docker_registries_remove
import networkhelm.app.shared.generated.resources.docker_registries_server
import networkhelm.app.shared.generated.resources.docker_registry_password
import networkhelm.app.shared.generated.resources.docker_save
import networkhelm.app.shared.generated.resources.docker_tab_daemon
import networkhelm.app.shared.generated.resources.docker_tab_disk
import networkhelm.app.shared.generated.resources.docker_tab_events
import networkhelm.app.shared.generated.resources.docker_tab_overview
import networkhelm.app.shared.generated.resources.docker_tab_prune
import networkhelm.app.shared.generated.resources.filter_all
import networkhelm.app.shared.generated.resources.window_loading
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val SYSTEM = MenuPath.parse("container/system")
private val ENGINE_CONTAINERS = MenuPath.parse("container")
private val REGISTRY_IMAGES = MenuPath.parse("container/image")

// Monitor of the window of containers: what the engine is, holds and does; its System: daemon.json and the cleanup
internal val MONITOR_TABS = listOf("overview" to Res.string.docker_tab_overview, "events" to Res.string.docker_tab_events, "disk" to Res.string.docker_tab_disk)
internal val SETTINGS_TABS = listOf("daemon" to Res.string.docker_tab_daemon, "prune" to Res.string.docker_tab_prune)
private val SYSTEM_TABS = MONITOR_TABS + SETTINGS_TABS

private val INFO_LABELS: Map<String, StringResource> = mapOf(
    "version" to Res.string.docker_info_version,
    "api-version" to Res.string.docker_info_api_version,
    "os" to Res.string.docker_info_os,
    "kernel" to Res.string.docker_info_kernel,
    "architecture" to Res.string.docker_info_architecture,
    "storage-driver" to Res.string.docker_info_storage_driver,
    "cgroup" to Res.string.docker_info_cgroup,
    "cpus" to Res.string.docker_info_cpus,
    "memory" to Res.string.docker_info_memory,
    "containers" to Res.string.docker_info_containers,
    "running" to Res.string.docker_info_running,
    "paused" to Res.string.docker_info_paused,
    "stopped" to Res.string.docker_info_stopped,
    "images" to Res.string.docker_info_images,
    "root-dir" to Res.string.docker_info_root_dir,
    "swarm" to Res.string.docker_info_swarm,
    "rootless" to Res.string.docker_info_rootless,
    "host" to Res.string.docker_info_host,
)

private val KINDS: Map<String, StringResource> = mapOf(
    "container" to Res.string.docker_kind_containers,
    "images" to Res.string.docker_kind_images,
    "image" to Res.string.docker_kind_images,
    "containers" to Res.string.docker_kind_containers,
    "volumes" to Res.string.docker_kind_volumes,
    "volume" to Res.string.docker_kind_volumes,
    "network" to Res.string.docker_kind_networks,
    "build-cache" to Res.string.docker_kind_build_cache,
)

// the past the tab of events starts with, and what the tab says when the engine kept nothing of it
private class EventPeriod(val seconds: Long, val title: StringResource, val quiet: StringResource)

private val EVENT_PERIODS = listOf(
    EventPeriod(3_600, Res.string.docker_events_hour, Res.string.docker_events_none_hour),
    EventPeriod(86_400, Res.string.docker_events_day, Res.string.docker_events_none_day),
)

// the engine as a whole: what it is and holds with the load of its containers, what happens in it, the room it takes, daemon.json of
// Docker and prune of everything, each kind listed before it goes
@Composable
internal fun SystemView(session: DeviceSession, opened: String, tabs: List<Pair<String, StringResource>> = SYSTEM_TABS) {
    var shown by remember { mutableIntStateOf(tabs.indexOfFirst { it.first == opened }.coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize().testTag("docker-system")) {
        TabStrip(tabs.map { stringResource(it.second) }, shown, { shown = it })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tabs[shown].first) {
                "overview" -> SystemOverview(session)
                "events" -> SystemEvents(session)
                "disk" -> SystemDisk(session)
                "daemon" -> SystemDaemon(session)
                else -> SystemPrune(session)
            }
        }
    }
}

// docker info and docker version, and the containers that load the host most, as the table of containers reads them
@Composable
private fun SystemOverview(session: DeviceSession) {
    val palette = Theme.palette
    var engine by remember { mutableStateOf<List<Item>>(emptyList()) }
    var busiest by remember { mutableStateOf<List<Item>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        problem = try {
            session.watch(SYSTEM).filterIsInstance<ListEvent.Snapshot>().collect { engine = it.items }
            null
        } catch (e: DeviceException) {
            e.message
        }
    }
    LaunchedEffect(attempt) {
        try {
            session.watch(ENGINE_CONTAINERS).filterIsInstance<ListEvent.Snapshot>().collect { snapshot ->
                busiest = snapshot.items.filter { it["state"] == "running" }.sortedByDescending { it["cpu"]?.toDoubleOrNull() ?: 0.0 }.take(BUSIEST)
            }
        } catch (e: DeviceException) {
            problem = e.message
        }
    }
    Column(Modifier.fillMaxSize()) {
        problem?.let { Problem(it) { attempt++ } }
        ScrollArea(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (engine.isEmpty()) Text(stringResource(Res.string.window_loading), color = palette.muted, style = Theme.type.small)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in engine) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(INFO_LABELS[row.id]?.let { stringResource(it) } ?: row.id, Modifier.width(160.dp), color = palette.muted)
                            Text(shownValue(row), style = Theme.type.mono)
                        }
                    }
                }
            }
            if (busiest.isNotEmpty()) {
                Text(stringResource(Res.string.docker_load_top), Modifier.padding(top = 12.dp), style = Theme.type.strong)
                for (container in busiest) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(container["name"].orEmpty(), Modifier.width(200.dp), style = Theme.type.mono)
                        Text(container["cpu"]?.let { "$it %" }.orEmpty(), Modifier.width(90.dp), style = Theme.type.mono)
                        Text(container["memory"]?.toDoubleOrNull()?.let(::byteSize).orEmpty(), style = Theme.type.mono)
                    }
                }
            }
        }
    }
}

private fun shownValue(row: Item): String {
    val value = row["value"].orEmpty()
    return if (row.id == "memory") value.toDoubleOrNull()?.let(::byteSize) ?: value else cell(null, row.id, value)
}

// docker events of the last hour or day, then those that come, the newest at the bottom; the filter keeps a kind and the events whose
// object has the text in its name, image or ID
@Composable
private fun SystemEvents(session: DeviceSession) {
    val palette = Theme.palette
    val events = remember { mutableStateListOf<Item>() }
    var period by remember { mutableStateOf(EVENT_PERIODS.first()) }
    // the engine has sent what it kept of the period
    var live by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf<String?>(null) }
    val filter = remember { TextFieldState() }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    LaunchedEffect(period, attempt) {
        events.clear()
        live = false
        problem = null
        try {
            session.run(SYSTEM, "events", mapOf("period" to "${period.seconds}")).collect { event ->
                if (event.values.isEmpty()) live = true else events += event
                if (events.size > MAX_EVENTS + 500) events.subList(0, events.size - MAX_EVENTS).clear()
            }
        } catch (e: DeviceException) {
            problem = e.message
        }
    }
    val text = filter.text.toString()
    val kinds = listOf(null) + events.mapNotNull { it["type"]?.ifEmpty { null } }.distinct().sorted()
    val shown = events.filter { event ->
        (kind == null || event["type"] == kind) && (text.isEmpty() || listOf("name", "image", "id").any { event[it].orEmpty().contains(text, ignoreCase = true) })
    }
    val atEnd = !list.canScrollForward
    LaunchedEffect(shown.size) { if (atEnd && shown.isNotEmpty()) list.scrollToItem(shown.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Segments(period, EVENT_PERIODS, { stringResource(it.title) }, { period = it })
            Select(kind, kinds, { value -> if (value == null) stringResource(Res.string.filter_all) else KINDS[value]?.let { stringResource(it) } ?: value }, { kind = it }, Modifier.width(140.dp))
            TextField(filter, Modifier.width(220.dp).testTag("docker-events-filter"), placeholder = stringResource(Res.string.docker_events_filter))
        }
        problem?.let { Problem(it) { attempt++ } }
        val empty = when {
            problem != null || shown.isNotEmpty() -> null
            !live && events.isEmpty() -> Res.string.window_loading
            events.isEmpty() -> period.quiet
            else -> Res.string.docker_events_none_filter
        }
        empty?.let { Text(stringResource(it), Modifier.padding(12.dp), color = palette.muted, style = Theme.type.small) }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = list) {
                items(shown.size) { index ->
                    val event = shown[index]
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(event["time"].orEmpty(), style = Theme.type.mono, color = palette.muted)
                        Text(event["type"].orEmpty(), Modifier.width(80.dp), style = Theme.type.mono)
                        Text(event["action"].orEmpty(), Modifier.width(110.dp), style = Theme.type.mono, color = palette.accent)
                        Text(event["name"].orEmpty().ifEmpty { event["id"].orEmpty().take(12) }, Modifier.width(180.dp), style = Theme.type.mono)
                        Text(event["attributes"].orEmpty(), style = Theme.type.small, color = palette.muted)
                    }
                }
            }
        }
    }
}

// docker system df: how much each kind takes and how much of it nothing uses
@Composable
private fun SystemDisk(session: DeviceSession) {
    val palette = Theme.palette
    var rows by remember { mutableStateOf<List<Item>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        problem = try {
            rows = session.run(SYSTEM, "disk", emptyMap()).toList()
            null
        } catch (e: DeviceException) {
            e.message
        }
    }
    val shown = rows
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Button(stringResource(Res.string.docker_refresh), { attempt++ })
        }
        problem?.let { Problem(it) { attempt++ } }
        if (shown == null) {
            Text(stringResource(Res.string.window_loading), Modifier.padding(12.dp), color = palette.muted, style = Theme.type.small)
        } else {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("", Modifier.width(140.dp))
                    for (title in listOf(Res.string.docker_disk_count, Res.string.docker_disk_active, Res.string.docker_disk_size, Res.string.docker_disk_reclaimable)) {
                        Text(stringResource(title), Modifier.width(110.dp), color = palette.muted, style = Theme.type.small)
                    }
                }
                for (row in shown) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(KINDS[row.id]?.let { stringResource(it) } ?: row.id, Modifier.width(140.dp))
                        Text(row["count"].orEmpty(), Modifier.width(110.dp), style = Theme.type.mono)
                        Text(row["active"].orEmpty(), Modifier.width(110.dp), style = Theme.type.mono)
                        Text(row["size"]?.toDoubleOrNull()?.let(::byteSize).orEmpty(), Modifier.width(110.dp), style = Theme.type.mono)
                        Text(row["reclaimable"]?.toDoubleOrNull()?.let(::byteSize).orEmpty(), Modifier.width(110.dp), style = Theme.type.mono)
                    }
                }
            }
        }
    }
}

// daemon.json of Docker in the editor: dockerd checks it, and Docker starts again with it under the guard
@Composable
private fun SystemDaemon(session: DeviceSession) {
    val palette = Theme.palette
    var editor by remember { mutableStateOf<EditorState?>(null) }
    val controls = remember { EditorControls() }
    var asking by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(attempt) {
        problem = try {
            val text = session.run(SYSTEM, "daemon", emptyMap()).toList().singleOrNull()?.get("text").orEmpty()
            editor = EditorState(TextDocument(text, LineEnding.Lf, cp1251 = false, bom = false, mixed = false), Language.Json)
            null
        } catch (e: DeviceException) {
            e.message
        }
    }
    fun save(text: String) = scope.launch {
        running = true
        done = false
        problem = try {
            session.run(SYSTEM, "save-daemon", mapOf("text" to text)).toList()
            done = true
            null
        } catch (e: DeviceException) {
            e.message
        } finally {
            running = false
        }
    }
    val shown = editor
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FlowRow(
                Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Button(stringResource(Res.string.docker_daemon_save), { asking = true }, Modifier.testTag("docker-daemon-save"), enabled = !running && shown != null, danger = true)
                Button(stringResource(Res.string.docker_refresh), { attempt++ }, enabled = !running)
                Text(stringResource(Res.string.docker_daemon_hint), color = palette.muted, style = Theme.type.small)
            }
            problem?.let { Text(deviceMessage(it), Modifier.padding(horizontal = 12.dp), color = palette.danger, style = Theme.type.small) }
            if (done) Text(stringResource(Res.string.docker_daemon_done), Modifier.padding(horizontal = 12.dp), color = palette.success, style = Theme.type.small)
            if (shown != null) TextEditor(shown, controls, readOnly = running, modifier = Modifier.weight(1f).fillMaxWidth())
        }
        if (asking && shown != null) {
            Confirm(stringResource(Res.string.docker_daemon_question), stringResource(Res.string.docker_daemon_save), onCancel = { asking = false }) {
                asking = false
                save(shown.snapshot())
            }
        }
    }
}

// prune of every kind of the engine, each listed before it goes
@Composable
private fun SystemPrune(session: DeviceSession) {
    var kind by remember { mutableStateOf("container") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Segments(kind, listOf("container", "image", "volume", "network"), { stringResource(KINDS.getValue(it)) }, { kind = it })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { key(kind) { DockerPrune(session, kind) } }
    }
}

// the logins of registries kept in the protected store of the app; a login can also go to the host, at the button alone
@Composable
internal fun RegistriesView(session: DeviceSession) {
    val palette = Theme.palette
    val registries = LocalDockerRegistries.current
    var kept by remember { mutableStateOf<List<DockerRegistry>?>(null) }
    var chosen by remember { mutableStateOf<Int?>(null) }
    val server = remember { TextFieldState() }
    val login = remember { TextFieldState() }
    val password = remember { TextFieldState() }
    val said = remember { mutableStateListOf<String>() }
    var problem by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // the protected store of the platform says what failed in its own words
    LaunchedEffect(registries) {
        problem = try {
            kept = registries?.all().orEmpty()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }
    fun pick(index: Int?) {
        chosen = index
        val entry = index?.let { kept?.getOrNull(it) }
        server.setTextAndPlaceCursorAtEnd(entry?.server.orEmpty())
        login.setTextAndPlaceCursorAtEnd(entry?.username.orEmpty())
        password.setTextAndPlaceCursorAtEnd("")
    }
    fun act(step: suspend () -> Unit) = scope.launch {
        running = true
        problem = try {
            step()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.toString()
        } finally {
            running = false
        }
    }
    // an empty password keeps the one the login had
    fun entry(): DockerRegistry = DockerRegistry(
        server.text.toString().trim(), login.text.toString().trim(),
        password.text.toString().ifEmpty { chosen?.let { kept?.getOrNull(it) }?.password.orEmpty() },
    )
    val list = kept.orEmpty()
    Column(Modifier.fillMaxSize().testTag("docker-registries")) {
        Column(Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Labeled(stringResource(Res.string.docker_registries_server)) { TextField(server, Modifier.width(260.dp), placeholder = "docker.io", mono = true) }
            Labeled(stringResource(Res.string.docker_registries_login)) { TextField(login, Modifier.width(260.dp)) }
            Labeled(stringResource(Res.string.docker_registry_password)) { SecretField(password, Modifier.width(260.dp)) }
            Labeled("") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(stringResource(if (chosen == null) Res.string.docker_registries_add else Res.string.docker_save), {
                        act {
                            val changed = entry()
                            val next = list.toMutableList()
                            val at = chosen
                            if (at == null) next += changed else next[at] = changed
                            registries?.save(next)
                            kept = next
                            pick(null)
                        }
                    }, enabled = !running && registries != null && login.text.isNotBlank(), primary = true)
                    if (chosen != null) {
                        Button(stringResource(Res.string.docker_registries_remove), {
                            act {
                                val next = list.filterIndexed { index, _ -> index != chosen }
                                registries?.save(next)
                                kept = next
                                pick(null)
                            }
                        }, enabled = !running, danger = true)
                        Button(stringResource(Res.string.docker_registries_host_login), {
                            act {
                                val entry = entry()
                                said.clear()
                                said += session.run(REGISTRY_IMAGES, "login", mapOf("server" to entry.server, "username" to entry.username, "password" to entry.password))
                                    .toList().map { it["line"].orEmpty() }
                            }
                        }, enabled = !running)
                    }
                }
            }
        }
        Text(stringResource(if (chosen == null) Res.string.docker_registries_none else Res.string.docker_registries_keep), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = palette.muted, style = Theme.type.small)
        if (chosen != null) Text(stringResource(Res.string.docker_registries_host_hint), Modifier.padding(horizontal = 8.dp), color = palette.muted, style = Theme.type.small)
        problem?.let { Text(deviceMessage(it), Modifier.padding(horizontal = 8.dp), color = palette.danger, style = Theme.type.small) }
        if (said.isNotEmpty()) SelectionContainer { Column(Modifier.padding(horizontal = 8.dp)) { for (line in said) Text(line, style = Theme.type.mono) } }
        if (kept == null) Text(stringResource(Res.string.window_loading), Modifier.padding(8.dp), color = palette.muted, style = Theme.type.small)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
            if (list.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.docker_registries_server), Modifier.width(260.dp), color = palette.muted, style = Theme.type.small)
                    Text(stringResource(Res.string.docker_registries_login), color = palette.muted, style = Theme.type.small)
                }
            }
            items(list.size) { index ->
                val entry = list[index]
                Row(
                    Modifier.fillMaxWidth().background(if (index == chosen) palette.raised else palette.surface).clickable { pick(index.takeIf { it != chosen }) }.hand().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(entry.server.ifEmpty { "docker.io" }, Modifier.width(260.dp), style = Theme.type.mono)
                    Text(entry.username, style = Theme.type.mono)
                }
            }
        }
    }
}

private const val BUSIEST = 8
private const val MAX_EVENTS = 5_000
