package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.quote

// Squid of a Linux host: the settings of squid.conf, its http_access rules in their order, its acl lines, and the users of the file
// basic_ncsa_auth reads, whose passwords go to openssl as its input and whose hashes the read never shows. A changed squid.conf goes
// through squid -k parse and comes back when that fails; a running Squid takes it with squid -k reconfigure, a new cache_dir with a restart.
// Sizes and times go with their units, as Squid takes a bare number for bytes or seconds and only warns of it
internal class LinuxProxySquid(private val root: Root, private val tools: suspend () -> Set<String>) {
    private val service = ProxyService(root, tools, "squid")

    val tables: Map<String, HostTable> = mapOf(
        "proxy/squid" to HostTable(
            SQUID_FIELDS, { listOfNotNull(state().row()) }, settings = true, slow = true, arguments = { SQUID_ARGUMENTS }, set = ::set,
            commands = listOf("up", "down"), run = { command, _ -> service.bring(command); emptyList() }, present = { "squid" in tools() },
        ),
        "proxy/squid/access" to HostTable(
            listOf("action", "acls"), { state().access() }, slow = true, arguments = { ACCESS_ARGUMENTS }, add = ::addAccess, set = ::setAccess,
            remove = { ids -> edit { text -> text.remove(HTTP_ACCESS, numbers(text, HTTP_ACCESS, ids)) } }, move = ::moveAccess, present = { "squid" in tools() },
        ),
        "proxy/squid/acl" to HostTable(
            listOf("name", "type", "values"), { state().acls() }, slow = true, arguments = { ACL_ARGUMENTS }, add = ::addAcl, set = ::setAcl,
            remove = { ids -> edit { text -> text.remove(ACL, numbers(text, ACL, ids)) } }, present = { "squid" in tools() },
        ),
        "proxy/squid/user" to HostTable(
            listOf("name"), { state().users.map { Item(it, linkedMapOf("name" to it)) } }, slow = true, arguments = { USER_ARGUMENTS }, add = ::addUser,
            set = ::setUser, remove = ::removeUsers, present = { "squid" in tools() },
        ),
        "proxy/squid/client" to HostTable(TRAFFIC_FIELDS, { traffic("client") }, slow = true, present = { "squid" in tools() }),
        "proxy/squid/domain" to HostTable(TRAFFIC_FIELDS, { traffic("domain") }, slow = true, present = { "squid" in tools() }),
    )

    private suspend fun traffic(kind: String) = parseTraffic(root.output(SQUID_TRAFFIC), kind)

    private suspend fun state() = parseSquid(root.output(SQUID_STATE))

    private suspend fun set(id: String?, changes: Map<String, String?>) {
        val host = state()
        if (!host.found) throw DeviceException(ErrorCategory.MissingItem, "the host has no config file $SQUID_CONF")
        val fields = changes.filterKeys { it in SQUID_SETTINGS }.mapValues { it.value.orEmpty().trim() }
        fields.forEach { (field, value) -> squidCheck(field, value) }
        val parent = changes.filterKeys { it in PARENT }.mapValues { (field, value) -> if (field == "parent-password") value.orEmpty() else value.orEmpty().trim() }
        parent.forEach { (field, value) -> parentCheck(field, value) }
        val auth = changes["auth"]?.let { it in ValueType.Flag.on }?.takeIf { it != host.auth() }
        val cache = changes["cache"]?.let { it in ValueType.Flag.on }
        val helper = if (auth == true) host.helper ?: throw DeviceException(ErrorCategory.Unsupported, "the host has no basic_ncsa_auth of Squid") else ""
        if (auth == true) root.proxied(passwdMade(PASSWD))
        if (fields.isNotEmpty() || auth != null || parent.isNotEmpty() || cache != null) {
            edit(cacheDir = "cache-dir" in fields) { text ->
                fields.forEach { (field, value) -> text.put(field, value) }
                when (auth) {
                    true -> text.authOn(helper)
                    false -> text.authOff()
                    null -> Unit
                }
                if (parent.isNotEmpty()) text.parent(parent["parent"], parent["parent-user"], parent["parent-password"])
                cache?.let(text::cache)
            }
        }
        changes["autostart"]?.let { service.autostart(it in ValueType.Flag.on) }
    }

