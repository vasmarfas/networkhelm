package com.vasmarfas.networkhelm.routeros.api

import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.session.SafeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector

interface RouterOsLink {
    suspend fun call(words: List<String>): ApiResult

    // the rows of a print; a link that reads a big table in portions tells after each how many of how many it has
    suspend fun table(words: List<String>, progress: suspend (Int, Int) -> Unit): List<Map<String, String>> = call(words).rows

    fun command(words: List<String>, sent: CompletableDeferred<Unit>? = null): Flow<ApiReply>
}

interface RouterOsClient : RouterOsLink {
    fun <T> each(block: suspend FlowCollector<T>.(RouterOsLink) -> Unit): Flow<T>

    val status: Flow<LinkState>

    val safeMode: SafeMode? get() = null

    val passwordExpired: Boolean get() = false

    fun close()
}

suspend fun RouterOsLink.call(vararg words: String): ApiResult = call(words.asList())
