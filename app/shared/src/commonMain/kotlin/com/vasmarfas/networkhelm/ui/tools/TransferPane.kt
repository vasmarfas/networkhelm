package com.vasmarfas.networkhelm.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.nettools.SizeUnit
import com.vasmarfas.networkhelm.nettools.SpeedUnit
import com.vasmarfas.networkhelm.nettools.Transfer
import com.vasmarfas.networkhelm.ui.kit.Labeled
import com.vasmarfas.networkhelm.ui.kit.Segments
import com.vasmarfas.networkhelm.ui.kit.Select
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.theme.Theme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.tools_b_find
import networkhelm.app.shared.generated.resources.tools_b_hours
import networkhelm.app.shared.generated.resources.tools_b_minutes
import networkhelm.app.shared.generated.resources.tools_b_other_speeds
import networkhelm.app.shared.generated.resources.tools_b_overhead
import networkhelm.app.shared.generated.resources.tools_b_seconds
import networkhelm.app.shared.generated.resources.tools_b_size
import networkhelm.app.shared.generated.resources.tools_b_speed
import networkhelm.app.shared.generated.resources.tools_b_time
import networkhelm.app.shared.generated.resources.unit_b
import networkhelm.app.shared.generated.resources.unit_b_s
import networkhelm.app.shared.generated.resources.unit_bit_s
import networkhelm.app.shared.generated.resources.unit_gb
import networkhelm.app.shared.generated.resources.unit_gb_s
import networkhelm.app.shared.generated.resources.unit_gbit_s
import networkhelm.app.shared.generated.resources.unit_gib
import networkhelm.app.shared.generated.resources.unit_kb
import networkhelm.app.shared.generated.resources.unit_kb_s
import networkhelm.app.shared.generated.resources.unit_kbit_s
import networkhelm.app.shared.generated.resources.unit_kib
import networkhelm.app.shared.generated.resources.unit_mb
import networkhelm.app.shared.generated.resources.unit_mb_s
import networkhelm.app.shared.generated.resources.unit_mbit_s
import networkhelm.app.shared.generated.resources.unit_mib
import networkhelm.app.shared.generated.resources.unit_tb
import networkhelm.app.shared.generated.resources.unit_tib

private val SizeUnit.label: StringResource
    get() = when (this) {
        SizeUnit.B -> Res.string.unit_b
        SizeUnit.KB -> Res.string.unit_kb
        SizeUnit.MB -> Res.string.unit_mb
        SizeUnit.GB -> Res.string.unit_gb
        SizeUnit.TB -> Res.string.unit_tb
        SizeUnit.KiB -> Res.string.unit_kib
        SizeUnit.MiB -> Res.string.unit_mib
        SizeUnit.GiB -> Res.string.unit_gib
        SizeUnit.TiB -> Res.string.unit_tib
    }

private val SpeedUnit.label: StringResource
    get() = when (this) {
        SpeedUnit.Bit -> Res.string.unit_bit_s
        SpeedUnit.Kbit -> Res.string.unit_kbit_s
        SpeedUnit.Mbit -> Res.string.unit_mbit_s
        SpeedUnit.Gbit -> Res.string.unit_gbit_s
        SpeedUnit.Bps -> Res.string.unit_b_s
        SpeedUnit.KBps -> Res.string.unit_kb_s
        SpeedUnit.MBps -> Res.string.unit_mb_s
        SpeedUnit.GBps -> Res.string.unit_gb_s
    }

private enum class Wanted(val title: StringResource) {
    Time(Res.string.tools_b_time),
    Speed(Res.string.tools_b_speed),
    Size(Res.string.tools_b_size),
}

