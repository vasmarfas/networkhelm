package com.vasmarfas.networkhelm

import com.vasmarfas.networkhelm.app.Access
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.SavedRouter
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.routeros.RouterOsProtocol
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.ui.theme.BundledThemes
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ShellTest {

    private val shell = Shell(Services(MemoryStorage(), MemoryTrustStore(), neighbors = null), TestScope(), canDetach = true)

    @Test
    fun aRouterPreparedOverThePlainApiOpensAgainOverApiSsl() = runBlocking {
        val logins = mutableListOf<RouterOsLogin>()
        val secured = FakeRouter(id = "rd-test-ssl")
        val services = Services(MemoryStorage(), MemoryTrustStore(), null) { login, _, _ ->
            logins += login
            secured
        }
        val shell = Shell(services, TestScope())
        shell.saved.save(SavedRouter("192.0.2.1:8728", "admin", Access.Api, group = "lab"))
        shell.add(FakeRouter(id = "rd-test-other"), "192.0.2.9")
        shell.add(FakeRouter(encrypted = false), "192.0.2.1:8728", RouterOsLogin("192.0.2.1", "admin", "secret", RouterOsProtocol.Api, 8728))
        shell.add(FakeRouter(id = "rd-test-third"), "192.0.2.3")
        shell.secure(shell.routers[1])
        val login = logins.single()
        assertEquals(RouterOsProtocol.ApiSsl to 8729, login.protocol to login.port)
        assertEquals("secret", login.password)
        assertEquals(listOf("192.0.2.9", "192.0.2.1", "192.0.2.3"), shell.routers.map { it.address })
        assertEquals(1, shell.selected)
        assertEquals(RouterOsProtocol.ApiSsl, shell.routers[1].login?.protocol)
        assertEquals(listOf(SavedRouter("192.0.2.1", "admin", Access.ApiSsl, group = "lab")), shell.saved.routers.value)
    }

    @Test
    fun aNewerReleaseOnGitHubIsKeptAndAFailedCheckLeavesNothing() = runBlocking {
        val listing = """[{"tag_name":"v99.0.0","html_url":"https://github.com/example/app/releases/tag/v99.0.0","draft":false,"prerelease":false}]"""
        val found = Shell(Services(MemoryStorage(), MemoryTrustStore(), null, releases = { listing }), TestScope())
        found.checkRelease()
        assertEquals("99.0.0", found.release?.version)
        assertEquals("https://github.com/example/app/releases/tag/v99.0.0", found.release?.url)

        val offline = Shell(Services(MemoryStorage(), MemoryTrustStore(), null, releases = { null }), TestScope())
        offline.checkRelease()
        assertEquals(null, offline.release)
        shell.checkRelease()
        assertEquals(null, shell.release)
    }

    @Test
    fun withTheCheckSwitchedOffTheShellAsksGitHubForNothingAndTheNoticeGoesAway() = runBlocking {
        val listing = """[{"tag_name":"v99.0.0","html_url":"https://github.com/example/app/releases/tag/v99.0.0","draft":false,"prerelease":false}]"""
        var asked = 0
        val checking = Shell(Services(MemoryStorage(), MemoryTrustStore(), null, releases = { listing.also { asked++ } }), TestScope())
        checking.settings.change { it.copy(checkUpdates = false) }
        checking.checkRelease()
        assertEquals(0, asked)
        assertEquals(null, checking.release)
        checking.settings.change { it.copy(checkUpdates = true) }
        checking.checkRelease()
        assertEquals(1, asked)
        assertEquals("99.0.0", checking.release?.version)
        checking.settings.change { it.copy(checkUpdates = false) }
        checking.checkRelease()
        assertEquals(1, asked)
        assertEquals(null, checking.release)
    }

    @Test
    fun aWindowTakenOutComesBackOnCloseAndGoesAwayWithItsRouter() {
        shell.add(FakeRouter(), "192.0.2.1")
        val tab = shell.routers.single()
        tab.open(MenuEntry("Addresses", "ip/address"))
        val window = tab.workspace.windows.single()
        val spec = tab.specs.getValue(window.id)

        shell.detach(tab, window)
        assertTrue(tab.workspace.windows.isEmpty())
        val detached = shell.detached.single()
        assertSame(spec, detached.spec)

        shell.attach(detached)
        assertTrue(shell.detached.isEmpty())
        assertSame(spec, tab.specs.getValue(tab.workspace.windows.single().id))

        shell.detach(tab, tab.workspace.windows.single())
        shell.disconnect(tab)
        assertTrue(shell.detached.isEmpty())
        assertEquals(0, shell.routers.size)
    }

    @Test
    fun backClosesTheFrontWindowOfTheRouterShown() {
        assertFalse(shell.canGoBack)
        shell.add(FakeRouter(), "192.0.2.1")
        val tab = shell.routers.single()
        tab.open(MenuEntry("Addresses", "ip/address"))
        tab.open(MenuEntry("Routes", "ip/route"))
        assertTrue(shell.canGoBack)
        shell.back()
        assertEquals(listOf("Addresses"), tab.workspace.windows.map { it.title })
        shell.back()
        assertFalse(shell.canGoBack)
    }

    @Test
    fun backLeavesTheThemeEditorBeforeTheSettingsUnderIt() {
        shell.settingsShown = true
        shell.themeEditor.value = BundledThemes.first()
        shell.back()
        assertEquals(null, shell.themeEditor.value)
        assertTrue(shell.settingsShown)
    }

    @Test
    fun backClosesWhatLiesOverTheWindowsBeforeTheWindows() {
        shell.add(FakeRouter(), "192.0.2.1")
        val tab = shell.routers.single()
        tab.open(MenuEntry("Addresses", "ip/address"))
        shell.about = true
        shell.settingsShown = true
        shell.back()
        assertFalse(shell.settingsShown)
        assertTrue(shell.about)
        shell.back()
        assertFalse(shell.about)
        assertEquals(1, tab.workspace.windows.size)
        shell.back()
        assertFalse(shell.canGoBack)
    }
}
