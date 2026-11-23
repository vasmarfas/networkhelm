package com.vasmarfas.networkhelm.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

@Stable
class Ring(val capacity: Int) {
    private var data by mutableStateOf(DoubleArray(capacity), neverEqualPolicy())
    private var start = 0
    var size = 0
        private set

    fun add(value: Double) {
        val array = data
        array[(start + size) % capacity] = value
        if (size < capacity) size++ else start = (start + 1) % capacity
        data = array
    }

    operator fun get(index: Int): Double = data[(start + index) % capacity]

    fun last(): Double? {
        val array = data
        return if (size == 0) null else array[(start + size - 1) % capacity]
    }

    fun max(): Double {
        val array = data
        return (0 until size).maxOfOrNull { array[(start + it) % capacity] } ?: 0.0
    }

    fun average(): Double {
        val array = data
        return if (size == 0) 0.0 else (0 until size).sumOf { array[(start + it) % capacity] } / size
    }
}

class Series(val name: String, val color: Color, val ring: Ring)

@Composable
fun Chart(
    series: List<Series>,
    modifier: Modifier = Modifier,
    axis: Boolean = true,
    ceiling: Double? = null,
    format: (Double) -> String = { it.toLong().toString() },
) {
    val palette = Theme.palette
    val measurer = rememberTextMeasurer()
    val style = Theme.type.small.copy(color = palette.muted)
    Canvas(modifier) {
        val top = ceiling ?: axisTop(series.maxOfOrNull { it.ring.max() } ?: 0.0)
        val known = ceiling != null || series.any { it.ring.size > 0 }
        val labels = if (axis && known) (1..STEPS).map { measurer.measure(format(top * it / STEPS), style) } else emptyList()
        // the labels get a column of their own on the right, so the newest values are not drawn under them
        val right = size.width - (labels.maxOfOrNull { it.size.width }?.let { it + 8.dp.toPx() } ?: 0f)
        if (axis) {
            for (step in 0..STEPS) {
                val y = (size.height - 1) * (1 - step.toFloat() / STEPS)
                drawLine(palette.line, Offset(0f, y), Offset(size.width, y))
                val text = labels.getOrNull(step - 1) ?: continue
                drawText(text, topLeft = Offset(size.width - text.size.width - 4.dp.toPx(), y + 1.dp.toPx()))
            }
        }
        for (line in series) {
            val ring = line.ring
            if (ring.size == 1) drawCircle(line.color, 2.dp.toPx(), Offset(right - 2.dp.toPx(), size.height * (1 - (ring[0] / top).toFloat())))
            if (ring.size < 2) continue
            val step = right / (ring.capacity - 1)
            val path = Path()
            for (index in 0 until ring.size) {
                val x = right - (ring.size - 1 - index) * step
                val y = size.height * (1 - (ring[index] / top).toFloat())
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, line.color, style = Stroke(1.5.dp.toPx()))
        }
    }
}

private const val STEPS = 5

internal fun roundTop(value: Double): Double {
    if (value <= 1.0) return 1.0
    val power = 10.0.pow(floor(log10(value)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * power >= value } * power
}

// a top below five whole units would write the same label on two steps
internal fun axisTop(max: Double): Double = maxOf(roundTop(max), STEPS.toDouble())

@Composable
fun Legend(series: List<Series>, format: (Double) -> String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        for (line in series) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(line.color))
                Text("${line.name}: ${line.ring.last()?.let(format) ?: "—"}", style = Theme.type.small)
            }
        }
    }
}

fun display(property: String, value: String): String = when {
    property.endsWith("bits-per-second") -> halves(value) { it.toDoubleOrNull()?.let(::bitRate) }
    property.endsWith("-byte") || property == "bytes" || property.endsWith("-bytes") || property.endsWith("-memory") || property.endsWith("-hdd-space") ->
        halves(value) { it.toDoubleOrNull()?.let(::byteCount) }
    property == "packets" || property.endsWith("-packet") || property.endsWith("-packets") || property.endsWith("-packets-per-second") ->
        halves(value) { it.toLongOrNull()?.let(::thousands) }
    property == "cpu-frequency" -> value.toLongOrNull()?.let { "$it MHz" } ?: value
    property == "cpu-load" -> value.toLongOrNull()?.let { "$it %" } ?: value
    else -> isoDate(value)
}

private val MONTH_NAMES = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

private val CONSOLE_DATE = Regex("([a-z]{3})/(\\d{2})/(\\d{4})( \\d{2}:\\d{2}:\\d{2})?")

// the console of RouterOS prints a date as jul/25/2026, a table of WinBox 4 writes it as 2026-07-25 on every version
fun isoDate(value: String): String {
    val parts = CONSOLE_DATE.matchEntire(value)?.groupValues ?: return value
    val month = MONTH_NAMES.indexOf(parts[1]) + 1
    return if (month == 0) value else "${parts[3]}-${month.toString().padStart(2, '0')}-${parts[2]}${parts[4]}"
}

