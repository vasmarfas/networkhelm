package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class WatchTest {

    private fun item(id: String, comment: String = "") = Item(id, mapOf("comment" to comment))

    @Test
    fun eventsKeepTheRouterOrder() = runTest {
        val rows = flowOf(
            ListEvent.Snapshot(listOf(item("*1"), item("*2"), item("*3"))),
            ListEvent.Upsert(item("*3"), next = "*1"),
            ListEvent.Upsert(item("*1", "changed")),
            ListEvent.Upsert(item("*4"), next = ""),
            ListEvent.Removed("*2"),
        ).rows().last()
        assertEquals(listOf(item("*3"), item("*1", "changed"), item("*4")), rows)
    }

    @Test
    fun theProgressOfABigTableIsNotARowsList() = runTest {
        val rows = flowOf(ListEvent.Progress(2_000, 5_000), ListEvent.Progress(4_000, 5_000), ListEvent.Snapshot(listOf(item("*1")))).rows().toList()
        assertEquals(listOf(emptyList(), listOf(item("*1"))), rows)
    }

    @Test
    fun aPolledListSendsASnapshotForEachReadingThatDiffers() = runTest {
        val readings = ArrayDeque(
            listOf(
                listOf(item("*1"), item("*2")),
                listOf(item("*1", "1 KiB"), item("*2", "2 KiB")),
                listOf(item("*1", "1 KiB"), item("*2", "2 KiB")),
                listOf(item("*2", "3 KiB")),
            ),
        )
        val events = polled(1.milliseconds) { readings.removeFirst() }.take(3).toList()
        assertEquals(
            listOf(
                ListEvent.Snapshot(listOf(item("*1"), item("*2"))),
                ListEvent.Snapshot(listOf(item("*1", "1 KiB"), item("*2", "2 KiB"))),
                ListEvent.Snapshot(listOf(item("*2", "3 KiB"))),
            ),
            events,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun valuesComeAtTheTurnOfASecond() = runTest {
        val turns = flow {
            while (true) {
                delay(1000 - currentTime % 1000)
                emit(Unit)
            }
        }.shareIn(backgroundScope, SharingStarted.Eagerly)
        val sent = flow {
            emit(1)
            delay(300)
            emit(2)
            delay(100)
            emit(3)
            delay(1700)
            emit(4)
        }
        val seen = mutableListOf<Pair<Int, Long>>()
        sent.onTheSecond(turns).collect { seen += it to currentTime }
        assertEquals(listOf(1 to 0L, 3 to 1000L, 4 to 3000L), seen)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aBurstGoesOutAsItsFirstAndLastValues() = runTest {
        val sent = flow {
            for (value in 1..10) {
                emit(value)
                delay(10)
            }
            delay(500)
            emit(11)
        }
        val seen = mutableListOf<Pair<Int, Long>>()
        sent.atMostEvery(150.milliseconds).collect { seen += it to currentTime }
        assertEquals(listOf(1, 10, 11), seen.map { it.first })
        assertEquals(listOf(0L, 150L, 600L), seen.map { it.second }, "the last of the burst waits out the period")
    }

    @Test
    fun roundsGoOutWholeByTheirSection() = runTest {
        fun row(section: String?, name: String, rate: String) =
            Item("", listOfNotNull(section?.let { ".section" to it }, "name" to name, "rx-bits-per-second" to rate).toMap())
        val sections = flowOf(row("0", "ether1", "1"), row("0", "ether2", "2"), row("1", "ether1", "3"), row("1", "ether2", "4"))
            .rounds { it["name"] }.toList()
        assertEquals(listOf(mapOf("ether1" to "1", "ether2" to "2"), mapOf("ether1" to "3", "ether2" to "4")), sections.map { round -> round.mapValues { it.value.getValue("rx-bits-per-second") } })
        assertEquals(setOf("name", "rx-bits-per-second"), sections.first().getValue("ether1").keys, ".section stays out")

        val plain = flowOf(row(null, "ether1", "5"), row(null, "ether1", "6")).rounds { it["name"] }.toList()
        assertEquals(listOf("5", "6"), plain.map { it.getValue("ether1").getValue("rx-bits-per-second") })
    }
}
