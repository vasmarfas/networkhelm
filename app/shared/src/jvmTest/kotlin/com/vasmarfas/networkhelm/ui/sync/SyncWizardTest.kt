package com.vasmarfas.networkhelm.ui.sync

import com.vasmarfas.networkhelm.app.MemorySecrets
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.sync.DeviceContent
import com.vasmarfas.networkhelm.sync.DirectoryFolder
import com.vasmarfas.networkhelm.sync.FolderService
import com.vasmarfas.networkhelm.sync.KeyChangedException
import com.vasmarfas.networkhelm.sync.SECRET
import com.vasmarfas.networkhelm.sync.SYNC_FOLDER
import com.vasmarfas.networkhelm.sync.SYNC_MEMORY
import com.vasmarfas.networkhelm.sync.Sodium
import com.vasmarfas.networkhelm.sync.SyncMemory
import com.vasmarfas.networkhelm.sync.VAULT
import com.vasmarfas.networkhelm.sync.devicePath
import com.vasmarfas.networkhelm.sync.secretKeyOf
import com.vasmarfas.networkhelm.sync.secretKeyText
import com.vasmarfas.networkhelm.sync.statePath
import com.vasmarfas.networkhelm.sync.transfer
import com.vasmarfas.networkhelm.sync.writeDevice
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct horse battery staple"
private const val NEW_PASSWORD = "lantern orbit maple quiet dune"

class SyncWizardTest {
    private val folder = createTempDirectory("networkhelm-service").toFile()
    private val place = ServiceSettings.Place(folder.path, "networkhelm-test")

    // a device with its stores in memory; a folder needs no HTTP, and none is made
    private inner class Device {
        val storage = MemoryStorage()
        val secrets = MemorySecrets()
        var started = 0
        val wizard = SyncWizard(
            Services(storage, MemoryTrustStore(), null, secrets, syncFolder = { DirectoryFolder(File(it)) }),
            { error("a folder needs no HTTP") },
            { started++ },
            passes = 1,
            memory = 8192,
        )
    }

    @Test
    fun aVaultMadeOnOneDeviceIsJoinedWithThePasswordOrTheCodeOfThatDevice() = runTest {
        val first = Device()
        val empty = first.wizard.reach(place, "")
        assertNull(empty.vault)
        first.wizard.create(empty, PASSWORD, null, Choice("desk", setOf("tunnel"), true))
        val made = assertNotNull(syncSetup(first.storage))
        assertEquals(1, first.started)
        assertEquals(setOf("tunnel"), made.leftOut)
        assertTrue(made.secrets)
        assertTrue(Regex("[0-9a-f]{32}").matches(made.device))

        val second = Device()
        val found = second.wizard.reach(place, "")
        assertNotNull(found.vault)
        assertFailsWith<DeviceException> { second.wizard.join(found, "not the password 9Kw", null, Choice("phone", emptySet(), false)) }
        assertNull(syncSetup(second.storage))
        second.wizard.join(found, PASSWORD, null, Choice("phone", emptySet(), false))
        val joined = assertNotNull(syncSetup(second.storage))
        assertEquals(made.vault, joined.vault)
        assertNotEquals(made.device, joined.device)
        assertContentEquals(assertNotNull(syncKeys(made, first.secrets)).key, assertNotNull(syncKeys(joined, second.secrets)).key)

        // the code of a folder holds no service: the new device picks its own folder, and sees the devices of the vault before it joins
        val service = FolderService(DirectoryFolder(folder))
        service.write(devicePath(made.device), writeDevice(assertNotNull(syncKeys(made, first.secrets)), made.device, 1, DeviceContent("desk", "Windows 11", "1.4.0", 1)))
        val moved = transfer(first.wizard.shownCode(PASSWORD))
        assertNull(moved.service)
        val third = Device()
        assertEquals(listOf("desk"), third.wizard.devices(third.wizard.reach(place, ""), moved))
        third.wizard.join(third.wizard.reach(place, ""), moved, Choice("tablet", emptySet(), false))
        assertContentEquals(syncKeys(made, first.secrets)?.key, syncKeys(assertNotNull(syncSetup(third.storage)), third.secrets)?.key)
        assertFailsWith<DeviceException> { first.wizard.shownCode("not the password 9Kw") }
    }

