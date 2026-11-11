package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.duration
import com.vasmarfas.networkhelm.search.span
import com.vasmarfas.networkhelm.ssh.RemoteFile
import com.vasmarfas.networkhelm.ssh.publicKeyBits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Instant

internal class Live(
    val fields: List<String>,
    val read: suspend () -> List<Item>,
    val settings: Boolean = false,
    val writable: List<String> = emptyList(),
    val arguments: List<Argument> = emptyList(),
    val add: (suspend (Map<String, String>) -> String)? = null,
    val set: (suspend (String, Map<String, String?>) -> Unit)? = null,
    val remove: (suspend (List<String>) -> Unit)? = null,
    val move: (suspend (List<String>, String?) -> Unit)? = null,
    val commands: List<String> = emptyList(),
    val run: (suspend (String, Map<String, String>) -> Unit)? = null,
    val events: (() -> Flow<Unit>)? = null,
)

internal class LiveLists(private val ubus: Ubus, private val uci: Uci, private val facts: OpenWrtFacts) {
    private val shell = ubus as? SshUbus
    private val objects = facts.objects
    private val hostapd = objects.filter { it.startsWith("hostapd.") }
    private var offset: Long? = null
    private val lists = mapOf(
        "status/log" to Live(listOf("time", "topics", "message"), ::log),
        "status/dhcp-leases" to Live(
            listOf("address", "mac-address", "host-name", "client-id", "expires"),
            ::leases,
            events = { if ("dnsmasq" in objects) ubus.subscribe("dnsmasq").map { } else emptyFlow() },
        ),
        "status/addresses" to Live(listOf("address", "interface", "device"), ::addresses, events = { ubus.listen("network.interface").map { } }),
        "status/prefixes" to Live(listOf("prefix", "interface", "valid-lifetime", "preferred-lifetime", "source"), ::prefixes, events = { ubus.listen("network.interface").map { } }),
        "status/interfaces" to Live(
            listOf("name", "running", "uptime", "l3-device", "ipv4-address", "ipv6-address"),
            ::interfaces,
            events = { ubus.listen("network.interface").map { } },
        ),
        "status/wireless-clients" to Live(
            listOf("mac-address", "interface", "signal", "authorized", "rx-bytes", "tx-bytes", "connected-time"),
            ::stations,
            commands = listOf("disconnect"),
            run = ::disconnect,
            events = { hostapd.map { ubus.subscribe(it) }.merge().map { } },
        ),
        "status/devices" to Live(listOf("name", "type", "devtype", "running", "mtu", "mac-address", "rx-byte", "tx-byte"), ::devices),
        "status/neighbors" to Live(listOf("address", "mac-address", "interface", "flags"), ::neighbors),
        "status/neighbors6" to Live(listOf("address", "mac-address", "interface", "status", "router"), ::neighbors6),
        "status/routes" to Live(ROUTE, { routes("-4") }),
        "status/routes6" to Live(ROUTE, { routes("-6") }),
        "status/processes" to Live(
            listOf("pid", "ppid", "user", "stat", "vsz", "memory", "cpu", "command"),
            ::processes,
            commands = signals.keys.toList(),
            run = ::signal,
        ),
        "status/kernel-log" to Live(listOf("time", "topics", "message"), ::kernelLog),
        "status/firewall" to Live(listOf("family", "table", "chain", "rule", "packets", "bytes", "comment"), ::ruleset),
        "status/connections" to Live(
            listOf(
                "protocol", "src-address", "src-port", "dst-address", "dst-port", "reply-src-address", "reply-src-port", "reply-dst-address", "reply-dst-port",
                "tcp-state", "flags", "timeout", "packets", "bytes", "reply-packets", "reply-bytes", "connection-mark", "zone",
            ),
            ::connections,
        ),
        "status/services" to Live(
            listOf("name", "start", "stop", "enabled", "running"),
            ::services,
            commands = listOf("start", "stop", "restart", "reload", "enable", "disable"),
            run = ::initScripts,
        ),
        "status/packages" to Live(listOf("name", "version"), ::packages, writable = listOf("name"), add = ::install, remove = ::uninstall),
        "status/files" to Live(
            listOf("name", "type", "size", "last-modified"),
            ::files,
            remove = ::removeFiles,
            commands = listOf("backup", "upgrade", "sftp"),
            run = ::fileCommand,
        ),
        "status/scheduler" to Live(TASK, ::tasks, writable = TASK, add = ::addTask, set = ::setTask, remove = ::removeTasks),
        "status/ssh-keys" to Live(listOf("user", "key-type", "bits", "key-owner", "key"), ::sshKeys, writable = listOf("user", "key"), add = ::addSshKey, remove = ::removeSshKeys),
        "status/resource" to Live(
            listOf(
                "uptime", "version", "revision", "board-name", "architecture-name", "cpu", "kernel", "load", "load-average", "free-memory", "total-memory",
                "free-hdd-space", "total-hdd-space", "date", "time",
            ),
            ::resource,
            settings = true,
            commands = listOf("sync-time"),
            run = { _, _ -> syncTime() },
        ),
    ) + listOfNotNull(
        ("status/dhcp6-leases" to Live(listOf("address", "duid", "iaid", "host-name", "interface", "life-time"), ::leases6)).takeIf { "luci-rpc" in objects || "dhcp" in objects },
        ("status/ddns" to Live(listOf("name", "ip", "last-update", "next-update", "next-check", "pid"), ::ddns)).takeIf { "luci.ddns" in objects },
        ("status/upnp" to Live(listOf("client", "address", "port", "external-port", "protocol", "expires", "description"), ::portMaps, remove = ::removePortMaps))
            .takeIf { "luci.upnp" in objects },
        ("status/wireguard" to Live(listOf("interface", "name", "public-key", "endpoint", "allowed-address", "last-handshake", "rx", "tx", "persistent-keepalive"), ::peers))
            .takeIf { "luci.wireguard" in objects },
        ("status/wireguard-interfaces" to Live(listOf("name", "public-key", "listen-port"), ::wireguardInterfaces)).takeIf { "luci.wireguard" in objects },
        ("status/wireguard-key" to Live(listOf("private-key", "public-key"), ::keyPair, settings = true)).takeIf { "luci.wireguard" in objects },
    ) + Mixomo(ubus, objects) { local(it + shift()) }.lists + Rutos(shell, facts.tools).lists

