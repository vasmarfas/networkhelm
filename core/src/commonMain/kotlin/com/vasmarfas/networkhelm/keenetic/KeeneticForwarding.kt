package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// the services the web interface offers as protocols of a rule, with the port they fill in; tcpudp/3389 is named as tcp/3389
internal val SERVICES = linkedMapOf(
    "tcp/20" to "File transfer (FTP)", "tcp/21" to "File transfer (FTP)", "tcp/22" to "Secure Shell (SSH)", "tcp/23" to "Remote terminal (Telnet)",
    "tcp/25" to "E-mail transmission (SMTP)", "tcp/53" to "DNS service", "udp/53" to "Domain name service (DNS)", "tcp/80" to "HTTP protocol",
    "tcp/110" to "E-mail retrieval (POP)", "tcp/123" to "Internet time server (NTP)", "udp/123" to "Internet time server (NTP)",
    "udp/137" to "Windows Internet Name Service (WINS)", "udp/138" to "NetBIOS Datagram Service (NBDS)", "tcp/139" to "NetBIOS Session Service (NBSS)",
    "tcp/143" to "E-mail retrieval (IMAP)", "tcp/443" to "HTTPS protocol", "tcp/445" to "MS Active Directory Services", "udp/500" to "ISAKMP/IKE",
    "udp/514" to "Syslog server", "tcp/515" to "Network printing (LPR, LPD)", "tcp/554" to "Internet telephony (RTSP)", "udp/554" to "Internet telephony (RTSP)",
    "tcp/631" to "Network printing (IPP)", "tcp/853" to "DNS-over-TLS", "udp/853" to "DNS-over-TLS", "tcp/993" to "Secure IMAP (IMAPS)",
    "tcp/995" to "Secure POP (POP3S)", "udp/995" to "Secure POP (POP3S)", "tcp/1080" to "SOCKS", "udp/1080" to "SOCKS", "tcp/1194" to "OpenVPN",
    "udp/1194" to "OpenVPN", "udp/1701" to "L2TP tunneling", "tcp/1723" to "PPTP tunneling", "tcp/3306" to "MySQL server",
    "tcp/3389" to "Remote Desktop Services (RDP)", "tcpudp/3389" to "Remote Desktop Services (RDP)", "udp/5060" to "Internet telephony (SIP)",
    "tcp/5222" to "XMPP (Jabber)", "tcp/5432" to "PostgreSQL server", "tcp/5900" to "Remote desktop (VNC)",
)

// the descriptions of the services in the dictionary of the web interface, under the id of each service
internal val SERVICE_KEYS = SERVICES.keys.associateWith { "devices-list.ports.protocols.${it.replace("tcpudp/", "tcp/")}" }

// a service as the list of the web interface names it, "TCP/22 - Secure Shell (SSH)"
internal fun serviceLabel(id: String, word: Map<String, String>) =
    "${if (id.startsWith("tcpudp/")) "TCP+UDP" else id.substringBefore('/').uppercase()}/${id.substringAfter('/')} - ${word[id] ?: SERVICES[id].orEmpty()}"

private const val ALL = "all"
private const val SUBNET = "_OTHER_SUBNET_INPUT_"
private const val BY_IP = "_BY_IP_"
private const val THIS = "127.0.0.1"
private const val SINGLE = "single"
private const val RANGE = "range"
private val PLAIN = listOf("tcp", "udp", "tcpudp", "icmp", ALL)
private val PORTLESS = setOf("icmp", ALL)

// the port forwarding of the web interface. A rule takes what comes to an input, an interface or a subnet, by a protocol and
// its ports to an output: this router, a client, another address or an interface. The router knows a rule by an index it
// works out from the rule, which a change gives anew, and checks the rules in their order
internal class Forwarding(private val api: KeeneticCall, private val words: () -> Map<String, String>) : Node("ip/static") {
    override val commands = listOf("print", "add", "set", "remove", "move")

