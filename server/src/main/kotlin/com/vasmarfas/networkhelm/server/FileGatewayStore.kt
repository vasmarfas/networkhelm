package com.vasmarfas.networkhelm.server

import com.vasmarfas.networkhelm.gateway.GatewayStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import javax.crypto.spec.SecretKeySpec

class FileGatewayStore(home: File, private val sealing: SecretKeySpec) : GatewayStore {
    private val documents = File(home, "documents")
    private val secrets = File(home, "secrets.json")

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) { document(name).takeIf { it.isFile }?.readText() }

    // one writer at a time, and for the stores of all the sockets: two of them share the temporary file of a name
    override suspend fun write(name: String, text: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            documents.mkdirs()
            replace(document(name), text)
        }
    }

    override suspend fun secret(key: String): String? = lock.withLock { stored()[key]?.let { unseal(sealing, it).decodeToString() } }

    override suspend fun keep(key: String, value: String) = lock.withLock { store(stored() + (key to seal(sealing, value.encodeToByteArray()))) }

    override suspend fun forget(key: String) = lock.withLock { store(stored() - key) }

    private fun document(name: String): File {
        require(DOCUMENT.matches(name)) { "$name is not a name of a document" }
        return File(documents, name)
    }

    private suspend fun stored(): Map<String, String> = withContext(Dispatchers.IO) {
        if (secrets.isFile) json.decodeFromString(table, secrets.readText()) else emptyMap()
    }

    private suspend fun store(values: Map<String, String>) = withContext(Dispatchers.IO) {
        replace(secrets, json.encodeToString(table, values))
        Gateway.ownerOnly(secrets)
    }

    companion object {
        private val DOCUMENT = Regex("[A-Za-z0-9][A-Za-z0-9._@+-]{0,199}")
        private val table = MapSerializer(String.serializer(), String.serializer())
        private val json = Json { prettyPrint = true }

        private val lock = Mutex()

        fun reseal(home: File, from: SecretKeySpec, to: SecretKeySpec): Int {
            val file = File(home, "secrets.json")
            if (!file.isFile) return 0
            val values = json.decodeFromString(table, file.readText()).mapValues { (_, sealed) ->
                try {
                    seal(to, unseal(from, sealed))
                } catch (e: GeneralSecurityException) {
                    throw IllegalArgumentException("secrets.json holds a secret that the current password does not open; --reset-password makes a new one and puts the saved passwords aside", e)
                }
            }
            replace(file, json.encodeToString(table, values))
            Gateway.ownerOnly(file)
            return values.size
        }

        fun putAside(home: File): Boolean {
            val file = File(home, "secrets.json")
            if (!file.isFile) return false
            Files.move(file.toPath(), File(home, "secrets.json.old").toPath(), StandardCopyOption.REPLACE_EXISTING)
            return true
        }

        private fun replace(file: File, text: String) {
            val temporary = File(file.parentFile, ".${file.name}.new")
            temporary.writeText(text)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
