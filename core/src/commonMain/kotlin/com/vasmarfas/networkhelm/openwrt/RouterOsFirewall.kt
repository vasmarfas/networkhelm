package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.duration
import com.vasmarfas.networkhelm.session.DeviceSession

internal class RouterOsFirewall(private val base: DeviceSession, private val rows: suspend (String) -> List<Item>) {

    val faces: Map<String, LayerFace> = mapOf(
        "interface/list" to LayerFace(
            NodeKind.List,
            listOf(ZONES),
            { listOf(Argument("name", ValueType.Text())) },
            { (zones) -> zones.map { Item(it.id, mapOf("name" to it["name"].orEmpty())) } },
            add = { values -> addZone(values["name"] ?: required("name")) },
            remove = { ids -> ids.forEach { removeZone(it) } },
        ),
        "interface/list/member" to LayerFace(
            NodeKind.List,
            listOf(ZONES, NETWORK_INTERFACE),
            { listOf(Argument("list", ValueType.Text()), Argument("interface", ValueType.Text())) },
            { (zones, interfaces) -> members(zones, interfaces) },
            add = { values -> addMember(values["list"] ?: required("list"), values["interface"] ?: required("interface")) },
            remove = { ids -> ids.forEach { removeMember(it) } },
        ),
        "ip/firewall/filter" to filter(IPV4),
        "ipv6/firewall/filter" to filter(IPV6),
        "ip/firewall/nat" to nat(IPV4),
        "ipv6/firewall/nat" to nat(IPV6),
        "ip/firewall/mangle" to mangle(IPV4),
        "ipv6/firewall/mangle" to mangle(IPV6),
        "ip/firewall/raw" to raw(IPV4),
        "ipv6/firewall/raw" to raw(IPV6),
        "ip/firewall/connection" to connections(IPV4),
        "ipv6/firewall/connection" to connections(IPV6),
    )

    private fun connections(family: String) = LayerFace(
        NodeKind.List,
        listOf(CONNECTIONS),
        {
            listOf(
                "src-address", "src-port", "dst-address", "dst-port", "protocol", "timeout", "tcp-state", "orig-packets", "repl-packets", "orig-bytes",
                "repl-bytes", "reply-src-address", "reply-src-port", "reply-dst-address", "reply-dst-port",
            ).map { Argument(it, ValueType.Text(), readOnly = true) }
        },
        { (connections) -> connections.filter { it["family"] == family }.map { Item(it.id, connectionValues(it)) } },
    )

    private fun connectionValues(connection: Item): Map<String, String> = buildMap {
        for (field in listOf("src-address", "src-port", "dst-address", "dst-port", "protocol", "reply-src-address", "reply-src-port", "reply-dst-address", "reply-dst-port")) {
            connection[field]?.let { put(field, it) }
        }
        connection["timeout"]?.toLongOrNull()?.let { put("timeout", duration(it)) }
        connection["tcp-state"]?.let { put("tcp-state", it.lowercase().replace('_', '-')) }
        for ((counter, field) in mapOf("packets" to "orig-packets", "reply-packets" to "repl-packets", "bytes" to "orig-bytes", "reply-bytes" to "repl-bytes")) {
            connection[counter]?.let { put(field, it) }
        }
    }

    private fun mangle(family: String) = LayerFace(
        NodeKind.List,
        listOf(RULES, RULESET),
        { mangleArguments() },
        { (rules, ruleset) ->
            rules.mapIndexedNotNull { index, rule ->
                Item(rule.id, mangleValues(rule) + counters(rule, index, ruleset)).takeIf { forFamily(rule, family) && rule["target"]?.lowercase() in MARKS }
            }
        },
        add = { values ->
            when (values["action"] ?: required("action")) {
                "mark-packet" -> if (values["new-packet-mark"] == null) required("new-packet-mark")
                "change-dscp" -> if (values["new-dscp"] == null) required("new-dscp")
            }
            base.add(path(RULES), (manglePlacement(values) + mangleOptions(values, null) + mapOf("family" to family)).present())
        },
        set = { id, changes ->
            val rule = staticRow(RULES, id)
            val moved = changes.keys.any { it in places }
            when (changes["action"]) {
                "mark-packet" -> if ("new-packet-mark" !in changes && mangleValues(rule)["new-packet-mark"] == null) required("new-packet-mark")
                "change-dscp" -> if ("new-dscp" !in changes && mangleValues(rule)["new-dscp"] == null) required("new-dscp")
            }
            base.set(path(RULES), rule.id, (if (moved) manglePlacement(mangleValues(rule) + changes) else emptyMap()) + mangleOptions(changes, rule))
        },
        remove = { ids -> base.remove(path(RULES), ids.map { staticRow(RULES, it).id }) },
        move = { ids, before -> base.move(path(RULES), ids.map { staticRow(RULES, it).id }, before) },
        optional = setOf(RULESET),
    )

