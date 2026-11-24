package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.duration
import com.vasmarfas.networkhelm.session.local
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

// keys of the demo: a word repeated to the length of a WireGuard key
private fun vpnKey(word: String) = word.repeat(43 / word.length + 1).take(43) + "="

private val vpnPeers = mapOf(
    "wg0/${vpnKey("annaPhone")}" to "10.8.0.2/32",
    "wg0/${vpnKey("ivanLaptop")}" to "10.8.0.3/32",
    "wg0/${vpnKey("branchOffice")}" to "10.8.0.4/32",
    "wg0/${vpnKey("oldTablet")}" to "10.8.0.5/32",
    "awg0/${vpnKey("travelRouter")}" to "10.20.0.2/32",
)

// what a session sends and receives goes on each second, the server sending more than it gets
private val vpnTraffic: Live = { row, tick ->
    if (row["rx-rate"].isNullOrEmpty()) {
        emptyMap()
    } else {
        val pace = row.id.hashCode().mod(5) + 1
        mapOf(
            "rx" to "${(row["rx"]?.toLongOrNull() ?: 0L) + tick * 6_000L * pace}", "tx" to "${(row["tx"]?.toLongOrNull() ?: 0L) + tick * 48_000L * pace}",
            "rx-rate" to "${wander(48_000L * pace, tick, row.id.hashCode())}", "tx-rate" to "${wander(384_000L * pace, tick, row.id.hashCode() + 1)}",
        )
    }
}

private fun vpnField(name: String, label: String, tab: String? = null, type: ValueType = ValueType.Text(), optional: Boolean = false) =
    Argument(name, type, label = label, tab = tab, optional = optional)

private val vpnImport = listOf(
    vpnField("name", "Name"), vpnField("autostart", "Start at Boot", type = ValueType.YesNo),
    vpnField("keep-session", "Keep This Session Outside the Tunnel", type = ValueType.YesNo), vpnField("text", "Configuration", type = ValueType.Secret),
)

private val vpnWireGuardServer = listOf(
    vpnField("name", "Name", "general"), vpnField("listen-port", "Listen Port", "general", ValueType.Integer(1, 65535)),
    vpnField("client-endpoint", "Address for Clients", "general", optional = true), vpnField("autostart", "Start at Boot", "general", ValueType.YesNo),
    vpnField("address", "Address", "network"), vpnField("mtu", "MTU", "network", ValueType.Integer(576, 65535), optional = true),
    vpnField("client-dns", "DNS for Clients", "dns", optional = true), vpnField("client-allowed-ips", "Client Allowed IPs", "dns"),
    vpnField("client-keepalive", "Client Keepalive, s", "dns", ValueType.Integer(0, 65535, unit = "s"), optional = true),
    vpnField("private-key", "Private Key", "keys", ValueType.Secret, optional = true), vpnField("preshared-key", "Preshared Keys", "keys", ValueType.YesNo),
    vpnField("nat", "NAT for Clients", "nat", ValueType.YesNo), vpnField("client", "First Client", "clients", optional = true),
    vpnField("keep-key", "Keep Client Key on Host", "clients", ValueType.YesNo),
)

private val vpnDisguise = listOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4").map { vpnField(it, it.uppercase(), "disguise", ValueType.Integer(0)) }

private val vpnClients = listOf(vpnField("user", "First User", "clients", optional = true), vpnField("password", "Password", "clients", ValueType.Secret, optional = true))

internal fun DemoBuilder.linuxVpnDemo() {
    vpnPanelDemo()
    vpnWireGuardDemo()
    vpnOpenVpnDemo()
    vpnIpsecDemo()
    vpnPppDemo()
    vpnClientsDemo()
    vpnMeshDemo()
}

