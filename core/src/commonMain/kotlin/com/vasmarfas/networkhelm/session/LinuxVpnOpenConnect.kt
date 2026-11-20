package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession

// the host as a client of OpenConnect: a unit of systemd of its own for each connection, whose line of openconnect holds the server, the
// protocol, the login and the pin of the certificate, and whose password lies in a file of its own that the unit gives openconnect as
// its input. A start goes under the guard and waits for the device of the tunnel, as the routes the server gives may take the way this
// session comes by
internal class LinuxVpnOpenConnect(private val ssh: SshSession, private val root: Root, private val tools: suspend () -> Set<String>, private val guard: LinuxGuard) {
    val tables: Map<String, HostTable> = mapOf(
        "vpn/openconnect" to HostTable(
            OC_CLIENT_FIELDS, { connections().map { it.row() } }, slow = true, arguments = { OC_CLIENT_ARGUMENTS }, add = ::add, set = ::set, remove = ::remove,
            commands = listOf("up", "down"), run = ::bring, present = { tools().let { "openconnect" in it && "systemctl" in it } },
        ),
        "vpn/openconnect/import" to HostTable(
            OC_IMPORT_ARGUMENTS.map { it.name }, { listOf(Item("", mapOf("name" to freeName(connections()), "autostart" to "true"))) }, settings = true,
            arguments = { OC_IMPORT_ARGUMENTS }, commands = listOf("import"), run = { _, values -> importConnection(values) },
            present = { tools().let { "openconnect" in it && "systemctl" in it } },
        ),
    )

    suspend fun connections() = parseOpenConnect(root.masked(OC_CLIENT_STATE))

    private suspend fun add(values: Map<String, String>): String {
        val name = values["name"].orEmpty()
        if (!OC_CLIENT_NAME.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
        if (connections().any { it.name == name }) throw DeviceException(ErrorCategory.ArgumentValue, "the host has an OpenConnect connection $name already")
        write(name, values, values["password"].orEmpty().ifEmpty { throw DeviceException(ErrorCategory.ArgumentValue, "password: a value is needed") })
        if (values["autostart"] in ValueType.Flag.on) root.masked("systemctl enable ${unit(name)}")
        return name
    }

    // the settings given take the place of those there are; an empty password leaves the file of the one there is
    private suspend fun set(id: String?, changes: Map<String, String?>) {
        val connection = find(connections(), id)
        write(connection.name, connection.values() + changes.mapValues { it.value.orEmpty() }, changes["password"].orEmpty())
        changes["autostart"]?.let { root.masked("systemctl ${if (it in ValueType.Flag.on) "enable" else "disable"} ${unit(connection.name)}") }
    }

    private suspend fun remove(ids: List<String>) {
        val host = connections()
        for (connection in ids.map { find(host, it) }) {
            if (connection.state == "active") keepSession(ssh, device(connection.name))
            root.masked("systemctl disable --now ${unit(connection.name)} >/dev/null 2>&1; rm -f ${quote(file(connection.name))} ${quote(passwd(connection.name))} && systemctl daemon-reload")
        }
    }

    // a connection that does not come up is stopped at once; one this session comes in by is not stopped
    private suspend fun bring(command: String, arguments: Map<String, String>): List<Item> {
        val host = connections()
        for (connection in arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }.map { find(host, it) }) {
            val unit = unit(connection.name)
            when (command) {
                "up" -> if (connection.state != "active") guard.guarded(LinuxGuard.Backup("vpn", "true", "systemctl stop $unit")) { root.masked(unitWithDevice(unit, device(connection.name), "openconnect")) }
                "down" -> if (connection.state == "active") {
                    keepSession(ssh, device(connection.name))
                    root.masked("systemctl stop $unit")
                }
            }
        }
        return emptyList()
    }

