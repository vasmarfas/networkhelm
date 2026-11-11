package com.vasmarfas.networkhelm.app

import java.io.IOException

class RefusingStorage(var refused: String?) : Storage {
    private val kept = MemoryStorage()

    var refusals = 0
        private set

    override suspend fun read(name: String) = kept.read(name)

    override suspend fun write(name: String, text: String) {
        if (name == refused) {
            refusals++
            throw IOException("$name is used by another process")
        }
        kept.write(name, text)
    }
}
