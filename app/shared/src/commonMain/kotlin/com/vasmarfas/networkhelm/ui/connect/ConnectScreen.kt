package com.vasmarfas.networkhelm.ui.connect

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Key
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PanelBottom
import com.composables.icons.lucide.PanelRight
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Upload
import com.composables.icons.lucide.Wrench
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.app.Access
import com.vasmarfas.networkhelm.app.DeviceKind
import com.vasmarfas.networkhelm.app.LocalAppSettings
import com.vasmarfas.networkhelm.app.LocalSettings
import com.vasmarfas.networkhelm.app.SavedRouter
import com.vasmarfas.networkhelm.app.SavedRouters
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.app.canSeal
import com.vasmarfas.networkhelm.discovery.Found
import com.vasmarfas.networkhelm.discovery.MndpSighting
import com.vasmarfas.networkhelm.discovery.hosts
import com.vasmarfas.networkhelm.appVersion
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.snmp.AuthProtocol
import com.vasmarfas.networkhelm.snmp.PrivProtocol
import com.vasmarfas.networkhelm.transport.TrustKind
import com.vasmarfas.networkhelm.ui.files.LocalFileExchange
import com.vasmarfas.networkhelm.transport.tcpListening
import com.vasmarfas.networkhelm.ui.about.appIcon
import com.vasmarfas.networkhelm.ui.files.fileDrop
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.Checkbox
import com.vasmarfas.networkhelm.ui.kit.DropdownItem
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.LabelWidth
import com.vasmarfas.networkhelm.ui.kit.Labeled
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.LocalSubmit
import com.vasmarfas.networkhelm.ui.kit.SecretField
import com.vasmarfas.networkhelm.ui.kit.Select
import com.vasmarfas.networkhelm.ui.kit.SplitState
import com.vasmarfas.networkhelm.ui.kit.TabStrip
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.kit.ToolbarButton
import com.vasmarfas.networkhelm.ui.kit.VerticalSplit
import com.vasmarfas.networkhelm.ui.kit.dialogPanel
import com.vasmarfas.networkhelm.ui.kit.hand
import com.vasmarfas.networkhelm.ui.kit.scrim
import com.vasmarfas.networkhelm.ui.table.DataTable
import com.vasmarfas.networkhelm.ui.table.TableColumn
import com.vasmarfas.networkhelm.ui.table.TableState
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.workspace.VerticalScrollbar
import eu.anifantakis.lib.ksafe.KSafeProtectionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.book_export_question
import networkhelm.app.shared.generated.resources.book_mismatch
import networkhelm.app.shared.generated.resources.book_open_question
import networkhelm.app.shared.generated.resources.book_password
import networkhelm.app.shared.generated.resources.book_repeat
import networkhelm.app.shared.generated.resources.connect
import networkhelm.app.shared.generated.resources.connect_unverified
import networkhelm.app.shared.generated.resources.connect_secrets_software
import networkhelm.app.shared.generated.resources.connect_secrets_system
import networkhelm.app.shared.generated.resources.connect_secrets_unavailable
import networkhelm.app.shared.generated.resources.connect_address
import networkhelm.app.shared.generated.resources.connect_address_hint
import networkhelm.app.shared.generated.resources.connect_comment
import networkhelm.app.shared.generated.resources.connect_connecting
import networkhelm.app.shared.generated.resources.connect_encoding
import networkhelm.app.shared.generated.resources.connect_export
import networkhelm.app.shared.generated.resources.connect_group
import networkhelm.app.shared.generated.resources.connect_import
import networkhelm.app.shared.generated.resources.connect_import_hint
import networkhelm.app.shared.generated.resources.connect_imported
import networkhelm.app.shared.generated.resources.connect_interface
import networkhelm.app.shared.generated.resources.connect_keenetic_token
import networkhelm.app.shared.generated.resources.connect_lists_below
import networkhelm.app.shared.generated.resources.connect_lists_beside
import networkhelm.app.shared.generated.resources.connect_neighbors
import networkhelm.app.shared.generated.resources.connect_neighbors_unavailable
import networkhelm.app.shared.generated.resources.connect_platform
import networkhelm.app.shared.generated.resources.connect_platform_found
import networkhelm.app.shared.generated.resources.connect_port
import networkhelm.app.shared.generated.resources.connect_scan
import networkhelm.app.shared.generated.resources.kind_keenetic
import networkhelm.app.shared.generated.resources.kind_openwrt
import networkhelm.app.shared.generated.resources.kind_opnsense
import networkhelm.app.shared.generated.resources.kind_other
import networkhelm.app.shared.generated.resources.kind_pfsense
import networkhelm.app.shared.generated.resources.kind_routeros
import networkhelm.app.shared.generated.resources.kind_vyos
import networkhelm.app.shared.generated.resources.renew_change
import networkhelm.app.shared.generated.resources.renew_new
import networkhelm.app.shared.generated.resources.renew_question
import networkhelm.app.shared.generated.resources.renew_repeat
import networkhelm.app.shared.generated.resources.renew_skip
import networkhelm.app.shared.generated.resources.scan_done
import networkhelm.app.shared.generated.resources.scan_hint
import networkhelm.app.shared.generated.resources.scan_ports
import networkhelm.app.shared.generated.resources.scan_running
import networkhelm.app.shared.generated.resources.scan_start
import networkhelm.app.shared.generated.resources.scan_stop
import networkhelm.app.shared.generated.resources.connect_browser_shut
import networkhelm.app.shared.generated.resources.connect_community
import networkhelm.app.shared.generated.resources.connect_open_file
import networkhelm.app.shared.generated.resources.connect_open_file_hint
import networkhelm.app.shared.generated.resources.connect_opnsense_key
import networkhelm.app.shared.generated.resources.connect_own_mac
import networkhelm.app.shared.generated.resources.connect_own_mac_hint
import networkhelm.app.shared.generated.resources.connect_pfsense_key
import networkhelm.app.shared.generated.resources.connect_password
import networkhelm.app.shared.generated.resources.connect_plain_community
import networkhelm.app.shared.generated.resources.connect_plain_warning
import networkhelm.app.shared.generated.resources.connect_probe_shut
import networkhelm.app.shared.generated.resources.connect_protocol
import networkhelm.app.shared.generated.resources.connect_remember
import networkhelm.app.shared.generated.resources.connect_remember_community
import networkhelm.app.shared.generated.resources.connect_remove
import networkhelm.app.shared.generated.resources.connect_save
import networkhelm.app.shared.generated.resources.connect_saved
import networkhelm.app.shared.generated.resources.connect_snmp3_auth
import networkhelm.app.shared.generated.resources.connect_snmp3_plain
import networkhelm.app.shared.generated.resources.connect_snmp3_privacy
import networkhelm.app.shared.generated.resources.connect_snmp3_user
import networkhelm.app.shared.generated.resources.connect_snmp_community
import networkhelm.app.shared.generated.resources.connect_upnp_port
import networkhelm.app.shared.generated.resources.connect_ssh_agent
import networkhelm.app.shared.generated.resources.connect_ssh_agent_hint
import networkhelm.app.shared.generated.resources.connect_ssh_key
import networkhelm.app.shared.generated.resources.connect_ssh_key_choose
import networkhelm.app.shared.generated.resources.connect_ssh_key_none
import networkhelm.app.shared.generated.resources.connect_ssh_passphrase
import networkhelm.app.shared.generated.resources.connect_stop
import networkhelm.app.shared.generated.resources.connect_user
import networkhelm.app.shared.generated.resources.connect_via
import networkhelm.app.shared.generated.resources.connect_via_direct
import networkhelm.app.shared.generated.resources.connect_via_hint
import networkhelm.app.shared.generated.resources.connect_vyos_key
import networkhelm.app.shared.generated.resources.encoding_cp1251
import networkhelm.app.shared.generated.resources.encoding_utf8
import networkhelm.app.shared.generated.resources.error_address
import networkhelm.app.shared.generated.resources.error_book_file
import networkhelm.app.shared.generated.resources.error_book_invalid
import networkhelm.app.shared.generated.resources.error_book_password
import networkhelm.app.shared.generated.resources.error_browser
import networkhelm.app.shared.generated.resources.error_browser_routeros
import networkhelm.app.shared.generated.resources.error_handshake
import networkhelm.app.shared.generated.resources.error_local_network
import networkhelm.app.shared.generated.resources.error_login
import networkhelm.app.shared.generated.resources.error_mac_telnet_no_interface
import networkhelm.app.shared.generated.resources.error_mac_telnet_unsupported
import networkhelm.app.shared.generated.resources.error_no_rest
import networkhelm.app.shared.generated.resources.error_no_snmp
import networkhelm.app.shared.generated.resources.error_no_snmp3
import networkhelm.app.shared.generated.resources.error_no_upnp
import networkhelm.app.shared.generated.resources.error_not_config
import networkhelm.app.shared.generated.resources.error_other
import networkhelm.app.shared.generated.resources.error_own_mac
import networkhelm.app.shared.generated.resources.error_port
import networkhelm.app.shared.generated.resources.error_through_circle
import networkhelm.app.shared.generated.resources.error_through_missing
import networkhelm.app.shared.generated.resources.error_refused
import networkhelm.app.shared.generated.resources.error_rejected
import networkhelm.app.shared.generated.resources.error_saved_list
import networkhelm.app.shared.generated.resources.error_secrets
import networkhelm.app.shared.generated.resources.error_snmp_through
import networkhelm.app.shared.generated.resources.error_upnp_through
import networkhelm.app.shared.generated.resources.error_tcp_8291_unsupported
import networkhelm.app.shared.generated.resources.error_timeout
import networkhelm.app.shared.generated.resources.error_unknown_host
import networkhelm.app.shared.generated.resources.error_unreachable
import networkhelm.app.shared.generated.resources.error_unreadable
import networkhelm.app.shared.generated.resources.error_unsupported
import networkhelm.app.shared.generated.resources.error_winbox_file
import networkhelm.app.shared.generated.resources.form_ok
import networkhelm.app.shared.generated.resources.list_cancel
import networkhelm.app.shared.generated.resources.main_about
import networkhelm.app.shared.generated.resources.neighbor_board
import networkhelm.app.shared.generated.resources.neighbor_connect_mac_telnet
import networkhelm.app.shared.generated.resources.neighbor_interface
import networkhelm.app.shared.generated.resources.neighbor_ip
import networkhelm.app.shared.generated.resources.neighbor_mac
import networkhelm.app.shared.generated.resources.neighbor_version
import networkhelm.app.shared.generated.resources.neighbors_hint
import networkhelm.app.shared.generated.resources.neighbors_not_heard
import networkhelm.app.shared.generated.resources.neighbors_not_sent
import networkhelm.app.shared.generated.resources.neighbors_refresh
import networkhelm.app.shared.generated.resources.protocol_api
import networkhelm.app.shared.generated.resources.protocol_api_ssl
import networkhelm.app.shared.generated.resources.protocol_keenetic_http
import networkhelm.app.shared.generated.resources.protocol_keenetic_https
import networkhelm.app.shared.generated.resources.protocol_mac_telnet
import networkhelm.app.shared.generated.resources.protocol_openwrt_http
import networkhelm.app.shared.generated.resources.protocol_openwrt_https
import networkhelm.app.shared.generated.resources.protocol_openwrt_ssh
import networkhelm.app.shared.generated.resources.protocol_rest
import networkhelm.app.shared.generated.resources.protocol_rest_ssl
import networkhelm.app.shared.generated.resources.protocol_opnsense
import networkhelm.app.shared.generated.resources.protocol_pfsense
import networkhelm.app.shared.generated.resources.protocol_snmp
import networkhelm.app.shared.generated.resources.protocol_snmp3
import networkhelm.app.shared.generated.resources.protocol_tcp_8291
import networkhelm.app.shared.generated.resources.protocol_upnp
import networkhelm.app.shared.generated.resources.protocol_vyos
import networkhelm.app.shared.generated.resources.protocol_ssh
import networkhelm.app.shared.generated.resources.settings_title
import networkhelm.app.shared.generated.resources.tools_title
import networkhelm.app.shared.generated.resources.trust_accept
import networkhelm.app.shared.generated.resources.trust_changed_ssh
import networkhelm.app.shared.generated.resources.trust_changed_tls
import networkhelm.app.shared.generated.resources.trust_fingerprint
import networkhelm.app.shared.generated.resources.trust_new_ssh
import networkhelm.app.shared.generated.resources.trust_new_tls
import networkhelm.app.shared.generated.resources.trust_previous
import networkhelm.app.shared.generated.resources.trust_reject

