package com.vasmarfas.networkhelm.transport

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocketStreamTest {

    // an answer that has arrived whole and that the socket gives a segment at a time
    private class Arrived(private val bytes: ByteArray, private val segment: Int) : InputStream() {
        val readers = mutableSetOf<Thread>()
        private var at = 0

        override fun available() = bytes.size - at

        override fun read(): Int = error("the stream reads into an array")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readers += Thread.currentThread()
            if (at == bytes.size) return -1
            val count = minOf(length, segment, bytes.size - at)
            bytes.copyInto(buffer, offset, at, at + count)
            at += count
            return count
        }
    }

    private class Wired(private val input: InputStream) : Socket() {
        override fun getInputStream() = input

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    }

    @Test
    fun theSocketIsNotReadOnTheThreadOfTheCallerThoughTheBytesHaveArrived() = runBlocking {
        val sent = ByteArray(200_000) { it.toByte() }
        val wire = Arrived(sent, 1_460)
        val stream = SocketStream(Wired(wire))
        val got = ByteArrayOutputStream()
        val part = ByteArray(700)
        while (got.size() < sent.size) got.write(part, 0, stream.read(part, 0, part.size))
        assertContentEquals(sent, got.toByteArray())
        assertTrue(wire.readers.isNotEmpty())
        assertFalse(Thread.currentThread() in wire.readers, "Android fails such a read on its main thread")
    }
}
