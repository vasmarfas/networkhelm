package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.nettools.Ipv4
import com.vasmarfas.networkhelm.nettools.Ipv4Subnet
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession

// the VPN servers and clients of a Linux host
internal class LinuxVpn(
    private val ssh: SshSession,
    private val root: Root,
    private val tools: suspend () -> Set<String>,
    private val guard: LinuxGuard,
    private val packages: LinuxPackages,
) {
    private val wireGuard = LinuxVpnWireGuard(ssh, root, tools, guard)
    private val openVpn = LinuxVpnOpenVpn(ssh, root, tools, guard)
    private val ipsec = LinuxVpnIpsec(ssh, root, tools, guard)
    private val ocserv = LinuxVpnOcserv(ssh, root, tools, guard)
    private val libreswan = LinuxVpnLibreswan(ssh, root, tools, guard)
    private val l2tp = LinuxVpnL2tp(ssh, root, tools, ipsec, guard)
    private val pptp = LinuxVpnPptp(ssh, root, tools, guard)
    private val openConnect = LinuxVpnOpenConnect(ssh, root, tools, guard)
    private val sstp = LinuxVpnSstp(ssh, root, tools, guard)
    private val amnezia = LinuxVpnWireGuard(ssh, root, tools, guard, AMNEZIA)
    private val tailscale = LinuxVpnTailscale(ssh, root, tools, guard)
    private val zerotier = LinuxVpnZerotier(ssh, root, tools, guard)
    private val panel = LinuxVpnPanel(root, tools, wireGuard, amnezia)
    private val users = LinuxVpnUsers(wireGuard, openVpn, ipsec, ocserv, l2tp, pptp, LinuxVpnLimits(root), tools)
    private val collector = LinuxVpnCollector(root, tools)

    val tables: Map<String, HostTable> = wireGuard.tables + openVpn.tables + ipsec.tables + ocserv.tables + libreswan.tables + l2tp.tables + pptp.tables +
        openConnect.tables + sstp.tables + amnezia.tables + tailscale.tables + zerotier.tables + panel.tables + users.tables + collector.tables +
        ("vpn/overview" to HostTable(OVERVIEW, ::overview, commands = listOf("install"), run = { _, arguments -> install(arguments) }))

    // what each kind of VPN needs installed. The ipsec of strongSwan has the name of the one of Libreswan, which its window tells apart
    private suspend fun overview(): List<Item> {
        val present = tools()
        return KINDS.map { (id, kind) ->
            val installed = if (id == "libreswan") tables.getValue("vpn/libreswan").present() else kind.tool in present
            Item(id, linkedMapOf("name" to kind.title, "installed" to "$installed", "packages" to if ("apt-get" in present) kind.apt else kind.packages))
        }
    }

    // the packages of the kinds chosen, by the manager the window of packages uses, with what it printed; a package the repositories
    // of the host lack is named with where it comes from instead. AmneziaWG, which no distribution packages, goes its own way after them
    private suspend fun install(arguments: Map<String, String>): List<Item> {
        val apt = "apt-get" in tools()
        val picked = KINDS.filter { it.first in arguments["numbers"].orEmpty().split(',') }
        val chosen = picked.filter { it.first != "amneziawg" }.map { it.second }
        val names = chosen.flatMap { it.names(apt) }
        val output = names.takeIf { it.isNotEmpty() }?.let {
            try {
                packages.install(names)
            } catch (e: DeviceException) {
                val missing = MISSING.findAll(e.message.orEmpty()).mapNotNull { match -> match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }.filter { it in names }.toList()
                if (missing.isEmpty()) throw e
                val elsewhere = chosen.firstNotNullOfOrNull { kind -> kind.elsewhere?.takeIf { kind.names(apt).any(missing::contains) } }
                throw DeviceException(ErrorCategory.Unsupported, elsewhere ?: "the repositories of the host have no ${missing.joinToString(", ")}: it comes from a repository the host does not have yet", e)
            }
        }
        val built = if (picked.any { it.first == "amneziawg" }) packages.run(AWG_INSTALL) else null
        return listOf(Item("", mapOf("output" to listOfNotNull(output, built).joinToString("\n\n"))))
    }

    // socat reaches the management socket of OpenVPN where nc has no -U; Debian and Ubuntu split strongSwan: EAP-MSCHAPv2 lies in its
    // extauth plugins there, and openssl, gcrypt and curve25519 for the newer proposals in the standard ones. Elsewhere says where a
    // kind comes from on hosts whose repositories lack it
    private class Kind(val title: String, val tool: String, val packages: String, val apt: String = packages, val elsewhere: String? = null) {
        fun names(debian: Boolean) = (if (debian) apt else packages).split(' ')
    }

    private companion object {
        val OVERVIEW = listOf("name", "installed", "packages")
        val KINDS = listOf(
            "wireguard" to Kind("WireGuard", "wg", "wireguard-tools"),
            "amneziawg" to Kind("AmneziaWG", "awg", "amneziawg-tools amneziawg-go"),
            "openvpn" to Kind("OpenVPN", "openvpn", "openvpn easy-rsa socat"),
            "ipsec" to Kind("strongSwan", "swanctl", "strongswan", "strongswan-swanctl charon-systemd strongswan-pki libcharon-extra-plugins libcharon-extauth-plugins libstrongswan-standard-plugins"),
            "libreswan" to Kind("Libreswan", "ipsec", "libreswan"),
            "ocserv" to Kind("ocserv", "ocserv", "ocserv"),
            "l2tp" to Kind("L2TP/IPsec", "xl2tpd", "xl2tpd"),
            "pptp" to Kind("PPTP", "pptpd", "pptpd"),
            "pptp-client" to Kind("PPTP Client", "pptp", "pptp", "pptp-linux"),
            "openconnect" to Kind("OpenConnect Client", "openconnect", "openconnect"),
            "sstp" to Kind("SSTP Client", "sstpc", "sstp-client"),
            "tailscale" to Kind(
                "Tailscale", "tailscale", "tailscale",
                elsewhere = "the repositories of the host have no Tailscale: its own repository comes with curl -fsSL https://tailscale.com/install.sh | sh",
            ),
            "zerotier" to Kind(
                "ZeroTier", "zerotier-cli", "zerotier-one",
                elsewhere = "the repositories of the host have no ZeroTier: its own repository comes with curl -s https://install.zerotier.com | sudo bash",
            ),
        )

        // what each manager says of a package its repositories lack: apt, dnf, yum, pacman, apk and zypper in turn
        val MISSING = Regex("""Unable to locate package (\S+)|No match for argument: (\S+)|No package (\S+) available|target not found: (\S+)|(\S+) \(no such package\)|No provider of '([^']+)' found""")
    }
}

