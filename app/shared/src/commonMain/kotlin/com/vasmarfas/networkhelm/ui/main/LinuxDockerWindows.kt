package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.chart.byteSize
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.Checkbox
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.Select
import com.vasmarfas.networkhelm.ui.kit.TabStrip
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.theme.on
import com.vasmarfas.networkhelm.ui.workspace.WindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.docker_group_close
import networkhelm.app.shared.generated.resources.docker_group_root
import networkhelm.app.shared.generated.resources.docker_network_aliases
import networkhelm.app.shared.generated.resources.docker_network_connect
import networkhelm.app.shared.generated.resources.docker_network_container
import networkhelm.app.shared.generated.resources.docker_network_disconnect
import networkhelm.app.shared.generated.resources.docker_network_empty
import networkhelm.app.shared.generated.resources.docker_network_ipv4
import networkhelm.app.shared.generated.resources.docker_network_ipv6
import networkhelm.app.shared.generated.resources.docker_prune_go
import networkhelm.app.shared.generated.resources.docker_prune_images_all
import networkhelm.app.shared.generated.resources.docker_prune_none
import networkhelm.app.shared.generated.resources.docker_prune_question
import networkhelm.app.shared.generated.resources.docker_prune_remove
import networkhelm.app.shared.generated.resources.docker_prune_size
import networkhelm.app.shared.generated.resources.docker_prune_volumes_all
import networkhelm.app.shared.generated.resources.docker_refresh
import networkhelm.app.shared.generated.resources.docker_tab_containers
import networkhelm.app.shared.generated.resources.docker_tab_inspect
import networkhelm.app.shared.generated.resources.docker_volume_browse
import networkhelm.app.shared.generated.resources.docker_volume_browse_question
import networkhelm.app.shared.generated.resources.window_loading
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

private val CONTAINERS = MenuPath.parse("container")
private val VOLUME = MenuPath.parse("container/volume")
private val NETWORK = MenuPath.parse("container/network")
private val SYSTEM = MenuPath.parse("container/system")
private const val ENGINES = "container-engine"
private const val CONTAINERS_WINDOW = "containers"

// how often the window of containers on a host without an engine asks whether one came
private val ENGINE_WAIT = 5.seconds

// the removal of a container that browsed a volume outlives the window that made it
private val CLEANUP = CoroutineScope(SupervisorJob())

private val NETWORK_TABS = listOf("containers" to Res.string.docker_tab_containers, "inspect" to Res.string.docker_tab_inspect)

// a login in the group docker is root on the host through the engine; each host says so once, at the first connection that finds it
@Composable
internal fun DockerGroupNotice(tab: RouterTab) {
    val templates = LocalDockerTemplates.current ?: return
    var shown by remember(tab) { mutableStateOf(false) }
    LaunchedEffect(tab) {
        if (tab.session.device.platform != Platform.Ssh) return@LaunchedEffect
        // a host without an engine has no table to ask, and a check that failed tells nothing until the next connection
        val member = try {
            tab.session.run(SYSTEM, "group", emptyMap()).toList().firstOrNull()?.get("docker") == "true"
        } catch (e: DeviceException) {
            false
        }
        shown = member && templates.firstNotice(tab.address)
    }
    if (!shown) return
    val palette = Theme.palette
    Row(
        Modifier.fillMaxWidth().background(palette.warning).padding(horizontal = 12.dp, vertical = 4.dp).testTag("docker-group"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(Res.string.docker_group_root), Modifier.weight(1f), color = palette.on(palette.warning))
        Button(stringResource(Res.string.docker_group_close), { shown = false })
    }
}

// a window of the images, volumes or networks of the engine, opened from the menu of their tables: what it shows by the name the
// menu gives it, and the row it was opened for, empty for a button that needs no row; the window of containers names its tab
internal class DockerCall(val kind: String, target: String) {
    var target by mutableStateOf(target)

    // the user agreed to browse the volume through a container: the window drawn again does not ask again
    var agreed by mutableStateOf(false)
}

// the group of containers of the menu, found by its overview of the engines, which every Linux host has
internal fun containersGroup(menu: List<MenuEntry>?): MenuEntry? = menu?.firstOrNull { group -> group.items.any { it.path == ENGINES } }

// an entry of the group of containers opens its tab in the one window of the group; an entry that fills a field or opens a row goes
// as it did
internal fun RouterTab.openContainers(entry: MenuEntry): Boolean {
    val group = containersGroup(menu) ?: return false
    val shown = group.items.firstOrNull { it.path == entry.path && it.window == entry.window } ?: return false
    if (entry.fill.isNotEmpty() || entry.arguments.isNotEmpty() || entry.item || entry.start) return false
    val open = workspace.windows.firstOrNull { dockers[it.id]?.kind == CONTAINERS_WINDOW }
    if (open != null) {
        dockers.getValue(open.id).target = shown.title
        workspace.bringToFront(open)
        return true
    }
    val shift = (workspace.windows.size % 8) * 24
    dockers[workspace.open(group.title, DpOffset((40 + shift).dp, (30 + shift).dp), DpSize(1100.dp, 600.dp)).id] = DockerCall(CONTAINERS_WINDOW, shown.title)
    return true
}

