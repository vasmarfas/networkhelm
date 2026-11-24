package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.duration
import com.vasmarfas.networkhelm.ui.chart.Chart
import com.vasmarfas.networkhelm.ui.chart.Ring
import com.vasmarfas.networkhelm.ui.chart.Series
import com.vasmarfas.networkhelm.ui.chart.bits
import com.vasmarfas.networkhelm.ui.chart.byteSize
import com.vasmarfas.networkhelm.ui.files.LocalFileExchange
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.Checkbox
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.ScrollArea
import com.vasmarfas.networkhelm.ui.kit.Segments
import com.vasmarfas.networkhelm.ui.table.DataTable
import com.vasmarfas.networkhelm.ui.table.TableColumn
import com.vasmarfas.networkhelm.ui.table.TableState
import com.vasmarfas.networkhelm.ui.table.rowsAsCsv
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.dashboard_download
import networkhelm.app.shared.generated.resources.dashboard_upload
import networkhelm.app.shared.generated.resources.vpn_collector_install
import networkhelm.app.shared.generated.resources.vpn_collector_journal
import networkhelm.app.shared.generated.resources.vpn_collector_off
import networkhelm.app.shared.generated.resources.vpn_collector_on
import networkhelm.app.shared.generated.resources.vpn_collector_question
import networkhelm.app.shared.generated.resources.vpn_collector_remove
import networkhelm.app.shared.generated.resources.vpn_column_address
import networkhelm.app.shared.generated.resources.vpn_column_cipher
import networkhelm.app.shared.generated.resources.vpn_column_client
import networkhelm.app.shared.generated.resources.vpn_column_connected
import networkhelm.app.shared.generated.resources.vpn_column_disconnected
import networkhelm.app.shared.generated.resources.vpn_column_duration
import networkhelm.app.shared.generated.resources.vpn_column_endpoint
import networkhelm.app.shared.generated.resources.vpn_column_handshake
import networkhelm.app.shared.generated.resources.vpn_column_kind
import networkhelm.app.shared.generated.resources.vpn_column_reason
import networkhelm.app.shared.generated.resources.vpn_column_received
import networkhelm.app.shared.generated.resources.vpn_column_receiving
import networkhelm.app.shared.generated.resources.vpn_column_sending
import networkhelm.app.shared.generated.resources.vpn_column_sent
import networkhelm.app.shared.generated.resources.vpn_column_service
import networkhelm.app.shared.generated.resources.vpn_column_sessions
import networkhelm.app.shared.generated.resources.vpn_column_since
import networkhelm.app.shared.generated.resources.vpn_column_uptime
import networkhelm.app.shared.generated.resources.vpn_column_user
import networkhelm.app.shared.generated.resources.vpn_panel_client_config
import networkhelm.app.shared.generated.resources.vpn_panel_day
import networkhelm.app.shared.generated.resources.vpn_panel_disconnect
import networkhelm.app.shared.generated.resources.vpn_panel_disconnect_question
import networkhelm.app.shared.generated.resources.vpn_panel_export
import networkhelm.app.shared.generated.resources.vpn_panel_history
import networkhelm.app.shared.generated.resources.vpn_panel_month
import networkhelm.app.shared.generated.resources.vpn_panel_no_history
import networkhelm.app.shared.generated.resources.vpn_panel_no_services
import networkhelm.app.shared.generated.resources.vpn_panel_open_peer
import networkhelm.app.shared.generated.resources.vpn_panel_rates
import networkhelm.app.shared.generated.resources.vpn_panel_services
import networkhelm.app.shared.generated.resources.vpn_panel_sessions
import networkhelm.app.shared.generated.resources.vpn_panel_users
import networkhelm.app.shared.generated.resources.vpn_panel_week
import networkhelm.app.shared.generated.resources.vpn_tile_rates
import networkhelm.app.shared.generated.resources.vpn_tile_sessions
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

