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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxVpnCollectorTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private val collector = MenuPath.parse("vpn/collector")

    // a public key of a peer with a slash in it, as base64 has them
    private val slashed = "rdTest/Collector".padEnd(43, 'A') + "="

    private fun line(time: Long, event: String, session: String, raw: String) =
        "{\"t\":$time,\"e\":\"$event\",\"k\":\"$session\",\"r\":\"${raw.replace("\t", "%09")}\"}"

    private fun peer(handshake: Long, rx: Long, tx: Long) = "wg0\t$slashed\t(set)\t192.0.2.7:5000\t10.66.0.2/32\t$handshake\t$rx\t$tx\toff"

    private fun client(rx: Long, tx: Long) = "CLIENT_LIST\tlaptop\t198.51.100.20:40112\t10.8.0.2\t\t$rx\t$tx\t2026-11-17 21:01:36\t1794938496\tUNDEF\t0\t0\tAES-256-GCM"

    // lines of the log as the script writes them, which a run of it on fake output of wg and of a status file showed, with the times of
    // this sample
    private val log = listOf(
        line(1794935000, "start", "wireguard/wg0/$slashed", peer(1794934990, 1000, 2000)),
        line(1794935300, "count", "wireguard/wg0/$slashed", peer(1794935200, 3000, 4000)),
        line(1794936800, "end", "wireguard/wg0/$slashed", peer(1794936600, 5000, 6000)),
        line(1794938500, "start", "openvpn/srv/laptop/198.51.100.20:40112", client(100, 200)),
        line(1794939000, "end", "openvpn/srv/laptop/198.51.100.20:40112", client(123456, 654321)),
        line(1794939060, "start", "wireguard/wg0/$slashed", peer(1794939050, 7000, 8000)),
        "not a line of the log",
    ).joinToString("\n")

    @Test
    fun theLogGivesSessionsWithTheirBytesAndTheJournalOfOpenVpnTakesThem() {
        val (open, laptop, closed) = parseCollected(log, "+0300", mapOf("wireguard/wg0/$slashed" to "lab-client"))
        assertEquals(listOf("lab-client", "1794939050", "", "0"), listOf("user", "start", "disconnected", "rx").map { open[it] })
        assertEquals(
            listOf("WireGuard", "wg0", "lab-client", "192.0.2.7:5000", "10.66.0.2/32", "1794934990", "1810", "30m10s", "4000", "4000"),
            listOf("kind", "service", "user", "endpoint", "address", "start", "seconds", "duration", "rx", "tx").map { closed[it] },
        )
        assertEquals(
            listOf("OpenVPN", "srv", "laptop", "198.51.100.20:40112", "10.8.0.2", "1794938496", "123456", "654321"),
            listOf("kind", "service", "user", "endpoint", "address", "start", "rx", "tx").map { laptop[it] },
        )
        val journal = Item(
            "srv/1794938496/laptop/198.51.100.20:40112",
            mapOf("kind" to "OpenVPN", "service" to "srv", "user" to "laptop", "endpoint" to "198.51.100.20:40112", "start" to "1794938496", "reason" to "remote-exit", "rx" to "", "tx" to ""),
        )
        val merged = mergeHistory(listOf(journal), listOf(open, laptop, closed))
        assertEquals(listOf(open.id, journal.id, closed.id), merged.map { it.id })
        assertEquals(listOf("remote-exit", "123456", "654321"), listOf("reason", "rx", "tx").map { merged[1][it] })
    }

    // awg prints the line of a peer as wg does, and the script keys it by amneziawg; a peer without a name goes by the start of its key
    @Test
    fun aSessionOfAmneziaWgInTheLogGoesByItsKind() {
        val awg = { handshake: Long, rx: Long -> peer(handshake, rx, 0).replace("wg0\t", "awg0\t") }
        val log = listOf(line(1794935000, "start", "amneziawg/awg0/$slashed", awg(1794934990, 100)), line(1794935600, "end", "amneziawg/awg0/$slashed", awg(1794935500, 900)))
        val session = parseCollected(log.joinToString("\n"), "+0300", emptyMap()).single()
        assertEquals(listOf("AmneziaWG", "awg0", slashed.take(8), "192.0.2.7:5000", "1794934990", "800"), listOf("kind", "service", "user", "endpoint", "start", "rx").map { session[it] })
    }

    // an IKE SA in the form swanctl --list-sas --raw gave in the lab on 17.11, its age and bytes changed
    private fun sa(established: Long, received: Long, sent: Long) =
        "list-sa event {rw {uniqueid=1 version=2 state=ESTABLISHED remote-host=192.168.10.249 remote-port=4500 remote-id=192.168.10.249 remote-eap-id=nh-ike " +
            "encr-alg=AES_CBC encr-keysize=256 integ-alg=HMAC_SHA2_256_128 established=$established remote-vips=[10.10.10.1] child-sas {rw-1 {name=rw uniqueid=1 " +
            "bytes-in=$received bytes-out=$sent install-time=$established local-ts=[10.10.10.0/24] remote-ts=[10.10.10.1/32]}}}}"

    @Test
    fun anIkeSaInTheLogBeganAsLongBeforeItsStartAsItWasEstablished() {
        val log = listOf(line(1794939000, "start", "ipsec/rw/1", sa(2, 0, 0)), line(1794939600, "end", "ipsec/rw/1", sa(602, 1000, 2000))).joinToString("\n")
        val session = parseCollected(log, "+0300", emptyMap()).single()
        assertEquals(
            listOf("IKEv2", "rw", "nh-ike", "192.168.10.249:4500", "10.10.10.1", "1794938998", "602", "1000", "2000"),
            listOf("kind", "service", "user", "endpoint", "address", "start", "seconds", "rx", "tx").map { session[it] },
        )
    }

    @Test
    fun theCollectorComesWithItsTimerAndGoesWithOrWithoutItsLog() = runTest {
        val host = VpnHost(
            listOf(
                "for t in" to ok("wg\nsystemctl\n"), "id -u" to ok("0\n"), "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "systemctl daemon-reload" to ok(),
                "{ systemctl disable" to ok(), "[ -f /usr/local/lib/networkhelm/vpn-collector.sh ]" to ok("yes\nsystemd\n2048\n"),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        session.run(collector, "install", emptyMap()).toList()
        val (script, service, timer) = host.given
        assertTrue(script.startsWith("#!/bin/sh\n") && "for t in wg awg; do\n\$t show all dump" in script, "the script reads awg as it reads wg: $script")
        assertTrue(host.ran.any { it.endsWith("\nsh -n '/usr/local/lib/networkhelm/vpn-collector.sh'") }, "${host.ran}")
        assertTrue("ExecStart=/bin/sh /usr/local/lib/networkhelm/vpn-collector.sh" in service && "OnUnitActiveSec=30s" in timer, "$service$timer")
        assertEquals("systemctl daemon-reload && systemctl enable --now networkhelm-vpn-collector.timer", host.ran.last())
        val state = (session.watch(collector).first() as ListEvent.Snapshot).items.single()
        assertEquals(listOf("true", "systemd", "2048"), listOf("installed", "runner", "size").map { state[it] })

        session.run(collector, "remove", mapOf("journal" to "no")).toList()
        assertTrue(
            host.ran.last().endsWith("rm -f /usr/local/lib/networkhelm/vpn-collector.sh /var/lib/networkhelm/vpn-live /var/lib/networkhelm/vpn-live.new /var/lib/networkhelm/vpn-counted"),
            host.ran.last(),
        )
        session.run(collector, "remove", mapOf("journal" to "yes")).toList()
        assertTrue(host.ran.last().endsWith(" /var/lib/networkhelm/vpn-sessions.jsonl /var/lib/networkhelm/vpn-sessions.jsonl.1 /var/lib/networkhelm/vpn-sessions.jsonl.2"), host.ran.last())

        val bare = VpnHost(listOf("for t in" to ok("wg\n"), "id -u" to ok("0\n")))
        val refused = assertFailsWith<DeviceException> { SshHostSession(bare, device, linux = true).run(collector, "install", emptyMap()).toList() }
        assertEquals("the host has neither systemd nor the crontabs of cron for the collector", refused.message)
    }
}
