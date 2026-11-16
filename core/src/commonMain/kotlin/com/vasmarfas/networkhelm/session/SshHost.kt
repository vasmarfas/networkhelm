package com.vasmarfas.networkhelm.session

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
import com.vasmarfas.networkhelm.ssh.SshAuth
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.ssh.connectSshTrusted
import com.vasmarfas.networkhelm.transport.TrustPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class SshHostSession(
    override val ssh: SshSession,
    override val device: DeviceInfo,
    private val linux: Boolean = false,
    private val pollInterval: Duration = 3.seconds,
    private val time: TimeSource = TimeSource.Monotonic,
    val password: String? = null,
    // a new login with the address and the credentials of the session, closed at once
    login: (suspend () -> Unit)? = null,
) : DeviceSession {
    override val capabilities = Capabilities(
        subscriptions = false,
        streamingCommands = linux,
        cancel = false,
        safeMode = SafeModeKind.None,
        terminal = true,
        files = true,
        export = false,
        menuContents = false,
    )
    override val safeMode: SafeMode? = null

    private val host = LinuxHost(ssh, password, login)
    private val files = HostFiles(ssh)
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private suspend fun table(path: MenuPath): HostTable? = if (linux) host.tables[path.toString()]?.takeIf { it.present() } else null

    override suspend fun describe(path: MenuPath): MenuSchema = calls {
        val known = listOf(FILES) + if (linux) host.tables.filter { it.value.present() }.keys.map(MenuPath::parse) else emptyList()
        val children = known.flatMap { key -> (1..key.segments.size).map { MenuPath(key.segments.take(it)) } }.filter { it.parent == path }.map { it.segments.last() }.distinct().sorted()
        val table = if (path == FILES) null else table(path)
        when {
            path == FILES -> MenuSchema(path, NodeKind.List, emptyList(), listOf("print", "remove"), FILE_FIELDS.map { Argument(it, ValueType.Text(), readOnly = true) })
            table != null -> {
                val typed = table.arguments()
                MenuSchema(
                    path, if (table.settings) NodeKind.Settings else NodeKind.List, children,
                    listOf("print") + listOfNotNull("add".takeIf { table.add != null }, "set".takeIf { table.set != null }, "remove".takeIf { table.remove != null }, "move".takeIf { table.move != null }) + table.commands,
                    table.fields.map { field -> typed.firstOrNull { it.name == field } ?: Argument(field, ValueType.Text(), readOnly = true) } + typed.filter { it.name !in table.fields },
                )
            }
            children.isNotEmpty() || path == MenuPath.Root -> MenuSchema(path, NodeKind.Directory, children, emptyList(), emptyList())
            else -> throw DeviceException(ErrorCategory.MissingItem, "$path is not on this host")
        }
    }

    // what needs root is read seldom, so sudo does not fill the log of the host, and again at once after a change made here
    override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> = flow {
        val table = if (path == FILES) null else calls { table(path) } ?: throw DeviceException(ErrorCategory.MissingItem, "$path is not on this host")
        emitAll(polled(if (table?.slow == true) SLOW else pollInterval, changed) { calls { read(path) }.filter { filter?.matches(it) != false } })
    }

    override suspend fun get(path: MenuPath, id: String?, properties: List<String>?): Item? =
        if (linux || path == FILES) calls { read(path).firstOrNull { id == null || it.id == id } } else null

    override suspend fun count(path: MenuPath, filter: Filter?): Int =
        if (linux || path == FILES) calls { read(path).count { filter?.matches(it) != false } } else 0

    private suspend fun read(path: MenuPath) = if (path == FILES) files.read() else (table(path) ?: throw DeviceException(ErrorCategory.MissingItem, "$path is not on this host")).read()

    override suspend fun add(path: MenuPath, values: Map<String, String>): String = changing { (writable(path).add ?: throw readOnly(path))(values) }

    override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) {
        changing { (writable(path).set ?: throw readOnly(path))(id, changes) }
    }

    override suspend fun remove(path: MenuPath, ids: List<String>) {
        if (path == FILES) calls { files.remove(ids) } else changing { (writable(path).remove ?: throw readOnly(path))(ids) }
    }

    override suspend fun move(path: MenuPath, ids: List<String>, before: String?) {
        changing { (writable(path).move ?: throw readOnly(path))(ids, before) }
    }

    override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> = flow {
        if (linux && path.toString() == "interface" && command == "monitor-traffic") {
            emitAll(traffic(arguments))
            return@flow
        }
        val action = calls { table(path) }?.takeIf { command in it.commands }?.run ?: throw DeviceException(ErrorCategory.MissingItem, "$path/$command is not on this host")
        changing { action(command, arguments) }.forEach { emit(it) }
    }

    private fun traffic(arguments: Map<String, String>): Flow<Item> = flow {
        val wanted = arguments["interface"].orEmpty().split(',').filter { it.isNotEmpty() }
        var before: Pair<Map<String, List<Long>>, TimeMark>? = null
        var round = 0
        while (true) {
            val now = calls { host.traffic() }
            before?.let { (then, at) ->
                val seconds = at.elapsedNow().inWholeMilliseconds / 1000.0
                for (name in wanted) {
                    val counters = now[name] ?: continue
                    val previous = then[name] ?: continue
                    fun rate(i: Int, scale: Int) = if (seconds > 0) "${((counters[i] - previous[i]).coerceAtLeast(0) * scale / seconds).toLong()}" else "0"
                    emit(Item("", mapOf(
                        "name" to name, "rx-bits-per-second" to rate(0, 8), "tx-bits-per-second" to rate(1, 8), "rx-packets-per-second" to rate(2, 1),
                        "tx-packets-per-second" to rate(3, 1), ".section" to "$round",
                    )))
                }
                round++
            }
            before = now to time.markNow()
            delay(1.seconds)
        }
    }

    override suspend fun export(path: MenuPath): String = throw readOnly(path)

    override fun close() = ssh.close()

    private suspend fun writable(path: MenuPath) = calls { table(path) } ?: throw readOnly(path)

    private suspend fun <T> changing(block: suspend () -> T): T = try {
        calls { block() }
    } finally {
        changed.tryEmit(Unit)
    }

    private fun readOnly(path: MenuPath) = DeviceException(
        ErrorCategory.Unsupported,
        if (linux) "$path of this host cannot be changed here" else "only the files of a host reached over SSH alone can be changed here",
    )

    private suspend fun <T> calls(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: DeviceException) {
        throw e
    } catch (e: Exception) {
        throw DeviceException(ErrorCategory.ConnectionLost, e.message ?: e::class.simpleName.orEmpty(), e)
    }

    private companion object {
        val FILES = MenuPath.parse("file")
        val FILE_FIELDS = listOf("name", "type", "size", "last-modified")
        val SLOW = 30.seconds
    }
}

// the password of the login, given with a key as well, is what sudo asks for
suspend fun connectSshHost(host: String, port: Int, user: String, auth: SshAuth, trust: TrustPolicy, password: String? = (auth as? SshAuth.Password)?.password): SshHostSession {
    val ssh = connectSshTrusted(host, port, user, auth, trust)
    val uname = try {
        ssh.exec("uname -snrm").takeIf { it.exitStatus == 0 }?.output?.trim()?.split(' ').orEmpty()
    } catch (e: Exception) {
        ssh.close()
        throw e
    }
    val named = uname.size >= 4
    val device = DeviceInfo(
        Platform.Ssh,
        if (named) uname[1] else host,
        if (named) uname[0] + " " + uname[2] else "",
        if (named) uname.last() else "",
        "",
        emptySet(),
        setOf("read", "write"),
    )
    return SshHostSession(ssh, device, linux = named && uname[0] == "Linux", password = password) { connectSshTrusted(host, port, user, auth, trust).close() }
}
