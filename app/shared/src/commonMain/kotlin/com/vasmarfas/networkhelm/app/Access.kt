package com.vasmarfas.networkhelm.app

import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.openwrt.OpenWrtProtocol
import com.vasmarfas.networkhelm.routeros.RouterOsProtocol

// the saved list and CSV files keep the entries by name, so a name must not change
enum class Access(val routerOs: RouterOsProtocol?, val openWrt: OpenWrtProtocol?, private val port: Int = 22) {
    ApiSsl(RouterOsProtocol.ApiSsl, null),
    Api(RouterOsProtocol.Api, null),
    RestSsl(RouterOsProtocol.RestSsl, null),
    Rest(RouterOsProtocol.Rest, null),
    Tcp8291(null, null, 8291),
    MacTelnet(null, null, 20561),
    OpenWrtSsh(null, OpenWrtProtocol.Ssh),
    OpenWrtUbusSsl(null, OpenWrtProtocol.UbusSsl),
    OpenWrtUbus(null, OpenWrtProtocol.Ubus),
    Ssh(null, null),
    Opnsense(null, null, 443),
    Pfsense(null, null, 443),
    KeeneticSsl(null, null, 443),
    Keenetic(null, null, 80),
    KeeneticSsh(null, null, 22),
    Vyos(null, null, 443),
    Snmp(null, null, 161),
    Snmp3(null, null, 161),
    Upnp(null, null, 1900),
    ;

    val defaultPort: Int get() = routerOs?.defaultPort ?: openWrt?.defaultPort ?: port

    val kind: DeviceKind get() = DeviceKind.entries.first { this in it.protocols }
}

enum class DeviceKind(val protocols: List<Access>) {
    RouterOs(listOf(Access.Tcp8291, Access.ApiSsl, Access.Api, Access.RestSsl, Access.Rest, Access.MacTelnet)),
    OpenWrt(listOf(Access.OpenWrtSsh, Access.OpenWrtUbusSsl, Access.OpenWrtUbus)),
    Opnsense(listOf(Access.Opnsense)),
    Pfsense(listOf(Access.Pfsense)),
    Keenetic(listOf(Access.KeeneticSsl, Access.Keenetic, Access.KeeneticSsh)),
    Vyos(listOf(Access.Vyos)),
    Linux(listOf(Access.Ssh)),
    Other(listOf(Access.Snmp, Access.Snmp3, Access.Upnp)),
    ;

    companion object {
        fun of(platform: Platform): DeviceKind = when (platform) {
            Platform.RouterOs -> RouterOs
            Platform.OpenWrt -> OpenWrt
            Platform.Opnsense -> Opnsense
            Platform.Pfsense -> Pfsense
            Platform.Keenetic -> Keenetic
            Platform.Vyos -> Vyos
            Platform.Ssh -> Linux
            Platform.Snmp, Platform.Upnp -> Other
        }
    }
}
