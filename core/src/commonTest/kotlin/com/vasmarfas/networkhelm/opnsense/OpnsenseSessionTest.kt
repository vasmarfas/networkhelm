package com.vasmarfas.networkhelm.opnsense

import com.vasmarfas.networkhelm.journal.EditKind
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpnsenseSessionTest {

    private class Api(private val answers: Map<String, String>) : OpnsenseCall {
        val calls = mutableListOf<Triple<String, String, JsonObject?>>()

        override suspend fun call(method: String, path: String, body: JsonObject?): JsonElement {
            calls += Triple(method, path, body)
            val answer = answers["$method $path"] ?: throw DeviceException(ErrorCategory.MissingItem, "this OPNsense has no /api/$path")
            return Json.parseToJsonElement(answer)
        }
    }

    private val device = DeviceInfo(Platform.Opnsense, "OPNsense.localdomain", "24.7", "amd64", "", emptySet(), setOf("read", "write"))

    private val template = """{"alias":{"enabled":"1","name":"","type":{"host":{"value":"Host(s)","selected":0},"network":{"value":"Network(s)","selected":0}},""" +
        """"proto":{"IPv4":{"value":"IPv4","selected":0},"IPv6":{"value":"IPv6","selected":0}},"interface":{"":{"value":"None","selected":1},"lan":{"value":"LAN","selected":0}},""" +
        """"content":{"":{"value":"","selected":1},"bogons":{"value":"bogons","selected":0}},"description":""}}"""

    private fun session(answers: Map<String, String>) = Api(answers).let { it to OpnsenseSession(it, device) }

    @Test
    fun aModelListTakesItsFieldsAndChoicesFromTheTemplateOfANewElement() = runTest {
        val (_, session) = session(mapOf("GET firewall/alias/get_item" to template))
        val schema = session.describe(MenuPath.parse("firewall/alias"))
        assertEquals(NodeKind.List, schema.kind)
        assertEquals(listOf("print", "add", "set", "remove"), schema.commands)
        assertEquals(ValueType.YesNo, schema.argument("disabled")?.type)
        assertNull(schema.argument("enabled"))
        assertEquals(ValueType.Enumeration(multiple = false), schema.argument("type")?.type)
        assertEquals(listOf("host", "network"), schema.argument("type")?.choices)
        assertEquals(ValueType.Enumeration(multiple = true), schema.argument("proto")?.type)
        assertEquals(listOf("lan"), schema.argument("interface")?.choices)
        assertTrue(schema.argument("interface")?.optional == true)
        assertEquals(ValueType.Many(ValueType.Text(), "\n"), schema.argument("content")?.type)
        assertEquals(ValueType.Text(), schema.argument("description")?.type)
        assertEquals(listOf("interfaces", "firewall", "routes", "unbound", "wireguard", "dnsmasq", "kea", "system", "services", "diagnostics", "log"), session.describe(MenuPath.Root).children)
        assertFailsWith<DeviceException> { session.describe(MenuPath.parse("system/rd-test")) }
    }

    @Test
    fun aBodyGoesAsAFormWithTheNamesNested() {
        val body = Json.parseToJsonElement("""{"alias":{"name":"rd_test","content":"192.0.2.1\n192.0.2.2","description":"тест & 100%"},"sort":{},"rowCount":-1}""")
        assertEquals(
            "alias[name]=rd_test&alias[content]=192.0.2.1%0A192.0.2.2&alias[description]=%D1%82%D0%B5%D1%81%D1%82%20%26%20100%25&rowCount=-1",
            form(body as JsonObject),
        )
    }

    @Test
    fun rowsComeByTheirUuidAndAnElementWithTheKeysOfItsChosenOptions() = runTest {
        val (_, session) = session(
            mapOf(
                "POST firewall/alias/search_item" to """{"rows":[{"uuid":"u1","enabled":"1","name":"rd-test","type":"Host(s)","content":"192.0.2.1"}],"rowCount":1,"total":1,"current":1}""",
                "GET firewall/alias/get_item/u1" to """{"alias":{"name":"rd-test","type":{"host":{"value":"Host(s)","selected":1},"network":{"value":"Network(s)","selected":0}},""" +
                    """"interface":{"":{"value":"None","selected":1},"lan":{"value":"LAN","selected":0}},""" +
                    """"content":{"bogons":{"value":"bogons","selected":0},"192.0.2.1":{"value":"192.0.2.1","selected":1},"192.0.2.2":{"value":"192.0.2.2","selected":1}}}}""",
                "GET diagnostics/interface/search_arp" to """{"total":1,"rowCount":1,"current":1,"rows":[{"mac":"b8:69:f4:12:34:56","ip":"192.0.2.10","intf":"vtnet0"}]}""",
                "GET interfaces/overview/interfaces_info" to """{"total":3,"rowCount":3,"current":1,"rows":[""" +
                    """{"identifier":"lan","device":"vtnet0","enabled":true,"ipv4":[{"ipaddr":"192.0.2.1/24"}],"ipv6":[{"ipaddr":"::1/128"},{"ipaddr":"fe80::1/64"}]},""" +
                    """{"identifier":"","device":"enc0","enabled":null,"ipv4":[]},{"identifier":"","device":"pflog0","enabled":null,"ipv4":[]}]}""",
            ),
        )
        val snapshot = session.watch(MenuPath.parse("firewall/alias")).first() as ListEvent.Snapshot
        assertEquals("u1", snapshot.items.single().id)
        assertEquals("rd-test", snapshot.items.single()["name"])
        assertEquals("false", snapshot.items.single()["disabled"])
        assertFalse("uuid" in snapshot.items.single().values || "enabled" in snapshot.items.single().values)
        val alias = session.get(MenuPath.parse("firewall/alias"), "u1")
        assertEquals("host", alias?.get("type"))
        assertEquals("", alias?.get("interface"))
        assertEquals("192.0.2.1\n192.0.2.2", alias?.get("content"))
        val arp = session.watch(MenuPath.parse("diagnostics/arp")).first() as ListEvent.Snapshot
        assertEquals("0", arp.items.single().id)
        assertEquals("192.0.2.10", arp.items.single()["ip"])
        val interfaces = (session.watch(MenuPath.parse("interfaces/overview")).first() as ListEvent.Snapshot).items
        assertEquals(listOf("lan", "1", "2"), interfaces.map { it.id })
        assertEquals(listOf("192.0.2.1/24", "::1/128,fe80::1/64"), listOf(interfaces[0]["ipv4"], interfaces[0]["ipv6"]))
        assertEquals(listOf("false", "true", "true"), interfaces.map { it["disabled"] })
    }

    @Test
    fun theRulesComeFromTheWholeModelInTheOrderOfTheirSequence() = runTest {
        val model = """{"filter":{"rules":{"rule":{""" +
            """"r2":{"enabled":"0","sequence":"20","action":{"pass":{"value":"Pass","selected":0},"block":{"value":"Block","selected":1}},"description":"rd-test 2"},""" +
            """"r1":{"enabled":"1","sequence":"10","action":{"pass":{"value":"Pass","selected":1},"block":{"value":"Block","selected":0}},"description":"rd-test 1"}""" +
            """}},"snatrules":{"rule":[]}}}"""
        val (_, session) = session(mapOf("GET firewall/filter/get" to model, "GET firewall/source_nat/get" to model))
        val rules = session.watch(MenuPath.parse("firewall/filter")).first() as ListEvent.Snapshot
        assertEquals(listOf("r1", "r2"), rules.items.map { it.id })
        assertEquals(listOf("pass", "block"), rules.items.map { it["action"] })
        assertEquals(listOf("false", "true"), rules.items.map { it["disabled"] })
        assertEquals(0, session.count(MenuPath.parse("firewall/source-nat")))
    }

    @Test
    fun onlyTheFieldsTouchedGoUnderTheKeyOfTheElement() = runTest {
        val (api, session) = session(
            mapOf(
                "POST firewall/alias/set_item/u1" to """{"result":"saved"}""",
                "POST firewall/alias/add_item" to """{"result":"saved","uuid":"u2"}""",
                "POST firewall/alias/del_item/u2" to """{"result":"deleted"}""",
            ),
        )
        session.set(MenuPath.parse("firewall/alias"), "u1", mapOf("description" to "rd-test", "content" to null))
        assertEquals("POST firewall/alias/set_item/u1", api.calls.single().let { "${it.first} ${it.second}" })
        assertEquals<JsonElement?>(Json.parseToJsonElement("""{"alias":{"description":"rd-test","content":""}}"""), api.calls.single().third)
        session.set(MenuPath.parse("firewall/alias"), "u1", mapOf("disabled" to "yes"))
        assertEquals<JsonElement?>(Json.parseToJsonElement("""{"alias":{"enabled":"0"}}"""), api.calls.last().third)
        assertEquals("u2", session.add(MenuPath.parse("firewall/alias"), mapOf("name" to "rd_test", "type" to "host", "content" to "192.0.2.1")))
        session.remove(MenuPath.parse("firewall/alias"), listOf("u2"))
        assertEquals(listOf("firewall/alias/set_item/u1", "firewall/alias/set_item/u1", "firewall/alias/add_item", "firewall/alias/del_item/u2"), api.calls.map { it.second })
        assertEquals(4, session.safeMode.actions.first())
        assertEquals(
            "POST /api/firewall/alias/set_item/u1 alias[description]=rd-test&alias[enabled]=1",
            opnsenseCall(MenuPath.parse("firewall/alias"), EditKind.Set, listOf("u1"), mapOf("disabled" to "false", "description" to "rd-test")),
        )
    }

    @Test
    fun aFieldThatDoesNotPassIsNamedWithTheMessageOfOpnsense() = runTest {
        val (_, session) = session(mapOf("POST firewall/alias/add_item" to """{"result":"failed","validations":{"alias.name":"The name must be unique."}}"""))
        val failure = assertFailsWith<DeviceException> { session.add(MenuPath.parse("firewall/alias"), mapOf("name" to "rd_test")) }
        assertEquals(ErrorCategory.ArgumentValue, failure.category)
        assertEquals("name: The name must be unique.", failure.message)
        assertEquals(0, session.safeMode.actions.first())
    }

    @Test
    fun applyGoesFromASavepointForTheFirewallAndAtOnceForTheRest() = runTest {
        val (api, session) = session(
            mapOf(
                "POST firewall/filter/add_rule" to """{"result":"saved","uuid":"r1"}""",
                "POST firewall/alias/add_item" to """{"result":"saved","uuid":"a1"}""",
                "POST firewall/filter/savepoint" to """{"status":"ok","retention":"60","revision":"1729700000.5"}""",
                "POST firewall/filter/apply/1729700000.5" to """{"status":"OK"}""",
                "POST firewall/alias/reconfigure" to """{"status":"ok"}""",
                "POST firewall/filter/cancel_rollback/1729700000.5" to """{"status":"ok"}""",
            ),
        )
        session.add(MenuPath.parse("firewall/filter"), mapOf("description" to "rd-test"))
        session.add(MenuPath.parse("firewall/alias"), mapOf("name" to "rd_test"))
        assertTrue(session.safeMode.enter())
        assertTrue(session.safeMode.active.first())
        assertEquals(0, session.safeMode.actions.first())
        session.safeMode.commit()
        assertFalse(session.safeMode.active.first())
        assertEquals(
            listOf(
                "firewall/filter/savepoint", "firewall/filter/add_rule", "firewall/alias/add_item", "firewall/filter/apply/1729700000.5",
                "firewall/alias/reconfigure", "firewall/filter/cancel_rollback/1729700000.5",
            ),
            api.calls.map { it.second },
        )
    }

    @Test
    fun aRollbackGoesBackToTheSavepointBeforeTheEdits() = runTest {
        val (api, session) = session(
            mapOf(
                "POST firewall/filter/savepoint" to """{"status":"ok","retention":"60","revision":"1729700000.5"}""",
                "POST firewall/filter/add_rule" to """{"result":"saved","uuid":"r1"}""",
                "POST firewall/filter/apply/1729700000.5" to """{"status":"OK"}""",
                "POST firewall/filter/revert/1729700000.5" to """{"status":"ok"}""",
                "POST firewall/filter/cancel_rollback/1729700000.5" to """{"status":"ok"}""",
            ),
        )
        val rules = MenuPath.parse("firewall/filter")
        session.add(rules, mapOf("description" to "rd-test"))
        assertTrue(session.safeMode.enter())
        session.safeMode.rollback()
        assertFalse(session.safeMode.active.first())
        session.add(rules, mapOf("description" to "rd-test"))
        assertEquals(
            listOf(
                "firewall/filter/savepoint", "firewall/filter/add_rule", "firewall/filter/apply/1729700000.5", "firewall/filter/revert/1729700000.5",
                "firewall/filter/cancel_rollback/1729700000.5", "firewall/filter/savepoint", "firewall/filter/add_rule",
            ),
            api.calls.map { it.second },
        )
    }

    @Test
    fun applyGoesAtOnceWhereTheFirewallHasNoSavepoint() = runTest {
        val (api, session) = session(
            mapOf(
                "POST firewall/filter/add_rule" to """{"result":"saved","uuid":"r1"}""",
                "POST firewall/filter/apply" to """{"status":"OK\n\n"}""",
            ),
        )
        session.add(MenuPath.parse("firewall/filter"), mapOf("description" to "rd-test"))
        assertTrue(session.safeMode.enter())
        assertFalse(session.safeMode.active.first())
        assertEquals(0, session.safeMode.actions.first())
        assertEquals(listOf("firewall/filter/savepoint", "firewall/filter/add_rule", "firewall/filter/apply"), api.calls.map { it.second })
    }

    @Test
    fun rulesMoveByTheirSequence() = runTest {
        val model = """{"filter":{"rules":{"rule":{""" +
            """"r1":{"enabled":"1","sequence":"10","description":"rd-test 1"},"r2":{"enabled":"1","sequence":"11","description":"rd-test 2"},""" +
            """"r3":{"enabled":"1","sequence":"30","description":"rd-test 3"}}}}}"""
        val saved = """{"result":"saved"}"""
        val (api, session) = session(
            mapOf(
                "GET firewall/filter/get" to model,
                "GET firewall/filter/get_rule" to """{"rule":{"enabled":"1","sequence":"1","description":""}}""",
                "POST firewall/filter/set_rule/r1" to saved,
                "POST firewall/filter/set_rule/r2" to saved,
                "POST firewall/filter/set_rule/r3" to saved,
            ),
        )
        val rules = MenuPath.parse("firewall/filter")
        assertTrue("move" in session.describe(rules).commands)
        session.move(rules, listOf("r3"), "r2")
        session.move(rules, listOf("r3"), "r1")
        session.move(rules, listOf("r1"), null)
        assertEquals(
            listOf("savepoint" to "", "r3" to "20", "r2" to "30", "r3" to "5", "r1" to "40"),
            api.calls.filter { it.first == "POST" }.map { (_, path, body) -> path.substringAfterLast('/') to body?.get("rule")?.let { (it as JsonObject)["sequence"]?.jsonPrimitive?.content }.orEmpty() },
        )
        assertEquals(3, session.safeMode.actions.first())
        assertFailsWith<DeviceException> { session.move(rules, listOf("r2"), "r9") }
        assertFailsWith<DeviceException> { session.move(MenuPath.parse("firewall/alias"), listOf("a1"), null) }
        assertEquals("# firewall/filter: r3 before r1 by their sequence", opnsenseCall(rules, EditKind.Move, listOf("r3"), emptyMap(), "r1"))
    }

    @Test
    fun theLogsShowNewestLastAndTheFirewallLogGrowsByDigest() = runTest {
        fun packet(digest: String, time: String, port: String) =
            """{"interface":"vtnet0","action":"pass","dir":"in","protoname":"tcp","src":"192.168.1.2","srcport":"$port","dst":"192.168.1.1","dstport":"443",""" +
                """"tcpflags":"S","__timestamp__":"$time","__digest__":"$digest","label":"anti-lockout rule"}"""
        val (api, session) = session(
            mapOf(
                "POST diagnostics/log/core/system" to """{"rows":[{"timestamp":"2026-10-25T03:05:05","severity":"Notice","process_name":"dhcp6c","pid":"80736","line":" Sending Solicit on vtnet1"},""" +
                    """{"timestamp":"2026-10-25T02:07:50","severity":"Error","process_name":"dhcp6c","pid":"80736","line":" Sending Solicit on vtnet1"}]}""",
                "GET diagnostics/firewall/log?limit=100" to "[${packet("b2", "2026-10-25T04:05:43", "57940")},${packet("b1", "2026-10-25T04:05:32", "33394")}]",
                "GET diagnostics/firewall/log?limit=100&digest=b2" to "[${packet("b3", "2026-10-25T04:05:54", "41000")},${packet("b2", "2026-10-25T04:05:43", "57940")}]",
                "GET interfaces/overview/interfaces_info" to """[{"identifier":"lan","device":"vtnet0","description":"LAN"}]""",
            ),
        )
        val system = session.get(MenuPath.parse("log/system"), null)
        assertEquals(listOf("2026-10-25 02:07:50", "Error", "dhcp6c", "Sending Solicit on vtnet1"), listOf("time", "severity", "program", "message").map { system?.get(it) })
        assertEquals(500, api.calls.first().third?.get("rowCount")?.jsonPrimitive?.content?.toInt())

        val log = MenuPath.parse("log/firewall")
        val first = (session.watch(log).first() as ListEvent.Snapshot).items
        assertEquals(listOf("b1", "b2"), first.map { it.id })
        assertEquals(listOf("2026-10-25 04:05:43", "pass", "LAN", "in", "tcp", "192.168.1.2:57940", "192.168.1.1:443", "S", "anti-lockout rule"), listOf("time", "action", "interface", "direction", "protocol", "source", "destination", "flags", "rule").map { first[1][it] })
        assertEquals(listOf("b1", "b2", "b3"), (session.watch(log).first() as ListEvent.Snapshot).items.map { it.id })
    }

    @Test
    fun dnsmasqListsSaveThroughTheSettingsAndApplyThroughTheService() = runTest {
        val (api, session) = session(
            mapOf(
                "GET dnsmasq/settings/get_host" to """{"host":{"host":"","domain":"","local":"0","ip":{"":{"value":"","selected":1}},"hwaddr":{"":{"value":"","selected":1}},"ignore":"0",""" +
                    """"set_tag":{"":{"value":"None","selected":1},"t1":{"value":"voip","selected":0}},"descr":""}}""",
                "GET dnsmasq/settings/get_host/h1" to """{"host":{"host":"nas","domain":"lan.example","local":"0","ip":{"192.0.2.10":{"value":"192.0.2.10","selected":1}},""" +
                    """"hwaddr":{"02:00:00:aa:bb:cc":{"value":"02:00:00:aa:bb:cc","selected":1}},"ignore":"1","set_tag":{"":{"value":"None","selected":0},"t1":{"value":"voip","selected":1}},"descr":"rd-test"}}""",
                "POST dnsmasq/settings/add_host" to """{"result":"saved","uuid":"h1"}""",
                "POST dnsmasq/settings/set_host/h1" to """{"result":"saved"}""",
                "POST dnsmasq/settings/del_host/h1" to """{"result":"deleted"}""",
                "POST dnsmasq/service/reconfigure" to """{"status":"ok"}""",
            ),
        )
        val hosts = MenuPath.parse("dnsmasq/host")
        val schema = session.describe(hosts)
        assertEquals(listOf("print", "add", "set", "remove"), schema.commands)
        assertEquals(listOf(ValueType.Flag, ValueType.Flag, ValueType.Text()), listOf("local", "ignore", "ip").map { schema.argument(it)?.type })
        assertEquals(mapOf("t1" to "voip"), schema.argument("set_tag")?.labels)
        val host = session.get(hosts, "h1")
        assertEquals(listOf("192.0.2.10", "02:00:00:aa:bb:cc", "1", "t1"), listOf("ip", "hwaddr", "ignore", "set_tag").map { host?.get(it) })
        assertEquals("h1", session.add(hosts, mapOf("host" to "nas", "ip" to "192.0.2.10")))
        session.set(hosts, "h1", mapOf("ignore" to "0"))
        session.remove(hosts, listOf("h1"))
        assertEquals(3, session.safeMode.actions.first())
        assertTrue(session.safeMode.enter())
        assertEquals(0, session.safeMode.actions.first())
        assertEquals(
            listOf(
                "dnsmasq/settings/get_host", "dnsmasq/settings/get_host/h1", "dnsmasq/settings/add_host", "dnsmasq/settings/set_host/h1", "dnsmasq/settings/del_host/h1",
                "dnsmasq/service/reconfigure",
            ),
            api.calls.map { it.second },
        )
        assertEquals<JsonElement?>(Json.parseToJsonElement("""{"host":{"host":"nas","ip":"192.0.2.10"}}"""), api.calls[2].third)
    }

    @Test
    fun keaKeepsItsNestedOptionsFlatWithDotsAndSendsThemNested() = runTest {
        val allocator = """"allocator":{"":{"value":"","selected":1},"iterative":{"value":"iterative","selected":0},"random":{"value":"random","selected":0}}"""
        val (api, session) = session(
            mapOf(
                "GET kea/dhcpv4/get_subnet" to """{"subnet4":{"subnet":"","option_data_autocollect":"1","option_data":{"routers":{"":{"value":"","selected":1}},"domain_name":""},"option":[],""" +
                    """"match-client-id":"1",$allocator,"pools":"","description":""}}""",
                "GET kea/dhcpv4/get_subnet/s1" to """{"subnet4":{"subnet":"10.0.0.0/24","option_data_autocollect":"1","option_data":{"routers":{"10.0.0.1":{"value":"10.0.0.1","selected":1}},""" +
                    """"domain_name":""},"option":[],"match-client-id":"0",$allocator,"pools":"10.0.0.100-10.0.0.150","description":"rd-test"}}""",
                "GET kea/dhcpv4/get_reservation" to """{"reservation":{"subnet":{"s1":{"value":"10.0.0.0/24","selected":0}},"ip_address":"","hw_address":"","hostname":"","description":""}}""",
                "POST kea/dhcpv4/set_subnet/s1" to """{"result":"saved"}""",
                "POST kea/service/reconfigure" to """{"status":"ok"}""",
            ),
        )
        val subnets = MenuPath.parse("kea/subnet")
        val schema = session.describe(subnets)
        assertEquals(listOf(ValueType.Text(), ValueType.Flag, ValueType.Flag), listOf("option_data.routers", "option_data_autocollect", "match-client-id").map { schema.argument(it)?.type })
        assertEquals(listOf("iterative", "random"), schema.argument("allocator")?.choices)
        val subnet = session.get(subnets, "s1")
        assertEquals(
            listOf("10.0.0.0/24", "10.0.0.1", "", "", "0"),
            listOf("subnet", "option_data.routers", "option_data.domain_name", "option", "match-client-id").map { subnet?.get(it) },
        )
        session.set(subnets, "s1", mapOf("option_data.routers" to "10.0.0.2", "option_data.domain_name" to "lan.example", "description" to "rd-test changed"))
        assertEquals<JsonElement?>(
            Json.parseToJsonElement("""{"subnet4":{"option_data":{"routers":"10.0.0.2","domain_name":"lan.example"},"description":"rd-test changed"}}"""),
            api.calls.last().third,
        )
        assertEquals(mapOf("s1" to "10.0.0.0/24"), session.describe(MenuPath.parse("kea/reservation")).argument("subnet")?.labels)
        assertTrue(session.safeMode.enter())
        assertEquals("kea/service/reconfigure", api.calls.last().second)
    }

    @Test
    fun usersAndGroupsUseTheBareVerbsAndAGroupReadsItsMembersFromAList() = runTest {
        val (api, session) = session(
            mapOf(
                "GET auth/group/get" to """{"group":{"gid":"2000","name":"","scope":"user","priv":{"page-all":{"value":"All pages","selected":0}},""" +
                    """"member":[{"value":"root","selected":0},{"value":"rd","selected":0}]}}""",
                "GET auth/group/get/g1" to """{"group":{"gid":"2000","name":"rd-test","scope":"user","priv":{"page-all":{"value":"All pages","selected":1}},""" +
                    """"member":[{"value":"root","selected":0},{"value":"rd","selected":1}]}}""",
                "POST auth/user/search" to """{"rows":[{"uuid":"u1","uid":"0","name":"root","disabled":"0","password":"hash","%password":"","apikeys":"key|hash","otp_seed":"seed",""" +
                    """"group_memberships":"1999","%group_memberships":"admins","descr":"System Administrator"}],"rowCount":1,"total":1,"current":1}""",
                "POST auth/user/add" to """{"result":"saved","uuid":"u2"}""",
                "POST auth/user/set/u2" to """{"result":"saved"}""",
                "POST auth/user/del/u2" to """{"result":"deleted"}""",
            ),
        )
        val groups = MenuPath.parse("system/groups")
        val schema = session.describe(groups)
        assertEquals(ValueType.Enumeration(multiple = true), schema.argument("member")?.type)
        assertEquals(mapOf("0" to "root", "1" to "rd"), schema.argument("member")?.labels)
        assertEquals(mapOf("page-all" to "All pages"), schema.argument("priv")?.labels)
        assertTrue(schema.argument("gid")?.readOnly == true)
        val group = session.get(groups, "g1")
        assertEquals(listOf("1", "page-all"), listOf("member", "priv").map { group?.get(it) })

        val users = MenuPath.parse("system/users")
        val root = (session.watch(users).first() as ListEvent.Snapshot).items.single()
        assertEquals(listOf("u1", "false", "admins", "System Administrator"), listOf(root.id, root["disabled"], root["%group_memberships"], root["descr"]))
        assertFalse(listOf("password", "%password", "apikeys", "otp_seed").any { it in root.values })
        assertEquals("u2", session.add(users, mapOf("name" to "rd-test", "password" to "x", "disabled" to "no")))
        session.set(users, "u2", mapOf("disabled" to "yes"))
        session.remove(users, listOf("u2"))
        assertEquals<JsonElement?>(Json.parseToJsonElement("""{"user":{"name":"rd-test","password":"x","disabled":"0"}}"""), api.calls.first { it.second == "auth/user/add" }.third)
        assertEquals<JsonElement?>(Json.parseToJsonElement("""{"user":{"disabled":"1"}}"""), api.calls.first { it.second == "auth/user/set/u2" }.third)
        assertEquals(0, session.safeMode.actions.first())
    }

    @Test
    fun certificatesAreAReadOnlyListWithDatesAndWithoutKeys() = runTest {
        val (_, session) = session(
            mapOf(
                "POST trust/cert/search" to """{"rows":[{"uuid":"c1","descr":"Web GUI TLS certificate","name":"/CN=OPNsense","caref":"","crt":"LS0t","prv":"LS0t",""" +
                    """"crt_payload":"-----BEGIN CERTIFICATE-----","prv_payload":"-----BEGIN PRIVATE KEY-----","valid_from":"1793444859","valid_to":"1827153373","in_use":"1"}],""" +
                    """"rowCount":1,"total":1,"current":1}""",
            ),
        )
        val certificates = MenuPath.parse("system/certificates")
        assertEquals(listOf("print"), session.describe(certificates).commands)
        val certificate = (session.watch(certificates).first() as ListEvent.Snapshot).items.single()
        assertEquals(listOf("2026-10-31 11:07:39", "2027-11-25 14:36:13"), listOf("valid_from", "valid_to").map { certificate[it] })
        assertFalse(listOf("crt", "prv", "crt_payload", "prv_payload").any { it in certificate.values })
        assertFailsWith<DeviceException> { session.set(certificates, "c1", mapOf("descr" to "rd-test")) }
    }

    @Test
    fun theSystemListsKeepTheNamesOfTheirControllers() = runTest {
        val (api, session) = session(
            mapOf(
                "POST cron/settings/search_jobs" to """{"rows":[{"uuid":"j1","origin":"cron","enabled":"0","minutes":"0","hours":"3","command":"firmware poll",""" +
                    """"%command":"Firmware update check","description":"rd-test"}],"rowCount":1,"total":1,"current":1}""",
                "POST cron/settings/set_job/j1" to """{"result":"saved"}""",
                "POST core/tunables/set_item/t1" to """{"result":"saved"}""",
                "POST cron/service/reconfigure" to """{"status":"ok"}""",
                "POST core/tunables/reconfigure" to """{"status":"ok"}""",
                "POST routing/settings/search_gateway" to """{"total":1,"rowCount":1,"current":1,"rows":[{"interface":"wan","name":"WAN_DHCP6","gateway":"fe80::2",""" +
                    """"uuid":"WAN_DHCP6","disabled":false,"status":"Online","delay":"~"}]}""",
            ),
        )
        val cron = MenuPath.parse("system/cron")
        val job = (session.watch(cron).first() as ListEvent.Snapshot).items.single()
        assertEquals(listOf("j1", "true", "Firmware update check"), listOf(job.id, job["disabled"], job["%command"]))
        session.set(cron, "j1", mapOf("disabled" to "no", "hours" to "4"))
        session.set(MenuPath.parse("system/tunables"), "t1", mapOf("value" to "1"))
        assertEquals<JsonElement?>(Json.parseToJsonElement("""{"job":{"hours":"4","enabled":"1"}}"""), api.calls.first { it.second == "cron/settings/set_job/j1" }.third)
        assertEquals<JsonElement?>(Json.parseToJsonElement("""{"sysctl":{"value":"1"}}"""), api.calls.first { it.second == "core/tunables/set_item/t1" }.third)
        assertTrue(session.safeMode.enter())
        assertEquals(listOf("cron/service/reconfigure", "core/tunables/reconfigure"), api.calls.map { it.second }.filter { it.endsWith("reconfigure") })
        val gateway = (session.watch(MenuPath.parse("system/gateways")).first() as ListEvent.Snapshot).items.single()
        assertEquals(listOf("WAN_DHCP6", "false", "Online"), listOf(gateway.id, gateway["disabled"], gateway["status"]))
    }

    @Test
    fun resourcesAndFirmwareAreSettingsMadeOfSeveralAnswers() = runTest {
        val (_, session) = session(
            mapOf(
                "GET diagnostics/system/systemResources" to """{"memory":{"total":"2102894592","total_frmt":"2005","used":508536950,"used_frmt":"484"}}""",
                "GET diagnostics/system/systemTime" to """{"uptime":"00:02:28","datetime":"Sat Oct 31 11:02:58 UTC 2026","loadavg":"0.24, 0.09, 0.03"}""",
                "GET diagnostics/system/systemDisk" to """{"devices":[{"device":"/dev/ufs/OPNsense_Nano","type":"ufs","blocks":"2.8G","used":"2.1G","available":"503M","used_pct":81,""" +
                    """"mountpoint":"/"},{"device":"tmpfs","type":"tmpfs","blocks":"1.0G","used":"176K","available":"1.0G","used_pct":0,"mountpoint":"/var/log"}]}""",
                "GET diagnostics/cpu_usage/getCPUType" to """["Intel(R) Core(TM) i7 (2 cores, 2 threads)"]""",
                "GET core/firmware/status" to """{"product":{"product_version":"26.7","product_latest":"26.7","product_check":null,"product_license":[]},""" +
                    """"status_msg":"Check first.","status":"none"}""",
            ),
        )
        assertEquals(
            listOf("gateways", "cron", "tunables", "users", "groups", "certificates", "resources", "firmware"),
            session.describe(MenuPath.parse("system")).children,
        )
        val resources = MenuPath.parse("system/resources")
        val schema = session.describe(resources)
        assertEquals(NodeKind.Settings, schema.kind)
        assertEquals(listOf("print"), schema.commands)
        assertTrue(schema.arguments.all { it.readOnly })
        val item = (session.watch(resources).first() as ListEvent.Snapshot).items.single()
        assertEquals(
            listOf("OPNsense.localdomain", "24.7", "Intel(R) Core(TM) i7 (2 cores, 2 threads)", "00:02:28", "2102894592", "508536950", "/: 2.1G/2.8G (81%)\n/var/log: 176K/1.0G (0%)"),
            listOf("hostname", "version", "cpu", "uptime", "memory_total", "memory_used", "disks").map { item[it] },
        )
        assertNull(item["boottime"])
        val firmware = session.get(MenuPath.parse("system/firmware"), null)
        assertEquals(listOf("26.7", "", "none"), listOf("product_version", "product_check", "status").map { firmware?.get(it) })
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
        val silent = HttpsOpnsense("192.0.2.1", "rd-test-key", "rd-test-secret") { Silent() }
        assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { silent.call("POST", "firewall/filter/apply", JsonObject(emptyMap())) }.category)
        assertEquals(180_000, testScheduler.currentTime)
    }
}
