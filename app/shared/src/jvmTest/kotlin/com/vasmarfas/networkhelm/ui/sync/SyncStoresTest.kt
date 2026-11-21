package com.vasmarfas.networkhelm.ui.sync

import com.vasmarfas.networkhelm.app.AppSettings
import com.vasmarfas.networkhelm.app.FileStorage
import com.vasmarfas.networkhelm.app.MemorySecrets
import com.vasmarfas.networkhelm.app.SavedRouter
import com.vasmarfas.networkhelm.app.SavedRouters
import com.vasmarfas.networkhelm.app.Snippet
import com.vasmarfas.networkhelm.app.Snippets
import com.vasmarfas.networkhelm.app.Version130
import com.vasmarfas.networkhelm.app.passwordKey
import com.vasmarfas.networkhelm.app.sshKeyKey
import com.vasmarfas.networkhelm.sync.DeviceContent
import com.vasmarfas.networkhelm.sync.DirectoryFolder
import com.vasmarfas.networkhelm.sync.FolderService
import com.vasmarfas.networkhelm.sync.Hlc
import com.vasmarfas.networkhelm.sync.Keys
import com.vasmarfas.networkhelm.sync.Record
import com.vasmarfas.networkhelm.sync.SyncLocal
import com.vasmarfas.networkhelm.sync.Synchronizer
import com.vasmarfas.networkhelm.sync.VAULT
import com.vasmarfas.networkhelm.sync.createVault
import com.vasmarfas.networkhelm.sync.newDevice
import com.vasmarfas.networkhelm.sync.recordId
import com.vasmarfas.networkhelm.sync.recordJson
import com.vasmarfas.networkhelm.sync.statePath
import com.vasmarfas.networkhelm.sync.writeState
import com.vasmarfas.networkhelm.transport.FileTrustStore
import com.vasmarfas.networkhelm.transport.TrustKind
import com.vasmarfas.networkhelm.transport.Trusted
import com.vasmarfas.networkhelm.ui.main.DockerTemplates
import com.vasmarfas.networkhelm.ui.main.SavedTab
import com.vasmarfas.networkhelm.ui.main.SavedTunnel
import com.vasmarfas.networkhelm.ui.main.SavedWindow
import com.vasmarfas.networkhelm.ui.main.SavedWorkspaces
import com.vasmarfas.networkhelm.ui.main.StackTemplate
import com.vasmarfas.networkhelm.ui.main.envNames
import com.vasmarfas.networkhelm.ui.theme.ThemeFile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "router password 7Qp"
private val HERE = "3f".repeat(32)
private val THERE = "c4".repeat(32)
private val tabJson = Json { ignoreUnknownKeys = true }
private val tunnels = ListSerializer(SavedTunnel.serializer())

class SyncStoresTest {
    private val serviceFolder = createTempDirectory("networkhelm-service").toFile()
    private val service = FolderService(DirectoryFolder(serviceFolder))
    private lateinit var keys: Keys

    private suspend fun vault() {
        val (bytes, made) = createVault("correct horse battery staple", null, newDevice(), passes = 1, memory = 8192)
        service.writeIf(VAULT, bytes, null)
        keys = made
    }

