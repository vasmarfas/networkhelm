@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.networkhelm.sync

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test

// the test process of the simulator gets no file coordinator: every call of NSFileCoordinator ends in NSCocoaErrorDomain 256 or 512
// without its block, while a plain write to the same folder goes; the folder of Files is checked in the app on a device
@Ignore
class FilesFolderTest {
    private val root = NSURL.fileURLWithPath(NSTemporaryDirectory() + "networkhelm-" + Random.nextLong().toULong().toString(16), true)

    @AfterTest
    fun removeTheFolder() {
        NSFileManager.defaultManager.removeItemAtURL(root, null)
    }

    private fun fake(): Fake {
        val folder = FilesFolder(root)
        return Fake("folder of Files", FolderService(folder), { path, bytes -> folder.write("$SYNC_FOLDER/$path", bytes) }, { folder.delete("$SYNC_FOLDER/$it") })
    }

    @Test
    fun aFileGoesInComesOutAndGoesAway() = runTest { fake().aFileGoesInComesOutAndGoesAway() }

    @Test
    fun theVaultIsWrittenWhereNoneIsOrOverTheOneReadBefore() = runTest { fake().theVaultIsWrittenWhereNoneIsOrOverTheOneReadBefore() }

    @Test
    fun aFileListedIsReadAsItIsWhenTheReadComes() = runTest { fake().aFileListedIsReadAsItIsWhenTheReadComes() }

    @Test
    fun copiesOfConflictsAndFilesOfOthersAreNotListed() = runTest { fake().copiesOfConflictsAndFilesOfOthersAreNotListed() }

    @Test
    fun aCopyGivenBackFromBeforeIsRefused() = runTest { fake().aCopyGivenBackFromBeforeIsRefused() }
}
