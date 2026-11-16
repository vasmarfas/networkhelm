package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

private val WORD = Regex("[a-z][a-z0-9-]*")

// the CLI of the router over RCI parse, a line at a time; each console logs in on its own, since the router keeps
// the context of the last command (interface, user) in the session
internal class KeeneticConsole(private val connect: () -> KeeneticCall) : SshSession {
    override suspend fun exec(command: String): ExecResult = throw unsupported()

    override suspend fun process(command: String): ByteStream = throw unsupported()

    override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = Console(connect())

    override suspend fun sftp(): SftpClient = throw unsupported()

    override suspend fun forward(host: String, port: Int): ByteStream = throw unsupported()

    override val canForward: Boolean get() = false

    override fun close() {}

    private fun unsupported() = DeviceException(ErrorCategory.Unsupported, "Keenetic gives its CLI over RCI only, a line at a time")
}

private class Console(private val api: KeeneticCall) : ShellChannel {
    private val typed = Channel<ByteArray>(Channel.UNLIMITED)
    private val keys = Channel<Byte>(Channel.UNLIMITED)
    private val shown = Channel<ByteArray>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rest: ByteArray? = null
    private var prompt = "(config)"

    @Volatile
    private var running: Job? = null

    // Ctrl+C stops a command that goes on, ping or traceroute; the rest waits its turn, as lines of a pasted script do
    init {
        print("$prompt> ")
        scope.launch {
            for (bytes in typed) for (byte in bytes) {
                val command = running
                if (byte.toInt() == 0x03 && command?.isActive == true) command.cancel() else keys.send(byte)
            }
            keys.close()
        }
        scope.launch { edit() }
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val chunk = rest ?: shown.receiveCatching().getOrNull() ?: return -1
        val count = minOf(length, chunk.size)
        chunk.copyInto(buffer, offset, 0, count)
        rest = if (count < chunk.size) chunk.copyOfRange(count, chunk.size) else null
        return count
    }

    override suspend fun write(bytes: ByteArray) {
        typed.send(bytes)
    }

    override fun resize(columns: Int, rows: Int) {}

    override fun close() {
        scope.cancel()
        typed.close()
        shown.close()
    }

    private fun print(text: String) {
        shown.trySend(text.encodeToByteArray())
    }

    private suspend fun edit() {
        val line = ArrayList<Byte>()
        val history = ArrayList<String>()
        var back = 0
        var escape = ""
        var lastReturn = false
        fun redraw(text: String) {
            line.clear()
            line.addAll(text.encodeToByteArray().toList())
            print("\r$prompt> $text\u001b[K")
        }
        for (byte in keys) {
            val char = byte.toInt() and 0xFF
            val afterReturn = lastReturn
            lastReturn = false
            when {
                escape.isNotEmpty() -> {
                    escape += char.toChar()
                    // an escape sequence ends with its first letter after ESC [ or ESC O
                    if (escape.length == 2 && char != '['.code && char != 'O'.code || escape.length > 2 && char in 0x40..0x7E) {
                        when (escape.drop(2)) {
                            "A" -> if (back < history.size) redraw(history[history.size - ++back])
                            "B" -> if (back > 0) redraw(if (--back == 0) "" else history[history.size - back])
                        }
                        escape = ""
                    }
                }
                char == 0x1B -> escape = "\u001b"
                char == '\n'.code && afterReturn -> {}
                char == '\r'.code || char == '\n'.code -> {
                    lastReturn = char == '\r'.code
                    val text = line.toByteArray().decodeToString().trim()
                    line.clear()
                    back = 0
                    print("\r\n")
                    if (text.isNotEmpty()) {
                        if (history.lastOrNull() != text) history += text
                        val command = scope.launch { run(text) }
                        running = command
                        command.join()
                    }
                    print("$prompt> ")
                }
                char == 0x7F || char == 0x08 -> if (line.isNotEmpty()) {
                    // a UTF-8 character ends with its continuation bytes, 10xxxxxx
                    var removed: Int
                    do removed = line.removeAt(line.lastIndex).toInt() while (line.isNotEmpty() && (removed and 0xC0) == 0x80)
                    print("\b \b")
                }
                char == 0x03 -> {
                    line.clear()
                    back = 0
                    print("^C\r\n$prompt> ")
                }
                char < 0x20 -> {}
                else -> {
                    line += byte
                    shown.trySend(byteArrayOf(byte))
                }
            }
        }
    }

