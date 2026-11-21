package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.Item
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val SERVICES_WG_OFFICE = "Of7cDemoPublicKeyOfTheOfficePeer0000000000A="

private val servicesHandlers = choice("handler", "not-used", "WifiToggle", "WifiGuestApToggle", "LedToggle", "WpsStartMainAp", "WpsStartMainAp5", "UnmountAll", "OpkgRunScript")

private val servicesControls = choice("control", "not-used", "UpdatesAvailable", "SelectedWan__PPPoE0", "SelectedSchedule__schedule0", "UsbPortDeviceAttached")

internal fun DemoBuilder.keeneticServicesDemo() {
    settings(
        "internet/dsl",
        "name=Dsl0", "line=Showtime", "opmode=VDSL2", "standard=G.993.2", "profile=17a", "ds_intd_rate=79999", "us_intd_rate=19999", "ds_fast_rate=0", "us_fast_rate=0",
        "ds_attain=112384", "us_attain=31284", "ds_noise=9.1", "us_noise=7.8", "ds_atten=12.4", "us_atten=8.9", "ds_power=13.2", "us_power=6.4", "no-carrier=2", "uptime=8d19h",
        "operating-mode.mode=auto", "operating-mode.annex=a", "vdsl.profile=8a,8b,8c,8d,12a,12b,17a", "vdsl.carrier=auto", "adsl.snr-margin-offset=0", "vdsl.snr-margin=0",
        "dsl.ginp=true", "vdsl.gvector=true", "vdsl.upbo=auto", "dsl.disconnect-report=false",
        writable = false,
    )
    list("internet/sms") {
        row(".id=UsbQmi0/nv-0", "interface=UsbQmi0", "from=900", "text=Internet package 30 GB renewed until 30.11.2026.", "timestamp=2026-10-31 09:12:44", "read=true", "storage=nv")
        row(".id=UsbQmi0/nv-1", "interface=UsbQmi0", "from=Demo Mobile", "text=Balance 412.50. Next payment 15.11.2026.", "timestamp=2026-10-30 18:02:11", "read=true", "storage=nv")
        row(".id=UsbQmi0/sim-2", "interface=UsbQmi0", "from=+70000000000", "text=Code 000000 for the demo account.", "timestamp=2026-10-31 11:47:05", "read=false", "storage=sim")
    }
    settings("internet/ussd", "interface=UsbQmi0", "request=*100#", "response=Balance 412.50. Tariff Demo 30.")

    list("vpn/wireguard") {
        row(
            ".id=Wireguard0", "name=Wireguard0", "type=Wireguard", "description=Office", "disabled=false", "connected=yes", "global=false", "schedule=", "uptime=1d2h",
            "public-key=Kq9DemoPublicKeyOfThisRouter000000000000000=", "address=10.8.0.2/24", "listen-port=51820",
            "dns=", "mtu=1420", "adjust-mss=true", "peers=1", "rxbytes=403221187", "txbytes=98126110",
        )
        row(
            ".id=Wireguard1", "name=Wireguard1", "type=Wireguard", "description=Phone access", "disabled=false", "connected=yes", "global=false", "schedule=", "uptime=8d19h",
            "public-key=Ph0DemoPublicKeyOfThisRouter000000000000000=", "address=172.16.82.1/24", "listen-port=51821",
            "dns=", "mtu=1420", "adjust-mss=true", "peers=2", "rxbytes=18204411", "txbytes=91200334",
        )
    }
    command("vpn/wireguard/generate-keys", run = { refusal("keys stay as they are") })
    list("vpn/wireguard/peer") {
        row(
            ".id=Wireguard0/$SERVICES_WG_OFFICE", "interface=Wireguard0", "connection=Office", "public-key=$SERVICES_WG_OFFICE", "comment=office-gw", "preshared-key=",
            "endpoint=vpn.example.com:51820", "allowed-ips=10.8.0.0/24, 10.20.0.0/16", "keepalive=25", "via=any", "online=true", "last-handshake=37", "rxbytes=403221187",
            "txbytes=98126110",
        )
        row(
            ".id=Wireguard1/PhoneDemoPublicKey000000000000000000000000=", "interface=Wireguard1", "connection=Phone access", "public-key=PhoneDemoPublicKey000000000000000000000000=",
            "comment=phone", "preshared-key=", "endpoint=", "allowed-ips=172.16.82.2/32", "keepalive=", "via=any", "online=true", "last-handshake=94", "rxbytes=18002133",
            "txbytes=90311220",
        )
        row(
            ".id=Wireguard1/TabletDemoPublicKey00000000000000000000000=", "interface=Wireguard1", "connection=Phone access", "public-key=TabletDemoPublicKey00000000000000000000000=",
            "comment=tablet", "preshared-key=", "endpoint=", "allowed-ips=172.16.82.3/32", "keepalive=", "via=any", "online=false", "last-handshake=0", "rxbytes=202278",
            "txbytes=889114",
        )
    }
    list("vpn/openvpn") {
        row(
            ".id=OpenVPN0", "name=OpenVPN0", "type=OpenVPN", "description=Backup VPN", "disabled=true", "connected=no", "global=false", "schedule=", "uptime=",
            "config=client\ndev tun\nproto udp\nremote vpn.example.org 1194\nnobind\npersist-key\npersist-tun\nremote-cert-tls server\ncipher AES-256-GCM\nverb 3",
            "accept-routes=true", "via=any", "address=", "rxbytes=0", "txbytes=0",
        )
    }
    list("vpn/tunnel", fields = listOf(choice("type", "IPIP", "Gre", "EoIP"))) {
        row(
            ".id=Gre0", "name=Gre0", "type=Gre", "description=Branch GRE", "disabled=false", "connected=yes", "global=false", "schedule=", "uptime=8d19h", "address=172.16.40.1/30",
            "destination=198.51.100.40", "source=auto", "eoip-id=", "mtu=1476", "local=203.0.113.120", "remote=198.51.100.40", "rxbytes=88120344", "txbytes=61200410",
        )
        row(
            ".id=EoIP0", "name=EoIP0", "type=EoIP", "description=Lab bridge", "disabled=true", "connected=no", "global=false", "schedule=", "uptime=", "address=172.16.41.1/30",
            "destination=192.0.2.77", "source=PPPoE0", "eoip-id=101", "mtu=1500", "local=", "remote=", "rxbytes=0", "txbytes=0",
        )
    }
    list("vpn/6in4") {
        row(
            ".id=TunnelSixInFour0", "name=TunnelSixInFour0", "type=TunnelSixInFour", "description=IPv6 broker", "disabled=true", "connected=no", "global=false", "schedule=",
            "uptime=", "server=192.0.2.66", "ipv6-address=2001:db8:ff00::2", "ipv6-prefix=2001:db8:ff10::/48",
        )
    }

    list("firewall/ipv6", ordered = true) {
        row(
            ".id=h0", "interface=PPPoE0", "direction=in", "acl=_WEBADMIN_PPPoE0", "position=0", "action=permit", "protocol=tcp", "source=", "source-port=",
            "destination=2001:db8:10::10", "destination-port=443", "icmp-type=", "icmp-code=", "schedule=", "description=NAS over HTTPS", "disabled=false",
        )
        row(
            ".id=h1", "interface=PPPoE0", "direction=in", "acl=_WEBADMIN_PPPoE0", "position=1", "action=permit", "protocol=icmp", "source=", "source-port=", "destination=",
            "destination-port=", "icmp-type=echo-request", "icmp-code=", "schedule=", "description=Ping", "disabled=false",
        )
        row(
            ".id=h2", "interface=PPPoE0", "direction=in", "acl=_WEBADMIN_PPPoE0", "position=2", "action=deny", "protocol=tcp", "source=", "source-port=", "destination=",
            "destination-port=22", "icmp-type=", "icmp-code=", "schedule=schedule0", "description=SSH at night", "disabled=true",
        )
    }
    list("schedule") {
        row(".id=schedule0", "name=schedule0", "description=Night", "actions=start 23:00 *; stop 07:00 *")
        row(".id=schedule1", "name=schedule1", "description=Kids Wi-Fi", "actions=stop 22:00 sun,mon,tue,wed,thu; start 07:00 mon,tue,wed,thu,fri")
    }

    settings("system/led", "mode=front", "schedule=schedule0", fields = listOf(choice("mode", "on", "all", "front", "back"), choice("schedule", "", "schedule0", "schedule1")))
    list("system/button", fields = listOf(servicesHandlers)) {
        row(".id=WLAN/click", "button=WLAN", "action=click", "handler=WpsStartMainAp", "default=WpsStartMainAp", "protected=false")
        row(".id=WLAN/double-click", "button=WLAN", "action=double-click", "handler=WifiGuestApToggle", "default=not-used", "protected=false")
        row(".id=WLAN/hold", "button=WLAN", "action=hold", "handler=WifiToggle", "default=WifiToggle", "protected=false")
        row(".id=FN1/click", "button=FN1", "action=click", "handler=LedToggle", "default=not-used", "protected=false")
        row(".id=FN2/click", "button=FN2", "action=click", "handler=UnmountAll", "default=UnmountAll", "protected=false")
        row(".id=RESET/hold", "button=RESET", "action=hold", "handler=FactoryReset", "default=FactoryReset", "protected=true")
    }
    list("system/led/binding", fields = listOf(servicesControls)) {
        row(".id=FN1", "led=FN1", "control=SelectedWan__PPPoE0", "default=not-used")
        row(".id=FN2", "led=FN2", "control=UpdatesAvailable", "default=UsbPortDeviceAttached")
    }

    list("system/storage", writable = false) {
        row(
            ".id=Media0", "name=Generic Flash Disk", "state=ready", "size=30752636928", "free=21474836480", "partitions=DEMO ext4", "usb=1 3.0", "ejectable=true",
            "serial=0000000000000001",
        )
    }
    command("system/storage/eject", run = { refusal("the drive stays where it is") })
    settings("system/opkg", "disk=DEMO:/", "initrc=/opt/etc/init.d/rc.unslung", "components=opkg, opkg-kmod-fs, opkg-kmod-netfilter", writable = false)
    settings("qos", "enable=true", writable = false)

    command("tools/ping", text("host"), text("count"), text("packetsize"), run = ::servicesPing)
    command("tools/traceroute", text("host"), run = ::servicesTraceroute)
}

