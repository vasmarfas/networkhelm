package com.vasmarfas.networkhelm.routeros.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private const val PORTION = 2_000
private const val PORTIONS = 16
private const val TRIES = 5
private const val PAUSE_MILLIS = 1_000L

// what print says of a row that went between the choice of the rows and the print: the console, REST and RouterOS 6;
// the console says it after the views it has already printed
private val GONE = listOf("interrupted", "Not Found", "no such item")

// the console and the web server of the router hold a whole answer in memory, so a table over a portion goes a part at a time
internal class BigTables {
    // menus that fit one answer or have no list to count
    private val small = MutableStateFlow(emptySet<String>())

    suspend fun read(
        words: List<String>,
        menu: String?,
        progress: suspend (Int, Int) -> Unit,
        whole: suspend (List<String>) -> ApiResult,
        portion: suspend (IntRange) -> List<Map<String, String>>?,
    ): ApiResult {
        if (menu == null) return whole(words)
        val total = size(words, menu, whole) ?: return whole(words).also { if (it.rows.size > PORTION) small.update { menus -> menus - menu } }
        val size = maxOf(PORTION, total / PORTIONS)
        val rows = LinkedHashMap<String, Map<String, String>>()
        for (from in 0 until total step size) {
            val part = tried(from until from + size, portion)
            if (part == null) {
                small.update { it + menu }
                return whole(words)
            }
            part.forEach { rows[it[".id"] ?: "#${rows.size}"] = it }
            progress(minOf(from + size, total), total)
        }
        return ApiResult(rows.values.toList(), emptyMap())
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

    private suspend fun tried(part: IntRange, portion: suspend (IntRange) -> List<Map<String, String>>?): List<Map<String, String>>? {
        var left = TRIES
        while (true) {
            try {
                return portion(part)
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
