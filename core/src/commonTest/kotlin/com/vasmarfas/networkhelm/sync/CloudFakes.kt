package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.keenetic.hex
import com.vasmarfas.networkhelm.keenetic.sha256
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

internal const val CLOUD_ACCESS = "access token of this device 6Qe"
internal const val CLOUD_REFRESH = "refresh token of this device 2Lx"
internal const val CLOUD_CODE = "code of the sign-in 9Vb"
internal const val WEB_TOKEN = "web token of the sign-in 0/Ab+c="
internal const val CLOUDKIT_API_TOKEN = "api token of the container 5Hn"
private const val CLIENT = "nh-test-client"

private val JSON = headersOf("Content-Type", "application/json")

internal fun cloudTokens(access: String = CLOUD_ACCESS, expires: Long = 0) = textOf(OAuthTokens(access, CLOUD_REFRESH, expires))

internal fun cloudHttp(answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
    SyncHttp(TrustPolicy(MemoryTrustStore()) { false }) { HttpClient(MockEngine(answer)) }

// a body that stops halfway, as when the connection drops
private suspend fun cut(bytes: ByteArray): ByteReadChannel = ByteChannel().apply {
    writeFully(bytes, 0, bytes.size / 2)
    flush()
    cancel(IllegalStateException("connection reset"))
}

private suspend fun body(bytes: ByteArray, breaking: Boolean): ByteReadChannel = if (breaking) cut(bytes) else ByteReadChannel(bytes)

private suspend fun body(text: String, breaking: Boolean) = body(text.encodeToByteArray(), breaking)

private fun two(value: Int) = value.toString().padStart(2, '0')

private fun ByteArray.indexOf(part: ByteArray, from: Int): Int = (from..size - part.size).firstOrNull { at -> part.indices.all { this[at + it] == part[it] } } ?: -1

// the token endpoint of a cloud: the code it gave, with the verifier of the challenge it was asked with, and its refresh token each give
// a new access token, and with rotates a new refresh token as well, as Microsoft and Yandex do
internal class FakeAuthority(base: OAuthClient, private val rotates: Boolean = false) {
    val client = base.copy(id = CLIENT)
    var access = CLOUD_ACCESS
    var refresh = CLOUD_REFRESH
    var challenge = ""
    val forms = mutableListOf<Parameters>()

    fun asks(request: HttpRequestData) = request.url.toString() == client.token

    fun signed(request: HttpRequestData, scheme: String = "Bearer") = request.headers["Authorization"] == "$scheme $access"

    fun http() = cloudHttp { if (asks(it)) grant(it) else respond("", HttpStatusCode.NotFound) }

    suspend fun MockRequestHandleScope.grant(request: HttpRequestData): HttpResponseData {
        val form = parseQueryString(request.body.toByteArray().decodeToString())
        forms += form
        val code = form["grant_type"] == "authorization_code"
        val known = when {
            form["client_id"] != client.id || form["client_secret"].orEmpty() != client.secret -> false
            code -> form["code"] == CLOUD_CODE && challenge.isNotEmpty() && challenge(form["code_verifier"].orEmpty()) == challenge
            else -> form["grant_type"] == "refresh_token" && form["refresh_token"] == refresh
        }
        if (!known) return respond("{\"error\":\"invalid_grant\",\"error_description\":\"the grant is not known here\"}", HttpStatusCode.BadRequest, JSON)
        access = "access token ${forms.size + 1}"
        if (!code && rotates) refresh = "refresh token ${forms.size + 1}"
        val renewed = if (code || rotates) ",\"refresh_token\":\"$refresh\"" else ""
        return respond("{\"access_token\":\"$access\",\"token_type\":\"bearer\",\"expires_in\":3600$renewed}", HttpStatusCode.OK, JSON)
    }
}

// Yandex Disk the way its REST API answers in the folder of an app: items two a page, links to hosts of their own to put and get a file
// by, the download link sending the request on to a storage server, and the errors named in JSON
internal class FakeYandexDisk {
    private class File(val bytes: ByteArray, val written: Int)

    private val files = HashMap<String, File>()
    private val folders = mutableSetOf("app:")
    private val links = HashMap<String, String>()
    private var writes = 0
    private var broken = false
    val authority = FakeAuthority(YANDEX_DISK, rotates = true)
    val requests = mutableListOf<HttpRequestData>()
    private val http = cloudHttp { answer(it) }

    fun service(tokens: String = cloudTokens(), keep: suspend (String) -> Unit = {}) = YandexDiskService(http, OAuthLogin(http, authority.client, tokens, keep, "OAuth"))

    fun fake(name: String) = Fake(name, service(), { path, bytes -> placed("app:/$SYNC_FOLDER/$path", bytes) }, { files.remove("app:/$SYNC_FOLDER/$it") })

    fun breakNext() {
        broken = true
    }

    private fun placed(path: String, bytes: ByteArray) {
        var folder = path.substringBeforeLast('/')
        while (folder != "app:") {
            folders += folder
            folder = folder.substringBeforeLast('/')
        }
        files[path] = File(bytes, ++writes)
    }

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        requests += request
        if (authority.asks(request)) return with(authority) { grant(request) }
        val breaking = broken.also { broken = false }
        if (breaking && request.method.value != "GET") throw IllegalStateException("connection reset")
        if (request.url.host != "cloud-api.yandex.net") return carried(request)
        if (!authority.signed(request, "OAuth")) return failed(HttpStatusCode.Unauthorized, "UnauthorizedError")
        val path = request.url.parameters["path"].orEmpty()
        val parent = path.substringBeforeLast('/')
        return when ("${request.method.value} ${request.url.encodedPath}") {
            "GET /v1/disk/resources" -> if (path in folders) listing(path, request.url.parameters["offset"]?.toInt() ?: 0, breaking) else failed(HttpStatusCode.NotFound, "DiskNotFoundError")
            "PUT /v1/disk/resources" -> when {
                path in folders -> failed(HttpStatusCode.Conflict, "DiskPathPointsToExistentDirectoryError")
                parent !in folders -> failed(HttpStatusCode.Conflict, "DiskPathDoesntExistsError")
                else -> {
                    folders += path
                    respond("{\"href\":\"https://cloud-api.yandex.net/v1/disk/resources?path=$path\",\"method\":\"GET\"}", HttpStatusCode.Created, JSON)
                }
            }
            "GET /v1/disk/resources/upload" -> when {
                parent !in folders -> failed(HttpStatusCode.Conflict, "DiskPathDoesntExistsError")
                path in files && request.url.parameters["overwrite"] != "true" -> failed(HttpStatusCode.Conflict, "DiskResourceAlreadyExistsError")
                else -> link("https://uploader1j.disk.yandex.net/upload-target/", path, breaking)
            }
            "GET /v1/disk/resources/download" -> if (path in files) link("https://downloader.disk.yandex.ru/disk/", path, breaking) else failed(HttpStatusCode.NotFound, "DiskNotFoundError")
            "DELETE /v1/disk/resources" -> if (files.remove(path) != null) respond("", HttpStatusCode.NoContent) else failed(HttpStatusCode.NotFound, "DiskNotFoundError")
            else -> failed(HttpStatusCode.MethodNotAllowed, "MethodNotAllowedError")
        }
    }

    // the hosts of the links take no token, and the downloader sends the request on to the server that holds the file
    private suspend fun MockRequestHandleScope.carried(request: HttpRequestData): HttpResponseData {
        if (request.headers["Authorization"] != null) return respond("a token came to the host of a link", HttpStatusCode.BadRequest)
        val id = request.url.encodedPath.substringAfterLast('/')
        val path = links[id] ?: return respond("", HttpStatusCode.NotFound)
        return when (request.url.host) {
            "uploader1j.disk.yandex.net" -> {
                placed(path, request.body.toByteArray())
                respond("", HttpStatusCode.Created)
            }
            "downloader.disk.yandex.ru" -> respond("", HttpStatusCode.Found, headersOf("Location", "https://s143vla.storage.yandex.net/rdisk/$id"))
            else -> files[path]?.let { respond(it.bytes, HttpStatusCode.OK) } ?: respond("", HttpStatusCode.NotFound)
        }
    }

    private suspend fun MockRequestHandleScope.link(base: String, path: String, breaking: Boolean): HttpResponseData {
        val id = "${links.size + 1}"
        links[id] = path
        return respond(body("{\"href\":\"$base$id\",\"method\":\"PUT\",\"templated\":false}", breaking), HttpStatusCode.OK, JSON)
    }

    private suspend fun MockRequestHandleScope.listing(folder: String, offset: Int, breaking: Boolean): HttpResponseData {
        val inside = folders.filter { it != folder && it.substringBeforeLast('/') == folder }.map { it to null } +
            files.filterKeys { it.substringBeforeLast('/') == folder }.map { it.key to it.value }
        val items = inside.sortedBy { it.first }.drop(offset).take(2).joinToString(",", "[", "]") { (path, file) ->
            val name = path.substringAfterLast('/')
            if (file == null) {
                "{\"name\":\"$name\",\"path\":\"$path\",\"type\":\"dir\"}"
            } else {
                "{\"name\":\"$name\",\"path\":\"$path\",\"type\":\"file\",\"md5\":\"${hex(sha256(file.bytes)).take(32)}\",\"size\":${file.bytes.size}," +
                    "\"modified\":\"2026-11-18T10:${two(file.written / 60)}:${two(file.written % 60)}+03:00\"}"
            }
        }
        val json = "{\"path\":\"$folder\",\"type\":\"dir\",\"_embedded\":{\"items\":$items,\"limit\":2,\"offset\":$offset,\"total\":${inside.size}}}"
        return respond(body(json, breaking), HttpStatusCode.OK, JSON)
    }

    private fun MockRequestHandleScope.failed(status: HttpStatusCode, error: String) =
        respond("{\"message\":\"$error\",\"description\":\"$error\",\"error\":\"$error\"}", status, JSON)
}

