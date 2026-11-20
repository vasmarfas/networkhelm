package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.BufferedStream
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.HttpResponse
import com.vasmarfas.networkhelm.transport.body
import com.vasmarfas.networkhelm.transport.closingAfter
import com.vasmarfas.networkhelm.transport.closingUnanswered
import com.vasmarfas.networkhelm.transport.head
import com.vasmarfas.networkhelm.transport.httpExchange
import com.vasmarfas.networkhelm.transport.httpRequest
import com.vasmarfas.networkhelm.transport.keepsAlive
import com.vasmarfas.networkhelm.transport.readHead
import com.vasmarfas.networkhelm.transport.readToEnd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// the API of the container engine of a host, which the CLI of the engine relays to a channel of the session (system dial-stdio).
// Docker is reached as the login when it gets in (as root, in the group docker, or to a rootless engine its context names), then
// at the socket a rootless Docker of the login keeps, then as root through sudo; Podman as root while its system service runs,
// else as the login. Reads share kept connections, everything else goes on a connection of its own
internal class DockerApi(private val ssh: SshSession, private val root: Root, private val tools: suspend () -> Set<String>) {
    // the relay to the socket of the engine and its CLI, which runs as root where the relay does
    private class Way(val relay: suspend () -> ByteStream, val cli: String, val asRoot: Boolean = false)

    private val reaching = Mutex()
    private var way: Way? = null
    private val negotiating = Mutex()
    private var versioned: String? = null
    private var offset: String? = null
    private val reads = Semaphore(MAX_READS)
    private val pool = Mutex()
    private val idle = ArrayDeque<Pair<BufferedStream, TimeMark>>()

    suspend fun get(path: String): JsonElement {
        val target = target(path)
        return parsed(target, reads.withPermit { read(target) })
    }

    // a change goes over a connection of its own: a kept one that proves dead would leave it unknown whether the change was made;
    // 304 is the answer to a start of a running container or a stop of a stopped one, nothing to do
    suspend fun send(method: String, path: String, json: JsonElement? = null, headers: Map<String, String> = emptyMap()): HttpResponse =
        send(method, path, json?.toString()?.encodeToByteArray() ?: ByteArray(0), typed(json, headers))

    suspend fun send(method: String, path: String, content: ByteArray, headers: Map<String, String>): HttpResponse {
        val target = target(path)
        val answer = relay()().closingAfter(CHANGE_TIME) { httpRequest(it, HOST, method, target, headers, content) }
        if (answer.status !in 200..299 && answer.status != 304) throw failure(target, answer.status, answer.body)
        return answer
    }

    // a change whose answer names what it made: a container, an exec, an image
    suspend fun created(path: String, json: JsonElement? = null): JsonObject =
        parsed(path, send("POST", path, json)) as? JsonObject ?: throw DeviceException(ErrorCategory.Api, "$path gave no JSON")

    // the CLI as a shell of the login runs it, for a terminal in a container
    suspend fun cli(): String {
        val way = way()
        return if (way.asRoot && !root.login()) "sudo ${way.cli}" else way.cli
    }

    // the CLI of the engine for a line of shell, which runs as root where the relay does; the guard puts things back with it
    suspend fun bareCli(): String = way().cli

    suspend fun isPodman(): Boolean = way().cli.endsWith("podman")

    // a rootless engine runs as the login, reached by its socket or by the context of the login: no interface of the host and no
    // file of root is its own
    suspend fun rootless(): Boolean = (get("/info") as? JsonObject)?.texts("SecurityOptions")?.any { "name=rootless" in it } == true

    // a line of shell on the host as the engine is reached: as root where the relay runs as root, as the login else
    suspend fun shell(line: String, input: String? = null): ExecResult {
        val way = way()
        return when {
            way.asRoot && input != null -> root.exec(line, input)
            way.asRoot -> root.exec(line)
            input != null -> ssh.exec(line, input)
            else -> ssh.exec(line)
        }
    }

