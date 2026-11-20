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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxVpnL2tpTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private val l2tp = MenuPath.parse("vpn/l2tp")
    private val users = MenuPath.parse("vpn/user")

    // a read of a server made here as the read script prints it, written to the formats of xl2tpd 1.3.18 and pppd 2.4.9 of Debian 12: the
    // container of the lab has no /dev/ppp, so no client got through
    private val made = "--nh-l2tp-- active enabled\n[lns default]\nip range = 10.20.30.10-10.20.30.254\nlocal ip = 10.20.30.1\nrequire chap = yes\n" +
        "refuse pap = yes\nrequire authentication = yes\nname = networkhelm-l2tp\npppoptfile = /etc/ppp/options.networkhelm-l2tp\nlength bit = yes\n" +
        "--nh-l2tp-ppp--\nname networkhelm-l2tp\nrequire-mschap-v2\nms-dns 1.1.1.1\nmtu 1280\nmru 1280\nlcp-echo-interval 30\nlcp-echo-failure 4\nnoccp\n" +
        "--nh-l2tp-users--\nalice\nbob off\n--nh-l2tp-ipsec-- 1\n"

    // the IPsec of L2TP as the read of strongSwan shows it, its secret masked on the host
    private val swan = "--nh-loaded--\nnetworkhelm-l2tp\n--nh-file-- /etc/swanctl/conf.d/networkhelm-l2tp.conf\nconnections {\n    networkhelm-l2tp {\n" +
        "        version = 1\n    }\n}\nsecrets {\n    ike-networkhelm-l2tp {\n        secret = (hidden)\n    }\n}\n--nh-unit-- strongswan.service active\n"

    private fun host(state: String, tools: String = "xl2tpd\nswanctl\nsystemctl\n", vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok(tools), "id -u" to ok("0\n"), "echo \"--nh-l2tp--" to ok(state), "echo --nh-loaded--" to ok(swan),
            "umask 077; mkdir -p" to ok("loaded connection 'networkhelm-l2tp'\nsuccessfully loaded 1 connections, 0 unloaded\n"), "rm -f" to ok(),
            "head -c 15 /dev/urandom" to ok("rdTestMade\n"), "systemctl" to ok(), "umask 077; f=/etc/ppp" to ok(), "f=/etc/ppp" to ok(),
            "echo \"\$SSH_CONNECTION\"" to ok("192.168.10.2 50000 192.168.10.250 22\n"),
            "cat '/etc/swanctl/conf.d/networkhelm-l2tp.conf'" to ok("connections {\n}\nsecrets {\n    ike-networkhelm-l2tp {\n        secret = \"rd-test-psk\"\n    }\n}\n"),
        ),
    )

    @Test
    fun theServerShowsTheNamesOfItsUsersAloneAndAFileNotMadeHereStays() = runTest {
        val (server) = SshHostSession(host(made + "--nh-nat-- networkhelm-nat-l2tp\n"), device, linux = true).rows("vpn/l2tp")
        assertEquals(
            listOf("active", "true", "10.20.30.1", "10.20.30.10-10.20.30.254", "1.1.1.1", "1280", "", "true", "2", "true", "NetworkHelm"),
            listOf("state", "autostart", "local-ip", "pool", "dns", "mtu", "psk", "ipsec-loaded", "users", "nat", "kept").map { server[it] },
        )
        // the file Fedora puts in its package of xl2tpd
        val fedora = "--nh-l2tp-- inactive disabled\n[lns default]\nip range = 192.168.1.128-192.168.1.254\nlocal ip = 192.168.1.99\nname = LinuxVPNserver\n" +
            "--nh-l2tp-ppp--\n--nh-l2tp-users--\n"
        val other = assertFailsWith<DeviceException> { SshHostSession(host(fedora), device, linux = true).set(l2tp, "l2tp", mapOf("dns" to "9.9.9.9")) }
        assertEquals("/etc/xl2tpd/xl2tpd.conf is no file of NetworkHelm, and it stays as it is", other.message)

        assertEquals("10.20.30.0/24", l2tpNetwork("10.20.30.1", "10.20.30.10-10.20.30.254"))
        assertNull(l2tpNetwork("10.20.30.50", "10.20.30.10-10.20.30.254"))
        assertNull(l2tpNetwork("1.1.1.1", "200.0.0.1-200.0.0.9"))
    }

    @Test
    fun theFirstSettingsMakeTheServerAndItsIpsecWithAKeyTheHostMakes() = runTest {
        val host = host("--nh-l2tp-- active enabled\n--nh-l2tp-ppp--\n--nh-l2tp-users--\n")
        val session = SshHostSession(host, device, linux = true)
        session.set(l2tp, "l2tp", mapOf("dns" to "9.9.9.9"))
        val (ppp, conf, ipsec) = host.given
        assertEquals(
            "# pppd of the L2TP server of NetworkHelm\nname networkhelm-l2tp\nrequire-mschap-v2\nms-dns 9.9.9.9\nmtu 1280\nmru 1280\nlcp-echo-interval 30\n" +
                "lcp-echo-failure 4\nnoccp\n",
            ppp,
        )
        assertTrue("[lns default]\nip range = 10.20.30.10-10.20.30.254\nlocal ip = 10.20.30.1\n" in conf && "\nname = networkhelm-l2tp\n" in conf, conf)
        assertTrue(
            listOf("version = 1", "mode = transport", "local_ts = dynamic[udp/1701]", "aes256-sha1-modp1024", "secret = \"rdTestMade\"").all { it in ipsec },
            ipsec,
        )
        assertTrue(host.ran.any { it.startsWith("umask 077; mkdir -p '/etc/swanctl/conf.d'") && it.endsWith("\nswanctl --load-all 2>&1") }, "${host.ran}")
        assertTrue("systemctl restart xl2tpd" in host.ran, "${host.ran}")
        assertTrue(host.sent.none { "rdTestMade" in it }, "the key is on no command line")

        val kept = assertFailsWith<DeviceException> { session.set(MenuPath.parse("vpn/ipsec"), "networkhelm-l2tp", mapOf("dns" to "9.9.9.9")) }
        assertEquals("networkhelm-l2tp is the IPsec of L2TP, which its window keeps", kept.message)
    }

    @Test
    fun aUserOfL2tpIsALineOfChapSecretsUnderTheNameOfTheServer() = runTest {
        val host = host(made, "xl2tpd\nsystemctl\n")
        val session = SshHostSession(host, device, linux = true)
        assertEquals(listOf("alice" to "false", "bob" to "true"), session.rows("vpn/user").map { it.id to it["disabled"] })
        session.add(users, mapOf("name" to "carol", "servers" to "l2tp", "password" to "rd-test \"pw\""))
        assertEquals("\"carol\" networkhelm-l2tp \"rd-test \\\"pw\\\"\" *\n", host.given.single())
        assertTrue(host.sent.none { "rd-test" in it }, "the password is on no command line")

        session.set(users, "bob", mapOf("disabled" to "no"))
        val on = host.ran.last()
        assertTrue(on.startsWith("f=/etc/ppp/chap-secrets; [ -f \"\$f\" ] || exit 0;") && "-v s='networkhelm-l2tp' -v list='bob' -v off=no " in on, on)
        session.remove(users, listOf("alice"))
        assertTrue("-v list='alice' -v off=gone " in host.ran.last(), host.ran.last())

        val conf = session.run(users, "client-config", mapOf("number" to "alice")).toList().single()["conf"]
        assertEquals("L2TP/IPsec\nserver: 192.168.10.250\npreshared key: rd-test-psk\nlogin: alice", conf)
    }

    @Test
    fun theLcpEchoAndIdleOfPppdComeFromTheFormAndBack() = runTest {
        val host = host(made)
        val session = SshHostSession(host, device, linux = true)
        assertEquals(listOf("30", "4", ""), session.rows("vpn/l2tp").single().let { row -> listOf("lcp-echo-interval", "lcp-echo-failure", "idle").map { row[it] } })
        session.set(l2tp, "l2tp", mapOf("lcp-echo-interval" to "10", "lcp-echo-failure" to "6", "idle" to "1800"))
        assertEquals(
            "# pppd of the L2TP server of NetworkHelm\nname networkhelm-l2tp\nrequire-mschap-v2\nms-dns 1.1.1.1\nmtu 1280\nmru 1280\nlcp-echo-interval 10\nlcp-echo-failure 6\n" +
                "idle 1800\nnoccp\n",
            host.given.first(),
        )
        val back = parseL2tp(made.replace("lcp-echo-interval 30\nlcp-echo-failure 4\n", "lcp-echo-interval 10\nlcp-echo-failure 6\nidle 1800\n")).row()
        assertEquals(listOf("10", "6", "1800"), listOf("lcp-echo-interval", "lcp-echo-failure", "idle").map { back[it] })
        val refused = assertFailsWith<DeviceException> { session.set(l2tp, "l2tp", mapOf("idle" to "forever")) }
        assertEquals("idle: forever is not a value it takes", refused.message)
        assertEquals(2, host.given.size, "a refused value goes to no file")
    }

    // in the lab on 18.11 the SA of a client of IKEv1 outlived xl2tpd that stopped
    @Test
    fun stoppingTheServerEndsTheSasOfItsIpsec() = runTest {
        val host = host(made)
        SshHostSession(host, device, linux = true).run(l2tp, "down", emptyMap()).toList()
        assertEquals("systemctl stop xl2tpd && ${ikeTerminate("networkhelm-l2tp")}", host.ran.last())
    }

    private val empty = "--nh-l2tp-- inactive disabled\n--nh-l2tp-ppp--\n--nh-l2tp-users--\n"

    // the scan of a host with its default route on eth0, written to the format of the lab on 18.11
    private val scan = "eth0\nlo\n--nh-udp--\n--nh-ip--\n2: eth0    inet 192.168.10.250/24 brd 192.168.10.255 scope global eth0\\       valid_lft forever preferred_lft forever\n" +
        "default via 192.168.10.1 dev eth0 \n192.168.10.0/24 dev eth0 proto kernel scope link src 192.168.10.250 \n--nh-default--\ndefault via 192.168.10.1 dev eth0 \n"

    private val wizard = MenuPath.parse("vpn/l2tp/server")

    @Test
    fun aNewServerIsProposedAroundWhatTheHostHas() = runTest {
        val proposed = SshHostSession(host(empty, "xl2tpd\nswanctl\nsystemctl\n", "ls /sys/class/net" to ok(scan)), device, linux = true).get(wizard, null)?.values.orEmpty()
        assertEquals(
            listOf("192.168.10.250", "10.8.0.1", "10.8.0.10-10.8.0.254", "1.1.1.1, 9.9.9.9", "true", "true", "client1"),
            listOf("client-endpoint", "local-ip", "pool", "dns", "nat", "autostart", "user").map { proposed[it] },
        )
    }

    @Test
    fun aServerOfOneStepStartsAndGivesItsFirstUserTheKeyALoginAndProfilesWithThePasswordOnlyThroughTheInput() = runTest {
        val after = "--nh-l2tp-- active enabled\n[lns default]\nip range = 10.8.0.10-10.8.0.254\nlocal ip = 10.8.0.1\nname = networkhelm-l2tp\n--nh-l2tp-ppp--\n" +
            "--nh-l2tp-users--\n--nh-l2tp-ipsec-- 1\n"
        val host = VpnHost(
            host(
                empty, "xl2tpd\nswanctl\nsystemctl\n", "f=/etc/sysctl.d" to ok(), "if [ -e" to ok(), "if systemctl is-active" to ok(),
                "cat /proc/sys/kernel/random/uuid" to ok("uuid-1\nuuid-2\n"),
            ).answers,
            mapOf("echo \"--nh-l2tp--" to listOf(ok(empty), ok(empty), ok(after))),
        )
        val values = mapOf(
            "client-endpoint" to "203.0.113.5", "autostart" to "true", "local-ip" to "10.8.0.1", "pool" to "10.8.0.10-10.8.0.254", "dns" to "1.1.1.1, 9.9.9.9",
            "psk" to "", "nat" to "true", "user" to "client1", "password" to "rd-test-first",
        )
        val reply = SshHostSession(host, device, linux = true).run(wizard, "create", values).toList()
        val (ppp, conf, ipsec, hook, line) = host.given
        assertTrue("ms-dns 1.1.1.1\nms-dns 9.9.9.9\n" in ppp && "ip range = 10.8.0.10-10.8.0.254\nlocal ip = 10.8.0.1\n" in conf, conf)
        assertTrue("secret = \"rdTestMade\"" in ipsec, ipsec)
        val (up, down) = deviceNat("l2tp", listOf("10.8.0.0/24"), "ppp", iptables = false, sealed = "1701")
        assertEquals(
            "nft add table inet networkhelm-nat-l2tp; nft add chain inet networkhelm-nat-l2tp nat '{ type nat hook postrouting priority 100; }'; " +
                "nft add rule inet networkhelm-nat-l2tp nat ip saddr 10.8.0.0/24 oifname != 'ppp*' masquerade; " +
                "nft add chain inet networkhelm-nat-l2tp input '{ type filter hook input priority 0; }'; nft add rule inet networkhelm-nat-l2tp input udp dport 1701 meta ipsec missing drop",
            up,
        )
        assertEquals("[Service]\nExecStartPost=-/bin/sh -c \"$up\"\nExecStopPost=-/bin/sh -c \"$down\"\n", hook)
        assertEquals("\"client1\" networkhelm-l2tp \"rd-test-first\" *\n", line)
        assertTrue(listOf("systemctl enable xl2tpd", "systemctl enable 'strongswan.service'").all { it in host.ran }, "${host.ran}")
        val started = host.ran.indexOfFirst { it.startsWith("systemctl restart xl2tpd; sleep 3; if systemctl is-active -q xl2tpd") }
        assertTrue(started in 0 until host.ran.indexOfFirst { it.startsWith("umask 077; f=/etc/ppp") }, "${host.ran}")

        assertEquals("L2TP/IPsec\nserver: 203.0.113.5\npreshared key: rd-test-psk\nlogin: client1\npassword: rd-test-first", reply.first()["conf"])
        val (apple, windows) = reply.drop(1)
        assertEquals(listOf("l2tp.mobileconfig", "l2tp.ps1"), listOf(apple["file"], windows["file"]))
        assertTrue(
            listOf("<key>VPNType</key><string>L2TP</string>", "<key>CommRemoteAddress</key><string>203.0.113.5</string>", "<key>AuthName</key><string>client1</string>",
                "<key>SharedSecret</key><data>cmQtdGVzdC1wc2s=</data>", "<string>uuid-2</string>").all { it in apple["conf"].orEmpty() },
            apple["conf"],
        )
        assertTrue("Add-VpnConnection -Name 'L2TP 203.0.113.5' -ServerAddress '203.0.113.5' -TunnelType L2tp -L2tpPsk 'rd-test-psk'" in windows["conf"].orEmpty(), windows["conf"])
        assertTrue(reply.drop(1).none { "rd-test-first" in it["conf"].orEmpty() }, "no profile holds the password")
        assertTrue(host.sent.none { "rd-test-first" in it || "rdTestMade" in it }, "the password and the key are on no command line")

        assertEquals(
            "iptables -w -t nat -A POSTROUTING -s 10.8.0.0/24 ! -o ppp+ -j MASQUERADE; iptables -w -I FORWARD -i ppp+ -j ACCEPT; " +
                "iptables -w -I FORWARD -o ppp+ -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; iptables -w -I INPUT -p udp --dport 1701 -m policy --dir in --pol none -j DROP",
            deviceNat("l2tp", listOf("10.8.0.0/24"), "ppp", iptables = true, sealed = "1701").first,
        )
    }

    // the reply of a new server and a connection made by the import as the read of the connections prints it
    private val reply = "L2TP/IPsec\nserver: 192.168.10.250\npreshared key: rd-test-psk\nlogin: client1\npassword: rd-test-first"
    private val home = "--nh-l2tp-client-- home inactive enabled\nport = 1702\nlns = 192.168.10.250\nname = client1\n"

    @Test
    fun aConnectionIsImportedFromTheReplyOfItsServerWithTheKeyAndThePasswordThroughTheInputAndComesUpUnderTheGuard() = runTest {
        val clients = MenuPath.parse("vpn/l2tp/client")
        val importing = MenuPath.parse("vpn/l2tp/import")
        val answers = host(
            empty, "xl2tpd\nswanctl\nsystemctl\n", "for f in /etc/systemd/system/networkhelm-l2tp-" to ok(), "cat /proc/net/udp" to ok(), "command -v xl2tpd" to ok("/usr/sbin/xl2tpd\n"),
            "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "kill" to ok(), "if command -v swanctl" to ok(),
        ).answers
        val host = VpnHost(answers, mapOf("for f in /etc/systemd/system/networkhelm-l2tp-" to listOf(ok(), ok(home))))
        val session = SshHostSession(host, device, linux = true)
        assertEquals(listOf("client", "true"), SshHostSession(VpnHost(answers), device, linux = true).get(importing, null)?.values.orEmpty().let { listOf(it["name"], it["autostart"]) })
        assertEquals("home", session.run(importing, "import", mapOf("name" to "home", "autostart" to "true", "text" to reply)).toList().single()["name"])
        val (ppp, conf, removed, line, ipsec) = host.given
        val unit = host.given[5]
        assertTrue(listOf("name client1", "remotename networkhelm-l2tp-home", "ifname l2tp-home", "refuse-mschap", "nodefaultroute").all { it in ppp.lines() }, ppp)
        assertTrue("[global]\nport = 1702\n[lac home]\nlns = 192.168.10.250\n" in conf, conf)
        assertEquals(listOf("\n", "\"client1\" networkhelm-l2tp-home \"rd-test-first\" *\n"), listOf(removed, line))
        assertTrue(
            listOf("remote_addrs = 192.168.10.250", "local_ts = dynamic[udp/1702]", "remote_ts = dynamic[udp/1701]", "start_action = trap", "secret = \"rd-test-psk\"")
                .all { it in ipsec },
            ipsec,
        )
        assertTrue(
            "ExecStart=/usr/sbin/xl2tpd -D -c /etc/xl2tpd/networkhelm-l2tp-home.conf -C /run/networkhelm-l2tp-home.control -p /run/networkhelm-l2tp-home.pid\n" in unit,
            unit,
        )
        assertTrue("systemctl enable networkhelm-l2tp-home.service" in host.ran && host.ran.any { it.startsWith("B=") }, "${host.ran}")
        assertTrue(host.ran.any { it.startsWith("systemctl start networkhelm-l2tp-home.service && for i in") }, "${host.ran}")
        assertTrue(host.sent.none { "rd-test-first" in it || "rd-test-psk" in it }, "the password and the key are on no command line")

        assertEquals(listOf("home", "192.168.10.250", "client1", "l2tp-home"), session.rows("vpn/l2tp/client").single().let { row -> listOf("name", "server", "user", "interface").map { row[it] } })
        session.remove(clients, listOf("home"))
        assertTrue(
            "systemctl disable --now networkhelm-l2tp-home.service >/dev/null 2>&1; rm -f '/etc/systemd/system/networkhelm-l2tp-home.service' " +
                "'/etc/xl2tpd/networkhelm-l2tp-home.conf' '/etc/ppp/options.networkhelm-l2tp-home' && systemctl daemon-reload" in host.ran,
            "${host.ran}",
        )
        assertTrue(host.ran.last().endsWith("rm -f '/etc/swanctl/conf.d/networkhelm-l2tp-home.conf' && swanctl --load-all >/dev/null"), host.ran.last())

        val server = assertFailsWith<DeviceException> { SshHostSession(host(made), device, linux = true).run(importing, "import", mapOf("name" to "lab", "text" to reply)).toList() }
        assertEquals("the host is a server of L2TP and can be no client of another", server.message)
        val bare = assertFailsWith<DeviceException> { session.run(importing, "import", mapOf("name" to "lab", "text" to reply.substringBefore("\npassword"))).toList() }
        assertEquals("password: a value is needed", bare.message)
    }

    @Test
    fun udp1701TakesOnlyWhatCameThroughIpsec() = runTest {
        val rules = "# rules.before\n*filter\n:ufw-before-input - [0:0]\n:ufw-not-local - [0:0]\n# End required lines\n-A ufw-before-input -i lo -j ACCEPT\nCOMMIT\n"
        val sealed = ufwSealed(rules, "networkhelm-nat-l2tp", listOf("1701/udp"), "ufw-before-input")
        assertEquals(
            "# rules.before\n*filter\n:ufw-before-input - [0:0]\n:ufw-not-local - [0:0]\n# networkhelm-nat-l2tp sealed\n" +
                "-A ufw-before-input -p udp --dport 1701 -m policy --dir in --pol ipsec -j ACCEPT\n# networkhelm-nat-l2tp sealed end\n# End required lines\n" +
                "-A ufw-before-input -i lo -j ACCEPT\nCOMMIT\n",
            sealed,
        )
        assertEquals(rules, ufwSealed(sealed, "networkhelm-nat-l2tp", emptyList(), "ufw-before-input"))

        val host = host(
            made, "xl2tpd\nswanctl\nsystemctl\nfirewall-cmd\n", "firewall-cmd --state" to ok("running\n"), "f=/etc/sysctl.d" to ok(),
            "B=\$(mktemp" to ok("4242 /tmp/networkhelm-firewall.Ab12Cd\n"), "firewall-cmd --permanent" to ok(), "kill" to ok(),
        )
        SshHostSession(host, device, linux = true).set(l2tp, "l2tp", mapOf("nat" to "yes"))
        val firewall = host.ran.single { it.startsWith("firewall-cmd --permanent --delete-policy=networkhelm-nat-l2tp") }
        assertTrue(
            listOf(
                "--zone=trusted --add-source=10.20.30.0/24", "--service=networkhelm-nat-l2tp --add-port=4500/udp", "--service=networkhelm-nat-l2tp --add-port=1701/udp",
                "--direct --add-rule ipv4 filter INPUT 0 -p udp --dport 1701 -m policy --dir in --pol none -j DROP",
                "--direct --remove-rule ipv6 filter INPUT 0 -p udp --dport 1701 -m policy --dir in --pol none -j DROP",
            ).all { it in firewall },
            firewall,
        )
        assertTrue(host.given.isEmpty(), "NAT alone writes no file of the server")
    }
}
