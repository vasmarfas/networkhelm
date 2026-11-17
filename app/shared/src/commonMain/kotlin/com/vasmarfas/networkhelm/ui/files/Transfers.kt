package com.vasmarfas.networkhelm.ui.files

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Upload
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.ToolbarButton
import com.vasmarfas.networkhelm.ui.main.deviceText
import com.vasmarfas.networkhelm.ui.terminal.sshFailure
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.files_failed
import networkhelm.app.shared.generated.resources.files_transfer_cancel
import networkhelm.app.shared.generated.resources.files_transfer_cancelled
import networkhelm.app.shared.generated.resources.files_transfer_clear
import networkhelm.app.shared.generated.resources.files_transfer_done
import networkhelm.app.shared.generated.resources.files_transfer_remove
import networkhelm.app.shared.generated.resources.files_transfer_retry
import networkhelm.app.shared.generated.resources.files_transfer_running
import networkhelm.app.shared.generated.resources.files_transfer_waiting
import networkhelm.app.shared.generated.resources.files_transfers
import kotlin.time.TimeSource

sealed interface TransferState {
    data object Waiting : TransferState
    data object Running : TransferState
    class Done(val bytes: Long, val millis: Long) : TransferState
    class Failed(val message: String) : TransferState
    data object Cancelled : TransferState
}

@Stable
class Transfer(val name: String, val upload: Boolean, val run: suspend () -> Long) {
    var state by mutableStateOf<TransferState>(TransferState.Waiting)
    var job: Job? = null
}

// the transfers of the file windows of a router, by path: they go on while a window is minimized or another router is shown,
// and end with the session
class TransferQueues {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queues = HashMap<String, Transfers>()

    fun of(path: String): Transfers = queues.getOrPut(path) { Transfers(scope) }

    fun close() = scope.cancel()
}

val LocalTransfers = staticCompositionLocalOf<TransferQueues?> { null }

@Stable
class Transfers(private val scope: CoroutineScope) {
    val list = mutableStateListOf<Transfer>()

    fun add(name: String, upload: Boolean, run: suspend () -> Long) {
        val transfer = Transfer(name, upload, run)
        list += transfer
        start(transfer)
    }

    fun start(transfer: Transfer) {
        transfer.job = scope.launch {
            transfer.state = TransferState.Running
            val begun = TimeSource.Monotonic.markNow()
            try {
                transfer.state = TransferState.Done(transfer.run(), begun.elapsedNow().inWholeMilliseconds)
            } catch (e: CancellationException) {
                transfer.state = TransferState.Cancelled
                throw e
            } catch (e: Exception) {
                transfer.state = TransferState.Failed(if (e is NoChannelException) bulkText(e.why, e.port) else getString(Res.string.files_failed, sshFailure(e)?.let { getString(it) } ?: e.message?.let { deviceText(it) } ?: e.toString()))
            }
        }
    }

    fun cancel(transfer: Transfer) {
        transfer.job?.cancel()
    }

    fun clear() {
        list.removeAll { it.state !is TransferState.Waiting && it.state !is TransferState.Running }
    }
}

@Composable
internal fun TransferPanel(transfers: Transfers, modifier: Modifier = Modifier) {
    val palette = Theme.palette
    Column(modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp).testTag("transfers"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.files_transfers), Modifier.weight(1f), style = Theme.type.strong)
            ToolbarButton(stringResource(Res.string.files_transfer_clear), null, { transfers.clear() })
        }
        Column(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (transfer in transfers.list) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UnstyledIcon(if (transfer.upload) Lucide.Upload else Lucide.Download, null, Modifier.size(14.dp), tint = palette.muted)
                    Text(transfer.name, Modifier.weight(1f), singleLine = true, overflow = TextOverflow.Ellipsis)
                    when (val state = transfer.state) {
                        TransferState.Waiting -> Text(stringResource(Res.string.files_transfer_waiting), style = Theme.type.small, color = palette.muted)
                        TransferState.Running -> {
                            Indeterminate()
                            Text(stringResource(Res.string.files_transfer_running), style = Theme.type.small, color = palette.muted)
                        }
                        is TransferState.Done -> Text(
                            stringResource(Res.string.files_transfer_done, sizeText(state.bytes), "${state.millis / 100 / 10.0} s"),
                            style = Theme.type.small,
                            color = palette.success,
                        )
                        is TransferState.Failed -> Text(state.message, Modifier.weight(2f), style = Theme.type.small, color = palette.danger, singleLine = true, overflow = TextOverflow.Ellipsis)
                        TransferState.Cancelled -> Text(stringResource(Res.string.files_transfer_cancelled), style = Theme.type.small, color = palette.muted)
                    }
                    when (transfer.state) {
                        TransferState.Waiting, TransferState.Running -> IconButton(Lucide.X, stringResource(Res.string.files_transfer_cancel), { transfers.cancel(transfer) })
                        is TransferState.Failed, TransferState.Cancelled -> {
                            IconButton(Lucide.RefreshCw, stringResource(Res.string.files_transfer_retry), { transfers.start(transfer) })
                            IconButton(Lucide.X, stringResource(Res.string.files_transfer_remove), { transfers.list -= transfer })
                        }
                        is TransferState.Done -> IconButton(Lucide.X, stringResource(Res.string.files_transfer_remove), { transfers.list -= transfer })
                    }
                }
            }
        }
    }
}

@Composable
private fun Indeterminate() {
    val palette = Theme.palette
    val phase by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart))
    Canvas(Modifier.size(80.dp, 3.dp)) {
        val round = CornerRadius(size.height / 2)
        drawRoundRect(palette.line, size = size, cornerRadius = round)
        val length = size.width * 0.35f
        val start = (size.width + length) * phase - length
        drawRoundRect(palette.accent, Offset(start.coerceAtLeast(0f), 0f), Size((start + length).coerceAtMost(size.width) - start.coerceAtLeast(0f), size.height), round)
    }
}
