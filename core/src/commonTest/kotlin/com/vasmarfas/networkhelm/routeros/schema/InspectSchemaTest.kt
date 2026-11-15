package com.vasmarfas.networkhelm.routeros.schema

import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.routeros.api.ApiReply
import com.vasmarfas.networkhelm.routeros.api.ApiResult
import com.vasmarfas.networkhelm.routeros.api.RouterOsLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InspectSchemaTest {

    private class Router(private val answers: Map<String, List<Map<String, String>>>, private val takes: Long = 0) : RouterOsLink {
        val asked = mutableListOf<String>()

        override suspend fun call(words: List<String>): ApiResult {
            fun word(prefix: String) = words.single { it.startsWith(prefix) }.removePrefix(prefix)
            val key = word("=request=") + ":" + word("=path=")
            asked += key
            if (takes > 0) delay(takes)
            return ApiResult(answers[key] ?: error("unexpected $key"), emptyMap())
        }

        override fun command(words: List<String>, sent: CompletableDeferred<Unit>?): Flow<ApiReply> = error("not used")
    }

    private fun child(vararg nodes: String) = nodes.map { node ->
        val (type, nodeType, name) = node.split(' ')
        mapOf("type" to type, "node-type" to nodeType, "name" to name)
    }

    private fun syntax(vararg rows: String) = rows.map { row ->
        val (nested, symbol, text) = row.split('|', limit = 3)
        mapOf("nested" to nested.removeSuffix("e"), "symbol-type" to if (nested.endsWith("e")) "explanation" else "definition", "symbol" to symbol, "text" to text)
    }

    private fun completion(vararg values: String) =
        listOf(mapOf("completion" to "[", "style" to "syntax-meta", "show" to "false")) + values.map { mapOf("completion" to it, "style" to "arg", "show" to "true") }

    private val vlan = mapOf(
        "child:interface,vlan" to child("self dir vlan", "child cmd add", "child cmd print", "child cmd remove", "child cmd set", "child cmd unset"),
        "child:interface,vlan,print" to child("self cmd print", "child arg count-only", "child arg where"),
        "child:interface,vlan,add" to child("self cmd add", "child arg copy-from", "child arg disabled", "child arg interface", "child arg name", "child arg place-before", "child arg vlan-id"),
        "child:interface,vlan,set" to child("self cmd set", "child arg disabled", "child arg mtu", "child arg numbers"),
        "syntax:interface,vlan,add,disabled" to syntax("0||"),
        "syntax:interface,vlan,add,interface" to syntax("0||"),
        "syntax:interface,vlan,add,name" to syntax("0|Name|string value"),
        "syntax:interface,vlan,add,vlan-id" to syntax("0|VlanId|Num", "1|Num|1..4094    (integer number)"),
        "syntax:interface,vlan,set,mtu" to syntax("0|Mtu|Num", "1|Num|0..65536    (integer number)"),
        "completion:interface,vlan,add,disabled" to completion("no", "yes"),
        "completion:interface,vlan,add,interface" to completion("ether1", "ether2") + mapOf("completion" to "<value>", "show" to "false"),
        "completion:interface,vlan,unset,value-name" to completion("mtu"),
    )

    @Test
    fun anAnswerCutShortIsNotKeptAndTheMenuIsAskedForAgain() = runTest {
        val cache = MemorySchemaCache()
        val btest = "child:tool,bandwidth-test"
        cache.store("7.22.2 x86_64 routeros", mapOf(btest to emptyList()))
        val router = Router(mapOf(btest to child("self cmd bandwidth-test", "child arg address", "child arg direction"), "syntax:tool,bandwidth-test,address" to syntax("0||"), "syntax:tool,bandwidth-test,direction" to syntax("0||"), "completion:tool,bandwidth-test,direction" to completion("both", "receive", "transmit"), "completion:tool,bandwidth-test,address" to completion()))
        val schema = InspectSchema(router, cache, "7.22.2 x86_64 routeros").describe(MenuPath.parse("tool/bandwidth-test"))
        assertEquals(NodeKind.Command, schema.kind)
        assertEquals(listOf("address", "direction"), schema.arguments.map { it.name })
        assertEquals(1, router.asked.count { it == btest })
        val empty = Router(mapOf(btest to emptyList()))
        val fresh = MemorySchemaCache()
        runCatching { InspectSchema(empty, fresh, "7.22.2").describe(MenuPath.parse("tool/bandwidth-test")) }
        assertEquals(emptyMap(), fresh.load("7.22.2"))
    }

    @Test
    fun listTakesFieldsOfAddThenSetAndAsksOnlyVariantsTheSecondTime() = runTest {
        val cache = MemorySchemaCache()
        val first = Router(vlan)
        val schema = InspectSchema(first, cache, "7.26 x86_64 routeros").describe(MenuPath.parse("interface/vlan"))
        assertEquals(NodeKind.List, schema.kind)
        assertEquals(listOf("add", "print", "remove", "set", "unset"), schema.commands)
        assertEquals(listOf("name", "interface", "disabled", "vlan-id", "mtu"), schema.arguments.map { it.name })
        assertEquals(ValueType.YesNo, schema.argument("disabled")?.type)
        assertEquals(ValueType.Integer(1, 4094, scaled = true), schema.argument("vlan-id")?.type)
        assertEquals(ValueType.Integer(0, 65536, scaled = true), schema.argument("mtu")?.type)
        assertEquals(listOf("mtu"), schema.arguments.filter { it.optional }.map { it.name }, "only what unset offers can be taken away")
        assertFalse(schema.listens)
        val listening = vlan + ("child:interface,vlan,print" to child("self cmd print", "child arg count-only", "child arg follow-only"))
        assertTrue(InspectSchema(Router(listening), MemorySchemaCache(), "7.26 x86_64 routeros").describe(MenuPath.parse("interface/vlan")).listens)
        assertEquals(
            listOf("completion:interface,vlan,add,disabled", "completion:interface,vlan,add,interface", "completion:interface,vlan,unset,value-name"),
            first.asked.filter { it.startsWith("completion") }.sorted(),
        )

        val second = Router(vlan)
        InspectSchema(second, cache, "7.26 x86_64 routeros").describe(MenuPath.parse("interface/vlan"))
        assertEquals(listOf("completion:interface,vlan,add,interface"), second.asked)
    }

    @Test
    fun aFlagOfACommandIsASwitch() = runTest {
        val router = Router(
            mapOf(
                "child:ping" to child("self cmd ping", "child arg arp-ping", "child arg do-not-fragment"),
                "syntax:ping,arp-ping" to syntax("0||"),
                "syntax:ping,do-not-fragment" to syntax("0||"),
                "completion:ping,arp-ping" to completion("no", "yes"),
                "completion:ping,do-not-fragment" to emptyList(),
            ),
        )
        val ping = InspectSchema(router, MemorySchemaCache(), "7.25 x86_64 routeros").describe(MenuPath.parse("ping"))
        assertEquals(NodeKind.Command, ping.kind)
        assertEquals(ValueType.YesNo, ping.argument("arp-ping")?.type)
        assertEquals(ValueType.Switch, ping.argument("do-not-fragment")?.type)
    }

    @Test
    fun theAltNamesOfACertificateAreLinesAfterTheirPrefixes() = runTest {
        val router = Router(
            mapOf(
                "child:certificate" to child("self dir certificate", "child cmd add", "child cmd print"),
                "child:certificate,print" to child("self cmd print", "child arg count-only"),
                "child:certificate,add" to child("self cmd add", "child arg subject-alt-name"),
                "syntax:certificate,add,subject-alt-name" to syntax("0||"),
                "completion:certificate,add,subject-alt-name" to completion("DNS", "IP", "email"),
            ),
        )
        val certificate = InspectSchema(router, MemorySchemaCache(), "7.25 x86_64 routeros").describe(MenuPath.parse("certificate"))
        val names = certificate.argument("subject-alt-name")
        assertEquals(ValueType.Many(ValueType.Text()), names?.type)
        assertEquals(listOf("DNS:", "IP:", "email:"), names?.choices)
    }

    @Test
    fun thePolicyOfAScheduleIsSeveralVariants() = runTest {
        val router = Router(
            mapOf(
                "child:system,scheduler" to child("self dir scheduler", "child cmd add", "child cmd print"),
                "child:system,scheduler,print" to child("self cmd print", "child arg count-only"),
                "child:system,scheduler,add" to child("self cmd add", "child arg policy"),
                "syntax:system,scheduler,add,policy" to syntax("0|Policy|", "1||local | telnet | ssh | ftp | reboot | ..."),
                "completion:system,scheduler,add,policy" to completion("ftp", "read", "write") +
                    mapOf("completion" to "!", "style" to "syntax-meta", "show" to "true") + mapOf("completion" to "<value>", "show" to "false"),
            ),
        )
        val policy = InspectSchema(router, MemorySchemaCache(), "7.25 x86_64 routeros").describe(MenuPath.parse("system/scheduler")).argument("policy")
        assertEquals(ValueType.Enumeration(multiple = true), policy?.type)
        assertEquals(listOf("ftp", "read", "write"), policy?.choices)
    }

    @Test
    fun theDstLimitOfARuleIsARateForEachFlow() = runTest {
        val router = Router(
            mapOf(
                "child:ip,firewall,filter" to child("self dir filter", "child cmd add", "child cmd print"),
                "child:ip,firewall,filter,print" to child("self cmd print", "child arg count-only"),
                "child:ip,firewall,filter,add" to child("self cmd add", "child arg dst-limit"),
                "syntax:ip,firewall,filter,add,dst-limit" to syntax("0|Count|Num", "1|Num|0..4294967295    (integer number)"),
            ),
        )
        val limit = InspectSchema(router, MemorySchemaCache(), "7.25 x86_64 routeros").describe(MenuPath.parse("ip/firewall/filter")).argument("dst-limit")
        val modes = listOf("dst-address", "dst-address-and-port", "src-address", "src-and-dst-addresses", "addresses-and-dst-port")
        assertEquals(ValueType.Rate(modes, perFlow = true), limit?.type)
    }

    @Test
    fun theServersOfDnsAreSeveralAddresses() = runTest {
        val router = Router(
            mapOf(
                "child:ip,dns" to child("self dir dns", "child cmd print", "child cmd set"),
                "child:ip,dns,print" to child("self cmd print"),
                "child:ip,dns,set" to child("self cmd set", "child arg servers"),
                "syntax:ip,dns,set,servers" to syntax("0|Servers|Address[@VRF]", "1|Address|IPv4 | IPv6", "2|IPv4|IPv4 address", "2|IPv6|IPv6 address", "1|VRF|main"),
            ),
        )
        val servers = InspectSchema(router, MemorySchemaCache(), "7.25 x86_64 routeros").describe(MenuPath.parse("ip/dns")).argument("servers")
        assertEquals(ValueType.Many(ValueType.Host(ValueType.Ip, names = false, "Address[@VRF]")), servers?.type)
    }

    @Test
    fun fieldsThatTakeSeveralValuesWhereTheSyntaxNamesOne() = runTest {
        val unnamed = mapOf("completion" to "<value>", "show" to "false")
        val router = Router(
            mapOf(
                "child:interface,bridge,vlan" to child("self dir vlan", "child cmd add", "child cmd print"),
                "child:interface,bridge,vlan,print" to child("self cmd print", "child arg count-only"),
                "child:interface,bridge,vlan,add" to child("self cmd add", "child arg tagged", "child arg vlan-ids"),
                "syntax:interface,bridge,vlan,add,tagged" to syntax("0|Interface|Interface | InterfaceList", "1|Interface|", "2||ether1 | ether2 | bridge1 | ...", "1|InterfaceList|", "2||all | none | dynamic | static"),
                "completion:interface,bridge,vlan,add,tagged" to completion("bridge1", "ether1", "ether2") + unnamed,
                "syntax:interface,bridge,vlan,add,vlan-ids" to syntax("0|VlanRange|Start[-End]", "1|Start|1..4094", "1|End|1..4094"),
                "child:system,logging" to child("self dir logging", "child cmd add", "child cmd print"),
                "child:system,logging,print" to child("self cmd print", "child arg count-only"),
                "child:system,logging,add" to child("self cmd add", "child arg topics"),
                "syntax:system,logging,add,topics" to syntax("0|Topic|[!]Topic", "1|Topic|", "2||info | warning | error | critical | debug | ..."),
                "completion:system,logging,add,topics" to completion("critical", "debug", "info") + unnamed,
                "child:ip,firewall,nat" to child("self dir nat", "child cmd add", "child cmd print"),
                "child:ip,firewall,nat,print" to child("self cmd print", "child arg count-only"),
                "child:ip,firewall,nat,add" to child("self cmd add", "child arg to-ports"),
                "syntax:ip,firewall,nat,add,to-ports" to syntax("0|Min|Num", "1|Num|0..65535    (integer number)"),
                "child:queue,simple" to child("self dir simple", "child cmd add", "child cmd print"),
                "child:queue,simple,print" to child("self cmd print", "child arg count-only"),
                "child:queue,simple,add" to child("self cmd add", "child arg target"),
                "syntax:queue,simple,add,target" to syntax(
                    "0|Target|TargetInterface | TargetAddress",
                    "1|TargetInterface|",
                    "2||lo | ether1 | bridge1 | ...",
                    "1|TargetAddress|IpAddress | Ipv6Address",
                    "2|IpAddress|A.B.C.D/M    (IP prefix)",
                    "2|Ipv6Address|IPv6/0..128    (IPv6 prefix)",
                ),
                "completion:queue,simple,add,target" to completion("bridge1", "ether1") + unnamed,
                "child:ip,dhcp-client" to child("self dir dhcp-client", "child cmd add", "child cmd print"),
                "child:ip,dhcp-client,print" to child("self cmd print", "child arg count-only"),
                "child:ip,dhcp-client,add" to child("self cmd add", "child arg default-route-tables"),
                "syntax:ip,dhcp-client,add,default-route-tables" to syntax(
                    "0|Table|TableDistance | Table",
                    "1|TableDistance|Table:Distance",
                    "2|Table|TableDefault | Table",
                    "3|TableDefault|",
                    "4||default",
                    "3|Table|",
                    "4||main",
                    "2|Distance|Num",
                    "3|Num|1..255    (integer number)",
                    "1|Table|TableDefault | Table",
                    "2|TableDefault|",
                    "3||default",
                    "2|Table|",
                    "3||main",
                ),
                "completion:ip,dhcp-client,add,default-route-tables" to completion("default", "main") + unnamed,
                "child:routing,bgp,vpls" to child("self dir vpls", "child cmd add", "child cmd print"),
                "child:routing,bgp,vpls,print" to child("self cmd print", "child arg count-only"),
                "child:routing,bgp,vpls,add" to child("self cmd add", "child arg import-route-targets"),
                "syntax:routing,bgp,vpls,add,import-route-targets" to syntax("0e|RouteTarget|Address[&RD]", "1|Address|", "1e|RD|route distinguisher"),
                "child:routing,filter,community-list" to child("self dir community-list", "child cmd add", "child cmd print"),
                "child:routing,filter,community-list,print" to child("self cmd print", "child arg count-only"),
                "child:routing,filter,community-list,add" to child("self cmd add", "child arg communities"),
                "syntax:routing,filter,community-list,add,communities" to syntax(
                    "0|Community|Value | WellKnown",
                    "1|Value|As:Number",
                    "2|As|Num",
                    "3|Num|0..65535    (integer number)",
                    "2|Number|Num",
                    "3|Num|0..65535    (integer number)",
                    "1|WellKnown|",
                    "2||accept-own | blackhole | graceful-shutdown | no-export | ...",
                ),
                "completion:routing,filter,community-list,add,communities" to completion("blackhole", "no-export") + unnamed,
                "child:routing,filter,community-ext-list" to child("self dir community-ext-list", "child cmd add", "child cmd print"),
                "child:routing,filter,community-ext-list,print" to child("self cmd print", "child arg count-only"),
                "child:routing,filter,community-ext-list,add" to child("self cmd add", "child arg communities"),
                "syntax:routing,filter,community-ext-list,add,communities" to syntax(
                    "0|Community|Typed | Raw",
                    "1|Typed|Type:Value",
                    "2|Type|",
                    "3||rt | soo",
                    "2e|Value|Address[&RD]",
                    "1|Raw|Num",
                    "2|Num|0..18446744073709551615    (integer number)",
                ),
                "completion:routing,filter,community-ext-list,add,communities" to completion("rt", "soo") + unnamed,
                "child:interface,dot1x,client" to child("self dir client", "child cmd add", "child cmd print"),
                "child:interface,dot1x,client,print" to child("self cmd print", "child arg count-only"),
                "child:interface,dot1x,client,add" to child("self cmd add", "child arg eap-methods"),
                "syntax:interface,dot1x,client,add,eap-methods" to syntax("0||"),
                "completion:interface,dot1x,client,add,eap-methods" to completion("eap-mschapv2", "eap-peap", "eap-tls", "eap-ttls"),
            ),
        )
        val schema = InspectSchema(router, MemorySchemaCache(), "7.25 x86_64 routeros")
        val tables = schema.describe(MenuPath.parse("ip/dhcp-client")).argument("default-route-tables")
        assertEquals(ValueType.Many(ValueType.Unparsed("Table:Distance")), tables?.type)
        assertEquals(listOf("default", "main"), tables?.choices)
        assertEquals(ValueType.Many(ValueType.RouteDistinguisher), schema.describe(MenuPath.parse("routing/bgp/vpls")).argument("import-route-targets")?.type)
        val communities = schema.describe(MenuPath.parse("routing/filter/community-list")).argument("communities")
        assertEquals(ValueType.Many(ValueType.Unparsed("As:Number")), communities?.type)
        assertEquals(listOf("blackhole", "no-export"), communities?.choices)
        assertEquals(ValueType.Many(ValueType.Text()), schema.describe(MenuPath.parse("routing/filter/community-ext-list")).argument("communities")?.type)
        val vlan = schema.describe(MenuPath.parse("interface/bridge/vlan"))
        assertEquals(ValueType.Many(ValueType.Enumeration()), vlan.argument("tagged")?.type)
        assertEquals(listOf("bridge1", "ether1", "ether2"), vlan.argument("tagged")?.choices)
        assertEquals(ValueType.Many(ValueType.Span(ValueType.Integer(1, 4094, scaled = true), listOf("start", "end"), open = true)), vlan.argument("vlan-ids")?.type)
        val topics = schema.describe(MenuPath.parse("system/logging")).argument("topics")
        assertEquals(ValueType.Many(ValueType.Enumeration()), topics?.type)
        assertFalse(topics?.negatable ?: true, "the ! of one topic is typed in its line")
        assertEquals(ValueType.PortList, schema.describe(MenuPath.parse("ip/firewall/nat")).argument("to-ports")?.type)
        assertEquals(ValueType.Many(ValueType.Ip), schema.describe(MenuPath.parse("queue/simple")).argument("target")?.type)
        val methods = schema.describe(MenuPath.parse("interface/dot1x/client")).argument("eap-methods")
        assertEquals(ValueType.Many(ValueType.Enumeration(closed = true)), methods?.type)
        assertEquals(listOf("eap-mschapv2", "eap-peap", "eap-tls", "eap-ttls"), methods?.choices)
    }

    @Test
    fun aVariantOfSeveralWordsComesWithoutTheQuotesOfTheConsole() = runTest {
        val router = Router(
            mapOf(
                "child:interface,wifi,configuration" to child("self dir configuration", "child cmd add", "child cmd print"),
                "child:interface,wifi,configuration,print" to child("self cmd print", "child arg count-only"),
                "child:interface,wifi,configuration,add" to child("self cmd add", "child arg country"),
                "syntax:interface,wifi,configuration,add,country" to syntax("0|Country|", "1||Oman | Togo | \"Aland Islands\" | ..."),
                "completion:interface,wifi,configuration,add,country" to completion("Afghanistan", "\"Aland Islands\"") + mapOf("completion" to "<value>", "show" to "false"),
            ),
        )
        val country = InspectSchema(router, MemorySchemaCache(), "7.25 x86_64 routeros").describe(MenuPath.parse("interface/wifi/configuration")).argument("country")
        assertEquals(ValueType.Enumeration(), country?.type)
        assertEquals(listOf("Afghanistan", "Aland Islands"), country?.choices)
    }

    @Test
    fun aFieldOfOneVariantTakesMoreThanIt() = runTest {
        val router = Router(
            mapOf(
                "child:interface,l2tp-client" to child("self dir l2tp-client", "child cmd add", "child cmd print"),
                "child:interface,l2tp-client,print" to child("self cmd print", "child arg count-only"),
                "child:interface,l2tp-client,add" to child("self cmd add", "child arg keepalive-timeout"),
                "syntax:interface,l2tp-client,add,keepalive-timeout" to syntax("0||"),
                "completion:interface,l2tp-client,add,keepalive-timeout" to completion("disabled"),
            ),
        )
        val timeout = InspectSchema(router, MemorySchemaCache(), "7.25 x86_64 routeros").describe(MenuPath.parse("interface/l2tp-client")).argument("keepalive-timeout")
        assertEquals(ValueType.Text(), timeout?.type)
        assertEquals(listOf("disabled"), timeout?.choices)
    }

    @Test
    fun spansWhoseSyntaxNamesOneEnd() = runTest {
        val router = Router(
            mapOf(
                "child:ip,firewall,filter" to child("self dir filter", "child cmd add", "child cmd print"),
                "child:ip,firewall,filter,print" to child("self cmd print"),
                "child:ip,firewall,filter,add" to child("self cmd add", "child arg connection-bytes"),
                "syntax:ip,firewall,filter,add,connection-bytes" to syntax("0|From|Num", "1|Num|0..4294967295    (integer number)"),
                "child:interface,wifi,access-list" to child("self dir access-list", "child cmd add", "child cmd print"),
                "child:interface,wifi,access-list,print" to child("self cmd print"),
                "child:interface,wifi,access-list,add" to child("self cmd add", "child arg signal-range"),
                "syntax:interface,wifi,access-list,add,signal-range" to syntax("0|Min|[-]Num", "1|Num|-120..120    (integer number)"),
            ),
        )
        val schema = InspectSchema(router, MemorySchemaCache(), "7.25 x86_64 routeros")
        val bytes = schema.describe(MenuPath.parse("ip/firewall/filter")).argument("connection-bytes")?.type
        assertEquals(ValueType.Span(ValueType.Integer(0, 4294967295, scaled = true), listOf("from", "to"), open = true), bytes)
        val signal = schema.describe(MenuPath.parse("interface/wifi/access-list")).argument("signal-range")?.type
        assertEquals(ValueType.Span(ValueType.Integer(-120, 120, scaled = true), listOf("min", "max"), open = true, separator = ".."), signal)
    }

    @Test
    fun theTtlOfMangleIsAModeAndANumber() = runTest {
        val router = Router(
            mapOf(
                "child:ip,firewall,mangle" to child("self dir mangle", "child cmd add", "child cmd print"),
                "child:ip,firewall,mangle,print" to child("self cmd print"),
                "child:ip,firewall,mangle,add" to child("self cmd add", "child arg new-ttl"),
                "syntax:ip,firewall,mangle,add,new-ttl" to syntax("0||"),
                "completion:ip,firewall,mangle,add,new-ttl" to completion("decrement", "increment", "set"),
            ),
        )
        val ttl = InspectSchema(router, MemorySchemaCache(), "7.25.1 x86_64 routeros").describe(MenuPath.parse("ip/firewall/mangle")).argument("new-ttl")?.type
        assertEquals(ValueType.Adjustment(listOf("set", "increment", "decrement"), ValueType.Integer(0, 255)), ttl)
    }

    @Test
    fun theDaysOfKidControlAreWindowsOfTheDay() = runTest {
        val router = Router(
            mapOf(
                "child:ip,kid-control" to child("self dir kid-control", "child cmd add", "child cmd print"),
                "child:ip,kid-control,print" to child("self cmd print", "child arg count-only"),
                "child:ip,kid-control,add" to child("self cmd add", "child arg mon", "child arg tur-mon"),
                "syntax:ip,kid-control,add,mon" to syntax("0e|Start|0s..1d    (time interval)"),
                "syntax:ip,kid-control,add,tur-mon" to syntax("0e|Start|0s..1d    (time interval)"),
            ),
        )
        val schema = InspectSchema(router, MemorySchemaCache(), "7.25 x86_64 routeros").describe(MenuPath.parse("ip/kid-control"))
        assertEquals(List(2) { ValueType.TimeWindow(emptyList()) }, schema.arguments.map { it.type })
    }

    @Test
    fun theAddressOfAnIpAddressTakesAPrefix() = runTest {
        val address = syntax("0|Address|A.B.C.D    (IP address)")
        val router = Router(
            listOf("ip,address", "ip,arp").flatMap { path ->
                listOf(
                    "child:$path" to child("self dir ${path.substringAfter(',')}", "child cmd add", "child cmd print"),
                    "child:$path,print" to child("self cmd print", "child arg count-only"),
                    "child:$path,add" to child("self cmd add", "child arg address"),
                    "syntax:$path,add,address" to address,
                )
            }.toMap(),
        )
        val schema = InspectSchema(router, MemorySchemaCache(), "7.25 x86_64 routeros")
        assertEquals(ValueType.Ip4Prefix, schema.describe(MenuPath.parse("ip/address")).argument("address")?.type)
        assertEquals(ValueType.Ip4, schema.describe(MenuPath.parse("ip/arp")).argument("address")?.type)
    }

    @Test
    fun theFieldsThatNameAnAddressListOfferTheLists() = runTest {
        val name = syntax("0|List|", "1||", "1e||string value")
        val lists = completion("rd-test-list") + mapOf("completion" to "<value>", "show" to "false")
        val router = Router(
            mapOf(
                "child:ip,firewall,address-list" to child("self dir address-list", "child cmd add", "child cmd print"),
                "child:ip,firewall,address-list,print" to child("self cmd print", "child arg count-only"),
                "child:ip,firewall,address-list,add" to child("self cmd add", "child arg list", "child arg comment"),
                "syntax:ip,firewall,address-list,add,list" to name,
                "syntax:ip,firewall,address-list,add,comment" to syntax("0|Comment|string value"),
                "completion:ip,firewall,address-list,add,list" to lists,
                "child:ip,firewall,filter" to child("self dir filter", "child cmd add", "child cmd print"),
                "child:ip,firewall,filter,print" to child("self cmd print", "child arg count-only"),
                "child:ip,firewall,filter,add" to child("self cmd add", "child arg src-address-list"),
                "syntax:ip,firewall,filter,add,src-address-list" to syntax("0|SrcAddressList|[!]AddressList", "1|AddressList|", "2||", "2e||string value"),
                "completion:ip,firewall,filter,add,src-address-list" to lists,
            ),
        )
        val schema = InspectSchema(router, MemorySchemaCache(), "7.25 x86_64 routeros")
        val entries = schema.describe(MenuPath.parse("ip/firewall/address-list"))
        assertEquals(listOf("rd-test-list"), entries.argument("list")?.choices)
        assertEquals(emptyList(), entries.argument("comment")?.choices)
        val rule = schema.describe(MenuPath.parse("ip/firewall/filter")).argument("src-address-list")
        assertEquals(listOf("rd-test-list"), rule?.choices)
        assertTrue(rule?.negatable == true)
    }

    @Test
    fun theIcmpOptionsOfIpv6AreOfIcmpv6() = runTest {
        val icmp = syntax("0|IcmpOptions|[!]Type[:Code]", "1|Type|Num", "2|Num|0..255    (integer number)", "1|Code|Start[-End]", "2|Start|0..255", "2|End|0..255")
        val router = Router(
            listOf("ip", "ipv6").flatMap { family ->
                listOf(
                    "child:$family,firewall,filter" to child("self dir filter", "child cmd add", "child cmd print"),
                    "child:$family,firewall,filter,print" to child("self cmd print", "child arg count-only"),
                    "child:$family,firewall,filter,add" to child("self cmd add", "child arg icmp-options"),
                    "syntax:$family,firewall,filter,add,icmp-options" to icmp,
                )
            }.toMap(),
        )
        val schema = InspectSchema(router, MemorySchemaCache(), "7.25 x86_64 routeros")
        val options = schema.describe(MenuPath.parse("ip/firewall/filter")).argument("icmp-options")
        assertEquals(ValueType.Icmp(), options?.type)
        assertTrue(options?.negatable == true)
        assertEquals(ValueType.Icmp(six = true), schema.describe(MenuPath.parse("ipv6/firewall/filter")).argument("icmp-options")?.type)
    }

    @Test
    fun settingsAndDirectories() = runTest {
        val router = Router(
            mapOf(
                "child:ip,dns" to child("self dir dns", "child dir static", "child cmd print", "child cmd set"),
                "child:ip,dns,print" to child("self cmd print", "child arg interval"),
                "child:ip,dns,set" to child("self cmd set", "child arg cache-size"),
                "syntax:ip,dns,set,cache-size" to syntax("0|CacheSize|Num[KiB]", "1|Num|64..4294967295    (integer number)"),
                "child:ip,firewall" to child("self dir firewall", "child dir filter", "child path nat", "child cmd export"),
                "child:snmp" to child("self dir snmp", "child dir community", "child cmd print", "child cmd set"),
                "child:snmp,print" to child("self cmd print"),
                "child:snmp,set" to child("self cmd set", "child arg contact", "child arg enabled"),
                "syntax:snmp,set,contact" to syntax("0|Contact|string value"),
                "syntax:snmp,set,enabled" to syntax("0||"),
                "completion:snmp,set,enabled" to completion("no", "yes"),
            ),
        )
        val schema = InspectSchema(router, MemorySchemaCache(), "7.26 x86_64 routeros")
        val dns = schema.describe(MenuPath.parse("ip/dns"))
        assertEquals(NodeKind.Settings, dns.kind)
        assertEquals(listOf("static"), dns.children)
        assertEquals(ValueType.Integer(64, 4294967295, "KiB", scaled = true), dns.argument("cache-size")?.type)
        val firewall = schema.describe(MenuPath.parse("ip/firewall"))
        assertEquals(NodeKind.Directory, firewall.kind)
        assertEquals(listOf("filter", "nat"), firewall.children)
        assertEquals(listOf("enabled", "contact"), schema.describe(MenuPath.parse("snmp")).arguments.map { it.name })
    }

    @Test
    fun whatTheRouterLeavesToTheDocumentation() = runTest {
        val router = Router(
            mapOf(
                "child:ipv6,address" to child("self dir address", "child cmd add", "child cmd print"),
                "child:ipv6,address,print" to child("self cmd print"),
                "child:ipv6,address,add" to child("self cmd add", "child arg address"),
                "syntax:ipv6,address,add,address" to syntax("0|Address|see documentation"),
                "child:system,clock,manual" to child("self dir manual", "child cmd print", "child cmd set"),
                "child:system,clock,manual,print" to child("self cmd print"),
                "child:system,clock,manual,set" to child("self cmd set", "child arg time-zone"),
                "syntax:system,clock,manual,set,time-zone" to syntax("0|TimeZone|see documentation"),
                "child:interface,gre6" to child("self dir gre6", "child cmd add", "child cmd print"),
                "child:interface,gre6,print" to child("self cmd print", "child arg count-only"),
                "child:interface,gre6,add" to child("self cmd add", "child arg local-address"),
                "syntax:interface,gre6,add,local-address" to syntax("0|LocalAddress|see documentation"),
            ),
        )
        val schema = InspectSchema(router, MemorySchemaCache(), "7.25 x86_64 routeros")
        assertEquals(ValueType.Ip6Prefix, schema.describe(MenuPath.parse("ipv6/address")).argument("address")?.type)
        assertEquals(ValueType.Offset, schema.describe(MenuPath.parse("system/clock/manual")).argument("time-zone")?.type)
        assertEquals(ValueType.Ip6Prefix, schema.describe(MenuPath.parse("interface/gre6")).argument("local-address")?.type)
    }

    @Test
    fun anOutlineAsksTheMenuAndItsPrintAndNoField() = runTest {
        val router = Router(vlan)
        val outline = InspectSchema(router, MemorySchemaCache(), "7.26 x86_64 routeros").outline(MenuPath.parse("interface/vlan"))
        assertEquals(NodeKind.List, outline.kind)
        assertEquals(listOf("add", "print", "remove", "set", "unset"), outline.commands)
        assertTrue(outline.arguments.isEmpty())
        assertEquals(listOf("child:interface,vlan", "child:interface,vlan,print"), router.asked.sorted())
    }

    @Test
    fun theNamesOfLiveObjectsAreAskedOnceForSeveralDescribesAndAgainAfterAChange() = runTest {
        val router = Router(vlan)
        val schema = InspectSchema(router, MemorySchemaCache(), "7.26 x86_64 routeros")
        val path = MenuPath.parse("interface/vlan")
        schema.describe(path)
        schema.describe(path)
        assertEquals(1, router.asked.count { it == "completion:interface,vlan,add,interface" })
        schema.forgetNames()
        schema.describe(path)
        assertEquals(2, router.asked.count { it == "completion:interface,vlan,add,interface" })
        assertEquals(1, router.asked.count { it == "syntax:interface,vlan,add,name" })
    }

    @Test
    fun forgettingTheStructureAsksTheRouterForEverythingAgain() = runTest {
        val cache = MemorySchemaCache()
        val router = Router(vlan)
        val schema = InspectSchema(router, cache, "A1B2 7.26 x86_64 routeros")
        val path = MenuPath.parse("interface/vlan")
        schema.describe(path)
        val first = router.asked.size
        schema.describe(path)
        assertEquals(first, router.asked.size)
        schema.forget()
        assertEquals(emptyMap(), cache.load("A1B2 7.26 x86_64 routeros"))
        schema.describe(path)
        assertEquals(first * 2, router.asked.size)
    }

    @Test
    fun describesAtOnceShareTheirRequests() = runTest {
        val router = Router(vlan, takes = 5)
        val schema = InspectSchema(router, MemorySchemaCache(), "7.26 x86_64 routeros")
        val path = MenuPath.parse("interface/vlan")
        List(4) { async { schema.describe(path) } }.awaitAll()
        assertEquals(router.asked.size, router.asked.toSet().size, router.asked.toString())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theAnswersAreSavedOnceTheyStopComing() = runTest {
        val saved = mutableListOf<Int>()
        val cache = object : SchemaCache {
            override suspend fun load(build: String) = emptyMap<String, List<Map<String, String>>>()

            override suspend fun store(build: String, answers: Map<String, List<Map<String, String>>>) {
                saved += answers.size
            }

            override suspend fun forget(build: String) {}
        }
        val schema = InspectSchema(Router(vlan), cache, "7.26 x86_64 routeros", backgroundScope)
        schema.describe(MenuPath.parse("interface/vlan"))
        runCurrent()
        assertTrue(saved.isEmpty(), "nothing is written while the window waits")
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(1, saved.size)
        schema.describe(MenuPath.parse("interface/vlan"))
        schema.flush()
        assertEquals(1, saved.size, "nothing new to write")
    }
}
