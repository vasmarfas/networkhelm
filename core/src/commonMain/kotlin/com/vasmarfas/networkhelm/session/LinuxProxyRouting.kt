package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// the outbounds of sing-box and Xray and the rules of their routing. An outbound is direct, block of Xray (sing-box blocks by the action of a
// rule since 1.11 and has no block outbound since 1.13) or the server of a link, which may dial through another outbound: a chain. What no
// rule takes goes to the first outbound, or to route.final of sing-box. A rule sends what matches its domains, geosite, geoip, addresses or
// inbounds to an outbound, or blocks it; geosite and geoip are the files of Xray and the rule sets of SagerNet for sing-box, which has no
// geosite of its own since 1.12
internal class LinuxProxyRouting(private val ssh: SshSession, private val json: LinuxProxyJson, private val tools: suspend () -> Set<String>) {
    val tables: Map<String, HostTable> = JSON_KINDS.flatMap { kind ->
        val present: suspend () -> Boolean = { kind.id in tools() }
        listOf(
            "proxy/${kind.id}/outbound" to HostTable(
                OUTBOUND_FIELDS, { outbounds(kind) }, slow = true, arguments = { outboundArguments(kind) }, add = { values -> addOutbound(kind, values) },
                remove = { ids -> removeOutbounds(kind, ids) }, commands = listOf("default"), run = { _, arguments -> makeDefault(kind, arguments["number"].orEmpty()) },
                present = present,
            ),
            "proxy/${kind.id}/rule" to HostTable(
                RULE_FIELDS, { rules(kind) }, slow = true, arguments = { ruleArguments(kind) }, add = { values -> addRule(kind, values) },
                set = { id, changes -> setRule(kind, id, changes) }, remove = { ids -> removeRules(kind, ids) }, move = { ids, before -> moveRules(kind, ids, before) },
                commands = listOf("block-ads"), run = { _, _ -> blockAds(kind) }, present = present,
            ),
        )
    }.toMap()

    private suspend fun outbounds(kind: JsonKind): List<Item> {
        val host = json.state(kind)
        val chosen = if (kind.sing) (host.json["route"] as? JsonObject)?.text("final").orEmpty() else ""
        return host.outboundList().mapIndexed { index, outbound ->
            val tag = kind.tag(outbound)
            Item(
                "${index + 1}",
                linkedMapOf(
                    "tag" to tag, "type" to kind.type(outbound), "server" to kind.serverOf(outbound), "through" to kind.through(outbound),
                    "default" to "${if (chosen.isEmpty()) index == 0 else tag == chosen}",
                ),
            )
        }
    }

    private suspend fun outboundArguments(kind: JsonKind): List<Argument> {
        val tags = json.state(kind).outboundList().map(kind::tag).filter { it.isNotEmpty() }
        val kinds = outboundKinds(kind)
        return listOf(
            Argument("kind", ValueType.Enumeration(closed = true), kinds, default = kinds.first(), labels = OUTBOUND_KINDS.filterKeys { it in kinds }, fixed = true),
            Argument("tag", ValueType.Text(), placeholder = AUTO, fixed = true),
            Argument("link", ValueType.Secret, placeholder = "vless://, vmess://, trojan://, ss://, hy2://", fixed = true, depends = LINKED),
            Argument("through", ValueType.Enumeration(closed = true), tags, optional = true, fixed = true, depends = LINKED),
        )
    }

    // a new outbound goes after the others, so what goes by default stays; one of a link dials through another where the form says so, and
    // sing-box gets the same check against REALITY of Xray its clients get
    private suspend fun addOutbound(kind: JsonKind, values: Map<String, String>): String {
        val host = json.full(kind)
        val outbounds = host.outboundList()
        val type = values["kind"]?.ifEmpty { null } ?: outboundKinds(kind).first()
        if (type !in outboundKinds(kind)) throw DeviceException(ErrorCategory.ArgumentValue, "kind: $type is not a value it takes")
        val tag = values["tag"]?.trim()?.ifEmpty { null } ?: freeTag(outbounds.map(kind::tag), if (type == "link") "proxy" else type)
        if (!OUTBOUND_TAG.matches(tag)) throw DeviceException(ErrorCategory.ArgumentValue, "tag: $tag is not a value it takes")
        if (outbounds.any { kind.tag(it) == tag }) throw DeviceException(ErrorCategory.ArgumentValue, "${kind.id} has an outbound $tag already")
        val through = values["through"]?.ifEmpty { null }
        if (through != null && outbounds.none { kind.tag(it) == through }) throw DeviceException(ErrorCategory.ArgumentValue, "through: $through is not a value it takes")
        val outbound = when (type) {
            "direct" -> JsonObject(kind.direct + ("tag" to JsonPrimitive(tag)))
            "block" -> blackhole(tag)
            else -> {
                val link = parseProxyLink(values["link"].orEmpty())
                if (kind.sing) refuseMlkem(ssh, link, "make this outbound in Xray")
                val made = if (kind.sing) singBoxOutbound(link, tag) else xrayOutbound(link, tag)
                if (through == null) made else made.placed(if (kind.sing) listOf("detour") else listOf("streamSettings", "sockopt", "dialerProxy"), JsonPrimitive(through))
            }
        }
        json.save(kind, host.with(host.inboundList(), outbounds + outbound))
        return "${outbounds.size + 1}"
    }

