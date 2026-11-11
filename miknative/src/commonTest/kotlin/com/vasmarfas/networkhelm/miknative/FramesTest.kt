package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal class MemoryStream(private val bytes: ByteArray, private val piece: Int = Int.MAX_VALUE) : ByteStream {
    private var position = 0

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position == bytes.size) return -1
        val count = minOf(length, piece, bytes.size - position)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }

    override suspend fun write(bytes: ByteArray) = Unit

    override fun close() = Unit
}

// whole chunks one after another, so the frame they make has no end while the bytes last
private class WholeChunks(private var left: Int) : ByteStream {
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (left == 0) return -1
        val count = minOf(length, left)
        buffer.fill(-1, offset, offset + count)
        left -= count
        return count
    }

    override suspend fun write(bytes: ByteArray) = Unit

    override fun close() = Unit
}

class FramesTest {
    @Test
    fun aShortFrameIsOneChunk() {
        val bytes = encodeFrame(6, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(3, 6, 1, 2, 3), bytes)
    }

    @Test
    fun aFrameOfWholeChunksEndsWithAnEmptyOne() {
        val bytes = encodeFrame(6, ByteArray(255))
        assertEquals(255 + 4, bytes.size)
        assertContentEquals(byteArrayOf(0, -1), bytes.copyOfRange(bytes.size - 2, bytes.size))
    }

    @Test
    fun framesComeBackWhateverTheSizeAndThePieces() = runTest {
        for (size in listOf(0, 1, 254, 255, 256, 509, 510, 511, 3570, 5000)) {
            val payload = ByteArray(size) { (it * 7).toByte() }
            for (piece in listOf(1, 5, 1000)) {
                val reader = FrameReader(MemoryStream(encodeFrame(6, payload) + encodeFrame(1, byteArrayOf(9)), piece))
                val first = checkNotNull(reader.next())
                assertEquals(6, first.tag)
                assertContentEquals(payload, first.payload, "size $size, pieces of $piece")
                assertEquals(1, checkNotNull(reader.next()).tag)
                assertNull(reader.next())
            }
        }
    }

    @Test
    fun aFrameCutShortIsAnError() = runTest {
        val bytes = encodeFrame(6, ByteArray(300))
        assertFailsWith<FrameEndedException> { FrameReader(MemoryStream(bytes.copyOf(bytes.size - 3))).next() }
    }

    @Test
    fun aFrameThatGrowsPastSixteenMebibytesIsGivenUp() = runTest {
        val error = assertFailsWith<DeviceException> { FrameReader(WholeChunks(17 shl 20)).next() }
        assertEquals(ErrorCategory.ConnectionLost, error.category)
    }
}
