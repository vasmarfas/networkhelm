package com.vasmarfas.networkhelm.routeros.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlin.time.TimeSource

private val SNAPSHOTS = setOf("monitor", "monitor-traffic")
// the seconds a round takes: profile prints nothing in its first two seconds and torch in its first
private val TIMED = mapOf("profile" to 3L, "torch" to 2L)
private val CLOCK = Regex("(\\d+):(\\d\\d):(\\d\\d)(?:\\.(\\d{1,6}))?")
private val SPAN = Regex("(\\d+)(ms|us|[dhms]?)")
private val UNITS = mapOf("us" to 1L, "ms" to 1_000L, "" to 1_000_000L, "s" to 1_000_000L, "m" to 60_000_000L, "h" to 3_600_000_000L, "d" to 86_400_000_000L)
private const val ROUND_MILLIS = 1_000L
private const val RETRIES = 5
private const val DURATION = "=duration="
private const val COUNT = "=count="
private const val INTERVAL = "=interval="

// the console and REST answer when a command ends, so one that goes on until it is stopped is asked a round at a time;
// a table a round comes under the next .section, as the API sends them, and a ping a round is counted here
internal fun rounds(words: List<String>, call: suspend (List<String>) -> List<Map<String, String>>): Flow<ApiReply>? {
    val verb = words.first().substringAfterLast('/')
    return when {
        verb in SNAPSHOTS && "=once=" !in words -> snapshots(words, call)
        verb in TIMED -> timed(words, TIMED.getValue(verb), call)
        words.first() == "/tool/ping" -> ping(words, call)
        else -> null
    }
}

// a round takes at most a tenth of the router's time, as the reads of a list do
private fun snapshots(words: List<String>, call: suspend (List<String>) -> List<Map<String, String>>) = flow {
    var round = 0
    while (true) {
        val asked = TimeSource.Monotonic.markNow()
        val rows = call(words + "=once=")
        val spent = asked.elapsedNow().inWholeMilliseconds
        emitTable(rows, round++)
        delay(maxOf(ROUND_MILLIS, spent * 9))
    }
}

private fun timed(words: List<String>, seconds: Long, call: suspend (List<String>) -> List<Map<String, String>>): Flow<ApiReply>? {
    val given = words.firstOrNull { it.startsWith(DURATION) }
    val limit = given?.let { micros(it.removePrefix(DURATION))?.div(1_000_000) ?: return null }?.takeIf { it > 0 }?.let { (it + seconds - 1) / seconds }
    val asked = words.filter { it != given } + "$DURATION$seconds"
    return flow {
        var round = 0
        while (limit == null || round < limit) emitTable(afterTheLastRun(asked, call), round++)
    }
}

// a console closed in the middle of a round leaves the run to end by itself, and the router takes no second one meanwhile
private suspend fun afterTheLastRun(words: List<String>, call: suspend (List<String>) -> List<Map<String, String>>): List<Map<String, String>> {
    repeat(RETRIES) {
        try {
            return call(words)
        } catch (e: ApiTrapException) {
            if ("already running" !in e.message.orEmpty()) throw e
        }
        delay(ROUND_MILLIS)
    }
    return call(words)
}

private fun ping(words: List<String>, call: suspend (List<String>) -> List<Map<String, String>>): Flow<ApiReply>? {
    val count = words.firstOrNull { it.startsWith(COUNT) }?.let { it.removePrefix(COUNT).toLongOrNull() ?: return null }
    val pause = words.firstOrNull { it.startsWith(INTERVAL) }?.let { micros(it.removePrefix(INTERVAL))?.div(1_000) } ?: ROUND_MILLIS
    val asked = words.filter { !it.startsWith(COUNT) && !it.startsWith(INTERVAL) } + "${COUNT}1"
    return flow {
        val times = ArrayList<Long>()
        var sent = 0
        while (count == null || sent < count) {
            val started = TimeSource.Monotonic.markNow()
            val reply = call(asked).firstOrNull().orEmpty()
            val time = reply["time"]?.let(::micros)
            if (time != null) times += time
            val row = LinkedHashMap(reply)
            row.remove(".id")
            row["seq"] = (sent++).toString()
            if (time != null) row["time"] = routerTime(time)
            row["sent"] = sent.toString()
            row["received"] = times.size.toString()
            row["packet-loss"] = ((sent - times.size) * 100 / sent).toString()
            if (times.isNotEmpty()) {
                row["min-rtt"] = routerTime(times.min())
                row["avg-rtt"] = routerTime(times.sum() / times.size)
                row["max-rtt"] = routerTime(times.max())
            }
            emit(ApiReply("!re", row, null))
            if (count == null || sent < count) delay((pause - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(0))
        }
    }
}

// REST gives every second of a run under its own .section; the last one is the table of the round
private suspend fun FlowCollector<ApiReply>.emitTable(rows: List<Map<String, String>>, round: Int) {
    val last = rows.mapNotNull { it[".section"]?.toIntOrNull() }.maxOrNull()
    for (row in rows) if (last == null || row[".section"]?.toIntOrNull() == last) emit(ApiReply("!re", row + (".section" to round.toString()), null))
}

// 61us and 1ms234us as the API writes a time, 00:00:00.000061 as the console prints it
internal fun micros(time: String): Long? {
    CLOCK.matchEntire(time)?.let { clock ->
        val (hours, minutes, seconds, fraction) = clock.destructured
        return ((hours.toLong() * 60 + minutes.toLong()) * 60 + seconds.toLong()) * 1_000_000 + fraction.padEnd(6, '0').toLong()
    }
    val parts = SPAN.findAll(time).toList()
    if (parts.isEmpty() || parts.sumOf { it.value.length } != time.length) return null
    return parts.sumOf { it.groupValues[1].toLong() * UNITS.getValue(it.groupValues[2]) }
}

internal fun routerTime(micros: Long): String = buildString {
    var rest = micros
    for ((unit, size) in listOf("s" to 1_000_000L, "ms" to 1_000L, "us" to 1L)) {
        val part = rest / size
        rest %= size
        if (part > 0) append(part).append(unit)
    }
    if (isEmpty()) append("0us")
}
