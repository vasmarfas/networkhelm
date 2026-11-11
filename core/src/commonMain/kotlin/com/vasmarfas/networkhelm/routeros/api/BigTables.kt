package com.vasmarfas.networkhelm.routeros.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private const val PORTION = 2_000
private const val PORTIONS = 16
private const val TRIES = 5
private const val PAUSE_MILLIS = 1_000L

// a part that a row gone under its print broke twice goes in halves, down to single rows, at most this many times a part
private const val HALF_TRIES = 2
private const val HALVES_MOST = 64

// a row printed and serialized at once costs the router about thirteen times its JSON, and such a read takes at most a quarter of the free memory;
// past a hundred thousand rows a slow router may take longer than the console waits for an answer
private const val ROW_COST = 16
private const val SHARE = 4
private const val AT_ONCE_MOST = 100_000

// what print says of a row that went between the choice of the rows and the print: the console, REST and RouterOS 6;
// the console says it after the views it has already printed
private val GONE = listOf("interrupted", "Not Found", "no such item")

internal fun ApiTrapException.gone() = message.orEmpty().substringAfterLast('\n').trim().let { last -> GONE.any { last.startsWith(it) } }

// the console and the web server of the router hold a whole answer in memory, so a table over a portion goes a part at a time,
// unless the router has the memory for it: every portion looks through the whole table again
internal class BigTables {
    // menus that fit one answer or have no list to count
    private val small = MutableStateFlow(emptySet<String>())

    // the JSON of a row of the menu, as its first portion weighed it
    private val widths = MutableStateFlow(emptyMap<String, Int>())

    // menus whose rows come and go faster than the whole table prints, like the connections of a busy router
    private val restless = MutableStateFlow(emptySet<String>())

    suspend fun read(
        words: List<String>,
        menu: String?,
        progress: suspend (Int, Int) -> Unit,
        whole: suspend (List<String>) -> ApiResult,
        atOnce: suspend () -> List<Map<String, String>> = { whole(words).rows },
        portion: suspend (IntRange) -> List<Map<String, String>>?,
    ): ApiResult {
        if (menu == null) return whole(words)
        // rows asked for by their ids are no table to take in parts: a part would ask for rows a second time
        if (words.any { it.startsWith("=from=") }) return tried { whole(words) }
        val total = size(words, menu, whole) ?: return printed(words, whole, portion).also { if (it.rows.size > PORTION) small.update { menus -> menus - menu } }
        val size = maxOf(PORTION, total / PORTIONS)
        val rows = LinkedHashMap<String, Map<String, String>>()
        progress(0, total)
        val known = widths.value[menu]
        if (known != null && menu !in restless.value && fits(known, total, whole)) {
            unlessGone(atOnce)?.let { return ApiResult(it, emptyMap()) }
            restless.update { it + menu }
        }
        for (from in 0 until total step size) {
            val part = parted(from until from + size, portion)
            if (part == null) {
                small.update { it + menu }
                return tried { whole(words) }
            }
            if (from == 0 && known == null && part.isNotEmpty()) {
                val width = part.sumOf { row -> row.entries.sumOf { (name, value) -> name.length + value.length + 6 } } / part.size
                widths.update { it + (menu to width) }
                if (menu !in restless.value && fits(width, total, whole)) {
                    unlessGone(atOnce)?.let { return ApiResult(it, emptyMap()) }
                    restless.update { it + menu }
                }
            }
            part.forEach { rows[it[".id"] ?: "#${rows.size}"] = it }
            progress(minOf(from + size, total), total)
        }
        return ApiResult(rows.values.toList(), emptyMap())
    }

    // a table of one answer that a row gone under the print keeps breaking goes in halves
    private suspend fun printed(words: List<String>, whole: suspend (List<String>) -> ApiResult, portion: suspend (IntRange) -> List<Map<String, String>>?): ApiResult {
        repeat(HALF_TRIES) {
            try {
                return whole(words)
            } catch (e: ApiTrapException) {
                if (!e.gone()) throw e
            }
        }
        val count = try {
            whole(listOf(words.first(), "=count-only=") + words.filter { it.startsWith("?") }).done["ret"]?.toIntOrNull()
        } catch (e: ApiTrapException) {
            null
        }
        return ApiResult(count?.let { parted(0 until it, portion) } ?: tried { whole(words) }.rows, emptyMap())
    }

    // a part is asked again in halves while a row of it goes before the print; a single row that went is left out, and past
    // the halves of a read the words of the router stand
    private suspend fun parted(range: IntRange, portion: suspend (IntRange) -> List<Map<String, String>>?): List<Map<String, String>>? {
        var halved = 0
        suspend fun part(range: IntRange): List<Map<String, String>>? {
            try {
                return tried(HALF_TRIES) { portion(range) }
            } catch (e: ApiTrapException) {
                if (!e.gone() || ++halved > HALVES_MOST) throw e
            }
            if (range.first == range.last) return emptyList()
            val middle = (range.first + range.last + 1) / 2
            val first = part(range.first until middle) ?: return null
            return first + (part(middle..range.last) ?: return null)
        }
        return part(range)
    }

    private suspend fun <T> unlessGone(read: suspend () -> T): T? = try {
        tried(HALF_TRIES, read)
    } catch (e: ApiTrapException) {
        if (!e.gone()) throw e
        null
    }

    private suspend fun size(words: List<String>, menu: String, whole: suspend (List<String>) -> ApiResult): Int? {
        if (menu in small.value || words.any { it.startsWith("?.id=") }) return null
        val query = words.filter { it.startsWith("?") }
        val count = try {
            whole(listOf(words.first(), "=count-only=") + query).done["ret"]?.toIntOrNull()
        } catch (e: ApiTrapException) {
            null
        }
        if (count != null && count > PORTION) return count
        if (count == null || query.isEmpty()) small.update { it + menu }
        return null
    }

    private suspend fun fits(width: Int, total: Int, whole: suspend (List<String>) -> ApiResult): Boolean {
        if (total > AT_ONCE_MOST) return false
        val free = try {
            whole(listOf("/system/resource/print")).rows.firstOrNull()?.get("free-memory")?.toLongOrNull()
        } catch (e: ApiTrapException) {
            null
        } ?: return false
        return total.toLong() * width * ROW_COST <= free / SHARE
    }

    private suspend fun <T> tried(tries: Int = TRIES, read: suspend () -> T): T {
        var gone = tries
        var closed = TRIES
        while (true) {
            try {
                return read()
            } catch (e: ApiTrapException) {
                if (!e.gone()) throw e
                if (--gone == 0) throw ApiTrapException(e.category, e.message.orEmpty().substringAfterLast('\n').trim())
            } catch (e: ApiConnectionClosedException) {
                if (--closed == 0) throw e
                delay(PAUSE_MILLIS)
            }
        }
    }
}
