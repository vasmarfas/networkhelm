package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.discovery.formatIpv6
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.duration
import com.vasmarfas.networkhelm.packs.NATIVE_NO_KEY
import com.vasmarfas.networkhelm.packs.PRESENCE
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.packs.PackNativeField
import com.vasmarfas.networkhelm.packs.PackNativeRef
import com.vasmarfas.networkhelm.packs.PackQuery
import com.vasmarfas.networkhelm.packs.QUERY_NO_COMMAND
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.session.NativeObjects
import com.vasmarfas.networkhelm.session.NativeRead
import com.vasmarfas.networkhelm.session.NativeRun
import com.vasmarfas.networkhelm.session.NativeSide
import com.vasmarfas.networkhelm.session.RouterFacts
import com.vasmarfas.networkhelm.session.keepsWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val GET_ALL = 0xFE0004L
private const val GET_SINGLETON = 0xFE000DL
private const val FLAGS = 0xFE000C
private const val GET_ALL_FLAGS = 0x10000005L
private const val RECORDS = 0xFE0002
private const val CONTINUATION = 0xFE0003
private const val CONTINUATION_RAW = 0xFE0015
private const val ELEMENT_ID = 0xFE0001
private const val FINISHED = 0xFE000B
private const val OBJECT_REF = 0xFE0026
private const val NO_MORE = 0xFE0004L
private const val NOT_PERMITTED = 0xFE0009L
private const val SET_SINGLETON = 0xFE000EL
private const val SET_RECORD = 0xFE0003L
private const val ADD = 0xFE0005L
private const val REMOVE = 0xFE0006L
private const val MOVE = 0xFE0007L
private const val BEFORE = 0xFE0005
private const val PAGES = 2_000
// the numbers a router gives the reads it keeps for a source
private val KEPT_READS = 0xFFFFF001L..0xFFFFFFFCL
private const val STOP_READ = 0xFE0018L
private const val SUBSCRIBE = 0xFE0012L
private const val UNSUBSCRIBE = 0xFE0013L
private const val CREDIT = 0xFE0017L
private const val GONE = 0xFE0013
private const val CREDITS = 0xFF0012
private const val FIRST_STATE = 0xFF0017
// a router on a slow link closes the channel when it runs 20 000 records ahead of it; 4 000 keep a fast one as busy
private const val DUMP_CREDITS = 4_000L
private const val QUIET_GAPS = 5
private const val MAC_BYTES = 6
internal const val ADDRESS_V4 = 0xFEFF20
internal const val ADDRESS_V6 = 0xFEFF21
internal const val ADDRESS_INTERFACE = 0xFEFF22
internal const val ADDRESS_VRF = 0xFEFF23
internal const val ADDRESS_LENGTH = 0xFEFF25
internal const val ADDRESS_NAME = 0xFEFF26
private const val ADDRESS_DISTINGUISHER = 0xFEFF27
internal const val ADDRESS_MAC = 0xFEFF2F
private const val DAY_SECONDS = 86_400L
private const val HOUR_SECONDS = 3_600L
private const val KIBI = 1024
private val NAMES_FRESH = 30.seconds
private val FACT_WAIT = 3.seconds
private val PROBE_WAIT = 4.seconds
// a query nobody started may leave its poll unanswered, while a handler the router lacks refuses at once
private val QUERY_PROBE_WAIT = 500.milliseconds
private val LOG_WAIT = 300.milliseconds
private val DUMP_FIRST = 2.seconds
private val DUMP_QUIET = 200.milliseconds
// the router groups the events of 200 ms, and the removals of a read come in the first group after its end
private val DUMP_SETTLE = 600.milliseconds
private const val ROW_COUNT = 0xFE0019
private const val STATE_END = 0xFF000B
private const val NO_ELEMENT = 0xFFFFFFFFL
private val SYSTEM_INFO = listOf(13L, 4L)
private val QUICK_SET = listOf(120L)
private const val INFO_COMMAND = 7
private const val WORD_COMMAND = 5
// the files of the web interface: the list of the descriptions and each description, which WinBox takes over this port
private val RESOURCES = listOf(2L, 2L)
private const val OPEN_RESOURCE = 7
private const val READ_RESOURCE = 4
private const val CLOSE_RESOURCE = 5
private const val RESOURCE_NAME = 0x000001
private const val RESOURCE_SIZE = 0x000002
private const val RESOURCE_DATA = 0x000003
private const val RESOURCE_END = 0x000004
// the router gives at most 61 440 bytes of a file to one read
private const val RESOURCE_PART = 61_440
private const val RESOURCE_MOST = 64L shl 20
private const val BOARD_ARCH = 0x11
private const val BOARD_NAME = 0x15
private const val BOARD_FAMILY = 0x17
private const val INFO_CAPABILITIES = 0x28
private const val CAPABILITY_MASK = 0x6D

class NativeObjectModel(private val connection: NativeConnection, private val owned: Boolean = true) : NativeObjects {

    private val lock = Mutex()
    // the numbers a read was asked for and the table did not have, as the all and the nothing of a field, are not asked for again until the table is read anew
    private val kept = HashMap<PackNativeRef, Triple<TimeMark, Map<Long, String>, Set<Long>>>()

    override suspend fun read(spec: PackNative, limit: Int?): List<Item>? {
        val records = alone(spec.handler) { if (spec.single) listOf(single(spec.handler)) else records(spec.handler, limit, spec.whole, mask = spec.mask ?: GET_ALL_FLAGS) } ?: return null
        return items(spec, records)
    }

