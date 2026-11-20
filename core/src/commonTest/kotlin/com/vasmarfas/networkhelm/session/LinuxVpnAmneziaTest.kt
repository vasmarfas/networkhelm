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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxVpnAmneziaTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private fun key(tag: String) = tag.padEnd(43, 'A') + "="

    private val serverPrivate = key("rdTestAwgServerPrivate")
    private val serverPublic = key("rdTestAwgServerPublic")
    private val phonePublic = key("rdTestAwgPhonePublic")
    private val phonePrivate = key("rdTestAwgPhonePrivate")
    private val preshared = key("rdTestAwgPreshared")

    private val faces = MenuPath.parse("vpn/amneziawg")
    private val server = MenuPath.parse("vpn/amneziawg/server")
    private val importing = MenuPath.parse("vpn/amneziawg/import")

    // the names awg set lists, as AWG_USAGE leaves them of the usage of amneziawg-tools 1.0.20241018, which lists no key of the disguise,
    // and of 1.0.20260618, which lists S3, S4 and I1 to I5; written to the source of the tools, set.c, not recorded on a host: no
    // distribution of the lab packages AmneziaWG
    private val usage2024 = "listen-port\nfwmark\nprivate-key\npeer\npreshared-key\nendpoint\npersistent-keepalive\nallowed-ips\nadvanced-security\n"
    private val usage2026 = "listen-port\nfwmark\nprivate-key\njc\njmin\njmax\ns1\ns2\ns3\ns4\nh1\nh2\nh3\nh4\ni1\ni2\ni3\ni4\ni5\npeer\npreshared-key\nendpoint\n" +
        "persistent-keepalive\nallowed-ips\nadvanced-security\n"

    private val serverFile = "[Interface]\nAddress = 10.8.0.1/24\nListenPort = 51820\nPrivateKey = $serverPrivate\nJc = 4\nJmin = 10\nJmax = 50\nS1 = 95\nS2 = 31\n" +
        "H1 = 1020325451\nH2 = 1288052141\nH3 = 1528465083\nH4 = 1766607858\n\n[Peer]\n# Name = phone\nPublicKey = $phonePublic\nPresharedKey = $preshared\nAllowedIPs = 10.8.0.2/32\n"

    // the read as the host prints it: the line of awg show for the interface cut to the fields of wg, the keys of the file masked
    private fun state(file: String = serverFile) = "--nh-now-- 1794939096\n--nh-dump-- awg0\t\t$serverPublic\t51820\toff\n" +
        "--nh-dump-- awg0\t$phonePublic\t(set)\t203.0.113.7:40000\t10.8.0.2/32\t1794939000\t1024\t2048\t25\n--nh-file-- wg-quick /etc/amnezia/amneziawg/awg0.conf\n" +
        file.replace("PrivateKey = $serverPrivate", "PrivateKey =").replace("PresharedKey = $preshared", "PresharedKey =") +
        "--nh-public-- $serverPublic\n--nh-auto-- awg0 enabled\n--nh-kept-- /etc/amnezia/amneziawg/networkhelm/awg0/phone.key\n"

    private val tools = "for t in" to ok("awg\nawg-quick\nsystemctl\n")
    private val login = "id -u" to ok("0\n")

    @Test
    fun anInterfaceOfAwgQuickShowsItsDisguiseAndItsPeersWithoutAKey() = runTest {
        val host = VpnHost(listOf(tools, login, "echo \"--nh-now--" to ok(state())))
        val session = SshHostSession(host, device, linux = true)
        val awg = session.rows("vpn/amneziawg").single()
        assertEquals(
            listOf("awg0", "awg-quick", "true", "true", serverPublic, "", "51820", "4", "95", "31", "1766607858"),
            listOf("name", "source", "running", "autostart", "public-key", "private-key", "listen-port", "jc", "s1", "s2", "h4").map { awg[it] },
        )
        assertTrue("s3" !in awg.values && "i1" !in awg.values, "a key the file does not set stays out of the row: ${awg.values}")
        val phone = session.rows("vpn/amneziawg/peer").single()
        assertEquals(listOf("awg0/$phonePublic", "phone", "203.0.113.7:40000", "host"), listOf(phone.id) + listOf("name", "current-endpoint", "client-key").map { phone[it] })
        val read = host.ran.first { it.startsWith("echo \"--nh-now--") }
        assertTrue(read.contains("\nawg show all dump 2>/dev/null | awk") && "/etc/amnezia/amneziawg/*.conf" in read && "awg-quick@" in read && "nmcli" !in read, read)
        assertTrue("\nwg show all dump" in WIREGUARD_STATE && "nmcli" in WIREGUARD_STATE && "/etc/wireguard/*.conf" in WIREGUARD_STATE, WIREGUARD_STATE)
    }

    @Test
    fun aNewServerIsProposedWithTheDisguiseOfAmneziaVpnAndOffersWhatTheAwgOfTheHostKnows() = runTest {
        fun host(usage: String) = VpnHost(
            listOf(tools, login, "echo \"--nh-now--" to ok("--nh-now-- 1794939096\n"), "ls /sys/class/net" to ok("eth0\nlo\n--nh-udp--\n--nh-ip--\n--nh-default--\n"), "awg set" to ok(usage)),
        )
        val session = SshHostSession(host(usage2024), device, linux = true)
        val proposed = session.get(server, null)?.values.orEmpty()
        assertEquals(listOf("awg0", "51820", "10.8.0.1/24", "client1"), listOf("name", "listen-port", "address", "client").map { proposed[it] })
        assertTrue(proposed["jc"]?.toIntOrNull() in 4..6 && proposed["jmin"] == "10" && proposed["jmax"] == "50", "$proposed")
        assertTrue(listOf("s3", "s4", "i1").none { it in proposed }, "a disguise of version 1.0, which every client reads: $proposed")
        val fields = session.describe(server).arguments.map { it.name }
        assertTrue(listOf("jc", "s1", "h4").all { it in fields } && listOf("s3", "s4", "i1").none { it in fields }, "$fields")
        val newer = SshHostSession(host(usage2026), device, linux = true).describe(server).arguments
        assertEquals(listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5"), newer.filter { it.tab == "disguise" }.map { it.name })
    }

    @Test
    fun theProposedDisguiseKeepsTheLimitsOfAmneziaWg() {
        repeat(200) { seed ->
            val values = proposedDisguise(Random(seed))
            val s1 = values.getValue("s1").toInt()
            val s2 = values.getValue("s2").toInt()
            val types = listOf("h1", "h2", "h3", "h4").map { values.getValue(it).toLong() }
            assertTrue(s1 in 15..149 && s2 in 15..149 && s2 != s1 + 56 && types.distinct().size == 4 && types.all { it in 5..Int.MAX_VALUE.toLong() }, "$values")
            checkDisguise(values, awgKnows(usage2024))
        }
    }

    @Test
    fun aDisguiseOutsideItsLimitsOrBeyondTheAwgOfTheHostIsRefused() {
        val old = awgKnows(usage2024)
        val new = awgKnows(usage2026)
        assertEquals(setOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4"), old)
        assertEquals("jmax: Jmin 60 is above Jmax 50", assertFailsWith<DeviceException> { checkDisguise(mapOf("jmin" to "60", "jmax" to "50"), old) }.message)
        assertEquals(
            "s2: with S2 = S1 + 56 the first two messages of the handshake are of one length",
            assertFailsWith<DeviceException> { checkDisguise(mapOf("s1" to "20", "s2" to "76"), old) }.message,
        )
        // without S1 the padding of the first message is 0
        assertFailsWith<DeviceException> { checkDisguise(mapOf("s2" to "56"), old) }
        assertEquals(
            "h3: H1, H2, H3 and H4 need values of their own and ranges that do not overlap",
            assertFailsWith<DeviceException> { checkDisguise(mapOf("h1" to "100-200", "h2" to "300", "h3" to "150-160"), new) }.message,
        )
        assertEquals("the awg of the host does not know S3; update amneziawg-tools or leave it out", assertFailsWith<DeviceException> { checkDisguise(mapOf("s3" to "10"), old) }.message)
        assertEquals("the awg of the host does not know H1 = 100-200; update amneziawg-tools or leave it out", assertFailsWith<DeviceException> { checkDisguise(mapOf("h1" to "100-200"), old) }.message)
        checkDisguise(mapOf("s3" to "10", "s4" to "5", "h1" to "100-200", "h2" to "201-300", "i1" to "<r 2><b 0x858000010001>", "jc" to ""), new)
        assertEquals(listOf(5L..5L, 100L..200L, null, null, null), listOf("5", "100-200", "4", "200-100", "1-2-3").map(::headerRange))
    }

    @Test
    fun aServerOfOneStepGoesToAwgQuickAndGivesAClientTheDisguiseOfItsInterface() = runTest {
        val made = "[Interface]\nPrivateKey = $serverPrivate\nListenPort = 51820\nAddress = 10.8.0.1/24\nJc = 4\nJmin = 10\nJmax = 50\nS1 = 95\nS2 = 31\n" +
            "H1 = 1020325451\nH2 = 1288052141\nH3 = 1528465083\nH4 = 1766607858\n"
        val peer = "\n[Peer]\n# Name = phone\nPublicKey = $phonePublic\nPresharedKey = $preshared\nAllowedIPs = 10.8.0.2/32\n"
        fun running(file: String) = "--nh-now-- 1794939096\n--nh-dump-- awg0\t\t$serverPublic\t51820\toff\n--nh-file-- wg-quick /etc/amnezia/amneziawg/awg0.conf\n" +
            file.replace("PrivateKey = $serverPrivate", "PrivateKey =").replace("PresharedKey = $preshared", "PresharedKey =") + "--nh-public-- $serverPublic\n--nh-auto-- awg0 enabled\n"
        val host = VpnHost(
            listOf(
                tools, login, "awg set" to ok(usage2024), "awg genkey" to ok("$serverPrivate\n"), "umask 077; mkdir -p '/etc/amnezia/amneziawg/networkhelm/awg0'" to ok("$phonePublic\n"),
                "umask 077; mkdir -p '/etc/amnezia/amneziawg'" to ok(), "rm -f" to ok(), "systemctl enable" to ok(), "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"),
                "if systemctl cat" to ok(), "kill" to ok(), "awg genpsk" to ok("$preshared\n"), "cat '/etc/amnezia/amneziawg/awg0.conf'; echo" to ok("$made$peer\n--nh-key--\n$phonePrivate\n"),
                "cat '/etc/amnezia/amneziawg/awg0.conf'" to ok(made), "echo \"\$SSH_CONNECTION\"" to ok("192.168.10.2 50000 203.0.113.5 22\n"),
            ),
            mapOf("echo \"--nh-now--" to listOf(ok("--nh-now-- 1794939096\n"), ok(running(made)), ok(running(made + peer)))),
        )
        val values = mapOf(
            "name" to "awg0", "listen-port" to "51820", "autostart" to "true", "address" to "10.8.0.1/24", "client-dns" to "", "preshared-key" to "true", "nat" to "false",
            "client" to "phone", "keep-key" to "true", "jc" to "4", "jmin" to "10", "jmax" to "50", "s1" to "95", "s2" to "31", "h1" to "1020325451", "h2" to "1288052141",
            "h3" to "1528465083", "h4" to "1766607858",
        )
        val reply = SshHostSession(host, device, linux = true).run(server, "create", values).toList().single()
        assertEquals(made, host.given.first())
        val written = host.ran.first { it.startsWith("umask 077; mkdir -p '/etc/amnezia/amneziawg' ") }
        assertTrue(written.endsWith("\nawg-quick strip '/etc/amnezia/amneziawg/awg0.conf' 2>&1 >/dev/null"), written)
        val guard = host.ran.indexOf(host.ran.single { it.startsWith("B=") })
        assertEquals(
            "if systemctl cat awg-quick@'awg0' >/dev/null 2>&1 && ! systemctl is-active -q awg-quick@'awg0'; then systemctl start awg-quick@'awg0'; else awg-quick up 'awg0'; fi",
            host.ran[guard + 1],
        )
        assertTrue("systemctl enable awg-quick@'awg0'" in host.ran, "${host.ran}")
        assertEquals(
            "[Interface]\nPrivateKey = $phonePrivate\nAddress = 10.8.0.2/32\nJc = 4\nJmin = 10\nJmax = 50\nS1 = 95\nS2 = 31\nH1 = 1020325451\nH2 = 1288052141\n" +
                "H3 = 1528465083\nH4 = 1766607858\n\n[Peer]\nPublicKey = $serverPublic\nPresharedKey = $preshared\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = 203.0.113.5:51820",
            reply["conf"],
        )
        assertTrue(host.ran.none { serverPrivate in it || phonePrivate in it || preshared in it }, "${host.ran}")
    }

    // the way to the computer of the coordinator of the lab on 18.11, 192.168.11.2 behind the gateway of 39002, as ip route get prints it
    private val way = "set -- \$SSH_CONNECTION" to ok("192.168.11.2\n192.168.11.2 via 192.168.10.1 dev eth0 src 192.168.10.249 uid 0 \n    cache \n")

    // a configuration of a client as AmneziaVPN saves it for AmneziaWG, written to its format with the keys of the test
    private val client = "[Interface]\nAddress = 10.8.1.2/32\nPrivateKey = $phonePrivate\nJc = 4\nJmin = 10\nJmax = 50\nS1 = 95\nS2 = 31\nH1 = 1020325451\n" +
        "H2 = 1288052141\nH3 = 1528465083\nH4 = 1766607858\n\n[Peer]\nPublicKey = $serverPublic\nPresharedKey = $preshared\nAllowedIPs = 0.0.0.0/0, ::/0\n" +
        "Endpoint = 203.0.113.5:51820\nPersistentKeepalive = 25"

    @Test
    fun theConfigurationOfAmneziaVpnIsImportedToAwgQuickKeepingThisSessionAndStartsAtBootAfterTheGuard() = runTest {
        val host = VpnHost(
            listOf(
                tools, login, "echo \"--nh-now--" to ok("--nh-now-- 1794939096\n"), "ls /sys/class/net" to ok("eth0\nlo\n--nh-udp--\n--nh-ip--\n--nh-default--\n"), way,
                "awg set" to ok(usage2024), "umask 077; mkdir -p '/etc/amnezia/amneziawg'" to ok(), "rm -f" to ok(), "systemctl enable" to ok(),
                "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "if systemctl cat" to ok(), "kill" to ok(),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        assertEquals(listOf("awg0", "true", "true"), session.get(importing, null)?.values.orEmpty().let { listOf(it["name"], it["autostart"], it["keep-session"]) })
        session.run(importing, "import", mapOf("name" to "awg0", "autostart" to "true", "keep-session" to "true", "text" to client)).toList()
        val kept = "H4 = 1766607858\nPreUp = ip -4 route replace 192.168.11.2/32 via 192.168.10.1 dev eth0 # networkhelm-session\n" +
            "PostDown = ip -4 route del 192.168.11.2/32 via 192.168.10.1 dev eth0 || true # networkhelm-session\n"
        assertEquals(client.replace("H4 = 1766607858\n", kept) + "\n", host.given.single())
        val guard = host.ran.indexOf(host.ran.single { it.startsWith("B=") })
        assertTrue(host.ran[guard + 1].startsWith("umask 077; mkdir -p '/etc/amnezia/amneziawg' && "), "the file goes in under the guard: ${host.ran}")
        assertTrue(host.ran[guard + 3].startsWith("if systemctl cat awg-quick@'awg0'"), "${host.ran}")
        assertEquals("systemctl enable awg-quick@'awg0'", host.ran.last(), "it starts at boot once the guard let it come up")
        assertTrue(host.ran.none { phonePrivate in it }, "${host.ran}")

        val newer = client.replace("H4 = 1766607858\n", "H4 = 1766607858\nS3 = 20\nI1 = <r 2><b 0x858000010001>\n")
        val unknown = assertFailsWith<DeviceException> { session.run(importing, "import", mapOf("name" to "awg1", "text" to newer)).toList() }
        assertEquals("the awg of the host does not know S3; update amneziawg-tools or leave it out", unknown.message)
        assertEquals(1, host.given.size, "a refused configuration goes to no file")
    }

    // the way of 39002 on 18.11: the session came from 10.10.10.2, and its answers went out by the default route of an OpenConnect
    // client, which has no gateway
    @Test
    fun aSessionAnsweredThroughATunnelWithoutAGatewayKeepsItsWayAsWell() = runTest {
        val host = VpnHost(
            listOf(
                tools, login, "echo \"--nh-now--" to ok("--nh-now-- 1794939096\n"), "ls /sys/class/net" to ok("eth0\nlo\noc-client\n--nh-udp--\n--nh-ip--\n--nh-default--\n"),
                "set -- \$SSH_CONNECTION" to ok("10.10.10.2\n10.10.10.2 dev oc-client src 10.10.0.29 uid 1001 \n    cache \n"),
                "awg set" to ok(usage2024), "umask 077; mkdir -p '/etc/amnezia/amneziawg'" to ok(), "rm -f" to ok(), "systemctl enable" to ok(),
                "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "if systemctl cat" to ok(), "kill" to ok(),
            ),
        )
        SshHostSession(host, device, linux = true).run(importing, "import", mapOf("name" to "awg0", "keep-session" to "true", "text" to client)).toList()
        val kept = "H4 = 1766607858\nPreUp = ip -4 route replace 10.10.10.2/32 dev oc-client # networkhelm-session\n" +
            "PostDown = ip -4 route del 10.10.10.2/32 dev oc-client || true # networkhelm-session\n"
        assertEquals(client.replace("H4 = 1766607858\n", kept) + "\n", host.given.single())
    }

    @Test
    fun theWindowOfWireGuardSendsAConfigurationOfAmneziaWgToItsOwn() = runTest {
        val host = VpnHost(listOf("for t in" to ok("wg\nwg-quick\nsystemctl\n"), login, "echo \"--nh-now--" to ok("--nh-now-- 1794939096\n")))
        val refused = assertFailsWith<DeviceException> {
            SshHostSession(host, device, linux = true).run(MenuPath.parse("vpn/wireguard/import"), "import", mapOf("name" to "wg1", "text" to client)).toList()
        }
        assertEquals("text: the configuration sets Jc, which belongs to AmneziaWG; import it in the window of AmneziaWG", refused.message)
        assertTrue(host.given.isEmpty())
    }

    // Alpine has no service of OpenRC for awg-quick: autostart writes the one of NetworkHelm and links the interface to it, as Alpine links
    // wg-quick.<interface> to the service of wireguard-tools-openrc
    @Test
    fun anInterfaceStartsAtBootOnOpenRcByAServiceOfItsOwn() = runTest {
        val host = VpnHost(
            listOf("for t in" to ok("awg\nawg-quick\n"), login, "echo \"--nh-now--" to ok(state()), "umask 077; mkdir -p '/etc/init.d'" to ok(), "rm -f" to ok(), "ln -sf" to ok()),
        )
        SshHostSession(host, device, linux = true).set(faces, "awg0", mapOf("autostart" to "yes"))
        assertEquals("$AWG_OPENRC\n", host.given.single())
        val written = host.ran.single { it.startsWith("umask 077; mkdir -p '/etc/init.d'") }
        assertTrue(written.endsWith("\n[ -d /etc/runlevels ] || { echo 'autostart: the host has neither systemd nor OpenRC'; exit 1; }"), written)
        assertEquals("ln -sf awg-quick /etc/init.d/awg-quick.'awg0' && rc-update add awg-quick.'awg0' default", host.ran.last())
        assertTrue("#!/sbin/openrc-run" in AWG_OPENRC && "awg-quick up \"\${RC_SVCNAME#*.}\"" in AWG_OPENRC && "awg-quick down \"\${RC_SVCNAME#*.}\"" in AWG_OPENRC)

        val again = VpnHost(listOf("for t in" to ok("awg\nawg-quick\n"), login, "echo \"--nh-now--" to ok(state() + "--nh-openrc--\n"), "ln -sf" to ok()))
        SshHostSession(again, device, linux = true).set(faces, "awg0", mapOf("autostart" to "yes"))
        assertTrue(again.given.isEmpty(), "a service there already is not written again")
    }

    @Test
    fun aChangedDisguiseStartsTheInterfaceAgainUnderTheGuard() = runTest {
        val host = VpnHost(
            listOf(
                tools, login, "echo \"--nh-now--" to ok(state()), "awg set" to ok(usage2024), "cat '/etc/amnezia/amneziawg/awg0.conf'" to ok(serverFile),
                "echo \"\$SSH_CONNECTION\"; ip -o addr show" to ok("192.168.10.2 50000 192.168.10.249 22\n2: eth0    inet 192.168.10.249/24 scope global eth0\n"),
                "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "umask 077; mkdir -p '/etc/amnezia/amneziawg'" to ok(), "rm -f" to ok(), "if systemctl is-active" to ok(),
                "kill" to ok(),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        session.set(faces, "awg0", mapOf("jc" to "6", "h4" to ""))
        val written = host.given.single()
        assertTrue("Jc = 6\n" in written && "H4" !in written && "H3 = 1528465083\n" in written, written)
        val guard = host.ran.indexOf(host.ran.single { it.startsWith("B=") })
        assertEquals(
            "if systemctl is-active -q awg-quick@'awg0' 2>/dev/null; then systemctl restart awg-quick@'awg0'; else awg-quick down 'awg0' && awg-quick up 'awg0'; fi",
            host.ran.drop(guard).first { it.startsWith("if systemctl is-active") },
        )
        val bad = assertFailsWith<DeviceException> { session.set(faces, "awg0", mapOf("s1" to "abc")) }
        assertEquals("s1: abc is not a value it takes", bad.message)
        val padded = assertFailsWith<DeviceException> { session.set(faces, "awg0", mapOf("s2" to "151")) }
        assertEquals("s2: with S2 = S1 + 56 the first two messages of the handshake are of one length", padded.message)
        assertEquals(1, host.given.size)
    }
}
