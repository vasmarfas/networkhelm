package com.vasmarfas.networkhelm.routeros.console

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.routeros.api.ApiConnectionClosedException
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.api.ApiReply
import com.vasmarfas.networkhelm.routeros.api.ApiResult
import com.vasmarfas.networkhelm.routeros.api.ApiTrapException
import com.vasmarfas.networkhelm.routeros.api.BigTables
import com.vasmarfas.networkhelm.routeros.api.RouterOsClient
import com.vasmarfas.networkhelm.routeros.api.RouterOsLink
import com.vasmarfas.networkhelm.routeros.api.rounds
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.ssh.ShellChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

private val BOOLEAN = Regex("invalid value of (\\S+), must be either yes or no")
private val REFUSAL = Regex("^bad (?:parameter|command name) (\\S+)")
private const val PROFILE = "/tool/profile"
internal const val LINE_LIMIT = 16_000

// RouterOS 6 redraws the line after every key, so a line of n characters costs about n squared
private const val CLASSIC_LINE_LIMIT = 2_500
private const val ROUND_MILLIS = 1_000L
private const val LONGEST_WAIT_MILLIS = 30_000L
private const val CRASHED = "Console has crashed"

class ConsoleClient internal constructor(
    private val size: Int = 4,
    private val dialect: Dialect = Dialect.Serialize,
    private val encoding: ApiEncoding = ApiEncoding.Utf8,
    private val alive: () -> Boolean = { true },
    private val open: suspend () -> ShellChannel,
) : RouterOsClient {
    private val slots: Channel<ConsoleTerminal?> = Channel<ConsoleTerminal?>(size, onUndeliveredElement = ::returned).also { repeat(size) { _ -> it.trySend(null) } }

    private val yesNo = MutableStateFlow(setOf("disabled"))

    private val refused = MutableStateFlow(emptyMap<String, Set<String>>())
    private val tables = BigTables()
    private val link = MutableStateFlow(LinkState.Up)
    private val expired = MutableStateFlow(false)

    // Safe Mode is bound to the session that took it, so one console, kept out of the pool, makes every change
    private val held = MutableStateFlow<ConsoleTerminal?>(null)
    private val heldLine = Mutex()
    private val mode = Mutex()
    private val heavyLine = Mutex()

    override val status: Flow<LinkState> = link.asStateFlow()

    override val passwordExpired: Boolean get() = expired.value

    override val safeMode: SafeMode = object : SafeMode {
        override val active: Flow<Boolean> = held.map { it != null }

        override val actions: Flow<Int> = flow {
            while (true) {
                emit(if (held.value == null) 0 else undoable())
                delay(ROUND_MILLIS)
            }
        }

        override suspend fun enter(): Boolean = mode.withLock {
            if (held.value != null) return@withLock true
            val console = acquire()
            val taken = try {
                console.safe()
            } catch (e: Throwable) {
                release(console, false)
                throw e
            }
            // a busy console takes the Ctrl+X later, and what went into it then would be rolled back when it is closed
            if (taken) held.value = console else release(console, false)
            taken
        }

        override suspend fun commit() = mode.withLock {
            val console = held.value ?: return@withLock
            var released = false
            try {
                heldLine.withLock { console.safe() }
                released = true
            } finally {
                held.value = null
                release(console, released)
            }
        }

        override suspend fun rollback() = mode.withLock {
            val console = held.value ?: return@withLock
            held.value = null
            heldLine.withLock { release(console, false) }
        }
    }

    override suspend fun call(words: List<String>): ApiResult = read(words) { _, _ -> }

    override suspend fun table(words: List<String>, progress: suspend (Int, Int) -> Unit): List<Map<String, String>> = read(words, progress).rows

    private suspend fun read(words: List<String>, progress: suspend (Int, Int) -> Unit): ApiResult {
        val call = translate(words)
        val menu = call.menu?.takeIf { call.reply != Reply.Count && portionable(it) }
        return tables.read(words, menu, progress, { whole(it) }, { whole(words, heavy = true).rows }) { part -> whole(words, part).rows.takeIf { portionable(menu.orEmpty()) } }
    }

    private fun portionable(menu: String) = refused.value[menu].orEmpty().none { it == FIND || it == FROM }

    private suspend fun whole(words: List<String>, portion: IntRange? = null, heavy: Boolean = false): ApiResult {
        var call = translate(words, portion)
        var answer = run(call, heavy)
        if (CRASHED in answer.text && !call.changes) answer = run(call, heavy)
        if (!answer.ok) {
            val name = BOOLEAN.find(answer.text)?.groupValues?.get(1)
            if (name != null && words.any { it == "=$name=true" || it == "=$name=false" }) {
                yesNo.update { it + name }
                call = translate(words, portion)
                answer = run(call, heavy)
            }
        }
        while (!answer.ok) {
            val last = answer.text.substringAfterLast('\n')
            val menu = call.menu
            val word = REFUSAL.find(last)?.groupValues?.get(1)
            call = when {
                menu != null && word != null && word in call.offered -> {
                    refused.update { it + (menu to it[menu].orEmpty() + word) }
                    translate(words, portion)
                }
                last.startsWith(AS_VALUE) -> call.fallback ?: break
                else -> break
            }
            answer = run(call, heavy)
        }
        if (!answer.ok) throw trap(answer.text.ifBlank { "the console gave no answer" }.let { if (call.probe) probed(it) else it })
        return result(call, answer.text)
    }

    private fun translate(words: List<String>, portion: IntRange? = null) = try {
        when (dialect) {
            Dialect.Serialize -> consoleCall(words, yesNo.value, refused.value, encoding, portion)
            Dialect.Classic -> classicCall(words, yesNo.value, refused.value, encoding, portion)
        }
    } catch (e: IllegalArgumentException) {
        throw DeviceException(ErrorCategory.ArgumentValue, e.message.orEmpty(), e)
    }

    override fun command(words: List<String>, sent: CompletableDeferred<Unit>?): Flow<ApiReply> = flow {
        if (words.first().endsWith("/listen")) throw ApiTrapException(null, "listen is not available over the console")
        sent?.complete(Unit)
        val stream = rounds(words) { asked -> call(asked).rows.map { row -> if (words.first() == PROFILE) percents(row) else row } }
        if (stream == null) call(words).rows.forEach { emit(ApiReply("!re", it, null)) } else emitAll(stream)
    }

    // the console writes the usage of profile in tenths of a percent, the API in percents
    private fun percents(row: Map<String, String>): Map<String, String> {
        val tenths = row["usage"]?.toIntOrNull() ?: return row
        return row + ("usage" to if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}")
    }

    // a watch outlives a lost console, as it does a lost connection of the API
    override fun <T> each(block: suspend FlowCollector<T>.(RouterOsLink) -> Unit): Flow<T> = flow {
        var wait = ROUND_MILLIS
        while (true) {
            try {
                block(this@ConsoleClient)
                return@flow
            } catch (e: ApiConnectionClosedException) {
                if (link.value == LinkState.Closed) throw e
                delay(wait)
                wait = minOf(wait * 2, LONGEST_WAIT_MILLIS)
            }
        }
    }

    fun warm(scope: CoroutineScope) {
        repeat(size - 1) {
            scope.launch {
                val console = try {
                    acquire()
                } catch (e: ApiConnectionClosedException) {
                    return@launch
                } catch (e: DeviceException) {
                    return@launch
                }
                release(console, true)
            }
        }
        scope.launch { keep() }
    }

    // a window that only shows a list asks nothing, so nothing else would notice a dropped connection or open a new one
    private suspend fun keep() {
        var wait = ROUND_MILLIS
        while (withTimeoutOrNull(wait) { link.first { it == LinkState.Closed } } == null) {
            if (!alive()) held.value?.let(::forget)
            if (link.value == LinkState.Up) {
                wait = ROUND_MILLIS
                if (!alive()) lost(ApiConnectionClosedException("the connection went down"))
                continue
            }
            wait = minOf(wait * 2, LONGEST_WAIT_MILLIS)
            try {
                call(listOf("/system/identity/print"))
            } catch (e: DeviceException) {
                // a refused login does not change by itself, and every failed one lands in the log of the router
                if (e.category == ErrorCategory.PermissionDenied) close()
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
            }
        }
    }

    override fun close() {
        link.value = LinkState.Closed
        held.getAndUpdate { null }?.close()
        slots.close()
        while (true) {
            val slot = slots.tryReceive()
            if (!slot.isSuccess) break
            slot.getOrNull()?.close()
        }
    }

    private suspend fun undoable(): Int = call(listOf("/system/history/print", "=count-only=", "?floating-undo=true")).done["ret"]?.toIntOrNull() ?: 0

    private suspend fun run(call: ConsoleCall, heavy: Boolean = false): Answer {
        val limit = if (dialect == Dialect.Classic) CLASSIC_LINE_LIMIT else LINE_LIMIT
        if (call.line.length > limit) throw DeviceException(ErrorCategory.ArgumentValue, "the line for the console is ${call.line.length} characters long, longer than the $limit that this router takes")
        if (encoding == ApiEncoding.Windows1251) {
            try {
                encoding.encode(call.line)
            } catch (e: IllegalArgumentException) {
                throw DeviceException(ErrorCategory.ArgumentValue, e.message.orEmpty(), e)
            }
        }
        val holder = held.value
        if (call.changes && holder != null) {
            return heldLine.withLock {
                try {
                    ask(holder, call)
                } catch (e: ApiConnectionClosedException) {
                    forget(holder)
                    throw e
                }
            }
        }
        return try {
            attempt(call, heavy)
        } catch (e: ApiConnectionClosedException) {
            // a read goes to another console, a change does not: the router may have run it
            if (call.changes || !call.quick && e !is SilentConsoleException) throw e
            attempt(call, heavy)
        }
    }

    private suspend fun attempt(call: ConsoleCall, heavy: Boolean): Answer = if (call.heavy || heavy) alone { asked(call) } else asked(call)

    // the consoles of a router are one process: a line typed in another one during a long line only stops there for ten seconds with the question of Restart console
    private suspend fun <T> alone(block: suspend () -> T): T = heavyLine.withLock {
        val others = ArrayList<ConsoleTerminal?>()
        try {
            // the console of Safe Mode is out of the pool, and nothing else stands behind it
            repeat(size - 1 - (if (held.value == null) 0 else 1)) { others += slot() }
            block()
        } finally {
            others.forEach(::returned)
        }
    }

    private suspend fun asked(call: ConsoleCall): Answer {
        val console = acquire()
        var healthy = false
        try {
            return ask(console, call).also { healthy = true }
        } finally {
            release(console, healthy)
        }
    }

    private suspend fun ask(console: ConsoleTerminal, call: ConsoleCall): Answer {
        try {
            val answer = console.run(call.line, call.quick)
            link.update { state -> if (state == LinkState.Closed) state else LinkState.Up }
            return answer
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeviceException) {
            throw if (e.category == ErrorCategory.ConnectionLost) dropped(e) else e
        } catch (e: ApiConnectionClosedException) {
            throw lost(e)
        } catch (e: Exception) {
            throw lost(ApiConnectionClosedException(e.message ?: "the console failed", e))
        }
    }

    private fun lost(error: ApiConnectionClosedException): ApiConnectionClosedException {
        link.update { state -> if (state == LinkState.Closed) state else LinkState.Reconnecting }
        // the parked consoles are streams of the connection that went down
        repeat(size) {
            val slot = slots.tryReceive()
            if (!slot.isSuccess) return error
            slot.getOrNull()?.close()
            slots.trySend(null)
        }
        return error
    }

    private fun dropped(error: DeviceException): ApiConnectionClosedException = lost(ApiConnectionClosedException(error.message.orEmpty(), error))

    private fun result(call: ConsoleCall, text: String): ApiResult = when (call.reply) {
        Reply.Rows -> {
            val menu = call.menu
            if (call.heavy && call.plain && menu != null && plainAddsNothing(text)) refused.update { it + (menu to it[menu].orEmpty() + PLAIN) }
            ApiResult(asked(call, parse(text)), emptyMap())
        }
        Reply.Records -> {
            val printed = records(text)
            yesNo.update { it + printed.flags }
            ApiResult(asked(call, printed.rows), emptyMap())
        }
        Reply.Pairs -> ApiResult(pairs(text), emptyMap())
        Reply.Count, Reply.Id, Reply.Text -> ApiResult(emptyList(), if (text.isBlank()) emptyMap() else mapOf("ret" to text.trim()))
        Reply.Nothing -> ApiResult(emptyList(), emptyMap())
    }

    private fun asked(call: ConsoleCall, rows: List<Map<String, String>>) =
        if (call.proplist.isEmpty()) rows else rows.map { row -> row.filterKeys { it == ".id" || it in call.proplist } }

    private fun parse(text: String): List<Map<String, String>> = try {
        rows(text)
    } catch (e: SerializationException) {
        throw DeviceException(ErrorCategory.Api, "the router answered with something other than rows: ${text.take(200)}", e)
    }

    private suspend fun slot() = try {
        slots.receive()
    } catch (e: ClosedReceiveChannelException) {
        throw ApiConnectionClosedException("the client is closed", e)
    }

    private suspend fun acquire(): ConsoleTerminal {
        val slot = slot()
        if (slot != null) return slot
        return try {
            ConsoleTerminal.open(open, dialect, encoding).also { if (it.expired) expired.value = true }
        } catch (e: Throwable) {
            slots.trySend(null)
            throw when {
                e is ApiConnectionClosedException -> lost(e)
                e is DeviceException && e.category == ErrorCategory.ConnectionLost -> dropped(e)
                e is CancellationException || e is DeviceException || e !is Exception -> e
                else -> lost(ApiConnectionClosedException(e.message ?: "the console did not open", e))
            }
        }
    }

    private fun release(console: ConsoleTerminal, healthy: Boolean) {
        val kept = console.takeIf { healthy }
        if (kept == null) console.close()
        returned(kept)
    }

    private fun returned(slot: ConsoleTerminal?) {
        if (!slots.trySend(slot).isSuccess) slot?.close()
    }

    private fun forget(console: ConsoleTerminal) {
        if (held.compareAndSet(console, null)) release(console, false)
    }
}
