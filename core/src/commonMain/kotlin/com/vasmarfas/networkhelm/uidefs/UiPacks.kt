package com.vasmarfas.networkhelm.uidefs

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.packs.CHANGELOG_FIELD
import com.vasmarfas.networkhelm.packs.GROUP_LINE
import com.vasmarfas.networkhelm.packs.MIRROR_AGE
import com.vasmarfas.networkhelm.packs.MIRROR_SINCE
import com.vasmarfas.networkhelm.packs.MIRROR_STAMP
import com.vasmarfas.networkhelm.packs.MIRROR_TIME
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.NATIVE_PATHS
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackAction
import com.vasmarfas.networkhelm.packs.PackFlag
import com.vasmarfas.networkhelm.packs.PackForm
import com.vasmarfas.networkhelm.packs.PackFormTab
import com.vasmarfas.networkhelm.packs.PackLink
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.packs.PackNativeField
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.packs.PackTab
import com.vasmarfas.networkhelm.packs.PackWindow
import com.vasmarfas.networkhelm.routeros.schema.label
import com.vasmarfas.networkhelm.session.RouterFacts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val packList = ListSerializer(Pack.serializer())
private val mirrorMap = MapSerializer(String.serializer(), PackNative.serializer())
private val keptJson = Json { ignoreUnknownKeys = true }

fun encodeUiPacks(packs: List<Pack>): String = keptJson.encodeToString(packList, packs)

fun decodeUiPacks(text: String): List<Pack>? = try {
    keptJson.decodeFromString(packList, text)
} catch (e: SerializationException) {
    null
}

fun encodeUiMirrors(mirrors: Map<String, PackNative>): String = keptJson.encodeToString(mirrorMap, mirrors)

fun decodeUiMirrors(text: String): Map<String, PackNative>? = try {
    keptJson.decodeFromString(mirrorMap, text)
} catch (e: SerializationException) {
    null
}

class UiMembers(val menus: List<String>, val commands: List<String>)

interface UiSchema {
    suspend fun members(path: MenuPath): UiMembers?

    suspend fun properties(path: MenuPath): List<String>
}

fun uiKnownPaths(menu: List<MenuEntry>, packs: Collection<Pack>): Map<String, String> = buildMap {
    fun walk(entries: List<MenuEntry>, group: String?) {
        for (entry in entries) {
            val path = entry.path
            if (path != null) getOrPut(squeezed(group.orEmpty()) + "/" + squeezed(entry.title)) { path }
            walk(entry.items, entry.title)
        }
    }
    walk(menu, null)
    for (pack in packs) {
        val tab = pack.window.tabs.firstOrNull() ?: continue
        getOrPut(squeezed(pack.menu.dropLast(1).joinToString("")) + "/" + squeezed(pack.menu.last())) { tab.path }
    }
}