private val FORM_WIDTH = 480.dp

// beside the form the lists keep room for the address, the comment and the user of a router
private val SIDE_BY_SIDE = 960.dp

@Composable
fun ConnectScreen(
    services: Services,
    saved: SavedRouters,
    scope: CoroutineScope,
    questions: TrustQuestions = remember { TrustQuestions() },
    onAbout: () -> Unit = {},
    onSettings: () -> Unit = {},
    onTools: () -> Unit = {},
    probe: Boolean = false,
    onConnected: (DeviceSession, String, RouterOsLogin?) -> Unit,
) {
    val state = remember { ConnectState(services, saved, scope, questions) }
    LaunchedEffect(state) { state.follow() }
    LaunchedEffect(state, probe) { if (probe) state.probe() }
    DisposableEffect(state) { onDispose { state.renewing?.skip() } }
    val ui = rememberCoroutineScope()
    val exchange = LocalFileExchange.current
    val palette = Theme.palette
    val compact = LocalCompact.current
    val density = LocalDensity.current
    val store = LocalAppSettings.current
    val settings = LocalSettings.current
    val split = remember { SplitState().apply { first = settings.connectSplit?.dp } }
    // the bar between the form and the lists is kept in the settings once it rests; the app reads them after its first frame,
    // so the bar takes its place from them again once they are read
    LaunchedEffect(split, store?.loaded) {
        split.first = settings.connectSplit?.dp
        snapshotFlow { split.first }.drop(1).collectLatest { height ->
            delay(400)
            store?.arrange { it.copy(connectSplit = height?.value) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.workspace).fileDrop { files -> ui.launch { state.openFiles(files, onConnected) } }, contentAlignment = Alignment.TopCenter) {
        val margin = if (compact) 8.dp else 16.dp
        val wide = !compact && maxWidth >= SIDE_BY_SIDE
        val beside = wide && !settings.listsBelow
        val shape = RoundedCornerShape(Theme.metrics.radius)
        val card = Modifier.background(palette.surface, shape).border(1.dp, palette.line, shape)
        val form: @Composable (Modifier, Boolean) -> Unit = { modifier, fill ->
            CompositionLocalProvider(LocalSubmit provides { if (state.connecting == null) state.connect(onConnected) }) {
                Form(
                    state,
                    saved.routers.collectAsState().value.map { it.address }.distinct(),
                    onConnect = { state.connect(onConnected) },
                    onSave = { ui.launch { state.save() } },
                    onOpenFile = { ui.launch { state.openFiles(exchange.pick(), onConnected) } },
                    onKey = { ui.launch { exchange.pick().firstOrNull()?.let { state.takeKey(it) } } },
                    modifier = modifier,
                    fill = fill,
                )
            }
        }
        val lists: @Composable (Modifier) -> Unit = { modifier ->
            Lists(
                services,
                saved,
                state,
                onConnect = { state.connect(onConnected) },
                onRemove = { router -> ui.launch { state.remove(router) } },
                onImport = { ui.launch { state.import(exchange.pick()) } },
                onExport = { state.sealing = Sealing.Export },
                beside = beside,
                onPlace = if (wide) ({ ui.launch { store?.arrange { it.copy(listsBelow = !settings.listsBelow) } } }) else null,
                modifier = modifier.then(card),
            )
        }
        when {
            beside -> Row(Modifier.fillMaxSize().padding(margin), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val scrolled = rememberScrollState()
                Column(Modifier.width(FORM_WIDTH).fillMaxHeight().then(card)) {
                    Header(onAbout, onSettings, onTools, Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), icons = true)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
                    Box(Modifier.weight(1f)) {
                        form(Modifier.verticalScroll(scrolled).padding(12.dp), true)
                        VerticalScrollbar(scrolled, Modifier.align(Alignment.CenterEnd))
                    }
                }
                lists(Modifier.weight(1f).fillMaxHeight())
            }
            // on a phone the page scrolls as a whole, and a tap opens a saved router at once
            compact -> {
                val scrolled = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scrolled).padding(margin), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Header(onAbout, onSettings, onTools)
                    form(card.padding(12.dp), true)
                    lists(Modifier.fillMaxWidth().height(420.dp))
                }
                VerticalScrollbar(scrolled, Modifier.align(Alignment.CenterEnd))
            }
            // the form and the lists scroll apart, and the part of the form keeps the height of its first look,
            // so a form that grows as a router fills it never moves a row from under the pointer
            else -> Column(Modifier.widthIn(max = 760.dp).fillMaxSize().padding(margin), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Header(onAbout, onSettings, onTools)
                VerticalSplit(split, Modifier.weight(1f), least = 120.dp, first = {
                    val scrolled = rememberScrollState()
                    Box(Modifier.fillMaxSize()) {
                        form(
                            Modifier.fillMaxWidth().verticalScroll(scrolled).wrapContentHeight(Alignment.Top)
                                .onSizeChanged { if (split.natural == 0.dp) split.natural = with(density) { it.height.toDp() } }.then(card).padding(12.dp),
                            false,
                        )
                        VerticalScrollbar(scrolled, Modifier.align(Alignment.CenterEnd))
                    }
                }) {
                    lists(Modifier.fillMaxSize())
                }
            }
        }
        state.sealing?.let { sealing ->
            MasterPassword(sealing is Sealing.Export, onCancel = { state.sealing = null }) { password ->
                ui.launch {
                    when (sealing) {
                        Sealing.Export -> state.export(password, exchange)
                        is Sealing.Open -> state.open(sealing.book, password)
                    }
                }
            }
        }
        state.winBox?.let { book -> WinBoxImportDialog(book, onCancel = { state.winBox = null }, onImport = { ui.launch { state.importWinBox(book) } }) }
        state.renewing?.let { PasswordRenewal(it) }
        questions.question?.let { TrustDialog(it) }
    }
}

