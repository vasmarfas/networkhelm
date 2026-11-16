package com.vasmarfas.networkhelm.search

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.session.DeviceSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class Place(path: String, val fields: List<String>, val filter: Filter? = null, val big: Boolean = false) {
    val path = MenuPath.parse(path)
}

sealed interface Finding {
    val place: Place

    class Hit(override val place: Place, val item: Item, val field: String) : Finding

    class TooBig(override val place: Place, val count: Int) : Finding
}

const val SearchLimit = 20_000

fun places(platform: Platform): List<Place> = when (platform) {
    Platform.RouterOs -> routerOsPlaces
    Platform.OpenWrt -> openWrtPlaces
    Platform.Ssh -> emptyList()
    Platform.Opnsense -> opnsensePlaces
    Platform.Snmp -> snmpPlaces
    Platform.Upnp -> upnpPlaces
    Platform.Pfsense -> pfsensePlaces
    Platform.Keenetic -> keeneticPlaces
    Platform.Vyos -> vyosPlaces
}

fun find(session: DeviceSession, lookup: Lookup, places: List<Place> = places(session.device.platform)): Flow<Finding> = channelFlow {
    val permits = Semaphore(4)
    for (place in places) {
        launch {
            permits.withPermit {
                try {
                    val count = if (place.big) session.count(place.path, place.filter) else 0
                    if (count > SearchLimit) {
                        send(Finding.TooBig(place, count))
                    } else {
                        val items = session.watch(place.path, place.filter, place.fields).filterIsInstance<ListEvent.Snapshot>().first().items
                        for (item in items) {
                            val field = place.fields.firstOrNull { field -> item[field]?.let(lookup::matches) == true } ?: continue
                            send(Finding.Hit(place, item, field))
                        }
                    }
                } catch (e: DeviceException) {
                    if (e.category == ErrorCategory.ConnectionLost) throw e
                }
            }
        }
    }
}

private val firewall = listOf("chain", "action", "src-address", "dst-address", "comment")

private val routerOsPlaces = listOf(
    Place("ip/address", listOf("address", "network", "interface", "comment")),
    Place("ip/arp", listOf("address", "mac-address", "interface", "comment"), big = true),
    Place("ip/dhcp-server/lease", listOf("address", "mac-address", "host-name", "active-address", "comment"), big = true),
    Place("ip/dhcp-server/network", listOf("address", "gateway", "dns-server", "comment")),
    Place("ip/dhcp-client", listOf("interface", "address", "gateway", "comment")),
    Place("ip/pool", listOf("name", "ranges", "comment")),
    Place("interface", listOf("name", "type", "mac-address", "comment")),
    Place("interface/bridge/host", listOf("mac-address", "interface", "bridge"), big = true),
    Place("ip/neighbor", listOf("identity", "address", "mac-address", "interface")),
    Place("ip/firewall/address-list", listOf("list", "address", "comment"), big = true),
    Place("ip/firewall/filter", firewall + "src-mac-address"),
    Place("ip/firewall/nat", firewall + "to-addresses"),
    Place("ip/firewall/mangle", firewall),
    Place("ip/firewall/raw", firewall),
    Place("ip/route", listOf("dst-address", "gateway", "comment"), Filter.Not(Filter.Equals("dynamic", "true")), big = true),
    Place("ip/dns/static", listOf("name", "address", "comment"), big = true),
    Place("ppp/secret", listOf("name", "local-address", "remote-address", "comment")),
    Place("ppp/active", listOf("name", "address", "caller-id")),
    Place("ip/hotspot/active", listOf("user", "address", "mac-address")),
    Place("ipv6/address", listOf("address", "interface", "comment")),
    Place("ipv6/neighbor", listOf("address", "mac-address", "interface"), big = true),
    Place("ipv6/firewall/address-list", listOf("list", "address", "comment"), big = true),
    Place("ipv6/firewall/filter", firewall),
    Place("interface/wifi/registration-table", listOf("mac-address", "interface", "ssid")),
    Place("interface/wireless/registration-table", listOf("mac-address", "interface", "last-ip")),
    Place("interface/wireguard/peers", listOf("name", "allowed-address", "endpoint-address", "comment")),
    Place("interface/wifi/capsman/remote-cap", listOf("identity", "address", "base-mac", "common-name")),
    Place("caps-man/remote-cap", listOf("identity", "address", "base-mac")),
    Place("routing/bgp/connection", listOf("name", "remote.address", "local.address", "comment")),
    Place("routing/bgp/session", listOf("name", "remote.address", "local.address")),
    Place("container", listOf("name", "interface", "hostname", "comment")),
    Place("interface/veth", listOf("name", "address", "gateway", "comment")),
    Place("queue/simple", listOf("name", "target", "comment")),
    Place("tool/netwatch", listOf("host", "comment")),
    Place("system/script", listOf("name", "comment")),
    Place("system/scheduler", listOf("name", "comment")),
)

