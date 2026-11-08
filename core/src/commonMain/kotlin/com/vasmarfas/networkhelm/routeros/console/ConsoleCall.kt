package com.vasmarfas.networkhelm.routeros.console

import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.api.ApiTrapException

// RouterOS 7.17 and later serializes what print gives; 6 and the older 7.x have only the console a person reads
enum class Dialect { Serialize, Classic }

internal enum class Reply { Rows, Records, Count, Id, Text, Nothing, Pairs }

internal class ConsoleCall(
    val line: String,
    val reply: Reply,
    val fallback: ConsoleCall? = null,
    val changes: Boolean = false,
    val menu: String? = null,
    val offered: Set<String> = emptySet(),
    val probe: Boolean = false,
    val proplist: List<String> = emptyList(),
    val quick: Boolean = false,
    val heavy: Boolean = false,
)

internal const val AS_VALUE = "bad parameter as-value"
private const val DETAIL = "detail"
private const val SENSITIVE = "show-sensitive"
private const val STATS_DETAIL = "stats-detail"
private const val STATS = "stats"
private const val PROPLIST = "proplist"
internal const val FIND = "find"
internal const val FROM = "from"
private val NAME = Regex("\\.?[A-Za-z0-9][A-Za-z0-9._-]*")
private val ID = Regex("\\*[0-9A-Fa-f]+")
private val NUMBER = Regex("-?[0-9]+(\\.[0-9]+)?")
private val BARE = Regex("[A-Za-z0-9._:/*+@%,][A-Za-z0-9._:/*+@%,-]*|-[0-9]+(\\.[0-9]+)?")
private val LITERALS = setOf("true", "false", "nil", "nothing")
private val FLAGS = setOf(
    "count-only", "once", "as-value", "without-paging", "detail", "terse", "brief", "show-ids", "compact", "verbose",
    "hide-sensitive", "show-sensitive",
)
private val CHANGES = setOf("set", "remove", "enable", "disable", "move", "comment")
private val READS = setOf("print", "export", "get", "find", "inspect", "ping", "traceroute", "monitor", "monitor-traffic", "torch", "scan", "profile")
private const val ROWS = "options=json.no-string-conversion"
private const val HEX = "0123456789ABCDEF"

internal fun consoleCall(words: List<String>, yesNo: Set<String>, refused: Map<String, Set<String>> = emptyMap(), encoding: ApiEncoding = ApiEncoding.Utf8, portion: IntRange? = null): ConsoleCall {
    val call = Words(words, encoding)
    val path = call.path
    val menu = "/" + path.joinToString("/")
    val verb = path.last()
    if (call.command == "/execute") return ConsoleCall(call.arguments.first { it.first == "script" }.second, Reply.Text, changes = true)
    val numbers = call.numbers
    val proplist = call.proplist
    val named = call.named
    fun given(skip: Set<String> = emptySet()) = named.filter { it.first !in skip }.joinToString("") { (name, value) -> " " + argument(name, value, name in yesNo, encoding) }
    val select = where(call.query, encoding = encoding)
    val list = if (proplist.isEmpty()) "" else " proplist=" + proplist.joinToString(",") { name(it) }
    val changes = verb !in READS
    return when {
        verb == "print" && named.any { it.first == "count-only" } -> ConsoleCall("$menu count-only$select", Reply.Count)
        verb == "print" -> {
            val key = "/" + path.dropLast(1).joinToString("/")
            val refusals = refused[key].orEmpty()
            val secrets = if (SENSITIVE in refusals) emptyList() else listOf(SENSITIVE)
            val views = buildList {
                add(if (DETAIL in refusals) secrets else emptyList())
                if (DETAIL !in refusals) add(listOf(DETAIL) + secrets)
                listOf(STATS_DETAIL, STATS).firstOrNull { it !in refusals }?.let { add(listOf(it)) }
            }
            val asked = if (PROPLIST in refusals) "" else list
            val part = portion.takeIf { FIND !in refusals && FROM !in refusals }
            val rows = if (part == null) select else " from=\$part"
            val reads = views.joinToString("; ") { flags -> serialized("$menu as-value${flags.joinToString("") { " $it" }}${given()}$asked$rows") }
            val line = if (part == null) reads else ":local part [:pick [$key/find$select] ${part.first} ${part.last + 1}]; :if ([:len \$part] > 0) do={ $reads } else={ :put \"[]\" }"
            ConsoleCall(line, Reply.Rows, menu = key, offered = views.flatten().toSet() + listOfNotNull(PROPLIST.takeIf { asked.isNotEmpty() }) + if (part == null) emptySet() else setOf(FIND, FROM), proplist = proplist, heavy = part != null)
        }
        verb == "add" -> ConsoleCall(":put [$menu${given()}]", Reply.Id, changes = true)
        verb in CHANGES -> ConsoleCall("$menu$numbers${given()}", Reply.Nothing, changes = true)
        verb == "unset" -> {
            val cleared = call.arguments.first { it.first == "value-name" }.second
            ConsoleCall("/" + path.dropLast(1).joinToString("/") + "/set$numbers ${name(cleared)}=\"\"", Reply.Nothing, changes = true)
        }
        verb == "export" -> ConsoleCall("$menu${given()}", Reply.Text)
        // :serialize cuts the time of a reply to whole seconds, :put keeps the microseconds
        menu == "/tool/ping" -> ConsoleCall(":put [$menu${given()} as-value]", Reply.Pairs)
        menu == "/password" -> ConsoleCall("$menu${given()}", Reply.Nothing, changes = true)
        else -> ConsoleCall(
            serialized("$menu${given()}$numbers$list as-value$select"), Reply.Rows, ConsoleCall("$menu$numbers${given()}", Reply.Text, changes = changes), changes,
        )
    }
}

