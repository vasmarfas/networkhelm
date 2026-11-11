package com.vasmarfas.networkhelm.routeros

import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.LINK_LOST
import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.openwrt.RequestOutcomeUnknownException
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.packs.bundledMenu
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.packs.packName
import com.vasmarfas.networkhelm.routeros.api.ApiConnectionClosedException
import com.vasmarfas.networkhelm.routeros.api.ApiTrapException
import com.vasmarfas.networkhelm.routeros.api.RouterOsClient
import com.vasmarfas.networkhelm.routeros.api.RouterOsLink
import com.vasmarfas.networkhelm.routeros.api.apiQuery
import com.vasmarfas.networkhelm.routeros.api.call
import com.vasmarfas.networkhelm.routeros.api.gone
import com.vasmarfas.networkhelm.routeros.console.ConsoleClient
import com.vasmarfas.networkhelm.routeros.console.Dialect
import com.vasmarfas.networkhelm.routeros.schema.InspectSchema
import com.vasmarfas.networkhelm.routeros.schema.MemorySchemaCache
import com.vasmarfas.networkhelm.routeros.schema.PrintSchema
import com.vasmarfas.networkhelm.routeros.schema.SchemaCache
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.NativeSide
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.session.TextStore
import com.vasmarfas.networkhelm.session.rounds
import com.vasmarfas.networkhelm.ssh.SshService
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.uidefs.UiMembers
import com.vasmarfas.networkhelm.uidefs.UiSchema
import com.vasmarfas.networkhelm.uidefs.WebAccess
import com.vasmarfas.networkhelm.uidefs.decodeUiMirrors
import com.vasmarfas.networkhelm.uidefs.decodeUiPacks
import com.vasmarfas.networkhelm.uidefs.encodeUiMirrors
import com.vasmarfas.networkhelm.uidefs.encodeUiPacks
import com.vasmarfas.networkhelm.uidefs.files
import com.vasmarfas.networkhelm.uidefs.loadUiEntries
import com.vasmarfas.networkhelm.uidefs.loadUiList
import com.vasmarfas.networkhelm.uidefs.nativeUiFiles
import com.vasmarfas.networkhelm.uidefs.routerFirst
import com.vasmarfas.networkhelm.uidefs.uiInheritedMirrors
import com.vasmarfas.networkhelm.uidefs.uiKnownPaths
import com.vasmarfas.networkhelm.uidefs.uiPacks
import com.vasmarfas.networkhelm.uidefs.uiQueries
import com.vasmarfas.networkhelm.uidefs.uiState
import com.vasmarfas.networkhelm.uidefs.withQueries
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class RouterOsSession(
    private val client: RouterOsClient,
    override val device: DeviceInfo,
    override val capabilities: Capabilities,
    override val ssh: SshSession? = null,
    schemaCache: SchemaCache = MemorySchemaCache(),
    private val pollInterval: Duration = 1.seconds,
    private val scope: CoroutineScope? = null,
    private val web: WebAccess? = null,
    native: NativeSide? = null,
) : DeviceSession {

    private val nativeWindows = NativeWindows(native, ::nativePort, pollInterval)

    // the menus whose window of the WinBox port the console disagreed with, the fields each one was checked on,
    // and those whose hidden rows the console was seen to hide
    private val mirrorsRefused = MutableStateFlow(emptySet<String>())
    private val mirrorsChecked = MutableStateFlow(emptyMap<String, Set<String>>())
    private val mirrorsHiding = MutableStateFlow(emptySet<String>())
    // the rows each menu had at its last whole read, so a small table is not counted by the console before each read
    private val sizes = MutableStateFlow(emptyMap<String, Int>())

    private val build = run {
        val mode = device.deviceMode.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        "${device.version} ${device.architecture} ${device.packages.sorted().joinToString(",")} ${device.policies.sorted().joinToString(",")} $mode"
    }
    private val inspect = if (capabilities.menuContents) InspectSchema(client, schemaCache, "${device.id.orEmpty()} $build", scope) else null

    // the routers of one build share what their consoles were seen to print, so a menu checked on one is read from its window on the others
    @Volatile
    private var release: TextStore? = null
    @Volatile
    private var released = emptyMap<String, List<Map<String, String>>>()
    private val releaseName = "mirrors-${build.hashCode().toUInt().toString(16)}.json"
    private val releasing = Mutex()

    private val mirrors = (client as? ConsoleClient)?.let { console ->
        MenuMirrors(nativeWindows, console.dialect == Dialect.Classic, { key -> inspect?.recalled(key) ?: released[key] }) { key, rows ->
            inspect?.remember(key, rows)
            release?.let { store -> releasing.withLock { store.write(releaseName, encodeAgreements(decodeAgreements(store.read(releaseName)) + (key to rows))) } }
        }
    }

    // the menus just changed, so that their lists are read again at once
    private val touched = MutableSharedFlow<String>(extraBufferCapacity = TOUCHED_KEPT)

    private val printing = PrintSchema(client)
    private val schema: suspend (MenuPath) -> MenuSchema = inspect?.let { it::describe } ?: printing::describe
    private val outlined: suspend (MenuPath) -> MenuSchema = inspect?.let { it::outline } ?: printing::outline

    override val safeMode: SafeMode? = client.safeMode?.let(::SafeModeErrors) ?: when {
        capabilities.safeMode == SafeModeKind.ApiSession -> ApiSafeMode(scope)
        capabilities.safeMode == SafeModeKind.SshConsole && ssh != null && scope != null ->
            ConsoleSafeMode(ssh, scope, { count(MenuPath.parse("system/history"), Filter.Equals("floating-undo", "true")) }, pollInterval)
        else -> null
    }

    override suspend fun describe(path: MenuPath): MenuSchema = deviceErrors { nativeSchema(path, lists = true) ?: schema(path) }

    override suspend fun outline(path: MenuPath): MenuSchema = deviceErrors { nativeSchema(path, lists = false) ?: outlined(path) }

    override fun nativeStands(path: MenuPath) = nativeWindows.stands(path)

    override var passwordExpired = client.passwordExpired
        private set

    override suspend fun changePassword(old: String, new: String) {
        deviceErrors { client.call("/password", "=old-password=$old", "=new-password=$new", "=confirm-new-password=$new") }
        passwordExpired = false
    }

    private suspend fun nativeSchema(path: MenuPath, lists: Boolean): MenuSchema? =
        nativeWindows.takeIf { it.stands(path) }?.let { it.schema(path, it.spec(path) ?: throw DeviceException(ErrorCategory.MissingItem, "no window of the native channel at $path"), lists) }

    override suspend fun forgetStructure() {
        inspect?.forget()
        printing.forget()
    }

    override suspend fun packs(store: TextStore?): List<Pack> = deviceErrors {
        release = store?.takeIf { mirrors != null }
        released = decodeAgreements(release?.read(releaseName))
        val schema = inspect ?: return@deviceErrors emptyList()
        val natively = nativeWindows.usable()
        // the descriptions come over the WinBox port as WinBox takes them, and from the web server where that port cannot give them
        val native = nativeUiFiles(nativeWindows::resource).takeIf { natively }
        val (described, files) = native?.let { from -> loadUiList(from)?.let { from to it } }
            ?: web?.let { access -> webPort()?.let { port -> access.files(port) } }?.let { from -> loadUiList(from)?.let { from to it } }
            ?: return@deviceErrors emptyList()
        val running = RouterOsVersion.parse(device.version)
        val bundled = bundledPacks().values.filter { it.platform == Platform.RouterOs.packName && running?.within(it.since, it.until) != false }
        // ipv6 is part of the main package from RouterOS 7; the other names the descriptions ask for have no known source and stay out
        val facts = if (natively) nativeWindows.facts()?.copy(addons = if ((running?.major ?: 0) >= 7) listOf("ipv6") else emptyList()) else null
        val state = "${device.id.orEmpty()} ${device.version} ${device.packages.sorted()} ${uiState(files)} ${encodeUiPacks(bundled).hashCode()} $UI_PACKS $natively $facts"
        val name = "ui-packs-${state.hashCode().toUInt().toString(16)}.json"
        val mirrorsName = "ui-mirrors-${state.hashCode().toUInt().toString(16)}.json"
        val verdictsName = "ui-verdicts-$UI_VERDICTS-${state.hashCode().toUInt().toString(16)}.json"
        suspend fun standing(all: List<Pack>, inherited: Map<String, PackNative>): List<Pack> {
            val known = store?.read(verdictsName)?.let(::decodeVerdicts)
            var checked = false
            val alive = nativeWindows.alive(all, known) { fresh ->
                checked = true
                store?.write(verdictsName, encodeVerdicts(fresh))
            }
            // the windows stand as a check made before found them, and are checked again beside the reads for the next connect
            if (known != null && !checked) {
                scope?.launch {
                    try {
                        nativeWindows.recheck(all)?.let { store.write(verdictsName, encodeVerdicts(it)) }
                    } catch (e: DeviceException) {
                        // a channel that cannot be asked now leaves the check made before to the next connect
                    }
                }
            }
            return withQueries(alive, bundled).also {
                nativeWindows.keep(it)
                nativeWindows.inherit(inherited, bundled)
            }
        }
        store?.read(name)?.let(::decodeUiPacks)?.let { return@deviceErrors standing(it, store.read(mirrorsName)?.let(::decodeUiMirrors).orEmpty()) }
        val source = object : UiSchema {
            override suspend fun members(path: MenuPath): UiMembers? = try {
                outlined(path).let { UiMembers(it.children, it.commands) }
            } catch (e: DeviceException) {
                if (e.category == ErrorCategory.MissingItem) null else throw e
            }

            override suspend fun properties(path: MenuPath): List<String> = schema.properties(path)
        }
        val entries = loadUiEntries(described, files)
        val known = uiKnownPaths(bundledMenu().items, bundled)
        val inherited = if (natively) uiInheritedMirrors(entries, source, known) else emptyMap()
        (routerFirst(bundled, uiPacks(entries, source, known, natively, facts, bundled)) + if (natively) uiQueries(entries, facts) else emptyList()).also {
            store?.write(name, encodeUiPacks(it))
            store?.write(mirrorsName, encodeUiMirrors(inherited))
        }.let { standing(it, inherited) }
    }

    private suspend fun servicePort(name: String, usual: Int): Int? = try {
        client.call("/ip/service/print", "?name=$name", "=.proplist=port,disabled").rows.firstOrNull()?.let { if (it["disabled"] == "true") null else it["port"]?.toIntOrNull() ?: usual } ?: usual
    } catch (e: ApiTrapException) {
        usual
    }

    private suspend fun webPort() = servicePort("www", 80)

    private suspend fun nativePort() = servicePort("winbox", 8291)

    override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> {
        val spec = nativeWindows.spec(path)
        return when {
            nativeWindows.stands(path) -> spec?.let { nativeWindows.list(it, filter, nativeWindows.refresh(path) ?: pollInterval) } ?: flow { throw DeviceException(ErrorCategory.MissingItem, "no window of the native channel at $path") }
            spec?.log == true && filter == null && properties == null -> nativeWindows.lines(spec, client.each { api -> follow(api, path, null, null) }) { count(path, null) }
            spec == null -> client.each { api -> follow(api, path, filter, properties) }
            else -> nativeWindows.joined(client.each { api -> follow(api, path, filter, properties) }, spec)
        }.asDeviceErrors()
    }

    override suspend fun get(path: MenuPath, id: String?, properties: List<String>?): Item? = deviceErrors {
        val spec = nativeWindows.spec(path)
        if (nativeWindows.stands(path)) return@deviceErrors spec?.let { nativeWindows.item(it, id) }
        val mirrored = mirrors?.rows(path, id?.let { Filter.Equals(".id", it) }, properties, null) { ids -> printed(client, path, ids) }
        val item = (mirrored ?: client.call(print(path, properties) + listOfNotNull(id?.let { "?.id=$it" })).rows).firstOrNull()?.let(::item)
        if (item == null || spec == null) item else nativeWindows.joined(item, spec)
    }

    override suspend fun count(path: MenuPath, filter: Filter?): Int = deviceErrors {
        nativeWindows.takeIf { it.stands(path) }?.let { windows -> return@deviceErrors windows.spec(path)?.let { windows.count(it) } ?: 0 }
        mirrors?.rows(path, filter, emptyList(), null) { ids -> printed(client, path, ids) }?.let { return@deviceErrors it.size }
        val count = client.call(listOf(path.apiCommand("print"), "=count-only=") + filter?.apiQuery().orEmpty()).done["ret"]
        count?.toIntOrNull() ?: throw DeviceException(ErrorCategory.Api, "count-only answered ${count ?: "without a number"}")
    }

    // the rows of a mirrored menu the console prints to check them, the one record of settings whole, or null where it cannot print them now
    private suspend fun printed(api: RouterOsLink, path: MenuPath, ids: List<String>): List<Map<String, String>>? = try {
        api.call(listOf(path.apiCommand("print")) + if (ids.all { it.isEmpty() }) emptyList() else listOf("=from=" + ids.joinToString(","))).rows
    } catch (e: ApiTrapException) {
        null
    }

    // a window of the channel is written through the channel, the other windows through the API
    private fun nativeSpec(path: MenuPath): PackNative? =
        nativeWindows.takeIf { it.stands(path) }?.let { it.spec(path) ?: throw DeviceException(ErrorCategory.MissingItem, "no window of the native channel at $path") }

    override suspend fun add(path: MenuPath, values: Map<String, String>): String = changing {
        nativeSpec(path)?.let { return@changing nativeWindows.create(it, values) }
        inspect?.forgetNames()
        client.call(listOf(path.apiCommand("add")) + values.words()).done["ret"]
            ?: throw DeviceException(ErrorCategory.Api, "${path.apiCommand("add")} answered without an id")
    }.also { touched.tryEmit(path.toString()) }

    override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) = changing {
        nativeSpec(path)?.let { return@changing nativeWindows.change(it, id, changes) }
        inspect?.forgetNames()
        val target = listOfNotNull(id?.let { "=.id=$it" })
        val values = changes.mapNotNull { (name, value) -> value?.let { name to it } }.toMap()
        if (values.isNotEmpty()) client.call(listOf(path.apiCommand("set")) + target + values.words())
        changes.filterValues { it == null }.keys.forEach { client.call(listOf(path.apiCommand("unset")) + target + "=value-name=$it") }
    }.also { touched.tryEmit(path.toString()) }

    override suspend fun remove(path: MenuPath, ids: List<String>) {
        changing {
            nativeSpec(path)?.let { return@changing nativeWindows.delete(it, ids) }
            inspect?.forgetNames()
            for (part in ids.chunked(REMOVED_AT_ONCE)) client.call(path.apiCommand("remove"), "=.id=" + part.joinToString(","))
        }
        touched.tryEmit(path.toString())
    }

    override suspend fun move(path: MenuPath, ids: List<String>, before: String?) {
        changing {
            nativeSpec(path)?.let { return@changing nativeWindows.move(it, ids, before) }
            client.call(listOfNotNull(path.apiCommand("move"), "=numbers=" + ids.joinToString(","), before?.let { "=destination=$it" }))
        }
        touched.tryEmit(path.toString())
    }

    override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> =
        if (nativeWindows.stands(path)) {
            flow<Item> {
                val spec = nativeSpec(path)
                if (spec?.query != null) emitAll(nativeWindows.query(spec, arguments)) else if (spec != null) nativeWindows.act(spec, command, arguments["id"])
            }.asDeviceErrors()
        } else {
            client.command(listOf(path.apiCommand(command)) + arguments.words()).map { item(it.attributes) }.asDeviceErrors()
        }

    override fun counters(path: MenuPath, properties: List<String>, filter: Filter?): Flow<Map<String, Map<String, String>>> = flow {
        if (!capabilities.streamingCommands || !outline(path).listens) return@flow
        val print = listOf(path.apiCommand("print"), "=interval=1", "=.proplist=" + (ids(path) + properties).joinToString(","))
        emitAll(client.command(print + filter?.apiQuery().orEmpty()).map { item(it.attributes) }.asDeviceErrors().rounds { it.id })
    }

    override val link: Flow<LinkState> get() = client.status

    override suspend fun export(path: MenuPath): String = deviceErrors {
        val script = "/" + (path.segments + "export").joinToString(" ")
        client.call("/execute", "=script=$script", "=as-string=").done["ret"]
            ?: throw DeviceException(ErrorCategory.Api, "$script returned no text")
    }

    // a router that cannot be asked right now says nothing of its ssh service, and the port is tried as the login names it
    suspend fun sshService(): SshService? = try {
        client.call("/ip/service/print", "?name=ssh", "=.proplist=port,disabled").rows.firstOrNull()?.let {
            SshService(it["port"]?.toIntOrNull(), it["disabled"] == "true")
        }
    } catch (e: ApiTrapException) {
        null
    } catch (e: ApiConnectionClosedException) {
        null
    }

    suspend fun sshPort(): Int? = sshService()?.port

    override fun close() {
        inspect?.let { schema -> scope?.launch { schema.flush() } }
        nativeWindows.close()
        client.close()
        ssh?.close()
    }

    // listen goes before print, so no change falls between them; events during print apply after the snapshot
    private suspend fun FlowCollector<ListEvent>.follow(api: RouterOsLink, path: MenuPath, filter: Filter?, properties: List<String>?) =
        coroutineScope {
            val query = filter?.apiQuery().orEmpty()
            val updates = Channel<Map<String, String>>(Channel.UNLIMITED)
            val sent = CompletableDeferred<Unit>()
            launch {
                try {
                    val listen = listOf(path.apiCommand("listen")) + proplist(path, properties?.let { listOf(".dead", ".nextid") + it })
                    api.command(listen, sent).collect { updates.send(it.attributes) }
                } catch (e: ApiTrapException) {
                    sent.complete(Unit)
                    updates.close(e)
                }
            }
            sent.await()
            val items = LinkedHashMap<String, Item>()
            rows(api, path, properties, query, filter) { read, total, added -> emit(ListEvent.Progress(read, total, added.map(::item))) }.map(::item).forEach { items[it.id] = it }
            emit(ListEvent.Snapshot(items.values.toList()))
            try {
                // a window of one element skips the events of the others without asking the router about each
                val only = (filter as? Filter.Equals)?.takeIf { it.property == ".id" }?.value
                for (event in updates) {
                    val id = event[".id"] ?: continue
                    when {
                        only != null && id != only -> continue
                        event[".dead"] == "true" -> if (items.remove(id) != null) emit(ListEvent.Removed(id))
                        filter == null -> emit(upsert(items, item(event), event))
                        else -> {
                            // an event does not tell whether the element still matches, and listen with a query hides removals
                            val row = api.call(print(path, properties) + query + "?.id=$id").rows.firstOrNull()
                            if (row != null) emit(upsert(items, item(row), event)) else if (items.remove(id) != null) emit(ListEvent.Removed(id))
                        }
                    }
                }
            } catch (e: ApiTrapException) {
                var rows = items.values.toList()
                // a list takes at most a tenth of the router's time and is read as often as its window in WinBox; the log is read whole on every round, so it is asked less often
                val least = if (path == LOG) pollInterval * 5 else nativeWindows.refresh(path) ?: pollInterval
                var wait = least
                val menu = path.toString()
                while (true) {
                    withTimeoutOrNull(wait) { touched.first { it == menu } }
                    val asked = TimeSource.Monotonic.markNow()
                    // a round that rows going under the print broke leaves the list as it was until the next one
                    val fresh = try {
                        rows(api, path, properties, query, filter) { _, _, _ -> }.map(::item)
                    } catch (e: ApiTrapException) {
                        if (!e.gone()) throw e
                        rows
                    }
                    wait = maxOf(least, asked.elapsedNow() * 9)
                    if (fresh != rows) emit(ListEvent.Snapshot(fresh))
                    rows = fresh
                }
            }
        }

    // a menu of the console is read as WinBox reads it, from its window of the WinBox port, once the console agrees with what the window gives
    private suspend fun rows(
        api: RouterOsLink,
        path: MenuPath,
        properties: List<String>?,
        query: List<String>,
        filter: Filter?,
        progress: suspend (Int, Int, List<Map<String, String>>) -> Unit,
    ): List<Map<String, String>> {
        val words = print(path, properties) + query
        val menu = path.toString()
        val mirror = nativeMirrors[menu]?.takeIf {
            client is ConsoleClient && properties == null && query.isEmpty() && menu !in mirrorsRefused.value && (sizes.value[menu] ?: MIRRORED_LEAST) > MIRRORED_LEAST / 2
        }
        val console: suspend () -> List<Map<String, String>> = { api.table(words) { read, total -> progress(read, total, emptyList()) } }
        // a table that had more rows than its window shows is left to the console until it has fewer
        val shown = mirrors?.takeIf { sizes.value[menu]?.let { rows -> nativeWindows.mirror(path)?.most?.let { rows > it } } != true }
        val found = mirror?.let { mirrored(api, menu, it, words, progress) } ?: shown?.rows(path, filter, properties, console) { ids -> printed(api, path, ids) } ?: console()
        if (properties == null && filter == null) sizes.update { it + (menu to found.size) }
        return found
    }

    // the rows go on as they come once the first ones agree with the console; the whole table is checked again for the fields they lacked
    private suspend fun mirrored(
        api: RouterOsLink,
        menu: String,
        mirror: NativeMirror,
        words: List<String>,
        progress: suspend (Int, Int, List<Map<String, String>>) -> Unit,
    ): List<Map<String, String>>? {
        if (!nativeWindows.usable()) return null
        val total = try {
            api.call(listOf(words.first(), "=count-only=")).done["ret"]?.toIntOrNull()
        } catch (e: ApiTrapException) {
            null
        }
        if (total == null || total <= MIRRORED_LEAST) return null
        progress(0, total, emptyList())
        val uptime = if (mirror.ages.isEmpty()) null else {
            try {
                nativeWindows.item(UPTIME, "#0")?.get("uptime")?.toLongOrNull()
            } catch (e: DeviceException) {
                null
            } ?: return null
        }
        val read = ArrayList<Map<String, String>>()
        val hidden = ArrayList<Map<String, String>>()
        var passed = 0
        var trusted = false
        var told = TimeSource.Monotonic.markNow()
        val items = nativeWindows.mirrored(mirror.spec, total) page@{ page ->
            page.map { mirror.row(it, uptime) }.partition(mirror.shown).let { (rows, unseen) ->
                read += rows
                hidden += unseen
            }
            if (!trusted) {
                if (read.isEmpty()) return@page
                checked(api, menu, mirror, words, read, hidden) ?: throw DeviceException(ErrorCategory.Unsupported, "the window of $menu does not agree with the console")
                trusted = true
            } else if (told.elapsedNow() < PARTIAL_EVERY) return@page
            progress(read.size, total, read.subList(passed, read.size).toList())
            passed = read.size
            told = TimeSource.Monotonic.markNow()
        } ?: return null
        val (rows, unseen) = items.map { mirror.row(it, uptime) }.partition(mirror.shown)
        val known = checked(api, menu, mirror, words, rows, unseen) ?: return null
        // a field only rows gone before the console printed them had is checked with the next read, and until then the rows go without it
        return rows.map { row -> if (known.containsAll(row.keys)) row else row.filterKeys { it in known } }
    }

    // the console prints a sample of the rows the same, and none of a few of those the menu hides; the fields checked so far,
    // or null where the console printed nothing or disagreed, and then the menu is left to the console for the session
    private suspend fun checked(
        api: RouterOsLink,
        menu: String,
        mirror: NativeMirror,
        words: List<String>,
        rows: List<Map<String, String>>,
        hidden: List<Map<String, String>>,
    ): Set<String>? {
        suspend fun printed(ids: List<String>) = try {
            api.call(words + "=from=${ids.joinToString(",")}").rows
        } catch (e: ApiTrapException) {
            null
        }
        // a row that went between the two reads fails the print, which is asked again in parts and then row by row
        suspend fun sampled(ids: List<String>): List<Map<String, String>> =
            if (ids.isEmpty()) emptyList() else printed(ids) ?: ids.chunked(SAMPLE_PART).flatMap { part -> printed(part) ?: part.flatMap { printed(listOf(it)).orEmpty() } }
        val before = mirrorsChecked.value[menu]
        val known = if (before != null && rows.all { before.containsAll(it.keys) }) before else {
            val console = sampled(mirror.sample(rows))
            if (console.isEmpty()) return null
            if (!mirror.agrees(rows.associateBy { it.getValue(".id") }, console)) {
                mirrorsRefused.update { it + menu }
                return null
            }
            (before.orEmpty() + console.flatMapTo(HashSet()) { it.keys }).also { all -> mirrorsChecked.update { it + (menu to all) } }
        }
        if (hidden.isNotEmpty() && menu !in mirrorsHiding.value) {
            if (sampled(mirror.unseen(hidden)).isNotEmpty()) {
                mirrorsRefused.update { it + menu }
                return null
            }
            mirrorsHiding.update { it + menu }
        }
        return known
    }

    private inner class ApiSafeMode(private val scope: CoroutineScope?) : SafeMode {
        private val held = MutableStateFlow(false)
        private val lost = MutableStateFlow(false)
        private val left = MutableStateFlow(false)
        private val history = MenuPath.parse("system/history")
        private var beat: Job? = null

        init {
            scope?.launch {
                client.status.transformWhile { emit(it); it != LinkState.Closed }.collect { link ->
                    if (link != LinkState.Up) {
                        drop()
                    } else if (lost.compareAndSet(true, false)) {
                        left.value = heldElsewhere()
                    }
                }
            }
        }

        override val active: Flow<Boolean> = combine(held, client.status) { on, link ->
            if (link != LinkState.Up) drop()
            on && link == LinkState.Up
        }

        override val stranded: Flow<Boolean> = left

        private fun drop() {
            if (held.compareAndSet(true, false)) lost.value = true
            beat?.cancel()
        }

        private suspend fun heldElsewhere(): Boolean = try {
            deviceErrors { client.call("/safe-mode/print").rows.firstOrNull() }?.let { it["enabled"] == "true" && it["current"] != "true" } == true
        } catch (e: DeviceException) {
            // a router that does not answer about the mode now leaves nothing to settle
            false
        }

        override suspend fun settle(keep: Boolean) {
            deviceErrors { client.call(if (keep) "/safe-mode/release" else "/safe-mode/unroll") }
            left.value = false
        }

        override val actions: Flow<Int> = flow {
            while (true) {
                emit(if (held.value) count(history, Filter.Equals("floating-undo", "true")) else 0)
                delay(pollInterval)
            }
        }

        override suspend fun enter(): Boolean = deviceErrors {
            (client.call("/safe-mode/take").done["ret"] == "true").also { taken ->
                held.value = taken
                if (taken) beat = scope?.launch { client.command(BEAT).catch { }.collect { } }
            }
        }

        override suspend fun commit() = leave("/safe-mode/release")

        override suspend fun rollback() = leave("/safe-mode/unroll")

        private suspend fun leave(command: String) {
            deviceErrors { client.call(command) }
            held.value = false
            beat?.cancel()
        }
    }

    private fun upsert(items: MutableMap<String, Item>, item: Item, event: Map<String, String>): ListEvent.Upsert {
        items[item.id] = item
        return ListEvent.Upsert(item, event[".nextid"]?.let { if (it == END) "" else it })
    }

    private suspend fun print(path: MenuPath, properties: List<String>?) = listOf(path.apiCommand("print")) + proplist(path, properties)

    private suspend fun proplist(path: MenuPath, properties: List<String>?): List<String> {
        properties ?: return emptyList()
        return listOf("=.proplist=" + (ids(path) + properties).joinToString(","))
    }

    // RouterOS 6: asked for .id, system/resource and system/license drop the connection, system/clock answers empty
    private suspend fun ids(path: MenuPath) = if (capabilities.menuContents || !settings(path)) listOf(".id") else emptyList()

    private suspend fun settings(path: MenuPath) = try {
        outline(path).kind == NodeKind.Settings
    } catch (e: DeviceException) {
        false
    }

    private fun Map<String, String>.words() = map { (name, value) -> "=$name=$value" }

    private fun item(attributes: Map<String, String>) = Item(attributes[".id"].orEmpty(), attributes - service)

    private companion object {
        const val UI_PACKS = 19
        // the way the windows are checked: the verdicts kept before it changed are made again
        const val UI_VERDICTS = 2
        const val TOUCHED_KEPT = 16

        // a smaller table the console reads at once in less time than the check of the mirror takes
        const val MIRRORED_LEAST = 2_000
        const val SAMPLE_PART = 10
        // the rows read so far go on to the window at most this often, so a table of 50 000 rows is not rebuilt for each page
        val PARTIAL_EVERY = 200.milliseconds

        // the line of the console of RouterOS 6 ends at 2 500 characters
        const val REMOVED_AT_ONCE = 200

        const val END = "*FFFFFFFF"
        val LOG = MenuPath.parse("log")
        val service = setOf(".id", ".dead", ".nextid")
        // the router learns of a session lost without FIN or RST by sending into it, and rolls back ~16 minutes later
        val BEAT = listOf("/system/resource/print", "=interval=5", "=.proplist=uptime")
    }
}