    // the table as the router hands it to a subscription: page after page without a request for each, in the order of the ids, with the number of
    // the rows of the table and an empty message at the end; the rows that went during the read come as removals after that end. A router that
    // sends neither is read until it pauses, and getall from the last id then reads whatever the pause left out
    override suspend fun dump(spec: PackNative, expected: Int, page: suspend (List<Item>) -> Unit): List<Item>? {
        if (spec.single) return null
        return alone(spec.handler) {
            val items = LinkedHashMap<Long, Item>()
            // a record that went may still come in a later page of the first state
            val gone = HashSet<Long>()
            val source = connection.newSource()
            val subscription = arrayOf(
                FIRST_STATE to M2Value.Bool(true),
                M2Key.Command to M2Value.U32(SUBSCRIBE),
                FLAGS to M2Value.U32(spec.mask ?: GET_ALL_FLAGS),
                CREDITS to M2Value.U32(DUMP_CREDITS),
            )
            var last: TimeMark? = null
            var counted = false
            var ended = false
            // after the end the read waits out the group of removals; a router that marks nothing is read up to the number the console gave
            fun done() = !ended && !counted && items.size >= expected
            try {
                source.send(spec.handler, *subscription)
                var credits = DUMP_CREDITS
                var longest = Duration.ZERO
                while (!done()) {
                    val wait = when {
                        ended -> DUMP_SETTLE
                        last == null -> DUMP_FIRST
                        else -> maxOf(DUMP_QUIET, longest * QUIET_GAPS)
                    }
                    val message = withTimeoutOrNull(wait) { source.next() ?: throw DeviceException(ErrorCategory.ConnectionLost, "the channel closed during the read") } ?: break
                    // a router that does not subscribe this window leaves it to getall
                    message.refusal()?.let { if (last == null) return@alone null else throw it }
                    if (message.number(ROW_COUNT) != null) counted = true
                    if (message.number(STATE_END) == NO_ELEMENT) ended = true
                    val objects = message.messages(RECORDS).orEmpty()
                    if (objects.isEmpty()) continue
                    last?.let { longest = maxOf(longest, it.elapsedNow()) }
                    last = TimeSource.Monotonic.markNow()
                    // an event about a record the read already has brings the change of a moment later, the next read sees it
                    val fresh = ArrayList<M2Message>(objects.size)
                    for (record in objects) {
                        val id = record.number(ELEMENT_ID) ?: continue
                        if (record.number(GONE) == 1L) {
                            items.remove(id)
                            gone += id
                        } else if (id !in items && id !in gone) {
                            fresh += record
                        }
                    }
                    credits -= objects.size
                    if (credits < DUMP_CREDITS / 2) {
                        source.send(spec.handler, M2Key.Command to M2Value.U32(CREDIT), CREDITS to M2Value.U32(DUMP_CREDITS - credits))
                        credits = DUMP_CREDITS
                    }
                    if (fresh.isNotEmpty()) page(kept(spec, fresh, items))
                }
            } finally {
                // the router matches a subscription by its content, so the same message with another command ends it
                try {
                    withContext(NonCancellable) {
                        source.send(spec.handler, *subscription.map { if (it.first == M2Key.Command) M2Key.Command to M2Value.U32(UNSUBSCRIBE) else it }.toTypedArray())
                    }
                } catch (e: DeviceException) {
                    if (e.category != ErrorCategory.ConnectionLost) throw e
                } finally {
                    source.close()
                }
            }
            if (ended) return@alone items.values.toList()
            val end = items.keys.maxOrNull() ?: return@alone null
            val tail = records(spec.handler, null, spec.whole, end, spec.mask ?: GET_ALL_FLAGS).orEmpty().filter { (it.number(ELEMENT_ID) ?: end) > end }
            if (tail.isNotEmpty()) page(kept(spec, tail, items))
            items.values.toList()
        }
    }

    private suspend fun kept(spec: PackNative, records: List<M2Message>, into: MutableMap<Long, Item>): List<Item> =
        items(spec, records).also { made -> records.forEachIndexed { at, record -> record.number(ELEMENT_ID)?.let { into[it] = made[at] } } }

    private suspend fun items(spec: PackNative, records: List<M2Message>): List<Item> {
        val names = spec.fields.flatMap { it.shapes() }.mapNotNull { it.refers }.distinct().associateWith { ref -> named(ref, wanted(records, spec.fields, ref)) }
        return records.mapIndexed { at, record ->
            val values = LinkedHashMap<String, String>()
            for (field in spec.fields) rendered(record, field, names)?.let { values[field.name] = it }
            Item(record.number(ELEMENT_ID)?.let { "*" + it.toString(16).uppercase() } ?: "#$at", values)
        }
    }

    // a handler the router ignores keeps a read waiting for its whole timeout: the window is not there, and the channel stays while the router answers elsewhere.
    // A query that says nothing to a poll before it is started is a window all the same, as WinBox shows it
    override suspend fun answers(spec: PackNative): Boolean {
        val query = spec.query
        var answered = false
        try {
            withTimeoutOrNull(if (query == null) PROBE_WAIT else QUERY_PROBE_WAIT) {
                if (query == null) read(spec, 1) else probed(spec, query)
                answered = true
            }
        } catch (e: DeviceException) {
            if (e.category == ErrorCategory.ConnectionLost) throw e
            return e.keepsWindow()
        }
        if (!answered && query != null) return true
        if (!answered && asked(SYSTEM_INFO, M2Value.U8(INFO_COMMAND)) == null) throw DeviceException(ErrorCategory.ConnectionLost, "the router did not answer")
        return answered
    }

    private suspend fun <T> alone(handler: List<Long>, request: suspend () -> T): T = try {
        request()
    } catch (e: DeviceException) {
        if (e.category == ErrorCategory.ConnectionLost && connection.alive && asked(SYSTEM_INFO, M2Value.U8(INFO_COMMAND)) != null) {
            throw DeviceException(ErrorCategory.Unsupported, "the router does not answer [${handler.joinToString(",")}]", e)
        }
        throw e
    }

