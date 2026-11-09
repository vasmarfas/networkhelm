package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.rows
import com.vasmarfas.networkhelm.ui.chart.Chart
import com.vasmarfas.networkhelm.ui.chart.Ring
import com.vasmarfas.networkhelm.ui.chart.Series
import com.vasmarfas.networkhelm.ui.chart.bits
import com.vasmarfas.networkhelm.ui.chart.byteSize
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.ScrollArea
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.dashboard_download
import networkhelm.app.shared.generated.resources.dashboard_rates
import networkhelm.app.shared.generated.resources.dashboard_upload
import org.jetbrains.compose.resources.stringResource

internal class DeviceCounts(val total: Int, val paired: Int, val online: Int)

internal class Throughput(val download: Double, val upload: Double)

// the console tells a device that waits for its pairing by a flag; the object model names the status
private fun Item.paired(): Boolean = this["pairing-status"]?.let { it == "paired" } ?: (this["pending"] != "true" && this["remote-pending"] != "true")

// the controller is not connected to itself, yet it answers
internal fun deviceCounts(devices: List<Item>): DeviceCounts {
    val paired = devices.filter { it.paired() }
    return DeviceCounts(devices.size, paired.size, paired.count { it["connected"] == "true" || it["controller"] == "true" })
}

internal fun activeAlerts(rules: List<Item>): List<Item> = rules.filter { (it["devices-on"]?.toIntOrNull() ?: 0) > 0 }.sortedBy { it["name"].orEmpty() }

internal fun availableUpdates(devices: List<Item>): List<Item> = devices.filter { it["upgrade-available"] == "true" }.sortedBy { it["identity"].orEmpty() }

// bits per second of the ports and the wireless interfaces of every device; what they receive is the download
internal fun throughput(rows: List<Item>) = Throughput(
    download = rows.sumOf { it["bandwidth-rx"]?.toDoubleOrNull() ?: 0.0 },
    upload = rows.sumOf { it["bandwidth-tx"]?.toDoubleOrNull() ?: 0.0 },
)

internal fun share(percent: String?): Float? = percent?.removeSuffix("%")?.trim()?.toFloatOrNull()?.coerceIn(0f, 100f)?.div(100f)

internal fun busiest(applications: List<Item>): List<Item> =
    applications.filter { it["name"] != null }.sortedByDescending { (it["last-hour-in"]?.toDoubleOrNull() ?: 0.0) + (it["last-hour-out"]?.toDoubleOrNull() ?: 0.0) }.take(APPLICATIONS)

internal fun summed(rows: List<Item>, key: String): Double = rows.sumOf { it[key]?.toDoubleOrNull() ?: 0.0 }

internal fun counted(rows: List<Item>, key: String): List<Pair<String, Int>> =
    rows.mapNotNull { it[key]?.takeIf(String::isNotEmpty) }.groupingBy { it }.eachCount().toList().sortedWith(compareBy({ -it.second }, { it.first }))

// a lost connection shows in the link strip, and the panels keep what they had
internal fun DeviceSession.rowsAt(path: MenuPath?): Flow<List<Item>> = if (path == null) flowOf(emptyList()) else watch(path).rows().catch { }

private val DEVICES = MenuPath.parse("cmr/device")
private val ALERTS = MenuPath.parse("cmr/alert")
private const val SAMPLES = 120
private const val SAMPLE_MILLIS = 2_000L
private const val APPLICATIONS = 8

// built in the composition, because the descriptions of the router arrive after its menu
private class Sources(tab: RouterTab) {
    val resources = tab.packPath(listOf("Controller", "Devices"), "Resources")
    val ports = tab.packPath(listOf("Controller", "Ports"), "Ports")
    val radios = tab.packPath(listOf("Controller", "WiFi"), "Interfaces")
    val clients = tab.packPath(listOf("Controller", "WiFi"), "Clients")
    val applications = tab.packPath(listOf("Controller", "Traffic"), "Applications")
}