// the window of containers in the saved layout: one tab named as the tab it shows, on the path of the overview, so that a build
// without this window, or another device, opens it as a window of the overview alone
internal fun RouterTab.containersLayout(window: WindowState): SavedWindow? {
    val call = dockers[window.id]?.takeIf { it.kind == CONTAINERS_WINDOW } ?: return null
    return SavedWindow(
        window.title, listOf(SavedTab(call.target, ENGINES)), 0, window.position.x.value, window.position.y.value, window.size.width.value, window.size.height.value,
        window.maximized, window.minimized,
    )
}

// a saved window of the overview comes back as the window of containers where the menu has the group, on the tab it showed
internal fun RouterTab.restoreContainers(saved: SavedWindow): Boolean {
    if (saved.tabs.singleOrNull()?.path != ENGINES) return false
    val group = containersGroup(menu) ?: return false
    if (workspace.windows.any { dockers[it.id]?.kind == CONTAINERS_WINDOW }) return true
    val shown = group.items.firstOrNull { it.title == saved.tabs[0].title } ?: group.items.first()
    val window = workspace.open(group.title, DpOffset(saved.x.dp, saved.y.dp), DpSize(saved.width.dp, saved.height.dp))
    dockers[window.id] = DockerCall(CONTAINERS_WINDOW, shown.title)
    window.maximized = saved.maximized
    if (saved.minimized) workspace.minimize(window)
    return true
}

// the tabs of the window of containers in its title
@Composable
internal fun ContainersTitleTabs(tab: RouterTab, window: WindowState, colour: Color) {
    val call = tab.dockers[window.id]?.takeIf { it.kind == CONTAINERS_WINDOW } ?: return
    val entries = containersGroup(tab.menu)?.items.orEmpty()
    if (entries.size < 2) return
    TabStrip(entries.map { it.title }, entries.indexOfFirst { it.title == call.target }.coerceAtLeast(0), { call.target = entries[it].title }, Modifier.fillMaxHeight(), titleText = colour)
}

// the group of containers in one window, a tab an entry of the menu: a table of the host as its own window shows it, a window of the
// app as it is. A host without an engine has the overview alone; once an engine answers, the menu is read again and brings its tabs
@Composable
private fun ContainersWindow(tab: RouterTab, call: DockerCall) {
    val entries = containersGroup(tab.menu)?.items.orEmpty()
    val shown = entries.firstOrNull { it.title == call.target } ?: entries.firstOrNull() ?: return
    if (entries.size == 1) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(ENGINE_WAIT)
                // a check that fails tells nothing, and the next one asks again
                val engine = try {
                    tab.session.outline(CONTAINERS)
                    true
                } catch (e: DeviceException) {
                    false
                }
                if (engine) break
            }
            tab.loadMenu()
        }
    }
    Column(Modifier.fillMaxSize().testTag("docker-containers")) {
        // the tabs go in the title, as those of every window, except where a window has no title for them
        if (entries.size > 1 && (Theme.classic || LocalCompact.current)) TabStrip(entries.map { it.title }, entries.indexOf(shown), { call.target = entries[it].title })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(shown.title) {
                when (shown.window) {
                    "docker-registries" -> RegistriesView(tab.session)
                    "docker-system-overview" -> SystemView(tab.session, "overview", MONITOR_TABS)
                    "docker-system-daemon" -> SystemView(tab.session, "daemon", SETTINGS_TABS)
                    else -> {
                        val spec = remember { WindowSpec(shown.title, listOf(tab.windowTab(shown.title, shown.path.orEmpty())), 0) }
                        PathWindow(tab.session, spec, tab.tables, tab::openItem, tab::open, onClose = {})
                    }
                }
            }
        }
    }
}

@Composable
internal fun LinuxDockerView(tab: RouterTab, call: DockerCall) {
    val session = tab.session
    Box(Modifier.fillMaxSize().background(Theme.palette.surface)) {
        when {
            call.kind == CONTAINERS_WINDOW -> ContainersWindow(tab, call)
            call.kind == "pull" -> ImagePull(session, call.target)
            call.kind == "load" -> ImageLoad(session)
            call.kind.startsWith("image-") -> ImageView(session, call.target, call.kind.removePrefix("image-"))
            call.kind.startsWith("prune-") -> DockerPrune(session, call.kind.removePrefix("prune-"))
            call.kind == "volume-files" -> VolumeFiles(session, call)
            call.kind.startsWith("network-") -> NetworkView(session, call.target, call.kind.removePrefix("network-"))
            call.kind == "stack-new" -> StackNew(session, call.target)
            call.kind.startsWith("stack-") -> StackView(session, call.target, call.kind.removePrefix("stack-"))
            call.kind.startsWith("system-") -> SystemView(session, call.kind.removePrefix("system-"))
            call.kind == "registries" -> RegistriesView(session)
        }
    }
}

