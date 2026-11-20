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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxProxyShadowsocksTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private val shadowsocks = MenuPath.parse("proxy/shadowsocks")

    // config.json of the package of shadowsocks-libev on Debian 12, written by that package and not recorded; the package makes up the password
    private fun config(password: String) =
        "{\n    \"server\":[\"::1\", \"127.0.0.1\"],\n    \"mode\":\"tcp_and_udp\",\n    \"server_port\":8388,\n    \"local_port\":1080,\n" +
            "    \"password\":\"$password\",\n    \"timeout\":86400,\n    \"method\":\"chacha20-ietf-poly1305\"\n}\n"

    private fun host(vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok("ss-server\nsystemctl\napt-get\n"), "id -u" to ok("0\n"),
            "c=/etc/shadowsocks-libev/config.json" to ok("--nh-ss-- shadowsocks-libev active enabled\n--nh-ss-conf--\n${config("(hidden)")}"),
            "cat /etc/shadowsocks-libev/config.json" to ok(config("rd-test-ss")), "echo \"\$SSH_CONNECTION\"" to ok("203.0.113.5 50122 192.0.2.10 22\n"),
            "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "systemctl" to ok(),
        ),
    )

    @Test
    fun theServerComesFromConfigJsonWithoutItsPassword() = runTest {
        val row = (SshHostSession(host(), device, linux = true).watch(shadowsocks).first() as ListEvent.Snapshot).items.single()
        assertEquals(
            listOf("active", "shadowsocks-libev", "::1, 127.0.0.1", "8388", "", "chacha20-ietf-poly1305", "tcp_and_udp", "86400"),
            listOf("state", "service", "server", "server-port", "password", "method", "mode", "timeout").map { row[it] },
        )
    }

    @Test
    fun theLinkOfAClientTakesTheAddressOfTheSessionForAServerOnLoopback() = runTest {
        val link = SshHostSession(host(), device, linux = true).run(shadowsocks, "client-link", emptyMap()).toList().single()["conf"]
        assertEquals("ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpyZC10ZXN0LXNz@192.0.2.10:8388#192.0.2.10", link)
    }

    @Test
    fun aNewPasswordAndMethodGoIntoTheFileWithItsOtherKeysAndTheServerRestarts() = runTest {
        val host = host()
        SshHostSession(host, device, linux = true).set(shadowsocks, "shadowsocks", mapOf("password" to "rd-test-new", "method" to "aes-256-gcm", "server" to "0.0.0.0"))
        val written = Json.parseToJsonElement(host.given.single()).jsonObject
        assertEquals(
            listOf("rd-test-new", "aes-256-gcm", "0.0.0.0", "1080", "8388"),
            listOf("password", "method", "server", "local_port", "server_port").map { written.getValue(it).jsonPrimitive.content },
        )
        assertTrue(host.ran.any { it.startsWith("systemctl restart shadowsocks-libev") }, "${host.ran}")
        assertTrue(host.sent.none { "rd-test-new" in it }, "${host.sent}")
    }

    @Test
    fun aCipherOfTheOldStreamsIsRefused() = runTest {
        val error = assertFailsWith<DeviceException> { SshHostSession(host(), device, linux = true).set(shadowsocks, "shadowsocks", mapOf("method" to "rc4-md5")) }
        assertEquals("method: rc4-md5 is not a value it takes", error.message)
    }

    @Test
    fun theSwitchesGoInAsTrueAndFalseOfJsonAndTheNameServersAsOneList() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        session.set(shadowsocks, "shadowsocks", mapOf("fast-open" to "true", "no-delay" to "false", "nameserver" to "1.1.1.1, 8.8.8.8", "reuse-port" to "yes"))
        val written = Json.parseToJsonElement(host.given.single()).jsonObject
        assertEquals(
            listOf("true", "false", "1.1.1.1,8.8.8.8", "true"),
            listOf("fast_open", "no_delay", "nameserver", "reuse_port").map { written.getValue(it).jsonPrimitive.content },
        )
        assertTrue(!written.getValue("fast_open").jsonPrimitive.isString, "ss-server takes a boolean of JSON, not a string")
        assertEquals("nameserver: dns.example.net is not a value it takes", assertFailsWith<DeviceException> { session.set(shadowsocks, "shadowsocks", mapOf("nameserver" to "dns.example.net")) }.message)
        assertEquals("ipv6-first: maybe is not a value it takes", assertFailsWith<DeviceException> { session.set(shadowsocks, "shadowsocks", mapOf("ipv6-first" to "maybe")) }.message)
    }
}
