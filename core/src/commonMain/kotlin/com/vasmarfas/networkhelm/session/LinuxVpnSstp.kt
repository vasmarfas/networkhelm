package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession

// the host as a client of SSTP by sstpc: a unit of systemd of its own for each connection with the server, the login and the CA of the
// server on the line of sstpc, and pppd after it with the name of the connection as remotename. The password is no option of sstpc, which
// would show it to ps: it is a line of chap-secrets under that name, which pppd finds as it logs in. A start goes under the guard and waits
// for the device of the tunnel. The certificate of the server is checked against its CA or the CAs of the system unless the check is
// skipped, and a connection that takes the default route keeps the way to the end of this session outside the tunnel
internal class LinuxVpnSstp(private val ssh: SshSession, private val root: Root, private val tools: suspend () -> Set<String>, private val guard: LinuxGuard) {
    val tables: Map<String, HostTable> = mapOf(
        "vpn/sstp" to HostTable(
            SSTP_FIELDS, { connections().map { it.row() } }, slow = true, arguments = { SSTP_ARGUMENTS }, add = ::add, set = ::set, remove = ::remove,
            commands = listOf("up", "down"), run = ::bring, present = { tools().let { "sstpc" in it && "systemctl" in it } },
        ),
    )

    suspend fun connections() = parseSstp(root.masked(SSTP_STATE))

