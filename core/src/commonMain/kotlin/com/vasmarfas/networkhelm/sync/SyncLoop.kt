package com.vasmarfas.networkhelm.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// what the app shows of synchronisation: a round running, or how the last one ended and when
sealed interface SyncStatus {
    data object Waiting : SyncStatus

    data object Running : SyncStatus

    class Done(val at: Long, val round: Round) : SyncStatus

    class Failed(val at: Long, val error: Throwable) : SyncStatus
}

private val QUIET = 2.seconds
private val FIRST_PAUSE = 5.seconds

// the rounds of a device: one at the start, one when edits have stopped for two seconds, one at once when the app comes to the front or
// the user asks, one at each change the service tells of, and else one each period. After a failed round the next waits twice as long
// as after the one before, from 5 s up to the period
class SyncLoop(private val round: suspend () -> Round, private val clock: Clock = Clock.System) {
    // a minute while the app is in front, more behind it
    var period: Duration = 1.minutes

    private val nudges = Channel<Boolean>(Channel.CONFLATED)
    private val current = MutableStateFlow<SyncStatus>(SyncStatus.Waiting)
    val status: StateFlow<SyncStatus> = current.asStateFlow()

    fun edited() {
        nudges.trySend(true)
    }

    fun now() {
        nudges.trySend(false)
    }

    // until cancelled; watch returns at each change of the files where the service tells of them, as the gateway does
    suspend fun run(watch: (suspend () -> Unit)? = null) = coroutineScope {
        if (watch != null) {
            launch {
                while (true) {
                    try {
                        watch()
                        now()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        delay(period)
                    }
                }
            }
        }
        var pause = Duration.ZERO
        while (true) {
            pause = next(pause)
            val edit = withTimeoutOrNull(if (pause == Duration.ZERO) period else pause) { nudges.receive() }
            if (edit == true) {
                while (withTimeoutOrNull(QUIET) { nudges.receive() } != null) continue
            }
        }
    }

    // the pause before the next round: none after one that went through, else twice the last one, from 5 s up to the period
    private suspend fun next(pause: Duration): Duration {
        current.value = SyncStatus.Running
        return try {
            val done = round()
            current.value = SyncStatus.Done(clock.now().toEpochMilliseconds(), done)
            Duration.ZERO
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            current.value = SyncStatus.Failed(clock.now().toEpochMilliseconds(), e)
            if (pause == Duration.ZERO) FIRST_PAUSE else minOf(pause * 2, period)
        }
    }
}
