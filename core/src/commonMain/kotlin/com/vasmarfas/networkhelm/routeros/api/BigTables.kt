package com.vasmarfas.networkhelm.routeros.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private const val PORTION = 2_000
private const val PORTIONS = 16
private const val TRIES = 5
private const val PAUSE_MILLIS = 1_000L

// a row printed and serialized at once costs the router about thirteen times its JSON, and such a read takes at most a quarter of the free memory;
// past a hundred thousand rows a slow router may take longer than the console waits for an answer
private const val ROW_COST = 16
private const val SHARE = 4
private const val AT_ONCE_MOST = 100_000

// what print says of a row that went between the choice of the rows and the print: the console, REST and RouterOS 6;
// the console says it after the views it has already printed
private val GONE = listOf("interrupted", "Not Found", "no such item")

// the console and the web server of the router hold a whole answer in memory, so a table over a portion goes a part at a time,
// unless the router has the memory for it: every portion looks through the whole table again
internal class BigTables {
    // menus that fit one answer or have no list to count
    private val small = MutableStateFlow(emptySet<String>())

    // the JSON of a row of the menu, as its first portion weighed it
    private val widths = MutableStateFlow(emptyMap<String, Int>())

    suspend fun read(
        words: List<String>,
        menu: String?,
        progress: suspend (Int, Int) -> Unit,
        whole: suspend (List<String>) -> ApiResult,
        atOnce: suspend () -> List<Map<String, String>> = { whole(words).rows },
        portion: suspend (IntRange) -> List<Map<String, String>>?,
    ): ApiResult {
        if (menu == null) return whole(words)
        val total = size(words, menu, whole) ?: return whole(words).also { if (it.rows.size > PORTION) small.update { menus -> menus - menu } }
        val size = maxOf(PORTION, total / PORTIONS)
        val rows = LinkedHashMap<String, Map<String, String>>()
        progress(0, total)
        val known = widths.value[menu]
        if (known != null && fits(known, total, whole)) return ApiResult(tried(atOnce), emptyMap())
        for (from in 0 until total step size) {
            val part = tried { portion(from until from + size) }
            if (part == null) {
                small.update { it + menu }
                return whole(words)
            }
            if (from == 0 && known == null && part.isNotEmpty()) {
                val width = part.sumOf { row -> row.entries.sumOf { (name, value) -> name.length + value.length + 6 } } / part.size
                widths.update { it + (menu to width) }
                if (fits(width, total, whole)) return ApiResult(tried(atOnce), emptyMap())
            }
            part.forEach { rows[it[".id"] ?: "#${rows.size}"] = it }
            progress(minOf(from + size, total), total)
        }
        return ApiResult(rows.values.toList(), emptyMap())
    }

    private suspend fun size(words: List<String>, menu: String, whole: suspend (List<String>) -> ApiResult): Int? {
        if (menu in small.value || words.any { it.startsWith("?.id=") || it.startsWith("=from=") }) return null
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

    private suspend fun <T> tried(read: suspend () -> T): T {
        var left = TRIES
        while (true) {
            try {
                return read()
            } catch (e: ApiTrapException) {
                val last = e.message.orEmpty().substringAfterLast('\n').trim()
                if (GONE.none { last.startsWith(it) }) throw e
                if (--left == 0) throw ApiTrapException(e.category, last)
            } catch (e: ApiConnectionClosedException) {
                if (--left == 0) throw e
                delay(PAUSE_MILLIS)
            }
        }
    }
}
