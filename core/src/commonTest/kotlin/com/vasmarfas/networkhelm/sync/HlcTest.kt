package com.vasmarfas.networkhelm.sync

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class HlcTest {

    private class Moved(var millis: Long) : Clock {
        override fun now() = Instant.fromEpochMilliseconds(millis)
    }

    @Test
    fun aStampIsWrittenReadBackAndOrderedByTimeCounterAndWriter() {
        val stamp = Hlc(1763400000000, 3, "a1b2")
        assertEquals("1763400000000-0003-a1b2", stamp.toString())
        assertEquals(stamp, Hlc.parse(stamp.toString()))
        assertEquals(Hlc(5, 12345, "ff"), Hlc.parse("0000000000005-12345-ff"))
        assertEquals("\"0000000000005-0000-ff\"", recordJson.encodeToString(Hlc.serializer(), Hlc(5, 0, "ff")))
        assertTrue(Hlc(1, 9, "b") < Hlc(2, 0, "a"))
        assertTrue(Hlc(2, 0, "b") < Hlc(2, 1, "a"))
        assertTrue(Hlc(2, 1, "a") < Hlc(2, 1, "b"))
        for (text in listOf("", "1-2", "x-0-a", "1-y-a", "1-0-", "-1-0-a", "1--1-a")) assertNull(Hlc.parse(text), text)
        assertFailsWith<SerializationException> { recordJson.decodeFromString(Hlc.serializer(), "\"yesterday\"") }
    }

    @Test
    fun theClockGoesOnThoughTheTimeOfTheSystemGoesBack() {
        val time = Moved(1_000)
        val clock = HlcClock("me", time)
        val first = clock.now()
        time.millis = 500
        val second = clock.now()
        val third = clock.now()
        time.millis = 2_000
        assertEquals(listOf(Hlc(1_000, 0, "me"), Hlc(1_000, 1, "me"), Hlc(1_000, 2, "me"), Hlc(2_000, 0, "me")), listOf(first, second, third, clock.now()))
    }

    @Test
    fun aStampSeenFromAnotherWriterIsFollowedOnlyByLaterOnes() {
        val time = Moved(1_000)
        val clock = HlcClock("me", time)
        clock.observe(Hlc(5_000, 7, "other"))
        assertEquals(Hlc(5_000, 8, "me"), clock.now())
        clock.observe(Hlc(4_000, 99, "other"))
        assertEquals(Hlc(5_000, 9, "me"), clock.now())
        time.millis = 6_000
        assertEquals(Hlc(6_000, 0, "me"), clock.now())
    }

    @Test
    fun everyClockWritesAsAWriterOfItsOwn() {
        assertNotEquals(HlcClock().device, HlcClock().device)
        assertEquals(16, HlcClock().device.length)
    }
}
