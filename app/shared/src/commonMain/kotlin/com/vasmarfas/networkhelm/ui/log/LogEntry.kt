package com.vasmarfas.networkhelm.ui.log

import com.vasmarfas.networkhelm.model.Item
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class Severity { Debug, Info, Warning, Error, Critical }

private val severityWords = mapOf(
    "debug" to Severity.Debug,
    "info" to Severity.Info,
    "notice" to Severity.Info,
    "warning" to Severity.Warning,
    "warn" to Severity.Warning,
    "error" to Severity.Error,
    "err" to Severity.Error,
    "critical" to Severity.Critical,
    "crit" to Severity.Critical,
    "alert" to Severity.Critical,
    "emerg" to Severity.Critical,
)

internal fun severityOf(word: String): Severity? = severityWords[word.lowercase()]

// seconds since 1970-01-01 as if the router clock kept UTC; it orders and dates lines, it is no instant
class Stamp(val seconds: Long, val date: String, val clock: String)

class LogEntry(val id: String, val time: String, val topics: String, val message: String, val stamp: Stamp?, val values: Map<String, String> = emptyMap()) {
    val words: List<String> = topics.split(',', '.').map { it.trim() }.filter { it.isNotEmpty() }

    val severity: Severity = words.mapNotNull(::severityOf).maxOrNull() ?: Severity.Info

    val tags: List<String> = words.filter { severityOf(it) != Severity.Info }

    val line: String get() = listOf(time, topics, message).filter { it.isNotEmpty() }.joinToString(" ")

    val fields: String get() = (listOf(".id: $id") + values.map { (name, value) -> "$name: $value" }).joinToString("\n")
}

fun Item.toEntry(today: String?): LogEntry {
    val time = this["time"].orEmpty()
    return LogEntry(id, time, this["topics"].orEmpty(), this["message"].orEmpty(), stampOf(time, today), values)
}

fun List<LogEntry>.around(entry: LogEntry, reach: Int = 5): List<LogEntry> {
    val at = indexOfFirst { it.id == entry.id }
    return if (at < 0) emptyList() else subList((at - reach).coerceAtLeast(0), (at + reach + 1).coerceAtMost(size))
}

class LogRow(val entries: List<LogEntry>) {
    val first: LogEntry get() = entries.first()
    val last: LogEntry get() = entries.last()
    val key: String get() = first.id
    val count: Int get() = entries.size
}

fun List<LogEntry>.collapsed(): List<LogRow> {
    val rows = ArrayList<LogRow>()
    var group = ArrayList<LogEntry>()
    for (entry in this) {
        val head = group.firstOrNull()
        if (head != null && (head.topics != entry.topics || head.message != entry.message)) {
            rows += LogRow(group)
            group = ArrayList()
        }
        group += entry
    }
    if (group.isNotEmpty()) rows += LogRow(group)
    return rows
}

private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
private val isoTime = Regex("""^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})""")
private val monthDayYear = Regex("""^([A-Za-z]{3})/(\d{1,2})/(\d{4}) (\d{2}):(\d{2}):(\d{2})""")
private val monthDay = Regex("""^([A-Za-z]{3})(?:/| +)(\d{1,2}) (\d{2}):(\d{2}):(\d{2})""")
private val logread = Regex("""^(?:[A-Za-z]{3} )?([A-Za-z]{3}) +(\d{1,2}) (\d{2}):(\d{2}):(\d{2}) (\d{4})""")
private val timeOfDay = Regex("""^(\d{2}):(\d{2}):(\d{2})""")
private val isoDate = Regex("""^(\d{4})-(\d{2})-(\d{2})""")

