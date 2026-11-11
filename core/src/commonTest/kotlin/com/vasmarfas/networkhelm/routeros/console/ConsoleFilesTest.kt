package com.vasmarfas.networkhelm.routeros.console

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.api.ApiReply
import com.vasmarfas.networkhelm.routeros.api.ApiResult
import com.vasmarfas.networkhelm.routeros.api.ApiTrapException
import com.vasmarfas.networkhelm.routeros.api.RouterOsLink
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.RemoteFile
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val DATA = Regex("^:local c \\[:convert from=base64 to=raw \"([^\"]*)\"\\]; (.*)$")
private val NAME = Regex("name=\"([^\"]*)\"")
private val READ = Regex("^:local f \\[/file find name=\"([^\"]*)\"\\]; :put \\[/file get \\\$f size\\]; :put \\[:convert to=base64 \\[/file get \\\$f contents\\]\\]$")

private class FilesRouter : RouterOsLink {
    val files = linkedMapOf<String, ByteArray>()
    val scripts = mutableListOf<String>()
    var more = 0

    override suspend fun call(words: List<String>): ApiResult = when (words.first()) {
        "/file/print" -> ApiResult(files.map { (name, bytes) -> mapOf("name" to name, "type" to "file", "size" to "${bytes.size}") } + mapOf("name" to "dir", "type" to "directory"), emptyMap())
        "/file/remove" -> {
            files.remove(words[1].removePrefix("=.id=")) ?: throw ApiTrapException(0, "no such item (4)")
            ApiResult(emptyList(), emptyMap())
        }
        else -> {
            val script = words[1].removePrefix("=script=")
            scripts += script
            ApiResult(emptyList(), mapOf("ret" to run(script).trim()))
        }
    }

    private fun run(script: String): String {
        val name = NAME.find(script)?.groupValues?.get(1)
        DATA.matchEntire(script)?.let { match ->
            val (data, rest) = match.destructured
            val bytes = Base64.decode(data)
            checkNotNull(name)
            files[name] = if (":if" in rest) bytes else (files[name] ?: throw ApiTrapException(0, "no such item (4)")) + bytes
            return ""
        }
        val read = READ.matchEntire(script) ?: throw ApiTrapException(0, "bad command: $script")
        val bytes = files[read.groupValues[1]] ?: throw ApiTrapException(0, "no such item (4)")
        return "${bytes.size + more}\n" + Base64.encode(bytes)
    }

    override fun command(words: List<String>, sent: CompletableDeferred<Unit>?): Flow<ApiReply> = error("no streams")
}

private val CLASSIC_READ = Regex("^:local f \\[/file find name=\"([^\"]*)\"\\]; :put \\[/file get \\\$f size\\]; :put \\[:len \\[/file get \\\$f contents\\]\\]; :put \"\\\\02\"; :put \\[/file get \\\$f contents\\]; :put \"\\\\03\"$")
private val CLASSIC_COUNT = Regex("^:put \\[:len \\[/file find name=\"([^\"]*)\"\\]\\]$")
private val CLASSIC_SIZE = Regex("^:put \\[/file get \\[/file find name=\"([^\"]*)\"\\] size\\]$")
private val CLASSIC_CREATE = Regex("^/file print file=\"([^\"]*)\"$")
private val CLASSIC_SET = Regex("^:put \\[/file set \\[/file find name=\"([^\"]*)\"\\] contents=(\\(\\[/file get \\[/file find name=\"[^\"]*\"\\] contents\\] \\. )?(\"[^\"]*\")\\)?\\]$")

private fun unquoted(text: String): ByteArray {
    val bytes = ArrayList<Byte>()
    var at = 1
    while (at < text.length - 1) {
        if (text[at] == '\\') {
            bytes += text.substring(at + 1, at + 3).toInt(16).toByte()
            at += 3
        } else {
            bytes += text[at++].code.toByte()
        }
    }
    return bytes.toByteArray()
}

private class ClassicRouter : RouterOsLink {
    val files = linkedMapOf<String, ByteArray>()
    val scripts = mutableListOf<String>()
    var keeps = Int.MAX_VALUE

    override suspend fun call(words: List<String>): ApiResult {
        val script = words[1].removePrefix("=script=")
        scripts += script
        return ApiResult(emptyList(), mapOf("ret" to run(script).trim()))
    }

    private fun file(name: String) = files[name] ?: throw ApiTrapException(0, "no such item")

