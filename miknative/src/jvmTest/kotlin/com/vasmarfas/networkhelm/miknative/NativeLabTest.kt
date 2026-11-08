package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.transport.openTcp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NativeLabTest {

    private fun connection(prefix: String, block: suspend CoroutineScope.(NativeConnection, Map<String, String>) -> Unit) = runBlocking {
        val lab = Lab.require("$prefix.host", "$prefix.user", "$prefix.password")
        withTimeout(60.seconds) {
            val connection = NativeConnection.open(lab.getValue("$prefix.host"), lab.getValue("$prefix.user"), lab.getValue("$prefix.password"), this)
            try {
                block(connection, lab)
            } finally {
                connection.close()
            }
        }
    }

    private suspend fun ShellChannel.readUntil(marker: String): String {
        val text = StringBuilder()
        val buffer = ByteArray(4096)
        while (marker !in text) {
            val count = read(buffer, 0, buffer.size)
            if (count < 0) break
            text.append(buffer.decodeToString(0, count))
        }
        return text.toString()
    }

    private suspend fun ShellChannel.run(command: String, marker: String = "] > "): String {
        write("$command\r".encodeToByteArray())
        return readUntil(marker)
    }

    private fun consoleTest(prefix: String, block: suspend CoroutineScope.(ShellChannel, Map<String, String>) -> Unit) =
        connection(prefix) { connection, lab ->
            val terminal = NativeTerminal.open(connection, lab.getValue("$prefix.password"), "xterm-256color", 100, 30)
            try {
                terminal.readUntil("] > ")
                block(terminal, lab)
            } finally {
                terminal.close()
            }
        }

    @Test
    fun routerOs7ConsoleAnswersACommand() = consoleTest("chr") { terminal, _ ->
        assertTrue("name:" in terminal.run("/system identity print", "name:"))
    }

    @Test
    fun routerOs6ConsoleAnswersACommand() = consoleTest("chr6") { terminal, _ ->
        assertTrue("name:" in terminal.run("/system identity print", "name:"))
    }

    @Test
    fun quitEndsTheRead() = consoleTest("chr") { terminal, _ ->
        terminal.write("/quit\r".encodeToByteArray())
        val buffer = ByteArray(4096)
        val ended = withTimeoutOrNull(10_000) {
            do {
                val count = terminal.read(buffer, 0, buffer.size)
            } while (count >= 0)
            true
        }
        assertTrue(ended == true)
    }

    @Test
    fun aLongOutputIsDeliveredWhole() = consoleTest("chr") { terminal, _ ->
        val script = """:for i from=1 to=1500 do={:put ("row " . ${'$'}i . " 0123456789012345678901234567890123456789")}"""
        val text = terminal.run(script, "row 1500 ")
        assertTrue(text.length > 60_000, "${text.length} characters")
        assertTrue("row 1 0123" in text)
        assertTrue("row 750 0123" in text)
    }

    @Test
    fun severalConsolesShareOneConnection() = connection("chr") { connection, lab ->
        val first = NativeTerminal.open(connection, lab.getValue("chr.password"), "xterm-256color", 100, 30)
        val second = NativeTerminal.open(connection, lab.getValue("chr.password"), "xterm-256color", 100, 30)
        try {
            first.readUntil("] > ")
            second.readUntil("] > ")
            val firstAnswer = first.run("/system identity print", "name:")
            val secondAnswer = second.run(":put (123*7)", "861")
            assertTrue("name:" in firstAnswer)
            assertTrue("861" in secondAnswer)
            assertFalse("861" in firstAnswer)
        } finally {
            first.close()
            second.close()
        }
    }

    private suspend fun ShellChannel.columnsOfInterfaces(): String {
        write("/interface print\r".encodeToByteArray())
        return readUntil("\n#").substringAfter("Columns:").substringBefore("\n#")
    }

    @Test
    fun resizeChangesTheWidthOfTables() = consoleTest("chr") { terminal, _ ->
        assertTrue("MAC-ADDRESS" in terminal.columnsOfInterfaces())
        terminal.resize(40, 20)
        delay(500)
        val narrow = terminal.columnsOfInterfaces()
        assertTrue("TYPE" in narrow, narrow)
        assertFalse("MAC-ADDRESS" in narrow, narrow)
    }

    private fun filesTest(prefix: String, size: Int) = connection(prefix) { connection, _ ->
        val files = NativeFiles(connection)
        val name = "rd-test-8291-$size.txt"
        val bytes = ByteArray(size) { (it * 31 + 7).toByte() }
        try {
            files.write("/$name", bytes)
            val listed = files.list("/")
            assertTrue(listed.any { it.name == name && it.size == bytes.size.toLong() && !it.directory }, listed.toString())
            assertContentEquals(bytes, files.read("/$name"))
        } finally {
            files.remove("/$name")
        }
        assertFalse(files.list("/").any { it.name == name })
    }

    @Test
    fun routerOs7FilesRoundTrip() = filesTest("chr", 1000)

    @Test
    fun routerOs7FilesInSeveralChunks() = filesTest("chr", 100_000)

    @Test
    fun routerOs7WorksInsideADirectory() = connection("chr") { connection, _ ->
        val files = NativeFiles(connection)
        val directory = files.list("/").first { it.directory }.name
        val name = "rd-test-8291-inside.txt"
        val path = "/$directory/$name"
        files.write(path, byteArrayOf(1, 2, 3))
        try {
            val inside = files.list("/$directory")
            assertTrue(inside.any { it.name == name }, inside.joinToString { it.name })
            assertContentEquals(byteArrayOf(1, 2, 3), files.read(path))
        } finally {
            files.remove(path)
        }
        assertFalse(files.list("/$directory").any { it.name == name })
    }

    @Test
    fun routerOs6FilesRoundTrip() = filesTest("chr6", 1000)

    @Test
    fun anEmptyFileIsKept() = filesTest("chr", 0)

    @Test
    fun noFileOfTheTestsIsLeftOnTheLab() {
        connection("chr") { connection, _ ->
            assertEquals(emptyList(), NativeFiles(connection).list("/").map { it.name }.filter { it.startsWith("rd-test-8291") })
        }
        connection("chr6") { connection, _ ->
            val files = NativeFiles(connection)
            for (name in listOf("1000", "six", "speed", "small").map { "rd-test-8291-$it" }) {
                val found = runCatching { files.read("/$name.txt") }.isSuccess || runCatching { files.read("/$name.bin") }.isSuccess
                assertFalse(found, name)
            }
        }
    }

    @Test
    fun aWrongPasswordIsRefused() = runBlocking {
        val lab = Lab.require("chr.host", "chr.user")
        withTimeout(30.seconds) {
            val stream = openTcp(lab.getValue("chr.host"), 8291)
            val error = assertFailsWith<DeviceException> { NativeConnection.open(stream, lab.getValue("chr.user"), "rd-test-8291-wrong", this) }
            assertEquals(ErrorCategory.PermissionDenied, error.category)
            stream.close()
        }
    }
}
