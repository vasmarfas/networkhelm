package com.vasmarfas.networkhelm.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.nettools.Ipv4
import com.vasmarfas.networkhelm.nettools.Ipv4Subnet
import com.vasmarfas.networkhelm.nettools.Subnets
import com.vasmarfas.networkhelm.ui.kit.Labeled
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.Segments
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.table.DataTable
import com.vasmarfas.networkhelm.ui.table.TableColumn
import com.vasmarfas.networkhelm.ui.theme.Theme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.tools_b_by_prefix
import networkhelm.app.shared.generated.resources.tools_b_equal
import networkhelm.app.shared.generated.resources.tools_b_hosts_needed
import networkhelm.app.shared.generated.resources.tools_b_into_parts
import networkhelm.app.shared.generated.resources.tools_b_list
import networkhelm.app.shared.generated.resources.tools_b_need
import networkhelm.app.shared.generated.resources.tools_b_no_room
import networkhelm.app.shared.generated.resources.tools_b_not_a_network
import networkhelm.app.shared.generated.resources.tools_b_not_one_network
import networkhelm.app.shared.generated.resources.tools_b_parts_count
import networkhelm.app.shared.generated.resources.tools_b_prefix_length
import networkhelm.app.shared.generated.resources.tools_b_prefix_range
import networkhelm.app.shared.generated.resources.tools_b_rows_capped
import networkhelm.app.shared.generated.resources.tools_b_split_by
import networkhelm.app.shared.generated.resources.tools_b_split_info
import networkhelm.app.shared.generated.resources.tools_b_summary
import networkhelm.app.shared.generated.resources.tools_b_summary_info
import networkhelm.app.shared.generated.resources.tools_b_too_many_parts
import networkhelm.app.shared.generated.resources.tools_b_used
import networkhelm.app.shared.generated.resources.tools_b_vlsm
import networkhelm.app.shared.generated.resources.tools_first_host
import networkhelm.app.shared.generated.resources.tools_hosts
import networkhelm.app.shared.generated.resources.tools_last_host
import networkhelm.app.shared.generated.resources.tools_netmask
import networkhelm.app.shared.generated.resources.tools_network

private typealias SubnetRow = IndexedValue<Ipv4Subnet>

private const val MAX_ROWS = 1024

private enum class SplitMode(val title: StringResource) {
    Equal(Res.string.tools_b_equal),
    Vlsm(Res.string.tools_b_vlsm),
    Summary(Res.string.tools_b_summary),
}

@Composable
internal fun SplitPane() {
    var mode by remember { mutableStateOf(SplitMode.Equal) }
    var byPrefix by remember { mutableStateOf(false) }
    val network = remember { TextFieldState("192.168.0.0/24") }
    val parts = remember { TextFieldState("4") }
    val prefix = remember { TextFieldState("26") }
    val hosts = remember { TextFieldState("100, 50, 20, 2") }
    val list = remember { TextFieldState("10.0.0.0/24\n10.0.1.0/24\n10.0.2.0/23") }
    Segments(mode, SplitMode.entries, { stringResource(it.title) }, { mode = it }, Modifier.testTag("split-mode"))
    when (mode) {
        SplitMode.Equal -> EqualSplit(network, parts, prefix, byPrefix) { byPrefix = it }
        SplitMode.Vlsm -> VlsmSplit(network, hosts)
        SplitMode.Summary -> SummaryList(list)
    }
}

@Composable
private fun NetworkField(state: TextFieldState, error: Boolean) {
    Labeled(stringResource(Res.string.tools_network)) {
        TextField(state, Modifier.width(FieldWidth).testTag("split-network"), placeholder = "192.168.0.0/24", mono = true, error = error)
    }
}

@Composable
private fun EqualSplit(network: TextFieldState, parts: TextFieldState, prefix: TextFieldState, byPrefix: Boolean, onBy: (Boolean) -> Unit) {
    val typed = network.text.toString()
    val whole = remember(typed) { Subnets.network(typed) }
    val notNetwork = typed.isNotBlank() && whole == null
    NetworkField(network, notNetwork)
    if (notNetwork && Subnets.span(typed) != null) Problem(stringResource(Res.string.tools_b_not_one_network))
    Labeled(stringResource(Res.string.tools_b_split_by)) {
        Segments(byPrefix, listOf(false, true), { stringResource(if (it) Res.string.tools_b_by_prefix else Res.string.tools_b_into_parts) }, onBy, Modifier.testTag("split-by"))
    }
    val field = if (byPrefix) prefix else parts
    val given = field.text.toString().trim().toIntOrNull()?.takeIf { byPrefix || it > 0 }
    val chosen = if (whole == null || given == null) null else if (byPrefix) given.takeIf { it in whole.prefix..32 } else Subnets.prefixFor(whole, given)
    Labeled(stringResource(if (byPrefix) Res.string.tools_b_prefix_length else Res.string.tools_b_parts_count)) {
        TextField(field, Modifier.width(NumberWidth).testTag("split-number"), mono = true, error = field.text.isNotBlank() && (given == null || (whole != null && chosen == null)))
    }
    if (whole != null && given != null && chosen == null) {
        Problem(if (byPrefix) stringResource(Res.string.tools_b_prefix_range, whole.prefix) else stringResource(Res.string.tools_b_too_many_parts))
    }
    if (whole != null && chosen != null) {
        val subnets = remember(typed, chosen) { Subnets.split(whole, chosen).take(MAX_ROWS).toList() }
        val total = 1L shl (chosen - whole.prefix)
        Text(stringResource(Res.string.tools_b_split_info, total, chosen, subnets.first().usableHosts), color = Theme.palette.muted, style = Theme.type.small)
        SubnetTable(subnets, "split-table")
        if (total > subnets.size) Text(stringResource(Res.string.tools_b_rows_capped, subnets.size, total), color = Theme.palette.muted, style = Theme.type.small)
    }
}