private fun DemoBuilder.vpnPanelDemo() {
    list("vpn/overview", writable = false) {
        row(".id=wireguard", "name=WireGuard", "installed=true", "packages=wireguard-tools")
        row(".id=amneziawg", "name=AmneziaWG", "installed=true", "packages=amneziawg-tools amneziawg-go")
        row(".id=openvpn", "name=OpenVPN", "installed=true", "packages=openvpn easy-rsa socat")
        row(".id=ipsec", "name=strongSwan", "installed=true", "packages=strongswan-swanctl charon-systemd strongswan-pki libcharon-extra-plugins libcharon-extauth-plugins libstrongswan-standard-plugins")
        row(".id=libreswan", "name=Libreswan", "installed=true", "packages=libreswan")
        row(".id=ocserv", "name=ocserv", "installed=true", "packages=ocserv")
        row(".id=l2tp", "name=L2TP/IPsec", "installed=true", "packages=xl2tpd")
        row(".id=pptp", "name=PPTP", "installed=true", "packages=pptpd")
        row(".id=pptp-client", "name=PPTP Client", "installed=true", "packages=pptp-linux")
        row(".id=openconnect", "name=OpenConnect Client", "installed=true", "packages=openconnect")
        row(".id=sstp", "name=SSTP Client", "installed=true", "packages=sstp-client")
        row(".id=tailscale", "name=Tailscale", "installed=true", "packages=tailscale")
        row(".id=zerotier", "name=ZeroTier", "installed=true", "packages=zerotier-one")
    }
    command("vpn/overview/install", run = { linuxAnswer("output=Reading package lists...\nAll the chosen packages are already installed: nothing to do.") })
    list("vpn/service", writable = false, live = vpnTraffic) {
        row(".id=wireguard/wg0", "kind=WireGuard", "name=wg0", "state=active", "port=51820", "protocol=udp", "network=10.8.0.1/24, fd42:42:42::1/64", "sessions=2", "rx=6120334871", "tx=38211004433", "rx-rate=96000", "tx-rate=768000")
        row(".id=amneziawg/awg0", "kind=AmneziaWG", "name=awg0", "state=active", "port=51821", "protocol=udp", "network=10.20.0.1/24", "sessions=1", "rx=310442871", "tx=2210448871", "rx-rate=48000", "tx-rate=384000")
        row(".id=openvpn/server", "kind=OpenVPN", "name=server", "state=active", "port=1194", "protocol=udp", "network=10.9.0.0 255.255.255.0", "sessions=1", "rx=1204331870", "tx=9877102218", "rx-rate=48000", "tx-rate=384000")
        row(".id=ipsec/ikev2-eap", "kind=strongSwan", "name=ikev2-eap", "state=active", "port=500", "protocol=udp", "network=10.30.0.0/24", "sessions=1", "rx=88120433", "tx=710334871", "rx-rate=48000", "tx-rate=384000")
        row(".id=ocserv", "kind=ocserv", "name=ocserv", "state=active", "port=4443", "protocol=tcp", "network=10.50.0.0/24", "sessions=0", "rx=", "tx=", "rx-rate=", "tx-rate=")
    }
    val now = Clock.System.now().epochSeconds
    list("vpn/session", writable = false, live = vpnTraffic) {
        row(".id=wireguard/wg0/${vpnKey("annaPhone")}", "kind=WireGuard", "service=wg0", "user=anna-phone", "public-key=${vpnKey("annaPhone")}", "endpoint=198.51.100.41:40112", "address=10.8.0.2/32", "since=${local(now - 10_311, "+0300")}", "uptime=2h51m51s", "rx=84120331", "tx=912044871", "rx-rate=48000", "tx-rate=384000", "last-handshake=41s", "cipher=", "client=", "peer=wg0/${vpnKey("annaPhone")}")
        row(".id=wireguard/wg0/${vpnKey("branchOffice")}", "kind=WireGuard", "service=wg0", "user=branch-office", "public-key=${vpnKey("branchOffice")}", "endpoint=198.51.100.90:51820", "address=10.8.0.4/32", "since=${local(now - 619_468, "+0300")}", "uptime=1w4h4m28s", "rx=5120334871", "tx=21004433112", "rx-rate=48000", "tx-rate=384000", "last-handshake=1m50s", "cipher=", "client=", "peer=wg0/${vpnKey("branchOffice")}")
        row(".id=amneziawg/awg0/${vpnKey("travelRouter")}", "kind=AmneziaWG", "service=awg0", "user=travel-router", "public-key=${vpnKey("travelRouter")}", "endpoint=203.0.113.150:39221", "address=10.20.0.2/32", "since=${local(now - 5_069, "+0300")}", "uptime=1h24m29s", "rx=31044287", "tx=221044887", "rx-rate=48000", "tx-rate=384000", "last-handshake=1m3s", "cipher=", "client=", "peer=awg0/${vpnKey("travelRouter")}")
        row(".id=openvpn/server/3", "kind=OpenVPN", "service=server", "user=anna-laptop", "public-key=", "endpoint=198.51.100.41:51022", "address=10.9.0.6", "since=${local(now - 171, "+0300")}", "uptime=2m51s", "rx=1203318", "tx=9877102", "rx-rate=48000", "tx-rate=384000", "last-handshake=", "cipher=AES-256-GCM", "client=", "peer=")
        row(".id=ipsec/ikev2-eap/12", "kind=IKEv2", "service=ikev2-eap", "user=ivan", "public-key=", "endpoint=203.0.113.150", "address=10.30.0.5", "since=${local(now - 2_656, "+0300")}", "uptime=44m16s", "rx=8812043", "tx=71033487", "rx-rate=48000", "tx-rate=384000", "last-handshake=", "cipher=AES_GCM_16_256", "client=", "peer=")
    }
    list("vpn/history", writable = false) {
        for ((ago, seconds, fields) in listOf(
            Triple(5 * 3_600L, 7200L, listOf("kind=WireGuard", "service=wg0", "user=anna-phone", "endpoint=198.51.100.41:40112", "address=10.8.0.2", "reason=handshake timeout", "rx=31204433", "tx=402118833")),
            Triple(26 * 3_600L, 9300L, listOf("kind=OpenVPN", "service=server", "user=anna-laptop", "endpoint=198.51.100.41:51022", "address=10.9.0.6", "reason=client-exit", "rx=88120433", "tx=1204331870")),
            Triple(50 * 3_600L, 14400L, listOf("kind=WireGuard", "service=wg0", "user=ivan-laptop", "endpoint=203.0.113.150:51432", "address=10.8.0.3", "reason=handshake timeout", "rx=120334871", "tx=2210448871")),
            Triple(3 * 86_400L, 1800L, listOf("kind=IKEv2", "service=ikev2-eap", "user=ivan", "endpoint=203.0.113.150", "address=10.30.0.5", "reason=deleted", "rx=4120334", "tx=31004433")),
            Triple(8 * 86_400L, 600L, listOf("kind=OpenVPN", "service=server", "user=ivan-phone", "endpoint=203.0.113.151:40211", "address=10.9.0.10", "reason=ping-restart", "rx=1204433", "tx=8120334")),
        )) {
            val start = now - ago
            row(*fields.toTypedArray(), "seconds=$seconds", "start=$start", "connected=${local(start, "+0300")}", "disconnected=${local(start + seconds, "+0300")}", "duration=${duration(seconds)}")
        }
    }
    settings("vpn/collector", "installed=true", "runner=systemd", "size=184320", "file=/var/log/networkhelm/vpn-sessions.log", writable = false)
    list("vpn/user", fields = listOf(Argument("servers", ValueType.Enumeration(multiple = true, closed = true), listOf("wireguard/wg0", "openvpn/server", "ipsec/eap", "l2tp", "ocserv", "pptp")))) {
        row(".id=anna", "name=anna", "disabled=false", "servers=wireguard/wg0,openvpn/server,ipsec/eap", "addresses=10.8.0.2/32, 10.9.0.6", "password=", "rate-limit=")
        row(".id=ivan", "name=ivan", "disabled=false", "servers=wireguard/wg0,ipsec/eap,ocserv", "addresses=10.8.0.3/32, 10.30.0.5", "password=", "rate-limit=10M/50M")
        row(".id=branch-office", "name=branch-office", "disabled=false", "servers=wireguard/wg0,l2tp", "addresses=10.8.0.4/32, 10.40.0.10", "password=", "rate-limit=")
        row(".id=guest", "name=guest", "disabled=true", "servers=ocserv,pptp", "addresses=", "password=", "rate-limit=2M/5M")
    }
    command("vpn/user/client-config", run = { vpnUserConfig(it["number"].orEmpty()) })
    command("vpn/user/mobileconfig", run = {
        linuxAnswer(
            "file=${it["number"]}.mobileconfig",
            "conf=<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plist version=\"1.0\">\n<dict>\n  <key>PayloadDisplayName</key>\n  <string>demo-server IKEv2</string>\n" +
                "  <key>RemoteAddress</key>\n  <string>vpn.example.com</string>\n  <key>AuthName</key>\n  <string>${it["number"]}</string>\n</dict>\n</plist>\n",
        )
    })
    command("vpn/user/sswan", run = {
        linuxAnswer("file=${it["number"]}.sswan", "conf={\"uuid\":\"00000000-0000-4000-8000-000000000001\",\"name\":\"demo-server\",\"type\":\"ikev2-eap\",\"remote\":{\"addr\":\"vpn.example.com\"},\"local\":{\"eap_id\":\"${it["number"]}\"}}\n")
    })
    command("vpn/user/windows", run = {
        linuxAnswer("file=${it["number"]}.ps1", "conf=Add-VpnConnection -Name \"demo-server\" -ServerAddress \"vpn.example.com\" -TunnelType Ikev2 -AuthenticationMethod Eap -EncryptionLevel Maximum -RememberCredential\n")
    })
}

