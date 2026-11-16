package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.routeros.schema.label
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal const val CONFIG = "rc"

// a context as a path of a window: the name of an interface such as FastEthernet0/Vlan2 has a slash of its own
internal fun configPath(context: List<String>) = "$CONFIG/" + context.joinToString("/") { it.replace('/', '~') }

internal fun configContext(path: MenuPath) = path.segments.drop(1).map { it.replace('~', '/') }

// how the CLI of the router describes a command to help: the commands under it, the choices of its value, a hint of its
// arguments such as "port {port}", and whether it runs as it is
internal class Help(val commands: Map<String, String>, val choices: List<String>, val hint: String, val runs: Boolean)

internal suspend fun KeeneticCall.help(lines: List<String>): List<Help> = post(lines.map { JsonObject(mapOf("help" to JsonPrimitive("$it "))) }, raising = false).map { answer ->
    val help = (answer as? JsonObject)?.get("help") as? JsonObject
    Help(
        (help?.get("command") as? JsonObject).orEmpty().mapValues { (_, about) -> ((about as? JsonObject)?.get("description") as? JsonPrimitive)?.contentOrNull.orEmpty() },
        (help?.get("choice") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() },
        (help?.get("hint") as? JsonPrimitive)?.contentOrNull.orEmpty(),
        help?.containsKey("cr") == true,
    )
}

internal enum class Kind { Flag, Value, Choice, Values }

// a command of a context that keeps a value: its words under the context, the arguments it takes and what help says of it
// fixed: a choice with options of its own, such as public [ssl], which a field of one choice would lose
internal class Field(val words: List<String>, val kind: Kind, val arguments: List<String>, val choices: List<String>, val about: String, val fixed: Boolean = false) {
    val name = words.joinToString(".")

    fun arguments(): List<Argument> = when (kind) {
        Kind.Flag -> listOf(Argument(name, ValueType.YesNo, description = about, label = label(name)))
        Kind.Value -> listOf(Argument(name, if (choices.isEmpty()) ValueType.Text() else ValueType.Enumeration(), choices, about, optional = true, label = label(name), editable = choices.isNotEmpty()))
        Kind.Choice -> listOf(Argument(name, ValueType.Enumeration(closed = true), choices, about, readOnly = fixed, optional = true, label = label(name)))
        Kind.Values -> arguments.map { Argument("$name.$it", ValueType.Text(), description = about, optional = true, label = label("$name $it")) }
    }

    fun values(config: JsonElement?): List<Pair<String, String>> {
        val at = words.fold(config) { inner, word -> (inner as? JsonObject)?.get(word) }
        return when (kind) {
            Kind.Flag -> listOf(name to "${(at as? JsonPrimitive)?.booleanOrNull == true}")
            Kind.Value -> listOf(name to ((at as? JsonPrimitive)?.contentOrNull ?: ((at as? JsonObject)?.values?.firstOrNull() as? JsonPrimitive)?.contentOrNull).orEmpty())
            Kind.Choice -> listOf(name to ((at as? JsonPrimitive)?.contentOrNull ?: (at as? JsonObject)?.entries?.firstOrNull { (it.value as? JsonPrimitive)?.booleanOrNull == true }?.key).orEmpty())
            Kind.Values -> arguments.map { "$name.$it" to (((at as? JsonObject)?.get(it) as? JsonPrimitive)?.contentOrNull).orEmpty() }
        }
    }

    // the command as show rc keeps it: a value, the choice made, the flag, or all the arguments at once
    fun change(changes: Map<String, String?>, old: Map<String, String>): JsonElement? = when (kind) {
        Kind.Flag -> changes[name]?.let { if (it in ValueType.Flag.on) JsonPrimitive(true) else NO }
        Kind.Value -> changes[name]?.let { if (it.isEmpty()) NO else JsonPrimitive(it) }
        Kind.Choice -> changes[name]?.takeUnless { fixed }?.let { if (it.isEmpty()) NO else JsonObject(mapOf(it to JsonPrimitive(true))) }
        Kind.Values -> {
            val names = arguments.map { "$name.$it" }
            if (names.none { it in changes }) {
                null
            } else {
                val merged = arguments.zip(names).map { (argument, field) -> argument to (if (field in changes) changes[field] else old[field]).orEmpty() }.filter { it.second.isNotEmpty() }
                if (merged.isEmpty()) NO else JsonObject(merged.associate { (argument, value) -> argument to JsonPrimitive(value) })
            }
        }
    }
}

