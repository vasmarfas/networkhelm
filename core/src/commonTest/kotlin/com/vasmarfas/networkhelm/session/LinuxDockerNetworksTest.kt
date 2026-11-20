package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinuxDockerNetworksTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    // the session came to 192.168.88.2 on eth0; root is the login, and the guard keeps its folder and answers after the change
    private fun host(vararg answers: Pair<String, ByteArray>, tool: String = "docker") = DockerHost(
        listOf(
            "$tool system dial-stdio </dev/null" to ok(),
            "date +%z" to ok("+0300\n"),
            "echo \"\$SSH_CONNECTION\"" to ok(SESSION),
            "ls /sys/class/net" to ok("eth0\nlo\nwlan0\n"),
            "id -u" to ok("0\n"),
            "B=" to ok("4242 /tmp/networkhelm-docker.k3Jd9a\n"),
            "true" to ok(),
        ),
    ) { DockerEngine(listOf("GET /version " to engineAnswer(VERSION)) + answers) }

    private fun networks(host: DockerHost, tool: String = "docker") =
        DockerNetworks(host, DockerApi(host, Root(host, null)) { setOf(tool) }, LinuxGuard(host, Root(host, null), null)).table { true }

    private fun body(host: DockerHost, line: String) =
        Json.parseToJsonElement(host.relays.flatMap { it.second.requests }.last { it.startsWith("$line ") }.substringAfter("\r\n\r\n")).jsonObject

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun aNetworkComesWithTheContainersOnItStoppedOnesToo() = runTest {
        val rows = networks(host("GET /v1.56/networks " to engineAnswer(NETWORKS_29), "GET /v1.56/containers/json?all=1 " to engineChunked(LIST_29))).read()
        assertEquals(listOf("none", "host", "bridge", "nh-sample-net"), rows.map { it["name"] })
        assertEquals(listOf("0", "0", "1", "1"), rows.map { it["containers"] })
        assertEquals(
            listOf("bridge", "172.30.9.0/24", "172.30.9.1", "false", "false", "2026-11-17 20:20:59"),
            listOf("driver", "subnet", "gateway", "internal", "ipv6", "created").map { rows[3][it] },
        )
        assertTrue("com.docker.network.bridge.name=docker0" in rows[2]["options"].orEmpty().lines())
        // Podman lists its default network as bridge, and its containers by the ID of the network
        val podman = networks(host("GET /v1.56/networks " to engineAnswer(NETWORKS_PODMAN_5), "GET /v1.56/containers/json?all=1 " to engineAnswer(LIST_PODMAN_5), tool = "podman"), "podman")
        val bridge = podman.read().single()
        assertEquals(listOf("bridge", "10.88.0.0/16", "10.88.0.1", "3"), listOf("name", "subnet", "gateway", "containers").map { bridge[it] })
        val members = assertNotNull(networks(host("GET /v1.56/networks/nh-sample-net " to engineAnswer(NETWORK_SAMPLE_29), "GET /v1.56/containers/json?all=1 " to engineChunked(LIST_29))).run)
        val web = members("containers", mapOf("id" to "nh-sample-net")).single()
        assertEquals(listOf("nh-sample-web", "running", "172.30.9.2/24", "1e:19:12:be:79:c6"), listOf("name", "state", "ipv4-address", "mac-address").map { web[it] })
    }

    @Test
    fun aBridgeNetworkIsMadeAsTheLabMadeItAndOutOfTheGuard() = runTest {
        val host = host("POST /v1.56/networks/create " to engineAnswer(NETWORK_CREATED_29, "201 Created"))
        val id = assertNotNull(networks(host).add)(mapOf("name" to "nh-c3-net", "driver" to "bridge", "subnet" to "172.31.77.0/24", "gateway" to "172.31.77.1", "labels" to "nh=c3"))
        assertEquals("8d62c77b50761c77d609be325f8c4bec6e2d4826e3f62fdb7568212444c2cdc4", id)
        assertEquals(json(NETWORK_REQUEST), body(host, "POST /v1.56/networks/create"))
        assertTrue(host.ran.none { it.startsWith("B=") || it.startsWith("echo") })
    }

    @Test
    fun aMacvlanNetworkOnTheInterfaceOfTheSessionIsMadeUnderTheGuard() = runTest {
        val values = mapOf(
            "name" to "nh-mv", "driver" to "macvlan", "parent" to "eth0", "mode" to "bridge", "subnet" to "192.168.88.0/24", "gateway" to "192.168.88.1",
            "ip-range" to "192.168.88.192/27",
        )
        val guarded = host("GET /v1.56/info " to engineAnswer(INFO), "GET /v1.56/networks " to engineAnswer(NETWORKS_29), "POST /v1.56/networks/create " to engineAnswer(NETWORK_CREATED_29, "201 Created"))
        assertNotNull(networks(guarded).add)(values)
        // the guard removes the network by itself a minute later unless the session still answers
        assertTrue("docker network rm '\\''nh-mv'\\''" in guarded.ran.single { it.startsWith("B=") })
        assertTrue(guarded.ran.any { it.startsWith("kill 4242") })
        val made = body(guarded, "POST /v1.56/networks/create")
        assertEquals(json("""{"parent":"eth0","macvlan_mode":"bridge"}"""), made["Options"]?.jsonObject)
        assertEquals(json("""{"Config":[{"Subnet":"192.168.88.0/24","Gateway":"192.168.88.1","IPRange":"192.168.88.192/27"}]}"""), made["IPAM"]?.jsonObject)

        // a name the engine has already would have the guard take the old network away
        val taken = host("GET /v1.56/info " to engineAnswer(INFO), "GET /v1.56/networks " to engineAnswer(NETWORKS_29))
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { assertNotNull(networks(taken).add)(values + ("name" to "nh-sample-net")) }.category)
        assertTrue(taken.ran.none { it.startsWith("B=") })

        // a rootless engine reaches no interface of the host, nor does a network on another one; Podman calls the mode mode
        val rootless = host("GET /v1.56/info " to engineAnswer(INFO.replace("\"name=cgroupns\"", "\"name=rootless\"")), "POST /v1.56/networks/create " to engineAnswer(NETWORK_CREATED_29, "201 Created"))
        assertNotNull(networks(rootless).add)(values)
        val podman = host("POST /v1.56/networks/create " to engineAnswer(NETWORK_CREATED_29, "201 Created"), tool = "podman")
        assertNotNull(networks(podman, "podman").add)(values + ("parent" to "wlan0"))
        assertTrue((rootless.ran + podman.ran).none { it.startsWith("B=") })
        assertEquals(json("""{"parent":"wlan0","mode":"bridge"}"""), body(podman, "POST /v1.56/networks/create")["Options"]?.jsonObject)
    }

    @Test
    fun aContainerJoinsANetworkWithAnAliasAndAnAddressAndLeavesIt() = runTest {
        val podmanSaysOk = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=us-ascii\r\nContent-Length: 3\r\n\r\nOK\n".encodeToByteArray()
        val host = host(
            "GET /v1.56/networks/nh-c3-net " to engineAnswer(NETWORK_C3_29),
            "POST /v1.56/networks/nh-c3-net/connect " to podmanSaysOk,
            "POST /v1.56/networks/nh-c3-net/disconnect " to podmanSaysOk,
        )
        val run = assertNotNull(networks(host).run)
        run("connect", mapOf("id" to "nh-c3-net", "container" to "nh-c3b", "aliases" to "second", "ipv4-address" to "172.31.77.50"))
        assertEquals(json(CONNECT_REQUEST), body(host, "POST /v1.56/networks/nh-c3-net/connect"))
        run("disconnect", mapOf("id" to "nh-c3-net", "container" to "nh-c3b"))
        assertEquals(json(DISCONNECT_REQUEST), body(host, "POST /v1.56/networks/nh-c3-net/disconnect"))
        assertTrue(host.ran.none { it.startsWith("B=") })
    }

    @Test
    fun theDefaultBridgeOfDockerTakesNoAliasAndTheAppSaysSoBeforeTheEngineRefusesTheEndpoint() = runTest {
        val bridge = Json.parseToJsonElement(NETWORKS_29).jsonArray.single { it.jsonObject["Name"]?.jsonPrimitive?.content == "bridge" }.toString()
        val host = host("GET /v1.56/networks/bridge " to engineAnswer(bridge), "POST /v1.56/networks/bridge/connect " to engineAnswer("", "200 OK"))
        val run = assertNotNull(networks(host).run)
        val refused = assertFailsWith<DeviceException> { run("connect", mapOf("id" to "bridge", "container" to "nh-c3b", "aliases" to "second")) }
        assertEquals(ErrorCategory.ArgumentValue to "the default bridge of Docker takes no aliases: they work on networks of the user", refused.category to refused.message)
        assertTrue(host.relays.flatMap { it.second.requests }.none { it.startsWith("POST ") })
        run("connect", mapOf("id" to "bridge", "container" to "nh-c3b"))
        assertTrue(host.relays.flatMap { it.second.requests }.any { it.startsWith("POST /v1.56/networks/bridge/connect ") })
    }

    @Test
    fun aContainerJoinsAMacvlanNetworkOfTheSessionUnderTheGuardAndOnlyOnce() = runTest {
        fun macvlan(network: String) = network.replace(""""Driver":"bridge"""", """"Driver":"macvlan"""").replace(""""Options":{}""", """"Options":{"parent":"eth0","macvlan_mode":"bridge"}""")
        val host = host(
            "GET /v1.56/networks/nh-c3-net " to engineAnswer(macvlan(NETWORK_C3_29)),
            "GET /v1.56/networks/nh-sample-net " to engineAnswer(macvlan(NETWORK_SAMPLE_29)),
            "GET /v1.56/info " to engineAnswer(INFO),
            "GET /v1.56/containers/json?all=1 " to engineChunked(LIST_29),
            "POST /v1.56/networks/nh-c3-net/connect " to engineAnswer("", "200 OK"),
        )
        val run = assertNotNull(networks(host).run)
        run("connect", mapOf("id" to "nh-c3-net", "container" to "nh-c3b", "ipv4-address" to "192.168.88.50"))
        assertTrue("docker network disconnect -f '\\''nh-c3-net'\\'' '\\''nh-c3b'\\''" in host.ran.single { it.startsWith("B=") })
        // the guard would take a container off a network it was on before the change
        val twice = assertFailsWith<DeviceException> { run("connect", mapOf("id" to "nh-sample-net", "container" to "nh-sample-web")) }
        assertEquals("nh-sample-web is on nh-sample-net already", twice.message)
    }

    @Test
    fun pruneTakesTheNetworksOfTheUserNoContainerIsOnAndOneGoneIsNoFailureOfItsRemoval() = runTest {
        val host = host(
            "GET /v1.56/networks " to engineAnswer(NETWORKS_29.dropLast(1) + "," + NETWORK_C3_29 + "]"),
            "GET /v1.56/containers/json?all=1 " to engineChunked(LIST_29),
            "DELETE /v1.56/networks/" to engineAnswer("""{"message":"network 8d62c77b50761c77d609be325f8c4bec6e2d4826e3f62fdb7568212444c2cdc4 not found"}""", "404 Not Found"),
        )
        val table = networks(host)
        assertEquals(listOf("nh-c3-net"), assertNotNull(table.run)("unused", emptyMap()).map { it["name"] })
        assertNotNull(table.remove)(listOf("8d62c77b50761c77d609be325f8c4bec6e2d4826e3f62fdb7568212444c2cdc4"))
        assertEquals(listOf("eth0", "wlan0"), assertNotNull(table.arguments().singleOrNull { it.name == "parent" }).choices)
    }

    private companion object {
        const val VERSION = """{"ApiVersion":"1.56","MinAPIVersion":"1.40"}"""
        const val SESSION = "192.168.88.10 51234 192.168.88.2 22\n" +
            "1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever preferred_lft forever\n" +
            "2: eth0    inet 192.168.88.2/24 brd 192.168.88.255 scope global eth0\\       valid_lft forever preferred_lft forever\n"

        // recorded on Docker 29.9.3 (API 1.56) on Arch and on Podman 5.8.9 (compatible API 1.44) on Fedora 44
        const val INFO = """{"SecurityOptions":["name=seccomp,profile=builtin","name=cgroupns"]}"""
        const val NETWORKS_29 = """[{"Name":"none","Id":"26c12cdddf1b5bcc91157a40eb16b4552a26f8acd2b6779d37e24cd1f1c73838","Created":"2026-11-17T17:20:32.038504606Z","Scope":"local",""" +
            """"Driver":"null","EnableIPv4":true,"EnableIPv6":false,"IPAM":{"Driver":"default","Options":null,"Config":null},"Internal":false,"Attachable":false,""" +
            """"Ingress":false,"ConfigFrom":{"Network":""},"ConfigOnly":false,"Options":{},"Labels":{}},{"Name":"host","Id":"2eee340a12346c6bc2a3010219a68a1c96f5ec2069ecfdfee6307912d7912143",""" +
            """"Created":"2026-11-17T17:20:32.101187461Z","Scope":"local","Driver":"host","EnableIPv4":true,"EnableIPv6":false,"IPAM":{"Driver":"default","Options":null,""" +
            """"Config":null},"Internal":false,"Attachable":false,"Ingress":false,"ConfigFrom":{"Network":""},"ConfigOnly":false,"Options":{},"Labels":{}},""" +
            """{"Name":"bridge","Id":"64d3a304e07732e9934cf31e994e28051b8311bd827019c939a65f58cf9f6196","Created":"2026-11-17T17:20:32.158578539Z","Scope":"local",""" +
            """"Driver":"bridge","EnableIPv4":true,"EnableIPv6":false,"IPAM":{"Driver":"default","Options":null,"Config":[{"Subnet":"172.17.0.0/16","Gateway":"172.17.0.1"}]},""" +
            """"Internal":false,"Attachable":false,"Ingress":false,"ConfigFrom":{"Network":""},"ConfigOnly":false,"Options":{"com.docker.network.bridge.default_bridge":"true",""" +
            """"com.docker.network.bridge.enable_icc":"true","com.docker.network.bridge.enable_ip_masquerade":"true","com.docker.network.bridge.host_binding_ipv4":"0.0.0.0",""" +
            """"com.docker.network.bridge.name":"docker0","com.docker.network.driver.mtu":"1500"},"Labels":{}},{"Name":"nh-sample-net","Id":"2ad1893d6f4bcfd3e5ad97f97591af44ddc5c6fa845e7f3e9fcba8879d1fe978",""" +
            """"Created":"2026-11-17T17:20:59.405078819Z","Scope":"local","Driver":"bridge","EnableIPv4":true,"EnableIPv6":false,"IPAM":{"Driver":"default",""" +
            """"Options":{},"Config":[{"Subnet":"172.30.9.0/24","Gateway":"172.30.9.1"}]},"Internal":false,"Attachable":false,"Ingress":false,"ConfigFrom":{"Network":""},""" +
            """"ConfigOnly":false,"Options":{},"Labels":{}}]"""
        const val NETWORKS_PODMAN_5 = """[{"Name":"bridge","Id":"2f259bab93aaaaa2542ba43ef33eb990d0999ee1b9924b557b7be53c0b7a1bb9","Created":"2026-11-17T17:31:14.423410576Z","Scope":"local",""" +
            """"Driver":"bridge","EnableIPv4":false,"EnableIPv6":false,"IPAM":{"Driver":"default","Options":{"driver":"host-local"},"Config":[{"Subnet":"10.88.0.0/16",""" +
            """"Gateway":"10.88.0.1"}]},"Internal":false,"Attachable":false,"Ingress":false,"ConfigFrom":{"Network":""},"ConfigOnly":false,"Containers":{"909aa0962d0d3ef68656655b582ec3dbef14ad7986f98ad6262c66ca44dfcc72":{"Name":"nh-sample-web",""" +
            """"EndpointID":"","MacAddress":"8e:cb:73:74:69:57","IPv4Address":"10.88.0.2/16","IPv6Address":""},"e80c6352d5d68ebbdcd9892063ce609141c9c56a2d5b94d5fb1c93a366f15eca":{"Name":"88edab2681f2-infra",""" +
            """"EndpointID":"","MacAddress":"7a:5e:72:bf:7e:48","IPv4Address":"10.88.0.3/16","IPv6Address":""}},"Options":{},"Labels":{}}]"""
        const val NETWORK_SAMPLE_29 = """{"Name":"nh-sample-net","Id":"2ad1893d6f4bcfd3e5ad97f97591af44ddc5c6fa845e7f3e9fcba8879d1fe978","Created":"2026-11-17T17:20:59.405078819Z","Scope":"local",""" +
            """"Driver":"bridge","EnableIPv4":true,"EnableIPv6":false,"IPAM":{"Driver":"default","Options":{},"Config":[{"Subnet":"172.30.9.0/24","Gateway":"172.30.9.1"}]},""" +
            """"Internal":false,"Attachable":false,"Ingress":false,"ConfigFrom":{"Network":""},"ConfigOnly":false,"Options":{},"Labels":{}}"""
        const val NETWORK_REQUEST = """{"Name":"nh-c3-net","Driver":"bridge","IPAM":{"Config":[{"Subnet":"172.31.77.0/24","Gateway":"172.31.77.1"}]},"Labels":{"nh":"c3"}}"""
        const val NETWORK_CREATED_29 = """{"Id":"8d62c77b50761c77d609be325f8c4bec6e2d4826e3f62fdb7568212444c2cdc4","Warning":""}"""
        const val NETWORK_C3_29 = """{"Name":"nh-c3-net","Id":"8d62c77b50761c77d609be325f8c4bec6e2d4826e3f62fdb7568212444c2cdc4","Created":"2026-11-17T18:00:02.079950679Z","Scope":"local",""" +
            """"Driver":"bridge","EnableIPv4":true,"EnableIPv6":false,"IPAM":{"Driver":"default","Options":null,"Config":[{"Subnet":"172.31.77.0/24","Gateway":"172.31.77.1"}]},""" +
            """"Internal":false,"Attachable":false,"Ingress":false,"ConfigFrom":{"Network":""},"ConfigOnly":false,"Options":{},"Labels":{"nh":"c3"},"Containers":{},""" +
            """"Status":{"IPAM":{"Subnets":{"172.31.77.0/24":{"IPsInUse":3,"DynamicIPsAvailable":253}}}}}"""
        const val CONNECT_REQUEST = """{"Container":"nh-c3b","EndpointConfig":{"Aliases":["second"],"IPAMConfig":{"IPv4Address":"172.31.77.50"}}}"""
        const val DISCONNECT_REQUEST = """{"Container":"nh-c3b","Force":false}"""
    }
}
