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

class LinuxProxySquidTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private val squid = MenuPath.parse("proxy/squid")
    private val access = MenuPath.parse("proxy/squid/access")
    private val user = MenuPath.parse("proxy/squid/user")

    // squid.conf of the package of Squid 5.7 on Debian 12 without its comments, in part, written by that package and not recorded
    private val conf = listOf(
        "acl localnet src 10.0.0.0/8\t\t# RFC 1918 local private network (LAN)", "acl localnet src 192.168.0.0/16\t\t# RFC 1918 local private network (LAN)",
        "acl SSL_ports port 443", "acl Safe_ports port 80\t\t# http", "acl Safe_ports port 443\t\t# https", "http_access deny !Safe_ports",
        "http_access deny CONNECT !SSL_ports", "http_access allow localhost manager", "http_access deny manager", "include /etc/squid/conf.d/*.conf",
        "http_access allow localhost", "http_access deny all", "http_port 3128", "coredump_dir /var/spool/squid",
    )

    private val file = "#\n# Recommended minimum configuration:\n#\n${conf.joinToString("\n")}\ncache_peer parent.example.net parent 3128 0 no-query login=nh-peer:rd-test-peer\n"

    // the read: the password of the peer masked by the host, its user apart, the users of basic_ncsa_auth by name
    private fun state(auth: Boolean) = "--nh-squid-- active enabled\n--nh-squid-version-- 5.7\n--nh-squid-helper-- /usr/lib/squid/basic_ncsa_auth\n" +
        "--nh-squid-parent-user-- nh-peer\n--nh-squid-conf--\n" +
        (if (auth) "auth_param basic program /usr/lib/squid/basic_ncsa_auth /etc/squid/passwords\n" else "") + conf.joinToString("\n") +
        "\ncache_peer parent.example.net parent 3128 0 no-query login=(hidden)\n" + (if (auth) "--nh-squid-users-- /etc/squid/passwords\nalice\nbob\n" else "")

    private fun host(state: String, vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok("squid\nsystemctl\napt-get\nss\n"), "id -u" to ok("0\n"), "c=/etc/squid/squid.conf" to ok(state), "cat /etc/squid/squid.conf" to ok(file),
            "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "squid -k" to ok(), "[ -e " to ok(), "umask 077; t=" to ok(), "systemctl" to ok(),
        ),
    )

    @Test
    fun theSettingsRulesAclsAndUsersComeFromSquidConfAndTheFileOfBasicAuth() = runTest {
        val session = SshHostSession(host(state(auth = true)), device, linux = true)
        val row = session.rows("proxy/squid").single()
        assertEquals(listOf("active", "true", "5.7", "3128", "true", "2"), listOf("state", "autostart", "version", "http-port", "auth", "users").map { row[it] })
        assertEquals(
            listOf("deny !Safe_ports", "deny CONNECT !SSL_ports", "allow localhost manager", "deny manager", "allow localhost", "deny all"),
            session.rows("proxy/squid/access").map { "${it["action"]} ${it["acls"]}" },
        )
        val acl = session.rows("proxy/squid/acl").first()
        assertEquals(listOf("1", "localnet", "src", "10.0.0.0/8"), listOf(acl.id, acl["name"], acl["type"], acl["values"]))
        assertEquals(listOf("alice", "bob"), session.rows("proxy/squid/user").map { it.id })
    }

    @Test
    fun aNewRuleGoesBeforeDenyAllAndTheFileGoesThroughTheCheckOfSquid() = runTest {
        val host = host(state(auth = false))
        assertEquals("6", SshHostSession(host, device, linux = true).add(access, mapOf("action" to "allow", "acls" to "localnet")))
        val written = host.given.single().lines()
        assertEquals(listOf("http_access allow localhost", "http_access allow localnet", "http_access deny all"), written.filter { it.startsWith("http_access") }.takeLast(3))
        assertTrue("cache_peer parent.example.net parent 3128 0 no-query login=nh-peer:rd-test-peer" in written, "the peer keeps its password")
        assertTrue(host.ran.any { it.startsWith("umask 077; mkdir -p") && "squid -k parse -f /etc/squid/squid.conf" in it }, "${host.ran}")
        assertTrue("squid -k reconfigure -f /etc/squid/squid.conf" in host.ran, "${host.ran}")
    }

    @Test
    fun aRuleMovedToTheTopLeavesTheIncludeWhereItStoodAmongTheRules() = runTest {
        val host = host(state(auth = false))
        SshHostSession(host, device, linux = true).move(access, listOf("5"), "1")
        assertEquals(
            listOf(
                "http_access allow localhost", "http_access deny !Safe_ports", "http_access deny CONNECT !SSL_ports", "http_access allow localhost manager",
                "include /etc/squid/conf.d/*.conf", "http_access deny manager", "http_access deny all",
            ),
            host.given.single().lines().filter { it.startsWith("http_access") || it.startsWith("include") },
        )
    }

    @Test
    fun basicAuthTurnedOnNamesTheHelperOfTheHostAndLetsItsUsersInBeforeDenyAll() = runTest {
        val host = host(state(auth = false))
        SshHostSession(host, device, linux = true).set(squid, "squid", mapOf("auth" to "yes"))
        assertTrue(host.ran.any { it.startsWith("[ -e '/etc/squid/passwords' ]") }, "${host.ran}")
        val written = host.given.single().lines()
        val first = written.indexOfFirst { it.startsWith("acl ") }
        assertEquals(listOf("auth_param basic program /usr/lib/squid/basic_ncsa_auth /etc/squid/passwords", "auth_param basic realm proxy"), written.subList(first - 2, first))
        assertEquals("acl networkhelm_users proxy_auth REQUIRED", written.last { it.startsWith("acl ") })
        assertEquals(listOf("http_access allow localhost", "http_access allow networkhelm_users", "http_access deny all"), written.filter { it.startsWith("http_access") }.takeLast(3))
    }

    @Test
    fun aUserGetsItsPasswordHashedFromTheInputAndNoCommandHoldsIt() = runTest {
        val host = host(state(auth = true))
        assertEquals("carol", SshHostSession(host, device, linux = true).add(user, mapOf("name" to "carol", "password" to "rd-test-carol")))
        assertEquals("rd-test-carol\n", host.given.single())
        assertTrue(host.ran.any { "openssl passwd -apr1 -stdin" in it && "u='carol'" in it }, "${host.ran}")
        assertTrue(host.sent.none { "rd-test-carol" in it }, "${host.sent}")
    }

    // what the summary of the host printed for an access log of four requests, one of them older than 30 days, in a file of gzip beside it
    @Test
    fun theClientsAndSitesOfTheAccessLogComeByTheirBytes() {
        val text = "client 192.168.1.10 2 6000 6000 6000\ndomain www.example.org 1 5000 5000 5000\ndomain example.com 2 1000 1300 1300\nclient 192.168.1.11 1 0 300 300\n"
        assertEquals(listOf("192.168.1.10" to "6000", "192.168.1.11" to "300"), parseTraffic(text, "client").map { it.id to it["month-bytes"] })
        assertEquals(listOf(listOf("www.example.org", "1", "5000"), listOf("example.com", "2", "1000")), parseTraffic(text, "domain").map { listOf(it["name"], it["requests"], it["day-bytes"]) })
    }

    @Test
    fun usersWaitForBasicAuthOfSquid() = runTest {
        val error = assertFailsWith<DeviceException> { SshHostSession(host(state(auth = false)), device, linux = true).add(user, mapOf("name" to "carol", "password" to "rd-test-carol")) }
        assertEquals("Squid takes no users of its own: no auth_param of it names basic_ncsa_auth", error.message)
    }

    @Test
    fun theParentGoesInAsCachePeerKeepsItsLoginAndEveryRequestGoesThroughIt() = runTest {
        val session = SshHostSession(host(state(auth = false)), device, linux = true)
        val row = session.rows("proxy/squid").single()
        assertEquals(listOf("parent.example.net:3128", "nh-peer", ""), listOf("parent", "parent-user", "parent-password").map { row[it] })
        val moved = host(state(auth = false))
        SshHostSession(moved, device, linux = true).set(squid, "squid", mapOf("parent" to "proxy.example.net:8080"))
        val written = moved.given.single().lines()
        assertTrue("cache_peer proxy.example.net parent 8080 0 no-query login=nh-peer:rd-test-peer" in written, "$written")
        assertEquals("never_direct allow all", written.last { it.isNotBlank() })
        val login = host(state(auth = false))
        SshHostSession(login, device, linux = true).set(squid, "squid", mapOf("parent-user" to "nh-new", "parent-password" to "rd-test-new"))
        assertTrue("cache_peer parent.example.net parent 3128 0 no-query login=nh-new:rd-test-new" in login.given.single().lines(), login.given.single())
        assertTrue(login.sent.none { "rd-test-new" in it }, "${login.sent}")
        val gone = host(state(auth = false), "cat /etc/squid/squid.conf" to ok(file + "never_direct allow all\n"))
        SshHostSession(gone, device, linux = true).set(squid, "squid", mapOf("parent" to null))
        assertTrue(gone.given.single().lines().none { it.startsWith("cache_peer") || it.startsWith("never_direct") }, gone.given.single())
    }

    @Test
    fun sizesAndTimesGoWithTheirUnitsAndCacheOffDeniesEveryObject() = runTest {
        val host = host(state(auth = false))
        val changes = mapOf(
            "cache-mem" to "256 MB", "maximum-object-size-in-memory" to "512 KB", "connect-timeout" to "30 seconds", "shutdown-lifetime" to "3 seconds",
            "logfile-rotate" to "7", "cache" to "no",
        )
        SshHostSession(host, device, linux = true).set(squid, "squid", changes)
        val written = host.given.single().lines()
        for (line in listOf("cache_mem 256 MB", "maximum_object_size_in_memory 512 KB", "connect_timeout 30 seconds", "shutdown_lifetime 3 seconds", "logfile_rotate 7", "cache deny all")) {
            assertTrue(line in written, "$line in $written")
        }
        val session = SshHostSession(host(state(auth = false)), device, linux = true)
        val refusals = listOf(
            mapOf("cache-mem" to "256MB") to "cache-mem: 256MB is not a value it takes",
            mapOf("connect-timeout" to "30") to "connect-timeout: 30 is not a value it takes",
            mapOf("logfile-rotate" to "-1") to "logfile-rotate: -1 is not a value it takes",
            mapOf("parent" to "proxy.example.net") to "parent: proxy.example.net is not a value it takes",
            mapOf("parent-user" to "nh:peer") to "parent-user: nh:peer is not a value it takes",
            mapOf("parent-password" to "rd test") to "the password of the parent proxy may hold no space, quote or #",
        )
        for ((values, message) in refusals) assertEquals(message, assertFailsWith<DeviceException> { session.set(squid, "squid", values) }.message)
    }

    @Test
    fun aSquidThatDidNotStartSaysWhyWithoutThePasswordsOfItsJournal() = runTest {
        val journal = "FATAL: Bungled /etc/squid/squid.conf line 16: cache_peer parent.example.net parent 3128 0 no-query login=nh-peer:rd-test-peer"
        val host = host(state(auth = true), "systemctl start squid" to ExecResult(journal, "", 1))
        val error = assertFailsWith<DeviceException> { SshHostSession(host, device, linux = true).run(squid, "up", emptyMap()).toList() }
        assertTrue(error.message.orEmpty().startsWith("squid did not start: FATAL: Bungled"), error.message)
        assertTrue("rd-test-peer" !in error.message.orEmpty(), error.message)
    }
}