    // squid.conf read whole, as it is written, changed and written back through the check of Squid
    private suspend fun edit(cacheDir: Boolean = false, change: (SquidText) -> Unit) {
        val running = running(state().state)
        val before = root.output("cat $SQUID_CONF")
        val text = SquidText(before)
        change(text)
        if (text.text() == before) return
        root.write(SQUID_CONF, text.text(), SQUID_CHECK, mode = "644", failed = { "FATAL" in it })
        when {
            cacheDir && running -> {
                service.bring("down")
                root.proxied("squid -N -z -f $SQUID_CONF >/dev/null")
                service.bring("up")
            }
            cacheDir -> root.proxied("squid -N -z -f $SQUID_CONF >/dev/null")
            running -> root.proxied("squid -k reconfigure -f $SQUID_CONF")
        }
    }

    private suspend fun addAccess(values: Map<String, String>): String {
        val line = accessLine(values["action"].orEmpty(), values["acls"].orEmpty())
        var id = ""
        edit { text -> id = "${text.insert(HTTP_ACCESS, line, text.catchAll())}" }
        return id
    }

    private suspend fun setAccess(id: String?, changes: Map<String, String?>) = edit { text ->
        val number = numbers(text, HTTP_ACCESS, listOf(id.orEmpty())).single()
        val old = text.words(HTTP_ACCESS, number)
        text.replace(HTTP_ACCESS, number, accessLine(changes["action"] ?: old.getOrElse(1) { "" }, changes["acls"] ?: old.drop(2).joinToString(" ")))
    }

    private suspend fun moveAccess(ids: List<String>, before: String?) = edit { text ->
        text.move(HTTP_ACCESS, numbers(text, HTTP_ACCESS, ids), before?.let { numbers(text, HTTP_ACCESS, listOf(it)).single() })
    }

    private suspend fun addAcl(values: Map<String, String>): String {
        val line = aclLine(values["name"].orEmpty(), values["type"].orEmpty(), values["values"].orEmpty(), "")
        var id = ""
        edit { text -> id = "${text.insert(ACL, line, null)}" }
        return id
    }

    // the comment after the values stays with them, as squid.conf of Debian has one on each acl of localnet
    private suspend fun setAcl(id: String?, changes: Map<String, String?>) = edit { text ->
        val number = numbers(text, ACL, listOf(id.orEmpty())).single()
        val old = text.words(ACL, number)
        val comment = text.line(ACL, number).let { line -> COMMENT.find(line)?.value.orEmpty() }
        val values = old.drop(3).joinToString(" ").replace(COMMENT, "")
        text.replace(ACL, number, aclLine(changes["name"] ?: old.getOrElse(1) { "" }, changes["type"] ?: old.getOrElse(2) { "" }, changes["values"] ?: values, comment))
    }

