package com.vasmarfas.networkhelm

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.packs.NativeMenu
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.session.TextStore
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

class FakeRouter(
    private val rows: Map<String, List<Item>> = emptyMap(),
    id: String = "rd-test-id",
    private val schemas: Map<String, MenuSchema> = emptyMap(),
    private val output: Map<String, List<Item>> = emptyMap(),
    version: String = "7.26",
    override val safeMode: SafeMode? = null,
    override val ssh: SshSession? = null,
    encrypted: Boolean = true,
    policies: Set<String> = setOf("read", "write", "ftp", "policy", "reboot", "password", "sensitive", "sniff", "test"),
    deviceMode: Map<String, String> = emptyMap(),
    platform: Platform = Platform.RouterOs,
    private val native: NativeMenu? = null,
    private val made: List<Pack> = emptyList(),
    private val endless: Boolean = false,
    private val lacking: Set<String> = emptySet(),
    fileTexts: Map<String, String> = emptyMap(),
    override val fileChannel: SftpClient? = null,
    private val settings: Map<String, Item> = emptyMap(),
    private val refused: Set<String> = emptySet(),
    private val gone: Set<String> = emptySet(),
    packages: Set<String> = setOf("routeros"),
    private val words: Map<String, String> = emptyMap(),
) : DeviceSession {
    val ran = mutableListOf<Pair<String, Map<String, String>>>()
    val counted = MutableSharedFlow<Map<String, Map<String, String>>>(replay = 1)
    val polled = mutableListOf<Triple<String, List<String>, Filter?>>()
    override val link = MutableStateFlow(LinkState.Up)
    val added = mutableListOf<Map<String, String>>()
    val removed = mutableListOf<List<String>>()
    val changed = mutableListOf<Pair<String?, Map<String, String?>>>()
    val moved = mutableListOf<Pair<List<String>, String?>>()
    val fileText = fileTexts.toMutableMap()

    override val device = DeviceInfo(platform, "rd-test", version, "x86_64", "CHR", packages, policies, deviceMode, id = id)
    override val capabilities = Capabilities(true, true, true, SafeModeKind.ApiSession, terminal = false, files = false, export = true, encrypted = encrypted, nativeMenu = native != null)

    override suspend fun nativeMenu() = native

    override suspend fun packs(store: TextStore?) = made

    override suspend fun words(store: TextStore?) = words

    override suspend fun describe(path: MenuPath): MenuSchema {
        if (path.toString() in lacking) throw DeviceException(ErrorCategory.MissingItem, "no such command prefix")
        return schemas[path.toString()]
            ?: MenuSchema(path, NodeKind.List, emptyList(), listOf("add", "print", "remove", "set"), listOf(Argument("disabled", ValueType.YesNo)))
    }

    override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> =
        flowOf(ListEvent.Snapshot(rows[path.toString()].orEmpty().filter { filter == null || it.matches(filter) }))

    private fun Item.matches(filter: Filter): Boolean = when (filter) {
        is Filter.Equals -> (if (filter.property == ".id") id else this[filter.property]) == filter.value
        is Filter.Or -> filter.filters.any { matches(it) }
        is Filter.And -> filter.filters.all { matches(it) }
        is Filter.Not -> !matches(filter.filter)
        else -> error("$filter is not understood")
    }

    override suspend fun get(path: MenuPath, id: String?, properties: List<String>?): Item? =
        if (id == null) settings[path.toString()] else fileText[id]?.let { Item(id, mapOf("contents" to it)) }
    override suspend fun count(path: MenuPath, filter: Filter?) = rows[path.toString()].orEmpty().size
    override suspend fun add(path: MenuPath, values: Map<String, String>): String {
        added += values
        return "*99"
    }
    override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) {
        changed += id to changes
        if (id != null) changes["contents"]?.let { fileText[id] = it }
    }

    override suspend fun remove(path: MenuPath, ids: List<String>) {
        removed += ids
    }

    override suspend fun move(path: MenuPath, ids: List<String>, before: String?) {
        moved += ids to before
    }
    override fun nativeStands(path: MenuPath) = path.toString() !in gone

    override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> {
        val full = path.child(command).toString()
        ran += full to arguments
        if (full in refused) return flow { throw DeviceException(ErrorCategory.Unsupported, "the router does not implement this request") }
        return flow {
            emitAll(output[full].orEmpty().asFlow())
            if (endless) awaitCancellation()
        }
    }
    override suspend fun export(path: MenuPath) = ""
    override fun counters(path: MenuPath, properties: List<String>, filter: Filter?): Flow<Map<String, Map<String, String>>> {
        polled += Triple(path.toString(), properties, filter)
        return counted
    }
    override fun close() = Unit
}
