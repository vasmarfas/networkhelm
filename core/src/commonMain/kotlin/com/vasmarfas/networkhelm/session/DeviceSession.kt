package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.packs.NativeMenu
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

interface DeviceSession {
    val device: DeviceInfo

    val capabilities: Capabilities

    suspend fun describe(path: MenuPath): MenuSchema

    suspend fun outline(path: MenuPath): MenuSchema = describe(path)

    // whether the native channel serves the window of a query under a console path; a router that refused the query has given the window back to the console
    fun nativeStands(path: MenuPath): Boolean = true

    suspend fun forgetStructure() {}

    fun watch(path: MenuPath, filter: Filter? = null, properties: List<String>? = null): Flow<ListEvent>

    suspend fun get(path: MenuPath, id: String?, properties: List<String>? = null): Item?

    suspend fun count(path: MenuPath, filter: Filter? = null): Int

    suspend fun add(path: MenuPath, values: Map<String, String>): String

    suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>)

    suspend fun remove(path: MenuPath, ids: List<String>)

    suspend fun move(path: MenuPath, ids: List<String>, before: String?)

    fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item>

    suspend fun export(path: MenuPath = MenuPath.Root): String

    fun counters(path: MenuPath, properties: List<String>, filter: Filter? = null): Flow<Map<String, Map<String, String>>> = emptyFlow()

    val link: Flow<LinkState> get() = flowOf(LinkState.Up)

    suspend fun packs(store: TextStore? = null): List<Pack> = emptyList()

    suspend fun nativeMenu(): NativeMenu? = null

    // the titles and labels of the bundled windows as the router's own interface names them, in the language of the app
    suspend fun words(store: TextStore? = null): Map<String, String> = emptyMap()

    // the router asked for a new password at login; the session works until one is given
    val passwordExpired: Boolean get() = false

    suspend fun changePassword(old: String, new: String): Unit = throw DeviceException(ErrorCategory.Unsupported, "the password of this account cannot be changed here")

    val safeMode: SafeMode?

    val ssh: SshSession?

    val fileChannel: SftpClient? get() = null

    fun close()
}

interface SafeMode {
    val active: Flow<Boolean>

    // RouterOS keeps 100 actions of history; past that it leaves Safe Mode on its own and undoes nothing
    val actions: Flow<Int>

    suspend fun enter(): Boolean

    suspend fun commit()

    suspend fun rollback()

    val stranded: Flow<Boolean> get() = flowOf(false)

    suspend fun settle(keep: Boolean) {}
}
