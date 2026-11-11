package com.vasmarfas.networkhelm.ui.workspace

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import kotlin.math.absoluteValue

private val MinSize = DpSize(160.dp, 90.dp)

@Stable
class WindowState internal constructor(val id: Long, title: String, position: DpOffset, size: DpSize) {
    var title by mutableStateOf(title)
    var position by mutableStateOf(position)
    var size by mutableStateOf(size)
    var minimized by mutableStateOf(false)
    var maximized by mutableStateOf(false)

    var closeGuard: (() -> Boolean)? = null

    var sized = false
        private set

    // x: -1 for the left edge, 1 for the right; y: 1 for the bottom edge; 0 leaves that size alone
    fun resize(x: Int, y: Int, dx: Dp, dy: Dp) {
        sized = true
        val width = when (x) {
            1 -> max(MinSize.width, size.width + dx)
            -1 -> max(MinSize.width, size.width - dx).coerceAtMost(size.width + position.x)
            else -> size.width
        }
        if (x == -1) position = DpOffset(position.x + size.width - width, position.y)
        size = DpSize(width, if (y == 1) max(MinSize.height, size.height + dy) else size.height)
    }
}

@Stable
class WorkspaceState(private val closed: (Long) -> Unit = {}) {
    val windows = mutableStateListOf<WindowState>()
    private var nextId = 0L

    val active: WindowState? get() = windows.lastOrNull { !it.minimized }

    var viewport by mutableStateOf<DpSize?>(null)

    fun open(title: String, position: DpOffset, size: DpSize): WindowState {
        val room = viewport?.let { DpSize(max(MinSize.width, it.width - position.x - 8.dp), max(MinSize.height, it.height - position.y - 8.dp)) }
        val fitted = if (room == null) size else DpSize(min(size.width, room.width), min(size.height, room.height))
        return WindowState(nextId++, title, position, fitted).also { windows += it }
    }

    fun fit(window: WindowState, more: Dp) {
        val area = viewport
        // less than a dp is the rounding of pixels, and following it would make the window tremble
        if (area == null || window.sized || window.maximized || more.value.absoluteValue < 1f) return
        val room = max(MinSize.height, area.height - window.position.y - 8.dp)
        window.size = DpSize(window.size.width, (window.size.height + more).coerceIn(MinSize.height, room))
    }

    fun close(window: WindowState) {
        if (window.closeGuard?.invoke() == false) {
            // what keeps a minimized window open is seen only in the window
            if (window.minimized) bringToFront(window)
            return
        }
        windows -= window
        closed(window.id)
    }

    fun bringToFront(window: WindowState) {
        window.minimized = false
        if (windows.lastOrNull() !== window) {
            windows -= window
            windows += window
        }
    }

    fun minimize(window: WindowState) {
        window.minimized = true
        windows -= window
        windows.add(0, window)
    }

    fun switchToPrevious() {
        if (windows.size >= 2) bringToFront(windows[windows.size - 2])
    }

    fun raiseBottom() {
        if (windows.size >= 2) bringToFront(windows.first())
    }
}