    // the CLI of the engine on the host, for what the API would carry over the session and back: an image saved to a file there
    suspend fun command(line: String): String = checked(shell("${bareCli()} $line"))

    // the engine gives its times in UTC, the tables show them in the time of the host
    suspend fun offset(): String = offset ?: ssh.exec("date +%z").output.trim().also { offset = it }

    // events, stats and the progress of a pull: a JSON object a line, until the engine ends them or the caller stops reading
    fun lines(method: String, path: String, json: JsonElement? = null, headers: Map<String, String> = emptyMap()): Flow<String> = flow {
        streamed(method, path, json, headers) { stream ->
            val lines = BufferedStream(stream)
            while (true) {
                val line = lines.readLine(MAX_LINE_BYTES) ?: break
                if (line.isNotBlank()) emit(line)
            }
        }
    }

    fun log(path: String): Flow<Pair<String, String>> = flow { streamed("GET", path, null, emptyMap()) { emitAll(logLines(it)) } }

    // past the head of the answer the connection carries the input and the output of the process, of exec as of attach
    suspend fun hijack(path: String, json: JsonElement): ByteStream {
        val target = target(path)
        val stream = relay()()
        try {
            val content = json.toString().encodeToByteArray()
            stream.write(head("POST", target, HOST, typed(json, UPGRADE), content.size).encodeToByteArray() + content)
            val source = BufferedStream(stream)
            val (status, fields) = readHead(source)
            if (status != 101 && status != 200) throw failure(target, status, readToEnd(body(source, fields), MAX_ERROR_BYTES))
            return source
        } catch (e: Throwable) {
            stream.close()
            throw e
        }
    }

    // an answer read as it comes, on a connection of its own that ends with the block
    private suspend fun streamed(method: String, path: String, json: JsonElement?, headers: Map<String, String>, block: suspend (ByteStream) -> Unit) {
        val target = target(path)
        relay()().closingAfter { stream ->
            val content = json?.toString()?.encodeToByteArray()
            stream.write(head(method, target, HOST, typed(json, headers), content?.size).encodeToByteArray() + (content ?: ByteArray(0)))
            val source = BufferedStream(stream)
            val (status, fields) = readHead(source)
            if (status !in 200..299) throw failure(target, status, readToEnd(body(source, fields), MAX_ERROR_BYTES))
            block(body(source, fields))
        }
    }

    // a kept connection that proves dead is tried again on a new one: a read changes nothing
    private suspend fun read(target: String): HttpResponse {
        val kept = pool.withLock {
            while (idle.isNotEmpty()) {
                val (connection, since) = idle.removeLast()
                if (since.elapsedNow() < MAX_IDLE) return@withLock connection
                connection.close()
            }
            null
        }
        if (kept != null) exchange(kept, target)?.let { return it }
        return exchange(BufferedStream(relay()()), target) ?: throw DeviceException(ErrorCategory.ConnectionLost, "the server closed the connection without an answer")
    }

    private suspend fun exchange(connection: BufferedStream, target: String): HttpResponse? {
        val answer = try {
            connection.closingUnanswered(REQUEST_TIME) { httpExchange(connection, HOST, "GET", target, emptyMap(), ByteArray(0)) }
        } catch (e: Throwable) {
            connection.close()
            throw e
        }
        if (answer?.keepsAlive() != true) {
            connection.close()
            return answer
        }
        try {
            pool.withLock { if (idle.size < MAX_KEPT) idle.addLast(connection to TimeSource.Monotonic.markNow()) else connection.close() }
        } catch (e: CancellationException) {
            connection.close()
            throw e
        }
        return answer
    }

    // asked once: paths without a version would follow the engine into whatever its newest API answers
    private suspend fun prefix(): String = versioned ?: negotiating.withLock {
        versioned ?: run {
            val engine = parsed("/version", read("/version")) as? JsonObject
            val version = negotiated(engine?.text("ApiVersion").orEmpty(), engine?.text("MinAPIVersion").orEmpty())
            (if (version.isEmpty()) "" else "/v$version").also { versioned = it }
        }
    }

