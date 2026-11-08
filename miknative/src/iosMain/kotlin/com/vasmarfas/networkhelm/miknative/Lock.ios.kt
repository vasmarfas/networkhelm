package com.vasmarfas.networkhelm.miknative

import platform.Foundation.NSRecursiveLock

internal actual fun reentrantLock(): Lock = object : Lock {
    private val lock = NSRecursiveLock()

    override fun lock() = lock.lock()

    override fun unlock() = lock.unlock()
}
