package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.model.duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

private const val UPTIME = 23 * 86_400L + 6 * 3_600L + 41 * 60L

internal fun linuxDemo(): DemoSession = demoSession(
    DeviceInfo(Platform.Ssh, "demo-server", "Linux 6.8.12-4-pve", "x86_64", "", emptySet(), setOf("read", "write"), id = "demo-linux"),
    Capabilities(subscriptions = false, streamingCommands = true, cancel = false, safeMode = SafeModeKind.None, terminal = false, files = false, export = false, menuContents = false),
) {
    linuxSystemDemo()
    linuxNetworkDemo()
    linuxStorageDemo()
    linuxFirewallDemo()
    linuxLogDemo()
    linuxVpnDemo()
    linuxProxyDemo()
    linuxContainersDemo()
    linuxProxmoxDemo()
}

internal fun linuxAnswer(vararg fields: String): Flow<Item> = flowOf(Item("", fields.associate { it.substringBefore('=') to it.substringAfter('=') }))

// counters that go on from the value of the row, each row at a pace of its own
internal fun linuxGrowing(vararg rates: Pair<String, Long>): Live = { row, tick ->
    val pace = row.id.hashCode().mod(5) + 1
    rates.mapNotNull { (key, rate) -> row[key]?.toLongOrNull()?.let { key to "${it + tick * rate * pace}" } }.toMap()
}