    private suspend fun addUser(values: Map<String, String>): String {
        val name = values["name"].orEmpty()
        if (!USER.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
        val host = state()
        val file = host.passwd ?: throw noUsers()
        if (name in host.users) throw DeviceException(ErrorCategory.ArgumentValue, "Squid has the user $name already")
        putUser(file, name, values["password"].orEmpty())
        return name
    }

    private suspend fun setUser(id: String?, changes: Map<String, String?>) {
        val host = state()
        val file = host.passwd ?: throw noUsers()
        val name = id?.takeIf { it in host.users } ?: throw DeviceException(ErrorCategory.MissingItem, "Squid has no user $id")
        changes["password"]?.ifEmpty { null }?.let { putUser(file, name, it) }
    }

    private suspend fun removeUsers(ids: List<String>) {
        val file = state().passwd ?: throw noUsers()
        root.proxied(
            $$"""umask 077; t=$(mktemp) || exit 1; for u in $${ids.joinToString(" ") { quote(it) }}; do awk -F: -v u="$u" '$1 != u' $${quote(file)} > "$t" && cat "$t" > $${quote(file)} || { rm -f "$t"; exit 1; }; done; rm -f "$t"""",
        )
    }

    // the hash is made on the host from the input, by openssl or, on Alpine without it, by cryptpw of busybox; the file is written in place
    // and keeps its owner and mode, so the helper of Squid still reads it
    private suspend fun putUser(file: String, name: String, password: String) {
        if (password.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "password: a value is needed")
        if (password.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
        root.proxied(
            passwdMade(file) + "\n" + $$"""umask 077; f=$${quote(file)}; u=$${quote(name)}
if command -v openssl >/dev/null 2>&1; then h=$(openssl passwd -apr1 -stdin); elif command -v cryptpw >/dev/null 2>&1; then h=$(cryptpw -m md5 -s); else echo 'the host has neither openssl nor cryptpw to hash the password: the package openssl gives one' >&2; exit 1; fi
[ -n "$h" ] || exit 1
{ awk -F: -v u="$u" '$1 != u' "$f"; printf '%s:%s\n' "$u" "$h"; } > "$f.networkhelm-new" && cat "$f.networkhelm-new" > "$f"; r=$?; rm -f "$f.networkhelm-new"; exit $r""",
            "$password\n",
        )
    }

    private fun noUsers() = DeviceException(ErrorCategory.Unsupported, "Squid takes no users of its own: no auth_param of it names basic_ncsa_auth")
}

// what the host has of Squid at one read: the state of its service, its version, the helper of basic auth it ships, the lines of
// squid.conf without comments and with the passwords of cache_peer masked, and the users of the file basic_ncsa_auth reads
internal class SquidState(
    val found: Boolean, val state: String, val autostart: Boolean, val version: String, val helper: String?, val directives: List<Pair<String, String>>,
    val passwd: String?, val users: List<String>, val parentUser: String,
) {
    fun values(key: String) = directives.filter { it.first == key }.map { it.second }

    // Squid takes the last line of a directive that holds one value
    fun value(key: String) = values(key).lastOrNull().orEmpty()

    fun auth() = values("auth_param").any { it.split(BLANKS).take(2) == listOf("basic", "program") }

    fun row(): Item? = if (!found) null else Item(
        "squid",
        linkedMapOf(
            "state" to state, "autostart" to "$autostart", "version" to version, "http-port" to values("http_port").joinToString(", ") { it.split(BLANKS).first() },
            "auth" to "${auth()}", "users" to "${users.size}", "cache" to "${values("cache").none { it.split(BLANKS) == listOf("deny", "all") }}",
            "cache-dir" to values("cache_dir").joinToString("; "), "dns-nameservers" to values("dns_nameservers").flatMap { it.split(BLANKS) }.joinToString(", "),
            "access-log" to values("access_log").joinToString("; "),
        ) + SINGLE.associateWith { value(SQUID_DIRECTIVES.getValue(it)) } + parent() + ("config" to SQUID_CONF),
    )

    // the first parent of cache_peer as host:port and its user; its password the read never has
    private fun parent(): Map<String, String> {
        val words = values(PEER).map { it.split(BLANKS) }.firstOrNull { it.getOrNull(1) == "parent" }
        return mapOf("parent" to words?.let { "${it[0]}:${it.getOrElse(2) { "" }}" }.orEmpty(), "parent-user" to parentUser, "parent-password" to "")
    }

    fun access() = values(HTTP_ACCESS).mapIndexed { index, rest ->
        val words = rest.split(BLANKS)
        Item("${index + 1}", linkedMapOf("action" to words.first(), "acls" to words.drop(1).joinToString(" ")))
    }

    fun acls() = values(ACL).mapIndexed { index, rest ->
        val words = rest.replace(COMMENT, "").split(BLANKS)
        Item("${index + 1}", linkedMapOf("name" to words.first(), "type" to words.getOrElse(1) { "" }, "values" to words.drop(2).joinToString(" ")))
    }
}

// the clients or the sites by the bytes of the last day, week and month, as the host summed them up; a line of another kind or shape is
// left out
internal fun parseTraffic(text: String, kind: String): List<Item> = text.lines().map { it.trim().split(BLANKS) }.filter { it.size == 6 && it[0] == kind }.map { words ->
    Item(words[1], linkedMapOf("name" to words[1], "requests" to words[2], "day-bytes" to words[3], "week-bytes" to words[4], "month-bytes" to words[5]))
}

internal fun parseSquid(text: String): SquidState {
    var head = listOf<String>()
    var version = ""
    var helper: String? = null
    var passwd: String? = null
    var parentUser = ""
    val directives = mutableListOf<Pair<String, String>>()
    val users = mutableListOf<String>()
    var section = ""
    for (line in text.lines()) {
        val mark = line.substringBefore(' ')
        val rest = line.substringAfter(' ', "").trim()
        when {
            mark == "--nh-squid--" -> head = rest.split(' ')
            mark == "--nh-squid-version--" -> version = rest
            mark == "--nh-squid-helper--" -> helper = rest
            mark == "--nh-squid-parent-user--" -> parentUser = rest
            mark == "--nh-squid-conf--" -> section = "conf"
            mark == "--nh-squid-users--" -> {
                passwd = rest
                section = "users"
            }
            mark.startsWith("--nh-") -> section = ""
            line.isBlank() || line.trim().startsWith("#") -> Unit
            section == "conf" -> line.trim().split(BLANKS, limit = 2).let { directives += it[0] to it.getOrElse(1) { "" }.trim() }
            section == "users" -> users += line.trim()
        }
    }
    return SquidState(head.isNotEmpty(), head.getOrElse(0) { "" }, head.getOrElse(1) { "" } == "enabled", version, helper, directives, passwd, users, parentUser)
}

// the lines of squid.conf as written; a directive is the first word of a line, and the lines of acl and http_access are counted from 1
// in the order Squid reads them
private class SquidText(text: String) {
    private val lines = text.lines().toMutableList()

    fun text() = lines.joinToString("\n")

    private fun keyOf(line: String) = line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }?.split(BLANKS, limit = 2)?.first()

    fun indices(key: String) = lines.indices.filter { keyOf(lines[it]) == key }

    private fun end() = lines.indexOfLast { it.isNotBlank() } + 1

    fun line(key: String, number: Int) = lines[indices(key)[number - 1]]

    fun words(key: String, number: Int) = line(key, number).trim().split(BLANKS)

    fun replace(key: String, number: Int, line: String) {
        lines[indices(key)[number - 1]] = line
    }

    fun remove(key: String, numbers: List<Int>) {
        val slots = indices(key)
        numbers.map { slots[it - 1] }.sortedDescending().forEach { lines.removeAt(it) }
    }

    // a new line goes before the one of the number given; an acl without it after the last acl, before the rules, or at the end
    fun insert(key: String, line: String, before: Int?): Int {
        val slots = indices(key)
        val at = when {
            before != null -> slots[before - 1]
            slots.isNotEmpty() -> slots.last() + 1
            else -> indices(HTTP_ACCESS).firstOrNull() ?: end()
        }
        lines.add(at, line)
        return indices(key).indexOf(at) + 1
    }

    // the lines keep their places in the file and take the rules in the new order: the rules around an include stay around it
    fun move(key: String, numbers: List<Int>, before: Int?) {
        val slots = indices(key)
        val rules = slots.map { lines[it] }
        val order = rules.indices.map { it + 1 }.filter { it !in numbers }.toMutableList()
        val at = before?.let { order.indexOf(it) }?.takeIf { it >= 0 } ?: order.size
        order.addAll(at, numbers)
        slots.forEachIndexed { index, slot -> lines[slot] = rules[order[index] - 1] }
    }

    // the rule that takes every request, http_access deny all, closes the list, and a new rule goes before it
    fun catchAll(): Int? = indices(HTTP_ACCESS).indexOfFirst { lines[it].trim().split(BLANKS).drop(2) == listOf("all") }.takeIf { it >= 0 }?.plus(1)

    // a directive of one value on one line, or of several values each on its line, at the place of its first line or at the end
    private fun set(key: String, values: List<String>) {
        val slots = indices(key)
        val at = slots.firstOrNull() ?: end()
        slots.sortedDescending().forEach { lines.removeAt(it) }
        lines.addAll(at, values)
    }

    fun put(field: String, value: String) {
        val key = SQUID_DIRECTIVES.getValue(field)
        when (field) {
            "http-port" -> {
                val old = indices(key).map { lines[it] }.associateBy { it.trim().split(BLANKS).getOrElse(1) { "" } }
                set(key, value.split(SQUID_LIST).filter { it.isNotEmpty() }.map { old[it] ?: "$key $it" })
            }
            "cache-dir", "access-log" -> set(key, value.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { "$key $it" })
            "dns-nameservers" -> set(key, listOfNotNull(value.split(SQUID_LIST).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }?.let { "$key $it" }))
            else -> set(key, listOfNotNull(value.ifEmpty { null }?.let { "$key $it" }))
        }
    }

