package com.vasmarfas.networkhelm.nettools

import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.closingAfter
import com.vasmarfas.networkhelm.transport.openTcp
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.milliseconds

private const val WHOIS_LIMIT = 1 shl 20
private const val IANA = "whois.iana.org"

private val referral = Regex("^(?:refer|whois):[ \\t]*(\\S+)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
private val registrarServer = Regex("^[ \\t]*Registrar WHOIS Server:[ \\t]*(\\S+)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
private val serverName = Regex("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+")

// Complete: the server ended its answer; Cut: it stopped or stalled after sending something; Silent: nothing came; Unreachable: no connection
enum class WhoisEnd { Complete, Cut, Silent, Unreachable }

class WhoisReply(val server: String, val text: String, val end: WhoisEnd)

fun whoisReferral(answer: String): String? = namedServer(referral, answer)

fun registrarWhois(answer: String): String? = namedServer(registrarServer, answer)

// IANA names the registry, a thin registry of a domain can name the registrar's server as well;
// a step that fails keeps what it had received and the chain ends there
suspend fun whois(
    query: String,
    timeoutMillis: Int = 8_000,
    open: suspend (String, Int, Int) -> ByteStream = ::openTcp,
): List<WhoisReply> {
    val asked = asciiDomain(query.trim())
    val replies = ArrayList<WhoisReply>()
    suspend fun ask(server: String): WhoisReply = askServer(server, asked, timeoutMillis, open).also { replies += it }
    val registry = whoisReferral(ask(IANA).text) ?: return replies
    val detail = ask(registry)
    registrarWhois(detail.text)?.takeIf { !it.equals(registry, ignoreCase = true) }?.let { ask(it) }
    return replies
}

// the answer of the registry beats the referral of IANA, which stays only when there is nothing better
fun whoisText(replies: List<WhoisReply>): String {
    val answers = replies.filter { it.text.isNotBlank() }
    return answers.filter { it.server != IANA }.ifEmpty { answers }.joinToString("\n\n") { "% ${it.server}\n${it.text.trimEnd()}" }
}

private fun namedServer(pattern: Regex, answer: String): String? =
    pattern.find(answer)?.groupValues?.get(1)?.trimEnd('.')?.takeIf { serverName.matches(it) }

private suspend fun askServer(server: String, query: String, timeoutMillis: Int, open: suspend (String, Int, Int) -> ByteStream): WhoisReply {
    val stream = try {
        open(server, 43, timeoutMillis)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return WhoisReply(server, "", WhoisEnd.Unreachable)
    }
    val parts = ArrayList<ByteArray>()
    var size = 0
    val ended = try {
        stream.closingAfter(timeoutMillis.milliseconds) { connection ->
            connection.write("$query\r\n".encodeToByteArray())
            val buffer = ByteArray(8192)
            while (size < WHOIS_LIMIT) {
                val read = connection.read(buffer, 0, buffer.size)
                if (read < 0) break
                parts += buffer.copyOf(read)
                size += read
            }
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
    val bytes = ByteArray(size)
    var at = 0
    for (part in parts) {
        part.copyInto(bytes, at)
        at += part.size
    }
    val end = when {
        size == 0 -> WhoisEnd.Silent
        ended -> WhoisEnd.Complete
        else -> WhoisEnd.Cut
    }
    return WhoisReply(server, bytes.decodeToString(), end)
}
