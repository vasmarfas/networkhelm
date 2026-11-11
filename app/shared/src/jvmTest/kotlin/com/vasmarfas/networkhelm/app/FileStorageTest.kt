package com.vasmarfas.networkhelm.app

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileStorageTest {

    @Test
    fun writesOfOneFileAtOnceNeitherFailNorLeaveATemporaryFile() = runTest {
        val directory = createTempDirectory("networkhelm-storage").toFile()
        val storage = FileStorage(directory)
        coroutineScope {
            repeat(40) { number -> launch { storage.write("settings.json", "text $number") } }
        }
        assertTrue(storage.read("settings.json").orEmpty().startsWith("text "))
        assertEquals(listOf("settings.json"), directory.list().orEmpty().sorted())
    }

    @Test
    fun anExistingStoredFileIsReplacedByTheNewContents() = runTest {
        val directory = createTempDirectory("networkhelm-storage").toFile()
        val storage = FileStorage(directory)
        storage.write("settings.json", "old")
        storage.write("settings.json", "new")
        assertEquals("new", storage.read("settings.json"))
        assertFalse(File(directory, "settings.json.tmp").exists())
    }

    @Test
    fun aReplacementRefusedWhileAnotherProgramHoldsTheFileIsTriedAgain() = runTest {
        val directory = createTempDirectory("networkhelm-storage").toFile()
        var held = 2
        val storage = FileStorage(directory) { temporary, target ->
            if (held-- > 0) throw IOException("the file is used by another process")
            replaceFile(temporary, target)
        }
        storage.write("settings.json", "old")
        storage.write("settings.json", "new")
        assertEquals("new", storage.read("settings.json"))
        assertEquals(listOf("settings.json"), directory.list().orEmpty().sorted())
    }

    @Test
    fun aFailedReplacementKeepsTheOldFileAndTheSavedRoutersUnchanged() = runTest {
        val directory = createTempDirectory("networkhelm-storage").toFile()
        val original = FileStorage(directory)
        val old = SavedRouter("192.0.2.1", "admin")
        SavedRouters(original).save(old)
        val text = original.read(SavedRouters.FILE)
        val failing = FileStorage(directory) { _, _ -> throw IOException("replacement refused") }
        val routers = SavedRouters(failing).also { it.load() }
        assertFailsWith<IOException> { routers.save(SavedRouter("192.0.2.2", "admin")) }
        assertEquals(text, original.read(SavedRouters.FILE))
        assertEquals(listOf(old), routers.routers.value)
        assertFalse(File(directory, "${SavedRouters.FILE}.tmp").exists())
    }
}
