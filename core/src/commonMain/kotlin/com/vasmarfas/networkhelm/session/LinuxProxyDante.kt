package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.quote

// Dante of a Linux host: danted with /etc/danted.conf on Debian and Ubuntu, sockd with /etc/sockd.conf on Fedora, Arch and Alpine. The
// settings outside the rules, the client and socks rules in the order Dante checks them, and the users of the method username: accounts of
// the system without a shell, marked networkhelm-socks in their comment, whose passwords go to chpasswd as its input. A changed file goes
// through -V of the server and comes back when that fails; a running server starts again with it and gets the old file back when it does not
// come up
internal class LinuxProxyDante(private val root: Root, private val tools: suspend () -> Set<String>) {
    val tables: Map<String, HostTable> = mapOf(
        "proxy/dante" to HostTable(
            DANTE_FIELDS, { listOfNotNull(state().row()) }, settings = true, slow = true, arguments = { DANTE_ARGUMENTS }, set = ::set,
            commands = listOf("up", "down"), run = { command, _ -> ProxyService(root, tools, server().first).bring(command); emptyList() }, present = ::dante,
        ),
        "proxy/dante/rule" to HostTable(
            RULE_FIELDS, { state().rules().mapIndexed { index, rule -> Item("${index + 1}", rule.row()) } }, slow = true, arguments = { RULE_ARGUMENTS },
            add = ::addRule, set = ::setRule, remove = ::removeRules, move = ::moveRules, present = ::dante,
        ),
        "proxy/dante/user" to HostTable(
            listOf("name"), { state().users.map { Item(it, linkedMapOf("name" to it)) } }, slow = true, arguments = { DANTE_USER_ARGUMENTS }, add = ::addUser,
            set = ::setUser, remove = ::removeUsers, present = ::dante,
        ),
    )

    private suspend fun dante() = "danted" in tools() || "sockd" in tools()

    // the name of the server, which is its service as well, and its config file
    private suspend fun server() = if ("danted" in tools()) "danted" to "/etc/danted.conf" else "sockd" to "/etc/sockd.conf"

    private suspend fun state(): DanteState {
        val (name, conf) = server()
        return parseDante(root.output(danteState(name, conf)))
    }