    // an outbound a rule, a chain, the default or DNS still names stays, as the program would refuse the file without it
    private suspend fun removeOutbounds(kind: JsonKind, ids: List<String>) {
        val host = json.full(kind)
        val outbounds = host.outboundList()
        val gone = ids.map { numberOf(kind, "outbound", outbounds.size, it) }
        val rest = JsonObject(host.json + ("outbounds" to JsonArray(outbounds.filterIndexed { index, _ -> index + 1 !in gone })))
        gone.map { kind.tag(outbounds[it - 1]) }.firstOrNull { it.isNotEmpty() && mentions(rest, it) }?.let {
            throw DeviceException(ErrorCategory.ArgumentValue, "the outbound $it is still named in the config: a rule, a chain or DNS goes through it")
        }
        json.save(kind, host.encoded(rest))
    }

    // the outbound goes first, where both programs send what no rule takes; route.final of sing-box, which would say otherwise, goes
    private suspend fun makeDefault(kind: JsonKind, id: String): List<Item> {
        val host = json.full(kind)
        val outbounds = host.outboundList()
        val index = numberOf(kind, "outbound", outbounds.size, id)
        val moved = JsonObject(host.json + ("outbounds" to JsonArray(listOf(outbounds[index - 1]) + outbounds.filterIndexed { at, _ -> at != index - 1 })))
        json.save(kind, host.encoded(if (kind.sing && host.json["route"] != null) moved.placed(listOf("route", "final"), null) else moved))
        return emptyList()
    }

    private suspend fun rules(kind: JsonKind): List<Item> = kind.rulesOf(json.state(kind).json).mapIndexed { index, rule ->
        val shown = kind.conditions(rule).mapValues { (_, items) -> items.joinToString(", ") } + ("more" to kind.more(rule).joinToString(", "))
        Item("${index + 1}", shown.filterValues { it.isNotEmpty() } + ("outbound" to kind.target(rule)))
    }

    private suspend fun ruleArguments(kind: JsonKind): List<Argument> {
        val host = json.state(kind)
        val outbounds = (host.outboundList().map(kind::tag).filter { it.isNotEmpty() } + BLOCK).distinct()
        return listOf(
            Argument("domain", ValueType.Text(), optional = true, placeholder = "example.com, full:www.example.com, keyword:ads"),
            Argument("geosite", ValueType.Text(), optional = true, placeholder = ADS),
            Argument("geoip", ValueType.Text(), optional = true, placeholder = PRIVATE),
            Argument("ip", ValueType.Text(), optional = true, placeholder = "10.0.0.0/8, 2001:db8::/32"),
            Argument("inbound", ValueType.Enumeration(multiple = true), host.inboundList().map(kind::tag).filter { it.isNotEmpty() }, optional = true),
            Argument("outbound", ValueType.Enumeration(closed = true), outbounds, default = BLOCK),
        )
    }

    // a new rule goes after the others, which the program reads in their order
    private suspend fun addRule(kind: JsonKind, values: Map<String, String>): String {
        val host = json.full(kind)
        val rules = kind.rulesOf(host.json)
        val fresh = JsonObject(if (kind.sing) emptyMap() else mapOf("type" to JsonPrimitive("field")))
        saveRules(kind, host, rules + ruled(kind, host, fresh, mapOf("outbound" to BLOCK) + values.filterValues { it.isNotEmpty() }))
        return "${rules.size + 1}"
    }

    private suspend fun setRule(kind: JsonKind, id: String?, changes: Map<String, String?>) {
        val host = json.full(kind)
        val rules = kind.rulesOf(host.json)
        val index = numberOf(kind, "rule", rules.size, id)
        saveRules(kind, host, rules.mapIndexed { at, rule -> if (at == index - 1) ruled(kind, host, rule, changes) else rule })
    }

