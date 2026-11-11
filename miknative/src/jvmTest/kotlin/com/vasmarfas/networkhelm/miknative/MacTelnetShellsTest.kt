package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.ShellChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MacTelnetShellsTest {
    private val serverMac = MacAddress.parse("66:77:88:99:aa:bb")
    private val clientMac = MacAddress.parse("00:11:22:33:44:55")

    private fun router(password: String = "secret") = FakeMacServer(serverMac, clientMac, "admin", password)

    private suspend fun ShellChannel.banner(): String {
        val buffer = ByteArray(1024)
        return buffer.decodeToString(0, withTimeout(2_000L) { read(buffer, 0, buffer.size) })
    }

    private suspend fun until(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000L
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for the condition" }
            delay(10)
        }
    }

    private suspend fun CoroutineScope.connect(first: FakeMacServer, vararg more: FakeMacServer, timeoutMillis: Long = 5_000L): MacTelnetSsh {
        val queue = ArrayDeque(more.toList())
        return connectMacTelnet(
            destinationMac = serverMac,
            user = "admin",
            password = "secret",
            scope = this,
            sourceMac = clientMac,
            transport = first,
            destinationAddress = "127.0.0.1",
            timeoutMillis = timeoutMillis,
            transports = { queue.removeFirst() },
        )
    }

    @Test
    fun everyShellIsASessionOfItsOwn() = runBlocking {
        val first = router()
        val second = router()
        val ssh = connect(first, second)
        try {
            val one = ssh.shell("vt102", 80, 24)
            val two = ssh.shell("vt102", 120, 30)
            assertTrue("Welcome to RouterOS" in one.banner())
            assertTrue("Welcome to RouterOS" in two.banner())
            assertNotEquals(first.sessionKey, second.sessionKey)
            assertEquals(120, second.lastTerminalWidth)
            assertEquals(30, second.lastTerminalHeight)

            one.write("first\r".encodeToByteArray())
            two.write("second\r".encodeToByteArray())
            until { first.receivedTerminalStrings.any { "first" in it } && second.receivedTerminalStrings.any { "second" in it } }
            assertTrue(first.receivedTerminalStrings.none { "second" in it })
            assertTrue(second.receivedTerminalStrings.none { "first" in it })
        } finally {
            ssh.close()
        }
    }

    @Test
    fun theFirstShellIsTheSessionThatLoggedIn() = runBlocking {
        val first = router()
        val ssh = connect(first)
        try {
            val one = ssh.shell("vt102", 100, 40)
            assertTrue("Welcome to RouterOS" in one.banner())
            until { first.lastTerminalWidth == 100 && first.lastTerminalHeight == 40 }
        } finally {
            ssh.close()
        }
    }

    @Test
    fun aShellThatIsClosedLeavesTheOthersTalking() = runBlocking {
        val first = router()
        val second = router()
        val third = router()
        val ssh = connect(first, second, third)
        try {
            val one = ssh.shell("vt102", 80, 24)
            val two = ssh.shell("vt102", 80, 24)
            one.banner()
            two.banner()
            one.close()
            val three = ssh.shell("vt102", 80, 24)
            three.banner()
            two.write("still\r".encodeToByteArray())
            until { second.receivedTerminalStrings.any { "still" in it } }
            three.write("here\r".encodeToByteArray())
            until { third.receivedTerminalStrings.any { "here" in it } }
        } finally {
            ssh.close()
        }
    }

    @Test
    fun closingTheConnectionClosesEveryShell() = runBlocking {
        val first = router()
        val second = router()
        val ssh = connect(first, second)
        val one = ssh.shell("vt102", 80, 24)
        val two = ssh.shell("vt102", 80, 24)
        ssh.close()
        for (shell in listOf(one, two)) {
            assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { shell.write("x".encodeToByteArray()) }.category)
        }
        assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { ssh.shell("vt102", 80, 24) }.category)
    }

    @Test
    fun aSessionThatSaysNothingSaysThatItIsThere() = runBlocking {
        val router = router()
        val ssh = connectMacTelnet(
            destinationMac = serverMac,
            user = "admin",
            password = "secret",
            scope = this,
            sourceMac = clientMac,
            transport = router,
            destinationAddress = "127.0.0.1",
            timeoutMillis = 5_000L,
            keepAliveMillis = 50L,
        )
        try {
            ssh.shell("vt102", 80, 24).banner()
            val before = router.acknowledgements
            until { router.acknowledgements >= before + 3 }
        } finally {
            ssh.close()
        }
    }

    @Test
    fun aKeepAliveTheNetworkDoesNotTakeEndsTheSessionAndNotItsScope() = runBlocking {
        val router = router()
        var down = false
        val link = object : MacTelnetTransport by router {
            override fun send(bytes: ByteArray, destination: String, port: Int) {
                if (down) throw IOException("Network is unreachable")
                router.send(bytes, destination, port)
            }
        }
        val ssh = connectMacTelnet(
            destinationMac = serverMac,
            user = "admin",
            password = "secret",
            scope = this,
            sourceMac = clientMac,
            transport = link,
            destinationAddress = "127.0.0.1",
            timeoutMillis = 5_000L,
            keepAliveMillis = 50L,
        )
        try {
            val shell = ssh.shell("vt102", 100, 40)
            shell.banner()
            until { router.lastTerminalWidth == 100 && router.lastTerminalHeight == 40 }
            down = true
            val failure = assertFailsWith<IOException> { withTimeout(2_000L) { shell.read(ByteArray(16), 0, 16) } }
            assertEquals("Network is unreachable", failure.message)
        } finally {
            ssh.close()
        }
    }

    @Test
    fun aSecondLoginThatTheRouterRefusesIsAPermissionError() = runBlocking {
        val ssh = connect(router(), router("another password"))
        try {
            ssh.shell("vt102", 80, 24)
            assertEquals(ErrorCategory.PermissionDenied, assertFailsWith<DeviceException> { ssh.shell("vt102", 80, 24) }.category)
        } finally {
            ssh.close()
        }
    }

    @Test
    fun aSecondLoginThatNobodyAnswersIsALostConnection() = runBlocking {
        val silent = object : MacTelnetTransport {
            override fun send(bytes: ByteArray, destination: String, port: Int) = Unit

            override fun onMessage(handler: (bytes: ByteArray, source: String, port: Int) -> Unit) = Unit

            override fun close() = Unit
        }
        val ssh = connectMacTelnet(
            destinationMac = serverMac,
            user = "admin",
            password = "secret",
            scope = this,
            sourceMac = clientMac,
            transport = router(),
            destinationAddress = "127.0.0.1",
            timeoutMillis = 300L,
            transports = { silent },
        )
        try {
            ssh.shell("vt102", 80, 24)
            assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { ssh.shell("vt102", 80, 24) }.category)
        } finally {
            ssh.close()
        }
    }
}
