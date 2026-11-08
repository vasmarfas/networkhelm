package com.vasmarfas.networkhelm.routeros

import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.routeros.api.ApiClient
import com.vasmarfas.networkhelm.routeros.api.ApiConnection
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.api.ApiTrapException
import com.vasmarfas.networkhelm.routeros.api.RouterOsClient
import com.vasmarfas.networkhelm.routeros.api.call
import com.vasmarfas.networkhelm.routeros.rest.RestClient
import com.vasmarfas.networkhelm.routeros.schema.MemorySchemaCache
import com.vasmarfas.networkhelm.routeros.schema.SchemaCache
import com.vasmarfas.networkhelm.session.NativeSide
import com.vasmarfas.networkhelm.ssh.Jump
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.Base64Bytes
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.openTcp
import com.vasmarfas.networkhelm.transport.openTlsTrusted
import com.vasmarfas.networkhelm.uidefs.WebAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

enum class RouterOsProtocol(val tls: Boolean, val rest: Boolean, val defaultPort: Int) {
    ApiSsl(tls = true, rest = false, defaultPort = 8729),
    Api(tls = false, rest = false, defaultPort = 8728),
    RestSsl(tls = true, rest = true, defaultPort = 443),
    Rest(tls = false, rest = true, defaultPort = 80),
}

@Serializable
class RouterOsLogin(
    val host: String,
    val user: String,
    val password: String,
    val protocol: RouterOsProtocol = RouterOsProtocol.ApiSsl,
    val port: Int = protocol.defaultPort,
    val encoding: ApiEncoding = ApiEncoding.Utf8,
    @Serializable(with = Base64Bytes::class) val sshKey: ByteArray? = null,
    val sshAgent: Boolean = false,
    val sshPort: Int = 22,
    val sshPassphrase: String? = null,
    val webPort: Int? = null,
    val nativePort: Int? = null,
)

private const val LOGIN_MILLIS = 10_000L

class LoginTimeoutException : DeviceException(ErrorCategory.ConnectionLost, "the router did not answer the login")

suspend fun connectRouterOs(
    login: RouterOsLogin,
    trust: TrustPolicy,
    scope: CoroutineScope,
    ssh: SshSession? = null,
    schemaCache: SchemaCache = MemorySchemaCache(),
    native: NativeSide? = null,
): RouterOsSession {
    suspend fun stream() = if (login.protocol.tls) openTlsTrusted(login.host, login.port, trust) else openTcp(login.host, login.port)

    suspend fun open(): ApiConnection {
        val connection = ApiConnection(stream(), login.encoding, scope)
        try {
            withTimeoutOrNull(LOGIN_MILLIS) { connection.login(login.user, login.password) } ?: throw LoginTimeoutException()
        } catch (e: Exception) {
            connection.close()
            throw if (e is ApiTrapException) DeviceException(ErrorCategory.PermissionDenied, e.message.orEmpty(), e) else e
        }
        return connection
    }

    val client: RouterOsClient =
        if (login.protocol.rest) RestClient(login.host, login.user, login.password) { stream() } else ApiClient(open(), scope) { open() }
    return try {
        val device = readDevice(client, login.user)
        val web = when {
            login.host != Jump.LOOPBACK -> WebAccess(login.host) { port -> openTcp(login.host, port) }
            login.webPort != null -> WebAccess(login.host) { openTcp(login.host, login.webPort) }
            else -> null
        }
        RouterOsSession(client, device, capabilities(device, login.protocol, ssh), ssh, schemaCache, scope = scope, web = web, native = native)
    } catch (e: Exception) {
        client.close()
        throw e
    }
}

internal suspend fun readDevice(client: RouterOsClient, user: String): DeviceInfo = coroutineScope {
    val resource = async { client.call("/system/resource/print").rows.first() }
    val packages = async {
        client.call("/system/package/print", "=.proplist=name,disabled,available").rows
            // 7.x lists packages that could be installed too, with available=true
            .filter { it["disabled"] != "true" && it["available"] != "true" }
            .mapNotNull { it["name"] }
            .toSet()
    }
    val identity = async { client.call("/system/identity/print").rows.first().getValue("name") }
    val policies = async { policies(client, user) }
    val deviceMode = async { optional { client.call("/system/device-mode/print").rows.firstOrNull() }.orEmpty() }
    val id = async { optional { client.call("/system/license/print").rows.firstOrNull()?.let { it["software-id"] ?: it["system-id"] } } }
    val read = resource.await()
    DeviceInfo(
        platform = Platform.RouterOs,
        identity = identity.await(),
        version = read.getValue("version"),
        architecture = read.getValue("architecture-name"),
        board = read.getValue("board-name"),
        packages = packages.await(),
        policies = policies.await(),
        deviceMode = deviceMode.await(),
        id = id.await(),
    )
}

private suspend fun policies(client: RouterOsClient, user: String): Set<String> {
    val group = optional { client.call("/user/print", "=.proplist=group", "?name=$user").rows.firstOrNull()?.get("group") }
        ?: return emptySet()
    val policy = optional { client.call("/user/group/print", "=.proplist=policy", "?name=$group").rows.firstOrNull()?.get("policy") }
        ?: return emptySet()
    return policy.split(',').filterNot { it.startsWith("!") }.toSet()
}

private suspend fun <T> optional(block: suspend () -> T?): T? = try {
    block()
} catch (e: ApiTrapException) {
    null
}

private fun capabilities(device: DeviceInfo, protocol: RouterOsProtocol, ssh: SshSession?): Capabilities {
    val version = RouterOsVersion.parse(device.version)
    return Capabilities(
        subscriptions = !protocol.rest,
        streamingCommands = !protocol.rest,
        cancel = !protocol.rest,
        safeMode = when {
            !protocol.rest && version != null && version >= RouterOsVersion(7, 18) -> SafeModeKind.ApiSession
            ssh != null -> SafeModeKind.SshConsole
            else -> SafeModeKind.None
        },
        terminal = ssh != null,
        files = ssh != null,
        export = version != null && version.major >= 7,
        encrypted = protocol.tls,
        menuContents = version == null || version.major >= 7,
    )
}
