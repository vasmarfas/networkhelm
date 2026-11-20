package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinuxDockerImagesTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private fun host(vararg answers: Pair<String, ByteArray>, commands: List<Pair<String, ExecResult>> = emptyList(), tool: String = "docker") =
        DockerHost(listOf("$tool system dial-stdio </dev/null" to ok(), "date +%z" to ok("+0300\n")) + commands) { DockerEngine(listOf("GET /version " to engineAnswer(VERSION)) + answers) }

    private fun images(host: DockerHost, tool: String = "docker", password: String? = null) = DockerImages(DockerApi(host, Root(host, password)) { setOf(tool) }).table { true }

    private fun requests(host: DockerHost) = host.relays.flatMap { it.second.requests }

    @Test
    fun aRowATagWithTheContainersThatUseItsImage() = runTest {
        val docker = images(host("GET /v1.56/containers/json?all=1 " to engineChunked(LIST_29), "GET /v1.56/images/json " to engineAnswer(IMAGES_29))).read()
        assertEquals(listOf("alpine:latest", "hello-world:latest"), docker.map { it.id })
        assertEquals(
            listOf("alpine:latest", "alpine", "latest", "f59abe656b69", "2026-11-05 21:00:28", "13030721", "2", "sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf"),
            listOf("name", "repository", "tag", "image-id", "created", "size", "containers", "digest").map { docker[0][it] },
        )
        assertEquals("0", docker[1]["containers"])
        val podman = images(host("GET /v1.56/containers/json?all=1 " to engineAnswer(LIST_PODMAN_5), "GET /v1.56/images/json " to engineAnswer(IMAGES_PODMAN_5), tool = "podman"), "podman").read()
        assertEquals(
            listOf("docker.io/library/alpine:latest", "docker.io/library/alpine", "latest", "3ea06e4cb95e", "8725601", "2"),
            listOf("name", "repository", "tag", "image-id", "size", "containers").map { podman.single()[it] },
        )
        // an image without a tag is named by its short ID, which the engine takes for the image
        val untagged = """{"Id":"sha256:1e4c05b897a751332ce85c7e9449da2d93514ea2dea6dbb0e2753c9e949b2bfa","RepoTags":[],"Created":1774301639,"Size":25874}"""
        val dangling = imageRows(listOf(Json.parseToJsonElement(untagged).jsonObject), emptyMap(), "+0000").single()
        assertEquals(listOf("sha256:1e4c05b897a751332ce85c7e9449da2d93514ea2dea6dbb0e2753c9e949b2bfa", "1e4c05b897a7", ""), listOf(dangling.id, dangling["name"], dangling["tag"]))
    }

    @Test
    fun aTimeTheEngineDoesNotKnowStaysEmpty() {
        // ubuntu:latest on docker.io 26.1.3 of Ubuntu 22.04 came with 0, shown as 1970; an image without a date gives the zero time of Go
        fun created(value: String) =
            imageRows(listOf(Json.parseToJsonElement("""{"Id":"sha256:1e4c05b897a7","RepoTags":["ubuntu:latest"],"Created":$value}""").jsonObject), emptyMap(), "+0300").single()["created"]
        assertEquals(
            listOf("", "", "2026-03-24 00:33:59", "2026-11-17 20:20:32"),
            listOf(created("0"), created("-62135596800"), created("1774301639"), created("\"2026-11-17T17:20:32.123456789Z\"")),
        )
        val layer = layers(Json.parseToJsonElement("""[{"Id":"<missing>","Created":0,"CreatedBy":"","Size":0,"Tags":null,"Comment":""}]"""), "+0300").single()
        assertEquals("", layer["created"])
    }

    @Test
    fun pruneTakesImagesNoContainerUsesOnlyThoseWithoutATagUnlessAll() = runTest {
        val unused = assertNotNull(images(host("GET /v1.56/containers/json?all=1 " to engineChunked(LIST_29), "GET /v1.56/images/json " to engineAnswer(IMAGES_29))).run)
        assertEquals(emptyList(), unused("unused", emptyMap()).map { it.id })
        assertEquals(listOf("hello-world:latest"), unused("unused", mapOf("all" to "true")).map { it.id })
    }

    @Test
    fun aPullGoesLayerByLayerWithTheLoginInItsHeaderAlone() = runTest {
        val host = host("POST /v1.56/images/create?" to engineStream(PULL_29, "\r\n"))
        val steps = assertNotNull(images(host).streams["pull"])(mapOf("image" to "registry.example.com:5000/nh/busybox:1.37", "username" to "nh", "password" to SECRET)).toList()
        assertEquals(10, steps.size)
        assertEquals(listOf("37bb94b0940b", "Downloading", "1048576", "2226163"), listOf("id", "status", "current", "total").map { steps[3][it] })
        assertEquals("1" to "s", steps[6]["current"] to steps[6]["units"])
        assertEquals("Status: Downloaded newer image for busybox:latest", steps.last()["status"])
        val request = requests(host).single { it.startsWith("POST ") }
        assertEquals("POST /v1.56/images/create?fromImage=registry.example.com%3A5000%2Fnh%2Fbusybox&tag=1.37 HTTP/1.1", request.substringBefore("\r\n"))
        val auth = request.split("\r\n").single { it.startsWith("X-Registry-Auth: ") }.substringAfter(": ")
        assertEquals(
            Json.parseToJsonElement("""{"username":"nh","password":"$SECRET","serveraddress":"registry.example.com:5000"}"""),
            Json.parseToJsonElement(Base64.UrlSafe.decode(auth).decodeToString()),
        )
        assertFalse(SECRET in request)
        // Podman ends its lines with a bare line feed, and a pull without a login carries no header for it
        val podman = host("POST /v1.56/images/create?" to engineStream(PULL_PODMAN_5, "\n"), tool = "podman")
        val layers = assertNotNull(images(podman, "podman").streams["pull"])(mapOf("image" to "busybox")).toList()
        assertEquals(listOf("Pulling fs layer", "Download complete"), layers.take(2).map { it["status"] })
        assertTrue(requests(podman).none { "X-Registry-Auth" in it })
        assertTrue(requests(podman).any { it.startsWith("POST /v1.56/images/create?fromImage=busybox&tag=latest ") })
    }

    @Test
    fun aPullThatFailsSaysWhatTheEngineSaidAndNeverTheLogin() = runTest {
        for ((answer, said) in listOf(
            engineAnswer(MISSING_TAG_29, "404 Not Found") to "failed to resolve reference \"docker.io/library/alpine:nh-missing\": docker.io/library/alpine:nh-missing: not found",
            "HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n31\r\n$MISSING_TAG_PODMAN_5\n\r\n0\r\n\r\n".encodeToByteArray() to "manifest unknown: manifest unknown",
        )) {
            val host = host("POST /v1.56/images/create?" to answer)
            val failed = assertFailsWith<DeviceException> { assertNotNull(images(host).streams["pull"])(mapOf("image" to "alpine:nh-missing", "username" to "nh", "password" to SECRET)).toList() }
            assertEquals(ErrorCategory.MissingItem to said, failed.category to failed.message)
        }
        val refused = """{"errorDetail":{"message":"unauthorized: incorrect username or password"},"error":"unauthorized: incorrect username or password"}"""
        val host = host("POST /v1.56/images/create?" to engineStream(listOf("""{"status":"Pulling from nh/busybox","id":"1.37"}""", refused), "\r\n"))
        val failed = assertFailsWith<DeviceException> { assertNotNull(images(host).streams["pull"])(mapOf("image" to "registry.example.com/nh/busybox:1.37", "username" to "nh", "password" to SECRET)).toList() }
        assertEquals("unauthorized: incorrect username or password", failed.message)
        assertFalse(SECRET in failed.stackTraceToString())
    }

    @Test
    fun aPushNamesTheRepositoryWithItsTagApartAndAlwaysCarriesALogin() = runTest {
        val host = host("POST /v1.56/images/" to engineStream(listOf("""{"status":"The push refers to repository [registry.example.com:5000/nh/app]"}"""), "\r\n"))
        assertNotNull(images(host).streams["push"])(mapOf("image" to "registry.example.com:5000/nh/app:1.0")).toList()
        val request = requests(host).single { it.startsWith("POST ") }
        assertEquals("POST /v1.56/images/registry.example.com%3A5000%2Fnh%2Fapp/push?tag=1.0 HTTP/1.1", request.substringBefore("\r\n"))
        assertTrue("X-Registry-Auth: e30=\r\n" in request)
    }

    @Test
    fun aTagIsANewNameAndAnImageGoneAlreadyIsNoFailureOfItsRemoval() = runTest {
        val conflict = """{"message":"conflict: unable to remove repository reference \"alpine:latest\" (must force) - container 87deced11966 is using its referenced image f59abe656b69"}"""
        val host = host(
            "POST /v1.56/images/" to engineAnswer("", "201 Created"),
            "DELETE /v1.56/images/hello-world%3Alatest " to engineAnswer("""{"message":"No such image: hello-world:latest"}""", "404 Not Found"),
            "DELETE /v1.56/images/alpine%3Alatest?force=1 " to engineAnswer("""[{"Untagged":"alpine:latest"}]"""),
            "DELETE /v1.56/images/" to engineAnswer(conflict, "409 Conflict"),
        )
        val table = images(host)
        assertNotNull(table.run)("tag", mapOf("id" to "alpine:latest", "reference" to "registry.example.com:5000/nh/alpine:3.24"))
        assertTrue(requests(host).any { it.startsWith("POST /v1.56/images/alpine%3Alatest/tag?repo=registry.example.com%3A5000%2Fnh%2Falpine&tag=3.24 ") })
        assertNotNull(table.remove)(listOf("hello-world:latest"))
        val refused = assertFailsWith<DeviceException> { assertNotNull(table.remove)(listOf("alpine:latest")) }
        assertEquals(ErrorCategory.ArgumentValue, refused.category)
        assertNotNull(table.run)("remove-forced", mapOf("numbers" to "alpine:latest"))
        assertTrue(requests(host).any { it.startsWith("DELETE /v1.56/images/alpine%3Alatest?force=1 ") })
    }

    @Test
    fun anImageIsSavedToAFileOfTheHostWhichTheLoginGetsAndReadBackFromOne() = runTest {
        val written = $$"""&& { [ -z "$SUDO_UID" ] || chown "$SUDO_UID:$SUDO_GID" 'alpine.tar'; } && readlink -f 'alpine.tar'"""
        val login = host(commands = listOf("docker save " to ok("/home/nh/alpine.tar\n"), "docker load " to ok("Loaded image: alpine:latest\n")))
        val table = images(login)
        assertEquals("/home/nh/alpine.tar", assertNotNull(table.run)("save", mapOf("id" to "alpine:latest", "file" to "alpine.tar")).single()["file"])
        assertEquals("docker save -o 'alpine.tar' 'alpine:latest' $written", login.ran.single { it.startsWith("docker save") })
        assertEquals(listOf("Loaded image: alpine:latest"), assertNotNull(table.run)("load", mapOf("file" to "/home/nh/alpine.tar")).map { it["line"] })
        assertEquals("docker load -i '/home/nh/alpine.tar'", login.ran.last())
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { assertNotNull(table.run)("save", mapOf("id" to "alpine:latest")) }.category)

        // through sudo the CLI runs as root, as the relay does, and the file goes to the login
        val sudo = DockerHost(
            listOf(
                "docker system dial-stdio </dev/null" to ExecResult("", "permission denied while trying to connect to the docker API at unix:///var/run/docker.sock\n", 1),
                "DOCKER_HOST=" to ExecResult("", "no socket\n", 1),
                "id -u" to ok("1000\n"),
                "sudo -n true" to ExecResult("", "sudo: a password is required\n", 1),
                "sudo -S -p '' true" to ok(),
                "sudo -S -p '' sh -c 'docker system dial-stdio </dev/null'" to ok(),
                "sudo -S -p '' sh -c 'docker save " to ok("/home/nh/alpine.tar\n"),
            ),
        ) { error("no relay") }
        assertEquals("/home/nh/alpine.tar", assertNotNull(images(sudo, password = PASSWORD).run)("save", mapOf("id" to "alpine:latest", "file" to "alpine.tar")).single()["file"])
        assertTrue(sudo.ran.last().startsWith("sudo -S -p '' sh -c 'docker save -o "))
        assertTrue(sudo.ran.none { PASSWORD in it })
    }

    @Test
    fun aLoginOnTheHostTakesThePasswordOnItsInputAloneAndTellsWhereTheHostKeepsIt() = runTest {
        val warning = "WARNING! Your credentials are stored unencrypted in '/home/nh/.docker/config.json'."
        val host = host(commands = listOf("docker login " to ExecResult("Login Succeeded\n", "$warning\n", 0)))
        val said = assertNotNull(images(host).run)("login", mapOf("server" to "registry.example.com:5000", "username" to "nh", "password" to SECRET))
        assertEquals(listOf("Login Succeeded", warning), said.map { it["line"] })
        assertEquals("docker login --username 'nh' --password-stdin 'registry.example.com:5000'" to SECRET, host.given.single())
        assertTrue(host.ran.none { SECRET in it })
    }

    @Test
    fun aReferenceIsARepositoryATagAndARegistry() {
        assertEquals("registry.local:5000/app" to "1.2", repositoryAndTag("registry.local:5000/app:1.2"))
        assertEquals("registry.local:5000/app" to "", repositoryAndTag("registry.local:5000/app"))
        assertEquals("alpine" to "", repositoryAndTag("alpine@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf"))
        assertEquals(
            listOf("registry.local:5000", "ghcr.io", "localhost", "https://index.docker.io/v1/", "https://index.docker.io/v1/"),
            listOf("registry.local:5000/app", "ghcr.io/nh/app:1", "localhost/app", "nh/app", "alpine").map(::registryOf),
        )
        assertEquals("e30=", registryAuth("", SECRET, "ghcr.io"))
    }

    private companion object {
        const val VERSION = """{"ApiVersion":"1.56","MinAPIVersion":"1.40"}"""
        const val SECRET = "nh-registry-secret"
        const val PASSWORD = "nh-sudo-secret"

        // recorded on Docker 29.9.3 (API 1.56) on Arch and on Podman 5.8.9 (compatible API 1.44) on Fedora 44
        const val IMAGES_29 = """[{"Containers":2,"Created":1793901628,"Id":"sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf","Labels":{},"ParentId":"",""" +
            """"Descriptor":{"mediaType":"application/vnd.oci.image.index.v1+json","digest":"sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf",""" +
            """"size":9218},"RepoDigests":["alpine@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf"],"RepoTags":["alpine:latest"],"SharedSize":-1,""" +
            """"Size":13030721},{"Containers":0,"Created":1774301639,"Id":"sha256:1e4c05b897a751332ce85c7e9449da2d93514ea2dea6dbb0e2753c9e949b2bfa","Labels":{},""" +
            """"ParentId":"","Descriptor":{"mediaType":"application/vnd.oci.image.index.v1+json","digest":"sha256:1e4c05b897a751332ce85c7e9449da2d93514ea2dea6dbb0e2753c9e949b2bfa",""" +
            """"size":11062},"RepoDigests":["hello-world@sha256:1e4c05b897a751332ce85c7e9449da2d93514ea2dea6dbb0e2753c9e949b2bfa"],"RepoTags":["hello-world:latest"],""" +
            """"SharedSize":-1,"Size":25874}]"""
        const val IMAGES_PODMAN_5 = """[{"Id":"sha256:3ea06e4cb95e726ea6c6cc4377570ac4c4050d78710004cbd631ca809daf7e8e","ParentId":"","RepoTags":["docker.io/library/alpine:latest"],""" +
            """"RepoDigests":["docker.io/library/alpine@sha256:0f49f331d9212aff11b932863be4eb01cdf962969aa86dab7d1ee21ac6f5f52b","docker.io/library/alpine@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf"],""" +
            """"Created":1793901628,"Size":8725601,"SharedSize":-1,"Labels":null,"Containers":2,"Digest":"sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf",""" +
            """"History":["docker.io/library/alpine:latest"],"Names":["docker.io/library/alpine:latest"]}]"""
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
        val PULL_PODMAN_5 = listOf(
            """{"status":"Pulling fs layer","progressDetail":{},"id":"37bb94b0940b"}""",
            """{"status":"Download complete","progressDetail":{},"id":"37bb94b0940b"}""",
            """{"status":"Pulling fs layer","progressDetail":{},"id":"aaef90e06523"}""",
            """{"status":"Download complete","progressDetail":{},"id":"aaef90e06523"}""",
            """{"status":"Download complete","progressDetail":{},"id":"aaef90e06523"}""",
        )
        const val MISSING_TAG_29 = """{"message":"failed to resolve reference \"docker.io/library/alpine:nh-missing\": docker.io/library/alpine:nh-missing: not found"}"""
        const val MISSING_TAG_PODMAN_5 = """{"message":"manifest unknown: manifest unknown"}"""
    }
}

