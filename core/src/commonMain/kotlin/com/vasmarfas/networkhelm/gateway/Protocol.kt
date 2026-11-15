package com.vasmarfas.networkhelm.gateway

import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.openwrt.OpenWrtLogin
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.session.Login
import com.vasmarfas.networkhelm.session.TextStore
import com.vasmarfas.networkhelm.transport.Base64Bytes
import com.vasmarfas.networkhelm.transport.TrustKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// one JSON message per WebSocket text frame; an Ask gets one Value, or Next frames and then Done
// bytes of a terminal or a file travel as base64 inside the JSON
const val GATEWAY_PROTOCOL = 3

internal val gatewayJson = Json { ignoreUnknownKeys = true }

internal val countersSerializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))

interface Wire {
    suspend fun send(text: String)

    suspend fun receive(): String?

    fun close()
}

@Serializable
sealed interface ToGateway {
    @Serializable
    @SerialName("ask")
    class Ask(val id: Long, val request: Request) : ToGateway

    @Serializable
    @SerialName("cancel")
    class Cancel(val id: Long) : ToGateway

    @Serializable
    @SerialName("answer")
    class Answer(val question: Long, val accepted: Boolean) : ToGateway

    @Serializable
    @SerialName("input")
    class Input(val channel: Long, @Serializable(with = Base64Bytes::class) val bytes: ByteArray) : ToGateway

    @Serializable
    @SerialName("resize")
    class Resize(val channel: Long, val columns: Int, val rows: Int) : ToGateway

    @Serializable
    @SerialName("close")
    class Close(val session: Long) : ToGateway
}

@Serializable
sealed interface FromGateway {
    @Serializable
    @SerialName("hello")
    class Hello(val protocol: Int, val offers: Offers = Offers()) : FromGateway

    @Serializable
    @SerialName("value")
    class Value(val id: Long, val value: JsonElement) : FromGateway

    @Serializable
    @SerialName("next")
    class Next(val id: Long, val value: JsonElement) : FromGateway

    @Serializable
    @SerialName("done")
    class Done(val id: Long) : FromGateway

    @Serializable
    @SerialName("failed")
    class Failed(val id: Long, val category: ErrorCategory, val message: String) : FromGateway

    @Serializable
    @SerialName("question")
    class Question(val id: Long, val ask: Long, val host: String, val port: Int, val kind: TrustKind, val fingerprint: String, val previous: String?) : FromGateway
}

internal fun FromGateway.Failed.exception() = DeviceException(category, message)

@Serializable
sealed interface Request {
    @Serializable
    @SerialName("connect-routeros")
    class ConnectRouterOs(val login: RouterOsLogin) : Request

    @Serializable
    @SerialName("connect-openwrt")
    class ConnectOpenWrt(val login: OpenWrtLogin) : Request

    @Serializable
    @SerialName("connect")
    class Connect(val login: Login) : Request

    @Serializable
    @SerialName("connect-native")
    class ConnectNative(val login: NativeLogin) : Request

    @Serializable
    @SerialName("connect-mac-telnet")
    class ConnectMacTelnet(val login: MacTelnetLogin) : Request

    @Serializable
    @SerialName("neighbors")
    data object Neighbors : Request

    @Serializable
    @SerialName("refresh-neighbors")
    data object RefreshNeighbors : Request

    @Serializable
    @SerialName("scan")
    class Scan(val addresses: List<String>) : Request

    @Serializable
    @SerialName("describe")
    class Describe(val session: Long, val path: MenuPath) : Request

    @Serializable
    @SerialName("outline")
    class Outline(val session: Long, val path: MenuPath) : Request

    @Serializable
    @SerialName("forget-structure")
    class ForgetStructure(val session: Long) : Request

    @Serializable
    @SerialName("watch")
    class Watch(val session: Long, val path: MenuPath, val filter: Filter? = null, val properties: List<String>? = null) : Request

    @Serializable
    @SerialName("get")
    class Get(val session: Long, val path: MenuPath, val id: String?, val properties: List<String>? = null) : Request

    @Serializable
    @SerialName("count")
    class Count(val session: Long, val path: MenuPath, val filter: Filter? = null) : Request

    @Serializable
    @SerialName("add")
    class Add(val session: Long, val path: MenuPath, val values: Map<String, String>) : Request

    @Serializable
    @SerialName("set")
    class Change(val session: Long, val path: MenuPath, val id: String?, val changes: Map<String, String?>) : Request

    @Serializable
    @SerialName("remove")
    class Remove(val session: Long, val path: MenuPath, val ids: List<String>) : Request

