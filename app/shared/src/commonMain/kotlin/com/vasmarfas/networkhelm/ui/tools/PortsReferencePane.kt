package com.vasmarfas.networkhelm.ui.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.nettools.WellKnownPorts
import com.vasmarfas.networkhelm.ui.kit.Labeled
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.table.DataTable
import com.vasmarfas.networkhelm.ui.table.TableColumn
import com.vasmarfas.networkhelm.ui.theme.Theme
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.search_nothing
import networkhelm.app.shared.generated.resources.tools_b_description
import networkhelm.app.shared.generated.resources.tools_b_ports_filter
import networkhelm.app.shared.generated.resources.tools_b_protocol
import networkhelm.app.shared.generated.resources.tools_port
import networkhelm.app.shared.generated.resources.tools_service

@Composable
internal fun PortsReferencePane() {
    val filter = remember { TextFieldState() }
    val query = filter.text.toString().trim()
    val notes = PortDescriptions.mapValues { stringResource(it.value) }
    val rows = WellKnownPorts.all.filter { it.matches(query, notes.getValue(it.port)) }
    Labeled(stringResource(Res.string.tools_b_ports_filter)) {
        TextField(filter, Modifier.width(FieldWidth).testTag("ports-ref-filter"), placeholder = "ssh · 443 · udp", mono = true)
    }
    if (rows.isEmpty()) {
        Text(stringResource(Res.string.search_nothing), color = Theme.palette.muted, style = Theme.type.small)
        return
    }
    DataTable(
        rows = rows,
        columns = listOf(
            TableColumn("port", stringResource(Res.string.tools_port), 60.dp) { it.port.toString() },
            TableColumn("protocol", stringResource(Res.string.tools_b_protocol), 76.dp) { it.protocol },
            TableColumn("service", stringResource(Res.string.tools_service), 130.dp) { it.service },
            TableColumn("description", stringResource(Res.string.tools_b_description), 340.dp) { notes.getValue(it.port) },
        ),
        key = { it.port },
        highlight = query,
        detail = LocalCompact.current,
        // the list is all this tool has, so it takes the room the others give their forms
        modifier = Modifier.fillMaxWidth().height(TableHeight + 200.dp).testTag("ports-ref-table"),
    )
}
