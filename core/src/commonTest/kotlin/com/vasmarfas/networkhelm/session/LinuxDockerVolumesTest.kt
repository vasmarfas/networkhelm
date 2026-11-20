package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.transport.urlEncoded
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LinuxDockerVolumesTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private fun host(vararg answers: Pair<String, ByteArray>) =
        DockerHost(listOf("docker system dial-stdio </dev/null" to ok(), "date +%z" to ok("+0300\n"))) { DockerEngine(listOf("GET /version " to engineAnswer(VERSION)) + answers) }

    private fun volumes(host: DockerHost): HostTable {
        val api = DockerApi(host, Root(host, null)) { setOf("docker") }
        return DockerVolumes(api, DockerContainers(api)::create).table { true }
    }

    private fun lines(host: DockerHost) = host.relays.flatMap { it.second.requests }.map { it.substringBefore("\r\n").removeSuffix(" HTTP/1.1") }

    private fun body(host: DockerHost, line: String) =
        Json.parseToJsonElement(host.relays.flatMap { it.second.requests }.last { it.startsWith("$line ") }.substringAfter("\r\n\r\n")).jsonObject

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun aVolumeComesWithTheContainersThatMountIt() = runTest {
        val volume = volumes(host("GET /v1.56/volumes " to engineAnswer(VOLUMES_29), "GET /v1.56/containers/json?all=1 " to engineChunked(LIST_29))).read().single()
        assertEquals(
            listOf("nh-sample-vol", "local", "local", "/var/lib/docker/volumes/nh-sample-vol/_data", "2026-11-17 20:20:59", "1", "false", ""),
            listOf("name", "driver", "scope", "mountpoint", "created", "containers", "anonymous", "labels").map { volume[it] },
        )
    }

    @Test
    fun aVolumeIsMadeAsTheLabMadeItAndOneGoneAlreadyIsNoFailureOfItsRemoval() = runTest {
        for (gone in listOf(VOLUME_GONE_29, VOLUME_GONE_PODMAN_5)) {
            val host = host("POST /v1.56/volumes/create " to engineAnswer(VOLUME_CREATED_29, "201 Created"), "DELETE /v1.56/volumes/nh-c3-vol " to engineAnswer(gone, "404 Not Found"))
            val table = volumes(host)
            assertEquals("nh-c3-vol", assertNotNull(table.add)(mapOf("name" to "nh-c3-vol", "labels" to "nh=c3")))
            assertEquals(json(VOLUME_REQUEST), body(host, "POST /v1.56/volumes/create"))
            assertNotNull(table.remove)(listOf("nh-c3-vol"))
            assertEquals("DELETE /v1.56/volumes/nh-c3-vol", lines(host).last())
        }
    }

    @Test
    fun pruneTakesTheVolumesNoContainerMountsOnlyAnonymousOnesUnlessAll() = runTest {
        // the volume of the lab, mounted, and two more of the shape the engine gives: one it made for a container, one named
        val anonymous = VOLUME_CREATED_29.replace("nh-c3-vol", ANONYMOUS).replace(""""Labels":{"nh":"c3"}""", """"Labels":{"com.docker.volume.anonymous":""}""")
        val listed = VOLUMES_29.replace("""}],"Warnings"""", """},$anonymous,$VOLUME_CREATED_29],"Warnings"""")
        val run = assertNotNull(volumes(host("GET /v1.56/volumes " to engineAnswer(listed), "GET /v1.56/containers/json?all=1 " to engineChunked(LIST_29))).run)
        assertEquals(listOf(ANONYMOUS), run("unused", emptyMap()).map { it.id })
        assertEquals(listOf(ANONYMOUS, "nh-c3-vol"), run("unused", mapOf("all" to "true")).map { it.id })
    }

    @Test
    fun aVolumeIsBrowsedThroughAContainerOfItsOwnAndEveryOneOfThoseGoesWhenDone() = runTest {
        val helper = "e736fc8bafbea47449f6baca64e002291d34ea098d7626d38eb623485bd07380"
        val left = "5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a"
        val other = "87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d"
        // the containers of the label, one of them left by a window that never said it was done, and one more, as an engine that
        // ignored the filter would give it
        val listed = """[{"Id":"$helper","Labels":{"networkhelm.browse":"nh-sample-vol"}},{"Id":"$left","Labels":{"networkhelm.browse":"nh-sample-vol"}},""" +
            """{"Id":"$other","Labels":{"nh.sample":"1"}}]"""
        val host = host(
            "POST /v1.56/containers/create " to engineAnswer("""{"Id":"$helper","Warnings":[]}""", "201 Created"),
            "POST /v1.56/containers/$helper/start " to ENGINE_NO_CONTENT,
            "GET /v1.56/containers/json?all=1&filters=" to engineAnswer(listed),
            "DELETE /v1.56/containers/" to ENGINE_NO_CONTENT,
        )
        val run = assertNotNull(volumes(host).run)
        val browsed = run("browse", mapOf("id" to "nh-sample-vol")).single()
        assertEquals(helper to "/volume", browsed["container"] to browsed["path"])
        assertEquals(
            json(
                """{"Image":"docker.io/library/alpine:latest","Cmd":["sleep","3600"],"Labels":{"networkhelm.browse":"nh-sample-vol"},"StopTimeout":1,""" +
                    """"HostConfig":{"Mounts":[{"Type":"volume","Source":"nh-sample-vol","Target":"/volume"}],"NetworkMode":"none","AutoRemove":true}}""",
            ),
            body(host, "POST /v1.56/containers/create"),
        )
        // the window says it is done with the volume it asked to browse, as the lab did
        run("unbrowse", mapOf("id" to "nh-sample-vol"))
        assertEquals(
            listOf(
                "GET /v1.56/containers/json?all=1&filters=" + urlEncoded("""{"label":["networkhelm.browse=nh-sample-vol"]}"""),
                "DELETE /v1.56/containers/$helper?force=1",
                "DELETE /v1.56/containers/$left?force=1",
            ),
            lines(host).filter { it.startsWith("GET /v1.56/containers/json") || it.startsWith("DELETE ") },
        )
    }

    private companion object {
        const val VERSION = """{"ApiVersion":"1.56","MinAPIVersion":"1.40"}"""
        const val ANONYMOUS = "fabe588542df3ebd8dde8e1adc969190183a99b094d5f479136e0f12722faded"

        // recorded on Docker 29.9.3 (API 1.56) on Arch and on Podman 5.8.9 (compatible API 1.44) on Fedora 44
        const val VOLUMES_29 = """{"Volumes":[{"CreatedAt":"2026-11-17T17:20:59Z","Driver":"local","Labels":null,"Mountpoint":"/var/lib/docker/volumes/nh-sample-vol/_data","Name":"nh-sample-vol",""" +
            """"Options":null,"Scope":"local"}],"Warnings":null}"""
        const val VOLUME_REQUEST = """{"Name":"nh-c3-vol","Labels":{"nh":"c3"}}"""
        const val VOLUME_CREATED_29 = """{"CreatedAt":"2026-11-17T18:00:02Z","Driver":"local","Labels":{"nh":"c3"},"Mountpoint":"/var/lib/docker/volumes/nh-c3-vol/_data","Name":"nh-c3-vol",""" +
            """"Options":null,"Scope":"local"}"""
        const val VOLUME_GONE_29 = """{"message":"get nh-c3-vol: no such volume"}"""
        const val VOLUME_GONE_PODMAN_5 = """{"cause":"no such volume","message":"no volume with name \"nh-c3-vol\" found: no such volume","response":404}"""
    }
}
