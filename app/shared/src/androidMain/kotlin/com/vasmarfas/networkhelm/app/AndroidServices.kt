package com.vasmarfas.networkhelm.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import com.vasmarfas.networkhelm.discovery.MndpSighting
import com.vasmarfas.networkhelm.discovery.mndpSightings
import com.vasmarfas.networkhelm.discovery.requestMndpRefresh
import com.vasmarfas.networkhelm.miknative.WinBoxAddressBook
import com.vasmarfas.networkhelm.miknative.connectNativeHost
import com.vasmarfas.networkhelm.miknative.nativeSide
import com.vasmarfas.networkhelm.routeros.schema.FileSchemaCache
import com.vasmarfas.networkhelm.session.connectWithSsh
import com.vasmarfas.networkhelm.transport.FileTrustStore
import com.vasmarfas.networkhelm.ui.sync.bookmarkedFolder
import eu.anifantakis.lib.ksafe.KSafe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import java.io.File

fun androidServices(context: Context): Services {
    val schemas = FileSchemaCache(File(context.filesDir, "schema"))
    return Services(
        FileStorage(context.filesDir),
        FileTrustStore(File(context.filesDir, "known-routers")),
        mndpSightings().multicastLocked(context),
        KSafeSecrets(KSafe(context, fileName = "routers")),
        refreshNeighbors = ::requestMndpRefresh,
        connectOpenWrt = ::connectOpenWrtWithForms,
        connect = { login, trust, scope -> connectWithSsh(login, trust, scope, schemas, login.nativeSide(scope)) },
        connectTcp8291 = { host, port, user, pass, encoding, scope -> connectNativeHost(host, port, user, pass, encoding, scope, schemas) },
        readWinBoxBook = WinBoxAddressBook::read,
        releases = ::fetchReleases,
        changelog = ::fetchChangelog,
        localNetwork = { localNetworkAllowed(context) },
        syncFolder = { bookmarkedFolder(context, it) },
    )
}

fun localNetworkAllowed(context: Context) =
    Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

// MNDP comes in broadcasts, which Wi-Fi drops to save power unless the app holds a multicast lock
private fun Flow<MndpSighting>.multicastLocked(context: Context): Flow<MndpSighting> {
    val lock = context.getSystemService(WifiManager::class.java).createMulticastLock("networkhelm-mndp").apply { setReferenceCounted(false) }
    return onStart { lock.acquire() }.onCompletion { lock.release() }
}
