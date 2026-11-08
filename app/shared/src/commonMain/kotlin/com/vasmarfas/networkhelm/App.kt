package com.vasmarfas.networkhelm

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.app.LocalAppSettings
import com.vasmarfas.networkhelm.app.LocalSettings
import com.vasmarfas.networkhelm.app.LocalSnippets
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.ui.about.AboutDialog
import com.vasmarfas.networkhelm.ui.about.appIcon
import com.vasmarfas.networkhelm.ui.connect.ConnectScreen
import com.vasmarfas.networkhelm.ui.connect.TrustDialog
import com.vasmarfas.networkhelm.ui.kit.CompactHeight
import com.vasmarfas.networkhelm.ui.kit.CompactWidth
import com.vasmarfas.networkhelm.ui.kit.HintHost
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.TabStrip
import com.vasmarfas.networkhelm.ui.main.LocalChangelog
import com.vasmarfas.networkhelm.ui.main.MainScreen
import com.vasmarfas.networkhelm.ui.main.RouterSearchDialog
import com.vasmarfas.networkhelm.ui.settings.BrowserAccessDialog
import com.vasmarfas.networkhelm.ui.settings.SettingsDialog
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.theme.TouchMetrics
import com.vasmarfas.networkhelm.ui.tools.ToolsDialog
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.main_connect_another
import networkhelm.app.shared.generated.resources.main_connect_another_short

@Composable
fun App(services: Services) {
    val scope = rememberCoroutineScope()
    App(remember { Shell(services, scope) })
}

@Composable
fun ShellTheme(shell: Shell, relocalize: Boolean = true, content: @Composable () -> Unit) {
    val settings = shell.settings.current
    val theme = shell.settings.theme.let { it.copy(metrics = it.metrics.copy(row = it.metrics.row + settings.rowSpacing.dp)) }
    key(settings.language.takeIf { relocalize }) {
        CompositionLocalProvider(LocalSettings provides settings, LocalAppSettings provides shell.settings, LocalSnippets provides shell.snippets, LocalChangelog provides shell.services.changelog) {
            NetworkHelmTheme(theme, theme.isDark(settings.dark, isSystemInDarkTheme()), settings.scale, content)
        }
    }
}

@Composable
fun App(shell: Shell, tabsInTitle: Boolean = false) {
    LaunchedEffect(shell) { shell.settings.load() }
    LaunchedEffect(shell) { shell.saved.load() }
    LaunchedEffect(shell) { shell.snippets.load() }
    LaunchedEffect(shell) { shell.tables.keep() }
    LaunchedEffect(shell) { shell.services.browserAccess?.resume() }
    val read = shell.settings.loaded
    LaunchedEffect(shell, read, shell.settings.current.checkUpdates) { if (read) shell.checkRelease() }
    ShellTheme(shell) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Theme.palette.workspace).safeDrawingPadding()) {
            val compact = maxWidth < CompactWidth || maxHeight < CompactHeight
            CompositionLocalProvider(LocalCompact provides compact) { TouchMetrics(compact) { HintHost { Routers(shell, tabsInTitle) } } }
        }
    }
}

@Composable
fun TitleBarContent(shell: Shell, modifier: Modifier, tabs: Modifier) {
    Row(modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        Image(appIcon(), null, Modifier.padding(horizontal = 10.dp).size(16.dp))
        if (shell.routers.isEmpty()) Text("NetworkHelm", style = Theme.type.strong, maxLines = 1) else RouterTabs(shell, tabs)
    }
}

@Composable
fun TitleBarName(name: String, modifier: Modifier) {
    Text(name, modifier, style = Theme.type.strong, maxLines = 1)
}

@Composable
private fun RouterTabs(shell: Shell, modifier: Modifier = Modifier) {
    TabStrip(
        shell.routers.map { it.title } + stringResource(if (LocalCompact.current) Res.string.main_connect_another_short else Res.string.main_connect_another),
        shell.selected,
        { shell.selected = it },
        modifier,
    )
}

@Composable
private fun Routers(shell: Shell, tabsInTitle: Boolean) {
    val tab = shell.routers.getOrNull(shell.selected)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Theme.palette.workspace)) {
            if (shell.routers.isNotEmpty() && !tabsInTitle) {
                Row(Modifier.fillMaxWidth().background(Theme.palette.raised)) { RouterTabs(shell) }
            }
            if (tab == null) {
                ConnectScreen(
                    shell.services,
                    shell.saved,
                    shell.scope,
                    shell.questions,
                    onAbout = { shell.about = true },
                    onSettings = { shell.settingsShown = true },
                    onTools = { shell.toolsShown = true },
                    probe = shell.settings.current.probe,
                ) { session, address, login ->
                    shell.add(session, address, login)
                }
            } else {
                key(tab) {
                    MainScreen(
                        tab,
                        onDisconnect = { shell.disconnect(tab) },
                        onDetach = if (shell.canDetach) { window -> shell.detach(tab, window) } else null,
                        onAbout = { shell.about = true },
                        onSettings = { shell.settingsShown = true },
                        onTools = { shell.toolsShown = true },
                        onSecure = if (tab.login != null) { { shell.secure(tab) } } else null,
                        onSearch = { shell.searchShown = true },
                        others = shell.routers.filter { it !== tab && it.session.device.platform == tab.session.device.platform },
                        release = shell.release,
                    )
                }
            }
        }
        if (tab != null) shell.questions.question?.let { TrustDialog(it) }
        if (shell.about) AboutDialog { shell.about = false }
        if (shell.settingsShown) {
            SettingsDialog(shell.settings, shell.services.browserAccess, onBrowserAccess = {
                shell.settingsShown = false
                shell.accessShown = true
            }, themeEditor = shell.themeEditor) { shell.settingsShown = false }
        }
        if (shell.toolsShown) ToolsDialog { shell.toolsShown = false }
        if (shell.accessShown) shell.services.browserAccess?.let { BrowserAccessDialog(it) { shell.accessShown = false } }
        if (shell.searchShown) RouterSearchDialog(shell.search, shell.routers, shell::open) { shell.searchShown = false }
    }
}