internal val VPN_TOOLS = listOf(
    "wg", "wg-quick", "awg", "awg-quick", "openvpn", "swanctl", "ipsec", "xl2tpd", "pppd", "pptpd", "pptp", "ocserv", "occtl", "ocpasswd", "openconnect",
    "sstpc", "accel-cmd", "tailscale", "zerotier-cli", "squid", "tinyproxy", "3proxy", "danted", "sockd", "ss-server", "sing-box", "xray", "resolvconf",
)

// a key of WireGuard: 32 bytes in base64
internal val WG_KEY = Regex("[A-Za-z0-9+/]{42}[AEIMQUYcgkosw480]=")

// wg quotes a key it cannot read, of whatever length: Key is not the correct length or format: `...'
private val QUOTED_KEY = Regex("(length or format: `)[^']*'")

// an auth key of Tailscale
private val TS_KEY = Regex("tskey-[A-Za-z0-9-]+")

// a private key in PEM and a static key of OpenVPN, whole
private val PEM_KEY = Regex("-----BEGIN ([A-Za-z0-9 ]*(PRIVATE KEY|Static key V1))-----[\\s\\S]*?-----END \\1-----")

// strongSwan of Debian names on stderr each optional plugin it lacks, whatever it was asked: plugin 'test-vectors': failed to load - ...
internal val PLUGIN_LINE = Regex("plugin '[^']*': failed to load.*")

