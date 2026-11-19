package com.vasmarfas.networkhelm.sync

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DirectoryFolderTest {
    private val root = Files.createTempDirectory("networkhelm").toFile()

    @AfterTest
    fun removeTheFolder() {
        root.deleteRecursively()
    }

    private fun fake(): Fake {
        val folder = DirectoryFolder(root)
        return Fake("folder of the desktop", FolderService(folder), { path, bytes -> folder.write("$SYNC_FOLDER/$path", bytes) }, { folder.delete("$SYNC_FOLDER/$it") })
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

    // a write leaves nothing beside the file; the app stopped after it wrote the new file beside the old one and before it moved it
    // over leaves the old one, and what lies beside it is not listed
    @Test
    fun aWriteCutShortLeavesTheOldFileAndNothingElseIsListed() = runTest {
        val service = FolderService(DirectoryFolder(root))
        service.write(statePath(FIRST_DEVICE), "one".encodeToByteArray())
        service.write(statePath(FIRST_DEVICE), "two".encodeToByteArray())
        val state = File(root, "$SYNC_FOLDER/state")
        assertEquals(listOf(FIRST_DEVICE), state.list()?.toList())
        File(state, ".${FIRST_DEVICE}8301547762.tmp").writeBytes("th".encodeToByteArray())
        assertEquals(listOf(statePath(FIRST_DEVICE)), service.list().map { it.path })
        assertContentEquals("two".encodeToByteArray(), service.read(statePath(FIRST_DEVICE))?.bytes)
    }
}
