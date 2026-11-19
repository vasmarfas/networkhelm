@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.vasmarfas.networkhelm.sync

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorWritingForDeleting
import platform.Foundation.NSFileCoordinatorWritingForReplacing
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.posix.memcpy

// a folder picked in Files, iCloud Drive among them: each access goes through a file coordinator, so the process that syncs the
// folder downloads a file before it is read and uploads it once written, and a file is written to a copy that then replaces it
class FilesFolder(private val root: NSURL) : Folder {
    override suspend fun list(directory: String): List<String> = withContext(Dispatchers.IO) {
        accessed { url(directory).path?.let { NSFileManager.defaultManager.contentsOfDirectoryAtPath(it, null) }?.filterIsInstance<String>().orEmpty() }
    }

    override suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) {
        accessed { coordinated(url(path), null) { file -> if (exists(file)) contents(file) else null } }
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            accessed {
                val file = url(path)
                file.URLByDeletingLastPathComponent?.let { parent -> foundation { NSFileManager.defaultManager.createDirectoryAtURL(parent, true, null, it) } }
                val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
                coordinated(file, NSFileCoordinatorWritingForReplacing) { given -> foundation { data.writeToURL(given, NSDataWritingAtomic, it) } }
            }
        }
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            accessed {
                coordinated(url(path), NSFileCoordinatorWritingForDeleting) { file ->
                    if (exists(file)) foundation { NSFileManager.defaultManager.removeItemAtURL(file, it) }
                }
            }
        }
    }

    private fun url(path: String) = root.URLByAppendingPathComponent(path) ?: throw IllegalArgumentException(path)

    private inline fun <T> accessed(action: () -> T): T {
        val started = root.startAccessingSecurityScopedResource()
        try {
            return action()
        } finally {
            if (started) root.stopAccessingSecurityScopedResource()
        }
    }
}

private fun exists(file: NSURL) = file.path?.let { NSFileManager.defaultManager.fileExistsAtPath(it) } == true

private fun contents(file: NSURL): ByteArray {
    val data = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        NSData.dataWithContentsOfURL(file, 0u, error.ptr) ?: throw IllegalStateException(error.value?.localizedDescription ?: "${file.path} was not read")
    }
    val copy = ByteArray(data.length.toInt())
    if (copy.isNotEmpty()) copy.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return copy
}

// a call of Foundation that answers false and tells why through NSError
private fun foundation(call: (CPointer<ObjCObjectVar<NSError?>>) -> Boolean) = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    if (!call(error.ptr)) throw IllegalStateException(error.value?.localizedDescription ?: "the file was not changed")
}

// the action on the file as the coordinator hands it over, for a write with its options or a read with null; Foundation calls the block,
// so an exception is carried out of it rather than thrown through it
private fun <T> coordinated(file: NSURL, writing: ULong?, action: (NSURL) -> T): T = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    var outcome: Result<T>? = null
    val accessor = { given: NSURL? -> outcome = given?.let { runCatching { action(it) } } }
    if (writing == null) {
        NSFileCoordinator(null).coordinateReadingItemAtURL(file, 0u, error.ptr, accessor)
    } else {
        NSFileCoordinator(null).coordinateWritingItemAtURL(file, writing, error.ptr, accessor)
    }
    (outcome ?: throw IllegalStateException(error.value?.localizedDescription ?: "${file.path} was not reached")).getOrThrow()
}