    fun has(path: MenuPath) = path.toString() in lists

    fun schema(path: MenuPath): MenuSchema? = lists[path.toString()]?.let { live ->
        val commands = listOf("print") +
            listOfNotNull("add".takeIf { live.add != null }, "set".takeIf { live.set != null }, "remove".takeIf { live.remove != null }, "move".takeIf { live.move != null }) +
            live.commands
        val arguments = live.fields.map { field -> live.arguments.firstOrNull { it.name == field } ?: Argument(field, ValueType.Text(), readOnly = field !in live.writable) }
        MenuSchema(path, if (live.settings) NodeKind.Settings else NodeKind.List, emptyList(), commands, arguments)
    }

    suspend fun read(path: MenuPath): List<Item> = numbered(live(path).read())

    fun watched(path: MenuPath) = live(path).events != null

    fun changes(path: MenuPath): Flow<Unit> = (live(path).events?.invoke() ?: emptyFlow()).catch { }

    fun follow(path: MenuPath, polled: () -> Flow<ListEvent>): Flow<ListEvent>? {
        if (path.toString() != "status/log") return null
        return flow {
            val kept = ArrayDeque(read(path))
            emit(ListEvent.Snapshot(kept.toList()))
            val shift = shift()
            emitAll(
                ubus.subscribe("log").transform { message ->
                    val item = logItem(message["message"] as? JsonObject ?: return@transform, shift)
                    kept.addLast(item)
                    emit(ListEvent.Upsert(item))
                    if (kept.size > LINES) emit(ListEvent.Removed(kept.removeFirst().id))
                }.catch { emitAll(polled()) },
            )
        }
    }

    suspend fun add(path: MenuPath, values: Map<String, String>): String = (live(path).add ?: unsupported(path))(values)

    suspend fun set(path: MenuPath, id: String, changes: Map<String, String?>) = (live(path).set ?: unsupported(path))(id, changes)

    suspend fun remove(path: MenuPath, ids: List<String>) = (live(path).remove ?: unsupported(path))(ids)

    suspend fun move(path: MenuPath, ids: List<String>, before: String?) = (live(path).move ?: unsupported(path))(ids, before)

    suspend fun run(path: MenuPath, command: String, arguments: Map<String, String>) = (live(path).run ?: unsupported(path))(command, arguments)

    private fun live(path: MenuPath) = lists.getValue(path.toString())

    private fun unsupported(path: MenuPath): Nothing = throw DeviceException(ErrorCategory.Unsupported, "$path cannot be changed")

    private fun numbered(items: List<Item>): List<Item> {
        val seen = mutableMapOf<String, Int>()
        return items.map { item ->
            val count = (seen[item.id] ?: 0) + 1
            seen[item.id] = count
            if (count == 1) item else item.copy(id = "${item.id} #$count")
        }
    }

    private suspend fun log(): List<Item> {
        val shift = shift()
        return ubus.call("log", "read", JsonObject(mapOf("lines" to JsonPrimitive(LINES), "stream" to JsonPrimitive(false)))).objects("log").map { logItem(it, shift) }
    }

    private fun logItem(line: JsonObject, shift: Long) = Item(
        line.text("id"),
        buildMap {
            line.number("time")?.let { put("time", local(it / 1000 + shift)) }
            line.number("priority")?.toInt()?.let { priority -> facilities.getOrNull(priority / 8)?.let { put("topics", "$it.${levels[priority % 8]}") } }
            put("message", line.text("msg"))
        },
    )

    private suspend fun leases(): List<Item> {
        val files = uci.sections("dhcp").filter { it.type == "dnsmasq" }.map { it.options["leasefile"] ?: "/tmp/dhcp.leases" }.distinct()
        val shift = shift()
        return files.flatMap { file(it).lines() }.map { it.split(' ') }.filter { it.size >= 4 }.map { lease ->
            Item(
                lease[2],
                buildMap {
                    put("address", lease[2])
                    put("mac-address", lease[1])
                    put("host-name", lease[3])
                    lease.getOrNull(4)?.let { put("client-id", it) }
                    val expiry = lease[0].toLongOrNull()
                    if (expiry == null) put("expires", lease[0]) else if (expiry > 0) put("expires", local(expiry + shift))
                },
            )
        }
    }

    private suspend fun addresses(): List<Item> = ubus.call("network.interface", "dump").objects("interface").flatMap { iface ->
        val name = iface.text("interface")
        val assigned = iface.objects("ipv6-prefix-assignment").mapNotNull { it["local-address"] as? JsonObject }
        (iface.objects("ipv4-address") + iface.objects("ipv6-address") + assigned).map { address ->
            val value = "${address.text("address")}/${address.text("mask")}"
            Item("$name $value", mapOf("address" to value, "interface" to name, "device" to iface.text("l3_device")))
        }
    }

