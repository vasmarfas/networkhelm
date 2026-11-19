package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ONE = "one".encodeToByteArray()
private val TWO = "two".encodeToByteArray()

// the checks every service passes, each on a fresh fake of its own: Yandex Disk, Dropbox, Google Drive, OneDrive and CloudKit
class CloudServicesTest {
    private fun fakes() = listOf(
        FakeYandexDisk().fake("Yandex Disk"),
        FakeDropbox().fake("Dropbox"),
        FakeGoogleDrive().fake("Google Drive"),
        FakeOneDrive().fake("OneDrive"),
        FakeCloudKit().fake("CloudKit"),
    )

    @Test
    fun aFileGoesInComesOutAndGoesAway() = runTest {
        for (fake in fakes()) fake.aFileGoesInComesOutAndGoesAway()
    }

    @Test
    fun theVaultIsWrittenWhereNoneIsOrOverTheOneReadBefore() = runTest {
        for (fake in fakes()) fake.theVaultIsWrittenWhereNoneIsOrOverTheOneReadBefore()
    }

    @Test
    fun aFileListedIsReadAsItIsWhenTheReadComes() = runTest {
        for (fake in fakes()) fake.aFileListedIsReadAsItIsWhenTheReadComes()
    }

    @Test
    fun aConnectionBrokenHalfwayFailsAndLeavesTheFileAsItWas() = runTest {
        val disk = FakeYandexDisk()
        disk.fake("Yandex Disk").aConnectionBrokenHalfwayFailsAndLeavesTheFileAsItWas(disk::breakNext)
        val dropbox = FakeDropbox()
        dropbox.fake("Dropbox").aConnectionBrokenHalfwayFailsAndLeavesTheFileAsItWas(dropbox::breakNext)
        val drive = FakeGoogleDrive()
        drive.fake("Google Drive").aConnectionBrokenHalfwayFailsAndLeavesTheFileAsItWas(drive::breakNext)
        val oneDrive = FakeOneDrive()
        oneDrive.fake("OneDrive").aConnectionBrokenHalfwayFailsAndLeavesTheFileAsItWas(oneDrive::breakNext)
        val cloudKit = FakeCloudKit()
        cloudKit.fake("CloudKit").aConnectionBrokenHalfwayFailsAndLeavesTheFileAsItWas(cloudKit::breakNext)
    }

    @Test
    fun copiesOfConflictsAndFilesOfOthersAreNotListed() = runTest {
        for (fake in fakes()) fake.copiesOfConflictsAndFilesOfOthersAreNotListed()
    }

    @Test
    fun aCopyGivenBackFromBeforeIsRefused() = runTest {
        for (fake in fakes()) fake.aCopyGivenBackFromBeforeIsRefused()
    }

    // the folders of a new vault are made by the first write that misses them; the bytes go up and come down through the hosts of the
    // links, which never see the token, the download going on to the server that holds the file
    @Test
    fun yandexDiskMakesTheFoldersOnceAndCarriesTheBytesThroughItsLinks() = runTest {
        val disk = FakeYandexDisk()
        val service = disk.service()
        service.write(statePath(FIRST_DEVICE), ONE)
        service.write(statePath(SECOND_DEVICE), TWO)
        assertContentEquals(TWO, service.read(statePath(SECOND_DEVICE))?.bytes)
        val made = disk.requests.filter { it.method.value == "PUT" && it.url.host == "cloud-api.yandex.net" }.map { it.url.parameters["path"] }
        assertEquals(listOf("app:/$SYNC_FOLDER", "app:/$SYNC_FOLDER/state"), made)
        val elsewhere = disk.requests.filter { it.url.host != "cloud-api.yandex.net" && it.url.host != "oauth.yandex.ru" }
        assertEquals(setOf("uploader1j.disk.yandex.net", "downloader.disk.yandex.ru", "s143vla.storage.yandex.net"), elsewhere.map { it.url.host }.toSet())
        assertTrue(elsewhere.none { it.headers["Authorization"] != null })
    }

