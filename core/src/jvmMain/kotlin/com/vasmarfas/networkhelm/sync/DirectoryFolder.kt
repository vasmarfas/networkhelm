package com.vasmarfas.networkhelm.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

// a folder of the desktop: a file is written beside itself and moved over the old one, so a reader, the client that syncs the folder
// among them, takes the file before or after and never half of it
class DirectoryFolder(private val root: File) : Folder {
    override suspend fun list(directory: String): List<String> = withContext(Dispatchers.IO) { File(root, directory).list()?.toList().orEmpty() }

    override suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            Files.readAllBytes(File(root, path).toPath())
        } catch (e: NoSuchFileException) {
            null
        }
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val file = File(root, path)
            Files.createDirectories(file.parentFile.toPath())
            val written = File.createTempFile(".${file.name}", ".tmp", file.parentFile)
            try {
                written.writeBytes(bytes)
                Files.move(written.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                written.delete()
            }
        }
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) { Files.deleteIfExists(File(root, path).toPath()) }
    }
}
