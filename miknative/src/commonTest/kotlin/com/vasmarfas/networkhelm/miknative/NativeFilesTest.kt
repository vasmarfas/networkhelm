package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SUBSCRIBE = 0xFE0012L
private const val UNSUBSCRIBE = 0xFE0013L
private const val CREDIT = 0xFE0017L
private const val REMOVE = 0xFE0006L
private const val GET_ALL = 0xFE0004L
private const val NOT_IMPLEMENTED = 0xFE0002L

// managed is RouterOS 6 and 7.17: [72, 5] is refused, and [72] holds every file under its whole name
private class FileStoreRouter(
    val files: MutableMap<String, ByteArray>,
    private val batches: List<Int>? = null,
    private val directories: Set<String> = emptySet(),
    private val managed: Boolean = false,
) {
    private val uploads = HashMap<Long, Pair<String, ByteArray>>()
    private val downloads = HashMap<Long, Pair<ByteArray, Int>>()
    private val numbers = HashMap<String, Long>()
    private var nextSession = 1L

    suspend fun FakeRouter.handle(message: M2Message) {
        when (message.numbers(M2Key.To)) {
            listOf(2L, 2L) -> fileStore(message)
            listOf(72L, 5L) -> if (managed) reply(message, M2Key.ErrorCode to M2Value.U32(NOT_IMPLEMENTED)) else objectModel(message)
            listOf(72L) -> if (managed) fileManager(message)
        }
    }

    private suspend fun FakeRouter.fileManager(message: M2Message) {
        val ids = files.keys.associateWith { numbers.getOrPut(it) { 0xFE000000L + numbers.size } }
        when (message.number(M2Key.Command)) {
            GET_ALL -> reply(
                message,
                0xFE0002 to M2Value.Messages(
                    ids.map { (name, id) ->
                        M2Message.of(
                            0xFE0001 to M2Value.U32(id),
                            1 to M2Value.Text(name),
                            3 to M2Value.U32(if (name in directories) 5 else 0),
                            0x0E to M2Value.U64(files.getValue(name).size.toLong()),
                        )
                    },
                ),
            )
            REMOVE -> {
                files.remove(ids.entries.first { it.value == message.number(0xFE0001) }.key)
                reply(message)
            }
        }
    }

    private suspend fun FakeRouter.fileStore(message: M2Message) {
        val session = message.number(M2Key.SessionId)
        when (message.number(M2Key.Command)) {
            1L -> {
                val id = nextSession++
                uploads[id] = checkNotNull(message.text(1)) to ByteArray(0)
                reply(message, M2Key.SessionId to M2Value.U32(id))
            }
            2L -> {
                val (name, soFar) = checkNotNull(uploads[session])
                val chunk = checkNotNull(message.raw(3))
                if (chunk.size > 32_000) return reply(message, M2Key.Error to M2Value.Text("chunk too large"))
                val whole = soFar + chunk
                uploads[checkNotNull(session)] = name to whole
                if ((message[4] as M2Value.Bool).value) files[name] = whole
                reply(message)
            }
            3L -> {
                val content = files[message.text(1)]
                if (content == null) {
                    reply(message, M2Key.Error to M2Value.Text("no such file"))
                } else {
                    val id = nextSession++
                    downloads[id] = content to 0
                    reply(message, M2Key.SessionId to M2Value.U32(id), 2 to M2Value.U32(content.size.toLong()))
                }
            }
            4L -> {
                val (content, offset) = checkNotNull(downloads[session])
                val end = minOf(offset + checkNotNull(message.number(2)).toInt(), content.size)
                downloads[checkNotNull(session)] = content to end
                reply(message, 3 to M2Value.Raw(content.copyOfRange(offset, end)), 4 to M2Value.Bool(end == content.size))
            }
        }
    }

    private suspend fun FakeRouter.objectModel(message: M2Message) {
        when (message.number(M2Key.Command)) {
            SUBSCRIBE -> {
                val target = M2Key.To to M2Value.U32s(longArrayOf(0, checkNotNull(message.numbers(M2Key.From))[1]))
                send(M2Message.of(target, M2Key.Command to M2Value.U32(0xFE000B), 0xFE0002 to M2Value.Messages(emptyList())))
                val inside = message.messages(0xFE0016)?.single()?.message(0x33)?.text(0xFE0010)?.let { "$it/" }.orEmpty()
                val items = files.entries.map { (name, content) ->
                    M2Message.of(
                        1 to M2Value.Text(inside + name),
                        3 to M2Value.U32(if (name in directories) 5 else 0),
                        4 to M2Value.U32(1_700_000_000),
                        0x0E to M2Value.U64(content.size.toLong()),
                    )
                }
                var from = 0
                for (count in batches ?: listOf(items.size)) {
                    send(M2Message.of(target, M2Key.Command to M2Value.U32(0xFE000B), 0xFE0002 to M2Value.Messages(items.subList(from, from + count))))
                    from += count
                }
            }
            REMOVE -> {
                files.remove(message.message(0xFE0026)?.text(0xFE0010))
                reply(message)
            }
        }
    }
}