    private fun rendered(record: M2Message, field: PackNativeField, names: Map<PackNativeRef, Map<Long, String>>): String? {
        if (field.flag?.let { record.raised(it) } == false) return null
        // of the shapes a value may take the record holds the one whose key it has; shapes that share a key take the value by turns, the names of the enumeration first
        val shape = if (field.members.isEmpty()) field else shaped(record, field.members) ?: return null
        val text = when {
            shape.kind == "tuple" -> shape.parts.map { rendered(record, it, names) }.takeIf { texts -> texts.any { it != null } }
                ?.mapIndexedNotNull { at, text -> text ?: "0".takeIf { shape.parts[at].kind in LATEST } }?.joinToString(between(shape.separator))
            shape.key == NATIVE_NO_KEY -> address(record, shape.refers?.let(names::get))
            // a flag the description gives as hasid is up while the record holds that field
            shape.kind == PRESENCE -> (record.fieldOf(shape.key) != null).toString()
            else -> {
                val value = record.fieldOf(shape.key, shape.letter) ?: return null
                if (shape.kind == "multi") (value as? M2Value.Messages)?.value?.mapNotNull { rendered(it, shape.parts.single(), names) }?.joinToString(", ")
                else written(shape, value, shape.refers?.let(names::get), shape.pair?.let { record.fieldOf(it) })
            }
        } ?: return null
        return if (field.negation?.let { record.raised(it) } == true) "!$text" else text
    }

    private fun shaped(record: M2Message, members: List<PackNativeField>): PackNativeField? {
        val present = members.filter { record.holds(it) }
        return present.firstOrNull { member -> member.kind != "enm" || present.count { it.key == member.key } == 1 || record.numberOf(member.key)?.let { it in member.names } == true }
    }

    override suspend fun change(spec: PackNative, id: String?, values: Map<String, String?>) {
        val fields = values.flatMap { (name, text) -> typed(spec, name, text) }
        if (fields.isEmpty()) return
        // a field with a flag of presence is taken off by the flag alone
        val off = values.filterValues { it == null }.keys.mapNotNull { name -> spec.fields.firstOrNull { it.name == name }?.takeIf { it.flag == null } }
            .flatMap(::cleared).map { (key, value) -> (M2Message.typeOf(value).toLong() shl 24) or key.toLong() }
        val all = if (off.isEmpty()) fields else fields + (M2Key.Unset to M2Value.U32s(off.toLongArray()))
        if (spec.single) write(spec.handler, SET_SINGLETON, all) else write(spec.handler, SET_RECORD, listOf(ELEMENT_ID to idOf(id)) + all)
    }

    override suspend fun create(spec: PackNative, values: Map<String, String>, before: String?): String {
        val fields = values.flatMap { (name, text) -> typed(spec, name, text) } + listOfNotNull(before?.let { BEFORE to idOf(it) })
        val made = write(spec.handler, ADD, fields).number(ELEMENT_ID) ?: throw DeviceException(ErrorCategory.General, "the router did not say which element it made")
        return "*" + made.toString(16).uppercase()
    }

    override suspend fun delete(spec: PackNative, id: String) {
        write(spec.handler, REMOVE, listOf(ELEMENT_ID to idOf(id)))
    }

    override suspend fun move(spec: PackNative, id: String, before: String?) {
        write(spec.handler, MOVE, listOf(ELEMENT_ID to idOf(id)) + listOfNotNull(before?.let { BEFORE to idOf(it) }))
    }

    private suspend fun typed(spec: PackNative, name: String, text: String?): List<Pair<Int, M2Value>> {
        val field = spec.fields.firstOrNull { it.name == name } ?: throw DeviceException(ErrorCategory.ArgumentValue, "$name is no field of this window")
        val tables = (listOf(field) + field.members).mapNotNull { it.refers }.distinct()
        val resolved = if (text.isNullOrEmpty() || text.toLongOrNull() != null) null else tables.flatMap { named(it, emptySet()).entries }.associate { it.key to it.value }
        return encoded(field, text, resolved)
    }

    private fun idOf(id: String?): M2Value = id?.removePrefix("*")?.toLongOrNull(16)?.let(M2Value::U32) ?: throw DeviceException(ErrorCategory.MissingItem, "the element has no id on the router")

    // the buttons of a window speak with a command of one byte, the rest of the writes with one of four
    override suspend fun act(spec: PackNative, command: Int, id: String?) {
        write(spec.handler, if (command in 0..0xFF) M2Value.U8(command) else M2Value.U32(command.toLong()), listOfNotNull(id?.let { ELEMENT_ID to idOf(it) }))
    }

    // a query or an action starts with the fields the user touched; the router answers with the number it keeps the run under, and every later request repeats it
    override suspend fun start(spec: PackNative, values: Map<String, String>): NativeRun {
        val query = spec.query ?: throw DeviceException(ErrorCategory.Unsupported, "this window starts nothing")
        val fields = values.flatMap { (name, text) -> requested(query, name, text) }
        // a run the router gives no number is known by the number of the object the request names
        val id = (if (query.start == QUERY_NO_COMMAND) null else alone(spec.handler) { write(spec.handler, commandOf(query.start), fields) }[ELEMENT_ID]) ?: fields.firstOrNull { it.first == ELEMENT_ID }?.second
        return Run(spec, query, id)
    }

    private suspend fun requested(query: PackQuery, name: String, text: String): List<Pair<Int, M2Value>> {
        val field = query.request.firstOrNull { it.name == name } ?: throw DeviceException(ErrorCategory.ArgumentValue, "$name is no field of this window")
        val tables = (listOf(field) + field.members + field.parts).mapNotNull { it.refers }.distinct()
        val names = text.isNotEmpty() && text.toLongOrNull() == null && (field.kind != "addr" || '%' in text)
        return encoded(field, text, if (names) tables.flatMap { named(it, emptySet()).entries }.associate { it.key to it.value } else null)
    }

    override suspend fun names(ref: PackNativeRef): Map<Long, String> = named(ref, emptySet())

    private fun commandOf(number: Long): M2Value = if (number in 0..0xFF) M2Value.U8(number.toInt()) else M2Value.U32(number)