    // the users of basic_ncsa_auth may use the proxy from anywhere, before the rule that closes the list
    fun authOn(helper: String) {
        val first = indices(ACL).firstOrNull() ?: end()
        lines.addAll(first, listOf("auth_param basic program $helper $PASSWD", "auth_param basic realm proxy"))
        insert(ACL, "acl $USERS_ACL proxy_auth REQUIRED", null)
        insert(HTTP_ACCESS, "http_access allow $USERS_ACL", catchAll())
    }

    // the first parent of cache_peer, through which never_direct sends every request; its login stays where the form leaves it, and its
    // password where its user stays
    fun parent(target: String?, user: String?, password: String?) {
        val index = indices(PEER).firstOrNull { lines[it].trim().split(BLANKS).getOrNull(2) == "parent" }
        val words = index?.let { lines[it].trim().split(BLANKS) }.orEmpty()
        val login = words.firstOrNull { it.startsWith("login=") }?.removePrefix("login=").orEmpty()
        val oldUser = login.substringBefore(':', "")
        val newTarget = target ?: if (index == null) "" else "${words[1]}:${words.getOrElse(3) { "" }}"
        val newUser = user ?: oldUser
        val newPassword = password?.ifEmpty { null } ?: login.substringAfter(':', "").takeIf { newUser == oldUser }.orEmpty()
        val options = (if (index == null) listOf("0", "no-query", "default") else words.drop(4)).filterNot { it.startsWith("login=") }
        val line = newTarget.ifEmpty { null }?.let { address ->
            (listOf(PEER, address.substringBeforeLast(':'), "parent", address.substringAfterLast(':')) + options + listOfNotNull(newUser.ifEmpty { null }?.let { "login=$it:$newPassword" }))
                .joinToString(" ")
        }
        when {
            index != null && line != null -> lines[index] = line
            index != null -> lines.removeAt(index)
            line != null -> lines.add(end(), line)
        }
        val direct = indices("never_direct").filter { lines[it].trim().split(BLANKS).drop(1) == listOf("allow", "all") }
        if (line == null) direct.sortedDescending().forEach { lines.removeAt(it) } else if (direct.isEmpty()) lines.add(end(), "never_direct allow all")
    }

