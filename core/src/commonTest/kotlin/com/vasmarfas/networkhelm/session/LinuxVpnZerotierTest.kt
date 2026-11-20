package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxVpnZerotierTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private val networks = MenuPath.parse("vpn/zerotier")

    // zerotier-cli -j info of ZeroTier 1.16.2 on Arch in the lab on 18.11, the address of the node masked there
    private val info = """{"address":"<node>","clock":1794956446750,"config":{"settings":{"allowTcpFallbackRelay":true,"forceTcpRelay":false,""" +
        """"homeDir":"/var/lib/zerotier-one","portMappingEnabled":true,"primaryPort":9993,"secondaryPort":45242,"tertiaryPort":40959}},"online":false,"version":"1.16.2"}"""

    // a network joined and let in, written to the form of zerotier-cli -j listnetworks: the lab joined only a network that does not exist
    private val joined = """[{"allowDNS":false,"allowDefault":false,"allowGlobal":false,"allowManaged":true,"assignedAddresses":["10.147.17.5/24"],"id":"8056c2e21c123456",""" +
        """"mac":"7a:12:34:56:78:9a","mtu":2800,"name":"lab","nwid":"8056c2e21c123456","portDeviceName":"ztabcdef12","status":"OK","type":"PRIVATE"}]"""

    private fun host(vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok("zerotier-cli\n"), "id -u" to ok("0\n"), "zerotier-cli -j listnetworks" to ok(joined), "zerotier-cli join" to ok("200 join OK\n"),
            "zerotier-cli set" to ok(), "zerotier-cli leave" to ok("200 leave OK\n"), "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "kill" to ok(),
            "echo \"\$SSH_CONNECTION\"; ip -o addr show" to ok("192.168.10.2 50000 192.168.10.245 22\n2: eth0    inet 192.168.10.245/24 scope global eth0\n"),
        ),
    )

    @Test
    fun theNodeAndItsNetworksComeFromZerotierCli() {
        assertEquals(
            listOf("<node>", "1.16.2", "false", "9993", "true", "false", "true"),
            listOf("address", "version", "online", "primary-port", "tcp-fallback", "force-tcp", "port-mapping").map { parseZerotierNode(info)[it] },
        )
        val (lab) = parseZerotierNetworks(joined)
        assertEquals(
            listOf("8056c2e21c123456", "lab", "OK", "PRIVATE", "ztabcdef12", "10.147.17.5/24", "7A:12:34:56:78:9A", "true", "false"),
            listOf(lab.id) + listOf("name", "status", "type", "device", "addresses", "mac-address", "allow-managed", "allow-default").map { lab[it] },
        )
        assertEquals(emptyList(), parseZerotierNetworks("[]\n"))
        val routes = """"routes":[{"flags":0,"metric":0,"target":"10.147.17.0/24","via":null},{"flags":0,"metric":0,"target":"0.0.0.0/0","via":"10.147.17.1"}],""" +
            """"dns":{"domain":"lab.zt","servers":["10.147.17.1"]},"""
        val routed = parseZerotierNetworks(joined.replace("\"mtu\":2800,", "\"mtu\":2800,$routes")).single()
        assertEquals(listOf("10.147.17.0/24, 0.0.0.0/0 via 10.147.17.1", "10.147.17.1", "lab.zt"), listOf("routes", "dns", "dns-domain").map { routed[it] })
    }

    // a root and a node of the network as zerotier-cli -j peers lists them, written to its form: the lab node reached no root
    @Test
    fun thePeersShowHowFarTheyAreAndTheWayToThem() {
        val peers = """[{"address":"778cde7190","isBonded":false,"latency":61,"paths":[{"active":true,"address":"103.195.103.66/9993","expired":false,"preferred":true}],""" +
            """"role":"PLANET","version":"-1.-1.-1"},{"address":"a1b2c3d4e5","isBonded":false,"latency":-1,"paths":[],"role":"LEAF","version":"1.16.2"}]"""
        val (root, leaf) = parseZerotierPeers(peers)
        assertEquals(listOf("778cde7190", "PLANET", "", "61", "103.195.103.66/9993", "1"), listOf(root.id) + listOf("role", "version", "latency", "path", "paths").map { root[it] })
        assertEquals(listOf("LEAF", "1.16.2", "", "", "0"), listOf("role", "version", "latency", "path", "paths").map { leaf[it] })
    }

    // a local.conf with a setting of its own besides the settings, written to the form of the documentation of ZeroTier
    @Test
    fun theSettingsOfTheNodeGoToLocalConfAndTheServiceStartsAgainUnderTheGuard() = runTest {
        val local = """{"physical":{"10.0.0.0/8":{"blacklist":true}},"settings":{"allowTcpFallbackRelay":true}}"""
        val host = host(
            "for t in" to ok("zerotier-cli\nsystemctl\n"), "cat '/var/lib/zerotier-one/local.conf'" to ok("$local\n"), "umask 077; mkdir -p '/var/lib/zerotier-one'" to ok(),
            "rm -f" to ok(), "systemctl restart" to ok(),
        )
        val node = MenuPath.parse("vpn/zerotier/node")
        SshHostSession(host, device, linux = true).set(node, null, mapOf("primary-port" to "9994", "tcp-fallback" to "", "port-mapping" to "no"))
        assertEquals("""{"physical":{"10.0.0.0/8":{"blacklist":true}},"settings":{"primaryPort":9994,"portMappingEnabled":false}}""" + "\n", host.given.single())
        val guard = host.ran.indexOf(host.ran.single { it.startsWith("B=") })
        assertEquals("systemctl restart zerotier-one", host.ran[guard + 3], "${host.ran}")
        assertTrue("systemctl restart zerotier-one" in host.ran[guard], "taking it back starts the service again with the old file: ${host.ran[guard]}")
        val port = assertFailsWith<DeviceException> { SshHostSession(host, device, linux = true).set(node, null, mapOf("primary-port" to "70000")) }
        assertEquals("primary-port: 70000 is not a value it takes", port.message)
    }

    @Test
    fun aNetworkJoinedWithTheDefaultRouteGoesUnderTheGuardAndOneThisSessionComesByStays() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        assertEquals("8056c2e21c123456", session.add(networks, mapOf("id" to "8056C2E21C123456", "allow-default" to "yes")))
        assertTrue("zerotier-cli join 8056c2e21c123456 >&2" in host.ran, "${host.ran}")
        assertTrue("sleep 60; zerotier-cli set 8056c2e21c123456 allowDefault=0;" in host.ran.single { it.startsWith("B=") }, "${host.ran}")
        assertTrue("zerotier-cli set 8056c2e21c123456 allowDefault=1 >/dev/null" in host.ran, "${host.ran}")

        val over = host("echo \"\$SSH_CONNECTION\"; ip -o addr show" to ok("10.147.17.9 50000 10.147.17.5 22\n7: ztabcdef12    inet 10.147.17.5/24 scope global ztabcdef12\n"))
        val kept = assertFailsWith<DeviceException> { SshHostSession(over, device, linux = true).remove(networks, listOf("8056c2e21c123456")) }
        assertEquals("this session goes over ztabcdef12, and it would end with it", kept.message)
        SshHostSession(host, device, linux = true).remove(networks, listOf("8056c2e21c123456"))
        assertEquals("zerotier-cli leave 8056c2e21c123456 >&2", host.ran.last())
    }
}
