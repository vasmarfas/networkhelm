package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxVpnTailscaleTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private val nodes = MenuPath.parse("vpn/tailscale")

    // tailscale status --json of Tailscale 1.98.10 on Alpine in the lab on 18.11, tailscaled in userspace networking and logged out
    private val loggedOut = """{"Version":"1.98.10-AlpineLinux","TUN":false,"BackendState":"NeedsLogin","AuthURL":"","TailscaleIPs":null,"Self":{"ID":"",""" +
        """"PublicKey":"nodekey:0000000000000000000000000000000000000000000000000000000000000000","HostName":"nh-alpine324","DNSName":"","OS":"linux","UserID":0,""" +
        """"TailscaleIPs":null,"Addrs":[],"CurAddr":"","Relay":"","PeerRelay":"","RxBytes":0,"TxBytes":0,"Created":"0001-01-01T00:00:00Z","LastSeen":"0001-01-01T00:00:00Z"}}"""

    // a node logged in with one peer, written to the form of ipnstate.Status: no account was used in the lab
    private val running = """{"Version":"1.98.10","BackendState":"Running","AuthURL":"","CurrentTailnet":{"Name":"example.com"},""" +
        """"Self":{"HostName":"nh-debian12","DNSName":"nh-debian12.example.ts.net.","OS":"linux","TailscaleIPs":["100.64.0.1","fd7a:115c:a1e0::1"],"Online":true},""" +
        """"Peer":{"nodekey:aa":{"PublicKey":"nodekey:aa","HostName":"nh-peer","DNSName":"nh-peer.example.ts.net.","OS":"linux","TailscaleIPs":["100.64.0.2"],""" +
        """"Online":true,"Active":true,"CurAddr":"","Relay":"fra","RxBytes":100,"TxBytes":200,"LastSeen":"2026-11-18T10:00:00Z","ExitNode":false,"ExitNodeOption":true}}}"""

    @Test
    fun theNodeShowsTheStateOfItsBackendAndThePeersHowTheyAreReached() {
        val (self) = parseTailscale(loggedOut)
        assertEquals(listOf("self", "nh-alpine324", "", "NeedsLogin", "1.98.10-AlpineLinux"), listOf("kind", "name", "addresses", "state", "version").map { self[it] })
        val (node, peer) = parseTailscale(running)
        assertEquals(listOf("100.64.0.1, fd7a:115c:a1e0::1", "example.com", "nh-debian12.example.ts.net"), listOf("addresses", "tailnet", "dns-name").map { node[it] })
        assertEquals(
            listOf("nodekey:aa", "peer", "nh-peer", "100.64.0.2", "true", "relay fra", "100", "2026-11-18T10:00:00Z", "true"),
            listOf(peer.id) + listOf("kind", "name", "addresses", "online", "connection", "rx", "last-seen", "exit-node-option").map { peer[it] },
        )
        assertEquals(listOf("NoState"), parseTailscale("").map { it["state"] })
    }

    @Test
    fun anAuthKeyGoesToTailscaleUpThroughAFileUnderTheGuard() = runTest {
        val key = "tskey-" + "auth-rdtest"
        val host = VpnHost(
            listOf(
                "for t in" to ok("tailscale\n"), "id -u" to ok("0\n"), "tailscale status --json" to ok(loggedOut), "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"),
                "umask 077; f=" to ok(), "kill" to ok(), "tailscale down" to ok(),
                "echo \"\$SSH_CONNECTION\"; ip -o addr show" to ok("192.168.10.2 50000 192.168.10.249 22\n2: eth0    inet 192.168.10.249/24 scope global eth0\n"),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        session.set(nodes, "self", mapOf("auth-key" to key))
        assertEquals(listOf("$key\n"), host.given)
        assertTrue(
            host.ran.any { it.startsWith("umask 077; f=\$(mktemp) && cat > \"\$f\" && tailscale up --auth-key=\"file:\$f\" --timeout=30s >&2; s=\$?; rm -f \"\$f\"") },
            "${host.ran}",
        )
        assertTrue("sleep 60; tailscale down;" in host.ran.single { it.startsWith("B=") }, "${host.ran}")
        assertTrue(host.sent.none { key in it }, "the key is on no command line")
        assertEquals("tailscale up: invalid key tskey-(hidden)", hidden("tailscale up: invalid key $key"))

        session.run(nodes, "down", emptyMap()).toList()
        assertEquals("tailscale down", host.ran.last())
    }

    // tailscale debug prefs of the same node in the lab on 18.11
    private val prefs = """{"ControlURL":"https://controlplane.tailscale.com","RouteAll":false,"ExitNodeID":"","ExitNodeIP":"","ExitNodeAllowLANAccess":false,""" +
        """"CorpDNS":true,"RunSSH":false,"RunWebClient":false,"WantRunning":false,"LoggedOut":false,"ShieldsUp":false,"AdvertiseTags":null,"Hostname":"",""" +
        """"AdvertiseRoutes":null,"NoSNAT":false,"NetfilterMode":2}"""

    @Test
    fun theSettingsOfTheNodeGoThroughTailscaleSetAndComeBackUnderTheGuard() = runTest {
        val (self) = parseTailscale("$loggedOut\n--nh-prefs--\n$prefs\n")
        assertEquals(
            listOf("false", "true", "false", "false", "", "", "false", "false"),
            listOf("accept-routes", "accept-dns", "ssh", "shields-up", "hostname", "advertise-routes", "advertise-exit-node", "exit-node-allow-lan-access").map { self[it] },
        )
        assertEquals("10.0.0.0/24", parseTailscale("$loggedOut\n--nh-prefs--\n${prefs.replace("\"AdvertiseRoutes\":null", "\"AdvertiseRoutes\":[\"10.0.0.0/24\",\"0.0.0.0/0\",\"::/0\"]")}")
            .first()["advertise-routes"])

        val host = VpnHost(
            listOf(
                "for t in" to ok("tailscale\n"), "id -u" to ok("0\n"), "tailscale status --json" to ok("$loggedOut\n--nh-prefs--\n$prefs\n"),
                "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "tailscale set" to ok(), "kill" to ok(),
            ),
        )
        SshHostSession(host, device, linux = true).set(nodes, "self", mapOf("accept-routes" to "yes", "hostname" to "nh-box"))
        assertTrue("tailscale set --accept-routes='true' --hostname='nh-box' >&2" in host.ran, "${host.ran}")
        assertTrue("tailscale set --accept-routes='\\''false'\\'' --hostname='\\'''\\''" in host.ran.single { it.startsWith("B=") }, "${host.ran}")
    }

    // the same prefs of 18.11 have no NoStatefulFiltering, OperatorUser and AutoUpdate: those stay empty and out of what the guard puts back
    @Test
    fun theFirewallAndTheServerOfTheNodeComeFromItsPrefs() = runTest {
        val (self) = parseTailscale("$loggedOut\n--nh-prefs--\n$prefs\n")
        assertEquals(
            listOf("https://controlplane.tailscale.com", "false", "true", "on", "", "", ""),
            listOf("login-server", "webclient", "snat-subnet-routes", "netfilter-mode", "stateful-filtering", "auto-update", "operator").map { self[it] },
        )
        val host = VpnHost(
            listOf(
                "for t in" to ok("tailscale\n"), "id -u" to ok("0\n"), "tailscale status --json" to ok("$loggedOut\n--nh-prefs--\n$prefs\n"),
                "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "tailscale set" to ok(), "kill" to ok(), "umask 077; f=" to ok(), "tailscale logout" to ok(),
                "echo \"\$SSH_CONNECTION\"; ip -o addr show" to ok("192.168.10.2 50000 192.168.10.249 22\n2: eth0    inet 192.168.10.249/24 scope global eth0\n"),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        session.set(nodes, "self", mapOf("netfilter-mode" to "nodivert", "stateful-filtering" to "yes"))
        assertTrue("tailscale set --stateful-filtering='true' --netfilter-mode='nodivert' >&2" in host.ran, "${host.ran}")
        val guard = host.ran.last { it.startsWith("B=") }
        assertTrue("tailscale set --netfilter-mode='\\''on'\\''" in guard && "stateful-filtering" !in guard, guard)
        val mode = assertFailsWith<DeviceException> { session.set(nodes, "self", mapOf("netfilter-mode" to "strict")) }
        assertEquals("netfilter-mode: strict is not a value it takes", mode.message)

        val key = "tskey-" + "auth-rdtest"
        session.set(nodes, "self", mapOf("auth-key" to key, "login-server" to "https://headscale.example.net"))
        assertTrue(host.ran.any { it.startsWith("umask 077; f=\$(mktemp) && cat > \"\$f\" && tailscale up --login-server='https://headscale.example.net' --auth-key=\"file:\$f\"") }, "${host.ran}")
        session.run(nodes, "logout", emptyMap()).toList()
        assertEquals("tailscale logout >&2", host.ran.last())
    }
}
