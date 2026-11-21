package com.vasmarfas.networkhelm.ui.sync

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CloudAlert
import com.composables.icons.lucide.CloudCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.vasmarfas.networkhelm.sync.SyncStatus
import com.vasmarfas.networkhelm.ui.kit.Hint
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.hand
import com.vasmarfas.networkhelm.ui.theme.Theme
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.sync_mark_done
import networkhelm.app.shared.generated.resources.sync_mark_failed
import networkhelm.app.shared.generated.resources.sync_mark_running

// the way from a toolbar to the window of synchronisation
internal class SyncAccess(val synchronisation: Synchronisation, val open: () -> Unit)

internal val LocalSync = staticCompositionLocalOf<SyncAccess?> { null }

// beside the settings, sized as the buttons there: synchronised, running or failed, in one of the places as well; nothing while
// synchronisation is off on this device
@Composable
internal fun SyncMark() {
    val access = LocalSync.current ?: return
    val synchronisation = access.synchronisation
    val setup by synchronisation.setup.collectAsState()
    if (setup == null) return
    val palette = Theme.palette
    val material = Theme.material
    val rounds by synchronisation.running.collectAsState()
    val trouble by synchronisation.trouble.collectAsState()
    val status = rounds?.let { it.status.collectAsState().value }
    val failed = trouble != null || status is SyncStatus.Failed || (status as? SyncStatus.Done)?.round?.failed?.isNotEmpty() == true
    val icon = when {
        failed -> Lucide.CloudAlert
        status == SyncStatus.Running -> Lucide.RefreshCw
        else -> Lucide.CloudCheck
    }
    val description = stringResource(
        when {
            failed -> Res.string.sync_mark_failed
            status == SyncStatus.Running -> Res.string.sync_mark_running
            else -> Res.string.sync_mark_done
        },
    )
    Hint(description) {
        UnstyledButton(
            onClick = access.open,
            indication = LocalIndication.current,
            modifier = Modifier
                .then(if (material) Modifier.size(Theme.metrics.control) else Modifier.size(if (LocalCompact.current) 40.dp else 28.dp, Theme.metrics.row))
                .clip(if (material) RoundedCornerShape(50) else RoundedCornerShape(Theme.metrics.radius))
                .hand()
                .testTag("sync-mark"),
        ) {
            UnstyledIcon(icon, description, Modifier.size(if (material) Theme.metrics.icon else 14.dp), tint = if (failed) palette.danger else palette.muted)
        }
    }
}
