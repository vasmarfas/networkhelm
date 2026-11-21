package com.vasmarfas.networkhelm.ui.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

// Windows 10 and 11 both give os.version 10.0, which tells nothing that the name does not
internal actual val syncPlatform: String = System.getProperty("os.name").let { name -> if (name.startsWith("Windows")) name else "$name ${System.getProperty("os.version")}" }

// the name of the computer as the system keeps it: InetAddress would ask a name server, and nothing goes out before synchronisation is on
internal actual suspend fun syncDeviceName(): String = withContext(Dispatchers.IO) {
    System.getenv("COMPUTERNAME") ?: try {
        ProcessBuilder("hostname").start().inputStream.bufferedReader().use { it.readText() }.trim().removeSuffix(".local").ifEmpty { null }
    } catch (e: IOException) {
        null
    }
} ?: syncPlatform
