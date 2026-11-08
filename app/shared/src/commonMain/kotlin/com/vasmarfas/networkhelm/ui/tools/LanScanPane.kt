package com.vasmarfas.networkhelm.ui.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.nettools.HostOps
import com.vasmarfas.networkhelm.nettools.Ipv4
import com.vasmarfas.networkhelm.nettools.LAN_LIMIT
import com.vasmarfas.networkhelm.nettools.LanHost
import com.vasmarfas.networkhelm.nettools.LocalNetwork
import com.vasmarfas.networkhelm.nettools.MacAddress
import com.vasmarfas.networkhelm.nettools.MacRegistry
import com.vasmarfas.networkhelm.nettools.WellKnownPorts
import com.vasmarfas.networkhelm.nettools.defaultNetwork
import com.vasmarfas.networkhelm.nettools.millis
import com.vasmarfas.networkhelm.nettools.scanLan
import com.vasmarfas.networkhelm.nettools.scanRange
import com.vasmarfas.networkhelm.nettools.scanSpan
import com.vasmarfas.networkhelm.ui.kit.Labeled
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.Select
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.table.DataTable
import com.vasmarfas.networkhelm.ui.table.TableColumn
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.tools_address
import networkhelm.app.shared.generated.resources.tools_mac_registry_failed
import networkhelm.app.shared.generated.resources.tools_n1_lan_done
import networkhelm.app.shared.generated.resources.tools_n1_lan_none
import networkhelm.app.shared.generated.resources.tools_n1_lan_progress
import networkhelm.app.shared.generated.resources.tools_n1_lan_range
import networkhelm.app.shared.generated.resources.tools_n1_lan_tcp_only
import networkhelm.app.shared.generated.resources.tools_n1_lan_too_many
import networkhelm.app.shared.generated.resources.tools_n1_lan_warning
import networkhelm.app.shared.generated.resources.tools_n1_mac
import networkhelm.app.shared.generated.resources.tools_n1_mac_random
import networkhelm.app.shared.generated.resources.tools_name
import networkhelm.app.shared.generated.resources.tools_network
import networkhelm.app.shared.generated.resources.tools_ping_failed
import networkhelm.app.shared.generated.resources.tools_ports_list
import networkhelm.app.shared.generated.resources.tools_time_ms
import networkhelm.app.shared.generated.resources.tools_vendor

private const val ECHO_TIMEOUT_MILLIS = 1_000
private const val ARP_EVERY = 64