private fun servicesPing(arguments: Map<String, String>): Flow<Item> = flow {
    val host = arguments["host"].orEmpty()
    val count = (arguments["count"]?.toIntOrNull() ?: 4).coerceAtLeast(1)
    val size = arguments["packetsize"]?.toIntOrNull() ?: 56
    val times = List(count) { wander(11_400, it, host.hashCode()) }
    emit(Item("0", mapOf("line" to "PING $host ($host) $size (${size + 28}) bytes of data.")))
    times.forEachIndexed { seq, micros ->
        delay(1000)
        emit(Item("${seq + 1}", mapOf("line" to "${size + 8} bytes from $host: icmp_req=${seq + 1}, ttl=58, time=${servicesMillis(micros)} ms.")))
    }
    val summary = listOf(
        "--- $host ping statistics ---",
        "$count packets transmitted, $count packets received, 0% packet loss,",
        "Round-trip min/avg/max = ${servicesMillis(times.min())}/${servicesMillis(times.sum() / count)}/${servicesMillis(times.max())} ms.",
    )
    summary.forEachIndexed { index, line -> emit(Item("${count + 1 + index}", mapOf("line" to line))) }
}

private fun servicesTraceroute(arguments: Map<String, String>): Flow<Item> = flow {
    val host = arguments["host"].orEmpty()
    emit(Item("0", mapOf("line" to "traceroute to $host ($host), 30 hops max, 38 byte packets")))
    listOf("203.0.113.1", "198.51.100.1", "198.51.100.17", "192.0.2.9", host).forEachIndexed { index, hop ->
        delay(700)
        val times = List(3) { servicesMillis(wander(1_900L * (index + 1), it, index)) }
        emit(Item("${index + 1}", mapOf("line" to "${index + 1}  $hop ($hop)  ${times.joinToString(" ms  ")} ms")))
    }
}

private fun servicesMillis(micros: Long) = "${micros / 1000}.${(micros % 1000).toString().padStart(3, '0')}"
