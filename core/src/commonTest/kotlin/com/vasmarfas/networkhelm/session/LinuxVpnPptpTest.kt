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

class LinuxVpnPptpTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private val pptp = MenuPath.parse("vpn/pptp")

    // pptpd.conf and pptpd-options of pptpd 1.4.0 of Debian 12 as the lab showed them on 18.11, with addresses and DNS as an administrator
    // sets them
    private val conf = "option /etc/ppp/pptpd-options\nlogwtmp\nlocalip 192.168.0.1\nremoteip 192.168.0.234-238,192.168.0.245\n"
    private val options = "name pptpd\nrefuse-pap\nrefuse-chap\nrefuse-mschap\nrequire-mschap-v2\nrequire-mppe-128\nms-dns 1.1.1.1\nproxyarp\nnodefaultroute\nlock\n"
    private val state = "--nh-pptp-- active enabled\n$conf--nh-pptp-options-- /etc/ppp/pptpd-options\n$options--nh-pptp-users-- pptpd\nalice\nbob off\n"

    private fun host() = VpnHost(
        listOf(
            "for t in" to ok("pptpd\nsystemctl\n"), "id -u" to ok("0\n"), "c=/etc/pptpd.conf" to ok(state), "cat /etc/pptpd.conf" to ok("# the local address\n$conf"),
            "cat '/etc/ppp/pptpd-options'" to ok(options), "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "systemctl" to ok(), "f=/etc/ppp" to ok(),
        ),
    )

    @Test
    fun aServerFoundIsShownAndChangedOnlyWhenItsWeaknessIsKnown() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        val (server) = session.rows("vpn/pptp")
        assertEquals(
            listOf("active", "true", "192.168.0.1", "192.168.0.234-238,192.168.0.245", "1.1.1.1", "true", "pptpd", "2"),
            listOf("state", "autostart", "local-ip", "remote-ip", "dns", "mppe", "name", "users").map { server[it] },
        )
        val refused = assertFailsWith<DeviceException> { session.set(pptp, "pptp", mapOf("dns" to "9.9.9.9")) }
        assertEquals("PPTP gives the logins and traffic of its users to anyone who records them: tick Insecure, Change It", refused.message)
        assertTrue(host.given.isEmpty())

        session.set(pptp, "pptp", mapOf("remote-ip" to "192.168.0.100-120", "dns" to "9.9.9.9, 8.8.8.8", "insecure" to "yes"))
        val (written, pppd) = host.given
        assertEquals("# the local address\noption /etc/ppp/pptpd-options\nlogwtmp\nlocalip 192.168.0.1\nremoteip 192.168.0.100-120\n", written)
        assertTrue("require-mppe-128\nms-dns 9.9.9.9\nms-dns 8.8.8.8\nproxyarp\n" in pppd, pppd)
        assertEquals("systemctl restart pptpd", host.ran.last())
    }

    // the scan of a host with its default route on eth0, written to the format of the lab on 18.11
    private val scan = "eth0\nlo\n--nh-udp--\n--nh-ip--\n2: eth0    inet 192.168.10.250/24 brd 192.168.10.255 scope global eth0\\       valid_lft forever preferred_lft forever\n" +
        "default via 192.168.10.1 dev eth0 \n192.168.10.0/24 dev eth0 proto kernel scope link src 192.168.10.250 \n--nh-default--\ndefault via 192.168.10.1 dev eth0 \n"

    private val wizard = MenuPath.parse("vpn/pptp/server")

    @Test
    fun aNewServerIsProposedWithItsWeaknessUnticked() = runTest {
        val proposed = SshHostSession(VpnHost(listOf("ls /sys/class/net" to ok(scan)) + host().answers), device, linux = true).get(wizard, null)?.values.orEmpty()
        assertEquals(
            listOf("", "192.168.10.250", "10.8.0.1", "10.8.0.10-254", "1.1.1.1, 9.9.9.9", "true", "true", "client1"),
            listOf("insecure", "client-endpoint", "local-ip", "remote-ip", "dns", "nat", "autostart", "user").map { proposed[it] },
        )
    }

    @Test
    fun aServerOfOneStepIsMadeOnlyWhenItsWeaknessIsTickedAndGivesItsFirstUserWithThePasswordOnlyThroughTheInput() = runTest {
        val values = mapOf(
            "client-endpoint" to "203.0.113.5", "autostart" to "true", "local-ip" to "10.8.0.1", "remote-ip" to "10.8.0.10-254", "dns" to "1.1.1.1, 9.9.9.9", "nat" to "true",
            "user" to "client1", "password" to "rd-test-first",
        )
        val unticked = host()
        val refused = assertFailsWith<DeviceException> { SshHostSession(unticked, device, linux = true).run(wizard, "create", values).toList() }
        assertEquals(
            "PPTP gives the logins and traffic of its users to anyone who records them, as MS-CHAPv2 is broken in hours: tick Insecure, Create It",
            refused.message,
        )
        assertTrue(unticked.given.isEmpty())

        val after = state.replace("localip 192.168.0.1\nremoteip 192.168.0.234-238,192.168.0.245", "localip 10.8.0.1\nremoteip 10.8.0.10-254")
        val host = VpnHost(
            listOf("f=/etc/sysctl.d" to ok(), "if [ -e" to ok(), "if systemctl is-active" to ok(), "umask 077; f=/etc/ppp" to ok()) + host().answers,
            mapOf("c=/etc/pptpd.conf" to listOf(ok(state), ok(state), ok(after))),
        )
        val reply = SshHostSession(host, device, linux = true).run(wizard, "create", values + ("insecure" to "yes")).toList()
        val (conf, pppd, hook, line) = host.given
        assertEquals("# the local address\noption /etc/ppp/pptpd-options\nlogwtmp\nlocalip 10.8.0.1\nremoteip 10.8.0.10-254\n", conf)
        assertTrue("require-mppe-128\nms-dns 1.1.1.1\nms-dns 9.9.9.9\nproxyarp\n" in pppd, pppd)
        val (up, down) = deviceNat("pptp", listOf("10.8.0.0/24"), "ppp", iptables = false)
        assertEquals("[Service]\nExecStartPost=-/bin/sh -c \"$up\"\nExecStopPost=-/bin/sh -c \"$down\"\n", hook)
        assertEquals("\"client1\" pptpd \"rd-test-first\" *\n", line)
        val started = host.ran.indexOfFirst { it.startsWith("systemctl restart pptpd; sleep 3; if systemctl is-active -q pptpd") }
        assertTrue("systemctl enable pptpd" in host.ran && started in 0 until host.ran.indexOfFirst { it.startsWith("umask 077; f=/etc/ppp") }, "${host.ran}")
        assertEquals("PPTP\nserver: 203.0.113.5\nlogin: client1\npassword: rd-test-first", reply.first()["conf"])
        assertEquals("pptp.ps1", reply.last()["file"])
        assertTrue("-ServerAddress '203.0.113.5' -TunnelType Pptp -EncryptionLevel Required -AuthenticationMethod MSChapv2" in reply.last()["conf"].orEmpty(), reply.last()["conf"])
        assertTrue(host.sent.none { "rd-test-first" in it }, "the password is on no command line")
    }

    // the reply of a new server and a connection made by the import as the read of the connections prints it
    private val reply = "PPTP\nserver: 192.168.10.250\nlogin: client1\npassword: rd-test-first"
    private val home = "--nh-pptp-client-- home inactive enabled\nserver = 192.168.10.250\nuser = client1\n"

    @Test
    fun aConnectionIsImportedFromTheReplyOfItsServerWithThePasswordThroughTheInputAndComesUpUnderTheGuard() = runTest {
        val importing = MenuPath.parse("vpn/pptp/import")
        val answers = listOf(
            // the login of Ubuntu 24.04 finds pptp and not pppd, which runs for root and the group dip alone
            "for t in" to ok("pptp\nsystemctl\n"), "for f in /etc/systemd/system/networkhelm-pptp-" to ok(), "command -v pptp" to ok("/usr/sbin/pptp\n"),
            "command -v pppd" to ok("/usr/sbin/pppd\n"), "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "kill" to ok(), "umask 077; f=/etc/ppp" to ok(),
        ) + host().answers
        val host = VpnHost(answers, mapOf("for f in /etc/systemd/system/networkhelm-pptp-" to listOf(ok(), ok(home))))
        val session = SshHostSession(host, device, linux = true)
        assertEquals(listOf("client", "true"), SshHostSession(VpnHost(answers), device, linux = true).get(importing, null)?.values.orEmpty().let { listOf(it["name"], it["autostart"]) })
        assertEquals("home", session.run(importing, "import", mapOf("name" to "home", "autostart" to "true", "text" to reply)).toList().single()["name"])
        val (peer, removed, line, unit) = host.given
        assertTrue(
            listOf("pty \"/usr/sbin/pptp 192.168.10.250 --nolaunchpppd\"", "name client1", "remotename networkhelm-pptp-home", "ifname pptp-home", "require-mppe-128")
                .all { it in peer.lines() },
            peer,
        )
        assertEquals(listOf("\n", "\"client1\" networkhelm-pptp-home \"rd-test-first\" *\n"), listOf(removed, line))
        assertTrue("ExecStart=/usr/sbin/pppd call networkhelm-pptp-home nodetach\n" in unit, unit)
        assertTrue("systemctl enable networkhelm-pptp-home.service" in host.ran && host.ran.any { it.startsWith("B=") }, "${host.ran}")
        assertTrue(host.ran.any { it.startsWith("systemctl start networkhelm-pptp-home.service && for i in") }, "${host.ran}")
        assertTrue(host.sent.none { "rd-test-first" in it }, "the password is on no command line")

        assertEquals(listOf("home", "192.168.10.250", "client1", "pptp-home"), session.rows("vpn/pptp/client").single().let { row -> listOf("name", "server", "user", "interface").map { row[it] } })
        session.remove(MenuPath.parse("vpn/pptp/client"), listOf("home"))
        assertTrue(
            "systemctl disable --now networkhelm-pptp-home.service >/dev/null 2>&1; rm -f '/etc/systemd/system/networkhelm-pptp-home.service' " +
                "'/etc/ppp/peers/networkhelm-pptp-home' && systemctl daemon-reload" in host.ran,
            "${host.ran}",
        )
        assertTrue("-v s='networkhelm-pptp-home' -v list='client1' -v off=gone " in host.ran.last(), host.ran.last())

        val bare = VpnHost(listOf("command -v pptp" to ExecResult("", "", 1)) + answers)
        val none = assertFailsWith<DeviceException> { SshHostSession(bare, device, linux = true).run(importing, "import", mapOf("name" to "lab", "text" to reply)).toList() }
        assertEquals("the host has no pptp: its package is pptp-linux on Debian and Ubuntu and pptp elsewhere", none.message)
    }

    @Test
    fun theSettingsOfPppdAndTheConnectionsOfPptpdAreLinesOfTheirFilesAndComeBack() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        session.set(pptp, "pptp", mapOf("connections" to "50", "mtu" to "1400", "mru" to "1400", "lcp-echo-interval" to "20", "lcp-echo-failure" to "3", "insecure" to "yes"))
        val (written, ppp) = host.given
        assertEquals("# the local address\n${conf}connections 50\n", written)
        assertTrue("ms-dns 1.1.1.1\nproxyarp\nnodefaultroute\nlock\nmtu 1400\nmru 1400\nlcp-echo-interval 20\nlcp-echo-failure 3\n" in ppp, ppp)
        val read = parsePptp(
            state.replace("remoteip 192.168.0.234-238,192.168.0.245\n", "remoteip 192.168.0.234-238,192.168.0.245\nconnections 50\n")
                .replace("nodefaultroute\nlock\n", "nodefaultroute\nlock\nmtu 1400\nmru 1400\nlcp-echo-interval 20\nlcp-echo-failure 3\n"),
        ).row()
        assertEquals(listOf("50", "1400", "1400", "20", "3"), listOf("connections", "mtu", "mru", "lcp-echo-interval", "lcp-echo-failure").map { read?.get(it) })

        // an empty value takes its line away, a number out of the range of pppd goes to no file
        val limited = VpnHost(listOf("cat /etc/pptpd.conf" to ok("# the local address\n${conf}connections 50\n")) + host().answers)
        SshHostSession(limited, device, linux = true).set(pptp, "pptp", mapOf("connections" to "", "insecure" to "yes"))
        assertEquals("# the local address\n$conf", limited.given.single())
        val wide = assertFailsWith<DeviceException> { session.set(pptp, "pptp", mapOf("mtu" to "9000", "insecure" to "yes")) }
        assertEquals("mtu: 9000 is not a value it takes", wide.message)
        assertEquals(2, host.given.size, "a refused value goes to no file")
    }

    @Test
    fun theUsersOfPptpAreLinesOfChapSecretsUnderTheNamePppdTakes() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        assertEquals(listOf("alice" to "pptp", "bob" to "pptp"), session.rows("vpn/user").map { it.id to it["servers"] })
        session.set(MenuPath.parse("vpn/user"), "bob", mapOf("disabled" to "no"))
        assertTrue("-v s='pptpd' -v list='bob' -v off=no " in host.ran.last(), host.ran.last())
    }
}