    // with extra protection a new device needs the secret key as well as the password; the code of a device brings the key along
    @Test
    fun aVaultWithASecretKeyAsksForItAndTheCodeBringsIt() = runTest {
        val first = Device()
        val key = Sodium.random(SECRET)
        first.wizard.create(first.wizard.reach(place, ""), PASSWORD, key, Choice("desk", emptySet(), false))
        val second = Device()
        val found = second.wizard.reach(place, "")
        assertTrue(found.secretKey)
        val refused = assertFailsWith<DeviceException> { second.wizard.join(found, PASSWORD, null, Choice("phone", emptySet(), false)) }
        assertEquals("the vault needs its secret key as well as the password", refused.message)
        second.wizard.join(found, PASSWORD, secretKeyOf(secretKeyText(key)), Choice("phone", emptySet(), false))
        assertContentEquals(key, syncSecretKey(second.secrets))

        val third = Device()
        third.wizard.join(third.wizard.reach(place, ""), transfer(first.wizard.shownCode(PASSWORD)), Choice("tablet", emptySet(), false))
        assertContentEquals(key, syncSecretKey(third.secrets))
    }

    @Test
    fun aVaultAnotherDeviceMadeMeanwhileIsNotWrittenOver() = runTest {
        val first = Device()
        val second = Device()
        val firstSaw = first.wizard.reach(place, "")
        val secondSaw = second.wizard.reach(place, "")
        first.wizard.create(firstSaw, PASSWORD, null, Choice("desk", emptySet(), false))
        val error = assertFailsWith<DeviceException> { second.wizard.create(secondSaw, "another phrase of five words", null, Choice("phone", emptySet(), false)) }
        assertEquals("another device put a vault on the service meanwhile: join it", error.message)
        assertNull(syncSetup(second.storage))
        second.wizard.join(second.wizard.reach(place, ""), PASSWORD, null, Choice("phone", emptySet(), false))
        assertEquals(syncSetup(first.storage)?.vault, syncSetup(second.storage)?.vault)
    }

    // a new password wraps the same key: the vault opens with it alone, and a device that joins with it reads what the others wrote
    @Test
    fun aNewPasswordKeepsTheKeyOfTheData() = runTest {
        val first = Device()
        first.wizard.create(first.wizard.reach(place, ""), PASSWORD, null, Choice("desk", emptySet(), false))
        val second = Device()
        second.wizard.join(second.wizard.reach(place, ""), PASSWORD, null, Choice("phone", emptySet(), false))
        assertFailsWith<DeviceException> { first.wizard.changePassword("not the password 9Kw", NEW_PASSWORD, newKey = false) }
        assertNull(first.wizard.changePassword(PASSWORD, NEW_PASSWORD, newKey = false))
        assertEquals(2, first.started)
        val third = Device()
        val found = third.wizard.reach(place, "")
        assertFailsWith<DeviceException> { third.wizard.join(found, PASSWORD, null, Choice("tablet", emptySet(), false)) }
        third.wizard.join(found, NEW_PASSWORD, null, Choice("tablet", emptySet(), false))
        suspend fun key(device: Device) = syncKeys(assertNotNull(syncSetup(device.storage)), device.secrets)?.key
        assertContentEquals(key(second), key(third))
        assertContentEquals(key(first), key(third))
    }

