package com.vasmarfas.networkhelm.session

import kotlinx.coroutines.CancellationException

interface TextStore {
    suspend fun read(name: String): String?

    suspend fun write(name: String, text: String)
}

// for what is written whole again with its next change or made again at the next connect: a store that refuses it now loses only this one;
// true when the store took it
suspend fun TextStore.keep(name: String, text: String): Boolean = try {
    write(name, text)
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // the storage of each platform fails with an exception of its own
    false
}

// for what is read only to merge a change into: a store that refuses it now leaves the change to go alone
suspend fun TextStore.peek(name: String): String? = try {
    read(name)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