    // a handler that is there answers a request for a run it does not have, and one that is not there refuses the command; a command that is also the start is not sent
    private suspend fun probed(spec: PackNative, query: PackQuery) {
        if (query.poll == QUERY_NO_COMMAND || query.poll == query.start || query.poll == query.cancel) return
        val refusal = ask(spec.handler, commandOf(query.poll), listOf(ELEMENT_ID to M2Value.U8(0))).refusal()
        if (refusal != null && refusal.category == ErrorCategory.Unsupported) throw refusal
    }

    private suspend fun ask(handler: List<Long>, command: M2Value, fields: List<Pair<Int, M2Value>>): M2Message {
        val source = connection.newSource()
        try {
            return source.exchange(handler, command, *fields.toTypedArray())
        } finally {
            source.close()
        }
    }

    // the request a log holds for a row it has not got yet, kept on its source
    private class Held(val source: NativeSource, val cursor: List<Pair<Int, M2Value>>, val id: Int)

    private class Pass(val records: List<M2Message>, val next: List<Pair<Int, M2Value>>, val complete: Boolean, val finished: Boolean, val named: Boolean)

    private enum class Rows { Log, Fresh, Table }

    private inner class Run(private val spec: PackNative, private val query: PackQuery, id: M2Value?) : NativeRun {
        private val about = listOfNotNull(id?.let { ELEMENT_ID to it })
        private var cursor = emptyList<Pair<Int, M2Value>>()
        private var shape: Rows? = null
        private var highest = -1L
        private var held: Held? = null

        override suspend fun read(): NativeRead = if (query.action) state() else rows()

        override suspend fun cancel() {
            held?.source?.close()
            held = null
            if (query.cancel != QUERY_NO_COMMAND) ask(spec.handler, commandOf(query.cancel), about)
        }

        // an action answers with one message that holds its whole state; the router forgetting it means it is over
        private suspend fun state(): NativeRead {
            val reply = ask(spec.handler, commandOf(query.poll), about)
            val code = reply.number(M2Key.ErrorCode)?.takeIf { it != 0L }
            if (code == NO_MORE) return NativeRead(emptyList(), snapshot = false, finished = true)
            if (code != null) throw reply.refusal() ?: DeviceException(ErrorCategory.General, "the router refused the request (error 0x${code.toString(16)})")
            return NativeRead(items(spec, listOf(reply)), snapshot = true, finished = reply.raised(FINISHED) == true)
        }

        // a table answers with all its rows each time and a log with the rows it has added, one page at a time, each page naming the place of the next; a log gives a row once,
        // so a second read from the start gives a table its rows again and a log only the rows that came since; a log that names no place is told from a table by the numbers of
        // its rows, which are all above the ones read before
        private suspend fun rows(): NativeRead {
            val first = pass(if (shape == Rows.Log) cursor else emptyList())
            var records = first.records
            var next = first.next
            var finished = first.finished
            if (shape == null && records.isNotEmpty()) {
                val known = records.mapNotNull { it.number(ELEMENT_ID) }.toSet()
                // the last page of a log that has ended names no place of a next one
                val ended = first.complete && first.finished && first.named
                val again = if (ended) null else pass(emptyList())
                val repeated = again != null && again.records.isNotEmpty() && (known.isEmpty() || again.records.any { it.number(ELEMENT_ID)?.let { id -> id in known } != false })
                shape = when {
                    ended -> Rows.Log
                    repeated -> Rows.Table
                    first.complete -> if (known.isEmpty()) Rows.Table else Rows.Fresh
                    else -> Rows.Log
                }
                if (again != null && shape != Rows.Table && again.records.isNotEmpty()) {
                    records += again.records
                    next = again.next
                    finished = finished || again.finished
                }
            }
            val numbers = records.mapNotNull { it.number(ELEMENT_ID) }
            if (shape == Rows.Fresh && numbers.any { it <= highest }) shape = Rows.Table
            numbers.maxOrNull()?.let { highest = maxOf(highest, it) }
            if (shape == Rows.Log) cursor = next
            return NativeRead(items(spec, records), snapshot = shape == Rows.Table, finished = finished)
        }

        // a log holds a request that follows a cursor until it has the row, and a row it has given to a request that is gone it gives no more: the request is waited for a short
        // time, and when the row has not come it stays on its source for the next read, which sends no other
        private suspend fun awaited(cursor: List<Pair<Int, M2Value>>, fields: List<Pair<Int, M2Value>>): M2Message? {
            val waiting = held?.takeIf { it.cursor == cursor } ?: run {
                held?.source?.close()
                val source = connection.newSource()
                val id = source.nextRequestId()
                try {
                    source.send(spec.handler, M2Key.Request to M2Value.Bool(true), M2Key.RequestId to M2Value.U8(id), M2Key.Command to commandOf(query.poll), *fields.toTypedArray())
                } catch (e: DeviceException) {
                    source.close()
                    throw e
                }
                Held(source, cursor, id)
            }
            held = waiting
            var message: M2Message? = null
            val answered = withTimeoutOrNull(LOG_WAIT) {
                do {
                    message = waiting.source.next()
                } while (message != null && message?.number(M2Key.RequestId) != waiting.id.toLong())
                true
            }
            if (answered == null) return null
            held = null
            waiting.source.close()
            return message ?: throw DeviceException(ErrorCategory.ConnectionLost, "the router did not answer")
        }

        private suspend fun pass(from: List<Pair<Int, M2Value>>): Pass {
            val records = ArrayList<M2Message>()
            var next = from
            var finished = false
            var named = false
            repeat(PAGES) {
                val fields = listOf(FLAGS to M2Value.U32(GET_ALL_FLAGS)) + about + next
                val reply = (if (next.isEmpty() || shape == Rows.Table || shape == Rows.Fresh) ask(spec.handler, commandOf(query.poll), fields) else awaited(next, fields))
                    ?: return Pass(records, next, false, finished, named)
                val code = reply.number(M2Key.ErrorCode)?.takeIf { it != 0L }
                if (code == NO_MORE) return Pass(records, next, false, finished, named)
                if (code != null) throw reply.refusal() ?: DeviceException(ErrorCategory.General, "the router refused the read (error 0x${code.toString(16)})")
                records += reply.messages(RECORDS).orEmpty()
                if (reply.raised(FINISHED) == true) finished = true
                next = reply.fields.filter { it.key == CONTINUATION || it.key == CONTINUATION_RAW }.map { it.key to it.value }
                if (next.isNotEmpty()) named = true
                if (next.isEmpty()) return Pass(records, next, true, finished, named)
            }
            throw DeviceException(ErrorCategory.General, "the router did not end the rows of [${spec.handler.joinToString(",")}] in $PAGES pages")
        }
    }

