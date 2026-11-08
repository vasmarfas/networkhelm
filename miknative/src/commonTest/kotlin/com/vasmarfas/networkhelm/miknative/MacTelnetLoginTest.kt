package com.vasmarfas.networkhelm.miknative

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class MacTelnetLoginTest {

    // the fake answers inside the call that sends, so the thread that holds the lock of the session takes it again
    @Test
    fun aRouterOs6LoginReachesTheConsole() = runTest {
        withContext(Dispatchers.Default) {
            val server = FakeMacServer(expectedPassword = "ros6password", authMode = FakeMacAuthMode.MD5)
            val ssh = connectMacTelnet(server.serverMac, "admin", "ros6password", this, server.clientMac, transport = server, destinationAddress = "127.0.0.1", timeoutMillis = 5_000L)
            try {
                val shell = ssh.shell("vt102", 80, 24)
                val buffer = ByteArray(1024)
                val count = withTimeout(2_000L) { shell.read(buffer, 0, buffer.size) }
                assertTrue("Welcome to RouterOS" in buffer.decodeToString(0, count))
            } finally {
                ssh.close()
            }
        }
    }
}
