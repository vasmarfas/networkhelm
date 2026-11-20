package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.quote

// 3proxy of a Linux host (/etc/3proxy.cfg of Fedora, /etc/3proxy/3proxy.cfg elsewhere). The file is read in order: auth, allow and deny build
// the access of the services that follow them until flush, so they stand with the services in one list of lines, moved by dragging; the
// settings before that list and the users of its users lines, whose passwords the read masks and a new one gets as an MD5 crypt made by
// openssl or, on Alpine without it, by cryptpw of busybox from the input. 3proxy has no check of its file: a running one starts again with
// it and gets the old file back when it does not come up
internal class LinuxProxy3proxy(private val root: Root, private val tools: suspend () -> Set<String>) {
    private val service = ProxyService(root, tools, "3proxy")

    val tables: Map<String, HostTable> = mapOf(
        "proxy/3proxy" to HostTable(
            THREE_FIELDS, { listOfNotNull(state().row()) }, settings = true, slow = true, arguments = { THREE_ARGUMENTS }, set = ::set,
            commands = listOf("up", "down"), run = { command, _ -> service.bring(command); emptyList() }, present = { "3proxy" in tools() },
        ),
        "proxy/3proxy/access" to HostTable(
            listOf("directive", "arguments"), { state().text.access().mapIndexed { index, line -> Item("${index + 1}", line.row()) } }, slow = true,
            arguments = { ACCESS_LINE_ARGUMENTS }, add = ::addLine, set = ::setLine, remove = ::removeLines, move = ::moveLines, present = { "3proxy" in tools() },
        ),
        "proxy/3proxy/user" to HostTable(
            listOf("name", "type"), { state().text.users().map { (name, type) -> Item(name, linkedMapOf("name" to name, "type" to type)) } }, slow = true,
            arguments = { THREE_USER_ARGUMENTS }, add = ::addUser, set = ::setUser, remove = { names -> edit { text -> text.users(text.entries().filter { it.name !in names }) } },
            present = { "3proxy" in tools() },
        ),
    )

    private suspend fun state() = parse3proxy(root.output(THREE_STATE))

