package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.packs.NATIVE_NO_KEY
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.packs.PackNativeField
import com.vasmarfas.networkhelm.packs.PackNativeRef
import com.vasmarfas.networkhelm.packs.PackQuery
import com.vasmarfas.networkhelm.packs.QUERY_NO_COMMAND
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val START = 0xFE000FL
private const val POLL = 0xFE0010L
private const val GET_ALL = 0xFE0004L
private const val CANCEL = 0xFE0011L
private const val NO_MORE = 0xFE0004L
private const val CURSOR = 0xFE0003
private const val ID = 0xFE0001
private const val FINISHED = 0xFE000B

private class Router(
    var log: Boolean = true,
    var rows: Int = 0,
    var startError: Pair<Long, String>? = null,
    var missing: Long? = null,
    var fresh: Boolean = false,
    var ends: Boolean = false,
    var idless: Boolean = false,
    var tables: List<List<Int>> = emptyList(),
    var arrives: Boolean = false,
    var holds: Boolean = false,
    var state: List<Pair<Int, M2Value>> = listOf(1 to M2Value.Text("checked")),
    var unanswered: Long? = null,
) {
    val asked = ArrayList<M2Message>()
    private var served = 0
    private var chunk = 0
    private var arrived = false
    private var parked: Pair<FakeRouter, M2Message>? = null

    fun after(command: Long) = asked.filter { it.number(M2Key.Command) == command }

    suspend fun release() {
        val (router, message) = parked ?: return
        parked = null
        val at = message.number(CURSOR)?.toInt() ?: 0
        served++
        router.reply(message, 0xFE0002 to M2Value.Messages(listOf(M2Message.of(ID to M2Value.U8(at), 0xF to M2Value.U8(at), 1 to M2Value.U32(0x0202000A)))), CURSOR to M2Value.U8(at + 1))
    }

    suspend fun FakeRouter.handle(message: M2Message) {
        val command = message.number(M2Key.Command) ?: return
        asked += message
        when {
            command == unanswered -> Unit
            command == missing -> reply(message, M2Key.ErrorCode to M2Value.U32(0xFE0003))
            command == GET_ALL && message.numbers(M2Key.To)?.toList()?.takeLast(2) == listOf(20L, 0L) ->
                reply(message, 0xFE0002 to M2Value.Messages(listOf(M2Message.of(ID to M2Value.U8(5), 1 to M2Value.Text("ether1")))))
            command == START || command == 2L -> startError?.let { (code, text) -> reply(message, M2Key.ErrorCode to M2Value.U32(code), M2Key.Error to M2Value.Text(text)) } ?: if (idless) reply(message) else reply(message, ID to M2Value.U8(7))
            command == CANCEL -> reply(message)
            command == POLL -> reply(message, *state.toTypedArray(), FINISHED to M2Value.Bool(true))
            command == GET_ALL && tables.isNotEmpty() -> {
                val ids = tables[minOf(chunk++, tables.lastIndex)]
                if (ids.isEmpty()) reply(message) else reply(message, 0xFE0002 to M2Value.Messages(ids.map { id -> M2Message.of(ID to M2Value.U8(id), 1 to M2Value.Text("row$id")) }))
            }
            command == GET_ALL && fresh -> {
                val rows = listOf(2 * chunk + 1, 2 * chunk + 2).map { M2Message.of(ID to M2Value.U8(it), 1 to M2Value.Text("row$it")) }
                chunk++
                reply(message, 0xFE0002 to M2Value.Messages(rows))
            }
            command == GET_ALL && log && holds && message.number(CURSOR) != null && (message.number(CURSOR)?.toInt() ?: 0) >= rows -> parked = this to message
            command == GET_ALL && log -> {
                val at = message.number(CURSOR)?.toInt() ?: if (arrived) served else 0
                if (message.number(ID) != 7L || at != served || at >= rows) {
                    reply(message, M2Key.ErrorCode to M2Value.U32(NO_MORE))
                    if (arrives && !arrived && at >= rows) {
                        rows++
                        arrived = true
                    }
                } else {
                    served++
                    val last = ends && at == rows - 1
                    reply(
                        message,
                        0xFE0002 to M2Value.Messages(listOf(M2Message.of(ID to M2Value.U8(at), 0xF to M2Value.U8(at), 1 to M2Value.U32(0x0202000A)))),
                        *listOfNotNull((CURSOR to M2Value.U8(at + 1)).takeIf { !last }, (FINISHED to M2Value.Bool(true)).takeIf { last }).toTypedArray(),
                    )
                }
            }
            command == GET_ALL -> reply(
                message,
                0xFE0002 to M2Value.Messages((0 until rows).map { M2Message.of(ID to M2Value.U8(it + 1), 1 to M2Value.Text("hop$it"), 2 to M2Value.U32(0)) }),
            )
        }
    }
}

