package com.vasmarfas.networkhelm.pfsense

import com.vasmarfas.networkhelm.journal.EditKind
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.syslogLine
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PfsenseSessionTest {

    private class Api(private val answers: Map<String, String>) : PfsenseCall {
        val calls = mutableListOf<Triple<String, Map<String, String>, JsonObject?>>()

        override suspend fun call(method: String, path: String, query: Map<String, String>, body: JsonObject?): JsonElement {
            calls += Triple("$method $path", query, body)
            val answer = answers["$method $path"] ?: throw DeviceException(ErrorCategory.MissingItem, "this pfSense has no /api/v2/$path")
            return Json.parseToJsonElement(answer)
        }
    }

    private val device = DeviceInfo(Platform.Pfsense, "pfSense", "2.8.0", "", "pfSense", emptySet(), setOf("read", "write"))

    private val openapi = """{"paths":{"/api/v2/firewall/rule":{"post":{"requestBody":{"content":{"application/json":{"schema":{"allOf":[""" +
        """{"${'$'}ref":"#/components/schemas/FirewallRule"},{"type":"object","required":["type","interface","ipprotocol","source","destination"]}]}}}}}}},""" +
        """"components":{"schemas":{"FirewallRule":{"properties":{""" +
        """"type":{"type":"string","enum":["pass","block","reject"],"description":"The action taken on traffic that matches the rule."},""" +
        """"interface":{"type":"array","items":{"type":"string","enum":["wan","lan"]}},""" +
        """"ipprotocol":{"type":"string","enum":["inet","inet6","inet46"]},""" +
        """"source":{"type":"string"},"destination_port":{"type":"string","nullable":true},""" +
        """"disabled":{"type":"boolean"},"tracker":{"type":"integer","readOnly":true},"descr":{"type":"string"}}}}}}"""

    private val rules = """[{"id":0,"type":"pass","interface":["lan"],"ipprotocol":"inet","source":"lan","destination_port":null,"disabled":false,"tracker":1700000000,"descr":"Default allow LAN to any rule"},""" +
        """{"id":1,"type":"block","interface":["wan","lan"],"ipprotocol":"inet46","source":"any","destination_port":"8291","disabled":true,"tracker":1700000001,"descr":"rd-test"}]"""

    private fun session(answers: Map<String, String>) = Api(answers).let { it to PfsenseSession(it, device) }

    @Test
    fun theFieldsOfARuleComeFromTheOpenApiOfTheFirewall() = runTest {
        val (_, session) = session(mapOf("GET schema/openapi" to openapi))
        val schema = session.describe(MenuPath.parse("firewall/rule"))
        assertEquals(NodeKind.List, schema.kind)
        assertEquals(listOf("print", "add", "set", "remove", "move"), schema.commands)
        assertEquals(ValueType.Enumeration(multiple = false), schema.argument("type")?.type)
        assertEquals(listOf("pass", "block", "reject"), schema.argument("type")?.choices)
        assertEquals("The action taken on traffic that matches the rule.", schema.argument("type")?.description)
        assertEquals(ValueType.Enumeration(multiple = true), schema.argument("interface")?.type)
        assertEquals(ValueType.YesNo, schema.argument("disabled")?.type)
        assertTrue(schema.argument("tracker")?.readOnly == true)
        assertTrue(schema.argument("destination_port")?.optional == true)
        assertEquals(listOf("diagnostics", "firewall", "interfaces", "log", "routing", "services", "system"), session.describe(MenuPath.Root).children.sorted())
    }

    @Test
    fun rowsGoByTheirIdAndAnEditSendsTheTypesOfTheModel() = runTest {
        val (api, session) = session(
            mapOf(
                "GET schema/openapi" to openapi,
                "GET firewall/rules" to rules,
                "PATCH firewall/rule" to """{"id":1}""",
                "POST firewall/rule" to """{"id":2}""",
                "DELETE firewall/rule" to """{"id":0}""",
                "POST firewall/apply" to """{"applied":true}""",
            ),
        )
        val rows = (session.watch(MenuPath.parse("firewall/rule")).first() as ListEvent.Snapshot).items
        assertEquals(listOf("0", "1"), rows.map { it.id })
        assertEquals(listOf("wan,lan", "true", "8291"), listOf("interface", "disabled", "destination_port").map { rows[1][it] })
        assertEquals("", rows[0]["destination_port"])

        session.set(MenuPath.parse("firewall/rule"), "1", mapOf("disabled" to "false", "interface" to "lan", "destination_port" to null, "descr" to "rd-test 2"))
        assertEquals<JsonElement?>(Json.parseToJsonElement("""{"id":1,"disabled":false,"interface":["lan"],"destination_port":null,"descr":"rd-test 2"}"""), api.calls.last().third)
        assertEquals("2", session.add(MenuPath.parse("firewall/rule"), mapOf("type" to "pass", "interface" to "lan", "descr" to "rd-test")))

        session.remove(MenuPath.parse("firewall/rule"), listOf("0", "1"))
        assertEquals(listOf(mapOf("id" to "1"), mapOf("id" to "0")), api.calls.filter { it.first == "DELETE firewall/rule" }.map { it.second })

        assertEquals(3, session.safeMode.actions.first())
        assertTrue(session.safeMode.enter())
        assertEquals(0, session.safeMode.actions.first())
        assertEquals("POST firewall/apply", api.calls.last().first)
    }

    @Test
    fun anElementMovesToThePlaceOfTheOneItGoesBefore() = runTest {
        val (api, session) = session(mapOf("GET firewall/rules" to rules, "PATCH firewall/rule" to """{"id":0}""", "GET schema/openapi" to openapi))
        session.move(MenuPath.parse("firewall/rule"), listOf("1"), "0")
        assertEquals<JsonElement?>(Json.parseToJsonElement("""{"id":1,"placement":0}"""), api.calls.last().third)
        session.move(MenuPath.parse("firewall/rule"), listOf("0"), null)
        assertEquals<JsonElement?>(Json.parseToJsonElement("""{"id":0,"placement":1}"""), api.calls.last().third)
        assertFailsWith<DeviceException> { session.move(MenuPath.parse("firewall/rule"), listOf("0", "1"), null) }
    }

    @Test
    fun aListOfTheStateIsOnlyRead() = runTest {
        val (_, session) = session(
            mapOf(
                "GET status/gateways" to """[{"name":"WAN_DHCP","srcip":"192.0.2.2","monitorip":"192.0.2.1","delay":"0.412ms","loss":"0.0%","status":"online"}]""",
                "GET status/system" to """{"platform":"pfSense","cpu_usage":12.5,"cpu_count":2,"uptime":"1 Day 02 Hours 03 Minutes","mem_usage":35.2}""",
            ),
        )
        val schema = session.describe(MenuPath.parse("routing/gateway-status"))
        assertEquals(listOf("print"), schema.commands)
        assertTrue(schema.arguments.all { it.readOnly })
        assertEquals("online", session.get(MenuPath.parse("routing/gateway-status"), null)?.get("status"))
        assertFailsWith<DeviceException> { session.set(MenuPath.parse("routing/gateway-status"), "0", mapOf("name" to "rd-test")) }
        val resource = session.get(MenuPath.parse("system/resource"), null)
        assertEquals(listOf("12", "35", "1d2h3m"), listOf("cpu-load", "memory-usage", "uptime").map { resource?.get(it) })
    }

    @Test
    fun theFirewallLogIsReadInTheFieldsOfItsPackets() = runTest {
        val head = "Oct 25 03:58:37 pfSense filterlog[71758]: 93,,,1792900685,vtnet0,match,pass,out,4,0x0,,64"
        val log = listOf(
            "$head,0,0,DF,6,tcp,60,192.168.1.1,192.168.1.250,58778,80,0,S,2399690791,,65228,,mss;nop;wscale;sackOK;TS",
            "$head,30026,0,none,17,udp,57,192.168.1.1,192.168.1.250,63473,53,37",
            "$head,62527,0,none,1,icmp,84,192.168.1.1,192.168.1.250,request,63921,064",
            "Oct 25 03:58:40 pfSense filterlog[71758]: an unknown line",
        )
        val (_, session) = session(
            mapOf(
                "GET status/logs/firewall" to log.mapIndexed { index, text -> """{"id":$index,"text":"$text"}""" }.joinToString(",", "[", "]"),
                "GET interfaces" to """[{"id":"wan","if":"vtnet1","descr":"WAN"},{"id":"lan","if":"vtnet0","descr":"LAN"}]""",
                "GET firewall/rules" to """[{"id":0,"tracker":1792900685,"descr":"rd-test-log"}]""",
            ),
        )
        val rows = (session.watch(MenuPath.parse("log/firewall")).first() as ListEvent.Snapshot).items
        val columns = listOf("time", "action", "interface", "direction", "protocol", "source", "destination", "flags", "rule")
        assertEquals(listOf("Oct 25 03:58:37", "pass", "LAN", "out", "tcp", "192.168.1.1:58778", "192.168.1.250:80", "S", "rd-test-log"), columns.map { rows[0][it] })
        assertEquals(listOf("udp", "192.168.1.1:63473", "192.168.1.250:53", null), listOf("protocol", "source", "destination", "flags").map { rows[1][it] })
        assertEquals(listOf("icmp", "192.168.1.1", "request"), listOf("protocol", "source", "flags").map { rows[2][it] })
        assertEquals(null, rows[3]["action"])
        assertEquals("Oct 25 03:58:40 pfSense filterlog[71758]: an unknown line", rows[3]["text"])
    }

    @Test
    fun aPacketOfIpv6HasItsFieldsAtOtherPlaces() {
        val fields = filterLog("5,,,1000000104,vtnet1,match,block,in,6,0x00,0x00000,64,tcp,6,40,2001:db8::1,2001:db8::2,51000,22,0,S,1,,64800,,mss")
        assertEquals(listOf("block", "in", "tcp", "40", "[2001:db8::1]:51000", "[2001:db8::2]:22", "S"), listOf("action", "direction", "protocol", "length", "source", "destination", "flags").map { fields?.get(it) })
        assertEquals(null, filterLog("5,,,1000000104,vtnet1,match,block,in"))
        assertEquals(mapOf("time" to "Oct 25 03:56:58", "program" to "check_reload_status", "message" to "Syncing firewall"), syslogLine("Oct 25 03:56:58 pfSense check_reload_status[425]: Syncing firewall"))
    }

    @Test
    fun theJournalWritesTheCallOfTheApi() {
        assertEquals("POST /api/v2/firewall/alias {\"name\":\"rd_test\"}", pfsenseCall(MenuPath.parse("firewall/alias"), EditKind.Add, emptyList(), mapOf("name" to "rd_test")))
        assertEquals("DELETE /api/v2/firewall/alias?id=3", pfsenseCall(MenuPath.parse("firewall/alias"), EditKind.Remove, listOf("3"), emptyMap()))
    }

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
        val silent = HttpsPfsense("192.0.2.1", "admin", "rd-test-secret") { Silent() }
        assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { silent.call("POST", "firewall/apply", emptyMap(), JsonObject(emptyMap())) }.category)
        assertEquals(240_000, testScheduler.currentTime)
    }
}
