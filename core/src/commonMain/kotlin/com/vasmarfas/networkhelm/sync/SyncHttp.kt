package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.TrustKind
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.rejected
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlin.concurrent.Volatile

// a body written as it goes out, so that a large file need not sit in memory whole; it is written again when a certificate was
// accepted only after the first attempt
class Outgoing(val type: String, val length: Long?, val write: suspend (ByteWriteChannel) -> Unit)

fun outgoing(bytes: ByteArray, type: String) = Outgoing(type, bytes.size.toLong()) { it.writeFully(bytes) }

// every status is an answer; a header by its name in any case
class Reply(val status: Int, private val headers: Headers, val body: ByteArray) {
    fun header(name: String): String? = headers[name]
}

// the certificate a server showed that neither the store of the system nor the pin took, kept by the client of that server
internal class Pinning(val pin: String?) {
    @Volatile
    var rejected: String? = null
}

internal expect fun httpClient(pinning: Pinning): HttpClient

internal fun HttpClientConfig<*>.limits() {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) { requestTimeoutMillis = 120_000 }
}

private const val TRUST_ROUNDS = 3
private const val LARGEST = 64L * 1024 * 1024
private val REPEATED = setOf("GET", "HEAD", "PROPFIND", "DELETE")
private val LOCAL_NAMES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".localhost")

// the HTTP of the services of synchronisation: a certificate passes the check of the system, or is the one the user accepted for a
// server of his own in the window routers ask in. A client is made with the first request to a server, so none exists before
class SyncHttp internal constructor(private val trust: TrustPolicy, private val client: (Pinning) -> HttpClient) {
    constructor(trust: TrustPolicy) : this(trust, ::httpClient)

    private val lock = Mutex()
    private val clients = HashMap<String, Pair<Pinning, HttpClient>>()

    suspend fun send(method: String, url: String, headers: Map<String, String> = emptyMap(), body: Outgoing? = null): Reply {
        val target = Url(url)
        var seen = ""
        var repeated = false
        repeat(TRUST_ROUNDS) {
            val pin = if (target.protocol == URLProtocol.HTTPS) trust.known(target.host, target.port, TrustKind.Tls) else null
            val (pinning, http) = clientFor(target, pin)
            val reply = try {
                // streamed, since a plain request of ktor takes the whole body into memory before the limit is checked
                http.prepareRequest(url) {
                    this.method = HttpMethod(method)
                    for ((name, value) in headers) header(name, value)
                    if (body != null) setBody(content(body))
                }.execute { response ->
                    val bytes = read(target.host, response)
                    Reply(response.status.value, response.headers, bytes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceException) {
                throw e
            } catch (e: Throwable) {
                val shown = pinning.rejected
                if (shown == null) {
                    // a server closes a kept-alive connection as the client takes it from its pool: a read goes once more, a write never
                    if (repeated || method !in REPEATED) throw unanswered(target.host, e)
                    repeated = true
                } else {
                    if (!pinnable(target)) throw untrusted(target.host)
                    seen = shown
                    pinning.rejected = null
                    if (!trust.confirm(target.host, target.port, TrustKind.Tls, seen, pin)) throw rejected(target.host, seen)
                }
                null
            }
            if (reply != null) return reply
        }
        throw rejected(target.host, seen)
    }

    fun close() {
        clients.values.forEach { it.second.close() }
        clients.clear()
    }

    private suspend fun clientFor(target: Url, pin: String?): Pair<Pinning, HttpClient> = lock.withLock {
        val origin = "${target.protocol.name}://${target.host}:${target.port}"
        clients[origin]?.takeIf { it.first.pin == pin }?.let { return it }
        clients.remove(origin)?.second?.close()
        val pinning = Pinning(pin)
        (pinning to client(pinning)).also { clients[origin] = it }
    }
}

private fun content(body: Outgoing): OutgoingContent = object : OutgoingContent.WriteChannelContent() {
    override val contentType = ContentType.parse(body.type)
    override val contentLength = body.length

    override suspend fun writeTo(channel: ByteWriteChannel) = body.write(channel)
}

private suspend fun read(host: String, response: HttpResponse): ByteArray {
    val (bytes, cut) = try {
        val channel = response.bodyAsChannel()
        channel.readBuffer(LARGEST + 1).readByteArray() to channel.closedCause
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw unanswered(host, e)
    }
    // a read with a limit stops quietly where the body was cut off: only the channel keeps the error
    if (cut != null) throw unanswered(host, cut)
    if (bytes.size > LARGEST) throw DeviceException(ErrorCategory.Api, "$host sent more than 64 MiB")
    return bytes
}

private fun unanswered(host: String, e: Throwable) = DeviceException(ErrorCategory.ConnectionLost, "$host does not answer: ${e.message ?: e::class.simpleName}")

// a certificate the system does not trust is pinned only for a server of the user's own: an address, a name of the local network, or a
// port other than that of HTTPS, as the gateway listens on; a public service such as webdav.yandex.ru or api.github.com shows one the
// system trusts, and another one there is someone on the way
private fun pinnable(target: Url): Boolean {
    val host = target.host.lowercase().removePrefix("[").removeSuffix("]")
    return target.port != 443 || '.' !in host || ':' in host || host.all { it.isDigit() || it == '.' } || LOCAL_NAMES.any { host.endsWith(it) }
}

private fun untrusted(host: String) = DeviceException(ErrorCategory.PermissionDenied, "the system does not trust the certificate of $host: a public service needs one it trusts")
