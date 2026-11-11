package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.MenuPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenWrtSessionTest {

    private class Router(private val answer: (String) -> String) : Ubus {
        override suspend fun call(obj: String, method: String, args: JsonObject): JsonObject = Json.parseToJsonElement(answer("$obj $method")).jsonObject

        override suspend fun objects() = setOf("uci")

        override fun listen(vararg events: String): Flow<JsonObject> = emptyFlow()

        override fun subscribe(obj: String): Flow<JsonObject> = emptyFlow()
    }

    private val empty = JsonObject(emptyMap())

    private fun session(router: Router) = OpenWrtSession(router, RpcdUci(router), OpenWrtFacts(empty, empty, empty, setOf("uci"), emptySet(), emptySet(), true), null, encrypted = true)

    @Test
    fun theCountOfEditsThatWaitOutlivesAPollThatFailed() = runTest {
        var polls = 0
        val session = session(
            Router {
                if (++polls == 1) throw UbusException(UbusStatus.TIMEOUT)
                """{"changes":{"network":[["set","lan","ipaddr","192.0.2.1"]]}}"""
            },
        )
        assertEquals(1, session.safeMode.actions.first())
        assertEquals(2, polls)
    }

    @Test
    fun anAnswerTheSessionCannotReadIsAnErrorOfTheDevice() = runTest {
        val session = session(Router { """{"values":{"lan":"not a section"}}""" })
        val failure = assertFailsWith<DeviceException> { session.get(MenuPath.parse("network/interface"), null) }
        assertEquals(ErrorCategory.Api, failure.category)
    }
}
