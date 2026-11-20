package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxTunnelTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private val route: suspend (String) -> String = { "192.168.10.250" }

    @Test
    fun aTunnelHasTheFormOfIpAndOfEachNetworkConfiguration() = runTest {
        val gre = linkTunnel("gre", mapOf("remote" to "192.168.10.249", "tunnel-id" to "42"), route)
        assertEquals("ip link add 'gre1' type gre local 192.168.10.250 remote 192.168.10.249 key 42", gre.command("gre1", ""))
        assertEquals("[NetDev]\nName=gre1\nKind=gre\n\n[Tunnel]\nLocal=192.168.10.250\nRemote=192.168.10.249\nKey=42\nIndependent=true\n", gre.netdev("gre1"))
        assertEquals("""{"mode":"gre","local":"192.168.10.250","remote":"192.168.10.249","key":42}""", gre.netplan().toString())
        assertEquals(
            "nmcli connection add con-name 'gre1' ifname 'gre1' type ip-tunnel mode gre ip-tunnel.local 192.168.10.250 ip-tunnel.remote 192.168.10.249 " +
                "ip-tunnel.input-key 42 ip-tunnel.output-key 42 ipv4.method disabled ipv6.method ignore",
            gre.nmcli("gre1", ""),
        )

        val vxlan = linkTunnel("vxlan", mapOf("remote" to "192.168.10.249", "local" to "192.168.10.250", "tunnel-id" to "100"), route)
        assertEquals("ip link add 'vx1' type vxlan id 100 local 192.168.10.250 remote 192.168.10.249 dstport 4789 dev 'eth0'", vxlan.command("vx1", "eth0"))
        assertEquals("""{"mode":"vxlan","local":"192.168.10.250","remote":"192.168.10.249","id":100,"port":4789}""", vxlan.netplan().toString())
        assertTrue("[VXLAN]\nVNI=100\nLocal=192.168.10.250\nRemote=192.168.10.249\nDestinationPort=4789\nIndependent=true\n" in vxlan.netdev("vx1"))

        val geneve = linkTunnel("geneve", mapOf("remote" to "192.168.10.249", "tunnel-id" to "7"), { error("GENEVE has no local address") })
        assertEquals("ip link add 'gn1' type geneve id 7 remote 192.168.10.249 dstport 6081", geneve.command("gn1", ""))

        val noVni = assertFailsWith<DeviceException> { linkTunnel("vxlan", mapOf("remote" to "192.168.10.249"), route) }
        assertEquals("tunnel-id: a value is needed", noVni.message)
        val keyOfIpip = assertFailsWith<DeviceException> { linkTunnel("ipip", mapOf("remote" to "192.168.10.249", "tunnel-id" to "5"), route) }
        assertEquals("tunnel-id: 5 is not a value it takes", keyOfIpip.message)
        val noRemote = assertFailsWith<DeviceException> { linkTunnel("gre", emptyMap(), route) }
        assertEquals("remote:  is not a value it takes", noRemote.message)
    }

    // info_data as ip -j -d link show of iproute2 6.1 prints it, GRE with its key as an address
    @Test
    fun theTableShowsTheAddressesKeyVniAndPortOfATunnel() {
        val gre = Json.parseToJsonElement("""{"remote":"192.168.10.249","local":"192.168.10.250","ttl":0,"pmtudisc":true,"ikey":"0.0.0.42","okey":"0.0.0.42"}""").jsonObject
        assertEquals(
            mapOf("vlan-id" to "", "local" to "192.168.10.250", "remote" to "192.168.10.249", "tunnel-id" to "42", "port" to ""),
            virtualData("gre", gre),
        )
        val vxlan = Json.parseToJsonElement("""{"id":100,"remote":"192.168.10.249","local":"192.168.10.250","link":"eth0","port":4789,"learning":true}""").jsonObject
        assertEquals(listOf("192.168.10.249", "100", "4789"), virtualData("vxlan", vxlan).let { listOf(it["remote"], it["tunnel-id"], it["port"]) })
        val ipip = Json.parseToJsonElement("""{"remote":"192.168.10.249","local":"any","ttl":0}""").jsonObject
        assertEquals("", virtualData("ipip", ipip)["local"])
        assertEquals("10", virtualData("vlan", Json.parseToJsonElement("""{"protocol":"802.1Q","id":10}""").jsonObject)["vlan-id"])
    }

    // through the window of virtual interfaces, as the patches .notes/agent-vpn-b7-tunnels*.patch make it; gre0 and gretap0 are what the
    // kernel of the lab made on 18.11 as it loaded ip_gre, on no link, which the window showed as removed and kept
    @Test
    fun aTunnelOfTheWindowTakesTheLocalAddressTheHostLeavesByAndTheTunnelsOfTheKernelStayOut() = runTest {
        val fallback = """{"ifindex":6,"link":null,"ifname":"gre0","flags":["NOARP"],"mtu":1476,"operstate":"DOWN","link_type":"gre","address":"0.0.0.0",""" +
            """"linkinfo":{"info_kind":"gre","info_data":{"remote":"any","local":"any","ttl":0,"pmtudisc":true}}},{"ifindex":7,"link":null,"ifname":"gretap0",""" +
            """"flags":["BROADCAST","MULTICAST"],"mtu":1462,"operstate":"DOWN","link_type":"ether","address":"00:00:00:00:00:00",""" +
            """"linkinfo":{"info_kind":"gretap","info_data":{"remote":"any","local":"any","ttl":0,"pmtudisc":true}}},"""
        val link = """{"ifindex":8,"ifname":"gre1","flags":["POINTOPOINT","NOARP","UP","LOWER_UP"],"mtu":1476,"operstate":"UNKNOWN","link_type":"gre",""" +
            """"address":"192.168.10.250","linkinfo":{"info_kind":"gre","info_data":{"remote":"192.168.10.249","local":"192.168.10.250","ttl":0,"ikey":"0.0.0.42","okey":"0.0.0.42"}}}"""
        val host = VpnHost(
            listOf(
                "for t in" to ok("systemctl\n"), "id -u" to ok("0\n"), "ip -j link show lo" to ok("[]\n"), "ip -j -d -s link show" to ok("[$fallback$link]\n"),
                "ip -j -d link show" to ok("[$fallback$link]\n"), "ip -o route get" to ok("192.168.10.249 dev eth0 src 192.168.10.250 uid 0 \\    cache \n"), "ip link add" to ok(),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        session.add(MenuPath.parse("interface/virtual"), mapOf("type" to "gre", "name" to "gre1", "remote" to "192.168.10.249", "tunnel-id" to "42"))
        assertTrue("ip link add 'gre1' type gre local 192.168.10.250 remote 192.168.10.249 key 42 && ip link set dev 'gre1' up" in host.ran, "${host.ran}")
        val (gre) = (session.watch(MenuPath.parse("interface/virtual")).first() as ListEvent.Snapshot).items.also { rows -> assertEquals(listOf("gre1"), rows.map { it["name"] }) }
        assertEquals(listOf("gre1", "gre", "192.168.10.250", "192.168.10.249", "42"), listOf("name", "type", "local", "remote", "tunnel-id").map { gre[it] })

        // the row of gre0 the window gave in the lab, .../v14/b7-tun-1-lab.txt
        assertTrue(kernelFallback(Item("6", mapOf("name" to "gre0", "type" to "gre", "mtu" to "1476", "running" to "false", "disabled" to "true", "parent" to "null"))))
    }
}