    // a list of more files than a page of the disk is read whole
    @Test
    fun yandexDiskListsAFolderPageByPage() = runTest {
        val service = FakeYandexDisk().service()
        val devices = listOf("1", "2", "3", "4", "5").map { it.padStart(32, 'e') }
        for (device in devices) service.write(statePath(device), device.encodeToByteArray())
        assertEquals(devices.map(::statePath).toSet(), service.list().map { it.path }.toSet())
    }

    // a device waiting on Dropbox hears of a change as it comes; the first call only takes the cursor, and the long poll goes without a token
    @Test
    fun aDeviceWaitingOnDropboxHearsOfAChangeAsItComes() = runTest {
        val dropbox = FakeDropbox()
        val service = dropbox.service()
        service.write(VAULT, ONE)
        service.changes()
        val waiting = async { service.changes() }
        service.write(statePath(FIRST_DEVICE), TWO)
        waiting.await()
        val polls = dropbox.requests.filter { it.url.host == "notify.dropboxapi.com" }
        assertEquals(1, polls.size)
        assertNull(polls.single().headers["Authorization"])
    }

    // Dropbox gives its list two entries a page, folders among them
    @Test
    fun dropboxListsTheFolderPageByPage() = runTest {
        val dropbox = FakeDropbox()
        val service = dropbox.service()
        val devices = listOf("1", "2", "3").map { it.padStart(32, 'd') }
        for (device in devices) service.write(statePath(device), device.encodeToByteArray())
        service.write(VAULT, ONE)
        assertEquals((devices.map(::statePath) + VAULT).toSet(), service.list().map { it.path }.toSet())
        assertTrue(dropbox.requests.count { it.url.encodedPath == "/2/files/list_folder/continue" } >= 2)
    }

    // two devices make a vault at the same moment: of the twins the drive then holds the one made first is the vault, and the one that came
    // second goes away again and is told so
    @Test
    fun aVaultMadeOnGoogleDriveAfterAnotherOneAtTheSameTimeGoesAway() = runTest {
        val drive = FakeGoogleDrive()
        val service = drive.service()
        drive.racing = TWO
        assertFalse(service.writeIf(VAULT, ONE, null))
        assertEquals(1, drive.named(VAULT))
        assertContentEquals(TWO, service.read(VAULT)?.bytes)
    }

    // twins of a name, left by a write whose answer was lost, are one file to the list and to a read: the one made first
    @Test
    fun twinsOnGoogleDriveAreOneFile() = runTest {
        val drive = FakeGoogleDrive()
        val fake = drive.fake("Google Drive")
        fake.service.write(statePath(FIRST_DEVICE), ONE)
        drive.racing = TWO
        fake.service.write(devicePath(FIRST_DEVICE), ONE)
        assertEquals(setOf(statePath(FIRST_DEVICE), devicePath(FIRST_DEVICE)), fake.service.list().map { it.path }.toSet())
        assertContentEquals(TWO, fake.service.read(devicePath(FIRST_DEVICE))?.bytes)
        fake.service.delete(devicePath(FIRST_DEVICE))
        assertEquals(0, drive.named(devicePath(FIRST_DEVICE)))
    }

    // OneDrive makes no folder on the way of a write: the first write that misses them makes them, and the bytes come from a download link
    // that sees no token
    @Test
    fun oneDriveMakesTheFoldersOnceAndReadsThroughTheDownloadLink() = runTest {
        val oneDrive = FakeOneDrive()
        val service = oneDrive.service()
        service.write(statePath(FIRST_DEVICE), ONE)
        service.write(statePath(SECOND_DEVICE), TWO)
        assertContentEquals(ONE, service.read(statePath(FIRST_DEVICE))?.bytes)
        assertEquals(2, oneDrive.requests.count { it.method.value == "POST" })
        val downloads = oneDrive.requests.filter { it.url.host == "public.dm.files.1drv.com" }
        assertEquals(1, downloads.size)
        assertNull(downloads.single().headers["Authorization"])
    }