@Composable
internal fun LanScanPane(ops: HostOps?, vendors: suspend () -> MacRegistry) {
    val scope = rememberCoroutineScope()
    val range = remember { TextFieldState() }
    val hosts = remember { mutableStateListOf<LanHost>() }
    val macs = remember { mutableStateMapOf<String, String>() }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var network by remember { mutableStateOf<LocalNetwork?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var withArp by remember { mutableStateOf(false) }
    val networks by produceState(emptyList<LocalNetwork>()) {
        value = try {
            ops?.localNetworks().orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the list only fills in the range, which can be typed
            emptyList()
        }
    }
    val loaded by produceState<Result<MacRegistry>?>(null, withArp) {
        if (!withArp) return@produceState
        value = try {
            Result.success(vendors())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    LaunchedEffect(networks) {
        val preferred = defaultNetwork(networks)
        if (preferred != null && range.text.isEmpty()) {
            network = preferred
            preferred.scanRange()?.let { range.setTextAndPlaceCursorAtEnd(it) }
        }
    }
    val written = range.text.toString()
    val span = remember(written) { scanSpan(written) }
    val addresses = span?.let { (first, last) -> last - first + 1 }
    val tooMany = addresses != null && addresses > LAN_LIMIT
    fun start() {
        val (first, last) = span ?: return
        hosts.clear()
        macs.clear()
        problem = null
        done = 0
        val list = (first..last).map(Ipv4::format)
        total = list.size
        job = scope.launch {
            try {
                withArp = ops?.arp() != null
                val scan = if (ops == null) scanLan(list, null, null) else scanLan(list, { ops.ping(it, ECHO_TIMEOUT_MILLIS) }, { ops.reverse(it) })
                scan.collect { host ->
                    done++
                    if (host != null) hosts += host
                    if (withArp && done % ARP_EVERY == 0) ops?.arp()?.let(macs::putAll)
                }
                if (ops != null) {
                    val table = ops.arp()
                    table?.let(macs::putAll)
                    val seen = hosts.map { it.address }.toSet()
                    coroutineScope {
                        for (address in table.orEmpty().keys) {
                            if (address in seen || Ipv4.parse(address)?.let { it in first..last } != true) continue
                            launch { hosts += LanHost(address, null, emptyList(), ops.reverse(address)) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = getString(Res.string.tools_ping_failed)
            } finally {
                job = null
            }
        }
    }
    Labeled(stringResource(Res.string.tools_n1_lan_range)) {
        TextField(range, Modifier.width(FieldWidth).testTag("lan-range"), placeholder = "192.168.1.0/24", mono = true, error = written.isNotBlank() && (span == null || tooMany))
    }
    if (networks.size > 1) {
        Labeled(stringResource(Res.string.tools_network)) {
            Select(
                network ?: networks.first(),
                networks,
                { "${it.name}  ${it.address}/${it.prefix}" },
                { chosen ->
                    network = chosen
                    chosen.scanRange()?.let { range.setTextAndPlaceCursorAtEnd(it) }
                },
                Modifier.width(FieldWidth).testTag("lan-network"),
            )
        }
    }
    Labeled("") { RunButton(job, span != null && !tooMany, ::start) }
    Text(stringResource(Res.string.tools_n1_lan_warning), color = Theme.palette.muted, style = Theme.type.small)
    if (ops == null) Text(stringResource(Res.string.tools_n1_lan_tcp_only), color = Theme.palette.muted, style = Theme.type.small)
    if (tooMany) Problem(stringResource(Res.string.tools_n1_lan_too_many, addresses, LAN_LIMIT))
    problem?.let { Problem(it) }
    if (loaded?.isFailure == true) Problem(stringResource(Res.string.tools_mac_registry_failed))
    if (total > 0) {
        val status = if (job != null) stringResource(Res.string.tools_n1_lan_progress, done, total, hosts.size) else stringResource(Res.string.tools_n1_lan_done, done, hosts.size)
        Text(status, Modifier.testTag("lan-status"), color = Theme.palette.muted, style = Theme.type.small)
        if (job == null && hosts.isEmpty()) Text(stringResource(Res.string.tools_n1_lan_none))
    }
    if (hosts.isNotEmpty()) {
        val registry = loaded?.getOrNull()
        val random = stringResource(Res.string.tools_n1_mac_random)
        val address = TableColumn<LanHost>("address", stringResource(Res.string.tools_address), 118.dp) { it.address }
        val name = TableColumn<LanHost>("name", stringResource(Res.string.tools_name), 170.dp) { it.name.orEmpty() }
        val mac = TableColumn<LanHost>("mac", stringResource(Res.string.tools_n1_mac), 130.dp) { macs[it.address].orEmpty() }
        val vendor = TableColumn<LanHost>("vendor", stringResource(Res.string.tools_vendor), 170.dp) { vendorOf(registry, macs[it.address], random) }
        val time = TableColumn<LanHost>("time", stringResource(Res.string.tools_time_ms), 76.dp) { host -> host.timeMillis?.let(::millis).orEmpty() }
        val ports = TableColumn<LanHost>("ports", stringResource(Res.string.tools_ports_list), 200.dp) { host ->
            host.ports.joinToString(", ") { port -> WellKnownPorts.service(port)?.let { "$port $it" } ?: port.toString() }
        }
        DataTable(
            rows = hosts.sortedBy { Ipv4.parse(it.address) },
            columns = if (withArp) listOf(address, name, mac, vendor, time, ports) else listOf(address, name, time, ports),
            key = { it.address },
            detail = LocalCompact.current,
            modifier = Modifier.fillMaxWidth().height(TableHeight).testTag("lan-hosts"),
        )
    }
}

private fun vendorOf(registry: MacRegistry?, mac: String?, random: String): String {
    val hex = mac?.let(MacAddress::normalize) ?: return ""
    return if (MacAddress.isLocal(hex)) random else registry?.find(MacAddress.individual(hex))?.vendor.orEmpty()
}