internal fun classicCall(words: List<String>, yesNo: Set<String>, refused: Map<String, Set<String>>, encoding: ApiEncoding = ApiEncoding.Utf8, portion: IntRange? = null): ConsoleCall {
    val call = Words(words, encoding)
    if (call.command == "/execute") return ConsoleCall(call.arguments.first { it.first == "script" }.second, Reply.Text, changes = true)
    val menu = "/" + call.path.dropLast(1).joinToString(" ")
    val verb = call.path.last()
    val named = call.named
    fun given() = named.joinToString("") { (name, value) -> " " + argument(name, value, name in yesNo, encoding) }
    val select = where(call.query, classic = true, encoding = encoding)
    return when {
        call.command == "/password" -> ConsoleCall("/password${given()}", Reply.Nothing, changes = true)
        verb == "print" && named.any { it.first == "count-only" } -> ConsoleCall("$menu print count-only$select", Reply.Count, probe = true, quick = true)
        verb == "print" -> {
            val found = "[$menu find$select]"
            val rows = if (portion == null) found else "[:pick $found ${portion.first} ${portion.last + 1}]"
            if (FIND in refused[menu].orEmpty()) ConsoleCall("\$rdrow [$menu get]", Reply.Records, proplist = call.proplist, quick = true)
            else ConsoleCall(":foreach i in=$rows do={ \$rdrow [$menu get \$i] }", Reply.Records, menu = menu, offered = setOf(FIND), proplist = call.proplist, quick = true, heavy = portion != null)
        }
        verb == "add" -> ConsoleCall(":put [$menu add${given()}]", Reply.Id, changes = true)
        verb in CHANGES -> ConsoleCall(":put [$menu $verb${call.numbers}${given()}]", Reply.Nothing, changes = true)
        verb == "unset" -> {
            val cleared = call.arguments.first { it.first == "value-name" }.second
            ConsoleCall(":put [$menu set${call.numbers} ${name(cleared)}=\"\"]", Reply.Nothing, changes = true)
        }
        verb == "monitor-traffic" -> {
            val names = call.arguments.firstOrNull { it.first == "interface" }?.second?.split(',')?.filter { it.isNotEmpty() }
                ?: throw ApiTrapException(1, "missing value(s) of argument(s) interface")
            ConsoleCall(":foreach n in={${names.joinToString(";") { quoted(it, encoding) }}} do={ \$rdrow [$menu monitor-traffic \$n once as-value] }", Reply.Records)
        }
        else -> throw ApiTrapException(0, "$verb is not available over the console of RouterOS 6")
    }
}

