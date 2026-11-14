package com.vasmarfas.networkhelm.ui.main

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.app.Storage
import com.vasmarfas.networkhelm.app.parseAddress
import com.vasmarfas.networkhelm.journal.EditJournal
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.openwrt.RouterOsLayer
import com.vasmarfas.networkhelm.packs.DEFAULT_MENU_PRIO
import com.vasmarfas.networkhelm.packs.Menu
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.NATIVE_PATHS
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackFieldsSession
import com.vasmarfas.networkhelm.packs.PackForm
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.packs.PackTab
import com.vasmarfas.networkhelm.packs.bundledMenu
import com.vasmarfas.networkhelm.packs.opensTable
import com.vasmarfas.networkhelm.packs.packName
import com.vasmarfas.networkhelm.packs.tablesWindow
import com.vasmarfas.networkhelm.packs.withTablesAsTabs
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.routeros.RouterOsVersion
import com.vasmarfas.networkhelm.routeros.schema.label
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.keep
import com.vasmarfas.networkhelm.session.watchSettings
import com.vasmarfas.networkhelm.ui.files.FILE_WINDOW
import com.vasmarfas.networkhelm.ui.files.FileSpec
import com.vasmarfas.networkhelm.ui.form.FormState
import com.vasmarfas.networkhelm.ui.log.LogEntryWindow
import com.vasmarfas.networkhelm.ui.log.LogRequest
import com.vasmarfas.networkhelm.ui.log.LogWindows
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.terminal.TelnetCall
import com.vasmarfas.networkhelm.ui.workspace.WindowState
import com.vasmarfas.networkhelm.ui.workspace.WorkspaceState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class Recall {
    val schemas = HashMap<MenuPath, MenuSchema>()
    val rows = HashMap<String, List<Item>>()

    fun clear() {
        schemas.clear()
        rows.clear()
    }
}

class WindowTab(
    val title: String,
    val path: MenuPath,
    val shown: PackPath? = null,
    val labels: Map<String, String> = emptyMap(),
    val form: PackForm? = null,
    val prefill: Map<String, String> = emptyMap(),
    val recall: Recall? = null,
    val start: Boolean = false,
)

@Stable
class WindowSpec(val title: String, tabs: List<WindowTab>, start: Int, val automatic: Boolean = false, val selector: Boolean = false) {
    var tabs by mutableStateOf(tabs)
    var selected by mutableIntStateOf(start)
}

@Stable
class ItemSpec(
    val title: String,
    val path: MenuPath,
    id: String?,
    val shown: PackPath?,
    val form: PackForm?,
    val labels: Map<String, String>,
    val name: String? = null,
    val prefill: Map<String, String> = emptyMap(),
    val order: List<String> = emptyList(),
    val listed: Map<String, String> = emptyMap(),
) {
    var id by mutableStateOf(id)

    // what was typed into the form stays with its window while the window is not drawn:
    // minimized, under another one on a phone, behind the tab of another router
    internal var edits: FormState? = null
}

@Serializable
class SavedWindow(
    val title: String,
    val tabs: List<SavedTab>,
    val selected: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val maximized: Boolean = false,
    val minimized: Boolean = false,
)

@Serializable
class SavedTab(val title: String, val path: String)

// workspace names go inside the file; the gateway accepts only ASCII letters in file names
@Serializable
class SavedWorkspaces(val current: String = "", val layouts: Map<String, List<SavedWindow>> = emptyMap())

@Serializable
data class SavedTunnel(val localPort: Int, val host: String, val port: Int)

enum class MenuView { WinBox, Native, RouterOs }

