package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.AppWindow
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Box
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.GitCompare
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.List
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.LockOpen
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Redo
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.Undo
import com.composables.icons.lucide.Wrench
import com.composables.icons.lucide.X
import com.composeunstyled.AnchorAlignment
import com.composeunstyled.AnchorSide
import com.composeunstyled.Text
import com.composeunstyled.UnstyledDropdownMenu
import com.composeunstyled.UnstyledIcon
import com.vasmarfas.networkhelm.app.LocalSettings
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.model.closedBy
import com.vasmarfas.networkhelm.model.lacks
import com.vasmarfas.networkhelm.offline.openConfig
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.routeros.schema.label
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.transport.tcpListening
import com.vasmarfas.networkhelm.ui.files.FileWindow
import com.vasmarfas.networkhelm.ui.files.LocalFileExchange
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.DropdownItem
import com.vasmarfas.networkhelm.ui.kit.Hint
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.MenuPanel
import com.vasmarfas.networkhelm.ui.kit.ScrollArea
import com.vasmarfas.networkhelm.ui.kit.Select
import com.vasmarfas.networkhelm.ui.kit.TabStrip
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.kit.ToolbarButton
import com.vasmarfas.networkhelm.ui.kit.hand
import com.vasmarfas.networkhelm.ui.log.LocalLogWindows
import com.vasmarfas.networkhelm.ui.log.LogEntryView
import com.vasmarfas.networkhelm.ui.table.marked
import com.vasmarfas.networkhelm.ui.terminal.LocalTerminal
import com.vasmarfas.networkhelm.ui.terminal.TelnetView
import com.vasmarfas.networkhelm.ui.terminal.TerminalView
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.theme.ThemeIcon
import com.vasmarfas.networkhelm.ui.theme.on
import com.vasmarfas.networkhelm.ui.theme.tintOf
import com.vasmarfas.networkhelm.ui.workspace.WindowState
import com.vasmarfas.networkhelm.ui.workspace.Workspace
import com.vasmarfas.networkhelm.update.Release
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.apply_held
import networkhelm.app.shared.generated.resources.compare_file
import networkhelm.app.shared.generated.resources.compare_platforms
import networkhelm.app.shared.generated.resources.compare_title
import networkhelm.app.shared.generated.resources.compare_window
import networkhelm.app.shared.generated.resources.error_not_config
import networkhelm.app.shared.generated.resources.link_closed
import networkhelm.app.shared.generated.resources.link_lost
import networkhelm.app.shared.generated.resources.main_about
import networkhelm.app.shared.generated.resources.main_apply
import networkhelm.app.shared.generated.resources.main_back
import networkhelm.app.shared.generated.resources.main_close_all
import networkhelm.app.shared.generated.resources.main_close_all_windows
import networkhelm.app.shared.generated.resources.main_disconnect
import networkhelm.app.shared.generated.resources.main_journal
import networkhelm.app.shared.generated.resources.main_more
import networkhelm.app.shared.generated.resources.main_offline
import networkhelm.app.shared.generated.resources.main_redo
import networkhelm.app.shared.generated.resources.main_reload_structure
import networkhelm.app.shared.generated.resources.main_safe_mode
import networkhelm.app.shared.generated.resources.main_search
import networkhelm.app.shared.generated.resources.main_undo
import networkhelm.app.shared.generated.resources.main_unencrypted
import networkhelm.app.shared.generated.resources.main_view_native
import networkhelm.app.shared.generated.resources.main_view_native_hint
import networkhelm.app.shared.generated.resources.main_view_routeros
import networkhelm.app.shared.generated.resources.main_view_routeros_hint
import networkhelm.app.shared.generated.resources.main_view_winbox
import networkhelm.app.shared.generated.resources.main_view_winbox_hint
import networkhelm.app.shared.generated.resources.main_windows
import networkhelm.app.shared.generated.resources.menu_locked_device_mode
import networkhelm.app.shared.generated.resources.menu_locked_policy
import networkhelm.app.shared.generated.resources.prepare_title
import networkhelm.app.shared.generated.resources.safe_held
import networkhelm.app.shared.generated.resources.safe_hint_reboot
import networkhelm.app.shared.generated.resources.safe_hint_rollback
import networkhelm.app.shared.generated.resources.safe_keep
import networkhelm.app.shared.generated.resources.safe_leave_question
import networkhelm.app.shared.generated.resources.safe_rollback
import networkhelm.app.shared.generated.resources.safe_stranded
import networkhelm.app.shared.generated.resources.search_title
import networkhelm.app.shared.generated.resources.settings_close
import networkhelm.app.shared.generated.resources.settings_title
import networkhelm.app.shared.generated.resources.tools_title
import networkhelm.app.shared.generated.resources.update_app
import networkhelm.app.shared.generated.resources.update_router
import networkhelm.app.shared.generated.resources.update_router_changes
import networkhelm.app.shared.generated.resources.update_router_installed
import networkhelm.app.shared.generated.resources.update_router_title
import networkhelm.app.shared.generated.resources.update_router_update
import networkhelm.app.shared.generated.resources.window_loading
import networkhelm.app.shared.generated.resources.workspace_create
import networkhelm.app.shared.generated.resources.workspace_main
import networkhelm.app.shared.generated.resources.workspace_copy
import networkhelm.app.shared.generated.resources.workspace_name
import networkhelm.app.shared.generated.resources.workspace_new
import networkhelm.app.shared.generated.resources.workspace_new_name
import networkhelm.app.shared.generated.resources.workspace_remove
import networkhelm.app.shared.generated.resources.workspace_rename
import networkhelm.app.shared.generated.resources.workspace_rename_action

