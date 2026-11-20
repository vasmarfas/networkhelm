package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.nettools.Ipv4
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlin.io.encoding.Base64

// L2TP over IPsec of a Linux host, the server the clients built into Windows, macOS and iOS reach with a preshared key: xl2tpd with its
// file and a file of pppd of its own, the connection of strongSwan in transport mode for UDP 1701, and the users as lines of
// /etc/ppp/chap-secrets under the name of this server, where the lines of other servers stay as they are. The read takes the names of
// the users alone, their passwords stay on the host. xl2tpd reads its file only as it starts, so a running server starts again. The
// host as a client of such a server has a unit of systemd of its own for each connection
internal class LinuxVpnL2tp(
    private val ssh: SshSession,
    private val root: Root,
    private val tools: suspend () -> Set<String>,
    private val ipsec: LinuxVpnIpsec,
    private val guard: LinuxGuard,
) {
    private val nat = LinuxVpnNat(root, tools, guard)
    private val chap = ChapSecrets(root, L2TP_NAME)

    val tables: Map<String, HostTable> = mapOf(
        "vpn/l2tp" to HostTable(
            L2TP_FIELDS, { listOf(state().row()) }, settings = true, slow = true, arguments = { L2TP_ARGUMENTS }, set = ::set, commands = listOf("up", "down"),
            run = { command, _ -> bring(command) }, present = { "xl2tpd" in tools() },
        ),
        "vpn/l2tp/server" to HostTable(
            L2TP_SERVER_ARGUMENTS.map { it.name }, { listOf(proposal()) }, settings = true, arguments = { L2TP_SERVER_ARGUMENTS }, commands = listOf("create"),
            run = { _, values -> create(values) }, present = { "xl2tpd" in tools() },
        ),
        "vpn/l2tp/client" to HostTable(
            L2TP_CLIENT_FIELDS, { clients().map { it.row() } }, slow = true, remove = ::removeClients, commands = listOf("up", "down"), run = ::bringClients,
            present = ::clientsPresent,
        ),
        "vpn/l2tp/import" to HostTable(
            L2TP_IMPORT_ARGUMENTS.map { it.name }, { listOf(Item("", mapOf("name" to freeName(clients()), "autostart" to "true"))) }, settings = true,
            arguments = { L2TP_IMPORT_ARGUMENTS }, commands = listOf("import"), run = { _, values -> importClient(values) }, present = ::clientsPresent,
        ),
    )

    private suspend fun clientsPresent() = tools().let { "xl2tpd" in it && "swanctl" in it && "systemctl" in it }

    suspend fun state() = parseL2tp(root.masked(L2TP_STATE))

    // the first settings make the server, with a preshared key the host makes where none is given; a file of xl2tpd with settings not
    // made here stays as it is
    private suspend fun set(id: String?, changes: Map<String, String?>) {
        val host = state()
        if (!host.free) throw DeviceException(ErrorCategory.Unsupported, "$L2TP_CONF is no file of NetworkHelm, and it stays as it is")
        if ("swanctl" !in tools()) throw DeviceException(ErrorCategory.Unsupported, "L2TP over IPsec here takes strongSwan with swanctl")
        if (!host.ours || L2TP_SETTINGS.any { it in changes }) {
            val local = changes["local-ip"]?.ifEmpty { null } ?: host.local.ifEmpty { "10.20.30.1" }
            val pool = changes["pool"]?.ifEmpty { null } ?: host.pool.ifEmpty { "10.20.30.10-10.20.30.254" }
            val dns = changes["dns"] ?: if (host.ours) host.dns else "1.1.1.1"
            val mtu = changes["mtu"] ?: if (host.ours) host.mtu else "1280"
            val echo = changes["lcp-echo-interval"] ?: if (host.ours) host.option("lcp-echo-interval") else "30"
            val failures = changes["lcp-echo-failure"] ?: if (host.ours) host.option("lcp-echo-failure") else "4"
            val idle = changes["idle"] ?: host.option("idle")
            val servers = dns.split(L2TP_LIST).filter { it.isNotEmpty() }
            val bad = when {
                Ipv4.parse(local) == null -> "local-ip" to local
                !L2TP_POOL.matches(pool) || l2tpNetwork(local, pool) == null -> "pool" to pool
                servers.size > 2 || servers.any { Ipv4.parse(it) == null } -> "dns" to dns
                mtu.isNotEmpty() && mtu.toIntOrNull() !in 576..1500 -> "mtu" to mtu
                echo.isNotEmpty() && echo.toIntOrNull() !in 0..86400 -> "lcp-echo-interval" to echo
                failures.isNotEmpty() && failures.toIntOrNull() !in 0..1000 -> "lcp-echo-failure" to failures
                idle.isNotEmpty() && idle.toIntOrNull() !in 0..86400 -> "idle" to idle
                else -> null
            }
            bad?.let { (field, value) -> throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes") }
            val ppp = listOf("# pppd of the L2TP server of NetworkHelm", "name $L2TP_NAME", "require-mschap-v2") + servers.map { "ms-dns $it" } +
                (if (mtu.isEmpty()) emptyList() else listOf("mtu $mtu", "mru $mtu")) +
                listOfNotNull(echo.ifEmpty { null }?.let { "lcp-echo-interval $it" }, failures.ifEmpty { null }?.let { "lcp-echo-failure $it" }, idle.ifEmpty { null }?.let { "idle $it" }) +
                "noccp"
            root.write(L2TP_PPP, ppp.joinToString("\n"), "true")
            val conf = listOf(
                "; the L2TP server of NetworkHelm, behind IPsec; its users are the lines of /etc/ppp/chap-secrets under $L2TP_NAME", "[lns default]",
                "ip range = $pool", "local ip = $local", "require chap = yes", "refuse pap = yes", "require authentication = yes", "name = $L2TP_NAME",
                "pppoptfile = $L2TP_PPP", "length bit = yes",
            )
            root.write(L2TP_CONF, conf.joinToString("\n"), "true")
            if (host.state == "active") root.masked("systemctl restart xl2tpd")
        }
        (changes["psk"]?.ifEmpty { null } ?: if (host.keyed) null else ipsec.newSecret())?.let { ipsec.setL2tp(it) }
        changes["autostart"]?.let { root.masked("systemctl ${if (it in ValueType.Flag.on) "enable" else "disable"} xl2tpd") }
        val natOn = changes["nat"]?.let { it in ValueType.Flag.on }
        val after = state()
        when {
            natOn != null -> natFor(host, after, natOn)
            "l2tp" in host.nat && after.network() != host.network() -> natFor(host, after, true)
        }
    }

    // NAT by the network of the clients, whose devices ppp0, ppp1 come and go, with the ports of IKE open and UDP 1701 only through IPsec:
    // through ufw or firewalld where one keeps the rules, else by an addition to the unit of xl2tpd
    private suspend fun natFor(before: L2tpState, after: L2tpState, on: Boolean) {
        val manager = nat.manager()
        val systemd = "systemctl" in tools()
        if (on && manager == null && !systemd) throw noNat("xl2tpd")
        if ("l2tp" in before.nat) {
            if (manager != null) nat.set(manager, "l2tp", listOfNotNull(before.network()), emptyList(), false, policy = true, sealed = L2TP_SEALED)
            if (systemd) root.natUnit(L2TP_UNIT, L2TP_HOOK, null)
        }
        if (!on) return
        nat.forward(false)
        if (manager != null) {
            nat.set(manager, "l2tp", listOfNotNull(after.network()), listOf("500/udp", "4500/udp"), true, policy = true, sealed = L2TP_SEALED)
        } else {
            root.natUnit(L2TP_UNIT, L2TP_HOOK, deviceNat("l2tp", listOfNotNull(after.network()), "ppp", "iptables-save" in tools(), "1701"))
        }
    }

    // what a new server takes unless the user says otherwise: the address of the interface of the default route for clients, the first
    // network 10.N.0.0/24 nothing of the host overlaps for the server and its clients, DNS for them, NAT, a start at boot, a preshared
    // key the host makes and a first user
    private suspend fun proposal(): Item {
        val host = state()
        val scan = ssh.scan(root)
        val network = freeNetwork(scan.networks)?.address
        return Item(
            "",
            linkedMapOf(
                "client-endpoint" to scan.address, "autostart" to "true", "local-ip" to network?.let { Ipv4.format(it + 1) }.orEmpty(),
                "pool" to network?.let { "${Ipv4.format(it + 10)}-${Ipv4.format(it + 254)}" }.orEmpty(), "dns" to CLIENT_DNS, "psk" to "",
                "nat" to "${"systemctl" in tools() || nat.manager() != null}",
                "user" to generateSequence(1) { it + 1 }.map { "client$it" }.first { name -> host.users.none { it.first == name } },
            ),
        )
    }

    // a server in one step: the settings make it as the first ones of its form do, xl2tpd starts and is waited for, charon starts at boot
    // with it, and last comes the first user, so that a server that did not start leaves no user in the way of the next try. The server,
    // preshared key, login and password come back, then a profile for macOS and iOS and a script of PowerShell for Windows
    private suspend fun create(values: Map<String, String>): List<Item> {
        val host = state()
        val endpoint = values["client-endpoint"].orEmpty().ifEmpty { localAddress(ssh) }
        val user = values["user"].orEmpty()
        val password = values["password"].orEmpty()
        if (!L2TP_HOST.matches(endpoint)) throw DeviceException(ErrorCategory.ArgumentValue, "client-endpoint: $endpoint is not a value it takes")
        if (user.isNotEmpty()) {
            if (!VPN_USER.matches(user)) throw DeviceException(ErrorCategory.ArgumentValue, "user: $user is not a value it takes")
            if (password.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
            if (host.users.any { it.first == user }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has a VPN user $user already")
        }
        if (values["nat"] in ValueType.Flag.on && "systemctl" !in tools() && nat.manager() == null) throw noNat("xl2tpd")
        set(null, values.filterKeys { it in L2TP_SETTINGS || it in listOf("psk", "nat", "autostart") })
        if (values["autostart"] in ValueType.Flag.on) ipsec.startAtBoot()
        start()
        if (user.isEmpty()) return listOf(Item("l2tp", mapOf("name" to "l2tp")))
        val secret = password.ifEmpty { root.newSecret() }
        addUser(user, secret, false)
        val psk = ipsec.l2tpSecret()
        val uuids = root.masked("cat /proc/sys/kernel/random/uuid /proc/sys/kernel/random/uuid").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val title = "L2TP $endpoint"
        return listOf(
            Item("l2tp", mapOf("name" to "l2tp", "conf" to "L2TP/IPsec\nserver: $endpoint\npreshared key: $psk\nlogin: $user\npassword: $secret")),
            Item("l2tp", mapOf("name" to "l2tp", "file" to "l2tp.mobileconfig", "conf" to l2tpProfile(title, endpoint, user, psk, uuids))),
            Item("l2tp", mapOf("name" to "l2tp", "file" to "l2tp.ps1", "conf" to l2tpScript(title, endpoint, psk))),
        )
    }

    // a start that is waited for, as xl2tpd that cannot take its port stops a moment after systemd started it
    private suspend fun start() {
        val result = root.exec("systemctl restart xl2tpd; sleep 3; if systemctl is-active -q xl2tpd; then exit 0; fi; journalctl -u xl2tpd -n 12 --no-pager -o cat; exit 1")
        if (result.exitStatus != 0) throw DeviceException(ErrorCategory.Api, "xl2tpd did not start: ${hidden(result.output.trim().ifEmpty { result.error.trim() })}")
    }

    // a server that stops ends the SAs of its IPsec too; swanctl unloads no single connection, so charon keeps it for the next start
    private suspend fun bring(command: String): List<Item> {
        root.masked(if (command == "up") "systemctl start xl2tpd" else "systemctl stop xl2tpd && ${ikeTerminate("networkhelm-l2tp")}")
        return emptyList()
    }

    // the users of this server by name and whether they are off
    suspend fun users(): List<Pair<String, Boolean>> = state().users

    suspend fun configured(): Boolean = state().ours

    suspend fun addUser(name: String, password: String, disabled: Boolean) = chap.add(name, password, disabled)

    suspend fun setUser(name: String, password: String?, disabled: Boolean?) {
        if (users().none { it.first == name }) throw DeviceException(ErrorCategory.MissingItem, "the host has no VPN user $name")
        chap.set(name, password, disabled)
    }

    suspend fun removeUsers(names: List<String>) = chap.remove(names)

    // the server, the preshared key and the login of a user, as the clients built into Windows, macOS and iOS ask for them; the password
    // is the one of the user
    suspend fun credentials(name: String): List<Item> {
        if (users().none { it.first == name }) throw DeviceException(ErrorCategory.MissingItem, "the host has no VPN user $name")
        return listOf(Item("", mapOf("conf" to "L2TP/IPsec\nserver: ${localAddress(ssh)}\npreshared key: ${ipsec.l2tpSecret()}\nlogin: $name")))
    }

    suspend fun clients() = parseL2tpClients(root.masked(L2TP_CLIENT_STATE))

    // a connection to a server of L2TP from what its new server gave, pasted as it is, or from the fields given, which go first: IPsec
    // in transport mode that charon brings up as xl2tpd first sends, the password as a line of chap-secrets under the name of the
    // connection, and xl2tpd with pppd of its own in a unit of systemd, on a port of its own beside the xl2tpd of the package. It comes
    // up under the guard
    private suspend fun importClient(values: Map<String, String>): List<Item> {
        val given = loginLines(values["text"].orEmpty())
        val fields = mapOf("server" to "server", "psk" to "preshared key", "user" to "login", "password" to "password")
            .mapValues { (field, key) -> values[field].orEmpty().ifEmpty { given[key].orEmpty() } }
        fields.entries.firstOrNull { it.value.isEmpty() }?.let { throw DeviceException(ErrorCategory.ArgumentValue, "${it.key}: a value is needed") }
        val (server, psk, user, password) = listOf("server", "psk", "user", "password").map { fields.getValue(it) }
        val name = values["name"].orEmpty()
        val bad = when {
            !L2TP_CLIENT_NAME.matches(name) -> "name" to name
            !L2TP_HOST.matches(server) -> "server" to server
            !VPN_USER.matches(user) -> "user" to user
            else -> null
        }
        bad?.let { (field, value) -> throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes") }
        // the secret of the server would answer for the connection too, as neither names the other side
        if (state().ours) throw DeviceException(ErrorCategory.Unsupported, "the host is a server of L2TP and can be no client of another")
        val hosts = clients()
        if (hosts.any { it.name == name }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has an L2TP connection $name already")
        val taken = udpPorts(ssh.exec("cat /proc/net/udp /proc/net/udp6 2>/dev/null").output) + hosts.mapNotNull { it.port.toIntOrNull() }
        val port = "${(1702..65535).first { it !in taken }}"
        val connection = "$L2TP_PREFIX-$name"
        val binary = root.masked("command -v xl2tpd").trim()
        val ppp = listOf(
            "# pppd of the L2TP connection $name of NetworkHelm", "name $user", "remotename $connection", "ifname ${l2tpDevice(name)}", "ipparam $connection", "noauth",
            "refuse-eap", "refuse-pap", "refuse-chap", "refuse-mschap", "noccp", "noipdefault", "nodefaultroute", "mtu 1280", "mru 1280", "lcp-echo-interval 30",
            "lcp-echo-failure 4",
        )
        root.write(l2tpPpp(name), ppp.joinToString("\n"), "true")
        val conf = listOf(
            "; an L2TP connection of NetworkHelm", "[global]", "port = $port", "[lac $name]", "lns = $server", "pppoptfile = ${l2tpPpp(name)}", "name = $user",
            "require authentication = no", "refuse pap = yes", "length bit = yes", "autodial = yes", "redial = yes", "redial timeout = 15", "max redials = 5",
        )
        root.write(l2tpConf(name), conf.joinToString("\n"), "true")
        val secrets = ChapSecrets(root, connection)
        secrets.remove(listOf(user))
        secrets.add(user, password, false)
        ipsec.setL2tpClient(name, server, port, psk)
        val unit = listOf(
            "# an L2TP connection of NetworkHelm", "[Unit]", "Description=L2TP connection $name", "After=network-online.target strongswan.service",
            "Wants=network-online.target", "", "[Service]", "Type=simple", "ExecStart=$binary -D -c ${l2tpConf(name)} -C /run/$connection.control -p /run/$connection.pid",
            "Restart=on-failure", "RestartSec=30", "", "[Install]", "WantedBy=multi-user.target",
        )
        root.write("/etc/systemd/system/${l2tpUnit(name)}", unit.joinToString("\n"), "systemctl daemon-reload 2>&1", mode = "644")
        if (values["autostart"] in ValueType.Flag.on) root.masked("systemctl enable ${l2tpUnit(name)}")
        bringClients("up", mapOf("numbers" to name))
        return listOf(Item(name, mapOf("name" to name)))
    }

    private suspend fun removeClients(ids: List<String>) {
        val hosts = clients()
        for (client in ids.map { find(hosts, it) }) {
            if (client.state == "active") keepSession(ssh, l2tpDevice(client.name))
            val files = listOf("/etc/systemd/system/${l2tpUnit(client.name)}", l2tpConf(client.name), l2tpPpp(client.name)).joinToString(" ") { quote(it) }
            root.masked("systemctl disable --now ${l2tpUnit(client.name)} >/dev/null 2>&1; rm -f $files && systemctl daemon-reload")
            ChapSecrets(root, "$L2TP_PREFIX-${client.name}").remove(listOf(client.user))
            ipsec.removeL2tpClient(client.name)
        }
    }

    // a connection that does not come up is stopped at once; one this session comes in by is not stopped
    private suspend fun bringClients(command: String, arguments: Map<String, String>): List<Item> {
        val hosts = clients()
        for (client in arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }.map { find(hosts, it) }) {
            val unit = l2tpUnit(client.name)
            when (command) {
                "up" -> if (client.state != "active") guard.guarded(LinuxGuard.Backup("vpn", "true", "systemctl stop $unit")) { root.masked(unitWithDevice(unit, l2tpDevice(client.name), "xl2tpd")) }
                "down" -> if (client.state == "active") {
                    keepSession(ssh, l2tpDevice(client.name))
                    root.masked("systemctl stop $unit")
                }
            }
        }
        return emptyList()
    }

    private fun find(hosts: List<L2tpClient>, id: String?) =
        hosts.firstOrNull { it.name == id } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no L2TP connection $id")
}

// a connection of the host to a server of L2TP as its unit and its file of xl2tpd show it
internal class L2tpClient(val name: String, val state: String, val autostart: Boolean, val server: String, val user: String, val port: String) {
    fun row() = Item(name, linkedMapOf("name" to name, "state" to state, "autostart" to "$autostart", "server" to server, "user" to user, "interface" to l2tpDevice(name)))
}

// the units of the connections made here, each after a line --nh-l2tp-client-- <name> <active> <enabled>, with lns, name and port of
// their files of xl2tpd
internal fun parseL2tpClients(text: String): List<L2tpClient> = text.split("--nh-l2tp-client-- ").drop(1).map { block ->
    val head = block.lines().first().trim().split(' ')
    val settings = block.lines().drop(1).filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    L2tpClient(head[0], head.getOrElse(1) { "" }, head.getOrElse(2) { "" } == "enabled", settings["lns"].orEmpty(), settings["name"].orEmpty(), settings["port"].orEmpty())
}

// the profile for macOS and iOS with the preshared key and without the password, which the device asks for
private fun l2tpProfile(title: String, server: String, user: String, psk: String, uuids: List<String>) = L2TP_MOBILECONFIG.replace("{title}", title)
    .replace("{server}", server).replace("{user}", user).replace("{psk}", Base64.encode(psk.encodeToByteArray()))
    .replace("{uuid1}", uuids.getOrElse(0) { "" }).replace("{uuid2}", uuids.getOrElse(1) { "" })

private fun l2tpScript(title: String, server: String, psk: String) = listOf(
    "# $title: L2TP/IPsec with a preshared key, in PowerShell as an administrator",
    "Add-VpnConnection -Name '$title' -ServerAddress '$server' -TunnelType L2tp -L2tpPsk '${psk.replace("'", "''")}' -AuthenticationMethod MSChapv2 " +
        "-EncryptionLevel Required -RememberCredential -Force",
    "# a server behind NAT, as at home, needs this once and a restart of Windows:",
    "# New-ItemProperty -Path 'HKLM:\\SYSTEM\\CurrentControlSet\\Services\\PolicyAgent' -Name AssumeUDPEncapsulationContextOnSendRule -Value 2 -PropertyType DWord -Force",
).joinToString("\n")

// the first name client, then client-2 and on, that no connection of the host has
private fun freeName(hosts: List<L2tpClient>) = (sequenceOf("client") + generateSequence(2) { it + 1 }.map { "client-$it" }).first { free -> hosts.none { it.name == free } }

private fun l2tpUnit(name: String) = "$L2TP_PREFIX-$name.service"

private fun l2tpConf(name: String) = "/etc/xl2tpd/$L2TP_PREFIX-$name.conf"

private fun l2tpPpp(name: String) = "/etc/ppp/options.$L2TP_PREFIX-$name"

// the device of the tunnel of a connection, which a name of up to 10 characters keeps within the 15 of Linux
private fun l2tpDevice(name: String) = "l2tp-$name"

// what the host has of xl2tpd at one read: the settings of its file and of the file of pppd made here, the users of this server, the
// state of its service, whether the IPsec of L2TP has its file and charon has it loaded, and whether ufw or firewalld keeps NAT for it
internal class L2tpState(
    val conf: List<String>, val ppp: List<String>, val users: List<Pair<String, Boolean>>, val state: String, val autostart: Boolean, val keyed: Boolean,
    val loaded: Boolean, val nat: Set<String>,
) {
    private fun value(key: String) = conf.lastOrNull { '=' in it && it.substringBefore('=').trim() == key }?.substringAfter('=')?.trim().orEmpty()

    val ours get() = value("name") == L2TP_NAME
    val free get() = ours || conf.isEmpty()
    val local get() = value("local ip")
    val pool get() = value("ip range")
    val dns get() = ppp.filter { it.startsWith("ms-dns ") }.joinToString(", ") { it.removePrefix("ms-dns ").trim() }
    val mtu get() = ppp.firstOrNull { it.startsWith("mtu ") }?.removePrefix("mtu ")?.trim().orEmpty()

    fun option(key: String) = ppp.firstOrNull { it.startsWith("$key ") }?.removePrefix("$key ")?.trim().orEmpty()

    fun network() = l2tpNetwork(local, pool)

    fun row() = Item(
        "l2tp",
        linkedMapOf(
            "state" to state, "autostart" to "$autostart", "local-ip" to local, "pool" to pool, "dns" to dns, "mtu" to mtu, "lcp-echo-interval" to option("lcp-echo-interval"),
            "lcp-echo-failure" to option("lcp-echo-failure"), "idle" to option("idle"), "psk" to "", "ipsec-loaded" to "$loaded",
            "users" to "${users.size}", "nat" to "${"l2tp" in nat}", "kept" to if (ours) "NetworkHelm" else "",
        ),
    )
}

internal fun parseL2tp(text: String): L2tpState {
    var head = listOf<String>()
    val conf = mutableListOf<String>()
    val ppp = mutableListOf<String>()
    val users = mutableListOf<Pair<String, Boolean>>()
    val nat = mutableSetOf<String>()
    var ipsec: String? = null
    var section = ""
    for (line in text.lines()) {
        val mark = line.substringBefore(' ')
        val rest = line.substringAfter(' ', "").trim()
        when {
            mark == "--nh-l2tp--" -> {
                head = rest.split(' ')
                section = "conf"
            }
            mark == "--nh-l2tp-ppp--" -> section = "ppp"
            mark == "--nh-l2tp-users--" -> section = "users"
            mark == "--nh-l2tp-ipsec--" -> {
                ipsec = rest
                section = ""
            }
            mark == "--nh-nat--" -> nat += rest.removePrefix("$NAT_MARK-")
            mark.startsWith("--nh-") -> section = ""
            line.isBlank() -> Unit
            section == "conf" -> conf += line.trim()
            section == "ppp" -> ppp += line.trim()
            section == "users" -> users += line.substringBefore(' ') to line.endsWith(" off")
        }
    }
    return L2tpState(conf, ppp, users, head.getOrElse(0) { "" }, head.getOrElse(1) { "" } == "enabled", ipsec != null, (ipsec?.toIntOrNull() ?: 0) > 0, nat)
}

// the smallest network that holds the address of the server and the range of its clients, which NAT goes by; null where the range is
// no range, holds the address of the server or would take a network wider than /8
internal fun l2tpNetwork(local: String, pool: String): String? {
    val address = Ipv4.parse(local) ?: return null
    val (first, last) = Ipv4.parseRange(pool) ?: return null
    if (address in first..last) return null
    return Ipv4.enclosing(minOf(address, first), maxOf(address, last)).takeIf { it.prefix >= 8 }?.cidr
}

private const val L2TP_NAME = "networkhelm-l2tp"
private const val L2TP_CONF = "/etc/xl2tpd/xl2tpd.conf"
private const val L2TP_PPP = "/etc/ppp/options.networkhelm-l2tp"

private val L2TP_LIST = Regex("[,;\\s]+")
private val L2TP_POOL = Regex("\\d{1,3}(\\.\\d{1,3}){3}-\\d{1,3}(\\.\\d{1,3}){3}")
private val L2TP_SEALED = listOf("1701/udp")
private val L2TP_SETTINGS = listOf("local-ip", "pool", "dns", "mtu", "lcp-echo-interval", "lcp-echo-failure", "idle")
private val L2TP_FIELDS = listOf("state", "autostart") + L2TP_SETTINGS + listOf("psk", "ipsec-loaded", "users", "nat", "kept")
private val L2TP_ARGUMENTS = listOf(
    Argument("local-ip", ValueType.Ip4, optional = true, placeholder = "10.20.30.1"),
    Argument("pool", ValueType.Ip4Range, optional = true, placeholder = "10.20.30.10-10.20.30.254"),
    Argument("dns", ValueType.Text(), optional = true, placeholder = "1.1.1.1"),
    Argument("mtu", ValueType.Integer(576, 1500), optional = true, placeholder = "1280"),
    Argument("lcp-echo-interval", ValueType.Integer(0, 86400, unit = "s"), optional = true, placeholder = "30"),
    Argument("lcp-echo-failure", ValueType.Integer(0, 1000), optional = true, placeholder = "4"),
    Argument("idle", ValueType.Integer(0, 86400, unit = "s"), optional = true),
    Argument("psk", ValueType.Secret, optional = true),
    Argument("autostart", ValueType.YesNo, optional = true),
    Argument("nat", ValueType.YesNo, optional = true),
)

private const val L2TP_UNIT = "xl2tpd.service"
private const val L2TP_HOOK = "/etc/systemd/system/xl2tpd.service.d/networkhelm-nat-l2tp.conf"
private val L2TP_HOST = Regex("[A-Za-z0-9.:-]+")
private val L2TP_CLIENT_NAME = Regex("[a-z0-9][a-z0-9-]{0,9}")
private val L2TP_CLIENT_FIELDS = listOf("name", "state", "autostart", "server", "user", "interface")

// the fields of a new server by the tabs of its window; the window shows them filled with what the host proposes
private val L2TP_SERVER_ARGUMENTS = listOf(
    Argument("client-endpoint", ValueType.Text(), optional = true, label = "Address for Clients", tab = "general", placeholder = "vpn.example.net"),
    Argument("autostart", ValueType.YesNo, label = "Start at Boot", tab = "general"),
    Argument("local-ip", ValueType.Ip4, label = "Local Address", tab = "network", placeholder = "10.20.30.1"),
    Argument("pool", ValueType.Ip4Range, label = "Client Addresses", tab = "network", placeholder = "10.20.30.10-10.20.30.254"),
    Argument("dns", ValueType.Text(), optional = true, label = "DNS for Clients", tab = "dns", placeholder = CLIENT_DNS),
    Argument("psk", ValueType.Secret, optional = true, label = "IPsec Secret", tab = "keys"),
    Argument("nat", ValueType.YesNo, label = "NAT for Clients", tab = "nat"),
    Argument("user", ValueType.Text(), optional = true, label = "First User", tab = "clients"),
    Argument("password", ValueType.Secret, optional = true, label = "Password", tab = "clients"),
)

// what the import takes beside the reply of the server, which holds the preshared key and the password and so is a secret
private val L2TP_IMPORT_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), label = "Name"),
    Argument("server", ValueType.Text(), optional = true, label = "Server", placeholder = "from the reply"),
    Argument("psk", ValueType.Secret, optional = true, label = "IPsec Secret", placeholder = "from the reply"),
    Argument("user", ValueType.Text(), optional = true, label = "Login", placeholder = "from the reply"),
    Argument("password", ValueType.Secret, optional = true, label = "Password", placeholder = "from the reply"),
    Argument("autostart", ValueType.YesNo, label = "Start at Boot"),
    Argument("text", ValueType.Secret, label = "Reply of the Server"),
)

// the units of the connections of the host made here with their state, and the server, login and port of their files of xl2tpd
private val L2TP_CLIENT_STATE = $$"""for f in /etc/systemd/system/networkhelm-l2tp-*.service; do [ -f "$f" ] || continue
n=${f##*/networkhelm-l2tp-}; n=${n%.service}
echo "--nh-l2tp-client-- $n $(systemctl is-active "networkhelm-l2tp-$n" 2>/dev/null) $(systemctl is-enabled "networkhelm-l2tp-$n" 2>/dev/null)"
grep -E '^[[:space:]]*(lns|name|port)[[:space:]]*=' "/etc/xl2tpd/networkhelm-l2tp-$n.conf" 2>/dev/null
done
true"""

private const val L2TP_MOBILECONFIG = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>PayloadContent</key>
  <array>
    <dict>
      <key>PayloadType</key><string>com.apple.vpn.managed</string>
      <key>PayloadVersion</key><integer>1</integer>
      <key>PayloadIdentifier</key><string>networkhelm.l2tp.vpn</string>
      <key>PayloadUUID</key><string>{uuid1}</string>
      <key>PayloadDisplayName</key><string>{title}</string>
      <key>UserDefinedName</key><string>{title}</string>
      <key>VPNType</key><string>L2TP</string>
      <key>PPP</key>
      <dict>
        <key>CommRemoteAddress</key><string>{server}</string>
        <key>AuthName</key><string>{user}</string>
      </dict>
      <key>IPSec</key>
      <dict>
        <key>AuthenticationMethod</key><string>SharedSecret</string>
        <key>LocalIdentifierType</key><string>KeyID</string>
        <key>SharedSecret</key><data>{psk}</data>
      </dict>
      <key>IPv4</key>
      <dict>
        <key>OverridePrimary</key><integer>1</integer>
      </dict>
    </dict>
  </array>
  <key>PayloadType</key><string>Configuration</string>
  <key>PayloadVersion</key><integer>1</integer>
  <key>PayloadIdentifier</key><string>networkhelm.l2tp</string>
  <key>PayloadUUID</key><string>{uuid2}</string>
  <key>PayloadDisplayName</key><string>{title}</string>
</dict>
</plist>"""

// the service of xl2tpd, its settings and those of the file of pppd made here without comments, the users of this server in
// chap-secrets by name, never their passwords, and whether charon has the IPsec of L2TP
private val L2TP_STATE = $$"""echo "--nh-l2tp-- $(systemctl is-active xl2tpd 2>/dev/null) $(systemctl is-enabled xl2tpd 2>/dev/null)"
[ ! -f /etc/xl2tpd/xl2tpd.conf ] || grep -vE '^[[:space:]]*([;#]|$)' /etc/xl2tpd/xl2tpd.conf
echo --nh-l2tp-ppp--
[ ! -f /etc/ppp/options.networkhelm-l2tp ] || grep -vE '^[[:space:]]*(#|$)' /etc/ppp/options.networkhelm-l2tp
echo --nh-l2tp-users--
[ ! -f /etc/ppp/chap-secrets ] || awk '{ off = sub(/^#\[disabled\] /, "") } $1 !~ /^#/ && $2 == "networkhelm-l2tp" { n = $1; gsub(/"/, "", n); t = off ? " off" : ""; print n t }' /etc/ppp/chap-secrets
[ ! -f $$L2TP_IPSEC ] || echo "--nh-l2tp-ipsec-- $(swanctl --list-conns 2>/dev/null | grep -c '^networkhelm-l2tp:')"
[ ! -f /etc/systemd/system/xl2tpd.service.d/networkhelm-nat-l2tp.conf ] || echo "--nh-nat-- networkhelm-nat-l2tp"
$$NAT_STATE
true"""
