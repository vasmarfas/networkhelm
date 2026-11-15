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

private val SERIALIZING = RouterOsVersion(7, 17)
private const val SERIALIZE_TRIES = 3

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

internal suspend fun understood(client: ConsoleClient, dialect: Dialect): Boolean = when (dialect) {
    // a router of 7.17 or later whose console missed a line is asked again: the classic dialect leaves its menus undescribed
    Dialect.Serialize -> identified(client) || answers { serializes(client) } && (1 until SERIALIZE_TRIES).any { identified(client) }
    Dialect.Classic -> answers { RouterOsVersion.parse(client.call("/system/resource/print").rows.firstOrNull()?.get("version").orEmpty())?.major in 6..7 }
}

private suspend fun identified(client: ConsoleClient) = answers { client.call("/system/identity/print").rows.isNotEmpty() }

private suspend fun answers(probe: suspend () -> Boolean) = try {
    probe()
} catch (e: ApiTrapException) {
    false
}

private suspend fun serializes(client: ConsoleClient) =
    RouterOsVersion.parse(client.call(listOf("/execute", "=script=:put [/system resource get version]")).done["ret"].orEmpty())?.let { it >= SERIALIZING } == true

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
