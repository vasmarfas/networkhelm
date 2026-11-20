package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LinuxDockerPodsTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    @Test
    fun aPodComesFromTheApiOfPodmanUnderAVersionOfItsOwnWithItsContainersButTheInfraOne() = runTest {
        val host = DockerHost(listOf("podman system dial-stdio </dev/null" to ok(), "date +%z" to ok("+0000\n"))) {
            DockerEngine(
                listOf(
                    "GET /version " to engineAnswer(VERSION),
                    "GET /v4.0.0/libpod/pods/json " to engineAnswer(PODS_PODMAN_5),
                    "POST /v4.0.0/libpod/pods/" to engineAnswer("""{"Errs":[],"Id":"88edab2681f29aff292bc4f8a091ca38d55897bfa524238868e65089cbc71694"}"""),
                    "DELETE /v4.0.0/libpod/pods/" to engineAnswer("""[{"Err":null,"Id":"88edab2681f29aff292bc4f8a091ca38d55897bfa524238868e65089cbc71694"}]"""),
                ),
            )
        }
        val pods = DockerPods(DockerApi(host, Root(host, null)) { setOf("podman") }).table { true }
        val pod = pods.read().single()
        assertEquals(
            listOf("nh-sample-pod", "Running", "nh-sample-podded", "2/2", "podman", "2026-11-17 17:31:05", "88edab2681f2"),
            listOf("name", "status", "containers", "running", "networks", "created", "pod-id").map { pod[it] },
        )
        assertNotNull(pods.run)("stop", mapOf("numbers" to pod.id))
        assertNotNull(pods.remove)(listOf(pod.id))
        assertEquals(
            listOf("GET /v4.0.0/libpod/pods/json", "POST /v4.0.0/libpod/pods/${pod.id}/stop", "DELETE /v4.0.0/libpod/pods/${pod.id}?force=true"),
            host.relays.flatMap { it.second.requests }.map { it.substringBefore("\r\n").removeSuffix(" HTTP/1.1") }.filter { "libpod" in it },
        )
    }

    private companion object {
        const val VERSION = """{"ApiVersion":"1.44","MinAPIVersion":"1.24"}"""

        // recorded on Podman 5.8.9 (compatible API 1.44) on Fedora 44
        const val PODS_PODMAN_5 = """[{"Cgroup":"machine.slice","Containers":[{"Id":"e80c6352d5d68ebbdcd9892063ce609141c9c56a2d5b94d5fb1c93a366f15eca","Names":"88edab2681f2-infra",""" +
            """"Status":"running","RestartCount":0},{"Id":"0c2069deda3ccb333bcccc89882db7a5ccc85cd9c6f9bff736ad37e0807973a2","Names":"nh-sample-podded","Status":"running",""" +
            """"RestartCount":0}],"Created":"2026-11-17T17:31:05.926548137Z","Id":"88edab2681f29aff292bc4f8a091ca38d55897bfa524238868e65089cbc71694","InfraId":"e80c6352d5d68ebbdcd9892063ce609141c9c56a2d5b94d5fb1c93a366f15eca",""" +
            """"Name":"nh-sample-pod","Namespace":"","Networks":["podman"],"Status":"Running","Labels":{}}]"""
    }
}
