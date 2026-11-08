package com.vasmarfas.networkhelm.routeros

import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SilentLoginTest {
    @Test
    fun aPortThatAcceptsAndThenSaysNothingEndsTheLoginAfterTenSeconds() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) { server.accept() }
            val login = RouterOsLogin("127.0.0.1", "admin", "", RouterOsProtocol.Api, server.localPort)
            assertFailsWith<LoginTimeoutException> { connectRouterOs(login, TrustPolicy(MemoryTrustStore()) { true }, scope) }
        }
        scope.cancel()
    }
}
