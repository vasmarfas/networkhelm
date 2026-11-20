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

class LinuxVpnOcservTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private val ocserv = MenuPath.parse("vpn/ocserv")
    private val users = MenuPath.parse("vpn/user")

    // ocserv.conf of the package of ocserv 1.1.6 on Debian 12, as the lab showed it on 17.11 without its comments, in part
    private val conf = listOf(
        "auth = \"plain[passwd=/etc/ocserv/passwd]\"", "tcp-port = 443", "udp-port = 443", "run-as-user = ocserv", "socket-file = /run/ocserv-socket",
        "isolate-workers = true", "max-clients = 16", "max-same-clients = 2", "keepalive = 32400", "dpd = 90", "mobile-dpd = 1800", "use-occtl = true",
        "device = vpns", "default-domain = example.com", "ipv4-network = 192.168.1.0", "ipv4-netmask = 255.255.255.0", "dns = 192.168.1.1",
        "route = 10.0.0.0/8", "route = 172.16.0.0/12", "route = 192.168.0.0/16", "cisco-client-compat = true",
    ).joinToString("\n")

    // the read: the user of the lab locked with ocpasswd -l, which the host gives without its hash
    private val state = "--nh-ocserv-- inactive disabled\n$conf\n--nh-ocserv-users-- /etc/ocserv/passwd\nnh-oc:*:locked\nalice:*:\n" +
        "--nh-ocserv-name-- subject=CN=vpn.example.net\n--nh-ocserv-pin-- rdTestPinAAAA=\n"

    private fun host(vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok("ocserv\nocpasswd\nocctl\nsystemctl\n"), "id -u" to ok("0\n"), "c=/etc/ocserv/ocserv.conf" to ok(state), "cat /etc/ocserv/ocserv.conf" to ok("$conf\n"),
            "umask 077; command -v openssl" to ok(), "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "ocpasswd" to ok(), "occtl disconnect" to ok(),
        ),
    )

    @Test
    fun theSettingsComeFromOcservConfAndTheUsersWithoutTheirHashes() = runTest {
        val session = SshHostSession(host(), device, linux = true)
        val row = session.rows("vpn/ocserv").single()
        assertEquals(
            listOf("inactive", "false", "443", "192.168.1.0/24", "192.168.1.1", "10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16", "16", "2", "vpn.example.net", "rdTestPinAAAA=", "2"),
            listOf("state", "autostart", "tcp-port", "network", "dns", "routes", "max-clients", "max-same-clients", "server-name", "pin", "users").map { row[it] },
        )
        val people = session.rows("vpn/user").associateBy { it.id }
        assertEquals(listOf("true", "false"), listOf("nh-oc", "alice").map { people.getValue(it)["disabled"] })
    }

    @Test
    fun aNameMakesACertificateAndTheSettingsGoThroughTheTestOfOcserv() = runTest {
        val host = host()
        SshHostSession(host, device, linux = true).set(ocserv, "ocserv", mapOf("server-name" to "vpn.example.net", "network" to "10.20.0.0/24", "dns" to "1.1.1.1, 9.9.9.9"))
        val made = host.ran.single { it.startsWith("umask 077; command -v openssl") }
        assertTrue("-subj '/CN=vpn.example.net' -addext 'subjectAltName=DNS:vpn.example.net'" in made, made)
        val written = host.given.single().lines()
        val wanted = listOf(
            "ipv4-network = 10.20.0.0", "ipv4-netmask = 255.255.255.0", "dns = 1.1.1.1", "dns = 9.9.9.9", "server-cert = /etc/ocserv/networkhelm-server.crt",
            "server-key = /etc/ocserv/networkhelm-server.key", "route = 10.0.0.0/8",
        )
        assertEquals(wanted, wanted.filter { it in written })
        assertTrue(host.ran.any { it.endsWith("\nocserv --test-config -c /etc/ocserv/ocserv.conf 2>&1") }, "${host.ran}")
    }

    @Test
    fun aUserOfOcservGetsItsPasswordThroughTheInputOfOcpasswdAndIsLockedWhenOff() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        assertEquals("bob", session.add(users, mapOf("name" to "bob", "servers" to "ocserv", "password" to "rd-test-bob", "disabled" to "yes")))
        assertEquals("rd-test-bob\nrd-test-bob\n", host.given.single())
        assertTrue(host.ran.any { it == "ocpasswd -c '/etc/ocserv/passwd' 'bob' >/dev/null" }, "${host.ran}")
        assertEquals("ocpasswd -c '/etc/ocserv/passwd' -l 'bob'", host.ran.last())
        assertTrue(host.sent.none { "rd-test-bob" in it }, "the password is on no command line")
        val none = assertFailsWith<DeviceException> { session.add(users, mapOf("name" to "carol", "servers" to "ocserv")) }
        assertEquals("password: a value is needed", none.message)

        session.set(users, "alice", mapOf("disabled" to "yes"))
        assertEquals(listOf("ocpasswd -c '/etc/ocserv/passwd' -l 'alice'", "occtl disconnect user 'alice' >/dev/null 2>&1; true"), host.ran.takeLast(2))
        session.remove(users, listOf("nh-oc"))
        assertEquals("ocpasswd -c '/etc/ocserv/passwd' -d 'nh-oc' && { occtl disconnect user 'nh-oc' >/dev/null 2>&1; true; }", host.ran.last())
        val text = session.run(users, "client-config", mapOf("number" to "alice")).toList().single()["conf"]
        assertEquals(
            "ocserv: OpenConnect\nserver: vpn.example.net:443\npin: pin-sha256:rdTestPinAAAA=\nlogin: alice\n" +
                "openconnect --protocol=anyconnect --user=alice --servercert pin-sha256:rdTestPinAAAA= vpn.example.net:443",
            text,
        )
    }

    // the scan of a host with its default route on eth0, written to the format of the lab on 18.11, and a listening socket of TCP on 443
    private val scan = "eth0\nlo\n--nh-udp--\n--nh-ip--\n2: eth0    inet 192.168.10.250/24 brd 192.168.10.255 scope global eth0\\       valid_lft forever preferred_lft forever\n" +
        "default via 192.168.10.1 dev eth0 \n192.168.10.0/24 dev eth0 proto kernel scope link src 192.168.10.250 \n--nh-default--\ndefault via 192.168.10.1 dev eth0 \n"
    private val tcp = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n" +
        "   0: 00000000:01BB 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 31337 1 0000000000000000 100 0 0 10 0\n"

    private val wizard = MenuPath.parse("vpn/ocserv/server")

    @Test
    fun aNewServerIsProposedAroundWhatTheHostHas() = runTest {
        val proposed = SshHostSession(host("ls /sys/class/net" to ok(scan), "cat /proc/net/tcp" to ok(tcp)), device, linux = true).get(wizard, null)?.values.orEmpty()
        assertEquals(
            listOf("192.168.10.250", "8443", "10.8.0.0/24", "1.1.1.1, 9.9.9.9", "default", "true", "true", "client1"),
            listOf("client-endpoint", "port", "network", "dns", "routes", "nat", "autostart", "user").map { proposed[it] },
        )
    }

    @Test
    fun aServerOfOneStepTakesTheConfigurationOfThePackageAndGivesItsFirstUserWithThePasswordOnlyThroughTheInput() = runTest {
        val after = state.replace("tcp-port = 443\nudp-port = 443", "tcp-port = 8443\nudp-port = 8443").replace("ipv4-network = 192.168.1.0", "ipv4-network = 10.8.0.0")
        val host = VpnHost(
            host("umask 077; [ -e" to ok(), "f=/etc/sysctl.d" to ok(), "if [ -e" to ok(), "if systemctl is-active" to ok(), "systemctl" to ok()).answers,
            mapOf("c=/etc/ocserv/ocserv.conf" to listOf(ok(state), ok(after))),
        )
        val values = mapOf(
            "client-endpoint" to "vpn.example.net", "port" to "8443", "autostart" to "true", "network" to "10.8.0.0/24", "dns" to "1.1.1.1, 9.9.9.9", "routes" to "default",
            "nat" to "true", "user" to "client1", "password" to "rd-test-first",
        )
        val reply = SshHostSession(host, device, linux = true).run(wizard, "create", values).toList().single()
        val written = host.given.first().lines()
        val wanted = listOf(
            "auth = \"plain[passwd=/etc/ocserv/passwd]\"", "tcp-port = 8443", "udp-port = 8443", "ipv4-network = 10.8.0.0", "ipv4-netmask = 255.255.255.0", "dns = 1.1.1.1",
            "dns = 9.9.9.9", "route = default", "server-cert = /etc/ocserv/networkhelm-server.crt", "server-key = /etc/ocserv/networkhelm-server.key", "run-as-user = ocserv",
        )
        assertEquals(wanted, wanted.filter { it in written })
        assertTrue("route = 10.0.0.0/8" !in written, "$written")
        val (up, down) = deviceNat("ocserv", listOf("10.8.0.0/24"), "vpns", iptables = false)
        assertEquals(
            "nft add table inet networkhelm-nat-ocserv; nft add chain inet networkhelm-nat-ocserv nat '{ type nat hook postrouting priority 100; }'; " +
                "nft add rule inet networkhelm-nat-ocserv nat ip saddr 10.8.0.0/24 oifname != 'vpns*' masquerade",
            up,
        )
        assertEquals(listOf("[Service]\nExecStartPost=-/bin/sh -c \"$up\"\nExecStopPost=-/bin/sh -c \"$down\"\n", "rd-test-first\nrd-test-first\n"), host.given.drop(1))
        val started = host.ran.indexOfFirst { it.startsWith("systemctl restart ocserv; sleep 5; if systemctl is-active -q ocserv") }
        assertTrue("systemctl enable ocserv" in host.ran && started in 0 until host.ran.indexOfFirst { it.startsWith("ocpasswd") }, "${host.ran}")
        assertEquals(
            "ocserv: OpenConnect\nserver: vpn.example.net:8443\npin: pin-sha256:rdTestPinAAAA=\nlogin: client1\npassword: rd-test-first\n" +
                "openconnect --protocol=anyconnect --user=client1 --servercert pin-sha256:rdTestPinAAAA= vpn.example.net:8443",
            reply["conf"],
        )
        assertEquals("ocserv", reply["name"])
        assertTrue(host.sent.none { "rd-test-first" in it }, "the password is on no command line")

        assertEquals(
            "iptables -w -t nat -A POSTROUTING -s 10.8.0.0/24 ! -o vpns+ -j MASQUERADE; iptables -w -I FORWARD -i vpns+ -j ACCEPT; " +
                "iptables -w -I FORWARD -o vpns+ -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT",
            deviceNat("ocserv", listOf("10.8.0.0/24"), "vpns", iptables = true).first,
        )
    }

    @Test
    fun aServerOfOneStepStopsBeforeItsCertificateWhereItsUserIsTakenOrNothingKeepsItsNat() = runTest {
        val taken = host()
        val user = assertFailsWith<DeviceException> { SshHostSession(taken, device, linux = true).run(wizard, "create", mapOf("client-endpoint" to "vpn.example.net", "user" to "alice")).toList() }
        assertEquals("the host has a VPN user alice already", user.message)
        val bare = VpnHost(listOf("for t in" to ok("ocserv\nocpasswd\nocctl\n")) + host().answers)
        val nat = assertFailsWith<DeviceException> { SshHostSession(bare, device, linux = true).run(wizard, "create", mapOf("client-endpoint" to "vpn.example.net", "nat" to "true")).toList() }
        assertEquals("NAT for the clients of ocserv needs ufw, firewalld or systemd", nat.message)
        assertTrue(listOf(taken, bare).all { tried -> tried.ran.none { it.startsWith("umask 077; command -v openssl") } })
    }

    // the users occtl -j show users gives, in the form of its source for ocserv 1.1, not recorded: the lab has no /dev/net/tun for a client
    @Test
    fun theSessionsOfOcservComeFromOcctlAndEndByTheirId() = runTest {
        val users = "[\n  {\n    \"ID\":  12,\n    \"Username\":  \"alice\",\n    \"Remote IP\":  \"203.0.113.7\",\n    \"IPv4\":  \"10.20.0.5\",\n" +
            "    \"User-Agent\":  \"Open AnyConnect VPN Agent v9.01\",\n    \"RX\":  \"4096\",\n    \"TX\":  \"8192\",\n    \"raw_connected_at\":  1794939036,\n" +
            "    \"DTLS cipher\":  \"(DTLS1.2)-(ECDHE-ECDSA)-(AES-256-GCM)\"\n  }\n]\n"
        val host = VpnHost(
            listOf(
                "for t in" to ok("ocserv\nocctl\n"), "id -u" to ok("0\n"),
                "echo \"--nh-now--" to ok("--nh-now-- 1794939096\n--nh-zone-- +0300\n--nh-oc-ports-- 4443 4443\n--nh-oc-users--\n$users"),
                "occtl disconnect" to ok(),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        val alice = session.rows("vpn/session").single()
        assertEquals(
            listOf("ocserv/12", "OpenConnect", "alice", "203.0.113.7", "10.20.0.5", "2026-11-17 21:10:36", "1m", "4096", "8192", "(DTLS1.2)-(ECDHE-ECDSA)-(AES-256-GCM)"),
            listOf(alice.id) + listOf("kind", "user", "endpoint", "address", "since", "uptime", "rx", "tx", "cipher").map { alice[it] },
        )
        assertEquals(listOf("4443", "tcp,udp", "1"), session.rows("vpn/service").single().let { listOf(it["port"], it["protocol"], it["sessions"]) })
        session.run(MenuPath.parse("vpn/session"), "disconnect", mapOf("numbers" to "ocserv/12")).toList()
        assertEquals("occtl disconnect id 12 >/dev/null", host.ran.last())
    }
}
