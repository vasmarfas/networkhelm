package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.ssh.Forwarding
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.TcpListener
import com.vasmarfas.networkhelm.transport.listenTcp
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.kit.hand
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.list_remove
import networkhelm.app.shared.generated.resources.tunnels_address
import networkhelm.app.shared.generated.resources.tunnels_browser
import networkhelm.app.shared.generated.resources.tunnels_connections
import networkhelm.app.shared.generated.resources.tunnels_failed
import networkhelm.app.shared.generated.resources.tunnels_forget
import networkhelm.app.shared.generated.resources.tunnels_hint
import networkhelm.app.shared.generated.resources.tunnels_local
import networkhelm.app.shared.generated.resources.tunnels_open
import networkhelm.app.shared.generated.resources.tunnels_port
import networkhelm.app.shared.generated.resources.tunnels_refused
import networkhelm.app.shared.generated.resources.tunnels_saved

@Stable
class Tunnel(val host: String, val port: Int, val wanted: Int) {
    internal var opening: Job? = null
    var forwarding by mutableStateOf<Forwarding?>(null)
    var failure by mutableStateOf<String?>(null)
}

@Stable
class Tunnels(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val listen: suspend (String, Int) -> TcpListener = ::listenTcp,
) {
    val all = mutableStateListOf<Tunnel>()

    // bound to 127.0.0.1: on any other address, whoever reaches the port gets the router's side of the tunnel
    fun open(ssh: SshSession, localPort: Int, host: String, port: Int) {
        val tunnel = Tunnel(host, port, localPort)
        all += tunnel
        tunnel.opening = scope.launch(start = CoroutineStart.LAZY) {
            var listener: TcpListener? = null
            try {
                withContext(NonCancellable) { listener = listen("127.0.0.1", localPort) }
                ensureActive()
                val forwarding = Forwarding(ssh, checkNotNull(listener), host, port)
                tunnel.forwarding = forwarding
                forwarding.serve()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tunnel.failure = e.message ?: e::class.simpleName
            } finally {
                tunnel.forwarding?.close() ?: listener?.close()
            }
        }
        tunnel.opening?.start()
    }

    fun close(tunnel: Tunnel) {
        tunnel.opening?.cancel()
        tunnel.forwarding?.close()
        all -= tunnel
    }

    fun closeAll() {
        all.toList().forEach(::close)
        scope.cancel()
    }
}

@Composable
internal fun TunnelsView(tab: RouterTab) {
    val palette = Theme.palette
    val ssh = tab.session.ssh ?: return
    val scope = rememberCoroutineScope()
    val local = remember { TextFieldState("0") }
    val host = remember { TextFieldState("127.0.0.1") }
    val port = remember { TextFieldState("80") }
    val localPort = local.text.toString().trim().toIntOrNull()?.takeIf { it in 0..65535 }
    val remotePort = port.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 }
    val address = host.text.toString().trim()
    LaunchedEffect(tab) { tab.readTunnels() }
    fun open(tunnel: SavedTunnel) {
        tab.tunnels.open(ssh, tunnel.localPort, tunnel.host, tunnel.port)
        scope.launch { tab.keepTunnel(tunnel) }
    }
    val closed = tab.savedTunnels.filter { saved -> tab.tunnels.all.none { it.wanted == saved.localPort && it.host == saved.host && it.port == saved.port } }
    Column(Modifier.fillMaxSize().background(palette.surface).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.tunnels_hint), color = palette.muted, style = Theme.type.small)
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.tunnels_local), color = palette.muted)
            TextField(local, Modifier.width(80.dp).testTag("tunnel-local"), mono = true, error = localPort == null)
            Text("→", color = palette.muted)
            TextField(host, Modifier.width(180.dp).testTag("tunnel-host"), mono = true, placeholder = stringResource(Res.string.tunnels_address))
            Text(":", color = palette.muted)
            TextField(port, Modifier.width(80.dp).testTag("tunnel-port"), mono = true, placeholder = stringResource(Res.string.tunnels_port), error = remotePort == null)
            Button(stringResource(Res.string.tunnels_open), {
                if (localPort != null && remotePort != null) open(SavedTunnel(localPort, address, remotePort))
            }, Modifier.testTag("tunnel-open"), enabled = localPort != null && remotePort != null && address.isNotEmpty(), primary = true)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(tab.tunnels.all.size) { index ->
                val tunnel = tab.tunnels.all[index]
                TunnelLine(tunnel) { tab.tunnels.close(tunnel) }
            }
            if (closed.isNotEmpty()) {
                item { Text(stringResource(Res.string.tunnels_saved), Modifier.padding(top = 8.dp), style = Theme.type.strong, color = palette.muted) }
                items(closed.size) { index ->
                    val saved = closed[index]
                    SavedLine(saved, { open(saved) }) { scope.launch { tab.forgetTunnel(saved) } }
                }
            }
        }
    }
}

@Composable
private fun SavedLine(tunnel: SavedTunnel, onOpen: () -> Unit, onForget: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("saved-tunnel"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("127.0.0.1:${tunnel.localPort} → ${tunnel.host}:${tunnel.port}", style = Theme.type.mono, color = Theme.palette.muted)
        Spacer(Modifier.weight(1f))
        Button(stringResource(Res.string.tunnels_open), onOpen, Modifier.testTag("saved-tunnel-open"))
        IconButton(Lucide.X, stringResource(Res.string.tunnels_forget), onForget, Modifier.testTag("saved-tunnel-forget"))
    }
}

@Composable
private fun TunnelLine(tunnel: Tunnel, onClose: () -> Unit) {
    val palette = Theme.palette
    val forwarding = tunnel.forwarding
    val browser = LocalUriHandler.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("tunnel"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("127.0.0.1:${forwarding?.localPort ?: tunnel.wanted} → ${tunnel.host}:${tunnel.port}", style = Theme.type.mono)
        val failure = tunnel.failure
        if (forwarding != null && failure == null) {
            val connections by forwarding.connections.collectAsState()
            val refused by forwarding.refused.collectAsState()
            Text(stringResource(Res.string.tunnels_connections, connections), color = palette.muted, style = Theme.type.small)
            val scheme = when (tunnel.port) {
                80, 8080, 8000 -> "http"
                443, 8443 -> "https"
                else -> null
            }
            if (scheme != null) {
                Text(
                    stringResource(Res.string.tunnels_browser),
                    Modifier.clickable { browser.openUri("$scheme://127.0.0.1:${forwarding.localPort}") }.hand(),
                    color = palette.accent,
                    style = Theme.type.small,
                )
            }
            refused?.let { Text(stringResource(Res.string.tunnels_refused, it), color = palette.danger, style = Theme.type.small, maxLines = 1) }
        }
        failure?.let { Text(stringResource(Res.string.tunnels_failed, tunnel.wanted, it), color = palette.danger, style = Theme.type.small, maxLines = 2) }
        Spacer(Modifier.weight(1f))
        IconButton(Lucide.X, stringResource(Res.string.list_remove), onClose, Modifier.testTag("tunnel-close"))
    }
}