    private suspend fun mangleArguments() = listOf(
        Argument("chain", ValueType.Enumeration(), listOf("prerouting", "input", "forward", "output", "postrouting")),
        Argument("action", ValueType.Enumeration(), listOf("mark-packet", "change-dscp")),
        Argument("new-packet-mark", ValueType.Text(), optional = true),
        Argument("new-dscp", ValueType.Text(), optional = true),
        Argument("protocol", ValueType.Text(), optional = true),
        Argument("src-address", ValueType.Text(), optional = true),
        Argument("dst-address", ValueType.Text(), optional = true),
        Argument("src-port", ValueType.PortList, optional = true),
        Argument("dst-port", ValueType.PortList, optional = true),
        Argument("in-interface-list", ValueType.Enumeration(), zoneNames(), optional = true),
        Argument("out-interface-list", ValueType.Enumeration(), zoneNames(), optional = true),
        Argument("comment", ValueType.Text(), optional = true),
        Argument("disabled", ValueType.YesNo),
    )

    private fun mangleValues(rule: Item): Map<String, String> = buildMap {
        putAll(filterValues(rule) - setOf("chain", "action", "in-interface", "out-interface"))
        val src = rule["src"]
        val dest = rule["dest"]
        put(
            "chain",
            when {
                src != null && dest != null -> if ((src == "*") == (dest == "*")) "forward" else if (src == "*") "postrouting" else "prerouting"
                src != null -> "input"
                else -> "output"
            },
        )
        put("action", if (rule["target"].equals("dscp", ignoreCase = true)) "change-dscp" else "mark-packet")
        (rule["set_mark"] ?: rule["set_xmark"])?.let { put("new-packet-mark", it) }
        rule["set_dscp"]?.let { put("new-dscp", it) }
    }

    private fun manglePlacement(values: Map<String, String?>): Map<String, String?> {
        val from = values["in-interface-list"]
        val to = values["out-interface-list"]
        fun named(list: String?, field: String) = list?.takeIf { it != "all" }?.let(::zone) ?: throw DeviceException(ErrorCategory.ArgumentValue, "$field must name a list of this chain, not all")
        return when (values["chain"] ?: required("chain")) {
            "prerouting" -> mapOf("src" to named(from, "in-interface-list"), "dest" to "*")
            "postrouting" -> mapOf("src" to "*", "dest" to named(to, "out-interface-list"))
            "forward" -> {
                if ((from == null || from == "all") != (to == null || to == "all")) throw DeviceException(ErrorCategory.ArgumentValue, "firewall4 forwards between two zones or between any and any: name both lists or neither")
                mapOf("src" to zone(from ?: "all"), "dest" to zone(to ?: "all"))
            }
            "input" -> mapOf("src" to zone(from ?: "all"), "dest" to null)
            "output" -> mapOf("src" to null, "dest" to to?.let(::zone))
            else -> throw DeviceException(ErrorCategory.ArgumentValue, "chain must be prerouting, input, forward, output or postrouting")
        }
    }

