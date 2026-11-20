package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinuxDockerStacksTest {

    private fun ok(output: String = "", error: String = "") = ExecResult(output, error, 0)

    private fun failed(error: String) = ExecResult("", error, 1)

    private fun host(vararg answers: Pair<String, ByteArray>, commands: List<Pair<String, ExecResult>> = emptyList()) =
        DockerHost(listOf("docker system dial-stdio </dev/null" to ok(), "date +%z" to ok("+0300\n")) + commands) { DockerEngine(listOf("GET /version " to engineAnswer(VERSION)) + answers) }

    private fun stacks(host: DockerHost) = DockerStacks(host, DockerApi(host, Root(host, null)) { setOf("docker") }).table { true }

    // the containers of the lab as the two services of one stack, one of them stopped
    private val composed = LIST_29
        .replace(""""Labels":{},"State":"running"""", """"Labels":{${labels("cache")}},"State":"exited"""")
        .replace(""""Labels":{"nh.sample":"1"}""", """"Labels":{"nh.sample":"1",${labels("web")}}""")

    private fun labels(service: String, project: String = "nh-stack", dir: String = DIR, files: String = "$DIR/compose.yaml") =
        """"com.docker.compose.project":"$project","com.docker.compose.service":"$service",""" +
            """"com.docker.compose.project.working_dir":"$dir","com.docker.compose.project.config_files":"$files""""

    // a log as the engine writes it without a terminal: one frame on the stream given
    private fun logged(stream: Int, text: String): ByteArray {
        val payload = text.encodeToByteArray()
        val frame = byteArrayOf(stream.toByte(), 0, 0, 0, 0, 0, (payload.size shr 8).toByte(), payload.size.toByte()) + payload
        return "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.docker.multiplexed-stream\r\nContent-Length: ${frame.size}\r\n\r\n".encodeToByteArray() + frame
    }

    @Test
    fun aStackIsAProjectOfTheLabelsOrAFolderOfTheAppWithNoContainerNow() = runTest {
        val kept = "$DIR/compose.yaml\n/home/nh/stacks/nh-down/compose.yaml\n/home/nh/stacks/nh-down/docker-compose.yml\n"
        val rows = stacks(host("GET /v1.56/containers/json?all=1 " to engineAnswer(composed), commands = listOf("ls -1d " to ok(kept)))).read()
        assertEquals(listOf("nh-stack", "nh-down"), rows.map { it.id })
        assertEquals(listOf("exited(1), running(1)", "cache, web", "2", DIR), listOf("status", "services", "containers", "dir").map { rows[0][it] })
        assertEquals(listOf("down", "0", "/home/nh/stacks/nh-down", "/home/nh/stacks/nh-down/compose.yaml"), listOf("status", "containers", "dir", "files").map { rows[1][it] })
    }

    @Test
    fun composeIsThePluginOfTheCliElseAProgramOfItsOwnAndRunsInTheFolderOfTheStack() = runTest {
        val progress = " Container nh-stack-cache-1  Started\n Container nh-stack-web-1  Started\n"
        val plugin = host(
            "GET /v1.56/containers/json?all=1 " to engineAnswer(composed),
            commands = listOf("ls -1d " to ok(), "docker compose version" to ok("Docker Compose version v5.7.1\n"), "cd " to ok("", progress)),
        )
        val said = assertNotNull(stacks(plugin).run)("up", mapOf("numbers" to "nh-stack"))
        assertEquals(listOf("Container nh-stack-cache-1  Started", "Container nh-stack-web-1  Started"), said.map { it["line"] })
        assertEquals("cd '$DIR' && docker compose -p 'nh-stack' -f '$DIR/compose.yaml' up -d", plugin.ran.last())

        val standalone = host(
            "GET /v1.56/containers/json?all=1 " to engineAnswer(composed),
            commands = listOf("ls -1d " to ok(), "docker-compose version" to ok("docker-compose version 1.29.2\n"), "cd " to failed("no such service: web")),
        )
        val refused = assertFailsWith<DeviceException> { assertNotNull(stacks(standalone).run)("down-volumes", mapOf("numbers" to "nh-stack")) }
        assertEquals("no such service: web", refused.message)
        assertEquals("cd '$DIR' && docker-compose -p 'nh-stack' -f '$DIR/compose.yaml' down -v", standalone.ran.last())

        val none = host("GET /v1.56/containers/json?all=1 " to engineAnswer(composed), commands = listOf("ls -1d " to ok()))
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { assertNotNull(stacks(none).run)("restart", mapOf("numbers" to "nh-stack")) }.category)
    }

    @Test
    fun aNewStackHasAFolderOfItsOwnThatComposeReadsBeforeItStartsAndLeavesNothingWhenItCannot() = runTest {
        val compose = "services:\n  web:\n    image: nginx:1.27\n  cache:\n    image: redis:7\n"
        val made = host(
            commands = listOf(
                "[ ! -e " to ok(), "mkdir -p " to ok(), "cat > " to ok(), "[ -z " to ok(), "docker compose version" to ok(),
                "cd " to ok("", " Container nh-new-web-1  Started\n"),
            ),
        )
        val said = assertNotNull(stacks(made).run)("create", mapOf("name" to "nh-new", "compose" to compose, "env" to "TAG=1\n"))
        assertEquals(listOf("Container nh-new-web-1  Started"), said.map { it["line"] })
        assertEquals(listOf("mkdir -p 'stacks/nh-new' && cat > 'stacks/nh-new/compose.yaml'" to compose, "cat > 'stacks/nh-new/.env'" to "TAG=1\n"), made.given)
        assertEquals(
            listOf("cd 'stacks/nh-new' && docker compose -p 'nh-new' config >/dev/null", "cd 'stacks/nh-new' && docker compose -p 'nh-new' up -d"),
            made.ran.filter { it.startsWith("cd ") },
        )

        val broken = host(
            commands = listOf(
                "[ ! -e " to ok(), "mkdir -p " to ok(), "[ -z " to ok(), "docker compose version" to ok(), "cd " to failed("services.web.image must be a string"),
                "rm -rf " to ok(),
            ),
        )
        val refused = assertFailsWith<DeviceException> { assertNotNull(stacks(broken).run)("create", mapOf("name" to "nh-new", "compose" to "services:\n  web:\n    image: [1]\n")) }
        assertEquals("services.web.image must be a string", refused.message)
        assertEquals("rm -rf 'stacks/nh-new'", broken.ran.last())

        val taken = host(commands = listOf("[ ! -e " to failed("")))
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { assertNotNull(stacks(taken).run)("create", mapOf("name" to "nh-new")) }.category)
        assertTrue(taken.ran.none { it.startsWith("mkdir") })
        val named = host()
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { assertNotNull(stacks(named).run)("create", mapOf("name" to "My Stack")) }.category)
        assertTrue(named.ran.none { it.startsWith("[ ") })
    }

    @Test
    fun aChangeOfTheFilesOfAStackIsReadByComposeBeforeItTakesThePlaceOfTheOldOnes() = runTest {
        val file = "$DIR/compose.yaml"
        val env = "$DIR/.env"
        fun files(check: ExecResult) = host(
            "GET /v1.56/containers/json?all=1 " to engineAnswer(composed),
            commands = listOf(
                "ls -1d " to ok(), "cat '$file'" to ok("services: {}\n"), "cat '$env' 2>/dev/null" to ok("TAG=1\n"), "cat > " to ok(), "docker compose version" to ok(),
                "cd " to check, "cat '$file.networkhelm' > " to ok(), "cat '$env.networkhelm' > " to ok(), "rm -f " to ok(),
            ),
        )
        val good = files(ok())
        val run = assertNotNull(stacks(good).run)
        assertEquals(listOf(file to "services: {}\n", env to "TAG=1\n"), run("config", mapOf("id" to "nh-stack")).map { it["file"] to it["text"] })
        val compose = "services:\n  web:\n    image: nginx:1.27\n"
        run("save-config", mapOf("id" to "nh-stack", "compose" to compose, "env" to "TAG=2\n"))
        assertEquals(listOf("cat > '$file.networkhelm'" to compose, "cat > '$env.networkhelm'" to "TAG=2\n"), good.given)
        assertEquals(
            listOf(
                "cd '$DIR' && docker compose -p 'nh-stack' -f '$file.networkhelm' --env-file '$env.networkhelm' config >/dev/null",
                "cat '$file.networkhelm' > '$file'",
                "cat '$env.networkhelm' > '$env'",
                "rm -f '$file.networkhelm' '$env.networkhelm'",
            ),
            good.ran.takeLast(4),
        )

        val wrong = files(failed("yaml: line 3: mapping values are not allowed in this context"))
        val refused = assertFailsWith<DeviceException> { assertNotNull(stacks(wrong).run)("save-config", mapOf("id" to "nh-stack", "compose" to "services: web: x\n")) }
        assertEquals("yaml: line 3: mapping values are not allowed in this context", refused.message)
        assertTrue(wrong.ran.none { it.startsWith("cat '$file.networkhelm' > ") })
        assertEquals("rm -f '$file.networkhelm'", wrong.ran.last())
    }

    @Test
    fun podmanComposeLeavesItsBannerOutAndItsFilesLieInTheFolderOfTheStack() = runTest {
        // podman-compose 1.6.0 on Fedora 44 names the file as it was given, without its folder: files=compose.yaml in the lab
        val dir = "/home/nhlab/stacks/nhstack"
        val podman = LIST_29
            .replace(""""Labels":{},"State":"running"""", """"Labels":{${labels("worker", "nhstack", dir, "compose.yaml")}},"State":"running"""")
            .replace(""""Labels":{"nh.sample":"1"}""", """"Labels":{"nh.sample":"1",${labels("web", "nhstack", dir, "compose.yaml")}}""")
        val banner = "\u001B[4m>>>> Executing external compose provider \"/usr/sbin/podman-compose\". Please see podman-compose(1) for how to disable this message. <<<<\n\u001B[0m"
        fun host(check: ExecResult) = DockerHost(
            listOf(
                "podman system dial-stdio </dev/null" to ok(), "date +%z" to ok("+0000\n"), "ls -1d " to ok(), "podman compose version" to ok("podman-compose version 1.6.0\n"),
                "cat > " to ok(), "cd " to check, "cat '$dir/compose.yaml.networkhelm' > " to ok(), "rm -f " to ok(),
            ),
        ) { DockerEngine(listOf("GET /version " to engineAnswer(VERSION), "GET /v1.56/containers/json?all=1 " to engineAnswer(podman))) }
        fun stacks(host: DockerHost) = DockerStacks(host, DockerApi(host, Root(host, null)) { setOf("podman") }).table { true }

        val good = host(ok("", "${banner}nhstack_web_1\n"))
        val table = stacks(good)
        assertEquals("$dir/compose.yaml", table.read().single()["files"])
        val said = assertNotNull(table.run)("up", mapOf("numbers" to "nhstack")).map { it["line"].orEmpty() }
        assertTrue(said.none { '\u001B' in it })
        assertEquals("nhstack_web_1", said.last())
        assertEquals("cd '$dir' && PODMAN_COMPOSE_WARNING_LOGS=false podman compose -p 'nhstack' -f '$dir/compose.yaml' up -d", good.ran.last())
        assertNotNull(table.run)("save-config", mapOf("id" to "nhstack", "compose" to "services: {}\n"))
        assertEquals("cat > '$dir/compose.yaml.networkhelm'", good.given.single().first)
        assertTrue(good.ran.any { it == "cat '$dir/compose.yaml.networkhelm' > '$dir/compose.yaml'" })

        val wrong = host(ExecResult("", "${banner}CRITICAL:podman_compose:missing files: ['compose.yaml']\n", 1))
        val refused = assertFailsWith<DeviceException> { assertNotNull(stacks(wrong).run)("save-config", mapOf("id" to "nhstack", "compose" to "services: {}\n")) }
        assertFalse('\u001B' in refused.message.orEmpty())
        assertTrue(refused.message.orEmpty().endsWith("CRITICAL:podman_compose:missing files: ['compose.yaml']"))
    }

    @Test
    fun theLogOfAStackIsTheLogsOfItsContainersEachLineWithItsService() = runTest {
        val tty = "5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a"
        val web = "87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d"
        val host = host(
            "GET /v1.56/containers/json?all=1&filters=" to engineAnswer(composed),
            "GET /v1.56/containers/$tty/logs?" to logged(1, "2026-11-17T17:22:10.000000000Z tty 1794936130\n"),
            "GET /v1.56/containers/$web/logs?" to logged(2, "2026-11-17T17:22:11.000000000Z err\n"),
        )
        val lines = assertNotNull(stacks(host).streams["log"])(mapOf("id" to "nh-stack", "tail" to "50")).toList()
        // the times come in the time of the host, as everywhere else in the windows of the engine
        assertEquals(
            setOf(listOf("cache", "stdout", "tty 1794936130", "2026-11-17 20:22:10.000"), listOf("web", "stderr", "err", "2026-11-17 20:22:11.000")),
            lines.map { listOf(it["service"], it["stream"], it["message"], it["time"]) }.toSet(),
        )
        assertTrue(host.relays.flatMap { it.second.requests }.any { it.startsWith("GET /v1.56/containers/$tty/logs?stdout=1&stderr=1&follow=1&timestamps=1&tail=50 ") })
        val services = assertNotNull(stacks(host).run)("services", mapOf("id" to "nh-stack"))
        assertEquals(listOf("cache" to "exited", "web" to "running"), services.map { it["service"] to it["state"] })
    }

    private companion object {
        const val VERSION = """{"ApiVersion":"1.56","MinAPIVersion":"1.40"}"""
        const val DIR = "/home/nh/stacks/nh-stack"
    }
}
