package com.vasmarfas.networkhelm.uidefs

import com.vasmarfas.networkhelm.packs.NATIVE_NO_KEY
import com.vasmarfas.networkhelm.packs.NATIVE_WRITABLE_KINDS
import com.vasmarfas.networkhelm.packs.PRESENCE
import com.vasmarfas.networkhelm.packs.PackNativeField
import com.vasmarfas.networkhelm.packs.PackNativeRef

private val nativeKinds = setOf(
    "string", "number", "integer", "bignumber", "bool", "flag", "enm", "interval", "age", "fixedpoint", "decimal", "bigdecimal", "multistring", "multinumber", "macaddr",
    "ipaddr", "bytes", "bigbytes", "kbytes", "bitrate", "bigbitrate", "version", "ip6addr", "set", "numberrange", "unit", "bigunit", "numbar", "multibits",
    "multitristate", "packet", "raw", "network", "network6", "multibignumber", "stringarray", "multiraw", "netmask", "clocktime", "clockdate", "dateandtime", "timezone", "addr",
    "numberrangelist", "ipaddrandport", "hyperlink", "macnetwork", "multiipaddr", "multiip6addr", "script", "multilinestring", "as", "group", "numflag", "multinumberrange",
    "multinetwork",
)

private val wrappers = setOf("opt", "not", "union")

private const val MAX_WRAPPERS = 10

// the interface of an address is a row of the table of interfaces, which every router has at the same place
private val INTERFACE_NAMES = PackNativeRef(listOf(20, 0), listOf(0x10006))

internal val bitRates = setOf("bitrate", "bigbitrate")

internal val byteSizes = setOf("bytes", "bigbytes")

// the sizes WinBox counts in KiB, and the numbers it writes in groups of three digits
internal val kibiSizes = setOf("kbytes")

internal val groupedNumbers = setOf("bignumber", "bigdecimal", "decimal")

// a tuple is read a part at a time: Bandwidth (Tx and Rx) as two fields, Last Minute (In/Out) as In and Out
internal fun UiField.parts(): List<UiField>? {
    if (kind != "tuple") return null
    val base = label.substringBefore(" (")
    val named = label.substringAfter(" (", "").substringBefore(')').split('/').map(String::trim).filter { it.isNotEmpty() }
    val parts = node.children.filter { it.text("id") != null }
    return parts.mapIndexed { at, part ->
        val title = part.label.orEmpty().ifEmpty { named.getOrNull(at) ?: "${at + 1}".takeIf { parts.size > 1 }.orEmpty() }
        UiField("$base $title".trim(), part.kind.orEmpty(), true, part.number("width")?.toInt() ?: node.number("width")?.toInt(), part.inheriting(node.text("postfix")), needs)
    }.takeIf { it.isNotEmpty() }
}

// the unit of a number with a prefix is named by the node or by the tuple it stands in
private fun UiNode.inheriting(postfix: String?): UiNode =
    if (postfix == null || kind != "unit" && kind != "bigunit" || text("postfix") != null) this else UiNode(kind, attrs + ("postfix" to JgString(postfix)), children)

internal fun UiNode.handler(): List<Long>? = (attrs["path"] as? JgList)?.items?.map { (it as? JgNumber)?.value?.toLong() ?: return null }?.takeIf { it.isNotEmpty() }

internal fun handlers(windows: List<UiNode>): Map<List<Long>, UiNode> = buildMap { windows.forEach { window -> window.handler()?.let { getOrPut(it) { window } } } }

internal fun nameOf(title: String): String? =
    title.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").replace(Regex("-+"), "-").trim('-').takeIf { it.isNotEmpty() }

// the id of a field is a letter for the type and a number in hexadecimal; the letter is upper case for an array
private fun idKey(id: String): Int? = id.drop(1).toIntOrNull(16)