    // a command that answers continued goes on in the router and gives the rest of its lines at its own path, the words
    // of the line up to its first value: tools ping 8.8.8.8 at tools/ping
    private suspend fun run(line: String) {
        val words = line.split(' ').filter { it.isNotEmpty() }.takeWhile { it.matches(WORD) }
        val paths = (words.size downTo 1).mapTo(ArrayList()) { words.take(it).joinToString("/") }
        var going = false
        try {
            val answer = api.post(listOf(JsonObject(mapOf("parse" to JsonPrimitive(line)))), raising = false).firstOrNull() as? JsonObject
            var result = answer?.get("parse") as? JsonObject ?: return
            (result["prompt"] as? JsonPrimitive)?.contentOrNull?.let { prompt = it }
            print(cliText(result))
            going = result.continues()
            while (going) {
                delay(1.seconds)
                result = more(paths) ?: return
                print(cliText(result))
                going = result.continues()
            }
        } catch (e: CancellationException) {
            if (going) withContext(NonCancellable) { paths.firstOrNull()?.let { stop(it) } }
            print("^C\r\n")
            throw e
        } catch (e: Exception) {
            print("% ${e.message ?: e::class.simpleName}\r\n")
        }
    }

    // a value taken for a word of the command leaves the router nothing at that path, so a shorter one is tried
    private suspend fun more(paths: MutableList<String>): JsonObject? {
        while (paths.isNotEmpty()) {
            val result = api.rest("GET", paths.first()) as? JsonObject ?: return null
            if (failure(result)?.startsWith("no input") != true) return result
            paths.removeAt(0)
        }
        return null
    }

    private suspend fun stop(path: String) {
        try {
            api.rest("DELETE", path)
        } catch (e: Exception) {
            print("% ${e.message ?: e::class.simpleName}\r\n")
        }
    }
}

private fun JsonObject.continues() = (this["continued"] as? JsonPrimitive)?.booleanOrNull == true

// as the CLI prints an answer: the lines of a text as they are, the fields of an object with their names right-aligned
// under one another between empty lines, an inner object under its name to the right of the colons, then the messages
internal fun cliText(result: JsonObject): String {
    val out = StringBuilder()
    val body = JsonObject(result - "prompt" - "status" - "continued")
    val lines = (body["message"] as? JsonArray)?.takeIf { body.size == 1 && it.all { line -> line is JsonPrimitive } }
    if (lines != null) {
        lines.forEach { out.append((it as JsonPrimitive).contentOrNull.orEmpty()).append("\r\n") }
    } else if (body.isNotEmpty()) {
        out.append("\r\n")
        fieldsText(body, 0, out)
        out.append("\r\n")
    }
    for (status in (result["status"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }) {
        fun text(name: String) = (status[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val error = text("status") == "error"
        out.append(text("ident")).append(if (error) " error[${text("code")}]: " else ": ").append(text("message")).append("\r\n")
    }
    return out.toString()
}

private fun fieldsText(fields: JsonObject, indent: Int, out: StringBuilder) {
    val width = (fields.keys.maxOfOrNull { it.length } ?: 0) + 3
    for ((key, inner) in fields) {
        val name = " ".repeat(indent + width - key.length) + key + ":"
        if (inner is JsonPrimitive || inner is JsonArray && inner.all { it is JsonPrimitive }) {
            out.append(name).append(' ').append(plain(inner)).append("\r\n")
            continue
        }
        for (element in inner as? JsonArray ?: listOf(inner)) {
            out.append(name).append(" \r\n")
            when (element) {
                is JsonObject -> fieldsText(element, indent + width + 2, out)
                else -> out.append(" ".repeat(indent + width + 2)).append(plain(element)).append("\r\n")
            }
        }
    }
}

private fun plain(value: JsonElement): String = when (value) {
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    is JsonArray -> value.joinToString(", ", transform = ::plain)
    is JsonObject -> value.toString()
}
