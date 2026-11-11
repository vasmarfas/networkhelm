package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.packs.NATIVE_NO_KEY
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.packs.PackNativeField
import com.vasmarfas.networkhelm.packs.PackNativeRef
import com.vasmarfas.networkhelm.session.RouterFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GET_ALL = 0xFE0004L
private const val GET_SINGLETON = 0xFE000DL
private const val DEVICES = 1L
private const val RESOURCES = 44L
private const val SUBSCRIBE = 0xFE0012L
private const val UNSUBSCRIBE = 0xFE0013L

private class ControllerRouter(private val pages: Map<Long, List<List<M2Message>>>, private val failure: Long? = null, private val holders: Map<Long, M2Message> = emptyMap(), private val gone: Long? = null) {
    val asked = ArrayList<M2Message>()

    suspend fun FakeRouter.handle(message: M2Message) {
        val command = message.number(M2Key.Command)
        if (command != GET_ALL && command != GET_SINGLETON) return
        asked += message
        if (failure != null) return reply(message, M2Key.ErrorCode to M2Value.U32(failure))
        val handler = checkNotNull(message.numbers(M2Key.To)).last()
        if (command == GET_SINGLETON) return reply(message, *holders.getValue(handler).fields.map { it.key to it.value }.toTypedArray())
        val cursor = message.number(0xFE0003) ?: 0
        if (cursor == gone) return reply(message, M2Key.ErrorCode to M2Value.U32(0xFE0004))
        val page = pages.getValue(handler).getOrNull(cursor.toInt()) ?: return reply(message, M2Key.ErrorCode to M2Value.U32(0xFE0004))
        val more = if (cursor + 1 < pages.getValue(handler).size) arrayOf(0xFE0003 to M2Value.U32(cursor + 1)) else emptyArray()
        reply(message, 0xFE0002 to M2Value.Messages(page), *more)
    }
}

// a table read as Routes are: the cursor of the first page names the read and not a row, the pages after it do not repeat it,
// and the read ends with an empty page or with the answer [ending]
private class RouteRouter(private val pages: List<List<M2Message>>, private val ending: Long? = null) {
    val asked = ArrayList<M2Message>()
    val stopped = ArrayList<M2Message>()

    suspend fun FakeRouter.handle(message: M2Message) {
        if (message.number(M2Key.Command) == 0xFE0018L) stopped += message
        if (message.number(M2Key.Command) != GET_ALL) return
        asked += message
        val page = pages.getOrNull(asked.size - 1)
        val cursor = if (asked.size == 1) arrayOf(0xFE0003 to M2Value.U32(0xFFFFF001)) else emptyArray()
        when {
            page != null -> reply(message, 0xFE0002 to M2Value.Messages(page), *cursor)
            ending != null -> reply(message, M2Key.ErrorCode to M2Value.U32(ending))
            else -> reply(message, 0xFE0002 to M2Value.Messages(emptyList()))
        }
    }
}

// a window that gives its first state to a subscription two records a message, the first [dumped] of them and then the removals,
// the [early] removals before them, and reads by getall from the record the cursor names; a [counted] one says the number of rows,
// marks the end of the first state and sends the removals after it, and then the [later] records one every quarter of a second,
// as a table that keeps changing does
private class SubscribedRouter(
    private val records: List<M2Message>,
    private val dumped: Int = records.size,
    private val gone: List<Long> = emptyList(),
    private val first: Boolean = true,
    private val early: List<Long> = emptyList(),
    private val counted: Boolean = false,
    private val later: List<M2Message> = emptyList(),
) {
    val asked = ArrayList<M2Message>()

    suspend fun FakeRouter.handle(message: M2Message) {
        asked += message
        when (message.number(M2Key.Command)) {
            SUBSCRIBE -> if (first) {
                val target = M2Key.To to M2Value.U32s(longArrayOf(0, checkNotNull(message.numbers(M2Key.From))[1]))
                fun removals(ids: List<Long>) = ids.map { M2Message.of(0xFE0001 to M2Value.U8(it.toInt()), 0xFE0013 to M2Value.U8(1)) }
                fun event(vararg fields: Pair<Int, M2Value>) = M2Message.of(target, M2Key.Command to M2Value.U32(0xFE000B), *fields)
                if (counted) {
                    records.chunked(2).forEachIndexed { at, part ->
                        send(event(0xFE0002 to M2Value.Messages(part), *(if (at == 0) arrayOf(0xFE0019 to M2Value.U32(records.size.toLong())) else emptyArray())))
                    }
                    send(event(0xFF000B to M2Value.U32(0xFFFFFFFF)))
                    if (gone.isNotEmpty()) send(event(0xFE0002 to M2Value.Messages(removals(gone)), 0xFE0019 to M2Value.U32((records.size - gone.size).toLong())))
                    for (record in later) {
                        delay(250)
                        send(event(0xFE0002 to M2Value.Messages(listOf(record))))
                    }
                } else {
                    (removals(early) + records.take(dumped) + removals(gone)).chunked(2).forEach { send(event(0xFE0002 to M2Value.Messages(it))) }
                }
            }
            GET_ALL -> {
                val from = message.number(0xFE0003)
                val rest = records.filter { from == null || checkNotNull(it.number(0xFE0001)) >= from }
                val more = rest.getOrNull(2)?.let { arrayOf(0xFE0003 to M2Value.U32(checkNotNull(it.number(0xFE0001)))) } ?: emptyArray()
                reply(message, 0xFE0002 to M2Value.Messages(rest.take(2)), *more)
            }
        }
    }
}

private class SystemRouter(private val word: Long?) {
    suspend fun FakeRouter.handle(message: M2Message) {
        val handler = checkNotNull(message.numbers(M2Key.To)).last()
        when (message.number(M2Key.Command)) {
            7L -> if (handler == 4L) {
                val mask = M2Message.of(0x6D to M2Value.U32s(longArrayOf(0x24481C87, 0x20102010, 0x7FBF8C00, 0x11000)))
                reply(message, 0x15 to M2Value.Text("CHR"), 0x17 to M2Value.Text("x86"), 0x11 to M2Value.Text("i386"), 0x28 to M2Value.Message(mask))
            }
            5L -> if (handler == 120L) {
                if (word == null) reply(message, M2Key.ErrorCode to M2Value.U32(0xFE0009)) else reply(message, 1 to M2Value.U8(word.toInt()))
            }
        }
    }
}

