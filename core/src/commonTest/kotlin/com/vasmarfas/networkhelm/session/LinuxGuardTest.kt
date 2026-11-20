package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxGuardTest {

    // a host whose session ends with the change, as one that sends its answers into a new tunnel does
    private class Cut : SshSession {
        val ran = mutableListOf<String>()
        var alive = true

        override suspend fun exec(command: String): ExecResult {
            if (!alive) throw IllegalStateException("session is down")
            ran += command
            return when {
                command == "id -u" -> ExecResult("0\n", "", 0)
                command.startsWith("B=") -> ExecResult("4242 /tmp/networkhelm-vpn.Ab12Cd\n", "", 0)
                else -> ExecResult("", "", 0)
            }
        }

        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    @Test
    fun aChangeThatCutsTheSessionTellsThatTheHostPutsItBackItself() = runTest {
        val host = Cut()
        val guard = LinuxGuard(host, Root(host, null), null)
        val failed = assertFailsWith<DeviceException> {
            guard.guarded(LinuxGuard.Backup("vpn", "cp -a /etc/wireguard \"\$B/wg\"", "cp -a \"\$1/wg\" /etc")) {
                host.alive = false
                throw IllegalStateException("session is down")
            }
        }
        assertEquals(ErrorCategory.ConnectionLost, failed.category)
        assertEquals("the host stopped answering after the change: what it had comes back in a minute", failed.message)
        assertTrue(host.ran.none { it.startsWith("kill") }, "${host.ran}")
    }
}
