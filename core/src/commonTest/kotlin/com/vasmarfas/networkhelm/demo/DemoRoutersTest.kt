package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.RouterOsLayer
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.bundledMenu
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.packs.packName
import com.vasmarfas.networkhelm.routeros.RouterOsVersion
import com.vasmarfas.networkhelm.session.DeviceSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoRoutersTest {

    // no pack lists these: the topology view reads the first two itself, and the unified view of OpenWrt the devices
    private val readDirectly = setOf("cmr/layout/node", "cmr/layout/link", "status/devices")

    private fun router(host: String): DeviceSession = assertNotNull(demoRouter(host))

    private suspend fun rows(session: DeviceSession, path: String) = (session.watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    private fun flatten(entries: List<MenuEntry>): List<MenuEntry> = entries.flatMap { listOf(it) + flatten(it.items) }

    private fun packsOf(platform: Platform, session: DeviceSession): List<Pack> {
        val running = RouterOsVersion.parse(session.device.version).takeIf { platform == Platform.RouterOs }
        return bundledPacks().values.filter { it.platform == platform.packName && running?.within(it.since, it.until) != false }
    }

    private suspend fun leaves(session: DeviceSession, path: MenuPath = MenuPath.Root): List<MenuPath> {
        val schema = session.describe(path)
        val below = (schema.children + schema.commands - setOf("print", "add", "set", "remove", "move")).flatMap { name ->
            try {
                leaves(session, path.child(name))
            } catch (e: DeviceException) {
                emptyList()
            }
        }
        return (if (schema.kind == NodeKind.Directory) emptyList() else listOf(path)) + below
    }

    @Test
    fun everyPlatformHasItsRouterAndNothingElseDoes() {
        for ((platform, host) in demoHosts) assertEquals(platform, router(host).device.platform)
        assertEquals(6, demoHosts.size)
        assertNull(demoRouter("router.example.com"))
    }

    @Test
    fun theMenuOfEveryPlatformFindsEnoughToShow() = runTest {
        for ((platform, host) in demoHosts) {
            val session = router(host)
            val paths = flatten(bundledMenu(platform).items).mapNotNull { it.path }
            val shown = paths.count { path ->
                try {
                    session.describe(MenuPath.parse(path))
                    true
                } catch (e: DeviceException) {
                    false
                }
            }
            assertTrue(shown >= 7, "$platform shows $shown of ${paths.size} entries of its menu")
        }
    }

    @Test
    fun everyPathOfADemoRouterIsOneThePacksKnow() = runTest {
        for ((platform, host) in demoHosts) {
            val packed = packsOf(platform, router(host)).flatMap { pack ->
                pack.paths.keys + pack.window.tabs.map { it.path } + pack.paths.flatMap { (path, shown) ->
                    listOfNotNull(shown.monitor?.command?.trimStart('/'), shown.owner?.path) + (shown.links + shown.menu).map { it.path } +
                        shown.actions.map { "$path/${it.command}" } + shown.open?.paths?.values.orEmpty()
                } + pack.forms.values.flatMap { form -> form.tabs.flatMap { listOfNotNull(it.path, it.monitor?.command?.trimStart('/')) } }
            }.toSet() + readDirectly
            val menu = flatten(bundledMenu(platform).items).mapNotNull { it.path }
            // an entry that no pack describes opens as an automatic window, and the child menus of its path are its tabs
            val automatic = menu.filter { it !in packed }
            val unknown = leaves(router(host)).map { it.toString() }.filter { path -> path !in packed && path !in menu && automatic.none { path.startsWith("$it/") } }
            assertTrue(unknown.isEmpty(), "$platform: $unknown")
        }
    }

    @Test
    fun theRowsCarryTheColumnsOfTheirPacks() = runTest {
        for ((platform, host) in demoHosts) {
            val session = router(host)
            for (pack in packsOf(platform, session)) {
                for ((path, shown) in pack.paths) {
                    val schema = try {
                        session.describe(MenuPath.parse(path))
                    } catch (e: DeviceException) {
                        continue
                    }
                    if (schema.kind != NodeKind.List || shown.columns.isEmpty()) continue
                    val keys = rows(session, path).flatMap { it.values.keys }.toSet()
                    val common = shown.columns.count { it in keys }
                    assertTrue(common >= minOf(2, shown.columns.size), "$platform $path has $common of ${shown.columns}: $keys")
                }
            }
        }
    }

    @Test
    fun changesStayUntilSafeModeTakesThemBack() = runTest {
        val session = router("routeros.demo")
        val path = MenuPath.parse("ip/dns/static")
        val before = rows(session, "ip/dns/static")

        val id = session.add(path, mapOf("name" to "test.lan", "address" to "10.0.0.9"))
        session.set(path, id, mapOf("address" to "10.0.0.10", "ttl" to "1h"))
        session.set(path, id, mapOf("ttl" to null))
        val added = rows(session, "ip/dns/static").single { it.id == id }
        assertEquals(mapOf("name" to "test.lan", "address" to "10.0.0.10"), added.values)

        val safe = assertNotNull(session.safeMode)
        assertTrue(safe.enter())
        session.remove(path, listOf(id))
        assertEquals(before.size, rows(session, "ip/dns/static").size)
        assertEquals(1, safe.actions.first())
        safe.rollback()
        assertEquals(before.size + 1, rows(session, "ip/dns/static").size)
        assertEquals(false, safe.active.first())
    }

    @Test
    fun anOrderedListMovesItsRules() = runTest {
        val session = router("routeros.demo")
        val path = MenuPath.parse("ip/firewall/filter")
        val ids = rows(session, "ip/firewall/filter").map { it.id }
        session.move(path, listOf(ids.last()), ids.first())
        assertEquals(listOf(ids.last()) + ids.dropLast(1), rows(session, "ip/firewall/filter").map { it.id })
        session.move(path, listOf(ids.last()), null)
        assertEquals(ids, rows(session, "ip/firewall/filter").map { it.id })
    }

    @Test
    fun whatIsReadOnlyOrMissingIsRefused() = runTest {
        val session = router("routeros.demo")
        assertEquals(ErrorCategory.PermissionDenied, assertFailsWith<DeviceException> { session.add(MenuPath.parse("ip/firewall/connection"), emptyMap()) }.category)
        assertEquals(ErrorCategory.MissingItem, assertFailsWith<DeviceException> { session.describe(MenuPath.parse("no/such/menu")) }.category)
        assertEquals(ErrorCategory.MissingItem, assertFailsWith<DeviceException> { session.set(MenuPath.parse("ip/dns/static"), "*FF", mapOf("ttl" to "1h")) }.category)
    }

    @Test
    fun theButtonsOfARouterOsPackAreOfferedAndDoNothing() = runTest {
        val session = router("routeros.demo")
        val lease = MenuPath.parse("ip/dhcp-server/lease")
        assertTrue("make-static" in session.describe(lease).commands)
        assertEquals(emptyList(), session.run(lease, "make-static", emptyMap()).toList())
        assertFalse("make-static" in router("openwrt.demo").describe(MenuPath.parse("firewall/rule")).commands)
    }

    @Test
    fun theExportKeepsTheSettingsAndLeavesTheStateOut() = runTest {
        val export = router("routeros.demo").export(MenuPath.Root)
        assertTrue("/ip firewall filter" in export)
        assertFalse("/log" in export)
        assertFalse("/ip firewall connection" in export)
    }

    @Test
    fun switchesOfUciAreFlagsAndThoseOfRouterOsYesOrNo() = runTest {
        val openWrt = router("openwrt.demo").describe(MenuPath.parse("firewall/rule"))
        assertEquals(ValueType.Flag, openWrt.argument("enabled")?.type)
        val routerOs = router("routeros.demo").describe(MenuPath.parse("ip/address"))
        assertEquals(ValueType.YesNo, routerOs.argument("disabled")?.type)
    }

    @Test
    fun theUnifiedViewOfOpenWrtReadsTheListsAndDevicesOfTheDemo() = runTest {
        val session = router("openwrt.demo")
        val layer = RouterOsLayer(session)
        assertEquals(listOf("lan1", "lan2", "lan3", "lan4"), rows(layer, "interface/bridge/port").map { it["interface"] })
        assertEquals(listOf("cfg03dc81 network wan", "cfg03dc81 network wan6"), rows(layer, "interface/list/member").filter { it["list"] == "wan" }.map { it.id })
        assertEquals("0.openwrt.pool.ntp.org,1.openwrt.pool.ntp.org", layer.get(MenuPath.parse("system/ntp/client"), null)?.get("servers"))
        assertTrue("br-lan" in layer.describe(MenuPath.parse("ip/address")).argument("interface")?.choices.orEmpty())
        layer.add(MenuPath.parse("interface/list"), mapOf("name" to "dmz"))
        assertEquals(listOf("REJECT", "ACCEPT", "REJECT"), rows(session, "firewall/zone").last().let { zone -> listOf("input", "output", "forward").map { zone[it] } })
    }

    @Test
    fun commandsAnswerWithRows() = runTest {
        val session = router("routeros.demo")
        val replies = session.run(MenuPath.parse("tool"), "ping", mapOf("address" to "1.1.1.1", "count" to "3", "interval" to "1ms")).toList()
        assertEquals(listOf("0", "1", "2"), replies.map { it["seq"] })
        assertEquals("3", replies.last()["sent"])

        val hops = session.run(MenuPath.parse("tool"), "traceroute", mapOf("address" to "1.1.1.1", "count" to "2")).toList()
        assertEquals(12, hops.size)
        assertEquals(setOf("1", "2"), hops.mapNotNull { it[".section"] }.toSet())

        assertEquals("ether1", session.run(MenuPath.parse("interface"), "monitor-traffic", mapOf("interface" to "ether1")).first()["name"])
    }

    @Test
    fun theExportListsTheConfigurationAsCommands() = runTest {
        val text = router("routeros.demo").export()
        assertTrue("/ip address\nadd address=192.168.88.1/24" in text, text.take(400))
        assertTrue("/system identity\nset name=demo-hap" in text)
    }
}
