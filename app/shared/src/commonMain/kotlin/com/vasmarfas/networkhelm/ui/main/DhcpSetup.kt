package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.journal.EditKind
import com.vasmarfas.networkhelm.journal.command
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.Labeled
import com.vasmarfas.networkhelm.ui.kit.ScrollArea
import com.vasmarfas.networkhelm.ui.kit.Select
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.dhcp_setup_dns
import networkhelm.app.shared.generated.resources.dhcp_setup_done
import networkhelm.app.shared.generated.resources.dhcp_setup_gateway
import networkhelm.app.shared.generated.resources.dhcp_setup_interface
import networkhelm.app.shared.generated.resources.dhcp_setup_intro
import networkhelm.app.shared.generated.resources.dhcp_setup_lease
import networkhelm.app.shared.generated.resources.dhcp_setup_network
import networkhelm.app.shared.generated.resources.dhcp_setup_none
import networkhelm.app.shared.generated.resources.dhcp_setup_pool
import networkhelm.app.shared.generated.resources.form_apply

internal class DhcpPlan(val network: String, val gateway: String, val pool: String, val dns: String)

internal fun dhcpPlan(address: String): DhcpPlan? {
    val host = address.substringBefore('/')
    val length = address.substringAfter('/', "32").toIntOrNull()?.takeIf { it in 1..30 } ?: return null
    val parts = host.split('.').mapNotNull { it.toLongOrNull()?.takeIf { part -> part in 0..255 } }
    if (parts.size != 4) return null
    val own = parts.fold(0L) { acc, part -> acc * 256 + part }
    val mask = (0xFFFFFFFFL shl (32 - length)) and 0xFFFFFFFFL
    val network = own and mask
    val last = (network or mask.inv()) and 0xFFFFFFFFL
    fun text(value: Long) = (3 downTo 0).joinToString(".") { "${(value shr (it * 8)) and 255}" }
    val ranges = listOf(network + 1 to own - 1, own + 1 to last - 1)
        .filter { (from, to) -> from <= to }
        .joinToString(",") { (from, to) -> if (from == to) text(from) else "${text(from)}-${text(to)}" }
    if (ranges.isEmpty()) return null
    return DhcpPlan("${text(network)}/$length", host, ranges, host)
}

private val pools = MenuPath.parse("ip/pool")
private val servers = MenuPath.parse("ip/dhcp-server")
private val networks = MenuPath.parse("ip/dhcp-server/network")

internal fun dhcpWrites(iface: String, plan: DhcpPlan, lease: String, pool: String, server: String): List<Pair<MenuPath, Map<String, String>>> = listOf(
    pools to mapOf("name" to pool, "ranges" to plan.pool),
    networks to mapOf("address" to plan.network, "gateway" to plan.gateway, "dns-server" to plan.dns),
    servers to mapOf("name" to server, "interface" to iface, "address-pool" to pool, "lease-time" to lease),
).map { (path, values) -> path to values.filterValues { it.isNotEmpty() } }

internal suspend fun setUpDhcp(session: DeviceSession, writes: List<Pair<MenuPath, Map<String, String>>>) {
    val written = mutableListOf<Pair<MenuPath, String>>()
    try {
        for ((path, values) in writes) written += path to session.add(path, values)
    } catch (e: DeviceException) {
        for ((path, id) in written.asReversed()) session.remove(path, listOf(id))
        throw e
    }
}

internal suspend fun dhcpNames(session: DeviceSession): Pair<String, String> = free(session, pools, "dhcp_pool", 0) to free(session, servers, "dhcp", 1)

private suspend fun free(session: DeviceSession, path: MenuPath, prefix: String, from: Int): String {
    val taken = rows(session, path).mapNotNull { it["name"] }.toSet()
    return generateSequence(from) { it + 1 }.map { "$prefix$it" }.first { it !in taken }
}

private suspend fun rows(session: DeviceSession, path: MenuPath): List<Item> = session.watch(path).filterIsInstance<ListEvent.Snapshot>().first().items