private val BLANKS = Regex("\\s+")

// what NetworkManager prints for a file it cannot load, exiting with 0 all the same
private const val NOT_LOADED = "Could not load file"

// a text of the host with any key in it masked and without the lines about the plugins strongSwan lacks
internal fun hidden(text: String) = TS_KEY.replace(
    QUOTED_KEY.replace(WG_KEY.replace(PEM_KEY.replace(text.lines().filterNot { PLUGIN_LINE.matches(it.trim()) }.joinToString("\n"), "(hidden)"), "(hidden)"), "\$1(hidden)'"),
    "tskey-(hidden)",
)

// a command as root that may read or print keys: what it says when it fails comes without what it printed, which may be a file with
// its keys, and with any key in it masked
internal suspend fun Root.masked(command: String, input: String? = null): String {
    val result = if (input == null) exec(command) else exec(command, input)
    if (result.exitStatus != 0) throw DeviceException(ErrorCategory.Api, hidden(result.error.trim()).ifEmpty { "exit ${result.exitStatus}" })
    return result.output
}

// a file written as root through its input: the old file waits beside the new one until the check has passed and comes back when it
// fails; the new one starts as a copy of the old, so it keeps its owner and mode, or as a file of the mode given. NetworkManager that
// cannot load a file says so and still exits with 0, so what the check printed counts as well
internal suspend fun Root.write(path: String, text: String, check: String, mode: String = "600", failed: (String) -> Boolean = { NOT_LOADED in it }) {
    val file = quote(path)
    val dir = path.substringBeforeLast('/')
    val new = quote("$dir/.${path.substringAfterLast('/')}.networkhelm-new")
    val old = quote("$dir/.${path.substringAfterLast('/')}.networkhelm-old")
    val result = exec(
        "umask 077; mkdir -p ${quote(dir)} && rm -f $old $new && { [ ! -e $file ] || { cp -p $file $old && cp -p $file $new; }; } && cat > $new && " +
            "{ [ -e $old ] || chmod $mode $new; } && mv -f $new $file || { rm -f $new; exit 1; }\n$check",
        text.trimEnd() + "\n",
    )
    if (result.exitStatus != 0 || failed(result.output)) {
        masked("if [ -e $old ]; then mv -f $old $file; else rm -f $file; fi")
        throw DeviceException(ErrorCategory.Api, hidden("${result.output}\n${result.error}".trim()).ifEmpty { "exit ${result.exitStatus}" })
    }
    masked("rm -f $old")
}

// a unit started and waited for until the device of its tunnel is there, 20 s at most; a unit that stops on the way says why from its
// journal
internal fun unitWithDevice(unit: String, device: String, tool: String) = "systemctl start $unit && for i in \$(seq 20); do ip link show $device >/dev/null 2>&1 && exit 0; " +
    "systemctl is-active --quiet $unit || { journalctl -u $unit -n 5 --no-pager -o cat >&2; exit 1; }; sleep 1; done; " +
    "echo '$tool made no device $device in 20 s' >&2; exit 1"

// the address of the host this session reached it by
internal suspend fun localAddress(ssh: SshSession): String =
    ssh.exec("echo \"\$SSH_CONNECTION\"").output.trim().split(' ').getOrNull(2)?.removePrefix("::ffff:").orEmpty()

// the interface this session came in by is not taken down
internal suspend fun keepSession(ssh: SshSession, name: String) {
    val lines = ssh.exec("echo \"\$SSH_CONNECTION\"; ip -o addr show").output.lines()
    val local = lines.firstOrNull()?.trim()?.split(' ')?.getOrNull(2)?.removePrefix("::ffff:") ?: return
    val over = lines.drop(1).map { it.trim().split(BLANKS) }.firstOrNull { it.size >= 4 && it[3].substringBefore('/') == local }?.get(1)?.removeSuffix(":")
    if (over == name) throw DeviceException(ErrorCategory.ArgumentValue, "this session goes over $name, and it would end with it")
}

