package com.vasmarfas.networkhelm.packs

import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.ValueType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
class Pack(
    @SerialName("\$schema") val schema: String? = null,
    val platform: String = "routeros",
    val menu: List<String>,
    val window: PackWindow,
    val paths: Map<String, PackPath> = emptyMap(),
    val forms: Map<String, PackForm> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
    val since: String? = null,
    val until: String? = null,
    val native: Boolean = false,
    val prio: Int? = null,
    // a hidden pack is a window that other windows open, and no entry of the menu
    val hidden: Boolean = false,
)

// a selector window shows its tabs as the choices of a list beside the title, even when there is one
@Serializable
class PackWindow(val title: String, val tabs: List<PackTab>, val selector: Boolean = false)

@Serializable
class PackTab(val title: String, val path: String, val since: String? = null, val until: String? = null)

@Serializable
data class PackPath(
    val ordered: Boolean = false,
    val columns: List<String> = emptyList(),
    val readonly: List<String> = emptyList(),
    val form: String? = null,
    val actions: List<PackAction> = emptyList(),
    val quick: String? = null,
    val category: String? = null,
    val iconBy: String? = null,
    val sort: String? = null,
    val follow: Boolean = false,
    val log: Boolean = false,
    // how often in milliseconds the window of the router reads its table again, autorefresh of its description
    val refresh: Long? = null,
    val join: String? = null,
    val monitor: PackMonitor? = null,
    val open: PackOpen? = null,
    val owner: PackOwner? = null,
    val links: List<PackLink> = emptyList(),
    val menu: List<PackLink> = emptyList(),
    val confirm: Boolean = false,
    val where: Map<String, List<String>> = emptyMap(),
    val summary: List<String> = emptyList(),
    val totals: List<String> = emptyList(),
    val numbered: Boolean = false,
    val history: String? = null,
    val flows: List<String> = emptyList(),
    val bits: List<String> = emptyList(),
    val limits: List<String> = emptyList(),
    val bytes: List<String> = emptyList(),
    val kibi: List<String> = emptyList(),
    val groups: List<String> = emptyList(),
    val pairs: Map<String, List<String>> = emptyMap(),
    val gauges: Map<String, String> = emptyMap(),
    val fasttrack: Boolean = false,
    val autostart: Boolean = false,
    val files: Boolean = false,
    val uploads: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val element: String? = null,
    val types: Map<String, ValueType> = emptyMap(),
    val defaults: Map<String, String> = emptyMap(),
    val flags: List<PackFlag> = emptyList(),
    val native: PackNative? = null,
    // the whole table of the menu in its window of the WinBox port, each field under the name of the console
    val mirror: PackNative? = null,
    val combos: Map<String, PackCombo> = emptyMap(),
    // the fields that go to the router as soon as they change, without Apply
    val autoset: List<String> = emptyList(),
    val derived: Map<String, PackDerived> = emptyMap(),
    // read-only fields the window shows empty while the router sends nothing for them, so that its lines do not jump
    val blank: List<String> = emptyList(),
    // the enumerations the router shows as radio buttons
    val radios: List<String> = emptyList(),
    // titles of the columns of a command's table and of the lines under it, over the labels of the path: the size of a ping is Packet Size in its form and Reply Size in its table
    val columnLabels: Map<String, String> = emptyMap(),
    // times the table writes in milliseconds, as WinBox does for the answers of a ping
    val millis: List<String> = emptyList(),
    // arguments of a command that take the name of the first row, by number, of another menu when the window opens, as WinBox does: the interface of torch
    val preselect: Map<String, String> = emptyMap(),
)

// a value the form works out of another one: the first of the values whose words the other one contains
@Serializable
class PackDerived(val of: String, val values: Map<String, List<String>>)

@Serializable
class PackFlag(val property: String, val letter: String)

const val NATIVE_PATHS = "native"

// the kinds of a field of a mirror that come as the bare number and are written as the console writes a time, the time left until a moment
// on the clock of the uptime of the router, and a date
const val MIRROR_TIME = "console-time"
const val MIRROR_AGE = "console-age"
const val MIRROR_STAMP = "console-stamp"
// a moment on the clock of the uptime of the router in hundredths of a second, shown as the date and time it came at
const val MIRROR_SINCE = "console-since"

// a flag that is up while the record holds the field of its key
const val PRESENCE = "presence"

// the list of changes of the update window: the router's own text, or the vendor's text for the version the router names
const val CHANGELOG_FIELD = "changelog"

@Serializable
data class PackNative(
    val handler: List<Long>,
    val fields: List<PackNativeField>,
    val single: Boolean = false,
    val edit: Boolean = false,
    val add: Boolean = false,
    val remove: Boolean = false,
    val move: Boolean = false,
    val doits: List<Int> = emptyList(),
    val query: PackQuery? = null,
    // the router calls the table a log: its lines are read from the first and then those added after the last one
    val log: Boolean = false,
    // the table stands in for a menu of the console, so a read the router broke off between two pages is no read of it
    val whole: Boolean = false,
    // the mirror of a menu lacks fields of the form of a row, which the console reads
    val partial: Boolean = false,
    // the read asks for the fields of these groups: the base ones, and those the window refreshes, as its counters
    val mask: Long? = null,
    // the rows of the table that hold these values: the window of one kind of interface reads the table of all of them
    val only: Map<String, String> = emptyMap(),
    // the most rows the window shows before it asks to load them all, maxobjs of its description; a bigger table the console reads
    val most: Int? = null,
)

