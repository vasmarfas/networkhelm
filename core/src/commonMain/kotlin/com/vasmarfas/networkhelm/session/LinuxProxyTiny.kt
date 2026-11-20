package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.quote

// tinyproxy of a Linux host: the settings of tinyproxy.conf and its users of BasicAuth, whose passwords stand in the file as they are and
// which the read never shows. tinyproxy has no check of its file: a running one is restarted, and when it does not come up the old file
// comes back and starts again. The values are checked against the patterns tinyproxy 1.11 parses its lines by (src/conf.c), so a line it
// would refuse never reaches the file
internal class LinuxProxyTiny(private val root: Root, private val tools: suspend () -> Set<String>) {
    private val service = ProxyService(root, tools, "tinyproxy")

    val tables: Map<String, HostTable> = mapOf(
        "proxy/tinyproxy" to HostTable(
            TINY_FIELDS, { listOfNotNull(state().row()) }, settings = true, slow = true, arguments = { TINY_ARGUMENTS }, set = ::set,
            commands = listOf("up", "down"), run = { command, _ -> service.bring(command); emptyList() }, present = { "tinyproxy" in tools() },
        ),
        "proxy/tinyproxy/user" to HostTable(
            listOf("name"), { state().users().map { Item(it, linkedMapOf("name" to it)) } }, slow = true, arguments = { TINY_USER_ARGUMENTS }, add = ::addUser,
            set = ::setUser, remove = { names -> edit { text -> text.removeUsers(names) } }, present = { "tinyproxy" in tools() },
        ),
    )

    private suspend fun state() = parseTiny(root.output(TINY_STATE))

    private suspend fun set(id: String?, changes: Map<String, String?>) {
        val fields = changes.filterKeys { it in TINY_DIRECTIVES || it in UPSTREAM }.mapValues { (field, value) -> if (field == "upstream-password") value.orEmpty() else value.orEmpty().trim() }
        fields.forEach { (field, value) -> tinyCheck(field, value) }
        if (fields.isNotEmpty()) {
            edit { text ->
                fields.filterKeys { it in TINY_DIRECTIVES }.forEach { (field, value) -> text.put(field, value) }
                if (fields.keys.any { it in UPSTREAM }) text.upstream(fields["upstream"], fields["upstream-user"], fields["upstream-password"])
            }
        }
        changes["autostart"]?.let { service.autostart(it in ValueType.Flag.on) }
    }

    private suspend fun addUser(values: Map<String, String>): String {
        val name = values["name"].orEmpty()
        if (!TINY_WORD.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
        if (name in state().users()) throw DeviceException(ErrorCategory.ArgumentValue, "tinyproxy has the user $name already")
        val password = values["password"].orEmpty()
        if (password.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "password: a value is needed")
        tinyPassword(password)
        edit { text -> text.putUser(name, password) }
        return name
    }

    private suspend fun setUser(id: String?, changes: Map<String, String?>) {
        val name = id?.takeIf { it in state().users() } ?: throw DeviceException(ErrorCategory.MissingItem, "tinyproxy has no user $id")
        val password = changes["password"]?.ifEmpty { null } ?: return
        tinyPassword(password)
        edit { text -> text.putUser(name, password) }
    }

    // tinyproxy.conf read whole, as it is written; a running tinyproxy that does not come up with the new file gets the old one back
    private suspend fun edit(change: (TinyText) -> Unit) {
        val host = state()
        val conf = host.config ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no config file ${TINY_CONFS.first()}")
        val before = root.output("cat ${quote(conf)}")
        val text = TinyText(before)
        change(text)
        if (text.text() != before) service.rewrite(conf, before, text.text(), running(host.state), "640")
    }
}

// what the host has of tinyproxy at one read: the state of its service, its config file and version, and its directives without
// comments, with the passwords of BasicAuth and of the upstream masked
internal class TinyState(val state: String, val autostart: Boolean, val config: String?, val version: String, val directives: List<Pair<String, String>>) {
    fun values(key: String) = directives.filter { it.first.equals(key, ignoreCase = true) }.map { it.second }

    fun value(key: String) = values(key).lastOrNull().orEmpty()

    fun users() = values("BasicAuth").map { it.split(BLANKS).first() }

    // yes and on of tinyproxy as the tick of a form reads them
    private fun flag(key: String) = value(key).ifEmpty { null }?.let { "${it.lowercase() in setOf("yes", "on")}" }.orEmpty()

