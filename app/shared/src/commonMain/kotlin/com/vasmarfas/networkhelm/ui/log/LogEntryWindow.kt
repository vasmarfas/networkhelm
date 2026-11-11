package com.vasmarfas.networkhelm.ui.log

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.ui.kit.rememberCopier
import com.vasmarfas.networkhelm.ui.workspace.WindowState
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

@Stable
class LogEntryWindow(val path: MenuPath, val rows: List<LogRow>, val lines: List<LogEntry>, start: Int, val clock: RouterClock) {
    var index by mutableIntStateOf(start)

    val row: LogRow get() = rows[index]

    val title: String
        get() {
            val entry = row.last
            val message = entry.message.trim()
            val cut = message.lastIndexOf(' ', TITLE_LENGTH).takeIf { it > 0 } ?: TITLE_LENGTH
            val head = if (message.length > TITLE_LENGTH) message.take(cut).trimEnd() + "…" else message
            return listOf(entry.stamp?.clock ?: entry.time, head).filter { it.isNotEmpty() }.joinToString(" ")
        }

    private companion object {
        const val TITLE_LENGTH = 48
    }
}

class LogRequest(val path: MenuPath, val id: String)

@Stable
class LogWindows(val open: (LogEntryWindow) -> Unit, val show: (MenuPath, String) -> Unit) {
    var request by mutableStateOf<LogRequest?>(null)
}

val LocalLogWindows = staticCompositionLocalOf<LogWindows?> { null }

@Composable
internal fun LogEntryView(entry: LogEntryWindow, window: WindowState, windows: LogWindows) {
    val copier = rememberCopier()
    val tick by produceState(0) {
        while (true) {
            delay(15.seconds)
            value += 1
        }
    }
    val now = remember(tick, entry.clock) { entry.clock.now }
    val row = entry.row
    LaunchedEffect(entry.index) { window.title = entry.title }
    Box(Modifier.fillMaxSize().testTag("log-entry-${window.id}")) {
        LogDetails(
            row,
            remember(entry.lines, row) { entry.lines.around(row.last) },
            now,
            onPrevious = if (entry.index > 0) ({ entry.index-- }) else null,
            onNext = if (entry.index < entry.rows.lastIndex) ({ entry.index++ }) else null,
            onCopy = { copier.copy(it) },
            modifier = Modifier.fillMaxSize(),
            onShow = { windows.show(entry.path, row.first.id) },
        )
    }
}
