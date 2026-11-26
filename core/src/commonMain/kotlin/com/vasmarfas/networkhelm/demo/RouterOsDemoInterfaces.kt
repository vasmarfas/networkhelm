package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.Item
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val INTERFACES_ADVERTISED = "10M-baseT-half,10M-baseT-full,100M-baseT-half,100M-baseT-full,1000M-baseT-full"

private const val INTERFACES_WG_HOME = "gD8tQ1xKzP5mWv0YbLcN3rJ7sUe4HfAi2oTpXkM9C6w="

private val interfacesLoopProtect = arrayOf("loop-protect=default", "loop-protect-status=off", "loop-protect-send-interval=5s", "loop-protect-disable-time=5m")

private val interfacesTunnel = arrayOf("keepalive=10s,10", "dscp=inherit", "clamp-tcp-mss=true", "dont-fragment=no", "allow-fast-path=true", "ipsec-secret=")

private val interfacesSecurityHome = arrayOf(
    "authentication-types=wpa2-psk,wpa3-psk", "passphrase=demo-wifi-pass", "encryption=ccmp", "group-encryption=ccmp", "management-protection=allowed", "management-encryption=cmac", "wps=disable",
    "connect-priority=0/1", "disable-pmkid=false", "group-key-update=1h", "beacon-protection=false", "ft=true", "ft-over-ds=true", "ft-mobility-domain=0x4F4D", "ft-preserve-vlanid=true",
    "ft-reassociation-deadline=20ms", "dh-groups=19", "sae-pwe=both", "sae-anti-clogging-threshold=5", "sae-max-failure-rate=40",
)

private val interfacesAaa = arrayOf(
    "called-format=mac:ssid", "calling-format=mac", "interim-update=5m", "mac-caching=1h", "nas-identifier=demo-hap", "password-format=empty", "username-format=MAC",
)

private val interfacesWifiShared = interfacesSecurityHome.map { "security.$it" }.toTypedArray() + interfacesAaa.map { "aaa.$it" }.toTypedArray() +
    arrayOf("security=sec-home", "datapath=dp-lan", "datapath.bridge=bridge", "datapath.client-isolation=false", "steering=steer-home", "steering.rrm=true", "steering.wnm=true", "aaa=aaa-radius")

private val interfacesQueueCounters = counting()

private val interfacesBridgeExtras = arrayOf(
    "arp-timeout=auto", "admin-mac=", "max-learned-entries=auto", "forward-reserved-addresses=false", "fast-forward=true", "port-cost-mode=long", "region-name=", "region-revision=0",
    "max-message-age=20s", "forward-delay=15s", "transmit-hold-count=6", "max-hops=20", "ether-type=0x8100", "pvid=1", "frame-types=admit-all", "ingress-filtering=true", "mvrp=false",
    "igmp-version=2", "mld-version=1", "multicast-router=temporary-query", "multicast-querier=false", "startup-query-count=2", "last-member-query-count=2", "last-member-interval=1s",
    "membership-interval=4m20s", "querier-interval=4m15s", "query-interval=2m5s", "query-response-interval=10s", "startup-query-interval=31s250ms", "dhcp-snooping-vlan-ids=",
    "add-dhcp-option82=false", "dhcp-snooping-option82-policy=replace", "ip-source-guard=false", "arp-inspection=false", "dhcpv6-snooping=false", "ra-guard=false",
)

private val interfacesPortExtras = arrayOf(
    "learn=auto", "unknown-unicast-flood=true", "unknown-multicast-flood=true", "broadcast-flood=true", "multicast-router=temporary-query", "fast-leave=false", "priority=0x80", "edge=auto",
    "point-to-point=auto", "auto-isolate=false", "restricted-role=false", "restricted-tcn=false", "bpdu-guard=false", "ingress-filtering=true", "tag-stacking=false",
    "mvrp-registrar-state=normal", "mvrp-applicant-state=normal-participant", "trusted=false", "trusted-arp=false", "trusted-dhcpv6=false", "trusted-ra=false",
)

private val interfacesClientSettings = arrayOf("client-dns=10.66.66.1", "client-endpoint=203.0.113.50", "client-keepalive=25s", "client-listen-port=", "client-mtu=", "client-allowed-address=0.0.0.0/0")

private val interfacesQueueExtras = arrayOf(
    "dst=", "time=", "packet-marks=", "parent=none", "limit-at=0/0", "burst-limit=0/0", "burst-threshold=0/0", "burst-time=0s/0s", "priority=8/8", "bucket-size=0.1/0.1",
    "queue=default-small/default-small", "dropped=0/0", "pcq-queues=0/0", "total-limit-at=0", "total-max-limit=0", "total-priority=8", "total-bucket-size=0.1", "total-burst-limit=0",
    "total-burst-threshold=0", "total-burst-time=0s", "total-queue=default-small", "total-rate=0", "total-packet-rate=0", "total-queued-bytes=0", "total-queued-packets=0", "total-bytes=0",
    "total-packets=0", "total-dropped=0", "total-pcq-queues=0",
)

private fun interfacesStatus(up: String?, tx: Long, rx: Long, txPackets: Long, rxPackets: Long, down: String? = null, downs: Int = 0): Array<String> = listOfNotNull(
    down?.let { "last-link-down-time=$it" }, up?.let { "last-link-up-time=$it" }, "link-downs=$downs", "tx-byte=$tx", "rx-byte=$rx", "tx-packet=$txPackets", "rx-packet=$rxPackets",
    "tx-drop=0", "rx-drop=0", "tx-queue-drop=0", "tx-error=0", "rx-error=0",
).toTypedArray()

private val interfacesNeighbors = listOf(
    mapOf("interface" to "wifi1", "address" to "00:00:5E:00:53:B1", "ssid" to "NeighborNet", "channel" to "2437/n/Ce", "security" to "WPA2-PSK", "signal" to "-58", "sta-count" to "4"),
    mapOf("interface" to "wifi1", "address" to "00:00:5E:00:53:B2", "ssid" to "HomeWiFi-2G", "channel" to "2462/n/eC", "security" to "WPA2-PSK", "signal" to "-66", "sta-count" to "2"),
    mapOf("interface" to "wifi1", "address" to "00:00:5E:00:53:B3", "ssid" to "CafeGuest", "channel" to "2412/n/Ce", "security" to "", "signal" to "-74", "sta-count" to "9"),
    mapOf("interface" to "wifi2", "address" to "00:00:5E:00:53:B4", "ssid" to "NeighborNet-5G", "channel" to "5180/ax/Ceee", "security" to "WPA2-PSK WPA3-PSK", "signal" to "-61", "sta-count" to "3"),
    mapOf("interface" to "wifi2", "address" to "00:00:5E:00:53:B5", "ssid" to "Office5", "channel" to "5260/ac/Ceee", "security" to "WPA2-PSK", "signal" to "-70", "sta-count" to "6"),
    mapOf("interface" to "wifi2", "address" to "00:00:5E:00:53:B6", "ssid" to "HomeWiFi-5G", "channel" to "5745/ax/Ceee", "security" to "WPA2-PSK", "signal" to "-77", "sta-count" to "1"),
)

private val interfacesClients = mapOf(
    "*1" to ("yPq3Zn8WcB1dH6sLm0TeRk4VaJ9uXoFiA2gNxYbC5Ew=" to "10.66.66.2/32"),
    "*2" to ("Qm5vR2cJx8LpZ0nWdH7sTa3YbEk6UfGiO9tNhMyA1Co=" to "10.66.66.3/32"),
)

