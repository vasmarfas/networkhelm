package com.vasmarfas.networkhelm.routeros.console

import com.vasmarfas.networkhelm.model.duration
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.api.ApiTrapException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val ESCAPES = Regex("\u001b(?:\\[[0-9;?]*[ -/]*[@-~]|[()*+][0-9A-Za-z]|[=>78DEHMNOZc])")
private val SINCE_EPOCH = Regex("(19[7-9][0-9])-(\\d\\d)-(\\d\\d) (\\d\\d):(\\d\\d):(\\d\\d)")
private val MAPPED = Regex("::ffff:(\\d{1,3}(\\.\\d{1,3}){3})")
internal val PROMPT = Regex("\\[[^\\]\\n]*\\] (<SAFE>|>) ?$")

private val ECHOED = Regex("^(\\S+ )?\\d\\d:\\d\\d:\\d\\d echo: ")
private val BUSY = Regex("^Console does not respond\\.$|^Restart console [^\n]*\\[y/N\\] ?$|^Console responded, action cancelled$")

internal fun clean(raw: String): String = raw.replace(ESCAPES, "").replace("\r", "")

// a stray byte is read as cp1251, in which RouterOS 6 keeps text typed in WinBox 3 on Russian Windows
internal fun decode(bytes: ByteArray, from: Int, to: Int): String = buildString(to - from) {
    var at = from
    while (at < to) {
        val first = bytes[at].toInt() and 0xFF
        val size = when {
            first < 0x80 -> 1
            first in 0xC2..0xDF -> 2
            first in 0xE0..0xEF -> 3
            first in 0xF0..0xF4 -> 4
            else -> 0
        }
        val sequence = if (size < 2 || at + size > to) null else try {
            bytes.decodeToString(at, at + size, throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }
        when {
            size == 1 -> append(first.toChar())
            sequence != null -> append(sequence)
            else -> append(ApiEncoding.Windows1251.decode(byteArrayOf(bytes[at])))
        }
        at += if (sequence != null) size else 1
    }
}

internal class Answer(val ok: Boolean, val text: String)

internal fun answer(raw: String, begin: String, end: String): Answer {
    val lines = clean(raw).split('\n').filterNot { ECHOED.containsMatchIn(it) || BUSY.containsMatchIn(it) }
    val from = lines.indexOf(begin)
    val to = lines.lastIndexOf(end)
    if (from >= 0 && to > from) return Answer(true, lines.subList(from + 1, to).joinToString("\n"))
    val body = lines.subList(from + 1, lines.size).dropLastWhile { it.isBlank() }.let { if (it.lastOrNull()?.let(PROMPT::containsMatchIn) == true) it.dropLast(1) else it }
    return Answer(false, (if (from >= 0) body.joinToString("\n") else body.lastOrNull { it.isNotBlank() }.orEmpty()).trim())
}

internal fun trap(message: String): ApiTrapException {
    val category = when {
        message.startsWith("no such") || message.startsWith("bad command name") || message.startsWith("syntax error") ||
            message.startsWith("expected end of command") -> 0
        message.startsWith("invalid ") || message.startsWith("input does not match") || message.startsWith("expected") ||
            message.startsWith("bad parameter") || message.startsWith("missing") -> 1
        message.startsWith("failure:") -> 4
        else -> null
    }
    return ApiTrapException(category, message)
}

internal fun rows(text: String): List<Map<String, String>> {
    val tables = text.lines().filter { it.isNotBlank() }.map(::table).ifEmpty { listOf(table(text)) }
    if (tables.size == 1) return tables.single()
    val joined = LinkedHashMap<String, MutableMap<String, String>>()
    for (table in tables) table.forEachIndexed { at, row ->
        joined.getOrPut(row[".id"] ?: "#$at") { LinkedHashMap() }.putAll(row)
    }
    return joined.values.toList()
}

// the first table is the plain view, the second detail
internal fun plainAddsNothing(text: String): Boolean {
    val tables = text.lines().filter { it.isNotBlank() }.map(::table)
    val detail = tables.getOrNull(1)?.associateBy { it[".id"] } ?: return false
    val plain = tables.first().filter { it[".id"] in detail }
    return plain.isNotEmpty() && plain.all { row -> detail.getValue(row[".id"]).keys.containsAll(row.keys) }
}

private fun table(text: String): List<Map<String, String>> = when (val json = Json.parseToJsonElement(text.trim())) {
    is JsonArray -> json.mapNotNull { (it as? JsonObject)?.let(::row) }
    is JsonObject -> listOf(row(json))
    else -> emptyList()
}

private fun row(json: JsonObject): Map<String, String> = buildMap {
    for ((key, element) in json) text(element)?.let { put(key, it) }
}

// :serialize writes a time of a day or more as the date that many seconds after 1970
private fun text(json: JsonElement): String? = when (json) {
    is JsonNull -> null
    is JsonArray -> json.joinToString(",") { text(it).orEmpty() }
    is JsonObject -> json.toString()
    is JsonPrimitive -> when {
        json.isString -> sinceEpoch(unmapped(json.content))
        '.' in json.content && json.content.toDoubleOrNull() != null -> json.content.trimEnd('0').trimEnd('.')
        else -> json.content
    }
}

// :put writes the elements of an array one after another, each starting where a key comes again
internal fun pairs(text: String): List<Map<String, String>> {
    val rows = ArrayList<Map<String, String>>()
    var row = LinkedHashMap<String, String>()
    for (piece in text.trim().split(';').filter { it.isNotEmpty() }) {
        val key = piece.substringBefore('=')
        if (key in row) {
            rows += row
            row = LinkedHashMap()
        }
        row[key] = piece.substringAfter('=', "")
    }
    if (row.isNotEmpty()) rows += row
    return rows
}

internal fun unmapped(text: String): String =MAPPED.matchEntire(text)?.groupValues?.get(1) ?: text

private fun sinceEpoch(text: String): String {
    val (year, month, day, hour, minute, second) = (SINCE_EPOCH.matchEntire(text) ?: return text).destructured
    return duration(days(year.toInt(), month.toInt(), day.toInt()) * 86_400 + hour.toLong() * 3_600 + minute.toLong() * 60 + second.toLong())
}

private fun days(year: Int, month: Int, day: Int): Long {
    val shifted = if (month <= 2) year - 1 else year
    val era = shifted / 400
    val yearOfEra = shifted - era * 400
    val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097L + dayOfEra - 719_468
}
