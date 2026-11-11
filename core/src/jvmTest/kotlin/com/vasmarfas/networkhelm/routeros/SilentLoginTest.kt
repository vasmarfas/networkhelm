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
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SilentLoginTest {
    @Test
    fun aPortThatAcceptsAndThenSaysNothingEndsTheLoginAfterTenSeconds() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            // the accepted socket is held to the end: a collection of the garbage closes one nobody holds, and the login sees the end of the stream instead of silence
            val accepted = arrayOfNulls<Socket>(1)
            thread(isDaemon = true) { accepted[0] = server.accept() }
            val login = RouterOsLogin("127.0.0.1", "admin", "", RouterOsProtocol.Api, server.localPort)
            assertFailsWith<LoginTimeoutException> { connectRouterOs(login, TrustPolicy(MemoryTrustStore()) { true }, scope) }
            accepted[0]?.close()
        }
        scope.cancel()
    }
}
