package com.vasmarfas.networkhelm

import com.vasmarfas.networkhelm.gateway.GatewayHost
import com.vasmarfas.networkhelm.gateway.GatewayStore
import com.vasmarfas.networkhelm.gateway.LoginForm
import com.vasmarfas.networkhelm.gateway.LoginPage
import com.vasmarfas.networkhelm.gateway.Reach
import com.vasmarfas.networkhelm.gateway.SetUpCode
import com.vasmarfas.networkhelm.miknative.nativeSide
import com.vasmarfas.networkhelm.openwrt.OpenWrtLogin
import com.vasmarfas.networkhelm.openwrt.QuickJsForms
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.server.FileGatewayStore
import com.vasmarfas.networkhelm.server.Gateway
import com.vasmarfas.networkhelm.server.KtorWire
import com.vasmarfas.networkhelm.server.Login
import com.vasmarfas.networkhelm.server.Session
import com.vasmarfas.networkhelm.server.administer
import com.vasmarfas.networkhelm.server.isLocal
import com.vasmarfas.networkhelm.server.jvmReach
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.connectOpenWrtWithSsh
import com.vasmarfas.networkhelm.session.connectWithSsh
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.TrustStore
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    val home = File(System.getenv("NETWORKHELM_GATEWAY_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.networkhelm-gateway"))
    administer(home, args, System.getenv(), ::ask, ::println)?.let { exitProcess(it) }
    fun option(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val host = option("--host") ?: "0.0.0.0"
    val port = option("--port")?.toIntOrNull() ?: 8443
    val password = System.getenv("NETWORKHELM_GATEWAY_PASSWORD")?.takeIf { it.isNotBlank() }
    val gateway = Gateway.open(home, public = "--public" in args, code = "--code" in args, password = password, announce = ::println)
    val keyStore = gateway.keyStore()
    println("NetworkHelm gateway on https://$host:$port, certificate SHA-256 ${Gateway.fingerprint(keyStore)}")
    if (!gateway.public) println("Answers the local networks only; --public opens it to any address")
    val web = System.getenv("NETWORKHELM_GATEWAY_WEB")?.takeIf { it.isNotBlank() }?.let(::File)
    if (web == null && Gateway::class.java.getResource("/web/index.html") == null) {
        println("No web app: build the jar with ./gradlew :server:buildFatJar, or name a build of :app:webApp in NETWORKHELM_GATEWAY_WEB")
    }
    val server = serve(gateway, host, port, web)
    Runtime.getRuntime().addShutdownHook(Thread(server::close))
    Thread.currentThread().join()
}

private fun ask(prompt: String): String? = System.console()?.readPassword(prompt)?.concatToString()

// a login waits for its key on one of two threads, so a flood of them leaves the rest of Dispatchers.IO to the routers
private val logins = Dispatchers.IO.limitedParallelism(2)

fun serve(
    gateway: Gateway,
    host: String,
    port: Int,
    web: File?,
    trust: TrustStore = gateway.trust,
    store: (Session) -> GatewayStore = { FileGatewayStore(gateway.home, it.master.sealing) },
): AutoCloseable {
    val keyStore = gateway.keyStore()
    val server = embeddedServer(Netty, applicationEnvironment { log = LoggerFactory.getLogger("networkhelm.gateway") }, {
        sslConnector(keyStore, Gateway.KEY_ALIAS, gateway::keyStorePassword, gateway::keyStorePassword) {
            this.host = host
            this.port = port
        }
    }) { gateway(gateway, web = web, trust = trust, store = store) }
    try {
        server.start(wait = false)
    } catch (e: Exception) {
        server.stop(0, 0)
        throw e
    }
    return AutoCloseable { server.stop(500, 2_000) }
}

