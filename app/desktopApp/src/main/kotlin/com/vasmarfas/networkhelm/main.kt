package com.vasmarfas.networkhelm

import androidx.compose.foundation.DarkDefaultContextMenuRepresentation
import androidx.compose.foundation.LightDefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.vasmarfas.networkhelm.app.ClearTypeLayer
import com.vasmarfas.networkhelm.app.ResizeEdges
import com.vasmarfas.networkhelm.app.WorkspaceBackground
import com.vasmarfas.networkhelm.app.desktopServices
import com.vasmarfas.networkhelm.app.quit
import com.vasmarfas.networkhelm.app.rememberFrameState
import com.vasmarfas.networkhelm.app.reportStartupFailure
import com.vasmarfas.networkhelm.ui.about.appIcon
import com.vasmarfas.networkhelm.ui.kit.HintHost
import com.vasmarfas.networkhelm.ui.main.PathWindow
import com.vasmarfas.networkhelm.ui.settings.BrowserAccessMark
import com.vasmarfas.networkhelm.ui.terminal.BossTerminal
import com.vasmarfas.networkhelm.ui.terminal.LocalTerminal
import kotlinx.coroutines.delay
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.newFullscreenControls
import org.jetbrains.jewel.window.utils.clientRegion

fun main() {
    try {
        startApplication()
    } catch (e: Throwable) {
        reportStartupFailure(e)
        throw e
    }
}

private fun startApplication() = application {
    val scope = rememberCoroutineScope()
    val shell = remember { Shell(desktopServices(), scope, canDetach = true) }
    if (System.getProperty("networkhelm.appcds") != null) {
        LaunchedEffect(Unit) {
            delay(5000)
            exitApplication()
        }
    }
    ShellTheme(shell, relocalize = false) {
        Frame(shell.settings.current.scale) {
            val tab = shell.routers.getOrNull(shell.selected)
            DecoratedWindow(
                onCloseRequest = { shell.quit { quit() } },
                state = rememberFrameState(shell.services.storage),
                title = tab?.let { "${it.session.device.identity} (${it.address}) — NetworkHelm" } ?: "NetworkHelm",
                icon = appIcon(),
            ) {
                ClearTypeLayer()
                WorkspaceBackground()
                TitleBar(Modifier.newFullscreenControls()) {
                    ShellTheme(shell) { TitleBarContent(shell, Modifier.align(Alignment.Start), Modifier.clientRegion("routers")) }
                    shell.services.browserAccess?.let { access ->
                        ShellTheme(shell) { BrowserAccessMark(access, Modifier.align(Alignment.End).clientRegion("access")) { shell.accessShown = true } }
                    }
                }
                CompositionLocalProvider(LocalTerminal provides { channel, modifier -> BossTerminal(channel, modifier) }) {
                    ResizeEdges { MenuLook(shell) { App(shell, tabsInTitle = true) } }
                }
            }
            for (window in shell.detached) {
                key(window) {
                    val name = "${window.spec.title} — ${window.tab.session.device.identity}"
                    DecoratedWindow(
                        onCloseRequest = { shell.attach(window) },
                        state = rememberWindowState(size = window.size),
                        title = name,
                        icon = appIcon(),
                    ) {
                        ClearTypeLayer()
                        TitleBar(Modifier.newFullscreenControls()) {
                            ShellTheme(shell) { TitleBarName(name, Modifier.align(Alignment.CenterHorizontally)) }
                        }
                        ShellTheme(shell) {
                            WorkspaceBackground()
                            MenuLook(shell) {
                                ResizeEdges {
                                    HintHost {
                                        PathWindow(window.tab.session, window.spec, shell.tables, window.tab::openItem, window.tab::open) {
                                            shell.detached.remove(window)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuLook(shell: Shell, content: @Composable () -> Unit) {
    val dark = shell.settings.theme.isDark(shell.settings.current.dark, isSystemInDarkTheme())
    val look = if (dark) DarkDefaultContextMenuRepresentation else LightDefaultContextMenuRepresentation
    CompositionLocalProvider(LocalContextMenuRepresentation provides look, content = content)
}