    private suspend fun set(id: String?, changes: Map<String, String?>) {
        val fields = changes.filterKeys { it in THREE_KEYS }.mapValues { it.value.orEmpty().trim() }
        fields.forEach { (field, value) -> if (!threeSetting(field, value)) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes") }
        if (fields.isNotEmpty()) edit { text -> fields.forEach { (field, value) -> text.set(field, value.split(';').map { it.trim() }.filter { it.isNotEmpty() }) } }
        changes["autostart"]?.let { service.autostart(it in ValueType.Flag.on) }
    }

    private suspend fun addLine(values: Map<String, String>): String {
        val line = accessLine(values["directive"].orEmpty(), values["arguments"].orEmpty())
        var id = ""
        edit { text -> id = "${text.insert(line)}" }
        return id
    }

    private suspend fun setLine(id: String?, changes: Map<String, String?>) = edit { text ->
        val lines = text.access()
        val number = lineNumbers(lines.size, listOf(id.orEmpty())).single()
        val old = lines[number - 1]
        text.replace(lines.mapIndexed { index, line -> if (index == number - 1) accessLine(changes["directive"] ?: old.directive, changes["arguments"] ?: old.arguments) else line })
    }

    private suspend fun removeLines(ids: List<String>) = edit { text ->
        val lines = text.access()
        val gone = lineNumbers(lines.size, ids)
        text.replace(lines.filterIndexed { index, _ -> index + 1 !in gone })
    }

    private suspend fun moveLines(ids: List<String>, before: String?) = edit { text ->
        val lines = text.access()
        val moved = lineNumbers(lines.size, ids)
        val order = lines.indices.map { it + 1 }.filter { it !in moved }.toMutableList()
        val at = before?.let { lineNumbers(lines.size, listOf(it)).single() }?.let { order.indexOf(it) }?.takeIf { it >= 0 } ?: order.size
        order.addAll(at, moved)
        text.replace(order.map { lines[it - 1] })
    }

    private suspend fun addUser(values: Map<String, String>): String {
        val name = values["name"].orEmpty()
        if (!THREE_USER.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
        if (state().text.users().any { it.first == name }) throw DeviceException(ErrorCategory.ArgumentValue, "3proxy has the user $name already")
        val entry = ThreeUser(name, "CR", hash(values["password"].orEmpty()))
        edit { text -> text.users(text.entries() + entry) }
        return name
    }

    private suspend fun setUser(id: String?, changes: Map<String, String?>) {
        if (state().text.users().none { it.first == id }) throw DeviceException(ErrorCategory.MissingItem, "3proxy has no user $id")
        val password = changes["password"]?.ifEmpty { null } ?: return
        val secret = hash(password)
        edit { text -> text.users(text.entries().map { if (it.name == id) ThreeUser(it.name, "CR", secret) else it }) }
    }

    // the crypt of MD5 3proxy takes as CR, made on the host from the input
    private suspend fun hash(password: String): String {
        if (password.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "password: a value is needed")
        if (password.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
        return root.proxied(
            "if command -v openssl >/dev/null 2>&1; then openssl passwd -1 -stdin; elif command -v cryptpw >/dev/null 2>&1; then cryptpw -m md5 -s; " +
                "else echo 'the host has neither openssl nor cryptpw to hash the password: the package openssl gives one' >&2; exit 1; fi",
            "$password\n",
        ).trim().ifEmpty { throw DeviceException(ErrorCategory.Api, "the host has neither openssl nor cryptpw to hash the password: the package openssl gives one") }
    }

    private suspend fun edit(change: (ThreeText) -> Unit) {
        val host = state()
        val conf = host.config ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no config file /etc/3proxy.cfg")
        val before = root.output("cat ${quote(conf)}")
        val text = ThreeText(before)
        change(text)
        if (text.text() != before) service.rewrite(conf, before, text.text(), running(host.state), "600")
    }
}

// a line of the list of access: a directive with its arguments as written
internal class ThreeLine(val directive: String, val arguments: String) {
    fun row() = linkedMapOf("directive" to directive, "arguments" to arguments)

    fun text() = listOf(directive, arguments).filter { it.isNotEmpty() }.joinToString(" ")
}

// an entry of a users line: name, the type of its secret (CL in clear, CR a crypt, NT a hash of NT) and the secret
internal class ThreeUser(val name: String, val type: String, val secret: String) {
    // 3proxy reads a word that starts with $ as a file to include, and a crypt holds $
    fun text() = "$name:$type:$secret".let { if ('$' in it || ' ' in it) "\"$it\"" else it }
}

internal class ThreeState(val state: String, val autostart: Boolean, val config: String?, val text: ThreeText) {
    fun row(): Item? = config?.let { file ->
        Item("3proxy", linkedMapOf("state" to state, "autostart" to "$autostart", "config" to file) + THREE_KEYS.associateWith { text.values(it).joinToString("; ") })
    }
}

internal fun parse3proxy(text: String): ThreeState {
    val head = text.lines().firstOrNull { it.startsWith("--nh-3proxy-- ") }?.split(' ')?.drop(1) ?: return ThreeState("", false, null, ThreeText(""))
    return ThreeState(head.getOrElse(0) { "" }, head.getOrElse(1) { "" } == "enabled", head.getOrNull(2), ThreeText(text.substringAfter("--nh-3proxy-conf--\n", "")))
}

// the lines of the config of 3proxy as written: the list of access starts at its first line of access or service and runs to the end,
// the settings stand before it
internal class ThreeText(text: String) {
    private var lines = text.lines()

    fun text() = lines.joinToString("\n")

    private fun directiveOf(line: String) = line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }?.split(BLANKS, limit = 2)?.first()?.lowercase()

    private fun start() = lines.indexOfFirst { directiveOf(it) in ACCESS_DIRECTIVES }.takeIf { it >= 0 } ?: (lines.indexOfLast { it.isNotBlank() } + 1)

    private fun accessSlots() = (start() until lines.size).filter { directiveOf(lines[it]).let { directive -> directive != null && directive != "users" } }

    fun access() = accessSlots().map { lines[it].trim().split(BLANKS, limit = 2).let { words -> ThreeLine(words[0], words.getOrElse(1) { "" }) } }

    // the lines of access in their places, each given in its place, new ones after the last
    fun replace(access: List<ThreeLine>) {
        val slots = accessSlots()
        val out = lines.toMutableList()
        slots.zip(access).forEach { (slot, line) -> out[slot] = line.text() }
        slots.drop(access.size).sortedDescending().forEach { out.removeAt(it) }
        lines = out
    }

    // a new line goes at the end, after the services: dragging puts it before the one it belongs to
    fun insert(line: ThreeLine): Int {
        val at = lines.indexOfLast { it.isNotBlank() } + 1
        lines = lines.toMutableList().also { it.add(at, line.text()) }
        return accessSlots().size
    }

    fun values(key: String) = (0 until start()).filter { directiveOf(lines[it]) == key }.map { lines[it].trim().split(BLANKS, limit = 2).getOrElse(1) { "" } }

    fun set(key: String, values: List<String>) {
        val slots = (0 until start()).filter { directiveOf(lines[it]) == key }
        val at = slots.firstOrNull() ?: start()
        lines = lines.filterIndexed { index, _ -> index !in slots }.toMutableList().also { it.addAll(at, values.map { value -> "$key $value" }) }
    }

    fun entries() = lines.filter { directiveOf(it) == "users" }.flatMap { words(it.trim()).drop(1) }.map { entry ->
        val parts = entry.split(':', limit = 3)
        ThreeUser(parts[0], parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
    }

    fun users() = entries().map { it.name to it.type }

    // all the users in one line at the place of the first users line, before the list of access where there was none
    fun users(entries: List<ThreeUser>) {
        val slots = lines.indices.filter { directiveOf(lines[it]) == "users" }
        val at = slots.firstOrNull() ?: start()
        lines = lines.filterIndexed { index, _ -> index !in slots }.toMutableList().also {
            if (entries.isNotEmpty()) it.add(at - slots.count { slot -> slot < at }, "users ${entries.joinToString(" ") { entry -> entry.text() }}")
        }
    }
}

private fun lineNumbers(count: Int, ids: List<String>) =
    ids.map { id -> id.toIntOrNull()?.takeIf { it in 1..count } ?: throw DeviceException(ErrorCategory.MissingItem, "3proxy has no rule $id") }

// a setting as 3proxy reads it: the counts as numbers, timeouts as the numbers of seconds of its kinds of waiting, one after another
private fun threeSetting(field: String, value: String) = value.none { it == '\n' || it == '\r' || it == '#' } && when (field) {
    "maxconn", "nscache" -> value.isEmpty() || value.toIntOrNull()?.let { it > 0 } == true
    "rotate" -> value.isEmpty() || value.toIntOrNull()?.let { it >= 0 } == true
    "timeouts" -> value.split(BLANKS).filter { it.isNotEmpty() }.let { numbers -> numbers.size <= 10 && numbers.all { number -> number.toIntOrNull()?.let { it >= 0 } == true } }
    else -> true
}

private fun accessLine(directive: String, arguments: String): ThreeLine {
    if (!DIRECTIVE.matches(directive) || directive == "users") throw DeviceException(ErrorCategory.ArgumentValue, "directive: $directive is not a value it takes")
    if (arguments.any { it == '\n' || it == '\r' || it == '#' }) throw DeviceException(ErrorCategory.ArgumentValue, "arguments: $arguments is not a value it takes")
    return ThreeLine(directive, arguments.trim())
}

private val BLANKS = Regex("\\s+")
private val DIRECTIVE = Regex("[a-z][a-z0-9]*")
private val THREE_USER = Regex("[A-Za-z0-9._@-]+")

// the directives that build the access of the services or are services themselves
private val ACCESS_DIRECTIVES = setOf(
    "auth", "allow", "deny", "flush", "redirect", "parent", "proxy", "socks", "admin", "pop3p", "ftppr", "tcppm", "udppm", "dnspr", "smtpp", "tlspr", "auto",
)

private val THREE_KEYS = listOf("nserver", "nscache", "log", "rotate", "maxconn", "timeouts", "internal", "external")
private val THREE_FIELDS = listOf("state", "autostart", "config") + THREE_KEYS
private val THREE_ARGUMENTS = listOf(
    Argument("nserver", ValueType.Text(), optional = true, placeholder = "1.1.1.1; 8.8.8.8"),
    Argument("nscache", ValueType.Integer(1, 10_000_000), optional = true, placeholder = "65536"),
    Argument("log", ValueType.Text(), optional = true, placeholder = "/var/log/3proxy/3proxy.log D"),
    Argument("rotate", ValueType.Integer(0, 10_000), optional = true),
    Argument("maxconn", ValueType.Integer(1, 1_000_000), optional = true),
    Argument("timeouts", ValueType.Text(), optional = true, placeholder = "1 5 30 60 180 1800 15 60"),
    Argument("internal", ValueType.Text(), optional = true, placeholder = "0.0.0.0"),
    Argument("external", ValueType.Text(), optional = true, placeholder = "0.0.0.0"),
    Argument("autostart", ValueType.YesNo, optional = true),
)
private val ACCESS_LINE_ARGUMENTS = listOf(
    Argument("directive", ValueType.Enumeration(), listOf("auth", "allow", "deny", "flush", "proxy", "socks", "maxconn", "parent", "admin"), default = "allow"),
    Argument("arguments", ValueType.Text(), optional = true, placeholder = "* 192.168.0.0/16"),
)
private val THREE_USER_ARGUMENTS = listOf(Argument("name", ValueType.Text(), fixed = true), Argument("password", ValueType.Secret, optional = true))

// the config without comments, the secrets of the users lines and the password of a parent masked
private val THREE_STATE = $$"""c=
for f in /etc/3proxy.cfg /etc/3proxy/3proxy.cfg; do [ -f "$f" ] && { c=$f; break; }; done
[ -n "$c" ] || exit 0
$$SERVICE_STATE
echo "--nh-3proxy-- $(s 3proxy) $c"
echo --nh-3proxy-conf--
grep -vE '^[[:space:]]*(#|$)' "$c" | sed -e 's/\(:[A-Z][A-Z]:\)[^[:space:]"]*/\1(hidden)/g' -e 's/^\([[:space:]]*parent\([[:space:]][[:space:]]*[^[:space:]][^[:space:]]*\)\{5\}[[:space:]][[:space:]]*\)[^[:space:]][^[:space:]]*$/\1(hidden)/'
true"""
