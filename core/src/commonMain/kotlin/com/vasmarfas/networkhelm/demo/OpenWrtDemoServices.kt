package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.ValueType
import kotlinx.coroutines.flow.emptyFlow

internal fun DemoBuilder.openWrtServicesDemo() {
    settings(
        "status/mobile",
        "current-operator=Demo Mobile", "data-class=LTE", "registration-status=Registered, home", "sim-status=Inserted", "current-cellid=4113517", "primary-band=LTE BAND 3",
        "rssi=-67", "rsrp=-95", "rsrq=-11", "sinr=12", "model=Demo LTE Cat 6", "revision=DEMO.01.000", "imei=000000000000001", "iccid=89001000000000000017",
        "imsi=001010000000001", "temperature=41.0",
        writable = false,
    )

    settings(
        "mixomo/overview",
        "mixomo-version=v1.4.2", "mihomo=running", "mihomo-version=v1.19.12", "active-config=home", "magitrickle=running", "magitrickle-version=v0.5.3", "schedules=1",
        "rule-lists=3", "routing-clients=2", "dns-servers=2", "automatic-backups=enabled",
        writable = false,
    )
    settings("mixomo/magitrickle", "status=running", "version=v0.5.3", "variant=original", "template=default", writable = false)
    list("mixomo/clients", ordered = true, fields = listOf(choice("connection-type", "redir-tproxy", "tun-socks5"))) {
        row("name=Smart TV", "source=192.168.1.40", "connection-type=redir-tproxy", "priority=1", "disabled=false")
        row("name=Laptop", "source=00:00:5e:00:53:71", "connection-type=tun-socks5", "priority=2", "disabled=false")
        row("name=Guest network", "source=192.168.30.0/24", "connection-type=redir-tproxy", "priority=3", "disabled=true")
    }
    list("mixomo/exclusions", ordered = true) {
        row("name=Bank", "destination=198.51.100.0/24", "priority=1", "disabled=false")
        row("name=Work VPN", "destination=vpn.example.com", "priority=2", "disabled=false")
    }
    settings("mixomo/routing", "router-traffic=false", "block-quic=true", "connection-type=redir-tproxy", "table=100")
    list("mixomo/profiles", fields = listOf(Argument("url", ValueType.Secret))) {
        row(".id=home", "name=home", "active=true", "url=https://sub.example.com/home/0000-demo-token", "interval=24", "updated=2026-10-31 06:00:12")
        row(".id=travel", "name=travel", "active=false", "url=https://sub.example.org/travel/0000-demo-token", "interval=12", "updated=2026-10-30 18:00:04")
    }
    command("mixomo/profiles/activate", run = { emptyFlow() })
    command("mixomo/profiles/refresh", run = { emptyFlow() })
    list("mixomo/rules", writable = false) {
        row("name=geosite-ru.mrs", "size=48211", "url=https://rules.example.com/geosite-ru.mrs", "interval=24", "updated=2026-10-31 06:00:15")
        row("name=geoip-ru.mrs", "size=91340", "url=https://rules.example.com/geoip-ru.mrs", "interval=24", "updated=2026-10-31 06:00:16")
        row("name=custom.txt", "size=812")
    }
    list("mixomo/schedule", writable = false) {
        row("name=nightly", "type=time", "profile=home", "disabled=false")
        row("name=wan-down", "type=trigger", "profile=travel", "disabled=true")
    }
    list("mixomo/dns", writable = false) {
        row("name=Cloudflare", "servers=1.1.1.1, 1.0.0.1", "active=true")
        row("name=Quad9", "servers=9.9.9.9, 149.112.112.112", "active=false")
    }
    list("mixomo/backups", writable = false) {
        row(".id=mixomo-all-20261031-030000.tar.gz", "created=2026-10-31 03:00:00", "component=all", "file=mixomo-all-20261031-030000.tar.gz")
        row(".id=mihomo-20261024-030000.tar.gz", "created=2026-10-24 03:00:00", "component=mihomo", "file=mihomo-20261024-030000.tar.gz")
    }
    command("mixomo/backups/create", run = { refusal("it makes no files") })
    command("mixomo/backups/restore", run = { refusal("it restores nothing") })

    list("ddns/service", ordered = true, fields = flags("enabled", "use_ipv6", "use_https") + Argument("password", ValueType.Secret)) {
        row(
            ".name=myddns_ipv4", "enabled=1", "service_name=cloudflare.com-v4", "lookup_host=demo-ap.example.com", "domain=demo-ap.example.com", "username=Bearer",
            "password=demo-api-token", "use_ipv6=0", "ip_source=network", "ip_network=wan", "interface=wan", "use_https=1", "check_interval=10", "check_unit=minutes",
            "force_interval=72", "force_unit=hours",
        )
        row(
            ".name=myddns_ipv6", "enabled=0", "service_name=cloudflare.com-v4", "lookup_host=demo-ap6.example.com", "domain=demo-ap6.example.com", "username=Bearer",
            "password=demo-api-token", "use_ipv6=1", "ip_source=network", "ip_network=wan6", "interface=wan6", "use_https=1", "check_interval=10", "check_unit=minutes",
        )
    }
    settings("ddns/ddns", "ddns_dateformat=%F %R", "ddns_rundir=/var/run/ddns", "ddns_logdir=/var/log/ddns", "ddns_loglines=250", "upd_privateip=0", fields = flags("upd_privateip"))

    settings(
        "pbr/pbr",
        "enabled=1", "verbosity=2", "strict_enforcement=1", "resolver_set=dnsmasq.nftset", "ipv6_enabled=0", "boot_timeout=30", "rule_create_option=add",
        "procd_reload_delay=1", "supported_interface=", "ignored_interface=vpnserver",
        fields = flags("enabled", "strict_enforcement", "ipv6_enabled"),
    )
    list("pbr/policy", ordered = true, fields = flags("enabled")) {
        row(".name=nas_wan", "name=NAS via WAN", "enabled=1", "interface=wan", "src_addr=192.168.1.20", "chain=prerouting")
        row(".name=work_vpn", "name=Work laptop via VPN", "enabled=1", "interface=wg0", "src_addr=192.168.1.113", "dest_addr=10.30.0.0/16", "chain=prerouting")
        row(".name=streaming", "name=Streaming via WAN", "enabled=0", "interface=wan", "dest_addr=media.example.com", "dest_port=443", "proto=tcp", "chain=prerouting")
    }
    list("pbr/dns_policy", ordered = true, fields = flags("enabled")) {
        row(".name=guest_dns", "name=Guests to filtered DNS", "enabled=0", "src_addr=192.168.30.0/24", "dest_dns=192.0.2.53", "dest_dns_port=53")
    }
    list("pbr/include", ordered = true, fields = flags("enabled")) {
        row(".name=aws", "path=/usr/share/pbr/pbr.user.aws", "enabled=0")
        row(".name=netflix", "path=/usr/share/pbr/pbr.user.netflix", "enabled=0")
    }

    settings(
        "upnpd/upnpd",
        "enabled=1", "enable_natpmp=1", "enable_upnp=1", "secure_mode=1", "log_output=0", "download=1024", "upload=512", "internal_iface=lan", "port=5000",
        "upnp_lease_file=/var/run/miniupnpd.leases",
        fields = flags("enabled", "enable_natpmp", "enable_upnp", "secure_mode", "log_output"),
    )
    list("upnpd/perm_rule", ordered = true) {
        row(".name=cfg03ec4a", "action=allow", "ext_ports=1024-65535", "int_addr=0.0.0.0/0", "int_ports=1024-65535", "comment=Allow high ports")
        row(".name=cfg04ec4a", "action=deny", "ext_ports=0-65535", "int_addr=0.0.0.0/0", "int_ports=0-65535", "comment=Default deny")
    }
    list("status/upnp", writable = false) {
        row("client=thinkpad", "address=192.168.1.113", "port=3478", "external-port=3478", "protocol=UDP", "expires=58m12s", "description=Game voice chat")
        row("client=macbook-pro", "address=192.168.1.101", "port=51413", "external-port=51413", "protocol=TCP", "description=Transmission at 51413")
    }

    list("luci_statistics/statistics", fields = flags("enable")) {
        row(
            ".name=collectd", "BaseDir=/var/run/collectd", "Include=/etc/collectd/conf.d", "PIDFile=/var/run/collectd.pid", "PluginDir=/usr/lib/collectd",
            "TypesDB=/usr/share/collectd/types.db", "Interval=30", "ReadThreads=2",
        )
        row(".name=rrdtool", "enable=1", "default_timespan=1hour", "image_width=600", "image_path=/tmp/rrdimg", "DataDir=/tmp/rrd", "RRATimespans=1hour 1day 1week 1month 1year")
        row(".name=collectd_cpu", "enable=1")
        row(".name=collectd_interface", "enable=1", "Interfaces=br-lan\nwan\nwg0")
        row(".name=collectd_load", "enable=1")
        row(".name=collectd_memory", "enable=1")
        row(".name=collectd_network", "enable=0")
        row(".name=collectd_ping", "enable=0", "Hosts=192.0.2.1", "Interval=30")
    }

    list("system/led", fields = flags("default")) {
        row(".name=led_wan", "name=WAN", "sysfs=green:wan", "trigger=netdev", "dev=wan", "mode=link tx rx")
        row(".name=led_wlan", "name=WLAN", "sysfs=blue:wlan", "trigger=phy1tpt")
        row(".name=led_status", "name=Status", "sysfs=green:status", "trigger=default-on", "default=1")
    }

    list("status/files", writable = false) {
        row(".id=bin", "name=bin", "type=directory", "size=4096", "last-modified=2026-09-14 19:20:01")
        row(".id=etc", "name=etc", "type=directory", "size=4096", "last-modified=2026-10-29 08:41:50")
        row(".id=etc/config", "name=etc/config", "type=directory", "size=4096", "last-modified=2026-10-29 08:41:50")
        row(".id=etc/config/network", "name=etc/config/network", "type=file", "size=1092", "last-modified=2026-10-29 08:41:50")
        row(".id=etc/config/wireless", "name=etc/config/wireless", "type=file", "size=874", "last-modified=2026-10-19 04:20:13")
        row(".id=etc/config/firewall", "name=etc/config/firewall", "type=file", "size=2391", "last-modified=2026-10-19 04:20:13")
        row(".id=overlay", "name=overlay", "type=directory", "size=4096", "last-modified=2026-09-14 19:22:10")
        row(".id=root", "name=root", "type=directory", "size=4096", "last-modified=2026-10-01 21:07:33")
        row(".id=tmp", "name=tmp", "type=directory", "size=360", "last-modified=2026-10-31 11:58:02")
        row(".id=usr", "name=usr", "type=directory", "size=4096", "last-modified=2026-09-14 19:20:01")
        row(".id=var", "name=var", "type=directory", "size=3", "last-modified=2026-09-14 19:20:01")
        row(".id=www", "name=www", "type=directory", "size=4096", "last-modified=2026-09-14 19:20:01")
        row(".id=tmp/dhcp.leases", "name=tmp/dhcp.leases", "type=file", "size=412", "last-modified=2026-10-31 11:58:02")
        row(".id=tmp/etc", "name=tmp/etc", "type=directory", "size=260", "last-modified=2026-10-19 04:18:51")
        row(".id=tmp/log", "name=tmp/log", "type=directory", "size=60", "last-modified=2026-10-19 04:18:49")
        row(".id=tmp/resolv.conf.d", "name=tmp/resolv.conf.d", "type=directory", "size=60", "last-modified=2026-10-29 08:41:50")
        row(".id=root/backup-demo-ap-2026-10-01.tar.gz", "name=root/backup-demo-ap-2026-10-01.tar.gz", "type=file", "size=24576", "last-modified=2026-10-01 21:07:33")
        row(".id=root/.ssh", "name=root/.ssh", "type=directory", "size=0", "last-modified=2026-09-14 19:22:10")
    }
    command("status/files/backup", run = { refusal("it makes no files") })
    command("status/files/upgrade", run = { refusal("it installs no firmware") })
    command("status/files/sftp", run = { refusal("it installs no packages") })
    list("status/ssh-keys") {
        row("user=root", "key-type=ssh-ed25519", "bits=256", "key-owner=admin@laptop.example.com", "key=AAAAC3NzaC1lZDI1NTE5AAAAIDEMODEMODEMODEMODEMODEMODEMODEMODEMODEMODEMO")
        row("user=root", "key-type=ssh-rsa", "bits=4096", "key-owner=backup@nas.example.com", "key=AAAAB3NzaC1yc2EAAAADAQABAAACAQDEMODEMODEMODEMODEMODEMODEMODEMODEMODEMO")
    }
    command(
        "tool/password", text("user"), Argument("new-password", ValueType.Secret), Argument("confirm-new-password", ValueType.Secret),
        run = { refusal("passwords stay as they are") },
    )
    command("tool/reboot", run = { refusal("it restarts nothing and keeps nothing") })
}
