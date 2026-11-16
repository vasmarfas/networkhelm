package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

// the firewalls of a Linux host, each where its tool is: ufw and firewalld through their own commands, nftables and
// iptables rule by rule. Before a change the host keeps the rules it had and puts them back by itself a minute later
// unless the session still answers after the change, and at once when a new login no longer gets in
internal class LinuxFirewall(
    private val ssh: SshSession,
    private val root: Root,
    private val tools: suspend () -> Set<String>,
    private val login: (suspend () -> Unit)? = null,
) {
    // whether a new login got in before the first change: a host that refuses the user a second session, or asks it for more than
    // the session gave, is checked through the session alone
    private var admitted: Boolean? = null

    val tables: Map<String, HostTable> = mapOf(
        "firewall" to HostTable(OVERVIEW, { listOf(Item("", overview())) }, settings = true, slow = true, present = { tools().any { it in KNOWN } }),
        "firewall/ufw" to HostTable(
            UFW, ::ufwRules, slow = true, arguments = { UFW_ARGUMENTS }, add = ::addUfw, set = ::changeUfwRule, remove = ::removeUfw, move = ::moveUfw,
            present = { "ufw" in tools() },
        ),
        "firewall/ufw/settings" to HostTable(
            UFW_SETTINGS, { listOf(Item("", ufwSettings())) }, settings = true, slow = true, arguments = { UFW_SETTINGS_ARGUMENTS },
            set = { _, changes -> setUfw(changes) }, present = { "ufw" in tools() },
        ),
        "firewall/firewalld" to HostTable(
            FIREWALLD, ::firewalldRules, slow = true, arguments = ::firewalldArguments, add = ::addFirewalld, set = ::changeFirewalld, remove = ::removeFirewalld,
            commands = listOf("reload", "runtime-to-permanent"), run = ::firewalldCommand, present = { "firewall-cmd" in tools() },
        ),
        "firewall/firewalld/zone" to HostTable(
            ZONE, ::zoneRows, slow = true, arguments = { ZONE_ARGUMENTS }, set = ::setZone, commands = listOf("set-default"), run = ::setDefaultZone,
            present = { "firewall-cmd" in tools() },
        ),
        "firewall/nftables" to HostTable(
            NFT_RULE, { nft().second }, slow = true, arguments = { NFT_ARGUMENTS }, add = ::addNft, set = ::changeNft, remove = ::removeNft, move = ::moveNft,
            commands = listOf("save"), run = { _, _ -> saveNft() }, present = { "nft" in tools() },
        ),
        "firewall/nftables/chain" to HostTable(NFT_CHAIN, { nft().first }, slow = true, present = { "nft" in tools() }),
        "firewall/iptables" to rules(Family.Four),
        "firewall/iptables/chain" to chains(Family.Four),
        "firewall/ip6tables" to rules(Family.Six),
        "firewall/ip6tables/chain" to chains(Family.Six),
    )

    private enum class Family(val tool: String, val v4: Boolean) { Four("iptables", true), Six("ip6tables", false) }

    private suspend fun overview(): Map<String, String> {
        val present = tools()
        val ufw = if ("ufw" in present) ufwStatus() else ""
        val firewalld = if ("firewall-cmd" in present) root.exec("firewall-cmd --state").output.trim() else ""
        val nft = if ("nft" in present) "${nft().second.size}" else ""
        val four = if ("iptables-save" in present) "${iptables(Family.Four).second.size}" else ""
        val six = if ("ip6tables-save" in present) "${iptables(Family.Six).second.size}" else ""
        val backend = if ("iptables-save" in present) root.exec("iptables -V").output.substringAfter('(', "").substringBefore(')') else ""
        val manager = when {
            ufw == "active" -> "ufw"
            firewalld == "running" -> "firewalld"
            (four.toIntOrNull() ?: 0) + (six.toIntOrNull() ?: 0) > 0 -> "iptables"
            (nft.toIntOrNull() ?: 0) > 0 -> "nftables"
            else -> "none"
        }
        return linkedMapOf("manager" to manager, "ufw" to ufw, "firewalld" to firewalld, "nftables-rules" to nft, "iptables-rules" to four, "ip6tables-rules" to six, "iptables-backend" to backend)
    }

    private suspend fun ufwStatus() = root.output("ufw status").lineSequence().first().substringAfter("Status:").trim()

    // the rule files number the rules as ufw delete does, the IPv4 ones first, whether ufw is on or off
    private suspend fun ufwRules(): List<Item> = root.output("cat /etc/ufw/user.rules; echo $SECTION; cat /etc/ufw/user6.rules").split("$SECTION\n").let { parseUfwTuples(it[0], it.getOrElse(1) { "" }) }

    private suspend fun ufwSettings(): Map<String, String> {
        val (status, defaults, conf) = root.output("ufw status; echo $SECTION; cat /etc/default/ufw; echo $SECTION; cat /etc/ufw/ufw.conf").split("$SECTION\n") + List(3) { "" }
        fun value(text: String, key: String) = text.lines().firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim('"', ' ').orEmpty()
        fun policy(key: String) = POLICIES[value(defaults, key)].orEmpty()
        return linkedMapOf(
            "enabled" to "${status.lineSequence().first().substringAfter("Status:").trim() == "active"}",
            "logging" to value(conf, "LOGLEVEL").ifEmpty { if (value(conf, "LOGLEVEL").isEmpty() && value(conf, "ENABLED").isNotEmpty()) "low" else "" },
            "default-incoming" to policy("DEFAULT_INPUT_POLICY"),
            "default-outgoing" to policy("DEFAULT_OUTPUT_POLICY"),
            "default-routed" to policy("DEFAULT_FORWARD_POLICY"),
        )
    }

    private suspend fun addUfw(values: Map<String, String>): String {
        guarded(UFW_BACKUP) { root.output(ufwRule(values, values["position"].orEmpty())) }
        return ""
    }

    private fun ufwRule(values: Map<String, String>, position: String): String {
        val action = values["action"].orEmpty().ifEmpty { "allow" }.also { require(it in listOf("allow", "deny", "reject", "limit"), "action", it) }
        val direction = values["direction"].orEmpty().ifEmpty { "in" }.also { require(it in listOf("in", "out"), "direction", it) }
        val protocol = values["protocol"].orEmpty().ifEmpty { "any" }.also { require(it in listOf("any", "tcp", "udp"), "protocol", it) }
        val from = values["from"].orEmpty().ifEmpty { "any" }.also { require(it == "any" || ADDRESS.matches(it), "from", it) }
        val to = values["to"].orEmpty().ifEmpty { "any" }.also { require(it == "any" || ADDRESS.matches(it), "to", it) }
        val port = values["to-port"].orEmpty().also { require(it.isEmpty() || PORTS.matches(it), "to-port", it) }
        val source = values["from-port"].orEmpty().also { require(it.isEmpty() || PORTS.matches(it), "from-port", it) }
        val face = values["interface"].orEmpty().also { require(it.isEmpty() || NAME.matches(it), "interface", it) }
        require(position.isEmpty() || position.toIntOrNull() != null, "position", position)
        return buildList {
            add("ufw")
            if (position.isNotEmpty()) add("insert $position")
            add(action)
            add(direction)
            if (face.isNotEmpty()) add("on $face")
            if (protocol != "any") add("proto $protocol")
            add("from $from")
            if (source.isNotEmpty()) add("port $source")
            add("to $to")
            if (port.isNotEmpty()) add("port $port")
            values["comment"]?.ifEmpty { null }?.let { add("comment ${quote(it)}") }
        }.joinToString(" ")
    }

    private suspend fun removeUfw(ids: List<String>) {
        val numbers = ids.map { it.toIntOrNull() ?: throw DeviceException(ErrorCategory.MissingItem, "ufw has no rule $it") }.sortedDescending()
        guarded(UFW_BACKUP) { root.output(numbers.joinToString(" && ") { "ufw --force delete $it" }) }
    }

    private suspend fun changeUfwRule(id: String?, changes: Map<String, String?>) {
        val rules = ufwRules()
        val old = rules.firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "ufw has no rule $id")
        val was = ufwValues(old)
        val values = was + changes.filterKeys { it in was }.mapValues { it.value.orEmpty() }
        if (values != was) placeUfw(rules, listOf(old), listOf(values), old.id.toInt())
    }

    private suspend fun moveUfw(ids: List<String>, before: String?) {
        val rules = ufwRules()
        val moved = ids.map { id -> rules.firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "ufw has no rule $id") }
        val place = before?.let { id -> rules.firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "ufw has no rule $id") }
        placeUfw(rules, moved, moved.map(::ufwValues), place?.id?.toInt())
    }

    // the old rules go with their IPv6 twins, which ufw made from the same command, and the new ones come in at the place of the
    // list the old ones left, or at its end
    private suspend fun placeUfw(rules: List<Item>, gone: List<Item>, added: List<Map<String, String>>, place: Int?) {
        val numbers = gone.flatMap { listOfNotNull(it, twin(it, rules)) }.map { it.id.toInt() }.distinct().sortedDescending()
        val at = place?.let { it - numbers.count { number -> number < it } }?.takeIf { it in 1..rules.size - numbers.size }
        val steps = numbers.map { "ufw --force delete $it" } + added.mapIndexed { index, values -> ufwRule(values, at?.let { "${it + index}" }.orEmpty()) }
        guarded(UFW_BACKUP) { root.output(steps.joinToString(" && ")) }
    }

    private fun ufwValues(rule: Item): Map<String, String> {
        if (rule["direction"] !in listOf("in", "out")) throw DeviceException(ErrorCategory.ArgumentValue, "a ufw rule of routing is removed and added again")
        if (listOf("to-port", "from-port").any { rule[it].orEmpty().let { port -> port.isNotEmpty() && !PORTS.matches(port) } }) {
            throw DeviceException(ErrorCategory.ArgumentValue, "a ufw rule of an application profile is removed and added again")
        }
        return listOf("action", "direction", "protocol", "from", "from-port", "to", "to-port", "interface", "comment").associateWith { rule[it].orEmpty() }
    }

    // the IPv6 rule ufw adds beside an IPv4 one for any address
    private fun twin(rule: Item, rules: List<Item>): Item? {
        if (rule["v6"] == "true" || rule["from"].orEmpty().isNotEmpty() || rule["to"].orEmpty().isNotEmpty()) return null
        val same = listOf("action", "direction", "protocol", "from", "from-port", "to", "to-port", "interface", "comment")
        return rules.firstOrNull { other -> other["v6"] == "true" && same.all { other[it] == rule[it] } }
    }

    private suspend fun setUfw(changes: Map<String, String?>) {
        val steps = buildList {
            for ((key, chain) in listOf("default-incoming" to "incoming", "default-outgoing" to "outgoing", "default-routed" to "routed")) {
                changes[key]?.ifEmpty { null }?.let { require(it in listOf("allow", "deny", "reject"), key, it); add("ufw default $it $chain") }
            }
            changes["logging"]?.ifEmpty { null }?.let { require(it in listOf("off", "low", "medium", "high", "full"), "logging", it); add("ufw logging $it") }
            changes["enabled"]?.let { if (it in ValueType.Flag.on) add("ufw --force enable") else add("ufw disable") }
        }
        if (steps.isEmpty()) return
        if (changes["enabled"] in ValueType.Flag.on) sshAllowedByUfw()
        guarded(UFW_BACKUP) { root.output(steps.joinToString(" && ")) }
    }

    // ufw on keeps the session it runs in and drops a new one, so it does not go on without a rule for the port of the session
    private suspend fun sshAllowedByUfw() {
        val port = ssh.exec("echo \$SSH_CONNECTION").output.trim().split(' ').getOrNull(3) ?: return
        val added = root.output("ufw show added").lines()
        val allowed = added.any { line ->
            val words = line.split(' ')
            ("allow" in words || "limit" in words) && "out" !in words &&
                (words.any { it == port || it == "$port/tcp" } || port == "22" && words.any { it.equals("OpenSSH", ignoreCase = true) || it == "ssh" })
        }
        if (!allowed) throw DeviceException(ErrorCategory.ArgumentValue, "ufw would let this session be and keep the next login out: allow $port/tcp first")
    }

    private suspend fun zones(): List<FirewalldZone> = parseFirewalldZones(root.output("firewall-cmd --list-all-zones"))

    private suspend fun firewalldRules(): List<Item> = zones().flatMap { zone ->
        zone.entries.map { (kind, value) ->
            Item("${zone.name}|$kind|$value", linkedMapOf("zone" to zone.name, "kind" to kind, "value" to value, "active" to "${zone.active}"))
        }
    }

    private suspend fun zoneRows(): List<Item> = zones().map { zone ->
        Item(
            zone.name,
            linkedMapOf(
                "name" to zone.name, "default" to "${zone.default}", "active" to "${zone.active}", "target" to zone.values["target"].orEmpty(),
                "interfaces" to zone.values["interfaces"].orEmpty(), "sources" to zone.values["sources"].orEmpty(), "services" to zone.values["services"].orEmpty(),
                "ports" to zone.values["ports"].orEmpty(), "masquerade" to "${zone.values["masquerade"] == "yes"}", "forward" to "${zone.values["forward"] == "yes"}",
            ),
        )
    }

    private suspend fun firewalldArguments(): List<Argument> {
        val zones = zones()
        return listOf(
            Argument("zone", ValueType.Enumeration(closed = true), zones.map { it.name }, default = zones.firstOrNull { it.default }?.name),
            Argument("kind", ValueType.Enumeration(closed = true), KINDS.values.toList(), default = "service"),
            Argument("value", ValueType.Text(), description = "ssh, 8080/tcp, 192.0.2.0/24, eth0 or a rich rule"),
            Argument("active", ValueType.Text(), readOnly = true),
        )
    }

    private suspend fun addFirewalld(values: Map<String, String>): String {
        val zone = values["zone"].orEmpty().also { require(NAME.matches(it), "zone", it) }
        val kind = values["kind"].orEmpty().also { require(it in KINDS.values, "kind", it) }
        val value = values["value"].orEmpty().also { require(it.isNotEmpty() && '\n' !in it, "value", it) }
        val option = quote(if (kind == "interface") "--change-interface=$value" else "--add-$kind=$value")
        guarded(FIREWALLD_BACKUP) { root.output("firewall-cmd --zone=$zone $option && firewall-cmd --permanent --zone=$zone $option") }
        return "$zone|$kind|$value"
    }

    private suspend fun removeFirewalld(ids: List<String>) {
        val steps = ids.map { id ->
            val (zone, kind, value) = id.split('|', limit = 3).takeIf { it.size == 3 } ?: throw DeviceException(ErrorCategory.MissingItem, "firewalld has no $id")
            require(NAME.matches(zone) && kind in KINDS.values, "rule", id)
            val option = quote("--remove-$kind=$value")
            // what the runtime has the permanent configuration may lack
            "firewall-cmd --zone=$zone $option && { firewall-cmd --permanent --zone=$zone $option >/dev/null 2>&1; true; }"
        }
        guarded(FIREWALLD_BACKUP) { root.output(steps.joinToString(" && ")) }
    }

    // firewalld keeps no order: the new entry goes in, then the old one out
    private suspend fun changeFirewalld(id: String?, changes: Map<String, String?>) {
        val (zone, kind, value) = id?.split('|', limit = 3)?.takeIf { it.size == 3 } ?: throw DeviceException(ErrorCategory.MissingItem, "firewalld has no $id")
        val was = mapOf("zone" to zone, "kind" to kind, "value" to value)
        val values = was.mapValues { (name, old) -> changes[name]?.ifEmpty { null } ?: old }
        if (values == was) return
        addFirewalld(values)
        removeFirewalld(listOf(id))
    }

    private suspend fun firewalldCommand(command: String, arguments: Map<String, String>): List<Item> {
        when (command) {
            "reload" -> guarded(FIREWALLD_BACKUP) { root.output("firewall-cmd --reload") }
            "runtime-to-permanent" -> root.output("firewall-cmd --runtime-to-permanent")
            else -> throw DeviceException(ErrorCategory.MissingItem, "firewalld has no command $command")
        }
        return emptyList()
    }

    private suspend fun setZone(id: String?, changes: Map<String, String?>) {
        val zone = id?.takeIf { NAME.matches(it) } ?: throw DeviceException(ErrorCategory.MissingItem, "firewalld has no zone $id")
        val steps = buildList {
            changes["masquerade"]?.let {
                val option = if (it in ValueType.Flag.on) "--add-masquerade" else "--remove-masquerade"
                add("firewall-cmd --zone=$zone $option && firewall-cmd --permanent --zone=$zone $option")
            }
            // the target is a setting of the permanent configuration only, and comes into force with a reload
            changes["target"]?.ifEmpty { null }?.let { require(it in TARGETS, "target", it); add("firewall-cmd --permanent --zone=$zone --set-target=${quote(it)} && firewall-cmd --reload") }
        }
        if (steps.isNotEmpty()) guarded(FIREWALLD_BACKUP) { root.output(steps.joinToString(" && ")) }
    }

    private suspend fun setDefaultZone(command: String, arguments: Map<String, String>): List<Item> {
        val zone = arguments["number"].orEmpty().also { require(NAME.matches(it), "zone", it) }
        guarded(FIREWALLD_BACKUP) { root.output("firewall-cmd --set-default-zone=$zone") }
        return emptyList()
    }

    private suspend fun nft() = parseNftRuleset(root.output("nft -a list ruleset"))

    private suspend fun addNft(values: Map<String, String>): String {
        val family = values["family"].orEmpty().ifEmpty { "inet" }.also { require(it in NFT_FAMILIES, "family", it) }
        val table = values["table"].orEmpty().also { require(NAME.matches(it), "table", it) }
        val chain = values["chain"].orEmpty().also { require(NAME.matches(it), "chain", it) }
        val rule = values["rule"].orEmpty().trim().also { require(it.isNotEmpty() && '\n' !in it, "rule", it) }
        guarded(NFT_BACKUP) { root.output("nft add rule $family $table $chain ${quote(rule)}") }
        return ""
    }

    private suspend fun removeNft(ids: List<String>) {
        val steps = ids.map { id -> nftRule(id).let { (family, table, chain, handle) -> "nft delete rule $family $table $chain handle $handle" } }
        guarded(NFT_BACKUP) { root.output(steps.joinToString(" && ")) }
    }

    private fun nftRule(id: String): List<String> {
        val parts = id.split('/')
        if (parts.size != 4 || parts[0] !in NFT_FAMILIES || !NAME.matches(parts[1]) || !NAME.matches(parts[2]) || parts[3].toIntOrNull() == null) {
            throw DeviceException(ErrorCategory.MissingItem, "nftables has no rule $id")
        }
        return parts
    }

    // the comment is a field of the window apart from the rule, and goes back into it
    private fun nftText(rule: String, comment: String) = rule.trim() + if (comment.isEmpty() || " comment " in " $rule ") "" else " comment \"${comment.replace("\"", "")}\""

    private suspend fun changeNft(id: String?, changes: Map<String, String?>) {
        val (family, table, chain, handle) = nftRule(id.orEmpty())
        val old = nft().second.firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "nftables has no rule $id")
        val rule = changes["rule"] ?: old["rule"].orEmpty()
        val comment = changes["comment"] ?: old["comment"].orEmpty()
        if (rule == old["rule"] && comment == old["comment"]) return
        require(rule.isNotBlank() && '\n' !in rule, "rule", rule)
        guarded(NFT_BACKUP) { root.output("nft replace rule $family $table $chain handle $handle ${quote(nftText(rule, comment))}") }
    }

    // a rule of nftables cannot move: it goes and comes back before the rule it is put above, in the same chain
    private suspend fun moveNft(ids: List<String>, before: String?) {
        val rules = nft().second
        val moved = ids.map { id -> rules.firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "nftables has no rule $id") }
        val (family, table, chain) = nftRule(moved.first().id)
        val same = rules.filter { it.id.startsWith("$family/$table/$chain/") }
        if (moved.any { it !in same }) throw DeviceException(ErrorCategory.ArgumentValue, "a rule moves within its chain")
        val anchor = before?.let { id -> same.dropWhile { it.id != id }.firstOrNull { it !in moved } ?: throw DeviceException(ErrorCategory.ArgumentValue, "$id is no rule of the chain") }
        val steps = moved.map { "nft delete rule $family $table $chain handle ${it["handle"]}" } + moved.map { rule ->
            val text = quote(nftText(rule["rule"].orEmpty(), rule["comment"].orEmpty()))
            if (anchor == null) "nft add rule $family $table $chain $text" else "nft insert rule $family $table $chain position ${anchor["handle"]} $text"
        }
        guarded(NFT_BACKUP) { root.output(steps.joinToString(" && ")) }
    }

    // the file the nftables service loads at boot; ufw and firewalld write the ruleset again when they start
    private suspend fun saveNft(): List<Item> {
        if (ufwOwns()) throw DeviceException(ErrorCategory.Unsupported, "ufw or firewalld keeps the rules of this host and writes them again when it starts")
        root.output(
            "for f in /etc/nftables.conf /etc/sysconfig/nftables.conf /etc/nftables.nft; do [ -f \"\$f\" ] && break; done; " +
                "[ -f \"\$f\" ] || f=/etc/nftables.conf; { echo '#!/usr/sbin/nft -f'; echo 'flush ruleset'; nft list ruleset; } > \"\$f.new\" && mv \"\$f.new\" \"\$f\"",
        )
        return emptyList()
    }

    private suspend fun ufwOwns(): Boolean {
        val present = tools()
        return "ufw" in present && ufwStatus() == "active" || "firewall-cmd" in present && root.exec("firewall-cmd --state").output.trim() == "running"
    }

    private suspend fun iptables(family: Family) = parseIptablesSave(root.output("${family.tool}-save -c"))

    private fun rules(family: Family) = HostTable(
        IPT_RULE, { iptables(family).second }, slow = true, arguments = { IPT_ARGUMENTS },
        add = { values -> addRule(family, values) }, set = { id, changes -> changeRule(family, id, changes) }, remove = { ids -> removeRules(family, ids) },
        move = { ids, before -> moveRules(family, ids, before) },
        commands = listOf("save"), run = { _, _ -> saveIptables(family) }, present = { "${family.tool}-save" in tools() },
    )

    private fun chains(family: Family) = HostTable(
        IPT_CHAIN, { iptables(family).first }, slow = true, arguments = { IPT_CHAIN_ARGUMENTS },
        add = { values -> addChain(family, values) }, set = { id, changes -> setPolicy(family, id, changes) }, remove = { ids -> removeChains(family, ids) },
        present = { "${family.tool}-save" in tools() },
    )

    private suspend fun addRule(family: Family, values: Map<String, String>): String {
        val table = values["table"].orEmpty().ifEmpty { "filter" }.also { require(it in IPT_TABLES, "table", it) }
        val chain = values["chain"].orEmpty().ifEmpty { "INPUT" }.also { require(NAME.matches(it), "chain", it) }
        val position = values["position"].orEmpty().also { require(it.isEmpty() || it.toIntOrNull() != null, "position", it) }
        val where = if (position.isEmpty()) "-A $chain" else "-I $chain $position"
        guarded(backup(family)) { root.output("${family.tool} -w -t $table $where ${spec(values["rule"].orEmpty())}") }
        return ""
    }

    // the numbers of a chain go down from the last, so each still points at its rule
    private suspend fun removeRules(family: Family, ids: List<String>) {
        val rules = ids.map { iptRule(family, it) }.sortedWith(compareBy<Triple<String, String, Int>>({ it.first }, { it.second }).thenByDescending { it.third })
        guarded(backup(family)) { root.output(rules.joinToString(" && ") { (table, chain, number) -> "${family.tool} -w -t $table -D $chain $number" }) }
    }

    private fun iptRule(family: Family, id: String): Triple<String, String, Int> {
        val parts = id.split('/')
        if (parts.size != 3 || parts[0] !in IPT_TABLES || !NAME.matches(parts[1]) || parts[2].toIntOrNull() == null) throw DeviceException(ErrorCategory.MissingItem, "${family.tool} has no rule $id")
        return Triple(parts[0], parts[1], parts[2].toInt())
    }

    private fun spec(rule: String) = words(rule).ifEmpty { throw DeviceException(ErrorCategory.ArgumentValue, "a rule needs its matches and its action, as -p tcp --dport 22 -j ACCEPT") }.joinToString(" ") { quote(it) }

    // a rule changed in its chain keeps its place; one sent to another chain goes to its end
    private suspend fun changeRule(family: Family, id: String?, changes: Map<String, String?>) {
        val (table, chain, number) = iptRule(family, id.orEmpty())
        val old = iptables(family).second.firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "${family.tool} has no rule $id")
        val rule = changes["rule"] ?: old["rule"].orEmpty()
        val newTable = changes["table"]?.ifEmpty { null } ?: table
        val newChain = changes["chain"]?.ifEmpty { null } ?: chain
        if (rule == old["rule"] && newTable == table && newChain == chain) return
        require(newTable in IPT_TABLES, "table", newTable)
        require(NAME.matches(newChain), "chain", newChain)
        val line = if (newTable == table && newChain == chain) {
            "${family.tool} -w -t $table -R $chain $number ${spec(rule)}"
        } else {
            "${family.tool} -w -t $newTable -A $newChain ${spec(rule)} && ${family.tool} -w -t $table -D $chain $number"
        }
        guarded(backup(family)) { root.output(line) }
    }

    // the moved rules leave their chain from the last up and come back in their order before the rule they are put above
    private suspend fun moveRules(family: Family, ids: List<String>, before: String?) {
        val rules = iptables(family).second
        val moved = ids.map { id -> rules.firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "${family.tool} has no rule $id") }
        val (table, chain) = iptRule(family, moved.first().id)
        val same = rules.filter { it["table"] == table && it["chain"] == chain }
        if (moved.any { it !in same }) throw DeviceException(ErrorCategory.ArgumentValue, "a rule moves within its chain")
        val left = same.filter { it !in moved }
        val anchor = before?.let { id -> same.dropWhile { it.id != id }.firstOrNull { it !in moved } }
        val place = anchor?.let { left.indexOf(it) + 1 } ?: (left.size + 1)
        val steps = moved.sortedByDescending { it["number"]?.toIntOrNull() ?: 0 }.map { "${family.tool} -w -t $table -D $chain ${it["number"]}" } +
            moved.mapIndexed { index, rule -> "${family.tool} -w -t $table -I $chain ${place + index} ${spec(rule["rule"].orEmpty())}" }
        guarded(backup(family)) { root.output(steps.joinToString(" && ")) }
    }

    private suspend fun addChain(family: Family, values: Map<String, String>): String {
        val table = values["table"].orEmpty().ifEmpty { "filter" }.also { require(it in IPT_TABLES, "table", it) }
        val chain = values["chain"].orEmpty().also { require(NAME.matches(it), "chain", it) }
        root.output("${family.tool} -w -t $table -N $chain")
        return "$table/$chain"
    }

    private suspend fun setPolicy(family: Family, id: String?, changes: Map<String, String?>) {
        val (table, chain) = id?.split('/')?.takeIf { it.size == 2 && it[0] in IPT_TABLES && NAME.matches(it[1]) } ?: throw DeviceException(ErrorCategory.MissingItem, "${family.tool} has no chain $id")
        val policy = changes["policy"]?.ifEmpty { null } ?: return
        require(policy == "ACCEPT" || policy == "DROP", "policy", policy)
        guarded(backup(family)) { root.output("${family.tool} -w -t $table -P $chain $policy") }
    }

    private suspend fun removeChains(family: Family, ids: List<String>) {
        val steps = ids.map { id ->
            val (table, chain) = id.split('/').takeIf { it.size == 2 && it[0] in IPT_TABLES && NAME.matches(it[1]) } ?: throw DeviceException(ErrorCategory.MissingItem, "${family.tool} has no chain $id")
            "${family.tool} -w -t $table -F $chain && ${family.tool} -w -t $table -X $chain"
        }
        guarded(backup(family)) { root.output(steps.joinToString(" && ")) }
    }

    // where the service of each family of distributions loads the rules at boot
    private suspend fun saveIptables(family: Family): List<Item> {
        if (ufwOwns()) throw DeviceException(ErrorCategory.Unsupported, "ufw or firewalld keeps the rules of this host and writes them again when it starts")
        val (alpine, arch, redHat, debian) = if (family.v4) {
            listOf("/etc/iptables/rules-save", "/etc/iptables/iptables.rules", "/etc/sysconfig/iptables", "/etc/iptables/rules.v4")
        } else {
            listOf("/etc/iptables/rules6-save", "/etc/iptables/ip6tables.rules", "/etc/sysconfig/ip6tables", "/etc/iptables/rules.v6")
        }
        root.output(
            "if [ -f /etc/alpine-release ]; then f=$alpine; elif [ -f /etc/arch-release ]; then f=$arch; elif [ -d /etc/sysconfig ]; then f=$redHat; else f=$debian; fi; " +
                "mkdir -p \"\${f%/*}\" && ${family.tool}-save > \"\$f.new\" && mv \"\$f.new\" \"\$f\"",
        )
        return emptyList()
    }

    private fun backup(family: Family) = Backup("${family.tool}-save > \"\$B/rules\"", "${family.tool}-restore < \"\$1/rules\"")

    // the rules as they were come back by themselves unless the session still answers after the change, and at once after a change
    // of several steps that failed in the middle
    private suspend fun guarded(backup: Backup, change: suspend () -> Unit) {
        val checked = admitted ?: admits().also { admitted = it }
        val armed = root.output(
            "B=\$(mktemp -d /tmp/networkhelm-firewall.XXXXXX) || exit 1\n${backup.save} || exit 1\n" +
                "nohup sh -c ${quote("sleep $GUARD_SECONDS; ${backup.restore}; rm -rf \"\$1\"")} guard \"\$B\" >/dev/null 2>&1 </dev/null &\necho \"\$! \$B\"",
        ).trim().split(' ')
        val pid = armed.getOrNull(0)?.takeIf { it.all(Char::isDigit) } ?: throw DeviceException(ErrorCategory.Api, "the host did not keep the rules it had: ${armed.joinToString(" ")}")
        val kept = armed.getOrNull(1)?.takeIf { it.startsWith("/tmp/networkhelm-firewall.") } ?: throw DeviceException(ErrorCategory.Api, "the host did not keep the rules it had")
        try {
            change()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            root.exec("kill $pid; sh -c ${quote(backup.restore)} restore ${quote(kept)}; rm -rf ${quote(kept)}")
            throw e
        }
        if (!answers()) throw DeviceException(ErrorCategory.ConnectionLost, "the host stopped answering after the change: the rules it had come back in a minute")
        // a rule that keeps the sessions that are open and drops new ones would lock the user out once this session ends
        if (checked && !admits()) {
            root.exec("kill $pid; sh -c ${quote(backup.restore)} restore ${quote(kept)}; rm -rf ${quote(kept)}")
            throw DeviceException(ErrorCategory.ArgumentValue, "after the change the host let no new SSH session in: the rules it had are back")
        }
        root.exec("kill $pid; rm -rf ${quote(kept)}")
    }

    // as the probe of the session, the login has a job of its own: a connection a firewall drops does not give up when its caller does
    private suspend fun admits(): Boolean {
        val login = login ?: return false
        val attempt = CoroutineScope(currentCoroutineContext().minusKey(Job)).async { login() }
        return try {
            withTimeoutOrNull(GUARD_LOGIN) { attempt.await() } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    // the probe has a job of its own: a command over a connection that went silent does not give up when its caller does
    private suspend fun answers(): Boolean {
        val probe = CoroutineScope(currentCoroutineContext().minusKey(Job)).async { ssh.exec("true") }
        return try {
            withTimeoutOrNull(GUARD_CHECK) { probe.await() } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private class Backup(val save: String, val restore: String)

    private fun require(valid: Boolean, field: String, value: String) {
        if (!valid) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes")
    }

    private companion object {
        const val GUARD_SECONDS = 60
        val GUARD_CHECK = 10.seconds
        val GUARD_LOGIN = 20.seconds
        val KNOWN = setOf("ufw", "firewall-cmd", "nft", "iptables-save", "ip6tables-save")
        val NAME = Regex("[A-Za-z0-9_.@:-]+")
        val ADDRESS = Regex("[0-9A-Fa-f.:/]+")
        val PORTS = Regex("[0-9:,]+")
        val POLICIES = mapOf("DROP" to "deny", "ACCEPT" to "allow", "REJECT" to "reject")
        val TARGETS = listOf("default", "ACCEPT", "DROP", "%%REJECT%%")
        val NFT_FAMILIES = listOf("inet", "ip", "ip6", "arp", "bridge", "netdev")
        val IPT_TABLES = listOf("filter", "nat", "mangle", "raw", "security")
        val UFW_BACKUP = Backup(
            "cp -a /etc/ufw \"\$B/ufw\"",
            "rm -rf /etc/ufw && cp -a \"\$1/ufw\" /etc/ufw && if grep -q '^ENABLED=yes' /etc/ufw/ufw.conf; then ufw --force enable; else ufw disable; fi",
        )
        val FIREWALLD_BACKUP = Backup("cp -a /etc/firewalld \"\$B/firewalld\"", "rm -rf /etc/firewalld && cp -a \"\$1/firewalld\" /etc/firewalld && firewall-cmd --reload")
        val NFT_BACKUP = Backup("nft list ruleset > \"\$B/rules\"", "{ echo 'flush ruleset'; cat \"\$1/rules\"; } | nft -f -")

        val OVERVIEW = listOf("manager", "ufw", "firewalld", "nftables-rules", "iptables-rules", "ip6tables-rules", "iptables-backend")
        val UFW = listOf("number", "action", "direction", "protocol", "to", "to-port", "from", "from-port", "interface", "comment", "v6")
        val UFW_ARGUMENTS = listOf(
            Argument("action", ValueType.Enumeration(closed = true), listOf("allow", "deny", "reject", "limit"), default = "allow"),
            Argument("direction", ValueType.Enumeration(closed = true), listOf("in", "out"), default = "in"),
            Argument("protocol", ValueType.Enumeration(closed = true), listOf("any", "tcp", "udp"), default = "any"),
            Argument("to-port", ValueType.Text(), optional = true, description = "22, 8000:8100 or 80,443"),
            Argument("from-port", ValueType.Text(), optional = true),
            Argument("from", ValueType.Text(), optional = true, default = "any"),
            Argument("to", ValueType.Text(), optional = true, default = "any"),
            Argument("interface", ValueType.Text(), optional = true),
            Argument("comment", ValueType.Text(), optional = true),
            Argument("position", ValueType.Integer(min = 1), optional = true),
        )
        val UFW_SETTINGS = listOf("enabled", "logging", "default-incoming", "default-outgoing", "default-routed")
        val UFW_SETTINGS_ARGUMENTS = listOf(
            Argument("enabled", ValueType.YesNo),
            Argument("logging", ValueType.Enumeration(closed = true), listOf("off", "low", "medium", "high", "full")),
            Argument("default-incoming", ValueType.Enumeration(closed = true), listOf("allow", "deny", "reject")),
            Argument("default-outgoing", ValueType.Enumeration(closed = true), listOf("allow", "deny", "reject")),
            Argument("default-routed", ValueType.Enumeration(closed = true), listOf("allow", "deny", "reject")),
        )
        val FIREWALLD = listOf("zone", "kind", "value", "active")
        val KINDS = linkedMapOf(
            "services" to "service", "ports" to "port", "protocols" to "protocol", "sources" to "source", "interfaces" to "interface",
            "rich rules" to "rich-rule", "forward-ports" to "forward-port", "source-ports" to "source-port", "icmp-blocks" to "icmp-block",
        )
        val ZONE = listOf("name", "default", "active", "target", "interfaces", "sources", "services", "ports", "masquerade", "forward")
        val ZONE_ARGUMENTS = listOf(
            Argument("masquerade", ValueType.YesNo),
            Argument("target", ValueType.Enumeration(closed = true), TARGETS),
        )
        val NFT_RULE = listOf("family", "table", "chain", "handle", "rule", "comment", "packets", "bytes")
        val NFT_ARGUMENTS = listOf(
            Argument("family", ValueType.Enumeration(closed = true), NFT_FAMILIES, default = "inet"),
            Argument("table", ValueType.Text()),
            Argument("chain", ValueType.Text()),
            Argument("rule", ValueType.Text(), description = "tcp dport 22 accept"),
        )
        val NFT_CHAIN = listOf("family", "table", "chain", "type", "hook", "priority", "policy", "rules")
        val IPT_RULE = listOf("table", "chain", "number", "rule", "target", "comment", "packets", "bytes")
        val IPT_ARGUMENTS = listOf(
            Argument("table", ValueType.Enumeration(closed = true), IPT_TABLES, default = "filter"),
            Argument("chain", ValueType.Text(), default = "INPUT"),
            Argument("rule", ValueType.Text(), description = "-p tcp --dport 22 -j ACCEPT"),
            Argument("position", ValueType.Integer(min = 1), optional = true),
        )
        val IPT_CHAIN = listOf("table", "chain", "policy", "packets", "bytes", "rules")
        val IPT_CHAIN_ARGUMENTS = listOf(
            Argument("table", ValueType.Enumeration(closed = true), IPT_TABLES, default = "filter"),
            Argument("chain", ValueType.Text()),
            Argument("policy", ValueType.Enumeration(closed = true), listOf("ACCEPT", "DROP")),
        )
    }
}

internal class FirewalldZone(val name: String, val default: Boolean, val active: Boolean, val values: Map<String, String>, val entries: List<Pair<String, String>>)

private const val SECTION = "--nh-section--"

// ### tuple ### allow tcp 80 0.0.0.0/0 1000:2000 192.0.2.0/24 in_eth0 comment=72642062: action, protocol, port and address of the
// destination, port and address of the source, the application profiles if any, the direction with the interface
internal fun parseUfwTuples(v4: String, v6: String): List<Item> {
    fun tuples(text: String, six: Boolean) = text.lines().filter { it.startsWith("### tuple ### ") }.map { it.removePrefix("### tuple ### ").trim().split(' ') to six }
    return (tuples(v4, false) + tuples(v6, true)).mapIndexed { index, (all, six) ->
        val comment = all.lastOrNull()?.takeIf { it.startsWith("comment=") }
        val words = if (comment != null) all.dropLast(1) else all
        val apps = words.size >= 9
        val way = words.getOrElse(if (apps) 8 else 6) { "in" }
        fun any(value: String) = value.takeIf { it != "any" && it != "-" && it != "0.0.0.0/0" && it != "::/0" }.orEmpty()
        val faces = way.split('!').filter { '_' in it }
        val number = "${index + 1}"
        Item(
            number,
            linkedMapOf(
                "number" to number,
                "action" to words[0].removePrefix("route:"),
                "direction" to if (words[0].startsWith("route:")) "route" else way.substringBefore('_'),
                "protocol" to any(words.getOrElse(1) { "" }),
                "to" to any(words.getOrElse(3) { "" }),
                "to-port" to (if (apps) any(words[6]) else "").ifEmpty { any(words.getOrElse(2) { "" }) },
                "from" to any(words.getOrElse(5) { "" }),
                "from-port" to (if (apps) any(words[7]) else "").ifEmpty { any(words.getOrElse(4) { "" }) },
                "interface" to if (faces.size > 1) faces.joinToString(", ") { "${it.substringBefore('_')} ${it.substringAfter('_')}" } else faces.singleOrNull()?.substringAfter('_').orEmpty(),
                "comment" to comment?.removePrefix("comment=")?.chunked(2)?.mapNotNull { it.toIntOrNull(16)?.toByte() }?.toByteArray()?.decodeToString().orEmpty(),
                "v6" to "$six",
            ),
        )
    }
}

internal fun parseFirewalldZones(text: String): List<FirewalldZone> {
    val zones = mutableListOf<FirewalldZone>()
    var name: String? = null
    var flags = ""
    val values = linkedMapOf<String, String>()
    val rich = mutableListOf<String>()
    fun close() {
        val zone = name ?: return
        val entries = values.filterKeys { it in FIREWALLD_KINDS && it != "rich rules" }.flatMap { (key, value) ->
            value.split(' ').filter { it.isNotEmpty() }.map { FIREWALLD_KINDS.getValue(key) to it }
        } + rich.map { "rich-rule" to it }
        zones += FirewalldZone(zone, "default" in flags, "active" in flags, values.toMap(), entries)
        values.clear()
        rich.clear()
    }
    for (line in text.lines()) {
        when {
            line.isBlank() -> Unit
            line.startsWith("\t") -> rich += line.trim()
            !line.startsWith(" ") -> {
                close()
                name = line.substringBefore(" (").trim()
                flags = line.substringAfter(" (", "").substringBefore(')')
            }
            ':' in line -> values[line.substringBefore(':').trim()] = line.substringAfter(':').trim()
        }
    }
    close()
    return zones
}

private val FIREWALLD_KINDS = mapOf(
    "services" to "service", "ports" to "port", "protocols" to "protocol", "sources" to "source", "interfaces" to "interface",
    "rich rules" to "rich-rule", "forward-ports" to "forward-port", "source-ports" to "source-port", "icmp-blocks" to "icmp-block",
)

// table inet filter { # handle 1 / chain input { # handle 1 / type filter hook input priority filter; policy accept; / rule # handle 4
internal fun parseNftRuleset(text: String): Pair<List<Item>, List<Item>> {
    val chains = mutableListOf<Item>()
    val rules = mutableListOf<Item>()
    var family = ""
    var table = ""
    var chain: String? = null
    var head = emptyMap<String, String>()
    var count = 0
    fun close() {
        val name = chain ?: return
        chains += Item("$family/$table/$name", linkedMapOf("family" to family, "table" to table, "chain" to name) + head + ("rules" to "$count"))
        chain = null
    }
    for (line in text.lines()) {
        val depth = line.takeWhile { it == '\t' }.length
        val body = line.trim()
        when {
            depth == 0 && body.startsWith("table ") -> {
                close()
                body.split(' ').let {
                    family = it.getOrElse(1) { "" }
                    table = it.getOrElse(2) { "" }
                }
            }
            depth == 1 && body.startsWith("chain ") -> {
                close()
                chain = body.split(' ').getOrElse(1) { "" }
                head = emptyMap()
                count = 0
            }
            depth == 1 -> close()
            depth == 2 && chain != null && body.startsWith("type ") && " hook " in body -> {
                val words = body.split(' ', ';').filter { it.isNotEmpty() }
                fun after(word: String) = words.indexOf(word).takeIf { it >= 0 }?.let { words.getOrNull(it + 1) }.orEmpty()
                head = linkedMapOf("type" to after("type"), "hook" to after("hook"), "priority" to body.substringAfter(" priority ", "").substringBefore(';').trim(), "policy" to after("policy"))
            }
            depth == 2 && chain != null && " # handle " in body -> {
                val handle = body.substringAfterLast(" # handle ").trim()
                var rule = body.substringBeforeLast(" # handle ")
                val counter = Regex("""counter packets (\d+) bytes (\d+)""").find(rule)
                if (counter != null) rule = rule.replace(counter.value, "counter")
                val comment = Regex(""" comment "((?:[^"\\]|\\.)*)"""").find(rule)
                if (comment != null) rule = rule.replace(comment.value, "")
                count++
                rules += Item(
                    "$family/$table/$chain/$handle",
                    linkedMapOf(
                        "family" to family, "table" to table, "chain" to chain.orEmpty(), "handle" to handle, "rule" to rule.trim(),
                        "comment" to comment?.groupValues?.get(1).orEmpty(), "packets" to counter?.groupValues?.get(1).orEmpty(), "bytes" to counter?.groupValues?.get(2).orEmpty(),
                    ),
                )
            }
        }
    }
    close()
    return chains to rules
}

// *filter / :INPUT ACCEPT [0:0] / [22:4854] -A INPUT -p tcp --dport 22 -j ACCEPT / COMMIT
internal fun parseIptablesSave(text: String): Pair<List<Item>, List<Item>> {
    val chains = linkedMapOf<String, MutableMap<String, String>>()
    val rules = mutableListOf<Item>()
    var table = ""
    val numbers = mutableMapOf<String, Int>()
    for (line in text.lines()) {
        when {
            line.startsWith("*") -> table = line.drop(1).trim()
            line.startsWith(":") -> {
                val words = line.drop(1).split(' ')
                val counters = words.getOrNull(2).orEmpty().trim('[', ']').split(':')
                chains["$table/${words[0]}"] = linkedMapOf(
                    "table" to table, "chain" to words[0], "policy" to words.getOrElse(1) { "" }.takeIf { it != "-" }.orEmpty(),
                    "packets" to counters.getOrElse(0) { "" }, "bytes" to counters.getOrElse(1) { "" }, "rules" to "0",
                )
            }
            "-A " in line && (line.startsWith("-A ") || line.startsWith("[")) -> {
                val counters = if (line.startsWith("[")) line.substringBefore(']').drop(1).split(':') else emptyList()
                val spec = line.substringAfter("-A ")
                val chain = spec.substringBefore(' ')
                val rule = spec.substringAfter(' ', "")
                val number = (numbers["$table/$chain"] ?: 0) + 1
                numbers["$table/$chain"] = number
                chains["$table/$chain"]?.set("rules", "$number")
                val parts = try {
                    words(rule)
                } catch (e: DeviceException) {
                    rule.split(' ')
                }
                fun after(option: String) = parts.indexOf(option).takeIf { it >= 0 }?.let { parts.getOrNull(it + 1) }.orEmpty()
                rules += Item(
                    "$table/$chain/$number",
                    linkedMapOf(
                        "table" to table, "chain" to chain, "number" to "$number", "rule" to rule, "target" to after("-j").ifEmpty { after("-g") },
                        "comment" to after("--comment"), "packets" to counters.getOrElse(0) { "" }, "bytes" to counters.getOrElse(1) { "" },
                    ),
                )
            }
        }
    }
    return chains.map { (id, values) -> Item(id, values) } to rules
}