// Dropbox the way its API answers in the folder of an app: entries two a page, a revision that changes with each write, strict conflicts of
// add and update, the errors as their error_summary, and a long poll without a token that answers once the files change after its cursor
internal class FakeDropbox {
    private class File(val bytes: ByteArray, val rev: String)

    private val files = HashMap<String, File>()
    private val version = MutableStateFlow(0)
    private var broken = false
    val authority = FakeAuthority(DROPBOX)
    val requests = mutableListOf<HttpRequestData>()
    private val http = cloudHttp { answer(it) }

    fun service(tokens: String = cloudTokens(), keep: suspend (String) -> Unit = {}) = DropboxService(http, OAuthLogin(http, authority.client, tokens, keep))

    fun fake(name: String) = Fake(name, service(), { path, bytes -> stored("/$SYNC_FOLDER/$path", bytes) }, { path -> changed { files.remove("/$SYNC_FOLDER/$path".lowercase()) } })

    fun breakNext() {
        broken = true
    }

    private fun stored(path: String, bytes: ByteArray) = changed { files[path.lowercase()] = File(bytes, "015d2a${version.value + 1}") }

    private fun changed(change: () -> Unit) {
        change()
        version.update { it + 1 }
    }

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        requests += request
        if (authority.asks(request)) return with(authority) { grant(request) }
        if (request.url.host == "notify.dropboxapi.com") return polled(request)
        if (!authority.signed(request)) return respond("{\"error_summary\":\"invalid_access_token/..\",\"error\":{\".tag\":\"invalid_access_token\"}}", HttpStatusCode.Unauthorized, JSON)
        val endpoint = request.url.encodedPath
        val breaking = broken.also { broken = false }
        if (breaking && endpoint != "/2/files/download") throw IllegalStateException("connection reset")
        val text = request.headers["Dropbox-API-Arg"] ?: request.body.toByteArray().decodeToString()
        val argument = if (text.isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
        val path = argument["path"]?.jsonPrimitive?.content.orEmpty().lowercase()
        return when (endpoint) {
            "/2/files/list_folder" -> if (files.keys.any { it.startsWith("$path/") }) page(0) else conflict("path/not_found/..")
            "/2/files/list_folder/continue" -> page(argument["cursor"]?.jsonPrimitive?.content.orEmpty().removePrefix("page-").toInt())
            "/2/files/list_folder/get_latest_cursor" -> respond("{\"cursor\":\"latest-${version.value}\"}", HttpStatusCode.OK, JSON)
            "/2/files/download" -> files[path]?.let { file ->
                respond(body(file.bytes, breaking), HttpStatusCode.OK, headersOf("Dropbox-API-Result", "{\"name\":\"${path.substringAfterLast('/')}\",\"path_lower\":\"$path\",\"rev\":\"${file.rev}\"}"))
            } ?: conflict("path/not_found/..")
            "/2/files/upload" -> upload(path, argument, request.body.toByteArray())
            "/2/files/delete_v2" -> if (path in files) {
                changed { files.remove(path) }
                respond("{\"metadata\":{\".tag\":\"file\",\"path_lower\":\"$path\"}}", HttpStatusCode.OK, JSON)
            } else {
                conflict("path_lookup/not_found/..")
            }
            "/2/auth/token/revoke" -> {
                authority.access = ""
                respond("null", HttpStatusCode.OK, JSON)
            }
            else -> respond("Unknown API function: \"$endpoint\"", HttpStatusCode.BadRequest)
        }
    }

