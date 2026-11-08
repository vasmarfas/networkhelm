package com.vasmarfas.networkhelm.app

import com.vasmarfas.networkhelm.discovery.mndpSightings
import com.vasmarfas.networkhelm.discovery.requestMndpRefresh
import com.vasmarfas.networkhelm.miknative.WinBoxAddressBook
import com.vasmarfas.networkhelm.miknative.connectMacTelnetOnInterface
import com.vasmarfas.networkhelm.miknative.connectNativeHost
import com.vasmarfas.networkhelm.miknative.macTelnetInterfaces
import com.vasmarfas.networkhelm.miknative.nativeSide
import com.vasmarfas.networkhelm.routeros.schema.FileSchemaCache
import com.vasmarfas.networkhelm.session.connectWithSsh
import com.vasmarfas.networkhelm.transport.FileTrustStore
import eu.anifantakis.lib.ksafe.KSafe
import java.io.File

fun desktopServices(): Services {
    val directory = applicationDirectory()
    val storage = FileStorage(directory)
    val trust = FileTrustStore(File(directory, "known-routers"))
    val secrets = KSafeSecrets(KSafe(fileName = "routers", baseDir = File(directory, "secrets")))
    val schemas = FileSchemaCache(File(directory, "schema"))
    return Services(
        storage,
        trust,
        mndpSightings(),
        secrets,
        refreshNeighbors = ::requestMndpRefresh,
        sshAgent = true,
        connectOpenWrt = ::connectOpenWrtWithForms,
        connect = { login, trust, scope -> connectWithSsh(login, trust, scope, schemas, login.nativeSide(scope)) },
        browserAccess = DesktopGateway(File(directory, "gateway"), webApp(), storage, secrets, trust),
        networkInterfaces = ::macTelnetInterfaces,
        connectMacTelnet = { mac, user, pass, iface, encoding, scope -> connectMacTelnetOnInterface(mac, user, pass, iface, encoding, scope, schemas) },
        connectTcp8291 = { host, port, user, pass, encoding, scope -> connectNativeHost(host, port, user, pass, encoding, scope, schemas) },
        readWinBoxBook = WinBoxAddressBook::read,
        // only a Mac App Store build runs in the sandbox, and the store updates it itself
        releases = if (System.getenv("APP_SANDBOX_CONTAINER_ID") == null) ::fetchReleases else null,
        changelog = ::fetchChangelog,
    )
}

private fun webApp(): File? = System.getProperty("compose.application.resources.dir")?.let { File(it, "web") }?.takeIf { File(it, "index.html").isFile }

private fun applicationDirectory(): File {
    System.getenv("NETWORKHELM_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it) }
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    return when {
        "win" in os -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "NetworkHelm")
        "mac" in os -> File(home, "Library/Application Support/NetworkHelm")
        else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "networkhelm")
    }
}
