package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.model.duration

private const val UPTIME = 41 * 86_400L + 2 * 3_600L + 17 * 60L

internal fun pfsenseDemo(): DemoSession = demoSession(
    DeviceInfo(Platform.Pfsense, "demo-pf.lan", "2.8.1", "amd64", "pfSense", emptySet(), setOf("read", "write"), id = "demo-pfsense"),
    Capabilities(subscriptions = false, streamingCommands = false, cancel = false, safeMode = SafeModeKind.None, terminal = false, files = false, export = false, menuContents = false),
) {
    list("interfaces/overview", writable = false) {
        row("name=wan", "descr=WAN", "hwif=igb0", "status=up", "ipaddr=203.0.113.90", "subnet=24", "macaddr=00:00:5e:00:53:51", "mtu=1500", "media=1000baseT <full-duplex>", "inbytes=912340112", "outbytes=88120344")
        row("name=lan", "descr=LAN", "hwif=igb1", "status=up", "ipaddr=172.16.0.1", "subnet=24", "macaddr=00:00:5e:00:53:52", "mtu=1500", "media=1000baseT <full-duplex>", "inbytes=88120344", "outbytes=912340112")
        row("name=opt1", "descr=DMZ", "hwif=igb2", "status=no carrier", "ipaddr=172.16.50.1", "subnet=24", "macaddr=00:00:5e:00:53:53", "mtu=1500", "media=1000baseT <full-duplex>", "inbytes=0", "outbytes=0")
    }
    list("interfaces/assigned") {
        row("if=igb0", "descr=WAN", "enable=true", "typev4=dhcp", "ipaddr=", "subnet=", "typev6=none", "mtu=1500")
        row("if=igb1", "descr=LAN", "enable=true", "typev4=staticv4", "ipaddr=172.16.0.1", "subnet=24", "typev6=none", "mtu=1500")
        row("if=igb2", "descr=DMZ", "enable=true", "typev4=staticv4", "ipaddr=172.16.50.1", "subnet=24", "typev6=none", "mtu=1500")
    }
    list("interfaces/vlan") {
        row("vlanif=igb1.30", "if=igb1", "tag=30", "pcp=0", "descr=Guests")
    }
    list("firewall/rule", ordered = true, fields = listOf(choice("type", "pass", "block", "reject"), choice("ipprotocol", "inet", "inet6", "inet46"), choice("protocol", "tcp", "udp", "tcp/udp", "icmp", "any"))) {
        row("type=pass", "interface=lan", "ipprotocol=inet", "protocol=any", "source=lan", "destination=any", "descr=Default allow LAN to any rule", "disabled=false")
        row("type=pass", "interface=wan", "ipprotocol=inet", "protocol=udp", "source=any", "destination=wan", "destination_port=51820", "descr=WireGuard", "disabled=false")
        row("type=pass", "interface=opt1", "ipprotocol=inet", "protocol=tcp", "source=any", "destination=172.16.50.10", "destination_port=443", "descr=DMZ web", "disabled=false")
        row("type=block", "interface=opt1", "ipprotocol=inet", "protocol=any", "source=opt1", "destination=lan", "descr=DMZ may not reach LAN", "disabled=false")
        row("type=reject", "interface=wan", "ipprotocol=inet", "protocol=tcp", "source=198.51.100.0/24", "destination=wan", "destination_port=22", "descr=SSH from the net", "disabled=true")
    }
    list("firewall/port-forward") {
        row("interface=wan", "protocol=tcp", "source=any", "destination=wan", "destination_port=443", "target=172.16.50.10", "local_port=443", "descr=DMZ web", "disabled=false")
    }
    list("firewall/outbound-nat") {
        row("interface=wan", "protocol=any", "source=172.16.0.0/24", "destination=any", "target=wan", "descr=LAN to WAN", "disabled=false")
    }
    list("firewall/alias") {
        row("name=admins", "type=host", "address=172.16.0.101 172.16.0.102", "descr=Admin workstations")
        row("name=web_ports", "type=port", "address=80 443 8443", "descr=Web")
    }
    list("routing/route") {
        row("network=10.30.0.0/16", "gateway=LAN_GW", "descr=Branch office", "disabled=false")
    }
    list("routing/gateway") {
        row("name=WAN_DHCP", "interface=wan", "ipprotocol=inet", "gateway=203.0.113.1", "monitor=203.0.113.1", "descr=Interface WAN_DHCP Gateway", "disabled=false")
        row("name=LAN_GW", "interface=lan", "ipprotocol=inet", "gateway=172.16.0.254", "monitor=172.16.0.254", "descr=Branch router", "disabled=false")
    }
    list("routing/gateway-status", writable = false) {
        row("name=WAN_DHCP", "srcip=203.0.113.90", "monitorip=203.0.113.1", "delay=1.8ms", "stddev=0.3ms", "loss=0.0%", "status=online")
        row("name=LAN_GW", "srcip=172.16.0.1", "monitorip=172.16.0.254", "delay=0.4ms", "stddev=0.1ms", "loss=0.0%", "status=online")
    }
    list("services/dhcp-lease", writable = false) {
        row("ip=172.16.0.101", "mac=00:00:5e:00:53:65", "hostname=macbook-pro", "if=lan", "starts=2026/10/31 09:12:04", "ends=2026/10/31 21:12:04", "active_status=active", "online_status=online", "descr=")
        row("ip=172.16.0.102", "mac=00:00:5e:00:53:66", "hostname=iphone-anna", "if=lan", "starts=2026/10/31 10:40:11", "ends=2026/10/31 22:40:11", "active_status=active", "online_status=idle", "descr=")
        row("ip=172.16.0.130", "mac=00:00:5e:00:53:82", "hostname=ps5", "if=lan", "starts=2026/10/30 18:01:47", "ends=2026/10/31 06:01:47", "active_status=expired", "online_status=offline", "descr=")
    }
    list("services/dns-override") {
        row("host=nas", "domain=lan", "ip=172.16.0.20", "descr=NAS")
        row("host=printer", "domain=lan", "ip=172.16.0.30", "descr=Printer")
    }
    list("services/status", writable = false) {
        row("name=unbound", "description=DNS Resolver", "enabled=true", "status=true")
        row("name=dhcpd", "description=DHCP Service", "enabled=true", "status=true")
        row("name=sshd", "description=Secure Shell Daemon", "enabled=true", "status=true")
        row("name=ntpd", "description=NTP clock sync", "enabled=true", "status=true")
        row("name=openvpn", "description=OpenVPN", "enabled=false", "status=false")
    }
    list("diagnostics/arp", writable = false) {
        row("ip_address=172.16.0.101", "mac_address=00:00:5e:00:53:65", "interface=igb1", "hostname=macbook-pro", "expires=19m", "type=ethernet")
        row("ip_address=172.16.0.102", "mac_address=00:00:5e:00:53:66", "interface=igb1", "hostname=iphone-anna", "expires=7m", "type=ethernet")
        row("ip_address=203.0.113.1", "mac_address=00:00:5e:00:53:fe", "interface=igb0", "hostname=", "expires=19m", "type=ethernet")
    }
    list("log/system", writable = false) {
        row("time=Oct 31 11:58:02", "program=dhcpd", "message=DHCPACK on 172.16.0.101 to 00:00:5e:00:53:65 (macbook-pro) via igb1")
        row("time=Oct 31 11:41:50", "program=check_reload_status", "message=Reloading filter")
        row("time=Oct 31 09:00:01", "program=sshd", "message=Accepted publickey for admin from 172.16.0.101 port 50122 ssh2")
        row("time=Oct 31 06:00:04", "program=php-fpm", "message=/index.php: Successful login for user 'admin' from: 172.16.0.101 (Local Database)")
    }
    list("log/firewall", writable = false) {
        row("time=Oct 31 11:58:14", "action=block", "interface=WAN", "direction=in", "protocol=TCP", "source=198.51.100.7:55871", "destination=203.0.113.90:22", "flags=S", "rule=Default deny rule IPv4 (1000000103)")
        row("time=Oct 31 11:57:02", "action=block", "interface=DMZ", "direction=in", "protocol=TCP", "source=172.16.50.10:41822", "destination=172.16.0.20:445", "flags=S", "rule=DMZ may not reach LAN")
    }
    settings(
        "system/resource",
        "platform=pfSense", "cpu_model=Intel(R) Celeron(R) J4125 CPU @ 2.00GHz", "cpu_count=4", "cpu-load=6", "cpu_load_avg=0.21, 0.17, 0.15", "memory-usage=18", "swap_usage=0", "disk_usage=7", "temp_c=47.0",
        "uptime=${duration(UPTIME)}", "serial=DEMO-PF-0001",
        writable = false,
        live = { _, tick -> mapOf("cpu-load" to "${wander(6, tick, 2)}", "uptime" to duration(UPTIME + tick)) },
    )
}
