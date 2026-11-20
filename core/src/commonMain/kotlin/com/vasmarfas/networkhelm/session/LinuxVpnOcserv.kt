package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.nettools.Ipv4
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ocserv, the server of OpenConnect, of a Linux host: its settings in /etc/ocserv/ocserv.conf, a certificate for the name clients reach it
// by, which they pin, and the users of its file of ocpasswd, whose passwords go to ocpasswd as its input and whose hashes the read never
// shows. A changed file goes through ocserv --test-config and comes back when that fails; a running server takes it with occtl reload,
// or a restart where its ports or network changed
internal class LinuxVpnOcserv(private val ssh: SshSession, private val root: Root, private val tools: suspend () -> Set<String>, guard: LinuxGuard) {
    private val nat = LinuxVpnNat(root, tools, guard)

    val tables: Map<String, HostTable> = mapOf(
        "vpn/ocserv" to HostTable(
            OC_FIELDS, { listOfNotNull(state().row()) }, settings = true, slow = true, arguments = { OC_ARGUMENTS }, set = ::set, commands = listOf("up", "down"),
            run = { command, _ -> bring(command) }, present = { "ocserv" in tools() },
        ),
        "vpn/ocserv/server" to HostTable(
            OC_SERVER_ARGUMENTS.map { it.name }, { listOf(proposal()) }, settings = true, arguments = { OC_SERVER_ARGUMENTS }, commands = listOf("create"),
            run = { _, values -> create(values) }, present = { "ocserv" in tools() },
        ),
    )

    suspend fun state() = parseOcserv(root.masked(OCSERV_STATE))

    // a new name for the server makes a certificate for it, which the settings then point to
    private suspend fun set(id: String?, changes: Map<String, String?>) {
        val host = state()
        if (!host.found) throw DeviceException(ErrorCategory.MissingItem, "the host has no $CONF")
        val fields = changes.filterKeys { it in OC_SETTINGS }.mapValues { it.value.orEmpty() }.filterNot { it.key == "camouflage-secret" && it.value.isEmpty() }
        fields.forEach { (field, value) -> if (value.isNotEmpty()) ocCheck(field, value) }
        val name = changes["server-name"]?.ifEmpty { null }?.also { if (!OC_HOST.matches(it)) throw DeviceException(ErrorCategory.ArgumentValue, "server-name: $it is not a value it takes") }
        if (fields.isNotEmpty() || name != null) {
            if (name != null) certificate(name)
            val before = root.masked("cat $CONF")
            val text = OcText(before)
            for ((field, value) in fields) text.put(field, value)
            if (name != null) {
                text.set("server-cert", listOf("server-cert = $CERT"))
                text.set("server-key", listOf("server-key = $KEY"))
            }
            if (text.text() != before) {
                root.write(CONF, text.text(), "ocserv --test-config -c $CONF 2>&1", failed = ::ocFailed)
                if (host.state == "active") root.masked(if (name != null || fields.keys.any { it in RESTART }) "systemctl restart ocserv" else "occtl reload >/dev/null")
            }
        }
        changes["autostart"]?.let { root.masked("systemctl ${if (it in ValueType.Flag.on) "enable" else "disable"} ocserv") }
        val natOn = changes["nat"]?.let { it in ValueType.Flag.on }
        val after = state()
        when {
            natOn != null -> natFor(host, after, natOn)
            "ocserv" in host.nat && fields.keys.any { it in RESTART } -> natFor(host, after, true)
            fields.keys.any { it == "tcp-port" || it == "udp-port" } -> nat.open("ocserv", after.ports())
        }
    }