@Composable
fun MainScreen(
    tab: RouterTab,
    onDisconnect: () -> Unit,
    onDetach: ((WindowState) -> Unit)? = null,
    onAbout: () -> Unit = {},
    onSettings: () -> Unit = {},
    onTools: () -> Unit = {},
    onSecure: (suspend () -> Unit)? = null,
    onSearch: () -> Unit = {},
    others: List<RouterTab> = emptyList(),
    release: Release? = null,
    leaves: Boolean = false,
    onStay: () -> Unit = {},
) {
    val palette = Theme.palette
    LaunchedEffect(tab) { tab.openMenu() }
    LaunchedEffect(tab) { tab.keepLayout() }
    LaunchedEffect(tab) { tab.session.ssh?.reach() }
    LaunchedEffect(tab, tab.updating) {
        tab.checkRouterUpdate()
        while (tab.updating) {
            delay(5_000)
            tab.checkRouterUpdate()
        }
    }
    val link by remember(tab) { tab.session.link }.collectAsState(LinkState.Up)
    val safeMode = tab.session.safeMode
    // the mode of a session behind the gateway fails with the gateway, which the banner of the link tells of
    val safe by remember(tab) { safeMode?.active?.catch { emit(false) } ?: flowOf(false) }.collectAsState(false)
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(leaves) { if (leaves) leaving = true }
    var problem by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf<Pair<Naming, TextFieldState>?>(null) }
    val scope = rememberCoroutineScope()

    // Safe Mode rolls the changes back when the session ends, so leaving with it on asks first
    fun leave(how: suspend (SafeMode) -> Unit) {
        leaving = false
        scope.launch {
            try {
                safeMode?.let { how(it) }
                onDisconnect()
            } catch (e: DeviceException) {
                problem = e.message
                onStay()
            }
        }
    }

    val exchange = LocalFileExchange.current
    fun compare(other: RouterTab?) {
        problem = null
        scope.launch {
            val platform = tab.session.device.platform
            val name: String
            val before: suspend () -> String
            if (other != null) {
                name = other.title
                before = { other.session.export() }
            } else {
                val file = exchange.pick().firstOrNull() ?: return@launch
                val opened = try {
                    withContext(Dispatchers.Default) { openConfig(file.name, file.read()) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // a file the system does not give is told in the words of the system
                    problem = e.message ?: e.toString()
                    return@launch
                }
                if (opened == null || opened.device.platform != platform) {
                    problem = getString(if (opened == null) Res.string.error_not_config else Res.string.compare_platforms, file.name)
                    return@launch
                }
                name = file.name
                before = { opened.export() }
            }
            tab.openComparison(getString(Res.string.compare_window, name, tab.title)).run(platform, before) { tab.session.export() }
        }
    }

    val search = remember { FocusRequester() }
    val compact = LocalCompact.current
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || event.key != Key.F) return@onPreviewKeyEvent false
            // AltGr comes as Ctrl with Alt, and with it the key is a character of the layout
            when {
                (event.isCtrlPressed && !event.isAltPressed || event.isMetaPressed) && event.isShiftPressed -> onSearch()
                event.isAltPressed && !event.isCtrlPressed && !compact -> search.requestFocus()
                else -> return@onPreviewKeyEvent false
            }
            true
        },
    ) {
        Column(Modifier.fillMaxSize().background(palette.workspace)) {
            Toolbar(tab, problem, { problem = it }, onAbout, onSettings, onTools, onSearch, others, release, ::compare, { naming = it to TextFieldState(if (it == Naming.Rename) tab.workspaceName else "") }) { if (safe) leaving = true else onDisconnect() }
            if (link != LinkState.Up) {
                Text(
                    stringResource(if (link == LinkState.Reconnecting) Res.string.link_lost else Res.string.link_closed),
                    Modifier.fillMaxWidth().background(palette.danger).padding(horizontal = 12.dp, vertical = 4.dp),
                    color = palette.on(palette.danger),
                )
            }
            safeMode?.let { held -> Stranded(held) { problem = it } }
            if (LocalCompact.current) {
                Phone(tab, onSecure, Modifier.weight(1f).fillMaxWidth())
            } else {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LeftMenu(tab, search, Modifier.width(200.dp).fillMaxHeight())
                    val titleTabs: @Composable (WindowState, Color) -> Unit = { window, colour ->
                        tab.specs[window.id]?.takeIf { it.selector || it.tabs.size > 1 }?.let { spec ->
                            if (spec.selector) Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) { ModeSelect(spec) }
                            else TabStrip(spec.tabs.map { it.title }, spec.selected, { spec.selected = it }, Modifier.fillMaxHeight(), titleText = colour)
                        }
                    }
                    Workspace(
                        tab.workspace,
                        Modifier.weight(1f).fillMaxHeight(),
                        onDetach,
                        detachable = { it.id in tab.specs },
                        inTitle = titleTabs.takeUnless { Theme.classic },
                    ) { window -> WindowContent(tab, window, onSecure) }
                }
            }
            if (LocalSettings.current.resourceBar && WindowInsets.ime.getBottom(LocalDensity.current) == 0) ResourceBar(tab)
        }
        if (leaving) {
            Confirm(
                stringResource(Res.string.safe_leave_question),
                stringResource(Res.string.safe_keep),
                onCancel = {
                    leaving = false
                    onStay()
                },
                alternative = stringResource(Res.string.safe_rollback) to { leave { it.rollback() } },
            ) { leave { it.commit() } }
        }
        naming?.let { (purpose, name) ->
            val chosen = name.text.toString().trim()
            val taken = purpose != Naming.New && chosen in tab.workspaceNames
            Confirm(
                stringResource(if (purpose == Naming.Rename) Res.string.workspace_new_name else Res.string.workspace_name),
                stringResource(if (purpose == Naming.Rename) Res.string.workspace_rename_action else Res.string.workspace_create),
                onCancel = { naming = null },
                content = { TextField(name, Modifier.fillMaxWidth().testTag("workspace-name"), error = taken) },
                typing = true,
            ) {
                naming = null
                if (chosen.isNotEmpty() && !taken) {
                    scope.launch {
                        when (purpose) {
                            Naming.New -> tab.showWorkspace(chosen)
                            Naming.Copy -> tab.copyWorkspace(chosen)
                            Naming.Rename -> tab.renameWorkspace(chosen)
                        }
                    }
                }
            }
        }
    }
}