    // cache deny all keeps every object out of the cache
    fun cache(on: Boolean) {
        val denied = indices("cache").filter { lines[it].trim().split(BLANKS).drop(1) == listOf("deny", "all") }
        if (on) denied.sortedDescending().forEach { lines.removeAt(it) } else if (denied.isEmpty()) lines.add(end(), "cache deny all")
    }

    fun authOff() {
        lines.removeAll { line ->
            val words = line.trim().split(BLANKS)
            words.take(2) == listOf("auth_param", "basic") || words.take(2) == listOf(ACL, USERS_ACL) || words.first() == HTTP_ACCESS && words.any { it.removePrefix("!") == USERS_ACL }
        }
    }
}

// the numbers of the rows, each a line of the directive the file has
private fun numbers(text: SquidText, key: String, ids: List<String>): List<Int> {
    val count = text.indices(key).size
    return ids.map { id -> id.toIntOrNull()?.takeIf { it in 1..count } ?: throw DeviceException(ErrorCategory.MissingItem, "Squid has no rule $id") }
}

private fun accessLine(action: String, acls: String): String {
    if (action !in listOf("allow", "deny")) throw DeviceException(ErrorCategory.ArgumentValue, "action: $action is not a value it takes")
    val names = acls.trim().split(BLANKS).filter { it.isNotEmpty() }
    if (names.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "acls: a value is needed")
    names.firstOrNull { !ACL_REFERENCE.matches(it) }?.let { throw DeviceException(ErrorCategory.ArgumentValue, "acls: $it is not a value it takes") }
    return "$HTTP_ACCESS $action ${names.joinToString(" ")}"
}

