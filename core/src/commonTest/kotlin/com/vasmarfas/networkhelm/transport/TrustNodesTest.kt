package com.vasmarfas.networkhelm.transport

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TrustNodesTest {
    private class Stream(override val certificateSha256: String) : TlsStream {
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = -1

        override suspend fun write(bytes: ByteArray) = Unit

        override fun close() = Unit
    }

    // the cloud of KeenDNS answered a name from two nodes, and the second connection met the certificate of the other one
    @Test
    fun aCertificateOfAnotherNodeOnTheNextConnectionIsAskedAboutToo() = runTest {
        val asked = mutableListOf<TrustRequest>()
        val policy = TrustPolicy(MemoryTrustStore()) { asked += it; true }
        val nodes = ArrayDeque(listOf("aa", "bb"))
        val stream = openTlsTrusted("rd-test.netcraze.link", 443, policy) { _, _, pinned ->
            val shown = nodes.removeFirstOrNull() ?: pinned.orEmpty()
            if (pinned != null && pinned != shown) throw CertificateMismatchException(pinned, shown)
            Stream(shown)
        }
        assertEquals(listOf("aa" to null, "bb" to "aa"), asked.map { it.fingerprint to it.previous })
        assertEquals("bb", stream.certificateSha256)
    }
}