    // the device turned off leaves the service with its files, a new key comes with the new password, and a device with the earlier key
    // changes nothing until it takes the new password
    @Test
    fun aDeviceTurnedOffLeavesAndTheOthersTakeTheNewPassword() = runTest {
        val first = Device()
        first.wizard.create(first.wizard.reach(place, ""), PASSWORD, null, Choice("desk", emptySet(), false))
        val second = Device()
        second.wizard.join(second.wizard.reach(place, ""), PASSWORD, null, Choice("phone", emptySet(), false))
        val lost = Device()
        lost.wizard.join(lost.wizard.reach(place, ""), PASSWORD, null, Choice("old phone", emptySet(), false))
        val gone = assertNotNull(syncSetup(lost.storage)).device
        val service = FolderService(DirectoryFolder(folder))
        for (path in listOf(statePath(gone), devicePath(gone))) service.write(path, "rd-test".encodeToByteArray())

        assertFailsWith<DeviceException> { first.wizard.disable(gone, "not the password 9Kw", NEW_PASSWORD) }
        assertEquals(1, assertNotNull(syncSetup(first.storage)).epoch)
        assertNull(first.wizard.disable(gone, PASSWORD, NEW_PASSWORD))
        assertNull(service.read(statePath(gone)))
        assertNull(service.read(devicePath(gone)))
        assertEquals(2, assertNotNull(syncSetup(first.storage)).epoch)
        assertFailsWith<KeyChangedException> { second.wizard.changePassword(PASSWORD, "a password from the earlier key", newKey = false) }
        assertFailsWith<DeviceException> { second.wizard.newKey(PASSWORD, null) }
        second.wizard.newKey(NEW_PASSWORD, null)
        assertEquals(2, assertNotNull(syncSetup(second.storage)).epoch)
        assertContentEquals(syncKeys(assertNotNull(syncSetup(first.storage)), first.secrets)?.key, syncKeys(assertNotNull(syncSetup(second.storage)), second.secrets)?.key)
    }

    // a new key of the data brings a new secret key, shown once: the one a device turned off holds, and each code shown before, opens
    // nothing written after
    @Test
    fun aNewKeyOfTheDataBringsANewSecretKey() = runTest {
        val first = Device()
        val key = Sodium.random(SECRET)
        first.wizard.create(first.wizard.reach(place, ""), PASSWORD, key, Choice("desk", emptySet(), false))
        val second = Device()
        second.wizard.join(second.wizard.reach(place, ""), PASSWORD, key, Choice("phone", emptySet(), false))
        val renewed = assertNotNull(secretKeyOf(assertNotNull(first.wizard.changePassword(PASSWORD, NEW_PASSWORD, newKey = true))))
        assertFalse(renewed.contentEquals(key))
        assertContentEquals(renewed, syncSecretKey(first.secrets))
        assertEquals(2, assertNotNull(syncSetup(first.storage)).epoch)
        assertTrue(second.wizard.asksSecretKey())
        assertFailsWith<DeviceException> { second.wizard.newKey(NEW_PASSWORD, null) }
        second.wizard.newKey(NEW_PASSWORD, renewed)
        assertContentEquals(renewed, syncSecretKey(second.secrets))
        assertEquals(2, assertNotNull(syncSetup(second.storage)).epoch)
    }

    // a forgotten password is replaced without the current one, and the key of the data changes with it. A vault the service gave back
    // with an earlier key is wrapped under no new password and opens no key here; the new password set without the current one sets it right
    @Test
    fun aForgottenPasswordChangesTheKeyAndAVaultGivenBackIsNotTaken() = runTest {
        val first = Device()
        first.wizard.create(first.wizard.reach(place, ""), PASSWORD, null, Choice("desk", emptySet(), false))
        val second = Device()
        second.wizard.join(second.wizard.reach(place, ""), PASSWORD, null, Choice("phone", emptySet(), false))
        val service = FolderService(DirectoryFolder(folder))
        val before = assertNotNull(service.read(VAULT)).bytes
        assertNull(first.wizard.forgotPassword(NEW_PASSWORD))
        assertEquals(2, assertNotNull(syncSetup(first.storage)).epoch)

        service.write(VAULT, before)
        val givenBack = "the service gave back a vault with an earlier key than this device saw"
        assertEquals(givenBack, assertFailsWith<DeviceException> { first.wizard.changePassword(NEW_PASSWORD, "a third password of five words", newKey = false) }.message)
        assertEquals(givenBack, assertFailsWith<DeviceException> { first.wizard.shownCode(NEW_PASSWORD) }.message)
        assertEquals(givenBack, assertFailsWith<DeviceException> { second.wizard.newKey(PASSWORD, null) }.message)
        // a device that saw the newer key in a round wraps nothing under a new password either
        second.storage.write(SYNC_MEMORY, Json.encodeToString(SyncMemory.serializer(), SyncMemory(epoch = 2)))
        assertFailsWith<KeyChangedException> { second.wizard.changePassword(PASSWORD, "a third password of five words", newKey = false) }

        first.wizard.forgotPassword("a third password of five words")
        assertEquals(3, assertNotNull(syncSetup(first.storage)).epoch)
        second.wizard.newKey("a third password of five words", null)
        assertEquals(3, assertNotNull(syncSetup(second.storage)).epoch)
    }

