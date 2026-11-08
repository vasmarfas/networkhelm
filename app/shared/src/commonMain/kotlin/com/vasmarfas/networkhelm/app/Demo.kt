package com.vasmarfas.networkhelm.app

import com.vasmarfas.networkhelm.demo.demoHosts
import com.vasmarfas.networkhelm.demo.demoRouter
import com.vasmarfas.networkhelm.model.Platform

fun Services.withDemo(): Services = Services(
    storage, trust, neighbors, secrets, refreshNeighbors, localNetwork, browserAccess, scan, sshAgent,
    connectOpenWrt = { login, trust -> demoRouter(login.host) ?: connectOpenWrt(login, trust) },
    connectLogin = { login, trust -> demoRouter(login.host) ?: connectLogin(login, trust) },
    networkInterfaces = networkInterfaces,
    connectMacTelnet = connectMacTelnet,
    typedMac = typedMac,
    connectTcp8291 = connectTcp8291,
    readWinBoxBook = readWinBoxBook,
    connect = { login, trust, scope -> demoRouter(login.host) ?: connect(login, trust, scope) },
    standalone = standalone,
)

fun demoRouters(): List<SavedRouter> = demoHosts.map { (platform, host) ->
    val access = when (platform) {
        Platform.RouterOs -> Access.RestSsl
        Platform.OpenWrt -> Access.OpenWrtUbusSsl
        Platform.Opnsense -> Access.Opnsense
        Platform.Pfsense -> Access.Pfsense
        Platform.Keenetic -> Access.KeeneticSsl
        Platform.Vyos -> Access.Vyos
        Platform.Ssh, Platform.Snmp, Platform.Upnp -> Access.Ssh
    }
    SavedRouter(host, "demo", access, DEMO_GROUP)
}

const val DEMO_GROUP = "Demo"