// what prune would take, listed first with every element ticked; the ticked ones go one by one, so one the engine refuses keeps
// none of the rest from going
@Composable
internal fun DockerPrune(session: DeviceSession, what: String) {
    val palette = Theme.palette
    val path = remember(what) { MenuPath.parse(if (what == "container") "container" else "container/$what") }
    var all by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<Item>?>(null) }
    val chosen = remember { mutableStateListOf<String>() }
    val failures = remember { mutableStateListOf<Pair<String, String>>() }
    var problem by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(all, attempt) {
        rows = null
        problem = try {
            val found = session.run(path, "unused", mapOf("all" to "$all")).toList()
            chosen.clear()
            chosen += found.map { it.id }
            rows = found
            null
        } catch (e: DeviceException) {
            rows = emptyList()
            e.message
        }
    }
    fun prune() = scope.launch {
        running = true
        failures.clear()
        for (id in chosen.toList()) {
            try {
                session.remove(path, listOf(id))
            } catch (e: DeviceException) {
                failures += (rows?.firstOrNull { it.id == id }?.get("name") ?: id) to e.message.orEmpty()
            }
        }
        running = false
        attempt++
    }
    val shown = rows
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().testTag("docker-prune-$what")) {
            FlowRow(
                Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (what == "image" || what == "volume") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Checkbox(all, { all = it }, enabled = !running)
                        Text(stringResource(if (what == "image") Res.string.docker_prune_images_all else Res.string.docker_prune_volumes_all), style = Theme.type.small)
                    }
                }
                Button(stringResource(Res.string.docker_refresh), { attempt++ }, enabled = !running)
                Button(stringResource(Res.string.docker_prune_remove, chosen.size), { asking = true }, Modifier.testTag("docker-prune"), enabled = !running && chosen.isNotEmpty(), danger = true)
                if (what == "image") {
                    val size = shown.orEmpty().filter { it.id in chosen }.sumOf { it["size"]?.toDoubleOrNull() ?: 0.0 }
                    Text(stringResource(Res.string.docker_prune_size, byteSize(size)), color = palette.muted, style = Theme.type.small)
                }
            }
            problem?.let { Problem(it) { attempt++ } }
            for ((name, message) in failures) Text("$name: ${deviceMessage(message)}", Modifier.padding(horizontal = 12.dp), color = palette.danger, style = Theme.type.small)
            when {
                shown == null -> Text(stringResource(Res.string.window_loading), Modifier.padding(12.dp), color = palette.muted, style = Theme.type.small)
                shown.isEmpty() -> Text(stringResource(Res.string.docker_prune_none), Modifier.padding(12.dp), color = palette.muted, style = Theme.type.small)
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                    items(shown.size) { index ->
                        val row = shown[index]
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Checkbox(row.id in chosen, { on -> if (on) chosen += row.id else chosen -= row.id }, enabled = !running)
                            Text(row["name"].orEmpty(), Modifier.weight(1f), style = Theme.type.mono)
                            Text(detail(what, row), Modifier.width(240.dp), style = Theme.type.small, color = palette.muted)
                        }
                    }
                }
            }
        }
        if (asking) {
            Confirm(stringResource(Res.string.docker_prune_question, chosen.size), stringResource(Res.string.docker_prune_go), onCancel = { asking = false }) {
                asking = false
                prune()
            }
        }
    }
}

private fun detail(what: String, row: Item): String = when (what) {
    "image" -> listOf(row["size"]?.toDoubleOrNull()?.let(::byteSize).orEmpty(), row["created"].orEmpty())
    "volume" -> listOf(row["driver"].orEmpty(), row["created"].orEmpty())
    "container" -> listOf(row["image"].orEmpty(), row["status"].orEmpty())
    else -> listOf(row["driver"].orEmpty(), row["subnet"].orEmpty())
}.filter { it.isNotEmpty() }.joinToString("  ")

