package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitServiceTest {
    // GitLab gives its tree in pages, two entries each in the fake, and names the next page in a header
    @Test
    fun theTreeOfGitLabIsReadPageByPage() = runTest {
        val repository = FakeGit(GitHost.GitLab)
        val service = repository.service()
        val devices = listOf("1", "2", "3").map { it.padStart(32, 'd') }
        for (device in devices) service.write(statePath(device), device.encodeToByteArray())
        service.write(VAULT, "vault".encodeToByteArray())
        assertEquals((devices.map(::statePath) + VAULT).toSet(), service.list().map { it.path }.toSet())
        assertEquals(3, repository.requests.count { it.endsWith("/repository/tree") })
    }

    // GitHub sends a file over 1 MB without its content, here a file over 4 bytes: the blob gives it
    @Test
    fun aLargeFileOfGitHubComesFromItsBlob() = runTest {
        val repository = FakeGit(GitHost.GitHub)
        val service = repository.service()
        service.write(statePath(FIRST_DEVICE), "more than four bytes".encodeToByteArray())
        assertContentEquals("more than four bytes".encodeToByteArray(), service.read(statePath(FIRST_DEVICE))?.bytes)
        assertTrue(repository.requests.any { "/git/blobs/" in it })
    }

    // the token goes only into the Authorization of each request, in the way of each host, and into no message
    @Test
    fun aRefusedTokenIsToldWithoutTheToken() = runTest {
        for (host in GitHost.entries) {
            val error = assertFailsWith<DeviceException> { FakeGit(host).service("a token that is not the one").list() }
            assertTrue(error.message.orEmpty().endsWith(" did not accept the login"), error.message)
            assertFalse("a token that is not the one" in error.toString())
            assertFalse(GIT_TOKEN in error.toString())
        }
    }

    // an address that is not the one of the API, such as the page of the server, answers with something else than its JSON
    @Test
    fun anAddressThatIsNotTheApiIsToldAsSuch() = runTest {
        val http = SyncHttp(TrustPolicy(MemoryTrustStore()) { false }) { HttpClient(MockEngine { respond("<html>Gitea</html>") }) }
        val error = assertFailsWith<DeviceException> { GitService(http, GitHost.Gitea, "https://git.example.org", "nhsync", "networkhelm-sync", "main", GIT_TOKEN).list() }
        assertEquals("git.example.org did not answer as the API of Gitea", error.message)
    }
}
