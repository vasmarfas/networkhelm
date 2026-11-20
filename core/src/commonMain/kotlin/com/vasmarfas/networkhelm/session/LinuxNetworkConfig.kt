package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// the change ip made, written also to the configuration the host comes up with: through nmcli to NetworkManager, to a
// netplan file of its own, to a drop-in of systemd-networkd, to the stanza ifupdown has for the interface. Nothing is
// applied or reloaded, the running system has the change already
internal class LinuxNetworkConfig(private val ssh: SshSession, private val root: Root, private val tools: suspend () -> Set<String>) {
    private enum class Kind { NetworkManager, Netplan, Networkd, Ifupdown }

    // where an interface is configured: the profile of NetworkManager, the netplan group and id, the .network file of
    // systemd-networkd, the ifupdown file with its stanza; an empty place is one the change makes
    private class Holder(val kind: Kind, val name: String, val place: String, val group: String = "", val id: String = "")

    suspend fun manager(device: String): String? = try {
        holder(device).name
    } catch (e: DeviceException) {
        if (e.category != ErrorCategory.Unsupported) throw e
        null
    }

    suspend fun addAddress(device: String, address: String) {
        prefixed(address)
        val holder = holder(device)
        val v6 = ':' in address
        when (holder.kind) {
            Kind.NetworkManager -> {
                val family = if (v6) "ipv6" else "ipv4"
                val manual = if (nm(holder, "$family.method").single() in NM_OFF) " $family.method manual" else ""
                root.output("nmcli connection modify ${holder.place}$manual +$family.addresses $address")
            }
            Kind.Netplan -> netplan(holder) { it.withItem("addresses", JsonPrimitive(address)) }
            Kind.Networkd -> networkd(holder, device) { networkdWith(it, "Address", listOf("Address" to address)) }
            Kind.Ifupdown -> interfaces(holder) {
                interfacesWith(it, device, listOf("post-up ${addressCommand(v6, "add", address, device)}", "pre-down ${addressCommand(v6, "del", address, device)}"))
            }
        }
    }

    suspend fun removeAddress(device: String, address: String) {
        prefixed(address)
        val holder = holder(device)
        val missing = holder.missing("address $address", device)
        when (holder.kind) {
            Kind.NetworkManager -> {
                val family = if (':' in address) "ipv6" else "ipv4"
                val (method, kept) = nm(holder, "$family.method", "$family.addresses")
                val addresses = kept.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (address !in addresses) throw missing
                val off = if (addresses.size == 1 && method == "manual") " $family.method disabled" else ""
                root.output("nmcli connection modify ${holder.place} -$family.addresses $address$off")
            }
            Kind.Netplan -> netplan(holder) { it.withoutItems("addresses") { item -> (item as? JsonPrimitive)?.contentOrNull == address } ?: throw missing }
            Kind.Networkd -> networkdRemove(holder, missing) {
                networkdWithout(it, { section, entries -> section == "Address" && ("Address" to address) in entries }) { section, key, value -> section == "Network" && key == "Address" && value == address }
            }
            Kind.Ifupdown -> interfaces(holder) { text ->
                interfacesEdit(text, device) { option -> option.takeUnless { hookAddress(it) == address || it == "address $address" } } ?: throw missing
            }
        }
    }

    suspend fun addRoute(destination: String, gateway: String, device: String, metric: String, table: String) {
        val route = checked(destination, gateway, metric, table)
        val name = device.ifEmpty { routeDevice(gateway) }
        val holder = holder(name).also { it.takes(route) }
        when (holder.kind) {
            Kind.NetworkManager -> root.output("nmcli connection modify ${holder.place} +${if (route.v6) "ipv6" else "ipv4"}.routes ${quote(route.nm())}")
            Kind.Netplan -> netplan(holder) { it.withItem("routes", route.netplan()) }
            Kind.Networkd -> networkd(holder, name) { networkdWith(it, "Route", route.networkd()) }
            Kind.Ifupdown -> interfaces(holder) { interfacesWith(it, name, listOf("post-up ${route.ip("add", name)}", "pre-down ${route.ip("del", name)}")) }
        }
    }

    suspend fun removeRoute(destination: String, gateway: String, device: String, metric: String, table: String) {
        val route = checked(destination, gateway, metric, table)
        val name = device.ifEmpty { routeDevice(gateway) }
        val holder = holder(name)
        val missing = holder.missing("route to ${route.destination}${if (route.gateway.isEmpty()) "" else " via ${route.gateway}"}", name)
        when (holder.kind) {
            Kind.NetworkManager -> {
                val family = if (route.v6) "ipv6" else "ipv4"
                val (routes, current) = nm(holder, "$family.routes", "$family.gateway")
                val entries = routes.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val left = entries.filterNot { nmRoute(it, route.v6).same(route) }
                when {
                    left.size < entries.size -> root.output("nmcli connection modify ${holder.place} $family.routes ${quote(left.joinToString(", "))}")
                    route.default && route.table.isEmpty() && route.gateway.isNotEmpty() && current == route.gateway -> root.output("nmcli connection modify ${holder.place} $family.gateway ''")
                    else -> throw missing
                }
            }
            Kind.Netplan -> netplan(holder) { it.withoutItems("routes") { item -> netplanRoute(item)?.same(route) == true } ?: throw missing }
            Kind.Networkd -> networkdRemove(holder, missing) {
                networkdWithout(it, { section, entries -> section == "Route" && networkdRoute(entries).same(route) }) { section, key, value ->
                    section == "Network" && key == "Gateway" && route.default && route.table.isEmpty() && value == route.gateway
                }
            }
            Kind.Ifupdown -> interfaces(holder) { text ->
                interfacesEdit(text, name) { option ->
                    option.takeUnless { hookRoute(it)?.same(route) == true || route.default && route.table.isEmpty() && it == "gateway ${route.gateway}" }
                } ?: throw missing
            }
        }
    }

