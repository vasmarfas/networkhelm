package com.vasmarfas.networkhelm.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronsDownUp
import com.composables.icons.lucide.ChevronsUpDown
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.FilePlus
import com.composables.icons.lucide.FolderDown
import com.composables.icons.lucide.FolderUp
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Upload
import com.composables.icons.lucide.Zap
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.lacks
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.PackAction
import com.vasmarfas.networkhelm.routeros.schema.label
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.atMostEvery
import com.vasmarfas.networkhelm.session.rows
import com.vasmarfas.networkhelm.session.watchSettings
import com.vasmarfas.networkhelm.ui.chart.WHOLE_BYTES
import com.vasmarfas.networkhelm.ui.kit.CountPill
import com.vasmarfas.networkhelm.ui.kit.DropdownItem
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.kit.ToolbarButton
import com.vasmarfas.networkhelm.ui.kit.copyClipboard
import com.vasmarfas.networkhelm.ui.kit.hand
import com.vasmarfas.networkhelm.ui.kit.writeText
import com.vasmarfas.networkhelm.ui.main.ActionsMenu
import com.vasmarfas.networkhelm.ui.main.Confirm
import com.vasmarfas.networkhelm.ui.main.Output
import com.vasmarfas.networkhelm.ui.main.WindowTab
import com.vasmarfas.networkhelm.ui.main.bytes
import com.vasmarfas.networkhelm.ui.main.output
import com.vasmarfas.networkhelm.ui.main.present
import com.vasmarfas.networkhelm.ui.table.SavedSort
import com.vasmarfas.networkhelm.ui.table.SortKey
import com.vasmarfas.networkhelm.ui.table.TableLayout
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.table.TableState
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.theme.tint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.command_question
import networkhelm.app.shared.generated.resources.file_import
import networkhelm.app.shared.generated.resources.files_auto_question
import networkhelm.app.shared.generated.resources.files_back
import networkhelm.app.shared.generated.resources.files_bad_name
import networkhelm.app.shared.generated.resources.files_collapse
import networkhelm.app.shared.generated.resources.files_collapse_all
import networkhelm.app.shared.generated.resources.files_copy_name
import networkhelm.app.shared.generated.resources.files_copy_path
import networkhelm.app.shared.generated.resources.files_delete
import networkhelm.app.shared.generated.resources.files_delete_folders
import networkhelm.app.shared.generated.resources.files_delete_question
import networkhelm.app.shared.generated.resources.files_download
import networkhelm.app.shared.generated.resources.files_download_folder
import networkhelm.app.shared.generated.resources.files_drop
import networkhelm.app.shared.generated.resources.files_empty
import networkhelm.app.shared.generated.resources.files_expand
import networkhelm.app.shared.generated.resources.files_expand_all
import networkhelm.app.shared.generated.resources.files_expand_below
import networkhelm.app.shared.generated.resources.files_export
import networkhelm.app.shared.generated.resources.files_filtered_empty
import networkhelm.app.shared.generated.resources.files_forward
import networkhelm.app.shared.generated.resources.files_name_hint
import networkhelm.app.shared.generated.resources.files_name_taken
import networkhelm.app.shared.generated.resources.files_new
import networkhelm.app.shared.generated.resources.files_new_file
import networkhelm.app.shared.generated.resources.files_new_folder
import networkhelm.app.shared.generated.resources.files_no_folder
import networkhelm.app.shared.generated.resources.files_only_folder
import networkhelm.app.shared.generated.resources.files_open
import networkhelm.app.shared.generated.resources.files_path
import networkhelm.app.shared.generated.resources.files_properties
import networkhelm.app.shared.generated.resources.files_refresh
import networkhelm.app.shared.generated.resources.files_rename
import networkhelm.app.shared.generated.resources.files_selected_size
import networkhelm.app.shared.generated.resources.files_send
import networkhelm.app.shared.generated.resources.files_space
import networkhelm.app.shared.generated.resources.files_space_free
import networkhelm.app.shared.generated.resources.files_up
import networkhelm.app.shared.generated.resources.files_upload
import networkhelm.app.shared.generated.resources.files_upload_folder
import networkhelm.app.shared.generated.resources.form_actions
import networkhelm.app.shared.generated.resources.form_configuration
import networkhelm.app.shared.generated.resources.list_find
import networkhelm.app.shared.generated.resources.list_items
import networkhelm.app.shared.generated.resources.list_items_of
import networkhelm.app.shared.generated.resources.list_selected
import networkhelm.app.shared.generated.resources.window_loading
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