// inside a list of messages a part may be a tuple of parts or an address that is the whole message, and neither has an id
internal fun nativeField(field: UiField, name: String, windows: Map<List<Long>, UiNode>, inside: Boolean = false, editable: Boolean = false, writing: Set<String> = NATIVE_WRITABLE_KINDS): PackNativeField? {
    // a flag up while the record holds a field, as Managed of the ports of a bridge
    field.node.text("hasid")?.takeIf { field.node.kind == "flag" }?.let { has -> return idKey(has)?.let { PackNativeField(name, it, PRESENCE, letter = "b") } }
    var node = field.node
    var flag: Int? = null
    var negation: Int? = null
    while (node.kind == "opt" || node.kind == "not") {
        val own = node.text("id")?.let(::idKey)
        if (node.kind == "opt") flag = own else negation = own
        node = node.children.firstOrNull { it.text("id") != null || it.kind in wrappers || inside && (it.kind == "tuple" || it.kind == "addr") } ?: return null
    }
    flag = flag ?: node.text("optid")?.let(::idKey)
    // an enumeration with a node of another kind inside takes a value of that kind besides the names it lists, both under the one id
    val custom = node.takeIf { it.kind == "enm" }?.children?.firstOrNull()?.takeIf { it.kind in nativeKinds && it.text("id") == null }
    val customId = node.text("id")
    if (custom != null && customId != null) {
        val own = UiNode(node.kind, node.attrs - "c", emptyList())
        val extra = UiNode(custom.kind, custom.attrs + ("id" to JgString(customId)), custom.children)
        val members = listOf(own, extra).mapNotNull { nativeField(UiField(field.label, it.kind.orEmpty(), true, null, it), name, windows, inside, editable, writing) }
        return members.takeIf { it.size == 2 }?.let { PackNativeField(name, it.first().key, "union", flag = flag, negation = negation, members = it, writable = editable && it.all { member -> member.writable }) }
    }
    if (node.kind == "union") {
        val members = node.children.mapNotNull { nativeField(UiField(field.label, it.kind.orEmpty(), true, null, it), name, windows, inside, editable, writing) }
        return members.takeIf { all -> all.isNotEmpty() && all.none { it.key == NATIVE_NO_KEY } }
            ?.let { PackNativeField(name, it.first().key, "union", flag = flag, negation = negation, members = it, writable = editable && it.all { member -> member.writable }) }
    }
    if (inside && node.kind == "tuple") {
        val parts = node.children.map { nativeField(UiField(it.label.orEmpty(), it.kind.orEmpty(), true, null, it), name, windows, inside = true) ?: return null }
        return parts.takeIf { it.isNotEmpty() }?.let { PackNativeField(name, NATIVE_NO_KEY, "tuple", flag = flag, negation = negation, parts = it, separator = node.text("sep")) }
    }
    if (inside && node.kind == "addr" && node.text("id") == null) return PackNativeField(name, NATIVE_NO_KEY, "addr", refers = INTERFACE_NAMES, flag = flag, negation = negation)
    if (node.kind == "multi") {
        val id = node.text("id") ?: return null
        val element = node.children.singleOrNull() ?: return null
        val part = nativeField(UiField(field.label, element.kind.orEmpty(), true, null, element), name, windows, inside = true, editable = editable, writing = writing) ?: return null
        return PackNativeField(name, idKey(id) ?: return null, "multi", letter = id.take(1), flag = flag, negation = negation, parts = listOf(part), writable = editable && "multi" in writing && part.writable)
    }
    val kind = node.kind?.takeIf { it in nativeKinds } ?: return null
    // a moment counted on the clock of the router since it started is no date
    if (kind == "dateandtime" && node.flag("relative")) return null
    // the child of a list says what its numbers are: ranges, communities of BGP written as two halves, intervals of a day, or addresses with a mask
    val child = node.children.firstOrNull()
    val intervals = child?.takeIf { it.kind == "tuple" && it.children.size == 2 && it.children.all { part -> part.kind == "interval" } }
    if (kind == "multinumberrange" && child?.kind != "numberrange" && child?.kind != "community" && intervals == null || kind == "multinetwork" && child?.kind != "network") return null
    val id = node.text("id") ?: return null
    val key = idKey(id) ?: return null
    val described = if (kind == "multinumber" || kind == "multibignumber" || kind == "multinetwork") node.children.firstOrNull() ?: node else node
    val values = described.attrs["values"] as? JgObject
    val dynamic = dynamicOf(values)
    // the rows of a window behind a name for all or a list of names stand where the router describes it, and a window it does not describe leaves the names alone
    val behind = dynamic != null && dynamic !== values
    val refers = when {
        // a string already holds the text, the other window is only where the choices of the dropdown come from
        dynamic != null && kind != "string" && kind != "multistring" -> reference(dynamic.fields["path"], windows) ?: if (behind) null else return null
        kind == "hyperlink" -> reference(node.attrs["path"], windows)
        kind == "addr" -> INTERFACE_NAMES
        else -> null
    }
    // the second value of a pair field lies under its own id: the mask of a network, the top of a range, the port of an address
    val pair = (node.text("maskid") ?: node.text("highid") ?: node.text("portid"))?.let(::idKey)
    val shown = when {
        kind != "multinumberrange" -> kind
        child?.kind == "community" -> "multicommunity"
        intervals != null -> "multiintervalrange"
        else -> kind
    }
    return PackNativeField(
        name, key, shown, if (kind == "numflag") flagLetters(node) else valueNames(values), node.number("scale")?.toInt(), node.text("postfix"), refers, id.take(1), flag, pair,
        described.flag("range"), negation, separator = intervals?.text("sep"), writable = editable && shown in writing,
    )
}