private val VPN_SERVICES = MenuPath.parse("vpn/service")
private val VPN_SESSIONS = MenuPath.parse("vpn/session")
private val VPN_HISTORY = MenuPath.parse("vpn/history")
private val VPN_PEERS = MenuPath.parse("vpn/wireguard/peer")
private val AWG_PEERS = MenuPath.parse("vpn/amneziawg/peer")
private const val NO_BREAK = "\u00A0"
private val VPN_COLLECTOR = MenuPath.parse("vpn/collector")
private const val VPN_SAMPLES = 120

private enum class Period(val seconds: Long) { Day(86_400), Week(604_800), Month(2_678_400) }

// what a user had over the period: sessions, their time and their bytes where the service counts them
private class UserTotal(val user: String, val sessions: Int, val seconds: Long, val received: Long?, val sent: Long?)

// the VPN of the host at a glance: a tile for each service, the rates of all of them and of the session chosen, the live sessions with
// what can be done to them, and the sessions the journals keep over a day, a week or a month with what each user had
@Composable
internal fun LinuxVpnPanelView(tab: RouterTab) {
    val palette = Theme.palette
    val session = tab.session
    val services by remember(session) { session.rowsAt(VPN_SERVICES) }.collectAsStateWithLifecycle(emptyList())
    val sessions by remember(session) { session.rowsAt(VPN_SESSIONS) }.collectAsStateWithLifecycle(emptyList())
    val history by remember(session) { session.rowsAt(VPN_HISTORY) }.collectAsStateWithLifecycle(emptyList())
    val collector by remember(session) { session.rowsAt(VPN_COLLECTOR) }.collectAsStateWithLifecycle(emptyList())
    val table = remember(tab) { TableState<String>() }
    val chosen = sessions.filter { it.id in table.selected }
    val one = chosen.singleOrNull()
    val peer = one?.get("peer")?.ifEmpty { null }
    val peers = if (one?.id?.startsWith("amneziawg/") == true) AWG_PEERS else VPN_PEERS
    val downloadName = stringResource(Res.string.dashboard_download)
    val uploadName = stringResource(Res.string.dashboard_upload)
    val receiving = remember(tab, downloadName) { Series(downloadName, palette.accent, Ring(VPN_SAMPLES)) }
    val sending = remember(tab, uploadName) { Series(uploadName, palette.success, Ring(VPN_SAMPLES)) }
    val oneIn = remember(tab, one?.id, downloadName) { Series("${shownUser(one)} $downloadName", palette.warning, Ring(VPN_SAMPLES)) }
    val oneOut = remember(tab, one?.id, uploadName) { Series("${shownUser(one)} $uploadName", palette.danger, Ring(VPN_SAMPLES)) }
    LaunchedEffect(services) {
        // a service that is stopped or read for the first time has no rate yet: the point sums those that have one
        val rated = services.filter { !it["rx-rate"].isNullOrEmpty() }
        if (rated.isEmpty()) return@LaunchedEffect
        receiving.ring.add(rated.sumOf { it["rx-rate"]?.toDoubleOrNull() ?: 0.0 })
        sending.ring.add(rated.sumOf { it["tx-rate"]?.toDoubleOrNull() ?: 0.0 })
    }
    LaunchedEffect(sessions, one?.id) {
        val current = sessions.firstOrNull { it.id == one?.id } ?: return@LaunchedEffect
        current["rx-rate"]?.toDoubleOrNull()?.let(oneIn.ring::add)
        current["tx-rate"]?.toDoubleOrNull()?.let(oneOut.ring::add)
    }
    var period by remember { mutableStateOf(Period.Day) }
    val now = remember(history) { Clock.System.now().epochSeconds }
    val shown = history.filter { (it["start"]?.toLongOrNull() ?: 0L) >= now - period.seconds }
    val scope = rememberCoroutineScope()
    val exchange = LocalFileExchange.current
    var problem by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf<Pair<String, String>?>(null) }
    var ending by remember { mutableStateOf<List<String>?>(null) }
    var removing by remember { mutableStateOf(false) }
    var journal by remember { mutableStateOf(false) }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            problem = try {
                block()
                null
            } catch (e: DeviceException) {
                e.message
            }
        }
    }
    val liveColumns = sessionColumns()
    val logColumns = historyColumns()
    val clientConfig = stringResource(Res.string.vpn_panel_client_config)
    Box(Modifier.fillMaxSize().background(palette.surface).testTag("vpn-panel")) {
        ScrollArea(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Section(stringResource(Res.string.vpn_panel_services)) {
                if (services.isEmpty()) Text(stringResource(Res.string.vpn_panel_no_services), color = palette.muted, style = Theme.type.small)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (service in services) Tile(service)
                }
            }
            Section(stringResource(Res.string.vpn_panel_rates)) {
                Chart(listOfNotNull(receiving, sending, oneIn.takeIf { one != null }, oneOut.takeIf { one != null }), Modifier.fillMaxWidth().height(120.dp).testTag("vpn-chart"), format = ::bits)
                Rates(receiving)
                Rates(sending)
                if (one != null) {
                    Rates(oneIn)
                    Rates(oneOut)
                }
            }
            Section(stringResource(Res.string.vpn_panel_sessions)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(stringResource(Res.string.vpn_panel_disconnect), { ending = chosen.map { it.id } }, Modifier.testTag("vpn-disconnect"), enabled = chosen.isNotEmpty(), danger = true)
                    Button(stringResource(Res.string.vpn_panel_open_peer), { if (peer != null) tab.openItem(shownUser(one), peers, peer) }, Modifier.testTag("vpn-open-peer"), enabled = peer != null)
                    Button(clientConfig, {
                        if (peer != null) {
                            act { output = clientConfig to session.run(peers, "client-config", mapOf("number" to peer)).toList().mapNotNull { it["conf"] }.joinToString("\n\n") }
                        }
                    }, Modifier.testTag("vpn-client-config"), enabled = peer != null)
                }
                problem?.let { Text(deviceMessage(it), Modifier.testTag("problem"), color = palette.danger, style = Theme.type.small) }
                DataTable(sessions, liveColumns, { it.id }, Modifier.fillMaxWidth().height(240.dp).testTag("vpn-sessions"), state = table)
            }
            Section(stringResource(Res.string.vpn_panel_history)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Segments(period, Period.entries, { periodName(it) }, { period = it }, Modifier.testTag("vpn-period"))
                    Button(stringResource(Res.string.vpn_panel_export), {
                        val bytes = rowsAsCsv(logColumns, shown).encodeToByteArray()
                        act { exchange.save("vpn-sessions.csv", bytes) }
                    }, Modifier.testTag("vpn-export"), enabled = shown.isNotEmpty())
                }
                collector.firstOrNull()?.let { row ->
                    val installed = row["installed"] == "true"
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (installed) stringResource(Res.string.vpn_collector_on, bytesOf(row["size"].orEmpty())) else stringResource(Res.string.vpn_collector_off),
                            Modifier.weight(1f),
                            color = palette.muted,
                            style = Theme.type.small,
                        )
                        if (installed) {
                            Button(stringResource(Res.string.vpn_collector_remove), { removing = true }, Modifier.testTag("vpn-collector-remove"))
                        } else {
                            Button(stringResource(Res.string.vpn_collector_install), { act { session.run(VPN_COLLECTOR, "install", emptyMap()).toList() } }, Modifier.testTag("vpn-collector-install"))
                        }
                    }
                }
                if (history.isEmpty()) Text(stringResource(Res.string.vpn_panel_no_history), color = palette.muted, style = Theme.type.small)
                DataTable(shown, logColumns, { it.id }, Modifier.fillMaxWidth().height(200.dp).testTag("vpn-history"))
                Text(stringResource(Res.string.vpn_panel_users), color = palette.muted, style = Theme.type.small)
                DataTable(totals(shown), userColumns(), { it.user }, Modifier.fillMaxWidth().height(140.dp).testTag("vpn-users"))
            }
        }
        ending?.let { ids ->
            Confirm(stringResource(Res.string.vpn_panel_disconnect_question), stringResource(Res.string.vpn_panel_disconnect), onCancel = { ending = null }) {
                ending = null
                act { session.run(VPN_SESSIONS, "disconnect", mapOf("numbers" to ids.joinToString(","))).toList() }
            }
        }
        if (removing) {
            Confirm(
                stringResource(Res.string.vpn_collector_question),
                stringResource(Res.string.vpn_collector_remove),
                onCancel = { removing = false },
                content = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(journal, { journal = it })
                        Text(stringResource(Res.string.vpn_collector_journal), style = Theme.type.small)
                    }
                },
            ) {
                removing = false
                act { session.run(VPN_COLLECTOR, "remove", mapOf("journal" to if (journal) "yes" else "no")).toList() }
            }
        }
        output?.let { (heading, text) -> Output(heading, text) { output = null } }
    }
}