    fun row(): Item? {
        if (config == null) return null
        val upstream = values("Upstream").map { it.split(BLANKS) }.firstOrNull { it.size == 2 && !it[0].equals("none", ignoreCase = true) }
        val login = upstream?.get(1)?.substringBefore('@', "").orEmpty()
        return Item(
            "tinyproxy",
            linkedMapOf(
                "state" to state, "autostart" to "$autostart", "version" to version, "config" to config, "port" to value("Port"), "listen" to value("Listen"),
                "bind" to values("Bind").joinToString(", "), "allow" to values("Allow").joinToString(", "), "deny" to values("Deny").joinToString(", "),
                "connect-ports" to values("ConnectPort").joinToString(", "), "max-clients" to value("MaxClients"), "timeout" to value("Timeout"),
                "log-level" to value("LogLevel"), "disable-via-header" to flag("DisableViaHeader"), "via-proxy-name" to value("ViaProxyName").removeSurrounding("\""),
                "xtinyproxy" to flag("XTinyproxy"), "upstream" to upstream?.let { "${it[0]} ${it[1].substringAfter('@')}" }.orEmpty(),
                "upstream-user" to login.substringBefore(':'), "upstream-password" to "", "users" to "${users().size}",
            ),
        )
    }
}

internal fun parseTiny(text: String): TinyState {
    var head = listOf<String>()
    var version = ""
    val directives = mutableListOf<Pair<String, String>>()
    var section = ""
    for (line in text.lines()) {
        val mark = line.substringBefore(' ')
        val rest = line.substringAfter(' ', "").trim()
        when {
            mark == "--nh-tiny--" -> head = rest.split(' ')
            mark == "--nh-tiny-version--" -> version = rest
            mark == "--nh-tiny-conf--" -> section = "conf"
            mark.startsWith("--nh-") -> section = ""
            line.isBlank() || line.trim().startsWith("#") -> Unit
            // a user of BasicAuth comes by its name alone, whatever the host left after it
            section == "conf" -> line.trim().split(BLANKS, limit = 2).let { words ->
                val value = words.getOrElse(1) { "" }.trim()
                directives += words[0] to if (words[0].equals("BasicAuth", ignoreCase = true)) value.split(BLANKS).first() else value
            }
        }
    }
    return TinyState(head.getOrElse(0) { "" }, head.getOrElse(1) { "" } == "enabled", head.getOrNull(2), version, directives)
}

// the lines of tinyproxy.conf as written; its directives are words of any case
private class TinyText(text: String) {
    private val lines = text.lines().toMutableList()

    fun text() = lines.joinToString("\n")

    private fun keyOf(line: String) = line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }?.split(BLANKS, limit = 2)?.first()?.lowercase()

    private fun indices(key: String) = lines.indices.filter { keyOf(lines[it]) == key.lowercase() }

    private fun end() = lines.indexOfLast { it.isNotBlank() } + 1

    private fun set(key: String, values: List<String>) {
        val slots = indices(key)
        val at = slots.firstOrNull() ?: end()
        slots.sortedDescending().forEach { lines.removeAt(it) }
        lines.addAll(at, values)
    }

    fun put(field: String, value: String) {
        val key = TINY_DIRECTIVES.getValue(field)
        when (field) {
            "allow", "deny", "connect-ports", "bind" -> set(key, value.split(TINY_LIST).filter { it.isNotEmpty() }.map { "$key $it" })
            "disable-via-header", "xtinyproxy" -> set(key, listOfNotNull(value.ifEmpty { null }?.let { "$key ${if (it in ValueType.Flag.on) "Yes" else "No"}" }))
            "via-proxy-name" -> set(key, listOfNotNull(value.ifEmpty { null }?.let { "$key \"$it\"" }))
            else -> set(key, listOfNotNull(value.ifEmpty { null }?.let { "$key $it" }))
        }
    }

    // the upstream for any site, the one without a domain; its password stays when the user does
    fun upstream(target: String?, user: String?, password: String?) {
        val index = indices("upstream").firstOrNull { lines[it].trim().split(BLANKS).let { words -> words.size == 3 && !words[1].equals("none", ignoreCase = true) } }
        val words = index?.let { lines[it].trim().split(BLANKS) }
        val login = words?.get(2)?.substringBefore('@', "").orEmpty()
        val oldUser = login.substringBefore(':')
        val newTarget = target ?: words?.let { "${it[1]} ${it[2].substringAfter('@')}" }.orEmpty()
        val newUser = user ?: oldUser
        val newPassword = password?.ifEmpty { null } ?: login.substringAfter(':', "").takeIf { newUser == oldUser }.orEmpty()
        val line = newTarget.ifEmpty { null }?.split(BLANKS)?.let { (type, address) -> "Upstream $type ${if (newUser.isEmpty()) "" else "$newUser:$newPassword@"}$address" }
        when {
            index != null && line != null -> lines[index] = line
            index != null -> lines.removeAt(index)
            line != null -> lines.add(end(), line)
        }
    }

    fun putUser(name: String, password: String) {
        val users = indices("basicauth")
        val line = "BasicAuth $name $password"
        val index = users.firstOrNull { lines[it].trim().split(BLANKS).getOrNull(1) == name }
        if (index != null) lines[index] = line else lines.add(users.lastOrNull()?.plus(1) ?: end(), line)
    }

    fun removeUsers(names: List<String>) {
        lines.removeAll { keyOf(it) == "basicauth" && it.trim().split(BLANKS).getOrNull(1) in names }
    }
}

// tinyproxy reads the name and the password of BasicAuth as words of letters, digits, dots, dashes and underscores; one of the upstream stands
// inside user:password@
private fun tinyPassword(password: String) {
    if (!TINY_WORD.matches(password)) throw DeviceException(ErrorCategory.ArgumentValue, "a password of BasicAuth of tinyproxy takes letters, digits, dots, dashes and underscores alone")
}

