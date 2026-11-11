package com.vasmarfas.networkhelm.routeros

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.packs.PackNativeField
import com.vasmarfas.networkhelm.session.NativeObjects
import com.vasmarfas.networkhelm.session.NativeRead
import com.vasmarfas.networkhelm.session.NativeRun
import com.vasmarfas.networkhelm.session.NativeSide
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class NativeLogWindowsTest {

    private val spec = PackNative(listOf(9, 4), listOf(PackNativeField("time", 2, "dateandtime"), PackNativeField("message", 6, "string")), log = true)

    private fun line(id: Int) = Item("*$id", mapOf("time" to "2026-01-01 00:00:${id.toString().padStart(2, '0')}", "message" to "line $id"))

    private fun lines(range: IntRange) = range.map(::line)

    private fun fresh(range: IntRange) = NativeRead(lines(range), snapshot = true, finished = false)

    private fun added(range: IntRange) = NativeRead(lines(range), snapshot = false, finished = false)

    private val onConsole = Item("*C", mapOf("message" to "from the console"))

    private val console = flow {
        emit(ListEvent.Snapshot(listOf(onConsole)))
        awaitCancellation()
    }

    // a script of reads, then silence: the router has no more lines
    private class Followed(private vararg val reads: () -> NativeRead) : NativeObjects {
        var started = 0
        var served = 0

        override suspend fun read(spec: PackNative, limit: Int?): List<Item>? = emptyList()

        override suspend fun lines(spec: PackNative): NativeRun {
            started++
            return object : NativeRun {
                override suspend fun read(): NativeRead = reads.getOrNull(served++)?.invoke() ?: NativeRead(emptyList(), snapshot = false, finished = false)

                override suspend fun cancel() = Unit
            }
        }

        override fun close() = Unit
    }

    private fun windows(objects: NativeObjects) = NativeWindows(NativeSide { objects }, { 8291 }, 1.seconds)

    private fun refused(category: ErrorCategory): () -> NativeRead = { throw DeviceException(category, "refused") }

    private suspend fun TestScope.collected(windows: NativeWindows, held: suspend () -> Int, seconds: Int): List<ListEvent> {
        val events = ArrayList<ListEvent>()
        val job = launch { windows.lines(spec, console, held).collect { events += it } }
        advanceTimeBy(seconds.seconds)
        runCurrent()
        job.cancel()
        return events
    }

    @Test
    fun theWholeBufferIsReadOnceAndThenOnlyTheLinesAddedAreAppended() = runTest {
        val objects = Followed({ fresh(1..3) }, { added(1..0) }, { added(4..5) })
        val events = collected(windows(objects), { 0 }, 4)
        assertEquals(listOf(ListEvent.Snapshot(lines(1..3)), ListEvent.Snapshot(lines(1..5))), events)
        assertEquals(1, objects.started)
    }

    @Test
    fun aSnapshotReadReplacesTheLinesShown() = runTest {
        val objects = Followed({ fresh(1..3) }, { fresh(10..11) })
        assertEquals(listOf(ListEvent.Snapshot(lines(1..3)), ListEvent.Snapshot(lines(10..11))), collected(windows(objects), { 0 }, 3))
    }

    @Test
    fun theLinesKeptAreAsManyAsTheRouterHeldAtFirstAndNeverFewerThanAThousand() = runTest {
        val big = Followed({ fresh(1..1500) }, { added(1501..1510) })
        val shown = collected(windows(big), { 0 }, 3).map { (it as ListEvent.Snapshot).items }
        assertEquals(listOf(1500, 1500), shown.map { it.size })
        assertEquals(listOf("*11", "*1510"), listOf(shown.last().first().id, shown.last().last().id))
        val small = Followed({ fresh(1..3) }, { added(4..1200) })
        val last = collected(windows(small), { 0 }, 3).map { (it as ListEvent.Snapshot).items }.last()
        assertEquals(1000, last.size)
        assertEquals("*1200", last.last().id)
    }

    @Test
    fun theConsoleReadsTheLogWhenTheChannelCannotBeOpened() = runTest {
        val events = collected(NativeWindows(NativeSide { throw DeviceException(ErrorCategory.ConnectionLost, "no login") }, { 8291 }, 1.seconds), { 0 }, 2)
        assertEquals(listOf(ListEvent.Snapshot(listOf(onConsole))), events)
    }

    @Test
    fun theLogOverTheConnectionOfTheSessionWaitsForItToComeBackInsteadOfGoingToTheConsole() = runTest {
        val objects = Followed({ fresh(1..3) })
        var down = 3
        val side = object : NativeSide {
            override val sessionPort = 18291

            override suspend fun open(port: Int): NativeObjects {
                if (down-- > 0) throw DeviceException(ErrorCategory.ConnectionLost, "the connection to the router is lost")
                return objects
            }
        }
        assertEquals(listOf(ListEvent.Snapshot(lines(1..3))), collected(NativeWindows(side, { 8291 }, 1.seconds), { 0 }, 6))
    }

    @Test
    fun theConsoleTakesOverWhenALaterReadFailsAndTheLinesShownAreReplaced() = runTest {
        val objects = Followed({ fresh(1..3) }, refused(ErrorCategory.Unsupported))
        assertEquals(listOf(ListEvent.Snapshot(lines(1..3)), ListEvent.Snapshot(listOf(onConsole))), collected(windows(objects), { 0 }, 3))
    }

    @Test
    fun anEmptyFirstReadHandsOverToTheConsoleOnlyWhereTheConsoleHasLines() = runTest {
        assertEquals(listOf(ListEvent.Snapshot(listOf(onConsole))), collected(windows(Followed({ fresh(1..0) })), { 7 }, 2))
        val objects = Followed({ fresh(1..0) }, { added(1..2) })
        val events = collected(windows(objects), { 0 }, 3)
        assertEquals(listOf(ListEvent.Snapshot(emptyList()), ListEvent.Snapshot(lines(1..2))), events)
        assertTrue(objects.served >= 2)
    }
}
