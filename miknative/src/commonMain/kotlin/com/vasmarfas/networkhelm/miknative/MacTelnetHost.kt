package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.schema.MemorySchemaCache
import com.vasmarfas.networkhelm.routeros.schema.SchemaCache
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.NativeSide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

// the stream of port 8291 first; a router that does not answer it keeps the console of MAC-Telnet
suspend fun connectMacTelnetHost(destination: MacAddress, user: String, password: String, link: MacBroadcastInterface, encoding: ApiEncoding, scope: CoroutineScope, schemaCache: SchemaCache = MemorySchemaCache()): DeviceSession {
    fun info(product: String) = DeviceInfo(Platform.Ssh, destination.format(), product, "", "", emptySet(), setOf("read", "write"))
    val connect: suspend () -> NativeConnection = { connectMacNative(destination, user, password, scope, link.mac, destinationAddress = link.broadcast) }
    val stream = try {
        NativeSsh.open(connect, password)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    if (stream != null) return consoleHost(stream, user, encoding, scope, info("RouterOS (MAC)"), schemaCache, native = NativeSide { NativeObjectModel(connect()) })
    val ssh = connectMacTelnet(destination, user, password, scope, link.mac, destinationAddress = link.broadcast, terminalType = "xterm-256color")
    return consoleHost(ssh, user, encoding, scope, info("RouterOS (MAC-Telnet)"), schemaCache)
}
