package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.model.duration

private const val UPTIME = 3 * 86_400L + 4 * 3_600L + 12 * 60L + 33L

private val rules = listOf(choice("chain", "input", "forward", "output"), choice("protocol", "tcp", "udp", "icmp", "ipsec-esp", "gre", "ospf", "vrrp"))

internal fun routerOsDemo(): DemoSession = demoSession(
    DeviceInfo(
        Platform.RouterOs, "demo-hap", "7.24.1", "arm64", "hAP ax^3", setOf("routeros", "wifi-qcom", "container", "dude", "iot", "cmr", "openflow", "netinstall"),
        setOf("local", "telnet", "ssh", "ftp", "reboot", "read", "write", "policy", "test", "winbox", "password", "web", "sniff", "sensitive", "api", "romon", "rest-api"),
        id = "DEMO-ROUTEROS",
    ),
    Capabilities(subscriptions = true, streamingCommands = true, cancel = true, safeMode = SafeModeKind.ApiSession, terminal = false, files = false, export = true),
    ids = { "*" + it.toString(16).uppercase() },
) {
    settings(
        "system/resource",
        "uptime=${duration(UPTIME)}", "version=7.24.1 (stable)", "build-time=2026-09-02 10:47:15", "factory-software=7.10.1",
        "free-memory=812941312", "total-memory=1073741824", "cpu=ARM64", "cpu-count=4", "cpu-frequency=1800", "cpu-load=4",
        "free-hdd-space=89849856", "total-hdd-space=134217728", "write-sect-since-reboot=2168", "write-sect-total=1842310",
        "architecture-name=arm64", "board-name=hAP ax^3", "platform=MikroTik",
        live = { _, tick ->
            mapOf(
                "uptime" to duration(UPTIME + tick), "cpu-load" to "${wander(5, tick, 3)}", "free-memory" to "${wander(812_941_312, tick, 5)}",
            )
        },
    )
    settings("system/identity", "name=demo-hap")
    settings(
        "system/clock",
        "time-zone-autodetect=true", "time-zone-name=Europe/Moscow", "gmt-offset=+03:00", "dst-active=false",
        live = { _, _ -> clock() },
    )
    settings("system/ntp/client", "enabled=true", "mode=unicast", "servers=pool.ntp.org", "status=synchronized", "freq-drift=-1.9 PPM", "synced-server=162.159.200.1", "synced-stratum=3", "system-offset=-0.482 ms")
    settings("system/license", "system-id=DEMO-ROUTEROS", "software-id=DEMO-0000", "level=4", "features=", writable = false)
    list("system/package", writable = false) {
        row("name=routeros", "version=7.24.1", "build-time=2026-09-02 10:47:15", "disabled=false")
        row("name=wifi-qcom", "version=7.24.1", "build-time=2026-09-02 10:47:15", "disabled=false")
        row("name=container", "version=7.24.1", "build-time=2026-09-02 10:47:15", "disabled=false")
        row("name=dude", "version=7.24.1", "build-time=2026-09-02 10:47:15", "disabled=false")
        row("name=iot", "version=7.24.1", "build-time=2026-09-02 10:47:15", "disabled=false")
        row("name=cmr", "version=7.24.1", "build-time=2026-09-02 10:47:15", "disabled=false")
        row("name=openflow", "version=7.24.1", "build-time=2026-09-02 10:47:15", "disabled=false")
        row("name=netinstall", "version=7.24.1", "build-time=2026-09-02 10:47:15", "disabled=false")
    }
    list("system/scheduler") {
        row("name=backup-weekly", "start-date=2026-01-04", "start-time=03:00:00", "interval=1w", "owner=admin", "run-count=43", "next-run=2026-11-01 03:00:00", "on-event=/system backup save name=weekly", "disabled=false")
        row("name=reboot-monthly", "start-date=2026-01-01", "start-time=04:30:00", "interval=4w2d", "owner=admin", "run-count=10", "next-run=2026-11-16 04:30:00", "on-event=/system reboot", "disabled=true")
    }
    list("system/script") {
        row("name=wan-watch", "owner=admin", "last-started=2026-10-31 09:15:02", "run-count=412", "source=:if ([/ping 1.1.1.1 count=2] = 0) do={ :log warning \"wan is down\" }")
        row("name=guest-off", "owner=admin", "last-started=2026-10-30 23:00:00", "run-count=88", "source=/interface wifi disable wifi1")
    }
    list("system/logging") {
        row("topics=info", "action=memory", "disabled=false", "default=true")
        row("topics=error", "action=memory", "disabled=false", "default=true")
        row("topics=warning", "action=memory", "disabled=false", "default=true")
        row("topics=critical", "action=echo", "disabled=false", "default=true")
    }
    list("user") {
        row("name=admin", "group=full", "address=", "last-logged-in=2026-10-31 09:41:55", "disabled=false", "comment=system default user")
        row("name=viewer", "group=read", "address=192.168.88.0/24", "last-logged-in=2026-10-29 18:02:10", "disabled=false")
    }
    list("user/group") {
        row("name=read", "policy=local,telnet,ssh,reboot,read,test,winbox,password,web,sniff,api,romon,rest-api,!ftp,!write,!policy,!sensitive", "skin=default")
        row("name=write", "policy=local,telnet,ssh,reboot,read,write,test,winbox,password,web,sniff,api,romon,rest-api,!ftp,!policy,!sensitive", "skin=default")
        row("name=full", "policy=local,telnet,ssh,ftp,reboot,read,write,policy,test,winbox,password,web,sniff,sensitive,api,romon,rest-api", "skin=default")
    }


    list("ip/address") {
        row("address=192.168.88.1/24", "network=192.168.88.0", "interface=bridge", "actual-interface=bridge", "dynamic=false", "invalid=false", "disabled=false", "comment=defconf")
        row("address=203.0.113.50/24", "network=203.0.113.0", "interface=ether1", "actual-interface=ether1", "dynamic=true", "invalid=false", "disabled=false")
        row("address=10.66.66.1/24", "network=10.66.66.0", "interface=wg-home", "actual-interface=wg-home", "dynamic=false", "invalid=false", "disabled=false")
    }
    list("ip/dhcp-client") {
        row("name=wan", "interface=ether1", "use-peer-dns=true", "add-default-route=yes", "address=203.0.113.50/24", "gateway=203.0.113.1", "dhcp-server=203.0.113.1", "primary-dns=203.0.113.2", "expires-after=9h42m10s", "status=bound", "disabled=false", "dynamic=false", "invalid=false")
    }
    list("ip/route", fields = listOf(choice("routing-table", "main"))) {
        row("dst-address=0.0.0.0/0", "gateway=203.0.113.1", "immediate-gw=203.0.113.1%ether1", "distance=1", "routing-table=main", "active=true", "dynamic=true", "dhcp=true")
        row("dst-address=192.168.88.0/24", "gateway=bridge", "immediate-gw=bridge", "distance=0", "routing-table=main", "pref-src=192.168.88.1", "local-address=192.168.88.1%bridge", "active=true", "dynamic=true", "connect=true")
        row("dst-address=203.0.113.0/24", "gateway=ether1", "immediate-gw=ether1", "distance=0", "routing-table=main", "pref-src=203.0.113.50", "local-address=203.0.113.50%ether1", "active=true", "dynamic=true", "connect=true")
        row("dst-address=10.66.66.0/24", "gateway=wg-home", "immediate-gw=wg-home", "distance=0", "routing-table=main", "pref-src=10.66.66.1", "local-address=10.66.66.1%wg-home", "active=true", "dynamic=true", "connect=true")
        row("dst-address=10.20.0.0/16", "gateway=192.168.88.254", "immediate-gw=192.168.88.254%bridge", "distance=1", "routing-table=main", "active=true", "static=true", "comment=office over the switch")
        row("dst-address=10.30.0.0/16", "gateway=192.168.88.254", "immediate-gw=192.168.88.254%bridge", "distance=110", "routing-table=main", "active=true", "dynamic=true", "ospf=true")
        row("dst-address=10.20.0.0/16", "gateway=192.168.88.254", "immediate-gw=192.168.88.254%bridge", "distance=110", "routing-table=main", "active=false", "dynamic=true", "ospf=true")
        row("dst-address=10.50.0.0/24", "gateway=192.0.2.1", "immediate-gw=192.0.2.1%ether1", "distance=20", "routing-table=main", "active=true", "dynamic=true", "bgp=true")
    }
    list("ip/pool") {
        row("name=dhcp", "ranges=192.168.88.10-192.168.88.254", "total=245", "used=8", "available=237")
        row("name=guest", "ranges=192.168.89.10-192.168.89.200", "total=191", "used=0", "available=191")
    }
    list("ip/dhcp-server") {
        row("name=defconf", "interface=bridge", "lease-time=10m", "address-pool=dhcp", "add-arp=false", "authoritative=yes", "disabled=false", "invalid=false", "dynamic=false")
    }
    list("ip/dhcp-server/network") {
        row("address=192.168.88.0/24", "gateway=192.168.88.1", "dns-server=192.168.88.1", "domain=lan", "comment=defconf")
    }
    list("ip/dhcp-server/lease", fields = listOf(choice("server", "defconf"))) {
        row("address=192.168.88.20", "mac-address=00:00:5E:00:53:20", "client-id=1:0:0:5e:0:53:20", "server=defconf", "active-address=192.168.88.20", "active-mac-address=00:00:5E:00:53:20", "active-server=defconf", "host-name=nas", "expires-after=7m31s", "last-seen=2m29s", "status=bound", "dynamic=false", "disabled=false", "blocked=false", "radius=false", "comment=NAS")
        row("address=192.168.88.30", "mac-address=00:00:5E:00:53:30", "server=defconf", "active-address=192.168.88.30", "active-mac-address=00:00:5E:00:53:30", "active-server=defconf", "host-name=printer-hp", "expires-after=4m02s", "last-seen=5m58s", "status=bound", "dynamic=false", "disabled=false", "blocked=false", "radius=false", "comment=printer")
        row("address=192.168.88.101", "mac-address=00:00:5E:00:53:65", "client-id=1:0:0:5e:0:53:65", "server=defconf", "active-address=192.168.88.101", "active-mac-address=00:00:5E:00:53:65", "active-server=defconf", "host-name=macbook-pro", "expires-after=9m11s", "last-seen=49s", "status=bound", "dynamic=true", "disabled=false", "blocked=false", "radius=false")
        row("address=192.168.88.102", "mac-address=00:00:5E:00:53:66", "client-id=1:0:0:5e:0:53:66", "server=defconf", "active-address=192.168.88.102", "active-mac-address=00:00:5E:00:53:66", "active-server=defconf", "host-name=iphone-anna", "expires-after=6m40s", "last-seen=3m20s", "status=bound", "dynamic=true", "disabled=false", "blocked=false", "radius=false")
        row("address=192.168.88.103", "mac-address=00:00:5E:00:53:67", "client-id=1:0:0:5e:0:53:67", "server=defconf", "active-address=192.168.88.103", "active-mac-address=00:00:5E:00:53:67", "active-server=defconf", "host-name=galaxy-s25", "expires-after=2m15s", "last-seen=7m45s", "status=bound", "dynamic=true", "disabled=false", "blocked=false", "radius=false")
        row("address=192.168.88.110", "mac-address=00:00:5E:00:53:6E", "client-id=1:0:0:5e:0:53:6e", "server=defconf", "active-address=192.168.88.110", "active-mac-address=00:00:5E:00:53:6E", "active-server=defconf", "host-name=tv-livingroom", "expires-after=8m50s", "last-seen=1m10s", "status=bound", "dynamic=true", "disabled=false", "blocked=false", "radius=false")
        row("address=192.168.88.121", "mac-address=00:00:5E:00:53:79", "client-id=1:0:0:5e:0:53:79", "server=defconf", "active-address=192.168.88.121", "active-mac-address=00:00:5E:00:53:79", "active-server=defconf", "host-name=esp32-sensor", "expires-after=5m33s", "last-seen=4m27s", "status=bound", "dynamic=true", "disabled=false", "blocked=false", "radius=false")
        row("address=192.168.88.130", "mac-address=00:00:5E:00:53:82", "client-id=1:0:0:5e:0:53:82", "server=defconf", "active-address=192.168.88.130", "active-mac-address=00:00:5E:00:53:82", "active-server=defconf", "host-name=ps5", "expires-after=9m58s", "last-seen=2s", "status=bound", "dynamic=true", "disabled=false", "blocked=false", "radius=false")
    }
    settings(
        "ip/dns",
        "servers=1.1.1.1,9.9.9.9", "dynamic-servers=203.0.113.2", "allow-remote-requests=true", "use-doh-server=", "verify-doh-cert=false",
        "max-udp-packet-size=4096", "query-server-timeout=2s", "query-total-timeout=10s", "max-concurrent-queries=100", "max-concurrent-tcp-sessions=20",
        "cache-size=2048KiB", "cache-max-ttl=1w", "cache-used=312KiB",
    )
    list("ip/dns/static", ordered = true, fields = listOf(choice("type", "A", "AAAA", "CNAME", "MX", "TXT", "FWD", "NS", "SRV"))) {
        row("name=router.lan", "type=A", "address=192.168.88.1", "ttl=1d", "disabled=false")
        row("name=nas.lan", "type=A", "address=192.168.88.20", "ttl=1d", "disabled=false")
        row("name=printer.lan", "type=A", "address=192.168.88.30", "ttl=1d", "disabled=false")
        row("name=files.lan", "type=CNAME", "cname=nas.lan", "ttl=1d", "disabled=false")
    }
    list("ip/dns/cache", writable = false) {
        row("name=example.com", "type=A", "data=93.184.216.34", "ttl=21m4s")
        row("name=api.github.com", "type=A", "data=140.82.121.5", "ttl=57s")
        row("name=cloudflare.com", "type=AAAA", "data=2606:4700::6810:84e5", "ttl=4m12s")
        row("name=time.cloudflare.com", "type=A", "data=162.159.200.1", "ttl=2h19m")
        row("name=nas.lan", "type=A", "data=192.168.88.20", "ttl=1d", "static=true")
    }
    list("ip/arp") {
        row("address=192.168.88.20", "mac-address=00:00:5E:00:53:20", "interface=bridge", "status=reachable", "dynamic=false", "complete=true", "dhcp=true", "host-name=nas")
        row("address=192.168.88.30", "mac-address=00:00:5E:00:53:30", "interface=bridge", "status=stale", "dynamic=false", "complete=true", "dhcp=true", "host-name=printer-hp")
        row("address=192.168.88.101", "mac-address=00:00:5E:00:53:65", "interface=bridge", "status=reachable", "dynamic=true", "complete=true", "dhcp=true", "host-name=macbook-pro")
        row("address=192.168.88.102", "mac-address=00:00:5E:00:53:66", "interface=bridge", "status=stale", "dynamic=true", "complete=true", "dhcp=true", "host-name=iphone-anna")
        row("address=192.168.88.130", "mac-address=00:00:5E:00:53:82", "interface=bridge", "status=reachable", "dynamic=true", "complete=true", "dhcp=true", "host-name=ps5")
        row("address=203.0.113.1", "mac-address=00:00:5E:00:53:FE", "interface=ether1", "status=reachable", "dynamic=true", "complete=true")
    }
    list("ip/firewall/filter", ordered = true, fields = rules + choice("action", "accept", "drop", "reject", "fasttrack-connection", "passthrough", "log", "jump", "return", "tarpit"), live = counting()) {
        row("chain=input", "action=accept", "connection-state=established,related,untracked", "comment=defconf: accept established,related,untracked", "disabled=false")
        row("chain=input", "action=drop", "connection-state=invalid", "comment=defconf: drop invalid", "disabled=false")
        row("chain=input", "action=accept", "protocol=icmp", "comment=defconf: accept ICMP", "disabled=false")
        row("chain=input", "action=accept", "dst-address=127.0.0.1", "comment=defconf: accept to local loopback (for CAPsMAN)", "disabled=false")
        row("chain=input", "action=accept", "protocol=udp", "dst-port=13231", "in-interface=ether1", "comment=WireGuard", "disabled=false")
        row("chain=input", "action=drop", "in-interface-list=!LAN", "comment=defconf: drop all not coming from LAN", "disabled=false")
        row("chain=forward", "action=accept", "ipsec-policy=in,ipsec", "comment=defconf: accept in ipsec policy", "disabled=false")
        row("chain=forward", "action=fasttrack-connection", "connection-state=established,related", "hw-offload=true", "comment=defconf: fasttrack", "disabled=false")
        row("chain=forward", "action=accept", "connection-state=established,related,untracked", "comment=defconf: accept established,related, untracked", "disabled=false")
        row("chain=forward", "action=drop", "connection-state=invalid", "comment=defconf: drop invalid", "disabled=false")
        row("chain=forward", "action=drop", "connection-state=new", "connection-nat-state=!dstnat", "in-interface-list=WAN", "comment=defconf: drop all from WAN not DSTNATed", "disabled=false")
    }
    list("ip/firewall/nat", ordered = true, fields = rules + choice("action", "accept", "masquerade", "dst-nat", "src-nat", "netmap", "redirect", "return", "passthrough", "log", "jump"), live = counting()) {
        row("chain=srcnat", "action=masquerade", "out-interface-list=WAN", "ipsec-policy=out,none", "comment=defconf: masquerade", "disabled=false")
        row("chain=dstnat", "action=dst-nat", "protocol=tcp", "dst-port=8080", "in-interface=ether1", "to-addresses=192.168.88.20", "to-ports=80", "comment=NAS web", "disabled=false")
    }
    list("ip/firewall/address-list") {
        row("list=blocked", "address=198.51.100.7", "timeout=23h41m", "creation-time=2026-10-30 10:12:44", "dynamic=true")
        row("list=admin", "address=192.168.88.101", "creation-time=2026-09-12 20:01:00", "dynamic=false")
        row("list=admin", "address=10.66.66.0/24", "creation-time=2026-09-12 20:01:00", "dynamic=false")
    }
    list("ip/firewall/connection", writable = false) {
        row("src-address=192.168.88.101:51944", "dst-address=140.82.121.5:443", "protocol=tcp", "tcp-state=established", "timeout=23h59m55s", "orig-rate=1.4kbps", "repl-rate=5.2kbps", "orig-packets=318", "repl-packets=402", "orig-bytes=21904", "repl-bytes=411220")
        row("src-address=192.168.88.130:49228", "dst-address=203.0.113.77:3478", "protocol=udp", "timeout=9s", "orig-rate=82kbps", "repl-rate=410kbps", "orig-packets=18821", "repl-packets=24012", "orig-bytes=1893211", "repl-bytes=29011888")
        row("src-address=192.168.88.102:60112", "dst-address=1.1.1.1:853", "protocol=tcp", "tcp-state=established", "timeout=23h59m1s", "orig-rate=0bps", "repl-rate=0bps", "orig-packets=40", "repl-packets=38", "orig-bytes=4811", "repl-bytes=9220")
        row("src-address=192.168.88.110:42118", "dst-address=198.51.100.140:443", "protocol=tcp", "tcp-state=established", "timeout=23h58m40s", "orig-rate=22kbps", "repl-rate=3.6Mbps", "orig-packets=88123", "repl-packets=391082", "orig-bytes=5822101", "repl-bytes=512004112")
        row("src-address=198.51.100.7:55871", "dst-address=203.0.113.50:22", "protocol=tcp", "tcp-state=syn-sent", "timeout=2s", "orig-rate=0bps", "repl-rate=0bps", "orig-packets=3", "repl-packets=0", "orig-bytes=180", "repl-bytes=0")
    }
    list("ip/service") {
        row("name=telnet", "port=23", "available-from=", "disabled=true", "max-sessions=20", "dynamic=false")
        row("name=ftp", "port=21", "available-from=", "disabled=true", "max-sessions=20", "dynamic=false")
        row("name=www", "port=80", "available-from=192.168.88.0/24", "disabled=false", "max-sessions=20", "dynamic=false")
        row("name=ssh", "port=22", "available-from=192.168.88.0/24,10.66.66.0/24", "disabled=false", "max-sessions=20", "dynamic=false")
        row("name=www-ssl", "port=443", "available-from=", "certificate=router.lan", "tls-version=any", "disabled=true", "max-sessions=20", "dynamic=false")
        row("name=api", "port=8728", "available-from=", "disabled=true", "max-sessions=20", "dynamic=false")
        row("name=winbox", "port=8291", "available-from=192.168.88.0/24", "disabled=false", "max-sessions=20", "dynamic=false")
        row("name=api-ssl", "port=8729", "available-from=", "certificate=router.lan", "tls-version=any", "disabled=false", "max-sessions=20", "dynamic=false")
    }
    list("ip/neighbor", writable = false) {
        row("interface=bridge/ether5", "address=192.168.88.2", "mac-address=00:00:5E:00:53:A2", "identity=switch-closet", "platform=MikroTik", "version=7.24.1 (stable)", "board=CSS610-8G-2S+", "age=24s", "uptime=41d2h")
        row("interface=bridge/wifi2", "address=192.168.88.3", "mac-address=00:00:5E:00:53:A3", "identity=cap-attic", "platform=MikroTik", "version=7.23.2 (stable)", "board=cAP ax", "age=11s", "uptime=12d9h")
        row("interface=ether1", "address=203.0.113.1", "mac-address=00:00:5E:00:53:FE", "identity=isp-edge", "platform=MikroTik", "version=7.18.2 (long-term)", "board=CCR2004-1G-12S+2XS", "age=33s", "uptime=102d14h")
    }
    list("tool/netwatch") {
        row("name=cloudflare", "host=1.1.1.1", "type=icmp", "interval=10s", "status=up", "since=2026-10-28 02:14:09", "done-tests=21644", "failed-tests=12", "disabled=false")
        row("name=nas", "host=192.168.88.20", "type=icmp", "interval=30s", "status=up", "since=2026-10-27 22:01:43", "done-tests=7212", "failed-tests=0", "disabled=false")
    }
    command("tool/ping", text("address"), text("count"), text("interval"), text("size"), text("ttl"), text("src-address"), run = ::ping)
    command("tool/traceroute", text("address"), text("count"), text("timeout"), text("size"), text("max-hops"), run = ::traceroute)
    command("interface/monitor-traffic", text("interface"), run = ::monitorTraffic)
    list("log", writable = false) {
        row("time=09:41:55", "topics=system,info,account", "message=user admin logged in from 192.168.88.101 via winbox")
        row("time=09:15:02", "topics=script,warning", "message=wan is down")
        row("time=09:15:00", "topics=dhcp,info", "message=dhcp-client on ether1 lost IP 203.0.113.50")
        row("time=09:14:41", "topics=interface,info", "message=ether1 link down")
        row("time=09:14:53", "topics=interface,info", "message=ether1 link up (speed 1G, full duplex)")
        row("time=09:15:03", "topics=dhcp,info", "message=dhcp-client on ether1 got IP address 203.0.113.50")
        row("time=08:59:18", "topics=wireguard,debug", "message=wg-home: peer phone-anna handshake completed")
        row("time=08:12:44", "topics=dhcp,info", "message=defconf assigned 192.168.88.103 to 00:00:5E:00:53:67")
        row("time=07:30:00", "topics=system,info", "message=scheduler backup-weekly finished")
        row("time=06:02:27", "topics=firewall,info", "message=input: in:ether1 out:(unknown 0), src-mac 00:00:5e:00:53:fe, proto TCP (SYN), 198.51.100.7:55871->203.0.113.50:22, len 60")
        row("time=oct/30 23:00:00", "topics=script,info", "message=guest-off: wifi1 disabled")
        row("time=oct/30 10:12:44", "topics=firewall,info", "message=198.51.100.7 added to blocked for 1d")
        row("time=oct/29 18:02:10", "topics=system,info,account", "message=user viewer logged in from 192.168.88.50 via ssh")
        row("time=oct/28 02:14:09", "topics=netwatch,info", "message=netwatch cloudflare is up")
        row("time=oct/28 02:13:57", "topics=netwatch,warning", "message=netwatch cloudflare is down")
        row("time=oct/27 22:01:43", "topics=system,info", "message=router rebooted")
    }
    interfacesDemo()
    ipDemo()
    ipv6Demo()
    routingDemo()
    systemDemo()
    toolsDemo()
    controllerDemo()
    packagesDemo()
    linksDemo()
}
