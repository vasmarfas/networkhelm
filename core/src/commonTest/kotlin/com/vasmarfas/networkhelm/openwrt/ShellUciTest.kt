package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShellUciTest {

    // uci batch ends with 0 and tells of a command it turned down on stderr alone
    private class Dropbear(private val errors: String) : SshSession {
        val commands = mutableListOf<String>()

        override suspend fun exec(command: String): ExecResult {
            commands += command
            return ExecResult("", errors, 0)
        }

        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp(): SftpClient = error("no sftp-server")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    @Test
    fun aCommandOfABatchThatUciTurnedDownIsAnError() = runTest {
        val refused = assertFailsWith<UbusException> { ShellUci(Dropbear("uci: Invalid argument\n")).set("network", "lan", mapOf("my-option" to "1")) }
        assertEquals(UbusStatus.UNKNOWN_ERROR to "uci: Invalid argument", refused.status to refused.message)
        val missing = assertFailsWith<UbusException> { ShellUci(Dropbear("uci: Entry not found\n")).delete("network", "lan", listOf("gateway")) }
        assertEquals(UbusStatus.NOT_FOUND, missing.status)
    }

    @Test
    fun aListThatIsNotThereYetIsFilledWithoutAnError() = runTest {
        val dns = mapOf("dns" to listOf("192.0.2.1", "192.0.2.2"))
        val dropbear = Dropbear("uci: Entry not found\n")
        ShellUci(dropbear).set("network", "lan", lists = dns)
        assertEquals(
            "mkdir -p /tmp/rd-uci; printf '%s\\n' ${quote("delete network.lan.dns\nadd_list network.lan.dns='192.0.2.1'\nadd_list network.lan.dns='192.0.2.2'")} | uci -P /tmp/rd-uci batch",
            dropbear.commands.single(),
        )
        val none = Dropbear("uci: Entry not found\n".repeat(3))
        assertEquals(UbusStatus.NOT_FOUND, assertFailsWith<UbusException> { ShellUci(none).set("rd_test_none", "lan", lists = dns) }.status)
    }
}
