package com.vasmarfas.networkhelm.session

import kotlinx.coroutines.CancellationException

interface TextStore {
    suspend fun read(name: String): String?

    suspend fun write(name: String, text: String)
}

// for what is written whole again with its next change or made again at the next connect: a store that refuses it now loses only this one
suspend fun TextStore.keep(name: String, text: String) {
    try {
        write(name, text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // the storage of each platform fails with an exception of its own
    }
}
