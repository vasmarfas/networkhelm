package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.app.HiddenValue
import com.vasmarfas.networkhelm.app.LocalSettings
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.sensitive
import com.vasmarfas.networkhelm.offline.MenuDifference
import com.vasmarfas.networkhelm.offline.compare
import com.vasmarfas.networkhelm.offline.configOf
import com.vasmarfas.networkhelm.routeros.cliValue
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.compare_reading
import networkhelm.app.shared.generated.resources.compare_same
import networkhelm.app.shared.generated.resources.compare_summary
import networkhelm.app.shared.generated.resources.compare_unsupported

@Stable
class Comparison {
    var differences by mutableStateOf<List<MenuDifference>?>(null)
    var failure by mutableStateOf<DeviceException?>(null)

    suspend fun run(platform: Platform, before: suspend () -> String, after: suspend () -> String) {
        try {
            val old = before()
            val new = after()
            differences = withContext(Dispatchers.Default) { compare(configOf(platform, old), configOf(platform, new)) }
        } catch (e: DeviceException) {
            failure = e
        }
    }
}

@Composable
internal fun CompareView(tab: RouterTab, comparison: Comparison) {
    val palette = Theme.palette
    val differences = comparison.differences
    val hide = LocalSettings.current.hidePasswords
    fun shown(name: String, value: String?) = value?.let { if (hide && it.isNotEmpty() && sensitive(name)) HiddenValue else it }
    Column(Modifier.fillMaxSize().background(palette.surface).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val failure = comparison.failure
        val status = when {
            // OpenWrt gives uci export over SSH only
            failure?.category == ErrorCategory.Unsupported -> stringResource(Res.string.compare_unsupported)
            failure != null -> failure.message?.let { deviceMessage(it) }
            differences == null -> stringResource(Res.string.compare_reading)
            differences.isEmpty() -> stringResource(Res.string.compare_same)
            else -> stringResource(Res.string.compare_summary, differences.size)
        }
        Text(status.orEmpty(), Modifier.testTag("compare-status"), color = if (failure != null) palette.danger else palette.muted, style = Theme.type.small)
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                for (difference in differences.orEmpty()) {
                    item { MenuHeader(tab, difference) }
                    items(difference.removed.size) { Line("−", line(difference.removed[it], ::shown), palette.danger) }
                    items(difference.added.size) { Line("+", line(difference.added[it], ::shown), palette.success) }
                    items(difference.changed.size) { index ->
                        val (before, after) = difference.changed[index]
                        if (after.id.isNotEmpty() || before.id.isNotEmpty()) Line("~", name(after) ?: line(before, ::shown), palette.warning)
                        for (property in (before.values.keys + after.values.keys).distinct().filter { before[it] != after[it] }) {
                            Line(" ", "  $property: ${shown(property, before[property]) ?: "—"} → ${shown(property, after[property]) ?: "—"}", palette.text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(tab: RouterTab, difference: MenuDifference) {
    val palette = Theme.palette
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(trail(tab.placeOf(difference.path), palette.muted), maxLines = 1)
        Text(difference.path.cli, color = palette.faint, style = Theme.type.small, maxLines = 1)
        Text("+${difference.added.size} −${difference.removed.size} ~${difference.changed.size}", color = palette.muted, style = Theme.type.small)
    }
}

@Composable
private fun Line(mark: String, text: String, colour: Color) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp)) {
        Text(mark, Modifier.padding(end = 6.dp), color = colour, style = Theme.type.mono)
        Text(text, color = colour, style = Theme.type.mono)
    }
}

private fun name(item: Item): String? = item[".name"]?.let { ".name=$it" } ?: item["name"]?.let { "name=${cliValue(it)}" } ?: item["default-name"]?.let { "default-name=$it" }

private fun line(item: Item, shown: (String, String) -> String?): String =
    item.values.entries.joinToString(" ") { (name, value) -> "$name=${cliValue(shown(name, value) ?: value)}" }