// a flag number names the values it may hold in a map of the node: under each the name and the letter that stands for it
private fun flagLetters(node: UiNode): Map<Long, String> = (node.attrs["c"] as? JgObject)?.fields.orEmpty().mapNotNull { (bit, words) ->
    val letter = ((words as? JgList)?.items?.getOrNull(1) as? JgString)?.text?.takeIf { it.isNotEmpty() }
    bit.toLongOrNull()?.let { number -> letter?.let { number to it } }
}.toMap()

// enumfilter and defenum wrap the list a field names, and defenum adds a member of its own
// the dynamic list of the values of a node, whether it stands alone, behind the names of a default or a filter, or beside static names
private fun dynamicOf(values: JgObject?, depth: Int = 0): JgObject? {
    if (values == null || depth > MAX_WRAPPERS) return null
    return when ((values.fields["type"] as? JgString)?.text) {
        "dynamic" -> values
        "enumfilter", "defenum" -> dynamicOf(values.fields["values"] as? JgObject, depth + 1)
        "pair" -> (values.fields["c"] as? JgList)?.items.orEmpty().firstNotNullOfOrNull { dynamicOf(it as? JgObject, depth + 1) }
        else -> null
    }
}

private fun valueNames(values: JgObject?, depth: Int = 0): Map<Long, String> {
    if (values == null || depth > MAX_WRAPPERS) return emptyMap()
    return when ((values.fields["type"] as? JgString)?.text) {
        "pair" -> (values.fields["c"] as? JgList)?.items.orEmpty().fold(emptyMap<Long, String>()) { all, part -> all + valueNames(part as? JgObject, depth + 1) }
        "static" -> staticNames(values.fields["map"])
        "enumfilter" -> valueNames(values.fields["values"] as? JgObject, depth + 1)
        "defenum" -> {
            val own = (values.fields["defname"] as? JgString)?.text?.takeIf { it.isNotEmpty() }?.let { label -> (values.fields["defid"] as? JgNumber)?.value?.toLong()?.let { it to label } }
            valueNames(values.fields["values"] as? JgObject, depth + 1) + listOfNotNull(own)
        }
        else -> emptyMap()
    }
}

private fun staticNames(map: JgValue?): Map<Long, String> = when (map) {
    is JgObject -> map.fields.mapNotNull { (number, word) -> number.toLongOrNull()?.let { it to (word as? JgString)?.text.orEmpty() } }.filter { it.second.isNotEmpty() }.toMap()
    is JgList -> map.items.withIndex().mapNotNull { (at, word) -> (word as? JgString)?.text?.let { at.toLong() to it } }.toMap()
    else -> emptyMap()
}

private fun reference(path: JgValue?, windows: Map<List<Long>, UiNode>): PackNativeRef? {
    val handler = (path as? JgList)?.items?.map { (it as? JgNumber)?.value?.toLong() ?: return null } ?: return null
    val target = windows[handler] ?: return null
    val (titles, separator) = when (val named = target.attrs["nameval"]) {
        is JgString -> listOf(named.text) to ""
        is JgObject -> listOfNotNull((named.fields["first"] as? JgString)?.text, (named.fields["second"] as? JgString)?.text) to ((named.fields["sep"] as? JgString)?.text ?: "")
        else -> return null
    }
    val fields = uiForm(target).tabs.flatMap { tab -> tab.items.filterNotNull() }
    val keys = titles.mapNotNull { title -> fields.firstOrNull { it.label == title && it.kind == "string" }?.node?.text("id")?.let(::idKey) }
    return keys.takeIf { it.isNotEmpty() }?.let { PackNativeRef(handler, it, separator) }
}
