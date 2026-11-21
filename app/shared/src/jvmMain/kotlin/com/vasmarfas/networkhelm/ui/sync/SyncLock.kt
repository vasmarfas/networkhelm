package com.vasmarfas.networkhelm.ui.sync

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

// of the copies of the app on one data folder, the one that took the lock of the folder synchronises, and holds it until it ends
fun syncLock(directory: File): () -> Boolean {
    var held: FileLock? = null
    return {
        held != null || run {
            directory.mkdirs()
            val channel = FileChannel.open(File(directory, "sync.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            // a copy in the same process asks the lock of the process again and hears so
            held = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            }
            if (held == null) channel.close()
            held != null
        }
    }
}
