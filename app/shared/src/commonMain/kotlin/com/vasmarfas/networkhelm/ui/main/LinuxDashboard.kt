package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.ui.chart.Chart
import com.vasmarfas.networkhelm.ui.chart.Ring
import com.vasmarfas.networkhelm.ui.chart.Series
import com.vasmarfas.networkhelm.ui.chart.bits
import com.vasmarfas.networkhelm.ui.chart.byteSize
import com.vasmarfas.networkhelm.ui.kit.ScrollArea
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.flow.catch
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.dashboard_download
import networkhelm.app.shared.generated.resources.dashboard_upload
import networkhelm.app.shared.generated.resources.host_architecture
import networkhelm.app.shared.generated.resources.host_by_cpu
import networkhelm.app.shared.generated.resources.host_by_memory
import networkhelm.app.shared.generated.resources.host_cores
import networkhelm.app.shared.generated.resources.host_cpu
import networkhelm.app.shared.generated.resources.host_disks
import networkhelm.app.shared.generated.resources.host_dns
import networkhelm.app.shared.generated.resources.host_failed
import networkhelm.app.shared.generated.resources.host_firewall
import networkhelm.app.shared.generated.resources.host_firewall_off
import networkhelm.app.shared.generated.resources.host_gateway
import networkhelm.app.shared.generated.resources.host_kernel
import networkhelm.app.shared.generated.resources.host_listening
import networkhelm.app.shared.generated.resources.host_load
import networkhelm.app.shared.generated.resources.host_memory
import networkhelm.app.shared.generated.resources.host_network
import networkhelm.app.shared.generated.resources.host_no_failed
import networkhelm.app.shared.generated.resources.host_nobody
import networkhelm.app.shared.generated.resources.host_os
import networkhelm.app.shared.generated.resources.host_processes
import networkhelm.app.shared.generated.resources.host_processor
import networkhelm.app.shared.generated.resources.host_reboot
import networkhelm.app.shared.generated.resources.host_running
import networkhelm.app.shared.generated.resources.host_services
import networkhelm.app.shared.generated.resources.host_swap
import networkhelm.app.shared.generated.resources.host_system
import networkhelm.app.shared.generated.resources.host_temperatures
import networkhelm.app.shared.generated.resources.host_time
import networkhelm.app.shared.generated.resources.host_uptime
import networkhelm.app.shared.generated.resources.host_users
import networkhelm.app.shared.generated.resources.host_virtualization
import networkhelm.app.shared.generated.resources.window_loading
import org.jetbrains.compose.resources.stringResource

private val HOST_STATUS = MenuPath.parse("status")
private val HOST_INTERFACES = MenuPath.parse("interface")
private const val HOST_SAMPLES = 120

// the host at a glance: the system with its load over the last minutes, the network with the rates of the way out, the disks,
// the services, the busiest processes and who is logged in; one read of status every poll, the rates each second
@Composable
internal fun LinuxDashboardView(tab: RouterTab) {
    val palette = Theme.palette
    val session = tab.session
    val status by remember(session) { session.rowsAt(HOST_STATUS) }.collectAsStateWithLifecycle(emptyList())
    val state = status.firstOrNull()
    val cpu = remember(tab) { Series("CPU", palette.accent, Ring(HOST_SAMPLES)) }
    LaunchedEffect(state) { state?.get("cpu-load")?.toDoubleOrNull()?.let(cpu.ring::add) }
    val downloadName = stringResource(Res.string.dashboard_download)
    val uploadName = stringResource(Res.string.dashboard_upload)
    val receiving = remember(tab, downloadName) { Series(downloadName, palette.accent, Ring(HOST_SAMPLES)) }
    val sending = remember(tab, uploadName) { Series(uploadName, palette.success, Ring(HOST_SAMPLES)) }
    val way = state?.get("gateway-interface")?.ifEmpty { null }
    LaunchedEffect(way, receiving, sending) {
        if (way == null) return@LaunchedEffect
        session.run(HOST_INTERFACES, "monitor-traffic", mapOf("interface" to way)).catch { }.collect { rates ->
            rates["rx-bits-per-second"]?.toDoubleOrNull()?.let(receiving.ring::add)
            rates["tx-bits-per-second"]?.toDoubleOrNull()?.let(sending.ring::add)
        }
    }
    ScrollArea(
        Modifier.fillMaxSize().background(palette.surface).testTag("linux-dashboard"),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state == null) {
            Text(stringResource(Res.string.window_loading), color = palette.muted, style = Theme.type.small)
        } else {
            Panels(
                {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Section(stringResource(Res.string.host_system)) { HostSystem(state, cpu) }
                        Section(stringResource(Res.string.host_network)) { HostNetwork(state, receiving, sending) }
                        Section(stringResource(Res.string.host_disks)) { HostDisks(state) }
                    }
                },
                {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Section(stringResource(Res.string.host_services)) { HostServices(state) }
                        Section(stringResource(Res.string.host_processes)) { HostProcesses(state) }
                        Section(stringResource(Res.string.host_users)) {
                            Text(state["users"]?.ifEmpty { null } ?: stringResource(Res.string.host_nobody), style = Theme.type.small)
                        }
                        val temperatures = state.groupedBy("temperature.")
                        if (temperatures.isNotEmpty()) Section(stringResource(Res.string.host_temperatures)) {
                            Facts({ it }, *temperatures.values.map { it["zone"].orEmpty() to it["celsius"]?.let { degrees -> "$degrees °C" } }.toTypedArray())
                        }
                    }
                },
            )
        }
    }
}

