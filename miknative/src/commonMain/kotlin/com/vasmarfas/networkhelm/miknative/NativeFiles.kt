package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.RemoteFile
import com.vasmarfas.networkhelm.ssh.SftpClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

private val FILE_STORE = listOf(2L, 2L)
private val OBJECT_MODEL = listOf(72L, 5L)
private val FILE_MANAGER = listOf(72L)

private const val CREATE = 1
private const val WRITE = 2
private const val OPEN = 3
private const val READ = 4

private const val NAME = 1
private const val SIZE = 2
private const val DATA = 3
private const val LAST = 4
private const val CHUNK = 32_000L
private const val READ_CHUNK = 32_168L

private const val REMOVE = 0xFE0006L
private const val SUBSCRIBE = 0xFE0012L
private const val UNSUBSCRIBE = 0xFE0013L
private const val CREDIT = 0xFE0017L

private const val ELEMENT_ID = 0xFE0001
private const val OBJECTS = 0xFE0002
private const val QUERY = 0xFE0016
private const val QUERY_SCOPE = 0xFE0017
private const val SUBSCRIPTION = 0xFE000C
private const val OBJECT_NAME = 0xFE0010
private const val OBJECT_REF = 0xFE0026
private const val CREDITS = 0xFF0012
private const val LIVE = 0xFF0017
private const val DIRECTORY_SCOPE = 0x33
private const val ITEM_TYPE = 3
private const val ITEM_MODIFIED = 4
private const val ITEM_SIZE = 0x0E
private const val DIRECTORY = 5L

private const val WINDOW = 1000L
private const val REFILL_BELOW = 500L
private const val FIRST_WAIT_MILLIS = 700L
private const val MIN_QUIET_MILLIS = 100L
private const val QUIET_FACTOR = 5
private const val LISTING_LIMIT_MILLIS = 20_000L

class NativeFiles(private val connection: NativeConnection) : SftpClient {
    private val lock = Mutex()

    // RouterOS 6 and 7.17 have nothing at [72, 5]: their file manager at [72] is one table of the files
    // of every directory, each under its whole name
    override suspend fun list(path: String): List<RemoteFile> = try {
        subscribed(path)
    } catch (e: DeviceException) {
        if (e.category != ErrorCategory.Unsupported) throw e
        val inside = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        lock.withLock { managed() }
            .filter { item -> item.text(NAME)?.let { it.startsWith(inside) && '/' !in it.removePrefix(inside) } == true }
            .mapNotNull { remoteFile(it, inside) }
    }

    // the object model has no end-of-listing marker: the live listing is complete once it falls quiet for
    // several times the longest gap between its portions
    private suspend fun subscribed(path: String): List<RemoteFile> = exclusive { source ->
        val scope = path.trim('/')
        val inside = if (scope.isEmpty()) "" else "$scope/"
        val query = M2Message.of(
            QUERY_SCOPE to M2Value.U8(0),
            DIRECTORY_SCOPE to M2Value.Message(if (scope.isEmpty()) M2Message(emptyList()) else reference(scope)),
        )
        val subscription = arrayOf(
            QUERY to M2Value.Messages(listOf(query)),
            LIVE to M2Value.Bool(true),
            M2Key.Command to M2Value.U32(SUBSCRIBE),
            SUBSCRIPTION to M2Value.U32(0x10000005),
            CREDITS to M2Value.U32(WINDOW),
        )
        val files = LinkedHashMap<String, RemoteFile>()
        val clock = TimeSource.Monotonic.markNow()
        var credits = WINDOW
        var lastData = -1L
        var longestGap = 0L
        try {
            source.send(OBJECT_MODEL, *subscription)
            withTimeoutOrNull(LISTING_LIMIT_MILLIS) {
                while (true) {
                    val quiet = if (lastData < 0) FIRST_WAIT_MILLIS else maxOf(MIN_QUIET_MILLIS, QUIET_FACTOR * longestGap)
                    val message = withTimeoutOrNull(quiet) { source.next() } ?: break
                    message.refusal()?.let { throw it }
                    val objects = message.messages(OBJECTS).orEmpty()
                    if (objects.isEmpty()) continue
                    val now = clock.elapsedNow().inWholeMilliseconds
                    if (lastData >= 0) longestGap = maxOf(longestGap, now - lastData)
                    lastData = now
                    objects.mapNotNull { remoteFile(it, inside) }.forEach { files[it.name] = it }
                    credits -= objects.size
                    if (credits < REFILL_BELOW) {
                        source.send(OBJECT_MODEL, M2Key.Command to M2Value.U32(CREDIT), CREDITS to M2Value.U32(WINDOW - credits))
                        credits = WINDOW
                    }
                }
            }
        } finally {
            // the router matches a subscription by its content, so the same message with another command ends it
            try {
                withContext(NonCancellable) {
                    source.send(
                        OBJECT_MODEL,
                        *subscription.map { if (it.first == M2Key.Command) M2Key.Command to M2Value.U32(UNSUBSCRIBE) else it }.toTypedArray(),
                    )
                }
            } catch (e: DeviceException) {
                if (e.category != ErrorCategory.ConnectionLost) throw e
            }
        }
        files.values.toList()
    }

