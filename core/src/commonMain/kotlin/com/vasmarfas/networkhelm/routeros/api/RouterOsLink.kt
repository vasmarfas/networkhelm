package com.vasmarfas.networkhelm.routeros.api

import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.session.SafeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

private const val ROUND_MILLIS = 1_000L
private const val LONGEST_WAIT_MILLIS = 30_000L

interface RouterOsLink {
    suspend fun call(words: List<String>): ApiResult

    // the rows of a print; a link that reads a big table in portions tells after each how many of how many it has
    suspend fun table(words: List<String>, progress: suspend (Int, Int) -> Unit): List<Map<String, String>> = call(words).rows

    fun command(words: List<String>, sent: CompletableDeferred<Unit>? = null): Flow<ApiReply>

    // the words print of a menu takes, from the description of the menu; a link that learns them from refusals asks fewer times
    fun printTakes(menu: MenuPath, words: Collection<String>) = Unit
}

interface RouterOsClient : RouterOsLink {
    fun <T> each(block: suspend FlowCollector<T>.(RouterOsLink) -> Unit): Flow<T>

    val status: Flow<LinkState>

    val safeMode: SafeMode? get() = null

    val passwordExpired: Boolean get() = false

    fun close()
}

suspend fun RouterOsLink.call(vararg words: String): ApiResult = call(words.asList())

// a watch of a client that asks for every answer starts over after a lost link: a round at least between its tries, fewer of them
// while the link stays down, and at once when the link is back
internal fun <T> outliving(link: StateFlow<LinkState>, block: suspend FlowCollector<T>.() -> Unit): Flow<T> = flow {
    var wait = ROUND_MILLIS
    while (true) {
        try {
            block()
            return@flow
        } catch (e: ApiConnectionClosedException) {
            if (link.value == LinkState.Closed) throw e
            delay(ROUND_MILLIS)
            withTimeoutOrNull(wait - ROUND_MILLIS) { link.first { it != LinkState.Reconnecting } }
            wait = if (link.value == LinkState.Up) ROUND_MILLIS else minOf(wait * 2, LONGEST_WAIT_MILLIS)
        }
    }
}