    // without strict_conflict Dropbox writes over a file of the same bytes and makes a file anew where an update finds none
    private fun MockRequestHandleScope.upload(path: String, argument: JsonObject, bytes: ByteArray): HttpResponseData {
        val mode = argument["mode"]
        val strict = argument["strict_conflict"]?.jsonPrimitive?.booleanOrNull == true
        val current = files[path]
        val conflict = when {
            mode is JsonPrimitive && mode.content == "add" -> current != null && (strict || !current.bytes.contentEquals(bytes))
            mode is JsonObject -> if (current == null) strict else mode["update"]?.jsonPrimitive?.content != current.rev
            else -> false
        }
        if (conflict) return conflict("path/conflict/file/..")
        stored(path, bytes)
        return respond("{\"name\":\"${path.substringAfterLast('/')}\",\"path_lower\":\"$path\",\"rev\":\"${files.getValue(path).rev}\"}", HttpStatusCode.OK, JSON)
    }

    private fun MockRequestHandleScope.page(start: Int): HttpResponseData {
        val folders = (files.keys.map { it.substringBeforeLast('/') } + "/$SYNC_FOLDER").toSet()
        val entries = (folders.map { it to "{\".tag\":\"folder\",\"name\":\"${it.substringAfterLast('/')}\",\"path_lower\":\"$it\"}" } +
            files.map { (path, file) -> path to "{\".tag\":\"file\",\"name\":\"${path.substringAfterLast('/')}\",\"path_lower\":\"$path\",\"rev\":\"${file.rev}\"}" })
            .sortedBy { it.first }.map { it.second }
        val more = start + 2 < entries.size
        val cursor = if (more) "page-${start + 2}" else "latest-${version.value}"
        return respond("{\"entries\":${entries.drop(start).take(2).joinToString(",", "[", "]")},\"cursor\":\"$cursor\",\"has_more\":$more}", HttpStatusCode.OK, JSON)
    }

