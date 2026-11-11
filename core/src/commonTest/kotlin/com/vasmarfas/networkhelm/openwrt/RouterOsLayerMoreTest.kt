package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterOsLayerMoreTest {

    @Test
    fun anIpv6NetworkIsWrittenShort() {
        assertEquals("fd61:198f:1f96::/48", network6("fd61:198f:1f96::1", 48))
        assertEquals("2001:db8:0:1::/64", network6("2001:db8:0:1:aaaa:bbbb:cccc:dddd", 64))
        assertEquals("2001:db8::/32", network6("2001:db8::1", 32))
        assertEquals("::/0", network6("fe80::1", 0))
        assertNull(network6("no address", 64))
    }

    @Test
    fun aHostWithADuidIsAStaticBindingAndALeaseADynamicOne() = runTest {
        val base = Lists(
            "dhcp/host" to listOf(
                Item("kitchen", mapOf("duid" to "00040102", "hostid" to "123", "name" to "kitchen")),
                Item("tv", mapOf("mac" to "aa:bb:cc:00:00:01", "ip" to "192.0.2.5")),
            ),
            "status/dhcp6-leases" to listOf(
                Item("lan 00040102 1", mapOf("interface" to "lan", "duid" to "00040102", "iaid" to "1", "address" to "fd00::123")),
                Item("lan 0004ffff 2", mapOf("interface" to "lan", "duid" to "0004ffff", "iaid" to "2", "address" to "fd00::1234", "life-time" to "11h", "host-name" to "nas")),
            ),
        )
        val layer = RouterOsLayer(base)
        val bindings = MenuPath.parse("ipv6/dhcp-server/binding")
        val kitchen = assertNotNull(layer.get(bindings, "kitchen", null))
        assertEquals(listOf("123", "00040102", "kitchen", null), listOf(kitchen["address"], kitchen["duid"], kitchen["comment"], kitchen["dynamic"]))
        val nas = assertNotNull(layer.get(bindings, "dynamic lan 0004ffff 2", null))
        assertEquals(listOf("fd00::1234", "lan", "11h", "nas", "true"), listOf(nas["address"], nas["server"], nas["life-time"], nas["comment"], nas["dynamic"]))
        assertNull(layer.get(bindings, "dynamic lan 00040102 1", null))
        assertEquals(1, layer.count(MenuPath.parse("ip/dhcp-server/lease"), null))
        layer.add(bindings, mapOf("duid" to "0004aaaa", "address" to "77", "comment" to "printer"))
        assertEquals("dhcp/host" to mapOf("duid" to "0004aaaa", "hostid" to "77", "name" to "printer"), base.added.single())
        layer.set(bindings, "kitchen", mapOf("comment" to "pantry"))
        assertEquals(Triple("dhcp/host", "kitchen", mapOf<String, String?>("name" to "pantry")), base.changed.single())
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.remove(bindings, listOf("dynamic lan 0004ffff 2")) }.category)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.add(bindings, mapOf("address" to "77")) }.category)
        layer.run(bindings, "make-static", mapOf("numbers" to "dynamic lan 0004ffff 2")).collect()
        assertEquals("dhcp/host" to mapOf("duid" to "0004ffff", "hostid" to "1234", "name" to "nas"), base.added.last())
    }

    @Test
    fun theAdvertisedPrefixesAreThoseOfTheInterfacesThatSendThem() = runTest {
        val base = Lists(
            "status/prefixes" to listOf(
                Item("lan fd61:198f:1f96::/64", mapOf("prefix" to "fd61:198f:1f96::/64", "interface" to "lan", "valid-lifetime" to "1w", "preferred-lifetime" to "1d", "source" to "assigned")),
                Item("lan fd61:198f:1f96::/60", mapOf("prefix" to "fd61:198f:1f96::/60", "interface" to "lan", "source" to "address")),
                Item("lan 2001:db8::/128", mapOf("prefix" to "2001:db8::/128", "interface" to "lan", "source" to "address")),
                Item("wan 2001:db8:1::/64", mapOf("prefix" to "2001:db8:1::/64", "interface" to "wan", "source" to "address")),
                Item("guest fd00:1::/64", mapOf("prefix" to "fd00:1::/64", "interface" to "guest", "source" to "assigned")),
            ),
            "dhcp/dhcp" to listOf(
                Item("lan", mapOf("interface" to "lan", "ra" to "server")),
                Item("wan", mapOf("interface" to "wan", "ra" to "disabled")),
                Item("guest", mapOf("interface" to "guest", "ra" to "server", "ra_slaac" to "0")),
            ),
            "network/interface" to listOf(
                Item("lan", mapOf(".name" to "lan", "device" to "br-lan")),
                Item("guest", mapOf(".name" to "guest", "device" to "br-guest")),
            ),
        )
        val prefixes = MenuPath.parse("ipv6/nd/prefix")
        val layer = RouterOsLayer(base)
        val lan = assertNotNull(layer.get(prefixes, "lan fd61:198f:1f96::/64", null))
        assertEquals(listOf("br-lan", "1w", "1d", "true", "true", "true"), listOf(lan["interface"], lan["valid-lifetime"], lan["preferred-lifetime"], lan["on-link"], lan["autonomous"], lan["dynamic"]))
        assertEquals("false", layer.get(prefixes, "lan fd61:198f:1f96::/60", null)?.get("autonomous"))
        assertNull(layer.get(prefixes, "lan 2001:db8::/128", null))
        assertNull(layer.get(prefixes, "wan 2001:db8:1::/64", null))
        assertEquals("false", layer.get(prefixes, "guest fd00:1::/64", null)?.get("autonomous"))
        assertEquals(3, layer.count(prefixes, null))
    }

    @Test
    fun theGroupsAreWhatTheLoginsOfRpcdAreIn() = runTest {
        val base = Lists(
            "rpcd/login" to listOf(
                Item("a", mapOf("username" to "root", "read" to "*", "write" to "*")),
                Item("b", mapOf("username" to "viewer", "read" to "*")),
                Item("c", mapOf("username" to "fw", "read" to "luci-app-firewall", "write" to "luci-app-firewall")),
            ),
        )
        val layer = RouterOsLayer(base)
        val groups = MenuPath.parse("user/group")
        assertEquals(3, layer.count(groups, null))
        assertEquals("read, write: every ACL (*), as root has it", layer.get(groups, "full", null)?.get("policy"))
        assertEquals("ACLs of the login: luci-app-firewall", layer.get(groups, "luci-app-firewall", null)?.get("policy"))
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { layer.add(groups, mapOf("name" to "ops")) }.category)
    }

    @Test
    fun aNetworkHasAConfigurationOfItsOwn() = runTest {
        val base = Lists(
            "wireless/wifi-iface" to listOf(
                Item("default_radio0", mapOf(".name" to "default_radio0", "device" to "radio0", "mode" to "ap", "ssid" to "Home", "hidden" to "1", "maxassoc" to "40", "ifname" to "phy0-ap0", "network" to "lan", "isolate" to "1")),
                Item("uplink", mapOf(".name" to "uplink", "device" to "radio0", "mode" to "sta", "ssid" to "Cafe")),
            ),
            "wireless/wifi-device" to listOf(Item("radio0", mapOf(".name" to "radio0", "country" to "PA", "txpower" to "26"))),
            "network/interface" to listOf(Item("lan", mapOf(".name" to "lan", "device" to "br-lan"))),
        )
        val layer = RouterOsLayer(base)
        val configurations = MenuPath.parse("interface/wifi/configuration")
        val home = assertNotNull(layer.get(configurations, "default_radio0", null))
        assertEquals(listOf("phy0-ap0", "ap", "Home", "PA", "true", "40", "26"), listOf(home["name"], home["mode"], home["ssid"], home["country"], home["hide-ssid"], home["max-clients"], home["tx-power"]))
        assertEquals(listOf("station", "false"), layer.get(configurations, "uplink", null).let { listOf(it?.get("mode"), it?.get("hide-ssid")) })
        layer.set(configurations, "uplink", mapOf("mode" to "ap", "ssid" to "Guest", "hide-ssid" to "false", "max-clients" to "10", "dtim-period" to null))
        assertEquals(
            Triple("wireless/wifi-iface", "uplink", mapOf<String, String?>("ssid" to "Guest", "maxassoc" to "10", "dtim_period" to null, "mode" to "ap", "hidden" to null)),
            base.changed.single(),
        )
        val datapaths = MenuPath.parse("interface/wifi/datapath")
        val isolated = assertNotNull(layer.get(datapaths, "default_radio0", null))
        assertEquals(listOf("phy0-ap0", "br-lan", "true"), listOf(isolated["name"], isolated["bridge"], isolated["client-isolation"]))
        assertEquals(listOf(null, "false"), layer.get(datapaths, "uplink", null).let { listOf(it?.get("bridge"), it?.get("client-isolation")) })
        layer.set(datapaths, "uplink", mapOf("client-isolation" to "true"))
        assertEquals(Triple("wireless/wifi-iface", "uplink", mapOf<String, String?>("isolate" to "1")), base.changed.last())
    }

    @Test
    fun theNatOfEachFamilyHasItsOwnForwardsAndMasquerading() = runTest {
        val base = Lists(
            "firewall/redirect" to listOf(
                Item("any", mapOf("target" to "DNAT", "src" to "wan", "src_dport" to "80", "dest_ip" to "192.0.2.5")),
                Item("six", mapOf("target" to "DNAT", "src" to "wan", "src_dport" to "443", "dest_ip" to "2001:db8::5")),
                Item("named", mapOf("target" to "DNAT", "src" to "wan", "src_dport" to "22", "family" to "ipv6")),
                Item("open", mapOf("target" to "DNAT", "src" to "wan", "src_dport" to "53")),
            ),
            "firewall/zone" to listOf(
                Item("wan", mapOf("name" to "wan", "masq" to "1")),
                Item("vpn", mapOf("name" to "vpn", "masq6" to "1")),
            ),
        )
        val layer = RouterOsLayer(base)
        val v4 = MenuPath.parse("ip/firewall/nat")
        val v6 = MenuPath.parse("ipv6/firewall/nat")
        assertEquals(listOf(true, false, false, true), listOf("any", "six", "named", "open").map { layer.get(v4, it, null) != null })
        assertEquals(listOf(false, true, true, true), listOf("any", "six", "named", "open").map { layer.get(v6, it, null) != null })
        assertEquals("wan", layer.get(v4, "dynamic masq wan", null)?.get("out-interface-list"))
        assertNull(layer.get(v4, "dynamic masq6 vpn", null))
        assertEquals("vpn", layer.get(v6, "dynamic masq6 vpn", null)?.get("out-interface-list"))
        layer.add(v6, mapOf("chain" to "dstnat", "action" to "dst-nat", "in-interface-list" to "wan", "dst-port" to "8443", "to-addresses" to "2001:db8::8"))
        assertEquals("firewall/redirect" to mapOf("target" to "DNAT", "family" to "ipv6", "src" to "wan", "src_dport" to "8443", "dest_ip" to "2001:db8::8"), base.added.single())
    }

    @Test
    fun aRedirectOfFw3ThatRewritesTheSourceIsWrittenAsItIsRead() = runTest {
        val base = Lists(
            "firewall/redirect" to listOf(
                Item("old", mapOf("target" to "SNAT", "dest" to "wan", "src_ip" to "192.0.2.0/24", "src_dip" to "203.0.113.5", "dest_ip" to "198.51.100.7", "dest_port" to "443")),
            ),
        )
        val layer = RouterOsLayer(base)
        val nat = MenuPath.parse("ip/firewall/nat")
        val shown = assertNotNull(layer.get(nat, "old", null))
        assertEquals(
            listOf("srcnat", "src-nat", "wan", "203.0.113.5", "198.51.100.7", "443"),
            listOf("chain", "action", "out-interface-list", "to-addresses", "dst-address", "dst-port").map { shown[it] },
        )
        layer.set(nat, "old", mapOf("out-interface-list" to "vpn", "to-addresses" to "203.0.113.9", "to-ports" to "1024-2048", "dst-address" to "198.51.100.8", "dst-port" to "80,443"))
        assertEquals(
            Triple("firewall/redirect", "old", mapOf<String, String?>("dest" to "vpn", "src_dip" to "203.0.113.9", "src_dport" to "1024-2048", "dest_ip" to "198.51.100.8", "dest_port" to "80 443")),
            base.changed.single(),
        )
    }

    @Test
    fun aRuleIsSwitchedOffByTheYesOfTheButtons() = runTest {
        val base = Lists(
            "firewall/rule" to listOf(Item("ping", mapOf("name" to "Allow-Ping", "src" to "wan", "target" to "ACCEPT"))),
            "firewall/redirect" to listOf(Item("web", mapOf("target" to "DNAT", "src" to "wan", "src_dport" to "80", "dest_ip" to "192.0.2.5"))),
            "firewall/nat" to listOf(Item("out", mapOf("target" to "SNAT", "src" to "wan", "snat_ip" to "203.0.113.5"))),
        )
        val layer = RouterOsLayer(base)
        val filter = MenuPath.parse("ip/firewall/filter")
        val nat = MenuPath.parse("ip/firewall/nat")
        layer.set(filter, "ping", mapOf("disabled" to "yes"))
        layer.set(nat, "web", mapOf("disabled" to "yes"))
        layer.set(nat, "out", mapOf("disabled" to "yes"))
        layer.set(filter, "ping", mapOf("disabled" to "no"))
        assertEquals(
            listOf(
                Triple("firewall/rule", "ping", mapOf<String, String?>("enabled" to "0")),
                Triple("firewall/redirect", "web", mapOf<String, String?>("enabled" to "0")),
                Triple("firewall/nat", "out", mapOf<String, String?>("enabled" to "0")),
                Triple("firewall/rule", "ping", mapOf<String, String?>("enabled" to null)),
            ),
            base.changed.toList(),
        )
    }

    @Test
    fun aNewInterfaceListTakesThePoliciesOfTheDefaults() = runTest {
        val lists = MenuPath.parse("interface/list")
        val strict = Lists("firewall/defaults" to listOf(Item("cfg01e63d", mapOf("input" to "REJECT", "output" to "ACCEPT", "forward" to "REJECT", "synflood_protect" to "1"))))
        RouterOsLayer(strict).add(lists, mapOf("name" to "dmz"))
        assertEquals("firewall/zone" to mapOf("name" to "dmz", "input" to "REJECT", "output" to "ACCEPT", "forward" to "REJECT"), strict.added.single())
        val bare = Lists()
        RouterOsLayer(bare).add(lists, mapOf("name" to "dmz"))
        assertEquals("firewall/zone" to mapOf("name" to "dmz"), bare.added.single())
    }

    @Test
    fun theConnectionsAreThoseOfTheirFamily() = runTest {
        val base = Lists(
            "status/connections" to listOf(
                Item(
                    "tcp 192.0.2.2 40000 198.51.100.1 443",
                    mapOf(
                        "family" to "ipv4", "protocol" to "tcp", "src-address" to "192.0.2.2", "src-port" to "40000", "dst-address" to "198.51.100.1", "dst-port" to "443",
                        "timeout" to "3725", "tcp-state" to "TIME_WAIT", "packets" to "12", "reply-packets" to "10", "bytes" to "900", "reply-bytes" to "8000",
                    ),
                ),
                Item("udp fd00::2 5000 2001:db8::1 53", mapOf("family" to "ipv6", "protocol" to "udp", "src-address" to "fd00::2", "dst-address" to "2001:db8::1")),
            ),
        )
        val layer = RouterOsLayer(base)
        val v4 = MenuPath.parse("ip/firewall/connection")
        val v6 = MenuPath.parse("ipv6/firewall/connection")
        val web = assertNotNull(layer.get(v4, "tcp 192.0.2.2 40000 198.51.100.1 443", null))
        assertEquals(listOf("1h2m5s", "time-wait", "12", "10", "900", "8000"), listOf(web["timeout"], web["tcp-state"], web["orig-packets"], web["repl-packets"], web["orig-bytes"], web["repl-bytes"]))
        assertNull(layer.get(v6, "tcp 192.0.2.2 40000 198.51.100.1 443", null))
        assertEquals(listOf(1, 1), listOf(layer.count(v4, null), layer.count(v6, null)))
        assertEquals("fd00::2", layer.get(v6, "udp fd00::2 5000 2001:db8::1 53", null)?.get("src-address"))
    }

    @Test
    fun aNotrackRuleIsRawAndNoFilterRule() = runTest {
        val base = Lists(
            "firewall/rule" to listOf(
                Item("a", mapOf("name" to "Allow-Ping", "src" to "wan", "target" to "ACCEPT")),
                Item("b", mapOf("name" to "skip-vpn", "src" to "vpn", "proto" to "udp", "dest_port" to "1194", "target" to "NOTRACK", "family" to "ipv4")),
                Item("c", mapOf("name" to "mark-voip", "src" to "lan", "dest" to "wan", "target" to "MARK", "set_mark" to "0x10")),
            ),
        )
        val layer = RouterOsLayer(base)
        val raw = MenuPath.parse("ip/firewall/raw")
        val filter = MenuPath.parse("ip/firewall/filter")
        val skip = assertNotNull(layer.get(raw, "b", null))
        assertEquals(listOf("prerouting", "notrack", "vpn", "udp", "1194", "skip-vpn"), listOf(skip["chain"], skip["action"], skip["in-interface-list"], skip["protocol"], skip["dst-port"], skip["comment"]))
        assertNull(layer.get(raw, "a", null))
        assertNull(layer.get(MenuPath.parse("ipv6/firewall/raw"), "b", null))
        assertEquals(listOf(true, false, false), listOf("a", "b", "c").map { layer.get(filter, it, null) != null })
        layer.add(raw, mapOf("chain" to "prerouting", "action" to "notrack", "in-interface-list" to "all", "protocol" to "udp", "comment" to "skip-all"))
        assertEquals("firewall/rule" to mapOf("target" to "NOTRACK", "src" to "*", "family" to "ipv4", "proto" to "udp", "name" to "skip-all"), base.added.single())
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.add(raw, mapOf("action" to "notrack")) }.category)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.add(raw, mapOf("in-interface-list" to "wan", "chain" to "output")) }.category)
        layer.set(raw, "b", mapOf("in-interface-list" to "lan", "comment" to "skip-lan"))
        assertEquals(Triple("firewall/rule", "b", mapOf<String, String?>("name" to "skip-lan", "src" to "lan")), base.changed.single())
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.set(raw, "b", mapOf("action" to "drop")) }.category)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.set(raw, "b", mapOf("chain" to "output")) }.category)
        assertEquals(1, base.changed.size)
    }

    @Test
    fun aMarkingRuleIsMangleInTheChainItsZonesGive() = runTest {
        val base = Lists(
            "firewall/rule" to listOf(
                Item("pre", mapOf("name" to "m1", "src" to "lan", "dest" to "*", "target" to "MARK", "set_mark" to "0x10")),
                Item("post", mapOf("name" to "m2", "src" to "*", "dest" to "wan", "target" to "DSCP", "set_dscp" to "46")),
                Item("fwd", mapOf("src" to "lan", "dest" to "wan", "target" to "MARK", "set_xmark" to "0x2/0xff")),
                Item("any", mapOf("src" to "*", "dest" to "*", "target" to "MARK", "set_mark" to "0x3")),
                Item("in", mapOf("src" to "lan", "target" to "MARK", "set_mark" to "0x4")),
                Item("out", mapOf("dest" to "wan", "target" to "MARK", "set_mark" to "0x5")),
                Item("drop", mapOf("src" to "wan", "target" to "DROP")),
            ),
        )
        val layer = RouterOsLayer(base)
        val mangle = MenuPath.parse("ip/firewall/mangle")
        val ids = listOf("pre", "post", "fwd", "any", "in", "out")
        assertEquals(listOf("prerouting", "postrouting", "forward", "forward", "input", "output"), ids.map { layer.get(mangle, it, null)?.get("chain") })
        val pre = assertNotNull(layer.get(mangle, "pre", null))
        assertEquals(listOf("mark-packet", "0x10", "lan", "all", "m1"), listOf(pre["action"], pre["new-packet-mark"], pre["in-interface-list"], pre["out-interface-list"], pre["comment"]))
        assertEquals(listOf("change-dscp", "46"), layer.get(mangle, "post", null).let { listOf(it?.get("action"), it?.get("new-dscp")) })
        assertEquals("0x2/0xff", layer.get(mangle, "fwd", null)?.get("new-packet-mark"))
        assertNull(layer.get(mangle, "drop", null))
        val filter = MenuPath.parse("ip/firewall/filter")
        assertEquals(listOf(false, false, false, false, false, false, true), (ids + "drop").map { layer.get(filter, it, null) != null })
        layer.add(mangle, mapOf("chain" to "postrouting", "action" to "mark-packet", "new-packet-mark" to "0x7", "out-interface-list" to "wan", "protocol" to "udp"))
        assertEquals("firewall/rule" to mapOf("src" to "*", "dest" to "wan", "target" to "MARK", "set_mark" to "0x7", "proto" to "udp", "family" to "ipv4"), base.added.single())
        val refused = listOf(
            mapOf("chain" to "prerouting", "action" to "mark-packet", "new-packet-mark" to "0x1"),
            mapOf("chain" to "forward", "action" to "mark-packet", "new-packet-mark" to "0x1", "in-interface-list" to "lan"),
            mapOf("chain" to "input", "new-packet-mark" to "0x1"),
            mapOf("chain" to "input", "action" to "change-dscp"),
            mapOf("chain" to "input", "action" to "passthrough", "new-packet-mark" to "0x1"),
            mapOf("chain" to "somewhere", "action" to "mark-packet", "new-packet-mark" to "0x1"),
        )
        for (values in refused) assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.add(mangle, values) }.category, values.toString())
        assertEquals(1, base.added.size)
        layer.set(mangle, "fwd", mapOf("new-packet-mark" to "0x9/0xff"))
        assertEquals(Triple("firewall/rule", "fwd", mapOf<String, String?>("set_xmark" to "0x9/0xff")), base.changed.last())
        layer.set(mangle, "in", mapOf("chain" to "output", "out-interface-list" to "wan"))
        assertEquals(Triple("firewall/rule", "in", mapOf<String, String?>("src" to null, "dest" to "wan")), base.changed.last())
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.set(mangle, "pre", mapOf("action" to "change-dscp")) }.category)
        layer.set(mangle, "pre", mapOf("action" to "change-dscp", "new-dscp" to "46"))
        assertEquals(Triple("firewall/rule", "pre", mapOf<String, String?>("target" to "DSCP", "set_dscp" to "46")), base.changed.last())
        layer.set(mangle, "pre", mapOf("action" to "mark-packet"))
        assertEquals(Triple("firewall/rule", "pre", mapOf<String, String?>("target" to "MARK")), base.changed.last())
    }

    @Test
    fun theWindowsGetTheTabsOfTheNewLists() {
        val routerOs = bundledPacks().values.filter { it.platform == "routeros" && it.until == null }
        val windows = RouterOsLayer(Lists()).windows(routerOs).associate { it.menu.joinToString("/") to it.window.tabs.map { tab -> tab.path } }
        assertEquals(listOf("user", "user/group", "user/ssh-keys"), windows["System/Users"])
        assertEquals(listOf("ipv6/dhcp-server", "ipv6/dhcp-server/binding"), windows["IPv6/DHCP Server"])
        assertEquals(listOf("ipv6/nd", "ipv6/nd/prefix"), windows["IPv6/ND"])
        assertEquals(listOf("interface"), windows["PPP"])
        assertEquals(listOf("filter", "nat", "mangle", "raw", "connection").map { "ip/firewall/$it" }, windows["IP/Firewall"])
        assertEquals(listOf("filter", "nat", "mangle", "raw", "connection").map { "ipv6/firewall/$it" }, windows["IPv6/Firewall"])
        assertEquals(listOf("interface/wifi") + listOf("configuration", "security", "datapath", "registration-table", "radio").map { "interface/wifi/$it" }, windows["WiFi"])
    }

    @Test
    fun theWindowsNameOnlyWhatTheLayerHas() = runTest {
        val layer = RouterOsLayer(Lists())
        val missing = mutableListOf<String>()
        suspend fun check(what: String, path: String) {
            try {
                layer.describe(MenuPath.parse(path))
            } catch (e: DeviceException) {
                missing += "$what -> $path"
            }
        }
        for (pack in layer.windows(bundledPacks().values.filter { it.platform == "routeros" && it.until == null })) {
            for ((path, shown) in pack.paths) {
                shown.join?.let { check("$path join", it) }
                shown.open?.paths?.values?.forEach { check("$path open", it) }
                shown.owner?.let { check("$path owner", it.path) }
                shown.links.filter { it.window == null }.forEach { check("$path link ${it.title}", it.path) }
                assertTrue(shown.monitor == null || shown.monitor.command == "interface/monitor-traffic", "$path monitor")
            }
            for ((name, form) in pack.forms) {
                form.tabs.mapNotNull { it.path }.forEach { check("form $name", it) }
                assertTrue(form.tabs.all { it.monitor == null || it.monitor.command == "interface/monitor-traffic" }, "form $name monitor")
            }
        }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun aSourceTheSessionMayNotReadCountsAsOneTheRouterLacks() = runTest {
        val lists = Lists("status/devices" to listOf(Item("eth0", mapOf("name" to "eth0", "type" to "Network device", "devtype" to "ethernet"))))
        val denied = object : DeviceSession by lists {
            override suspend fun describe(path: MenuPath): MenuSchema =
                if (path.segments.first() == "sqm") throw DeviceException(ErrorCategory.PermissionDenied, "Permission denied") else lists.describe(path)
        }
        assertTrue("queue" in RouterOsLayer(lists).describe(MenuPath.Root).children)
        val layer = RouterOsLayer(denied)
        assertEquals(listOf(true, false), listOf("interface", "queue").map { it in layer.describe(MenuPath.Root).children })
        assertNotNull(layer.get(MenuPath.parse("interface"), "eth0", null))
    }

    @Test
    fun aDeviceOfNoKindIsNotAnEthernetPort() = runTest {
        val layer = RouterOsLayer(
            Lists(
                "status/devices" to listOf(
                    Item("eth0", mapOf("name" to "eth0", "type" to "Network device", "devtype" to "ethernet")),
                    Item("Mihomo", mapOf("name" to "Mihomo", "type" to "Network device", "devtype" to "none")),
                ),
            ),
        )
        val interfaces = MenuPath.parse("interface")
        assertEquals(listOf("ether", "virtual"), listOf("eth0", "Mihomo").map { assertNotNull(layer.get(interfaces, it, null))["type"] })
    }

    private class Lists(vararg lists: Pair<String, List<Item>>) : DeviceSession {
        private val lists = lists.toMap()
        val added = mutableListOf<Pair<String, Map<String, String>>>()
        val changed = mutableListOf<Triple<String, String?, Map<String, String?>>>()
        override val device = DeviceInfo(Platform.OpenWrt, "lab", "25.12.6", "x86_64", "VM", emptySet(), emptySet())
        override val capabilities = Capabilities(true, true, true, SafeModeKind.None, terminal = false, files = false, export = false, menuContents = false)
        override suspend fun describe(path: MenuPath) = MenuSchema(path, NodeKind.List, emptyList(), listOf("print"), emptyList())
        override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> = flowOf(ListEvent.Snapshot(lists[path.toString()].orEmpty()))
        override suspend fun get(path: MenuPath, id: String?, properties: List<String>?): Item? = null
        override suspend fun count(path: MenuPath, filter: Filter?) = 0
        override suspend fun add(path: MenuPath, values: Map<String, String>): String {
            added += path.toString() to values
            return "new"
        }
        override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) {
            changed += Triple(path.toString(), id, changes)
        }
        override suspend fun remove(path: MenuPath, ids: List<String>) = Unit
        override suspend fun move(path: MenuPath, ids: List<String>, before: String?) = Unit
        override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> = emptyFlow()
        override suspend fun export(path: MenuPath): String = error("not used")
        override val safeMode: SafeMode? = null
        override val ssh: SshSession? = null
        override fun close() = Unit
    }
}