// answers the system info and the holders it is told to, and says nothing to the rest, as a router does to a handler of hardware it has not
private class PatchyRouter(
    private val holders: Set<List<Long>>,
    private val silent: Boolean = false,
    private val off: Set<List<Long>> = emptySet(),
    private val absent: Set<List<Long>> = emptySet(),
) {
    suspend fun FakeRouter.handle(message: M2Message) {
        if (silent) return
        val handler = checkNotNull(message.numbers(M2Key.To)).toList().takeLast(2)
        when (message.number(M2Key.Command)) {
            7L -> if (handler == listOf(13L, 4L)) reply(message, 0x15 to M2Value.Text("CHR"))
            GET_SINGLETON -> when (handler) {
                in holders -> reply(message, 0x1 to M2Value.U32(1))
                in off -> reply(message, M2Key.ErrorCode to M2Value.U32(0xFE0006), M2Key.Error to M2Value.Text("dude is disabled"))
                in absent -> reply(message, M2Key.ErrorCode to M2Value.U32(0xFE0002))
            }
        }
    }
}

// serves the files of the web interface in parts of at most [part] bytes, marking the part that ends a file only when [marks],
// and names [size] for the size of each when it is given one
private class FileRouter(private val files: Map<String, ByteArray>, private val part: Int, private val marks: Boolean, private val size: M2Value? = null) {
    val closed = ArrayList<Long>()
    private val open = HashMap<Long, Pair<ByteArray, Int>>()
    private var next = 1L

    suspend fun FakeRouter.handle(message: M2Message) {
        if (checkNotNull(message.numbers(M2Key.To)).toList().takeLast(2) != listOf(2L, 2L)) return
        when (message.number(M2Key.Command)) {
            7L -> {
                val data = files[message.text(1)] ?: return reply(message, M2Key.ErrorCode to M2Value.U32(0xFE0006))
                val handle = next++
                open[handle] = data to 0
                reply(message, 0xFE0001 to M2Value.U32(handle), 2 to (size ?: M2Value.U32(data.size.toLong())))
            }
            4L -> {
                val handle = checkNotNull(message.number(0xFE0001))
                val (data, at) = open[handle] ?: return reply(message, M2Key.ErrorCode to M2Value.U32(0xFE0004))
                val count = minOf(part, checkNotNull(message.number(2)).toInt(), data.size - at)
                open[handle] = data to at + count
                val end = if (marks && at + count == data.size) arrayOf(4 to M2Value.Bool(true)) else emptyArray()
                reply(message, 3 to M2Value.Raw(data.copyOfRange(at, at + count)), *end)
            }
            5L -> {
                val handle = checkNotNull(message.number(0xFE0001))
                closed += handle
                open.remove(handle)
                reply(message)
            }
        }
    }
}

class NativeObjectModelTest {

    private val description = ByteArray(150_000) { (it % 251).toByte() }

    private fun files(marks: Boolean, size: M2Value? = null, block: suspend CoroutineScope.(NativeObjectModel, FileRouter) -> Unit) = runTest {
        val router = FileRouter(mapOf("roteros-aa.jg.gz" to description), 61_440, marks, size)
        val (client, server) = pipe()
        val fake = FakeRouter(this, server, "admin", "secret") { with(router) { handle(it) } }
        val connection = NativeConnection.open(client, "admin", "secret", this)
        try {
            withTimeout(30_000) { block(NativeObjectModel(connection), router) }
        } finally {
            connection.close()
            fake.close()
        }
    }

    @Test
    fun aFileOfTheWebInterfaceIsReadInPartsToTheMarkOfItsEnd() = files(marks = true) { model, router ->
        assertContentEquals(description, model.resource("roteros-aa.jg.gz"))
        assertNull(model.resource("roteros.jg"))
        assertEquals(emptyList(), router.closed)
    }

    @Test
    fun aFileReadToItsSizeWithoutTheMarkOfItsEndIsClosed() = files(marks = false) { model, router ->
        assertContentEquals(description, model.resource("roteros-aa.jg.gz"))
        assertEquals(listOf(1L), router.closed)
    }

    @Test
    fun aFileWhoseSizeHasTheHighBitUpIsNotRead() = files(marks = true, size = M2Value.U64(Long.MIN_VALUE)) { model, _ ->
        assertNull(model.resource("roteros-aa.jg.gz"))
    }

    private fun asked(router: SystemRouter, block: suspend CoroutineScope.(RouterFacts?) -> Unit) = runTest {
        val (client, server) = pipe()
        val fake = FakeRouter(this, server, "admin", "secret") { with(router) { handle(it) } }
        val connection = NativeConnection.open(client, "admin", "secret", this)
        try {
            withTimeout(30_000) { block(NativeObjectModel(connection).facts()) }
        } finally {
            connection.close()
            fake.close()
        }
    }

    @Test
    fun theFactsOfARouterComeFromItsSystemInfoAndTheWordOfTheQuickSetHandler() = asked(SystemRouter(64)) { facts ->
        assertEquals(RouterFacts("CHR", "x86", "i386", listOf(0x24481C87, 0x20102010, 0x7FBF8C00, 0x11000), 64), facts)
    }

    @Test
    fun aQuickSetHandlerThatRefusesLeavesTheWordUnknownAndTheRestStands() = asked(SystemRouter(null)) { facts ->
        assertEquals(RouterFacts("CHR", "x86", "i386", listOf(0x24481C87, 0x20102010, 0x7FBF8C00, 0x11000), null), facts)
    }

    private fun patchy(router: PatchyRouter, block: suspend CoroutineScope.(NativeObjectModel) -> Unit) = runTest {
        val (client, server) = pipe()
        val fake = FakeRouter(this, server, "admin", "secret") { with(router) { handle(it) } }
        val connection = NativeConnection.open(client, "admin", "secret", this)
        try {
            withTimeout(60_000) { block(NativeObjectModel(connection)) }
        } finally {
            connection.close()
            fake.close()
        }
    }

    private val uptime = PackNative(listOf(24, 2), listOf(PackNativeField("uptime", 0x1, "interval")), single = true)

    private val ignored = PackNative(listOf(145, 1), listOf(PackNativeField("size", 0x1, "number")), single = true)

