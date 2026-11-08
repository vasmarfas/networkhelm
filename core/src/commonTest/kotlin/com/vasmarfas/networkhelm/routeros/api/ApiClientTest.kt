package com.vasmarfas.networkhelm.routeros.api

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApiClientTest {

    private fun tagOf(sentence: List<String>) = sentence.single { it.startsWith(".tag=") }.substring(5)

    @Test
    fun subscriptionRunsAgainOnTheNextConnection() = runTest {
        val first = FakeRouterStream()
        val second = FakeRouterStream()
        val client = ApiClient(ApiConnection(first, ApiEncoding.Utf8, backgroundScope), backgroundScope) {
            ApiConnection(second, ApiEncoding.Utf8, backgroundScope)
        }
        val seen = Channel<String>(Channel.UNLIMITED)
        backgroundScope.launch {
            client.each { api -> api.command(listOf("/ip/address/listen")).collect { emit(it.attributes.getValue("address")) } }
                .collect { seen.send(it) }
        }
        first.reply("!re", "=address=10.0.0.1/24", ".tag=${tagOf(first.nextSent())}")
        assertEquals("10.0.0.1/24", seen.receive())

        first.close()
        val down = assertIs<ApiLink.Down>(client.state.first { it !is ApiLink.Up })
        assertEquals(1, down.attempt)
        val listen = second.nextSent()
        assertEquals("/ip/address/listen", listen.first())
        second.reply("!re", "=address=10.0.1.1/24", ".tag=${tagOf(listen)}")
        assertEquals("10.0.1.1/24", seen.receive())
    }

    @Test
    fun commandCutByALossIsNotRepeated() = runTest {
        val first = FakeRouterStream()
        val second = FakeRouterStream()
        val reopened = ApiConnection(second, ApiEncoding.Utf8, backgroundScope)
        val client = ApiClient(ApiConnection(first, ApiEncoding.Utf8, backgroundScope), backgroundScope) { reopened }
        val add = async { runCatching { client.call("/ip/address/add", "=address=10.0.0.1/24", "=interface=ether1") } }
        first.nextSent()
        first.close()
        assertIs<ApiConnectionClosedException>(add.await().exceptionOrNull())
        client.state.first { it is ApiLink.Up && it.connection === reopened }
        assertTrue(second.sent.tryReceive().isFailure)

        client.close()
        assertIs<ApiLink.Closed>(client.state.value)
        assertFailsWith<ApiConnectionClosedException> { client.call("/system/identity/print") }
    }

    @Test
    fun routerThatStopsAnsweringIsDroppedByTheKeepAlive() = runTest {
        val first = FakeRouterStream()
        val client = ApiClient(ApiConnection(first, ApiEncoding.Utf8, backgroundScope), backgroundScope) {
            ApiConnection(FakeRouterStream(), ApiEncoding.Utf8, backgroundScope)
        }
        assertEquals("/system/identity/print", first.nextSent().first())
        val down = assertIs<ApiLink.Down>(client.state.first { it !is ApiLink.Up })
        assertTrue(down.cause.message.orEmpty().startsWith("no answer"), down.cause.message)
    }

    @Test
    fun aReadLongerThanTheKeepAliveIsNotTakenForALostRouter() = runTest {
        val first = FakeRouterStream()
        val client = ApiClient(ApiConnection(first, ApiEncoding.Utf8, backgroundScope), backgroundScope) {
            ApiConnection(FakeRouterStream(), ApiEncoding.Utf8, backgroundScope)
        }
        val read = async { client.call("/ip/firewall/address-list/print") }
        val tag = tagOf(first.nextSent())
        repeat(20) { round ->
            delay(5_000)
            first.reply("!re", "=address=10.0.0.$round", ".tag=$tag")
        }
        first.reply("!done", ".tag=$tag")
        assertEquals(20, read.await().rows.size)
        assertIs<ApiLink.Up>(client.state.value)
    }

    @Test
    fun refusedLoginStopsReconnecting() = runTest {
        val first = FakeRouterStream()
        var attempts = 0
        val client = ApiClient(ApiConnection(first, ApiEncoding.Utf8, backgroundScope), backgroundScope) {
            attempts++
            throw ApiTrapException(null, "invalid user name or password (6)")
        }
        val watch = async {
            runCatching { client.each<Unit> { api -> api.command(listOf("/ip/address/listen")).collect() }.collect() }
        }
        first.nextSent()
        first.close()
        val closed = assertIs<ApiLink.Closed>(client.state.first { it is ApiLink.Closed })
        assertIs<ApiTrapException>(closed.cause)
        assertIs<ApiTrapException>(watch.await().exceptionOrNull()?.cause)
        assertEquals(1, attempts)
    }
}