    // a path that names its own version goes as it is: the API of Podman for its pods is not the API of Docker
    private suspend fun target(path: String): String = if (OWN_VERSION.matches(path)) path else prefix() + path

    private suspend fun relay(): suspend () -> ByteStream = way().relay

    private suspend fun way(): Way = way ?: reaching.withLock { way ?: reach().also { way = it } }

    // podman-docker puts a script named docker in front of podman, and that one has no dial-stdio
    private suspend fun reach(): Way {
        val present = tools()
        val podman = "podman" in present && ("docker" !in present || "podman" in ssh.exec("docker --version").output.lowercase())
        return when {
            podman -> podman()
            "docker" in present -> docker()
            else -> throw DeviceException(ErrorCategory.Unsupported, "the host has neither docker nor podman")
        }
    }

    private suspend fun docker(): Way {
        val login = ssh.exec("$DOCKER </dev/null")
        if (login.exitStatus == 0) return Way({ ssh.process(DOCKER) }, "docker")
        if (ssh.exec("$ROOTLESS $DOCKER </dev/null").exitStatus == 0) return Way({ ssh.process("$ROOTLESS $DOCKER") }, "$ROOTLESS docker")
        if (!login.error.contains("permission denied", ignoreCase = true)) throw unreachable(login)
        return rooted("docker")
    }

    // the system service of Podman listens at a socket only root may open; without it the login has a rootless Podman of its own
    private suspend fun podman(): Way {
        if (ssh.exec(PODMAN_SYSTEM).exitStatus == 0) return rooted("podman")
        if (ssh.exec("$PODMAN </dev/null").exitStatus != 0) {
            val served = ssh.exec(PODMAN_SERVICE)
            if (served.exitStatus != 0) throw unreachable(served)
        }
        return Way({ ssh.process(PODMAN) }, "podman")
    }

    private suspend fun rooted(cli: String): Way {
        val relay = "$cli system dial-stdio"
        val probe = root.exec("$relay </dev/null")
        if (probe.exitStatus != 0) throw unreachable(probe)
        return Way({ root.process(relay) }, cli, asRoot = true)
    }

    private fun parsed(target: String, answer: HttpResponse): JsonElement {
        if (answer.status !in 200..299) throw failure(target, answer.status, answer.body)
        return try {
            Json.parseToJsonElement(answer.body.decodeToString())
        } catch (e: SerializationException) {
            throw DeviceException(ErrorCategory.Api, "$target gave no JSON", e)
        }
    }

    private companion object {
        const val HOST = "docker"
        const val DOCKER = "docker system dial-stdio"
        const val ROOTLESS = $$"""DOCKER_HOST=unix://${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/docker.sock"""
        const val PODMAN = "podman system dial-stdio"
        const val PODMAN_SYSTEM = $$"""[ "$(id -u)" != 0 ] && [ -S /run/podman/podman.sock ]"""

        // a Podman whose socket unit is off serves the API from a process of the login that ends a quarter of an hour after its last use
        const val PODMAN_SERVICE = "nohup podman system service --time=900 >/dev/null 2>&1 </dev/null & " +
            $$"""i=0; until podman system dial-stdio </dev/null 2>/dev/null; do i=$((i+1)); [ $i -ge 20 ] && exec podman system dial-stdio </dev/null; sleep 0.25; done"""

        const val MAX_READS = 3
        const val MAX_KEPT = 2
        const val MAX_LINE_BYTES = 1024 * 1024
        val MAX_IDLE = 30.seconds
        val REQUEST_TIME = 2.minutes
        val CHANGE_TIME = 10.minutes
        val UPGRADE = mapOf("Upgrade" to "tcp")
        val OWN_VERSION = Regex("/v\\d.*")
    }
}

// the version of the API both ends know: the engine's own up to the newest this client was checked against, and never below the
// oldest the engine still takes; an engine that names none gets paths without a version
internal fun negotiated(engine: String, oldest: String): String {
    val newest = if (newer(engine, NEWEST_API)) NEWEST_API else engine
    return if (newer(oldest, newest)) oldest else newest
}

