package com.vasmarfas.networkhelm.routeros.console

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.routeros.api.ApiConnectionClosedException
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.ssh.ShellChannel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

private val CONFIRM = Regex("\\[y/N\\]:? ?$")

// the consoles of a router are one process: one that waits a second for a busy one asks this, a yes ends them all, a no loses the line,
// and without an answer the console goes on by itself when the process comes back
private val RESTART = Regex("Restart console[^\n]*\\[y/N\\] ?$")
private val ARGUMENT = Regex("(?:^|\n)([A-Za-z][A-Za-z0-9-]*): ?$")
private val NEW_PASSWORD = Regex("new password> ?$")
private val LICENSE = Regex("software license\\? \\[Y/n\\]:? ?$")
private const val TAIL = 400
private const val QUIET_MILLIS = 150L
private const val ECHO_MILLIS = 2_000L
private const val ASK_MILLIS = 400L
private const val SAFE_MILLIS = 5_000L
private const val SILENT_MILLIS = 10_000L
private const val ANSWER_MILLIS = 120_000L
private const val CTRL_C: Byte = 0x03
private const val CTRL_X: Byte = 0x18

// the router keeps what is typed at its prompt in the history of the user and what is typed at the question of ask it does not;
// a line typed again right after itself is kept once, so the history gets one line however many commands go through
internal const val RELAY = "[[:parse [/terminal/ask prompt=\"NetworkHelm> \"]]]"
internal const val ASKING = "NetworkHelm>"

private fun silence(said: String) = "the console of the router fell silent in the middle of a line" + if (said.isBlank()) "" else ": ${said.trim().takeLast(160)}"

internal class SilentConsoleException(said: String) : ApiConnectionClosedException(silence(said))

// the router prints log messages with action echo into every console and redraws the prompt after each,
// so a prompt proves nothing: only the end marker, or the beginning one after a failure, ends a command
internal class ConsoleTerminal private constructor(private val channel: ShellChannel, private val encoding: ApiEncoding, private var relay: Boolean) {
    private val token = Random.nextInt(0x1000000).toString(16)
    private var count = 0

    private val cursor = Cursor(CONSOLE_ROWS, CONSOLE_COLUMNS)

    private var asked: String? = null

    var expired = false
        private set

    // RouterOS 6 now and then stops for good in the middle of an echo, at times after it has run the line: a quick line gives up
    // the silent console and is asked again, any other line gives up once its echo has stood still as long
    suspend fun run(line: String, quick: Boolean = false): Answer {
        val begin = "rd$token-${count}b"
        val end = "rd$token-${count++}e"
        asked = null
        val typed = ":put \"$begin\"; $line; :put \"$end\"\r"
        channel.write(encoding.encode(if (relay) "$RELAY\r$typed" else typed))
        val raw = read(begin, end, quick)
        if (relay && clean(raw).lines().none { it.trim() == ASKING }) relay = false
        return asked?.let { Answer(false, "missing value(s) of argument(s) $it") } ?: answer(raw, begin, end)
    }

    suspend fun safe(): Boolean {
        channel.write(byteArrayOf(CTRL_X))
        val screen = withTimeoutOrNull(SAFE_MILLIS) { read(null, null) } ?: return false
        return "<SAFE>" in clean(screen)
    }

    fun close() = channel.close()

    private suspend fun start() {
        read(null, null, quick = true)
    }

