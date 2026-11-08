package com.vasmarfas.networkhelm.ui.form

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.ChevronsUpDown
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledDropdownMenu
import com.vasmarfas.networkhelm.app.LocalSettings
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.sensitive
import com.vasmarfas.networkhelm.routeros.schema.label
import com.vasmarfas.networkhelm.search.span
import com.vasmarfas.networkhelm.ui.kit.Calendar
import com.vasmarfas.networkhelm.ui.kit.Checkbox
import com.vasmarfas.networkhelm.ui.kit.ChoiceList
import com.vasmarfas.networkhelm.ui.kit.Combo
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.Radios
import com.vasmarfas.networkhelm.ui.kit.SecretField
import com.vasmarfas.networkhelm.ui.kit.Segments
import com.vasmarfas.networkhelm.ui.kit.Select
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.kit.hand
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.form_add_line
import networkhelm.app.shared.generated.resources.form_choices
import networkhelm.app.shared.generated.resources.form_negate
import networkhelm.app.shared.generated.resources.form_remove_line
import networkhelm.app.shared.generated.resources.form_whole_number

@Composable
fun FieldEditor(argument: Argument, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, beside: String? = null) {
    val type = argument.type
    val scripts = LocalScripts.current
    val hide = LocalSettings.current.hidePasswords
    when {
        // a source that is an address, as that of a group of IGMP Proxy, is no script
        argument.name in ScriptProperties && type is ValueType.Text && scripts != null -> ScriptEditor(value, onChange, scripts, modifier)
        (argument.name == "rule" || argument.name == "note") && scripts != null -> ScriptEditor(value, onChange, scripts, modifier, console = false)
        type == ValueType.YesNo -> {
            val shown = value.ifEmpty { argument.default.orEmpty() }
            Tick(shown == "true" || shown == "yes", { onChange(if (it) "true" else "false") }, beside, modifier, unset = shown.isEmpty())
        }
        type == ValueType.Flag -> {
            val shown = value.ifEmpty { argument.default.orEmpty() }
            Tick(shown == argument.word(true) || shown in ValueType.Flag.on, { onChange(argument.word(it)) }, beside, modifier)
        }
        type == ValueType.Switch -> Tick(value.isNotEmpty(), { onChange(if (it) "yes" else "") }, beside, modifier)
        type is ValueType.Enumeration && type.multiple && argument.choices.isNotEmpty() -> Flags(argument.choices, value, onChange, modifier, argument.labels)
        type is ValueType.Enumeration && type.closed && value.isNotEmpty() && value !in argument.choices -> Line(type, value, onChange, modifier, argument.choices)
        type is ValueType.Enumeration && argument.radio && argument.choices.size > 1 -> Radios(value.ifEmpty { argument.default.orEmpty() }, argument.choices, { argument.labels[it] ?: it }, onChange, modifier)
        type is ValueType.Enumeration && argument.editable && argument.choices.isNotEmpty() -> NamesAndNumbers(argument, value.ifEmpty { argument.default.orEmpty() }, onChange, modifier)
        type is ValueType.Enumeration && argument.choices.isNotEmpty() -> {
            val shown = value.ifEmpty { argument.default.orEmpty() }
            val choices = if (shown.isEmpty() || shown in argument.choices) argument.choices else listOf(shown) + argument.choices
            val named = { choice: String -> argument.labels[choice] ?: choice.takeIf { it != it.lowercase() } ?: label(choice) }
            if (type.closed && choices.size in 2..4 && choices.sumOf { named(it).length } <= 32) Segments(shown, choices, { named(it) }, onChange, modifier)
            else Select(shown, choices, { argument.labels[it] ?: it }, onChange, modifier, apart = { it in argument.apart })
        }
        type is ValueType.Many -> Lines(type, value, onChange, modifier, argument.choices)
        type is ValueType.TimeWindow -> TimeWindowField(type, value, onChange, modifier)
        type is ValueType.Rate -> RateField(type, value, onChange, modifier)
        type is ValueType.ConnectionLimit -> ConnectionLimitField(type, value, onChange, modifier)
        type is ValueType.Span -> SpanField(type, value, onChange, modifier)
        type is ValueType.Adjustment -> AdjustmentField(type, value, onChange, modifier)
        type == ValueType.Classifier -> ClassifierField(argument.choices, value, onChange, modifier)
        type is ValueType.Icmp -> IcmpField(type, value, onChange, modifier)
        type is ValueType.Headers -> HeadersField(type, value, onChange, modifier)
        type == ValueType.DateTime -> DateTimeField(value, onChange, modifier)
        hide && argument.sensitive() -> SecretField(synced(value, onChange), modifier)
        else -> Line(
            type, value, onChange, modifier, argument.choices,
            argument.default?.takeIf { it.isNotEmpty() } ?: argument.placeholder ?: hint(type).takeUnless { type is ValueType.Integer && argument.choices.isNotEmpty() },
        )
    }
}

