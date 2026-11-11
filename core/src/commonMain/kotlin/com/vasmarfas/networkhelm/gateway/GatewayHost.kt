package com.vasmarfas.networkhelm.gateway

import com.vasmarfas.networkhelm.discovery.Found
import com.vasmarfas.networkhelm.discovery.MndpSighting
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.openwrt.OpenWrtLogin
import com.vasmarfas.networkhelm.packs.NativeMenu
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.Login
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.RemoteFile
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshReach
import com.vasmarfas.networkhelm.transport.Base64Bytes
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.TrustStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonNull

class GatewayHost(
    private val wire: Wire,
    private val trust: TrustStore,
    private val connectRouterOs: suspend (RouterOsLogin, TrustPolicy, CoroutineScope) -> DeviceSession,
    private val connectOpenWrt: suspend (OpenWrtLogin, TrustPolicy) -> DeviceSession,
    private val store: GatewayStore? = null,
    private val connectLogin: suspend (Login, TrustPolicy) -> DeviceSession = { login, trust -> login.connect(trust) },
    private val reach: Reach = Reach(),
) {
    private val lock = Mutex()
    private var last = 0L
    private val sessions = HashMap<Long, DeviceSession>()
    private val connected = HashMap<Long, Long>()
    private val jobs = HashMap<Long, Job>()
    private val shells = HashMap<Long, ShellChannel>()
    private val questions = HashMap<Long, CompletableDeferred<Boolean>>()

    suspend fun serve() = supervisorScope {
        try {
            post(FromGateway.Hello(GATEWAY_PROTOCOL, reach.offers()))
            while (true) {
                val text = wire.receive() ?: break
                val frame = try {
                    gatewayJson.decodeFromString(ToGateway.serializer(), text)
                } catch (e: SerializationException) {
                    // the exception quotes the frame in its message, and a frame may carry the password of a router; what leaves here goes to the log of the gateway
                    throw DeviceException(ErrorCategory.General, "the browser sent a message this gateway cannot read")
                }
                when (frame) {
                    is ToGateway.Ask -> {
                        val job = launch(start = CoroutineStart.LAZY) { answer(this@supervisorScope, frame.id, frame.request) }
                        lock.withLock { jobs[frame.id] = job }
                        job.start()
                    }
                    is ToGateway.Cancel -> {
                        lock.withLock { jobs[frame.id] }?.cancel()
                        // a browser that gives a connection up while its answer is on the way never hears of the session, and this cancel is all it sends
                        lock.withLock { connected.remove(frame.id)?.let { sessions.remove(it) } }?.close()
                    }
                    is ToGateway.Answer -> lock.withLock { questions.remove(frame.question) }?.complete(frame.accepted)
                    is ToGateway.Input -> lock.withLock { shells[frame.channel] }?.let { type(it, frame.bytes) }
                    is ToGateway.Resize -> lock.withLock { shells[frame.channel] }?.resize(frame.columns, frame.rows)
                    is ToGateway.Close -> lock.withLock {
                        connected.values.remove(frame.session)
                        sessions.remove(frame.session)
                    }?.close()
                }
            }
        } finally {
            wire.close()
            coroutineContext.cancelChildren()
            val open = withContext(NonCancellable) { lock.withLock { sessions.values.toList().also { sessions.clear() } } }
            open.forEach { it.close() }
        }
    }

    private suspend fun answer(scope: CoroutineScope, id: Long, request: Request) {
        try {
            when (request) {
                is Request.ConnectRouterOs -> value(id, Opened.serializer(), opened(id, connectRouterOs(request.login, policy(id), scope)))
                is Request.ConnectOpenWrt -> value(id, Opened.serializer(), opened(id, connectOpenWrt(request.login, policy(id))))
                is Request.Connect -> value(id, Opened.serializer(), opened(id, connectLogin(request.login, policy(id))))
                is Request.ConnectNative -> value(id, Opened.serializer(), opened(id, offered(reach.connectNative, "reach a router over port 8291")(request.login, scope)))
                is Request.ConnectMacTelnet -> value(id, Opened.serializer(), opened(id, offered(reach.connectMacTelnet, "reach a router by its MAC address")(request.login, scope)))
                is Request.Neighbors -> stream(id, MndpSighting.serializer(), offered(reach.neighbors, "hear MNDP")())
                is Request.RefreshNeighbors -> value(id, Unit.serializer(), offered(reach.refreshNeighbors, "ask for MNDP announcements")())
                is Request.Scan -> stream(id, Found.serializer(), offered(reach.scan, "sweep addresses")(sweepable(request.addresses)))
                is Request.Describe -> value(id, MenuSchema.serializer(), session(request.session).describe(request.path))
                is Request.Outline -> value(id, MenuSchema.serializer(), session(request.session).outline(request.path))
                is Request.ForgetStructure -> value(id, Unit.serializer(), session(request.session).forgetStructure())
                is Request.Watch -> stream(id, ListEvent.serializer(), session(request.session).watch(request.path, request.filter, request.properties))
                is Request.Get -> value(id, Item.serializer().nullable, session(request.session).get(request.path, request.id, request.properties))
                is Request.Count -> value(id, Int.serializer(), session(request.session).count(request.path, request.filter))
                is Request.Add -> value(id, String.serializer(), session(request.session).add(request.path, request.values))
                is Request.Change -> value(id, Unit.serializer(), session(request.session).set(request.path, request.id, request.changes))
                is Request.Remove -> value(id, Unit.serializer(), session(request.session).remove(request.path, request.ids))
                is Request.Move -> value(id, Unit.serializer(), session(request.session).move(request.path, request.ids, request.before))
                is Request.Run -> stream(id, Item.serializer(), session(request.session).run(request.path, request.command, request.arguments))
                is Request.Export -> value(id, String.serializer(), session(request.session).export(request.path))
                is Request.Counters -> stream(id, countersSerializer, session(request.session).counters(request.path, request.properties, request.filter))
                is Request.Link -> stream(id, LinkState.serializer(), session(request.session).link)
                is Request.Packs -> value(id, ListSerializer(Pack.serializer()), session(request.session).packs(store))
                is Request.Native -> value(id, NativeMenu.serializer().nullable, session(request.session).nativeMenu())
                is Request.SafeModeActive -> stream(id, Boolean.serializer(), safeMode(request.session).active)
                is Request.SafeModeActions -> stream(id, Int.serializer(), safeMode(request.session).actions)
                is Request.EnterSafeMode -> value(id, Boolean.serializer(), safeMode(request.session).enter())
                is Request.CommitSafeMode -> value(id, Unit.serializer(), safeMode(request.session).commit())
                is Request.RollbackSafeMode -> value(id, Unit.serializer(), safeMode(request.session).rollback())
                is Request.SafeModeStranded -> stream(id, Boolean.serializer(), safeMode(request.session).stranded)
                is Request.SettleSafeMode -> value(id, Unit.serializer(), safeMode(request.session).settle(request.keep))
                is Request.Exec -> value(id, ExecResult.serializer(), ssh(request.session).exec(request.command))
                is Request.Shell -> shell(id, ssh(request.session).shell(request.term, request.columns, request.rows))
                is Request.SshReachable -> value(id, SshReach.serializer(), ssh(request.session).reach(request.fresh))
                is Request.SftpList -> value(id, ListSerializer(RemoteFile.serializer()), files(request.session, request.own) { it.list(request.path) })
                is Request.SftpRead -> value(id, Base64Bytes, files(request.session, request.own) { it.read(request.path) })
                is Request.SftpWrite -> value(id, Unit.serializer(), files(request.session, request.own) { it.write(request.path, request.bytes) })
                is Request.SftpRemove -> value(id, Unit.serializer(), files(request.session, request.own) { it.remove(request.path) })
                is Request.SftpRename -> value(id, Unit.serializer(), files(request.session, request.own) { it.rename(request.from, request.to) })
                is Request.SftpMkdir -> value(id, Unit.serializer(), files(request.session, request.own) { it.mkdir(request.path) })
                is Request.ReadDocument -> value(id, String.serializer().nullable, store().read(request.name))
                is Request.WriteDocument -> value(id, Unit.serializer(), store().write(request.name, request.text))
                is Request.ReadSecret -> value(id, String.serializer().nullable, store().secret(request.key))
                is Request.WriteSecret -> value(id, Unit.serializer(), store().keep(request.key, request.value))
                is Request.DeleteSecret -> value(id, Unit.serializer(), store().forget(request.key))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeviceException) {
            post(FromGateway.Failed(id, e.category, e.message.orEmpty()))
        } catch (e: Exception) {
            post(FromGateway.Failed(id, ErrorCategory.General, e.message ?: e::class.simpleName.orEmpty()))
        } finally {
            withContext(NonCancellable) { lock.withLock { jobs.remove(id) } }
        }
    }

    private suspend fun opened(ask: Long, session: DeviceSession): Opened {
        val id = try {
            lock.withLock {
                currentCoroutineContext().ensureActive()
                (++last).also {
                    sessions[it] = session
                    connected[ask] = it
                }
            }
        } catch (e: CancellationException) {
            session.close()
            throw e
        }
        val ssh = session.ssh
        return Opened(id, session.device, session.capabilities, session.safeMode != null, ssh != null, ssh?.fileStore == true, ssh?.canForward != false, session.fileChannel != null)
    }

    private fun <T : Any> offered(part: T?, what: String): T = part ?: throw DeviceException(ErrorCategory.Unsupported, "this gateway cannot $what")

    private fun sweepable(addresses: List<String>): List<String> {
        if (addresses.size > MAX_SWEEP) throw DeviceException(ErrorCategory.ArgumentValue, "a sweep takes $MAX_SWEEP addresses at the most")
        return addresses
    }

    private suspend fun session(id: Long) = lock.withLock { sessions[id] } ?: throw DeviceException(ErrorCategory.MissingItem, "the gateway has no session $id")

    private suspend fun safeMode(id: Long) = session(id).safeMode ?: throw DeviceException(ErrorCategory.Unsupported, "the router has no Safe Mode")

    private suspend fun ssh(id: Long) = session(id).ssh ?: throw DeviceException(ErrorCategory.Unsupported, "the router has no SSH")

    private fun store() = store ?: throw DeviceException(ErrorCategory.Unsupported, "the gateway keeps no documents")

    private suspend fun <T> files(id: Long, own: Boolean, block: suspend (SftpClient) -> T): T {
        if (own) return block(session(id).fileChannel ?: throw DeviceException(ErrorCategory.Unsupported, "the router has no file channel"))
        val sftp = ssh(id).sftp()
        return try {
            block(sftp)
        } finally {
            sftp.close()
        }
    }

    private fun policy(ask: Long) = TrustPolicy(trust) { request ->
        val answer = CompletableDeferred<Boolean>()
        val question = lock.withLock { (++last).also { questions[it] = answer } }
        try {
            post(FromGateway.Question(question, ask, request.host, request.port, request.kind, request.fingerprint, request.previous))
            answer.await()
        } finally {
            withContext(NonCancellable) { lock.withLock { questions.remove(question) } }
        }
    }

    private suspend fun <T> value(id: Long, answer: KSerializer<T>, value: T) = post(FromGateway.Value(id, gatewayJson.encodeToJsonElement(answer, value)))

    private suspend fun <T> stream(id: Long, element: KSerializer<T>, flow: Flow<T>) {
        flow.collect { post(FromGateway.Next(id, gatewayJson.encodeToJsonElement(element, it))) }
        post(FromGateway.Done(id))
    }

    private suspend fun shell(id: Long, shell: ShellChannel) {
        lock.withLock { shells[id] = shell }
        try {
            post(FromGateway.Value(id, JsonNull))
            val buffer = ByteArray(8192)
            while (true) {
                val read = shell.read(buffer, 0, buffer.size)
                if (read < 0) break
                post(FromGateway.Next(id, gatewayJson.encodeToJsonElement(Base64Bytes, buffer.copyOf(read))))
            }
            post(FromGateway.Done(id))
        } finally {
            shell.close()
            withContext(NonCancellable) { lock.withLock { shells.remove(id) } }
        }
    }

    private suspend fun type(shell: ShellChannel, bytes: ByteArray) {
        try {
            shell.write(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            shell.close()
        }
    }

    private suspend fun post(message: FromGateway) {
        try {
            wire.send(gatewayJson.encodeToString(FromGateway.serializer(), message))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            wire.close()
            throw CancellationException("the browser is gone", e)
        }
    }
}

private const val MAX_SWEEP = 1024
