package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.session.DeviceSession

val demoHosts: Map<Platform, String> = mapOf(
    Platform.RouterOs to "routeros.demo",
    Platform.OpenWrt to "openwrt.demo",
    Platform.Opnsense to "opnsense.demo",
    Platform.Pfsense to "pfsense.demo",
    Platform.Vyos to "vyos.demo",
    Platform.Keenetic to "keenetic.demo",
    Platform.Ssh to "linux.demo",
)

fun demoRouter(host: String): DeviceSession? = when (host.lowercase()) {
    demoHosts.getValue(Platform.RouterOs) -> routerOsDemo()
    demoHosts.getValue(Platform.OpenWrt) -> openWrtDemo()
    demoHosts.getValue(Platform.Opnsense) -> opnsenseDemo()
    demoHosts.getValue(Platform.Pfsense) -> pfsenseDemo()
    demoHosts.getValue(Platform.Vyos) -> vyosDemo()
    demoHosts.getValue(Platform.Keenetic) -> keeneticDemo()
    demoHosts.getValue(Platform.Ssh) -> linuxDemo()
    else -> null
}
