package com.vasmarfas.networkhelm.session

import kotlinx.coroutines.CancellationException

interface TextStore {
    suspend fun read(name: String): String?

    suspend fun write(name: String, text: String)
}

// a session keeps there what it can make again, so a store that cannot be written now costs only the next connect its time
internal suspend fun TextStore.keep(name: String, text: String) {
    try {
        write(name, text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // the storage of each platform fails with an exception of its own
    }
}
