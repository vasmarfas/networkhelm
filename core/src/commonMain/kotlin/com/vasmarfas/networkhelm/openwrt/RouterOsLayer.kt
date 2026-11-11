package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.matches
import com.vasmarfas.networkhelm.packs.NativeMenu
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackAction
import com.vasmarfas.networkhelm.packs.PackForm
import com.vasmarfas.networkhelm.packs.PackFormTab
import com.vasmarfas.networkhelm.packs.PackOpen
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.packs.PackWindow
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.search.span
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.TextStore
import com.vasmarfas.networkhelm.session.rows
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RouterOsLayer(private val base: DeviceSession) : DeviceSession by base {
    override val capabilities = base.capabilities.copy(menuContents = true)

    private val faces: Map<MenuPath, LayerFace> = mapOf(
        "system/identity" to LayerFace(
            NodeKind.Settings,
            listOf(SYSTEM),
            { listOf(Argument("name", ValueType.Text())) },
            { (systems) -> systems.take(1).map { Item(it.id, mapOf("name" to it["hostname"].orEmpty())) } },
            set = { _, changes -> changes["name"]?.let { base.set(path(SYSTEM), null, mapOf("hostname" to it)) } },
        ),
        "interface" to LayerFace(
            NodeKind.List,
            listOf(DEVICES, NETWORK_INTERFACE),
            { listOf("name", "type", "actual-mtu", "mac-address", "running", "rx-byte", "tx-byte").map { Argument(it, ValueType.Text(), readOnly = true) } },
            { (devices, interfaces) ->
                val clients = interfaces.mapNotNull { client -> dialers[client["proto"]]?.let { pppName(client) to it } }.toMap()
                devices.map { device ->
                    val values = mapOf(
                        "name" to device.id,
                        "type" to when (device.id) {
                            in clients -> clients.getValue(device.id)
                            "lo" -> "loopback"
                            else -> deviceType(device["type"].orEmpty(), device["devtype"].orEmpty())
                        },
                        "actual-mtu" to device["mtu"],
                        "mac-address" to device["mac-address"],
                        "running" to device["running"],
                        "rx-byte" to device["rx-byte"],
                        "tx-byte" to device["tx-byte"],
                    )
                    Item(device.id, values.filterValues { it != null }.mapValues { it.value.orEmpty() })
                } + (clients - devices.map { it.id }.toSet()).map { (name, type) -> Item(name, mapOf("name" to name, "type" to type, "running" to "false")) }
            },
        ),
        "interface/bridge" to LayerFace(
            NodeKind.List,
            listOf(NETWORK_DEVICE),
            {
                listOf(
                    Argument("name", ValueType.Text()),
                    Argument("mtu", ValueType.Integer(68, 65535), optional = true),
                    Argument("mac-address", ValueType.Mac, optional = true),
                    Argument("protocol-mode", ValueType.Enumeration(), listOf("none", "rstp")),
                    Argument("vlan-filtering", ValueType.YesNo),
                )
            },
            { (devices) ->
                devices.filter { it["type"] == "bridge" }.map { bridge ->
                    Item(
                        bridge.id,
                        buildMap {
                            put("name", bridge["name"].orEmpty())
                            bridge["mtu"]?.let { put("mtu", it) }
                            bridge["macaddr"]?.let { put("mac-address", it) }
                            put("protocol-mode", if (bridge["stp"] in Flag) "rstp" else "none")
                            put("vlan-filtering", "${bridge["vlan_filtering"] in Flag}")
                        },
                    )
                }
            },
            add = { values ->
                val name = values["name"] ?: required("name")
                base.add(path(NETWORK_DEVICE), mapOf("type" to "bridge", "name" to name) + bridgeOptions(values).present())
            },
            set = { id, changes -> base.set(path(NETWORK_DEVICE), id, bridgeOptions(changes)) },
            remove = { ids -> base.remove(path(NETWORK_DEVICE), ids) },
        ),
        "interface/bridge/port" to LayerFace(
            NodeKind.List,
            listOf(NETWORK_DEVICE),
            { listOf(Argument("interface", ValueType.Text()), Argument("bridge", ValueType.Text())) },
            { (devices) ->
                devices.filter { it["type"] == "bridge" }.flatMap { bridge ->
                    uciLines(bridge["ports"]).map { port -> Item("${bridge.id} $port", mapOf("interface" to port, "bridge" to bridge["name"].orEmpty())) }
                }
            },
            add = { values -> addPort(values["bridge"] ?: required("bridge"), values["interface"] ?: required("interface")) },
            set = { id, changes ->
                val (bridge, port) = port(id)
                val name = changes["bridge"] ?: bridgeName(bridge)
                removePorts(listOfNotNull(id))
                addPort(name, changes["interface"] ?: port)
            },
            remove = { ids -> removePorts(ids) },
        ),
        "interface/vlan" to LayerFace(
            NodeKind.List,
            listOf(NETWORK_DEVICE),
            {
                listOf(
                    Argument("name", ValueType.Text()),
                    Argument("vlan-id", ValueType.Integer(1, 4094)),
                    Argument("interface", ValueType.Text()),
                    Argument("mtu", ValueType.Integer(68, 65535), optional = true),
                )
            },
            { (devices) ->
                devices.filter { it["type"] == "8021q" }.map { vlan ->
                    val values = mapOf("name" to vlan["name"], "vlan-id" to vlan["vid"], "interface" to vlan["ifname"], "mtu" to vlan["mtu"])
                    Item(vlan.id, values.filterValues { it != null }.mapValues { it.value.orEmpty() })
                }
            },
            add = { values ->
                for (field in listOf("name", "vlan-id", "interface")) if (values[field] == null) required(field)
                base.add(path(NETWORK_DEVICE), mapOf("type" to "8021q") + vlanOptions(values).present())
            },
            set = { id, changes -> base.set(path(NETWORK_DEVICE), id, vlanOptions(changes)) },
            remove = { ids -> base.remove(path(NETWORK_DEVICE), ids) },
        ),
        "interface/pppoe-client" to pppClient(PPPOE, pppoeFields, "interface") {
            listOf(
                Argument("name", ValueType.Text()),
                Argument("interface", ValueType.Enumeration(), deviceNames()),
                Argument("user", ValueType.Text()),
                Argument("password", ValueType.Secret, optional = true),
                Argument("service-name", ValueType.Text(), optional = true),
                Argument("ac-name", ValueType.Text(), optional = true),
                Argument("host-uniq", ValueType.Hex, optional = true),
                Argument("max-mtu", ValueType.Integer(68, 65535), optional = true),
                Argument("add-default-route", ValueType.YesNo),
                Argument("default-route-distance", ValueType.Integer(0, 255), optional = true),
                Argument("use-peer-dns", ValueType.YesNo),
                Argument("running", ValueType.Text(), readOnly = true),
                Argument("disabled", ValueType.YesNo),
            )
        },
        "interface/pptp-client" to pppClient(PPTP, tunnelFields, "connect-to") { tunnelArguments() },
        "interface/l2tp-client" to pppClient(L2TP, tunnelFields, "connect-to") { tunnelArguments() },
        "interface/wireguard" to LayerFace(
            NodeKind.List,
            listOf(NETWORK_INTERFACE, WIREGUARD_INTERFACES),
            {
                listOf(
                    Argument("name", ValueType.Text()),
                    Argument("mtu", ValueType.Integer(68, 65535), optional = true),
                    Argument("listen-port", ValueType.Integer(1, 65535), optional = true),
                    Argument("private-key", ValueType.Secret, optional = true),
                    Argument("public-key", ValueType.Text(), readOnly = true),
                    Argument("disabled", ValueType.YesNo),
                )
            },
            { (interfaces, instances) ->
                val keys = instances.associateBy { it.id }
                interfaces.filter { it["proto"] == "wireguard" }.map { iface ->
                    val name = sectionName(iface)
                    Item(
                        iface.id,
                        buildMap {
                            put("name", name)
                            iface["mtu"]?.let { put("mtu", it) }
                            iface["listen_port"]?.let { put("listen-port", it) }
                            iface["private_key"]?.let { put("private-key", it) }
                            keys[name]?.get("public-key")?.let { put("public-key", it) }
                            put("disabled", "${iface["disabled"] in Flag}")
                        },
                    )
                }
            },
            add = { values ->
                val name = values["name"] ?: required("name")
                val key = values["private-key"] ?: base.get(path(WIREGUARD_KEY), null)?.get("private-key") ?: required("private-key")
                base.add(path(NETWORK_INTERFACE), mapOf(NAME to name, "proto" to "wireguard", "private_key" to key) + wireguardOptions(values).present())
            },
            set = { id, changes -> base.set(path(NETWORK_INTERFACE), id, wireguardOptions(changes) + renamed(changes, mapOf("private-key" to "private_key"))) },
            remove = { ids ->
                val names = rows(NETWORK_INTERFACE).filter { it.id in ids }.map(::sectionName).toSet()
                val peers = rows(WIREGUARD_PEERS).filter { it[TYPE]?.removePrefix(PEER_TYPE) in names }.map { it.id }
                if (peers.isNotEmpty()) base.remove(path(WIREGUARD_PEERS), peers)
                base.remove(path(NETWORK_INTERFACE), ids)
            },
        ),
        "interface/wireguard/peers" to LayerFace(
            NodeKind.List,
            listOf(WIREGUARD_PEERS, WIREGUARD_STATUS),
            {
                listOf(
                    Argument("interface", ValueType.Enumeration(), wireguardNames()),
                    Argument("public-key", ValueType.Text()),
                    Argument("preshared-key", ValueType.Secret, optional = true),
                    Argument("endpoint-address", ValueType.Text(), optional = true),
                    Argument("endpoint-port", ValueType.Integer(1, 65535), optional = true),
                    Argument("allowed-address", ValueType.Many(ValueType.Text()), optional = true),
                    Argument("persistent-keepalive", ValueType.Text(), optional = true),
                    Argument("comment", ValueType.Text(), optional = true),
                    Argument("disabled", ValueType.YesNo),
                    Argument("current-endpoint-address", ValueType.Text(), readOnly = true),
                    Argument("current-endpoint-port", ValueType.Text(), readOnly = true),
                    Argument("last-handshake", ValueType.Text(), readOnly = true),
                    Argument("rx", ValueType.Text(), readOnly = true),
                    Argument("tx", ValueType.Text(), readOnly = true),
                )
            },
            { (peers, status) ->
                val reported = status.associateBy { "${it["interface"]} ${it["public-key"]}" }
                peers.map { peer ->
                    val iface = peer[TYPE].orEmpty().removePrefix(PEER_TYPE)
                    val state = reported["$iface ${peer["public_key"]}"]
                    Item(
                        peer.id,
                        buildMap {
                            put("interface", iface)
                            peer["public_key"]?.let { put("public-key", it) }
                            peer["preshared_key"]?.let { put("preshared-key", it) }
                            peer["endpoint_host"]?.let { put("endpoint-address", it) }
                            peer["endpoint_port"]?.let { put("endpoint-port", it) }
                            put("allowed-address", uciLines(peer["allowed_ips"]).joinToString(","))
                            peer["persistent_keepalive"]?.let { put("persistent-keepalive", "${it}s") }
                            peer["description"]?.let { put("comment", it) }
                            put("disabled", "${peer["disabled"] in Flag}")
                            state?.get("endpoint")?.let {
                                put("current-endpoint-address", it.substringBeforeLast(':').removeSurrounding("[", "]"))
                                put("current-endpoint-port", it.substringAfterLast(':'))
                            }
                            state?.get("last-handshake")?.let { put("last-handshake", it) }
                            state?.get("rx")?.let { put("rx", it) }
                            state?.get("tx")?.let { put("tx", it) }
                        },
                    )
                }
            },
            add = { values ->
                val iface = values["interface"] ?: required("interface")
                if (values["public-key"] == null) required("public-key")
                base.add(path("network/$PEER_TYPE$iface"), peerOptions(values).present())
            },
            set = { id, changes ->
                if ("interface" in changes) throw DeviceException(ErrorCategory.ArgumentValue, "a peer stays on its interface here: OpenWrt keeps it in a section of that interface")
                base.set(path(WIREGUARD_PEERS), id, peerOptions(changes))
            },
            remove = { ids -> base.remove(path(WIREGUARD_PEERS), ids) },
        ),
        "ip/address" to LayerFace(
            NodeKind.List,
            listOf(NETWORK_INTERFACE, ADDRESSES),
            {
                listOf(
                    Argument("address", ValueType.Ip4Prefix),
                    Argument("network", ValueType.Ip4, readOnly = true),
                    Argument("interface", ValueType.Enumeration(), deviceNames()),
                )
            },
            { (interfaces, addresses) -> addresses(interfaces, addresses) },
            add = { values -> addAddress(prefixed(values["address"] ?: required("address")), values["interface"] ?: required("interface")) },
            set = { id, changes ->
                val (owner, cidr) = staticAddress(id)
                val address = changes["address"]?.let(::prefixed) ?: cidr
                val device = changes["interface"] ?: deviceOf(owner)
                withoutAddress(owner, cidr)
                addAddress(address, device)
            },
            remove = { ids -> ids.forEach { id -> staticAddress(id).let { (owner, cidr) -> withoutAddress(owner, cidr) } } },
        ),
        "ip/route" to LayerFace(
            NodeKind.List,
            listOf(NETWORK_ROUTE, NETWORK_INTERFACE, KERNEL_ROUTES),
            {
                listOf(
                    Argument("dst-address", ValueType.Ip4Prefix),
                    Argument("gateway", ValueType.Text()),
                    Argument("distance", ValueType.Integer(0, 255), optional = true),
                    Argument("routing-table", ValueType.Text(), optional = true),
                    Argument("pref-src", ValueType.Ip4, optional = true),
                )
            },
            { (routes, interfaces, kernel) -> routes(routes, interfaces, kernel, "0.0.0.0/0") },
            add = { values ->
                val target = prefixed(values["dst-address"] ?: required("dst-address"))
                base.add(path(NETWORK_ROUTE), mapOf("target" to target) + routeOptions(values + ("gateway" to (values["gateway"] ?: required("gateway")))).present())
            },
            set = { id, changes ->
                val target = changes["dst-address"]?.let { mapOf("target" to prefixed(it), "netmask" to null) }.orEmpty()
                base.set(path(NETWORK_ROUTE), staticRoute(id), target + routeOptions(changes))
            },
            remove = { ids -> base.remove(path(NETWORK_ROUTE), ids.map(::staticRoute)) },
            optional = setOf(KERNEL_ROUTES),
        ),
        "ip/dns" to LayerFace(
            NodeKind.Settings,
            listOf(DNSMASQ),
            { listOf(Argument("servers", ValueType.Many(ValueType.Text())), Argument("cache-size", ValueType.Integer(min = 0))) },
            { (instances) ->
                instances.take(1).map { dnsmasq ->
                    Item(dnsmasq.id, mapOf("servers" to uciLines(dnsmasq["server"]).joinToString(","), "cache-size" to dnsmasq["cachesize"]).filterValues { it != null }.mapValues { it.value.orEmpty() })
                }
            },
            set = { _, changes ->
                val instance = rows(DNSMASQ).firstOrNull() ?: throw DeviceException(ErrorCategory.MissingItem, "dnsmasq has no instance")
                val options = buildMap {
                    if ("servers" in changes) put("server", changes["servers"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(LIST)?.ifEmpty { null })
                    if ("cache-size" in changes) put("cachesize", changes["cache-size"])
                }
                base.set(path(DNSMASQ), instance.id, options)
            },
        ),
        "ip/dns/static" to LayerFace(
            NodeKind.List,
            listOf(DOMAINS),
            { listOf(Argument("name", ValueType.Text()), Argument("address", ValueType.Ip4)) },
            { (domains) -> domains.map { Item(it.id, mapOf("name" to it["name"].orEmpty(), "address" to it["ip"].orEmpty())) } },
            add = { values -> base.add(path(DOMAINS), mapOf("name" to (values["name"] ?: required("name")), "ip" to (values["address"] ?: required("address")))) },
            set = { id, changes -> base.set(path(DOMAINS), id, renamed(changes, mapOf("name" to "name", "address" to "ip"))) },
            remove = { ids -> base.remove(path(DOMAINS), ids) },
        ),
        "ip/dhcp-server" to LayerFace(
            NodeKind.List,
            listOf(POOLS, NETWORK_INTERFACE),
            {
                listOf(
                    Argument("name", ValueType.Text(), readOnly = true),
                    Argument("interface", ValueType.Text(), readOnly = true),
                    Argument("lease-time", ValueType.Text(), optional = true),
                    Argument("address-pool", ValueType.Text(), readOnly = true),
                    Argument("disabled", ValueType.YesNo),
                )
            },
            { (pools, interfaces) ->
                pools.map { pool ->
                    val name = sectionName(pool)
                    val start = pool["start"]
                    val limit = pool["limit"]
                    val network = interfaces.firstOrNull { sectionName(it) == pool["interface"] }?.let(::staticAddresses)?.firstOrNull { ':' !in it }
                    val range = network?.let { cidr -> start?.toLongOrNull()?.let { from -> limit?.toLongOrNull()?.let { count -> dhcpRange(cidr, from, count) } } }
                    Item(
                        pool.id,
                        buildMap {
                            put("name", name)
                            put("interface", served(pool, interfaces))
                            pool["leasetime"]?.let { put("lease-time", it) }
                            if (start != null && limit != null) put("address-pool", range ?: "$start+$limit")
                            put("disabled", "${pool["ignore"] in Flag}")
                        },
                    )
                }
            },
            set = { id, changes -> base.set(path(POOLS), id, renamed(changes, mapOf("lease-time" to "leasetime")) + flag(changes, "disabled", "ignore")) },
        ),
        "ip/dhcp-server/lease" to LayerFace(
            NodeKind.List,
            listOf(HOSTS, LEASES),
            {
                listOf(
                    Argument("address", ValueType.Ip4),
                    Argument("mac-address", ValueType.Mac),
                    Argument("host-name", ValueType.Text(), optional = true),
                    Argument("status", ValueType.Text(), readOnly = true),
                )
            },
            { (hosts, leases) -> leases(hosts, leases) },
            add = { values ->
                base.add(path(HOSTS), mapOf("mac" to (values["mac-address"] ?: required("mac-address")), "ip" to (values["address"] ?: required("address"))) + listOfNotNull(values["host-name"]?.let { "name" to it }))
            },
            set = { id, changes -> base.set(path(HOSTS), staticLease(id), renamed(changes, mapOf("address" to "ip", "mac-address" to "mac", "host-name" to "name"))) },
            remove = { ids -> base.remove(path(HOSTS), ids.map(::staticLease)) },
            commands = mapOf("make-static" to ::makeStatic),
        ),
        "ipv6/nd" to LayerFace(
            NodeKind.List,
            listOf(POOLS, NETWORK_INTERFACE),
            {
                listOf(
                    Argument("interface", ValueType.Text(), readOnly = true),
                    Argument("ra-interval", ValueType.Text()),
                    Argument("ra-lifetime", ValueType.Time),
                    Argument("ra-preference", ValueType.Enumeration(closed = true), listOf("low", "medium", "high")),
                    Argument("hop-limit", ValueType.Integer(0, 255), optional = true),
                    Argument("mtu", ValueType.Integer(1280, 65535), optional = true),
                    Argument("managed-address-configuration", ValueType.YesNo),
                    Argument("other-configuration", ValueType.YesNo),
                    Argument("advertise-dns", ValueType.YesNo),
                    Argument("dns", ValueType.Many(ValueType.Ip6), optional = true),
                    Argument("disabled", ValueType.YesNo),
                )
            },
            { (pools, interfaces) -> pools.map { nd(it, interfaces) } },
            set = { id, changes -> base.set(path(POOLS), id, ndOptions(rows(POOLS).firstOrNull { it.id == id }?.get("ra_flags"), changes)) },
        ),
        "ipv6/dhcp-server" to LayerFace(
            NodeKind.List,
            listOf(POOLS, NETWORK_INTERFACE),
            { listOf(Argument("name", ValueType.Text(), readOnly = true), Argument("interface", ValueType.Text(), readOnly = true), Argument("disabled", ValueType.YesNo)) },
            { (pools, interfaces) ->
                pools.map { pool ->
                    Item(pool.id, mapOf("name" to sectionName(pool), "interface" to served(pool, interfaces), "disabled" to "${pool["dhcpv6"] !in setOf("server", "hybrid")}"))
                }
            },
            set = { id, changes -> if ("disabled" in changes) base.set(path(POOLS), id, mapOf("dhcpv6" to if (changes["disabled"] == "true" || changes["disabled"] == "yes") "disabled" else "server")) },
        ),
        "ipv6/dhcp-server/binding" to LayerFace(
            NodeKind.List,
            listOf(HOSTS, LEASES6),
            {
                listOf(
                    Argument("address", ValueType.Text(), optional = true),
                    Argument("duid", ValueType.Text()),
                    Argument("iaid", ValueType.Text(), readOnly = true),
                    Argument("server", ValueType.Text(), readOnly = true),
                    Argument("life-time", ValueType.Text(), readOnly = true),
                    Argument("comment", ValueType.Text(), optional = true),
                )
            },
            { (hosts, leases) -> bindings(hosts, leases) },
            add = { values -> base.add(path(HOSTS), mapOf("duid" to (values["duid"] ?: required("duid"))) + bindingOptions(values).present()) },
            set = { id, changes -> base.set(path(HOSTS), staticLease(id), bindingOptions(changes)) },
            remove = { ids -> base.remove(path(HOSTS), ids.map(::staticLease)) },
            commands = mapOf("make-static" to ::makeStatic6),
        ),
        "ipv6/nd/prefix" to LayerFace(
            NodeKind.List,
            listOf(PREFIXES, POOLS, NETWORK_INTERFACE),
            {
                listOf(
                    Argument("prefix", ValueType.Ip6Prefix, readOnly = true),
                    Argument("interface", ValueType.Text(), readOnly = true),
                    Argument("valid-lifetime", ValueType.Time, readOnly = true),
                    Argument("preferred-lifetime", ValueType.Time, readOnly = true),
                    Argument("on-link", ValueType.YesNo, readOnly = true),
                    Argument("autonomous", ValueType.YesNo, readOnly = true),
                )
            },
            { (prefixes, pools, interfaces) -> advertised(prefixes, pools, interfaces) },
        ),
        "ipv6/address" to LayerFace(
            NodeKind.List,
            listOf(NETWORK_INTERFACE, ADDRESSES),
            { listOf(Argument("address", ValueType.Text()), Argument("interface", ValueType.Enumeration(), deviceNames())) },
            { (interfaces, addresses) -> addresses6(interfaces, addresses) },
            add = { values -> addAddress(values["address"] ?: required("address"), values["interface"] ?: required("interface")) },
            remove = { ids -> ids.forEach { id -> staticAddress(id).let { (owner, cidr) -> withoutAddress(owner, cidr) } } },
        ),
        "ipv6/route" to LayerFace(
            NodeKind.List,
            listOf(NETWORK_ROUTE6, NETWORK_INTERFACE, KERNEL_ROUTES6),
            {
                listOf(
                    Argument("dst-address", ValueType.Ip6Prefix),
                    Argument("gateway", ValueType.Text()),
                    Argument("distance", ValueType.Integer(0, 255), optional = true),
                    Argument("routing-table", ValueType.Text(), optional = true),
                )
            },
            { (routes, interfaces, kernel) -> routes(routes, interfaces, kernel, "::/0") },
            add = { values ->
                val target = values["dst-address"] ?: required("dst-address")
                base.add(path(NETWORK_ROUTE6), mapOf("target" to target) + route6Options(values + ("gateway" to (values["gateway"] ?: required("gateway")))).present())
            },
            set = { id, changes -> base.set(path(NETWORK_ROUTE6), staticRoute(id), renamed(changes, mapOf("dst-address" to "target")) + route6Options(changes)) },
            remove = { ids -> base.remove(path(NETWORK_ROUTE6), ids.map(::staticRoute)) },
            optional = setOf(KERNEL_ROUTES6),
        ),
        "queue/simple" to LayerFace(
            NodeKind.List,
            listOf(SQM_QUEUES),
            {
                listOf(
                    Argument("name", ValueType.Text()),
                    Argument("target", ValueType.Enumeration(), deviceNames()),
                    Argument("max-limit", ValueType.Text()),
                    Argument("queue", ValueType.Text(), readOnly = true),
                    Argument("disabled", ValueType.YesNo),
                )
            },
            { (queues) ->
                queues.map { queue ->
                    Item(
                        queue.id,
                        buildMap {
                            put("name", sectionName(queue))
                            queue["interface"]?.let { put("target", it) }
                            put("max-limit", "${rate(queue["upload"])}/${rate(queue["download"])}")
                            queue["qdisc"]?.let { put("queue", "$it/$it") }
                            put("disabled", "${queue["enabled"] !in Flag}")
                        },
                    )
                }
            },
            add = { values ->
                val name = values["name"] ?: required("name")
                if (values["target"] == null) required("target")
                base.add(path(SQM_QUEUES), mapOf(NAME to name, "enabled" to "1", "qdisc" to "cake", "script" to "piece_of_cake.qos") + queueOptions(values).present())
            },
            set = { id, changes -> base.set(path(SQM_QUEUES), id, queueOptions(changes)) },
            remove = { ids -> base.remove(path(SQM_QUEUES), ids) },
        ),
        "system/clock" to LayerFace(
            NodeKind.Settings,
            listOf(RESOURCE, SYSTEM),
            { listOf("time", "date", "time-zone-name").map { Argument(it, ValueType.Text(), readOnly = true) } },
            { (resources, systems) ->
                resources.take(1).map { resource ->
                    Item(
                        resource.id,
                        buildMap {
                            resource["time"]?.let { put("time", it) }
                            resource["date"]?.let { put("date", it) }
                            systems.firstOrNull()?.get("zonename")?.let { put("time-zone-name", it) }
                        },
                    )
                }
            },
            commands = mapOf("sync-time" to { _ -> base.run(path(RESOURCE), "sync-time", emptyMap()).collect() }),
            actions = listOf(PackAction("Sync with This Device", "sync-time")),
        ),
        "system/scheduler" to LayerFace(
            NodeKind.List,
            listOf(TASKS),
            {
                listOf(
                    Argument("name", ValueType.Text(), readOnly = true),
                    Argument("start-time", ValueType.TimeOfDay),
                    Argument("interval", ValueType.Time),
                    Argument("days", ValueType.Enumeration(multiple = true), weekdays, optional = true),
                    Argument("on-event", ValueType.Text()),
                    Argument("owner", ValueType.Text(), readOnly = true),
                    Argument("comment", ValueType.Text(), readOnly = true),
                )
            },
            { (tasks) -> tasks.map(::schedule) },
            add = { values -> base.add(path(TASKS), cron(values)) },
            set = { id, changes ->
                val task = rows(TASKS).firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "no task $id")
                val timed = listOf("start-time", "interval", "days").any { it in changes }
                val times = if (timed) cron(schedule(task).values + changes) - "command" else emptyMap()
                base.set(path(TASKS), id, times + listOfNotNull(changes["on-event"]?.let { "command" to it }))
            },
            remove = { ids -> base.remove(path(TASKS), ids) },
        ),
        "user" to LayerFace(
            NodeKind.List,
            listOf(LOGINS),
            { listOf(Argument("name", ValueType.Text(), readOnly = true), Argument("group", ValueType.Enumeration(), listOf("full", "read"))) },
            { (logins) -> logins.map { Item(it.id, mapOf("name" to it["username"].orEmpty(), "group" to group(it))) } },
            set = { id, changes -> if ("group" in changes) base.set(path(LOGINS), id, rights(changes["group"])) },
        ),
        "user/group" to LayerFace(
            NodeKind.List,
            listOf(LOGINS),
            { listOf("name", "policy").map { Argument(it, ValueType.Text(), readOnly = true) } },
            { (logins) -> groups(logins) },
        ),
        "system/ntp/client" to LayerFace(
            NodeKind.Settings,
            listOf(TIMESERVER),
            { listOf(Argument("enabled", ValueType.YesNo), Argument("servers", ValueType.Many(ValueType.Text()))) },
            { (servers) -> servers.take(1).map { ntp -> Item(ntp.id, mapOf("enabled" to "${ntp["enabled"] != "0"}", "servers" to uciLines(ntp["server"]).joinToString(","))) } },
            set = { _, changes ->
                val ntp = rows(TIMESERVER).firstOrNull() ?: throw DeviceException(ErrorCategory.MissingItem, "the system config has no timeserver")
                val options = buildMap {
                    if ("enabled" in changes) put("enabled", if (changes["enabled"] == "true" || changes["enabled"] == "yes") "1" else "0")
                    if ("servers" in changes) put("server", changes["servers"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(LIST)?.ifEmpty { null })
                }
                base.set(path(TIMESERVER), ntp.id, options)
            },
        ),
        "interface/wifi" to LayerFace(
            NodeKind.List,
            listOf(WIFI_IFACES, WIFI_DEVICES),
            {
                listOf(
                    Argument("name", ValueType.Text(), readOnly = true),
                    Argument("configuration.mode", ValueType.Enumeration(), listOf("ap", "station")),
                    Argument("configuration.ssid", ValueType.Text()),
                    Argument("security.authentication-types", ValueType.Enumeration(), authentications.values.distinct()),
                    Argument("security.passphrase", ValueType.Secret, optional = true),
                    Argument("channel.band", ValueType.Text(), readOnly = true),
                    Argument("channel.frequency", ValueType.Text(), readOnly = true),
                    Argument("channel.width", ValueType.Text(), readOnly = true),
                    Argument("disabled", ValueType.YesNo),
                )
            },
            { (ifaces, devices) -> wifi(ifaces, devices) },
            set = { id, changes -> base.set(path(WIFI_IFACES), id, wifiOptions(changes)) },
            remove = { ids -> base.remove(path(WIFI_IFACES), ids) },
        ),
        "interface/wifi/security" to LayerFace(
            NodeKind.List,
            listOf(WIFI_IFACES),
            {
                listOf(
                    Argument("name", ValueType.Text(), readOnly = true),
                    Argument("authentication-types", ValueType.Enumeration(), authentications.values.distinct()),
                    Argument("passphrase", ValueType.Secret, optional = true),
                    Argument("encryption", ValueType.Enumeration(multiple = true), ciphers.values.distinct(), optional = true),
                    Argument("management-protection", ValueType.Enumeration(), protections.values.toList(), optional = true),
                    Argument("ft", ValueType.YesNo),
                    Argument("ft-over-ds", ValueType.YesNo),
                    Argument("ft-mobility-domain", ValueType.Text(), optional = true),
                    Argument("wps", ValueType.Enumeration(), listOf("disabled", "push-button")),
                )
            },
            { (ifaces) -> ifaces.map(::security) },
            set = { id, changes -> base.set(path(WIFI_IFACES), id, securityOptions(rows(WIFI_IFACES).firstOrNull { it.id == id }?.get("encryption"), changes)) },
        ),
        "interface/wifi/configuration" to LayerFace(
            NodeKind.List,
            listOf(WIFI_IFACES, WIFI_DEVICES),
            {
                listOf(
                    Argument("name", ValueType.Text(), readOnly = true),
                    Argument("mode", ValueType.Enumeration(), listOf("ap", "station")),
                    Argument("ssid", ValueType.Text()),
                    Argument("country", ValueType.Text(), readOnly = true),
                    Argument("hide-ssid", ValueType.YesNo),
                    Argument("max-clients", ValueType.Integer(1, 2007), optional = true),
                    Argument("tx-power", ValueType.Text(), readOnly = true),
                    Argument("dtim-period", ValueType.Integer(1, 255), optional = true),
                )
            },
            { (ifaces, devices) -> ifaces.map { configuration(it, devices) } },
            set = { id, changes -> base.set(path(WIFI_IFACES), id, configurationOptions(changes)) },
        ),
        "interface/wifi/datapath" to LayerFace(
            NodeKind.List,
            listOf(WIFI_IFACES, NETWORK_INTERFACE),
            {
                listOf(
                    Argument("name", ValueType.Text(), readOnly = true),
                    Argument("bridge", ValueType.Text(), readOnly = true),
                    Argument("client-isolation", ValueType.YesNo),
                )
            },
            { (ifaces, interfaces) -> ifaces.map { datapath(it, interfaces) } },
            set = { id, changes -> base.set(path(WIFI_IFACES), id, flag(changes, "client-isolation", "isolate")) },
        ),
        "interface/wifi/radio" to LayerFace(
            NodeKind.List,
            listOf(WIFI_DEVICES),
            { listOf("name", "band", "frequency", "width", "country", "disabled").map { Argument(it, ValueType.Text(), readOnly = true) } },
            { (devices) ->
                devices.map { radio ->
                    Item(
                        radio.id,
                        buildMap {
                            put("name", sectionName(radio))
                            radio["band"]?.let { put("band", band(it)) }
                            radio["channel"]?.let { put("frequency", frequency(radio["band"].orEmpty(), it)) }
                            radio["htmode"]?.let { put("width", width(it)) }
                            radio["country"]?.let { put("country", it) }
                            put("disabled", "${radio["disabled"] in Flag}")
                        },
                    )
                }
            },
        ),
        "interface/wifi/registration-table" to LayerFace(
            NodeKind.List,
            listOf(STATIONS, WIFI_DEVICES),
            { listOf("interface", "mac-address", "signal", "uptime", "authorized", "bytes").map { Argument(it, ValueType.Text(), readOnly = true) } },
            { (stations) ->
                stations.map { client ->
                    Item(
                        client.id,
                        buildMap {
                            for (field in listOf("interface", "mac-address", "signal", "authorized")) client[field]?.let { put(field, it) }
                            client["connected-time"]?.let { put("uptime", it) }
                            if (client["tx-bytes"] != null && client["rx-bytes"] != null) put("bytes", "${client["tx-bytes"]},${client["rx-bytes"]}")
                        },
                    )
                }
            },
            remove = { ids -> base.run(path(STATIONS), "disconnect", mapOf("numbers" to ids.joinToString(","))).collect() },
        ),
    ).plus(RouterOsFirewall(base, ::rows).faces).mapKeys { MenuPath.parse(it.key) }

    private val aliases = mapOf(
        "system/resource" to "status/resource",
        "system/package" to "status/packages",
        "system/reboot" to "tool/reboot",
        "log" to "status/log",
        "ip/arp" to "status/neighbors",
        "ipv6/neighbor" to "status/neighbors6",
        "file" to "status/files",
        "user/ssh-keys" to "status/ssh-keys",
        "tool/ping" to "tool/ping",
        "tool/traceroute" to "tool/traceroute",
    ).entries.associate { MenuPath.parse(it.key) to MenuPath.parse(it.value) }

    private val directories: Map<MenuPath, List<String>> = (faces.keys + aliases.keys)
        .flatMap { path -> path.segments.indices.map { MenuPath(path.segments.take(it)) to path.segments[it] } }
        .groupBy({ it.first }, { it.second })
        .mapValues { it.value.distinct() }

    private val owned = directories.getValue(MenuPath.Root).toSet()
    private val lock = Mutex()
    private var lacking: Set<MenuPath>? = null

    fun windows(packs: Collection<Pack>): List<Pack> {
        val files = bundledPacks().values.firstNotNullOfOrNull { it.paths[FILES] }
        return packs.mapNotNull { pack ->
            val tabs = pack.window.tabs.filter { translates(it.path) }
            val buttoned = pack.paths.mapValues { (path, shown) ->
                val face = faces[MenuPath.parse(path)]
                val own = shown.within(face)
                face?.actions?.takeIf { it.isNotEmpty() }?.let { own.copy(actions = own.actions + it) } ?: own
            }
            val paths = if (files != null && "file" in buttoned) buttoned + ("file" to files) else buttoned
            val forms = pack.forms.mapValues { (_, form) -> form.within() }
            if (tabs.isEmpty()) null else Pack(pack.schema, pack.platform, pack.menu, PackWindow(pack.window.title, tabs), paths, forms, pack.labels, pack.since, pack.until)
        }
    }

    private fun translates(path: String) = MenuPath.parse(path).let { it in faces || it in aliases }

    private fun PackPath.within(face: LayerFace?) = copy(
        join = join?.takeIf(::translates),
        open = open?.let { PackOpen(it.by, it.paths.filterValues(::translates)) }?.takeIf { it.paths.isNotEmpty() },
        owner = owner?.takeIf { translates(it.path) },
        links = links.filter { it.window != null || translates(it.path) },
        monitor = monitor?.takeIf { it.command == TRAFFIC },
        actions = if (face == null) actions else actions.filter { it.command in face.commands },
    )

    private fun PackForm.within() = PackForm(
        title,
        tabs.filter { it.path == null || translates(it.path) }.map { tab ->
            if (tab.monitor == null || tab.monitor.command == TRAFFIC) tab else PackFormTab(tab.title, tab.fields, tab.readonly, tab.since, tab.until, tab.path, null, tab.chart, tab.graph)
        },
        only, confirm, renamed,
    )

    override suspend fun describe(path: MenuPath): MenuSchema {
        faces[path]?.let { face ->
            if (path in lacking()) missing(path)
            val commands = if (face.kind == NodeKind.Settings) {
                listOf("print", "set") + face.commands.keys
            } else {
                listOf("print") + listOfNotNull("add".takeIf { face.add != null }, "set".takeIf { face.set != null }, "remove".takeIf { face.remove != null }) +
                    face.commands.keys
            }
            return MenuSchema(path, face.kind, shown(path), commands, face.arguments())
        }
        aliases[path]?.let { other -> return base.describe(other).let { MenuSchema(path, it.kind, it.children, it.commands, it.arguments) } }
        if (path in directories) return MenuSchema(path, NodeKind.Directory, shown(path), emptyList(), emptyList())
        if (path.segments.firstOrNull() in owned) missing(path)
        return base.describe(path)
    }

    override suspend fun outline(path: MenuPath): MenuSchema = describe(path)

    override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> {
        val face = faces[path]
        if (face == null) {
            if (path !in aliases && path.segments.firstOrNull() in owned) return flow { missing(path) }
            return base.watch(aliases[path] ?: path, filter, properties)
        }
        val sources = face.sources.map { source ->
            val rows = base.watch(MenuPath.parse(source)).rows().drop(1)
            if (source in face.optional) rows.catch { if (it !is DeviceException) throw it else emit(emptyList()) } else rows
        }
        return combine(sources) { lists -> ListEvent.Snapshot(face.compose(lists.toList()).filter { filter?.matches(it) != false }) }.distinctUntilChanged()
    }

    override suspend fun get(path: MenuPath, id: String?, properties: List<String>?): Item? {
        val face = faces[path] ?: return base.get(aliases[path] ?: path, id, properties)
        return read(face).firstOrNull { id == null || it.id == id }
    }

    override suspend fun count(path: MenuPath, filter: Filter?): Int {
        val face = faces[path] ?: return base.count(aliases[path] ?: path, filter)
        return read(face).count { filter?.matches(it) != false }
    }

    override suspend fun add(path: MenuPath, values: Map<String, String>): String {
        val face = faces[path] ?: return base.add(aliases[path] ?: path, values)
        return (face.add ?: unsupported(path, "add"))(values)
    }

    override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) {
        val face = faces[path] ?: return base.set(aliases[path] ?: path, id, changes)
        (face.set ?: unsupported(path, "set"))(id, changes)
    }

    override suspend fun remove(path: MenuPath, ids: List<String>) {
        val face = faces[path] ?: return base.remove(aliases[path] ?: path, ids)
        (face.remove ?: unsupported(path, "remove"))(ids)
    }

    override suspend fun move(path: MenuPath, ids: List<String>, before: String?) {
        val face = faces[path] ?: return base.move(aliases[path] ?: path, ids, before)
        (face.move ?: unsupported(path, "move"))(ids, before)
    }

    override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> {
        if (command == "monitor-traffic") return base.run(MenuPath.parse("tool"), "traffic", arguments.filterKeys { it == "interface" })
        val face = faces[path] ?: return base.run(aliases[path] ?: path, command, arguments)
        val action = face.commands[command] ?: return flow { unsupported(path, command) }
        return flow { action(arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }) }
    }

    override suspend fun export(path: MenuPath): String =
        base.export(faces[path]?.let { MenuPath.parse(it.sources.first().substringBefore('/')) } ?: aliases[path] ?: path)

    override suspend fun packs(store: TextStore?): List<Pack> = emptyList()

    override suspend fun nativeMenu(): NativeMenu? = null

    private suspend fun shown(path: MenuPath): List<String> {
        val lacking = lacking()
        return directories[path].orEmpty().filter { child ->
            val under = path.segments + child
            (faces.keys + aliases.keys).any { it.segments.take(under.size) == under && it !in lacking }
        }
    }

    private suspend fun lacking(): Set<MenuPath> = lock.withLock {
        lacking ?: run {
            val absent = faces.values.flatMap { it.sources - it.optional }.distinct().filterNot { has(it) }.toSet()
            faces.filterValues { face -> (face.sources - face.optional).any { it in absent } }.keys
        }.also { lacking = it }
    }

    // over /ubus a config that no application of LuCI is installed for is denied to the session, not missing
    private suspend fun has(source: String) = try {
        base.describe(MenuPath.parse(source))
        true
    } catch (e: DeviceException) {
        if (e.category != ErrorCategory.MissingItem && e.category != ErrorCategory.PermissionDenied) throw e
        false
    }

    private suspend fun read(face: LayerFace) = face.compose(
        face.sources.map { source ->
            if (source !in face.optional) {
                rows(source)
            } else {
                try {
                    rows(source)
                } catch (e: DeviceException) {
                    emptyList()
                }
            }
        },
    )

    private suspend fun rows(path: String): List<Item> = base.watch(MenuPath.parse(path)).filterIsInstance<ListEvent.Snapshot>().first().items

    private fun addresses(interfaces: List<Item>, addresses: List<Item>): List<Item> {
        val static = interfaces.filter { it["proto"] == "static" }.flatMap { owner ->
            staticAddresses(owner).filter { ':' !in it }.map { cidr -> Item("${sectionName(owner)}/$cidr", address(cidr, deviceOf(owner))) }
        }
        val shown = static.mapNotNull { it["address"] }.toSet()
        val dynamic = addresses.filter { row -> row["address"].orEmpty().let { ':' !in it && it !in shown } }.map { row ->
            Item("${row["interface"]}/${row["address"]}", address(row["address"].orEmpty(), row["device"].orEmpty()) + ("dynamic" to "true"))
        }
        return static + dynamic
    }

    private fun address(cidr: String, device: String) = mapOf("address" to cidr, "network" to network(cidr), "interface" to device)

    private fun addresses6(interfaces: List<Item>, addresses: List<Item>): List<Item> {
        val static = interfaces.filter { it["proto"] == "static" }.flatMap { owner ->
            uciLines(owner["ip6addr"]).map { cidr -> Item("${sectionName(owner)}/$cidr", mapOf("address" to cidr, "interface" to deviceOf(owner))) }
        }
        val shown = static.mapNotNull { it["address"] }.toSet()
        val dynamic = addresses.filter { row -> row["address"].orEmpty().let { ':' in it && it !in shown } }.map { row ->
            Item("${row["interface"]}/${row["address"]}", mapOf("address" to row["address"].orEmpty(), "interface" to row["device"].orEmpty(), "dynamic" to "true"))
        }
        return static + dynamic
    }

    private fun routes(routes: List<Item>, interfaces: List<Item>, kernel: List<Item>, default: String): List<Item> {
        val four = default == "0.0.0.0/0"
        val installed = kernel.filter { it["type"] == "unicast" }.map { it.copy(values = it.values + ("dst-address" to target(it["dst-address"].orEmpty(), default))) }
        val static = routes.map { route ->
            val owner = interfaces.firstOrNull { sectionName(it) == route["interface"] }
            val destination = if (four) prefixed(route["target"].orEmpty(), route["netmask"]) else route["target"].orEmpty()
            Item(
                route.id,
                buildMap {
                    put("dst-address", destination)
                    put("gateway", route["gateway"] ?: owner?.let(::deviceOf) ?: route["interface"].orEmpty())
                    route["metric"]?.let { put("distance", it) }
                    put("routing-table", route["table"] ?: "main")
                    if (four) route["source"]?.let { put("pref-src", it) }
                    put("static", "true")
                    put("active", "${installed.any { it["dst-address"] == destination }}")
                },
            )
        }
        val shown = static.mapNotNull { it["dst-address"] }.toSet()
        val dynamic = installed.filter { it["dst-address"] !in shown }.map { route ->
            Item(
                "$DYNAMIC ${route.id}",
                buildMap {
                    put("dst-address", route["dst-address"].orEmpty())
                    put("gateway", route["gateway"] ?: route["interface"].orEmpty())
                    route["distance"]?.let { put("distance", it) }
                    put("routing-table", route["table"] ?: "main")
                    if (four) route["pref-src"]?.let { put("pref-src", it) }
                    put("dynamic", "true")
                    put("active", "true")
                    put("connect", "${route["gateway"] == null}")
                },
            )
        }
        return static + dynamic
    }

    private fun wifi(ifaces: List<Item>, devices: List<Item>): List<Item> = ifaces.map { iface ->
        val radio = devices.firstOrNull { sectionName(it) == iface["device"] }
        Item(
            iface.id,
            buildMap {
                put("name", iface["ifname"] ?: sectionName(iface))
                put("configuration.mode", if (iface["mode"] == "sta") "station" else iface["mode"].orEmpty())
                (iface["ssid"] ?: iface["mesh_id"])?.let { put("configuration.ssid", it) }
                iface["encryption"]?.let { put("security.authentication-types", authentications[it.substringBefore('+')] ?: it) }
                iface["key"]?.let { put("security.passphrase", it) }
                radio?.get("band")?.let { put("channel.band", band(it)) }
                radio?.get("channel")?.let { put("channel.frequency", frequency(radio["band"].orEmpty(), it)) }
                radio?.get("htmode")?.let { put("channel.width", width(it)) }
                put("disabled", "${iface["disabled"] in Flag || radio?.get("disabled") in Flag}")
            },
        )
    }

    private fun configuration(iface: Item, devices: List<Item>): Item {
        val radio = devices.firstOrNull { sectionName(it) == iface["device"] }
        return Item(
            iface.id,
            buildMap {
                put("name", iface["ifname"] ?: sectionName(iface))
                put("mode", if (iface["mode"] == "sta") "station" else iface["mode"].orEmpty())
                (iface["ssid"] ?: iface["mesh_id"])?.let { put("ssid", it) }
                radio?.get("country")?.let { put("country", it) }
                put("hide-ssid", "${iface["hidden"] in Flag}")
                iface["maxassoc"]?.let { put("max-clients", it) }
                radio?.get("txpower")?.let { put("tx-power", it) }
                iface["dtim_period"]?.let { put("dtim-period", it) }
            },
        )
    }

    private fun datapath(iface: Item, interfaces: List<Item>): Item {
        val network = uciLines(iface["network"]).firstOrNull()
        return Item(
            iface.id,
            buildMap {
                put("name", iface["ifname"] ?: sectionName(iface))
                interfaces.firstOrNull { sectionName(it) == network }?.let(::deviceOf)?.takeIf { it.isNotEmpty() }?.let { put("bridge", it) }
                put("client-isolation", "${iface["isolate"] in Flag}")
            },
        )
    }

    private fun configurationOptions(changes: Map<String, String?>): Map<String, String?> = buildMap {
        putAll(renamed(changes, mapOf("ssid" to "ssid", "max-clients" to "maxassoc", "dtim-period" to "dtim_period")))
        if ("mode" in changes) put("mode", if (changes["mode"] == "station") "sta" else changes["mode"])
        putAll(flag(changes, "hide-ssid", "hidden"))
    }

    private fun wifiOptions(changes: Map<String, String?>): Map<String, String?> = buildMap {
        if ("configuration.ssid" in changes) put("ssid", changes["configuration.ssid"])
        if ("configuration.mode" in changes) put("mode", if (changes["configuration.mode"] == "station") "sta" else changes["configuration.mode"])
        if ("security.passphrase" in changes) put("key", changes["security.passphrase"])
        if ("security.authentication-types" in changes) {
            val wanted = changes["security.authentication-types"]
            put("encryption", authentications.entries.firstOrNull { it.value == wanted }?.key ?: wanted)
        }
        putAll(flag(changes, "disabled", "disabled"))
    }

    private fun served(pool: Item, interfaces: List<Item>): String =
        interfaces.firstOrNull { sectionName(it) == pool["interface"] }?.let(::deviceOf)?.ifEmpty { null } ?: pool["interface"].orEmpty()

    private fun nd(pool: Item, interfaces: List<Item>): Item {
        val flags = pool["ra_flags"]?.let(::uciLines) ?: listOf("other-config")
        fun time(value: String) = value.toLongOrNull()?.let(::duration) ?: value
        return Item(
            pool.id,
            buildMap {
                put("interface", served(pool, interfaces))
                put("ra-interval", "${time(pool["ra_mininterval"] ?: "200")}-${time(pool["ra_maxinterval"] ?: "600")}")
                put("ra-lifetime", time(pool["ra_lifetime"] ?: "1800"))
                put("ra-preference", pool["ra_preference"] ?: "medium")
                pool["ra_hoplimit"]?.let { put("hop-limit", it) }
                pool["ra_mtu"]?.let { put("mtu", it) }
                put("managed-address-configuration", "${"managed-config" in flags}")
                put("other-configuration", "${"other-config" in flags}")
                put("advertise-dns", "${pool["dns_service"]?.let { it in Flag } ?: true}")
                pool["dns"]?.let { put("dns", uciLines(it).joinToString(",")) }
                put("disabled", "${pool["ra"] !in setOf("server", "relay", "hybrid")}")
            },
        )
    }

    // an empty ra_flags is written as none, as odhcpd would send other-config for an empty one
    private fun ndOptions(raFlags: String?, changes: Map<String, String?>): Map<String, String?> = buildMap {
        fun on(field: String) = changes[field] == "true" || changes[field] == "yes"
        if ("disabled" in changes) put("ra", if (on("disabled")) "disabled" else "server")
        if ("ra-interval" in changes) {
            val times = changes["ra-interval"].orEmpty().split('-')
            if (times.size != 2) throw DeviceException(ErrorCategory.ArgumentValue, "${changes["ra-interval"]} is not an interval such as 3m20s-10m")
            put("ra_mininterval", seconds(times[0]))
            put("ra_maxinterval", seconds(times[1]))
        }
        if ("ra-lifetime" in changes) put("ra_lifetime", changes["ra-lifetime"]?.let(::seconds))
        putAll(renamed(changes, mapOf("ra-preference" to "ra_preference", "hop-limit" to "ra_hoplimit", "mtu" to "ra_mtu")))
        if ("managed-address-configuration" in changes || "other-configuration" in changes) {
            val now = raFlags?.let(::uciLines) ?: listOf("other-config")
            fun wanted(field: String, flag: String) = if (field in changes) on(field) else flag in now
            val flags = now.filter { it !in setOf("managed-config", "other-config", "none") } +
                listOfNotNull("managed-config".takeIf { wanted("managed-address-configuration", it) }, "other-config".takeIf { wanted("other-configuration", it) })
            put("ra_flags", flags.ifEmpty { listOf("none") }.joinToString(LIST))
        }
        if ("advertise-dns" in changes) put("dns_service", if (on("advertise-dns")) null else "0")
        if ("dns" in changes) put("dns", changes["dns"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(LIST)?.ifEmpty { null })
    }

    private fun advertised(prefixes: List<Item>, pools: List<Item>, interfaces: List<Item>): List<Item> {
        val sending = pools.filter { it["ra"] in setOf("server", "relay", "hybrid") }.associateBy { it["interface"] }
        return prefixes.mapNotNull { row ->
            val pool = sending[row["interface"]] ?: return@mapNotNull null
            val prefix = row["prefix"].orEmpty()
            val length = prefix.substringAfter('/').toIntOrNull()?.takeIf { it <= 64 } ?: return@mapNotNull null
            Item(
                row.id,
                buildMap {
                    put("prefix", prefix)
                    put("interface", served(pool, interfaces))
                    row["valid-lifetime"]?.let { put("valid-lifetime", it) }
                    row["preferred-lifetime"]?.let { put("preferred-lifetime", it) }
                    put("on-link", "true")
                    put("autonomous", "${length == 64 && (pool["ra_slaac"]?.let { it in Flag } ?: true)}")
                    put("dynamic", "true")
                },
            )
        }
    }

    private fun bindings(hosts: List<Item>, leases: List<Item>): List<Item> {
        val static = hosts.filter { uciLines(it["duid"]).isNotEmpty() }.map { host ->
            Item(
                host.id,
                buildMap {
                    host["hostid"]?.let { put("address", it) }
                    put("duid", uciLines(host["duid"]).first())
                    host["name"]?.let { put("comment", it) }
                },
            )
        }
        val fixed = hosts.flatMap { uciLines(it["duid"]) }.map { it.lowercase() }.toSet()
        val dynamic = leases.filter { lease ->
            val duid = lease["duid"].orEmpty().lowercase()
            duid !in fixed && "$duid%${lease["iaid"].orEmpty().lowercase()}" !in fixed
        }.map { lease ->
            Item(
                "$DYNAMIC ${lease.id}",
                buildMap {
                    put("address", lease["address"].orEmpty())
                    put("duid", lease["duid"].orEmpty())
                    lease["iaid"]?.let { put("iaid", it) }
                    put("server", lease["interface"].orEmpty())
                    lease["life-time"]?.let { put("life-time", it) }
                    lease["host-name"]?.let { put("comment", it) }
                    put("dynamic", "true")
                },
            )
        }
        return static + dynamic
    }

    private fun bindingOptions(values: Map<String, String?>): Map<String, String?> = renamed(values, mapOf("duid" to "duid", "address" to "hostid", "comment" to "name"))

    private fun schedule(task: Item): Item {
        val (minute, hour, day, month, weekday) = listOf("minute", "hour", "day", "month", "weekday").map { task[it].orEmpty() }
        val command = task["command"].orEmpty()
        return Item(
            task.id,
            buildMap {
                put("name", command)
                put("on-event", command)
                put("owner", "root")
                put("comment", "cron $minute $hour $day $month $weekday")
                timing(minute, hour, day, month, weekday)?.let { (start, every, days) ->
                    put("start-time", start)
                    put("interval", every)
                    days?.let { put("days", it) }
                }
            },
        )
    }

    // a step that does not divide the day or the hour starts again at midnight or at the full hour
    private fun timing(minute: String, hour: String, day: String, month: String, weekday: String): Triple<String, String, String?>? {
        if (day != "*" || month != "*") return null
        val days = if (weekday == "*") null else weekdayNames(weekday) ?: return null
        val at = minute.toIntOrNull()?.takeIf { it in 0..59 }
        fun step(field: String, whole: Int) = field.removePrefix("*/").toIntOrNull()?.takeIf { field.startsWith("*/") && it > 0 && whole % it == 0 }
        fun start(hours: Int, minutes: Int) = "${"$hours".padStart(2, '0')}:${"$minutes".padStart(2, '0')}:00"
        val hours = step(hour, 24)
        val minutes = step(minute, 60)
        return when {
            at != null && hour.toIntOrNull() in 0..23 -> Triple(start(hour.toInt(), at), "1d", days)
            at != null && hour == "*" -> Triple(start(0, at), "1h", days)
            at != null && hours != null -> Triple(start(0, at), "${hours}h", days)
            minute == "*" && hour == "*" -> Triple(start(0, 0), "1m", days)
            minutes != null && hour == "*" -> Triple(start(0, 0), "${minutes}m", days)
            else -> null
        }
    }

    private fun weekdayNames(field: String): String? = field.split(',').flatMap { part ->
        val bounds = part.split('-').map { bound -> bound.toIntOrNull()?.takeIf { it in 0..7 } ?: weekdays.indexOf(bound.lowercase()).takeIf { it >= 0 } ?: return null }
        when (bounds.size) {
            1 -> bounds
            2 -> (bounds[0]..bounds[1]).toList()
            else -> return null
        }
    }.map { weekdays[it % 7] }.distinct().joinToString(",")

    private fun cron(values: Map<String, String?>): Map<String, String> {
        val command = values["on-event"]?.takeIf { it.isNotBlank() } ?: required("on-event")
        val start = values["start-time"]?.takeIf { it.isNotBlank() } ?: "00:00:00"
        if (start == "startup") throw DeviceException(ErrorCategory.ArgumentValue, "OpenWrt runs commands at startup from /etc/rc.local, not from cron")
        val time = Regex("""(\d{1,2}):(\d{2}):(\d{2})""").matchEntire(start)?.groupValues?.drop(1)?.map(String::toInt)
            ?: throw DeviceException(ErrorCategory.ArgumentValue, "$start is not a time of day such as 03:00:00")
        val (hour, minute, second) = time
        if (second != 0) throw DeviceException(ErrorCategory.ArgumentValue, "cron starts a task on a whole minute, not at $start")
        val every = values["interval"]?.let(::seconds)?.toLong() ?: throw DeviceException(ErrorCategory.ArgumentValue, "cron needs an interval such as 1d, 1h or 10m")
        val weekday = values["days"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.ifEmpty { null }?.joinToString(",") { day ->
            weekdays.indexOf(day).takeIf { it >= 0 }?.let { "$it" } ?: throw DeviceException(ErrorCategory.ArgumentValue, "$day is not a day such as mon")
        } ?: "*"
        val (minutes, hours) = when {
            every == 86_400L -> "$minute" to "$hour"
            every == 3_600L -> "$minute" to "*"
            every % 3_600 == 0L && 86_400 % every == 0L && hour % (every / 3_600) == 0L -> "$minute" to "*/${every / 3_600}"
            every == 60L -> "*" to "*"
            every < 3_600 && every % 60 == 0L && 3_600 % every == 0L && minute % (every / 60) == 0L -> "*/${every / 60}" to "*"
            else -> throw DeviceException(
                ErrorCategory.ArgumentValue,
                "cron repeats a task every day, every hour, or every few hours or minutes that divide a day or an hour, not every ${values["interval"]} from $start; " +
                    "a task once a week is 1d on its day",
            )
        }
        return mapOf("minute" to minutes, "hour" to hours, "day" to "*", "month" to "*", "weekday" to weekday, "command" to command)
    }

    private fun groups(logins: List<Item>): List<Item> {
        val own = logins.map(::group).filter { it != "full" && it != "read" }.distinct()
        return listOf(
            Item("full", mapOf("name" to "full", "policy" to "read, write: every ACL (*), as root has it")),
            Item("read", mapOf("name" to "read", "policy" to "read: every ACL (*), no writing")),
        ) + own.map { Item(it, mapOf("name" to it, "policy" to "ACLs of the login: $it")) }
    }

    private fun group(login: Item): String {
        val read = uciLines(login["read"])
        val write = uciLines(login["write"])
        return when {
            "*" in write -> "full"
            "*" in read && write.isEmpty() -> "read"
            else -> (read + write).distinct().joinToString(",")
        }
    }

    private fun rights(group: String?): Map<String, String?> = when (group) {
        "full" -> mapOf("read" to "*", "write" to "*")
        "read" -> mapOf("read" to "*", "write" to null)
        else -> throw DeviceException(ErrorCategory.ArgumentValue, "a login of rpcd is in full or read here; ACLs of its own are set in /etc/config/rpcd")
    }

    private fun pppName(client: Item) = "${client["proto"]}-${sectionName(client)}"

    private fun pppClient(proto: String, fields: Map<String, String>, needed: String, arguments: suspend () -> List<Argument>) = LayerFace(
        NodeKind.List,
        listOf(NETWORK_INTERFACE, INTERFACES),
        arguments,
        { (interfaces, states) ->
            val running = states.associateBy { it.id }
            interfaces.filter { it["proto"] == proto }.map { client ->
                Item(
                    client.id,
                    buildMap {
                        put("name", pppName(client))
                        if ("interface" in fields) deviceOf(client).takeIf { it.isNotEmpty() }?.let { put("interface", it) }
                        for ((field, option) in fields - "interface") client[option]?.let { put(field, it) }
                        put("add-default-route", "${client["defaultroute"]?.let { it in Flag } ?: true}")
                        put("use-peer-dns", "${client["peerdns"]?.let { it in Flag } ?: true}")
                        running[sectionName(client)]?.get("running")?.let { put("running", it) }
                        put("disabled", "${client["disabled"] in Flag}")
                    },
                )
            }
        },
        add = { values ->
            val name = values["name"]?.removePrefix("$proto-") ?: required("name")
            if (values[needed] == null) required(needed)
            val options = mapOf("defaultroute" to "0", "peerdns" to "0") + pppOptions(values, fields)
            base.add(path(NETWORK_INTERFACE), mapOf(NAME to name, "proto" to proto) + options.present())
        },
        set = { id, changes ->
            if ("name" in changes) throw DeviceException(ErrorCategory.ArgumentValue, "an interface of OpenWrt keeps the name of its section here")
            base.set(path(NETWORK_INTERFACE), id, pppOptions(changes, fields))
        },
        remove = { ids -> base.remove(path(NETWORK_INTERFACE), ids) },
        optional = setOf(INTERFACES),
    )

    private fun tunnelArguments() = listOf(
        Argument("name", ValueType.Text()),
        Argument("connect-to", ValueType.Text()),
        Argument("user", ValueType.Text(), optional = true),
        Argument("password", ValueType.Secret, optional = true),
        Argument("max-mtu", ValueType.Integer(68, 65535), optional = true),
        Argument("add-default-route", ValueType.YesNo),
        Argument("default-route-distance", ValueType.Integer(0, 255), optional = true),
        Argument("use-peer-dns", ValueType.YesNo),
        Argument("running", ValueType.Text(), readOnly = true),
        Argument("disabled", ValueType.YesNo),
    )

    // netifd has the default route and the DNS of the peer on unless told otherwise, so only off is written
    private fun pppOptions(values: Map<String, String?>, fields: Map<String, String>): Map<String, String?> = buildMap {
        putAll(renamed(values, fields))
        if ("add-default-route" in values) put("defaultroute", if (values["add-default-route"] == "false" || values["add-default-route"] == "no") "0" else null)
        if ("use-peer-dns" in values) put("peerdns", if (values["use-peer-dns"] == "false" || values["use-peer-dns"] == "no") "0" else null)
        putAll(flag(values, "disabled", "disabled"))
    }

    private fun security(iface: Item): Item {
        val parts = iface["encryption"]?.split('+')
        return Item(
            iface.id,
            buildMap {
                put("name", iface["ifname"] ?: sectionName(iface))
                parts?.let { put("authentication-types", authentications[it.first()] ?: it.first()) }
                parts?.drop(1)?.takeIf { it.isNotEmpty() }?.let { put("encryption", it.joinToString(",") { cipher -> ciphers[cipher] ?: cipher }) }
                iface["key"]?.let { put("passphrase", it) }
                iface["ieee80211w"]?.let { put("management-protection", protections[it] ?: it) }
                put("ft", "${iface["ieee80211r"] in Flag}")
                iface["ft_over_ds"]?.let { put("ft-over-ds", "${it in Flag}") }
                iface["mobility_domain"]?.let { put("ft-mobility-domain", it) }
                put("wps", if (iface["wps_pushbutton"] in Flag) "push-button" else "disabled")
            },
        )
    }

    // ft_over_ds is written out, as it was on before 23.05
    private fun securityOptions(encryption: String?, changes: Map<String, String?>): Map<String, String?> = buildMap {
        val now = encryption?.split('+').orEmpty()
        if ("authentication-types" in changes || "encryption" in changes) {
            val authentication = if ("authentication-types" in changes) {
                changes["authentication-types"]?.let { wanted -> authentications.entries.firstOrNull { it.value == wanted }?.key ?: wanted }
            } else {
                now.firstOrNull()
            } ?: "none"
            val chosen = if ("encryption" in changes) {
                changes["encryption"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { name -> ciphers.entries.firstOrNull { it.value == name }?.key ?: name }
            } else {
                now.drop(1)
            }
            put("encryption", (listOf(authentication) + chosen.takeIf { authentication != "none" }.orEmpty()).joinToString("+"))
        }
        if ("passphrase" in changes) put("key", changes["passphrase"])
        if ("management-protection" in changes) put("ieee80211w", changes["management-protection"]?.let { wanted -> protections.entries.firstOrNull { it.value == wanted }?.key ?: wanted })
        putAll(flag(changes, "ft", "ieee80211r"))
        if ("ft-over-ds" in changes) put("ft_over_ds", if (changes["ft-over-ds"] == "true" || changes["ft-over-ds"] == "yes") "1" else "0")
        if ("ft-mobility-domain" in changes) put("mobility_domain", changes["ft-mobility-domain"])
        if ("wps" in changes) put("wps_pushbutton", if (changes["wps"] == "push-button") "1" else null)
    }

    private suspend fun addAddress(cidr: String, device: String): String {
        val interfaces = rows(NETWORK_INTERFACE)
        val owner = interfaces.firstOrNull { it["proto"] == "static" && deviceOf(it) == device }
        if (owner != null) {
            base.set(path(NETWORK_INTERFACE), owner.id, mapOf("ipaddr" to (staticAddresses(owner) + cidr).distinct().joinToString(LIST), "netmask" to null))
            return "${sectionName(owner)}/$cidr"
        }
        val taken = interfaces.map(::sectionName).toSet()
        val stem = device.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) stem else "${stem}_$it" }.first { it !in taken }
        base.add(path(NETWORK_INTERFACE), mapOf(NAME to name, "proto" to "static", "device" to device, "ipaddr" to cidr))
        return "$name/$cidr"
    }

    private suspend fun withoutAddress(owner: Item, cidr: String) {
        val rest = staticAddresses(owner) - cidr
        base.set(path(NETWORK_INTERFACE), owner.id, mapOf("ipaddr" to rest.joinToString(LIST).ifEmpty { null }, "netmask" to null))
    }

    private suspend fun staticAddress(id: String?): Pair<Item, String> {
        val (name, cidr) = (id ?: "").split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val owner = rows(NETWORK_INTERFACE).firstOrNull { sectionName(it) == name && it["proto"] == "static" && cidr in staticAddresses(it) }
            ?: throw DeviceException(ErrorCategory.ArgumentValue, "cannot change dynamic address $cidr")
        return owner to cidr
    }

    private suspend fun deviceNames() = rows(DEVICES).map { it.id }

    private fun queueOptions(values: Map<String, String?>): Map<String, String?> = buildMap {
        if ("target" in values) put("interface", values["target"])
        values["max-limit"]?.let { limit ->
            val parts = limit.split('/')
            if (parts.size != 2) throw DeviceException(ErrorCategory.ArgumentValue, "max-limit is upload/download, as 10M/85M")
            put("upload", kilobits(parts[0]))
            put("download", kilobits(parts[1]))
        }
        if ("disabled" in values) put("enabled", if (values["disabled"] == "true" || values["disabled"] == "yes") "0" else "1")
    }

    private suspend fun wireguardNames() = rows(NETWORK_INTERFACE).filter { it["proto"] == "wireguard" }.map(::sectionName)

    private fun wireguardOptions(values: Map<String, String?>) = renamed(values, mapOf("mtu" to "mtu", "listen-port" to "listen_port")) + flag(values, "disabled", "disabled")

    private fun peerOptions(values: Map<String, String?>): Map<String, String?> = buildMap {
        val names = mapOf("public-key" to "public_key", "preshared-key" to "preshared_key", "endpoint-address" to "endpoint_host", "endpoint-port" to "endpoint_port", "comment" to "description")
        putAll(renamed(values, names))
        if ("allowed-address" in values) put("allowed_ips", values["allowed-address"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(LIST)?.ifEmpty { null })
        if ("persistent-keepalive" in values) put("persistent_keepalive", values["persistent-keepalive"]?.let(::seconds))
        putAll(flag(values, "disabled", "disabled"))
    }

    private suspend fun addPort(bridge: String, port: String): String {
        val owner = rows(NETWORK_DEVICE).firstOrNull { it["type"] == "bridge" && it["name"] == bridge } ?: throw DeviceException(ErrorCategory.ArgumentValue, "no such bridge $bridge")
        base.set(path(NETWORK_DEVICE), owner.id, mapOf("ports" to (uciLines(owner["ports"]) + port).distinct().joinToString(LIST)))
        return "${owner.id} $port"
    }

    private suspend fun removePorts(ids: List<String>) {
        val devices = rows(NETWORK_DEVICE)
        ids.map(::port).groupBy({ it.first }, { it.second }).forEach { (bridge, ports) ->
            val owner = devices.firstOrNull { it.id == bridge } ?: return@forEach
            base.set(path(NETWORK_DEVICE), owner.id, mapOf("ports" to (uciLines(owner["ports"]) - ports.toSet()).joinToString(LIST).ifEmpty { null }))
        }
    }

    private fun port(id: String?): Pair<String, String> = (id ?: "").split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }

    private suspend fun bridgeName(id: String) = rows(NETWORK_DEVICE).firstOrNull { it.id == id }?.get("name").orEmpty()

    private fun leases(hosts: List<Item>, leases: List<Item>): List<Item> {
        val given = leases.associateBy { it["mac-address"].orEmpty().lowercase() }
        val static = hosts.filter { it["mac"] != null || it["ip"] != null || it["duid"] == null }.map { host ->
            val mac = uciLines(host["mac"]).firstOrNull().orEmpty()
            val lease = given[mac.lowercase()]
            Item(
                host.id,
                buildMap {
                    put("address", host["ip"].orEmpty())
                    put("mac-address", mac)
                    host["name"]?.let { put("host-name", it) }
                    put("status", if (lease != null) "bound" else "waiting")
                    lease?.get("expires")?.let { put("expires", it) }
                },
            )
        }
        val macs = hosts.flatMap { uciLines(it["mac"]) }.map { it.lowercase() }.toSet()
        val dynamic = leases.filter { it["mac-address"].orEmpty().lowercase() !in macs }.map { lease ->
            Item(
                "$DYNAMIC ${lease["mac-address"]}",
                buildMap {
                    put("address", lease["address"].orEmpty())
                    put("mac-address", lease["mac-address"].orEmpty())
                    lease["host-name"]?.takeIf { it != "*" }?.let { put("host-name", it) }
                    put("status", "bound")
                    lease["expires"]?.let { put("expires", it) }
                    put("dynamic", "true")
                },
            )
        }
        return static + dynamic
    }

    private fun staticLease(id: String?): String = id?.takeUnless { it.startsWith("$DYNAMIC ") } ?: throw DeviceException(ErrorCategory.ArgumentValue, "cannot change dynamic lease")

    private suspend fun makeStatic(ids: List<String>) {
        val leases = rows(LEASES)
        for (id in ids.filter { it.startsWith("$DYNAMIC ") }) {
            val mac = id.removePrefix("$DYNAMIC ")
            val lease = leases.firstOrNull { it["mac-address"].equals(mac, ignoreCase = true) } ?: continue
            base.add(path(HOSTS), mapOf("mac" to mac, "ip" to lease["address"].orEmpty()) + listOfNotNull(lease["host-name"]?.takeIf { it != "*" }?.let { "name" to it }))
        }
    }

    private suspend fun makeStatic6(ids: List<String>) {
        val leases = rows(LEASES6)
        for (id in ids.filter { it.startsWith("$DYNAMIC ") }) {
            val lease = leases.firstOrNull { "$DYNAMIC ${it.id}" == id } ?: continue
            val bytes = lease["address"].orEmpty().substringBefore(',').substringBefore('/').let(::span)?.start
            val token = bytes?.takeLast(4)?.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }?.trimStart('0')?.takeIf { it.isNotEmpty() }
            base.add(path(HOSTS), mapOf("duid" to lease["duid"].orEmpty()) + listOfNotNull(token?.let { "hostid" to it }, lease["host-name"]?.let { "name" to it }))
        }
    }

    private suspend fun routeOptions(values: Map<String, String?>): Map<String, String?> = buildMap {
        if ("gateway" in values) {
            val gateway = values["gateway"].orEmpty()
            val interfaces = rows(NETWORK_INTERFACE)
            val byDevice = interfaces.firstOrNull { deviceOf(it) == gateway }
            if (byDevice != null) {
                put("interface", sectionName(byDevice))
                put("gateway", null)
            } else {
                val held = rows(ADDRESSES)
                val owner = held.firstOrNull { ':' !in gateway && contains(it["address"].orEmpty(), gateway) }?.get("interface")
                    ?: interfaces.firstOrNull { iface -> staticAddresses(iface).any { contains(it, gateway) } }?.let(::sectionName)
                    ?: throw DeviceException(ErrorCategory.ArgumentValue, "no interface reaches gateway $gateway")
                put("interface", owner)
                put("gateway", gateway)
            }
        }
        if ("distance" in values) put("metric", values["distance"])
        if ("routing-table" in values) put("table", values["routing-table"]?.takeIf { it != "main" })
        if ("pref-src" in values) put("source", values["pref-src"])
    }

    private fun staticRoute(id: String?): String {
        if (id == null || id.startsWith("$DYNAMIC ")) throw DeviceException(ErrorCategory.ArgumentValue, "a route the router holds by itself is not changed here")
        return id
    }

    private suspend fun route6Options(values: Map<String, String?>): Map<String, String?> = buildMap {
        if ("gateway" in values) {
            val gateway = values["gateway"].orEmpty()
            val interfaces = rows(NETWORK_INTERFACE)
            val zone = gateway.substringAfter('%', "")
            val byDevice = interfaces.firstOrNull { deviceOf(it) == gateway }
            when {
                byDevice != null -> {
                    put("interface", sectionName(byDevice))
                    put("gateway", null)
                }
                zone.isNotEmpty() -> {
                    val owner = interfaces.firstOrNull { deviceOf(it) == zone } ?: throw DeviceException(ErrorCategory.ArgumentValue, "no interface stands on $zone")
                    put("interface", sectionName(owner))
                    put("gateway", gateway.substringBefore('%'))
                }
                else -> {
                    val address = span(gateway)?.takeIf { ':' in gateway } ?: throw DeviceException(ErrorCategory.ArgumentValue, "$gateway is no IPv6 address and no interface")
                    fun reaches(prefix: String) = ':' in prefix && span(prefix)?.let(address::within) == true
                    val owner = rows(ADDRESSES).firstOrNull { reaches(it["address"].orEmpty()) }?.get("interface")
                        ?: interfaces.firstOrNull { iface -> uciLines(iface["ip6addr"]).any(::reaches) }?.let(::sectionName)
                        ?: throw DeviceException(ErrorCategory.ArgumentValue, "no interface reaches gateway $gateway")
                    put("interface", owner)
                    put("gateway", gateway)
                }
            }
        }
        if ("distance" in values) put("metric", values["distance"])
        if ("routing-table" in values) put("table", values["routing-table"]?.takeIf { it != "main" })
    }

    private fun bridgeOptions(values: Map<String, String?>): Map<String, String?> = buildMap {
        if ("name" in values) put("name", values["name"])
        if ("mtu" in values) put("mtu", values["mtu"])
        if ("mac-address" in values) put("macaddr", values["mac-address"])
        if ("protocol-mode" in values) put("stp", if (values["protocol-mode"] == "none") null else "1")
        putAll(flag(values, "vlan-filtering", "vlan_filtering"))
    }

    private val vlanFields = mapOf("name" to "name", "vlan-id" to "vid", "interface" to "ifname", "mtu" to "mtu")

    private fun vlanOptions(values: Map<String, String?>) = renamed(values, vlanFields)

    private fun renamed(values: Map<String, String?>, names: Map<String, String>): Map<String, String?> =
        values.filterKeys { it in names }.mapKeys { names.getValue(it.key) }

    private fun flag(values: Map<String, String?>, field: String, option: String): Map<String, String?> =
        if (field in values) mapOf(option to if (values[field] == "true" || values[field] == "yes") "1" else null) else emptyMap()

    private fun unsupported(path: MenuPath, command: String): Nothing = throw DeviceException(ErrorCategory.Unsupported, "$path has no $command here")

    private fun missing(path: MenuPath): Nothing = throw DeviceException(ErrorCategory.MissingItem, "no such command or directory ($path)")

    private companion object {
        const val TRAFFIC = "interface/monitor-traffic"
        const val SYSTEM = "system/system"
        const val DEVICES = "status/devices"
        const val ADDRESSES = "status/addresses"
        const val LEASES = "status/dhcp-leases"
        const val LEASES6 = "status/dhcp6-leases"
        const val PREFIXES = "status/prefixes"
        const val NETWORK_INTERFACE = "network/interface"
        const val NETWORK_DEVICE = "network/device"
        const val NETWORK_ROUTE = "network/route"
        const val DNSMASQ = "dhcp/dnsmasq"
        const val DOMAINS = "dhcp/domain"
        const val POOLS = "dhcp/dhcp"
        const val HOSTS = "dhcp/host"
        const val NETWORK_ROUTE6 = "network/route6"
        const val KERNEL_ROUTES = "status/routes"
        const val KERNEL_ROUTES6 = "status/routes6"
        const val RESOURCE = "status/resource"
        const val TIMESERVER = "system/timeserver"
        const val INTERFACES = "status/interfaces"
        const val LOGINS = "rpcd/login"
        const val TASKS = "status/scheduler"
        val weekdays = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
        const val PPPOE = "pppoe"
        const val PPTP = "pptp"
        const val L2TP = "l2tp"
        const val WIFI_IFACES = "wireless/wifi-iface"
        const val WIFI_DEVICES = "wireless/wifi-device"
        const val STATIONS = "status/wireless-clients"
        const val FILES = "status/files"
        const val SQM_QUEUES = "sqm/queue"
        const val WIREGUARD_INTERFACES = "status/wireguard-interfaces"
        const val WIREGUARD_KEY = "status/wireguard-key"
        const val WIREGUARD_STATUS = "status/wireguard"
        const val WIREGUARD_PEERS = "network/wireguard_*"
        const val PEER_TYPE = "wireguard_"
        const val TYPE = ".type"
        const val NAME = ".name"
        const val DYNAMIC = "dynamic"
        val Flag = ValueType.Flag.on

        fun path(text: String) = MenuPath.parse(text)

        fun staticAddresses(item: Item): List<String> = uciLines(item["ipaddr"]).map { prefixed(it, item["netmask"]) }

        val authentications = mapOf(
            "none" to "",
            "psk" to "wpa-psk",
            "psk2" to "wpa2-psk",
            "psk-mixed" to "wpa-psk,wpa2-psk",
            "sae" to "wpa3-psk",
            "sae-mixed" to "wpa2-psk,wpa3-psk",
            "owe" to "owe",
            "wpa2" to "wpa2-eap",
            "wpa3" to "wpa3-eap",
        )

        val pppFields = mapOf("user" to "username", "password" to "password", "max-mtu" to "mtu", "default-route-distance" to "metric")
        val pppoeFields = pppFields + mapOf("service-name" to "service", "ac-name" to "ac", "host-uniq" to "host_uniq", "interface" to "device")
        val tunnelFields = pppFields + mapOf("connect-to" to "server")

        val dialers = mapOf(PPPOE to "pppoe-out", PPTP to "pptp-out", L2TP to "l2tp-out")

        val ciphers = mapOf("ccmp" to "ccmp", "aes" to "ccmp", "gcmp" to "gcmp", "ccmp256" to "ccmp-256", "gcmp256" to "gcmp-256", "tkip" to "tkip")

        val protections = mapOf("0" to "disabled", "1" to "allowed", "2" to "required")

        fun band(band: String) = band.removeSuffix("g") + "ghz"

        fun frequency(band: String, channel: String): String {
            val number = channel.toIntOrNull() ?: return channel
            return when (band) {
                "2g" -> if (number == 14) "2484" else "${2407 + 5 * number}"
                "5g" -> "${5000 + 5 * number}"
                "6g" -> "${5950 + 5 * number}"
                else -> channel
            }
        }

        fun width(htmode: String) = htmode.takeLastWhile { it.isDigit() }.takeIf { it.isNotEmpty() }?.let { "${it}mhz" } ?: htmode

        fun rate(kilobits: String?): String {
            val value = kilobits?.toLongOrNull()?.takeIf { it > 0 } ?: return "0"
            return when {
                value % 1_000_000 == 0L -> "${value / 1_000_000}G"
                value % 1_000 == 0L -> "${value / 1_000}M"
                else -> "${value}k"
            }
        }

        fun kilobits(rate: String): String {
            val bits = ValueType.Integer(min = 0, scaled = true).number(rate.trim()) ?: throw DeviceException(ErrorCategory.ArgumentValue, "$rate is not a rate such as 85M")
            return "${bits / 1_000}"
        }

        fun seconds(time: String): String? {
            val text = time.trim()
            if (text.isEmpty() || text == "off" || text == "0") return null
            text.toLongOrNull()?.let { return "$it" }
            val units = mapOf('d' to 86_400L, 'h' to 3_600L, 'm' to 60L, 's' to 1L)
            val parts = Regex("(\\d+)([dhms])").findAll(text).toList()
            if (parts.isEmpty() || parts.sumOf { it.value.length } != text.length) throw DeviceException(ErrorCategory.ArgumentValue, "$time is not a time such as 25s")
            return "${parts.sumOf { it.groupValues[1].toLong() * units.getValue(it.groupValues[2][0]) }}"
        }

        fun duration(seconds: Long): String {
            var rest = seconds
            return listOf(86_400L to "d", 3_600L to "h", 60L to "m", 1L to "s").mapNotNull { (size, unit) ->
                val count = rest / size
                rest %= size
                if (count > 0) "$count$unit" else null
            }.joinToString("").ifEmpty { "0s" }
        }

        fun deviceType(type: String, devtype: String = "") = when {
            type == "Network device" -> if (devtype == "none") "virtual" else "ether"
            type == "bridge" -> "bridge"
            "VLAN" in type || type == "8021q" -> "vlan"
            type == "wireguard" -> "wg"
            else -> type.lowercase()
        }

        fun prefixed(address: String, netmask: String? = null): String = when {
            '/' in address || ':' in address -> address
            netmask != null -> "$address/${prefixLength(netmask)}"
            else -> "$address/32"
        }

        fun target(destination: String, default: String) = if (destination == "default") default else prefixed(destination)

        fun prefixLength(netmask: String) = netmask.split('.').mapNotNull { it.toIntOrNull() }.sumOf { it.countOneBits() }

        fun network(cidr: String): String {
            val address = ip(cidr.substringBefore('/')) ?: return ""
            val length = cidr.substringAfter('/', "32").toIntOrNull() ?: 32
            return dotted(address and mask(length))
        }

        fun contains(cidr: String, address: String): Boolean {
            val network = ip(cidr.substringBefore('/')) ?: return false
            val host = ip(address) ?: return false
            val mask = mask(cidr.substringAfter('/', "32").toIntOrNull() ?: 32)
            return network and mask == host and mask
        }
    }
}

