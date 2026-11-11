package com.vasmarfas.networkhelm.routeros.api

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor

class ApiTrapException(val category: Int?, message: String) : Exception(message)

open class ApiConnectionClosedException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ApiResult(val rows: List<Map<String, String>>, val done: Map<String, String>)

class ApiConnection(
    private val stream: ByteStream,
    private val encoding: ApiEncoding,
    scope: CoroutineScope,
) : RouterOsLink {
    private val writeLock = Mutex()
    private val stateLock = Mutex()
    private val pending = HashMap<String, Channel<ApiReply>>()
    private var nextTag = 0L
    private var closedBy: Throwable? = null
    private val closed = CompletableDeferred<ApiConnectionClosedException>()

    @Volatile
    internal var heard = 0L
        private set

    private val reader = scope.launch { readLoop() }

    private val dispatcher = scope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default

    override fun command(words: List<String>, sent: CompletableDeferred<Unit>?): Flow<ApiReply> =
        exchange(words, sent).filter { it.type == "!re" }

    override suspend fun call(words: List<String>): ApiResult {
        val replies = exchange(words).toList()
        return ApiResult(replies.filter { it.type == "!re" }.map { it.attributes }, replies.last().attributes)
    }

    suspend fun login(user: String, password: String) {
        val challenge = call("/login", "=name=$user", "=password=$password").done["ret"] ?: return
        // RouterOS before 6.43 ignores the password and answers with a challenge for an MD5 response
        val secret = challenge.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val digest = md5(byteArrayOf(0) + encoding.encode(password) + secret)
        call("/login", "=name=$user", "=response=00" + digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
    }

    suspend fun awaitClosed(): ApiConnectionClosedException = closed.await()

    fun close(reason: ApiConnectionClosedException = ApiConnectionClosedException("closed by the client")) {
        closed.complete(reason)
        stream.close()
        reader.cancel()
    }

    // locks are taken on the connection's dispatcher: a Mutex waiter on a busy UI thread would stall the reader
    private fun exchange(words: List<String>, sent: CompletableDeferred<Unit>? = null): Flow<ApiReply> = flow {
        val replies = Channel<ApiReply>(Channel.UNLIMITED)
        val tag = stateLock.withLock {
            closedBy?.let { throw ApiConnectionClosedException("connection closed", it) }
            (nextTag++).toString().also { pending[it] = replies }
        }
        var finished = false
        try {
            send(words + ".tag=$tag")
            sent?.complete(Unit)
            for (reply in replies) {
                when (reply.type) {
                    "!re" -> emit(reply)
                    "!done" -> {
                        finished = true
                        emit(reply)
                        return@flow
                    }
                    "!trap" -> {
                        finished = true
                        throw ApiTrapException(reply.attributes["category"]?.toIntOrNull(), reply.attributes["message"].orEmpty())
                    }
                    "!fatal" -> {
                        finished = true
                        throw ApiConnectionClosedException(reply.attributes["message"].orEmpty())
                    }
                }
            }
            throw ApiConnectionClosedException("connection closed")
        } finally {
            withContext(NonCancellable) {
                val open = stateLock.withLock {
                    pending.remove(tag)
                    closedBy == null
                }
                if (!finished && open) runCatching { send(listOf("/cancel", "=tag=$tag")) }
            }
        }
    }.flowOn(dispatcher)

    private suspend fun send(words: List<String>) {
        val bytes = try {
            encodeSentence(words.map(encoding::encode))
        } catch (e: IllegalArgumentException) {
            throw DeviceException(ErrorCategory.ArgumentValue, e.message.orEmpty(), e)
        }
        writeLock.withLock {
            try {
                stream.write(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ApiConnectionClosedException("connection closed", e)
            }
        }
    }

    private suspend fun readLoop() {
        val sentences = SentenceReader(stream::read)
        val names = HashMap<String, String>()
        var cause: Throwable? = null
        try {
            while (true) {
                val reply = parseReply(sentences.next()?.map(encoding::decode) ?: break, names)
                heard++
                val target = reply.tag?.let { tag -> stateLock.withLock { pending[tag] } }
                if (target != null) {
                    target.trySend(reply)
                } else if (reply.type == "!fatal") {
                    cause = ApiConnectionClosedException(reply.attributes["message"].orEmpty())
                    break
                }
            }
        } catch (e: CancellationException) {
            cause = e
            throw e
        } catch (e: Exception) {
            cause = e
        } finally {
            withContext(NonCancellable) {
                stateLock.withLock {
                    val reason = ApiConnectionClosedException("connection closed", cause)
                    closedBy = reason
                    closed.complete(reason)
                    pending.values.forEach { it.close(reason) }
                    pending.clear()
                }
            }
            stream.close()
        }
    }
}