// a name of the list, or the whole number the list stands for besides: the CPU of Profile, the new MSS of a rule of the mangle
@Composable
private fun NamesAndNumbers(argument: Argument, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    fun read(text: String) = argument.choices.firstOrNull { it == text }
        ?: argument.choices.firstOrNull { it.equals(text, ignoreCase = true) || argument.labels[it].equals(text, ignoreCase = true) }
        ?: text.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    Combo(value, argument.choices, { argument.labels[it] ?: it }, ::read, onChange, modifier, stringResource(Res.string.form_whole_number), apart = { it in argument.apart })
}

@Composable
private fun Tick(checked: Boolean, onChange: (Boolean) -> Unit, beside: String?, modifier: Modifier, unset: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, unset = unset)
        if (beside != null) Text(beside, Modifier.padding(start = 6.dp).clickable { onChange(!checked) }.hand(), style = Theme.type.body)
    }
}

@Composable
fun NegationToggle(on: Boolean, onToggle: (Boolean) -> Unit) {
    val palette = Theme.palette
    val shape = RoundedCornerShape(Theme.metrics.radius)
    val description = stringResource(Res.string.form_negate)
    UnstyledButton(
        onClick = { onToggle(!on) },
        indication = LocalIndication.current,
        modifier = Modifier
            .size(24.dp, Theme.metrics.control)
            .clip(shape)
            .background(if (on) palette.text else palette.raised)
            .border(1.dp, palette.line, shape)
            .hand()
            .semantics {
                contentDescription = description
                selected = on
            },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("!", color = if (on) palette.surface else palette.faint, style = Theme.type.strong)
        }
    }
}

@Composable
private fun Line(type: ValueType, value: String, onChange: (String) -> Unit, modifier: Modifier, choices: List<String> = emptyList(), placeholder: String? = hint(type)) {
    val state = synced(value, onChange)
    val mono = type is ValueType.Ip4 || type is ValueType.Ip4Prefix || type is ValueType.Ip4Range || type is ValueType.Ip6 ||
        type is ValueType.Ip6Prefix || type is ValueType.Ip || type is ValueType.Mac || type is ValueType.PortList || type is ValueType.Host || type == ValueType.Hex ||
        type == ValueType.RouteDistinguisher
    val invalid = value.isNotEmpty() && value !in choices && !fits(type, value)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        TextField(state, Modifier.weight(1f), placeholder = placeholder, mono = mono, error = invalid)
        val unit = when (type) {
            is ValueType.Integer -> type.unit
            is ValueType.Decimal -> type.unit
            else -> null
        }
        unit?.let { Text(it, Modifier.padding(horizontal = 4.dp), color = Theme.palette.muted) }
        if (type == ValueType.Date) Calendar(date(value)) { state.setTextAndPlaceCursorAtEnd(it.toString()) }
        if (choices.isNotEmpty()) Suggestions(choices, value) { state.setTextAndPlaceCursorAtEnd(it) }
    }
}

