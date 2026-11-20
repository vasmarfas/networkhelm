package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.ValueType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// what the form of an inbound of sing-box or Xray shows of its settings besides the columns, the secrets left out
internal fun JsonKind.inboundSettings(inbound: JsonObject): Map<String, String> {
    val tls = tls(inbound)
    val stream = inbound["streamSettings"] as? JsonObject
    val secure = (if (sing) inbound["tls"] else stream?.get(if (tls.security == "reality") "realitySettings" else "tlsSettings")) as? JsonObject
    val reality = if (sing) secure?.get("reality") as? JsonObject else secure
    val held = if (sing) secure else (secure?.get("certificates") as? JsonArray)?.firstOrNull() as? JsonObject
    val handshake = reality?.get("handshake") as? JsonObject
    return buildMap<String, String> {
        put(if (tls.network == "grpc") "service-name" else "transport-path", tls.path)
        put("transport-host", tls.host)
        put("server-names", if (sing) secure.textOf("server_name") else if (tls.security == "reality") reality.joined("serverNames") else secure.textOf("serverName"))
        put("alpn", secure.joined("alpn"))
        put("certificate-path", held.textOf(if (sing) "certificate_path" else "certificateFile"))
        put("key-path", held.textOf(if (sing) "key_path" else "keyFile"))
        put("dest", if (sing) listOf(handshake.textOf("server"), handshake.textOf("server_port")).filter { it.isNotEmpty() }.joinToString(":") else reality.textOf("target").ifEmpty { reality.textOf("dest") })
        put("short-ids", reality.joined(if (sing) "short_id" else "shortIds"))
        put("method", method(inbound))
        if (!sing) put("sniffing", "${(inbound["sniffing"] as? JsonObject).textOf("enabled") == "true"}")
        if (sing && type(inbound) == "hysteria2") put("obfs", "${inbound["obfs"] != null}")
        put("up-mbps", inbound.textOf("up_mbps"))
        put("down-mbps", inbound.textOf("down_mbps"))
    }.filterValues { it.isNotEmpty() }
}

// the inbound with one setting of its form changed where its program keeps it; an empty value takes the setting away. The settings of the
// transport and of the security go where the network and the security the inbound has by then want them
internal fun JsonKind.edited(inbound: JsonObject, field: String, value: String): JsonObject {
    val tls = tls(inbound)
    val text = value.ifEmpty { null }?.let(::JsonPrimitive)
    val stream = listOf("streamSettings")
    val transport = if (sing) listOf("transport") else stream + "${tls.network}Settings"
    val secure = if (sing) listOf("tls") else stream + (if (tls.security == "reality") "realitySettings" else "tlsSettings")
    val reality = if (sing) listOf("tls", "reality") else stream + "realitySettings"
    return when (field) {
        "tag" -> inbound.placed(listOf("tag"), text)
        "listen" -> inbound.placed(listOf("listen"), text ?: JsonPrimitive("::").takeIf { sing })
        "port" -> inbound.placed(listOf(if (sing) "listen_port" else "port"), value.toIntOrNull()?.let(::JsonPrimitive))
        "network" -> when {
            !sing -> inbound.placed(stream + "network", text)
            value == "tcp" -> inbound.placed(transport, null)
            else -> inbound.placed(transport, JsonObject(mapOf("type" to JsonPrimitive(value))))
        }
        "transport-path" -> inbound.placed(transport + "path", text)
        "transport-host" -> inbound.placed(if (sing && tls.network == "ws") transport + "headers" + "Host" else transport + "host", text)
        "service-name" -> inbound.placed(transport + (if (sing) "service_name" else "serviceName"), text)
        "security" -> when {
            !sing -> inbound.placed(stream + "security", text)
            value == "none" -> if (inbound["tls"] == null) inbound else inbound.placed(secure + "enabled", JsonPrimitive(false))
            value == "tls" && (inbound["tls"] as? JsonObject)?.get("reality") == null -> inbound.placed(secure + "enabled", JsonPrimitive(true))
            else -> inbound.placed(secure + "enabled", JsonPrimitive(true)).placed(reality + "enabled", JsonPrimitive(value == "reality"))
        }
        "server-names" -> if (!sing && tls.security == "reality") {
            inbound.placed(secure + "serverNames", items(value))
        } else {
            inbound.placed(secure + (if (sing) "server_name" else "serverName"), text)
        }
        "alpn" -> inbound.placed(secure + "alpn", items(value))
        "certificate-path", "key-path" -> {
            val lines = if (field == "certificate-path") "certificate" else "key"
            val file = if (sing) "${lines}_path" else "${lines}File"
            if (sing) {
                inbound.placed(secure + file, text).let { if (text == null) it else it.placed(secure + lines, null) }
            } else {
                val held = (((inbound["streamSettings"] as? JsonObject)?.get("tlsSettings") as? JsonObject)?.get("certificates") as? JsonArray).orEmpty()
                val first = ((held.firstOrNull() as? JsonObject) ?: JsonObject(emptyMap())).placed(listOf(file), text).let { if (text == null) it else it.placed(listOf(lines), null) }
                inbound.placed(stream + "tlsSettings" + "certificates", JsonArray(listOf<JsonElement>(first) + held.drop(1)))
            }
        }
        "dest" -> if (sing) {
            inbound.placed(reality + "handshake" + "server", value.substringBeforeLast(':').ifEmpty { null }?.let(::JsonPrimitive))
                .placed(reality + "handshake" + "server_port", value.substringAfterLast(':', "").toIntOrNull()?.let(::JsonPrimitive))
        } else {
            val target = ((inbound["streamSettings"] as? JsonObject)?.get("realitySettings") as? JsonObject)?.get("target") != null
            inbound.placed(reality + (if (target) "target" else "dest"), text)
        }
        "short-ids" -> inbound.placed(reality + (if (sing) "short_id" else "shortIds"), items(value))
        "private-key" -> inbound.placed(reality + (if (sing) "private_key" else "privateKey"), text)
        "sniffing" -> {
            val on = value in ValueType.Flag.on
            val sniffing = inbound.placed(listOf("sniffing", "enabled"), JsonPrimitive(on))
            if (!on || (inbound["sniffing"] as? JsonObject)?.get("destOverride") != null) sniffing else sniffing.placed(listOf("sniffing", "destOverride"), items("http,tls,quic"))
        }
        "method", "password" -> inbound.placed(if (sing) listOf(field) else listOf("settings", field), text)
        "obfs" -> if (value in ValueType.Flag.on) inbound else inbound.placed(listOf("obfs"), null)
        "obfs-password" -> inbound.placed(listOf("obfs", "type"), JsonPrimitive("salamander")).placed(listOf("obfs", "password"), text)
        "up-mbps", "down-mbps" -> inbound.placed(listOf(field.replace('-', '_')), value.toIntOrNull()?.let(::JsonPrimitive))
        else -> inbound
    }
}

