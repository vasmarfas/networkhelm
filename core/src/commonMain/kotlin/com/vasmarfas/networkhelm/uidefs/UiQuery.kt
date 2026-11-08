package com.vasmarfas.networkhelm.uidefs

import com.vasmarfas.networkhelm.packs.NATIVE_PATHS
import com.vasmarfas.networkhelm.packs.NATIVE_WRITABLE_KINDS
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackCondition
import com.vasmarfas.networkhelm.packs.PackFallback
import com.vasmarfas.networkhelm.packs.PackFlag
import com.vasmarfas.networkhelm.packs.PackForm
import com.vasmarfas.networkhelm.packs.PackLink
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.packs.PackNativeField
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.packs.PackQuery
import com.vasmarfas.networkhelm.packs.PackTab
import com.vasmarfas.networkhelm.packs.PackWindow
import com.vasmarfas.networkhelm.packs.QUERY_NO_COMMAND
import com.vasmarfas.networkhelm.session.RouterFacts

private const val START = 0xFE000FL
private const val CANCEL = 0xFE0011L
private const val POLL = 0xFE0010L
private const val GET_ALL = 0xFE0004L
private const val REFRESH = 1_000L
private const val LIMIT_MOST = 100_000

private val toolKinds = setOf("query", "action")

private val requestKinds = NATIVE_WRITABLE_KINDS + setOf("addr", "numberrange", "unit", "bigunit", "network", "network6", "macnetwork", "multi")

// windows that change data or settings of the router with no way back: the handler and the title say which
private val risky = setOf(
    "79,1:Format Drive", "79,1:Repair File System", "79,1:Copy", "92,1:Copy To", "92,1:Restore Config From", "24,31:Update", "20,61:LTE Firmware Upgrade", "20,61:Provision",
    "65:Renew License", "123,2:Remove Backup", "123,2:Download Backup", "24,27:Upgrade", "19,1:Sign", "20,0:Cable Test",
)

// the windows of the router that run a query or an action, made into packs; a window of an entry that has no other window of its own stands for the entry,
// the others are hidden packs that the buttons of their entry open
fun uiQueries(entries: List<UiNode>, facts: RouterFacts?): List<Pack> {
    val handled = handlers(entries.flatMap { entry -> entry.children.filter { it.kind == "map" } })
    val taken = HashSet<String>()
    return entries.filter { entry -> entry.shown(facts) && (!entry.flag("nonpublic") || entry.attrs["owner"] != null) }.flatMap { entry ->
        entry.children.filter { it.kind in toolKinds }.mapNotNull { tool -> queryPack(entry, tool, handled, facts, taken) }
    }
}

private class Names {
    private val used = HashSet<String>()

    fun of(label: String): String? {
        val base = nameOf(label) ?: return null
        var name = base
        var number = 1
        while (!used.add(name)) name = base + "-" + ++number
        return name
    }
}