    // a device of the vault with its stores in a data folder of its own, the folder of another copy of the app where two share one; a
    // device that does not trust keeps no fingerprints from one run to the next, as a page in a browser
    private inner class Device(val folder: File = createTempDirectory("networkhelm-device").toFile(), withSecrets: Boolean = false, trusts: Boolean = true) {
        val storage = FileStorage(folder)
        val secrets = MemorySecrets()
        val routers = SavedRouters(storage)
        val snippets = Snippets(storage)
        val settings = AppSettings(storage)
        val templates = DockerTemplates(storage)
        val trust = FileTrustStore(File(folder, "known-routers"))
        val trusted = TrustRecords(trust, storage)
        val files = RouterFiles(storage)
        private val stores = SyncStores(routers, snippets, settings, templates, trusted.takeIf { trusts }, files, secrets, storage, emptySet(), withSecrets)

        // what the user does while a round runs, after it read the stores and before the merged records go in
        var between: suspend () -> Unit = {}
        lateinit var sync: Synchronizer

        suspend fun start(): Device {
            settings.load()
            routers.load()
            snippets.load()
            templates.load()
            val local = object : SyncLocal by stores {
                override suspend fun apply(records: List<Record>) {
                    between()
                    stores.apply(records)
                }
            }
            sync = Synchronizer(service, keys, newDevice(), DeviceContent("test", "test", "1.4.0", 0), local, storage)
            return this
        }
    }

    @Test
    fun routersSnippetsSharedSettingsThemesAndTemplatesReachTheOtherDevice() = runTest {
        vault()
        val a = Device().start()
        val b = Device().start()
        a.routers.save(SavedRouter("192.0.2.1", "admin", group = "lab", comment = "core"))
        a.secrets.put(passwordKey("192.0.2.1", "admin"), PASSWORD)
        a.snippets.save(emptyList(), listOf(Snippet("Routes", "/ip route print")))
        a.settings.change { it.copy(hidePasswords = false, scale = 1.5f) }
        a.settings.keep(ThemeFile("Mine"))
        a.templates.put(StackTemplate("web", "services: {}"))
        a.sync.round()
        b.sync.round()

        assertEquals(a.routers.routers.value, b.routers.routers.value)
        assertEquals(listOf(Snippet("Routes", "/ip route print")), b.snippets.all.value)
        assertFalse(b.settings.current.hidePasswords)
        assertEquals(1f, b.settings.current.scale)
        assertEquals(listOf("Mine"), b.settings.themes.map { it.name })
        assertEquals(listOf(StackTemplate("web", "services: {}")), b.templates.all.value)
        assertNull(b.secrets.get(passwordKey("192.0.2.1", "admin")))
        // the list stays one that 1.3.0 reads
        val listed = assertNotNull(b.storage.read(SavedRouters.FILE))
        val older = Version130.listJson.decodeFromString(ListSerializer(Version130.SavedRouter.serializer()), listed)
        assertEquals(listOf(Version130.SavedRouter("192.0.2.1", "admin", group = "lab", comment = "core")), older)
    }

    // a device with no file of settings yet takes the shared ones with its first round; a setting the user changes while a round runs
    // stays, and the next round brings the rest and takes the change to the other device
    @Test
    fun aSettingChangedWhileARoundRunsStaysAndTheRestComesWithTheNextRound() = runTest {
        vault()
        val a = Device().start()
        a.settings.change { it.copy(hidePasswords = false, checkUpdates = false) }
        a.sync.round()
        val b = Device().start()
        b.between = { b.settings.change { it.copy(confirmRemoval = false) } }
        b.sync.round()
        assertFalse(b.settings.current.confirmRemoval)
        assertTrue(b.settings.current.hidePasswords)
        b.between = {}
        b.sync.round()
        assertFalse(b.settings.current.confirmRemoval)
        assertFalse(b.settings.current.hidePasswords)
        assertFalse(b.settings.current.checkUpdates)
        a.sync.round()
        assertFalse(a.settings.current.confirmRemoval)
    }

    @Test
    fun aRouterRemovedOnOneDeviceGoesFromTheOther() = runTest {
        vault()
        val a = Device().start()
        val b = Device().start()
        a.routers.save(SavedRouter("192.0.2.1", "admin"))
        a.routers.save(SavedRouter("192.0.2.2", "admin"))
        a.sync.round()
        b.sync.round()
        b.routers.remove(SavedRouter("192.0.2.1", "admin"))
        b.sync.round()
        a.sync.round()
        assertEquals(listOf(SavedRouter("192.0.2.2", "admin")), a.routers.routers.value)
    }

