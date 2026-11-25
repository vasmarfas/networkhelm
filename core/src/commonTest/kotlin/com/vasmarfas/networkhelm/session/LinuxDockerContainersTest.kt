package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxDockerContainersTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private fun host(vararg answers: Pair<String, ByteArray>) =
        DockerHost(listOf("docker system dial-stdio </dev/null" to ok(), "date +%z" to ok("+0300\n"))) { DockerEngine(listOf("GET /version " to engineAnswer(VERSION)) + answers) }

    private fun table(host: DockerHost) = DockerContainers(DockerApi(host, Root(host, null)) { setOf("docker") }).table { true }

    private fun lines(host: DockerHost) = host.relays.flatMap { it.second.requests }.map { it.substringBefore("\r\n").removeSuffix(" HTTP/1.1") }

    private fun body(host: DockerHost, line: String) = Json.parseToJsonElement(host.relays.flatMap { it.second.requests }.last { it.startsWith("$line ") }.substringAfter("\r\n\r\n")).jsonObject

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun upgraded(vararg frames: ByteArray) =
        "HTTP/1.1 101 UPGRADED\r\nContent-Type: application/vnd.docker.multiplexed-stream\r\nConnection: Upgrade\r\nUpgrade: tcp\r\n\r\n".encodeToByteArray() + frames.fold(ByteArray(0)) { all, frame -> all + frame }

    private fun frame(stream: Int, text: String) = text.encodeToByteArray().let { payload ->
        byteArrayOf(stream.toByte(), 0, 0, 0, 0, 0, (payload.size shr 8).toByte(), payload.size.toByte()) + payload
    }

    // states and statuses as docker ps of Docker 29 writes them; an older engine says the health only in the status
    @Test
    fun theLettersOfARowTellItsStateAndHealth() {
        val flags = listOf("running", "paused", "restarting", "stopped", "failed", "healthy", "unhealthy")
        fun on(state: String, status: String) = containerRow(json("""{"Id":"a","Names":["/a"],"State":"$state","Status":"$status","Created":1794939096}"""), "+0300", emptyMap())
            .values.filter { (key, value) -> key in flags && value == "true" }.keys
        assertEquals(setOf("running", "healthy"), on("running", "Up 2 hours (healthy)"))
        assertEquals(setOf("running", "unhealthy"), on("running", "Up 5 minutes (unhealthy)"))
        assertEquals(setOf("paused"), on("paused", "Up 3 minutes (Paused)"))
        assertEquals(setOf("restarting"), on("restarting", "Restarting (1) 10 seconds ago"))
        assertEquals(setOf("stopped"), on("exited", "Exited (0) 3 days ago"))
        assertEquals(setOf("stopped"), on("created", "Created"))
        assertEquals(setOf("failed"), on("exited", "Exited (137) 2 hours ago"))
        assertEquals(setOf("failed"), on("dead", "Dead"))
    }

    @Test
    fun theListIsWhatDockerPsShowsWithTheLoadOfRunningContainers() = runTest {
        val host = host("GET /v1.56/containers/json?all=1 " to engineChunked(LIST_29), "GET /v1.56/containers/" to engineAnswer(STATS_29))
        val rows = table(host).read()
        assertEquals(listOf("nh-sample-tty", "nh-sample-web"), rows.map { it["name"] })
        val web = rows[1]
        assertEquals("87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d", web.id)
        assertEquals(
            listOf("alpine:latest", "running", "", "0.0.0.0:18080->80/tcp, [::]:18080->80/tcp", "nh-sample-net", "172.30.9.2", "2026-11-17 20:21:04", "0.16", "737280", "4516"),
            listOf("image", "state", "health", "ports", "networks", "ip-address", "created", "cpu", "memory", "rx-byte").map { web[it] },
        )
        assertEquals("", rows[0]["ports"])
        assertEquals(listOf("true", "false", "false"), listOf("running", "stopped", "failed").map { web[it] })
        assertEquals(
            listOf("GET /version", "GET /v1.56/containers/json?all=1", "GET /v1.56/containers/5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a/stats?stream=false&one-shot=true"),
            lines(host).take(3),
        )
    }

    @Test
    fun pruneTakesTheContainersThatDoNotRun() = runTest {
        val stopped = LIST_29.replaceFirst(""""State":"running"""", """"State":"exited"""")
        val unused = assertNotNull(table(host("GET /v1.56/containers/json?all=1 " to engineChunked(stopped))).run)("unused", emptyMap())
        assertEquals(listOf("nh-sample-tty"), unused.map { it["name"] })
    }

    @Test
    fun theTimesOfALogComeInTheTimeOfTheHost() {
        // in UTC or with an offset, to the millisecond; what is no time stays as it came
        assertEquals(
            listOf("2026-11-17 20:22:10.418", "2026-11-17 20:22:10.418", "-"),
            listOf("2026-11-17T17:22:10.418265093Z", "2026-11-17T20:22:10.418265093+03:00", "-").map { logTime(it, "+0300") },
        )
    }

    @Test
    fun aSampleWithoutItsOwnBeforeCountsTheCpuAgainstTheLastRead() {
        val (first, second) = STATS_STREAM_PODMAN_5.map(::json)
        assertEquals("0.19", statsOf(second, cpuReading(first))["cpu"])
        assertNull(cpuReading(json("""{"read":"2026-11-17T17:56:30.416747153Z"}""")))
        // one-shot stats of the same container eighty minutes after the sample of the list
        val shot = json(ONE_SHOT_29)
        assertEquals(listOf("", "741376"), listOf("cpu", "memory").map { statsOf(shot)[it] })
        assertEquals("0.16", statsOf(shot, cpuReading(json(STATS_29)))["cpu"])
    }

    @Test
    fun theFormShowsEverySettingOfADockerContainerFromInspect() {
        val item = containerItem(json(INSPECT_29), "+0300")
        assertEquals(
            mapOf(
                "name" to "nh-sample-web", "image" to "alpine:latest", "command" to "sh -c 'while true; do echo out \$(date +%s); echo err >&2; sleep 1; done'",
                "entrypoint" to "", "workdir" to "/", "labels" to "nh.sample=1", "tty" to "false", "env" to "GREETING=hello\nPATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "secret-env" to "", "ports" to "18080:80/tcp", "mounts" to "type=volume,source=nh-sample-vol,target=/data", "network" to "nh-sample-net", "aliases" to "",
                "mac-address" to "1e:19:12:be:79:c6", "memory" to "", "shm-size" to "64m", "pids-limit" to "", "restart" to "unless-stopped", "restart-retries" to "",
                "log-driver" to "json-file", "runtime" to "runc", "privileged" to "false", "state" to "running", "started" to "2026-11-17 20:21:04", "ip-address" to "172.30.9.2",
                "restart-count" to "0",
            ),
            item.values.filterKeys { it in setOf("name", "image", "command", "entrypoint", "workdir", "labels", "tty", "env", "secret-env", "ports", "mounts", "network", "aliases",
                "mac-address", "memory", "shm-size", "pids-limit", "restart", "restart-retries", "log-driver", "runtime", "privileged", "state", "started", "ip-address", "restart-count") },
        )
    }

    @Test
    fun podmanSaysBridgeForEveryNetworkOfItsOwnAndANewContainerGoesToTheNetworkByItsName() = runTest {
        val item = containerItem(json(INSPECT_PODMAN_5), "+0000")
        assertEquals(
            listOf("podman", "", "10.88.0.2", "RLIMIT_NOFILE=524288:524288\nRLIMIT_NPROC=772879:772879", "journald", "no", "2048", "64000k", "15", "10", "0.0.0.0:18080:80/tcp"),
            listOf("network", "aliases", "ip-address", "ulimits", "log-driver", "restart", "pids-limit", "shm-size", "stop-signal", "stop-timeout", "ports").map { item[it] },
        )
        val id = json(INSPECT_PODMAN_5).getValue("Id").jsonPrimitive.content
        // the container of the lab sat in a network of its own, which Podman reports as bridge as well
        val own = INSPECT_PODMAN_5.replace(""""Networks":{"podman":""", """"Networks":{"nh-lab-net":""")
        for ((inspected, network) in listOf(INSPECT_PODMAN_5 to "podman", own to "nh-lab-net")) {
            val host = host("GET /v1.56/containers/$id/json " to engineAnswer(inspected), "POST /v1.56/containers/create" to engineAnswer(CREATED_C3, "201 Created"))
            assertNotNull(table(host).run)("duplicate", mapOf("numbers" to id))
            val made = body(host, "POST /v1.56/containers/create?name=nh-sample-web-copy")
            assertEquals(network, made["HostConfig"]?.jsonObject?.get("NetworkMode")?.jsonPrimitive?.content)
            assertEquals(setOf(network), made["NetworkingConfig"]?.jsonObject?.get("EndpointsConfig")?.jsonObject?.keys)
        }
    }

    @Test
    fun anAnonymousVolumeAndATmpfsKeepTheirPlaceAndTheHealthcheckReadsAsDockerRunWritesIt() = runTest {
        val docker = containerItem(json(INSPECT_ANON_29), "+0000")
        assertEquals(
            listOf("type=volume,source=fabe588542df3ebd8dde8e1adc969190183a99b094d5f479136e0f12722faded,target=/data\ntype=tmpfs,target=/run,tmpfs-size=64m", "true", "30s", "", "starting"),
            listOf("mounts", "health-cmd", "health-interval", "health-retries", "health").map { docker[it] },
        )
        val podman = containerItem(json(INSPECT_ANON_PODMAN_5), "+0000")
        assertEquals(
            listOf("true", "30s", "30s", "3", "healthy", "type=volume,source=450f44473c4dc838651a49212ac4b3cc056a3995b08bde5a14315a189373f413,target=/data\ntype=tmpfs,target=/run,tmpfs-size=64m"),
            listOf("health-cmd", "health-interval", "health-timeout", "health-retries", "health", "mounts").map { podman[it] },
        )
        val id = json(INSPECT_ANON_29).getValue("Id").jsonPrimitive.content
        val host = host(
            "GET /v1.56/containers/$id/json " to engineChunked(INSPECT_ANON_29), "POST /v1.56/containers/create" to engineAnswer(CREATED_C3, "201 Created"),
            "POST /v1.56/containers/" to ENGINE_NO_CONTENT, "DELETE /v1.56/containers/" to ENGINE_NO_CONTENT,
        )
        assertNotNull(table(host).set)(id, mapOf("command" to "sleep 60", "recreate" to "true"))
        val made = body(host, "POST /v1.56/containers/create?name=nh-anon").getValue("HostConfig").jsonObject
        assertEquals(
            Json.parseToJsonElement("""[{"Type":"volume","Source":"fabe588542df3ebd8dde8e1adc969190183a99b094d5f479136e0f12722faded","Target":"/data"}]"""),
            made["Mounts"],
        )
        assertEquals(json("""{"/run":"size=64m"}"""), made["Tmpfs"])
    }

    @Test
    fun aNewContainerTakesTheFieldsGivenPullsAMissingImageAndStarts() = runTest {
        lateinit var host: DockerHost
        host = DockerHost(listOf("docker system dial-stdio </dev/null" to ok())) {
            val made = host.relays.any { (_, engine) -> engine.requests.any { request -> request.startsWith("POST /v1.56/containers/create") } }
            DockerEngine(listOf(
                "GET /version " to engineAnswer(VERSION),
                "POST /v1.56/containers/create?name=nh-c3 " to if (made) engineAnswer(CREATED_C3, "201 Created") else engineAnswer(MISSING_IMAGE, "404 Not Found"),
                "POST /v1.56/images/create?" to engineStream(listOf("""{"status":"Pull complete","progressDetail":{},"id":"37bb94b0940b"}"""), "\r\n"),
                "POST /v1.56/containers/e736fc8bafbea47449f6baca64e002291d34ea098d7626d38eb623485bd07380/start " to ENGINE_NO_CONTENT,
            ))
        }
        val id = assertNotNull(table(host).add)(mapOf(
            "name" to "nh-c3", "image" to "docker.io/library/alpine:latest", "command" to "sleep 3600", "env" to "A=1", "labels" to "nh=c3", "ports" to "18081:80",
            "mounts" to "nh-c3-vol:/data", "network" to "nh-c3-net", "aliases" to "web", "restart" to "unless-stopped", "memory" to "64m",
        ))
        assertEquals("e736fc8bafbea47449f6baca64e002291d34ea098d7626d38eb623485bd07380", id)
        assertEquals(
            listOf(
                "GET /version", "POST /v1.56/containers/create?name=nh-c3", "POST /v1.56/images/create?fromImage=docker.io%2Flibrary%2Falpine&tag=latest",
                "POST /v1.56/containers/create?name=nh-c3", "POST /v1.56/containers/e736fc8bafbea47449f6baca64e002291d34ea098d7626d38eb623485bd07380/start",
            ),
            lines(host),
        )
        // what docker run -p 18081:80 -v nh-c3-vol:/data --network nh-c3-net --network-alias web --restart unless-stopped -m 64m sends
        assertEquals(
            json(
                """{"Image":"docker.io/library/alpine:latest","Cmd":["sleep","3600"],"Labels":{"nh":"c3"},"ExposedPorts":{"80/tcp":{}},"Env":["A=1"],"HostConfig":{""" +
                    """"PortBindings":{"80/tcp":[{"HostIp":"","HostPort":"18081"}]},"Mounts":[{"Type":"volume","Source":"nh-c3-vol","Target":"/data"}],"NetworkMode":"nh-c3-net",""" +
                    """"Memory":67108864,"RestartPolicy":{"Name":"unless-stopped","MaximumRetryCount":0}},"NetworkingConfig":{"EndpointsConfig":{"nh-c3-net":{"Aliases":["web"]}}}}""",
            ),
            body(host, "POST /v1.56/containers/create?name=nh-c3"),
        )
    }

    @Test
    fun theImageANewContainerOrOneMadeAnewPullsGoesWithTheLoginTheAppKeepsForItsRegistry() = runTest {
        val secret = "rd-test-secret"
        val logins = """[{"server":"ghcr.io","username":"other","password":"rd-test-other"},{"server":"registry.example.com:5000","username":"nh","password":"$secret"}]"""
        val pulled = engineStream(listOf("""{"status":"Pull complete","progressDetail":{},"id":"37bb94b0940b"}"""), "\r\n")
        fun login(host: DockerHost) = host.relays.flatMap { it.second.requests }.single { it.startsWith("POST /v1.56/images/create?") }.split("\r\n")
            .single { it.startsWith("X-Registry-Auth: ") }.substringAfter(": ").let { Json.parseToJsonElement(Base64.UrlSafe.decode(it).decodeToString()) }
        val expected = Json.parseToJsonElement("""{"username":"nh","password":"$secret","serveraddress":"registry.example.com:5000"}""")

        lateinit var first: DockerHost
        first = DockerHost(listOf("docker system dial-stdio </dev/null" to ok())) {
            val made = first.relays.any { (_, engine) -> engine.requests.any { request -> request.startsWith("POST /v1.56/containers/create") } }
            DockerEngine(listOf(
                "GET /version " to engineAnswer(VERSION),
                "POST /v1.56/containers/create?name=nh-private " to if (made) engineAnswer(CREATED_C3, "201 Created") else engineAnswer(MISSING_IMAGE, "404 Not Found"),
                "POST /v1.56/images/create?" to pulled,
                "POST /v1.56/containers/" to ENGINE_NO_CONTENT,
            ))
        }
        assertNotNull(table(first).add)(mapOf("name" to "nh-private", "image" to "registry.example.com:5000/nh/app:1.2", "registries" to logins))
        assertEquals(expected, login(first))
        assertFalse(secret in body(first, "POST /v1.56/containers/create?name=nh-private").toString())

        val old = "87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d"
        val again = host(
            "GET /v1.56/containers/$old/json " to engineAnswer(INSPECT_29.replace(""""Image":"alpine:latest"""", """"Image":"registry.example.com:5000/nh/app:1.2"""")),
            "POST /v1.56/images/create?" to pulled, "POST /v1.56/containers/create" to engineAnswer(CREATED_C3, "201 Created"), "POST /v1.56/containers/" to ENGINE_NO_CONTENT,
            "DELETE /v1.56/containers/" to ENGINE_NO_CONTENT,
        )
        assertNotNull(table(again).run)("recreate", mapOf("numbers" to old, "registries" to logins))
        assertEquals(expected, login(again))

        // an image of a registry the app keeps no login for goes without the header, as a pull of the window does
        assertEquals(emptyMap(), savedLogin(logins, "alpine:latest"))
        assertEquals(emptyMap(), savedLogin(null, "registry.example.com:5000/nh/app:1.2"))
        assertEquals(setOf("X-Registry-Auth"), savedLogin("""[{"server":"docker.io","username":"nh","password":"$secret"}]""", "nh/private").keys)
    }

    @Test
    fun theActionsOfThePackAreCommandsOfTheTable() {
        val commands = table(host()).commands
        val actions = bundledPacks().getValue("ssh-docker-containers.yaml").paths.getValue("container").actions.map { it.command }
        assertEquals(emptyList(), actions.filterNot { it in commands })
        assertFalse("terminate" in commands)
    }

    @Test
    fun resourcesGoToUpdateANameAloneIsARenameAndTheRestWaitsForRecreateTicked() = runTest {
        val id = "87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d"
        val host = host("GET /v1.56/containers/$id/json " to engineAnswer(INSPECT_29), "POST /v1.56/containers/$id/" to ENGINE_NO_CONTENT)
        val set = assertNotNull(table(host).set)
        set(id, mapOf("memory" to "128m", "memory-swap" to "256m", "cpu-shares" to "512"))
        // the very body docker update took on the lab host
        assertEquals(json("""{"Memory":134217728,"MemorySwap":268435456,"CpuShares":512}"""), body(host, "POST /v1.56/containers/$id/update"))
        set(id, mapOf("name" to "nh-c3b"))
        assertTrue("POST /v1.56/containers/$id/rename?name=nh-c3b" in lines(host))
        val refused = assertFailsWith<DeviceException> { set(id, mapOf("env" to "GREETING=bye")) }
        assertEquals(ErrorCategory.ArgumentValue, refused.category)
        assertTrue(refused.message.orEmpty().startsWith("this change takes the container made anew"))
        assertFalse(lines(host).any { it.startsWith("POST /v1.56/containers/create") })
    }

    @Test
    fun recreateKeepsTheOldContainerAsideUntilTheNewOneRunsAndBringsItBackWhenItDoesNot() = runTest {
        val old = "87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d"
        val new = "e736fc8bafbea47449f6baca64e002291d34ea098d7626d38eb623485bd07380"
        val taken = """{"message":"driver failed programming external connectivity on endpoint nh-sample-web: Bind for 0.0.0.0:18080 failed: port is already allocated"}"""
        fun starting(start: ByteArray) = host(
            "GET /v1.56/containers/$old/json " to engineAnswer(INSPECT_29), "POST /v1.56/containers/create" to engineAnswer(CREATED_C3, "201 Created"),
            "POST /v1.56/containers/$new/start " to start, "POST /v1.56/containers/$old/" to ENGINE_NO_CONTENT, "DELETE /v1.56/containers/" to ENGINE_NO_CONTENT,
        )
        val good = starting(ENGINE_NO_CONTENT)
        assertNotNull(table(good).set)(old, mapOf("command" to "sleep 60", "recreate" to "true"))
        assertEquals(
            listOf(
                "POST /v1.56/containers/$old/rename?name=nh-sample-web-replaced", "POST /v1.56/containers/create?name=nh-sample-web", "POST /v1.56/containers/$old/stop",
                "POST /v1.56/containers/$new/start", "DELETE /v1.56/containers/$old?force=1",
            ),
            lines(good).filterNot { it.startsWith("GET") },
        )
        val made = body(good, "POST /v1.56/containers/create?name=nh-sample-web")
        assertEquals(listOf("sleep", "60"), made["Cmd"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertNull(made["Hostname"])
        assertEquals(listOf("nh-sample-vol:/data"), made["HostConfig"]?.jsonObject?.get("Binds")?.jsonArray?.map { it.jsonPrimitive.content })

        val bad = starting(engineAnswer(taken, "500 Internal Server Error"))
        val failed = assertFailsWith<DeviceException> { assertNotNull(table(bad).set)(old, mapOf("command" to "sleep 60", "recreate" to "true")) }
        assertEquals("driver failed programming external connectivity on endpoint nh-sample-web: Bind for 0.0.0.0:18080 failed: port is already allocated", failed.message)
        assertEquals(
            listOf(
                "POST /v1.56/containers/$old/rename?name=nh-sample-web-replaced", "POST /v1.56/containers/create?name=nh-sample-web", "POST /v1.56/containers/$old/stop",
                "POST /v1.56/containers/$new/start", "DELETE /v1.56/containers/$new?force=1", "POST /v1.56/containers/$old/rename?name=nh-sample-web", "POST /v1.56/containers/$old/start",
            ),
            lines(bad).filterNot { it.startsWith("GET") },
        )
    }

    @Test
    fun aSecretVariableIsNeverReadBackAndAnEmptyValueKeepsWhatItHad() = runTest {
        val old = "87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d"
        val inspected = INSPECT_29.replace(""""Env":["GREETING=hello",""", """"Env":["DB_PASSWORD=s3cret","GREETING=hello",""")
        val item = containerItem(json(inspected), "+0300")
        assertEquals("DB_PASSWORD=", item["secret-env"])
        assertFalse(item.values.values.any { "s3cret" in it })
        val host = host(
            "GET /v1.56/containers/$old/json " to engineAnswer(inspected), "POST /v1.56/containers/create" to engineAnswer(CREATED_C3, "201 Created"),
            "POST /v1.56/containers/" to ENGINE_NO_CONTENT, "DELETE /v1.56/containers/" to ENGINE_NO_CONTENT,
        )
        val containers = table(host)
        assertNotNull(containers.set)(old, mapOf("secret-env" to "DB_PASSWORD=\nAPI_TOKEN=t0ken", "recreate" to "true"))
        assertEquals(
            listOf("GREETING=hello", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "DB_PASSWORD=s3cret", "API_TOKEN=t0ken"),
            body(host, "POST /v1.56/containers/create?name=nh-sample-web")["Env"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        val shown = assertNotNull(containers.run)("inspect", mapOf("id" to old)).single()["json"].orEmpty()
        assertTrue("\"DB_PASSWORD=********\"" in shown)
        assertFalse("s3cret" in shown)
    }

    @Test
    fun portsMountsSizesDurationsAndCpusAreReadAndWrittenAsDockerRunTakesThem() {
        val bound = bindings("18081:80\n127.0.0.1::53/udp\n[::1]:8080:8080/tcp\n9000")
        assertEquals(
            mapOf("80/tcp" to listOf("" to "18081"), "53/udp" to listOf("127.0.0.1" to ""), "8080/tcp" to listOf("::1" to "8080"), "9000/tcp" to listOf("" to "")),
            bound,
        )
        val written = json("""{"80/tcp":[{"HostIp":"","HostPort":"18081"}],"53/udp":[{"HostIp":"127.0.0.1","HostPort":""}],"8080/tcp":[{"HostIp":"::1","HostPort":"8080"}],"9000/tcp":[{"HostIp":"","HostPort":""}]}""")
        assertEquals("18081:80/tcp\n127.0.0.1::53/udp\n[::1]:8080:8080/tcp\n9000/tcp", portsOf(written))
        assertFailsWith<DeviceException> { bindings("80:") }

        assertEquals(
            json("""{"Type":"bind","Source":"/srv","Target":"/srv","ReadOnly":true,"BindOptions":{"Propagation":"rshared"}}"""),
            mountOf("type=bind,source=/srv,target=/srv,readonly,bind-propagation=rshared"),
        )
        assertEquals(json("""{"Type":"volume","Source":"nh-vol","Target":"/data","ReadOnly":true}"""), mountOf("nh-vol:/data:ro"))
        assertEquals(json("""{"Type":"bind","Source":"/etc/nh","Target":"/etc/nh"}"""), mountOf("/etc/nh:/etc/nh"))
        assertEquals(json("""{"Type":"volume","Target":"/cache"}"""), mountOf("/cache"))
        assertEquals(json("""{"Type":"tmpfs","Target":"/run","TmpfsOptions":{"SizeBytes":67108864,"Mode":1023}}"""), mountOf("type=tmpfs,target=/run,tmpfs-size=64m,tmpfs-mode=1777"))
        assertFailsWith<DeviceException> { mountOf("type=nfs,target=/x") }

        assertEquals(536_870_912L, sizeBytes("512m"))
        assertEquals(listOf("512m", "1g", "64000k", "1000", "-1"), listOf(536_870_912L, 1L shl 30, 65_536_000L, 1000L, -1L).map(::size))
        assertFailsWith<DeviceException> { sizeBytes("12q") }
        assertEquals(listOf(90_000_000_000L, 500_000_000L, 30_000_000_000L), listOf("1m30s", "500ms", "30").map(::nanoseconds))
        assertEquals(listOf("1m30s", "500ms"), listOf(90_000_000_000L, 500_000_000L).map(::interval))
        assertFailsWith<DeviceException> { nanoseconds("5 minutes") }
        assertEquals(1_500_000_000L, nanoCpus("1.5"))
        assertEquals(listOf("1.5", "2", "0.25"), listOf(1_500_000_000L, 2_000_000_000L, 250_000_000L).map(::cpus))

        assertEquals("fromImage=alpine&tag=latest", pulled("alpine"))
        assertEquals("fromImage=registry.example.com%3A5000%2Fnh%2Fapp&tag=1.2", pulled("registry.example.com:5000/nh/app:1.2"))
        assertEquals("fromImage=registry.example.com%3A5000%2Fnh%2Fapp&tag=latest", pulled("registry.example.com:5000/nh/app"))
        assertEquals("fromImage=alpine%40sha256%3Aabc", pulled("alpine@sha256:abc"))

        val command = listOf("sh", "-c", "echo \$(date) > /tmp/x")
        assertEquals("sh -c 'echo \$(date) > /tmp/x'", shellWords(command))
        assertEquals(command, words(shellWords(command)))
        assertEquals("exec sudo docker exec -it -u 'www-data' 'nh-sample-web' bash", terminal("sudo docker", "nh-sample-web", "bash", "www-data"))
        assertEquals("exec podman attach 'nh-sample-web'", terminal("podman", "nh-sample-web", "attach", ""))
        assertEquals("exec docker exec -it 'nh-sample-web' sh -c 'command -v bash >/dev/null && exec bash || exec sh'", terminal("docker", "nh-sample-web", "", ""))
    }

    @Test
    fun theProcessesAndTheChangesOfAContainerBecomeRows() {
        val processes = processes(json(TOP_29))
        assertEquals(listOf("727", "901"), processes.map { it.id })
        assertEquals(listOf("root", "727", "702", "sleep 1"), listOf("uid", "pid", "ppid", "cmd").map { if (it == "cmd") processes[1][it] else processes[0][it] })
        assertEquals(listOf("/data" to "added"), differences(Json.parseToJsonElement(CHANGES_29)).map { it["path"] to it["kind"] })
        assertEquals(emptyList(), differences(JsonNull))
    }

    @Test
    fun filesAreListedByLsAndComeAndGoAsTarArchives() = runTest {
        val busybox = listing(LS_BUSYBOX_29.joinToString("\n")).associateBy { it.id }
        assertEquals(listOf("alpine-release", "apk", "busybox-paths.d"), busybox.keys.take(3))
        assertEquals(listOf("file", "7", "Nov 5 09:17"), listOf("type", "size", "modified").map { busybox["alpine-release"]?.get(it) })
        assertEquals(listOf("link", "/proc/mounts"), listOf("type", "target").map { busybox["mtab"]?.get(it) })
        assertEquals(listOf("directory", "", "Mar 24 2026"), listOf(busybox["periodic"]?.get("type"), busybox["periodic"]?.get("size"), busybox["fstab"]?.get("modified")))
        val gnu = listing(LS_GNU.joinToString("\n")).associateBy { it.id }
        assertEquals(listOf(".pwd.lock", "alternatives", "apt"), gnu.keys.take(3))
        assertEquals(listOf("root", "shadow", "364"), listOf("owner", "group", "size").map { gnu["gshadow"]?.get(it) })
        assertEquals("Jun 24 2025", gnu["kernel"]?.get("modified"))
        // a device has its major and minor number where a file has its size
        assertEquals("other" to "", listing("crw-rw-rw-    1 root     root        1,   3 Nov 17 17:21 null").single().let { it["type"] to it["size"] })

        val docker = Base64.decode(ARCHIVE_DOCKER_C3)
        assertEquals("hostname" to "e736fc8bafbe\n", untar(docker)?.let { (name, content) -> name to content.decodeToString() })
        assertEquals("hostname" to "localhost\n", untar(Base64.decode(ARCHIVE_PODMAN_C3))?.let { (name, content) -> name to content.decodeToString() })
        // the same bytes the engine sent, so it takes ours as well
        assertContentEquals(docker, tar("hostname", "e736fc8bafbe\n".encodeToByteArray(), "15277113042".toLong(8)))
        assertNull(untar(docker.copyOf().also { it[156] = '5'.code.toByte() }))
        assertFailsWith<DeviceException> { tar("x".repeat(101), ByteArray(0), 0) }

        val exec = "e70bbfce178570d270c22b71e55945654ff5b5c2a91ffd82584ba906f64b2c11"
        val container = "87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d"
        val host = host(
            "POST /v1.56/containers/$container/exec " to engineAnswer("""{"Id":"$exec"}""", "201 Created"),
            "POST /v1.56/exec/$exec/start " to upgraded(frame(1, "-rw-r--r--    1 root     root            13 Nov 17 17:21 hostname\n")),
            "GET /v1.56/exec/$exec/json " to engineAnswer("""{"ExitCode":0,"Running":false}"""),
            "PUT /v1.56/containers/$container/archive?path=%2Ftmp " to engineAnswer(""),
        )
        val run = assertNotNull(table(host).run)
        assertEquals(listOf("hostname"), run("ls", mapOf("id" to container, "path" to "/etc")).map { it["name"] })
        // with the slash ls lists the folder a link leads to: /bin of the images of Debian and Ubuntu is a link to usr/bin
        assertEquals(listOf("ls", "-la", "/etc/"), body(host, "POST /v1.56/containers/$container/exec")["Cmd"]?.jsonArray?.map { it.jsonPrimitive.content })
        run("upload", mapOf("id" to container, "path" to "/tmp", "name" to "note.txt", "content" to Base64.encode("hello\n".encodeToByteArray())))
        val put = host.relays.flatMap { it.second.requests }.single { it.startsWith("PUT ") }
        assertTrue("Content-Type: application/x-tar\r\n" in put)
        assertEquals("note.txt" to "hello\n", untar(put.substringAfter("\r\n\r\n").encodeToByteArray())?.let { (name, content) -> name to content.decodeToString() })
    }

    @Test
    fun aCommandThatFailsInTheContainerSaysWhatItSaidOnStderr() = runTest {
        val exec = "e70bbfce178570d270c22b71e55945654ff5b5c2a91ffd82584ba906f64b2c11"
        val host = host(
            "POST /v1.56/containers/nh-sample-web/exec " to engineAnswer("""{"Id":"$exec"}""", "201 Created"),
            "POST /v1.56/exec/$exec/start " to upgraded(frame(2, "ls: /nope: No such file or directory\n")),
            "GET /v1.56/exec/$exec/json " to engineAnswer("""{"ExitCode":1,"Running":false}"""),
        )
        val failed = assertFailsWith<DeviceException> { assertNotNull(table(host).run)("ls", mapOf("id" to "nh-sample-web", "path" to "/nope")) }
        assertEquals("ls: /nope: No such file or directory", failed.message)
    }

    private companion object {
        const val VERSION = """{"ApiVersion":"1.56","MinAPIVersion":"1.40"}"""
        const val MISSING_IMAGE = """{"message":"No such image: docker.io/library/alpine:latest"}"""

        // recorded on Docker 29.9.3 (API 1.56) on Arch and on Podman 5.8.9 (compatible API 1.44) on Fedora 44
        const val INSPECT_29 = """{"Id":"87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d","Created":"2026-11-17T17:21:04.043430702Z","Path":"sh","Args":["-c",""" +
            """"while true; do echo out $(date +%s); echo err >&2; sleep 1; done"],"State":{"Status":"running","Running":true,"Paused":false,"Restarting":false,""" +
            """"OOMKilled":false,"Dead":false,"Pid":727,"ExitCode":0,"Error":"","StartedAt":"2026-11-17T17:21:04.730585129Z","FinishedAt":"0001-01-01T00:00:00Z"},""" +
            """"Image":"sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf","ResolvConfPath":"/var/lib/docker/containers/87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d/resolv.conf",""" +
            """"HostnamePath":"/var/lib/docker/containers/87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d/hostname","HostsPath":"/var/lib/docker/containers/87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d/hosts",""" +
            """"LogPath":"/var/lib/docker/containers/87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d/87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d-json.log",""" +
            """"Name":"/nh-sample-web","RestartCount":0,"Driver":"overlayfs","Platform":"linux","MountLabel":"","ProcessLabel":"","AppArmorProfile":"","ExecIDs":null,""" +
            """"HostConfig":{"Binds":["nh-sample-vol:/data"],"ContainerIDFile":"","LogConfig":{"Type":"json-file","Config":{}},"NetworkMode":"nh-sample-net",""" +
            """"PortBindings":{"80/tcp":[{"HostIp":"","HostPort":"18080"}]},"RestartPolicy":{"Name":"unless-stopped","MaximumRetryCount":0},"AutoRemove":false,""" +
            """"VolumeDriver":"","VolumesFrom":null,"ConsoleSize":[0,0],"CapAdd":null,"CapDrop":null,"CgroupnsMode":"private","Dns":null,"DnsOptions":[],"DnsSearch":[],""" +
            """"ExtraHosts":null,"GroupAdd":null,"IpcMode":"private","Cgroup":"","Links":null,"OomScoreAdj":0,"PidMode":"","Privileged":false,"PublishAllPorts":false,""" +
            """"ReadonlyRootfs":false,"SecurityOpt":null,"UTSMode":"","UsernsMode":"","ShmSize":67108864,"Runtime":"runc","Isolation":"","CpuShares":0,"Memory":0,""" +
            """"NanoCpus":0,"CgroupParent":"","BlkioWeight":0,"BlkioWeightDevice":[],"BlkioDeviceReadBps":[],"BlkioDeviceWriteBps":[],"BlkioDeviceReadIOps":[],""" +
            """"BlkioDeviceWriteIOps":[],"CpuPeriod":0,"CpuQuota":0,"CpuRealtimePeriod":0,"CpuRealtimeRuntime":0,"CpusetCpus":"","CpusetMems":"","Devices":[],""" +
            """"DeviceCgroupRules":null,"DeviceRequests":null,"MemoryReservation":0,"MemorySwap":0,"MemorySwappiness":null,"OomKillDisable":null,"PidsLimit":null,""" +
            """"Ulimits":null,"CpuCount":0,"CpuPercent":0,"IOMaximumIOps":0,"IOMaximumBandwidth":0,"MaskedPaths":["/proc/acpi","/proc/asound","/proc/interrupts",""" +
            """"/proc/kcore","/proc/keys","/proc/latency_stats","/proc/sched_debug","/proc/scsi","/proc/timer_list","/proc/timer_stats","/sys/devices/virtual/powercap",""" +
            """"/sys/firmware","/sys/devices/system/cpu/cpu0/thermal_throttle","/sys/devices/system/cpu/cpu1/thermal_throttle","/sys/devices/system/cpu/cpu2/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu3/thermal_throttle","/sys/devices/system/cpu/cpu4/thermal_throttle","/sys/devices/system/cpu/cpu5/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu6/thermal_throttle","/sys/devices/system/cpu/cpu7/thermal_throttle","/sys/devices/system/cpu/cpu8/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu9/thermal_throttle","/sys/devices/system/cpu/cpu10/thermal_throttle","/sys/devices/system/cpu/cpu11/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu12/thermal_throttle","/sys/devices/system/cpu/cpu13/thermal_throttle","/sys/devices/system/cpu/cpu14/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu15/thermal_throttle","/sys/devices/system/cpu/cpu16/thermal_throttle","/sys/devices/system/cpu/cpu17/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu18/thermal_throttle","/sys/devices/system/cpu/cpu19/thermal_throttle","/sys/devices/system/cpu/cpu20/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu21/thermal_throttle","/sys/devices/system/cpu/cpu22/thermal_throttle","/sys/devices/system/cpu/cpu23/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu24/thermal_throttle","/sys/devices/system/cpu/cpu25/thermal_throttle","/sys/devices/system/cpu/cpu26/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu27/thermal_throttle","/sys/devices/system/cpu/cpu28/thermal_throttle","/sys/devices/system/cpu/cpu29/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu30/thermal_throttle","/sys/devices/system/cpu/cpu31/thermal_throttle","/sys/devices/system/cpu/cpu32/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu33/thermal_throttle","/sys/devices/system/cpu/cpu34/thermal_throttle","/sys/devices/system/cpu/cpu35/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu36/thermal_throttle","/sys/devices/system/cpu/cpu37/thermal_throttle","/sys/devices/system/cpu/cpu38/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu39/thermal_throttle","/sys/devices/system/cpu/cpu40/thermal_throttle","/sys/devices/system/cpu/cpu41/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu42/thermal_throttle","/sys/devices/system/cpu/cpu43/thermal_throttle","/sys/devices/system/cpu/cpu44/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu45/thermal_throttle","/sys/devices/system/cpu/cpu46/thermal_throttle","/sys/devices/system/cpu/cpu47/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu48/thermal_throttle","/sys/devices/system/cpu/cpu49/thermal_throttle","/sys/devices/system/cpu/cpu50/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu51/thermal_throttle","/sys/devices/system/cpu/cpu52/thermal_throttle","/sys/devices/system/cpu/cpu53/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu54/thermal_throttle","/sys/devices/system/cpu/cpu55/thermal_throttle","/sys/devices/system/cpu/cpu56/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu57/thermal_throttle","/sys/devices/system/cpu/cpu58/thermal_throttle","/sys/devices/system/cpu/cpu59/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu60/thermal_throttle","/sys/devices/system/cpu/cpu61/thermal_throttle","/sys/devices/system/cpu/cpu62/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu63/thermal_throttle","/sys/devices/system/cpu/cpu64/thermal_throttle","/sys/devices/system/cpu/cpu65/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu66/thermal_throttle","/sys/devices/system/cpu/cpu67/thermal_throttle","/sys/devices/system/cpu/cpu68/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu69/thermal_throttle","/sys/devices/system/cpu/cpu70/thermal_throttle","/sys/devices/system/cpu/cpu71/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu72/thermal_throttle","/sys/devices/system/cpu/cpu73/thermal_throttle","/sys/devices/system/cpu/cpu74/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu75/thermal_throttle","/sys/devices/system/cpu/cpu76/thermal_throttle","/sys/devices/system/cpu/cpu77/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu78/thermal_throttle","/sys/devices/system/cpu/cpu79/thermal_throttle"],"ReadonlyPaths":["/proc/bus","/proc/fs","/proc/irq",""" +
            """"/proc/sys","/proc/sysrq-trigger"]},"Storage":{"RootFS":{"Snapshot":{"Name":"overlayfs"}}},"Mounts":[{"Type":"volume","Name":"nh-sample-vol",""" +
            """"Source":"/var/lib/docker/volumes/nh-sample-vol/_data","Destination":"/data","Driver":"local","Mode":"z","RW":true,"Propagation":""}],"Config":{"Hostname":"87deced11966",""" +
            """"Domainname":"","User":"","AttachStdin":false,"AttachStdout":false,"AttachStderr":false,"ExposedPorts":{"80/tcp":{}},"Tty":false,"OpenStdin":false,""" +
            """"StdinOnce":false,"Env":["GREETING=hello","PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"],"Cmd":["sh","-c","while true; do echo out $(date +%s); echo err >&2; sleep 1; done"],""" +
            """"Image":"alpine:latest","Volumes":null,"WorkingDir":"/","Entrypoint":null,"Labels":{"nh.sample":"1"}},"NetworkSettings":{"SandboxID":"e8549c4315ca23a3030387091e7f32e6185911b7848e9ecd4087d76f1a07be91",""" +
            """"SandboxKey":"/var/run/docker/netns/e8549c4315ca","Ports":{"80/tcp":[{"HostIp":"0.0.0.0","HostPort":"18080"},{"HostIp":"::","HostPort":"18080"}]},""" +
            """"Networks":{"nh-sample-net":{"IPAMConfig":null,"Links":null,"Aliases":null,"DriverOpts":null,"GwPriority":0,"NetworkID":"2ad1893d6f4bcfd3e5ad97f97591af44ddc5c6fa845e7f3e9fcba8879d1fe978",""" +
            """"EndpointID":"044878b019dc1abd9be1bd312d8223807bfd136558d3e2cb685c66c2385a1ace","Gateway":"172.30.9.1","IPAddress":"172.30.9.2","MacAddress":"1e:19:12:be:79:c6",""" +
            """"IPPrefixLen":24,"IPv6Gateway":"","GlobalIPv6Address":"","GlobalIPv6PrefixLen":0,"DNSNames":["nh-sample-web","87deced11966"]}}},"ImageManifestDescriptor":{"mediaType":"application/vnd.oci.image.manifest.v1+json",""" +
            """"digest":"sha256:0f49f331d9212aff11b932863be4eb01cdf962969aa86dab7d1ee21ac6f5f52b","size":1022,"annotations":{"com.docker.official-images.bashbrew.arch":"amd64",""" +
            """"org.opencontainers.image.base.name":"scratch","org.opencontainers.image.created":"2026-11-05T18:00:01Z","org.opencontainers.image.revision":"0c9af76133a2dd9a31dedf3600e423087b29a879",""" +
            """"org.opencontainers.image.source":"https://github.com/alpinelinux/docker-alpine.git#0c9af76133a2dd9a31dedf3600e423087b29a879:x86_64","org.opencontainers.image.url":"https://hub.docker.com/_/alpine",""" +
            """"org.opencontainers.image.version":"3.24.4"},"platform":{"architecture":"amd64","os":"linux"}}}"""
        const val INSPECT_PODMAN_5 = """{"Id":"909aa0962d0d3ef68656655b582ec3dbef14ad7986f98ad6262c66ca44dfcc72","Created":"2026-11-17T17:31:05.421816231Z","Path":"sh","Args":["-c",""" +
            """"while true; do echo out $(date +%s); sleep 1; done"],"State":{"Status":"running","Running":true,"Paused":false,"Restarting":false,"OOMKilled":false,""" +
            """"Dead":false,"Pid":655,"ExitCode":0,"Error":"","StartedAt":"2026-11-17T17:31:05.854678786Z","FinishedAt":"0001-01-01T00:00:00Z"},"Image":"sha256:3ea06e4cb95e726ea6c6cc4377570ac4c4050d78710004cbd631ca809daf7e8e",""" +
            """"ResolvConfPath":"/run/containers/storage/overlay-containers/909aa0962d0d3ef68656655b582ec3dbef14ad7986f98ad6262c66ca44dfcc72/userdata/resolv.conf",""" +
            """"HostnamePath":"/run/containers/storage/overlay-containers/909aa0962d0d3ef68656655b582ec3dbef14ad7986f98ad6262c66ca44dfcc72/userdata/hostname",""" +
            """"HostsPath":"/run/containers/storage/overlay-containers/909aa0962d0d3ef68656655b582ec3dbef14ad7986f98ad6262c66ca44dfcc72/userdata/hosts","LogPath":"",""" +
            """"Name":"/nh-sample-web","RestartCount":0,"Driver":"overlay","Platform":"linux","MountLabel":"","ProcessLabel":"","AppArmorProfile":"","ExecIDs":[],""" +
            """"HostConfig":{"Binds":[],"ContainerIDFile":"","LogConfig":{"Type":"journald","Config":null},"NetworkMode":"bridge","PortBindings":{"80/tcp":[{"HostIp":"0.0.0.0",""" +
            """"HostPort":"18080"}]},"RestartPolicy":{"Name":"no","MaximumRetryCount":0},"AutoRemove":false,"VolumeDriver":"","VolumesFrom":null,"ConsoleSize":[0,""" +
            """0],"Annotations":{"io.container.manager":"libpod","org.opencontainers.image.stopSignal":"15","org.systemd.property.KillSignal":"15","org.systemd.property.TimeoutStopUSec":"uint64 10000000"},""" +
            """"CapAdd":[],"CapDrop":[],"CgroupnsMode":"private","Dns":[],"DnsOptions":[],"DnsSearch":[],"ExtraHosts":[],"GroupAdd":[],"IpcMode":"shareable",""" +
            """"Cgroup":"","Links":null,"OomScoreAdj":0,"PidMode":"private","Privileged":false,"PublishAllPorts":false,"ReadonlyRootfs":false,"SecurityOpt":[],""" +
            """"UTSMode":"private","UsernsMode":"","ShmSize":65536000,"Runtime":"oci","Isolation":"","CpuShares":0,"Memory":0,"NanoCpus":0,"CgroupParent":"",""" +
            """"BlkioWeight":0,"BlkioWeightDevice":null,"BlkioDeviceReadBps":null,"BlkioDeviceWriteBps":null,"BlkioDeviceReadIOps":null,"BlkioDeviceWriteIOps":null,""" +
            """"CpuPeriod":0,"CpuQuota":0,"CpuRealtimePeriod":0,"CpuRealtimeRuntime":0,"CpusetCpus":"","CpusetMems":"","Devices":[],"DeviceCgroupRules":null,""" +
            """"DeviceRequests":null,"MemoryReservation":0,"MemorySwap":0,"MemorySwappiness":0,"OomKillDisable":false,"PidsLimit":2048,"Ulimits":[{"Name":"RLIMIT_NOFILE",""" +
            """"Hard":524288,"Soft":524288},{"Name":"RLIMIT_NPROC","Hard":772879,"Soft":772879}],"CpuCount":0,"CpuPercent":0,"IOMaximumIOps":0,"IOMaximumBandwidth":0,""" +
            """"MaskedPaths":null,"ReadonlyPaths":null},"GraphDriver":{"Data":{"LowerDir":"/var/lib/containers/storage/overlay/75f1d9657d880da5f75298e8f40e9928bb6e260c50d26f97af196845307e3064/diff",""" +
            """"MergedDir":"/var/lib/containers/storage/overlay/f366c13b7e82cf27a299b414a394770fb9f7f70aefdea3ab8ea1d59c80a72fbd/merged","UpperDir":"/var/lib/containers/storage/overlay/f366c13b7e82cf27a299b414a394770fb9f7f70aefdea3ab8ea1d59c80a72fbd/diff",""" +
            """"WorkDir":"/var/lib/containers/storage/overlay/f366c13b7e82cf27a299b414a394770fb9f7f70aefdea3ab8ea1d59c80a72fbd/work"},"Name":"overlay"},"SizeRootFs":0,""" +
            """"Mounts":[],"Config":{"Hostname":"909aa0962d0d","Domainname":"","User":"","AttachStdin":false,"AttachStdout":false,"AttachStderr":false,"ExposedPorts":{"80/tcp":{}},""" +
            """"Tty":false,"OpenStdin":false,"StdinOnce":false,"Env":["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin","container=podman",""" +
            """"HOME=/root","HOSTNAME=909aa0962d0d"],"Cmd":["sh","-c","while true; do echo out $(date +%s); sleep 1; done"],"Image":"docker.io/library/alpine:latest",""" +
            """"Volumes":null,"WorkingDir":"/","Entrypoint":[],"OnBuild":null,"Labels":{},"StopSignal":"15","StopTimeout":10},"NetworkSettings":{"Bridge":"",""" +
            """"SandboxID":"","SandboxKey":"/run/user/0/netns/netns-173af76c-aee4-2989-5433-16b4a0360d27","Ports":{"80/tcp":[{"HostIp":"0.0.0.0","HostPort":"18080"}]},""" +
            """"HairpinMode":false,"LinkLocalIPv6Address":"","LinkLocalIPv6PrefixLen":0,"SecondaryIPAddresses":null,"SecondaryIPv6Addresses":null,"EndpointID":"",""" +
            """"Gateway":"10.88.0.1","GlobalIPv6Address":"","GlobalIPv6PrefixLen":0,"IPAddress":"10.88.0.2","IPPrefixLen":16,"IPv6Gateway":"","MacAddress":"8e:cb:73:74:69:57",""" +
            """"Networks":{"podman":{"IPAMConfig":null,"Links":null,"Aliases":["909aa0962d0d"],"MacAddress":"8e:cb:73:74:69:57","DriverOpts":null,"GwPriority":0,""" +
            """"NetworkID":"2f259bab93aaaaa2542ba43ef33eb990d0999ee1b9924b557b7be53c0b7a1bb9","EndpointID":"","Gateway":"10.88.0.1","IPAddress":"10.88.0.2",""" +
            """"IPPrefixLen":16,"IPv6Gateway":"","GlobalIPv6Address":"","GlobalIPv6PrefixLen":0,"DNSNames":null}}}}"""
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
        const val TOP_29 = """{"Processes":[["root","727","702","0","17:21","?","00:00:00","sh -c while true; do echo out $(date +%s); echo err >&2; sleep 1; done"],["root",""" +
            """"901","727","0","17:21","?","00:00:00","sleep 1"]],"Titles":["UID","PID","PPID","C","STIME","TTY","TIME","CMD"]}"""
        const val CHANGES_29 = """[{"Path":"/data","Kind":1}]"""
        const val CREATED_C3 = """{"Id":"e736fc8bafbea47449f6baca64e002291d34ea098d7626d38eb623485bd07380","Warnings":[]}"""
        const val ARCHIVE_DOCKER_C3 = "aG9zdG5hbWUAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAADAwMDA2NDQAMDAwMDAwMAAwMDAwMDAwADAwMDAwMDAwMDE1ADE1Mjc3MTEzMDQyADAxMTAwMwAgMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB1c3RhcgAwMAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwMDAwMDAwADAwMDAwMDAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABlNzM2ZmM4YmFmYmUKAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        const val ONE_SHOT_29 = """{"id":"87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d","name":"/nh-sample-web","os_type":"linux","read":"2026-11-17T18:41:13.360384931Z",""" +
            """"cpu_stats":{"cpu_usage":{"total_usage":7714483000,"usage_in_kernelmode":6127996000,"usage_in_usermode":1586487000},"system_cpu_usage":44017780000000,""" +
            """"online_cpus":2,"throttling_data":{"periods":0,"throttled_periods":0,"throttled_time":0}},"memory_stats":{"usage":745472,"stats":{"active_anon":118784,""" +
            """"active_file":0,"anon":118784,"anon_thp":0,"file":4096,"file_dirty":0,"file_mapped":0,"file_writeback":0,"inactive_anon":0,"inactive_file":4096,""" +
            """"kernel_stack":32768,"pgactivate":0,"pgdeactivate":0,"pgfault":590876,"pglazyfree":0,"pglazyfreed":0,"pgmajfault":0,"pgrefill":0,"pgscan":0,""" +
            """"pgsteal":0,"shmem":0,"slab":418920,"slab_reclaimable":227184,"slab_unreclaimable":191736,"sock":0,"thp_collapse_alloc":0,"thp_fault_alloc":0,""" +
            """"unevictable":0,"workingset_activate":0,"workingset_nodereclaim":0,"workingset_refault":0},"limit":1073741824},"networks":{"eth0":{"rx_bytes":5146,""" +
            """"rx_packets":51,"rx_errors":0,"rx_dropped":0,"tx_bytes":126,"tx_packets":3,"tx_errors":0,"tx_dropped":0}},"pids_stats":{"current":2,"limit":231863},""" +
            """"blkio_stats":{"io_service_bytes_recursive":null,"io_serviced_recursive":null,"io_queue_recursive":null,"io_service_time_recursive":null,"io_wait_time_recursive":null,""" +
            """"io_merged_recursive":null,"io_time_recursive":null,"sectors_recursive":null},"num_procs":0,"storage_stats":{},"preread":"0001-01-01T00:00:00Z",""" +
            """"precpu_stats":{"cpu_usage":{"total_usage":0,"usage_in_kernelmode":0,"usage_in_usermode":0},"throttling_data":{"periods":0,"throttled_periods":0,""" +
            """"throttled_time":0}}}"""
        const val INSPECT_ANON_29 = """{"Id":"1a929ee5fc3454dc34cb17040fe705dd73032d68e70f8d42e3bec8d4379af16c","Created":"2026-11-17T18:41:14.847194727Z","Path":"sleep","Args":["3600"],""" +
            """"State":{"Status":"running","Running":true,"Paused":false,"Restarting":false,"OOMKilled":false,"Dead":false,"Pid":22462,"ExitCode":0,"Error":"",""" +
            """"StartedAt":"2026-11-17T18:41:15.334646682Z","FinishedAt":"0001-01-01T00:00:00Z","Health":{"Status":"starting","FailingStreak":0,"Log":[]}},""" +
            """"Image":"sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf","ResolvConfPath":"/var/lib/docker/containers/1a929ee5fc3454dc34cb17040fe705dd73032d68e70f8d42e3bec8d4379af16c/resolv.conf",""" +
            """"HostnamePath":"/var/lib/docker/containers/1a929ee5fc3454dc34cb17040fe705dd73032d68e70f8d42e3bec8d4379af16c/hostname","HostsPath":"/var/lib/docker/containers/1a929ee5fc3454dc34cb17040fe705dd73032d68e70f8d42e3bec8d4379af16c/hosts",""" +
            """"LogPath":"/var/lib/docker/containers/1a929ee5fc3454dc34cb17040fe705dd73032d68e70f8d42e3bec8d4379af16c/1a929ee5fc3454dc34cb17040fe705dd73032d68e70f8d42e3bec8d4379af16c-json.log",""" +
            """"Name":"/nh-anon","RestartCount":0,"Driver":"overlayfs","Platform":"linux","MountLabel":"","ProcessLabel":"","AppArmorProfile":"","ExecIDs":null,""" +
            """"HostConfig":{"Binds":null,"ContainerIDFile":"","LogConfig":{"Type":"json-file","Config":{}},"NetworkMode":"bridge","PortBindings":{},"RestartPolicy":{"Name":"no",""" +
            """"MaximumRetryCount":0},"AutoRemove":false,"VolumeDriver":"","VolumesFrom":null,"ConsoleSize":[0,0],"CapAdd":null,"CapDrop":null,"CgroupnsMode":"private",""" +
            """"Dns":null,"DnsOptions":[],"DnsSearch":[],"ExtraHosts":null,"GroupAdd":null,"IpcMode":"private","Cgroup":"","Links":null,"OomScoreAdj":0,"PidMode":"",""" +
            """"Privileged":false,"PublishAllPorts":false,"ReadonlyRootfs":false,"SecurityOpt":null,"Tmpfs":{"/run":"size=64m"},"UTSMode":"","UsernsMode":"",""" +
            """"ShmSize":67108864,"Runtime":"runc","Isolation":"","CpuShares":0,"Memory":0,"NanoCpus":0,"CgroupParent":"","BlkioWeight":0,"BlkioWeightDevice":[],""" +
            """"BlkioDeviceReadBps":[],"BlkioDeviceWriteBps":[],"BlkioDeviceReadIOps":[],"BlkioDeviceWriteIOps":[],"CpuPeriod":0,"CpuQuota":0,"CpuRealtimePeriod":0,""" +
            """"CpuRealtimeRuntime":0,"CpusetCpus":"","CpusetMems":"","Devices":[],"DeviceCgroupRules":null,"DeviceRequests":null,"MemoryReservation":0,"MemorySwap":0,""" +
            """"MemorySwappiness":null,"OomKillDisable":null,"PidsLimit":null,"Ulimits":null,"CpuCount":0,"CpuPercent":0,"IOMaximumIOps":0,"IOMaximumBandwidth":0,""" +
            """"MaskedPaths":["/proc/acpi","/proc/asound","/proc/interrupts","/proc/kcore","/proc/keys","/proc/latency_stats","/proc/sched_debug","/proc/scsi",""" +
            """"/proc/timer_list","/proc/timer_stats","/sys/devices/virtual/powercap","/sys/firmware","/sys/devices/system/cpu/cpu0/thermal_throttle","/sys/devices/system/cpu/cpu1/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu2/thermal_throttle","/sys/devices/system/cpu/cpu3/thermal_throttle","/sys/devices/system/cpu/cpu4/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu5/thermal_throttle","/sys/devices/system/cpu/cpu6/thermal_throttle","/sys/devices/system/cpu/cpu7/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu8/thermal_throttle","/sys/devices/system/cpu/cpu9/thermal_throttle","/sys/devices/system/cpu/cpu10/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu11/thermal_throttle","/sys/devices/system/cpu/cpu12/thermal_throttle","/sys/devices/system/cpu/cpu13/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu14/thermal_throttle","/sys/devices/system/cpu/cpu15/thermal_throttle","/sys/devices/system/cpu/cpu16/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu17/thermal_throttle","/sys/devices/system/cpu/cpu18/thermal_throttle","/sys/devices/system/cpu/cpu19/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu20/thermal_throttle","/sys/devices/system/cpu/cpu21/thermal_throttle","/sys/devices/system/cpu/cpu22/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu23/thermal_throttle","/sys/devices/system/cpu/cpu24/thermal_throttle","/sys/devices/system/cpu/cpu25/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu26/thermal_throttle","/sys/devices/system/cpu/cpu27/thermal_throttle","/sys/devices/system/cpu/cpu28/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu29/thermal_throttle","/sys/devices/system/cpu/cpu30/thermal_throttle","/sys/devices/system/cpu/cpu31/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu32/thermal_throttle","/sys/devices/system/cpu/cpu33/thermal_throttle","/sys/devices/system/cpu/cpu34/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu35/thermal_throttle","/sys/devices/system/cpu/cpu36/thermal_throttle","/sys/devices/system/cpu/cpu37/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu38/thermal_throttle","/sys/devices/system/cpu/cpu39/thermal_throttle","/sys/devices/system/cpu/cpu40/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu41/thermal_throttle","/sys/devices/system/cpu/cpu42/thermal_throttle","/sys/devices/system/cpu/cpu43/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu44/thermal_throttle","/sys/devices/system/cpu/cpu45/thermal_throttle","/sys/devices/system/cpu/cpu46/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu47/thermal_throttle","/sys/devices/system/cpu/cpu48/thermal_throttle","/sys/devices/system/cpu/cpu49/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu50/thermal_throttle","/sys/devices/system/cpu/cpu51/thermal_throttle","/sys/devices/system/cpu/cpu52/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu53/thermal_throttle","/sys/devices/system/cpu/cpu54/thermal_throttle","/sys/devices/system/cpu/cpu55/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu56/thermal_throttle","/sys/devices/system/cpu/cpu57/thermal_throttle","/sys/devices/system/cpu/cpu58/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu59/thermal_throttle","/sys/devices/system/cpu/cpu60/thermal_throttle","/sys/devices/system/cpu/cpu61/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu62/thermal_throttle","/sys/devices/system/cpu/cpu63/thermal_throttle","/sys/devices/system/cpu/cpu64/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu65/thermal_throttle","/sys/devices/system/cpu/cpu66/thermal_throttle","/sys/devices/system/cpu/cpu67/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu68/thermal_throttle","/sys/devices/system/cpu/cpu69/thermal_throttle","/sys/devices/system/cpu/cpu70/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu71/thermal_throttle","/sys/devices/system/cpu/cpu72/thermal_throttle","/sys/devices/system/cpu/cpu73/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu74/thermal_throttle","/sys/devices/system/cpu/cpu75/thermal_throttle","/sys/devices/system/cpu/cpu76/thermal_throttle",""" +
            """"/sys/devices/system/cpu/cpu77/thermal_throttle","/sys/devices/system/cpu/cpu78/thermal_throttle","/sys/devices/system/cpu/cpu79/thermal_throttle"],""" +
            """"ReadonlyPaths":["/proc/bus","/proc/fs","/proc/irq","/proc/sys","/proc/sysrq-trigger"]},"Storage":{"RootFS":{"Snapshot":{"Name":"overlayfs"}}},""" +
            """"Mounts":[{"Type":"volume","Name":"fabe588542df3ebd8dde8e1adc969190183a99b094d5f479136e0f12722faded","Source":"/var/lib/docker/volumes/fabe588542df3ebd8dde8e1adc969190183a99b094d5f479136e0f12722faded/_data",""" +
            """"Destination":"/data","Driver":"local","Mode":"","RW":true,"Propagation":""}],"Config":{"Hostname":"1a929ee5fc34","Domainname":"","User":"",""" +
            """"AttachStdin":false,"AttachStdout":false,"AttachStderr":false,"Tty":false,"OpenStdin":false,"StdinOnce":false,"Env":["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"],""" +
            """"Cmd":["sleep","3600"],"Healthcheck":{"Test":["CMD-SHELL","true"],"Interval":30000000000},"Image":"alpine:latest","Volumes":{"/data":{}},"WorkingDir":"/",""" +
            """"Entrypoint":null,"Labels":{}},"NetworkSettings":{"SandboxID":"e989239ca4c4a8091bf4a682eb7e939830ba87e88327ff8c0c80a80f19e27843","SandboxKey":"/var/run/docker/netns/e989239ca4c4",""" +
            """"Ports":{},"Networks":{"bridge":{"IPAMConfig":null,"Links":null,"Aliases":null,"DriverOpts":null,"GwPriority":0,"NetworkID":"64d3a304e07732e9934cf31e994e28051b8311bd827019c939a65f58cf9f6196",""" +
            """"EndpointID":"6fb74db4f14644a74b6da00355c27ff40116b95a975fa9fb40711408b2eb297b","Gateway":"172.17.0.1","IPAddress":"172.17.0.3","MacAddress":"fe:4b:2b:87:b4:62",""" +
            """"IPPrefixLen":16,"IPv6Gateway":"","GlobalIPv6Address":"","GlobalIPv6PrefixLen":0,"DNSNames":null}}},"ImageManifestDescriptor":{"mediaType":"application/vnd.oci.image.manifest.v1+json",""" +
            """"digest":"sha256:0f49f331d9212aff11b932863be4eb01cdf962969aa86dab7d1ee21ac6f5f52b","size":1022,"annotations":{"com.docker.official-images.bashbrew.arch":"amd64",""" +
            """"org.opencontainers.image.base.name":"scratch","org.opencontainers.image.created":"2026-11-05T18:00:01Z","org.opencontainers.image.revision":"0c9af76133a2dd9a31dedf3600e423087b29a879",""" +
            """"org.opencontainers.image.source":"https://github.com/alpinelinux/docker-alpine.git#0c9af76133a2dd9a31dedf3600e423087b29a879:x86_64","org.opencontainers.image.url":"https://hub.docker.com/_/alpine",""" +
            """"org.opencontainers.image.version":"3.24.4"},"platform":{"architecture":"amd64","os":"linux"}}}"""
        const val INSPECT_ANON_PODMAN_5 = """{"Id":"e70a7382bcc51fd5291c9e03bf50832efbf4e00eba2a9a7eb318e5af7e6a0b87","Created":"2026-11-17T18:41:20.694710614Z","Path":"sleep","Args":["3600"],""" +
            """"State":{"Status":"running","Running":true,"Paused":false,"Restarting":false,"OOMKilled":false,"Dead":false,"Pid":10432,"ExitCode":0,"Error":"",""" +
            """"StartedAt":"2026-11-17T18:41:21.243431096Z","FinishedAt":"0001-01-01T00:00:00Z","Health":{"Status":"healthy","FailingStreak":0,"Log":[{"Start":"2026-11-17T18:41:21.32139242Z",""" +
            """"End":"2026-11-17T18:41:21.374836906Z","ExitCode":0,"Output":""}]}},"Image":"sha256:3ea06e4cb95e726ea6c6cc4377570ac4c4050d78710004cbd631ca809daf7e8e",""" +
            """"ResolvConfPath":"/run/containers/storage/overlay-containers/e70a7382bcc51fd5291c9e03bf50832efbf4e00eba2a9a7eb318e5af7e6a0b87/userdata/resolv.conf",""" +
            """"HostnamePath":"/run/containers/storage/overlay-containers/e70a7382bcc51fd5291c9e03bf50832efbf4e00eba2a9a7eb318e5af7e6a0b87/userdata/hostname",""" +
            """"HostsPath":"/run/containers/storage/overlay-containers/e70a7382bcc51fd5291c9e03bf50832efbf4e00eba2a9a7eb318e5af7e6a0b87/userdata/hosts","LogPath":"",""" +
            """"Name":"/nh-anon","RestartCount":0,"Driver":"overlay","Platform":"linux","MountLabel":"","ProcessLabel":"","AppArmorProfile":"","ExecIDs":[],""" +
            """"HostConfig":{"Binds":["450f44473c4dc838651a49212ac4b3cc056a3995b08bde5a14315a189373f413:/data:rprivate,nosuid,nodev,rbind"],"ContainerIDFile":"",""" +
            """"LogConfig":{"Type":"journald","Config":null},"NetworkMode":"bridge","PortBindings":{},"RestartPolicy":{"Name":"no","MaximumRetryCount":0},"AutoRemove":false,""" +
            """"VolumeDriver":"","VolumesFrom":null,"ConsoleSize":[0,0],"Annotations":{"io.container.manager":"libpod","org.opencontainers.image.stopSignal":"15",""" +
            """"org.systemd.property.KillSignal":"15","org.systemd.property.TimeoutStopUSec":"uint64 10000000"},"CapAdd":[],"CapDrop":[],"CgroupnsMode":"private",""" +
            """"Dns":[],"DnsOptions":[],"DnsSearch":[],"ExtraHosts":[],"GroupAdd":[],"IpcMode":"shareable","Cgroup":"","Links":null,"OomScoreAdj":0,"PidMode":"private",""" +
            """"Privileged":false,"PublishAllPorts":false,"ReadonlyRootfs":false,"SecurityOpt":[],"Tmpfs":{"/run":"size=64m,rprivate,nosuid,nodev,tmpcopyup"},""" +
            """"UTSMode":"private","UsernsMode":"","ShmSize":65536000,"Runtime":"oci","Isolation":"","CpuShares":0,"Memory":0,"NanoCpus":0,"CgroupParent":"",""" +
            """"BlkioWeight":0,"BlkioWeightDevice":null,"BlkioDeviceReadBps":null,"BlkioDeviceWriteBps":null,"BlkioDeviceReadIOps":null,"BlkioDeviceWriteIOps":null,""" +
            """"CpuPeriod":0,"CpuQuota":0,"CpuRealtimePeriod":0,"CpuRealtimeRuntime":0,"CpusetCpus":"","CpusetMems":"","Devices":[],"DeviceCgroupRules":null,""" +
            """"DeviceRequests":null,"MemoryReservation":0,"MemorySwap":0,"MemorySwappiness":0,"OomKillDisable":false,"PidsLimit":2048,"Ulimits":[{"Name":"RLIMIT_NOFILE",""" +
            """"Hard":524288,"Soft":524288},{"Name":"RLIMIT_NPROC","Hard":772879,"Soft":772879}],"CpuCount":0,"CpuPercent":0,"IOMaximumIOps":0,"IOMaximumBandwidth":0,""" +
            """"MaskedPaths":null,"ReadonlyPaths":null},"GraphDriver":{"Data":{"LowerDir":"/var/lib/containers/storage/overlay/75f1d9657d880da5f75298e8f40e9928bb6e260c50d26f97af196845307e3064/diff",""" +
            """"MergedDir":"/var/lib/containers/storage/overlay/c3373b1497d13ae2f98643fc0c2d6782b70ce6adff2c7bc360242663338c1dea/merged","UpperDir":"/var/lib/containers/storage/overlay/c3373b1497d13ae2f98643fc0c2d6782b70ce6adff2c7bc360242663338c1dea/diff",""" +
            """"WorkDir":"/var/lib/containers/storage/overlay/c3373b1497d13ae2f98643fc0c2d6782b70ce6adff2c7bc360242663338c1dea/work"},"Name":"overlay"},"SizeRootFs":0,""" +
            """"Mounts":[{"Type":"volume","Name":"450f44473c4dc838651a49212ac4b3cc056a3995b08bde5a14315a189373f413","Source":"/var/lib/containers/storage/volumes/450f44473c4dc838651a49212ac4b3cc056a3995b08bde5a14315a189373f413/_data",""" +
            """"Destination":"/data","Driver":"local","Mode":"","RW":true,"Propagation":"rprivate"}],"Config":{"Hostname":"e70a7382bcc5","Domainname":"","User":"",""" +
            """"AttachStdin":false,"AttachStdout":false,"AttachStderr":false,"Tty":false,"OpenStdin":false,"StdinOnce":false,"Env":["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",""" +
            """"container=podman","HOME=/root","HOSTNAME=e70a7382bcc5"],"Cmd":["sleep","3600"],"Healthcheck":{"Test":["CMD-SHELL","true"],"Interval":30000000000,""" +
            """"Timeout":30000000000,"Retries":3},"Image":"docker.io/library/alpine:latest","Volumes":null,"WorkingDir":"/","Entrypoint":[],"OnBuild":null,""" +
            """"Labels":{},"StopSignal":"15","StopTimeout":10},"NetworkSettings":{"Bridge":"","SandboxID":"","SandboxKey":"/run/user/0/netns/netns-f9ba456d-12e5-e7c5-6c60-c0fd51276dd3",""" +
            """"Ports":{},"HairpinMode":false,"LinkLocalIPv6Address":"","LinkLocalIPv6PrefixLen":0,"SecondaryIPAddresses":null,"SecondaryIPv6Addresses":null,""" +
            """"EndpointID":"","Gateway":"10.88.0.1","GlobalIPv6Address":"","GlobalIPv6PrefixLen":0,"IPAddress":"10.88.0.5","IPPrefixLen":16,"IPv6Gateway":"",""" +
            """"MacAddress":"de:f3:0e:68:cf:bf","Networks":{"podman":{"IPAMConfig":null,"Links":null,"Aliases":["e70a7382bcc5"],"MacAddress":"de:f3:0e:68:cf:bf",""" +
            """"DriverOpts":null,"GwPriority":0,"NetworkID":"2f259bab93aaaaa2542ba43ef33eb990d0999ee1b9924b557b7be53c0b7a1bb9","EndpointID":"","Gateway":"10.88.0.1",""" +
            """"IPAddress":"10.88.0.5","IPPrefixLen":16,"IPv6Gateway":"","GlobalIPv6Address":"","GlobalIPv6PrefixLen":0,"DNSNames":null}}}}"""
        val LS_BUSYBOX_29 = listOf(
            """total 156""",
            """drwxr-xr-x    1 root     root          4096 Nov 17 17:21 .""",
            """drwxr-xr-x    1 root     root          4096 Nov 17 17:22 ..""",
            """-rw-r--r--    1 root     root             7 Nov  5 09:17 alpine-release""",
            """drwxr-xr-x    4 root     root          4096 Nov  5 09:19 apk""",
            """drwxr-xr-x    2 root     root          4096 Nov  5 09:19 busybox-paths.d""",
            """drwxr-xr-x    2 root     root          4096 Nov  5 09:19 crontabs""",
            """-rw-r--r--    1 root     root            89 Mar 24  2026 fstab""",
            """-rw-r--r--    1 root     root           510 Mar 24  2026 group""",
            """-rw-r--r--    1 root     root            13 Nov 17 17:21 hostname""",
            """-rw-r--r--    1 root     root           172 Nov 17 17:21 hosts""",
            """-rw-r--r--    1 root     root           570 Mar 24  2026 inittab""",
            """-rw-r--r--    1 root     root            51 Nov  5 09:17 issue""",
            """drwxr-xr-x    2 root     root          4096 Nov  5 09:19 logrotate.d""",
            """drwxr-xr-x    2 root     root          4096 Nov  5 09:19 modprobe.d""",
            """-rw-r--r--    1 root     root            15 Mar 24  2026 modules""",
            """drwxr-xr-x    2 root     root          4096 Nov  5 09:19 modules-load.d""",
            """-rw-r--r--    1 root     root           284 Mar 24  2026 motd""",
            """lrwxrwxrwx    1 root     root            12 Nov 17 17:21 mtab -> /proc/mounts""",
            """drwxr-xr-x    8 root     root          4096 Nov  5 09:19 network""",
            """-rw-r--r--    1 root     root           205 Mar 24  2026 nsswitch.conf""",
            """drwxr-xr-x    2 root     root          4096 Nov  5 09:19 opt""",
            """lrwxrwxrwx    1 root     root            21 Nov  5 09:19 os-release -> ../usr/lib/os-release""",
            """-rw-r--r--    1 root     root           702 Mar 24  2026 passwd""",
            """drwxr-xr-x    7 root     root          4096 Nov  5 09:19 periodic""",
        )
        val LS_GNU = listOf(
            """total 224""",
            """drwxr-xr-x 1 root root   4096 Nov 17 18:41 .""",
            """drwxr-xr-x 1 root root   4096 Nov 17 18:41 ..""",
            """-rw------- 1 root root      0 Nov  4 21:23 .pwd.lock""",
            """drwxr-xr-x 2 root root   4096 Nov  4 21:23 alternatives""",
            """drwxr-xr-x 8 root root   4096 Nov  4 21:23 apt""",
            """-rw-r--r-- 1 root root   1997 Jul 12 16:58 bash.bashrc""",
            """-rw-r--r-- 1 root root    367 Jun 29 21:37 bindresvport.blacklist""",
            """drwxr-xr-x 2 root root   4096 Nov  4 21:23 cron.daily""",
            """-rw-r--r-- 1 root root   2967 Mar 10  2025 debconf.conf""",
            """-rw-r--r-- 1 root root      5 Jul 17 17:05 debian_version""",
            """drwxr-xr-x 2 root root   4096 Nov  4 21:23 default""",
            """drwxr-xr-x 4 root root   4096 Nov  4 21:23 dpkg""",
            """-rw-r--r-- 1 root root      0 Nov  4 21:23 environment""",
            """-rw-r--r-- 1 root root     37 Nov  4 21:23 fstab""",
            """-rw-r--r-- 1 root root   2584 Jan 28  2025 gai.conf""",
            """-rw-r--r-- 1 root root    434 Nov  4 21:23 group""",
            """-rw-r--r-- 1 root root    434 Nov  4 21:23 group-""",
            """-rw-r----- 1 root shadow  364 Nov  4 21:23 gshadow""",
            """-rw-r--r-- 1 root root      9 Jul 17 17:05 host.conf""",
            """-rw-r--r-- 1 root root     13 Nov 17 18:41 hostname""",
            """-rw-r--r-- 1 root root    172 Nov 17 18:41 hosts""",
            """-rw-r--r-- 1 root root     27 Jul 17 17:05 issue""",
            """-rw-r--r-- 1 root root     20 Jul 17 17:05 issue.net""",
            """drwxr-xr-x 3 root root   4096 Jun 24  2025 kernel""",
        )
        const val ARCHIVE_PODMAN_C3 ="aG9zdG5hbWUAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAADAwMDA2NDQAMDAwMDAwMAAwMDAwMDAwADAwMDAwMDAwMDEyADE1MTYwNTY0MzA2ADAxMTAwNAAgMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB1c3RhcgAwMAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwMDAwMDAwADAwMDAwMDAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABsb2NhbGhvc3QKAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}

// the containers of the lab Docker 29.9.3, which the tests of images, volumes and networks count as well
internal const val LIST_29 = """[{"Id":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a","Names":["/nh-sample-tty"],"Image":"alpine:latest","ImageID":"sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf",""" +
    """"ImageManifestDescriptor":{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"sha256:0f49f331d9212aff11b932863be4eb01cdf962969aa86dab7d1ee21ac6f5f52b",""" +
    """"size":1022,"annotations":{"com.docker.official-images.bashbrew.arch":"amd64","org.opencontainers.image.base.name":"scratch","org.opencontainers.image.created":"2026-11-05T18:00:01Z",""" +
    """"org.opencontainers.image.revision":"0c9af76133a2dd9a31dedf3600e423087b29a879","org.opencontainers.image.source":"https://github.com/alpinelinux/docker-alpine.git#0c9af76133a2dd9a31dedf3600e423087b29a879:x86_64",""" +
    """"org.opencontainers.image.url":"https://hub.docker.com/_/alpine","org.opencontainers.image.version":"3.24.4"},"platform":{"architecture":"amd64",""" +
    """"os":"linux"}},"Command":"sh -c 'while true; do echo tty $(date +%s); sleep 1; done'","Created":1794936065,"Ports":[],"Labels":{},"State":"running",""" +
    """"Status":"Up 8 seconds","HostConfig":{"NetworkMode":"bridge"},"Health":{"Status":"none","FailingStreak":0},"NetworkSettings":{"Networks":{"bridge":{"IPAMConfig":null,""" +
    """"Links":null,"Aliases":null,"DriverOpts":null,"GwPriority":0,"NetworkID":"64d3a304e07732e9934cf31e994e28051b8311bd827019c939a65f58cf9f6196",""" +
    """"EndpointID":"c2808a11833bce93e4ff3f8a9d93905886dc66120775458a1395a88382a77f75","Gateway":"172.17.0.1","IPAddress":"172.17.0.2","MacAddress":"26:c7:5f:64:ac:f5",""" +
    """"IPPrefixLen":16,"IPv6Gateway":"","GlobalIPv6Address":"","GlobalIPv6PrefixLen":0,"DNSNames":null}}},"Mounts":[]},{"Id":"87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d",""" +
    """"Names":["/nh-sample-web"],"Image":"alpine:latest","ImageID":"sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf","ImageManifestDescriptor":{"mediaType":"application/vnd.oci.image.manifest.v1+json",""" +
    """"digest":"sha256:0f49f331d9212aff11b932863be4eb01cdf962969aa86dab7d1ee21ac6f5f52b","size":1022,"annotations":{"com.docker.official-images.bashbrew.arch":"amd64",""" +
    """"org.opencontainers.image.base.name":"scratch","org.opencontainers.image.created":"2026-11-05T18:00:01Z","org.opencontainers.image.revision":"0c9af76133a2dd9a31dedf3600e423087b29a879",""" +
    """"org.opencontainers.image.source":"https://github.com/alpinelinux/docker-alpine.git#0c9af76133a2dd9a31dedf3600e423087b29a879:x86_64","org.opencontainers.image.url":"https://hub.docker.com/_/alpine",""" +
    """"org.opencontainers.image.version":"3.24.4"},"platform":{"architecture":"amd64","os":"linux"}},"Command":"sh -c 'while true; do echo out $(date +%s); echo err >&2; sleep 1; done'",""" +
    """"Created":1794936064,"Ports":[{"IP":"0.0.0.0","PrivatePort":80,"PublicPort":18080,"Type":"tcp"},{"IP":"::","PrivatePort":80,"PublicPort":18080,""" +
    """"Type":"tcp"}],"Labels":{"nh.sample":"1"},"State":"running","Status":"Up 9 seconds","HostConfig":{"NetworkMode":"nh-sample-net"},"Health":{"Status":"none",""" +
    """"FailingStreak":0},"NetworkSettings":{"Networks":{"nh-sample-net":{"IPAMConfig":null,"Links":null,"Aliases":null,"DriverOpts":null,"GwPriority":0,""" +
    """"NetworkID":"2ad1893d6f4bcfd3e5ad97f97591af44ddc5c6fa845e7f3e9fcba8879d1fe978","EndpointID":"044878b019dc1abd9be1bd312d8223807bfd136558d3e2cb685c66c2385a1ace",""" +
    """"Gateway":"172.30.9.1","IPAddress":"172.30.9.2","MacAddress":"1e:19:12:be:79:c6","IPPrefixLen":24,"IPv6Gateway":"","GlobalIPv6Address":"","GlobalIPv6PrefixLen":0,""" +
    """"DNSNames":null}}},"Mounts":[{"Type":"volume","Name":"nh-sample-vol","Source":"/var/lib/docker/volumes/nh-sample-vol/_data","Destination":"/data",""" +
    """"Driver":"local","Mode":"z","RW":true,"Propagation":""}]}]"""