    // NAT by the networks of the clients, as their devices vpns0, vpns1 come and go: through ufw or firewalld where one keeps the rules,
    // else by an addition to the unit of ocserv. The ports of the server are opened apart from it, as the NAT of a server made before
    // held them under its own mark
    private suspend fun natFor(before: OcservState, after: OcservState, on: Boolean) {
        val manager = nat.manager()
        val systemd = "systemctl" in tools()
        if (on && manager == null && !systemd) throw noNat("ocserv")
        if ("ocserv" in before.nat) {
            if (manager != null) nat.set(manager, "ocserv", before.networks(), emptyList(), false, policy = true)
            if (systemd) root.natUnit(OC_UNIT, OC_HOOK, null)
        }
        if (on) {
            nat.forward(after.networks().any { ':' in it })
            if (manager != null) {
                nat.set(manager, "ocserv", after.networks(), emptyList(), true, policy = true)
            } else {
                root.natUnit(OC_UNIT, OC_HOOK, deviceNat("ocserv", after.networks(), after.value("device").ifEmpty { "vpns" }, "iptables-save" in tools()))
            }
        }
        nat.open("ocserv", after.ports())
    }

    // what a new server takes unless the user says otherwise: the address of the interface of the default route for clients and its
    // certificate, the first port of 443, 8443 and from 20000 on that no socket of TCP or UDP holds but ocserv itself, the first network
    // 10.N.0.0/24 nothing of the host overlaps, DNS and the default route for clients, NAT, a start at boot and a first user
    private suspend fun proposal(): Item {
        val host = state()
        val scan = ssh.scan(root)
        // /proc/net/tcp has the form of /proc/net/udp
        val taken = scan.ports + udpPorts(ssh.exec("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null").output) -
            setOfNotNull(host.value("tcp-port").toIntOrNull()?.takeIf { host.state == "active" })
        return Item(
            "",
            linkedMapOf(
                "client-endpoint" to scan.address, "port" to "${(sequenceOf(443, 8443) + (20000..65535).asSequence()).first { it !in taken }}", "autostart" to "true",
                "network" to freeNetwork(scan.networks)?.cidr.orEmpty(), "dns" to CLIENT_DNS, "routes" to "default", "nat" to "${"systemctl" in tools() || nat.manager() != null}",
                "user" to generateSequence(1) { it + 1 }.map { "client$it" }.first { name -> host.users.none { it.first == name } },
            ),
        )
    }