    suspend fun setMtu(device: String, mtu: Int) {
        if (mtu !in 68..65535) throw DeviceException(ErrorCategory.ArgumentValue, "mtu: $mtu is not a number from 68 to 65535")
        val holder = holder(device)
        when (holder.kind) {
            Kind.NetworkManager -> root.output("nmcli connection modify ${holder.place} ${NM_MTU[nm(holder, "connection.type").single()] ?: "802-3-ethernet.mtu"} $mtu")
            Kind.Netplan -> netplan(holder) { JsonObject(it.orEmpty() + ("mtu" to JsonPrimitive(mtu))) }
            Kind.Networkd -> networkd(holder, device) { networkdSet(it, "Link", "MTUBytes", "$mtu") }
            Kind.Ifupdown -> interfaces(holder) { text ->
                val cleared = interfacesEdit(text, device) { option -> option.takeUnless { it.startsWith("mtu ") || hookMtu(it, device) } } ?: text
                interfacesWith(cleared, device, listOf("post-up ip link set dev $device mtu $mtu"))
            }
        }
    }

    // whether the interface comes up at boot
    suspend fun setEnabled(device: String, up: Boolean) {
        val holder = holder(device)
        when (holder.kind) {
            Kind.NetworkManager -> root.output("nmcli connection modify ${holder.place} connection.autoconnect ${if (up) "yes" else "no"}")
            Kind.Netplan -> netplan(holder) { JsonObject(if (up) it.orEmpty() - "activation-mode" else it.orEmpty() + ("activation-mode" to JsonPrimitive("manual"))) }
            Kind.Networkd -> networkd(holder, device) { networkdSet(it, "Link", "ActivationPolicy", if (up) "up" else "down") }
            Kind.Ifupdown -> interfaces(holder) { interfacesAuto(it, device, up) }
        }
    }

    // a link ip has just made: a VLAN goes where its parent is configured, a bridge, a dummy and a tunnel where the interface with
    // the default route is
    suspend fun addLink(kind: String, name: String, parent: String, vlanId: Int?, tunnel: LinkTunnel? = null) {
        val group = LINKS[kind] ?: throw DeviceException(ErrorCategory.ArgumentValue, "type: $kind is none of vlan, bridge and dummy")
        valid(name)
        val vlan = kind == "vlan"
        if (vlan && (vlanId == null || vlanId !in 1..4094)) throw DeviceException(ErrorCategory.ArgumentValue, "vlan-id: $vlanId is not a number from 1 to 4094")
        val system = if (vlan) holder(parent) else systemHolder(name)
        when (system.kind) {
            Kind.NetworkManager -> {
                if (kind == "geneve") throw until("NetworkManager keeps no GENEVE tunnel")
                root.output(
                    tunnel?.nmcli(name, parent) ?: ("nmcli connection add type $kind con-name ${quote(name)} ifname ${quote(name)}" +
                        (if (vlan) " dev ${quote(parent)} id $vlanId" else "") + (if (kind == "bridge") " bridge.stp no" else "") + " ipv4.method disabled ipv6.method ignore"),
                )
            }
            Kind.Netplan -> {
                if (kind == "geneve") throw until("netplan keeps no GENEVE tunnel")
                netplan(Holder(Kind.Netplan, system.name, "", group, name)) {
                    tunnel?.netplan() ?: JsonObject(if (vlan) mapOf("id" to JsonPrimitive(vlanId), "link" to JsonPrimitive(system.id)) else emptyMap())
                }
            }
            Kind.Networkd -> {
                // a .network made for the parent would take it over at boot without what configures it now
                if (vlan && system.place.isEmpty()) throw until("systemd-networkd has no .network file for $parent")
                val own = "$NETWORKD/00-networkhelm-$name"
                update("$own.netdev") { tunnel?.netdev(name) ?: ("[NetDev]\nName=$name\nKind=$kind\n" + (if (vlan) "\n[VLAN]\nId=$vlanId\n" else "")) }
                update("$own.network") { "[Match]\nName=$name\n" }
                if (vlan) networkd(system, parent) { networkdWith(it, "Network", listOf("VLAN" to name)) }
            }
            Kind.Ifupdown -> {
                val create = tunnel?.command(name, parent) ?: if (vlan) "ip link add link $parent name $name type vlan id $vlanId" else "ip link add name $name type $kind"
                interfaces(ifupdownOf(name, orNew = true) ?: system) { interfacesWith(it, name, listOf("pre-up $create", "post-down ip link del $name")) }
            }
        }
    }