class NativeFilesTest {

    private suspend fun until(condition: () -> Boolean) {
        while (!condition()) delay(10)
    }

    private fun files(
        router: FileStoreRouter,
        block: suspend CoroutineScope.(NativeFiles, FakeRouter) -> Unit,
    ) = runTest {
        val (client, server) = pipe()
        val fake = FakeRouter(this, server, "admin", "secret") { with(router) { handle(it) } }
        val connection = NativeConnection.open(client, "admin", "secret", this)
        try {
            withTimeout(30_000) { block(NativeFiles(connection), fake) }
        } finally {
            connection.close()
            fake.close()
        }
    }

    @Test
    fun aFileSurvivesTheRoundTripInChunks() = files(FileStoreRouter(HashMap())) { files, _ ->
        val bytes = ByteArray(70_000) { (it * 13).toByte() }
        files.write("/backup.bin", bytes)
        assertContentEquals(bytes, files.read("/backup.bin"))
    }

    @Test
    fun anEmptyFileIsWrittenAndRead() = files(FileStoreRouter(HashMap())) { files, _ ->
        files.write("/empty.txt", ByteArray(0))
        assertContentEquals(ByteArray(0), files.read("/empty.txt"))
    }

    @Test
    fun aMissingFileIsAnErrorWithTheTextOfTheRouter() = files(FileStoreRouter(HashMap())) { files, _ ->
        assertEquals("no such file", assertFailsWith<DeviceException> { files.read("/nothing") }.message)
    }

    @Test
    fun theListingHasNamesSizesAndKinds() = files(FileStoreRouter(hashMapOf("a.txt" to ByteArray(5), "pub" to ByteArray(0)), directories = setOf("pub"))) { files, _ ->
        val listed = files.list("/").sortedBy { it.name }
        assertEquals(listOf("a.txt", "pub"), listed.map { it.name })
        assertEquals(listOf(5L, 0L), listed.map { it.size })
        assertEquals(listOf(false, true), listed.map { it.directory })
        assertEquals(1_700_000_000L, listed.first().modifiedEpochSeconds)
    }

    @Test
    fun theSubscriptionIsEndedByTheSameMessageWithAnotherCommand() = files(FileStoreRouter(hashMapOf("a.txt" to ByteArray(5)))) { files, router ->
        files.list("/flash")
        until { router.received.any { it.number(M2Key.Command) == UNSUBSCRIBE } }
        val subscribe = router.received.first { it.number(M2Key.Command) == SUBSCRIBE }
        val unsubscribe = router.received.first { it.number(M2Key.Command) == UNSUBSCRIBE }
        fun rest(message: M2Message) = message.fields.filter { it.key != M2Key.Command }.map { it.key }
        assertEquals(rest(subscribe), rest(unsubscribe))
        assertContentEquals(subscribe.messages(0xFE0016)?.single()?.encode(), unsubscribe.messages(0xFE0016)?.single()?.encode())
        assertEquals(subscribe.number(0xFF0012), unsubscribe.number(0xFF0012))
    }

    @Test
    fun aListingThatIsGivenUpStillEndsTheSubscription() = files(FileStoreRouter(HashMap())) { files, router ->
        val listing = launch { files.list("/") }
        until { router.received.any { it.number(M2Key.Command) == SUBSCRIBE } }
        listing.cancelAndJoin()
        until { router.received.any { it.number(M2Key.Command) == UNSUBSCRIBE } }
    }

