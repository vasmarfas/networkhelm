package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.CannedStream
import com.vasmarfas.networkhelm.transport.HttpResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpUbusTest {

    private fun ok(body: String) = CannedStream("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n$body")

    private fun login(sid: String) = ok("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[0,{\"ubus_rpc_session\":\"$sid\",\"timeout\":300}]}")

    private val denied = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32002,\"message\":\"Access denied\"}}"

    private val access = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[0,{\"access\":true}]}"

    private val done = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[0,{}]}"

    private class Server(vararg answers: CannedStream) {
        val opened = mutableListOf<CannedStream>()
        private val answers = ArrayDeque(answers.toList())
        suspend fun open() = answers.removeFirst().also { opened += it }
    }

    @Test
    fun theFirstCallLogsInAndTheSessionGoesWithEveryCall() = runTest {
        val server = Server(login("0a"), ok("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[0,{\"hostname\":\"OpenWrt\"}]}"), ok("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[0]}"))
        val ubus = HttpUbus("192.0.2.1", "root", "secret", server::open)
        assertEquals("OpenWrt", ubus.call("system", "board")["hostname"]?.jsonPrimitive?.content)
        assertEquals(0, ubus.call("uci", "commit").size)
        val requests = server.opened.map { it.written.toString().substringAfter("\r\n\r\n") }
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"call\",\"params\":[\"00000000000000000000000000000000\",\"session\",\"login\",{\"username\":\"root\",\"password\":\"secret\"}]}", requests[0])
        assertTrue(requests[1].contains("\"params\":[\"0a\",\"system\",\"board\",{}]"), requests[1])
        assertTrue(requests[2].contains("\"params\":[\"0a\",\"uci\",\"commit\",{}]"), requests[2])
    }

    // a session that has lapsed is denied the question about itself as well
    @Test
    fun aLapsedSessionIsReplacedOnceAndThenTheCallIsDenied() = runTest {
        val server = Server(login("0a"), ok(denied), ok(denied), login("0b"), ok(done), ok(denied), ok(denied), login("0c"), ok(denied))
        val ubus = HttpUbus("192.0.2.1", "root", "secret", server::open)
        ubus.call("system", "board")
        assertTrue(server.opened[2].written.contains("\"params\":[\"0a\",\"session\",\"access\","), server.opened[2].written.toString())
        assertTrue(server.opened[4].written.contains("\"params\":[\"0b\",\"system\",\"board\",{}]"))
        assertEquals(UbusStatus.PERMISSION_DENIED, assertFailsWith<UbusException> { ubus.call("network.interface.wan", "up") }.status)
        assertEquals(9, server.opened.size)
    }

    @Test
    fun aCallTheAclDoesNotAllowIsDeniedAndTheSessionWithItsDraftStays() = runTest {
        val server = Server(login("0a"), ok(denied), ok(access), ok(done))
        val ubus = HttpUbus("192.0.2.1", "root", "secret", server::open)
        assertEquals(UbusStatus.PERMISSION_DENIED, assertFailsWith<UbusException> { ubus.call("network.interface.wan", "up") }.status)
        ubus.call("uci", "changes")
        assertTrue(server.opened[3].written.contains("\"params\":[\"0a\",\"uci\",\"changes\",{}]"), server.opened[3].written.toString())
        assertEquals(4, server.opened.size)
    }

    @Test
    fun aRefusedLoginAndAFailedCallCarryTheStatusOfUbus() = runTest {
        val refused = HttpUbus("192.0.2.1", "root", "wrong", Server(ok("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[6]}"))::open)
        assertEquals(UbusStatus.PERMISSION_DENIED, assertFailsWith<UbusException> { refused.call("system", "board") }.status)
        val missing = HttpUbus("192.0.2.1", "root", "secret", Server(login("0a"), ok("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[4]}"))::open)
        assertEquals("Not found", assertFailsWith<UbusException> { missing.call("network.interface.rd_test", "status") }.message)
    }

    @Test
    fun notificationsComeAsServerSentEvents() = runTest {
        val events = CannedStream("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\nevent: message\ndata: {\"msg\":\"rd-test\"}\n\n")
        val server = Server(login("0a"), events)
        val notifications = HttpUbus("192.0.2.1", "root", "secret", server::open).subscribe("log").toList()
        assertEquals("rd-test", notifications.single()["message"]?.jsonObject?.get("msg")?.jsonPrimitive?.content)
        assertTrue(events.written.startsWith("GET /ubus/subscribe/log HTTP/1.1\r\n"))
        assertTrue(events.written.contains("Authorization: Bearer 0a\r\n"))
    }

    @Test
    fun aWebOtherThanSocketsCarriesTheSameRequestsAndEvents() = runTest {
        val posted = mutableListOf<String>()
        val asked = mutableListOf<Pair<String, Map<String, String>>>()
        val web = object : UbusWeb {
            override suspend fun post(json: String): HttpResponse {
                posted += json
                val answer = if (posted.size == 1) "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[0,{\"ubus_rpc_session\":\"0a\",\"timeout\":300}]}"
                else "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[0,{\"hostname\":\"OpenWrt\"}]}"
                return HttpResponse(200, answer.encodeToByteArray())
            }

            override fun lines(target: String, headers: Map<String, String>): Flow<String> {
                asked += target to headers
                return flowOf("event: message", "data: {\"msg\":\"rd-test\"}", "")
            }
        }
        val ubus = HttpUbus("root", "secret", web)
        assertEquals("OpenWrt", ubus.call("system", "board")["hostname"]?.jsonPrimitive?.content)
        assertTrue("\"session\",\"login\",{\"username\":\"root\",\"password\":\"secret\"}" in posted[0], posted[0])
        assertTrue("\"params\":[\"0a\",\"system\",\"board\",{}]" in posted[1], posted[1])
        assertEquals("rd-test", ubus.subscribe("log").toList().single()["message"]?.jsonObject?.get("msg")?.jsonPrimitive?.content)
        assertEquals("/ubus/subscribe/log" to mapOf("Authorization" to "Bearer 0a"), asked.single())
    }

    @Test
    fun aRequestWithNoAnswerIsALostConnection() = runTest {
        val reset = IllegalStateException("Connection reset")
        var lines = false
        val web = object : UbusWeb {
            override suspend fun post(json: String): HttpResponse {
                if ("\"login\"" !in json) throw reset
                return HttpResponse(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[0,{\"ubus_rpc_session\":\"0a\",\"timeout\":300}]}".encodeToByteArray())
            }

            override fun lines(target: String, headers: Map<String, String>): Flow<String> = flow {
                lines = true
                throw reset
            }
        }
        val ubus = HttpUbus("root", "secret", web)
        val cut = assertFailsWith<DeviceException> { ubus.call("system", "board") }
        assertEquals(ErrorCategory.ConnectionLost, cut.category)
        assertEquals(reset, cut.cause)
        assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { ubus.subscribe("log").toList() }.category)
        assertTrue(lines)
    }

    @Test
    fun aSubscriptionTheAclDoesNotAllowIsDenied() = runTest {
        val server = Server(login("0a"), ok(denied), ok(access))
        val subscription = HttpUbus("192.0.2.1", "root", "secret", server::open).subscribe("log")
        assertEquals(UbusStatus.PERMISSION_DENIED, assertFailsWith<UbusException> { subscription.toList() }.status)
        assertTrue(server.opened[1].written.contains("Authorization: Bearer 0a\r\n"))
        assertTrue(server.opened[2].written.contains("\"params\":[\"0a\",\"session\",\"access\","), server.opened[2].written.toString())
        assertEquals(3, server.opened.size)
    }

    @Test
    fun aSubscriptionOfALapsedSessionIsMadeAgainAfterANewLogin() = runTest {
        val events = CannedStream("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\nevent: message\ndata: {\"msg\":\"rd-test\"}\n\n")
        val server = Server(login("0a"), ok(denied), ok(denied), login("0b"), events)
        val notifications = HttpUbus("192.0.2.1", "root", "secret", server::open).subscribe("log").toList()
        assertEquals("rd-test", notifications.single()["message"]?.jsonObject?.get("msg")?.jsonPrimitive?.content)
        assertTrue(events.written.contains("Authorization: Bearer 0b\r\n"))
    }

    @Test
    fun anAnswerThatIsNotJsonRpcIsNoUbus() = runTest {
        val ubus = HttpUbus("192.0.2.1", "root", "secret", Server(ok("<html><body>It works</body></html>"))::open)
        assertEquals(UbusStatus.NOT_SUPPORTED, assertFailsWith<UbusException> { ubus.call("system", "board") }.status)
    }
}