    private suspend fun prefixes(): List<Item> = ubus.call("network.interface", "dump").objects("interface").flatMap { iface ->
        val name = iface.text("interface")
        val entries = iface.objects("ipv6-prefix-assignment").map { it to "assigned" } + iface.objects("ipv6-address").map { it to "address" }
        entries.mapNotNull { (entry, source) ->
            val prefix = network6(entry.text("address"), entry.number("mask")?.toInt() ?: return@mapNotNull null) ?: return@mapNotNull null
            Item(
                "$name $prefix",
                buildMap {
                    put("prefix", prefix)
                    put("interface", name)
                    entry.number("valid")?.let { put("valid-lifetime", duration(it)) }
                    entry.number("preferred")?.let { put("preferred-lifetime", duration(it)) }
                    put("source", source)
                },
            )
        }.distinctBy { it.id }
    }

    private suspend fun leases6(): List<Item> = if ("luci-rpc" in objects) {
        ubus.call("luci-rpc", "getDHCPLeases").objects("dhcp6_leases").map { lease ->
            val addresses = lease["ip6addrs"]?.jsonArray?.map { it.plain() } ?: listOfNotNull(lease.text("ip6addr").takeIf { it.isNotEmpty() })
            lease6(lease.text("interface"), lease, addresses, lease.number("expires"))
        }
    } else {
        (ubus.call("dhcp", "ipv6leases")["device"] as? JsonObject).orEmpty().flatMap { (device, value) ->
            (value as? JsonObject)?.objects("leases").orEmpty().map { lease -> lease6(device, lease, lease.objects("ipv6-addr").map { it.text("address") }, lease.number("valid")) }
        }
    }

    private fun lease6(device: String, lease: JsonObject, addresses: List<String>, seconds: Long?) = Item(
        "$device ${lease.text("duid")} ${lease.text("iaid")}",
        buildMap {
            put("interface", device)
            put("duid", lease.text("duid"))
            lease.text("iaid").takeIf { it.isNotEmpty() }?.let { put("iaid", it) }
            lease.text("hostname").takeIf { it.isNotEmpty() && it != "-" }?.let { put("host-name", it) }
            put("address", addresses.joinToString(","))
            seconds?.takeIf { it > 0 }?.let { put("life-time", duration(it)) }
        },
    )

    private suspend fun interfaces(): List<Item> = ubus.call("network.interface", "dump").objects("interface").map { iface ->
        val name = iface.text("interface")
        val assigned = iface.objects("ipv6-prefix-assignment").mapNotNull { it["local-address"] as? JsonObject }
        Item(
            name,
            buildMap {
                put("name", name)
                put("running", iface.text("up"))
                iface.number("uptime")?.let { put("uptime", duration(it)) }
                put("l3-device", iface.text("l3_device"))
                put("ipv4-address", iface.objects("ipv4-address").joinToString(",") { "${it.text("address")}/${it.text("mask")}" })
                put("ipv6-address", (iface.objects("ipv6-address") + assigned).joinToString(",") { "${it.text("address")}/${it.text("mask")}" })
            },
        )
    }

    private suspend fun devices(): List<Item> = ubus.call("network.device", "status").map { (name, value) ->
        val device = value as? JsonObject ?: JsonObject(emptyMap())
        val counters = device["statistics"] as? JsonObject
        Item(
            name,
            buildMap {
                put("name", name)
                put("type", device.text("type"))
                put("devtype", device.text("devtype"))
                put("running", device.text("up"))
                device.number("mtu")?.let { put("mtu", "$it") }
                put("mac-address", device.text("macaddr"))
                counters?.number("rx_bytes")?.let { put("rx-byte", "$it") }
                counters?.number("tx_bytes")?.let { put("tx-byte", "$it") }
            }.filterValues { it.isNotEmpty() },
        )
    }

    // hostapd does not tell how long a station has been on (25.12), iwinfo does, as LuCI takes it
    private suspend fun stations(): List<Item> = hostapd.flatMap { obj ->
        val iface = obj.removePrefix("hostapd.")
        val connected = connectedTimes(iface)
        (ubus.call(obj, "get_clients")["clients"] as? JsonObject).orEmpty().map { (mac, value) ->
            val client = value as? JsonObject ?: JsonObject(emptyMap())
            val bytes = client["bytes"] as? JsonObject
            Item(
                "$iface $mac",
                buildMap {
                    put("mac-address", mac)
                    put("interface", iface)
                    client.number("signal")?.let { put("signal", "$it") }
                    put("authorized", client.text("authorized"))
                    bytes?.number("rx")?.let { put("rx-bytes", "$it") }
                    bytes?.number("tx")?.let { put("tx-bytes", "$it") }
                    connected[mac.lowercase()]?.let { put("connected-time", duration(it)) }
                },
            )
        }
    }

    private suspend fun connectedTimes(device: String): Map<String, Long> {
        if ("iwinfo" !in objects) return emptyMap()
        val stations = try {
            ubus.call("iwinfo", "assoclist", JsonObject(mapOf("device" to JsonPrimitive(device))))["results"] as? JsonArray
        } catch (e: UbusException) {
            null
        }
        return stations.orEmpty().filterIsInstance<JsonObject>().mapNotNull { station -> station.number("connected_time")?.let { station.text("mac").lowercase() to it } }.toMap()
    }

    private suspend fun disconnect(command: String, arguments: Map<String, String>) {
        arguments["numbers"].orEmpty().split(",").filter { it.isNotEmpty() }.forEach { id ->
            val (iface, mac) = id.split(' ', limit = 2)
            val reason = mapOf("addr" to JsonPrimitive(mac), "reason" to JsonPrimitive(5), "deauth" to JsonPrimitive(true), "ban_time" to JsonPrimitive(0))
            ubus.call("hostapd.$iface", "del_client", JsonObject(reason))
        }
    }