internal fun fits(type: ValueType, value: String): Boolean = when (type) {
    is ValueType.Integer -> type.number(value)?.let { number -> type.min?.let { number < it } != true && type.max?.let { number > it } != true } == true
    is ValueType.Decimal -> value.removeSuffix(type.unit.orEmpty()).toDoubleOrNull()?.let { number -> type.min?.let { number < it } != true && type.max?.let { number > it } != true } == true
    ValueType.Ip4, ValueType.Ip4Prefix, ValueType.Ip4Range -> families(value) == setOf(4)
    ValueType.Ip6, ValueType.Ip6Prefix -> families(value) == setOf(16)
    ValueType.Ip -> families(value) != null
    ValueType.Mac -> value.split('/').let { parts -> parts.size <= 2 && parts.all(::mac) }
    ValueType.Hex -> value.removePrefix("0x").let { digits -> digits.length % 2 == 0 && digits.all { it in "0123456789abcdefABCDEF" } }
    ValueType.RouteDistinguisher -> distinguisher.matchEntire(value)?.groupValues?.get(1)?.let { head -> head.all(Char::isDigit) || family(head) == 4 } == true
    ValueType.Date -> date(value) != null
    ValueType.TimeOfDay -> clock(value)
    ValueType.Offset -> offset.matchEntire(value)?.groupValues?.let { (_, hours, minutes) -> hours.toInt() <= 14 && (minutes.isEmpty() || minutes.toInt() <= 59) } == true
    is ValueType.Span -> value.split(type.separator).let { ends -> (ends.size == 2 || ends.size == 1 && type.open) && ends.all { fits(type.element, it) } }
    // what looks like an address has to be one: the router reads a gateway 192.168.1 as 192.168.0.1 without a word
    is ValueType.Host -> value.substringBefore('@').substringBefore('%').let { base ->
        if (':' in base || base.all { it.isDigit() || it in "./-" }) fits(type.address, base) else type.names
    }
    else -> true
}

private val isoDay = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")
private val consoleDay = Regex("""([A-Za-z]{3})/(\d{1,2})/(\d{4})""")
private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

internal fun date(value: String): LocalDate? {
    val (year, month, day) = isoDay.matchEntire(value)?.groupValues?.drop(1)?.map(String::toInt)
        ?: consoleDay.matchEntire(value)?.groupValues?.let { (_, name, number, year) -> listOf(year.toInt(), monthNames.indexOf(name.lowercase()) + 1, number.toInt()) }
        ?: return null
    if (year !in 1970..2106 || month !in 1..12 || day !in 1..YearMonth(year, month).numberOfDays) return null
    return LocalDate(year, month, day)
}

private val offset = Regex("""[+-]?(\d{1,2})(?::(\d{2}))?""")

private val distinguisher = Regex("""([\d.]+):\d{1,10}""")

private val timeOfDay = Regex("""(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?""")

private fun clock(value: String) = timeOfDay.matchEntire(value)?.groupValues?.drop(1)?.let { (hour, minute, second) ->
    hour.toInt() <= 24 && minute.toInt() <= 59 && (second.isEmpty() || second.toInt() <= 59)
} == true

private fun families(value: String): Set<Int>? = value.split(',').mapTo(HashSet()) { family(it.trim()) ?: return null }

private fun mac(value: String) = value.all { it in "0123456789abcdefABCDEF:-." } && value.count { it !in ":-." } == 12

private val withPort = Regex("""([\d.]+):\d{1,5}""")

private fun family(value: String): Int? {
    withPort.matchEntire(value)?.let { return family(it.groupValues[1]) }
    val mask = value.substringAfter('/', "")
    if ('.' in mask) return span(value.substringBefore('/'))?.start?.size?.takeIf { it == 4 && span(mask)?.start?.size == 4 }
    return span(value)?.start?.size
}

internal fun Argument.misfits(value: String): Boolean {
    val plain = if (negatable) value.removePrefix("!") else value
    return plain.isNotEmpty() && plain !in choices && !fits(type, plain)
}

