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

class LinuxVpnUsersTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private val users = MenuPath.parse("vpn/user")

    private fun key(tag: String) = tag.padEnd(43, 'A') + "="

    private val serverPublic = key("rdTestServerPublic")
    private val laptopKey = key("rdTestLaptopPublic")
    private val laptopPrivate = key("rdTestLaptopPrivate")
    private val phoneKey = key("rdTestPhonePublic")

    // laptop has a peer of wg0 and a certificate of srv, phone the peer alone and tablet the certificate alone; the WireGuard part is in
    // the format of the samples of the lab, the OpenVPN part written to the format of OpenVPN 2.6, as the lab has no /dev/net/tun
    private val wgFile = "[Interface]\nAddress = 10.66.0.1/24\nListenPort = 51820\nPrivateKey = ${key("rdTestServerPrivate")}\n\n[Peer]\n# Name = laptop\n" +
        "PublicKey = $laptopKey\nAllowedIPs = 10.66.0.2/32\n\n[Peer]\n# Name = phone\nPublicKey = $phoneKey\nAllowedIPs = 10.66.0.3/32\n"

    private val wgState = "--nh-now-- 1794939096\n--nh-dump-- wg0\t\t$serverPublic\t51820\toff\n" +
        "--nh-dump-- wg0\t$laptopKey\t(none)\t(none)\t10.66.0.2/32\t0\t0\t0\toff\n--nh-dump-- wg0\t$phoneKey\t(none)\t(none)\t10.66.0.3/32\t0\t0\t0\toff\n" +
        "--nh-file-- wg-quick /etc/wireguard/wg0.conf\n" + wgFile.replace(Regex("PrivateKey = \\S+"), "PrivateKey =") + "--nh-public-- $serverPublic\n--nh-auto-- wg0 enabled\n"

    private val ovpnFiles = "--nh-zone-- +0300\n--nh-ovpn-- srv active enabled /etc/openvpn/server/srv.conf\n" +
        "port 1194\nproto udp\ndev tun-srv\ntopology subnet\nserver 10.8.0.0 255.255.255.0\nca /etc/openvpn/server/srv/pki/ca.crt\n" +
        "cert /etc/openvpn/server/srv/pki/issued/server.crt\ncrl-verify /etc/openvpn/server/srv/crl.pem\ntls-crypt /etc/openvpn/server/srv/tc.key\n" +
        "client-config-dir /etc/openvpn/server/srv/ccd\nmanagement /run/openvpn-server/srv.sock unix\n# ClientRemote = vpn.example.net\n" +
        "--nh-ovpn-pki-- /etc/openvpn/server/srv/pki\nV\t351115120000Z\t\t7F01\tunknown\t/CN=server\nV\t351115120100Z\t\t7F02\tunknown\t/CN=laptop\n" +
        "V\t351115120200Z\t\t7F03\tunknown\t/CN=tablet\n--nh-ovpn-ccd-- laptop\nifconfig-push 10.8.0.10 255.255.255.0\n"

    private fun host(vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok("wg\nwg-quick\nopenvpn\nsystemctl\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(wgState), "full=1" to ok(ovpnFiles),
            "cat '/etc/wireguard/wg0.conf'" to ok(wgFile), "umask 077; e=" to ok(), "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "wg set" to ok(),
            "{ printf" to ok("SUCCESS: common name 'laptop' found, 1 client(s) killed\n"),
        ),
    )

    @Test
    fun aUserIsOneNameOverThePeersOfWireGuardAndTheCertificatesOfOpenVpn() = runTest {
        val rows = SshHostSession(host(), device, linux = true).rows("vpn/user").associateBy { it.id }
        assertEquals(listOf("laptop", "phone", "tablet"), rows.keys.toList())
        assertEquals(
            listOf("false", "wireguard/wg0,openvpn/srv", "wg0 10.66.0.2/32, srv 10.8.0.10"),
            listOf("disabled", "servers", "addresses").map { rows.getValue("laptop")[it] },
        )
        assertEquals(listOf("wireguard/wg0", "openvpn/srv"), listOf("phone", "tablet").map { rows.getValue(it)["servers"] })
    }

    @Test
    fun aUserTurnedOffIsOffOnEveryServerAndItsSessionsEnd() = runTest {
        val host = host()
        SshHostSession(host, device, linux = true).set(users, "laptop", mapOf("disabled" to "yes"))
        assertTrue(host.given.any { "#[disabled] [Peer]\n#[disabled] # Name = laptop\n#[disabled] PublicKey = $laptopKey" in it }, "${host.given}")
        assertTrue("ifconfig-push 10.8.0.10 255.255.255.0\ndisable\n" in host.given, "${host.given}")
        assertTrue(host.ran.any { it.startsWith("wg set 'wg0' peer '$laptopKey' remove") }, "${host.ran}")
        assertTrue(host.ran.last().startsWith("{ printf '%s\\nquit\\n' 'kill laptop'; }"), host.ran.last())
    }

    @Test
    fun aNewUserGetsWhatTheServersChosenNeedAndNoNameTwice() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        assertEquals("tv", session.add(users, mapOf("name" to "tv", "servers" to "openvpn/srv")))
        assertTrue(host.ran.any { it.endsWith("\"\$e\" build-client-full 'tv' nopass >/dev/null") }, "${host.ran}")
        val twice = assertFailsWith<DeviceException> { session.add(users, mapOf("name" to "laptop", "servers" to "openvpn/srv")) }
        assertEquals("the host has a VPN user laptop already", twice.message)
        val unknown = assertFailsWith<DeviceException> { session.add(users, mapOf("name" to "tv2", "servers" to "openvpn/none")) }
        assertEquals("servers: openvpn/none is not a value it takes", unknown.message)
        val none = assertFailsWith<DeviceException> { session.add(users, mapOf("name" to "tv3")) }
        assertEquals("servers: a value is needed", none.message)
    }

    @Test
    fun aRemovedUserLosesItsPeerAndItsCertificateIsRevoked() = runTest {
        val host = host()
        SshHostSession(host, device, linux = true).remove(users, listOf("phone", "tablet"))
        assertTrue(host.given.any { "# Name = laptop" in it && "# Name = phone" !in it }, "${host.given}")
        assertTrue(host.ran.any { it.startsWith("wg set 'wg0' peer '$phoneKey' remove") }, "${host.ran}")
        assertTrue(host.ran.any { "\"\$e\" revoke 'tablet' >/dev/null && \"\$e\" gen-crl >/dev/null" in it }, "${host.ran}")
    }

    @Test
    fun theConfigurationsOfAUserOnTwoServersGoUnderTheNamesOfTheServers() = runTest {
        val host = host(
            "cat '/etc/wireguard/wg0.conf'; echo" to ok("$wgFile\n--nh-key--\n$laptopPrivate\n"),
            "echo \"\$SSH_CONNECTION\"" to ok("192.168.10.2 50000 192.168.10.250 22\n"),
            "cat '/etc/openvpn/server/srv/pki/ca.crt'" to ok("rdTestCa\n--nh-part--\nrdTestCert\n--nh-part--\nrdTestKey\n--nh-part--\nrdTestStatic\n"),
        )
        val confs = SshHostSession(host, device, linux = true).run(users, "client-config", mapOf("number" to "laptop")).toList().map { it["conf"].orEmpty() }
        assertEquals(2, confs.size)
        assertTrue(confs[0].startsWith("# wireguard/wg0\n[Interface]\nPrivateKey = $laptopPrivate\nAddress = 10.66.0.2/32\n"), confs[0])
        assertTrue(confs[1].startsWith("# openvpn/srv\nclient\n") && "remote vpn.example.net 1194" in confs[1], confs[1])
    }
}