@Composable
private fun PasswordRenewal(renewal: Renewal) {
    val palette = Theme.palette
    val first = remember { TextFieldState() }
    val second = remember { TextFieldState() }
    val differ = first.text.toString() != second.text.toString()
    val ready = first.text.isNotEmpty() && !differ && !renewal.busy
    Box(Modifier.fillMaxSize().scrim(palette.workspace.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).dialogPanel(16.dp).testTag("renewal"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(Res.string.renew_question))
            Labeled(stringResource(Res.string.renew_new)) { SecretField(first, Modifier.width(260.dp).testTag("renewal-new")) }
            Labeled(stringResource(Res.string.renew_repeat)) {
                SecretField(second, Modifier.width(260.dp).testTag("renewal-repeat"), onGo = { if (ready) renewal.change(first.text.toString()) })
            }
            if (differ && second.text.isNotEmpty()) Text(stringResource(Res.string.book_mismatch), color = palette.warning)
            renewal.failure?.let { Text(it, color = palette.warning, modifier = Modifier.testTag("renewal-failure")) }
            Row(Modifier.align(Alignment.End).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(stringResource(Res.string.renew_skip), renewal::skip)
                Button(stringResource(Res.string.renew_change), { renewal.change(first.text.toString()) }, enabled = ready, primary = true)
            }
        }
    }
}