internal fun hint(type: ValueType): String? = when (type) {
    is ValueType.Integer -> if (type.min != null && type.max != null) "${type.min}..${type.max}" else null
    is ValueType.Decimal -> if (type.min != null && type.max != null) "${type.min.toString().removeSuffix(".0")}..${type.max.toString().removeSuffix(".0")}" else null
    ValueType.Time -> "1d2h3m4s"
    ValueType.Ip4 -> "A.B.C.D"
    ValueType.Ip4Prefix -> "A.B.C.D/M"
    ValueType.Ip4Range -> "A.B.C.D/M | A.B.C.D-A.B.C.D"
    ValueType.Ip6, ValueType.Ip6Prefix -> "::/M"
    ValueType.Ip -> "A.B.C.D | ::"
    ValueType.Mac -> "AA:BB:CC:DD:EE:FF"
    ValueType.Hex -> "0A1B2C3D"
    ValueType.RouteDistinguisher -> "65530:100"
    ValueType.PortList -> "80,443,1000-2000"
    ValueType.Date -> "YYYY-MM-DD"
    ValueType.TimeOfDay -> "HH:MM:SS"
    ValueType.Offset -> "+03:00"
    is ValueType.Span -> type.element.min?.let { low -> type.element.max?.let { high -> if (type.open) "$low[${type.separator}$high]" else "$low${type.separator}$high" } }
    is ValueType.Host -> type.notation
    is ValueType.Unparsed -> type.notation.takeIf { it.isNotEmpty() }
    else -> null
}

@Composable
private fun Suggestions(choices: List<String>, current: String, named: (String) -> String = { it }, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    UnstyledDropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        panel = {
            ChoiceList(choices, current, { named(it) }, { choice ->
                onPick(choice)
                open = false
            })
        },
        anchor = { IconButton(Lucide.ChevronsUpDown, stringResource(Res.string.form_choices), { open = true }) },
    )
}

@Composable
private fun Flags(choices: List<String>, value: String, onChange: (String) -> Unit, modifier: Modifier, labels: Map<String, String> = emptyMap()) {
    val picked = value.split(',').filter { it.isNotEmpty() }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (choice in choices) {
            fun tick(on: Boolean) = onChange(choices.filter { if (it == choice) on else it in picked }.joinToString(","))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(choice in picked, ::tick)
                Text(labels[choice] ?: choice, Modifier.clickable(interactionSource = null, indication = null) { tick(choice !in picked) }.hand())
            }
        }
    }
}

@Composable
private fun Lines(type: ValueType.Many, value: String, onChange: (String) -> Unit, modifier: Modifier, choices: List<String>) {
    val separator = type.separator
    val lines = value.split(separator)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEachIndexed { index, line ->
            key(index) {
                fun set(text: String) = onChange(lines.toMutableList().also { it[index] = text }.joinToString(separator))
                val negated = type.negatable && line.startsWith("!")
                val text = if (type.negatable) line.removePrefix("!") else line
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (type.negatable) {
                        NegationToggle(negated) { set((if (it) "!" else "") + text) }
                        Spacer(Modifier.width(4.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        Line(type.element, text, { set((if (negated) "!" else "") + it) }, Modifier.fillMaxWidth(), choices)
                    }
                    IconButton(Lucide.ChevronDown, stringResource(Res.string.form_add_line), {
                        onChange(lines.toMutableList().also { it.add(index + 1, "") }.joinToString(separator))
                    })
                    IconButton(Lucide.ChevronUp, stringResource(Res.string.form_remove_line), {
                        onChange(if (lines.size == 1) "" else lines.toMutableList().also { it.removeAt(index) }.joinToString(separator))
                    })
                }
            }
        }
    }
}