// the fields disk.<n>.<field> of the status as a map of n to its fields, in their order
private fun Item.groupedBy(prefix: String): Map<String, Map<String, String>> =
    values.filterKeys { it.startsWith(prefix) }.entries
        .groupBy({ it.key.removePrefix(prefix).substringBeforeLast('.') }, { it.key.substringAfterLast('.') to it.value })
        .mapValues { it.value.toMap() }

private fun fraction(used: Double?, total: Double?): Float? = if (used != null && total != null && total > 0) (used / total).toFloat() else null

@Composable
private fun HostSystem(state: Item, cpu: Series) {
    val total = state["total-memory"]?.toDoubleOrNull()
    val used = total?.let { all -> state["free-memory"]?.toDoubleOrNull()?.let { all - it } }
    val swap = state["total-swap"]?.toDoubleOrNull()?.takeIf { it > 0 }
    val swapped = swap?.let { all -> state["free-swap"]?.toDoubleOrNull()?.let { all - it } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chart(listOf(cpu), Modifier.fillMaxWidth().height(90.dp).testTag("linux-dashboard-cpu"), format = { "${it.toInt()} %" })
        Load(stringResource(Res.string.host_cpu), state["cpu-load"]?.toFloatOrNull()?.div(100f), state["cpu-load"]?.let { "$it %" })
        Load(stringResource(Res.string.host_memory), fraction(used, total), if (used != null) "${byteSize(used)} / ${byteSize(total)}" else null)
        if (swap != null) Load(stringResource(Res.string.host_swap), fraction(swapped, swap), swapped?.let { "${byteSize(it)} / ${byteSize(swap)}" })
        Facts(
            { it },
            stringResource(Res.string.host_load) to state["load"],
            stringResource(Res.string.host_os) to state["version"],
            stringResource(Res.string.host_kernel) to state["kernel"],
            stringResource(Res.string.host_architecture) to state["architecture-name"],
            stringResource(Res.string.host_virtualization) to state["virtualization"],
            stringResource(Res.string.host_processor) to (state["cpu-model"]?.ifEmpty { null }?.let { model -> state["board-name"]?.ifEmpty { null }?.let { "$model ($it)" } ?: model } ?: state["board-name"]),
            stringResource(Res.string.host_cores) to state["cpu-count"],
            stringResource(Res.string.host_uptime) to state["uptime"],
            stringResource(Res.string.host_time) to state["time"],
        )
    }
}

@Composable
private fun HostNetwork(state: Item, receiving: Series, sending: Series) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state["gateway-interface"].isNullOrEmpty()) {
            Text(state["gateway-interface"].orEmpty(), style = Theme.type.strong)
            Chart(listOf(receiving, sending), Modifier.fillMaxWidth().height(100.dp).testTag("linux-dashboard-rates"), format = ::bits)
            Rates(receiving)
            Rates(sending)
        }
        Facts(
            { it },
            *state.groupedBy("net.").map { (name, fields) -> name to fields["addresses"] }.toTypedArray(),
            stringResource(Res.string.host_gateway) to state["gateway"],
            stringResource(Res.string.host_dns) to state["dns-servers"],
        )
    }
}

@Composable
private fun HostDisks(state: Item) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (disk in state.groupedBy("disk.").values) {
            val size = disk["size"]?.toDoubleOrNull()
            val used = disk["used"]?.toDoubleOrNull()
            Load(disk["mount"].orEmpty(), fraction(used, size), if (used != null && size != null) "${byteSize(used)} / ${byteSize(size)}" else null)
        }
    }
}

@Composable
private fun HostServices(state: Item) {
    val palette = Theme.palette
    val failed = state["failed"].orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state["reboot-required"] == "true") Text(stringResource(Res.string.host_reboot), color = palette.warning, style = Theme.type.small)
        if (failed.isEmpty()) {
            Text(stringResource(Res.string.host_no_failed), color = palette.success, style = Theme.type.small)
        } else {
            Text("${stringResource(Res.string.host_failed)}: $failed", color = palette.danger, style = Theme.type.small, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Facts(
            { it },
            stringResource(Res.string.host_running) to state["running-services"]?.takeIf { it != "0" },
            stringResource(Res.string.host_listening) to state["listening"],
            stringResource(Res.string.host_firewall) to (state["firewall"]?.ifEmpty { null } ?: stringResource(Res.string.host_firewall_off)),
        )
    }
}

@Composable
private fun HostProcesses(state: Item) {
    val palette = Theme.palette
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((title, prefix) in listOf(stringResource(Res.string.host_by_cpu) to "top-cpu.", stringResource(Res.string.host_by_memory) to "top-memory.")) {
            val processes = state.groupedBy(prefix).values
            if (processes.isEmpty()) continue
            Text(title, color = palette.muted, style = Theme.type.small)
            for (process in processes) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(process["name"].orEmpty(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(process["pid"].orEmpty(), Modifier.width(64.dp), color = palette.muted, style = Theme.type.mono, maxLines = 1)
                    Text("${process["cpu"].orEmpty()} %", Modifier.width(64.dp), style = Theme.type.mono, maxLines = 1)
                    Text(process["memory"]?.toDoubleOrNull()?.let(::byteSize).orEmpty(), Modifier.width(90.dp), style = Theme.type.mono, maxLines = 1)
                }
            }
        }
    }
}