    private suspend fun add(values: Map<String, String>): String {
        val name = values["name"].orEmpty()
        if (!SSTP_NAME.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
        if (connections().any { it.name == name }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has an SSTP connection $name already")
        if (values["password"].isNullOrEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "password: a value is needed")
        write(name, values)
        ChapSecrets(root, sstpServer(name)).add(values["user"].orEmpty(), values["password"].orEmpty(), false)
        if (values["autostart"] in ValueType.Flag.on) root.masked("systemctl enable ${sstpUnit(name)}")
        return name
    }

    // the settings given take the place of those there are; the line of chap-secrets follows a new login and keeps the password where
    // none is given
    private suspend fun set(id: String?, changes: Map<String, String?>) {
        val connection = find(connections(), id)
        val values = connection.values() + changes.mapValues { it.value.orEmpty() }
        val user = values["user"].orEmpty()
        val password = changes["password"].orEmpty()
        if (user != connection.user && password.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "password: a value is needed")
        write(connection.name, values)
        val chap = ChapSecrets(root, sstpServer(connection.name))
        when {
            user != connection.user -> {
                chap.remove(listOf(connection.user))
                chap.add(user, password, false)
            }
            password.isNotEmpty() -> chap.set(user, password, null)
        }
        changes["autostart"]?.let { root.masked("systemctl ${if (it in ValueType.Flag.on) "enable" else "disable"} ${sstpUnit(connection.name)}") }
    }

    private suspend fun remove(ids: List<String>) {
        val host = connections()
        for (connection in ids.map { find(host, it) }) {
            if (connection.state == "active") keepSession(ssh, sstpDevice(connection.name))
            root.masked("systemctl disable --now ${sstpUnit(connection.name)} >/dev/null 2>&1; rm -f ${quote(sstpFile(connection.name))} && systemctl daemon-reload")
            ChapSecrets(root, sstpServer(connection.name)).remove(listOf(connection.user))
        }
    }

    // a connection that does not come up is stopped at once; one this session comes in by is not stopped
    private suspend fun bring(command: String, arguments: Map<String, String>): List<Item> {
        val host = connections()
        for (connection in arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }.map { find(host, it) }) {
            val unit = sstpUnit(connection.name)
            when (command) {
                "up" -> if (connection.state != "active") {
                    guard.guarded(LinuxGuard.Backup("vpn", "true", "systemctl stop $unit")) { root.masked(unitWithDevice(unit, sstpDevice(connection.name), "sstpc")) }
                }
                "down" -> if (connection.state == "active") {
                    keepSession(ssh, sstpDevice(connection.name))
                    root.masked("systemctl stop $unit")
                }
            }
        }
        return emptyList()
    }

    private fun find(host: List<SstpClient>, id: String?) =
        host.firstOrNull { it.name == id } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no SSTP connection $id")

    // the unit with the line of sstpc and the options of pppd after the server. Without a CA file the certificate of the server is checked
    // against the bundle of the CAs of the system, unless the check is skipped, when sstpc only warns. The default route goes to the tunnel
    // in place of the one there is; sstpc keeps a route of its own to the server, and the unit one to the end of this session
    private suspend fun write(name: String, values: Map<String, String>) {
        val given = { key: String -> values[key].orEmpty() }
        val bad = when {
            !SSTP_SERVER.matches(given("server")) -> "server" to given("server")
            !SSTP_USER.matches(given("user")) -> "user" to given("user")
            given("ca-file").isNotEmpty() && !SSTP_CA.matches(given("ca-file")) -> "ca-file" to given("ca-file")
            given("mtu").isNotEmpty() && given("mtu").toIntOrNull() !in 576..1500 -> "mtu" to given("mtu")
            else -> null
        }
        bad?.let { (field, value) -> throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes") }
        val ca = given("ca-file").ifEmpty {
            if (given("skip-cert-check") in ValueType.Flag.on) "" else root.masked(SYSTEM_CA).trim().ifEmpty { throw DeviceException(ErrorCategory.Unsupported, NO_BUNDLE) }
        }
        val full = given("default-route") in ValueType.Flag.on
        val binary = root.masked("command -v sstpc").trim()
        val line = listOf(binary, "--log-stderr", "--save-server-route", "--user", given("user")) +
            (if (ca.isEmpty()) listOf("--cert-warn") else listOf("--ca-cert", ca)) +
            listOf(given("server"), "remotename", sstpServer(name), "ipparam", sstpServer(name), "ifname", sstpDevice(name), "require-mschap-v2", "noauth", "noipdefault") +
            (if (full) listOf("defaultroute", "replacedefaultroute") else listOf("nodefaultroute")) + listOfNotNull("usepeerdns".takeIf { given("peer-dns") in ValueType.Flag.on }) +
            given("mtu").ifEmpty { null }?.let { listOf("mtu", it, "mru", it) }.orEmpty()
        val route = (if (full) sessionWay(ssh) else null)?.let { way ->
            val ip = root.masked("command -v ip").trim()
            "$ip ${if (':' in way.address) "-6" else "-4"} route" to way.target
        }
        val text = listOf(
            "# a connection of SSTP made by NetworkHelm; its password is the line of /etc/ppp/chap-secrets under ${sstpServer(name)}", "[Unit]",
            "Description=SSTP connection $name", "After=network-online.target", "Wants=network-online.target", "", "[Service]", "Type=simple",
        ) + listOfNotNull(route?.let { (ip, target) -> "ExecStartPre=-$ip replace $target" }, "ExecStart=${line.joinToString(" ")}") +
            listOfNotNull(route?.let { (ip, target) -> "ExecStopPost=-$ip del $target" }) + listOf("Restart=on-failure", "RestartSec=30", "", "[Install]", "WantedBy=multi-user.target")
        root.write(sstpFile(name), text.joinToString("\n"), "systemctl daemon-reload 2>&1", mode = "644")
    }
}

// a connection as its unit shows it: the server, the login, the CA file and the options of pppd on its line of sstpc, and the state of the
// unit
internal class SstpClient(
    val name: String, val state: String, val autostart: Boolean, val server: String, val user: String, val ca: String, val skip: Boolean, val defaultRoute: Boolean,
    val peerDns: Boolean, val mtu: String,
) {
    fun values() = mapOf(
        "server" to server, "user" to user, "ca-file" to ca, "skip-cert-check" to "$skip", "default-route" to "$defaultRoute", "peer-dns" to "$peerDns", "mtu" to mtu,
    )

    fun row() = Item(name, linkedMapOf("name" to name, "state" to state, "autostart" to "$autostart") + values() + mapOf("password" to "", "interface" to sstpDevice(name)))
}

// the units of the connections made here, each after a line --nh-sstp-- <name> <active> <enabled>, as their ExecStart has them
internal fun parseSstp(text: String): List<SstpClient> {
    val found = mutableListOf<SstpClient>()
    var head: List<String>? = null
    for (line in text.lines()) {
        when {
            line.startsWith("--nh-sstp-- ") -> head = line.removePrefix("--nh-sstp-- ").trim().split(' ')
            head != null && line.isNotBlank() -> {
                val words = line.trim().split(Regex("\\s+")).drop(1)
                fun option(name: String) = words.indexOf(name).takeIf { it >= 0 }?.let { words.getOrNull(it + 1) }.orEmpty()
                val server = words.indices.firstOrNull { !words[it].startsWith("--") && words.getOrNull(it - 1) !in SSTP_VALUED }?.let { words[it] }.orEmpty()
                found += SstpClient(
                    head[0], head.getOrElse(1) { "" }, head.getOrElse(2) { "" } == "enabled", server, option("--user"), option("--ca-cert"), "--cert-warn" in words,
                    "replacedefaultroute" in words || "defaultroute" in words, "usepeerdns" in words, option("mtu"),
                )
                head = null
            }
        }
    }
    return found
}

private fun sstpUnit(name: String) = "networkhelm-sstp-$name.service"

private fun sstpFile(name: String) = "/etc/systemd/system/${sstpUnit(name)}"

// the name of the connection as pppd takes the server, under which chap-secrets keeps the password
private fun sstpServer(name: String) = "networkhelm-sstp-$name"

// the device of pppd for a connection, which a name of up to 10 characters keeps within the 15 of Linux
private fun sstpDevice(name: String) = "sstp-$name"

private val SSTP_NAME = Regex("[a-z0-9][a-z0-9-]{0,9}")
private val SSTP_SERVER = Regex("[A-Za-z0-9.:\\[\\]-]+")
private val SSTP_USER = Regex("[A-Za-z0-9_.@-]{1,64}")
private val SSTP_CA = Regex("/[A-Za-z0-9._/+-]+")

// the options of sstpc that take a value after them
private val SSTP_VALUED = setOf("--user", "--ca-cert", "--ca-path", "--log-level", "--priv-user", "--password")

private val SSTP_FIELDS = listOf("name", "state", "autostart", "server", "user", "password", "ca-file", "skip-cert-check", "default-route", "peer-dns", "mtu", "interface")
private val SSTP_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), fixed = true),
    Argument("server", ValueType.Text(), placeholder = "vpn.example.net"),
    Argument("user", ValueType.Text()),
    Argument("password", ValueType.Secret, optional = true),
    Argument("ca-file", ValueType.Text(), optional = true, placeholder = "/etc/ssl/certs/ISRG_Root_X1.pem"),
    Argument("skip-cert-check", ValueType.YesNo, optional = true),
    Argument("default-route", ValueType.YesNo, optional = true),
    Argument("peer-dns", ValueType.YesNo, optional = true),
    Argument("mtu", ValueType.Integer(576, 1500), optional = true),
    Argument("autostart", ValueType.YesNo, optional = true),
)

// the bundle of the CAs of the system: of Debian, Ubuntu, Arch and Alpine, of Fedora, and the file of OpenSSL elsewhere
private const val SYSTEM_CA = "for f in /etc/ssl/certs/ca-certificates.crt /etc/pki/tls/certs/ca-bundle.crt /etc/ssl/cert.pem; do [ -f \"\$f\" ] && echo \"\$f\" && break; done; true"

private const val NO_BUNDLE = "the host has no bundle of CA certificates: give the CA File of the server or tick Skip Certificate Check"

// the units of the connections made here with their state and their line of sstpc, which holds no password
private val SSTP_STATE = $$"""for f in /etc/systemd/system/networkhelm-sstp-*.service; do [ -f "$f" ] || continue
n=${f##*/networkhelm-sstp-}; n=${n%.service}
echo "--nh-sstp-- $n $(systemctl is-active "networkhelm-sstp-$n" 2>/dev/null) $(systemctl is-enabled "networkhelm-sstp-$n" 2>/dev/null)"
sed -n 's/^ExecStart=//p' "$f" | head -n 1
done
true"""
