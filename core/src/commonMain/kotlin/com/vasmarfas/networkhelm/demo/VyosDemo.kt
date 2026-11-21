package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind

internal fun vyosDemo(): DemoSession = demoSession(
    DeviceInfo(Platform.Vyos, "demo-vyos", "1.4.3", "amd64", "VyOS", emptySet(), setOf("read", "write"), id = "demo-vyos"),
    Capabilities(subscriptions = false, streamingCommands = false, cancel = false, safeMode = SafeModeKind.None, terminal = false, files = false, export = false, menuContents = false),
) {
    list("interfaces/ethernet") {
        row(".name=eth0", "description=WAN", "address=dhcp", "hw-id=00:00:5e:00:53:61", "mtu=1500", "speed=auto", "duplex=auto")
        row(".name=eth1", "description=LAN", "address=172.31.10.1/24", "hw-id=00:00:5e:00:53:62", "mtu=1500", "speed=auto", "duplex=auto", "vif=20")
        row(".name=eth2", "description=lab", "address=172.31.20.1/24", "hw-id=00:00:5e:00:53:63", "mtu=1500", "speed=auto", "duplex=auto")
        row(".name=eth3", "description=storage A", "hw-id=00:00:5e:00:53:64", "mtu=9000", "speed=auto", "duplex=auto")
        row(".name=eth4", "description=storage B", "hw-id=00:00:5e:00:53:6a", "mtu=9000", "speed=auto", "duplex=auto")
    }
    list("interfaces/bridge") {
        row(".name=br0", "description=hosts", "address=10.0.0.1/24", "member interface=eth2")
    }
    list("interfaces/bonding", fields = listOf(choice("mode", "802.3ad", "active-backup", "broadcast", "round-robin", "transmit-load-balance", "adaptive-load-balance", "xor-hash"))) {
        row(".name=bond0", "description=storage", "address=172.31.30.1/24", "mode=802.3ad", "hash-policy=layer3+4", "member interface=eth3,eth4", "mtu=9000")
    }
    list("interfaces/wireguard") {
        row(".name=wg01", "description=site-to-site", "address=10.77.0.1/30", "port=51820", "peer=branch")
    }
    list("interfaces/pppoe") {
        row(".name=pppoe0", "description=backup ISP", "source-interface=eth0", "authentication username=demo-user", "authentication password=demo-pass", "mtu=1492", "disable=")
    }
    list("interfaces/dummy") {
        row(".name=dum0", "description=router id", "address=10.255.255.1/32")
    }
    list("interfaces/loopback") {
        row(".name=lo", "description=")
    }
    list("status/interfaces", writable = false) {
        row("interface=eth0", "ip-address=203.0.113.100/24", "mac=00:00:5e:00:53:61", "vrf=default", "mtu=1500", "s-l=u/u", "rx-bytes=912340112", "tx-bytes=88120344", "rx-errors=0", "tx-errors=0", "description=WAN")
        row("interface=eth1", "ip-address=172.31.10.1/24", "mac=00:00:5e:00:53:62", "vrf=default", "mtu=1500", "s-l=u/u", "rx-bytes=88120344", "tx-bytes=912340112", "rx-errors=0", "tx-errors=0", "description=LAN")
        row("interface=eth2", "ip-address=172.31.20.1/24", "mac=00:00:5e:00:53:63", "vrf=default", "mtu=1500", "s-l=u/u", "rx-bytes=4120344", "tx-bytes=912344", "rx-errors=0", "tx-errors=0", "description=lab")
        row("interface=wg01", "ip-address=10.77.0.1/30", "mac=", "vrf=default", "mtu=1420", "s-l=u/u", "rx-bytes=18340", "tx-bytes=22110", "rx-errors=0", "tx-errors=0", "description=site-to-site")
        row("interface=bond0", "ip-address=172.31.30.1/24", "mac=00:00:5e:00:53:64", "vrf=default", "mtu=9000", "s-l=u/u", "rx-bytes=51203990112", "tx-bytes=48117302440", "rx-errors=0", "tx-errors=0", "description=storage")
        row("interface=dum0", "ip-address=10.255.255.1/32", "mac=00:00:5e:00:53:6b", "vrf=default", "mtu=1500", "s-l=u/u", "rx-bytes=0", "tx-bytes=0", "rx-errors=0", "tx-errors=0", "description=router id")
        row("interface=lo", "ip-address=127.0.0.1/8", "mac=00:00:00:00:00:00", "vrf=default", "mtu=65536", "s-l=u/u", "rx-bytes=120334", "tx-bytes=120334", "rx-errors=0", "tx-errors=0", "description=")
        row("interface=pppoe0", "ip-address=", "mac=", "vrf=default", "mtu=1492", "s-l=A/D", "rx-bytes=0", "tx-bytes=0", "rx-errors=0", "tx-errors=0", "description=backup ISP")
    }
    list("firewall/ipv4/forward/filter/rule", ordered = true, fields = listOf(choice("action", "accept", "drop", "reject", "jump", "return"), choice("protocol", "tcp", "udp", "icmp", "all"))) {
        row(".name=10", "action=accept", "state=established,related", "description=Allow established")
        row(".name=20", "action=drop", "state=invalid", "description=Drop invalid")
        row(".name=100", "action=accept", "protocol=icmp", "description=Allow ping")
        row(".name=200", "action=accept", "source address=172.31.10.0/24", "description=LAN to anywhere")
        row(".name=300", "action=accept", "protocol=tcp", "destination address=172.31.20.10", "destination port=443", "description=Lab web")
    }
    list("firewall/ipv4/input/filter/rule", ordered = true, fields = listOf(choice("action", "accept", "drop", "reject"))) {
        row(".name=10", "action=accept", "state=established,related", "description=Allow established")
        row(".name=20", "action=accept", "protocol=icmp", "description=Allow ping")
        row(".name=30", "action=accept", "protocol=tcp", "destination port=22", "source address=172.31.10.0/24", "description=SSH from LAN")
        row(".name=40", "action=accept", "protocol=udp", "destination port=51820", "description=WireGuard")
    }
    list("firewall/ipv4/output/filter/rule", ordered = true) {
        row(".name=10", "action=accept", "description=Allow all from the router")
    }
    list("firewall/group/address-group") {
        row(".name=admins", "address=172.31.10.101", "description=Admin workstations")
    }
    list("firewall/group/network-group") {
        row(".name=internal", "network=172.31.10.0/24,172.31.20.0/24,172.31.30.0/24", "description=Internal networks")
        row(".name=documentation", "network=192.0.2.0/24,198.51.100.0/24,203.0.113.0/24", "description=Never routed")
    }
    list("firewall/group/port-group") {
        row(".name=web", "port=80,443", "description=Web")
    }
    list("nat/source/rule") {
        row(".name=100", "outbound-interface name=eth0", "source address=172.31.10.0/24", "translation address=masquerade", "description=LAN to WAN")
    }
    list("nat/destination/rule") {
        row(".name=10", "inbound-interface name=eth0", "protocol=tcp", "destination port=8443", "translation address=172.31.20.10", "translation port=443", "description=Lab web")
    }
    list("protocols/static/route") {
        row(".name=0.0.0.0/0", "next-hop=203.0.113.1", "description=ISP")
        row(".name=10.30.0.0/16", "next-hop=10.77.0.2", "description=branch over the tunnel")
    }
    list("protocols/static/route6") {
        row(".name=::/0", "next-hop=2001:db8:ffff::1", "description=ISP")
        row(".name=2001:db8:30::/48", "interface=wg01", "description=branch over the tunnel")
        row(".name=2001:db8:dead::/48", "blackhole=", "description=never leaves the router")
    }
    list("status/routes", writable = false) {
        row("dst-address=0.0.0.0/0", "gateway=203.0.113.1", "interface=eth0", "protocol=static", "distance=1", "metric=0", "uptime=3d04h", "vrf=default")
        row("dst-address=10.30.0.0/16", "gateway=10.77.0.2", "interface=wg01", "protocol=static", "distance=1", "metric=0", "uptime=3d04h", "vrf=default")
        row("dst-address=172.31.10.0/24", "interface=eth1", "protocol=connected", "distance=0", "metric=0", "uptime=3d04h", "vrf=default")
        row("dst-address=172.31.20.0/24", "interface=eth2", "protocol=connected", "distance=0", "metric=0", "uptime=3d04h", "vrf=default")
        row("dst-address=203.0.113.0/24", "interface=eth0", "protocol=connected", "distance=0", "metric=0", "uptime=3d04h", "vrf=default")
    }
    list("status/arp", writable = false) {
        row("address=172.31.10.101", "link-layer-address=00:00:5e:00:53:65", "interface=eth1", "state=REACHABLE")
        row("address=172.31.10.102", "link-layer-address=00:00:5e:00:53:66", "interface=eth1", "state=STALE")
        row("address=172.31.20.10", "link-layer-address=00:00:5e:00:53:a1", "interface=eth2", "state=REACHABLE")
        row("address=203.0.113.1", "link-layer-address=00:00:5e:00:53:fe", "interface=eth0", "state=REACHABLE")
    }
    list("service/dhcp-server/shared-network-name") {
        row(".name=LAN", "subnet=172.31.10.0/24", "authoritative=true", "description=office")
    }
    list("status/dhcp-leases", writable = false) {
        row("ip=172.31.10.101", "mac=00:00:5e:00:53:65", "hostname=macbook-pro", "state=active", "start=2026/10/31 09:12:04", "end=2026/10/31 21:12:04", "pool=LAN")
        row("ip=172.31.10.102", "mac=00:00:5e:00:53:66", "hostname=iphone-anna", "state=active", "start=2026/10/31 10:40:11", "end=2026/10/31 22:40:11", "pool=LAN")
    }
    settings("service/dns/forwarding", "listen-address=172.31.10.1", "allow-from=172.31.10.0/24", "cache-size=10000", "name-server=1.1.1.1")
    settings("service/ntp", "server=0.pool.ntp.org", "allow-client address=172.31.10.0/24")
    settings("service/ssh", "port=22", "listen-address=172.31.10.1", "disable-password-authentication=")
    settings("system", "host-name=demo-vyos", "domain-name=lan", "time-zone=Europe/Moscow", "name-server=1.1.1.1")
    list("system/login/user") {
        row(".name=vyos", "full-name=Demo admin")
        row(".name=viewer", "full-name=Read only")
    }
    list("status/log", writable = false) {
        row("time=Oct 31 11:58:02", "program=dhcpd", "message=DHCPACK on 172.31.10.101 to 00:00:5e:00:53:65 (macbook-pro) via eth1")
        row("time=Oct 31 11:15:39", "program=charon", "message=wg01: handshake completed with peer branch")
        row("time=Oct 31 09:00:01", "program=sshd", "message=Accepted publickey for vyos from 172.31.10.101 port 50122 ssh2")
        row("time=Oct 30 22:14:40", "program=commit", "message=commit by vyos: firewall ipv4 forward rule 300")
    }
}