    override suspend fun read(path: String): ByteArray = exclusive { source ->
        val opened = source.request(FILE_STORE, M2Value.U8(OPEN), NAME to M2Value.Text(path.trim('/')))
        val session = opened.number(M2Key.SessionId) ?: throw DeviceException(ErrorCategory.General, "the router opened no file")
        val size = opened.number(SIZE)
        val out = ByteSink()
        while (true) {
            val reply = source.request(FILE_STORE, M2Value.U8(READ), M2Key.SessionId to M2Value.U32(session), SIZE to M2Value.U32(READ_CHUNK))
            val data = reply.raw(DATA)
            data?.let { out.write(it) }
            val end = (reply[LAST] as? M2Value.Bool)?.value == true
            if (end || data == null || data.isEmpty() || (size != null && out.size >= size)) break
        }
        if (size != null && out.size < size) throw DeviceException(ErrorCategory.General, "the router sent ${out.size} of $size bytes")
        out.toByteArray()
    }

    override suspend fun write(path: String, bytes: ByteArray) = exclusive { source ->
        val created = source.request(
            FILE_STORE,
            M2Value.U8(CREATE),
            SIZE to M2Value.U32(bytes.size.toLong()),
            NAME to M2Value.Text(path.trim('/')),
        )
        val session = created.number(M2Key.SessionId) ?: throw DeviceException(ErrorCategory.General, "the router created no file")
        var offset = 0
        do {
            val end = minOf(offset + CHUNK.toInt(), bytes.size)
            source.request(
                FILE_STORE,
                M2Value.U8(WRITE),
                M2Key.SessionId to M2Value.U32(session),
                DATA to M2Value.Raw(bytes.copyOfRange(offset, end)),
                LAST to M2Value.Bool(end >= bytes.size),
            )
            offset = end
        } while (offset < bytes.size)
    }

    override suspend fun remove(path: String) = exclusive { source ->
        val name = path.trim('/')
        try {
            source.request(OBJECT_MODEL, M2Value.U32(REMOVE), OBJECT_REF to M2Value.Message(reference(name)))
        } catch (e: DeviceException) {
            if (e.category != ErrorCategory.Unsupported) throw e
            val id = managed().firstOrNull { it.text(NAME) == name }?.number(ELEMENT_ID) ?: throw DeviceException(ErrorCategory.MissingItem, "the router has no file $name")
            source.request(FILE_MANAGER, M2Value.U32(REMOVE), ELEMENT_ID to M2Value.U32(id))
        }
        Unit
    }

    override fun close() = Unit

    private suspend fun <T> exclusive(block: suspend (NativeSource) -> T): T = lock.withLock {
        val source = connection.newSource()
        try {
            block(source)
        } finally {
            source.close()
        }
    }

    private suspend fun managed(): List<M2Message> = NativeObjectModel(connection).records(FILE_MANAGER).orEmpty()

    private fun reference(name: String) = M2Message.of(OBJECT_NAME to M2Value.Text(name))

    private fun remoteFile(item: M2Message, inside: String): RemoteFile? {
        val name = item.text(NAME)?.removePrefix(inside) ?: return null
        val type = item.number(ITEM_TYPE) ?: return null
        return RemoteFile(name, item.number(ITEM_SIZE) ?: item.number(SIZE) ?: 0, type == DIRECTORY, item.number(ITEM_MODIFIED) ?: 0)
    }
}
