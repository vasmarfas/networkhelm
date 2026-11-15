package com.vasmarfas.networkhelm.gateway

import com.vasmarfas.networkhelm.discovery.Found
import com.vasmarfas.networkhelm.discovery.MndpSighting
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.openwrt.OpenWrtLogin
import com.vasmarfas.networkhelm.packs.NativeMenu
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.Login
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.session.TextStore
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.RemoteFile
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshReach
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.Base64Bytes
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.TrustPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

class GatewayClient(private val wire: Wire, internal val scope: CoroutineScope) {
    private val lock = Mutex()
    private var last = 0L
    private val waiting = HashMap<Long, Channel<FromGateway>>()
    private var gone: DeviceException? = null
    private val end = CompletableDeferred<DeviceException>()
    private val greeted = CompletableDeferred<Unit>()

    val ended: Deferred<DeviceException> get() = end

    var offers = Offers()
        private set

    suspend fun hello() = greeted.await()

    init {
        scope.launch {
            var reason = DeviceException(ErrorCategory.ConnectionLost, "the gateway closed the connection")
            try {
                read()
            } catch (e: DeviceException) {
                reason = e
            } catch (e: SerializationException) {
                reason = DeviceException(ErrorCategory.General, "the gateway sent a message this client cannot read: ${e.message}")
            } finally {
                wire.close()
                withContext(NonCancellable) {
                    lock.withLock {
                        gone = reason
                        for (frames in waiting.values) frames.close(reason)
                        waiting.clear()
                    }
                }
                greeted.completeExceptionally(reason)
                end.complete(reason)
            }
        }
    }

    suspend fun connect(login: RouterOsLogin, trust: TrustPolicy): DeviceSession =
        RemoteDeviceSession(this, call(Request.ConnectRouterOs(login), Opened.serializer(), trust))

    suspend fun connect(login: OpenWrtLogin, trust: TrustPolicy): DeviceSession =
        RemoteDeviceSession(this, call(Request.ConnectOpenWrt(login), Opened.serializer(), trust))

    suspend fun connect(login: Login, trust: TrustPolicy): DeviceSession =
        RemoteDeviceSession(this, call(Request.Connect(login), Opened.serializer(), trust))

    suspend fun connect(login: NativeLogin): DeviceSession =
        RemoteDeviceSession(this, call(Request.ConnectNative(login), Opened.serializer()))

    suspend fun connect(login: MacTelnetLogin): DeviceSession =
        RemoteDeviceSession(this, call(Request.ConnectMacTelnet(login), Opened.serializer()))

    fun neighbors(): Flow<MndpSighting> = stream(Request.Neighbors, MndpSighting.serializer())

    suspend fun refreshNeighbors() = call(Request.RefreshNeighbors, Unit.serializer())

    fun scan(addresses: List<String>): Flow<Found> = stream(Request.Scan(addresses), Found.serializer())

    suspend fun readDocument(name: String) = call(Request.ReadDocument(name), String.serializer().nullable)

    suspend fun writeDocument(name: String, text: String) = call(Request.WriteDocument(name, text), Unit.serializer())

    suspend fun readSecret(key: String) = call(Request.ReadSecret(key), String.serializer().nullable)

    suspend fun writeSecret(key: String, value: String) = call(Request.WriteSecret(key, value), Unit.serializer())

    suspend fun deleteSecret(key: String) = call(Request.DeleteSecret(key), Unit.serializer())

    private suspend fun read() {
        while (true) {
            val text = wire.receive() ?: return
            when (val frame = gatewayJson.decodeFromString(FromGateway.serializer(), text)) {
                is FromGateway.Hello -> {
                    if (frame.protocol != GATEWAY_PROTOCOL) {
                        throw DeviceException(ErrorCategory.Unsupported, "the gateway speaks protocol ${frame.protocol}, this client $GATEWAY_PROTOCOL")
                    }
                    offers = frame.offers
                    greeted.complete(Unit)
                }
                is FromGateway.Question -> deliver(frame.ask, frame)
                is FromGateway.Value -> deliver(frame.id, frame)
                is FromGateway.Next -> deliver(frame.id, frame)
                is FromGateway.Done -> deliver(frame.id, frame)
                is FromGateway.Failed -> deliver(frame.id, frame)
            }
        }
    }

    private suspend fun deliver(id: Long, frame: FromGateway) {
        lock.withLock { waiting[id] }?.trySend(frame)
    }

    internal suspend fun open(request: Request): Pair<Long, ReceiveChannel<FromGateway>> {
        val frames = Channel<FromGateway>(Channel.UNLIMITED)
        val id = lock.withLock {
            gone?.let { throw DeviceException(it.category, it.message.orEmpty()) }
            (++last).also { waiting[it] = frames }
        }
        post(ToGateway.Ask(id, request))
        return id to frames
    }

