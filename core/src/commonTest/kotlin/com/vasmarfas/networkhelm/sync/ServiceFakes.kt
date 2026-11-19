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
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLParameter
import io.ktor.http.headersOf
import io.ktor.http.hostWithPortIfSpecified
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

internal const val DAV_PASSWORD = "app password 5mKq"
internal const val S3_SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
internal const val GIT_TOKEN = "token of the repository 7Tz"
internal const val GATEWAY_TOKEN = "token of this device 3Wd"
private const val DAV_USER = "nhsync"
private const val S3_ACCESS = "nhsync"

private fun mockHttp(answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
    SyncHttp(TrustPolicy(MemoryTrustStore()) { false }) { HttpClient(MockEngine(answer)) }

// a body that stops halfway, as when the connection drops
private suspend fun halfway(bytes: ByteArray): ByteReadChannel = ByteChannel().apply {
    writeFully(bytes, 0, bytes.size / 2)
    flush()
    cancel(IllegalStateException("connection reset"))
}

// a folder in memory, its disk pulled out in the middle of the next access while broken
internal class MemoryFolder : Folder {
    val files = HashMap<String, ByteArray>()
    var broken = false

    override suspend fun list(directory: String) = files.keys.filter { it.substringBeforeLast('/', "") == directory }.map { it.substringAfterLast('/') }

    override suspend fun read(path: String): ByteArray? {
        pulledOut()
        return files[path]
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        pulledOut()
        files[path] = bytes.copyOf()
    }

    override suspend fun delete(path: String) {
        files.remove(path)
    }

    fun fake() = Fake("folder", FolderService(this), { path, bytes -> files["$SYNC_FOLDER/$path"] = bytes }, { files.remove("$SYNC_FOLDER/$it") })

    private fun pulledOut() {
        if (broken) {
            broken = false
            throw IllegalStateException("the disk was pulled out")
        }
    }
}

// a WebDAV server the way Nextcloud and Apache answer: collections made by MKCOL, PROPFIND of depth 1, If-Match compared strongly.
// Apache tags the file written last weakly until age() and strongly with the same value after it; Nextcloud escapes the quotes of a
// tag in its XML
internal class FakeWebDav(private val apache: Boolean) {
    private val files = HashMap<String, Pair<ByteArray, Int>>()
    private val collections = mutableSetOf("/dav/")
    private var writes = 0
    private var fresh: String? = null
    private var broken = false
    val requests = mutableListOf<String>()

    fun service(password: String = DAV_PASSWORD) = WebDavService(mockHttp { answer(it) }, "https://dav.example.org/dav", DAV_USER, password)

    fun fake(name: String) = Fake(name, service(), { path, bytes -> placed("/dav/$SYNC_FOLDER/$path", bytes) }, { files.remove("/dav/$SYNC_FOLDER/$it") })

    fun breakNext() {
        broken = true
    }

    fun age() {
        fresh = null
    }

    // a file another device or the user put there, the collections on its way with it
    private fun placed(path: String, bytes: ByteArray) {
        val parts = path.trim('/').split('/')
        for (count in 1 until parts.size) collections += parts.take(count).joinToString("/", "/", "/")
        stored(path, bytes)
    }

    private fun stored(path: String, bytes: ByteArray) {
        files[path] = bytes to ++writes
        if (apache) fresh = path
    }

    private fun tag(path: String) = "\"${files.getValue(path).second}\"".let { if (path == fresh) "W/$it" else it }

    private fun parent(path: String) = path.trimEnd('/').substringBeforeLast('/') + "/"

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        if (request.headers["Authorization"] != "Basic " + Base64.encode("$DAV_USER:$DAV_PASSWORD".encodeToByteArray())) {
            return respond("refused: ${request.headers["Authorization"]}", HttpStatusCode.Unauthorized)
        }
        val breaking = broken.also { broken = false }
        val file = files[path]
        return when (request.method.value) {
            "PROPFIND" -> if (path in collections) respond(multistatus(path), HttpStatusCode.MultiStatus) else respond("", HttpStatusCode.NotFound)
            "GET" -> if (file == null) {
                respond("", HttpStatusCode.NotFound)
            } else {
                val content = if (breaking) halfway(file.first) else ByteReadChannel(file.first)
                respond(content, HttpStatusCode.OK, headersOf("ETag", tag(path)))
            }
            "PUT" -> {
                val bytes = request.body.toByteArray()
                val match = request.headers["If-Match"]
                when {
                    parent(path) !in collections -> respond("", HttpStatusCode.Conflict)
                    request.headers["If-None-Match"] == "*" && file != null -> respond("", HttpStatusCode.PreconditionFailed)
                    match != null && (file == null || path == fresh || match != "\"${file.second}\"") -> respond("", HttpStatusCode.PreconditionFailed)
                    breaking -> throw IllegalStateException("connection reset")
                    else -> {
                        stored(path, bytes)
                        respond("", if (file == null) HttpStatusCode.Created else HttpStatusCode.NoContent)
                    }
                }
            }
            "MKCOL" -> when {
                path in collections -> respond("", HttpStatusCode.MethodNotAllowed)
                parent(path) !in collections -> respond("", HttpStatusCode.Conflict)
                else -> {
                    collections += path
                    respond("", HttpStatusCode.Created)
                }
            }
            "DELETE" -> respond("", if (files.remove(path) == null) HttpStatusCode.NotFound else HttpStatusCode.NoContent)
            else -> respond("", HttpStatusCode.MethodNotAllowed)
        }
    }

    private fun multistatus(collection: String): String {
        val members = listOf(collection) + collections.filter { it != collection && parent(it) == collection }.sorted() + files.keys.filter { parent(it) == collection }.sorted()
        val responses = members.joinToString("") { member ->
            val href = member.replace(" ", "%20")
            val properties = when {
                member.endsWith("/") && apache -> "<lp1:resourcetype><D:collection/></lp1:resourcetype>"
                member.endsWith("/") -> "<d:resourcetype><d:collection/></d:resourcetype>"
                apache -> "<lp1:resourcetype/><lp1:getcontentlength>${files.getValue(member).first.size}</lp1:getcontentlength><lp1:getetag>${tag(member)}</lp1:getetag>"
                else -> "<d:resourcetype/><d:getcontentlength>${files.getValue(member).first.size}</d:getcontentlength><d:getetag>${tag(member).replace("\"", "&quot;")}</d:getetag>"
            }
            if (apache) {
                "<D:response xmlns:lp1=\"DAV:\"><D:href>$href</D:href><D:propstat><D:prop>$properties</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
            } else {
                "<d:response><d:href>$href</d:href><d:propstat><d:prop>$properties</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
            }
        }
        return if (apache) {
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">$responses</D:multistatus>"
        } else {
            "<?xml version=\"1.0\"?>\n<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:oc=\"http://owncloud.org/ns\">$responses</d:multistatus>"
        }
    }
}