    suspend fun removeLink(name: String) {
        valid(name)
        val holder = explicit(name) ?: systemHolder(name)
        val missing = holder.missing("definition", name)
        when (holder.kind) {
            Kind.NetworkManager -> root.output("nmcli connection delete ${profiles(name).ifEmpty { throw missing }.joinToString(" ")}")
            Kind.Netplan -> update(NETPLAN, NETPLAN_CHECK, "600") { text ->
                LINKS.values.fold(text) { left, group -> netplanWith(left, group, name) { null } }.also { if (it.trimEnd() == text.trimEnd()) throw missing }
            }
            Kind.Networkd -> {
                val own = "$NETWORKD/00-networkhelm-$name"
                val files = files(root.output(dump("${quote("$own.netdev")} $NETWORKD/*.network.d/networkhelm.conf")))
                if ("$own.netdev" !in files) throw missing
                for ((path, text) in files) {
                    if (!path.endsWith("/networkhelm.conf")) continue
                    networkdWithout(text, { _, _ -> false }) { section, key, value -> section == "Network" && key == "VLAN" && value == name }?.let { write(path, it) }
                }
                root.output("rm -rf ${quote("$own.netdev")} ${quote("$own.network")} ${quote("$own.network.d")}")
            }
            Kind.Ifupdown -> interfaces(holder) { interfacesWithout(it, name) ?: throw missing }
        }
    }

    private suspend fun holder(device: String): Holder {
        valid(device)
        explicit(device)?.let { return it }
        val system = systemHolder(device)
        return when (system.kind) {
            Kind.NetworkManager -> throw until("NetworkManager keeps no profile for $device")
            Kind.Netplan -> if (physical(device)) Holder(Kind.Netplan, system.name, "", "ethernets", device) else throw until("netplan does not define $device")
            Kind.Networkd -> Holder(Kind.Networkd, system.name, "")
            Kind.Ifupdown -> ifupdownOf(device, orNew = true) ?: system
        }
    }

    private suspend fun explicit(device: String): Holder? = networkManagerOf(device) ?: netplanOf(device) ?: networkdOf(device) ?: ifupdownOf(device, orNew = false)

    // an interface no configuration has yet goes to the manager of the interface with the default route
    private suspend fun systemHolder(device: String): Holder {
        val main = dev(read("ip route show default; ip -6 route show default"))?.takeIf { it != device }
        return main?.let { explicit(it) } ?: throw until(
            if (tools().none { it in MANAGERS }) "the host has none of NetworkManager, netplan, systemd-networkd and ifupdown"
            else "no configuration of NetworkManager, netplan, systemd-networkd or ifupdown holds $device${main?.let { " or $it" }.orEmpty()}",
        )
    }

    private suspend fun networkManagerOf(device: String): Holder? {
        if ("nmcli" !in tools()) return null
        val lines = read("nmcli -t -f RUNNING general; nmcli -t -f GENERAL.STATE,GENERAL.CON-UUID device show ${quote(device)}").lines()
        if (lines.firstOrNull() != "running") return null
        val values = lines.drop(1).associate { it.substringBefore(':') to it.substringAfter(':', "") }
        val state = values["GENERAL.STATE"] ?: return null
        if ("unmanaged" in state) return null
        // an interface brought up by hand is connected externally, and a profile on disk made for it, as a new link gets one, holds it still
        val active = values["GENERAL.CON-UUID"].orEmpty().takeIf { it.isNotEmpty() && "externally" !in state }
        val uuid = active ?: profiles(device).firstOrNull() ?: return null
        return Holder(Kind.NetworkManager, "NetworkManager", uuid)
    }

    // the profiles on disk bound to the interface by name, active or not; the one NetworkManager keeps in memory for an
    // interface brought up by hand is none of them
    private suspend fun profiles(device: String): List<String> = read(
        "d=${quote(device)}; nmcli -t -f UUID,FILENAME connection show | while IFS=: read -r u f; do case \"\$f\" in /etc/*|/usr/lib/*) " +
            "[ \"\$(nmcli -e no -g connection.interface-name connection show \"\$u\")\" = \"\$d\" ] && echo \"\$u\";; esac; done; true",
    ).lines().filter { it.isNotEmpty() }

    private suspend fun nm(holder: Holder, vararg properties: String): List<String> {
        val values = root.output("nmcli -e no -g ${properties.joinToString(",")} connection show ${holder.place}").lines()
        return properties.indices.map { values.getOrElse(it) { "" } }
    }

    // the files are root's alone on Ubuntu, so they are read as root once there are any
    private suspend fun netplanOf(device: String): Holder? {
        if ("netplan" !in tools()) return null
        val paths = read("for f in /lib/netplan/*.yaml /etc/netplan/*.yaml /run/netplan/*.yaml; do [ -f \"\$f\" ] && echo \"\$f\"; done; true").lines().filter { it.isNotEmpty() }
        if (paths.isEmpty()) return null
        val found = parseNetplan(files(root.output(dump(paths.sortedBy { it.substringAfterLast('/') }.joinToString(" ") { quote(it) }))).values)
            .firstOrNull { netplanHolds(it.name, device) } ?: return null
        return Holder(Kind.Netplan, "netplan (${if (found.renderer == "NetworkManager") "NetworkManager" else "systemd-networkd"})", "", found.group, found.id)
    }

    // a drop-in goes to the file networkd took for the interface, or to one in /etc that names it alone, as a file of
    // NetworkHelm does; a file that matches more interfaces would pass the change to all of them
    private suspend fun networkdOf(device: String): Holder? {
        if ("networkctl" !in tools()) return null
        val output = read(
            "systemctl is-active systemd-networkd; f=\$(networkctl status ${quote(device)} --no-pager 2>/dev/null | sed -n 's/^ *Network File: //p'); echo \"\$f\"; " +
                dump("\"\$f\" $NETWORKD/*.network"),
        )
        val (active, network) = output.substringBefore(FILE).lines() + List(2) { "" }
        if (active.trim() != "active") return null
        val files = files(output)
        val place = if (network.startsWith("/")) {
            network.also { if (networkdNames(files[it].orEmpty()) != listOf(device)) throw until("$it configures more interfaces than $device, and a change to it would reach them all") }
        } else {
            files.keys.firstOrNull { it.startsWith("$NETWORKD/") && networkdNames(files.getValue(it)) == listOf(device) } ?: return null
        }
        return Holder(Kind.Networkd, "systemd-networkd", place)
    }