private enum class Naming { New, Copy, Rename }

@Composable
private fun WindowContent(tab: RouterTab, window: WindowState, onSecure: (suspend () -> Unit)?) {
    val close = { tab.workspace.close(window) }
    tab.specs[window.id]?.let { spec ->
        LaunchedEffect(spec) { tab.expand(spec) }
        CompositionLocalProvider(LocalLogWindows provides tab.logWindows) {
            PathWindow(tab.session, spec, tab.tables, tab::openItem, tab::open, tabsInTitle = !Theme.classic && !LocalCompact.current, onFit = { tab.workspace.fit(window, it) }, onClose = close)
        }
    }
    tab.items[window.id]?.let { item ->
        ItemForm(tab.session, item, { window.title = it }, close, { values -> tab.openItem(item.title, item.path, null, prefill = values) }, tab::open) {
            tab.workspace.fit(window, it)
        }
    }
    tab.files[window.id]?.let { FileWindow(tab.session, it, window, close) }
    tab.logEntries[window.id]?.let { LogEntryView(it, window, tab.logWindows) }
    if (window.id == tab.journalWindow) JournalView(tab.journal, tab.session.device.platform)
    if (window.id == tab.tunnelsWindow) TunnelsView(tab)
    if (window.id == tab.dashboardWindow) DashboardView(tab)
    if (window.id == tab.topologyWindow) TopologyView(tab)
    tab.comparisons[window.id]?.let { CompareView(tab, it) }
    if (window.id == tab.prepareWindow) PrepareView(tab.session, onSecure)
    if (window.id == tab.dhcpSetupWindow) DhcpSetupView(tab.session)
    if (window.id in tab.terminals) {
        val ssh = tab.session.ssh
        val terminal = LocalTerminal.current
        if (ssh != null && terminal != null) TerminalView(ssh, terminal = terminal)
    }
    if (window.id in tab.telnets) {
        val ssh = tab.session.ssh
        val terminal = LocalTerminal.current
        if (ssh != null && terminal != null) TelnetView(ssh, terminal, tab.telnetCalls[window.id])
    }
}

