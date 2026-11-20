package com.vasmarfas.networkhelm.server

import com.vasmarfas.networkhelm.sync.DirectoryFolder
import com.vasmarfas.networkhelm.sync.FolderService
import com.vasmarfas.networkhelm.sync.Listed
import com.vasmarfas.networkhelm.sync.Stored
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

// the files of synchronisation the gateway keeps for the devices, in the folder sync of its home. Writes go one at a time, so no other
// write comes between the check of a condition and its write; each change moves a version that a waiting device hears of. The version
// starts anywhere, so a device that waited on the gateway before it restarted reads the files again
class SyncFiles(home: File) {
    private val files = FolderService(DirectoryFolder(File(home, "sync")))
    private val lock = Mutex()
    private val version = MutableStateFlow(Random.nextLong())

    suspend fun list(): List<Listed> = files.list()

    suspend fun read(path: String): Stored? = files.read(path)

    // accepts sees the file as it is, or null where there is none, and says whether the write goes on
    suspend fun write(path: String, bytes: ByteArray, accepts: (Stored?) -> Boolean): Boolean = lock.withLock {
        if (!accepts(files.read(path))) return@withLock false
        files.write(path, bytes)
        version.update { it + 1 }
        true
    }

    suspend fun delete(path: String) {
        lock.withLock {
            files.delete(path)
            version.update { it + 1 }
        }
    }

    // the version once it is another than the one a device knows, or the same one after the wait
    suspend fun changes(after: Long, wait: Long): Long = withTimeoutOrNull(wait) { version.first { it != after } } ?: version.value
}

// the tokens the devices synchronise with, kept as hashes beside the check of the password they were given under: a new password of
// the gateway ends them all
class SyncTokens(private val home: File) {
    @Serializable
    private class Given(val hash: String, val check: String)

    private val file = File(home, "sync-tokens.json")
    private val list = ListSerializer(Given.serializer())

    @Synchronized
    fun give(): String {
        val check = Gateway.account(home)?.check ?: error("the gateway has no account yet")
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32))
        val temporary = File(home, ".sync-tokens.json.new")
        temporary.writeText(Json.encodeToString(list, given().filter { it.check == check } + Given(hash(token), check)))
        Gateway.ownerOnly(temporary)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return token
    }

    @Synchronized
    fun valid(token: String?): Boolean {
        val check = Gateway.account(home)?.check ?: return false
        val hash = token?.let(::hash) ?: return false
        return given().any { it.check == check && MessageDigest.isEqual(it.hash.encodeToByteArray(), hash.encodeToByteArray()) }
    }

    private fun given(): List<Given> = if (file.isFile) Json.decodeFromString(list, file.readText()) else emptyList()

    private fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
