package com.vasmarfas.networkhelm.ui.connect

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.vasmarfas.networkhelm.app.Access
import com.vasmarfas.networkhelm.app.BOOK_FORMAT
import com.vasmarfas.networkhelm.app.DeviceKind
import com.vasmarfas.networkhelm.app.Book
import com.vasmarfas.networkhelm.app.BookFormatException
import com.vasmarfas.networkhelm.app.SavedRouter
import com.vasmarfas.networkhelm.app.SavedRouters
import com.vasmarfas.networkhelm.app.SealedBook
import com.vasmarfas.networkhelm.app.Secrets
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.app.formatAddress
import com.vasmarfas.networkhelm.app.isMacAddress
import com.vasmarfas.networkhelm.app.parseAddress
import com.vasmarfas.networkhelm.app.parseCsv
import com.vasmarfas.networkhelm.app.passwordKey
import com.vasmarfas.networkhelm.app.savedRouterOf
import com.vasmarfas.networkhelm.app.seal
import com.vasmarfas.networkhelm.app.snmpPrivacyKey
import com.vasmarfas.networkhelm.app.sshKeyKey
import com.vasmarfas.networkhelm.app.sshPassphraseKey
import com.vasmarfas.networkhelm.app.unseal
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.offline.openConfig
import com.vasmarfas.networkhelm.openwrt.OpenWrtLogin
import com.vasmarfas.networkhelm.routeros.LoginTimeoutException
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.api.ApiTrapException
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.Login
import com.vasmarfas.networkhelm.session.recognize
import com.vasmarfas.networkhelm.snmp.AuthProtocol
import com.vasmarfas.networkhelm.snmp.PrivProtocol
import com.vasmarfas.networkhelm.snmp.SnmpUser
import com.vasmarfas.networkhelm.ssh.Hop
import com.vasmarfas.networkhelm.ssh.Jump
import com.vasmarfas.networkhelm.ssh.JumpedSession
import com.vasmarfas.networkhelm.ssh.SshAuth
import com.vasmarfas.networkhelm.ssh.sshKeyEncrypted
import com.vasmarfas.networkhelm.transport.BrowserBlockedException
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.TrustRequest
import com.vasmarfas.networkhelm.transport.openTcp
import com.vasmarfas.networkhelm.ui.files.FileExchange
import com.vasmarfas.networkhelm.ui.files.LocalFile
import com.vasmarfas.networkhelm.winbox.WinBoxBookException
import com.vasmarfas.networkhelm.winbox.WinBoxSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

sealed interface Problem {
    data object Address : Problem

    data object Port : Problem

    data object Rejected : Problem

    class Login(val message: String) : Problem

    class Refused(val host: String, val port: Int) : Problem

    class Timeout(val host: String) : Problem

    class UnknownHost(val host: String) : Problem

    class Unreachable(val host: String) : Problem

    data object LocalNetwork : Problem

    // no common TLS: API-SSL without a certificate offers only anonymous TLS, which Android lacks
    class Handshake(val host: String) : Problem

    class Unsupported(val message: String) : Problem

    // a page in a browser got no answer it may read: the device is out of reach, or the browser refused it
    class Browser(val host: String, val routerOs: Boolean) : Problem

    data object MacTelnetUnsupported : Problem

    data object MacTelnetNoInterface : Problem
    data object OwnMac : Problem

    data object Tcp8291Unsupported : Problem

    data object NoRest : Problem

    class Other(val host: String, val message: String) : Problem

    class SavedList(val message: String) : Problem

    class Secrets(val message: String) : Problem

    data object BookPassword : Problem

    data object BookInvalid : Problem

    class BookFile(val message: String) : Problem

    data object WinBoxFile : Problem

    class ThroughMissing(val address: String) : Problem

    data object ThroughCircle : Problem

    class NotConfig(val file: String) : Problem

    // an SNMP agent answers a wrong community with silence, the same as no agent at all
    class NoSnmp(val host: String, val v3: Boolean) : Problem

    data object SnmpThrough : Problem

    class NoUpnp(val host: String) : Problem

    data object UpnpThrough : Problem
}

