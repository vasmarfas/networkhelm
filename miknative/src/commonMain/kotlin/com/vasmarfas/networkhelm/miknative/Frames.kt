package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.ByteStream

internal const val TAG_ENCRYPTED = 0x06
private const val TAG_NEXT = 0xFF
private const val CHUNK = 0xFF
// a record takes 64 KiB at most and a frame holds a few of them; without a limit chunks that never end fill the memory
private const val FRAME_MOST = 16 shl 20

internal class Frame(val tag: Int, val payload: ByteArray)

internal class FrameEndedException(message: String) : Exception(message)

// chunks of length, tag and up to 255 bytes; the first carries the tag of the frame, the rest 0xFF
// a chunk shorter than 255 bytes ends the frame, so a multiple of 255 is closed by an empty chunk
internal fun encodeFrame(tag: Int, payload: ByteArray): ByteArray {
    val out = ByteSink(payload.size + payload.size / CHUNK * 2 + 2)
    var offset = 0
    var chunkTag = tag
    while (true) {
        val length = minOf(CHUNK, payload.size - offset)
        out.write(length)
        out.write(chunkTag)
        out.write(payload, offset, length)
        offset += length
        if (length < CHUNK) return out.toByteArray()
        chunkTag = TAG_NEXT
    }
}

internal class FrameReader(private val stream: ByteStream) {
    private val header = ByteArray(2)

    suspend fun next(): Frame? {
        val payload = ByteSink()
        var tag = -1
        while (true) {
            if (!readFully(header, 2, tag < 0)) return null
            val length = header[0].toInt() and 0xFF
            if (tag < 0) tag = header[1].toInt() and 0xFF
            if (payload.size + length > FRAME_MOST) throw DeviceException(ErrorCategory.ConnectionLost, "a frame of the router is longer than $FRAME_MOST bytes")
            val chunk = ByteArray(length)
            readFully(chunk, length, false)
            payload.write(chunk)
            if (length < CHUNK) return Frame(tag, payload.toByteArray())
        }
    }

    private suspend fun readFully(target: ByteArray, length: Int, endAllowed: Boolean): Boolean {
        var done = 0
        while (done < length) {
            val count = stream.read(target, done, length - done)
            if (count < 0) {
                if (done == 0 && endAllowed) return false
                throw FrameEndedException("the router closed the connection inside a frame")
            }
            done += count
        }
        return true
    }
}
