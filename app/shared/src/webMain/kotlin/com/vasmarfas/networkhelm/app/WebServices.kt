@file:OptIn(ExperimentalWasmJsInterop::class)
@file:Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")

package com.vasmarfas.networkhelm.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.vasmarfas.networkhelm.App
import com.vasmarfas.networkhelm.gateway.GatewayClient
import com.vasmarfas.networkhelm.gateway.LoginForm
import com.vasmarfas.networkhelm.gateway.LoginPage
import com.vasmarfas.networkhelm.gateway.SetUpCode
import com.vasmarfas.networkhelm.gateway.Wire
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.openwrt.UbusWeb
import com.vasmarfas.networkhelm.openwrt.connectOpenWrt
import com.vasmarfas.networkhelm.transport.BrowserTrust
import com.vasmarfas.networkhelm.transport.HttpResponse
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.browserHttp
import com.vasmarfas.networkhelm.ui.connect.GatewayAnswer
import com.vasmarfas.networkhelm.ui.connect.GatewayLogin
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import eu.anifantakis.lib.ksafe.KSafe
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.w3c.dom.WebSocket
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.Promise

private val json = Json { ignoreUnknownKeys = true }

@Composable
fun WebApp() {
    val scope = rememberCoroutineScope()
    var gateway by remember { mutableStateOf<GatewayClient?>(null) }
    var page by remember { mutableStateOf<LoginPage?>(null) }
    var router by remember { mutableStateOf<Services?>(null) }
    suspend fun start() {
        when (val served = served()) {
            Served.Router -> router = routerServices()
            Served.Standalone -> router = standaloneServices()
            Served.Session -> {
                gateway = open(scope)
                if (gateway == null) page = FullLogin
            }
            is Served.Login -> page = served.page
        }
    }
    LaunchedEffect(Unit) { start() }
    val direct = router
    val opened = gateway
    val login = page
    when {
        direct != null -> App(direct)
        opened != null -> key(opened) {
            LaunchedEffect(opened) {
                opened.ended.await()
                // run in the page scope: the effect is cancelled with the app once gateway is cleared
                scope.launch {
                    gateway = null
                    start()
                }
            }
            App(remember(opened) { gatewayServices(opened) })
        }
        login != null -> NetworkHelmTheme {
            GatewayLogin(login) { user, password, code ->
                val answer = logIn(user, password, code)
                if (answer != GatewayAnswer.In) return@GatewayLogin answer
                gateway = open(scope) ?: return@GatewayLogin GatewayAnswer.Unreachable
                answer
            }
        }
    }
}

private val FullLogin = LoginPage(code = true, setUp = false)

private sealed interface Served {
    data object Session : Served

    class Login(val page: LoginPage) : Served

    data object Router : Served

    data object Standalone : Served
}

private suspend fun served(): Served = if (standalone()) Served.Standalone else try {
    val response = window.fetch("/login", requestInit(null, null, null)).await<Response>()
    when (response.status.toInt()) {
        204 -> Served.Session
        404 -> Served.Router
        else -> Served.Login(json.decodeFromString(LoginPage.serializer(), response.text().await<JsString>().toString()))
    }
} catch (e: JsException) {
    Served.Login(FullLogin)
} catch (e: SerializationException) {
    Served.Login(FullLogin)
}

private suspend fun routerServices(): Services {
    val host = window.location.hostname
    val encrypted = window.location.protocol == "https:"
    val storage = BrowserStorage()
    val saved = SavedRouters(storage).also { it.load() }
    if (saved.routers.value.isEmpty()) saved.save(SavedRouter(window.location.host, "root", if (encrypted) Access.OpenWrtUbusSsl else Access.OpenWrtUbus))
    return Services(
        storage,
        MemoryTrustStore(),
        neighbors = null,
        scan = null,
        syncLock = null,
        connectOpenWrt = { login, _ ->
            if (login.host != host) throw DeviceException(ErrorCategory.Unsupported, "this page reaches only $host, the router that served it")
            connectOpenWrt(login.user, login.password, FetchUbusWeb(), encrypted)
        },
        connectLogin = { _, _ -> throw DeviceException(ErrorCategory.Unsupported, "this page reaches only $host, the OpenWrt router that served it") },
        connect = { _, _, _ -> throw DeviceException(ErrorCategory.Unsupported, "this page reaches only $host, the OpenWrt router that served it") },
    )
}

