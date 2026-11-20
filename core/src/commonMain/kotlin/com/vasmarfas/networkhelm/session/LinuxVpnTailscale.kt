package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Tailscale of a Linux host as tailscaled has it: the node itself with the state of its backend and its settings, and the peers of its
// tailnet. No login is made here but by an auth key, which goes to tailscale up through a file of its own, never on a command line,
// with the server of Tailscale or another one of its protocol, such as Headscale.
// Bringing the node up, its settings and choosing an exit node go under the guard, as the routes they take or the shields they raise may
// hold the way this session comes by
internal class LinuxVpnTailscale(private val ssh: SshSession, private val root: Root, private val tools: suspend () -> Set<String>, private val guard: LinuxGuard) {
    val tables: Map<String, HostTable> = mapOf(
        "vpn/tailscale" to HostTable(
            TS_FIELDS, { parseTailscale(root.masked(TS_STATE)) }, arguments = { TS_ARGUMENTS }, set = ::set,
            commands = listOf("up", "down", "logout", "exit-node", "no-exit-node"), run = ::act, present = { "tailscale" in tools() },
        ),
    )

    private suspend fun act(command: String, arguments: Map<String, String>): List<Item> {
        when (command) {
            "up" -> up(arguments["auth-key"].orEmpty())
            "down" -> {
                keepSession(ssh, TS_DEVICE)
                root.masked("tailscale down")
            }
            "logout" -> {
                keepSession(ssh, TS_DEVICE)
                root.masked("tailscale logout >&2")
            }
            "exit-node" -> {
                val peer = parseTailscale(root.masked("tailscale status --json")).firstOrNull { it.id == arguments["numbers"] && it["kind"] == "peer" }
                    ?: throw DeviceException(ErrorCategory.MissingItem, "the tailnet has no peer ${arguments["numbers"]}")
                val address = peer["addresses"].orEmpty().substringBefore(',').trim()
                guard.guarded(LinuxGuard.Backup("vpn", "true", "tailscale set --exit-node=")) { root.masked("tailscale set --exit-node=${quote(address)} >&2") }
            }
            "no-exit-node" -> root.masked("tailscale set --exit-node= >&2")
        }
        return emptyList()
    }

    // the settings of the node through tailscale set, which a guard sets back as the read showed them, one it did not show left as it is;
    // an auth key or another server logs the node in
    private suspend fun set(id: String?, changes: Map<String, String?>) {
        val key = changes["auth-key"].orEmpty()
        val server = changes["login-server"].orEmpty()
        if (key.isNotEmpty() || server.isNotEmpty()) up(key, server)
        val given = TS_PREFS.filter { it in changes }
        if (given.isEmpty()) return
        for (field in given) {
            val text = changes[field].orEmpty()
            val valid = when (field) {
                "hostname" -> text.isEmpty() || TS_HOSTNAME.matches(text)
                "advertise-routes" -> text.split(TS_LIST).filter { it.isNotEmpty() }.all(TS_ROUTE::matches)
                "netfilter-mode" -> text in TS_NETFILTER.values
                "operator" -> text.isEmpty() || TS_USER.matches(text)
                else -> true
            }
            if (!valid) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $text is not a value it takes")
        }
        val before = parseTailscale(root.masked(TS_STATE)).first()
        val back = given.filter { it in TS_CLEARABLE || before[it].orEmpty().isNotEmpty() }
        val restore = if (back.isEmpty()) "true" else "tailscale set ${tsFlags(back) { before[it] }}"
        guard.guarded(LinuxGuard.Backup("vpn", "true", restore)) { root.masked("tailscale set ${tsFlags(given) { changes[it] }} >&2") }
    }