// written back whole, as RouterOS keeps a part a value leaves out: 20,6 over 10/1h,5:bit sets 20/1h,6:bit
@Composable
private fun Parts(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
    split: (String) -> List<String>?,
    join: (List<String>) -> String,
    content: @Composable (parts: List<String>, set: (Int, String) -> Unit) -> Unit,
) {
    val first = remember { split(value) }
    if (first == null) {
        Line(ValueType.Text(), value, onChange, modifier)
        return
    }
    val parts = remember { first.toMutableStateList() }
    var written by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value == written) return@LaunchedEffect
        split(value)?.let {
            parts.clear()
            parts.addAll(it)
        }
        written = value
    }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        content(parts) { index, part ->
            parts[index] = part
            written = join(parts)
            onChange(written)
        }
    }
}

@Composable
private fun Part(name: String, text: String, onChange: (String) -> Unit, width: Dp, separators: String, placeholder: String? = null, error: Boolean = false) {
    val description = label(name)
    TextField(
        synced(text, onChange),
        Modifier.width(width).semantics { contentDescription = description },
        placeholder = placeholder,
        error = error,
        inputTransformation = remember(separators) { InputTransformation { if (asCharSequence().any { it in separators }) revertAllChanges() } },
    )
}

@Composable
private fun Caption(text: String) {
    Text(text, color = Theme.palette.muted)
}

private val counted = ValueType.Integer(scaled = true)

private fun wrongCount(part: String, blank: Boolean) = !blank && counted.number(part) == null

@Composable
private fun TimeWindowField(type: ValueType.TimeWindow, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    Parts(
        value,
        onChange,
        modifier,
        split = { text ->
            val head = text.substringBefore(',')
            val days = text.substringAfter(',', "")
            when {
                text.isEmpty() -> listOf("", "", "")
                '-' !in head || days.split(',').any { it.isNotEmpty() && it !in type.days } -> null
                else -> listOf(head.substringBefore('-'), head.substringAfter('-'), days)
            }
        },
        join = { (start, end, days) -> if ("$start$end$days".isEmpty()) "" else "$start-$end" + if (days.isEmpty()) "" else ",$days" },
    ) { parts, set ->
        val blank = parts.all { it.isEmpty() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Part("start", parts[0], { set(0, it) }, 84.dp, "-,", error = !blank && parts[0].isEmpty())
            Caption("–")
            Part("end", parts[1], { set(1, it) }, 84.dp, "-,", error = !blank && parts[1].isEmpty())
        }
        if (type.days.isNotEmpty()) Flags(type.days, parts[2], { set(2, it) }, Modifier)
    }
}

// time and expire are written as 1s and 1m when empty, as a rule that has them would keep the old ones
@Composable
private fun RateField(type: ValueType.Rate, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    Parts(
        value,
        onChange,
        modifier,
        split = { text ->
            when {
                text.isEmpty() -> List(if (type.perFlow) 5 else 4) { "" }
                else -> (if (type.perFlow) flowRate else rate).matchEntire(text)?.groupValues?.drop(1)?.takeIf { it[3].isEmpty() || it[3] in type.modes }
            }
        },
        join = { parts ->
            val (count, time, burst, mode) = parts
            val head = "$count/${time.ifEmpty { "1s" }},$burst"
            when {
                parts.all { it.isEmpty() } -> ""
                type.perFlow -> "$head,$mode/${parts[4].ifEmpty { "1m" }}"
                mode.isEmpty() -> head
                else -> "$head:$mode"
            }
        },
    ) { parts, set ->
        val blank = parts.all { it.isEmpty() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Part("count", parts[0], { set(0, it) }, 84.dp, "/,", error = wrongCount(parts[0], blank))
            Caption("/")
            Part("time", parts[1], { set(1, it) }, 64.dp, "/,", placeholder = "1s")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Caption(label("burst"))
            Part("burst", parts[2], { set(2, it) }, 64.dp, ",:", error = wrongCount(parts[2], blank))
        }
        if (type.perFlow) {
            Select(parts[3], type.modes, { it }, { set(3, it) }, Modifier.width(200.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Caption(label("expire"))
                Part("expire", parts[4], { set(4, it) }, 64.dp, "/,", placeholder = "1m")
            }
        } else {
            Segments(parts[3], type.modes, { label(it) }, { set(3, it) })
        }
    }
}

private val rate = Regex("""([^/,]*)(?:/([^,]*))?,([^:,]*)(?::(.*))?""")
private val flowRate = Regex("""([^/,]*)(?:/([^,]*))?,([^,]*),([^/,]*)(?:/(.*))?""")

@Composable
private fun ConnectionLimitField(type: ValueType.ConnectionLimit, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    Parts(
        value,
        onChange,
        modifier,
        split = { text -> if (text.isEmpty()) listOf("", "") else text.split(',').takeIf { it.size == 2 } },
        join = { (limit, mask) -> if ("$limit$mask".isEmpty()) "" else "$limit,$mask" },
    ) { parts, set ->
        val blank = parts.all { it.isEmpty() }
        Part("limit", parts[0], { set(0, it) }, 84.dp, ",", error = wrongCount(parts[0], blank))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val name = if (type.prefix == 32) "netmask" else "prefix-length"
            Caption(label(name))
            Part(name, parts[1], { set(1, it) }, 110.dp, ",", placeholder = "0..${type.prefix}", error = !blank && parts[1].isEmpty())
        }
    }
}

