package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import io.ktor.http.Url
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val APP_FOLDER = "https://graph.microsoft.com/v1.0/me/drive/special/approot"
private const val GRAPH_HOST = "graph.microsoft.com"
private const val CREATE_ONLY = "?%40microsoft.graph.conflictBehavior=fail"

// the app registered in Microsoft Entra as a public client with the right to its own folder on OneDrive; consumers is the sign-in of
// personal accounts, whose OneDrive has the folders of apps
internal val ONEDRIVE = OAuthClient(
    id = "",
    authorize = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",
    token = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
    scope = "Files.ReadWrite.AppFolder offline_access",
    redirect = "http://localhost:$SIGN_IN_PORT/",
)

@Serializable
private class OneDriveItem(
    val name: String = "",
    val eTag: String = "",
    val file: JsonObject? = null,
    @SerialName("@microsoft.graph.downloadUrl") val download: String = "",
)

@Serializable
private class OneDriveItems(val value: List<OneDriveItem> = emptyList(), @SerialName("@odata.nextLink") val next: String = "")

// OneDrive through Microsoft Graph in the folder of the app, Apps/<name of the app>, which is all the token opens. A write over the eTag
// read is refused with 412 when the file changed since; the bytes come from a download link of their own that takes no token
class OneDriveService(private val http: SyncHttp, private val login: OAuthLogin) : SyncService {
    override suspend fun list(): List<Listed> = listOf("", "devices/", "state/").flatMap { directory ->
        val listed = mutableListOf<Listed>()
        var url = "${item(directory)}:/children"
        while (url.isNotEmpty()) {
            val reply = login.send("GET", url)
            if (reply.status == 404) break
            val page = parsed(reply, OneDriveItems.serializer())
            page.value.filter { it.file != null }.mapTo(listed) { Listed(directory + it.name, it.eTag) }
            url = page.next
        }
        listed
    }.filter { syncFile(it.path) }

    override suspend fun read(path: String): Stored? {
        val reply = login.send("GET", item(path))
        if (reply.status == 404) return null
        val found = parsed(reply, OneDriveItem.serializer())
        if (found.download.isEmpty()) throw notApi()
        val content = http.send("GET", found.download)
        if (content.status == 404) return null
        if (content.status != 200) throw answered(Url(found.download).host, "${content.status}")
        return Stored(content.body, found.eTag)
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        val reply = upload(path, bytes, "", emptyMap())
        if (reply.status != 200 && reply.status != 201) throw failed(reply)
    }

    // 409 where a file is there already, 412 where the file has another eTag or is gone
    override suspend fun writeIf(path: String, bytes: ByteArray, over: Stored?): Boolean {
        val reply = if (over == null) upload(path, bytes, CREATE_ONLY, emptyMap()) else upload(path, bytes, "", mapOf("If-Match" to over.tag))
        return when (reply.status) {
            200, 201 -> true
            409, 412 -> false
            else -> throw failed(reply)
        }
    }

    override suspend fun delete(path: String) {
        val reply = login.send("DELETE", item(path))
        if (reply.status != 204 && reply.status != 404) throw failed(reply)
    }

    // the folders of a new vault are made by the first write that misses them
    private suspend fun upload(path: String, bytes: ByteArray, query: String, headers: Map<String, String>): Reply {
        val url = "${item(path)}:/content$query"
        val reply = login.send("PUT", url, headers, outgoing(bytes, "application/octet-stream"))
        if (reply.status != 404) return reply
        folder(APP_FOLDER, SYNC_FOLDER)
        if ('/' in path) folder("${item("")}:", path.substringBefore('/'))
        return login.send("PUT", url, headers, outgoing(bytes, "application/octet-stream"))
    }

    private suspend fun folder(parent: String, name: String) {
        val made = buildJsonObject {
            put("name", name)
            putJsonObject("folder") {}
            put("@microsoft.graph.conflictBehavior", "fail")
        }
        val reply = login.send("POST", "$parent/children", body = outgoing(made.toString().encodeToByteArray(), "application/json"))
        if (reply.status != 201 && reply.status != 409) throw failed(reply)
    }

    private fun item(path: String) = "$APP_FOLDER:/" + "$SYNC_FOLDER/$path".trimEnd('/')

    private fun <T> parsed(reply: Reply, serializer: KSerializer<T>): T {
        if (reply.status != 200) throw failed(reply)
        return try {
            fileJson.decodeFromString(serializer, reply.body.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw notApi()
        }
    }

    private fun notApi() = DeviceException(ErrorCategory.Api, "$GRAPH_HOST did not answer as the API of OneDrive")

    // Microsoft Graph names the error in its JSON: itemNotFound, quotaLimitReached and the like
    private fun failed(reply: Reply): DeviceException {
        val code = try {
            fileJson.parseToJsonElement(reply.body.decodeToString()).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()
        } catch (e: IllegalArgumentException) {
            ""
        }
        return answered(GRAPH_HOST, "${reply.status} $code".trim())
    }
}