private fun aclLine(name: String, type: String, values: String, comment: String): String {
    if (!ACL_NAME.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
    if (!ACL_TYPE.matches(type)) throw DeviceException(ErrorCategory.ArgumentValue, "type: $type is not a value it takes")
    if (values.isBlank()) throw DeviceException(ErrorCategory.ArgumentValue, "values: a value is needed")
    if (values.any { it == '\n' || it == '\r' || it == '#' }) throw DeviceException(ErrorCategory.ArgumentValue, "values: $values is not a value it takes")
    return listOf(ACL, name, type, values.trim().split(BLANKS).joinToString(" ")).joinToString(" ") + comment
}

private fun squidCheck(field: String, value: String) {
    val items = value.split(SQUID_LIST).filter { it.isNotEmpty() }
    if (field == "http-port" && items.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "http-port: a value is needed")
    val valid = value.none { it == '\n' || it == '\r' || it == '#' } && when (field) {
        "http-port" -> items.all { item -> SQUID_PORT.matchEntire(item)?.groupValues?.get(2)?.toIntOrNull() in 1..65535 }
        "cache-dir" -> value.split(';').map { it.trim() }.filter { it.isNotEmpty() }.all(CACHE_DIR::matches)
        "cache-mem", "maximum-object-size", "maximum-object-size-in-memory" -> value.isEmpty() || SIZE.matches(value)
        "connect-timeout", "read-timeout", "request-timeout", "shutdown-lifetime" -> value.isEmpty() || TIME.matches(value)
        "logfile-rotate" -> value.isEmpty() || value.toIntOrNull()?.let { it >= 0 } == true
        "dns-nameservers" -> items.all(ADDRESS::matches)
        "visible-hostname" -> value.isEmpty() || HOSTNAME.matches(value)
        "forwarded-for" -> value in listOf("", "on", "off", "transparent", "delete", "truncate")
        "via" -> value in listOf("", "on", "off")
        else -> true
    }
    if (!valid) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes")
}

// the login of a parent goes as login=user:password, a word of squid.conf
private fun parentCheck(field: String, value: String) {
    when (field) {
        "parent-password" -> if (value.any { it.isWhitespace() || it == '"' || it == '#' }) {
            throw DeviceException(ErrorCategory.ArgumentValue, "the password of the parent proxy may hold no space, quote or #")
        }
        "parent" -> if (value.isNotEmpty() && PARENT_ADDRESS.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull() !in 1..65535) {
            throw DeviceException(ErrorCategory.ArgumentValue, "parent: $value is not a value it takes")
        }
        else -> if (value.any { it.isWhitespace() || it == '"' || it == '#' || it == ':' }) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes")
    }
}

private const val HTTP_ACCESS = "http_access"
private const val ACL = "acl"
private const val USERS_ACL = "networkhelm_users"
private const val PEER = "cache_peer"
private const val PASSWD = "/etc/squid/passwords"

private val BLANKS = Regex("\\s+")
private val SQUID_LIST = Regex("[,;\\s]+")
private val COMMENT = Regex("\\s+#.*$")
private val USER = Regex("[A-Za-z0-9._@-]+")
private val ACL_NAME = Regex("[A-Za-z0-9_.-]+")
private val ACL_TYPE = Regex("[a-z_]+(::[a-z_]+)?")
private val ACL_REFERENCE = Regex("!?[A-Za-z0-9_.-]+")
private val SQUID_PORT = Regex("((?:\\d{1,3}(?:\\.\\d{1,3}){3}|\\[[0-9A-Fa-f:]+\\]):)?(\\d{1,5})")
private val CACHE_DIR = Regex("(ufs|aufs|diskd|rock)\\s+/\\S+(\\s+\\S+)*")
private val SIZE = Regex("\\d+\\s+(bytes|KB|MB|GB)", RegexOption.IGNORE_CASE)
private val TIME = Regex("\\d+(\\.\\d+)?\\s+(seconds?|minutes?|hours?|days?|weeks?)", RegexOption.IGNORE_CASE)
private val PARENT_ADDRESS = Regex("[A-Za-z0-9.-]+:(\\d{1,5})")
private val ADDRESS = Regex("[0-9A-Fa-f.:]+")
private val HOSTNAME = Regex("[A-Za-z0-9.-]+")

private val SQUID_DIRECTIVES = mapOf(
    "http-port" to "http_port", "cache-dir" to "cache_dir", "cache-mem" to "cache_mem", "maximum-object-size" to "maximum_object_size",
    "maximum-object-size-in-memory" to "maximum_object_size_in_memory", "dns-nameservers" to "dns_nameservers", "visible-hostname" to "visible_hostname",
    "forwarded-for" to "forwarded_for", "via" to "via", "access-log" to "access_log", "logfile-rotate" to "logfile_rotate", "connect-timeout" to "connect_timeout",
    "read-timeout" to "read_timeout", "request-timeout" to "request_timeout", "shutdown-lifetime" to "shutdown_lifetime",
)
private val SQUID_SETTINGS = SQUID_DIRECTIVES.keys

// the directives of one value, whose last line Squid takes
private val SINGLE = SQUID_SETTINGS - setOf("http-port", "cache-dir", "dns-nameservers", "access-log")
private val PARENT = setOf("parent", "parent-user", "parent-password")
private val SQUID_FIELDS = listOf("state", "autostart", "version", "http-port", "auth", "users", "cache") + (SQUID_SETTINGS - "http-port") + PARENT + "config"
private val SQUID_ARGUMENTS = listOf(
    Argument("http-port", ValueType.Text(), placeholder = "3128"),
    Argument("auth", ValueType.YesNo, optional = true),
    Argument("cache-dir", ValueType.Text(), optional = true, placeholder = "ufs /var/spool/squid 1000 16 256"),
    Argument("cache-mem", ValueType.Text(), optional = true, placeholder = "256 MB"),
    Argument("maximum-object-size", ValueType.Text(), optional = true, placeholder = "4 MB"),
    Argument("maximum-object-size-in-memory", ValueType.Text(), optional = true, placeholder = "512 KB"),
    Argument("cache", ValueType.YesNo, optional = true),
    Argument("dns-nameservers", ValueType.Text(), optional = true),
    Argument("visible-hostname", ValueType.Text(), optional = true),
    Argument("forwarded-for", ValueType.Enumeration(closed = true), listOf("on", "off", "transparent", "delete", "truncate"), optional = true),
    Argument("via", ValueType.Enumeration(closed = true), listOf("on", "off"), optional = true),
    Argument("access-log", ValueType.Text(), optional = true, placeholder = "daemon:/var/log/squid/access.log squid"),
    Argument("logfile-rotate", ValueType.Integer(0, 1000), optional = true),
    Argument("connect-timeout", ValueType.Text(), optional = true, placeholder = "1 minute"),
    Argument("read-timeout", ValueType.Text(), optional = true, placeholder = "15 minutes"),
    Argument("request-timeout", ValueType.Text(), optional = true, placeholder = "5 minutes"),
    Argument("shutdown-lifetime", ValueType.Text(), optional = true, placeholder = "30 seconds"),
    Argument("parent", ValueType.Text(), optional = true, placeholder = "proxy.example.net:3128"),
    Argument("parent-user", ValueType.Text(), optional = true),
    Argument("parent-password", ValueType.Secret, optional = true),
    Argument("autostart", ValueType.YesNo, optional = true),
)
private val ACCESS_ARGUMENTS = listOf(
    Argument("action", ValueType.Enumeration(closed = true), listOf("allow", "deny"), default = "allow"),
    Argument("acls", ValueType.Text(), placeholder = "localnet"),
)
private val ACL_ARGUMENTS = listOf(
    Argument("name", ValueType.Text()),
    Argument(
        "type", ValueType.Enumeration(),
        listOf("src", "dst", "dstdomain", "dstdom_regex", "srcdomain", "port", "localport", "proto", "method", "time", "url_regex", "urlpath_regex", "browser", "proxy_auth", "maxconn", "arp"),
        default = "src",
    ),
    Argument("values", ValueType.Text(), placeholder = "192.168.0.0/16"),
)
private val USER_ARGUMENTS = listOf(Argument("name", ValueType.Text(), fixed = true), Argument("password", ValueType.Secret, optional = true))

// a file of users made empty when missing, readable by the group the logs of Squid belong to, which is the group Squid runs as on each
// distribution
private fun passwdMade(file: String) =
    $$"""[ -e $${quote(file)} ] || { (umask 077; : > $${quote(file)}) || exit 1; g=$(stat -c %G /var/log/squid 2>/dev/null) && [ -n "$g" ] && chgrp "$g" $${quote(file)}; chmod 640 $${quote(file)} || exit 1; }"""

// the lines of squid.conf without comments and with the passwords of cache_peer masked; the users of basic_ncsa_auth without their hashes
private val SQUID_STATE = $$"""c=$$SQUID_CONF
[ -f "$c" ] || exit 0
$$SERVICE_STATE
echo "--nh-squid-- $(s squid)"
echo "--nh-squid-version-- $(squid -v 2>/dev/null | sed -n '1s/.*Version //p')"
for p in /usr/lib/squid/basic_ncsa_auth /usr/lib64/squid/basic_ncsa_auth /usr/libexec/squid/basic_ncsa_auth /usr/sbin/basic_ncsa_auth; do [ -x "$p" ] && { echo "--nh-squid-helper-- $p"; break; }; done
awk '$1 == "cache_peer" && $3 == "parent" { for (i = 6; i <= NF; i++) if ($i ~ /^login=/) { u = substr($i, 7); sub(/:.*/, "", u); print "--nh-squid-parent-user-- " u } exit }' "$c"
echo --nh-squid-conf--
grep -vE '^[[:space:]]*(#|$)' "$c" | $$PROXY_MASK
f=$(awk '$1 == "auth_param" && $2 == "basic" && $3 == "program" && $4 ~ /basic_ncsa_auth$/ { print $5; exit }' "$c")
[ -z "$f" ] || { echo "--nh-squid-users-- $f"; [ ! -f "$f" ] || awk -F: '$1 != "" && $1 !~ /^#/ && NF > 1 { print $1 }' "$f"; }
true"""

private val TRAFFIC_FIELDS = listOf("name", "requests", "day-bytes", "week-bytes", "month-bytes")

// the access log of the native format of Squid (time, elapsed, client, code, bytes, method, URL), rotated files and their gzip as well,
// summed up on the host over the last 30 days: the site of a CONNECT is its host without the port, of any other request the host of
// its URL. A request Squid refused, as the 407 that asks a client for its login, carried nothing through. The 500 clients and sites of most
// bytes of each come back
private val SQUID_TRAFFIC = $$"""f=$(awk '$1 == "access_log" && $2 != "none" { sub(/^[a-z]+:/, "", $2); print $2; exit }' $$SQUID_CONF)
[ -n "$f" ] || f=/var/log/squid/access.log
for g in "$f" "$f".*; do [ -f "$g" ] || continue; case "$g" in *.gz) gzip -dc "$g";; *) cat "$g";; esac; done 2>/dev/null | awk -v now="$(date +%s)" '
{ t = $1 + 0; if (t < now - 2592000 || NF < 7 || $4 ~ /DENIED/) next; b = $5 + 0; d = $7
  if ($6 == "CONNECT") sub(/:[0-9]+$/, "", d); else { sub(/^[a-zA-Z]+:\/\//, "", d); sub(/[\/:?].*$/, "", d) }
  r["client " $3]++; m["client " $3] += b; r["domain " d]++; m["domain " d] += b
  if (t >= now - 604800) { w["client " $3] += b; w["domain " d] += b }
  if (t >= now - 86400) { y["client " $3] += b; y["domain " d] += b } }
END { for (k in r) print k, r[k], y[k] + 0, w[k] + 0, m[k] }' | sort -k6,6nr | awk '++n[$1] <= 500'
true"""

// squid -k parse prints each line it reads; only what failed comes back, masked, with the status of Squid
private val SQUID_CHECK = $$"""o=$(squid -k parse -f $$SQUID_CONF 2>&1); r=$?; printf '%s\n' "$o" | grep -E 'FATAL|ERROR|Bungled' | $$PROXY_MASK; exit $r"""