// a bucket the way MinIO and AWS answer: ListObjectsV2 two keys a page, If-Match and If-None-Match on PUT, the codes of S3 in errors.
// A request passes only with the signature SigV4 makes of what reached the bucket, the query put in order by the bucket itself
internal class FakeS3 {
    private val objects = HashMap<String, ByteArray>()
    private var broken = false
    val requests = mutableListOf<HttpRequestData>()

    fun service(secret: String = S3_SECRET) = S3Service(mockHttp { answer(it) }, "https://s3.example.org:9000", "networkhelm", "us-east-1", S3_ACCESS, secret)

    fun fake(name: String) = Fake(name, service(), { key, bytes -> objects["$SYNC_FOLDER/$key"] = bytes }, { objects.remove("$SYNC_FOLDER/$it") })

    fun breakNext() {
        broken = true
    }

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        requests += request
        val body = request.body.toByteArray()
        if (!signed(request, body)) return failed(HttpStatusCode.Forbidden, "SignatureDoesNotMatch")
        val breaking = broken.also { broken = false }
        val key = request.url.encodedPath.removePrefix("/networkhelm").removePrefix("/")
        val current = objects[key]
        return when (request.method.value) {
            "GET" -> when {
                key.isEmpty() -> respond(page(request.url.parameters), HttpStatusCode.OK)
                current == null -> failed(HttpStatusCode.NotFound, "NoSuchKey")
                else -> {
                    val content = if (breaking) halfway(current) else ByteReadChannel(current)
                    respond(content, HttpStatusCode.OK, headersOf("ETag", tag(current)))
                }
            }
            "PUT" -> {
                val match = request.headers["If-Match"]
                when {
                    request.headers["If-None-Match"] == "*" && current != null -> failed(HttpStatusCode.PreconditionFailed, "PreconditionFailed")
                    match != null && current == null -> failed(HttpStatusCode.NotFound, "NoSuchKey")
                    match != null && current != null && match != tag(current) -> failed(HttpStatusCode.PreconditionFailed, "PreconditionFailed")
                    breaking -> throw IllegalStateException("connection reset")
                    else -> {
                        objects[key] = body
                        respond("", HttpStatusCode.OK, headersOf("ETag", tag(body)))
                    }
                }
            }
            "DELETE" -> {
                objects.remove(key)
                respond("", HttpStatusCode.NoContent)
            }
            else -> failed(HttpStatusCode.MethodNotAllowed, "MethodNotAllowed")
        }
    }

    private fun signed(request: HttpRequestData, body: ByteArray): Boolean {
        val authorization = request.headers["Authorization"] ?: return false
        val names = authorization.substringAfter("SignedHeaders=").substringBefore(",").split(";")
        val headers = names.associateWith { if (it == "host") request.url.hostWithPortIfSpecified else request.headers[it].orEmpty() }
        val query = request.url.parameters.entries().flatMap { (name, values) -> values.map { "${name.encodeURLParameter()}=${it.encodeURLParameter()}" } }
        val payload = hex(sha256(body))
        val expected = sigV4(request.method.value, request.url.encodedPath, query.sorted().joinToString("&"), headers, payload, request.headers["x-amz-date"].orEmpty(), "us-east-1", S3_ACCESS, S3_SECRET)
        return payload == request.headers["x-amz-content-sha256"] && authorization == expected
    }

    private fun page(parameters: Parameters): String {
        val keys = objects.keys.filter { it.startsWith(parameters["prefix"].orEmpty()) }.sorted()
        val start = parameters["continuation-token"]?.let { keys.indexOf(Base64.decode(it).decodeToString()) } ?: 0
        val next = keys.getOrNull(start + 2)
        val contents = keys.drop(start).take(2).joinToString("") { key ->
            "<Contents><Key>$key</Key><ETag>${tag(objects.getValue(key)).replace("\"", "&quot;")}</ETag><Size>${objects.getValue(key).size}</Size></Contents>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>networkhelm</Name>" +
            "<IsTruncated>${next != null}</IsTruncated>" + next?.let { "<NextContinuationToken>${Base64.encode(it.encodeToByteArray())}</NextContinuationToken>" }.orEmpty() +
            contents + "</ListBucketResult>"
    }

    private fun MockRequestHandleScope.failed(status: HttpStatusCode, code: String) =
        respond("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>$code</Code><Message>$code</Message></Error>", status)

    private fun tag(bytes: ByteArray) = "\"${hex(sha256(bytes)).take(32)}\""
}