@Composable
internal fun DashboardView(tab: RouterTab) {
    val palette = Theme.palette
    val session = tab.session
    val sources = Sources(tab)
    val downloadName = stringResource(Res.string.dashboard_download)
    val uploadName = stringResource(Res.string.dashboard_upload)
    val download = remember(tab, downloadName) { Series(downloadName, palette.accent, Ring(SAMPLES)) }
    val upload = remember(tab, uploadName) { Series(uploadName, palette.success, Ring(SAMPLES)) }
    val devices by remember(session) { session.rowsAt(DEVICES) }.collectAsStateWithLifecycle(emptyList())
    val alerts by remember(session) { session.rowsAt(ALERTS) }.collectAsStateWithLifecycle(emptyList())
    val resources by remember(session, sources.resources) { session.rowsAt(sources.resources) }.collectAsStateWithLifecycle(emptyList())
    val ports by remember(session, sources.ports) { session.rowsAt(sources.ports) }.collectAsStateWithLifecycle(emptyList())
    val radios by remember(session, sources.radios) { session.rowsAt(sources.radios) }.collectAsStateWithLifecycle(emptyList())
    val clients by remember(session, sources.clients) { session.rowsAt(sources.clients) }.collectAsStateWithLifecycle(emptyList())
    val applications by remember(session, sources.applications) { session.rowsAt(sources.applications) }.collectAsStateWithLifecycle(emptyList())
    val current by rememberUpdatedState(if (ports.isEmpty() && radios.isEmpty()) null else throughput(ports + radios))
    LaunchedEffect(download, upload) {
        while (true) {
            current?.let {
                download.ring.add(it.download)
                upload.ring.add(it.upload)
            }
            delay(SAMPLE_MILLIS)
        }
    }
    ScrollArea(
        Modifier.fillMaxSize().background(palette.surface).testTag("dashboard"),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panels(
            { Section("Devices") { DeviceMeters(deviceCounts(devices)) } },
            { Section("Resources") { ResourceTable(resources) } },
        )
        Section("Bandwidth") {
            Chart(listOf(download, upload), Modifier.fillMaxWidth().height(150.dp).testTag("dashboard-bandwidth"), format = ::bits)
            Rates(download)
            Rates(upload)
        }
        Panels(
            { Section("Active Alerts") { AlertTable(activeAlerts(alerts)) } },
            { Section("Available Updates") { UpdateTable(availableUpdates(devices)) } },
        )
        if (sources.applications != null) Section("Traffic") { TrafficTable(applications) }
        if (sources.radios != null || sources.clients != null) {
            Panels(
                { Section("Access Points") { AccessPoints(radios) } },
                { Section("Stations") { Stations(clients) } },
            )
        }
    }
}

@Composable
private fun Panels(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    if (LocalCompact.current) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            left()
            right()
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.weight(1f)) { left() }
            Box(Modifier.weight(1f)) { right() }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val palette = Theme.palette
    Column(Modifier.fillMaxWidth().testTag("dashboard-section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = palette.muted, style = Theme.type.small)
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
        content()
    }
}

@Composable
private fun Meter(fraction: Float, modifier: Modifier = Modifier, color: Color = Theme.palette.accent) {
    Box(modifier.height(6.dp).background(Theme.palette.line)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

@Composable
private fun DeviceMeters(counts: DeviceCounts) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Meters("Devices Online", counts.online, counts.total, "dashboard-online")
        Meters("Devices Paired", counts.paired, counts.total, "dashboard-paired")
    }
}

@Composable
private fun Meters(label: String, count: Int, total: Int, tag: String) {
    val palette = Theme.palette
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(120.dp), style = Theme.type.strong, maxLines = 1)
        Row(
            Modifier.weight(1f).fillMaxHeight().background(palette.raised).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("$count/$total", Modifier.testTag(tag), style = Theme.type.mono)
            Meter(if (total == 0) 0f else count.toFloat() / total, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Counter(label: String, count: Int, tag: String) {
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(120.dp), style = Theme.type.strong, maxLines = 1)
        Text("$count", Modifier.testTag(tag), style = Theme.type.mono)
    }
}

@Composable
private fun Rates(line: Series) {
    val last = line.ring.last()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(line.color))
        Text(line.name, Modifier.width(80.dp), style = Theme.type.strong, maxLines = 1)
        Text(
            if (last == null) "—" else stringResource(Res.string.dashboard_rates, bits(last), bits(line.ring.average()), bits(line.ring.max())),
            Modifier.testTag("dashboard-rates"),
            style = Theme.type.small,
        )
    }
}

