package com.vasmarfas.networkhelm.ui.terminal

import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalStartupTest {

    private class Console : ShellChannel {
        val shown = Channel<String>(Channel.UNLIMITED)
        val written = StringBuilder()
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val bytes = shown.receive().encodeToByteArray()
            bytes.copyInto(buffer, offset)
            return bytes.size
        }
        override suspend fun write(bytes: ByteArray) {
            written.append(bytes.decodeToString())
        }
        override fun resize(columns: Int, rows: Int) = Unit
        override fun close() = Unit
    }

    private class Ssh(val console: Console) : SshSession {
        override suspend fun exec(command: String): ExecResult = error("no exec")
        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = console
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    @Test
    fun theLineForTheConsoleOfRouterOsWaitsForItsPrompt() = runTest {
        val console = Console()
        val shell = Consoles().open(1, Ssh(console), "/container/shell *2", atPrompt = true)
        val buffer = ByteArray(1024)
        console.shown.send("\r\n  MikroTik RouterOS 7.25 (c) 1999-2026       https://www.mikrotik.com/\r\n\r\nPress F1 for help\r\n")
        shell.read(buffer, 0, buffer.size)
        assertEquals("", console.written.toString())
        console.shown.send("\r\u001b[K[admin@CHR] ")
        shell.read(buffer, 0, buffer.size)
        console.shown.send("> \u001b[K")
        shell.read(buffer, 0, buffer.size)
        assertEquals("/container/shell *2\r", console.written.toString())
        console.shown.send("\r\n[admin@CHR] > ")
        shell.read(buffer, 0, buffer.size)
        assertEquals("/container/shell *2\r", console.written.toString())
    }

    @Test
    fun theLineForAnotherShellGoesAtOnce() = runTest {
        val console = Console()
        Consoles().open(1, Ssh(console), "exec docker exec -it 'web' sh")
        assertEquals("exec docker exec -it 'web' sh\r", console.written.toString())
    }
}