    private suspend fun ifupdownOf(device: String, orNew: Boolean): Holder? {
        if ("ifup" !in tools()) return null
        val output = read("PATH=\$PATH:/usr/sbin:/sbin ifup --version 2>/dev/null | head -n 1; ${dump("$INTERFACES /etc/network/interfaces.d/*")}")
        val files = files(output)
        val main = files[INTERFACES] ?: return null
        val sources = interfacesSources(main)
        val place = files.entries.firstOrNull { (path, text) ->
            val file = path.substringAfterLast('/')
            (path == INTERFACES || !file.startsWith(".") && sources.any { (dir, names) -> path.substringBeforeLast('/') == dir && names.matches(file) }) &&
                text.lines().any { ifaceOf(it, device) }
        }?.key ?: if (orNew) interfacesFile(main) else return null
        return Holder(Kind.Ifupdown, if ("ifupdown-ng" in output.substringBefore(FILE)) "ifupdown-ng" else "ifupdown", place)
    }

    private suspend fun physical(device: String) = read("[ -e /sys/class/net/$device/device ] && [ ! -e /sys/class/net/$device/wireless ] && echo yes").trim() == "yes"

    private suspend fun routeDevice(gateway: String): String {
        if (gateway.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "a route goes through a gateway or an interface")
        return dev(read("ip -o route get $gateway")) ?: throw DeviceException(ErrorCategory.MissingItem, "no interface leads to $gateway")
    }

    private suspend fun read(command: String) = ssh.exec(command).output

    private suspend fun netplan(holder: Holder, change: (JsonObject?) -> JsonObject?) = update(NETPLAN, NETPLAN_CHECK, "600") { netplanWith(it, holder.group, holder.id, change) }

    // the drop-in of NetworkHelm beside the .network file of the interface, or beside a file of its own when it has none
    private suspend fun networkd(holder: Holder, device: String, change: (String) -> String) {
        val network = holder.place.ifEmpty { "$NETWORKD/00-networkhelm-$device.network" }
        update(dropIn(network), change = change)
        if (holder.place.isEmpty()) update(network) { "[Match]\nName=$device\n" }
    }

    // what NetworkHelm added is in its drop-in, and the .network file itself gives up its own entries when it lies in /etc
    private suspend fun networkdRemove(holder: Holder, missing: DeviceException, change: (String) -> String?) {
        var removed = false
        if (holder.place.isNotEmpty()) {
            for (path in listOf(dropIn(holder.place), holder.place).filter { it.startsWith("/etc/") }) update(path) { text -> change(text)?.also { removed = true } ?: text }
        }
        if (!removed) throw missing
    }

    private fun dropIn(network: String) = "$NETWORKD/${network.substringAfterLast('/')}.d/networkhelm.conf"

    private suspend fun interfaces(holder: Holder, change: (String) -> String) = update(holder.place, IFUPDOWN_CHECK, change = change)

    private suspend fun update(path: String, check: String? = null, mode: String? = null, change: (String) -> String) {
        val text = root.output("[ ! -e ${quote(path)} ] || cat ${quote(path)}")
        val changed = change(text)
        if (changed.trimEnd() != text.trimEnd()) write(path, changed, check, mode)
    }

    // the file as it was waits beside it until the check has passed and comes back when it fails; no text takes the file away
    private suspend fun write(path: String, text: String, check: String? = null, mode: String? = null) {
        val file = quote(path)
        val dir = path.substringBeforeLast('/')
        val new = quote("$dir/.${path.substringAfterLast('/')}.networkhelm-new")
        val old = quote("$dir/.${path.substringAfterLast('/')}.networkhelm-old")
        val put = if (text.isBlank()) {
            "rm -f $file"
        } else {
            "{ [ ! -e $file ] || cp -p $file $new; } && printf '%s' ${quote(text.trimEnd() + "\n")} > $new${mode?.let { " && chmod $it $new" }.orEmpty()} && mv -f $new $file"
        }
        root.output(
            "mkdir -p ${quote(dir)} && rm -f $old && { [ ! -e $file ] || cp -p $file $old; } && $put || { rm -f $new; exit 1; }\n" +
                check?.let { "if ! out=\$(PATH=\$PATH:/usr/sbin:/sbin; $it 2>&1); then if [ -e $old ]; then mv -f $old $file; else rm -f $file; fi; echo \"\$out\" >&2; exit 1; fi\n" }.orEmpty() +
                "rm -f $old",
        )
    }

    private fun Holder.missing(what: String, device: String) = DeviceException(
        ErrorCategory.MissingItem,
        if (kind == Kind.Netplan) "$NETPLAN has no $what of $device, and the other netplan files stay as they are" else "$name keeps no $what of $device",
    )

    private fun Holder.takes(route: Route) {
        if (kind != Kind.Ifupdown && route.table.any { !it.isDigit() }) throw DeviceException(ErrorCategory.ArgumentValue, "routing-table: ${route.table} is no number, and $name takes a table by its number")
    }

    private fun until(why: String) = DeviceException(ErrorCategory.Unsupported, "this change lasts until a reboot: $why")

    private fun valid(device: String) {
        if (!DEVICE.matches(device)) throw DeviceException(ErrorCategory.ArgumentValue, "interface: $device is no interface")
    }

    private fun prefixed(address: String) {
        if (!PREFIXED.matches(address)) throw DeviceException(ErrorCategory.ArgumentValue, "address: $address is not an address with its prefix, as 192.0.2.5/24")
    }

    private fun checked(destination: String, gateway: String, metric: String, table: String): Route {
        if (destination != "default" && !PREFIXED.matches(destination) && !ADDRESS.matches(destination)) throw DeviceException(ErrorCategory.ArgumentValue, "dst-address: $destination is not a network, as 192.0.2.0/24")
        if (gateway.isNotEmpty() && !ADDRESS.matches(gateway)) throw DeviceException(ErrorCategory.ArgumentValue, "gateway: $gateway is no address")
        if (metric.any { !it.isDigit() }) throw DeviceException(ErrorCategory.ArgumentValue, "metric: $metric is no number")
        if (table.isNotEmpty() && !DEVICE.matches(table)) throw DeviceException(ErrorCategory.ArgumentValue, "routing-table: $table is no table")
        return route(destination, gateway, metric, table)
    }

    private companion object {
        const val NETPLAN = "/etc/netplan/90-networkhelm.yaml"
        const val NETPLAN_CHECK = "netplan generate"
        const val NETWORKD = "/etc/systemd/network"
        const val IFUPDOWN_CHECK = "if command -v ifquery >/dev/null; then ifquery --list >/dev/null; fi"
        val MANAGERS = setOf("nmcli", "netplan", "networkctl", "ifup")
        val NM_OFF = setOf("disabled", "ignore", "link-local")
        val NM_MTU = mapOf("802-11-wireless" to "802-11-wireless.mtu", "wireguard" to "wireguard.mtu", "infiniband" to "infiniband.mtu", "ip-tunnel" to "ip-tunnel.mtu")
        val LINKS = mapOf("vlan" to "vlans", "bridge" to "bridges", "dummy" to "dummy-devices") + TUNNEL_KINDS.associateWith { "tunnels" }
        val DEVICE = Regex("[A-Za-z0-9_.@:-]+")
        val ADDRESS = Regex("[0-9A-Fa-f.:]+")
        val PREFIXED = Regex("[0-9A-Fa-f.:]+/\\d{1,3}")
    }
}

private const val INTERFACES = "/etc/network/interfaces"

private const val FILE = "--nh-file--"

private val blanks = Regex("\\s+")

private fun dump(paths: String) = "for f in $paths; do [ -f \"\$f\" ] && { echo \"$FILE \$f\"; cat \"\$f\"; echo; }; done; true"

private fun files(output: String): Map<String, String> =
    output.split("$FILE ").drop(1).associate { it.substringBefore('\n') to it.substringAfter('\n', "").removeSuffix("\n") }

private fun dev(text: String) = text.split(blanks).zipWithNext().firstOrNull { it.first == "dev" }?.second

private fun addressCommand(v6: Boolean, verb: String, address: String, device: String) = "ip ${if (v6) "-6" else "-4"} addr $verb $address dev $device"

// a route as a configuration has it: the destination with its prefix, the gateway, metric and table where set, main being none
private class Route(val destination: String, val gateway: String, val metric: String, val table: String) {
    val v6: Boolean get() = ':' in destination
    val default: Boolean get() = destination.endsWith("/0")

    // the route of a configuration is the one meant when destination, gateway and table agree, and the metric where both have one
    fun same(other: Route) = destination == other.destination && gateway == other.gateway && table == other.table &&
        (metric.isEmpty() || other.metric.isEmpty() || metric == other.metric)
}

private fun route(destination: String, gateway: String, metric: String, table: String, v6: Boolean = ':' in destination || ':' in gateway): Route {
    val network = when {
        destination.isEmpty() || destination == "default" -> if (v6) "::/0" else "0.0.0.0/0"
        '/' in destination -> destination
        else -> "$destination/${if (v6) 128 else 32}"
    }
    return Route(network.lowercase(), gateway.takeUnless { it == "0.0.0.0" || it == "::" }.orEmpty().lowercase(), metric, table.takeUnless { it == "main" || it == "254" }.orEmpty())
}

// 192.0.2.0/24 192.0.2.1 100 table=200, an entry of ipv4.routes as nmcli prints it: a word with = is an attribute, a number the metric
private fun nmRoute(entry: String, v6: Boolean): Route {
    val words = entry.split(' ').filter { it.isNotEmpty() }
    val plain = words.drop(1).filter { '=' !in it }
    val table = words.firstOrNull { it.startsWith("table=") }?.substringAfter('=').orEmpty()
    return route(words.firstOrNull().orEmpty(), plain.firstOrNull { word -> !word.all(Char::isDigit) }.orEmpty(), plain.firstOrNull { word -> word.all(Char::isDigit) }.orEmpty(), table, v6)
}

private fun Route.nm() = listOfNotNull(destination, gateway.ifEmpty { null }, metric.ifEmpty { null }, table.ifEmpty { null }?.let { "table=$it" }).joinToString(" ")

private fun Route.networkd() = listOfNotNull(
    "Destination" to destination,
    gateway.ifEmpty { null }?.let { "Gateway" to it },
    metric.ifEmpty { null }?.let { "Metric" to it },
    table.ifEmpty { null }?.let { "Table" to it },
)

private fun networkdRoute(entries: List<Pair<String, String>>): Route {
    fun value(key: String) = entries.lastOrNull { it.first == key }?.second.orEmpty()
    return route(value("Destination"), value("Gateway"), value("Metric"), value("Table"))
}

// netplan takes a route without a gateway as one of the link
private fun Route.netplan() = JsonObject(buildMap {
    put("to", JsonPrimitive(destination))
    if (gateway.isEmpty()) put("scope", JsonPrimitive("link")) else put("via", JsonPrimitive(gateway))
    metric.toLongOrNull()?.let { put("metric", JsonPrimitive(it)) }
    table.toLongOrNull()?.let { put("table", JsonPrimitive(it)) }
})

private fun netplanRoute(item: JsonElement): Route? {
    val values = (item as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: return null
    return route(values["to"].orEmpty(), values["via"].orEmpty(), values["metric"].orEmpty(), values["table"].orEmpty())
}

private fun Route.ip(verb: String, device: String) = "ip ${if (v6) "-6" else "-4"} route $verb $destination" + (if (gateway.isEmpty()) "" else " via $gateway") +
    " dev $device" + (if (metric.isEmpty()) "" else " metric $metric") + (if (table.isEmpty()) "" else " table $table")

private val HOOKS = setOf("up", "post-up", "pre-up", "down", "pre-down", "post-down")

// post-up ip -4 addr add 192.0.2.5/24 dev eth0
private fun hookAddress(option: String): String? {
    val words = option.split(' ')
    val at = words.indexOfFirst { it == "addr" || it == "address" }
    if (words.getOrNull(0) !in HOOKS || words.getOrNull(1) != "ip" || at < 0) return null
    return words.getOrNull(at + 2)
}

// post-up ip -4 route add 192.0.2.0/24 via 192.0.2.1 dev eth0 metric 100
private fun hookRoute(option: String): Route? {
    val words = option.split(' ')
    val at = words.indexOf("route")
    if (words.getOrNull(0) !in HOOKS || words.getOrNull(1) != "ip" || at < 0) return null
    val destination = words.getOrElse(at + 2) { "" }
    val named = words.drop(at + 3).zipWithNext().toMap()
    val gateway = named["via"].orEmpty()
    return route(destination, gateway, named["metric"].orEmpty(), named["table"].orEmpty(), "-6" in words || ':' in destination || ':' in gateway)
}

private fun hookMtu(option: String, device: String): Boolean {
    val words = option.split(' ')
    return words.getOrNull(0) in HOOKS && words.getOrNull(1) == "ip" && "link" in words && "mtu" in words && device in words
}

private val STANZA = Regex("(iface|mapping|auto|allow-\\S+|source|source-directory|rename|template|no-auto-down|no-scripts)(\\s.*)?")

private fun ifaceOf(line: String, device: String) = line.trim().split(blanks).let { it[0] == "iface" && it.getOrNull(1) == device }

private fun autoOf(line: String, device: String): Boolean {
    val words = line.trim().split(blanks)
    return (words[0] == "auto" || words[0] == "allow-auto" || words[0] == "allow-hotplug") && device in words.drop(1)
}

// the option lines of the iface stanzas of a device: they follow the iface line until the next stanza
private fun optionLines(lines: List<String>, device: String): List<Int> {
    var inside = false
    return lines.indices.filter { i ->
        val line = lines[i].trim()
        if (STANZA.matches(line)) {
            inside = ifaceOf(line, device)
            false
        } else {
            inside && line.isNotEmpty() && !line.startsWith("#")
        }
    }
}

// each option of the stanzas of a device, its words single spaced, kept, changed or dropped by edit; null when nothing changed
internal fun interfacesEdit(text: String, device: String, edit: (String) -> String?): String? {
    val lines = text.lines()
    val options = optionLines(lines, device).toSet()
    var changed = false
    val edited = lines.mapIndexedNotNull { i, line ->
        if (i !in options) return@mapIndexedNotNull line
        val option = line.trim().split(blanks).joinToString(" ")
        val result = edit(option)
        if (result != option) changed = true
        result?.let { if (it == option) line else line.takeWhile(Char::isWhitespace) + it }
    }
    return if (changed) edited.joinToString("\n") else null
}

// the options a device lacks, added after the last option of its first stanza; a device without a stanza gets one of the
// manual method, brought up at boot
internal fun interfacesWith(text: String, device: String, options: List<String>): String {
    val lines = text.trimEnd().lines().toMutableList()
    val first = lines.indexOfFirst { ifaceOf(it, device) }
    if (first < 0) {
        val head = text.trimEnd().let { if (it.isEmpty()) "" else "$it\n\n" }
        val auto = if (lines.any { autoOf(it, device) }) "" else "auto $device\n"
        return head + auto + "iface $device inet manual\n" + options.joinToString("") { "    $it\n" }
    }
    val present = optionLines(lines, device)
    val next = (first + 1 until lines.size).firstOrNull { STANZA.matches(lines[it].trim()) } ?: lines.size
    val own = present.filter { it in first until next }
    val indent = own.firstOrNull()?.let { lines[it].takeWhile(Char::isWhitespace) } ?: "    "
    val known = present.map { lines[it].trim().split(blanks).joinToString(" ") }
    lines.addAll((own.lastOrNull() ?: first) + 1, options.filter { it !in known }.map { indent + it })
    return lines.joinToString("\n") + "\n"
}

// auto brings an interface up at boot, and so does allow-hotplug once it appears
internal fun interfacesAuto(text: String, device: String, on: Boolean): String {
    val lines = text.trimEnd().lines()
    if (on) {
        if (lines.any { autoOf(it, device) }) return text
        val at = lines.indexOfFirst { ifaceOf(it, device) }
        if (at < 0) return interfacesWith(text, device, emptyList())
        return (lines.take(at) + "auto $device" + lines.drop(at)).joinToString("\n") + "\n"
    }
    return lines.mapNotNull { line ->
        if (!autoOf(line, device)) return@mapNotNull line
        val words = line.trim().split(blanks)
        val rest = words.drop(1).filter { it != device }
        if (rest.isEmpty()) null else "${words[0]} ${rest.joinToString(" ")}"
    }.joinToString("\n").trimEnd() + "\n"
}

// the stanzas of a device and its auto entries taken out; null when the text has no stanza of it
internal fun interfacesWithout(text: String, device: String): String? {
    val lines = text.lines()
    val options = optionLines(lines, device)
    val starts = lines.indices.filter { ifaceOf(lines[it], device) }
    if (starts.isEmpty()) return null
    val gone = mutableSetOf<Int>()
    for (start in starts) {
        val next = (start + 1 until lines.size).firstOrNull { STANZA.matches(lines[it].trim()) } ?: lines.size
        val end = options.lastOrNull { it in start until next } ?: start
        gone += start..end
        if (end + 1 < next && lines[end + 1].isBlank()) gone += end + 1
    }
    return interfacesAuto(lines.filterIndexed { i, _ -> i !in gone }.joinToString("\n"), device, false)
}

// the files the main interfaces file reads, by directory and name: source takes a pattern, source-directory the names run-parts takes
private fun interfacesSources(main: String): List<Pair<String, Regex>> = main.lines().mapNotNull { line ->
    val words = line.trim().split(blanks)
    val path = words.getOrNull(1)?.let { if (it.startsWith("/")) it else "/etc/network/$it" } ?: return@mapNotNull null
    when (words[0]) {
        "source" -> path.substringBeforeLast('/') to glob(path.substringAfterLast('/'))
        "source-directory" -> path.trimEnd('/') to Regex("[A-Za-z0-9_-]+")
        else -> null
    }
}

// where a stanza of NetworkHelm goes: a file of its own in a directory the main file sources, otherwise the main file
internal fun interfacesFile(main: String): String =
    interfacesSources(main).firstNotNullOfOrNull { (dir, names) -> listOf("networkhelm", "networkhelm.cfg").firstOrNull { names.matches(it) }?.let { "$dir/$it" } } ?: INTERFACES

private fun glob(pattern: String) = Regex(pattern.map { if (it == '*') ".*" else if (it == '?') "." else Regex.escape("$it") }.joinToString(""))

private fun entry(line: String): Pair<String, String>? {
    val text = line.trim()
    return if (text.startsWith("#") || text.startsWith(";") || text.startsWith("[") || '=' !in text) null else text.substringBefore('=').trim() to text.substringAfter('=').trim()
}

// a section of a systemd unit file with its header line; the one with no name holds what comes before the first header
private class Section(val name: String, val lines: List<String>) {
    val entries: List<Pair<String, String>> get() = lines.mapNotNull(::entry)
}

private fun sections(text: String): List<Section> {
    val parts = mutableListOf("" to mutableListOf<String>())
    for (line in text.trimEnd().lines()) {
        val header = line.trim()
        if (header.startsWith("[") && header.endsWith("]")) parts += header.drop(1).dropLast(1).trim() to mutableListOf(line) else parts.last().second += line
    }
    return parts.map { (name, lines) -> Section(name, lines) }.filter { it.name.isNotEmpty() || it.lines.any(String::isNotBlank) }
}

private fun List<Section>.text() = joinToString("\n\n") { it.lines.joinToString("\n").trim('\n') }.let { if (it.isBlank()) "" else "$it\n" }

// the names a .network file matches, Name= with nothing starting the list over
internal fun networkdNames(text: String): List<String> = sections(text).filter { it.name == "Match" }.flatMap { it.entries }.filter { it.first == "Name" }
    .fold(emptyList<String>()) { names, (_, value) -> if (value.isEmpty()) emptyList() else names + value.split(blanks) }

// a section added at the end unless one with the same entries is there
internal fun networkdWith(text: String, section: String, entries: List<Pair<String, String>>): String {
    if (sections(text).any { it.name == section && it.entries == entries }) return text
    val body = "[$section]\n" + entries.joinToString("") { (key, value) -> "$key=$value\n" }
    return if (text.isBlank()) body else text.trimEnd() + "\n\n" + body
}

// a key set in the last section of the name, taken out of the others
internal fun networkdSet(text: String, section: String, key: String, value: String): String {
    val all = sections(text)
    val last = all.lastOrNull { it.name == section } ?: return networkdWith(text, section, listOf(key to value))
    return all.map { part ->
        if (part.name != section) return@map part
        val kept = part.lines.filter { line -> entry(line)?.first != key }
        Section(part.name, if (part === last) kept.dropLastWhile(String::isBlank) + "$key=$value" else kept)
    }.text()
}

// the sections and the entries picked taken out, and a section left with no entry after it; null when nothing was picked
internal fun networkdWithout(text: String, section: (String, List<Pair<String, String>>) -> Boolean, line: (String, String, String) -> Boolean): String? {
    var picked = false
    val left = sections(text).mapNotNull { part ->
        if (part.name.isNotEmpty() && section(part.name, part.entries)) {
            picked = true
            return@mapNotNull null
        }
        val kept = part.lines.filterNot { raw -> entry(raw)?.let { (key, value) -> line(part.name, key, value) } == true }
        if (kept.size == part.lines.size) return@mapNotNull part
        picked = true
        Section(part.name, kept).takeIf { it.entries.isNotEmpty() }
    }
    return if (picked) left.text() else null
}

internal class NetplanDevice(val group: String, val id: String, val name: String, val renderer: String)

private val NETPLAN_GROUPS = setOf("ethernets", "bonds", "bridges", "vlans", "wifis", "tunnels", "vrfs", "dummy-devices", "virtual-ethernets", "modems", "nm-devices")

private val YAML_KEY = Regex("""(\s*(?:-\s+)?)([^\s#'"-][^:]*?|"[^"]*"|'[^']*')\s*:(?:\s+(.*))?""")

// the keys of block YAML, each with the path that leads to it and its value; a flow collection stays a value
private fun yamlKeys(text: String): List<Pair<List<String>, String>> {
    val path = mutableListOf<Pair<Int, String>>()
    return text.lines().mapNotNull { line ->
        val match = YAML_KEY.matchEntire(line.substringBefore(" #").trimEnd()) ?: return@mapNotNull null
        val indent = match.groupValues[1].length
        while (path.isNotEmpty() && path.last().first >= indent) path.removeAt(path.lastIndex)
        path += indent to match.groupValues[2].trim('"', '\'')
        path.map { it.second } to match.groupValues[3].trim().trim('"', '\'')
    }
}

private fun jsonKeys(element: JsonElement, path: List<String>): List<Pair<List<String>, String>> =
    (element as? JsonObject)?.flatMap { (key, value) -> listOf((path + key) to (value as? JsonPrimitive)?.contentOrNull.orEmpty()) + jsonKeys(value, path + key) }.orEmpty()

// the devices netplan files define: group and id, the name the device gets from set-name or match, empty when only a MAC
// address picks it, and the renderer that writes it
internal fun parseNetplan(files: Collection<String>): List<NetplanDevice> {
    var global = ""
    val renderers = mutableMapOf<String, String>()
    val ids = linkedSetOf<String>()
    val setNames = mutableMapOf<String, String>()
    val matchNames = mutableMapOf<String, String>()
    val matched = mutableSetOf<String>()
    for (text in files) {
        // flow YAML that is not JSON is beyond this reader, as it is beyond yamlKeys
        val json = if (text.trimStart().startsWith("{")) {
            try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                null
            }
        } else {
            null
        }
        for ((path, value) in json?.let { jsonKeys(it, emptyList()) } ?: yamlKeys(text)) {
            if (path.firstOrNull() != "network") continue
            if (path.size == 2 && path[1] == "renderer") global = value
            if (path.size < 3 || path[1] !in NETPLAN_GROUPS) continue
            val device = "${path[1]}/${path[2]}"
            when {
                path.size == 3 && path[2] == "renderer" -> renderers[path[1]] = value
                path.size == 3 -> ids += device
                path.size == 4 && path[3] == "renderer" -> renderers[device] = value
                path.size == 4 && path[3] == "set-name" -> setNames[device] = value
                path.size == 4 && path[3] == "match" -> matched += device
                path.size == 5 && path[3] == "match" && path[4] == "name" -> matchNames[device] = value
            }
        }
    }
    return ids.map { device ->
        val group = device.substringBefore('/')
        val id = device.substringAfter('/')
        NetplanDevice(group, id, setNames[device] ?: matchNames[device] ?: if (device in matched) "" else id, renderers[device] ?: renderers[group] ?: global.ifEmpty { "networkd" })
    }
}

private fun netplanHolds(name: String, device: String) = name == device || ('*' in name || '?' in name) && glob(name).matches(device)

private val pretty = Json { prettyPrint = true }

// the netplan file of NetworkHelm with one device changed: JSON is YAML to netplan and reads back without a YAML parser. An
// ethernet port left with nothing goes, as does a file left with no device
internal fun netplanWith(text: String, group: String, id: String, change: (JsonObject?) -> JsonObject?): String {
    val file = try {
        if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
    } catch (e: SerializationException) {
        throw DeviceException(ErrorCategory.Api, "the netplan file of NetworkHelm is no longer the JSON it writes: ${e.message}", e)
    }
    val network = file["network"] as? JsonObject ?: JsonObject(emptyMap())
    val devices = network[group] as? JsonObject ?: JsonObject(emptyMap())
    val device = change(devices[id] as? JsonObject)?.takeUnless { it.isEmpty() && group == "ethernets" }
    val left = if (device == null) devices - id else devices + (id to device)
    val changed = (if (left.isEmpty()) network - group else network + (group to JsonObject(left))) - "version"
    if (changed.isEmpty()) return ""
    return pretty.encodeToString(JsonObject.serializer(), JsonObject(file + ("network" to JsonObject(mapOf<String, JsonElement>("version" to JsonPrimitive(2)) + changed)))) + "\n"
}

private fun JsonObject?.withItem(key: String, item: JsonElement): JsonObject {
    val items = (this?.get(key) as? JsonArray).orEmpty()
    return JsonObject(orEmpty() + (key to JsonArray((items + item).distinct())))
}

// the object with the items drop picks taken out of a list; null when it picks none
private fun JsonObject?.withoutItems(key: String, drop: (JsonElement) -> Boolean): JsonObject? {
    val items = (this?.get(key) as? JsonArray).orEmpty()
    val left = items.filterNot(drop)
    if (left.size == items.size) return null
    return JsonObject(if (left.isEmpty()) orEmpty() - key else orEmpty() + (key to JsonArray(left)))
}