const val FILE_WINDOW = "file"

private enum class NewKind { Folder, File }

private class Making(val kind: NewKind, val into: String, val field: TextFieldState)

@Composable
internal fun FilesView(
    session: DeviceSession,
    tab: WindowTab,
    schema: MenuSchema,
    tables: TableLayouts,
    onOpenItem: (String, MenuPath, String?, String?, Map<String, String>, List<String>) -> Unit,
    onOpenWindow: (MenuEntry) -> Unit,
) {
    val palette = Theme.palette
    val compact = LocalCompact.current
    val path = tab.path
    val pack = tab.shown
    val name = path.toString()
    val scope = rememberCoroutineScope()
    val backend = remember(session, path) { FileBackend(session, path) }
    val exchange = LocalFileExchange.current
    val clipboard = copyClipboard()
    val transfers = remember(session, path) { Transfers(scope) }
    val routerOs = session.device.platform == Platform.RouterOs
    val bulk by backend.bulk.collectAsState()
    val ready = bulk is Bulk.Ready
    LaunchedEffect(backend) { backend.probe() }
    val remote = !routerOs && ready
    var refresh by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    val arrived = remember(session, path, refresh) {
        session.watch(path).rows().drop(1).atMostEvery(150.milliseconds).catch { (it as? DeviceException)?.let { e -> problem = e.message } }
    }.collectAsState(null).value
    val feed = remember(arrived) { arrived.orEmpty().mapNotNull(::entryOf) }

    val saved = remember(name) { tables[name] }
    val state = remember(name) {
        TableState<String>().apply {
            sort = saved?.sort?.map { SortKey(it.column, it.ascending) }.orEmpty()
            saved?.widths?.forEach { (column, width) -> widths[column] = width.dp }
        }
    }
    LaunchedEffect(name) {
        snapshotFlow { TableLayout(widths = state.widths.mapValues { it.value.value }, sort = state.sort.map { SavedSort(it.column, it.ascending) }) }
            .drop(1).collect { tables[name] = it }
    }
    val kept = "files-tree/${session.device.id ?: session.device.identity}"
    val expanded = remember(kept) { mutableStateSetOf<String>().apply { addAll(tables[kept]?.expanded.orEmpty()) } }
    LaunchedEffect(kept) {
        snapshotFlow { expanded.sorted() }.drop(1).collect { tables[kept] = TableLayout(expanded = it) }
    }
    val query = remember { TextFieldState() }
    val text = query.text.toString().trim()
    val find = remember { FocusRequester() }
    val typing = remember { FocusRequester() }
    var editing by remember { mutableStateOf<TextFieldState?>(null) }
    var dropping by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<String?>(null) }

    var root by remember(path) { mutableStateOf("") }
    val back = remember(path) { mutableStateListOf<String>() }
    val forward = remember(path) { mutableStateListOf<String>() }
    fun go(target: String) {
        if (target == root) return
        back += root
        forward.clear()
        root = target
        state.selected = emptySet()
    }
    fun up() = go(root.substringBeforeLast('/', ""))
    fun goBack() {
        val target = back.removeLastOrNull() ?: return
        forward += root
        root = target
        state.selected = emptySet()
    }
    fun goForward() {
        val target = forward.removeLastOrNull() ?: return
        back += root
        root = target
        state.selected = emptySet()
    }

    val loaded = remember(path) { mutableStateMapOf<String, List<Entry>>() }
    val listed = loaded.toMap()
    val all = remember(feed, listed) { if (listed.isEmpty()) feed else feed.filter { it.parent !in listed } + listed.values.flatten() }
    val tree = remember(all, routerOs) { FileTree(all, partial = !routerOs) }
    val unfolded = expanded.toSet()
    val rows = remember(tree, root, unfolded, state.sort, text) { tree.rows(root, unfolded, state.sort.firstOrNull(), text) }
    val chosenRows = remember(rows, state.selected) { rows.filter { it.entry.path in state.selected } }
    val chosen = remember(chosenRows) { chosenRows.map { it.entry } }
    val loading = if (remote) unfolded.filter { it in tree.folders && it !in listed }.toSet() else emptySet()
    val reading = arrived == null

    fun guarded(block: suspend () -> Unit) {
        scope.launch {
            problem = try {
                block()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fileFailure(e)
            }
        }
    }

    val treeNow by rememberUpdatedState(tree)
    LaunchedEffect(remote, refresh) {
        if (!remote) return@LaunchedEffect
        fun wanted() = expanded.filterTo(LinkedHashSet()) { it in treeNow.folders }
        while (true) {
            val folders = wanted()
            if (folders.isNotEmpty()) {
                try {
                    for ((folder, result) in backend.list(folders)) {
                        result.onSuccess { loaded[folder] = it }.onFailure {
                            if (folder !in loaded) loaded[folder] = emptyList()
                            problem = fileFailure(it)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    for (folder in folders) if (folder !in loaded) loaded[folder] = emptyList()
                    problem = fileFailure(e)
                }
            }
            withTimeoutOrNull(3.seconds) { snapshotFlow { wanted() }.first { it != folders } }
        }
    }
    LaunchedEffect(tree, arrived) {
        if (arrived == null || root.isEmpty() || remote || tree.entry(root) != null) return@LaunchedEffect
        var parent = root
        while (parent.isNotEmpty() && tree.entry(parent) == null) parent = parent.substringBeforeLast('/', "")
        root = parent
        state.selected = emptySet()
    }

    val canWrite = session.device.canWrite && session.device.lacks(path).isEmpty()
    val canRemove = canWrite && "remove" in schema.commands
    val canCreate = canWrite && if (backend.commandNames) "add" in schema.commands || ready else ready
    val canRename = canWrite && if (backend.commandNames) "set" in schema.commands else ready
    fun changeable(entry: Entry) = entry.id != null && entry.kind != FileKind.Disk && entry.kind != FileKind.Mount && !(entry.directory && entry.parent.isEmpty() && !routerOs)

    fun rowOf(target: String?) = rows.firstOrNull { it.entry.path == target }
    fun pick(target: String) {
        state.selected = setOf(target)
        state.anchor = target
    }
    fun toggle(row: TreeRow) {
        if (!row.entry.directory || text.isNotEmpty()) return
        if (row.open) expanded -= row.entry.path else expanded += row.entry.path
    }
    fun expandBelow(folder: String) {
        expanded += folder
        expanded.addAll(tree.foldersBelow(folder))
    }
    fun reveal(target: String) {
        var parent = target.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            expanded += parent
            parent = parent.substringBeforeLast('/', "")
        }
        if (root.isNotEmpty() && target != root && !target.startsWith("$root/")) go("")
        if (text.isNotEmpty()) query.clearText()
        val entry = tree.entry(target)
        if (entry?.directory == true) expanded += target
        if (entry != null) pick(target)
    }
    fun moved(from: String, to: String) {
        val inside = expanded.filter { it == from || it.startsWith("$from/") }
        expanded.removeAll(inside.toSet())
        expanded.addAll(inside.map { to + it.removePrefix(from) })
    }
    fun forget(folders: List<Entry>) {
        expanded.removeAll(expanded.filter { folded -> folders.any { it.directory && (folded == it.path || folded.startsWith(it.path + "/")) } }.toSet())
    }

    fun open(row: TreeRow) {
        val entry = row.entry
        if (entry.directory) {
            toggle(row)
        } else {
            onOpenWindow(MenuEntry(entry.name, window = FILE_WINDOW, arguments = mapOf("name" to entry.path, "id" to entry.id.orEmpty(), "size" to entry.size?.toString().orEmpty())))
        }
    }

    fun target(): String {
        val one = chosen.singleOrNull()
        return when {
            one == null -> root
            one.directory -> one.path
            else -> one.parent
        }
    }
    fun landing() = target().ifEmpty { pack?.uploads.orEmpty() }
    fun upload(files: List<LocalFile>, into: String) {
        if (into.isNotEmpty()) expanded += into
        for (file in files) {
            transfers.add(file.name, true) {
                val bytes = file.read()
                backend.write(listOf(into, file.name).filter { it.isNotEmpty() }.joinToString("/"), bytes)
                bytes.size.toLong()
            }
        }
    }
    var sending by remember { mutableStateOf<Pair<List<LocalFile>, String>?>(null) }
    // the router installs *.auto.npk and reboots, and runs *.auto.rsc, as soon as the file lands
    fun send(files: List<LocalFile>, into: String) {
        if (files.any { it.name.endsWith(".auto.npk") || it.name.endsWith(".auto.rsc") }) sending = files to into else upload(files, into)
    }
    fun download(files: List<Entry>) {
        for (file in files.filter { !it.directory }) {
            transfers.add(file.name, false) {
                val bytes = backend.read(file)
                if (!exchange.save(file.name, bytes)) throw CancellationException("file save cancelled")
                bytes.size.toLong()
            }
        }
    }
    fun dragOut(): DragAndDropTransferData? {
        val files = chosen.filter { !it.directory }.takeIf { it.isNotEmpty() } ?: return null
        return filesOut(files.map { it.path }) { file -> backend.channel { it.read("/$file") } }
    }

    var renaming by remember { mutableStateOf<Pair<Entry, TextFieldState>?>(null) }
    var creating by remember { mutableStateOf<Making?>(null) }
    var deleting by remember { mutableStateOf<List<Entry>?>(null) }
    fun beginRename() {
        chosen.singleOrNull()?.takeIf { canRename && changeable(it) }?.let { renaming = it to TextFieldState(it.name) }
    }
    fun beginDelete() {
        val doomed = chosen.filter(::changeable).filter { entry -> chosen.none { it.directory && changeable(it) && entry.path.startsWith(it.path + "/") } }
        if (canRemove && doomed.isNotEmpty()) deleting = doomed
    }
    fun beginNew(kind: NewKind) {
        if (canCreate) creating = Making(kind, target(), TextFieldState())
    }
    fun beginPath() {
        editing = TextFieldState("/$root")
    }
    fun copyPaths(files: List<Entry>, names: Boolean = false) {
        scope.launch { clipboard.writeText(files.joinToString("\n") { if (names) it.name else "/" + it.path }) }
    }

    val actions = pack?.actions.orEmpty().filter { canWrite && it.command in schema.commands }
    var confirming by remember { mutableStateOf<Pair<PackAction, List<String>>?>(null) }
    var output by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun run(action: PackAction) {
        val ids = chosen.map { it.id ?: it.path }
        val property = action.output
        when {
            action.confirm -> confirming = action to ids
            property != null -> guarded { output = action.title to session.output(path, action, ids, property) }
            else -> guarded { for (arguments in action.runs(ids)) session.run(path, action.command, arguments).collect() }
        }
    }
    fun available(action: PackAction) = !action.selection || chosen.isNotEmpty()

    fun stepIn(row: TreeRow) {
        when {
            !row.entry.directory -> {}
            !row.open -> toggle(row)
            else -> rows.getOrNull(rows.indexOf(row) + 1)?.takeIf { it.depth > row.depth }?.let { pick(it.entry.path) }
        }
    }
    fun stepOut(row: TreeRow) {
        if (row.open && text.isEmpty()) toggle(row) else rowOf(row.entry.parent)?.let { pick(it.entry.path) }
    }
    fun onKey(event: KeyEvent): Boolean {
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        val row = rowOf(state.anchor)
        when {
            event.key == Key.F2 -> beginRename()
            event.key == Key.Delete -> beginDelete()
            event.isAltPressed && event.key == Key.DirectionLeft -> goBack()
            event.isAltPressed && event.key == Key.DirectionRight -> goForward()
            event.key == Key.DirectionRight -> if (row != null) stepIn(row)
            event.key == Key.DirectionLeft -> if (row != null) stepOut(row)
            (event.key == Key.Enter || event.key == Key.NumPadEnter) && row != null -> open(row)
            event.utf16CodePoint == '*'.code || event.key == Key.NumPadMultiply -> if (row != null && row.entry.directory) expandBelow(row.entry.path)
            event.key == Key.Backspace -> {
                val parent = rowOf(row?.entry?.parent)
                if (parent != null) pick(parent.entry.path) else up()
            }
            event.key == Key.F5 -> refresh++
            ctrl && event.key == Key.L -> beginPath()
            ctrl && event.key == Key.F -> find.requestFocus()
            ctrl && event.key == Key.C && chosen.isNotEmpty() -> copyPaths(chosen)
            ctrl && event.isShiftPressed && event.key == Key.N -> beginNew(NewKind.Folder)
            ctrl && event.key == Key.N -> beginNew(NewKind.File)
            else -> return false
        }
        return true
    }

    val space by remember(session) {
        val resource = MenuPath.parse(if (session.device.platform == Platform.OpenWrt) "status/resource" else "system/resource")
        session.watchSettings(resource, listOf("free-hdd-space", "total-hdd-space")).catch { }
    }.collectAsState(null)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(palette.raised).then(if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(Lucide.ArrowLeft, stringResource(Res.string.files_back), ::goBack, Modifier.testTag("folder-back"), enabled = back.isNotEmpty())
                IconButton(Lucide.ArrowRight, stringResource(Res.string.files_forward), ::goForward, Modifier.testTag("folder-forward"), enabled = forward.isNotEmpty())
                IconButton(Lucide.ArrowUp, stringResource(Res.string.files_up), ::up, Modifier.testTag("folder-up"), enabled = root.isNotEmpty())
                IconButton(Lucide.RefreshCw, stringResource(Res.string.files_refresh), { refresh++ }, Modifier.testTag("files-refresh"))
                IconButton(Lucide.ChevronsUpDown, stringResource(Res.string.files_expand_all), { expanded.addAll(tree.folders) }, Modifier.testTag("files-expand-all"))
                IconButton(Lucide.ChevronsDownUp, stringResource(Res.string.files_collapse_all), { expanded.clear() }, Modifier.testTag("files-collapse-all"))
                Breadcrumbs(
                    root,
                    editing,
                    typing,
                    onGo = ::go,
                    onEdit = ::beginPath,
                    onCommit = { typed ->
                        val target = typed.trim().trim('/')
                        editing = null
                        when {
                            target.isEmpty() -> go("")
                            tree.entry(target) != null -> reveal(target)
                            remote -> guarded {
                                backend.list(target)
                                reveal(target)
                            }
                            else -> guarded { throw DeviceException(ErrorCategory.MissingItem, getString(Res.string.files_no_folder, "/$target")) }
                        }
                    },
                    onCancel = { editing = null },
                    modifier = Modifier.weight(1f).widthIn(min = if (compact) 160.dp else 0.dp),
                )
                TextField(query, Modifier.width(if (compact) 140.dp else 200.dp).focusRequester(find).testTag("find"), placeholder = stringResource(Res.string.list_find))
            }
            Row(
                Modifier.fillMaxWidth().background(palette.raised).then(if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canCreate) {
                    val folderLabel = stringResource(Res.string.files_new_folder)
                    val fileLabel = stringResource(Res.string.files_new_file)
                    ActionsMenu(stringResource(Res.string.files_new), Lucide.FilePlus, NewKind.entries, { true }, { if (it == NewKind.Folder) folderLabel else fileLabel }, Modifier.testTag("files-new"), iconOnly = compact, onRun = ::beginNew)
                }
                if (canWrite && ready) ToolbarButton(stringResource(Res.string.files_upload), Lucide.Upload, { guarded { send(exchange.pick(), landing()) } }, Modifier.testTag("files-upload"), iconOnly = compact)
                if (routerOs || ready) ToolbarButton(stringResource(Res.string.files_download), Lucide.Download, { download(chosen) }, Modifier.testTag("files-download"), enabled = chosen.any { !it.directory && backend.canRead(it) }, iconOnly = compact)
                if (ready) {
                    if (exchange.folders) {
                        if (canWrite) ToolbarButton(stringResource(Res.string.files_upload_folder), Lucide.FolderUp, { guarded { exchange.pickFolder()?.let { send(it.files, landing()) } } }, iconOnly = compact)
                        ToolbarButton(stringResource(Res.string.files_download_folder), Lucide.FolderDown, {
                            guarded {
                                val destination = exchange.pickFolder() ?: return@guarded
                                for (file in chosen.filter { !it.directory }) destination.write(file.name, backend.read(file))
                            }
                        }, enabled = chosen.any { !it.directory }, iconOnly = compact)
                    }
                }
                if (canRename) ToolbarButton(stringResource(Res.string.files_rename), Lucide.Pencil, ::beginRename, Modifier.testTag("files-rename"), enabled = chosen.size == 1 && changeable(chosen[0]), iconOnly = compact)
                if (canRemove) ToolbarButton(stringResource(Res.string.files_delete), Lucide.Trash2, ::beginDelete, Modifier.testTag("files-delete"), enabled = chosen.any(::changeable), iconOnly = compact)
                when {
                    actions.isEmpty() -> {}
                    actions.size == 1 -> ToolbarButton(actions[0].title, Lucide.Zap, { run(actions[0]) }, enabled = available(actions[0]), iconOnly = compact)
                    else -> ActionsMenu(stringResource(Res.string.form_actions), Lucide.Zap, actions, ::available, { it.title }, iconOnly = compact, onRun = ::run)
                }
                val links = present(session, pack?.links.orEmpty())
                if (links.isNotEmpty()) {
                    ActionsMenu(stringResource(Res.string.form_configuration), Lucide.Settings, links, { true }, { it.title }, iconOnly = compact) { link ->
                        onOpenWindow(MenuEntry(link.title, link.path, window = link.window))
                    }
                }
                if (session.capabilities.export) {
                    ToolbarButton(stringResource(Res.string.files_export), Lucide.Save, { guarded { exchange.save(session.device.identity + ".rsc", session.export().encodeToByteArray()) } }, iconOnly = compact)
                }
            }
            (bulk as? Bulk.Missing)?.let { BulkNote(session, backend, it.why, it.port, onProblem = { message -> problem = message }) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                TreeTable(
                    rows = rows,
                    state = state,
                    highlight = text,
                    loading = loading,
                    dropEnabled = ready && canWrite,
                    held = held,
                    onHold = { folder, entered -> held = if (entered) folder else held?.takeIf { it != folder } },
                    onToggle = ::toggle,
                    onOpen = ::open,
                    onKey = ::onKey,
                    onDropOnto = { row, files -> send(files, row.entry.path) },
                    dragOut = if (ready && filesDragOut) ::dragOut else null,
                    modifier = Modifier.fillMaxSize().then(if (ready && canWrite) Modifier.fileDrop({ send(it, landing()) }) { dropping = it } else Modifier),
                    menu = { close ->
                        fun act(action: () -> Unit) {
                            close()
                            action()
                        }
                        val single = chosenRows.singleOrNull()
                        if (single != null) {
                            if (!single.entry.directory) {
                                FileMenuLine(stringResource(Res.string.files_open), "Enter") { act { open(single) } }
                            } else {
                                if (text.isEmpty()) {
                                    FileMenuLine(stringResource(if (single.open) Res.string.files_collapse else Res.string.files_expand), "Enter") { act { toggle(single) } }
                                    FileMenuLine(stringResource(Res.string.files_expand_below), "*") { act { expandBelow(single.entry.path) } }
                                }
                                FileMenuLine(stringResource(Res.string.files_only_folder), "") { act { go(single.entry.path) } }
                            }
                        }
                        if (chosen.any { !it.directory && backend.canRead(it) }) FileMenuLine(stringResource(Res.string.files_download), "") { act { download(chosen) } }
                        if (canRename && single != null && changeable(single.entry)) FileMenuLine(stringResource(Res.string.files_rename), "F2") { act(::beginRename) }
                        if (canRemove && chosen.any(::changeable)) FileMenuLine(stringResource(Res.string.files_delete), "Del") { act(::beginDelete) }
                        if (chosen.isNotEmpty()) {
                            FileMenuLine(stringResource(Res.string.files_copy_path), "Ctrl+C") { act { copyPaths(chosen) } }
                            FileMenuLine(stringResource(Res.string.files_copy_name), "") { act { copyPaths(chosen, names = true) } }
                        }
                        if (canCreate) {
                            FileMenuLine(stringResource(Res.string.files_new_folder), "Ctrl+Shift+N") { act { beginNew(NewKind.Folder) } }
                            FileMenuLine(stringResource(Res.string.files_new_file), "Ctrl+N") { act { beginNew(NewKind.File) } }
                        }
                        if (single != null && routerOs && single.entry.id != null) FileMenuLine(stringResource(Res.string.files_properties), "") { act { onOpenItem(tab.title, path, single.entry.id, null, emptyMap(), emptyList()) } }
                        if (single != null && single.entry.name.endsWith(".rsc") && routerOs && canWrite) FileMenuLine(stringResource(Res.string.file_import), "") { act { open(single) } }
                        for (action in actions.filter(::available)) FileMenuLine(action.title, "") { act { run(action) } }
                    },
                )
                val empty = when {
                    reading -> stringResource(Res.string.window_loading)
                    rows.isEmpty() && text.isNotEmpty() -> stringResource(Res.string.files_filtered_empty)
                    rows.isEmpty() -> stringResource(Res.string.files_empty)
                    else -> null
                }
                empty?.let { Text(it, Modifier.align(Alignment.Center).padding(top = 24.dp).testTag("files-empty"), color = palette.muted) }
                if (dropping) {
                    Box(
                        Modifier.fillMaxSize().background(palette.accent.copy(alpha = 0.12f)).border(2.dp, palette.accent, RoundedCornerShape(Theme.metrics.radius)).testTag("files-drop"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(Res.string.files_drop, "/" + (held ?: landing())), style = Theme.type.strong, color = palette.accent)
                    }
                }
            }
            if (transfers.list.isNotEmpty()) TransferPanel(transfers)
            problem?.let { Text(it, Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 2.dp).testTag("files-problem"), style = Theme.type.small, color = palette.danger) }
            chosenRows.singleOrNull()?.let { Details(it, compact) }
            DisableSelection {
                Row(
                    Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val total = tree.countIn(root)
                    val counted = when {
                        reading -> stringResource(Res.string.window_loading)
                        text.isNotEmpty() -> pluralStringResource(Res.plurals.list_items_of, total, rows.count { it.entry.name.contains(text, ignoreCase = true) }, total)
                        else -> pluralStringResource(Res.plurals.list_items, total, total)
                    }
                    CountPill(counted)
                    if (chosen.isNotEmpty()) {
                        Text(stringResource(Res.string.list_selected, chosen.size), style = Theme.type.small, color = palette.muted)
                        val weight = chosen.filter { !it.directory }.sumOf { it.size ?: 0L }
                        if (weight > 0) Text(stringResource(Res.string.files_selected_size, sizeText(weight)), style = Theme.type.small, color = palette.muted)
                    }
                    ((bulk as? Bulk.Ready)?.via ?: if (routerOs) FileVia.Api else null)?.let { Text(viaText(it), Modifier.testTag("files-via"), style = Theme.type.small, color = palette.muted) }
                    space?.let { values ->
                        val free = values["free-hdd-space"]
                        val whole = values["total-hdd-space"]
                        val left = free?.toLongOrNull()
                        val size = whole?.toLongOrNull()?.takeIf { it > 0 }
                        if (left != null && size != null) {
                            Text(stringResource(Res.string.files_space, bytes((size - left).toString()), bytes(size.toString())), Modifier.testTag("files-space"), style = Theme.type.small, color = palette.muted)
                            Text(stringResource(Res.string.files_space_free, "${left * 100 / size} %"), style = Theme.type.small, color = palette.muted)
                        }
                    }
                }
            }
        }
        sending?.let { (files, into) ->
            Confirm(stringResource(Res.string.files_auto_question, files.joinToString(", ") { it.name }), stringResource(Res.string.files_send), onCancel = { sending = null }) {
                sending = null
                upload(files, into)
            }
        }
        renaming?.let { (entry, field) ->
            Confirm(stringResource(Res.string.files_rename), stringResource(Res.string.files_rename), onCancel = { renaming = null }, content = {
                TextField(field, Modifier.width(320.dp).testTag("files-name"), mono = true)
            }) {
                val new = field.text.toString().trim()
                val destination = listOf(entry.parent, new).filter { it.isNotEmpty() }.joinToString("/")
                renaming = null
                when {
                    new.isEmpty() || new == entry.name -> {}
                    '/' in new -> guarded { throw DeviceException(ErrorCategory.General, getString(Res.string.files_bad_name)) }
                    tree.entry(destination) != null -> guarded { throw DeviceException(ErrorCategory.General, getString(Res.string.files_name_taken, new)) }
                    else -> guarded {
                        backend.rename(entry, destination)
                        moved(entry.path, destination)
                        refresh++
                    }
                }
            }
        }
        creating?.let { making ->
            Confirm(
                stringResource(if (making.kind == NewKind.Folder) Res.string.files_new_folder else Res.string.files_new_file),
                stringResource(Res.string.files_new),
                onCancel = { creating = null },
                content = { TextField(making.field, Modifier.width(320.dp).testTag("files-name"), placeholder = stringResource(Res.string.files_name_hint), mono = true) },
            ) {
                val new = making.field.text.toString().trim()
                val destination = listOf(making.into, new).filter { it.isNotEmpty() }.joinToString("/")
                creating = null
                when {
                    new.isEmpty() -> {}
                    '/' in new -> guarded { throw DeviceException(ErrorCategory.General, getString(Res.string.files_bad_name)) }
                    tree.entry(destination) != null -> guarded { throw DeviceException(ErrorCategory.General, getString(Res.string.files_name_taken, new)) }
                    else -> guarded {
                        if (making.kind == NewKind.Folder) backend.makeFolder(destination) else backend.makeFile(destination)
                        if (making.into.isNotEmpty()) expanded += making.into
                        refresh++
                    }
                }
            }
        }
        deleting?.let { doomed ->
            Confirm(
                pluralStringResource(Res.plurals.files_delete_question, doomed.size, doomed.size),
                stringResource(Res.string.files_delete),
                onCancel = { deleting = null },
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (entry in doomed.take(6)) Text("/" + entry.path, color = palette.muted, singleLine = true, overflow = TextOverflow.Ellipsis, style = Theme.type.mono)
                        if (doomed.size > 6) Text("…", color = palette.muted)
                        if (doomed.any { it.directory }) Text(stringResource(Res.string.files_delete_folders), color = palette.warning, style = Theme.type.small)
                    }
                },
            ) {
                deleting = null
                guarded {
                    backend.delete(doomed, all)
                    forget(doomed)
                    state.selected = emptySet()
                    refresh++
                }
            }
        }
        confirming?.let { (action, ids) ->
            Confirm(stringResource(Res.string.command_question, action.title), action.title, onCancel = { confirming = null }) {
                confirming = null
                guarded { for (arguments in action.runs(ids)) session.run(path, action.command, arguments).collect() }
            }
        }
        output?.let { (heading, reply) -> Output(heading, reply) { output = null } }
    }
}