@Composable
private fun Phone(tab: RouterTab, onSecure: (suspend () -> Unit)?, modifier: Modifier) {
    val front = tab.workspace.windows.lastOrNull()
    if (front == null) {
        LeftMenu(tab, remember { FocusRequester() }, modifier)
        return
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(Theme.metrics.title + 6.dp).background(Theme.palette.raised).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(Lucide.ArrowLeft, stringResource(Res.string.main_back), { tab.workspace.close(front) }, Modifier.testTag("back"))
            Text(front.title, style = Theme.type.strong, singleLine = true, overflow = TextOverflow.Ellipsis)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Theme.palette.surface)) {
            // a phone on its side has less height than the bars of a window, which then scrolls under the bars of the app
            val typing = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            val tall = if (typing) maxHeight else maxOf(maxHeight, WINDOW_HEIGHT)
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().height(tall)) {
                    key(front.id) { WindowContent(tab, front, onSecure) }
                }
            }
        }
    }
}

@Composable
private fun Toolbar(
    tab: RouterTab,
    problem: String?,
    onProblem: (String?) -> Unit,
    onAbout: () -> Unit,
    onSettings: () -> Unit,
    onTools: () -> Unit,
    onSearch: () -> Unit,
    others: List<RouterTab>,
    release: Release?,
    onCompare: (RouterTab?) -> Unit,
    onName: (Naming) -> Unit,
    onDisconnect: () -> Unit,
) {
    val palette = Theme.palette
    val links = LocalUriHandler.current
    var windows by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val compact = LocalCompact.current
    fun act(block: suspend () -> Unit) {
        onProblem(null)
        scope.launch {
            try {
                block()
            } catch (e: DeviceException) {
                onProblem(e.message)
            }
        }
    }
    fun closeAll() = tab.workspace.windows.toList().forEach(tab.workspace::close)
    Row(
        Modifier.fillMaxWidth().height(Theme.metrics.title + 6.dp).background(palette.raised).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        UnstyledDropdownMenu(
            expanded = windows,
            onExpandedChange = { windows = it },
            panel = {
                MenuPanel {
                    for (window in tab.workspace.windows.reversed()) {
                        DropdownItem(onClick = {
                            tab.workspace.bringToFront(window)
                            windows = false
                        }, selected = window == tab.workspace.active) { Text(trail(tab.trail(window), palette.muted), maxLines = 1) }
                    }
                    if (tab.workspace.windows.isNotEmpty()) {
                        DropdownItem(onClick = {
                            closeAll()
                            windows = false
                        }) { Text(stringResource(Res.string.main_close_all), color = palette.muted) }
                    }
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(palette.line))
                    val main = stringResource(Res.string.workspace_main)
                    for (name in listOf("") + tab.workspaceNames) {
                        DropdownItem(onClick = {
                            windows = false
                            act { tab.showWorkspace(name) }
                        }, selected = name == tab.workspaceName) { Text(name.ifEmpty { main }, maxLines = 1) }
                    }
                    DropdownItem(onClick = {
                        windows = false
                        onName(Naming.New)
                    }) { Text(stringResource(Res.string.workspace_new), color = palette.muted) }
                    DropdownItem(onClick = {
                        windows = false
                        onName(Naming.Copy)
                    }) { Text(stringResource(Res.string.workspace_copy), color = palette.muted) }
                    if (tab.workspaceName.isNotEmpty()) {
                        DropdownItem(onClick = {
                            windows = false
                            onName(Naming.Rename)
                        }) { Text(stringResource(Res.string.workspace_rename, tab.workspaceName), color = palette.muted) }
                        DropdownItem(onClick = {
                            windows = false
                            act { tab.removeWorkspace() }
                        }) { Text(stringResource(Res.string.workspace_remove, tab.workspaceName), color = palette.muted) }
                    }
                }
            },
            anchor = {
                val count = tab.workspace.windows.size
                val named = tab.workspaceName.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
                ToolbarButton(if (compact) "$count" else "${stringResource(Res.string.main_windows)} $count$named", Lucide.AppWindow, { windows = true }, Modifier.testTag("windows"))
            },
        )
        // the button keeps its place when the last window goes: a second tap there would otherwise land on Undo, which the router takes at once
        IconButton(Lucide.X, stringResource(Res.string.main_close_all_windows), ::closeAll, Modifier.testTag("close-all"), enabled = tab.workspace.windows.isNotEmpty())
        val canWrite = tab.session.device.canWrite
        if (canWrite && tab.session.device.platform == Platform.RouterOs) {
            val undo = stringResource(Res.string.main_undo)
            val redo = stringResource(Res.string.main_redo)
            IconButton(Lucide.Undo, undo, { act { tab.session.run(MenuPath.Root, "undo", emptyMap()).collect() } }, Modifier.testTag("undo"), hint = { historyHint(tab.session, undo, "undoable", newest = true) })
            IconButton(Lucide.Redo, redo, { act { tab.session.run(MenuPath.Root, "redo", emptyMap()).collect() } }, Modifier.testTag("redo"), hint = { historyHint(tab.session, redo, "redoable", newest = false) })
        }
        tab.session.safeMode?.takeIf { canWrite }?.let { safeMode ->
            val draft = tab.session.capabilities.safeMode == SafeModeKind.ApplyRollback
            val active by remember(safeMode) { safeMode.active.catch { emit(false) } }.collectAsState(false)
            val actions by remember(safeMode, active) { if (active || draft) safeMode.actions.catch { emit(0) } else flowOf(0) }.collectAsState(0)
            val title = stringResource(if (draft) Res.string.main_apply else Res.string.main_safe_mode)
            val held = stringResource(if (draft) Res.string.apply_held else Res.string.safe_held)
            if (active) {
                val keep = stringResource(Res.string.safe_keep)
                ActionsMenu(if (compact) "$actions" else "$title $actions", Lucide.Shield, listOf(keep, stringResource(Res.string.safe_rollback)), { true }, modifier = Modifier.testTag("safe-mode"), active = true) {
                    act { if (it == keep) safeMode.commit() else safeMode.rollback() }
                }
            } else {
                val how = when (tab.session.capabilities.safeMode) {
                    SafeModeKind.CommitConfirm -> stringResource(Res.string.safe_hint_reboot)
                    SafeModeKind.ApiSession, SafeModeKind.SshConsole -> stringResource(Res.string.safe_hint_rollback)
                    else -> null
                }
                Hint(how.orEmpty(), enabled = how != null) {
                    val label = when {
                        draft && compact -> "$actions"
                        draft -> "$title $actions"
                        else -> title
                    }
                    ToolbarButton(label, Lucide.Shield, { act { if (!safeMode.enter()) onProblem(held) } }, Modifier.testTag("safe-mode"), iconOnly = compact && !draft)
                }
            }
        }
        val journal = stringResource(Res.string.main_journal)
        val compare = stringResource(Res.string.compare_title)
        val file = stringResource(Res.string.compare_file)
        if (!compact) {
            ToolbarButton(journal, Lucide.History, { tab.openJournal(journal) })
            if (tab.session.capabilities.export) ActionsMenu(compare, Lucide.GitCompare, others + null, { true }, { it?.title ?: file }, Modifier.testTag("compare"), onRun = onCompare)
        }
        if (!tab.session.capabilities.encrypted) {
            val prepare = stringResource(Res.string.prepare_title)
            val opens = tab.session.device.platform == Platform.RouterOs
            val unencrypted = stringResource(Res.string.main_unencrypted)
            val mark = Modifier.clip(RoundedCornerShape(Theme.metrics.radius)).background(palette.warning)
                .then(if (opens) Modifier.clickable { tab.openPrepare(prepare) }.hand() else Modifier)
            // on a phone the words would push the buttons that end the session off the bar
            if (compact) {
                Hint(unencrypted) { UnstyledIcon(Lucide.LockOpen, unencrypted, mark.padding(4.dp).size(16.dp).testTag("unencrypted"), tint = palette.on(palette.warning)) }
            } else {
                Text(unencrypted, mark.padding(horizontal = 8.dp, vertical = 2.dp).testTag("unencrypted"), color = palette.on(palette.warning), style = Theme.type.small)
            }
        }
        val offline = tab.session.capabilities.offline
        if (offline) {
            Text(
                stringResource(Res.string.main_offline),
                Modifier.clip(RoundedCornerShape(Theme.metrics.radius)).background(palette.selection).padding(horizontal = 8.dp, vertical = 2.dp).testTag("offline"),
                style = Theme.type.small,
            )
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tab.routerUpdate?.let { version -> RouterUpdateChip(tab, version, compact) }
            release?.let { next ->
                UpdateChip("NetworkHelm ${next.version}", stringResource(Res.string.update_app, next.version), palette.accent, compact, Modifier.testTag("app-update")) { links.openUri(next.url) }
            }
            problem?.let { Text(deviceMessage(it), color = palette.danger, style = Theme.type.small, singleLine = true, overflow = TextOverflow.Ellipsis) }
        }
        if (tab.session.device.platform == Platform.OpenWrt) {
            tab.viewProblem?.let { Text(it, color = palette.danger, style = Theme.type.small, singleLine = true, overflow = TextOverflow.Ellipsis) }
            val views = MenuView.entries.filter { it != MenuView.Native || tab.session.capabilities.nativeMenu }
            Select(tab.view, views, { viewLabel(it) }, { view -> scope.launch { tab.changeView(view) } }, Modifier.width(if (compact) 110.dp else 150.dp).testTag("view"), hint = { viewHint(it) })
        }
        val search = stringResource(Res.string.search_title)
        val about = stringResource(Res.string.main_about)
        val settings = stringResource(Res.string.settings_title)
        val tools = stringResource(Res.string.tools_title)
        val reload = stringResource(Res.string.main_reload_structure)
        val reloads = tab.session.device.platform == Platform.RouterOs
        if (compact) {
            val more = buildList<Pair<String, () -> Unit>> {
                add(journal to { tab.openJournal(journal) })
                if (tab.session.capabilities.export) for (other in others + null) add("$compare: ${other?.title ?: file}" to { onCompare(other) })
                if (reloads) add(reload to { act { tab.reloadStructure() } })
                add(search to onSearch)
                add(tools to onTools)
                add(about to onAbout)
                add(settings to onSettings)
            }
            ActionsMenu(stringResource(Res.string.main_more), Lucide.EllipsisVertical, more, { true }, { it.first }, Modifier.testTag("more"), iconOnly = true) { it.second() }
        } else {
            if (reloads) IconButton(Lucide.RefreshCw, reload, { act { tab.reloadStructure() } }, Modifier.testTag("reload-structure"))
            IconButton(Lucide.Search, search, onSearch, Modifier.testTag("router-search"))
            IconButton(Lucide.Wrench, tools, onTools, Modifier.testTag("tools"))
            ToolbarButton(about, Lucide.Info, onAbout, Modifier.testTag("about"))
            ToolbarButton(settings, Lucide.Settings, onSettings, Modifier.testTag("settings"))
        }
        val disconnect = stringResource(if (offline) Res.string.settings_close else Res.string.main_disconnect)
        if (compact) IconButton(Lucide.LogOut, disconnect, onDisconnect, Modifier.testTag("disconnect")) else ToolbarButton(disconnect, Lucide.LogOut, onDisconnect, Modifier.testTag("disconnect"))
    }
}

