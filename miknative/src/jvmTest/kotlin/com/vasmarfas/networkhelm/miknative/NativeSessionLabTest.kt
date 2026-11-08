package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.routeros.RouterOsProtocol
import com.vasmarfas.networkhelm.routeros.connectRouterOs
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NativeSessionLabTest {

    private val devices = MenuPath.parse("cmr/device")
    private val resources = MenuPath.parse("native/167/44")

    private suspend fun checkController(session: DeviceSession) {
        val pack = session.packs(null).first { it.menu == listOf("Controller", "Devices") }
        assertEquals(listOf("Devices", "Resources"), pack.window.tabs.map { it.title })
        val names = assertNotNull(pack.paths.getValue("cmr/device").native).fields.map { it.name }
        assertTrue("pairing-status" in names && "identity" !in names, names.toString())
        val device = assertNotNull(session.get(devices, "*1", null))
        assertEquals("paired", device["pairing-status"])
        assertTrue(device["identity"].orEmpty().isNotEmpty(), device.toString())
        assertEquals(NodeKind.List, session.describe(resources).kind)
        assertEquals(1, session.count(resources))
        val used = assertIs<ListEvent.Snapshot>(session.watch(resources).first()).items.single()
        assertEquals(device["identity"], used["device"])
        for (name in listOf("cpu", "memory", "hdd")) assertTrue(used[name].orEmpty().endsWith("%"), used.toString())
        val listed = session.watch(devices).mapNotNull { event ->
            when (event) {
                is ListEvent.Snapshot -> event.items.firstOrNull { "pairing-status" in it.values }
                is ListEvent.Upsert -> event.item.takeIf { "pairing-status" in it.values }
                is ListEvent.Removed, is ListEvent.Progress -> null
            }
        }.first()
        assertEquals("paired", listed["pairing-status"])
    }

    @Test
    fun theDevicesHaveTheFieldsOfWinBoxAndTheResourcesATabOfTheirOwn() = runBlocking<Unit> {
        val lab = Lab.require("chr.host", "chr.user", "chr.password")
        withTimeout(240.seconds) {
            val login = RouterOsLogin(lab.getValue("chr.host"), lab.getValue("chr.user"), lab.getValue("chr.password"), RouterOsProtocol.ApiSsl)
            val session = connectRouterOs(login, TrustPolicy(MemoryTrustStore()) { true }, this, native = login.nativeSide(this))
            try {
                checkController(session)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun theSameOverPort8291() = runBlocking<Unit> {
        val lab = Lab.require("chr.host", "chr.user", "chr.password")
        withTimeout(240.seconds) {
            val session = connectNativeHost(lab.getValue("chr.host"), 8291, lab.getValue("chr.user"), lab.getValue("chr.password"), this)
            try {
                checkController(session)
            } finally {
                session.close()
            }
        }
    }
}
