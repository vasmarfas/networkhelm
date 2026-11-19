package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.transport.sha256
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import javax.net.ssl.HttpsURLConnection

// OkHttp checks the name of the server after the handshake: the pinned certificate passes it too
internal actual fun httpClient(pinning: Pinning): HttpClient = HttpClient(OkHttp) {
    limits()
    engine {
        config {
            val trust = PinnedTrust(pinning)
            val names = HttpsURLConnection.getDefaultHostnameVerifier()
            sslSocketFactory(trust.context.socketFactory, trust)
            hostnameVerifier { host, session -> pinning.pin != null && session.peerCertificates.firstOrNull()?.let(::sha256) == pinning.pin || names.verify(host, session) }
        }
    }
}
