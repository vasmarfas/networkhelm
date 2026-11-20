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

class LinuxVpnOpenVpnTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private val servers = MenuPath.parse("vpn/openvpn")
    private val clients = MenuPath.parse("vpn/openvpn/client")
    private val connections = MenuPath.parse("vpn/openvpn/connection")

    // a server as this window makes one, with index.txt of easy-rsa and files of client configuration, written to the formats of
    // OpenVPN 2.6 and easy-rsa 3.1, not recorded on a host: the containers of the lab have no /dev/net/tun to run OpenVPN
    private val serverFile = listOf(
        "port 1194", "proto udp", "dev tun-srv", "topology subnet", "server 10.8.0.0 255.255.255.0", "ca /etc/openvpn/server/srv/pki/ca.crt",
        "cert /etc/openvpn/server/srv/pki/issued/server.crt", "key /etc/openvpn/server/srv/pki/private/server.key", "dh none",
        "crl-verify /etc/openvpn/server/srv/crl.pem", "tls-crypt /etc/openvpn/server/srv/tc.key", "data-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305",
        "keepalive 10 120", "client-config-dir /etc/openvpn/server/srv/ccd", "management /run/openvpn-server/srv.sock unix",
        "status /run/openvpn-server/status-srv.log 1", "status-version 3", "push \"dhcp-option DNS 1.1.1.1\"", "push \"redirect-gateway def1 bypass-dhcp\"",
        "push \"route 192.168.1.0 255.255.255.0\"", "# ClientRemote = vpn.example.net", "explicit-exit-notify 1", "verb 3", "",
    ).joinToString("\n")

    // the lines of server and laptop as easy-rsa 3.1.0 wrote them on Debian 12 in the lab on 17.11; those of phone in their form
    private val index = listOf(
        "V\t361114194955Z\t\tC108F0D0825E3D09314AFD55CB35BDA7\tunknown\t/CN=server", "V\t361114194955Z\t\tE45C8C4DB1D5EC0C623D1C212AA32908\tunknown\t/CN=laptop",
        "R\t361114194955Z\t261117195001Z\tD2A61F3C9B7E4A05861C3E7F9A1B2C4D\tunknown\t/CN=phone", "V\t361116080000Z\t\tF3B72A4D0C8F5B16972D4F8A0B2C3D5E\tunknown\t/CN=phone",
    ).joinToString("\n")

    // the read in full: the server running, its PKI and two files of client configuration, a connection of the host with the block of its
    // key emptied on the host, and the NAT ufw keeps for the server
    private val files = "--nh-zone-- +0300\n--nh-ovpn-- srv active enabled /etc/openvpn/server/srv.conf\n$serverFile\n" +
        "--nh-ovpn-pki-- /etc/openvpn/server/srv/pki\n$index\n--nh-ovpn-ccd-- laptop\nifconfig-push 10.8.0.10 255.255.255.0\n--nh-ovpn-ccd-- phone\ndisable\n" +
        "--nh-ovpn-client-- office inactive disabled /etc/openvpn/client/office.conf\nclient\ndev tun\nproto udp\nremote office.example.net 1194\n" +
        "auth-user-pass /etc/openvpn/client/office.auth\n<ca>\n-----BEGIN CERTIFICATE-----\nrdTestCa\n-----END CERTIFICATE-----\n</ca>\n<key>\n</key>\n" +
        "--nh-ovpn-user-- alice\n--nh-nat-- tun-srv\n"

    private val tools = "for t in" to ok("openvpn\nsystemctl\n")
    private val login = "id -u" to ok("0\n")

    @Test
    fun theReadGivesTheServersWithTheirClientsAndTheConnectionsOfTheHost() = runTest {
        val session = SshHostSession(VpnHost(listOf(tools, login, "full=1" to ok(files))), device, linux = true)
        val server = session.rows("vpn/openvpn").single()
        assertEquals(
            listOf("active", "true", "1194", "udp", "tun-srv", "10.8.0.0/24", "1.1.1.1", "true", "192.168.1.0/24", "tls-crypt", "vpn.example.net", "true", "2"),
            listOf("state", "autostart", "port", "proto", "dev", "network", "dns", "redirect-gateway", "push-routes", "tls", "client-remote", "nat", "clients").map { server[it] },
        )
        val (laptop, phone) = session.rows("vpn/openvpn/client")
        assertEquals(listOf("srv/laptop", "valid", "2036-11-14 19:49", "false", "10.8.0.10"), listOf(laptop.id) + listOf("state", "expires", "disabled", "address").map { laptop[it] })
        assertEquals(listOf("srv/phone", "valid", "2036-11-16 08:00", "true", ""), listOf(phone.id) + listOf("state", "expires", "disabled", "address").map { phone[it] })
        val office = session.rows("vpn/openvpn/connection").single()
        assertEquals(
            listOf("office", "inactive", "false", "office.example.net 1194", "alice", ""),
            listOf(office.id) + listOf("state", "autostart", "remote", "user", "password").map { office[it] },
        )
    }

    @Test
    fun aNewServerHasItsPkiMadeByEasyRsaAndAConfigurationTheSettingsChange() = runTest {
        val host = VpnHost(
            listOf(
                "for t in" to ok("openvpn\nsystemctl\nufw\n"), login, "full=1" to ok("--nh-zone-- +0300\n"), "d='/etc/openvpn/server/srv'" to ok(),
                "umask 077; mkdir -p '/etc/openvpn/server'" to ok(), "rm -f" to ok(), "systemctl enable" to ok(), "ufw status" to ok("Status: active\n"), "for n in" to ok(),
                "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "systemctl start" to ok(), "kill" to ok(),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        val values = mapOf("name" to "srv", "port" to "1195", "proto" to "tcp", "network" to "10.9.0.0/24", "dns" to "9.9.9.9", "client-remote" to "vpn.example.net", "autostart" to "yes")
        assertEquals("srv", session.add(servers, values))
        val pki = host.ran.single { it.startsWith("d=") }
        assertTrue("EASYRSA_PKI='/etc/openvpn/server/srv/pki'" in pki, pki)
        assertTrue(
            "\"\$e\" init-pki >/dev/null && \"\$e\" --req-cn='srv CA' build-ca nopass >/dev/null && \"\$e\" build-server-full server nopass >/dev/null && " +
                "\"\$e\" gen-crl >/dev/null && install -m 644" in pki,
            pki,
        )
        assertTrue("EASYRSA_REQ_CN" !in pki, "easy-rsa 3.1 refuses build-server-full with a name of the request in its environment: $pki")
        val written = host.given.single().lines()
        val wanted = listOf(
            "port 1195", "proto tcp", "dev tun-srv", "server 10.9.0.0 255.255.255.0", "push \"dhcp-option DNS 9.9.9.9\"", "management /run/openvpn-server/srv.sock unix",
            "status /run/openvpn-server/status-srv.log 1", "crl-verify /etc/openvpn/server/srv/crl.pem", "# ClientRemote = vpn.example.net",
        )
        assertEquals(wanted, wanted.filter { it in written })
        assertTrue("explicit-exit-notify 1" !in written, "a server of tcp gives no notice of its exit")
        assertTrue("systemctl enable 'openvpn-server@srv'" in host.ran, "${host.ran}")
        assertTrue(host.ran.any { it.startsWith("for n in") && it.endsWith(" && ufw allow 1195/tcp comment networkhelm-port-openvpn-srv") }, "the port is open without NAT: ${host.ran}")
        val guard = host.ran.single { it.startsWith("B=") }
        assertEquals(
            "systemctl start 'openvpn-server@srv'; sleep 5; if systemctl is-active -q 'openvpn-server@srv'; then exit 0; fi; journalctl -u 'openvpn-server@srv' -n 12 --no-pager -o cat; exit 1",
            host.ran[host.ran.indexOf(guard) + 1],
        )
        assertEquals(listOf("kill 4242; rm -rf '/tmp/networkhelm-vpn.Ab12Cd'", "systemctl enable 'openvpn-server@srv'"), host.ran.takeLast(2), "it starts at boot once the guard let it start")

        val taken = VpnHost(listOf(tools, login, "full=1" to ok("--nh-zone-- +0300\n"), "[ -e '/etc/openvpn/server/srv' ]" to ok()))
        val there = assertFailsWith<DeviceException> { SshHostSession(taken, device, linux = true).add(servers, mapOf("name" to "srv")) }
        assertEquals("the folder /etc/openvpn/server/srv for the PKI is there already", there.message)
        val named = assertFailsWith<DeviceException> { session.add(servers, mapOf("name" to "my server")) }
        assertEquals("name: my server is not a value it takes", named.message)
    }

    @Test
    fun aChangedServerThatDoesNotStartAgainIsTakenBackWithTheEndOfItsJournal() = runTest {
        val host = VpnHost(
            listOf(
                tools, login, "full=1" to ok(files), "cat '/etc/openvpn/server/srv.conf'" to ok(serverFile),
                "echo \"\$SSH_CONNECTION\"" to ok("192.168.10.2 50000 192.168.10.250 22\n2: eth0    inet 192.168.10.250/24 brd 192.168.10.255 scope global eth0\n"),
                "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "umask 077; mkdir -p '/etc/openvpn/server'" to ok(), "rm -f" to ok(),
                "systemctl restart" to ExecResult("Options error: --max-clients must be at least 1\nExiting due to fatal error\n", "", 1), "kill" to ok(),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        val failed = assertFailsWith<DeviceException> { session.set(servers, "srv", mapOf("max-clients" to "5")) }
        assertEquals("srv did not start: Options error: --max-clients must be at least 1\nExiting due to fatal error", failed.message)
        assertTrue("max-clients 5" in host.given.single().lines(), host.given.single())
        assertEquals(
            "systemctl restart 'openvpn-server@srv'; sleep 5; if systemctl is-active -q 'openvpn-server@srv'; then exit 0; fi; " +
                "journalctl -u 'openvpn-server@srv' -n 12 --no-pager -o cat; exit 1",
            host.ran.single { it.startsWith("systemctl restart") },
        )
        val restore = host.ran.last()
        assertTrue(restore.startsWith("kill 4242; sh -c '") && "cp -p \"\$1/conf\"" in restore && "systemctl start" in restore, restore)
    }

    @Test
    fun theDefaultRouteOfIpv6GoesToClientsWithTheNetworkOfIpv6() = runTest {
        val stopped = files.replace("srv active enabled", "srv inactive enabled").replace("--nh-nat-- tun-srv\n", "")
        val host = VpnHost(
            listOf(tools, login, "full=1" to ok(stopped), "cat '/etc/openvpn/server/srv.conf'" to ok(serverFile), "umask 077; mkdir -p '/etc/openvpn/server'" to ok(), "rm -f" to ok()),
        )
        SshHostSession(host, device, linux = true).set(servers, "srv", mapOf("network6" to "fd42:8::/64"))
        val written = host.given.single().lines()
        assertTrue("server-ipv6 fd42:8::/64" in written && "push \"redirect-gateway def1 ipv6 bypass-dhcp\"" in written, "$written")
        assertTrue("push \"redirect-gateway def1 bypass-dhcp\"" !in written, "$written")
    }

    // the unit of OpenVPN on Debian 12 holds LimitNPROC=10, under which the root of an unprivileged container forks no shell: the rules go
    // to a unit of their own, bound to the server
    @Test
    fun natGoesToAUnitOfItsOwnWhereNoFirewallKeepsTheRules() = runTest {
        val plain = files.replace("--nh-nat-- tun-srv\n", "")
        val host = VpnHost(
            listOf(
                "for t in" to ok("openvpn\nsystemctl\niptables-save\n"), login, "full=1" to ok(plain), "f=/etc/sysctl.d" to ok(), "systemctl stop" to ok(),
                "umask 077; mkdir -p '/etc/systemd/system'" to ok(), "rm -f" to ok(), "systemctl enable" to ok(), "[ ! -e" to ok(),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        session.set(servers, "srv", mapOf("nat" to "yes"))
        val up = "iptables -w -t nat -A POSTROUTING -s 10.8.0.0/24 ! -o tun-srv -j MASQUERADE; iptables -w -I FORWARD -i tun-srv -j ACCEPT; " +
            "iptables -w -I FORWARD -o tun-srv -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT"
        val down = up.replace(" -A ", " -D ").replace(" -I ", " -D ")
        assertEquals(
            "[Unit]\nDescription=NAT for the clients of OpenVPN srv\nBindsTo=openvpn-server@srv.service\nAfter=openvpn-server@srv.service\n\n[Service]\nType=oneshot\n" +
                "RemainAfterExit=yes\nExecStart=/bin/sh -c \"$up\"\nExecStop=-/bin/sh -c \"$down\"\n\n[Install]\nWantedBy=openvpn-server@srv.service\n",
            host.given.single(),
        )
        val unit = "'networkhelm-nat-openvpn-srv.service'"
        val file = "'/etc/systemd/system/networkhelm-nat-openvpn-srv.service'"
        val written = host.ran.indexOf(host.ran.single { it.startsWith("umask 077; mkdir -p '/etc/systemd/system'") })
        assertTrue(" && mv -f '/etc/systemd/system/.networkhelm-nat-openvpn-srv.service.networkhelm-new' $file " in host.ran[written], host.ran[written])
        assertTrue(host.ran[written].endsWith("\nsystemctl daemon-reload 2>&1"), host.ran[written])
        assertEquals("systemctl stop $unit 2>/dev/null; true", host.ran[written - 1], "the rules of the unit before go first")
        assertEquals("systemctl enable $unit && if systemctl is-active -q 'openvpn-server@srv'; then systemctl start $unit; fi", host.ran.last(), "the running server takes the rules at once")
        assertTrue(host.ran.none { ".service.d" in it || "LimitNPROC" in it }, "${host.ran}")
        assertEquals("true", parseOpenVpn(plain + "--nh-ovpn-hook-- srv\n").serverRows().single()["nat"])

        session.set(servers, "srv", mapOf("nat" to "no"))
        assertEquals("[ ! -e $file ] || { systemctl disable --now $unit; rm -f $file && systemctl daemon-reload; }", host.ran.last())
    }

    private val pki = "--nh-zone-- +0300\n--nh-ovpn-- srv active enabled /etc/openvpn/server/srv.conf\n$serverFile\n--nh-ovpn-pki-- /etc/openvpn/server/srv/pki\n" +
        "V\t361114194955Z\t\tC108F0D0825E3D09314AFD55CB35BDA7\tunknown\t/CN=server\n"

    @Test
    fun aServerOfOneStepStartsAndGivesTheConfigurationOfItsFirstClient() = runTest {
        // a socket on 1194, 0x04AA, of a server the host has already and its network 10.8.0.0/24 in the routes, in the form of the read
        val scan = "eth0\nlo\n--nh-udp--\n  sl  local_address rem_address   st\n   7: 00000000:04AA 00000000:0000 07\n--nh-ip--\n" +
            "2: eth0    inet 192.168.10.250/24 brd 192.168.10.255 scope global dynamic eth0\n10.8.0.0/24 dev tun0 proto kernel scope link src 10.8.0.1\n" +
            "--nh-default--\ndefault via 192.168.10.1 dev eth0 \n"
        val proposing = VpnHost(listOf(tools, login, "full=1" to ok("--nh-zone-- +0300\n"), "ls /sys/class/net" to ok(scan)))
        val proposed = SshHostSession(proposing, device, linux = true).get(MenuPath.parse("vpn/openvpn/server"), null)?.values.orEmpty()
        assertEquals(
            listOf("server", "1195", "udp", "10.9.0.0/24", "192.168.10.250", "1.1.1.1, 9.9.9.9", "true", "true", "true", "client1"),
            listOf("name", "port", "proto", "network", "client-remote", "dns", "redirect-gateway", "nat", "autostart", "client").map { proposed[it] },
        )

        val host = VpnHost(
            listOf(
                "for t in" to ok("openvpn\nsystemctl\niptables-save\n"), login, "d='/etc/openvpn/server/srv'" to ok(), "umask 077; mkdir -p '/etc/openvpn/server'" to ok(), "rm -f" to ok(),
                "f=/etc/sysctl.d" to ok(), "systemctl stop" to ok(), "umask 077; mkdir -p '/etc/systemd/system" to ok(),
                "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "systemctl start" to ok(), "kill" to ok(), "systemctl enable" to ok(), "umask 077; e=" to ok(),
                "cat '/etc/openvpn/server/srv/pki/ca.crt'" to ok(
                    "-----BEGIN CERTIFICATE-----\nrdTestCa\n-----END CERTIFICATE-----\n--nh-part--\n-----BEGIN CERTIFICATE-----\nrdTestClient1\n-----END CERTIFICATE-----\n" +
                        "--nh-part--\n-----BEGIN PRIVATE KEY-----\nrdTestClient1Key\n-----END PRIVATE KEY-----\n--nh-part--\n-----BEGIN OpenVPN Static key V1-----\nrdTestTc\n-----END OpenVPN Static key V1-----\n",
                ),
            ),
            mapOf("full=1" to listOf(ok("--nh-zone-- +0300\n"), ok(pki), ok(pki + "V\t361118000000Z\t\tA1B2C3D4E5F60718293A4B5C6D7E8F90\tunknown\t/CN=client1\n"))),
        )
        val values = mapOf(
            "name" to "srv", "port" to "1194", "proto" to "udp", "client-remote" to "vpn.example.net", "autostart" to "true", "network" to "10.8.0.0/24", "dns" to "1.1.1.1, 9.9.9.9",
            "redirect-gateway" to "true", "nat" to "true", "client" to "client1", "client-to-client" to "false",
        )
        val reply = SshHostSession(host, device, linux = true).run(MenuPath.parse("vpn/openvpn/server"), "create", values).toList().single()
        assertEquals("srv", reply["name"])
        val conf = reply["conf"].orEmpty()
        assertEquals(listOf("client", "dev tun", "proto udp", "remote vpn.example.net 1194"), conf.lines().take(4))
        assertTrue("<cert>\n-----BEGIN CERTIFICATE-----\nrdTestClient1\n-----END CERTIFICATE-----\n</cert>" in conf && "<tls-crypt>" in conf, conf)
        assertTrue(
            host.given.any { "\nBindsTo=openvpn-server@srv.service\n" in it && "\nExecStart=/bin/sh -c \"iptables -w -t nat -A POSTROUTING -s 10.8.0.0/24 ! -o tun-srv -j MASQUERADE;" in it },
            "${host.given}",
        )
        val wanted = host.ran.indexOfFirst { it.startsWith("systemctl enable 'networkhelm-nat-openvpn-srv.service' && ") }
        assertTrue(wanted in 0 until host.ran.indexOf(host.ran.single { it.startsWith("B=") }), "the unit of NAT is wanted by the server before it starts: ${host.ran}")
        val started = host.ran.indexOf("kill 4242; rm -rf '/tmp/networkhelm-vpn.Ab12Cd'")
        assertEquals("systemctl enable 'openvpn-server@srv'", host.ran[started + 1], "it starts at boot once the guard let it start")
        assertTrue(host.ran.drop(started).any { it.startsWith("umask 077; e=") && it.endsWith("\"\$e\" build-client-full 'client1' nopass >/dev/null") }, "${host.ran}")
    }

    @Test
    fun theConfigurationOfAClientIsImportedThroughTheInputAndStartsUnderTheGuard() = runTest {
        val key = "-----BEGIN PRIVATE KEY-----\nrdTestClientKeyAAAA\n-----END PRIVATE KEY-----"
        val text = "client\ndev tun\nproto udp\nremote vpn.example.net 1194\nnobind\n<ca>\nrdTestCa\n</ca>\n<key>\n$key\n</key>"
        val host = VpnHost(
            listOf(
                tools, login, "full=1" to ok("--nh-zone-- +0300\n"), "set -- \$SSH_CONNECTION" to ok("192.168.11.2\n192.168.11.2 via 192.168.10.1 dev eth0 src 192.168.10.249 uid 0 \n"),
                "umask 077; mkdir -p '/etc/openvpn/client'" to ok(), "rm -f" to ok(), "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "systemctl start" to ok(), "kill" to ok(),
                "systemctl enable" to ok(),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        val importing = MenuPath.parse("vpn/openvpn/import")
        assertEquals(listOf("client", "true", "true"), session.get(importing, null)?.values.orEmpty().let { listOf(it["name"], it["autostart"], it["keep-session"]) })
        session.run(importing, "import", mapOf("name" to "client", "autostart" to "true", "keep-session" to "true", "text" to text)).toList()
        assertEquals(text.replace("nobind\n", "nobind\nroute 192.168.11.2 255.255.255.255 net_gateway\n") + "\n", host.given.single())
        val guard = host.ran.indexOf(host.ran.single { it.startsWith("B=") })
        assertTrue(host.ran[guard + 1].startsWith("systemctl start 'openvpn-client@client'; sleep 5;"), "${host.ran}")
        assertEquals("systemctl enable 'openvpn-client@client'", host.ran.last(), "it starts at boot once the guard let it start")
        assertTrue(host.ran.none { "rdTestClientKeyAAAA" in it }, "${host.ran}")

        val server = assertFailsWith<DeviceException> { session.run(importing, "import", mapOf("name" to "hq", "text" to "port 1194\nserver 10.8.0.0 255.255.255.0\n")).toList() }
        assertEquals("text: this is the configuration of a server", server.message)
        val remoteless = assertFailsWith<DeviceException> { session.run(importing, "import", mapOf("name" to "hq", "text" to "client\ndev tun\n")).toList() }
        assertEquals("the configuration names no server by remote", remoteless.message)
        assertEquals(1, host.given.size, "a refused configuration goes to no file")
    }

    @Test
    fun aClientIsACertificateWithItsFileOfClientConfigurationAndGoesRevoked() = runTest {
        val host = VpnHost(
            listOf(
                tools, login, "full=1" to ok(files), "umask 077; e=" to ok(), "umask 077; mkdir -p" to ok(), "rm -f" to ok(),
                "{ printf" to ok(">INFO:OpenVPN Management Interface Version 5 -- type 'help' for more info\nSUCCESS: common name 'laptop' found, 1 client(s) killed\n"),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        assertEquals("srv/tablet", session.add(clients, mapOf("server" to "srv", "name" to "tablet", "address" to "10.8.0.20", "disabled" to "no")))
        assertTrue(
            host.ran.any {
                it.startsWith("umask 077; e=") && it.endsWith(
                    "export EASYRSA_BATCH=1 EASYRSA_PKI='/etc/openvpn/server/srv/pki' EASYRSA_ALGO=ec EASYRSA_CURVE=prime256v1 EASYRSA_CERT_EXPIRE=3650 " +
                        "EASYRSA_CRL_DAYS=3650 && \"\$e\" build-client-full 'tablet' nopass >/dev/null",
                )
            },
            "${host.ran}",
        )
        assertTrue(host.ran.none { "REQ_CN" in it || "req-cn" in it }, "build-client-full takes no name of a request but its own")
        assertEquals("ifconfig-push 10.8.0.20 255.255.255.0\n", host.given.last())
        val outside =assertFailsWith<DeviceException> { session.add(clients, mapOf("server" to "srv", "name" to "tv", "address" to "10.9.0.1")) }
        assertEquals("address: 10.9.0.1 is not a value it takes", outside.message)

        session.set(clients, "srv/laptop", mapOf("disabled" to "yes"))
        assertEquals("ifconfig-push 10.8.0.10 255.255.255.0\ndisable\n", host.given.last())
        assertTrue(host.ran.last().startsWith("{ printf '%s\\nquit\\n' 'kill laptop'; } | {"), host.ran.last())

        session.remove(clients, listOf("srv/phone"))
        val revoked = host.ran.single { "revoke" in it }
        assertTrue(
            revoked.endsWith(
                "\"\$e\" revoke 'phone' >/dev/null && \"\$e\" gen-crl >/dev/null && install -m 644 '/etc/openvpn/server/srv/pki/crl.pem' '/etc/openvpn/server/srv/crl.pem' && " +
                    "rm -f '/etc/openvpn/server/srv/ccd/phone'",
            ),
            revoked,
        )
    }

    @Test
    fun theConfigurationOfAClientHoldsItsKeysInlineAndNoErrorHoldsAKey() = runTest {
        val key = "-----BEGIN PRIVATE KEY-----\nrdTestClientKeyAAAA\n-----END PRIVATE KEY-----"
        val tls = "-----BEGIN OpenVPN Static key V1-----\nrdTestStaticKeyAAAA\n-----END OpenVPN Static key V1-----"
        val host = VpnHost(
            listOf(
                tools, login, "full=1" to ok(files),
                "cat '/etc/openvpn/server/srv/pki/ca.crt'" to ok(
                    "-----BEGIN CERTIFICATE-----\nrdTestCa\n-----END CERTIFICATE-----\n--nh-part--\n-----BEGIN CERTIFICATE-----\nrdTestLaptop\n-----END CERTIFICATE-----\n" +
                        "--nh-part--\n$key\n--nh-part--\n$tls\n",
                ),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        val conf = session.run(clients, "client-config", mapOf("number" to "srv/laptop")).toList().single()["conf"].orEmpty()
        assertEquals(
            listOf("client", "dev tun", "proto udp", "remote vpn.example.net 1194", "resolv-retry infinite", "nobind", "persist-key", "persist-tun", "remote-cert-tls server", "verb 3"),
            conf.lines().take(10),
        )
        assertTrue("<key>\n$key\n</key>\n<tls-crypt>\n$tls\n</tls-crypt>" in conf, conf)
        assertEquals(
            "cat '/etc/openvpn/server/srv/pki/ca.crt' && echo --nh-part-- && sed -n '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/p' " +
                "'/etc/openvpn/server/srv/pki/issued/laptop.crt' && echo --nh-part-- && cat '/etc/openvpn/server/srv/pki/private/laptop.key' && echo --nh-part-- && " +
                "cat '/etc/openvpn/server/srv/tc.key'",
            host.ran.last(),
        )
        assertEquals("cat: (hidden) and (hidden)", hidden("cat: $key and $tls"))
    }

    @Test
    fun aConnectionComesFromAnOvpnOnTheHostWithItsLoginInAFileOfItsOwn() = runTest {
        val host = VpnHost(
            listOf(
                tools, login, "full=1" to ok("--nh-zone-- +0300\n"), "cat '/root/office.ovpn'" to ok("client\ndev tun\nremote office.example.net 1194\nauth-user-pass\n<ca>\nrdTestCa\n</ca>\n"),
                "cat '/root/server.conf'" to ok("port 1194\nserver 10.8.0.0 255.255.255.0\n"), "umask 077; mkdir -p '/etc/openvpn/client'" to ok(), "rm -f" to ok(),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        assertEquals("office", session.add(connections, mapOf("name" to "office", "import" to "/root/office.ovpn", "user" to "alice", "password" to "rd-test-secret")))
        val (conf, secret) = host.given
        assertTrue("auth-user-pass /etc/openvpn/client/office.auth\n<ca>" in conf, conf)
        assertEquals("alice\nrd-test-secret\n", secret)
        assertTrue(host.sent.none { "rd-test-secret" in it }, "the password is on no command line")
        val server = assertFailsWith<DeviceException> { session.add(connections, mapOf("name" to "hq", "import" to "/root/server.conf")) }
        assertEquals("import: /root/server.conf is the configuration of a server", server.message)
    }
}
