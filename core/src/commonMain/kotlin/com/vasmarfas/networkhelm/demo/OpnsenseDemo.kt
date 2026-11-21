package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind

private val actions = choice("action", "pass", "block", "reject")

internal fun opnsenseDemo(): DemoSession = demoSession(
    DeviceInfo(Platform.Opnsense, "demo-fw.lan", "25.1.5", "amd64", "OPNsense", emptySet(), setOf("read", "write"), id = "demo-opnsense"),
    Capabilities(subscriptions = false, streamingCommands = false, cancel = false, safeMode = SafeModeKind.None, terminal = false, files = false, export = false, menuContents = false),
    ids = { "7d0c1a5e-4b2f-4c3a-8e11-" + it.toString(16).padStart(12, '0') },
) {
    list("interfaces/overview", writable = false) {
        row("description=WAN", "device=igc0", "status=up", "link_type=dhcp", "ipv4=203.0.113.80/24", "ipv6=", "gateways=WAN_DHCP", "macaddr=00:00:5e:00:53:41", "mtu=1500", "media=1000baseT <full-duplex>")
        row("description=LAN", "device=igc1", "status=up", "link_type=static", "ipv4=10.10.0.1/24", "ipv6=fd00:10::1/64", "gateways=", "macaddr=00:00:5e:00:53:42", "mtu=1500", "media=1000baseT <full-duplex>")
        row("description=IoT", "device=igc1_vlan20", "status=up", "link_type=static", "ipv4=10.10.20.1/24", "ipv6=", "gateways=", "macaddr=00:00:5e:00:53:42", "mtu=1500", "media=1000baseT <full-duplex>")
        row("description=WireGuard", "device=wg0", "status=up", "link_type=static", "ipv4=10.99.0.1/24", "ipv6=", "gateways=", "macaddr=", "mtu=1420", "media=")
    }
    list("interfaces/vlan") {
        row("vlanif=igc1_vlan20", "if=igc1", "tag=20", "pcp=0", "proto=802.1q", "descr=IoT")
    }
    list("interfaces/bridge") {
        row("deviceName=bridge0", "members=igc2,igc3", "description=Lab switch", "linklocal=0", "stp=0", "proto=rstp", "maxage=20", "fwdelay=15", "holdcnt=6", "maxaddr=2000", "timeout=1200")
    }
    list("firewall/filter", ordered = true, fields = listOf(actions, choice("direction", "in", "out"), choice("ipprotocol", "inet", "inet6", "inet46"), choice("protocol", "TCP", "UDP", "TCP/UDP", "ICMP", "any"))) {
        row("sequence=1", "action=pass", "interface=lan", "direction=in", "ipprotocol=inet", "protocol=any", "source_net=lan", "destination_net=any", "description=Default allow LAN to any rule", "disabled=false")
        row("sequence=2", "action=pass", "interface=lan", "direction=in", "ipprotocol=inet6", "protocol=any", "source_net=lan", "destination_net=any", "description=Default allow LAN IPv6 to any rule", "disabled=false")
        row("sequence=10", "action=pass", "interface=wan", "direction=in", "ipprotocol=inet", "protocol=UDP", "source_net=any", "destination_net=wan", "destination_port=51820", "description=WireGuard", "disabled=false")
        row("sequence=20", "action=pass", "interface=iot", "direction=in", "ipprotocol=inet", "protocol=UDP", "source_net=iot", "destination_net=10.10.0.1", "destination_port=53", "description=IoT DNS", "disabled=false")
        row("sequence=21", "action=block", "interface=iot", "direction=in", "ipprotocol=inet", "protocol=any", "source_net=iot", "destination_net=lan", "description=IoT may not reach LAN", "disabled=false")
        row("sequence=30", "action=pass", "interface=wan", "direction=in", "ipprotocol=inet", "protocol=TCP", "source_net=any", "destination_net=10.10.0.20", "destination_port=443", "description=NAS over HTTPS", "disabled=true")
    }
    list("firewall/source-nat", fields = listOf(choice("protocol", "TCP", "UDP", "TCP/UDP", "ICMP", "any"))) {
        row("sequence=100", "interface=wan", "ipprotocol=inet", "protocol=any", "source_net=10.10.0.0/16", "destination_net=any", "target=wanip", "description=LAN to WAN", "disabled=false")
    }
    list("firewall/alias") {
        row("name=admins", "type=host", "content=10.10.0.101\n10.10.0.102", "description=Admin workstations", "current_items=2", "last_updated=")
        row("name=web_ports", "type=port", "content=80\n443\n8443", "description=Web", "current_items=3", "last_updated=")
        row("name=blocklist", "type=url", "content=https://feodotracker.abuse.ch/downloads/ipblocklist.txt", "description=Feodo", "current_items=312", "last_updated=2026-10-31T06:00:04+03:00")
    }
    list("routes/routes") {
        row("network=10.20.0.0/16", "gateway=LAN_GW", "descr=Branch office", "disabled=false")
        row("network=192.0.2.0/24", "gateway=WAN_DHCP", "descr=Partner", "disabled=true")
    }
    list("wireguard/server") {
        row("name=roadwarrior", "interface=wg0", "port=51820", "tunneladdress=10.99.0.1/24", "pubkey=U7hQ0sVx2aKd9cLm4NpRz1WfYeT8bJgHoI6nXtMqA3E=", "peers=phone,laptop", "disabled=false")
    }
    list("wireguard/client") {
        row("name=phone", "tunneladdress=10.99.0.2/32", "serveraddress=", "serverport=", "pubkey=Fn3Yk8TzQw1LcP5xVbR7uGdHs0AeJmOi9NtXq2KhWvE=", "servers=roadwarrior", "disabled=false")
        row("name=laptop", "tunneladdress=10.99.0.3/32", "serveraddress=", "serverport=", "pubkey=Zc6Vm1RtGp4JsKo8BwXn3QyAeL0uHdIf7NxTq5MhOaE=", "servers=roadwarrior", "disabled=false")
    }
    list("services/dhcp-leases", writable = false) {
        row("address=10.10.0.101", "hwaddr=00:00:5e:00:53:65", "hostname=macbook-pro", "starts=2026/10/31 09:12:04", "ends=2026/10/31 21:12:04", "state=active", "if=igc1")
        row("address=10.10.0.102", "hwaddr=00:00:5e:00:53:66", "hostname=iphone-anna", "starts=2026/10/31 10:40:11", "ends=2026/10/31 22:40:11", "state=active", "if=igc1")
        row("address=10.10.20.14", "hwaddr=00:00:5e:00:53:79", "hostname=esp32-sensor", "starts=2026/10/31 08:00:57", "ends=2026/10/31 20:00:57", "state=active", "if=igc1_vlan20")
    }
    list("kea/subnet") {
        row(
            "subnet=10.10.0.0/24", "pools=10.10.0.100-10.10.0.199", "description=LAN", "valid_lifetime=4000", "next_server=", "match-client-id=1", "allocator=iterative",
            "option_data_autocollect=0", "option_data.routers=10.10.0.1", "option_data.domain_name_servers=10.10.0.1", "option_data.domain_search=lan",
            "option_data.domain_name=lan", "option_data.ntp_servers=10.10.0.1", "option_data.time_servers=", "option_data.static_routes=", "option_data.tftp_server_name=",
            "option_data.boot_file_name=",
        )
    }
    list("kea/reservation") {
        row("subnet=10.10.0.0/24", "ip_address=10.10.0.20", "hw_address=00:00:5e:00:53:20", "hostname=nas", "client_id=", "description=NAS")
        row("subnet=10.10.0.0/24", "ip_address=10.10.0.30", "hw_address=00:00:5e:00:53:30", "hostname=printer", "client_id=", "description=Printer")
    }
    list("services/kea-leases", writable = false) {
        row("address=10.10.0.101", "hwaddr=00:00:5e:00:53:65", "client_id=", "valid_lifetime=4000", "expire=1793439812", "subnet_id=1", "hostname=macbook-pro", "state=0", "if=igc1", "if_descr=LAN", "is_reserved=")
        row("address=10.10.0.102", "hwaddr=00:00:5e:00:53:66", "client_id=", "valid_lifetime=4000", "expire=1793440390", "subnet_id=1", "hostname=iphone-anna", "state=0", "if=igc1", "if_descr=LAN", "is_reserved=")
        row("address=10.10.0.20", "hwaddr=00:00:5e:00:53:20", "client_id=", "valid_lifetime=4000", "expire=1793438901", "subnet_id=1", "hostname=nas", "state=0", "if=igc1", "if_descr=LAN", "is_reserved=1")
    }
    list("dnsmasq/range") {
        row(
            "interface=opt1", "start_addr=10.10.20.100", "end_addr=10.10.20.199", "subnet_mask=", "prefix_len=", "constructor=", "mode=", "lease_time=86400", "domain_type=range",
            "domain=iot.lan", "set_tag=", "nosync=0", "description=IoT pool", "ra_mode=", "ra_priority=", "ra_mtu=", "ra_interval=", "ra_router_lifetime=",
        )
        row(
            "interface=lan", "start_addr=::1000", "end_addr=::1fff", "subnet_mask=", "prefix_len=64", "constructor=lan", "mode=", "lease_time=86400", "domain_type=range",
            "domain=lan", "set_tag=", "nosync=0", "description=LAN IPv6", "ra_mode=slaac", "ra_priority=", "ra_mtu=", "ra_interval=", "ra_router_lifetime=",
        )
    }
    list("dnsmasq/host") {
        row("host=esp32-sensor", "domain=iot.lan", "ip=10.10.20.14", "hwaddr=00:00:5e:00:53:79", "client_id=", "lease_time=", "ignore=0", "descr=Temperature sensor")
        row("host=camera", "domain=iot.lan", "ip=10.10.20.15", "hwaddr=00:00:5e:00:53:7c", "client_id=", "lease_time=", "ignore=1", "descr=Old camera, kept off")
    }
    list("dnsmasq/option") {
        row("type=set", "option=6", "option6=", "interface=opt1", "value=10.10.0.1", "force=0", "description=DNS for IoT")
        row("type=set", "option=42", "option6=", "interface=opt1", "value=10.10.0.1", "force=0", "description=NTP for IoT")
    }
    list("services/dnsmasq-leases", writable = false) {
        row("address=10.10.20.14", "hwaddr=00:00:5e:00:53:79", "hostname=esp32-sensor", "expire=1793480021", "client_id=01:00:00:5e:00:53:79", "if=igc1_vlan20", "if_descr=IoT", "is_reserved=1")
        row("address=10.10.20.117", "hwaddr=00:00:5e:00:53:7d", "hostname=smart-plug", "expire=1793471204", "client_id=", "if=igc1_vlan20", "if_descr=IoT", "is_reserved=")
    }
    list("unbound/host-override") {
        row("hostname=nas", "domain=lan", "rr=A", "server=10.10.0.20", "description=NAS", "enabled=1")
        row("hostname=printer", "domain=lan", "rr=A", "server=10.10.0.30", "description=Printer", "enabled=1")
    }
    list("services/services", writable = false) {
        row("name=unbound", "description=Unbound DNS", "running=1", "locked=0")
        row("name=dhcpd", "description=ISC DHCPv4 Server", "running=1", "locked=0")
        row("name=openssh", "description=Secure Shell Daemon", "running=1", "locked=0")
        row("name=wireguard", "description=WireGuard", "running=1", "locked=0")
        row("name=ntpd", "description=Network Time Daemon", "running=1", "locked=0")
    }
    list("diagnostics/arp", writable = false) {
        row("ip=10.10.0.101", "mac=00:00:5e:00:53:65", "intf_description=LAN", "hostname=macbook-pro", "manufacturer=", "expires=1186")
        row("ip=10.10.0.102", "mac=00:00:5e:00:53:66", "intf_description=LAN", "hostname=iphone-anna", "manufacturer=", "expires=912")
        row("ip=10.10.20.14", "mac=00:00:5e:00:53:79", "intf_description=IoT", "hostname=esp32-sensor", "manufacturer=", "expires=1108")
        row("ip=203.0.113.1", "mac=00:00:5e:00:53:fe", "intf_description=WAN", "hostname=", "manufacturer=", "expires=1200")
    }
    list("diagnostics/ndp", writable = false) {
        row("ip=fd00:10::65", "mac=00:00:5e:00:53:65", "intf=igc1", "intf_description=LAN", "manufacturer=")
        row("ip=fe80::200:5eff:fe00:5365%igc1", "mac=00:00:5e:00:53:65", "intf=igc1", "intf_description=LAN", "manufacturer=")
        row("ip=fe80::200:5eff:fe00:5366%igc1", "mac=00:00:5e:00:53:66", "intf=igc1", "intf_description=LAN", "manufacturer=")
        row("ip=fe80::200:5eff:fe00:53fe%igc0", "mac=00:00:5e:00:53:fe", "intf=igc0", "intf_description=WAN", "manufacturer=")
    }
    list("log/system", writable = false) {
        row("time=2026-10-31T11:58:02+03:00", "severity=Informational", "program=dhcpd", "message=DHCPACK on 10.10.0.101 to 00:00:5e:00:53:65 (macbook-pro) via igc1")
        row("time=2026-10-31T11:15:39+03:00", "severity=Notice", "program=wireguard", "message=wg0: peer Fn3Yk8TzQw1L handshake completed")
        row("time=2026-10-31T09:00:01+03:00", "severity=Informational", "program=configd.py", "message=[7a41] Reloading filter")
        row("time=2026-10-31T06:00:04+03:00", "severity=Informational", "program=filter", "message=alias blocklist refreshed: 312 entries")
        row("time=2026-10-30T22:14:40+03:00", "severity=Notice", "program=audit", "message=user root authenticated successfully via api from 10.10.0.101")
    }
    list("log/firewall", writable = false) {
        row("time=2026-10-31T11:58:14+03:00", "action=block", "interface=wan", "direction=in", "protocol=tcp", "source=198.51.100.7:55871", "destination=203.0.113.80:22", "flags=S", "rule=Default deny / state violation rule")
        row("time=2026-10-31T11:57:02+03:00", "action=block", "interface=iot", "direction=in", "protocol=tcp", "source=10.10.20.14:41822", "destination=10.10.0.20:445", "flags=S", "rule=IoT may not reach LAN")
        row("time=2026-10-31T11:41:50+03:00", "action=pass", "interface=wan", "direction=in", "protocol=udp", "source=198.51.100.77:51820", "destination=203.0.113.80:51820", "flags=", "rule=WireGuard")
    }
    settings(
        "system/resources",
        "hostname=demo-fw.lan", "version=25.1.5", "architecture=amd64", "cpu=Intel(R) Celeron(R) N5105 @ 2.00GHz (4 cores, 4 threads)", "uptime=12 days, 07:41:09",
        "datetime=Sat Oct 31 12:00:00 MSK 2026", "boottime=Mon Oct 19 04:18:51 MSK 2026", "config=Fri Oct 30 22:14:40 MSK 2026", "loadavg=0.21, 0.18, 0.15",
        "memory_used=1184301056", "memory_total=8448638976", "disks=/: 3.1G/111G (3%)\n/var/log: 21M/3.9G (1%)\n/tmp: 2.0M/3.9G (0%)",
        writable = false,
    )
    settings(
        "system/firmware",
        "product_name=OPNsense", "product_version=25.1.5", "product_latest=25.1.12", "product_series=25.1", "product_nickname=Ultimate Unicorn", "product_abi=25.1",
        "product_arch=amd64", "product_repos=OPNsense", "product_mirror=https://pkg.opnsense.org/FreeBSD:14:amd64/25.1", "product_time=Wed Apr 2 10:22:18 UTC 2025",
        "status=update", "status_msg=There are 21 updates available, total download size is 96.4MiB. This update requires a reboot.", "last_check=Sat Oct 31 06:00:12 MSK 2026",
        "updates=21", "download_size=96.4MiB", "needs_reboot=1",
        writable = false,
    )
    list("system/gateways", writable = false) {
        row(
            "name=WAN_DHCP", "interface_descr=WAN", "ipprotocol=inet", "gateway=203.0.113.1", "status=Online", "delay=2.4 ms", "stddev=0.3 ms", "loss=0.0 %", "priority=254",
            "weight=1", "defaultgw=true", "descr=Interface WAN Gateway", "disabled=false",
        )
        row(
            "name=WAN_DHCP6", "interface_descr=WAN", "ipprotocol=inet6", "gateway=fe80::200:5eff:fe00:53fe", "status=Online", "delay=2.9 ms", "stddev=0.4 ms", "loss=0.0 %",
            "priority=254", "weight=1", "defaultgw=true", "descr=Interface WAN Gateway", "disabled=false",
        )
        row(
            "name=LAN_GW", "interface_descr=LAN", "ipprotocol=inet", "gateway=10.10.0.254", "status=Online", "delay=0.6 ms", "stddev=0.1 ms", "loss=0.0 %", "priority=255",
            "weight=1", "defaultgw=false", "descr=Branch office router", "disabled=false",
        )
    }
    list("system/cron") {
        row("minutes=0", "hours=3", "days=*", "months=*", "weekdays=*", "who=root", "command=firmware poll", "parameters=", "description=Firmware update check", "origin=cron", "disabled=false")
        row("minutes=0", "hours=6", "days=*", "months=*", "weekdays=*", "who=root", "command=filter refresh_aliases", "parameters=", "description=Refresh the URL aliases", "origin=cron", "disabled=false")
        row("minutes=30", "hours=4", "days=*", "months=*", "weekdays=0", "who=root", "command=system remote backup", "parameters=", "description=Configuration backup", "origin=cron", "disabled=true")
    }
    list("system/tunables") {
        row("tunable=net.inet.tcp.blackhole", "value=2", "default_value=0", "type=runtime", "descr=Drop packets to closed TCP ports without returning a RST")
        row("tunable=net.inet.udp.blackhole", "value=1", "default_value=0", "type=runtime", "descr=Do not send ICMP port unreachable messages for closed UDP ports")
        row("tunable=net.inet.ip.random_id", "value=default", "default_value=1", "type=runtime", "descr=Randomize the ID field in IP packets")
        row("tunable=vm.pmap.pti", "value=0", "default_value=1", "type=boot", "descr=Page Table Isolation")
    }
    list("system/users") {
        row(
            "name=root", "descr=System Administrator", "group_memberships=1999", "%group_memberships=admins", "email=", "scope=system", "shell=/bin/csh", "uid=0",
            "language=", "landing_page=", "expires=", "comment=", "disabled=false",
        )
        row(
            "name=backup", "descr=Backup user", "group_memberships=", "%group_memberships=", "email=backup@example.com", "scope=user", "shell=/usr/sbin/nologin", "uid=2000",
            "language=", "landing_page=", "expires=", "comment=Pulls the configuration over the API", "disabled=false",
        )
        row(
            "name=viewer", "descr=Read only", "group_memberships=2000", "%group_memberships=viewers", "email=viewer@example.com", "scope=user", "shell=/usr/sbin/nologin",
            "uid=2001", "language=", "landing_page=ui/core/dashboard", "expires=12/31/2026", "comment=", "disabled=false",
        )
    }
    list("system/groups") {
        row("name=admins", "description=System Administrators", "member=0", "%member=root", "priv=page-all", "%priv=All pages", "scope=system", "gid=1999")
        row("name=viewers", "description=Dashboard only", "member=2001", "%member=viewer", "priv=page-dashboard-all", "%priv=Dashboard (all)", "scope=user", "gid=2000")
    }
    list("system/certificates", writable = false) {
        row(
            "descr=Demo internal CA", "name=/CN=Demo Internal CA/O=Example", "caref=", "rfc3280_purpose=", "valid_from=2026-01-12 09:14:02", "valid_to=2036-01-10 09:14:02",
            "in_use=1",
        )
        row(
            "descr=Web GUI TLS certificate", "name=/CN=demo-fw.lan", "caref=65a0f3e2b7c41", "rfc3280_purpose=Server Certificate", "valid_from=2026-01-12 09:15:40",
            "valid_to=2027-02-14 09:15:40", "in_use=1",
        )
        row(
            "descr=Old VPN server", "name=/CN=vpn.example.com", "caref=65a0f3e2b7c41", "rfc3280_purpose=Server Certificate", "valid_from=2024-11-02 18:40:11",
            "valid_to=2025-12-07 18:40:11", "in_use=0",
        )
    }
}