    // the value goes from the protected store of one device to that of the other; files hold a keyed hash of it, the service its cipher
    @Test
    fun passwordsGoOnlyWhenLetAndNeverLieInAFileInTheClear() = runTest {
        vault()
        val a = Device(withSecrets = true).start()
        val b = Device(withSecrets = true).start()
        val kept = Device().start()
        a.routers.save(SavedRouter("192.0.2.1", "admin"))
        a.secrets.put(passwordKey("192.0.2.1", "admin"), PASSWORD)
        a.secrets.put(sshKeyKey("192.0.2.1", "admin"), "private key 2Hx")
        a.sync.round()
        b.sync.round()
        kept.sync.round()
        assertEquals(PASSWORD, b.secrets.get(passwordKey("192.0.2.1", "admin")))
        assertEquals("private key 2Hx", b.secrets.get(sshKeyKey("192.0.2.1", "admin")))
        assertNull(kept.secrets.get(passwordKey("192.0.2.1", "admin")))
        for (file in listOf(a.folder, b.folder, kept.folder, serviceFolder).flatMap { it.walk().filter(File::isFile).toList() }) {
            val text = file.readBytes().decodeToString()
            assertFalse(PASSWORD in text || "private key 2Hx" in text, file.path)
        }

        a.secrets.delete(passwordKey("192.0.2.1", "admin"))
        a.sync.round()
        b.sync.round()
        assertNull(b.secrets.get(passwordKey("192.0.2.1", "admin")))
        assertEquals("private key 2Hx", b.secrets.get(sshKeyKey("192.0.2.1", "admin")))
    }

    // a template goes to the other devices without the box of passwords and lies in a plain file: its .env keeps the names of the
    // variables, and none of their values
    @Test
    fun aTemplateKeepsTheNamesOfTheVariablesOfItsEnvAndNoneOfTheirValues() {
        val env = "# the database\nDB_USER=nh\nexport DB_PASSWORD = rd-test-secret\n\nPORT=8080\nDB_USER=again\nDEBUG\n"
        assertEquals("DB_USER=\nDB_PASSWORD=\nPORT=\nDEBUG=\n", envNames(env))
        assertEquals("", envNames(""))
    }

    // a secret this device cannot place, as one a newer version sends without its router, keeps its value in the state alone
    @Test
    fun aSecretWithoutItsRouterLiesInNoFileInTheClear() = runTest {
        vault()
        val b = Device(withSecrets = true).start()
        val other = newDevice()
        val orphan = Record("secret.password", recordId("lost"), Hlc(1_800_000_000_000, 0, "e"), data = JsonObject(mapOf("value" to JsonPrimitive("orphan value 7Qz"))))
        service.write(statePath(other), writeState(keys, other, 1, listOf(orphan)))
        b.sync.round()
        for (file in b.folder.walk().filter(File::isFile)) assertFalse("orphan value 7Qz" in file.readBytes().decodeToString(), file.path)
    }

    // a newer version added a field to a router: the file of the routers stays as this version and 1.3.0 read it, the records keep the
    // field and the stamp of the newer device, and matching the file stamps nothing anew
    @Test
    fun aFieldOfANewerVersionStaysInTheRecordsAndOutOfTheFile() = runTest {
        vault()
        val b = Device().start()
        val newer = newDevice()
        val data = recordJson.encodeToJsonElement(SavedRouter.serializer(), SavedRouter("192.0.2.9", "admin")).jsonObject + ("colour" to JsonPrimitive("red"))
        val record = Record("router", recordId("192.0.2.9", "admin"), Hlc(1_700_000_000_000, 0, "newer"), data = JsonObject(data))
        service.write(statePath(newer), writeState(keys, newer, 1, listOf(record)))
        b.sync.round()
        assertEquals(listOf(SavedRouter("192.0.2.9", "admin")), b.routers.routers.value)
        assertFalse("colour" in assertNotNull(b.storage.read(SavedRouters.FILE)))
        b.routers.load()
        val kept = b.routers.records.all.single()
        assertEquals(JsonPrimitive("red"), kept.data["colour"])
        assertEquals("newer", kept.hlc.device)
    }