internal fun DemoBuilder.interfacesDemo() {
    list("interface", live = { row, tick -> interfaceRates(row["name"].orEmpty(), row["comment"] == "WAN", row["running"] != "false", tick) }) {
        row(
            "name=ether1", "default-name=ether1", "type=ether", "mtu=1500", "actual-mtu=1500", "l2mtu=1584", "mac-address=00:00:5E:00:53:01", "running=true", "disabled=false", "slave=false", "comment=WAN",
            *interfacesStatus("2026-10-31 09:14:53", 6_912_008_334, 48_201_334_118, 21_908_114, 38_114_902, "2026-10-31 09:14:41", 1),
        )
        row(
            "name=ether2", "default-name=ether2", "type=ether", "mtu=1500", "actual-mtu=1500", "l2mtu=1584", "mac-address=00:00:5E:00:53:02", "running=true", "disabled=false", "slave=true",
            *interfacesStatus("2026-10-27 22:01:58", 18_204_455, 4_118_900, 41_204, 29_881),
        )
        row(
            "name=ether3", "default-name=ether3", "type=ether", "mtu=1500", "actual-mtu=1500", "l2mtu=1584", "mac-address=00:00:5E:00:53:03", "running=true", "disabled=false", "slave=true",
            *interfacesStatus("2026-10-27 22:01:58", 3_118_204_900, 21_340_118_221, 4_204_118, 17_602_330),
        )
        row(
            "name=ether4", "default-name=ether4", "type=ether", "mtu=1500", "actual-mtu=1500", "l2mtu=1584", "mac-address=00:00:5E:00:53:04", "running=false", "disabled=false", "slave=true",
            *interfacesStatus("2026-10-29 18:30:11", 902_114, 411_208, 2_204, 1_908, "2026-10-29 19:44:02", 1),
        )
        row(
            "name=ether5", "default-name=ether5", "type=ether", "mtu=1500", "actual-mtu=1500", "l2mtu=1584", "mac-address=00:00:5E:00:53:05", "running=true", "disabled=false", "slave=true",
            *interfacesStatus("2026-10-27 22:01:58", 41_503_228_117, 3_102_448_090, 33_804_221, 12_988_104),
        )
        row(
            "name=wifi1", "default-name=wifi1", "type=wifi", "mtu=1500", "actual-mtu=1500", "l2mtu=1560", "mac-address=00:00:5E:00:53:06", "running=true", "disabled=false", "slave=true", "comment=2.4 GHz",
            *interfacesStatus("2026-10-27 22:02:21", 411_882_090, 188_112_004, 902_118, 611_204),
        )
        row(
            "name=wifi2", "default-name=wifi2", "type=wifi", "mtu=1500", "actual-mtu=1500", "l2mtu=1560", "mac-address=00:00:5E:00:53:07", "running=true", "disabled=false", "slave=true", "comment=5 GHz",
            *interfacesStatus("2026-10-27 22:02:21", 9_811_204_338, 1_204_998_117, 8_204_118, 5_118_902),
        )
        row(
            "name=bridge", "type=bridge", "mtu=1500", "actual-mtu=1500", "l2mtu=1584", "mac-address=00:00:5E:00:53:02", "running=true", "disabled=false", "slave=false", "comment=LAN",
            *interfacesStatus("2026-10-27 22:01:56", 47_903_118_204, 7_228_410_990, 36_918_204, 21_302_118),
        )
        row(
            "name=wg-home", "type=wg", "mtu=1420", "actual-mtu=1420", "running=true", "disabled=false", "slave=false", "comment=road warriors",
            *interfacesStatus("2026-10-27 22:02:30", 14_884_700, 96_128_044, 61_204, 118_902),
        )
        row(
            "name=eoip-office", "type=eoip", "mtu=1500", "actual-mtu=1500", "l2mtu=65535", "mac-address=00:00:5E:00:53:40", "running=true", "disabled=false", "slave=false", "comment=office router",
            *interfacesStatus("2026-10-31 09:15:19", 220_118_902, 98_220_117, 301_204, 188_118, "2026-10-31 09:15:03", 1),
        )
        row(
            "name=eoip-lab", "type=eoip", "mtu=1500", "actual-mtu=1500", "l2mtu=65535", "mac-address=00:00:5E:00:53:41", "running=false", "disabled=false", "slave=false", "comment=lab router, link is down",
            *interfacesStatus("2026-10-29 07:30:05", 4_118_204, 2_902_118, 8_204, 6_118, "2026-10-30 23:41:12", 3),
        )
        row(
            "name=ipip-lab", "type=ipip-tunnel", "mtu=1480", "actual-mtu=1480", "running=true", "disabled=false", "slave=false", "comment=lab uplink",
            *interfacesStatus("2026-10-31 09:15:20", 18_204_118, 9_118_204, 41_204, 30_118, "2026-10-31 09:15:03", 1),
        )
        row(
            "name=gre-dc", "type=gre-tunnel", "mtu=1476", "actual-mtu=1476", "running=true", "disabled=false", "slave=false", "comment=data center",
            *interfacesStatus("2026-10-31 09:15:20", 1_204_118_902, 3_811_204_338, 1_602_118, 3_204_118, "2026-10-31 09:15:03", 1),
        )
        row(
            "name=gre-backup", "type=gre-tunnel", "mtu=1476", "actual-mtu=1476", "running=false", "disabled=true", "slave=false", "comment=standby path",
            *interfacesStatus(null, 0, 0, 0, 0),
        )
        row(
            "name=vlan89-guest", "type=vlan", "mtu=1500", "actual-mtu=1500", "l2mtu=1580", "mac-address=00:00:5E:00:53:02", "running=true", "disabled=false", "slave=false", "comment=guest network",
            *interfacesStatus("2026-10-27 22:01:57", 288_118_204, 66_204_118, 402_118, 188_204),
        )
        row(
            "name=vlan1002-iptv", "type=vlan", "mtu=1500", "actual-mtu=1500", "l2mtu=1580", "mac-address=00:00:5E:00:53:01", "running=true", "disabled=false", "slave=false", "comment=IPTV from the provider",
            *interfacesStatus("2026-10-31 09:14:53", 1_204_118, 8_811_204_338, 12_204, 6_602_118, "2026-10-31 09:14:41", 1),
        )
        row(
            "name=vxlan-lab", "type=vxlan", "mtu=1500", "actual-mtu=1500", "l2mtu=65535", "mac-address=00:00:5E:00:53:42", "running=true", "disabled=false", "slave=false", "comment=hypervisor segment",
            *interfacesStatus("2026-10-27 22:02:40", 9_204_118, 7_118_204, 22_204, 18_118),
        )
        row(
            "name=vrrp-lan", "type=vrrp", "mtu=1500", "actual-mtu=1500", "mac-address=00:00:5E:00:01:0A", "running=true", "disabled=false", "slave=false", "comment=LAN gateway address",
            *interfacesStatus("2026-10-27 22:02:45", 16_604_118, 0, 276_204, 0),
        )
        row(
            "name=veth1", "type=veth", "mtu=1500", "actual-mtu=1500", "mac-address=00:00:5E:00:53:46", "running=true", "disabled=false", "slave=false", "comment=DNS container",
            *interfacesStatus("2026-10-28 08:12:10", 411_204_118, 188_118_204, 3_204_118, 2_118_204),
        )
        row(
            "name=macsec-lab", "type=macsec", "mtu=1500", "running=false", "disabled=true", "slave=false", "comment=link encryption test",
            *interfacesStatus(null, 0, 0, 0, 0),
        )
        row(
            "name=macvlan1", "type=macvlan", "mtu=1500", "mac-address=00:00:5E:00:53:47", "running=true", "disabled=false", "slave=false",
            *interfacesStatus("2026-10-27 22:01:59", 2_204_118, 1_118_204, 18_204, 12_118),
        )
        row(
            "name=bond-nas", "type=bond", "mtu=1500", "actual-mtu=1500", "l2mtu=1584", "mac-address=00:00:5E:00:53:43", "running=false", "disabled=true", "slave=false", "comment=to the NAS, not enabled yet",
            *interfacesStatus(null, 0, 0, 0, 0),
        )
        row(
            "name=lte1", "type=lte", "mtu=1500", "mac-address=00:00:5E:00:53:48", "running=false", "disabled=true", "slave=false", "comment=USB modem, backup uplink",
            *interfacesStatus(null, 0, 0, 0, 0),
        )
        row(
            "name=pppoe-out1", "type=pppoe-out", "mtu=1480", "actual-mtu=1480", "running=false", "disabled=true", "slave=false", "comment=spare provider login",
            *interfacesStatus(null, 0, 0, 0, 0),
        )
        row(
            "name=l2tpv3-lab", "type=l2tp-ether", "mtu=1500", "actual-mtu=1500", "l2mtu=1600", "mac-address=00:00:5E:00:53:51", "running=false", "disabled=false", "slave=false", "comment=lab pseudowire, peer is down",
            *interfacesStatus("2026-10-29 07:30:01", 902_118, 411_204, 3_204, 2_118, "2026-10-30 23:41:08", 2),
        )
        row(
            "name=mesh1", "type=mesh", "mtu=1500", "actual-mtu=1500", "l2mtu=1500", "mac-address=00:00:5E:00:53:49", "running=true", "disabled=false", "slave=false", "comment=joins the sites over EoIP",
            *interfacesStatus("2026-10-27 22:03:02", 118_204_118, 66_118_204, 188_204, 102_118),
        )
        row(
            "name=<pppoe-lab-user>", "type=pppoe-in", "mtu=1480", "actual-mtu=1480", "running=true", "disabled=false", "dynamic=true", "slave=false",
            *interfacesStatus("2026-10-31 08:30:18", 418_204_118, 28_118_204, 402_118, 188_204),
        )
        row(
            "name=lo", "type=bridge", "mtu=1500", "actual-mtu=1500", "l2mtu=65535", "mac-address=00:00:5E:00:53:08", "running=true", "disabled=false", "slave=false", "comment=loopback",
            *interfacesStatus("2026-10-27 22:01:56", 0, 0, 0, 0),
        )
        row(
            "name=vpls-office", "type=vpls", "mtu=1500", "actual-mtu=1500", "l2mtu=1500", "mac-address=00:00:5E:00:53:B1", "running=true", "disabled=false", "slave=true",
            *interfacesStatus("2026-10-27 22:04:12", 88_211_904, 121_904_118, 88_120, 121_904, "2026-10-27 22:03:40", 1),
        )
        row(
            "name=veth2", "type=veth", "mtu=1500", "actual-mtu=1500", "mac-address=00:00:5E:00:53:4A", "running=false", "disabled=false", "slave=false", "comment=MQTT broker container, stopped",
            *interfacesStatus("2026-10-28 08:12:30", 12_204_118, 8_118_204, 22_118, 18_204, "2026-10-29 21:05:11", 1),
        )
    }
    list("interface/ethernet") {
        for (n in 1..5) {
            row(
                "name=ether$n", "default-name=ether$n", "mtu=1500", "l2mtu=1584", "mac-address=00:00:5E:00:53:0$n", "orig-mac-address=00:00:5E:00:53:0$n", "arp=enabled",
                "auto-negotiation=true", "speed=${if (n == 4) "10Mbps" else "1Gbps"}", "full-duplex=true", "tx-flow-control=off", "rx-flow-control=off",
                "running=${n != 4}", "disabled=false", "slave=${n != 1}",
                "arp-timeout=auto", "arp-accept=false", "advertise=$INTERFACES_ADVERTISED${if (n == 1) ",2.5G-baseT" else ""}", "mdix-enable=true", "cable-settings=default",
                "disable-running-check=false", *interfacesLoopProtect,
            )
        }
    }
    list("interface/bridge") {
        row(
            "name=bridge", "mtu=auto", "actual-mtu=1500", "l2mtu=1584", "mac-address=00:00:5E:00:53:02", "arp=enabled", "auto-mac=true", "protocol-mode=rstp", "priority=0x8000", "vlan-filtering=false",
            "igmp-snooping=false", "dhcp-snooping=false", "ageing-time=5m", "running=true", "disabled=false", "comment=LAN", *interfacesBridgeExtras,
        )
        row(
            "name=lo", "mtu=auto", "actual-mtu=1500", "l2mtu=65535", "mac-address=00:00:5E:00:53:08", "arp=enabled", "auto-mac=true", "protocol-mode=none", "priority=0x8000", "vlan-filtering=false",
            "igmp-snooping=false", "dhcp-snooping=false", "ageing-time=5m", "running=true", "disabled=false", "comment=loopback", *interfacesBridgeExtras,
        )
    }
    list("interface/bridge/port", ordered = true) {
        for (name in listOf("ether2", "ether3", "ether4", "ether5", "wifi1", "wifi2")) {
            row(
                "interface=$name", "bridge=bridge", "pvid=1", "frame-types=admit-all", "horizon=none", "hw=true", "forwarding=${name != "ether4"}", "role=${if (name == "ether4") "disabled-port" else "designated-port"}",
                "inactive=false", "dynamic=false", "disabled=false", "path-cost=10", "internal-path-cost=10", "edge-port=${name != "ether4"}",
                "point-to-point-port=${name.startsWith("ether") && name != "ether4"}", "actual-path-cost=20000", "root-path-cost=0", *interfacesPortExtras,
            )
        }
        row(
            "interface=vpls-office", "bridge=bridge", "pvid=1", "frame-types=admit-all", "horizon=1", "hw=false", "forwarding=true", "role=designated-port", "inactive=false", "dynamic=true",
            "disabled=false", "path-cost=50", "internal-path-cost=50", "edge-port=false", "point-to-point-port=true", "actual-path-cost=50", "root-path-cost=0", *interfacesPortExtras,
        )
    }
    list("interface/wireguard") {
        row(
            "name=wg-home", "mtu=1420", "listen-port=13231", "public-key=Xk3aPq9vN2mZr8Wc5LtH0eYdB7fUo1sGJiA4nKxVQhM=", "running=true", "disabled=false",
            "private-key=$INTERFACES_WG_HOME", "vrf=main",
        )
    }
    list("interface/wireguard/peers") {
        row(
            "name=phone-anna", "interface=wg-home", "public-key=r8Zq2m7oQ1wH0dYk5nLp9aVtB4cJfS6uEiGxM3zNhWA=", "allowed-address=10.66.66.2/32", "endpoint-port=0", "current-endpoint-address=198.51.100.77",
            "current-endpoint-port=51820", "last-handshake=1m12s", "rx=1843201", "tx=902211", "persistent-keepalive=25s", "disabled=false",
            "endpoint-address=", "private-key=${interfacesClients.getValue("*1").first}", "preshared-key=", "responder=false", "client-address=${interfacesClients.getValue("*1").second}", *interfacesClientSettings,
        )
        row(
            "name=laptop-work", "interface=wg-home", "public-key=Hd5Tn0cYqK8vZb3JxW2sPm6eLr9aUo4FiGt1NzMkBvE=", "allowed-address=10.66.66.3/32", "endpoint-port=0", "current-endpoint-address=192.0.2.88",
            "current-endpoint-port=40412", "last-handshake=3h41m", "rx=88430211", "tx=12099871", "persistent-keepalive=25s", "disabled=false",
            "endpoint-address=", "private-key=${interfacesClients.getValue("*2").first}", "preshared-key=", "responder=false", "client-address=${interfacesClients.getValue("*2").second}", *interfacesClientSettings,
        )
    }
    list(
        "queue/simple",
        live = { row, tick ->
            row["rate"]?.split('/')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 2 }
                ?.let { (up, down) -> mapOf("rate" to "${wander(up, tick, row.id.hashCode())}/${wander(down, tick, row.id.hashCode() + 1)}") }.orEmpty()
        },
    ) {
        row(
            "name=guest", "target=192.168.89.0/24", "max-limit=10M/10M", "rate=0/0", "queued-bytes=0", "disabled=false", "dynamic=false", "invalid=false",
            "bytes=0/0", "packets=0/0", "packet-rate=0/0", "queued-packets=0/0", *interfacesQueueExtras,
        )
        row(
            "name=iot", "target=192.168.88.121/32", "max-limit=1M/1M", "rate=2304/1664", "queued-bytes=0", "disabled=false", "dynamic=false", "invalid=false",
            "bytes=18204118/9118204", "packets=88204/61118", "packet-rate=3/2", "queued-packets=0/0", *interfacesQueueExtras,
        )
        row(
            "name=ps5", "target=192.168.88.130/32", "max-limit=100M/300M", "rate=84000/410000", "queued-bytes=0", "disabled=false", "dynamic=false", "invalid=false", "comment=gaming",
            "bytes=611204118/9811204338", "packets=4118204/8204118", "packet-rate=12/57", "queued-packets=0/0", *interfacesQueueExtras,
        )
    }

    list("interface/list") {
        row("name=all", "include=", "exclude=", "builtin=true", "dynamic=false", "comment=contains all interfaces")
        row("name=none", "include=", "exclude=", "builtin=true", "dynamic=false", "comment=contains no interfaces")
        row("name=dynamic", "include=", "exclude=", "builtin=true", "dynamic=false", "comment=contains dynamic interfaces")
        row("name=static", "include=", "exclude=", "builtin=true", "dynamic=false", "comment=contains static interfaces")
        row("name=LAN", "include=", "exclude=", "builtin=false", "dynamic=false", "comment=defconf")
        row("name=WAN", "include=", "exclude=", "builtin=false", "dynamic=false", "comment=defconf")
    }
    list("interface/list/member", fields = listOf(choice("list", "LAN", "WAN"))) {
        row("list=LAN", "interface=bridge", "dynamic=false", "disabled=false", "comment=defconf")
        row("list=WAN", "interface=ether1", "dynamic=false", "disabled=false", "comment=defconf")
        row("list=LAN", "interface=wg-home", "dynamic=false", "disabled=false")
    }
    settings("interface/detect-internet", "detect-interface-list=all", "lan-interface-list=LAN", "wan-interface-list=WAN", "internet-interface-list=WAN")
    command("interface/ethernet/monitor", text("numbers"), run = ::interfacesEthernetMonitor)

    list("interface/eoip") {
        row(
            "name=eoip-office", "type=eoip", "mtu=1500", "actual-mtu=1500", "l2mtu=65535", "mac-address=00:00:5E:00:53:40", "arp=enabled", "arp-timeout=auto", "arp-accept=false", *interfacesLoopProtect,
            "local-address=203.0.113.50", "remote-address=198.51.100.20", "tunnel-id=10", *interfacesTunnel, "running=true", "disabled=false", "comment=office router",
        )
        row(
            "name=eoip-lab", "type=eoip", "mtu=1500", "actual-mtu=1500", "l2mtu=65535", "mac-address=00:00:5E:00:53:41", "arp=enabled", "arp-timeout=auto", "arp-accept=false", *interfacesLoopProtect,
            "local-address=203.0.113.50", "remote-address=192.0.2.61", "tunnel-id=11", *interfacesTunnel, "running=false", "disabled=false", "comment=lab router, link is down",
        )
    }
    list("interface/ipip") {
        row(
            "name=ipip-lab", "type=ipip-tunnel", "mtu=1480", "actual-mtu=1480", "local-address=203.0.113.50", "remote-address=192.0.2.60", *interfacesTunnel,
            "running=true", "disabled=false", "comment=lab uplink",
        )
    }
    list("interface/gre") {
        row(
            "name=gre-dc", "type=gre-tunnel", "mtu=1476", "actual-mtu=1476", "local-address=203.0.113.50", "remote-address=198.51.100.30", *interfacesTunnel,
            "running=true", "disabled=false", "comment=data center",
        )
        row(
            "name=gre-backup", "type=gre-tunnel", "mtu=1476", "actual-mtu=1476", "local-address=203.0.113.50", "remote-address=198.51.100.31", *interfacesTunnel,
            "running=false", "disabled=true", "comment=standby path",
        )
    }
    list("interface/vlan") {
        row(
            "name=vlan89-guest", "type=vlan", "mtu=1500", "actual-mtu=1500", "l2mtu=1580", "mac-address=00:00:5E:00:53:02", "forced-mac-address=", "arp=enabled", "arp-timeout=auto", "arp-accept=false",
            *interfacesLoopProtect, "vlan-id=89", "interface=bridge", "use-service-tag=false", "mvrp=false", "running=true", "disabled=false", "comment=guest network",
        )
        row(
            "name=vlan1002-iptv", "type=vlan", "mtu=1500", "actual-mtu=1500", "l2mtu=1580", "mac-address=00:00:5E:00:53:01", "forced-mac-address=", "arp=enabled", "arp-timeout=auto", "arp-accept=false",
            *interfacesLoopProtect, "vlan-id=1002", "interface=ether1", "use-service-tag=false", "mvrp=false", "running=true", "disabled=false", "comment=IPTV from the provider",
        )
    }
    list("interface/vxlan") {
        row(
            "name=vxlan-lab", "type=vxlan", "mtu=1500", "actual-mtu=1500", "l2mtu=65535", "mac-address=00:00:5E:00:53:42", "arp=enabled", "arp-timeout=auto", "arp-accept=false", *interfacesLoopProtect,
            "vni=10010", "group=", "interface=all", "port=4789", "local-address=203.0.113.50", "dont-fragment=inherit", "max-fdb-size=4096", "ttl=auto", "vteps-ip-version=ipv4", "vtep-vrf=main",
            "allow-fast-path=true", "learning=true", "checksum=false", "rem-csum=false", "hw=true", "bridge=", "bridge-pvid=1", "running=true", "disabled=false", "comment=hypervisor segment",
        )
    }
    list("interface/vrrp") {
        row(
            "name=vrrp-lan", "type=vrrp", "mtu=1500", "actual-mtu=1500", "mac-address=00:00:5E:00:01:0A", "arp=enabled", "arp-timeout=auto", "interface=bridge", "vrid=10", "priority=100",
            "interval=1s", "preemption-mode=true", "authentication=none", "password=", "version=3", "v3-protocol=ipv4", "v3-checksum-as-v2=false", "group-authority=none", "group-master=none",
            "sync-connection-tracking=false", "remote-address=0.0.0.0", "connection-tracking-port=8275", "connection-tracking-mode=ignore", "on-master=", "on-backup=", "on-fail=",
            "running=true", "disabled=false", "invalid=false", "comment=LAN gateway address",
        )
    }
    list("interface/veth") {
        row("name=veth1", "type=veth", "address=172.17.0.2/24", "gateway=172.17.0.1", "gateway6=", "mac-address=00:00:5E:00:53:46", "running=true", "disabled=false", "comment=DNS container")
        row("name=veth2", "type=veth", "address=172.17.0.3/24", "gateway=172.17.0.1", "gateway6=", "mac-address=00:00:5E:00:53:4A", "running=false", "disabled=false", "comment=MQTT broker container, stopped")
    }
    list("interface/macsec") {
        row(
            "name=macsec-lab", "type=macsec", "mtu=1500", "interface=ether4", "profile=macsec-lab", "cak=", "ckn=", "status=disabled", "arp=enabled", "arp-timeout=auto",
            "running=false", "disabled=true", "comment=link encryption test",
        )
    }
    list("interface/macvlan", fields = listOf(choice("mode", "private", "bridge", "vepa", "passthru"))) {
        row(
            "name=macvlan1", "type=macvlan", "mtu=1500", "interface=bridge", "mode=private", "mac-address=00:00:5E:00:53:47", "arp=enabled", "arp-timeout=auto",
            "running=true", "disabled=false",
        )
    }
    list("interface/bonding", fields = listOf(choice("mode", "802.3ad", "active-backup", "balance-alb", "balance-rr", "balance-tlb", "balance-xor", "broadcast"))) {
        row(
            "name=bond-nas", "type=bond", "mtu=1500", "actual-mtu=1500", "l2mtu=1584", "mac-address=00:00:5E:00:53:43", "arp=enabled", "arp-timeout=auto", "forced-mac-address=",
            "slaves=ether4,ether5", "mode=802.3ad", "primary=none", "link-monitoring=mii", "transmit-hash-policy=layer-2", "min-links=0", "down-delay=0ms", "up-delay=0ms", "lacp-rate=30secs",
            "lacp-user-key=0", "mlag-id=", "lacp-mode=active", "lacp-system-id=", "lacp-system-priority=65535", "mii-interval=100ms", "arp-interval=100ms", "arp-ip-targets=",
            "running=false", "disabled=true", "comment=to the NAS, not enabled yet",
        )
    }
    list("interface/lte") {
        row(
            "name=lte1", "type=lte", "mtu=1500", "mac-address=00:00:5E:00:53:48", "apn-profiles=default", "pin=", "network-mode=lte,3g", "allow-roaming=false", "band=", "modem-init=",
            "running=false", "disabled=true", "comment=USB modem, backup uplink",
        )
    }
    list("interface/pppoe-client") {
        row(
            "name=pppoe-out1", "type=pppoe-out", "interface=ether1", "user=demo-user", "password=demo-pass", "service-name=", "ac-name=", "profile=default-encryption", "max-mtu=auto", "max-mru=auto",
            "mrru=disabled", "keepalive-timeout=10", "add-default-route=false", "default-route-distance=1", "dial-on-demand=false", "use-peer-dns=false", "allow=pap,chap,mschap1,mschap2",
            "running=false", "disabled=true", "comment=spare provider login",
        )
    }

    list("interface/ethernet/switch") {
        row("name=switch1", "type=IPQ-PPE", "l3-hw-offloading=false", "qos-hw-offloading=false", "invalid=false")
    }

    list("interface/wifi") {
        row(
            "name=wifi1", "default-name=wifi1", "type=wifi", "mtu=1500", "actual-mtu=1500", "l2mtu=1560", "mac-address=00:00:5E:00:53:06", "arp=enabled", "arp-timeout=auto", "radio-mac=00:00:5E:00:53:06",
            "master-interface=", "disable-running-check=false", "master=true", "bound=true", "inactive=false", "running=true", "disabled=false", "comment=2.4 GHz",
            "configuration=cfg-2g", "configuration.mode=ap", "configuration.ssid=DemoNet", "configuration.country=Russia", "configuration.installation=indoor", "configuration.hide-ssid=false",
            "configuration.max-clients=128", "configuration.antenna-gain=0", "configuration.chains=0,1", "configuration.tx-chains=0,1", "configuration.distance=indoors",
            "configuration.beacon-interval=100ms", "configuration.dtim-period=2", "configuration.multicast-enhance=disabled", "configuration.qos-classifier=priority",
            "configuration.station-roaming=enabled", "configuration.hw-protection-mode=none", "configuration.manager=local",
            "channel=ch-2g", "channel.band=2ghz-ax", "channel.width=20/40mhz", "channel.frequency=2412,2437,2462", "channel.secondary-frequency=disabled", "channel.skip-dfs-channels=disabled",
            "channel.deprioritize-unii-3-4=false", *interfacesWifiShared,
        )
        row(
            "name=wifi2", "default-name=wifi2", "type=wifi", "mtu=1500", "actual-mtu=1500", "l2mtu=1560", "mac-address=00:00:5E:00:53:07", "arp=enabled", "arp-timeout=auto", "radio-mac=00:00:5E:00:53:07",
            "master-interface=", "disable-running-check=false", "master=true", "bound=true", "inactive=false", "running=true", "disabled=false", "comment=5 GHz",
            "configuration=cfg-5g", "configuration.mode=ap", "configuration.ssid=DemoNet-5G", "configuration.country=Russia", "configuration.installation=indoor", "configuration.hide-ssid=false",
            "configuration.max-clients=128", "configuration.antenna-gain=0", "configuration.chains=0,1", "configuration.tx-chains=0,1", "configuration.distance=indoors",
            "configuration.beacon-interval=100ms", "configuration.dtim-period=2", "configuration.multicast-enhance=disabled", "configuration.qos-classifier=priority",
            "configuration.station-roaming=enabled", "configuration.hw-protection-mode=none", "configuration.manager=local",
            "channel=ch-5g", "channel.band=5ghz-ax", "channel.width=20/40/80mhz", "channel.frequency=5180,5220", "channel.secondary-frequency=disabled", "channel.skip-dfs-channels=10min-cac",
            "channel.deprioritize-unii-3-4=false", *interfacesWifiShared,
        )
    }
    command("interface/wifi/scan", text("number"), text("duration"), text("frequency"), text("rounds"), text("save-file"), run = ::interfacesScan)
    list("interface/wifi/network") {
        row(
            "ssid=DemoGuest", "labels=5ghz", "mlo=false", "mode=ap", "hide-ssid=false", "max-clients=32", "beacon-interval=100ms", "dtim-period=2", "multicast-enhance=disabled",
            "qos-classifier=priority", "station-roaming=enabled", "hw-protection-mode=none", "security.authentication-types=wpa2-psk", "security.encryption=ccmp", "security.wps=disable",
            "security.ft=false", "datapath.bridge=bridge", "datapath.vlan-id=89", "datapath.client-isolation=true", "disabled=true", "comment=guest network, not applied yet",
        )
    }
    list("interface/wifi/network/radio") {
        row("labels=2ghz", "extra-labels=", "channel.band=2ghz-ax", "channel.width=20/40mhz", "channel.frequency=2412,2437,2462", "configuration.country=Russia", "configuration.chains=0,1", "disabled=true")
        row("labels=5ghz", "extra-labels=", "channel.band=5ghz-ax", "channel.width=20/40/80mhz", "channel.frequency=5180,5220", "configuration.country=Russia", "configuration.chains=0,1", "disabled=true")
    }
    list("interface/wifi/configuration", fields = listOf(choice("mode", "ap", "station", "station-bridge", "station-pseudobridge"))) {
        row(
            "name=cfg-2g", "mode=ap", "ssid=DemoNet", "country=Russia", "installation=indoor", "hide-ssid=false", "max-clients=128", "antenna-gain=0", "chains=0,1", "tx-chains=0,1", "distance=indoors",
            "beacon-interval=100ms", "dtim-period=2", "multicast-enhance=disabled", "qos-classifier=priority", "station-roaming=enabled", "hw-protection-mode=none", "manager=local",
            "channel=ch-2g", "security=sec-home", "datapath=dp-lan", "steering=steer-home",
        )
        row(
            "name=cfg-5g", "mode=ap", "ssid=DemoNet-5G", "country=Russia", "installation=indoor", "hide-ssid=false", "max-clients=128", "antenna-gain=0", "chains=0,1", "tx-chains=0,1", "distance=indoors",
            "beacon-interval=100ms", "dtim-period=2", "multicast-enhance=disabled", "qos-classifier=priority", "station-roaming=enabled", "hw-protection-mode=none", "manager=local",
            "channel=ch-5g", "security=sec-home", "datapath=dp-lan", "steering=steer-home",
        )
        row(
            "name=cfg-guest", "mode=ap", "ssid=DemoGuest", "country=Russia", "installation=indoor", "hide-ssid=false", "max-clients=32", "antenna-gain=0", "chains=0,1", "tx-chains=0,1", "distance=indoors",
            "beacon-interval=100ms", "dtim-period=2", "multicast-enhance=disabled", "qos-classifier=priority", "station-roaming=disabled", "hw-protection-mode=none", "manager=capsman",
            "channel=", "security=sec-guest", "datapath=dp-guest", "steering=", "comment=pushed to the access points",
        )
    }
    list("interface/wifi/channel") {
        row("name=ch-2g", "band=2ghz-ax", "width=20/40mhz", "frequency=2412,2437,2462", "secondary-frequency=disabled", "skip-dfs-channels=disabled", "deprioritize-unii-3-4=false")
        row("name=ch-5g", "band=5ghz-ax", "width=20/40/80mhz", "frequency=5180,5220", "secondary-frequency=disabled", "skip-dfs-channels=10min-cac", "deprioritize-unii-3-4=false")
        row("name=ch-5g-dfs", "band=5ghz-ax", "width=20/40/80mhz", "frequency=5260,5500", "secondary-frequency=disabled", "skip-dfs-channels=disabled", "deprioritize-unii-3-4=false", "comment=with radar detection")
    }
    list("interface/wifi/security") {
        row("name=sec-home", *interfacesSecurityHome)
        row(
            "name=sec-guest", "authentication-types=wpa2-psk", "passphrase=demo-guest-pass", "encryption=ccmp", "group-encryption=ccmp", "management-protection=disabled", "wps=disable",
            "connect-priority=0/1", "disable-pmkid=false", "group-key-update=1h", "beacon-protection=false", "ft=false", "ft-over-ds=false",
        )
    }
    list("interface/wifi/aaa") {
        row("name=aaa-radius", *interfacesAaa)
    }
    list("interface/wifi/datapath") {
        row("name=dp-lan", "bridge=bridge", "vlan-id=", "client-isolation=false")
        row("name=dp-guest", "bridge=bridge", "vlan-id=89", "client-isolation=true")
    }
    list("interface/wifi/interworking") {
        row("name=iw-home", "internet=true", "network-type=private", "venue=residential-private-residence", "uesa=false", "esr=false", "hotspot20=false")
    }
    list("interface/wifi/steering") {
        row("name=steer-home", "neighbor-group=", "rrm=true", "wnm=true")
    }
    list(
        "interface/wifi/registration-table",
        writable = false,
        live = { row, tick -> row["signal"]?.toLongOrNull()?.let { mapOf("signal" to "${wander(it, tick, row.id.hashCode())}") }.orEmpty() },
    ) {
        row(
            "interface=wifi2", "ssid=DemoNet-5G", "mac-address=00:00:5E:00:53:65", "uptime=3h12m40s", "last-activity=120ms", "signal=-48", "auth-type=wpa3-psk", "band=5ghz-ax",
            "tx-rate=1.2Gbps", "rx-rate=864.7Mbps", "packets=301204,142993", "bytes=412809221,88120344",
        )
        row(
            "interface=wifi2", "ssid=DemoNet-5G", "mac-address=00:00:5E:00:53:66", "uptime=41m18s", "last-activity=4s", "signal=-57", "auth-type=wpa3-psk", "band=5ghz-ax",
            "tx-rate=960.7Mbps", "rx-rate=720.5Mbps", "packets=30288,19022", "bytes=18002133,2200112",
        )
        row(
            "interface=wifi2", "ssid=DemoNet-5G", "mac-address=00:00:5E:00:53:67", "uptime=1h5m12s", "last-activity=40ms", "signal=-63", "auth-type=wpa2-psk", "band=5ghz-ax",
            "tx-rate=573.5Mbps", "rx-rate=458.8Mbps", "packets=88410,40113", "bytes=96110442,6021180",
        )
        row(
            "interface=wifi1", "ssid=DemoNet", "mac-address=00:00:5E:00:53:79", "uptime=9d2h5m", "last-activity=1s", "signal=-71", "auth-type=wpa2-psk", "band=2ghz-n",
            "tx-rate=72.2Mbps", "rx-rate=65Mbps", "packets=44120,38011", "bytes=3204411,611900",
        )
    }
    list("interface/wifi/access-list", fields = listOf(choice("action", "accept", "reject", "query-radius"))) {
        row("interface=wifi2", "mac-address=00:00:5E:00:53:66", "ssid-regexp=", "action=accept", "signal-range=", "time=", "disabled=false", "comment=Anna's phone, always allowed")
        row("interface=", "mac-address=00:00:5E:00:53:67", "ssid-regexp=", "action=reject", "signal-range=", "time=22h-6h,mon,tue,wed,thu,fri", "disabled=false", "comment=school nights")
        row("interface=", "mac-address=", "ssid-regexp=", "action=reject", "signal-range=-120..-85", "time=", "disabled=false", "comment=drop clients with a weak signal")
    }
    list(
        "interface/wifi/provisioning",
        ordered = true,
        fields = listOf(choice("action", "create-disabled", "create-enabled", "create-dynamic-enabled", "none")),
    ) {
        row("action=create-dynamic-enabled", "radio-mac=00:00:00:00:00:00", "supported-bands=2ghz-ax", "master-configuration=cfg-2g", "slave-configurations=cfg-guest", "disabled=false")
        row("action=create-dynamic-enabled", "radio-mac=00:00:00:00:00:00", "supported-bands=5ghz-ax", "master-configuration=cfg-5g", "slave-configurations=", "disabled=false")
    }
    list("interface/wifi/radio", writable = false) {
        row(
            "radio-mac=00:00:5E:00:53:06", "interface=wifi1", "cap=", "tx-chains=0,1", "rx-chains=0,1", "bands=2ghz-b,2ghz-g,2ghz-n,2ghz-ax",
            "ciphers=tkip,ccmp,gcmp,ccmp-256,gcmp-256,cmac,gmac,cmac-256,gmac-256", "countries=Latvia,Lithuania,Poland,Russia,United States",
        )
        row(
            "radio-mac=00:00:5E:00:53:07", "interface=wifi2", "cap=", "tx-chains=0,1", "rx-chains=0,1", "bands=5ghz-a,5ghz-n,5ghz-ac,5ghz-ax",
            "ciphers=tkip,ccmp,gcmp,ccmp-256,gcmp-256,cmac,gmac,cmac-256,gmac-256", "countries=Latvia,Lithuania,Poland,Russia,United States",
        )
    }
    list("interface/wifi/capsman/remote-cap", writable = false) {
        row("address=192.168.88.8", "identity=cap-ax-workshop", "board=cAP ax", "serial=HG10DEMO001", "version=7.24.1", "state=Idle")
        row("address=192.168.88.9", "identity=cap-shed", "board=cAP ax", "serial=HG10DEMO002", "version=7.24.1", "state=Run")
    }
    settings(
        "interface/wifi/capsman",
        "enabled=true", "interfaces=bridge", "package-path=", "require-peer-certificate=false", "upgrade-policy=none", "ca-certificate=", "certificate=",
        fields = listOf(choice("upgrade-policy", "none", "require-same-version", "suggest-same-version")),
    )
    settings(
        "interface/wifi/cap",
        "enabled=false", "discovery-interfaces=", "caps-man-addresses=", "caps-man-names=", "caps-man-certificate-common-names=", "certificate=none", "lock-to-caps-man=false",
        "slaves-datapath=", "slaves-static=false", "static-virtual=false",
    )

    list("interface/bridge/vlan") {
        row("bridge=bridge", "vlan-ids=89", "tagged=bridge,ether5", "untagged=", "current-tagged=", "current-untagged=", "mvrp-forbidden=", "dynamic=false", "disabled=false", "comment=guest")
        row("bridge=bridge", "vlan-ids=20", "tagged=bridge,ether5", "untagged=", "current-tagged=", "current-untagged=", "mvrp-forbidden=", "dynamic=false", "disabled=false", "comment=IoT, not used yet")
    }
    list("interface/bridge/msti") {
        row("bridge=bridge", "identifier=1", "priority=0x9000", "vlan-mapping=89", "disabled=false")
    }
    list("interface/bridge/port/mst-override") {
        row("interface=ether5", "identifier=1", "priority=0x80", "internal-path-cost=20000", "disabled=false")
    }
    list(
        "interface/bridge/filter",
        ordered = true,
        fields = listOf(
            choice("chain", "forward", "input", "output"),
            choice("action", "accept", "drop", "jump", "log", "mark-packet", "passthrough", "return", "set-priority"),
        ),
        live = counting(),
    ) {
        row(
            "chain=forward", "action=drop", "in-interface=wifi1", "mac-protocol=ip", "ip-protocol=udp", "src-port=67", "invalid=false", "dynamic=false", "disabled=false",
            "comment=drop DHCP replies coming from wifi1",
        )
        row(
            "chain=forward", "action=drop", "in-interface=wifi2", "mac-protocol=ip", "ip-protocol=udp", "src-port=67", "invalid=false", "dynamic=false", "disabled=false",
            "comment=drop DHCP replies coming from wifi2",
        )
        row(
            "chain=forward", "action=drop", "in-interface=wifi1", "dst-mac-address=01:80:C2:00:00:00/FF:FF:FF:FF:FF:FF", "invalid=false", "dynamic=false", "disabled=true",
            "comment=drop BPDUs from the 2.4 GHz clients",
        )
    }
    list(
        "interface/bridge/nat",
        ordered = true,
        fields = listOf(
            choice("chain", "srcnat", "dstnat"),
            choice("action", "accept", "arp-reply", "dst-nat", "jump", "log", "passthrough", "redirect", "return", "src-nat"),
        ),
        live = counting(),
    ) {
        row(
            "chain=srcnat", "action=src-nat", "out-interface=ether3", "src-mac-address=00:00:5E:00:53:65", "to-src-mac-address=00:00:5E:00:53:02", "invalid=false", "dynamic=false", "disabled=false",
            "comment=the MacBook reaches the NAS with the router MAC",
        )
        row(
            "chain=dstnat", "action=dst-nat", "in-interface=wifi2", "dst-mac-address=00:00:5E:00:53:A9", "to-dst-mac-address=00:00:5E:00:53:20", "invalid=false", "dynamic=false", "disabled=true",
            "comment=disabled: old redirect to the NAS",
        )
    }
    list("interface/bridge/host", writable = false) {
        row("mac-address=00:00:5E:00:53:20", "vid=1", "on-interface=ether3", "bridge=bridge", "age=4s", "local=false", "external=false", "dynamic=true", "invalid=false", "disabled=false")
        row("mac-address=00:00:5E:00:53:30", "vid=1", "on-interface=ether2", "bridge=bridge", "age=1m12s", "local=false", "external=false", "dynamic=true", "invalid=false", "disabled=false")
        row("mac-address=00:00:5E:00:53:65", "vid=1", "on-interface=wifi2", "bridge=bridge", "age=0s", "local=false", "external=false", "dynamic=true", "invalid=false", "disabled=false")
        row("mac-address=00:00:5E:00:53:66", "vid=1", "on-interface=wifi2", "bridge=bridge", "age=3m20s", "local=false", "external=false", "dynamic=true", "invalid=false", "disabled=false")
        row("mac-address=00:00:5E:00:53:79", "vid=1", "on-interface=wifi1", "bridge=bridge", "age=11s", "local=false", "external=false", "dynamic=true", "invalid=false", "disabled=false")
        row("mac-address=00:00:5E:00:53:82", "vid=1", "on-interface=ether5", "bridge=bridge", "age=2s", "local=false", "external=false", "dynamic=true", "invalid=false", "disabled=false")
        row("mac-address=00:00:5E:00:53:A2", "vid=1", "on-interface=ether5", "bridge=bridge", "age=19s", "local=false", "external=false", "dynamic=true", "invalid=false", "disabled=false")
        row("mac-address=00:00:5E:00:53:C1", "vid=1", "on-interface=vpls-office", "bridge=bridge", "age=2m41s", "local=false", "external=false", "dynamic=true", "invalid=false", "disabled=false")
        row("mac-address=00:00:5E:00:53:02", "vid=1", "on-interface=bridge", "bridge=bridge", "age=0s", "local=true", "external=false", "dynamic=false", "invalid=false", "disabled=false")
    }
    list("interface/bridge/mdb") {
        row("group=239.192.10.5", "vid=1", "ports=ether5", "bridge=bridge", "dynamic=false", "disabled=false", "comment=IPTV stream pinned to the TV port")
        row("group=239.192.10.6", "vid=1", "ports=ether5", "bridge=bridge", "dynamic=false", "disabled=false", "comment=second IPTV channel")
    }
    settings(
        "interface/bridge/settings",
        "use-ip-firewall=false", "use-ip-firewall-for-vlan=false", "use-ip-firewall-for-pppoe=false", "allow-fast-path=true", "bridge-fast-path-active=true",
        "bridge-fast-path-packets=912204", "bridge-fast-path-bytes=1240118302", "bridge-fast-forward-packets=0", "bridge-fast-forward-bytes=0",
        live = { _, tick -> mapOf("bridge-fast-path-packets" to "${912_204L + tick * 1_300L}", "bridge-fast-path-bytes" to "${1_240_118_302L + tick * 1_800_000L}") },
    )
    command(
        "interface/bridge/monitor",
        text("numbers"),
        run = {
            flow {
                emit(
                    Item(
                        "",
                        mapOf(
                            "name" to "bridge", "state" to "running", "current-mac-address" to "00:00:5E:00:53:02", "bridge-id" to "0x8000.00:00:5E:00:53:02", "root-bridge" to "true",
                            "root-bridge-id" to "0x8000.00:00:5E:00:53:02", "regional-root-bridge-id" to "0x8000.00:00:5E:00:53:02", "root-path-cost" to "0", "root-port" to "none",
                            "port-count" to "7", "designated-port-count" to "6", "mst-config-digest" to "0x7F3A9C21D0B4E8A65C12F9D3B7E04A18",
                        ),
                    ),
                )
            }
        },
    )
    command("interface/bridge/port/monitor", text("numbers"), run = ::interfacesPortMonitor)

    command("interface/wireguard/peers/show-client-config", text("number"), run = ::interfacesClientConfig)

    list("ppp/profile", fields = listOf(choice("use-encryption", "default", "no", "yes", "required"))) {
        row(
            "name=default", "default=true", "local-address=", "remote-address=", "bridge=", "rate-limit=", "only-one=default", "use-ipv6=yes", "use-mpls=default", "use-compression=default",
            "use-encryption=default", "change-tcp-mss=yes", "use-upnp=default", "address-list=", "dns-server=", "wins-server=", "session-timeout=", "idle-timeout=", "on-up=", "on-down=",
        )
        row(
            "name=default-encryption", "default=true", "local-address=", "remote-address=", "bridge=", "rate-limit=", "only-one=default", "use-ipv6=yes", "use-mpls=default", "use-compression=default",
            "use-encryption=yes", "change-tcp-mss=yes", "use-upnp=default", "address-list=", "dns-server=", "wins-server=", "session-timeout=", "idle-timeout=", "on-up=", "on-down=",
        )
        row(
            "name=pppoe-lab", "default=false", "local-address=10.99.0.1", "remote-address=10.99.0.10", "bridge=", "rate-limit=20M/20M", "only-one=yes", "use-ipv6=yes", "use-mpls=default",
            "use-compression=default", "use-encryption=default", "change-tcp-mss=yes", "use-upnp=default", "address-list=", "dns-server=192.168.88.1", "wins-server=", "session-timeout=",
            "idle-timeout=", "on-up=", "on-down=",
        )
        row(
            "name=vpn-profile", "default=false", "local-address=10.99.1.1", "remote-address=10.99.1.10", "bridge=", "rate-limit=", "only-one=yes", "use-ipv6=no", "use-mpls=default",
            "use-compression=default", "use-encryption=required", "change-tcp-mss=yes", "use-upnp=default", "address-list=", "dns-server=192.168.88.1", "wins-server=", "session-timeout=1d",
            "idle-timeout=30m", "on-up=", "on-down=",
        )
    }
    list("ppp/secret", fields = listOf(choice("service", "any", "async", "isdn", "l2tp", "ovpn", "pppoe", "pptp", "sstp"))) {
        row(
            "name=lab-user", "password=demo-pass-1", "service=pppoe", "caller-id=00:00:5E:00:53:65", "profile=pppoe-lab", "local-address=", "remote-address=", "remote-ipv6-prefix=",
            "routes=", "ipv6-routes=", "limit-bytes-in=0", "limit-bytes-out=0", "last-logged-out=2026-10-30 22:14:03", "last-caller-id=00:00:5E:00:53:65", "last-disconnect-reason=user request",
            "disabled=false", "comment=lab laptop",
        )
        row(
            "name=vpn-anna", "password=demo-pass-2", "service=l2tp", "caller-id=", "profile=vpn-profile", "local-address=", "remote-address=", "remote-ipv6-prefix=", "routes=", "ipv6-routes=",
            "limit-bytes-in=0", "limit-bytes-out=0", "last-logged-out=2026-10-29 21:08:44", "last-caller-id=198.51.100.77", "last-disconnect-reason=peer is not responding", "disabled=false",
        )
        row(
            "name=vpn-work", "password=demo-pass-3", "service=sstp", "caller-id=", "profile=vpn-profile", "local-address=", "remote-address=", "remote-ipv6-prefix=", "routes=", "ipv6-routes=",
            "limit-bytes-in=0", "limit-bytes-out=0", "last-logged-out=", "last-caller-id=", "last-disconnect-reason=", "disabled=true", "comment=reserved for the office laptop",
        )
    }
    list("ppp/active", writable = false) {
        row(
            "name=lab-user", "service=pppoe", "caller-id=00:00:5E:00:53:65", "encoding=", "address=10.99.0.10", "uptime=1h37m12s", "session-id=0x81C00001", "limit-bytes-in=0",
            "limit-bytes-out=0", "radius=false",
        )
    }
    settings("ppp/aaa", "use-radius=false", "accounting=true", "interim-update=0s", "use-circuit-id-in-nas-port-id=false")
    list("ppp/l2tp-secret") {
        row("address=192.0.2.200", "secret=demo-l2tp-secret", "disabled=false")
    }
    list("interface/pppoe-server/server") {
        row(
            "service-name=lab-pppoe", "interface=bridge", "max-mtu=1480", "max-mru=1480", "mrru=disabled", "keepalive-timeout=10", "default-profile=pppoe-lab", "accept-empty-service=true",
            "one-session-per-host=true", "max-sessions=0", "pado-delay=0ms", "authentication=pap,chap,mschap1,mschap2", "pppoe-over-vlan-range=", "accept-untagged=true", "disabled=false",
            "comment=PPPoE test for the lab laptop",
        )
    }
    list("interface/ovpn-server/server") {
        row(
            "name=ovpn-server1", "port=1194", "mode=ip", "protocol=udp", "netmask=24", "mac-address=FE:00:5E:00:53:50", "max-mtu=1500", "keepalive-timeout=60", "default-profile=default",
            "certificate=none", "require-client-certificate=false", "tls-version=any", "auth=sha1", "cipher=aes256-gcm", "reneg-sec=3600", "redirect-gateway=disabled", "push-routes=",
            "vrf=main", "user-auth-method=pap", "enable-tun-ipv6=false", "tun-server-ipv6=::", "ipv6-prefix-len=64", "push-routes-ipv6=", "disabled=true", "comment=off, WireGuard is used instead",
        )
    }
    list("interface/l2tp-ether") {
        row(
            "name=l2tpv3-lab", "type=l2tp-ether", "mtu=1500", "actual-mtu=1500", "l2mtu=1600", "mac-address=00:00:5E:00:53:51", "connect-to=192.0.2.200", "running=false", "disabled=false",
            "comment=lab pseudowire, peer is down",
        )
    }
    settings(
        "interface/l2tp-server/server",
        "enabled=false", "accept-proto-version=all", "max-mtu=1450", "max-mru=1450", "mrru=disabled", "keepalive-timeout=30", "default-profile=default-encryption", "max-sessions=unlimited",
        "authentication=pap,chap,mschap1,mschap2", "use-ipsec=no", "ipsec-secret=", "caller-id-type=ip-address", "one-session-per-host=false", "allow-fast-path=false",
        "l2tpv3-circuit-id=", "l2tpv3-cookie-length=0", "l2tpv3-digest-hash=md5", "l2tpv3-ether-interface-list=none", "accept-pseudowire-type=all",
    )
    settings(
        "interface/sstp-server/server",
        "enabled=false", "port=443", "max-mtu=1500", "max-mru=1500", "mrru=disabled", "keepalive-timeout=60", "default-profile=default", "authentication=pap,chap,mschap1,mschap2",
        "certificate=none", "tls-version=any", "verify-client-certificate=no", "pfs=no", "ciphers=aes256-gcm-sha384,aes256-sha",
    )
    settings(
        "interface/pptp-server/server",
        "enabled=false", "max-mtu=1450", "max-mru=1450", "mrru=disabled", "keepalive-timeout=30", "default-profile=default-encryption", "authentication=mschap1,mschap2",
    )

    list("interface/dot1x/client") {
        row("interface=ether1", "eap-methods=eap-peap", "identity=demo-hap@isp.example", "password=demo-dot1x", "anon-identity=", "certificate=none", "status=authenticated", "disabled=false")
    }
    list(
        "interface/dot1x/server",
        fields = listOf(choice("mac-auth-mode", "mac-as-username", "mac-as-username-and-password")),
    ) {
        row(
            "interface=ether2", "auth-types=dot1x,mac-auth", "mac-auth-mode=mac-as-username", "radius-mac-format=XX:XX:XX:XX:XX:XX", "accounting=true", "interim-update=5m", "auth-timeout=1m",
            "retrans-timeout=30s", "reauth-period=1h", "reject-vlan-id=", "guest-vlan-id=89", "server-fail-vlan-id=", "disabled=false", "comment=printer port",
        )
        row(
            "interface=ether3", "auth-types=mac-auth", "mac-auth-mode=mac-as-username-and-password", "radius-mac-format=xxxxxxxxxxxx", "accounting=false", "interim-update=0s", "auth-timeout=1m",
            "retrans-timeout=30s", "reauth-period=1h", "reject-vlan-id=", "guest-vlan-id=", "server-fail-vlan-id=", "disabled=false", "comment=NAS port",
        )
    }
    list("interface/dot1x/server/state", writable = false) {
        row("interface=ether2", "status=authorized")
        row("interface=ether3", "status=authorized")
    }
    list("interface/dot1x/server/active", writable = false) {
        row("interface=ether2", "username=00:00:5E:00:53:30", "client-mac-address=00:00:5E:00:53:30", "vlan-id=", "auth-info=mac-auth, RADIUS accept")
        row("interface=ether3", "username=00:00:5E:00:53:20", "client-mac-address=00:00:5E:00:53:20", "vlan-id=", "auth-info=mac-auth, RADIUS accept")
    }

    list("interface/mesh") {
        row(
            "name=mesh1", "type=mesh", "mtu=1500", "actual-mtu=1500", "l2mtu=1500", "mac-address=00:00:5E:00:53:49", "arp=enabled", "arp-timeout=auto", "admin-mac=", "auto-mac=true",
            "mesh-portal=false", "hwmp-default-hoplimit=32", "hwmp-preq-waiting-time=1s", "hwmp-preq-retries=2", "hwmp-preq-destination-only=true", "hwmp-preq-reply-and-forward=true",
            "hwmp-prep-lifetime=5m", "hwmp-rann-interval=10s", "hwmp-rann-propagation-delay=1s", "hwmp-rann-lifetime=1s", "reoptimize-paths=false", "running=true", "disabled=false",
            "comment=joins the sites over EoIP",
        )
    }
    list("interface/mesh/port") {
        row(
            "interface=eoip-office", "mesh=mesh1", "path-cost=10", "hello-interval=1s", "port-type=auto", "dr-address=00:00:00:00:00:00", "active-port-type=ethernet", "inactive=false",
            "dynamic=false", "disabled=false",
        )
        row(
            "interface=eoip-lab", "mesh=mesh1", "path-cost=10", "hello-interval=1s", "port-type=auto", "dr-address=00:00:00:00:00:00", "active-port-type=ethernet", "inactive=true",
            "dynamic=false", "disabled=false",
        )
    }
    list("interface/mesh/fdb", writable = false) {
        row("mesh=mesh1", "type=local", "mac-address=00:00:5E:00:53:49", "on-interface=mesh1", "lifetime=0s", "age=0s", "metric=0", "seq-number=0", "active=true", "root=false")
        row("mesh=mesh1", "type=remote", "mac-address=00:00:5E:00:53:A2", "on-interface=eoip-office", "lifetime=4m33s", "age=27s", "metric=20", "seq-number=118", "active=true", "root=false")
        row("mesh=mesh1", "type=remote", "mac-address=00:00:5E:00:53:A3", "on-interface=eoip-office", "lifetime=3m51s", "age=1m9s", "metric=40", "seq-number=96", "active=true", "root=true")
    }

    list("radius", ordered = true, fields = listOf(choice("protocol", "udp", "radsec"))) {
        row(
            "service=wireless,dot1x", "address=192.168.88.20", "protocol=udp", "authentication-port=1812", "accounting-port=1813", "src-address=0.0.0.0", "timeout=300ms", "status=ok",
            "secret=demo-radius-secret", "called-id=", "domain=", "realm=", "accounting-backup=false", "require-message-auth=yes-for-request-resp", "radsec-timeout=", "certificate=",
            "pending=0", "requests=1412", "accepts=1380", "rejects=29", "resends=3", "timeouts=0", "bad-replies=0", "last-request-rtt=4ms", "disabled=false", "comment=RADIUS on the NAS",
        )
        row(
            "service=login,ppp", "address=192.168.88.20", "protocol=udp", "authentication-port=1812", "accounting-port=1813", "src-address=0.0.0.0", "timeout=300ms", "status=ok",
            "secret=demo-radius-secret", "called-id=", "domain=", "realm=", "accounting-backup=false", "require-message-auth=yes-for-request-resp", "radsec-timeout=", "certificate=",
            "pending=0", "requests=96", "accepts=95", "rejects=1", "resends=0", "timeouts=0", "bad-replies=0", "last-request-rtt=3ms", "disabled=false",
        )
        row(
            "service=hotspot", "address=203.0.113.200", "protocol=udp", "authentication-port=1812", "accounting-port=1813", "src-address=0.0.0.0", "timeout=1s", "status=timeout",
            "secret=demo-radius-secret", "called-id=", "domain=", "realm=", "accounting-backup=false", "require-message-auth=yes-for-request-resp", "radsec-timeout=", "certificate=",
            "pending=0", "requests=0", "accepts=0", "rejects=0", "resends=0", "timeouts=0", "bad-replies=0", "last-request-rtt=", "disabled=true", "comment=guest portal, not used",
        )
    }
    settings(
        "radius/incoming",
        "accept=false", "port=3799", "vrf=main", "requests=0", "bad-requests=0", "acks=0", "naks=0",
    )

    list("queue/interface", writable = false) {
        for (name in listOf("ether1", "ether2", "ether3", "ether4", "ether5")) {
            row("interface=$name", "queue=only-hardware-queue", "active-queue=only-hardware-queue", "default-queue=only-hardware-queue", "dynamic=false")
        }
        for (name in listOf("wifi1", "wifi2")) {
            row("interface=$name", "queue=wireless-default", "active-queue=wireless-default", "default-queue=wireless-default", "dynamic=false")
        }
        for (name in listOf("bridge", "wg-home")) {
            row("interface=$name", "queue=no-queue", "active-queue=no-queue", "default-queue=no-queue", "dynamic=false")
        }
    }
    list(
        "queue/tree",
        live = { row, tick -> interfacesQueueCounters(row, tick) + ("rate" to "${wander(2_000_000L * (row.id.hashCode().mod(5) + 1), tick, row.id.hashCode())}") },
    ) {
        row(
            "name=total-download", "parent=bridge", "packet-mark=", "queue=pcq-download", "priority=8", "limit-at=0", "max-limit=300M", "bucket-size=0.1", "burst-limit=0", "burst-threshold=0",
            "burst-time=0s", "rate=0", "packet-rate=0", "queued-bytes=0", "queued-packets=0", "bytes=0", "packets=0", "dropped=0", "pcq-queues=0", "invalid=false", "disabled=false",
        )
        row(
            "name=total-upload", "parent=ether1", "packet-mark=", "queue=pcq-upload", "priority=8", "limit-at=0", "max-limit=100M", "bucket-size=0.1", "burst-limit=0", "burst-threshold=0",
            "burst-time=0s", "rate=0", "packet-rate=0", "queued-bytes=0", "queued-packets=0", "bytes=0", "packets=0", "dropped=0", "pcq-queues=0", "invalid=false", "disabled=false",
        )
        row(
            "name=dl-gaming", "parent=total-download", "packet-mark=gaming-in", "queue=pcq-download", "priority=1", "limit-at=50M", "max-limit=300M", "bucket-size=0.1", "burst-limit=0",
            "burst-threshold=0", "burst-time=0s", "rate=0", "packet-rate=0", "queued-bytes=0", "queued-packets=0", "bytes=0", "packets=0", "dropped=0", "pcq-queues=0", "invalid=false",
            "disabled=false", "comment=game consoles first",
        )
        row(
            "name=dl-bulk", "parent=total-download", "packet-mark=bulk-in", "queue=pcq-download", "priority=8", "limit-at=10M", "max-limit=150M", "bucket-size=0.1", "burst-limit=0",
            "burst-threshold=0", "burst-time=0s", "rate=0", "packet-rate=0", "queued-bytes=0", "queued-packets=0", "bytes=0", "packets=0", "dropped=0", "pcq-queues=0", "invalid=false",
            "disabled=false",
        )
    }
    list(
        "queue/type",
        fields = listOf(choice("kind", "pfifo", "bfifo", "mq-pfifo", "red", "sfq", "pcq", "codel", "fq-codel", "cake", "none")),
    ) {
        row("name=default", "kind=pfifo", "pfifo-limit=50", "default=true")
        row("name=ethernet-default", "kind=pfifo", "pfifo-limit=50", "default=true")
        row("name=wireless-default", "kind=sfq", "sfq-perturb=5", "sfq-allot=1514", "default=true")
        row("name=synchronous-default", "kind=red", "red-limit=60", "red-min-threshold=10", "red-max-threshold=50", "red-burst=20", "red-avg-packet=1000", "default=true")
        row("name=hotspot-default", "kind=sfq", "sfq-perturb=5", "sfq-allot=1514", "default=true")
        row("name=only-hardware-queue", "kind=none", "default=true")
        row("name=multi-queue-ethernet-default", "kind=mq-pfifo", "mq-pfifo-limit=50", "default=true")
        row("name=default-small", "kind=pfifo", "pfifo-limit=10", "default=true")
        row(
            "name=pcq-download", "kind=pcq", "pcq-rate=0", "pcq-limit=50KiB", "pcq-total-limit=2000KiB", "pcq-classifier=dst-address", "pcq-burst-rate=0", "pcq-burst-threshold=0",
            "pcq-burst-time=10s", "pcq-src-address-mask=32", "pcq-dst-address-mask=32", "pcq-src-address6-mask=128", "pcq-dst-address6-mask=128", "default=false",
        )
        row(
            "name=pcq-upload", "kind=pcq", "pcq-rate=0", "pcq-limit=50KiB", "pcq-total-limit=2000KiB", "pcq-classifier=src-address", "pcq-burst-rate=0", "pcq-burst-threshold=0",
            "pcq-burst-time=10s", "pcq-src-address-mask=32", "pcq-dst-address-mask=32", "pcq-src-address6-mask=128", "pcq-dst-address6-mask=128", "default=false",
        )
        row(
            "name=fq-codel-wan", "kind=fq-codel", "fq-codel-limit=10240", "fq-codel-flows=1024", "fq-codel-quantum=1514", "fq-codel-target=5ms", "fq-codel-interval=100ms",
            "fq-codel-ecn=true", "fq-codel-memlimit=32MiB", "fq-codel-ce-threshold=0", "default=false",
        )
    }
}

