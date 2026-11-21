package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.ui.editor.EditorControls
import com.vasmarfas.networkhelm.ui.editor.EditorState
import com.vasmarfas.networkhelm.ui.editor.Language
import com.vasmarfas.networkhelm.ui.editor.LineEnding
import com.vasmarfas.networkhelm.ui.editor.TextDocument
import com.vasmarfas.networkhelm.ui.editor.TextEditor
import com.vasmarfas.networkhelm.ui.files.LocalFileExchange
import com.vasmarfas.networkhelm.ui.form.FormState
import com.vasmarfas.networkhelm.ui.form.misfits
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.Segments
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.vpn_form_clients
import networkhelm.app.shared.generated.resources.vpn_form_create
import networkhelm.app.shared.generated.resources.vpn_form_disguise
import networkhelm.app.shared.generated.resources.vpn_form_dns
import networkhelm.app.shared.generated.resources.vpn_form_done
import networkhelm.app.shared.generated.resources.vpn_form_general
import networkhelm.app.shared.generated.resources.vpn_form_import
import networkhelm.app.shared.generated.resources.vpn_form_keep_session
import networkhelm.app.shared.generated.resources.vpn_form_keys
import networkhelm.app.shared.generated.resources.vpn_form_nat
import networkhelm.app.shared.generated.resources.vpn_form_network
import networkhelm.app.shared.generated.resources.vpn_form_open_file
import networkhelm.app.shared.generated.resources.vpn_form_private
import networkhelm.app.shared.generated.resources.vpn_form_text
import networkhelm.app.shared.generated.resources.vpn_panel_client_config
import networkhelm.app.shared.generated.resources.window_loading
import org.jetbrains.compose.resources.stringResource

// a window of the VPN the router tab opens over a path: a new server of a kind or the import of the configuration of a client
internal class VpnCall(val path: String)

// the configuration of a client the import takes, which the window shows in an editor instead of a field
private const val TEXT = "text"

