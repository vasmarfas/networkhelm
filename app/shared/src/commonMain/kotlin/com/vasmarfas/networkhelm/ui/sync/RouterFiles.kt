package com.vasmarfas.networkhelm.ui.sync

import com.vasmarfas.networkhelm.app.Storage
import com.vasmarfas.networkhelm.session.keep
import com.vasmarfas.networkhelm.sync.FileRecords
import com.vasmarfas.networkhelm.sync.HlcClock
import com.vasmarfas.networkhelm.sync.Record
import com.vasmarfas.networkhelm.sync.recordId
import com.vasmarfas.networkhelm.sync.recordJson
import com.vasmarfas.networkhelm.ui.main.SavedTunnel
import com.vasmarfas.networkhelm.ui.main.SavedWindow
import com.vasmarfas.networkhelm.ui.main.SavedWorkspaces
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

// the named workspaces and the tunnels of a router lie in files of its own, named by the key its tab gives it. The storage cannot list
// its files, so the routers whose tabs opened are listed; a tab and the records of other devices change these files under one lock
class RouterFiles(private val storage: Storage) {
    val lock = Mutex()
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // a workspace or a tunnel a tab kept, for synchronisation to take with a round soon
    val edits: SharedFlow<Unit> = changed

    suspend fun <T> change(block: suspend () -> T): T = lock.withLock { block() }.also { changed.tryEmit(Unit) }

    suspend fun opened(router: String) = lock.withLock {
        val listed = listed()
        if (router !in listed) storage.keep(LIST, JSON.encodeToString(KEYS, (listed + router).sorted()))
    }

    internal suspend fun listed(): List<String> = storage.read(LIST)?.let { decoded(KEYS, it) }.orEmpty()
}

@Serializable
private class WorkspaceData(val router: String, val view: String, val name: String, val windows: List<SavedWindow>)

@Serializable
private class TunnelData(val router: String, val localPort: Int, val host: String, val port: Int)

// the workspaces and tunnels of the routers as records: a workspace by its router, view and name, a tunnel by its router and the three
// numbers it is. Which workspace a device shows stays on it. A record this version cannot put into a file, and the records of a file that
// does not read, keep what they hold until a tab or the records of other devices write the file whole
internal class RouterRecords(private val files: RouterFiles, private val storage: Storage, clock: HlcClock) {
    private val workspaceRecords = FileRecords(storage, "workspaces.json", "workspace", clock)
    private val tunnelRecords = FileRecords(storage, "tunnels.json", "tunnel", clock)

    suspend fun workspaces(): List<Record> {
        matchWorkspaces()
        return workspaceRecords.take()
    }

    suspend fun tunnels(): List<Record> {
        matchTunnels()
        return tunnelRecords.take()
    }

    // each file that held a workspace or takes one is written again, with the workspace its device shows
    suspend fun applyWorkspaces(merged: List<Record>) = files.lock.withLock {
        matchWorkspaces()
        val held = workspaceRecords.all.filterNot { it.deleted }.mapNotNull { decoded(WorkspaceData.serializer(), it) }
        workspaceRecords.put(merged, WorkspaceData.serializer()) { live ->
            val came = live.filter { placed(it.router, it.view) }
            for ((router, view) in (held + came).map { it.router to it.view }.toSet()) {
                val file = workspacesFile(router, view)
                val shown = storage.read(file)?.let { decoded(SavedWorkspaces.serializer(), it) }?.current.orEmpty()
                val layouts = came.filter { it.router == router && it.view == view }.associate { it.name to it.windows }
                storage.write(file, JSON.encodeToString(SavedWorkspaces.serializer(), SavedWorkspaces(shown, layouts)))
            }
        }
    }

