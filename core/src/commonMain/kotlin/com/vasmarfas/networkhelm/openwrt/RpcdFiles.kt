package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.RemoteFile
import com.vasmarfas.networkhelm.ssh.SftpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64

const val RPCD_READ_LIMIT = 256 * 1024L

private const val PART = 24 * 1024

internal class RpcdFiles(private val ubus: Ubus) : SftpClient {
    override suspend fun list(path: String): List<RemoteFile> = call("list", "path" to JsonPrimitive(path)).list("entries").map {
        val name = it.text("name")
        val directory = when (it.text("type")) {
            "directory" -> true
            // a link to a folder opens as the folder: /var and /run of OpenWrt lead to /tmp
            "symlink" -> folder("${path.trimEnd('/')}/$name")
            else -> false
        }
        RemoteFile(name, it.number("size") ?: 0, directory, it.number("mtime") ?: 0)
    }

    // the ACL of LuCI lets a login list any folder and stat none, so the link is listed: a link to a file, to nothing or to where
    // the login may not look leads to no folder
    private suspend fun folder(path: String): Boolean = try {
        ubus.call("file", "list", JsonObject(mapOf("path" to JsonPrimitive(path))))
        true
    } catch (e: UbusException) {
        false
    }

    override suspend fun read(path: String): ByteArray {
        val data = call("read", "path" to JsonPrimitive(path), "base64" to JsonPrimitive(true)).text("data")
        return Base64.decode(data)
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        var at = 0
        do {
            val end = minOf(bytes.size, at + PART)
            call(
                "write",
                "path" to JsonPrimitive(path),
                "data" to JsonPrimitive(Base64.encode(bytes.copyOfRange(at, end))),
                "base64" to JsonPrimitive(true),
                "append" to JsonPrimitive(at > 0),
            )
            at = end
        } while (at < bytes.size)
    }

    override suspend fun remove(path: String) {
        if (call("stat", "path" to JsonPrimitive(path)).text("type") == "directory") run("/bin/rmdir", path) else call("remove", "path" to JsonPrimitive(path))
    }

    override suspend fun rename(from: String, to: String) {
        if (exists(to)) throw DeviceException(ErrorCategory.General, "${to.substringAfterLast('/')} exists")
        run("/bin/mv", from, to)
    }

    override suspend fun mkdir(path: String) = run("/bin/mkdir", path)

    override fun close() = Unit

    private suspend fun exists(path: String): Boolean = try {
        call("stat", "path" to JsonPrimitive(path))
        true
    } catch (e: DeviceException) {
        if (e.category != ErrorCategory.MissingItem) throw e
        false
    }

    private suspend fun run(command: String, vararg params: String) {
        val answer = call("exec", "command" to JsonPrimitive(command), "params" to JsonArray(params.map { JsonPrimitive(it) }))
        if ((answer.number("code") ?: 0L) != 0L) throw DeviceException(ErrorCategory.General, answer.text("stderr").ifBlank { answer.text("stdout") }.trim())
    }

    private suspend fun call(method: String, vararg arguments: Pair<String, JsonElement>): JsonObject = try {
        ubus.call("file", method, JsonObject(arguments.toMap()))
    } catch (e: UbusException) {
        throw DeviceException(if (e.status == UbusStatus.NOT_FOUND) ErrorCategory.MissingItem else ErrorCategory.General, "rpcd file $method: ${e.message}")
    }

    private fun JsonObject.list(key: String) = this[key]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
}
