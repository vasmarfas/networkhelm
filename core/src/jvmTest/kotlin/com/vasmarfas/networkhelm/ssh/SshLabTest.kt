package com.vasmarfas.networkhelm.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.vasmarfas.networkhelm.Lab
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.routeros.api.ApiConnection
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.api.call
import com.vasmarfas.networkhelm.session.connectSshHost
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustKind
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.listenTcp
import com.vasmarfas.networkhelm.transport.openTcp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SshLabTest {

    private fun session(prefix: String, block: suspend CoroutineScope.(SshSession, Map<String, String>) -> Unit) =
        session(Lab.require("$prefix.host", "$prefix.user", "$prefix.password", "$prefix.ssh.hostKey").mapKeys { it.key.removePrefix("$prefix.") }, block)

    private fun session(lab: Map<String, String>, block: suspend CoroutineScope.(SshSession, Map<String, String>) -> Unit) = runBlocking {
        withTimeout(60.seconds) {
            val ssh = connectSsh(
                lab.getValue("host"), 22, lab.getValue("user"), SshAuth.Password(lab.getValue("password")),
                acceptHostKey = { it.fingerprint == lab.getValue("ssh.hostKey") },
            )
            try {
                block(ssh, lab)
            } finally {
                ssh.close()
            }
        }
    }

    private suspend fun ByteStream.readUntil(marker: String): String {
        val text = StringBuilder()
        val buffer = ByteArray(4096)
        while (marker !in text) {
            val count = read(buffer, 0, buffer.size)
            if (count < 0) break
            val chunk = buffer.decodeToString(0, count)
            repeat(Regex("\u001b\\[6n").findAll(chunk).count()) { write("\u001b[40;120R".encodeToByteArray()) }
            text.append(chunk)
        }
        return text.toString()
    }

    @Test
    fun routerOsExecWithoutPty() = session("chr") { ssh, _ -> identity(ssh) }

    @Test
    fun routerOsExecWithoutPtyOnRouterOs6() = session("chr6") { ssh, _ -> identity(ssh) }

    private suspend fun identity(ssh: SshSession) {
        val result = ssh.exec("/system identity print")
        assertEquals(0, result.exitStatus)
        assertTrue("name:" in result.output, result.output)
    }

    @Test
    fun unknownHostKeyStopsTheConnectionBeforeLogin() = runBlocking {
        val lab = Lab.require("chr.host", "chr.user", "chr.password", "chr.ssh.hostKey")
        val error = assertFailsWith<HostKeyRejectedException> {
            connectSsh(lab.getValue("chr.host"), 22, lab.getValue("chr.user"), SshAuth.Password(lab.getValue("chr.password")), acceptHostKey = { false })
        }
        assertEquals(lab.getValue("chr.ssh.hostKey"), error.key.fingerprint)
    }

    @Test
    fun aClosedSessionRefusesItsChannelsAsALostConnection() = session("openwrt") { ssh, _ ->
        ssh.close()
        for (call in listOf<suspend () -> Any>({ ssh.exec("true") }, { ssh.process("sh") }, { ssh.shell("xterm", 80, 24) }, { ssh.sftp() })) {
            assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { call() }.category)
        }
    }

    @Test
    fun routerOs6AgreesOnAlgorithmsWithJsch() = runBlocking {
        val lab = Lab.require("chr6.host", "chr6.ssh.hostKey")
        val error = assertFailsWith<HostKeyRejectedException> {
            connectSsh(lab.getValue("chr6.host"), 22, "rd-test", SshAuth.Password(""), acceptHostKey = { false })
        }
        assertEquals(listOf("ssh-rsa", lab.getValue("chr6.ssh.hostKey")), listOf(error.key.type, error.key.fingerprint))
    }

    @Test
    fun apiThroughTheSshTunnel() = session("chr") { ssh, lab -> apiThroughTunnel(ssh, lab, "7.") }

    @Test
    fun apiThroughTheSshTunnelOnRouterOs6() = session("chr6") { ssh, lab -> apiThroughTunnel(ssh, lab, "6.") }

    private suspend fun CoroutineScope.apiThroughTunnel(ssh: SshSession, lab: Map<String, String>, version: String) {
        val api = ApiConnection(ssh.forward("127.0.0.1", 8728), ApiEncoding.Utf8, this)
        try {
            api.login(lab.getValue("user"), lab.getValue("password"))
            val running = api.call("/system/resource/print", "=.proplist=version").rows.single().getValue("version")
            assertTrue(running.startsWith(version), running)
        } finally {
            api.close()
        }
    }

    @Test
    fun routerOsSftpRoundTrip() = session("chr") { ssh, _ -> sftpRoundTrip(ssh) }

    @Test
    fun routerOsSftpRoundTripOnRouterOs6() = session("chr6") { ssh, _ -> sftpRoundTrip(ssh) }

    private suspend fun sftpRoundTrip(ssh: SshSession) {
        val sftp = ssh.sftp()
        val name = "rd-test-sftp.txt"
        val bytes = "rd-test ".repeat(16).encodeToByteArray()
        try {
            sftp.write("/$name", bytes)
            assertTrue(sftp.list("/").any { it.name == name && it.size == bytes.size.toLong() })
            assertContentEquals(bytes, sftp.read("/$name"))
            sftp.remove("/$name")
            assertFalse(sftp.list("/").any { it.name == name })
        } finally {
            sftp.close()
        }
    }

    @Test
    fun routerOsConsoleInPty() = session("chr") { ssh, _ -> console(ssh) }

    @Test
    fun routerOsConsoleInPtyOnRouterOs6() = session("chr6") { ssh, _ -> console(ssh) }

    private suspend fun console(ssh: SshSession) {
        val shell = ssh.shell("xterm", 120, 40)
        try {
            shell.readUntil("] > ")
            shell.write("/system identity print\r".encodeToByteArray())
            assertTrue("name:" in shell.readUntil("name:"))
            shell.write("/quit\r".encodeToByteArray())
        } finally {
            shell.close()
        }
    }

    @Test
    fun ed25519KeyLogin() = keyLogin("chr", "keyEd25519")

    @Test
    fun rsaKeyLogin() = keyLogin("chr", "keyRsa")

    @Test
    fun rsaKeyLoginOnRouterOs6() = keyLogin("chr6", "keyRsa")

    @Test
    fun encryptedKeyLogin() = keyLogin("chr", "keyRsa", passphrase = "rd-test passphrase")

    private fun keyLogin(prefix: String, keyField: String, passphrase: String? = null) = runBlocking {
        val lab = Lab.require("$prefix.host", "$prefix.keyUser", "$prefix.$keyField", "$prefix.ssh.hostKey")
        val file = Lab.file(lab.getValue("$prefix.$keyField")).readBytes()
        val key = passphrase?.let { encrypted(file, it) } ?: file
        assertEquals(passphrase != null, sshKeyEncrypted(key))
        withTimeout(30.seconds) {
            val ssh = connectSsh(
                lab.getValue("$prefix.host"), 22, lab.getValue("$prefix.keyUser"), SshAuth.Key(key, passphrase),
                acceptHostKey = { it.fingerprint == lab.getValue("$prefix.ssh.hostKey") },
            )
            try {
                assertEquals(0, ssh.exec("/system identity print").exitStatus)
            } finally {
                ssh.close()
            }
        }
    }

    private fun encrypted(key: ByteArray, passphrase: String): ByteArray {
        val pem = ByteArrayOutputStream().also { KeyPair.load(JSch(), key, null).writePrivateKey(it) }.toByteArray()
        return ByteArrayOutputStream().also { KeyPair.load(JSch(), pem, null).writePrivateKey(it, passphrase.encodeToByteArray()) }.toByteArray()
    }

    @Test
    fun aTunnelLeadsToWebFigOfTheRouter() = session("chr") { ssh, _ ->
        val forwarding = Forwarding(ssh, listenTcp("127.0.0.1", 0), "127.0.0.1", 80)
        val served = async(Dispatchers.Default) { forwarding.serve() }
        val http = openTcp("127.0.0.1", forwarding.localPort)
        try {
            http.write("HEAD / HTTP/1.0\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            val buffer = ByteArray(64)
            val count = http.read(buffer, 0, buffer.size)
            assertTrue(count > 0 && buffer.decodeToString(0, count).startsWith("HTTP/1."), forwarding.refused.value)
        } finally {
            http.close()
            forwarding.close()
            served.await()
        }
    }

    @Test
    fun aHostOverSshAloneIsNamedByUname() = Lab.forEachOpenWrt("host", "user", "password", "ssh.hostKey") { lab ->
        runBlocking {
            val policy = TrustPolicy(MemoryTrustStore().also { it.put(lab.getValue("host"), 22, TrustKind.Ssh, lab.getValue("ssh.hostKey")) }) { false }
            val host = connectSshHost(lab.getValue("host"), 22, lab.getValue("user"), SshAuth.Password(lab.getValue("password")), policy)
            try {
                assertEquals(Platform.Ssh, host.device.platform)
                assertEquals("OpenWrt", host.device.identity)
                assertTrue(host.device.version.startsWith("Linux "), host.device.version)
                assertEquals("x86_64", host.device.architecture)
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun aLinuxHostOverSshShowsItsMenus() = Lab.forEachOpenWrt("host", "user", "password", "ssh.hostKey", "lan") { lab ->
        runBlocking {
            val policy = TrustPolicy(MemoryTrustStore().also { it.put(lab.getValue("host"), 22, TrustKind.Ssh, lab.getValue("ssh.hostKey")) }) { false }
            val host = connectSshHost(lab.getValue("host"), 22, lab.getValue("user"), SshAuth.Password(lab.getValue("password")), policy)
            try {
                suspend fun rows(path: String) = (host.watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items
                assertTrue(rows("interface").any { it["name"] == "eth0" && it["type"] == "ether" && it["running"] == "true" })
                assertTrue(rows("ip/address").any { it["address"].orEmpty().substringBefore('/') == lab.getValue("lan").substringBefore('/') })
                assertTrue(rows("ip/route").any { it["dst-address"] == "0.0.0.0/0" && it["gateway"].orEmpty().isNotEmpty() })
                assertTrue(rows("ip/arp").any { it["complete"] == "true" })
                val resource = host.get(MenuPath.parse("system/resource"), null)
                assertTrue(resource?.get("version").orEmpty().startsWith("OpenWrt "), resource.toString())
                assertTrue((resource?.get("total-memory")?.toLongOrNull() ?: 0) > 100_000_000, resource.toString())
                assertTrue(rows("system/storage").any { it["mount-point"] == "/" })
                assertTrue(rows("system/process").any { it["pid"] == "1" && "procd" in it["command"].orEmpty() })
                assertTrue(rows("log").isNotEmpty())
                assertFailsWith<DeviceException> { host.describe(MenuPath.parse("system/service")) }
                val traffic = host.run(MenuPath.parse("interface"), "monitor-traffic", mapOf("interface" to "eth1")).take(2).toList()
                assertTrue(traffic.all { it["name"] == "eth1" && it["rx-bits-per-second"]?.toLongOrNull() != null }, traffic.toString())
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun aLinuxHostWithSystemdShowsItsServices() = runBlocking {
        val lab = Lab.require("linux.host", "linux.user", "linux.password", "linux.ssh.hostKey")
        val policy = TrustPolicy(MemoryTrustStore().also { it.put(lab.getValue("linux.host"), 22, TrustKind.Ssh, lab.getValue("linux.ssh.hostKey")) }) { false }
        val host = connectSshHost(lab.getValue("linux.host"), 22, lab.getValue("linux.user"), SshAuth.Password(lab.getValue("linux.password")), policy)
        try {
            withTimeout(60.seconds) {
                suspend fun rows(path: String) = (host.watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items
                assertEquals(Platform.Ssh, host.device.platform)
                assertTrue(host.device.version.startsWith("Linux "), host.device.version)
                assertTrue(host.get(MenuPath.parse("system/resource"), null)?.get("version").orEmpty().startsWith("Ubuntu "))
                val ssh = rows("system/service").single { it["name"] == "ssh" }
                assertEquals("true", ssh["running"], ssh.values.toString())
                val processes = rows("system/process")
                assertTrue(processes.any { it["pid"] == "1" } && processes.any { "systemd-journald" in it["command"].orEmpty() })
                assertTrue(rows("system/storage").any { it["mount-point"] == "/" })
                val files = MenuPath.parse("file")
                val sftp = host.ssh.sftp()
                try {
                    sftp.write("/tmp/rd-test-linux.txt", "rd-test".encodeToByteArray())
                } finally {
                    sftp.close()
                }
                assertTrue("tmp/rd-test-linux.txt" in rows("file").map { it.id })
                host.remove(files, listOf("tmp/rd-test-linux.txt"))
                assertTrue("tmp/rd-test-linux.txt" !in rows("file").map { it.id })
                assertTrue(rows("log").isNotEmpty())
            }
        } finally {
            host.close()
        }
    }

    @Test
    fun aHostOverSshAloneListsAndRemovesItsFiles() = Lab.forEachOpenWrt("host", "user", "password", "ssh.hostKey") { lab ->
        runBlocking {
            val policy = TrustPolicy(MemoryTrustStore().also { it.put(lab.getValue("host"), 22, TrustKind.Ssh, lab.getValue("ssh.hostKey")) }) { false }
            val host = connectSshHost(lab.getValue("host"), 22, lab.getValue("user"), SshAuth.Password(lab.getValue("password")), policy)
            try {
                val files = MenuPath.parse("file")
                suspend fun names() = (host.watch(files).first() as ListEvent.Snapshot).items.map { it.id }
                val sftp = host.ssh.sftp()
                try {
                    sftp.write("/tmp/rd-test-files.txt", "rd-test".encodeToByteArray())
                } finally {
                    sftp.close()
                }
                val listed = names()
                assertTrue("tmp/rd-test-files.txt" in listed && "tmp" in listed && listed.first() != "tmp", listed.toString())
                host.remove(files, listOf("tmp/rd-test-files.txt"))
                assertTrue("tmp/rd-test-files.txt" !in names())
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun dropbearExecSftpPtyAndForward() = Lab.forEachOpenWrt("host", "user", "password", "ssh.hostKey") { lab ->
        session(lab) { ssh, _ ->
            val board = ssh.exec("ubus call system board")
            assertEquals(0, board.exitStatus)
            assertTrue("\"release\"" in board.output, board.output)

            val sftp = ssh.sftp()
            try {
                assertTrue(sftp.list("/etc/config").any { it.name == "network" })
            } finally {
                sftp.close()
            }

            val shell = ssh.shell("xterm", 120, 40)
            try {
                shell.readUntil("# ")
                shell.write("exit\n".encodeToByteArray())
            } finally {
                shell.close()
            }

            val http = ssh.forward("127.0.0.1", 80)
            try {
                http.write("HEAD / HTTP/1.0\r\nHost: localhost\r\n\r\n".encodeToByteArray())
                val buffer = ByteArray(64)
                val count = http.read(buffer, 0, buffer.size)
                assertTrue(count > 0 && buffer.decodeToString(0, count).startsWith("HTTP/1."))
            } finally {
                http.close()
            }
        }
    }
}
