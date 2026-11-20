package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxProxyRoutingTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private fun conf(file: String, version: String, config: String) =
        "PATH=\"\$PATH:/usr/local/bin\"\nc=\nfor f in $file" to ok("--nh-json-- active enabled $file\n--nh-json-version-- $version\n--nh-json-conf--\n$config\n")

    private fun host(vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf("for t in" to ok("sing-box\nxray\nsystemctl\n"), "id -u" to ok("0\n"), "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "systemctl" to ok()),
    )

    private fun written(host: VpnHost) = Json.parseToJsonElement(host.given.single()).jsonObject

    private fun JsonObject.at(vararg keys: String): String = keys.dropLast(1).fold(this) { json, key -> json.getValue(key).jsonObject }.getValue(keys.last()).jsonPrimitive.content

    private fun rules(kind: String) = MenuPath.parse("proxy/$kind/rule")

    private fun outbounds(kind: String) = MenuPath.parse("proxy/$kind/outbound")

    // a direct outbound and a server of Trojan dialed through it, which route.final makes the default, and rules of sing-box 1.11 and newer:
    // sniffing, one of a domain, a keyword, a rule set of geosite, one of its own and private addresses, DNS
    private val singBox = """
        {
          "inbounds": [ { "type": "vless", "tag": "vless-in", "listen": "::", "listen_port": 443, "users": [ { "name": "alice", "uuid": "11111111-2222-3333-4444-555555555555" } ] } ],
          "outbounds": [
            { "type": "direct", "tag": "direct" },
            { "type": "trojan", "tag": "proxy", "server": "203.0.113.7", "server_port": 443, "password": "rd-test-trojan", "detour": "direct" }
          ],
          "route": {
            "rules": [
              { "action": "sniff" },
              { "domain_suffix": [ "example.com" ], "domain_keyword": "ads", "rule_set": [ "geosite-category-ads-all", "my-list" ], "ip_is_private": true, "inbound": "vless-in", "outbound": "direct" },
              { "port": 53, "action": "hijack-dns" }
            ],
            "rule_set": [ { "type": "local", "tag": "my-list", "format": "binary", "path": "/etc/sing-box/my.srs" } ],
            "final": "proxy"
          }
        }
    """.trimIndent()

    // a rule of Xray by its documentation: a domain without a prefix matches as a part of the name
    private val xray = """
        {
          "inbounds": [ { "tag": "vless-in", "protocol": "vless", "port": 443, "settings": { "clients": [ { "id": "11111111-2222-3333-4444-555555555555", "email": "alice" } ], "decryption": "none" } } ],
          "outbounds": [ { "protocol": "freedom", "tag": "direct" } ],
          "routing": { "rules": [ { "type": "field", "domain": [ "example.com", "domain:example.org", "geosite:google" ], "ip": [ "geoip:private", "1.1.1.1/32" ], "inboundTag": [ "vless-in" ], "outboundTag": "direct" } ] }
        }
    """.trimIndent()

    private val singBoxFile = "/etc/sing-box/config.json"
    private val xrayFile = "/usr/local/etc/xray/config.json"

    @Test
    fun theOutboundsShowTheirServerChainAndDefaultAndTheRulesTheirConditions() = runTest {
        val session = SshHostSession(host(conf(singBoxFile, "sing-box version 1.14.4", singBox)), device, linux = true)
        assertEquals(
            listOf(listOf("direct", "direct", "", "", "false"), listOf("proxy", "trojan", "203.0.113.7:443", "direct", "true")),
            session.rows("proxy/sing-box/outbound").map { row -> listOf("tag", "type", "server", "through", "default").map { row[it] } },
        )
        val shown = session.rows("proxy/sing-box/rule")
        assertEquals(listOf("sniff", "direct", "hijack-dns"), shown.map { it["outbound"] })
        assertEquals(
            mapOf("domain" to "example.com, keyword:ads", "geosite" to "category-ads-all", "geoip" to "private", "inbound" to "vless-in", "more" to "rule_set", "outbound" to "direct"),
            shown[1].values,
        )
        assertEquals("port", shown[2]["more"])
        val rule = SshHostSession(host(conf(xrayFile, "Xray 26.11.9", xray)), device, linux = true).rows("proxy/xray/rule").single()
        assertEquals(
            mapOf("domain" to "keyword:example.com, example.org", "geosite" to "google", "geoip" to "private", "ip" to "1.1.1.1/32", "inbound" to "vless-in", "outbound" to "direct"),
            rule.values,
        )
    }

    @Test
    fun aRuleOfSingBoxGoesToFieldsOfTheirKindAndNamesTheRuleSetsOfSagerNet() = runTest {
        val host = host(conf(singBoxFile, "sing-box version 1.14.4", singBox))
        val values = mapOf(
            "domain" to "example.org, full:www.example.net, regexp:^ads[.]", "geosite" to "category-ads-all", "geoip" to "cn, private", "ip" to "10.0.0.0/8",
            "inbound" to "vless-in", "outbound" to "proxy",
        )
        assertEquals("4", SshHostSession(host, device, linux = true).add(rules("sing-box"), values))
        val route = written(host).getValue("route").jsonObject
        val rule = route.getValue("rules").jsonArray[3].jsonObject
        assertEquals(
            mapOf(
                "domain_suffix" to listOf("example.org"), "domain" to listOf("www.example.net"), "domain_regex" to listOf("^ads[.]"),
                "rule_set" to listOf("geosite-category-ads-all", "geoip-cn"), "ip_cidr" to listOf("10.0.0.0/8"), "inbound" to listOf("vless-in"),
            ),
            rule.filterValues { it is JsonArray }.mapValues { (_, value) -> value.jsonArray.map { it.jsonPrimitive.content } },
        )
        assertEquals(listOf("true", "route", "proxy"), listOf(rule.at("ip_is_private"), rule.at("action"), rule.at("outbound")))
        val sets = route.getValue("rule_set").jsonArray.map { it.jsonObject }
        assertEquals(listOf("my-list", "geosite-category-ads-all", "geoip-cn"), sets.map { it.at("tag") })
        assertEquals(listOf("remote", "binary"), listOf(sets[1].at("type"), sets[1].at("format")))
        assertEquals("https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs", sets[1].at("url"))
        assertEquals("https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs", sets[2].at("url"))
        assertTrue(host.ran.any { it.startsWith("umask 077; mkdir -p") && "sing-box check -c '/etc/sing-box/config.json'" in it }, "${host.ran}")
    }

    @Test
    fun blockOfSingBoxIsTheActionRejectAndBlockAdsAddsItsRuleOnce() = runTest {
        val host = host(conf(singBoxFile, "sing-box version 1.14.4", """{ "inbounds": [], "outbounds": [ { "type": "direct", "tag": "direct" } ] }"""))
        SshHostSession(host, device, linux = true).run(rules("sing-box"), "block-ads", emptyMap()).toList()
        val config = written(host)
        val rule = config.getValue("route").jsonObject.getValue("rules").jsonArray.single().jsonObject
        assertEquals(listOf("geosite-category-ads-all"), rule.getValue("rule_set").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("reject", rule.at("action"))
        assertTrue("outbound" !in rule, rule.toString())
        assertEquals(listOf("geosite-category-ads-all"), config.getValue("route").jsonObject.getValue("rule_set").jsonArray.map { it.jsonObject.at("tag") })
        val again = host(conf(singBoxFile, "sing-box version 1.14.4", config.toString()))
        SshHostSession(again, device, linux = true).run(rules("sing-box"), "block-ads", emptyMap()).toList()
        assertTrue(again.given.isEmpty(), "${again.given}")
    }

    @Test
    fun blockOfXrayIsABlackholeOutboundMadeWhenARuleFirstNamesIt() = runTest {
        val host = host(conf(xrayFile, "Xray 26.11.9", """{ "inbounds": [ { "tag": "vless-in", "protocol": "vless", "port": 443 } ] }"""))
        SshHostSession(host, device, linux = true).add(rules("xray"), mapOf("domain" to "example.com, keyword:ads, full:x.example.com", "geosite" to "category-ads-all"))
        val config = written(host)
        assertEquals("""[{"protocol":"freedom","tag":"direct"},{"protocol":"blackhole","tag":"block"}]""", config.getValue("outbounds").toString())
        val rule = config.getValue("routing").jsonObject.getValue("rules").jsonArray.single().jsonObject
        assertEquals(listOf("domain:example.com", "keyword:ads", "full:x.example.com", "geosite:category-ads-all"), rule.getValue("domain").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("field", "block"), listOf(rule.at("type"), rule.at("outboundTag")))
    }

    @Test
    fun aDomainOfXrayWithoutAPrefixKeepsItsMatchWhenAnotherFieldOfItsRuleChanges() = runTest {
        val host = host(conf(xrayFile, "Xray 26.11.9", xray))
        SshHostSession(host, device, linux = true).set(rules("xray"), "1", mapOf("geosite" to "netflix", "geoip" to null))
        val rule = written(host).getValue("routing").jsonObject.getValue("rules").jsonArray.single().jsonObject
        assertEquals(listOf("keyword:example.com", "domain:example.org", "geosite:netflix"), rule.getValue("domain").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("1.1.1.1/32"), rule.getValue("ip").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("vless-in"), rule.getValue("inboundTag").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun anOutboundOfALinkDialsThroughAnotherAndMakeDefaultPutsAnOutboundFirst() = runTest {
        val host = host(conf(xrayFile, "Xray 26.11.9", xray))
        val link = "trojan://rd-test-pass@203.0.113.7:443?security=tls&sni=proxy.example.net#chain"
        assertEquals("2", SshHostSession(host, device, linux = true).add(outbounds("xray"), mapOf("kind" to "link", "link" to link, "through" to "direct")))
        val proxy = written(host).getValue("outbounds").jsonArray[1].jsonObject
        val server = proxy.getValue("settings").jsonObject.getValue("servers").jsonArray.single().jsonObject
        assertEquals(
            listOf("proxy", "trojan", "203.0.113.7", "rd-test-pass", "direct", "proxy.example.net"),
            listOf(proxy.at("tag"), proxy.at("protocol"), server.at("address"), server.at("password"), proxy.at("streamSettings", "sockopt", "dialerProxy"), proxy.at("streamSettings", "tlsSettings", "serverName")),
        )
        assertTrue(host.sent.none { "rd-test-pass" in it }, "the password goes to the host as input alone: ${host.sent}")
        val other = host(conf(singBoxFile, "sing-box version 1.14.4", singBox))
        SshHostSession(other, device, linux = true).run(outbounds("sing-box"), "default", mapOf("number" to "1")).toList()
        val config = written(other)
        assertEquals(listOf("direct", "proxy"), config.getValue("outbounds").jsonArray.map { it.jsonObject.at("tag") })
        assertTrue("final" !in config.getValue("route").jsonObject, config.toString())
        val moved = host(conf(xrayFile, "Xray 26.11.9", written(host).toString()))
        SshHostSession(moved, device, linux = true).run(outbounds("xray"), "default", mapOf("number" to "2")).toList()
        assertEquals(listOf("proxy", "direct"), written(moved).getValue("outbounds").jsonArray.map { it.jsonObject.at("tag") })
    }

    @Test
    fun anOutboundARuleAChainOrTheDefaultStillNamesStays() = runTest {
        val host = host(conf(singBoxFile, "sing-box version 1.14.4", singBox))
        val session = SshHostSession(host, device, linux = true)
        for ((id, tag) in listOf("1" to "direct", "2" to "proxy")) {
            val error = assertFailsWith<DeviceException> { session.remove(outbounds("sing-box"), listOf(id)) }
            assertEquals("the outbound $tag is still named in the config: a rule, a chain or DNS goes through it", error.message)
        }
        assertEquals("sing-box has no outbound 3", assertFailsWith<DeviceException> { session.remove(outbounds("sing-box"), listOf("3")) }.message)
        assertTrue(host.given.isEmpty(), "${host.given}")
    }

    @Test
    fun anOutboundRefusesATakenTagABlockOfSingBoxAndALinkOfRealityOfXrayForASingBoxThatDropsMlkem() = runTest {
        val host = host(conf(singBoxFile, "sing-box version 1.14.4", singBox), "sing-box version" to ok("sing-box version 1.14.4\n"))
        val session = SshHostSession(host, device, linux = true)
        assertEquals("sing-box has an outbound proxy already", assertFailsWith<DeviceException> { session.add(outbounds("sing-box"), mapOf("kind" to "direct", "tag" to "proxy")) }.message)
        assertEquals("kind: block is not a value it takes", assertFailsWith<DeviceException> { session.add(outbounds("sing-box"), mapOf("kind" to "block")) }.message)
        val reality = "vless://11111111-2222-3333-4444-555555555555@203.0.113.7:443?encryption=none&security=reality&sni=dl.google.com&fp=chrome" +
            "&pbk=${"A".repeat(43)}&sid=0123&spx=%2F&type=tcp#x"
        assertEquals(
            "sing-box 1.14.4 leaves X25519MLKEM768 out of its Client Hello of Reality, and servers of Xray refuse such a client: make this outbound in Xray",
            assertFailsWith<DeviceException> { session.add(outbounds("sing-box"), mapOf("link" to reality)) }.message,
        )
        assertTrue(host.given.isEmpty() && host.sent.none { "AAAAAAAA" in it }, "${host.sent}")
    }

    @Test
    fun aRuleRefusesWhatItCannotTakeBeforeTheFileChanges() = runTest {
        val host = host(conf(singBoxFile, "sing-box version 1.14.4", singBox))
        val session = SshHostSession(host, device, linux = true)
        val refusals = listOf(
            mapOf("domain" to "example.org", "outbound" to "nowhere") to "outbound: nowhere is not a value it takes",
            mapOf("outbound" to "proxy") to "a rule needs a domain, a geosite, a geoip, an ip or an inbound",
            mapOf("geosite" to "ads list") to "geosite: ads list is not a value it takes",
            mapOf("domain" to "ext:geosite.dat:ads") to "domain: ext:geosite.dat:ads is not a value it takes",
            mapOf("ip" to "example.com") to "ip: example.com is not a value it takes",
            mapOf("inbound" to "socks-in", "domain" to "example.org") to "inbound: socks-in is not a value it takes",
        )
        for ((values, message) in refusals) assertEquals(message, assertFailsWith<DeviceException> { session.add(rules("sing-box"), values) }.message)
        assertEquals("sing-box has no rule 9", assertFailsWith<DeviceException> { session.remove(rules("sing-box"), listOf("9")) }.message)
        assertTrue(host.given.isEmpty(), "${host.given}")
    }

    @Test
    fun rulesMoveInTheOrderTheProgramReadsThem() = runTest {
        val host = host(conf(singBoxFile, "sing-box version 1.14.4", singBox))
        SshHostSession(host, device, linux = true).move(rules("sing-box"), listOf("3"), "1")
        val order = written(host).getValue("route").jsonObject.getValue("rules").jsonArray.map { it.jsonObject["action"]?.jsonPrimitive?.content }
        assertEquals(listOf("hijack-dns", "sniff", null), order)
    }

    @Test
    fun aRenamedInboundStaysInTheRulesThatNameIt() = runTest {
        val host = host(conf(singBoxFile, "sing-box version 1.14.4", singBox))
        SshHostSession(host, device, linux = true).set(MenuPath.parse("proxy/sing-box/inbound"), "1", mapOf("tag" to "vless-main"))
        val config = written(host)
        assertEquals("vless-main", config.getValue("inbounds").jsonArray.single().jsonObject.at("tag"))
        val rule = config.getValue("route").jsonObject.getValue("rules").jsonArray[1].jsonObject
        assertEquals(listOf("vless-main"), rule.getValue("inbound").jsonArray.map { it.jsonPrimitive.content })
    }
}