@Composable
private fun FileMenuLine(title: String, keys: String, onClick: () -> Unit) {
    DropdownItem(onClick) {
        Row(Modifier.widthIn(min = 220.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Text(keys, Modifier.padding(start = 16.dp), color = Theme.palette.muted, style = Theme.type.small)
        }
    }
}

@Composable
private fun Breadcrumbs(
    folder: String,
    editing: TextFieldState?,
    focus: FocusRequester,
    onGo: (String) -> Unit,
    onEdit: () -> Unit,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    val palette = Theme.palette
    val shape = RoundedCornerShape(Theme.metrics.radius)
    if (editing != null) {
        LaunchedEffect(editing) { focus.requestFocus() }
        TextField(
            editing,
            modifier.focusRequester(focus).testTag("files-path").onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter -> onCommit(editing.text.toString())
                    Key.Escape -> onCancel()
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
            mono = true,
            placeholder = stringResource(Res.string.files_path),
        )
        return
    }
    val scroll = rememberScrollState()
    LaunchedEffect(folder) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier.height(Theme.metrics.control).clip(shape).background(palette.surface).border(1.dp, palette.line, shape)
            .clickable(onClick = onEdit).hand().horizontalScroll(scroll).padding(horizontal = 6.dp).testTag("files-crumbs"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val crumbs = crumbs(folder)
        crumbs.forEachIndexed { index, (title, target) ->
            if (index > 0) UnstyledIcon(Lucide.ChevronRight, null, Modifier.size(12.dp), tint = palette.faint)
            val last = index == crumbs.lastIndex
            Row(Modifier.clickable { onGo(target) }.hand().padding(horizontal = 3.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (index == 0) {
                    UnstyledIcon(Lucide.HardDrive, null, Modifier.size(14.dp), tint = if (last) palette.text else palette.muted)
                    Text("/", Modifier.padding(start = 4.dp), color = if (last) palette.text else palette.muted, style = Theme.type.strong)
                } else {
                    Text(title, color = if (last) palette.text else palette.muted, style = if (last) Theme.type.strong else Theme.type.body, singleLine = true)
                }
            }
        }
        Spacer(Modifier.width(24.dp))
    }
}

@Composable
private fun Details(row: TreeRow, compact: Boolean) {
    if (compact) return
    val palette = Theme.palette
    val entry = row.entry
    Row(
        Modifier.fillMaxWidth().background(palette.surface).border(1.dp, palette.line).padding(horizontal = 8.dp, vertical = 4.dp).testTag("files-details"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = entry.kind.icon(row.open)
        UnstyledIcon(icon.vector, null, Modifier.size(Theme.metrics.icon), tint = icon.tint?.let { palette.tint(it) } ?: palette.text)
        Text(entry.name, Modifier.widthIn(max = 280.dp), style = Theme.type.strong, singleLine = true, overflow = TextOverflow.Ellipsis)
        if (entry.directory) {
            row.count?.let { count ->
                val items = pluralStringResource(Res.plurals.list_items, count, count)
                Text(if (row.size != null && row.size > 0) "$items, ${sizeText(row.size)}" else items, color = palette.muted, singleLine = true)
            }
        } else if (entry.size != null) {
            Text("${label("size")} ${sizeText(entry.size)}" + if (entry.size >= WHOLE_BYTES) " (${entry.size} B)" else "", color = palette.muted, singleLine = true)
        }
        if (entry.modified.isNotEmpty()) Text("${label("last-modified")} ${entry.modified}", color = palette.muted, singleLine = true)
        if (entry.type.isNotEmpty()) Text(entry.type, color = palette.muted, singleLine = true)
        Text("/" + entry.path, Modifier.weight(1f), color = palette.faint, singleLine = true, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
    }
}
