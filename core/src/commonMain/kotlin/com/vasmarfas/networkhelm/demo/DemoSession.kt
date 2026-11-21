package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.matches
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.packs.packName
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

typealias Live = (row: Item, tick: Int) -> Map<String, String>

internal class DemoMenu(
    val kind: NodeKind,
    val rows: List<Item>,
    val fields: List<Argument>,
    val writable: Boolean,
    val ordered: Boolean,
    val live: Live?,
    val command: ((Map<String, String>) -> Flow<Item>)?,
)

private val packsOfPlatform: Map<String, List<Pack>> by lazy { bundledPacks().values.groupBy { it.platform } }

private val yesNo = setOf("true", "false", "yes", "no")

class DemoSession internal constructor(
    override val device: DeviceInfo,
    override val capabilities: Capabilities,
    private val menus: Map<MenuPath, DemoMenu>,
    private val ids: (Int) -> String,
) : DeviceSession {
    private val lists = MutableStateFlow(menus.mapValues { (_, menu) -> menu.rows })
    private var created = menus.values.sumOf { it.rows.size }
    private val held = MutableStateFlow(false)
    private val edits = MutableStateFlow(0)
    private var saved: Map<MenuPath, List<Item>> = emptyMap()

    override val safeMode: SafeMode? = if (capabilities.safeMode == SafeModeKind.None) null else Hold()
    override val ssh: SshSession? = null

    private val shown: Map<MenuPath, Pair<PackPath, Pack>> by lazy {
        val found = LinkedHashMap<MenuPath, Pair<PackPath, Pack>>()
        for (pack in packsOfPlatform[device.platform.packName].orEmpty()) for ((path, info) in pack.paths) found.getOrPut(MenuPath.parse(path)) { info to pack }
        found
    }

    override suspend fun describe(path: MenuPath): MenuSchema {
        val depth = path.segments.size
        val below = menus.keys.filter { it.segments.size > depth && it.segments.take(depth) == path.segments }
        val commands = below.filter { it.segments.size == depth + 1 && menus.getValue(it).kind == NodeKind.Command }.map { it.segments[depth] }
        val children = below.filterNot { it.segments.size == depth + 1 && menus.getValue(it).kind == NodeKind.Command }.map { it.segments[depth] }.distinct()
        val menu = menus[path]
        return when {
            menu == null && below.isEmpty() -> throw DeviceException(ErrorCategory.MissingItem, "no such menu: $path")
            menu == null -> MenuSchema(path, NodeKind.Directory, children, commands, emptyList())
            else -> MenuSchema(path, menu.kind, children, (own(menu) + commands + actions(path)).distinct(), arguments(path, menu))
        }
    }

    override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> = flow {
        val menu = menu(path)
        val live = menu.live
        if (live != null) {
            var tick = 0
            while (true) {
                val rows = lists.value[path].orEmpty().let { all -> if (menu.kind == NodeKind.Settings) all.take(1) else all.filter { filter?.matches(it) != false } }
                emit(ListEvent.Snapshot(rows.map { Item(it.id, it.values + live(it, tick)) }))
                tick++
                delay(1000)
            }
        }
        emitAll(lists.map { all -> all[path].orEmpty().filter { filter?.matches(it) != false } }.distinctUntilChanged().map { ListEvent.Snapshot(it) })
    }

    override suspend fun get(path: MenuPath, id: String?, properties: List<String>?): Item? {
        menu(path)
        return lists.value[path].orEmpty().firstOrNull { id == null || it.id == id }
    }

    override suspend fun count(path: MenuPath, filter: Filter?): Int {
        menu(path)
        return lists.value[path].orEmpty().count { filter?.matches(it) != false }
    }

    override suspend fun add(path: MenuPath, values: Map<String, String>): String {
        writable(path)
        val id = ids(++created)
        change(path) { it + Item(id, values) }
        return id
    }

    override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) {
        writable(path)
        val rows = lists.value[path].orEmpty()
        if (rows.none { id == null || it.id == id }) throw DeviceException(ErrorCategory.MissingItem, "no such item: $id")
        change(path) { all ->
            all.map { row ->
                if (id != null && row.id != id) return@map row
                val merged = row.values.toMutableMap()
                for ((name, value) in changes) if (value == null) merged.remove(name) else merged[name] = value
                Item(row.id, merged)
            }
        }
    }

    override suspend fun remove(path: MenuPath, ids: List<String>) {
        writable(path)
        change(path) { all -> all.filterNot { it.id in ids } }
    }

    override suspend fun move(path: MenuPath, ids: List<String>, before: String?) {
        if (!writable(path).ordered) throw DeviceException(ErrorCategory.Unsupported, "$path keeps no order")
        change(path) { all ->
            val moving = all.filter { it.id in ids }
            val rest = all.filterNot { it.id in ids }
            val at = if (before == null) rest.size else rest.indexOfFirst { it.id == before }.takeIf { it >= 0 } ?: throw DeviceException(ErrorCategory.MissingItem, "no such item: $before")
            rest.take(at) + moving + rest.drop(at)
        }
    }

    override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> {
        menus[path.child(command)]?.command?.let { return it(arguments) }
        return when (command) {
            "reboot", "shutdown", "reset-configuration" -> flow { throw DeviceException(ErrorCategory.General, "this is a demo router: it restarts nothing and keeps nothing") }
            else -> emptyFlow()
        }
    }

    override suspend fun export(path: MenuPath): String = buildString {
        appendLine("# demo export of ${device.identity}, RouterOS ${device.version}")
        for ((menuPath, menu) in menus) {
            if (menu.kind == NodeKind.Command || !menu.writable || path.segments.size > menuPath.segments.size || menuPath.segments.take(path.segments.size) != path.segments) continue
            val skipped = shown[menuPath]?.first?.readonly.orEmpty()
            val rows = lists.value[menuPath].orEmpty().filter { it["dynamic"] != "true" }
            if (rows.isEmpty()) continue
            appendLine(menuPath.cli)
            for (row in rows) {
                val fields = row.values.filterKeys { it !in skipped && !it.startsWith(".") }.entries.joinToString(" ") { (name, value) -> "$name=" + if (' ' in value || value.isEmpty()) "\"$value\"" else value }
                appendLine((if (menu.kind == NodeKind.Settings) "set " else "add ") + fields)
            }
        }
    }

    override fun counters(path: MenuPath, properties: List<String>, filter: Filter?): Flow<Map<String, Map<String, String>>> {
        val live = menus[path]?.live ?: return emptyFlow()
        return flow {
            var tick = 0
            while (true) {
                val rows = lists.value[path].orEmpty().filter { filter?.matches(it) != false }
                emit(rows.associate { row -> row.id to live(row, tick).filterKeys { it in properties } })
                tick++
                delay(1000)
            }
        }
    }

    override fun close() {}

    private fun menu(path: MenuPath): DemoMenu = menus[path] ?: throw DeviceException(ErrorCategory.MissingItem, "no such menu: $path")

    private fun writable(path: MenuPath): DemoMenu = menu(path).also {
        if (!it.writable) throw DeviceException(ErrorCategory.PermissionDenied, "$path of a demo router is read only")
    }

    private fun change(path: MenuPath, edit: (List<Item>) -> List<Item>) {
        lists.update { all -> all + (path to edit(all[path].orEmpty())) }
        if (held.value) edits.update { it + 1 }
    }

    // the buttons of a RouterOS or Linux pack are shown; a command the demo does not define does nothing
    private fun actions(path: MenuPath): List<String> =
        if (device.platform == Platform.RouterOs || device.platform == Platform.Ssh) shown[path]?.first?.actions.orEmpty().map { it.command } else emptyList()

    private fun own(menu: DemoMenu): List<String> = when (menu.kind) {
        NodeKind.List -> listOf("print") + if (menu.writable) listOf("add", "set", "remove") + listOfNotNull("move".takeIf { menu.ordered }) else emptyList()
        NodeKind.Settings -> if (menu.writable) listOf("print", "set") else listOf("print")
        else -> emptyList()
    }

    private fun arguments(path: MenuPath, menu: DemoMenu): List<Argument> {
        val (info, pack) = shown[path] ?: (null to null)
        val form = info?.form?.let { pack?.forms?.get(it) }?.tabs?.flatMap { it.fields }.orEmpty()
        val names = (menu.fields.map { it.name } + info?.columns.orEmpty() + form + menu.rows.flatMap { it.values.keys })
            .filter { it != "-" && it != ".id" }.distinct()
        return names.map { name ->
            menu.fields.firstOrNull { it.name == name } ?: Argument(name, kindOf(name, menu.rows), readOnly = !menu.writable || name in info?.readonly.orEmpty())
        }
    }

    private fun kindOf(name: String, rows: List<Item>): ValueType {
        val values = rows.mapNotNull { it[name] }
        return if (name == "disabled" || values.isNotEmpty() && values.all { it in yesNo }) ValueType.YesNo else ValueType.Text()
    }

    private inner class Hold : SafeMode {
        override val active: Flow<Boolean> = held.asStateFlow()
        override val actions: Flow<Int> = edits.asStateFlow()

        override suspend fun enter(): Boolean {
            if (!held.value) {
                saved = lists.value
                edits.value = 0
                held.value = true
            }
            return true
        }

        override suspend fun commit() {
            held.value = false
            edits.value = 0
        }

        override suspend fun rollback() {
            if (held.value) lists.value = saved
            commit()
        }
    }
}