private suspend fun standaloneServices(): Services {
    browserHttp = true
    val storage = BrowserStorage()
    val saved = SavedRouters(storage).also { it.load() }
    if (saved.failure == null && storage.read(DEMO_SEEDED) == null) {
        demoRouters().forEach { saved.save(it) }
        storage.write(DEMO_SEEDED, "yes")
    }
    // passwords and the keys of synchronisation lie in localStorage under a WebCrypto key that never leaves IndexedDB
    return Services(storage, BrowserTrust(), neighbors = null, secrets = KSafeSecrets(KSafe(fileName = "routers")), scan = null, standalone = true).withDemo()
}

private const val DEMO_SEEDED = "demo-seeded"

private class BrowserStorage : Storage {
    override suspend fun read(name: String): String? = localStorage.getItem("networkhelm.$name")

    override suspend fun write(name: String, text: String) = localStorage.setItem("networkhelm.$name", text)
}

private class FetchUbusWeb : UbusWeb {
    override suspend fun post(json: String): HttpResponse {
        val headers = Headers().apply { append("Content-Type", "application/json") }
        val response = try {
            window.fetch("/ubus", requestInit("POST", headers, json)).await<Response>()
        } catch (e: JsException) {
            throw DeviceException(ErrorCategory.ConnectionLost, "the router does not answer: ${e.message}")
        }
        return HttpResponse(response.status.toInt(), response.text().await<JsString>().toString().encodeToByteArray())
    }

    override fun lines(target: String, headers: Map<String, String>): Flow<String> = flow {
        val asked = Headers().apply { headers.forEach { (name, value) -> append(name, value) } }
        val response = try {
            window.fetch(target, requestInit(null, asked, null)).await<Response>()
        } catch (e: JsException) {
            throw DeviceException(ErrorCategory.ConnectionLost, "the router does not answer: ${e.message}")
        }
        val status = response.status.toInt()
        if (status != 200) throw DeviceException(if (status == 401 || status == 403) ErrorCategory.PermissionDenied else ErrorCategory.Api, "HTTP $status on $target")
        val reader = reader(response)
        val decoder = decoder()
        val pending = StringBuilder()
        try {
            while (true) {
                val chunk = read(reader).await<JsAny>()
                if (done(chunk)) break
                pending.append(decode(decoder, chunk))
                while (true) {
                    val end = pending.indexOf("\n")
                    if (end < 0) break
                    emit(pending.substring(0, end).removeSuffix("\r"))
                    pending.deleteRange(0, end + 1)
                }
            }
        } finally {
            cancel(reader)
        }
    }
}

private fun standalone(): Boolean = js("(document.querySelector('meta[name=networkhelm-mode]') || {}).content === 'standalone'")

private fun reader(response: Response): JsAny = js("response.body.getReader()")

private fun read(reader: JsAny): Promise<JsAny> = js("reader.read()")

private fun done(chunk: JsAny): Boolean = js("chunk.done")

private fun decoder(): JsAny = js("new TextDecoder()")

private fun decode(decoder: JsAny, chunk: JsAny): String = js("decoder.decode(chunk.value, { stream: true })")

private fun cancel(reader: JsAny): Unit = js("{ reader.cancel(); }")

// RequestInit() hands omitted members to fetch as null, which rejects a null cache or mode
private fun requestInit(method: String?, headers: Headers?, body: String?): RequestInit =
    js("({ method: method == null ? undefined : method, headers: headers == null ? undefined : headers, body: body == null ? undefined : body })")

private suspend fun logIn(user: String, password: String, code: String): GatewayAnswer = try {
    val body = json.encodeToString(LoginForm.serializer(), LoginForm(user, password, code))
    val response = window.fetch("/login", requestInit("POST", null, body)).await<Response>()
    when (response.status.toInt()) {
        204 -> GatewayAnswer.In
        200 -> GatewayAnswer.SetUp(json.decodeFromString(SetUpCode.serializer(), response.text().await<JsString>().toString()).otpauth)
        else -> GatewayAnswer.Refused
    }
} catch (e: JsException) {
    GatewayAnswer.Unreachable
}

private suspend fun open(scope: CoroutineScope): GatewayClient? {
    val wire = BrowserWire(WebSocket("wss://${window.location.host}/session"))
    return try {
        wire.opened.await()
        GatewayClient(wire, scope).also { it.hello() }
    } catch (e: DeviceException) {
        null
    }
}

private class BrowserWire(private val socket: WebSocket) : Wire {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    val opened = CompletableDeferred<Unit>()

    init {
        socket.onopen = { opened.complete(Unit) }
        socket.onmessage = { event -> (event.data as? JsString)?.let { incoming.trySend(it.toString()) } }
        socket.onclose = {
            incoming.close()
            opened.completeExceptionally(DeviceException(ErrorCategory.ConnectionLost, "the gateway closed the session"))
        }
    }

    override suspend fun send(text: String) = socket.send(text)

    override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()

    override fun close() = socket.close()
}
