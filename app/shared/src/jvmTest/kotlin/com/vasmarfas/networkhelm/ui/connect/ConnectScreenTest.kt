package com.vasmarfas.networkhelm.ui.connect

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.app.Access
import com.vasmarfas.networkhelm.app.DeviceKind
import com.vasmarfas.networkhelm.app.MemorySecrets
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.SavedRouter
import com.vasmarfas.networkhelm.app.SavedRouters
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.app.passwordKey
import com.vasmarfas.networkhelm.app.snmpPrivacyKey
import com.vasmarfas.networkhelm.app.sshKeyKey
import com.vasmarfas.networkhelm.app.sshPassphraseKey
import com.vasmarfas.networkhelm.discovery.Found
import com.vasmarfas.networkhelm.discovery.MndpAnnouncement
import com.vasmarfas.networkhelm.discovery.MndpSighting
import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.api.ApiTrapException
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.Login
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.snmp.AuthProtocol
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustKind
import com.vasmarfas.networkhelm.ui.files.FileExchange
import com.vasmarfas.networkhelm.ui.files.LocalFile
import com.vasmarfas.networkhelm.ui.files.LocalFileExchange
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import com.vasmarfas.networkhelm.winbox.WinBoxSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.util.Locale
import javax.imageio.ImageIO
import javax.net.ssl.SSLHandshakeException
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ConnectScreenTest {

    private class Router(override val device: DeviceInfo) : DeviceSession {
        override val capabilities = Capabilities(true, true, true, SafeModeKind.ApiSession, terminal = false, files = false, export = true)
        override suspend fun describe(path: MenuPath): MenuSchema = error("not used")
        override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> = emptyFlow()
        override suspend fun get(path: MenuPath, id: String?, properties: List<String>?): Item? = null
        override suspend fun count(path: MenuPath, filter: Filter?) = 0
        override suspend fun add(path: MenuPath, values: Map<String, String>) = error("not used")
        override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) = Unit
        override suspend fun remove(path: MenuPath, ids: List<String>) = Unit
        override suspend fun move(path: MenuPath, ids: List<String>, before: String?) = Unit
        override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> = emptyFlow()
        override suspend fun export(path: MenuPath) = ""
        override val safeMode: SafeMode? = null
        override val ssh: SshSession? = null
        override fun close() = Unit
    }

    private val router = Router(DeviceInfo(Platform.RouterOs, "rd-test", "7.26", "x86_64", "CHR", setOf("routeros"), setOf("read", "write")))

    private fun neighbor(identity: String, mac: String, ip: String, port: String) = MndpSighting(
        ip,
        MndpAnnouncement(mac, identity, "7.26 (stable)", "MikroTik", 3600, null, "CHR", port, ip, null, emptyMap()),
    )

    private val neighbors = flowOf(
        neighbor("rd-test-a", "02:00:00:00:00:01", "192.0.2.11", "ether1"),
        neighbor("rd-test-a", "02:00:00:00:00:02", "198.51.100.11", "ether2"),
        neighbor("rd-test-b", "02:00:00:00:00:03", "192.0.2.12", "bridge1"),
    )

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    @Test
    fun aNewCertificateIsAskedAboutAndTheSessionOpens() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val trust = MemoryTrustStore()
        var opened: DeviceSession? = null
        var asked = 0
        val services = Services(MemoryStorage(), trust, neighbors) { login, policy, _ ->
            asked++
            assertEquals("192.0.2.1", login.host)
            assertEquals(8729, login.port)
            if (!policy.confirm(login.host, login.port, TrustKind.Tls, "4f".repeat(32), null)) error("declined")
            router
        }
        setContent {
            NetworkHelmTheme(dark = true) {
                ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { session, _, _ -> opened = session }
            }
        }
        onNodeWithTag("address").performTextInput("192.0.2.1")
        onNodeWithTag("password").performTextInput("secret")
        picture("connect-dark")
        onNodeWithText("Connect").performClick()
        waitUntil { onAllNodes(hasTestTag("trust")).fetchSemanticsNodes().isNotEmpty() }
        picture("connect-trust")
        onNodeWithText("Trust").performClick()
        waitUntil { opened != null }
        assertEquals(1, asked)
        assertEquals("4f".repeat(32), runBlocking { trust.get("192.0.2.1", 8729, TrustKind.Tls) })
    }

    @Test
    fun aCsvFileFillsTheSavedListAndItsPasswordsComeBack() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val saved = SavedRouters(storage)
        val secrets = MemorySecrets()
        val services = Services(storage, MemoryTrustStore(), null, secrets) { _, _, _ -> router }
        val csv = "address;user;password;group;comment\n192.0.2.21;rd-test;\"p;w\";Лаборатория;CHR\nnot an address;admin;;;\n"
        var picks = 0
        val exchange = object : FileExchange {
            override suspend fun pick() = if (picks++ == 0) emptyList() else listOf(LocalFile("routers.csv") { csv.encodeToByteArray() })
            override suspend fun save(name: String, bytes: ByteArray) = false
        }
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { ConnectScreen(services, saved, rememberCoroutineScope()) { _, _, _ -> } }
            }
        }
        onNodeWithText("Import").performClick()
        waitForIdle()
        assertTrue(onAllNodes(hasText("Imported", substring = true)).fetchSemanticsNodes().isEmpty(), "a cancelled dialog reports nothing")
        onNodeWithText("Import").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Imported: 1, skipped: 1")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(SavedRouter("192.0.2.21", "rd-test", group = "Лаборатория", comment = "CHR")), saved.routers.value)
        assertEquals("p;w", runBlocking { secrets.get(passwordKey("192.0.2.21", "rd-test")) })

        onNodeWithText("192.0.2.21").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasSetTextAction() and hasText("p;w")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aCsvOfExcelComesInWithItsCyrillic() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val saved = SavedRouters(storage)
        val services = Services(storage, MemoryTrustStore(), null, MemorySecrets()) { _, _, _ -> router }
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "address;group\n192.0.2.22;Офис\n".encodeToByteArray()
        val cp1251 = "address;group\n192.0.2.23;Склад\n".toByteArray(Charset.forName("windows-1251"))
        val exchange = object : FileExchange {
            override suspend fun pick() = listOf(LocalFile("utf8.csv") { utf8 }, LocalFile("cp1251.csv") { cp1251 })
            override suspend fun save(name: String, bytes: ByteArray) = false
        }
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { ConnectScreen(services, saved, rememberCoroutineScope()) { _, _, _ -> } }
            }
        }
        onNodeWithText("Import").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Imported: 2, skipped: 0")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(setOf("Офис", "Склад"), saved.routers.value.map { it.group }.toSet())
    }

    @Test
    fun aCsvChangesOnlyItsOwnColumnsOfARouterSavedBefore() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val saved = SavedRouters(storage)
        runBlocking { saved.save(SavedRouter("192.0.2.24", "rd-test", Access.Api, "Лаборатория", "old", ApiEncoding.Windows1251, via = "192.0.2.1")) }
        val services = Services(storage, MemoryTrustStore(), null, MemorySecrets()) { _, _, _ -> router }
        val exchange = object : FileExchange {
            override suspend fun pick() = listOf(LocalFile("routers.csv") { "address,user,comment\n192.0.2.24,rd-test,new\n".encodeToByteArray() })
            override suspend fun save(name: String, bytes: ByteArray) = false
        }
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { ConnectScreen(services, saved, rememberCoroutineScope()) { _, _, _ -> } }
            }
        }
        onNodeWithText("Import").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Imported: 1, skipped: 0")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(SavedRouter("192.0.2.24", "rd-test", Access.Api, "Лаборатория", "new", ApiEncoding.Windows1251, via = "192.0.2.1")), saved.routers.value)
    }

    private fun SkikoComposeUiTest.pickWinBox(saved: SavedRouters, secrets: MemorySecrets, sessions: List<WinBoxSession>, compact: Boolean = false) {
        val book = byteArrayOf(0x0D, 0xF0.toByte(), 0x1D, 0xC0.toByte())
        val services = Services(MemoryStorage(), MemoryTrustStore(), null, secrets, readWinBoxBook = { bytes -> sessions.takeIf { bytes.contentEquals(book) } }) { _, _, _ -> router }
        val exchange = object : FileExchange {
            override suspend fun pick() = listOf(LocalFile("Addresses.cdb") { book })
            override suspend fun save(name: String, bytes: ByteArray) = false
        }
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange, LocalCompact provides compact) {
                NetworkHelmTheme(dark = true) { ConnectScreen(services, saved, rememberCoroutineScope()) { _, _, _ -> } }
            }
        }
        if (compact) onNodeWithContentDescription("Import").performScrollTo().performClick() else onNodeWithText("Import").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("winbox-import")).fetchSemanticsNodes().isNotEmpty() }
    }

    private val winBoxSessions = listOf(
        WinBoxSession("192.0.2.31:8292", "rd-test", "s3cret", "Лаборатория", "CHR", ""),
        WinBoxSession("02:00:00:00:00:09", "admin", "", "", "", "198.51.100.5"),
        WinBoxSession("02:00:00:00:00:0A", "", "", "", "", ""),
        WinBoxSession("not an address", "x", "", "", "", ""),
    )

    @Test
    fun anAddressBookOfWinBoxComesInWithItsPasswords() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val saved = SavedRouters(MemoryStorage())
        val secrets = MemorySecrets()
        pickWinBox(saved, secrets, winBoxSessions)
        picture("connect-winbox-import")
        onNodeWithTag("winbox-row-2").assertIsOn()
        onNodeWithTag("winbox-row-3").assertDoesNotExist()
        onNodeWithTag("winbox-ok").assertTextEquals("Import (3)")
        onNodeWithTag("winbox-ok").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Imported: 3, skipped: 1")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(
            listOf(
                SavedRouter("02:00:00:00:00:0A", "admin", Access.MacTelnet),
                SavedRouter("198.51.100.5", "admin", Access.Tcp8291),
                SavedRouter("192.0.2.31:8292", "rd-test", Access.Tcp8291, "Лаборатория", "CHR"),
            ),
            saved.routers.value,
        )
        assertEquals("s3cret", runBlocking { secrets.get(passwordKey("192.0.2.31:8292", "rd-test")) })
        assertEquals(null, runBlocking { secrets.get(passwordKey("198.51.100.5", "admin")) })
    }

    @Test
    fun aSessionOfWinBoxThatIsUntickedStaysOut() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val saved = SavedRouters(MemoryStorage())
        val secrets = MemorySecrets()
        pickWinBox(saved, secrets, winBoxSessions)
        onNodeWithTag("winbox-row-0").performClick()
        onNodeWithTag("winbox-row-0").assertIsOff()
        onNodeWithTag("winbox-ok").assertTextEquals("Import (2)")
        onNodeWithTag("winbox-ok").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Imported: 2, skipped: 2")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("02:00:00:00:00:0A", "198.51.100.5"), saved.routers.value.map { it.address })
        assertEquals(null, runBlocking { secrets.get(passwordKey("192.0.2.31:8292", "rd-test")) })
    }

    @Test
    fun thePasswordsOfWinBoxStayOutWhenTheirBoxIsUnticked() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val saved = SavedRouters(MemoryStorage())
        val secrets = MemorySecrets()
        pickWinBox(saved, secrets, winBoxSessions)
        onNodeWithTag("winbox-passwords").assertIsOn().performClick()
        onNodeWithTag("winbox-passwords").assertIsOff()
        onNodeWithTag("winbox-ok").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Imported: 3, skipped: 1")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(3, saved.routers.value.size)
        assertEquals(null, runBlocking { secrets.get(passwordKey("192.0.2.31:8292", "rd-test")) })
    }

    @Test
    fun aBookOfWinBoxWithoutPasswordsHasTheirBoxOff() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        pickWinBox(SavedRouters(MemoryStorage()), MemorySecrets(), listOf(WinBoxSession("192.0.2.31", "rd-test", "", "", "", "")))
        onNodeWithTag("winbox-passwords").assertIsOff().assertIsNotEnabled()
        onNodeWithText("The book keeps no passwords.").assertExists()
    }

    @Test
    fun theBoxInTheHeaderTicksAndUnticksAllSessionsOfWinBox() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        pickWinBox(SavedRouters(MemoryStorage()), MemorySecrets(), winBoxSessions)
        onNodeWithTag("winbox-all").assertIsOn().performClick()
        onNodeWithTag("winbox-all").assertIsOff()
        onNodeWithTag("winbox-row-1").assertIsOff()
        onNodeWithTag("winbox-ok").assertTextEquals("Import (0)").assertIsNotEnabled()
        onNodeWithTag("winbox-all").performClick()
        onNodeWithTag("winbox-row-1").assertIsOn()
        onNodeWithTag("winbox-ok").assertTextEquals("Import (3)").assertIsEnabled()
    }

    @Test
    fun aRouterOfTheListStartsUntickedAndTakesTheGroupAndPasswordOfWinBox() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val saved = SavedRouters(MemoryStorage())
        runBlocking { saved.save(SavedRouter("192.0.2.31:8292", "rd-test", Access.Api, "old")) }
        val secrets = MemorySecrets()
        runBlocking { secrets.put(passwordKey("192.0.2.31:8292", "rd-test"), "old-secret") }
        pickWinBox(saved, secrets, winBoxSessions.take(1))
        onNodeWithTag("winbox-row-0").assertIsOff()
        onNodeWithText("In the list").assertExists()
        onNodeWithTag("winbox-ok").assertTextEquals("Import (0)").assertIsNotEnabled()
        onNodeWithTag("winbox-row-0").performClick()
        onNodeWithTag("winbox-ok").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Imported: 1, skipped: 0")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(SavedRouter("192.0.2.31:8292", "rd-test", Access.Api, "Лаборатория", "CHR")), saved.routers.value)
        assertEquals("s3cret", runBlocking { secrets.get(passwordKey("192.0.2.31:8292", "rd-test")) })
    }

    @Test
    fun theSessionsOfWinBoxFitAPhoneInTwoLines() = runDesktopComposeUiTest(400, 800) {
        Locale.setDefault(Locale.ENGLISH)
        pickWinBox(SavedRouters(MemoryStorage()), MemorySecrets(), winBoxSessions, compact = true)
        picture("connect-winbox-import-phone")
        onNodeWithTag("winbox-row-0").assertIsOn()
        onNodeWithText("rd-test", substring = true).assertExists()
        onNodeWithTag("winbox-ok").assertTextEquals("Import (3)")
    }

    @Test
    fun cancellingTheChoiceOfWinBoxSavesNothing() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val saved = SavedRouters(MemoryStorage())
        val secrets = MemorySecrets()
        pickWinBox(saved, secrets, winBoxSessions)
        onNodeWithTag("winbox-cancel").performClick()
        onNodeWithTag("winbox-import").assertDoesNotExist()
        assertTrue(saved.routers.value.isEmpty())
        assertEquals(null, runBlocking { secrets.get(passwordKey("192.0.2.31:8292", "rd-test")) })
    }

    @Test
    fun aFileOfWinBoxThatIsNoBookIsNamedAsTheProblem() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val saved = SavedRouters(storage)
        val services = Services(storage, MemoryTrustStore(), null, MemorySecrets(), readWinBoxBook = { null }) { _, _, _ -> router }
        val exchange = object : FileExchange {
            override suspend fun pick() = listOf(LocalFile("Addresses.cdb") { byteArrayOf(1, 2, 3) })
            override suspend fun save(name: String, bytes: ByteArray) = false
        }
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { ConnectScreen(services, saved, rememberCoroutineScope()) { _, _, _ -> } }
            }
        }
        onNodeWithText("Import").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("not an address book of WinBox", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(saved.routers.value.isEmpty())
    }

    @Test
    fun anSshKeyIsRememberedWithThePasswordAndGoesToTheLogin() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val saved = SavedRouters(storage)
        val secrets = MemorySecrets()
        var login: RouterOsLogin? = null
        val exchange = object : FileExchange {
            override suspend fun pick() = listOf(LocalFile("id_ed25519") { "rd-test key".encodeToByteArray() })
            override suspend fun save(name: String, bytes: ByteArray) = false
        }
        val services = Services(storage, MemoryTrustStore(), null, secrets) { given, _, _ ->
            login = given
            router
        }
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { ConnectScreen(services, saved, rememberCoroutineScope()) { _, _, _ -> } }
            }
        }
        onNodeWithTag("address").performTextInput("192.0.2.41")
        onNodeWithTag("password").performTextInput("rd-test")
        onNodeWithText("Choose").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("id_ed25519")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Remember the password").performClick()
        onNodeWithTag("encoding").performClick()
        onAllNodes(hasText("Windows-1251")).onLast().performClick()
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 5_000) { login != null }
        assertEquals("rd-test key", login?.sshKey?.decodeToString())
        assertEquals(ApiEncoding.Windows1251, login?.encoding)
        waitUntil(timeoutMillis = 5_000) { runBlocking { secrets.get(sshKeyKey("192.0.2.41", "admin")) } != null }
        assertEquals("id_ed25519:" + Base64.encode("rd-test key".encodeToByteArray()), runBlocking { secrets.get(sshKeyKey("192.0.2.41", "admin")) })
        assertTrue(onAllNodes(hasTestTag("ssh-passphrase")).fetchSemanticsNodes().isEmpty(), "a key in the clear has no passphrase")
    }

    @Test
    fun anOpenedSavedRouterWaitsForItsStoredPassword() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val saved = SavedRouters(storage)
        val secrets = MemorySecrets()
        runBlocking {
            saved.save(SavedRouter("192.0.2.45", "rd-test", Access.ApiSsl))
            secrets.put(passwordKey("192.0.2.45", "rd-test"), "rd-test-stored")
        }
        var login: RouterOsLogin? = null
        val services = Services(storage, MemoryTrustStore(), null, secrets) { given, _, _ ->
            login = given
            router
        }
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, saved, rememberCoroutineScope()) { _, _, _ -> } } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("192.0.2.45")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("192.0.2.45").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { login != null }
        assertEquals("rd-test-stored", login?.password)
    }

    @Test
    fun aRemovedRouterTakesItsButtonAndPasswordWithIt() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val saved = SavedRouters(storage)
        val secrets = MemorySecrets()
        runBlocking {
            saved.save(SavedRouter("192.0.2.46", "rd-test", Access.ApiSsl))
            secrets.put(passwordKey("192.0.2.46", "rd-test"), "rd-test-stored")
        }
        val services = Services(storage, MemoryTrustStore(), null, secrets) { _, _, _ -> router }
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, saved, rememberCoroutineScope()) { _, _, _ -> } } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("192.0.2.46")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("192.0.2.46").performClick()
        onNodeWithText("Remove").performClick()
        waitUntil(timeoutMillis = 5_000) { saved.routers.value.isEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Remove")).fetchSemanticsNodes().isEmpty() }
        waitUntil(timeoutMillis = 5_000) { runBlocking { secrets.get(passwordKey("192.0.2.46", "rd-test")) } == null }
    }

    @Test
    fun anEncryptedKeyAsksForItsPassphrase() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val secrets = MemorySecrets()
        var login: RouterOsLogin? = null
        val exchange = object : FileExchange {
            override suspend fun pick() = listOf(LocalFile("id_rsa") { "-----BEGIN ENCRYPTED PRIVATE KEY-----\nrd-test\n-----END ENCRYPTED PRIVATE KEY-----\n".encodeToByteArray() })
            override suspend fun save(name: String, bytes: ByteArray) = false
        }
        val services = Services(storage, MemoryTrustStore(), null, secrets) { given, _, _ ->
            login = given
            router
        }
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(storage), rememberCoroutineScope()) { _, _, _ -> } }
            }
        }
        onNodeWithTag("address").performTextInput("192.0.2.42")
        assertTrue(onAllNodes(hasTestTag("ssh-passphrase")).fetchSemanticsNodes().isEmpty(), "no key, no passphrase")
        onNodeWithText("Choose").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("ssh-passphrase")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("ssh-passphrase").performTextInput("rd-test phrase")
        onNodeWithText("Remember the password").performClick()
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 5_000) { login != null }
        assertEquals("rd-test phrase", login?.sshPassphrase)
        waitUntil(timeoutMillis = 5_000) { runBlocking { secrets.get(sshPassphraseKey("192.0.2.42", "admin")) } == "rd-test phrase" }
    }

    @Test
    fun theSshAgentGoesToTheLoginAndStaysWithTheSavedRouter() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val saved = SavedRouters(storage)
        var login: RouterOsLogin? = null
        val services = Services(storage, MemoryTrustStore(), null, MemorySecrets(), sshAgent = true) { given, _, _ ->
            login = given
            router
        }
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, saved, rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithTag("address").performTextInput("192.0.2.42")
        onNodeWithTag("ssh-agent").performClick()
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 5_000) { saved.routers.value.isNotEmpty() }
        assertTrue(saved.routers.value.single().agent)
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 5_000) { login != null }
        assertEquals(true, login?.sshAgent)
    }

    @Test
    fun opnsenseTellsThatItTakesTheKeyOfItsApi() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val saved = SavedRouters(storage)
        val services = Services(storage, MemoryTrustStore(), null, MemorySecrets()) { _, _, _ -> router }
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, saved, rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithTag("platform").performClick()
        onAllNodes(hasText("OPNsense")).onLast().performClick()
        onNodeWithText("REST with an API key (443)").assertExists()
        onNodeWithText("User is the key of an API key", substring = true).assertExists()
        onNodeWithTag("address").performTextInput("192.0.2.43")
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 5_000) { saved.routers.value.isNotEmpty() }
        assertEquals(Access.Opnsense, saved.routers.value.single().protocol)
    }

    @Test
    fun aUserOfSnmpV3GoesWithItsProtocolsAndPrivacyPassword() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val saved = SavedRouters(storage)
        val secrets = MemorySecrets()
        var login: Login? = null
        val services = Services(storage, MemoryTrustStore(), null, secrets, connectLogin = { given, _ ->
            login = given
            router
        })
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, saved, rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithTag("platform").performClick()
        onAllNodes(hasText("Other device: SSH, SNMP, UPnP")).onLast().performClick()
        onNodeWithTag("protocol").performClick()
        onAllNodes(hasText("SNMP v3 (UDP 161)")).onLast().performClick()
        onNodeWithTag("address").performTextInput("192.0.2.44")
        onNodeWithTag("user").performTextClearance()
        onNodeWithTag("user").performTextInput("rd-test")
        onNodeWithTag("snmp-privacy").assertDoesNotExist()
        onNodeWithTag("password").performTextInput("rd-test-auth")
        onNodeWithTag("snmp-auth").performClick()
        onAllNodes(hasText("MD5")).onLast().performClick()
        onNodeWithText("Without a privacy password", substring = true).assertExists()
        onNodeWithTag("snmp-privacy").performTextInput("rd-test-privacy")
        onNodeWithText("Without a privacy password", substring = true).assertDoesNotExist()
        onNodeWithText("Remember the password").performClick()
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { secrets.get(snmpPrivacyKey("192.0.2.44", "rd-test")) } == "rd-test-privacy" }
        assertEquals(AuthProtocol.Md5, saved.routers.value.single().snmpAuth)
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 5_000) { login != null }
        val user = (login as Login.Snmp).user
        assertEquals(listOf("rd-test", "Md5", "rd-test-auth", "Aes", "rd-test-privacy"), listOf(user?.name, "${user?.auth}", user?.authPassword, "${user?.priv}", user?.privPassword))
        picture("connect-snmp3")
    }

    @Test
    fun aRouterIsReachedThroughASavedOne() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val saved = SavedRouters(storage)
        runBlocking {
            saved.save(SavedRouter("192.0.2.1", "rd-test", Access.Ssh))
            saved.save(SavedRouter("192.0.2.70", "rd-test", via = "192.0.2.99"))
        }
        var connected = false
        val services = Services(storage, MemoryTrustStore(), null, MemorySecrets()) { _, _, _ ->
            connected = true
            router
        }
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, saved, rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithTag("address").performTextInput("192.0.2.60")
        onNodeWithTag("via").performClick()
        onAllNodes(hasText("192.0.2.1")).onLast().performClick()
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 5_000) { saved.routers.value.any { it.address == "192.0.2.60" } }
        assertEquals("192.0.2.1", saved.routers.value.single { it.address == "192.0.2.60" }.via)

        onNodeWithText("192.0.2.70").performClick()
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("192.0.2.99, the router to go through, is not in the saved list")).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(connected)
    }

    @Test
    fun theBookGoesOutSealedAndComesBackWithItsPasswords() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val saved = SavedRouters(storage)
        val secrets = MemorySecrets()
        val key = passwordKey("192.0.2.31", "rd-test")
        runBlocking {
            saved.save(SavedRouter("192.0.2.31", "rd-test", comment = "CHR"))
            secrets.put(key, "rd-test-password")
        }
        val files = mutableListOf<ByteArray>()
        val exchange = object : FileExchange {
            override suspend fun pick() = listOf(LocalFile("networkhelm-routers.json") { files.single() })
            override suspend fun save(name: String, bytes: ByteArray): Boolean {
                files += bytes
                return true
            }
        }
        val services = Services(storage, MemoryTrustStore(), null, secrets) { _, _, _ -> router }
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) {
                NetworkHelmTheme(dark = true) { ConnectScreen(services, saved, rememberCoroutineScope()) { _, _, _ -> } }
            }
        }
        onNodeWithText("Export").performClick()
        onNodeWithTag("master").performTextInput("rd-test-master")
        onNodeWithTag("master-repeat").performTextInput("rd-test-master")
        picture("connect-book-export")
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 10_000) { files.isNotEmpty() }
        val text = files.single().decodeToString()
        assertTrue("networkhelm-book-1" in text && "rd-test-password" !in text && "192.0.2.31" !in text, "the file is sealed")

        runBlocking {
            saved.remove(saved.routers.value.single())
            secrets.delete(key)
        }
        onNodeWithText("Import").performClick()
        onNodeWithTag("master").performTextInput("wrong")
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("The master password does not open this file")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Import").performClick()
        onNodeWithTag("master").performTextInput("rd-test-master")
        onNodeWithText("OK").performClick()
        waitUntil(timeoutMillis = 10_000) { saved.routers.value.isNotEmpty() }
        assertEquals(listOf(SavedRouter("192.0.2.31", "rd-test", comment = "CHR")), saved.routers.value)
        assertEquals("rd-test-password", runBlocking { secrets.get(key) })
    }

    @Test
    fun neighboursAreGroupedByIdentityAndFillTheAddress() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        val storage = MemoryStorage()
        val saved = SavedRouters(storage)
        runBlocking {
            saved.save(SavedRouter("192.0.2.1", "admin", group = "Лаборатория", comment = "CHR"))
            saved.save(SavedRouter("192.0.2.2", "admin"))
        }
        val services = Services(storage, MemoryTrustStore(), neighbors) { _, _, _ -> router }
        setContent {
            NetworkHelmTheme(dark = false) {
                ConnectScreen(services, saved, rememberCoroutineScope()) { _, _, _ -> }
            }
        }
        picture("connect-light-saved-ru")
        onNodeWithText("Соседи").performClick()
        waitForIdle()
        picture("connect-light-neighbors-ru")
        assertTrue(onAllNodes(hasTextExactly("rd-test-a")).fetchSemanticsNodes().size == 1)
    }

    @Test
    fun aPortOfNeighborsThatCannotBeHeardSaysSo() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val services = Services(storage, MemoryTrustStore(), flow { throw IOException("Address already in use") }, refreshNeighbors = {}) { _, _, _ -> router }
        setContent { NetworkHelmTheme { ConnectScreen(services, SavedRouters(storage), rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithText("Neighbors").performClick()
        waitForIdle()
        onNodeWithTag("neighbors-status").assertTextContains("5678", substring = true)
    }

    @Test
    fun enterInThePasswordConnects() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        var opened: DeviceSession? = null
        val services = Services(MemoryStorage(), MemoryTrustStore(), null) { _, _, _ -> router }
        setContent { NetworkHelmTheme { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { session, _, _ -> opened = session } } }
        onNodeWithTag("address").performTextInput("192.0.2.1")
        onNodeWithTag("password").performTextInput("rd-test")
        onNodeWithTag("password").performImeAction()
        waitUntil { opened != null }
    }

    @Test
    fun enterInTheAddressConnectsToo() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        var opened: DeviceSession? = null
        val services = Services(MemoryStorage(), MemoryTrustStore(), null) { _, _, _ -> router }
        setContent { NetworkHelmTheme { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { session, _, _ -> opened = session } } }
        onNodeWithTag("address").performTextInput("192.0.2.1")
        onNodeWithTag("address").performKeyInput { pressKey(Key.Enter) }
        waitUntil { opened != null }
    }

    @Test
    fun refreshAsksTheNeighboursAndSaysWhenNoInterfaceTookTheRequest() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val heard = MutableSharedFlow<MndpSighting>(replay = 4)
        var requests = 0
        var refused = false
        val refresh: suspend () -> Unit = {
            requests++
            if (refused) throw DeviceException(ErrorCategory.General, "no network interface took the MNDP request")
            heard.emit(neighbor("rd-test-c", "02:00:00:00:00:04", "192.0.2.13", "ether1"))
        }
        val services = Services(MemoryStorage(), MemoryTrustStore(), heard, refreshNeighbors = refresh) { _, _, _ -> router }
        setContent { NetworkHelmTheme { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithText("Neighbors").performClick()
        waitUntil { onAllNodes(hasTextExactly("rd-test-c")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, requests)
        refused = true
        onNodeWithTag("neighbors-refresh").performClick()
        waitUntil { onAllNodes(hasText("The request did not go out", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, requests)
    }

    @Test
    fun restOnRouterOs6SaysWhereRestBegins() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val services = Services(MemoryStorage(), MemoryTrustStore(), neighbors) { login, _, _ ->
            assertEquals(80, login.port)
            throw ApiTrapException(null, "<html><head><title>Error 501: Not Implemented</title></head></html>")
        }
        setContent {
            NetworkHelmTheme(dark = true) {
                ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> }
            }
        }
        onNodeWithText("API-SSL (8729)").performClick()
        onNodeWithText("REST over HTTP (80)").performClick()
        onNodeWithTag("address").performTextInput("192.0.2.1")
        onNodeWithTag("password").performTextInput("secret")
        onNodeWithText("Connect").performClick()
        waitUntil { onAllNodes(hasTestTag("problem")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("problem").assertTextEquals("The router has no REST: it came with RouterOS 7.1. Connect over API-SSL or API.")
    }

    @Test
    fun portsThatGiveNoAnswerAreGreyedOut() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val services = Services(MemoryStorage(), MemoryTrustStore(), null)
        setContent { NetworkHelmTheme { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope(), probe = true) { _, _, _ -> } } }
        onNodeWithTag("address").performTextInput("127.0.0.1")
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Grey: the port gave no answer")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("address").performTextInput(":8728")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Grey: the port gave no answer")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun anAddressThatAnswersOnNoPortIsNotSwitchedToMacTelnet() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val services = Services(MemoryStorage(), MemoryTrustStore(), null)
        setContent { NetworkHelmTheme { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope(), probe = true) { _, _, _ -> } } }
        onNodeWithTag("address").performTextInput("127.0.0.1")
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Grey: the port gave no answer")).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        assertTrue(onAllNodes(hasText("MAC-Telnet (UDP 20561)")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theLocalNetworkAndTlsWithoutACertificateAreToldApart() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        var allowed = false
        val services = Services(MemoryStorage(), MemoryTrustStore(), neighbors, connect = { _, _, _ ->
            if (allowed) throw IOException("handshake", SSLHandshakeException("no cipher suites in common")) else throw SocketTimeoutException()
        }, localNetwork = { allowed })
        setContent {
            NetworkHelmTheme(dark = true) {
                ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> }
            }
        }
        onNodeWithTag("address").performTextInput("192.0.2.1")
        onNodeWithTag("password").performTextInput("secret")
        onNodeWithText("Connect").performClick()
        waitUntil { onAllNodes(hasTestTag("problem")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("problem").assertTextEquals("Android does not let NetworkHelm reach the local network. Allow Nearby devices for NetworkHelm in the settings of the app.")
        allowed = true
        onNodeWithText("Connect").performClick()
        waitUntil { onAllNodes(hasText("could not agree on TLS", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aScanOfANetworkListsWhatAnswersAndFillsTheForm() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val asked = mutableListOf<List<String>>()
        val services = Services(MemoryStorage(), MemoryTrustStore(), null, scan = { addresses ->
            asked += addresses
            flowOf(Found("192.168.88.10", listOf(22, 8728)), Found("192.168.88.1", listOf(80, 443, 8729)), Found("192.168.88.20", listOf(80, 8291)))
        })
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithText("Scan").performClick()
        onNodeWithTag("scan-network").performTextInput("192.168.88.0/33")
        onNodeWithTag("scan").assertIsNotEnabled()
        onNodeWithTag("scan-network").performTextClearance()
        onNodeWithTag("scan-network").performTextInput("192.168.88.0/24")
        onNodeWithTag("scan").performClick()
        waitUntil { onAllNodes(hasText("Answered: 3 of 254")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(254, asked.single().size)
        picture("connect-scan")
        onNodeWithText("192.168.88.1").performMouseInput { doubleClick() }
        onNodeWithTag("address").assertTextEquals("192.168.88.1")
        onNodeWithText("API-SSL (8729)").assertExists()
        onNodeWithText("192.168.88.20").performMouseInput { doubleClick() }
        onNodeWithTag("address").assertTextEquals("192.168.88.20")
        onNodeWithText("TCP 8291 (WinBox port)").assertExists()
    }

    @Test
    fun typingMacAddressSwitchesProtocolToMacTelnetWhenServicePresent() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val services = Services(MemoryStorage(), MemoryTrustStore(), null, connectMacTelnet = { _, _, _, _, _, _ -> router })
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithTag("address").performTextInput("00:11:22:33:44:55")
        waitUntil { onAllNodes(hasText("MAC-Telnet (UDP 20561)")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("MAC-Telnet (UDP 20561)").assertExists()
    }

    @Test
    fun typingMacWithoutServiceDoesNotSwitchToMacTelnet() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val services = Services(MemoryStorage(), MemoryTrustStore(), null)
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithTag("address").performTextInput("00:11:22:33:44:55")
        waitForIdle()
        onNodeWithText("API-SSL (8729)").assertExists()
    }

    @Test
    fun connectingOverMacTelnetCallsService() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        var connectedMac = ""
        val services = Services(
            MemoryStorage(),
            MemoryTrustStore(),
            null,
            networkInterfaces = { listOf("eth0 (00:AA:BB:CC:DD:EE)") },
            connectMacTelnet = { mac, _, _, _, _, _ ->
                connectedMac = mac
                router
            },
        )
        var opened = false
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> opened = true } } }
        onNodeWithTag("address").performTextInput("48:8F:5A:11:22:33")
        waitUntil { onAllNodes(hasText("MAC-Telnet (UDP 20561)")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Connect").performClick()
        waitUntil { opened }
        assertEquals("48:8F:5A:11:22:33", connectedMac)
    }

    @Test
    fun connectingOverMacTelnetWithoutAnInterfaceShowsTheProblem() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        var called = false
        val services = Services(
            MemoryStorage(),
            MemoryTrustStore(),
            null,
            networkInterfaces = { emptyList() },
            connectMacTelnet = { _, _, _, _, _, _ ->
                called = true
                router
            },
        )
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithTag("address").performTextInput("48:8F:5A:11:22:33")
        waitUntil { onAllNodes(hasText("MAC-Telnet (UDP 20561)")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Connect").performClick()
        waitUntil { onAllNodes(hasText("No network interface", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(called)
    }

    @Test
    fun aSystemThatHidesItsMacAsksForItAndKeepsIt() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        var link: String? = null
        val services = Services(
            MemoryStorage(),
            MemoryTrustStore(),
            null,
            connectMacTelnet = { _, _, _, own, _, _ ->
                link = own
                router
            },
            typedMac = true,
        )
        var opened = false
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> opened = true } } }
        onNodeWithTag("address").performTextInput("48:8F:5A:11:22:33")
        waitUntil { onAllNodes(hasText("MAC-Telnet (UDP 20561)")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Connect").performClick()
        waitUntil { onAllNodes(hasText("Type the MAC address of this device", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertNull(link)
        onNodeWithTag("own-mac").performTextInput("A6:1F:00:12:34:56")
        onNodeWithText("Connect").performClick()
        waitUntil { opened }
        assertEquals("A6:1F:00:12:34:56", link)
        assertEquals("A6:1F:00:12:34:56", runBlocking { services.storage.read("own-mac") })
    }

    @Test
    fun doubleClickingNeighborWithoutIpSelectsMacTelnet() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val neighbor = neighbor("CCR", "00:0C:42:AA:BB:CC", "0.0.0.0", "ether1")
        val services = Services(MemoryStorage(), MemoryTrustStore(), flowOf(neighbor), connectMacTelnet = { _, _, _, _, _, _ -> router })
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithText("Neighbors").performClick()
        waitUntil { onAllNodes(hasText("00:0C:42:AA:BB:CC")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("00:0C:42:AA:BB:CC").performMouseInput { doubleClick() }
        onNodeWithTag("address").assertTextEquals("00:0C:42:AA:BB:CC")
        onNodeWithText("MAC-Telnet (UDP 20561)").assertExists()
    }

    @Test
    fun aNeighborGivesItsMacOnTheMacCellAndItsAddressElsewhere() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val sighting = neighbor("rd-test-d", "02:00:00:00:00:05", "192.0.2.14", "ether1")
        val services = Services(MemoryStorage(), MemoryTrustStore(), flowOf(sighting), connectMacTelnet = { _, _, _, _, _, _ -> router })
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithText("Neighbors").performClick()
        fun cell(text: String) = onNode(hasText(text) and hasAnyAncestor(hasTestTag("table")))
        waitUntil { onAllNodes(hasText("02:00:00:00:00:05") and hasAnyAncestor(hasTestTag("table"))).fetchSemanticsNodes().isNotEmpty() }
        cell("02:00:00:00:00:05").performMouseInput { doubleClick() }
        onNodeWithTag("address").assertTextEquals("02:00:00:00:00:05")
        onNodeWithText("MAC-Telnet (UDP 20561)").assertExists()
        cell("rd-test-d").performMouseInput { doubleClick() }
        onNodeWithTag("address").assertTextEquals("192.0.2.14")
        cell("02:00:00:00:00:05").performMouseInput { doubleClick() }
        onNodeWithTag("address").assertTextEquals("02:00:00:00:00:05")
        cell("192.0.2.14").performMouseInput { doubleClick() }
        onNodeWithTag("address").assertTextEquals("192.0.2.14")
    }

    @Test
    fun connectingOverTcp8291CallsServiceWithTheAddressAndThePort() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val asked = mutableListOf<String>()
        val services = Services(
            MemoryStorage(),
            MemoryTrustStore(),
            null,
            connectTcp8291 = { host, port, user, pass, _, _ ->
                asked += listOf(host, "$port", user, pass)
                router
            },
        )
        var opened = false
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> opened = true } } }
        onNodeWithTag("protocol").performClick()
        onAllNodes(hasText("TCP 8291 (WinBox port)")).onLast().performClick()
        onNodeWithTag("address").performTextInput("192.0.2.45")
        onNodeWithTag("user").performTextClearance()
        onNodeWithTag("user").performTextInput("rd-test")
        onNodeWithTag("password").performTextInput("secret")
        onNodeWithText("Connect").performClick()
        waitUntil { opened }
        assertEquals(listOf("192.0.2.45", "8291", "rd-test", "secret"), asked)
    }

    @Test
    fun withTheServiceAtHandTheScreenStartsOnTcp8291AndListsItFirst() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals(Access.Tcp8291, DeviceKind.RouterOs.protocols.first())
        val asked = mutableListOf<String>()
        val services = Services(
            MemoryStorage(),
            MemoryTrustStore(),
            null,
            connectTcp8291 = { host, port, _, _, _, _ ->
                asked += "$host:$port"
                router
            },
        )
        var opened = false
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> opened = true } } }
        onNodeWithTag("address").performTextInput("192.0.2.45")
        onNodeWithTag("password").performTextInput("secret")
        onNodeWithText("Connect").performClick()
        waitUntil { opened }
        assertEquals(listOf("192.0.2.45:8291"), asked)
    }

    @Test
    fun theEncodingOfTheRouterGoesToTheConsolesOf8291AndOfMacTelnet() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val given = mutableListOf<ApiEncoding>()
        val services = Services(
            MemoryStorage(),
            MemoryTrustStore(),
            null,
            networkInterfaces = { listOf("eth0 (00:AA:BB:CC:DD:EE)") },
            connectMacTelnet = { _, _, _, _, encoding, _ ->
                given += encoding
                router
            },
            connectTcp8291 = { _, _, _, _, encoding, _ ->
                given += encoding
                router
            },
        )
        var opened = 0
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> opened++ } } }
        onNodeWithTag("protocol").performClick()
        onAllNodes(hasText("TCP 8291 (WinBox port)")).onLast().performClick()
        onNodeWithTag("address").performTextInput("192.0.2.45")
        onNodeWithTag("encoding").performClick()
        onAllNodes(hasText("Windows-1251")).onLast().performClick()
        onNodeWithText("Connect").performClick()
        waitUntil { opened == 1 }
        onNodeWithTag("address").performTextClearance()
        onNodeWithTag("address").performTextInput("48:8F:5A:11:22:33")
        waitUntil { onAllNodes(hasText("MAC-Telnet (UDP 20561)")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Connect").performClick()
        waitUntil { opened == 2 }
        assertEquals(listOf(ApiEncoding.Windows1251, ApiEncoding.Windows1251), given)
    }

    @Test
    fun connectingOverTcp8291WithoutServiceShowsTheProblem() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val services = Services(MemoryStorage(), MemoryTrustStore(), null)
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithTag("protocol").performClick()
        onAllNodes(hasText("TCP 8291 (WinBox port)")).onLast().performClick()
        onNodeWithTag("address").performTextInput("192.0.2.45")
        onNodeWithText("Connect").performClick()
        waitUntil { onAllNodes(hasTestTag("problem")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("problem").assertTextEquals("TCP 8291 is not supported on this platform.")
    }

    @Test
    fun theChannelOfPort8291LogsInWithThePasswordAndAsksForNoSshKey() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val services = Services(MemoryStorage(), MemoryTrustStore(), null)
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> } } }
        assertTrue(onAllNodes(hasTestTag("ssh-key")).fetchSemanticsNodes().isNotEmpty())
        onNodeWithTag("protocol").performClick()
        onAllNodes(hasText("TCP 8291 (WinBox port)")).onLast().performClick()
        assertTrue(onAllNodes(hasTestTag("ssh-key")).fetchSemanticsNodes().isEmpty())
        onNodeWithTag("protocol").performClick()
        onAllNodes(hasText("API-SSL (8729)")).onLast().performClick()
        assertTrue(onAllNodes(hasTestTag("ssh-key")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aPortOfItsOwnGoesToTheLoginAndIntoTheSavedAddress() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val saved = SavedRouters(storage)
        var login: RouterOsLogin? = null
        val services = Services(storage, MemoryTrustStore(), null, MemorySecrets()) { given, _, _ ->
            login = given
            router
        }
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, saved, rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithTag("address").performTextInput("192.0.2.42")
        onNodeWithTag("port").performTextInput("9729")
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 5_000) { saved.routers.value.isNotEmpty() }
        assertEquals("192.0.2.42:9729", saved.routers.value.single().address)
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 5_000) { login != null }
        assertEquals(9729, login?.port)
    }

    @Test
    fun aSshHostIsReachedOnThePortOfItsOwn() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        var login: Login? = null
        val services = Services(MemoryStorage(), MemoryTrustStore(), null, connectLogin = { given, _ ->
            login = given
            router
        })
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithTag("platform").performClick()
        onAllNodes(hasText("Other device: SSH, SNMP, UPnP")).onLast().performClick()
        onNodeWithTag("address").performTextInput("192.0.2.60")
        onNodeWithTag("port").performTextInput("20x22")
        onNodeWithTag("port").performTextInput("2022")
        onNodeWithTag("password").performTextInput("secret")
        picture("connect-port")
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 5_000) { login != null }
        val ssh = login as Login.Ssh
        assertEquals(listOf("192.0.2.60", 2022), listOf(ssh.host, ssh.port))
    }

    @Test
    fun anotherProtocolDropsThePortOfTheFormer() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        var login: RouterOsLogin? = null
        val services = Services(MemoryStorage(), MemoryTrustStore(), null, MemorySecrets()) { given, _, _ ->
            login = given
            router
        }
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithTag("address").performTextInput("192.0.2.42")
        onNodeWithTag("port").performTextInput("9729")
        onNodeWithTag("protocol").performClick()
        onAllNodes(hasText("API (8728)")).onLast().performClick()
        onNodeWithText("Connect").performClick()
        waitUntil(timeoutMillis = 5_000) { login != null }
        assertEquals(8728, login?.port)
    }

    @Test
    fun aPortOutOfRangeIsRefusedBeforeAnythingIsTried() = runDesktopComposeUiTest(1100, 760) {
        Locale.setDefault(Locale.ENGLISH)
        var tried = false
        val services = Services(MemoryStorage(), MemoryTrustStore(), null, MemorySecrets()) { _, _, _ ->
            tried = true
            router
        }
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithTag("address").performTextInput("192.0.2.42")
        onNodeWithTag("port").performTextInput("70000")
        onNodeWithText("Connect").performClick()
        waitUntil { onAllNodes(hasTestTag("problem")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("problem").assertTextEquals("The port is a number from 1 to 65535.")
        assertFalse(tried)
    }

    @Test
    fun theSavedListIsReachedByScrollingInAWindowTooShortForTheFormAndTheList() = runDesktopComposeUiTest(1100, 420) {
        Locale.setDefault(Locale.ENGLISH)
        val services = Services(MemoryStorage(), MemoryTrustStore(), null)
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> } } }
        onRoot().performTouchInput { swipeUp() }
        waitForIdle()
        onNodeWithText("Saved").assertIsDisplayed()
        picture("connect-short-window")
    }

    @Test
    fun theFormAndTheListsBothShowInATallWindow() = runDesktopComposeUiTest(1100, 1300) {
        Locale.setDefault(Locale.ENGLISH)
        val services = Services(MemoryStorage(), MemoryTrustStore(), null)
        setContent { NetworkHelmTheme(dark = true) { ConnectScreen(services, SavedRouters(services.storage), rememberCoroutineScope()) { _, _, _ -> } } }
        onNodeWithText("Connect").assertIsDisplayed()
        onNodeWithText("Saved").assertIsDisplayed()
        picture("connect-tall-window")
    }
}