    private suspend fun ddns(): List<Item> = ubus.call("luci.ddns", "get_services_status").map { (name, value) ->
        val service = value as? JsonObject ?: JsonObject(emptyMap())
        val fields = mapOf("ip" to "ip", "last_update" to "last-update", "next_update" to "next-update", "next_check" to "next-check", "pid" to "pid")
        Item(name, mapOf("name" to name) + fields.mapNotNull { (key, field) -> service[key]?.takeIf { it !is JsonNull }?.let { field to it.plain() } })
    }

    private suspend fun portMaps(): List<Item> = ubus.call("luci.upnp", "get_status").objects("rules").map { rule ->
        Item(
            rule.text("num"),
            buildMap {
                put("client", rule.text("host_hint"))
                put("address", rule.text("intaddr"))
                put("port", rule.text("intport"))
                put("external-port", rule.text("extport"))
                put("protocol", rule.text("proto"))
                rule.number("expires")?.takeIf { it > 0 }?.let { put("expires", duration(it)) }
                put("description", rule.text("descr"))
            }.filterValues { it.isNotEmpty() },
        )
    }

    private suspend fun removePortMaps(ids: List<String>) {
        ids.sortedByDescending { it.toIntOrNull() ?: 0 }.forEach { ubus.call("luci.upnp", "delete_rule", JsonObject(mapOf("token" to JsonPrimitive(it)))) }
    }

    private suspend fun peers(): List<Item> {
        val shift = shift()
        return ubus.call("luci.wireguard", "getWgInstances").flatMap { (iface, value) ->
            (value as? JsonObject)?.objects("peers").orEmpty().map { peer ->
                Item(
                    "$iface ${peer.text("public_key")}",
                    buildMap {
                        put("interface", iface)
                        put("name", peer.text("name"))
                        put("public-key", peer.text("public_key"))
                        put("endpoint", peer.text("endpoint").takeIf { it != "(none)" }.orEmpty())
                        put("allowed-address", peer["allowed_ips"]?.jsonArray.orEmpty().joinToString(",") { it.plain() })
                        peer.text("latest_handshake").toLongOrNull()?.let { put("last-handshake", if (it > 0) local(it + shift) else "0") }
                        put("rx", peer.text("transfer_rx"))
                        put("tx", peer.text("transfer_tx"))
                        put("persistent-keepalive", peer.text("persistent_keepalive").takeIf { it != "off" }.orEmpty())
                    }.filterValues { it.isNotEmpty() },
                )
            }
        }
    }

    private suspend fun wireguardInterfaces(): List<Item> = ubus.call("luci.wireguard", "getWgInstances").mapNotNull { (iface, value) ->
        val instance = value as? JsonObject ?: return@mapNotNull null
        Item(iface, mapOf("name" to iface, "public-key" to instance.text("public_key"), "listen-port" to instance.text("listen_port")).filterValues { it.isNotEmpty() })
    }

    private suspend fun keyPair(): List<Item> {
        val keys = ubus.call("luci.wireguard", "generateKeyPair")["keys"] as? JsonObject ?: return emptyList()
        return listOf(Item("keys", mapOf("private-key" to keys.text("priv"), "public-key" to keys.text("pub"))))
    }

    private suspend fun files(): List<Item> {
        val shift = shift()
        return FILES.flatMap { directory ->
            entries(directory).map { file ->
                val name = "${directory.drop(1)}/${file.name}"
                Item(name, mapOf("name" to name, "type" to if (file.directory) "directory" else "file", "size" to "${file.size}", "last-modified" to local(file.modifiedEpochSeconds + shift)))
            }
        }
    }

    private suspend fun entries(directory: String): List<RemoteFile> {
        if ("file" !in objects) return listing(shell?.sh("ls -la --full-time ${quote(directory)}")?.output.orEmpty())
        return ubus.call("file", "list", JsonObject(mapOf("path" to JsonPrimitive(directory)))).objects("entries").map {
            RemoteFile(it.text("name"), it.number("size") ?: 0, it.text("type") == "directory", it.number("mtime") ?: 0)
        }
    }

    private suspend fun removeFiles(names: List<String>) = names.forEach { name ->
        val shell = shell
        when {
            "file" in objects -> RpcdFiles(ubus).remove("/$name")
            shell != null -> {
                val removed = shell.sh("if [ -d ${quote("/$name")} ] && [ ! -L ${quote("/$name")} ]; then rmdir ${quote("/$name")}; else rm -f ${quote("/$name")}; fi")
                if (removed.exitStatus != 0) throw DeviceException(ErrorCategory.General, removed.output.trim())
            }
            else -> throw DeviceException(ErrorCategory.Unsupported, "files of this router cannot be removed without SSH")
        }
    }

    private suspend fun fileCommand(command: String, arguments: Map<String, String>) {
        when (command) {
            "backup" -> backup()
            "upgrade" -> upgrade(arguments["numbers"].orEmpty())
            else -> install(mapOf("name" to "openssh-sftp-server"))
        }
    }

    private suspend fun backup() {
        val date = ubus.call("system", "info").number("localtime")?.let { local(it).substringBefore(' ') }
        command("/sbin/sysupgrade", listOf("-b", "/tmp/backup-${facts.board.text("hostname")}-$date.tar.gz"))
    }

    private suspend fun command(tool: String, params: List<String>): String {
        val shell = shell
        if (shell != null) {
            val run = shell.sh((listOf(tool) + params).joinToString(" ") { quote(it) })
            if (run.exitStatus != 0) throw DeviceException(ErrorCategory.General, run.output.trim())
            return run.output
        }
        val run = ubus.call("file", "exec", JsonObject(mapOf("command" to JsonPrimitive(tool), "params" to JsonArray(params.map(::JsonPrimitive)))))
        if (run.number("code") != 0L) throw DeviceException(ErrorCategory.General, run.text("stderr").ifBlank { run.text("stdout") }.trim())
        return run.text("stdout")
    }

