package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellUciApplyTest {

    private val sh = if (System.getProperty("os.name").startsWith("Windows")) File(System.getenv("ProgramFiles"), "Git/bin/sh.exe").path else "sh"

    // the files of the router stand in a directory of the test, and uci is a function that prints what the test tells it to
    private inner class Router(private val uci: String) : SshSession {
        val directory: File = Files.createTempDirectory("shell-uci-").toFile()
        val config = File(directory, "config/network")
        val draft = File(directory, "rd-uci/network")
        val applied = File(directory, "rd-apply")

        init {
            assumeTrue("sh or cmp is unavailable", runCatching { ProcessBuilder(sh, "-c", "PATH=/usr/bin:/bin; command -v cmp >/dev/null").start().waitFor() == 0 }.getOrDefault(false))
            for (name in listOf("config", "rd-uci", "export")) File(directory, name).mkdir()
            config.writeText(OLD)
            draft.writeText("network.lan.proto='static'\n")
            File(directory, "export/network").writeText("package network\n$NEW")
        }

        override suspend fun exec(command: String): ExecResult {
            val local = command.replace("/tmp/rd-uci", "rd-uci").replace("/tmp/rd-apply", "rd-apply").replace("/etc/config/", "config/").replace("/tmp/.uci/", "console/")
            val process = ProcessBuilder(sh, "-s").directory(directory).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write("PATH=/usr/bin:/bin\nuci() { $uci; }\nreload_config() { :; }\n$local\n") }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return ExecResult(output, "", process.waitFor())
        }

        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp(): SftpClient = error("no SFTP")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forwarding")
        override fun close() = Unit
    }

    @Test
    fun anApplyWritesTheConfigAndKeepsTheOldOneForTheWayBack() = runTest {
        val router = Router("cat \"export/\$4\"")
        ShellUci(router).apply(rollbackSeconds = 20)
        assertEquals(NEW, router.config.readText())
        assertEquals(OLD, File(router.applied, "network.old").readText())
        assertFalse(router.draft.exists())
        ShellUci(router).rollback()
        assertEquals(OLD, router.config.readText())
        assertTrue(router.draft.exists())
        assertFalse(router.applied.exists())
    }

    @Test
    fun anExportThatGivesNothingLeavesTheConfigAsItWas() = runTest {
        val router = Router("return 1")
        val refused = assertFailsWith<UbusException> { ShellUci(router).apply(rollbackSeconds = 20) }
        assertTrue("nothing is applied" in refused.message.orEmpty(), refused.message)
        assertEquals(OLD, router.config.readText())
        assertTrue(router.draft.exists())
        assertFalse(router.applied.exists())
    }

    // as a file that /tmp cut short: what stands in it is not what uci exports
    @Test
    fun aNewConfigThatIsNotTheWholeExportLeavesTheConfigAsItWas() = runTest {
        val router = Router("cat \"export/\$4\"; echo \"config changed\" >> \"export/\$4\"")
        assertFailsWith<UbusException> { ShellUci(router).apply(rollbackSeconds = 20) }
        assertEquals(OLD, router.config.readText())
        assertTrue(router.draft.exists())
        assertFalse(router.applied.exists())
    }

    private companion object {
        const val OLD = "config interface 'lan'\n\toption proto 'dhcp'\n"
        const val NEW = "\nconfig interface 'lan'\n\toption proto 'static'\n\n"
    }
}