private fun DemoBuilder.linuxSystemDemo() {
    settings(
        "status",
        "hostname=demo-server", "version=Debian GNU/Linux 12 (bookworm)", "kernel=6.8.12-4-pve", "architecture-name=x86_64", "virtualization=",
        "cpu-model=Intel(R) N100", "board-name=Mini PC N100", "cpu-count=4", "cpu-load=12", "load=0.42 0.51 0.48", "uptime=${duration(UPTIME)}",
        "time=2026-11-19 12:04:31", "total-memory=16694808576", "free-memory=7311892480", "total-swap=8589930496", "free-swap=8380215296",
        "gateway=192.168.10.1", "gateway-interface=vmbr0", "dns-servers=192.168.10.1, 2001:db8:10::1",
        "net.vmbr0.addresses=192.168.10.20/24, 2001:db8:10::20/64", "net.vmbr1.addresses=10.10.10.1/24", "net.vmbr0.30.addresses=192.168.30.1/24",
        "net.docker0.addresses=172.17.0.1/16", "net.wg0.addresses=10.8.0.1/24", "net.tun0.addresses=10.9.0.1/24", "net.tailscale0.addresses=100.101.102.103/32",
        "disk.0.mount=/", "disk.0.device=/dev/mapper/pve-root", "disk.0.type=ext4", "disk.0.size=105089261568", "disk.0.used=41231876096", "disk.0.free=58466525184",
        "disk.1.mount=/boot/efi", "disk.1.device=/dev/nvme0n1p2", "disk.1.type=vfat", "disk.1.size=1071624192", "disk.1.used=9330688", "disk.1.free=1062293504",
        "disk.2.mount=/srv/data", "disk.2.device=/dev/sda1", "disk.2.type=ext4", "disk.2.size=1967317852160", "disk.2.used=1330491723776", "disk.2.free=536802328576",
        "disk.3.mount=/mnt/backup", "disk.3.device=192.168.10.5:/volume1/backup", "disk.3.type=nfs4", "disk.3.size=3936818806784", "disk.3.used=2214592512000", "disk.3.free=1722226294784",
        "failed=rsync-offsite", "running-services=41", "users=admin", "listening=27", "firewall=ufw", "reboot-required=true",
        "top-cpu.0.pid=2214", "top-cpu.0.user=root", "top-cpu.0.cpu=7.9", "top-cpu.0.memory=2251177984", "top-cpu.0.name=kvm",
        "top-cpu.1.pid=3120", "top-cpu.1.user=root", "top-cpu.1.cpu=4.2", "top-cpu.1.memory=612401152", "top-cpu.1.name=jellyfin",
        "top-cpu.2.pid=1210", "top-cpu.2.user=root", "top-cpu.2.cpu=1.1", "top-cpu.2.memory=98766848", "top-cpu.2.name=dockerd",
        "top-cpu.3.pid=1688", "top-cpu.3.user=www-data", "top-cpu.3.cpu=0.6", "top-cpu.3.memory=167641088", "top-cpu.3.name=pveproxy worker",
        "top-cpu.4.pid=1102", "top-cpu.4.user=root", "top-cpu.4.cpu=0.4", "top-cpu.4.memory=52137984", "top-cpu.4.name=tailscaled",
        "top-memory.0.pid=2214", "top-memory.0.user=root", "top-memory.0.cpu=7.9", "top-memory.0.memory=2251177984", "top-memory.0.name=kvm",
        "top-memory.1.pid=2330", "top-memory.1.user=root", "top-memory.1.cpu=0.3", "top-memory.1.memory=1101004800", "top-memory.1.name=kvm",
        "top-memory.2.pid=3120", "top-memory.2.user=root", "top-memory.2.cpu=4.2", "top-memory.2.memory=612401152", "top-memory.2.name=jellyfin",
        "top-memory.3.pid=3388", "top-memory.3.user=999", "top-memory.3.cpu=0.2", "top-memory.3.memory=301989888", "top-memory.3.name=postgres",
        "top-memory.4.pid=1688", "top-memory.4.user=www-data", "top-memory.4.cpu=0.6", "top-memory.4.memory=167641088", "top-memory.4.name=pveproxy worker",
        "temperature.0.zone=acpitz", "temperature.0.celsius=41", "temperature.1.zone=x86_pkg_temp", "temperature.1.celsius=47",
        writable = false,
        live = { _, tick ->
            val now = clock()
            mapOf(
                "uptime" to duration(UPTIME + tick), "cpu-load" to "${wander(12, tick, 2)}", "free-memory" to "${7_311_892_480 + wander(40_000_000, tick, 5)}",
                "load" to "0.${wander(42, tick, 1)} 0.51 0.48", "time" to "${now["date"]} ${now["time"]}",
            )
        },
    )
    settings(
        "system/resource",
        "uptime=${duration(UPTIME)}", "load=0.42 0.51 0.48", "cpu-load=12", "cpu-count=4", "total-memory=16694808576", "free-memory=7311892480",
        "total-swap=8589930496", "free-swap=8380215296", "version=Debian GNU/Linux 12 (bookworm)", "architecture-name=x86_64", "board-name=Mini PC N100",
        writable = false,
        live = { _, tick ->
            mapOf("uptime" to duration(UPTIME + tick), "cpu-load" to "${wander(12, tick, 2)}", "free-memory" to "${7_311_892_480 + wander(40_000_000, tick, 5)}", "load" to "0.${wander(42, tick, 1)} 0.51 0.48")
        },
    )
    list("system/process", writable = false, live = { row, tick -> row["cpu"]?.toDoubleOrNull()?.takeIf { it >= 1 }?.let { mapOf("cpu" to "${wander((it * 10).toLong(), tick, row.id.hashCode()) / 10.0}") }.orEmpty() }) {
        row(".id=1", "pid=1", "user=root", "cpu=0.0", "memory=13107200", "stat=Ss", "command=/sbin/init")
        row(".id=812", "pid=812", "user=root", "cpu=0.0", "memory=9437184", "stat=Ss", "command=sshd: /usr/sbin/sshd -D [listener] 0 of 10-100 startups")
        row(".id=1102", "pid=1102", "user=root", "cpu=0.4", "memory=52137984", "stat=Ssl", "command=/usr/sbin/tailscaled --state=/var/lib/tailscale/tailscaled.state --socket=/run/tailscale/tailscaled.sock --port=41641")
        row(".id=1120", "pid=1120", "user=zerotier-one", "cpu=0.1", "memory=11534336", "stat=Ssl", "command=/usr/sbin/zerotier-one")
        row(".id=1180", "pid=1180", "user=root", "cpu=0.2", "memory=48234496", "stat=Ssl", "command=/usr/bin/containerd")
        row(".id=1210", "pid=1210", "user=root", "cpu=1.1", "memory=98766848", "stat=Ssl", "command=/usr/sbin/dockerd -H fd:// --containerd=/run/containerd/containerd.sock")
        row(".id=1310", "pid=1310", "user=root", "cpu=0.0", "memory=17825792", "stat=Ssl", "command=/usr/lib/ipsec/charon")
        row(".id=1402", "pid=1402", "user=nobody", "cpu=0.1", "memory=10485760", "stat=Ss", "command=/usr/sbin/openvpn --status /run/openvpn-server/status-server.log --status-version 2 --suppress-timestamps --config server.conf")
        row(".id=1650", "pid=1650", "user=root", "cpu=0.0", "memory=151519232", "stat=Ss", "command=pvedaemon")
        row(".id=1688", "pid=1688", "user=www-data", "cpu=0.6", "memory=167641088", "stat=S", "command=pveproxy worker")
        row(".id=1910", "pid=1910", "user=proxy", "cpu=0.1", "memory=40894464", "stat=Ss", "command=(squid-1) --kid squid-1 --foreground -sYC")
        row(".id=1960", "pid=1960", "user=root", "cpu=0.3", "memory=36700160", "stat=Ssl", "command=/usr/bin/sing-box -D /var/lib/sing-box -C /etc/sing-box run")
        row(".id=1970", "pid=1970", "user=nobody", "cpu=0.2", "memory=31457280", "stat=Ssl", "command=/usr/local/bin/xray run -config /usr/local/etc/xray/config.json")
        row(".id=2214", "pid=2214", "user=root", "cpu=7.9", "memory=2251177984", "stat=Sl", "command=/usr/bin/kvm -id 100 -name homeassistant,debug-threads=on -no-shutdown -smp 2,sockets=1,cores=2,maxcpus=2 -m 2048")
        row(".id=2330", "pid=2330", "user=root", "cpu=0.3", "memory=1101004800", "stat=Sl", "command=/usr/bin/kvm -id 102 -name pbs,debug-threads=on -no-shutdown -smp 2,sockets=1,cores=2,maxcpus=2 -m 1024")
        row(".id=2401", "pid=2401", "user=root", "cpu=0.0", "memory=4194304", "stat=Ss", "command=/usr/bin/lxc-start -F -n 101")
        row(".id=3120", "pid=3120", "user=root", "cpu=4.2", "memory=612401152", "stat=Ssl", "command=/jellyfin/jellyfin --datadir /config --cachedir /cache --ffmpeg /usr/lib/jellyfin-ffmpeg/ffmpeg")
        row(".id=3388", "pid=3388", "user=999", "cpu=0.2", "memory=301989888", "stat=Ss", "command=postgres: nextcloud nextcloud 172.18.0.4(51822) idle")
        row(".id=4410", "pid=4410", "user=admin", "cpu=0.0", "memory=5242880", "stat=Ss", "command=-bash")
    }
    list("system/service", writable = false) {
        row(".id=ssh.service", "name=ssh", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=OpenBSD Secure Shell server")
        row(".id=cron.service", "name=cron", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=Regular background program processing daemon")
        row(".id=docker.service", "name=docker", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=Docker Application Container Engine")
        row(".id=containerd.service", "name=containerd", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=containerd container runtime")
        row(".id=pveproxy.service", "name=pveproxy", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=PVE API Proxy Server")
        row(".id=pvedaemon.service", "name=pvedaemon", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=PVE API Daemon")
        row(".id=pve-cluster.service", "name=pve-cluster", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=The Proxmox VE cluster filesystem")
        row(".id=wg-quick@wg0.service", "name=wg-quick@wg0", "enabled=enabled", "load=loaded", "status=active (exited)", "running=false", "disabled=false", "description=WireGuard via wg-quick(8) for wg0")
        row(".id=openvpn-server@server.service", "name=openvpn-server@server", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=OpenVPN service for server")
        row(".id=strongswan.service", "name=strongswan", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=strongSwan IPsec IKEv1/IKEv2 daemon using swanctl")
        row(".id=xl2tpd.service", "name=xl2tpd", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=Layer 2 Tunneling Protocol Daemon (L2TP)")
        row(".id=pptpd.service", "name=pptpd", "enabled=disabled", "load=loaded", "status=inactive (dead)", "running=false", "disabled=false", "description=PoPToP Point to Point Tunneling Server")
        row(".id=ocserv.service", "name=ocserv", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=OpenConnect SSL VPN server")
        row(".id=tailscaled.service", "name=tailscaled", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=Tailscale node agent")
        row(".id=zerotier-one.service", "name=zerotier-one", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=ZeroTier One")
        row(".id=squid.service", "name=squid", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=Squid Web Proxy Server")
        row(".id=tinyproxy.service", "name=tinyproxy", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=Tinyproxy lightweight HTTP Proxy")
        row(".id=danted.service", "name=danted", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=SOCKS (v4 and v5) proxy daemon (danted)")
        row(".id=3proxy.service", "name=3proxy", "enabled=disabled", "load=loaded", "status=inactive (dead)", "running=false", "disabled=false", "description=3proxy tiny proxy server")
        row(".id=shadowsocks-libev.service", "name=shadowsocks-libev", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=Shadowsocks-libev Default Server Service")
        row(".id=sing-box.service", "name=sing-box", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=sing-box service")
        row(".id=xray.service", "name=xray", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=Xray Service")
        row(".id=smartd.service", "name=smartd", "enabled=enabled", "load=loaded", "status=active (running)", "running=true", "disabled=false", "description=Self Monitoring and Reporting Technology (SMART) Daemon")
        row(".id=ufw.service", "name=ufw", "enabled=enabled", "load=loaded", "status=active (exited)", "running=false", "disabled=false", "description=Uncomplicated firewall")
        row(".id=rsync-offsite.service", "name=rsync-offsite", "enabled=static", "load=loaded", "status=failed (failed)", "running=false", "disabled=false", "description=Copy the backups to the offsite store")
        row(".id=apt-daily.service", "name=apt-daily", "enabled=static", "load=loaded", "status=inactive (dead)", "running=false", "disabled=false", "description=Daily apt download activities")
        row(".id=nftables.service", "name=nftables", "enabled=disabled", "load=", "status=inactive (dead)", "running=false", "disabled=false", "description=")
        row(".id=bluetooth.service", "name=bluetooth", "enabled=masked", "load=masked", "status=inactive (dead)", "running=false", "disabled=true", "description=bluetooth.service")
    }
    list("system/package", writable = false) {
        row(".id=bash", "name=bash", "version=5.2.15-2+b7", "architecture=amd64", "size=7356416", "description=GNU Bourne Again SHell")
        row(".id=docker.io", "name=docker.io", "version=20.10.24+dfsg1-1+deb12u1", "architecture=amd64", "size=150287360", "description=Linux container runtime")
        row(".id=openssh-server", "name=openssh-server", "version=1:9.2p1-2+deb12u3", "architecture=amd64", "size=1995776", "description=secure shell (SSH) server, for secure access from remote machines")
        row(".id=openvpn", "name=openvpn", "version=2.6.3-1+deb12u2", "architecture=amd64", "size=1659904", "description=virtual private network daemon")
        row(".id=proxmox-ve", "name=proxmox-ve", "version=8.2.0", "architecture=all", "size=27648", "description=Proxmox Virtual Environment")
        row(".id=pve-manager", "name=pve-manager", "version=8.2.7", "architecture=amd64", "size=9547776", "description=Proxmox Virtual Environment Management Tools")
        row(".id=squid", "name=squid", "version=5.7-2+deb12u2", "architecture=amd64", "size=10715136", "description=Full featured Web Proxy cache (HTTP proxy)")
        row(".id=strongswan-swanctl", "name=strongswan-swanctl", "version=5.9.8-5+deb12u1", "architecture=amd64", "size=335872", "description=strongSwan IPsec client, swanctl command")
        row(".id=tailscale", "name=tailscale", "version=1.76.6", "architecture=amd64", "size=49283072", "description=The easiest, most secure, cross platform way to use WireGuard + oauth2 + 2FA/SSO")
        row(".id=ufw", "name=ufw", "version=0.36.2-1", "architecture=all", "size=880640", "description=program for managing a Netfilter firewall")
        row(".id=wireguard-tools", "name=wireguard-tools", "version=1.0.20210914-1+b1", "architecture=amd64", "size=344064", "description=fast, modern, secure kernel VPN tunnel (userland utilities)")
        row(".id=zerotier-one", "name=zerotier-one", "version=1.14.2", "architecture=amd64", "size=11943936", "description=ZeroTier network virtualization service")
    }
    list("system/package/update", writable = false) {
        row(".id=openssl", "name=openssl", "version=3.0.14-1~deb12u2", "new-version=3.0.15-1~deb12u1", "architecture=amd64", "repository=bookworm-security")
        row(".id=libssl3", "name=libssl3", "version=3.0.14-1~deb12u2", "new-version=3.0.15-1~deb12u1", "architecture=amd64", "repository=bookworm-security")
        row(".id=tzdata", "name=tzdata", "version=2024a-0+deb12u1", "new-version=2024b-0+deb12u1", "architecture=all", "repository=bookworm-updates")
        row(".id=pve-manager", "name=pve-manager", "version=8.2.7", "new-version=8.2.8", "architecture=amd64", "repository=pve-no-subscription")
    }
    command("system/package/refresh", run = {
        linuxAnswer("output=Hit:1 http://deb.debian.org/debian bookworm InRelease\nGet:2 http://security.debian.org/debian-security bookworm-security InRelease [48.0 kB]\nHit:3 http://download.proxmox.com/debian/pve bookworm InRelease\nFetched 48.0 kB in 1s (52.4 kB/s)\nReading package lists...")
    })
    command("system/package/update/upgrade", run = {
        linuxAnswer("output=Reading package lists...\nThe following packages will be upgraded:\n  libssl3 openssl\n2 upgraded, 0 newly installed, 0 to remove and 2 not upgraded.\nThis is a demo host: nothing was installed.")
    })
    command("system/package/update/upgrade-all", run = {
        linuxAnswer("output=Reading package lists...\nThe following packages will be upgraded:\n  libssl3 openssl pve-manager tzdata\n4 upgraded, 0 newly installed, 0 to remove and 0 not upgraded.\nThis is a demo host: nothing was installed.")
    })
    list("file", writable = false) {
        row(".id=/home/admin", "name=/home/admin", "type=directory")
        row(".id=/home/admin/compose", "name=/home/admin/compose", "type=directory", "size=4096", "last-modified=2026-10-02 18:40:12")
        row(".id=/home/admin/notes.md", "name=/home/admin/notes.md", "type=file", "size=2214", "last-modified=2026-11-12 21:03:44")
        row(".id=/home/admin/backup.sh", "name=/home/admin/backup.sh", "type=file", "size=1180", "last-modified=2026-09-28 10:15:09")
        row(".id=/home/admin/wg0-phone.conf", "name=/home/admin/wg0-phone.conf", "type=file", "size=342", "last-modified=2026-11-03 19:22:51")
    }
}

private fun DemoBuilder.linuxNetworkDemo() {
    list("interface", live = linuxGrowing("rx-byte" to 1_480_000L, "tx-byte" to 310_000L, "rx-packet" to 1_100L, "tx-packet" to 420L)) {
        row(".id=1", "name=lo", "type=loopback", "mtu=65536", "mac-address=", "running=true", "disabled=false", "comment=", "rx-byte=8841201733", "tx-byte=8841201733", "rx-packet=21004417", "tx-packet=21004417", "rx-drop=0", "tx-drop=0", "rx-error=0", "tx-error=0")
        row(".id=2", "name=enp1s0", "type=ether", "mtu=1500", "mac-address=00:00:5E:00:53:20", "running=true", "disabled=false", "comment=", "master=vmbr0", "rx-byte=1846002281941", "tx-byte=412339807321", "rx-packet=1622018833", "tx-packet=891204517", "rx-drop=212", "tx-drop=0", "rx-error=0", "tx-error=0")
        row(".id=3", "name=enp2s0", "type=ether", "mtu=1500", "mac-address=00:00:5E:00:53:21", "running=false", "disabled=false", "comment=", "rx-byte=0", "tx-byte=0", "rx-packet=0", "tx-packet=0", "rx-drop=0", "tx-drop=0", "rx-error=0", "tx-error=0")
        row(".id=4", "name=vmbr0", "type=bridge", "mtu=1500", "mac-address=00:00:5E:00:53:20", "running=true", "disabled=false", "comment=LAN", "rx-byte=1790114503372", "tx-byte=402118833014", "rx-packet=1588340021", "tx-packet=870011342", "rx-drop=0", "tx-drop=0", "rx-error=0", "tx-error=0")
        row(".id=5", "name=vmbr1", "type=bridge", "mtu=1500", "mac-address=00:00:5E:00:53:22", "running=true", "disabled=false", "comment=guests behind NAT", "rx-byte=9120334871", "tx-byte=48211904433", "rx-packet=10233411", "tx-packet=37200981", "rx-drop=0", "tx-drop=0", "rx-error=0", "tx-error=0")
        row(".id=6", "name=vmbr0.30", "type=vlan", "mtu=1500", "mac-address=00:00:5E:00:53:20", "running=true", "disabled=false", "comment=IoT", "parent=vmbr0", "rx-byte=211830044", "tx-byte=98112035", "rx-packet=1880412", "tx-packet=902117", "rx-drop=0", "tx-drop=0", "rx-error=0", "tx-error=0")
        row(".id=7", "name=docker0", "type=bridge", "mtu=1500", "mac-address=00:00:5E:00:53:30", "running=true", "disabled=false", "comment=", "rx-byte=3301228840", "tx-byte=41290033117", "rx-packet=4120554", "tx-packet=30881205", "rx-drop=0", "tx-drop=0", "rx-error=0", "tx-error=0")
        row(".id=8", "name=br-7c1e0a9f3b42", "type=bridge", "mtu=1500", "mac-address=00:00:5E:00:53:31", "running=true", "disabled=false", "comment=", "rx-byte=15520117236", "tx-byte=251004331987", "rx-packet=21003877", "tx-packet=180334102", "rx-drop=0", "tx-drop=0", "rx-error=0", "tx-error=0")
        row(".id=9", "name=wg0", "type=wg", "mtu=1420", "mac-address=", "running=true", "disabled=false", "comment=", "rx-byte=6120334871", "tx-byte=38211004433", "rx-packet=9021877", "tx-packet=29100433", "rx-drop=0", "tx-drop=0", "rx-error=0", "tx-error=0")
        row(".id=10", "name=tun0", "type=tun", "mtu=1500", "mac-address=", "running=true", "disabled=false", "comment=", "rx-byte=1204331870", "tx-byte=9877102218", "rx-packet=2210334", "tx-packet=8102447", "rx-drop=0", "tx-drop=0", "rx-error=0", "tx-error=0")
        row(".id=11", "name=tailscale0", "type=tun", "mtu=1280", "mac-address=", "running=true", "disabled=false", "comment=", "rx-byte=884120331", "tx-byte=2210448871", "rx-packet=1102334", "tx-packet=2440871", "rx-drop=0", "tx-drop=0", "rx-error=0", "tx-error=0")
        row(".id=12", "name=ztr2qsmswx", "type=tun", "mtu=2800", "mac-address=", "running=true", "disabled=false", "comment=", "rx-byte=120334871", "tx-byte=98200433", "rx-packet=402117", "tx-packet=388104", "rx-drop=0", "tx-drop=0", "rx-error=0", "tx-error=0")
        row(".id=13", "name=tap100i0", "type=tun", "mtu=1500", "mac-address=00:00:5E:00:53:A0", "running=true", "disabled=false", "comment=", "master=vmbr0", "rx-byte=20334871022", "tx-byte=88120433211", "rx-packet=31002877", "tx-packet=70233104", "rx-drop=0", "tx-drop=0", "rx-error=0", "tx-error=0")
        row(".id=14", "name=veth101i0", "type=veth", "mtu=1500", "mac-address=00:00:5E:00:53:A1", "running=true", "disabled=false", "comment=", "master=vmbr0", "rx-byte=1803348710", "tx-byte=7120433211", "rx-packet=2902877", "tx-packet=6233104", "rx-drop=0", "tx-drop=0", "rx-error=0", "tx-error=0")
        row(".id=15", "name=veth3a91c2e", "type=veth", "mtu=1500", "mac-address=00:00:5E:00:53:C2", "running=true", "disabled=false", "comment=", "master=br-7c1e0a9f3b42", "rx-byte=9120334871", "tx-byte=210448871022", "rx-packet=12002334", "tx-packet=150440871", "rx-drop=0", "tx-drop=0", "rx-error=0", "tx-error=0")
    }
    command("interface/monitor-traffic", text("interface"), run = { monitorTraffic(it, "vmbr0") })
    list("interface/virtual") {
        row(".id=4", "name=vmbr0", "type=bridge", "parent=", "vlan-id=", "mtu=1500", "mac-address=00:00:5E:00:53:20", "running=true", "disabled=false", "comment=LAN")
        row(".id=5", "name=vmbr1", "type=bridge", "parent=", "vlan-id=", "mtu=1500", "mac-address=00:00:5E:00:53:22", "running=true", "disabled=false", "comment=guests behind NAT")
        row(".id=6", "name=vmbr0.30", "type=vlan", "parent=vmbr0", "vlan-id=30", "mtu=1500", "mac-address=00:00:5E:00:53:20", "running=true", "disabled=false", "comment=IoT")
        row(".id=7", "name=docker0", "type=bridge", "parent=", "vlan-id=", "mtu=1500", "mac-address=00:00:5E:00:53:30", "running=true", "disabled=false", "comment=")
        row(".id=16", "name=gre-office", "type=gre", "parent=", "vlan-id=", "local=192.168.10.20", "remote=198.51.100.40", "tunnel-id=", "mtu=1476", "mac-address=", "running=false", "disabled=true", "comment=old tunnel to the office")
    }
    list("ip/address") {
        row(".id=lo/127.0.0.1/8", "address=127.0.0.1/8", "network=127.0.0.0", "interface=lo", "scope=host", "dynamic=false")
        row(".id=vmbr0/192.168.10.20/24", "address=192.168.10.20/24", "network=192.168.10.0", "interface=vmbr0", "scope=global", "dynamic=false")
        row(".id=vmbr1/10.10.10.1/24", "address=10.10.10.1/24", "network=10.10.10.0", "interface=vmbr1", "scope=global", "dynamic=false")
        row(".id=vmbr0.30/192.168.30.1/24", "address=192.168.30.1/24", "network=192.168.30.0", "interface=vmbr0.30", "scope=global", "dynamic=false")
        row(".id=docker0/172.17.0.1/16", "address=172.17.0.1/16", "network=172.17.0.0", "interface=docker0", "scope=global", "dynamic=false")
        row(".id=br-7c1e0a9f3b42/172.18.0.1/16", "address=172.18.0.1/16", "network=172.18.0.0", "interface=br-7c1e0a9f3b42", "scope=global", "dynamic=false")
        row(".id=wg0/10.8.0.1/24", "address=10.8.0.1/24", "network=10.8.0.0", "interface=wg0", "scope=global", "dynamic=false")
        row(".id=tun0/10.9.0.1/24", "address=10.9.0.1/24", "network=10.9.0.0", "interface=tun0", "scope=global", "dynamic=false")
        row(".id=tailscale0/100.101.102.103/32", "address=100.101.102.103/32", "network=100.101.102.103", "interface=tailscale0", "scope=global", "dynamic=false")
        row(".id=ztr2qsmswx/10.147.17.20/24", "address=10.147.17.20/24", "network=10.147.17.0", "interface=ztr2qsmswx", "scope=global", "dynamic=false")
    }
    list("ipv6/address") {
        row(".id=lo/::1/128", "address=::1/128", "interface=lo", "scope=host", "dynamic=false")
        row(".id=vmbr0/2001:db8:10::20/64", "address=2001:db8:10::20/64", "interface=vmbr0", "scope=global", "dynamic=true")
        row(".id=vmbr0/fe80::200:5eff:fe00:5320/64", "address=fe80::200:5eff:fe00:5320/64", "interface=vmbr0", "scope=link", "dynamic=false")
        row(".id=wg0/fd42:42:42::1/64", "address=fd42:42:42::1/64", "interface=wg0", "scope=global", "dynamic=false")
        row(".id=tailscale0/fd7a:115c:a1e0::6f01:6667/128", "address=fd7a:115c:a1e0::6f01:6667/128", "interface=tailscale0", "scope=global", "dynamic=false")
    }
    list("ip/route") {
        row(".id=0.0.0.0/0/192.168.10.1/vmbr0/main/", "dst-address=0.0.0.0/0", "gateway=192.168.10.1", "interface=vmbr0", "protocol=boot", "metric=", "routing-table=main", "pref-src=", "type=unicast", "active=true", "connect=false", "static=true", "dynamic=false")
        row(".id=10.8.0.0/24//wg0/main/", "dst-address=10.8.0.0/24", "gateway=", "interface=wg0", "protocol=kernel", "metric=", "routing-table=main", "pref-src=10.8.0.1", "type=unicast", "active=true", "connect=true", "static=false", "dynamic=true")
        row(".id=10.9.0.0/24//tun0/main/", "dst-address=10.9.0.0/24", "gateway=", "interface=tun0", "protocol=kernel", "metric=", "routing-table=main", "pref-src=10.9.0.1", "type=unicast", "active=true", "connect=true", "static=false", "dynamic=true")
        row(".id=10.10.10.0/24//vmbr1/main/", "dst-address=10.10.10.0/24", "gateway=", "interface=vmbr1", "protocol=kernel", "metric=", "routing-table=main", "pref-src=10.10.10.1", "type=unicast", "active=true", "connect=true", "static=false", "dynamic=true")
        row(".id=10.147.17.0/24//ztr2qsmswx/main/", "dst-address=10.147.17.0/24", "gateway=", "interface=ztr2qsmswx", "protocol=kernel", "metric=", "routing-table=main", "pref-src=10.147.17.20", "type=unicast", "active=true", "connect=true", "static=false", "dynamic=true")
        row(".id=172.17.0.0/16//docker0/main/", "dst-address=172.17.0.0/16", "gateway=", "interface=docker0", "protocol=kernel", "metric=", "routing-table=main", "pref-src=172.17.0.1", "type=unicast", "active=true", "connect=true", "static=false", "dynamic=true")
        row(".id=172.18.0.0/16//br-7c1e0a9f3b42/main/", "dst-address=172.18.0.0/16", "gateway=", "interface=br-7c1e0a9f3b42", "protocol=kernel", "metric=", "routing-table=main", "pref-src=172.18.0.1", "type=unicast", "active=true", "connect=true", "static=false", "dynamic=true")
        row(".id=192.168.10.0/24//vmbr0/main/", "dst-address=192.168.10.0/24", "gateway=", "interface=vmbr0", "protocol=kernel", "metric=", "routing-table=main", "pref-src=192.168.10.20", "type=unicast", "active=true", "connect=true", "static=false", "dynamic=true")
        row(".id=192.168.30.0/24//vmbr0.30/main/", "dst-address=192.168.30.0/24", "gateway=", "interface=vmbr0.30", "protocol=kernel", "metric=", "routing-table=main", "pref-src=192.168.30.1", "type=unicast", "active=true", "connect=true", "static=false", "dynamic=true")
        row(".id=192.168.50.0/24/10.8.0.2/wg0/main/10", "dst-address=192.168.50.0/24", "gateway=10.8.0.2", "interface=wg0", "protocol=static", "metric=10", "routing-table=main", "pref-src=", "type=unicast", "active=true", "connect=false", "static=true", "dynamic=false")
        row(".id=100.88.12.4/32//tailscale0/52/", "dst-address=100.88.12.4/32", "gateway=", "interface=tailscale0", "protocol=", "metric=", "routing-table=52", "pref-src=", "type=unicast", "active=true", "connect=true", "static=false", "dynamic=true")
        row(".id=100.91.40.17/32//tailscale0/52/", "dst-address=100.91.40.17/32", "gateway=", "interface=tailscale0", "protocol=", "metric=", "routing-table=52", "pref-src=", "type=unicast", "active=true", "connect=true", "static=false", "dynamic=true")
    }
    list("ipv6/route") {
        row(".id=::/0/fe80::1/vmbr0/main/1024", "dst-address=::/0", "gateway=fe80::1", "interface=vmbr0", "protocol=ra", "metric=1024", "routing-table=main", "pref-src=", "type=unicast", "active=true", "connect=false", "static=false", "dynamic=true")
        row(".id=2001:db8:10::/64//vmbr0/main/256", "dst-address=2001:db8:10::/64", "gateway=", "interface=vmbr0", "protocol=kernel", "metric=256", "routing-table=main", "pref-src=", "type=unicast", "active=true", "connect=true", "static=false", "dynamic=true")
        row(".id=fd42:42:42::/64//wg0/main/256", "dst-address=fd42:42:42::/64", "gateway=", "interface=wg0", "protocol=kernel", "metric=256", "routing-table=main", "pref-src=", "type=unicast", "active=true", "connect=true", "static=false", "dynamic=true")
        row(".id=fe80::/64//vmbr0/main/256", "dst-address=fe80::/64", "gateway=", "interface=vmbr0", "protocol=kernel", "metric=256", "routing-table=main", "pref-src=", "type=unicast", "active=true", "connect=true", "static=false", "dynamic=true")
    }
    list("ip/arp") {
        row(".id=vmbr0/192.168.10.1", "address=192.168.10.1", "mac-address=00:00:5E:00:53:01", "interface=vmbr0", "status=reachable", "dynamic=true", "complete=true")
        row(".id=vmbr0/192.168.10.5", "address=192.168.10.5", "mac-address=00:00:5E:00:53:05", "interface=vmbr0", "status=stale", "dynamic=true", "complete=true")
        row(".id=vmbr0/192.168.10.101", "address=192.168.10.101", "mac-address=00:00:5E:00:53:65", "interface=vmbr0", "status=reachable", "dynamic=true", "complete=true")
        row(".id=vmbr0/192.168.10.7", "address=192.168.10.7", "mac-address=", "interface=vmbr0", "status=failed", "dynamic=true", "complete=false")
        row(".id=vmbr1/10.10.10.100", "address=10.10.10.100", "mac-address=00:00:5E:00:53:A2", "interface=vmbr1", "status=reachable", "dynamic=true", "complete=true")
        row(".id=br-7c1e0a9f3b42/172.18.0.3", "address=172.18.0.3", "mac-address=00:00:5E:00:53:C3", "interface=br-7c1e0a9f3b42", "status=reachable", "dynamic=true", "complete=true")
        row(".id=vmbr0.30/192.168.30.12", "address=192.168.30.12", "mac-address=00:00:5E:00:53:7B", "interface=vmbr0.30", "status=permanent", "dynamic=false", "complete=true")
    }
    list("ipv6/neighbor") {
        row(".id=vmbr0/fe80::1", "address=fe80::1", "mac-address=00:00:5E:00:53:01", "interface=vmbr0", "status=reachable", "dynamic=true", "complete=true", "router=true")
        row(".id=vmbr0/2001:db8:10::101", "address=2001:db8:10::101", "mac-address=00:00:5E:00:53:65", "interface=vmbr0", "status=stale", "dynamic=true", "complete=true")
    }
    list("ip/socket", writable = false) {
        row(".id=tcp 0.0.0.0:22 0.0.0.0:*", "protocol=tcp", "state=listen", "local-address=0.0.0.0", "local-port=22", "remote-address=0.0.0.0", "remote-port=*", "process=sshd (812)", "receive-queue=0", "send-queue=128")
        row(".id=tcp 192.168.10.20:22 192.168.10.101:51522", "protocol=tcp", "state=estab", "local-address=192.168.10.20", "local-port=22", "remote-address=192.168.10.101", "remote-port=51522", "process=sshd (4402)", "receive-queue=0", "send-queue=0")
        row(".id=tcp [::ffff:192.168.10.20]:8006 [::ffff:192.168.10.101]:52114", "protocol=tcp", "state=estab", "local-address=192.168.10.20", "local-port=8006", "remote-address=192.168.10.101", "remote-port=52114", "process=pveproxy worker (1688)", "receive-queue=0", "send-queue=0")
        row(".id=tcp 192.168.10.20:443 198.51.100.23:40112", "protocol=tcp", "state=time-wait", "local-address=192.168.10.20", "local-port=443", "remote-address=198.51.100.23", "remote-port=40112", "process=", "receive-queue=0", "send-queue=0")
        row(".id=tcp 192.168.10.20:39874 203.0.113.80:443", "protocol=tcp", "state=estab", "local-address=192.168.10.20", "local-port=39874", "remote-address=203.0.113.80", "remote-port=443", "process=tailscaled (1102)", "receive-queue=0", "send-queue=0")
        row(".id=tcp 172.18.0.1:5432 172.18.0.4:51822", "protocol=tcp", "state=estab", "local-address=172.18.0.1", "local-port=51822", "remote-address=172.18.0.4", "remote-port=5432", "process=", "receive-queue=0", "send-queue=0")
        row(".id=udp 0.0.0.0:51820 0.0.0.0:*", "protocol=udp", "state=unconn", "local-address=0.0.0.0", "local-port=51820", "remote-address=0.0.0.0", "remote-port=*", "process=", "receive-queue=0", "send-queue=0")
        row(".id=udp 192.168.10.20:41641 198.51.100.71:41641", "protocol=udp", "state=estab", "local-address=192.168.10.20", "local-port=41641", "remote-address=198.51.100.71", "remote-port=41641", "process=tailscaled (1102)", "receive-queue=0", "send-queue=0")
        row(".id=udp 0.0.0.0:1194 0.0.0.0:*", "protocol=udp", "state=unconn", "local-address=0.0.0.0", "local-port=1194", "remote-address=0.0.0.0", "remote-port=*", "process=openvpn (1402)", "receive-queue=0", "send-queue=0")
    }
    list("ip/socket/listen", writable = false) {
        row(".id=tcp 0.0.0.0:22", "protocol=tcp", "address=0.0.0.0", "port=22", "process=sshd", "pid=812", "user=root", "interface=")
        row(".id=tcp6 [::]:22", "protocol=tcp6", "address=::", "port=22", "process=sshd", "pid=812", "user=root", "interface=")
        row(".id=tcp 127.0.0.1:25", "protocol=tcp", "address=127.0.0.1", "port=25", "process=master", "pid=1290", "user=root", "interface=")
        row(".id=tcp 0.0.0.0:111", "protocol=tcp", "address=0.0.0.0", "port=111", "process=rpcbind, systemd", "pid=640, 1", "user=_rpc", "interface=")
        row(".id=tcp 127.0.0.1:85", "protocol=tcp", "address=127.0.0.1", "port=85", "process=pvedaemon", "pid=1650", "user=root", "interface=")
        row(".id=tcp6 [::]:8006", "protocol=tcp6", "address=::", "port=8006", "process=pveproxy", "pid=1688", "user=www-data", "interface=")
        row(".id=tcp 0.0.0.0:80", "protocol=tcp", "address=0.0.0.0", "port=80", "process=docker-proxy", "pid=2422", "user=root", "interface=")
        row(".id=tcp 0.0.0.0:443", "protocol=tcp", "address=0.0.0.0", "port=443", "process=docker-proxy", "pid=2410", "user=root", "interface=")
        row(".id=tcp 0.0.0.0:8096", "protocol=tcp", "address=0.0.0.0", "port=8096", "process=docker-proxy", "pid=2433", "user=root", "interface=")
        row(".id=tcp6 [::]:3128", "protocol=tcp6", "address=::", "port=3128", "process=squid", "pid=1910", "user=proxy", "interface=")
        row(".id=tcp 192.168.10.20:8888", "protocol=tcp", "address=192.168.10.20", "port=8888", "process=tinyproxy", "pid=1931", "user=tinyproxy", "interface=")
        row(".id=tcp 0.0.0.0:1080", "protocol=tcp", "address=0.0.0.0", "port=1080", "process=danted", "pid=1925", "user=root", "interface=")
        row(".id=tcp6 [::]:8388", "protocol=tcp6", "address=::", "port=8388", "process=ss-server", "pid=1950", "user=nobody", "interface=")
        row(".id=udp6 [::]:8388", "protocol=udp6", "address=::", "port=8388", "process=ss-server", "pid=1950", "user=nobody", "interface=")
        row(".id=tcp6 [::]:8443", "protocol=tcp6", "address=::", "port=8443", "process=sing-box", "pid=1960", "user=root", "interface=")
        row(".id=tcp6 [::]:9443", "protocol=tcp6", "address=::", "port=9443", "process=xray", "pid=1970", "user=nobody", "interface=")
        row(".id=tcp 0.0.0.0:4443", "protocol=tcp", "address=0.0.0.0", "port=4443", "process=ocserv-main", "pid=1380", "user=root", "interface=")
        row(".id=udp 0.0.0.0:4443", "protocol=udp", "address=0.0.0.0", "port=4443", "process=ocserv-main", "pid=1380", "user=root", "interface=")
        row(".id=udp 0.0.0.0:1194", "protocol=udp", "address=0.0.0.0", "port=1194", "process=openvpn", "pid=1402", "user=nobody", "interface=")
        row(".id=udp 0.0.0.0:51820", "protocol=udp", "address=0.0.0.0", "port=51820", "process=", "pid=", "user=", "interface=")
        row(".id=udp 0.0.0.0:51821", "protocol=udp", "address=0.0.0.0", "port=51821", "process=amneziawg-go", "pid=1415", "user=root", "interface=")
        row(".id=udp 0.0.0.0:500", "protocol=udp", "address=0.0.0.0", "port=500", "process=charon", "pid=1310", "user=root", "interface=")
        row(".id=udp 0.0.0.0:4500", "protocol=udp", "address=0.0.0.0", "port=4500", "process=charon", "pid=1310", "user=root", "interface=")
        row(".id=udp 0.0.0.0:1701", "protocol=udp", "address=0.0.0.0", "port=1701", "process=xl2tpd", "pid=1350", "user=root", "interface=")
        row(".id=udp 0.0.0.0:41641", "protocol=udp", "address=0.0.0.0", "port=41641", "process=tailscaled", "pid=1102", "user=root", "interface=")
        row(".id=udp 0.0.0.0:9993", "protocol=udp", "address=0.0.0.0", "port=9993", "process=zerotier-one", "pid=1120", "user=zerotier-one", "interface=")
        row(".id=tcp 100.101.102.103:53", "protocol=tcp", "address=100.101.102.103", "port=53", "process=tailscaled", "pid=1102", "user=root", "interface=tailscale0")
    }
}

private fun DemoBuilder.linuxStorageDemo() {
    list("system/storage") {
        row(".id=/", "mount-point=/", "device=/dev/mapper/pve-root", "type=ext4", "options=rw,relatime,errors=remount-ro", "size=105089261568", "used=41231876096", "free=58466525184", "use=39%", "read-only=false")
        row(".id=/boot/efi", "mount-point=/boot/efi", "device=/dev/nvme0n1p2", "type=vfat", "options=rw,relatime,fmask=0022,dmask=0022,codepage=437,iocharset=iso8859-1,shortname=mixed,errors=remount-ro", "size=1071624192", "used=9330688", "free=1062293504", "use=1%", "read-only=false")
        row(".id=/run", "mount-point=/run", "device=tmpfs", "type=tmpfs", "options=rw,nosuid,nodev,noexec,relatime,size=1630352k,mode=755", "size=1669480448", "used=2236416", "free=1667244032", "use=0%", "read-only=false")
        row(".id=/etc/pve", "mount-point=/etc/pve", "device=/dev/fuse", "type=fuse", "options=rw,nosuid,nodev,relatime,user_id=0,group_id=0,default_permissions,allow_other", "size=134217728", "used=32768", "free=134184960", "use=0%", "read-only=false")
        row(".id=/srv/data", "mount-point=/srv/data", "device=/dev/sda1", "type=ext4", "options=rw,noatime", "size=1967317852160", "used=1330491723776", "free=536802328576", "use=72%", "read-only=false")
        row(".id=/mnt/backup", "mount-point=/mnt/backup", "device=192.168.10.5:/volume1/backup", "type=nfs4", "options=rw,noatime,vers=4.1,rsize=131072,wsize=131072,hard,proto=tcp,timeo=600,retrans=2,sec=sys", "size=3936818806784", "used=2214592512000", "free=1722226294784", "use=57%", "read-only=false")
    }
    list("system/storage/disk", writable = false) {
        row(".id=/dev/nvme0n1", "name=nvme0n1", "path=/dev/nvme0n1", "type=disk", "size=500107862016", "model=Samsung SSD 980 500GB", "serial=S0000000000001", "transport=nvme", "rotational=false", "removable=false", "read-only=false", "file-system=", "label=", "uuid=", "mount-point=", "parent=")
        row(".id=/dev/nvme0n1p1", "name=nvme0n1p1", "path=/dev/nvme0n1p1", "type=part", "size=1031168", "model=", "serial=", "transport=nvme", "rotational=false", "removable=false", "read-only=false", "file-system=", "label=", "uuid=", "mount-point=", "parent=nvme0n1")
        row(".id=/dev/nvme0n1p2", "name=nvme0n1p2", "path=/dev/nvme0n1p2", "type=part", "size=1073741824", "model=", "serial=", "transport=nvme", "rotational=false", "removable=false", "read-only=false", "file-system=vfat", "label=", "uuid=7A1C-3F0E", "mount-point=/boot/efi", "parent=nvme0n1")
        row(".id=/dev/nvme0n1p3", "name=nvme0n1p3", "path=/dev/nvme0n1p3", "type=part", "size=499033980928", "model=", "serial=", "transport=nvme", "rotational=false", "removable=false", "read-only=false", "file-system=LVM2_member", "label=", "uuid=Xq0d1E-demo-0000-0000-0000-0000-000001", "mount-point=", "parent=nvme0n1")
        row(".id=/dev/mapper/pve-swap", "name=pve-swap", "path=/dev/mapper/pve-swap", "type=lvm", "size=8589934592", "model=", "serial=", "transport=", "rotational=false", "removable=false", "read-only=false", "file-system=swap", "label=", "uuid=5d0f0c3a-0000-4000-8000-000000000001", "mount-point=[SWAP]", "parent=nvme0n1p3")
        row(".id=/dev/mapper/pve-root", "name=pve-root", "path=/dev/mapper/pve-root", "type=lvm", "size=107374182400", "model=", "serial=", "transport=", "rotational=false", "removable=false", "read-only=false", "file-system=ext4", "label=", "uuid=5d0f0c3a-0000-4000-8000-000000000002", "mount-point=/", "parent=nvme0n1p3")
        row(".id=/dev/mapper/pve-data_tdata", "name=pve-data_tdata", "path=/dev/mapper/pve-data_tdata", "type=lvm", "size=374467461120", "model=", "serial=", "transport=", "rotational=false", "removable=false", "read-only=false", "file-system=", "label=", "uuid=", "mount-point=", "parent=nvme0n1p3")
        row(".id=/dev/sda", "name=sda", "path=/dev/sda", "type=disk", "size=2000398934016", "model=WDC WD20EFRX-68EUZN0", "serial=WD-0000000000000", "transport=sata", "rotational=true", "removable=false", "read-only=false", "file-system=", "label=", "uuid=", "mount-point=", "parent=")
        row(".id=/dev/sda1", "name=sda1", "path=/dev/sda1", "type=part", "size=2000397795328", "model=", "serial=", "transport=sata", "rotational=true", "removable=false", "read-only=false", "file-system=ext4", "label=data", "uuid=3d6f2a1e-8c4b-4f7a-9e21-5b0c7d8e9f10", "mount-point=/srv/data", "parent=sda")
    }
    list("system/storage/fstab") {
        row(".id=1", "device=/dev/pve/root", "mount-point=/", "type=ext4", "options=errors=remount-ro", "dump=0", "pass=1", "mounted=true")
        row(".id=2", "device=UUID=7A1C-3F0E", "mount-point=/boot/efi", "type=vfat", "options=defaults", "dump=0", "pass=1", "mounted=true")
        row(".id=3", "device=/dev/pve/swap", "mount-point=none", "type=swap", "options=sw", "dump=0", "pass=0")
        row(".id=4", "device=proc", "mount-point=/proc", "type=proc", "options=defaults", "dump=0", "pass=0", "mounted=true")
        row(".id=5", "device=UUID=3d6f2a1e-8c4b-4f7a-9e21-5b0c7d8e9f10", "mount-point=/srv/data", "type=ext4", "options=defaults,noatime", "dump=0", "pass=2", "mounted=true")
        row(".id=6", "device=192.168.10.5:/volume1/backup", "mount-point=/mnt/backup", "type=nfs4", "options=rw,noatime,_netdev", "dump=0", "pass=0", "mounted=true")
        row(".id=7", "device=LABEL=usb-copy", "mount-point=/mnt/usb", "type=exfat", "options=noauto,nofail", "dump=0", "pass=0", "mounted=false")
    }
    list("system/storage/smart", writable = false) {
        row(".id=/dev/nvme0n1", "device=/dev/nvme0n1", "type=nvme", "model=Samsung SSD 980 500GB", "serial=S0000000000001", "firmware=3B4QFXO7", "capacity=500107862016", "smart-enabled=true", "health=PASSED", "message=", "temperature=44", "power-on-hours=8213", "power-cycles=152", "reallocated=", "pending=", "uncorrectable=0", "wear=3", "life-left=")
        row(".id=/dev/sda", "device=/dev/sda", "type=sat", "model=WDC WD20EFRX-68EUZN0", "serial=WD-0000000000000", "firmware=82.00A82", "capacity=2000398934016", "smart-enabled=true", "health=PASSED", "message=", "temperature=36", "power-on-hours=31402", "power-cycles=88", "reallocated=0", "pending=0", "uncorrectable=0", "wear=", "life-left=")
    }
    command("system/storage/smart/short-test", run = {
        linuxAnswer("output=Sending command: \"Execute SMART Short self-test routine immediately in off-line mode\".\nDrive command \"Execute SMART Short self-test routine immediately in off-line mode\" successful.\nTesting has begun.\nPlease wait 2 minutes for test to complete.")
    })
}

private fun DemoBuilder.linuxFirewallDemo() {
    settings(
        "firewall",
        "manager=ufw", "ufw=active", "firewalld=", "nftables-rules=3", "iptables-rules=8", "ip6tables-rules=3", "iptables-backend=nf_tables",
        writable = false,
    )
    list("firewall/ufw", ordered = true) {
        row(".id=1", "number=1", "action=allow", "direction=in", "protocol=tcp", "to=any", "to-port=22", "from=192.168.10.0/24", "from-port=any", "interface=", "comment=SSH from the LAN", "v6=false")
        row(".id=2", "number=2", "action=limit", "direction=in", "protocol=tcp", "to=any", "to-port=22", "from=any", "from-port=any", "interface=", "comment=SSH from outside, rate limited", "v6=false")
        row(".id=3", "number=3", "action=allow", "direction=in", "protocol=tcp", "to=any", "to-port=80,443", "from=any", "from-port=any", "interface=", "comment=web", "v6=false")
        row(".id=4", "number=4", "action=allow", "direction=in", "protocol=udp", "to=any", "to-port=51820", "from=any", "from-port=any", "interface=", "comment=WireGuard", "v6=false")
        row(".id=5", "number=5", "action=allow", "direction=in", "protocol=udp", "to=any", "to-port=1194", "from=any", "from-port=any", "interface=", "comment=OpenVPN", "v6=false")
        row(".id=6", "number=6", "action=allow", "direction=in", "protocol=tcp", "to=any", "to-port=8006", "from=192.168.10.0/24", "from-port=any", "interface=", "comment=Proxmox web interface", "v6=false")
        row(".id=7", "number=7", "action=allow", "direction=route", "protocol=any", "to=any", "to-port=any", "from=10.8.0.0/24", "from-port=any", "interface=in wg0, out vmbr0", "comment=VPN clients to the LAN", "v6=false")
        row(".id=8", "number=8", "action=deny", "direction=in", "protocol=any", "to=any", "to-port=any", "from=203.0.113.66", "from-port=any", "interface=", "comment=scanner", "v6=false")
        row(".id=9", "number=9", "action=allow", "direction=in", "protocol=tcp", "to=any", "to-port=22", "from=2001:db8:10::/64", "from-port=any", "interface=", "comment=SSH from the LAN", "v6=true")
        row(".id=10", "number=10", "action=allow", "direction=in", "protocol=tcp", "to=any", "to-port=80,443", "from=any", "from-port=any", "interface=", "comment=web", "v6=true")
    }
    settings("firewall/ufw/settings", "enabled=yes", "default-incoming=deny", "default-outgoing=allow", "default-routed=deny", "logging=low")
    list("firewall/iptables", ordered = true) {
        row(".id=filter/INPUT/1", "table=filter", "chain=INPUT", "number=1", "rule=-j ufw-before-logging-input", "target=ufw-before-logging-input", "comment=", "packets=81120334", "bytes=60334871022")
        row(".id=filter/INPUT/2", "table=filter", "chain=INPUT", "number=2", "rule=-j ufw-before-input", "target=ufw-before-input", "comment=", "packets=81120334", "bytes=60334871022")
        row(".id=filter/FORWARD/1", "table=filter", "chain=FORWARD", "number=1", "rule=-j DOCKER-USER", "target=DOCKER-USER", "comment=", "packets=42118033", "bytes=51200433871")
        row(".id=filter/FORWARD/2", "table=filter", "chain=FORWARD", "number=2", "rule=-j DOCKER-ISOLATION-STAGE-1", "target=DOCKER-ISOLATION-STAGE-1", "comment=", "packets=42118033", "bytes=51200433871")
        row(".id=filter/FORWARD/3", "table=filter", "chain=FORWARD", "number=3", "rule=-o docker0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT", "target=ACCEPT", "comment=", "packets=30881205", "bytes=41290033117")
        row(".id=filter/DOCKER/1", "table=filter", "chain=DOCKER", "number=1", "rule=-d 172.18.0.3/32 ! -i br-7c1e0a9f3b42 -o br-7c1e0a9f3b42 -p tcp -m tcp --dport 443 -j ACCEPT", "target=ACCEPT", "comment=", "packets=1204331", "bytes=880412033")
        row(".id=nat/POSTROUTING/1", "table=nat", "chain=POSTROUTING", "number=1", "rule=-s 172.17.0.0/16 ! -o docker0 -j MASQUERADE", "target=MASQUERADE", "comment=", "packets=33120", "bytes=2010433")
        row(".id=nat/POSTROUTING/2", "table=nat", "chain=POSTROUTING", "number=2", "rule=-s 10.8.0.0/24 -o vmbr0 -m comment --comment networkhelm-wg0 -j MASQUERADE", "target=MASQUERADE", "comment=networkhelm-wg0", "packets=10233", "bytes=812044")
    }
    list("firewall/iptables/chain") {
        row(".id=filter/INPUT", "table=filter", "chain=INPUT", "policy=DROP", "rules=2", "packets=1120", "bytes=88012")
        row(".id=filter/FORWARD", "table=filter", "chain=FORWARD", "policy=DROP", "rules=3", "packets=402", "bytes=30112")
        row(".id=filter/OUTPUT", "table=filter", "chain=OUTPUT", "policy=ACCEPT", "rules=0", "packets=0", "bytes=0")
        row(".id=filter/DOCKER", "table=filter", "chain=DOCKER", "policy=", "rules=1", "packets=", "bytes=")
        row(".id=nat/POSTROUTING", "table=nat", "chain=POSTROUTING", "policy=ACCEPT", "rules=2", "packets=881204", "bytes=60120433")
    }
    list("firewall/ip6tables", ordered = true) {
        row(".id=filter/INPUT/1", "table=filter", "chain=INPUT", "number=1", "rule=-j ufw6-before-logging-input", "target=ufw6-before-logging-input", "comment=", "packets=2210334", "bytes=1880412033")
        row(".id=filter/INPUT/2", "table=filter", "chain=INPUT", "number=2", "rule=-j ufw6-before-input", "target=ufw6-before-input", "comment=", "packets=2210334", "bytes=1880412033")
        row(".id=filter/ufw6-user-input/1", "table=filter", "chain=ufw6-user-input", "number=1", "rule=-s 2001:db8:10::/64 -p tcp -m tcp --dport 22 -j ACCEPT", "target=ACCEPT", "comment=", "packets=1204", "bytes=98112")
    }
    list("firewall/ip6tables/chain") {
        row(".id=filter/INPUT", "table=filter", "chain=INPUT", "policy=DROP", "rules=2", "packets=88", "bytes=7012")
        row(".id=filter/FORWARD", "table=filter", "chain=FORWARD", "policy=DROP", "rules=0", "packets=0", "bytes=0")
        row(".id=filter/ufw6-user-input", "table=filter", "chain=ufw6-user-input", "policy=", "rules=1", "packets=", "bytes=")
    }
    list("firewall/nftables", ordered = true) {
        row(".id=ip/guests/forward/4", "family=ip", "table=guests", "chain=forward", "handle=4", "rule=ct state established,related accept", "comment=", "packets=37200981", "bytes=48211904433")
        row(".id=ip/guests/forward/5", "family=ip", "table=guests", "chain=forward", "handle=5", "rule=iifname \"vmbr1\" oifname \"vmbr0\" accept", "comment=guests out", "packets=10233411", "bytes=9120334871")
        row(".id=ip/guests/postrouting/7", "family=ip", "table=guests", "chain=postrouting", "handle=7", "rule=ip saddr 10.10.10.0/24 oifname \"vmbr0\" masquerade", "comment=", "packets=88120", "bytes=6120433")
    }
    list("firewall/nftables/chain", writable = false) {
        row(".id=ip/guests/forward", "family=ip", "table=guests", "chain=forward", "type=filter", "hook=forward", "priority=0", "policy=accept", "rules=2")
        row(".id=ip/guests/postrouting", "family=ip", "table=guests", "chain=postrouting", "type=nat", "hook=postrouting", "priority=srcnat", "policy=accept", "rules=1")
    }
}

private fun DemoBuilder.linuxLogDemo() {
    list("log", writable = false) {
        row("time=2026-11-19 11:58:02", "topics=daemon.info", "message=systemd: Starting apt-daily.service - Daily apt download activities...")
        row("time=2026-11-19 11:58:04", "topics=daemon.info", "message=systemd: apt-daily.service: Deactivated successfully.")
        row("time=2026-11-19 12:00:01", "topics=cron.info", "message=CRON: (root) CMD (/usr/local/bin/backup.sh)")
        row("time=2026-11-19 12:00:17", "topics=daemon.err", "message=rsync-offsite: rsync: [sender] failed to connect to backup.example.org (198.51.100.90): Connection timed out (110)")
        row("time=2026-11-19 12:00:17", "topics=daemon.warning", "message=systemd: rsync-offsite.service: Main process exited, code=exited, status=10/n/a")
        row("time=2026-11-19 12:01:40", "topics=daemon.info", "message=openvpn: anna-laptop/198.51.100.41:51022 MULTI_sva: pool returned IPv4=10.9.0.6")
        row("time=2026-11-19 12:02:13", "topics=daemon.info", "message=dockerd: time=\"2026-11-19T12:02:13.402Z\" level=info msg=\"ignoring event\" container=c3f0a8 module=libcontainerd")
        row("time=2026-11-19 12:03:55", "topics=authpriv.info", "message=sshd: Accepted publickey for admin from 192.168.10.101 port 51522 ssh2: ED25519 SHA256:demoKEYdemoKEYdemoKEYdemoKEYdemoKEYdemo0000")
        row("time=2026-11-19 12:04:02", "topics=daemon.info", "message=pvedaemon: <root@pam> starting task UPID:demo-server:0000F1A2:01B2C3D4:673C8E02:vzdump:101:root@pam:")
    }
    list("log/kernel", writable = false) {
        row("time=2026-10-27 05:23:11", "topics=kern.info", "message=kernel: Linux version 6.8.12-4-pve (build@proxmox) (gcc (Debian 12.2.0-14) 12.2.0) #1 SMP PREEMPT_DYNAMIC PMX 6.8.12-4")
        row("time=2026-10-27 05:23:14", "topics=kern.info", "message=kernel: wireguard: WireGuard 1.0.0 loaded. See www.wireguard.com for information.")
        row("time=2026-11-18 22:41:09", "topics=kern.warning", "message=kernel: [UFW BLOCK] IN=vmbr0 OUT= SRC=203.0.113.66 DST=192.168.10.20 PROTO=TCP SPT=48122 DPT=3389")
        row("time=2026-11-19 09:12:30", "topics=kern.info", "message=kernel: br-7c1e0a9f3b42: port 2(veth3a91c2e) entered forwarding state")
    }
    list("log/auth", writable = false) {
        row("time=2026-11-19 03:14:52", "topics=auth.info", "message=sshd: Invalid user oracle from 203.0.113.66 port 40188")
        row("time=2026-11-19 03:14:52", "topics=auth.info", "message=sshd: Connection closed by invalid user oracle 203.0.113.66 port 40188 [preauth]")
        row("time=2026-11-19 12:03:55", "topics=authpriv.info", "message=sshd: Accepted publickey for admin from 192.168.10.101 port 51522 ssh2")
        row("time=2026-11-19 12:04:01", "topics=authpriv.notice", "message=sudo: admin : TTY=pts/0 ; PWD=/home/admin ; USER=root ; COMMAND=/usr/bin/docker ps")
    }
    list("log/errors", writable = false) {
        row("time=2026-11-18 12:00:17", "topics=daemon.err", "message=rsync-offsite: rsync error: error in socket IO (code 10) at clientserver.c(139) [sender=3.2.7]")
        row("time=2026-11-19 12:00:17", "topics=daemon.err", "message=rsync-offsite: rsync: [sender] failed to connect to backup.example.org (198.51.100.90): Connection timed out (110)")
    }
    list("log/boot", writable = false) {
        row("time=2026-10-27 05:23:11", "topics=kern.notice", "message=kernel: Command line: BOOT_IMAGE=/boot/vmlinuz-6.8.12-4-pve root=/dev/mapper/pve-root ro quiet")
        row("time=2026-10-27 05:23:19", "topics=daemon.info", "message=systemd: Reached target network-online.target - Network is Online.")
        row("time=2026-10-27 05:23:24", "topics=daemon.info", "message=systemd: Started docker.service - Docker Application Container Engine.")
        row("time=2026-10-27 05:23:31", "topics=daemon.info", "message=systemd: Started pve-guests.service - PVE guests.")
    }
}