    // the long poll takes no token, and Dropbox refuses one that comes with it
    private suspend fun MockRequestHandleScope.polled(request: HttpRequestData): HttpResponseData {
        if (request.headers["Authorization"] != null) return respond("Your request includes an Authorization header, but this function does not use it.", HttpStatusCode.BadRequest)
        val cursor = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject["cursor"]?.jsonPrimitive?.content.orEmpty()
        val after = cursor.removePrefix("latest-").toInt()
        version.first { it != after }
        return respond("{\"changes\":true}", HttpStatusCode.OK, JSON)
    }

    private fun MockRequestHandleScope.conflict(summary: String) = respond("{\"error_summary\":\"$summary\",\"error\":{}}", HttpStatusCode.Conflict, JSON)
}

// Google Drive the way its API answers in the hidden folder of an app: two files a page and the newest first, a name any number of files
// may share, a version that grows with each change of a file, a new file as its metadata and its bytes in parts of one request
internal class FakeGoogleDrive {
    private class File(val id: String, val name: String, var bytes: ByteArray, var version: Int, val created: String)

    private val files = mutableListOf<File>()
    private var made = 0
    private var broken = false
    // the bytes of a file of the same name another device makes just before the next file this one makes
    var racing: ByteArray? = null
    val authority = FakeAuthority(GOOGLE_DRIVE.copy(secret = "secret of a client for a desktop"))
    private val http = cloudHttp { answer(it) }

    fun service(tokens: String = cloudTokens(), keep: suspend (String) -> Unit = {}) = GoogleDriveService(OAuthLogin(http, authority.client, tokens, keep))

    fun fake(name: String) = Fake(name, service(), { path, bytes -> put("$SYNC_FOLDER/$path", bytes) }, { path -> files.removeAll { it.name == "$SYNC_FOLDER/$path" } })

    fun breakNext() {
        broken = true
    }

    fun named(path: String) = files.count { it.name == "$SYNC_FOLDER/$path" }

    private fun put(name: String, bytes: ByteArray) {
        val file = files.firstOrNull { it.name == name }
        if (file == null) {
            make(name, bytes)
        } else {
            file.bytes = bytes
            file.version++
        }
    }

