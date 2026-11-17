package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.model.DeviceException
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
import networkhelm.app.shared.generated.resources.window_loading
import org.jetbrains.compose.resources.stringResource

private val STATUS = MenuPath.parse("status")
private val INTERFACES = MenuPath.parse("interface")
private val ACCESS_POINTS = MenuPath.parse("wifi")
private const val KEENETIC_SAMPLES = 120

// the System Dashboard of the web interface of Keenetic: the internet with its rates, the home networks with their Wi-Fi,
// the ports and the system, in the words of the router; all of it is one read of status, the rates come each second
@Composable
internal fun KeeneticDashboardView(tab: RouterTab) {
    val palette = Theme.palette
    val session = tab.session
    val word = { text: String -> tab.words[text] ?: text }
    val status by remember(session) { session.rowsAt(STATUS) }.collectAsStateWithLifecycle(emptyList())
    val protections by produceState(emptyMap<String, String>(), session) {
        value = try {
            session.describe(ACCESS_POINTS).argument("security")?.labels.orEmpty()
        } catch (e: DeviceException) {
            emptyMap()
        }
    }
    val state = status.firstOrNull()
    val receiving = remember(tab) { Series(word("Receiving"), palette.accent, Ring(KEENETIC_SAMPLES)) }
    val sending = remember(tab) { Series(word("Sending"), palette.success, Ring(KEENETIC_SAMPLES)) }
    val connection = state?.get("connection-id")?.ifEmpty { null }
    LaunchedEffect(connection) {
        if (connection == null) return@LaunchedEffect
        session.run(INTERFACES, "monitor-traffic", mapOf("interface" to connection)).catch { }.collect { rates ->
            rates["rx-bits-per-second"]?.toDoubleOrNull()?.let(receiving.ring::add)
            rates["tx-bits-per-second"]?.toDoubleOrNull()?.let(sending.ring::add)
        }
    }
    ScrollArea(
        Modifier.fillMaxSize().background(palette.surface).testTag("keenetic-dashboard"),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state == null) {
            Text(stringResource(Res.string.window_loading), color = palette.muted, style = Theme.type.small)
        } else {
            val apps = state.grouped("app.")
            val radios = state.grouped("radio.")
            val top = state.grouped("top.")
            Panels(
                {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Section(word("Internet")) { Internet(state, receiving, sending, word) }
                        Section(word("About the System")) { AboutSystem(state, word) }
                        if (apps.isNotEmpty()) Section(word("Applications")) { Applications(apps, word) }
                    }
                },
                {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Section(word("My Networks")) { HomeNetworks(state, protections, word) }
                        Section(word("Network Ports")) { SwitchPorts(state, word) }
                        if (radios.isNotEmpty()) Section(word("Wi-Fi Monitor")) { Radios(radios, state["extenders"], word) }
                        if (top.isNotEmpty()) Section(word("Traffic Monitor")) { TopHosts(top, word) }
                    }
                },
            )
        }
    }
}

// the fields port.<label>.<field> of the status as a map of the labels to their fields
private fun Item.grouped(prefix: String): Map<String, Map<String, String>> =
    values.filterKeys { it.startsWith(prefix) }.entries
        .groupBy({ it.key.removePrefix(prefix).substringBeforeLast('.') }, { it.key.substringAfterLast('.') to it.value })
        .mapValues { it.value.toMap() }

@Composable
private fun Internet(state: Item, receiving: Series, sending: Series, word: (String) -> String) {
    val palette = Theme.palette
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).background(if (state["internet"] == "true") palette.success else palette.danger, CircleShape))
            Text(listOf(state["connection"], state["carrier"]?.takeIf { it != state["connection"] }, state["connection-type"]).filterNot { it.isNullOrEmpty() }.joinToString(" · "), style = Theme.type.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Chart(listOf(receiving, sending), Modifier.fillMaxWidth().height(120.dp).testTag("keenetic-dashboard-rates"), format = ::bits)
        Rates(receiving)
        Rates(sending)
        Facts(
            word,
            "Connected For" to state["connected"],
            "IP Address" to state["address"],
            "Mask" to state["mask"],
            "Gateway" to state["remote"],
            "MAC Address" to state["mac"],
            "DNS Servers" to state["dns-servers"],
            "KeenDNS Name" to state["keendns"],
            "Content Filter" to state["content-filter"]?.ifEmpty { word("Disabled") },
            "Received" to state["rxbytes"]?.toDoubleOrNull()?.let(::byteSize),
            "Sent" to state["txbytes"]?.toDoubleOrNull()?.let(::byteSize),
        )
    }
}