    private suspend fun write(handler: List<Long>, command: Long, fields: List<Pair<Int, M2Value>>) = write(handler, M2Value.U32(command), fields)

    private suspend fun write(handler: List<Long>, command: M2Value, fields: List<Pair<Int, M2Value>>): M2Message {
        val source = connection.newSource()
        try {
            val reply = source.exchange(handler, command, *fields.toTypedArray())
            val code = reply.number(M2Key.ErrorCode)?.takeIf { it != 0L }
            val text = reply.text(M2Key.Error)
            when (code) {
                NO_MORE -> throw DeviceException(ErrorCategory.MissingItem, text ?: "the element is gone")
                NOT_PERMITTED -> throw DeviceException(ErrorCategory.PermissionDenied, text ?: "the router does not let this user change it")
            }
            reply.refusal()?.let { throw it }
            return reply
        } finally {
            source.close()
        }
    }

    override fun close() {
        if (owned) connection.close()
    }

    override suspend fun lines(spec: PackNative): NativeRun = Lines(spec)

    // a log comes in pages of the lines that fill a frame, each naming the line the next page starts with; the last page names none.
    // the line a later read starts from is read again first and has to be in the buffer of the router still, or the router answers that there is no more of it
    private inner class Lines(private val spec: PackNative) : NativeRun {
        private var last: Long? = null

        override suspend fun read(): NativeRead = alone(spec.handler) {
            val continued = last?.let { known -> pages(known)?.dropWhile { row -> row.number(ELEMENT_ID)?.let { it <= known } == true } }
            val rows = continued ?: pages(null).orEmpty()
            last = rows.lastOrNull()?.number(ELEMENT_ID) ?: if (continued == null) null else last
            NativeRead(if (rows.isEmpty()) emptyList() else items(spec, rows), snapshot = continued == null, finished = false)
        }

        override suspend fun cancel() = Unit

        private suspend fun pages(from: Long?): List<M2Message>? {
            val rows = ArrayList<M2Message>()
            var cursor: List<Pair<Int, M2Value>> = from?.let { listOf(CONTINUATION to M2Value.U32(it)) }.orEmpty()
            repeat(PAGES) {
                val reply = ask(spec.handler, M2Value.U32(GET_ALL), listOf(FLAGS to M2Value.U32(GET_ALL_FLAGS)) + cursor)
                val code = reply.number(M2Key.ErrorCode)?.takeIf { it != 0L }
                if (code == NO_MORE) return if (rows.isEmpty() && from != null) null else rows
                if (code != null) throw reply.refusal() ?: DeviceException(ErrorCategory.General, "the router refused the read (error 0x${code.toString(16)})")
                rows += reply.messages(RECORDS).orEmpty()
                cursor = reply.fields.filter { it.key == CONTINUATION || it.key == CONTINUATION_RAW }.map { it.key to it.value }
                if (cursor.isEmpty()) return rows
            }
            throw DeviceException(ErrorCategory.General, "the router did not end the lines of [${spec.handler.joinToString(",")}] in $PAGES pages")
        }
    }

    // the router hands a file a part at a time from where the last read stopped; a file read to its size without the mark of the end is closed
    override suspend fun resource(name: String): ByteArray? {
        val source = connection.newSource()
        try {
            val opened = source.exchange(RESOURCES, M2Value.U8(OPEN_RESOURCE), RESOURCE_NAME to M2Value.Text(name))
            if (opened.refusal() != null) return null
            val handle = opened.number(ELEMENT_ID) ?: return null
            val size = opened.number(RESOURCE_SIZE)?.takeIf { it <= RESOURCE_MOST }?.toInt() ?: return null
            val data = ByteArray(size)
            var at = 0
            var ended = false
            try {
                while (at < size && !ended) {
                    val part = source.request(RESOURCES, M2Value.U8(READ_RESOURCE), ELEMENT_ID to M2Value.U32(handle), RESOURCE_SIZE to M2Value.U32(minOf(RESOURCE_PART, size - at).toLong()))
                    val bytes = part.raw(RESOURCE_DATA)?.takeIf { it.size <= size - at } ?: return null
                    bytes.copyInto(data, at)
                    at += bytes.size
                    ended = (part[RESOURCE_END] as? M2Value.Bool)?.value == true || bytes.isEmpty()
                }
            } finally {
                if (!ended) withContext(NonCancellable) { source.exchange(RESOURCES, M2Value.U8(CLOSE_RESOURCE), ELEMENT_ID to M2Value.U32(handle)) }
            }
            return data.takeIf { at == size }
        } finally {
            source.close()
        }
    }

    override suspend fun facts(): RouterFacts? {
        val info = asked(SYSTEM_INFO, M2Value.U8(INFO_COMMAND)) ?: return null
        val capabilities = (info[INFO_CAPABILITIES] as? M2Value.Message)?.value?.numbers(CAPABILITY_MASK).orEmpty()
        return RouterFacts(info.text(BOARD_NAME), info.text(BOARD_FAMILY), info.text(BOARD_ARCH), capabilities, asked(QUICK_SET, M2Value.U8(WORD_COMMAND))?.number(1))
    }

