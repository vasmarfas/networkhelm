package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.keenetic.hex
import com.vasmarfas.networkhelm.keenetic.sha256
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.coroutines.CancellationException

// a folder of this device by paths from it, / between the parts: a file that is not there reads as null, a directory that is not
// there lists empty, and a write makes the directories on its way
interface Folder {
    suspend fun list(directory: String): List<String>

    suspend fun read(path: String): ByteArray?

    suspend fun write(path: String, bytes: ByteArray)

    suspend fun delete(path: String)
}

// a folder that Syncthing or the client of a cloud keeps the same on every device. A file is tagged by the hash of its bytes: not every
// provider of documents gives the time of a change, and a file of whole 4 KiB blocks seldom changes its size
class FolderService(private val folder: Folder) : SyncService {
    override suspend fun list() = reached {
        listOf("", "devices/", "state/")
            .flatMap { directory -> folder.list("$SYNC_FOLDER/$directory".trimEnd('/')).map { directory + it } }
            .filter(::syncFile)
            .mapNotNull { path -> folder.read("$SYNC_FOLDER/$path")?.let { Listed(path, tag(it)) } }
    }

    override suspend fun read(path: String) = reached { folder.read("$SYNC_FOLDER/$path")?.let { Stored(it, tag(it)) } }

    override suspend fun write(path: String, bytes: ByteArray) = reached { folder.write("$SYNC_FOLDER/$path", bytes) }

    override suspend fun writeIf(path: String, bytes: ByteArray, over: Stored?) = reached {
        val current = folder.read("$SYNC_FOLDER/$path")?.let(::tag)
        (current == over?.tag).also { if (it) folder.write("$SYNC_FOLDER/$path", bytes) }
    }

    override suspend fun delete(path: String) = reached { folder.delete("$SYNC_FOLDER/$path") }

    private fun tag(bytes: ByteArray) = hex(sha256(bytes))

    private suspend fun <T> reached(action: suspend () -> T): T = try {
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw DeviceException(ErrorCategory.General, "the folder of synchronisation is not available: ${e.message ?: e::class.simpleName}")
    }
}