private fun vpnUserConfig(user: String): Flow<Item> {
    val address = mapOf("anna" to "10.8.0.2/32", "ivan" to "10.8.0.3/32", "branch-office" to "10.8.0.4/32")[user] ?: "10.8.0.9/32"
    return linuxAnswer("file=$user-wg0.conf", "conf=${vpnWireGuardConf(address, "51820", "10.8.0.1")}")
}

private fun vpnWireGuardConf(address: String, port: String, dns: String) =
    "[Interface]\nPrivateKey = ${vpnKey("clientPrivate")}\nAddress = $address\nDNS = $dns\n\n[Peer]\nPublicKey = ${vpnKey("demoServer")}\n" +
        "PresharedKey = ${vpnKey("presharedDemo")}\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = vpn.example.com:$port\nPersistentKeepalive = 25\n"

private fun vpnOpenVpnConf(name: String) =
    "client\ndev tun\nproto udp\nremote vpn.example.com 1194\nresolv-retry infinite\nnobind\nremote-cert-tls server\ndata-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305\nverb 3\n" +
        "<ca>\n-----BEGIN CERTIFICATE-----\nDEMO CERTIFICATE OF THE DEMO CA, NOT A REAL ONE\n-----END CERTIFICATE-----\n</ca>\n" +
        "<cert>\n-----BEGIN CERTIFICATE-----\nDEMO CERTIFICATE OF $name\n-----END CERTIFICATE-----\n</cert>\n" +
        "<key>\n-----BEGIN PRIVATE KEY-----\nDEMO KEY OF $name\n-----END PRIVATE KEY-----\n</key>\n"

