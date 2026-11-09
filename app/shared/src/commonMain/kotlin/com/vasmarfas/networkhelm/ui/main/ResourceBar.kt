package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.app.BarItem
import com.vasmarfas.networkhelm.app.LocalSettings
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.onTheSecond
import com.vasmarfas.networkhelm.session.watchSettings
import com.vasmarfas.networkhelm.ui.chart.Chart
import com.vasmarfas.networkhelm.ui.chart.Ring
import com.vasmarfas.networkhelm.ui.chart.Series
import com.vasmarfas.networkhelm.ui.chart.bits
import com.vasmarfas.networkhelm.ui.chart.isoDate
import com.vasmarfas.networkhelm.ui.chart.kibiCount
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.resource_cpu
import networkhelm.app.shared.generated.resources.resource_disk
import networkhelm.app.shared.generated.resources.resource_load
import networkhelm.app.shared.generated.resources.resource_memory
import networkhelm.app.shared.generated.resources.resource_uptime
import kotlin.math.roundToInt

private val PhoneItems = setOf(BarItem.Cpu, BarItem.Memory, BarItem.Uptime)

private val FromResource = setOf(BarItem.Cpu, BarItem.Memory, BarItem.Disk, BarItem.Uptime)

private val ROUTES = MenuPath.parse("ip/route")
private val INTERFACES = MenuPath.parse("interface")

private val MinFlexible = 120.dp
private val MinShown = 72.dp
private const val FLEXIBLE = "flexible"

