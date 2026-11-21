package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.app.HiddenValue
import com.vasmarfas.networkhelm.app.LocalSettings
import com.vasmarfas.networkhelm.journal.EditJournal
import com.vasmarfas.networkhelm.journal.EditRecord
import com.vasmarfas.networkhelm.journal.command
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.sensitive
import com.vasmarfas.networkhelm.ui.table.DataTable
import com.vasmarfas.networkhelm.ui.table.TableColumn
import com.vasmarfas.networkhelm.ui.table.TableState
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.journal_command
import networkhelm.app.shared.generated.resources.journal_empty
import networkhelm.app.shared.generated.resources.journal_time

@Composable
internal fun JournalView(journal: EditJournal, platform: Platform) {
    val palette = Theme.palette
    val records by journal.records.collectAsState()
    val state = remember { TableState<Int>() }
    val zone = remember { TimeZone.currentSystemDefault() }
    val hide = LocalSettings.current.hidePasswords
    fun time(record: EditRecord) = record.time.toLocalDateTime(zone).time.let { listOf(it.hour, it.minute, it.second) }
        .joinToString(":") { it.toString().padStart(2, '0') }
    fun written(record: EditRecord) = (if (hide) record.copy(after = record.after.mapValues { (name, value) -> if (!value.isNullOrEmpty() && (name in record.secret || sensitive(name))) HiddenValue else value }) else record)
        .command(platform)
    Column(Modifier.fillMaxSize().background(palette.surface)) {
        if (records.isEmpty()) Note(stringResource(Res.string.journal_empty))
        DataTable(
            rows = records.withIndex().toList(),
            columns = listOf(
                TableColumn("time", stringResource(Res.string.journal_time), 80.dp) { time(it.value) },
                TableColumn("command", stringResource(Res.string.journal_command), 600.dp) { written(it.value).lines().joinToString("; ") },
            ),
            key = { it.index },
            state = state,
            follow = true,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        val chosen = records.filterIndexed { index, _ -> index in state.selected }.ifEmpty { records }
        SelectionContainer {
            Text(
                chosen.joinToString("\n") { written(it) },
                Modifier.fillMaxWidth().heightIn(max = 140.dp).background(palette.raised).verticalScroll(rememberScrollState()).padding(8.dp),
                style = Theme.type.mono,
            )
        }
    }
}