class NativeQueryTest {

    private fun query(action: Boolean = false, poll: Long = GET_ALL, cancel: Long = CANCEL, start: Long = START) = PackNative(
        listOf(22),
        listOf(PackNativeField("seq", 0xF, "number", letter = "u"), PackNativeField("host", 1, "ipaddr", letter = "u"), PackNativeField("status", 1, "string", letter = "s")),
        query = PackQuery(
            action,
            listOf(
                PackNativeField("address", 0x16, "addr", letter = "m", writable = true),
                PackNativeField("count", 0x11, "number", letter = "u", writable = true),
                PackNativeField("speed", 0x24, "bigunit", letter = "q", postfix = "bps", writable = true),
                PackNativeField("sizes", 0x29, "numberrange", letter = "u", pair = 0x2A, writable = true),
            ),
            start, cancel, poll,
        ),
    )

    private fun session(router: Router = Router(), block: suspend CoroutineScope.(NativeObjectModel, Router) -> Unit) = runTest {
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

    private fun M2Message.body() = fields.filter { it.key < 0xFF0000 }.map { it.key to it.value }

    @Test
    fun theStartSendsTheFieldsTheUserTouchedInTheTypesTheRouterReads() = session { model, router ->
        model.start(query(), mapOf("address" to "10.0.2.2", "count" to "3", "speed" to "10M", "sizes" to "1000..1500"))
        val asked = router.asked.single()
        assertEquals(START, asked.number(M2Key.Command))
        assertEquals(listOf(22L), asked.numbers(M2Key.To)?.toList()?.takeLast(1))
        val body = asked.body()
        assertEquals(listOf(0x16, 0x11, 0x24, 0x29, 0x2A), body.map { it.first })
        assertEquals(listOf(ADDRESS_V4 to M2Value.U32(0x0202000A)), (body[0].second as M2Value.Message).value.fields.map { it.key to it.value })
        assertEquals(M2Value.U32(3), body[1].second)
        assertEquals(M2Value.U64(10_000_000), body[2].second)
        assertEquals(listOf(M2Value.U32(1000), M2Value.U32(1500)), listOf(body[3].second, body[4].second))
    }

    @Test
    fun aSmallCommandOfADescriptionGoesOutInOneByte() = session { model, router ->
        model.start(query(start = 2), emptyMap())
        assertEquals(M2Value.U8(2), router.asked.single()[M2Key.Command])
    }

    @Test
    fun aRouterThatRefusesTheStartGivesItsTextAsTheError() = session(Router(startError = 0xFE0006L to "device-mode: traffic-gen is not allowed")) { model, _ ->
        val failure = assertFailsWith<DeviceException> { model.start(query(), emptyMap()) }
        assertEquals("device-mode: traffic-gen is not allowed", failure.message)
    }

    @Test
    fun aFieldTheWindowDoesNotHaveIsNotSent() = session { model, router ->
        assertFailsWith<DeviceException> { model.start(query(), mapOf("nothing" to "1")) }
        assertTrue(router.asked.isEmpty())
    }

    @Test
    fun aLogGivesEachRowOnceAndTheNextReadContinuesWhereTheLastEnded() = session(Router(rows = 2)) { model, router ->
        val run = model.start(query(), emptyMap())
        val first = run.read()
        assertEquals(listOf("*0", "*1"), first.rows.map { it.id })
        assertEquals("10.0.2.2", first.rows[0]["host"])
        assertFalse(first.snapshot)
        assertFalse(first.finished)
        router.rows = 3
        val second = run.read()
        assertEquals(listOf("*2"), second.rows.map { it.id })
        assertTrue(run.read().rows.isEmpty())
        val reads = router.after(GET_ALL)
        assertTrue(reads.all { it.number(ID) == 7L })
        assertEquals(listOf(null, 1L, 2L, null, 2L, 3L, 3L), reads.map { it.number(CURSOR) })
    }

    @Test
    fun aLogThatHasEndedGivesItsLastRowWithoutAPlaceForTheNextAndStaysALog() = session(Router(rows = 3, ends = true)) { model, _ ->
        val read = model.start(query(), emptyMap()).read()
        assertEquals(listOf("*0", "*1", "*2"), read.rows.map { it.id })
        assertFalse(read.snapshot)
        assertTrue(read.finished)
    }

    @Test
    fun aRunTheRouterGivesNoNumberIsKnownByTheNumberOfTheObjectTheRequestNames() = session(Router(idless = true)) { model, router ->
        val selector = PackNativeField("interface", 0xFE0001, "number", letter = "u", writable = true)
        val spec = PackNative(listOf(22), emptyList(), query = PackQuery(true, listOf(selector), START, CANCEL, POLL))
        val run = model.start(spec, mapOf("interface" to "14"))
        run.read()
        run.cancel()
        assertEquals(14L, router.after(POLL).single().number(ID))
        assertEquals(14L, router.after(CANCEL).single().number(ID))
    }

    @Test
    fun aTableIsReadWholeEachTimeAndTheRowsReplaceThoseBefore() = session(Router(log = false, rows = 2)) { model, router ->
        val run = model.start(query(), emptyMap())
        val first = run.read()
        assertTrue(first.snapshot)
        assertEquals(listOf("hop0", "hop1"), first.rows.map { it["status"] })
        router.rows = 1
        assertEquals(listOf("hop0"), run.read().rows.map { it["status"] })
        assertTrue(router.after(GET_ALL).all { it.number(CURSOR) == null && it.number(ID) == 7L })
    }

    @Test
    fun aLogThatNamesNoPlaceIsToldFromATableByAnotherReadFromTheStartAndGivesTheRowsOfBoth() = session(Router(fresh = true)) { model, _ ->
        val run = model.start(query(), emptyMap())
        val first = run.read()
        assertFalse(first.snapshot)
        assertEquals(listOf("*1", "*2", "*3", "*4"), first.rows.map { it.id })
        val second = run.read()
        assertFalse(second.snapshot)
        assertEquals(listOf("*5", "*6"), second.rows.map { it.id })
    }

    @Test
    fun aTableWhoseRowsAreAllReplacedBetweenTwoReadsStaysATableAndIsNotAppendedTo() = session(Router(tables = listOf(listOf(1, 2), listOf(1, 2), listOf(3, 4)))) { model, _ ->
        val run = model.start(query(), emptyMap())
        val first = run.read()
        assertTrue(first.snapshot)
        assertEquals(listOf("*1", "*2"), first.rows.map { it.id })
        val second = run.read()
        assertTrue(second.snapshot)
        assertEquals(listOf("*3", "*4"), second.rows.map { it.id })
        assertTrue(run.read().snapshot)
    }

    @Test
    fun aLogWithoutAPlaceThatIsQuietKeepsTheRowsItHasShown() = session(Router(tables = listOf(listOf(1, 2), emptyList(), emptyList(), listOf(3, 4)))) { model, _ ->
        val run = model.start(query(), emptyMap())
        assertEquals(listOf("*1", "*2"), run.read().rows.map { it.id })
        val quiet = run.read()
        assertFalse(quiet.snapshot)
        assertTrue(quiet.rows.isEmpty())
        val later = run.read()
        assertFalse(later.snapshot)
        assertEquals(listOf("*3", "*4"), later.rows.map { it.id })
    }

    @Test
    fun aRowThatComesWhileTheShapeOfALogIsToldIsNotThrownAway() = session(Router(rows = 2, arrives = true)) { model, router ->
        val run = model.start(query(), emptyMap())
        val first = run.read()
        assertFalse(first.snapshot)
        assertEquals(listOf("*0", "*1", "*2"), first.rows.map { it.id })
        router.rows = 4
        assertEquals(listOf("*3"), run.read().rows.map { it.id })
    }

    @Test
    fun aFieldSetToAllIsNotAskedTheTableAboutAgainOnEveryRead() = session(Router(log = false, rows = 2)) { model, router ->
        val reference = PackNativeField("interface", 2, "enm", names = mapOf(0L to "all"), letter = "u", refers = PackNativeRef(listOf(20, 0), listOf(1)))
        val spec = query().let { PackNative(it.handler, it.fields + reference, query = it.query) }
        val run = model.start(spec, emptyMap())
        repeat(5) { assertEquals("all", run.read().rows.first()["interface"]) }
        assertEquals(1, router.asked.count { it.number(M2Key.Command) == GET_ALL && it.numbers(M2Key.To)?.toList()?.takeLast(2) == listOf(20L, 0L) })
    }

    @Test
    fun aLogThatHoldsTheRequestForARowItHasNotGotIsNotAskedAgainAndIsReadWhenTheRowHasCome() = session(Router(rows = 1, holds = true)) { model, router ->
        val run = model.start(query(), emptyMap())
        assertEquals(listOf("*0"), run.read().rows.map { it.id })
        assertTrue(run.read().rows.isEmpty())
        assertEquals(1, router.after(GET_ALL).count { it.number(CURSOR) == 1L })
        router.rows = 2
        router.release()
        assertEquals(listOf("*1"), run.read().rows.map { it.id })
    }

    @Test
    fun aRunWithoutAStartCommandIsKnownByTheSelectorOfTheRequestAndSendsNoStart() = session { model, router ->
        val selector = PackNativeField("interface", 0xFE0001, "number", letter = "u", writable = true)
        val spec = PackNative(listOf(22), emptyList(), query = PackQuery(true, listOf(selector), QUERY_NO_COMMAND, CANCEL, POLL))
        model.start(spec, mapOf("interface" to "14")).read()
        assertTrue(router.after(START).isEmpty())
        assertEquals(14L, router.after(POLL).single().number(ID))
    }

    @Test
    fun aPartOfATupleThatTheRouterLeavesOutIsZeroWhereItIsARate() = session(Router(state = listOf(0xB to M2Value.U64s(longArrayOf(0, 2_000_000))))) { model, _ ->
        val rate = PackNativeField(
            "rate", NATIVE_NO_KEY, "tuple", separator = "/",
            parts = listOf(PackNativeField("tx", 1, "bigbitrate", letter = "Q"), PackNativeField("rx", 0xB, "bigbitrate", letter = "Q")),
        )
        val run = model.start(query(action = true, poll = POLL).let { PackNative(it.handler, listOf(rate), query = it.query) }, emptyMap())
        assertEquals("0/2000000", run.read().rows.single()["rate"])
    }

    @Test
    fun anActionIsPolledWithItsIdAndEndsWithTheFlagOfTheRouter() = session { model, router ->
        val run = model.start(query(action = true, poll = POLL), emptyMap())
        val state = run.read()
        assertTrue(state.snapshot)
        assertTrue(state.finished)
        assertEquals("checked", state.rows.single()["status"])
        assertEquals(7L, router.after(POLL).single().number(ID))
        run.cancel()
        assertEquals(7L, router.after(CANCEL).single().number(ID))
    }

    @Test
    fun aWindowWithoutACancelCommandSendsNone() = session { model, router ->
        model.start(query(action = true, poll = POLL, cancel = 0xFFFFFFFFL), emptyMap()).cancel()
        assertTrue(router.after(CANCEL).isEmpty())
    }

    @Test
    fun aHandlerThatKnowsTheCommandAnswersAndOneThatDoesNotIsGone() = session { model, router ->
        assertTrue(model.answers(query()))
        router.missing = GET_ALL
        assertFalse(model.answers(query()))
    }

    @Test
    fun aQueryThatSaysNothingToAPollBeforeItIsStartedIsAWindow() = session(Router(unanswered = GET_ALL)) { model, _ ->
        assertTrue(model.answers(query()))
    }

    @Test
    fun aCommandThatIsAlsoTheStartIsNotSentToLearnWhetherTheWindowIsThere() = session { model, router ->
        assertTrue(model.answers(query(action = true, poll = START)))
        assertTrue(router.asked.isEmpty())
    }

    @Test
    fun anInterfaceAfterTheHostIsSentByItsNumber() {
        val field = checkNotNull(query().query?.request?.first())
        val sent = encoded(field, "fe80::1%3", null).single().second as M2Value.Message
        assertEquals(listOf(ADDRESS_V6, ADDRESS_INTERFACE), sent.value.fields.map { it.key })
        assertEquals(M2Value.U32(3), sent.value[ADDRESS_INTERFACE])
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { encoded(field, "fe80::1%nothing", null) }.category)
    }
}
