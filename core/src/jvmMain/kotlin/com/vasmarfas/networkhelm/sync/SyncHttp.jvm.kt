package com.vasmarfas.networkhelm.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java

// the client of Java checks the name of the server only through the trust manager, so a pinned certificate passes whatever it names
internal actual fun httpClient(pinning: Pinning): HttpClient = HttpClient(Java) {
    limits()
    engine { config { sslContext(PinnedTrust(pinning).context) } }
}