    // the password, token or key of a service goes over HTTPS only, the login of a gateway too
    @Test
    fun aServiceOnANetworkIsReachedOverHttpsOnly() = runTest {
        val device = Device()
        val error = assertFailsWith<DeviceException> { device.wizard.reach(ServiceSettings.WebDav("http://dav.example.org/dav", "nhsync"), "rd-test-app-password") }
        assertEquals("the address of the service must start with https://", error.message)
        assertFailsWith<DeviceException> { device.wizard.gatewayLogin("http://192.0.2.10:8443", "admin", "rd-test", "123456") }
    }

    // the vault moves whole to another folder with the same keys; a device that comes there later finds it moved and goes along
    @Test
    fun theVaultMovesWholeToAnotherService() = runTest {
        val first = Device()
        first.wizard.create(first.wizard.reach(place, ""), PASSWORD, null, Choice("desk", emptySet(), false))
        val second = Device()
        second.wizard.join(second.wizard.reach(place, ""), PASSWORD, null, Choice("phone", emptySet(), false))
        val elsewhere = ServiceSettings.Place(createTempDirectory("networkhelm-moved").toFile().path, "moved")
        first.wizard.move(first.wizard.reach(elsewhere, ""))
        assertEquals(elsewhere, assertNotNull(syncSetup(first.storage)).service)
        second.wizard.move(second.wizard.reach(elsewhere, ""))
        assertEquals(elsewhere, assertNotNull(syncSetup(second.storage)).service)
        val third = Device()
        third.wizard.join(third.wizard.reach(elsewhere, ""), PASSWORD, null, Choice("tablet", emptySet(), false))
        assertEquals(syncSetup(first.storage)?.vault, syncSetup(third.storage)?.vault)
    }

    // one more folder keeps a copy of the vault: it gets the vault alone, and a device joins the vault there. The device leaves a place with
    // its files, the first place left becomes the one of the setup with its memory, and the last place stays
    @Test
    fun aPlaceIsAddedWithTheVaultAloneAndLeftWithTheFilesOfTheDevice() = runTest {
        val first = Device()
        first.wizard.create(first.wizard.reach(place, ""), PASSWORD, null, Choice("desk", emptySet(), false))
        val made = assertNotNull(syncSetup(first.storage))
        val service = FolderService(DirectoryFolder(folder))
        service.write(devicePath("a".repeat(32)), "rd-test".encodeToByteArray())
        val second = createTempDirectory("networkhelm-second").toFile()
        val elsewhere = ServiceSettings.Place(second.path, "second")
        first.wizard.addPlace(first.wizard.reach(elsewhere, ""))
        val added = assertNotNull(syncSetup(first.storage)).more.single()
        assertEquals(elsewhere, added.service)
        assertEquals(2, first.started)
        val copy = FolderService(DirectoryFolder(second))
        assertContentEquals(assertNotNull(service.read(VAULT)).bytes, assertNotNull(copy.read(VAULT)).bytes)
        assertEquals(listOf(VAULT), copy.list().map { it.path })
        for (taken in listOf(place, elsewhere)) {
            assertEquals("the vault is kept there already", assertFailsWith<DeviceException> { first.wizard.addPlace(first.wizard.reach(taken, "")) }.message)
        }
        val other = Device()
        other.wizard.join(other.wizard.reach(elsewhere, ""), PASSWORD, null, Choice("phone", emptySet(), false))
        assertEquals(made.vault, syncSetup(other.storage)?.vault)

        for (files in listOf(service, copy)) for (path in listOf(statePath(made.device), devicePath(made.device))) files.write(path, "rd-test".encodeToByteArray())
        first.storage.write(placeMemory(added.id), "the memory of the second place")
        first.wizard.removePlace("")
        val left = assertNotNull(syncSetup(first.storage))
        assertEquals(elsewhere, left.service)
        assertTrue(left.more.isEmpty())
        assertEquals("the memory of the second place", first.storage.read(SYNC_MEMORY))
        assertEquals("", first.storage.read(placeMemory(added.id)))
        assertNull(service.read(statePath(made.device)))
        assertNull(service.read(devicePath(made.device)))
        assertNotNull(service.read(VAULT))
        assertNotNull(copy.read(statePath(made.device)))
        first.wizard.removePlace("")
        assertEquals(elsewhere, assertNotNull(syncSetup(first.storage)).service)
    }