    // the tunnels of a router keep their order, those from elsewhere come after them
    suspend fun applyTunnels(merged: List<Record>) = files.lock.withLock {
        matchTunnels()
        val held = tunnelRecords.all.filterNot { it.deleted }.mapNotNull { decoded(TunnelData.serializer(), it) }
        tunnelRecords.put(merged, TunnelData.serializer()) { live ->
            val came = live.filter { placed(it.router) }
            for (router in (held + came).map { it.router }.toSet()) {
                val file = tunnelsFile(router)
                val stored = storage.read(file)?.let { decoded(TUNNELS, it) }.orEmpty()
                val kept = came.filter { it.router == router }.map { SavedTunnel(it.localPort, it.host, it.port) }
                storage.write(file, JSON.encodeToString(TUNNELS, stored.filter { it in kept } + kept.filterNot { it in stored }))
            }
        }
    }

    private suspend fun matchWorkspaces() = workspaceRecords.match {
        val stored = workspaceRecords.stored().filterNot { it.deleted }
        val unread = HashSet<String>()
        val read = HashMap<String, JsonObject>()
        for (router in routers(stored)) for (view in VIEWS) {
            val text = storage.read(workspacesFile(router, view)) ?: continue
            val saved = decoded(SavedWorkspaces.serializer(), text)
            if (saved == null) unread += workspacesFile(router, view)
            saved?.layouts?.forEach { (name, windows) -> read[recordId(router, view, name)] = entry(WorkspaceData.serializer(), WorkspaceData(router, view, name, windows)) }
        }
        stored.filter { record -> decoded(WorkspaceData.serializer(), record).let { it == null || !placed(it.router, it.view) || workspacesFile(it.router, it.view) in unread } }
            .forEach { read[it.id] = it.data }
        read
    }

    private suspend fun matchTunnels() = tunnelRecords.match {
        val stored = tunnelRecords.stored().filterNot { it.deleted }
        val unread = HashSet<String>()
        val read = HashMap<String, JsonObject>()
        for (router in routers(stored)) {
            val text = storage.read(tunnelsFile(router)) ?: continue
            val saved = decoded(TUNNELS, text)
            if (saved == null) unread += router
            saved?.forEach { read[tunnelId(router, it)] = entry(TunnelData.serializer(), TunnelData(router, it.localPort, it.host, it.port)) }
        }
        stored.filter { record -> decoded(TunnelData.serializer(), record).let { it == null || !placed(it.router) || it.router in unread } }
            .forEach { read[it.id] = it.data }
        read
    }

    // the routers listed as their tabs opened here, and those whose records came from other devices
    private suspend fun routers(stored: List<Record>): Set<String> =
        files.listed().toSet() + stored.mapNotNull { record -> (record.data["router"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { placed(it) } }

    private fun <T> entry(serializer: KSerializer<T>, value: T) = recordJson.encodeToJsonElement(serializer, value).jsonObject
}

// a key of a router as its tab makes it, and a view the files know: anything else would name another file
private fun placed(router: String, view: String = "") = router.isNotEmpty() && router.all { it.isLetterOrDigit() || it in ".-_" } && view in VIEWS

private fun <T> decoded(serializer: KSerializer<T>, record: Record): T? = try {
    recordJson.decodeFromJsonElement(serializer, record.data)
} catch (e: IllegalArgumentException) {
    null
}

// a file cut short reads as none, as the tab reads it
private fun <T> decoded(serializer: KSerializer<T>, text: String): T? = try {
    JSON.decodeFromString(serializer, text)
} catch (e: IllegalArgumentException) {
    null
}

private fun workspacesFile(router: String, view: String) = if (view == ROUTEROS) "workspaces-$router-routeros.json" else "workspaces-$router.json"

private fun tunnelsFile(router: String) = "tunnels-$router.json"

private fun tunnelId(router: String, tunnel: SavedTunnel) = recordId(router, tunnel.localPort.toString(), tunnel.host, tunnel.port.toString())

private const val LIST = "router-files.json"
private const val ROUTEROS = "routeros"
private val VIEWS = listOf("", ROUTEROS)
private val KEYS = ListSerializer(String.serializer())
private val TUNNELS = ListSerializer(SavedTunnel.serializer())
private val JSON = Json { ignoreUnknownKeys = true }