// a command that does something rather than keep a value: the configuration has none of these, and a field of one would
// reboot the router or make new keys the moment it was set
private val actions = Regex("^(add|reboot|regenerate|reset|erase|restart|run|check|update|upgrade|clear|flush|release|renew|test|send|drop|book|get|copy|save|load|start a|stop|scan|delete|remove|hard reboot)\\b")

// the fields of a command by its help: no hint and nothing to add is a flag, "(a | b)" a choice, "{x}" a value, more
// arguments of one command go together; a hint with words between its arguments, such as "led {led} indicate {control}",
// is left out
internal fun fieldOf(words: List<String>, help: Help, about: String): Field? {
    if (actions.containsMatchIn(about)) return null
    val command = words.last()
    if (help.hint.isEmpty()) return if (help.runs && help.commands.isEmpty()) Field(words, Kind.Flag, emptyList(), emptyList(), about) else null
    val first = topLevel(help.hint).trim()
    if (!first.startsWith("$command ")) return null
    val rest = first.removePrefix("$command ").trim()
    if (rest.startsWith("(")) return Field(words, Kind.Choice, emptyList(), help.choices, about, fixed = '[' in rest).takeIf { help.choices.isNotEmpty() }
    if (rest.replace(ARGUMENT, "").replace(Regex("[\\[\\]\\s]"), "").isNotEmpty()) return null
    val arguments = ARGUMENT.findAll(rest).map { it.groupValues[1] }.toList()
    return when (arguments.size) {
        0 -> null
        1 -> Field(words, Kind.Value, arguments, help.choices, about)
        else -> Field(words, Kind.Values, arguments, emptyList(), about)
    }
}

internal val ARGUMENT = Regex("\\{([^}]+)\\}")

// the hint up to its first " | " outside the parentheses
private fun topLevel(hint: String): String {
    var depth = 0
    for (index in hint.indices) {
        when (hint[index]) {
            '(', '[' -> depth++
            ')', ']' -> depth--
            '|' -> if (depth == 0) return hint.substring(0, index)
        }
    }
    return hint
}

// a context of the CLI as a window of settings: its commands with values, and those of the contexts under it that have
// no window of their own, such as session of ip ssh
internal class Settings(private val api: KeeneticCall, private val context: List<String>) : Node(configPath(context), NodeKind.Settings) {
    override val commands = listOf("print", "set")

    private var known: List<Field>? = null

    override suspend fun arguments(): List<Argument> = fields().flatMap { it.arguments() }

    // a context nothing of which is set yet, such as ndns of a router in the cloud, is not found in show rc: its fields are empty
    override suspend fun read(): List<Item> {
        val words = listOf(CONFIG) + context
        val answer = api.post(listOf(JsonObject(mapOf("show" to nested(words, JsonObject(emptyMap()))))), raising = false).firstOrNull()
        val config = answer?.takeIf { failure(it) == null }?.let { words.fold((it as? JsonObject)?.get("show")) { at, word -> (at as? JsonObject)?.get(word) } }
        return listOf(Item("", fields().flatMap { it.values(config) }.toMap(LinkedHashMap())))
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val old = read().first().values
        api.send(fields().mapNotNull { field -> field.change(changes, old)?.let { leaf -> nested(context + field.words.dropLast(1), JsonObject(mapOf(field.words.last() to leaf))) } })
    }

    private suspend fun fields(): List<Field> = known ?: helped().also { known = it }

    private suspend fun helped(): List<Field> = api.fieldsOf(listOf(context)).getValue(context)
}

// the fields of contexts, all of them in three requests of help, a few hundred lines at a time: the commands of each
// context, what help says of each command, and of the commands of the contexts under a context of the second level, such
// as session of ip ssh; a context of the root has windows of its own for the contexts under it
internal suspend fun KeeneticCall.fieldsOf(contexts: List<List<String>>): Map<List<String>, List<Field>> {
    val lines = contexts.map { it.joinToString(" ") }
    val owns = helps(lines).map { it.commands }
    val asked = contexts.indices.flatMap { at -> owns[at].keys.map { at to it } }
    val helps = helps(asked.map { (at, command) -> "${lines[at]} $command" })
    val inner = asked.zip(helps).filter { (key, help) -> contexts[key.first].size >= 2 && help.commands.isNotEmpty() && help.hint.isEmpty() && !help.runs }
    val deeper = inner.flatMap { (key, help) -> help.commands.map { (command, about) -> Triple(key.first, listOf(key.second, command), about) } }
    val deeperHelps = helps(deeper.map { (at, words, _) -> "${lines[at]} ${words.joinToString(" ")}" })
    val own = asked.zip(helps).mapNotNull { (key, help) -> fieldOf(listOf(key.second), help, owns[key.first].getValue(key.second))?.let { key.first to it } }
    val nested = deeper.zip(deeperHelps).mapNotNull { (asked, help) -> fieldOf(asked.second, help, asked.third)?.let { asked.first to it } }
    val found = (own + nested).groupBy({ it.first }, { it.second })
    return contexts.indices.associate { contexts[it] to found[it].orEmpty() }
}