private fun DemoBuilder.vpnWireGuardDemo() {
    list("vpn/wireguard") {
        row(
            ".id=wg0", "name=wg0", "source=wg-quick", "running=true", "autostart=true", "listen-port=51820", "endpoint=203.0.113.45:51820", "address=10.8.0.1/24, fd42:42:42::1/64",
            "public-key=${vpnKey("demoServer")}", "private-key=", "peers=4", "nat=true", "rx=6120334871", "tx=38211004433", "mtu=1420", "client-endpoint=vpn.example.com",
            "client-dns=10.8.0.1", "client-allowed-ips=0.0.0.0/0, ::/0", "client-keepalive=25", "dns=", "table=", "fwmark=", "pre-up=", "post-up=", "pre-down=", "post-down=",
            "file=/etc/wireguard/wg0.conf",
        )
        row(
            ".id=wg-office", "name=wg-office", "source=wg-quick", "running=false", "autostart=false", "listen-port=", "endpoint=", "address=10.77.0.2/32",
            "public-key=${vpnKey("officeClient")}", "private-key=", "peers=1", "nat=false", "rx=", "tx=", "mtu=", "client-endpoint=", "client-dns=", "client-allowed-ips=",
            "client-keepalive=", "dns=192.168.100.1", "table=", "fwmark=", "pre-up=", "post-up=", "pre-down=", "post-down=", "file=/etc/wireguard/wg-office.conf",
        )
    }
    list("vpn/wireguard/peer", live = linuxGrowing("rx" to 6_000L, "tx" to 48_000L)) {
        row(".id=wg0/${vpnKey("annaPhone")}", "interface=wg0", "name=anna-phone", "public-key=${vpnKey("annaPhone")}", "preshared-key=", "allowed-ips=10.8.0.2/32", "endpoint=", "persistent-keepalive=", "disabled=false", "current-endpoint=198.51.100.41:40112", "last-handshake=41s", "rx=84120331", "tx=912044871", "keep-key=true", "client-key=true", "client-address=10.8.0.2/32", "client-dns=10.8.0.1", "client-allowed-ips=0.0.0.0/0, ::/0", "client-endpoint=vpn.example.com:51820", "client-mtu=", "client-keepalive=25")
        row(".id=wg0/${vpnKey("ivanLaptop")}", "interface=wg0", "name=ivan-laptop", "public-key=${vpnKey("ivanLaptop")}", "preshared-key=", "allowed-ips=10.8.0.3/32", "endpoint=", "persistent-keepalive=", "disabled=false", "current-endpoint=203.0.113.150:51432", "last-handshake=4h12m", "rx=", "tx=", "keep-key=true", "client-key=true", "client-address=10.8.0.3/32", "client-dns=10.8.0.1", "client-allowed-ips=0.0.0.0/0, ::/0", "client-endpoint=vpn.example.com:51820", "client-mtu=", "client-keepalive=25")
        row(".id=wg0/${vpnKey("branchOffice")}", "interface=wg0", "name=branch-office", "public-key=${vpnKey("branchOffice")}", "preshared-key=", "allowed-ips=10.8.0.4/32, 192.168.50.0/24", "endpoint=branch.example.org:51820", "persistent-keepalive=25", "disabled=false", "current-endpoint=198.51.100.90:51820", "last-handshake=1m50s", "rx=5120334871", "tx=21004433112", "keep-key=false", "client-key=false", "client-address=", "client-dns=", "client-allowed-ips=", "client-endpoint=", "client-mtu=", "client-keepalive=")
        row(".id=wg0/${vpnKey("oldTablet")}", "interface=wg0", "name=old-tablet", "public-key=${vpnKey("oldTablet")}", "preshared-key=", "allowed-ips=10.8.0.5/32", "endpoint=", "persistent-keepalive=", "disabled=true", "current-endpoint=", "last-handshake=", "rx=", "tx=", "keep-key=true", "client-key=true", "client-address=10.8.0.5/32", "client-dns=10.8.0.1", "client-allowed-ips=0.0.0.0/0", "client-endpoint=vpn.example.com:51820", "client-mtu=", "client-keepalive=")
        row(".id=wg-office/${vpnKey("officeServer")}", "interface=wg-office", "name=office", "public-key=${vpnKey("officeServer")}", "preshared-key=", "allowed-ips=10.77.0.0/24, 192.168.100.0/24", "endpoint=office.example.org:51820", "persistent-keepalive=25", "disabled=false", "current-endpoint=", "last-handshake=", "rx=", "tx=", "keep-key=false", "client-key=false", "client-address=", "client-dns=", "client-allowed-ips=", "client-endpoint=", "client-mtu=", "client-keepalive=")
    }
    command("vpn/wireguard/peer/client-config", run = { linuxAnswer("conf=${vpnWireGuardConf(vpnPeers[it["number"].orEmpty()] ?: "10.8.0.9/32", "51820", "10.8.0.1")}") })
    settings(
        "vpn/wireguard/server",
        "name=wg1", "listen-port=51822", "client-endpoint=203.0.113.45", "autostart=yes", "address=10.8.1.1/24", "mtu=", "client-dns=10.8.1.1",
        "client-allowed-ips=0.0.0.0/0, ::/0", "client-keepalive=25", "private-key=", "preshared-key=yes", "nat=yes", "client=phone", "keep-key=yes",
        writable = false, fields = vpnWireGuardServer,
    )
    command("vpn/wireguard/server/create", run = { linuxAnswer("name=${it["name"]}", "conf=${vpnWireGuardConf("10.8.1.2/32", it["listen-port"].orEmpty(), it["client-dns"].orEmpty())}") })
    settings("vpn/wireguard/import", "name=wg1", "autostart=true", "keep-session=true", "text=", writable = false, fields = vpnImport)
    command("vpn/wireguard/import/import", run = { linuxAnswer("name=${it["name"]}") })

    list("vpn/amneziawg") {
        row(
            ".id=awg0", "name=awg0", "running=true", "autostart=true", "listen-port=51821", "endpoint=203.0.113.45:51821", "address=10.20.0.1/24", "public-key=${vpnKey("amneziaServer")}",
            "private-key=", "peers=1", "nat=true", "rx=310442871", "tx=2210448871", "mtu=1380", "jc=4", "jmin=40", "jmax=70", "s1=52", "s2=118", "s3=", "s4=",
            "h1=1196425712", "h2=2084936617", "h3=301928744", "h4=1844214530", "i1=", "i2=", "i3=", "i4=", "i5=", "client-endpoint=vpn.example.com", "client-dns=10.20.0.1",
            "client-allowed-ips=0.0.0.0/0", "client-keepalive=25", "dns=", "table=", "fwmark=", "pre-up=", "post-up=", "pre-down=", "post-down=", "source=awg-quick",
            "file=/etc/amnezia/amneziawg/awg0.conf",
        )
    }
    list("vpn/amneziawg/peer", live = linuxGrowing("rx" to 6_000L, "tx" to 48_000L)) {
        row(".id=awg0/${vpnKey("travelRouter")}", "interface=awg0", "name=travel-router", "public-key=${vpnKey("travelRouter")}", "preshared-key=", "allowed-ips=10.20.0.2/32", "endpoint=", "persistent-keepalive=", "disabled=false", "current-endpoint=203.0.113.150:39221", "last-handshake=1m3s", "rx=31044287", "tx=221044887", "keep-key=true", "client-key=true", "client-address=10.20.0.2/32", "client-dns=10.20.0.1", "client-allowed-ips=0.0.0.0/0", "client-endpoint=vpn.example.com:51821", "client-mtu=1380", "client-keepalive=25")
        row(".id=awg0/${vpnKey("spareLaptop")}", "interface=awg0", "name=spare-laptop", "public-key=${vpnKey("spareLaptop")}", "preshared-key=", "allowed-ips=10.20.0.3/32", "endpoint=", "persistent-keepalive=", "disabled=false", "current-endpoint=", "last-handshake=", "rx=", "tx=", "keep-key=true", "client-key=true", "client-address=10.20.0.3/32", "client-dns=10.20.0.1", "client-allowed-ips=0.0.0.0/0", "client-endpoint=vpn.example.com:51821", "client-mtu=1380", "client-keepalive=25")
    }
    command("vpn/amneziawg/peer/client-config", run = {
        linuxAnswer("conf=${vpnWireGuardConf(vpnPeers[it["number"].orEmpty()] ?: "10.20.0.3/32", "51821", "10.20.0.1").replace("DNS = 10.20.0.1\n", "DNS = 10.20.0.1\nJc = 4\nJmin = 40\nJmax = 70\nS1 = 52\nS2 = 118\nH1 = 1196425712\nH2 = 2084936617\nH3 = 301928744\nH4 = 1844214530\n")}")
    })
    settings(
        "vpn/amneziawg/server",
        "name=awg1", "listen-port=51823", "client-endpoint=203.0.113.45", "autostart=yes", "address=10.20.1.1/24", "mtu=1380", "client-dns=10.20.1.1",
        "client-allowed-ips=0.0.0.0/0", "client-keepalive=25", "private-key=", "preshared-key=yes", "nat=yes", "client=phone", "keep-key=yes",
        "jc=5", "jmin=50", "jmax=1000", "s1=68", "s2=149", "h1=1033124712", "h2=1730920131", "h3=402184457", "h4=1913374006",
        writable = false, fields = vpnWireGuardServer + vpnDisguise,
    )
    command("vpn/amneziawg/server/create", run = { linuxAnswer("name=${it["name"]}", "conf=${vpnWireGuardConf("10.20.1.2/32", it["listen-port"].orEmpty(), it["client-dns"].orEmpty())}") })
    settings("vpn/amneziawg/import", "name=awg1", "autostart=true", "keep-session=true", "text=", writable = false, fields = vpnImport)
    command("vpn/amneziawg/import/import", run = { linuxAnswer("name=${it["name"]}") })
}