fun Application.gateway(
    gateway: Gateway,
    connectRouterOs: suspend (RouterOsLogin, TrustPolicy, CoroutineScope) -> DeviceSession = { login, trust, scope -> connectWithSsh(login, trust, scope, gateway.schemas, login.nativeSide(scope)) },
    connectOpenWrt: suspend (OpenWrtLogin, TrustPolicy) -> DeviceSession = { login, trust -> connectOpenWrtWithSsh(login, trust, ::QuickJsForms) },
    web: File? = null,
    trust: TrustStore = gateway.trust,
    store: (Session) -> GatewayStore = { FileGatewayStore(gateway.home, it.master.sealing) },
    reach: Reach = jvmReach(gateway.schemas),
) {
    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 60.seconds
        // a file of the router travels whole, as base64 inside one message
        maxFrameSize = 128L * 1024 * 1024
    }
    install(Compression)
    intercept(ApplicationCallPipeline.Plugins) {
        if (!gateway.public && !isLocal(context.request.origin.remoteAddress)) {
            context.respond(HttpStatusCode.Forbidden)
            finish()
            return@intercept
        }
        context.response.headers.append("X-Frame-Options", "DENY")
        context.response.headers.append("X-Content-Type-Options", "nosniff")
        context.response.headers.append("Referrer-Policy", "no-referrer")
    }
    routing {
        get("/login") {
            if (gateway.session(call.request.cookies[Gateway.COOKIE]) != null) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respondText(Json.encodeToString(LoginPage.serializer(), LoginPage(gateway.asksCode, gateway.settingUp)), ContentType.Application.Json, HttpStatusCode.Unauthorized)
            }
        }
        post("/login") {
            val form = try {
                Json.decodeFromString(LoginForm.serializer(), call.receiveText())
            } catch (e: SerializationException) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            when (val login = withContext(logins) { gateway.login(call.request.origin.remoteAddress, form.user, form.password, form.code) }) {
                is Login.In -> {
                    call.response.cookies.append(Cookie(Gateway.COOKIE, login.token, httpOnly = true, secure = true, path = "/", extensions = mapOf("SameSite" to "Strict")))
                    call.respond(HttpStatusCode.NoContent)
                }
                is Login.SetUp -> {
                    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                    call.respondText(Json.encodeToString(SetUpCode.serializer(), SetUpCode(login.otpauth)), ContentType.Application.Json)
                }
                Login.Refused -> call.respond(HttpStatusCode.Unauthorized)
            }
        }
        post("/logout") {
            gateway.logout(call.request.cookies[Gateway.COOKIE])
            call.response.cookies.append(Cookie(Gateway.COOKIE, "", httpOnly = true, secure = true, path = "/", maxAge = 0, extensions = mapOf("SameSite" to "Strict")))
            call.respond(HttpStatusCode.NoContent)
        }
        webSocket("/session") {
            val token = call.request.cookies[Gateway.COOKIE]
            val job = coroutineContext.job
            val session = if (sameOrigin(call)) gateway.attach(token, job) else null
            if (session == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "log in to the gateway first"))
                return@webSocket
            }
            val expiry = launch {
                while (isActive) {
                    delay(1_000)
                    gateway.session(token)
                }
            }
            try {
                val wire = KtorWire(this) {
                    if (gateway.session(token) !== session) throw CancellationException("the gateway session has ended")
                }
                // the threads of the websocket serve every browser, the work of the routers goes elsewhere
                withContext(Dispatchers.Default) { GatewayHost(wire, trust, connectRouterOs, connectOpenWrt, store(session), reach = reach).serve() }
            } finally {
                expiry.cancel()
                gateway.detach(session, job)
            }
        }
        if (web != null) staticFiles("/", web) else staticResources("/", "web")
    }
}

// another site's page can open this WebSocket with the gateway cookie; a browser names that page in Origin
// a client that is not a browser sends no Origin
private fun sameOrigin(call: ApplicationCall): Boolean {
    val origin = call.request.headers[HttpHeaders.Origin] ?: return true
    return try {
        URI.create(origin).authority == call.request.headers[HttpHeaders.Host]
    } catch (e: IllegalArgumentException) {
        false
    }
}