private fun queryPack(entry: UiNode, tool: UiNode, handled: Map<List<Long>, UiNode>, facts: RouterFacts?, taken: MutableSet<String>): Pack? {
    val handler = tool.handler() ?: return null
    val title = tool.label ?: return null
    if (!tool.shown(facts)) return null
    val action = tool.kind == "action"
    val start = tool.number("startcmd") ?: START
    val names = Names()

    val form = uiForm(UiNode("request", emptyMap(), tool.attrs["request"]?.let(::uiNodes).orEmpty().map(::plain)))
    val request = LinkedHashMap<String, PackNativeField>()
    val placed = LinkedHashMap<UiField, String>()
    val notes = LinkedHashMap<String, String>()
    val byNode = HashMap<UiNode, PackNativeField>()
    for (field in form.tabs.flatMap { tab -> tab.items.filterNotNull() }) {
        val name = names.of(field.label) ?: continue
        if (field.node.flag("note")) {
            notes[name] = field.label
            placed[field] = name
            continue
        }
        val native = nativeField(field, name, handled, editable = true, writing = requestKinds)?.takeIf { it.writable } ?: continue
        request[name] = native
        placed[field] = name
        byNode[field.node] = native
    }
    val only = ArrayList<PackCondition>()
    for ((field, name) in placed) {
        val depends = dependsOn(field.needs, byNode)
        if (depends.isEmpty()) continue
        request[name]?.let { request[name] = it.dependingOn(depends) }
        gate(name, depends)?.let { only += it }
    }
    val labels = LinkedHashMap<String, String>()
    val defaults = LinkedHashMap<String, String>()
    val optional = ArrayList<String>()
    val required = ArrayList<String>()
    for ((field, name) in placed) {
        if (name in notes) continue
        labels[name] = captioned(field.label, field.node)
        defaultText(request.getValue(name), field.node)?.let { defaults[name] = it }
        if (field.node.kind == "opt" || field.node.flag("opt")) optional += name
        if (field.node.flag("req")) required += name
    }

    val result = uiForm(UiNode("result", emptyMap(), tool.children))
    val read = LinkedHashMap<String, PackNativeField>()
    val columns = ArrayList<String>()
    // a table whose fields have no width in the description shows them all
    for (field in if (action) emptyList() else result.columns.ifEmpty { result.tabs.flatMap { tab -> tab.items.filterNotNull() } + result.extras }) {
        val name = names.of(field.label) ?: continue
        val native = nativeField(field, name, handled, inside = true) ?: continue
        read[name] = native.reshaped(strip = true)
        columns += name
        labels[name] = captioned(field.label, field.node)
    }
    val flags = ArrayList<PackFlag>()
    for (flag in result.flags) {
        val node = flag.node ?: continue
        val name = names.of(flag.name) ?: continue
        read[name] = nativeField(UiField(flag.name, "flag", true, null, node), name, handled) ?: continue
        flags += PackFlag(name, flag.letter)
    }
    val results = ArrayList<String>()
    var uptime: String? = null
    if (action) {
        for (field in result.tabs.flatMap { tab -> tab.items.filterNotNull() } + result.extras) {
            val name = names.of(field.label) ?: continue
            labels[name] = field.label
            if (tool.text("uptime") == field.label) {
                uptime = name
                results += name
                continue
            }
            val native = nativeField(field, name, handled, inside = true) ?: continue
            read[name] = native.reshaped(strip = false)
            results += name
        }
    }
    val status = ArrayList<String>()
    for (bar in tool.attrs["status"]?.let(::uiNodes).orEmpty() + tool.children) {
        if (bar.kind != "statusbar") continue
        for (node in bar.children) {
            val label = node.label.orEmpty()
            val name = names.of(label.ifEmpty { "status" }) ?: continue
            val native = nativeField(UiField(label, node.kind.orEmpty(), true, null, node), name, handled, inside = true) ?: continue
            read[name] = native.reshaped(strip = false)
            status += name
            if (label.isNotEmpty()) labels[name] = label
        }
    }

    val key = keyOf(handler, action, if (start != QUERY_NO_COMMAND) start else tool.number("pollcmd") ?: tool.number("getallcmd") ?: start, title, taken)
    val query = PackQuery(
        action, request.values.toList(), start, tool.number("cancelcmd") ?: CANCEL,
        if (action) tool.number("pollcmd") ?: POLL else tool.number("getallcmd") ?: GET_ALL,
        tool.number("autorefresh") ?: REFRESH, tool.number("autolimit")?.takeIf { it in 1..LIMIT_MOST }?.toInt(), uptime, optional, required, results, status, tool.text("confirm"), notes, tool.flag("autoclose"),
    )
    val tabs = formTabs(form) { item, _ -> listOfNotNull(placed[item]) }
    fun of(kinds: Set<String>) = read.values.filter { it.kind in kinds || it.kind == "tuple" && it.parts.isNotEmpty() && it.parts.all { part -> part.kind in kinds } }.map { it.name }
    val path = PackPath(
        columns = columns,
        form = key.takeIf { tabs.isNotEmpty() },
        labels = labels,
        defaults = defaults,
        flags = flags,
        bits = of(bitRates),
        bytes = of(byteSizes),
        kibi = of(kibiSizes),
        groups = of(groupedNumbers),
        confirm = tool.text("confirm") != null || handler.joinToString(",") + ":" + title in risky,
        native = PackNative(handler, read.values.toList(), query = query),
    )
    val group = entry.text("group") ?: entry.text("section")
    val owner = (entry.attrs["owner"] as? JgObject)?.fields
    val entryTitle = entry.text("dname") ?: entry.label ?: return null
    val standalone = owner == null && entry.children.none { it.usable() }
    val ownerMenu = if (owner == null) listOfNotNull(group, entryTitle) else listOfNotNull((owner["group"] as? JgString)?.text, (owner["title"] as? JgString)?.text)
    val shown = if (standalone) entryTitle else title
    return Pack(
        menu = if (standalone) ownerMenu else ownerMenu + title,
        window = PackWindow(shown, listOf(PackTab(shown, key))),
        paths = mapOf(key to path),
        forms = if (tabs.isEmpty()) emptyMap() else mapOf(key to PackForm(tabs = tabs, only = only)),
        prio = (entry.attrs["prio"] as? JgNumber)?.value?.toInt(),
        hidden = !standalone,
    )
}

