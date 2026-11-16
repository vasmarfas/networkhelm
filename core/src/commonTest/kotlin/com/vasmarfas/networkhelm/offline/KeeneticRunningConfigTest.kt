package com.vasmarfas.networkhelm.offline

import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KeeneticRunningConfigTest {

    // the running-config of a Keenetic Launcher with KeeneticOS 5.1.8, shortened, names, addresses and hashes replaced
    private val config = """
        ! ${'$'}${'$'}${'$'} Model: Keenetic Launcher
        ! ${'$'}${'$'}${'$'} Agent: http/rci

        system
            set net.ipv4.ip_forward 1
            domainname WORKGROUP
            hostname rd-test-keenetic
        !
        known host "rd-test pc - Home network" 02:00:5e:10:00:01
        access-list _WEBADMIN_PPPoE0
            permit tcp 0.0.0.0 0.0.0.0 0.0.0.0 0.0.0.0
            auto-delete
        !
        isolate-private
        user admin
            password nt 0123456789abcdef0123456789abcdef
            password md5 fedcba9876543210fedcba9876543210
            tag cli
            tag http
        !
        dyndns profile _WEBADMIN
        !
        interface Bridge0
            rename Home
            description "Home network"
            include FastEthernet0/Vlan1
            include WifiMaster0/AccessPoint0
            security-level private
            ip address 192.168.1.1 255.255.255.0
            ip dhcp client dns-routes
            up
        !
        ip dhcp pool _WEBADMIN
            range 192.168.1.33 192.168.1.152
            bind Bridge0
            enable
        !
        ip nat Bridge0
        ip ssh
            security-level private
            lockout-policy 5 15 3
        !
        service dhcp
        service ssh
        cloud control2 security-level public
        !
    """.trimIndent()

    @Test
    fun theBlocksOfTheRunningConfigAreRowsOfTheirContextsAndTheirLinesValues() {
        val parsed = keeneticConfig(config)
        assertEquals(mapOf("model" to "Keenetic Launcher", "agent" to "http/rci"), parsed.facts)
        val menus = parsed.menus.mapKeys { it.key.toString() }
        assertEquals(
            listOf("system", "known", "access-list", "isolate-private", "user", "dyndns/profile", "interface", "ip/dhcp/pool", "ip", "ip/ssh", "service", "cloud"),
            menus.keys.toList(),
        )
        assertEquals(listOf(NodeKind.Settings, NodeKind.List, NodeKind.Settings), listOf("system", "interface", "ip/ssh").map { menus.getValue(it).kind })
        assertEquals(mapOf("set net.ipv4.ip_forward" to "1", "domainname" to "WORKGROUP", "hostname" to "rd-test-keenetic"), menus.getValue("system").items.single().values)
        assertEquals(
            mapOf(
                "name" to "Bridge0", "rename" to "Home", "description" to "Home network", "include" to "FastEthernet0/Vlan1, WifiMaster0/AccessPoint0",
                "security-level" to "private", "ip address" to "192.168.1.1 255.255.255.0", "ip dhcp client" to "dns-routes", "up" to "true",
            ),
            menus.getValue("interface").items.single().values,
        )
        assertEquals(mapOf("name" to "admin", "password" to "nt 0123456789abcdef0123456789abcdef, md5 fedcba9876543210fedcba9876543210", "tag" to "cli, http"), menus.getValue("user").items.single().values)
        assertEquals(mapOf("name" to "_WEBADMIN", "range" to "192.168.1.33 192.168.1.152", "bind" to "Bridge0", "enable" to "true"), menus.getValue("ip/dhcp/pool").items.single().values)
        assertEquals(mapOf("security-level" to "private", "lockout-policy" to "5 15 3"), menus.getValue("ip/ssh").items.single().values)
        assertEquals(listOf("_WEBADMIN"), menus.getValue("dyndns/profile").items.map { it.id })
        assertEquals(listOf("host rd-test pc - Home network 02:00:5e:10:00:01"), menus.getValue("known").items.map { it["name"] })
        assertEquals(listOf("dhcp", "ssh"), menus.getValue("service").items.map { it["name"] })
        assertEquals(listOf("control2 security-level public"), menus.getValue("cloud").items.map { it["name"] })
        assertEquals(mapOf("isolate-private" to "true"), menus.getValue("isolate-private").items.single().values)
    }

    @Test
    fun twoConfigurationsDifferInTheElementsAndValuesThatChanged() {
        val changed = config.replace("192.168.1.1 255.255.255.0", "192.168.2.1 255.255.255.0").replace("service ssh\n", "").replace("tag http", "tag readonly") +
            "\nuser rd_test\n    tag cli\n!"
        val differences = compare(configOf(Platform.Keenetic, config), configOf(Platform.Keenetic, changed)).associateBy { it.path.toString() }
        assertEquals(setOf("interface", "user", "service"), differences.keys)
        val (before, after) = differences.getValue("interface").changed.single()
        assertEquals("192.168.1.1 255.255.255.0" to "192.168.2.1 255.255.255.0", before["ip address"] to after["ip address"])
        assertEquals(listOf("cli, http" to "cli, readonly"), differences.getValue("user").changed.map { (old, new) -> old["tag"] to new["tag"] })
        assertEquals(listOf("rd_test"), differences.getValue("user").added.map { it["name"] })
        assertEquals(listOf("ssh"), differences.getValue("service").removed.map { it["name"] })
    }

    @Test
    fun aSavedConfigurationOpensWithoutTheRouter() = runTest {
        val session = assertNotNull(openConfig("startup-config.txt", config.encodeToByteArray()))
        assertEquals(Platform.Keenetic, session.device.platform)
        assertEquals(listOf("rd-test-keenetic", "Keenetic Launcher"), listOf(session.device.identity, session.device.board))
        assertEquals("192.168.1.1 255.255.255.0", session.get(MenuPath.parse("interface"), "Bridge0")?.get("ip address"))
        assertEquals(config, session.export(MenuPath.Root))
    }
}
