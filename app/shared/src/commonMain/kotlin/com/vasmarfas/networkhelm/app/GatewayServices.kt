package com.vasmarfas.networkhelm.app

import com.vasmarfas.networkhelm.gateway.GatewayClient
import com.vasmarfas.networkhelm.gateway.MacTelnetLogin
import com.vasmarfas.networkhelm.gateway.NativeLogin
import com.vasmarfas.networkhelm.transport.MemoryTrustStore

fun gatewayServices(gateway: GatewayClient): Services {
    val offers = gateway.offers
    return Services(
        GatewayDocuments(gateway),
        MemoryTrustStore(),
        neighbors = if (offers.neighbors) gateway.neighbors() else null,
        secrets = GatewaySecrets(gateway),
        refreshNeighbors = if (offers.neighbors) gateway::refreshNeighbors else null,
        scan = if (offers.scan) gateway::scan else null,
        connectOpenWrt = { login, trust -> gateway.connect(login, trust) },
        connectLogin = { login, trust -> gateway.connect(login, trust) },
        networkInterfaces = if (offers.interfaces.isEmpty()) null else ({ offers.interfaces }),
        connectMacTelnet = if (offers.interfaces.isEmpty()) null else ({ mac, user, pass, interfaceName, encoding, _ -> gateway.connect(MacTelnetLogin(mac, user, pass, interfaceName, encoding)) }),
        connectTcp8291 = if (offers.native) ({ host, port, user, pass, encoding, _ -> gateway.connect(NativeLogin(host, port, user, pass, encoding)) }) else null,
        syncLock = null,
        connect = { login, trust, _ -> gateway.connect(login, trust) },
    )
}

private class GatewayDocuments(private val gateway: GatewayClient) : Storage {
    override suspend fun read(name: String) = gateway.readDocument(name)

    override suspend fun write(name: String, text: String) = gateway.writeDocument(name, text)
}

private class GatewaySecrets(private val gateway: GatewayClient) : Secrets {
    override suspend fun get(key: String) = gateway.readSecret(key)

    override suspend fun put(key: String, value: String) = gateway.writeSecret(key, value)

    override suspend fun delete(key: String) = gateway.deleteSecret(key)
}