// the address this session comes from with the device the way to it goes by and its gateway, where it has one. A way without one is
// kept too: it may be the default route of another tunnel, as of OpenConnect, which a new tunnel of the default route takes over
internal class SessionWay(val address: String, val via: String?, val device: String) {
    val target get() = "$address/${if (':' in address) 128 else 32}${via?.let { " via $it" }.orEmpty()} dev $device"
}

// none where the session comes from the host itself
internal suspend fun sessionWay(ssh: SshSession): SessionWay? {
    val lines = ssh.exec("set -- \$SSH_CONNECTION; c=\${1#::ffff:}; [ -n \"\$c\" ] && echo \"\$c\" && ip route get \"\$c\"").output.lines()
    val address = lines.firstOrNull()?.trim()?.ifEmpty { null } ?: return null
    val route = lines.drop(1).joinToString(" ")
    if (route.trimStart().startsWith("local ")) return null
    return DEFAULT_DEVICE.find(route)?.groupValues?.get(1)?.let { SessionWay(address, VIA.find(route)?.groupValues?.get(1), it) }
}

// the servers of names a new server gives its clients
internal const val CLIENT_DNS = "1.1.1.1, 9.9.9.9"

// what a new server is fitted around: the links of the host, the ports of its sockets of UDP and those the files of WireGuard and
// AmneziaWG name for interfaces that are down, its networks of IPv4 by address and route and those of its servers of VPN, and the
// address of the interface its default route goes out by
internal class HostScan(val links: Set<String>, val ports: Set<Int>, val networks: List<Ipv4Subnet>, val address: String)

// read as root the scan has the networks of every server of VPN, whose files are root's alone; as the login only those of ocserv, xl2tpd
// and pptpd, which anyone reads
internal suspend fun SshSession.scan(root: Root? = null) = parseScan((root?.exec(HOST_SCAN) ?: exec(HOST_SCAN)).output)

internal fun parseScan(text: String): HostScan {
    val parts = mutableMapOf<String, MutableList<String>>()
    var section = "links"
    for (line in text.lines()) {
        if (line.startsWith("--nh-") && line.endsWith("--")) section = line.removePrefix("--nh-").removeSuffix("--") else parts.getOrPut(section) { mutableListOf() } += line
    }
    val ip = parts["ip"].orEmpty()
    val uplink = parts["default"].orEmpty().firstNotNullOfOrNull { DEFAULT_DEVICE.find(it)?.groupValues?.get(1) }
    val address = ip.firstNotNullOfOrNull { line ->
        INET.find(line)?.groupValues?.get(1)?.takeIf { "scope global" in line && line.trim().split(BLANKS).getOrNull(1)?.substringBefore('@') == uplink }
    }
    return HostScan(
        parts["links"].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        udpPorts(parts["udp"].orEmpty().joinToString("\n")) + parts["port"].orEmpty().mapNotNull { it.trim().toIntOrNull() },
        ip.flatMap { line -> CIDR.findAll(line).mapNotNull { Ipv4.parseSubnet(it.value) }.toList() } + parts["vpn"].orEmpty().flatMap { it.split(',') }.mapNotNull(::vpnNetwork),
        address.orEmpty(),
    )
}

// a network of a server of VPN as its file writes it: 10.8.0.1/24, 10.9.0.0 255.255.255.0, a range 10.13.0.10-10.13.0.100 or
// 10.14.0.10-100, taken whole by the smallest network that holds it, or one address; IPv6 and what is no address count for nothing
private fun vpnNetwork(item: String): Ipv4Subnet? {
    val text = item.substringBefore('#').trim()
    return when {
        '-' in text -> Ipv4.parseRange(text)?.let { (first, last) -> Ipv4.enclosing(first, last) }
        '/' in text || ' ' in text -> Ipv4.parseSubnet(text)
        else -> Ipv4.parse(text)?.let { Ipv4Subnet(it, 32) }
    }
}

