package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.keenetic.hex
import com.vasmarfas.networkhelm.keenetic.sha256
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.http.takeFrom
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val RESOURCES = "https://cloud-api.yandex.net/v1/disk/resources"
private const val DISK = "cloud-api.yandex.net"
private const val PAGE = 100
private const val REDIRECTS = 5

// the app registered on oauth.yandex.ru, with the right to its own folder on the disk and to nothing else
internal val YANDEX_DISK = OAuthClient(
    id = "a812d1b6417c4a1f842fc2bcd72b95f4",
    authorize = "https://oauth.yandex.ru/authorize",
    token = "https://oauth.yandex.ru/token",
    scope = "cloud_api:disk.app_folder",
    redirect = "http://127.0.0.1:$SIGN_IN_PORT/",
)

@Serializable
private class DiskItem(val name: String = "", val type: String = "", val md5: String = "", val modified: String = "")

@Serializable
private class DiskItems(val items: List<DiskItem> = emptyList(), val total: Int = 0)

@Serializable
private class DiskFolder(@SerialName("_embedded") val embedded: DiskItems = DiskItems())

@Serializable
private class DiskLink(val href: String = "")

@Serializable
private class DiskError(val error: String = "")

// Yandex Disk through its REST API, in the folder of the app, Applications/<name of the app>, which is all the token opens. A file goes up
// and comes down through a link the API gives for it, on a host of its own that takes no token. The disk writes on one condition alone,
// that no file is there, so the vault over the one read is written after a read right before
class YandexDiskService(private val http: SyncHttp, private val login: OAuthLogin) : SyncService {
    override suspend fun list(): List<Listed> = listOf("", "devices/", "state/").flatMap { directory ->
        val listed = mutableListOf<Listed>()
        var offset = 0
        while (true) {
            val reply = login.send("GET", "$RESOURCES?path=${place(directory)}&limit=$PAGE&offset=$offset")
            if (reply.status == 404) break
            val page = parsed(reply, DiskFolder.serializer()).embedded
            page.items.filter { it.type == "file" }.mapTo(listed) { Listed(directory + it.name, "${it.md5} ${it.modified}") }
            offset += page.items.size
            if (page.items.isEmpty() || offset >= page.total) break
        }
        listed
    }.filter { syncFile(it.path) }

    override suspend fun read(path: String): Stored? {
        val reply = login.send("GET", "$RESOURCES/download?path=${place(path)}")
        if (reply.status == 404) return null
        val bytes = fetched(parsed(reply, DiskLink.serializer()).href) ?: return null
        return Stored(bytes, hex(sha256(bytes)))
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        upload(path, bytes, true)
    }

    override suspend fun writeIf(path: String, bytes: ByteArray, over: Stored?): Boolean {
        if (over != null && read(path)?.tag != over.tag) return false
        return upload(path, bytes, over != null)
    }

    override suspend fun delete(path: String) {
        val reply = login.send("DELETE", "$RESOURCES?path=${place(path)}&permanently=true")
        if (reply.status != 204 && reply.status != 202 && reply.status != 404) throw failed(reply)
    }

    // false where a file is there that is not to be written over; the folders of a new vault are made by the first write that misses them
    private suspend fun upload(path: String, bytes: ByteArray, over: Boolean): Boolean {
        val asked = "$RESOURCES/upload?path=${place(path)}&overwrite=$over"
        var reply = login.send("GET", asked)
        if (reply.status == 409 && code(reply) == "DiskPathDoesntExistsError") {
            folder("")
            if ('/' in path) folder(path.substringBeforeLast('/'))
            reply = login.send("GET", asked)
        }
        if (reply.status == 409 && code(reply) == "DiskResourceAlreadyExistsError") return false
        val link = parsed(reply, DiskLink.serializer()).href
        val sent = http.send("PUT", link, body = outgoing(bytes, "application/octet-stream"))
        if (sent.status != 201 && sent.status != 202) throw answered(Url(link).host, "${sent.status}")
        return true
    }

    private suspend fun folder(path: String) {
        val reply = login.send("PUT", "$RESOURCES?path=${place(path)}")
        if (reply.status != 201 && reply.status != 409) throw failed(reply)
    }

    // the link sends the request on to the server that holds the file
    private suspend fun fetched(link: String): ByteArray? {
        var url = link
        repeat(REDIRECTS) {
            val reply = http.send("GET", url)
            if (reply.status == 200) return reply.body
            if (reply.status == 404) return null
            val location = reply.header("Location")
            if (reply.status !in 300..399 || location == null) throw answered(Url(url).host, "${reply.status}")
            url = URLBuilder(url).takeFrom(location).buildString()
        }
        throw answered(Url(url).host, "302")
    }

    private fun place(path: String) = "app:/$SYNC_FOLDER/$path".trimEnd('/').encodeURLParameter()

    private fun <T> parsed(reply: Reply, serializer: KSerializer<T>): T {
        if (reply.status != 200) throw failed(reply)
        return try {
            fileJson.decodeFromString(serializer, reply.body.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw DeviceException(ErrorCategory.Api, "$DISK did not answer as the API of Yandex Disk")
        }
    }

    private fun code(reply: Reply) = try {
        fileJson.decodeFromString(DiskError.serializer(), reply.body.decodeToString()).error
    } catch (e: IllegalArgumentException) {
        ""
    }

    // the disk names the error in its JSON: DiskNotFoundError, DiskStorageQuotaExhaustedError and the like
    private fun failed(reply: Reply) = answered(DISK, "${reply.status} ${code(reply)}".trim())
}