    @Test
    fun theFilesOf130ReachTheOtherDeviceWhole() = runTest {
        vault()
        val folder = createTempDirectory("networkhelm-130").toFile()
        val routers = listOf(Version130.SavedRouter("192.0.2.1", "admin", group = "lab"), Version130.SavedRouter("192.0.2.3", "monitor", comment = "snmp"))
        File(folder, SavedRouters.FILE).writeText(Version130.listJson.encodeToString(ListSerializer(Version130.SavedRouter.serializer()), routers))
        val a = Device(folder).start()
        val b = Device().start()
        a.sync.round()
        b.sync.round()
        assertEquals(listOf(SavedRouter("192.0.2.3", "monitor", comment = "snmp"), SavedRouter("192.0.2.1", "admin", group = "lab")), b.routers.routers.value)
        val listed = assertNotNull(a.storage.read(SavedRouters.FILE))
        assertEquals(routers, Version130.listJson.decodeFromString(ListSerializer(Version130.SavedRouter.serializer()), listed))
        assertTrue(File(folder, "routers-1.3.json").isFile)
    }

    // the trust of one device spares the others the question; a line of another form stays in its file and goes nowhere, and a device
    // that keeps no fingerprints passes them on without taking them away
    @Test
    fun aFingerprintTrustedOnOneDeviceIsTrustedOnTheOthers() = runTest {
        vault()
        val a = Device().start()
        val browser = Device(trusts = false).start()
        val b = Device().start()
        a.trust.put("192.0.2.1", 8729, TrustKind.Tls, HERE)
        File(a.folder, "known-routers").appendText("Quic 192.0.2.7 443 $THERE\n")
        a.sync.round()
        browser.sync.round()
        browser.sync.round()
        b.sync.round()
        assertEquals(listOf(Trusted("192.0.2.1", 8729, TrustKind.Tls, HERE)), b.trust.all())
        assertTrue("Quic 192.0.2.7 443 $THERE" in File(a.folder, "known-routers").readText())
    }

    // both devices trusted a key for one address before the vault: the merge picks one, and the device whose key lost is asked while it
    // keeps its own, with no writes on and on. Insisting on its key turns the question to the other device; taking the other key ends it
    @Test
    fun twoFingerprintsOfOneAddressAreNotChosenSilently() = runTest {
        vault()
        val a = Device().start()
        val b = Device().start()
        a.trust.put("192.0.2.1", 8729, TrustKind.Tls, HERE)
        b.trust.put("192.0.2.1", 8729, TrustKind.Tls, THERE)
        a.sync.round()
        b.sync.round()
        a.sync.round()
        assertEquals(HERE, a.trust.get("192.0.2.1", 8729, TrustKind.Tls))
        assertEquals(THERE, b.trust.get("192.0.2.1", 8729, TrustKind.Tls))
        val (asked, other) = if (a.trusted.disputes.value.isEmpty()) b to a else a to b
        val dispute = asked.trusted.disputes.value.single()
        assertEquals(other.trust.get("192.0.2.1", 8729, TrustKind.Tls), dispute.there)
        assertTrue(other.trusted.disputes.value.isEmpty())
        assertFalse(a.sync.round().wrote)
        assertFalse(b.sync.round().wrote)

        asked.trusted.settle(dispute, take = false)
        asked.sync.round()
        other.sync.round()
        assertTrue(asked.trusted.disputes.value.isEmpty())
        val turned = other.trusted.disputes.value.single()
        assertEquals(asked.trust.get("192.0.2.1", 8729, TrustKind.Tls), turned.there)
        other.trusted.settle(turned, take = true)
        other.sync.round()
        asked.sync.round()
        assertEquals(asked.trust.get("192.0.2.1", 8729, TrustKind.Tls), other.trust.get("192.0.2.1", 8729, TrustKind.Tls))
        assertTrue(a.trusted.disputes.value.isEmpty() && b.trusted.disputes.value.isEmpty())
    }

