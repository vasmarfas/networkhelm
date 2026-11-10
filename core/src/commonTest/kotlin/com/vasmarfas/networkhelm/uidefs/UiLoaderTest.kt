package com.vasmarfas.networkhelm.uidefs

import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun gzipped(text: String): ByteArray {
    val data = text.encodeToByteArray()
    val header = byteArrayOf(0x1F, 0x8B.toByte(), 8, 0, 0, 0, 0, 0, 0, 0xFF.toByte())
    val block = byteArrayOf(1, data.size.toByte(), (data.size shr 8).toByte(), data.size.inv().toByte(), (data.size.inv() shr 8).toByte())
    return header + block + data + ByteArray(8)
}

class UiLoaderTest {

    private class Server(private val files: Map<String, String>) {
        fun web() = WebAccess("router") { connect() }

        private fun connect() = object : ByteStream {
            private var pending = ByteArray(0)

            override suspend fun write(bytes: ByteArray) {
                val request = bytes.decodeToString()
                val body = files[request.substringAfter(' ').substringBefore(' ')]
                pending = when {
                    "Accept-Encoding: gzip" !in request -> "HTTP/1.1 406 Not Acceptable\r\nContent-Length: 0\r\n\r\n".encodeToByteArray()
                    body == null -> "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".encodeToByteArray()
                    else -> gzipped(body).let { "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: ${it.size}\r\n\r\n".encodeToByteArray() + it }
                }
            }

            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (pending.isEmpty()) return -1
                val count = minOf(length, pending.size)
                pending.copyInto(buffer, offset, 0, count)
                pending = pending.copyOfRange(count, pending.size)
                return count
            }

            override fun close() = Unit
        }
    }

    private val list = "{ crc: 1, size: 120, name: \"roteros.jg\", unique: \"roteros-aa.jg\", version: \"7.99\" },\n" +
        "{ crc: 2, size: 80, name: \"wave2.jg\", unique: \"wave2-bb.jg\", version: \"7.99\" },\n" +
        "{ crc: 3, size: 0, name: \"empty.jg\", unique: \"empty-cc.jg\", version: \"7.99\" },\n" +
        "{ crc: 4, size: 9, name: \"broken.jg\", unique: \"broken-dd.jg\", version: \"7.99\" },\n" +
        "{ crc: 5, size: 9, name: \"gone.jg\", unique: \"gone-ee.jg\", version: \"7.99\" },\n" +
        "{ crc: 6, size: 9, name: \"icons.png\", unique: \"icons-ff.png\", version: \"7.99\" },\n"

    private val server = Server(
        mapOf(
            "/webfig/list" to list,
            "/webfig/roteros-aa.jg" to "[{name:'Main',group:'IP',c:[]}]",
            "/webfig/wave2-bb.jg" to "[{name:'Wave',group:'WiFi',c:[]}]",
            "/webfig/broken-dd.jg" to "[{name:",
        ),
    )

    @Test
    fun theListKeepsTheFilesOfDescriptionsThatHaveContent() = runTest {
        val files = assertNotNull(loadUiList(server.web().files(80)))
        assertEquals(listOf("roteros.jg", "wave2.jg", "broken.jg", "gone.jg"), files.map { it.name })
        assertEquals("roteros.jg:1,wave2.jg:2,broken.jg:4,gone.jg:5", uiState(files))
    }

    @Test
    fun aRouterThatServesNoMainFileOrNoListGivesNone() = runTest {
        assertNull(loadUiList(Server(mapOf("/webfig/list" to "{ crc: 2, size: 80, name: \"wave2.jg\", unique: \"wave2-bb.jg\", version: \"7.99\" },\n")).web().files(80)))
        assertNull(loadUiList(Server(emptyMap()).web().files(80)))
        assertNull(loadUiList(Server(mapOf("/webfig/list" to "not a list")).web().files(80)))
    }

    @Test
    fun theEntriesOfEveryFileComeMainFirstAndAFileThatDoesNotReadIsLeftOut() = runTest {
        val web = server.web()
        val entries = loadUiEntries(web.files(80), assertNotNull(loadUiList(web.files(80))))
        assertEquals(listOf("Main", "Wave"), entries.map { it.name })
    }

    @Test
    fun theWinBoxPortGivesTheListAsItIsAndEachDescriptionGzippedUnderItsUniqueName() = runTest {
        val asked = ArrayList<String>()
        val resources = mapOf(
            "list" to list.encodeToByteArray(),
            "roteros-aa.jg.gz" to gzipped("[{name:'Main',group:'IP',c:[]}]"),
            "wave2-bb.jg.gz" to "not gzipped".encodeToByteArray(),
        )
        val files = nativeUiFiles { name -> asked += name; resources[name] }
        val entries = loadUiEntries(files, assertNotNull(loadUiList(files)))
        assertEquals(listOf("Main"), entries.map { it.name })
        assertEquals(listOf("list", "roteros-aa.jg.gz"), asked.take(2))
    }
}
