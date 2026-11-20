package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxProxyClientTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    // the link Client Links gives for a server of the wizard, its UUID and public key made up
    private val vless = "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@192.0.2.10:8443?encryption=none&security=reality&sni=dl.google.com&fp=chrome" +
        "&pbk=Gk3-h_0x-QpWb3K2X_9mJcN8kH3ZqVYxRrT1uEoP0lA&sid=0123456789abcdef&type=tcp&flow=xtls-rprx-vision#user1"

    // the clients a host has, as the read of their files prints them: none unless a test says otherwise
    private fun host(vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok("sing-box\nxray\nsystemctl\nss\n"), "id -u" to ok("0\n"), "s() {" to ok(), "command -v sing-box" to ok("/usr/bin/sing-box\n"),
            "command -v xray" to ok("/usr/local/bin/xray\n"), "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "if command -v systemctl" to ok(), "systemctl" to ok(),
        ),
    )

    private fun written(host: VpnHost) = Json.parseToJsonElement(host.given.single()).jsonObject

    private fun JsonObject.at(vararg keys: String): String = keys.dropLast(1).fold(this) { json, key -> json.getValue(key).jsonObject }.getValue(keys.last()).jsonPrimitive.content

    @Test
    fun aLinkOfVlessWithRealityBecomesAClientOfSingBoxWithAFileAndAServiceOfItsOwn() = runTest {
        val host = host()
        assertEquals("home", SshHostSession(host, device, linux = true).add(MenuPath.parse("proxy/sing-box/client"), mapOf("name" to "home", "link" to vless)))
        val config = written(host)
        val inbound = config.getValue("inbounds").jsonArray.single().jsonObject
        assertEquals(listOf("mixed", "127.0.0.1", "1080"), listOf(inbound.at("type"), inbound.at("listen"), inbound.at("listen_port")))
        val outbound = config.getValue("outbounds").jsonArray.single().jsonObject
        assertEquals(
            listOf("vless", "192.0.2.10", "8443", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "xtls-rprx-vision", "true", "dl.google.com", "chrome", "true", "Gk3-h_0x-QpWb3K2X_9mJcN8kH3ZqVYxRrT1uEoP0lA", "0123456789abcdef"),
            listOf(
                outbound.at("type"), outbound.at("server"), outbound.at("server_port"), outbound.at("uuid"), outbound.at("flow"), outbound.at("tls", "enabled"), outbound.at("tls", "server_name"),
                outbound.at("tls", "utls", "fingerprint"), outbound.at("tls", "reality", "enabled"), outbound.at("tls", "reality", "public_key"), outbound.at("tls", "reality", "short_id"),
            ),
        )
        val file = "/etc/networkhelm/sing-box/home.json"
        assertTrue(host.ran.any { it.startsWith("umask 077; mkdir -p") && "sing-box check -c '$file'" in it }, "${host.ran}")
        val unit = host.ran.single { it.startsWith("if command -v systemctl") }
        assertTrue("/etc/systemd/system/sing-box-client-home.service" in unit && "ExecStart=/usr/bin/sing-box -c $file run" in unit, unit)
        assertTrue(host.ran.any { it.startsWith("systemctl start sing-box-client-home") } && "systemctl enable sing-box-client-home" in host.ran, "${host.ran}")
        assertTrue(host.sent.none { "aaaaaaaa-bbbb" in it }, "the UUID goes to the host as input alone: ${host.sent}")
    }

    @Test
    fun theSameLinkMakesAClientOfXrayOnAPortNothingHasTaken() = runTest {
        val host = host("ss -H -tuln" to ok("tcp   LISTEN 0      4096         127.0.0.1:1080     0.0.0.0:*\n"))
        SshHostSession(host, device, linux = true).add(MenuPath.parse("proxy/xray/client"), mapOf("name" to "home", "link" to vless, "autostart" to "no"))
        val config = written(host)
        val inbound = config.getValue("inbounds").jsonArray.single().jsonObject
        assertEquals(listOf("socks", "127.0.0.1", "10808", "true"), listOf(inbound.at("protocol"), inbound.at("listen"), inbound.at("port"), inbound.at("settings", "udp")))
        val outbound = config.getValue("outbounds").jsonArray.single().jsonObject
        val server = outbound.getValue("settings").jsonObject.getValue("vnext").jsonArray.single().jsonObject
        val user = server.getValue("users").jsonArray.single().jsonObject
        assertEquals(
            listOf("vless", "192.0.2.10", "8443", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "none", "xtls-rprx-vision"),
            listOf(outbound.at("protocol"), server.at("address"), server.at("port"), user.at("id"), user.at("encryption"), user.at("flow")),
        )
        assertEquals(
            listOf("tcp", "reality", "dl.google.com", "chrome", "Gk3-h_0x-QpWb3K2X_9mJcN8kH3ZqVYxRrT1uEoP0lA", "0123456789abcdef"),
            listOf("network", "security").map { outbound.at("streamSettings", it) } + listOf("serverName", "fingerprint", "publicKey", "shortId").map { outbound.at("streamSettings", "realitySettings", it) },
        )
        assertTrue("ExecStart=/usr/local/bin/xray run -config /etc/networkhelm/xray/home.json" in host.ran.single { it.startsWith("if command -v systemctl") }, "${host.ran}")
        assertTrue(host.ran.any { it.startsWith("systemctl start xray-client-home") } && host.ran.none { it.startsWith("systemctl enable") }, "${host.ran}")
    }

    // the case of 18.11 on the lab: sing-box 1.14.4 on 39002 against the wizard of Xray 26.11.9 on 39001 got reality verification failed, as its
    // Client Hello lacks X25519MLKEM768; sing-box against a server of sing-box, whose link has no spx, still goes
    @Test
    fun singBoxThatLeavesOutX25519Mlkem768IsSentToXrayForALinkOfRealityOfXray() = runTest {
        val ofXray = vless.replace("#user1", "&spx=%2F#user1")
        val old = host("sing-box version" to ok("sing-box version 1.14.4\n\nEnvironment: go1.25.3 linux/amd64\n"))
        val session = SshHostSession(old, device, linux = true)
        val client = MenuPath.parse("proxy/sing-box/client")
        assertEquals(
            "sing-box 1.14.4 leaves X25519MLKEM768 out of its Client Hello of Reality, and servers of Xray refuse such a client: make it on the Clients tab of Xray, which Install Now of Proxy Overview installs",
            assertFailsWith<DeviceException> { session.add(client, mapOf("name" to "home", "link" to ofXray)) }.message,
        )
        assertTrue(old.given.isEmpty())
        session.add(client, mapOf("name" to "home", "link" to vless))
        assertEquals(1, old.given.size)
        val fixed = host("sing-box version" to ok("sing-box version 1.15.0-alpha.14\n"))
        SshHostSession(fixed, device, linux = true).add(client, mapOf("name" to "home", "link" to ofXray))
        assertEquals(1, fixed.given.size)
    }

    @Test
    fun aLinkOfHysteria2WithSalamanderMakesAClientOfSingBoxThatSkipsTheCheckItAsksToSkip() = runTest {
        val host = host()
        SshHostSession(host, device, linux = true).add(
            MenuPath.parse("proxy/sing-box/client"), mapOf("name" to "hy2", "link" to "hy2://rd-test%2Bhy2%2Fpass@192.0.2.10:8443?sni=dl.google.com&insecure=1&obfs=salamander&obfs-password=rd-test-obfs#alice"),
        )
        val outbound = written(host).getValue("outbounds").jsonArray.single().jsonObject
        assertEquals(
            listOf("hysteria2", "192.0.2.10", "8443", "rd-test+hy2/pass", "salamander", "rd-test-obfs", "true", "dl.google.com", "true"),
            listOf(
                outbound.at("type"), outbound.at("server"), outbound.at("server_port"), outbound.at("password"), outbound.at("obfs", "type"), outbound.at("obfs", "password"),
                outbound.at("tls", "enabled"), outbound.at("tls", "server_name"), outbound.at("tls", "insecure"),
            ),
        )
    }

    // the link of a server behind a certificate it signed itself: sing-box skips the check, Xray 26 pins the certificate and refuses a link
    // without its pin
    @Test
    fun aCertificateTheServerSignedItselfIsPinnedByXrayAndNotCheckedBySingBox() = runTest {
        val pin = "709e80c88487a2411e1ee4dfb9f22a861492d20c4765150c0c794abd70f8147c"
        val trojan = "trojan://rd-test-trojan@192.0.2.10:8443?security=tls&sni=dl.google.com&allowInsecure=1&pcs=$pin&type=tcp#bob"
        val xray = host()
        SshHostSession(xray, device, linux = true).add(MenuPath.parse("proxy/xray/client"), mapOf("name" to "home", "link" to trojan))
        val tls = written(xray).getValue("outbounds").jsonArray.single().jsonObject.getValue("streamSettings").jsonObject.getValue("tlsSettings").jsonObject
        assertEquals(listOf("dl.google.com", pin), listOf(tls.at("serverName"), tls.at("pinnedPeerCertSha256")))
        assertTrue("allowInsecure" !in tls, tls.toString())
        val singBox = host()
        SshHostSession(singBox, device, linux = true).add(MenuPath.parse("proxy/sing-box/client"), mapOf("name" to "home", "link" to trojan))
        assertEquals("true", written(singBox).getValue("outbounds").jsonArray.single().jsonObject.at("tls", "insecure"))
        val unpinned = host()
        assertEquals(
            "the client of Xray checks every certificate, and the link gives no pcs to pin this one",
            assertFailsWith<DeviceException> {
                SshHostSession(unpinned, device, linux = true).add(MenuPath.parse("proxy/xray/client"), mapOf("name" to "home", "link" to trojan.replace("&pcs=$pin", "")))
            }.message,
        )
        assertTrue(unpinned.given.isEmpty())
    }

    @Test
    fun linksOfShadowsocksVmessTrojanAndHysteria2GiveTheirServersAndSecrets() {
        val ss = parseProxyLink("ss://MjAyMi1ibGFrZTMtYWVzLTEyOC1nY206cmQtdGVzdC1zZXJ2ZXI@192.0.2.10:8080#")
        assertEquals(listOf("shadowsocks", "192.0.2.10", "8080", "2022-blake3-aes-128-gcm", "rd-test-server"), listOf(ss.type, ss.host, "${ss.port}", ss.method, ss.secret))
        val plain = parseProxyLink("ss://2022-blake3-aes-256-gcm:rd%2Btest%3D@[2001:db8::1]:8388#x")
        assertEquals(listOf("2001:db8::1", "8388", "2022-blake3-aes-256-gcm", "rd+test="), listOf(plain.host, "${plain.port}", plain.method, plain.secret))
        val vmess = parseProxyLink(
            "vmess://eyJ2IjoiMiIsInBzIjoiYm9iIiwiYWRkIjoicHJveHkuZXhhbXBsZS5uZXQiLCJwb3J0IjoiODQ0MyIsImlkIjoiMTExMTExMTEtMjIyMi0zMzMzLTQ0NDQtNTU1NTU1NTU1NTU1IiwiYWlkIjoiMCIsIm5ldCI6IndzIiwidHlwZSI6" +
                "Im5vbmUiLCJob3N0IjoiY2RuLmV4YW1wbGUubmV0IiwicGF0aCI6Ii93cyIsInRscyI6InRscyIsInNuaSI6InByb3h5LmV4YW1wbGUubmV0In0=",
        )
        assertEquals(
            listOf("vmess", "proxy.example.net", "8443", "11111111-2222-3333-4444-555555555555", "tls", "proxy.example.net", "ws", "/ws", "cdn.example.net"),
            listOf(vmess.type, vmess.host, "${vmess.port}", vmess.secret, vmess.security, vmess.serverName, vmess.network, vmess.query["path"], vmess.query["host"]),
        )
        val trojan = parseProxyLink("trojan://rd-test-trojan@192.0.2.10:8443?security=tls&sni=proxy.example.net&type=grpc&serviceName=tunnel#bob%40example.net")
        assertEquals(listOf("trojan", "rd-test-trojan", "tls", "proxy.example.net", "grpc", "tunnel"), listOf(trojan.type, trojan.secret, trojan.security, trojan.serverName, trojan.network, trojan.query["serviceName"]))
        val hy2 = parseProxyLink("hy2://rd-test-hy2@192.0.2.10:8443?sni=proxy.example.net&insecure=1#x")
        assertEquals(listOf("hysteria2", "rd-test-hy2", "tls", "proxy.example.net", "1"), listOf(hy2.type, hy2.secret, hy2.security, hy2.serverName, hy2.query["insecure"]))
    }

    @Test
    fun aLinkTheClientCannotTakeIsRefusedWithoutItsSecretBeforeAnythingIsWritten() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        val client = MenuPath.parse("proxy/xray/client")
        val refusals = listOf(
            "wireguard://rd-test-secret@192.0.2.10:51820" to "the link is none of vless://, vmess://, trojan://, ss:// and hy2://",
            "vless://rd-test-secret@192.0.2.10" to "the link names no server, port or secret",
            "vmess://rd-test-secret" to "the link names no server, port or secret",
            "hy2://rd-test-secret@192.0.2.10:8443?sni=proxy.example.net" to "the client of Xray takes no link of hysteria2",
            "trojan://rd-test-secret@192.0.2.10:443?type=kcp" to "the client takes no transport kcp",
        )
        for ((link, message) in refusals) assertEquals(message, assertFailsWith<DeviceException> { session.add(client, mapOf("name" to "x", "link" to link)) }.message)
        assertEquals("name: a b is not a value it takes", assertFailsWith<DeviceException> { session.add(client, mapOf("name" to "a b", "link" to vless)) }.message)
        assertTrue(host.given.isEmpty() && host.sent.none { "rd-test-secret" in it }, "${host.sent}")
    }

    @Test
    fun theClientsComeWithTheirServersPortsAndServicesButNoSecret() = runTest {
        val masked = """{ "inbounds": [ { "type": "mixed", "listen": "127.0.0.1", "listen_port": 1080 } ], "outbounds": [ { "type": "vless", "server": "192.0.2.10", "server_port": 8443, "uuid": "(hidden)" } ] }"""
        val host = host("s() {" to ok("--nh-client-- home active enabled\n$masked\n"))
        val row = SshHostSession(host, device, linux = true).rows("proxy/sing-box/client").single()
        assertEquals(
            listOf("home", "vless", "192.0.2.10:8443", "1080", "active", "true", "/etc/networkhelm/sing-box/home.json"),
            listOf("name", "type", "server", "local-port", "state", "autostart", "config").map { row[it] },
        )
        assertTrue(host.ran.any { it.startsWith("s() {") && "for f in /etc/networkhelm/sing-box/*.json" in it && "(hidden)" in it }, "${host.ran}")
    }

    @Test
    fun aRemovedClientLeavesWithItsServiceAndFileAndAnUnknownOneIsRefused() = runTest {
        val host = host("s() {" to ok("--nh-client-- home inactive disabled\n{ \"inbounds\": [], \"outbounds\": [] }\n"))
        val session = SshHostSession(host, device, linux = true)
        val client = MenuPath.parse("proxy/xray/client")
        assertEquals("xray has no client away", assertFailsWith<DeviceException> { session.remove(client, listOf("away")) }.message)
        session.remove(client, listOf("home"))
        val gone = host.ran.single { it.startsWith("if command -v systemctl") }
        listOf("systemctl disable --now xray-client-home", "rm -f /etc/systemd/system/xray-client-home.service", "rm -f '/etc/networkhelm/xray/home.json'").forEach { assertTrue(it in gone, gone) }
    }
}
