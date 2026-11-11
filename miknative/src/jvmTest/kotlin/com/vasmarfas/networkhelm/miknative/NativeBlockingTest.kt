package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.SocketStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class QuietStream : ByteStream {
    private val closed = CountDownLatch(1)

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(Dispatchers.IO) {
        closed.await()
        throw IOException("Socket closed")
    }

    override suspend fun write(bytes: ByteArray) = Unit

    override fun close() = closed.countDown()
}

// the router end of a login it answers with a text in place of its confirmation, as it does to a wrong password
private suspend fun refuse(stream: ByteStream, words: String = "invalid user name or password (6)") {
    val frames = FrameReader(stream)
    val hello = checkNotNull(frames.next())
    stream.write(encodeFrame(TAG_ENCRYPTED, EcSrp5Server("admin", "secret", ByteArray(16)).challenge(hello.payload)))
    checkNotNull(frames.next())
    stream.write(encodeFrame(TAG_ENCRYPTED, words.encodeToByteArray()))
}

class NativeBlockingTest {
    @Test
    fun aRouterThatNeverAnswersTheLoginIsGivenUpAtTheDeadline() = runBlocking {
        val error = assertFailsWith<DeviceException> {
            withTimeout(10_000) { NativeConnection.open(QuietStream(), "admin", "secret", this, loginTimeoutMillis = 200) }
        }
        assertEquals(ErrorCategory.ConnectionLost, error.category)
    }

    @Test
    fun aCallerThatGivesUpIsNotHeldByAQuietRouter() = runBlocking {
        val attempt = launch { NativeConnection.open(QuietStream(), "admin", "secret", this) }
        delay(200)
        withTimeout(5_000) { attempt.cancelAndJoin() }
    }

    @Test
    fun aLoginTheRouterRefusesIsNotTriedAgain() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val login = async(Dispatchers.IO) { runCatching { NativeConnection.open("127.0.0.1", "admin", "guess", this, server.localPort) }.exceptionOrNull() }
            val first = server.accept()
            refuse(SocketStream(first))
            server.soTimeout = 1_000
            assertFailsWith<SocketTimeoutException> { server.accept() }
            val error = login.await() as? DeviceException
            assertEquals(ErrorCategory.PermissionDenied, error?.category)
            assertEquals("wrong user name or password", error?.message)
            first.close()
        }
    }

    @Test
    fun aLoginOverTheLimitOfTheRouterIsTriedThreeTimesAndToldInItsWords() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 10_000
            val login = async(Dispatchers.IO) { runCatching { NativeConnection.open("127.0.0.1", "admin", "secret", this, server.localPort) }.exceptionOrNull() }
            repeat(3) { server.accept().use { refuse(SocketStream(it), "too many concurrent logins (6)") } }
            val error = login.await() as? DeviceException
            assertEquals(ErrorCategory.PermissionDenied, error?.category)
            assertEquals("too many concurrent logins", error?.message)
        }
    }

    @Test
    fun aLoginTheRouterDropsIsTriedThreeTimes() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 10_000
            val login = async(Dispatchers.IO) { runCatching { NativeConnection.open("127.0.0.1", "admin", "secret", this, server.localPort) }.exceptionOrNull() }
            repeat(3) { server.accept().close() }
            assertEquals(ErrorCategory.PermissionDenied, (login.await() as? DeviceException)?.category)
        }
    }
}
