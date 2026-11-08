package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.routeros.LoginTimeoutException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MacTelnetSessionTest {

    private val testServerMac = MacAddress.parse("66:77:88:99:aa:bb")
    private val testClientMac = MacAddress.parse("00:11:22:33:44:55")

    @Test
    fun mtweiAuthenticationAndTerminalIo() = runBlocking {
        val server = FakeMacServer(
            serverMac = testServerMac,
            clientMac = testClientMac,
            expectedUser = "admin",
            expectedPassword = "secret",
            authMode = FakeMacAuthMode.MTWEI,
        )

        val ssh = connectMacTelnet(
            destinationMac = testServerMac,
            user = "admin",
            password = "secret",
            scope = this,
            sourceMac = testClientMac,
            transport = server,
            destinationAddress = "127.0.0.1",
            timeoutMillis = 5_000L,
        )

        try {
            val shell = ssh.shell("vt102", 80, 24)

            val buf = ByteArray(1024)
            val readCount = withTimeout(2_000L) {
                shell.read(buf, 0, buf.size)
            }
            assertTrue(readCount > 0)
            val welcome = buf.decodeToString(0, readCount)
            assertTrue(welcome.contains("Welcome to RouterOS"))

            shell.write("system identity print\r\n".encodeToByteArray())

            until { server.receivedTerminalStrings.isNotEmpty() }
            assertTrue(server.receivedTerminalStrings.any { it.contains("system identity print") })

            server.sendTerminalOutput("  name: MikroTik\r\n[admin@MikroTik] > ")
            val readResponse = withTimeout(2_000L) {
                shell.read(buf, 0, buf.size)
            }
            assertTrue(readResponse > 0)
            val output = buf.decodeToString(0, readResponse)
            assertTrue(output.contains("name: MikroTik"))
        } finally {
            ssh.close()
        }
    }

    @Test
    fun md5AuthenticationAndTerminalIo() = runBlocking {
        val server = FakeMacServer(
            serverMac = testServerMac,
            clientMac = testClientMac,
            expectedUser = "admin",
            expectedPassword = "ros6password",
            authMode = FakeMacAuthMode.MD5,
        )

        val ssh = connectMacTelnet(
            destinationMac = testServerMac,
            user = "admin",
            password = "ros6password",
            scope = this,
            sourceMac = testClientMac,
            transport = server,
            destinationAddress = "127.0.0.1",
            timeoutMillis = 5_000L,
        )

        try {
            val shell = ssh.shell("vt102", 80, 24)
            val buf = ByteArray(1024)
            val readCount = withTimeout(2_000L) {
                shell.read(buf, 0, buf.size)
            }
            assertTrue(readCount > 0)
            val welcome = buf.decodeToString(0, readCount)
            assertTrue(welcome.contains("Welcome to RouterOS"))
        } finally {
            ssh.close()
        }
    }

    @Test
    fun authFailureThrowsPermissionDenied() = runBlocking {
        val server = FakeMacServer(
            serverMac = testServerMac,
            clientMac = testClientMac,
            expectedUser = "admin",
            expectedPassword = "correctPassword",
            authMode = FakeMacAuthMode.MTWEI,
        )

        val ex = assertFailsWith<DeviceException> {
            connectMacTelnet(
                destinationMac = testServerMac,
                user = "admin",
                password = "wrongPassword",
                scope = this,
                sourceMac = testClientMac,
                transport = server,
                destinationAddress = "127.0.0.1",
                timeoutMillis = 5_000L,
            )
        }
        assertEquals(ErrorCategory.PermissionDenied, ex.category)
    }

    @Test
    fun terminalResizeSendsControlBlocks() = runBlocking {
        val server = FakeMacServer(
            serverMac = testServerMac,
            clientMac = testClientMac,
            expectedUser = "admin",
            expectedPassword = "secret",
            authMode = FakeMacAuthMode.MTWEI,
        )

        val ssh = connectMacTelnet(
            destinationMac = testServerMac,
            user = "admin",
            password = "secret",
            scope = this,
            sourceMac = testClientMac,
            transport = server,
            destinationAddress = "127.0.0.1",
            timeoutMillis = 5_000L,
        )

        try {
            val shell = ssh.shell("vt102", 80, 24)
            shell.resize(120, 45)

            until { server.lastTerminalWidth == 120 && server.lastTerminalHeight == 45 }
            assertEquals(120, server.lastTerminalWidth)
            assertEquals(45, server.lastTerminalHeight)
        } finally {
            ssh.close()
        }
    }

    @Test
    fun retransmissionOnDroppedSessionStart() = runBlocking {
        val server = FakeMacServer(
            serverMac = testServerMac,
            clientMac = testClientMac,
            expectedUser = "admin",
            expectedPassword = "secret",
            authMode = FakeMacAuthMode.MTWEI,
            dropFirstSessionStart = true,
        )

        val ssh = connectMacTelnet(
            destinationMac = testServerMac,
            user = "admin",
            password = "secret",
            scope = this,
            sourceMac = testClientMac,
            transport = server,
            destinationAddress = "127.0.0.1",
            timeoutMillis = 5_000L,
        )

        try {
            val shell = ssh.shell("vt102", 80, 24)
            val buf = ByteArray(1024)
            val readCount = withTimeout(2_000L) {
                shell.read(buf, 0, buf.size)
            }
            assertTrue(readCount > 0)
        } finally {
            ssh.close()
        }
    }

    @Test
    fun connectionTimeoutClosesSessionAndFailsWithLoginTimeout() = runBlocking {
        val silentTransport = object : MacTelnetTransport {
            override fun send(bytes: ByteArray, destination: String, port: Int) {}
            override fun onMessage(handler: (bytes: ByteArray, source: String, port: Int) -> Unit) {}
            override fun close() {}
        }

        assertFailsWith<LoginTimeoutException> {
            connectMacTelnet(
                destinationMac = testServerMac,
                user = "admin",
                password = "secret",
                scope = this,
                sourceMac = testClientMac,
                transport = silentTransport,
                destinationAddress = "127.0.0.1",
                timeoutMillis = 50L,
            )
        }
        Unit
    }

    @Test
    fun serverEndBeforeReadyFailsImmediately() = runBlocking {
        val rejectingTransport = object : MacTelnetTransport {
            private var onMsg: ((ByteArray, String, Int) -> Unit)? = null
            override fun onMessage(handler: (bytes: ByteArray, source: String, port: Int) -> Unit) {
                onMsg = handler
            }
            override fun send(bytes: ByteArray, destination: String, port: Int) {
                val header = decodeHeader(bytes, fromServer = false)
                if (header.type == MacTelnetPacketType.SESSION_START) {
                    val end = encodeHeader(
                        type = MacTelnetPacketType.END,
                        sourceMac = testServerMac,
                        destinationMac = testClientMac,
                        sessionKey = header.sessionKey,
                        counter = 0,
                        fromServer = true,
                    )
                    onMsg?.invoke(end, "127.0.0.1", MAC_TELNET_PORT)
                }
            }
            override fun close() {}
        }

        val start = System.currentTimeMillis()
        val ex = assertFailsWith<DeviceException> {
            connectMacTelnet(
                destinationMac = testServerMac,
                user = "admin",
                password = "secret",
                scope = this,
                sourceMac = testClientMac,
                transport = rejectingTransport,
                destinationAddress = "127.0.0.1",
                timeoutMillis = 10_000L,
            )
        }
        assertTrue(System.currentTimeMillis() - start < 2_000L)
        assertEquals(ErrorCategory.ConnectionLost, ex.category)
    }

    @Test
    fun sequentialChunkingForLargeWrites() = runBlocking {
        val server = FakeMacServer(
            serverMac = testServerMac,
            clientMac = testClientMac,
            expectedUser = "admin",
            expectedPassword = "secret",
            authMode = FakeMacAuthMode.MTWEI,
        )

        val ssh = connectMacTelnet(
            destinationMac = testServerMac,
            user = "admin",
            password = "secret",
            scope = this,
            sourceMac = testClientMac,
            transport = server,
            destinationAddress = "127.0.0.1",
            timeoutMillis = 5_000L,
        )

        try {
            val shell = ssh.shell("vt102", 80, 24)
            val largeData = ByteArray(2500) { (it % 26 + 'a'.code).toByte() }
            shell.write(largeData)

            until {
                val totalReceived = server.receivedPayloads.sumOf { it.size }
                totalReceived >= 2500
            }
            val concatenated = ByteArrayOutputStream()
            server.receivedPayloads.forEach { concatenated.write(it) }
            assertTrue(concatenated.toByteArray().contentEquals(largeData))
        } finally {
            ssh.close()
        }
    }

    @Test
    fun ignoresForeignPacketsWithMismatchedSessionKeyOrMac() = runBlocking {
        val server = FakeMacServer(
            serverMac = testServerMac,
            clientMac = testClientMac,
            expectedUser = "admin",
            expectedPassword = "secret",
            authMode = FakeMacAuthMode.MTWEI,
        )

        val ssh = connectMacTelnet(
            destinationMac = testServerMac,
            user = "admin",
            password = "secret",
            scope = this,
            sourceMac = testClientMac,
            transport = server,
            destinationAddress = "127.0.0.1",
            timeoutMillis = 5_000L,
        )

        try {
            val wrongKeyPacket = encodeHeader(
                type = MacTelnetPacketType.ACK,
                sourceMac = testServerMac,
                destinationMac = testClientMac,
                sessionKey = 0x9999,
                counter = 0,
                fromServer = true,
            )
            server.onClientPacket?.invoke(wrongKeyPacket, "127.0.0.1", MAC_TELNET_PORT)

            val wrongMacPacket = encodeHeader(
                type = MacTelnetPacketType.ACK,
                sourceMac = MacAddress.parse("de:ad:be:ef:00:01"),
                destinationMac = testClientMac,
                sessionKey = server.sessionKey,
                counter = 0,
                fromServer = true,
            )
            server.onClientPacket?.invoke(wrongMacPacket, "127.0.0.1", MAC_TELNET_PORT)

            val shell = ssh.shell("vt102", 80, 24)
            val buf = ByteArray(1024)
            val welcomeCount = withTimeout(2_000L) {
                shell.read(buf, 0, buf.size)
            }
            assertTrue(welcomeCount > 0)

            server.sendTerminalOutput("Still working\r\n")
            val readCount = withTimeout(2_000L) {
                shell.read(buf, 0, buf.size)
            }
            assertTrue(readCount > 0)
            assertTrue(buf.decodeToString(0, readCount).contains("Still working"))
        } finally {
            ssh.close()
        }
    }

    @Test
    fun dropsOutOfOrderPacketsAndReAcksExpectedCounter() = runBlocking {
        val server = FakeMacServer(
            serverMac = testServerMac,
            clientMac = testClientMac,
            expectedUser = "admin",
            expectedPassword = "secret",
            authMode = FakeMacAuthMode.MTWEI,
        )

        val ssh = connectMacTelnet(
            destinationMac = testServerMac,
            user = "admin",
            password = "secret",
            scope = this,
            sourceMac = testClientMac,
            transport = server,
            destinationAddress = "127.0.0.1",
            timeoutMillis = 5_000L,
        )

        try {
            val shell = ssh.shell("vt102", 80, 24)
            val buf = ByteArray(1024)
            withTimeout(2_000L) { shell.read(buf, 0, buf.size) }

            val gapPayload = "GAP_DATA".encodeToByteArray()
            val gapHeader = encodeHeader(
                type = MacTelnetPacketType.DATA,
                sourceMac = testServerMac,
                destinationMac = testClientMac,
                sessionKey = server.sessionKey,
                counter = server.serverOutCounter + 100L,
                fromServer = true,
            )
            val gapPacket = gapHeader + gapPayload
            server.onClientPacket?.invoke(gapPacket, "127.0.0.1", MAC_TELNET_PORT)

            val validPayload = "VALID_DATA".encodeToByteArray()
            val validHeader = encodeHeader(
                type = MacTelnetPacketType.DATA,
                sourceMac = testServerMac,
                destinationMac = testClientMac,
                sessionKey = server.sessionKey,
                counter = server.serverOutCounter,
                fromServer = true,
            )
            val validPacket = validHeader + validPayload
            server.onClientPacket?.invoke(validPacket, "127.0.0.1", MAC_TELNET_PORT)

            val readCount = withTimeout(2_000L) {
                shell.read(buf, 0, buf.size)
            }
            val text = buf.decodeToString(0, readCount)
            assertTrue(text.contains("VALID_DATA"))
            assertTrue(!text.contains("GAP_DATA"))
        } finally {
            ssh.close()
        }
    }

    @Test
    fun readDrainsIncomingChunksBeforeThrowingFailure() = runBlocking {
        val server = FakeMacServer(
            serverMac = testServerMac,
            clientMac = testClientMac,
            expectedUser = "admin",
            expectedPassword = "secret",
            authMode = FakeMacAuthMode.MTWEI,
        )

        val ssh = connectMacTelnet(
            destinationMac = testServerMac,
            user = "admin",
            password = "secret",
            scope = this,
            sourceMac = testClientMac,
            transport = server,
            destinationAddress = "127.0.0.1",
            timeoutMillis = 5_000L,
        )

        try {
            val shell = ssh.shell("vt102", 80, 24)
            val buf = ByteArray(1024)
            val initial = shell.read(buf, 0, buf.size)
            assertTrue(initial > 0)

            server.sendTerminalOutput("Final Message Before Close")
            val endPacket = encodeHeader(
                type = MacTelnetPacketType.END,
                sourceMac = testServerMac,
                destinationMac = testClientMac,
                sessionKey = server.sessionKey,
                counter = server.serverOutCounter,
                fromServer = true,
            )
            server.onClientPacket?.invoke(endPacket, "127.0.0.1", MAC_TELNET_PORT)

            val readCount = shell.read(buf, 0, buf.size)
            assertTrue(readCount > 0)
            assertEquals("Final Message Before Close", buf.decodeToString(0, readCount))

            val nextRead = shell.read(buf, 0, buf.size)
            assertEquals(-1, nextRead)
        } finally {
            ssh.close()
        }
    }

    @Test
    fun resizeWaitsForAWriteInFlightAndBothAreAcknowledged() = runBlocking {
        val server = FakeMacServer(
            serverMac = testServerMac,
            clientMac = testClientMac,
            expectedUser = "admin",
            expectedPassword = "secret",
            authMode = FakeMacAuthMode.MTWEI,
        )

        val ssh = connectMacTelnet(
            destinationMac = testServerMac,
            user = "admin",
            password = "secret",
            scope = this,
            sourceMac = testClientMac,
            transport = server,
            destinationAddress = "127.0.0.1",
            timeoutMillis = 5_000L,
        )

        try {
            val shell = ssh.shell("vt102", 100, 30)
            until { server.lastTerminalWidth == 100 && server.lastTerminalHeight == 30 }

            server.holdAcks = true
            val write = async { shell.write("show".encodeToByteArray()) }
            until { server.receivedTerminalStrings.any { it.contains("show") } }
            shell.resize(120, 40)
            server.releaseHeldAck()

            withTimeout(2_000L) { write.await() }
            until { server.lastTerminalWidth == 120 && server.lastTerminalHeight == 40 }
        } finally {
            ssh.close()
        }
    }

    @Test
    fun writeAfterCloseFailsWithConnectionLost() = runBlocking {
        val server = FakeMacServer(
            serverMac = testServerMac,
            clientMac = testClientMac,
            expectedUser = "admin",
            expectedPassword = "secret",
            authMode = FakeMacAuthMode.MTWEI,
        )

        val ssh = connectMacTelnet(
            destinationMac = testServerMac,
            user = "admin",
            password = "secret",
            scope = this,
            sourceMac = testClientMac,
            transport = server,
            destinationAddress = "127.0.0.1",
            timeoutMillis = 5_000L,
        )

        val shell = ssh.shell("vt102", 80, 24)
        ssh.close()

        val ex = assertFailsWith<DeviceException> { shell.write("show".encodeToByteArray()) }
        assertEquals(ErrorCategory.ConnectionLost, ex.category)
    }

    private suspend fun until(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                error("Timed out waiting for condition")
            }
            delay(10)
        }
    }
}
