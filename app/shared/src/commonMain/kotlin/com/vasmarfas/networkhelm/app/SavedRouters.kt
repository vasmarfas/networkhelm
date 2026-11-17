package com.vasmarfas.networkhelm.app

import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.snmp.AuthProtocol
import com.vasmarfas.networkhelm.snmp.PrivProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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

class SavedRouters(private val storage: Storage) {
    private val list = MutableStateFlow<List<SavedRouter>>(emptyList())
    val routers: StateFlow<List<SavedRouter>> = list.asStateFlow()

    var failure: Exception? = null
        private set

    suspend fun load() {
        val text = storage.read(FILE) ?: return
        try {
            list.value = json.decodeFromString(serializer, text)
            failure = null
        } catch (e: IllegalArgumentException) {
            failure = e
        }
    }

    suspend fun save(router: SavedRouter) = update { routers -> routers.filterNot { it.address == router.address && it.user == router.user } + router }

    suspend fun remove(router: SavedRouter) = update { routers -> routers - router }

    private suspend fun update(change: (List<SavedRouter>) -> List<SavedRouter>) {
        // a browser may have changed the list through the gateway of the desktop app since it was read
        load()
        failure?.let { throw IllegalStateException("the saved list could not be read, not overwriting it", it) }
        val changed = change(list.value).sortedWith(compareBy({ it.group }, { it.address }))
        storage.write(FILE, json.encodeToString(serializer, changed))
        list.value = changed
    }

    companion object {
        const val FILE = "routers.json"
        private val serializer = ListSerializer(SavedRouter.serializer())
        private val json = Json { prettyPrint = true }
    }
}