// the containers of the lab Podman 5.8.9, which the test of networks counts as well
internal const val LIST_PODMAN_5 = """[{"Id":"909aa0962d0d3ef68656655b582ec3dbef14ad7986f98ad6262c66ca44dfcc72","Names":["/nh-sample-web"],"Image":"docker.io/library/alpine:latest",""" +
    """"ImageID":"sha256:3ea06e4cb95e726ea6c6cc4377570ac4c4050d78710004cbd631ca809daf7e8e","Command":"sh -c while true; do echo out $(date +%s); sleep 1; done",""" +
    """"Created":1794936665,"Ports":[{"IP":"0.0.0.0","PrivatePort":80,"PublicPort":18080,"Type":"tcp"}],"Labels":{},"State":"running","Status":"Up 8 seconds",""" +
    """"NetworkSettings":{"Networks":{"podman":{"IPAMConfig":null,"Links":null,"Aliases":["909aa0962d0d"],"MacAddress":"8e:cb:73:74:69:57","DriverOpts":null,""" +
    """"GwPriority":0,"NetworkID":"2f259bab93aaaaa2542ba43ef33eb990d0999ee1b9924b557b7be53c0b7a1bb9","EndpointID":"","Gateway":"10.88.0.1","IPAddress":"10.88.0.2",""" +
    """"IPPrefixLen":16,"IPv6Gateway":"","GlobalIPv6Address":"","GlobalIPv6PrefixLen":0,"DNSNames":null}}},"Mounts":[],"Name":"","Config":null,"NetworkingConfig":null,""" +
    """"Platform":null,"DefaultReadOnlyNonRecursive":false},{"Id":"e80c6352d5d68ebbdcd9892063ce609141c9c56a2d5b94d5fb1c93a366f15eca","Names":["/88edab2681f2-infra"],""" +
    """"Image":"","ImageID":"sha256:","Command":"","Created":1794936666,"Ports":[],"Labels":{},"State":"running","Status":"Up 7 seconds","NetworkSettings":{"Networks":{"podman":{"IPAMConfig":null,""" +
    """"Links":null,"Aliases":["e80c6352d5d6","nh-sample-pod"],"MacAddress":"7a:5e:72:bf:7e:48","DriverOpts":null,"GwPriority":0,"NetworkID":"2f259bab93aaaaa2542ba43ef33eb990d0999ee1b9924b557b7be53c0b7a1bb9",""" +
    """"EndpointID":"","Gateway":"10.88.0.1","IPAddress":"10.88.0.3","IPPrefixLen":16,"IPv6Gateway":"","GlobalIPv6Address":"","GlobalIPv6PrefixLen":0,""" +
    """"DNSNames":null}}},"Mounts":[],"Name":"","Config":null,"NetworkingConfig":null,"Platform":null,"DefaultReadOnlyNonRecursive":false},{"Id":"0c2069deda3ccb333bcccc89882db7a5ccc85cd9c6f9bff736ad37e0807973a2",""" +
    """"Names":["/nh-sample-podded"],"Image":"docker.io/library/alpine:latest","ImageID":"sha256:3ea06e4cb95e726ea6c6cc4377570ac4c4050d78710004cbd631ca809daf7e8e",""" +
    """"Command":"sleep 3600","Created":1794936666,"Ports":[],"Labels":{},"State":"running","Status":"Up 7 seconds","NetworkSettings":{"Networks":{"podman":{"IPAMConfig":null,""" +
    """"Links":null,"Aliases":["e80c6352d5d6","nh-sample-pod"],"MacAddress":"7a:5e:72:bf:7e:48","DriverOpts":null,"GwPriority":0,"NetworkID":"2f259bab93aaaaa2542ba43ef33eb990d0999ee1b9924b557b7be53c0b7a1bb9",""" +
    """"EndpointID":"","Gateway":"10.88.0.1","IPAddress":"10.88.0.3","IPPrefixLen":16,"IPv6Gateway":"","GlobalIPv6Address":"","GlobalIPv6PrefixLen":0,""" +
    """"DNSNames":null}}},"Mounts":[],"Name":"","Config":null,"NetworkingConfig":null,"Platform":null,"DefaultReadOnlyNonRecursive":false}]"""
