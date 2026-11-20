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

class LinuxVpnOpenConnectTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private val connections = MenuPath.parse("vpn/openconnect")

    private val pin = "pin-sha256:" + "A".repeat(43) + "="

    // a unit made here as the read prints it, written to the options of OpenConnect 9.01 of Debian 12: the container of the lab has no
    // /dev/net/tun, so no tunnel came up
    private fun state(active: String) = "--nh-openconnect-- office $active enabled\n" +
        "/usr/sbin/openconnect --non-inter --passwd-on-stdin --protocol=anyconnect --user=nh-oc --servercert=$pin --interface=oc-office 192.168.10.250:4443\n"

    private fun host(active: String = "inactive", vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok("openconnect\nsystemctl\n"), "id -u" to ok("0\n"), "for f in /etc/systemd/system/networkhelm-openconnect-" to ok(state(active)),
            "command -v openconnect" to ok("/usr/sbin/openconnect\n"), "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "systemctl" to ok(),
            "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "kill" to ok(),
            "echo \"\$SSH_CONNECTION\"; ip -o addr show" to ok("192.168.10.2 50000 192.168.10.249 22\n2: eth0    inet 192.168.10.249/24 brd 192.168.10.255 scope global eth0\n"),
        ),
    )

    @Test
    fun aConnectionIsAUnitOfItsOwnWithThePasswordInAFileOfItsOwn() = runTest {
        val (office) = SshHostSession(host(), device, linux = true).rows("vpn/openconnect")
        assertEquals(
            listOf("inactive", "true", "192.168.10.250:4443", "anyconnect", "nh-oc", pin, "", "oc-office"),
            listOf("state", "autostart", "server", "protocol", "user", "pin", "password", "interface").map { office[it] },
        )

        val host = host()
        val session = SshHostSession(host, device, linux = true)
        // the pin as the window of ocserv shows it, bare base64
        val bare = pin.removePrefix("pin-sha256:")
        session.add(connections, mapOf("name" to "home", "server" to "vpn.example.net", "user" to "nh-oc", "password" to "rd-test-pw", "pin" to bare, "autostart" to "yes"))
        val (password, unit) = host.given
        assertEquals("rd-test-pw\n", password)
        assertTrue(
            "StandardInput=file:/etc/networkhelm/openconnect/home.passwd\n" in unit &&
                "ExecStart=/usr/sbin/openconnect --non-inter --passwd-on-stdin --protocol=anyconnect --user=nh-oc --servercert=$pin --interface=oc-home vpn.example.net\n" in unit,
            unit,
        )
        assertTrue(host.ran.any { it.startsWith("umask 077; mkdir -p '/etc/systemd/system'") && it.endsWith("\nsystemctl daemon-reload 2>&1") }, "${host.ran}")
        assertEquals("systemctl enable networkhelm-openconnect-home.service", host.ran.last())
        assertTrue(host.sent.none { "rd-test-pw" in it }, "the password is on no command line")
        val none = assertFailsWith<DeviceException> { session.add(connections, mapOf("name" to "lab", "server" to "vpn.example.net", "user" to "nh-oc")) }
        assertEquals("password: a value is needed", none.message)
    }

    // the reply of a new server of ocserv as its window gives it
    private val reply = "ocserv: OpenConnect\nserver: vpn.example.net:8443\npin: $pin\nlogin: client1\npassword: rd-test-first\n" +
        "openconnect --protocol=anyconnect --user=client1 --servercert $pin vpn.example.net:8443"

    @Test
    fun aConnectionIsImportedFromTheReplyOfItsServerWithThePasswordThroughTheInputAndComesUpUnderTheGuard() = runTest {
        val importing = MenuPath.parse("vpn/openconnect/import")
        assertEquals(listOf("client", "true"), SshHostSession(host(), device, linux = true).get(importing, null)?.values.orEmpty().let { listOf(it["name"], it["autostart"]) })

        val home = "--nh-openconnect-- home inactive enabled\n" +
            "/usr/sbin/openconnect --non-inter --passwd-on-stdin --protocol=anyconnect --user=client1 --servercert=$pin --interface=oc-home vpn.example.net:8443\n"
        val host = VpnHost(host().answers, mapOf("for f in /etc/systemd/system/networkhelm-openconnect-" to listOf(ok(state("inactive")), ok(state("inactive") + home))))
        val session = SshHostSession(host, device, linux = true)
        assertEquals("home", session.run(importing, "import", mapOf("name" to "home", "autostart" to "true", "text" to reply)).toList().single()["name"])
        val (password, unit) = host.given
        assertEquals("rd-test-first\n", password)
        assertTrue("ExecStart=/usr/sbin/openconnect --non-inter --passwd-on-stdin --protocol=anyconnect --user=client1 --servercert=$pin --interface=oc-home vpn.example.net:8443\n" in unit, unit)
        assertTrue("systemctl enable networkhelm-openconnect-home.service" in host.ran && host.ran.any { it.startsWith("B=") }, "${host.ran}")
        assertTrue(host.ran.any { it.startsWith("systemctl start networkhelm-openconnect-home.service && for i in") }, "${host.ran}")
        assertTrue(host.sent.none { "rd-test-first" in it }, "the password is on no command line")

        val bare = assertFailsWith<DeviceException> { session.run(importing, "import", mapOf("name" to "lab", "text" to "server: vpn.example.net:8443")).toList() }
        assertEquals("user: a value is needed", bare.message)
        assertEquals(2, host.given.size, "a refused import writes no file")
    }

    @Test
    fun aStartGoesUnderTheGuardAndWaitsForTheDeviceAndAStopKeepsThisSession() = runTest {
        val host = host()
        SshHostSession(host, device, linux = true).run(connections, "up", mapOf("numbers" to "office")).toList()
        assertTrue("systemctl stop networkhelm-openconnect-office.service" in host.ran.single { it.startsWith("B=") }, "${host.ran}")
        val start = host.ran.single { it.startsWith("systemctl start ") }
        assertTrue(start.startsWith("systemctl start networkhelm-openconnect-office.service && for i in \$(seq 20); do ip link show oc-office"), start)

        val journal = "Failed to obtain WebVPN cookie\nopenconnect: Login failed.\n"
        val refused = host("inactive", "systemctl start" to ExecResult("", journal, 1))
        val failed = assertFailsWith<DeviceException> { SshHostSession(refused, device, linux = true).run(connections, "up", mapOf("numbers" to "office")).toList() }
        assertEquals(journal.trim(), failed.message)
        assertTrue(refused.ran.last().startsWith("kill 4242; sh -c 'systemctl stop networkhelm-openconnect-office.service'"), refused.ran.last())

        val running = host("active")
        SshHostSession(running, device, linux = true).run(connections, "down", mapOf("numbers" to "office")).toList()
        assertEquals("systemctl stop networkhelm-openconnect-office.service", running.ran.last())
        val over = host("active", "echo \"\$SSH_CONNECTION\"; ip -o addr show" to ok("10.10.0.2 50000 10.10.0.9 22\n7: oc-office    inet 10.10.0.9/32 scope global oc-office\n"))
        val kept = assertFailsWith<DeviceException> { SshHostSession(over, device, linux = true).run(connections, "down", mapOf("numbers" to "office")).toList() }
        assertEquals("this session goes over oc-office, and it would end with it", kept.message)
    }
}
