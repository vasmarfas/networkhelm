package com.vasmarfas.networkhelm.openwrt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenWrtFactsTest {

    @Test
    fun aRouterWithoutLuciHasNoMenusAndIsStillRead() = runTest {
        val ubus = object : Ubus {
            override suspend fun call(obj: String, method: String, args: JsonObject): JsonObject = when ("$obj $method") {
                "file read", "file list" -> throw UbusException(UbusStatus.NOT_FOUND)
                "session access" -> JsonObject(mapOf("access" to JsonPrimitive(true)))
                else -> JsonObject(mapOf("hostname" to JsonPrimitive("rd-test")))
            }

            override suspend fun objects() = setOf("file", "session", "system")

            override fun listen(vararg events: String): Flow<JsonObject> = emptyFlow()

            override fun subscribe(obj: String): Flow<JsonObject> = emptyFlow()
        }
        val facts = readFacts(ubus, null)
        assertEquals(emptySet<String>(), facts.apps)
        assertEquals("rd-test", facts.device.identity)
        assertTrue(facts.writable)
    }
}