@Composable
private fun MasterPassword(export: Boolean, onCancel: () -> Unit, onDone: (String) -> Unit) {
    val palette = Theme.palette
    val first = remember { TextFieldState() }
    val second = remember { TextFieldState() }
    val differ = export &&first.text.toString() != second.text.toString()
    Box(Modifier.fillMaxSize().scrim(palette.workspace.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).dialogPanel(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(if (export) Res.string.book_export_question else Res.string.book_open_question))
            Labeled(stringResource(Res.string.book_password)) { SecretField(first, Modifier.width(260.dp).testTag("master")) }
            if (export) Labeled(stringResource(Res.string.book_repeat)) { SecretField(second, Modifier.width(260.dp).testTag("master-repeat")) }
            if (differ && second.text.isNotEmpty()) Text(stringResource(Res.string.book_mismatch), color = palette.warning)
            Row(Modifier.align(Alignment.End).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(stringResource(Res.string.list_cancel), onCancel)
                Button(stringResource(Res.string.form_ok), { onDone(first.text.toString()) }, enabled = first.text.isNotEmpty() && !differ, primary = true)
            }
        }
    }
}

private val portDigits = InputTransformation { if (length > 5 || asCharSequence().any { !it.isDigit() }) revertAllChanges() }

@Composable
private fun Status(state: ConnectState) {
    val connecting = state.connecting
    when {
        connecting != null -> Text(stringResource(Res.string.connect_connecting, connecting), color = Theme.palette.muted)
        else -> state.problem?.let { Text(problemText(it), Modifier.testTag("problem"), color = Theme.palette.danger) }
    }
}

