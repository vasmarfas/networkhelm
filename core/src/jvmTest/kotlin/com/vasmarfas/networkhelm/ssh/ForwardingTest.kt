package com.vasmarfas.networkhelm.ssh

import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.TcpListener
import com.vasmarfas.networkhelm.transport.listenTcp
import com.vasmarfas.networkhelm.transport.openTcp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForwardingTest {

    private class Direct(private val refuse: String? = null) : SshSession {
        override suspend fun exec(command: String): ExecResult = error("no exec")
        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = if (refuse != null) throw IllegalStateException(refuse) else openTcp(host, port)
        override fun close() = Unit
    }

    private suspend fun ByteStream.readText(size: Int): String {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = read(buffer, read, size - read)
            if (count < 0) break
            read += count
        }
        return buffer.copyOf(read).decodeToString()
    }

    @Test
    fun aListenerThatFailsEndsTheTunnelAndTellsWhy() = runBlocking {
        val broken = object : TcpListener {
            override val port = 0

            override suspend fun accept(): ByteStream = throw IOException("too many open files")

            override fun close() = Unit
        }
        val forwarding = Forwarding(Direct(), broken, "127.0.0.1", 80)
        forwarding.serve()
        assertEquals("too many open files", forwarding.refused.value)
    }

    @Test
    fun bytesGoThroughTheTunnelBothWays() = runBlocking(Dispatchers.Default) {
        withTimeout(10_000) {
            val echo = listenTcp("127.0.0.1", 0)
            launch {
                val peer = echo.accept()
                val buffer = ByteArray(1024)
                while (true) {
                    val read = peer.read(buffer, 0, buffer.size)
                    if (read < 0) break
                    peer.write(buffer.copyOf(read))
                }
                peer.close()
            }
            val forwarding = Forwarding(Direct(), listenTcp("127.0.0.1", 0), "127.0.0.1", echo.port)
            val served = async { forwarding.serve() }
            val client = openTcp("127.0.0.1", forwarding.localPort)
            client.write("rd-test".encodeToByteArray())
            assertEquals("rd-test", client.readText(7))
            forwarding.connections.first { it == 1 }
            forwarding.close()
            served.await()
            assertEquals(-1, client.read(ByteArray(1), 0, 1))
            forwarding.connections.first { it == 0 }
            client.close()
            echo.close()
        }
    }

    @Test
    fun aRefusedConnectionClosesAndTellsWhy() = runBlocking(Dispatchers.Default) {
        withTimeout(10_000) {
            val forwarding = Forwarding(Direct(refuse = "administratively prohibited"), listenTcp("127.0.0.1", 0), "192.0.2.1", 80)
            val served = async { forwarding.serve() }
            val client = openTcp("127.0.0.1", forwarding.localPort)
            assertEquals(-1, client.read(ByteArray(1), 0, 1))
            assertTrue(forwarding.refused.first { it != null }.orEmpty().contains("administratively prohibited"))
            client.close()
            forwarding.close()
            served.await()
        }
    }
}