    private fun run(script: String): String {
        CLASSIC_READ.matchEntire(script)?.let { read ->
            val bytes = file(read.groupValues[1])
            return "${bytes.size}\n${minOf(keeps, bytes.size)}\n\u0002\n${bytes.decodeToString().replace("\r", "")}\n\u0003"
        }
        CLASSIC_COUNT.matchEntire(script)?.let { return if (it.groupValues[1] in files) "1" else "0" }
        CLASSIC_SIZE.matchEntire(script)?.let { return "${file(it.groupValues[1]).size}" }
        CLASSIC_CREATE.matchEntire(script)?.let {
            files[it.groupValues[1] + ".txt"] = "# a print of the files".encodeToByteArray()
            return ""
        }
        val set = CLASSIC_SET.matchEntire(script) ?: throw ApiTrapException(0, "bad command: $script")
        val name = set.groupValues[1]
        val part = unquoted(set.groupValues[3])
        files[name] = (if (set.groupValues[2].isEmpty()) part else file(name) + part).let { if (it.size > keeps) it.copyOf(keeps) else it }
        return ""
    }

    override fun command(words: List<String>, sent: CompletableDeferred<Unit>?): Flow<ApiReply> = error("no streams")
}

class ConsoleFilesSshTest {
    private class Channel(private val files: () -> SftpClient) : SshSession {
        override suspend fun exec(command: String): ExecResult = error("no exec")
        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp(): SftpClient = files()
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override val canForward = false
        override fun close() = Unit
    }

    private val console = ConsoleFiles(FilesRouter(), Dialect.Serialize, ApiEncoding.Utf8)

    @Test
    fun theConsoleGivesTheFilesOfAChannelThatHasNoSftp() = runTest {
        val ssh = ConsoleFilesSsh(Channel { throw DeviceException(ErrorCategory.Unsupported, "no sftp") }, console)
        assertSame(console, ssh.sftp())
        assertFalse(ssh.canForward)
    }

    @Test
    fun theSftpOfAChannelIsKeptAndItsOtherFailuresAreNotHidden() = runTest {
        val own = ConsoleFiles(FilesRouter(), Dialect.Serialize, ApiEncoding.Utf8)
        assertSame(own, ConsoleFilesSsh(Channel { own }, console).sftp())
        assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { ConsoleFilesSsh(Channel { throw DeviceException(ErrorCategory.ConnectionLost, "gone") }, console).sftp() }.category)
    }
}

class ConsoleFilesClassicTest {
    private val router = ClassicRouter()
    private val files = ConsoleFiles(router, Dialect.Classic, ApiEncoding.Utf8)

    @Test
    fun aTextFileIsReadWithItsWhitespaceAndWithoutItsCarriageReturns() = runTest {
        router.files["a.txt"] = "\n  one\r\ntwo  \n\n".encodeToByteArray()
        assertEquals("\n  one\ntwo  \n\n", files.read("/a.txt").decodeToString())
    }

    @Test
    fun aFileThatTheRouterGivesShortIsNotTakenForWhole() = runTest {
        router.files["a.txt"] = ByteArray(10) { 65 }
        router.keeps = 4
        assertEquals("the router gives 4 of the 10 bytes of the file", assertFailsWith<DeviceException> { files.read("/a.txt") }.message)
    }

    @Test
    fun theTextIsGivenInTheEncodingOfTheRouter() = runTest {
        router.files["a.txt"] = "Привет".encodeToByteArray()
        assertContentEquals("Привет".encodeToByteArray(), files.read("/a.txt"))
        assertContentEquals(ApiEncoding.Windows1251.encode("Привет"), ConsoleFiles(router, Dialect.Classic, ApiEncoding.Windows1251).read("/a.txt"))
    }

    @Test
    fun aFileThatIsThereIsWrittenInShortLinesEachAddingItsPart() = runTest {
        router.files["rd-test.rsc"] = ByteArray(1)
        val text = "/ip address add address=192.0.2.1/24 comment=\"x;y\" \$z [w]\n".repeat(30)
        files.write("/rd-test.rsc", text.encodeToByteArray())
        assertEquals(text, router.files.getValue("rd-test.rsc").decodeToString())
        assertTrue(router.scripts.size > 5)
        assertTrue(router.scripts.all { it.length < 600 }, router.scripts.maxOf { it.length }.toString())
    }

