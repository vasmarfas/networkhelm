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

class LinuxProxyDanteTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private val rule = MenuPath.parse("proxy/dante/rule")
    private val user = MenuPath.parse("proxy/dante/user")

    // danted.conf of Debian 12 (Dante 1.4.2) made to work: the package gives only logoutput and the users, and -V refuses it without internal
    private val conf = listOf(
        "logoutput: syslog", "user.privileged: proxy", "user.unprivileged: nobody", "internal: 0.0.0.0 port = 1080", "external: eth0", "socksmethod: username",
        "clientmethod: none", "client pass {", "\tfrom: 0.0.0.0/0 to: 0.0.0.0/0", "\tlog: error connect disconnect", "}", "socks pass {",
        "\tfrom: 0.0.0.0/0 to: 0.0.0.0/0", "\tcommand: bind connect udpassociate", "\tlog: error connect disconnect", "\tsocksmethod: username", "}",
    )

    private val file = "# the rules of the lab\n${conf.joinToString("\n")}\n"

    private fun host(vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok("danted\nsystemctl\napt-get\n"), "id -u" to ok("0\n"),
            "c=/etc/danted.conf" to ok("--nh-dante-- active enabled /etc/danted.conf\n--nh-dante-version-- v1.4.2.\n--nh-dante-conf--\n$file--nh-dante-users--\nnh-socks\n"),
            "cat '/etc/danted.conf'" to ok(file), "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "if [ -e " to ok(), "u=" to ok(), "systemctl" to ok(),
        ),
    )

    @Test
    fun theSettingsRulesAndUsersComeFromTheConfigOfDante() = runTest {
        val session = SshHostSession(host(), device, linux = true)
        val row = session.rows("proxy/dante").single()
        assertEquals(listOf("active", "v1.4.2", "0.0.0.0 port = 1080", "eth0", "username", "proxy"), listOf("state", "version", "internal", "external", "socksmethod", "user-privileged").map { row[it] })
        assertEquals(
            listOf(
                listOf("client", "pass", "0.0.0.0/0", "0.0.0.0/0", "log: error connect disconnect"),
                listOf("socks", "pass", "0.0.0.0/0", "0.0.0.0/0", "command: bind connect udpassociate; log: error connect disconnect; socksmethod: username"),
            ),
            session.rows("proxy/dante/rule").map { item -> listOf("kind", "action", "from", "to", "options").map { item[it] } },
        )
        assertEquals(listOf("nh-socks"), session.rows("proxy/dante/user").map { it.id })
    }

    @Test
    fun aNewRuleGoesBeforeThePassOfItsKindForAnyAddressAndTheFileThroughDantedV() = runTest {
        val host = host()
        val id = SshHostSession(host, device, linux = true).add(rule, mapOf("kind" to "socks", "action" to "block", "from" to "0.0.0.0/0", "to" to "10.0.0.0/8"))
        assertEquals("2", id)
        val written = host.given.single().lines()
        val blocks = written.filter { it.endsWith("{") }
        assertEquals(listOf("client pass {", "socks block {", "socks pass {"), blocks)
        assertEquals(listOf("\tfrom: 0.0.0.0/0 to: 10.0.0.0/8", "\tlog: error connect disconnect"), written.drop(written.indexOf("socks block {") + 1).take(2))
        assertTrue(host.ran.any { it.startsWith("umask 077; mkdir -p") && it.endsWith("danted -V -f '/etc/danted.conf' 2>&1") }, "${host.ran}")
        assertTrue(host.ran.any { it.startsWith("systemctl restart danted") }, "${host.ran}")
    }

    // Debian gives user.privileged proxy, which cannot read /etc/shadow: with it the lab rejected the login of nh-socks (1 1)
    @Test
    fun theMethodUsernameRunsThePrivilegedPartAsRootAndAnotherUserForItIsRefused() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        session.set(MenuPath.parse("proxy/dante"), "dante", mapOf("logoutput" to "syslog stderr"))
        val written = host.given.single().lines()
        assertEquals(listOf("user.privileged: root"), written.filter { it.startsWith("user.privileged") })
        val error = assertFailsWith<DeviceException> { session.set(MenuPath.parse("proxy/dante"), "dante", mapOf("user-privileged" to "proxy")) }
        assertEquals("the method username needs user.privileged root: Dante reads /etc/shadow as that user", error.message)
    }

    @Test
    fun theRotationAndTimeoutsOfTheServerStandAmongItsSettingsBeforeTheRules() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        session.set(MenuPath.parse("proxy/dante"), "dante", mapOf("external-rotation" to "same-same", "timeout-connect" to "30", "timeout-io" to "600"))
        val written = host.given.single().lines()
        val settings = written.take(written.indexOf("client pass {"))
        assertTrue(listOf("external.rotation: same-same", "timeout.connect: 30", "timeout.io: 600").all { it in settings }, "$written")
        val refusals = listOf(
            mapOf("external-rotation" to "spread") to "external-rotation: spread is not a value it takes",
            mapOf("timeout-io" to "10m") to "timeout-io: 10m is not a value it takes",
        )
        for ((values, message) in refusals) assertEquals(message, assertFailsWith<DeviceException> { session.set(MenuPath.parse("proxy/dante"), "dante", values) }.message)
    }

    @Test
    fun aRuleMovedStaysAmongTheLinesThatWereAroundTheRules() = runTest {
        val host = host()
        SshHostSession(host, device, linux = true).move(rule, listOf("2"), "1")
        val written = host.given.single().lines()
        assertEquals(listOf("socks pass {", "client pass {"), written.filter { it.endsWith("{") })
        assertEquals(listOf("# the rules of the lab", "logoutput: syslog"), written.take(2))
    }

    // what danted -V of Debian 12 printed for the file of the lab with a broken rule
    @Test
    fun aFileDantedRefusesComesBackWithItsWords() = runTest {
        val refused = "Nov 17 22:34:57 (1794954897.629483) danted[14784]: error: /etc/danted.conf: problem on line 15 near token \"no\": syntax error.  " +
            "Please see the Dante manual for more information"
        val host = host("umask 077; mkdir -p" to ExecResult(refused, "", 1))
        val error = assertFailsWith<DeviceException> { SshHostSession(host, device, linux = true).set(MenuPath.parse("proxy/dante"), "dante", mapOf("socksmethod" to "username none")) }
        assertTrue("problem on line 15 near token" in error.message.orEmpty(), error.message)
        assertTrue(host.ran.any { it.startsWith("if [ -e ") }, "the old file comes back: ${host.ran}")
    }

    @Test
    fun aUserIsAnAccountWithoutAShellAndItsPasswordGoesToChpasswdAsInput() = runTest {
        val host = host()
        assertEquals("carol", SshHostSession(host, device, linux = true).add(user, mapOf("name" to "carol", "password" to "rd-test-carol")))
        assertEquals("rd-test-carol\n", host.given.single())
        assertTrue(host.ran.any { it.startsWith("u='carol'") && "-c networkhelm-socks" in it && "chpasswd" in it }, "${host.ran}")
        assertTrue(host.sent.none { "rd-test-carol" in it }, "${host.sent}")
    }
}