suspend fun uiPacks(entries: List<UiNode>, schema: UiSchema, known: Map<String, String>, natively: Boolean = false, facts: RouterFacts? = null, bundled: Collection<Pack> = emptyList()): List<Pack> = coroutineScope {
    val resolver = UiResolver(schema, known)
    val limit = Semaphore(6)
    val handled = if (natively) handlers(entries.flatMap { entry -> entry.children.filter { it.kind == "map" } }) else emptyMap()
    val made = entries.map { entry ->
        async {
            limit.withPermit {
                try {
                    uiPack(entry, resolver, schema, handled, facts)
                } catch (e: DeviceException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
    }.awaitAll().filterNotNull()
    val windows = made.associate { it.name to it.pack.window.tabs.first().path }
    val packs = made.map { it.pack } + bundled
    made.map { it.linked(windows, it.menus.mapValues { (_, items) -> menuLinks(items, entries, packs) }) }
}

// a button that opens the window of an entry by the name of that entry, which is known when the packs of all entries are made
private class Pending(val label: String, val entry: String, val beside: String?, val below: String?, val depends: List<Map<String, String>>, val row: Int = 0)

private class Made(val name: String, val pack: Pack, val opens: Map<String, List<Pending>>, val menus: Map<String, List<UiMenu>> = emptyMap()) {
    fun linked(windows: Map<String, String>, menu: Map<String, List<PackLink>>): Pack {
        if (opens.isEmpty() && menu.values.all { it.isEmpty() }) return pack
        val paths = pack.paths.mapValues { (path, shown) ->
            val items = menu[path].orEmpty()
            val links = opens[path].orEmpty().filter { link -> items.none { it.global && it.title == link.label } }
                .mapNotNull { link -> windows[link.entry]?.takeIf { it != path }?.let { PackLink(link.label, it, beside = link.beside, below = link.below, depends = link.depends, row = link.row) } }
            if (links.isEmpty() && items.isEmpty()) shown else shown.copy(links = shown.links + links, menu = shown.menu + items)
        }
        return Pack(pack.schema, pack.platform, pack.menu, pack.window, paths, pack.forms, pack.labels, pack.since, pack.until, pack.native, pack.prio, pack.hidden)
    }
}

private val toolKinds = setOf("doit", "query", "action")

private val everyElement = setOf("disabled", "dynamic", "invalid", "inactive", "comment")

private const val NATIVE_COLUMNS = 8

internal fun UiNode.usable() = (kind == "map" || kind == "item") && !flag("nonpublic") && text("inherit") == null && text("generic") == null && attrs["typeon"] == null

private fun UiNode.objectKey(): String? = (attrs["path"] as? JgList)?.items?.joinToString(",") { (it as? JgNumber)?.value?.toLong().toString() }

private fun command(title: String, commands: List<String>) =
    matchTitle(title, commands, loose = true) ?: title.removeSuffix(" Tool").takeIf { it != title }?.let { matchTitle(it, commands, loose = true) }

private suspend fun uiPack(entry: UiNode, resolver: UiResolver, schema: UiSchema, handled: Map<List<Long>, UiNode>, facts: RouterFacts?): Made? {
    // an entry that another window owns is no entry of the menu, but the window that window's button opens
    val owned = entry.attrs["owner"] != null
    if (entry.flag("nonpublic") && !owned) return null
    val title = entry.text("dname") ?: entry.label ?: return null
    val group = entry.text("group") ?: entry.text("section")
    if (entry.children.none { it.usable() }) return null
    val base = if (owned) null else resolver.entry(group, entry.name ?: title, title)
    // an entry without a menu of its own is read from the object model, and only where the router does not hide it behind a condition
    if (base == null && (handled.isEmpty() || !entry.shown(facts))) return null
    val children = if (base == null) entry.children.mapNotNull { it.settled(facts) } else entry.children
    val windows = children.filter { it.usable() }
    if (windows.isEmpty()) return null
    val first = children.firstOrNull { it.kind == "map" || it.kind == "item" }
    // the windows of a selector are the modes of one window, chosen from a list beside its title
    val selector = entry.flag("selector")
    val tabs = mutableListOf<PackTab>()
    val paths = LinkedHashMap<String, PackPath>()
    val forms = LinkedHashMap<String, PackForm>()
    val opens = LinkedHashMap<String, List<Pending>>()
    val buttons = LinkedHashMap<String, MutableList<PackLink>>()
    val menus = LinkedHashMap<String, List<UiMenu>>()
    var owner = base?.toString().orEmpty()
    for (window in windows) {
        val form = uiForm(window)
        val candidates = if (base == null) emptyList() else resolver.windows(base, window.label.orEmpty(), window === first)
        // a read-only table whose menu names few of its fields is another table than the menu's, so the router's own is read
        val standing = if (candidates.isNotEmpty() && window.kind == "map" && window.flag("ro") && candidates.none { path -> covered(form, path, schema) }) nativeWindow(window, form, handled) else null
        val found = when {
            standing != null -> standing
            candidates.isEmpty() -> nativeWindow(window, form, handled)?.takeUnless { base != null && resolver.around(base).any { path -> covered(form, path, schema, 2) } }
            candidates.first().toString() in paths -> null
            else -> candidates.firstNotNullOfOrNull { path -> uiWindow(entry, window, form, path, schema, handled)?.let { path.toString() to it } }
        }
        val (key, made) = found ?: continue
        if (key in paths) continue
        // the buttons of a window of the object model that open the window of another entry (Quick Set: Check For Updates, Reboot, Reset Configuration, Password) link to the entry the menu names by its group and title
        val opened = if (made.path.native == null) emptyList() else form.buttons.mapNotNull { button ->
            resolver.known(button.group, button.entry)?.let { PackLink(button.label, it.toString(), beside = made.place(button.beside), below = made.place(button.below), depends = made.depends(button.needs), row = button.row) }
        }.distinctBy { it.path }
        paths[key] = if (opened.isEmpty()) made.path else made.path.copy(links = made.path.links + opened)
        made.form?.let { forms[key] = it }
        val items = if (window.kind == "map") contextMenus(window) else emptyList()
        if (items.isNotEmpty()) menus[key] = items
        // the buttons the menu does not know by group and title may open an entry of its own, which is told by its name once all packs are made
        val later = form.links.map { Pending(it.label, it.entry, null, null, emptyList()) } +
            if (made.path.native == null) emptyList() else form.buttons.filter { resolver.known(it.group, it.entry) == null }.map { Pending(it.label, it.entry, made.place(it.beside), made.place(it.below), made.depends(it.needs), it.row) }
        if (later.isNotEmpty()) opens[key] = later
        if (window.flag("hide")) continue
        val name = window.label.orEmpty().ifEmpty { title }
        if (window === first || selector || first?.kind == "map" && window.kind == "map") {
            tabs += PackTab(name, key)
            owner = key
        } else {
            buttons.getOrPut(owner) { mutableListOf() } += PackLink(name, key)
        }
    }
    if (tabs.isEmpty()) return null
    for ((key, links) in buttons) paths[key] = (paths[key] ?: PackPath()).let { it.copy(links = it.links + links) }
    val prio = (entry.attrs["prio"] as? JgNumber)?.value?.toInt()
    return Made(entry.name ?: title, Pack(menu = listOfNotNull(group, title), window = PackWindow(title, tabs, selector), paths = paths, forms = forms, prio = prio, hidden = owned), opens, menus)
}

// place names the field of the window that a node of the description became, and depends says what the rules of a node ask of the fields
private class UiWindow(val path: PackPath, val form: PackForm?, val place: (UiNode?) -> String? = { null }, val depends: (List<UiRule>) -> List<Map<String, String>> = { emptyList() })

internal fun formTabs(form: UiForm, names: (UiField, UiTab) -> List<String>): List<PackFormTab> = form.tabs.mapNotNull { tab ->
    val fields = mutableListOf<String>()
    for (item in tab.items) {
        for (name in if (item == null) listOf(GROUP_LINE) else names(item, tab)) {
            if (name == GROUP_LINE && (fields.isEmpty() || fields.last() == GROUP_LINE)) continue
            if (name != GROUP_LINE && name in fields) continue
            fields += name
        }
    }
    while (fields.lastOrNull() == GROUP_LINE) fields.removeAt(fields.lastIndex)
    if (fields.isEmpty()) null else PackFormTab(tab.title, fields, inline = tab.inline)
}

private data class Fit(val every: List<String>, val names: List<String>, val titles: List<String>, val matched: Properties)

private suspend fun covered(form: UiForm, path: MenuPath, schema: UiSchema, shares: Int = 3) =
    fit(form, path, schema).let { it.matched.used.count { name -> name != "name" } * shares >= it.titles.size }

private suspend fun fit(form: UiForm, path: MenuPath, schema: UiSchema): Fit {
    val every = schema.properties(path)
    val names = every.filter { it !in everyElement }
    val titles = (form.tabs.flatMap { tab -> tab.items.filterNotNull() } + form.columns).map { it.label }.distinct()
    return Fit(every, names, titles, inGroups(form, titles, names, matchTitles(titles, names, abbreviated = true).mapKeys { titles[it.key] }))
}

private suspend fun uiWindow(entry: UiNode, window: UiNode, form: UiForm, path: MenuPath, schema: UiSchema, handled: Map<List<Long>, UiNode>): UiWindow? {
    val (every, names, titles, matched) = fit(form, path, schema)
    val members = schema.members(path)
    if (titles.size >= 3 && matched.used.none { it != "name" }) return null
    val locked = window.flag("ro")
    // the lines of a log are read whole from the object model, so every field of its window is taken, those the console prints too
    val log = window.flag("logstyle")
    val shown = (form.tabs.flatMap { tab -> tab.items.filterNotNull().map { tab to it } } + form.columns.map { null to it } + form.extras.map { null to it }).filter { (tab, field) -> log || (field.readonly || locked) && matched.of(field, tab) == null }
    // the text box without a title in a window of settings is the one the router fills itself, which the console does not print
    val box = window.kind == "item" && shown.any { (_, field) -> field.isBox() }
    val native = natives(window, shown, if (log) emptySet() else names.toSet() + matched.used, handled, holders = box)
    val properties = native?.let { Properties(matched.general + it.named, matched.byTab) } ?: matched
    fun property(field: UiField, tab: UiTab? = null) = properties.of(field, tab)
    val formTabs = formTabs(form) { item, tab -> native?.parts?.get(item.label) ?: listOfNotNull(property(item, tab)) }
    val fields = form.tabs.flatMap { tab -> tab.items.filterNotNull().map { tab to it } }
    val labels = fields.mapNotNull { (tab, field) -> property(field, tab)?.takeIf { label(it) != field.label && sharesWord(label(it), field.label) }?.let { it to field.label } }.toMap() +
        native?.named?.entries?.associate { (title, name) -> name to title }.orEmpty()
    val readonly = fields.filter { it.second.readonly || locked }.flatMap { (tab, field) -> native?.parts?.get(field.label) ?: listOfNotNull(property(field, tab)) }.distinct()
    val autoset = fields.filter { it.second.autoset }.flatMap { (tab, field) -> native?.parts?.get(field.label) ?: listOfNotNull(property(field, tab)) }.distinct()
    val radios = fields.filter { it.second.isRadio() }.flatMap { (tab, field) -> native?.parts?.get(field.label) ?: listOfNotNull(property(field, tab)) }.distinct()
    val commands = members?.commands.orEmpty()
    val tools = entry.children.filter { it.kind in toolKinds && !it.flag("hide") && !it.flag("nonpublic") && window.objectKey() != null && it.objectKey() == window.objectKey() }
    val rows = window.kind == "map"
    val actions = (tools.filter { it.kind == "doit" }.mapNotNull { tool -> command(tool.label.orEmpty(), commands)?.let { PackAction(tool.label.orEmpty(), it, selection = rows, confirm = tool.text("confirm") != null) } } +
        form.commands.mapNotNull { name -> command(name, commands)?.let { PackAction(name, it, selection = rows) } }).distinctBy { it.command }
    val links = tools.filter { it.kind != "doit" }.mapNotNull { tool -> command(tool.label.orEmpty(), commands)?.let { PackLink(tool.label.orEmpty(), path.child(it).toString()) } }
    val flags = form.flags.mapNotNull { flag ->
        val property = if (flag.letter == "X" || flag.letter == "D") flag.name else every.firstOrNull { squeezed(it) == squeezed(flag.name) }
        property?.let { PackFlag(it, flag.letter) }
    }.takeIf { found -> found.any { it.property != "disabled" && it.property != "dynamic" } }.orEmpty()
    val key = path.toString()
    return UiWindow(
        PackPath(
            columns = form.columns.flatMap { native?.parts?.get(it.label) ?: listOfNotNull(property(it)) }.distinct(),
            readonly = readonly,
            form = key.takeIf { formTabs.isNotEmpty() },
            actions = actions,
            links = links,
            labels = labels,
            flags = flags,
            bits = native?.of(bitRates).orEmpty(),
            bytes = native?.of(byteSizes).orEmpty(),
            kibi = native?.of(kibiSizes).orEmpty(),
            groups = native?.of(groupedNumbers).orEmpty(),
            refresh = window.number("autorefresh"),
            native = native?.spec,
            mirror = if (rows || window.holder()) mirrorOf(window, form, properties, every, handled) else null,
            autoset = autoset,
            radios = radios,
        ),
        formTabs.takeIf { it.isNotEmpty() }?.let { PackForm(tabs = it) },
    )
}

// the windows of the kinds of interfaces inherit the fields of the generic window of the table of all of them, and the descriptions make no
// window of either; their menus of the console are read from that table, each from the rows of its own kind
suspend fun uiInheritedMirrors(entries: List<UiNode>, schema: UiSchema, known: Map<String, String>): Map<String, PackNative> {
    val generics = entries.flatMap { it.children }.filter { it.kind == "map" && it.handler() != null }.mapNotNull { node -> node.text("generic")?.let { it to node } }.toMap()
    if (generics.isEmpty()) return emptyMap()
    val handled = handlers(entries.flatMap { entry -> entry.children.filter { it.kind == "map" } })
    val resolver = UiResolver(schema, known)
    val found = LinkedHashMap<String, PackNative>()
    for (entry in entries) {
        val title = entry.text("dname") ?: entry.label ?: continue
        // the generic window of All Routes stands first and hidden, so the window of IPv4 routes is the one of the menu of the entry
        val first = entry.children.firstOrNull { (it.kind == "map" || it.kind == "item") && !it.flag("nonpublic") }
        for (window in entry.children) {
            if (window.kind != "map" || window.flag("nonpublic")) continue
            val generic = window.text("inherit")?.let(generics::get) ?: window.takeIf { it.text("generic") != null } ?: continue
            val base = resolver.entry(entry.text("group") ?: entry.text("section"), entry.name ?: title, title) ?: continue
            // the kind of a tunnel is named without the word, EoIP Tunnel by the menu eoip
            val label = window.label.orEmpty()
            val path = (resolver.windows(base, label, window === first || window === generic).firstOrNull() ?: resolver.windows(base, label.removeSuffix(" Tunnel"), false).firstOrNull())?.toString() ?: continue
            if (path in found) continue
            val merged = inherited(generic, window)
            val form = uiForm(merged)
            val (every, _, _, matched) = fit(form, MenuPath.parse(path), schema)
            // a window the menu names few of its own fields is the table of another menu, unless it is the window of the menu of its entry, as
            // IPv4 Routes with the attributes of BGP, OSPF and LDP the console does not print
            val own = window.children.toSet()
            val placed = form.tabs.flatMap { tab -> tab.items.filterNotNull().filter { it.node in own }.map { tab to it } }
            if (window === generic && !covered(form, MenuPath.parse(path), schema) || window !== first && placed.count { (tab, field) -> matched.of(field, tab) in every } * 2 < placed.size) continue
            val spec = mirrorOf(merged, form, matched, every, handled) ?: continue
            val kind = window.number("typevalue")
            val typeKey = generic.children.firstOrNull { it.name == generic.text("typeon") }?.text("id")?.drop(1)?.toIntOrNull(16)
            found[path] = when {
                kind == null -> spec
                typeKey == null -> continue
                else -> {
                    val type = spec.fields.firstOrNull { it.key == typeKey } ?: PackNativeField(KIND_FIELD, typeKey, "number", letter = "u")
                    spec.copy(fields = if (type in spec.fields) spec.fields else spec.fields + type, only = mapOf(type.name to kind.toString()))
                }
            }
        }
    }
    return found
}

// the window of a kind takes the fields of the generic window, its own fields of the same id in their place; the kind of a row, which the
// generic window shows by its name, is the number the table holds under typeon
private fun inherited(generic: UiNode, window: UiNode): UiNode {
    val typeon = generic.text("typeon")
    val kind = generic.children.firstOrNull { it.name == typeon && it.text("id") != null }
    fun shown(node: UiNode) = if (node.kind == "objtype" && kind != null) UiNode(kind.kind, kind.attrs - "nonpublic" + ("name" to JgString(node.name ?: "Type")), kind.children) else node
    if (generic === window) return UiNode(window.kind, window.attrs, window.children.map(::shown))
    val own = window.children.mapNotNull { child -> child.text("id")?.let { it to child } }.toMap()
    val ids = generic.children.mapNotNullTo(HashSet()) { it.text("id") }
    val children = generic.children.map { child -> child.text("id")?.let(own::get) ?: shown(child) } + window.children.filter { it.text("id") == null || it.text("id") !in ids }
    return UiNode(window.kind, generic.attrs - "generic" - "typeon" + (window.attrs - "inherit"), children)
}

private const val KIND_FIELD = ".kind"

// the fields every element of the object model has, which a window does not list
private val ELEMENT_FIELDS = listOf(
    PackNativeField("comment", 0xFE0009, "string", letter = "s"),
    PackNativeField("disabled", 0xFE000A, "bool", letter = "b"),
    PackNativeField("dynamic", 0xFE0007, "bool", letter = "b"),
    PackNativeField("invalid", 0xFE0008, "bool", letter = "b"),
)

// the groups of fields of a read of the object model: the base ones and the counters, with those the refresh of a window asks for
private const val READ_BASE = 0x10000005L
private const val READ_COUNTERS = 0x2L

private val SECOND_POSTFIXES = setOf("s", " s", "sec", " sec")

// a table of the console in the shape of its window: each field the console prints and the description places, with its key, under the name of the console;
// a column or a flag the window cannot read leaves the table to the console, a field of the form it cannot read leaves the window of a row to it
private fun mirrorOf(window: UiNode, form: UiForm, properties: Properties, every: List<String>, handled: Map<List<Long>, UiNode>): PackNative? {
    if (handled.isEmpty()) return null
    val handler = window.handler() ?: return null
    val fields = LinkedHashMap<String, PackNativeField>()
    val unread = HashSet<String>()
    val columns = form.columns.mapNotNullTo(HashSet()) { properties.of(it, null) }
    val placed = form.tabs.flatMap { tab -> tab.items.filterNotNull().map { tab to it } } + (form.columns + form.extras).map { null to it }
    for ((tab, whole) in placed) {
        // a tuple the console prints as one property stays one field the window does not read; one it prints a property for each part of,
        // as orig-bytes and repl-bytes of Orig./Repl. Bytes, is read a part at a time
        val split = whole.parts()?.takeIf { properties.of(whole, tab) !in every }
        for (field in split ?: listOf(whole)) {
            // the console splits an address with its port in two, and names the address part so
            val name = properties.of(field, tab)?.takeIf { it in every }
                ?: nameOf(field.label)?.let { if (split != null) it else "$it-address".takeIf { field.node.kind == "addr" } }?.takeIf { it in every }
                ?: continue
            if (name in fields) continue
            val native = nativeField(field, name, handled) ?: secretField(field, name) ?: sinceField(field, name)
            if (native == null) unread += name else fields[name] = consoleField(native, field.node.text("postfix"))
        }
    }
    // the fields of the panes of a deck stand for one value of the field that chooses the pane, as To Addresses for the actions of NAT;
    // the console names them by their titles, and one it names otherwise leaves the window of a row to the console
    for (node in window.panes()) {
        val label = node.label ?: continue
        val name = nameOf(label)?.takeIf { it in every }
        if (name == null) {
            unread += label
            continue
        }
        if (name in fields) continue
        val field = UiField(label, node.kind.orEmpty(), node.flag("ro"), null, node)
        val native = nativeField(field, name, handled) ?: secretField(field, name) ?: sinceField(field, name)
        if (native == null) unread += name else fields[name] = consoleField(native, node.text("postfix"))
    }
    for (flag in form.flags) {
        val node = flag.node ?: continue
        val name = (if (flag.letter == "X" || flag.letter == "D") flag.name else every.firstOrNull { squeezed(it) == squeezed(flag.name) }) ?: continue
        if (name in fields) continue
        // Disabled and Dynamic are flags of every element, which the window names without an id of its own
        val native = nativeField(UiField(flag.name, "flag", true, null, node), name, handled)?.let { consoleField(it) } ?: ELEMENT_FIELDS.firstOrNull { it.name == name }
        if (native == null) unread += name else fields[name] = native
    }
    for (field in ELEMENT_FIELDS) if (field.name in every && field.name !in fields) fields[field.name] = field
    unread -= fields.keys
    // a window of settings is its form, which has to be whole
    val single = window.kind == "item"
    if (fields.isEmpty() || unread.any { it in columns } || single && unread.isNotEmpty()) return null
    val refreshed = window.number("refreshfilter") ?: 0L
    // the rows of an ordered table stand where the router keeps them, and the console has to print them in that order
    return PackNative(
        handler, fields.values.toList(), single = single, whole = true, partial = unread.isNotEmpty(), mask = READ_BASE or READ_COUNTERS or refreshed, move = window.flag("ordered"),
        most = window.number("maxobjs")?.toInt(),
    )
}

// the fields of the panes of the decks of a window, those of panes inside panes too
private fun UiNode.panes(): List<UiNode> = children.flatMap { child ->
    val panes = (child.attrs["panes"] as? JgList)?.items.orEmpty().flatMap { pane -> (pane as? JgObject)?.fields?.get("c")?.let(::uiNodes).orEmpty() }
    panes.filter { it.name != null && it.kind != "separator" } + UiNode(null, emptyMap(), panes).panes() + child.panes()
}

// a date the router keeps as the uptime it came at, in hundredths of a second
private fun sinceField(field: UiField, name: String): PackNativeField? {
    val node = field.node
    val id = node.text("id")?.takeIf { node.kind == "dateandtime" && node.flag("relative") } ?: return null
    return id.drop(1).toIntOrNull(16)?.let { PackNativeField(name, it, MIRROR_SINCE, scale = node.number("scale")?.toInt(), letter = id.take(1)) }
}

// a key or a password the console prints to a user who may see it; whether the window sends it too, the check of the mirror finds out
private fun secretField(field: UiField, name: String): PackNativeField? {
    val id = field.node.text("id")?.takeIf { field.node.kind == "secret" } ?: return null
    return id.drop(1).toIntOrNull(16)?.let { PackNativeField(name, it, "string", letter = id.take(1)) }
}

// the console prints a number without the unit WinBox puts after it, a number of seconds as a time, and a time and a date its own way
private fun consoleField(field: PackNativeField, postfix: String? = null): PackNativeField = PackNativeField(
    field.name,
    field.key,
    when (field.kind) {
        "interval", "clocktime" -> MIRROR_TIME
        "age" -> MIRROR_AGE
        "dateandtime" -> MIRROR_STAMP
        "unit", "bigunit" -> "number"
        // the offset of a time zone is printed as the signed number of its seconds
        "timezone" -> "integer"
        "number", "integer", "fixedpoint", "decimal", "bigdecimal" -> if (postfix in SECOND_POSTFIXES) MIRROR_TIME else field.kind
        else -> field.kind
    },
    field.names, field.scale, null, field.refers, field.letter, field.flag, field.pair, field.range, field.negation,
    field.members.map(::consoleField), field.parts.map(::consoleField), field.separator,
)

private class Natives(val spec: PackNative, val named: Map<String, String>, val parts: Map<String, List<String>>, val controllers: Map<UiNode, PackNativeField> = emptyMap()) {
    fun of(kinds: Set<String>) = spec.fields.filter { it.kind in kinds }.map { it.name }
}

// a window of settings is read whole with the singleton command, unless the router names a command of its own or hides the window behind a condition
private fun UiNode.holder() = kind == "item" && attrs["getcmd"] == null && attrs["pred"] == null

private fun UiField.isBox() = kind == "multilinestring" && label.isEmpty()

private fun UiField.isRadio() = kind == "enm" && node.flag("radio")

private fun natives(window: UiNode, fields: List<Pair<UiTab?, UiField>>, taken: Set<String>, handled: Map<List<Long>, UiNode>, flags: List<UiFlag> = emptyList(), holders: Boolean = false, doits: List<UiDoit> = emptyList(), active: Boolean = false): Natives? {
    if (handled.isEmpty()) return null
    val handler = window.handler()?.takeIf { window.kind == "map" && window.attrs["maxobjs"] == null || holders && window.holder() } ?: return null
    val used = taken.toMutableSet()
    val read = LinkedHashMap<String, PackNativeField>()
    val parts = LinkedHashMap<String, List<String>>()
    val editable = !window.flag("ro")
    // a field no form shows has a name that starts with a dot, as the console's own .id does
    fun take(field: UiField, hidden: Boolean = false): String? {
        if (field.label in read) return null
        val title = if (field.isBox() && window.kind == "item") CHANGELOG_FIELD else field.label
        val name = nameOf(title)?.let { if (hidden) ".$it" else it }?.takeIf { it !in used } ?: return null
        val native = nativeField(field, name, handled, editable = editable && !field.readonly) ?: return null
        used += name
        read[field.label] = native
        return name
    }
    for ((_, field) in fields) {
        val split = field.parts()
        if (split == null) {
            take(field)
        } else {
            val names = split.mapNotNull(::take)
            if (names.isNotEmpty()) parts[field.label] = names
        }
    }
    for (flag in flags) flag.node?.let { take(UiField(flag.name, "flag", true, null, it)) }
    var controllers = emptyMap<UiNode, PackNativeField>()
    if (taken.isEmpty()) {
        val byNode = fields.mapNotNull { (_, field) -> read[field.label]?.let { field.node to it } }.toMap().toMutableMap()
        // a condition may ask about a field that no form shows
        for ((_, field) in fields) {
            for (rule in field.needs) {
                val node = rule.controller
                val label = node.label?.takeIf { node !in byNode } ?: continue
                take(UiField(label, node.kind.orEmpty(), true, null, node), hidden = true)
                read[label]?.let { byNode[node] = it }
            }
        }
        val needs = fields.filter { (_, field) -> field.needs.isNotEmpty() }.associate { (_, field) -> field.label to field.needs }
        for ((label, native) in read.entries.toList()) read[label] = native.dependingOn(dependsOn(needs[label].orEmpty(), byNode))
        controllers = byNode
    }
    // the ACTIVE label of a window shows the flag 0xFE000A of its record, which is down for the active one
    if (active && read.isNotEmpty()) read["inactive"] = PackNativeField(".inactive", INACTIVE_KEY, "bool", letter = "b")
    val rows = window.kind == "map"
    return read.takeIf { it.isNotEmpty() }?.let { fields ->
        val spec = PackNative(
            handler,
            fields.values.toList(),
            single = !rows,
            edit = editable && fields.values.any { it.writable },
            add = rows && editable && !window.flag("nonaddable"),
            remove = rows && (editable || window.flag("removable")),
            move = rows && editable && window.flag("ordered"),
            doits = doits.map { it.number }.distinct(),
            log = rows && window.flag("logstyle"),
        )
        Natives(spec, fields.mapValues { (_, field) -> field.name }, parts, controllers)
    }
}

private const val SAME = '\u0001'

private const val INACTIVE_KEY = 0xFE000A

private const val MAX_ALTERNATIVES = 16

// a field of a form stands in a place: its title, its id and the rules it stands under; places that share a title, as the modes of Quick Set share IP Address,
// are told apart by a number behind it; a column or a field of a group keeps the title of the place that has its id, or of the first place of its title
private fun UiForm.distinct(): UiForm {
    val places = HashMap<String, MutableMap<Pair<String?, List<Pair<UiNode, JgValue?>>>, String>>()
    fun labelOf(field: UiField): String {
        val byPlace = places.getOrPut(field.label) { LinkedHashMap() }
        return byPlace.getOrPut(field.node.text("id") to field.needs.map { it.controller to it.pred }) { if (byPlace.isEmpty()) field.label else "${field.label}$SAME${byPlace.size + 1}" }
    }
    fun renamed(field: UiField, label: String) = UiField(label, field.kind, field.readonly, field.width, field.node, field.needs, field.autoset)
    val stood = tabs.map { tab -> UiTab(tab.title, tab.items.map { item -> item?.let { renamed(it, labelOf(it)) } }, tab.inline) }
    fun alias(field: UiField): UiField {
        val byPlace = places[field.label] ?: return renamed(field, labelOf(field))
        val id = field.node.text("id")
        return renamed(field, byPlace.entries.firstOrNull { it.key.first == id }?.value ?: byPlace.values.first())
    }
    return UiForm(stood, columns.map(::alias), flags, commands, links, extras.map(::alias), doits, buttons, active)
}

// what the rules of a field ask, said in the texts the form shows: sets of values of other fields of which one must hold; a rule that cannot be said so asks nothing
internal fun dependsOn(needs: List<UiRule>, native: Map<UiNode, PackNativeField>): List<Map<String, String>> {
    var alternatives = listOf(emptyMap<String, String>())
    for (rule in needs) {
        val controller = native[rule.controller] ?: continue
        val texts = answers(rule.pred, controller) ?: continue
        alternatives = alternatives.flatMap { known -> texts.map { known + (controller.name to it) } }
        if (alternatives.size > MAX_ALTERNATIVES) return emptyList()
    }
    return alternatives.takeIf { it.any { known -> known.isNotEmpty() } }.orEmpty()
}

private fun answers(pred: JgValue?, controller: PackNativeField): List<String>? {
    val fields = (pred as? JgObject)?.fields ?: return null
    val numbers = when (val value = fields["value"]) {
        is JgNumber -> listOf(value.value.toLong())
        is JgList -> value.items.map { (it as? JgNumber)?.value?.toLong() ?: return null }
        else -> return null
    }
    return when ((fields["type"] as? JgString)?.text) {
        "bool", "number" -> when {
            controller.kind == "bool" || controller.kind == "flag" -> numbers.map { (it != 0L).toString() }
            controller.kind == "enm" && numbers.all { it in controller.names } -> numbers.map { controller.names.getValue(it) }
            controller.kind == "number" || controller.kind == "integer" -> numbers.map { it.toString() }
            else -> null
        }
        else -> null
    }
}

private fun nativeWindow(window: UiNode, originalForm: UiForm, handled: Map<List<Long>, UiNode>): Pair<String, UiWindow>? {
    val form = originalForm.distinct()
    // a table without a title of its own is a list the dropdowns of other windows draw their names from, and no window of the menu
    if (window.flag("hide") || window.label.isNullOrEmpty() || !window.flag("ro") && window.text("title") == null) return null
    val native = natives(window, (form.tabs.flatMap { tab -> tab.items.filterNotNull().map { tab to it } } + form.columns.map { null to it } + form.extras.map { null to it }), emptySet(), handled, form.flags, holders = true, doits = form.doits, active = form.active) ?: return null
    val flagged = form.flags.mapNotNull { flag -> native.named[flag.name]?.let { PackFlag(it, flag.letter) } }
    val shown = native.named.filterKeys { title -> form.flags.none { it.name == title } }.filterValues { !it.startsWith(".") }
    val columns = form.columns.flatMap { native.parts[it.label] ?: listOfNotNull(native.named[it.label]) }.distinct().ifEmpty { shown.values.take(NATIVE_COLUMNS) }
    val tabs = formTabs(form) { item, _ -> native.parts[item.label] ?: listOfNotNull(native.named[item.label]) }
    val key = NATIVE_PATHS + "/" + native.spec.handler.joinToString("/")
    val titles = form.tabs.flatMap { tab -> tab.items.filterNotNull() }.associate { it.node to it.label }
    val place = { node: UiNode? -> node?.let { titles[it] }?.let { title -> native.parts[title]?.lastOrNull() ?: native.named[title] } }
    // a button that stands beside a field or under it is one of its place; the others of a command are one button
    val actions = form.doits.groupBy { Triple(it.number, place(it.beside), place(it.below)) }.map { (at, same) ->
        val alternatives = same.map { dependsOn(it.needs, native.controllers) }
        PackAction(
            same.first().title, at.first.toString(), selection = window.kind == "map", confirm = same.any { it.confirm }, argument = "id",
            depends = if (alternatives.any { it.isEmpty() }) emptyList() else alternatives.flatten(), tabs = same.map { it.tab }.distinct(), beside = at.second, below = at.third, row = same.first().row,
        )
    }
    return key to
        UiWindow(
            PackPath(
                columns = columns,
                actions = actions,
                readonly = native.spec.fields.filter { !native.spec.edit || !it.writable }.map { it.name },
                form = key.takeIf { tabs.isNotEmpty() },
                labels = native.named.entries.associate { (title, name) -> name to title.substringBefore(SAME) },
                flags = flagged,
                bits = native.of(bitRates),
                bytes = native.of(byteSizes),
                kibi = native.of(kibiSizes),
                groups = native.of(groupedNumbers),
                refresh = window.number("autorefresh"),
                native = native.spec,
                radios = form.tabs.flatMap { tab -> tab.items.filterNotNull() }.filter { it.isRadio() }.flatMap { native.parts[it.label] ?: listOfNotNull(native.named[it.label]) }.distinct(),
            ),
            tabs.takeIf { it.isNotEmpty() }?.let { PackForm(tabs = it) },
            place,
        ) { needs -> dependsOn(needs, native.controllers) }
}

private class Properties(val general: Map<String, String>, val byTab: Map<UiTab, Map<String, String>>) {
    fun of(field: UiField, tab: UiTab? = null): String? = byTab[tab]?.get(field.label) ?: general[field.label]

    val used get() = general.values + byTab.values.flatMap { it.values }
}

private fun inGroups(form: UiForm, titles: List<String>, names: List<String>, mapped: Map<String, String>): Properties {
    val general = mapped.toMutableMap()
    val byTab = HashMap<UiTab, Map<String, String>>()
    fun among(labels: List<String>, group: List<String>): Map<String, String> {
        val inner = group.filter { name -> group.count { it.substringAfter('.') == name.substringAfter('.') } == 1 }
        return matchTitles(labels, inner.map { it.substringAfter('.') }, pairLast = false, abbreviated = true).entries.associate { (at, rest) -> labels[at] to inner.first { it.substringAfter('.') == rest } }
    }
    val inside = names.filter { '.' in it }
    val taken = mapped.values.toMutableSet()
    for (tab in form.tabs) {
        val labels = tab.items.filterNotNull().map { it.label }.filter { it !in mapped }.distinct()
        val found = among(labels, inside.filter { squeezed(it.substringBefore('.')) == squeezed(tab.title) && it !in taken })
        if (found.isNotEmpty()) byTab[tab] = found
        taken += found.values
    }
    general += among(titles.filter { it !in general && byTab.values.none { found -> it in found } }, inside.filter { it !in taken })
    return Properties(general, byTab)
}

private class UiResolver(private val schema: UiSchema, private val known: Map<String, String>) {
    private val cache = HashMap<MenuPath, UiMembers?>()
    private val lock = Mutex()

    private suspend fun members(path: MenuPath): UiMembers? {
        lock.withLock { if (path in cache) return cache[path] }
        val fresh = schema.members(path)
        lock.withLock { cache[path] = fresh }
        return fresh
    }

    private suspend fun menus(path: MenuPath) = members(path)?.let { it.menus + it.commands }.orEmpty()

    fun known(group: String?, title: String): MenuPath? = known[squeezed(group.orEmpty()) + "/" + squeezed(title)]?.let(MenuPath::parse)

    suspend fun entry(group: String?, name: String, title: String): MenuPath? {
        for (key in listOf(title, name)) known[squeezed(group.orEmpty()) + "/" + squeezed(key)]?.let { return MenuPath.parse(it).takeIf { path -> members(path) != null } }
        val root = menus(MenuPath.Root)
        val directories = when {
            group == null -> listOf(MenuPath.Root, MenuPath.parse("interface"), MenuPath.parse("ppp"))
            else -> listOfNotNull(matchTitle(group, root)?.let { MenuPath(listOf(it)) })
        }
        for (directory in directories) {
            // an entry of the top level names a menu: set, add and print are commands of the directory, not its windows
            val names = if (group == null) members(directory)?.menus.orEmpty() else menus(directory)
            for (key in listOf(name, title)) matchTitle(key, names)?.let { return directory.child(it) }
        }
        val words = wordsOf(name)
        for (cut in 1 until words.size) {
            val first = matchTitle(words.take(cut).joinToString(" "), root) ?: continue
            val rest = words.drop(cut).joinToString(" ")
            val inside = MenuPath(listOf(first))
            return (matchTitle(rest, menus(inside))?.let { inside.child(it) } ?: inside.takeIf { squeezed(rest) == "settings" })?.takeIf { members(it) != null }
        }
        return null
    }

    suspend fun around(base: MenuPath): List<MenuPath> {
        val inside = members(base) ?: return emptyList()
        val parent = base.parent?.takeIf { it.segments.isNotEmpty() }
        val beside = parent?.let { up -> members(up)?.menus.orEmpty().filter { it != base.segments.last() }.map(up::child) }.orEmpty()
        return listOf(base) + inside.menus.map(base::child) + beside
    }

    suspend fun windows(base: MenuPath, title: String, first: Boolean): List<MenuPath> {
        val inside = members(base) ?: return emptyList()
        val own = "print" in inside.commands || "set" in inside.commands
        val parent = base.parent?.takeIf { it.segments.isNotEmpty() }
        val beside = parent?.let { members(it)?.menus }.orEmpty().filter { it != base.segments.last() }
        return listOfNotNull(
            base.takeIf { first && own },
            matchTitle(title, inside.menus)?.let(base::child),
            parent?.let { up -> matchTitle(title, beside)?.let(up::child) },
        ).filter { members(it) != null }.distinct()
    }
}
