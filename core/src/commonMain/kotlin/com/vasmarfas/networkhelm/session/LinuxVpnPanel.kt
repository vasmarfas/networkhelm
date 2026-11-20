package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.duration
import com.vasmarfas.networkhelm.openwrt.quote
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// the panel of the VPN of a Linux host: its services and their live sessions, WireGuard, AmneziaWG, OpenVPN, IKEv2, ocserv and accel-ppp,
// with their rates, and the sessions the journals and the log of the collector keep. One read as root serves both live tables for a
// second. A peer of WireGuard or AmneziaWG is in a session while its last handshake is under 180 s old, and the session began with the
// first handshake after a longer break, which the panel knows only where it saw that break, its reads not more than 180 s apart
internal class LinuxVpnPanel(
    private val root: Root,
    private val tools: suspend () -> Set<String>,
    private val wireGuard: LinuxVpnWireGuard,
    private val amnezia: LinuxVpnWireGuard,
) {
    private class Live(val services: List<Item>, val sessions: List<Item>, val servers: List<OvpnConf>)

    private val lock = Mutex()
    private var last: Pair<TimeMark, Live>? = null
    private val counted = HashMap<String, Pair<Pair<Long, Long>, TimeMark>>()
    private val started = HashMap<String, Long>()
    private var seen: Pair<Long, Set<String>>? = null

    val tables: Map<String, HostTable> = mapOf(
        "vpn/service" to HostTable(SERVICE, { live().services }, present = ::present),
        "vpn/session" to HostTable(SESSION, { live().sessions }, commands = listOf("disconnect"), run = { _, arguments -> disconnect(arguments) }, present = ::present),
        "vpn/history" to HostTable(HISTORY, ::history, slow = true, present = ::present),
    )

    private suspend fun present() = tools().let { "wg" in it || "awg" in it || "openvpn" in it || "swanctl" in it || "ocserv" in it || "accel-cmd" in it }

    private suspend fun live(): Live = lock.withLock {
        last?.takeIf { it.first.elapsedNow() < FRESH }?.second ?: read().also { last = TimeSource.Monotonic.markNow() to it }
    }

    private suspend fun read(): Live {
        val awg = "awg" in tools()
        val output = root.masked("$WIREGUARD_STATE\n${if (awg) "$AMNEZIA_READ\n" else ""}$OPENVPN_STATE\n$IKE_STATE\n$OC_STATE\n$ACCEL_STATE")
        // the read of AmneziaWG has the marks of the one of WireGuard, so each is parsed apart
        val state = parseWireGuard(output.substringBefore(AWG_PART) + output.substringAfter(AWG_END, ""))
        val kinds = listOf(WIREGUARD to state) + listOfNotNull(if (awg) AMNEZIA to parseWireGuard(output.substringAfter(AWG_PART, "").substringBefore(AWG_END), AMNEZIA) else null)
        val servers = parseOpenVpn(output).servers
        // the connections the host makes as a client of another server are no service of its own
        val clients = output.substringAfter("--nh-ike-clients--\n", "").substringBefore("--nh-ike-conns--").lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val connections = output.substringAfter("--nh-ike-conns--\n", "").substringBefore("--nh-ike-sas--").lines().map { it.trim() }.filter { it.isNotEmpty() && it !in clients }
        val sas = parseIkeSas(output.substringAfter("--nh-ike-sas--\n", "").substringBefore("--nh-oc-users--")).filter { it.connection !in clients }
        val connected = parseOcUsers(output.substringAfter("--nh-oc-users--\n", "").substringBefore(ACCEL_MARK))
        val offset = output.lines().firstOrNull { it.startsWith("--nh-zone-- ") }?.removePrefix("--nh-zone-- ")?.trim().orEmpty()
        val now = state.now
        val before = seen?.takeIf { now != null && now - it.first <= LIVE_HANDSHAKE }?.second
        if (before == null) started.clear()
        val services = mutableListOf<Item>()
        val sessions = mutableListOf<Item>()
        val live = mutableSetOf<String>()
        for ((kind, host) in kinds) for (face in host.faces) {
            val running = host.live[face.name]
            val id = "${kind.path.substringAfter('/')}/${face.id}"
            val parts = face.peers()
            val names = parts.associate { face.peerKey(it) to it.value("#Name").orEmpty() }
            val off = parts.filter { it.off }.map { face.peerKey(it) }.toSet()
            val peers = running?.peers.orEmpty()
            var count = 0
            for ((key, fields) in peers) {
                if (key in off) continue
                val handshake = fields.getOrNull(5)?.toLongOrNull()?.takeIf { it > 0 } ?: continue
                if (now == null || now - handshake > LIVE_HANDSHAKE) continue
                val session = "$id/$key"
                live += session
                if (before?.contains(session) == false) started[session] = handshake
                count++
                val since = started[session]
                val (rx, tx) = fields.getOrElse(6) { "" } to fields.getOrElse(7) { "" }
                val (rxRate, txRate) = rate(session, rx, tx)
                sessions += Item(
                    session,
                    linkedMapOf(
                        "kind" to kind.title, "service" to face.name, "user" to names[key].orEmpty(), "public-key" to key,
                        "endpoint" to fields.getOrElse(3) { "" }.takeIf { it != "(none)" }.orEmpty(),
                        "address" to fields.getOrElse(4) { "" }.split(',').filter { it.endsWith("/32") || it.endsWith("/128") }.joinToString(", "),
                        "since" to since?.let { local(it, offset) }.orEmpty(), "uptime" to since?.let { duration(now - it) }.orEmpty(),
                        "rx" to rx, "tx" to tx, "rx-rate" to rxRate, "tx-rate" to txRate, "last-handshake" to duration((now - handshake).coerceAtLeast(0)),
                        "cipher" to "", "client" to "", "peer" to "${face.id}/$key",
                    ),
                )
            }
            val (rx, tx) = totals(peers.values.map { it.getOrElse(6) { "" } to it.getOrElse(7) { "" } }, running != null)
            val port = face.setting("listen-port").ifEmpty { running?.port.orEmpty() }
            services += service(id, kind.title, face.name, if (running == null) "inactive" else "active", port, "udp", face.setting("address"), count, rx, tx)
        }
        for (server in servers) {
            val clients = server.status?.joinToString("\n")?.let(::parseOpenVpnStatus).orEmpty()
            for (client in clients) {
                val real = client["Real Address"].orEmpty()
                val id = "openvpn/${server.name}/${client["Client ID"]?.ifEmpty { null } ?: real}"
                val since = client["Connected Since (time_t)"]?.toLongOrNull()
                val (rx, tx) = client["Bytes Received"].orEmpty() to client["Bytes Sent"].orEmpty()
                val (rxRate, txRate) = rate(id, rx, tx)
                live += id
                sessions += Item(
                    id,
                    linkedMapOf(
                        "kind" to "OpenVPN", "service" to server.name, "user" to client["Common Name"].orEmpty(), "public-key" to "", "endpoint" to real,
                        "address" to listOfNotNull(client["Virtual Address"], client["Virtual IPv6 Address"]).filter { it.isNotEmpty() }.joinToString(", "),
                        "since" to (since?.let { local(it, offset) } ?: client["Connected Since"].orEmpty()),
                        "uptime" to if (since != null && now != null) duration((now - since).coerceAtLeast(0)) else "",
                        "rx" to rx, "tx" to tx, "rx-rate" to rxRate, "tx-rate" to txRate, "last-handshake" to "", "cipher" to client["Data Channel Cipher"].orEmpty(),
                        "client" to "", "peer" to "",
                    ),
                )
            }
            val (rx, tx) = totals(clients.map { it["Bytes Received"].orEmpty() to it["Bytes Sent"].orEmpty() }, server.status != null)
            services += service(
                "openvpn/${server.name}", "OpenVPN", server.name, server.state, server.directive("port").orEmpty(), server.directive("proto").orEmpty(),
                server.directive("server").orEmpty(), clients.size, rx, tx,
            )
        }
        for (sa in sas) {
            val id = "ipsec/${sa.connection}/${sa.id}"
            val established = sa.values["established"]?.toLongOrNull()
            val (rx, tx) = sa.bytes("in") to sa.bytes("out")
            val (rxRate, txRate) = rate(id, rx, tx)
            live += id
            sessions += Item(
                id,
                linkedMapOf(
                    "kind" to sa.kind, "service" to sa.connection, "user" to sa.user, "public-key" to "", "endpoint" to sa.endpoint, "address" to sa.address,
                    "since" to if (established != null && now != null) local(now - established, offset) else "", "uptime" to established?.let(::duration).orEmpty(),
                    "rx" to rx, "tx" to tx, "rx-rate" to rxRate, "tx-rate" to txRate, "last-handshake" to "", "cipher" to sa.cipher, "client" to "", "peer" to "",
                ),
            )
        }
        for (connection in connections) {
            val own = sas.filter { it.connection == connection }
            val (rx, tx) = totals(own.map { it.bytes("in") to it.bytes("out") }, true)
            services += service("ipsec/$connection", own.firstOrNull()?.kind ?: "IKEv2", connection, "active", "500,4500", "udp", "", own.size, rx, tx)
        }
        for (user in connected.orEmpty()) {
            val id = "ocserv/${user["ID"].orEmpty()}"
            val since = user["raw_connected_at"]?.toLongOrNull()
            val (rx, tx) = user["RX"].orEmpty() to user["TX"].orEmpty()
            val (rxRate, txRate) = rate(id, rx, tx)
            live += id
            sessions += Item(
                id,
                linkedMapOf(
                    "kind" to "OpenConnect", "service" to "ocserv", "user" to user["Username"].orEmpty(), "public-key" to "", "endpoint" to user["Remote IP"].orEmpty(),
                    "address" to listOf("IPv4", "IPv6").mapNotNull { user[it]?.ifEmpty { null } }.joinToString(", "), "since" to since?.let { local(it, offset) }.orEmpty(),
                    "uptime" to if (since != null && now != null) duration((now - since).coerceAtLeast(0)) else "", "rx" to rx, "tx" to tx, "rx-rate" to rxRate,
                    "tx-rate" to txRate, "last-handshake" to "", "cipher" to user["DTLS cipher"].orEmpty().ifEmpty { user["TLS ciphersuite"].orEmpty() },
                    "client" to user["User-Agent"].orEmpty(), "peer" to "",
                ),
            )
        }
        connected?.let { users ->
            val (rx, tx) = totals(users.map { it["RX"].orEmpty() to it["TX"].orEmpty() }, true)
            val (tcp, udp) = output.lines().firstOrNull { it.startsWith("--nh-oc-ports--") }.orEmpty().removePrefix("--nh-oc-ports--").trim().split(' ').let {
                it.getOrElse(0) { "" } to it.getOrElse(1) { "" }
            }
            val protocols = listOfNotNull("tcp".takeIf { tcp.isNotEmpty() }, "udp".takeIf { udp.isNotEmpty() }).joinToString(",")
            services += service("ocserv", "OpenConnect", "ocserv", "active", listOf(tcp, udp).filter { it.isNotEmpty() }.distinct().joinToString(","), protocols, "", users.size, rx, tx)
        }
        val accel = if (ACCEL_MARK in output) parseAccelSessions(output.substringAfter(ACCEL_MARK)) else null
        for (session in accel.orEmpty()) {
            val id = "accel/${session["ifname"].orEmpty()}"
            val uptime = session["uptime-raw"]?.toLongOrNull()
            val (rx, tx) = session["rx-bytes-raw"].orEmpty() to session["tx-bytes-raw"].orEmpty()
            val (rxRate, txRate) = rate(id, rx, tx)
            live += id
            sessions += Item(
                id,
                linkedMapOf(
                    "kind" to session["type"].orEmpty().uppercase(), "service" to "accel-ppp", "user" to session["username"].orEmpty(), "public-key" to "",
                    "endpoint" to session["calling-sid"].orEmpty(), "address" to session["ip"].orEmpty(),
                    "since" to if (uptime != null && now != null) local(now - uptime, offset) else "", "uptime" to uptime?.let(::duration).orEmpty(), "rx" to rx, "tx" to tx,
                    "rx-rate" to rxRate, "tx-rate" to txRate, "last-handshake" to "", "cipher" to "", "client" to "", "peer" to "",
                ),
            )
        }
        accel?.let { all ->
            val (rx, tx) = totals(all.map { it["rx-bytes-raw"].orEmpty() to it["tx-bytes-raw"].orEmpty() }, true)
            services += service("accel-ppp", "accel-ppp", "accel-ppp", "active", "", all.mapNotNull { it["type"]?.ifEmpty { null } }.distinct().joinToString(","), "", all.size, rx, tx)
        }
        seen = now?.let { time -> time to live.filter { it.startsWith("wireguard/") || it.startsWith("amneziawg/") }.toSet() }
        started.keys.retainAll(live)
        counted.keys.retainAll(live + services.map { it.id })
        return Live(services, sessions, servers)
    }

    private fun service(id: String, kind: String, name: String, state: String, port: String, protocol: String, network: String, sessions: Int, rx: String, tx: String): Item {
        val (rxRate, txRate) = rate(id, rx, tx)
        return Item(
            id,
            linkedMapOf(
                "kind" to kind, "name" to name, "state" to state, "port" to port, "protocol" to protocol, "network" to network, "sessions" to "$sessions",
                "rx" to rx, "tx" to tx, "rx-rate" to rxRate, "tx-rate" to txRate,
            ),
        )
    }

    // the bytes of a service, which has none while it is not running
    private fun totals(counters: List<Pair<String, String>>, running: Boolean): Pair<String, String> =
        if (!running) "" to "" else "${counters.sumOf { it.first.toLongOrNull() ?: 0L }}" to "${counters.sumOf { it.second.toLongOrNull() ?: 0L }}"

    // bits per second since the read before, none for the first read and where a counter went back
    private fun rate(id: String, rx: String, tx: String): Pair<String, String> {
        val received = rx.toLongOrNull() ?: return "" to ""
        val sent = tx.toLongOrNull() ?: return "" to ""
        val before = counted[id]
        counted[id] = (received to sent) to TimeSource.Monotonic.markNow()
        val (old, at) = before ?: return "" to ""
        val seconds = at.elapsedNow().inWholeMilliseconds / 1000.0
        if (seconds <= 0 || received < old.first || sent < old.second) return "" to ""
        return "${((received - old.first) * 8 / seconds).toLong()}" to "${((sent - old.second) * 8 / seconds).toLong()}"
    }

    // a session of WireGuard or AmneziaWG ends with its peer turned off, or its client would come back at once; one of OpenVPN is killed
    // through the management interface of its server
    private suspend fun disconnect(arguments: Map<String, String>): List<Item> {
        val current = live()
        for (id in arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }) {
            val rest = id.substringAfter('/', "")
            when (id.substringBefore('/')) {
                "wireguard" -> wireGuard.setPeer(rest, mapOf("disabled" to "yes"))
                "amneziawg" -> amnezia.setPeer(rest, mapOf("disabled" to "yes"))
                "openvpn" -> kill(current, id)
                "ipsec" -> {
                    val unique = id.substringAfterLast('/').takeIf { current.sessions.any { it.id == id } && it.isNotEmpty() && it.all(Char::isDigit) }
                        ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no VPN session $id")
                    root.masked("swanctl --terminate --ike-id $unique --force >/dev/null")
                }
                "ocserv" -> {
                    val number = rest.takeIf { current.sessions.any { it.id == id } && it.isNotEmpty() && it.all(Char::isDigit) }
                        ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no VPN session $id")
                    root.masked("occtl disconnect id $number >/dev/null")
                }
                "accel" -> {
                    if (rest.isEmpty() || current.sessions.none { it.id == id }) throw DeviceException(ErrorCategory.MissingItem, "the host has no VPN session $id")
                    root.masked("accel-cmd terminate if ${quote(rest)} >/dev/null")
                }
                else -> throw DeviceException(ErrorCategory.MissingItem, "the host has no VPN session $id")
            }
        }
        lock.withLock { last = null }
        return emptyList()
    }

    // client-kill by the id of the client where the status gives one, kill by its address where it does not
    private suspend fun kill(live: Live, id: String) {
        val session = live.sessions.firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no VPN session $id")
        val server = live.servers.firstOrNull { it.name == session["service"] } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no VPN session $id")
        val client = id.substringAfterLast('/')
        val answer = root.manage(server, if (client.all(Char::isDigit)) "client-kill $client" else "kill ${session["endpoint"]}")
        if (answer.lines().none { it.startsWith("SUCCESS:") && ("client-kill" in it || "killed" in it) }) {
            throw DeviceException(ErrorCategory.Api, answer.lines().firstOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim() ?: "OpenVPN did not end the session $id")
        }
    }

    // the journals of OpenVPN and charon and the log the collector keeps, where the host has them; the log names the peers of WireGuard and
    // AmneziaWG by their keys
    private suspend fun history(): List<Item> {
        val parts = root.masked("$OPENVPN_HISTORY; echo --nh-section--; $CHARON_HISTORY; echo --nh-section--; $COLLECTED").split("--nh-section--\n")
        val offset = parts[0].trim()
        val installed = tools()
        val names = listOf(Triple(wireGuard, "wg", "wireguard"), Triple(amnezia, "awg", "amneziawg")).filter { it.second in installed }.flatMap { (module, _, prefix) ->
            module.state().peers().map { "$prefix/${it["interface"].orEmpty().substringBefore('#')}/${it["public-key"]}" to it["name"].orEmpty() }
        }.toMap()
        val journals = parseOpenVpnJournal(parts.getOrElse(1) { "" }, offset) + parseCharonJournal(parts.getOrElse(2) { "" }, offset)
        return mergeHistory(journals, parseCollected(parts.getOrElse(3) { "" }, offset, names))
    }

    private companion object {
        const val LIVE_HANDSHAKE = 180
        val FRESH = 1.seconds
        val SERVICE = listOf("kind", "name", "state", "port", "protocol", "network", "sessions", "rx", "tx", "rx-rate", "tx-rate")
        val SESSION = listOf(
            "kind", "service", "user", "public-key", "endpoint", "address", "since", "uptime", "rx", "tx", "rx-rate", "tx-rate", "last-handshake", "cipher",
            "client", "peer",
        )
        val HISTORY = listOf("kind", "service", "user", "endpoint", "address", "connected", "disconnected", "duration", "reason", "rx", "tx", "start", "seconds")
    }
}

// the sessions of accel-cmd show sessions in the columns asked for: a table with | between its cells and its head on the first line
internal fun parseAccelSessions(text: String): List<Map<String, String>> {
    val lines = text.lines().filter { '|' in it }
    val head = lines.firstOrNull()?.split('|')?.map { it.trim() } ?: return emptyList()
    return lines.drop(1).map { line -> head.zip(line.split('|').map { it.trim() }).toMap() }
}

private const val ACCEL_MARK = "--nh-accel--\n"
private const val AWG_PART = "--nh-awg-part--"
private const val AWG_END = "--nh-awg-end--"

// the read of AmneziaWG between marks of its own
private val AMNEZIA_READ = "echo $AWG_PART\n${wireGuardState(AMNEZIA)}\necho $AWG_END"

// the sessions of accel-ppp with the columns the panel shows, in seconds and bytes
private const val ACCEL_STATE = "if command -v accel-cmd >/dev/null 2>&1; then echo --nh-accel--; " +
    "accel-cmd show sessions ifname,username,calling-sid,ip,type,state,uptime-raw,rx-bytes-raw,tx-bytes-raw 2>/dev/null; fi; true"