// the files of a volume, through a container of a small image that mounts it: made once the user agrees, removed when the window
// is done with it
@Composable
private fun VolumeFiles(session: DeviceSession, call: DockerCall) {
    val palette = Theme.palette
    if (!call.agreed) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.docker_volume_browse_question, call.target))
            Button(stringResource(Res.string.docker_volume_browse), { call.agreed = true }, Modifier.testTag("docker-volume-browse"), primary = true)
        }
        return
    }
    var helper by remember(call.target) { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(call.target, attempt) {
        problem = try {
            helper = session.run(VOLUME, "browse", mapOf("id" to call.target)).toList().singleOrNull()?.get("container")
            null
        } catch (e: DeviceException) {
            e.message
        }
    }
    DisposableEffect(call.target) {
        onDispose {
            CLEANUP.launch {
                try {
                    session.run(VOLUME, "unbrowse", mapOf("id" to call.target)).collect()
                } catch (_: DeviceException) {
                    // a container the engine did not take away now ends by itself within the hour and is removed then
                }
            }
        }
    }
    val made = helper
    Column(Modifier.fillMaxSize()) {
        problem?.let { Problem(it) { attempt++ } }
        if (made == null) {
            if (problem == null) Text(stringResource(Res.string.window_loading), Modifier.padding(12.dp), color = palette.muted, style = Theme.type.small)
        } else {
            ContainerFiles(session, made, "/volume")
        }
    }
}

// a network in a window of its own: the containers on it, each to take off it, a container to put on it with its aliases and
// addresses, and the network as the engine reports it
@Composable
private fun NetworkView(session: DeviceSession, network: String, opened: String) {
    var shown by remember(network) { mutableIntStateOf(NETWORK_TABS.indexOfFirst { it.first == opened }.coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize().testTag("docker-network")) {
        TabStrip(NETWORK_TABS.map { stringResource(it.second) }, shown, { shown = it })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (NETWORK_TABS[shown].first == "inspect") DockerInspect(session, NETWORK, network) else NetworkContainers(session, network)
        }
    }
}

@Composable
private fun NetworkContainers(session: DeviceSession, network: String) {
    val palette = Theme.palette
    var members by remember(network) { mutableStateOf<List<Item>?>(null) }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var container by remember { mutableStateOf("") }
    val aliases = remember { TextFieldState() }
    val v4 = remember { TextFieldState() }
    val v6 = remember { TextFieldState() }
    var problem by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(network, attempt) {
        problem = try {
            members = session.run(NETWORK, "containers", mapOf("id" to network)).toList()
            names = session.watch(CONTAINERS).filterIsInstance<ListEvent.Snapshot>().first().items.mapNotNull { it["name"] }.sorted()
            null
        } catch (e: DeviceException) {
            e.message
        }
    }
    fun act(command: String, arguments: Map<String, String>) = scope.launch {
        running = true
        problem = try {
            session.run(NETWORK, command, arguments + ("id" to network)).collect()
            attempt++
            null
        } catch (e: DeviceException) {
            e.message
        } finally {
            running = false
        }
    }
    val choose = stringResource(Res.string.docker_network_container)
    val shown = members
    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Select(container, names, { it.ifEmpty { choose } }, { container = it }, Modifier.width(200.dp).testTag("docker-network-container"))
            TextField(aliases, Modifier.width(170.dp), placeholder = stringResource(Res.string.docker_network_aliases), mono = true)
            TextField(v4, Modifier.width(140.dp), placeholder = stringResource(Res.string.docker_network_ipv4), mono = true)
            TextField(v6, Modifier.width(180.dp), placeholder = stringResource(Res.string.docker_network_ipv6), mono = true)
            Button(stringResource(Res.string.docker_network_connect), {
                act("connect", mapOf("container" to container, "aliases" to aliases.text.toString(), "ipv4-address" to v4.text.toString().trim(), "ipv6-address" to v6.text.toString().trim()))
            }, Modifier.testTag("docker-network-connect"), enabled = !running && container.isNotEmpty(), primary = true)
            Button(stringResource(Res.string.docker_refresh), { attempt++ }, enabled = !running)
        }
        problem?.let { Problem(it) { attempt++ } }
        when {
            shown == null -> Text(stringResource(Res.string.window_loading), Modifier.padding(12.dp), color = palette.muted, style = Theme.type.small)
            shown.isEmpty() -> Text(stringResource(Res.string.docker_network_empty), Modifier.padding(12.dp), color = palette.muted, style = Theme.type.small)
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                items(shown.size) { index ->
                    val member = shown[index]
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(member["name"].orEmpty(), Modifier.weight(1f), style = Theme.type.mono)
                        Text(member["state"].orEmpty(), Modifier.width(80.dp), style = Theme.type.small, color = palette.muted)
                        Text(listOf(member["ipv4-address"], member["ipv6-address"]).filterNot { it.isNullOrEmpty() }.joinToString(" "), Modifier.width(240.dp), style = Theme.type.mono)
                        Text(member["aliases"].orEmpty(), Modifier.width(140.dp), style = Theme.type.small, color = palette.muted)
                        Button(stringResource(Res.string.docker_network_disconnect), { act("disconnect", mapOf("container" to member.id)) }, enabled = !running)
                    }
                }
            }
        }
    }
}
