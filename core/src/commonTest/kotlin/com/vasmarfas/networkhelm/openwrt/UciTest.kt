package com.vasmarfas.networkhelm.openwrt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UciTest {

    @Test
    fun onlyAnEditOfTheInterfaceOfTheConnectionWarns() {
        val wan = setOf("wan", "wan6")
        assertTrue(touchesConnection(listOf(UciChange("network", "set", "wan", "proto", "static")), wan))
        assertFalse(touchesConnection(listOf(UciChange("network", "set", "lan", "ipaddr", "192.168.99.2")), wan))
        assertFalse(touchesConnection(listOf(UciChange("firewall", "set", "wan", "input", "DROP")), wan))
    }

    @Test
    fun aConfigTheLoginMayNotReadIsNotThereToIt() = runTest {
        val ubus = object : Ubus {
            override suspend fun call(obj: String, method: String, args: JsonObject): JsonObject = throw UbusException(UbusStatus.PERMISSION_DENIED)

            override suspend fun objects() = setOf("uci")

            override fun listen(vararg events: String): Flow<JsonObject> = emptyFlow()

            override fun subscribe(obj: String): Flow<JsonObject> = emptyFlow()
        }
        assertEquals(UbusStatus.NOT_FOUND, assertFailsWith<UbusException> { RpcdUci(ubus).sections("sqm") }.status)
        assertEquals(UbusStatus.PERMISSION_DENIED, assertFailsWith<UbusException> { RpcdUci(ubus).set("sqm", "eth1", mapOf("enabled" to "1"), emptyMap()) }.status)
    }

    @Test
    fun onlyALapsedSessionIsReplaced() = runTest {
        var known = setOf("s1", "s2")
        var denied = setOf("s1")
        val ubus = object : Ubus {
            override suspend fun call(obj: String, method: String, args: JsonObject): JsonObject {
                val session = args["ubus_rpc_session"]?.jsonPrimitive?.content
                if (obj == "session") return if (session in known) JsonObject(emptyMap()) else throw UbusException(UbusStatus.NOT_FOUND)
                if (session !in known || session in denied) throw UbusException(UbusStatus.PERMISSION_DENIED)
                return JsonObject(mapOf("configs" to JsonArray(listOf(JsonPrimitive("network")))))
            }

            override suspend fun objects() = setOf("uci")

            override fun listen(vararg events: String): Flow<JsonObject> = emptyFlow()

            override fun subscribe(obj: String): Flow<JsonObject> = emptyFlow()
        }
        var opened = 0
        val uci = RpcdUci(ubus) { "s${++opened}" }
        assertEquals(UbusStatus.PERMISSION_DENIED, assertFailsWith<UbusException> { uci.configs() }.status)
        assertEquals(1, opened)

        known = setOf("s2")
        denied = emptySet()
        assertEquals(listOf("network"), uci.configs())
        assertEquals(listOf("network"), uci.configs())
        assertEquals(2, opened)
    }
}