private const val NOT_FOUND = "{\"message\":\"Not Found\"}"

// a repository the way GitHub, Gitea and GitLab answer through their APIs: a commit for each write, a file of GitHub and Gitea replaced
// over the sha of its blob and one of GitLab over its last commit. GitHub sends a file of more than 4 bytes without its content, as it
// does above 1 MB, and the blob holds it; GitLab gives its tree two entries a page
internal class FakeGit(private val host: GitHost) {
    private class Blob(val bytes: ByteArray, val sha: String, val commit: String)

    private class Answer(val status: HttpStatusCode, val json: String, val next: String = "")

    private val files = HashMap<String, Blob>()
    private var commits = 0
    private var broken = false
    val requests = mutableListOf<String>()

    fun service(token: String = GIT_TOKEN): GitService {
        val api = when (host) {
            GitHost.GitHub -> "https://api.github.com"
            GitHost.Gitea -> "https://git.example.org/api/v1"
            GitHost.GitLab -> "https://gitlab.example.org/api/v4"
        }
        return GitService(mockHttp { answer(it) }, host, api, "nhsync", "networkhelm-sync", "main", token)
    }

    fun fake(name: String) = Fake(name, service(), { path, bytes -> committed("$SYNC_FOLDER/$path", bytes) }, { files.remove("$SYNC_FOLDER/$it") })