    // the files of a router as its tab writes them: the named workspaces and the tunnels go to the other device, the workspace each
    // device shows and the layout without a name stay, a removal comes back and the tunnels of a device keep their order
    @Test
    fun namedWorkspacesAndTunnelsOfARouterReachTheOtherDevice() = runTest {
        vault()
        val a = Device().start()
        val b = Device().start()
        val window = SavedWindow("Firewall", listOf(SavedTab("Filter Rules", "ip/firewall/filter")), 0, 120f, 40f, 640f, 360f)
        a.files.opened("rd-test-id")
        a.storage.write("workspaces-rd-test-id.json", tabJson.encodeToString(SavedWorkspaces.serializer(), SavedWorkspaces("Firewall", mapOf("Firewall" to listOf(window), "Empty" to emptyList()))))
        a.storage.write("workspace-rd-test-id.json", "[]")
        a.storage.write("tunnels-rd-test-id.json", tabJson.encodeToString(tunnels, listOf(SavedTunnel(8080, "10.0.0.2", 80))))
        a.sync.round()
        b.sync.round()
        val came = tabJson.decodeFromString(SavedWorkspaces.serializer(), assertNotNull(b.storage.read("workspaces-rd-test-id.json")))
        assertEquals("", came.current)
        assertEquals(setOf("Firewall", "Empty"), came.layouts.keys)
        assertEquals(120f, came.layouts.getValue("Firewall").single().x)
        assertNull(b.storage.read("workspace-rd-test-id.json"))
        assertEquals(listOf(SavedTunnel(8080, "10.0.0.2", 80)), tabJson.decodeFromString(tunnels, assertNotNull(b.storage.read("tunnels-rd-test-id.json"))))

        b.storage.write("workspaces-rd-test-id.json", tabJson.encodeToString(SavedWorkspaces.serializer(), SavedWorkspaces("", mapOf("Firewall" to listOf(window)))))
        b.storage.write("tunnels-rd-test-id.json", tabJson.encodeToString(tunnels, listOf(SavedTunnel(2222, "10.0.0.3", 22), SavedTunnel(8080, "10.0.0.2", 80))))
        b.sync.round()
        a.sync.round()
        val back = tabJson.decodeFromString(SavedWorkspaces.serializer(), assertNotNull(a.storage.read("workspaces-rd-test-id.json")))
        assertEquals("Firewall", back.current)
        assertEquals(setOf("Firewall"), back.layouts.keys)
        assertEquals(listOf(SavedTunnel(8080, "10.0.0.2", 80), SavedTunnel(2222, "10.0.0.3", 22)), tabJson.decodeFromString(tunnels, assertNotNull(a.storage.read("tunnels-rd-test-id.json"))))
        assertFalse(a.sync.round().wrote)
    }

    // the copy that holds the lock of the folder runs the rounds and takes what the other copy saved there
    @Test
    fun ofTwoCopiesOnOneFolderOneSynchronisesAndTakesTheEditsOfTheOther() = runTest {
        vault()
        val folder = createTempDirectory("networkhelm-copies").toFile()
        val first = syncLock(folder)
        assertTrue(first())
        assertFalse(syncLock(folder)())
        val syncing = Device(folder).start()
        val other = Device(folder)
        other.routers.load()
        other.routers.save(SavedRouter("192.0.2.5", "admin"))
        syncing.sync.round()
        val b = Device().start()
        b.sync.round()
        assertEquals(listOf(SavedRouter("192.0.2.5", "admin")), b.routers.routers.value)
        assertTrue(first())
    }
}
