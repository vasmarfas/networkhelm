package com.vasmarfas.networkhelm.routeros

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.packs.NATIVE_PATHS
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.packs.PackNativeField
import com.vasmarfas.networkhelm.packs.PackNativeRef
import com.vasmarfas.networkhelm.packs.PackWindow
import com.vasmarfas.networkhelm.session.Backoff
import com.vasmarfas.networkhelm.session.NativeObjects
import com.vasmarfas.networkhelm.session.NativeRun
import com.vasmarfas.networkhelm.session.NativeSide
import com.vasmarfas.networkhelm.session.QUERY_END
import com.vasmarfas.networkhelm.session.QUERY_PASS
import com.vasmarfas.networkhelm.session.RouterFacts
import com.vasmarfas.networkhelm.session.keepsWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.TimeSource

private const val ROWS = 500

private const val EXTRAS_EVERY = 3
private const val REOPEN_EVERY = 30
private const val DROPS = 2
private const val LINES_RETRIES = 3
private const val PROBES = 6
private const val QUERY_LEAST_MILLIS = 100L
private const val LOG_LEAST = 1_000
private const val MIRROR_TRIES = 3

internal class NativeWindows(
    private val side: NativeSide?,
    private val port: suspend () -> Int?,
    private val every: Duration,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    @Volatile
    private var specs: Map<String, PackNative> = emptyMap()
    private val lock = Mutex()
    private val reading = Mutex()
    private val backoff = Backoff(time)
    private var opened: NativeObjects? = null

    @Volatile
    private var closed = false
    private val drops = HashMap<List<Long>, Int>()
    private val refused = HashMap<List<Long>, DeviceException>()

    suspend fun usable() = side != null && port() != null

    suspend fun facts(): RouterFacts? = reading.withLock {
        try {
            objects().facts()
        } catch (e: DeviceException) {
            null
        }
    }

    fun keep(packs: List<Pack>) {
        specs = packs.flatMap { pack -> pack.paths.mapNotNull { (path, shown) -> shown.native?.let { path to it } } }.toMap()
    }

    // the descriptions the router serves hold windows of hardware and services it does not have; the object model says which by refusing them or by staying silent
    suspend fun alive(packs: List<Pack>): List<Pack> {
        val verdicts = verdicts(packs.flatMap { pack -> pack.window.tabs.mapNotNull { tab -> pack.paths[tab.path]?.native?.takeIf { stands(MenuPath.parse(tab.path)) } } })
        val standing = packs.mapNotNull { pack ->
            val tabs = pack.window.tabs.filter { tab -> pack.paths[tab.path]?.native?.let { spec -> verdicts[verdict(spec)] != false } ?: true }
            when {
                tabs.size == pack.window.tabs.size -> pack
                tabs.isEmpty() -> null
                else -> Pack(pack.schema, pack.platform, pack.menu, PackWindow(pack.window.title, tabs, pack.window.selector), pack.paths, pack.forms, pack.labels, pack.since, pack.until, pack.native, pack.prio, pack.hidden)
            }
        }
        keep(standing)
        return standing
    }

    private class Probe(val stands: Boolean, val silent: Boolean = false, val lost: DeviceException? = null)

    // the windows of one pass are asked about a few at a time, so that handlers which stay silent cost their waiting once and not each in turn
    // a handler may hold a table and queries, and the router refuses one command of it and not another
    private fun verdict(spec: PackNative) = spec.handler to spec.query?.poll

    private suspend fun verdicts(specs: List<PackNative>): Map<Pair<List<Long>, Long?>, Boolean> = reading.withLock {
        val objects = try {
            objects()
        } catch (e: DeviceException) {
            return@withLock specs.associate { verdict(it) to e.keepsWindow() }
        }
        val gate = Semaphore(PROBES)
        val probes = coroutineScope { specs.distinctBy(::verdict).map { spec -> async { spec to gate.withPermit { probe(objects, spec) } } }.awaitAll() }
        for ((spec, probe) in probes) {
            if (probe.silent) refused[spec.handler] = DeviceException(ErrorCategory.Unsupported, "the router does not answer [${spec.handler.joinToString(",")}]")
            probe.lost?.let { lost(objects, spec, it) }
        }
        probes.associate { (spec, probe) -> verdict(spec) to probe.stands }
    }

    private suspend fun probe(objects: NativeObjects, spec: PackNative): Probe {
        if (spec.handler in refused) return Probe(false)
        return try {
            objects.answers(spec).let { Probe(it, silent = !it) }
        } catch (e: DeviceException) {
            Probe(e.keepsWindow(), lost = e.takeIf { it.category == ErrorCategory.ConnectionLost })
        }
    }

    fun spec(path: MenuPath): PackNative? = specs[path.toString()]

    // a window of a query stands under the path of the console command it replaces
    fun stands(path: MenuPath) = path.segments.firstOrNull() == NATIVE_PATHS || specs[path.toString()]?.query != null

    suspend fun schema(path: MenuPath, spec: PackNative, lists: Boolean = false): MenuSchema {
        spec.query?.let { query ->
            val rows = if (lists) listsOf(query.request) else null
            return MenuSchema(
                path, NodeKind.Command, emptyList(), emptyList(),
                query.request.map { argument(it, true, rows?.get(it.name).orEmpty(), rows != null).copy(optional = it.flag != null || it.name in query.optional) },
                listens = false,
            )
        }
        val commands = listOfNotNull("set".takeIf { spec.edit }, "add".takeIf { spec.add }, "remove".takeIf { spec.remove }, "move".takeIf { spec.move }) + spec.doits.map(Int::toString)
        return MenuSchema(path, if (spec.single) NodeKind.Settings else NodeKind.List, emptyList(), commands, spec.fields.map { argument(it, spec.edit) }, listens = false)
    }

    // the dropdowns of a request: the rows of the table a field refers to, read once when the window opens; a table that cannot be read leaves the field a line of text
    private suspend fun listsOf(fields: List<PackNativeField>): Map<String, Map<Long, String>> {
        val refs = fields.associate { field ->
            field.name to (if (field.kind == "addr") null else (if (field.kind == "multi") field.parts else listOf(field) + field.members).firstNotNullOfOrNull { it.refers })
        }
        val read = HashMap<PackNativeRef, Map<Long, String>>()
        for (ref in refs.values.filterNotNull().distinct()) {
            read[ref] = try {
                written { it.names(ref) }
            } catch (e: DeviceException) {
                emptyMap()
            }
        }
        return refs.mapNotNull { (name, ref) -> ref?.let { read[it] }?.takeIf { it.isNotEmpty() }?.let { name to it } }.toMap()
    }

    private fun argument(field: PackNativeField, edit: Boolean, rows: Map<Long, String> = emptyMap(), asked: Boolean = false): Argument {
        val shape = if (field.kind == "union") field.members.first() else if (asked && field.kind == "multi") field.parts.single() else field
        // a name of nothing is the state of a field the router has not set, no choice to make
        val choices = (shape.names.entries.sortedBy { it.key }.map { it.value } + rows.entries.sortedBy { it.key }.map { it.value }).filter { it.isNotEmpty() }.distinct()
        val type = when {
            asked && (field.kind == "multi" || field.kind == "multinumber") && choices.isNotEmpty() -> ValueType.Enumeration(multiple = true, closed = true)
            field.kind == "union" && choices.isNotEmpty() -> ValueType.Enumeration()
            field.kind == "enm" && choices.isNotEmpty() -> ValueType.Enumeration(closed = true)
            field.kind == "set" && choices.isNotEmpty() -> ValueType.Enumeration(multiple = true, closed = true)
            field.kind == "bool" || field.kind == "flag" -> ValueType.YesNo
            field.kind == "interval" -> ValueType.Time
            field.kind == "ipaddr" || field.kind == "netmask" -> ValueType.Ip4
            field.kind == "ip6addr" -> ValueType.Ip6
            field.kind == "macaddr" -> ValueType.Mac
            field.kind == "number" || field.kind == "integer" || field.kind == "bignumber" || field.kind == "bytes" || field.kind == "bigbytes" || field.kind == "kbytes" -> ValueType.Integer()
            field.kind == "fixedpoint" || field.kind == "decimal" || field.kind == "bigdecimal" -> ValueType.Decimal()
            else -> ValueType.Text()
        }
        val numbered = field.kind == "union" && type is ValueType.Enumeration && field.members.size > 1 && field.members.drop(1).all { it.kind == "number" || it.kind == "integer" }
        return Argument(field.name, type, choices = if (type is ValueType.Enumeration) choices else emptyList(), readOnly = !edit || !field.writable, negatable = field.negation != null, optional = field.flag != null, depends = field.depends, editable = numbered, labels = rows.values.associateWith { it })
    }

    suspend fun change(spec: PackNative, id: String?, values: Map<String, String?>) = written { it.change(spec, id, values) }

    suspend fun create(spec: PackNative, values: Map<String, String>): String = written { it.create(spec, values) }

    suspend fun delete(spec: PackNative, ids: List<String>) = written { objects -> ids.forEach { objects.delete(spec, it) } }

    suspend fun move(spec: PackNative, ids: List<String>, before: String?) = written { objects -> ids.forEach { objects.move(spec, it, before) } }

    suspend fun act(spec: PackNative, command: String, id: String?) =
        written { it.act(spec, command.toIntOrNull() ?: throw DeviceException(ErrorCategory.ArgumentValue, "$command is no command of the window"), id) }

    // the window of a query or an action runs while it is collected: the start, a read every refresh of the window, and the cancel when the window stops or the router says it is done
    fun query(spec: PackNative, values: Map<String, String>): Flow<Item> = flow {
        val query = spec.query ?: throw DeviceException(ErrorCategory.MissingItem, "no query at [${spec.handler.joinToString(",")}]")
        var run: NativeRun? = null
        var channel: NativeObjects? = null
        try {
            // a start that is waited for to the end gives the run that the cancel is sent to, whenever the window is stopped
            try {
                withContext(NonCancellable) {
                    val begin: suspend () -> NativeRun = { counted(spec) { objects -> objects.start(spec, values).also { run = it; channel = objects } } }
                    try {
                        begin()
                    } catch (e: DeviceException) {
                        // a channel the router closed while nothing was asked fails a start that has made no run yet, so the start is made once more on a new channel
                        if (e.category != ErrorCategory.ConnectionLost) throw e
                        begin()
                    }
                }
            } catch (e: DeviceException) {
                if (e.category == ErrorCategory.Unsupported && query.fallback != null) specs = specs.filterValues { it !== spec }
                throw e
            }
            val started = run ?: return@flow
            var pass = 0
            var shown: List<Item>? = null
            while (true) {
                val read = counted(spec, channel) { started.read() }
                if (!read.snapshot) {
                    read.rows.forEach { emit(it) }
                } else if (read.rows != shown) {
                    pass++
                    read.rows.forEach { emit(it.copy(values = it.values + (QUERY_PASS to pass.toString()))) }
                    emit(Item(QUERY_END, mapOf(QUERY_PASS to pass.toString())))
                    shown = read.rows
                }
                if (read.finished) return@flow
                delay(maxOf(query.refresh, QUERY_LEAST_MILLIS))
            }
        } finally {
            val started = run
            if (started != null) {
                withContext(NonCancellable) {
                    try {
                        reading.withLock { started.cancel() }
                    } catch (e: DeviceException) {
                        // a channel that dropped took the query with it
                    }
                }
            }
        }
    }

    // a write waits for the read that is on the channel; a channel that drops under it is let go, and whether the write reached the router stays unknown
    private suspend fun <T> written(action: suspend (NativeObjects) -> T): T = reading.withLock {
        val objects = objects()
        try {
            action(objects)
        } catch (e: DeviceException) {
            if (e.category == ErrorCategory.ConnectionLost) dropped(objects, e)
            throw e
        }
    }

    suspend fun count(spec: PackNative): Int = read(spec, null).orEmpty().size

    // a console menu read from its window of the WinBox port as a subscription gets it, page by page, or by getall where the router
    // does not send it that way; a read the table changed under is made again, and null is a channel that cannot give it now
    suspend fun mirrored(spec: PackNative, expected: Int, page: suspend (List<Item>) -> Unit): List<Item>? {
        repeat(MIRROR_TRIES) {
            try {
                return counted(spec) { objects -> objects.dump(spec, expected, page) ?: objects.read(spec, null) }
            } catch (e: DeviceException) {
                if (e.category != ErrorCategory.MissingItem) return null
            }
        }
        return null
    }

    suspend fun item(spec: PackNative, id: String?): Item? = read(spec, null).orEmpty().firstOrNull { it.id == id }

    // a read takes at most a tenth of the time, as a list of the console does
    fun list(spec: PackNative): Flow<ListEvent> = flow {
        var shown: List<Item>? = null
        while (true) {
            val started = time.markNow()
            val items = read(spec, null).orEmpty()
            if (items != shown) emit(ListEvent.Snapshot(items))
            shown = items
            delay(maxOf(every, started.elapsedNow() * 9))
        }
    }

    // the lines of a log are read from the object model, the whole buffer once and then the lines added; when the object model fails, or has no lines where the console has some, the console reads the log
    fun lines(spec: PackNative, console: Flow<ListEvent>, held: suspend () -> Int): Flow<ListEvent> = flow {
        var run: NativeRun? = null
        var channel: NativeObjects? = null
        var kept = emptyList<Item>()
        var keep = LOG_LEAST
        var first = true
        var retries = 0
        while (true) {
            val read = try {
                val reading = run ?: counted(spec) { objects -> objects.lines(spec).also { channel = objects } }.also { run = it }
                counted(spec, channel) { reading.read() }.also {
                    if (first && it.rows.isEmpty() && held() > 0) throw DeviceException(ErrorCategory.Unsupported, "the object model has no lines where the console has some")
                }
            } catch (e: DeviceException) {
                // a channel that dropped under a read is opened again a few times, a log the object model cannot give, or a channel that cannot be opened, is left to the console
                if (e.category != ErrorCategory.ConnectionLost || run == null || ++retries > LINES_RETRIES) break
                run = null
                delay(every)
                continue
            }
            retries = 0
            if (read.snapshot) kept = read.rows else if (read.rows.isNotEmpty()) kept += read.rows
            if (first) keep = maxOf(keep, kept.size)
            if (kept.size > keep) kept = kept.takeLast(keep)
            if (first || read.snapshot || read.rows.isNotEmpty()) emit(ListEvent.Snapshot(kept))
            first = false
            delay(every)
        }
        emitAll(console)
    }

    fun joined(console: Flow<ListEvent>, spec: PackNative): Flow<ListEvent> = channelFlow {
        val shared = Mutex()
        val rows = LinkedHashMap<String, Item>()
        var extras = emptyMap<String, Map<String, String>>()
        // the console prints the one record of a window of settings without the id the object model gives it
        fun extra(id: String, among: Map<String, Map<String, String>>) = if (spec.single) among.values.firstOrNull() else among[id]
        fun merged(item: Item) = extra(item.id, extras)?.let { item.copy(values = it + item.values) } ?: item
        val reader = launch {
            while (true) {
                val read = try {
                    read(spec, ROWS) ?: return@launch
                } catch (e: DeviceException) {
                    if (e.category != ErrorCategory.ConnectionLost) return@launch
                    delay(every * REOPEN_EVERY)
                    continue
                }
                shared.withLock {
                    val fresh = read.associate { it.id to it.values }
                    val changed = rows.values.filter { extra(it.id, fresh) != extra(it.id, extras) }
                    extras = fresh
                    changed.forEach { send(ListEvent.Upsert(merged(it))) }
                }
                delay(every * EXTRAS_EVERY)
            }
        }
        try {
            console.collect { event ->
                shared.withLock {
                    when (event) {
                        is ListEvent.Snapshot -> {
                            rows.clear()
                            event.items.forEach { rows[it.id] = it }
                            send(ListEvent.Snapshot(event.items.map(::merged)))
                        }
                        is ListEvent.Upsert -> {
                            rows[event.item.id] = event.item
                            send(ListEvent.Upsert(merged(event.item), event.next))
                        }
                        is ListEvent.Removed -> {
                            rows.remove(event.id)
                            send(event)
                        }
                        is ListEvent.Progress -> send(event)
                    }
                }
            }
        } finally {
            reader.cancel()
        }
    }

    suspend fun joined(item: Item, spec: PackNative): Item = try {
        read(spec, ROWS)?.firstOrNull { spec.single || it.id == item.id }?.let { item.copy(values = it.values + item.values) } ?: item
    } catch (e: DeviceException) {
        item
    }

    fun close() {
        closed = true
        opened?.close()
        opened = null
    }

    private suspend fun read(spec: PackNative, limit: Int?): List<Item>? = counted(spec) { objects -> objects.read(spec, limit) }

    // one read at a time, so a dropped channel is charged to the read that was on it; a run lives on the channel that started it and does not go on a new one
    private suspend fun <T> counted(spec: PackNative, owner: NativeObjects? = null, action: suspend (NativeObjects) -> T): T = reading.withLock {
        refused[spec.handler]?.let { throw it }
        val objects = if (owner == null) objects() else lock.withLock { opened }?.takeIf { it === owner } ?: throw DeviceException(ErrorCategory.ConnectionLost, "the channel of the window dropped")
        try {
            action(objects).also { drops.remove(spec.handler) }
        } catch (e: DeviceException) {
            if (e.category == ErrorCategory.ConnectionLost) lost(objects, spec, e)
            throw e
        }
    }

    private suspend fun lost(objects: NativeObjects, spec: PackNative, cause: DeviceException) {
        val times = (drops[spec.handler] ?: 0) + 1
        drops[spec.handler] = times
        if (times >= DROPS) refused[spec.handler] = DeviceException(ErrorCategory.Unsupported, "the router drops the channel every time [${spec.handler.joinToString(",")}] is read", cause)
        dropped(objects, cause)
    }

    private suspend fun dropped(objects: NativeObjects, cause: DeviceException) = lock.withLock {
        if (opened === objects) {
            opened = null
            objects.close()
            backoff.lost(cause)
        }
    }

    private suspend fun objects(): NativeObjects = lock.withLock {
        opened ?: run {
            if (closed) throw gone()
            val fresh = backoff.opening(::open)
            if (closed) {
                fresh.close()
                throw gone()
            }
            fresh.also { opened = it }
        }
    }

    private fun gone() = DeviceException(ErrorCategory.ConnectionLost, "the session is closed")

    private suspend fun open(): NativeObjects {
        val side = side ?: throw DeviceException(ErrorCategory.Unsupported, "this connection has no native channel")
        val port = port() ?: throw DeviceException(ErrorCategory.Unsupported, "the WinBox service of the router is off")
        return try {
            side.open(port)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeviceException) {
            throw e
        } catch (e: Exception) {
            // a firewall closes the port of a service the router keeps on, and for the windows that is the service being off
            throw DeviceException(ErrorCategory.Unsupported, "the WinBox port of the router does not answer", e)
        }
    }
}