private fun tinyCheck(field: String, value: String) {
    val items = value.split(TINY_LIST).filter { it.isNotEmpty() }
    if (field == "upstream-password") {
        if (value.any { it.isWhitespace() || it == '"' || it == '@' || it == ':' }) {
            throw DeviceException(ErrorCategory.ArgumentValue, "the password of the upstream may hold no space, quote, colon or @")
        }
        return
    }
    val valid = value.isEmpty() || when (field) {
        "port" -> value.toIntOrNull() in 1..65535
        "listen" -> TINY_ADDRESS.matches(value)
        "bind" -> items.all(TINY_ADDRESS::matches)
        "allow", "deny" -> items.all(TINY_SOURCE::matches)
        "connect-ports" -> items.all { it.toIntOrNull() in 1..65535 }
        "max-clients", "timeout" -> value.toIntOrNull()?.let { it > 0 } == true
        "log-level" -> value in LOG_LEVELS
        "disable-via-header", "xtinyproxy" -> value in ValueType.Flag.on || value.lowercase() in listOf("0", "no", "off", "false")
        "via-proxy-name" -> value.none { it == '"' }
        "upstream" -> TINY_UPSTREAM.matchEntire(value)?.groupValues?.get(3)?.toIntOrNull() in 1..65535
        "upstream-user" -> value.none { it.isWhitespace() || it == '"' || it == '@' || it == ':' }
        else -> true
    }
    if (!valid) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes")
}

private val BLANKS = Regex("\\s+")
private val TINY_LIST = Regex("[,;\\s]+")
private val TINY_WORD = Regex("[A-Za-z0-9._-]+")
private val TINY_ADDRESS = Regex("[0-9A-Fa-f.:]+")
private val TINY_SOURCE = Regex("[0-9A-Fa-f.:]+(/\\d{1,3})?|[A-Za-z0-9._-]+")
private val TINY_UPSTREAM = Regex("(http|socks4|socks5)\\s+([A-Za-z0-9._-]+):(\\d{1,5})")
private val LOG_LEVELS = listOf("Critical", "Error", "Warning", "Notice", "Connect", "Info")
private val UPSTREAM = setOf("upstream", "upstream-user", "upstream-password")

private val TINY_DIRECTIVES = mapOf(
    "port" to "Port", "listen" to "Listen", "bind" to "Bind", "allow" to "Allow", "deny" to "Deny", "connect-ports" to "ConnectPort", "max-clients" to "MaxClients",
    "timeout" to "Timeout", "log-level" to "LogLevel", "disable-via-header" to "DisableViaHeader", "via-proxy-name" to "ViaProxyName", "xtinyproxy" to "XTinyproxy",
)
private val TINY_FIELDS = listOf("state", "autostart", "version", "config") + TINY_DIRECTIVES.keys + listOf("upstream", "upstream-user", "upstream-password", "users")
private val TINY_ARGUMENTS = listOf(
    Argument("port", ValueType.Integer(1, 65535), optional = true, placeholder = "8888"),
    Argument("listen", ValueType.Ip, optional = true),
    Argument("bind", ValueType.Text(), optional = true, placeholder = "192.0.2.10"),
    Argument("allow", ValueType.Text(), optional = true, placeholder = "127.0.0.1, 192.168.0.0/16"),
    Argument("deny", ValueType.Text(), optional = true, placeholder = "192.168.1.13"),
    Argument("connect-ports", ValueType.Text(), optional = true, placeholder = "443, 563"),
    Argument("max-clients", ValueType.Integer(1, 1_000_000), optional = true),
    Argument("timeout", ValueType.Integer(1, 1_000_000, unit = "s"), optional = true),
    Argument("log-level", ValueType.Enumeration(closed = true), LOG_LEVELS, optional = true),
    Argument("disable-via-header", ValueType.YesNo, optional = true),
    Argument("via-proxy-name", ValueType.Text(), optional = true, placeholder = "tinyproxy"),
    Argument("xtinyproxy", ValueType.YesNo, optional = true),
    Argument("upstream", ValueType.Text(), optional = true, placeholder = "http proxy.example.net:3128"),
    Argument("upstream-user", ValueType.Text(), optional = true),
    Argument("upstream-password", ValueType.Secret, optional = true),
    Argument("autostart", ValueType.YesNo, optional = true),
)
private val TINY_USER_ARGUMENTS = listOf(Argument("name", ValueType.Text(), fixed = true), Argument("password", ValueType.Secret, optional = true))

// the directives without comments, the passwords of BasicAuth and of the upstream masked
private val TINY_STATE = $$"""c=
for f in $${TINY_CONFS.joinToString(" ")}; do [ -f "$f" ] && { c=$f; break; }; done
[ -n "$c" ] || exit 0
$$SERVICE_STATE
echo "--nh-tiny-- $(s tinyproxy) $c"
echo "--nh-tiny-version-- $(tinyproxy -v 2>/dev/null | awk '{ print $2 }')"
echo --nh-tiny-conf--
grep -vE '^[[:space:]]*(#|$)' "$c" | $$PROXY_MASK
true"""
