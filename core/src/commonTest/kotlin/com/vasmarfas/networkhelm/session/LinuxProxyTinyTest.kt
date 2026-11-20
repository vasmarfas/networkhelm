package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxProxyTinyTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private val tinyproxy = MenuPath.parse("proxy/tinyproxy")
    private val user = MenuPath.parse("proxy/tinyproxy/user")

    // tinyproxy.conf of the package of tinyproxy 1.11.1 on Debian 12 without its comments, in part, with a user and an upstream added;
    // written by that package and not recorded
    private val conf = listOf(
        "User tinyproxy", "Group tinyproxy", "Port 8888", "Timeout 600", "DefaultErrorFile \"/usr/share/tinyproxy/default.html\"", "LogFile \"/var/log/tinyproxy/tinyproxy.log\"",
        "LogLevel Info", "PidFile \"/run/tinyproxy/tinyproxy.pid\"", "MaxClients 100", "Allow 127.0.0.1", "Allow ::1", "ViaProxyName \"tinyproxy\"",
    )

    private val file = "# tinyproxy daemon configuration file\n${conf.joinToString("\n")}\nBasicAuth alice rd-test-alice\nUpstream http nh-up:rd-test-up@proxy.example.net:3128\n"

    // the read with the passwords masked by the host; the second user is a line the mask would have missed, and its name comes alone all the same
    private val state = "--nh-tiny-- active enabled /etc/tinyproxy/tinyproxy.conf\n--nh-tiny-version-- 1.11.1\n--nh-tiny-conf--\n${conf.joinToString("\n")}\n" +
        "BasicAuth alice (hidden)\nBasicAuth bob rd-test-bob\nUpstream http nh-up:(hidden)@proxy.example.net:3128\n"

    private fun host(vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok("tinyproxy\nsystemctl\napt-get\n"), "id -u" to ok("0\n"), "c=\nfor f in" to ok(state), "cat '/etc/tinyproxy/tinyproxy.conf'" to ok(file),
            "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "systemctl" to ok(),
        ),
    )

    @Test
    fun theSettingsAndUsersComeWithoutAnyPassword() = runTest {
        val session = SshHostSession(host(), device, linux = true)
        val row = session.rows("proxy/tinyproxy").single()
        assertEquals(
            listOf("active", "1.11.1", "8888", "127.0.0.1, ::1", "100", "Info", "http proxy.example.net:3128", "nh-up", "", "2"),
            listOf("state", "version", "port", "allow", "max-clients", "log-level", "upstream", "upstream-user", "upstream-password", "users").map { row[it] },
        )
        val users = session.rows("proxy/tinyproxy/user")
        assertEquals(listOf("alice", "bob"), users.map { it.id })
        assertTrue((listOf(row) + users).flatMap { it.values.values }.none { "rd-test" in it }, "$row $users")
    }

    @Test
    fun aNewPasswordOfTheUpstreamGoesIntoTheFileAndARunningTinyproxyRestarts() = runTest {
        val host = host()
        SshHostSession(host, device, linux = true).set(tinyproxy, "tinyproxy", mapOf("port" to "8080", "upstream-password" to "rd-test-new"))
        val written = host.given.single().lines()
        assertTrue("Port 8080" in written && "Upstream http nh-up:rd-test-new@proxy.example.net:3128" in written && "BasicAuth alice rd-test-alice" in written, "$written")
        assertTrue(host.ran.any { it.startsWith("systemctl restart tinyproxy") }, "${host.ran}")
        assertTrue(host.sent.none { "rd-test-new" in it }, "${host.sent}")
    }

    @Test
    fun aTinyproxyThatDoesNotComeUpGetsItsOldFileBackAndTheErrorNoPassword() = runTest {
        val host = host("systemctl restart tinyproxy" to ExecResult("ERROR: Unable to parse config: BasicAuth alice rd-test-alice", "", 1))
        val error = assertFailsWith<DeviceException> { SshHostSession(host, device, linux = true).set(tinyproxy, "tinyproxy", mapOf("port" to "8080")) }
        assertTrue(error.message.orEmpty().startsWith("tinyproxy did not start: ERROR: Unable to parse config"), error.message)
        assertTrue("rd-test-alice" !in error.message.orEmpty(), error.message)
        assertEquals(listOf("Port 8080", "Port 8888"), host.given.map { written -> written.lines().single { it.startsWith("Port ") } })
    }

    @Test
    fun aPasswordTinyproxyWouldNotParseIsRefusedWithoutBeingRepeated() = runTest {
        val session = SshHostSession(host(), device, linux = true)
        for (password in listOf("rd test", "rd-test@home", "rd-test!")) {
            val error = assertFailsWith<DeviceException> { session.add(user, mapOf("name" to "carol", "password" to password)) }
            assertEquals("a password of BasicAuth of tinyproxy takes letters, digits, dots, dashes and underscores alone", error.message)
        }
        assertEquals("name: carol@home is not a value it takes", assertFailsWith<DeviceException> { session.add(user, mapOf("name" to "carol@home", "password" to "rd-test")) }.message)
    }

    @Test
    fun theAccessListsHeadersAndBindGoInAsLinesTinyproxyParses() = runTest {
        val host = host()
        val changes = mapOf(
            "bind" to "192.0.2.10", "deny" to "192.168.1.13, 10.0.0.0/8", "connect-ports" to "443, 563, 8443", "via-proxy-name" to "edge", "xtinyproxy" to "true",
            "disable-via-header" to "true",
        )
        SshHostSession(host, device, linux = true).set(tinyproxy, "tinyproxy", changes)
        val written = host.given.single().lines()
        assertEquals(
            listOf("Bind 192.0.2.10", "Deny 192.168.1.13", "Deny 10.0.0.0/8", "ConnectPort 443", "ConnectPort 563", "ConnectPort 8443", "XTinyproxy Yes", "DisableViaHeader Yes"),
            written.filter { line -> listOf("Deny", "ConnectPort", "Bind", "XTinyproxy", "DisableViaHeader").any { line.startsWith("$it ") } },
        )
        assertEquals(written.indexOf("ViaProxyName \"edge\""), file.lines().indexOf("ViaProxyName \"tinyproxy\""))
    }

    @Test
    fun theSwitchesOfTheFileComeAsTicksAndTheListsAsTheyStand() = runTest {
        val read = state.replace("MaxClients 100\n", "MaxClients 100\nDisableViaHeader Yes\nXTinyproxy off\nConnectPort 443\nConnectPort 563\nDeny 192.168.1.13\n")
        val row = SshHostSession(host("c=\nfor f in" to ok(read)), device, linux = true).rows("proxy/tinyproxy").single()
        assertEquals(
            listOf("true", "false", "443, 563", "192.168.1.13", "tinyproxy"),
            listOf("disable-via-header", "xtinyproxy", "connect-ports", "deny", "via-proxy-name").map { row[it] },
        )
    }

    @Test
    fun valuesTinyproxyWouldNotParseAreRefusedBeforeTheFileChanges() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        val refusals = listOf(
            mapOf("upstream" to "http [2001:db8::1]:3128") to "upstream: http [2001:db8::1]:3128 is not a value it takes",
            mapOf("connect-ports" to "443, 70000") to "connect-ports: 443, 70000 is not a value it takes",
            mapOf("via-proxy-name" to "edge \"one\"") to "via-proxy-name: edge \"one\" is not a value it takes",
            mapOf("deny" to "192.168.1.0/24, ~bad") to "deny: 192.168.1.0/24, ~bad is not a value it takes",
        )
        for ((changes, message) in refusals) assertEquals(message, assertFailsWith<DeviceException> { session.set(tinyproxy, "tinyproxy", changes) }.message)
        assertTrue(host.given.isEmpty(), "${host.given}")
    }
}