@Composable
internal fun TransferPane() {
    var find by remember { mutableStateOf(Wanted.Time) }
    var sizeUnit by remember { mutableStateOf(SizeUnit.GB) }
    var speedUnit by remember { mutableStateOf(SpeedUnit.Mbit) }
    val size = remember { TextFieldState("1") }
    val speed = remember { TextFieldState("100") }
    val hours = remember { TextFieldState("0") }
    val minutes = remember { TextFieldState("1") }
    val seconds = remember { TextFieldState("20") }
    val overhead = remember { TextFieldState("0") }
    val hour = stringResource(Res.string.tools_b_hours)
    val minute = stringResource(Res.string.tools_b_minutes)
    val second = stringResource(Res.string.tools_b_seconds)
    val sizeValue = Transfer.amount(size.text.toString())
    val speedValue = Transfer.amount(speed.text.toString())
    val timeValue = Transfer.time(hours.text.toString(), minutes.text.toString(), seconds.text.toString())
    val extra = overhead.text.toString().let { if (it.isBlank()) 0.0 else Transfer.amount(it) }
    val timeResult = if (find == Wanted.Time && sizeValue != null && speedValue != null && extra != null) Transfer.seconds(sizeValue, sizeUnit, speedValue, speedUnit, extra) else null
    val speedResult = if (find == Wanted.Speed && sizeValue != null && timeValue != null && extra != null) Transfer.speed(sizeValue, sizeUnit, timeValue, speedUnit, extra) else null
    val sizeResult = if (find == Wanted.Size && speedValue != null && timeValue != null && extra != null) Transfer.size(speedValue, speedUnit, timeValue, sizeUnit, extra) else null
    Labeled(stringResource(Res.string.tools_b_find)) {
        Segments(find, Wanted.entries, { stringResource(it.title) }, { find = it }, Modifier.testTag("transfer-find"))
    }
    Labeled(stringResource(Res.string.tools_b_size)) {
        Quantity(size, sizeResult, find != Wanted.Size, sizeUnit, SizeUnit.entries, { stringResource(it.label) }, { sizeUnit = it }, "transfer-size")
    }
    Labeled(stringResource(Res.string.tools_b_speed)) {
        Quantity(speed, speedResult, find != Wanted.Speed, speedUnit, SpeedUnit.entries, { stringResource(it.label) }, { speedUnit = it }, "transfer-speed")
    }
    Labeled(stringResource(Res.string.tools_b_time)) {
        if (find == Wanted.Time) {
            Text(timeResult?.let { Transfer.timeText(it, hour, minute, second) }.orEmpty(), Modifier.testTag("transfer-time-result"), style = Theme.type.mono)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TimePart(hours, hour, "transfer-hours")
                TimePart(minutes, minute, "transfer-minutes")
                TimePart(seconds, second, "transfer-seconds")
            }
        }
    }
    Labeled(stringResource(Res.string.tools_b_overhead)) {
        TextField(overhead, Modifier.width(NumberWidth).testTag("transfer-overhead"), mono = true, error = overhead.text.isNotBlank() && (extra == null || extra >= 100))
    }
    if (find == Wanted.Time && sizeValue != null && extra != null) {
        Text(stringResource(Res.string.tools_b_other_speeds), color = Theme.palette.muted, style = Theme.type.small)
        for ((rate, unit) in Transfer.references) {
            if (speedValue != null && rate * unit.bitsPerSecond == speedValue * speedUnit.bitsPerSecond) continue
            val taken = Transfer.seconds(sizeValue, sizeUnit, rate, unit, extra) ?: continue
            Fact("${Transfer.rounded(rate)} ${stringResource(unit.label)}", Transfer.timeText(taken, hour, minute, second))
        }
    }
}

@Composable
private fun <U> Quantity(input: TextFieldState, result: Double?, editable: Boolean, unit: U, units: List<U>, symbol: @Composable (U) -> String, onUnit: (U) -> Unit, tag: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (editable) {
            TextField(input, Modifier.width(120.dp).testTag(tag), mono = true, error = input.text.isNotBlank() && Transfer.amount(input.text.toString()) == null)
        } else {
            Text(result?.let(Transfer::rounded).orEmpty(), Modifier.width(120.dp).testTag("$tag-result"), style = Theme.type.mono)
        }
        Select(unit, units, { symbol(it) }, onUnit, Modifier.width(96.dp).testTag("$tag-unit"))
    }
}

@Composable
private fun TimePart(input: TextFieldState, unit: String, tag: String) {
    TextField(input, Modifier.width(56.dp).testTag(tag), mono = true, error = input.text.isNotBlank() && Transfer.amount(input.text.toString()) == null)
    Text(unit, color = Theme.palette.muted)
}