    // a node logged in comes up as it was set; a new one takes the key, which a file of mode 600 hands to tailscale and which goes at once,
    // and the server it logs in to where one is given
    private suspend fun up(key: String, server: String = "") {
        if (key.any { it.isWhitespace() }) throw DeviceException(ErrorCategory.ArgumentValue, "auth-key: a key of Tailscale holds no blank")
        if (server.isNotEmpty() && !TS_SERVER.matches(server)) throw DeviceException(ErrorCategory.ArgumentValue, "login-server: $server is not a value it takes")
        val login = if (server.isEmpty()) "" else " --login-server=${quote(server)}"
        val command = if (key.isEmpty()) {
            "tailscale up$login --timeout=30s >&2"
        } else {
            "umask 077; f=\$(mktemp) && cat > \"\$f\" && tailscale up$login --auth-key=\"file:\$f\" --timeout=30s >&2; s=\$?; rm -f \"\$f\"; exit \$s"
        }
        guard.guarded(LinuxGuard.Backup("vpn", "true", "tailscale down")) { if (key.isEmpty()) root.masked(command) else root.masked(command, "$key\n") }
    }
}

// the node and its peers from tailscale status --json: the node with the state of its backend, its tailnet, the URL of a login waiting
// and its settings from tailscale debug prefs after the mark, each peer with its addresses, whether it is online and how it is reached,
// directly or by a relay of DERP
internal fun parseTailscale(text: String): List<Item> {
    fun json(part: String) = try {
        Json.parseToJsonElement(part.substring(part.indexOf('{').coerceAtLeast(0)).ifBlank { "{}" }) as? JsonObject
    } catch (e: SerializationException) {
        null
    }
    val status = json(text.substringBefore(TS_PREFS_MARK))?.takeIf { it.isNotEmpty() } ?: return listOf(Item("self", linkedMapOf("kind" to "self", "state" to "NoState")))
    val prefs = json(text.substringAfter(TS_PREFS_MARK, "")) ?: JsonObject(emptyMap())
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun JsonObject.list(key: String) = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().joinToString(", ")
    fun row(id: String, kind: String, peer: JsonObject) = Item(
        id,
        linkedMapOf(
            "kind" to kind, "name" to peer.text("HostName"), "dns-name" to peer.text("DNSName").removeSuffix("."), "addresses" to peer.list("TailscaleIPs"), "os" to peer.text("OS"),
            "online" to peer.text("Online"), "active" to peer.text("Active"),
            "connection" to peer.text("CurAddr").ifEmpty { peer.text("Relay").ifEmpty { null }?.let { "relay $it" }.orEmpty() },
            "rx" to peer.text("RxBytes"), "tx" to peer.text("TxBytes"), "last-seen" to peer.text("LastSeen").takeIf { !it.startsWith("0001-") }.orEmpty(),
            "exit-node" to peer.text("ExitNode"), "exit-node-option" to peer.text("ExitNodeOption"),
        ),
    )
    val self = status["Self"] as? JsonObject ?: JsonObject(emptyMap())
    val tailnet = (status["CurrentTailnet"] as? JsonObject)?.text("Name").orEmpty()
    val routes = (prefs["AdvertiseRoutes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    // NoSNAT and NoStatefulFiltering say the opposite of the flags of tailscale set
    fun negated(key: String) = when (prefs.text(key)) {
        "true" -> "false"
        "false" -> "true"
        else -> ""
    }
    val settings = mapOf(
        "accept-routes" to prefs.text("RouteAll"), "accept-dns" to prefs.text("CorpDNS"), "ssh" to prefs.text("RunSSH"), "shields-up" to prefs.text("ShieldsUp"),
        "hostname" to prefs.text("Hostname"), "advertise-routes" to routes.filter { it !in TS_DEFAULT_ROUTES }.joinToString(", "),
        "advertise-exit-node" to if (prefs.isEmpty()) "" else "${routes.any { it in TS_DEFAULT_ROUTES }}", "exit-node-allow-lan-access" to prefs.text("ExitNodeAllowLANAccess"),
        "auto-update" to (prefs["AutoUpdate"] as? JsonObject)?.text("Apply").orEmpty(), "webclient" to prefs.text("RunWebClient"), "snat-subnet-routes" to negated("NoSNAT"),
        "stateful-filtering" to negated("NoStatefulFiltering"), "netfilter-mode" to TS_NETFILTER[prefs.text("NetfilterMode")].orEmpty(), "operator" to prefs.text("OperatorUser"),
        "login-server" to prefs.text("ControlURL"),
    )
    val node = row("self", "self", self).let {
        Item(it.id, it.values + mapOf("state" to status.text("BackendState"), "tailnet" to tailnet, "version" to status.text("Version"), "auth-url" to status.text("AuthURL")) + settings)
    }
    val peers = (status["Peer"] as? JsonObject).orEmpty().values.mapNotNull { it as? JsonObject }.map { row(it.text("PublicKey"), "peer", it) }
    return listOf(node) + peers.sortedBy { it["name"] }
}

// the flags of tailscale set for the settings given: a flag as true or false, a list with commas
private fun tsFlags(fields: List<String>, value: (String) -> String?) = fields.joinToString(" ") { field ->
    val text = value(field).orEmpty()
    "--$field=" + quote(if (field in TS_FLAGS) "${text in ValueType.Flag.on}" else text.split(TS_LIST).filter { it.isNotEmpty() }.joinToString(","))
}

private const val TS_DEVICE = "tailscale0"
private const val TS_PREFS_MARK = "--nh-prefs--"

// the state of tailscaled and the settings of the node, which tailscale debug prefs has
private const val TS_STATE = "tailscale status --json 2>/dev/null; echo $TS_PREFS_MARK; tailscale debug prefs 2>/dev/null; true"

// the settings of the node by the names of the flags of tailscale set
private val TS_PREFS = listOf(
    "accept-routes", "accept-dns", "ssh", "shields-up", "hostname", "advertise-routes", "advertise-exit-node", "exit-node-allow-lan-access", "auto-update", "webclient",
    "snat-subnet-routes", "stateful-filtering", "netfilter-mode", "operator",
)
private val TS_FLAGS = setOf(
    "accept-routes", "accept-dns", "ssh", "shields-up", "advertise-exit-node", "exit-node-allow-lan-access", "auto-update", "webclient", "snat-subnet-routes",
    "stateful-filtering",
)

// the settings an empty value sets as well, which the guard puts back empty
private val TS_CLEARABLE = setOf("hostname", "advertise-routes", "operator")

// NetfilterMode of the prefs by its number
private val TS_NETFILTER = mapOf("0" to "off", "1" to "nodivert", "2" to "on")
private val TS_DEFAULT_ROUTES = setOf("0.0.0.0/0", "::/0")
private val TS_HOSTNAME = Regex("[A-Za-z0-9-]{1,63}")
private val TS_ROUTE = Regex("[0-9A-Fa-f.:]+/\\d{1,3}")
private val TS_LIST = Regex("[,;\\s]+")
private val TS_SERVER = Regex("https?://[A-Za-z0-9.:\\[\\]/_-]+")
private val TS_USER = Regex("[a-z_][a-z0-9_.-]{0,31}")

private val TS_FIELDS = listOf(
    "kind", "name", "dns-name", "addresses", "os", "online", "active", "connection", "rx", "tx", "last-seen", "exit-node", "exit-node-option", "state", "tailnet",
    "version", "auth-url", "login-server",
) + TS_PREFS
private val TS_ARGUMENTS = listOf(
    Argument("auth-key", ValueType.Secret, optional = true),
    Argument("login-server", ValueType.Text(), optional = true, placeholder = "https://headscale.example.net"),
) + TS_PREFS.map {
    when (it) {
        in TS_FLAGS -> Argument(it, ValueType.YesNo, optional = true)
        "netfilter-mode" -> Argument(it, ValueType.Enumeration(closed = true), TS_NETFILTER.values.toList(), optional = true)
        else -> Argument(it, ValueType.Text(), optional = true)
    }
}
