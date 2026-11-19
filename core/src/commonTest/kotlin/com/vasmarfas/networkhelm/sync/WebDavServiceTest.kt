package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebDavServiceTest {
    // Apache: the prefix D: and the namespace lp1: of its properties, and the weak tag of a fresh file
    @Test
    fun theChecksOfEveryServicePassOnApache() = runTest {
        FakeWebDav(apache = true).fake("Apache").aFileGoesInComesOutAndGoesAway()
        FakeWebDav(apache = true).fake("Apache").aFileListedIsReadAsItIsWhenTheReadComes()
        val server = FakeWebDav(apache = true)
        server.fake("Apache").aConnectionBrokenHalfwayFailsAndLeavesTheFileAsItWas(server::breakNext)
        FakeWebDav(apache = true).fake("Apache").copiesOfConflictsAndFilesOfOthersAreNotListed()
        FakeWebDav(apache = true).fake("Apache").aCopyGivenBackFromBeforeIsRefused()
    }

    // Apache tags a file written within the last second weakly and compares If-Match strongly, so the vault is not written over such a
    // file; once the second has passed the same tag, now strong, lets it through without another read
    @Test
    fun theVaultIsNotWrittenOverAFileApacheTagsWeaklyAndIsOnceTheTagIsStrong() = runTest {
        val server = FakeWebDav(apache = true)
        val service = server.service()
        assertTrue(service.writeIf(VAULT, "one".encodeToByteArray(), null))
        val fresh = assertNotNull(service.read(VAULT))
        assertEquals("\"1\"", fresh.tag)
        assertEquals("\"1\"", service.list().single().tag)
        assertFalse(service.writeIf(VAULT, "two".encodeToByteArray(), fresh))
        server.age()
        assertEquals("\"1\"", service.list().single().tag)
        assertTrue(service.writeIf(VAULT, "two".encodeToByteArray(), fresh))
    }

    // a new vault on a server: the folders are made by the first write that misses them, and only then
    @Test
    fun theFoldersOfANewVaultAreMadeByTheFirstWriteThatMissesThem() = runTest {
        val server = FakeWebDav(apache = true)
        val service = server.service()
        service.write(statePath(FIRST_DEVICE), "one".encodeToByteArray())
        service.write(statePath(SECOND_DEVICE), "two".encodeToByteArray())
        service.write(VAULT, "three".encodeToByteArray())
        val state = "/dav/$SYNC_FOLDER/state"
        assertEquals(
            listOf(
                "PUT $state/$FIRST_DEVICE",
                "MKCOL /dav/$SYNC_FOLDER/",
                "MKCOL $state/",
                "PUT $state/$FIRST_DEVICE",
                "PUT $state/$SECOND_DEVICE",
                "PUT /dav/$SYNC_FOLDER/vault",
            ),
            server.requests,
        )
    }

    // the password goes only into the Authorization of each request: not into the message of a refusal, even when the server sends the
    // header back
    @Test
    fun aRefusedLoginIsToldWithoutThePassword() = runTest {
        val error = assertFailsWith<DeviceException> { FakeWebDav(apache = false).service("wrong password").list() }
        assertEquals("dav.example.org did not accept the login", error.message)
        assertFalse("wrong password" in error.toString())
        assertFalse(Base64.encode("nhsync:wrong password".encodeToByteArray()) in error.toString())
        assertFalse(DAV_PASSWORD in error.toString())
    }
}