private inline fun <T> deviceErrors(block: () -> T): T = try {
    block()
} catch (e: Exception) {
    throw e.toDeviceException()
}

// an edit that lost the link may have been done, and a repeat could do it twice
private inline fun <T> changing(block: () -> T): T = try {
    block()
} catch (e: Exception) {
    val failure = e.toDeviceException()
    throw if (failure is DeviceException && failure.category == ErrorCategory.ConnectionLost) RequestOutcomeUnknownException(e) else failure
}

private class SafeModeErrors(private val inner: SafeMode) : SafeMode by inner {
    override suspend fun enter() = deviceErrors { inner.enter() }

    override suspend fun commit() = deviceErrors { inner.commit() }

    override suspend fun rollback() = deviceErrors { inner.rollback() }
}

private fun <T> Flow<T>.asDeviceErrors(): Flow<T> = catch { throw it.toDeviceException() }

private fun Throwable.toDeviceException(): Throwable = when (this) {
    is ApiTrapException -> DeviceException(ErrorCategory.ofTrap(category), message.orEmpty(), this)
    is ApiConnectionClosedException -> DeviceException(ErrorCategory.ConnectionLost, LINK_LOST, this)
    is DeviceException -> if (category == ErrorCategory.ConnectionLost && this !is RequestOutcomeUnknownException) DeviceException(ErrorCategory.ConnectionLost, LINK_LOST, this) else this
    else -> this
}
