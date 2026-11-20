package com.vasmarfas.networkhelm.server

import com.vasmarfas.networkhelm.LOGIN_MOST
import com.vasmarfas.networkhelm.gateway.LoginForm
import com.vasmarfas.networkhelm.logins
import com.vasmarfas.networkhelm.sync.Listed
import com.vasmarfas.networkhelm.sync.syncFile
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val FILE_MOST = 64 * 1024 * 1024

// a device waits this long for a change before it asks again, within the minute after which proxies drop a silent connection
private const val WAIT = 50_000L

// the page on GitHub Pages synchronises through the gateway from an address of its own: the browser lets it send the token and the
// conditions of a write, and read the answers with their ETag, only where the gateway names that address (CORS)
private const val PAGE = "https://vasmarfas.github.io"

// the synchronisation of devices through the gateway: a token for the login of the gateway and the code of its authenticator app, then
// the files of the vault by that token and a wait for their next change. Only paths of a vault are served, so no other file of the
// home of the gateway can be named
fun Route.synchronisation(gateway: Gateway) {
    val files = SyncFiles(gateway.home)
    val tokens = SyncTokens(gateway.home)
    options("/sync/{path...}") {
        if (call.letPageIn()) {
            call.response.headers.append(HttpHeaders.AccessControlAllowMethods, "GET, PUT, POST, DELETE")
            call.response.headers.append(HttpHeaders.AccessControlAllowHeaders, "Authorization, Content-Type, If-Match, If-None-Match")
            call.response.headers.append(HttpHeaders.AccessControlMaxAge, "600")
        }
        call.respond(HttpStatusCode.NoContent)
    }
    post("/sync/login") {
        call.letPageIn()
        val form = try {
            call.bodyUpTo(LOGIN_MOST)?.let { Json.decodeFromString(LoginForm.serializer(), it.decodeToString()) }
        } catch (e: SerializationException) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        if (form == null) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@post
        }
        when (val login = withContext(logins) { gateway.login(call.request.origin.remoteAddress, form.user, form.password, form.code) }) {
            // the session of a browser that the login opens is not needed: the device keeps its token instead
            is Login.In -> {
                gateway.logout(login.token)
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.respondText(tokens.give())
            }
            is Login.SetUp -> call.respond(HttpStatusCode.Forbidden)
            Login.Refused -> call.respond(HttpStatusCode.Unauthorized)
        }
    }
    get("/sync/files") {
        call.letPageIn()
        if (signedIn(tokens)) call.respondText(Json.encodeToString(ListSerializer(Listed.serializer()), files.list()), ContentType.Application.Json)
    }
    get("/sync/files/{path...}") {
        call.letPageIn()
        if (!signedIn(tokens)) return@get
        val stored = call.vaultPath()?.let { files.read(it) }
        if (stored == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.headers.append(HttpHeaders.ETag, "\"${stored.tag}\"")
        call.respondBytes(stored.bytes, ContentType.Application.OctetStream)
    }
    put("/sync/files/{path...}") {
        call.letPageIn()
        if (!signedIn(tokens)) return@put
        val path = call.vaultPath()
        if (path == null) {
            call.respond(HttpStatusCode.NotFound)
            return@put
        }
        val bytes = call.bodyUpTo(FILE_MOST)
        if (bytes == null) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@put
        }
        val match = call.request.headers[HttpHeaders.IfMatch]
        val absent = call.request.headers[HttpHeaders.IfNoneMatch] == "*"
        val written = files.write(path, bytes) { current ->
            when {
                absent -> current == null
                match != null -> current?.tag == match.removeSurrounding("\"")
                else -> true
            }
        }
        call.respond(if (written) HttpStatusCode.NoContent else HttpStatusCode.PreconditionFailed)
    }
    delete("/sync/files/{path...}") {
        call.letPageIn()
        if (!signedIn(tokens)) return@delete
        val path = call.vaultPath()
        if (path == null) {
            call.respond(HttpStatusCode.NotFound)
            return@delete
        }
        files.delete(path)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/sync/changes") {
        call.letPageIn()
        if (signedIn(tokens)) call.respondText(files.changes(call.request.queryParameters["after"]?.toLongOrNull() ?: 0, WAIT).toString())
    }
}

private suspend fun RoutingContext.signedIn(tokens: SyncTokens): Boolean {
    if (tokens.valid(call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer "))) return true
    call.respond(HttpStatusCode.Unauthorized)
    return false
}

// true where the request comes from the page on GitHub Pages, which the browser then lets read the answer; a page of another address
// is named nothing, and the browser keeps the answer from it
private fun ApplicationCall.letPageIn(): Boolean {
    response.headers.append(HttpHeaders.Vary, HttpHeaders.Origin)
    if (request.headers[HttpHeaders.Origin] != PAGE) return false
    response.headers.append(HttpHeaders.AccessControlAllowOrigin, PAGE)
    response.headers.append(HttpHeaders.AccessControlExposeHeaders, HttpHeaders.ETag)
    return true
}

private fun ApplicationCall.vaultPath(): String? = parameters.getAll("path")?.joinToString("/")?.takeIf(::syncFile)

// the body up to most bytes, null when it is longer; a read with a limit ends quietly where the body was cut off, so the channel is
// asked for its error and the length told is checked, and half a file is never kept
private suspend fun ApplicationCall.bodyUpTo(most: Int): ByteArray? {
    val told = request.contentLength()
    if (told != null && told > most) return null
    val channel = receiveChannel()
    val bytes = channel.readBuffer(most + 1L).readByteArray()
    channel.closedCause?.let { throw it }
    check(told == null || bytes.size.toLong() == told) { "the body ended after ${bytes.size} of $told bytes" }
    return bytes.takeIf { it.size <= most }
}