    private fun mangleOptions(values: Map<String, String?>, rule: Item?): Map<String, String?> = buildMap {
        putAll(ruleOptions(values - setOf("action", "chain", "in-interface-list", "out-interface-list")))
        if ("action" in values) {
            put("target", when (values["action"]) {
                "mark-packet" -> "MARK"
                "change-dscp" -> "DSCP"
                else -> throw DeviceException(ErrorCategory.ArgumentValue, "firewall4 marks packets and changes DSCP: the action is mark-packet or change-dscp")
            })
        }
        if ("new-packet-mark" in values) put(if (rule?.get("set_xmark") != null) "set_xmark" else "set_mark", values["new-packet-mark"])
        if ("new-dscp" in values) put("set_dscp", values["new-dscp"])
    }

    private fun raw(family: String) = LayerFace(
        NodeKind.List,
        listOf(RULES, RULESET),
        { rawArguments() },
        { (rules, ruleset) ->
            rules.mapIndexedNotNull { index, rule ->
                Item(rule.id, filterValues(rule) + ("chain" to "prerouting") + counters(rule, index, ruleset)).takeIf { forFamily(rule, family) && rule["target"]?.lowercase() == "notrack" }
            }
        },
        add = { values ->
            val from = values["in-interface-list"] ?: required("in-interface-list")
            checkRaw(values)
            base.add(path(RULES), (rawOptions(values) + mapOf("target" to "NOTRACK", "src" to zone(from), "family" to family)).present())
        },
        set = { id, changes ->
            checkRaw(changes)
            base.set(path(RULES), staticRow(RULES, id).id, rawOptions(changes) + listOfNotNull(changes["in-interface-list"]?.let { "src" to zone(it) }))
        },
        remove = { ids -> base.remove(path(RULES), ids.map { staticRow(RULES, it).id }) },
        move = { ids, before -> base.move(path(RULES), ids.map { staticRow(RULES, it).id }, before) },
        optional = setOf(RULESET),
    )

    private fun checkRaw(values: Map<String, String?>) {
        if (values["chain"].let { it != null && it != "prerouting" }) throw DeviceException(ErrorCategory.ArgumentValue, "firewall4 skips connection tracking before routing only: the chain is prerouting")
        if (values["action"].let { it != null && it != "notrack" }) throw DeviceException(ErrorCategory.ArgumentValue, "action must be notrack")
    }

    private suspend fun rawArguments() = listOf(
        Argument("chain", ValueType.Enumeration(), listOf("prerouting")),
        Argument("action", ValueType.Enumeration(), listOf("notrack")),
        Argument("protocol", ValueType.Text(), optional = true),
        Argument("src-address", ValueType.Text(), optional = true),
        Argument("dst-address", ValueType.Text(), optional = true),
        Argument("src-port", ValueType.PortList, optional = true),
        Argument("dst-port", ValueType.PortList, optional = true),
        Argument("in-interface-list", ValueType.Enumeration(), zoneNames()),
        Argument("comment", ValueType.Text(), optional = true),
        Argument("disabled", ValueType.YesNo),
    )

    private fun rawOptions(values: Map<String, String?>): Map<String, String?> = ruleOptions(values - setOf("action", "chain", "in-interface-list"))

    private fun nat(family: String) = LayerFace(
        NodeKind.List,
        listOf(REDIRECTS, NATS, ZONES),
        { natArguments() },
        { (redirects, nats, zones) ->
            val masq = if (family == IPV4) "masq" else "masq6"
            redirects.filter { forFamily(it, family) }.map { Item(it.id, redirectValues(it)) } + nats.filter { forFamily(it, family) }.map { Item(it.id, natValues(it)) } +
                zones.filter { it[masq] in Flag }.map { zone ->
                    Item("$DYNAMIC $masq ${zone.id}", mapOf("chain" to "srcnat", "action" to "masquerade", "out-interface-list" to zone["name"].orEmpty(), "dynamic" to "true"))
                }
        },
        add = { values ->
            when (values["chain"]) {
                "dstnat" -> base.add(path(REDIRECTS), (mapOf("target" to "DNAT", "family" to family) + redirectOptions(values)).present())
                "srcnat" -> base.add(path(NATS), (mapOf("family" to family) + natOptions(values)).present())
                else -> throw DeviceException(ErrorCategory.ArgumentValue, "chain must be dstnat or srcnat")
            }
        },
        set = { id, changes ->
            val redirect = rows(REDIRECTS).firstOrNull { it.id == id }
            if (redirect != null) base.set(path(REDIRECTS), redirect.id, redirectOptions(changes, redirect["target"] == "SNAT")) else base.set(path(NATS), staticRow(NATS, id).id, natOptions(changes))
        },
        remove = { ids ->
            val redirects = rows(REDIRECTS).map { it.id }.toSet()
            val (forwards, others) = ids.partition { it in redirects }
            if (forwards.isNotEmpty()) base.remove(path(REDIRECTS), forwards)
            if (others.isNotEmpty()) base.remove(path(NATS), others.map { staticRow(NATS, it).id })
        },
    )

