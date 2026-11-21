package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.keenetic.SECURITY
import com.vasmarfas.networkhelm.keenetic.TAGS
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.duration

private const val UPTIME = 8 * 86_400L + 19 * 3_600L + 5 * 60L

internal fun keeneticDemo(): DemoSession = demoSession(
    DeviceInfo(Platform.Keenetic, "Keenetic Demo", "5.1.6", "mips", "Keenetic Giga (KN-1011)", emptySet(), setOf("read", "write"), id = "demo-keenetic"),
    Capabilities(subscriptions = false, streamingCommands = false, cancel = false, safeMode = SafeModeKind.None, terminal = false, files = false, export = false, menuContents = false),
) {
    settings(
        "status",
        "model=Keenetic Giga (KN-1011)", "firmware=5.1.6", "channel=stable", "hostname=Keenetic Demo", "uptime=${duration(UPTIME)}", "time=2026-10-31 12:04:31",
        "cpu-load=7", "used-memory=136314880", "total-memory=536870912", "internet=true", "connection=Provider", "connection-type=PPPoE", "address=203.0.113.120",
        "connected=8d19h", "rxspeed=8400000", "txspeed=1200000", "rxbytes=9814006313", "txbytes=1530211907", "dns-servers=203.0.113.1, 9.9.9.9",
        "mask=255.255.255.255", "remote=203.0.113.1", "mac=00:00:5e:00:53:71", "carrier=Broadband connection", "connections=213", "connections-max=32768",
        "connection-id=PPPoE0",
        "port.0.link=up", "port.0.speed=1000", "port.0.duplex=full", "port.0.internet=true",
        "port.1.link=up", "port.1.speed=1000", "port.1.duplex=full", "port.1.internet=false",
        "port.2.link=up", "port.2.speed=100", "port.2.duplex=full", "port.2.internet=false",
        "port.3.link=down", "port.3.internet=false", "port.4.link=down", "port.4.internet=false",
        "network.Bridge0.name=Home network", "network.Bridge0.address=192.168.1.1", "network.Bridge0.wifi=3", "network.Bridge0.wired=1",
        "network.Bridge1.name=Guest network", "network.Bridge1.address=192.168.2.1", "network.Bridge1.wifi=1", "network.Bridge1.wired=0",
        "ap.WifiMaster0/AccessPoint0.ssid=Demo-2.4", "ap.WifiMaster0/AccessPoint0.band=2.4 GHz", "ap.WifiMaster0/AccessPoint0.channel=6",
        "ap.WifiMaster0/AccessPoint0.security=wpa2,wpa3", "ap.WifiMaster0/AccessPoint0.network=Bridge0", "ap.WifiMaster0/AccessPoint0.up=true",
        "ap.WifiMaster1/AccessPoint0.ssid=Demo-5", "ap.WifiMaster1/AccessPoint0.band=5 GHz", "ap.WifiMaster1/AccessPoint0.channel=44",
        "ap.WifiMaster1/AccessPoint0.security=wpa2,wpa3", "ap.WifiMaster1/AccessPoint0.network=Bridge0", "ap.WifiMaster1/AccessPoint0.up=true",
        "ap.WifiMaster0/AccessPoint1.ssid=Demo-Guest", "ap.WifiMaster0/AccessPoint1.band=2.4 GHz", "ap.WifiMaster0/AccessPoint1.channel=6",
        "ap.WifiMaster0/AccessPoint1.security=wpa2", "ap.WifiMaster0/AccessPoint1.network=Bridge1", "ap.WifiMaster0/AccessPoint1.up=true",
        "keendns=demo.keenetic.link", "keendns-access=direct", "wifi=Demo-2.4 (2.4 GHz), Demo-5 (5 GHz), Demo-Guest (2.4 GHz)", "clients-online=4", "clients-registered=4",
        "radio.WifiMaster0.band=2.4 GHz", "radio.WifiMaster0.channel=6", "radio.WifiMaster0.width=20", "radio.WifiMaster0.temperature=51", "radio.WifiMaster0.clients=2",
        "radio.WifiMaster0.load=34", "radio.WifiMaster0.up=true",
        "radio.WifiMaster1.band=5 GHz", "radio.WifiMaster1.channel=44", "radio.WifiMaster1.width=80", "radio.WifiMaster1.temperature=55", "radio.WifiMaster1.clients=1",
        "radio.WifiMaster1.load=9", "radio.WifiMaster1.up=true",
        "top.0.name=nas", "top.0.rx=118400512", "top.0.tx=9437184", "top.1.name=macbook-pro", "top.1.rx=40894464", "top.1.tx=3145728",
        "top.2.name=", "top.2.rx=1048576", "top.2.tx=524288",
        "app.wireguard-server.name=WireGuard VPN", "app.wireguard-server.enabled=true", "app.oc-server.name=OpenConnect VPN", "app.oc-server.enabled=false",
        "auto-update=true", "content-filter=", "remote-access=true", "extenders=0", "service-tag=000-000-000-000-000", "serial=S0000000000000",
        writable = false,
        live = { _, tick ->
            mapOf(
                "uptime" to duration(UPTIME + tick), "cpu-load" to "${wander(7, tick, 4)}", "rxspeed" to "${wander(8_400_000, tick, 11)}", "txspeed" to "${wander(1_200_000, tick, 12)}",
                "radio.WifiMaster0.load" to "${wander(34, tick, 6)}", "radio.WifiMaster1.load" to "${wander(9, tick, 7)}",
            )
        },
    )
    list("internet/wired") {
        row(".id=PPPoE0", "name=PPPoE0", "title=Provider", "description=Provider", "type=PPPoE", "connected=yes", "disabled=false", "address=203.0.113.120", "mask=255.255.255.255", "uptime=8d19h", "default=true", "priority=32767", "security-level=public", "mtu=1492", "rxbytes=9814006313", "txbytes=1530211907")
        row(".id=GigabitEthernet1/Vlan2", "name=GigabitEthernet1/Vlan2", "title=Broadband connection", "description=Broadband connection", "type=Vlan", "connected=yes", "disabled=false", "uptime=8d19h", "default=false", "security-level=public", "mtu=1500", "mac=00:00:5e:00:53:71", "rxbytes=9930417710", "txbytes=1561002346")
    }
    list("internet/mobile") {
        row(".id=UsbQmi0", "name=UsbQmi0", "title=Mobile", "description=Mobile", "type=UsbQmi", "connected=no", "disabled=false", "address=", "uptime=", "default=false", "priority=32766", "security-level=public", "mtu=1500", "rxbytes=0", "txbytes=0")
    }
    list("internet/wisp") {
        row(".id=WifiMaster0/WifiStation0", "name=WifiMaster0/WifiStation0", "title=WISP", "description=WISP", "type=WifiStation", "connected=no", "disabled=true", "default=false", "security-level=public", "mtu=1500")
    }
    list("apps", writable = false) {
        row(".id=cifs", "name=SMB Server", "status=Installed", "components=tsmb")
        row(".id=torrent", "name=Download Station", "status=Installed", "components=transmission")
        row(".id=dlna", "name=Media Server", "status=Installed", "components=dlna")
        row(".id=vpn-sstp", "name=SSTP VPN Server", "status=Not installed", "components=sstp-server, ndns")
        row(".id=wireguard-server", "name=WireGuard VPN Server", "status=Installed", "components=wireguard-server, ndns")
        row(".id=vpn-openconnect", "name=OpenConnect VPN Server", "status=Installed", "components=ocserver")
    }
    settings(
        "show/internet/status",
        "internet=true", "gateway-accessible=true", "dns-accessible=true", "captive-accessible=true", "reliable=true", "checked=Sat Oct 31 11:58:00 2026",
        "gateway.interface=PPPoE0", "gateway.failures=0", "captive.host=captive.keenetic.ru", "captive.response=200 (OK)",
        writable = false,
    )
    list("show/ip/name-server", writable = false) {
        row("address=203.0.113.1", "domain=", "interface=PPPoE0", "service=Network::Interface::Ppp-PPPoE0")
        row("address=9.9.9.9", "domain=", "interface=", "service=")
    }
    list("ip/name-server") {
        row(".id=9.9.9.9", "address=9.9.9.9", "domain=")
    }
    list("clients") {
        row(".id=00:00:5e:00:53:65", "name=macbook-pro", "ip=192.168.1.101", "mac=00:00:5e:00:53:65", "hostname=macbook-pro", "segment=Home", "connection=Demo-5 (5 GHz)", "online=true", "uptime=3h12m", "rxbytes=88120344", "txbytes=412809221", "registered=true", "access=permit", "static-ip=", "expires=10h31m", "priority=6")
        row(".id=00:00:5e:00:53:66", "name=iphone-anna", "ip=192.168.1.102", "mac=00:00:5e:00:53:66", "hostname=iphone-anna", "segment=Home", "connection=Demo-2.4 (2.4 GHz)", "online=true", "uptime=41m", "rxbytes=2200112", "txbytes=18002133", "registered=true", "access=permit", "static-ip=", "expires=7h12m", "priority=6")
        row(".id=00:00:5e:00:53:79", "name=nas", "ip=192.168.1.10", "mac=00:00:5e:00:53:79", "hostname=nas", "segment=Home", "connection=Port 2", "online=true", "uptime=8d19h", "rxbytes=31042004118", "txbytes=2201955034", "registered=true", "access=permit", "static-ip=192.168.1.10", "priority=6")
        row(".id=00:00:5e:00:53:7b", "name=esp32-sensor", "ip=192.168.1.121", "mac=00:00:5e:00:53:7b", "hostname=esp32-sensor", "segment=Home", "connection=Demo-2.4 (2.4 GHz)", "online=true", "uptime=8d19h", "rxbytes=611900", "txbytes=3204411", "registered=true", "access=deny", "static-ip=", "expires=4h2m", "priority=6")
        row(".id=00:00:5e:00:53:7a", "name=guest-phone", "ip=192.168.2.14", "mac=00:00:5e:00:53:7a", "hostname=guest-phone", "segment=Guest", "connection=Demo-Guest (2.4 GHz)", "online=false", "uptime=0s", "rxbytes=4410", "txbytes=8802", "registered=false", "access=permit", "static-ip=", "expires=48m", "priority=6")
    }
    list("wifi", fields = listOf(Argument("security", ValueType.Enumeration(closed = true), SECURITY.keys.toList(), labels = SECURITY))) {
        row(".id=WifiMaster0/AccessPoint0", "name=WifiMaster0/AccessPoint0", "ssid=Demo-2.4", "band=2.4 GHz", "channel=6", "security=wpa2,wpa3", "psk=demo-password", "disabled=false", "running=true", "segment=Home network", "clients=2", "mac=00:00:5e:00:53:74", "description=Wi-Fi access point", "interface-name=AccessPoint")
        row(".id=WifiMaster1/AccessPoint0", "name=WifiMaster1/AccessPoint0", "ssid=Demo-5", "band=5 GHz", "channel=44", "security=wpa2,wpa3", "psk=demo-password", "disabled=false", "running=true", "segment=Home network", "clients=1", "mac=00:00:5e:00:53:75", "description=Wi-Fi access point", "interface-name=AccessPoint_5G")
        row(".id=WifiMaster0/AccessPoint1", "name=WifiMaster0/AccessPoint1", "ssid=Demo-Guest", "band=2.4 GHz", "channel=6", "security=wpa2", "psk=guest-password", "disabled=false", "running=true", "segment=Guest network", "clients=0", "mac=00:00:5e:00:53:76", "description=Guest access point", "interface-name=GuestWiFi")
    }
    list("wifi/radio", writable = false) {
        row("name=WifiMaster0", "band=2.4 GHz", "channel=6", "width=20", "standard=BGNAX", "temperature=51", "rate=574000000", "busy-channels=1,6,11", "access-points=2", "running=true", "load=34", "clients=2")
        row("name=WifiMaster1", "band=5 GHz", "channel=44", "width=80", "standard=ANACAX", "temperature=55", "rate=1201000000", "busy-channels=36,44", "access-points=1", "running=true", "load=9", "clients=1")
    }
    list("segments") {
        row(".id=Bridge0", "name=Bridge0", "interface-name=Home", "description=Home network", "address=192.168.1.1", "mask=255.255.255.0", "dhcp=192.168.1.33-192.168.1.152", "nat=true", "wifi=Demo-2.4, Demo-5", "members=1, 2, 3, Wi-Fi access point", "clients=4", "security-level=private", "running=true", "uptime=8d19h", "mac=00:00:5e:00:53:72")
        row(".id=Bridge1", "name=Bridge1", "interface-name=Guest", "description=Guest network", "address=192.168.2.1", "mask=255.255.255.0", "dhcp=192.168.2.33-192.168.2.152", "nat=true", "wifi=Demo-Guest", "members=Guest access point", "clients=0", "security-level=protected", "running=true", "uptime=8d19h", "mac=00:00:5e:00:53:73")
    }
    list("ip/dhcp/pool") {
        row(".id=_WEBADMIN", "name=_WEBADMIN", "interface=Bridge0", "begin=192.168.1.33", "end=192.168.1.152", "lease=25200", "disabled=false", "network=192.168.1.1/24", "state=running", "size=120", "used=4")
        row(".id=_WEBADMIN_GUEST_AP", "name=_WEBADMIN_GUEST_AP", "interface=Bridge1", "begin=192.168.2.33", "end=192.168.2.152", "lease=3600", "disabled=false", "network=192.168.2.1/24", "state=running", "size=120", "used=1")
    }
    list("show/ip/dhcp/bindings", writable = false) {
        row("ip=192.168.1.101", "mac=00:00:5e:00:53:65", "hostname=macbook-pro", "name=macbook-pro", "expires=10h31m")
        row("ip=192.168.1.102", "mac=00:00:5e:00:53:66", "hostname=iphone-anna", "name=iphone-anna", "expires=7h12m")
        row("ip=192.168.1.121", "mac=00:00:5e:00:53:7b", "hostname=esp32-sensor", "name=esp32-sensor", "expires=4h2m")
        row("ip=192.168.2.14", "mac=00:00:5e:00:53:7a", "hostname=guest-phone", "expires=48m")
    }
    list("ip/dhcp/host") {
        row(".id=00:00:5e:00:53:79", "mac=00:00:5e:00:53:79", "ip=192.168.1.10")
    }
    list("show/ip/arp", writable = false) {
        row("ip=192.168.1.101", "mac=00:00:5e:00:53:65", "interface=Bridge0")
        row("ip=192.168.1.10", "mac=00:00:5e:00:53:79", "interface=Bridge0")
        row("ip=192.168.2.14", "mac=00:00:5e:00:53:7a", "interface=Bridge1")
    }
    list("firewall", ordered = true) {
        row(".id=a588b962ab9c7ebea843b916ec1bfd91", "interface=PPPoE0", "direction=in", "acl=_WEBADMIN_PPPoE0", "position=0", "action=permit", "protocol=tcp", "source=198.51.100.0/24", "source-port=", "destination=", "destination-port=22", "description=SSH from the office", "disabled=false")
        row(".id=8a40c8d073fa7a0ba4e8ae394e3d51ae", "interface=PPPoE0", "direction=in", "acl=_WEBADMIN_PPPoE0", "position=1", "action=deny", "protocol=tcp", "source=", "source-port=", "destination=", "destination-port=22", "description=SSH from the rest", "disabled=false")
        row(".id=dc0e7d7f6d54f3f8b1717ca8ca6ed087", "interface=Bridge1", "direction=in", "acl=_WEBADMIN_Bridge1", "position=0", "action=deny", "protocol=ip", "source=", "source-port=", "destination=192.168.1.0/24", "destination-port=", "description=Guests stay out of home", "disabled=false")
    }
    list("ip/static") {
        row(".id=eb673af1d80982b51876e45559ac1753", "disabled=false", "comment=NAS", "interface=PPPoE0", "input=PPPoE0", "protocol=tcp", "mode=single", "port=8443", "to-port=443", "ports=TCP/8443 → 443", "to=_BY_IP_", "to-address=192.168.1.10", "output=192.168.1.10", "schedule=")
        row(".id=3a33a06338a936692f65d39425c94307", "disabled=false", "comment=WireGuard on the NAS", "interface=PPPoE0", "input=PPPoE0", "protocol=udp", "mode=single", "port=51820", "ports=UDP/51820", "to=_BY_IP_", "to-address=192.168.1.10", "output=192.168.1.10", "schedule=")
    }
    list("show/upnp/redirect", writable = false) {}
    list("ip/nat") {
        row(".id=Bridge0", "interface=Bridge0")
        row(".id=Bridge1", "interface=Bridge1")
    }
    list("show/ip/nat", writable = false) {
        row("protocol=TCP", "src=192.168.1.101", "sport=52344", "dst=198.51.100.20", "dport=443", "src-out=198.51.100.20", "dport-out=52344", "bytes=48211", "packets=61", "bytes-out=912044", "packets-out=702")
        row("protocol=UDP", "src=192.168.1.102", "sport=40112", "dst=9.9.9.9", "dport=53", "src-out=9.9.9.9", "dport-out=40112", "bytes=74", "packets=1", "bytes-out=90", "packets-out=1")
    }
    list("show/ip/route", writable = false) {
        row("destination=0.0.0.0/0", "gateway=0.0.0.0", "interface=PPPoE0", "metric=0", "proto=boot", "flags=U")
        row("destination=10.20.0.0/16", "gateway=0.0.0.0", "interface=Wireguard0", "metric=0", "proto=static", "flags=U")
        row("destination=192.168.1.0/24", "gateway=0.0.0.0", "interface=Bridge0", "metric=0", "proto=kernel", "flags=U")
        row("destination=192.168.2.0/24", "gateway=0.0.0.0", "interface=Bridge1", "metric=0", "proto=kernel", "flags=U")
    }
    list("ip/route") {
        row(".id=4acd6dd9b63e1c21b88214f44c2841aa", "network=10.20.0.0", "mask=255.255.0.0", "interface=Wireguard0", "comment=Office")
        row(".id=1be2562553970312ec74cd2e686171e4", "host=198.51.100.7", "gateway=203.0.113.1", "auto=true", "comment=Monitoring")
    }
    settings("show/ndns", "name=demo", "domain=keenetic.link", "access=direct", "address=203.0.113.120", "address6=", "updated=true", "ttp.direct=true", writable = false)
    settings("system", "hostname=Keenetic Demo", "domainname=WORKGROUP", "description=Keenetic Giga (KN-1011)")
    settings("system/update", "installed-version=5.1.6", "release=5.01.C.6.0-1", "channel=stable", "auto-update=true", "schedule=", "latest-version=5.1.6", "update-available=false", "progress=")
    settings("system/clock", "date=2026-10-31", "time=12:04:31", "time-zone-name=Europe/Moscow", "dst=inactive", "ntp-server=1.ntp.keenetic.ru", "ntp-synchronized=true", "ntp-accurate=true", "ntp-elapsed=3600", writable = false)
    settings(
        "system/resource",
        "hostname=Keenetic Demo", "board-name=Keenetic Giga (KN-1011)", "version=5.1.6", "architecture-name=mips", "uptime=${duration(UPTIME)}", "cpu-load=7",
        "free-memory=268435456", "total-memory=536870912",
        writable = false,
        live = { _, tick -> mapOf("uptime" to duration(UPTIME + tick), "cpu-load" to "${wander(7, tick, 4)}", "free-memory" to "${wander(268_435_456, tick, 9)}") },
    )
    list("user", fields = listOf(Argument("tag", ValueType.Enumeration(multiple = true), TAGS.keys.toList(), optional = true, labels = TAGS))) {
        row(".id=admin", "name=admin", "tag=cli,http")
        row(".id=family", "name=family", "tag=http,readonly")
    }
    list("show/ip/service", writable = false) {
        row("service-name=HTTP", "protocol=tcp", "port=80", "family=all", "security-level=private")
        row("service-name=HTTPS", "protocol=tcp", "port=443", "family=all", "security-level=public")
        row("service-name=SSH", "protocol=tcp", "port=22", "family=all", "security-level=private")
        row("service-name=Telnet", "protocol=tcp", "port=23", "family=all", "security-level=private")
    }
    list("interface", writable = false) {
        row(".id=PPPoE0", "name=PPPoE0", "interface-name=PPPoE0", "type=PPPoE", "description=Provider", "link=up", "state=up", "mtu=1492", "address=203.0.113.120", "mask=255.255.255.255", "uptime=8d19h", "rxbytes=9814006313", "txbytes=1530211907")
        row(".id=Bridge0", "name=Bridge0", "interface-name=Home", "type=Bridge", "description=Home network", "link=up", "state=up", "mac=00:00:5e:00:53:72", "mtu=1500", "address=192.168.1.1", "mask=255.255.255.0", "uptime=8d19h", "rxbytes=1840022510", "txbytes=9500431101")
        row(".id=Bridge1", "name=Bridge1", "interface-name=Guest", "type=Bridge", "description=Guest network", "link=up", "state=up", "mac=00:00:5e:00:53:73", "mtu=1500", "address=192.168.2.1", "mask=255.255.255.0", "uptime=8d19h", "rxbytes=4410", "txbytes=8802")
        row(".id=WifiMaster0/AccessPoint0", "name=WifiMaster0/AccessPoint0", "interface-name=AccessPoint", "type=AccessPoint", "description=Wi-Fi access point", "link=up", "state=up", "mac=00:00:5e:00:53:74", "mtu=1500", "ssid=Demo-2.4", "uptime=8d19h")
        row(".id=WifiMaster1/AccessPoint0", "name=WifiMaster1/AccessPoint0", "interface-name=AccessPoint_5G", "type=AccessPoint", "description=Wi-Fi access point", "link=up", "state=up", "mac=00:00:5e:00:53:75", "mtu=1500", "ssid=Demo-5", "uptime=8d19h")
        row(".id=Wireguard0", "name=Wireguard0", "interface-name=Wireguard0", "type=Wireguard", "description=Office", "link=up", "state=up", "mtu=1420", "address=10.8.0.2", "mask=255.255.255.0", "uptime=1d2h")
    }
    command("interface/monitor-traffic", text("interface"), run = ::monitorTraffic)
    list("show/ipv6/addresses", writable = false) {
        row("address=2001:db8:10::1", "prefix-length=64", "interface=Bridge0", "proto=SLAAC", "valid-lifetime=infinite")
        row("address=fe80::200:5eff:fe00:5372", "prefix-length=64", "interface=Bridge0", "proto=KERNEL", "valid-lifetime=infinite")
    }
    list("show/ipv6/prefixes", writable = false) {
        row("prefix=2001:db8:10::/48", "interface=PPPoE0", "valid-lifetime=86400", "preferred-lifetime=14400", "global=true")
    }
    list("show/log", writable = false) {
        row("time=Oct 30 22:14:40", "topics=ndm,info", "message=Core::Authenticator: user admin authorized via http/rci from 192.168.1.101.")
        row("time=Oct 31 09:00:05", "topics=ndm,info", "message=Ntp::Client: time synchronized with 1.ntp.keenetic.ru.")
        row("time=Oct 31 11:41:19", "topics=ndm,warning", "message=Wireguard::Interface: \"Wireguard0\": peer handshake timed out, trying again.")
        row("time=Oct 31 11:58:02", "topics=ndhcps,info", "message=DHCPACK of 192.168.1.101 to 00:00:5e:00:53:65.")
    }
    list("traffic", writable = false) {
        row(".id=00:00:5e:00:53:79", "name=nas", "mac=00:00:5e:00:53:79", "online=true", "rx-rate=5240000", "tx-rate=410000", "rxbytes=118400512", "txbytes=9437184")
        row(".id=00:00:5e:00:53:65", "name=macbook-pro", "mac=00:00:5e:00:53:65", "online=true", "rx-rate=1830000", "tx-rate=126000", "rxbytes=40894464", "txbytes=3145728")
        row(".id=others", "name=", "mac=", "online=false", "rx-rate=12000", "tx-rate=3000", "rxbytes=1048576", "txbytes=524288")
    }
    list("vpn", fields = listOf(Argument("type", ValueType.Enumeration(closed = true), listOf("PPTP", "L2TP", "SSTP")))) {
        row(".id=Wireguard0", "name=Wireguard0", "type=Wireguard", "description=Office", "server=vpn.example.com:51820", "user=", "connected=yes", "disabled=false", "address=10.8.0.2", "uptime=1d2h", "rxbytes=403221187", "txbytes=98126110")
        row(".id=L2TP0", "name=L2TP0", "type=L2TP", "description=Branch", "server=198.51.100.40", "user=branch", "connected=no", "disabled=true", "address=", "uptime=", "rxbytes=0", "txbytes=0")
    }
    list("ip/policy") {
        row(".id=Policy0", "name=Policy0", "description=Through the office", "multipath=false", "connections=Office, Provider")
    }
    list("wifi/acl", fields = listOf(Argument("type", ValueType.Enumeration(closed = true), listOf("none", "permit", "deny"), labels = mapOf("none" to "Not used", "permit" to "White List", "deny" to "Black List")))) {
        row(".id=WifiMaster0/AccessPoint0", "name=WifiMaster0/AccessPoint0", "ssid=Demo-2.4", "segment=Home network", "type=none", "addresses=")
        row(".id=WifiMaster0/AccessPoint1", "name=WifiMaster0/AccessPoint1", "ssid=Demo-Guest", "segment=Guest network", "type=deny", "addresses=00:00:5e:00:53:7a")
    }
    list("mws/member", writable = false) {}
    settings("rc/dns-proxy/filter", "engine=public")
    settings("rc/dns-proxy/intercept", "enable=true")
    settings("rc/oc-server", "interface=Bridge0", "pool-start=172.16.5.2", "pool-size=150", "multi-login=false", "nat=false", "enable=false")
    settings("rc/wireguard-server", "interface=Bridge0", "subnet=172.16.6.1/24", "nat=true", "enable=true")
    settings("rc/ntp", "server=1.ntp.keenetic.ru", "master=false")
    settings("rc/mws", "acl=false", "auto-ap-shutdown=false")
    keeneticServicesDemo()
}
