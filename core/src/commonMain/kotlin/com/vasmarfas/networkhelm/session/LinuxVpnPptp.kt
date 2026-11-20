package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.nettools.Ipv4
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession

// PPTP of a Linux host by pptpd. MS-CHAPv2 and MPPE give the logins and the traffic of its users to anyone who records them, so a server
// is made and changed only once the user says this is known. Its addresses are in /etc/pptpd.conf, its DNS in the file of pppd that the
// option line names, its users in chap-secrets under the name pppd takes there. The host as a client of such a server has a unit of
// systemd of its own for each connection, with pppd and pptp
internal class LinuxVpnPptp(private val ssh: SshSession, private val root: Root, private val tools: suspend () -> Set<String>, private val guard: LinuxGuard) {
    private val nat = LinuxVpnNat(root, tools, guard)

    val tables: Map<String, HostTable> = mapOf(
        "vpn/pptp" to HostTable(
            PPTP_FIELDS, { listOfNotNull(state().row()) }, settings = true, slow = true, arguments = { PPTP_ARGUMENTS }, set = ::set, commands = listOf("up", "down"),
            run = { command, _ -> root.masked(if (command == "up") "systemctl start pptpd" else "systemctl stop pptpd"); emptyList() }, present = { "pptpd" in tools() },
        ),
        "vpn/pptp/server" to HostTable(
            PPTP_SERVER_ARGUMENTS.map { it.name }, { listOf(proposal()) }, settings = true, arguments = { PPTP_SERVER_ARGUMENTS }, commands = listOf("create"),
            run = { _, values -> create(values) }, present = { "pptpd" in tools() },
        ),
        "vpn/pptp/client" to HostTable(
            PPTP_CLIENT_FIELDS, { clients().map { it.row() } }, slow = true, remove = ::removeClients, commands = listOf("up", "down"), run = ::bringClients,
            present = ::clientsPresent,
        ),
        "vpn/pptp/import" to HostTable(
            PPTP_IMPORT_ARGUMENTS.map { it.name }, { listOf(Item("", mapOf("name" to freeName(clients()), "autostart" to "true"))) }, settings = true,
            arguments = { PPTP_IMPORT_ARGUMENTS }, commands = listOf("import"), run = { _, values -> importClient(values) }, present = ::clientsPresent,
        ),
    )

    // by pptp and not pppd: pppd of Debian and Ubuntu runs for root and the group dip alone, so the login does not find it
    private suspend fun clientsPresent() = tools().let { "pptp" in it && "systemctl" in it }

    suspend fun state() = parsePptp(root.masked(PPTP_STATE))

