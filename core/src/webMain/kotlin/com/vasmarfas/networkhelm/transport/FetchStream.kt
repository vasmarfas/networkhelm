@file:OptIn(ExperimentalWasmJsInterop::class)

package com.vasmarfas.networkhelm.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.js.Promise

var browserHttp = false

private val headerFields = MapSerializer(String.serializer(), String.serializer())

internal class FetchStream(private val host: String, private val port: Int, private val tls: Boolean, private val timeoutMillis: Int) : TlsStream {
    override val certificateSha256: String = BROWSER_CHECKED

    private val scope = CoroutineScope(SupervisorJob())
    private val answers = Channel<ByteArray>(Channel.UNLIMITED)
    private var written = ByteArray(0)
    private var current = ByteArray(0)
    private var at = 0

    override suspend fun write(bytes: ByteArray) {
        written += bytes
        while (true) {
            val request = readRequest(written) ?: return
            written = written.copyOfRange(request.size, written.size)
            answer(request)
        }
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (at == current.size) {
            current = answers.receiveCatching().getOrNull() ?: return -1
            at = 0
        }
        val count = minOf(length, current.size - at)
        current.copyInto(buffer, offset, at, at + count)
        at += count
        return count
    }

    override fun close() {
        scope.cancel()
        answers.close()
    }

    private suspend fun answer(request: HttpRequest) {
        val address = if (':' in host) "[$host]" else host
        val url = "${if (tls) "https" else "http"}://$address:$port${request.target}"
        val asked = Json.encodeToString(headerFields, request.headers.filterKeys { it.lowercase() !in withheld })
        val body = request.body.takeIf { request.method != "GET" && request.method != "HEAD" }
        val events = request.headers.any { (name, value) -> name.equals("accept", ignoreCase = true) && "text/event-stream" in value }
        try {
            val response = send(url, request.method, asked, body, timeoutMillis).await<JsAny>()
            val head = responseHead(status(response), headerLines(response))
            if (events) {
                answers.trySend((head + "Transfer-Encoding: chunked\r\n\r\n").encodeToByteArray())
                scope.launch { follow(response, url) }
            } else {
                val text = string(text(response).await<JsAny>()).encodeToByteArray()
                answers.trySend((head + "Content-Length: ${text.size}\r\n\r\n").encodeToByteArray() + text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw BrowserBlockedException(url, e)
        }
    }

    private suspend fun follow(response: JsAny, url: String) {
        val reader = reader(response)
        val decoder = decoder()
        try {
            while (true) {
                val chunk = nextChunk(reader).await<JsAny>()
                if (done(chunk)) break
                val piece = decode(decoder, chunk).encodeToByteArray()
                if (piece.isNotEmpty()) answers.trySend("${piece.size.toString(16)}\r\n".encodeToByteArray() + piece + "\r\n".encodeToByteArray())
            }
            answers.trySend("0\r\n\r\n".encodeToByteArray())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            answers.close(BrowserBlockedException(url, e))
        } finally {
            stopReading(reader)
        }
    }
}

private fun send(url: String, method: String, headers: String, body: String?, timeout: Int): Promise<JsAny> =
    js("(() => { const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), timeout); const init = { method: method, headers: JSON.parse(headers), signal: controller.signal, credentials: 'omit', cache: 'no-store' }; if (body != null) init.body = body; return fetch(url, init).finally(() => clearTimeout(timer)); })()")

private fun status(response: JsAny): Int = js("response.status")

private fun headerLines(response: JsAny): String =
    js("(() => { const lines = []; response.headers.forEach((value, name) => lines.push(name + ': ' + value)); return lines.join('\\r\\n'); })()")

private fun text(response: JsAny): Promise<JsAny> = js("response.text()")

private fun string(value: JsAny): String = js("value")

private fun reader(response: JsAny): JsAny = js("response.body.getReader()")

private fun nextChunk(reader: JsAny): Promise<JsAny> = js("reader.read()")

private fun done(chunk: JsAny): Boolean = js("chunk.done")

private fun decoder(): JsAny = js("new TextDecoder()")

private fun decode(decoder: JsAny, chunk: JsAny): String = js("decoder.decode(chunk.value, { stream: true })")

private fun stopReading(reader: JsAny): Unit = js("{ reader.cancel(); }")
