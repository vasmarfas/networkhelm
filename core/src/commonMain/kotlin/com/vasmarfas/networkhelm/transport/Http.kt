package com.vasmarfas.networkhelm.transport

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val MAX_HTTP_BODY_BYTES = 64 * 1024 * 1024
private const val MAX_HTTP_LINE_BYTES = 64 * 1024
private const val MAX_HTTP_HEADERS = 256

// the header fields with their names in lower case
class HttpResponse(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())

suspend fun httpRequest(
    stream: ByteStream,
    host: String,
    method: String,
    target: String,
    headers: Map<String, String>,
    body: ByteArray,
    maxBodyBytes: Int = MAX_HTTP_BODY_BYTES,
): HttpResponse {
    try {
        stream.write(head(method, target, host, headers, body.size).encodeToByteArray() + body)
        val source = BufferedStream(stream)
        val (status, fields) = readHead(source)
        val content = readToEnd(body(source, fields), maxBodyBytes)
        val length = fields["content-length"]?.toIntOrNull()
        if (length != null && length != content.size) {
            throw DeviceException(ErrorCategory.ConnectionLost, "the HTTP answer has ${content.size} of $length bytes")
        }
        return HttpResponse(status, content, fields)
    } finally {
        stream.close()
    }
}

// no answer does not tell whether the server executed the request
suspend fun httpExchange(connection: BufferedStream, host: String, method: String, target: String, headers: Map<String, String>, body: ByteArray, maxBodyBytes: Int = MAX_HTTP_BODY_BYTES): HttpResponse? {
    val first = try {
        connection.write(head(method, target, host, headers, body.size, keepAlive = true).encodeToByteArray() + body)
        connection.readLine(MAX_HTTP_LINE_BYTES)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } ?: return null
    val (status, fields) = readHead(connection, first)
    val content = readToEnd(body(connection, fields), maxBodyBytes)
    val length = fields["content-length"]?.toIntOrNull()
    if (length != null && length != content.size) {
        throw DeviceException(ErrorCategory.ConnectionLost, "the HTTP answer has ${content.size} of $length bytes")
    }
    // the empty line that ends the last chunk is still ahead of the next answer
    if (fields["transfer-encoding"]?.contains("chunked") == true) {
        var line = connection.readLine(MAX_HTTP_LINE_BYTES)
        while (!line.isNullOrEmpty()) line = connection.readLine(MAX_HTTP_LINE_BYTES)
    }
    return HttpResponse(status, content, fields)
}

fun HttpResponse.keepsAlive(): Boolean =
    !headers["connection"].orEmpty().contains("close", ignoreCase = true) && (headers.containsKey("content-length") || headers["transfer-encoding"]?.contains("chunked") == true)

fun httpLines(open: suspend () -> ByteStream, host: String, target: String, headers: Map<String, String>): Flow<String> = flow {
    open().closingAfter { stream ->
        stream.write(head("GET", target, host, headers, null).encodeToByteArray())
        val source = BufferedStream(stream)
        val (status, fields) = readHead(source)
        val category = when (status) {
            200 -> null
            401, 403 -> ErrorCategory.PermissionDenied
            404 -> ErrorCategory.MissingItem
            else -> ErrorCategory.Api
        }
        if (category != null) throw DeviceException(category, "HTTP $status on $target")
        val lines = BufferedStream(body(source, fields))
        while (true) emit(lines.readLine(MAX_HTTP_BODY_BYTES) ?: break)
    }
}

fun urlEncoded(text: String) = text.encodeToByteArray().joinToString("") {
    val code = it.toInt() and 0xFF
    val char = code.toChar()
    if (code < 0x80 && (char.isLetterOrDigit() || char in "-._~")) "$char" else "%" + code.toString(16).uppercase().padStart(2, '0')
}

private fun head(method: String, target: String, host: String, headers: Map<String, String>, length: Int?, keepAlive: Boolean = false) = buildString {
    append("$method $target HTTP/1.1\r\nHost: $host\r\nConnection: ${if (keepAlive) "keep-alive" else "close"}\r\n")
    if (length != null) append("Content-Length: $length\r\n")
    headers.forEach { (name, value) -> append("$name: $value\r\n") }
    append("\r\n")
}

private suspend fun readHead(source: BufferedStream): Pair<Int, Map<String, String>> =
    readHead(source, source.readLine(MAX_HTTP_LINE_BYTES) ?: throw DeviceException(ErrorCategory.ConnectionLost, "the server closed the connection without an answer"))

private suspend fun readHead(source: BufferedStream, first: String): Pair<Int, Map<String, String>> {
    val status = first.split(' ').getOrNull(1)?.toIntOrNull() ?: throw DeviceException(ErrorCategory.Api, "not an HTTP status line: $first")
    val fields = mutableMapOf<String, String>()
    var lines = 0
    while (true) {
        val line = source.readLine(MAX_HTTP_LINE_BYTES) ?: throw DeviceException(ErrorCategory.ConnectionLost, "the HTTP answer ended inside its headers")
        if (line.isEmpty()) return status to fields
        if (++lines > MAX_HTTP_HEADERS) throw DeviceException(ErrorCategory.Api, "the HTTP answer has more than $MAX_HTTP_HEADERS header lines")
        fields[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
    }
}

private fun body(source: BufferedStream, fields: Map<String, String>): ByteStream = when {
    fields["transfer-encoding"]?.contains("chunked") == true -> ChunkedBody(source)
    else -> fields["content-length"]?.toLongOrNull()?.takeIf { it >= 0 }?.let { SizedBody(source, it) } ?: source
}

private class SizedBody(private val source: BufferedStream, private var left: Long) : ByteStream {
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (left == 0L) return -1
        val count = source.read(buffer, offset, minOf(length.toLong(), left).toInt())
        if (count > 0) left -= count
        return count
    }

    override suspend fun write(bytes: ByteArray) = source.write(bytes)

    override fun close() = source.close()
}

private class ChunkedBody(private val source: BufferedStream) : ByteStream {
    private var left = 0
    private var ended = false

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (ended) return -1
        if (left == 0) {
            val size = source.readLine(MAX_HTTP_LINE_BYTES)?.substringBefore(';')?.trim()?.toIntOrNull(16)?.takeIf { it >= 0 } ?: throw cut()
            if (size == 0) {
                ended = true
                return -1
            }
            left = size
        }
        val count = source.read(buffer, offset, minOf(length, left))
        if (count < 0) throw cut()
        left -= count
        if (left == 0 && source.readLine(MAX_HTTP_LINE_BYTES) == null) throw cut()
        return count
    }

    override suspend fun write(bytes: ByteArray) = source.write(bytes)

    override fun close() = source.close()

    private fun cut() = DeviceException(ErrorCategory.ConnectionLost, "the HTTP answer ended inside a chunk")
}

private suspend fun readToEnd(stream: ByteStream, limit: Int): ByteArray {
    require(limit > 0)
    var data = ByteArray(minOf(16 * 1024, limit))
    var size = 0
    while (true) {
        if (size == limit) {
            if (stream.read(ByteArray(1), 0, 1) < 0) return data.copyOf(size)
            throw DeviceException(ErrorCategory.Api, "the HTTP response exceeds $limit bytes")
        }
        if (size == data.size) data = data.copyOf(minOf(size.toLong() * 2, limit.toLong()).toInt())
        val count = stream.read(data, size, data.size - size)
        if (count < 0) return data.copyOf(size)
        size += count
    }
}