// a value of a field of the form refused before it goes to the config, where the program would meet it at its check or not at all
internal fun checkedSetting(field: String, value: String) {
    val fits = when (field) {
        "listen" -> LISTEN.matches(value)
        "transport-path" -> value.isEmpty() || value.startsWith("/") && value.none { it.isWhitespace() }
        "transport-host" -> value.isEmpty() || HOST.matches(value)
        "dest" -> value.isEmpty() || HOST.matches(value.substringBeforeLast(':')) && value.substringAfterLast(':', "").toIntOrNull() in 1..65535
        "service-name" -> value.isEmpty() || NAME.matches(value)
        "server-names" -> value.isEmpty() || value.split(',').all { HOST.matches(it.trim()) }
        "alpn" -> value.isEmpty() || value.split(',').all { NAME.matches(it.trim()) }
        "certificate-path", "key-path" -> value.isEmpty() || FILE.matches(value)
        "short-ids" -> value.split(',').all { SHORT.matches(it.trim()) }
        "up-mbps", "down-mbps" -> value.isEmpty() || value.toIntOrNull() in 0..100_000
        else -> value.none { it == '\n' || it == '\r' }
    }
    if (!fits) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes")
}

private fun JsonObject?.textOf(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject?.joined(key: String) = when (val value = this?.get(key)) {
    is JsonArray -> value.joinToString(",") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    else -> ""
}

private fun items(value: String): JsonElement? = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null }?.let { names -> JsonArray(names.map(::JsonPrimitive)) }

// the object with the value at the path, the objects on the way made where they are missing; null takes the key away
internal fun JsonObject.placed(path: List<String>, value: JsonElement?): JsonObject {
    val key = path.first()
    if (path.size == 1) return JsonObject(if (value == null) this - key else this + (key to value))
    return JsonObject(this + (key to ((this[key] as? JsonObject) ?: JsonObject(emptyMap())).placed(path.drop(1), value)))
}

private val LISTEN = Regex("[0-9a-fA-F.:]*|/[A-Za-z0-9._/-]+")
private val HOST = Regex("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?")
private val NAME = Regex("[A-Za-z0-9._/-]+")
private val FILE = Regex("/[A-Za-z0-9._/@+=,~-]+")
private val SHORT = Regex("([0-9a-f]{2}){0,8}")
