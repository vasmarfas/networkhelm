package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.ui.editor.EditorControls
import com.vasmarfas.networkhelm.ui.editor.EditorState
import com.vasmarfas.networkhelm.ui.editor.Language
import com.vasmarfas.networkhelm.ui.editor.LineEnding
import com.vasmarfas.networkhelm.ui.editor.TextDocument
import com.vasmarfas.networkhelm.ui.editor.TextEditor
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.proxy_config_done
import networkhelm.app.shared.generated.resources.proxy_config_hint
import networkhelm.app.shared.generated.resources.proxy_config_reread
import networkhelm.app.shared.generated.resources.proxy_config_save
import org.jetbrains.compose.resources.stringResource

// a window of the proxies the router tab opens by its kind: the config of sing-box or Xray in the editor
internal class ProxyCall(val kind: String)

// the config of sing-box or Xray whole: the program itself checks it before it takes the place of the old file, and a running service
// starts again with it and gets the old file back when it does not come up
@Composable
internal fun LinuxProxyView(tab: RouterTab, call: ProxyCall) {
    val palette = Theme.palette
    val session = tab.session
    val path = remember(call) { MenuPath.parse("proxy/" + call.kind.removeSuffix("-config")) }
    var editor by remember { mutableStateOf<EditorState?>(null) }
    val controls = remember { EditorControls() }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path, attempt) {
        problem = try {
            val text = session.run(path, "config", emptyMap()).toList().singleOrNull()?.get("text").orEmpty()
            editor = EditorState(TextDocument(text, LineEnding.Lf, cp1251 = false, bom = false, mixed = false), Language.Json)
            null
        } catch (e: DeviceException) {
            e.message
        }
    }
    val shown = editor
    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Button(stringResource(Res.string.proxy_config_save), {
                val text = shown?.snapshot() ?: return@Button
                scope.launch {
                    running = true
                    done = false
                    problem = try {
                        session.run(path, "save-config", mapOf("text" to text)).toList()
                        done = true
                        null
                    } catch (e: DeviceException) {
                        e.message
                    } finally {
                        running = false
                    }
                }
            }, Modifier.testTag("proxy-config-save"), enabled = !running && shown != null, primary = true)
            Button(stringResource(Res.string.proxy_config_reread), { attempt++ }, enabled = !running)
            Text(stringResource(Res.string.proxy_config_hint), color = palette.muted, style = Theme.type.small)
        }
        problem?.let { Text(deviceMessage(it), Modifier.padding(horizontal = 12.dp), color = palette.danger, style = Theme.type.small) }
        if (done) Text(stringResource(Res.string.proxy_config_done), Modifier.padding(horizontal = 12.dp), color = palette.success, style = Theme.type.small)
        if (shown != null) TextEditor(shown, controls, readOnly = running, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}
