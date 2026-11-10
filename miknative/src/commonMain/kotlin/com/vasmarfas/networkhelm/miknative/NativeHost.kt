package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.console.connectRouterOsConsole
import com.vasmarfas.networkhelm.routeros.schema.MemorySchemaCache
import com.vasmarfas.networkhelm.routeros.schema.SchemaCache
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.NativeSide
import com.vasmarfas.networkhelm.session.SshHostSession
import com.vasmarfas.networkhelm.ssh.Jump
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.openTcp
import com.vasmarfas.networkhelm.uidefs.WebAccess
import kotlinx.coroutines.CoroutineScope

suspend fun connectNativeHost(host: String, port: Int, user: String, password: String, encoding: ApiEncoding, scope: CoroutineScope, schemaCache: SchemaCache = MemorySchemaCache()): DeviceSession {
    val native = connectNative(host, user, password, scope, port)
    return consoleHost(native, user, encoding, scope, DeviceInfo(Platform.Ssh, "RouterOS", "TCP 8291", "", "", emptySet(), setOf("read", "write")), schemaCache, WebAccess(host) { webPort -> openTcp(host, webPort) }.takeIf { host != Jump.LOOPBACK }, native.side(port))
}

suspend fun connectNativeHost(host: String, port: Int, user: String, password: String, scope: CoroutineScope): DeviceSession =
    connectNativeHost(host, port, user, password, ApiEncoding.Utf8, scope)

suspend fun consoleHost(ssh: SshSession, user: String, encoding: ApiEncoding, scope: CoroutineScope, fallback: DeviceInfo, schemaCache: SchemaCache = MemorySchemaCache(), web: WebAccess? = null, native: NativeSide? = null): DeviceSession {
    return try {
        connectRouterOsConsole(ssh, user, scope, schemaCache, encoding = encoding, web = web, native = native) ?: SshHostSession(ssh, fallback)
    } catch (e: Exception) {
        ssh.close()
        throw e
    }
}
