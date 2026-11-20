package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxVpnIpsecTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private val ipsec = MenuPath.parse("vpn/ipsec")
    private val users = MenuPath.parse("vpn/user")

    private val server = IkeServer("rw", "vpn.example.net", "10.10.10.0/24", "", "1.1.1.1", "")

    // the users of EAP as this window writes them, bob turned off; the read shows them with their secrets masked on the host
    private val secrets = "secrets {\n    eap-alice {\n        id = alice\n        secret = \"rd-test-alice\"\n    }\n" +
        "#[disabled]     eap-bob {\n#[disabled]         id = bob\n#[disabled]         secret = \"rd-test-bob\"\n#[disabled]     }\n}"

    // a read of strongSwan 5.9 as swanctl and the files lay it out, written to their format: the containers of the lab run charon, but
    // IKE between them is not proven
    private fun state(own: Boolean = true) = "--nh-loaded--\noffice\n" + (if (own) "rw\n" else "") + "--nh-file-- /etc/swanctl/swanctl.conf\ninclude conf.d/*.conf\n" +
        (if (own) "--nh-file-- /etc/swanctl/conf.d/networkhelm-rw.conf\n${server.text()}\n" else "") +
        "--nh-file-- /etc/swanctl/conf.d/office.conf\nconnections {\n    office {\n        remote_addrs = 203.0.113.1\n        local {\n            auth = psk\n        }\n" +
        "        remote {\n            auth = psk\n        }\n        children {\n            net {\n                local_ts = 192.168.1.0/24\n" +
        "                remote_ts = 192.168.2.0/24\n            }\n        }\n    }\n}\nsecrets {\n    ike-office {\n        secret = (hidden)\n    }\n}\n" +
        "--nh-file-- /etc/swanctl/conf.d/networkhelm-users.conf\n" + secrets.replace(Regex("secret = \"[^\"]*\""), "secret = (hidden)") + "\n--nh-unit-- strongswan.service active\n--nh-boot-- enabled\n"

    private fun host(own: Boolean = true, vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok("swanctl\nsystemctl\napt-get\n"), "id -u" to ok("0\n"), "echo --nh-loaded--" to ok(state(own)),
            "echo \"\$SSH_CONNECTION\"" to ok("192.168.10.2 50000 192.168.10.250 22\n"), "umask 077; command -v pki" to ok(),
            "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "cd /etc/swanctl" to ok(), "systemctl enable" to ok(), "cat /etc/swanctl/conf.d/networkhelm-users.conf" to ok(secrets),
            "head -c 15 /dev/urandom" to ok("rdTestMade\n"),
            "swanctl --stats" to ok("uptime: 2 minutes, since Nov 17 23:10:00 2026\nloaded plugins: charon random nonce x509 pubkey pem gmp eap-identity eap-mschapv2 vici socket-default\n"),
            "swanctl --list-sas --raw" to ok("list-sa event {rw {uniqueid=7 version=2 state=ESTABLISHED remote-host=203.0.113.5 remote-eap-id=alice child-sas {}}}\n"),
            "swanctl --terminate" to ok(),
            "echo --nh-part--" to ok("--nh-part--\n-----BEGIN CERTIFICATE-----\nMIIBrd\nTestCa==\n-----END CERTIFICATE-----\nuuid-1\nuuid-2\nuuid-3\n"),
        ),
    )

    @Test
    fun theConnectionsComeFromTheFilesAndOnlyThoseMadeHereChange() = runTest {
        val session = SshHostSession(host(), device, linux = true)
        val (rw, office) = session.rows("vpn/ipsec")
        assertEquals(
            listOf("rw", "true", "2", "pubkey", "eap-mschapv2", "vpn.example.net", "10.10.10.0/24", "1.1.1.1", "0.0.0.0/0", "NetworkHelm"),
            listOf("name", "loaded", "version", "local-auth", "remote-auth", "server-id", "pool", "dns", "local-ts", "kept").map { rw[it] },
        )
        assertEquals(listOf("office", "psk", "psk", "192.168.1.0/24", "203.0.113.1", ""), listOf("name", "local-auth", "remote-auth", "local-ts", "remote-addrs", "kept").map { office[it] })
        val other = assertFailsWith<DeviceException> { session.set(ipsec, "office", mapOf("dns" to "9.9.9.9")) }
        assertEquals("/etc/swanctl/conf.d/office.conf is no file of NetworkHelm, and it stays as it is", other.message)
    }

    @Test
    fun startAtBootOfAConnectionIsThatOfTheServiceOfCharon() = runTest {
        val host = host(true, "systemctl disable" to ok())
        val session = SshHostSession(host, device, linux = true)
        assertEquals(listOf("true", "true"), session.rows("vpn/ipsec").map { it["autostart"] })
        session.set(ipsec, "rw", mapOf("autostart" to "no"))
        assertEquals("systemctl disable 'strongswan.service'", host.ran.last())
        assertEquals(false, parseIpsec("--nh-unit--  \n--nh-boot-- \n").enabled)
    }

    // what strongSwan 5.9.8 of Debian 12 printed in the lab on 17.11, each optional plugin it lacks on a line of its own
    private val plugins = "plugin 'test-vectors': failed to load - test_vectors_plugin_create not found and no plugin file available\n" +
        "plugin 'ldap': failed to load - ldap_plugin_create not found and no plugin file available\n"

    @Test
    fun aNewServerGetsACaACertificateForItsNameAndAConnectionThatSwanctlLoads() = runTest {
        val host = host(
            false,
            "umask 077; command -v pki" to ExecResult("", plugins, 0),
            "umask 077; mkdir -p '/etc/swanctl/conf.d'" to ok("${plugins}loaded certificate from '/etc/swanctl/x509/networkhelm-rw.crt'\nloaded connection 'rw'\nsuccessfully loaded 1 connections, 0 unloaded\n"),
        )
        val session = SshHostSession(host, device, linux = true)
        assertEquals("rw", session.add(ipsec, mapOf("name" to "rw", "server-id" to "vpn.example.net", "autostart" to "yes")))
        val pki = host.ran.single { it.startsWith("umask 077; command -v pki") }
        assertTrue("--dn 'CN=rw CA'" in pki && "--san 'vpn.example.net' --san '192.168.10.250' --flag serverAuth --flag ikeIntermediate" in pki, pki)
        val written = host.given.single().lines().map { it.trim() }
        assertEquals(
            listOf("certs = networkhelm-rw.crt", "id = vpn.example.net", "auth = eap-mschapv2", "local_ts = 0.0.0.0/0", "addrs = 10.10.10.0/24", "dns = 1.1.1.1"),
            listOf("certs = networkhelm-rw.crt", "id = vpn.example.net", "auth = eap-mschapv2", "local_ts = 0.0.0.0/0", "addrs = 10.10.10.0/24", "dns = 1.1.1.1").filter { it in written },
        )
        assertTrue(host.ran.any { it.startsWith("umask 077; mkdir -p '/etc/swanctl/conf.d'") && it.endsWith("\nswanctl --load-all 2>&1") })
        assertEquals("systemctl enable 'strongswan.service'", host.ran.last())

        assertEquals(false, swanFailed("${plugins}loaded connection 'rw'\nsuccessfully loaded 1 connections, 0 unloaded"))
        assertEquals(true, swanFailed("${plugins}loading connection 'rw' failed: invalid value for: local_ts"))
        val refused = host(
            false,
            "umask 077; command -v pki" to ExecResult("", "${plugins}building CRED_PRIVATE_KEY - RSA failed\n", 1),
        )
        val failed = assertFailsWith<DeviceException> { SshHostSession(refused, device, linux = true).add(ipsec, mapOf("name" to "rw", "server-id" to "vpn.example.net")) }
        assertEquals("building CRED_PRIVATE_KEY - RSA failed", failed.message)
    }

    // the messages of charon-systemd of the lab on 17.11, .notes/lab/samples/strongswan-charon-journal.txt, as journalctl -o json gives them,
    // with times of this sample: a login that failed, the one that worked and Disconnect from the panel
    private fun entry(seconds: Long, message: String) = "{\"__REALTIME_TIMESTAMP\":\"${seconds}000000\",\"_COMM\":\"charon-systemd\",\"MESSAGE\":\"$message\"}"

    @Test
    fun theJournalOfCharonGivesTheSessionsOfIkeAndTheLogOfTheHostTheirBytes() {
        val journal = listOf(
            entry(1794935000, "received EAP identity 'nh-ike'"), entry(1794935000, "loading EAP_MSCHAPV2 method failed"),
            entry(1794935100, "received EAP identity 'nh-ike'"), entry(1794935100, "EAP method EAP_MSCHAPV2 succeeded, MSK established"),
            entry(1794935101, "IKE_SA rw[1] established between 192.168.10.250[192.168.10.250]...192.168.10.249[192.168.10.249]"),
            entry(1794936976, "vici terminate IKE_SA #1"),
            entry(1794936976, "deleting IKE_SA rw[1] between 192.168.10.250[192.168.10.250]...192.168.10.249[192.168.10.249]"),
            entry(1794936976, "sending DELETE for IKE_SA rw[1]"),
        ).joinToString("\n")
        val session = parseCharonJournal(journal, "+0300").single()
        assertEquals(
            listOf("IKEv2", "rw", "nh-ike", "192.168.10.249", "1794935101", "1875", "31m15s", ""),
            listOf("kind", "service", "user", "endpoint", "start", "seconds", "duration", "rx").map { session[it] },
        )
        val logged = Item("log/ipsec/rw/1/1794935099", mapOf("kind" to "IKEv2", "service" to "rw", "user" to "nh-ike", "endpoint" to "192.168.10.249:4500", "start" to "1794935099", "rx" to "1000", "tx" to "2000"))
        assertEquals(listOf("1000", "2000"), mergeHistory(listOf(session), listOf(logged)).single().let { listOf(it["rx"], it["tx"]) })
    }

    @Test
    fun theNatOfAServerOfIpsecGoesByTheNetworksOfItsClientsAndThePolicy() {
        val block = ufwNat("*filter\nCOMMIT\n", "networkhelm-nat-ipsec-rw", "ipsec-rw", listOf("10.10.10.0/24"), policy = true)
        assertTrue("-A POSTROUTING -s 10.10.10.0/24 -m policy --dir out --pol none -j MASQUERADE" in block, block)
        val rows = parseIpsec(state() + "--nh-nat-- networkhelm-nat-ipsec-rw\n").rows()
        assertEquals(listOf("true", "false"), rows.map { it["nat"] })

        // where no firewall keeps the rules, the unit of charon puts these in and takes them out
        val (up, down) = ipsecNat("rw", listOf("10.10.10.0/24", "fd42:10::/112"), iptables = true)
        assertEquals(
            "iptables -w -t nat -A POSTROUTING -s 10.10.10.0/24 -m policy --dir out --pol none -j MASQUERADE; iptables -w -I FORWARD -s 10.10.10.0/24 -m policy --dir in --pol ipsec -j ACCEPT; " +
                "iptables -w -I FORWARD -d 10.10.10.0/24 -m policy --dir out --pol ipsec -j ACCEPT; " +
                "ip6tables -w -t nat -A POSTROUTING -s fd42:10::/112 -m policy --dir out --pol none -j MASQUERADE; ip6tables -w -I FORWARD -s fd42:10::/112 -m policy --dir in --pol ipsec -j ACCEPT; " +
                "ip6tables -w -I FORWARD -d fd42:10::/112 -m policy --dir out --pol ipsec -j ACCEPT",
            up,
        )
        assertEquals(up.replace(" -A ", " -D ").replace(" -I ", " -D "), down)
    }

    // what the scan of a host finds with its default route on eth0 and a network of WireGuard on 10.8, written to the format of the
    // lab on 18.11
    private val scan = "eth0\nlo\nwg0\n--nh-udp--\n--nh-ip--\n" +
        "2: eth0    inet 203.0.113.5/24 brd 203.0.113.255 scope global eth0\\       valid_lft forever preferred_lft forever\n" +
        "4: wg0    inet 10.8.0.1/24 scope global wg0\\       valid_lft forever preferred_lft forever\n" +
        "default via 203.0.113.1 dev eth0 \n203.0.113.0/24 dev eth0 proto kernel scope link src 203.0.113.5 \n10.8.0.0/24 dev wg0 proto kernel scope link src 10.8.0.1 \n" +
        "--nh-default--\ndefault via 203.0.113.1 dev eth0 \n"

    private val wizard = MenuPath.parse("vpn/ipsec/server")

    @Test
    fun aNewServerIsProposedAroundWhatTheHostHas() = runTest {
        val proposed = SshHostSession(host(true, "ls /sys/class/net" to ok(scan)), device, linux = true).get(wizard, null)?.values.orEmpty()
        assertEquals(
            listOf("ikev2", "203.0.113.5", "10.9.0.0/24", "1.1.1.1, 9.9.9.9", "true", "true", "client1"),
            listOf("name", "client-endpoint", "pool", "dns", "nat", "autostart", "user").map { proposed[it] },
        )
    }

    @Test
    fun aServerOfOneStepGivesItsFirstUserALoginAndProfilesAndThePasswordGoesOnlyThroughTheInput() = runTest {
        val made = IkeServer("ikev2", "203.0.113.5", "10.9.0.0/24", "", "1.1.1.1, 9.9.9.9", "")
        val host = VpnHost(
            host(true, "f=/etc/sysctl.d" to ok(), "if [ -e" to ok(), "if systemctl is-active" to ok()).answers,
            mapOf("echo --nh-loaded--" to listOf(ok(state()), ok(state() + "--nh-file-- /etc/swanctl/conf.d/networkhelm-ikev2.conf\n${made.text()}\n"))),
        )
        val values = mapOf(
            "name" to "ikev2", "client-endpoint" to "203.0.113.5", "autostart" to "true", "pool" to "10.9.0.0/24", "pool6" to "", "dns" to "1.1.1.1, 9.9.9.9",
            "local-ts" to "", "nat" to "true", "user" to "client1", "password" to "rd-test-first",
        )
        val reply = SshHostSession(host, device, linux = true).run(wizard, "create", values).toList()
        val pki = host.ran.single { it.startsWith("umask 077; command -v pki") }
        assertTrue("--dn 'CN=203.0.113.5' --san '203.0.113.5' --san '192.168.10.250'" in pki, pki)
        val (up, down) = ipsecNat("ikev2", listOf("10.9.0.0/24"), iptables = false)
        assertEquals(
            "nft add table inet networkhelm-nat-ipsec-ikev2; nft add chain inet networkhelm-nat-ipsec-ikev2 nat '{ type nat hook postrouting priority 100; }'; " +
                "nft add rule inet networkhelm-nat-ipsec-ikev2 nat ip saddr 10.9.0.0/24 rt ipsec missing masquerade",
            up,
        )
        assertEquals(listOf("${made.text()}\n", "[Service]\nExecStartPost=-/bin/sh -c \"$up\"\nExecStopPost=-/bin/sh -c \"$down\"\n"), host.given.take(2))
        assertTrue("    eap-client1 {\n        id = client1\n        secret = \"rd-test-first\"\n    }" in host.given[2], host.given[2])
        assertTrue("systemctl enable 'strongswan.service'" in host.ran && "if systemctl is-active -q 'strongswan.service'; then $up; fi" in host.ran, "${host.ran}")

        assertEquals("ikev2: IKEv2, EAP-MSCHAPv2\nserver: 203.0.113.5\nlogin: client1\npassword: rd-test-first", reply.first()["conf"])
        val profiles = reply.drop(1)
        assertEquals(listOf("ikev2.mobileconfig", "ikev2.sswan", "ikev2.ps1"), profiles.map { it["file"] })
        assertTrue(profiles.all { "203.0.113.5" in it["conf"].orEmpty() && "MIIBrdTestCa==" in it["conf"].orEmpty() && "rd-test-first" !in it["conf"].orEmpty() }, "$profiles")
        assertEquals(List(4) { "ikev2" }, reply.map { it["name"] })
        assertTrue(host.sent.none { "rd-test-first" in it }, "the password is on no command line")
    }

    @Test
    fun aServerOfOneStepStopsBeforeItsCaWhereItsUserIsTakenOrNothingKeepsItsNat() = runTest {
        val taken = host(true)
        val user = assertFailsWith<DeviceException> { SshHostSession(taken, device, linux = true).run(wizard, "create", mapOf("name" to "ikev2", "user" to "alice")).toList() }
        assertEquals("the host has a VPN user alice already", user.message)
        val bare = VpnHost(host(true).answers, mapOf("echo --nh-loaded--" to listOf(ok(state().replace("--nh-unit-- strongswan.service active", "--nh-unit--  ")))))
        val nat = assertFailsWith<DeviceException> {
            SshHostSession(bare, device, linux = true).run(wizard, "create", mapOf("name" to "ikev2", "client-endpoint" to "203.0.113.5", "nat" to "true", "user" to "client1")).toList()
        }
        assertEquals("NAT for the clients of ikev2 needs ufw, firewalld or systemd", nat.message)
        assertTrue(listOf(taken, bare).all { tried -> tried.ran.none { it.startsWith("umask 077; command -v pki") } })
    }

    // charon of the lab ran from before libcharon-extauth-plugins came, and answered EAP_FAILURE until it started again
    @Test
    fun charonWithoutEapMschapv2StartsAgainAndSaysWhatItLacksIfItStillDoes() = runTest {
        val host = host(false, "swanctl --stats" to ok("loaded plugins: charon random nonce x509 pubkey pem gmp vici socket-default\n"), "systemctl restart" to ok())
        val lacking = assertFailsWith<DeviceException> { SshHostSession(host, device, linux = true).add(ipsec, mapOf("name" to "rw", "server-id" to "vpn.example.net")) }
        assertEquals("charon has no plugin eap-identity, eap-mschapv2, which libcharon-extauth-plugins brings on Debian and Ubuntu", lacking.message)
        assertTrue("systemctl restart 'strongswan.service'" in host.ran, "${host.ran}")
        assertTrue(host.ran.last().startsWith("cd /etc/swanctl && rm -f conf.d/networkhelm-rw.conf networkhelm/networkhelm-rw-ca.key"), host.ran.last())
    }

    // in the lab on 18.11 a server of the first round with the same identity answered the client of a new one, and its CA failed the check
    // of the client: charon takes the first connection that answers as the identity asked for
    @Test
    fun aNewServerWithTheIdentityOfAnotherIsRefusedBeforeItsCa() = runTest {
        val host = host(true)
        val session = SshHostSession(host, device, linux = true)
        val taken = assertFailsWith<DeviceException> { session.run(wizard, "create", mapOf("name" to "ikev2", "client-endpoint" to "vpn.example.net", "user" to "client1")).toList() }
        assertEquals(
            "the server rw already answers as vpn.example.net: clients would get its certificate; give the new server another name or address for clients, or change rw",
            taken.message,
        )
        assertFailsWith<DeviceException> { session.add(ipsec, mapOf("name" to "rw2", "server-id" to "vpn.example.net")) }
        // the file of a server so named would be that of the users of EAP or of L2TP
        for (name in listOf("users", "l2tp", "client-x")) {
            assertEquals("name: $name is not a value it takes", assertFailsWith<DeviceException> { session.add(ipsec, mapOf("name" to name, "server-id" to "203.0.113.5")) }.message)
        }
        assertTrue(host.ran.none { it.startsWith("umask 077; command -v pki") }, "${host.ran}")
    }

    private val options = mapOf(
        "proposals" to "aes256gcm16-prfsha384-ecp384", "esp-proposals" to "aes256gcm16-ecp384", "dpd-delay" to "60s", "rekey-time" to "8h", "child-rekey-time" to "2h",
        "mobike" to "no", "fragmentation" to "force", "unique" to "replace", "encap" to "yes", "send-cert" to "ifasked",
    )
    private val custom = IkeServer("rw", "vpn.example.net", "10.10.10.0/24", "", "1.1.1.1", "", options)

    @Test
    fun theOptionsOfAServerGoToItsFileAndComeBackToItsForm() {
        val lines = custom.text().lines().map { it.trim() }
        val written = listOf(
            "proposals = aes256gcm16-prfsha384-ecp384", "esp_proposals = aes256gcm16-ecp384", "dpd_delay = 60s", "rekey_time = 8h", "rekey_time = 2h", "mobike = no",
            "fragmentation = force", "unique = replace", "encap = yes", "send_cert = ifasked",
        )
        assertEquals(written, written.filter { it in lines })
        val fields = listOf("proposals", "esp-proposals", "dpd-delay", "rekey-time", "child-rekey-time", "mobike", "fragmentation", "unique", "encap", "send-cert")
        val row = parseIpsec("--nh-file-- /etc/swanctl/conf.d/networkhelm-rw.conf\n${custom.text()}\n").rows().single()
        assertEquals(listOf("aes256gcm16-prfsha384-ecp384", "aes256gcm16-ecp384", "60s", "8h", "2h", "false", "force", "replace", "true", "ifasked"), fields.map { row[it] })
        // the template without options writes what it wrote before 18.11, which reads back as the defaults of charon and of the template
        val plain = parseIpsec("--nh-file-- /etc/swanctl/conf.d/networkhelm-rw.conf\n${server.text()}\n").rows().single()
        assertEquals(
            listOf("aes256gcm16-prfsha256-ecp256,aes256-sha256-modp2048,aes256-sha256-ecp256,default", "30s", "", "true", "yes", "never", "false", "always"),
            listOf("proposals", "dpd-delay", "rekey-time", "mobike", "fragmentation", "unique", "encap", "send-cert").map { plain[it] },
        )
        assertTrue(listOf("mobike", "fragmentation", "encap", "rekey_time").none { key -> server.text().lines().any { it.trim().startsWith(key) } }, server.text())
    }

    @Test
    fun aChangeOfOneOptionKeepsTheOthersAsTheFileHasThemAndAValueSwanctlRefusesStopsTheForm() = runTest {
        val host = VpnHost(host(true).answers, mapOf("echo --nh-loaded--" to listOf(ok(state().replace(server.text(), custom.text())))))
        val session = SshHostSession(host, device, linux = true)
        session.set(ipsec, "rw", mapOf("mobike" to "yes", "dns" to "9.9.9.9"))
        assertEquals("${IkeServer("rw", "vpn.example.net", "10.10.10.0/24", "", "9.9.9.9", "0.0.0.0/0", options + ("mobike" to "yes")).text()}\n", host.given.single())
        for ((field, value) in listOf("dpd-delay" to "5 minutes", "proposals" to "aes256-sha256!", "fragmentation" to "maybe", "send-cert" to "never")) {
            assertEquals("$field: $value is not a value it takes", assertFailsWith<DeviceException> { session.set(ipsec, "rw", mapOf(field to value)) }.message)
        }
        assertEquals(1, host.given.size, "a refused value goes to no file")

        // the profiles say so where the proposals leave out what they ask for
        val windows = session.run(users, "windows", mapOf("number" to "alice")).toList().single()["conf"].orEmpty()
        val apple = session.run(users, "mobileconfig", mapOf("number" to "alice")).toList().single()["conf"].orEmpty()
        val gap = "the proposals of this server leave out AES256 with SHA256 and group 14, which this profile asks for: the device does not connect until they are back"
        assertTrue(windows.startsWith("# $gap\n") && "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- $gap -->\n" in apple, windows)
    }

    // systemctl list-unit-files 'strongswan*.service' as the read prints each unit: Ubuntu 24.04 of the lab (39002) on 18.11 word for word,
    // Debian 12 (39001) by the list the coordinator took there, the states of strongswan-starter.service written to the format
    @Test
    fun theUnitOfCharonIsTheOneThatIsNoAliasAndOpenRcHasNone() {
        val ubuntu = "--nh-unit-- strongswan-swanctl.service active alias alias\n--nh-unit-- strongswan.service active enabled enabled\n"
        val debian = "--nh-unit-- strongswan-starter.service inactive disabled disabled\n$ubuntu"
        for (text in listOf(ubuntu, debian)) assertEquals(listOf("strongswan.service", true, true), parseIpsec(text).let { listOf(it.unit, it.active, it.enabled) })
        assertEquals(listOf(null, true, false), parseIpsec("--nh-unit-- - active service \n").let { listOf(it.unit, it.active, it.enabled) })
    }

    // Debian 12 refused the alias the read took for charon before 18.11, after the connection was loaded
    @Test
    fun aServerThatFailsAfterItsConnectionIsLoadedGoesAgainAndLeavesItsNameFree() = runTest {
        val refused = "Failed to enable unit: Refusing to operate on alias name or linked unit file: strongswan-swanctl.service"
        val host = host(false, "systemctl enable" to ExecResult("", "$refused\n", 1))
        val values = mapOf("name" to "ikev2", "client-endpoint" to "203.0.113.5", "autostart" to "true", "user" to "client1", "password" to "rd-test-first")
        val failed = assertFailsWith<DeviceException> { SshHostSession(host, device, linux = true).run(wizard, "create", values).toList() }
        assertEquals(refused, failed.message)
        assertEquals(
            "cd /etc/swanctl && rm -f conf.d/networkhelm-ikev2.conf networkhelm/networkhelm-ikev2-ca.key x509ca/networkhelm-ikev2-ca.crt private/networkhelm-ikev2.key " +
                "x509/networkhelm-ikev2.crt && { swanctl --load-all >/dev/null 2>&1; true; }",
            host.ran.last(),
        )
        assertEquals(1, host.given.size, "the user of a server that is gone is not made")
    }

    @Test
    fun aUserOfEapHasItsSecretInTheFileOfItsOwnAndGoesOffWithItsSessions() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        val rows = session.rows("vpn/user").associateBy { it.id }
        assertEquals(listOf("ipsec/eap", "false", "true"), listOf(rows.getValue("alice")["servers"], rows.getValue("alice")["disabled"], rows.getValue("bob")["disabled"]))

        assertEquals("carol", session.add(users, mapOf("name" to "carol", "servers" to "ipsec/eap", "password" to "rd-test-carol")))
        val written = host.given.last()
        assertTrue("    eap-carol {\n        id = carol\n        secret = \"rd-test-carol\"\n    }" in written && "#[disabled]     eap-bob {" in written, written)
        assertTrue(host.sent.none { "rd-test-carol" in it }, "the password is on no command line")
        session.add(users, mapOf("name" to "dave", "servers" to "ipsec/eap"))
        assertTrue("secret = \"rdTestMade\"" in host.given.last(), host.given.last())

        session.set(users, "alice", mapOf("disabled" to "yes"))
        assertTrue("#[disabled]     eap-alice {" in host.given.last(), host.given.last())
        assertEquals("swanctl --terminate --ike-id 7 --force >/dev/null", host.ran.last())
    }

    @Test
    fun theProfilesOfAUserNameTheServerAndItsCaAndHoldNoPassword() = runTest {
        val session = SshHostSession(host(), device, linux = true)
        suspend fun export(kind: String) = session.run(users, kind, mapOf("number" to "alice")).toList().single()["conf"].orEmpty()
        val apple = export("mobileconfig")
        assertTrue(
            listOf("<key>RemoteAddress</key><string>vpn.example.net</string>", "<data>MIIBrdTestCa==</data>", "<key>AuthName</key><string>alice</string>", "<string>uuid-3</string>")
                .all { it in apple },
            apple,
        )
        val android = Json.parseToJsonElement(export("sswan")).jsonObject
        assertEquals(
            listOf("ikev2-eap", "vpn.example.net", "MIIBrdTestCa==", "alice"),
            listOf(
                android.getValue("type").jsonPrimitive.content, android.getValue("remote").jsonObject.getValue("addr").jsonPrimitive.content,
                android.getValue("remote").jsonObject.getValue("cert").jsonPrimitive.content, android.getValue("local").jsonObject.getValue("eap_id").jsonPrimitive.content,
            ),
        )
        val windows = export("windows")
        assertTrue("FromBase64String('MIIBrdTestCa==')" in windows && "Add-VpnConnection -Name 'rw' -ServerAddress 'vpn.example.net' -TunnelType IKEv2" in windows, windows)
        assertTrue(listOf(apple, windows, android.toString()).none { "rd-test-alice" in it }, "no profile holds the password")
        val text = session.run(users, "client-config", mapOf("number" to "alice")).toList().single()["conf"]
        assertEquals("rw: IKEv2, EAP-MSCHAPv2\nserver: vpn.example.net\nlogin: alice\npassword: rd-test-alice", text)

        // the import of a client reads back what the profiles say
        for (profile in listOf(apple, android.toString())) assertEquals(listOf("vpn.example.net", "alice", "MIIBrdTestCa=="), parseIkeProfile(profile)?.let { listOf(it.server, it.user, it.ca) })
        assertEquals("MIIBrdTestCa==", parseIkeProfile("-----BEGIN CERTIFICATE-----\nMIIBrd\nTestCa==\n-----END CERTIFICATE-----\n")?.ca)
        assertNull(parseIkeProfile("[Interface]\nPrivateKey = rd-test"))
    }
}