private val opnsensePlaces = listOf(
    Place("interfaces/overview", listOf("ipv4", "ipv6", "macaddr", "description", "device")),
    Place("firewall/alias", listOf("name", "type", "content", "description")),
    Place("firewall/filter", listOf("description", "interface", "source_net", "destination_net")),
    Place("firewall/source-nat", listOf("description", "source_net", "target")),
    Place("routes/routes", listOf("network", "gateway", "descr")),
    Place("unbound/host-override", listOf("hostname", "domain", "server", "description")),
    Place("services/kea-leases", listOf("address", "hwaddr", "hostname")),
    Place("services/dhcp-leases", listOf("address", "mac", "hostname", "descr")),
    Place("diagnostics/arp", listOf("ip", "mac", "hostname", "intf_description")),
)

private val snmpPlaces = listOf(
    Place("ip/address", listOf("address", "interface")),
    Place("ipv6/address", listOf("address", "interface")),
    Place("ip/arp", listOf("address", "mac-address", "interface")),
    Place("ipv6/neighbor", listOf("address", "mac-address", "interface")),
    Place("interface", listOf("name", "mac-address", "comment", "description")),
    Place("ip/route", listOf("dst-address", "gateway")),
    Place("ip/neighbor", listOf("identity", "address", "mac-address", "interface")),
)

private val pfsensePlaces = listOf(
    Place("interfaces/overview", listOf("ipaddr", "macaddr", "descr", "hwif")),
    Place("firewall/alias", listOf("name", "address", "descr")),
    Place("firewall/rule", listOf("descr", "source", "destination")),
    Place("firewall/port-forward", listOf("descr", "target", "destination")),
    Place("routing/route", listOf("network", "gateway", "descr")),
    Place("services/dhcp-lease", listOf("ip", "mac", "hostname")),
    Place("services/dns-override", listOf("host", "domain", "ip")),
    Place("diagnostics/arp", listOf("ip_address", "mac_address", "hostname")),
)

private val keeneticPlaces = listOf(
    Place("clients", listOf("name", "ip", "mac", "hostname")),
    Place("interface", listOf("interface-name", "description", "mac", "address", "ssid")),
    Place("wifi", listOf("ssid", "name")),
    Place("segments", listOf("interface-name", "address")),
    Place("show/ip/dhcp/bindings", listOf("ip", "mac", "name")),
    Place("ip/dhcp/host", listOf("mac", "ip")),
    Place("show/ip/arp", listOf("ip", "mac", "interface")),
    Place("ip/route", listOf("network", "host", "gateway", "comment")),
    Place("ip/static", listOf("to-address", "port", "comment")),
    Place("firewall", listOf("source", "destination", "description")),
)

private val vyosPlaces = listOf(
    Place("interfaces/ethernet", listOf(".name", "description", "address", "hw-id")),
    Place("interfaces/bridge", listOf(".name", "description", "address")),
    Place("interfaces/wireguard", listOf(".name", "description", "address")),
    Place("firewall/group/address-group", listOf(".name", "address", "description")),
    Place("firewall/group/network-group", listOf(".name", "network", "description")),
    Place("firewall/ipv4/forward/filter/rule", listOf("description", "source address", "destination address")),
    Place("nat/source/rule", listOf("description", "source address", "translation address")),
    Place("nat/destination/rule", listOf("description", "destination address", "translation address")),
    Place("protocols/static/route", listOf(".name", "next-hop", "description")),
    Place("service/dhcp-server/shared-network-name", listOf(".name", "subnet", "description")),
)

private val upnpPlaces = listOf(
    Place("ip/dhcp-server/lease", listOf("address", "mac-address", "host-name")),
    Place("ip/firewall/nat", listOf("to-addresses", "src-address", "comment")),
)

private val openWrtPlaces = listOf(
    Place("status/addresses", listOf("address", "interface", "device")),
    Place("status/dhcp-leases", listOf("address", "mac-address", "host-name")),
    Place("status/neighbors", listOf("address", "mac-address", "interface")),
    Place("status/neighbors6", listOf("address", "mac-address", "interface")),
    Place("status/devices", listOf("name", "mac-address")),
    Place("status/wireless-clients", listOf("mac-address", "interface")),
    Place("network/interface", listOf(".name", "proto", "ipaddr", "ip6addr", "gateway")),
    Place("network/route", listOf("interface", "target", "gateway")),
    Place("dhcp/host", listOf("name", "mac", "ip")),
    Place("dhcp/domain", listOf("name", "ip")),
    Place("firewall/rule", listOf("name", "src_ip", "dest_ip", "src_mac")),
    Place("firewall/redirect", listOf("name", "src_ip", "src_dip", "dest_ip")),
    Place("wireless/wifi-iface", listOf(".name", "ssid", "network")),
)
