package com.vasmarfas.networkhelm.app

import androidx.compose.ui.window.ApplicationScope
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val QUIT_MILLIS = 2_000L

// a read from a socket does not see cancellation: an open session would keep the process after its last window
fun ApplicationScope.quit() {
    thread(isDaemon = true) {
        Thread.sleep(QUIT_MILLIS)
        exitProcess(0)
    }
    exitApplication()
}
