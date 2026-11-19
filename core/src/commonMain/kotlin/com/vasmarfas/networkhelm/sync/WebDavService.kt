package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.upnp.element
import com.vasmarfas.networkhelm.upnp.unescaped
import io.ktor.http.Url
import kotlin.io.encoding.Base64

private val PROPFIND = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getetag/>" +
    "<d:getlastmodified/><d:getcontentlength/></d:prop></d:propfind>").encodeToByteArray()

private val RESPONSE = Regex("""<(?:[\w-]+:)?response[\s>][\s\S]*?</(?:[\w-]+:)?response>""")

private val COLLECTION = Regex("""<(?:[\w-]+:)?collection\b""")

// a WebDAV server: Nextcloud, ownCloud, Synology, Apache, and Yandex Disk and Cloud Mail.ru with a password of an app. Base is the
// address of the folder that holds networkhelm-sync, as the server names it
class WebDavService(private val http: SyncHttp, base: String, user: String, password: String) : SyncService {
    private val root = base.trimEnd('/') + "/$SYNC_FOLDER/"
    private val host = Url(base).host
    private val login = "Basic " + Base64.encode("$user:$password".encodeToByteArray())

    override suspend fun list() = listOf("", "devices/", "state/").flatMap { directory ->
        val reply = send("PROPFIND", root + directory, mapOf("Depth" to "1"), outgoing(PROPFIND, "application/xml; charset=utf-8"))
        when (reply.status) {
            207 -> files(reply.body.decodeToString()).map { (name, tag) -> Listed(directory + name, tag) }
            404 -> emptyList()
            else -> throw answered(host, "${reply.status}")
        }
    }.filter { syncFile(it.path) }

    override suspend fun read(path: String): Stored? {
        val reply = send("GET", root + path)
        return when (reply.status) {
            200 -> Stored(reply.body, strong(reply.header("ETag").orEmpty()))
            404 -> null
            else -> throw answered(host, "${reply.status}")
        }
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        val reply = put(path, bytes, emptyMap())
        if (reply.status !in 200..299) throw answered(host, "${reply.status}")
    }

    override suspend fun writeIf(path: String, bytes: ByteArray, over: Stored?): Boolean {
        val reply = put(path, bytes, if (over == null) mapOf("If-None-Match" to "*") else mapOf("If-Match" to over.tag))
        if (reply.status == 412) return false
        if (reply.status !in 200..299) throw answered(host, "${reply.status}")
        return true
    }

    override suspend fun delete(path: String) {
        val reply = send("DELETE", root + path)
        if (reply.status != 404 && reply.status !in 200..299) throw answered(host, "${reply.status}")
    }

    // a collection missing on the way is made once: a new vault starts without its folders
    private suspend fun put(path: String, bytes: ByteArray, condition: Map<String, String>): Reply {
        val reply = send("PUT", root + path, condition, outgoing(bytes, "application/octet-stream"))
        if (reply.status != 409 && reply.status != 404) return reply
        collection(root)
        if ('/' in path) collection(root + path.substringBeforeLast('/') + "/")
        return send("PUT", root + path, condition, outgoing(bytes, "application/octet-stream"))
    }

    private suspend fun collection(url: String) {
        val reply = send("MKCOL", url)
        if (reply.status != 201 && reply.status != 405) throw answered(host, "${reply.status}")
    }

    private suspend fun send(method: String, url: String, headers: Map<String, String> = emptyMap(), body: Outgoing? = null): Reply {
        val reply = http.send(method, url, headers + ("Authorization" to login), body)
        if (reply.status == 401) throw loginRefused(host)
        return reply
    }
}

// the files a PROPFIND of depth 1 names, by name and tag; the collection asked about and those in it are left out
private fun files(xml: String) = RESPONSE.findAll(xml).mapNotNull { match ->
    val name = element(match.value, "href")?.trim()?.trimEnd('/')?.substringAfterLast('/')
    if (name == null || COLLECTION.containsMatchIn(match.value)) return@mapNotNull null
    val tag = element(match.value, "getetag")?.takeIf { it.isNotBlank() }?.let(::strong)
    name to (tag ?: listOf("getlastmodified", "getcontentlength").joinToString(" ") { element(match.value, it).orEmpty() })
}.toList()

// Apache tags a file changed within the last second weakly and gives it the same value, strong, once the second has passed
private fun strong(tag: String) = unescaped(tag.trim()).removePrefix("W/")