    private suspend fun removeRules(kind: JsonKind, ids: List<String>) {
        val host = json.full(kind)
        val rules = kind.rulesOf(host.json)
        val gone = ids.map { numberOf(kind, "rule", rules.size, it) }
        saveRules(kind, host, rules.filterIndexed { index, _ -> index + 1 !in gone })
    }

    private suspend fun moveRules(kind: JsonKind, ids: List<String>, before: String?) {
        val host = json.full(kind)
        val rules = kind.rulesOf(host.json)
        val moved = ids.map { numberOf(kind, "rule", rules.size, it) }
        val order = rules.indices.map { it + 1 }.filter { it !in moved }.toMutableList()
        val at = before?.let { order.indexOf(numberOf(kind, "rule", rules.size, it)) }?.takeIf { it >= 0 } ?: order.size
        order.addAll(at, moved)
        saveRules(kind, host, order.map { rules[it - 1] })
    }

    // the example of a rule: the list category-ads-all of geosite blocked, once
    private suspend fun blockAds(kind: JsonKind): List<Item> {
        val rules = kind.rulesOf(json.full(kind).json)
        if (rules.none { kind.conditions(it)["geosite"] == listOf(ADS) && kind.target(it) == BLOCK }) addRule(kind, mapOf("geosite" to ADS, "outbound" to BLOCK))
        return emptyList()
    }

    // the rule with what the form changed, each new value checked; a rule with no condition would take everything, which Xray refuses
    private fun ruled(kind: JsonKind, host: JsonProxyState, rule: JsonObject, changes: Map<String, String?>): JsonObject {
        val inbounds = host.inboundList().map(kind::tag)
        val outbounds = host.outboundList().map(kind::tag) + BLOCK
        var changed = rule
        for ((field, value) in changes) {
            val items = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val before = kind.conditions(rule)[field].orEmpty()
            val wrong = when (field) {
                "outbound" -> items.firstOrNull().orEmpty().takeIf { it !in outbounds || items.size > 1 }
                "inbound" -> items.firstOrNull { it !in before && it !in inbounds }
                in CONDITIONS -> items.firstOrNull { item ->
                    item !in before && (!CONDITIONS.getValue(field).matches(item) || field == "domain" && kind.sing && ':' in item && SING_PREFIXES.none { item.startsWith(it) })
                }
                else -> continue
            }
            if (wrong != null) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $wrong is not a value it takes")
            changed = if (field == "outbound") kind.withTarget(changed, items.single()) else kind.withCondition(changed, field, items)
        }
        if (kind.conditions(changed).values.all { it.isEmpty() } && kind.more(changed).isEmpty()) {
            throw DeviceException(ErrorCategory.ArgumentValue, "a rule needs a domain, a geosite, a geoip, an ip or an inbound")
        }
        return changed
    }

    private suspend fun saveRules(kind: JsonKind, host: JsonProxyState, rules: List<JsonObject>) =
        json.save(kind, host.encoded(kind.withRuleSets(kind.withBlock(kind.withRules(host.json, rules)))))
}

// the rules that name an inbound by its tag go on naming it after its tag changes
internal fun JsonKind.retagged(config: JsonObject, old: String, new: String): JsonObject {
    val key = if (sing) "inbound" else "inboundTag"
    val rules = rulesOf(config)
    val renamed = rules.map { rule -> if (old !in rule.names(key)) rule else rule.withList(key, rule.names(key).map { if (it == old) new else it }) }
    return if (old == new || renamed == rules) config else withRules(config, renamed)
}

private fun JsonKind.rulesOf(config: JsonObject) = (config[if (sing) "route" else "routing"] as? JsonObject)?.listed("rules").orEmpty()

private fun JsonKind.withRules(config: JsonObject, rules: List<JsonObject>) = config.placed(listOf(if (sing) "route" else "routing", "rules"), JsonArray(rules))

// the conditions of a rule by the fields of the form. sing-box keeps the domains of each kind in a field of its own, geosite and geoip as
// rule sets and private addresses as ip_is_private; Xray keeps domains in one list by their prefixes beside geosite, and addresses beside
// geoip. A domain of Xray without a prefix matches as a part of the name, which keyword: says
private fun JsonKind.conditions(rule: JsonObject): Map<String, List<String>> = if (sing) {
    val sets = rule.names("rule_set")
    mapOf(
        "domain" to SING_DOMAINS.flatMap { (key, prefix) -> rule.names(key).map { prefix + it } },
        "geosite" to sets.filter { it.startsWith(GEOSITE) }.map { it.removePrefix(GEOSITE) },
        "geoip" to listOfNotNull(PRIVATE.takeIf { (rule["ip_is_private"] as? JsonPrimitive)?.booleanOrNull == true }) + sets.filter { it.startsWith(GEOIP) }.map { it.removePrefix(GEOIP) },
        "ip" to rule.names("ip_cidr"),
        "inbound" to rule.names("inbound"),
    )
} else {
    val (sites, domains) = rule.names("domain").partition { it.startsWith("geosite:") }
    val (countries, addresses) = rule.names("ip").partition { it.startsWith("geoip:") }
    mapOf(
        "domain" to domains.map { if (':' in it) it.removePrefix("domain:") else "keyword:$it" },
        "geosite" to sites.map { it.removePrefix("geosite:") },
        "geoip" to countries.map { it.removePrefix("geoip:") },
        "ip" to addresses,
        "inbound" to rule.names("inboundTag"),
    )
}

