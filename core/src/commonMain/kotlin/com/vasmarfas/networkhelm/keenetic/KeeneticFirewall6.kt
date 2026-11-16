package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val ANY6 = "::/0"

// the rules of the IPv6 tab of the firewall of the web interface: an ipv6 access-list each interface takes in or out, kept,
// numbered and moved as the lists of IPv4 are. A rule of ICMP has the type and code of ICMPv6, and only TCP and UDP have ports
internal class Firewall6(private val api: KeeneticCall, private val words: () -> Map<String, String>) : Node("firewall/ipv6") {
    override val commands = listOf("print", "add", "set", "remove", "move")

    override suspend fun arguments(): List<Argument> {
        val protocols = PROTOCOLS + SERVICES.keys.filter { !it.startsWith("tcpudp/") }.associateWith { serviceLabel(it, words()) }
        val schedules = scheduleNames(api.shows("rc/schedule").single())
        return api.withInterfaces(
            listOf(
                Argument("interface", ValueType.Text()),
                Argument("direction", ValueType.Enumeration(closed = true), listOf("in", "out"), default = "in"),
                Argument("action", ValueType.Enumeration(closed = true), ACTIONS.keys.toList(), default = "permit", labels = ACTIONS),
                Argument("protocol", ValueType.Enumeration(), protocols.keys.toList(), default = "tcp", labels = protocols),
                Argument("source", ValueType.Text(), optional = true, placeholder = "any"),
                Argument("source-port", ValueType.Text(), optional = true, placeholder = "any"),
                Argument("destination", ValueType.Text(), optional = true, placeholder = "any"),
                Argument("destination-port", ValueType.Text(), optional = true, placeholder = "any"),
                Argument("icmp-type", ValueType.Enumeration(), ICMP_TYPES, optional = true),
                Argument("icmp-code", ValueType.Integer(0, 255), optional = true),
                Argument("schedule", ValueType.Enumeration(closed = true), listOf("") + schedules.keys, optional = true, labels = schedules),
                Argument("description", ValueType.Text(), optional = true),
                Argument("disabled", ValueType.YesNo),
            ),
        )
    }

    override suspend fun read(): List<Item> = rules().map { it.first }

    // each rule as the window shows it and as show rc gives it, by which the router finds it to remove
    private suspend fun rules(): List<Pair<Item, Map<String, String>>> {
        val (lists, config) = api.shows("rc/ipv6/access-list", "rc/interface")
        val bindings = (config as? JsonObject).orEmpty().flatMap { (name, element) ->
            val groups = (((element as? JsonObject)?.get("ipv6") as? JsonObject)?.get("access-group") as? JsonArray).orEmpty()
            groups.mapNotNull { it as? JsonObject }.mapNotNull { group -> group.text("acl")?.let { it to (name to group.text("direction").orEmpty()) } }
        }.toMap()
        val places = HashMap<String, Int>()
        return rows(lists).filter { it["action"] != null }.map { rule ->
            val acl = rule["acl"].orEmpty()
            val place = places.getOrElse(acl) { 0 }.also { places[acl] = it + 1 }
            Item(
                rule["index"] ?: "$acl#$place",
                linkedMapOf(
                    "interface" to bindings[acl]?.first.orEmpty(),
                    "direction" to bindings[acl]?.second.orEmpty(),
                    "acl" to acl,
                    "position" to "$place",
                    "action" to rule["action"].orEmpty(),
                    "protocol" to rule["protocol"].orEmpty(),
                    "source" to shownAddress(rule["source"]),
                    "source-port" to port(rule["src-port-operator"], rule["source-port"], rule["source-end-port"]),
                    "destination" to shownAddress(rule["destination"]),
                    "destination-port" to port(rule["dst-port-operator"], rule["destination-port"], rule["destination-end-port"]),
                    "icmp-type" to rule["icmp-type"].orEmpty(),
                    "icmp-code" to rule["icmp-code"].orEmpty(),
                    "schedule" to rule["schedule"].orEmpty(),
                    "description" to rule["description"].orEmpty(),
                    "disabled" to (rule["disable"] ?: "false"),
                ),
            ) to rule.values
        }
    }