private fun newer(version: String, than: String): Boolean {
    val a = version.split('.').map { it.toIntOrNull() ?: 0 }
    val b = than.split('.').map { it.toIntOrNull() ?: 0 }
    return ((0 until maxOf(a.size, b.size)).map { a.getOrElse(it) { 0 } - b.getOrElse(it) { 0 } }.firstOrNull { it != 0 } ?: 0) > 0
}

// the lines of a log or of the output of a process, each with its stream. Without a terminal the engine frames every piece: a byte
// of the stream (1 out, 2 err, 3 an error of the engine), three zeros and the length; with one it sends the bytes as they come.
// docker logs knows which from the terminal of the container, here the first eight bytes tell it
internal fun logLines(source: ByteStream): Flow<Pair<String, String>> = flow {
    val header = ByteArray(FRAME_HEADER)
    val chunk = ByteArray(16 * 1024)
    val out = StreamLines()
    val err = StreamLines()
    var got = filled(source, header)
    if (got == FRAME_HEADER && framed(header)) {
        while (got == FRAME_HEADER) {
            if (!framed(header)) throw DeviceException(ErrorCategory.Api, "the engine sent a frame of the unknown stream ${header[0]}")
            var left = (header[4].toLong() and 0xFF shl 24) or (header[5].toLong() and 0xFF shl 16) or (header[6].toLong() and 0xFF shl 8) or (header[7].toLong() and 0xFF)
            if (header[0].toInt() == 3) {
                val text = ByteArray(minOf(left, MAX_ERROR_BYTES.toLong()).toInt())
                filled(source, text)
                throw DeviceException(ErrorCategory.Api, text.decodeToString().trim())
            }
            val (name, lines) = if (header[0].toInt() == 2) "stderr" to err else "stdout" to out
            while (left > 0) {
                val count = source.read(chunk, 0, minOf(left, chunk.size.toLong()).toInt())
                if (count < 0) throw DeviceException(ErrorCategory.ConnectionLost, "the log ended inside a frame")
                left -= count
                lines.add(chunk, count).forEach { emit(name to it) }
            }
            got = filled(source, header)
        }
        if (got > 0) throw DeviceException(ErrorCategory.ConnectionLost, "the log ended inside a frame")
    } else {
        out.add(header, got).forEach { emit("stdout" to it) }
        while (true) {
            val count = source.read(chunk, 0, chunk.size)
            if (count < 0) break
            out.add(chunk, count).forEach { emit("stdout" to it) }
        }
    }
    out.rest()?.let { emit("stdout" to it) }
    err.rest()?.let { emit("stderr" to it) }
}

private fun framed(header: ByteArray) = header[0].toInt() in 0..3 && (1..3).all { header[it].toInt() == 0 }

// what the stream gives until the buffer is full or the stream ends
private suspend fun filled(source: ByteStream, buffer: ByteArray): Int {
    var size = 0
    while (size < buffer.size) {
        val count = source.read(buffer, size, buffer.size - size)
        if (count < 0) break
        size += count
    }
    return size
}

// the bytes of one stream cut into lines as they come: a line is decoded once whole, so a character split between two pieces
// stays whole; a line longer than the limit is cut there
private class StreamLines {
    private var bytes = ByteArray(256)
    private var size = 0

    fun add(data: ByteArray, count: Int): List<String> {
        val lines = mutableListOf<String>()
        for (i in 0 until count) {
            if (data[i] == LF) {
                lines += take()
                continue
            }
            if (size == MAX_LOG_LINE) lines += take()
            if (size == bytes.size) bytes = bytes.copyOf(size * 2)
            bytes[size++] = data[i]
        }
        return lines
    }

    fun rest(): String? = if (size > 0) take() else null

    private fun take(): String = bytes.decodeToString(0, if (size > 0 && bytes[size - 1] == CR) size - 1 else size).also { size = 0 }
}