    private fun filter(family: String) = LayerFace(
        NodeKind.List,
        listOf(RULES, FORWARDINGS, ZONES, RULESET),
        { filterArguments() },
        { (rules, forwardings, zones, ruleset) ->
            rules.mapIndexedNotNull { index, rule ->
                Item(rule.id, filterValues(rule) + counters(rule, index, ruleset)).takeIf { forFamily(rule, family) && rule["target"]?.lowercase() !in MANGLED }
            } + dynamicFilter(forwardings, zones)
        },
        add = { values -> base.add(path(RULES), (placement(values) + ruleOptions(values) + ("family" to family)).present()) },
        set = { id, changes ->
            val rule = staticRow(RULES, id)
            val moved = changes.keys.any { it in places }
            base.set(path(RULES), rule.id, (if (moved) placement(filterValues(rule) + changes) else emptyMap()) + ruleOptions(changes))
        },
        remove = { ids -> base.remove(path(RULES), ids.map { staticRow(RULES, it).id }) },
        move = { ids, before -> base.move(path(RULES), ids.map { staticRow(RULES, it).id }, before?.takeUnless { it.startsWith(DYNAMIC) }) },
        optional = setOf(RULESET),
    )

    private fun familyOf(rule: Item): String? {
        rule["family"]?.let { family -> return family.takeIf { it == IPV4 || it == IPV6 } }
        val addresses = listOf("src_ip", "dest_ip", "src_dip", "snat_ip").flatMap { uciLines(rule[it]) }
        return when {
            addresses.any { ':' in it } -> IPV6
            addresses.any { '.' in it } -> IPV4
            else -> null
        }
    }

    private fun forFamily(rule: Item, family: String) = familyOf(rule).let { it == null || it == family }

    private fun counters(rule: Item, index: Int, ruleset: List<Item>): Map<String, String> {
        val comment = "!fw4: " + (rule["name"] ?: "@rule[$index]")
        val made = ruleset.filter { it["comment"] == comment && it["packets"] != null }
        if (made.isEmpty()) return emptyMap()
        val packets = made.map { it["packets"]?.toLongOrNull() ?: return emptyMap() }
        val bytes = made.map { it["bytes"]?.toLongOrNull() ?: return emptyMap() }
        return mapOf("packets" to "${packets.sum()}", "bytes" to "${bytes.sum()}")
    }

    private suspend fun zoneNames() = listOf("all") + rows(ZONES).mapNotNull { it["name"] }

    private suspend fun filterArguments() = listOf(
        Argument("chain", ValueType.Enumeration(), listOf("input", "forward", "output")),
        Argument("action", ValueType.Enumeration(), listOf("accept", "drop", "reject")),
        Argument("protocol", ValueType.Text(), optional = true),
        Argument("src-address", ValueType.Text(), optional = true),
        Argument("dst-address", ValueType.Text(), optional = true),
        Argument("src-port", ValueType.PortList, optional = true),
        Argument("dst-port", ValueType.PortList, optional = true),
        Argument("in-interface-list", ValueType.Enumeration(), zoneNames(), optional = true),
        Argument("out-interface-list", ValueType.Enumeration(), zoneNames(), optional = true),
        Argument("in-interface", ValueType.Text(), optional = true),
        Argument("out-interface", ValueType.Text(), optional = true),
        Argument("comment", ValueType.Text(), optional = true),
        Argument("disabled", ValueType.YesNo),
    )

