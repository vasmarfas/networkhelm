package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// the engine at the far end of one relay: a request gets the first answer its line starts with, and after one that upgrades
// the bytes go raw. Through sudo the first line is the password, which sudo takes before the engine sees anything; an engine
// that serves fewer requests than come leaves the rest unanswered, as one whose relay has ended
internal class DockerEngine(private val answers: List<Pair<String, ByteArray>>, private val sudo: Boolean = false, private val serves: Int = Int.MAX_VALUE) : ByteStream {
    val requests = mutableListOf<String>()
    val raw = StringBuilder()
    var password: String? = null
        private set
    private var received = ByteArray(0)
    private var answer = ByteArray(0)
    private var at = 0
    private var upgraded = false

    override suspend fun write(bytes: ByteArray) {
        if (upgraded) {
            raw.append(bytes.decodeToString())
            return
        }
        received += bytes
        if (sudo && password == null) {
            val end = received.indexOf('\n'.code.toByte())
            if (end < 0) return
            password = received.decodeToString(0, end)
            received = received.copyOfRange(end + 1, received.size)
        }
        while (true) {
            val text = received.decodeToString()
            val end = text.indexOf("\r\n\r\n")
            if (end < 0) return
            val size = end + 4 + (Regex("content-length: (\\d+)", RegexOption.IGNORE_CASE).find(text.take(end))?.groupValues?.get(1)?.toInt() ?: 0)
            if (received.size < size) return
            val request = text.take(size)
            received = received.copyOfRange(size, received.size)
            requests += request
            if (requests.size > serves) return
            answer += answers.firstOrNull { request.startsWith(it.first) }?.second ?: ENGINE_NOT_FOUND.encodeToByteArray()
            if ("Upgrade: tcp" in request) {
                upgraded = true
                return
            }
        }
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (at == answer.size) return -1
        val count = minOf(length, answer.size - at, 7)
        answer.copyInto(buffer, offset, at, at + count)
        at += count
        return count
    }

    override fun close() = Unit
}

// answers a command by how it starts and gives each relay an engine of its own
internal class DockerHost(private val commands: List<Pair<String, ExecResult>>, private val engine: (String) -> DockerEngine) : SshSession {
    val ran = mutableListOf<String>()
    // the commands that read their input, with what they read
    val given = mutableListOf<Pair<String, String>>()
    val relays = mutableListOf<Pair<String, DockerEngine>>()

    override suspend fun exec(command: String): ExecResult {
        ran += command
        return commands.firstOrNull { command.startsWith(it.first) }?.second ?: ExecResult("", "not found", 127)
    }

    override suspend fun exec(command: String, input: String): ExecResult {
        given += command to input
        return exec(command)
    }

    override suspend fun process(command: String): ByteStream = engine(command).also { relays += command to it }
    override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
    override suspend fun sftp(): SftpClient = error("no sftp")
    override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
    override fun close() = Unit
}

// bytes that come a few at a time, as a channel hands them over
internal class Trickle(private val data: ByteArray) : ByteStream {
    private var at = 0

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (at == data.size) return -1
        val count = minOf(length, data.size - at, 5)
        data.copyInto(buffer, offset, at, at + count)
        at += count
        return count
    }

    override suspend fun write(bytes: ByteArray) = Unit
    override fun close() = Unit
}

internal fun engineAnswer(json: String, status: String = "200 OK") =
    "HTTP/1.1 $status\r\nApi-Version: 1.56\r\nContent-Type: application/json\r\nContent-Length: ${json.encodeToByteArray().size}\r\n\r\n$json".encodeToByteArray()

// a body the engine did not measure before sending, in two chunks
internal fun engineChunked(json: String) = ("HTTP/1.1 200 OK\r\nApi-Version: 1.56\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n" +
    listOf(json.take(json.length / 2), json.drop(json.length / 2)).joinToString("") { "${it.encodeToByteArray().size.toString(16)}\r\n$it\r\n" } + "0\r\n\r\n").encodeToByteArray()

// a stream as the engine writes it: every object with its line end in a chunk of its own
internal fun engineStream(lines: List<String>, end: String, type: String = "application/json") = ("HTTP/1.1 200 OK\r\nApi-Version: 1.56\r\nContent-Type: $type\r\nTransfer-Encoding: chunked\r\n\r\n" +
    lines.joinToString("") { "${(it + end).encodeToByteArray().size.toString(16)}\r\n$it$end\r\n" } + "0\r\n\r\n").encodeToByteArray()

internal val ENGINE_NO_CONTENT = "HTTP/1.1 204 No Content\r\nApi-Version: 1.56\r\n\r\n".encodeToByteArray()

private const val ENGINE_NOT_FOUND = "HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: 29\r\n\r\n{\"message\":\"page not found\"}\n"

class LinuxDockerApiTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private fun docker(host: DockerHost, password: String? = null) = DockerApi(host, Root(host, password)) { setOf("docker") }

    private fun reachable(vararg answers: Pair<String, ByteArray>) =
        DockerHost(listOf("docker system dial-stdio </dev/null" to ok())) { DockerEngine(listOf("GET /version " to engineAnswer(VERSION_29)) + answers) }

    private fun frame(stream: Int, payload: ByteArray) =
        byteArrayOf(stream.toByte(), 0, 0, 0, (payload.size shr 24).toByte(), (payload.size shr 16).toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + payload

    private fun String.line() = substringBefore("\r\n")

    @Test
    fun aLoginThatReachesDockerAsksTheVersionOnceAndReadsOverOneKeptConnection() = runTest {
        val host = reachable("GET /v1.56/volumes " to engineChunked(VOLUMES_29), "GET /v1.56/containers/nh-sample-web/top " to engineAnswer(TOP_29))
        val api = docker(host)
        assertEquals("nh-sample-vol", api.get("/volumes").jsonObject["Volumes"]?.jsonArray?.single()?.jsonObject?.get("Name")?.jsonPrimitive?.content)
        assertEquals("sleep 1", api.get("/containers/nh-sample-web/top").jsonObject["Processes"]?.jsonArray?.last()?.jsonArray?.last()?.jsonPrimitive?.content)
        assertEquals(listOf("docker system dial-stdio"), host.relays.map { it.first })
        assertEquals(
            listOf("GET /version HTTP/1.1", "GET /v1.56/volumes HTTP/1.1", "GET /v1.56/containers/nh-sample-web/top HTTP/1.1"),
            host.relays.single().second.requests.map { it.line() },
        )
    }

    @Test
    fun podmanBehindTheScriptOfPodmanDockerIsReachedAsRootWhileItsSystemServiceRuns() = runTest {
        val host = DockerHost(
            listOf(
                "docker --version" to ok("podman version 5.8.9\n"),
                $$"""[ "$(id -u)" != 0 ] && [ -S /run/podman/podman.sock ]""" to ok(),
                "id -u" to ok("1001\n"),
                "sudo -n true" to ok(),
                "sudo -n sh -c 'podman system dial-stdio </dev/null'" to ok(),
            ),
        ) { DockerEngine(listOf("GET /version " to engineAnswer(VERSION_PODMAN_5), "GET /v1.44/volumes " to engineAnswer("""{"Volumes":[],"Warnings":[]}"""))) }
        val api = DockerApi(host, Root(host, null)) { setOf("docker", "podman") }
        assertEquals(0, api.get("/volumes").jsonObject["Volumes"]?.jsonArray?.size)
        assertEquals(listOf("sudo -n sh -c 'podman system dial-stdio'"), host.relays.map { it.first })
        assertEquals("GET /v1.44/volumes HTTP/1.1", host.relays.single().second.requests.last().line())
        assertTrue(host.ran.none { it.startsWith("docker system") })
    }

    @Test
    fun aLoginOutsideTheGroupDockerGoesThroughSudoAndItsPasswordStopsAtSudo() = runTest {
        val host = DockerHost(
            listOf(
                "docker system dial-stdio </dev/null" to ExecResult("", "permission denied while trying to connect to the docker API at unix:///var/run/docker.sock\n", 1),
                "DOCKER_HOST=" to ExecResult("", "failed to connect to the docker API at unix:///run/user/1000/docker.sock: dial unix /run/user/1000/docker.sock: connect: no such file or directory\n", 1),
                "id -u" to ok("1000\n"),
                "sudo -n true" to ExecResult("", "sudo: a password is required\n", 1),
                "sudo -S -p '' true" to ok(),
                "sudo -S -p '' sh -c 'docker system dial-stdio </dev/null'" to ok(),
            ),
        ) { DockerEngine(listOf("GET /version " to engineAnswer(VERSION_29), "POST /v1.56/containers/nh-sample-web/restart" to ENGINE_NO_CONTENT), sudo = true) }
        docker(host, PASSWORD).send("POST", "/containers/nh-sample-web/restart?t=5")
        assertEquals(listOf("sudo -k -S -p '' sh -c 'docker system dial-stdio'"), host.relays.map { it.first }.distinct())
        assertEquals(2, host.relays.size)
        assertTrue(host.relays.all { it.second.password == PASSWORD })
        assertTrue(host.relays.none { (_, engine) -> engine.requests.any { PASSWORD in it } })
    }

    @Test
    fun theRootlessDockerOfTheLoginComesBeforeSudo() = runTest {
        val host = DockerHost(
            listOf(
                "docker system dial-stdio </dev/null" to ExecResult("", "failed to connect to the docker API at unix:///var/run/docker.sock: dial unix /var/run/docker.sock: connect: no such file or directory\n", 1),
                "DOCKER_HOST=" to ok(),
            ),
        ) { DockerEngine(listOf("GET /version " to engineAnswer(VERSION_29), "GET /v1.56/volumes " to engineAnswer(VOLUMES_29))) }
        docker(host, PASSWORD).get("/volumes")
        assertEquals(listOf($$"""DOCKER_HOST=unix://${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/docker.sock docker system dial-stdio"""), host.relays.map { it.first })
        assertTrue(host.ran.none { it.startsWith("sudo") || it == "id -u" })
    }

    @Test
    fun anEngineOutOfReachSaysWhyInTheWordsOfTheHostAndSudoIsNotTried() = runTest {
        val down = "failed to connect to the docker API at unix:///var/run/docker.sock: dial unix /var/run/docker.sock: connect: no such file or directory"
        val host = DockerHost(listOf("docker system dial-stdio </dev/null" to ExecResult("", "$down\n", 1), "DOCKER_HOST=" to ExecResult("", "no socket\n", 1))) { error("no relay") }
        assertEquals("the app cannot reach the container engine: $down", assertFailsWith<DeviceException> { docker(host, PASSWORD).get("/volumes") }.message)
        assertTrue(host.ran.none { it.startsWith("sudo") })
        val bare = DockerHost(emptyList()) { error("no relay") }
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { DockerApi(bare, Root(bare, null)) { emptySet() }.get("/volumes") }.category)
    }

    @Test
    fun aPodmanOfTheLoginWithoutItsSocketGetsAServiceThatEndsByItself() = runTest {
        val host = DockerHost(
            listOf(
                "[ " to ExecResult("", "", 1),
                "podman system dial-stdio </dev/null" to ExecResult("", "Error: failed to open connection to podman: dial unix /run/user/1001/podman/podman.sock: connect: no such file or directory\n", 125),
                "nohup podman system service --time=900 " to ok(),
            ),
        ) { DockerEngine(listOf("GET /version " to engineAnswer(VERSION_PODMAN_5), "GET /v1.44/volumes " to engineAnswer("""{"Volumes":[],"Warnings":[]}"""))) }
        DockerApi(host, Root(host, null)) { setOf("podman") }.get("/volumes")
        assertEquals(listOf("podman system dial-stdio"), host.relays.map { it.first })
        assertTrue(host.ran.any { it.startsWith("nohup podman system service --time=900 ") })
    }

    @Test
    fun theVersionIsTheEnginesUpToTheNewestKnownAndNeverBelowItsOldest() {
        assertEquals("1.56", negotiated("1.56", "1.40"))
        assertEquals("1.44", negotiated("1.44", "1.24"))
        assertEquals("1.41", negotiated("1.41", "1.12"))
        assertEquals("1.56", negotiated("1.61", "1.44"))
        assertEquals("1.58", negotiated("1.61", "1.58"))
        assertEquals("", negotiated("", ""))
    }

    @Test
    fun aKeptConnectionThatEndedIsReplacedForAReadAndAChangeAlwaysGoesOnItsOwn() = runTest {
        var serves = 2
        val host = DockerHost(listOf("docker system dial-stdio </dev/null" to ok())) {
            val answers = listOf("GET /version " to engineAnswer(VERSION_29), "GET /v1.56/volumes " to engineAnswer(VOLUMES_29), "POST /v1.56/containers/nh-sample-web/stop" to ENGINE_NO_CONTENT)
            DockerEngine(answers, serves = serves).also { serves = Int.MAX_VALUE }
        }
        val api = docker(host)
        api.get("/volumes")
        api.get("/volumes")
        api.send("POST", "/containers/nh-sample-web/stop?t=5")
        assertEquals(
            listOf(
                listOf("GET /version HTTP/1.1", "GET /v1.56/volumes HTTP/1.1", "GET /v1.56/volumes HTTP/1.1"),
                listOf("GET /v1.56/volumes HTTP/1.1"),
                listOf("POST /v1.56/containers/nh-sample-web/stop?t=5 HTTP/1.1"),
            ),
            host.relays.map { it.second.requests.map { request -> request.line() } },
        )
        assertTrue("Connection: close" in host.relays[2].second.requests.single())
    }

    @Test
    fun aRefusalOfTheEngineReachesTheUserAsItCameAndTheRegistryCredentialsStayOut() = runTest {
        val auth = Base64.UrlSafe.encode("""{"username":"nh","password":"$SECRET","serveraddress":"registry.example.com"}""".encodeToByteArray())
        val host = reachable(
            "GET /v1.56/containers/nope/json " to engineAnswer("""{"message":"No such container: nope"}""", "404 Not Found"),
            "POST /v1.56/containers/create" to engineAnswer("""{"message":"Conflict. The container name \"/nh-sample-web\" is already in use by container \"87deced11966\". You have to remove (or rename) that container to be able to reuse that name."}""", "409 Conflict"),
            "POST /v1.56/images/create" to engineAnswer("""{"message":"Head \"https://registry.example.com/v2/nh/app/manifests/latest\": unauthorized: authentication required"}""", "500 Internal Server Error"),
        )
        val api = docker(host)
        val missing = assertFailsWith<DeviceException> { api.get("/containers/nope/json") }
        assertEquals(ErrorCategory.MissingItem to "No such container: nope", missing.category to missing.message)
        val taken = assertFailsWith<DeviceException> { api.send("POST", "/containers/create?name=nh-sample-web", buildJsonObject { put("Image", "alpine:latest") }) }
        assertEquals(ErrorCategory.ArgumentValue, taken.category)
        assertTrue(taken.message.orEmpty().startsWith("Conflict. The container name \"/nh-sample-web\" is already in use"))
        val create = host.relays.flatMap { it.second.requests }.single { it.startsWith("POST /v1.56/containers/create?name=nh-sample-web ") }
        assertTrue("Content-Type: application/json\r\n" in create && create.endsWith("""{"Image":"alpine:latest"}"""))
        val denied = assertFailsWith<DeviceException> {
            api.lines("POST", "/images/create?fromImage=registry.example.com%2Fnh%2Fapp&tag=latest", headers = mapOf("X-Registry-Auth" to auth)).toList()
        }
        assertEquals("Head \"https://registry.example.com/v2/nh/app/manifests/latest\": unauthorized: authentication required", denied.message)
        assertFalse(auth in denied.message.orEmpty() || SECRET in denied.message.orEmpty())
        assertTrue(host.relays.any { (_, engine) -> engine.requests.any { "X-Registry-Auth: $auth\r\n" in it } })
    }

    @Test
    fun aLogWithoutATerminalComesByStreamAndOneWithATerminalAsItCame() = runTest {
        assertEquals(
            listOf(
                "stderr" to "2026-11-17T17:21:24.028829191Z err",
                "stdout" to "2026-11-17T17:21:24.028947089Z out 1794936084",
                "stdout" to "2026-11-17T17:21:25.030123924Z out 1794936085",
                "stderr" to "2026-11-17T17:21:25.030164562Z err",
            ),
            logLines(Trickle(Base64.decode(MULTIPLEXED_29))).toList(),
        )
        assertEquals(listOf("tty 1794936083", "tty 1794936084", "tty 1794936085").map { "stdout" to it }, logLines(Trickle(Base64.decode(TTY_29))).toList())
        assertEquals(listOf("out 1794936680", "out 1794936681", "out 1794936682").map { "stdout" to it }, logLines(Trickle(Base64.decode(LOG_PODMAN_5))).toList())
    }

    @Test
    fun aFollowedLogComesChunkedOnAConnectionOfItsOwn() = runTest {
        // the record stops after three seconds; the engine ends the answer so when the container stops
        val host = reachable("GET /v1.56/containers/nh-sample-web/logs?" to Base64.decode(FOLLOW_29) + "0\r\n\r\n".encodeToByteArray())
        val lines = docker(host).log("/containers/nh-sample-web/logs?follow=1&stdout=1&stderr=1&tail=0").toList()
        assertEquals(listOf("err", "out 1794936130", "err", "out 1794936131", "err", "out 1794936132", "err"), lines.map { it.second })
        assertEquals(listOf("stderr", "stdout"), lines.take(2).map { it.first })
        assertEquals(2, host.relays.size)
        assertTrue("Connection: close" in host.relays[1].second.requests.single())
    }

    @Test
    fun aLineAndACharacterSplitBetweenFramesComeWholeAndAnErrorFrameStopsTheLog() = runTest {
        val text = "привет мир\n".encodeToByteArray()
        val pieces = frame(1, text.copyOfRange(0, 3)) + frame(2, "e\n".encodeToByteArray()) + frame(1, text.copyOfRange(3, text.size)) + frame(1, "без конца".encodeToByteArray())
        assertEquals(listOf("stderr" to "e", "stdout" to "привет мир", "stdout" to "без конца"), logLines(Trickle(pieces)).toList())
        val stopped = assertFailsWith<DeviceException> { logLines(Trickle(frame(1, "a\n".encodeToByteArray()) + frame(3, "container 87deced11966 is not running".encodeToByteArray()))).toList() }
        assertEquals("container 87deced11966 is not running", stopped.message)
        assertFailsWith<DeviceException> { logLines(Trickle(frame(1, "a\n".encodeToByteArray()) + byteArrayOf(9, 0, 0, 0, 0, 0, 0, 1, 65))).toList() }
        assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { logLines(Trickle(frame(1, "abc".encodeToByteArray()).copyOf(9))).toList() }.category)
    }

    @Test
    fun statsCountTheCpuSinceTheSampleBeforeAndTheMemoryWithoutTheCacheTheKernelMayDrop() {
        assertEquals(
            mapOf(
                "time" to "2026-11-17T17:21:17.400096833Z", "cpu" to "0.16", "memory" to "737280", "memory-limit" to "1073741824",
                "rx-byte" to "4516", "tx-byte" to "126", "read-byte" to "", "write-byte" to "", "pids" to "2",
            ),
            dockerStats(STATS_29),
        )
        assertEquals("", dockerStats(STATS_STREAM_FIRST_29)["cpu"])
        val podman = dockerStats(STATS_PODMAN_5)
        assertEquals(listOf("", "782336", "1810", "628", "0", "0", "2"), listOf("cpu", "memory", "rx-byte", "tx-byte", "read-byte", "write-byte", "pids").map { podman[it] })
        // podman stats --no-stream gave 0.19 % at the time; the system CPU time of Podman moved 23 ms in five seconds
        assertEquals(listOf("0.00", "0.19"), STATS_STREAM_PODMAN_5.map { dockerStats(it)["cpu"] })
    }

    @Test
    fun eventsSayWhatHappenedToWhat() = runTest {
        val host = reachable("GET /v1.56/events" to engineStream(EVENTS_29, "\n", "application/jsonl"))
        val events = docker(host).lines("GET", "/events?since=1794936139&until=1794936141").toList().map(::dockerEvent)
        assertEquals(listOf("kill", "kill", "disconnect", "stop", "die", "connect", "start", "restart"), events.map { it["action"] })
        assertEquals(listOf("container", "nh-sample-tty", "alpine:latest", "signal=15", "1794936139"), listOf("type", "name", "image", "attributes", "time").map { events[0][it] })
        assertEquals("execDuration=42, exitCode=137", events[4]["attributes"])
        assertEquals(
            listOf("network", "bridge", "container=5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a, type=bridge"),
            listOf("type", "name", "attributes").map { events[2][it] },
        )
    }

    @Test
    fun aPullShowsEachLayerAndStopsAtAFailureInsideAnAnswerThatBeganWell() = runTest {
        val failure = """{"errorDetail":{"message":"failed to register layer: no space left on device"},"error":"failed to register layer: no space left on device"}"""
        val host = reachable(
            "POST /v1.56/images/create?fromImage=busybox" to engineStream(PULL_29, "\r\n"),
            "POST /v1.56/images/create?fromImage=nh-full" to engineStream(PULL_29.take(2) + failure, "\r\n"),
        )
        val api = docker(host)
        val steps = api.lines("POST", "/images/create?fromImage=busybox&tag=latest").toList().map(::dockerProgress)
        assertEquals(listOf("latest", "Pulling from library/busybox"), listOf("id", "status").map { steps[0][it] })
        assertEquals(listOf("37bb94b0940b", "Downloading", "1048576", "2226163", ""), listOf("id", "status", "current", "total", "units").map { steps[3][it] })
        assertEquals(listOf("Extracting", "1", "", "s"), listOf("status", "current", "total", "units").map { steps[6][it] })
        assertEquals("Status: Downloaded newer image for busybox:latest", steps.last()["status"])
        val failed = assertFailsWith<DeviceException> { api.lines("POST", "/images/create?fromImage=nh-full&tag=latest").toList().map(::dockerProgress) }
        assertEquals("failed to register layer: no space left on device", failed.message)
    }

    @Test
    fun execTakesTheConnectionOverAndItsOutputComesInFramesOrAsItCame() = runTest {
        val podman = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.docker.raw-stream\r\n\r\n/ # ".encodeToByteArray()
        val host = reachable(
            "POST /v1.56/exec/e70bbfce" to Base64.decode(EXEC_29), "POST /v1.56/exec/0c2069de" to podman, "POST /v1.56/exec/e6db1eff" to Base64.decode(EXEC_PODMAN_5),
        )
        val api = docker(host)
        val framed = api.hijack(
            "/exec/e70bbfce178570d270c22b71e55945654ff5b5c2a91ffd82584ba906f64b2c11/start",
            buildJsonObject {
                put("Detach", false)
                put("Tty", false)
            },
        )
        framed.write("ls\n".encodeToByteArray())
        assertEquals(listOf("stdout" to "exec-out", "stderr" to "exec-err"), logLines(framed).toList())
        val engine = host.relays.last().second
        assertTrue("Upgrade: tcp\r\n" in engine.requests.single())
        assertEquals("ls\n", engine.raw.toString())
        val terminal = api.hijack(
            "/exec/0c2069deda3c/start",
            buildJsonObject {
                put("Detach", false)
                put("Tty", true)
            },
        )
        assertEquals(listOf("stdout" to "/ # "), logLines(terminal).toList())
        // Podman answers 200 and calls the stream raw, yet without a terminal it sends frames as Docker does
        val podmanFramed = api.hijack(
            "/exec/e6db1effde09e758dae282b0281bf04c9a8846082e76758ba9a36c2a5d990d9b/start",
            buildJsonObject {
                put("Detach", false)
                put("Tty", false)
            },
        )
        assertEquals(listOf("stdout" to "exec-out", "stderr" to "exec-err"), logLines(podmanFramed).toList())
    }

    private companion object {
        const val PASSWORD = "nh-sudo-secret"
        const val SECRET = "nh-registry-secret"

        // recorded on Docker 29.9.3 (API 1.56) on Arch and on Podman 5.8.9 (compatible API 1.44) on Fedora 44
        const val VERSION_29 = """{"Platform":{"Name":""},"Version":"29.9.3","ApiVersion":"1.56","MinAPIVersion":"1.40","Os":"linux","Arch":"amd64","Components":[{"Name":"Engine",""" +
            """"Version":"29.9.3","Details":{"ApiVersion":"1.56","Arch":"amd64","BuildTime":"2026-11-04T13:44:59.000000000+00:00","Experimental":"false","GitCommit":"70efc0e31d",""" +
            """"GoVersion":"go1.27.3-X:nodwarf5","KernelVersion":"6.8.4-2-pve","MinAPIVersion":"1.40","Module":"github.com/moby/moby/v2","ModuleVersion":"v2.0.0+unknown",""" +
            """"Os":"linux"}},{"Name":"containerd","Version":"v2.4.2","Details":{"GitCommit":"b7a25261e029d68978bbf290df0a62fc819b3c51.m"}},{"Name":"runc",""" +
            """"Version":"1.5.3","Details":{"GitCommit":""}},{"Name":"docker-init","Version":"0.19.0","Details":{"GitCommit":"de40ad0"}}],"GitCommit":"70efc0e31d",""" +
            """"GoVersion":"go1.27.3-X:nodwarf5","KernelVersion":"6.8.4-2-pve","BuildTime":"2026-11-04T13:44:59.000000000+00:00"}"""
        const val VOLUMES_29 = """{"Volumes":[{"CreatedAt":"2026-11-17T17:20:59Z","Driver":"local","Labels":null,"Mountpoint":"/var/lib/docker/volumes/nh-sample-vol/_data","Name":"nh-sample-vol",""" +
            """"Options":null,"Scope":"local"}],"Warnings":null}"""
        const val TOP_29 = """{"Processes":[["root","727","702","0","17:21","?","00:00:00","sh -c while true; do echo out $(date +%s); echo err >&2; sleep 1; done"],["root",""" +
            """"901","727","0","17:21","?","00:00:00","sleep 1"]],"Titles":["UID","PID","PPID","C","STIME","TTY","TIME","CMD"]}"""
        const val STATS_29 = """{"id":"87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d","name":"/nh-sample-web","os_type":"linux","read":"2026-11-17T17:21:17.400096833Z",""" +
            """"cpu_stats":{"cpu_usage":{"total_usage":76493000,"usage_in_kernelmode":36756000,"usage_in_usermode":39736000},"system_cpu_usage":34460010000000,""" +
            """"online_cpus":2,"throttling_data":{"periods":0,"throttled_periods":0,"throttled_time":0}},"memory_stats":{"usage":741376,"stats":{"active_anon":81920,""" +
            """"active_file":0,"anon":114688,"anon_thp":0,"file":4096,"file_dirty":0,"file_mapped":0,"file_writeback":0,"inactive_anon":0,"inactive_file":4096,""" +
            """"kernel_stack":32768,"pgactivate":0,"pgdeactivate":0,"pgfault":2791,"pglazyfree":0,"pglazyfreed":0,"pgmajfault":0,"pgrefill":0,"pgscan":0,"pgsteal":0,""" +
            """"shmem":0,"slab":414408,"slab_reclaimable":222672,"slab_unreclaimable":191736,"sock":0,"thp_collapse_alloc":0,"thp_fault_alloc":0,"unevictable":0,""" +
            """"workingset_activate":0,"workingset_nodereclaim":0,"workingset_refault":0},"limit":1073741824},"networks":{"eth0":{"rx_bytes":4516,"rx_packets":42,""" +
            """"rx_errors":0,"rx_dropped":0,"tx_bytes":126,"tx_packets":3,"tx_errors":0,"tx_dropped":0}},"pids_stats":{"current":2,"limit":231863},"blkio_stats":{"io_service_bytes_recursive":null,""" +
            """"io_serviced_recursive":null,"io_queue_recursive":null,"io_service_time_recursive":null,"io_wait_time_recursive":null,"io_merged_recursive":null,""" +
            """"io_time_recursive":null,"sectors_recursive":null},"num_procs":0,"storage_stats":{},"preread":"2026-11-17T17:21:16.396256428Z","precpu_stats":{"cpu_usage":{"total_usage":74908000,""" +
            """"usage_in_kernelmode":35429000,"usage_in_usermode":39478000},"system_cpu_usage":34458030000000,"online_cpus":2,"throttling_data":{"periods":0,""" +
            """"throttled_periods":0,"throttled_time":0}}}"""
        const val STATS_STREAM_FIRST_29 = """{"id":"87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d","name":"/nh-sample-web","os_type":"linux","read":"2026-11-17T17:21:31.419384742Z",""" +
            """"cpu_stats":{"cpu_usage":{"total_usage":97719000,"usage_in_kernelmode":54842000,"usage_in_usermode":42876000},"system_cpu_usage":34487830000000,""" +
            """"online_cpus":2,"throttling_data":{"periods":0,"throttled_periods":0,"throttled_time":0}},"memory_stats":{"usage":745472,"stats":{"active_anon":114688,""" +
            """"active_file":0,"anon":114688,"anon_thp":0,"file":4096,"file_dirty":0,"file_mapped":0,"file_writeback":0,"inactive_anon":0,"inactive_file":4096,""" +
            """"kernel_stack":32768,"pgactivate":0,"pgdeactivate":0,"pgfault":4509,"pglazyfree":0,"pglazyfreed":0,"pgmajfault":0,"pgrefill":0,"pgscan":0,"pgsteal":0,""" +
            """"shmem":0,"slab":414408,"slab_reclaimable":222672,"slab_unreclaimable":191736,"sock":0,"thp_collapse_alloc":0,"thp_fault_alloc":0,"unevictable":0,""" +
            """"workingset_activate":0,"workingset_nodereclaim":0,"workingset_refault":0},"limit":1073741824},"networks":{"eth0":{"rx_bytes":4586,"rx_packets":43,""" +
            """"rx_errors":0,"rx_dropped":0,"tx_bytes":126,"tx_packets":3,"tx_errors":0,"tx_dropped":0}},"pids_stats":{"current":2,"limit":231863},"blkio_stats":{"io_service_bytes_recursive":null,""" +
            """"io_serviced_recursive":null,"io_queue_recursive":null,"io_service_time_recursive":null,"io_wait_time_recursive":null,"io_merged_recursive":null,""" +
            """"io_time_recursive":null,"sectors_recursive":null},"num_procs":0,"storage_stats":{},"preread":"0001-01-01T00:00:00Z","precpu_stats":{"cpu_usage":{"total_usage":0,""" +
            """"usage_in_kernelmode":0,"usage_in_usermode":0},"throttling_data":{"periods":0,"throttled_periods":0,"throttled_time":0}}}"""
        val EVENTS_29 = listOf(
            """{"Type":"container","Action":"kill","Actor":{"ID":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a","Attributes":{"image":"alpine:latest",""" +
                """"name":"nh-sample-tty","signal":"15"}},"scope":"local","time":1794936139,"timeNano":1794936139230222874}""",
            """{"Type":"container","Action":"kill","Actor":{"ID":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a","Attributes":{"image":"alpine:latest",""" +
                """"name":"nh-sample-tty","signal":"9"}},"scope":"local","time":1794936140,"timeNano":1794936140251185885}""",
            """{"Type":"network","Action":"disconnect","Actor":{"ID":"64d3a304e07732e9934cf31e994e28051b8311bd827019c939a65f58cf9f6196","Attributes":{"container":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a",""" +
                """"name":"bridge","type":"bridge"}},"scope":"local","time":1794936140,"timeNano":1794936140435724233}""",
            """{"Type":"container","Action":"stop","Actor":{"ID":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a","Attributes":{"image":"alpine:latest",""" +
                """"name":"nh-sample-tty"}},"scope":"local","time":1794936140,"timeNano":1794936140436018165}""",
            """{"Type":"container","Action":"die","Actor":{"ID":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a","Attributes":{"execDuration":"42",""" +
                """"exitCode":"137","image":"alpine:latest","name":"nh-sample-tty"}},"scope":"local","time":1794936140,"timeNano":1794936140477929097}""",
            """{"Type":"network","Action":"connect","Actor":{"ID":"64d3a304e07732e9934cf31e994e28051b8311bd827019c939a65f58cf9f6196","Attributes":{"container":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a",""" +
                """"name":"bridge","type":"bridge"}},"scope":"local","time":1794936140,"timeNano":1794936140734350141}""",
            """{"Type":"container","Action":"start","Actor":{"ID":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a","Attributes":{"image":"alpine:latest",""" +
                """"name":"nh-sample-tty"}},"scope":"local","time":1794936140,"timeNano":1794936140760100779}""",
            """{"Type":"container","Action":"restart","Actor":{"ID":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a","Attributes":{"image":"alpine:latest",""" +
                """"name":"nh-sample-tty"}},"scope":"local","time":1794936140,"timeNano":1794936140760120475}""",
        )
        val PULL_29 = listOf(
            """{"status":"Pulling from library/busybox","id":"latest"}""",
            """{"status":"Pulling fs layer","progressDetail":{},"id":"37bb94b0940b"}""",
            """{"status":"Download complete","progressDetail":{"hidecounts":true},"id":"46500c34ad0c"}""",
            """{"status":"Downloading","progressDetail":{"current":1048576,"total":2226163},"id":"37bb94b0940b"}""",
            """{"status":"Downloading","progressDetail":{"current":2226163,"total":2226163},"id":"37bb94b0940b"}""",
            """{"status":"Download complete","progressDetail":{"hidecounts":true},"id":"37bb94b0940b"}""",
            """{"status":"Extracting","progressDetail":{"current":1,"units":"s"},"id":"37bb94b0940b"}""",
            """{"status":"Pull complete","progressDetail":{"hidecounts":true},"id":"37bb94b0940b"}""",
            """{"status":"Digest: sha256:a46257e06c82ad85242d0d38077d0a52d9896389ca60a1a59d3463db586e5fa4"}""",
            """{"status":"Status: Downloaded newer image for busybox:latest"}""",
        )
        const val MULTIPLEXED_29 = "AgAAAAAAACMyMDI2LTExLTE3VDE3OjIxOjI0LjAyODgyOTE5MVogZXJyCgEAAAAAAAAuMjAyNi0xMS0xN1QxNzoyMToyNC4wMjg5NDcwODlaIG91dCAxNzk0" +
            "OTM2MDg0CgEAAAAAAAAuMjAyNi0xMS0xN1QxNzoyMToyNS4wMzAxMjM5MjRaIG91dCAxNzk0OTM2MDg1CgIAAAAAAAAjMjAyNi0xMS0xN1QxNzoyMToyNS4w" +
            "MzAxNjQ1NjJaIGVycgo="
        const val TTY_29 = "dHR5IDE3OTQ5MzYwODMNCnR0eSAxNzk0OTM2MDg0DQp0dHkgMTc5NDkzNjA4NQ0K"
        const val FOLLOW_29 = "SFRUUC8xLjEgMjAwIE9LDQpBcGktVmVyc2lvbjogMS41Ng0KQ29udGVudC1UeXBlOiBhcHBsaWNhdGlvbi92bmQuZG9ja2VyLm11bHRpcGxleGVkLXN0cmVh" +
            "bQ0KRG9ja2VyLUV4cGVyaW1lbnRhbDogZmFsc2UNCk9zdHlwZTogbGludXgNClNlcnZlcjogRG9ja2VyLzI5LjkuMyAobGludXgpDQpEYXRlOiBUdWUsIDE3" +
            "IE5vdiAyMDI2IDE3OjIyOjEwIEdNVA0KVHJhbnNmZXItRW5jb2Rpbmc6IGNodW5rZWQNCg0KYw0KAgAAAAAAAARlcnIKDQoxNw0KAQAAAAAAAA9vdXQgMTc5" +
            "NDkzNjEzMAoNCmMNCgIAAAAAAAAEZXJyCg0KMTcNCgEAAAAAAAAPb3V0IDE3OTQ5MzYxMzEKDQpjDQoCAAAAAAAABGVycgoNCjE3DQoBAAAAAAAAD291dCAx" +
            "Nzk0OTM2MTMyCg0KYw0KAgAAAAAAAARlcnIKDQo="
        const val EXEC_29 = "SFRUUC8xLjEgMTAxIFVQR1JBREVEDQpDb250ZW50LVR5cGU6IGFwcGxpY2F0aW9uL3ZuZC5kb2NrZXIubXVsdGlwbGV4ZWQtc3RyZWFtDQpDb25uZWN0aW9u" +
            "OiBVcGdyYWRlDQpVcGdyYWRlOiB0Y3ANCkFwaS1WZXJzaW9uOiAxLjU2DQpEb2NrZXItRXhwZXJpbWVudGFsOiBmYWxzZQ0KT3N0eXBlOiBsaW51eA0KU2Vy" +
            "dmVyOiBEb2NrZXIvMjkuOS4zIChsaW51eCkNCg0KAQAAAAAAAAlleGVjLW91dAoCAAAAAAAACWV4ZWMtZXJyCg=="
        const val EXEC_PODMAN_5 = "SFRUUC8xLjEgMjAwIE9LDQpDb250ZW50LVR5cGU6IGFwcGxpY2F0aW9uL3ZuZC5kb2NrZXIucmF3LXN0cmVhbQ0KDQoBAAAAAAAACWV4ZWMtb3V0CgIAAAAAAAAJZXhlYy1lcnIK"
        const val VERSION_PODMAN_5 = """{"Platform":{"Name":"linux/amd64/fedora-44"},"Components":[{"Name":"Podman Engine","Version":"5.8.9","Details":{"APIVersion":"5.8.9","Arch":"amd64",""" +
            """"BuildTime":"2026-10-12T00:00:00Z","Experimental":"false","GitCommit":"40030f4073bd4e1bf4f2fd9df2f6952b4d9d350a","GoVersion":"go1.26.9-X:nodwarf5",""" +
            """"KernelVersion":"6.8.4-2-pve","MinAPIVersion":"4.0.0","Os":"linux"}},{"Name":"Conmon","Version":"conmon version 2.2.1, commit: ","Details":{"Package":"conmon-2.2.1-2.fc44.x86_64"}},""" +
            """{"Name":"OCI Runtime (crun)","Version":"crun version 1.30\ncommit: a6e70f254e509ceac317027c858082cc6da34cf2\nrundir: /run/crun\nspec: 1.0.0\n+SYSTEMD +SELINUX +APPARMOR +CAP +SECCOMP +EBPF +CRIU +LIBKRUN +WASM:wasmedge +JSON_C",""" +
            """"Details":{"Package":"crun-1.30-1.fc44.x86_64"}}],"Version":"5.8.9","ApiVersion":"1.44","MinAPIVersion":"1.24","GitCommit":"40030f4073bd4e1bf4f2fd9df2f6952b4d9d350a",""" +
            """"GoVersion":"go1.26.9-X:nodwarf5","Os":"linux","Arch":"amd64","KernelVersion":"6.8.4-2-pve","BuildTime":"2026-10-12T00:00:00Z"}"""
        const val STATS_PODMAN_5 = """{"read":"2026-11-17T17:31:16.567398515Z","preread":"0001-01-01T00:00:00Z","pids_stats":{"current":2},"blkio_stats":{"io_service_bytes_recursive":[],""" +
            """"io_serviced_recursive":null,"io_queue_recursive":null,"io_service_time_recursive":null,"io_wait_time_recursive":null,"io_merged_recursive":null,""" +
            """"io_time_recursive":null,"sectors_recursive":null},"num_procs":0,"storage_stats":{},"cpu_stats":{"cpu_usage":{"total_usage":26829000,"usage_in_kernelmode":22701,""" +
            """"usage_in_usermode":26806299},"system_cpu_usage":13475852000,"online_cpus":2,"cpu":0,"throttling_data":{"periods":0,"throttled_periods":0,"throttled_time":0}},""" +
            """"precpu_stats":{"cpu_usage":{"total_usage":0,"usage_in_kernelmode":0,"usage_in_usermode":0},"cpu":0,"throttling_data":{"periods":0,"throttled_periods":0,""" +
            """"throttled_time":0}},"memory_stats":{"usage":782336,"limit":1073741824},"name":"nh-sample-web","id":"909aa0962d0d3ef68656655b582ec3dbef14ad7986f98ad6262c66ca44dfcc72",""" +
            """"networks":{"eth0":{"rx_bytes":1810,"rx_packets":21,"rx_errors":0,"rx_dropped":0,"tx_bytes":628,"tx_packets":8,"tx_errors":0,"tx_dropped":0}}}"""
        val STATS_STREAM_PODMAN_5 = listOf(
            """{"read":"2026-11-17T17:56:30.416747153Z","preread":"2026-11-17T17:56:30.411049203Z","pids_stats":{"current":2},"blkio_stats":{"io_service_bytes_recursive":[],""" +
                """"io_serviced_recursive":null,"io_queue_recursive":null,"io_service_time_recursive":null,"io_wait_time_recursive":null,"io_merged_recursive":null,""" +
                """"io_time_recursive":null,"sectors_recursive":null},"num_procs":0,"storage_stats":{},"cpu_stats":{"cpu_usage":{"total_usage":2946904000,"usage_in_kernelmode":2339137,""" +
                """"usage_in_usermode":2944564863},"system_cpu_usage":22893639000,"online_cpus":2,"cpu":0,"throttling_data":{"periods":0,"throttled_periods":0,""" +
                """"throttled_time":0}},"precpu_stats":{"cpu_usage":{"total_usage":2946904000,"usage_in_kernelmode":2339137,"usage_in_usermode":2944564863},"system_cpu_usage":22888728000,""" +
                """"cpu":0.19329584501472333,"throttling_data":{"periods":0,"throttled_periods":0,"throttled_time":0}},"memory_stats":{"usage":770048,"limit":1073741824},""" +
                """"name":"nh-sample-web","id":"909aa0962d0d3ef68656655b582ec3dbef14ad7986f98ad6262c66ca44dfcc72","networks":{"eth0":{"rx_bytes":2790,"rx_packets":35,""" +
                """"rx_errors":0,"rx_dropped":0,"tx_bytes":1118,"tx_packets":15,"tx_errors":0,"tx_dropped":0}}}""",
            """{"read":"2026-11-17T17:56:35.421972051Z","preread":"2026-11-17T17:56:30.416747153Z","pids_stats":{"current":2},"blkio_stats":{"io_service_bytes_recursive":[],""" +
                """"io_serviced_recursive":null,"io_queue_recursive":null,"io_service_time_recursive":null,"io_wait_time_recursive":null,"io_merged_recursive":null,""" +
                """"io_time_recursive":null,"sectors_recursive":null},"num_procs":0,"storage_stats":{},"cpu_stats":{"cpu_usage":{"total_usage":2956323000,"usage_in_kernelmode":2347488,""" +
                """"usage_in_usermode":2953975512},"system_cpu_usage":22916877000,"online_cpus":2,"cpu":0.18811929318446666,"throttling_data":{"periods":0,"throttled_periods":0,""" +
                """"throttled_time":0}},"precpu_stats":{"cpu_usage":{"total_usage":2946904000,"usage_in_kernelmode":2339137,"usage_in_usermode":2944564863},"system_cpu_usage":22893639000,""" +
                """"online_cpus":2,"cpu":0,"throttling_data":{"periods":0,"throttled_periods":0,"throttled_time":0}},"memory_stats":{"usage":782336,"limit":1073741824},""" +
                """"name":"nh-sample-web","id":"909aa0962d0d3ef68656655b582ec3dbef14ad7986f98ad6262c66ca44dfcc72","networks":{"eth0":{"rx_bytes":2790,"rx_packets":35,""" +
                """"rx_errors":0,"rx_dropped":0,"tx_bytes":1118,"tx_packets":15,"tx_errors":0,"tx_dropped":0}}}""",
        )
        const val LOG_PODMAN_5 ="AQAAAAAAAA9vdXQgMTc5NDkzNjY4MAoBAAAAAAAAD291dCAxNzk0OTM2NjgxCgEAAAAAAAAPb3V0IDE3OTQ5MzY2ODIK"
    }
}