    @Test
    fun aNewTxtFileIsMadeByPrintAndAnyOtherNewFileIsRefused() = runTest {
        files.write("/rd-test.txt", "hello".encodeToByteArray())
        assertEquals("hello", router.files.getValue("rd-test.txt").decodeToString())
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { files.write("/rd-test.rsc", ByteArray(1)) }.category)
    }

    @Test
    fun aFileLongerThanTheConsoleCarriesIsRefusedAndOneThatWasKeptShortIsNoted() = runTest {
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { files.write("/a.txt", ByteArray(5_000)) }.category)
        router.files["a.txt"] = ByteArray(1)
        router.keeps = 10
        assertEquals("the router keeps 10 of the 50 bytes that were written", assertFailsWith<DeviceException> { files.write("/a.txt", ByteArray(50) { 66 }) }.message)
    }
}

class ConsoleFilesTest {
    private val router = FilesRouter()
    private val files = ConsoleFiles(router, Dialect.Serialize, ApiEncoding.Utf8)

    @Test
    fun aFolderListsWhatIsRightInIt() = runTest {
        router.files["a.txt"] = ByteArray(3)
        router.files["dir/b.txt"] = ByteArray(5)
        router.files["dir/deeper/c.txt"] = ByteArray(7)
        val top = files.list("/").associateBy { it.name }
        assertEquals(setOf("a.txt", "dir"), top.keys)
        assertEquals(3, top.getValue("a.txt").size)
        assertTrue(top.getValue("dir").directory)
        assertEquals(listOf("b.txt"), files.list("/dir").map(RemoteFile::name))
    }

    @Test
    fun aFileComesWholeWhateverItHolds() = runTest {
        val bytes = byteArrayOf(0, 13, 10, -1, 65, 27)
        router.files["rd-test.bin"] = bytes
        assertContentEquals(bytes, files.read("/rd-test.bin"))
    }

    @Test
    fun anEmptyFileIsReadAndOneThatTheRouterGivesShortIsNotTakenForWhole() = runTest {
        router.files["empty"] = ByteArray(0)
        assertContentEquals(ByteArray(0), files.read("/empty"))
        router.files["cut"] = ByteArray(3)
        router.more = 5
        assertEquals("the router gave 3 of the 8 bytes of the file; its console carries about 64 KB of a file at most", assertFailsWith<DeviceException> { files.read("/cut") }.message)
    }

    @Test
    fun aFileIsWrittenByOneLineOrByManyEachAddingItsPart() = runTest {
        files.write("/small.txt", "hello".encodeToByteArray())
        assertEquals(1, router.scripts.size)
        assertEquals("hello", router.files.getValue("small.txt").decodeToString())
        router.scripts.clear()
        val big = ByteArray(40_000) { (it * 31).toByte() }
        files.write("/big.bin", big)
        assertTrue(router.scripts.size > 3)
        assertTrue(router.scripts.all { it.length <= LINE_LIMIT })
        assertContentEquals(big, router.files.getValue("big.bin"))
        files.write("/big.bin", "x".encodeToByteArray())
        assertEquals("x", router.files.getValue("big.bin").decodeToString())
    }

    @Test
    fun anEmptyFileIsWritten() = runTest {
        files.write("/empty", ByteArray(0))
        assertContentEquals(ByteArray(0), router.files.getValue("empty"))
    }

    @Test
    fun aFileLongerThanTheRouterTakesIsRefusedBeforeAnythingIsSent() = runTest {
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { files.write("/big", ByteArray(1_000_000)) }.category)
        assertTrue(router.scripts.isEmpty())
    }

    @Test
    fun aFileIsRemovedByItsName() = runTest {
        router.files["gone.txt"] = ByteArray(1)
        files.remove("/gone.txt")
        assertTrue(router.files.isEmpty())
        assertEquals(ErrorCategory.Api, assertFailsWith<DeviceException> { files.remove("/gone.txt") }.category)
    }

    @Test
    fun aNameTheEncodingOfTheRouterHasNoCodeForIsRefusedAsAValue() = runTest {
        val cyrillic = ConsoleFiles(router, Dialect.Serialize, ApiEncoding.Windows1251)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { cyrillic.write("/\u4E2D.txt", ByteArray(1)) }.category)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { cyrillic.read("/\u4E2D.txt") }.category)
        assertTrue(router.scripts.isEmpty())
    }

    @Test
    fun theTextOfTheRouterIsWhatTheErrorSays() = runTest {
        assertEquals("no such item (4)", assertFailsWith<DeviceException> { files.read("/none") }.message)
    }

}
