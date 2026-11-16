package com.vasmarfas.networkhelm.offline

import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.sensitive

// the head of a running-config and of the startup-config saved from the web interface: ! $$$ Model: Keenetic Giga
private const val HEAD = "! \$\$\$ "

// the contexts of the CLI whose blocks are elements of a list, named by the words after the context
private val NAMED = listOf(
    "interface", "user", "access-list", "ipv6 access-list", "ip dhcp pool", "ip policy", "schedule", "dyndns profile", "ipv6 subnet", "mws wlan",
    "crypto map", "crypto ike key", "crypto ike policy", "crypto ike proposal", "crypto ipsec transform-set", "crypto ipsec profile", "ping-check profile",
    "object-group fqdn",
).map { it.split(' ') }

// a word of a command rather than of its value: ip, dhcp and client of ip dhcp client hostname rd-test
private val KEYWORD = Regex("[a-z][a-z0-9._-]*")

// the running-config of Keenetic as menus. A block of a context that names its element, as interface Bridge0 or ip dhcp pool
// _WEBADMIN, is a row of that context; another block, as ip ssh, holds the settings of its context; a line of the top level is a
// row of its first word, a word alone a setting of its own. A line of a block is a value named by its keywords, as ip address of
// ip address 192.168.1.1 255.255.255.0: the last word is always the value, a secret such as password ends the name, and lines of
// the same name make one value
fun keeneticConfig(text: String): Config {
    val facts = linkedMapOf<String, String>()
    val menus = linkedMapOf<MenuPath, KeeneticMenu>()
    fun menu(words: List<String>) = menus.getOrPut(MenuPath(words)) { KeeneticMenu() }
    val lines = text.lines()
    var at = 0
    while (at < lines.size) {
        val line = lines[at++]
        if (line.startsWith(HEAD)) {
            val (name, value) = line.removePrefix(HEAD).split(':', limit = 2).takeIf { it.size == 2 } ?: continue
            facts.getOrPut(name.trim().lowercase()) { value.trim() }
            continue
        }
        if (line.isBlank() || line.first().isWhitespace() || line.startsWith("!")) continue
        val words = words(line)
        val body = mutableListOf<String>()
        while (at < lines.size && lines[at].firstOrNull()?.isWhitespace() == true) body += lines[at++].trim()
        val context = NAMED.filter { words.size > it.size && words.take(it.size) == it }.maxByOrNull { it.size }
        // a block of nothing, as dyndns profile _WEBADMIN, ends at once; a line before the ! that ends the config is no block
        val block = body.isNotEmpty() || context != null && lines.getOrNull(at)?.trim() == "!"
        when {
            block && context != null -> menu(context).rows += mapOf("name" to words.drop(context.size).joinToString(" ")) + values(body)
            block -> menu(words).settings.putAll(values(body))
            words.size == 1 -> menu(words).settings[words.single()] = "true"
            else -> menu(words.take(1)).rows += mapOf("name" to words.drop(1).joinToString(" "))
        }
    }
    return Config(menus.mapValues { it.value.build() }, facts)
}

// a file without a router is named by the host name its system context keeps, and the model by its head
fun keeneticDevice(config: Config, file: String) = DeviceInfo(
    Platform.Keenetic,
    config.menus[MenuPath.parse("system")]?.items?.firstOrNull()?.get("hostname") ?: file.substringBeforeLast('.'),
    "",
    "",
    config.facts["model"].orEmpty(),
    emptySet(),
    setOf("read"),
)

private fun values(lines: List<String>): Map<String, String> {
    val values = linkedMapOf<String, String>()
    for (line in lines) {
        if (line.isEmpty() || line.startsWith("!")) continue
        val words = words(line)
        var size = 1
        while (size < words.size - 1 && !sensitive(words.take(size).joinToString(" ")) && KEYWORD.matches(words[size])) size++
        val key = words.take(size).joinToString(" ")
        val value = words.drop(size).joinToString(" ").ifEmpty { "true" }
        values[key] = values[key]?.let { "$it, $value" } ?: value
    }
    return values
}

// words apart by spaces, a quoted one with its spaces and without its quotes, as description "Home network"
private fun words(line: String): List<String> {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    var quoted = false
    var at = 0
    while (at < line.length) {
        val char = line[at++]
        when {
            quoted && char == '\\' && at < line.length -> word.append(line[at++])
            char == '"' -> quoted = !quoted
            char.isWhitespace() && !quoted -> if (word.isNotEmpty()) {
                words += word.toString()
                word.clear()
            }
            else -> word.append(char)
        }
    }
    if (word.isNotEmpty()) words += word.toString()
    return words
}

private class KeeneticMenu {
    val settings = linkedMapOf<String, String>()
    val rows = mutableListOf<Map<String, String>>()

    // a word that stands alone and also starts lines keeps its setting as a row of no name
    fun build(): ConfigMenu = if (rows.isEmpty()) {
        ConfigMenu(NodeKind.Settings, listOf(Item("", settings)))
    } else {
        ConfigMenu(NodeKind.List, (rows + listOfNotNull(settings.takeIf { it.isNotEmpty() })).mapIndexed { index, values -> Item(values["name"] ?: "*${index + 1}", values) })
    }
}
