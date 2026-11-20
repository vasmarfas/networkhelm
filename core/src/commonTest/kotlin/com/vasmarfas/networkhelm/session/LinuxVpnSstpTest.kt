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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxVpnSstpTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private val connections = MenuPath.parse("vpn/sstp")

    // a unit made here as the read prints it, with the options of sstp-client 1.0.18 of Debian 12 the lab listed on 18.11; the container
    // has no /dev/ppp, so no tunnel came up
    private val state = "--nh-sstp-- office inactive enabled\n/usr/sbin/sstpc --log-stderr --save-server-route --user alice --ca-cert /etc/ssl/certs/ca.pem " +
        "vpn.example.net remotename networkhelm-sstp-office ipparam networkhelm-sstp-office ifname sstp-office require-mschap-v2 noauth noipdefault nodefaultroute\n"

    private fun host(vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok("sstpc\nsystemctl\n"), "id -u" to ok("0\n"), "for f in /etc/systemd/system/networkhelm-sstp-" to ok(state),
            "for f in /etc/ssl/certs/ca-certificates.crt" to ok("/etc/ssl/certs/ca-certificates.crt\n"), "command -v sstpc" to ok("/usr/sbin/sstpc\n"),
            "command -v ip" to ok("/usr/sbin/ip\n"), "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "systemctl" to ok(), "umask 077; f=/etc/ppp" to ok(),
            "f=/etc/ppp" to ok(), "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "kill" to ok(),
        ),
    )

    @Test
    fun aConnectionIsAUnitWithItsPasswordInChapSecretsUnderItsOwnName() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        val (office) = (session.watch(connections).first() as ListEvent.Snapshot).items
        assertEquals(
            listOf("inactive", "true", "vpn.example.net", "alice", "/etc/ssl/certs/ca.pem", "false", "false", "", "", "sstp-office"),
            listOf("state", "autostart", "server", "user", "ca-file", "skip-cert-check", "default-route", "mtu", "password", "interface").map { office[it] },
        )

        session.add(connections, mapOf("name" to "home", "server" to "vpn.example.net", "user" to "bob", "password" to "rd-test-pw", "autostart" to "yes"))
        val (unit, secret) = host.given
        assertTrue(
            "ExecStart=/usr/sbin/sstpc --log-stderr --save-server-route --user bob --ca-cert /etc/ssl/certs/ca-certificates.crt vpn.example.net remotename " +
                "networkhelm-sstp-home ipparam networkhelm-sstp-home ifname sstp-home require-mschap-v2 noauth noipdefault nodefaultroute\n" in unit,
            "without a CA file of its own the server is checked against the CAs of the system: $unit",
        )
        assertTrue("ExecStartPre" !in unit, unit)
        assertEquals("\"bob\" networkhelm-sstp-home \"rd-test-pw\" *\n", secret)
        assertEquals("systemctl enable networkhelm-sstp-home.service", host.ran.last())
        assertTrue(host.sent.none { "rd-test-pw" in it }, "the password is on no command line")

        val written = host.given.size
        val login = assertFailsWith<DeviceException> { session.set(connections, "office", mapOf("user" to "carol")) }
        assertEquals("password: a value is needed", login.message)
        assertEquals(written, host.given.size, "a new login without a password leaves the unit as it is")
    }

    // the way to the computer of the coordinator of the lab on 18.11, 192.168.11.2 behind the gateway of 39002, as ip route get prints it
    private val way = "set -- \$SSH_CONNECTION" to ok("192.168.11.2\n192.168.11.2 via 192.168.10.1 dev eth0 src 192.168.10.249 uid 0 \n    cache \n")

    @Test
    fun theDefaultRouteGoesToTheTunnelAndTheWayToThisSessionStaysOutsideIt() = runTest {
        val host = host(way)
        val session = SshHostSession(host, device, linux = true)
        val values = mapOf(
            "name" to "home", "server" to "vpn.example.net", "user" to "bob", "password" to "rd-test-pw", "skip-cert-check" to "yes", "default-route" to "yes",
            "peer-dns" to "yes", "mtu" to "1400",
        )
        session.add(connections, values)
        val unit = host.given.first()
        val line = "/usr/sbin/sstpc --log-stderr --save-server-route --user bob --cert-warn vpn.example.net remotename networkhelm-sstp-home ipparam networkhelm-sstp-home " +
            "ifname sstp-home require-mschap-v2 noauth noipdefault defaultroute replacedefaultroute usepeerdns mtu 1400 mru 1400"
        assertTrue(
            "ExecStartPre=-/usr/sbin/ip -4 route replace 192.168.11.2/32 via 192.168.10.1 dev eth0\nExecStart=$line\n" +
                "ExecStopPost=-/usr/sbin/ip -4 route del 192.168.11.2/32 via 192.168.10.1 dev eth0\n" in unit,
            unit,
        )
        val (home) = parseSstp("--nh-sstp-- home inactive disabled\n$line\n")
        assertEquals(listOf("vpn.example.net", "bob", "", "true", "true", "true", "1400"), listOf("server", "user", "ca-file", "skip-cert-check", "default-route", "peer-dns", "mtu").map { home.row()[it] })

        val bare = host("for f in /etc/ssl/certs/ca-certificates.crt" to ok())
        val refused = assertFailsWith<DeviceException> {
            SshHostSession(bare, device, linux = true).add(connections, mapOf("name" to "work", "server" to "vpn.example.net", "user" to "bob", "password" to "rd-test-pw"))
        }
        assertEquals("the host has no bundle of CA certificates: give the CA File of the server or tick Skip Certificate Check", refused.message)
        val mtu = assertFailsWith<DeviceException> { session.add(connections, values + ("name" to "work") + ("mtu" to "9000")) }
        assertEquals("mtu: 9000 is not a value it takes", mtu.message)
    }

    @Test
    fun aStartGoesUnderTheGuardAndWaitsForTheDeviceOfPppd() = runTest {
        val host = host()
        SshHostSession(host, device, linux = true).run(connections, "up", mapOf("numbers" to "office")).toList()
        val start = host.ran.single { it.startsWith("systemctl start ") }
        assertTrue(start.startsWith("systemctl start networkhelm-sstp-office.service && for i in \$(seq 20); do ip link show sstp-office") && "sstpc made no device sstp-office in 20 s" in start, start)
        assertTrue("systemctl stop networkhelm-sstp-office.service" in host.ran.single { it.startsWith("B=") }, "${host.ran}")
    }
}
