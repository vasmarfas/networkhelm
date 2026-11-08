package com.vasmarfas.networkhelm.nettools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosHostOpsTest {
    private val ops = assertNotNull(hostOps())

    private fun ipv4(ttl: Int, icmp: ByteArray) = ByteArray(20).also {
        it[0] = 0x45
        it[8] = ttl.toByte()
    } + icmp

    private fun echo(type: Int, identifier: Int, number: Int) =
        byteArrayOf(type.toByte(), 0, 0, 0, (identifier shr 8).toByte(), identifier.toByte(), (number shr 8).toByte(), number.toByte())

    @Test
    fun theLoopbackAnswersAnEcho() = runTest {
        val reply = ops.ping("127.0.0.1", 2_000)
        assertNotNull(reply.timeMillis)
        assertEquals("127.0.0.1", reply.from)
        assertEquals(64, reply.ttl)
    }

    @Test
    fun theIpv6LoopbackAnswersAnEcho() = runTest {
        val reply = ops.ping("::1", 2_000)
        assertNotNull(reply.timeMillis)
        assertEquals("::1", reply.from)
    }

    // lo0 carries 16384 bytes, so the echo stops on this device
    @Test
    fun anEchoWiderThanTheLoopbackWithoutFragmentsSaysSo() = runTest {
        assertTrue(ops.ping("127.0.0.1", 2_000, size = 20_000, dontFragment = true).fragmentationNeeded)
        assertNotNull(ops.ping("127.0.0.1", 2_000, size = 20_000).timeMillis)
    }

    // the simulator of the tests runs without the services of a device, so only the hosts file answers
    @Test
    fun localhostResolvesBothWays() = runTest {
        assertTrue("127.0.0.1" in ops.resolve("localhost"))
        assertEquals("localhost", withContext(Dispatchers.Default) { ops.reverse("127.0.0.1") })
    }

    @Test
    fun onlyTheMessagesAboutTheEchoCount() {
        assertEquals(57, answer(ipv4(57, echo(0, 0x1234, 7)), "192.0.2.1", false, 0x1234, 7, 3.0)?.ttl)
        assertNull(answer(ipv4(57, echo(0, 0x1234, 8)), "192.0.2.1", false, 0x1234, 7, 3.0))
        val expired = assertNotNull(answer(ipv4(254, byteArrayOf(11, 0, 0, 0, 0, 0, 0, 0) + ipv4(1, echo(8, 0x1234, 7))), "198.51.100.1", false, 0x1234, 7, 9.0))
        assertTrue(expired.expired)
        assertEquals("198.51.100.1", expired.from)
        assertTrue(assertNotNull(answer(ipv4(254, byteArrayOf(3, 4, 0, 0, 0, 0, 5, 0xDC.toByte()) + ipv4(64, echo(8, 0x1234, 7))), "198.51.100.1", false, 0x1234, 7, 9.0)).fragmentationNeeded)
        assertNull(answer(ipv4(254, byteArrayOf(3, 1, 0, 0, 0, 0, 0, 0) + ipv4(64, echo(8, 0x1234, 7))), "198.51.100.1", false, 0x1234, 7, 9.0))
        assertTrue(assertNotNull(answer(byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0) + ByteArray(40) + echo(128, 0x1234, 7), "2001:db8::1", true, 0x1234, 7, 9.0)).expired)
        assertNull(answer(byteArrayOf(3, 0, 0), "192.0.2.1", false, 0x1234, 7, 3.0))
    }

    // the sum over a message that carries its own sum is zero
    @Test
    fun theSumOfAnEchoChecksOut() {
        val packet = echo(8, 0x1234, 7) + ByteArray(5) { it.toByte() }
        val sum = checksum(packet)
        packet[2] = (sum shr 8).toByte()
        packet[3] = sum.toByte()
        assertEquals(0, checksum(packet))
    }
}
