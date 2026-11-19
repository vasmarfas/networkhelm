package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

private const val API = "https://api.dropboxapi.com/2"
private const val CONTENT = "https://content.dropboxapi.com/2"
private const val LONG_POLL = "https://notify.dropboxapi.com/2/files/list_folder/longpoll"
private const val DROPBOX_HOST = "api.dropboxapi.com"
private const val ROOT = "/$SYNC_FOLDER"

// the app registered in the App Console of Dropbox with the access type App folder; offline access brings a refresh token
internal val DROPBOX = OAuthClient(
    id = "o62vbooj1z07hkq",
    authorize = "https://www.dropbox.com/oauth2/authorize",
    token = "https://api.dropboxapi.com/oauth2/token",
    scope = "",
    redirect = "http://localhost:$SIGN_IN_PORT/",
    extra = mapOf("token_access_type" to "offline"),
)

@Serializable
private class DropboxEntry(@SerialName(".tag") val kind: String = "", @SerialName("path_lower") val path: String = "", val rev: String = "")

@Serializable
private class DropboxListing(val entries: List<DropboxEntry> = emptyList(), val cursor: String = "", @SerialName("has_more") val more: Boolean = false)

@Serializable
private class DropboxPoll(val changes: Boolean = false, val backoff: Long = 0)

@Serializable
private class DropboxError(@SerialName("error_summary") val summary: String = "")

// Dropbox in the folder of the app, Apps/<name of the app>, which is all the token opens. A write over the revision read is refused when
// the file has another one by then, and a long poll tells of a change of the files as it comes
class DropboxService(private val http: SyncHttp, private val login: OAuthLogin) : SyncService {
    private var cursor: String? = null

    override suspend fun list(): List<Listed> {
        val listed = mutableListOf<Listed>()
        var reply = call("$API/files/list_folder", buildJsonObject {
            put("path", ROOT)
            put("recursive", true)
        })
        while (true) {
            if (reply.status == 409 && summary(reply).startsWith("path/not_found")) return emptyList()
            val page = parsed(reply, DropboxListing.serializer())
            page.entries.filter { it.kind == "file" }.mapTo(listed) { Listed(it.path.removePrefix("$ROOT/"), it.rev) }
            if (!page.more) return listed.filter { syncFile(it.path) }
            reply = call("$API/files/list_folder/continue", buildJsonObject { put("cursor", page.cursor) })
        }
    }

    override suspend fun read(path: String): Stored? {
        val reply = login.send("POST", "$CONTENT/files/download", mapOf("Dropbox-API-Arg" to argument(path).toString()))
        if (reply.status == 409 && summary(reply).startsWith("path/not_found")) return null
        if (reply.status != 200) throw failed(reply)
        val metadata = reply.header("Dropbox-API-Result") ?: throw notApi()
        return Stored(reply.body, decoded(metadata, DropboxEntry.serializer()).rev)
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        val reply = upload(path, bytes, JsonPrimitive("overwrite"))
        if (reply.status != 200) throw failed(reply)
    }

    override suspend fun writeIf(path: String, bytes: ByteArray, over: Stored?): Boolean {
        val mode = if (over == null) {
            JsonPrimitive("add")
        } else {
            buildJsonObject {
                put(".tag", "update")
                put("update", over.tag)
            }
        }
        val reply = upload(path, bytes, mode)
        if (reply.status == 409 && summary(reply).startsWith("path/conflict")) return false
        if (reply.status != 200) throw failed(reply)
        return true
    }

    override suspend fun delete(path: String) {
        val reply = call("$API/files/delete_v2", argument(path))
        if (reply.status == 409 && summary(reply).startsWith("path_lookup/not_found")) return
        if (reply.status != 200) throw failed(reply)
    }

    // returns at a change of the files after the last call, and at once on the first, which only takes the cursor the next ones wait on.
    // The long poll takes no token; Dropbox holds it for 30 s and a random while more, and at times asks to wait before the next one
    suspend fun changes() {
        val known = cursor
        if (known != null) {
            do {
                val asked = buildJsonObject {
                    put("cursor", known)
                    put("timeout", 30)
                }
                val reply = http.send("POST", LONG_POLL, body = json(asked))
                if (reply.status != 200) throw failed(reply)
                val poll = decoded(reply.body.decodeToString(), DropboxPoll.serializer())
                if (poll.backoff > 0) delay(poll.backoff.seconds)
            } while (!poll.changes)
        }
        val latest = call("$API/files/list_folder/get_latest_cursor", buildJsonObject {
            put("path", ROOT)
            put("recursive", true)
        })
        cursor = parsed(latest, DropboxListing.serializer()).cursor
    }

    // the token and its refresh token stop working on Dropbox as well
    suspend fun revoke() {
        val reply = login.send("POST", "$API/auth/token/revoke")
        if (reply.status != 200) throw failed(reply)
    }

    // strict, so that a revision whose file is gone and a file of the same bytes are refused as well
    private suspend fun upload(path: String, bytes: ByteArray, mode: JsonElement): Reply {
        val argument = buildJsonObject {
            put("path", "$ROOT/$path")
            put("mode", mode)
            put("autorename", false)
            put("mute", true)
            put("strict_conflict", true)
        }
        return login.send("POST", "$CONTENT/files/upload", mapOf("Dropbox-API-Arg" to argument.toString()), outgoing(bytes, "application/octet-stream"))
    }

    private suspend fun call(url: String, argument: JsonObject) = login.send("POST", url, body = json(argument))

    private fun json(argument: JsonObject) = outgoing(argument.toString().encodeToByteArray(), "application/json")

    private fun argument(path: String) = buildJsonObject { put("path", "$ROOT/$path") }

    private fun <T> parsed(reply: Reply, serializer: KSerializer<T>): T {
        if (reply.status != 200) throw failed(reply)
        return decoded(reply.body.decodeToString(), serializer)
    }

    private fun <T> decoded(text: String, serializer: KSerializer<T>): T = try {
        fileJson.decodeFromString(serializer, text)
    } catch (e: IllegalArgumentException) {
        throw notApi()
    }

    private fun notApi() = DeviceException(ErrorCategory.Api, "$DROPBOX_HOST did not answer as the API of Dropbox")

    private fun summary(reply: Reply) = try {
        fileJson.decodeFromString(DropboxError.serializer(), reply.body.decodeToString()).summary
    } catch (e: IllegalArgumentException) {
        ""
    }

    // Dropbox tells why in error_summary, path/conflict/file/.. or too_many_write_operations/.., and in plain text for a request it did
    // not take
    private fun failed(reply: Reply) = answered(DROPBOX_HOST, "${reply.status} ${summary(reply).ifEmpty { reply.body.decodeToString().take(200) }}".trim())
}
