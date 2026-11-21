package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.Item
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.math.abs

private val linksBusy = setOf(2412, 2437, 2462, 5180, 5260, 5745)

internal fun DemoBuilder.linksDemo() {
    command("interface/ethernet/cable-test", run = ::linksCableTest)
    command("interface/bonding/monitor-slaves", text("bond"), run = { linksBondSlaves() })
    command("interface/mesh/traceroute", text("mesh"), text("destination"), run = { linksMeshTraceroute() })
    command("interface/wifi/frequency-scan", run = ::linksFrequencyScan)
    command("interface/wifi/spectral-scan", run = ::linksSpectralScan)
    command("interface/wifi/sniffer", run = { linksSniffer() })
    command("interface/wifi/flat-snoop", run = { linksFlatSnoop() })
    command("interface/pppoe-client/scan", run = { linksPppoeScan() })
    command("interface/ovpn-client/import-ovpn-configuration", run = { refusal("it imports nothing") })
    command("interface/ovpn-server/server/export-client-configuration", run = { refusal("it makes no files") })
    command("ip/hotspot/host/make-binding", text("numbers"), choice("type", "regular", "bypassed", "blocked"), run = { emptyFlow() })
    command("ip/ssh/export-host-key", text("key-file-prefix"), run = { refusal("it makes no files") })
    command("ip/ssh/import-host-key", text("private-key-file"), run = { refusal("it imports nothing") })
    command("system/check-installation", run = { flowOf(Item("", mapOf("status" to "installation is ok"))) })
    settings("system/console/screen", "blank-interval=10m", "line-count=25")
    list("system/ntp/client/servers") {
        row("address=pool.ntp.org", "iburst=yes", "min-poll=6", "max-poll=10", "auth-key=none", "disabled=false")
        row("address=192.168.88.20", "iburst=yes", "min-poll=4", "max-poll=8", "auth-key=none", "disabled=true", "comment=NTP of the NAS")
    }
    command("tool/romon/discover", run = { linksRomonDiscover() })
    command("tool/romon/ping", text("id"), text("count"), text("size"), run = { ping(it + ("address" to it["id"].orEmpty())) })
}

// ether4 has no cable in it, as its monitor says
private fun linksCableTest(arguments: Map<String, String>): Flow<Item> = flow {
    for (name in arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }) {
        val values = if (name == "ether4") mapOf("status" to "no-link", "cable-pairs" to "open:0,open:0,open:0,open:0") else mapOf("status" to "link-ok")
        emit(Item("", values + ("name" to name)))
    }
}

private fun linksBondSlaves(): Flow<Item> = flow {
    for ((port, name) in listOf("ether4", "ether5").withIndex()) {
        emit(
            Item(
                "",
                mapOf(
                    "interface" to name, "active" to "false", "partner" to "false", "port" to "${port + 1}", "system" to "00:00:5E:00:53:43", "system-priority" to "65535",
                    "key" to "9", "port-priority" to "255", "collecting-distributing" to "no",
                ),
            ),
        )
    }
}

private fun linksMeshTraceroute(): Flow<Item> = flow {
    for ((hop, address) in listOf("00:00:5E:00:53:A2", "00:00:5E:00:53:A3").withIndex()) {
        delay(300)
        emit(Item("", mapOf("hop" to "${hop + 1}", "address" to address, "time" to "${(hop + 1) * 2}ms")))
    }
}

private fun linksFrequencyScan(arguments: Map<String, String>): Flow<Item> = flow {
    val channels = if (arguments["number"] == "wifi2") listOf(5180, 5200, 5220, 5240, 5260, 5280, 5300, 5320, 5745, 5765, 5785, 5805) else (2412..2472 step 5).toList()
    for (frequency in channels) {
        delay(150)
        val busy = frequency in linksBusy
        emit(
            Item(
                "$frequency",
                mapOf(
                    "channel" to "$frequency/20", "networks" to if (busy) "1" else "0", "load" to "${if (busy) wander(28, frequency, 3) else wander(4, frequency, 5)}",
                    "nf" to "-${wander(104, frequency, 7)}", "max-signal" to if (busy) "-${wander(62, frequency, 9)}" else "", "min-signal" to if (busy) "-${wander(78, frequency, 11)}" else "",
                ),
            ),
        )
    }
}

private fun linksSpectralScan(arguments: Map<String, String>): Flow<Item> = flow {
    val range = if (arguments["number"] == "wifi2") 5170..5330 else 2402..2482
    for (frequency in range step 5) {
        delay(100)
        val near = linksBusy.any { abs(it - frequency) <= 10 }
        emit(Item("$frequency", mapOf("freq" to "$frequency", "dbm-avg" to "-${wander(if (near) 82L else 97L, frequency, 2)}", "dbm-peak" to "-${wander(if (near) 66L else 90L, frequency, 4)}")))
    }
}

private fun linksSniffer(): Flow<Item> = flow {
    val frames = listOf(
        listOf("0.014", "00:00:5E:00:53:B1", "FF:FF:FF:FF:FF:FF", "beacon", "-58"),
        listOf("0.102", "00:00:5E:00:53:65", "FF:FF:FF:FF:FF:FF", "probe-request", "-47"),
        listOf("0.103", "00:00:5E:00:53:10", "00:00:5E:00:53:65", "probe-response", "-31"),
        listOf("0.231", "00:00:5E:00:53:B3", "FF:FF:FF:FF:FF:FF", "beacon", "-74"),
        listOf("0.418", "00:00:5E:00:53:66", "00:00:5E:00:53:10", "qos-data", "-55"),
    )
    for ((time, source, destination, type, signal) in frames) {
        delay(200)
        emit(Item("", mapOf("time" to time, "src-address" to source, "dst-address" to destination, "type" to type, "signal" to signal)))
    }
}

private fun linksFlatSnoop(): Flow<Item> = flow {
    val seen = listOf(
        listOf("00:00:5E:00:53:B1", "ap", "NeighborNet", "2437", "-58"),
        listOf("00:00:5E:00:53:B2", "ap", "HomeWiFi-2G", "2462", "-66"),
        listOf("00:00:5E:00:53:B3", "ap", "CafeGuest", "2412", "-74"),
        listOf("00:00:5E:00:53:C5", "station", "NeighborNet", "2437", "-71"),
        listOf("00:00:5E:00:53:C6", "station", "CafeGuest", "2412", "-80"),
    )
    for ((address, type, ssid, channel, signal) in seen) {
        delay(250)
        emit(Item("", mapOf("address" to address, "type" to type, "ssid" to ssid, "channel" to channel, "signal" to signal)))
    }
}

private fun linksPppoeScan(): Flow<Item> = flow {
    delay(1000)
    emit(Item("", mapOf("mac-address" to "00:00:5E:00:53:C1", "service" to "", "ac-name" to "bras-demo-01")))
    emit(Item("", mapOf("mac-address" to "00:00:5E:00:53:C2", "service" to "internet", "ac-name" to "bras-demo-02")))
}

private fun linksRomonDiscover(): Flow<Item> = flow {
    val found = listOf(
        listOf("00:00:5E:00:53:A2", "200", "1", "00:00:5E:00:53:A2", "1584", "office-hex", "7.24.1", "RB750Gr3"),
        listOf("00:00:5E:00:53:A3", "400", "2", "00:00:5E:00:53:A2,00:00:5E:00:53:A3", "1580", "lab-crs", "7.22.2", "CRS326-24G-2S+"),
    )
    for (values in found) {
        delay(500)
        emit(Item("", listOf("address", "cost", "hops", "path", "l2mtu", "identity", "version", "board").zip(values).toMap()))
    }
}