// a secret is a string the client never shows back, a titled cell of the grid is a heading that the fields after it stand under, the history an address offers is no list to choose from,
// and a label is a line of text that stands in the form where the description puts it
private fun plain(node: UiNode): UiNode = when {
    node.kind == "label" && node.name != null -> UiNode("string", node.attrs + ("note" to JgNumber(1.0)), emptyList())
    node.kind == "gridcell" && node.name != null && node.children.isEmpty() -> UiNode("tab", node.attrs + ("inline" to JgNumber(1.0)), emptyList())
    node.kind == "addr" -> UiNode("addr", node.attrs - "values", node.children)
    else -> UiNode(if (node.kind == "secret") "string" else node.kind, node.attrs, node.children.map(::plain))
}

private fun keyOf(handler: List<Long>, action: Boolean, command: Long, title: String, taken: MutableSet<String>): String {
    val base = NATIVE_PATHS + "/" + handler.joinToString("/") + "/" + (if (action) "a" else "q") + command
    if (taken.add(base)) return base
    return (base + "-" + nameOf(title)).also { taken += it }
}

private fun captioned(label: String, node: UiNode): String {
    val unit = (node.text("postfix") ?: node.children.firstOrNull()?.text("postfix"))?.takeIf { it.firstOrNull()?.isLetter() == true }
    return label.trim() + if (unit == null) "" else " ($unit)"
}

// the table shows the unit in the heading, a form beside the value
private fun PackNativeField.reshaped(strip: Boolean): PackNativeField = PackNativeField(
    name, key, kind, names, scale, if (strip) null else postfix?.let { if (it.firstOrNull()?.isLetter() == true) " $it" else it }, refers, letter, flag, pair, range, negation,
    members.map { it.reshaped(strip) }, parts.map { it.reshaped(strip) }, separator ?: "/".takeIf { kind == "tuple" }, writable, depends,
)

// a field stands in the form while the fields the router names hold the values it names
private fun gate(name: String, depends: List<Map<String, String>>): PackCondition? {
    val first = depends.firstOrNull() ?: return null
    if (depends.size == 1) return PackCondition(listOf(name), first.mapValues { listOf(it.value) }, hide = true)
    val key = first.keys.singleOrNull() ?: return null
    if (depends.any { it.keys != first.keys }) return null
    return PackCondition(listOf(name), mapOf(key to depends.map { it.getValue(key) }), hide = true)
}