// a sample of the stats of a container: the CPU in percent of one CPU over the time from the sample before it to this one (the
// system CPU time Podman sends is not the time of the host, so it cannot stand in for it as in docker stats), the memory without
// the page cache the kernel may drop, the bytes of all networks and disks; what the sample lacks stays empty
internal fun dockerStats(line: String): Map<String, String> = statsOf(jsonObject(line))

// the CPU time a sample counts and when it was read, to count the share of a later sample of the same container against
internal fun cpuReading(sample: JsonObject): Pair<Long, Instant>? {
    val used = sample.inner("cpu_stats")?.inner("cpu_usage")?.long("total_usage") ?: return null
    return Instant.parseOrNull(sample.text("read"))?.let { used to it }
}

// the share against an earlier reading where the sample carries none of its own, as one-shot stats do
internal fun statsOf(sample: JsonObject, earlier: Pair<Long, Instant>? = null): Map<String, String> {
    val now = cpuReading(sample)
    // before its first sample the engine sends the zero time of Go, the year 1
    val before = earlier ?: sample.inner("precpu_stats")?.inner("cpu_usage")?.long("total_usage")?.let { used ->
        Instant.parseOrNull(sample.text("preread"))?.takeIf { it.epochSeconds > 0 }?.let { used to it }
    }
    val wall = if (now != null && before != null) (now.second - before.second).inWholeNanoseconds else 0L
    val hundredths = if (now != null && before != null && wall > 0) ((now.first - before.first).coerceAtLeast(0) * 100.0 / wall * 100).roundToLong() else null
    val share = hundredths?.let { "${it / 100}.${(it % 100).toString().padStart(2, '0')}" }.orEmpty()
    val memory = sample.inner("memory_stats")
    val usage = memory?.long("usage")
    val cache = listOf("total_inactive_file", "inactive_file").mapNotNull { memory?.inner("stats")?.long(it) }.firstOrNull { usage != null && it < usage } ?: 0L
    val networks = sample.inner("networks")?.values?.mapNotNull { it as? JsonObject }
    val disks = (sample.inner("blkio_stats")?.get("io_service_bytes_recursive") as? JsonArray)?.mapNotNull { it as? JsonObject }
    fun network(key: String) = networks?.sumOf { it.long(key) ?: 0L }?.toString().orEmpty()
    fun disk(op: String) = disks?.filter { it.text("op").equals(op, ignoreCase = true) }?.sumOf { it.long("value") ?: 0L }?.toString().orEmpty()
    return linkedMapOf(
        "time" to sample.text("read"),
        "cpu" to share,
        "memory" to usage?.let { "${it - cache}" }.orEmpty(),
        "memory-limit" to memory?.long("limit")?.toString().orEmpty(),
        "rx-byte" to network("rx_bytes"),
        "tx-byte" to network("tx_bytes"),
        "read-byte" to disk("read"),
        "write-byte" to disk("write"),
        "pids" to sample.inner("pids_stats")?.long("current")?.toString().orEmpty(),
    )
}

// an event of the engine: what happened (Action) to what (Type, the ID and the attributes of the actor)
internal fun dockerEvent(line: String): Map<String, String> {
    val event = jsonObject(line)
    val actor = event.inner("Actor")
    val attributes = actor?.inner("Attributes")?.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
    return linkedMapOf(
        "time" to event.text("time"),
        "time-nano" to event.text("timeNano"),
        "type" to event.text("Type"),
        "action" to event.text("Action"),
        "id" to actor?.text("ID").orEmpty(),
        "name" to attributes["name"].orEmpty(),
        "image" to attributes["image"].orEmpty(),
        "attributes" to attributes.filterKeys { it != "name" && it != "image" }.entries.joinToString(", ") { "${it.key}=${it.value}" },
    )
}