    private suspend fun set(id: String?, changes: Map<String, String?>) {
        val fields = changes.filterKeys { it in DANTE_KEYS }.mapValues { it.value.orEmpty().trim() }
        fields.forEach { (field, value) -> if (!danteSetting(field, value)) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes") }
        if (fields.isNotEmpty()) {
            edit { text ->
                fields.forEach { (field, value) -> text.set(DANTE_KEYS.getValue(field), value.split(';').map { it.trim() }.filter { it.isNotEmpty() }) }
                if (text.username() && fields["user-privileged"].let { it != null && it.isNotEmpty() && it != "root" }) {
                    throw DeviceException(ErrorCategory.ArgumentValue, "the method username needs user.privileged root: Dante reads /etc/shadow as that user")
                }
            }
        }
        changes["autostart"]?.let { ProxyService(root, tools, server().first).autostart(it in ValueType.Flag.on) }
    }

    // a new rule goes before the rule of its kind that takes any address, as a pass after it would never be reached; without options of its
    // own it logs its connections, without log Dante writes nothing of the clients of a rule
    private suspend fun addRule(values: Map<String, String>): String {
        val rule = danteRule(values["kind"].orEmpty(), values["action"].orEmpty(), values["from"].orEmpty(), values["to"].orEmpty(), values["options"] ?: RULE_LOG)
        var id = ""
        edit { text -> id = "${text.insert(rule)}" }
        return id
    }

    private suspend fun setRule(id: String?, changes: Map<String, String?>) = edit { text ->
        val rules = text.rules()
        val number = numbers(rules.size, listOf(id.orEmpty())).single()
        val old = rules[number - 1]
        val rule = danteRule(
            changes["kind"] ?: old.kind, changes["action"] ?: old.action, changes["from"] ?: old.from, changes["to"] ?: old.to,
            changes["options"] ?: old.options.joinToString("; "),
        )
        text.replace(rules.mapIndexed { index, it -> if (index == number - 1) rule else it })
    }

    private suspend fun removeRules(ids: List<String>) = edit { text ->
        val rules = text.rules()
        val gone = numbers(rules.size, ids)
        text.replace(rules.mapIndexed { index, rule -> rule.takeIf { index + 1 !in gone } })
    }

    // the rules keep the places of the file the rules had and take them in the new order
    private suspend fun moveRules(ids: List<String>, before: String?) = edit { text ->
        val rules = text.rules()
        val moved = numbers(rules.size, ids)
        val order = rules.indices.map { it + 1 }.filter { it !in moved }.toMutableList()
        val at = before?.let { numbers(rules.size, listOf(it)).single() }?.let { order.indexOf(it) }?.takeIf { it >= 0 } ?: order.size
        order.addAll(at, moved)
        text.replace(order.map { rules[it - 1] })
    }

    private suspend fun addUser(values: Map<String, String>): String {
        val name = values["name"].orEmpty()
        if (!DANTE_USER.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
        if (name in state().users) throw DeviceException(ErrorCategory.ArgumentValue, "Dante has the user $name already")
        val password = values["password"].orEmpty()
        danteCheck(password)
        root.proxied(
            $$"""u=$${quote(name)}
if command -v useradd >/dev/null 2>&1; then useradd -M -s "$(command -v nologin || echo /sbin/nologin)" -c $$USER_MARK "$u"; else adduser -D -H -s /sbin/nologin -g $$USER_MARK "$u"; fi || exit 1
$$PASSWORD""",
            "$password\n",
        )
        return name
    }

    private suspend fun setUser(id: String?, changes: Map<String, String?>) {
        val name = id?.takeIf { it in state().users } ?: throw DeviceException(ErrorCategory.MissingItem, "Dante has no user $id")
        val password = changes["password"]?.ifEmpty { null } ?: return
        danteCheck(password)
        root.proxied("u=${quote(name)}\n$PASSWORD", "$password\n")
    }

    // only an account the window made goes, by its mark
    private suspend fun removeUsers(ids: List<String>) {
        val users = state().users
        ids.firstOrNull { it !in users }?.let { throw DeviceException(ErrorCategory.MissingItem, "Dante has no user $it") }
        root.proxied(
            $$"""for u in $${ids.joinToString(" ") { quote(it) }}; do if command -v userdel >/dev/null 2>&1; then userdel "$u"; else deluser "$u"; fi || exit 1; done""",
        )
    }

    private suspend fun edit(change: (DanteText) -> Unit) {
        val (name, conf) = server()
        val host = state()
        if (host.config == null) throw DeviceException(ErrorCategory.MissingItem, "the host has no config file $conf")
        val before = root.output("cat ${quote(conf)}")
        val text = DanteText(before)
        change(text)
        // the method username checks a password against /etc/shadow, which the user of privileged operations reads only as root, and
        // Debian gives proxy there
        if (text.username() && text.values("user.privileged") != listOf("root")) text.set("user.privileged", listOf("root"))
        if (text.text() != before) ProxyService(root, tools, name).rewrite(conf, before, text.text(), running(host.state), "644", "$name -V -f ${quote(conf)} 2>&1")
    }
}

// a rule of Dante: client rules are checked as a client connects, socks rules for each request it makes; from and to are the first line of
// the block, the other lines its options, each a key with a colon
internal class DanteRule(val kind: String, val action: String, val from: String, val to: String, val options: List<String>) {
    fun row() = linkedMapOf("kind" to kind, "action" to action, "from" to from, "to" to to, "options" to options.joinToString("; "))

    fun lines() = listOf("$kind $action {", "\tfrom: $from to: $to") + options.map { "\t$it" } + "}"
}

// what the host has of Dante at one read: the state of its service, its config file and version, its lines without comments and the
// accounts of its users
internal class DanteState(val state: String, val autostart: Boolean, val config: String?, val version: String, private val text: DanteText, val users: List<String>) {
    fun rules() = text.rules()

    fun row(): Item? = config?.let { file ->
        Item("dante", linkedMapOf("state" to state, "autostart" to "$autostart", "version" to version, "config" to file) + DANTE_KEYS.mapValues { (_, key) -> text.values(key).joinToString("; ") })
    }
}

internal fun parseDante(text: String): DanteState {
    var head = listOf<String>()
    var version = ""
    val conf = mutableListOf<String>()
    val users = mutableListOf<String>()
    var section = ""
    for (line in text.lines()) {
        val mark = line.substringBefore(' ')
        when {
            mark == "--nh-dante--" -> head = line.substringAfter(' ').trim().split(' ')
            mark == "--nh-dante-version--" -> version = line.substringAfter(' ').trim().removeSuffix(".")
            mark == "--nh-dante-conf--" -> section = "conf"
            mark == "--nh-dante-users--" -> section = "users"
            section == "conf" -> conf += line
            section == "users" && line.isNotBlank() -> users += line.trim()
        }
    }
    return DanteState(head.getOrElse(0) { "" }, head.getOrElse(1) { "" } == "enabled", head.getOrNull(2), version, DanteText(conf.joinToString("\n")), users)
}

// the lines of the config of Dante as written: a block runs from its opening brace to its closing one, a setting is a key with a colon
// outside them, and a rule a block of client or socks, pass or block
internal class DanteText(text: String) {
    private var lines = text.lines()

    fun text() = lines.joinToString("\n")

    private fun bare(line: String) = line.substringBefore('#')

    private fun blocks(): List<IntRange> = buildList {
        var at = 0
        while (at < lines.size) {
            if ('{' in bare(lines[at])) {
                val end = (at until lines.size).firstOrNull { '}' in bare(lines[it]) } ?: lines.lastIndex
                add(at..end)
                at = end + 1
            } else {
                at++
            }
        }
    }

    private fun ruleBlocks() = blocks().filter { RULE_START.containsMatchIn(bare(lines[it.first])) }

    private fun keyOf(line: String) = bare(line).takeIf { ':' in it }?.substringBefore(':')?.trim()

    private fun outside(): List<Int> {
        val inside = blocks().flatMap { it.toList() }.toSet()
        return lines.indices.filter { it !in inside }
    }

    private fun end() = lines.indexOfLast { it.isNotBlank() } + 1

    fun values(key: String) = outside().filter { keyOf(lines[it]) == key }.map { bare(lines[it]).substringAfter(':').trim() }

    // the method username stands among the methods of the server or of a socks rule
    fun username() = (values("socksmethod") + rules().flatMap { rule -> rule.options.filter { it.startsWith("socksmethod:") }.map { it.substringAfter(':') } })
        .any { "username" in it.trim().split(WORDS) }

    // the settings stand before the first block, as the manual of Dante puts them
    fun set(key: String, values: List<String>) {
        val slots = outside().filter { keyOf(lines[it]) == key }
        val at = slots.firstOrNull() ?: blocks().firstOrNull()?.first ?: end()
        lines = lines.filterIndexed { index, _ -> index !in slots }.toMutableList().also { it.addAll(at, values.map { value -> "$key: $value" }) }
    }

    fun rules() = ruleBlocks().map { range ->
        val block = lines.subList(range.first, range.last + 1).joinToString("\n")
        val head = RULE_START.find(bare(lines[range.first]))?.groupValues.orEmpty()
        val kind = head.getOrElse(1) { "" }
        val action = head.getOrElse(2) { "" }
        val body = block.substringAfter('{').substringBeforeLast('}').lines().map { bare(it).trim() }.filter { it.isNotEmpty() }
        val route = body.firstOrNull { it.startsWith("from:") }.orEmpty()
        DanteRule(kind, action, route.substringAfter("from:").substringBefore("to:").trim(), route.substringAfter("to:", "").trim(), body.filter { it != route })
    }

    // the rules in their places in the file, each one given in its place or left out where it is null
    fun replace(rules: List<DanteRule?>) {
        val ranges = ruleBlocks()
        val out = mutableListOf<String>()
        var at = 0
        ranges.forEachIndexed { index, range ->
            out += lines.subList(at, range.first)
            rules.getOrNull(index)?.let { out += it.lines() }
            at = range.last + 1
        }
        out += lines.subList(at, lines.size)
        lines = out
    }

    fun insert(rule: DanteRule): Int {
        val rules = rules()
        val ranges = ruleBlocks()
        val catchAll = rules.indexOfFirst { it.kind == rule.kind && it.from == ANY && it.to == ANY }
        val last = rules.indexOfLast { it.kind == rule.kind }
        val at = when {
            catchAll >= 0 -> ranges[catchAll].first
            last >= 0 -> ranges[last].last + 1
            ranges.isNotEmpty() -> ranges.last().last + 1
            else -> end()
        }
        lines = lines.toMutableList().also { it.addAll(at, rule.lines()) }
        return ruleBlocks().indexOfFirst { it.first == at } + 1
    }
}

private fun numbers(count: Int, ids: List<String>) =
    ids.map { id -> id.toIntOrNull()?.takeIf { it in 1..count } ?: throw DeviceException(ErrorCategory.MissingItem, "Dante has no rule $id") }

private fun danteRule(kind: String, action: String, from: String, to: String, options: String): DanteRule {
    if (kind !in listOf("client", "socks")) throw DeviceException(ErrorCategory.ArgumentValue, "kind: $kind is not a value it takes")
    if (action !in listOf("pass", "block")) throw DeviceException(ErrorCategory.ArgumentValue, "action: $action is not a value it takes")
    for ((field, value) in listOf("from" to from, "to" to to)) {
        if (value.isBlank()) throw DeviceException(ErrorCategory.ArgumentValue, "$field: a value is needed")
        if (value.any { it in FORBIDDEN } || "to:" in value) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes")
    }
    val lines = options.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    lines.firstOrNull { line -> ':' !in line || line.any { it in FORBIDDEN } }?.let { throw DeviceException(ErrorCategory.ArgumentValue, "options: $it is not a value it takes") }
    return DanteRule(kind, action, from.trim(), to.trim(), lines)
}

// a setting of the server as danted.conf(5) takes it: the rotation of the external addresses by its words, the timeouts in seconds
private fun danteSetting(field: String, value: String) = value.none { it in FORBIDDEN } && when (field) {
    "external-rotation" -> value in listOf("", "none", "route", "same-same")
    "timeout-negotiate", "timeout-connect", "timeout-io" -> value.isEmpty() || value.toIntOrNull()?.let { it >= 0 } == true
    else -> true
}

private fun danteCheck(password: String) {
    if (password.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "password: a value is needed")
    if (password.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
}

private const val USER_MARK = "networkhelm-socks"
private const val ANY = "0.0.0.0/0"
private const val RULE_LOG = "log: error connect disconnect"

// the password comes as the first line of the input and goes to chpasswd beside the name, never on a command line
private const val PASSWORD = $$"""IFS= read -r p; printf '%s:%s\n' "$u" "$p" | chpasswd"""

private val RULE_START = Regex("^\\s*(client|socks)\\s+(pass|block)\\s*\\{")
private val FORBIDDEN = setOf('\n', '\r', '#', '{', '}')
private val WORDS = Regex("\\s+")
private val DANTE_USER = Regex("[a-z_][a-z0-9_-]{0,31}")

private val DANTE_KEYS = linkedMapOf(
    "internal" to "internal", "external" to "external", "external-rotation" to "external.rotation", "socksmethod" to "socksmethod", "clientmethod" to "clientmethod",
    "user-privileged" to "user.privileged", "user-unprivileged" to "user.unprivileged", "logoutput" to "logoutput", "timeout-negotiate" to "timeout.negotiate",
    "timeout-connect" to "timeout.connect", "timeout-io" to "timeout.io",
)
private val DANTE_FIELDS = listOf("state", "autostart", "version", "config") + DANTE_KEYS.keys
private val DANTE_ARGUMENTS = listOf(
    Argument("internal", ValueType.Text(), optional = true, placeholder = "0.0.0.0 port = 1080"),
    Argument("external", ValueType.Text(), optional = true, placeholder = "eth0"),
    Argument("external-rotation", ValueType.Enumeration(closed = true), listOf("none", "route", "same-same"), optional = true),
    Argument("socksmethod", ValueType.Text(), optional = true, placeholder = "username"),
    Argument("clientmethod", ValueType.Text(), optional = true, placeholder = "none"),
    Argument("user-privileged", ValueType.Text(), optional = true, placeholder = "proxy"),
    Argument("user-unprivileged", ValueType.Text(), optional = true, placeholder = "nobody"),
    Argument("logoutput", ValueType.Text(), optional = true, placeholder = "syslog"),
    Argument("timeout-negotiate", ValueType.Integer(0, 86_400, unit = "s"), optional = true),
    Argument("timeout-connect", ValueType.Integer(0, 86_400, unit = "s"), optional = true),
    Argument("timeout-io", ValueType.Integer(0, 2_592_000, unit = "s"), optional = true),
    Argument("autostart", ValueType.YesNo, optional = true),
)
private val RULE_FIELDS = listOf("kind", "action", "from", "to", "options")
private val RULE_ARGUMENTS = listOf(
    Argument("kind", ValueType.Enumeration(closed = true), listOf("client", "socks"), default = "socks"),
    Argument("action", ValueType.Enumeration(closed = true), listOf("pass", "block"), default = "pass"),
    Argument("from", ValueType.Text(), placeholder = ANY),
    Argument("to", ValueType.Text(), placeholder = ANY),
    Argument("options", ValueType.Text(), optional = true, default = RULE_LOG, placeholder = "log: error connect disconnect; socksmethod: username"),
)
private val DANTE_USER_ARGUMENTS = listOf(Argument("name", ValueType.Text(), fixed = true), Argument("password", ValueType.Secret, optional = true))

private fun danteState(name: String, conf: String) = $$"""c=$$conf
[ -f "$c" ] || exit 0
$$SERVICE_STATE
echo "--nh-dante-- $(s $$name) $c"
echo "--nh-dante-version-- $($$name -v 2>&1 | awk 'NR == 1 { print $2 }')"
echo --nh-dante-conf--
grep -vE '^[[:space:]]*(#|$)' "$c"
echo --nh-dante-users--
awk -F: '$5 == "$$USER_MARK" { print $1 }' /etc/passwd
true"""
