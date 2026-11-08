package com.vasmarfas.networkhelm.miknative

// a thread that holds the lock may take it again: a packet that comes back while the session sends goes through it twice
internal interface Lock {
    fun lock()

    fun unlock()
}

internal expect fun reentrantLock(): Lock

internal inline fun <T> Lock.locked(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