// a query or an action window of the router: the commands that start, read and end it, and the fields of its request; the fields of the result are those of the PackNative
@Serializable
data class PackQuery(
    val action: Boolean,
    val request: List<PackNativeField>,
    val start: Long,
    val cancel: Long,
    val poll: Long,
    val refresh: Long = 1_000,
    val limit: Int? = null,
    val uptime: String? = null,
    val optional: List<String> = emptyList(),
    val required: List<String> = emptyList(),
    val results: List<String> = emptyList(),
    val status: List<String> = emptyList(),
    val confirm: String? = null,
    val notes: Map<String, String> = emptyMap(),
    val close: Boolean = false,
    val fallback: PackFallback? = null,
)

// the console window that a query took the place of, for the one time the router refuses the query
@Serializable
class PackFallback(val path: PackPath, val form: PackForm? = null, val labels: Map<String, String> = emptyMap())

// the number a description gives a command it does not have
const val QUERY_NO_COMMAND = 0xFFFFFFFFL

@Serializable
class PackNativeField(
    val name: String,
    val key: Int,
    val kind: String,
    val names: Map<Long, String> = emptyMap(),
    val scale: Int? = null,
    val postfix: String? = null,
    val refers: PackNativeRef? = null,
    val letter: String? = null,
    val flag: Int? = null,
    val pair: Int? = null,
    val range: Boolean = false,
    val negation: Int? = null,
    val members: List<PackNativeField> = emptyList(),
    val parts: List<PackNativeField> = emptyList(),
    val separator: String? = null,
    val writable: Boolean = false,
    val depends: List<Map<String, String>> = emptyList(),
) {
    // the field stands in a form while one of these sets of other fields hold the values it names
    fun dependingOn(depends: List<Map<String, String>>) =
        PackNativeField(name, key, kind, names, scale, postfix, refers, letter, flag, pair, range, negation, members, parts, separator, writable, depends)
}

const val NATIVE_NO_KEY = -1

// the kinds of a native field whose value the client can write back: the writer of the channel knows each of them
val NATIVE_WRITABLE_KINDS = setOf(
    "string", "script", "bool", "flag", "number", "integer", "bignumber", "enm", "set", "interval", "clocktime", "clockdate", "dateandtime", "timezone", "ipaddr", "netmask",
    "macaddr", "ip6addr", "bytes", "bigbytes", "kbytes", "bitrate", "bigbitrate", "fixedpoint", "decimal", "bigdecimal", "multistring", "multinumber", "multiipaddr", "multiip6addr",
)

@Serializable
data class PackNativeRef(val handler: List<Long>, val keys: List<Int>, val separator: String = "")

fun MenuSchema.withDefaults(defaults: Map<String, String>): MenuSchema = if (defaults.isEmpty()) this else MenuSchema(
    path, kind, children, commands,
    arguments.map { argument -> defaults[argument.name]?.takeIf { argument.default == null }?.let { argument.copy(default = it) } ?: argument },
    listens, prefill, lacks,
)

fun MenuSchema.withRadios(radios: List<String>): MenuSchema = if (radios.isEmpty()) this else MenuSchema(
    path, kind, children, commands,
    arguments.map { argument -> if (argument.name in radios) argument.copy(radio = true) else argument },
    listens, prefill, lacks,
)

// beside: the button stands in the row of that field; below: in a row of its own under that field; neither: among the buttons beside the form
// row: the toolbar of the window under that field, and a gap between two rows is a line
// fill: the fields of the window it opens that take their values from the row it is opened for; item: it opens a new row of the table at path
@Serializable
class PackLink(
    val title: String,
    val path: String,
    val window: String? = null,
    val argument: String? = null,
    val beside: String? = null,
    val below: String? = null,
    val depends: List<Map<String, String>> = emptyList(),
    val fill: List<PackFill> = emptyList(),
    val autostart: Boolean = false,
    val global: Boolean = false,
    val item: Boolean = false,
    val row: Int = 0,
)

val PackLink.opensTable: Boolean get() = window == null && argument == null && fill.isEmpty() && !item && !autostart && depends.isEmpty()

// the buttons of a settings tab that open a table the pack describes
fun Pack.tablesOf(tab: PackTab): List<PackLink> {
    val shown = paths[tab.path]
    if (shown == null || shown.columns.isNotEmpty()) return emptyList()
    return shown.links.filter { link -> link.opensTable && window.tabs.none { it.path == link.path } && paths[link.path]?.columns?.isNotEmpty() == true }
}