private fun DemoBuilder.vpnOpenVpnDemo() {
    list("vpn/openvpn") {
        row(
            ".id=server", "name=server", "state=active", "autostart=true", "port=1194", "proto=udp", "dev=tun0", "network=10.9.0.0/24", "network6=", "clients=3", "nat=true",
            "client-remote=vpn.example.com", "dns=10.9.0.1", "redirect-gateway=true", "push-routes=192.168.10.0/24", "client-to-client=false", "duplicate-cn=false",
            "max-clients=32", "data-ciphers=AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305", "keepalive=10 120", "user=nobody", "group=nogroup", "verb=3", "tls=tls-crypt",
            "management=", "pki=/etc/openvpn/server/easy-rsa", "file=/etc/openvpn/server/server.conf",
        )
    }
    list("vpn/openvpn/client") {
        row(".id=server/anna-laptop", "server=server", "name=anna-laptop", "state=valid", "expires=2028-11-03 19:20:44", "disabled=false", "address=10.9.0.6", "serial=5A1F0C33D2E4B1A07C9E", "revoked=")
        row(".id=server/ivan-phone", "server=server", "name=ivan-phone", "state=valid", "expires=2028-08-14 10:02:11", "disabled=false", "address=10.9.0.10", "serial=1C40A9E2B7D3F0815A6C", "revoked=")
        row(".id=server/old-pc", "server=server", "name=old-pc", "state=revoked", "expires=2027-02-01 08:00:00", "disabled=true", "address=", "serial=7E22B04C91A3D5F6E018", "revoked=2026-05-20 21:14:09")
    }
    command("vpn/openvpn/client/client-config", run = { linuxAnswer("conf=${vpnOpenVpnConf(it["number"].orEmpty().substringAfter('/'))}") })
    list("vpn/openvpn/connection") {
        row(".id=provider-nl", "name=provider-nl", "import=", "user=demo", "password=", "autostart=false", "state=inactive", "remote=nl.vpn.example.net 1194", "proto=udp", "dev=tun", "file=/etc/openvpn/client/provider-nl.conf")
    }
    settings(
        "vpn/openvpn/server",
        "name=server2", "port=1195", "proto=udp", "client-remote=203.0.113.45", "autostart=yes", "network=10.9.1.0/24", "network6=", "dns=10.9.1.1",
        "redirect-gateway=yes", "push-routes=", "data-ciphers=AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305", "nat=yes", "client=phone", "client-to-client=no", "max-clients=32",
        writable = false,
        fields = listOf(
            vpnField("name", "Name", "general"), vpnField("port", "Port", "general", ValueType.Integer(1, 65535)), vpnField("proto", "Protocol", "general"),
            vpnField("client-remote", "Address for Clients", "general"), vpnField("autostart", "Start at Boot", "general", ValueType.YesNo),
            vpnField("network", "IPv4 Network", "network"), vpnField("network6", "IPv6 Network", "network", optional = true), vpnField("dns", "DNS for Clients", "dns", optional = true),
            vpnField("redirect-gateway", "All Traffic Through VPN", "dns", ValueType.YesNo), vpnField("push-routes", "Routes for Clients", "dns", optional = true),
            vpnField("data-ciphers", "Data Ciphers", "dns"), vpnField("nat", "NAT for Clients", "nat", ValueType.YesNo), vpnField("client", "First Client", "clients", optional = true),
            vpnField("client-to-client", "Client to Client", "clients", ValueType.YesNo), vpnField("max-clients", "Max Clients", "clients", ValueType.Integer(1)),
        ),
    )
    command("vpn/openvpn/server/create", run = { linuxAnswer("name=${it["name"]}", "conf=${vpnOpenVpnConf(it["client"].orEmpty().ifEmpty { "client" })}") })
    settings(
        "vpn/openvpn/import", "name=provider-de", "autostart=true", "keep-session=true", "user=", "password=", "text=",
        writable = false, fields = vpnImport + listOf(vpnField("user", "User", optional = true), vpnField("password", "Password", type = ValueType.Secret, optional = true)),
    )
    command("vpn/openvpn/import/import", run = { linuxAnswer("name=${it["name"]}") })
}

