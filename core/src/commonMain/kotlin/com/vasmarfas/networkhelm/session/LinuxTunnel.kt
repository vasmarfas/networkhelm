package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.nettools.Ipv4
import com.vasmarfas.networkhelm.openwrt.quote
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// a tunnel of the kernel to another host: GRE, GRETAP, IPIP and SIT by their addresses, GRE with a key, VXLAN and GENEVE by their VNI and
// UDP port. ip makes it, and each network configuration keeps it in its own form; netplan and NetworkManager know no GENEVE
internal class LinkTunnel(val kind: String, val local: String, val remote: String, val id: Long?, val port: Int?) {
    // the line of ip that makes it, which ifupdown runs as well
    fun command(name: String, parent: String): String = "ip link add ${quote(name)} type $kind " + when (kind) {
        "vxlan" -> "id $id local $local remote $remote dstport $port" + if (parent.isEmpty()) "" else " dev ${quote(parent)}"
        "geneve" -> "id $id remote $remote dstport $port"
        else -> "local $local remote $remote" + id?.let { " key $it" }.orEmpty()
    }

    // the .netdev of systemd-networkd, which stands on its own without a .network of the interface below it
    fun netdev(name: String): String = "[NetDev]\nName=$name\nKind=$kind\n\n" + when (kind) {
        "vxlan" -> "[VXLAN]\nVNI=$id\nLocal=$local\nRemote=$remote\nDestinationPort=$port\nIndependent=true\n"
        "geneve" -> "[GENEVE]\nId=$id\nRemote=$remote\nDestinationPort=$port\n"
        else -> "[Tunnel]\nLocal=$local\nRemote=$remote\n" + id?.let { "Key=$it\n" }.orEmpty() + "Independent=true\n"
    }

    // its entry under tunnels of netplan
    fun netplan(): JsonObject = JsonObject(
        mapOf("mode" to JsonPrimitive(kind), "local" to JsonPrimitive(local), "remote" to JsonPrimitive(remote)) +
            listOfNotNull(id?.let { (if (kind == "vxlan") "id" else "key") to JsonPrimitive(it) }, port?.let { "port" to JsonPrimitive(it) }),
    )

    // a profile of NetworkManager: ip-tunnel for GRE, GRETAP, IPIP and SIT, vxlan for VXLAN
    fun nmcli(name: String, parent: String): String = "nmcli connection add con-name ${quote(name)} ifname ${quote(name)} " + when (kind) {
        "vxlan" -> "type vxlan vxlan.id $id vxlan.local $local vxlan.remote $remote vxlan.destination-port $port" + if (parent.isEmpty()) "" else " vxlan.parent ${quote(parent)}"
        else -> "type ip-tunnel mode $kind ip-tunnel.local $local ip-tunnel.remote $remote" + id?.let { " ip-tunnel.input-key $it ip-tunnel.output-key $it" }.orEmpty()
    } + " ipv4.method disabled ipv6.method ignore"
}

internal val TUNNEL_KINDS = listOf("gre", "gretap", "ipip", "sit", "vxlan", "geneve")

// a tunnel the kernel makes for itself as it loads a module, gre0, gretap0, tunl0 and the like: ip shows it on no link, @NONE, which
// the row has as the parent null, and keeps it however often ip link del goes, exiting with 0
internal fun kernelFallback(link: Item) = link["name"] in KERNEL_TUNNELS && link["parent"] == "null"

private val KERNEL_TUNNELS = setOf("gre0", "gretap0", "erspan0", "tunl0", "sit0", "ip6tnl0", "ip6gre0", "ip6gretap0", "ip6erspan0", "ip_vti0", "ip6_vti0")

// the fields of the form of a tunnel; without a local address the tunnel takes the one the host leaves by toward the remote one, which
// route gives
internal suspend fun linkTunnel(kind: String, values: Map<String, String>, route: suspend (String) -> String): LinkTunnel {
    val remote = values["remote"].orEmpty()
    val id = values["tunnel-id"].orEmpty()
    val port = values["port"].orEmpty()
    val vni = kind == "vxlan" || kind == "geneve"
    if (Ipv4.parse(remote) == null) throw DeviceException(ErrorCategory.ArgumentValue, "remote: $remote is not a value it takes")
    if (vni && id.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "tunnel-id: a value is needed")
    val local = values["local"].orEmpty().ifEmpty { if (kind == "geneve") "" else route(remote) }
    val bad = when {
        kind != "geneve" && Ipv4.parse(local) == null -> "local" to local
        vni && id.toLongOrNull() !in 0L..16_777_215L -> "tunnel-id" to id
        !vni && id.isNotEmpty() && (kind !in listOf("gre", "gretap") || id.toLongOrNull() !in 0L..4_294_967_295L) -> "tunnel-id" to id
        port.isNotEmpty() && (!vni || port.toIntOrNull() !in 1..65535) -> "port" to port
        else -> null
    }
    bad?.let { (field, value) -> throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes") }
    return LinkTunnel(kind, local, remote, id.toLongOrNull(), if (vni) port.toIntOrNull() ?: if (kind == "vxlan") 4789 else 6081 else null)
}

// what ip -j -d link show says of a virtual interface in its info_data: the id of a VLAN, the addresses of a tunnel, the key of GRE,
// which it shows as an address, and the VNI and port of VXLAN and GENEVE
internal fun virtualData(type: String, info: JsonObject?): Map<String, String> {
    fun text(key: String) = (info?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty().takeIf { it != "any" }.orEmpty()
    val vni = type == "vxlan" || type == "geneve"
    return mapOf(
        "vlan-id" to if (type == "vlan") text("id") else "", "local" to text("local"), "remote" to text("remote"),
        "tunnel-id" to if (vni) text("id") else if (type == "gre" || type == "gretap") text("ikey").let { key -> Ipv4.parse(key)?.toString() ?: key } else "",
        "port" to if (vni) text("port") else "",
    )
}