    private fun make(name: String, bytes: ByteArray) = File("1Fz${++made}xQ", name, bytes, 1, "2026-11-18T10:00:00.${made.toString().padStart(3, '0')}Z").also { files += it }

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        if (authority.asks(request)) return with(authority) { grant(request) }
        if (!authority.signed(request)) return failed(HttpStatusCode.Unauthorized, "Request had invalid authentication credentials.")
        val breaking = broken.also { broken = false }
        if (breaking && request.method.value != "GET") throw IllegalStateException("connection reset")
        val path = request.url.encodedPath
        val id = path.substringAfterLast('/')
        val file = files.firstOrNull { it.id == id }
        return when {
            request.method.value == "GET" && path == "/drive/v3/files" -> listed(request.url.parameters, breaking)
            request.method.value == "GET" && path.startsWith("/drive/v3/files/") && request.url.parameters["alt"] == "media" ->
                file?.let { respond(body(it.bytes, breaking), HttpStatusCode.OK) } ?: failed(HttpStatusCode.NotFound, "File not found: $id.")
            request.method.value == "POST" && path == "/upload/drive/v3/files" && request.url.parameters["uploadType"] == "multipart" -> created(request)
            request.method.value == "PATCH" && path.startsWith("/upload/drive/v3/files/") && request.url.parameters["uploadType"] == "media" -> if (file == null) {
                failed(HttpStatusCode.NotFound, "File not found: $id.")
            } else {
                file.bytes = request.body.toByteArray()
                file.version++
                respond("{\"id\":\"$id\"}", HttpStatusCode.OK, JSON)
            }
            request.method.value == "DELETE" && path.startsWith("/drive/v3/files/") -> if (file != null && files.remove(file)) {
                respond("", HttpStatusCode.NoContent)
            } else {
                failed(HttpStatusCode.NotFound, "File not found: $id.")
            }
            else -> failed(HttpStatusCode.NotFound, "Not Found")
        }
    }

    // only the hidden folder of the app is open to a token of drive.appdata
    private suspend fun MockRequestHandleScope.listed(parameters: Parameters, breaking: Boolean): HttpResponseData {
        if (parameters["spaces"] != "appDataFolder") return failed(HttpStatusCode.Forbidden, "The granted scopes do not give access to all of the requested spaces.")
        val name = parameters["q"]?.let { Regex("name = '([^']*)'").find(it)?.groupValues?.get(1) }
        val all = files.filter { name == null || it.name == name }.reversed()
        val start = parameters["pageToken"]?.toInt() ?: 0
        val next = if (start + 2 < all.size) ",\"nextPageToken\":\"${start + 2}\"" else ""
        val json = all.drop(start).take(2).joinToString(",", "[", "]") { "{\"id\":\"${it.id}\",\"name\":\"${it.name}\",\"version\":\"${it.version}\",\"createdTime\":\"${it.created}\"}" }
        return respond(body("{\"files\":$json$next}", breaking), HttpStatusCode.OK, JSON)
    }

    private suspend fun MockRequestHandleScope.created(request: HttpRequestData): HttpResponseData {
        val boundary = request.body.contentType?.parameter("boundary") ?: return failed(HttpStatusCode.BadRequest, "Missing boundary")
        val bytes = request.body.toByteArray()
        val separator = "\r\n\r\n".encodeToByteArray()
        val start = bytes.indexOf(separator, 0) + separator.size
        val end = bytes.indexOf("\r\n--$boundary\r\n".encodeToByteArray(), start)
        val media = bytes.indexOf(separator, end + 1) + separator.size
        val last = bytes.indexOf("\r\n--$boundary--".encodeToByteArray(), media)
        if (start < separator.size || end < 0 || last < 0) return failed(HttpStatusCode.BadRequest, "Malformed multipart body.")
        val metadata = Json.parseToJsonElement(bytes.copyOfRange(start, end).decodeToString()).jsonObject
        val name = metadata["name"]?.jsonPrimitive?.content.orEmpty()
        if (metadata["parents"]?.jsonArray?.map { it.jsonPrimitive.content } != listOf("appDataFolder")) return failed(HttpStatusCode.Forbidden, "The user does not have sufficient permissions.")
        racing?.let {
            make(name, it)
            racing = null
        }
        return respond("{\"id\":\"${make(name, bytes.copyOfRange(media, last)).id}\"}", HttpStatusCode.OK, JSON)
    }

    private fun MockRequestHandleScope.failed(status: HttpStatusCode, message: String) =
        respond("{\"error\":{\"code\":${status.value},\"message\":\"$message\",\"errors\":[{\"message\":\"$message\",\"domain\":\"global\"}]}}", status, JSON)
}