    internal suspend fun post(message: ToGateway) {
        try {
            wire.send(gatewayJson.encodeToString(ToGateway.serializer(), message))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the reader sees the wire end as well and fails everything waiting with ConnectionLost
            wire.close()
        }
    }

    internal fun tell(message: ToGateway) {
        scope.launch { post(message) }
    }

    internal suspend fun settle(id: Long, finished: Boolean) = withContext(NonCancellable) {
        val known = lock.withLock { waiting.remove(id) != null }
        if (known && !finished) post(ToGateway.Cancel(id))
    }

    internal suspend fun <T> call(request: Request, answer: KSerializer<T>, trust: TrustPolicy? = null): T {
        val (id, frames) = open(request)
        var finished = false
        try {
            while (true) {
                when (val frame = frames.receive()) {
                    is FromGateway.Value -> {
                        finished = true
                        return gatewayJson.decodeFromJsonElement(answer, frame.value)
                    }
                    is FromGateway.Failed -> {
                        finished = true
                        throw frame.exception()
                    }
                    is FromGateway.Question -> {
                        val accepted = trust?.confirm(frame.host, frame.port, frame.kind, frame.fingerprint, frame.previous) == true
                        post(ToGateway.Answer(frame.id, accepted))
                    }
                    else -> error("a call gets no ${frame::class.simpleName}")
                }
            }
        } finally {
            settle(id, finished)
        }
    }

    internal fun <T> stream(request: Request, element: KSerializer<T>): Flow<T> = flow {
        val (id, frames) = open(request)
        var finished = false
        try {
            while (true) {
                when (val frame = frames.receive()) {
                    is FromGateway.Next -> emit(gatewayJson.decodeFromJsonElement(element, frame.value))
                    is FromGateway.Done -> {
                        finished = true
                        return@flow
                    }
                    is FromGateway.Failed -> {
                        finished = true
                        throw frame.exception()
                    }
                    else -> error("a stream gets no ${frame::class.simpleName}")
                }
            }
        } finally {
            settle(id, finished)
        }
    }

    internal suspend fun shell(request: Request.Shell): ShellChannel {
        val (id, frames) = open(request)
        var opened = false
        var failed = false
        try {
            when (val frame = frames.receive()) {
                is FromGateway.Value -> {
                    opened = true
                    return RemoteShell(this, id, frames)
                }
                is FromGateway.Failed -> {
                    failed = true
                    throw frame.exception()
                }
                else -> error("a terminal opens with no ${frame::class.simpleName}")
            }
        } finally {
            if (!opened) settle(id, failed)
        }
    }
}

class RemoteDeviceSession internal constructor(private val client: GatewayClient, opened: Opened) : DeviceSession {
    private val session = opened.session
    override val device = opened.device
    override val capabilities = opened.capabilities

    override suspend fun describe(path: MenuPath) = client.call(Request.Describe(session, path), MenuSchema.serializer())

    override suspend fun outline(path: MenuPath) = client.call(Request.Outline(session, path), MenuSchema.serializer())

    override suspend fun forgetStructure() = client.call(Request.ForgetStructure(session), Unit.serializer())

    override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?) =
        client.stream(Request.Watch(session, path, filter, properties), ListEvent.serializer())

    override suspend fun get(path: MenuPath, id: String?, properties: List<String>?) =
        client.call(Request.Get(session, path, id, properties), Item.serializer().nullable)

    override suspend fun count(path: MenuPath, filter: Filter?) = client.call(Request.Count(session, path, filter), Int.serializer())

    override suspend fun add(path: MenuPath, values: Map<String, String>) = client.call(Request.Add(session, path, values), String.serializer())

    override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) =
        client.call(Request.Change(session, path, id, changes), Unit.serializer())

    override suspend fun remove(path: MenuPath, ids: List<String>) = client.call(Request.Remove(session, path, ids), Unit.serializer())

    override suspend fun move(path: MenuPath, ids: List<String>, before: String?) =
        client.call(Request.Move(session, path, ids, before), Unit.serializer())

    override fun run(path: MenuPath, command: String, arguments: Map<String, String>) =
        client.stream(Request.Run(session, path, command, arguments), Item.serializer())

    override suspend fun export(path: MenuPath) = client.call(Request.Export(session, path), String.serializer())

    override fun counters(path: MenuPath, properties: List<String>, filter: Filter?) =
        client.stream(Request.Counters(session, path, properties, filter), countersSerializer)

    override val link: Flow<LinkState> = client.stream(Request.Link(session), LinkState.serializer())
        .catch { if (it is DeviceException) emit(LinkState.Closed) else throw it }

    override suspend fun packs(store: TextStore?) = client.call(Request.Packs(session), ListSerializer(Pack.serializer()))

    override suspend fun nativeMenu() = client.call(Request.Native(session), NativeMenu.serializer().nullable)

    override suspend fun words(store: TextStore?) = client.call(Request.Words(session), MapSerializer(String.serializer(), String.serializer()))

    override val safeMode: SafeMode? = if (opened.safeMode) RemoteSafeMode(client, session) else null

    override val ssh: SshSession? = if (opened.ssh) RemoteSsh(client, session, opened.fileStore, opened.canForward) else null

    override val fileChannel: SftpClient? = if (opened.fileChannel) RemoteSftp(client, session, own = true) else null

    override fun close() = client.tell(ToGateway.Close(session))
}