    private suspend fun upgrade(names: String) {
        val name = names.split(",").filter { it.isNotEmpty() }.singleOrNull() ?: throw DeviceException(ErrorCategory.ArgumentValue, "choose one image")
        val image = "/$name"
        val check = ubus.call("system", "validate_firmware_image", JsonObject(mapOf("path" to JsonPrimitive(image))))
        if (check.text("valid") != "true") throw DeviceException(ErrorCategory.ArgumentValue, "$name is not an image for this router: ${check["tests"] ?: check}")
        val shell = shell
        if (shell != null) {
            // in the background, as sysupgrade ends dropbear and the shell with it; true keeps & apart from the ; that sh puts after a command
            shell.sh("(sleep 1; sysupgrade ${quote(image)}) >/dev/null 2>&1 & true")
        } else {
            ubus.call("file", "exec", JsonObject(mapOf("command" to JsonPrimitive("/sbin/sysupgrade"), "params" to JsonArray(listOf(JsonPrimitive(image))))))
        }
    }

    private suspend fun neighbors(): List<Item> = file("/proc/net/arp").lines().drop(1).map { it.trim().split(blanks) }.filter { it.size >= 6 }.map { row ->
        Item("${row[0]} ${row[5]}", mapOf("address" to row[0], "mac-address" to row[3], "interface" to row[5], "flags" to row[2]))
    }

    private suspend fun neighbors6(): List<Item> = command("/sbin/ip", listOf("-6", "neigh", "show")).lines().map { it.trim().split(blanks) }.filter { it.size > 1 }.map { words ->
        fun after(word: String) = words.indexOf(word).takeIf { it >= 0 }?.let { words.getOrNull(it + 1) }
        Item(
            "${words[0]} ${after("dev").orEmpty()}",
            buildMap {
                put("address", words[0])
                after("dev")?.let { put("interface", it) }
                after("lladdr")?.let { put("mac-address", it) }
                put("status", words.last())
                if ("router" in words) put("router", "true")
            },
        )
    }

    private suspend fun routes(family: String): List<Item> = command("/sbin/ip", listOf(family, "route", "show", "table", "all")).lines().map { it.trim().split(blanks) }
        .filter { it.isNotEmpty() && it[0].isNotEmpty() }.map { words ->
            val typed = words[0] in routeTypes
            val start = if (typed) 1 else 0
            val values = buildMap {
                put("type", if (typed) words[0] else "unicast")
                put("dst-address", words.getOrElse(start) { "" })
                put("table", "main")
                var index = start + 1
                while (index < words.size) {
                    val name = routeWords[words[index]]
                    if (name != null && index + 1 < words.size) {
                        put(name, words[index + 1])
                        index += 2
                    } else {
                        index++
                    }
                }
            }
            Item(words.joinToString(" "), values)
        }

    private suspend fun processes(): List<Item> {
        val processes = if ("luci" in objects) {
            ubus.call("luci", "getProcessList").objects("result").map { process ->
                val values = process.entries.associate { (key, value) -> (processFields[key] ?: key.lowercase()) to value.plain().trim() }
                Item(values["pid"].orEmpty(), values)
            }
        } else {
            command("/bin/busybox", listOf("top", "-bn1")).lines().mapNotNull { topLine.matchEntire(it)?.groupValues?.drop(1) }.map { words ->
                Item(words[0], processFields.values.zip(words).toMap())
            }
        }
        return processes.sortedBy { it.id.toIntOrNull() }
    }

    private suspend fun signal(command: String, arguments: Map<String, String>) =
        arguments["numbers"].orEmpty().split(",").filter { it.isNotEmpty() }.forEach { pid -> command("/bin/kill", listOf(signals.getValue(command), pid)) }

    private suspend fun kernelLog(): List<Item> = command("/bin/dmesg", listOf("-r")).lines().filter { it.isNotBlank() }.mapIndexed { index, line ->
        val match = kernelLine.matchEntire(line.trim())
        Item(
            "$index",
            buildMap {
                val priority = match?.groupValues?.get(1)?.toIntOrNull()
                if (priority != null) put("topics", "${facilities.getOrElse(priority / 8) { "kern" }}.${levels[priority % 8]}")
                match?.groupValues?.get(2)?.let { put("time", it) }
                put("message", match?.groupValues?.get(3) ?: line)
            },
        )
    }

    private suspend fun ruleset(): List<Item> {
        val text = command("/usr/sbin/nft", listOf("--terse", "--json", "list", "ruleset"))
        val entries = (Json.parseToJsonElement(text) as? JsonObject)?.get("nftables")?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
        fun chainOf(entry: JsonObject, name: String) = listOf("family", "table", name).joinToString(" ") { entry.text(it) }
        val policies = entries.mapNotNull { it["chain"] as? JsonObject }.filter { it.text("policy").isNotEmpty() }.associateBy { chainOf(it, "name") }.toMutableMap()
        val rows = mutableListOf<Item>()
        fun close(chain: String?) {
            val policy = policies.remove(chain) ?: return
            rows += Item(
                "$chain policy",
                mapOf("family" to policy.text("family"), "table" to policy.text("table"), "chain" to policy.text("name"), "rule" to "policy ${policy.text("policy")}"),
            )
        }
        var previous: String? = null
        for (rule in entries.mapNotNull { it["rule"] as? JsonObject }) {
            val chain = chainOf(rule, "chain")
            if (chain != previous) close(previous)
            previous = chain
            val expressions = rule["expr"]?.jsonArray.orEmpty()
            val counter = expressions.firstNotNullOfOrNull { (it as? JsonObject)?.get("counter") as? JsonObject }
            rows += Item(
                "$chain ${rule.text("handle")}",
                buildMap {
                    for (field in listOf("family", "table", "chain")) put(field, rule.text(field))
                    put("rule", nftRule(expressions))
                    counter?.let {
                        put("packets", it.text("packets"))
                        put("bytes", it.text("bytes"))
                    }
                    rule.text("comment").takeIf { it.isNotEmpty() }?.let { put("comment", it) }
                },
            )
        }
        close(previous)
        policies.keys.toList().forEach(::close)
        return rows
    }