private fun interfacesEthernetMonitor(arguments: Map<String, String>): Flow<Item> = flow {
    for (name in arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }) {
        val values = if (name == "ether4") {
            mapOf("status" to "no-link")
        } else {
            mapOf(
                "status" to "link-ok", "auto-negotiation" to "done", "rate" to "1Gbps", "full-duplex" to "true", "tx-flow-control" to "false", "rx-flow-control" to "false",
                "advertising" to INTERFACES_ADVERTISED, "link-partner-advertising" to INTERFACES_ADVERTISED,
            )
        }
        emit(Item("", values + ("name" to name)))
    }
}

// *3 is the port of ether4, which has no link
private fun interfacesPortMonitor(arguments: Map<String, String>): Flow<Item> = flow {
    val idle = arguments["numbers"] == "*3"
    emit(
        Item(
            "",
            mapOf(
                "status" to if (idle) "inactive" else "in-bridge", "external-fdb" to "false", "learning" to "${!idle}", "forwarding" to "${!idle}", "port-id" to "0x8001",
                "role" to if (idle) "disabled-port" else "designated-port", "edge-port" to "${!idle}", "edge-port-discovery" to "true", "point-to-point-port" to "${!idle}",
                "sending-rstp" to "true", "actual-path-cost" to "20000", "designated-bridge-id" to "0x8000.00:00:5E:00:53:02", "designated-cost" to "0",
                "designated-port-id" to "0x8001", "tx-rx-bpdu" to "0 / 0",
            ),
        ),
    )
}

private fun interfacesScan(arguments: Map<String, String>): Flow<Item> = flow {
    val radio = arguments["number"].orEmpty()
    for (neighbor in interfacesNeighbors.filter { radio.isEmpty() || it["interface"] == radio }) {
        emit(Item("", neighbor - "interface"))
        delay(400)
    }
}

private fun interfacesClientConfig(arguments: Map<String, String>): Flow<Item> = flow {
    val (key, address) = interfacesClients[arguments["number"]] ?: return@flow
    emit(
        Item(
            "",
            mapOf(
                "conf" to "\n[Interface]\nPrivateKey = $key\nAddress = $address\nDNS = 10.66.66.1\n\n" +
                    "[Peer]\nPublicKey = Xk3aPq9vN2mZr8Wc5LtH0eYdB7fUo1sGJiA4nKxVQhM=\nAllowedIPs = 0.0.0.0/0\nEndpoint = 203.0.113.50:13231\nPersistentKeepalive = 25",
            ),
        ),
    )
}