    override suspend fun add(values: Map<String, String>): String {
        val face = values["interface"].orEmpty()
        val direction = values["direction"]?.ifEmpty { null } ?: "in"
        if (face.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "a rule needs the interface whose traffic it filters")
        val rules = read()
        val acl = rules.firstOrNull { it["interface"] == face && it["direction"] == direction }?.get("acl") ?: "_WEBADMIN_$face"
        val place = rules.count { it["acl"] == acl }
        api.post(listOfNotNull(list(acl, match(values)), binding(face, acl, direction).takeIf { rules.none { it["acl"] == acl && it["interface"] == face } }))
        api.send(extras(acl, place, values, emptyMap()))
        return read().firstOrNull { it["acl"] == acl && it["position"] == "$place" }?.id.orEmpty()
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val (old, shown) = rules().firstOrNull { it.first.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "firewall/ipv6 has no $id")
        if (listOf("interface", "direction").any { name -> changes[name]?.let { it != old[name] } == true }) throw DeviceException(ErrorCategory.Unsupported, "a rule stays with its interface; add it to the other one and remove this one")
        val acl = old["acl"].orEmpty()
        val place = old["position"].orEmpty().toInt()
        val merged = old.values + changes.mapValues { it.value.orEmpty() }
        if (match(merged) != match(old.values)) {
            val last = read().count { it["acl"] == acl }
            api.post(listOf(list(acl, match(merged))))
            api.post(listOf(rule(acl, last, "new-index" to JsonPrimitive(place)), removal(shown)) + extras(acl, place, merged, emptyMap()))
        } else {
            api.send(extras(acl, place, changes, old.values))
        }
    }

    // the router keeps a list it has no rule left in, bound to its interface; the web interface leaves none of its own behind
    override suspend fun remove(ids: List<String>) {
        val rules = rules()
        val removed = ids.map { id -> rules.firstOrNull { it.first.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "firewall/ipv6 has no $id") }
        api.post(removed.map { removal(it.second) })
        val emptied = removed.map { it.first }.groupBy { it["acl"].orEmpty() }.filter { (acl, gone) -> acl.startsWith("_WEBADMIN_") && rules.count { it.first["acl"] == acl } == gone.size }
        api.send(
            emptied.flatMap { (acl, gone) ->
                val rule = gone.first()
                listOfNotNull(
                    rule["interface"]?.ifEmpty { null }?.let { face ->
                        nested(listOf("interface", face, "ipv6", "access-group"), JsonObject(mapOf("no" to JsonPrimitive(true), "acl" to JsonPrimitive(acl), "direction" to JsonPrimitive(rule["direction"].orEmpty()))))
                    },
                    JsonObject(mapOf("parse" to JsonPrimitive("no ipv6 access-list ${quoted(acl)}"))),
                )
            },
        )
    }

    override suspend fun move(ids: List<String>, before: String?) {
        val rules = read()
        val moved = rules.firstOrNull { it.id == ids.singleOrNull() } ?: throw DeviceException(ErrorCategory.Unsupported, "Keenetic moves one rule at a time")
        val list = rules.filter { it["acl"] == moved["acl"] }.map { it.id }
        val target = before?.let { list.indexOf(it) } ?: list.size
        if (target < 0) throw DeviceException(ErrorCategory.Unsupported, "a rule moves only within the list of its interface")
        val from = list.indexOf(moved.id)
        val place = if (target > from) target - 1 else target
        if (place != from) api.post(listOf(rule(moved["acl"].orEmpty(), from, "new-index" to JsonPrimitive(place))))
    }

    // all of the match, as show rc gives it; an acl alone would remove the whole list
    private fun removal(rule: Map<String, String>): JsonObject {
        val acl = rule["acl"].orEmpty()
        val match = rule.filterKeys { it in MATCH }
        if (acl.isEmpty() || "action" !in match || "protocol" !in match) throw DeviceException(ErrorCategory.ArgumentValue, "the rule has no match the router can find it by")
        return list(acl, mapOf("no" to JsonPrimitive(true)) + match.mapValues { JsonPrimitive(it.value) })
    }

