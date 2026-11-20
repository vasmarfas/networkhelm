package com.vasmarfas.networkhelm.session

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

class LinuxVpnAccelTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    // accel-cmd show sessions with the columns the panel asks for, written to the documentation of accel-ppp: no package of it is in
    // Debian 12, Alpine, Fedora 44 or Arch
    private val sessions = " ifname | username | calling-sid |     ip     | type  | state  | uptime-raw | rx-bytes-raw | tx-bytes-raw\n" +
        "--------+----------+-------------+------------+-------+--------+------------+--------------+--------------\n" +
        " ppp0   | alice    | 203.0.113.5 | 10.0.0.2   | pptp  | active | 125        | 1000         | 2000\n" +
        " ppp1   | bob      | 203.0.113.6 | 10.0.0.3   | sstp  | active | 3700       | 10           | 20\n"

    @Test
    fun theSessionsOfAccelPppComeToThePanelAndEndByTheirInterface() = runTest {
        assertEquals(listOf("ppp0", "alice", "203.0.113.5", "10.0.0.2", "pptp", "125"), parseAccelSessions(sessions).first().let { row ->
            listOf("ifname", "username", "calling-sid", "ip", "type", "uptime-raw").map { row[it] }
        })
        val host = VpnHost(
            listOf(
                "for t in" to ok("accel-cmd\n"), "id -u" to ok("0\n"), "echo \"--nh-now--" to ok("--nh-now-- 1794939096\n--nh-zone-- +0300\n--nh-accel--\n$sessions"),
                "accel-cmd terminate" to ok(),
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        val (alice, bob) = (session.watch(MenuPath.parse("vpn/session")).first() as ListEvent.Snapshot).items
        assertEquals(
            listOf("accel/ppp0", "PPTP", "accel-ppp", "alice", "203.0.113.5", "10.0.0.2", "2m5s", "1000", "2000"),
            listOf(alice.id) + listOf("kind", "service", "user", "endpoint", "address", "uptime", "rx", "tx").map { alice[it] },
        )
        assertEquals("SSTP", bob["kind"])
        session.run(MenuPath.parse("vpn/session"), "disconnect", mapOf("numbers" to "accel/ppp1")).toList()
        assertEquals("accel-cmd terminate if 'ppp1' >/dev/null", host.ran.last())
    }
}
