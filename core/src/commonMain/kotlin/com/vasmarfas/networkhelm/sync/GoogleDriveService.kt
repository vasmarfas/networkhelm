package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.keenetic.hex
import com.vasmarfas.networkhelm.keenetic.sha256
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val FILES = "https://www.googleapis.com/drive/v3/files"
private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
private const val DRIVE_HOST = "www.googleapis.com"

// the secret of the client for a desktop, put in by the builds of the desktop and Android (core/build.gradle.kts); iOS and the page have none
internal expect val googleDesktopSecret: String

// the app registered in Google Cloud with a client for a desktop and the right to the hidden folder of the app alone. Offline access with
// consent brings a refresh token to each device that signs in
internal val GOOGLE_DRIVE = OAuthClient(
    id = "235939716345-g11k0vea9beh57nej20o9r38oda05rl5.apps.googleusercontent.com",
    authorize = "https://accounts.google.com/o/oauth2/v2/auth",
    token = "https://oauth2.googleapis.com/token",
    scope = "https://www.googleapis.com/auth/drive.appdata",
    redirect = "http://127.0.0.1:$SIGN_IN_PORT/",
    secret = googleDesktopSecret,
    extra = mapOf("access_type" to "offline", "prompt" to "consent"),
    revocation = "https://oauth2.googleapis.com/revoke",
)

private const val GOOGLE_IOS = "235939716345-mkq5qr1o3n5a8664sf87jnbmud0csmba.apps.googleusercontent.com"

// the client for iOS, registered by the bundle of the app: it comes back by the scheme of its own id read backwards and has no secret
internal val GOOGLE_DRIVE_IOS = GOOGLE_DRIVE.copy(id = GOOGLE_IOS, redirect = "com.googleusercontent.apps.${GOOGLE_IOS.substringBefore('.')}:/oauth2redirect", secret = "")

@Serializable
private class DriveFile(val id: String = "", val name: String = "", val version: String = "", val createdTime: String = "")

@Serializable
private class DriveFiles(val files: List<DriveFile> = emptyList(), val nextPageToken: String = "")

// Google Drive in the hidden folder of the app, appDataFolder: it is not shown among the files of the drive, and the token opens nothing
// else. The folder knows no paths, so a file is named by its path from networkhelm-sync, and since the drive lets files share a name, of
// such twins the one made first is the file. Drive v3 writes on no condition: the vault over the one read is written after a read right
// before, and a new vault made at the same time as another one and after it goes away again
class GoogleDriveService(private val login: OAuthLogin) : SyncService {
    override suspend fun list(): List<Listed> = files(null)
        .filter { it.name.startsWith("$SYNC_FOLDER/") }
        .distinctBy { it.name }
        .map { Listed(it.name.removePrefix("$SYNC_FOLDER/"), it.version) }
        .filter { syncFile(it.path) }

    override suspend fun read(path: String): Stored? {
        val file = files(path).firstOrNull() ?: return null
        val bytes = content(file.id) ?: return null
        return Stored(bytes, hex(sha256(bytes)))
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        val file = files(path).firstOrNull()
        if (file == null || !update(file.id, bytes)) create(path, bytes)
    }

    override suspend fun writeIf(path: String, bytes: ByteArray, over: Stored?): Boolean {
        val file = files(path).firstOrNull()
        if (over == null) {
            if (file != null) return false
            val made = create(path, bytes)
            val first = files(path).firstOrNull()
            if (first == null || first.id == made) return true
            remove(made)
            return false
        }
        if (file == null) return false
        val current = content(file.id) ?: return false
        return hex(sha256(current)) == over.tag && update(file.id, bytes)
    }

    override suspend fun delete(path: String) {
        for (file in files(path)) remove(file.id)
    }

    // the files of the folder, or the twins of one path, the first made first
    private suspend fun files(path: String?): List<DriveFile> {
        val found = mutableListOf<DriveFile>()
        var page = ""
        do {
            val query = listOfNotNull(
                "spaces=appDataFolder",
                "pageSize=1000",
                "fields=" + "nextPageToken,files(id,name,version,createdTime)".encodeURLParameter(),
                path?.let { "q=" + "name = '$SYNC_FOLDER/$it'".encodeURLParameter() },
                page.takeIf { it.isNotEmpty() }?.let { "pageToken=" + it.encodeURLParameter() },
            )
            val listed = parsed(login.send("GET", "$FILES?${query.joinToString("&")}"), DriveFiles.serializer())
            found += listed.files
            page = listed.nextPageToken
        } while (page.isNotEmpty())
        return found.sortedWith(compareBy({ it.createdTime }, { it.id }))
    }

    private suspend fun content(id: String): ByteArray? {
        val reply = login.send("GET", "$FILES/$id?alt=media")
        if (reply.status == 404) return null
        if (reply.status != 200) throw failed(reply)
        return reply.body
    }

    // false where the file was deleted meanwhile
    private suspend fun update(id: String, bytes: ByteArray): Boolean {
        val reply = login.send("PATCH", "$UPLOAD/$id?uploadType=media", body = outgoing(bytes, "application/octet-stream"))
        if (reply.status == 404) return false
        if (reply.status != 200) throw failed(reply)
        return true
    }

    // the name and the bytes in one request, so that the file is never there without its bytes; the id of the new file
    private suspend fun create(path: String, bytes: ByteArray): String {
        val boundary = hex(Sodium.random(16))
        val metadata = buildJsonObject {
            put("name", "$SYNC_FOLDER/$path")
            putJsonArray("parents") { add("appDataFolder") }
        }
        val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n"
        val body = head.encodeToByteArray() + bytes + "\r\n--$boundary--\r\n".encodeToByteArray()
        val reply = login.send("POST", "$UPLOAD?uploadType=multipart&fields=id", body = outgoing(body, "multipart/related; boundary=$boundary"))
        return parsed(reply, DriveFile.serializer()).id
    }

    private suspend fun remove(id: String) {
        val reply = login.send("DELETE", "$FILES/$id")
        if (reply.status != 204 && reply.status != 404) throw failed(reply)
    }

    private fun <T> parsed(reply: Reply, serializer: KSerializer<T>): T {
        if (reply.status != 200) throw failed(reply)
        return try {
            fileJson.decodeFromString(serializer, reply.body.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw DeviceException(ErrorCategory.Api, "$DRIVE_HOST did not answer as the API of Google Drive")
        }
    }

    // Google says why in the message of its error
    private fun failed(reply: Reply): DeviceException {
        val message = try {
            fileJson.parseToJsonElement(reply.body.decodeToString()).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
        } catch (e: IllegalArgumentException) {
            ""
        }
        return answered(DRIVE_HOST, "${reply.status} $message".trim())
    }
}