@Composable
private fun Form(
    state: ConnectState,
    saved: List<String>,
    onConnect: () -> Unit,
    onSave: () -> Unit,
    onOpenFile: () -> Unit,
    onKey: () -> Unit,
    modifier: Modifier,
    fill: Boolean,
) {
    val palette = Theme.palette
    val compact = LocalCompact.current
    // filled, the fields reach the right edge of the panel; otherwise they keep one width in a wider column
    val field = if (fill) Modifier.fillMaxWidth() else Modifier.width(300.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Labeled(stringResource(Res.string.connect_address)) {
            TextField(state.address, field.testTag("address"), placeholder = stringResource(Res.string.connect_address_hint))
        }
        Labeled(stringResource(Res.string.connect_platform)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Select(state.kind, DeviceKind.entries, { kindLabel(it) }, { state.pick(it) }, (if (fill) Modifier.weight(1f) else field).testTag("platform"), dimmed = { kind -> kind.protocols.all { it.defaultPort in state.shut } })
                if (state.detected != null && state.detected == state.kind) {
                    Text(stringResource(Res.string.connect_platform_found), Modifier.testTag("platform-found"), color = palette.muted, style = Theme.type.small)
                }
            }
        }
        Labeled(stringResource(Res.string.connect_protocol)) {
            Select(state.protocol, state.kind.protocols, { protocolLabel(it) }, { state.pick(it) }, field.testTag("protocol"), dimmed = { it.defaultPort in state.shut })
        }
        val unverified = when {
            state.kind == DeviceKind.OpenWrt -> "RutOS (Teltonika)"
            state.kind == DeviceKind.Keenetic -> "Keenetic / Netcraze"
            state.protocol == Access.Upnp -> "TR-064"
            else -> null
        }
        if (unverified != null) Labeled("") {
            Text(stringResource(Res.string.connect_unverified, unverified), Modifier.testTag("platform-unverified"), color = palette.muted, style = Theme.type.small)
        }
        if (state.protocol != Access.MacTelnet) {
            Labeled(stringResource(Res.string.connect_port)) {
                TextField(
                    state.port,
                    (if (compact) Modifier.fillMaxWidth() else Modifier.width(110.dp)).testTag("port"),
                    placeholder = state.portHint.toString(),
                    error = state.portInvalid,
                    inputTransformation = portDigits,
                )
            }
        }
        if (state.protocol == Access.MacTelnet && state.networkInterfaces.isNotEmpty()) {
            Labeled(stringResource(Res.string.connect_interface)) {
                Select(state.selectedInterface.ifEmpty { state.networkInterfaces.first() }, state.networkInterfaces, { it }, { state.selectedInterface = it }, field.testTag("interface"))
            }
        }
        if (state.protocol == Access.MacTelnet && state.typedMac) {
            Labeled(stringResource(Res.string.connect_own_mac)) { TextField(state.ownMac, field.testTag("own-mac"), placeholder = "aa:bb:cc:dd:ee:ff", mono = true) }
            Labeled("") { Text(stringResource(Res.string.connect_own_mac_hint), color = palette.muted, style = Theme.type.small) }
        }
        if (state.shut.isNotEmpty()) Labeled("") { Text(stringResource(if (state.standalone) Res.string.connect_browser_shut else Res.string.connect_probe_shut), color = palette.muted, style = Theme.type.small) }
        val community = state.protocol == Access.Snmp
        if (!community && state.protocol != Access.Vyos) Labeled(stringResource(Res.string.connect_user)) { TextField(state.user, field.testTag("user")) }
        Labeled(stringResource(if (community) Res.string.connect_community else Res.string.connect_password)) {
            FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val box = field.testTag("password")
                if (community) TextField(state.password, box) else SecretField(state.password, box, onGo = onConnect)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(state.remember, { state.remember = it })
                    Text(stringResource(if (community) Res.string.connect_remember_community else Res.string.connect_remember), Modifier.clickable { state.remember = !state.remember }.hand(), color = palette.muted)
                }
            }
        }
        val ownSsh = state.protocol != Access.Tcp8291 && state.protocol != Access.MacTelnet
        if (state.remember) state.secretProtection?.let { protection ->
            val label = when {
                !protection.isEncryptionOperational -> Res.string.connect_secrets_unavailable
                protection.effectiveLevel == KSafeProtectionLevel.SOFTWARE -> Res.string.connect_secrets_software
                else -> Res.string.connect_secrets_system
            }
            Labeled("") { Text(stringResource(label), Modifier.testTag("secrets-protection"), color = palette.muted, style = Theme.type.small) }
        }
        if ((state.kind == DeviceKind.RouterOs && ownSsh) || state.kind == DeviceKind.OpenWrt || state.protocol == Access.Ssh) Labeled(stringResource(Res.string.connect_ssh_key)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.key?.first ?: stringResource(Res.string.connect_ssh_key_none), (if (fill) Modifier.weight(1f) else Modifier.width(220.dp)).testTag("ssh-key"), color = palette.muted, singleLine = true)
                ToolbarButton(stringResource(Res.string.connect_ssh_key_choose), Lucide.Key, onKey)
                if (state.key != null) ToolbarButton(stringResource(Res.string.connect_remove), Lucide.X, { state.key = null })
                if (state.agentAvailable) {
                    Checkbox(state.agent, { state.agent = it }, Modifier.testTag("ssh-agent"))
                    Text(stringResource(Res.string.connect_ssh_agent), Modifier.clickable { state.agent = !state.agent }.hand(), color = palette.muted)
                }
            }
        }
        if (state.agentAvailable && ((state.kind == DeviceKind.RouterOs && ownSsh) || state.kind == DeviceKind.OpenWrt || state.protocol == Access.Ssh)) {
            Labeled("") { Text(stringResource(Res.string.connect_ssh_agent_hint), color = palette.muted, style = Theme.type.small) }
        }
        if (state.keyEncrypted) {
            Labeled(stringResource(Res.string.connect_ssh_passphrase)) { SecretField(state.passphrase, field.testTag("ssh-passphrase")) }
        }
        val through = saved.filter { it != state.fullAddress() }
        if (tcpListening && state.protocol != Access.Snmp && state.protocol != Access.Snmp3 && (through.isNotEmpty() || state.via.isNotEmpty())) {
            Labeled(stringResource(Res.string.connect_via)) {
                val direct = stringResource(Res.string.connect_via_direct)
                val choices = listOf("") + through
                Select(state.via, choices, { it.ifEmpty { direct } }, { state.via = it }, field.testTag("via"))
            }
            Labeled("") { Text(stringResource(Res.string.connect_via_hint), color = palette.muted, style = Theme.type.small) }
        }
        // OPNsense has no password for its API: the user is the API key, the password is its secret
        if (state.protocol == Access.Opnsense) Labeled("") { Text(stringResource(Res.string.connect_opnsense_key), color = palette.muted, style = Theme.type.small) }
        if (state.protocol == Access.Pfsense) Labeled("") { Text(stringResource(Res.string.connect_pfsense_key), color = palette.muted, style = Theme.type.small) }
        if (state.protocol == Access.KeeneticSsl || state.protocol == Access.Keenetic) {
            Labeled("") { Text(stringResource(Res.string.connect_keenetic_token), color = palette.muted, style = Theme.type.small) }
        }
        if (state.protocol == Access.Vyos) Labeled("") { Text(stringResource(Res.string.connect_vyos_key), color = palette.muted, style = Theme.type.small) }
        if (state.protocol == Access.Snmp) Labeled("") { Text(stringResource(Res.string.connect_snmp_community), color = palette.muted, style = Theme.type.small) }
        if (state.protocol == Access.Snmp3) {
            Labeled(stringResource(Res.string.connect_snmp3_auth)) {
                Select(state.snmpAuth, AuthProtocol.entries, { authLabel(it) }, { state.snmpAuth = it }, field.testTag("snmp-auth"))
            }
            if (state.password.text.isNotEmpty()) {
                Labeled(stringResource(Res.string.connect_snmp3_privacy)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Select(state.snmpPriv, PrivProtocol.entries, { privLabel(it) }, { state.snmpPriv = it }, Modifier.width(110.dp).testTag("snmp-priv"))
                        SecretField(state.privacy, (if (fill) Modifier.weight(1f) else Modifier.width(182.dp)).testTag("snmp-privacy"))
                    }
                }
            }
            Labeled("") { Text(stringResource(Res.string.connect_snmp3_user), color = palette.muted, style = Theme.type.small) }
            if (state.snmpPrivacy == null) Labeled("") { Text(stringResource(Res.string.connect_snmp3_plain), color = palette.warning) }
        }
        if (state.protocol == Access.Upnp) Labeled("") { Text(stringResource(Res.string.connect_upnp_port), color = palette.muted, style = Theme.type.small) }
        val plain = state.protocol == Access.Snmp || state.protocol == Access.Upnp || state.protocol == Access.Keenetic
        if (state.protocol.routerOs?.tls == false || state.protocol.openWrt?.encrypted == false || plain) {
            Labeled("") { Text(stringResource(if (community) Res.string.connect_plain_community else Res.string.connect_plain_warning), color = palette.warning) }
        }
        if (state.protocol.routerOs?.rest == false || state.protocol == Access.Tcp8291 || state.protocol == Access.MacTelnet) {
            Labeled(stringResource(Res.string.connect_encoding)) {
                Select(state.encoding, ApiEncoding.entries, { encodingLabel(it) }, { state.encoding = it }, field.testTag("encoding"))
            }
        }
        Labeled(stringResource(Res.string.connect_group)) { TextField(state.group, field) }
        Labeled(stringResource(Res.string.connect_comment)) { TextField(state.comment, field) }
        val buttons: @Composable () -> Unit = {
            val connecting = state.connecting
            if (connecting == null) {
                Button(stringResource(Res.string.connect), onConnect, primary = true)
            } else {
                Button(stringResource(Res.string.connect_stop), { state.stop() })
            }
            Button(stringResource(Res.string.connect_save), onSave, enabled = connecting == null)
            ToolbarButton(stringResource(Res.string.connect_open_file), Lucide.FolderOpen, onOpenFile, Modifier.testTag("open-file"), iconOnly = compact)
        }
        val indent = if (compact) 0.dp else LabelWidth + 10.dp
        if (fill) {
            FlowRow(
                Modifier.padding(start = indent, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) { buttons() }
            Box(Modifier.padding(start = indent)) { Status(state) }
        } else {
            Row(Modifier.padding(start = indent, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons()
                Status(state)
            }
        }
        val openHint = stringResource(Res.string.connect_open_file_hint)
        Text(if (compact) "${stringResource(Res.string.connect_open_file)}. $openHint" else openHint, Modifier.padding(start = indent), color = palette.muted, style = Theme.type.small)
    }
}