// the rule with the conditions of one field of the form in place of those it had, the rest of the rule as it was
private fun JsonKind.withCondition(rule: JsonObject, field: String, items: List<String>): JsonObject {
    val now = conditions(rule)
    if (!sing) return when (field) {
        "domain", "geosite" -> {
            val domains = (if (field == "domain") items else now.getValue("domain")).map { if (':' in it) it else "domain:$it" }
            rule.withList("domain", domains + (if (field == "geosite") items else now.getValue("geosite")).map { "geosite:$it" })
        }
        "ip", "geoip" -> rule.withList("ip", (if (field == "ip") items else now.getValue("ip")) + (if (field == "geoip") items else now.getValue("geoip")).map { "geoip:$it" })
        else -> rule.withList("inboundTag", items)
    }
    val sets = rule.names("rule_set")
    return when (field) {
        "domain" -> SING_DOMAINS.fold(rule) { changed, (key, prefix) ->
            val own = items.filter { item -> if (prefix.isEmpty()) SING_DOMAINS.drop(1).none { item.startsWith(it.second) } else item.startsWith(prefix) }
            changed.withList(key, own.map { if (prefix.isEmpty()) it.removePrefix("domain:") else it.removePrefix(prefix) })
        }
        "geosite" -> rule.withList("rule_set", sets.filterNot { it.startsWith(GEOSITE) } + items.map { GEOSITE + it })
        "geoip" -> rule.withList("rule_set", sets.filterNot { it.startsWith(GEOIP) } + items.filter { it != PRIVATE }.map { GEOIP + it })
            .placed(listOf("ip_is_private"), JsonPrimitive(true).takeIf { PRIVATE in items })
        "ip" -> rule.withList("ip_cidr", items)
        else -> rule.withList("inbound", items)
    }
}

// where a rule sends what it matches: block is the action reject of sing-box and the outbound of that tag in Xray
private fun JsonKind.target(rule: JsonObject): String = if (sing) {
    when (val action = rule.text("action").ifEmpty { "route" }) {
        "route" -> rule.text("outbound")
        "reject" -> BLOCK
        else -> action
    }
} else {
    rule.text("outboundTag").ifEmpty { rule.text("balancerTag").let { if (it.isEmpty()) "" else "balancer:$it" } }
}

private fun JsonKind.withTarget(rule: JsonObject, outbound: String): JsonObject = when {
    !sing -> rule.placed(listOf("outboundTag"), JsonPrimitive(outbound)).placed(listOf("balancerTag"), null)
    outbound == BLOCK -> rule.placed(listOf("action"), JsonPrimitive("reject")).placed(listOf("outbound"), null)
    else -> rule.placed(listOf("action"), JsonPrimitive("route")).placed(listOf("outbound"), JsonPrimitive(outbound))
}

// the keys of a rule the form leaves to the editor, as port or network
private fun JsonKind.more(rule: JsonObject): List<String> {
    val others = rule.names("rule_set").any { !it.startsWith(GEOSITE) && !it.startsWith(GEOIP) }
    return rule.keys.filter { it !in (if (sing) SING_HANDLED else XRAY_HANDLED) || it == "rule_set" && others }
}

// block of Xray is its outbound of that tag: a blackhole, made after the others, where a rule names it and the config has none
private fun JsonKind.withBlock(config: JsonObject): JsonObject {
    val outbounds = config.listed("outbounds")
    if (sing || outbounds.any { tag(it) == BLOCK } || rulesOf(config).none { it.text("outboundTag") == BLOCK }) return config
    return JsonObject(config + ("outbounds" to JsonArray(outbounds.ifEmpty { listOf(direct) } + blackhole(BLOCK))))
}

