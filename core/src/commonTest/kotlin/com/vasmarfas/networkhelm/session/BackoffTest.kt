package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class BackoffTest {

    private val time = TestTimeSource()
    private val backoff = Backoff(time)
    private val gone = DeviceException(ErrorCategory.ConnectionLost, "gone")
    private var attempts = 0

    private suspend fun waited(open: suspend () -> Unit = {}): Duration {
        val before = attempts
        var waited = Duration.ZERO
        while (attempts == before) {
            runCatching { backoff.opening { attempts++.also { open() } } }
            if (attempts == before) {
                time += 1.seconds
                waited += 1.seconds
            }
        }
        return waited
    }

    @Test
    fun theFirstLossIsOpenedAgainAtOnce() = runTest {
        waited()
        backoff.lost(gone)
        assertEquals(Duration.ZERO, waited())
    }

    @Test
    fun aChannelThatDropsAgainAndAgainIsWaitedForLongerEachTimeUpToFiveMinutes() = runTest {
        waited()
        val waits = List(9) {
            backoff.lost(gone)
            waited()
        }
        assertEquals(listOf(0, 5, 10, 20, 40, 80, 160, 300, 300).map { it.seconds }, waits)
    }

    @Test
    fun theLongestWaitCanBeShortened() = runTest {
        val quick = Backoff(time, 30.seconds)
        repeat(9) {
            runCatching { quick.opening { error("down") } }
            time += 31.seconds
        }
        val before = attempts
        quick.opening { attempts++ }
        assertEquals(before + 1, attempts)
    }

    @Test
    fun whileItWaitsTheLastFailureIsThrownAndNothingIsOpened() = runTest {
        waited()
        repeat(2) {
            backoff.lost(gone)
            waited()
        }
        backoff.lost(gone)
        val before = attempts
        repeat(3) { assertSame(gone, assertFailsWith<DeviceException> { backoff.opening { attempts++ } }) }
        assertEquals(before, attempts)
    }

    @Test
    fun aChannelThatLivedForTwoMinutesStartsTheCountAnew() = runTest {
        waited()
        repeat(2) {
            backoff.lost(gone)
            waited()
        }
        time += 2.minutes
        backoff.lost(gone)
        assertEquals(Duration.ZERO, waited())
    }

    @Test
    fun aLossIsCountedOncePerChannel() = runTest {
        waited()
        repeat(5) { backoff.lost(gone) }
        assertEquals(Duration.ZERO, waited())
    }

    @Test
    fun anOpenThatFailsIsCountedAsWell() = runTest {
        val waits = List(5) { waited { throw DeviceException(ErrorCategory.PermissionDenied, "refused") } }
        assertEquals(listOf(0, 0, 5, 10, 20).map { it.seconds }, waits)
    }

    @Test
    fun anOpenThatDidNotReachTheRouterHoldsBackNoNextOne() = runTest {
        val reaching = Backoff(time) { it is DeviceException }
        repeat(5) { runCatching { reaching.opening { attempts++.also { error("network is unreachable") } } } }
        assertEquals(5, attempts)
        repeat(3) { runCatching { reaching.opening { attempts++.also { throw DeviceException(ErrorCategory.PermissionDenied, "refused") } } } }
        assertEquals(7, attempts)
    }

    @Test
    fun aCancelledOpenIsNotAFailure() = runTest {
        assertFailsWith<CancellationException> { backoff.opening { throw CancellationException("cancelled") } }
        assertEquals(Duration.ZERO, waited())
    }
}
