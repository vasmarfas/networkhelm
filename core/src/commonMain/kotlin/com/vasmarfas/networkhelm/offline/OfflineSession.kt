package com.vasmarfas.networkhelm.offline

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
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.matches
import com.vasmarfas.networkhelm.openwrt.parseUci
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.packs.packName
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

class Config(val menus: Map<MenuPath, ConfigMenu>, val facts: Map<String, String> = emptyMap())

class ConfigMenu(val kind: NodeKind, val items: List<Item>)

fun openConfig(file: String, bytes: ByteArray): OfflineSession? {
    val gzip = bytes.size > 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
    val text = if (gzip) {
        try {
            backupExport(bytes)
        } catch (e: IllegalArgumentException) {
            return null
        }
    } else {
        ApiEncoding.Utf8.decode(bytes)
    }
    val export = if (gzip) null else parseExport(text).takeIf { it.menus.isNotEmpty() }
    if (export != null) return OfflineSession(export, exportDevice(export, file), text)
    val running = if (gzip) null else keeneticConfig(text).takeIf { it.facts.isNotEmpty() }
    if (running != null) return OfflineSession(running, keeneticDevice(running, file), text)
    val uci = uciConfig(parseUci(text, file.substringBefore('.'))).takeIf { it.menus.isNotEmpty() } ?: return null
    return OfflineSession(uci, openWrtDevice(uci, file), text)
}

class OfflineSession(private val config: Config, override val device: DeviceInfo, private val text: String) : DeviceSession {
    override val capabilities = Capabilities(
        subscriptions = false,
        streamingCommands = false,
        cancel = false,
        safeMode = SafeModeKind.None,
        terminal = false,
        files = false,
        export = true,
        menuContents = false,
        offline = true,
    )
    override val safeMode: SafeMode? = null
    override val ssh: SshSession? = null

    private val settings: Set<MenuPath> by lazy {
        bundledPacks().values.filter { it.platform == device.platform.packName }
            .flatMap { pack -> pack.paths.filterValues { it.form != null && it.columns.isEmpty() }.keys }
            .map(MenuPath::parse).toSet()
    }

    override suspend fun describe(path: MenuPath): MenuSchema {
        val depth = path.segments.size
        val children = config.menus.keys.filter { it.segments.size > depth && it.segments.take(depth) == path.segments }.map { it.segments[depth] }.distinct()
        val menu = config.menus[path]
        return when {
            menu != null -> MenuSchema(path, if (path in settings && menu.items.size <= 1) NodeKind.Settings else menu.kind, children, listOf("print"), arguments(menu.items))
            children.isNotEmpty() -> MenuSchema(path, NodeKind.Directory, children, emptyList(), emptyList())
            else -> MenuSchema(path, if (path in settings) NodeKind.Settings else NodeKind.List, emptyList(), listOf("print"), emptyList())
        }
    }

    override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> = flowOf(ListEvent.Snapshot(items(path, filter)))

    override suspend fun get(path: MenuPath, id: String?, properties: List<String>?): Item? = items(path, null).firstOrNull { id == null || it.id == id }

    override suspend fun count(path: MenuPath, filter: Filter?): Int = items(path, filter).size

    override suspend fun add(path: MenuPath, values: Map<String, String>): String = throw readOnly()

    override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) {
        throw readOnly()
    }

    override suspend fun remove(path: MenuPath, ids: List<String>) {
        throw readOnly()
    }

    override suspend fun move(path: MenuPath, ids: List<String>, before: String?) {
        throw readOnly()
    }

    override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> = emptyFlow()

    override suspend fun export(path: MenuPath): String = text

    override fun close() {}

    private fun items(path: MenuPath, filter: Filter?) = config.menus[path]?.items.orEmpty().filter { filter?.matches(it) != false }

    private fun arguments(items: List<Item>): List<Argument> = items.flatMap { it.values.keys }.distinct().map { name ->
        val values = items.mapNotNull { it[name] }
        Argument(name, if (values.all { it == "true" || it == "false" }) ValueType.YesNo else ValueType.Text())
    }

    private fun readOnly() = DeviceException(ErrorCategory.Unsupported, "a configuration from a file is read only")
}