@Composable
private fun HomeNetworks(state: Item, protections: Map<String, String>, word: (String) -> String) {
    val palette = Theme.palette
    val points = state.grouped("ap.")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((id, network) in state.grouped("network.")) {
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("keenetic-dashboard-network"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(network["name"].orEmpty(), style = Theme.type.strong, maxLines = 1)
                    Text(network["address"].orEmpty(), color = palette.muted, style = Theme.type.small)
                }
                Text("${word("Wireless")}: ${network["wifi"].orEmpty()}    ${word("Wired")}: ${network["wired"].orEmpty()}", style = Theme.type.small)
                for (point in points.values.filter { it["network"] == id }) {
                    val up = point["up"] == "true"
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).background(if (up) palette.success else palette.faint, CircleShape))
                        Text(point["ssid"].orEmpty(), Modifier.width(150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (up) listOf(point["band"], point["channel"]?.let { "${word("Channel")} $it" }, point["security"]?.let { protections[it] ?: it }).filterNot { it.isNullOrEmpty() }.joinToString(", ") else word("Wi-Fi Disabled"),
                            color = palette.muted,
                            style = Theme.type.small,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchPorts(state: Item, word: (String) -> String) {
    val palette = Theme.palette
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((label, port) in state.grouped("port.")) {
            val up = port["link"] == "up"
            Column(Modifier.width(76.dp).semantics(mergeDescendants = true) {}.testTag("keenetic-dashboard-port"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier.size(44.dp).background(if (up) palette.selection else palette.raised, RoundedCornerShape(4.dp)).border(1.dp, if (up) palette.accent else palette.line, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = Theme.type.strong)
                }
                Text(if (up) listOf(port["speed"], if (port["duplex"] == "full") "FDX" else port["duplex"]?.uppercase()).filterNot { it.isNullOrEmpty() }.joinToString(" ") else "—", style = Theme.type.small, maxLines = 1)
                if (port["internet"] == "true") Text(word("Internet"), color = palette.accent, style = Theme.type.small, maxLines = 1)
            }
        }
    }
}

@Composable
private fun AboutSystem(state: Item, word: (String) -> String) {
    val used = state["used-memory"]?.toDoubleOrNull()
    val total = state["total-memory"]?.toDoubleOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Load(word("CPU"), state["cpu-load"]?.toFloatOrNull()?.div(100f), state["cpu-load"]?.let { "$it %" })
        Load(word("RAM"), if (used != null && total != null && total > 0) (used / total).toFloat() else null, if (used != null && total != null) "${byteSize(used)} / ${byteSize(total)}" else null)
        Facts(
            word,
            "Model" to state["model"],
            "KeeneticOS" to state["firmware"],
            "Update Channel" to state["channel"]?.let(word),
            "Uptime" to state["uptime"],
            "Router Time" to state["time"],
            "Active Connections" to state["connections"]?.let { now -> state["connections-max"]?.let { "$now / $it" } ?: now },
            "Clients Online" to state["clients-online"],
            "Registered Clients" to state["clients-registered"],
            "Auto-Update" to state["auto-update"]?.let { word(if (it == "true") "Enabled" else "Disabled") },
            "Access from the Internet" to state["remote-access"]?.let { word(if (it == "true") "Enabled" else "Disabled") },
            "Operating Mode (IPv4)" to state["keendns-access"]?.let {
                when (it) {
                    "cloud" -> word("Cloud Access")
                    "direct" -> word("Direct Access")
                    else -> it
                }
            },
            "Service Tag" to state["service-tag"],
            "Serial Number" to state["serial"],
        )
    }
}

@Composable
private fun Applications(apps: Map<String, Map<String, String>>, word: (String) -> String) {
    Facts(word, *apps.values.map { it["name"].orEmpty() to word(if (it["enabled"] == "true") "Enabled" else "Disabled") }.toTypedArray())
}

@Composable
private fun Radios(radios: Map<String, Map<String, String>>, extenders: String?, word: (String) -> String) {
    val palette = Theme.palette
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (radio in radios.values) {
            val load = radio["load"]?.toFloatOrNull()
            Load(radio["band"].orEmpty(), load?.div(100f), load?.let { "${radio["load"]} %" })
            Text(
                listOfNotNull(
                    radio["channel"]?.ifEmpty { null }?.let { "${word("Channel")} $it" },
                    radio["width"]?.ifEmpty { null }?.let { "$it MHz" },
                    radio["clients"]?.let { "${word("Clients")}: $it" },
                    radio["temperature"]?.ifEmpty { null }?.let { "$it °C" },
                ).joinToString(" · "),
                color = palette.muted,
                style = Theme.type.small,
            )
        }
        Facts(word, "Extenders" to extenders)
    }
}

// the hosts that sent and took the most in the last three minutes; the router sums up the rest as one line without a name
@Composable
private fun TopHosts(hosts: Map<String, Map<String, String>>, word: (String) -> String) {
    val palette = Theme.palette
    fun bytes(host: Map<String, String>, field: String) = host[field]?.toDoubleOrNull() ?: 0.0
    val rx = hosts.values.sumOf { bytes(it, "rx") }
    val tx = hosts.values.sumOf { bytes(it, "tx") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (rx + tx == 0.0) {
            Text(word("No data available"), color = palette.muted, style = Theme.type.small)
        } else {
            Text("${word("Total traffic in 3 minutes:")} ↓ ${byteSize(rx)}  ↑ ${byteSize(tx)}", style = Theme.type.small)
            for (host in hosts.values.filter { bytes(it, "rx") + bytes(it, "tx") > 0 }) {
                val sum = bytes(host, "rx") + bytes(host, "tx")
                Load(host["name"]?.ifEmpty { null } ?: word("Other"), (sum / (rx + tx)).toFloat(), byteSize(sum))
            }
        }
    }
}

@Composable
internal fun Load(title: String, fraction: Float?, text: String?) {
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.width(120.dp), style = Theme.type.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Meter(fraction ?: 0f, Modifier.weight(1f))
        Text(text.orEmpty(), Modifier.width(150.dp), style = Theme.type.mono, maxLines = 1)
    }
}

// the facts the router gave, under their names; one it did not give leaves no empty line
@Composable
internal fun Facts(word: (String) -> String, vararg facts: Pair<String, String?>) {
    val palette = Theme.palette
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((title, value) in facts.filterNot { it.second.isNullOrEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(word(title), Modifier.width(160.dp), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value.orEmpty(), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