@Composable
private fun VlsmSplit(network: TextFieldState, hosts: TextFieldState) {
    val typed = network.text.toString()
    val span = remember(typed) { Subnets.span(typed) }
    NetworkField(network, typed.isNotBlank() && span == null)
    val needed = hosts.text.toString()
    val counts = remember(needed) { Subnets.hostCounts(needed) }
    Labeled(stringResource(Res.string.tools_b_hosts_needed)) {
        TextField(hosts, Modifier.width(FieldWidth).testTag("split-hosts"), placeholder = "100, 50, 20", mono = true, error = needed.isNotBlank() && counts == null)
    }
    if (span == null || counts == null) return
    val plan = remember(span, counts) { Subnets.allocate(span.first, span.second, counts) }
    if (plan == null) {
        Problem(stringResource(Res.string.tools_b_no_room))
        return
    }
    Fact(stringResource(Res.string.tools_b_used), "${plan.sumOf { it.subnet.totalAddresses }} / ${span.second - span.first + 1}")
    SubnetTable(plan.map { it.subnet }, "split-table", plan.map { it.need })
}

@Composable
private fun SummaryList(list: TextFieldState) {
    val palette = Theme.palette
    val shape = RoundedCornerShape(Theme.metrics.radius)
    val typed = list.text.toString()
    val entries = remember(typed) { Subnets.items(typed).map { it to Ipv4.parseRange(it) } }
    val unreadable = entries.firstOrNull { it.second == null }?.first
    Labeled(stringResource(Res.string.tools_b_list)) {
        BasicTextField(
            list,
            Modifier.width(360.dp).background(palette.surface, shape).border(1.dp, if (unreadable != null) palette.danger else palette.line, shape).padding(6.dp).testTag("split-list"),
            textStyle = Theme.type.mono.copy(color = palette.text),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            cursorBrush = SolidColor(palette.accent),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3),
        )
    }
    if (unreadable != null) {
        Problem(stringResource(Res.string.tools_b_not_a_network, unreadable))
    } else if (entries.isNotEmpty()) {
        val blocks = remember(typed) { Subnets.summarize(entries.mapNotNull { it.second }) }
        Text(stringResource(Res.string.tools_b_summary_info, entries.size, blocks.size), color = palette.muted, style = Theme.type.small)
        SubnetTable(blocks, "split-table")
    }
}

@Composable
private fun SubnetTable(subnets: List<Ipv4Subnet>, tag: String, needs: List<Int>? = null) {
    val rows = remember(subnets) { subnets.withIndex().toList() }
    val columns = listOfNotNull(
        needs?.let { counts -> TableColumn<SubnetRow>("need", stringResource(Res.string.tools_b_need), null, widest = "4294967294") { counts[it.index].toString() } },
        TableColumn<SubnetRow>("network", stringResource(Res.string.tools_network), null, widest = "255.255.255.255/32") { it.value.cidr },
        TableColumn<SubnetRow>("first", stringResource(Res.string.tools_first_host), null, widest = "255.255.255.255") { Ipv4.format(it.value.firstHost) },
        TableColumn<SubnetRow>("last", stringResource(Res.string.tools_last_host), null, widest = "255.255.255.255") { Ipv4.format(it.value.lastHost) },
        TableColumn<SubnetRow>("hosts", stringResource(Res.string.tools_hosts), null, widest = "4294967294") { it.value.usableHosts.toString() },
        TableColumn<SubnetRow>("mask", stringResource(Res.string.tools_netmask), null, widest = "255.255.255.255") { Ipv4.format(it.value.mask) },
    )
    DataTable(
        rows = rows,
        columns = columns,
        key = { it.index },
        detail = LocalCompact.current,
        modifier = Modifier.fillMaxWidth().height(TableHeight).testTag(tag),
    )
}
