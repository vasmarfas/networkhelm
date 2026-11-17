package com.vasmarfas.networkhelm.app

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeProtectionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// KSafe reads its file when it opens and does not see what a copy of the app started beside this one saves later, so the
// password of a router saved there would not fill in here before a restart: the store opens again when a file in its
// folder changed since this copy last read or wrote it
class FolderSecrets(private val folder: File, private val open: () -> KSafe) : Secrets {
    private val lock = Mutex()
    private var store = open()
    private var seen = stamp()

    override val protection: KSafeProtectionInfo get() = store.protectionInfo

    override suspend fun get(key: String): String? = held { it.get<String?>(key, null) }

    override suspend fun put(key: String, value: String) = held { store ->
        store.put(key, value)
        seen = stamp()
    }

    override suspend fun delete(key: String) = held { store ->
        store.delete(key)
        seen = stamp()
    }

    private suspend fun <T> held(use: suspend (KSafe) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            val now = stamp()
            if (now != seen) {
                store.close()
                store = open()
                seen = now
            }
            use(store)
        }
    }

    private fun stamp(): List<String> = folder.walkTopDown().filter { it.isFile }.map { "${it.path} ${it.length()} ${it.lastModified()}" }.sorted().toList()
}