    // a handler that refuses or stays silent leaves the fact unknown
    private suspend fun asked(handler: List<Long>, command: M2Value): M2Message? {
        val source = connection.newSource()
        return try {
            withTimeoutOrNull(FACT_WAIT) { source.request(handler, command) }
        } catch (e: DeviceException) {
            if (e.category == ErrorCategory.ConnectionLost) throw e
            null
        } finally {
            source.close()
        }
    }

    // a cursor names the record a page starts with, so a read from one gives that record first; one among the numbers the router gives the reads
    // it keeps, as that of Routes, names the read itself: it goes with every page, the pages after the first need not repeat it, and an empty
    // page or no more objects ends the read
    internal suspend fun records(handler: List<Long>, limit: Int? = null, whole: Boolean = false, from: Long? = null, mask: Long = GET_ALL_FLAGS): List<M2Message>? {
        val source = connection.newSource()
        try {
            val records = ArrayList<M2Message>()
            var cursor: List<Pair<Int, M2Value>> = from?.let { listOf(CONTINUATION to M2Value.U32(it)) }.orEmpty()
            var kept = false
            repeat(PAGES) {
                val reply = source.exchange(handler, M2Value.U32(GET_ALL), FLAGS to M2Value.U32(mask), *cursor.toTypedArray())
                val code = reply.number(M2Key.ErrorCode)?.takeIf { it != 0L }
                if (code != null && code != NO_MORE) throw reply.refusal() ?: DeviceException(ErrorCategory.General, "the router refused the read (error 0x${code.toString(16)})")
                val page = reply.messages(RECORDS).orEmpty()
                if (kept && (code == NO_MORE || page.isEmpty())) return records
                // no more objects after a cursor: the record it names went between two pages, and the pages before it are not the whole table
                if (whole && code == NO_MORE && cursor.isNotEmpty()) throw DeviceException(ErrorCategory.MissingItem, "the table [${handler.joinToString(",")}] changed between two pages of the read")
                records += page
                if (limit != null && records.size > limit) {
                    // the router keeps the rest of a read it keeps for the source until it is told to stop
                    if (kept) source.send(handler, M2Key.Command to M2Value.U32(STOP_READ), *cursor.toTypedArray())
                    return null
                }
                if (kept) return@repeat
                cursor = reply.fields.filter { it.key == CONTINUATION || it.key == CONTINUATION_RAW }.map { it.key to it.value }
                kept = cursor.any { (_, value) -> (value as? M2Value.U32)?.value in KEPT_READS }
                if (code == NO_MORE || cursor.isEmpty()) return records
            }
            throw DeviceException(ErrorCategory.General, "the router did not end the list of [${handler.joinToString(",")}] in $PAGES pages")
        } finally {
            source.close()
        }
    }

    // a holder object answers with its fields in the reply itself
    internal suspend fun single(handler: List<Long>): M2Message {
        val source = connection.newSource()
        try {
            return source.request(handler, M2Value.U32(GET_SINGLETON))
        } finally {
            source.close()
        }
    }

    private fun wanted(records: List<M2Message>, fields: List<PackNativeField>, ref: PackNativeRef): Set<Long> =
        records.flatMap { record -> fields.flatMap { it.numbers(record, ref) } }.toSet()

    // the numbers a record holds under the fields that name their value in a table of the router, the elements of a list included
    private fun PackNativeField.numbers(record: M2Message, ref: PackNativeRef): List<Long> {
        val own = when {
            refers != ref -> emptyList()
            key == NATIVE_NO_KEY -> listOfNotNull(record.number(ADDRESS_INTERFACE))
            else -> listOfNotNull(record.numberOf(key), (record.fieldOf(key) as? M2Value.Message)?.value?.number(ADDRESS_INTERFACE)) + record.numbers(key).orEmpty()
        }
        val elements = if (kind == "multi") (record.fieldOf(key) as? M2Value.Messages)?.value.orEmpty() else listOf(record)
        return own + members.flatMap { it.numbers(record, ref) } + elements.flatMap { element -> parts.flatMap { it.numbers(element, ref) } }
    }

    // the flag of an optional or negated field: down, up, or not in the record at all
    private fun M2Message.raised(key: Int): Boolean? = (fieldOf(key, "b") as? M2Value.Bool)?.value

    // an element that lies under another names its owner in the reference to itself: the ports of a controller keep their device there
    private fun M2Message.fieldOf(key: Int, letter: String? = null): M2Value? =
        valueOf(key, letter) ?: (this[OBJECT_REF] as? M2Value.Message)?.value?.valueOf(key, letter)

    private fun M2Message.numberOf(key: Int): Long? = number(key) ?: (this[OBJECT_REF] as? M2Value.Message)?.value?.number(key)

    // a tuple has no key of its own: it is there when one of its parts is
    private fun M2Message.holds(field: PackNativeField): Boolean = if (field.kind == "tuple") field.parts.any { holds(it) } else fieldOf(field.key, field.letter) != null

    private suspend fun named(ref: PackNativeRef, wanted: Set<Long>): Map<Long, String> {
        lock.withLock {
            kept[ref]?.takeIf { (read, names, asked) -> read.elapsedNow() < NAMES_FRESH && (names.keys + asked).containsAll(wanted) }?.let { return it.second }
        }
        val fresh = records(ref.handler).orEmpty().mapNotNull { record ->
            val id = record.number(ELEMENT_ID) ?: return@mapNotNull null
            id to ref.keys.mapNotNull { key -> record.text(key)?.takeIf { it.isNotEmpty() } }.joinToString(ref.separator)
        }.toMap()
        lock.withLock { kept[ref] = Triple(TimeSource.Monotonic.markNow(), fresh, wanted) }
        return fresh
    }
}

// two fields of a record may share a key and differ in type; the letter of the id in the description says which one is meant
private fun M2Message.valueOf(key: Int, letter: String?): M2Value? {
    var first: M2Value? = null
    var typed: M2Value? = null
    var count = 0
    for (field in fields) {
        if (field.key != key) continue
        count++
        if (first == null) first = field.value
        if (typed == null && letter != null && field.value.isOf(letter)) typed = field.value
    }
    return if (count == 1) first else typed ?: first
}

