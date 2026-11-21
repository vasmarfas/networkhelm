package com.vasmarfas.networkhelm.ui.sync

import com.vasmarfas.networkhelm.app.AppSettings
import com.vasmarfas.networkhelm.app.MemorySecrets
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.SavedRouters
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.app.Snippets
import com.vasmarfas.networkhelm.app.passwordKey
import com.vasmarfas.networkhelm.sync.DirectoryFolder
import com.vasmarfas.networkhelm.sync.Folder
import com.vasmarfas.networkhelm.sync.FolderService
import com.vasmarfas.networkhelm.sync.Keys
import com.vasmarfas.networkhelm.sync.SYNC_FOLDER
import com.vasmarfas.networkhelm.sync.SyncHttp
import com.vasmarfas.networkhelm.sync.VAULT
import com.vasmarfas.networkhelm.sync.createVault
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustKind
import com.vasmarfas.networkhelm.ui.main.DockerTemplates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

// a folder in memory that counts the rounds by their lists of the folder of the vault
private class CountingFolder : Folder {
    private val files = HashMap<String, ByteArray>()
    var lists = 0

    override suspend fun list(directory: String): List<String> {
        if (directory == SYNC_FOLDER) lists++
        return files.keys.filter { it.substringBeforeLast('/') == directory }.map { it.substringAfterLast('/') }
    }

    override suspend fun read(path: String) = files[path]

    override suspend fun write(path: String, bytes: ByteArray) {
        files[path] = bytes
    }

    override suspend fun delete(path: String) {
        files.remove(path)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SynchronisationTest {
    private val storage = MemoryStorage()
    private val secrets = MemorySecrets()
    private val folder = createTempDirectory("networkhelm-folder").toFile()
    private var locked = false
    private var made = 0

    private fun synchronisation(lock: (() -> Boolean)? = { locked }): Synchronisation {
        val services = Services(storage, MemoryTrustStore(), null, secrets, syncLock = lock, syncFolder = { DirectoryFolder(File(it)) })
        return Synchronisation(services, SavedRouters(storage), Snippets(storage), AppSettings(storage), DockerTemplates(storage), RouterFiles(storage), { false }) { trust ->
            made++
            SyncHttp(trust)
        }
    }

    // the owner's requirement: until synchronisation is set up, and in the copy that does not hold the data folder, no HTTP of it exists
    @Test
    fun nothingThatCouldReachANetworkIsMadeUntilSynchronisationIsSetUpInTheCopyThatHoldsTheFolder() = runTest {
        val synchronisation = synchronisation()
        val running = launch { synchronisation.run() }
        runCurrent()
        assertNull(synchronisation.running.value)
        assertEquals(0, made)

        val keys = Keys("v1", 1, ByteArray(32), ByteArray(32))
        keepSyncSetup(storage, secrets, SyncSetup("v1", 1, "0f8c4a1b2c3d4e5f60718293a4b5c6d7", "desk", ServiceSettings.WebDav("https://dav.example.org/dav", "nhsync")), keys, "app password")
        synchronisation.restart()
        runCurrent()
        assertNull(synchronisation.running.value)
        assertEquals(0, made)

        // a folder needs no HTTP either
        keepSyncSetup(storage, secrets, SyncSetup("v1", 1, "0f8c4a1b2c3d4e5f60718293a4b5c6d7", "desk", ServiceSettings.Place(folder.path)), keys, "")
        locked = true
        synchronisation.restart()
        runCurrent()
        assertNotNull(synchronisation.running.value)
        assertEquals(0, made)
        running.cancel()
    }

    // a minute between rounds while the app is in front, a quarter of an hour behind it, though it went behind before the rounds started
    @Test
    fun theRoundsGoSlowerWhileTheAppIsBehind() = runTest {
        val synchronisation = synchronisation()
        keepSyncSetup(storage, secrets, SyncSetup("v1", 1, "0f8c4a1b2c3d4e5f60718293a4b5c6d7", "desk", ServiceSettings.Place(folder.path)), Keys("v1", 1, ByteArray(32), ByteArray(32)), "")
        locked = true
        synchronisation.front(false)
        val running = launch { synchronisation.run() }
        runCurrent()
        val rounds = assertNotNull(synchronisation.running.value)
        assertEquals(15.minutes, rounds.period)
        synchronisation.front(true)
        assertEquals(1.minutes, rounds.period)
        running.cancel()
    }

    // a page a router or a gateway serves works with the data of that host: a setup found there starts nothing and reads no keys
    @Test
    fun aCopyThatNeverSynchronisesLeavesASetupItFindsAlone() = runTest {
        keepSyncSetup(storage, secrets, SyncSetup("v1", 1, "0f8c4a1b2c3d4e5f60718293a4b5c6d7", "desk", ServiceSettings.Place(folder.path)), Keys("v1", 1, ByteArray(32), ByteArray(32)), "")
        val synchronisation = synchronisation(lock = null)
        val running = launch { synchronisation.run() }
        runCurrent()
        assertNull(synchronisation.setup.value)
        assertNull(synchronisation.running.value)
        assertNull(synchronisation.trouble.value)
        assertEquals(0, made)
        running.cancel()
    }

    // a password, a fingerprint, a workspace or a tunnel kept here starts a round two quiet seconds later, as an edit of a store does
    @Test
    fun aPasswordAFingerprintOrAWorkspaceKeptStartsARound() = runTest {
        val rounds = CountingFolder()
        val device = "0f8c4a1b2c3d4e5f60718293a4b5c6d7"
        val (bytes, keys) = createVault("correct horse battery staple", null, device, passes = 1, memory = 8192)
        FolderService(rounds).writeIf(VAULT, bytes, null)
        keepSyncSetup(storage, secrets, SyncSetup(keys.vault, keys.epoch, device, "desk", ServiceSettings.Place("memory")), keys, "")
        val services = Services(storage, MemoryTrustStore(), null, secrets, syncFolder = { rounds })
        val files = RouterFiles(storage)
        val synchronisation = Synchronisation(services, SavedRouters(storage), Snippets(storage), AppSettings(storage), DockerTemplates(storage), files, { false })
        val running = launch { synchronisation.run() }
        advanceTimeBy(10_000)
        val edits = listOf<suspend () -> Unit>(
            { services.secrets.put(passwordKey("192.0.2.1", "admin"), "rd-test-password") },
            { services.trust.put("192.0.2.1", 443, TrustKind.Tls, "ab12") },
            { files.change {} },
        )
        for (edit in edits) {
            val before = rounds.lists
            edit()
            advanceTimeBy(3_000)
            assertEquals(before + 1, rounds.lists)
        }
        running.cancel()
    }
}
