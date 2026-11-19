package com.vasmarfas.networkhelm.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private fun end(bytes: ByteArray) = 7 + ((bytes[5].toInt() and 0xFF) shl 8 or (bytes[6].toInt() and 0xFF))

internal fun headerOf(bytes: ByteArray) = fileJson.parseToJsonElement(bytes.decodeToString(7, end(bytes))).jsonObject

// the file with another header and the same encrypted text, as a service that changes a field would give it
internal fun withHeader(bytes: ByteArray, header: JsonObject): ByteArray {
    val text = header.toString().encodeToByteArray()
    return bytes.copyOf(5) + byteArrayOf((text.size shr 8).toByte(), text.size.toByte()) + text + bytes.copyOfRange(end(bytes), bytes.size)
}