private fun M2Value.isOf(letter: String): Boolean = when (letter) {
    "u" -> this is M2Value.U8 || this is M2Value.U32
    "q" -> this is M2Value.U64
    "b" -> this is M2Value.Bool
    "s" -> this is M2Value.Text
    "r" -> this is M2Value.Raw
    "a" -> this is M2Value.Address6
    "m" -> this is M2Value.Message
    "U" -> this is M2Value.U32s
    "Q" -> this is M2Value.U64s
    "B" -> this is M2Value.Bools
    "S" -> this is M2Value.Texts
    "R" -> this is M2Value.Raws
    "A" -> this is M2Value.Address6s
    "M" -> this is M2Value.Messages
    else -> false
}

internal fun written(field: PackNativeField, value: M2Value, named: Map<Long, String>?, paired: M2Value? = null): String? = when (value) {
    is M2Value.Bool -> field.names[if (value.value) 1L else 0L] ?: value.value.toString()
    is M2Value.U8 -> number(field, value.value.toLong(), named, paired)
    is M2Value.U32 -> number(field, value.value, named, paired)
    is M2Value.U64 -> number(field, value.value, named, paired)
    is M2Value.Text -> value.value
    is M2Value.Address6 -> (mapped(value.value)?.let(::dotted) ?: formatIpv6(value.value)) + if (field.kind == "network6") paired.number()?.let { "/$it" }.orEmpty() else ""
    is M2Value.U32s -> when (field.kind) {
        "numberrangelist", "multinumberrange" -> ranges(value.value)
        "multicommunity" -> ranges(value.value, ::community)
        "multiintervalrange" -> value.value.toList().chunked(2).joinToString(", ") { pair -> pair.joinToString(field.separator ?: "-") { duration(it) } }
        "multinetwork" -> value.value.toList().chunked(2).joinToString(",") { network(field, it[0], it.getOrNull(1)?.let(M2Value::U32)) }
        "multiipaddr" -> value.value.joinToString(",") { dotted(it) }
        else -> value.value.joinToString(",") { named?.get(it) ?: field.names[it] ?: it.toString() }
    }
    is M2Value.Address6s -> value.value.takeIf { field.kind == "multiip6addr" }?.joinToString(",") { formatIpv6(it) }
    is M2Value.Bools -> value.value.joinToString(",")
    is M2Value.U64s -> if (field.kind in LATEST) number(field, value.value.lastOrNull() ?: 0, named, paired) else value.value.joinToString(",")
    is M2Value.Texts -> value.value.joinToString(",")
    is M2Value.Raws -> value.value.takeIf { field.kind == "multiraw" }?.joinToString(",") { hexOf(it) }
    is M2Value.Raw -> when (field.kind) {
        "macaddr" -> value.value.takeIf { it.size == MAC_BYTES }?.let(::macOf)
        "macnetwork" -> value.value.takeIf { it.size == MAC_BYTES }?.let { macOf(it) + (paired as? M2Value.Raw)?.value?.takeIf { mask -> mask.size == MAC_BYTES }?.let { mask -> "/" + macOf(mask) }.orEmpty() }
        "raw" -> hexOf(value.value)
        else -> null
    }
    is M2Value.Message -> if (field.kind == "addr") address(value.value, named) else null
    else -> null
}

// an address of version 4 that a field of version 6 holds is ::ffff: and the four bytes
private fun mapped(bytes: ByteArray): Long? =
    if ((0 until 10).all { bytes[it].toInt() == 0 } && bytes[10] == (-1).toByte() && bytes[11] == (-1).toByte()) (0..3).fold(0L) { all, at -> all or ((bytes[12 + at].toLong() and 0xFF) shl (8 * at)) } else null

private fun hexOf(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

private fun macOf(bytes: ByteArray) = bytes.joinToString(":") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

// an address is a message with a key for each shape of it, and the marks of the console stand before the parts: / % @
private fun address(message: M2Message, interfaces: Map<Long, String>?): String? {
    val host = message.number(ADDRESS_V4)?.let(::dotted)
        ?: (message[ADDRESS_V6] as? M2Value.Address6)?.let { formatIpv6(it.value) }
        ?: message.text(ADDRESS_NAME)
        ?: message.text(ADDRESS_DISTINGUISHER)
        ?: message.raw(ADDRESS_MAC)?.takeIf { it.size == MAC_BYTES }?.let(::macOf)
    // an interface alone is written by its name, as the gateway of a route; after an address it is the zone of it
    val text = host.orEmpty() +
        message.number(ADDRESS_LENGTH)?.let { "/$it" }.orEmpty() +
        message.number(ADDRESS_INTERFACE)?.let { (if (host == null) "" else "%") + (interfaces?.get(it) ?: it) }.orEmpty() +
        message.number(ADDRESS_VRF)?.let { "@$it" }.orEmpty()
    return text.takeIf { it.isNotEmpty() }
}

private fun two(number: Long) = number.toString().padStart(2, '0')

private fun clock(seconds: Long, always: Boolean): String {
    val ofDay = seconds % DAY_SECONDS
    val tail = if (always || ofDay % 60 != 0L) ":" + two(ofDay % 60) else ""
    return two(ofDay / HOUR_SECONDS) + ":" + two(ofDay % HOUR_SECONDS / 60) + tail
}

// seconds since 1970 in UTC, written the way WinBox 4 writes a date on every version of RouterOS: 2026-11-03
private fun calendar(epoch: Long): String {
    val shifted = epoch.floorDiv(DAY_SECONDS) + 719_468
    val era = shifted.floorDiv(146_097)
    val dayOfEra = shifted - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val month = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * month + 2) / 5 + 1
    val calendarMonth = if (month < 10) month + 3 else month - 9
    return "${yearOfEra + era * 400 + if (calendarMonth <= 2) 1 else 0}-${two(calendarMonth)}-${two(day)}"
}

