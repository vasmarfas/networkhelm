package com.vasmarfas.networkhelm.transport

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

interface ByteStream {
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int

    suspend fun write(bytes: ByteArray)

    fun close()
}

// a blocking read outlives its coroutine; closing the stream is what wakes it with a failure
// the watchdog starts undispatched: queued, it could be cancelled before it ran, or wait behind a blocking read
internal suspend fun <T> ByteStream.closingAfter(timeout: Duration = Duration.INFINITE, block: suspend (ByteStream) -> T): T = coroutineScope {
    val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            delay(timeout)
        } finally {
            close()
        }
    }
    try {
        block(this@closingAfter)
    } catch (e: Exception) {
        ensureActive()
        throw e
    } finally {
        watchdog.cancel()
    }
}

// the same for a connection kept for the next request: only one that got no answer in time is closed
internal suspend fun <T> ByteStream.closingUnanswered(timeout: Duration, block: suspend () -> T): T = coroutineScope {
    var answered = false
    val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            delay(timeout)
        } finally {
            if (!answered) close()
        }
    }
    try {
        block().also { answered = true }
    } catch (e: Exception) {
        ensureActive()
        throw e
    } finally {
        watchdog.cancel()
    }
}
