package com.vasmarfas.networkhelm

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.app.Access
import com.vasmarfas.networkhelm.app.AppSettings
import com.vasmarfas.networkhelm.app.SavedRouters
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.app.Snippets
import com.vasmarfas.networkhelm.app.parseAddress
import com.vasmarfas.networkhelm.journal.EditJournal
import com.vasmarfas.networkhelm.journal.JournalingSession
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.routeros.RouterOsProtocol
import com.vasmarfas.networkhelm.search.Finding
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.ui.connect.TrustQuestions
import com.vasmarfas.networkhelm.ui.main.RouterSearch
import com.vasmarfas.networkhelm.ui.main.RouterTab
import com.vasmarfas.networkhelm.ui.main.WindowSpec
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.ThemeFile
import com.vasmarfas.networkhelm.ui.workspace.WindowState
import com.vasmarfas.networkhelm.update.Release
import com.vasmarfas.networkhelm.update.newerRelease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class Detached(val tab: RouterTab, val spec: WindowSpec, val size: DpSize)

@Stable
class Shell(val services: Services, val scope: CoroutineScope, val canDetach: Boolean = false) {
    val saved = SavedRouters(services.storage)
    val routers = mutableStateListOf<RouterTab>()
    val detached = mutableStateListOf<Detached>()
    var selected by mutableIntStateOf(0)
    val tables = TableLayouts(services.storage)
    val questions = TrustQuestions()
    val settings = AppSettings(services.storage)
    var about by mutableStateOf(false)
    var settingsShown by mutableStateOf(false)
    val themeEditor = mutableStateOf<ThemeFile?>(null)
    var toolsShown by mutableStateOf(false)
    var accessShown by mutableStateOf(false)
    val search = RouterSearch(scope)
    val snippets = Snippets(services.storage)
    var searchShown by mutableStateOf(false)
    var release by mutableStateOf<Release?>(null)
        private set

    // the router that is asked about its Safe Mode before the app closes, and what closes the app
    var leaving by mutableStateOf<RouterTab?>(null)
        private set
    private var exit: (() -> Unit)? = null
    private val packs = bundledPacks().values

    suspend fun checkRelease() {
        release = if (settings.current.checkUpdates) services.releases?.invoke()?.let { newerRelease(it, appVersion) } else null
    }

    fun add(session: DeviceSession, address: String, login: RouterOsLogin? = null) {
        val journal = EditJournal()
        routers += RouterTab(JournalingSession(session, journal, address), address, packs, services.storage, tables, journal, login) { settings.current.tablesAsTabs }
        selected = routers.size - 1
    }

    suspend fun secure(tab: RouterTab) {
        val login = tab.login ?: return
        val secured = RouterOsLogin(
            login.host, login.user, login.password, RouterOsProtocol.ApiSsl, RouterOsProtocol.ApiSsl.defaultPort, login.encoding, login.sshKey, login.sshAgent,
            sshPassphrase = login.sshPassphrase,
        )
        val session = services.connect(secured, TrustPolicy(services.trust, questions::ask), scope + Dispatchers.Default)
        val address = tab.address.takeIf { parseAddress(it)?.port == null } ?: login.host
        val place = routers.indexOf(tab)
        disconnect(tab)
        val journal = EditJournal()
        routers.add(place.coerceIn(0, routers.size), RouterTab(JournalingSession(session, journal, address), address, packs, services.storage, tables, journal, secured) { settings.current.tablesAsTabs })
        selected = place.coerceIn(0, routers.size - 1)
        saved.routers.value.firstOrNull { it.address == tab.address && it.user == login.user && it.protocol == Access.Api }?.let { entry ->
            saved.remove(entry)
            saved.save(entry.copy(address = address, protocol = Access.ApiSsl))
        }
    }

    fun disconnect(tab: RouterTab) {
        detached.removeAll { it.tab === tab }
        tab.tunnels.closeAll()
        tab.session.close()
        routers.remove(tab)
        selected = selected.coerceAtMost(routers.size)
        if (leaving === tab) exit?.let(::quit)
    }

    // a router rolls back what was changed in Safe Mode when its session ends, so the app closes only after every router
    // that holds the mode was asked what to do with it, as Disconnect asks
    fun quit(exit: () -> Unit) {
        stay()
        scope.launch {
            val held = routers.firstOrNull { tab -> tab.session.safeMode?.active?.catch { emit(false) }?.first() == true }
            if (held == null) {
                exit()
            } else {
                this@Shell.exit = exit
                selected = routers.indexOf(held)
                leaving = held
            }
        }
    }

    fun stay() {
        leaving = null
        exit = null
    }

    fun open(tab: RouterTab, hit: Finding.Hit) {
        selected = routers.indexOf(tab).takeIf { it >= 0 } ?: return
        tab.openItem(tab.placeOf(hit.place.path).last(), hit.place.path, hit.item.id)
        searchShown = false
    }

    fun detach(tab: RouterTab, window: WindowState) {
        val spec = tab.specs[window.id] ?: return
        tab.workspace.close(window)
        detached += Detached(tab, spec, window.size)
    }

    val canGoBack: Boolean
        get() = about || settingsShown || toolsShown || accessShown || searchShown || routers.getOrNull(selected)?.workspace?.windows?.isNotEmpty() == true

    fun back() {
        when {
            searchShown -> searchShown = false
            accessShown -> accessShown = false
            toolsShown -> toolsShown = false
            themeEditor.value != null -> themeEditor.value = null
            settingsShown -> settingsShown = false
            about -> about = false
            else -> routers.getOrNull(selected)?.workspace?.let { workspace -> workspace.windows.lastOrNull()?.let(workspace::close) }
        }
    }

    fun attach(window: Detached) {
        detached.remove(window)
        if (window.tab in routers) window.tab.place(window.spec, DpOffset(24.dp, 24.dp), window.size)
    }
}