// the chip opens a card under itself that tells the versions and sends to the window of updates, which downloads nothing by itself
@Composable
private fun RouterUpdateChip(tab: RouterTab, version: String, compact: Boolean) {
    val palette = Theme.palette
    var open by remember { mutableStateOf(false) }
    val go = {
        open = false
        tab.openUpdate()
    }
    UnstyledDropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        panel = {
            MenuPanel {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("router-update-card"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(Res.string.update_router_title, version), style = Theme.type.strong)
                    tab.routerInstalled?.let { Text(stringResource(Res.string.update_router_installed, it), color = palette.muted, style = Theme.type.small) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(Res.string.update_router_update),
                            Modifier.clip(RoundedCornerShape(Theme.metrics.radius)).background(palette.success).clickable(onClick = go).hand().padding(horizontal = 14.dp, vertical = 5.dp).testTag("router-update-open"),
                            color = palette.on(palette.success),
                        )
                        IconButton(Lucide.List, stringResource(Res.string.update_router_changes), go, Modifier.testTag("router-update-changes"))
                    }
                }
            }
        },
        anchor = { UpdateChip("RouterOS $version", stringResource(Res.string.update_router, version), palette.success, compact, Modifier.testTag("router-update")) { open = true } },
    )
}

@Composable
private fun UpdateChip(text: String, hint: String, color: Color, compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Hint(hint) {
        if (compact) {
            ToolbarButton(hint, Lucide.Download, onClick, modifier, iconOnly = true, tint = color)
        } else {
            Text(
                text,
                modifier.clip(RoundedCornerShape(Theme.metrics.radius)).background(color).clickable(onClick = onClick).hand().padding(horizontal = 10.dp, vertical = 3.dp),
                color = Theme.palette.on(color),
                style = Theme.type.small,
                singleLine = true,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LeftMenu(tab: RouterTab, search: FocusRequester, modifier: Modifier) {
    val palette = Theme.palette
    val query = remember { TextFieldState() }
    val text = query.text.toString().trim()
    val band = palette.band?.takeIf { Theme.classic && !LocalCompact.current }
    val terminal = LocalTerminal.current != null && tab.session.ssh != null
    val menu = tab.menu?.filter { it.window != "terminal" || terminal }?.filter { it.window != "tunnels" || tab.session.ssh?.canForward == true && tcpListening }
    val typed = remember(text) { consolePath(text) }
    val deeper by produceState<Pair<String, MenuEntry>?>(null, typed) {
        value = null
        val path = typed ?: return@produceState
        value = try {
            tab.session.outline(path).takeIf { it.kind != NodeKind.Directory }?.let { "/$path" to MenuEntry(label(path.segments.last()), path.toString()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    val results = listOfNotNull(deeper) + if (menu != null && text.length >= 2) found(tab, menu, text) else emptyList()
    var chosen by remember(text) { mutableIntStateOf(0) }
    val browser = LocalUriHandler.current
    val open: (MenuEntry) -> Unit = { entry ->
        val page = entry.page
        if (page != null) browser.openUri(tab.webPage(page)) else tab.open(entry)
    }
    Row(modifier) {
        if (band != null) Band(band)
        Column(Modifier.weight(1f).fillMaxHeight().background(palette.raised).padding(vertical = 6.dp)) {
            TextField(
                query,
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).focusRequester(search).testTag("menu-search").onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> chosen = (chosen + 1).coerceAtMost(results.lastIndex.coerceAtLeast(0))
                        Key.DirectionUp -> chosen = (chosen - 1).coerceAtLeast(0)
                        Key.Enter, Key.NumPadEnter -> open((results.getOrNull(chosen) ?: return@onPreviewKeyEvent false).second)
                        Key.Escape -> if (text.isEmpty()) return@onPreviewKeyEvent false else query.clearText()
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
                // Alt+F reaches the field only beside the workspace, so a narrow window does not name it
                placeholder = stringResource(Res.string.main_search) + if (LocalCompact.current) "" else "  Alt+F",
            )
            Spacer(Modifier.height(6.dp))
            if (menu == null) {
                Text(stringResource(Res.string.window_loading), Modifier.padding(horizontal = 12.dp), color = palette.muted, style = Theme.type.small)
                return@Column
            }
            ScrollArea(Modifier.weight(1f)) {
                if (text.length >= 2) {
                    results.forEachIndexed { index, (title, entry) ->
                        MenuRow(title, false, active = index == chosen, highlight = text, icon = menuIcon(title.substringBefore(" > ")), locked = locked(tab.session.device, entry)) { open(entry) }
                    }
                } else {
                    for (entry in menu) MenuItem(entry, tab.session.device, open)
                }
            }
        }
    }
}

@Composable
private fun Band(colour: Color) {
    Box(Modifier.width(16.dp).fillMaxHeight().background(colour), contentAlignment = Alignment.BottomCenter) {
        Text("NetworkHelm", Modifier.padding(bottom = 10.dp).sideways(), color = Theme.palette.on(colour), style = Theme.type.small, maxLines = 1)
    }
}

private fun Modifier.sideways() = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints())
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(-(placeable.width - placeable.height) / 2, (placeable.width - placeable.height) / 2) { rotationZ = -90f }
    }
}

@Composable
private fun menuIcon(title: String): ThemeIcon = Theme.icons["menu/$title"] ?: ThemeIcon(Lucide.Box, null)

internal fun trail(steps: List<String>, muted: Color): AnnotatedString = buildAnnotatedString {
    steps.forEachIndexed { index, step ->
        if (index > 0) withStyle(SpanStyle(color = muted)) { append(" › ") }
        withStyle(if (index == steps.lastIndex) SpanStyle(fontWeight = FontWeight.SemiBold) else SpanStyle(color = muted)) { append(step) }
    }
}

@Composable
private fun Stranded(safeMode: SafeMode, onProblem: (String?) -> Unit) {
    val stranded by remember(safeMode) { safeMode.stranded.catch { emit(false) } }.collectAsState(false)
    if (!stranded) return
    val palette = Theme.palette
    val scope = rememberCoroutineScope()
    fun settle(keep: Boolean) {
        scope.launch {
            try {
                safeMode.settle(keep)
            } catch (e: DeviceException) {
                onProblem(e.message)
            }
        }
    }
    val banner = Modifier.fillMaxWidth().background(palette.warning).padding(horizontal = 12.dp, vertical = 4.dp).testTag("safe-stranded")
    val buttons: @Composable () -> Unit = {
        Button(stringResource(Res.string.safe_rollback), { settle(false) }, primary = true)
        Button(stringResource(Res.string.safe_keep), { settle(true) })
    }
    // a narrow window has no room for the words beside the two buttons
    if (LocalCompact.current) {
        Column(banner, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.safe_stranded), color = palette.on(palette.warning))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
        }
    } else {
        Row(banner, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.safe_stranded), Modifier.weight(1f), color = palette.on(palette.warning))
            buttons()
        }
    }
}