@Composable
private fun Header(onAbout: () -> Unit, onSettings: () -> Unit, onTools: () -> Unit, modifier: Modifier = Modifier, icons: Boolean = false) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(appIcon(), null, Modifier.size(36.dp))
        Column(Modifier.weight(1f)) {
            Text("NetworkHelm", style = Theme.type.strong, maxLines = 1)
            Text(appVersion, color = Theme.palette.muted, style = Theme.type.small, maxLines = 1)
        }
        val tools = stringResource(Res.string.tools_title)
        val about = stringResource(Res.string.main_about)
        val settings = stringResource(Res.string.settings_title)
        if (icons || LocalCompact.current) {
            IconButton(Lucide.Wrench, tools, onTools, Modifier.testTag("tools"))
            IconButton(Lucide.Info, about, onAbout, Modifier.testTag("about"))
            IconButton(Lucide.Settings, settings, onSettings, Modifier.testTag("settings"))
        } else {
            Button(tools, onTools, Modifier.testTag("tools"))
            Button(about, onAbout, Modifier.testTag("about"))
            Button(settings, onSettings, Modifier.testTag("settings"))
        }
    }
}

@Composable
private fun Lists(
    services: Services,
    saved: SavedRouters,
    state: ConnectState,
    onConnect: () -> Unit,
    onRemove: (SavedRouter) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    beside: Boolean,
    onPlace: (() -> Unit)?,
    modifier: Modifier,
) {
    var tab by remember { mutableStateOf(0) }
    val palette = Theme.palette
    val compact = LocalCompact.current
    val routers by saved.routers.collectAsState()
    Column(modifier) {
        BoxWithConstraints {
            // a narrow list beside the form has no room for the words of its buttons next to the tabs
            val narrow = compact || maxWidth < 560.dp
            Row(Modifier.padding(start = 4.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val tabs = listOf(stringResource(Res.string.connect_saved), stringResource(Res.string.connect_neighbors)) +
                    listOfNotNull(stringResource(Res.string.connect_scan).takeIf { services.scan != null })
                TabStrip(tabs, tab, { tab = it })
                Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    val imported = state.imported
                    if (tab == 0 && imported != null) {
                        Text(stringResource(Res.string.connect_imported, imported.first, imported.second), color = palette.muted, style = Theme.type.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (tab == 0) {
                    val import = stringResource(Res.string.connect_import)
                    val export = stringResource(Res.string.connect_export)
                    if (narrow) {
                        IconButton(Lucide.Upload, import, onImport, Modifier.testTag("import"))
                        if (canSeal) IconButton(Lucide.Download, export, onExport, Modifier.testTag("export"))
                    } else {
                        ToolbarButton(import, Lucide.Upload, onImport, Modifier.testTag("import"))
                        if (canSeal) ToolbarButton(export, Lucide.Download, onExport, Modifier.testTag("export"))
                    }
                }
                if (onPlace != null) {
                    IconButton(
                        if (beside) Lucide.PanelBottom else Lucide.PanelRight,
                        stringResource(if (beside) Res.string.connect_lists_below else Res.string.connect_lists_beside),
                        onPlace,
                        Modifier.testTag("lists-place"),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
        Box(Modifier.fillMaxSize()) {
            val scan = services.scan
            when {
                tab == 0 -> SavedTable(saved, state, onConnect, onRemove)
                tab == 2 && scan != null -> ScanTable(scan, services, state)
                else -> NeighborTable(services, state)
            }
            // an empty list tells what Import takes in its own body, where the words have room
            if (tab == 0 && routers.isEmpty() && state.imported == null) {
                Text(
                    stringResource(Res.string.connect_import_hint),
                    Modifier.align(Alignment.Center).widthIn(max = 420.dp).padding(16.dp).testTag("import-hint"),
                    color = palette.muted,
                    style = Theme.type.small,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SavedTable(saved: SavedRouters, state: ConnectState, onConnect: () -> Unit, onRemove: (SavedRouter) -> Unit) {
    val routers by saved.routers.collectAsState()
    var selected by remember { mutableStateOf<SavedRouter?>(null) }
    val kinds = DeviceKind.entries.associateWith { kindLabel(it) }
    val protocols = Access.entries.associateWith { protocolLabel(it) }
    val ui = rememberCoroutineScope()
    val compact = LocalCompact.current
    val removable = selected?.takeIf { it in routers }
    Column(Modifier.fillMaxSize()) {
        if (compact) removable?.let { RemoveRow(it, onRemove) }
        DataTable(
            rows = routers,
            columns = listOf(
                TableColumn("address", stringResource(Res.string.connect_address), 200.dp) { it.address },
                TableColumn("comment", stringResource(Res.string.connect_comment), 200.dp) { it.comment },
                TableColumn("user", stringResource(Res.string.connect_user), 120.dp) { it.user },
                TableColumn("platform", stringResource(Res.string.connect_platform), 150.dp) { kinds.getValue(it.protocol.kind) },
                TableColumn("protocol", stringResource(Res.string.connect_protocol), 200.dp) { protocols.getValue(it.protocol) },
            ),
            key = { it.address to it.user },
            onSelection = { keys ->
                selected = routers.firstOrNull { (it.address to it.user) in keys }
                selected?.let(state::fill)
            },
            onOpen = {
                val filling = state.fill(it)
                ui.launch {
                    filling.join()
                    if (state.problem == null) onConnect()
                }
            },
            caption = { router -> router.group.takeIf { it.isNotEmpty() && routers.firstOrNull { other -> other.group == it } == router } },
            detail = compact,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        if (!compact) removable?.let { RemoveRow(it, onRemove) }
    }
}

@Composable
private fun RemoveRow(router: SavedRouter, onRemove: (SavedRouter) -> Unit) {
    Row(Modifier.padding(6.dp)) { Button(stringResource(Res.string.connect_remove), { onRemove(router) }) }
}

@Composable
private fun NeighborTable(services: Services, state: ConnectState) {
    val neighbors = services.neighbors
    if (neighbors == null) {
        Text(stringResource(Res.string.connect_neighbors_unavailable), Modifier.padding(12.dp), color = Theme.palette.muted)
        return
    }
    val palette = Theme.palette
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    var deaf by remember { mutableStateOf(false) }
    val noLocalNetwork = stringResource(Res.string.error_local_network)
    val notSent = stringResource(Res.string.neighbors_not_sent)
    val heard = remember { mutableStateMapOf<String, MndpSighting>() }
    val tableState = remember { TableState<String>() }
    LaunchedEffect(neighbors) {
        try {
            neighbors.collect { sighting -> heard[sighting.announcement.mac ?: sighting.sourceAddress] = sighting }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            deaf = true
        }
    }
    val rows = heard.values.sortedWith(compareBy({ it.announcement.identity.orEmpty() }, { it.announcement.mac.orEmpty() }))
    fun ask(refresh: suspend () -> Unit) {
        problem = null
        scope.launch {
            problem = when {
                !services.localNetwork() -> noLocalNetwork
                else -> try {
                    refresh()
                    null
                } catch (_: DeviceException) {
                    notSent
                }
            }
        }
    }
    // the routers announce themselves once in 30 to 60 s, so the tab asks them once as it opens
    LaunchedEffect(Unit) { services.refreshNeighbors?.let(::ask) }
    fun fill(sighting: MndpSighting, column: String?) {
        val mac = sighting.announcement.mac.orEmpty()
        val ip = sighting.announcement.ipv4 ?: sighting.sourceAddress
        val byMac = mac.isNotEmpty() && services.connectMacTelnet != null
        val noIp = ip.isEmpty() || ip == "0.0.0.0"
        when {
            byMac && (column == "mac" || noIp) -> {
                state.fillAddress(mac)
                state.pick(DeviceKind.RouterOs)
                state.pick(Access.MacTelnet)
            }
            !noIp -> state.fillAddress(ip)
        }
    }
    Column(Modifier.fillMaxSize()) {
        services.refreshNeighbors?.let { refresh ->
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(stringResource(Res.string.neighbors_refresh), { ask(refresh) }, Modifier.testTag("neighbors-refresh"))
                Text(
                    problem ?: stringResource(if (deaf) Res.string.neighbors_not_heard else Res.string.neighbors_hint),
                    Modifier.testTag("neighbors-status"),
                    color = if (problem != null || deaf) palette.danger else palette.muted,
                    style = Theme.type.small,
                )
            }
        }
        DataTable(
            rows = rows,
            columns = listOf(
                TableColumn("mac", stringResource(Res.string.neighbor_mac), 140.dp) { it.announcement.mac.orEmpty() },
                TableColumn("ip", stringResource(Res.string.neighbor_ip), 130.dp) { (it.announcement.ipv4 ?: it.sourceAddress).takeIf { ip -> ip != "0.0.0.0" }.orEmpty() },
                TableColumn("interface", stringResource(Res.string.neighbor_interface), 130.dp) { it.announcement.interfaceName.orEmpty() },
                TableColumn("version", stringResource(Res.string.neighbor_version), 160.dp) { it.announcement.version.orEmpty() },
                TableColumn("board", stringResource(Res.string.neighbor_board), 160.dp) { it.announcement.board.orEmpty() },
            ),
            key = { it.announcement.mac ?: it.sourceAddress },
            state = tableState,
            onOpen = { sighting -> fill(sighting, null) },
            onOpenColumn = ::fill,
            caption = { sighting ->
                val identity = sighting.announcement.identity.orEmpty()
                identity.takeIf { rows.firstOrNull { it.announcement.identity.orEmpty() == identity } === sighting }
            },
            menu = if (services.connectMacTelnet == null) null else { close ->
                val chosen = rows.singleOrNull { (it.announcement.mac ?: it.sourceAddress) in tableState.selected }
                val mac = chosen?.announcement?.mac
                if (!mac.isNullOrEmpty()) {
                    DropdownItem({
                        close()
                        state.fillAddress(mac)
                        state.pick(DeviceKind.RouterOs)
                        state.pick(Access.MacTelnet)
                    }) {
                        Text(stringResource(Res.string.neighbor_connect_mac_telnet))
                    }
                }
            },
            detail = LocalCompact.current,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun ScanTable(scan: (List<String>) -> Flow<Found>, services: Services, state: ConnectState) {
    val palette = Theme.palette
    val scope = rememberCoroutineScope()
    val network = remember { TextFieldState() }
    val found = remember { mutableStateListOf<Found>() }
    var job by remember { mutableStateOf<Job?>(null) }
    var checked by remember { mutableStateOf<Int?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val hosts = hosts(network.text.toString())
    val noLocalNetwork = stringResource(Res.string.error_local_network)
    fun start(addresses: List<String>) {
        found.clear()
        checked = null
        problem = null
        job = scope.launch {
            try {
                if (!services.localNetwork()) {
                    problem = noLocalNetwork
                    return@launch
                }
                scan(addresses).collect { found += it }
                checked = addresses.size
            } catch (e: DeviceException) {
                problem = getString(Res.string.error_unsupported, e.message.orEmpty())
            } finally {
                job = null
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(network, Modifier.width(170.dp).testTag("scan-network"), placeholder = "192.168.88.0/24")
            val running = job
            if (running != null) {
                Button(stringResource(Res.string.scan_stop), { running.cancel() }, Modifier.testTag("scan"))
            } else {
                Button(stringResource(Res.string.scan_start), { hosts?.let(::start) }, Modifier.testTag("scan"), enabled = hosts != null)
            }
            val done = checked
            val text = when {
                running != null -> stringResource(Res.string.scan_running, hosts?.size ?: 0)
                problem != null -> problem.orEmpty()
                done != null -> stringResource(Res.string.scan_done, found.size, done)
                else -> stringResource(Res.string.scan_hint)
            }
            Text(text, Modifier.testTag("scan-status"), color = if (problem != null) palette.danger else palette.muted, style = Theme.type.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        DataTable(
            rows = found.sortedBy { it.address.split('.').fold(0L) { acc, byte -> acc * 256 + byte.toLong() } },
            columns = listOf(
                TableColumn("address", stringResource(Res.string.connect_address), 160.dp) { it.address },
                TableColumn("ports", stringResource(Res.string.scan_ports), 220.dp) { it.ports.joinToString(", ") },
            ),
            key = { it.address },
            onOpen = { router ->
                state.fillAddress(router.address)
                when {
                    8291 in router.ports -> state.pick(Access.Tcp8291)
                    8729 in router.ports -> state.pick(Access.ApiSsl)
                    8728 in router.ports -> state.pick(Access.Api)
                }
            },
            detail = LocalCompact.current,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
internal fun TrustDialog(question: TrustQuestion) {
    val palette = Theme.palette
    val request = question.request
    val target = "${request.host}:${request.port}"
    val text = when {
        request.previous == null && request.kind == TrustKind.Tls -> stringResource(Res.string.trust_new_tls, target)
        request.previous == null -> stringResource(Res.string.trust_new_ssh, target)
        request.kind == TrustKind.Tls -> stringResource(Res.string.trust_changed_tls, target)
        else -> stringResource(Res.string.trust_changed_ssh, target)
    }
    Box(Modifier.fillMaxSize().scrim(palette.workspace.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).dialogPanel(16.dp).testTag("trust"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text, color = if (request.previous == null) palette.text else palette.warning)
            Text(stringResource(Res.string.trust_fingerprint), color = palette.muted)
            Text(request.fingerprint, style = Theme.type.mono)
            request.previous?.let {
                Text(stringResource(Res.string.trust_previous), color = palette.muted)
                Text(it, style = Theme.type.mono, color = palette.muted)
            }
            Row(Modifier.align(Alignment.End).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(stringResource(Res.string.trust_reject), { question.answer(false) })
                Button(stringResource(Res.string.trust_accept), { question.answer(true) }, primary = request.previous == null)
            }
        }
    }
}

@Composable
private fun encodingLabel(encoding: ApiEncoding) = stringResource(
    when (encoding) {
        ApiEncoding.Utf8 -> Res.string.encoding_utf8
        ApiEncoding.Windows1251 -> Res.string.encoding_cp1251
    },
)

private fun authLabel(auth: AuthProtocol) = when (auth) {
    AuthProtocol.Md5 -> "MD5"
    AuthProtocol.Sha1 -> "SHA-1"
}

private fun privLabel(priv: PrivProtocol) = when (priv) {
    PrivProtocol.Des -> "DES"
    PrivProtocol.Aes -> "AES-128"
}

@Composable
internal fun protocolLabel(protocol: Access) = stringResource(
    when (protocol) {
        Access.MacTelnet -> Res.string.protocol_mac_telnet
        Access.Tcp8291 -> Res.string.protocol_tcp_8291
        Access.ApiSsl -> Res.string.protocol_api_ssl
        Access.Api -> Res.string.protocol_api
        Access.RestSsl -> Res.string.protocol_rest_ssl
        Access.Rest -> Res.string.protocol_rest
        Access.OpenWrtSsh -> Res.string.protocol_openwrt_ssh
        Access.OpenWrtUbusSsl -> Res.string.protocol_openwrt_https
        Access.OpenWrtUbus -> Res.string.protocol_openwrt_http
        Access.Ssh -> Res.string.protocol_ssh
        Access.Opnsense -> Res.string.protocol_opnsense
        Access.Pfsense -> Res.string.protocol_pfsense
        Access.KeeneticSsl -> Res.string.protocol_keenetic_https
        Access.Keenetic -> Res.string.protocol_keenetic_http
        Access.Vyos -> Res.string.protocol_vyos
        Access.Snmp -> Res.string.protocol_snmp
        Access.Snmp3 -> Res.string.protocol_snmp3
        Access.Upnp -> Res.string.protocol_upnp
    },
)

@Composable
private fun kindLabel(kind: DeviceKind) = stringResource(
    when (kind) {
        DeviceKind.RouterOs -> Res.string.kind_routeros
        DeviceKind.OpenWrt -> Res.string.kind_openwrt
        DeviceKind.Opnsense -> Res.string.kind_opnsense
        DeviceKind.Pfsense -> Res.string.kind_pfsense
        DeviceKind.Keenetic -> Res.string.kind_keenetic
        DeviceKind.Vyos -> Res.string.kind_vyos
        DeviceKind.Other -> Res.string.kind_other
    },
)

@Composable
private fun problemText(problem: Problem): String = when (problem) {
    Problem.Address -> stringResource(Res.string.error_address)
    Problem.Port -> stringResource(Res.string.error_port)
    Problem.Rejected -> stringResource(Res.string.error_rejected)
    is Problem.Login -> stringResource(Res.string.error_login, problem.message)
    is Problem.Refused -> stringResource(Res.string.error_refused, problem.host, problem.port)
    is Problem.Timeout -> stringResource(Res.string.error_timeout, problem.host)
    is Problem.UnknownHost -> stringResource(Res.string.error_unknown_host, problem.host)
    is Problem.Unreachable -> stringResource(Res.string.error_unreachable, problem.host)
    Problem.LocalNetwork -> stringResource(Res.string.error_local_network)
    is Problem.Handshake -> stringResource(Res.string.error_handshake, problem.host)
    is Problem.Unsupported -> stringResource(Res.string.error_unsupported, problem.message)
    is Problem.Browser -> stringResource(if (problem.routerOs) Res.string.error_browser_routeros else Res.string.error_browser, problem.host)
    Problem.MacTelnetUnsupported -> stringResource(Res.string.error_mac_telnet_unsupported)
    Problem.MacTelnetNoInterface -> stringResource(Res.string.error_mac_telnet_no_interface)
    Problem.OwnMac -> stringResource(Res.string.error_own_mac)
    Problem.Tcp8291Unsupported -> stringResource(Res.string.error_tcp_8291_unsupported)
    Problem.NoRest -> stringResource(Res.string.error_no_rest)
    is Problem.Other -> stringResource(Res.string.error_other, problem.host, problem.message)
    is Problem.SavedList -> stringResource(Res.string.error_saved_list, problem.message)
    is Problem.Secrets -> stringResource(Res.string.error_secrets, problem.message)
    Problem.BookPassword -> stringResource(Res.string.error_book_password)
    Problem.BookInvalid -> stringResource(Res.string.error_book_invalid)
    is Problem.BookFile -> stringResource(Res.string.error_book_file, problem.message)
    Problem.WinBoxFile -> stringResource(Res.string.error_winbox_file)
    is Problem.NotConfig -> stringResource(Res.string.error_not_config, problem.file)
    is Problem.Unreadable -> stringResource(Res.string.error_unreadable, problem.file, problem.message)
    is Problem.ThroughMissing -> stringResource(Res.string.error_through_missing, problem.address)
    Problem.ThroughCircle -> stringResource(Res.string.error_through_circle)
    is Problem.NoSnmp -> stringResource(if (problem.v3) Res.string.error_no_snmp3 else Res.string.error_no_snmp, problem.host)
    Problem.SnmpThrough -> stringResource(Res.string.error_snmp_through)
    is Problem.NoUpnp -> stringResource(Res.string.error_no_upnp, problem.host)
    Problem.UpnpThrough -> stringResource(Res.string.error_upnp_through)
}