// a line of a pull or a push: the layer or the tag it is about, its status and, while it moves, how far it got, in bytes unless
// units says otherwise; a failure comes as a line of its own inside an answer that began well
internal fun dockerProgress(line: String): Map<String, String> {
    val step = jsonObject(line)
    val failure = step.inner("errorDetail")?.text("message")?.ifEmpty { null } ?: step.text("error").ifEmpty { null }
    if (failure != null) throw DeviceException(ErrorCategory.Api, failure)
    val detail = step.inner("progressDetail")
    return linkedMapOf(
        "id" to step.text("id"),
        "status" to step.text("status"),
        "current" to detail?.text("current").orEmpty(),
        "total" to detail?.text("total").orEmpty(),
        "units" to detail?.text("units").orEmpty(),
    )
}

// the engine says what went wrong in the message of a JSON object; that text reaches the user as it came
private fun failure(target: String, status: Int, content: ByteArray): DeviceException {
    val text = content.decodeToString().trim()
    val json = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    }
    val message = json?.text("message")?.ifEmpty { null } ?: text.ifEmpty { "HTTP $status on $target" }
    val category = when (status) {
        400, 409 -> ErrorCategory.ArgumentValue
        401, 403 -> ErrorCategory.PermissionDenied
        404 -> ErrorCategory.MissingItem
        else -> ErrorCategory.Api
    }
    return DeviceException(category, message)
}

// the output of a command that went well, or what it said on stderr
internal fun checked(result: ExecResult): String {
    if (result.exitStatus != 0) throw DeviceException(ErrorCategory.Api, result.error.trim().ifEmpty { result.output.trim() }.ifEmpty { "exit ${result.exitStatus}" })
    return result.output
}

private fun unreachable(result: ExecResult) = DeviceException(
    ErrorCategory.Api,
    "the app cannot reach the container engine: " + result.error.trim().ifEmpty { result.output.trim() }.ifEmpty { "exit ${result.exitStatus}" },
)

private fun typed(json: JsonElement?, headers: Map<String, String>) = if (json == null) headers else headers + ("Content-Type" to "application/json")

private fun jsonObject(line: String): JsonObject {
    val element = try {
        Json.parseToJsonElement(line)
    } catch (e: SerializationException) {
        throw DeviceException(ErrorCategory.Api, "the engine sent a line that is no JSON object", e)
    }
    return element as? JsonObject ?: throw DeviceException(ErrorCategory.Api, "the engine sent a line that is no JSON object")
}

internal fun Map<String, JsonElement>.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun Map<String, JsonElement>.inner(key: String) = this[key] as? JsonObject

internal fun Map<String, JsonElement>.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

internal fun Map<String, JsonElement>.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true

internal fun Map<String, JsonElement>.texts(key: String) = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

internal fun Map<String, JsonElement>.objects(key: String) = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

internal fun objects(element: JsonElement, path: String) = (element as? JsonArray)?.mapNotNull { it as? JsonObject } ?: throw DeviceException(ErrorCategory.Api, "$path gave no list")

// a removal of what is gone already leaves the host as it was asked to be: a second DELETE of a volume answers 404
internal suspend fun removed(remove: suspend () -> Unit) {
    try {
        remove()
    } catch (e: DeviceException) {
        if (e.category != ErrorCategory.MissingItem) throw e
    }
}

// a step back after a failure: its own failure stays with the first one, which is the one the user needs to read
internal suspend fun undo(failure: Exception, step: suspend () -> Unit) {
    try {
        step()
    } catch (e: DeviceException) {
        failure.addSuppressed(e)
    }
}

// what inspect shows: the JSON of the engine as it came, laid out to read
internal fun laidOut(element: JsonElement): String = PRETTY.encodeToString(JsonElement.serializer(), element)

private val PRETTY = Json { prettyPrint = true }

// the newest API this client was checked against: Docker 29.9.3
private const val NEWEST_API = "1.56"
private const val FRAME_HEADER = 8
private const val MAX_ERROR_BYTES = 64 * 1024
private const val MAX_LOG_LINE = 1024 * 1024
private const val CR = '\r'.code.toByte()
private const val LF = '\n'.code.toByte()
