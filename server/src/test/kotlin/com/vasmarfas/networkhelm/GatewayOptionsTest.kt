package com.vasmarfas.networkhelm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayOptionsTest {
    @Test
    fun defaultsKeepTheGatewayOnLocalNetworks() {
        val options = GatewayOptions(emptyArray(), emptyMap())
        assertEquals("0.0.0.0", options.host)
        assertEquals(8443, options.port)
        assertFalse(options.public)
        assertFalse(options.code)
    }

    @Test
    fun theEnvironmentSetsTheListenerAndTheLogin() {
        val options = GatewayOptions(emptyArray(), mapOf(
            "NETWORKHELM_GATEWAY_HOST" to "127.0.0.1",
            "NETWORKHELM_GATEWAY_PORT" to "9443",
            "NETWORKHELM_GATEWAY_PUBLIC" to "true",
            "NETWORKHELM_GATEWAY_CODE" to "true",
        ))
        assertEquals("127.0.0.1", options.host)
        assertEquals(9443, options.port)
        assertTrue(options.public)
        assertTrue(options.code)
    }

    @Test
    fun argumentsTakePriorityOverTheEnvironment() {
        val options = GatewayOptions(arrayOf("--host", "::1", "--port", "443", "--public", "--code"), mapOf(
            "NETWORKHELM_GATEWAY_HOST" to "127.0.0.1",
            "NETWORKHELM_GATEWAY_PORT" to "9443",
            "NETWORKHELM_GATEWAY_PUBLIC" to "false",
            "NETWORKHELM_GATEWAY_CODE" to "false",
        ))
        assertEquals("::1", options.host)
        assertEquals(443, options.port)
        assertTrue(options.public)
        assertTrue(options.code)
    }

    @Test
    fun invalidValuesDoNotSilentlyChangeTheListenerOrTheAccessPolicy() {
        for (port in listOf("0", "65536", "8443x")) {
            assertFailsWith<IllegalArgumentException> { GatewayOptions(arrayOf("--port", port), emptyMap()) }
            assertFailsWith<IllegalArgumentException> { GatewayOptions(emptyArray(), mapOf("NETWORKHELM_GATEWAY_PORT" to port)) }
        }
        for (flag in listOf("PUBLIC", "CODE")) {
            assertFailsWith<IllegalArgumentException> { GatewayOptions(emptyArray(), mapOf("NETWORKHELM_GATEWAY_$flag" to "yes")) }
        }
        assertFailsWith<IllegalArgumentException> { GatewayOptions(arrayOf("--port"), emptyMap()) }
        assertFailsWith<IllegalArgumentException> { GatewayOptions(arrayOf("--host", "--public"), emptyMap()) }
    }
}