// the router takes the ends in either order, except those of connection-rate, and its refusal says so
@Composable
private fun SpanField(type: ValueType.Span, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    Parts(
        value,
        onChange,
        modifier,
        split = { text -> text.split(type.separator).takeIf { it.size <= 2 }?.let { listOf(it[0], it.getOrElse(1) { "" }) } },
        join = { (low, high) -> if (high.isEmpty()) low else low + type.separator + high },
    ) { parts, set ->
        val blank = parts.all { it.isEmpty() }
        val bounds = hint(type.element)
        val separators = if (type.separator == "..") "." else type.separator
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Part(type.names[0], parts[0], { set(0, it) }, 110.dp, separators, placeholder = bounds, error = !blank && !fits(type.element, parts[0]))
            Caption(if (type.separator == "-") "–" else type.separator)
            Part(type.names[1], parts[1], { set(1, it) }, 110.dp, separators, placeholder = bounds, error = !blank && !(type.open && parts[1].isEmpty()) && !fits(type.element, parts[1]))
        }
    }
}

@Composable
private fun AdjustmentField(type: ValueType.Adjustment, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    Parts(
        value,
        onChange,
        modifier,
        split = { text -> if (text.isEmpty()) listOf("", "") else text.split(':').takeIf { it.size == 2 && it[0] in type.modes } },
        join = { (mode, number) -> if (number.isEmpty()) "" else "${mode.ifEmpty { type.modes.first() }}:$number" },
    ) { parts, set ->
        Segments(parts[0].ifEmpty { type.modes.first() }, type.modes, { label(it) }, { set(0, it) })
        Part("value", parts[1], { set(1, it) }, 64.dp, ":", placeholder = hint(type.element), error = parts[1].isNotEmpty() && !fits(type.element, parts[1]))
    }
}