// the offset from UTC is a signed number of seconds that the wire carries unsigned
private fun offset(number: Long): String {
    val signed = if (number > Int.MAX_VALUE) number - (1L shl 32) else number
    val seconds = if (signed < 0) -signed else signed
    return (if (signed < 0) "-" else "+") + two(seconds / HOUR_SECONDS) + ":" + two(seconds % HOUR_SECONDS / 60)
}

private fun M2Value?.number(): Long? = when (this) {
    is M2Value.U8 -> value.toLong()
    is M2Value.U32 -> value
    else -> null
}

private fun dotted(address: Long) = (0..3).joinToString(".") { ((address shr (8 * it)) and 0xFF).toString() }

// a network is its address and a mask in the byte order of the address; with a range the second number is the last address
private fun network(field: PackNativeField, address: Long, paired: M2Value?): String {
    val second = paired.number() ?: return dotted(address)
    if (!field.range) return dotted(address) + "/" + second.toInt().countOneBits()
    val start = swapped(address)
    val size = swapped(second) - start + 1
    return when {
        size == 1L -> dotted(address)
        size > 1 && size and (size - 1) == 0L && start % size == 0L -> dotted(address) + "/" + (32 - size.countTrailingZeroBits())
        else -> dotted(address) + "-" + dotted(second)
    }
}

private fun swapped(address: Long) = (0..3).fold(0L) { all, at -> (all shl 8) or ((address shr (8 * at)) and 0xFF) }

// the bits of a set are named by their number, from the lowest
private fun bits(field: PackNativeField, number: Long) =
    (0 until 32).filter { number and (1L shl it) != 0L }.joinToString(",") { field.names[it.toLong()] ?: it.toString() }

// a word between the parts of an element stands apart from them, a mark sticks to them
private fun between(separator: String?) = when {
    separator == null -> " "
    separator.any(Char::isLetter) -> " $separator "
    else -> separator
}

private fun PackNativeField.shapes(): List<PackNativeField> = listOf(this) + (members + parts).flatMap { it.shapes() }

// a state of three: a bit up in the value is on, a bit up only in the mask is off, a bit in neither is not given
private fun tristate(field: PackNativeField, value: Long, mask: Long?): String =
    (0 until 32).filter { (value or (mask ?: 0L)) and (1L shl it) != 0L }.joinToString(",") { (if (value and (1L shl it) != 0L) "" else "!") + (field.names[it.toLong()] ?: it.toString()) }

private fun range(low: Long, high: Long?, shown: (Long) -> String = Long::toString) =
    if (high == null || high == low) shown(low) else shown(low) + ".." + shown(high)

// a list of ranges is a flat list of pairs, and a single value stands as a pair of equal numbers
private fun ranges(values: LongArray, shown: (Long) -> String = Long::toString) = values.toList().chunked(2).joinToString(", ") { range(it[0], it.getOrNull(1), shown) }

// the autonomous system sits in the high half of a community and the value in the low one
private fun community(value: Long) = "${value ushr 16}:${value and 0xFFFF}"

private val PREFIXES = listOf("", "k", "M", "G")

// a graph keeps the rates of every second in an array, and the field shows the last of them
private val LATEST = setOf("bitrate", "bigbitrate")

// a number is stepped up by a thousand at a time, one digit stays after the point, the unit is the postfix of the node
private fun prefixed(number: Long, unit: String?): String {
    var step = 0
    var divisor = 1L
    while (step < PREFIXES.lastIndex && number / divisor >= 1_000) {
        divisor *= 1_000
        step++
    }
    val text = if (step == 0) "$number" else "${number / divisor}.${number % divisor * 10 / divisor}"
    return if (unit.isNullOrEmpty()) text + PREFIXES[step] else "$text ${PREFIXES[step]}$unit"
}

private fun number(field: PackNativeField, number: Long, named: Map<Long, String>?, paired: M2Value?): String {
    when (field.kind) {
        "set" -> return bits(field, number)
        "numflag" -> return field.names[number].orEmpty()
        "network" -> return network(field, number, paired)
        "multitristate" -> return tristate(field, number, paired.number())
        "numberrange" -> return range(number, paired.number())
        "ipaddrandport" -> return dotted(number) + paired.number()?.let { ":$it" }.orEmpty()
        "unit", "bigunit" -> return prefixed(number, field.postfix)
    }
    named?.get(number)?.let { return it }
    field.names[number]?.let { return it }
    val scale = field.scale?.takeIf { it > 1 }
    val written = when (field.kind) {
        "bool", "flag" -> (number != 0L).toString()
        "kbytes" -> (number * KIBI).toString()
        "ipaddr", "netmask" -> dotted(number)
        "clocktime" -> clock(number, always = false)
        "clockdate" -> calendar(number)
        "dateandtime" -> calendar(number) + " " + clock(number, always = true)
        "timezone" -> offset(number)
        "interval", "age" -> duration(if (scale == null) number else number / scale)
        "fixedpoint", "decimal", "bigdecimal" -> scaled(number, scale)
        "integer" -> (if (number > Int.MAX_VALUE) number - (1L shl 32) else number).toString()
        else -> number.toString()
    }
    return written + field.postfix.orEmpty()
}

private fun scaled(number: Long, scale: Int?): String {
    if (scale == null) return number.toString()
    val digits = scale.toString().length - 1
    val whole = (if (number < 0) -number else number)
    val fraction = (whole % scale).toString().padStart(digits, '0').trimEnd('0')
    return (if (number < 0) "-" else "") + (whole / scale) + if (fraction.isEmpty()) "" else ".$fraction"
}

suspend fun openNativeObjects(host: String, port: Int, user: String, password: String, scope: CoroutineScope): NativeObjects =
    NativeObjectModel(NativeConnection.open(host, user, password, scope, port))

fun RouterOsLogin.nativeSide(scope: CoroutineScope) = NativeSide { port -> openNativeObjects(host, nativePort ?: port, user, password, scope) }
