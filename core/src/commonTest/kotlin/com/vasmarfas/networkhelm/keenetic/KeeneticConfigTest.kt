package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeeneticConfigTest {

    private fun help(hint: String = "", choices: List<String> = emptyList(), runs: Boolean = false, commands: Map<String, String> = emptyMap()) = Help(commands, choices, hint, runs)

    @Test
    fun aCommandBecomesAFieldByWhatHelpSaysOfIt() {
        assertEquals(Kind.Value, fieldOf(listOf("port"), help("port {port}"), "set SSH port")?.kind)
        val policy = fieldOf(listOf("lockout-policy"), help("lockout-policy {threshold} [{duration} [{observation-window}]]"), "set SSH bruteforce detection parameters")
        assertEquals(Kind.Values to listOf("threshold", "duration", "observation-window"), policy?.kind to policy?.arguments)
        val level = fieldOf(listOf("security-level"), help("security-level ((public [ssl]) | private | protected)", listOf("public", "private", "protected")), "set HTTP security level")
        assertEquals(Kind.Choice to listOf("public", "private", "protected"), level?.kind to level?.choices)
        assertEquals(true, level?.fixed)
        assertNull(level?.change(mapOf("security-level" to "public"), emptyMap()))
        assertEquals(false, fieldOf(listOf("security-level"), help("security-level (public | private | protected)", listOf("public", "private", "protected")), "set SSH security level")?.fixed)
        assertEquals(Kind.Flag, fieldOf(listOf("master"), help(runs = true), "enable NTP server")?.kind)
        assertEquals(Kind.Value, fieldOf(listOf("address"), help("address {address} | dhcp ..."), "set an address")?.kind)
        assertNull(fieldOf(listOf("led"), help("led {led} indicate {control} | power ..."), "setup LED"))
        assertNull(fieldOf(listOf("keygen"), help(runs = true), "regenerate SSH keys"))
        assertNull(fieldOf(listOf("server"), help("server {server}"), "add a new server"))
        assertNull(fieldOf(listOf("session"), help(commands = mapOf("timeout" to "set SSH session timeout")), "configure SSH session properties"))
    }

    @Test
    fun aFieldReadsAndWritesTheFormOfShowRc() {
        val config = Json.parseToJsonElement("""{"master":true,"security-level":{"private":true},"lockout-policy":{"threshold":"5","duration":"15","observation-window":"3"},"session":{"timeout":"600"}}""")
        val master = Field(listOf("master"), Kind.Flag, emptyList(), emptyList(), "")
        val level = Field(listOf("security-level"), Kind.Choice, emptyList(), listOf("public", "private", "protected"), "")
        val policy = Field(listOf("lockout-policy"), Kind.Values, listOf("threshold", "duration", "observation-window"), emptyList(), "")
        val timeout = Field(listOf("session", "timeout"), Kind.Value, listOf("timeout"), emptyList(), "")
        val old = (master.values(config) + level.values(config) + policy.values(config) + timeout.values(config)).toMap()
        assertEquals(
            mapOf("master" to "true", "security-level" to "private", "lockout-policy.threshold" to "5", "lockout-policy.duration" to "15", "lockout-policy.observation-window" to "3", "session.timeout" to "600"),
            old,
        )
        assertEquals("""{"no":true}""", master.change(mapOf("master" to "no"), old).toString())
        assertEquals("""{"protected":true}""", level.change(mapOf("security-level" to "protected"), old).toString())
        assertEquals("""{"threshold":"6","duration":"15","observation-window":"3"}""", policy.change(mapOf("lockout-policy.threshold" to "6"), old).toString())
        assertEquals("""{"no":true}""", timeout.change(mapOf("session.timeout" to ""), old).toString())
        assertNull(policy.change(mapOf("master" to "yes"), old))
    }

    @Test
    fun theNameOfAnInterfaceKeepsItsSlashInThePathOfItsWindow() {
        val path = configPath(listOf("interface", "FastEthernet0/Vlan2"))
        assertEquals("rc/interface/FastEthernet0~Vlan2", path)
        assertEquals(listOf("interface", "FastEthernet0/Vlan2"), configContext(MenuPath.parse(path)))
    }

    // help of a router with two contexts of its own, one under ip and one of the cloud, and an interface
    private val tree = mapOf(
        "" to """{"command":{"ip":{"description":"configure IP"},"cloud":{"description":"cloud"},"ntp":{"description":"configure NTP"},"interface":{},"show":{}}}""",
        "ip" to """{"command":{"ssh":{"description":"manage SSH server service"}}}""",
        "cloud" to """{"command":{"control2":{"description":"configure cloud control2 service"}}}""",
        "ntp" to """{"command":{"server":{"description":"set an NTP server"}}}""",
        "ip ssh" to """{"command":{"port":{"description":"set port"}}}""",
        "cloud control2" to """{"command":{"enable":{"description":"enable"}}}""",
        "ntp server" to """{"hint":"server {server}","cr":{}}""",
        "ip ssh port" to """{"hint":"port {port}"}""",
        "cloud control2 enable" to """{"cr":{}}""",
        "interface Bridge0" to """{"command":{"description":{"description":"set the description"},"reset":{"description":"reset the counters"}}}""",
        "interface Bridge0 description" to """{"hint":"description {description}"}""",
        "interface Bridge0 reset" to """{"cr":{}}""",
    )

    private val router = object : KeeneticCall {
        override suspend fun show(path: String): JsonElement = throw DeviceException(ErrorCategory.MissingItem, path)

        override suspend fun post(commands: List<JsonObject>, raising: Boolean) = commands.map { command ->
            val line = (command["help"] as? JsonPrimitive)?.contentOrNull?.trim()
            if (line != null) {
                Json.parseToJsonElement("""{"help":${tree[line] ?: "{}"}}""")
            } else {
                Json.parseToJsonElement("""{"show":{"rc":{"interface":{"Bridge0":{"description":"Home network"}}}}}""")
            }
        }

        override suspend fun realm() = "rd-test"

        override suspend fun rest(method: String, path: String, body: JsonElement?): JsonElement = throw DeviceException(ErrorCategory.MissingItem, path)
    }

    @Test
    fun allSettingsGroupsTheContextsAsTheWebInterfaceDoesAndLeavesTheCloudOut() = runTest {
        val device = DeviceInfo(Platform.Keenetic, "rd-test", "5.1.6", "mips", "Keenetic Launcher", emptySet(), setOf("read", "write"))
        val packs = KeeneticSession(router, device).packs(null)
        assertEquals(
            mapOf("IP" to listOf("ip ssh" to "rc/ip/ssh"), "Interfaces" to listOf("Bridge0 · Home network" to "rc/interface/Bridge0"), "System" to listOf("ntp" to "rc/ntp")),
            packs.associate { pack -> pack.menu.last() to pack.window.tabs.map { it.title to it.path } },
        )
        assertEquals(setOf("All Settings"), packs.map { it.menu.first() }.toSet())
    }
}
