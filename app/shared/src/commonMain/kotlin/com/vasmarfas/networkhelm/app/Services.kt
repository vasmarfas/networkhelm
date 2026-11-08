package com.vasmarfas.networkhelm.app

import com.vasmarfas.networkhelm.discovery.Found
import com.vasmarfas.networkhelm.discovery.MndpSighting
import com.vasmarfas.networkhelm.discovery.scan as sweep
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.openwrt.OpenWrtLogin
import com.vasmarfas.networkhelm.openwrt.OpenWrtProtocol
import com.vasmarfas.networkhelm.openwrt.connectOpenWrt as connectOverUbus
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.connectRouterOs
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.Login
import com.vasmarfas.networkhelm.session.TextStore
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.TrustStore
import com.vasmarfas.networkhelm.winbox.WinBoxSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface Storage : TextStore

class MemoryStorage : Storage {
    private val lock = Mutex()
    private val documents = HashMap<String, String>()

    override suspend fun read(name: String) = lock.withLock { documents[name] }

    override suspend fun write(name: String, text: String) = lock.withLock { documents[name] = text }
}

class Services(
    val storage: Storage,
    val trust: TrustStore,
    val neighbors: Flow<MndpSighting>?,
    val secrets: Secrets = MemorySecrets(),
    val refreshNeighbors: (suspend () -> Unit)? = null,
    val localNetwork: () -> Boolean = { true },
    val browserAccess: BrowserAccess? = null,
    val scan: ((List<String>) -> Flow<Found>)? = { sweep(it) },
    val sshAgent: Boolean = false,
    val connectOpenWrt: suspend (OpenWrtLogin, TrustPolicy) -> DeviceSession = { login, trust ->
        if (login.protocol == OpenWrtProtocol.Ssh) throw DeviceException(ErrorCategory.Unsupported, "SSH is not available on this platform")
        connectOverUbus(login, trust)
    },
    val connectLogin: suspend (Login, TrustPolicy) -> DeviceSession = { login, trust -> login.connect(trust) },
    val networkInterfaces: (() -> List<String>)? = null,
    // link: the label from networkInterfaces, or the MAC of this device that the user typed where typedMac is set
    val connectMacTelnet: (suspend (mac: String, user: String, pass: String, link: String?, encoding: ApiEncoding, scope: CoroutineScope) -> DeviceSession)? = null,
    // iOS does not tell apps the MAC of the device, and the router sends its answers of MAC-Telnet to the MAC the client names
    val typedMac: Boolean = false,
    val connectTcp8291: (suspend (host: String, port: Int, user: String, pass: String, encoding: ApiEncoding, scope: CoroutineScope) -> DeviceSession)? = null,
    val readWinBoxBook: ((ByteArray) -> List<WinBoxSession>?)? = null,
    val releases: (suspend () -> String?)? = null,
    val changelog: (suspend (version: String) -> String?)? = null,
    val standalone: Boolean = false,
    val connect: suspend (RouterOsLogin, TrustPolicy, CoroutineScope) -> DeviceSession = { login, trust, scope -> connectRouterOs(login, trust, scope) },
)
