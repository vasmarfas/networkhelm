package com.vasmarfas.networkhelm.ui.files

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.openwrt.RPCD_READ_LIMIT
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.rows
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.SshReach
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

// RouterOS 6 and 7 give the contents of a file as a property up to this size, and 7 reads more with /file/read
const val CONTENTS_READ = 4_095L

// RouterOS 7 takes this much text as contents and refuses more; a much longer one drops the connection
private const val CONTENTS_WRITE = 60_000L

// /file/read of RouterOS 7 gives at most this many bytes of a file at a time
private const val CHUNK = 32_768L

private val SERVICES = MenuPath.parse("ip/service")

internal class FileBackend(private val session: DeviceSession, private val files: MenuPath) {
    private val ssh = session.ssh
    private val own = session.fileChannel
    private val routerOs = session.device.platform == Platform.RouterOs
    private val seven = session.device.version.startsWith("7")
    private val writable = session.device.canWrite
    private val state = MutableStateFlow<Bulk>(Bulk.Asking)
    private val asking = Mutex()
    private val used = MutableStateFlow<FileVia?>(null)
    private var known: Set<String>? = null
    private var sshOpen = false

    val bulk: StateFlow<Bulk> get() = state

    val via: StateFlow<FileVia?> get() = used

    // a drag out of the window is read on the thread of the interface, where the question about the host key of SSH is never answered
    val canDrag: Boolean get() = (state.value as? Bulk.Ready)?.via.let { it != null && (it != FileVia.Sftp || sshOpen) }

    // RouterOS 7 adds and renames files with the commands; 6 has neither /file add nor a name to set
    val commandNames: Boolean get() = routerOs && seven

    suspend fun probe(fresh: Boolean = false) {
        asking.withLock {
            if (!fresh && state.value !is Bulk.Asking) return
            state.value = decide(fresh)
        }
    }

    private suspend fun decide(fresh: Boolean): Bulk {
        val other = own?.let { Bulk.Ready(FileVia.Rpcd) }
        return when {
            ssh == null -> other ?: Bulk.Missing(Why.NoSsh)
            !session.device.canTransfer -> other ?: Bulk.Missing(Why.Rights)
            ssh.fileStore -> Bulk.Ready(FileVia.Native)
            else -> when (val reach = ssh.reach(fresh)) {
                SshReach.Open -> Bulk.Ready(FileVia.Sftp)
                SshReach.Off -> other ?: Bulk.Missing(Why.SshOff)
                is SshReach.Closed -> other ?: Bulk.Missing(Why.SshClosed, reach.port)
            }
        }
    }

    private suspend fun settled(): Bulk {
        probe()
        return state.value
    }

    private suspend fun bulkRoute(size: Long?, reading: Boolean): Route = when (val now = settled()) {
        is Bulk.Ready -> if (reading && now.via == FileVia.Rpcd && size != null && size >= RPCD_READ_LIMIT) Route.Blocked(Why.TooBig) else Route.Over(now.via)
        is Bulk.Missing -> Route.Blocked(now.why, now.port)
        Bulk.Asking -> Route.Blocked(Why.NoSsh)
    }

    private suspend fun commands(): Set<String> = known ?: session.describe(files).commands.toSet().also { known = it }

    private val commandsCarry = routerOs && ssh?.fileStore != true

    private fun text(entry: Entry) = commandsCarry && entry.id != null && entry.kind.text

    private val rpcd = own != null && !routerOs

    private fun fits(size: Long?) = rpcd && size != null && size < RPCD_READ_LIMIT

    suspend fun readRoute(entry: Entry): Route {
        val size = entry.size
        if (fits(size)) return Route.Over(FileVia.Rpcd)
        if (text(entry) && size != null && (size <= CONTENTS_READ || size <= EDIT_LIMIT && seven && "read" in commands())) return Route.Over(FileVia.Api)
        return bulkRoute(size, reading = true)
    }

    fun readsOver(entry: Entry): FileVia? {
        val size = entry.size
        if (fits(size)) return FileVia.Rpcd
        if (text(entry) && size != null && size <= CONTENTS_READ) return FileVia.Api
        return (state.value as? Bulk.Ready)?.via
    }

    fun canRead(entry: Entry): Boolean = fits(entry.size) || text(entry) && (entry.size ?: Long.MAX_VALUE) <= CONTENTS_READ || state.value is Bulk.Ready

    fun canWrite(entry: Entry): Boolean = writable && (routerOs && entry.id != null || state.value is Bulk.Ready)

    suspend fun read(entry: Entry): ByteArray = when (val route = readRoute(entry)) {
        is Route.Blocked -> throw NoChannelException(route.why, route.port)
        is Route.Over -> if (route.via == FileVia.Api) readText(entry)?.also { used.value = FileVia.Api } ?: elsewhere(entry) else over(route.via) { it.read("/${entry.path}") }
    }

    private suspend fun elsewhere(entry: Entry): ByteArray = when (val other = bulkRoute(entry.size, reading = true)) {
        is Route.Blocked -> throw NoChannelException(other.why, other.port)
        is Route.Over -> over(other.via) { it.read("/${entry.path}") }
    }

    private suspend fun readText(entry: Entry): ByteArray? {
        val id = entry.id ?: return null
        val size = entry.size ?: 0
        val text = if (size <= CONTENTS_READ) {
            session.get(files, id, listOf("contents"))?.get("contents").orEmpty()
        } else {
            buildString {
                var offset = 0L
                while (offset < size) {
                    val part = session.run(files, "read", mapOf("file" to entry.path, "offset" to "$offset", "chunk-size" to "$CHUNK")).toList().firstOrNull()?.get("data")
                    append(part.orEmpty())
                    offset += CHUNK
                }
            }
        }
        return plain(text)
    }

