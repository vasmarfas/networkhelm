package com.vasmarfas.networkhelm.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vasmarfas.networkhelm.gateway.GatewayStore
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.serve
import com.vasmarfas.networkhelm.server.FileGatewayStore
import com.vasmarfas.networkhelm.server.Gateway
import com.vasmarfas.networkhelm.server.isLocal
import com.vasmarfas.networkhelm.sync.recordsFile
import com.vasmarfas.networkhelm.transport.TrustStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.net.BindException
import java.net.Inet4Address
import java.net.NetworkInterface

@Serializable
private class Opening(val open: Boolean = false, val port: Int = 8443, val public: Boolean = false, val code: Boolean = false)

class DesktopGateway(
    private val home: File,
    private val web: File?,
    private val storage: Storage,
    private val secrets: Secrets,
    private val trust: TrustStore,
    private val host: String = "0.0.0.0",
) : BrowserAccess {
    private val lock = Mutex()
    private var server: AutoCloseable? = null
    private var opening = read()

    private val stored = try {
        Gateway.account(home)
    } catch (e: SerializationException) {
        null
    }

    override var ready by mutableStateOf(stored != null)
        private set
    override var open by mutableStateOf(false)
        private set
    override var port by mutableStateOf(opening.port)
        private set
    override var public by mutableStateOf(opening.public)
        private set
    override var code by mutableStateOf(opening.code)
        private set
    override var otpauth by mutableStateOf(stored?.otpauth?.takeIf { opening.code || opening.public })
        private set
    override var addresses by mutableStateOf(emptyList<String>())
        private set
    override var certificate by mutableStateOf<String?>(null)
        private set
    override val webApp = web != null
    override var problem by mutableStateOf<AccessProblem?>(null)
        private set

    override suspend fun setPassword(password: String) = lock.withLock {
        withContext(Dispatchers.IO) { Gateway.setPassword(home, password) }
        ready = true
        otpauth = secret()
        // the sessions of the old password end with the gateway that holds them
        if (open) start()
    }

    override suspend fun newCode() = lock.withLock {
        otpauth = withContext(Dispatchers.IO) { Gateway.newCode(home) }.otpauth
        if (open) start()
    }

    override suspend fun change(port: Int, public: Boolean, code: Boolean) = lock.withLock {
        this.port = port
        this.public = public
        this.code = code
        otpauth = secret()
        save()
        if (open) start()
    }

    override suspend fun open() = lock.withLock {
        start()
        if (open) save()
    }

    override suspend fun close() = lock.withLock {
        withContext(Dispatchers.IO) { stop() }
        open = false
        addresses = emptyList()
        save()
    }

    override suspend fun resume() {
        if (opening.open && ready) open()
    }

    private suspend fun secret(): String? = if (!ready || !(code || public)) null else withContext(Dispatchers.IO) {
        (Gateway.account(home)?.takeIf { it.totp != null } ?: Gateway.newCode(home)).otpauth
    }

    private suspend fun start() {
        val port = port
        val public = public
        val code = code
        problem = null
        val started = withContext(Dispatchers.IO) {
            stop()
            try {
                val gateway = Gateway.open(home, public, code, password = null, announce = {})
                val fingerprint = Gateway.fingerprint(gateway.keyStore())
                val found = addresses(port)
                server = serve(gateway, host, port, web, trust) { session -> DesktopStore(storage, secrets, FileGatewayStore(home, session.master.sealing)) }
                Result.success(fingerprint to found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        started.onSuccess { (fingerprint, found) ->
            certificate = fingerprint
            addresses = found
        }.onFailure { e ->
            addresses = emptyList()
            problem = if (e is BindException) AccessProblem.PortTaken(port) else AccessProblem.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
        open = started.isSuccess
    }

    private fun stop() {
        server?.close()
        server = null
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        opening = Opening(open, port, public, code)
        home.mkdirs()
        File(home, "access.json").writeText(json.encodeToString(Opening.serializer(), opening))
    }

    private fun read(): Opening = try {
        File(home, "access.json").takeIf { it.isFile }?.let { json.decodeFromString(Opening.serializer(), it.readText()) } ?: Opening()
    } catch (e: SerializationException) {
        Opening()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun addresses(port: Int): List<String> = NetworkInterface.networkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses().toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .sortedBy {
                when {
                    it.startsWith("192.168.") -> 0
                    it.startsWith("10.") -> 1
                    isLocal(it) -> 2
                    else -> 3
                }
            }
            .map { "https://$it:$port" }
    }
}

internal class DesktopStore(private val storage: Storage, private val secrets: Secrets, private val own: GatewayStore) : GatewayStore {
    override suspend fun read(name: String) = if (name in shared) storage.read(name) else own.read(name)

    override suspend fun write(name: String, text: String) = if (name in shared) storage.write(name, text) else own.write(name, text)

    // the keys of synchronisation stay with the app: the page of the gateway works with its data and never synchronises
    override suspend fun secret(key: String) = if (key.startsWith(SYNC_SECRETS)) null else secrets.get(key)

    override suspend fun keep(key: String, value: String) = secrets.put(changeable(key), value)

    override suspend fun forget(key: String) = secrets.delete(changeable(key))

    private fun changeable(key: String) = key.also {
        if (it.startsWith(SYNC_SECRETS)) throw DeviceException(ErrorCategory.PermissionDenied, "the page of the gateway does not change the keys of synchronisation")
    }
}

// the browser edits the list of routers of the app and its records as another copy of the app would
private val shared = setOf(SavedRouters.FILE, recordsFile(SavedRouters.FILE))

private const val SYNC_SECRETS = "sync/"
