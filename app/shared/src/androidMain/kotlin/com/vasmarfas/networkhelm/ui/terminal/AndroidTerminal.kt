package com.vasmarfas.networkhelm.ui.terminal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory

@Composable
fun AndroidTerminal(shell: ShellChannel, modifier: Modifier) {
    val palette = Theme.palette
    val keys = remember(shell) { Channel<ByteArray>(Channel.UNLIMITED) }
    val mouse = remember(shell) { TerminalMouse() }
    val emulator = remember(shell) {
        TerminalEmulatorFactory.create(
            defaultForeground = palette.text,
            defaultBackground = palette.surface,
            onKeyboardInput = { keys.trySend(it) },
            onResize = { size -> shell.resize(size.columns, size.rows) },
        )
    }
    LaunchedEffect(shell) {
        launch { for (bytes in keys) shell.write(bytes) }
        val buffer = ByteArray(8192)
        while (true) {
            val read = shell.read(buffer, 0, buffer.size)
            if (read < 0) break
            mouse.read(buffer, read)
            emulator.writeInput(buffer, 0, read)
        }
    }
    // the keyboard the terminal raised would stay over the menu after its window closes
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(shell) { onDispose { keyboard?.hide() } }
    // termlib keeps the mouse to itself: a tap goes to the program that asked for the mouse as a click in its cell, and the library still
    // gets the tap for the keyboard and the selection
    Box(
        modifier.pointerInput(emulator) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                val grid = emulator.dimensions
                if (!mouse.wanted || grid.columns <= 0 || grid.rows <= 0 || (up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                val width = if (grid.widthPixels > 0) grid.widthPixels.toFloat() else size.width.toFloat()
                val height = if (grid.heightPixels > 0) grid.heightPixels.toFloat() else size.height.toFloat()
                val column = (up.position.x * grid.columns / width).toInt().coerceIn(0, grid.columns - 1) + 1
                val row = (up.position.y * grid.rows / height).toInt().coerceIn(0, grid.rows - 1) + 1
                keys.trySend(mouse.click(column, row))
            }
        },
    ) {
        Terminal(emulator, Modifier.fillMaxSize(), backgroundColor = palette.surface, foregroundColor = palette.text, keyboardEnabled = true)
    }
}

// termlib 0.3 has a scrollback size of its own and takes none from outside
internal actual val terminalScrollback = false
