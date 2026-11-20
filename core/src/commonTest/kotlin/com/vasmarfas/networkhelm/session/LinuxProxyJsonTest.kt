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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxProxyJsonTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.8.4", "x86_64", "", emptySet(), setOf("read", "write"))

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private fun state(file: String) = "PATH=\"\$PATH:/usr/local/bin\"\nc=\nfor f in $file"

    private fun read(service: String, file: String, version: String, config: String) = ok("--nh-json-- $service $file\n--nh-json-version-- $version\n--nh-json-conf--\n$config\n")

    // the inbound of the package of sing-box 1.13.11 on Alpine 3.24 as the lab recorded it, its key made up, and an inbound of VLESS with
    // Reality beside it; the private key of Reality is 32 zero bytes
    private val singBox = """
        {
          "log": { "level": "info" },
          "inbounds": [
            { "type": "shadowsocks", "listen": "::", "listen_port": 8080, "network": "tcp", "method": "2022-blake3-aes-128-gcm", "password": "rd-test-server" },
            {
              "type": "vless", "tag": "vless-in", "listen": "::", "listen_port": 443,
              "users": [ { "name": "alice", "uuid": "11111111-2222-3333-4444-555555555555", "flow": "xtls-rprx-vision" } ],
              "tls": {
                "enabled": true, "server_name": "www.example.com",
                "reality": { "enabled": true, "handshake": { "server": "www.example.com", "server_port": 443 }, "private_key": "${"A".repeat(43)}", "short_id": [ "0123abcd" ] }
              }
            }
          ],
          "outbounds": [ { "type": "direct" } ]
        }
    """.trimIndent()

    // the example config the packages of sing-box ship, release/config/config.json of its repository, with the password written there
    private val example = """
        {
          "log": { "level": "info" },
          "dns": { "servers": [ { "type": "tls", "tag": "google", "server": "8.8.8.8" } ] },
          "inbounds": [
            {
              "type": "shadowsocks", "listen": "::", "listen_port": 8080, "network": "tcp", "method": "2022-blake3-aes-128-gcm", "password": "${SING_BOX_EXAMPLE_PASSWORDS.last()}",
              "multiplex": { "enabled": true, "padding": true }
            }
          ],
          "outbounds": [ { "type": "direct" } ],
          "route": { "rules": [ { "port": 53, "action": "hijack-dns" } ] }
        }
    """.trimIndent()

    // a config of Xray by its documentation, not recorded: the lab has no Xray
    private val xray = """
        {
          "inbounds": [
            {
              "tag": "trojan-in", "protocol": "trojan", "listen": "0.0.0.0", "port": 8443,
              "settings": { "clients": [ { "email": "bob@example.net", "password": "rd-test-trojan" } ] },
              "streamSettings": { "network": "tcp", "security": "tls", "tlsSettings": { "serverName": "proxy.example.net" } }
            }
          ]
        }
    """.trimIndent()

    // what the wizard has the host make: a UUID of the kernel, a short ID out of od and the key pair as the program prints it
    private val singBoxKeys = "cat /proc/sys/kernel/random/uuid; head" to
        ok("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\n0123456789abcdef\nPrivateKey: ${"B".repeat(43)}\nPublicKey: ${"C".repeat(43)}\n")
    private val xrayKeys = "cat /proc/sys/kernel/random/uuid; head" to
        ok("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\nfedcba9876543210\nPrivateKey: ${"D".repeat(43)}\nPassword (PublicKey): ${"E".repeat(43)}\nHash32: ${"F".repeat(43)}\n")

    private fun host(vararg more: Pair<String, ExecResult>) = VpnHost(
        more.toList() + listOf(
            "for t in" to ok("sing-box\nxray\nsystemctl\n"), "id -u" to ok("0\n"),
            state("/etc/sing-box/config.json") to read("active enabled", "/etc/sing-box/config.json", "sing-box version 1.13.11", singBox),
            state("/usr/local/etc/xray/config.json") to read("active enabled", "/usr/local/etc/xray/config.json", "Xray 1.8.24", xray),
            "cat /proc/sys/kernel/random/uuid" to ok("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\n"), "command -v openssl" to ok("Gk3+h/0x+QpWb3K2X/9mJcN8kH3ZqVYxRrT1uEoP0lA=\n"),
            "echo \"\$SSH_CONNECTION\"" to ok("203.0.113.5 50122 192.0.2.10 22\n"), "umask 077; mkdir -p" to ok(), "rm -f" to ok(), "systemctl" to ok(),
        ),
    )

    private val singBoxInbound = MenuPath.parse("proxy/sing-box/inbound")

    private fun written(host: VpnHost) = Json.parseToJsonElement(host.given.single()).jsonObject

    // a block of PEM as sing-box prints its key and certificate
    private fun pem(type: String, body: String) = "-----BEGIN $type-----\n$body\n-----END $type-----"

    private fun JsonObject.at(vararg keys: String): String = keys.dropLast(1).fold(this) { json, key -> json.getValue(key).jsonObject }.getValue(keys.last()).jsonPrimitive.content

    @Test
    fun theInboundsAndTheirUsersComeWithoutTheirKeys() = runTest {
        val session = SshHostSession(host(), device, linux = true)
        assertEquals(
            listOf(listOf("", "shadowsocks", "8080", "none", "0"), listOf("vless-in", "vless", "443", "reality", "1")),
            session.rows("proxy/sing-box/inbound").map { row -> listOf("tag", "type", "port", "security", "users").map { row[it] } },
        )
        val users = session.rows("proxy/sing-box/user") + session.rows("proxy/xray/user")
        assertEquals(listOf("2/1" to "alice", "1/1" to "bob@example.net"), users.map { it.id to it["name"] })
        assertTrue(users.flatMap { it.values.values }.none { "rd-test" in it || "1111" in it }, "$users")
    }

    @Test
    fun theLinkOfVlessWithRealityTakesThePublicKeyOfItsPrivateKeyWithoutTheHost() = runTest {
        val host = host()
        val link = SshHostSession(host, device, linux = true).run(MenuPath.parse("proxy/sing-box/user"), "client-link", mapOf("number" to "2/1")).toList().single()["conf"]
        assertEquals(
            "vless://11111111-2222-3333-4444-555555555555@192.0.2.10:443?encryption=none&security=reality&sni=www.example.com&fp=chrome" +
                "&pbk=L-V9o0fNYkMVKNqsX7spBzD_9oSvxM_C7ZCZX1jLO3Q&sid=0123abcd&type=tcp&flow=xtls-rprx-vision#alice",
            link,
        )
        assertTrue(host.sent.none { "openssl" in it || "AAAAAAAA" in it }, "${host.sent}")
    }

    @Test
    fun theLinkOfRealityOfXrayCarriesTheSpiderPathThatTellsItIsXray() = runTest {
        val reality = """
            { "inbounds": [ {
              "tag": "vless-reality", "protocol": "vless", "listen": "0.0.0.0", "port": 8443,
              "settings": { "clients": [ { "id": "11111111-2222-3333-4444-555555555555", "email": "user1", "flow": "xtls-rprx-vision" } ], "decryption": "none" },
              "streamSettings": { "network": "tcp", "security": "reality",
                "realitySettings": { "dest": "dl.google.com:443", "serverNames": [ "dl.google.com" ], "privateKey": "${"A".repeat(43)}", "shortIds": [ "fedcba9876543210" ] } }
            } ] }
        """.trimIndent()
        val host = host(state("/usr/local/etc/xray/config.json") to read("active enabled", "/usr/local/etc/xray/config.json", "Xray 26.11.9", reality))
        val link = SshHostSession(host, device, linux = true).run(MenuPath.parse("proxy/xray/inbound"), "client-link", mapOf("number" to "1")).toList().single()["conf"]
        assertEquals(
            "vless://11111111-2222-3333-4444-555555555555@192.0.2.10:8443?encryption=none&security=reality&sni=dl.google.com&fp=chrome" +
                "&pbk=L-V9o0fNYkMVKNqsX7spBzD_9oSvxM_C7ZCZX1jLO3Q&sid=fedcba9876543210&spx=%2F&type=tcp&flow=xtls-rprx-vision#user1",
            link,
        )
    }

    @Test
    fun theLinkOfHysteria2CarriesSalamanderAndSkipsTheCheckOfACertificateSingBoxSignedItself() = runTest {
        val hysteria = """
            { "inbounds": [ {
              "type": "hysteria2", "tag": "hysteria2", "listen": "::", "listen_port": 8443, "users": [ { "name": "alice", "password": "rd-test+hy2/pass" } ],
              "obfs": { "type": "salamander", "password": "rd-test-obfs" },
              "tls": { "enabled": true, "server_name": "dl.google.com", "alpn": [ "h3" ], "certificate": [ "rd-test-certificate" ], "key": [ "rd-test-key" ] }
            } ] }
        """.trimIndent()
        val host = host(state("/etc/sing-box/config.json") to read("active enabled", "/etc/sing-box/config.json", "sing-box version 1.14.4", hysteria))
        val link = SshHostSession(host, device, linux = true).run(singBoxInbound, "client-link", mapOf("number" to "1")).toList().single()["conf"]
        assertEquals("hy2://rd-test%2Bhy2%2Fpass@192.0.2.10:8443?sni=dl.google.com&insecure=1&obfs=salamander&obfs-password=rd-test-obfs#alice", link)
    }

    @Test
    fun theLinkOfTrojanOfXrayTakesItsNameOfTheServer() = runTest {
        val link = SshHostSession(host(), device, linux = true).run(MenuPath.parse("proxy/xray/user"), "client-link", mapOf("number" to "1/1")).toList().single()["conf"]
        assertEquals("trojan://rd-test-trojan@192.0.2.10:8443?security=tls&sni=proxy.example.net&type=tcp#bob%40example.net", link)
    }

    @Test
    fun anInboundGivesTheLinksOfItsUsersAndOneOfShadowsocksWithoutUsersThatOfItsPassword() = runTest {
        val session = SshHostSession(host(), device, linux = true)
        val vless = session.run(singBoxInbound, "client-link", mapOf("number" to "2")).toList().map { it["conf"] }
        assertEquals(session.run(MenuPath.parse("proxy/sing-box/user"), "client-link", mapOf("number" to "2/1")).toList().map { it["conf"] }, vless)
        val shadowsocks = session.run(singBoxInbound, "client-link", mapOf("number" to "1")).toList().single()["conf"]
        assertEquals("ss://MjAyMi1ibGFrZTMtYWVzLTEyOC1nY206cmQtdGVzdC1zZXJ2ZXI@192.0.2.10:8080#", shadowsocks)
    }

    @Test
    fun aNewUserGetsAUuidOfTheKernelAndTheFileGoesThroughSingBoxCheck() = runTest {
        val host = host()
        assertEquals("2/2", SshHostSession(host, device, linux = true).add(MenuPath.parse("proxy/sing-box/user"), mapOf("inbound" to "2", "name" to "carol")))
        val written = Json.parseToJsonElement(host.given.single()).jsonObject
        val users = written.getValue("inbounds").jsonArray[1].jsonObject.getValue("users").jsonArray
        assertEquals(listOf("alice", "carol"), users.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", users[1].jsonObject.getValue("uuid").jsonPrimitive.content)
        assertTrue(host.ran.any { it.startsWith("umask 077; mkdir -p") && "sing-box check -c '/etc/sing-box/config.json'" in it }, "${host.ran}")
        assertTrue(host.ran.any { it.startsWith("systemctl restart sing-box") }, "${host.ran}")
    }

    @Test
    fun aConfigThatIsNoJsonIsRefusedBeforeTheHostSeesIt() = runTest {
        val host = host()
        val error = assertFailsWith<DeviceException> { SshHostSession(host, device, linux = true).run(MenuPath.parse("proxy/sing-box"), "save-config", mapOf("text" to "{ inbounds: ")).toList() }
        assertEquals("config.json is no JSON object", error.message)
        assertTrue(host.given.isEmpty())
    }

    @Test
    fun theWizardOffersItsSchemesAndAPortNoInboundAndNothingOnTheHostHasTaken() = runTest {
        val host = host("ss -H -tuln" to ok("udp   UNCONN 0      0            0.0.0.0:8443       0.0.0.0:*\n"))
        val session = SshHostSession(host, device, linux = true)
        val arguments = session.describe(singBoxInbound).arguments.associateBy { it.name }
        assertEquals(listOf("vless-reality", "vless-ws-tls", "trojan-tls", "shadowsocks-2022", "hysteria2"), arguments.getValue("scheme").choices)
        assertEquals(listOf("self-signed", "files", "acme"), arguments.getValue("certificate").choices)
        assertTrue(arguments.getValue("path").default.orEmpty().matches(Regex("/[0-9a-f]{8}")), "${arguments.getValue("path").default}")
        assertEquals(
            listOf("vless-reality", null, "dl.google.com", "user1", "yes"),
            listOf("scheme", "tag", "server-name", "user", "obfs").map { arguments.getValue(it).default },
        )
        assertEquals("auto", arguments.getValue("tag").placeholder)
        val port = arguments.getValue("port").default?.toIntOrNull() ?: 0
        assertTrue(port in 20000 until 60000, "443 is taken by vless-in, 8443 by a program of the host over UDP: $port")
        val xray = session.describe(MenuPath.parse("proxy/xray/inbound")).arguments
        assertEquals(listOf("vless-reality", "vless-ws-tls", "trojan-tls", "shadowsocks-2022"), xray.single { it.name == "scheme" }.choices)
        assertEquals(listOf("self-signed", "files"), xray.single { it.name == "certificate" }.choices)
        assertTrue(xray.none { it.name == "obfs" || it.name == "email" || it.name == "obfs-password" } && xray.any { it.name == "sniffing" }, "$xray")
        assertTrue("sniffing" !in arguments && "obfs-password" in arguments, "$arguments")
        // the settings of an inbound there is hang on its type, network and security, which a new inbound has not; the scheme hides the wizard
        // fields an inbound there is never shows
        assertEquals(listOf(mapOf("security" to "reality")), arguments.getValue("private-key").depends)
        assertEquals(listOf("vless-ws-tls", "trojan-tls", "hysteria2"), arguments.getValue("certificate").depends.map { it.getValue("scheme") })
    }

    @Test
    fun theWizardMakesShadowsocks2022WithTheKeysOfTheServerAndOfItsFirstUser() = runTest {
        val keys = "head -c 16 /dev/urandom | base64; head" to ok("${"A".repeat(22)}==\n${"B".repeat(22)}==\n")
        val xray = host(keys)
        assertEquals("2", SshHostSession(xray, device, linux = true).add(MenuPath.parse("proxy/xray/inbound"), mapOf("scheme" to "shadowsocks-2022", "listen" to "127.0.0.1")))
        val inbound = written(xray).getValue("inbounds").jsonArray[1].jsonObject
        assertEquals(
            listOf("shadowsocks-2022", "shadowsocks", "127.0.0.1", "443", "2022-blake3-aes-128-gcm", "${"A".repeat(22)}==", "tcp,udp"),
            listOf(
                inbound.at("tag"), inbound.at("protocol"), inbound.at("listen"), inbound.at("port"), inbound.at("settings", "method"), inbound.at("settings", "password"),
                inbound.at("settings", "network"),
            ),
        )
        val client = inbound.getValue("settings").jsonObject.getValue("clients").jsonArray.single().jsonObject
        assertEquals(listOf("${"B".repeat(22)}==", "user1"), listOf(client.at("password"), client.at("email")))
        val singBox = host(keys)
        assertEquals("3", SshHostSession(singBox, device, linux = true).add(singBoxInbound, mapOf("scheme" to "shadowsocks-2022", "user" to "carol")))
        val shadowsocks = written(singBox).getValue("inbounds").jsonArray[2].jsonObject
        assertEquals(
            listOf("shadowsocks", "::", "8443", "2022-blake3-aes-128-gcm", "${"A".repeat(22)}=="),
            listOf(shadowsocks.at("type"), shadowsocks.at("listen"), shadowsocks.at("listen_port"), shadowsocks.at("method"), shadowsocks.at("password")),
        )
        val user = shadowsocks.getValue("users").jsonArray.single().jsonObject
        assertEquals(listOf("carol", "${"B".repeat(22)}=="), listOf(user.at("name"), user.at("password")))
        assertTrue(singBox.sent.none { "AAAAAAAA" in it || "BBBBBBBB" in it }, "the keys go to the host as input alone: ${singBox.sent}")
    }

    @Test
    fun theWizardMakesHysteria2OfSingBoxWithSalamanderAndACertificateSingBoxSignsItself() = runTest {
        val key = pem("PRIVATE KEY", "rd-test-key")
        val certificate = pem("CERTIFICATE", "rd-test-certificate")
        val made = "head -c 18 /dev/urandom | base64" to ok("${"C".repeat(24)}\n${"D".repeat(24)}\n")
        val signed = "sing-box generate tls-keypair" to ok("$key\n\n$certificate\n\n")
        val host = host(made, signed)
        val session = SshHostSession(host, device, linux = true)
        assertEquals("3", session.add(singBoxInbound, mapOf("scheme" to "hysteria2", "user" to "alice")))
        val inbound = written(host).getValue("inbounds").jsonArray[2].jsonObject
        assertEquals(
            listOf("hysteria2", "hysteria2", "::", "8443", "alice", "C".repeat(24), "salamander", "D".repeat(24), "true", "dl.google.com"),
            listOf(
                inbound.at("type"), inbound.at("tag"), inbound.at("listen"), inbound.at("listen_port"), inbound.getValue("users").jsonArray.single().jsonObject.at("name"),
                inbound.getValue("users").jsonArray.single().jsonObject.at("password"), inbound.at("obfs", "type"), inbound.at("obfs", "password"), inbound.at("tls", "enabled"),
                inbound.at("tls", "server_name"),
            ),
        )
        val tls = inbound.getValue("tls").jsonObject
        assertEquals(listOf("h3"), tls.getValue("alpn").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(certificate.lines(), tls.getValue("certificate").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(key.lines(), tls.getValue("key").jsonArray.map { it.jsonPrimitive.content })
        assertTrue("sing-box generate tls-keypair 'dl.google.com' -m 120" in host.ran, "${host.ran}")
        val plain = host(made, signed)
        SshHostSession(plain, device, linux = true).add(singBoxInbound, mapOf("scheme" to "hysteria2", "obfs" to "no"))
        assertTrue("obfs" !in written(plain).getValue("inbounds").jsonArray[2].jsonObject, written(plain).toString())
        assertEquals(
            "scheme: hysteria2 is not a value it takes",
            assertFailsWith<DeviceException> { SshHostSession(host(made), device, linux = true).add(MenuPath.parse("proxy/xray/inbound"), mapOf("scheme" to "hysteria2")) }.message,
        )
    }

    @Test
    fun trojanOfSingBoxGoesBehindACertificateSingBoxSignsItselfAndVlessOfXrayOverWebSocketBehindOneXraySigns() = runTest {
        val key = pem("PRIVATE KEY", "rd-test-key")
        val certificate = pem("CERTIFICATE", "rd-test-certificate")
        val singBox = host("head -c 18 /dev/urandom | base64" to ok("${"C".repeat(24)}\n"), "sing-box generate tls-keypair" to ok("$key\n\n$certificate\n\n"))
        assertEquals("3", SshHostSession(singBox, device, linux = true).add(singBoxInbound, mapOf("scheme" to "trojan-tls", "user" to "bob")))
        val trojan = written(singBox).getValue("inbounds").jsonArray[2].jsonObject
        assertEquals(
            listOf("trojan", "trojan-tls", "8443", "bob", "C".repeat(24), "true", "dl.google.com"),
            listOf(
                trojan.at("type"), trojan.at("tag"), trojan.at("listen_port"), trojan.getValue("users").jsonArray.single().jsonObject.at("name"),
                trojan.getValue("users").jsonArray.single().jsonObject.at("password"), trojan.at("tls", "enabled"), trojan.at("tls", "server_name"),
            ),
        )
        val tls = trojan.getValue("tls").jsonObject
        assertEquals(listOf("h2", "http/1.1"), tls.getValue("alpn").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(certificate.lines(), tls.getValue("certificate").jsonArray.map { it.jsonPrimitive.content })
        val lines = "\"certificate\": [ \"-----BEGIN CERTIFICATE-----\", \"rd-test-certificate\", \"-----END CERTIFICATE-----\" ]"
        val xray = host("xray tls cert" to ok("{\n  $lines,\n  \"key\": [ \"rd-test-key\" ]\n}\n"))
        SshHostSession(xray, device, linux = true).add(MenuPath.parse("proxy/xray/inbound"), mapOf("scheme" to "vless-ws-tls", "path" to "/rd-test", "server-name" to "proxy.example.net"))
        val vless = written(xray).getValue("inbounds").jsonArray[1].jsonObject
        assertEquals(
            listOf("vless", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "none", "ws", "tls", "/rd-test", "proxy.example.net"),
            listOf(
                vless.at("protocol"), vless.getValue("settings").jsonObject.getValue("clients").jsonArray.single().jsonObject.at("id"), vless.at("settings", "decryption"),
                vless.at("streamSettings", "network"), vless.at("streamSettings", "security"), vless.at("streamSettings", "wsSettings", "path"),
                vless.at("streamSettings", "tlsSettings", "serverName"),
            ),
        )
        val held = vless.getValue("streamSettings").jsonObject.getValue("tlsSettings").jsonObject.getValue("certificates").jsonArray.single().jsonObject
        assertEquals(certificate.lines(), held.getValue("certificate").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("rd-test-key"), held.getValue("key").jsonArray.map { it.jsonPrimitive.content })
        assertTrue("xray tls cert --domain='proxy.example.net' --expire=87600h" in xray.ran, "${xray.ran}")
    }

    @Test
    fun aCertificateOfTheHostIsTakenByItsFilesAndAcmeOfSingBoxGoesByItsVersion() = runTest {
        val fullchain = "/etc/letsencrypt/live/proxy.example.net/fullchain.pem"
        val privkey = "/etc/letsencrypt/live/proxy.example.net/privkey.pem"
        val files = mapOf("scheme" to "trojan-tls", "certificate" to "files", "certificate-file" to fullchain, "key-file" to privkey, "server-name" to "proxy.example.net")
        val passwords = "head -c 18 /dev/urandom | base64" to ok("${"C".repeat(24)}\n")
        val present = host(passwords, "test -r" to ok())
        SshHostSession(present, device, linux = true).add(MenuPath.parse("proxy/xray/inbound"), files)
        val held = written(present).getValue("inbounds").jsonArray[1].jsonObject.getValue("streamSettings").jsonObject.getValue("tlsSettings").jsonObject
            .getValue("certificates").jsonArray.single().jsonObject
        assertEquals(listOf(fullchain, privkey), listOf(held.at("certificateFile"), held.at("keyFile")))
        val missing = host(passwords, "test -r '$fullchain'" to ok(), "test -r" to ExecResult("", "", 1))
        assertEquals("the host has no file $privkey", assertFailsWith<DeviceException> { SshHostSession(missing, device, linux = true).add(singBoxInbound, files) }.message)
        assertTrue(missing.given.isEmpty())
        val acme = mapOf("scheme" to "trojan-tls", "certificate" to "acme", "server-name" to "proxy.example.net", "email" to "admin@example.net")
        val old = host(passwords)
        SshHostSession(old, device, linux = true).add(singBoxInbound, acme)
        val before = written(old).getValue("inbounds").jsonArray[2].jsonObject.getValue("tls").jsonObject
        assertEquals(listOf("proxy.example.net", "/var/lib/sing-box/acme", "admin@example.net"), listOf(before.getValue("acme").jsonObject.getValue("domain").jsonArray.single().jsonPrimitive.content, before.at("acme", "data_directory"), before.at("acme", "email")))
        val new = host(passwords, state("/etc/sing-box/config.json") to read("active enabled", "/etc/sing-box/config.json", "sing-box version 1.14.4", singBox))
        SshHostSession(new, device, linux = true).add(singBoxInbound, acme)
        val after = written(new).getValue("inbounds").jsonArray[2].jsonObject.getValue("tls").jsonObject
        assertEquals(listOf("acme", "proxy.example.net"), listOf(after.at("certificate_provider", "type"), after.getValue("certificate_provider").jsonObject.getValue("domain").jsonArray.single().jsonPrimitive.content))
        assertTrue("acme" !in after, after.toString())
        val session = SshHostSession(host(passwords), device, linux = true)
        assertEquals("ACME needs a domain of this host in server-name, not dl.google.com", assertFailsWith<DeviceException> { session.add(singBoxInbound, acme - "server-name") }.message)
        assertEquals("certificate: acme is not a value it takes", assertFailsWith<DeviceException> { session.add(MenuPath.parse("proxy/xray/inbound"), acme) }.message)
        assertEquals("certificate-file: fullchain.pem is not a value it takes", assertFailsWith<DeviceException> { session.add(singBoxInbound, files + ("certificate-file" to "fullchain.pem")) }.message)
    }

    @Test
    fun aServerBehindACertificateItSignedItselfGivesLinksThatSkipItsCheckAndPinIt() = runTest {
        val trojan = """
            { "inbounds": [ {
              "type": "trojan", "tag": "trojan-tls", "listen": "::", "listen_port": 8443, "users": [ { "name": "bob", "password": "rd-test-trojan" } ],
              "tls": { "enabled": true, "server_name": "dl.google.com", "alpn": [ "h2", "http/1.1" ], "certificate": [ "-----BEGIN CERTIFICATE-----", "AAAA", "-----END CERTIFICATE-----" ], "key": [ "rd-test-key" ] }
            } ] }
        """.trimIndent()
        val signed = host(state("/etc/sing-box/config.json") to read("active enabled", "/etc/sing-box/config.json", "sing-box version 1.14.4", trojan))
        assertEquals(
            "trojan://rd-test-trojan@192.0.2.10:8443?security=tls&sni=dl.google.com&allowInsecure=1&pcs=709e80c88487a2411e1ee4dfb9f22a861492d20c4765150c0c794abd70f8147c&type=tcp#bob",
            SshHostSession(signed, device, linux = true).run(singBoxInbound, "client-link", mapOf("number" to "1")).toList().single()["conf"],
        )
        val vless = """
            { "inbounds": [ {
              "tag": "vless-ws-tls", "protocol": "vless", "listen": "0.0.0.0", "port": 443,
              "settings": { "clients": [ { "id": "11111111-2222-3333-4444-555555555555", "email": "user1" } ], "decryption": "none" },
              "streamSettings": { "network": "ws", "security": "tls", "wsSettings": { "path": "/rd-test", "host": "cdn.example.net" },
                "tlsSettings": { "serverName": "proxy.example.net", "certificates": [ { "certificateFile": "/etc/ssl/proxy.pem", "keyFile": "/etc/ssl/proxy.key" } ] } }
            } ] }
        """.trimIndent()
        val files = host(state("/usr/local/etc/xray/config.json") to read("active enabled", "/usr/local/etc/xray/config.json", "Xray 26.11.9", vless))
        assertEquals(
            "vless://11111111-2222-3333-4444-555555555555@192.0.2.10:443?encryption=none&security=tls&sni=proxy.example.net&type=ws&path=%2Frd-test&host=cdn.example.net#user1",
            SshHostSession(files, device, linux = true).run(MenuPath.parse("proxy/xray/inbound"), "client-link", mapOf("number" to "1")).toList().single()["conf"],
        )
    }

    @Test
    fun theWizardOfXrayWritesVlessWithRealityAndItsFirstUserAndStartsTheServiceAtBoot() = runTest {
        val host = host(xrayKeys)
        assertEquals("2", SshHostSession(host, device, linux = true).add(MenuPath.parse("proxy/xray/inbound"), emptyMap()))
        val config = written(host)
        val (trojan, vless) = config.getValue("inbounds").jsonArray.map { it.jsonObject }
        assertEquals("rd-test-trojan", trojan.getValue("settings").jsonObject.getValue("clients").jsonArray.single().jsonObject.at("password"))
        assertEquals(
            listOf("vless-reality", "0.0.0.0", "443", "vless", "none", "tcp", "reality", "dl.google.com:443", "D".repeat(43)),
            listOf(
                vless.at("tag"), vless.at("listen"), vless.at("port"), vless.at("protocol"), vless.at("settings", "decryption"), vless.at("streamSettings", "network"),
                vless.at("streamSettings", "security"), vless.at("streamSettings", "realitySettings", "dest"), vless.at("streamSettings", "realitySettings", "privateKey"),
            ),
        )
        val reality = vless.getValue("streamSettings").jsonObject.getValue("realitySettings").jsonObject
        assertEquals(listOf("dl.google.com"), reality.getValue("serverNames").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("fedcba9876543210"), reality.getValue("shortIds").jsonArray.map { it.jsonPrimitive.content })
        val client = vless.getValue("settings").jsonObject.getValue("clients").jsonArray.single().jsonObject
        assertEquals(listOf("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "user1", "xtls-rprx-vision"), listOf(client.at("id"), client.at("email"), client.at("flow")))
        assertEquals("""[{"protocol":"freedom","tag":"direct"}]""", config.getValue("outbounds").toString())
        assertTrue(host.ran.any { it.startsWith("umask 077; mkdir -p") && "xray run -test -c '/usr/local/etc/xray/config.json'" in it }, "${host.ran}")
        assertTrue(host.ran.any { it.startsWith("systemctl restart xray") } && "systemctl enable xray" in host.ran, "${host.ran}")
        assertTrue(host.sent.none { "DDDDDDDD" in it || "aaaaaaaa-bbbb" in it }, "the key and the UUID go to the host as input alone: ${host.sent}")
    }

    @Test
    fun theWizardOfSingBoxDropsTheExampleInboundWithItsKnownPasswordAndStartsTheStoppedService() = runTest {
        val host = host(
            singBoxKeys, "ss -H -tuln" to ok("tcp   LISTEN 0      511          0.0.0.0:443        0.0.0.0:*\n"),
            state("/etc/sing-box/config.json") to read("inactive disabled", "/etc/sing-box/config.json", "sing-box version 1.13.11", example),
        )
        assertEquals("1", SshHostSession(host, device, linux = true).add(singBoxInbound, mapOf("user" to "alice")))
        val config = written(host)
        val vless = config.getValue("inbounds").jsonArray.single().jsonObject
        assertEquals(
            listOf("vless", "vless-reality", "::", "8443", "true", "dl.google.com", "true", "dl.google.com", "443", "B".repeat(43)),
            listOf(
                vless.at("type"), vless.at("tag"), vless.at("listen"), vless.at("listen_port"), vless.at("tls", "enabled"), vless.at("tls", "server_name"),
                vless.at("tls", "reality", "enabled"), vless.at("tls", "reality", "handshake", "server"), vless.at("tls", "reality", "handshake", "server_port"),
                vless.at("tls", "reality", "private_key"),
            ),
        )
        val tls = vless.getValue("tls").jsonObject
        assertEquals(listOf("0123456789abcdef"), tls.getValue("reality").jsonObject.getValue("short_id").jsonArray.map { it.jsonPrimitive.content })
        val user = vless.getValue("users").jsonArray.single().jsonObject
        assertEquals(listOf("alice", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "xtls-rprx-vision"), listOf(user.at("name"), user.at("uuid"), user.at("flow")))
        assertEquals(listOf("log", "dns", "inbounds", "outbounds", "route"), config.keys.toList())
        assertEquals("""[{"type":"direct"}]""", config.getValue("outbounds").toString())
        assertTrue(host.ran.any { it.startsWith("systemctl restart sing-box") } && "systemctl enable sing-box" in host.ran, "${host.ran}")
    }

    @Test
    fun aServerThatDoesNotComeUpGetsTheOldFileBackAndStaysStopped() = runTest {
        val bind = "FATAL[0000] start service: start inbound/vless[vless-reality]: listen tcp [::]:443: bind: address already in use"
        val host = host(
            singBoxKeys, "systemctl restart sing-box" to ExecResult("$bind\n", "", 1),
            state("/etc/sing-box/config.json") to read("inactive disabled", "/etc/sing-box/config.json", "sing-box version 1.13.11", example),
        )
        val error = assertFailsWith<DeviceException> { SshHostSession(host, device, linux = true).add(singBoxInbound, emptyMap()) }
        assertEquals("sing-box did not start: $bind", error.message)
        assertEquals(2, host.given.size)
        assertEquals("$example\n", host.given[1])
        assertTrue("systemctl stop sing-box" in host.ran && "systemctl enable sing-box" !in host.ran, "${host.ran}")
        assertTrue("BBBBBBBB" !in error.message.orEmpty() && "aaaaaaaa-bbbb" !in error.message.orEmpty())
    }

    @Test
    fun theWizardRefusesATakenTagOrPortAndANameOfASiteItCannotBeBeforeTheHostMakesAnything() = runTest {
        val host = host(singBoxKeys, "ss -H -tuln" to ok("tcp   LISTEN 0      4096         0.0.0.0:3128       0.0.0.0:*\n"))
        val session = SshHostSession(host, device, linux = true)
        val refusals = listOf(
            mapOf("tag" to "vless-in") to "sing-box has an inbound vless-in already",
            mapOf("port" to "443") to "sing-box has an inbound on port 443 already",
            mapOf("port" to "3128") to "port 3128 is taken on the host",
            mapOf("port" to "70000") to "port: 70000 is not a value it takes",
            mapOf("server-name" to "-www.example.com") to "server-name: -www.example.com is not a value it takes",
            mapOf("scheme" to "vmess-ws") to "scheme: vmess-ws is not a value it takes",
            mapOf("listen" to "my host") to "listen: my host is not a value it takes",
        )
        for ((values, message) in refusals) assertEquals(message, assertFailsWith<DeviceException> { session.add(singBoxInbound, values) }.message)
        assertTrue(host.given.isEmpty() && host.ran.none { it.startsWith("cat /proc/sys/kernel/random/uuid") }, "${host.ran}")
    }

    @Test
    fun aRemovedInboundLeavesTheFileThroughTheCheck() = runTest {
        val host = host()
        SshHostSession(host, device, linux = true).remove(singBoxInbound, listOf("1"))
        assertEquals(listOf("vless-in"), written(host).getValue("inbounds").jsonArray.map { it.jsonObject.at("tag") })
        assertTrue(host.ran.any { it.startsWith("umask 077; mkdir -p") && "sing-box check -c '/etc/sing-box/config.json'" in it }, "${host.ran}")
    }

    @Test
    fun theWizardOpensItsPortInUfwAndARemovedInboundClosesIt() = runTest {
        val host = host(xrayKeys, "for t in" to ok("sing-box\nxray\nsystemctl\nufw\n"), "ufw status" to ok("Status: active\n"), "for n in \$(ufw status numbered" to ok())
        val session = SshHostSession(host, device, linux = true)
        session.add(MenuPath.parse("proxy/xray/inbound"), emptyMap())
        assertTrue(host.ran.any { it.startsWith("for n in \$(ufw status numbered") && it.endsWith(" && ufw allow 443/tcp comment networkhelm-port-xray-vless-reality") }, "${host.ran}")
        session.remove(singBoxInbound, listOf("2"))
        assertTrue(host.ran.any { it.startsWith("for n in \$(ufw status numbered") && "networkhelm-port-sing-box-vless-in" in it && "ufw allow" !in it }, "${host.ran}")
    }

    @Test
    fun theServiceOfSingBoxNeitherStartsNorStartsAtBootNorSavesAFileWithTheExampleInbound() = runTest {
        val host = host(state("/etc/sing-box/config.json") to read("inactive disabled", "/etc/sing-box/config.json", "sing-box version 1.14.4", example))
        val session = SshHostSession(host, device, linux = true)
        val service = MenuPath.parse("proxy/sing-box")
        val refused = "the example inbound of sing-box on port 8080 has a published password: remove it or give it a password of its own"
        assertEquals(refused, assertFailsWith<DeviceException> { session.run(service, "up", emptyMap()).toList() }.message)
        assertEquals(refused, assertFailsWith<DeviceException> { session.set(service, null, mapOf("autostart" to "yes")) }.message)
        assertEquals(refused, assertFailsWith<DeviceException> { session.run(service, "save-config", mapOf("text" to example)).toList() }.message)
        assertTrue(host.given.isEmpty() && host.ran.none { it.startsWith("systemctl start") || it.startsWith("systemctl enable") }, "${host.ran}")
        session.set(service, null, mapOf("autostart" to "no"))
        assertTrue("systemctl disable sing-box" in host.ran, "${host.ran}")
    }

    @Test
    fun anInboundOfXrayTakesItsTransportTlsAndSniffingFromItsForm() = runTest {
        val host = host()
        val session = SshHostSession(host, device, linux = true)
        val row = session.rows("proxy/xray/inbound").single()
        assertEquals(listOf("proxy.example.net", "false", null), listOf(row["server-names"], row["sniffing"], row["transport-path"]))
        val changes = mapOf(
            "network" to "ws", "transport-path" to "/rd-ws", "transport-host" to "cdn.example.net", "alpn" to "h2, http/1.1", "certificate-path" to "/etc/ssl/proxy.pem",
            "key-path" to "/etc/ssl/proxy.key", "sniffing" to "true",
        )
        session.set(MenuPath.parse("proxy/xray/inbound"), "1", changes)
        val trojan = written(host).getValue("inbounds").jsonArray.single().jsonObject
        val stream = trojan.getValue("streamSettings").jsonObject
        assertEquals(
            listOf("ws", "tls", "/rd-ws", "cdn.example.net", "proxy.example.net"),
            listOf(stream.at("network"), stream.at("security"), stream.at("wsSettings", "path"), stream.at("wsSettings", "host"), stream.at("tlsSettings", "serverName")),
        )
        val tls = stream.getValue("tlsSettings").jsonObject
        assertEquals(listOf("h2", "http/1.1"), tls.getValue("alpn").jsonArray.map { it.jsonPrimitive.content })
        val held = tls.getValue("certificates").jsonArray.single().jsonObject
        assertEquals(listOf("/etc/ssl/proxy.pem", "/etc/ssl/proxy.key"), listOf(held.at("certificateFile"), held.at("keyFile")))
        assertEquals("true", trojan.at("sniffing", "enabled"))
        assertEquals(listOf("http", "tls", "quic"), trojan.getValue("sniffing").jsonObject.getValue("destOverride").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("rd-test-trojan", trojan.getValue("settings").jsonObject.getValue("clients").jsonArray.single().jsonObject.at("password"))
        assertTrue(host.ran.any { it.startsWith("umask 077; mkdir -p") && "xray run -test -c '/usr/local/etc/xray/config.json'" in it }, "${host.ran}")
    }

    @Test
    fun realityOfSingBoxTakesAnotherSiteShortIdsAndAPrivateKeyTheProgramMakes() = runTest {
        val host = host("sing-box generate reality-keypair" to ok("PrivateKey: ${"B".repeat(43)}\nPublicKey: ${"C".repeat(43)}\n"))
        val session = SshHostSession(host, device, linux = true)
        val row = session.rows("proxy/sing-box/inbound")[1]
        assertEquals(listOf("www.example.com", "www.example.com:443", "0123abcd"), listOf(row["server-names"], row["dest"], row["short-ids"]))
        assertTrue(row.values.values.none { "AAAAAAAA" in it }, "$row")
        session.set(singBoxInbound, "2", mapOf("dest" to "dl.google.com:443", "server-names" to "dl.google.com", "short-ids" to "0123abcd, 89ef", "private-key" to ""))
        val tls = written(host).getValue("inbounds").jsonArray[1].jsonObject.getValue("tls").jsonObject
        assertEquals(
            listOf("dl.google.com", "dl.google.com", "443", "B".repeat(43)),
            listOf(tls.at("server_name"), tls.at("reality", "handshake", "server"), tls.at("reality", "handshake", "server_port"), tls.at("reality", "private_key")),
        )
        assertEquals(listOf("0123abcd", "89ef"), tls.getValue("reality").jsonObject.getValue("short_id").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(host.sent.none { "BBBBBBBB" in it }, "the key goes to the host as input alone: ${host.sent}")
        val many = assertFailsWith<DeviceException> { session.set(singBoxInbound, "2", mapOf("server-names" to "a.example.com,b.example.com")) }
        assertEquals("server-names: a.example.com,b.example.com is not a value it takes", many.message)
    }

    @Test
    fun salamanderOfHysteria2GoesAndComesBackWithAPasswordOfItsOwn() = runTest {
        val salamander = """ "obfs": { "type": "salamander", "password": "rd-test-obfs" },"""
        fun hysteria(obfs: String) = """
            { "inbounds": [ {
              "type": "hysteria2", "tag": "hysteria2", "listen": "::", "listen_port": 8443, "users": [ { "name": "alice", "password": "rd-test-hy2" } ],$obfs
              "tls": { "enabled": true, "server_name": "dl.google.com", "alpn": [ "h3" ], "certificate": [ "rd-test-certificate" ], "key": [ "rd-test-key" ] }
            } ] }
        """.trimIndent()
        fun config(obfs: String) = state("/etc/sing-box/config.json") to read("active enabled", "/etc/sing-box/config.json", "sing-box version 1.14.4", hysteria(obfs))
        val off = host(config(salamander))
        val session = SshHostSession(off, device, linux = true)
        assertEquals("true", session.rows("proxy/sing-box/inbound").single()["obfs"])
        session.set(singBoxInbound, "1", mapOf("obfs" to "false", "obfs-password" to "rd-test-ignored", "up-mbps" to "100", "down-mbps" to "200"))
        val plain = written(off).getValue("inbounds").jsonArray.single().jsonObject
        assertTrue("obfs" !in plain, plain.toString())
        assertEquals(listOf("100", "200"), listOf(plain.at("up_mbps"), plain.at("down_mbps")))
        val on = host(config(""), "head -c 18 /dev/urandom | base64" to ok("${"D".repeat(24)}\n"))
        SshHostSession(on, device, linux = true).set(singBoxInbound, "1", mapOf("obfs" to "true"))
        val obfs = written(on).getValue("inbounds").jsonArray.single().jsonObject
        assertEquals(listOf("salamander", "D".repeat(24)), listOf(obfs.at("obfs", "type"), obfs.at("obfs", "password")))
        assertTrue(on.sent.none { "DDDDDDDD" in it }, "${on.sent}")
    }

    @Test
    fun anInboundTurnedToRealityGetsAKeyAShortIdAndTheSiteOfTheWizard() = runTest {
        val vless = """
            { "inbounds": [ {
              "tag": "vless-ws-tls", "protocol": "vless", "listen": "0.0.0.0", "port": 443,
              "settings": { "clients": [ { "id": "11111111-2222-3333-4444-555555555555", "email": "user1" } ], "decryption": "none" },
              "streamSettings": { "network": "ws", "security": "tls", "wsSettings": { "path": "/rd-test" },
                "tlsSettings": { "serverName": "proxy.example.net", "certificates": [ { "certificateFile": "/etc/ssl/proxy.pem", "keyFile": "/etc/ssl/proxy.key" } ] } }
            } ] }
        """.trimIndent()
        val host = host(
            state("/usr/local/etc/xray/config.json") to read("active enabled", "/usr/local/etc/xray/config.json", "Xray 26.11.9", vless),
            "xray x25519" to ok("PrivateKey: ${"D".repeat(43)}\nPassword (PublicKey): ${"E".repeat(43)}\n"), "head -c 8 /dev/urandom" to ok("fedcba9876543210\n"),
        )
        SshHostSession(host, device, linux = true).set(MenuPath.parse("proxy/xray/inbound"), "1", mapOf("network" to "tcp", "security" to "reality"))
        val stream = written(host).getValue("inbounds").jsonArray.single().jsonObject.getValue("streamSettings").jsonObject
        assertEquals(
            listOf("tcp", "reality", "dl.google.com:443", "D".repeat(43)),
            listOf(stream.at("network"), stream.at("security"), stream.at("realitySettings", "dest"), stream.at("realitySettings", "privateKey")),
        )
        val reality = stream.getValue("realitySettings").jsonObject
        assertEquals(listOf("dl.google.com"), reality.getValue("serverNames").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("fedcba9876543210"), reality.getValue("shortIds").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun aNewCipherOfShadowsocks2022GetsNewKeysWhereTheOldOnesDoNotFitIt() = runTest {
        val fits = "${"D".repeat(42)}A="
        val shadowsocks = """
            { "inbounds": [ {
              "tag": "shadowsocks-2022", "protocol": "shadowsocks", "listen": "0.0.0.0", "port": 8388,
              "settings": { "method": "2022-blake3-aes-128-gcm", "password": "${"B".repeat(21)}A==", "network": "tcp,udp",
                "clients": [ { "password": "${"C".repeat(21)}A==", "email": "user1" }, { "password": "$fits", "email": "user2" } ] }
            } ] }
        """.trimIndent()
        val made = "${"E".repeat(42)}A="
        val host = host(
            state("/usr/local/etc/xray/config.json") to read("active enabled", "/usr/local/etc/xray/config.json", "Xray 26.11.9", shadowsocks),
            "head -c 32 /dev/urandom | base64" to ok("$made\n"),
        )
        SshHostSession(host, device, linux = true).set(MenuPath.parse("proxy/xray/inbound"), "1", mapOf("method" to "2022-blake3-aes-256-gcm"))
        val settings = written(host).getValue("inbounds").jsonArray.single().jsonObject.getValue("settings").jsonObject
        assertEquals(listOf("2022-blake3-aes-256-gcm", made), listOf(settings.at("method"), settings.at("password")))
        assertEquals(listOf(made, fits), settings.getValue("clients").jsonArray.map { it.jsonObject.at("password") })
        assertTrue(host.sent.none { "EEEEEEEE" in it }, "${host.sent}")
    }

    @Test
    fun anEditRefusesATakenTagOrPortAndValuesItCannotTakeWithoutShowingASecret() = runTest {
        val host = host("ss -H -tuln" to ok("tcp   LISTEN 0      4096         0.0.0.0:3128       0.0.0.0:*\n"))
        val session = SshHostSession(host, device, linux = true)
        val refusals = listOf(
            mapOf("tag" to "bad tag") to "tag: bad tag is not a value it takes",
            mapOf("tag" to "vless-in") to "sing-box has an inbound vless-in already",
            mapOf("port" to "443") to "sing-box has an inbound on port 443 already",
            mapOf("port" to "3128") to "port 3128 is taken on the host",
            mapOf("transport-path" to "rd-test") to "transport-path: rd-test is not a value it takes",
            mapOf("password" to "rd-test\nsecret") to "the password may hold no line break",
        )
        for ((values, message) in refusals) assertEquals(message, assertFailsWith<DeviceException> { session.set(singBoxInbound, "1", values) }.message)
        assertEquals("the private key of Reality is no 32 bytes of base64url", assertFailsWith<DeviceException> { session.set(singBoxInbound, "2", mapOf("private-key" to "rd-test-key")) }.message)
        assertEquals("sing-box has no inbound 3", assertFailsWith<DeviceException> { session.set(singBoxInbound, "3", mapOf("tag" to "spare")) }.message)
        assertTrue(host.given.isEmpty(), "${host.given}")
    }

    @Test
    fun aNewPortOrTagOfAnInboundMovesItsRuleInUfw() = runTest {
        val host = host("for t in" to ok("sing-box\nxray\nsystemctl\nufw\n"), "ufw status" to ok("Status: active\n"), "for n in \$(ufw status numbered" to ok())
        val session = SshHostSession(host, device, linux = true)
        val inbound = MenuPath.parse("proxy/xray/inbound")
        session.set(inbound, "1", mapOf("port" to "9443"))
        assertTrue(host.ran.any { it.startsWith("for n in \$(ufw status numbered") && it.endsWith(" && ufw allow 9443/tcp comment networkhelm-port-xray-trojan-in") }, "${host.ran}")
        session.set(inbound, "1", mapOf("tag" to "trojan-tls"))
        assertTrue(host.ran.any { it.startsWith("for n in \$(ufw status numbered") && "networkhelm-port-xray-trojan-in" in it && "ufw allow" !in it }, "${host.ran}")
        assertTrue(host.ran.any { it.endsWith(" && ufw allow 8443/tcp comment networkhelm-port-xray-trojan-tls") }, "${host.ran}")
    }

    @Test
    fun aUserIsRenamedLosesItsFlowAndGetsANewUuidOfTheKernel() = runTest {
        val vless = """
            { "inbounds": [ {
              "type": "vless", "tag": "vless-in", "listen": "::", "listen_port": 443,
              "users": [ { "name": "alice", "uuid": "11111111-2222-3333-4444-555555555555", "flow": "xtls-rprx-vision" }, { "name": "bob", "uuid": "rd-test-bob" } ]
            } ] }
        """.trimIndent()
        val host = host(state("/etc/sing-box/config.json") to read("active enabled", "/etc/sing-box/config.json", "sing-box version 1.14.4", vless))
        val session = SshHostSession(host, device, linux = true)
        val users = MenuPath.parse("proxy/sing-box/user")
        assertEquals(listOf("xtls-rprx-vision", null), session.rows("proxy/sing-box/user").map { it["flow"] })
        assertEquals("sing-box has the user bob already", assertFailsWith<DeviceException> { session.set(users, "1/1", mapOf("name" to "bob")) }.message)
        session.set(users, "1/1", mapOf("name" to "alice", "secret" to "", "flow" to null))
        val saved = written(host).getValue("inbounds").jsonArray.single().jsonObject.getValue("users").jsonArray.map { user -> user.jsonObject.mapValues { it.value.jsonPrimitive.content } }
        assertEquals(listOf(mapOf("name" to "alice", "uuid" to "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), mapOf("name" to "bob", "uuid" to "rd-test-bob")), saved)
    }

    @Test
    fun aNewUserOfShadowsocks2022OfXrayGetsAKeyAsLongAsItsCipherWants() = runTest {
        val shadowsocks = """
            { "inbounds": [ {
              "tag": "shadowsocks-2022", "protocol": "shadowsocks", "port": 8388,
              "settings": { "method": "2022-blake3-aes-128-gcm", "password": "${"B".repeat(21)}A==", "network": "tcp,udp", "clients": [] }
            } ] }
        """.trimIndent()
        val key = "${"C".repeat(21)}A=="
        val host = host(
            state("/usr/local/etc/xray/config.json") to read("active enabled", "/usr/local/etc/xray/config.json", "Xray 26.11.9", shadowsocks),
            "head -c 16 /dev/urandom | base64" to ok("$key\n"),
        )
        assertEquals("1/1", SshHostSession(host, device, linux = true).add(MenuPath.parse("proxy/xray/user"), mapOf("inbound" to "1", "name" to "carol")))
        val client = written(host).getValue("inbounds").jsonArray.single().jsonObject.getValue("settings").jsonObject.getValue("clients").jsonArray.single().jsonObject
        assertEquals(listOf("carol", key), listOf(client.at("email"), client.at("password")))
    }
}
