package com.vasmarfas.networkhelm.ui.files

import com.vasmarfas.networkhelm.app.connectOpenWrtWithForms
import com.vasmarfas.networkhelm.miknative.connectNativeHost
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.openwrt.OpenWrtLogin
import com.vasmarfas.networkhelm.openwrt.OpenWrtProtocol
import com.vasmarfas.networkhelm.openwrt.connectOpenWrt
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.routeros.RouterOsProtocol
import com.vasmarfas.networkhelm.routeros.connectRouterOs
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.connectWithSsh
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Locale
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilesLabTest {

    private fun lab(): Properties {
        val file = System.getProperty("networkhelm.lab")?.let(::File)?.takeIf { it.isFile }
        assumeTrue("lab.properties is missing", file != null)
        return Properties().apply { file?.inputStream()?.use(::load) }
    }

    private fun host(lab: Properties, prefix: String): String {
        val host = lab.getProperty("$prefix.host").orEmpty()
        assumeTrue("$prefix.host is empty", host.isNotEmpty())
        return host
    }

    private suspend fun rows(session: DeviceSession, path: MenuPath) =
        withTimeout(30_000) { session.watch(path).filterIsInstance<ListEvent.Snapshot>().first().items }

    private suspend fun run(session: DeviceSession, files: MenuPath, root: String, listing: suspend (FileBackend) -> List<Entry>) {
        val backend = FileBackend(session, files)
        val dir = "$root/rd-test-files".trimStart('/')
        try {
            backend.makeFolder(dir)
            backend.makeFile("$dir/a.txt")
            backend.write("$dir/a.txt", "hello\n".encodeToByteArray())
            val first = listing(backend).first { it.path == "$dir/a.txt" }
            assertEquals("hello\n", backend.read(first).decodeToString())
            backend.write(first, "changed\n".encodeToByteArray())
            backend.rename(first, "$dir/b.txt")
            val renamed = listing(backend).filter { it.path.startsWith("$dir/") }
            assertEquals(listOf("$dir/b.txt"), renamed.map { it.path })
            assertEquals("changed\n", backend.read(renamed.single()).decodeToString())
            val all = listing(backend)
            backend.delete(listOf(all.first { it.path == dir }), all)
            assertTrue(listing(backend).none { it.path == dir || it.path.startsWith("$dir/") }, "the folder is gone")
        } finally {
            val all = try {
                listing(backend)
            } catch (e: Exception) {
                emptyList()
            }
            all.firstOrNull { it.path == dir }?.let { runCatching { backend.delete(listOf(it), all) } }
        }
    }

    @Test
    fun theChrKeepsItsFilesWhatEverTheWindowDoes() {
        val lab = lab()
        val host = host(lab, "chr")
        Locale.setDefault(Locale.ENGLISH)
        val scope = CoroutineScope(Dispatchers.Default)
        val session = runBlocking {
            connectWithSsh(RouterOsLogin(host, lab.getProperty("chr.user"), lab.getProperty("chr.password"), RouterOsProtocol.ApiSsl), TrustPolicy(MemoryTrustStore()) { true }, scope)
        }
        try {
            val files = MenuPath.parse("file")
            runBlocking { run(session, files, "") { rows(session, files).mapNotNull(::entryOf) } }
        } finally {
            session.close()
            scope.cancel()
        }
    }

    @Test
    fun theChrOverPort8291DoesTheSame() {
        val lab = lab()
        val host = host(lab, "chr")
        Locale.setDefault(Locale.ENGLISH)
        val scope = CoroutineScope(Dispatchers.Default)
        val session = runBlocking { connectNativeHost(host, 8291, lab.getProperty("chr.user"), lab.getProperty("chr.password"), scope) }
        try {
            val files = MenuPath.parse("file")
            runBlocking { run(session, files, "") { rows(session, files).mapNotNull(::entryOf) } }
        } finally {
            session.close()
            scope.cancel()
        }
    }

    @Test
    fun openWrtKeepsItsFilesToo() {
        val lab = lab()
        val files = MenuPath.parse("status/files")
        for (prefix in listOf("openwrt", "openwrt24").filter { lab.getProperty("$it.host").orEmpty().isNotBlank() }) {
            Locale.setDefault(Locale.ENGLISH)
            val key = lab.getProperty("$prefix.ssh.hostKey")
            val login = OpenWrtLogin(host(lab, prefix), lab.getProperty("$prefix.user"), lab.getProperty("$prefix.password"))
            val session = runBlocking { connectOpenWrtWithForms(login, TrustPolicy(MemoryTrustStore()) { it.fingerprint == key }) }
            try {
                try {
                    runBlocking {
                        run(session, files, "tmp") { backend ->
                            rows(session, files).mapNotNull(::entryOf) + backend.list("tmp") + runCatching { backend.list("tmp/rd-test-files") }.getOrDefault(emptyList())
                        }
                    }
                } catch (e: Throwable) {
                    throw AssertionError("on $prefix: $e", e)
                }
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun aTextIsKeptOverTheApiWithoutSsh() {
        val lab = lab()
        Locale.setDefault(Locale.ENGLISH)
        for ((prefix, protocol) in listOf("chr" to RouterOsProtocol.ApiSsl, "chr6" to RouterOsProtocol.Api)) {
            val host = host(lab, prefix)
            val scope = CoroutineScope(Dispatchers.Default)
            val session = runBlocking {
                connectRouterOs(RouterOsLogin(host, lab.getProperty("$prefix.user"), lab.getProperty("$prefix.password"), protocol), TrustPolicy(MemoryTrustStore()) { true }, scope)
            }
            try {
                runBlocking { withTimeout(60_000) { keepText(session, prefix) } }
            } catch (e: Throwable) {
                throw AssertionError("on $prefix: $e", e)
            } finally {
                session.close()
                scope.cancel()
            }
        }
    }

    private suspend fun keepText(session: DeviceSession, prefix: String) {
        val files = MenuPath.parse("file")
        val backend = FileBackend(session, files)
        val name = "rd-test-nossh.txt"
        suspend fun entry(): Entry? = rows(session, files).mapNotNull(::entryOf).firstOrNull { it.path == name }
        suspend fun clean() {
            entry()?.let { backend.delete(listOf(it), listOf(it)) }
            entry()?.let { throw AssertionError("$name is still on $prefix") }
        }
        clean()
        try {
            if (backend.commandNames && "add" in session.describe(files).commands) backend.makeFile(name) else session.run(files, "print", mapOf("file" to name.removeSuffix(".txt"))).toList()
            val made = checkNotNull(entry()) { "$name was not made" }
            backend.write(made, "one\ntwo\n".encodeToByteArray())
            assertEquals("one\ntwo\n", backend.read(checkNotNull(entry())).decodeToString())
            assertEquals(FileVia.Api, backend.via.value)
            backend.probe()
            assertEquals(Bulk.Missing(Why.NoSsh), backend.bulk.value)
            if ("read" in session.describe(files).commands) {
                val long = "rd-test line of a longer text\n".repeat(900)
                backend.write(checkNotNull(entry()), long.encodeToByteArray())
                assertEquals(long, backend.read(checkNotNull(entry())).decodeToString())
            }
            if (backend.commandNames) {
                backend.rename(checkNotNull(entry()), "rd-test-nossh2.txt")
                assertEquals(null, entry())
                val renamed = rows(session, files).mapNotNull(::entryOf).first { it.path == "rd-test-nossh2.txt" }
                backend.delete(listOf(renamed), listOf(renamed))
            }
        } finally {
            clean()
            rows(session, files).mapNotNull(::entryOf).firstOrNull { it.path == "rd-test-nossh2.txt" }?.let { backend.delete(listOf(it), listOf(it)) }
        }
    }

    @Test
    fun openWrtKeepsItsFilesOverRpcdWithoutSsh() {
        val lab = lab()
        Locale.setDefault(Locale.ENGLISH)
        val files = MenuPath.parse("status/files")
        for (prefix in listOf("openwrt", "openwrt24").filter { lab.getProperty("$it.host").orEmpty().isNotBlank() }) {
            val login = OpenWrtLogin(host(lab, prefix), lab.getProperty("$prefix.user"), lab.getProperty("$prefix.password"), OpenWrtProtocol.Ubus)
            val session = runBlocking { connectOpenWrt(login, TrustPolicy(MemoryTrustStore()) { true }) }
            try {
                runBlocking {
                    run(session, files, "tmp") { backend ->
                        backend.probe()
                        assertEquals(Bulk.Ready(FileVia.Rpcd), backend.bulk.value)
                        rows(session, files).mapNotNull(::entryOf) + backend.list("tmp") + runCatching { backend.list("tmp/rd-test-files") }.getOrDefault(emptyList())
                    }
                }
            } catch (e: Throwable) {
                throw AssertionError("on $prefix: $e", e)
            } finally {
                session.close()
            }
        }
    }
}