    private suspend fun natArguments() = listOf(
        Argument("chain", ValueType.Enumeration(), listOf("srcnat", "dstnat")),
        Argument("action", ValueType.Enumeration(), listOf("dst-nat", "src-nat", "masquerade", "accept")),
        Argument("protocol", ValueType.Text(), optional = true),
        Argument("src-address", ValueType.Text(), optional = true),
        Argument("dst-address", ValueType.Text(), optional = true),
        Argument("dst-port", ValueType.PortList, optional = true),
        Argument("in-interface-list", ValueType.Enumeration(), zoneNames(), optional = true),
        Argument("out-interface-list", ValueType.Enumeration(), zoneNames(), optional = true),
        Argument("to-addresses", ValueType.Text(), optional = true),
        Argument("to-ports", ValueType.PortList, optional = true),
        Argument("comment", ValueType.Text(), optional = true),
        Argument("disabled", ValueType.YesNo),
    )

    private fun members(zones: List<Item>, interfaces: List<Item>): List<Item> = zones.flatMap { zone ->
        val list = zone["name"].orEmpty()
        val networks = uciLines(zone["network"]).map { network ->
            val device = interfaces.firstOrNull { sectionName(it) == network }?.let(::deviceOf)?.takeIf { it.isNotEmpty() && !it.startsWith('@') }
            Item("${zone.id} network $network", mapOf("list" to list, "interface" to (device ?: network)))
        }
        networks + uciLines(zone["device"]).map { device -> Item("${zone.id} device $device", mapOf("list" to list, "interface" to device)) }
    }

    private suspend fun addMember(list: String, device: String): String {
        val zone = rows(ZONES).firstOrNull { it["name"] == list } ?: throw DeviceException(ErrorCategory.ArgumentValue, "no such interface list $list")
        val networks = rows(NETWORK_INTERFACE).filter { deviceOf(it) == device }.map(::sectionName)
        if (networks.isNotEmpty()) {
            base.set(path(ZONES), zone.id, mapOf("network" to (uciLines(zone["network"]) + networks).distinct().joinToString(LIST)))
            return "${zone.id} network ${networks.first()}"
        }
        base.set(path(ZONES), zone.id, mapOf("device" to (uciLines(zone["device"]) + device).distinct().joinToString(LIST)))
        return "${zone.id} device $device"
    }

    private suspend fun removeMember(id: String) {
        val (zoneId, option, name) = id.split(' ', limit = 3).takeIf { it.size == 3 } ?: throw DeviceException(ErrorCategory.MissingItem, "no such member $id")
        val zone = rows(ZONES).firstOrNull { it.id == zoneId } ?: throw DeviceException(ErrorCategory.MissingItem, "no such member $id")
        base.set(path(ZONES), zone.id, mapOf(option to (uciLines(zone[option]) - name).joinToString(LIST).ifEmpty { null }))
    }

    // a new zone takes the policies of the defaults, as LuCI fills them in; one left without a policy has that of the defaults anyway
    private suspend fun addZone(name: String): String {
        val defaults = rows(DEFAULTS).firstOrNull()
        return base.add(path(ZONES), mapOf("name" to name) + listOf("input", "output", "forward").mapNotNull { policy -> defaults?.get(policy)?.let { policy to it } })
    }