// a peer of WireGuard or AmneziaWG without a name goes by the start of its key
private fun shownUser(row: Item?): String = row?.get("user")?.ifEmpty { null } ?: row?.get("public-key").orEmpty().take(8)

@Composable
private fun periodName(period: Period) = stringResource(
    when (period) {
        Period.Day -> Res.string.vpn_panel_day
        Period.Week -> Res.string.vpn_panel_week
        Period.Month -> Res.string.vpn_panel_month
    },
)

private fun bytesOf(value: String) = value.toDoubleOrNull()?.let(::byteSize) ?: value

private fun bitsOf(value: String) = value.toDoubleOrNull()?.let(::bits) ?: value

@Composable
private fun sessionColumns(): List<TableColumn<Item>> = listOf(
    TableColumn("user", stringResource(Res.string.vpn_column_user), null) { shownUser(it) },
    TableColumn("kind", stringResource(Res.string.vpn_column_kind), null) { it["kind"].orEmpty() },
    TableColumn("service", stringResource(Res.string.vpn_column_service), null) { it["service"].orEmpty() },
    TableColumn("endpoint", stringResource(Res.string.vpn_column_endpoint), null) { it["endpoint"].orEmpty() },
    TableColumn("address", stringResource(Res.string.vpn_column_address), null) { it["address"].orEmpty() },
    TableColumn("since", stringResource(Res.string.vpn_column_since), null) { it["since"].orEmpty() },
    TableColumn("uptime", stringResource(Res.string.vpn_column_uptime), null) { it["uptime"].orEmpty() },
    TableColumn("rx", stringResource(Res.string.vpn_column_received), null, ::bytesOf) { it["rx"].orEmpty() },
    TableColumn("tx", stringResource(Res.string.vpn_column_sent), null, ::bytesOf) { it["tx"].orEmpty() },
    TableColumn("rx-rate", stringResource(Res.string.vpn_column_receiving), null, ::bitsOf) { it["rx-rate"].orEmpty() },
    TableColumn("tx-rate", stringResource(Res.string.vpn_column_sending), null, ::bitsOf) { it["tx-rate"].orEmpty() },
    TableColumn("last-handshake", stringResource(Res.string.vpn_column_handshake), null) { it["last-handshake"].orEmpty() },
    TableColumn("cipher", stringResource(Res.string.vpn_column_cipher), null) { it["cipher"].orEmpty() },
    TableColumn("client", stringResource(Res.string.vpn_column_client), null) { it["client"].orEmpty() },
)

