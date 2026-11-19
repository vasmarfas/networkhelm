package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SyncLoopTest {
    private val nothing = Round(0, emptyMap(), applied = false, wrote = false)

    @Test
    fun aRoundRunsAtOnceAndThenEachPeriod() = runTest {
        var rounds = 0
        val loop = SyncLoop({ rounds++; nothing })
        backgroundScope.launch { loop.run() }
        runCurrent()
        assertEquals(1, rounds)
        advanceTimeBy(3.minutes)
        runCurrent()
        assertEquals(4, rounds)
    }

    @Test
    fun editsMakeOneRoundOnceTheyStopForTwoSeconds() = runTest {
        var rounds = 0
        val loop = SyncLoop({ rounds++; nothing })
        backgroundScope.launch { loop.run() }
        runCurrent()
        repeat(3) {
            loop.edited()
            advanceTimeBy(1.seconds)
        }
        advanceTimeBy(900.milliseconds)
        assertEquals(1, rounds)
        advanceTimeBy(200.milliseconds)
        assertEquals(2, rounds)
    }

    @Test
    fun aFailedRoundIsTriedAgainAfterLongerPausesUpToThePeriod() = runTest {
        val times = mutableListOf<Long>()
        val loop = SyncLoop({
            times += currentTime
            throw DeviceException(ErrorCategory.ConnectionLost, "dav.example.org does not answer: connection refused")
        })
        backgroundScope.launch { loop.run() }
        advanceTimeBy(4.minutes)
        assertEquals(listOf(0L, 5_000, 15_000, 35_000, 75_000, 135_000, 195_000), times)
        assertTrue(loop.status.value is SyncStatus.Failed)
    }

    @Test
    fun aChangeTheServiceTellsOfMakesARoundAtOnce() = runTest {
        var rounds = 0
        val changes = Channel<Unit>()
        val loop = SyncLoop({ rounds++; nothing })
        backgroundScope.launch { loop.run { changes.receive() } }
        runCurrent()
        assertEquals(1, rounds)
        changes.send(Unit)
        runCurrent()
        assertEquals(2, rounds)
    }
}
