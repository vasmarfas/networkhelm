package com.vasmarfas.networkhelm.ui.files

import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.RPCD_READ_LIMIT
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.HostKey
import com.vasmarfas.networkhelm.ssh.HostKeyRejectedException
import com.vasmarfas.networkhelm.ssh.RemoteFile
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshReach
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileBackendTest {

    private class Sftp(private val folders: Map<String, List<RemoteFile>> = emptyMap()) : SftpClient {
        val reads = mutableListOf<String>()
        val written = mutableMapOf<String, ByteArray>()
        override suspend fun list(path: String) = folders[path].orEmpty()
        override suspend fun read(path: String): ByteArray {
            reads += path
            return "bytes of $path".encodeToByteArray()
        }
        override suspend fun write(path: String, bytes: ByteArray) {
            written[path] = bytes
        }
        override suspend fun remove(path: String) = Unit
        val made = mutableListOf<String>()
        override suspend fun mkdir(path: String) {
            made += path
        }
        override fun close() = Unit
    }

    private class Ssh(
        val sftp: Sftp,
        private val answer: (Boolean) -> SshReach = { SshReach.Open },
        override val fileStore: Boolean = false,
        private val refuseKey: Boolean = false,
    ) : SshSession {
        var opened = 0
        override suspend fun reach(fresh: Boolean) = answer(fresh)
        override suspend fun exec(command: String) = ExecResult("", "", 0)
        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp(): SftpClient {
            opened++
            if (refuseKey) throw HostKeyRejectedException(HostKey("ssh-ed25519", "SHA256:rd-test"))
            return sftp
        }
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    private val files = MenuPath.parse("file")
    private val small = Entry("*1", "rc.local", false, 127, "", ".local file")
    private val big = Entry("*2", "export.rsc", false, 40_000, "", "script")
    private val texts = mapOf("*1" to "hello\n")

    private fun router(ssh: SshSession?, policies: Set<String> = setOf("read", "write", "ftp"), platform: Platform = Platform.RouterOs, channel: SftpClient? = null) =
        FakeRouter(ssh = ssh, policies = policies, platform = platform, fileTexts = texts, fileChannel = channel)

    @Test
    fun aSmallTextIsReadByTheCommandsAndNeverAsksSsh() = runTest {
        val ssh = Ssh(Sftp())
        val backend = FileBackend(router(ssh), files)
        assertEquals("hello\n", backend.read(small).decodeToString())
        assertEquals(FileVia.Api, backend.via.value)
        assertEquals(0, ssh.opened)
    }

    @Test
    fun aBigFileGoesOverSftp() = runTest {
        val ssh = Ssh(Sftp())
        val backend = FileBackend(router(ssh), files)
        assertEquals("bytes of /export.rsc", backend.read(big).decodeToString())
        assertEquals(FileVia.Sftp, backend.via.value)
        assertEquals(Bulk.Ready(FileVia.Sftp), backend.bulk.value)
        assertEquals(Route.Over(FileVia.Api), backend.readRoute(small))
    }

    @Test
    fun withSshOffASmallTextStillGoesAndABigFileIsRefusedWithTheReason() = runTest {
        val ssh = Ssh(Sftp(), answer = { SshReach.Off })
        val backend = FileBackend(router(ssh), files)
        assertEquals("hello\n", backend.read(small).decodeToString())
        assertEquals(Route.Blocked(Why.SshOff), backend.readRoute(big))
        assertEquals(Why.SshOff, assertFailsWith<NoChannelException> { backend.read(big) }.why)
        assertEquals(Why.SshOff, assertFailsWith<NoChannelException> { backend.write(big, "x".repeat(70_000).encodeToByteArray()) }.why)
        assertEquals(0, ssh.opened)
    }

    @Test
    fun aPortThatDoesNotAnswerIsToldWithItsNumber() = runTest {
        val backend = FileBackend(router(Ssh(Sftp(), answer = { SshReach.Closed(2222) })), files)
        assertEquals(Route.Blocked(Why.SshClosed, 2222), backend.readRoute(big))
    }

    @Test
    fun aRefusedHostKeyIsToldAndSmallTextsStillGo() = runTest {
        val ssh = Ssh(Sftp(), refuseKey = true)
        val backend = FileBackend(router(ssh), files)
        assertEquals(Why.KeyRefused, assertFailsWith<NoChannelException> { backend.read(big) }.why)
        assertEquals(Bulk.Missing(Why.KeyRefused), backend.bulk.value)
        assertEquals("hello\n", backend.read(small).decodeToString())
        backend.probe(fresh = true)
        assertEquals(Bulk.Ready(FileVia.Sftp), backend.bulk.value)
    }

    @Test
    fun theFileStoreOfPort8291IsTheWayWhereThereIsNoSsh() = runTest {
        val backend = FileBackend(router(Ssh(Sftp(), fileStore = true)), files)
        assertEquals("bytes of /export.rsc", backend.read(big).decodeToString())
        assertEquals(FileVia.Native, backend.via.value)
        assertEquals("bytes of /rc.local", backend.read(small).decodeToString())
    }

    @Test
    fun aRouterWithoutSshOverRestKeepsSmallTextsAndSaysWhyNotBigOnes() = runTest {
        val backend = FileBackend(router(null), files)
        assertEquals("hello\n", backend.read(small).decodeToString())
        assertEquals(Route.Blocked(Why.NoSsh), backend.readRoute(big))
    }

    @Test
    fun aUserGroupWithoutFtpHasNoSftpButTheCommandsStillWork() = runTest {
        val ssh = Ssh(Sftp())
        val backend = FileBackend(router(ssh, policies = setOf("read", "write")), files)
        assertEquals("hello\n", backend.read(small).decodeToString())
        assertEquals(Route.Blocked(Why.Rights), backend.readRoute(big))
        assertEquals(0, ssh.opened)
    }

    @Test
    fun openWrtKeepsSmallFilesAndFoldersOverRpcdAndAsksSshForNothingOfThat() = runTest {
        val rpcd = Sftp(mapOf("/tmp" to listOf(RemoteFile("a.log", 10, false, 1_800_000_000))))
        val ssh = Ssh(Sftp())
        val backend = FileBackend(router(ssh, platform = Platform.OpenWrt, channel = rpcd), files)
        val some = Entry("a", "tmp/a.log", false, 10, "", ".log file")
        assertEquals("bytes of /tmp/a.log", backend.read(some).decodeToString())
        assertEquals(listOf("tmp/a.log"), backend.list("tmp").map { it.path })
        backend.write(some, "changed".encodeToByteArray())
        assertEquals("changed", rpcd.written.getValue("/tmp/a.log").decodeToString())
        assertEquals(0, ssh.opened)
        assertEquals("bytes of /tmp/b.bin", backend.read(Entry("b", "tmp/b.bin", false, RPCD_READ_LIMIT, "", "file")).decodeToString())
        assertEquals(1, ssh.opened)
    }

    @Test
    fun openWrtWithoutSshGoesOverRpcdUpToWhatItReads() = runTest {
        val rpcd = Sftp()
        val backend = FileBackend(router(null, platform = Platform.OpenWrt, channel = rpcd), files)
        val some = Entry("a", "tmp/a.log", false, 10_000, "", ".log file")
        assertEquals("bytes of /tmp/a.log", backend.read(some).decodeToString())
        assertEquals(FileVia.Rpcd, backend.via.value)
        assertEquals(Route.Blocked(Why.TooBig), backend.readRoute(Entry("b", "tmp/b.log", false, RPCD_READ_LIMIT, "", ".log file")))
        backend.write("tmp/c.txt", "text".encodeToByteArray())
        assertEquals("text", rpcd.written.getValue("/tmp/c.txt").decodeToString())
    }

    @Test
    fun aSmallUtf8TextIsWrittenByTheCommandsAndOtherBytesOverSftp() = runTest {
        val sftp = Sftp()
        val router = router(Ssh(sftp))
        val backend = FileBackend(router, files)
        backend.write(small, "привет\n".encodeToByteArray())
        assertEquals("*1" to mapOf<String, String?>("contents" to "привет\n"), router.changed.single())
        backend.write(small, byteArrayOf(0xEF.toByte(), 0xF0.toByte(), 0xE8.toByte(), 0xE2.toByte(), 0xE5.toByte(), 0xF2.toByte()))
        assertEquals(1, router.changed.size)
        assertEquals(setOf("/rc.local"), sftp.written.keys)
        backend.write(small, "x".repeat(70_000).encodeToByteArray())
        assertEquals(1, router.changed.size)
        assertEquals(70_000, sftp.written.getValue("/rc.local").size)
    }

    @Test
    fun aFolderIsMadeByTheCommandOnRouterOs7AndOverSftpOnRouterOs6() = runTest {
        val sftp = Sftp()
        val seven = FakeRouter(ssh = Ssh(sftp))
        FileBackend(seven, files).makeFolder("rd-test-dir")
        assertEquals(mapOf("name" to "rd-test-dir", "type" to "directory"), seven.added.single())
        val six = FakeRouter(version = "6.49.10", ssh = Ssh(sftp))
        FileBackend(six, files).makeFolder("rd-test-dir")
        assertEquals(emptyList(), six.added)
        assertEquals(listOf("/rd-test-dir"), sftp.made)
    }

    @Test
    fun aTextBeyondTheContentsIsReadInPartsByTheCommandOfRouterOs7() = runTest {
        val schema = MenuSchema(files, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set", "read"), listOf(Argument("name", ValueType.Text())))
        val router = FakeRouter(
            schemas = mapOf("file" to schema),
            output = mapOf("file/read" to listOf(Item("", mapOf("data" to "line\n".repeat(2_000))))),
            ssh = Ssh(Sftp(), answer = { SshReach.Off }),
        )
        val backend = FileBackend(router, files)
        val ten = Entry("*3", "log.txt", false, 10_000, "", ".txt file")
        assertEquals(Route.Over(FileVia.Api), backend.readRoute(ten))
        assertEquals(10_000, backend.read(ten).size)
        assertEquals("file/read" to mapOf("file" to "log.txt", "offset" to "0", "chunk-size" to "32768"), router.ran.single())
    }

    @Test
    fun filesAreDraggedOutOverSftpOnlyOnceSshHasOpened() = runTest {
        val backend = FileBackend(router(Ssh(Sftp())), files)
        backend.probe()
        backend.read(small)
        assertFalse(backend.canDrag, "a text read by the commands opens no SSH")
        backend.read(big)
        assertTrue(backend.canDrag)
        val native = FileBackend(router(Ssh(Sftp(), fileStore = true)), files)
        native.probe()
        assertTrue(native.canDrag, "port 8291 asks about no host key")
    }

    @Test
    fun aFileToReplaceIsFoundByTheCommandsOnRouterOsAndInItsFolderElsewhere() = runTest {
        val rows = listOf(Item("*1", mapOf("name" to "flash", "type" to "directory")), Item("*2", mapOf("name" to "flash/rc.local", "type" to ".local file")))
        val ssh = Ssh(Sftp())
        val routerOs = FileBackend(FakeRouter(mapOf("file" to rows), ssh = ssh), files)
        assertTrue(routerOs.exists("flash/rc.local"))
        assertFalse(routerOs.exists("flash/new.txt"))
        assertFalse(routerOs.exists("flash"), "a folder is not replaced by a file")
        assertEquals(0, ssh.opened)
        val host = FileBackend(router(Ssh(Sftp(mapOf("/tmp" to listOf(RemoteFile("a.log", 10, false, 1_800_000_000))))), platform = Platform.OpenWrt), files)
        assertTrue(host.exists("tmp/a.log"))
        assertFalse(host.exists("tmp/b.log"))
    }

    @Test
    fun turningSshOnSetsTheServiceAndAsksAgain() = runTest {
        var on = false
        val ssh = Ssh(Sftp(), answer = { if (on) SshReach.Open else SshReach.Off })
        val services = listOf(Item("*3", mapOf("name" to "ssh", "port" to "22", "disabled" to "true")))
        val router = FakeRouter(mapOf("ip/service" to services), ssh = ssh)
        val backend = FileBackend(router, files)
        backend.probe()
        assertIs<Bulk.Missing>(backend.bulk.value)
        on = true
        backend.enableSsh()
        assertEquals("*3" to mapOf<String, String?>("disabled" to "no"), router.changed.single())
        assertEquals(Bulk.Ready(FileVia.Sftp), backend.bulk.value)
    }
}