// oct/22 07:08:26 and Oct 22 07:08:26 of Keenetic are of this year and 07:08:26 of today, so they need the date of the router as yyyy-MM-dd;
// a time of the kernel log, as 12.345678, is seconds since boot and has no stamp
fun stampOf(time: String, today: String?): Stamp? {
    val text = time.trim()
    isoTime.find(text)?.destructured?.let { (year, month, day, hour, minute, second) ->
        return stamp(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
    }
    monthDayYear.find(text)?.destructured?.let { (month, day, year, hour, minute, second) ->
        return stamp(year.toInt(), monthOf(month), day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
    }
    logread.find(text)?.destructured?.let { (month, day, hour, minute, second, year) ->
        return stamp(year.toInt(), monthOf(month), day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
    }
    val now = today?.let { isoDate.find(it)?.destructured?.toList()?.map(String::toInt) } ?: return null
    monthDay.find(text)?.destructured?.let { (month, day, hour, minute, second) ->
        val candidate = stamp(now[0], monthOf(month), day.toInt(), hour.toInt(), minute.toInt(), second.toInt()) ?: return null
        // a line of the last days of the year read in the first days of the next one
        val tomorrow = days(now[0], now[1], now[2]) + 2
        return if (candidate.seconds < tomorrow * 86_400) candidate else stamp(now[0] - 1, monthOf(month), day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
    }
    timeOfDay.find(text)?.destructured?.let { (hour, minute, second) ->
        return stamp(now[0], now[1], now[2], hour.toInt(), minute.toInt(), second.toInt())
    }
    return null
}

private fun monthOf(name: String) = months.indexOf(name.lowercase()) + 1

private fun stamp(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Stamp? {
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
    val seconds = days(year, month, day) * 86_400 + hour * 3_600 + minute * 60 + second
    fun two(value: Int) = value.toString().padStart(2, '0')
    return Stamp(seconds, "${year.toString().padStart(4, '0')}-${two(month)}-${two(day)}", "${two(hour)}:${two(minute)}:${two(second)}")
}

private fun days(year: Int, month: Int, day: Int): Long {
    val shifted = if (month <= 2) year - 1 else year
    val era = (if (shifted >= 0) shifted else shifted - 399) / 400
    val yearOfEra = shifted - era * 400
    val monthIndex = if (month > 2) month - 3 else month + 9
    val dayOfYear = (153 * monthIndex + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097L + dayOfEra - 719_468
}

enum class AgoUnit { Now, Seconds, Minutes, Hours, Days }

class Ago(val unit: AgoUnit, val count: Long)

fun ago(seconds: Long): Ago = when {
    seconds < 5 -> Ago(AgoUnit.Now, 0)
    seconds < 60 -> Ago(AgoUnit.Seconds, seconds)
    seconds < 3_600 -> Ago(AgoUnit.Minutes, seconds / 60)
    seconds < 86_400 -> Ago(AgoUnit.Hours, seconds / 3_600)
    else -> Ago(AgoUnit.Days, seconds / 86_400)
}

enum class Span(val seconds: Long?) { Any(null), Minutes5(300), Minutes15(900), Hour(3_600), Day(86_400), Today(null) }

class LogFilter(
    val severities: Set<Severity> = emptySet(),
    val topics: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val query: String = "",
    val span: Span = Span.Any,
    val message: String? = null,
    val now: Long? = null,
) {
    val terms: List<String> = query.split(' ').filter { it.isNotEmpty() }

    val empty: Boolean get() = severities.isEmpty() && topics.isEmpty() && hidden.isEmpty() && terms.isEmpty() && span == Span.Any && message == null

    fun passes(entry: LogEntry): Boolean {
        if (severities.isNotEmpty() && entry.severity !in severities) return false
        if (topics.isNotEmpty() && entry.words.none { word -> topics.any { it.equals(word, ignoreCase = true) } }) return false
        if (hidden.isNotEmpty() && entry.words.any { word -> hidden.any { it.equals(word, ignoreCase = true) } }) return false
        if (message != null && entry.message != message) return false
        if (terms.isNotEmpty()) {
            val text = entry.line
            if (terms.any { !text.contains(it, ignoreCase = true) }) return false
        }
        return inSpan(entry)
    }

    private fun inSpan(entry: LogEntry): Boolean {
        val stamp = entry.stamp ?: return true
        val at = now ?: return true
        val from = if (span == Span.Today) at - at.mod(86_400L) else at - (span.seconds ?: return true)
        return stamp.seconds >= from
    }
}

fun List<LogEntry>.filtered(rules: LogFilter): List<LogEntry> = if (rules.empty) this else filter { rules.passes(it) }

fun List<LogEntry>.topicCounts(): List<Pair<String, Int>> = flatMap { entry -> entry.words.distinctBy { it.lowercase() } }
    .filter { severityOf(it) == null }
    .groupingBy { it }.eachCount()
    .toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

fun List<LogEntry>.severityCounts(): Map<Severity, Int> = groupingBy { it.severity }.eachCount()

fun List<LogEntry>.asLines(): String = joinToString("\n") { it.line }

fun List<LogEntry>.asMessages(): String = joinToString("\n") { it.message }

fun List<LogEntry>.asCsv(): String {
    fun cell(text: String) = if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    return (listOf("time,topics,message") + map { listOf(it.time, it.topics, it.message).joinToString(",", transform = ::cell) }).joinToString("\r\n")
}

private val pretty = Json { prettyPrint = true }

fun List<LogEntry>.asJson(): String {
    val lines: List<JsonElement> = map { entry ->
        JsonObject(mapOf("time" to JsonPrimitive(entry.time), "topics" to JsonArray(entry.words.map { JsonPrimitive(it) }), "message" to JsonPrimitive(entry.message)))
    }
    return pretty.encodeToString(JsonArray.serializer(), JsonArray(lines))
}
