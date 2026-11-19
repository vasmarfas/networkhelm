@file:OptIn(ExperimentalWasmJsInterop::class)

package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustPolicy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncHttpWebTest {

    // the client of the browser is made with the first request and not before: until then the page fetches nothing
    @Test
    fun theHttpOfSynchronisationMakesNoClientAndFetchesNothingBeforeItsFirstRequest() = runTest {
        countFetches()
        val before = fetches()
        var made = 0
        val http = SyncHttp(TrustPolicy(MemoryTrustStore()) { false }) { pinning ->
            made++
            httpClient(pinning)
        }
        assertEquals(0, made)
        assertEquals(before, fetches())
        assertFailsWith<DeviceException> { http.send("PUT", "http://127.0.0.1:1/") }
        assertEquals(1, made)
        assertEquals(before + 1, fetches())
        http.close()
    }
}

private fun countFetches(): Unit =
    js("{ if (globalThis.networkhelmFetches === undefined) { globalThis.networkhelmFetches = 0; const fetch = globalThis.fetch; globalThis.fetch = function () { globalThis.networkhelmFetches++; return fetch.apply(this, arguments); }; } }")

private fun fetches(): Int = js("globalThis.networkhelmFetches")
