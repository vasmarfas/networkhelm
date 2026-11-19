package com.vasmarfas.networkhelm.routeros.schema

import com.vasmarfas.networkhelm.model.ValueType

class SyntaxRow(val nested: Int, val definition: Boolean, val symbol: String, val text: String)

class ArgumentSyntax(val type: ValueType, val negatable: Boolean, val choices: Boolean)

fun parseSyntax(rows: List<SyntaxRow>): ArgumentSyntax {
    val root = children(rows, 0, -1).first.firstOrNull() ?: return ArgumentSyntax(ValueType.Unparsed(""), false, false)
    val resolved = resolve(root)
    return ArgumentSyntax(resolved.type, root.row.text.startsWith("[!"), resolved.choices)
}

private class Node(val row: SyntaxRow, val children: List<Node>) {
    val defines: Boolean get() = row.definition || leaf(row.text) != null

    fun named(symbol: String) = children.filter { it.row.symbol == symbol && it.defines }
}

private class Resolved(val type: ValueType, val choices: Boolean)

private fun children(rows: List<SyntaxRow>, from: Int, parentLevel: Int): Pair<List<Node>, Int> {
    val nodes = mutableListOf<Node>()
    var at = from
    while (at < rows.size && rows[at].nested > parentLevel) {
        val row = rows[at]
        val (below, next) = children(rows, at + 1, row.nested)
        nodes += Node(row, below)
        at = next
    }
    return nodes to at
}

private val names = Regex("""^[A-Za-z][\w-]*(\s*\|\s*[A-Za-z][\w-]*)*$""")
private val list = Regex("""^(\w+)\[,(\w+)\*?\]$""")
private val range = Regex("""^(\w+)\[-(\w+)\]$""")
private val masked = Regex("""^(\w+)(\[/\w+\]|/\w+)$""")
private val suffixed = Regex("""^(\w+)(?:\[/\w+\])?(\[%\w+\])?(\[@\w+\])?$""")

private val spans = mapOf("Min[-Max]" to true, "Min-Max" to false, "From-To" to false)

private val unit = Regex("""^(\w+)\[(KiB|bps|%|C)\]$""")
private val bounds = Regex("""^(-?\w+)\.\.(-?\w+)(\s+\(integer number\))?$""")
private val decimals = Regex("""^(-?\d+(?:\.\d+)?)\.\.(-?\d+(?:\.\d+)?)\s+\(decimal number\)$""")
private val string = Regex("""^string value(, min length \d+)?(, max length (\d+))?$""")

private val remark = Regex("""\s+\([^()]*\)$""")

private val variants = Regex("""^("[^"]*"|[\w.:/<>-]+)?(\s*\|\s*("[^"]*"|[\w.:/<>-]+))*(\s*\|\s*\.\.\.)?$""")
private val variantList = Regex("""^[\w.:-]+(\s*\|\s*[\w.:-]+)+\[,\w+\*?\]$""")

internal const val UNDOCUMENTED = "see documentation"

private fun resolve(node: Node): Resolved {
    val text = node.row.text.removePrefix("[!,]").removePrefix("[!]").removePrefix("[+][-]").removePrefix("[-]").trim()
    val own = if (text.isEmpty()) null else notation(node, text)
    val all = listOfNotNull(own) + node.children.filter { it.row.symbol.isEmpty() && it.defines }.map(::resolve)
    return if (all.isEmpty()) Resolved(ValueType.Enumeration(closed = true), true) else combine(all, text)
}

