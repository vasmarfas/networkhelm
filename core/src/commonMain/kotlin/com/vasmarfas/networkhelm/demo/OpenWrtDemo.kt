package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.duration
import com.vasmarfas.networkhelm.openwrt.LIST
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val UPTIME = 12 * 86_400L + 7 * 3_600L + 41 * 60L + 9L

private val policies = setOf("read", "write")

private val switches = flags("enabled", "disabled", "masq", "mtu_fix", "ignore", "authoritative", "local", "stp", "vlan_filtering", "rebind_protection")

private val verdicts = listOf("input", "output", "forward").map { choice(it, "ACCEPT", "REJECT", "DROP") }

// the session gives a list of UCI as its lines
private fun many(name: String) = Argument(name, ValueType.Many(ValueType.Text(), LIST))

internal fun openWrtDemo(): DemoSession = demoSession(
    DeviceInfo(Platform.OpenWrt, "demo-ap", "24.10.2", "aarch64_cortex-a53", "Demo AP (mediatek,mt7981)", setOf("luci", "firewall4", "dnsmasq-full", "wireguard-tools"), policies, id = "demo-openwrt"),
    Capabilities(subscriptions = false, streamingCommands = true, cancel = true, safeMode = SafeModeKind.None, terminal = false, files = false, export = false, menuContents = false),
) {
    list("network/interface", fields = switches) {
        row(".name=lan", "proto=static", "device=br-lan", "ipaddr=192.168.1.1", "netmask=255.255.255.0", "ip6assign=60", "ipv4-address=192.168.1.1/24", "uptime=12d7h41m")
        row(".name=wan", "proto=dhcp", "device=wan", "ipv4-address=203.0.113.64/24", "uptime=2d3h18m")
        row(".name=wan6", "proto=dhcpv6", "device=wan", "reqaddress=try", "reqprefix=auto", "uptime=2d3h18m")
        row(".name=guest", "proto=static", "device=br-guest", "ipaddr=192.168.30.1", "netmask=255.255.255.0", "ipv4-address=192.168.30.1/24", "uptime=12d7h41m")
        row(".name=wg0", "proto=wireguard", "listen_port=51820", "addresses=10.200.0.1/24", "mtu=1420", "ipv4-address=10.200.0.1/24", "uptime=12d7h40m")
    }
    list("network/device", fields = switches + many("ports")) {
        row("name=br-lan", "type=bridge", "ports=lan1\nlan2\nlan3\nlan4", "macaddr=00:00:5E:00:53:10", "mtu=1500", "stp=0", "vlan_filtering=0")
        row("name=br-guest", "type=bridge", "ports=", "macaddr=00:00:5E:00:53:11", "mtu=1500", "stp=0", "vlan_filtering=0")
    }
    list("network/route") {
        row("interface=lan", "target=10.20.0.0", "netmask=255.255.0.0", "gateway=192.168.1.254", "metric=0", "table=main")
    }
    list("status/routes", writable = false) {
        row("dst-address=0.0.0.0/0", "gateway=203.0.113.1", "interface=wan", "table=main", "distance=0", "type=unicast", "protocol=dhcp")
        row("dst-address=192.168.1.0/24", "interface=lan", "pref-src=192.168.1.1", "table=main", "distance=0", "type=unicast", "protocol=kernel")
        row("dst-address=192.168.30.0/24", "interface=guest", "pref-src=192.168.30.1", "table=main", "distance=0", "type=unicast", "protocol=kernel")
        row("dst-address=203.0.113.0/24", "interface=wan", "pref-src=203.0.113.64", "table=main", "distance=0", "type=unicast", "protocol=kernel")
        row("dst-address=10.20.0.0/16", "gateway=192.168.1.254", "interface=lan", "table=main", "distance=0", "type=unicast", "protocol=boot")
    }
    list("status/addresses", writable = false) {
        row("address=192.168.1.1/24", "interface=lan", "device=br-lan")
        row("address=203.0.113.64/24", "interface=wan", "device=wan")
        row("address=192.168.30.1/24", "interface=guest", "device=br-guest")
        row("address=10.200.0.1/24", "interface=wg0", "device=wg0")
    }
    list("status/devices", writable = false) {
        row(".id=lo", "name=lo", "type=Network device", "devtype=loopback", "running=true", "mtu=65536", "mac-address=00:00:00:00:00:00", "rx-byte=4119022", "tx-byte=4119022")
        row(".id=lan1", "name=lan1", "type=Network device", "devtype=dsa", "running=true", "mtu=1500", "mac-address=00:00:5E:00:53:10", "rx-byte=812044310", "tx-byte=6190421077")
        row(".id=lan2", "name=lan2", "type=Network device", "devtype=dsa", "running=true", "mtu=1500", "mac-address=00:00:5E:00:53:10", "rx-byte=378176107", "tx-byte=2649698225")
        row(".id=lan3", "name=lan3", "type=Network device", "devtype=dsa", "running=false", "mtu=1500", "mac-address=00:00:5E:00:53:10", "rx-byte=0", "tx-byte=0")
        row(".id=lan4", "name=lan4", "type=Network device", "devtype=dsa", "running=false", "mtu=1500", "mac-address=00:00:5E:00:53:10", "rx-byte=0", "tx-byte=0")
        row(".id=wan", "name=wan", "type=Network device", "devtype=dsa", "running=true", "mtu=1500", "mac-address=00:00:5E:00:53:12", "rx-byte=9120044812", "tx-byte=1204883019")
        row(".id=br-lan", "name=br-lan", "type=bridge", "devtype=bridge", "running=true", "mtu=1500", "mac-address=00:00:5E:00:53:10", "rx-byte=1190220417", "tx-byte=8840119302")
        row(".id=br-guest", "name=br-guest", "type=bridge", "devtype=bridge", "running=true", "mtu=1500", "mac-address=00:00:5E:00:53:11", "rx-byte=3204411", "tx-byte=611900")
        row(".id=phy0-ap0", "name=phy0-ap0", "type=Network device", "devtype=wlan", "running=true", "mtu=1500", "mac-address=00:00:5E:00:53:13", "rx-byte=3204411", "tx-byte=611900")
        row(".id=phy1-ap0", "name=phy1-ap0", "type=Network device", "devtype=wlan", "running=true", "mtu=1500", "mac-address=00:00:5E:00:53:14", "rx-byte=430811354", "tx-byte=90320456")
        row(".id=wg0", "name=wg0", "type=Network device", "devtype=wireguard", "running=true", "mtu=1420", "rx-byte=90273412", "tx-byte=13002082")
    }
    list("status/neighbors", writable = false) {
        row("address=192.168.1.20", "mac-address=00:00:5E:00:53:20", "interface=br-lan", "flags=REACHABLE")
        row("address=192.168.1.101", "mac-address=00:00:5E:00:53:65", "interface=br-lan", "flags=STALE")
        row("address=192.168.1.113", "mac-address=00:00:5E:00:53:71", "interface=br-lan", "flags=REACHABLE")
        row("address=203.0.113.1", "mac-address=00:00:5E:00:53:FE", "interface=wan", "flags=REACHABLE")
    }

    list("wireless/wifi-iface", fields = switches) {
        row(".name=default_radio0", "device=radio0", "mode=ap", "ssid=DemoNet", "network=lan", "encryption=sae-mixed", "disabled=0")
        row(".name=default_radio1", "device=radio1", "mode=ap", "ssid=DemoNet-5G", "network=lan", "encryption=sae-mixed", "disabled=0")
        row(".name=guest_radio0", "device=radio0", "mode=ap", "ssid=DemoGuest", "network=guest", "encryption=psk2", "isolate=1", "disabled=1")
    }
    list("wireless/wifi-device", fields = switches) {
        row(".name=radio0", "type=mac80211", "band=2g", "channel=6", "htmode=HE20", "country=RU", "disabled=0")
        row(".name=radio1", "type=mac80211", "band=5g", "channel=36", "htmode=HE80", "country=RU", "disabled=0")
    }
    list("status/wireless-clients", writable = false) {
        row("interface=phy1-ap0", "mac-address=00:00:5E:00:53:65", "signal=-48 dBm", "connected-time=3h12m", "rx-bytes=412809221", "tx-bytes=88120344")
        row("interface=phy1-ap0", "mac-address=00:00:5E:00:53:66", "signal=-57 dBm", "connected-time=41m", "rx-bytes=18002133", "tx-bytes=2200112")
        row("interface=phy0-ap0", "mac-address=00:00:5E:00:53:79", "signal=-71 dBm", "connected-time=9d2h", "rx-bytes=3204411", "tx-bytes=611900")
    }
    list("status/wireguard", writable = false) {
        row("interface=wg0", "name=phone", "endpoint=198.51.100.77:51820", "allowed-address=10.200.0.2/32", "last-handshake=1m12s", "rx=1843201", "tx=902211")
        row("interface=wg0", "name=laptop", "endpoint=192.0.2.88:40412", "allowed-address=10.200.0.3/32", "last-handshake=3h41m", "rx=88430211", "tx=12099871")
    }

    list("dhcp/dnsmasq", fields = switches) {
        row(".name=cfg01411c", "domainneeded=1", "boguspriv=1", "filterwin2k=0", "localise_queries=1", "rebind_protection=1", "rebind_localhost=1", "local=/lan/", "domain=lan", "expandhosts=1", "authoritative=1", "readethers=1", "leasefile=/tmp/dhcp.leases", "resolvfile=/tmp/resolv.conf.d/resolv.conf.auto")
    }
    list("dhcp/dhcp", fields = switches) {
        row(".name=lan", "interface=lan", "start=100", "limit=150", "leasetime=12h", "dhcpv4=server", "dhcpv6=server", "ra=server")
        row(".name=guest", "interface=guest", "start=100", "limit=100", "leasetime=1h", "dhcpv4=server")
        row(".name=wan", "interface=wan", "ignore=1")
    }
    list("dhcp/host") {
        row("name=nas", "mac=00:00:5E:00:53:20", "ip=192.168.1.20", "leasetime=infinite")
        row("name=printer", "mac=00:00:5E:00:53:30", "ip=192.168.1.30", "leasetime=infinite")
    }
    list("status/dhcp-leases", writable = false) {
        row("address=192.168.1.101", "mac-address=00:00:5E:00:53:65", "host-name=macbook-pro", "expires=11h21m")
        row("address=192.168.1.102", "mac-address=00:00:5E:00:53:66", "host-name=iphone-anna", "expires=8h02m")
        row("address=192.168.1.113", "mac-address=00:00:5E:00:53:71", "host-name=thinkpad", "expires=10h47m")
        row("address=192.168.1.121", "mac-address=00:00:5E:00:53:79", "host-name=esp32-sensor", "expires=5h33m")
    }

    settings("firewall/defaults", "input=REJECT", "output=ACCEPT", "forward=REJECT", "synflood_protect=1", fields = verdicts + flags("synflood_protect"))
    list("firewall/zone", fields = switches + verdicts + many("network")) {
        row(".name=cfg02dc81", "name=lan", "network=lan", "input=ACCEPT", "output=ACCEPT", "forward=ACCEPT")
        row(".name=cfg03dc81", "name=wan", "network=wan\nwan6", "input=REJECT", "output=ACCEPT", "forward=REJECT", "masq=1", "mtu_fix=1")
        row(".name=guest", "name=guest", "network=guest", "input=REJECT", "output=ACCEPT", "forward=REJECT")
    }
    list("firewall/forwarding") {
        row(".name=cfg04ad58", "src=lan", "dest=wan")
        row(".name=guest_wan", "src=guest", "dest=wan")
    }
    list("firewall/rule", ordered = true, fields = switches) {
        row(".name=cfg0592bd", "name=Allow-DHCP-Renew", "src=wan", "proto=udp", "dest_port=68", "target=ACCEPT", "family=ipv4", "enabled=1")
        row(".name=cfg06592b", "name=Allow-Ping", "src=wan", "proto=icmp", "target=ACCEPT", "family=ipv4", "enabled=1")
        row(".name=cfg07592b", "name=Allow-IGMP", "src=wan", "proto=igmp", "target=ACCEPT", "family=ipv4", "enabled=0")
        row(".name=cfg08592b", "name=Allow-DHCPv6", "src=wan", "proto=udp", "dest_ip=fc00::/6", "dest_port=546", "target=ACCEPT", "family=ipv6", "enabled=1")
        row(".name=guest_dns", "name=Guest-DNS", "src=guest", "proto=tcp udp", "dest_port=53", "target=ACCEPT", "enabled=1")
        row(".name=guest_dhcp", "name=Guest-DHCP", "src=guest", "proto=udp", "dest_port=67", "target=ACCEPT", "family=ipv4", "enabled=1")
        row(".name=ssh_lan", "name=Allow-SSH-from-VPN", "src=wan", "src_ip=198.51.100.0/24", "proto=tcp", "dest_port=22", "target=ACCEPT", "enabled=0")
    }
    list("firewall/redirect", ordered = true, fields = switches) {
        row(".name=nas_web", "name=NAS web", "src=wan", "src_dport=8080", "dest=lan", "dest_ip=192.168.1.20", "dest_port=80", "proto=tcp", "target=DNAT", "enabled=1")
    }
    list("status/connections", writable = false) {
        row("protocol=tcp", "src-address=192.168.1.101", "src-port=51944", "dst-address=140.82.121.5", "dst-port=443", "tcp-state=ESTABLISHED", "timeout=431999", "bytes=21904", "reply-bytes=411220")
        row("protocol=udp", "src-address=192.168.1.113", "src-port=49228", "dst-address=203.0.113.77", "dst-port=3478", "timeout=29", "bytes=1893211", "reply-bytes=29011888")
        row("protocol=tcp", "src-address=192.168.1.102", "src-port=60112", "dst-address=1.1.1.1", "dst-port=853", "tcp-state=ESTABLISHED", "timeout=431870", "bytes=4811", "reply-bytes=9220")
    }

    settings("system/system", "hostname=demo-ap", "timezone=MSK-3", "zonename=Europe/Moscow", "ttylogin=0", "log_size=64", "urandom_seed=0", "conloglevel=8", "cronloglevel=7")
    settings("system/timeserver", "enabled=1", "enable_server=0", "server=0.openwrt.pool.ntp.org\n1.openwrt.pool.ntp.org", fields = switches + many("server"))
    settings(
        "status/resource",
        "uptime=${duration(UPTIME)}", "date=2026-10-31", "time=12:00:00", "version=OpenWrt 24.10.2", "revision=r28739-d9340319c6", "board-name=Demo AP (mediatek,mt7981)",
        "architecture-name=aarch64_cortex-a53", "cpu=ARMv8 Processor rev 4", "kernel=6.6.86", "load-average=0.12 0.09 0.08", "free-memory=311459840", "total-memory=519700480",
        "free-hdd-space=81264640", "total-hdd-space=117440512",
        writable = false,
        live = { _, tick ->
            mapOf("uptime" to duration(UPTIME + tick), "free-memory" to "${wander(311_459_840, tick, 7)}") + clock()
        },
    )
    list("status/packages", writable = false) {
        row("name=base-files", "version=1626-r28739-d9340319c6")
        row("name=busybox", "version=1.36.1-r2")
        row("name=dnsmasq-full", "version=2.90-r3")
        row("name=dropbear", "version=2024.86-r1")
        row("name=firewall4", "version=2024.08.24~5ac5a4a0-r1")
        row("name=kernel", "version=6.6.86~a7ba5a9e1b36b3a4ccf0f1e1f1e5f8f6-r1")
        row("name=luci", "version=git-25.141.64829-14f7a3d")
        row("name=luci-app-firewall", "version=git-25.141.64829-14f7a3d")
        row("name=odhcpd-ipv6only", "version=2024.05.08~a3f8d7e5-r1")
        row("name=uhttpd", "version=2024.10.07~4e45d0be-r1")
        row("name=wireguard-tools", "version=1.0.20210914-r6")
        row("name=wpad-openssl", "version=2024.09.15~5ace39b0-r1")
    }
    list("status/processes", writable = false) {
        row("pid=1", "user=root", "command=/sbin/procd", "cpu=0", "memory=0.4", "vsz=1648", "stat=S")
        row("pid=412", "user=root", "command=/sbin/ubusd", "cpu=0", "memory=0.2", "vsz=1220", "stat=S")
        row("pid=1180", "user=root", "command=/usr/sbin/hostapd -s -g /var/run/hostapd/global", "cpu=1", "memory=1.1", "vsz=6204", "stat=S")
        row("pid=1342", "user=dnsmasq", "command=/usr/sbin/dnsmasq -C /var/etc/dnsmasq.conf.cfg01411c", "cpu=0", "memory=1.5", "vsz=9120", "stat=S")
        row("pid=1503", "user=root", "command=/usr/sbin/dropbear -F -P /var/run/dropbear.1.pid -p 22 -K 0", "cpu=0", "memory=0.5", "vsz=1912", "stat=S")
        row("pid=1611", "user=root", "command=/usr/sbin/uhttpd -f -h /www -r demo-ap -x /cgi-bin", "cpu=0", "memory=0.7", "vsz=2420", "stat=S")
    }
    list("status/scheduler") {
        row("minute=0", "hour=3", "day=*", "month=*", "weekday=*", "command=/etc/init.d/sysntpd restart")
        row("minute=30", "hour=4", "day=*", "month=*", "weekday=7", "command=/sbin/reboot")
    }
    list("status/services", writable = false) {
        row("name=dnsmasq", "start=19", "enabled=true", "running=true")
        row("name=dropbear", "start=19", "enabled=true", "running=true")
        row("name=firewall", "start=19", "enabled=true", "running=true")
        row("name=network", "start=20", "enabled=true", "running=true")
        row("name=sqm", "start=50", "enabled=false", "running=false")
        row("name=uhttpd", "start=50", "enabled=true", "running=true")
    }
    list("dropbear/dropbear", fields = switches) {
        row(".name=cfg029dfe", "PasswordAuth=on", "RootPasswordAuth=on", "Port=22", "Interface=lan")
    }
    list("uhttpd/uhttpd", fields = switches) {
        row(".name=main", "listen_http=0.0.0.0:80", "listen_https=0.0.0.0:443", "redirect_https=0", "home=/www", "rfc1918_filter=1", "max_requests=3", "cert=/etc/uhttpd.crt", "key=/etc/uhttpd.key", "cgi_prefix=/cgi-bin", "lua_prefix=/cgi-bin/luci=/usr/lib/lua/luci/sgi/uhttpd.lua")
    }
    list("sqm/queue", fields = switches) {
        row(".name=wan_sqm", "enabled=0", "interface=wan", "download=90000", "upload=18000", "qdisc=cake", "script=piece_of_cake.qos")
    }
    list("status/log", writable = false) {
        row("time=Sat Oct 31 11:58:02 2026", "topics=daemon.info", "message=dnsmasq-dhcp[1342]: DHCPACK(br-lan) 192.168.1.101 00:00:5e:00:53:65 macbook-pro")
        row("time=Sat Oct 31 11:41:19 2026", "topics=daemon.notice", "message=hostapd: phy1-ap0: AP-STA-CONNECTED 00:00:5e:00:53:66 auth_alg=sae")
        row("time=Sat Oct 31 09:00:05 2026", "topics=cron.info", "message=crond[1190]: USER root pid 9114 cmd /etc/init.d/sysntpd restart")
        row("time=Sat Oct 31 03:00:01 2026", "topics=daemon.notice", "message=netifd: Interface 'wan' is now up")
        row("time=Fri Oct 30 22:14:40 2026", "topics=daemon.info", "message=dropbear[7311]: Password auth succeeded for 'root' from 192.168.1.101:50122")
    }
    list("status/kernel-log", writable = false) {
        row("time=[    0.000000]", "topics=kern.notice", "message=Booting Linux on physical CPU 0x0000000000 [0x410fd034]")
        row("time=[    4.211873]", "topics=kern.info", "message=mt7915e 0000:00:00.0: HW/SW Version: 0x8a108a10, Build Time: 20240910090504a")
        row("time=[    9.804119]", "topics=kern.info", "message=br-lan: port 1(lan1) entered forwarding state")
        row("time=[   10.012774]", "topics=kern.info", "message=mtk_soc_eth 15100000.ethernet wan: Link is Up - 1Gbps/Full - flow control rx/tx")
    }
    command("tool/ping", text("address"), text("count"), text("interval"), run = ::ping)
    command("tool/traceroute", text("address"), text("count"), run = ::traceroute)
    command("tool/nslookup", text("name"), run = ::nslookup)
    command("tool/traffic", run = { traffic() })
}

private fun nslookup(arguments: Map<String, String>): Flow<Item> = flow {
    val name = arguments["name"].orEmpty()
    emit(Item("", mapOf("name" to name, "address" to "93.184.216.34", "status" to "ok")))
    emit(Item("", mapOf("name" to name, "address" to "2606:2800:21f:cb07:6820:80da:af6b:8b2c", "status" to "ok")))
}

private fun traffic(): Flow<Item> = flow {
    var tick = 0
    while (true) {
        for (name in listOf("wan", "br-lan", "br-guest", "phy1-ap0")) {
            emit(Item("", mapOf("name" to name) + interfaceRates(name, name == "wan", true, tick).filterKeys { it.startsWith("rx-bits") || it.startsWith("tx-bits") }))
        }
        tick++
        delay(1000)
    }
}
