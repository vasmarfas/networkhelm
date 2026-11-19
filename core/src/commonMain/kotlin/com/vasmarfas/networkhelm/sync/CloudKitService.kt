package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.http.parseQueryString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64

private const val DATABASE = "https://api.apple-cloudkit.com/database/1"
private const val CLOUDKIT_HOST = "api.apple-cloudkit.com"
private const val RECORD_TYPE = "SyncFile"

private val WEB_TOKEN_QUERY = Regex("""ckWebAuthToken=[^&\s,\]]*""")

// the container of the app in CloudKit, its environment and an API token made for it in CloudKit Console, which also sets where the sign-in
// of that token sends the browser back
internal class CloudKitContainer(val id: String, val environment: String, val token: String, val redirect: String)

private const val CONTAINER = "iCloud.com.vasmarfas.networkhelm"

// CloudKit in production takes no loopback address: iOS comes back by the scheme of the container, the rest to the page, which passes the
// web token of the desktop and Android on to their loopback address
internal val CLOUDKIT_APPLE = CloudKitContainer(CONTAINER, "production", "a3a6d5985ecceabd5d6b850d9dbfed28d7681349da61d846d9349c68a802919b", "cloudkit-${CONTAINER.lowercase()}://signin")
internal val CLOUDKIT_PAGE = CloudKitContainer(CONTAINER, "production", "3c6b361d1320558031ea922d74240c13c64d934f7f6d5b4181706265730158f4", SIGN_IN_PAGE)

@Serializable
private class CloudKitField(val value: String = "")

// a record as CloudKit gives it back, or the refusal of it or of the whole request
@Serializable
private class CloudKitRecord(
    val recordName: String = "",
    val recordChangeTag: String = "",
    val fields: Map<String, CloudKitField> = emptyMap(),
    val serverErrorCode: String = "",
    val reason: String = "",
    val redirectURL: String = "",
)

@Serializable
private class CloudKitRecords(val records: List<CloudKitRecord> = emptyList(), val continuationMarker: String = "")

// iCloud through CloudKit Web Services: a file is a record of the type SyncFile in the private database of the user in the container of
// the app, its path in one field and its bytes in another, under 1 MB a record. A write over the change tag read is refused when the
// record has another one by then. The web token of the sign-in is good for one request, and each answer brings the next one, which the
// device keeps: the requests go one at a time
class CloudKitService internal constructor(
    private val http: SyncHttp,
    private val container: CloudKitContainer,
    private var token: String,
    private val keep: suspend (String) -> Unit,
) : SyncService {
    private val lock = Mutex()

    override suspend fun list(): List<Listed> {
        val listed = mutableListOf<Listed>()
        var marker = ""
        do {
            val query = buildJsonObject {
                putJsonObject("query") { put("recordType", RECORD_TYPE) }
                putJsonArray("desiredKeys") { add("path") }
                if (marker.isNotEmpty()) put("continuationMarker", marker)
            }
            val reply = post("records/query", query)
            if (reply.status != 200) throw refused(decoded(reply, CloudKitRecord.serializer()))
            val page = decoded(reply, CloudKitRecords.serializer())
            page.records.mapNotNullTo(listed) { record -> record.fields["path"]?.let { Listed(it.value, record.recordChangeTag) } }
            marker = page.continuationMarker
        } while (marker.isNotEmpty())
        return listed.filter { syncFile(it.path) }
    }

    override suspend fun read(path: String): Stored? {
        val lookup = buildJsonObject { putJsonArray("records") { addJsonObject { put("recordName", name(path)) } } }
        val record = single(post("records/lookup", lookup))
        if (record.serverErrorCode == "NOT_FOUND") return null
        if (record.serverErrorCode.isNotEmpty()) throw refused(record)
        val data = record.fields["data"] ?: throw notApi()
        return Stored(Base64.decode(data.value), record.recordChangeTag)
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        val record = modify("forceReplace", path, bytes, null)
        if (record.serverErrorCode.isNotEmpty()) throw refused(record)
    }

    // a record made meanwhile is EXISTS, another change tag CONFLICT and a deleted record NOT_FOUND
    override suspend fun writeIf(path: String, bytes: ByteArray, over: Stored?): Boolean {
        val record = if (over == null) modify("create", path, bytes, null) else modify("replace", path, bytes, over.tag)
        return when (record.serverErrorCode) {
            "" -> true
            "EXISTS", "CONFLICT", "NOT_FOUND" -> false
            else -> throw refused(record)
        }
    }

    override suspend fun delete(path: String) {
        val record = modify("forceDelete", path, null, null)
        if (record.serverErrorCode.isNotEmpty() && record.serverErrorCode != "NOT_FOUND") throw refused(record)
    }

    private suspend fun modify(operation: String, path: String, bytes: ByteArray?, tag: String?): CloudKitRecord {
        val body = buildJsonObject {
            putJsonArray("operations") {
                addJsonObject {
                    put("operationType", operation)
                    putJsonObject("record") {
                        put("recordName", name(path))
                        put("recordType", RECORD_TYPE)
                        if (tag != null) put("recordChangeTag", tag)
                        if (bytes != null) {
                            putJsonObject("fields") {
                                putJsonObject("path") {
                                    put("value", path)
                                    put("type", "STRING")
                                }
                                putJsonObject("data") {
                                    put("value", Base64.encode(bytes))
                                    put("type", "BYTES")
                                }
                            }
                        }
                    }
                }
            }
        }
        return single(post("records/modify", body))
    }

    // the token of each answer takes the place of the one sent. A request that broke may hold its address in its message, and the address
    // holds the token
    private suspend fun post(operation: String, body: JsonObject): Reply = lock.withLock {
        val sent = token
        val url = "$DATABASE/${container.id}/${container.environment}/private/$operation?ckAPIToken=${container.token.encodeURLParameter()}&ckWebAuthToken=${sent.encodeURLParameter()}"
        val reply = try {
            http.send("POST", url, emptyMap(), outgoing(body.toString().encodeToByteArray(), "application/json"))
        } catch (e: DeviceException) {
            throw DeviceException(e.category, e.message.orEmpty().replace(WEB_TOKEN_QUERY, "ckWebAuthToken=").replace(sent, ""))
        }
        val next = reply.header("X-Apple-CloudKit-Web-Auth-Token") ?: reply.header("X-Apple-CloudKit-Session")
        if (!next.isNullOrEmpty() && next != sent) {
            token = next
            keep(next)
        }
        if (reply.status == 401 || reply.status == 421) throw loginRefused(CLOUDKIT_HOST)
        reply
    }

    // the one record an answer is about; the refusal of the whole request comes as such a record as well
    private fun single(reply: Reply): CloudKitRecord {
        if (reply.status != 200) return decoded(reply, CloudKitRecord.serializer()).takeIf { it.serverErrorCode.isNotEmpty() } ?: throw answered(CLOUDKIT_HOST, "${reply.status}")
        return decoded(reply, CloudKitRecords.serializer()).records.singleOrNull() ?: throw notApi()
    }

    private fun <T> decoded(reply: Reply, serializer: KSerializer<T>): T = try {
        fileJson.decodeFromString(serializer, reply.body.decodeToString())
    } catch (e: IllegalArgumentException) {
        throw notApi()
    }

    private fun name(path: String) = path.replace('/', '-')
}

