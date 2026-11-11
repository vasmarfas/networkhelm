package com.vasmarfas.networkhelm.transport

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpTest {

    @Test
    fun aResponseCannotGrowPastItsLimitWithAnyFraming() = runTest {
        for (response in listOf(
            "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n12345",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\n12345\r\n0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n12345",
        )) {
            val stream = CannedStream(response)
            assertFailsWith<DeviceException> { httpRequest(stream, "192.0.2.1", "GET", "/", emptyMap(), ByteArray(0), maxBodyBytes = 4) }
            assertTrue(stream.closed)
        }
        assertEquals("1234", httpRequest(CannedStream("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n1234"), "192.0.2.1", "GET", "/", emptyMap(), ByteArray(0), maxBodyBytes = 4).body.decodeToString())
    }

    @Test
    fun aHeaderLineThatNeverEndsIsRefused() = runTest {
        val stream = CannedStream("HTTP/1.1 200 OK\r\nX-Padding: " + "a".repeat(70 * 1024))
        assertFailsWith<DeviceException> { httpRequest(stream, "192.0.2.1", "GET", "/", emptyMap(), ByteArray(0)) }
        assertTrue(stream.closed)
    }

    @Test
    fun aKeepAliveResponseHasTheSameBodyLimit() = runTest {
        val stream = CannedStream("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n12345")
        assertFailsWith<DeviceException> { httpExchange(BufferedStream(stream), "192.0.2.1", "GET", "/", emptyMap(), ByteArray(0), maxBodyBytes = 4) }
    }

    private suspend fun answer(text: String) = httpRequest(CannedStream(text), "192.0.2.1", "POST", "/ubus", emptyMap(), ByteArray(0))

    @Test
    fun requestAsksToCloseAndTheAnswerIsReadToTheEnd() = runTest {
        val stream = CannedStream("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 13\r\n\r\n{\"ret\":\"*38\"}")
        val response = httpRequest(stream, "192.0.2.1", "POST", "/rest/ip/address/add", mapOf("Content-Type" to "application/json"), "{}".encodeToByteArray())
        assertEquals(200, response.status)
        assertEquals("{\"ret\":\"*38\"}", response.body.decodeToString())
        assertEquals(
            "POST /rest/ip/address/add HTTP/1.1\r\nHost: 192.0.2.1\r\nConnection: close\r\nContent-Length: 2\r\nContent-Type: application/json\r\n\r\n{}",
            stream.written.toString(),
        )
        assertTrue(stream.closed)
    }

    @Test
    fun chunkedAnswerIsJoined() = runTest {
        val response = answer("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n{\"a\"\r\n3\r\n:1}\r\n0\r\n\r\n")
        assertEquals("{\"a\":1}", response.body.decodeToString())
    }

    @Test
    fun anAnswerOfAKnownLengthEndsWithoutTheEndOfTheConnection() = runTest {
        val stream = object : ByteStream {
            private var answer: ByteArray? = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".encodeToByteArray()

            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val bytes = answer ?: awaitCancellation()
                answer = null
                bytes.copyInto(buffer, offset)
                return bytes.size
            }

            override suspend fun write(bytes: ByteArray) = Unit

            override fun close() = Unit
        }
        assertEquals("{}", httpRequest(stream, "192.0.2.1", "POST", "/rest/system/identity/print", emptyMap(), ByteArray(0)).body.decodeToString())
    }

    @Test
    fun cutAnswerIsAnError() = runTest {
        assertFailsWith<DeviceException> { answer("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n{}") }
        assertFailsWith<DeviceException> { answer("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n9\r\n{}") }
        assertFailsWith<DeviceException> { answer("HTTP/1.1 200 OK\r\nContent-Le") }
    }

    @Test
    fun aNegativeLengthOfTheBodyOrOfAChunkIsAnErrorOfTheAnswer() = runTest {
        assertFailsWith<DeviceException> { answer("HTTP/1.1 200 OK\r\nContent-Length: -2\r\n\r\n{}") }
        assertFailsWith<DeviceException> { answer("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n-2\r\n{}\r\n0\r\n\r\n") }
    }

    @Test
    fun linesOfAnEndlessAnswerComeOneByOne() = runTest {
        val stream = CannedStream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n15\r\nevent: message\ndata: \r\n9\r\n{\"n\":1}\n\n\r\n0\r\n\r\n")
        val lines = httpLines({ stream }, "192.0.2.1", "/ubus/subscribe/log", mapOf("Authorization" to "Bearer 0f")).toList()
        assertEquals(listOf("event: message", "data: {\"n\":1}", ""), lines)
        assertEquals("GET /ubus/subscribe/log HTTP/1.1\r\nHost: 192.0.2.1\r\nConnection: close\r\nAuthorization: Bearer 0f\r\n\r\n", stream.written.toString())
        val denied = CannedStream("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n")
        val error = assertFailsWith<DeviceException> { httpLines({ denied }, "192.0.2.1", "/ubus/subscribe/log", emptyMap()).toList() }
        assertEquals(ErrorCategory.PermissionDenied, error.category)
    }
}