private fun DemoBuilder.vpnIpsecDemo() {
    list("vpn/ipsec") {
        row(
            ".id=ikev2-eap", "name=ikev2-eap", "loaded=true", "version=2", "local-auth=pubkey", "remote-auth=eap-mschapv2", "server-id=vpn.example.com", "pool=10.30.0.0/24",
            "pool6=", "dns=10.30.0.1", "local-ts=0.0.0.0/0", "autostart=true", "nat=true", "proposals=aes256gcm16-prfsha384-ecp384, aes256-sha256-modp2048",
            "esp-proposals=aes256gcm16, aes256-sha256", "rekey-time=4h", "child-rekey-time=1h", "send-cert=always", "dpd-delay=30s", "mobike=yes", "fragmentation=yes",
            "encap=no", "unique=never", "remote-addrs=%any", "kept=true", "file=/etc/swanctl/conf.d/ikev2-eap.conf",
        )
        row(
            ".id=site-office", "name=site-office", "loaded=true", "version=2", "local-auth=psk", "remote-auth=psk", "server-id=demo-server.example.com", "pool=",
            "pool6=", "dns=", "local-ts=192.168.10.0/24", "autostart=true", "nat=false", "proposals=aes256-sha256-modp2048", "esp-proposals=aes256-sha256-modp2048",
            "rekey-time=4h", "child-rekey-time=1h", "send-cert=never", "dpd-delay=30s", "mobike=no", "fragmentation=yes", "encap=no", "unique=replace",
            "remote-addrs=office.example.org", "kept=true", "file=/etc/swanctl/conf.d/site-office.conf",
        )
    }
    command("vpn/ipsec/load", run = {
        linuxAnswer("output=loaded certificate from '/etc/swanctl/x509/server.pem'\nloaded ecdsa key from '/etc/swanctl/private/server.key'\nloaded eap secret 'eap-anna'\nloaded eap secret 'eap-ivan'\nloaded ike secret 'ike-site-office'\nloaded pool 'pool-ikev2-eap'\nsuccessfully loaded 1 pools, 0 unloaded\nloaded connection 'ikev2-eap'\nloaded connection 'site-office'\nsuccessfully loaded 2 connections, 0 unloaded")
    })
    list("vpn/ipsec/client") {
        row(".id=provider-ikev2", "name=provider-ikev2", "state=inactive", "loaded=true", "server=ikev2.vpn.example.net", "auth=eap", "user=demo", "secret=", "server-id=ikev2.vpn.example.net", "ca-file=/etc/swanctl/x509ca/provider.pem", "networks=0.0.0.0/0", "local-networks=", "vip=", "file=/etc/swanctl/conf.d/provider-ikev2.conf")
    }
    settings(
        "vpn/ipsec/server",
        "name=ikev2-mobile", "client-endpoint=vpn.example.com", "autostart=yes", "pool=10.31.0.0/24", "pool6=", "dns=10.31.0.1", "local-ts=0.0.0.0/0", "nat=yes", "user=", "password=",
        writable = false,
        fields = listOf(
            vpnField("name", "Name", "general"), vpnField("client-endpoint", "Address for Clients", "general"), vpnField("autostart", "Start at Boot", "general", ValueType.YesNo),
            vpnField("pool", "IPv4 Pool", "network"), vpnField("pool6", "IPv6 Pool", "network", optional = true), vpnField("dns", "DNS for Clients", "dns", optional = true),
            vpnField("local-ts", "Networks for Clients", "dns"), vpnField("nat", "NAT for Clients", "nat", ValueType.YesNo),
        ) + vpnClients,
    )
    command("vpn/ipsec/server/create", run = { linuxAnswer("name=${it["name"]}") })
    settings(
        "vpn/ipsec/import", "name=provider-de", "server=", "user=", "password=", "networks=0.0.0.0/0", "text=",
        writable = false,
        fields = listOf(
            vpnField("name", "Name"), vpnField("server", "Server"), vpnField("user", "User", optional = true), vpnField("password", "Password", type = ValueType.Secret, optional = true),
            vpnField("networks", "Networks Through the Tunnel"), vpnField("text", "Configuration", type = ValueType.Secret),
        ),
    )
    command("vpn/ipsec/import/import", run = { linuxAnswer("name=${it["name"]}") })
    list("vpn/libreswan") {
        row(
            ".id=office-lan", "name=office-lan", "loaded=true", "sas=2", "left=%defaultroute", "leftid=@demo-server.example.com", "leftsubnet=192.168.10.0/24",
            "right=198.51.100.40", "rightid=@office.example.org", "rightsubnet=192.168.100.0/24", "keyexchange=ikev2", "authby=secret", "psk=", "type=tunnel", "auto=start",
            "ike=aes256-sha2;dh19", "esp=aes256-sha2", "dpddelay=30", "file=/etc/ipsec.d/office-lan.conf",
        )
    }
}

