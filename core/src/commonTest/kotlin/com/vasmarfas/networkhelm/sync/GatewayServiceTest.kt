package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GatewayServiceTest {
    // a device that waits on the gateway hears of a change as it comes, and of one made before it asked at once
    @Test
    fun aDeviceWaitingHearsOfAChangeAsItComes() = runTest {
        val service = FakeGateway().service()
        val first = service.changes(-1)
        val waiting = async { service.changes(first) }
        service.write(statePath(FIRST_DEVICE), "one".encodeToByteArray())
        assertEquals(first + 1, waiting.await())
        assertEquals(first + 1, service.changes(first))
    }

    // the token comes once for the user, the password and the code; they go nowhere else, and not into the message of a refusal
    @Test
    fun theTokenComesForTheLoginAndTheCodeOfTheGateway() = runTest {
        val gateway = FakeGateway()
        val address = "https://gateway.example.org:8443"
        val token = gatewayToken(gateway.http(), address, "admin", "gateway password", "512743")
        assertEquals(GATEWAY_TOKEN, token)
        val error = assertFailsWith<DeviceException> { gatewayToken(gateway.http(), address, "admin", "wrong password", "512743") }
        assertEquals("gateway.example.org did not accept the login", error.message)
        assertFalse("wrong password" in error.toString())
        val refused = assertFailsWith<DeviceException> { gateway.service("a token that is not the one").list() }
        assertFalse("a token that is not the one" in refused.toString())
    }
}