    // rpcd reads only the first page of a /proc file, so without SSH the table of LuCI is used
    private suspend fun connections(): List<Item> {
        if (shell == null && "luci" in objects) {
            return ubus.call("luci", "getConntrackList").objects("result").map { flow ->
                val values = flow.entries.associate { (key, value) -> (luciConntrack[key] ?: key) to value.plain() }
                Item(listOf("protocol", "src-address", "src-port", "dst-address", "dst-port").mapNotNull { values[it] }.joinToString(" "), values)
            }
        }
        return file("/proc/net/nf_conntrack").lines().map { it.trim().split(blanks) }.filter { it.size > 5 }.map(::conntrackFlow)
    }

    private fun conntrackFlow(words: List<String>): Item {
        val values = mutableMapOf("family" to words[0], "protocol" to words[2], "timeout" to words[4])
        val flags = mutableListOf<String>()
        for (word in words.drop(5)) {
            val pair = word.split('=', limit = 2)
            val name = conntrack[pair[0]] ?: pair[0]
            when {
                pair.size == 2 -> values[if (name in values) "reply-$name" else name] = pair[1]
                word.startsWith("[") -> flags += word.trim('[', ']')
                else -> values["tcp-state"] = word
            }
        }
        if (flags.isNotEmpty()) values["flags"] = flags.joinToString(",")
        return Item(listOf("protocol", "src-address", "src-port", "dst-address", "dst-port", "type", "code", "id").mapNotNull { values[it] }.joinToString(" "), values)
    }

    private suspend fun services(): List<Item> {
        val shell = shell
        if ("rc" in objects || shell == null) {
            return ubus.call("rc", "list").map { (name, state) -> Item(name, mapOf("name" to name) + state.jsonObject.mapValues { it.value.plain() }) }
        }
        val printed = shell.sh("(cd /etc/init.d && for n in *; do echo \"\$n \$(sed -n 's/^START=//p' \"\$n\" | head -n 1) \$(sed -n 's/^STOP=//p' \"\$n\" | head -n 1)\"; done); echo; ls /etc/rc.d").output
        val enabled = printed.substringAfter("\n\n", "").lines().filter { it.startsWith('S') }.map { it.drop(3) }.toSet()
        val running = ubus.call("service", "list").filterValues { service ->
            ((service as? JsonObject)?.get("instances") as? JsonObject)?.values?.any { (it as? JsonObject)?.get("running")?.plain() == "true" } == true
        }.keys
        return printed.substringBefore("\n\n").lines().filter { it.isNotBlank() }.map { line ->
            val (name, start, stop) = line.split(' ')
            Item(name, buildMap {
                put("name", name)
                if (start.isNotEmpty()) put("start", start)
                if (stop.isNotEmpty()) put("stop", stop)
                put("enabled", "${name in enabled}")
                put("running", "${name in running}")
            })
        }
    }

    private suspend fun initScripts(command: String, arguments: Map<String, String>) {
        val shell = shell
        arguments["numbers"].orEmpty().split(",").filter { it.isNotEmpty() }.forEach { name ->
            if ("rc" in objects || shell == null) {
                ubus.call("rc", "init", JsonObject(mapOf("name" to JsonPrimitive(name), "action" to JsonPrimitive(command))))
            } else {
                val run = shell.sh("/etc/init.d/${quote(name)} ${quote(command)}")
                if (run.exitStatus != 0) throw DeviceException(ErrorCategory.General, run.output.trim().ifEmpty { "$name $command ended with ${run.exitStatus}" })
            }
        }
    }

    private suspend fun packages(): List<Item> =
        installedPackages(ubus, objects, ::file).map { (name, version) -> Item(name, mapOf("name" to name, "version" to version)) }

    private suspend fun install(values: Map<String, String>): String {
        val name = values["name"] ?: throw DeviceException(ErrorCategory.ArgumentValue, "missing value of argument name")
        packageManager("update")
        packageManager("install", name)
        return name
    }

    private suspend fun uninstall(names: List<String>) = names.forEach { packageManager("remove", it) }

    private suspend fun packageManager(action: String, name: String? = null) {
        val words = listOfNotNull(action, name)
        val shell = shell
        val (code, printed) = if (shell != null) {
            val apk = listOfNotNull(apkActions.getValue(action), name).joinToString(" ") { quote(it) }
            val opkg = words.joinToString(" ") { quote(it) }
            val result = shell.sh("if [ -x $MANAGER ]; then $MANAGER $opkg; elif command -v apk >/dev/null; then apk $apk; else opkg $opkg; fi")
            result.exitStatus.toLong() to result.output
        } else {
            val answer = ubus.call("file", "exec", JsonObject(mapOf("command" to JsonPrimitive(MANAGER), "params" to JsonArray(words.map(::JsonPrimitive)))))
            (answer.number("code") ?: 0L) to answer.text("stderr").ifBlank { answer.text("stdout") }
        }
        if (code != 0L) throw DeviceException(ErrorCategory.General, printed.trim())
    }

    private suspend fun tasks(): List<Item> = crontab().mapNotNull(::task)

    private suspend fun addTask(values: Map<String, String>): String {
        val lines = crontab() + line(values)
        writeCrontab(lines)
        return taskLines(lines).entries.single { it.value == lines.lastIndex }.key
    }