    override suspend fun arguments(): List<Argument> {
        val word = words()
        val (shown, known, schedules) = api.shows("interface", "rc/known", "rc/schedule")
        val faces = interfaces(shown, JsonObject(emptyMap())).filter { it["type"] != "Port" }.let(::titles)
        val hosts = named((known as? JsonObject)?.get("host") ?: JsonObject(emptyMap())).associate { (name, host) -> host["mac"].orEmpty().lowercase() to name }
        val protocols = linkedMapOf("tcp" to "TCP", "udp" to "UDP", "tcpudp" to "TCP/UDP", "icmp" to "ICMP", ALL to (word["TCP/UDP (all ports) and ICMP"] ?: "TCP/UDP (all ports) and ICMP")) +
            SERVICES.keys.associateWith { serviceLabel(it, word) }
        val ported = protocols.keys.filter { it !in PORTLESS }.map { mapOf("protocol" to it) }
        val outputs = linkedMapOf(THIS to (word["This Keenetic device"] ?: "This Keenetic device")) + hosts + (BY_IP to (word["Other client"] ?: "Other client")) + faces
        return listOf(
            Argument("disabled", ValueType.YesNo, default = "no"),
            Argument("comment", ValueType.Text(), optional = true),
            Argument("interface", ValueType.Enumeration(closed = true), faces.keys.toList() + SUBNET, labels = faces + (SUBNET to (word["Subnet address"] ?: "Subnet address"))),
            Argument("address", ValueType.Ip4, depends = listOf(mapOf("interface" to SUBNET))),
            Argument("mask", ValueType.Ip4, depends = listOf(mapOf("interface" to SUBNET))),
            Argument("to", ValueType.Enumeration(closed = true), outputs.keys.toList(), labels = outputs, default = THIS),
            Argument("to-address", ValueType.Ip4, depends = listOf(mapOf("to" to BY_IP))),
            Argument("protocol", ValueType.Enumeration(closed = true), protocols.keys.toList(), labels = protocols, default = "tcp"),
            Argument("mode", ValueType.Enumeration(closed = true), listOf(SINGLE, RANGE), labels = mapOf(SINGLE to (word["Single port"] ?: "Single port"), RANGE to (word["Port range"] ?: "Port range")), default = SINGLE, radio = true, depends = ported),
            Argument("port", ValueType.Integer(1, 65535), optional = true, depends = ported),
            Argument("end-port", ValueType.Integer(1, 65535), depends = listOf(mapOf("mode" to RANGE))),
            Argument("to-port", ValueType.Integer(1, 65535), optional = true, depends = listOf(mapOf("mode" to SINGLE))),
            Argument("schedule", ValueType.Enumeration(closed = true), listOf("") + named(schedules).map { it.first }, optional = true),
        )
    }

    override suspend fun read(): List<Item> {
        val (rules, known) = api.shows("rc/ip/static", "rc/known")
        val hosts = named((known as? JsonObject)?.get("host") ?: JsonObject(emptyMap())).associate { (name, host) -> host["mac"].orEmpty().lowercase() to name }
        return rows(rules).map { rule -> Item(rule["index"].orEmpty(), shownRule(rule.values, hosts)) }
    }

    // the rule as the window shows it: its input, its ports as the table of the web interface writes them, its output
    private fun shownRule(rule: Map<String, String>, hosts: Map<String, String>): Map<String, String> {
        val protocol = rule["protocol"].orEmpty().ifEmpty { ALL }
        val port = rule["port"].orEmpty()
        val end = rule["end-port"].orEmpty()
        val service = "$protocol/$port".takeIf { end.isEmpty() && rule["to-port"].isNullOrEmpty() && it in SERVICES }
        val label = if (protocol == "tcpudp") "TCP/UDP " else "${protocol.uppercase()}/"
        val output = rule["to-host"]?.ifEmpty { null } ?: rule["to-interface"]?.ifEmpty { null } ?: rule["to-address"].orEmpty()
        return linkedMapOf(
            "disabled" to "${rule["disable"] == "true"}",
            "comment" to rule["comment"].orEmpty(),
            "interface" to if (rule["address"].isNullOrEmpty()) rule["interface"].orEmpty() else SUBNET,
            "address" to rule["address"].orEmpty(),
            "mask" to rule["mask"].orEmpty(),
            "input" to rule["address"]?.ifEmpty { null }?.let { "$it/${rule["mask"].orEmpty()}" }.orEmpty().ifEmpty { rule["interface"].orEmpty() },
            "protocol" to (service ?: protocol),
            "mode" to if (end.isEmpty()) SINGLE else RANGE,
            "port" to port,
            "end-port" to end,
            "to-port" to rule["to-port"].orEmpty(),
            "ports" to when {
                protocol == ALL -> "All"
                protocol in PORTLESS -> protocol.uppercase()
                end.isNotEmpty() -> "$label$port - $end"
                !rule["to-port"].isNullOrEmpty() && rule["to-port"] != port -> "$label$port → ${rule["to-port"]}"
                else -> "$label$port"
            },
            "to" to when {
                !rule["to-host"].isNullOrEmpty() -> rule["to-host"].orEmpty().lowercase()
                !rule["to-interface"].isNullOrEmpty() -> rule["to-interface"].orEmpty()
                rule["to-address"] == THIS -> THIS
                else -> BY_IP
            },
            "to-address" to rule["to-address"].orEmpty(),
            "output" to (hosts[output.lowercase()] ?: output),
            "schedule" to rule["schedule"].orEmpty(),
        )
    }