    // a connection to a server of ocserv from what its new server gave, pasted as it is, or from the fields given, which go first; it
    // comes up under the guard
    private suspend fun importConnection(values: Map<String, String>): List<Item> {
        val given = loginLines(values["text"].orEmpty())
        val fields = mapOf("server" to "server", "pin" to "pin", "user" to "login", "password" to "password").mapValues { (field, key) -> values[field].orEmpty().ifEmpty { given[key].orEmpty() } }
        listOf("server", "user", "password").firstOrNull { fields[it].isNullOrEmpty() }?.let { throw DeviceException(ErrorCategory.ArgumentValue, "$it: a value is needed") }
        val name = add(values + fields)
        bring("up", mapOf("numbers" to name))
        return listOf(Item(name, mapOf("name" to name)))
    }

    private fun find(host: List<OpenConnectClient>, id: String?) =
        host.firstOrNull { it.name == id } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no OpenConnect connection $id")

    // the unit with the line of openconnect, and the password, where one is given, in its file
    private suspend fun write(name: String, values: Map<String, String>, password: String) {
        val given = { key: String -> values[key].orEmpty() }
        val protocol = given("protocol").ifEmpty { "anyconnect" }
        // the window of ocserv shows the pin as bare base64
        val pin = given("pin").let { if (OC_BARE_PIN.matches(it)) "pin-sha256:$it" else it }
        val bad = when {
            !OC_CLIENT_SERVER.matches(given("server")) -> "server" to given("server")
            protocol !in OC_PROTOCOLS -> "protocol" to protocol
            !OC_CLIENT_USER.matches(given("user")) -> "user" to given("user")
            pin.isNotEmpty() && !OC_CLIENT_PIN.matches(pin) -> "pin" to pin
            else -> null
        }
        bad?.let { (field, value) -> throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes") }
        if (password.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
        val binary = root.masked("command -v openconnect").trim()
        val line = listOfNotNull(
            binary, "--non-inter", "--passwd-on-stdin", "--protocol=$protocol", "--user=${given("user")}", pin.ifEmpty { null }?.let { "--servercert=$it" },
            "--interface=${device(name)}", given("server"),
        ).joinToString(" ")
        if (password.isNotEmpty()) root.write(passwd(name), password, "true")
        val text = listOf(
            "# a connection of OpenConnect made by NetworkHelm", "[Unit]", "Description=OpenConnect connection $name", "After=network-online.target",
            "Wants=network-online.target", "", "[Service]", "Type=simple", "StandardInput=file:${passwd(name)}", "ExecStart=$line", "Restart=on-failure",
            "RestartSec=30", "", "[Install]", "WantedBy=multi-user.target",
        )
        root.write(file(name), text.joinToString("\n"), "systemctl daemon-reload 2>&1", mode = "644")
    }

    private fun unit(name: String) = "networkhelm-openconnect-$name.service"

    private fun file(name: String) = "/etc/systemd/system/${unit(name)}"

    private fun passwd(name: String) = "/etc/networkhelm/openconnect/$name.passwd"
}

// a connection as its unit shows it: the options of its line of openconnect and the state of the unit
internal class OpenConnectClient(val name: String, val state: String, val autostart: Boolean, val options: Map<String, String>, val server: String) {
    fun values() = mapOf("server" to server, "protocol" to options["protocol"].orEmpty(), "user" to options["user"].orEmpty(), "pin" to options["servercert"].orEmpty())

    fun row() = Item(name, linkedMapOf("name" to name, "state" to state, "autostart" to "$autostart") + values() + mapOf("password" to "", "interface" to device(name)))
}

// the units of the connections made here, each after a line --nh-openconnect-- <name> <active> <enabled>, as their ExecStart has them
internal fun parseOpenConnect(text: String): List<OpenConnectClient> {
    val found = mutableListOf<OpenConnectClient>()
    var head: List<String>? = null
    for (line in text.lines()) {
        when {
            line.startsWith("--nh-openconnect-- ") -> head = line.removePrefix("--nh-openconnect-- ").trim().split(' ')
            head != null && line.isNotBlank() -> {
                val words = line.trim().split(Regex("\\s+")).drop(1)
                val options = words.filter { it.startsWith("--") }.associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=', "") }
                found += OpenConnectClient(head[0], head.getOrElse(1) { "" }, head.getOrElse(2) { "" } == "enabled", options, words.lastOrNull { !it.startsWith("--") }.orEmpty())
                head = null
            }
        }
    }
    return found
}

// what the reply of a new server gives each on a line of its own, server: vpn.example.net:443, by the words before the colon
internal fun loginLines(text: String): Map<String, String> = text.lines().filter { ':' in it }.associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }

// the first name client, then client-2 and on, that no connection of the host has
private fun freeName(host: List<OpenConnectClient>) =
    (sequenceOf("client") + generateSequence(2) { it + 1 }.map { "client-$it" }).first { free -> host.none { it.name == free } }

// the device of the tunnel of a connection, which a name of up to 12 characters keeps within the 15 of Linux
private fun device(name: String) = "oc-$name"

private val OC_CLIENT_NAME = Regex("[a-z0-9][a-z0-9-]{0,11}")
private val OC_CLIENT_SERVER = Regex("(https://)?[A-Za-z0-9.:\\[\\]-]+(/[A-Za-z0-9._/-]*)?")
private val OC_CLIENT_USER = Regex("[A-Za-z0-9_.@-]{1,64}")
private val OC_CLIENT_PIN = Regex("(pin-sha256:[A-Za-z0-9+/]{43}=|sha256:[0-9a-fA-F]{64}|sha1:[0-9a-fA-F]{40})")
private val OC_BARE_PIN = Regex("[A-Za-z0-9+/]{43}=")
private val OC_PROTOCOLS = listOf("anyconnect", "nc", "gp", "pulse", "f5", "fortinet", "array")

private val OC_CLIENT_FIELDS = listOf("name", "state", "autostart", "server", "protocol", "user", "pin", "password", "interface")
private val OC_CLIENT_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), fixed = true),
    Argument("server", ValueType.Text(), placeholder = "vpn.example.net:443"),
    Argument("protocol", ValueType.Enumeration(closed = true), OC_PROTOCOLS, optional = true, placeholder = "anyconnect"),
    Argument("user", ValueType.Text()),
    Argument("password", ValueType.Secret, optional = true),
    Argument("pin", ValueType.Text(), optional = true, placeholder = "pin-sha256:..."),
    Argument("autostart", ValueType.YesNo, optional = true),
)

// what the import takes beside the reply of the server, which may hold a password and so is a secret
private val OC_IMPORT_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(), label = "Name"),
    Argument("server", ValueType.Text(), optional = true, label = "Server", placeholder = "vpn.example.net:443"),
    Argument("pin", ValueType.Text(), optional = true, label = "Certificate Pin", placeholder = "pin-sha256:..."),
    Argument("user", ValueType.Text(), optional = true, label = "Login"),
    Argument("password", ValueType.Secret, optional = true, label = "Password"),
    Argument("autostart", ValueType.YesNo, label = "Start at Boot"),
    Argument("text", ValueType.Secret, label = "Reply of the Server"),
)

// the units of the connections made here with their state and their line of openconnect, which holds no password
private val OC_CLIENT_STATE = $$"""for f in /etc/systemd/system/networkhelm-openconnect-*.service; do [ -f "$f" ] || continue
n=${f##*/networkhelm-openconnect-}; n=${n%.service}
echo "--nh-openconnect-- $n $(systemctl is-active "networkhelm-openconnect-$n" 2>/dev/null) $(systemctl is-enabled "networkhelm-openconnect-$n" 2>/dev/null)"
sed -n 's/^ExecStart=//p' "$f" | head -n 1
done
true"""