    fun breakNext() {
        broken = true
    }

    private fun committed(path: String, bytes: ByteArray) {
        files[path] = Blob(bytes, hex(sha256(bytes)).take(40), "c${++commits}")
    }

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        if (request.headers["Authorization"] != (if (host == GitHost.Gitea) "token $GIT_TOKEN" else "Bearer $GIT_TOKEN")) {
            return respond("{\"message\":\"Bad credentials\"}", HttpStatusCode.Unauthorized)
        }
        val breaking = broken.also { broken = false }
        if (breaking && request.method.value != "GET") throw IllegalStateException("connection reset")
        val body = request.body.toByteArray().decodeToString()
        val change = if (body.isEmpty()) emptyMap() else Json.parseToJsonElement(body).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull }
        val answer = when (host) {
            GitHost.GitLab -> gitLab(request.method.value, path.removePrefix("/api/v4/projects/nhsync%2Fnetworkhelm-sync/repository"), request.url.parameters, change)
            GitHost.Gitea -> gitHub(request.method.value, path.removePrefix("/api/v1/repos/nhsync/networkhelm-sync"), change)
            GitHost.GitHub -> gitHub(request.method.value, path.removePrefix("/repos/nhsync/networkhelm-sync"), change)
        }
        val json = answer.json.encodeToByteArray()
        val content = if (breaking) halfway(json) else ByteReadChannel(json)
        return respond(content, answer.status, if (host == GitHost.GitLab) headersOf("X-Next-Page", answer.next) else headersOf())
    }

    private fun gitHub(method: String, target: String, change: Map<String, String?>): Answer {
        if (target.startsWith("/git/blobs/")) {
            val blob = files.values.firstOrNull { it.sha == target.removePrefix("/git/blobs/") } ?: return Answer(HttpStatusCode.NotFound, NOT_FOUND)
            return Answer(HttpStatusCode.OK, "{\"sha\":\"${blob.sha}\",\"size\":${blob.bytes.size},\"encoding\":\"base64\",\"content\":\"${Base64.encode(blob.bytes)}\"}")
        }
        val path = target.removePrefix("/contents/")
        val current = files[path]
        val sha = change["sha"]
        return when (method) {
            "GET" -> when {
                current != null -> Answer(HttpStatusCode.OK, contents(path, current))
                files.keys.any { it.startsWith("$path/") } -> Answer(HttpStatusCode.OK, listing(path))
                else -> Answer(HttpStatusCode.NotFound, NOT_FOUND)
            }
            // Gitea makes a file with POST and changes one with PUT
            "POST" -> when {
                host == GitHost.GitHub -> Answer(HttpStatusCode.NotFound, NOT_FOUND)
                current != null -> Answer(HttpStatusCode.UnprocessableEntity, "{\"message\":\"repository file already exists [path: $path]\"}")
                else -> written(HttpStatusCode.Created, path, change)
            }
            "PUT" -> when {
                sha == null && host == GitHost.Gitea -> Answer(HttpStatusCode.UnprocessableEntity, "{\"message\":\"sha is required\"}")
                sha == null && current != null -> Answer(HttpStatusCode.UnprocessableEntity, "{\"message\":\"Invalid request. sha was not supplied.\"}")
                sha == null -> written(HttpStatusCode.Created, path, change)
                current == null -> Answer(HttpStatusCode.NotFound, NOT_FOUND)
                sha != current.sha -> Answer(HttpStatusCode.Conflict, "{\"message\":\"$path does not match $sha\"}")
                else -> written(HttpStatusCode.OK, path, change)
            }
            "DELETE" -> when {
                current == null -> Answer(HttpStatusCode.NotFound, NOT_FOUND)
                sha != current.sha -> Answer(HttpStatusCode.Conflict, "{\"message\":\"$path does not match $sha\"}")
                else -> {
                    files.remove(path)
                    Answer(HttpStatusCode.OK, "{\"commit\":{}}")
                }
            }
            else -> Answer(HttpStatusCode.MethodNotAllowed, NOT_FOUND)
        }
    }

    private fun gitLab(method: String, target: String, parameters: Parameters, change: Map<String, String?>): Answer {
        if (target == "/tree") {
            val folder = parameters["path"].orEmpty()
            val paths = files.keys.filter { it.startsWith("$folder/") }
            val trees = paths.map { it.substringBeforeLast('/') }.filter { it != folder }.distinct()
            val entries = (trees.map { it to "tree" } + paths.map { it to "blob" }).sortedBy { it.first }
            if (entries.isEmpty()) return Answer(HttpStatusCode.NotFound, "{\"message\":\"404 Tree Not Found\"}")
            val page = parameters["page"]?.toInt() ?: 1
            val json = entries.drop((page - 1) * 2).take(2).joinToString(",", "[", "]") { (path, type) ->
                "{\"id\":\"${files[path]?.sha ?: "0"}\",\"name\":\"${path.substringAfterLast('/')}\",\"type\":\"$type\",\"path\":\"$path\",\"mode\":\"100644\"}"
            }
            return Answer(HttpStatusCode.OK, json, if (page * 2 < entries.size) "${page + 1}" else "")
        }
        val path = target.removePrefix("/files/").decodeURLPart()
        val current = files[path]
        return when (method) {
            "GET" -> if (current == null) {
                Answer(HttpStatusCode.NotFound, "{\"message\":\"404 File Not Found\"}")
            } else {
                Answer(
                    HttpStatusCode.OK,
                    "{\"file_path\":\"$path\",\"size\":${current.bytes.size},\"encoding\":\"base64\",\"content\":\"${Base64.encode(current.bytes)}\"," +
                        "\"blob_id\":\"${current.sha}\",\"last_commit_id\":\"${current.commit}\"}",
                )
            }
            "POST" -> if (current != null) Answer(HttpStatusCode.BadRequest, "{\"message\":\"A file with this name already exists\"}") else written(HttpStatusCode.Created, path, change)
            "PUT" -> when {
                current == null -> Answer(HttpStatusCode.BadRequest, "{\"message\":\"A file with this name does not exist\"}")
                change["last_commit_id"]?.let { it != current.commit } == true ->
                    Answer(HttpStatusCode.BadRequest, "{\"message\":\"You are attempting to update a file that has changed since you started editing it.\"}")
                else -> written(HttpStatusCode.OK, path, change)
            }
            "DELETE" -> if (current == null) {
                Answer(HttpStatusCode.BadRequest, "{\"message\":\"A file with this name does not exist\"}")
            } else {
                files.remove(path)
                Answer(HttpStatusCode.NoContent, "")
            }
            else -> Answer(HttpStatusCode.MethodNotAllowed, NOT_FOUND)
        }
    }

    private fun written(status: HttpStatusCode, path: String, change: Map<String, String?>): Answer {
        committed(path, Base64.decode(change["content"].orEmpty()))
        return Answer(status, "{\"content\":{\"path\":\"$path\",\"sha\":\"${files.getValue(path).sha}\"}}")
    }

    private fun contents(path: String, blob: Blob): String {
        val content = if (host == GitHost.GitHub && blob.bytes.size > 4) "" else Base64.encode(blob.bytes).chunked(60).joinToString("\\n")
        return "{\"type\":\"file\",\"name\":\"${path.substringAfterLast('/')}\",\"path\":\"$path\",\"sha\":\"${blob.sha}\",\"size\":${blob.bytes.size}," +
            "\"encoding\":\"${if (content.isEmpty()) "none" else "base64"}\",\"content\":\"$content\"}"
    }

    private fun listing(directory: String): String {
        val names = files.keys.filter { it.startsWith("$directory/") }.map { it.removePrefix("$directory/") }
        val entries = names.filter { '/' !in it }.map { "{\"type\":\"file\",\"name\":\"$it\",\"path\":\"$directory/$it\",\"sha\":\"${files.getValue("$directory/$it").sha}\"}" } +
            names.filter { '/' in it }.map { it.substringBefore('/') }.distinct().map { "{\"type\":\"dir\",\"name\":\"$it\",\"path\":\"$directory/$it\",\"sha\":\"0\"}" }
        return entries.joinToString(",", "[", "]")
    }
}