private val history = MenuPath.parse("system/history")
private val WINDOW_HEIGHT = 360.dp

// print gives the newest record first: undo takes the newest it can undo, redo the oldest it can redo
@Composable
private fun historyHint(session: DeviceSession, name: String, flag: String, newest: Boolean): String {
    val record by produceState<Item?>(null, session) {
        value = try {
            session.watch(history, Filter.Equals(flag, "true"), listOf("action", "by")).filterIsInstance<ListEvent.Snapshot>().first().items
                .let { if (newest) it.firstOrNull() else it.lastOrNull() }
        } catch (e: DeviceException) {
            null
        }
    }
    val known = record ?: return name
    val action = known["action"] ?: return name
    return known["by"]?.let { "$name: $action ($it)" } ?: "$name: $action"
}

private fun consolePath(text: String): MenuPath? {
    if (!text.startsWith("/") && '/' !in text) return null
    return MenuPath.parse(text).takeIf { path -> path.segments.isNotEmpty() && path.segments.all { segment -> segment.all { it.isLetterOrDigit() || it == '-' || it == '.' } } }
}

private fun found(tab: RouterTab, entries: List<MenuEntry>, text: String, prefix: String = ""): List<Pair<String, MenuEntry>> = entries.flatMap { entry ->
    val title = prefix + entry.title
    if (entry.items.isNotEmpty()) return@flatMap found(tab, entry.items, text, "$title > ")
    val tabs = tab.tabs(entry).filter { it.path != entry.path }.map { "$title > ${it.title}" to MenuEntry(entry.title, it.path) }
    val words = text.split(' ', '>').filter { it.isNotEmpty() }
    (listOf(title to entry) + tabs).filter { (place, _) -> words.all { place.contains(it, ignoreCase = true) } }
}