    private suspend fun setTask(id: String, changes: Map<String, String?>) {
        val lines = crontab().toMutableList()
        val at = taskLines(lines)[id] ?: throw DeviceException(ErrorCategory.MissingItem, "no task $id")
        val values = task(lines[at])?.values.orEmpty() + changes
        lines[at] = line(values.mapNotNull { (name, value) -> value?.let { name to it } }.toMap())
        writeCrontab(lines)
    }

    private suspend fun removeTasks(ids: List<String>) {
        val lines = crontab()
        val gone = ids.mapNotNull(taskLines(lines)::get).toSet()
        writeCrontab(lines.filterIndexed { index, _ -> index !in gone })
    }

    private suspend fun crontab() = file(CRONTAB).lines().filter { it.isNotEmpty() }

    private fun task(line: String): Item? {
        val words = line.trim().split(blanks, limit = 6)
        if (words.size < 6 || line.trimStart().startsWith("#") || '=' in words[0]) return null
        return Item(line, mapOf("minute" to words[0], "hour" to words[1], "day" to words[2], "month" to words[3], "weekday" to words[4], "command" to words[5]))
    }

    private fun taskLines(lines: List<String>): Map<String, Int> {
        val tasks = lines.withIndex().filter { task(it.value) != null }
        return numbered(tasks.map { Item(it.value, emptyMap()) }).map { it.id }.zip(tasks.map { it.index }).toMap()
    }

    private fun line(values: Map<String, String>): String {
        val command = values["command"]?.takeIf { it.isNotBlank() } ?: throw DeviceException(ErrorCategory.ArgumentValue, "missing value of argument command")
        val times = TASK.dropLast(1).map { values[it]?.takeIf { time -> time.isNotBlank() } ?: "*" }
        if (times.any { time -> time.any(Char::isWhitespace) } || '\n' in command) throw DeviceException(ErrorCategory.ArgumentValue, "a time of cron is one word, a command one line")
        return (times + command).joinToString(" ")
    }

    private suspend fun writeCrontab(lines: List<String>) {
        val text = lines.joinToString("") { "$it\n" }
        val shell = shell
        if (shell != null) {
            val written = shell.sh("printf '%s' ${quote(text)} > $CRONTAB && { /etc/init.d/cron restart; true; }")
            if (written.exitStatus != 0) throw DeviceException(ErrorCategory.General, written.output.trim())
        } else {
            ubus.call("file", "write", JsonObject(mapOf("path" to JsonPrimitive(CRONTAB), "data" to JsonPrimitive(text))))
            ubus.call("rc", "init", JsonObject(mapOf("name" to JsonPrimitive("cron"), "action" to JsonPrimitive("restart"))))
        }
    }

    private suspend fun sshKeys(): List<Item> = file(AUTHORIZED_KEYS).lines().mapNotNull(::sshKey)

    private fun sshKey(line: String): Item? {
        val words = line.trim().split(blanks, limit = 3)
        if (words.size < 2 || !keyTypes.matches(words[0])) return null
        return Item(
            line.trim(),
            buildMap {
                put("user", "root")
                put("key-type", words[0])
                publicKeyBits(words[0], words[1])?.let { put("bits", "$it") }
                words.getOrNull(2)?.let { put("key-owner", it) }
                put("key", words[1])
            },
        )
    }

    private suspend fun addSshKey(values: Map<String, String>): String {
        if (values["user"].let { it != null && it != "root" }) throw DeviceException(ErrorCategory.ArgumentValue, "dropbear of OpenWrt keeps the keys of root only")
        val line = values["key"]?.trim().orEmpty()
        val key = sshKey(line)?.takeIf { '\n' !in line } ?: throw DeviceException(ErrorCategory.ArgumentValue, "a public key is a line such as ssh-ed25519 AAAA… comment")
        writeKeys(file(AUTHORIZED_KEYS).lines().filter { it.isNotBlank() } + line)
        return key.id
    }

    private suspend fun removeSshKeys(ids: List<String>) = writeKeys(file(AUTHORIZED_KEYS).lines().filter { it.isNotBlank() && it.trim() !in ids })

    // read and written by root alone, or dropbear takes no key from the file
    private suspend fun writeKeys(lines: List<String>) {
        val text = lines.joinToString("") { "$it\n" }
        val shell = shell
        if (shell != null) {
            val written = shell.sh("(umask 077 && printf '%s' ${quote(text)} > $AUTHORIZED_KEYS)")
            if (written.exitStatus != 0) throw DeviceException(ErrorCategory.General, written.output.trim())
        } else {
            ubus.call("file", "write", JsonObject(mapOf("path" to JsonPrimitive(AUTHORIZED_KEYS), "data" to JsonPrimitive(text), "mode" to JsonPrimitive(0x180))))
        }
    }