    override suspend fun add(values: Map<String, String>): String {
        api.post(listOf(command(values, null)))
        return read().lastOrNull()?.id.orEmpty()
    }

    // a changed rule is another rule with another index: whether it is on goes to the old index first, which the new one keeps
    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val old = read().firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "port forwarding has no rule $id")
        val merged = old.values + changes.mapValues { it.value.orEmpty() }
        val switch = changes["disabled"]?.let { disabled ->
            val off = mapOf("index" to JsonPrimitive(old.id)) + if (disabled in ValueType.Flag.on) emptyMap() else mapOf("no" to JsonPrimitive(true))
            rule(JsonObject(mapOf("disable" to JsonObject(off))))
        }
        val rest = changes - "disabled"
        api.post(listOfNotNull(switch, command(merged, old.id).takeIf { rest.isNotEmpty() }))
    }

    override suspend fun remove(ids: List<String>) {
        api.post(ids.map { rule(JsonObject(mapOf("no" to JsonPrimitive(true), "index" to JsonPrimitive(it)))) })
    }

    // a rule takes the place of the rule it is put before, which moves down; put at the end, each takes the place of the last
    override suspend fun move(ids: List<String>, before: String?) {
        val order = read().map { it.id }
        var target = before?.takeIf { it in order } ?: order.lastOrNull() ?: return
        val moves = ids.map { id ->
            rule(JsonObject(mapOf("rule" to JsonObject(mapOf("index" to JsonPrimitive(id), "new-index" to JsonPrimitive(target)))))).also { if (before == null) target = id }
        }
        api.post(moves)
    }

    private fun rule(body: JsonObject) = JsonObject(mapOf("ip" to JsonObject(mapOf("static" to body))))

    // the rule as the web interface writes it: no protocol for all of them, no ports for ICMP, the port of a service when none is given
    private fun command(values: Map<String, String>, index: String?): JsonObject {
        val chosen = values["protocol"].orEmpty().ifEmpty { "tcp" }
        if (chosen !in PLAIN && chosen !in SERVICES) throw DeviceException(ErrorCategory.ArgumentValue, "protocol: $chosen is not one of the router")
        val protocol = chosen.substringBefore('/')
        val body = LinkedHashMap<String, JsonElement>()
        index?.let { body["index"] = JsonPrimitive(it) }
        if (index == null) body["disable"] = JsonPrimitive(values["disabled"] in ValueType.Flag.on)
        body["comment"] = JsonPrimitive(values["comment"].orEmpty())
        if (values["interface"] == SUBNET) {
            body["address"] = JsonPrimitive(values["address"].orEmpty().ifEmpty { throw DeviceException(ErrorCategory.ArgumentValue, "a rule for a subnet needs its address") })
            body["mask"] = JsonPrimitive(values["mask"].orEmpty().ifEmpty { throw DeviceException(ErrorCategory.ArgumentValue, "a rule for a subnet needs its mask") })
        } else {
            body["interface"] = JsonPrimitive(values["interface"].orEmpty().ifEmpty { throw DeviceException(ErrorCategory.ArgumentValue, "a rule needs its input") })
        }
        when (val to = values["to"].orEmpty().ifEmpty { THIS }) {
            THIS -> body["to-address"] = JsonPrimitive(THIS)
            BY_IP -> body["to-address"] = JsonPrimitive(values["to-address"].orEmpty().ifEmpty { throw DeviceException(ErrorCategory.ArgumentValue, "the output needs the address of the client") })
            else -> if (MAC.matches(to)) body["to-host"] = JsonPrimitive(to) else body["to-interface"] = JsonPrimitive(to)
        }
        if (chosen != ALL) body["protocol"] = JsonPrimitive(protocol)
        if (chosen !in PORTLESS) {
            val port = values["port"].orEmpty().ifEmpty { chosen.substringAfter('/', "") }.ifEmpty { throw DeviceException(ErrorCategory.ArgumentValue, "the rule needs the port it opens") }
            body["port"] = JsonPrimitive(port)
            if (values["mode"] == RANGE) {
                body["end-port"] = JsonPrimitive(values["end-port"].orEmpty().ifEmpty { throw DeviceException(ErrorCategory.ArgumentValue, "a range of ports needs its last port") })
            } else {
                values["to-port"]?.ifEmpty { null }?.takeIf { it != port }?.let { body["to-port"] = JsonPrimitive(it) }
            }
        }
        values["schedule"]?.ifEmpty { null }?.let { body["schedule"] = JsonPrimitive(it) }
        return rule(JsonObject(body))
    }
}