@Composable
private fun locked(device: DeviceInfo, entry: MenuEntry): String? {
    val path = entry.path?.let(MenuPath::parse) ?: return null
    device.closedBy(path)?.let { return stringResource(Res.string.menu_locked_device_mode, it) }
    val lacking = device.lacks(path).ifEmpty { return null }
    return stringResource(Res.string.menu_locked_policy, lacking.joinToString(", "))
}

@Composable
private fun MenuItem(entry: MenuEntry, device: DeviceInfo, open: (MenuEntry) -> Unit) {
    if (entry.items.isEmpty()) {
        MenuRow(entry.title, false, icon = menuIcon(entry.title), locked = locked(device, entry)) { open(entry) }
        return
    }
    val palette = Theme.palette
    var expanded by remember { mutableStateOf(false) }
    if (LocalCompact.current) {
        MenuRow(entry.title, true, active = expanded, icon = menuIcon(entry.title)) { expanded = !expanded }
        if (expanded) for (child in entry.items) MenuRow(child.title, false, inner = true, locked = locked(device, child)) { open(child) }
        return
    }
    UnstyledDropdownMenu(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        side = AnchorSide.End,
        alignment = AnchorAlignment.Start,
        panel = {
            MenuPanel {
                for (child in entry.items) {
                    val reason = locked(device, child)
                    DropdownItem(onClick = {
                        open(child)
                        expanded = false
                    }) {
                        Hint(reason.orEmpty(), enabled = reason != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(child.title, color = if (reason != null) palette.muted else Color.Unspecified)
                                if (reason != null) UnstyledIcon(Lucide.Lock, reason, Modifier.padding(start = 6.dp).size(12.dp), tint = palette.muted)
                            }
                        }
                    }
                }
            }
        },
        anchor = { MenuRow(entry.title, true, active = expanded, icon = menuIcon(entry.title)) { expanded = true } },
    )
}