    private suspend fun resource(): List<Item> {
        val info = ubus.call("system", "info")
        val board = facts.board
        val release = board["release"] as? JsonObject
        val memory = info["memory"] as? JsonObject
        val root = info["root"] as? JsonObject
        val load = (info["load"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }?.map { "${it * 100 / 65536 / 100.0}" }
        val values = buildMap {
            info.number("uptime")?.let { put("uptime", duration(it)) }
            release?.let {
                put("version", it.text("version"))
                put("revision", it.text("revision"))
                put("architecture-name", it.text("target"))
            }
            put("board-name", board.text("model"))
            put("cpu", board.text("system"))
            put("kernel", board.text("kernel"))
            load?.firstOrNull()?.let { put("load", it) }
            load?.let { put("load-average", it.joinToString(" ")) }
            memory?.number("available")?.let { put("free-memory", "$it") }
            memory?.number("total")?.let { put("total-memory", "$it") }
            root?.number("avail")?.let { put("free-hdd-space", "${it * 1024}") }
            root?.number("total")?.let { put("total-hdd-space", "${it * 1024}") }
            info.number("localtime")?.let { local(it).split(' ') }?.let { (date, time) ->
                put("date", date)
                put("time", time)
            }
        }
        return listOf(Item("", values))
    }

    // setLocaltime takes the Unix time, whatever its name says, and sysfixtime saves it to the RTC
    private suspend fun syncTime() {
        val now = Clock.System.now().epochSeconds
        val shell = shell
        if ("luci" in objects || shell == null) {
            ubus.call("luci", "setLocaltime", JsonObject(mapOf("localtime" to JsonPrimitive(now))))
        } else {
            val set = shell.sh("date -s @$now >/dev/null && { /etc/init.d/sysfixtime restart; true; }")
            if (set.exitStatus != 0) throw DeviceException(ErrorCategory.General, set.output.trim().ifEmpty { "date ended with ${set.exitStatus}" })
        }
    }

    private suspend fun file(path: String): String {
        val shell = shell ?: return readFile(ubus, objects, null, path).orEmpty()
        val read = shell.sh("[ ! -e ${quote(path)} ] || cat ${quote(path)}")
        if (read.exitStatus != 0) throw DeviceException(ErrorCategory.General, read.output.trim())
        return read.output
    }

    private suspend fun shift() = offset ?: localOffset().also { offset = it }

    private suspend fun localOffset(): Long {
        val local = ubus.call("system", "info")["localtime"]?.jsonPrimitive?.longOrNull ?: return 0
        val utc = if ("luci" in objects) {
            val time = try {
                ubus.call("luci", "getUnixtime")
            } catch (e: UbusException) {
                // LuCI before 25.12 gives the same Unix time under the name getLocaltime
                if (e.status != UbusStatus.METHOD_NOT_FOUND) throw e
                ubus.call("luci", "getLocaltime")
            }
            time["result"]?.jsonPrimitive?.longOrNull
        } else {
            shell?.sh("date +%s")?.output?.trim()?.toLongOrNull()
        } ?: return 0
        return ((local - utc) / 900.0).roundToLong() * 900
    }

    private fun local(seconds: Long) = Instant.fromEpochSeconds(seconds).toString().removeSuffix("Z").replace('T', ' ')

    private fun JsonObject.objects(key: String) = this[key]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.text(key: String) = this[key]?.plain().orEmpty()

    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonElement.plain() = (this as? JsonPrimitive)?.content ?: toString()

    private companion object {
        const val CRONTAB = "/etc/crontabs/root"
        const val AUTHORIZED_KEYS = "/etc/dropbear/authorized_keys"
        val keyTypes = Regex("(ssh|ecdsa-sha2|sk-ssh|sk-ecdsa-sha2)-[\\w@.-]+")
        const val LINES = 500
        val FILES = listOf("/tmp", "/root")
        const val MANAGER = "/usr/libexec/package-manager-call"
        val TASK = listOf("minute", "hour", "day", "month", "weekday", "command")
        val blanks = Regex("\\s+")
        val apkActions = mapOf("update" to "update", "install" to "add", "remove" to "del")
        val conntrack = mapOf("src" to "src-address", "dst" to "dst-address", "sport" to "src-port", "dport" to "dst-port", "mark" to "connection-mark")
        val luciConntrack = conntrack + mapOf("layer3" to "family", "layer4" to "protocol")
        val facilities = listOf(
            "kern", "user", "mail", "daemon", "auth", "syslog", "lpr", "news", "uucp", "cron", "authpriv", "ftp", "ntp", "audit", "alert",
            "clock", "local0", "local1", "local2", "local3", "local4", "local5", "local6", "local7",
        )
        val levels = listOf("emerg", "alert", "crit", "err", "warn", "notice", "info", "debug")
        val ROUTE = listOf("type", "dst-address", "gateway", "interface", "pref-src", "table", "distance", "protocol", "scope")
        val routeTypes = setOf("local", "broadcast", "anycast", "multicast", "unreachable", "prohibit", "blackhole", "throw", "nat")
        val routeWords = mapOf("via" to "gateway", "dev" to "interface", "src" to "pref-src", "table" to "table", "metric" to "distance", "proto" to "protocol", "scope" to "scope")
        val processFields = mapOf("PID" to "pid", "PPID" to "ppid", "USER" to "user", "STAT" to "stat", "VSZ" to "vsz", "%MEM" to "memory", "%CPU" to "cpu", "COMMAND" to "command")
        val signals = mapOf("hang-up" to "-HUP", "terminate" to "-TERM", "kill" to "-KILL")
        val kernelLine = Regex("<(\\d+)>\\s*\\[\\s*([\\d.]+)]\\s?(.*)")
        val topLine = Regex("\\s*(\\d+)\\s+(\\d+)\\s+(\\S+)\\s+(.{1,4}?)\\s+(\\d+[kmgKMG]?)\\s+(\\d+%)\\s+(\\d+%)\\s+(.+)")
    }
}

internal fun network6(address: String, length: Int): String? {
    val start = span("$address/$length")?.start?.takeIf { it.size == 16 } ?: return null
    val groups = List(8) { ((start[it * 2].toInt() and 0xFF) shl 8) or (start[it * 2 + 1].toInt() and 0xFF) }
    var from = -1
    var count = 0
    var at = 0
    while (at < groups.size) {
        if (groups[at] != 0) {
            at++
            continue
        }
        val end = (at until groups.size).firstOrNull { groups[it] != 0 } ?: groups.size
        if (end - at > count) {
            from = at
            count = end - at
        }
        at = end
    }
    fun text(part: List<Int>) = part.joinToString(":") { it.toString(16) }
    val written = if (count < 2) text(groups) else text(groups.take(from)) + "::" + text(groups.drop(from + count))
    return "$written/$length"
}