// OneDrive the way Microsoft Graph answers in the folder of an app: items by path, children two a page, folders that a write misses until
// they are made, an eTag that changes with each write and that If-Match is compared with, the bytes behind a download link without a token
internal class FakeOneDrive {
    private class Item(val bytes: ByteArray, val written: Int)

    private val items = HashMap<String, Item>()
    private val folders = mutableSetOf("")
    private val links = HashMap<String, String>()
    private var writes = 0
    private var broken = false
    val authority = FakeAuthority(ONEDRIVE, rotates = true)
    val requests = mutableListOf<HttpRequestData>()
    private val http = cloudHttp { answer(it) }

    fun service(tokens: String = cloudTokens(), keep: suspend (String) -> Unit = {}) = OneDriveService(http, OAuthLogin(http, authority.client, tokens, keep))

    fun fake(name: String) = Fake(name, service(), { path, bytes -> placed("$SYNC_FOLDER/$path", bytes) }, { items.remove("$SYNC_FOLDER/$it") })

    fun breakNext() {
        broken = true
    }

    private fun placed(path: String, bytes: ByteArray) {
        var folder = path.substringBeforeLast('/', "")
        while (folder.isNotEmpty()) {
            folders += folder
            folder = folder.substringBeforeLast('/', "")
        }
        items[path] = Item(bytes, ++writes)
    }

    private fun tag(item: Item) = "\"{5E3C0E2A-61B4-4F3D-9A50-${item.written.toString().padStart(12, '0')}},1\""

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        requests += request
        if (authority.asks(request)) return with(authority) { grant(request) }
        val breaking = broken.also { broken = false }
        if (breaking && request.method.value != "GET") throw IllegalStateException("connection reset")
        if (request.url.host != "graph.microsoft.com") return downloaded(request)
        if (!authority.signed(request)) return failed(HttpStatusCode.Unauthorized, "InvalidAuthenticationToken")
        val target = request.url.encodedPath.decodeURLPart().removePrefix("/v1.0/me/drive/special/approot")
        val (path, action) = when {
            target == "/children" -> "" to "children"
            target.endsWith(":/children") -> target.removePrefix(":/").removeSuffix(":/children") to "children"
            target.endsWith(":/content") -> target.removePrefix(":/").removeSuffix(":/content") to "content"
            else -> target.removePrefix(":/") to ""
        }
        val item = items[path]
        return when ("${request.method.value} $action") {
            "GET children" -> if (path in folders) children(path, request.url.parameters["\$skiptoken"]?.toInt() ?: 0, breaking) else failed(HttpStatusCode.NotFound, "itemNotFound")
            "POST children" -> {
                val name = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                val made = if (path.isEmpty()) name else "$path/$name"
                when {
                    path !in folders -> failed(HttpStatusCode.NotFound, "itemNotFound")
                    made in folders || made in items -> failed(HttpStatusCode.Conflict, "nameAlreadyExists")
                    else -> {
                        folders += made
                        respond("{\"name\":\"$name\",\"folder\":{\"childCount\":0}}", HttpStatusCode.Created, JSON)
                    }
                }
            }
            "GET " -> if (item != null) {
                val id = "${links.size + 1}"
                links[id] = path
                val json = "{\"name\":\"${path.substringAfterLast('/')}\",\"eTag\":${JsonPrimitive(tag(item))},\"file\":{\"mimeType\":\"application/octet-stream\"}," +
                    "\"@microsoft.graph.downloadUrl\":\"https://public.dm.files.1drv.com/y4m$id\"}"
                respond(body(json, breaking), HttpStatusCode.OK, JSON)
            } else {
                failed(HttpStatusCode.NotFound, "itemNotFound")
            }
            "PUT content" -> {
                val match = request.headers["If-Match"]
                when {
                    path.substringBeforeLast('/', "") !in folders -> failed(HttpStatusCode.NotFound, "itemNotFound")
                    request.url.parameters["@microsoft.graph.conflictBehavior"] == "fail" && item != null -> failed(HttpStatusCode.Conflict, "nameAlreadyExists")
                    match != null && (item == null || match != tag(item)) -> failed(HttpStatusCode.PreconditionFailed, "resourceModified")
                    else -> {
                        placed(path, request.body.toByteArray())
                        val json = "{\"name\":\"${path.substringAfterLast('/')}\",\"eTag\":${JsonPrimitive(tag(items.getValue(path)))},\"file\":{}}"
                        respond(json, if (item == null) HttpStatusCode.Created else HttpStatusCode.OK, JSON)
                    }
                }
            }
            "DELETE " -> if (items.remove(path) != null) respond("", HttpStatusCode.NoContent) else failed(HttpStatusCode.NotFound, "itemNotFound")
            else -> failed(HttpStatusCode.BadRequest, "invalidRequest")
        }
    }

    private suspend fun MockRequestHandleScope.children(folder: String, start: Int, breaking: Boolean): HttpResponseData {
        val inside = folders.filter { it.isNotEmpty() && it != folder && it.substringBeforeLast('/', "") == folder }.map { it to null } +
            items.filterKeys { it.substringBeforeLast('/', "") == folder }.map { it.key to it.value }
        val value = inside.sortedBy { it.first }.drop(start).take(2).joinToString(",", "[", "]") { (path, item) ->
            val name = JsonPrimitive(path.substringAfterLast('/'))
            if (item == null) "{\"name\":$name,\"folder\":{\"childCount\":1}}" else "{\"name\":$name,\"eTag\":${JsonPrimitive(tag(item))},\"file\":{}}"
        }
        val next = if (start + 2 < inside.size) ",\"@odata.nextLink\":\"https://graph.microsoft.com/v1.0/me/drive/special/approot:/$folder:/children?\$skiptoken=${start + 2}\"" else ""
        return respond(body("{\"value\":$value$next}", breaking), HttpStatusCode.OK, JSON)
    }

    // the download link holds an authorisation of its own and takes no token
    private fun MockRequestHandleScope.downloaded(request: HttpRequestData): HttpResponseData {
        if (request.headers["Authorization"] != null) return respond("a token came to a download link", HttpStatusCode.BadRequest)
        val item = links[request.url.encodedPath.removePrefix("/y4m")]?.let { items[it] } ?: return respond("", HttpStatusCode.NotFound)
        return respond(item.bytes, HttpStatusCode.OK)
    }

    private fun MockRequestHandleScope.failed(status: HttpStatusCode, code: String) =
        respond("{\"error\":{\"code\":\"$code\",\"message\":\"$code\"}}", status, JSON)
}

