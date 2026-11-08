package com.vasmarfas.networkhelm.miknative

import java.util.concurrent.locks.ReentrantLock

internal actual fun reentrantLock(): Lock = object : Lock {
    private val lock = ReentrantLock()

    override fun lock() = lock.lock()

    override fun unlock() = lock.unlock()
}
