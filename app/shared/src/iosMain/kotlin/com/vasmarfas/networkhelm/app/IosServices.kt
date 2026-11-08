package com.vasmarfas.networkhelm.app

import com.vasmarfas.networkhelm.discovery.mndpSightings
import com.vasmarfas.networkhelm.discovery.requestMndpRefresh
import com.vasmarfas.networkhelm.miknative.WinBoxAddressBook
import com.vasmarfas.networkhelm.miknative.connectMacTelnetFrom
import com.vasmarfas.networkhelm.miknative.connectNativeHost
import com.vasmarfas.networkhelm.miknative.nativeSide
import com.vasmarfas.networkhelm.nettools.hostOps
import com.vasmarfas.networkhelm.openwrt.QuickJsForms
import com.vasmarfas.networkhelm.session.connectOpenWrtWithSsh
import com.vasmarfas.networkhelm.session.connectWithSsh
import com.vasmarfas.networkhelm.transport.TrustKind
import com.vasmarfas.networkhelm.transport.TrustStore
import eu.anifantakis.lib.ksafe.KSafe
import platform.Foundation.NSUserDefaults

// the neighbours and MAC-Telnet go by broadcasts, which a device sends and hears only with the multicast entitlement
// of Apple; until Info.plist says the app has it, they stay hidden
fun iosServices(): Services {
    val broadcasts = hostOps()?.broadcasts == true
    return Services(
        DefaultsStorage(),
        DefaultsTrustStore(),
        neighbors = if (broadcasts) mndpSightings() else null,
        secrets = KSafeSecrets(KSafe(fileName = "routers")),
        refreshNeighbors = if (broadcasts) ::requestMndpRefresh else null,
        connectOpenWrt = { login, trust -> connectOpenWrtWithSsh(login, trust, ::QuickJsForms) },
        connectMacTelnet = if (broadcasts) {
            { mac, user, pass, own, encoding, scope -> connectMacTelnetFrom(mac, own.orEmpty(), user, pass, encoding, scope) }
        } else {
            null
        },
        typedMac = broadcasts,
        connect = { login, trust, scope -> connectWithSsh(login, trust, scope, native = login.nativeSide(scope)) },
        connectTcp8291 = ::connectNativeHost,
        readWinBoxBook = WinBoxAddressBook::read,
    )
}

private class DefaultsStorage : Storage {
    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun read(name: String): String? = defaults.stringForKey(name)

    override suspend fun write(name: String, text: String) = defaults.setObject(text, name)
}

// fingerprints of certificates and host keys are public, so UserDefaults is enough
private class DefaultsTrustStore : TrustStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun get(host: String, port: Int, kind: TrustKind) = defaults.stringForKey(key(host, port, kind))

    override suspend fun put(host: String, port: Int, kind: TrustKind, fingerprint: String) = defaults.setObject(fingerprint, key(host, port, kind))

    private fun key(host: String, port: Int, kind: TrustKind) = "trust/$kind/$host/$port"
}