private fun DemoBuilder.vpnPppDemo() {
    settings(
        "vpn/l2tp",
        "state=active", "local-ip=10.40.0.1", "pool=10.40.0.10-10.40.0.50", "dns=10.40.0.1", "mtu=1400", "psk=", "autostart=true", "nat=true",
        "lcp-echo-interval=30", "lcp-echo-failure=4", "idle=1800", "ipsec-loaded=true", "users=2", "kept=true",
    )
    list("vpn/l2tp/client") {
        row(".id=office-l2tp", "name=office-l2tp", "state=down", "autostart=false", "server=198.51.100.40", "user=branch", "interface=")
    }
    settings(
        "vpn/l2tp/server",
        "client-endpoint=203.0.113.45", "autostart=yes", "local-ip=10.40.0.1", "pool=10.40.0.10-10.40.0.50", "dns=10.40.0.1", "psk=", "nat=yes", "user=", "password=",
        writable = false,
        fields = listOf(
            vpnField("client-endpoint", "Address for Clients", "general"), vpnField("autostart", "Start at Boot", "general", ValueType.YesNo), vpnField("local-ip", "Local Address", "network"),
            vpnField("pool", "Client Addresses", "network"), vpnField("dns", "DNS for Clients", "dns", optional = true), vpnField("psk", "IPsec Secret", "keys", ValueType.Secret, optional = true),
            vpnField("nat", "NAT for Clients", "nat", ValueType.YesNo),
        ) + vpnClients,
    )
    command("vpn/l2tp/server/create", run = { linuxAnswer("name=l2tp") })
    settings("vpn/l2tp/import", "name=branch-l2tp", "server=", "psk=", "user=", "password=", "autostart=false", "text=", writable = false, fields = vpnPppImport + vpnField("psk", "IPsec Secret", type = ValueType.Secret, optional = true))
    command("vpn/l2tp/import/import", run = { linuxAnswer("name=${it["name"]}") })
    settings(
        "vpn/pptp",
        "state=inactive", "local-ip=10.60.0.1", "remote-ip=10.60.0.10-50", "connections=20", "dns=10.60.0.1", "autostart=false", "nat=true", "insecure=true",
        "mtu=1400", "mru=1400", "lcp-echo-interval=30", "lcp-echo-failure=4", "name=pptpd", "mppe=require-mppe-128", "options=/etc/ppp/pptpd-options", "users=1",
    )
    list("vpn/pptp/client") {
        row(".id=old-office", "name=old-office", "state=down", "autostart=false", "server=198.51.100.60", "user=branch", "interface=")
    }
    settings(
        "vpn/pptp/server",
        "insecure=no", "client-endpoint=203.0.113.45", "autostart=no", "local-ip=10.60.0.1", "remote-ip=10.60.0.10-50", "dns=10.60.0.1", "nat=yes", "user=", "password=",
        writable = false,
        fields = listOf(
            vpnField("insecure", "Insecure, Create It", "general", ValueType.YesNo), vpnField("client-endpoint", "Address for Clients", "general"),
            vpnField("autostart", "Start at Boot", "general", ValueType.YesNo), vpnField("local-ip", "Local Address", "network"), vpnField("remote-ip", "Client Addresses", "network"),
            vpnField("dns", "DNS for Clients", "dns", optional = true), vpnField("nat", "NAT for Clients", "nat", ValueType.YesNo),
        ) + vpnClients,
    )
    command("vpn/pptp/server/create", run = { linuxAnswer("name=pptp") })
    settings("vpn/pptp/import", "name=old-office-2", "server=", "user=", "password=", "autostart=false", "text=", writable = false, fields = vpnPppImport)
    command("vpn/pptp/import/import", run = { linuxAnswer("name=${it["name"]}") })
    settings(
        "vpn/ocserv",
        "state=active", "server-name=vpn.example.com", "pin=sha256:DEMOdemoDEMOdemoDEMOdemoDEMOdemoDEMOdemo0000=", "tcp-port=4443", "udp-port=4443", "network=10.50.0.0/24",
        "ipv6-network=", "autostart=true", "nat=true", "dns=10.50.0.1", "routes=default", "no-routes=192.168.10.0/24", "default-domain=example.com", "max-clients=16",
        "max-same-clients=2", "banner=", "keepalive=32400", "dpd=90", "mobile-dpd=1800", "idle-timeout=1200", "mobile-idle-timeout=2400", "isolate-workers=true",
        "cisco-client-compat=true", "camouflage=false", "camouflage-secret=", "server-cert=/etc/ocserv/server-cert.pem", "server-key=/etc/ocserv/server-key.pem", "users=2",
    )
    settings(
        "vpn/ocserv/server",
        "client-endpoint=vpn.example.com", "port=4443", "autostart=yes", "network=10.50.0.0/24", "dns=10.50.0.1", "routes=default", "nat=yes", "user=", "password=",
        writable = false,
        fields = listOf(
            vpnField("client-endpoint", "Address for Clients", "general"), vpnField("port", "Port", "general", ValueType.Integer(1, 65535)),
            vpnField("autostart", "Start at Boot", "general", ValueType.YesNo), vpnField("network", "IPv4 Network", "network"), vpnField("dns", "DNS for Clients", "dns", optional = true),
            vpnField("routes", "Routes for Clients", "dns"), vpnField("nat", "NAT for Clients", "nat", ValueType.YesNo),
        ) + vpnClients,
    )
    command("vpn/ocserv/server/create", run = { linuxAnswer("name=ocserv") })
}