sealed interface Sealing {
    data object Export : Sealing

    class Open(val book: SealedBook) : Sealing
}

class TrustQuestion(val request: TrustRequest, private val answer: CompletableDeferred<Boolean>) {
    fun answer(trusted: Boolean) = answer.complete(trusted)
}

@Stable
class Renewal {
    var failure by mutableStateOf<String?>(null)
        internal set
    var busy by mutableStateOf(false)
        internal set
    internal var answer = CompletableDeferred<String?>()

    fun change(password: String) {
        answer.complete(password)
    }

    fun skip() {
        answer.complete(null)
    }
}

@Stable
class TrustQuestions {
    private val lock = Mutex()
    var question by mutableStateOf<TrustQuestion?>(null)
        private set

    suspend fun ask(request: TrustRequest): Boolean = lock.withLock {
        val answer = CompletableDeferred<Boolean>()
        question = TrustQuestion(request, answer)
        try {
            answer.await()
        } finally {
            question = null
        }
    }
}

@Stable
class ConnectState(
    private val services: Services,
    private val saved: SavedRouters,
    private val scope: CoroutineScope,
    private val questions: TrustQuestions,
) {
    val secretProtection get() = services.secrets.protection

    val address = TextFieldState()
    val port = TextFieldState()
    val user = TextFieldState("admin")
    val password = TextFieldState()
    val group = TextFieldState()
    val comment = TextFieldState()
    var kind by mutableStateOf(DeviceKind.RouterOs)
        private set
    var protocol by mutableStateOf(
        when {
            services.standalone -> Access.RestSsl
            services.connectTcp8291 != null -> Access.Tcp8291
            else -> Access.ApiSsl
        },
    )
    var detected by mutableStateOf<DeviceKind?>(null)
        private set
    private var kindPicked = false
    private var protocolPicked = false
    private var proposed = false
    var encoding by mutableStateOf(ApiEncoding.Utf8)
    var remember by mutableStateOf(false)
    var connecting by mutableStateOf<String?>(null)
        private set
    var renewing by mutableStateOf<Renewal?>(null)
        private set
    var problem by mutableStateOf<Problem?>(null)
    var imported by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var shut by mutableStateOf(if (services.standalone) BROWSER_SHUT else emptySet())
        private set
    val standalone: Boolean get() = services.standalone
    var sealing by mutableStateOf<Sealing?>(null)
    var winBox by mutableStateOf<WinBoxBook?>(null)
    var key by mutableStateOf<Pair<String, ByteArray>?>(null)
    val passphrase = TextFieldState()
    val keyEncrypted: Boolean get() = key?.second?.let(::sshKeyEncrypted) == true
    var agent by mutableStateOf(false)
    var via by mutableStateOf("")
    val agentAvailable: Boolean get() = services.sshAgent
    var snmpAuth by mutableStateOf(AuthProtocol.Sha1)
    var snmpPriv by mutableStateOf(PrivProtocol.Aes)
    val privacy = TextFieldState()
    val snmpPrivacy: String? get() = privacy.text.toString().takeIf { protocol == Access.Snmp3 && password.text.isNotEmpty() && it.isNotEmpty() }
    val networkInterfaces: List<String> = services.networkInterfaces?.invoke().orEmpty()
    var selectedInterface by mutableStateOf(networkInterfaces.firstOrNull().orEmpty())
    val typedMac: Boolean get() = services.typedMac
    val ownMac = TextFieldState()
    private val chosenPort: Int? get() = port.text.toString().toIntOrNull()?.takeIf { it in 1..65535 }
    val portInvalid: Boolean get() = port.text.isNotEmpty() && chosenPort == null
    val portHint: Int get() = parseAddress(address.text.toString())?.port ?: protocol.defaultPort

    fun fullAddress(): String {
        val typed = address.text.toString().trim()
        val target = parseAddress(typed)
        val chosen = chosenPort
        return if (target == null || chosen == null || protocol == Access.MacTelnet) typed else formatAddress(target.host, chosen)
    }

    init {
        if (services.typedMac) scope.launch { services.storage.read(OWN_MAC)?.let(ownMac::setTextAndPlaceCursorAtEnd) }
        scope.launch {
            snapshotFlow { address.text.toString() }.collect { text ->
                val isMac = isMacAddress(text.trim())
                if (isMac && services.connectMacTelnet != null) {
                    kind = DeviceKind.RouterOs
                    protocol = Access.MacTelnet
                } else if (protocol == Access.MacTelnet && !isMac) {
                    protocol = firstOpen(DeviceKind.RouterOs)
                }
            }
        }
        scope.launch {
            snapshotFlow { protocol }.collect { chosen ->
                if (chosen == Access.Snmp) {
                    if (password.text.isEmpty()) {
                        password.setTextAndPlaceCursorAtEnd(DEFAULT_COMMUNITY)
                        proposed = true
                    }
                } else if (proposed) {
                    // the community that was put in is not a password for the other protocols
                    if (password.text.toString() == DEFAULT_COMMUNITY) password.setTextAndPlaceCursorAtEnd("")
                    proposed = false
                }
            }
        }
    }

    private var attempt: Job? = null
    private var declined = false

    fun fill(router: SavedRouter): Job {
        address.setTextAndPlaceCursorAtEnd(router.address)
        port.setTextAndPlaceCursorAtEnd("")
        user.setTextAndPlaceCursorAtEnd(router.user)
        password.setTextAndPlaceCursorAtEnd("")
        key = null
        passphrase.setTextAndPlaceCursorAtEnd("")
        agent = router.agent
        via = router.via
        snmpAuth = router.snmpAuth
        snmpPriv = router.snmpPriv
        privacy.setTextAndPlaceCursorAtEnd("")
        group.setTextAndPlaceCursorAtEnd(router.group)
        comment.setTextAndPlaceCursorAtEnd(router.comment)
        kind = router.protocol.kind
        protocol = router.protocol
        kindPicked = true
        protocolPicked = true
        encoding = router.encoding
        remember = false
        problem = null
        return scope.launch {
            secretly { secrets ->
                val stored = secrets.get(passwordKey(router.address, router.user))
                val storedKey = secrets.get(sshKeyKey(router.address, router.user))
                val storedPassphrase = secrets.get(sshPassphraseKey(router.address, router.user))
                val storedPrivacy = secrets.get(snmpPrivacyKey(router.address, router.user))
                if (address.text.toString() == router.address && user.text.toString() == router.user) {
                    if (stored != null) password.setTextAndPlaceCursorAtEnd(stored)
                    key = storedKey?.let(::sshKeyOf)
                    if (storedPassphrase != null) passphrase.setTextAndPlaceCursorAtEnd(storedPassphrase)
                    if (storedPrivacy != null) privacy.setTextAndPlaceCursorAtEnd(storedPrivacy)
                    remember = stored != null || storedKey != null
                }
            }
        }
    }

    fun fillAddress(text: String) {
        address.setTextAndPlaceCursorAtEnd(text)
        port.setTextAndPlaceCursorAtEnd("")
        if (isMacAddress(text.trim())) {
            kind = DeviceKind.RouterOs
            protocol = Access.MacTelnet
        }
        problem = null
    }

    fun pick(kind: DeviceKind) {
        kindPicked = true
        this.kind = kind
        if (protocol !in kind.protocols) {
            protocolPicked = false
            protocol = firstOpen(kind)
            port.setTextAndPlaceCursorAtEnd("")
        }
    }

    fun pick(protocol: Access) {
        kindPicked = true
        protocolPicked = true
        kind = protocol.kind
        if (protocol != this.protocol) port.setTextAndPlaceCursorAtEnd("")
        this.protocol = protocol
    }

    // MAC-Telnet is not probed and takes a MAC address, so it is never the answer for an address that gave none
    private fun offered(access: Access) = access != Access.MacTelnet && (access != Access.Tcp8291 || services.connectTcp8291 != null)

    private fun firstOpen(kind: DeviceKind) =
        kind.protocols.firstOrNull { offered(it) && it.defaultPort !in shut } ?: kind.protocols.firstOrNull(::offered) ?: kind.protocols.first()

    suspend fun probe() {
        if (services.standalone) return
        try {
            snapshotFlow { address.text.toString() to port.text.toString() }.collectLatest { (text, own) ->
                shut = emptySet()
                detected = null
                if (text.isBlank()) {
                    kindPicked = false
                    protocolPicked = false
                }
                if (isMacAddress(text.trim())) return@collectLatest
                val target = parseAddress(text)?.takeIf { it.port == null && own.isEmpty() } ?: return@collectLatest
                delay(700)
                val ports = Access.entries.filter { it != Access.Snmp && it != Access.Snmp3 && it != Access.Upnp && it != Access.MacTelnet }.map { it.defaultPort }.distinct()
                val answered = coroutineScope { ports.map { port -> async { port to answers(target.host, port) } }.awaitAll() }
                shut = answered.filter { it.second == false }.map { it.first }.toSet()
                val found = recognize(target.host, answered.filter { it.second == true }.map { it.first }.toSet(), PROBE_TIMEOUT)?.let { DeviceKind.of(it) }
                detected = found
                if (found != null && !kindPicked) kind = found
                if (!protocolPicked) protocol = firstOpen(kind)
            }
        } finally {
            shut = emptySet()
        }
    }

    private suspend fun answers(host: String, port: Int): Boolean? = try {
        openTcp(host, port, PROBE_TIMEOUT).close()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: DeviceException) {
        if (e.category == ErrorCategory.Unsupported) null else false
    } catch (e: Exception) {
        false
    }

    fun connect(onConnected: (DeviceSession, String, RouterOsLogin?) -> Unit) {
        if (portInvalid) {
            problem = Problem.Port
            return
        }
        val typed = fullAddress()
        val isMac = isMacAddress(typed)
        val target = parseAddress(typed)
        if (target == null && !isMac) {
            problem = Problem.Address
            return
        }
        val access = protocol
        if (access == Access.MacTelnet && !isMac) {
            problem = Problem.Address
            return
        }
        if (access == Access.MacTelnet && services.connectMacTelnet == null) {
            problem = Problem.MacTelnetUnsupported
            return
        }
        if (access == Access.MacTelnet && services.typedMac && !isMacAddress(ownMac.text.toString().trim())) {
            problem = Problem.OwnMac
            return
        }
        if (access == Access.MacTelnet && services.networkInterfaces?.invoke()?.isEmpty() == true) {
            problem = Problem.MacTelnetNoInterface
            return
        }
        if (access == Access.Tcp8291 && services.connectTcp8291 == null) {
            problem = Problem.Tcp8291Unsupported
            return
        }
        if (services.standalone && access.defaultPort in BROWSER_SHUT) {
            problem = Problem.Unsupported("a page in a browser can send HTTP requests only")
            return
        }
        val host = target?.host ?: typed
        val port = target?.port ?: access.defaultPort
        val name = user.text.toString()
        val secret = password.text.toString()
        val chosen = key
        val phrase = passphrase.text.toString().takeIf { keyEncrypted }
        val byAgent = agent && services.sshAgent
        val through = via
        val hidden = snmpPrivacy
        val snmp = SnmpUser(name, snmpAuth.takeIf { secret.isNotEmpty() }, secret, snmpPriv.takeIf { hidden != null }, hidden.orEmpty())
        problem = null
        declined = false
        connecting = typed
        attempt?.cancel()
        val job = scope.launch {
            var jump: Jump? = null
            try {
                if ((access == Access.Snmp || access == Access.Snmp3) && through.isNotEmpty()) throw ThroughFailure(Problem.SnmpThrough)
                if (access == Access.Upnp && port == Access.Upnp.defaultPort && through.isNotEmpty()) throw ThroughFailure(Problem.UpnpThrough)
                val trust = TrustPolicy(services.trust, ::ask)
                jump = if (through.isEmpty()) null else Jump.open(hops(through), trust, scope + Dispatchers.Default)
                val reach = jump?.let { trust.through(it.leads) } ?: trust
                val toHost = if (jump == null) host else Jump.LOOPBACK
                val toPort = jump?.tunnel(host, port) ?: port
                val sshBeside = access.routerOs != null || access == Access.OpenWrtUbus || access == Access.OpenWrtUbusSsl
                val sshPort = if (jump == null || !sshBeside) 22 else jump.tunnel(host, 22)
                val routerOs = access.routerOs
                val webPort = if (jump == null || routerOs == null) null else jump.tunnel(host, 80)
                val nativePort = if (jump == null || routerOs == null) null else jump.tunnel(host, 8291)
                val openWrt = access.openWrt
                fun loginWith(password: String) = routerOs?.let { RouterOsLogin(toHost, name, password, it, toPort, encoding, chosen?.second, byAgent, sshPort, phrase, webPort, nativePort) }
                val login = loginWith(secret)
                val session = when {
                    access == Access.MacTelnet -> {
                        val connector = services.connectMacTelnet
                            ?: throw DeviceException(ErrorCategory.Unsupported, "MAC-Telnet")
                        connector(typed, name, secret, if (services.typedMac) ownMac.text.toString().trim() else selectedInterface.ifEmpty { null }, encoding, scope + Dispatchers.Default)
                    }
                    access == Access.Tcp8291 -> {
                        val connector = services.connectTcp8291
                            ?: throw DeviceException(ErrorCategory.Unsupported, "TCP 8291")
                        connector(toHost, toPort, name, secret, encoding, scope + Dispatchers.Default)
                    }
                    login != null -> services.connect(login, reach, scope + Dispatchers.Default)
                    openWrt != null -> services.connectOpenWrt(OpenWrtLogin(toHost, name, secret, openWrt, toPort, chosen?.second, byAgent, sshPort, phrase), reach)
                    else -> services.connectLogin(other(access, toHost, toPort, name, secret, chosen?.second, byAgent, phrase, snmp), reach)
                }
                if (access == Access.MacTelnet && services.typedMac) services.storage.write(OWN_MAC, ownMac.text.toString().trim())
                val renewed = try {
                    val new = if (session.passwordExpired) renew(session, secret) else null
                    remembered(typed, name, new ?: secret, chosen, phrase, hidden)
                    new
                } catch (e: Throwable) {
                    session.close()
                    throw e
                }
                onConnected(jump?.let { JumpedSession(session, it) } ?: session, typed, (renewed?.let { loginWith(it) } ?: login).takeIf { jump == null })
            } catch (e: CancellationException) {
                jump?.close()
                throw e
            } catch (e: ThroughFailure) {
                problem = e.problem
            } catch (e: Exception) {
                jump?.close()
                if (isActive) problem = problemOf(e, access, host, port)
            }
        }
        attempt = job
        job.invokeOnCompletion { if (attempt === job) connecting = null }
    }

    private suspend fun renew(session: DeviceSession, old: String): String? {
        val renewal = Renewal().also { renewing = it }
        try {
            while (true) {
                renewal.busy = false
                val new = renewal.answer.await() ?: return null
                renewal.busy = true
                renewal.failure = null
                renewal.answer = CompletableDeferred()
                try {
                    session.changePassword(old, new)
                    return new
                } catch (e: DeviceException) {
                    if (e.category == ErrorCategory.ConnectionLost) throw e
                    renewal.failure = e.message
                }
            }
        } finally {
            renewing = null
        }
    }

    // the job ends only when its socket call does, so the window does not wait for it
    fun stop() {
        attempt?.cancel()
        attempt = null
        connecting = null
    }

    private suspend fun hops(via: String): List<Hop> {
        val chain = mutableListOf<SavedRouter>()
        var next = via
        while (next.isNotEmpty()) {
            val entry = saved.routers.value.firstOrNull { it.address == next } ?: throw ThroughFailure(Problem.ThroughMissing(next))
            if (entry in chain) throw ThroughFailure(Problem.ThroughCircle)
            chain += entry
            next = entry.via
        }
        return chain.asReversed().map { entry -> hop(entry) }
    }

    private suspend fun hop(entry: SavedRouter): Hop {
        val target = parseAddress(entry.address) ?: throw ThroughFailure(Problem.ThroughMissing(entry.address))
        val sshAlone = entry.protocol == Access.Ssh || entry.protocol == Access.OpenWrtSsh
        var password = ""
        var key: ByteArray? = null
        var phrase: String? = null
        secretly { secrets ->
            password = secrets.get(passwordKey(entry.address, entry.user)).orEmpty()
            key = secrets.get(sshKeyKey(entry.address, entry.user))?.let(::sshKeyOf)?.second
            phrase = secrets.get(sshPassphraseKey(entry.address, entry.user))
        }
        return Hop(target.host, if (sshAlone) target.port ?: 22 else 22, entry.user, SshAuth.of(password, key, entry.agent && services.sshAgent, phrase))
    }

    private class ThroughFailure(val problem: Problem) : Exception()

    private fun other(access: Access, host: String, port: Int, user: String, secret: String, key: ByteArray?, agent: Boolean, phrase: String?, snmp: SnmpUser): Login = when (access) {
        Access.Opnsense -> Login.Opnsense(host, user, secret, port)
        Access.Pfsense -> Login.Pfsense(host, user, secret, port)
        Access.KeeneticSsl, Access.Keenetic -> Login.Keenetic(host, user, secret, access == Access.KeeneticSsl, port)
        Access.Vyos -> Login.Vyos(host, secret, port)
        Access.Snmp -> Login.Snmp(host, secret, port)
        Access.Snmp3 -> Login.Snmp(host, "", port, snmp)
        Access.Upnp -> Login.Upnp(host, port, user, secret)
        else -> Login.Ssh(host, port, user, secret, key, agent, phrase)
    }

    suspend fun save() {
        if (portInvalid) {
            problem = Problem.Port
            return
        }
        val router = SavedRouter(fullAddress(), user.text.toString(), protocol, group.text.toString().trim(), comment.text.toString().trim(), encoding, agent, via, snmpAuth, snmpPriv)
        if (parseAddress(router.address) == null && !isMacAddress(router.address)) {
            problem = Problem.Address
            return
        }
        try {
            saved.save(router)
            remembered(router.address, router.user, password.text.toString(), key, passphrase.text.toString().takeIf { keyEncrypted }, snmpPrivacy)
        } catch (e: IllegalStateException) {
            problem = Problem.SavedList(saved.failure?.message ?: e.message.orEmpty())
        }
    }

    suspend fun remove(router: SavedRouter) {
        try {
            saved.remove(router)
            secretly { secrets ->
                secrets.delete(passwordKey(router.address, router.user))
                secrets.delete(sshKeyKey(router.address, router.user))
                secrets.delete(sshPassphraseKey(router.address, router.user))
                secrets.delete(snmpPrivacyKey(router.address, router.user))
            }
        } catch (e: IllegalStateException) {
            problem = Problem.SavedList(saved.failure?.message ?: e.message.orEmpty())
        }
    }

    suspend fun openFiles(files: List<LocalFile>, onOpened: (DeviceSession, String, RouterOsLogin?) -> Unit) {
        for (file in files) {
            val session = withContext(Dispatchers.Default) { openConfig(file.name, file.read()) }
            if (session == null) problem = Problem.NotConfig(file.name) else onOpened(session, file.name, null)
        }
    }

    suspend fun import(files: List<LocalFile>) {
        if (files.isEmpty()) return
        var taken = 0
        var skipped = 0
        val sessions = mutableListOf<WinBoxSession>()
        try {
            for (file in files) {
                val bytes = file.read()
                val book = try {
                    services.readWinBoxBook?.invoke(bytes)
                } catch (e: WinBoxBookException) {
                    problem = Problem.WinBoxFile
                    continue
                }
                if (book != null) {
                    sessions += book
                    continue
                }
                if (file.name.endsWith(".cdb", ignoreCase = true) || file.name.endsWith(".wbx", ignoreCase = true)) {
                    problem = Problem.WinBoxFile
                    continue
                }
                // Excel writes UTF-8 with a byte order mark at the start, or cp1251 on a Russian Windows
                val text = ApiEncoding.Utf8.decode(bytes).removePrefix("﻿")
                if (text.trimStart().startsWith("{")) {
                    val book = sealedBook(text)
                    if (book == null) problem = Problem.BookInvalid else sealing = Sealing.Open(book)
                    continue
                }
                val rows = parseCsv(text)
                val header = rows.firstOrNull()?.map { it.trim().lowercase() } ?: continue
                for (row in rows.drop(1)) {
                    fun cell(name: String) = header.indexOf(name).takeIf { it >= 0 }?.let { row.getOrNull(it)?.trim() }
                    val address = cell("address")?.takeIf { parseAddress(it) != null }
                    if (address == null) {
                        skipped++
                        continue
                    }
                    val user = cell("user").orEmpty().ifEmpty { "admin" }
                    val kind = cell("protocol")?.let { name -> Access.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                    val known = saved.routers.value.firstOrNull { it.address == address && it.user == user } ?: SavedRouter(address, user)
                    saved.save(known.copy(protocol = kind ?: known.protocol, group = cell("group") ?: known.group, comment = cell("comment") ?: known.comment))
                    cell("password")?.let { secret -> secretly { secrets -> secrets.put(passwordKey(address, user), secret) } }
                    taken++
                }
            }
        } catch (e: IllegalStateException) {
            problem = Problem.SavedList(saved.failure?.message ?: e.message.orEmpty())
        }
        if (sessions.isNotEmpty()) {
            winBox = winBoxBook(sessions)
            if (taken + skipped == 0) return
        }
        imported = taken to skipped
    }

    private fun winBoxBook(sessions: List<WinBoxSession>): WinBoxBook {
        val known = saved.routers.value
        val rows = sessions.mapNotNull { session ->
            savedRouterOf(session)?.let { router -> WinBoxBook.Row(session, router, known.any { it.address == router.address && it.user == router.user }) }
        }
        return WinBoxBook(rows, sessions.size - rows.size)
    }

    suspend fun importWinBox(book: WinBoxBook) {
        winBox = null
        var taken = 0
        try {
            for (row in book.rows.filter { it.ticked }) {
                val router = row.router
                val known = saved.routers.value.firstOrNull { it.address == router.address && it.user == router.user }
                saved.save(known?.copy(group = router.group.ifEmpty { known.group }, comment = router.comment.ifEmpty { known.comment }) ?: router)
                if (book.passwords && row.session.password.isNotEmpty()) secretly { secrets -> secrets.put(passwordKey(router.address, router.user), row.session.password) }
                taken++
            }
        } catch (e: IllegalStateException) {
            problem = Problem.SavedList(saved.failure?.message ?: e.message.orEmpty())
        }
        imported = taken to book.rows.size - taken + book.unusable
    }

    suspend fun export(password: String, exchange: FileExchange) {
        sealing = null
        val routers = saved.routers.value
        val passwords = HashMap<String, String>()
        secretly { secrets ->
            for (router in routers) {
                for (key in listOf(passwordKey(router.address, router.user), snmpPrivacyKey(router.address, router.user))) {
                    secrets.get(key)?.let { passwords[key] = it }
                }
            }
        }
        val plain = Json.encodeToString(Book.serializer(), Book(routers, passwords)).encodeToByteArray()
        try {
            val sealed = withContext(Dispatchers.Default) { seal(plain, password) }
            exchange.save("networkhelm-routers.json", Json.encodeToString(SealedBook.serializer(), sealed).encodeToByteArray())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = Problem.BookFile(e.message.orEmpty())
        }
    }

    suspend fun open(book: SealedBook, password: String) {
        sealing = null
        val plain = try {
            withContext(Dispatchers.Default) { unseal(book, password) }
        } catch (e: BookFormatException) {
            problem = Problem.BookInvalid
            return
        }
        if (plain == null) {
            problem = Problem.BookPassword
            return
        }
        val contents = try {
            Json.decodeFromString(Book.serializer(), plain.decodeToString())
        } catch (e: SerializationException) {
            problem = Problem.BookInvalid
            return
        }
        try {
            for (router in contents.routers) saved.save(router)
            secretly { secrets -> for ((key, value) in contents.passwords) secrets.put(key, value) }
            imported = contents.routers.size to 0
        } catch (e: IllegalStateException) {
            problem = Problem.SavedList(saved.failure?.message ?: e.message.orEmpty())
        }
    }

    private fun sealedBook(text: String): SealedBook? {
        if (!text.trimStart().startsWith("{")) return null
        return try {
            Json.decodeFromString(SealedBook.serializer(), text).takeIf { it.format == BOOK_FORMAT }
        } catch (e: SerializationException) {
            null
        }
    }

    private suspend fun remembered(address: String, user: String, password: String, key: Pair<String, ByteArray>?, phrase: String?, privacy: String?) = secretly { secrets ->
        val passwordKey = passwordKey(address, user)
        val sshKey = sshKeyKey(address, user)
        val phraseKey = sshPassphraseKey(address, user)
        val privacyKey = snmpPrivacyKey(address, user)
        if (remember) secrets.put(passwordKey, password) else secrets.delete(passwordKey)
        if (remember && key != null) secrets.put(sshKey, key.first + ":" + Base64.encode(key.second)) else secrets.delete(sshKey)
        if (remember && key != null && phrase != null) secrets.put(phraseKey, phrase) else secrets.delete(phraseKey)
        if (remember && privacy != null) secrets.put(privacyKey, privacy) else secrets.delete(privacyKey)
    }

    private fun sshKeyOf(stored: String) = stored.substringBeforeLast(':') to Base64.decode(stored.substringAfterLast(':'))

    private suspend fun secretly(block: suspend (Secrets) -> Unit) {
        try {
            block(services.secrets)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = Problem.Secrets(e.message.orEmpty())
        }
    }

    private suspend fun ask(request: TrustRequest) = questions.ask(request).also { if (!it) declined = true }

    private fun problemOf(e: Exception, access: Access, host: String, port: Int): Problem {
        if (access == Access.MacTelnet && services.connectMacTelnet == null) return Problem.MacTelnetUnsupported
        if (generateSequence<Throwable>(e) { it.cause }.any { it is BrowserBlockedException }) return Problem.Browser(host, access.routerOs != null)
        // RouterOS 6 has no REST, and its web server answers a POST with 501
        if (access.routerOs?.rest == true && e is ApiTrapException) return Problem.NoRest
        if (e is DeviceException) {
            when (e.category) {
                ErrorCategory.PermissionDenied -> return if (declined) Problem.Rejected else Problem.Login(e.message.orEmpty())
                ErrorCategory.Unsupported -> return Problem.Unsupported(e.message.orEmpty())
                else -> Unit
            }
        }
        // a connection the platform does not let out only times out, so the permission is looked at first
        if (!services.localNetwork()) return Problem.LocalNetwork
        val causes = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it::class.simpleName }.toList()
        if ((access == Access.Snmp || access == Access.Snmp3) && (e is DeviceException && e.category == ErrorCategory.ConnectionLost || "PortUnreachableException" in causes)) {
            return Problem.NoSnmp(host, access == Access.Snmp3)
        }
        if (access == Access.Upnp && (e is DeviceException && e.category in setOf(ErrorCategory.ConnectionLost, ErrorCategory.MissingItem) || "PortUnreachableException" in causes)) {
            return Problem.NoUpnp(host)
        }
        return when {
            "SSLHandshakeException" in causes -> Problem.Handshake(host)
            "ConnectException" in causes -> Problem.Refused(host, port)
            "SocketTimeoutException" in causes || generateSequence<Throwable>(e) { it.cause }.any { it is LoginTimeoutException } -> Problem.Timeout(host)
            "UnknownHostException" in causes -> Problem.UnknownHost(host)
            "NoRouteToHostException" in causes -> Problem.Unreachable(host)
            else -> Problem.Other(host, e.message ?: e::class.simpleName.orEmpty())
        }
    }
}

private const val PROBE_TIMEOUT = 1_500
private const val DEFAULT_COMMUNITY = "public"
private const val OWN_MAC = "own-mac"

private val BROWSER_SHUT = setOf(8728, 8729, 8291, 20561, 22, 161, 1900)