    // a server in one step on the configuration the package brought, which keeps its user, socket and the other settings of the
    // distribution: a certificate for the name or address clients reach it by, the auth of a file of ocpasswd, its port for TCP and
    // UDP, network, DNS and routes, through ocserv --test-config; then NAT, the port opened, a start at boot, a start that is waited
    // for, and last the first user, so that a server that did not start leaves no user in the way of the next try. The server, pin,
    // login and password come back with the command line of OpenConnect
    private suspend fun create(values: Map<String, String>): List<Item> {
        val host = state()
        if (!host.found) throw DeviceException(ErrorCategory.MissingItem, "the host has no $CONF")
        val endpoint = values["client-endpoint"].orEmpty().ifEmpty { localAddress(ssh) }
        val user = values["user"].orEmpty()
        val password = values["password"].orEmpty()
        if (!OC_HOST.matches(endpoint)) throw DeviceException(ErrorCategory.ArgumentValue, "client-endpoint: $endpoint is not a value it takes")
        val port = values["port"].orEmpty()
        val fields = mapOf("tcp-port" to port, "udp-port" to port, "network" to values["network"].orEmpty(), "dns" to values["dns"].orEmpty(), "routes" to values["routes"].orEmpty())
            .filterValues { it.isNotEmpty() }
        fields.forEach { (field, value) -> ocCheck(field, value) }
        if (user.isNotEmpty()) {
            if (!VPN_USER.matches(user)) throw DeviceException(ErrorCategory.ArgumentValue, "user: $user is not a value it takes")
            if (password.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
            if (host.users.any { it.first == user }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has a VPN user $user already")
        }
        val natOn = values["nat"] in ValueType.Flag.on
        if (natOn && "systemctl" !in tools() && nat.manager() == null) throw noNat("ocserv")
        certificate(endpoint)
        val passwd = host.passwd ?: PASSWD
        root.masked("umask 077; [ -e ${quote(passwd)} ] || : > ${quote(passwd)}")
        val text = OcText(root.masked("cat $CONF"))
        text.set("auth", listOf("auth = \"plain[passwd=$passwd]\""))
        text.set("server-cert", listOf("server-cert = $CERT"))
        text.set("server-key", listOf("server-key = $KEY"))
        for ((field, value) in fields) text.put(field, value)
        root.write(CONF, text.text(), "ocserv --test-config -c $CONF 2>&1", failed = ::ocFailed)
        val after = state()
        natFor(host, after, natOn)
        root.masked("systemctl ${if (values["autostart"] in ValueType.Flag.on) "enable" else "disable"} ocserv")
        start()
        if (user.isEmpty()) return listOf(Item("ocserv", mapOf("name" to "ocserv")))
        val secret = password.ifEmpty { root.newSecret() }
        addUser(user, secret, false)
        return listOf(Item("ocserv", mapOf("name" to "ocserv", "conf" to login(after, user, secret))))
    }

    // a start that is waited for, as ocserv that cannot take its port stops a moment after systemd started it
    private suspend fun start() {
        val result = root.exec("systemctl restart ocserv; sleep 5; if systemctl is-active -q ocserv; then exit 0; fi; journalctl -u ocserv -n 12 --no-pager -o cat; exit 1")
        if (result.exitStatus != 0) throw DeviceException(ErrorCategory.Api, "ocserv did not start: ${hidden(result.output.trim().ifEmpty { result.error.trim() })}")
    }

    private suspend fun bring(command: String): List<Item> {
        root.masked(if (command == "up") "systemctl start ocserv" else "systemctl stop ocserv")
        return emptyList()
    }

    // a certificate of its own for the name or address clients reach the server by, ten years long; OpenConnect pins its key
    private suspend fun certificate(name: String) {
        val san = if (Ipv4.parse(name) != null || ':' in name) "IP:$name" else "DNS:$name"
        root.masked(
            "umask 077; command -v openssl >/dev/null || { echo 'the host has no openssl' >&2; exit 1; }; openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 " +
                "-nodes -days 3650 -subj ${quote("/CN=$name")} -addext ${quote("subjectAltName=$san")} -keyout $KEY.new -out $CERT.new 2>/dev/null && chmod 644 $CERT.new && " +
                "mv -f $KEY.new $KEY && mv -f $CERT.new $CERT",
        )
    }

    // the users of the file of ocpasswd that auth = "plain[passwd=...]" names, by name and whether they are locked
    suspend fun users(): List<Pair<String, Boolean>> = state().users

    suspend fun plain(): Boolean = state().passwd != null

    suspend fun addUser(name: String, password: String, disabled: Boolean) {
        val file = passwd()
        if (password.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "password: a value is needed")
        if (password.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
        root.masked("ocpasswd -c ${quote(file)} ${quote(name)} >/dev/null", "$password\n$password\n")
        if (disabled) root.masked("ocpasswd -c ${quote(file)} -l ${quote(name)}")
    }

    // a new password unlocks the user in ocpasswd, so a user that stays off is locked again; one turned off loses its sessions
    suspend fun setUser(name: String, password: String?, disabled: Boolean?) {
        val file = passwd()
        val off = disabled ?: state().users.firstOrNull { it.first == name }?.second ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no VPN user $name")
        password?.ifEmpty { null }?.let {
            if (it.any { char -> char == '\n' || char == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
            root.masked("ocpasswd -c ${quote(file)} ${quote(name)} >/dev/null", "$it\n$it\n")
        }
        root.masked("ocpasswd -c ${quote(file)} ${if (off) "-l" else "-u"} ${quote(name)}")
        if (off) root.masked("occtl disconnect user ${quote(name)} >/dev/null 2>&1; true")
    }

    suspend fun removeUsers(names: List<String>) {
        val file = passwd()
        root.masked(names.joinToString("; ") { "ocpasswd -c ${quote(file)} -d ${quote(it)} && { occtl disconnect user ${quote(it)} >/dev/null 2>&1; true; }" })
    }

    // the server, its pin and the login of a user, with the command line of OpenConnect that takes them
    suspend fun credentials(name: String): List<Item> = listOf(Item("", mapOf("conf" to login(state(), name, null))))

    // the password, which ocpasswd keeps only as a hash, where the user was just made
    private suspend fun login(host: OcservState, name: String, password: String?): String {
        val server = host.name.ifEmpty { localAddress(ssh) }
        val port = host.value("tcp-port").ifEmpty { "443" }
        val at = if (':' in server) "[$server]:$port" else "$server:$port"
        val pin = host.pin.ifEmpty { null }?.let { "pin-sha256:$it" }
        val command = listOfNotNull("openconnect --protocol=anyconnect --user=$name", pin?.let { "--servercert $it" }, at).joinToString(" ")
        return listOfNotNull("ocserv: OpenConnect", "server: $at", pin?.let { "pin: $it" }, "login: $name", password?.let { "password: $it" }, command).joinToString("\n")
    }

    private suspend fun passwd() = state().passwd ?: throw DeviceException(ErrorCategory.Unsupported, "ocserv takes no users of its own: its auth is not plain")
}

// what the host has of ocserv at one read: its settings line by line, the state of its service, the users of its file of ocpasswd, the name
// in its certificate and the pin of its key, and whether ufw or firewalld keeps NAT for its clients
internal class OcservState(
    val found: Boolean, val settings: List<Pair<String, String>>, val state: String, val autostart: Boolean, val passwd: String?, val users: List<Pair<String, Boolean>>,
    val name: String, val pin: String, val nat: Set<String>,
) {
    fun value(key: String) = settings.firstOrNull { it.first == key }?.second.orEmpty()

    fun values(key: String) = settings.filter { it.first == key }.map { it.second }

    fun network() = value("ipv4-network").let { network -> if ('/' in network) network else Ipv4.parseSubnet("$network ${value("ipv4-netmask")}")?.cidr ?: network }

    fun networks() = listOf(network(), value("ipv6-network")).filter { it.isNotEmpty() }

    fun ports() = listOfNotNull(value("tcp-port").ifEmpty { null }?.let { "$it/tcp" }, value("udp-port").ifEmpty { null }?.let { "$it/udp" })

    fun row(): Item? = if (!found) null else Item(
        "ocserv",
        linkedMapOf(
            "state" to state, "autostart" to "$autostart", "tcp-port" to value("tcp-port"), "udp-port" to value("udp-port"), "network" to network(),
            "ipv6-network" to value("ipv6-network"), "dns" to values("dns").joinToString(", "), "routes" to values("route").joinToString(", "),
            "no-routes" to values("no-route").joinToString(", "), "default-domain" to value("default-domain"), "max-clients" to value("max-clients"),
            "max-same-clients" to value("max-same-clients"), "keepalive" to value("keepalive"), "dpd" to value("dpd"), "mobile-dpd" to value("mobile-dpd"),
            "idle-timeout" to value("idle-timeout"), "mobile-idle-timeout" to value("mobile-idle-timeout"), "isolate-workers" to value("isolate-workers"),
            "cisco-client-compat" to value("cisco-client-compat"), "banner" to value("banner").removeSurrounding("\""), "camouflage" to value("camouflage"),
            "camouflage-secret" to "", "server-name" to name, "server-cert" to value("server-cert"), "server-key" to value("server-key"), "pin" to pin,
            "users" to "${users.size}", "nat" to "${"ocserv" in nat}",
        ),
    )
}

// the read: the settings with the secret of camouflage masked on the host, the users of the file of ocpasswd without their hashes, the
// name and pin of the certificate
internal fun parseOcserv(text: String): OcservState {
    var head = listOf<String>()
    val settings = mutableListOf<Pair<String, String>>()
    val users = mutableListOf<Pair<String, Boolean>>()
    val nat = mutableSetOf<String>()
    var passwd: String? = null
    var name = ""
    var pin = ""
    var section = ""
    for (line in text.lines()) {
        val mark = line.substringBefore(' ')
        val rest = line.substringAfter(' ', "").trim()
        when {
            mark == "--nh-ocserv--" -> {
                head = rest.split(' ')
                section = "conf"
            }
            mark == "--nh-ocserv-users--" -> {
                passwd = rest
                section = "users"
            }
            mark == "--nh-ocserv-name--" -> name = rest.substringAfter("CN=", "").substringBefore(',').trim()
            mark == "--nh-ocserv-pin--" -> pin = rest
            mark == "--nh-nat--" -> nat += rest.removePrefix("$NAT_MARK-")
            mark.startsWith("--nh-") -> section = ""
            section == "conf" && '=' in line && !line.trim().startsWith("#") -> settings += line.substringBefore('=').trim() to line.substringAfter('=').trim()
            section == "users" && ':' in line -> users += line.substringBefore(':') to line.endsWith(":locked")
        }
    }
    return OcservState(head.isNotEmpty(), settings, head.getOrElse(0) { "" }, head.getOrElse(1) { "" } == "enabled", passwd, users, name, pin, nat)
}

// the users ocserv has connected as occtl -j show users gives them, by the titles of their fields; null where ocserv did not answer
internal fun parseOcUsers(text: String): List<Map<String, String>>? {
    val users = try {
        Json.parseToJsonElement(text.trim()) as? JsonArray
    } catch (e: SerializationException) {
        null
    } ?: return null
    return users.mapNotNull { user -> (user as? JsonObject)?.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull.orEmpty() } }
}

// the ports of the server and the users connected now, read with the live tables of the panel where occtl reaches ocserv
internal const val OC_STATE = "if command -v occtl >/dev/null 2>&1; then echo \"--nh-oc-ports-- " +
    "\$(sed -n 's/^[[:space:]]*tcp-port[[:space:]]*=[[:space:]]*//p' /etc/ocserv/ocserv.conf 2>/dev/null | head -n 1) " +
    "\$(sed -n 's/^[[:space:]]*udp-port[[:space:]]*=[[:space:]]*//p' /etc/ocserv/ocserv.conf 2>/dev/null | head -n 1)\"; " +
    "echo --nh-oc-users--; occtl -j show users 2>/dev/null; fi; true"

// the lines of ocserv.conf as key = value, each kept as written; a key may stand on several lines, as dns and route do
private class OcText(text: String) {
    private val lines = text.lines().toMutableList()

    fun text() = lines.joinToString("\n")

    private fun keyOf(line: String) = line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") && '=' in it }?.substringBefore('=')?.trim()

    // the lines of a key replaced by new ones at the place of the first, or after the last setting
    fun set(key: String, values: List<String>) {
        val keys = lines.map(::keyOf)
        val first = keys.indexOf(key)
        val at = if (first >= 0) first else keys.indexOfLast { it != null } + 1
        val kept = lines.filterIndexed { line, _ -> keys[line] != key }
        lines.clear()
        lines += kept
        lines.addAll(at - (0 until at).count { keys[it] == key }, values)
    }

    fun put(field: String, value: String) {
        val items = value.split(OC_LIST).filter { it.isNotEmpty() }
        when (field) {
            "network" -> {
                val subnet = Ipv4.parseSubnet(value)
                set("ipv4-network", listOfNotNull(subnet?.let { "ipv4-network = ${Ipv4.format(it.network)}" }))
                set("ipv4-netmask", listOfNotNull(subnet?.let { "ipv4-netmask = ${Ipv4.format(it.mask)}" }))
            }
            "dns" -> set("dns", items.map { "dns = $it" })
            "routes" -> set("route", items.map { "route = $it" })
            "no-routes" -> set("no-route", items.map { "no-route = $it" })
            "banner" -> set("banner", listOfNotNull(value.ifEmpty { null }?.let { "banner = \"$it\"" }))
            "camouflage-secret" -> set("camouflage_secret", listOfNotNull(value.ifEmpty { null }?.let { "camouflage_secret = \"$it\"" }))
            "isolate-workers", "cisco-client-compat", "camouflage" -> set(field, listOfNotNull(value.ifEmpty { null }?.let { "$field = ${if (it in ValueType.Flag.on) "true" else "false"}" }))
            else -> set(field, listOfNotNull(value.ifEmpty { null }?.let { "$field = $it" }))
        }
    }
}

private fun ocFailed(output: String) = output.lines().any { "error" in it.lowercase() }

private fun ocCheck(field: String, value: String) {
    val items = value.split(OC_LIST).filter { it.isNotEmpty() }
    val valid = when (field) {
        "tcp-port", "udp-port", "max-clients", "max-same-clients" -> value.toIntOrNull() in 0..65535
        "keepalive", "dpd", "mobile-dpd", "idle-timeout", "mobile-idle-timeout" -> value.toIntOrNull() in 0..2_000_000
        "network" -> Ipv4.parseSubnet(value)?.takeIf { '/' in value }?.prefix in 8..30
        "ipv6-network" -> OC_NETWORK6.matches(value)
        "dns" -> items.all(OC_HOST::matches)
        "routes", "no-routes" -> items.all { it == "default" || OC_ROUTE.matches(it) }
        "default-domain" -> OC_HOST.matches(value)
        "banner", "camouflage-secret" -> value.none { it == '"' || it == '\n' || it == '\r' }
        else -> value in ValueType.Flag.on || value in OC_OFF
    }
    if (!valid) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes")
}

private const val CONF = "/etc/ocserv/ocserv.conf"
private const val CERT = "/etc/ocserv/networkhelm-server.crt"
private const val KEY = "/etc/ocserv/networkhelm-server.key"
private const val PASSWD = "/etc/ocserv/ocpasswd"
private const val OC_UNIT = "ocserv.service"
private const val OC_HOOK = "/etc/systemd/system/ocserv.service.d/networkhelm-nat-ocserv.conf"

private val OC_LIST = Regex("[,;\\s]+")
private val OC_HOST = Regex("[A-Za-z0-9.:-]+")
private val OC_NETWORK6 = Regex("[0-9A-Fa-f:]+/\\d{1,3}")
private val OC_ROUTE = Regex("[0-9A-Fa-f.:]+/[0-9.]+")
private val OC_OFF = setOf("0", "no", "off", "false", "disabled")
private val RESTART = setOf("tcp-port", "udp-port", "network", "ipv6-network")
private val OC_SETTINGS = listOf(
    "tcp-port", "udp-port", "network", "ipv6-network", "dns", "routes", "no-routes", "default-domain", "max-clients", "max-same-clients", "keepalive", "dpd", "mobile-dpd",
    "idle-timeout", "mobile-idle-timeout", "isolate-workers", "cisco-client-compat", "banner", "camouflage", "camouflage-secret",
)
private val OC_FIELDS = listOf("state", "autostart") + OC_SETTINGS + listOf("server-name", "server-cert", "server-key", "pin", "users", "nat")
private val OC_ARGUMENTS = listOf(
    Argument("tcp-port", ValueType.Integer(1, 65535), optional = true, placeholder = "443"),
    Argument("udp-port", ValueType.Integer(1, 65535), optional = true, placeholder = "443"),
    Argument("network", ValueType.Ip4Prefix, optional = true, placeholder = "192.168.1.0/24"),
    Argument("ipv6-network", ValueType.Ip6Prefix, optional = true),
    Argument("dns", ValueType.Text(), optional = true),
    Argument("routes", ValueType.Text(), optional = true, placeholder = "default"),
    Argument("no-routes", ValueType.Text(), optional = true),
    Argument("default-domain", ValueType.Text(), optional = true),
    Argument("max-clients", ValueType.Integer(0, 65535), optional = true),
    Argument("max-same-clients", ValueType.Integer(0, 65535), optional = true),
    Argument("keepalive", ValueType.Integer(0, 2_000_000, unit = "s"), optional = true),
    Argument("dpd", ValueType.Integer(0, 2_000_000, unit = "s"), optional = true),
    Argument("mobile-dpd", ValueType.Integer(0, 2_000_000, unit = "s"), optional = true),
    Argument("idle-timeout", ValueType.Integer(0, 2_000_000, unit = "s"), optional = true),
    Argument("mobile-idle-timeout", ValueType.Integer(0, 2_000_000, unit = "s"), optional = true),
    Argument("isolate-workers", ValueType.YesNo, optional = true),
    Argument("cisco-client-compat", ValueType.YesNo, optional = true),
    Argument("banner", ValueType.Text(), optional = true),
    Argument("camouflage", ValueType.YesNo, optional = true),
    Argument("camouflage-secret", ValueType.Secret, optional = true),
    Argument("server-name", ValueType.Text(), optional = true, placeholder = "vpn.example.net"),
    Argument("autostart", ValueType.YesNo, optional = true),
    Argument("nat", ValueType.YesNo, optional = true),
)

// the fields of a new server by the tabs of its window; the window shows them filled with what the host proposes
private val OC_SERVER_ARGUMENTS = listOf(
    Argument("client-endpoint", ValueType.Text(), optional = true, label = "Address for Clients", tab = "general", placeholder = "vpn.example.net"),
    Argument("port", ValueType.Integer(1, 65535), label = "Port, TCP and UDP", tab = "general"),
    Argument("autostart", ValueType.YesNo, label = "Start at Boot", tab = "general"),
    Argument("network", ValueType.Ip4Prefix, label = "IPv4 Network", tab = "network", placeholder = "10.10.10.0/24"),
    Argument("dns", ValueType.Text(), optional = true, label = "DNS for Clients", tab = "dns", placeholder = CLIENT_DNS),
    Argument("routes", ValueType.Text(), optional = true, label = "Routes for Clients", tab = "dns", placeholder = "default"),
    Argument("nat", ValueType.YesNo, label = "NAT for Clients", tab = "nat"),
    Argument("user", ValueType.Text(), optional = true, label = "First User", tab = "clients"),
    Argument("password", ValueType.Secret, optional = true, label = "Password", tab = "clients"),
)

// the settings without comments, the secret of camouflage masked; the users of the file of plain auth by name, group and lock, never their
// hashes; the name in the certificate and the pin OpenConnect takes, sha256 of its key
private val OCSERV_STATE = $$"""c=/etc/ocserv/ocserv.conf
[ -f "$c" ] || exit 0
echo "--nh-ocserv-- $(systemctl is-active ocserv 2>/dev/null) $(systemctl is-enabled ocserv 2>/dev/null)"
grep -vE '^[[:space:]]*(#|$)' "$c" | sed 's/^\([[:space:]]*camouflage_secret[[:space:]]*=\).*/\1 (hidden)/'
p=$(sed -n 's/^[[:space:]]*auth[[:space:]]*=[[:space:]]*"plain\[passwd=\([^],]*\).*/\1/p' "$c" | head -n 1)
[ -z "$p" ] || { echo "--nh-ocserv-users-- $p"; [ ! -f "$p" ] || awk -F: '{ print $1 ":" $2 ":" (substr($3, 1, 1) == "!" ? "locked" : "") }' "$p"; }
f=$(sed -n 's/^[[:space:]]*server-cert[[:space:]]*=[[:space:]]*//p' "$c" | head -n 1)
[ -z "$f" ] || [ ! -f "$f" ] || { echo "--nh-ocserv-name-- $(openssl x509 -in "$f" -noout -subject -nameopt RFC2253 2>/dev/null)"; echo "--nh-ocserv-pin-- $(openssl x509 -in "$f" -pubkey -noout 2>/dev/null | openssl pkey -pubin -outform der 2>/dev/null | openssl dgst -sha256 -binary | base64)"; }
[ ! -f /etc/systemd/system/ocserv.service.d/networkhelm-nat-ocserv.conf ] || echo "--nh-nat-- networkhelm-nat-ocserv"
$$NAT_STATE
true"""