private class Words(words: List<String>, encoding: ApiEncoding) {
    val command = words.first()
    val path = command.trim('/').split('/').also {
        if (!command.startsWith("/") || it.any { part -> !NAME.matches(part) }) throw ApiTrapException(0, "no such command $command")
    }
    val arguments = words.drop(1).filter { it.startsWith("=") }.map { it.substring(1).substringBefore('=') to it.substring(1).substringAfter('=', "") }
    val query = words.drop(1).filter { it.startsWith("?") }.map { it.substring(1) }
    val numbers = arguments.firstOrNull { it.first == ".id" }?.let { " numbers=" + token(it.second, false, encoding) }.orEmpty()
    val proplist = arguments.firstOrNull { it.first == ".proplist" }?.second?.split(',')?.filter { it.isNotEmpty() && it != ".id" }.orEmpty()
    val named = arguments.filter { it.first !in setOf(".id", ".proplist", ".tag") }
}

private fun serialized(command: String) = ":put [:serialize to=json $ROWS [$command]]"

private fun name(text: String): String {
    if (!NAME.matches(text)) throw ApiTrapException(1, "bad name $text")
    return text
}

private fun argument(name: String, value: String, asYesNo: Boolean, encoding: ApiEncoding): String = when {
    value.isEmpty() && name in FLAGS -> name(name)
    else -> name(name) + "=" + token(value, asYesNo, encoding)
}

private fun token(value: String, asYesNo: Boolean, encoding: ApiEncoding): String {
    val text = when {
        !asYesNo -> value
        value == "true" -> "yes"
        value == "false" -> "no"
        else -> value
    }
    return if (BARE.matches(text) && text !in LITERALS) text else quoted(text, encoding)
}

internal fun quoted(text: String, encoding: ApiEncoding): String = quoted(encoding.encode(text))

internal fun quoted(bytes: ByteArray): String = buildString {
    append('"')
    for (byte in bytes) {
        val code = byte.toInt() and 0xFF
        if (plain(byte)) append(code.toChar()) else append('\\').append(HEX[code shr 4]).append(HEX[code and 15])
    }
    append('"')
}

internal fun plain(byte: Byte): Boolean {
    val code = byte.toInt() and 0xFF
    return code < 0x80 && (code.toChar().isLetterOrDigit() || code.toChar() in " ._:/*+@%,=-")
}

// a flag in a query goes as yes or no: floating-undo of the history matches nothing as the string true
private fun flag(value: String) = when (value) {
    "true" -> "yes"
    "false" -> "no"
    else -> value
}

private fun compare(value: String, encoding: ApiEncoding) = when {
    value == "true" || value == "yes" -> "yes"
    value == "false" || value == "no" -> "no"
    ID.matches(value) || NUMBER.matches(value) -> value
    else -> quoted(value, encoding)
}

private fun where(query: List<String>, classic: Boolean = false, encoding: ApiEncoding): String {
    if (query.isEmpty()) return ""
    val stack = ArrayDeque<String>()
    for (word in query) {
        when {
            word.startsWith("#") -> for (operation in word.drop(1)) {
                when (operation) {
                    '!' -> stack.addLast("!(" + stack.removeLast() + ")")
                    '&', '|' -> {
                        val right = stack.removeLast()
                        stack.addLast("(" + stack.removeLast() + (if (operation == '&') " && " else " || ") + right + ")")
                    }
                    else -> throw ApiTrapException(1, "bad query operation $operation")
                }
            }
            word.startsWith("-") -> stack.addLast("!" + name(word.drop(1)))
            word.startsWith("<") || word.startsWith(">") -> {
                val value = word.substringAfter('=')
                stack.addLast(name(word.drop(1).substringBefore('=')) + word[0] + if (classic) compare(value, encoding) else quoted(value, encoding))
            }
            '=' in word -> {
                val value = word.substringAfter('=')
                stack.addLast(name(word.substringBefore('=')) + "=" + if (classic) compare(value, encoding) else quoted(flag(value), encoding))
            }
            else -> stack.addLast(name(word))
        }
    }
    return " where " + stack.joinToString(" && ")
}