@Composable
fun ResourceBar(tab: RouterTab) {
    val palette = Theme.palette
    val settings = LocalSettings.current
    val compact = LocalCompact.current
    val openWrt = tab.session.device.platform == Platform.OpenWrt
    val items = settings.barOrder.filter { it !in settings.barHidden && (!compact || it in PhoneItems) }
    val cpu = remember(tab) { Series("CPU", palette.accent, Ring(60)) }
    val memory = remember(tab) { Series("Memory", palette.success, Ring(60)) }
    val received = remember(tab) { Series("Rx", palette.success, Ring(60)) }
    val sent = remember(tab) { Series("Tx", palette.accent, Ring(60)) }
    val resourced = items.any { it in FromResource || openWrt && it == BarItem.Clock }
    val resource by remember(tab, resourced) { if (resourced) resourceOf(tab, openWrt, cpu, memory) else emptyFlow() }.collectAsStateWithLifecycle(null)
    val clocked = !openWrt && BarItem.Clock in items
    val clock by remember(tab, clocked) { if (clocked) clockOf(tab) else emptyFlow() }.collectAsStateWithLifecycle(null)
    val trafficked = BarItem.Traffic in items
    val traffic by remember(tab, trafficked, settings.barInterface) {
        if (trafficked) trafficOf(tab, settings.barInterface.trim(), received, sent) else emptyFlow()
    }.collectAsStateWithLifecycle(null)
    DisableSelection {
        BarLine(Modifier.fillMaxWidth().height(Theme.metrics.row + 4.dp).background(palette.raised).padding(horizontal = 10.dp), if (compact) 10.dp else 14.dp) {
            for (item in items) {
                key(item) {
                    when (item) {
                        BarItem.Router -> RouterName(tab)
                        BarItem.Cpu -> resource?.let { Processor(it, cpu) }
                        BarItem.Memory -> resource?.let { Memory(it, memory) }
                        BarItem.Disk -> resource?.let { Disk(it) }
                        BarItem.Traffic -> traffic?.let { Traffic(it, received, sent) }
                        BarItem.Uptime -> resource?.let { Uptime(it) }
                        BarItem.Clock -> (if (openWrt) resource else clock)?.let { Clock(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarLine(modifier: Modifier, gap: Dp, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val space = gap.roundToPx()
        val height = constraints.maxHeight
        val flexible = measurables.firstOrNull { it.layoutId == FLEXIBLE }
        var room = constraints.maxWidth - if (flexible != null) MinFlexible.roundToPx() else 0
        var standing = if (flexible != null) 1 else 0
        val allowed = LinkedHashMap<Measurable, Int>()
        for (item in measurables) {
            if (item === flexible) continue
            val before = if (standing == 0) 0 else space
            val natural = item.maxIntrinsicWidth(height)
            if (before + natural > room) {
                if (room - before >= MinShown.roundToPx()) allowed[item] = room - before
                break
            }
            allowed[item] = Constraints.Infinity
            standing++
            room -= before + natural
        }
        val placed = allowed.mapValues { (item, width) -> item.measure(Constraints(maxWidth = width, maxHeight = height)) }
        val left = constraints.maxWidth - placed.values.sumOf { it.width } - space * (placed.size + (if (flexible != null) 1 else 0) - 1).coerceAtLeast(0)
        val name = flexible?.measure(Constraints(minWidth = left.coerceAtLeast(0), maxWidth = left.coerceAtLeast(0), maxHeight = height))
        layout(constraints.maxWidth, height) {
            var x = 0
            for (item in measurables) {
                val placeable = (if (item === flexible) name else placed[item]) ?: continue
                placeable.place(x, (height - placeable.height) / 2)
                x += placeable.width + space
            }
        }
    }
}

@Composable
private fun RouterName(tab: RouterTab) {
    val device = tab.session.device
    Row(Modifier.layoutId(FLEXIBLE), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(device.identity, style = Theme.type.strong, maxLines = 1)
        Text(listOf(tab.address, device.architecture, device.board, device.version).filter { it.isNotEmpty() }.joinToString(" / "), Modifier.weight(1f), color = Theme.palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun resourceOf(tab: RouterTab, openWrt: Boolean, cpu: Series, memory: Series): Flow<Item> =
    if (openWrt) {
        tab.session.watchSettings(MenuPath.parse("status/resource")).onTheSecond().onEach { values -> used(values)?.let(memory.ring::add) }.catch { }
    } else {
        tab.session.watchSettings(MenuPath.parse("system/resource"), listOf("uptime", "cpu-load", "free-memory", "total-memory", "free-hdd-space", "total-hdd-space")).onTheSecond()
            .onEach { values ->
                values["cpu-load"]?.toDoubleOrNull()?.let(cpu.ring::add)
                used(values)?.let(memory.ring::add)
            }.catch { }
    }

private fun clockOf(tab: RouterTab): Flow<Item> = tab.session.watchSettings(MenuPath.parse("system/clock"), listOf("date", "time")).onTheSecond().catch { }

private class Rates(val name: String, val received: String, val sent: String)

private fun trafficOf(tab: RouterTab, named: String, received: Series, sent: Series): Flow<Rates> = flow {
    val name = named.ifEmpty { uplink(tab.session) } ?: return@flow
    emitAll(
        tab.session.run(INTERFACES, "monitor-traffic", mapOf("interface" to name)).mapNotNull { row ->
            row["rx-bits-per-second"]?.let { rx -> row["tx-bits-per-second"]?.let { tx -> Rates(name, rx, tx) } }
        },
    )
}.onEach { rates ->
    rates.received.toDoubleOrNull()?.let(received.ring::add)
    rates.sent.toDoubleOrNull()?.let(sent.ring::add)
}.onTheSecond().catch { }

// without an interface of its own the route names it in the gateway: after the %, or as the bare name
private suspend fun uplink(session: DeviceSession): String? {
    val routes = session.watch(ROUTES, Filter.Equals("dst-address", "0.0.0.0/0")).filterIsInstance<ListEvent.Snapshot>().first().items
    val route = routes.firstOrNull { it["active"] == "true" } ?: routes.firstOrNull() ?: return null
    route["interface"]?.takeIf { it.isNotEmpty() }?.let { return it }
    for (key in listOf("immediate-gw", "gateway")) {
        val gateway = route[key].orEmpty()
        gateway.substringAfter('%', "").takeIf { it.isNotEmpty() }?.let { return it }
        if (':' !in gateway && gateway.any { it.isLetter() }) return gateway
    }
    return Regex("via\\s+(\\S+)").find(route["gateway-status"].orEmpty())?.groupValues?.get(1)
}

@Composable
private fun Processor(values: Item, cpu: Series) {
    val usage = values["cpu-load"]
    // the load average, as a Linux host and VyOS tell it
    val load = values["load"]
    if (usage == null && load == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        usage?.let {
            Text("${stringResource(Res.string.resource_cpu)} $it %", maxLines = 1)
            Trend(listOf(cpu), 100.0)
        }
        load?.let { Text("${stringResource(Res.string.resource_load)} $it", maxLines = 1) }
    }
}

@Composable
private fun Memory(values: Item, memory: Series) {
    val free = values["free-memory"]
    val total = values["total-memory"]
    val share = values["memory-usage"] ?: used(values)?.takeIf { LocalCompact.current }?.roundToInt()?.toString()
    val shown = when {
        share != null -> "$share %"
        free != null && total != null -> memory(free, total)
        else -> return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${stringResource(Res.string.resource_memory)} $shown", maxLines = 1)
        Trend(listOf(memory), 100.0)
    }
}

@Composable
private fun Disk(values: Item) {
    val free = values["free-hdd-space"] ?: return
    val total = values["total-hdd-space"] ?: return
    Text("${stringResource(Res.string.resource_disk)} ${bytes(free)} / ${bytes(total)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Traffic(rates: Rates, received: Series, sent: Series) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${rates.name} ↓ ${rate(rates.received)} ↑ ${rate(rates.sent)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        Trend(listOf(received, sent), null)
    }
}

// a no-break space between the label and the value keeps them on one line
@Composable
private fun Uptime(values: Item) {
    val value = values["uptime"] ?: return
    Text("${stringResource(Res.string.resource_uptime)} $value", maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Clock(values: Item) {
    val text = listOfNotNull(values["date"]?.let(::isoDate), values["time"]).joinToString(" ")
    if (text.isNotEmpty()) Text(text, style = Theme.type.mono, maxLines = 1)
}

@Composable
private fun Trend(series: List<Series>, ceiling: Double?) {
    if (!LocalCompact.current) Chart(series, Modifier.size(48.dp, 14.dp), axis = false, ceiling = ceiling)
}

private fun used(values: Item): Double? {
    val free = values["free-memory"]?.toDoubleOrNull()
    val total = values["total-memory"]?.toDoubleOrNull()?.takeIf { it > 0 }
    return if (free != null && total != null) (total - free) / total * 100 else values["memory-usage"]?.toDoubleOrNull()
}

private fun rate(value: String) = value.toDoubleOrNull()?.let(::bits) ?: value

internal fun bytes(value: String): String = value.toDoubleOrNull()?.let(::kibiCount) ?: value

private fun memory(free: String, total: String): String {
    val used = free.toLongOrNull()?.let { total.toLongOrNull()?.minus(it) }
    return listOfNotNull(bytes(free), used?.let { bytes(it.toString()) }, bytes(total)).joinToString(" / ")
}