private fun notation(node: Node, text: String): Resolved {
    leaf(text)?.let { return Resolved(it, false) }
    if (text == UNDOCUMENTED && '6' in node.row.symbol) return Resolved(ValueType.Ip6, false)
    val shape = text.replace(remark, "")
    if (names.matches(shape)) {
        val referenced = shape.split('|').map { it.trim() }.toSet().flatMap(node::named)
        if (referenced.isNotEmpty()) {
            val resolved = referenced.map(::resolve)
            if (resolved.any { it.type is ValueType.Text }) host(resolved.map { it.type }, text)?.let { return Resolved(it, resolved.any { it.choices }) }
            return combine(resolved, text)
        }
    }
    list.matchEntire(shape)?.let { match ->
        val element = node.named(match.groupValues[1]).firstOrNull()?.let(::resolve)?.type
        if (element is ValueType.Enumeration) return Resolved(ValueType.Enumeration(multiple = true), true)
        if (element == ValueType.PortList) return Resolved(ValueType.PortList, false)
        if (element != null) return Resolved(ValueType.Many(element), false)
    }
    spans[shape]?.let { open ->
        val parts = shape.split('[', '-', ']').filter { it.isNotEmpty() }
        val ends = parts.map { name -> node.named(name).firstOrNull()?.let(::resolve)?.type }
        val first = ends.first()
        if (first is ValueType.Integer && ends.all { it == first }) return Resolved(ValueType.Span(first, parts.map(String::lowercase), open), false)
    }
    range.matchEntire(shape)?.let { match ->
        val ends = match.groupValues.drop(1).map { name -> node.named(name).firstOrNull()?.let(::resolve)?.type }
        if (ends.all { it is ValueType.Integer && it.max == 65535L }) return Resolved(ValueType.PortList, false)
        val first = ends.first()
        if (first is ValueType.Integer && ends.all { it == first }) return Resolved(ValueType.Span(first, listOf("start", "end"), open = true), false)
    }
    masked.matchEntire(shape)?.let { match ->
        when (val type = node.named(match.groupValues[1]).firstOrNull()?.let(::resolve)?.type) {
            ValueType.Ip4 -> return Resolved(ValueType.Ip4Prefix, false)
            ValueType.Ip6 -> return Resolved(ValueType.Ip6Prefix, false)
            ValueType.Ip -> return Resolved(ValueType.Ip, false)
            ValueType.Mac -> return Resolved(ValueType.Mac, false)
            is ValueType.Integer -> {
                val mask = match.groupValues[2].trim('[', ']', '/')
                if (node.named(mask).firstOrNull()?.let(::resolve)?.type == type) {
                    return Resolved(ValueType.Span(type, listOf(match.groupValues[1], mask).map(String::lowercase), open = match.groupValues[2].startsWith("["), separator = "/"), false)
                }
            }
            else -> Unit
        }
    }
    unit.matchEntire(shape)?.let { match ->
        val number = node.named(match.groupValues[1]).firstOrNull()?.let(::resolve)
        val type = number?.type
        if (type is ValueType.Integer) return Resolved(type.copy(unit = match.groupValues[2]), number.choices)
        if (type is ValueType.Decimal) return Resolved(type.copy(unit = match.groupValues[2]), number.choices)
    }
    if (variantList.matches(shape)) return Resolved(ValueType.Enumeration(multiple = true), true)
    if (variants.matches(shape) && ('|' in shape || node.children.none { it.row.definition })) return Resolved(ValueType.Enumeration(), true)
    composite(node, shape)?.let { return Resolved(it, it == ValueType.Classifier) }
    suffixed.matchEntire(shape)?.takeIf { it.groupValues[2].isNotEmpty() || it.groupValues[3].isNotEmpty() }?.let { match ->
        val parts = node.named(match.groupValues[1]).firstOrNull()?.let(::alternatives).orEmpty()
        host(parts.map { it.type }, text)?.let { return Resolved(it, parts.any { part -> part.choices } || match.groupValues[2].isNotEmpty()) }
    }
    if ("[%Interface]" in shape) return Resolved(ValueType.Unparsed(text), true)
    return Resolved(ValueType.Unparsed(text), false)
}

private fun alternatives(rule: Node): List<Resolved> {
    val shape = rule.row.text.replace(remark, "")
    val named = if (names.matches(shape)) shape.split('|').map { it.trim() }.toSet().flatMap(rule::named) else emptyList()
    return if (named.isEmpty()) listOf(resolve(rule)) else named.map(::resolve)
}