@Composable
private fun MenuRow(title: String, group: Boolean, active: Boolean = false, inner: Boolean = false, highlight: String = "", icon: ThemeIcon? = null, locked: String? = null, onClick: () -> Unit) {
    val palette = Theme.palette
    val divider = if (Theme.classic) Modifier.drawBehind { drawLine(palette.divider ?: palette.line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) } else Modifier
    val pill = if (Theme.material) Modifier.background(palette.raised).padding(horizontal = 8.dp).clip(RoundedCornerShape(50)) else Modifier
    Hint(locked.orEmpty(), enabled = locked != null) {
        Row(
            Modifier.fillMaxWidth().height(if (LocalCompact.current) 44.dp else Theme.metrics.menuItem)
                .then(pill)
                .background(if (active) palette.selection else palette.raised)
                .then(divider)
                .clickable(onClick = onClick).hand().padding(start = if (inner) 28.dp else if (icon != null) 10.dp else 14.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = palette.tintOf(icon).takeIf { locked == null } ?: if (Theme.classic && locked == null) palette.accent else palette.muted
            if (icon != null) UnstyledIcon(icon.vector, null, Modifier.padding(end = 9.dp).size(Theme.metrics.icon), tint = tint)
            Text(
                marked(title, highlight, SpanStyle(background = palette.warning.copy(alpha = 0.35f))),
                Modifier.weight(1f),
                color = if (locked != null) palette.muted else Color.Unspecified,
                maxLines = 1,
            )
            if (locked != null) UnstyledIcon(Lucide.Lock, locked, Modifier.size(12.dp).testTag("menu-locked"), tint = palette.muted)
            if (group) UnstyledIcon(Lucide.ChevronRight, null, Modifier.size(14.dp), tint = Theme.palette.muted)
        }
    }
}

@Composable
private fun viewLabel(view: MenuView) = stringResource(
    when (view) {
        MenuView.WinBox -> Res.string.main_view_winbox
        MenuView.Native -> Res.string.main_view_native
        MenuView.RouterOs -> Res.string.main_view_routeros
    },
)

@Composable
private fun viewHint(view: MenuView) = stringResource(
    when (view) {
        MenuView.WinBox -> Res.string.main_view_winbox_hint
        MenuView.Native -> Res.string.main_view_native_hint
        MenuView.RouterOs -> Res.string.main_view_routeros_hint
    },
)