    @Test
    fun filesAroundTheSizeOfAChunkSurvive() = files(FileStoreRouter(HashMap())) { files, _ ->
        for (size in listOf(31_999, 32_000, 32_001, 64_000, 64_001)) {
            val bytes = ByteArray(size) { (it * 3).toByte() }
            files.write("/chunk-$size.bin", bytes)
            assertContentEquals(bytes, files.read("/chunk-$size.bin"), "size $size")
        }
    }

    @Test
    fun theWindowOfCreditsIsRefilledOnTheWay() {
        val names = (1..1200).associate { "file-$it" to ByteArray(0) }.toMutableMap()
        files(FileStoreRouter(names, listOf(400, 400, 400))) { files, router ->
            assertEquals(1200, files.list("/").size)
            until { router.received.any { it.number(M2Key.Command) == CREDIT } }
            val refills = router.received.filter { it.number(M2Key.Command) == CREDIT }
            assertEquals(listOf(800L), refills.map { it.number(0xFF0012) })
        }
    }

    @Test
    fun aSubdirectoryIsAskedForByItsPath() = files(FileStoreRouter(HashMap())) { files, router ->
        files.list("/flash/backups")
        val query = router.received.first { it.number(M2Key.Command) == SUBSCRIBE }.messages(0xFE0016)?.single()
        assertEquals("flash/backups", query?.message(0x33)?.text(0xFE0010))
    }

    @Test
    fun theEntriesOfADirectoryAreNamedFromTheDirectory() = files(FileStoreRouter(hashMapOf("a.txt" to ByteArray(5)))) { files, _ ->
        assertEquals(listOf("a.txt"), files.list("/pub").map { it.name })
    }

    @Test
    fun theRootIsAskedForWithAnEmptyScope() = files(FileStoreRouter(HashMap())) { files, router ->
        files.list("/")
        val query = router.received.first { it.number(M2Key.Command) == SUBSCRIBE }.messages(0xFE0016)?.single()
        assertEquals(0, query?.message(0x33)?.fields?.size)
    }

    @Test
    fun aFileIsRemovedByItsName() = files(FileStoreRouter(hashMapOf("old.txt" to ByteArray(1)))) { files, _ ->
        assertTrue(files.list("/").any { it.name == "old.txt" })
        files.remove("/old.txt")
        assertFalse(files.list("/").any { it.name == "old.txt" })
    }

    @Test
    fun theFilesOfARouterWithoutTheNewManagerAreListedFromTheOldOne() {
        val router = FileStoreRouter(hashMapOf("a.txt" to ByteArray(5), "pub" to ByteArray(0), "pub/b.txt" to ByteArray(3)), directories = setOf("pub"), managed = true)
        files(router) { files, _ ->
            val root = files.list("/").sortedBy { it.name }
            assertEquals(listOf("a.txt", "pub"), root.map { it.name })
            assertEquals(listOf(5L, 0L), root.map { it.size })
            assertEquals(listOf(false, true), root.map { it.directory })
            assertEquals(listOf("b.txt" to 3L), files.list("/pub/").map { it.name to it.size })
        }
    }

    @Test
    fun theOldManagerRemovesAFileByItsNumber() {
        val router = FileStoreRouter(hashMapOf("a.txt" to ByteArray(5), "pub" to ByteArray(0), "pub/b.txt" to ByteArray(3)), directories = setOf("pub"), managed = true)
        files(router) { files, fake ->
            files.remove("/pub/b.txt")
            assertEquals(setOf("a.txt", "pub"), router.files.keys)
            assertEquals(1, fake.received.count { it.numbers(M2Key.To) == listOf(72L) && it.number(M2Key.Command) == REMOVE })
            assertEquals(ErrorCategory.MissingItem, assertFailsWith<DeviceException> { files.remove("/pub/b.txt") }.category)
        }
    }

    @Test
    fun aRouterThatCannotListSaysSo() = runTest {
        val (client, server) = pipe()
        val fake = FakeRouter(this, server, "admin", "secret") { message ->
            if (message.number(M2Key.Command) == SUBSCRIBE || message.number(M2Key.Command) == GET_ALL) {
                reply(message, M2Key.ErrorCode to M2Value.U32(NOT_IMPLEMENTED))
            }
        }
        val connection = NativeConnection.open(client, "admin", "secret", this)
        try {
            val error = assertFailsWith<DeviceException> { NativeFiles(connection).list("/") }
            assertEquals(ErrorCategory.Unsupported, error.category)
        } finally {
            connection.close()
            fake.close()
        }
    }
}