// CloudKit Web Services the way they answer for the private database: a web token good for one request that each answer replaces in a
// header, records of a query two a page, looked up and changed by name, the change tag compared on replace, the errors given per record
internal class FakeCloudKit {
    private class Kept(val path: String, val bytes: ByteArray, val change: Int)

    private val records = HashMap<String, Kept>()
    private var changes = 0
    private var issued = 0
    private var broken = false
    private var stalled = false
    var token = WEB_TOKEN
    val kept = mutableListOf<String>()
    val requests = mutableListOf<HttpRequestData>()
    val container = CloudKitContainer("iCloud.com.vasmarfas.networkhelm", "development", CLOUDKIT_API_TOKEN, "http://127.0.0.1:0/")
    val http = cloudHttp { answer(it) }

    fun service(): CloudKitService = CloudKitService(http, container, token) { kept += it }

    fun fake(name: String) = Fake(name, service(), { path, bytes -> records[path.replace('/', '-')] = Kept(path, bytes, ++changes) }, { records.remove(it.replace('/', '-')) })

    fun breakNext() {
        broken = true
    }

    // the next request gets no answer before the client gives up on it, and the client tells of that with the address of the request
    fun stallNext() {
        stalled = true
    }

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        requests += request
        if (stalled) {
            stalled = false
            throw IllegalStateException("Request timeout has expired [url=${request.url}, request_timeout=120000 ms]")
        }
        val parameters = request.url.parameters
        val operation = request.url.encodedPath.removePrefix("/database/1/${container.id}/${container.environment}/private/")
        if (parameters["ckAPIToken"] != CLOUDKIT_API_TOKEN) return refusal(HttpStatusCode.Unauthorized, "AUTHENTICATION_FAILED")
        if (parameters["ckWebAuthToken"] != token) {
            val json = "{\"uuid\":\"4f02f7aa\",\"serverErrorCode\":\"AUTHENTICATION_REQUIRED\",\"reason\":\"request needs authorization\"," +
                "\"redirectURL\":\"https://idmsa.apple.com/IDMSWebAuth/signin?appIdKey=networkhelm\"}"
            return respond(json, HttpStatusCode(421, "Misdirected Request"), JSON)
        }
        val breaking = broken.also { broken = false }
        if (breaking && operation != "records/lookup") throw IllegalStateException("connection reset")
        val asked = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
        val json = when (operation) {
            "records/query" -> query(asked) ?: return refusal(HttpStatusCode.BadRequest, "BAD_REQUEST")
            "records/lookup" -> lookup(asked)
            "records/modify" -> modify(asked)
            else -> return refusal(HttpStatusCode.BadRequest, "BAD_REQUEST")
        }
        // a request cut off on its way back leaves the token it came with good
        if (!breaking) token = "web token ${++issued}/+="
        return respond(body(json, breaking), HttpStatusCode.OK, Headers.build {
            append("Content-Type", "application/json")
            append("X-Apple-CloudKit-Web-Auth-Token", token)
        })
    }

    private fun query(asked: JsonObject): String? {
        if (asked["query"]?.jsonObject?.get("recordType")?.jsonPrimitive?.content != "SyncFile") return null
        val all = records.entries.sortedBy { it.key }
        val start = asked["continuationMarker"]?.jsonPrimitive?.content?.toInt() ?: 0
        val marker = if (start + 2 < all.size) ",\"continuationMarker\":\"${start + 2}\"" else ""
        val json = all.drop(start).take(2).joinToString(",", "[", "]") { (name, kept) ->
            "{\"recordName\":\"$name\",\"recordType\":\"SyncFile\",\"recordChangeTag\":\"k${kept.change}\",\"fields\":{\"path\":{\"value\":\"${kept.path}\",\"type\":\"STRING\"}}}"
        }
        return "{\"records\":$json$marker}"
    }

    private fun lookup(asked: JsonObject): String {
        val name = asked["records"]?.jsonArray?.singleOrNull()?.jsonObject?.get("recordName")?.jsonPrimitive?.content.orEmpty()
        val kept = records[name] ?: return "{\"records\":[{\"recordName\":\"$name\",\"reason\":\"Record not found\",\"serverErrorCode\":\"NOT_FOUND\"}]}"
        return "{\"records\":[${full(name, kept)}]}"
    }

    private fun modify(asked: JsonObject): String {
        val operation = asked["operations"]?.jsonArray?.singleOrNull()?.jsonObject ?: return "{\"records\":[]}"
        val type = operation["operationType"]?.jsonPrimitive?.content
        val record = operation["record"]?.jsonObject ?: JsonObject(emptyMap())
        val name = record["recordName"]?.jsonPrimitive?.content.orEmpty()
        val current = records[name]
        val refusal = when (type) {
            "create" -> if (current != null) "EXISTS" else null
            "replace" -> when {
                current == null -> "NOT_FOUND"
                record["recordChangeTag"]?.jsonPrimitive?.content != "k${current.change}" -> "CONFLICT"
                else -> null
            }
            "forceReplace" -> null
            "forceDelete" -> if (current == null) "NOT_FOUND" else null
            else -> "BAD_REQUEST"
        }
        if (refusal != null) return "{\"records\":[{\"recordName\":\"$name\",\"reason\":\"$refusal\",\"serverErrorCode\":\"$refusal\"}]}"
        if (type == "forceDelete") {
            records.remove(name)
            return "{\"records\":[{\"recordName\":\"$name\",\"deleted\":true}]}"
        }
        val fields = record["fields"]?.jsonObject
        val path = fields?.get("path")?.jsonObject?.get("value")?.jsonPrimitive?.content.orEmpty()
        val data = Base64.decode(fields?.get("data")?.jsonObject?.get("value")?.jsonPrimitive?.content.orEmpty())
        val kept = Kept(path, data, ++changes)
        records[name] = kept
        return "{\"records\":[${full(name, kept)}]}"
    }

    private fun full(name: String, kept: Kept) = "{\"recordName\":\"$name\",\"recordType\":\"SyncFile\",\"recordChangeTag\":\"k${kept.change}\"," +
        "\"fields\":{\"path\":{\"value\":\"${kept.path}\",\"type\":\"STRING\"},\"data\":{\"value\":\"${Base64.encode(kept.bytes)}\",\"type\":\"BYTES\"}}}"

    private fun MockRequestHandleScope.refusal(status: HttpStatusCode, code: String) =
        respond("{\"uuid\":\"0a1b2c3d\",\"serverErrorCode\":\"$code\",\"reason\":\"$code\"}", status, JSON)
}