private fun host(types: List<ValueType>, notation: String): ValueType.Host? {
    val addresses = types.filter { it in ip4 || it in ip6 || it == ValueType.Ip }
    if (addresses.isEmpty()) return null
    val family = when {
        addresses.all { it in ip4 } -> ValueType.Ip4
        addresses.all { it in ip6 } -> ValueType.Ip6
        else -> ValueType.Ip
    }
    return ValueType.Host(family, types.any { it is ValueType.Text || it is ValueType.Enumeration }, notation)
}

private fun composite(node: Node, shape: String): ValueType? = when (shape) {
    "Start-End,Day" -> words(node, "Day")?.let { ValueType.TimeWindow(it) }
    "Count[/Time],Burst[:Mode]" -> words(node, "Mode")?.let { ValueType.Rate(it) }
    "Limit,Netmask" -> ValueType.ConnectionLimit(32)
    "Limit,PrefixLen" -> ValueType.ConnectionLimit(128)
    "ValuesToHash:Denominator/Remainder" -> ValueType.Classifier
    "Type[:Code]" -> ValueType.Icmp()
    "Type[:Mode]" -> words(node, "Type")?.let { kinds -> words(node, "Mode")?.let { ValueType.Headers(kinds, it) } }
    "Address[&RD]" -> ValueType.RouteDistinguisher
    "Type:Data", "Type:Value" -> ValueType.Text()
    "Year-Month-Day" -> ValueType.Date
    "Hour:Min:Sec" -> ValueType.TimeOfDay
    "Year-Month-Day Hour:Min:Sec" -> ValueType.DateTime
    else -> null
}

private fun words(node: Node, part: String): List<String>? {
    val rule = node.named(part).firstOrNull() ?: return null
    val text = rule.row.text.ifEmpty { rule.children.firstOrNull { it.row.symbol.isEmpty() }?.row?.text.orEmpty() }
    return text.substringBefore('[').split('|').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.size > 1 }
}

private val ip4 = setOf(ValueType.Ip4, ValueType.Ip4Prefix, ValueType.Ip4Range)
private val ip6 = setOf(ValueType.Ip6, ValueType.Ip6Prefix)

private fun combine(resolved: List<Resolved>, text: String): Resolved {
    val values = resolved.map { it.type }.filterNot { it is ValueType.Enumeration }.distinct()
    val choices = resolved.any { it.choices }
    return when {
        values.isEmpty() -> Resolved(resolved.first().type, true)
        values.size == 1 -> Resolved(values.single(), choices)
        values.all { it in ip4 || it in ip6 } && values.any { it in ip4 } && values.any { it in ip6 } -> Resolved(ValueType.Ip, choices)
        values.any { it is ValueType.Text } -> Resolved(ValueType.Text(), choices)
        else -> Resolved(ValueType.Unparsed(text), choices)
    }
}

private fun leaf(notation: String): ValueType? {
    bounds.matchEntire(notation)?.let { match ->
        val (low, high) = match.groupValues.drop(1).take(2)
        val (min, max) = listOf(low, high).map { it.toLongOrNull() ?: it.toLongOrNull(16) }
        if (min != null && (max != null || high.all(Char::isDigit))) return ValueType.Integer(min, max, scaled = true)
    }
    decimals.matchEntire(notation)?.let { match -> return ValueType.Decimal(match.groupValues[1].toDouble(), match.groupValues[2].toDouble()) }
    string.matchEntire(notation)?.let { return ValueType.Text(it.groupValues[3].toIntOrNull()) }
    return when {
        notation == "integer value" -> ValueType.Integer(scaled = true)
        notation == "time interval" || notation.endsWith("(time interval)") -> ValueType.Time
        notation == "IPv4 address" || notation.endsWith("(IP address)") -> ValueType.Ip4
        notation == "IPv6 address" -> ValueType.Ip6
        notation.endsWith("(IPv6 prefix)") -> ValueType.Ip6Prefix
        notation.endsWith("(IP prefix)") -> ValueType.Ip4Prefix
        notation.endsWith("(IP address range)") -> ValueType.Ip4Range
        notation.endsWith("(MAC address)") -> ValueType.Mac
        notation.startsWith("hexadecimal string value") -> ValueType.Hex
        else -> null
    }
}
