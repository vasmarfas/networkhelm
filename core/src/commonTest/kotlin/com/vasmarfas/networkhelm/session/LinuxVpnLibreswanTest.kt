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

class LinuxVpnLibreswanTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private val conns = MenuPath.parse("vpn/libreswan")

    // what pluto of Libreswan 5.4.1 on Fedora 44 printed in the lab on 17.11 for a conn with left=nonsense, with exit 0
    private val refused = "\"bad\": failed to resolve 'left=nonsense', domain 'nonsense' is bogus: validation failure <nonsense. A IN>: no signatures from " +
        "192.168.10.1 for trust anchor . while building chain of trust\n\"bad\": added unoriented IKEv2 connection (neither left=nonsense nor right=%any match an interface)\n"

    // ipsec.conf of the package on Fedora 44, the bad conn of the lab and one made here; the line of trafficstatus is written to the format of
    // Libreswan 5, the lab has no SA of Libreswan yet
    private val state = "--nh-file-- /etc/ipsec.conf\nconfig setup\n\tlogfile=/var/log/pluto.log\ninclude /etc/crypto-policies/back-ends/libreswan.config\n" +
        "include /etc/ipsec.d/*.conf\n--nh-file-- /etc/ipsec.d/bad.conf\nconn bad\n\tleft=nonsense\n\tright=%any\n" +
        "--nh-file-- /etc/ipsec.d/networkhelm-office.conf\nconn office\n    left=%defaultroute\n    leftsubnet=192.168.1.0/24\n    right=203.0.113.9\n" +
        "    rightsubnet=192.168.2.0/24\n    authby=secret\n    auto=start\n--nh-loaded--\nbad\noffice\n--nh-traffic--\n" +
        "#3: \"office\"[1] 203.0.113.9, type=ESP, add_time=1794939000, inBytes=1024, outBytes=2048, maxBytes=2^63B, id='203.0.113.9'\n--nh-unit-- active\n"

    private fun host(vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok("ipsec\nsystemctl\n"), "id -u" to ok("0\n"), "ipsec --version" to ok("Libreswan 5.4.1\n"), "for f in /etc/ipsec.conf" to ok(state),
            "umask 077; mkdir -p" to ok("\"office2\": added IKEv2 connection\n"), "rm -f" to ok(), "if [ -e" to ok(), "ipsec delete" to ok(), "f=" to ok(),
        ),
    )

    @Test
    fun theConnsComeFromTheFilesWithWhatPlutoHasOfThem() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        val (bad, office) = session.rows("vpn/libreswan")
        assertEquals(listOf("bad", "true", "0", "nonsense", "/etc/ipsec.d/bad.conf"), listOf("name", "loaded", "sas", "left", "file").map { bad[it] })
        assertEquals(
            listOf("office", "1", "192.168.1.0/24", "203.0.113.9", "secret", "start", ""),
            listOf("name", "sas", "leftsubnet", "right", "authby", "auto", "psk").map { office[it] },
        )
        val other = assertFailsWith<DeviceException> { session.set(conns, "bad", mapOf("right" to "203.0.113.10")) }
        assertEquals("/etc/ipsec.d/bad.conf is no file of NetworkHelm, and it stays as it is", other.message)
        session.set(conns, "office", mapOf("right" to "203.0.113.10", "psk" to ""))
        assertEquals(
            "f='/etc/ipsec.d/networkhelm-office.secrets'; [ ! -f \"\$f\" ] || { sed -i 's|^.* : PSK |%any 203.0.113.10 : PSK |' \"\$f\" && ipsec secrets >&2; }",
            host.ran.last(),
        )

        val four = "--nh-file-- /etc/ipsec.d/office.conf\nconn office\n--nh-traffic--\n006 #2: \"office\", type=ESP, add_time=1794939000, inBytes=0, outBytes=0, id='@office'\n"
        assertEquals(1, parseLibreswan(four).conns.single().sas)
    }

    @Test
    fun aConnCountsAsAddedOnlyWherePlutoSaysNeitherFailedNorUnoriented() = runTest {
        assertTrue(libreswanFailed(refused))
        assertEquals(false, libreswanFailed("\"office2\": added IKEv2 connection\n"))
        val host = host()
        SshHostSession(host, device, linux = true).add(conns, mapOf("name" to "office2", "right" to "203.0.113.10", "authby" to "secret", "psk" to "rd-test-psk", "auto" to "add"))
        val (conn, secret) = host.given
        assertEquals("%any 203.0.113.10 : PSK \"rd-test-psk\"\n", secret)
        assertEquals("conn office2\n    right=203.0.113.10\n    authby=secret\n    auto=add\n", conn)
        assertTrue(host.ran.any { it.startsWith("umask 077; mkdir -p '/etc/ipsec.d'") && it.endsWith("\nipsec addconn 'office2' 2>&1") }, "${host.ran}")
        assertTrue(host.sent.none { "rd-test-psk" in it }, "the key is on no command line")

        val bad = host("umask 077; mkdir -p '/etc/ipsec.d'" to ok(refused))
        val failed = assertFailsWith<DeviceException> {
            SshHostSession(bad, device, linux = true).add(conns, mapOf("name" to "bad2", "left" to "nonsense", "psk" to "rd-test-psk"))
        }
        assertTrue("unoriented" in failed.message.orEmpty(), failed.message)
        assertEquals(listOf("conn bad2\n    left=nonsense\n"), bad.given, "a refused conn writes no key")
        val (back, delete) = bad.ran.takeLast(2)
        assertTrue(back.startsWith("if [ -e '/etc/ipsec.d/.networkhelm-bad2.conf.networkhelm-old' ]"), back)
        assertTrue(delete.startsWith("ipsec delete 'bad2' >/dev/null 2>&1;"), delete)
    }

    // an up the peer refused in the lab on 17.11, .notes/lab/samples/libreswan-pair.txt: pluto exits with 56 and keeps reviving the conn
    // until it is taken down
    @Test
    fun anUpThePeerRefusesIsTakenDownByTheGuard() = runTest {
        val rejected = "\"office\" #10: IKE SA authentication request rejected by peer: AUTHENTICATION_FAILED\n" +
            "\"office\" #10: encountered fatal error in state IKE_AUTH_I\n\"office\" #11: connection is supposed to remain up; revival attempt 1 scheduled in 0 seconds\n"
        val host = host("B=\$(mktemp" to ok("4242 /tmp/networkhelm-vpn.Ab12Cd\n"), "ipsec up" to ExecResult("", rejected, 56), "kill" to ok())
        val failed = assertFailsWith<DeviceException> { SshHostSession(host, device, linux = true).run(conns, "up", mapOf("numbers" to "office")).toList() }
        assertTrue("AUTHENTICATION_FAILED" in failed.message.orEmpty(), failed.message)
        val (up, down) = host.ran.takeLast(2)
        assertEquals("ipsec up 'office' >&2", up)
        assertEquals("kill 4242; sh -c 'ipsec down '\\''office'\\''' restore '/tmp/networkhelm-vpn.Ab12Cd'; rm -rf '/tmp/networkhelm-vpn.Ab12Cd'", down)
    }

    // strongSwan of Debian brings an ipsec of its own, which the overview does not take for Libreswan
    @Test
    fun theOverviewTellsLibreswanFromTheIpsecOfStrongSwan() = runTest {
        val libreswan = SshHostSession(host(), device, linux = true).rows("vpn/overview").single { it.id == "libreswan" }
        assertEquals(listOf("Libreswan", "true", "libreswan"), listOf("name", "installed", "packages").map { libreswan[it] })
        val strongSwan = VpnHost(listOf("for t in" to ok("ipsec\nswanctl\n"), "id -u" to ok("0\n"), "ipsec --version" to ok("Linux strongSwan U5.9.13/K6.8.4-2-pve\n")))
        val rows = SshHostSession(strongSwan, device, linux = true).rows("vpn/overview").associate { it.id to it["installed"] }
        assertEquals("false" to "true", rows["libreswan"] to rows["ipsec"])
    }
}
