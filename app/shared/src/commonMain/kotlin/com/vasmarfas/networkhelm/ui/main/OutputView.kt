package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.app.HiddenValue
import com.vasmarfas.networkhelm.app.LocalSettings
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.sensitive
import com.vasmarfas.networkhelm.packs.PackAction
import com.vasmarfas.networkhelm.qr.QrCode
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.QrImage
import com.vasmarfas.networkhelm.ui.kit.ScrollArea
import com.vasmarfas.networkhelm.ui.kit.dialogPanel
import com.vasmarfas.networkhelm.ui.kit.rememberCopier
import com.vasmarfas.networkhelm.ui.kit.scrim
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.flow.toList
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.form_hide_secret
import networkhelm.app.shared.generated.resources.form_show_secret
import networkhelm.app.shared.generated.resources.output_close
import networkhelm.app.shared.generated.resources.output_copy

// the configuration of a WireGuard client starts with an empty line on RouterOS 7.25
internal suspend fun DeviceSession.output(path: MenuPath, action: PackAction, ids: List<String>, property: String): String =
    action.runs(ids).flatMap { arguments -> run(path, action.command, arguments).toList().mapNotNull { it[property]?.trim() } }.joinToString("\n\n")

@Composable
internal fun Output(title: String, text: String, onClose: () -> Unit) {
    val palette = Theme.palette
    val copier = rememberCopier()
    val hiding = LocalSettings.current.hidePasswords && masked(text) != text
    var shown by remember(text) { mutableStateOf(!hiding) }
    val code = remember(text) {
        try {
            QrCode.encode(text)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().scrim(palette.workspace.copy(alpha = 0.5f), onClose), contentAlignment = Alignment.Center) {
        val wide = maxWidth >= 600.dp
        Column(
            Modifier.widthIn(max = 720.dp).heightIn(max = maxHeight - 16.dp).dialogPanel(14.dp).testTag("output"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = Theme.type.strong)
                IconButton(Lucide.X, stringResource(Res.string.output_close), onClose, Modifier.testTag("output-cross"))
            }
            val qr = @Composable { if (code != null && shown) QrImage(code, Modifier.size(220.dp)) }
            val lines = @Composable { SelectionContainer { Text(if (shown) text else masked(text), style = Theme.type.mono) } }
            ScrollArea(Modifier.weight(1f, fill = false)) {
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        qr()
                        lines()
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        qr()
                        lines()
                    }
                }
            }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hiding) {
                    IconButton(if (shown) Lucide.EyeOff else Lucide.Eye, stringResource(if (shown) Res.string.form_hide_secret else Res.string.form_show_secret), { shown = !shown })
                }
                Button(stringResource(Res.string.output_copy), { copier.copy(text) })
                Button(stringResource(Res.string.output_close), onClose, primary = true)
            }
        }
    }
}

private val line = Regex("^(\\s*)([A-Za-z]+)(\\s*=\\s*)(.+)$")

private fun masked(text: String): String = text.lines().joinToString("\n") { row ->
    val match = line.matchEntire(row) ?: return@joinToString row
    val (indent, name, equals) = match.destructured
    if (sensitive(name.replace(Regex("(?<=[a-z])(?=[A-Z])"), "-").lowercase())) "$indent$name$equals$HiddenValue" else row
}
