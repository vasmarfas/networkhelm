package com.vasmarfas.networkhelm.routeros

import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ConsoleSafeModeTest {

    private class Console(firstLogin: Boolean, quiet: Boolean) : ShellChannel {
        private val output = Channel<String>(Channel.UNLIMITED).also { if (!quiet) it.trySend("\u001b[6n") }
        private val replies = ArrayDeque(
            if (firstLogin) {
                listOf(
                    "\r\nDo you want to see the software license? [Y/n]: ",
                    "n\r\nPress F1 for help\r\n\r\nChange your password (Ctrl-C to skip)\r\n\r\nnew password> ",
                )
            } else {
                emptyList()
            },
        )
        val answers = mutableListOf<String>()
        var safe = false
        var closed = false

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val bytes = output.receiveCatching().getOrNull()?.encodeToByteArray() ?: return -1
            bytes.copyInto(buffer, offset)
            return bytes.size
        }

        override suspend fun write(bytes: ByteArray) {
            if (bytes.contentEquals(byteArrayOf(0x18))) {
                safe = !safe
                output.send(if (safe) "[Safe Mode taken]\r\n[rd-test@lab] <SAFE> " else "[Safe Mode released]\r\n[rd-test@lab] > ")
            } else {
                answers += bytes.decodeToString()
                output.send(replies.removeFirstOrNull() ?: "\u001b[32m[rd-test@lab] > \u001b[0m")
            }
        }

        override fun resize(columns: Int, rows: Int) = Unit

        override fun close() {
            closed = true
            output.close()
        }
    }

    private class Ssh(val firstLogin: Boolean = false, val quiet: Boolean = false) : SshSession {
        val consoles = mutableListOf<Console>()
        override suspend fun exec(command: String): ExecResult = error("no exec")
        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = Console(firstLogin, quiet).also { consoles += it }
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anEnterCutOffLeavesNoConsoleBehind() = runTest {
        val ssh = Ssh(quiet = true)
        val safeMode = ConsoleSafeMode(ssh, backgroundScope, { 1 }, 1.seconds)
        val entering = launch { safeMode.enter() }
        runCurrent()
        entering.cancelAndJoin()
        assertTrue(ssh.consoles.single().closed)
        assertFalse(safeMode.active.first())
    }

    @Test
    fun theConsoleTakesTheModeKeepsTheChangesWithCtrlXAndRollsBackByEnding() = runTest {
        val ssh = Ssh()
        val safeMode = ConsoleSafeMode(ssh, backgroundScope, { 1 }, 1.seconds)

        assertTrue(safeMode.enter())
        val first = ssh.consoles.single()
        assertEquals(listOf("\u001b[50;200R"), first.answers, "the cursor query is answered with the size of the window")
        assertTrue(first.safe && safeMode.active.first())
        safeMode.commit()
        assertFalse(first.safe, "Ctrl+X again releases the mode with the changes kept")
        assertTrue(first.closed)
        assertFalse(safeMode.active.first())

        assertTrue(safeMode.enter())
        val second = ssh.consoles.last()
        safeMode.rollback()
        assertTrue(second.safe && second.closed, "the console ends while it holds the mode, so the router rolls back")
        assertFalse(safeMode.active.first())
    }

    @Test
    fun theModeAlreadyHeldIsNotTakenAgainThroughAnotherConsole() = runTest {
        val ssh = Ssh()
        val safeMode = ConsoleSafeMode(ssh, backgroundScope, { 1 }, 1.seconds)

        assertTrue(safeMode.enter())
        assertTrue(safeMode.enter())
        assertEquals(1, ssh.consoles.size)
        assertTrue(ssh.consoles.single().safe && safeMode.active.first())
    }

    @Test
    fun theQuestionsOfTheFirstLoginAreAnsweredBeforeTheModeIsTaken() = runTest {
        val ssh = Ssh(firstLogin = true)
        val safeMode = ConsoleSafeMode(ssh, backgroundScope, { 1 }, 1.seconds)

        assertTrue(safeMode.enter())
        val console = ssh.consoles.single()
        assertEquals(listOf("\u001b[50;200R", "n", "\u0003"), console.answers)
        assertTrue(console.safe)
        safeMode.rollback()
    }
}