@Stable
class RouterTab(
    session: DeviceSession,
    val address: String,
    packs: Collection<Pack>,
    private val storage: Storage,
    val tables: TableLayouts,
    val journal: EditJournal = EditJournal(),
    val login: RouterOsLogin? = null,
    private val tablesAsTabs: () -> Boolean = { false },
) {
    private val base: DeviceSession = if (session.capabilities.menuContents || session.device.platform != Platform.RouterOs) session else PackFieldsSession(session, packs)
    private val layer by lazy { RouterOsLayer(base) }

    var session by mutableStateOf(base)
        private set

    val title: String get() = "${session.device.identity} ($address)"
    private val running = RouterOsVersion.parse(session.device.version)
    private val bundled = packs
    private var plainPacks by mutableStateOf(shown(session.device.platform))
    private val packs by derivedStateOf { arranged(plainPacks) }
    private val recall = Recall()
    val workspace = WorkspaceState { id ->
        specs -= id
        items -= id
        files -= id
        logEntries -= id
        comparisons -= id
        terminals -= id
        telnets -= id
        telnetCalls -= id
    }
    val specs = mutableStateMapOf<Long, WindowSpec>()
    val items = mutableStateMapOf<Long, ItemSpec>()
    val files = mutableStateMapOf<Long, FileSpec>()
    val logEntries = mutableStateMapOf<Long, LogEntryWindow>()
    val logWindows = LogWindows(::openLogEntry, ::showInLog)
    val terminals = mutableStateListOf<Long>()
    val telnets = mutableStateListOf<Long>()
    val telnetCalls = mutableStateMapOf<Long, TelnetCall>()
    var menu by mutableStateOf<List<MenuEntry>?>(null)
    var view by mutableStateOf(MenuView.WinBox)
        private set
    var viewProblem by mutableStateOf<String?>(null)
        private set
    var journalWindow by mutableStateOf<Long?>(null)
    var prepareWindow by mutableStateOf<Long?>(null)
    var dhcpSetupWindow by mutableStateOf<Long?>(null)
    val comparisons = mutableStateMapOf<Long, Comparison>()
    var tunnelsWindow by mutableStateOf<Long?>(null)
    var dashboardWindow by mutableStateOf<Long?>(null)
    var topologyWindow by mutableStateOf<Long?>(null)
    val tunnels = Tunnels()
    var savedTunnels by mutableStateOf(emptyList<SavedTunnel>())
        private set
    var workspaceName by mutableStateOf("")
        private set
    var workspaceNames by mutableStateOf(emptyList<String>())
        private set

    private val router = (session.device.id ?: address).map { if (it.isLetterOrDigit() || it in ".-_") it else '_' }.joinToString("")
    private val layoutFile get() = if (view == MenuView.RouterOs) "workspace-$router-routeros.json" else "workspace-$router.json"
    private val workspacesFile get() = if (view == MenuView.RouterOs) "workspaces-$router-routeros.json" else "workspaces-$router.json"
    private val viewFile = "view-$router.txt"
    private val tunnelsFile = "tunnels-$router.json"
    private var restored = false
    private var switching = false
    private var loads = 0

    private var hidden = emptyList<String>()

    private val grows get() = base.device.platform == Platform.RouterOs && base.capabilities.menuContents

    fun open(entry: MenuEntry) {
        if (entry.window == FILE_WINDOW) {
            openFile(FileSpec(entry.arguments["name"] ?: return, entry.arguments["id"]?.ifEmpty { null }, entry.arguments["size"]?.toLongOrNull()))
            return
        }
        if (entry.window == "dhcp-setup") {
            dhcpSetupWindow = single(dhcpSetupWindow, entry.title, DpSize(560.dp, 480.dp))
            return
        }
        if (entry.window == "tunnels") {
            tunnelsWindow = single(tunnelsWindow, entry.title, DpSize(760.dp, 360.dp))
            return
        }
        if (entry.window == "dashboard") {
            dashboardWindow = single(dashboardWindow, entry.title, DpSize(980.dp, 680.dp))
            return
        }
        if (entry.window == "topology") {
            topologyWindow = single(topologyWindow, entry.title, DpSize(860.dp, 560.dp))
            return
        }
        if (entry.window == "terminal") {
            val shift = (workspace.windows.size % 8) * 24
            terminals += workspace.open("Terminal", DpOffset((40 + shift).dp, (30 + shift).dp), DpSize(760.dp, 440.dp)).id
            return
        }
        if (entry.window == "telnet" || entry.window == "mac-telnet") {
            val shift = (workspace.windows.size % 8) * 24
            val id = workspace.open(entry.title, DpOffset((40 + shift).dp, (30 + shift).dp), DpSize(760.dp, 440.dp)).id
            telnets += id
            entry.fill.values.firstOrNull()?.let { telnetCalls[id] = TelnetCall(it, entry.window == "mac-telnet") }
            return
        }
        val path = MenuPath.parse(entry.path ?: return)
        val pack = owner(entry, path)
        val title = pack?.window?.title ?: entry.title
        val given = entry.arguments + filled(path, entry.fill)
        if (entry.item) {
            openItem(pack?.window?.tabs?.firstOrNull { MenuPath.parse(it.path) == path }?.title ?: title, path, null, null, given)
            return
        }
        val open = workspace.windows.firstOrNull { window -> specs[window.id]?.let { it.title == title && it.tabs.any { tab -> tab.path == path } } == true }
        if (open != null && given.isEmpty() && !entry.start) {
            specs[open.id]?.let { spec -> spec.selected = spec.tabs.indexOfFirst { it.path == path } }
            workspace.bringToFront(open)
            return
        }
        open?.let(workspace::close)
        val spec = if (pack != null) {
            packSpec(pack, path, given, entry.start)
        } else {
            WindowSpec(title, listOf(windowTab(title, path.toString(), prefill = given, start = entry.start)), 0, automatic = true)
        }
        val shift = (workspace.windows.size % 8) * 24
        val columns = spec.tabs.getOrNull(spec.selected)?.shown?.columns?.size ?: 0
        val strip = if (spec.selector) 0 else (spec.tabs.sumOf { it.title.length * 7 + 26 } + title.length * 9 + 150).coerceAtMost(1100)
        val height = if (spec.tabs.getOrNull(spec.selected)?.shown?.log == true) 560.dp else 440.dp
        place(spec, DpOffset((16 + shift).dp, (16 + shift).dp), DpSize(maxOf(760, columns * 120 + 60, strip).dp, height))
    }

    private fun packSpec(pack: Pack, path: MenuPath, arguments: Map<String, String>, start: Boolean = false): WindowSpec {
        val tabs = pack.window.tabs.filter { running?.within(it.since, it.until) != false }
            .map { windowTab(it.title, it.path, pack, if (MenuPath.parse(it.path) == path) arguments else emptyMap(), start && MenuPath.parse(it.path) == path) }
        return WindowSpec(pack.window.title, withoutOwnLinks(tabs), tabs.indexOfFirst { it.path == path }, selector = pack.window.selector)
    }

    // a button that opens a table the window already holds as a tab gives way to the tab
    private fun withoutOwnLinks(tabs: List<WindowTab>): List<WindowTab> {
        val paths = tabs.map { it.path.toString() }.toSet()
        return tabs.map { tab ->
            val shown = tab.shown ?: return@map tab
            val kept = shown.links.filterNot { it.opensTable && it.path in paths }
            if (kept.size == shown.links.size) tab else WindowTab(tab.title, tab.path, shown.copy(links = kept), tab.labels, tab.form, tab.prefill, tab.recall, tab.start)
        }
    }

    // a link names the fields it fills by their labels in WinBox, which are the labels of the pack or the ones a property gets by its name
    private fun filled(path: MenuPath, fill: Map<String, String>): Map<String, String> {
        if (fill.isEmpty()) return emptyMap()
        val tab = windowTab("", path.toString())
        val properties = (tab.form?.tabs.orEmpty().flatMap { it.properties } + tab.shown?.columns.orEmpty() + tab.labels.keys).distinct()
        return fill.mapNotNull { (title, value) -> properties.singleOrNull { (tab.labels[it] ?: label(it)).equals(title, ignoreCase = true) }?.let { it to value } }.toMap()
    }

    fun trail(window: WindowState): List<String> {
        val spec = specs[window.id] ?: return listOf(window.title)
        val shown = spec.tabs.getOrNull(spec.selected)
        val pack = packs.firstOrNull { it.window.title == spec.title && it.window.tabs.any { tab -> tab.path == shown?.path.toString() } }
        return (pack?.menu ?: listOf(spec.title)) + listOfNotNull(shown?.title?.takeIf { spec.tabs.size > 1 })
    }

    fun placeOf(path: MenuPath): List<String> {
        for (pack in packs) {
            val shown = pack.window.tabs.firstOrNull { MenuPath.parse(it.path) == path } ?: continue
            val trail = pack.menu + listOfNotNull(shown.title.takeIf { pack.window.tabs.size > 1 })
            if (trail.isNotEmpty()) return trail
        }
        return path.segments.map(::label)
    }

    // windows made of the descriptions of the router have no console path, so a tab is found by the names of its menu and of the tab itself
    fun packPath(menu: List<String>, tab: String): MenuPath? =
        packs.firstOrNull { it.menu == menu }?.window?.tabs?.firstOrNull { it.title == tab }?.path?.let(MenuPath::parse)

    fun tabs(entry: MenuEntry): List<PackTab> {
        val pack = owner(entry, MenuPath.parse(entry.path ?: return emptyList())) ?: return emptyList()
        return pack.window.tabs.filter { running?.within(it.since, it.until) != false }.takeIf { it.size > 1 }.orEmpty()
    }

    private fun owner(entry: MenuEntry, path: MenuPath): Pack? {
        val owners = packs.filter { pack -> pack.window.tabs.any { MenuPath.parse(it.path) == path } }
        return owners.firstOrNull { it.menu.last() == entry.title } ?: owners.firstOrNull()
    }

    // uhttpd redirects this plain HTTP address to HTTPS where it is set to
    fun webPage(page: String): String {
        val host = parseAddress(address)?.host ?: address
        return "http://${if (':' in host) "[$host]" else host}/cgi-bin/luci/$page"
    }

    fun openJournal(title: String) {
        journalWindow = single(journalWindow, title, DpSize(760.dp, 360.dp))
    }

    fun openPrepare(title: String) {
        prepareWindow = single(prepareWindow, title, DpSize(640.dp, 440.dp))
    }

    fun openComparison(title: String): Comparison {
        val shift = (workspace.windows.size % 8) * 24
        return Comparison().also { comparisons[workspace.open(title, DpOffset((60 + shift).dp, (40 + shift).dp), DpSize(860.dp, 520.dp)).id] = it }
    }

    fun openLogEntry(entry: LogEntryWindow) {
        val shown = workspace.windows.firstOrNull { window -> logEntries[window.id]?.let { it.path == entry.path && it.row.first.id == entry.row.first.id } == true }
        if (shown != null) {
            workspace.bringToFront(shown)
            return
        }
        while (logEntries.size >= LOG_ENTRY_WINDOWS) {
            val oldest = logEntries.keys.min()
            workspace.close(workspace.windows.first { it.id == oldest })
        }
        val shift = (workspace.windows.size % 8) * 24
        logEntries[workspace.open(entry.title, DpOffset((90 + shift).dp, (50 + shift).dp), DpSize(640.dp, 460.dp)).id] = entry
    }

    fun showInLog(path: MenuPath, id: String) {
        val window = workspace.windows.firstOrNull { open -> specs[open.id]?.tabs?.any { it.path == path } == true }
        if (window == null) {
            open(MenuEntry("Log", path.toString()))
        } else {
            specs[window.id]?.let { spec -> spec.selected = spec.tabs.indexOfFirst { it.path == path } }
            workspace.bringToFront(window)
        }
        logWindows.request = LogRequest(path, id)
    }

    private fun openFile(spec: FileSpec) {
        val open = workspace.windows.firstOrNull { window -> files[window.id]?.path == spec.path }
        if (open != null) {
            workspace.bringToFront(open)
            return
        }
        val shift = (workspace.windows.size % 8) * 24
        files[workspace.open(spec.name, DpOffset((70 + shift).dp, (30 + shift).dp), DpSize(900.dp, 600.dp)).id] = spec
    }

    private fun single(id: Long?, title: String, size: DpSize): Long {
        val open = workspace.windows.firstOrNull { it.id == id }
        if (open != null) {
            workspace.bringToFront(open)
            return open.id
        }
        return workspace.open(title, DpOffset(120.dp, 60.dp), size).id
    }

    fun openItem(title: String, path: MenuPath, id: String?, name: String? = null, prefill: Map<String, String> = emptyMap(), order: List<String> = emptyList(), listed: Map<String, String> = emptyMap()) {
        val open = workspace.windows.firstOrNull { window ->
            items[window.id]?.let { it.path == path && (id != null && it.id == id || name != null && it.name == name) } == true
        }
        if (open != null) {
            workspace.bringToFront(open)
            return
        }
        val tab = windowTab(title, path.toString())
        val shift = (workspace.windows.size % 8) * 24
        val window = workspace.open(title, DpOffset((80 + shift).dp, (40 + shift).dp), DpSize(720.dp, 540.dp))
        items[window.id] = ItemSpec(title, path, id, tab.shown, tab.form, tab.labels, name, prefill, order, listed)
    }

    suspend fun expand(spec: WindowSpec) {
        if (!spec.automatic) {
            val shown = spec.tabs.getOrNull(spec.selected) ?: return
            val lacking = if (spec.tabs.size > 1) lacking(spec.tabs.map { it.path }) else emptySet()
            val kept = spec.tabs.filter { it == shown || it.path !in lacking }
            val added = beside(kept.map { it.path }).map { windowTab(label(it.segments.last()), it.toString()) }
            if (kept.size == spec.tabs.size && added.isEmpty()) return
            spec.tabs = kept + added
            spec.selected = spec.tabs.indexOf(shown)
            return
        }
        if (!spec.automatic || spec.tabs.size != 1) return
        val first = spec.tabs.single()
        val own = describedOrNull(first.path) ?: return
        val around = first.path.parent?.takeIf { it.segments.size >= 2 }?.let { directory ->
            describedOrNull(directory)?.takeIf { it.kind == NodeKind.Directory }?.children?.map(directory::child)
        }.orEmpty()
        val packed = packs.flatMap { pack -> pack.window.tabs.map { it.path } }.toSet()
        val found = coroutineScope {
            (own.children.map(first.path::child) + around).filter { it != first.path && it.toString() !in packed }
                .map { path -> async { path.takeIf { describedOrNull(it)?.kind.let { kind -> kind == NodeKind.List || kind == NodeKind.Settings } } } }
                .awaitAll().filterNotNull()
        }
        val head = if (around.isEmpty()) first else windowTab(label(first.path.segments.last()), first.path.toString())
        val tabs = (if (own.kind == NodeKind.Directory) emptyList() else listOf(head)) + found.map { windowTab(label(it.segments.last()), it.toString()) }
        if (tabs.isNotEmpty()) spec.tabs = tabs
    }

    private suspend fun beside(tabs: List<MenuPath>): List<MenuPath> {
        if (!grows) return emptyList()
        val root = rootOf(tabs) ?: return emptyList()
        val children = describedOrNull(root)?.children ?: return emptyList()
        val known = claimed(menu.orEmpty())
        return coroutineScope {
            children.map(root::child).filter { it !in tabs && !known.covers(it) && !hides(it) }
                .map { path -> async { path.takeIf { describedOrNull(it)?.kind.let { kind -> kind == NodeKind.List || kind == NodeKind.Settings } } } }
                .awaitAll().filterNotNull()
        }
    }

    private fun rootOf(tabs: List<MenuPath>): MenuPath? {
        if (tabs.size == 1) return tabs.single()
        val shared = tabs.map { it.segments }.reduceOrNull { left, right -> left.zip(right).takeWhile { (a, b) -> a == b }.map { it.first } }.orEmpty()
        return shared.takeIf { it.isNotEmpty() }?.let(::MenuPath)
    }

    private fun claimed(entries: List<MenuEntry>): Set<String> = buildSet {
        for (pack in packs) {
            for (tab in pack.window.tabs) add(tab.path)
            for ((path, shown) in pack.paths) {
                add(path)
                for (link in shown.links) add(link.path)
                shown.open?.paths?.values?.let(::addAll)
            }
        }
        for (entry in flatten(entries)) entry.path?.let(::add)
    }

    private fun Set<String>.covers(path: MenuPath) = path.toString().let { name -> any { it == name || it.startsWith("$name/") } }

    private fun hides(path: MenuPath) = path.toString().let { name -> hidden.any { name == it || name.startsWith("$it/") } }

    private suspend fun describedOrNull(path: MenuPath) = try {
        session.outline(path)
    } catch (e: DeviceException) {
        null
    }

    private suspend fun lacking(paths: List<MenuPath>): Set<MenuPath> = coroutineScope {
        val parents = if (session.capabilities.menuContents) paths.mapNotNull { it.parent }.distinct() else emptyList()
        val listed = parents.associateWith { parent -> async { describedOrNull(parent)?.let { it.children + it.commands } } }
        paths.map { path ->
            async {
                path.takeIf {
                    path.parent?.let { listed[it]?.await() }?.contains(path.segments.last()) != true && try {
                        session.outline(path)
                        false
                    } catch (e: DeviceException) {
                        e.category == ErrorCategory.MissingItem
                    }
                }
            }
        }.awaitAll().filterNotNull().toSet()
    }

    private fun windowTab(title: String, path: String, owner: Pack? = null, prefill: Map<String, String> = emptyMap(), start: Boolean = false): WindowTab {
        val pack = owner?.takeIf { path in it.paths } ?: packs.firstOrNull { path in it.paths }
        val shown = pack?.paths?.get(path)
        return WindowTab(title, MenuPath.parse(path), shown, pack?.labels.orEmpty() + owner?.labels.orEmpty() + shown?.labels.orEmpty(), shown?.form?.let { pack.forms[it] }, prefill, recall, start)
    }

    fun place(spec: WindowSpec, position: DpOffset, size: DpSize): WindowState =
        workspace.open(spec.title, position, size).also { specs[it.id] = spec }

    suspend fun keepLayout() {
        if (!restored) {
            snapshotFlow { menu }.first { it != null }
            readWorkspaces()
            restoreLayout()
            restored = true
        }
        snapshotFlow { layout() }.drop(1).collectLatest { layout ->
            delay(500)
            if (!switching) saveLayout(layout)
        }
    }

    suspend fun showWorkspace(name: String) {
        if (name == workspaceName) return
        switching = true
        try {
            saveLayout(layout())
            for (window in workspace.windows.toList()) workspace.close(window)
            val saved = workspaces()
            val layouts = if (name.isEmpty() || name in saved.layouts) saved.layouts else saved.layouts + (name to emptyList())
            writeWorkspaces(SavedWorkspaces(name, layouts))
            restoreLayout()
        } finally {
            switching = false
        }
    }

    suspend fun copyWorkspace(name: String) {
        if (name == workspaceName || name in workspaceNames) return
        val layout = layout()
        saveLayout(layout)
        writeWorkspaces(SavedWorkspaces(name, workspaces().layouts + (name to layout)))
    }

    suspend fun renameWorkspace(name: String) {
        val old = workspaceName.takeIf { it.isNotEmpty() } ?: return
        if (name == old || name in workspaceNames) return
        writeWorkspaces(SavedWorkspaces(name, workspaces().layouts - old + (name to layout())))
    }

    suspend fun removeWorkspace() {
        val name = workspaceName.takeIf { it.isNotEmpty() } ?: return
        switching = true
        try {
            for (window in workspace.windows.toList()) workspace.close(window)
            writeWorkspaces(SavedWorkspaces("", workspaces().layouts - name))
            restoreLayout()
        } finally {
            switching = false
        }
    }

    private suspend fun saveLayout(layout: List<SavedWindow>) {
        if (workspaceName.isEmpty()) {
            storage.keep(layoutFile, json.encodeToString(layoutSerializer, layout))
        } else {
            val saved = workspaces()
            writeWorkspaces(SavedWorkspaces(workspaceName, saved.layouts + (workspaceName to layout)))
        }
    }

    private suspend fun restoreLayout() {
        val saved = if (workspaceName.isNotEmpty()) {
            workspaces().layouts[workspaceName].orEmpty()
        } else {
            val text = storage.read(layoutFile) ?: return
            try {
                json.decodeFromString(layoutSerializer, text)
            } catch (e: IllegalArgumentException) {
                // a layout from an older build or a cut file: the router opens with an empty workspace
                emptyList()
            }
        }
        for (window in saved) restore(window)
    }

    private suspend fun workspaces(): SavedWorkspaces = storage.read(workspacesFile)?.let { text ->
        try {
            json.decodeFromString(SavedWorkspaces.serializer(), text)
        } catch (e: IllegalArgumentException) {
            null
        }
    } ?: SavedWorkspaces()

    private suspend fun readWorkspaces() {
        val saved = workspaces()
        workspaceNames = saved.layouts.keys.sorted()
        workspaceName = saved.current.takeIf { it in saved.layouts }.orEmpty()
    }

    private suspend fun writeWorkspaces(saved: SavedWorkspaces) {
        storage.keep(workspacesFile, json.encodeToString(SavedWorkspaces.serializer(), saved))
        workspaceNames = saved.layouts.keys.sorted()
        workspaceName = saved.current
    }

    suspend fun readTunnels() {
        savedTunnels = storage.read(tunnelsFile)?.let { text ->
            try {
                json.decodeFromString(tunnelsSerializer, text)
            } catch (e: IllegalArgumentException) {
                null
            }
        }.orEmpty()
    }

    suspend fun keepTunnel(tunnel: SavedTunnel) = writeTunnels(listOf(tunnel) + (savedTunnels - tunnel))

    suspend fun forgetTunnel(tunnel: SavedTunnel) = writeTunnels(savedTunnels - tunnel)

    private suspend fun writeTunnels(tunnels: List<SavedTunnel>) {
        savedTunnels = tunnels
        storage.keep(tunnelsFile, json.encodeToString(tunnelsSerializer, tunnels))
    }

    private fun restore(saved: SavedWindow) {
        val owner = packs.firstOrNull { it.window.title == saved.title }
        val tabs = withoutOwnLinks(saved.tabs.map { windowTab(it.title, it.path, owner) })
        if (tabs.isEmpty()) return
        val paths = tabs.map { it.path }
        if (workspace.windows.any { window -> specs[window.id]?.let { it.title == saved.title && it.tabs.map(WindowTab::path) == paths } == true }) return
        val spec = WindowSpec(saved.title, tabs, saved.selected.coerceIn(tabs.indices), automatic = owner == null, selector = owner?.window?.selector == true)
        val window = place(spec, DpOffset(saved.x.dp, saved.y.dp), DpSize(saved.width.dp, saved.height.dp))
        window.maximized = saved.maximized
        if (saved.minimized) workspace.minimize(window)
    }

    private fun layout(): List<SavedWindow> = workspace.windows.mapNotNull { window ->
        val spec = specs[window.id] ?: return@mapNotNull null
        SavedWindow(
            spec.title,
            spec.tabs.map { SavedTab(it.title, it.path.toString()) },
            spec.selected,
            window.position.x.value,
            window.position.y.value,
            window.size.width.value,
            window.size.height.value,
            window.maximized,
            window.minimized,
        )
    }

    private var loaded = false
    private var cut = false

    // the menu is read once for a tab, so a return from the tab of another router finds the windows as they were left
    // and asks the router nothing; a read that the link cut short is made again
    suspend fun openMenu() {
        if (loaded) return
        cut = false
        loadMenu()
        loaded = !cut
    }

    suspend fun loadMenu() {
        recall.clear()
        view = storage.read(viewFile)?.let { name -> MenuView.entries.firstOrNull { it.name == name } } ?: MenuView.WinBox
        viewProblem = null
        if (view == MenuView.RouterOs && base.device.platform == Platform.OpenWrt) {
            session = layer
            plainPacks = layer.windows(shown(Platform.RouterOs))
            loadMenu(bundledMenu(Platform.RouterOs))
            return
        }
        session = base
        plainPacks = shown(base.device.platform)
        val native = if (view == MenuView.Native) {
            try {
                base.nativeMenu()
            } catch (e: DeviceException) {
                viewProblem = e.message
                null
            }
        } else {
            null
        }
        if (native == null) {
            view = MenuView.WinBox
            loadMenu(bundledMenu(base.device.platform))
            return
        }
        plainPacks = plainPacks + native.packs.filter { own -> plainPacks.none { it.menu == own.menu } }
        loadMenu(native.menu, native = true)
    }

    suspend fun reloadStructure() {
        base.forgetStructure()
        loadMenu()
    }

    var routerUpdate by mutableStateOf<String?>(null)
        private set

    var routerInstalled by mutableStateOf<String?>(null)
        private set

    private val update = MenuPath.parse("system/package/update")

    // the router's word on versions changes in this window, so the card in the toolbar asks again as the window comes and goes
    val updating: Boolean get() = workspace.windows.any { window -> specs[window.id]?.tabs?.any { it.path == update } == true }

    // the router tells what it found on its last check; a user who cannot read that state has nothing to be told
    suspend fun checkRouterUpdate() {
        if (base.device.platform != Platform.RouterOs) return
        val state = try {
            base.watchSettings(update).firstOrNull()
        } catch (e: DeviceException) {
            return
        } ?: return
        val latest = state["latest-version"] ?: return
        val installed = state["installed-version"]?.let(RouterOsVersion::parse) ?: return
        routerInstalled = state["installed-version"]
        routerUpdate = latest.takeIf { RouterOsVersion.parse(it)?.let { version -> version > installed } == true }
    }

    fun openUpdate() = open(MenuEntry("Check For Updates", update.toString()))

    suspend fun changeView(view: MenuView) {
        val crossing = (view == MenuView.RouterOs) != (this.view == MenuView.RouterOs)
        switching = true
        try {
            if (crossing) {
                saveLayout(layout())
                for (window in workspace.windows.toList()) workspace.close(window)
            }
            storage.keep(viewFile, view.name)
            menu = null
            loadMenu()
            if (crossing) {
                readWorkspaces()
                restoreLayout()
            }
        } finally {
            switching = false
        }
    }

    private fun shown(platform: Platform) = bundled.filter { it.platform == platform.packName && running?.within(it.since, it.until) != false }

    private fun arranged(packs: List<Pack>) = if (tablesAsTabs()) packs.map(Pack::withTablesAsTabs) else packs + packs.mapNotNull(Pack::tablesWindow)

    suspend fun loadMenu(source: Menu, native: Boolean = false) {
        val load = ++loads
        val entries = source.items
        hidden = source.hidden
        val paths = flatten(entries).mapNotNull { it.path?.let(MenuPath::parse) }
        val parents = if (session.capabilities.menuContents) paths.mapNotNull { it.parent }.distinct() else emptyList()
        val known = coroutineScope {
            parents.map { parent ->
                async {
                    parent to try {
                        session.outline(parent).let { (it.children + it.commands).toSet() }
                    } catch (e: DeviceException) {
                        if (e.category == ErrorCategory.MissingItem) emptySet() else null
                    }
                }
            }.awaitAll().toMap()
        }

        // where a menu cannot say what it holds (RouterOS 6), each entry asks after its own path
        val lacking = coroutineScope {
            paths.filter { path -> path.parent?.let { known[it] } == null }.map { path ->
                async {
                    try {
                        session.outline(path)
                        null
                    } catch (e: DeviceException) {
                        path.takeIf { e.category == ErrorCategory.MissingItem }
                    }
                }
            }.awaitAll().filterNotNull().toSet()
        }

        fun visible(entry: MenuEntry): MenuEntry? {
            if (running?.within(entry.since, entry.until) == false) return null
            if (entry.window == "telnet" && session.ssh == null) return null
            if (lacksPackage(entry.requires)) return null
            if (entry.items.isNotEmpty()) {
                val children = entry.items.mapNotNull(::visible)
                return if (children.isEmpty()) null else MenuEntry(entry.title, entry.path, children, entry.requires, prio = entry.prio)
            }
            if (entry.page != null || entry.window != null && entry.path == null) return entry
            val path = entry.path?.let(MenuPath::parse) ?: return null
            val siblings = path.parent?.let { known[it] } ?: return entry.takeIf { path !in lacking }
            return entry.takeIf { path.segments.last() in siblings }
        }
        val windowed = packs.filter { pack -> pack.window.tabs.any { MenuPath.parse(it.path) in paths } }.flatMap { pack -> pack.window.tabs.map { MenuPath.parse(it.path) } }
        val named = (paths + windowed).map { it.segments.first() }.toSet() + source.hidden
        val extra = if (native) emptyList() else describedOrNull(MenuPath.Root)?.children.orEmpty().filter { it !in named }.map { MenuEntry(label(it), it) }
        val offered = entries.mapNotNull(::visible)
        val shown = if (native || !grows) offered else withNew(offered, claimed(entries))
        if (load != loads) return
        menu = shown + apart(extra)
        if (native) return

        val made = try {
            session.packs(storage)
        } catch (e: DeviceException) {
            // a lost connection shows in the link strip, and the menu stays without them
            cut = true
            return
        }
        if (made.isEmpty() || load != loads) return
        val own = made.filter { pack -> plainPacks.none { it.menu == pack.menu || it.window.tabs.any { tab -> tab.path == pack.window.tabs.firstOrNull()?.path } } }
        plainPacks = if (grows) plainPacks.filter { pack -> made.none { it.menu == pack.menu } } + made else plainPacks + own
        val windows = arranged(made)
        for ((id, spec) in specs.entries.toList()) {
            val shown = spec.tabs.getOrNull(spec.selected) ?: continue
            val pack = windows.firstOrNull { pack -> pack.window.title == spec.title && pack.window.tabs.any { MenuPath.parse(it.path) == shown.path } }
            specs[id] = when {
                pack != null -> packSpec(pack, shown.path, shown.prefill)
                spec.automatic -> WindowSpec(spec.title, spec.tabs.map { windowTab(it.title, it.path.toString(), prefill = it.prefill) }, spec.selected, automatic = true)
                else -> continue
            }
        }
        val configs = own.flatMap { pack -> pack.window.tabs.map { it.path.substringBefore('/') } }.toSet()
        val moved = movedInto(made)
        val wired = rewired(shown.filterNot { it.items.isEmpty() && it.title in moved }, null, own)
        val listed = flatten(wired).mapNotNull { it.path }.toSet()
        menu = withPacks(wired, own.filter { it.window.tabs.firstOrNull()?.path !in listed && !it.hidden }) + apart(extra.filter { it.path !in configs })
    }

    // /cmr stands in every RouterOS for its client, so the path of an entry does not tell whether the package it needs is installed
    private fun lacksPackage(requires: String?): Boolean {
        val name = requires?.removeSuffix(" package")?.takeIf { it != requires } ?: return false
        return base.device.platform == Platform.RouterOs && name !in base.device.packages
    }

    // the router's description may put an entry of the bundled top level into a group, as CAPsMAN under Wireless, and it stands only there;
    // a window its buttons open, as CAPs Scanner, stands under it and makes no group of it
    private fun movedInto(made: List<Pack>): Set<String> {
        val places = made.filterNot { it.hidden }.map { it.menu }
        val groups = places.filter { it.size > 1 }.flatMap { it.dropLast(1) }.toSet()
        return places.filter { it.size > 1 }.map { it.last() }.toSet() - places.filter { it.size == 1 }.map { it.first() }.toSet() - groups
    }

    // an entry of the bundled menu whose window the router describes as tables of the object model opens that window
    private fun rewired(entries: List<MenuEntry>, group: String?, own: List<Pack>): List<MenuEntry> = entries.map { entry ->
        val pack = own.firstOrNull { it.menu == listOfNotNull(group, entry.title) }?.takeIf { it.window.tabs.firstOrNull()?.path?.startsWith("$NATIVE_PATHS/") == true }
        when {
            entry.items.isNotEmpty() -> MenuEntry(entry.title, entry.path, rewired(entry.items, entry.title, own), entry.requires, entry.window, entry.since, entry.until, entry.page, entry.arguments, entry.prio)
            pack != null && entry.path != null -> MenuEntry(entry.title, pack.window.tabs.first().path, entry.items, entry.requires, entry.window, entry.since, entry.until, entry.page, entry.arguments, entry.prio)
            else -> entry
        }
    }

    // RouterOS has top-level menus that WinBox shows nowhere, such as /suggestion
    private fun apart(found: List<MenuEntry>) =
        if (found.isEmpty() || base.device.platform != Platform.RouterOs) found else listOf(MenuEntry("More", items = found))

    private suspend fun withNew(groups: List<MenuEntry>, known: Set<String>): List<MenuEntry> {
        val windows = packs.mapNotNull { pack -> rootOf(pack.window.tabs.map { MenuPath.parse(it.path) }) }.toSet()
        return coroutineScope {
            groups.map { group ->
                async {
                    val directory = directoryOf(group)?.takeIf { it !in windows } ?: return@async group
                    val found = describedOrNull(directory)?.children.orEmpty().map(directory::child).filter { !known.covers(it) && !hides(it) }
                        .map { path -> async { path.takeIf { holdsWindow(it) } } }.awaitAll().filterNotNull()
                    if (found.isEmpty()) group else MenuEntry(group.title, group.path, group.items.withEntries(found.map { MenuEntry(label(it.segments.last()), it.toString()) }), group.requires, prio = group.prio)
                }
            }.awaitAll()
        }
    }

    private fun directoryOf(group: MenuEntry): MenuPath? {
        val firsts = flatten(group.items).mapNotNull { it.path?.let(MenuPath::parse)?.segments?.firstOrNull() }
        val top = firsts.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return MenuPath(listOf(top.key)).takeIf { top.value * 2 > firsts.size }
    }

    private suspend fun holdsWindow(path: MenuPath): Boolean {
        val schema = describedOrNull(path) ?: return false
        fun shows(kind: NodeKind?) = kind == NodeKind.List || kind == NodeKind.Settings
        return when (schema.kind) {
            NodeKind.List, NodeKind.Settings -> true
            NodeKind.Command -> false
            NodeKind.Directory -> coroutineScope { schema.children.map { child -> async { shows(describedOrNull(path.child(child))?.kind) } }.awaitAll().any { it } }
        }
    }

    private fun List<MenuEntry>.withEntries(added: List<MenuEntry>): List<MenuEntry> = added.fold(this) { items, entry ->
        val at = items.indexOfFirst { it.title > entry.title }
        if (at < 0) items + entry else items.take(at) + entry + items.drop(at)
    }

    private fun withPacks(entries: List<MenuEntry>, own: List<Pack>): List<MenuEntry> {
        val (alone, grouped) = own.partition { pack -> pack.menu.size == 1 && entries.none { it.title == pack.menu.first() } }
        val added = grouped.groupBy({ it.menu.first() }, { MenuEntry(it.menu.last(), it.window.tabs.first().path) }).mapValues { (_, items) -> items.sortedBy { it.title } }
        val grown = entries.map { entry ->
            added[entry.title]?.let { MenuEntry(entry.title, entry.path, entry.items + it, entry.requires, entry.window, entry.since, entry.until, prio = entry.prio) } ?: entry
        }
        val groups = added.filterKeys { group -> entries.none { it.title == group } }.map { (group, items) ->
            MenuEntry(group, items = items, prio = grouped.filter { it.menu.first() == group }.mapNotNull { it.prio }.minOrNull())
        }
        return (groups + alone.map { MenuEntry(it.menu.first(), it.window.tabs.first().path, prio = it.prio) }).fold(grown) { menu, entry -> menu.placed(entry) }
    }

    // WinBox sorts the top level by the priority the router's descriptions give, and by title among equals
    private fun List<MenuEntry>.placed(entry: MenuEntry): List<MenuEntry> {
        val prio = entry.prio ?: DEFAULT_MENU_PRIO
        val at = indexOfFirst { other -> (other.prio ?: DEFAULT_MENU_PRIO).let { it > prio || it == prio && other.title > entry.title } }
        return if (at < 0) this + entry else take(at) + entry + drop(at)
    }

    private fun flatten(entries: List<MenuEntry>): List<MenuEntry> = entries.flatMap { listOf(it) + flatten(it.items) }

    private companion object {
        const val LOG_ENTRY_WINDOWS = 8
        val layoutSerializer = ListSerializer(SavedWindow.serializer())
        val tunnelsSerializer = ListSerializer(SavedTunnel.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }
}