@Composable
private fun ClassifierField(hashes: List<String>, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    Parts(
        value,
        onChange,
        modifier,
        split = { text -> if (text.isEmpty()) listOf("", "", "") else classifier.matchEntire(text)?.groupValues?.drop(1) },
        join = { (hash, denominator, remainder) -> if ("$hash$denominator$remainder".isEmpty()) "" else "$hash:$denominator/$remainder" },
    ) { parts, set ->
        val blank = parts.all { it.isEmpty() }
        Select(parts[0], if (parts[0].isEmpty() || parts[0] in hashes) hashes else listOf(parts[0]) + hashes, { it }, { set(0, it) }, Modifier.width(220.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Caption(":")
            Part("denominator", parts[1], { set(1, it) }, 64.dp, ":/", error = wrongCount(parts[1], blank))
            Caption("/")
            Part("remainder", parts[2], { set(2, it) }, 64.dp, ":/", error = wrongCount(parts[2], blank))
        }
    }
}

private val classifier = Regex("""([^:]*):([^/]*)/(.*)""")

private val icmp4 = mapOf(
    "0" to "echo-reply", "3" to "destination-unreachable", "4" to "source-quench", "5" to "redirect", "8" to "echo-request",
    "9" to "router-advertisement", "10" to "router-solicitation", "11" to "time-exceeded", "12" to "parameter-problem", "13" to "timestamp",
    "14" to "timestamp-reply",
)
private val icmp6 = mapOf(
    "1" to "destination-unreachable", "2" to "packet-too-big", "3" to "time-exceeded", "4" to "parameter-problem", "128" to "echo-request",
    "129" to "echo-reply", "130" to "multicast-listener-query", "131" to "multicast-listener-report", "132" to "multicast-listener-done",
    "133" to "router-solicitation", "134" to "router-advertisement", "135" to "neighbor-solicitation", "136" to "neighbor-advertisement",
    "137" to "redirect", "143" to "multicast-listener-report-v2",
)

@Composable
private fun IcmpField(type: ValueType.Icmp, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val names = if (type.six) icmp6 else icmp4
    Parts(
        value,
        onChange,
        modifier,
        split = { text -> listOf(text.substringBefore(':'), text.substringAfter(':', "")) },
        join = { (kind, code) -> if (code.isEmpty()) kind else "$kind:$code" },
    ) { parts, set ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Part("type", parts[0], { set(0, it) }, 64.dp, ":", placeholder = "0..255", error = parts.any { it.isNotEmpty() } && parts[0].toIntOrNull() !in 0..255)
            Suggestions(names.keys.toList(), parts[0], { "$it ${names[it]}" }) { set(0, it) }
            names[parts[0]]?.let { Caption(it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Caption(label("code"))
            Part("code", parts[1], { set(1, it) }, 84.dp, ":", placeholder = "0..255")
        }
    }
}

@Composable
private fun HeadersField(type: ValueType.Headers, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    Parts(
        value,
        onChange,
        modifier,
        split = { text ->
            val kinds = text.substringBefore(':')
            val mode = text.substringAfter(':', "")
            listOf(kinds, mode).takeIf { kinds.split(',').all { it.isEmpty() || it in type.kinds } && (mode.isEmpty() || mode in type.modes) }
        },
        join = { (kinds, mode) -> if (mode.isEmpty() || kinds.isEmpty()) kinds else "$kinds:$mode" },
    ) { parts, set ->
        Flags(type.kinds, parts[0], { set(0, it) }, Modifier)
        Segments(parts[1], type.modes, { label(it) }, { set(1, it) })
    }
}

@Composable
private fun DateTimeField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    Parts(
        value,
        onChange,
        modifier,
        split = { text -> if (text.isEmpty()) listOf("", "") else text.split(' ').takeIf { it.size == 2 } },
        join = { (day, time) -> if ("$day$time".isEmpty()) "" else "$day $time" },
    ) { parts, set ->
        val blank = parts.all { it.isEmpty() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Part("date", parts[0], { set(0, it) }, 110.dp, " ", placeholder = hint(ValueType.Date), error = !blank && date(parts[0]) == null)
            Calendar(date(parts[0])) { set(0, it.toString()) }
        }
        Part("time", parts[1], { set(1, it) }, 84.dp, " ", placeholder = hint(ValueType.TimeOfDay), error = !blank && !clock(parts[1]))
    }
}

// a new value gets a new state in the composition that brings it; set from an effect it is drawn a frame late
@Composable
private fun synced(value: String, onChange: (String) -> Unit): TextFieldState {
    val field = remember { Synced(value) }
    val state = field.follow(value)
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { if (it != current) change(it) }
    }
    return state
}

private class Synced(private var value: String) {
    private var state = TextFieldState(value)

    fun follow(value: String): TextFieldState {
        if (value != this.value) {
            this.value = value
            if (state.text.toString() != value) state = TextFieldState(value)
        }
        return state
    }
}
