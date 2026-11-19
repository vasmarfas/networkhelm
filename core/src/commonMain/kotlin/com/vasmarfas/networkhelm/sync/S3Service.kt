package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.keenetic.hex
import com.vasmarfas.networkhelm.keenetic.sha256
import com.vasmarfas.networkhelm.snmp.hmac
import com.vasmarfas.networkhelm.upnp.element
import com.vasmarfas.networkhelm.upnp.unescaped
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.http.hostWithPortIfSpecified
import kotlin.time.Clock

private val CONTENTS = Regex("""<Contents>([\s\S]*?)</Contents>""")

// a bucket of S3 addressed by path: Yandex Object Storage, Selectel, MinIO, AWS and the others that speak it. Every request is signed
// with SigV4 for the region of the bucket; the key of the user is best limited to this bucket
class S3Service(
    private val http: SyncHttp,
    endpoint: String,
    private val bucket: String,
    private val region: String,
    private val access: String,
    private val secret: String,
) : SyncService {
    private val endpoint = endpoint.trimEnd('/')
    private val host = Url(endpoint).host

    override suspend fun list(): List<Listed> {
        val listed = mutableListOf<Listed>()
        var next: String? = null
        do {
            val query = listOfNotNull(next?.let { "continuation-token=${it.encodeURLParameter()}" }, "list-type=2", "prefix=${"$SYNC_FOLDER/".encodeURLParameter()}")
            val reply = send("GET", "", query.joinToString("&"))
            if (reply.status != 200) throw failed(reply)
            val xml = reply.body.decodeToString()
            for (contents in CONTENTS.findAll(xml)) {
                val key = element(contents.value, "Key")?.let(::unescaped) ?: continue
                listed += Listed(key.removePrefix("$SYNC_FOLDER/"), unescaped(element(contents.value, "ETag").orEmpty()))
            }
            next = element(xml, "NextContinuationToken")?.takeIf { element(xml, "IsTruncated") == "true" }?.let(::unescaped)
        } while (next != null)
        return listed.filter { syncFile(it.path) }
    }

    override suspend fun read(path: String): Stored? {
        val reply = send("GET", "$SYNC_FOLDER/$path")
        return when (reply.status) {
            200 -> Stored(reply.body, reply.header("ETag").orEmpty())
            404 -> null
            else -> throw failed(reply)
        }
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        val reply = send("PUT", "$SYNC_FOLDER/$path", body = bytes)
        if (reply.status != 200) throw failed(reply)
    }

    // AWS answers 409 when two conditional writes of one key meet, and NoSuchKey to If-Match on a key that is gone
    override suspend fun writeIf(path: String, bytes: ByteArray, over: Stored?): Boolean {
        val reply = send("PUT", "$SYNC_FOLDER/$path", headers = if (over == null) mapOf("If-None-Match" to "*") else mapOf("If-Match" to over.tag), body = bytes)
        return when {
            reply.status == 200 -> true
            reply.status == 412 || reply.status == 409 || code(reply) == "NoSuchKey" -> false
            else -> throw failed(reply)
        }
    }

    override suspend fun delete(path: String) {
        val reply = send("DELETE", "$SYNC_FOLDER/$path")
        if (reply.status != 204 && reply.status != 200) throw failed(reply)
    }

    private suspend fun send(method: String, key: String, query: String = "", headers: Map<String, String> = emptyMap(), body: ByteArray? = null): Reply {
        val url = "$endpoint/$bucket" + (if (key.isEmpty()) "" else "/$key") + (if (query.isEmpty()) "" else "?$query")
        val target = Url(url)
        val payload = hex(sha256(body ?: ByteArray(0)))
        val time = Clock.System.now().toString().take(19).filter { it != '-' && it != ':' } + "Z"
        val signed = mapOf("host" to target.hostWithPortIfSpecified, "x-amz-content-sha256" to payload, "x-amz-date" to time)
        val authorization = sigV4(method, target.encodedPath, query, signed, payload, time, region, access, secret)
        val sent = headers + mapOf("x-amz-content-sha256" to payload, "x-amz-date" to time, "Authorization" to authorization)
        return http.send(method, url, sent, body?.let { outgoing(it, "application/octet-stream") })
    }

    private fun code(reply: Reply) = element(reply.body.decodeToString(), "Code")

    // the code of S3 tells more than the status: SignatureDoesNotMatch, AccessDenied, NoSuchBucket
    private fun failed(reply: Reply) = answered(host, listOfNotNull("${reply.status}", code(reply)).joinToString(" "))
}

// the Authorization of AWS Signature Version 4 for S3: headers are the signed ones by lower-case name, payload the hex of the SHA-256 of
// the body, time as 20130524T000000Z
internal fun sigV4(method: String, path: String, query: String, headers: Map<String, String>, payload: String, time: String, region: String, access: String, secret: String): String {
    val names = headers.keys.sorted()
    val canonical = listOf(method, path, query, names.joinToString("") { "$it:${headers.getValue(it).trim()}\n" }, names.joinToString(";"), payload)
    val scope = "${time.take(8)}/$region/s3/aws4_request"
    val signing = listOf(time.take(8), region, "s3", "aws4_request").fold("AWS4$secret".encodeToByteArray()) { key, part -> hmac(::sha256, key, part.encodeToByteArray()) }
    val text = "AWS4-HMAC-SHA256\n$time\n$scope\n${hex(sha256(canonical.joinToString("\n").encodeToByteArray()))}"
    return "AWS4-HMAC-SHA256 Credential=$access/$scope, SignedHeaders=${names.joinToString(";")}, Signature=${hex(hmac(::sha256, signing, text.encodeToByteArray()))}"
}