    private suspend fun removeZone(id: String) {
        val zone = rows(ZONES).firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "no such interface list $id")
        val name = zone["name"]
        val users = listOf(RULES, REDIRECTS, NATS, FORWARDINGS).flatMap { rows(it) }.filter { it["src"] == name || it["dest"] == name }
        if (users.isNotEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "interface list $name is in use")
        base.remove(path(ZONES), listOf(id))
    }

    private fun filterValues(rule: Item): Map<String, String> {
        val src = rule["src"]
        val dest = rule["dest"]
        return buildMap {
            put("chain", chainOf(src, dest))
            rule["target"]?.let { put("action", it.lowercase()) }
            src?.let { put("in-interface-list", list(it)) }
            dest?.let { put("out-interface-list", list(it)) }
            rule["device"]?.let { put(if (rule["direction"] == "out") "out-interface" else "in-interface", it) }
            rule["proto"]?.let { put("protocol", it) }
            rule["src_ip"]?.let { put("src-address", uciLines(it).joinToString(",")) }
            rule["dest_ip"]?.let { put("dst-address", uciLines(it).joinToString(",")) }
            rule["src_port"]?.let { put("src-port", ports(it)) }
            rule["dest_port"]?.let { put("dst-port", ports(it)) }
            rule["name"]?.let { put("comment", it) }
            put("disabled", "${rule["enabled"] == "0"}")
        }
    }

    private fun dynamicFilter(forwardings: List<Item>, zones: List<Item>): List<Item> {
        val forwards = forwardings.map { forwarding ->
            Item(
                "$DYNAMIC forward ${forwarding.id}",
                mapOf("chain" to "forward", "action" to "accept", "in-interface-list" to forwarding["src"].orEmpty(), "out-interface-list" to forwarding["dest"].orEmpty(), "dynamic" to "true"),
            )
        }
        val policies = listOf("input", "forward", "output").flatMap { chain ->
            zones.mapNotNull { zone ->
                val policy = zone[chain] ?: return@mapNotNull null
                val side = if (chain == "output") "out-interface-list" else "in-interface-list"
                Item("$DYNAMIC $chain ${zone.id}", mapOf("chain" to chain, "action" to policy.lowercase(), side to zone["name"].orEmpty(), "dynamic" to "true"))
            }
        }
        return forwards + policies
    }

    private fun placement(values: Map<String, String?>): Map<String, String?> {
        val chain = values["chain"] ?: "input"
        val from = values["in-interface-list"]?.let(::zone)
        val to = values["out-interface-list"]?.let(::zone)
        val device = values["in-interface"]?.let { it to "in" } ?: values["out-interface"]?.let { it to "out" }
        return when (chain) {
            "input" -> mapOf("src" to (from ?: "*"), "dest" to null)
            "forward" -> mapOf("src" to (from ?: "*"), "dest" to (to ?: "*"))
            "output" -> mapOf("src" to null, "dest" to to)
            else -> throw DeviceException(ErrorCategory.ArgumentValue, "chain must be input, forward or output")
        } + mapOf("device" to device?.first, "direction" to device?.second)
    }

    private fun ruleOptions(values: Map<String, String?>): Map<String, String?> = buildMap {
        if ("action" in values) put("target", values["action"]?.uppercase())
        if ("protocol" in values) put("proto", values["protocol"])
        if ("src-address" in values) put("src_ip", values["src-address"]?.split(',')?.joinToString(LIST))
        if ("dst-address" in values) put("dest_ip", values["dst-address"]?.split(',')?.joinToString(LIST))
        if ("src-port" in values) put("src_port", values["src-port"]?.let(::uciPorts))
        if ("dst-port" in values) put("dest_port", values["dst-port"]?.let(::uciPorts))
        if ("comment" in values) put("name", values["comment"])
        if ("disabled" in values) put("enabled", if (values["disabled"] in Flag) "0" else null)
    }

    private fun redirectValues(redirect: Item): Map<String, String> = buildMap {
        val snat = redirect["target"] == "SNAT"
        put("chain", if (snat) "srcnat" else "dstnat")
        put("action", if (snat) "src-nat" else "dst-nat")
        if (snat) redirect["dest"]?.let { put("out-interface-list", list(it)) } else redirect["src"]?.let { put("in-interface-list", list(it)) }
        redirect["proto"]?.let { put("protocol", it) }
        redirect["src_ip"]?.let { put("src-address", it) }
        redirect["src_dip"]?.let { put(if (snat) "to-addresses" else "dst-address", it) }
        redirect["src_dport"]?.let { put(if (snat) "to-ports" else "dst-port", ports(it)) }
        redirect["dest_ip"]?.let { put(if (snat) "dst-address" else "to-addresses", it) }
        redirect["dest_port"]?.let { put(if (snat) "dst-port" else "to-ports", ports(it)) }
        redirect["name"]?.let { put("comment", it) }
        put("disabled", "${redirect["enabled"] == "0"}")
    }

    // a redirect of fw3 with the target SNAT matches the destination and rewrites the source, the other way round from DNAT
    private fun redirectOptions(values: Map<String, String?>, snat: Boolean = false): Map<String, String?> = buildMap {
        val (field, option) = if (snat) "out-interface-list" to "dest" else "in-interface-list" to "src"
        if (field in values) put(option, values[field]?.let(::zone))
        if ("protocol" in values) put("proto", values["protocol"])
        if ("src-address" in values) put("src_ip", values["src-address"])
        if ("dst-address" in values) put(if (snat) "dest_ip" else "src_dip", values["dst-address"])
        if ("dst-port" in values) put(if (snat) "dest_port" else "src_dport", values["dst-port"]?.let(::uciPorts))
        if ("to-addresses" in values) put(if (snat) "src_dip" else "dest_ip", values["to-addresses"])
        if ("to-ports" in values) put(if (snat) "src_dport" else "dest_port", values["to-ports"]?.let(::uciPorts))
        if ("comment" in values) put("name", values["comment"])
        if ("disabled" in values) put("enabled", if (values["disabled"] in Flag) "0" else null)
    }

    private fun natValues(nat: Item): Map<String, String> = buildMap {
        put("chain", "srcnat")
        nat["target"]?.let { put("action", if (it == "SNAT") "src-nat" else it.lowercase()) }
        nat["src"]?.let { put("out-interface-list", list(it)) }
        nat["proto"]?.let { put("protocol", it) }
        nat["src_ip"]?.let { put("src-address", it) }
        nat["dest_ip"]?.let { put("dst-address", it) }
        nat["dest_port"]?.let { put("dst-port", ports(it)) }
        nat["snat_ip"]?.let { put("to-addresses", it) }
        nat["snat_port"]?.let { put("to-ports", ports(it)) }
        nat["name"]?.let { put("comment", it) }
        put("disabled", "${nat["enabled"] == "0"}")
    }

    private fun natOptions(values: Map<String, String?>): Map<String, String?> = buildMap {
        if ("action" in values) put("target", if (values["action"] == "src-nat") "SNAT" else values["action"]?.uppercase())
        if ("out-interface-list" in values) put("src", values["out-interface-list"]?.let(::zone))
        if ("protocol" in values) put("proto", values["protocol"])
        if ("src-address" in values) put("src_ip", values["src-address"])
        if ("dst-address" in values) put("dest_ip", values["dst-address"])
        if ("dst-port" in values) put("dest_port", values["dst-port"]?.let(::uciPorts))
        if ("to-addresses" in values) put("snat_ip", values["to-addresses"])
        if ("to-ports" in values) put("snat_port", values["to-ports"]?.let(::uciPorts))
        if ("comment" in values) put("name", values["comment"])
        if ("disabled" in values) put("enabled", if (values["disabled"] in Flag) "0" else null)
    }

    private suspend fun staticRow(path: String, id: String?): Item =
        rows(path).firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.ArgumentValue, "cannot change dynamic rule")

    private companion object {
        const val ZONES = "firewall/zone"
        const val DEFAULTS = "firewall/defaults"
        const val FORWARDINGS = "firewall/forwarding"
        const val RULES = "firewall/rule"
        const val RULESET = "status/firewall"
        const val CONNECTIONS = "status/connections"
        const val REDIRECTS = "firewall/redirect"
        const val NATS = "firewall/nat"
        const val NETWORK_INTERFACE = "network/interface"
        const val DYNAMIC = "dynamic"
        const val IPV4 = "ipv4"
        const val IPV6 = "ipv6"

        val MARKS = setOf("mark", "dscp")
        val MANGLED = MARKS + "notrack"
        val Flag = ValueType.Flag.on
        val places = setOf("chain", "in-interface-list", "out-interface-list", "in-interface", "out-interface")

        fun path(text: String) = MenuPath.parse(text)

        fun chainOf(src: String?, dest: String?) = when {
            src != null && dest != null -> "forward"
            src != null -> "input"
            else -> "output"
        }

        fun list(zone: String) = if (zone == "*") "all" else zone

        fun zone(list: String) = if (list == "all") "*" else list
    }
}

internal fun ports(value: String) = value.trim().split(Regex("\\s+")).joinToString(",")

internal fun uciPorts(value: String) = value.split(',').joinToString(" ") { it.trim() }
