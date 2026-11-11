package com.vasmarfas.networkhelm.routeros.api

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApiConnectionTest {

    private fun tagOf(sentence: List<String>) = sentence.single { it.startsWith(".tag=") }.substring(5)

    @Test
    fun plainLoginSendsNameAndPassword() = runTest {
        val router = FakeRouterStream()
        val api = ApiConnection(router, ApiEncoding.Utf8, backgroundScope)
        val login = async { api.login("admin", "secret") }
        val sent = router.nextSent()
        assertEquals(listOf("/login", "=name=admin", "=password=secret"), sent.dropLast(1))
        router.reply("!done", ".tag=${tagOf(sent)}")
        login.await()
    }

    @Test
    fun challengeLoginAnswersWithMd5OfZeroPasswordAndChallenge() = runTest {
        val router = FakeRouterStream()
        val api = ApiConnection(router, ApiEncoding.Utf8, backgroundScope)
        val login = async { api.login("admin", "secret") }
        val first = router.nextSent()
        val challenge = "ebddd18303a54111e2dea05a92ab46b4"
        router.reply("!done", "=ret=$challenge", ".tag=${tagOf(first)}")
        val second = router.nextSent()
        val secret = challenge.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val expected = md5(byteArrayOf(0) + "secret".encodeToByteArray() + secret).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        assertEquals(listOf("/login", "=name=admin", "=response=00$expected"), second.dropLast(1))
        router.reply("!done", ".tag=${tagOf(second)}")
        login.await()
    }

    @Test
    fun interleavedRepliesReachTheirOwnCommands() = runTest {
        val router = FakeRouterStream()
        val api = ApiConnection(router, ApiEncoding.Utf8, backgroundScope)
        val addresses = async { api.call("/ip/address/print").rows }
        val addressTag = tagOf(router.nextSent())
        val routes = async { api.call("/ip/route/print").rows }
        val routeTag = tagOf(router.nextSent())
        router.reply("!re", "=dst-address=0.0.0.0/0", ".tag=$routeTag")
        router.reply("!re", "=address=10.0.0.1/24", ".tag=$addressTag")
        router.reply("!done", ".tag=$routeTag")
        router.reply("!re", "=address=10.0.1.1/24", ".tag=$addressTag")
        router.reply("!done", ".tag=$addressTag")
        assertEquals(listOf(mapOf("dst-address" to "0.0.0.0/0")), routes.await())
        assertEquals(listOf("10.0.0.1/24", "10.0.1.1/24"), addresses.await().map { it["address"] })
    }

    @Test
    fun trapBecomesAnExceptionWithCategory() = runTest {
        val router = FakeRouterStream()
        val api = ApiConnection(router, ApiEncoding.Utf8, backgroundScope)
        val add = async { runCatching { api.call("/ip/address/add", "=address=10.0.0.1/24", "=interface=ether1") } }
        val tag = tagOf(router.nextSent())
        router.reply("!trap", "=category=1", "=message=failure: already have such address", ".tag=$tag")
        router.reply("!done", ".tag=$tag")
        val error = assertFailsWith<ApiTrapException> { add.await().getOrThrow() }
        assertEquals(1, error.category)
        assertEquals("failure: already have such address", error.message)
    }

    @Test
    fun emptyReplyMeansNoRows() = runTest {
        val router = FakeRouterStream()
        val api = ApiConnection(router, ApiEncoding.Utf8, backgroundScope)
        val print = async { api.call("/ip/address/print", "?address=192.0.2.1/32") }
        val tag = tagOf(router.nextSent())
        router.reply("!empty", ".tag=$tag")
        router.reply("!done", ".tag=$tag")
        assertTrue(print.await().rows.isEmpty())
    }

    @Test
    fun addReturnsTheNewIdInDone() = runTest {
        val router = FakeRouterStream()
        val api = ApiConnection(router, ApiEncoding.Utf8, backgroundScope)
        val add = async { api.call("/ip/firewall/address-list/add", "=list=rd-test", "=address=192.0.2.1") }
        router.reply("!done", "=ret=*1F", ".tag=${tagOf(router.nextSent())}")
        assertEquals("*1F", add.await().done["ret"])
    }

    @Test
    fun collectorThatStopsEarlyCancelsTheCommandOnTheRouter() = runTest {
        val router = FakeRouterStream()
        val api = ApiConnection(router, ApiEncoding.Utf8, backgroundScope)
        val first = async { api.command(listOf("/ping", "=address=192.0.2.1")).map { it.attributes["seq"] }.first() }
        val tag = tagOf(router.nextSent())
        router.reply("!re", "=seq=0", "=status=timeout", ".tag=$tag")
        assertEquals("0", first.await())
        assertEquals(listOf("/cancel", "=tag=$tag"), router.nextSent())
        router.reply("!trap", "=category=2", "=message=interrupted", ".tag=$tag")
        router.reply("!done", ".tag=$tag")
        router.reply("!done")
        val next = async { api.call("/system/identity/print").rows }
        val nextTag = tagOf(router.nextSent())
        router.reply("!re", "=name=lab", ".tag=$nextTag")
        router.reply("!done", ".tag=$nextTag")
        assertEquals(listOf(mapOf("name" to "lab")), next.await())
    }

    @Test
    fun brokenConnectionFailsPendingAndLaterCommands() = runTest {
        val router = FakeRouterStream()
        val api = ApiConnection(router, ApiEncoding.Utf8, backgroundScope)
        val listen = async { runCatching { api.command(listOf("/ip/address/listen")).toList() } }
        router.nextSent()
        router.close()
        assertFailsWith<ApiConnectionClosedException> { listen.await().getOrThrow() }
        assertFailsWith<ApiConnectionClosedException> { api.call("/system/identity/print") }
    }

    @Test
    fun untaggedFatalClosesTheConnectionWithItsReason() = runTest {
        val router = FakeRouterStream()
        val api = ApiConnection(router, ApiEncoding.Utf8, backgroundScope)
        val listen = async { runCatching { api.command(listOf("/log/listen")).toList() } }
        router.nextSent()
        router.reply("!fatal", "session terminated on request")
        val error = assertFailsWith<ApiConnectionClosedException> { listen.await().getOrThrow() }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it.message == "session terminated on request" })
    }

    @Test
    fun aWordTheEncodingOfTheRouterHasNoCodeForIsRefusedAsAValue() = runTest {
        val api = ApiConnection(FakeRouterStream(), ApiEncoding.Windows1251, backgroundScope)
        val error = assertFailsWith<DeviceException> { api.call("/ip/address/set", "=.id=*1", "=comment=\u4E2D") }
        assertEquals(ErrorCategory.ArgumentValue, error.category)
    }

    @Test
    fun cp1251CommentIsDecodedUnderUtf8Setting() = runTest {
        val router = FakeRouterStream()
        val api = ApiConnection(router, ApiEncoding.Utf8, backgroundScope)
        val print = async { api.call("/ip/address/print").rows }
        val tag = tagOf(router.nextSent())
        router.replyBytes(listOf("!re".encodeToByteArray(), "=comment=".encodeToByteArray() + ApiEncoding.Windows1251.encode("Привет"), ".tag=$tag".encodeToByteArray()))
        router.reply("!done", ".tag=$tag")
        assertEquals("Привет", print.await().single()["comment"])
    }
}