    private fun extras(acl: String, place: Int, changes: Map<String, String?>, old: Map<String, String>): List<JsonObject> = listOfNotNull(
        changes["description"]?.takeIf { it != old["description"].orEmpty() }?.let { rule(acl, place, "description" to JsonPrimitive(it)) },
        changes["schedule"]?.takeIf { it != old["schedule"].orEmpty() }?.let { rule(acl, place, "schedule" to if (it.isEmpty()) NO else JsonPrimitive(it)) },
        changes["disabled"]?.let { it in ValueType.Flag.on }?.takeIf { it != (old["disabled"] in ValueType.Flag.on) }?.let { rule(acl, place, "disable" to if (it) JsonPrimitive(true) else NO) },
    )

    private fun list(acl: String, body: Map<String, JsonElement>) = nested(listOf("ipv6", "access-list"), JsonObject(mapOf<String, JsonElement>("acl" to JsonPrimitive(acl)) + body))

    private fun rule(acl: String, place: Int, change: Pair<String, JsonElement>) = list(acl, mapOf("rule" to JsonObject(mapOf("index" to JsonPrimitive(place), change))))

    private fun binding(face: String, acl: String, direction: String) =
        nested(listOf("interface", face, "ipv6", "access-group"), JsonObject(mapOf("acl" to JsonPrimitive(acl), "direction" to JsonPrimitive(direction))))

    private fun match(values: Map<String, String?>): Map<String, JsonElement> = buildMap {
        val protocol = values["protocol"]?.ifEmpty { null } ?: "ipv6"
        val service = protocol.takeIf { '/' in it }
        val chosen = service?.substringBefore('/') ?: protocol
        put("action", JsonPrimitive(values["action"]?.ifEmpty { null } ?: "permit"))
        put("protocol", JsonPrimitive(chosen))
        for (side in listOf("source", "destination")) putAll(address6(side, values[side].orEmpty()))
        if (chosen == "icmp") {
            values["icmp-type"]?.ifEmpty { null }?.let { type ->
                put("icmp-type", JsonPrimitive(type))
                values["icmp-code"]?.ifEmpty { null }?.let { put("icmp-code", JsonPrimitive(it)) }
            }
        }
        if (chosen != "tcp" && chosen != "udp") return@buildMap
        for ((side, operator, prefix) in listOf(Triple("source-port", "src-port-operator", "source"), Triple("destination-port", "dst-port-operator", "destination"))) {
            val ports = (if (side == "destination-port" && service != null) service.substringAfter('/') else values[side].orEmpty()).let(::ports) ?: continue
            put(operator, JsonPrimitive(ports.first))
            put("$prefix-port", JsonPrimitive(ports.second))
            ports.third?.let { put("$prefix-end-port", JsonPrimitive(it)) }
        }
    }

    // any address is ::/0; one address goes with the mask of a host, as the web interface sends it, a prefix as it is
    private fun address6(side: String, text: String): Map<String, JsonElement> {
        val value = text.trim()
        return when {
            value.isEmpty() || value == "any" || value == ANY6 -> mapOf(side to JsonPrimitive(ANY6))
            '/' in value -> mapOf(side to JsonPrimitive(value))
            else -> mapOf(side to JsonPrimitive(value), "$side-mask" to JsonPrimitive("255.255.255.255"))
        }
    }

    private fun shownAddress(address: String?) = address?.takeIf { it != ANY6 && it != "any" }.orEmpty()

    private companion object {
        // the protocols help gives an ipv6 access-list, in the order of the list of the web interface
        val PROTOCOLS = linkedMapOf("tcp" to "TCP", "udp" to "UDP", "icmp" to "ICMP", "esp" to "ESP", "ipv6" to "IPv6", "pcp" to "PCP", "sctp" to "SCTP", "ahp" to "AHP")
        val ICMP_TYPES = listOf("echo-request", "echo-reply", "nd-ns", "nd-na", "router-solicitation", "router-advertisement")
        val MATCH = setOf(
            "action", "protocol", "source", "source-mask", "destination", "destination-mask", "src-port-operator", "source-port", "source-end-port",
            "dst-port-operator", "destination-port", "destination-end-port", "icmp-type", "icmp-code",
        )
    }
}