    // each answer of CloudKit brings the web token for the next request, which the device keeps; the token goes in the query and only there
    @Test
    fun cloudKitTakesTheWebTokenOfEachAnswerForTheNextRequest() = runTest {
        val cloudKit = FakeCloudKit()
        val service = cloudKit.service()
        service.write(VAULT, ONE)
        service.write(statePath(FIRST_DEVICE), TWO)
        assertContentEquals(ONE, service.read(VAULT)?.bytes)
        assertEquals(setOf(VAULT, statePath(FIRST_DEVICE)), service.list().map { it.path }.toSet())
        assertEquals(4, cloudKit.kept.size)
        assertEquals(cloudKit.token, cloudKit.kept.last())
        assertEquals(cloudKit.kept.size, cloudKit.kept.toSet().size)
        assertTrue(cloudKit.requests.none { request -> request.headers.entries().any { (_, values) -> values.any { WEB_TOKEN in it } } })
    }

    // a web token CloudKit no longer takes asks for a new sign-in
    @Test
    fun aWebTokenCloudKitNoLongerTakesAsksForANewSignIn() = runTest {
        val cloudKit = FakeCloudKit()
        val service = cloudKit.service()
        cloudKit.token = "a web token of another sign-in"
        val error = assertFailsWith<DeviceException> { service.list() }
        assertEquals("api.apple-cloudkit.com did not accept the login", error.message)
    }

    // a token the cloud refuses is renewed once with the refresh token and the request sent again; the new tokens go to the protected
    // store, and the next request takes them without another renewal
    @Test
    fun aRefusedTokenIsRenewedOnceAndTheNewTokensKept() = runTest {
        val dropbox = FakeDropbox()
        val kept = mutableListOf<String>()
        val service = dropbox.service(cloudTokens("an access token that ran out"), keep = { kept += it })
        service.write(VAULT, ONE)
        service.write(statePath(FIRST_DEVICE), TWO)
        assertEquals(1, dropbox.authority.forms.size)
        assertEquals(dropbox.authority.access, tokensOf(kept.single())?.access)
        assertEquals(CLOUD_REFRESH, tokensOf(kept.single())?.refresh)
    }

    // an access token that runs out within the minute is renewed before the request, and a cloud that gives a new refresh token each time
    // has the new one kept
    @Test
    fun aTokenAboutToRunOutIsRenewedBeforeTheRequest() = runTest {
        val oneDrive = FakeOneDrive()
        val kept = mutableListOf<String>()
        val service = oneDrive.service(cloudTokens(CLOUD_ACCESS, expires = 1), keep = { kept += it })
        service.list()
        assertEquals(1, oneDrive.authority.forms.size)
        assertTrue(oneDrive.requests.none { it.headers["Authorization"] == "Bearer $CLOUD_ACCESS" })
        val renewed = tokensOf(kept.single())
        assertEquals(oneDrive.authority.refresh, renewed?.refresh)
        assertNotEquals(CLOUD_REFRESH, renewed?.refresh)
    }

    // a refresh token the cloud took back ends in a refusal of the sign-in, which the user answers by signing in again
    @Test
    fun aRefreshTokenTakenBackEndsInARefusalOfTheSignIn() = runTest {
        val drive = FakeGoogleDrive()
        drive.authority.refresh = "a refresh token of another sign-in"
        val error = assertFailsWith<DeviceException> { drive.service(cloudTokens("an access token that ran out")).list() }
        assertEquals("oauth2.googleapis.com refused the sign-in: invalid_grant: the grant is not known here", error.message)
    }

    // the sign-in to Dropbox ends on Dropbox as well when synchronisation is turned off
    @Test
    fun turningOffEndsTheSignInToDropbox() = runTest {
        val dropbox = FakeDropbox()
        dropbox.service().revoke()
        assertEquals("", dropbox.authority.access)
        assertTrue(dropbox.requests.any { it.url.encodedPath == "/2/auth/token/revoke" && it.headers["Authorization"] == "Bearer $CLOUD_ACCESS" })
    }
}