// one step over a path of settings: the host proposes the values, the tabs of the fields group them, and the command of the path makes
// the server or the connection and starts it; the configuration of a client it gives back opens with its QR code
@Composable
internal fun LinuxVpnView(tab: RouterTab, call: VpnCall) {
    val palette = Theme.palette
    val session = tab.session
    val path = remember(call) { MenuPath.parse(call.path) }
    val form = remember(call) { FormState() }
    val editor = remember(call) { EditorState(TextDocument("", LineEnding.Lf, cp1251 = false, bom = false, mixed = false), Language.Config) }
    val controls = remember { EditorControls() }
    var schema by remember { mutableStateOf<MenuSchema?>(null) }
    var page by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var made by remember { mutableStateOf<String?>(null) }
    var outputs by remember { mutableStateOf(emptyList<Item>()) }
    val scope = rememberCoroutineScope()
    val exchange = LocalFileExchange.current
    LaunchedEffect(path) {
        problem = try {
            val described = session.describe(path)
            form.update(session.get(path, null)?.values.orEmpty())
            schema = described
            null
        } catch (e: DeviceException) {
            e.message
        }
    }
    val current = schema
    Box(Modifier.fillMaxSize().background(palette.surface).testTag("vpn-form")) {
        if (current == null) {
            Text(problem?.let { deviceMessage(it) } ?: stringResource(Res.string.window_loading), Modifier.padding(12.dp), color = if (problem != null) palette.danger else palette.muted)
            return@Box
        }
        val fields = current.arguments.filter { it.name != TEXT }
        val pages = fields.map { it.tab.orEmpty() }.distinct()
        val shown = page?.takeIf { it in pages } ?: pages.firstOrNull().orEmpty()
        val command = current.commands.first { it != "print" }
        val wrong = fields.any { argument -> form.value(argument.name)?.let(argument::misfits) == true }
        Column(Modifier.fillMaxSize().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pages.size > 1) Segments(shown, pages, { pageName(it) }, { page = it }, Modifier.padding(horizontal = 12.dp).testTag("vpn-form-pages"))
            for (argument in fields.filter { it.tab.orEmpty() == shown }) FieldRow(argument, emptyMap(), form, open = true)
            (form.value("client-endpoint") ?: form.value("client-remote"))?.takeIf { shown == "general" && privateAddress(it) }?.let {
                Text(stringResource(Res.string.vpn_form_private, it), Modifier.padding(horizontal = 12.dp).testTag("vpn-form-private"), color = palette.warning, style = Theme.type.small)
            }
            if (current.argument("keep-session") != null) {
                Text(stringResource(Res.string.vpn_form_keep_session), Modifier.padding(horizontal = 12.dp), color = palette.muted, style = Theme.type.small)
            }
            if (current.argument(TEXT) != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.vpn_form_text), Modifier.weight(1f), color = palette.muted, style = Theme.type.small)
                    Button(stringResource(Res.string.vpn_form_open_file), {
                        scope.launch { exchange.pick().firstOrNull()?.let { editor.text.setTextAndPlaceCursorAtEnd(it.read().decodeToString()) } }
                    }, Modifier.testTag("vpn-form-open-file"), enabled = !running && made == null)
                }
                TextEditor(editor, controls, readOnly = running || made != null, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).testTag("vpn-form-text"))
            } else {
                Spacer(Modifier.weight(1f))
            }
            problem?.let { Text(deviceMessage(it), Modifier.padding(horizontal = 12.dp).testTag("problem"), color = palette.danger, style = Theme.type.small) }
            made?.let { Text(stringResource(Res.string.vpn_form_done, it), Modifier.padding(horizontal = 12.dp).testTag("vpn-form-done"), color = palette.success, style = Theme.type.small) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                Button(stringResource(if (command == "import") Res.string.vpn_form_import else Res.string.vpn_form_create), {
                    val values = fields.mapNotNull { argument -> form.value(argument.name)?.let { argument.name to it } }.toMap() +
                        listOfNotNull(current.argument(TEXT)?.let { TEXT to editor.snapshot() })
                    scope.launch {
                        running = true
                        problem = try {
                            val reply = session.run(path, command, values).toList()
                            made = reply.firstOrNull()?.get("name").orEmpty()
                            outputs = reply.filter { it["conf"] != null }
                            null
                        } catch (e: DeviceException) {
                            e.message
                        } finally {
                            running = false
                        }
                    }
                }, Modifier.testTag("vpn-form-run"), enabled = !running && made == null && !wrong, primary = true)
            }
        }
        // a reply of several files, as the profiles of IKEv2, opens them one after another under their names
        outputs.firstOrNull()?.let { next -> Output(next["file"] ?: stringResource(Res.string.vpn_panel_client_config), next["conf"].orEmpty()) { outputs = outputs.drop(1) } }
    }
}

@Composable
private fun pageName(page: String) = stringResource(
    when (page) {
        "network" -> Res.string.vpn_form_network
        "dns" -> Res.string.vpn_form_dns
        "keys" -> Res.string.vpn_form_keys
        "disguise" -> Res.string.vpn_form_disguise
        "nat" -> Res.string.vpn_form_nat
        "clients" -> Res.string.vpn_form_clients
        else -> Res.string.vpn_form_general
    },
)

// an address clients on the internet do not reach: of a private network, of the shared space of carrier NAT, of the link or the host
private fun privateAddress(address: String): Boolean {
    val bytes = address.split('.').mapNotNull { it.toIntOrNull() }
    if (bytes.size == 4) {
        val (a, b) = bytes
        return a == 10 || a == 127 || a == 172 && b in 16..31 || a == 192 && b == 168 || a == 100 && b in 64..127 || a == 169 && b == 254
    }
    val head = address.lowercase()
    return ':' in head && (head.startsWith("fc") || head.startsWith("fd") || head.startsWith("fe80"))
}