// the tables of a settings window as its own tabs, right after the settings
fun Pack.withTablesAsTabs(): Pack {
    val tabs = window.tabs.flatMap { tab -> listOf(tab) + tablesOf(tab).map { PackTab(it.title, it.path, tab.since, tab.until) } }.distinctBy { it.path }
    return if (tabs.size == window.tabs.size) this else Pack(schema, platform, menu, PackWindow(window.title, tabs, window.selector), paths, forms, labels, since, until, native, prio, hidden)
}

// several tables of a settings window share one window named as its menu, as Static, Cache, Adlist and Forwarders of DNS do in WinBox
fun Pack.tablesWindow(): Pack? {
    val tables = window.tabs.flatMap(::tablesOf).distinctBy { it.path }.takeIf { it.size > 1 } ?: return null
    val tabs = tables.map { PackTab(it.title, it.path) }
    return Pack(schema, platform, menu, PackWindow(menu.lastOrNull() ?: window.title, tabs), paths, forms, labels, since, until, native, prio, hidden = true)
}

// from names a column of the row by its label in WinBox, and none means the name of the row; to names a field of the window by its label
@Serializable
class PackFill(val to: String, val from: String? = null)

@Serializable
class PackOpen(val by: String, val paths: Map<String, String>)

@Serializable
class PackOwner(val path: String, val by: String, val title: String)

@Serializable
class PackMonitor(val command: String, val argument: String? = null)

@Serializable
class PackAction(
    val title: String,
    val command: String,
    val selection: Boolean = false,
    val confirm: Boolean = false,
    val argument: String = "numbers",
    val output: String? = null,
    val depends: List<Map<String, String>> = emptyList(),
    val tabs: List<String> = emptyList(),
    val beside: String? = null,
    val below: String? = null,
    val row: Int = 0,
) {
    fun runs(ids: List<String>): List<Map<String, String>> = when {
        !selection -> listOf(emptyMap())
        argument == "numbers" -> listOf(mapOf(argument to ids.joinToString(",")))
        else -> ids.map { mapOf(argument to it) }
    }
}

@Serializable
class PackForm(
    val title: String? = null,
    val tabs: List<PackFormTab>,
    val only: List<PackCondition> = emptyList(),
    val confirm: List<String> = emptyList(),
    val renamed: Map<String, String> = emptyMap(),
) {
    fun gates(known: (String) -> Boolean) = only.filter { it.where.keys.all(known) }
}

fun List<PackCondition>.hides(field: String, value: (String) -> String?) = any { it.hide && field in it.fields && !it.holds(value) }

@Serializable
class PackCondition(val fields: List<String>, val where: Map<String, List<String>>, val hide: Boolean = false) {
    fun holds(value: (String) -> String?) = where.all { (property, values) -> matches(values, value(property).orEmpty()) }

    companion object {
        fun matches(values: List<String>, value: String) = values.any { wanted ->
            wanted == "*" && value.isNotEmpty() || value.split(',').any { it.equals(wanted, ignoreCase = true) }
        }
    }
}

@Serializable
class PackFormTab(
    val title: String,
    val fields: List<String>,
    val readonly: Boolean = false,
    val since: String? = null,
    val until: String? = null,
    val path: String? = null,
    val monitor: PackMonitor? = null,
    val chart: Boolean = false,
    val graph: String? = null,
    val inline: Boolean = false,
) {
    val properties: List<String> get() = fields.filter { it != GROUP_LINE }
}

const val GROUP_LINE = "-"

@Serializable
class Menu(
    @SerialName("\$schema") val schema: String? = null,
    val platform: String = "routeros",
    val items: List<MenuEntry>,
    val hidden: List<String> = emptyList(),
)

const val DEFAULT_MENU_PRIO = 500

// fill: values for fields of the window by their labels; start: the window runs its command as it opens; item: a new row of the table at path opens instead of its window
@Serializable
class MenuEntry(
    val title: String,
    val path: String? = null,
    val items: List<MenuEntry> = emptyList(),
    val requires: String? = null,
    val window: String? = null,
    val since: String? = null,
    val until: String? = null,
    val page: String? = null,
    val arguments: Map<String, String> = emptyMap(),
    val prio: Int? = null,
    val fill: Map<String, String> = emptyMap(),
    val start: Boolean = false,
    val item: Boolean = false,
)

@Serializable
class NativeMenu(val menu: Menu, val packs: List<Pack>)

private val packJson = Json

fun parsePack(text: String): Pack = packJson.decodeFromString(Pack.serializer(), text)

fun parseMenu(text: String): Menu = packJson.decodeFromString(Menu.serializer(), text)

// half a megabyte of JSON, parsed once: a pack and a menu are never changed after they are read
private val parsedPacks: Map<String, Pack> by lazy { bundledPackSources.mapValues { (_, text) -> parsePack(text) } }

private val parsedMenus: List<Menu> by lazy { bundledMenuSources.map(::parseMenu) }

fun bundledPacks(): Map<String, Pack> = parsedPacks

fun bundledMenu(platform: Platform = Platform.RouterOs): Menu = parsedMenus.single { it.platform == platform.packName }

val Platform.packName: String get() = name.lowercase()
