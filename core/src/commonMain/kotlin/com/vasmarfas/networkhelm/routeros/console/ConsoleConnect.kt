package com.vasmarfas.networkhelm.routeros.console

import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.routeros.RouterOsSession
import com.vasmarfas.networkhelm.routeros.RouterOsVersion
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.api.ApiTrapException
import com.vasmarfas.networkhelm.routeros.api.call
import com.vasmarfas.networkhelm.routeros.readDevice
import com.vasmarfas.networkhelm.routeros.schema.MemorySchemaCache
import com.vasmarfas.networkhelm.routeros.schema.SchemaCache
import com.vasmarfas.networkhelm.session.NativeSide
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.uidefs.WebAccess
import kotlinx.coroutines.CoroutineScope

internal const val CONSOLE_COLUMNS = 4000
internal const val CONSOLE_ROWS = 100

suspend fun connectRouterOsConsole(
    ssh: SshSession,
    user: String,
    scope: CoroutineScope,
    schemaCache: SchemaCache = MemorySchemaCache(),
    dialects: List<Dialect> = Dialect.entries,
    encoding: ApiEncoding = ApiEncoding.Utf8,
    web: WebAccess? = null,
    native: NativeSide? = null,
): RouterOsSession? {
    val open: suspend () -> ShellChannel = { ssh.shell("xterm-256color", CONSOLE_COLUMNS, CONSOLE_ROWS) }
    for (dialect in dialects) {
        val client = ConsoleClient(dialect = dialect, encoding = encoding, alive = { ssh.connected }, open = open)
        val session = try {
            if (understood(client, dialect)) {
                client.warm(scope)
                val device = readDevice(client, user)
                RouterOsSession(client, device, capabilities(device, dialect), ConsoleFilesSsh(ssh, ConsoleFiles(client, dialect, encoding)), schemaCache, scope = scope, web = web, native = native)
            } else {
                null
            }
        } catch (e: Exception) {
            client.close()
            throw e
        }
        if (session != null) return session
        client.close()
    }
    return null
}

private suspend fun understood(client: ConsoleClient, dialect: Dialect) = try {
    when (dialect) {
        Dialect.Serialize -> client.call("/system/identity/print").rows.isNotEmpty()
        Dialect.Classic -> RouterOsVersion.parse(client.call("/system/resource/print").rows.firstOrNull()?.get("version").orEmpty())?.major in 6..7
    }
} catch (e: ApiTrapException) {
    false
}

private fun capabilities(device: DeviceInfo, dialect: Dialect): Capabilities {
    val version = RouterOsVersion.parse(device.version)
    return Capabilities(
        subscriptions = false,
        streamingCommands = false,
        cancel = false,
        safeMode = SafeModeKind.SshConsole,
        terminal = true,
        files = true,
        export = version != null && version.major >= 7,
        menuContents = dialect == Dialect.Serialize,
    )
}