    suspend fun write(entry: Entry, bytes: ByteArray) {
        val id = entry.id
        if (commandsCarry && id != null && bytes.size <= (if (seven) CONTENTS_WRITE else CONTENTS_READ)) {
            val text = bytes.decodeToString()
            if (plain(text)?.contentEquals(bytes) == true) {
                session.set(files, id, mapOf("contents" to text))
                used.value = FileVia.Api
                return
            }
        }
        if (rpcd && bytes.size < RPCD_READ_LIMIT) return over(FileVia.Rpcd) { it.write("/${entry.path}", bytes) }
        when (val route = bulkRoute(null, reading = false)) {
            is Route.Blocked -> throw NoChannelException(route.why, route.port)
            is Route.Over -> over(route.via) { it.write("/${entry.path}", bytes) }
        }
    }

    suspend fun write(path: String, bytes: ByteArray) =
        if (rpcd && bytes.size < RPCD_READ_LIMIT) over(FileVia.Rpcd) { it.write("/$path", bytes) } else channel { it.write("/$path", bytes) }

    private fun plain(text: String): ByteArray? = if ('�' in text || '\u0000' in text) null else text.encodeToByteArray()

    suspend fun <T> channel(block: suspend (SftpClient) -> T): T = when (val route = bulkRoute(null, reading = false)) {
        is Route.Blocked -> throw NoChannelException(route.why, route.port)
        is Route.Over -> over(route.via, block)
    }

    private suspend fun <T> over(via: FileVia, block: suspend (SftpClient) -> T): T {
        val client = try {
            if (via == FileVia.Rpcd) own else ssh?.sftp()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!e.refusedHostKey()) throw e
            state.value = Bulk.Missing(Why.KeyRefused)
            throw NoChannelException(Why.KeyRefused)
        } ?: throw NoChannelException(Why.NoSsh)
        used.value = via
        if (via == FileVia.Sftp) sshOpen = true
        try {
            return block(client)
        } finally {
            client.close()
        }
    }

    private suspend fun <T> places(block: suspend (SftpClient) -> T): T = if (rpcd) over(FileVia.Rpcd, block) else channel(block)

    suspend fun list(folder: String): List<Entry> = places { listing(it, folder) }

    suspend fun list(folders: Collection<String>): Map<String, Result<List<Entry>>> = places { client ->
        folders.associateWith { folder ->
            try {
                Result.success(listing(client, folder))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun exists(path: String): Boolean {
        val found = if (routerOs) {
            withTimeout(10.seconds) { session.watch(files, Filter.Equals("name", path)).rows().drop(1).first() }.mapNotNull(::entryOf)
        } else {
            list(path.substringBeforeLast('/', ""))
        }
        return found.any { it.path == path && !it.directory }
    }

    private suspend fun listing(client: SftpClient, folder: String): List<Entry> = client.list("/$folder").map { file ->
        val path = if (folder.isEmpty()) file.name else "$folder/${file.name}"
        Entry(path, path, file.directory, file.size, cleanTime(utcText(file.modifiedEpochSeconds)), if (file.directory) "directory" else "file")
    }

    suspend fun rename(entry: Entry, to: String) {
        val id = entry.id
        if (commandNames && id != null) session.set(files, id, mapOf("name" to to)) else places { it.rename("/${entry.path}", "/$to") }
    }

    suspend fun makeFolder(path: String) {
        if (commandNames && "add" in commands()) {
            session.add(files, mapOf("name" to path, "type" to "directory"))
        } else {
            places { it.mkdir("/$path") }
        }
    }

    suspend fun makeFile(path: String) {
        if (commandNames && "add" in commands()) {
            session.add(files, mapOf("name" to path, "type" to "file"))
        } else {
            places { it.write("/$path", ByteArray(0)) }
        }
    }

    suspend fun delete(targets: List<Entry>, all: List<Entry>) {
        val ids = LinkedHashSet<String>()
        for (target in targets) {
            if (target.directory) {
                val prefix = target.path + "/"
                val inside = all.filter { it.path.startsWith(prefix) }
                if (routerOs || !rpcd && state.value !is Bulk.Ready) {
                    inside.sortedByDescending { entry -> entry.path.count { it == '/' } }.forEach { entry -> idOf(entry)?.let(ids::add) }
                } else {
                    places { client -> client.tree("/${target.path}") }.forEach { ids += it.removePrefix("/") }
                }
            }
            idOf(target)?.let(ids::add)
        }
        session.remove(files, ids.toList())
    }

    private fun idOf(entry: Entry): String? = if (routerOs) entry.id else entry.id ?: entry.path

    private suspend fun SftpClient.tree(path: String): List<String> = list(path).flatMap { child ->
        val full = "$path/${child.name}"
        if (child.directory) tree(full) + full else listOf(full)
    }

    suspend fun enableSsh() {
        val row = withTimeout(10.seconds) { session.watch(SERVICES, Filter.Equals("name", "ssh")).rows().drop(1).first().firstOrNull() }
            ?: throw DeviceException(ErrorCategory.MissingItem, "the router lists no SSH service")
        session.set(SERVICES, row.id, mapOf("disabled" to "no"))
        repeat(5) {
            probe(fresh = true)
            if (state.value is Bulk.Ready) return
            delay(1.seconds)
        }
    }
}
