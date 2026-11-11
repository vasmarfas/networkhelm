package com.vasmarfas.networkhelm.session

import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val FIRST_WAIT = 5.seconds
private val STEADY = 2.minutes

// each login costs the router a key exchange, so the first failure is retried at once and the next ones at growing intervals;
// an open that failed before it reached the router cost it nothing and holds back no next one
class Backoff(
    private val time: TimeSource = TimeSource.Monotonic,
    private val longest: Duration = 5.minutes,
    private val reached: (Exception) -> Boolean = { true },
) {
    private var failures = 0
    private var since: TimeMark? = null
    private var waiting: Pair<TimeMark, Throwable>? = null

    suspend fun <T> opening(open: suspend () -> T): T {
        waiting?.let { (until, cause) -> if (until.hasNotPassedNow()) throw cause }
        val channel = try {
            open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (reached(e)) failed(e)
            throw e
        }
        since = time.markNow()
        return channel
    }

    fun lost(cause: Throwable) {
        if (since != null) failed(cause)
    }

    private fun failed(cause: Throwable) {
        if (since?.let { it.elapsedNow() >= STEADY } == true) failures = 0
        since = null
        failures++
        waiting = time.markNow() + wait() to cause
    }

    private fun wait(): Duration = if (failures < 2) Duration.ZERO else minOf(longest, FIRST_WAIT * (1 shl minOf(failures - 2, 6)))
}
