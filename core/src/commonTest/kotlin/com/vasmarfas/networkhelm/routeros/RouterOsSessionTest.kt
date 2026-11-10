package com.vasmarfas.networkhelm.routeros

import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.LINK_LOST
import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.openwrt.RequestOutcomeUnknownException
import com.vasmarfas.networkhelm.routeros.api.ApiClient
import com.vasmarfas.networkhelm.routeros.api.ApiConnection
import com.vasmarfas.networkhelm.routeros.api.ApiConnectionClosedException
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.api.ApiReply
import com.vasmarfas.networkhelm.routeros.api.ApiResult
import com.vasmarfas.networkhelm.routeros.api.ApiTrapException
import com.vasmarfas.networkhelm.routeros.api.FakeRouterStream
import com.vasmarfas.networkhelm.routeros.api.RouterOsClient
import com.vasmarfas.networkhelm.routeros.api.RouterOsLink
import com.vasmarfas.networkhelm.session.SafeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RouterOsSessionTest {

    private val device = DeviceInfo(Platform.RouterOs, "lab", "7.26", "x86_64", "CHR", setOf("routeros"), setOf("read", "write"))
    private val capabilities = Capabilities(true, true, true, SafeModeKind.ApiSession, terminal = false, files = false, export = true)

    private fun session(router: FakeRouterStream, scope: CoroutineScope) =
        RouterOsSession(ApiClient(ApiConnection(router, ApiEncoding.Utf8, scope), scope) { error("no second connection") }, device, capabilities)

    private suspend fun FakeRouterStream.expect(vararg words: String): String {
        val sentence = nextSent()
        assertEquals(words.toList(), sentence.filterNot { it.startsWith(".tag=") })
        return sentence.single { it.startsWith(".tag=") }.substring(5)
    }

    @Test
    fun watchSendsListenBeforePrintAndKeepsTheOrderOfMoves() = runTest {
        val router = FakeRouterStream()
        val events = Channel<ListEvent>(Channel.UNLIMITED)
        backgroundScope.launch { session(router, backgroundScope).watch(MenuPath.parse("ip/firewall/filter")).collect { events.send(it) } }
        val listen = router.expect("/ip/firewall/filter/listen")
        val print = router.expect("/ip/firewall/filter/print")
        router.reply("!re", "=.id=*1", "=chain=input", ".tag=$print")
        router.reply("!re", "=.id=*2", "=chain=forward", ".tag=$print")
        router.reply("!done", ".tag=$print")
        assertEquals(
            ListEvent.Snapshot(listOf(Item("*1", mapOf("chain" to "input")), Item("*2", mapOf("chain" to "forward")))),
            events.receive(),
        )
        router.reply("!re", "=.id=*2", "=.nextid=*1", "=chain=forward", ".tag=$listen")
        assertEquals(ListEvent.Upsert(Item("*2", mapOf("chain" to "forward")), next = "*1"), events.receive())
        router.reply("!re", "=.id=*1", "=.nextid=*FFFFFFFF", "=chain=output", ".tag=$listen")
        assertEquals(ListEvent.Upsert(Item("*1", mapOf("chain" to "output")), next = ""), events.receive())
        router.reply("!re", "=.id=*2", "=.dead=true", ".tag=$listen")
        assertEquals(ListEvent.Removed("*2"), events.receive())
    }

    @Test
    fun menuWithoutListenIsPolled() = runTest {
        val router = FakeRouterStream()
        val events = Channel<ListEvent>(Channel.UNLIMITED)
        val path = MenuPath.parse("system/resource")
        backgroundScope.launch { session(router, backgroundScope).watch(path, properties = listOf("uptime")).collect { events.send(it) } }
        val listen = router.expect("/system/resource/listen", "=.proplist=.id,.dead,.nextid,uptime")
        val print = router.expect("/system/resource/print", "=.proplist=.id,uptime")
        router.reply("!trap", "=message=unknown parameter follow-only", ".tag=$listen")
        router.reply("!done", ".tag=$listen")
        router.reply("!re", "=uptime=1s", ".tag=$print")
        router.reply("!done", ".tag=$print")
        assertEquals(ListEvent.Snapshot(listOf(Item("", mapOf("uptime" to "1s")))), events.receive())
        val again = router.expect("/system/resource/print", "=.proplist=.id,uptime")
        router.reply("!re", "=uptime=2s", ".tag=$again")
        router.reply("!done", ".tag=$again")
        assertEquals(ListEvent.Snapshot(listOf(Item("", mapOf("uptime" to "2s")))), events.receive())
    }

    @Test
    fun aPollThatRowsGoingUnderThePrintBrokeKeepsTheList() = runTest {
        val router = FakeRouterStream()
        val events = Channel<ListEvent>(Channel.UNLIMITED)
        backgroundScope.launch { session(router, backgroundScope).watch(MenuPath.parse("ip/firewall/connection")).collect { events.send(it) } }
        val listen = router.expect("/ip/firewall/connection/listen")
        val print = router.expect("/ip/firewall/connection/print")
        router.reply("!trap", "=message=no such command", ".tag=$listen")
        router.reply("!done", ".tag=$listen")
        router.reply("!re", "=.id=*1", "=protocol=udp", ".tag=$print")
        router.reply("!done", ".tag=$print")
        assertEquals(ListEvent.Snapshot(listOf(Item("*1", mapOf("protocol" to "udp")))), events.receive())
        val broken = router.expect("/ip/firewall/connection/print")
        router.reply("!trap", "=message=interrupted", ".tag=$broken")
        router.reply("!done", ".tag=$broken")
        val again = router.expect("/ip/firewall/connection/print")
        router.reply("!re", "=.id=*2", "=protocol=tcp", ".tag=$again")
        router.reply("!done", ".tag=$again")
        assertEquals(ListEvent.Snapshot(listOf(Item("*2", mapOf("protocol" to "tcp")))), events.receive())
    }

    @Test
    fun theLogIsPolledOnceInFiveIntervals() = runTest {
        val router = FakeRouterStream()
        val events = Channel<ListEvent>(Channel.UNLIMITED)
        backgroundScope.launch { session(router, backgroundScope).watch(MenuPath.parse("log")).collect { events.send(it) } }
        val listen = router.expect("/log/listen")
        val print = router.expect("/log/print")
        router.reply("!trap", "=message=unknown parameter follow-only", ".tag=$listen")
        router.reply("!done", ".tag=$listen")
        router.reply("!re", "=.id=*1", "=message=up", ".tag=$print")
        router.reply("!done", ".tag=$print")
        assertEquals(ListEvent.Snapshot(listOf(Item("*1", mapOf("message" to "up")))), events.receive())
        assertNull(withTimeoutOrNull(4.9.seconds) { router.nextSent() })
        router.expect("/log/print")
    }

    @Test
    fun settingsOfRouterOs6AreReadWithoutIds() = runTest {
        val router = FakeRouterStream()
        val client = ApiClient(ApiConnection(router, ApiEncoding.Utf8, backgroundScope), backgroundScope) { error("no second connection") }
        val session = RouterOsSession(client, device, capabilities.copy(menuContents = false))
        val get = async { session.get(MenuPath.parse("system/resource"), null, listOf("uptime")) }
        val count = router.expect("/system/resource/print", "=count-only=")
        router.reply("!trap", "=message=unknown parameter", ".tag=$count")
        router.reply("!done", ".tag=$count")
        val proplist = router.expect("/system/resource/print", "=.proplist=uptime")
        router.reply("!re", "=uptime=2s", ".tag=$proplist")
        router.reply("!done", ".tag=$proplist")
        assertEquals(Item("", mapOf("uptime" to "2s")), get.await())
    }

    @Test
    fun countersOfSettingsOfRouterOs6AreAskedWithoutIds() = runTest {
        val router = FakeRouterStream()
        val client = ApiClient(ApiConnection(router, ApiEncoding.Utf8, backgroundScope), backgroundScope) { error("no second connection") }
        val session = RouterOsSession(client, device, capabilities.copy(menuContents = false))
        val round = async { session.counters(MenuPath.parse("system/license"), listOf("level")).first() }
        val count = router.expect("/system/license/print", "=count-only=")
        router.reply("!trap", "=message=unknown parameter", ".tag=$count")
        router.reply("!done", ".tag=$count")
        val counters = router.expect("/system/license/print", "=interval=1", "=.proplist=level")
        router.reply("!re", "=level=free", "=.section=0", ".tag=$counters")
        router.reply("!re", "=level=free", "=.section=1", ".tag=$counters")
        assertEquals(mapOf("" to mapOf("level" to "free")), round.await())
    }

    @Test
    fun countersOfOneElementAreAskedForItAlone() = runTest {
        val router = FakeRouterStream()
        val client = ApiClient(ApiConnection(router, ApiEncoding.Utf8, backgroundScope), backgroundScope) { error("no second connection") }
        val session = RouterOsSession(client, device, capabilities.copy(menuContents = false))
        val path = MenuPath.parse("ip/dhcp-server/lease")
        val round = async { session.counters(path, listOf("expires-after"), Filter.Equals(".id", "*5")).first() }
        val count = router.expect("/ip/dhcp-server/lease/print", "=count-only=")
        router.reply("!done", "=ret=600", ".tag=$count")
        val counters = router.expect("/ip/dhcp-server/lease/print", "=interval=1", "=.proplist=.id,expires-after", "?.id=*5")
        router.reply("!re", "=.id=*5", "=expires-after=9m", "=.section=0", ".tag=$counters")
        router.reply("!re", "=.id=*5", "=expires-after=8m59s", "=.section=1", ".tag=$counters")
        assertEquals(mapOf("*5" to mapOf("expires-after" to "9m")), round.await())
    }

    @Test
    fun filteredWatchAsksAgainWhetherAChangedElementStillMatches() = runTest {
        val router = FakeRouterStream()
        val events = Channel<ListEvent>(Channel.UNLIMITED)
        val path = MenuPath.parse("ip/firewall/address-list")
        backgroundScope.launch { session(router, backgroundScope).watch(path, Filter.Equals("list", "blocked")).collect { events.send(it) } }
        val listen = router.expect("/ip/firewall/address-list/listen")
        val print = router.expect("/ip/firewall/address-list/print", "?list=blocked")
        router.reply("!re", "=.id=*1", "=list=blocked", "=address=192.0.2.1", ".tag=$print")
        router.reply("!done", ".tag=$print")
        assertIs<ListEvent.Snapshot>(events.receive())
        router.reply("!re", "=.id=*1", "=list=allowed", "=address=192.0.2.1", ".tag=$listen")
        val check = router.expect("/ip/firewall/address-list/print", "?list=blocked", "?.id=*1")
        router.reply("!empty", ".tag=$check")
        router.reply("!done", ".tag=$check")
        assertEquals(ListEvent.Removed("*1"), events.receive())
    }

    @Test
    fun aWatchOfOneElementAsksNothingAboutTheEventsOfOthers() = runTest {
        val router = FakeRouterStream()
        val events = Channel<ListEvent>(Channel.UNLIMITED)
        val path = MenuPath.parse("ip/firewall/address-list")
        backgroundScope.launch { session(router, backgroundScope).watch(path, Filter.Equals(".id", "*1")).collect { events.send(it) } }
        val listen = router.expect("/ip/firewall/address-list/listen")
        val print = router.expect("/ip/firewall/address-list/print", "?.id=*1")
        router.reply("!re", "=.id=*1", "=list=blocked", ".tag=$print")
        router.reply("!done", ".tag=$print")
        assertIs<ListEvent.Snapshot>(events.receive())
        router.reply("!re", "=.id=*2", "=list=allowed", ".tag=$listen")
        router.reply("!re", "=.id=*1", "=list=allowed", ".tag=$listen")
        val check = router.expect("/ip/firewall/address-list/print", "?.id=*1", "?.id=*1")
        router.reply("!re", "=.id=*1", "=list=allowed", ".tag=$check")
        router.reply("!done", ".tag=$check")
        assertEquals(Item("*1", mapOf("list" to "allowed")), assertIs<ListEvent.Upsert>(events.receive()).item)
    }

    @Test
    fun setWritesValuesThenUnsetsEachNull() = runTest {
        val router = FakeRouterStream()
        val session = session(router, backgroundScope)
        val set = async { session.set(MenuPath.parse("ip/firewall/filter"), "*5", mapOf("comment" to "web", "src-address" to null, "dst-port" to null)) }
        router.reply("!done", ".tag=${router.expect("/ip/firewall/filter/set", "=.id=*5", "=comment=web")}")
        router.reply("!done", ".tag=${router.expect("/ip/firewall/filter/unset", "=.id=*5", "=value-name=src-address")}")
        router.reply("!done", ".tag=${router.expect("/ip/firewall/filter/unset", "=.id=*5", "=value-name=dst-port")}")
        set.await()
    }

    @Test
    fun trapComesAsDeviceExceptionWithItsCategory() = runTest {
        val router = FakeRouterStream()
        val session = session(router, backgroundScope)
        val add = async { runCatching { session.add(MenuPath.parse("ip/address"), mapOf("address" to "10.0.0.1/24")) } }
        val tag = router.expect("/ip/address/add", "=address=10.0.0.1/24")
        router.reply("!trap", "=category=1", "=message=failure: already have such address", ".tag=$tag")
        router.reply("!done", ".tag=$tag")
        val error = assertIs<DeviceException>(add.await().exceptionOrNull())
        assertEquals(ErrorCategory.ArgumentValue, error.category)
        assertEquals("failure: already have such address", error.message)
    }

    private class Link : RouterOsClient {
        val state = MutableStateFlow(LinkState.Up)
        val calls = mutableListOf<String>()
        val sent = mutableListOf<List<String>>()
        var expired = false
        override val status: Flow<LinkState> = state
        override val passwordExpired: Boolean get() = expired

        override suspend fun call(words: List<String>): ApiResult {
            calls += words.first()
            sent += words
            return when (words.first()) {
                "/safe-mode/take" -> ApiResult(emptyList(), mapOf("ret" to "true"))
                "/safe-mode/print" -> ApiResult(listOf(mapOf("enabled" to "true", "current" to "false", "owner" to "api")), emptyMap())
                "/ip/service/print" -> ApiResult(listOf(mapOf("port" to "2222", "disabled" to "true")), emptyMap())
                else -> ApiResult(emptyList(), emptyMap())
            }
        }

        val streams = mutableListOf<List<String>>()

        override fun command(words: List<String>, sent: CompletableDeferred<Unit>?): Flow<ApiReply> = flow {
            streams += words
            awaitCancellation()
        }

        override fun <T> each(block: suspend FlowCollector<T>.(RouterOsLink) -> Unit): Flow<T> = emptyFlow()

        override fun close() {
            state.value = LinkState.Closed
        }
    }

    @Test
    fun aTableThatComesInPortionsTellsItsProgressBeforeTheSnapshot() = runTest {
        val events = RouterOsSession(PortionsLink(), device, capabilities).watch(MenuPath.parse("ip/firewall/address-list")).take(3).toList()
        assertEquals(listOf(ListEvent.Progress(2_000, 3_000), ListEvent.Progress(3_000, 3_000), ListEvent.Snapshot(listOf(Item("*1", emptyMap())))), events)
    }

    @Test
    fun manyRowsAreRemovedInLinesThatFitTheConsole() = runTest {
        val link = Link()
        RouterOsSession(link, device, capabilities).remove(MenuPath.parse("ip/firewall/address-list"), (1..450).map { "*$it" })
        assertEquals(listOf(200, 200, 50), link.sent.filter { it.first().endsWith("/remove") }.map { it[1].split(',').size })
    }

    @Test
    fun anEditThatLosesTheLinkDoesNotClaimItFailed() = runTest {
        val session = RouterOsSession(Dropping(), device, capabilities)
        assertFailsWith<RequestOutcomeUnknownException> { session.set(MenuPath.parse("ip/pool"), "*1", mapOf("ranges" to "10.0.0.1")) }
        assertFailsWith<RequestOutcomeUnknownException> { session.add(MenuPath.parse("ip/pool"), mapOf("name" to "a")) }
        assertFailsWith<RequestOutcomeUnknownException> { session.remove(MenuPath.parse("ip/pool"), listOf("*1")) }
    }

    @Test
    fun aReadThatLosesTheLinkSaysSoInPlainWords() = runTest {
        val session = RouterOsSession(Dropping(), device, capabilities)
        assertEquals(LINK_LOST, assertFailsWith<DeviceException> { session.count(MenuPath.parse("ip/pool"), null) }.message)
    }

    @Test
    fun theSafeModeOfTheConsoleFailsWithADeviceError() = runTest {
        val session = RouterOsSession(Dropping(), device, capabilities)
        assertEquals(LINK_LOST, assertFailsWith<DeviceException> { assertNotNull(session.safeMode).enter() }.message)
    }

    private class Dropping : RouterOsClient by Link() {
        override suspend fun call(words: List<String>): ApiResult = throw ApiConnectionClosedException("connection closed")

        override val safeMode: SafeMode = object : SafeMode {
            override val active: Flow<Boolean> = emptyFlow()
            override val actions: Flow<Int> = emptyFlow()

            override suspend fun enter(): Boolean = throw ApiConnectionClosedException("connection closed")

            override suspend fun commit() = Unit

            override suspend fun rollback() = Unit
        }
    }

    private class PortionsLink : RouterOsClient by Link() {
        override suspend fun table(words: List<String>, progress: suspend (Int, Int) -> Unit): List<Map<String, String>> {
            progress(2_000, 3_000)
            progress(3_000, 3_000)
            return listOf(mapOf(".id" to "*1"))
        }

        override fun command(words: List<String>, sent: CompletableDeferred<Unit>?): Flow<ApiReply> = flow { throw ApiTrapException(0, "no such command") }

        override fun <T> each(block: suspend FlowCollector<T>.(RouterOsLink) -> Unit): Flow<T> = flow { block(this@PortionsLink) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aClosedSessionLeavesNothingRunningInItsScope() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val session = RouterOsSession(Link(), device, capabilities, scope = scope)
        runCurrent()
        assertEquals(1, scope.coroutineContext.job.children.count(), "the watch of the link")
        session.close()
        runCurrent()
        assertEquals(0, scope.coroutineContext.job.children.count())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun safeModeLostWithTheLinkWaitsToBeSettled() = runTest {
        val link = Link()
        val safe = assertNotNull(RouterOsSession(link, device, capabilities, scope = backgroundScope).safeMode)
        runCurrent()
        assertTrue(safe.enter())
        runCurrent()
        assertEquals(listOf(listOf("/system/resource/print", "=interval=5", "=.proplist=uptime")), link.streams, "the router keeps sending while the mode is held")
        link.state.value = LinkState.Reconnecting
        runCurrent()
        assertFalse(safe.active.first())
        link.state.value = LinkState.Up
        runCurrent()
        assertTrue(safe.stranded.first())
        safe.settle(keep = false)
        assertEquals("/safe-mode/unroll", link.calls.last())
        assertFalse(safe.stranded.first())
    }

    @Test
    fun aPasswordThatExpiredIsChangedWithTheCommandOfTheRouterAndIsNotAskedAgain() = runTest {
        val link = Link().apply { expired = true }
        val session = RouterOsSession(link, device, capabilities)
        assertTrue(session.passwordExpired)
        session.changePassword("old", "new")
        assertEquals(listOf("/password", "=old-password=old", "=new-password=new", "=confirm-new-password=new"), link.sent.last())
        assertFalse(session.passwordExpired)
    }

    @Test
    fun theSshPortComesFromTheServiceOfTheRouter() = runTest {
        assertEquals(2222, RouterOsSession(Link(), device, capabilities).sshPort())
    }

    @Test
    fun theSshServiceTellsWhetherItIsTurnedOff() = runTest {
        val service = assertNotNull(RouterOsSession(Link(), device, capabilities).sshService())
        assertEquals(2222, service.port)
        assertTrue(service.disabled)
    }
}
