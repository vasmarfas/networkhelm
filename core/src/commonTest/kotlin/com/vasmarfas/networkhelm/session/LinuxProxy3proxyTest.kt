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

class LinuxProxy3proxyTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    // /etc/3proxy.cfg of the package of Fedora 44 without its comments, as the lab recorded it, with the secrets of its users made up
    private fun conf(clear: String, crypt: String) = listOf(
        "nscache 65536", "timeouts 1 5 30 60 180 1800 15 60", "users 3APA3A:CL:$clear \"test:CR:$crypt\"", "daemon", "log /var/log/3proxy/3proxy.log",
        "logformat \"- +_L%t.%.  %N.%p %E %U %C:%c %R:%r %O %I %h %T\"", "archiver gz /bin/gzip %F", "rotate 30", "auth iponly", "external 0.0.0.0",
        "internal 127.0.0.1", "auth none", "dnspr", "auth strong", "deny * * 127.0.0.1,192.168.1.1", "allow * * * 80-88,8080-8088 HTTP", "allow * * * 443,8443 HTTPS",
        "proxy -n", "auth none", "pop3p", "tcppm 25 mail.my.provider 25", "auth strong", "flush", "allow 3APA3A,test", "maxconn 20", "socks", "auth strong", "flush",
        "internal 127.0.0.1", "allow 3APA3A 127.0.0.1", "maxconn 3", "admin",
    ).joinToString("\n")

    private fun host(state: String = "inactive", vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok("3proxy\nsystemctl\ndnf\n"), "id -u" to ok("0\n"),
            "c=\nfor f in /etc/3proxy.cfg" to ok("--nh-3proxy-- $state enabled /etc/3proxy.cfg\n--nh-3proxy-conf--\n${conf("(hidden)", "(hidden)")}\n"),
            "cat '/etc/3proxy.cfg'" to ok(conf("rd-test-clear", "\$1\$qwer\$rdtest") + "\n"), "if command -v openssl" to ok("\$1\$nhsalt\$rdtestcarol\n"),
            "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "systemctl" to ok(),
        ),
    )

    @Test
    fun theSettingsTheListOfAccessAndTheUsersComeInTheOrderOfTheFile() = runTest {
        val session = SshHostSession(host(), device, linux = true)
        assertEquals("/var/log/3proxy/3proxy.log", session.rows("proxy/3proxy").single()["log"])
        val access = session.rows("proxy/3proxy/access")
        assertEquals(24, access.size)
        assertEquals(listOf("auth iponly", "external 0.0.0.0", "internal 127.0.0.1"), access.take(3).map { "${it["directive"]} ${it["arguments"]}" })
        assertEquals(listOf("3APA3A" to "CL", "test" to "CR"), session.rows("proxy/3proxy/user").map { it.id to it["type"] })
    }

    @Test
    fun aNewUserGetsACryptOfItsPasswordQuotedForTheDollarsAndTheOthersStay() = runTest {
        val host = host("active")
        assertEquals("carol", SshHostSession(host, device, linux = true).add(MenuPath.parse("proxy/3proxy/user"), mapOf("name" to "carol", "password" to "rd-test-carol")))
        assertEquals("rd-test-carol\n", host.given.first())
        val users = host.given.last().lines().single { it.startsWith("users ") }
        assertEquals("users 3APA3A:CL:rd-test-clear \"test:CR:\$1\$qwer\$rdtest\" \"carol:CR:\$1\$nhsalt\$rdtestcarol\"", users)
        assertTrue(host.sent.none { "rd-test-carol" in it }, "${host.sent}")
        assertTrue(host.ran.any { it.startsWith("systemctl restart 3proxy") }, "${host.ran}")
    }

    @Test
    fun theCacheOfNamesRotationAndTimeoutsComeAndGoInTheirPlaces() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        val row = session.rows("proxy/3proxy").single()
        assertEquals(listOf("65536", "30", "1 5 30 60 180 1800 15 60"), listOf("nscache", "rotate", "timeouts").map { row[it] })
        session.set(MenuPath.parse("proxy/3proxy"), "3proxy", mapOf("timeouts" to "1 5 30 60 180 1800 15 60 30", "rotate" to "7"))
        val lines = host.given.single().lines()
        assertEquals(listOf("nscache 65536", "timeouts 1 5 30 60 180 1800 15 60 30"), lines.take(2))
        assertEquals("auth iponly", lines[lines.indexOf("rotate 7") + 1])
        val refusals = listOf(
            mapOf("timeouts" to "1 5 x") to "timeouts: 1 5 x is not a value it takes",
            mapOf("maxconn" to "0") to "maxconn: 0 is not a value it takes",
            mapOf("rotate" to "-2") to "rotate: -2 is not a value it takes",
        )
        for ((values, message) in refusals) assertEquals(message, assertFailsWith<DeviceException> { session.set(MenuPath.parse("proxy/3proxy"), "3proxy", values) }.message)
    }

    @Test
    fun aLineMovedTakesThePlaceOfTheOneItGoesBefore() = runTest {
        val host = host()
        SshHostSession(host, device, linux = true).move(MenuPath.parse("proxy/3proxy/access"), listOf("2"), "1")
        val lines = host.given.single().lines()
        assertEquals(listOf("rotate 30", "external 0.0.0.0", "auth iponly", "internal 127.0.0.1"), lines.subList(lines.indexOf("rotate 30"), lines.indexOf("rotate 30") + 4))
    }
}
