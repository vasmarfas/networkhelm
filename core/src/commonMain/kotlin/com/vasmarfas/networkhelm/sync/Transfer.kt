package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64

// what a device shows another one as a QR code or a text once its password was typed again: the master key, the secret key where the
// vault has one, and how to reach a service with a login of its own; a plain class, so that no log prints the keys
@Serializable
class Transfer(val vault: String, val master: String, val secret: String? = null, val service: JsonObject? = null)

private const val TRANSFER = "nhs1:"

fun transferCode(vault: String, master: ByteArray, secret: ByteArray?, service: JsonObject?): String =
    TRANSFER + Base64.UrlSafe.encode(fileJson.encodeToString(Transfer.serializer(), Transfer(vault, Base64.encode(master), secret?.let { Base64.encode(it) }, service)).encodeToByteArray())

// the text is not repeated in the message: it holds the master key
fun transfer(code: String): Transfer = try {
    val text = code.trim()
    require(text.startsWith(TRANSFER))
    fileJson.decodeFromString(Transfer.serializer(), Base64.UrlSafe.decode(text.removePrefix(TRANSFER)).decodeToString()).also {
        require(Base64.decode(it.master).size == Sodium.KEY && it.secret?.let { secret -> Base64.decode(secret).size == SECRET } != false)
    }
} catch (e: IllegalArgumentException) {
    throw DeviceException(ErrorCategory.ArgumentValue, "the code is not one of NetworkHelm synchronisation")
}