// the sign-in with an Apple ID: CloudKit answers a request without the web token with the address of its sign-in page, and the page sends
// the browser back to the address set for the API token, with the web token in the query; open shows the page and gives that address
internal suspend fun CloudKitContainer.signIn(http: SyncHttp, open: suspend (String) -> String): String {
    val address = signInPage(http)
    val back = withTimeoutOrNull(SIGN_IN_TIME) { open(address) }
        ?: throw DeviceException(ErrorCategory.Interrupted, "the sign-in was not finished in the browser in time")
    return webToken(parseQueryString(back.substringAfter('?', "").substringBefore('#')))
}

// a device CloudKit sends no browser back to, as a desktop or Android, signs in through the page: the page keeps the port of the loopback
// address of the app, sends the browser on to the sign-in of Apple and passes the web token the browser comes back with to that address
internal suspend fun CloudKitContainer.relayed(http: SyncHttp, open: (String) -> Unit, page: String): String {
    val listener = listen(0)
    try {
        open("$SIGN_IN_PAGE?relay=${listener.port}&to=${signInPage(http).encodeURLParameter()}")
        return webToken(loopbackAnswer(listener, page))
    } finally {
        listener.close()
    }
}

private suspend fun CloudKitContainer.signInPage(http: SyncHttp): String {
    val reply = http.send("GET", "$DATABASE/$id/$environment/private/users/current?ckAPIToken=${token.encodeURLParameter()}")
    val address = try {
        fileJson.decodeFromString(CloudKitRecord.serializer(), reply.body.decodeToString()).redirectURL
    } catch (e: IllegalArgumentException) {
        ""
    }
    if (reply.status != 421 || address.isEmpty()) throw answered(CLOUDKIT_HOST, "${reply.status}")
    return address
}

private fun webToken(answer: Parameters) =
    answer["ckWebAuthToken"] ?: answer["ckSession"] ?: throw DeviceException(ErrorCategory.Api, "the browser came back without the answer of the sign-in")

private fun notApi() = DeviceException(ErrorCategory.Api, "$CLOUDKIT_HOST did not answer as the API of CloudKit")

// CloudKit names the error and says why: QUOTA_EXCEEDED, THROTTLED, BAD_REQUEST and the like
private fun refused(record: CloudKitRecord) = answered(CLOUDKIT_HOST, listOf(record.serverErrorCode, record.reason).filter { it.isNotEmpty() }.joinToString(" "))