    @Serializable
    @SerialName("move")
    class Move(val session: Long, val path: MenuPath, val ids: List<String>, val before: String?) : Request

    @Serializable
    @SerialName("run")
    class Run(val session: Long, val path: MenuPath, val command: String, val arguments: Map<String, String>) : Request

    @Serializable
    @SerialName("export")
    class Export(val session: Long, val path: MenuPath) : Request

    @Serializable
    @SerialName("counters")
    class Counters(val session: Long, val path: MenuPath, val properties: List<String>, val filter: Filter? = null) : Request

    @Serializable
    @SerialName("link")
    class Link(val session: Long) : Request

    @Serializable
    @SerialName("packs")
    class Packs(val session: Long) : Request

    @Serializable
    @SerialName("native-menu")
    class Native(val session: Long) : Request

    @Serializable
    @SerialName("words")
    class Words(val session: Long) : Request

    @Serializable
    @SerialName("safe-mode")
    class SafeModeActive(val session: Long) : Request

    @Serializable
    @SerialName("safe-mode-actions")
    class SafeModeActions(val session: Long) : Request

    @Serializable
    @SerialName("safe-mode-enter")
    class EnterSafeMode(val session: Long) : Request

    @Serializable
    @SerialName("safe-mode-commit")
    class CommitSafeMode(val session: Long) : Request

    @Serializable
    @SerialName("safe-mode-rollback")
    class RollbackSafeMode(val session: Long) : Request

    @Serializable
    @SerialName("safe-mode-stranded")
    class SafeModeStranded(val session: Long) : Request

    @Serializable
    @SerialName("safe-mode-settle")
    class SettleSafeMode(val session: Long, val keep: Boolean) : Request

    @Serializable
    @SerialName("exec")
    class Exec(val session: Long, val command: String, val input: String? = null) : Request

    @Serializable
    @SerialName("shell")
    class Shell(val session: Long, val term: String, val columns: Int, val rows: Int) : Request

    @Serializable
    @SerialName("ssh-reachable")
    class SshReachable(val session: Long, val fresh: Boolean) : Request

    @Serializable
    @SerialName("sftp-list")
    class SftpList(val session: Long, val path: String, val own: Boolean = false) : Request

    @Serializable
    @SerialName("sftp-read")
    class SftpRead(val session: Long, val path: String, val own: Boolean = false) : Request

    @Serializable
    @SerialName("sftp-write")
    class SftpWrite(val session: Long, val path: String, @Serializable(with = Base64Bytes::class) val bytes: ByteArray, val own: Boolean = false) : Request

    @Serializable
    @SerialName("sftp-remove")
    class SftpRemove(val session: Long, val path: String, val own: Boolean = false) : Request

    @Serializable
    @SerialName("sftp-rename")
    class SftpRename(val session: Long, val from: String, val to: String, val own: Boolean = false) : Request

    @Serializable
    @SerialName("sftp-mkdir")
    class SftpMkdir(val session: Long, val path: String, val own: Boolean = false) : Request

    @Serializable
    @SerialName("read-document")
    class ReadDocument(val name: String) : Request

    @Serializable
    @SerialName("write-document")
    class WriteDocument(val name: String, val text: String) : Request

    @Serializable
    @SerialName("read-secret")
    class ReadSecret(val key: String) : Request

    @Serializable
    @SerialName("write-secret")
    class WriteSecret(val key: String, val value: String) : Request

    @Serializable
    @SerialName("delete-secret")
    class DeleteSecret(val key: String) : Request
}

interface GatewayStore : TextStore {
    suspend fun secret(key: String): String?

    suspend fun keep(key: String, value: String)

    suspend fun forget(key: String)
}

@Serializable
class Opened(
    val session: Long,
    val device: DeviceInfo,
    val capabilities: Capabilities,
    val safeMode: Boolean,
    val ssh: Boolean,
    val fileStore: Boolean = false,
    val canForward: Boolean = true,
    val fileChannel: Boolean = false,
)

@Serializable
class Offers(
    val native: Boolean = false,
    val interfaces: List<String> = emptyList(),
    val neighbors: Boolean = false,
    val scan: Boolean = false,
)

@Serializable
class NativeLogin(val host: String, val port: Int, val user: String, val password: String, val encoding: ApiEncoding = ApiEncoding.Utf8)

@Serializable
class MacTelnetLogin(val mac: String, val user: String, val password: String, val interfaceName: String?, val encoding: ApiEncoding = ApiEncoding.Utf8)