private val vpnPppImport = listOf(
    vpnField("name", "Name"), vpnField("server", "Server"), vpnField("user", "User"), vpnField("password", "Password", type = ValueType.Secret, optional = true),
    vpnField("autostart", "Start at Boot", type = ValueType.YesNo), vpnField("text", "Configuration", type = ValueType.Secret),
)

private fun DemoBuilder.vpnClientsDemo() {
    list("vpn/openconnect") {
        row(".id=work-anyconnect", "name=work-anyconnect", "state=inactive", "autostart=false", "server=vpn.example.net", "protocol=anyconnect", "user=demo", "password=", "pin=", "interface=")
    }
    settings("vpn/openconnect/import", "name=work-2", "server=", "pin=", "user=", "password=", "autostart=false", "text=", writable = false, fields = vpnPppImport + vpnField("pin", "Server Certificate Pin", optional = true))
    command("vpn/openconnect/import/import", run = { linuxAnswer("name=${it["name"]}") })
    list("vpn/sstp") {
        row(".id=provider-sstp", "name=provider-sstp", "state=active", "autostart=true", "server=sstp.vpn.example.net", "user=demo", "password=", "ca-file=", "skip-cert-check=false", "default-route=false", "peer-dns=false", "mtu=1400", "interface=ppp0")
    }
}

private fun DemoBuilder.vpnMeshDemo() {
    list("vpn/tailscale", live = { row, tick -> if (row["online"] == "true" && row["kind"] == "peer") mapOf("last-seen" to "", "rx" to "${(row["rx"]?.toLongOrNull() ?: 0L) + tick * 2_100L}") else emptyMap() }) {
        row(
            ".id=self", "kind=self", "name=demo-server", "dns-name=demo-server.tail0000.ts.net", "addresses=100.101.102.103, fd7a:115c:a1e0::6f01:6667", "os=linux", "online=true",
            "active=true", "connection=", "rx=", "tx=", "last-seen=", "exit-node=false", "exit-node-option=false", "state=Running", "tailnet=example.com", "version=1.76.6",
            "auth-url=", "auth-key=", "login-server=", "hostname=", "accept-routes=true", "accept-dns=false", "ssh=false", "shields-up=false",
            "advertise-routes=192.168.10.0/24", "advertise-exit-node=true", "exit-node-allow-lan-access=false", "auto-update=false", "webclient=false", "operator=admin",
            "snat-subnet-routes=true", "stateful-filtering=false", "netfilter-mode=on",
        )
        row(".id=nodekey:laptop", "kind=peer", "name=anna-laptop", "dns-name=anna-laptop.tail0000.ts.net", "addresses=100.88.12.4, fd7a:115c:a1e0::2a01:c04", "os=macOS", "online=true", "active=true", "connection=198.51.100.71:41641", "rx=88120433", "tx=21004433", "last-seen=", "exit-node=false", "exit-node-option=false")
        row(".id=nodekey:phone", "kind=peer", "name=ivan-phone", "dns-name=ivan-phone.tail0000.ts.net", "addresses=100.91.40.17, fd7a:115c:a1e0::5b01:2811", "os=iOS", "online=true", "active=false", "connection=relay \"fra\"", "rx=1204433", "tx=880412", "last-seen=", "exit-node=false", "exit-node-option=false")
        row(".id=nodekey:vps", "kind=peer", "name=vps-exit", "dns-name=vps-exit.tail0000.ts.net", "addresses=100.77.3.9, fd7a:115c:a1e0::ab01:309", "os=linux", "online=true", "active=false", "connection=203.0.113.200:41641", "rx=0", "tx=0", "last-seen=", "exit-node=false", "exit-node-option=true")
        row(".id=nodekey:nas", "kind=peer", "name=nas", "dns-name=nas.tail0000.ts.net", "addresses=100.120.8.33, fd7a:115c:a1e0::e601:821", "os=linux", "online=false", "active=false", "connection=", "rx=0", "tx=0", "last-seen=2026-11-17 08:12:44 UTC", "exit-node=false", "exit-node-option=false")
    }
    list("vpn/zerotier") {
        row(".id=1c33c1ced0f2e3a4", "id=1c33c1ced0f2e3a4", "name=home-lab", "status=OK", "type=PRIVATE", "mac-address=00:00:5E:00:53:9A", "device=ztr2qsmswx", "addresses=10.147.17.20/24", "mtu=2800", "routes=10.147.17.0/24", "dns=", "dns-domain=", "allow-managed=true", "allow-global=false", "allow-default=false", "allow-dns=false")
    }
    list("vpn/zerotier/peer", writable = false) {
        row(".id=a0b1c2d3e4", "address=a0b1c2d3e4", "role=PLANET", "version=", "latency=48", "path=203.0.113.11/9993", "paths=1")
        row(".id=b1c2d3e4f5", "address=b1c2d3e4f5", "role=PLANET", "version=", "latency=112", "path=198.51.100.12/9993", "paths=1")
        row(".id=a1b2c3d4e5", "address=a1b2c3d4e5", "role=LEAF", "version=1.14.2", "latency=7", "path=192.168.10.101/9993", "paths=2")
    }
    settings("vpn/zerotier/node", "address=f0e1d2c3b4", "version=1.14.2", "online=true", "primary-port=9993", "port-mapping=true", "tcp-fallback=false", "force-tcp=false")
}
