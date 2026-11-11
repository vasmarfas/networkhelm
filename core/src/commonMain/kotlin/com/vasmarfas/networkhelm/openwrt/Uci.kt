package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// the cfgXXXXXX name of an anonymous section changes with its contents and the section count, so it is never kept
class UciSection(
    val name: String,
    val type: String,
    val anonymous: Boolean,
    val options: Map<String, String>,
    val lists: Map<String, List<String>>,
)

class UciChange(val config: String, val operation: String, val section: String, val option: String?, val value: String?)

interface Uci {
    suspend fun configs(): List<String>

    suspend fun sections(config: String): List<UciSection>

    suspend fun add(config: String, type: String, name: String? = null, options: Map<String, String> = emptyMap(), lists: Map<String, List<String>> = emptyMap()): String

    suspend fun set(config: String, section: String, options: Map<String, String> = emptyMap(), lists: Map<String, List<String>> = emptyMap())

    suspend fun delete(config: String, section: String, options: List<String>? = null)

    suspend fun order(config: String, sections: List<String>)

    suspend fun changes(): List<UciChange>

    suspend fun revert(config: String)

    suspend fun apply(rollbackSeconds: Int)

    suspend fun confirm()

    suspend fun rollback()
}

class RpcdUci(private val ubus: Ubus, private val open: (suspend () -> String)? = null) : Uci {
    private val lock = Mutex()
    private var session: String? = null

    override suspend fun configs(): List<String> = call("configs")["configs"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }

    override suspend fun sections(config: String): List<UciSection> {
        val values = try {
            call("get", "config" to JsonPrimitive(config))
        } catch (e: UbusException) {
            // to a login over /ubus rpcd denies every config its ACL does not name, one that does not exist as well
            if (open != null || e.status != UbusStatus.PERMISSION_DENIED) throw e
            throw UbusException(UbusStatus.NOT_FOUND, "the login may not read the config $config")
        }["values"]?.jsonObject.orEmpty()
        return values.values.map { it.jsonObject }.sortedBy { it[".index"]?.jsonPrimitive?.intOrNull }.map { section ->
            val own = section.filterKeys { !it.startsWith(".") }
            UciSection(
                name = section.text(".name"),
                type = section.text(".type"),
                anonymous = section[".anonymous"]?.jsonPrimitive?.contentOrNull == "true",
                options = own.filterValues { it is JsonPrimitive }.mapValues { it.value.jsonPrimitive.content },
                lists = own.filterValues { it is JsonArray }.mapValues { (_, list) -> list.jsonArray.map { it.jsonPrimitive.content } },
            )
        }
    }

    override suspend fun add(config: String, type: String, name: String?, options: Map<String, String>, lists: Map<String, List<String>>): String {
        val args = listOfNotNull("config" to JsonPrimitive(config), "type" to JsonPrimitive(type), name?.let { "name" to JsonPrimitive(it) }, "values" to values(options, lists))
        return call("add", *args.toTypedArray())["section"]?.jsonPrimitive?.contentOrNull
            ?: throw UbusException(UbusStatus.UNKNOWN_ERROR, "uci add answered without a section")
    }

    override suspend fun set(config: String, section: String, options: Map<String, String>, lists: Map<String, List<String>>) {
        call("set", "config" to JsonPrimitive(config), "section" to JsonPrimitive(section), "values" to values(options, lists))
    }

    override suspend fun delete(config: String, section: String, options: List<String>?) {
        val names = options?.let { "options" to JsonArray(it.map(::JsonPrimitive)) }
        call("delete", *listOfNotNull("config" to JsonPrimitive(config), "section" to JsonPrimitive(section), names).toTypedArray())
    }

    override suspend fun order(config: String, sections: List<String>) {
        call("order", "config" to JsonPrimitive(config), "sections" to JsonArray(sections.map(::JsonPrimitive)))
    }

    override suspend fun changes(): List<UciChange> =
        call("changes")["changes"]?.jsonObject.orEmpty().flatMap { (config, edits) ->
            edits.jsonArray.map { edit ->
                val words = edit.jsonArray.map { (it as? JsonPrimitive)?.contentOrNull }
                UciChange(config, words.getOrNull(0).orEmpty(), words.getOrNull(1).orEmpty(), words.getOrNull(2), words.getOrNull(3))
            }
        }

    override suspend fun revert(config: String) {
        call("revert", "config" to JsonPrimitive(config))
    }

    override suspend fun apply(rollbackSeconds: Int) {
        call("apply", "rollback" to JsonPrimitive(true), "timeout" to JsonPrimitive(rollbackSeconds))
    }

    override suspend fun confirm() {
        call("confirm")
    }

    override suspend fun rollback() {
        call("rollback")
    }

    private suspend fun call(method: String, vararg args: Pair<String, JsonElement>): JsonObject {
        val open = open ?: return ubus.call("uci", method, JsonObject(args.toMap()))
        suspend fun send(session: String) = ubus.call("uci", method, JsonObject(args.toMap() + ("ubus_rpc_session" to JsonPrimitive(session))))
        val used = session(open, null)
        return try {
            send(used)
        } catch (e: UbusException) {
            if (e.status != UbusStatus.PERMISSION_DENIED || known(used)) throw e
            send(session(open, used))
        }
    }

    private suspend fun session(open: suspend () -> String, lapsed: String?): String = lock.withLock {
        session?.takeIf { it != lapsed } ?: open().also { session = it }
    }

    private suspend fun known(session: String) = try {
        ubus.call("session", "access", JsonObject(mapOf("ubus_rpc_session" to JsonPrimitive(session))))
        true
    } catch (e: UbusException) {
        if (e.status != UbusStatus.NOT_FOUND) throw e
        false
    }

    private fun values(options: Map<String, String>, lists: Map<String, List<String>>) =
        JsonObject(options.mapValues { JsonPrimitive(it.value) } + lists.mapValues { (_, list) -> JsonArray(list.map(::JsonPrimitive)) })

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}

suspend fun uciSession(ubus: Ubus, timeoutSeconds: Int = 3600): String {
    val session = ubus.call("session", "create", JsonObject(mapOf("timeout" to JsonPrimitive(timeoutSeconds))))["ubus_rpc_session"]
        ?.jsonPrimitive?.contentOrNull ?: throw UbusException(UbusStatus.UNKNOWN_ERROR, "session create answered without a session")
    val uci = JsonArray(listOf("read", "write").map { JsonArray(listOf(JsonPrimitive("*"), JsonPrimitive(it))) })
    ubus.call("session", "grant", JsonObject(mapOf("ubus_rpc_session" to JsonPrimitive(session), "scope" to JsonPrimitive("uci"), "objects" to uci)))
    return session
}

suspend fun peerInterfaces(ssh: SshSession, ubus: Ubus): Set<String> {
    val route = ssh.exec("set -- \$SSH_CONNECTION; ip route get \"\$1\" from \"\$3\"").output
    val device = Regex("""\bdev (\S+)""").find(route)?.groupValues?.get(1) ?: return emptySet()
    val interfaces = ubus.call("network.interface", "dump")["interface"]?.jsonArray.orEmpty().map { it.jsonObject }
    return interfaces.filter { iface -> listOf("device", "l3_device").any { iface[it]?.jsonPrimitive?.contentOrNull == device } }
        .mapNotNull { it["interface"]?.jsonPrimitive?.contentOrNull }
        .toSet()
}

fun touchesConnection(changes: List<UciChange>, interfaces: Set<String>) = changes.any { it.config == "network" && it.section in interfaces }
