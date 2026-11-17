package com.vasmarfas.networkhelm.ui.terminal

import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KeptShellTest {

    private class Console(chunks: List<String>) : ShellChannel {
        val written = mutableListOf<String>()
        private val left = ArrayDeque(chunks)

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val bytes = left.removeFirstOrNull()?.encodeToByteArray() ?: return -1
            bytes.copyInto(buffer, offset)
            return bytes.size
        }

        override suspend fun write(bytes: ByteArray) {
            written += bytes.decodeToString()
        }

        override fun resize(columns: Int, rows: Int) = Unit
        override fun close() = Unit
    }

    private val ssh = object : SshSession {
        override suspend fun exec(command: String): ExecResult = error("no exec")
        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    @Test
    fun theRootButtonGivesSudoThePasswordWhenSudoAsksAndNotWhenTheLineIsEchoed() = runTest {
        val console = Console(listOf("sudo -p 'NetworkHelm sudo for %u: ' -s\r\n", "NetworkHelm sudo for rd", "-test: ", "\r\nroot@rd-test:~# ", "NetworkHelm sudo for rd-test: "))
        val kept = KeptShell(console, ssh)
        val buffer = ByteArray(256)
        kept.elevate("rd-test-pw")
        assertEquals(listOf("sudo -p 'NetworkHelm sudo for %u: ' -s\r"), console.written)
        kept.read(buffer, 0, buffer.size)
        kept.read(buffer, 0, buffer.size)
        assertEquals(1, console.written.size)
        kept.read(buffer, 0, buffer.size)
        assertEquals("rd-test-pw\r", console.written.last())
        kept.read(buffer, 0, buffer.size)
        kept.read(buffer, 0, buffer.size)
        assertEquals(2, console.written.size)
    }

    @Test
    fun withoutThePasswordTheButtonOnlyTypesSudo() = runTest {
        val console = Console(listOf("NetworkHelm sudo for rd-test: "))
        val kept = KeptShell(console, ssh)
        kept.elevate("")
        kept.read(ByteArray(64), 0, 64)
        assertEquals(listOf("sudo -p 'NetworkHelm sudo for %u: ' -s\r"), console.written)
    }
}
