package com.vasmarfas.networkhelm.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

// the browser checks certificates itself and lets no page pin one: a server with a certificate of its own is reached through the gateway
internal actual fun httpClient(pinning: Pinning): HttpClient = HttpClient(Js) { limits() }
