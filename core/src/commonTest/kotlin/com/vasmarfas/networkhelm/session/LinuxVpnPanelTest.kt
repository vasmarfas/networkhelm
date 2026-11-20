package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxVpnPanelTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private fun key(tag: String) = tag.padEnd(43, 'A') + "="

    private val serverPrivate = key("rdTestServerPrivate")
    private val serverPublic = key("rdTestServerPublic")
    private val clientPublic = key("rdTestClientPublic")
    private val phonePublic = key("rdTestPhonePublic")

    private val sessions = MenuPath.parse("vpn/session")

    private val serverFile = "[Interface]\nAddress = 10.66.0.1/24\nListenPort = 51820\nPrivateKey = $serverPrivate\n\n[Peer]\n# Name = lab-client\nPublicKey = $clientPublic\n" +
        "AllowedIPs = 10.66.0.2/32\n\n[Peer]\n# Name = phone\nPublicKey = $phonePublic\nAllowedIPs = 10.66.0.3/32\n"

    // the status and the journal of OpenVPN are written to the format of 2.6, not recorded on a host: the containers of the lab have
    // no /dev/net/tun for it
    private val title = "TITLE,OpenVPN 2.6.14 x86_64-pc-linux-gnu [SSL (OpenSSL)] [LZO] [LZ4] [EPOLL] [PKCS11] [MH/PKTINFO] [AEAD] [DCO]"
    private val header = "HEADER,CLIENT_LIST,Common Name,Real Address,Virtual Address,Virtual IPv6 Address,Bytes Received,Bytes Sent,Connected Since," +
        "Connected Since (time_t),Username,Client ID,Peer ID,Data Channel Cipher"
    private val statusTwo = "$title\nTIME,2026-11-17 21:11:36,1794939096\n$header\n" +
        "CLIENT_LIST,laptop,198.51.100.20:40112,10.8.0.2,,123456,654321,2026-11-17 21:01:36,1794938496,UNDEF,0,0,AES-256-GCM\n" +
        "HEADER,ROUTING_TABLE,Virtual Address,Common Name,Real Address,Last Ref,Last Ref (time_t)\n" +
        "ROUTING_TABLE,10.8.0.2,laptop,198.51.100.20:40112,2026-11-17 21:11:34,1794939094\nGLOBAL_STATS,Max bcast/mcast queue length,0\nGLOBAL_STATS,dco_enabled,1\nEND\n"

    private fun openVpn(management: Boolean = true) = "--nh-zone-- +0300\n--nh-ovpn-- server active enabled /etc/openvpn/server/server.conf\n" +
        "port 1194\nproto udp\ndev tun\nserver 10.8.0.0 255.255.255.0\nstatus /run/openvpn-server/status-server.log 5\n" +
        (if (management) "management /run/openvpn-server/server.sock unix\n" else "") + "--nh-ovpn-status-- /run/openvpn-server/status-server.log\n" + statusTwo

    // the read of the panel: the server of the lab pair with its client handshaking and a phone that has not, and the OpenVPN server
    private fun state(now: Long, received: Long, phone: String = "\t(none)\t10.66.0.3/32\t0", management: Boolean = true) = "--nh-now-- $now\n" +
        "--nh-dump-- wg0\t\t$serverPublic\t51820\toff\n" +
        "--nh-dump-- wg0\t$clientPublic\t(none)\t192.168.10.249:59837\t10.66.0.2/32\t1794939064\t$received\t2592\toff\n" +
        "--nh-dump-- wg0\t$phonePublic\t(none)$phone\t0\t0\toff\n" +
        "--nh-file-- wg-quick /etc/wireguard/wg0.conf\n" + serverFile.replace("PrivateKey = $serverPrivate", "PrivateKey =") +
        "--nh-public-- $serverPublic\n--nh-auto-- wg0 enabled\n" + openVpn(management)

    // an IKE SA of the server rw the window made, with a client of EAP-MSCHAPv2 on Ubuntu 24.04, as swanctl --list-sas --raw showed it on
    // Debian 12 in the lab on 17.11
    private val ikeSa = "list-sa event {rw {uniqueid=1 version=2 state=ESTABLISHED local-host=192.168.10.250 local-port=4500 local-id=192.168.10.250 " +
        "remote-host=192.168.10.249 remote-port=4500 remote-id=192.168.10.249 remote-eap-id=nh-ike initiator-spi=12f32f47804f4051 responder-spi=622de9aa8fac2a0e " +
        "encr-alg=AES_CBC encr-keysize=256 integ-alg=HMAC_SHA2_256_128 prf-alg=PRF_HMAC_SHA2_256 dh-group=MODP_2048 established=2 rekey-time=13011 " +
        "remote-vips=[10.10.10.1] child-sas {rw-1 {name=rw uniqueid=1 reqid=1 state=INSTALLED mode=TUNNEL protocol=ESP spi-in=c5686606 spi-out=c25463a7 " +
        "encr-alg=AES_CBC encr-keysize=256 integ-alg=HMAC_SHA2_256_128 bytes-in=0 packets-in=0 bytes-out=0 packets-out=0 rekey-time=3276 life-time=3958 " +
        "install-time=2 local-ts=[10.10.10.0/24] remote-ts=[10.10.10.1/32]}}}}\nlist-sas reply {}\n"

    @Test
    fun theSessionsOfIkev2ComeFromTheSasOfCharonAndEndByTheirId() = runTest {
        val host = VpnHost(
            listOf(
                "for t in" to ok("swanctl\n"), "id -u" to ok("0\n"),
                "echo \"--nh-now--" to ok("--nh-now-- 1794939096\n--nh-zone-- +0300\n--nh-ike-conns--\nrw\n--nh-ike-sas--\n$ikeSa"), "swanctl --terminate" to ok(),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        val sa = session.rows("vpn/session").single()
        assertEquals(
            listOf("ipsec/rw/1", "IKEv2", "rw", "nh-ike", "192.168.10.249:4500", "10.10.10.1", "2026-11-17 21:11:34", "2s", "0", "0", "AES_CBC-256/HMAC_SHA2_256_128"),
            listOf(sa.id) + listOf("kind", "service", "user", "endpoint", "address", "since", "uptime", "rx", "tx", "cipher").map { sa[it] },
        )
        assertEquals(listOf("ipsec/rw", "1", "500,4500"), session.rows("vpn/service").single().let { listOf(it.id, it["sessions"], it["port"]) })
        session.run(sessions, "disconnect", mapOf("numbers" to "ipsec/rw/1")).toList()
        assertEquals("swanctl --terminate --ike-id 1 --force >/dev/null", host.ran.last())
    }

    @Test
    fun aStatusOfEachVersionGivesItsClientsByTheTitlesOfItsColumns() {
        val two = parseOpenVpnStatus(statusTwo).single()
        assertEquals(
            listOf("laptop", "198.51.100.20:40112", "10.8.0.2", "123456", "654321", "1794938496", "0", "AES-256-GCM"),
            listOf("Common Name", "Real Address", "Virtual Address", "Bytes Received", "Bytes Sent", "Connected Since (time_t)", "Client ID", "Data Channel Cipher").map { two[it] },
        )
        assertEquals(two, parseOpenVpnStatus(statusTwo.replace(',', '\t')).single(), "version 3 is version 2 with tabs")
        val one = parseOpenVpnStatus(
            "OpenVPN CLIENT LIST\nUpdated,2026-11-17 21:11:36\nCommon Name,Real Address,Bytes Received,Bytes Sent,Connected Since\n" +
                "laptop,198.51.100.20:40112,123456,654321,2026-11-17 21:01:36\nROUTING TABLE\nVirtual Address,Common Name,Real Address,Last Ref\n" +
                "10.8.0.2,laptop,198.51.100.20:40112,2026-11-17 21:11:34\nGLOBAL STATS\nMax bcast/mcast queue length,0\nEND\n",
        ).single()
        assertEquals(listOf("laptop", "10.8.0.2", "123456", "2026-11-17 21:01:36"), listOf("Common Name", "Virtual Address", "Bytes Received", "Connected Since").map { one[it] })
        assertEquals(emptyList(), parseOpenVpnStatus("$title\nTIME,2026-11-17 21:11:36,1794939096\n$header\nEND\n"))
    }

    @Test
    fun theJournalOfOpenVpnGivesItsSessionsWithTheirEndAndItsReason() {
        val unit = "\"_SYSTEMD_UNIT\":\"openvpn-server@server.service\""
        val journal = listOf(
            """{"__REALTIME_TIMESTAMP":"1794935000000000",$unit,"MESSAGE":"203.0.113.9:51000 [phone] Peer Connection Initiated with [AF_INET]203.0.113.9:51000"}""",
            """{"__REALTIME_TIMESTAMP":"1794935001000000",$unit,"MESSAGE":"phone/203.0.113.9:51000 MULTI_sva: pool returned IPv4=10.8.0.3, IPv6=(Not enabled)"}""",
            """{"__REALTIME_TIMESTAMP":"1794936800000000",$unit,"MESSAGE":"phone/203.0.113.9:51000 SIGTERM[soft,remote-exit] received, client-instance exiting"}""",
            """{"__REALTIME_TIMESTAMP":"1794938496000000",$unit,"MESSAGE":"198.51.100.20:40112 [laptop] Peer Connection Initiated with [AF_INET]198.51.100.20:40112"}""",
            """{"__REALTIME_TIMESTAMP":"1794938497000000",$unit,"MESSAGE":"laptop/198.51.100.20:40112 MULTI_sva: pool returned IPv4=10.8.0.2, IPv6=(Not enabled)"}""",
        ).joinToString("\n")
        val (laptop, phone) = parseOpenVpnJournal(journal, "+0300")
        assertEquals("server/1794938496/laptop/198.51.100.20:40112", laptop.id)
        assertEquals(listOf("laptop", "10.8.0.2", "2026-11-17 21:01:36", "", "", ""), listOf("user", "address", "connected", "disconnected", "duration", "reason").map { laptop[it] })
        assertEquals(
            listOf("phone", "203.0.113.9:51000", "10.8.0.3", "2026-11-17 20:03:20", "2026-11-17 20:33:20", "30m", "remote-exit", "1800", ""),
            listOf("user", "endpoint", "address", "connected", "disconnected", "duration", "reason", "seconds", "rx").map { phone[it] },
        )
    }

    @Test
    fun thePanelShowsTheLiveSessionsOfWireGuardAndOpenVpnWithTheirRatesAndHistory() = runTest {
        val host = VpnHost(
            listOf(
                "for t in" to ok("wg\nwg-quick\nopenvpn\njournalctl\nsystemctl\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(state(1794939096, 8448)),
                "date +%z; echo --nh-section--" to ok("+0300\n--nh-section--\n" +
                    """{"__REALTIME_TIMESTAMP":"1794938496000000","_SYSTEMD_UNIT":"openvpn-server@server.service","MESSAGE":"198.51.100.20:40112 [laptop] Peer Connection Initiated with [AF_INET]198.51.100.20:40112"}""" + "\n"),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        val (wireGuard, openVpn) = session.rows("vpn/session")
        assertEquals(listOf("wireguard/wg0/$clientPublic", "openvpn/server/0"), listOf(wireGuard.id, openVpn.id))
        assertEquals(
            listOf("WireGuard", "wg0", "lab-client", "192.168.10.249:59837", "10.66.0.2/32", "", "", "8448", "2592", "", "32s", "wg0/$clientPublic"),
            listOf("kind", "service", "user", "endpoint", "address", "since", "uptime", "rx", "tx", "rx-rate", "last-handshake", "peer").map { wireGuard[it] },
        )
        assertEquals(
            listOf("OpenVPN", "server", "laptop", "198.51.100.20:40112", "10.8.0.2", "2026-11-17 21:01:36", "10m", "123456", "654321", "AES-256-GCM", ""),
            listOf("kind", "service", "user", "endpoint", "address", "since", "uptime", "rx", "tx", "cipher", "peer").map { openVpn[it] },
        )
        val services = session.rows("vpn/service")
        assertEquals(
            listOf(
                listOf("wireguard/wg0", "active", "51820", "udp", "10.66.0.1/24", "1", "8448", "2592"),
                listOf("openvpn/server", "active", "1194", "udp", "10.8.0.0 255.255.255.0", "1", "123456", "654321"),
            ),
            services.map { listOf(it.id) + listOf("state", "port", "protocol", "network", "sessions", "rx", "tx").map { name -> it[name].orEmpty() } },
        )
        assertEquals(1, host.ran.count { it.startsWith("echo \"--nh-now--") }, "both tables come of one read")
        assertEquals(listOf("laptop"), session.rows("vpn/history").map { it["user"] })

        host.answers.add(0, "echo \"--nh-now--" to ok(state(1794939097, 8448 + 12500, "\t203.0.113.9:51000\t10.66.0.3/32\t1794939095")))
        withContext(Dispatchers.Default) { delay(1100) }
        val later = session.rows("vpn/session").associateBy { it.id }
        val rate = later.getValue("wireguard/wg0/$clientPublic")["rx-rate"]?.toLongOrNull() ?: 0L
        assertTrue(rate in 30_000L..100_000L, "12500 bytes in 1.1 s or a little more: $rate")
        assertEquals("0", later.getValue("openvpn/server/0")["rx-rate"])
        assertEquals(listOf("phone", "2026-11-17 21:11:35", "2s"), listOf("user", "since", "uptime").map { later.getValue("wireguard/wg0/$phonePublic")[it] })

        host.answers.add(0, "echo \"--nh-now--" to ok(state(1794939297, 8448 + 12500, "\t203.0.113.9:51000\t10.66.0.3/32\t1794939290")))
        withContext(Dispatchers.Default) { delay(1100) }
        val gone = session.rows("vpn/session").single { it.id.startsWith("wireguard/") }
        assertEquals(listOf("phone", "", "", "7s"), listOf("user", "since", "uptime", "last-handshake").map { gone[it] }, "200 s between reads hide whether the session broke")
    }

    // the read of AmneziaWG between its marks after the one of WireGuard, its interface line cut on the host to the fields of wg, and a
    // phone handshaking with awg0, written to the format of the read
    private val awgFile = "[Interface]\nAddress = 10.8.0.1/24\nListenPort = 51821\nPrivateKey = $serverPrivate\nJc = 4\n\n[Peer]\n# Name = phone\nPublicKey = $phonePublic\n" +
        "AllowedIPs = 10.8.0.2/32\n"
    private val awgPart = "--nh-awg-part--\n--nh-now-- 1794939096\n--nh-dump-- awg0\t\t$serverPublic\t51821\toff\n" +
        "--nh-dump-- awg0\t$phonePublic\t(none)\t203.0.113.7:40000\t10.8.0.2/32\t1794939090\t1024\t2048\t25\n--nh-file-- wg-quick /etc/amnezia/amneziawg/awg0.conf\n" +
        awgFile.replace("PrivateKey = $serverPrivate", "PrivateKey =") + "--nh-public-- $serverPublic\n--nh-auto-- awg0 enabled\n--nh-awg-end--\n"

    @Test
    fun theSessionsOfAmneziaWgComeFromTheirOwnPartOfTheReadAndEndByTheirPeer() = runTest {
        val host = VpnHost(
            listOf(
                "for t in" to ok("wg\nwg-quick\nawg\nawg-quick\nopenvpn\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(state(1794939096, 8448).replace("--nh-zone--", "$awgPart--nh-zone--")),
                "cat '/etc/amnezia/amneziawg/awg0.conf'" to ok(awgFile), "umask 077" to ok(), "rm -f" to ok(), "awg set" to ok(),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        val rows = session.rows("vpn/session")
        assertEquals(listOf("wireguard/wg0/$clientPublic", "amneziawg/awg0/$phonePublic", "openvpn/server/0"), rows.map { it.id }, "the phone of wg0 is not handshaking")
        val phone = rows[1]
        assertEquals(
            listOf("AmneziaWG", "awg0", "phone", "203.0.113.7:40000", "10.8.0.2/32", "1024", "2048", "6s", "awg0/$phonePublic"),
            listOf("kind", "service", "user", "endpoint", "address", "rx", "tx", "last-handshake", "peer").map { phone[it] },
        )
        val awg = session.rows("vpn/service").single { it.id == "amneziawg/awg0" }
        assertEquals(listOf("AmneziaWG", "51821", "10.8.0.1/24", "1"), listOf("kind", "port", "network", "sessions").map { awg[it] })
        val read = host.ran.single { it.startsWith("echo \"--nh-now--") }
        assertTrue("\necho --nh-awg-part--\necho \"--nh-now--" in read && "\nawg show all dump 2>/dev/null" in read && "\necho --nh-awg-end--\n" in read, read)

        session.run(sessions, "disconnect", mapOf("numbers" to "amneziawg/awg0/$phonePublic")).toList()
        assertTrue("#[disabled] PublicKey = $phonePublic" in host.given.single(), host.given.single())
        assertTrue(host.ran.last().startsWith("awg set 'awg0' peer '$phonePublic' remove"), host.ran.last())
    }

    @Test
    fun aSessionEndsWithItsPeerTurnedOffOrThroughTheManagementOfItsOpenVpnServer() = runTest {
        val host = VpnHost(
            listOf(
                "for t in" to ok("wg\nwg-quick\nopenvpn\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(state(1794939096, 8448)),
                "cat '/etc/wireguard/wg0.conf'" to ok(serverFile), "umask 077" to ok(), "rm -f" to ok(), "wg set" to ok(),
                "{ printf" to ok(">INFO:OpenVPN Management Interface Version 5 -- type 'help' for more info\nSUCCESS: client-kill command succeeded\n"),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        session.run(sessions, "disconnect", mapOf("numbers" to "wireguard/wg0/$clientPublic")).toList()
        assertTrue(
            "#[disabled] [Peer]\n#[disabled] # Name = lab-client\n#[disabled] PublicKey = $clientPublic\n#[disabled] AllowedIPs = 10.66.0.2/32\n\n[Peer]\n# Name = phone" in host.given.single(),
            host.given.single(),
        )
        assertEquals("wg set 'wg0' peer '$clientPublic' remove && { ip -4 route del 10.66.0.2/32 dev 'wg0' 2>/dev/null || true; }", host.ran.last())

        session.run(sessions, "disconnect", mapOf("numbers" to "openvpn/server/0")).toList()
        assertEquals(
            "{ printf '%s\\nquit\\n' 'client-kill 0'; } | { if command -v socat >/dev/null 2>&1; then socat - UNIX-CONNECT:'/run/openvpn-server/server.sock'; " +
                "else nc -U '/run/openvpn-server/server.sock'; fi; }",
            host.ran.last(),
        )

        host.answers.add(0, "{ printf" to ok(">INFO:OpenVPN Management Interface Version 5 -- type 'help' for more info\nERROR: client-kill command failed\n"))
        val refused = assertFailsWith<DeviceException> { session.run(sessions, "disconnect", mapOf("numbers" to "openvpn/server/0")).toList() }
        assertEquals("client-kill command failed", refused.message)

        val bare = VpnHost(listOf("for t in" to ok("openvpn\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok(state(1794939096, 8448, management = false))))
        val unmanaged = assertFailsWith<DeviceException> { SshHostSession(bare, device, linux = true).run(sessions, "disconnect", mapOf("numbers" to "openvpn/server/0")).toList() }
        assertEquals(ErrorCategory.Unsupported, unmanaged.category)
        assertEquals("server has no management interface to end a session with", unmanaged.message)

        val locked = VpnHost(
            listOf(
                "for t in" to ok("openvpn\n"), "id -u" to ok("0\n"),
                "echo \"--nh-now--" to ok(state(1794939096, 8448).replace("server.sock unix\n", "server.sock unix /etc/openvpn/server/management.pw\n")),
                "{ head -n 1" to ok("ENTER PASSWORD:SUCCESS: password is correct\nSUCCESS: client-kill command succeeded\n"),
            ),
        )
        SshHostSession(locked, device, linux = true).run(sessions, "disconnect", mapOf("numbers" to "openvpn/server/0")).toList()
        assertTrue(locked.ran.last().startsWith("{ head -n 1 '/etc/openvpn/server/management.pw'; printf '%s\\nquit\\n' 'client-kill 0'; }"), "the password goes from its file to the socket on the host")
    }
}