@Composable
internal fun DhcpSetupView(session: DeviceSession) {
    val palette = Theme.palette
    val scope = rememberCoroutineScope()
    val addresses by produceState<List<Item>?>(null, session) {
        value = try {
            rows(session, MenuPath.parse("ip/address")).filter { it["disabled"] != "true" && it["invalid"] != "true" }
        } catch (e: DeviceException) {
            emptyList()
        }
    }
    var iface by remember { mutableStateOf<String?>(null) }
    val network = remember { TextFieldState() }
    val gateway = remember { TextFieldState() }
    val pool = remember { TextFieldState() }
    val dns = remember { TextFieldState() }
    val lease = remember { TextFieldState("30m") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val names by produceState<Pair<String, String>?>(null, session, done) {
        value = try {
            dhcpNames(session)
        } catch (e: DeviceException) {
            problem = e.message
            null
        }
    }
    val interfaces = addresses.orEmpty().mapNotNull { it["interface"] }.distinct()
    LaunchedEffect(interfaces) { if (iface == null) iface = interfaces.firstOrNull() }
    LaunchedEffect(iface, addresses) {
        val plan = addresses.orEmpty().firstOrNull { it["interface"] == iface }?.get("address")?.let(::dhcpPlan)
        network.setTextAndPlaceCursorAtEnd(plan?.network.orEmpty())
        gateway.setTextAndPlaceCursorAtEnd(plan?.gateway.orEmpty())
        pool.setTextAndPlaceCursorAtEnd(plan?.pool.orEmpty())
        dns.setTextAndPlaceCursorAtEnd(plan?.dns.orEmpty())
        done = null
    }
    val plan = DhcpPlan(network.text.toString().trim(), gateway.text.toString().trim(), pool.text.toString().trim(), dns.text.toString().trim())
    val chosen = iface
    val writes = if (chosen == null || plan.network.isEmpty() || plan.pool.isEmpty()) null else names?.let { (poolName, serverName) ->
        dhcpWrites(chosen, plan, lease.text.toString().trim(), poolName, serverName)
    }
    ScrollArea(Modifier.fillMaxSize().background(palette.surface), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.dhcp_setup_intro), color = palette.muted)
        if (addresses != null && interfaces.isEmpty()) {
            Text(stringResource(Res.string.dhcp_setup_none), color = palette.warning)
            return@ScrollArea
        }
        val field = if (LocalCompact.current) Modifier.fillMaxWidth() else Modifier.width(260.dp)
        Labeled(stringResource(Res.string.dhcp_setup_interface)) { Select(chosen, interfaces, { it.orEmpty() }, { iface = it }, field.testTag("dhcp-interface")) }
        Labeled(stringResource(Res.string.dhcp_setup_network)) { TextField(network, field.testTag("dhcp-network")) }
        Labeled(stringResource(Res.string.dhcp_setup_gateway)) { TextField(gateway, field) }
        Labeled(stringResource(Res.string.dhcp_setup_pool)) { TextField(pool, field.testTag("dhcp-pool")) }
        Labeled(stringResource(Res.string.dhcp_setup_dns)) { TextField(dns, field) }
        Labeled(stringResource(Res.string.dhcp_setup_lease)) { TextField(lease, field) }
        SelectionContainer(Modifier.fillMaxWidth()) {
            Text(
                writes.orEmpty().joinToString("\n") { (path, values) -> command(session.device.platform, path, EditKind.Add, emptyList(), values) },
                Modifier.fillMaxWidth().heightIn(min = 72.dp).background(palette.raised).padding(8.dp).testTag("dhcp-commands"),
                style = Theme.type.mono,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                problem?.let { deviceMessage(it) } ?: done?.let { stringResource(Res.string.dhcp_setup_done, it, chosen.orEmpty()) }.orEmpty(),
                Modifier.weight(1f).testTag("dhcp-result"),
                color = if (problem != null) palette.danger else palette.success,
                style = Theme.type.small,
            )
            Button(stringResource(Res.string.form_apply), {
                val ready = writes ?: return@Button
                busy = true
                problem = null
                scope.launch {
                    try {
                        setUpDhcp(session, ready)
                        done = names?.second
                    } catch (e: DeviceException) {
                        problem = e.message
                    } finally {
                        busy = false
                    }
                }
            }, Modifier.testTag("dhcp-apply"), enabled = writes != null && !busy && done == null && session.device.canWrite, primary = true)
        }
    }
}