private fun defaultText(field: PackNativeField, node: UiNode): String? {
    if (field.kind == "numberrange") {
        val low = node.number("deflow") ?: return null
        val high = node.number("defhigh") ?: low
        return if (low == high) low.toString() else "$low..$high"
    }
    val number = node.number("def")?.takeIf { it != QUERY_NO_COMMAND } ?: return null
    return when (field.kind) {
        "enm" -> field.names[number] ?: number.toString()
        "bool", "flag" -> (number != 0L).toString()
        "fixedpoint", "decimal" -> field.scale?.takeIf { it > 1 }?.let { scale -> scaled(number, scale) } ?: number.toString()
        "union" -> null
        else -> number.toString()
    }
}

private fun scaled(number: Long, scale: Int): String {
    val digits = scale.toString().length - 1
    val fraction = (number % scale).toString().padStart(digits, '0').trimEnd('0')
    return (number / scale).toString() + if (fraction.isEmpty()) "" else ".$fraction"
}

private fun PackPath.owns(handler: List<Long>) = native?.let { it.handler == handler && it.query == null } == true

private fun Pack.queryKey(): String? = paths.entries.firstOrNull { it.value.native?.query != null }?.key

private fun Pack.changed(paths: Map<String, PackPath>, forms: Map<String, PackForm> = this.forms, prio: Int? = this.prio) =
    Pack(schema, platform, menu, window, paths, forms, labels, since, until, native, prio, hidden)

// the windows of queries that the router answers for take the place of the console windows with the same name, under the path of the console tab, and the buttons of the
// windows an entry owns open the rest; what the router does not answer for has been dropped by then, so the console windows stay
fun withQueries(packs: List<Pack>, bundled: Collection<Pack>): List<Pack> {
    val queries = packs.filter { it.queryKey() != null }
    if (queries.isEmpty()) return packs
    val kept = packs.filter { it.queryKey() == null }.toMutableList()
    val made = ArrayList<Pack>()
    val upgraded = LinkedHashMap<List<String>, Pack>()
    val linked = LinkedHashMap<List<String>, Pack>()
    for (query in queries) {
        val key = query.queryKey() ?: continue
        val spec = query.paths.getValue(key).native ?: continue
        if (!query.hidden) {
            val target = upgraded[query.menu] ?: kept.firstOrNull { it.menu == query.menu } ?: bundled.firstOrNull { it.menu == query.menu }
            val tab = target?.window?.tabs?.let { tabs -> tabs.firstOrNull { squeezed(it.title) == squeezed(query.window.title) } ?: tabs.singleOrNull() }
            if (target == null || tab == null) {
                made += query
                continue
            }
            val path = query.paths.getValue(key)
            val form = query.forms[key]
            val console = target.paths[tab.path]?.takeIf { it.native?.query == null }?.let { PackFallback(it, it.form?.let(target.forms::get), target.labels) }
            val native = path.native?.let { it.copy(query = it.query?.copy(fallback = console)) }
            val merged = target.changed(
                target.paths + (tab.path to path.copy(form = form?.let { tab.path }, native = native, fasttrack = target.paths[tab.path]?.fasttrack == true)),
                target.forms.minus(tab.path) + listOfNotNull(form?.let { tab.path to it }),
                target.prio ?: query.prio,
            )
            kept.remove(target)
            upgraded[query.menu] = merged
        } else {
            val menu = query.menu.dropLast(1)
            val owner = kept.indexOfFirst { pack -> pack.paths.values.any { it.owns(spec.handler) } }.takeIf { it >= 0 } ?: kept.indexOfFirst { it.menu == menu }.takeIf { it >= 0 }
            // the window of an entry that the router describes only by its queries is the bundled one
            val base = if (owner != null) kept[owner] else linked[menu] ?: bundled.firstOrNull { it.menu == menu } ?: continue
            val at = base.paths.entries.firstOrNull { it.value.owns(spec.handler) }?.key ?: base.window.tabs.firstOrNull()?.path ?: continue
            val shown = base.paths[at] ?: continue
            val link = PackLink(query.window.title, key)
            val grown = base.changed(base.paths + (at to shown.copy(links = shown.links.filter { it.title != link.title } + link)))
            if (owner != null) kept[owner] = grown else linked[menu] = grown
            made += query
        }
    }
    return kept + upgraded.values + linked.values + made
}