    @Test
    fun aHandlerTheRouterIgnoresIsNoWindowAndNotALostChannelWhileTheRouterAnswersElsewhere() = patchy(PatchyRouter(setOf(listOf(24L, 2L)))) { model ->
        assertTrue(model.answers(uptime))
        assertFalse(model.answers(ignored))
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { model.read(ignored) }.category)
        assertEquals(listOf("1s"), model.read(uptime)?.map { it["uptime"] })
    }

    @Test
    fun aHandlerThatRefusesOutrightIsNoWindowWhetherItsServiceIsOffOrTheRouterLacksIt() = patchy(PatchyRouter(emptySet(), off = setOf(listOf(103L, 3L)), absent = setOf(listOf(24L, 29L)))) { model ->
        assertFalse(model.answers(PackNative(listOf(103, 3), listOf(PackNativeField("enabled", 0x1, "bool")), single = true)))
        assertFalse(model.answers(PackNative(listOf(24, 29), listOf(PackNativeField("name", 0x1, "string")), single = true)))
    }

    @Test
    fun aRouterThatAnswersNothingAtAllIsALostChannel() = patchy(PatchyRouter(emptySet(), silent = true)) { model ->
        assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { model.answers(uptime) }.category)
        assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { model.read(uptime) }.category)
    }

    private fun device(id: Long, name: String) = M2Message.of(0xFE0001 to M2Value.U8(id.toInt()), 0x2009 to M2Value.Text(name), 0x2112 to M2Value.Message(M2Message(emptyList())))

    private fun resource(id: Long, cpu: Int, memory: Long) = M2Message.of(0x202 to M2Value.U8(cpu), 0xFE0001 to M2Value.U8(id.toInt()), 0x204 to M2Value.U64(memory))

    private fun read(router: ControllerRouter, spec: PackNative, block: suspend CoroutineScope.(List<Item>) -> Unit) = runTest {
        val (client, server) = pipe()
        val fake = FakeRouter(this, server, "admin", "secret") { with(router) { handle(it) } }
        val connection = NativeConnection.open(client, "admin", "secret", this)
        try {
            withTimeout(30_000) { block(checkNotNull(NativeObjectModel(connection).read(spec))) }
        } finally {
            connection.close()
            fake.close()
        }
    }

    private val resources = PackNative(
        listOf(167, RESOURCES),
        listOf(
            PackNativeField("device", 0xFE0001, "enm", refers = PackNativeRef(listOf(167, DEVICES), listOf(0x2009, 0x2112), "@")),
            PackNativeField("cpu", 0x202, "number", postfix = "%"),
            PackNativeField("memory", 0x204, "bignumber", postfix = "%"),
        ),
    )

    @Test
    fun theElementsOfAHandlerAreReadWithTheIdsOfTheConsoleAndTheDevicesNamed() = read(
        ControllerRouter(mapOf(RESOURCES to listOf(listOf(resource(1, 3, 14), resource(10, 0, 99))), DEVICES to listOf(listOf(device(1, "gw-1"), device(10, "cap-1"))))),
        resources,
    ) { items ->
        assertEquals(listOf("*1", "*A"), items.map { it.id })
        assertEquals(mapOf("device" to "gw-1", "cpu" to "3%", "memory" to "14%"), items[0].values)
        assertEquals("cap-1", items[1]["device"])
    }

    @Test
    fun anElementThatLiesUnderAnotherNamesItsOwnerFromTheReferenceToItself() = read(
        ControllerRouter(
            mapOf(
                RESOURCES to listOf(listOf(M2Message.of(0x8100 to M2Value.Text("ether1"), 0xFE0001 to M2Value.U8(4), 0xFE0026 to M2Value.Message(M2Message.of(0x8110 to M2Value.U8(1), 2 to M2Value.U8(4)))))),
                DEVICES to listOf(listOf(device(1, "gw-1"))),
            ),
        ),
        PackNative(
            listOf(167, RESOURCES),
            listOf(
                PackNativeField("device", 0x8110, "enm", refers = PackNativeRef(listOf(167, DEVICES), listOf(0x2009), "@")),
                PackNativeField("interface", 0x8100, "string"),
            ),
        ),
    ) { items -> assertEquals(mapOf("device" to "gw-1", "interface" to "ether1"), items.single().values) }

    @Test
    fun aWindowOfSettingsIsReadWithTheSingletonCommandAndItsFieldsComeInTheReplyItself() {
        val record = M2Message.of(0x1 to M2Value.U32(705_894), 0x2c to M2Value.Text("CHR QEMU"), 0x16 to M2Value.Text("7.26beta1"), 0x3 to M2Value.U32(7))
        val router = ControllerRouter(emptyMap(), holders = mapOf(2L to record))
        val spec = PackNative(
            listOf(24, 2),
            listOf(PackNativeField("uptime", 0x1, "interval"), PackNativeField("board-name", 0x2c, "string"), PackNativeField("version", 0x16, "version")),
            single = true,
        )
        read(router, spec) { items ->
            assertEquals(listOf("#0"), items.map { it.id })
            assertEquals(mapOf("uptime" to "1w1d4h4m54s", "board-name" to "CHR QEMU", "version" to "7.26beta1"), items.single().values)
            assertEquals(listOf(GET_SINGLETON), router.asked.map { it.number(M2Key.Command) })
        }
    }

    @Test
    fun aWindowOfSettingsTheRouterDoesNotKnowIsUnsupported() = runTest {
        val (client, server) = pipe()
        val router = ControllerRouter(emptyMap(), failure = 0xFE0002)
        val fake = FakeRouter(this, server, "admin", "secret") { with(router) { handle(it) } }
        val connection = NativeConnection.open(client, "admin", "secret", this)
        try {
            val spec = PackNative(listOf(24, 2), listOf(PackNativeField("uptime", 0x1, "interval")), single = true)
            assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { NativeObjectModel(connection).read(spec) }.category)
        } finally {
            connection.close()
            fake.close()
        }
    }

    @Test
    fun theNamesOfAListAreKeptAndReadAgainWhereANumberIsNotAmongThem() = runTest {
        val pages = mutableMapOf(RESOURCES to listOf(listOf(resource(1, 3, 14))), DEVICES to listOf(listOf(device(1, "gw-1"))))
        val router = ControllerRouter(pages)
        fun asked(handler: Long) = router.asked.count { checkNotNull(it.numbers(M2Key.To)).last() == handler }
        val (client, server) = pipe()
        val fake = FakeRouter(this, server, "admin", "secret") { with(router) { handle(it) } }
        val connection = NativeConnection.open(client, "admin", "secret", this)
        try {
            val model = NativeObjectModel(connection)
            withTimeout(30_000) {
                assertEquals("gw-1", checkNotNull(model.read(resources)).single()["device"])
                model.read(resources)
                assertEquals(2, asked(RESOURCES))
                assertEquals(1, asked(DEVICES))
                pages[RESOURCES] = listOf(listOf(resource(1, 3, 14), resource(2, 1, 1)))
                pages[DEVICES] = listOf(listOf(device(1, "gw-1"), device(2, "cap-1")))
                assertEquals(listOf("gw-1", "cap-1"), checkNotNull(model.read(resources)).map { it["device"] })
                assertEquals(2, asked(DEVICES))
            }
        } finally {
            connection.close()
            fake.close()
        }
    }

    @Test
    fun theRouterGivesTheListInPagesAndTheCursorGoesBackAsItCame() {
        val router = ControllerRouter(mapOf(DEVICES to listOf(listOf(device(1, "a")), listOf(device(2, "b")), listOf(device(3, "c")))))
        read(router, PackNative(listOf(167, DEVICES), listOf(PackNativeField("identity", 0x2009, "string")))) { items ->
            assertEquals(listOf("a", "b", "c"), items.map { it["identity"] })
            assertEquals(listOf<Long?>(null, 1L, 2L), router.asked.map { it.number(0xFE0003) })
            assertEquals(0x10000005L, router.asked.first().number(0xFE000C))
        }
    }

    @Test
    fun aListLongerThanTheLimitIsNotReadToTheEnd() {
        val router = ControllerRouter(mapOf(DEVICES to listOf(listOf(device(1, "a"), device(2, "b")), listOf(device(3, "c")), listOf(device(4, "d")))))
        runTest {
            val (client, server) = pipe()
            val fake = FakeRouter(this, server, "admin", "secret") { with(router) { handle(it) } }
            val connection = NativeConnection.open(client, "admin", "secret", this)
            try {
                val spec = PackNative(listOf(167, DEVICES), listOf(PackNativeField("identity", 0x2009, "string")))
                assertEquals(null, NativeObjectModel(connection).read(spec, limit = 2).also { assertEquals(2, router.asked.size) })
                assertEquals(4, NativeObjectModel(connection).read(spec, limit = 4)?.size)
            } finally {
                connection.close()
                fake.close()
            }
        }
    }

    @Test
    fun aTableThatStandsInForAMenuIsNotTakenWholeWhenTheRecordOfTheCursorWent() {
        val router = ControllerRouter(mapOf(DEVICES to listOf(listOf(device(1, "a")), listOf(device(2, "b")))), gone = 1)
        val spec = PackNative(listOf(167, DEVICES), listOf(PackNativeField("identity", 0x2009, "string")))
        read(router, spec) { items -> assertEquals(listOf("a"), items.map { it["identity"] }) }
        runTest {
            val (client, server) = pipe()
            val fake = FakeRouter(this, server, "admin", "secret") { with(router) { handle(it) } }
            val connection = NativeConnection.open(client, "admin", "secret", this)
            try {
                assertEquals(ErrorCategory.MissingItem, assertFailsWith<DeviceException> { NativeObjectModel(connection).read(spec.copy(whole = true)) }.category)
            } finally {
                connection.close()
                fake.close()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun dumped(router: SubscribedRouter, expected: Int, block: suspend CoroutineScope.(List<Item>?, List<List<Item>>) -> Unit) = runTest {
        val (client, server) = pipe()
        val fake = FakeRouter(this, server, "admin", "secret") { with(router) { handle(it) } }
        val connection = NativeConnection.open(client, "admin", "secret", this)
        try {
            val pages = ArrayList<List<Item>>()
            val spec = PackNative(listOf(167, DEVICES), listOf(PackNativeField("identity", 0x2009, "string")))
            val items = withTimeout(30_000) { NativeObjectModel(connection, time = testTimeSource).dump(spec, expected) { pages += it } }
            block(items, pages)
        } finally {
            connection.close()
            fake.close()
        }
    }

    private fun commands(router: SubscribedRouter) = router.asked.map { it.number(M2Key.Command) }

    @Test
    fun aSubscriptionGivesTheTableInPagesAndAPageOfGetallFromTheLastIdEndsIt() {
        val router = SubscribedRouter((1L..5L).map { device(it, "d$it") })
        dumped(router, 5) { items, pages ->
            assertEquals((1..5).map { "d$it" }, items?.map { it["identity"] })
            assertEquals(listOf(2, 2, 1), pages.map { it.size })
            assertEquals(listOf(SUBSCRIBE, UNSUBSCRIBE, GET_ALL), commands(router))
            assertEquals(5L, router.asked.last().number(0xFE0003))
        }
    }

    @Test
    fun theRecordsARouterLeftOutOfTheSubscriptionAreReadByGetall() {
        val router = SubscribedRouter((1L..5L).map { device(it, "d$it") }, dumped = 3)
        dumped(router, 5) { items, pages ->
            assertEquals((1..5).map { "d$it" }, items?.map { it["identity"] })
            assertEquals(listOf(2, 1, 2), pages.map { it.size })
        }
    }

    @Test
    fun aRecordRemovedWhileTheSubscriptionGivesTheTableIsNotInIt() {
        val router = SubscribedRouter((1L..4L).map { device(it, "d$it") }, gone = listOf(2))
        dumped(router, 5) { items, _ -> assertEquals(listOf("d1", "d3", "d4"), items?.map { it["identity"] }) }
    }

    @Test
    fun theRowsThatWentDuringTheReadComeAsRemovalsAfterTheEndOfTheFirstState() {
        val router = SubscribedRouter((1L..4L).map { device(it, "d$it") }, gone = listOf(2), counted = true)
        dumped(router, 4) { items, _ ->
            assertEquals(listOf("d1", "d3", "d4"), items?.map { it["identity"] })
            withTimeout(5_000) { while (UNSUBSCRIBE !in commands(router)) delay(10) }
            assertEquals(listOf(SUBSCRIBE, UNSUBSCRIBE), commands(router))
        }
    }

    @Test
    fun aTableThatKeepsChangingIsReadForTheTimeOfTheRemovalsAfterItsEndAndNoLonger() {
        val router = SubscribedRouter((1L..4L).map { device(it, "d$it") }, counted = true, later = (5L..8L).map { device(it, "d$it") })
        dumped(router, 4) { items, _ ->
            assertEquals((1..6).map { "d$it" }, items?.map { it["identity"] })
            withTimeout(5_000) { while (UNSUBSCRIBE !in commands(router)) delay(10) }
        }
    }

    @Test
    fun aRecordRemovedBeforeItsPageOfTheFirstStateCameIsNotInIt() {
        val router = SubscribedRouter((1L..4L).map { device(it, "d$it") }, early = listOf(3))
        dumped(router, 4) { items, _ -> assertEquals(listOf("d1", "d2", "d4"), items?.map { it["identity"] }) }
    }

    @Test
    fun aRouterThatSendsNoFirstStateLeavesTheTableToGetall() {
        val router = SubscribedRouter((1L..3L).map { device(it, "d$it") }, first = false)
        dumped(router, 3) { items, pages ->
            assertEquals(null, items)
            assertEquals(emptyList(), pages)
            withTimeout(5_000) { while (UNSUBSCRIBE !in commands(router)) delay(10) }
            assertEquals(listOf(SUBSCRIBE, UNSUBSCRIBE), commands(router))
        }
    }

    @Test
    fun aTupleAmongTheShapesOfAValueIsThereWhenItsPartsAre() = read(
        ControllerRouter(
            mapOf(
                RESOURCES to listOf(
                    listOf(
                        M2Message.of(0xFE0001 to M2Value.U8(1), 5 to M2Value.U32(0x0909_090A)),
                        M2Message.of(0xFE0001 to M2Value.U8(2), 6 to M2Value.Text("ns1.example.")),
                        M2Message.of(0xFE0001 to M2Value.U8(3), 9 to M2Value.U8(10), 10 to M2Value.Text("mail.example.")),
                        M2Message.of(0xFE0001 to M2Value.U8(4)),
                    ),
                ),
            ),
        ),
        PackNative(
            listOf(167, RESOURCES),
            listOf(
                PackNativeField(
                    "data", 5, "union",
                    members = listOf(
                        PackNativeField("data", 5, "ipaddr", letter = "u"),
                        PackNativeField("data", 6, "string", letter = "s"),
                        PackNativeField(
                            "data", NATIVE_NO_KEY, "tuple",
                            parts = listOf(PackNativeField("data", 9, "number", letter = "u"), PackNativeField("data", 10, "string", letter = "s")),
                        ),
                    ),
                ),
            ),
        ),
    ) { items -> assertEquals(listOf("10.9.9.9", "ns1.example.", "10 mail.example.", null), items.map { it["data"] }) }

    private fun route(id: Int, address: Long) = M2Message.of(0xFE0001 to M2Value.U8(id), 0x1 to M2Value.U32(address))

    private val routeTable = PackNative(listOf(44, 21), listOf(PackNativeField("gateway", 0x1, "ipaddr", letter = "u")), whole = true)

    private fun routes(router: RouteRouter, limit: Int? = null, block: suspend CoroutineScope.(List<Item>?) -> Unit) = runTest {
        val (client, server) = pipe()
        val fake = FakeRouter(this, server, "admin", "secret") { with(router) { handle(it) } }
        val connection = NativeConnection.open(client, "admin", "secret", this)
        try {
            withTimeout(30_000) { block(NativeObjectModel(connection).read(routeTable, limit)) }
        } finally {
            connection.close()
            fake.close()
        }
    }

    private val routePages = listOf(listOf(route(1, 1), route(2, 2)), listOf(route(3, 3)), listOf(route(4, 4)))

    @Test
    fun aReadTheRouterKeepsGoesOnUnderItsFirstCursorToAnEmptyPage() {
        val router = RouteRouter(routePages)
        routes(router) { items ->
            assertEquals(listOf("*1", "*2", "*3", "*4"), items.orEmpty().map { it.id })
            assertEquals(listOf(null, 0xFFFFF001L, 0xFFFFF001L, 0xFFFFF001L), router.asked.map { it.number(0xFE0003) })
        }
    }

    @Test
    fun aReadTheRouterKeepsEndsWithNoMoreObjectsAndNotWithAChangedTable() = routes(RouteRouter(routePages, ending = 0xFE0004)) { items ->
        assertEquals(listOf("*1", "*2", "*3", "*4"), items.orEmpty().map { it.id })
    }

    @Test
    fun aReadTheRouterKeepsIsStoppedWhenTheTableHasMoreRowsThanTheWindowShows() {
        val router = RouteRouter(routePages)
        routes(router, limit = 2) { items ->
            assertNull(items)
            delay(100)
            assertEquals(listOf(0xFFFFF001L), router.stopped.map { it.number(0xFE0003) })
        }
    }

    @Test
    fun theAnswerThatThereAreNoMoreObjectsEndsTheList() = read(
        ControllerRouter(mapOf(DEVICES to emptyList())),
        PackNative(listOf(167, DEVICES), listOf(PackNativeField("identity", 0x2009, "string"))),
    ) { items -> assertEquals(emptyList(), items) }

    private fun unsupportedWith(code: Long) = runTest {
        val (client, server) = pipe()
        val router = ControllerRouter(emptyMap(), failure = code)
        val fake = FakeRouter(this, server, "admin", "secret") { with(router) { handle(it) } }
        val connection = NativeConnection.open(client, "admin", "secret", this)
        try {
            val error = assertFailsWith<DeviceException> { NativeObjectModel(connection).read(resources) }
            assertEquals(ErrorCategory.Unsupported, error.category)
        } finally {
            connection.close()
            fake.close()
        }
    }

    @Test
    fun aHandlerTheRouterDoesNotKnowIsUnsupported() = unsupportedWith(0xFE0002)

    @Test
    fun aCommandTheHandlerDoesNotImplementIsUnsupported() = unsupportedWith(0xFE0003)

    @Test
    fun valuesAreWrittenOutTheWayTheConsoleWritesThem() {
        fun write(field: PackNativeField, value: M2Value) = written(field, value, null)
        assertEquals("stable", write(PackNativeField("channel", 1, "enm", names = mapOf(3L to "stable")), M2Value.U8(3)))
        assertEquals("4d2h37m32s", write(PackNativeField("uptime", 1, "interval"), M2Value.U32(355_052)))
        assertEquals("1d", write(PackNativeField("age", 1, "interval", scale = 1000), M2Value.U32(86_400_000)))
        assertEquals("500ms", write(PackNativeField("timeout", 1, "interval", scale = 1000), M2Value.U32(500)))
        assertEquals("1m1s500ms", write(PackNativeField("timeout", 1, "interval", scale = 1000), M2Value.U32(61_500)))
        assertEquals("2s50ms", write(PackNativeField("timeout", 1, "interval", scale = 100), M2Value.U32(205)))
        assertEquals("1.5", write(PackNativeField("level", 1, "fixedpoint", scale = 10), M2Value.U32(15)))
        assertEquals("-0.05", write(PackNativeField("level", 1, "fixedpoint", scale = 100), M2Value.U32(-5)))
        assertEquals("true", write(PackNativeField("flag", 1, "bool"), M2Value.Bool(true)))
        assertEquals("false", write(PackNativeField("flag", 1, "bool"), M2Value.U8(0)))
        assertEquals("-2", write(PackNativeField("level", 1, "integer"), M2Value.U32(4_294_967_294)))
        assertEquals("1,2", write(PackNativeField("alerts", 1, "multinumber"), M2Value.U32s(longArrayOf(1, 2))))
        assertEquals("high,2", write(PackNativeField("alerts", 1, "multinumber", names = mapOf(1L to "high")), M2Value.U32s(longArrayOf(1, 2))))
        assertEquals("cpu,ram", written(PackNativeField("alerts", 1, "multinumber"), M2Value.U32s(longArrayOf(1, 2)), mapOf(1L to "cpu", 2L to "ram")))
        assertEquals("a,b", write(PackNativeField("labels", 1, "multistring"), M2Value.Texts(listOf("a", "b"))))
        assertEquals("00:00:5E:00:53:01", write(PackNativeField("mac", 1, "macaddr"), M2Value.Raw(byteArrayOf(0, 0, 94, 0, 83, 1))))
        assertEquals("192.0.2.5", write(PackNativeField("address", 1, "ipaddr"), M2Value.U32(0x0502_00C0)))
        assertEquals("true", write(PackNativeField("running", 1, "flag"), M2Value.U8(1)))
        assertEquals("7.26beta1", write(PackNativeField("version", 1, "version"), M2Value.Text("7.26beta1")))
        assertEquals("2048", write(PackNativeField("size", 1, "kbytes"), M2Value.U32(2)))
        assertEquals("125000", write(PackNativeField("rate", 1, "bigbitrate"), M2Value.U64(125_000)))
        assertEquals(null, write(PackNativeField("nested", 1, "addr"), M2Value.Message(M2Message(emptyList()))))
    }

    @Test
    fun theKindsOfFieldsTheDescriptionsNameAreWrittenOutToo() {
        fun write(field: PackNativeField, value: M2Value, paired: M2Value? = null) = written(field, value, null, paired)
        val states = PackNativeField("states", 1, "set", names = mapOf(0L to "established", 1L to "related", 3L to "new"))
        assertEquals("established,related,new,4", write(states, M2Value.U32(0b1_1011)))
        assertEquals("", write(states, M2Value.U8(0)))
        assertEquals("on", write(PackNativeField("mode", 1, "enm", names = mapOf(0L to "off", 1L to "on")), M2Value.Bool(true)))
        assertEquals("false", write(PackNativeField("mode", 1, "bool"), M2Value.Bool(false)))
        assertEquals("A1B2", write(PackNativeField("key", 1, "raw"), M2Value.Raw(byteArrayOf(-95, -78))))
        assertEquals("01,02", write(PackNativeField("keys", 1, "multiraw"), M2Value.Raws(listOf(byteArrayOf(1), byteArrayOf(2)))))
        assertEquals("5000000000000,1", write(PackNativeField("counters", 1, "multibignumber"), M2Value.U64s(longArrayOf(5_000_000_000_000, 1))))
        assertEquals("true,false", write(PackNativeField("marks", 1, "multinumber"), M2Value.Bools(booleanArrayOf(true, false))))
        assertEquals("192.0.2.5,10.0.0.1", write(PackNativeField("servers", 1, "multiipaddr"), M2Value.U32s(longArrayOf(0x0502_00C0, 0x0100_000A))))
        assertEquals("::1,2001:db8::1", write(PackNativeField("servers", 1, "multiip6addr"), M2Value.Address6s(listOf(ByteArray(16).also { it[15] = 1 }, byteArrayOf(32, 1, 13, -72, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))))
    }

    @Test
    fun anAddressWithItsMaskIsWrittenAsANetworkAndARangeByItsSpan() {
        fun network(address: String, mask: Long, range: Boolean = false): String? {
            val value = address.split('.').foldIndexed(0L) { at, all, octet -> all or (octet.toLong() shl (8 * at)) }
            return written(PackNativeField("address", 1, "network", range = range), M2Value.U32(value), null, M2Value.U32(mask))
        }
        fun last(address: String) = address.split('.').foldIndexed(0L) { at, all, octet -> all or (octet.toLong() shl (8 * at)) }
        assertEquals("192.168.35.1/24", network("192.168.35.1", 0x00FF_FFFF))
        assertEquals("10.0.0.0/8", network("10.0.0.0", 0x0000_00FF))
        assertEquals("0.0.0.0/0", network("0.0.0.0", 0))
        assertEquals("192.0.2.74", network("192.0.2.74", last("192.0.2.74"), range = true))
        assertEquals("192.0.2.0/24", network("192.0.2.0", last("192.0.2.255"), range = true))
        assertEquals("192.0.2.0/30", network("192.0.2.0", last("192.0.2.3"), range = true))
        assertEquals("192.0.2.10-192.0.2.20", network("192.0.2.10", last("192.0.2.20"), range = true))
        assertEquals("192.0.2.1-192.0.2.4", network("192.0.2.1", last("192.0.2.4"), range = true))
        assertEquals("192.0.2.7", written(PackNativeField("address", 1, "network"), M2Value.U32(last("192.0.2.7")), null, null))
        val six = PackNativeField("prefix", 1, "network6")
        val loopback = ByteArray(16).also { it[15] = 1 }
        assertEquals("::1/128", written(six, M2Value.Address6(loopback), null, M2Value.U8(128)))
        assertEquals("fe80::5054:ff:fe12:3457/64", written(six, M2Value.Address6(byteArrayOf(-2, -128, 0, 0, 0, 0, 0, 0, 80, 84, 0, -1, -2, 18, 52, 87)), null, M2Value.U8(64)))
        assertEquals("2001:db8::1", written(PackNativeField("address", 1, "ip6addr"), M2Value.Address6(byteArrayOf(32, 1, 13, -72, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)), null))
    }

    @Test
    fun clocksDatesAndOffsetsAreWrittenAsTheConsoleWritesThem() {
        fun write(kind: String, number: Long) = written(PackNativeField("x", 1, kind), M2Value.U32(number), null)
        assertEquals("255.255.255.0", write("netmask", 0x00FF_FFFF))
        assertEquals("01:01:01", write("clocktime", 3_661))
        assertEquals("01:00", write("clocktime", 3_600))
        assertEquals("2026-07-25", write("clockdate", 1_784_975_092))
        assertEquals("2026-07-25 10:24:52", write("dateandtime", 1_784_975_092))
        assertEquals("2026-09-20 16:27:49", write("dateandtime", 1_789_921_669))
        assertEquals("1970-01-01 00:00:00", write("dateandtime", 0))
        assertEquals("2000-02-29 00:00:00", write("dateandtime", 951_782_400))
        assertEquals("2099-12-31 23:59:59", write("dateandtime", 4_102_444_799))
        assertEquals("+02:00", write("timezone", 7_200))
        assertEquals("-05:30", write("timezone", 4_294_967_296 - 19_800))
        assertEquals("+00:00", write("timezone", 0))
    }

    @Test
    fun anAddressOfTheDescriptionsIsWrittenWithTheMarksOfTheConsole() {
        val field = PackNativeField("address", 1, "addr")
        val interfaces = mapOf(2L to "ether1")
        fun write(vararg parts: Pair<Int, M2Value>) = written(field, M2Value.Message(M2Message.of(*parts)), interfaces)
        val host = 0x0502_00C0L
        assertEquals("192.0.2.5", write(0xFEFF1F to M2Value.U8(1), 0xFEFF20 to M2Value.U32(host)))
        assertEquals("192.0.2.5/24%ether1", write(0xFEFF1F to M2Value.U8(1), 0xFEFF20 to M2Value.U32(host), 0xFEFF25 to M2Value.U32(24), 0xFEFF22 to M2Value.U32(2)))
        assertEquals("ether1", write(0xFEFF1F to M2Value.U8(9), 0xFEFF22 to M2Value.U32(2)))
        assertEquals("7", write(0xFEFF1F to M2Value.U8(9), 0xFEFF22 to M2Value.U32(7)))
        assertEquals("::1/128", write(0xFEFF21 to M2Value.Address6(ByteArray(16).also { it[15] = 1 }), 0xFEFF25 to M2Value.U32(128)))
        assertEquals("example.net", write(0xFEFF26 to M2Value.Text("example.net")))
        assertEquals("10.0.0.1@3", write(0xFEFF20 to M2Value.U32(0x0100_000AL), 0xFEFF23 to M2Value.U32(3)))
        assertEquals("00:00:5E:00:53:01", write(0xFEFF2F to M2Value.Raw(byteArrayOf(0, 0, 94, 0, 83, 1))))
    }

    @Test
    fun pairsRangesAndUnitsAreWrittenAsTheDescriptionsShowThem() {
        fun write(kind: String, number: Long, paired: M2Value? = null, postfix: String? = null) =
            written(PackNativeField("x", 1, kind, postfix = postfix), M2Value.U32(number), null, paired)
        assertEquals("3..4", write("numberrange", 3, M2Value.U32(4)))
        assertEquals("3", write("numberrange", 3, M2Value.U32(3)))
        assertEquals("3", write("numberrange", 3))
        val list = PackNativeField("ports", 1, "numberrangelist")
        assertEquals("80..90", written(list, M2Value.U32s(longArrayOf(80, 90)), null))
        assertEquals("80, 90", written(list, M2Value.U32s(longArrayOf(80, 80, 90, 90)), null))
        assertEquals("80..90, 443", written(list, M2Value.U32s(longArrayOf(80, 90, 443, 443)), null))
        assertEquals("999 bps", write("unit", 999, postfix = "bps"))
        assertEquals("1.0 kbps", write("unit", 1_000, postfix = "bps"))
        assertEquals("1.2 kbps", write("unit", 1_234, postfix = "bps"))
        assertEquals("1.5 Mbps", write("unit", 1_500_000, postfix = "bps"))
        assertEquals("10.0 Gbps", write("unit", 10_000_000_000, postfix = "bps"))
        assertEquals("2.5 Mbits/s", written(PackNativeField("x", 1, "bigunit", postfix = "bits/s"), M2Value.U64(2_500_000), null))
        assertEquals("1.5k", write("unit", 1_500))
        assertEquals("192.0.2.5:8080", write("ipaddrandport", 0x0502_00C0, M2Value.U32(8080)))
        assertEquals("192.0.2.5", write("ipaddrandport", 0x0502_00C0))
        val mac = byteArrayOf(0, 0, 94, 0, 83, 1)
        assertEquals("00:00:5E:00:53:01/FF:FF:FF:00:00:00", written(PackNativeField("x", 1, "macnetwork"), M2Value.Raw(mac), null, M2Value.Raw(byteArrayOf(-1, -1, -1, 0, 0, 0))))
        val flags = PackNativeField("flags", 1, "multitristate", names = mapOf(0L to "fin", 1L to "syn", 4L to "ack"))
        assertEquals("syn,!ack", written(flags, M2Value.U32(0b00010), null, M2Value.U32(0b10000)))
        assertEquals("syn,!ack", written(flags, M2Value.U32(0b00010), null, M2Value.U32(0b10010)))
        assertEquals("syn", written(flags, M2Value.U32(0b00010), null, M2Value.U32(0)))
        assertEquals("!fin,!ack", written(flags, M2Value.U32(0), null, M2Value.U32(0b10001)))
        assertEquals("syn", written(flags, M2Value.U32(0b00010), null))
    }

    @Test
    fun aValueThatMayTakeSeveralShapesIsShownInTheShapeTheRecordHolds() = read(
        ControllerRouter(
            mapOf(
                RESOURCES to listOf(
                    listOf(
                        M2Message.of(0xFE0001 to M2Value.U8(1), 3 to M2Value.U32(0x0002_00C0), 4 to M2Value.U32(0xFF02_00C0)),
                        M2Message.of(0xFE0001 to M2Value.U8(2), 8 to M2Value.Text("example.net")),
                        M2Message.of(0xFE0001 to M2Value.U8(3)),
                    ),
                ),
            ),
        ),
        PackNative(
            listOf(167, RESOURCES),
            listOf(
                PackNativeField(
                    "address", 3, "union",
                    members = listOf(
                        PackNativeField("address", 3, "network", letter = "u", pair = 4, range = true),
                        PackNativeField("address", 8, "string", letter = "s"),
                    ),
                ),
            ),
        ),
    ) { items -> assertEquals(listOf("192.0.2.0/24", "example.net", null), items.map { it["address"] }) }

    @Test
    fun aNegatedFieldIsShownWithAMarkWhileItsFlagIsUp() = read(
        ControllerRouter(
            mapOf(
                RESOURCES to listOf(
                    listOf(
                        M2Message.of(0xFE0001 to M2Value.U8(1), 0x31 to M2Value.Bool(true), 0x32 to M2Value.U32(0x0002_00C0), 0x33 to M2Value.U32(0x00FF_FFFF)),
                        M2Message.of(0xFE0001 to M2Value.U8(2), 0x31 to M2Value.Bool(false), 0x32 to M2Value.U32(0x0002_00C0), 0x33 to M2Value.U32(0x00FF_FFFF)),
                    ),
                ),
            ),
        ),
        PackNative(listOf(167, RESOURCES), listOf(PackNativeField("source", 0x32, "network", letter = "u", pair = 0x33, negation = 0x31))),
    ) { items -> assertEquals(listOf("!192.0.2.0/24", "192.0.2.0/24"), items.map { it["source"] }) }

    @Test
    fun twoFieldsOfOneKeyAreToldApartByTheLetterOfTheId() = read(
        ControllerRouter(
            mapOf(
                RESOURCES to listOf(
                    listOf(
                        M2Message.of(0xFE0001 to M2Value.U8(1), 0x30 to M2Value.U32s(longArrayOf(7, 8)), 0x30 to M2Value.U8(5)),
                        M2Message.of(0xFE0001 to M2Value.U8(2), 0x30 to M2Value.U8(6), 0x30 to M2Value.U32s(longArrayOf(9))),
                    ),
                ),
            ),
        ),
        PackNative(
            listOf(167, RESOURCES),
            listOf(PackNativeField("one", 0x30, "number", letter = "u"), PackNativeField("many", 0x30, "multinumber", letter = "U")),
        ),
    ) { items ->
        assertEquals(mapOf("one" to "5", "many" to "7,8"), items[0].values)
        assertEquals(mapOf("one" to "6", "many" to "9"), items[1].values)
    }

    @Test
    fun anOptionalFieldIsShownOnlyWhileItsFlagIsNotDown() = read(
        ControllerRouter(
            mapOf(
                RESOURCES to listOf(
                    listOf(
                        M2Message.of(0xFE0001 to M2Value.U8(1), 0x31 to M2Value.Bool(true), 0x32 to M2Value.U8(9)),
                        M2Message.of(0xFE0001 to M2Value.U8(2), 0x31 to M2Value.Bool(false), 0x32 to M2Value.U8(9)),
                        M2Message.of(0xFE0001 to M2Value.U8(3), 0x32 to M2Value.U8(9)),
                    ),
                ),
            ),
        ),
        PackNative(listOf(167, RESOURCES), listOf(PackNativeField("optional", 0x32, "number", letter = "u", flag = 0x31))),
    ) { items -> assertEquals(listOf("9", null, "9"), items.map { it["optional"] }) }

    @Test
    fun flagLettersListsOfRangesAndNetworksAreWrittenAsTheDescriptionsShowThem() {
        fun address(text: String) = text.split('.').foldIndexed(0L) { at, all, octet -> all or (octet.toLong() shl (8 * at)) }
        val status = PackNativeField("status", 1, "numflag", names = mapOf(0L to "S", 2L to "R", 4L to "A"))
        assertEquals("R", written(status, M2Value.U8(2), null))
        assertEquals("A", written(status, M2Value.U32(4), null))
        assertEquals("S", written(status, M2Value.U32(0), null))
        assertEquals("", written(status, M2Value.U8(11), null))
        assertEquals("100..200, 300", written(PackNativeField("vlans", 1, "multinumberrange"), M2Value.U32s(longArrayOf(100, 200, 300, 300)), null))
        fun community(asn: Long, value: Long) = (asn shl 16) or value
        val communities = M2Value.U32s(longArrayOf(community(65_000, 100), community(65_000, 100), community(65_000, 200), community(65_001, 300)))
        assertEquals("65000:100, 65000:200..65001:300", written(PackNativeField("communities", 1, "multicommunity"), communities, null))
        assertEquals("0s-1d, 8h-12h", written(PackNativeField("mon", 1, "multiintervalrange", separator = "-"), M2Value.U32s(longArrayOf(0, 86_400, 28_800, 43_200)), null))
        val pools = PackNativeField("pools", 1, "multinetwork", range = true)
        assertEquals("192.0.2.0/24,192.0.2.10-192.0.2.20", written(pools, M2Value.U32s(longArrayOf(address("192.0.2.0"), address("192.0.2.255"), address("192.0.2.10"), address("192.0.2.20"))), null))
        assertEquals("10.0.0.0/8", written(PackNativeField("nets", 1, "multinetwork"), M2Value.U32s(longArrayOf(address("10.0.0.0"), 0x0000_00FF)), null))
        assertEquals("true", written(PackNativeField("lbt", 1, "group"), M2Value.Bool(true), null))
        assertEquals("65000", written(PackNativeField("local", 1, "as"), M2Value.U32(65_000), null))
        assertEquals("a\nb", written(PackNativeField("source", 1, "multilinestring"), M2Value.Text("a\nb"), null))
    }

    @Test
    fun aListOfMessagesIsWrittenElementByElementWithTheNamesOfItsParts() {
        val interfaces = PackNativeRef(listOf(167, DEVICES), listOf(0x2009))
        val six = ByteArray(16).also { it[0] = -2; it[1] = -128; it[15] = 1 }
        val record = M2Message.of(
            0xFE0001 to M2Value.U8(1),
            0x0F to M2Value.Messages(
                listOf(
                    M2Message.of(0xFEFF1F to M2Value.U8(1), 0xFEFF20 to M2Value.U32(0x0502_00C0), 0xFEFF22 to M2Value.U32(1)),
                    M2Message.of(0xFEFF26 to M2Value.Text("example.net")),
                ),
            ),
            0x93 to M2Value.Messages(listOf(M2Message.of(0x16 to M2Value.U32(0x0102_00C0), 0x0E to M2Value.U8(5)), M2Message.of(0x16 to M2Value.U32(0x0202_00C0)))),
            0x05 to M2Value.Messages(listOf(M2Message.of(0xA4 to M2Value.Address6(six), 0x03 to M2Value.U32(2)))),
        )
        fun list(name: String, key: Int, element: PackNativeField) = PackNativeField(name, key, "multi", letter = "M", parts = listOf(element))
        fun tuple(name: String, separator: String, vararg parts: PackNativeField) = PackNativeField(name, NATIVE_NO_KEY, "tuple", separator = separator, parts = parts.toList())
        val fields = listOf(
            list("dns", 0x0F, PackNativeField("dns", NATIVE_NO_KEY, "addr", refers = interfaces)),
            list("routes", 0x93, tuple("routes", "dist.", PackNativeField("routes", 0x16, "ipaddr", letter = "u"), PackNativeField("routes", 0x0E, "number", letter = "u"))),
            list("relay", 0x05, tuple("relay", "%", PackNativeField("relay", 0xA4, "ip6addr", letter = "a"), PackNativeField("relay", 0x03, "enm", letter = "u", refers = interfaces))),
        )
        val pages = mapOf(RESOURCES to listOf(listOf(record)), DEVICES to listOf(listOf(device(1, "ether1"), device(2, "ether2"))))
        read(ControllerRouter(pages), PackNative(listOf(167, RESOURCES), fields)) { items ->
            assertEquals(mapOf("dns" to "192.0.2.5%ether1, example.net", "routes" to "192.0.2.1 dist. 5, 192.0.2.2", "relay" to "fe80::1%ether2"), items.single().values)
        }
    }
}
