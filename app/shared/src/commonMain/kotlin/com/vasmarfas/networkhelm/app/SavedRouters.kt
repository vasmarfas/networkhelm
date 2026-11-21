package com.vasmarfas.networkhelm.app

import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.snmp.AuthProtocol
import com.vasmarfas.networkhelm.snmp.PrivProtocol
import com.vasmarfas.networkhelm.sync.FileRecords
import com.vasmarfas.networkhelm.sync.HlcClock
import com.vasmarfas.networkhelm.sync.Record
import com.vasmarfas.networkhelm.sync.recordId
import com.vasmarfas.networkhelm.sync.recordJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Serializable
data class SavedRouter(
    val address: String,
    val user: String,
    val protocol: Access = Access.ApiSsl,
    val group: String = "",
    val comment: String = "",
    val encoding: ApiEncoding = ApiEncoding.Utf8,
    val agent: Boolean = false,
    val via: String = "",
    val snmpAuth: AuthProtocol = AuthProtocol.Sha1,
    val snmpPriv: PrivProtocol = PrivProtocol.Aes,
    // the address and port of SSH of a Keenetic, for its command line
    val ssh: String = "",
)

class SavedRouters(private val storage: Storage, clock: HlcClock = HlcClock()) {
    private val list = MutableStateFlow<List<SavedRouter>>(emptyList())
    val routers: StateFlow<List<SavedRouter>> = list.asStateFlow()
    val records = FileRecords(storage, FILE, "router", clock)

    // an edit of the user and the routers of other devices go into the file one after the other
    private val lock = Mutex()

    var failure: Exception? = null
        private set

    suspend fun load() = lock.withLock { read() }

    suspend fun save(router: SavedRouter) = update { routers -> routers.filterNot { it.address == router.address && it.user == router.user } + router }

    suspend fun remove(router: SavedRouter) = update { routers -> routers - router }

    // the routers merged with those of the other devices of the vault
    suspend fun apply(merged: List<Record>) = lock.withLock {
        read()
        failure?.let { throw IllegalStateException("the saved list could not be read, not overwriting it", it) }
        records.put(merged, SavedRouter.serializer(), ::write)
        read()
    }

    private suspend fun read() = records.match {
        storage.read(FILE)?.let { text ->
            try {
                list.value = json.decodeFromString(serializer, text)
                failure = null
            } catch (e: IllegalArgumentException) {
                failure = e
                return@match null
            }
        }
        entries(list.value)
    }

    private suspend fun update(change: (List<SavedRouter>) -> List<SavedRouter>) = lock.withLock {
        // a browser may have changed the list through the gateway of the desktop app since it was read
        read()
        failure?.let { throw IllegalStateException("the saved list could not be read, not overwriting it", it) }
        write(change(list.value))
        // the records take the change from the file, as they take the change of any other copy
        read()
    }

    private suspend fun write(routers: List<SavedRouter>) {
        val sorted = routers.sortedWith(compareBy({ it.group }, { it.address }))
        storage.write(FILE, json.encodeToString(serializer, sorted))
        list.value = sorted
    }

    companion object {
        const val FILE = "routers.json"
        private val serializer = ListSerializer(SavedRouter.serializer())
        private val json = Json { prettyPrint = true }

        // a router is its address and user, as the list and the passwords key it
        private fun entries(routers: List<SavedRouter>) =
            routers.distinctBy { it.address to it.user }.associate { recordId(it.address, it.user) to recordJson.encodeToJsonElement(SavedRouter.serializer(), it).jsonObject }
    }
}