private class RemoteSafeMode(private val client: GatewayClient, private val session: Long) : SafeMode {
    override val active = client.stream(Request.SafeModeActive(session), Boolean.serializer())

    override val actions = client.stream(Request.SafeModeActions(session), Int.serializer())

    override suspend fun enter() = client.call(Request.EnterSafeMode(session), Boolean.serializer())

    override suspend fun commit() = client.call(Request.CommitSafeMode(session), Unit.serializer())

    override suspend fun rollback() = client.call(Request.RollbackSafeMode(session), Unit.serializer())

    override val stranded = client.stream(Request.SafeModeStranded(session), Boolean.serializer())

    override suspend fun settle(keep: Boolean) = client.call(Request.SettleSafeMode(session, keep), Unit.serializer())
}

private class RemoteSsh(
    private val client: GatewayClient,
    private val session: Long,
    override val fileStore: Boolean,
    override val canForward: Boolean,
) : SshSession {
    override suspend fun exec(command: String) = client.call(Request.Exec(session, command), ExecResult.serializer())

    override suspend fun exec(command: String, input: String) = client.call(Request.Exec(session, command, input), ExecResult.serializer())

    override suspend fun process(command: String): ByteStream = throw DeviceException(ErrorCategory.Unsupported, "no SSH process through the gateway")

    override suspend fun shell(term: String, columns: Int, rows: Int) = client.shell(Request.Shell(session, term, columns, rows))

    override suspend fun sftp(): SftpClient = RemoteSftp(client, session, own = false)

    override suspend fun forward(host: String, port: Int): ByteStream = throw DeviceException(ErrorCategory.Unsupported, "no SSH tunnel through the gateway")

    override suspend fun reach(fresh: Boolean) = client.call(Request.SshReachable(session, fresh), SshReach.serializer())

    override fun close() = Unit
}

private class RemoteSftp(private val client: GatewayClient, private val session: Long, private val own: Boolean) : SftpClient {
    override suspend fun list(path: String) = client.call(Request.SftpList(session, path, own), ListSerializer(RemoteFile.serializer()))

    override suspend fun read(path: String) = client.call(Request.SftpRead(session, path, own), Base64Bytes)

    override suspend fun write(path: String, bytes: ByteArray) = client.call(Request.SftpWrite(session, path, bytes, own), Unit.serializer())

    override suspend fun remove(path: String) = client.call(Request.SftpRemove(session, path, own), Unit.serializer())

    override suspend fun rename(from: String, to: String) = client.call(Request.SftpRename(session, from, to, own), Unit.serializer())

    override suspend fun mkdir(path: String) = client.call(Request.SftpMkdir(session, path, own), Unit.serializer())

    override fun close() = Unit
}

private class RemoteShell(private val client: GatewayClient, private val id: Long, private val frames: ReceiveChannel<FromGateway>) : ShellChannel {
    private var chunk = ByteArray(0)
    private var start = 0
    private var finished = false

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (start == chunk.size) {
            if (finished) return -1
            when (val frame = frames.receive()) {
                is FromGateway.Next -> {
                    chunk = gatewayJson.decodeFromJsonElement(Base64Bytes, frame.value)
                    start = 0
                }
                is FromGateway.Done -> finished = true
                is FromGateway.Failed -> {
                    finished = true
                    throw frame.exception()
                }
                else -> error("a terminal gets no ${frame::class.simpleName}")
            }
        }
        val count = minOf(length, chunk.size - start)
        chunk.copyInto(buffer, offset, start, start + count)
        start += count
        return count
    }

    override suspend fun write(bytes: ByteArray) = client.post(ToGateway.Input(id, bytes))

    override fun resize(columns: Int, rows: Int) = client.tell(ToGateway.Resize(id, columns, rows))

    override fun close() {
        client.scope.launch { client.settle(id, finished) }
    }
}