@Composable
private fun Header(vararg cells: Pair<String, Float>) {
    Row(Modifier.fillMaxWidth().background(Theme.palette.raised).padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((title, weight) in cells) Text(title, Modifier.weight(weight), style = Theme.type.strong, maxLines = 1)
    }
}

@Composable
private fun Cells(tag: String, vararg cells: Pair<String, Float>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).semantics(mergeDescendants = true) {}.testTag(tag), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((text, weight) in cells) Text(text, Modifier.weight(weight), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Rows(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 70.dp).background(Theme.palette.surface)) { content() }
}

@Composable
private fun ResourceTable(rows: List<Item>) {
    Column(Modifier.fillMaxWidth()) {
        Header("Device" to 2f, "CPU" to 1.5f, "Memory" to 1.5f, "HDD" to 1.5f)
        Rows {
            for (row in rows.sortedBy { it["device"].orEmpty() }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).semantics(mergeDescendants = true) {}.testTag("dashboard-resource"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row["device"].orEmpty(), Modifier.weight(2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    for (key in listOf("cpu", "memory", "hdd")) Usage(row[key], Modifier.weight(1.5f))
                }
            }
        }
    }
}

@Composable
private fun Usage(percent: String?, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(percent.orEmpty(), Modifier.width(38.dp), style = Theme.type.mono, maxLines = 1)
        share(percent)?.let { Meter(it, Modifier.weight(1f)) }
    }
}

@Composable
private fun AlertTable(rules: List<Item>) {
    Column(Modifier.fillMaxWidth()) {
        Header("Name" to 3f, "Devices" to 1f, "Severity" to 1.2f)
        Rows {
            for (rule in rules) Cells("dashboard-alert", rule["name"].orEmpty() to 3f, rule["devices-on"].orEmpty() to 1f, rule["severity"].orEmpty() to 1.2f)
        }
    }
}

@Composable
private fun UpdateTable(devices: List<Item>) {
    Column(Modifier.fillMaxWidth()) {
        Header("Address" to 1.5f, "Identity" to 2f, "Available Version" to 1.6f)
        Rows {
            for (device in devices) Cells("dashboard-update", device["address"].orEmpty() to 1.5f, device["identity"].orEmpty() to 2f, device["available-version"].orEmpty() to 1.6f)
        }
    }
}

@Composable
private fun TrafficTable(applications: List<Item>) {
    val received = summed(applications, "last-hour-in")
    val sent = summed(applications, "last-hour-out")
    Column(Modifier.fillMaxWidth()) {
        Header("Application" to 2f, "Downloaded" to 2f, "Uploaded" to 2f)
        Rows {
            for (application in busiest(applications)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).semantics(mergeDescendants = true) {}.testTag("dashboard-application"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(application["name"].orEmpty(), Modifier.weight(2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Amount(application["last-hour-in"], received, Modifier.weight(2f))
                    Amount(application["last-hour-out"], sent, Modifier.weight(2f))
                }
            }
        }
    }
}

@Composable
private fun Amount(value: String?, total: Double, modifier: Modifier) {
    val bytes = value?.toDoubleOrNull()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(bytes?.let(::byteSize) ?: value.orEmpty(), Modifier.width(72.dp), style = Theme.type.mono, maxLines = 1)
        if (bytes != null && total > 0.0) Meter((bytes / total).toFloat(), Modifier.weight(1f))
    }
}

@Composable
private fun AccessPoints(rows: List<Item>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Counter("Total", rows.size, "dashboard-ap-total")
        Meters("Running", rows.count { it["running"] == "true" }, rows.size, "dashboard-ap-running")
        Bars(counted(rows, "channel"), rows.size, "dashboard-ap-channel")
    }
}

@Composable
private fun Stations(rows: List<Item>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Counter("Total", rows.size, "dashboard-station-total")
        Bars(counted(rows, "band"), rows.size, "dashboard-station-band")
    }
}

@Composable
private fun Bars(groups: List<Pair<String, Int>>, total: Int, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((name, count) in groups) {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Meter(count.toFloat() / total, Modifier.weight(1f))
                Text("$count", Modifier.width(28.dp), style = Theme.type.mono, maxLines = 1)
            }
        }
    }
}