internal class LayerFace(
    val kind: NodeKind,
    val sources: List<String>,
    val arguments: suspend () -> List<Argument>,
    val compose: (List<List<Item>>) -> List<Item>,
    val add: (suspend (Map<String, String>) -> String)? = null,
    val set: (suspend (String?, Map<String, String?>) -> Unit)? = null,
    val remove: (suspend (List<String>) -> Unit)? = null,
    val commands: Map<String, suspend (List<String>) -> Unit> = emptyMap(),
    val move: (suspend (List<String>, String?) -> Unit)? = null,
    val optional: Set<String> = emptySet(),
    val actions: List<PackAction> = emptyList(),
)

internal fun sectionName(item: Item) = item[".name"] ?: item.id

// device since 21.02, ifname before
internal fun deviceOf(item: Item) = item["device"] ?: item["ifname"].orEmpty()

internal fun uciLines(value: String?) = value?.split(LIST)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

internal fun dhcpRange(cidr: String, start: Long, limit: Long): String? {
    val address = ip(cidr.substringBefore('/')) ?: return null
    val length = cidr.substringAfter('/', "32").toIntOrNull() ?: return null
    val network = address and mask(length)
    val first = network + start
    val last = minOf(first + limit - 1, (network or (mask(length) xor 0xFFFFFFFFL)) - 1)
    return if (start < 1 || first > last) null else "${dotted(first)}-${dotted(last)}"
}

private fun ip(text: String): Long? {
    val parts = text.split('.').mapNotNull { it.toLongOrNull()?.takeIf { part -> part in 0..255 } }
    return if (parts.size == 4) parts.fold(0L) { acc, part -> acc * 256 + part } else null
}

private fun mask(length: Int) = if (length <= 0) 0L else (0xFFFFFFFFL shl (32 - length)) and 0xFFFFFFFFL

private fun dotted(value: Long) = (3 downTo 0).joinToString(".") { "${(value shr (it * 8)) and 255}" }

internal fun Map<String, String?>.present(): Map<String, String> = filterValues { it != null }.mapValues { it.value.orEmpty() }

internal fun required(field: String): Nothing = throw DeviceException(ErrorCategory.ArgumentValue, "$field is required")