    private suspend fun set(id: String?, changes: Map<String, String?>) {
        val host = state()
        if (!host.found) throw DeviceException(ErrorCategory.MissingItem, "the host has no /etc/pptpd.conf")
        if (changes["insecure"] !in ValueType.Flag.on) {
            throw DeviceException(ErrorCategory.ArgumentValue, "PPTP gives the logins and traffic of its users to anyone who records them: tick Insecure, Change It")
        }
        val dns = changes["dns"]?.split(PPTP_LIST)?.filter { it.isNotEmpty() }
        val bad = when {
            changes["local-ip"]?.let { PPTP_ADDRESSES.matches(it) } == false -> "local-ip" to changes["local-ip"]
            changes["remote-ip"]?.let { PPTP_ADDRESSES.matches(it) } == false -> "remote-ip" to changes["remote-ip"]
            dns != null && (dns.size > 2 || dns.any { Ipv4.parse(it) == null }) -> "dns" to changes["dns"]
            else -> PPTP_NUMBERS.entries.firstOrNull { (field, range) -> changes[field]?.ifEmpty { null }?.let { it.toIntOrNull() !in range } == true }?.let { it.key to changes[it.key] }
        }
        bad?.let { (field, value) -> throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes") }
        var restart = false
        // a value given takes the place of the line of its key, an empty one takes the line away
        fun edit(text: String, keys: Map<String, String>) = keys.entries.fold(text) { edited, (field, key) ->
            changes[field]?.let { value -> pptpLines(edited, key, listOfNotNull(value.ifEmpty { null }?.let { "$key $it" })) } ?: edited
        }
        if (PPTP_CONF_KEYS.keys.any { changes[it] != null }) {
            val before = root.masked("cat $PPTP_CONF")
            val text = edit(before, PPTP_CONF_KEYS)
            if (text != before) root.write(PPTP_CONF, text, "true").also { restart = true }
        }
        if (dns != null || PPTP_PPP_KEYS.keys.any { changes[it] != null }) {
            val before = root.masked("cat ${quote(host.options)}")
            val text = edit(dns?.let { pptpLines(before, "ms-dns", it.map { server -> "ms-dns $server" }) } ?: before, PPTP_PPP_KEYS)
            if (text != before) root.write(host.options, text, "true").also { restart = true }
        }
        changes["autostart"]?.let { root.masked("systemctl ${if (it in ValueType.Flag.on) "enable" else "disable"} pptpd") }
        if (restart && host.state == "active") root.masked("systemctl restart pptpd")
        val natOn = changes["nat"]?.let { it in ValueType.Flag.on }
        if (natOn == null && "pptp" !in host.nat) return
        val after = state()
        when {
            natOn != null -> natFor(host, after, natOn)
            after.network() != host.network() -> natFor(host, after, true)
        }
    }

    // NAT by the network of the clients, whose devices ppp0, ppp1 come and go, with TCP 1723 open: through ufw or firewalld where one
    // keeps the rules, else by an addition to the unit of pptpd. GRE, which PPTP takes as well, they do not let in by this
    private suspend fun natFor(before: PptpState, after: PptpState, on: Boolean) {
        val manager = nat.manager()
        val systemd = "systemctl" in tools()
        if (on && manager == null && !systemd) throw noNat("pptpd")
        if ("pptp" in before.nat) {
            if (manager != null) nat.set(manager, "pptp", listOfNotNull(before.network()), emptyList(), false, policy = true)
            if (systemd) root.natUnit(PPTP_UNIT, PPTP_HOOK, null)
        }
        if (on) {
            nat.forward(false)
            if (manager != null) {
                nat.set(manager, "pptp", listOfNotNull(after.network()), emptyList(), true, policy = true)
            } else {
                root.natUnit(PPTP_UNIT, PPTP_HOOK, deviceNat("pptp", listOfNotNull(after.network()), "ppp", "iptables-save" in tools()))
            }
        }
        nat.open("pptp", listOf("1723/tcp"))
    }

    // what a new server takes unless the user says otherwise: the address of the interface of the default route for clients, the first
    // network 10.N.0.0/24 nothing of the host overlaps for the server and its clients, DNS for them, NAT, a start at boot and a first
    // user; the weakness of PPTP is never ticked for the user
    private suspend fun proposal(): Item {
        val host = state()
        val scan = ssh.scan()
        val network = freeNetwork(scan.networks)?.address
        return Item(
            "",
            linkedMapOf(
                "insecure" to "", "client-endpoint" to scan.address, "autostart" to "true", "local-ip" to network?.let { Ipv4.format(it + 1) }.orEmpty(),
                "remote-ip" to network?.let { "${Ipv4.format(it + 10)}-254" }.orEmpty(), "dns" to CLIENT_DNS, "nat" to "${"systemctl" in tools() || nat.manager() != null}",
                "user" to generateSequence(1) { it + 1 }.map { "client$it" }.first { name -> host.users.none { it.first == name } },
            ),
        )
    }

    // a server in one step on the files the package brought, once the weakness of PPTP is ticked: the settings as the form makes them,
    // MS-CHAPv2 and MPPE of 128 bits demanded in its file of pppd, a start that is waited for, and last the first user, so that a server
    // that did not start leaves no user in the way of the next try. The server, login and password come back with a script of
    // PowerShell for Windows, whose built-in client is the last to take PPTP
    private suspend fun create(values: Map<String, String>): List<Item> {
        if (values["insecure"] !in ValueType.Flag.on) {
            throw DeviceException(
                ErrorCategory.ArgumentValue,
                "PPTP gives the logins and traffic of its users to anyone who records them, as MS-CHAPv2 is broken in hours: tick Insecure, Create It",
            )
        }
        val host = state()
        if (!host.found) throw DeviceException(ErrorCategory.MissingItem, "the host has no $PPTP_CONF")
        val endpoint = values["client-endpoint"].orEmpty().ifEmpty { localAddress(ssh) }
        val user = values["user"].orEmpty()
        val password = values["password"].orEmpty()
        if (!PPTP_HOST.matches(endpoint)) throw DeviceException(ErrorCategory.ArgumentValue, "client-endpoint: $endpoint is not a value it takes")
        if (user.isNotEmpty()) {
            if (!VPN_USER.matches(user)) throw DeviceException(ErrorCategory.ArgumentValue, "user: $user is not a value it takes")
            if (password.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
            if (host.users.any { it.first == user }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has a VPN user $user already")
        }
        if (values["nat"] in ValueType.Flag.on && "systemctl" !in tools() && nat.manager() == null) throw noNat("pptpd")
        set(null, values.filterKeys { it in listOf("local-ip", "remote-ip", "dns", "autostart", "nat", "insecure") })
        val options = root.masked("cat ${quote(host.options)}")
        val strict = pptpLines(pptpLines(options, "require-mschap-v2", listOf("require-mschap-v2")), "require-mppe-128", listOf("require-mppe-128"))
        if (strict != options) root.write(host.options, strict, "true")
        start()
        if (user.isEmpty()) return listOf(Item("pptp", mapOf("name" to "pptp")))
        val secret = password.ifEmpty { root.newSecret() }
        addUser(user, secret, false)
        val title = "PPTP $endpoint"
        val script = listOf(
            "# $title: PPTP gives the logins and traffic to anyone who records them; in PowerShell as an administrator",
            "Add-VpnConnection -Name '$title' -ServerAddress '$endpoint' -TunnelType Pptp -EncryptionLevel Required -AuthenticationMethod MSChapv2 -RememberCredential -Force",
        )
        return listOf(
            Item("pptp", mapOf("name" to "pptp", "conf" to "PPTP\nserver: $endpoint\nlogin: $user\npassword: $secret")),
            Item("pptp", mapOf("name" to "pptp", "file" to "pptp.ps1", "conf" to script.joinToString("\n"))),
        )
    }

    // a start that is waited for, as pptpd that cannot take its port stops a moment after systemd started it
    private suspend fun start() {
        val result = root.exec("systemctl restart pptpd; sleep 3; if systemctl is-active -q pptpd; then exit 0; fi; journalctl -u pptpd -n 12 --no-pager -o cat; exit 1")
        if (result.exitStatus != 0) throw DeviceException(ErrorCategory.Api, "pptpd did not start: ${hidden(result.output.trim().ifEmpty { result.error.trim() })}")
    }

    // the users of the server by name and whether they are off
    suspend fun users(): List<Pair<String, Boolean>> = state().users

    suspend fun found(): Boolean = state().found

    suspend fun addUser(name: String, password: String, disabled: Boolean) = ChapSecrets(root, state().name).add(name, password, disabled)

    suspend fun setUser(name: String, password: String?, disabled: Boolean?) {
        val host = state()
        if (host.users.none { it.first == name }) throw DeviceException(ErrorCategory.MissingItem, "the host has no VPN user $name")
        ChapSecrets(root, host.name).set(name, password, disabled)
    }

    suspend fun removeUsers(names: List<String>) = ChapSecrets(root, state().name).remove(names)

    suspend fun credentials(name: String): List<Item> {
        if (users().none { it.first == name }) throw DeviceException(ErrorCategory.MissingItem, "the host has no VPN user $name")
        return listOf(Item("", mapOf("conf" to "PPTP\nserver: ${localAddress(ssh)}\nlogin: $name")))
    }

    suspend fun clients() = parsePptpClients(root.masked(PPTP_CLIENT_STATE))

    // a connection to a server of PPTP from what its new server gave, pasted as it is, or from the fields given, which go first: a file
    // of peers of pppd that pptp carries, the password as a line of chap-secrets under the name of the connection, and a unit of systemd
    // with pppd. It comes up under the guard
    private suspend fun importClient(values: Map<String, String>): List<Item> {
        val given = loginLines(values["text"].orEmpty())
        val fields = mapOf("server" to "server", "user" to "login", "password" to "password").mapValues { (field, key) -> values[field].orEmpty().ifEmpty { given[key].orEmpty() } }
        fields.entries.firstOrNull { it.value.isEmpty() }?.let { throw DeviceException(ErrorCategory.ArgumentValue, "${it.key}: a value is needed") }
        val (server, user, password) = listOf("server", "user", "password").map { fields.getValue(it) }
        val name = values["name"].orEmpty()
        val bad = when {
            !PPTP_CLIENT_NAME.matches(name) -> "name" to name
            !PPTP_HOST.matches(server) -> "server" to server
            !VPN_USER.matches(user) -> "user" to user
            else -> null
        }
        bad?.let { (field, value) -> throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes") }
        if (clients().any { it.name == name }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has a PPTP connection $name already")
        val pptp = root.exec("command -v pptp").output.trim()
            .ifEmpty { throw DeviceException(ErrorCategory.Unsupported, "the host has no pptp: its package is pptp-linux on Debian and Ubuntu and pptp elsewhere") }
        val pppd = root.masked("command -v pppd").trim()
        val connection = "$PPTP_PREFIX-$name"
        val peer = listOf(
            "# the PPTP connection $name of NetworkHelm", "pty \"$pptp $server --nolaunchpppd\"", "name $user", "remotename $connection", "ifname ${pptpDevice(name)}",
            "ipparam $connection", "require-mppe-128", "refuse-eap", "refuse-pap", "refuse-chap", "refuse-mschap", "noauth", "nodefaultroute", "persist", "holdoff 30",
        )
        root.write("/etc/ppp/peers/$connection", peer.joinToString("\n"), "true")
        val secrets = ChapSecrets(root, connection)
        secrets.remove(listOf(user))
        secrets.add(user, password, false)
        val unit = listOf(
            "# a PPTP connection of NetworkHelm", "[Unit]", "Description=PPTP connection $name", "After=network-online.target", "Wants=network-online.target", "",
            "[Service]", "Type=simple", "ExecStart=$pppd call $connection nodetach", "Restart=on-failure", "RestartSec=30", "", "[Install]", "WantedBy=multi-user.target",
        )
        root.write("/etc/systemd/system/${pptpUnit(name)}", unit.joinToString("\n"), "systemctl daemon-reload 2>&1", mode = "644")
        if (values["autostart"] in ValueType.Flag.on) root.masked("systemctl enable ${pptpUnit(name)}")
        bringClients("up", mapOf("numbers" to name))
        return listOf(Item(name, mapOf("name" to name)))
    }

    private suspend fun removeClients(ids: List<String>) {
        val hosts = clients()
        for (client in ids.map { find(hosts, it) }) {
            if (client.state == "active") keepSession(ssh, pptpDevice(client.name))
            val files = listOf("/etc/systemd/system/${pptpUnit(client.name)}", "/etc/ppp/peers/$PPTP_PREFIX-${client.name}").joinToString(" ") { quote(it) }
            root.masked("systemctl disable --now ${pptpUnit(client.name)} >/dev/null 2>&1; rm -f $files && systemctl daemon-reload")
            ChapSecrets(root, "$PPTP_PREFIX-${client.name}").remove(listOf(client.user))
        }
    }

    // a connection that does not come up is stopped at once; one this session comes in by is not stopped
    private suspend fun bringClients(command: String, arguments: Map<String, String>): List<Item> {
        val hosts = clients()
        for (client in arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }.map { find(hosts, it) }) {
            val unit = pptpUnit(client.name)
            when (command) {
                "up" -> if (client.state != "active") guard.guarded(LinuxGuard.Backup("vpn", "true", "systemctl stop $unit")) { root.masked(unitWithDevice(unit, pptpDevice(client.name), "pppd")) }
                "down" -> if (client.state == "active") {
                    keepSession(ssh, pptpDevice(client.name))
                    root.masked("systemctl stop $unit")
                }
            }
        }
        return emptyList()
    }

    private fun find(hosts: List<PptpClient>, id: String?) =
        hosts.firstOrNull { it.name == id } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no PPTP connection $id")
}

// a connection of the host to a server of PPTP as its unit and its file of peers show it
internal class PptpClient(val name: String, val state: String, val autostart: Boolean, val server: String, val user: String) {
    fun row() = Item(name, linkedMapOf("name" to name, "state" to state, "autostart" to "$autostart", "server" to server, "user" to user, "interface" to pptpDevice(name)))
}

// the units of the connections made here, each after a line --nh-pptp-client-- <name> <active> <enabled>, with the server and login of
// their files of peers
internal fun parsePptpClients(text: String): List<PptpClient> = text.split("--nh-pptp-client-- ").drop(1).map { block ->
    val head = block.lines().first().trim().split(' ')
    val settings = block.lines().drop(1).filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    PptpClient(head[0], head.getOrElse(1) { "" }, head.getOrElse(2) { "" } == "enabled", settings["server"].orEmpty(), settings["user"].orEmpty())
}

// the first name client, then client-2 and on, that no connection of the host has
private fun freeName(hosts: List<PptpClient>) = (sequenceOf("client") + generateSequence(2) { it + 1 }.map { "client-$it" }).first { free -> hosts.none { it.name == free } }

private fun pptpUnit(name: String) = "$PPTP_PREFIX-$name.service"

// the device of the tunnel of a connection, which a name of up to 10 characters keeps within the 15 of Linux
private fun pptpDevice(name: String) = "pptp-$name"

// what the host has of pptpd at one read: the lines of /etc/pptpd.conf and of its file of pppd without comments, the name pppd takes,
// the users under that name and the state of the service
internal class PptpState(
    val found: Boolean, val conf: List<String>, val options: String, val pppd: List<String>, val name: String, val users: List<Pair<String, Boolean>>,
    val state: String, val autostart: Boolean, val nat: Set<String>,
) {
    private fun value(lines: List<String>, key: String) = lines.firstOrNull { it.split(PPTP_BLANKS).firstOrNull() == key }?.substringAfter(key)?.trim().orEmpty()

    // the network of the server and its clients, which NAT goes by; none where the addresses of the clients are a list
    fun network() = l2tpNetwork(value(conf, "localip"), value(conf, "remoteip"))

    fun row(): Item? = if (!found) null else Item(
        "pptp",
        linkedMapOf(
            "state" to state, "autostart" to "$autostart", "local-ip" to value(conf, "localip"), "remote-ip" to value(conf, "remoteip"), "connections" to value(conf, "connections"),
            "mtu" to value(pppd, "mtu"), "mru" to value(pppd, "mru"), "lcp-echo-interval" to value(pppd, "lcp-echo-interval"), "lcp-echo-failure" to value(pppd, "lcp-echo-failure"),
            "dns" to pppd.filter { it.startsWith("ms-dns ") }.joinToString(", ") { it.removePrefix("ms-dns ").trim() }, "mppe" to "${"require-mppe-128" in pppd}",
            "name" to name, "options" to options, "users" to "${users.size}", "nat" to "${"pptp" in nat}", "insecure" to "",
        ),
    )
}

internal fun parsePptp(text: String): PptpState {
    var head = listOf<String>()
    val conf = mutableListOf<String>()
    val pppd = mutableListOf<String>()
    val users = mutableListOf<Pair<String, Boolean>>()
    val nat = mutableSetOf<String>()
    var options = ""
    var name = ""
    var section = ""
    for (line in text.lines()) {
        val mark = line.substringBefore(' ')
        val rest = line.substringAfter(' ', "").trim()
        when {
            mark == "--nh-pptp--" -> {
                head = rest.split(' ')
                section = "conf"
            }
            mark == "--nh-pptp-options--" -> {
                options = rest
                section = "pppd"
            }
            mark == "--nh-pptp-users--" -> {
                name = rest
                section = "users"
            }
            mark == "--nh-nat--" -> nat += rest.removePrefix("$NAT_MARK-")
            line.isBlank() -> Unit
            section == "conf" -> conf += line.trim()
            section == "pppd" -> pppd += line.trim()
            section == "users" -> users += line.substringBefore(' ') to line.endsWith(" off")
        }
    }
    return PptpState(head.isNotEmpty(), conf, options, pppd, name, users, head.getOrElse(0) { "" }, head.getOrElse(1) { "" } == "enabled", nat)
}

// the lines of a file of pptpd or pppd whose first word is key replaced by the lines given, at the place of the first, or after the last
// line that is not blank; commented lines stay
private fun pptpLines(text: String, key: String, values: List<String>): String {
    val lines = mutableListOf<String>()
    var placed = false
    for (line in text.lines()) {
        if (line.trim().split(PPTP_BLANKS).firstOrNull() != key) {
            lines += line
        } else if (!placed) {
            lines += values
            placed = true
        }
    }
    if (!placed) lines.addAll(lines.indexOfLast { it.isNotBlank() } + 1, values)
    return lines.joinToString("\n")
}

private const val PPTP_CONF = "/etc/pptpd.conf"

private val PPTP_BLANKS = Regex("\\s+")
private val PPTP_LIST = Regex("[,;\\s]+")

// 192.168.0.234-238,192.168.0.245 as pptpd takes its addresses
private val PPTP_ADDRESSES = Regex("\\d{1,3}(\\.\\d{1,3}){3}(-\\d{1,3})?(,\\d{1,3}(\\.\\d{1,3}){3}(-\\d{1,3})?)*")

private val PPTP_FIELDS = listOf(
    "state", "autostart", "local-ip", "remote-ip", "connections", "dns", "mtu", "mru", "lcp-echo-interval", "lcp-echo-failure", "mppe", "name", "options", "users", "nat", "insecure",
)

// the fields of the form that are lines of pptpd.conf and of the file of pppd, by the keys of those lines
private val PPTP_CONF_KEYS = mapOf("local-ip" to "localip", "remote-ip" to "remoteip", "connections" to "connections")
private val PPTP_PPP_KEYS = mapOf("mtu" to "mtu", "mru" to "mru", "lcp-echo-interval" to "lcp-echo-interval", "lcp-echo-failure" to "lcp-echo-failure")
private val PPTP_NUMBERS = mapOf("connections" to 1..65535, "mtu" to 576..1500, "mru" to 576..1500, "lcp-echo-interval" to 0..86400, "lcp-echo-failure" to 0..1000)
private val PPTP_ARGUMENTS = listOf(
    Argument("local-ip", ValueType.Text(), optional = true, placeholder = "192.168.0.1"),
    Argument("remote-ip", ValueType.Text(), optional = true, placeholder = "192.168.0.234-238"),
    Argument("dns", ValueType.Text(), optional = true, placeholder = "1.1.1.1"),
    Argument("connections", ValueType.Integer(1, 65535), optional = true, placeholder = "100"),
    Argument("mtu", ValueType.Integer(576, 1500), optional = true, placeholder = "1500"),
    Argument("mru", ValueType.Integer(576, 1500), optional = true, placeholder = "1500"),
    Argument("lcp-echo-interval", ValueType.Integer(0, 86400, unit = "s"), optional = true),
    Argument("lcp-echo-failure", ValueType.Integer(0, 1000), optional = true),
    Argument("autostart", ValueType.YesNo, optional = true),
    Argument("nat", ValueType.YesNo, optional = true),
    Argument("insecure", ValueType.YesNo, optional = true),
)

private const val PPTP_PREFIX = "networkhelm-pptp"
private const val PPTP_UNIT = "pptpd.service"
private const val PPTP_HOOK = "/etc/systemd/system/pptpd.service.d/networkhelm-nat-pptp.conf"
private val PPTP_HOST = Regex("[A-Za-z0-9.:-]+")
private val PPTP_CLIENT_NAME = Regex("[a-z0-9][a-z0-9-]{0,9}")
private val PPTP_CLIENT_FIELDS = listOf("name", "state", "autostart", "server", "user", "interface")

// the fields of a new server by the tabs of its window; the window shows them filled with what the host proposes, the weakness unticked
private val PPTP_SERVER_ARGUMENTS = listOf(
    Argument("insecure", ValueType.YesNo, label = "Insecure, Create It", tab = "general"),
    Argument("client-endpoint", ValueType.Text(), optional = true, label = "Address for Clients", tab = "general", placeholder = "vpn.example.net"),
    Argument("autostart", ValueType.YesNo, label = "Start at Boot", tab = "general"),
    Argument("local-ip", ValueType.Ip4, label = "Local Address", tab = "network", placeholder = "192.168.0.1"),
    Argument("remote-ip", ValueType.Text(), label = "Client Addresses", tab = "network", placeholder = "192.168.0.234-238"),
    Argument("dns", ValueType.Text(), optional = true, label = "DNS for Clients", tab = "dns", placeholder = CLIENT_DNS),
    Argument("nat", ValueType.YesNo, label = "NAT for Clients", tab = "nat"),
    Argument("user", ValueType.Text(), optional = true, label = "First User", tab = "clients"),
    Argument("password", ValueType.Secret, optional = true, label = "Password", tab = "clients"),
)

// what the import takes beside the reply of the server, which holds the password and so is a secret
private val PPTP_IMPORT_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), label = "Name"),
    Argument("server", ValueType.Text(), optional = true, label = "Server", placeholder = "from the reply"),
    Argument("user", ValueType.Text(), optional = true, label = "Login", placeholder = "from the reply"),
    Argument("password", ValueType.Secret, optional = true, label = "Password", placeholder = "from the reply"),
    Argument("autostart", ValueType.YesNo, label = "Start at Boot"),
    Argument("text", ValueType.Secret, label = "Reply of the Server"),
)

// the units of the connections of the host made here with their state, and the server and login of their files of peers
private val PPTP_CLIENT_STATE = $$"""for f in /etc/systemd/system/networkhelm-pptp-*.service; do [ -f "$f" ] || continue
n=${f##*/networkhelm-pptp-}; n=${n%.service}
echo "--nh-pptp-client-- $n $(systemctl is-active "networkhelm-pptp-$n" 2>/dev/null) $(systemctl is-enabled "networkhelm-pptp-$n" 2>/dev/null)"
sed -n 's/^pty "[^ ]* \([^ ]*\) .*/server = \1/p; s/^name /user = /p' "/etc/ppp/peers/networkhelm-pptp-$n" 2>/dev/null
done
true"""

// the settings without comments, the file of pppd the option line names, the name pppd takes there, or the name of the host as pppd
// takes it without one, and the users under that name, never their passwords
private val PPTP_STATE = $$"""c=/etc/pptpd.conf
[ -f "$c" ] || exit 0
echo "--nh-pptp-- $(systemctl is-active pptpd 2>/dev/null) $(systemctl is-enabled pptpd 2>/dev/null)"
grep -vE '^[[:space:]]*(#|$)' "$c"
o=$(sed -n 's/^[[:space:]]*option[[:space:]][[:space:]]*//p' "$c" | head -n 1); o=${o:-/etc/ppp/pptpd-options}
echo "--nh-pptp-options-- $o"
[ ! -f "$o" ] || grep -vE '^[[:space:]]*(#|$)' "$o"
s=$(sed -n 's/^[[:space:]]*name[[:space:]][[:space:]]*//p' "$o" 2>/dev/null | head -n 1); s=${s:-$(hostname)}
echo "--nh-pptp-users-- $s"
[ ! -f /etc/ppp/chap-secrets ] || awk -v s="$s" '{ off = sub(/^#\[disabled\] /, "") } $1 !~ /^#/ && $2 == s { n = $1; gsub(/"/, "", n); t = off ? " off" : ""; print n t }' /etc/ppp/chap-secrets
[ ! -f /etc/systemd/system/pptpd.service.d/networkhelm-nat-pptp.conf ] || echo "--nh-nat-- networkhelm-nat-pptp"
$$NAT_STATE
true"""
