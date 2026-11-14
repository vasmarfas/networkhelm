package com.vasmarfas.networkhelm.packs

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PackTest {

    @Test
    fun packReadsWithDefaultsAndRefusesUnknownKeys() {
        val pack = parsePack("""{"menu": ["IP", "Pool"], "window": {"title": "IP Pool", "tabs": [{"title": "Pools", "path": "ip/pool"}]}}""")
        assertEquals("routeros", pack.platform)
        assertEquals("ip/pool", pack.window.tabs.single().path)
        assertFailsWith<SerializationException> {
            parsePack("""{"menu": ["IP"], "window": {"title": "IP", "tabs": []}, "colums": []}""")
        }
    }

    @Test
    fun theTablesOfDnsSettingsBecomeTabsAndItsOtherButtonsStay() {
        fun pack(vararg menu: String) = bundledPacks().values.single { it.platform == "routeros" && it.menu == menu.toList() }
        val dns = pack("IP", "DNS").withTablesAsTabs()
        assertEquals(listOf("ip/dns", "ip/dns/static", "ip/dns/cache", "ip/dns/adlist", "ip/dns/forwarders"), dns.window.tabs.map { it.path })
        assertEquals(listOf("DNS Settings", "Static", "Cache", "Adlist", "Forwarders"), dns.window.tabs.map { it.title })
        val ssh = pack("IP", "SSH")
        assertSame(ssh, ssh.withTablesAsTabs())
        val dhcp = pack("IP", "DHCP Server")
        assertSame(dhcp, dhcp.withTablesAsTabs())
    }

    @Test
    fun theTablesOfDnsShareAWindowAndALoneTableKeepsItsOwn() {
        fun pack(vararg menu: String) = bundledPacks().values.single { it.platform == "routeros" && it.menu == menu.toList() }
        val tables = assertNotNull(pack("IP", "DNS").tablesWindow())
        assertEquals("DNS", tables.window.title)
        assertEquals(listOf("Static", "Cache", "Adlist", "Forwarders"), tables.window.tabs.map { it.title })
        assertTrue(tables.hidden)
        assertNull(pack("IP", "SNMP").tablesWindow())
        assertNull(pack("IP", "SSH").tablesWindow())
    }

    @Test
    fun anActionTakesTheRowsAsItsCommandDoes() {
        val rows = listOf("*1", "*2")
        assertEquals(listOf(mapOf("numbers" to "*1,*2")), PackAction("Reset Counters", "reset-counters", selection = true).runs(rows))
        assertEquals(listOf(mapOf("number" to "*1"), mapOf("number" to "*2")), PackAction("Run Script", "run", selection = true, argument = "number").runs(rows))
        assertEquals(listOf(emptyMap()), PackAction("Reset All Counters", "reset-counters-all").runs(rows))
    }

    @Test
    fun aConditionHoldsWithItsValues() {
        val doh = PackCondition(listOf("doh-timeout"), mapOf("use-doh-server" to listOf("*")), hide = true)
        assertEquals(false, doh.holds { null })
        assertEquals(false, doh.holds { "" })
        assertEquals(true, doh.holds { "https://192.0.2.1/dns-query" })
        val address = PackCondition(listOf("address"), mapOf("type" to listOf("", "A")))
        assertEquals(true, address.holds { null })
        assertEquals(true, address.holds { "a" })
        assertEquals(false, address.holds { "CNAME" })
        val mac = PackCondition(listOf("mac-auth-mode"), mapOf("auth-types" to listOf("mac-auth")))
        assertEquals(true, mac.holds { "dot1x,mac-auth" })
        assertEquals(false, mac.holds { "dot1x" })
        val form = PackForm(tabs = emptyList(), only = listOf(doh, address))
        assertEquals(true, form.only.hides("doh-timeout") { null })
        assertEquals(false, form.only.hides("address") { "CNAME" }, "a condition without hide only waits")
        assertEquals(listOf(address), form.gates { it != "use-doh-server" })
    }

    @Test
    fun aRuleShowsTheFieldsOfItsActionOnly() {
        val rule = bundledPacks().getValue("firewall.json").forms.getValue("rule")
        val tab = rule.tabs.single { it.title == "Action" }
        fun shown(action: String) = tab.properties.filterNot { field -> rule.only.hides(field) { mapOf("action" to action)[it] } }
        assertEquals(listOf("action", "log", "log-prefix"), shown("accept"))
        assertEquals(listOf("action", "log", "log-prefix", "new-packet-mark", "passthrough"), shown("mark-packet"))
        assertEquals(listOf("action", "log", "log-prefix", "route-dst", "passthrough"), shown("route"))
        assertEquals(listOf("action", "log", "log-prefix", "sniff-target", "sniff-target-port", "sniff-id"), shown("sniff-pc"))
        assertEquals(listOf("action", "log", "log-prefix", "socksify-service", "socks5-server", "socks5-port"), shown("socksify"))
        assertEquals(listOf("action", "log", "log-prefix", "to-addresses", "to-ports", "randomise-ports"), shown("endpoint-independent-nat"))
    }

    @Test
    fun aBridgeShowsTheFieldsOfVlanFilteringOnceItIsOn() {
        val bridge = bundledPacks().getValue("bridge.json").forms.getValue("bridge")
        val vlan = bridge.tabs.single { it.title == "VLAN" }
        fun shown(filtering: String) = vlan.properties.filterNot { field -> bridge.only.hides(field) { mapOf("vlan-filtering" to filtering)[it] } }
        assertEquals(listOf("vlan-filtering"), shown("false"))
        assertEquals(listOf("vlan-filtering", "ether-type", "pvid", "frame-types", "ingress-filtering", "mvrp"), shown("true"))
    }

    @Test
    fun theLogsOfRouterOsOpenWrtAndHostsOverSshAreFlaggedAsLogs() {
        val logs = bundledPacks().values.flatMap { pack -> pack.paths.filterValues { it.log }.keys.map { pack.platform to it } }
        assertEquals(listOf("openwrt" to "status/kernel-log", "openwrt" to "status/log", "routeros" to "log", "ssh" to "log"), logs.sortedWith(compareBy({ it.first }, { it.second })))
    }
}
