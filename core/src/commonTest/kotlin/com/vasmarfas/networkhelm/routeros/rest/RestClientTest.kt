package com.vasmarfas.networkhelm.routeros.rest

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.routeros.api.ApiTrapException
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.CannedStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RestClientTest {

    private val streams = ArrayDeque<CannedStream>()
    private val client = RestClient("192.0.2.1", "admin", "secret") { streams.removeFirst() }

    private fun answer(status: Int, json: String) =
        CannedStream("HTTP/1.1 $status X\r\nContent-Length: ${json.encodeToByteArray().size}\r\n\r\n$json").also { streams += it }

    @Test
    fun wordsBecomeAPostWithJsonArguments() = runTest {
        answer(200, """{"ret":"1"}""")
        val stream = answer(200, """[{".id":"*1","address":"192.0.2.1"}]""")
        val result = client.call(listOf("/ip/firewall/address-list/print", "=.proplist=.id,address", "?list=blocked", "?#!"))
        assertEquals(listOf(mapOf(".id" to "*1", "address" to "192.0.2.1")), result.rows)
        val request = stream.written.toString()
        assertTrue(request.startsWith("POST /rest/ip/firewall/address-list/print HTTP/1.1\r\n"), request)
        assertTrue("Authorization: Basic YWRtaW46c2VjcmV0\r\n" in request, request)
        assertEquals(
            Json.parseToJsonElement("""{".query":["list=blocked","#!"],".proplist":[".id","address"]}"""),
            Json.parseToJsonElement(request.substringAfter("\r\n\r\n")),
        )
    }

    private fun rows(range: IntRange) = range.joinToString(",", "[", "]") { "{\".id\":\"*$it\"}" }

    private fun body(stream: CannedStream) = Json.parseToJsonElement(stream.written.toString().substringAfter("\r\n\r\n")).jsonObject

    private val addresses = listOf("/ip/firewall/address-list/print", "?list=blocked")

    private class Silent : ByteStream {
        private val closed = CompletableDeferred<Unit>()

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            closed.await()
            return -1
        }

        override suspend fun write(bytes: ByteArray) = Unit

        override fun close() {
            closed.complete(Unit)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aRouterThatGoesSilentEndsTheRequestInsteadOfHoldingItForever() = runTest {
        val silent = RestClient("192.0.2.1", "admin", "secret") { Silent() }
        assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { silent.call(listOf("/ip/pool/set", "=.id=*1")) }.category)
        assertEquals(120_000, testScheduler.currentTime)
    }

    @Test
    fun aBigTableIsAskedByItsIdsInPortions() = runTest {
        val count = answer(200, """{"ret":"2500"}""")
        val ids = answer(200, rows(0 until 2_500))
        val first = answer(200, rows(0 until 2_000))
        val second = answer(200, rows(2_000 until 2_500))
        val progress = mutableListOf<Pair<Int, Int>>()
        assertEquals(2_500, client.table(addresses) { read, total -> progress += read to total }.size)
        assertEquals(listOf(2_000 to 2_500, 2_500 to 2_500), progress)
        assertEquals("""{"count-only":"",".query":["list=blocked"]}""".let(Json::parseToJsonElement), body(count))
        assertEquals("""{".proplist":[".id"],".query":["list=blocked"]}""".let(Json::parseToJsonElement), body(ids))
        assertEquals((0 until 2_000).joinToString(",") { "*$it" }, body(first).getValue("from").jsonPrimitive.content)
        assertEquals(setOf("from"), body(second).keys)
    }

    @Test
    fun aRowThatWentDuringTheReadingBringsNewIds() = runTest {
        answer(200, """{"ret":"2500"}""")
        answer(200, rows(0 until 2_500))
        answer(404, """{"error":404,"message":"Not Found"}""")
        val fresh = answer(200, rows(1 until 2_500))
        val first = answer(200, rows(1 until 2_001))
        answer(200, rows(2_001 until 2_500))
        assertEquals(2_499, client.call(addresses).rows.size)
        assertEquals(setOf(".proplist", ".query"), body(fresh).keys)
        assertTrue(body(first).getValue("from").jsonPrimitive.content.startsWith("*1,*2,"))
    }

    @Test
    fun aMenuWithoutFromIsAskedWholeAndNotCountedAgain() = runTest {
        answer(200, """{"ret":"2500"}""")
        answer(200, rows(0 until 2_500))
        answer(400, """{"detail":"unknown parameter from","error":400,"message":"Bad Request"}""")
        answer(200, rows(0 until 2_500))
        answer(200, rows(0 until 2_400))
        val log = listOf("/log/print")
        assertEquals(2_500, client.call(log).rows.size)
        assertEquals(2_400, client.call(log).rows.size)
        assertTrue(streams.isEmpty())
    }

    @Test
    fun aMenuWithoutAListIsAskedOnceAboutItsSize() = runTest {
        answer(400, """{"detail":"unknown parameter count-only","error":400,"message":"Bad Request"}""")
        answer(200, """[{"servers":""}]""")
        answer(200, """[{"servers":""}]""")
        val dns = listOf("/ip/dns/print")
        assertEquals(1, client.call(dns).rows.size)
        assertEquals(1, client.call(dns).rows.size)
        assertTrue(streams.isEmpty())
    }

    @Test
    fun objectAnswerIsWhatDoneCarries() = runTest {
        answer(200, """{"ret":"*38"}""")
        assertEquals(mapOf("ret" to "*38"), client.call(listOf("/ip/address/add", "=address=192.0.2.1/32", "=interface=lo")).done)
    }

    @Test
    fun routerErrorsKeepTheRouterText() = runTest {
        answer(400, """{"detail":"failure: already have such entry","error":400,"message":"Bad Request"}""")
        answer(401, """{"error":401,"message":"Unauthorized"}""")
        assertEquals("failure: already have such entry", assertFailsWith<ApiTrapException> { client.call(listOf("/ip/address/add")) }.message)
        assertEquals(ErrorCategory.PermissionDenied, assertFailsWith<DeviceException> { client.call(listOf("/system/identity/print")) }.category)
    }

    @Test
    fun aCommandThatNeverEndsIsAskedARoundAtATime() = runTest {
        val first = answer(200, """[{"name":"total","usage":"0"}]""")
        answer(200, """[{"name":"total","usage":"1"}]""")
        val replies = client.command(listOf("/tool/profile")).take(2).toList()
        assertEquals(listOf("0", "1"), replies.map { it.attributes[".section"] })
        assertEquals(Json.parseToJsonElement("""{"duration":"3"}"""), Json.parseToJsonElement(first.written.toString().substringAfter("\r\n\r\n")))
    }

    @Test
    fun listenIsRefusedSoTheDriverPolls() = runTest {
        assertFailsWith<ApiTrapException> { client.command(listOf("/ip/address/listen")).toList() }
        assertTrue(streams.isEmpty())
    }
}