@Composable
private fun historyColumns(): List<TableColumn<Item>> = listOf(
    TableColumn("connected", stringResource(Res.string.vpn_column_connected), null) { it["connected"].orEmpty() },
    TableColumn("disconnected", stringResource(Res.string.vpn_column_disconnected), null) { it["disconnected"].orEmpty() },
    TableColumn("duration", stringResource(Res.string.vpn_column_duration), null) { it["duration"].orEmpty() },
    TableColumn("user", stringResource(Res.string.vpn_column_user), null) { it["user"].orEmpty() },
    TableColumn("service", stringResource(Res.string.vpn_column_service), null) { it["service"].orEmpty() },
    TableColumn("endpoint", stringResource(Res.string.vpn_column_endpoint), null) { it["endpoint"].orEmpty() },
    TableColumn("address", stringResource(Res.string.vpn_column_address), null) { it["address"].orEmpty() },
    TableColumn("reason", stringResource(Res.string.vpn_column_reason), null) { it["reason"].orEmpty() },
    TableColumn("rx", stringResource(Res.string.vpn_column_received), null, ::bytesOf) { it["rx"].orEmpty() },
    TableColumn("tx", stringResource(Res.string.vpn_column_sent), null, ::bytesOf) { it["tx"].orEmpty() },
)

