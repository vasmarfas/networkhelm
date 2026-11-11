package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 25.12 renamed the ports on some boards, so their names come only from board.json
class OpenWrtFacts(
    val board: JsonObject,
    val features: JsonObject,
    val boardJson: JsonObject,
    val objects: Set<String>,
    val packages: Set<String>,
    val apps: Set<String>,
    val writable: Boolean,
    val tools: Set<String> = emptySet(),
) {
    val device: DeviceInfo
        get() {
            val release = board["release"]?.jsonObject
            return DeviceInfo(
                platform = Platform.OpenWrt,
                identity = board.text("hostname"),
                version = release?.text("version").orEmpty(),
                architecture = release?.text("target").orEmpty(),
                board = board.text("model"),
                packages = packages,
                policies = if (writable) setOf("read", "write") else setOf("read"),
            )
        }
}

suspend fun readFacts(ubus: Ubus, ssh: SshSession?): OpenWrtFacts {
    val objects = ubus.objects()
    val empty = JsonObject(emptyMap())
    val boardJson = readFile(ubus, objects, ssh, BOARD)
    val packages = installedPackages(ubus, objects) { readFile(ubus, objects, ssh, it) }.keys
    val apps = listFiles(ubus, objects, ssh, MENUS).filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.toSet()
    val writable = ssh != null || ubus.call("session", "access", JsonObject(mapOf("scope" to JsonPrimitive("uci"), "object" to JsonPrimitive("network"), "function" to JsonPrimitive("write"))))["access"]
        ?.jsonPrimitive?.booleanOrNull == true
    val tools = ssh?.exec("for t in $TOOLS; do command -v \$t >/dev/null 2>&1 && echo \$t; done")?.output.orEmpty().lines().filter { it.isNotBlank() }.toSet()
    return OpenWrtFacts(
        board = ubus.call("system", "board"),
        features = if ("luci" in objects) ubus.call("luci", "getFeatures") else empty,
        boardJson = boardJson?.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject } ?: empty,
        objects = objects,
        packages = packages,
        apps = apps,
        writable = writable,
        tools = tools,
    )
}

internal suspend fun readFile(ubus: Ubus, objects: Set<String>, ssh: SshSession?, path: String, maxBytes: Int? = null): String? {
    if ("file" in objects) {
        try {
            return ubus.call("file", "read", JsonObject(mapOf("path" to JsonPrimitive(path))))["data"]?.jsonPrimitive?.contentOrNull
        } catch (e: UbusException) {
            // rpcd turns down a file it finds too big with NOT_SUPPORTED, as the view of mixomo, 373 KB
            if (e.status == UbusStatus.NOT_FOUND) return null
            if (e.status != UbusStatus.NOT_SUPPORTED) throw e
        }
    }
    if (maxBytes != null && ssh != null) return readBoundedFile(ssh, path, maxBytes)
    return ssh?.exec("cat ${quote(path)} 2>/dev/null")?.takeIf { it.exitStatus == 0 }?.output
}

private suspend fun readBoundedFile(ssh: SshSession, path: String, limit: Int): String? {
    require(limit in 1..Int.MAX_VALUE - 6)
    val stream = ssh.process("head -c ${limit + 1} < ${quote(path)} 2>/dev/null; printf '\\n%d' \$?")
    try {
        val chunks = mutableListOf<ByteArray>()
        val buffer = ByteArray(minOf(8 * 1024, limit + 5))
        var size = 0
        while (true) {
            val count = stream.read(buffer, 0, minOf(buffer.size, limit + 6 - size))
            if (count < 0) break
            size += count
            if (size > limit + 5) throw DeviceException(ErrorCategory.Api, "a form source exceeds $limit bytes")
            chunks += buffer.copyOf(count)
        }
        val bytes = ByteArray(size)
        var at = 0
        for (chunk in chunks) {
            chunk.copyInto(bytes, at)
            at += chunk.size
        }
        val end = bytes.indexOfLast { it == '\n'.code.toByte() }
        val status = bytes.decodeToString(end + 1).toIntOrNull()
        if (end < 0 || status == null) throw DeviceException(ErrorCategory.ConnectionLost, "the file channel ended without a status")
        if (status != 0) return null
        if (end > limit) throw DeviceException(ErrorCategory.Api, "a form source exceeds $limit bytes")
        return bytes.decodeToString(0, end)
    } finally {
        stream.close()
    }
}

internal suspend fun installedPackages(ubus: Ubus, objects: Set<String>, read: suspend (String) -> String?): Map<String, String> {
    if ("rpc-sys" in objects) {
        return ubus.call("rpc-sys", "packagelist", JsonObject(mapOf("all" to JsonPrimitive(true))))["packages"]?.jsonObject.orEmpty()
            .mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
    }
    return packageDatabase(read(APK_DATABASE)?.ifEmpty { null } ?: read(OPKG_STATUS).orEmpty())
}

// opkg keeps removed packages too, with a Status that does not end in installed
internal fun packageDatabase(text: String): Map<String, String> = text.split("\n\n").mapNotNull { paragraph ->
    val fields = paragraph.lines().associate { it.substringBefore(':') to it.substringAfter(':').trim() }
    val name = fields["P"] ?: fields["Package"] ?: return@mapNotNull null
    if (fields["Status"]?.endsWith(" installed") == false) return@mapNotNull null
    name to (fields["V"] ?: fields["Version"]).orEmpty()
}.toMap()

internal suspend fun listFiles(ubus: Ubus, objects: Set<String>, ssh: SshSession?, path: String): List<String> = when {
    "file" in objects -> try {
        ubus.call("file", "list", JsonObject(mapOf("path" to JsonPrimitive(path))))["entries"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
    } catch (e: UbusException) {
        // a router without LuCI has no directory of its menus
        if (e.status != UbusStatus.NOT_FOUND) throw e
        emptyList()
    }
    ssh != null -> ssh.exec("ls ${quote(path)} 2>/dev/null").output.lines().filter { it.isNotBlank() }
    else -> emptyList()
}

private const val BOARD = "/etc/board.json"
private const val APK_DATABASE = "/lib/apk/db/installed"
private const val OPKG_STATUS = "/usr/lib/opkg/status"
private const val TOOLS = "gsmctl"

internal const val MENUS = "/usr/share/luci/menu.d"

private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