    // a device of one place alone changes the key, and the device in both places takes the new password from the vault there; turning a
    // device off takes its files from every place
    @Test
    fun aNewKeyMadeInOnePlaceIsTakenFromThereAndADeviceTurnedOffLeavesEveryPlace() = runTest {
        val first = Device()
        first.wizard.create(first.wizard.reach(place, ""), PASSWORD, null, Choice("desk", emptySet(), false))
        val second = createTempDirectory("networkhelm-second").toFile()
        val elsewhere = ServiceSettings.Place(second.path, "second")
        first.wizard.addPlace(first.wizard.reach(elsewhere, ""))
        val other = Device()
        other.wizard.join(other.wizard.reach(elsewhere, ""), PASSWORD, null, Choice("phone", emptySet(), false))
        assertNull(other.wizard.changePassword(PASSWORD, NEW_PASSWORD, newKey = true))
        first.wizard.newKey(NEW_PASSWORD, null)
        assertEquals(2, assertNotNull(syncSetup(first.storage)).epoch)

        val services = listOf(FolderService(DirectoryFolder(folder)), FolderService(DirectoryFolder(second)))
        // a round of the device in both places puts the newest vault into the first place
        services[0].write(VAULT, assertNotNull(services[1].read(VAULT)).bytes)
        val lost = Device()
        lost.wizard.join(lost.wizard.reach(elsewhere, ""), NEW_PASSWORD, null, Choice("old phone", emptySet(), false))
        val gone = assertNotNull(syncSetup(lost.storage)).device
        for (service in services) for (path in listOf(statePath(gone), devicePath(gone))) service.write(path, "rd-test".encodeToByteArray())
        assertNull(first.wizard.disable(gone, NEW_PASSWORD, "a third password of five words"))
        assertEquals(3, assertNotNull(syncSetup(first.storage)).epoch)
        for (service in services) {
            assertNull(service.read(statePath(gone)))
            assertNull(service.read(devicePath(gone)))
        }
    }

    @Test
    fun aDeviceIsRenamedAndTurnedOffWithOrWithoutTheVault() = runTest {
        val first = Device()
        first.wizard.create(first.wizard.reach(place, ""), PASSWORD, null, Choice("desk", emptySet(), false))
        first.wizard.rename("office")
        first.wizard.choose(setOf("theme"), true)
        val renamed = assertNotNull(syncSetup(first.storage))
        assertEquals("office", renamed.name)
        assertEquals(setOf("theme"), renamed.leftOut)
        assertTrue(renamed.secrets)

        val second = Device()
        second.wizard.join(second.wizard.reach(place, ""), PASSWORD, null, Choice("phone", emptySet(), false))
        second.wizard.forget(removeVault = false)
        assertNull(syncSetup(second.storage))
        assertEquals("", second.storage.read(SYNC_MEMORY))
        assertNull(syncKeys(renamed, second.secrets))
        assertNotNull(first.wizard.reach(place, "").vault)
        first.wizard.forget(removeVault = true)
        assertNull(syncSetup(first.storage))
        assertTrue(File(folder, SYNC_FOLDER).walk().none { it.isFile })
    }

    @Test
    fun theCodeOfAServiceOnANetworkCarriesItsLogin() {
        val webDav = ServiceSettings.WebDav("https://dav.example.org/dav", "nhsync")
        val (settings, secret) = serviceOf(assertNotNull(serviceJson(webDav, "rd-test-app-password")))
        assertEquals(webDav, settings)
        assertEquals("rd-test-app-password", secret)
        assertNull(serviceJson(place, ""))
    }
}
