package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.sensitive
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxVpnWireGuardTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    // keys of the form wg takes that no host has: the samples of the lab of 17.11 carry these in place of their own
    private fun key(tag: String) = tag.padEnd(43, 'A') + "="

    private val serverPrivate = key("rdTestServerPrivate")
    private val serverPublic = key("rdTestServerPublic")
    private val clientPublic = key("rdTestClientPublic")
    private val clientPrivate = key("rdTestClientPrivate")
    private val phonePublic = key("rdTestPhonePublic")
    private val preshared = key("rdTestPreshared")
    private val managerPublic = key("rdTestManagerPublic")
    private val managerPrivate = key("rdTestManagerPrivate")
    private val managerPeer = key("rdTestManagerPeer")
    private val networkdPublic = key("rdTestNetworkdPublic")
    private val networkdPeer = key("rdTestNetworkdPeer")

    private val wg = MenuPath.parse("vpn/wireguard")
    private val peers = MenuPath.parse("vpn/wireguard/peer")

    // the read of the server of the lab pair, Debian 12 with wireguard-tools v1.0.20210914, its client handshaking
    private val serverFile = "[Interface]\nAddress = 10.66.0.1/24\nListenPort = 51820\nPrivateKey = $serverPrivate\n\n[Peer]\n# Name = lab-client\nPublicKey = $clientPublic\nAllowedIPs = 10.66.0.2/32\n"

    private fun serverState(file: String = serverFile) = "--nh-now-- 1794939096\n" +
        "--nh-dump-- wg0\t\t$serverPublic\t51820\toff\n" +
        "--nh-dump-- wg0\t$clientPublic\t(none)\t192.168.10.249:59837\t10.66.0.2/32\t1794939064\t8448\t2592\toff\n" +
        "--nh-file-- wg-quick /etc/wireguard/wg0.conf\n" + file.replace("PrivateKey = $serverPrivate", "PrivateKey =") +
        "--nh-public-- $serverPublic\n--nh-auto-- wg0 disabled\n"

    // its client, Ubuntu 24.04
    private val clientState = "--nh-now-- 1794939097\n" +
        "--nh-dump-- wg0\t\t$clientPublic\t59837\toff\n" +
        "--nh-dump-- wg0\t$serverPublic\t(none)\t192.168.10.250:51820\t10.66.0.0/24\t1794939064\t2592\t8448\t25\n" +
        "--nh-file-- wg-quick /etc/wireguard/wg0.conf\n[Interface]\nAddress = 10.66.0.2/24\nPrivateKey =\n\n" +
        "[Peer]\nPublicKey = $serverPublic\nEndpoint = 192.168.10.250:51820\nAllowedIPs = 10.66.0.0/24\nPersistentKeepalive = 25\n" +
        "--nh-public-- $clientPublic\n--nh-auto-- wg0 disabled\n"

    // the profile wg9 that nmcli connection import made on Fedora 44
    private fun managerFile(privateKey: String, psk: String) = "[connection]\nid=wg9\nuuid=a4cbea79-9b10-426e-9300-e6656b0b00d8\ntype=wireguard\nautoconnect=false\ninterface-name=wg9\n\n" +
        "[wireguard]\nlisten-port=51829\nprivate-key=$privateKey\n\n[wireguard-peer.$managerPeer]\npreshared-key=$psk\npreshared-key-flags=0\nallowed-ips=10.79.0.2/32;fd79::2/128;\n\n" +
        "[ipv4]\naddress1=10.79.0.1/24\ndns=10.79.0.1;\ndns-search=~;\nmethod=manual\n\n[ipv6]\naddr-gen-mode=default\naddress1=fd79::1/64\nmethod=manual\n\n[proxy]\n"

    private val managerState = "--nh-now-- 1794939167\n" +
        "--nh-dump-- wg9\t\t$managerPublic\t51829\toff\n" +
        "--nh-dump-- wg9\t$managerPeer\t(set)\t(none)\t10.79.0.2/32,fd79::2/128\t0\t0\t0\toff\n" +
        "--nh-file-- NetworkManager /etc/NetworkManager/system-connections/wg9.nmconnection\n" + managerFile("", "") + "--nh-public-- $managerPublic\n"

    // the .netdev wg2 of systemd-networkd on Arch, its key in a file, and the .network of eth0 that names another interface
    private val networkdState = "--nh-now-- 1794939112\n" +
        "--nh-dump-- wg2\t\t$networkdPublic\t51822\toff\n" +
        "--nh-dump-- wg2\t$networkdPeer\t(none)\t(none)\t10.77.0.2/32\t0\t0\t0\t25\n" +
        "--nh-file-- systemd-networkd /etc/systemd/network/90-wg2.netdev\n" +
        "[NetDev]\nName=wg2\nKind=wireguard\nDescription=lab\n\n[WireGuard]\nListenPort=51822\nPrivateKeyFile=/etc/systemd/network/wg2.key\n\n" +
        "[WireGuardPeer]\nPublicKey=$networkdPeer\nAllowedIPs=10.77.0.2/32\nPersistentKeepalive=25\n--nh-public-- $networkdPublic\n" +
        "--nh-network-- /etc/systemd/network/90-wg2.network\n[Match]\nName=wg2\n\n[Network]\nAddress=10.77.0.1/24\n\n" +
        "--nh-network-- /etc/systemd/network/eth0.network\n[Match]\nName = eth0\n\n[Network]\nDescription = Interface eth0 autoconfigured by PVE\nDHCP = ipv4\nIPv6AcceptRA = false\n\n"

    @Test
    fun theServerAndTheClientOfTheLabShowTheirInterfacesAndPeersWithoutAKey() {
        val server = parseWireGuard(serverState())
        val face = server.interfaces().single()
        assertEquals(
            listOf("wg0", "wg-quick", "true", "false", serverPublic, "", "51820", "10.66.0.1/24", "false", "1", "8448", "2592"),
            listOf("name", "source", "running", "autostart", "public-key", "private-key", "listen-port", "address", "nat", "peers", "rx", "tx").map { face[it] },
        )
        val peer = server.peers().single()
        assertEquals("wg0/$clientPublic", peer.id)
        assertEquals(
            listOf("lab-client", "10.66.0.2/32", "", "192.168.10.249:59837", "32s", "8448", "2592", "", "false", ""),
            listOf("name", "allowed-ips", "endpoint", "current-endpoint", "last-handshake", "rx", "tx", "preshared-key", "disabled", "client-key").map { peer[it] },
        )

        val client = parseWireGuard(clientState)
        assertEquals(listOf("false", "59837", "10.66.0.2/24"), listOf("autostart", "listen-port", "address").map { client.interfaces().single()[it] })
        assertEquals(
            listOf("192.168.10.250:51820", "10.66.0.0/24", "25", "33s", "2592", "8448"),
            listOf("endpoint", "allowed-ips", "persistent-keepalive", "last-handshake", "rx", "tx").map { client.peers().single()[it] },
        )

        val alpine = parseWireGuard("--nh-now-- 1794939119\n--nh-openrc--\n")
        assertTrue(alpine.faces.isEmpty() && alpine.openRc)
    }

    @Test
    fun aProfileOfNetworkManagerAndANetdevOfNetworkdAreInterfacesAsWell() {
        val state = parseWireGuard(managerState + networkdState)
        val (manager, networkd) = state.interfaces()
        assertEquals(
            listOf("wg9", "NetworkManager", "true", "false", "51829", "10.79.0.1/24, fd79::1/64", "10.79.0.1", "1", managerPublic),
            listOf("name", "source", "running", "autostart", "listen-port", "address", "dns", "peers", "public-key").map { manager[it] },
        )
        assertEquals(
            listOf("wg2", "systemd-networkd", "true", "true", "51822", "10.77.0.1/24", "", "", networkdPublic),
            listOf("name", "source", "running", "autostart", "listen-port", "address", "dns", "mtu", "public-key").map { networkd[it] },
        )
        val (managerRow, networkdRow) = state.peers()
        assertEquals(listOf("wg9/$managerPeer", "10.79.0.2/32, fd79::2/128", "", ""), listOf(managerRow.id, managerRow["allowed-ips"], managerRow["endpoint"], managerRow["persistent-keepalive"]))
        assertEquals(listOf("wg2/$networkdPeer", "10.77.0.2/32", "25", ""), listOf(networkdRow.id, networkdRow["allowed-ips"], networkdRow["persistent-keepalive"], networkdRow["last-handshake"]))
    }

    @Test
    fun aPeerTurnedOffStaysInItsFileBehindTheMarkOfPiVpnAndComesBackAsItWas() {
        val text = "[Interface]\nPrivateKey = $serverPrivate\nListenPort = 51820\n\n### begin phone ###\n[Peer]\nPublicKey = $clientPublic\nPresharedKey = $preshared\n" +
            "AllowedIPs = 10.6.0.2/32\n### end phone ###\n"
        val file = WgIni(text)
        file.all("Peer").single().turn(false)
        assertTrue(
            "#[disabled] [Peer]\n#[disabled] PublicKey = $clientPublic\n#[disabled] PresharedKey = $preshared\n#[disabled] AllowedIPs = 10.6.0.2/32\n#[disabled] ### end phone ###\n" in file.text(),
            file.text(),
        )

        val again = WgIni(file.text())
        val off = again.all("Peer").single()
        assertTrue(off.off)
        assertEquals(clientPublic, off.value("PublicKey"))
        assertEquals(null, again.part("Peer"), "a peer turned off is none of the interface")
        off.set("AllowedIPs", "10.6.0.3/32", " = ")
        off.turn(true)
        assertEquals(text.replace("10.6.0.2/32", "10.6.0.3/32"), again.text())
    }

    @Test
    fun aNewPeerGetsKeysMadeOnTheHostAndTheNextFreeAddressAndJoinsTheRunningInterfaceByWgSet() = runTest {
        val host = VpnHost(
            listOf(
                "for t in" to ok("wg\nwg-quick\nsystemctl\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(serverState()),
                "umask 077; mkdir -p '/etc/wireguard/networkhelm/wg0'" to ok("$phonePublic\n"), "wg genpsk" to ok("$preshared\n"),
                "cat '/etc/wireguard/wg0.conf'" to ok(serverFile), "umask 077; mkdir -p '/etc/wireguard'" to ok(), "rm -f" to ok(), "wg set" to ok(),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        assertEquals("wg0/$phonePublic", session.add(peers, mapOf("interface" to "wg0", "name" to "phone", "preshared-key" to "auto")))
        assertTrue(
            host.ran.any { it.startsWith("umask 077; mkdir -p '/etc/wireguard/networkhelm/wg0' && wg genkey > '/etc/wireguard/networkhelm/wg0/phone.key.new' && wg pubkey < ") },
            "${host.ran}",
        )
        assertEquals(
            serverFile + "\n[Peer]\n# Name = phone\nPublicKey = $phonePublic\nPresharedKey = $preshared\nAllowedIPs = 10.66.0.3/32\n",
            host.given.single { it.startsWith("[Interface]") },
        )
        assertTrue(host.ran.any { it.startsWith("umask 077; mkdir -p '/etc/wireguard' && ") && it.endsWith("\nwg-quick strip '/etc/wireguard/wg0.conf' 2>&1 >/dev/null") }, "${host.ran}")
        assertEquals(
            "wg set 'wg0' peer '$phonePublic' preshared-key /dev/stdin allowed-ips '10.66.0.3/32' persistent-keepalive 0 && " +
                "{ ip -4 route show dev 'wg0' match 10.66.0.3/32 | grep -q . || ip -4 route add 10.66.0.3/32 dev 'wg0'; }",
            host.ran.last(),
        )
        assertEquals(preshared, host.given.last())
        assertTrue(host.ran.none { it.startsWith("B=") }, "a peer in the network of the interface moves no route")

        session.add(peers, mapOf("interface" to "wg0", "name" to "tablet"))
        assertTrue("# Name = tablet\nPublicKey = $phonePublic\nPresharedKey = $preshared\n" in host.given.dropLast(1).last(), "a client the host makes the keys of gets a preshared key")
    }

    @Test
    fun aPeerTheRunningInterfaceRefusesLeavesTheFileAsItWasAndNoKeyBehind() = runTest {
        val host = VpnHost(
            listOf(
                "for t in" to ok("wg\nwg-quick\nsystemctl\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(serverState()),
                "umask 077; mkdir -p '/etc/wireguard/networkhelm/wg0'" to ok("$phonePublic\n"), "wg genpsk" to ok("$preshared\n"),
                "cat '/etc/wireguard/wg0.conf'" to ok(serverFile), "umask 077; mkdir -p '/etc/wireguard'" to ok(), "rm -f" to ok(),
                "wg set" to ExecResult("", "", 1),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        assertFailsWith<DeviceException> { session.add(peers, mapOf("interface" to "wg0", "name" to "phone", "preshared-key" to "auto")) }
        assertEquals(serverFile, host.given.last { it.startsWith("[Interface]") })
        assertEquals("rm -f '/etc/wireguard/networkhelm/wg0/phone.key'", host.ran.last())
    }

    @Test
    fun aKeyGoesToTheHostAsInputAndNeverIntoACommandAnErrorOrARow() = runTest {
        val host = VpnHost(
            listOf(
                "for t in" to ok("wg\nwg-quick\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(serverState()), "cat '/etc/wireguard/wg0.conf'" to ok(serverFile),
                "umask 077" to ExecResult("", "Key is not the correct length or format: `$serverPrivate'", 1), "if [ -e" to ok(),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        val refused = assertFailsWith<DeviceException> { session.set(wg, "wg0", mapOf("listen-port" to "51830")) }
        assertEquals("Key is not the correct length or format: `(hidden)'", refused.message)
        assertEquals("if [ -e '/etc/wireguard/.wg0.conf.networkhelm-old' ]; then mv -f '/etc/wireguard/.wg0.conf.networkhelm-old' '/etc/wireguard/wg0.conf'; else rm -f '/etc/wireguard/wg0.conf'; fi", host.ran.last())
        assertEquals("Key is not the correct length or format: `(hidden)'", hidden("Key is not the correct length or format: `AAAA'"), "wg quotes a short key too")
        val wrong = assertFailsWith<DeviceException> { session.set(wg, "wg0", mapOf("private-key" to "rd-not-a-key-but-a-secret")) }
        assertTrue("rd-not-a-key-but-a-secret" !in wrong.message.orEmpty(), wrong.message)
        val shown = (session.rows("vpn/wireguard") + session.rows("vpn/wireguard/peer")).flatMap { it.values.values }
        assertTrue(shown.none { serverPrivate in it }, "$shown")
        assertTrue(host.ran.none { serverPrivate in it }, "${host.ran}")
        assertTrue(host.given.single().contains("PrivateKey = $serverPrivate"))
        assertTrue(listOf("private-key", "preshared-key").all { sensitive(it) }, "the journal hides them by name")

        val asking = VpnHost(
            listOf(
                "for t in" to ok("wg\nwg-quick\n"), "id -u" to ok("1000\n"), "sudo -n true" to ExecResult("", "sudo: a password is required", 1),
                "sudo -S -p '' true" to ok(), "echo \"--nh-now--" to ok(serverState()), "cat '/etc/wireguard/wg0.conf'" to ok(serverFile), "umask 077" to ok(), "rm -f" to ok(),
                "wg set" to ok(),
            ),
        )
        SshHostSession(asking, device, linux = true, password = "rd-test-pw").set(wg, "wg0", mapOf("listen-port" to "51830"))
        assertTrue(asking.sent.any { it.startsWith("sudo -k -S -p '' sh -c 'umask 077;") }, "${asking.sent}")
        assertTrue(asking.given.any { it.startsWith("rd-test-pw\n[Interface]\nAddress = 10.66.0.1/24\nListenPort = 51830\nPrivateKey = $serverPrivate\n") }, "${asking.given}")
        assertEquals("wg set 'wg0' listen-port 51830", asking.ran.last())
    }

    @Test
    fun anInterfaceComesUpUnderTheGuardAndGoesDownAgainWhenNoNewLoginGetsIn() = runTest {
        val stopped = "--nh-now-- 1794939096\n--nh-file-- wg-quick /etc/wireguard/wg0.conf\n[Interface]\nAddress = 10.66.0.2/24\nPrivateKey =\n\n" +
            "[Peer]\nPublicKey = $serverPublic\nEndpoint = 192.168.10.250:51820\nAllowedIPs = 0.0.0.0/0\n--nh-public-- $clientPublic\n"
        val host = VpnHost(
            listOf(
                "for t in" to ok("wg\nwg-quick\nsystemctl\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(stopped),
                "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "if systemctl cat" to ok(), "true" to ok(), "kill" to ok(),
            ),
        )
        var logins = 0
        val session = SshHostSession(host, device, linux = true) { if (++logins > 1) throw DeviceException(ErrorCategory.ConnectionLost, "timed out") }
        val refused = assertFailsWith<DeviceException> { session.run(wg, "up", mapOf("numbers" to "wg0")).toList() }
        assertTrue("no new SSH session" in refused.message.orEmpty(), refused.message)
        val guard = host.ran.single { it.startsWith("B=") }
        assertTrue("cp -p '/etc/wireguard/wg0.conf' \"\$B/0\"" in guard, guard)
        assertEquals(
            "if systemctl cat wg-quick@'wg0' >/dev/null 2>&1 && ! systemctl is-active -q wg-quick@'wg0'; then systemctl start wg-quick@'wg0'; else wg-quick up 'wg0'; fi",
            host.ran[host.ran.indexOf(guard) + 1],
        )
        assertTrue(host.ran.last().startsWith("kill 4242; sh -c '") && "wg-quick down" in host.ran.last(), host.ran.last())
    }

    @Test
    fun theInterfaceThisSessionComesByStaysUp() = runTest {
        val host = VpnHost(
            listOf(
                "for t in" to ok("wg\nwg-quick\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(serverState()),
                "echo \"\$SSH_CONNECTION\"; ip -o addr show" to ok("10.66.0.2 50000 10.66.0.1 22\n4: wg0    inet 10.66.0.1/24 scope global wg0\\       valid_lft forever preferred_lft forever\n"),
            ),
        )
        val refused = assertFailsWith<DeviceException> { SshHostSession(host, device, linux = true).run(wg, "down", mapOf("numbers" to "wg0")).toList() }
        assertEquals(ErrorCategory.ArgumentValue, refused.category)
        assertTrue(host.ran.none { "wg-quick down" in it }, "${host.ran}")
    }

    @Test
    fun theConfigurationOfADeviceCarriesTheKeyTheHostKeepsAndTheServerAsThisSessionReachesIt() = runTest {
        val host = VpnHost(
            listOf(
                "for t in" to ok("wg\nwg-quick\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(serverState() + "--nh-kept-- /etc/wireguard/networkhelm/wg0/lab-client.key\n"),
                "cat '/etc/wireguard/wg0.conf'; echo" to ok("$serverFile\n--nh-key--\n$clientPrivate\n"), "echo \"\$SSH_CONNECTION\"" to ok("192.168.10.2 50000 192.168.10.250 22\n"),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        assertEquals("host", session.rows("vpn/wireguard/peer").single()["client-key"])
        val conf = session.run(peers, "client-config", mapOf("number" to "wg0/$clientPublic")).toList().single()["conf"]
        assertEquals(
            "[Interface]\nPrivateKey = $clientPrivate\nAddress = 10.66.0.2/32\n\n[Peer]\nPublicKey = $serverPublic\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = 192.168.10.250:51820",
            conf,
        )
        assertTrue(host.ran.none { clientPrivate in it })
    }

    @Test
    fun theAddressesOfAProfileOfNetworkManagerGoByFamilyAndAFileItCannotLoadGoesBack() = runTest {
        val path = "/etc/NetworkManager/system-connections/wg9.nmconnection"
        val stopped = managerState.lines().filterNot { it.startsWith("--nh-dump--") }.joinToString("\n")
        fun host(load: String) = VpnHost(
            listOf(
                "for t in" to ok("wg\nnmcli\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(stopped), "cat '$path'" to ok(managerFile(managerPrivate, preshared)),
                "umask 077; mkdir -p '/etc/NetworkManager/system-connections'" to ok(load), "rm -f" to ok(), "if [ -e" to ok(),
            ),
        )
        val loaded = host("")
        SshHostSession(loaded, device, linux = true).set(wg, "wg9", mapOf("address" to "10.79.0.3/24, fd79::3/64"))
        val written = loaded.given.single()
        assertTrue(
            "[ipv4]\ndns=10.79.0.1;\ndns-search=~;\nmethod=manual\naddress1=10.79.0.3/24\n\n[ipv6]\naddr-gen-mode=default\nmethod=manual\naddress1=fd79::3/64\n\n[proxy]\n" in written,
            written,
        )
        assertTrue("private-key=$managerPrivate" in written && "preshared-key=$preshared" in written)
        assertTrue(loaded.ran.single { it.startsWith("umask 077") }.endsWith("\nnmcli connection load '$path' 2>&1"))
        assertEquals("rm -f '/etc/NetworkManager/system-connections/.wg9.nmconnection.networkhelm-old'", loaded.ran.last())

        // NetworkManager of Fedora 44 answers a file it cannot read so, and with 0
        val refused = host("Could not load file '$path'\n")
        val error = assertFailsWith<DeviceException> { SshHostSession(refused, device, linux = true).set(wg, "wg9", mapOf("address" to "10.79.0.3/24")) }
        assertEquals("Could not load file '$path'", error.message)
        assertEquals(
            "if [ -e '/etc/NetworkManager/system-connections/.wg9.nmconnection.networkhelm-old' ]; then mv -f '/etc/NetworkManager/system-connections/.wg9.nmconnection.networkhelm-old' '$path'; " +
                "else rm -f '$path'; fi",
            refused.ran.last(),
        )
    }

    // the sockets of UDP as the kernel lists them, written to its format: a running interface on 51820 and a service on 51821 over IPv6
    private val udp = "   sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops\n" +
        "  316: 00000000:CA6C 00000000:0000 07 00000000:00000000 00:00000000 00000000     0        0 0 2 0000000000000000 0\n" +
        "  317: 3500007F:0035 00000000:0000 07 00000000:00000000 00:00000000 00000000   991        0 21390 2 0000000000000000 0\n" +
        "  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops\n" +
        "  542: 00000000000000000000000000000000:CA6D 00000000000000000000000000000000:0000 07 00000000:00000000 00:00000000 00000000     0        0 31337 2 0000000000000000 0\n"

    @Test
    fun aNewInterfaceTakesTheFirstFreePortComesUpAtOnceAndHasItsPortOpenInUfw() = runTest {
        val other = "--nh-now-- 1794939096\n--nh-file-- wg-quick /etc/wireguard/wg9.conf\n[Interface]\nListenPort = 51822\nPrivateKey =\n--nh-auto-- wg9 disabled\n"
        val host = VpnHost(
            listOf(
                "for t in" to ok("wg\nwg-quick\nsystemctl\nufw\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(other), "ls /sys/class/net" to ok("eth0\nlo\n--nh-udp--\n$udp"),
                "wg genkey" to ok("$serverPrivate\n"), "umask 077; mkdir -p '/etc/wireguard'" to ok(), "rm -f" to ok(), "systemctl enable" to ok(),
                "ufw status" to ok("Status: active\n\nTo                         Action      From\n"), "for n in" to ok(), "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"),
                "if systemctl cat" to ok(), "kill" to ok(), "cat '/etc/wireguard/wg9.conf'" to ok("[Interface]\nListenPort = 51822\nPrivateKey = $clientPrivate\n"),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        assertEquals("wg1", session.add(wg, mapOf("name" to "wg1", "address" to "10.67.0.1/24", "autostart" to "yes")))
        assertEquals("[Interface]\nPrivateKey = $serverPrivate\nListenPort = 51823\nAddress = 10.67.0.1/24\n", host.given.single())
        assertTrue("systemctl enable wg-quick@'wg1'" in host.ran, "${host.ran}")
        val open = "s/^\\[ *\\([0-9]*\\)\\].*# networkhelm-port-wg1 *\$/\\1/p"
        assertTrue(host.ran.any { open in it && it.endsWith(" && ufw allow 51823/udp comment networkhelm-port-wg1") }, "${host.ran}")
        val guard = host.ran.single { it.startsWith("B=") }
        assertEquals(
            "if systemctl cat wg-quick@'wg1' >/dev/null 2>&1 && ! systemctl is-active -q wg-quick@'wg1'; then systemctl start wg-quick@'wg1'; else wg-quick up 'wg1'; fi",
            host.ran[host.ran.indexOf(guard) + 1],
        )
        assertEquals(listOf("kill 4242; rm -rf '/tmp/networkhelm-vpn.Ab12Cd'", "systemctl enable wg-quick@'wg1'"), host.ran.takeLast(2), "it starts at boot once the guard let it come up")

        session.set(wg, "wg9", mapOf("listen-port" to "51830"))
        assertTrue(host.ran.last().endsWith(" && ufw allow 51830/udp comment networkhelm-port-wg9"), host.ran.last())

        // the servers of DNS are the host's own to wg-quick, which gives them through resolvconf
        val refused = assertFailsWith<DeviceException> { session.add(wg, mapOf("name" to "wg2", "dns" to "1.1.1.1")) }
        assertEquals(
            "dns: wg-quick sets these servers on the host itself through resolvconf, which the host lacks; the servers for clients go to Client DNS of their peers",
            refused.message,
        )
        assertTrue(host.given.none { "wg2" in it || "DNS" in it }, "${host.given}")
    }

    @Test
    fun anInterfaceWithSaveConfigStaysAsItIs() = runTest {
        val saved = serverFile.replace("ListenPort = 51820\n", "ListenPort = 51820\nSaveConfig = true\n")
        val host = VpnHost(listOf("for t in" to ok("wg\nwg-quick\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(serverState(saved)), "cat '/etc/wireguard/wg0.conf'" to ok(saved)))
        val session = SshHostSession(host, device, linux = true)
        val refused = assertFailsWith<DeviceException> { session.set(wg, "wg0", mapOf("listen-port" to "51830")) }
        assertEquals(
            "wg0 has SaveConfig = true, and wg-quick writes the running interface over /etc/wireguard/wg0.conf as it goes down; take SaveConfig out of the file first",
            refused.message,
        )
        assertFailsWith<DeviceException> { session.add(peers, mapOf("interface" to "wg0", "public-key" to phonePublic)) }
        assertTrue(host.given.isEmpty() && host.ran.none { it.startsWith("wg set") }, "${host.ran}")
    }

    @Test
    fun aPeerOfNetworkManagerKeepsItsPresharedKeyByFlagsZero() = runTest {
        val path = "/etc/NetworkManager/system-connections/wg9.nmconnection"
        val stopped = managerState.lines().filterNot { it.startsWith("--nh-dump--") }.joinToString("\n")
        val host = VpnHost(
            listOf(
                "for t in" to ok("wg\nnmcli\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(stopped), "wg genpsk" to ok("$preshared\n"),
                "cat '$path'" to ok(managerFile(managerPrivate, preshared)), "umask 077; mkdir -p '/etc/NetworkManager/system-connections'" to ok(), "rm -f" to ok(),
            ),
        )
        SshHostSession(host, device, linux = true).add(peers, mapOf("interface" to "wg9", "public-key" to phonePublic, "preshared-key" to "auto"))
        val written = host.given.single()
        assertTrue(written.endsWith("\n\n[wireguard-peer.$phonePublic]\npreshared-key=$preshared\npreshared-key-flags=0\nallowed-ips=10.79.0.3/32;\n"), written)
    }

    // the read of 39001 of the lab, Debian 12, on 18.11, .notes/lab/samples/vpn-hosts-1811.txt: its links, its sockets of UDP with
    // strongSwan on 500 and 4500 (some lines left out), its addresses and routes of IPv4 and its default route
    private val udp39001 ="   sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops            \n" +
        "19379: 00000000:0044 00000000:0000 07 00000000:00000000 00:00000000 00000000     0        0 4557414 2 000000009d85e017 0       \n" +
        "19811: 00000000:01F4 00000000:0000 07 00000000:00000000 00:00000000 00000000     0        0 4557406 2 00000000b4fc88a2 0       \n" +
        "23811: 00000000:1194 00000000:0000 07 00000000:00000000 00:00000000 00000000     0        0 4557407 2 000000000a6eb0b2 0       \n" +
        "27699: 00000000:20C4 00000000:0000 07 00000000:00000000 00:00000000 00000000 64677        0 4559564 2 00000000547106d3 0       \n" +
        "58842: 00000000:9A6B 00000000:0000 07 00000000:00000000 00:00000000 00000000    13        0 4559810 2 00000000e9828b8f 0       \n" +
        "  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops\n" +
        "13552: 00000000000000000000000001000000:E981 00000000000000000000000001000000:AEF3 01 00000000:00000000 00:00000000 00000000    13        0 4557681 2 000000001539dc4b 0\n" +
        "19811: 00000000000000000000000000000000:01F4 00000000000000000000000000000000:0000 07 00000000:00000000 00:00000000 00000000     0        0 4557404 2 000000003ba1eee5 0\n"

    private val scan39001 = "bonding_masters\nerspan0\neth0\ngre0\ngretap0\nlo\ntunl0\n--nh-udp--\n$udp39001--nh-ip--\n" +
        "1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever preferred_lft forever\n" +
        "6: eth0    inet 192.168.10.250/24 brd 192.168.10.255 scope global dynamic eth0\\       valid_lft 81131sec preferred_lft 81131sec\n" +
        "default via 192.168.10.1 dev eth0 \n192.168.10.0/24 dev eth0 proto kernel scope link src 192.168.10.250 \n" +
        "local 127.0.0.0/8 dev lo table local proto kernel scope host src 127.0.0.1 \nlocal 127.0.0.1 dev lo table local proto kernel scope host src 127.0.0.1 \n" +
        "broadcast 127.255.255.255 dev lo table local proto kernel scope link src 127.0.0.1 \n" +
        "local 192.168.10.250 dev eth0 table local proto kernel scope host src 192.168.10.250 \n" +
        "broadcast 192.168.10.255 dev eth0 table local proto kernel scope link src 192.168.10.250 \n--nh-default--\ndefault via 192.168.10.1 dev eth0 \n"

    @Test
    fun aNewServerIsProposedAroundWhatTheHostHas() = runTest {
        assertEquals(setOf(68, 500, 4500, 8388, 39531, 59777), udpPorts(udp39001))
        val scan = parseScan(scan39001)
        assertEquals(listOf(setOf("bonding_masters", "erspan0", "eth0", "gre0", "gretap0", "lo", "tunl0"), "192.168.10.250"), listOf(scan.links, scan.address))

        // the network of the file of wg0 is taken as one of a route would be
        val state = serverState(serverFile.replace("10.66.0.1/24", "10.8.0.1/24"))
        val host = VpnHost(listOf("for t in" to ok("wg\nwg-quick\nsystemctl\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(state), "ls /sys/class/net" to ok(scan39001)))
        val proposed = SshHostSession(host, device, linux = true).get(MenuPath.parse("vpn/wireguard/server"), null)?.values.orEmpty()
        assertEquals(
            listOf("wg1", "51821", "10.9.0.1/24", "192.168.10.250", "1.1.1.1, 9.9.9.9", "0.0.0.0/0, ::/0", "true", "true", "client1"),
            listOf("name", "listen-port", "address", "client-endpoint", "client-dns", "client-allowed-ips", "nat", "autostart", "client").map { proposed[it] },
        )
    }

    // the networks of the servers of 39001 as the scan prints them, written to its format: the file of wg1 with 10.8.0.1/24, the server
    // of OpenVPN on 10.9.0.0/24 and the pool of IKEv2 10.10.0.0/24, which no route shows; then ranges of xl2tpd and pptpd and an address
    @Test
    fun aNewNetworkPassesOverTheNetworksOfTheOtherServersOfTheHost() {
        val vpn = "--nh-vpn--\n10.8.0.1/24, fd42::1/64\n10.9.0.0 255.255.255.0\n10.10.0.0/24\n"
        assertEquals("10.11.0.0/24", freeNetwork(parseScan(scan39001 + vpn).networks)?.cidr)
        val more = "10.11.0.10-10.11.0.100\n10.12.0.10-100,10.12.0.200 # pptpd\n10.13.0.1\n10.14.0.0 255.255.255.0\n"
        assertEquals(listOf("10.11.0.0/25", "10.12.0.0/25", "10.12.0.200/32", "10.13.0.1/32", "10.14.0.0/24"), parseScan("--nh-vpn--\n$more").networks.map { it.cidr })
        assertEquals("10.15.0.0/24", freeNetwork(parseScan(scan39001 + vpn + more).networks)?.cidr)
    }

    // the scan of 39001 on 18.11 with the files of wg0, down, on 51820 and of wg1 on 51821
    @Test
    fun aPortTheFileOfAStoppedInterfaceNamesIsTaken() {
        val scan = parseScan(scan39001 + "--nh-vpn--\n10.8.0.1/24\n--nh-port--\n51820\n51821\n")
        assertEquals(setOf(68, 500, 4500, 8388, 39531, 59777, 51820, 51821), scan.ports)
    }

    @Test
    fun aServerOfOneStepStartsAndGivesTheConfigurationOfItsFirstClient() = runTest {
        val newPrivate = key("rdTestNewServerPrivate")
        val newPublic = key("rdTestNewServerPublic")
        val made = "[Interface]\nPrivateKey = $newPrivate\nListenPort = 51821\n# ClientEndpoint = 203.0.113.5\nAddress = 10.9.0.1/24\n# ClientDNS = 1.1.1.1, 9.9.9.9\n" +
            "# ClientAllowedIPs = 0.0.0.0/0, ::/0\n# ClientKeepalive = 25\n"
        val peer = "\n[Peer]\n# Name = client1\nPublicKey = $phonePublic\nPresharedKey = $preshared\nAllowedIPs = 10.9.0.2/32\n"
        fun running(file: String) = serverState() + "--nh-dump-- wg1\t\t$newPublic\t51821\toff\n--nh-file-- wg-quick /etc/wireguard/wg1.conf\n" +
            file.replace("PrivateKey = $newPrivate", "PrivateKey =").replace("PresharedKey = $preshared", "PresharedKey =") + "--nh-public-- $newPublic\n--nh-auto-- wg1 enabled\n"
        val host = VpnHost(
            listOf(
                "for t in" to ok("wg\nwg-quick\nsystemctl\n"), "id -u" to ok("0\n"), "wg genkey" to ok("$newPrivate\n"), "umask 077; mkdir -p '/etc/wireguard/networkhelm/wg1'" to ok("$phonePublic\n"),
                "umask 077; mkdir -p '/etc/wireguard'" to ok(), "rm -f" to ok(), "systemctl enable" to ok(), "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "if systemctl cat" to ok(),
                "kill" to ok(), "wg genpsk" to ok("$preshared\n"), "cat '/etc/wireguard/wg1.conf'; echo" to ok("$made$peer\n--nh-key--\n$clientPrivate\n"),
                "cat '/etc/wireguard/wg1.conf'" to ok(made), "wg set" to ok(),
            ),
            mapOf("echo \"--nh-now--" to listOf(ok(serverState()), ok(running(made)), ok(running(made + peer)))),
        )
        val values = mapOf(
            "name" to "wg1", "listen-port" to "51821", "client-endpoint" to "203.0.113.5", "autostart" to "true", "address" to "10.9.0.1/24", "client-dns" to "1.1.1.1, 9.9.9.9",
            "client-allowed-ips" to "0.0.0.0/0, ::/0", "client-keepalive" to "25", "preshared-key" to "true", "nat" to "false", "client" to "client1", "keep-key" to "true",
        )
        val reply = SshHostSession(host, device, linux = true).run(MenuPath.parse("vpn/wireguard/server"), "create", values).toList().single()
        assertEquals(listOf(made, made + peer, preshared), host.given)
        assertTrue("systemctl enable wg-quick@'wg1'" in host.ran, "${host.ran}")
        assertTrue(host.ran[host.ran.indexOf(host.ran.single { it.startsWith("B=") }) + 1].startsWith("if systemctl cat wg-quick@'wg1'"), "${host.ran}")
        assertEquals(
            "[Interface]\nPrivateKey = $clientPrivate\nAddress = 10.9.0.2/32\nDNS = 1.1.1.1, 9.9.9.9\n\n[Peer]\nPublicKey = $newPublic\nPresharedKey = $preshared\n" +
                "AllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = 203.0.113.5:51821\nPersistentKeepalive = 25",
            reply["conf"],
        )
        assertEquals("wg1", reply["name"])
        assertTrue(host.ran.none { newPrivate in it || clientPrivate in it }, "${host.ran}")

        val faces = parseWireGuard(running(made)).interfaces("192.168.10.250").associateBy { it.id }
        assertEquals(listOf("203.0.113.5:51821", "192.168.10.250:51820"), listOf(faces["wg1"]?.get("endpoint"), faces["wg0"]?.get("endpoint")))
    }

    // the way to the computer of the coordinator of the lab on 18.11, 192.168.11.2 behind the gateway of 39002, as ip route get prints it
    private val way = "set -- \$SSH_CONNECTION" to ok("192.168.11.2\n192.168.11.2 via 192.168.10.1 dev eth0 src 192.168.10.249 uid 0 \n    cache \n")

    @Test
    fun theConfigurationOfAClientIsImportedThroughTheInputAndComesUpUnderTheGuard() = runTest {
        val text = "[Interface]\nPrivateKey = $clientPrivate\nAddress = 10.9.0.2/32\n\n[Peer]\nPublicKey = $serverPublic\nPresharedKey = $preshared\nAllowedIPs = 0.0.0.0/0, ::/0\n" +
            "Endpoint = 192.168.10.250:51821\nPersistentKeepalive = 25"
        val host = VpnHost(
            listOf(
                "for t in" to ok("wg\nwg-quick\nsystemctl\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(clientState), "ls /sys/class/net" to ok(scan39001), way,
                "umask 077; mkdir -p '/etc/wireguard'" to ok(), "rm -f" to ok(), "systemctl enable" to ok(), "B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"),
                "if systemctl cat" to ok(), "kill" to ok(),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        val importing = MenuPath.parse("vpn/wireguard/import")
        assertEquals(listOf("wg1", "true", "true"), session.get(importing, null)?.values.orEmpty().let { listOf(it["name"], it["autostart"], it["keep-session"]) })
        assertEquals("wg1", session.run(importing, "import", mapOf("name" to "wg1", "autostart" to "true", "keep-session" to "true", "text" to text)).toList().single()["name"])
        val kept = "Address = 10.9.0.2/32\nPreUp = ip -4 route replace 192.168.11.2/32 via 192.168.10.1 dev eth0 # networkhelm-session\n" +
            "PostDown = ip -4 route del 192.168.11.2/32 via 192.168.10.1 dev eth0 || true # networkhelm-session\n"
        assertEquals(listOf(text.replace("Address = 10.9.0.2/32\n", kept) + "\n"), host.given)
        val guard = host.ran.indexOf(host.ran.single { it.startsWith("B=") })
        assertTrue(host.ran[guard + 1].startsWith("umask 077; mkdir -p '/etc/wireguard' && ") && host.ran[guard + 2].startsWith("rm -f"), "the file goes in under the guard: ${host.ran}")
        assertTrue(host.ran[guard + 3].startsWith("if systemctl cat wg-quick@'wg1'"), "${host.ran}")
        assertEquals("systemctl enable wg-quick@'wg1'", host.ran.last(), "it starts at boot once the guard let it come up")
        assertTrue(host.ran.none { clientPrivate in it }, "${host.ran}")

        val split = session.run(importing, "import", mapOf("name" to "wg2", "keep-session" to "true", "text" to text.replace("0.0.0.0/0, ::/0", "10.9.0.0/24"))).toList()
        assertEquals("wg2", split.single()["name"])
        assertTrue("PreUp" !in host.given.last(), "a tunnel that leaves the way to this session alone needs no route of its own")

        val dns = assertFailsWith<DeviceException> { session.run(importing, "import", mapOf("name" to "wg3", "text" to text.replace("Address = 10.9.0.2/32", "Address = 10.9.0.2/32\nDNS = 1.1.1.1"))).toList() }
        assertEquals("the configuration sets DNS, which wg-quick gives the host through resolvconf, and the host lacks it; take the line DNS out of it", dns.message)
        val keyless = assertFailsWith<DeviceException> { session.run(importing, "import", mapOf("name" to "wg3", "text" to text.replace(clientPrivate, "rd-not-a-key"))).toList() }
        assertEquals("text: a configuration of WireGuard needs a private key and a peer", keyless.message)
        // the window of the configuration shows the keys so until they are shown
        val masked = assertFailsWith<DeviceException> { session.run(importing, "import", mapOf("name" to "wg3", "text" to text.replace(clientPrivate, "••••••••"))).toList() }
        assertEquals("text: the keys are masked; show them in the window of the configuration or take it by Copy", masked.message)
        assertEquals(2, host.given.size, "a refused configuration goes to no file")
    }

    @Test
    fun natGoesToTheHooksOfWgQuickWhereNoFirewallKeepsTheRulesAndToABlockOfItsOwnInUfw() = runTest {
        val host = VpnHost(
            listOf(
                "for t in" to ok("wg\nwg-quick\niptables-save\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(serverState()), "f=/etc/sysctl.d" to ok(),
                "cat '/etc/wireguard/wg0.conf'" to ok(serverFile), "umask 077; mkdir -p '/etc/wireguard'" to ok(), "rm -f" to ok(), "iptables -w" to ok(),
            ),
        )
        SshHostSession(host, device, linux = true).set(wg, "wg0", mapOf("nat" to "yes"))
        val (up, down) = natHooks(listOf("10.66.0.0/24"), iptables = true)
        assertEquals(
            "iptables -w -t nat -A POSTROUTING -s 10.66.0.0/24 ! -o %i -j MASQUERADE; iptables -w -I FORWARD -i %i -j ACCEPT; " +
                "iptables -w -I FORWARD -o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT # networkhelm-nat",
            up,
        )
        val written = host.given.single()
        assertTrue("PrivateKey = $serverPrivate\nPostUp = $up\nPostDown = $down\n" in written, written)
        assertEquals(up.substringBefore(" #").replace("%i", "'wg0'"), host.ran.last())
        assertTrue(host.ran.any { it.startsWith("f=/etc/sysctl.d/99-networkhelm-forward.conf") }, "${host.ran}")

        val natted = parseWireGuard(serverState(written)).interfaces().single()
        assertEquals(listOf("true", ""), listOf("nat", "post-up").map { natted[it] })
        assertTrue(natHooks(listOf("10.66.0.0/24"), iptables = false).first.startsWith("nft add table inet networkhelm-%i; "))

        val rules = "# rules.before\n*filter\n:ufw-before-input - [0:0]\nCOMMIT\n"
        val nat = ufwNat(rules, "networkhelm-nat-wg0", "wg0", listOf("10.66.0.0/24"))
        assertEquals(
            "# rules.before\n# networkhelm-nat-wg0\n*nat\n:POSTROUTING ACCEPT [0:0]\n-A POSTROUTING -s 10.66.0.0/24 ! -o wg0 -j MASQUERADE\nCOMMIT\n# networkhelm-nat-wg0 end\n" +
                "*filter\n:ufw-before-input - [0:0]\nCOMMIT\n",
            nat,
        )
        assertEquals(rules, ufwNat(nat, "networkhelm-nat-wg0", "wg0", emptyList()))
    }
}

// answers a command, or the command sudo runs, by how it starts, the answer added last first; a command of turns gets its answers in
// turn and the last one after them, as a host that changed between two reads; records what was sent, what ran and what went in as input
internal class VpnHost(answers: List<Pair<String, ExecResult>>, turns: Map<String, List<ExecResult>> = emptyMap()) : SshSession {
    val answers = answers.toMutableList()
    private val turns = turns.mapValues { it.value.toMutableList() }
    val sent = mutableListOf<String>()
    val ran = mutableListOf<String>()
    val given = mutableListOf<String>()

    override suspend fun exec(command: String): ExecResult {
        sent += command
        val sudo = listOf("sudo -n sh -c ", "sudo -S -p '' sh -c ", "sudo -k -S -p '' sh -c ").firstOrNull { command.startsWith(it) }
        val inner = if (sudo == null) command else unquote(command.removePrefix(sudo))
        ran += inner
        turns.entries.firstOrNull { inner.startsWith(it.key) }?.value?.let { return if (it.size > 1) it.removeAt(0) else it.first() }
        return answers.firstOrNull { inner.startsWith(it.first) }?.second ?: ExecResult("", "not found", 127)
    }

    override suspend fun exec(command: String, input: String): ExecResult {
        given += input
        return exec(command)
    }

    override suspend fun process(command: String): ByteStream = error("no process")
    override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
    override suspend fun sftp(): SftpClient = error("no sftp")
    override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
    override fun close() = Unit

    private fun unquote(text: String) = text.removePrefix("'").removeSuffix("'").replace("'\\''", "'")
}