@Composable
private fun userColumns(): List<TableColumn<UserTotal>> = listOf(
    TableColumn("user", stringResource(Res.string.vpn_column_user), null) { it.user },
    TableColumn("sessions", stringResource(Res.string.vpn_column_sessions), null) { "${it.sessions}" },
    TableColumn("duration", stringResource(Res.string.vpn_column_duration), null) { duration(it.seconds) },
    TableColumn("rx", stringResource(Res.string.vpn_column_received), null, ::bytesOf) { it.received?.toString().orEmpty() },
    TableColumn("tx", stringResource(Res.string.vpn_column_sent), null, ::bytesOf) { it.sent?.toString().orEmpty() },
)

// the sessions of the period by user; bytes only where every session of the user has them
private fun totals(rows: List<Item>): List<UserTotal> = rows.groupBy { it["user"].orEmpty() }.map { (user, sessions) ->
    fun sum(key: String) = sessions.map { it[key]?.toLongOrNull() }.takeIf { values -> values.none { it == null } }?.sumOf { it ?: 0L }
    UserTotal(user, sessions.size, sessions.sumOf { it["seconds"]?.toLongOrNull() ?: 0L }, sum("rx"), sum("tx"))
}.sortedByDescending { it.seconds }

@Composable
private fun Tile(service: Item) {
    val palette = Theme.palette
    val state = service["state"].orEmpty()
    Column(Modifier.then(if (LocalCompact.current) Modifier.fillMaxWidth() else Modifier.width(200.dp)).background(palette.raised).padding(10.dp).testTag("vpn-tile"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(service["name"].orEmpty(), Modifier.weight(1f), style = Theme.type.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(service["kind"].orEmpty(), color = palette.muted, style = Theme.type.small, maxLines = 1)
        }
        Text(
            state,
            color = when (state) {
                "active" -> palette.success
                "failed" -> palette.danger
                else -> palette.muted
            },
            style = Theme.type.small,
        )
        val port = listOf(service["port"].orEmpty(), service["protocol"].orEmpty()).filter { it.isNotEmpty() }.joinToString("/")
        if (port.isNotEmpty()) Text(port, style = Theme.type.mono, maxLines = 1)
        Text(stringResource(Res.string.vpn_tile_sessions, service["sessions"].orEmpty()), style = Theme.type.small)
        if (!service["rx-rate"].isNullOrEmpty() || !service["tx-rate"].isNullOrEmpty()) {
            // a no-break space keeps a rate with its unit when the line wraps
            val rate = { name: String -> bitsOf(service[name].orEmpty()).replace(" ", NO_BREAK) }
            Text(stringResource(Res.string.vpn_tile_rates, rate("rx-rate"), rate("tx-rate")), style = Theme.type.small)
        }
    }
}
