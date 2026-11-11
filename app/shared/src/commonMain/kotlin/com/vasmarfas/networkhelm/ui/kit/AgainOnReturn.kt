package com.vasmarfas.networkhelm.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val RETURN = 15.seconds

// the system holds an app that is out of sight, and a request made then loses its connection as the app comes back:
// a read that lost it within that time is made again without a press of Retry
@Composable
fun AgainOnReturn(lost: Boolean, again: () -> Unit) {
    val onScreen = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    val back = remember(onScreen) { TimeSource.Monotonic.markNow() }
    val read by rememberUpdatedState(again)
    LaunchedEffect(onScreen, lost) {
        if (onScreen && lost && back.elapsedNow() < RETURN) {
            delay(1.seconds)
            read()
        }
    }
}