// the ports of the sockets in /proc/net/udp and udp6, 0100007F:0035 for 53
internal fun udpPorts(text: String) = text.lines().mapNotNull { it.trim().split(BLANKS).getOrNull(1)?.substringAfter(':', "")?.toIntOrNull(16) }.toSet()

// the first network 10.N.0.0/24 from 10.8 on that none of the networks given overlaps; one wider than /8 is a way out, as the halves
// of the default route a client of OpenVPN sets, and no network of the host
internal fun freeNetwork(taken: List<Ipv4Subnet>): Ipv4Subnet? {
    val own = taken.filter { it.prefix >= 8 }
    return (8..254).asSequence().map { Ipv4Subnet(10L shl 24 or (it.toLong() shl 16), 24) }
        .firstOrNull { free -> own.none { (free.address xor it.address) shr (32 - minOf(free.prefix, it.prefix)) == 0L } }
}

// what a new server is fitted around: the links, the sockets of UDP, the addresses and routes of IPv4, the default route and the
// networks of the servers of VPN, running or not: the addresses of wg-quick and awg-quick, server of OpenVPN, the pools of strongSwan
// in swanctl and ipsec.conf, the network of ocserv and the addresses and ranges of xl2tpd and pptpd
private const val HOST_SCAN = "ls /sys/class/net; echo --nh-udp--; cat /proc/net/udp /proc/net/udp6 2>/dev/null; echo --nh-ip--; ip -4 -o addr show 2>/dev/null; " +
    "ip -4 route show table all 2>/dev/null; echo --nh-default--; ip -4 route show default 2>/dev/null; echo --nh-vpn--\n" + $$"""s() { f=$1; shift; [ -r "$f" ] && sed -n -E "$@" "$f"; }
for f in /etc/wireguard/*.conf /etc/amnezia/amneziawg/*.conf; do s "$f" 's/^[[:space:]]*[Aa]ddress[[:space:]]*=[[:space:]]*//p'; done
for f in /etc/openvpn/server/*.conf /etc/openvpn/*.conf; do s "$f" 's/^[[:space:]]*server[[:space:]]+([0-9.]+[[:space:]]+[0-9.]+).*/\1/p'; done
for f in /etc/swanctl/swanctl.conf /etc/swanctl/conf.d/*.conf /etc/strongswan/swanctl/swanctl.conf /etc/strongswan/swanctl/conf.d/*.conf; do s "$f" 's/^[[:space:]]*addrs[[:space:]]*=[[:space:]]*//p'; done
for f in /etc/ipsec.conf /etc/strongswan/ipsec.conf; do s "$f" 's/^[[:space:]]*rightsourceip[[:space:]]*=[[:space:]]*//p'; done
[ ! -r /etc/ocserv/ocserv.conf ] || echo "$(s /etc/ocserv/ocserv.conf 's/^[[:space:]]*ipv4-network[[:space:]]*=[[:space:]]*//p' | head -n 1) $(s /etc/ocserv/ocserv.conf 's/^[[:space:]]*ipv4-netmask[[:space:]]*=[[:space:]]*//p' | head -n 1)"
s /etc/xl2tpd/xl2tpd.conf 's/^[[:space:]]*(ip range|local ip)[[:space:]]*=[[:space:]]*//p'
s /etc/pptpd.conf 's/^[[:space:]]*(remoteip|localip)[[:space:]]+//p'
echo --nh-port--
for f in /etc/wireguard/*.conf /etc/amnezia/amneziawg/*.conf; do s "$f" 's/^[[:space:]]*[Ll]isten[Pp]ort[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p'; done
true"""

private val INET = Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)/\\d+")
private val CIDR = Regex("\\d+\\.\\d+\\.\\d+\\.\\d+/\\d+")
private val DEFAULT_DEVICE = Regex("\\bdev (\\S+)")
private val VIA = Regex("\\bvia (\\S+)")
