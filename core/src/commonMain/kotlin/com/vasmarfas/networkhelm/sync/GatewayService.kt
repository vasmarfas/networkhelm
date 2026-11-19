package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.gateway.LoginForm
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import io.ktor.http.Url
import kotlinx.serialization.builtins.ListSerializer

// the gateway of NetworkHelm, on a server or in the app of a desktop: it keeps the files and tells of a change as it comes. Address is
// the gateway itself, https://192.0.2.10:8443; the token comes once from gatewayToken
class GatewayService(private val http: SyncHttp, address: String, token: String) : SyncService {
    private val files = address.trimEnd('/') + "/sync/files"
    private val waits = address.trimEnd('/') + "/sync/changes"
    private val server = Url(address).host
    private val login = mapOf("Authorization" to "Bearer $token")

    override suspend fun list(): List<Listed> {
        val reply = send("GET", files)
        if (reply.status != 200) throw answered(server, "${reply.status}")
        val listed = try {
            fileJson.decodeFromString(ListSerializer(Listed.serializer()), reply.body.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw notTheGateway(server)
        }
        return listed.filter { syncFile(it.path) }
    }

    override suspend fun read(path: String): Stored? {
        val reply = send("GET", "$files/$path")
        return when (reply.status) {
            200 -> Stored(reply.body, reply.header("ETag").orEmpty())
            404 -> null
            else -> throw answered(server, "${reply.status}")
        }
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        val reply = send("PUT", "$files/$path", emptyMap(), bytes)
        if (reply.status != 204) throw answered(server, "${reply.status}")
    }

    override suspend fun writeIf(path: String, bytes: ByteArray, over: Stored?): Boolean {
        val reply = send("PUT", "$files/$path", if (over == null) mapOf("If-None-Match" to "*") else mapOf("If-Match" to over.tag), bytes)
        if (reply.status == 412) return false
        if (reply.status != 204) throw answered(server, "${reply.status}")
        return true
    }

    override suspend fun delete(path: String) {
        val reply = send("DELETE", "$files/$path")
        if (reply.status != 204) throw answered(server, "${reply.status}")
    }

    // the version of the files once it is another than the one given: at once when they changed since, else as soon as they change,
    // or the same version after a minute without a change
    suspend fun changes(after: Long): Long {
        val reply = send("GET", "$waits?after=$after")
        if (reply.status != 200) throw answered(server, "${reply.status}")
        return reply.body.decodeToString().toLongOrNull() ?: throw notTheGateway(server)
    }

    private suspend fun send(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): Reply {
        val reply = http.send(method, url, login + headers, body?.let { outgoing(it, "application/octet-stream") })
        if (reply.status == 401) throw loginRefused(server)
        return reply
    }
}

// the token of this device, given once for the user and the password of the gateway and the code of its authenticator app
suspend fun gatewayToken(http: SyncHttp, address: String, user: String, password: String, code: String): String {
    val server = Url(address).host
    val form = fileJson.encodeToString(LoginForm.serializer(), LoginForm(user, password, code)).encodeToByteArray()
    val reply = http.send("POST", address.trimEnd('/') + "/sync/login", body = outgoing(form, "application/json"))
    return when (reply.status) {
        200 -> reply.body.decodeToString()
        401 -> throw loginRefused(server)
        403 -> throw DeviceException(ErrorCategory.PermissionDenied, "$server asks to set up the code of an authenticator app on its page first")
        else -> throw answered(server, "${reply.status}")
    }
}

private fun notTheGateway(server: String) = DeviceException(ErrorCategory.Api, "$server did not answer as the API of NetworkHelm")
