package com.vasmarfas.networkhelm.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

// the checks every service passes, each on a fresh fake of its own: a folder, WebDAV the way Nextcloud answers, S3, GitHub, Gitea,
// GitLab and the gateway of NetworkHelm
class SyncServicesTest {
    private fun fakes() = listOf(
        MemoryFolder().fake(),
        FakeWebDav(apache = false).fake("WebDAV"),
        FakeS3().fake("S3"),
        FakeGit(GitHost.GitHub).fake("GitHub"),
        FakeGit(GitHost.Gitea).fake("Gitea"),
        FakeGit(GitHost.GitLab).fake("GitLab"),
        FakeGateway().fake("gateway"),
    )

    @Test
    fun aFileGoesInComesOutAndGoesAway() = runTest {
        for (fake in fakes()) fake.aFileGoesInComesOutAndGoesAway()
    }

    @Test
    fun theVaultIsWrittenWhereNoneIsOrOverTheOneReadBefore() = runTest {
        for (fake in fakes()) fake.theVaultIsWrittenWhereNoneIsOrOverTheOneReadBefore()
    }

    @Test
    fun aFileListedIsReadAsItIsWhenTheReadComes() = runTest {
        for (fake in fakes()) fake.aFileListedIsReadAsItIsWhenTheReadComes()
    }

    @Test
    fun aConnectionBrokenHalfwayFailsAndLeavesTheFileAsItWas() = runTest {
        val folder = MemoryFolder()
        folder.fake().aConnectionBrokenHalfwayFailsAndLeavesTheFileAsItWas { folder.broken = true }
        val dav = FakeWebDav(apache = false)
        dav.fake("WebDAV").aConnectionBrokenHalfwayFailsAndLeavesTheFileAsItWas(dav::breakNext)
        val bucket = FakeS3()
        bucket.fake("S3").aConnectionBrokenHalfwayFailsAndLeavesTheFileAsItWas(bucket::breakNext)
        for (host in GitHost.entries) {
            val repository = FakeGit(host)
            repository.fake("$host").aConnectionBrokenHalfwayFailsAndLeavesTheFileAsItWas(repository::breakNext)
        }
        val gateway = FakeGateway()
        gateway.fake("gateway").aConnectionBrokenHalfwayFailsAndLeavesTheFileAsItWas(gateway::breakNext)
    }

    @Test
    fun copiesOfConflictsAndFilesOfOthersAreNotListed() = runTest {
        for (fake in fakes()) fake.copiesOfConflictsAndFilesOfOthersAreNotListed()
    }

    @Test
    fun aCopyGivenBackFromBeforeIsRefused() = runTest {
        for (fake in fakes()) fake.aCopyGivenBackFromBeforeIsRefused()
    }
}