// the rule sets of SagerNet the rules of sing-box name, defined where the config does not define them yet
private fun JsonKind.withRuleSets(config: JsonObject): JsonObject {
    val defined = (config["route"] as? JsonObject)?.listed("rule_set").orEmpty()
    val missing = rulesOf(config).flatMap { it.names("rule_set") }.filter { tag -> (tag.startsWith(GEOSITE) || tag.startsWith(GEOIP)) && defined.none { it.text("tag") == tag } }.distinct()
    if (!sing || missing.isEmpty()) return config
    val sets = missing.map { tag ->
        val repository = "sing-${tag.substringBefore('-')}"
        JsonObject(listOf("type" to "remote", "tag" to tag, "format" to "binary", "url" to "https://raw.githubusercontent.com/SagerNet/$repository/rule-set/$tag.srs").associate { (key, value) -> key to JsonPrimitive(value) })
    }
    return config.placed(listOf("route", "rule_set"), JsonArray(defined + sets))
}

// the outbound a chain dials through: detour of sing-box, dialerProxy of Xray or its older proxySettings
private fun JsonKind.through(outbound: JsonObject): String = if (sing) {
    outbound.text("detour")
} else {
    ((outbound["streamSettings"] as? JsonObject)?.get("sockopt") as? JsonObject)?.text("dialerProxy").orEmpty().ifEmpty { (outbound["proxySettings"] as? JsonObject)?.text("tag").orEmpty() }
}

// whether anything of the config names the outbound: a rule, a chain, route.final, a rule set or a server of DNS that dials through it
private fun mentions(element: JsonElement, tag: String): Boolean = when (element) {
    is JsonObject -> element.any { (key, value) ->
        key in REFERENCES && (value as? JsonPrimitive)?.contentOrNull == tag || key == "proxySettings" && (value as? JsonObject)?.text("tag") == tag || mentions(value, tag)
    }
    is JsonArray -> element.any { mentions(it, tag) }
    else -> false
}

private fun outboundKinds(kind: JsonKind) = if (kind.sing) listOf("link", "direct") else listOf("link", "direct", "block")

private fun blackhole(tag: String) = JsonObject(mapOf("protocol" to JsonPrimitive("blackhole"), "tag" to JsonPrimitive(tag)))

private fun freeTag(tags: List<String>, base: String) = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in tags }

private fun numberOf(kind: JsonKind, what: String, count: Int, id: String?) =
    id?.toIntOrNull()?.takeIf { it in 1..count } ?: throw DeviceException(ErrorCategory.MissingItem, "${kind.id} has no $what $id")

// a field sing-box takes as one value or a list of them
private fun JsonObject.names(key: String): List<String> = when (val value = this[key]) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> listOfNotNull(value.contentOrNull)
    else -> emptyList()
}

private fun JsonObject.withList(key: String, values: List<String>) = placed(listOf(key), values.ifEmpty { null }?.let { list -> JsonArray(list.map(::JsonPrimitive)) })

private const val AUTO = "auto"
private const val BLOCK = "block"
private const val ADS = "category-ads-all"
private const val PRIVATE = "private"
private const val GEOSITE = "geosite-"
private const val GEOIP = "geoip-"

private val OUTBOUND_KINDS = mapOf("link" to "Server of a Link", "direct" to "Direct", "block" to "Block")
private val OUTBOUND_TAG = Regex("[A-Za-z0-9_.-]{1,48}")
private val LINKED = listOf(mapOf("kind" to "link"))

// the kinds of domains of sing-box by the prefixes the form gives them; a domain without one matches with its subdomains
private val SING_DOMAINS = listOf("domain_suffix" to "", "domain" to "full:", "domain_keyword" to "keyword:", "domain_regex" to "regexp:")
private val SING_PREFIXES = listOf("full:", "keyword:", "regexp:", "domain:")
private val CONDITIONS = mapOf(
    "domain" to Regex("\\S+"), "geosite" to Regex("[A-Za-z0-9!@._-]+"), "geoip" to Regex("[A-Za-z0-9!._-]+"), "ip" to Regex("[0-9a-fA-F.:]+(/\\d{1,3})?"),
)
private val SING_HANDLED = setOf("domain_suffix", "domain", "domain_keyword", "domain_regex", "rule_set", "ip_is_private", "ip_cidr", "inbound", "outbound", "action")
private val XRAY_HANDLED = setOf("type", "domain", "ip", "inboundTag", "outboundTag", "balancerTag")
private val REFERENCES = setOf("outbound", "outboundTag", "detour", "download_detour", "final", "dialerProxy")

private val OUTBOUND_FIELDS = listOf("tag", "type", "server", "through", "default")
private val RULE_FIELDS = listOf("domain", "geosite", "geoip", "ip", "inbound", "outbound", "more")
