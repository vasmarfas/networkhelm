package com.vasmarfas.networkhelm.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class FileTrustStore(private val file: File) : TrustStore {
    private val lock = Mutex()

    override suspend fun get(host: String, port: Int, kind: TrustKind): String? = lock.withLock { read()[trustKey(host, port, kind)] }

    override suspend fun put(host: String, port: Int, kind: TrustKind, fingerprint: String) = lock.withLock {
        val entries = read() + (trustKey(host, port, kind) to fingerprint)
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(entries.entries.joinToString("") { "${it.key} ${it.value}\n" })
        }
    }

    // a line of another form, edited by hand or left by a newer version, is left out
    override suspend fun all(): List<Trusted> = lock.withLock { read() }.mapNotNull { (key, fingerprint) ->
        val parts = key.split(' ')
        val kind = TrustKind.entries.firstOrNull { it.name == parts[0] }
        val port = parts.getOrNull(2)?.toIntOrNull()
        if (parts.size == 3 && kind != null && port != null) Trusted(parts[1], port, kind, fingerprint) else null
    }

    private suspend fun read(): Map<String, String> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyMap()
        file.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.associate {
            val split = it.lastIndexOf(' ')
            it.substring(0, split) to it.substring(split + 1)
        }
    }
}
