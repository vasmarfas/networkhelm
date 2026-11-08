package com.vasmarfas.networkhelm.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.appVersion
import com.vasmarfas.networkhelm.nettools.HostOps
import com.vasmarfas.networkhelm.nettools.MacRegistry
import com.vasmarfas.networkhelm.nettools.PublicHttp
import com.vasmarfas.networkhelm.nettools.hostOps
import com.vasmarfas.networkhelm.nettools.publicHttp
import com.vasmarfas.networkhelm.transport.tcpListening
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.Labeled
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.ScrollArea
import com.vasmarfas.networkhelm.ui.kit.Select
import com.vasmarfas.networkhelm.ui.kit.dialogPanel
import com.vasmarfas.networkhelm.ui.kit.hand
import com.vasmarfas.networkhelm.ui.kit.scrim
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.tools_close
import networkhelm.app.shared.generated.resources.tools_dns
import networkhelm.app.shared.generated.resources.tools_ip_info
import networkhelm.app.shared.generated.resources.tools_lan_scan
import networkhelm.app.shared.generated.resources.tools_mac
import networkhelm.app.shared.generated.resources.tools_ping
import networkhelm.app.shared.generated.resources.tools_ports
import networkhelm.app.shared.generated.resources.tools_ports_ref
import networkhelm.app.shared.generated.resources.tools_speed
import networkhelm.app.shared.generated.resources.tools_split
import networkhelm.app.shared.generated.resources.tools_start
import networkhelm.app.shared.generated.resources.tools_stop
import networkhelm.app.shared.generated.resources.tools_subnet
import networkhelm.app.shared.generated.resources.tools_title
import networkhelm.app.shared.generated.resources.tools_traceroute
import networkhelm.app.shared.generated.resources.tools_transfer
import networkhelm.app.shared.generated.resources.tools_wake
import networkhelm.app.shared.generated.resources.tools_whois

internal val FieldWidth = 260.dp
internal val NumberWidth = 100.dp
internal val TableHeight = 280.dp

enum class NetTool(val title: StringResource) {
    Ping(Res.string.tools_ping),
    Traceroute(Res.string.tools_traceroute),
    Ports(Res.string.tools_ports),
    LanScan(Res.string.tools_lan_scan),
    Dns(Res.string.tools_dns),
    Whois(Res.string.tools_whois),
    IpInfo(Res.string.tools_ip_info),
    Speed(Res.string.tools_speed),
    Wake(Res.string.tools_wake),
    Subnet(Res.string.tools_subnet),
    Split(Res.string.tools_split),
    TransferTime(Res.string.tools_transfer),
    PortsRef(Res.string.tools_ports_ref),
    Mac(Res.string.tools_mac),
}

suspend fun bundledVendors(): MacRegistry = withContext(Dispatchers.Default) {
    MacRegistry.parse(Res.readBytes("files/mac-vendors.tsv").decodeToString())
}

// traceroute and Wake-on-LAN need the system of this device, the socket tools need TCP and UDP, which a browser has not,
// the tools that ask a service of the internet need HTTPS with the certificate check of the system
@Composable
fun ToolsDialog(vendors: suspend () -> MacRegistry = ::bundledVendors, onClose: () -> Unit) {
    val palette = Theme.palette
    val shape = RoundedCornerShape(Theme.metrics.radius)
    val ops = remember { hostOps() }
    val http = remember { publicHttp("NetworkHelm/$appVersion") }
    val tools = NetTool.entries.filter {
        when (it) {
            NetTool.Ping, NetTool.LanScan -> ops != null || tcpListening
            NetTool.Traceroute -> ops != null
            NetTool.Wake -> ops?.broadcasts == true
            NetTool.Ports, NetTool.Dns -> tcpListening
            NetTool.Whois -> http != null || tcpListening
            NetTool.IpInfo, NetTool.Speed -> http != null
            NetTool.Subnet, NetTool.Split, NetTool.TransferTime, NetTool.PortsRef, NetTool.Mac -> true
        }
    }
    var chosen by remember { mutableStateOf(tools.first()) }
    val compact = LocalCompact.current
    BoxWithConstraints(Modifier.fillMaxSize().scrim(palette.workspace.copy(alpha = 0.6f), onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 900.dp).height(minOf(maxHeight, 700.dp)).padding(if (compact) 8.dp else 16.dp).dialogPanel(if (compact) 10.dp else 16.dp).testTag("tools-dialog"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.tools_title), Modifier.weight(1f), style = Theme.type.strong)
                IconButton(Lucide.X, stringResource(Res.string.tools_close), onClose, Modifier.testTag("tools-cross"))
            }
            if (compact) {
                Select(chosen, tools, { stringResource(it.title) }, { chosen = it }, Modifier.fillMaxWidth().testTag("tool-choice"))
                Pane(chosen, ops, http, vendors, Modifier.weight(1f))
            } else {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.width(180.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (tool in tools) {
                            DisableSelection {
                                Text(
                                    stringResource(tool.title),
                                    Modifier.fillMaxWidth().clip(shape).background(if (tool == chosen) palette.selection else palette.surface)
                                        .clickable { chosen = tool }.hand().padding(horizontal = 10.dp, vertical = 6.dp).testTag("tool-${tool.name}"),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Pane(chosen, ops, http, vendors, Modifier.weight(1f))
                }
            }
            Row(Modifier.align(Alignment.End)) { Button(stringResource(Res.string.tools_close), onClose, Modifier.testTag("tools-close"), primary = true) }
        }
    }
}

@Composable
private fun Pane(tool: NetTool, ops: HostOps?, http: PublicHttp?, vendors: suspend () -> MacRegistry, modifier: Modifier) {
    ScrollArea(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        key(tool) {
            when (tool) {
                NetTool.Ping -> PingPane(ops)
                NetTool.Traceroute -> ops?.let { TraceroutePane(it) }
                NetTool.Ports -> PortsPane()
                NetTool.LanScan -> LanScanPane(ops, vendors)
                NetTool.Dns -> DnsPane()
                NetTool.Whois -> WhoisPane(http)
                NetTool.IpInfo -> http?.let { IpInfoPane(it, ops) }
                NetTool.Speed -> http?.let { SpeedTestPane(it) }
                NetTool.Wake -> ops?.let { WakePane(it) }
                NetTool.Subnet -> SubnetPane()
                NetTool.Split -> SplitPane()
                NetTool.TransferTime -> TransferPane()
                NetTool.PortsRef -> PortsReferencePane()
                NetTool.Mac -> MacPane(vendors)
            }
        }
    }
}

@Composable
internal fun RunButton(job: Job?, enabled: Boolean, onStart: () -> Unit) {
    if (job != null) {
        Button(stringResource(Res.string.tools_stop), { job.cancel() }, Modifier.testTag("tool-stop"))
    } else {
        Button(stringResource(Res.string.tools_start), onStart, Modifier.testTag("tool-start"), enabled = enabled, primary = true)
    }
}

@Composable
internal fun Fact(label: String, value: String) {
    Labeled(label) { Text(value, style = Theme.type.mono) }
}

@Composable
internal fun Problem(text: String) {
    Text(text, Modifier.testTag("tool-problem"), color = Theme.palette.danger, style = Theme.type.small)
}
