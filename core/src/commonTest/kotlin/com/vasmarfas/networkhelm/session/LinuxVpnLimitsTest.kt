package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxVpnLimitsTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    @Test
    fun aRateIsWrittenAsRouterOsWritesIt() {
        assertEquals(5_000L to 20_000L, vpnRates("5M/20M"))
        assertEquals(512L to 512L, vpnRates("512k"))
        assertEquals(1_000L to 2_000_000L, vpnRates("1000000/2G"))
        assertEquals("rate-limit: fast is not a value it takes", assertFailsWith<DeviceException> { vpnRates("fast") }.message)
        assertEquals(listOf("20M", "512k", "2G", "0"), listOf(20_000L, 512L, 2_000_000L, 0L).map(::vpnRate))
    }

    @Test
    fun theLinesOfAUserGoToTheFileAndTcIsMadeAgainOnEachDeviceTheyTouch() = runTest {
        val host = VpnHost(
            listOf(
                "id -u" to ok("0\n"), "cat /etc/networkhelm/vpn-limits" to ok("bob wg0 10.66.0.3/32 1000 1000\nalice wg1 10.67.0.2/32 2000 2000\n"),
                "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "/usr/local/sbin/networkhelm-vpn-limits" to ok(),
            ),
        )
        val limits = LinuxVpnLimits(Root(host, null))
        limits.set("alice", listOf(VpnLimit("alice", "wg0", "10.66.0.2/32", 20_000, 5_000), VpnLimit("alice", "wg0", "fd00::2/128", 20_000, 5_000)))
        val (script, rule, lines) = host.given
        assertTrue(script.startsWith("#!/bin/sh\n") && "tc filter add dev \"\$d\" parent ffff: matchall action mirred egress redirect dev \"\$i\"" in script, script)
        assertEquals("ACTION==\"add\", SUBSYSTEM==\"net\", KERNEL!=\"ifb-*\", RUN+=\"/usr/local/sbin/networkhelm-vpn-limits %k\"\n", rule)
        assertEquals("bob wg0 10.66.0.3/32 1000 1000\nalice wg0 10.66.0.2/32 20000 5000\nalice wg0 fd00::2/128 20000 5000\n", lines)
        assertEquals("/usr/local/sbin/networkhelm-vpn-limits 'wg1' && /usr/local/sbin/networkhelm-vpn-limits 'wg0'", host.ran.last())
    }
}