    private suspend fun read(begin: String?, end: String?, quick: Boolean = false): String {
        val received = Bytes(encoding)
        val chunk = ByteArray(32 * 1024)
        var confirmed = false
        var quiet = false
        var quietMillis = QUIET_MILLIS
        var plain = ""
        while (true) {
            val asking = if (quiet || asked != null) null else begin?.let { question(plain, received, it) }
            val size = when {
                quiet || asking != null -> withTimeoutOrNull(if (quiet) quietMillis else ASK_MILLIS) { channel.read(chunk, 0, chunk.size) }
                quick && !RESTART.containsMatchIn(plain) -> withTimeoutOrNull(SILENT_MILLIS) { channel.read(chunk, 0, chunk.size) }
                    ?: throw SilentConsoleException(plain)
                else -> withTimeoutOrNull(SILENT_MILLIS) { channel.read(chunk, 0, chunk.size) }
                    ?: if (end != null && !RESTART.containsMatchIn(plain) && end !in clean(received.text())) {
                        throw SilentConsoleException(plain)
                    } else {
                        withTimeoutOrNull(ANSWER_MILLIS - SILENT_MILLIS) { channel.read(chunk, 0, chunk.size) }
                            ?: throw ApiConnectionClosedException("the router did not answer in ${ANSWER_MILLIS / 1000} s")
                    }
            }
            if (size == null) {
                if (quiet) return received.text()
                // the console asks for a missing argument even in a script; Ctrl+C gives the line up
                asked = asking
                channel.write(byteArrayOf(CTRL_C))
                continue
            }
            if (size < 0) throw ApiConnectionClosedException("the console of the router ended")
            received.add(chunk, size)
            quiet = false
            for (answer in cursor.answers(decode(chunk, 0, size))) channel.write(answer.encodeToByteArray())
            plain = clean(received.text(maxOf(0, received.size - TAIL)))
            if (quick && begin == null && !expired && NEW_PASSWORD.containsMatchIn(plain)) {
                // a user whose password expired is asked for a new one in every console; Ctrl+C leaves the question
                expired = true
                channel.write(byteArrayOf(CTRL_C))
            }
            // a router that nobody logged in to over a console yet shows its license first
            if (quick && begin == null && LICENSE.containsMatchIn(plain)) channel.write("n\r".encodeToByteArray())
            when {
                PROMPT.containsMatchIn(plain) -> {
                    val seen = if (begin == null) "" else clean(received.text())
                    when {
                        end != null && "\n$end\n" in seen -> return received.text()
                        begin != null && "\n$begin\n" in seen -> return received.text()
                        // a prompt may be of an earlier message or redrawn halfway through the line, which a busy router leaves for a while;
                        // right after the echo it answers a line the router could not read
                        begin == null || begin in seen -> {
                            quiet = true
                            quietMillis = if (begin == null || end != null && end in seen) QUIET_MILLIS else ECHO_MILLIS
                        }
                    }
                }
                CONFIRM.containsMatchIn(plain) && !RESTART.containsMatchIn(plain) && !confirmed -> {
                    confirmed = true
                    channel.write("y\r".encodeToByteArray())
                }
            }
        }
    }

    private fun question(plain: String, received: Bytes, begin: String): String? {
        val name = ARGUMENT.find(plain)?.groupValues?.get(1) ?: return null
        return name.takeIf { "\n$begin\n" in clean(received.text()) }
    }

    private class Bytes(private val encoding: ApiEncoding) {
        private var data = ByteArray(8 * 1024)
        var size = 0
            private set

        fun add(chunk: ByteArray, count: Int) {
            if (size + count > data.size) data = data.copyOf(maxOf(data.size * 2, size + count))
            chunk.copyInto(data, size, 0, count)
            size += count
        }

        fun text(from: Int = 0): String = if (encoding == ApiEncoding.Windows1251) encoding.decode(data.copyOfRange(from, size)) else decode(data, from, size)
    }

    companion object {
        suspend fun open(channel: suspend () -> ShellChannel, dialect: Dialect, encoding: ApiEncoding): ConsoleTerminal {
            val console = ConsoleTerminal(channel(), encoding, dialect == Dialect.Serialize)
            try {
                console.start()
                if (dialect == Dialect.Classic) {
                    val answer = console.run(PRELUDE, quick = true)
                    if (!answer.ok) throw DeviceException(ErrorCategory.General, "the console of the router refused what the client defines: ${answer.text}")
                }
            } catch (e: Exception) {
                console.close()
                throw e
            }
            return console
        }
    }
}