// the gateway of NetworkHelm the way its routes of synchronisation answer: a token for its login, the files by that token with
// If-Match and If-None-Match on PUT, and a version that a wait gives back as soon as it is another than the one asked about
internal class FakeGateway {
    private val files = HashMap<String, ByteArray>()
    private val version = MutableStateFlow(7L)
    private var broken = false

    fun http() = mockHttp { answer(it) }

    fun service(token: String = GATEWAY_TOKEN) = GatewayService(http(), "https://gateway.example.org:8443", token)

    fun fake(name: String) = Fake(name, service(), { path, bytes -> changed { files[path] = bytes } }, { path -> changed { files.remove(path) } })

    fun breakNext() {
        broken = true
    }

    private fun changed(change: () -> Unit) {
        change()
        version.update { it + 1 }
    }

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        if (path == "/sync/login") {
            val form = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject.mapValues { it.value.jsonPrimitive.content }
            val known = form["user"] == "admin" && form["password"] == "gateway password" && form["code"] == "512743"
            return if (known) respond(GATEWAY_TOKEN) else respond("", HttpStatusCode.Unauthorized)
        }
        if (request.headers["Authorization"] != "Bearer $GATEWAY_TOKEN") return respond("", HttpStatusCode.Unauthorized)
        val breaking = broken.also { broken = false }
        if (breaking && request.method.value != "GET") throw IllegalStateException("connection reset")
        if (path == "/sync/changes") {
            val after = request.url.parameters["after"]?.toLong()
            val next = version.first { it != after }
            return respond("$next")
        }
        if (path == "/sync/files") return respond(files.entries.joinToString(",", "[", "]") { (name, bytes) -> "{\"path\":\"$name\",\"tag\":\"${tag(bytes)}\"}" })
        val name = path.removePrefix("/sync/files/")
        val current = files[name]
        return when (request.method.value) {
            "GET" -> if (current == null) {
                respond("", HttpStatusCode.NotFound)
            } else {
                val content = if (breaking) halfway(current) else ByteReadChannel(current)
                respond(content, HttpStatusCode.OK, headersOf("ETag", "\"${tag(current)}\""))
            }
            "PUT" -> {
                val match = request.headers["If-Match"]
                when {
                    request.headers["If-None-Match"] == "*" && current != null -> respond("", HttpStatusCode.PreconditionFailed)
                    match != null && match != current?.let { "\"${tag(it)}\"" } -> respond("", HttpStatusCode.PreconditionFailed)
                    else -> {
                        val bytes = request.body.toByteArray()
                        changed { files[name] = bytes }
                        respond("", HttpStatusCode.NoContent)
                    }
                }
            }
            "DELETE" -> {
                changed { files.remove(name) }
                respond("", HttpStatusCode.NoContent)
            }
            else -> respond("", HttpStatusCode.MethodNotAllowed)
        }
    }

    private fun tag(bytes: ByteArray) = hex(sha256(bytes))
}