private suspend fun KeeneticCall.helps(lines: List<String>): List<Help> = lines.chunked(HELP_BATCH).flatMap { help(it) }

private const val HELP_BATCH = 200

// a context of the CLI that holds settings and how its window is titled: an interface by its name and description
internal class Context(val words: List<String>, val title: String)

// the contexts of the CLI that hold settings, as help names them: a command with commands under it and no instance to
// name, among the commands of the root and those one level down; a context of contexts, such as ip, gives none of its own
internal suspend fun KeeneticCall.contexts(): List<Context> {
    val root = help(listOf("")).single().commands.keys.filter { it !in notContexts }
    val first = help(root).zip(root).filter { (help, _) -> help.commands.isNotEmpty() && help.hint.isEmpty() }
    val second = first.flatMap { (help, name) -> help.commands.keys.map { listOf(name, it) } }
    val secondHelps = if (second.isEmpty()) emptyList() else help(second.map { it.joinToString(" ") })
    val nested = second.zip(secondHelps).filter { (_, help) -> help.commands.isNotEmpty() && help.hint.isEmpty() && !help.runs }.map { it.first }
    val directories = first.map { it.second }.filter { name -> second.filter { it.first() == name }.all { it in nested } }
    // each interface is a context of its own, with all the settings help gives for its kind
    val interfaces = (shows("rc/interface").single() as? JsonObject).orEmpty().map { (name, config) ->
        val description = ((config as? JsonObject)?.get("description") as? JsonPrimitive)?.contentOrNull?.ifEmpty { null }
        Context(listOf("interface", name), listOfNotNull(name, description).joinToString(" · "))
    }
    val own = (first.map { listOf(it.second) }.filter { it.single() !in directories } + nested).map { Context(it, it.joinToString(" ")) }
    return (own + interfaces).sortedBy { it.words.joinToString(" ") }
}

// the groups of All Settings by the first word of a context, as the web interface places them; a word not named here goes
// to Other. The cloud, the first setup and the license have no settings in the web interface and are left out
internal val GROUPS = mapOf(
    "interface" to "Interfaces",
    "ip" to "IP", "igmp-proxy" to "IP", "upnp" to "IP", "pppoe" to "IP", "ping-check" to "IP",
    "ipv6" to "IPv6",
    "dns-proxy" to "DNS", "dyndns" to "DNS", "ndns" to "DNS",
    "mws" to "Mesh Wi-Fi System",
    "system" to "System", "ntp" to "System", "components" to "System", "service" to "System", "discovery" to "System",
    "oc-server" to "VPN", "vpn-server" to "VPN", "sstp-server" to "VPN", "wireguard-server" to "VPN", "crypto" to "VPN",
)

internal val HIDDEN = setOf("cloud", "easyconfig", "eula", "dpn")

// commands of the root that read, run or change the files rather than keep settings
private val notContexts = setOf("show", "tools", "copy", "erase", "ls", "mkdir", "more", "whoami", "sms", "ussd", "known", "interface", "user", "access-list", "object-group", "schedule")

// a tool of the router, such as ping, with the arguments help gives it: the host and the options after it
internal class Tool(private val api: KeeneticCall, private val name: String, private val named: () -> Map<String, String>) : Node("tools/$name", NodeKind.Command) {
    private var known: List<String>? = null

    override suspend fun arguments(): List<Argument> {
        val names = known ?: ARGUMENT.findAll(api.help(listOf("tools $name")).single().hint).map { it.groupValues[1] }.distinct().toList().also { known = it }
        return names.mapIndexed { index, argument -> Argument(argument, ValueType.Text(), optional = index > 0, label = label(argument).let { named()[it] ?: it }) }
    }

    override suspend fun read(): List<Item> = emptyList()
}