fun halves(value: String, format: (String) -> String?): String = value.split('/').map { format(it) ?: return value }.joinToString(" / ")

// a table writes the counters of packets in groups of three digits
private fun thousands(value: Long): String = value.toString().reversed().chunked(3).joinToString(" ").reversed()

private val TIME_PARTS = Regex("(\\d+(?:\\.\\d+)?)(us|ms|s|m|h|d|w)")

private val TIME_WHOLE = Regex("(?:\\d+(?:\\.\\d+)?(?:us|ms|s|m|h|d|w))+")

private val MICROSECONDS = mapOf("us" to 1.0, "ms" to 1_000.0, "s" to 1e6, "m" to 6e7, "h" to 3.6e9, "d" to 8.64e10, "w" to 6.048e11)

// a time of the router, as 1ms396us, in milliseconds with three decimals, as the answers of a ping stand in WinBox
fun inMilliseconds(value: String): String {
    if (!TIME_WHOLE.matches(value)) return value
    val micro = TIME_PARTS.findAll(value).sumOf { it.groupValues[1].toDouble() * MICROSECONDS.getValue(it.groupValues[2]) }.roundToLong()
    return "${micro / 1000}.${(micro % 1000).toString().padStart(3, '0')}"
}

private val NUMBER = Regex("(-?)(\\d+)(\\.\\d+)?")

// the whole part of a number the router counts in big units is written in groups, the fraction as it came
fun grouped(value: String): String {
    val parts = NUMBER.matchEntire(value)?.groupValues ?: return value
    return parts[1] + parts[2].reversed().chunked(3).joinToString(" ").reversed() + parts[3]
}

private val BIT_UNITS = listOf("bps", "kbps", "Mbps", "Gbps", "Tbps")

private const val WHOLE_BITS = 2000L

fun bits(value: Double): String = scaled(value, 1000.0, BIT_UNITS)

// a table cuts a rate to one decimal and changes the unit at 2000 of the one below; bytes are rounded and change it at 5 of the next one
fun bitRate(value: Double): String {
    val bps = value.toLong()
    var divisor = 1L
    var unit = 0
    while (unit < BIT_UNITS.lastIndex && bps / divisor >= WHOLE_BITS) {
        divisor *= 1000
        unit++
    }
    return if (unit == 0) "$bps bps" else "${bps / divisor}.${bps % divisor * 10 / divisor} ${BIT_UNITS[unit]}"
}

fun limit(value: Long): String {
    val (step, unit) = listOf(1_000_000_000L to "G", 1_000_000L to "M", 1_000L to "k").firstOrNull { value != 0L && value % it.first == 0L } ?: return "$value"
    return "${value / step}$unit"
}

private val SIZE_UNITS = listOf("B", "KiB", "MiB", "GiB", "TiB")

internal const val WHOLE_BYTES = 5 * 1024.0

fun byteSize(value: Double): String = scaled(value, 1024.0, SIZE_UNITS)

// a table changes the unit at 5 of the next one, measured up to GiB (5116.4 MiB stands, 5.0 GiB follows); the status bar of WinBox changes it earlier, at 5 120 000 000 bytes
private val BYTE_STEPS = DoubleArray(SIZE_UNITS.lastIndex) { 5 * 1024.0.pow(it + 1) }

fun byteCount(value: Double): String {
    val unit = BYTE_STEPS.count { value >= it }
    if (unit == 0) return "${value.roundToLong()} ${SIZE_UNITS[0]}"
    return "${(value / 1024.0.pow(unit) * 10).roundToLong() / 10.0} ${SIZE_UNITS[unit]}"
}

// the status bar and the disk space of WinBox count in KiB: whole KiB below 5000, then MiB, and GiB from 5 000 000 KiB, each with one decimal
fun kibiCount(value: Double): String {
    val kib = value / 1024.0
    return when {
        kib < 5_000 -> "${kib.roundToLong()} ${SIZE_UNITS[1]}"
        kib < 5_000_000 -> "${(kib / 1024.0 * 10).roundToLong() / 10.0} ${SIZE_UNITS[2]}"
        else -> "${(kib / 1024.0 / 1024.0 * 10).roundToLong() / 10.0} ${SIZE_UNITS[3]}"
    }
}

private fun scaled(value: Double, step: Double, units: List<String>, switchAt: Double = step, compact: Boolean = true): String {
    var scaled = value
    var unit = 0
    while (scaled >= switchAt && unit < units.lastIndex) {
        scaled /= step
        unit++
    }
    val text = if (unit == 0 || compact && scaled >= 100) scaled.roundToLong().toString() else ((scaled * 10).roundToLong() / 10.0).toString()
    return "$text ${units[unit]}"
}
