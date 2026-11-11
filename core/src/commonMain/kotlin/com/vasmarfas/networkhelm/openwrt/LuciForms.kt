package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.ValueType
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

interface LuciForms {
    suspend fun views(): Map<String, List<String>>

    suspend fun maps(view: String): List<LuciMap>

    suspend fun protocol(name: String): List<LuciOption>

    suspend fun menu(): List<LuciNode>
}

@Serializable
class LuciNode(
    val path: String,
    val app: String,
    val title: String? = null,
    val order: Int? = null,
    val view: String? = null,
    val configs: List<String> = emptyList(),
    val fs: JsonElement? = null,
    val uci: JsonElement? = null,
)

@Serializable
class LuciMap(val config: String, val sections: List<LuciSection> = emptyList()) {
    val allSections: List<LuciSection> get() = sections.flatMap { it.withInner() }
}

@Serializable
class LuciSection(
    val kind: String,
    val type: String? = null,
    val title: JsonPrimitive? = null,
    val anonymous: Boolean? = null,
    val addremove: Boolean? = null,
    val sortable: Boolean? = null,
    val tabs: List<LuciTab> = emptyList(),
    val options: List<LuciOption> = emptyList(),
    val config: String? = null,
    val named: Boolean? = null,
    val added: Map<String, String> = emptyMap(),
    val section: String? = null,
) {
    val single: Boolean get() = kind == "NamedSection" || addremove != true

    fun withInner(): List<LuciSection> = listOf(this) + options.mapNotNull { it.subsection }.flatMap { it.withInner() }
}

@Serializable
class LuciTab(val name: String, val title: JsonPrimitive? = null) {
    val shown: String? get() = title?.contentOrNull?.let(::plain)
}

@Serializable
data class LuciOption(
    val kind: String,
    val name: String? = null,
    val title: JsonPrimitive? = null,
    val tab: String? = null,
    val datatype: String? = null,
    val default: JsonPrimitive? = null,
    val placeholder: JsonPrimitive? = null,
    val optional: Boolean? = null,
    val readonly: Boolean? = null,
    val enabled: JsonPrimitive? = null,
    val disabled: JsonPrimitive? = null,
    val values: List<List<String>> = emptyList(),
    val password: Boolean? = null,
    val multiple: Boolean? = null,
    val depends: List<JsonObject> = emptyList(),
    val subsection: LuciSection? = null,
) {
    fun argument(tabs: List<LuciTab> = emptyList(), more: List<Map<String, String>> = emptyList()): Argument? {
        val name = name ?: return null
        if (kind in shown || name.startsWith('_')) return null
        val typed = datatype?.takeIf { it != "<function>" }?.let { uciArgument(name, it) }
        val words = listOf(enabled?.contentOrNull ?: "1", disabled?.contentOrNull ?: "0").takeIf { kind == "Flag" && it != listOf("1", "0") }
        val choices = words ?: values.map { it.first() }
        val type = when (kind) {
            "Flag" -> ValueType.Flag
            "ListValue", "RichListValue" -> if (choices.isNotEmpty()) ValueType.Enumeration() else typed?.type ?: ValueType.Text()
            "DynamicList", "MultiValue" -> typed?.type as? ValueType.Many ?: ValueType.Many(typed?.type ?: ValueType.Text(), LIST, typed?.negatable == true)
            else -> if (password == true) ValueType.Secret else typed?.type ?: ValueType.Text()
        }.let { if (multiple == true && it !is ValueType.Many) ValueType.Many(it, LIST) else it }
        val own = conditions()
        val depends = when {
            more.isEmpty() -> own
            own.isEmpty() -> more
            else -> more.flatMap { other -> own.map { it + other } }.distinct()
        }
        val shownOn = tab?.let { id -> tabs.firstOrNull { it.name == id }?.shown }
        val labels = values.mapNotNull { value -> value.getOrNull(1)?.let(::plain)?.takeIf { it.isNotEmpty() && it != value[0] }?.let { value[0] to it } }.toMap()
        return Argument(
            name, type, choices.ifEmpty { typed?.choices.orEmpty() }, readOnly = readonly == true, negatable = typed?.negatable == true && type !is ValueType.Many,
            optional = optional == true && type is ValueType.Enumeration && default?.contentOrNull == null, label = title?.contentOrNull?.let(::plain), tab = shownOn,
            depends = depends, default = default?.contentOrNull, labels = labels, placeholder = placeholder?.contentOrNull?.takeIf { it.isNotEmpty() },
        )
    }

    private fun conditions(): List<Map<String, String>> = depends.map { condition ->
        condition.mapValues { (key, value) -> (value as? JsonPrimitive)?.takeIf { it.isString && !key.startsWith('!') }?.content ?: return emptyList() }
    }

    private companion object {
        val shown = setOf("DummyValue", "Button", "SectionValue", "HiddenValue")
    }
}

private fun plain(title: String) =
    title.replace(markup, "").replace("&#160;", " ").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&").trim()

private val markup = Regex("<[^>]+>")

internal val luciJson = Json { ignoreUnknownKeys = true }

// LuCI passes over a menu file it cannot read as JSON
internal fun menuNodes(app: String, menu: String): List<LuciNode> {
    val entries = try {
        luciJson.parseToJsonElement(menu) as? JsonObject
    } catch (e: SerializationException) {
        null
    }
    return entries.orEmpty().mapNotNull { (path, entry) ->
        val node = entry as? JsonObject ?: return@mapNotNull null
        val action = node["action"] as? JsonObject
        val depends = node["depends"] as? JsonObject
        LuciNode(
            path = path,
            app = app,
            title = (node["title"] as? JsonPrimitive)?.contentOrNull,
            // LuCI takes an order only as a number: adblock and banip give theirs as a string, and stay at the default
            order = (node["order"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull,
            view = (action?.get("path") as? JsonPrimitive)?.contentOrNull?.takeIf { (action["type"] as? JsonPrimitive)?.contentOrNull == "view" },
            configs = conditionConfigs(depends?.get("uci")).toList(),
            fs = depends?.get("fs"),
            uci = depends?.get("uci"),
        )
    }
}

internal fun configsOfView(source: String): Set<String> = (mapCall.findAll(source) + ownConfig.findAll(source)).map { it.groupValues[1] }.toSet()

private val mapCall = Regex("""\bform\.Map\(\s*['"]([\w.-]+)['"]""")
private val ownConfig = Regex("""\.uciconfig\s*=\s*['"]([\w.-]+)['"]""")
