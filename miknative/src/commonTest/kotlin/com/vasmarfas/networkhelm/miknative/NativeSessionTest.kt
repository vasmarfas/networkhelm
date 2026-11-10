package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.ShellChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val MEPTY_LOGIN = 0x0A0065L
private const val MEPTY_CLOSE = 0x0A0066L
private const val MEPTY_DATA = 0x0A0067L
private const val MEPTY_RESIZE = 0x0A0068L

class NativeSessionTest {

    private fun session(
        handler: suspend FakeRouter.(M2Message) -> Unit,
        block: suspend CoroutineScope.(NativeConnection, FakeRouter) -> Unit,
    ) = runTest {
        val (client, server) = pipe()
        val router = FakeRouter(this, server, "admin", "secret", handler)
        val connection = NativeConnection.open(client, "admin", "secret", this)
        try {
            withTimeout(20_000) { block(connection, router) }
        } finally {
            connection.close()
            router.close()
        }
    }

    private suspend fun until(condition: () -> Boolean) {
        while (!condition()) delay(10)
    }

    private fun source(message: M2Message) = checkNotNull(message.numbers(M2Key.From))[1]

    @Test
    fun aRequestIsAnsweredByItsId() = session({ message ->
        if (message.number(M2Key.Command) == 7L) reply(message, 5 to M2Value.Text("CHR"))
    }) { connection, _ ->
        val source = connection.newSource()
        assertEquals("CHR", source.request(listOf(13, 4), M2Value.U8(7)).text(5))
        assertEquals("CHR", source.request(listOf(13, 4), M2Value.U8(7)).text(5))
    }

    @Test
    fun aRefusalOfTheHandlerIsAnError() = session({ message ->
        reply(message, M2Key.ErrorCode to M2Value.U32(0xFE0009), M2Key.Error to M2Value.Text("not permitted"))
    }) { connection, _ ->
        val error = assertFailsWith<DeviceException> { connection.newSource().request(listOf(2, 2), M2Value.U8(1)) }
        assertEquals("not permitted", error.message)
    }

    @Test
    fun messagesGoToTheSourceTheyAreAddressedTo() = session({ message ->
        send(M2Message.of(M2Key.To to M2Value.U32s(longArrayOf(0, source(message))), 1 to M2Value.U32(source(message))))
    }) { connection, _ ->
        val first = connection.newSource()
        val second = connection.newSource()
        first.send(listOf(1))
        second.send(listOf(1))
        assertEquals(first.id.toLong(), first.next()?.number(1))
        assertEquals(second.id.toLong(), second.next()?.number(1))
    }

    @Test
    fun messagesThatShareOneFrameAllArrive() = session({}) { connection, router ->
        val first = connection.newSource()
        val second = connection.newSource()
        fun to(source: NativeSource, number: Long) = M2Message.of(M2Key.To to M2Value.U32s(longArrayOf(0, source.id.toLong())), 1 to M2Value.U32(number))
        router.sendTogether(to(first, 1), to(second, 2), to(first, 3))
        assertEquals(1L, first.next()?.number(1))
        assertEquals(2L, second.next()?.number(1))
        assertEquals(3L, first.next()?.number(1))
    }

    @Test
    fun aWrongPasswordEndsTheConnection() = runTest {
        val (client, server) = pipe()
        FakeRouter(this, server, "admin", "secret") {}
        val error = assertFailsWith<DeviceException> { NativeConnection.open(client, "admin", "guess", this) }
        assertEquals(ErrorCategory.PermissionDenied, error.category)
    }

    @Test
    fun aConnectionTheRouterClosesEndsTheSources() = session({}) { connection, router ->
        val source = connection.newSource()
        router.close()
        assertFailsWith<DeviceException> { source.next() }
    }

    private fun number(value: Long) = if (value < 256) M2Value.U8(value.toInt()) else M2Value.U32(value)

    private fun consoleRouter(chatty: Boolean = false, console: Long = 3): suspend FakeRouter.(M2Message) -> Unit = { message ->
        when (message.numbers(M2Key.To)) {
            listOf(13L, 4L) -> reply(message, 5 to M2Value.Text("info"))
            listOf(76L) -> when (message.number(M2Key.Command)) {
                MEPTY_LOGIN -> reply(message, M2Key.SessionId to number(console))
                MEPTY_DATA -> if (message.number(M2Key.SessionId) == console) {
                    val typed = message.raw(2)
                    val output = when {
                        typed != null -> "echo:" + typed.decodeToString()
                        message.number(3) == 0L -> "banner> "
                        else -> null
                    }
                    if (output != null) send(
                        M2Message.of(
                            M2Key.To to M2Value.U32s(longArrayOf(0, source(message))),
                            M2Key.From to M2Value.U32s(longArrayOf(76)),
                            M2Key.SessionId to number(console),
                            M2Key.Command to M2Value.U32(MEPTY_DATA),
                            2 to M2Value.Raw(output.encodeToByteArray()),
                        ),
                    )
                    if (chatty && output == "banner> ") {
                        send(M2Message.of(M2Key.To to M2Value.U32s(longArrayOf(0, source(message))), 0xFF0003 to M2Value.U8(2)))
                    }
                }
                else -> Unit
            }
        }
    }

    private suspend fun ShellChannel.readText(length: Int): String {
        val buffer = ByteArray(length)
        var done = 0
        while (done < length) {
            val count = read(buffer, done, length - done)
            check(count >= 0)
            done += count
        }
        return buffer.decodeToString()
    }

    @Test
    fun theConsoleShowsItsOutputAndEchoesInput() = session(consoleRouter()) { connection, router ->
        val terminal = NativeTerminal.open(connection, "secret", "vt102", 80, 24)
        assertEquals("banner> ", terminal.readText(8))
        terminal.write("ls".encodeToByteArray())
        assertEquals("echo:ls", terminal.readText(7))
        val data = router.received.filter { it.number(M2Key.Command) == MEPTY_DATA && it.raw(2) != null }
        assertEquals(listOf(0L), data.map { it.number(3) })
        assertContentEquals("ls".encodeToByteArray(), data.single().raw(2))
        terminal.write("x".encodeToByteArray())
        until { router.received.count { it.number(M2Key.Command) == MEPTY_DATA && it.raw(2) != null } == 2 }
        assertEquals(2L, router.received.last { it.raw(2) != null }.number(3))
    }

    @Test
    fun theLoginOfTheConsoleCarriesTheSizeTheTypeAndThePassword() = session(consoleRouter()) { connection, router ->
        val terminal = NativeTerminal.open(connection, "secret", "vt102", 80, 24)
        assertEquals("banner> ", terminal.readText(8))
        val login = router.received.single { it.number(M2Key.Command) == MEPTY_LOGIN }
        assertEquals(listOf(80L, 24L, 0L), listOf(login.number(5), login.number(6), login.number(8)))
        assertEquals("vt102", login.text(7))
        assertEquals("secret", login.text(1))
        assertEquals(0L, login.message(0x0B)?.number(1))
    }

    @Test
    fun aConsoleWhoseNumberIsAbove255IsAddressedByTheWholeNumber() = session(consoleRouter(console = 281)) { connection, router ->
        val terminal = NativeTerminal.open(connection, "secret", "vt102", 80, 24)
        assertEquals("banner> ", terminal.readText(8))
        terminal.write("ls".encodeToByteArray())
        assertEquals("echo:ls", terminal.readText(7))
        terminal.resize(132, 43)
        until { router.received.any { it.number(M2Key.Command) == MEPTY_RESIZE } }
        assertEquals(281L, router.received.single { it.number(M2Key.Command) == MEPTY_RESIZE }.number(M2Key.SessionId))
    }

    @Test
    fun whatArrivedIsAcknowledgedWithItsByteCount() = session(consoleRouter()) { connection, router ->
        val terminal = NativeTerminal.open(connection, "secret", "vt102", 80, 24)
        terminal.readText(8)
        val waiting = launch { terminal.read(ByteArray(1), 0, 1) }
        until { router.received.any { it.number(M2Key.Command) == MEPTY_DATA && it.raw(2) == null && it.number(3) == 8L } }
        waiting.cancel()
        terminal.close()
    }

    @Test
    fun theAcknowledgementIsNotHeldBackByMessagesThatAreNoOutput() = session(consoleRouter(chatty = true)) { connection, router ->
        val terminal = NativeTerminal.open(connection, "secret", "vt102", 80, 24)
        assertEquals("banner> ", terminal.readText(8))
        val waiting = launch { terminal.read(ByteArray(1), 0, 1) }
        until { router.received.any { it.number(M2Key.Command) == MEPTY_DATA && it.raw(2) == null && it.number(3) == 8L } }
        waiting.cancel()
    }

    @Test
    fun theEndOfTheConsoleOnTheRouterEndsTheRead() = session({ message ->
        when (message.numbers(M2Key.To)) {
            listOf(13L, 4L) -> reply(message, 5 to M2Value.Text("info"))
            listOf(76L) -> when (message.number(M2Key.Command)) {
                MEPTY_LOGIN -> reply(message, M2Key.SessionId to M2Value.U8(3))
                MEPTY_DATA -> {
                    val to = M2Key.To to M2Value.U32s(longArrayOf(0, source(message)))
                    val from = M2Key.From to M2Value.U32s(longArrayOf(76))
                    send(M2Message.of(to, from, M2Key.SessionId to M2Value.U8(3), M2Key.Command to M2Value.U32(MEPTY_DATA), 2 to M2Value.Raw("bye".encodeToByteArray())))
                    send(M2Message.of(to, from, M2Key.SessionId to M2Value.U8(3), M2Key.Command to M2Value.U32(MEPTY_CLOSE)))
                }
            }
        }
    }) { connection, router ->
        val terminal = NativeTerminal.open(connection, "secret", "vt102", 80, 24)
        assertEquals("bye", terminal.readText(3))
        assertEquals(-1, terminal.read(ByteArray(8), 0, 8))
        assertEquals(-1, terminal.read(ByteArray(8), 0, 8))
        terminal.close()
        delay(200)
        assertEquals(0, router.received.count { it.number(M2Key.Command) == MEPTY_CLOSE })
    }

    @Test
    fun theWindowSizeAndTheEndOfTheConsoleGoToTheRouter() = session(consoleRouter()) { connection, router ->
        val terminal = NativeTerminal.open(connection, "secret", "vt102", 80, 24)
        terminal.resize(132, 43)
        until { router.received.any { it.number(M2Key.Command) == MEPTY_RESIZE } }
        val resize = router.received.single { it.number(M2Key.Command) == MEPTY_RESIZE }
        assertEquals(listOf(132L, 43L, 3L), listOf(resize.number(5), resize.number(6), resize.number(M2Key.SessionId)))
        terminal.close()
        until { router.received.any { it.number(M2Key.Command) == MEPTY_CLOSE } }
    }

    @Test
    fun aConsoleTheRouterRefusesIsAnError() = session({ message ->
        if (message.numbers(M2Key.To) == listOf(76L)) reply(message) else reply(message, 5 to M2Value.Text("info"))
    }) { connection, _ ->
        val error = assertFailsWith<DeviceException> { NativeTerminal.open(connection, "secret", "vt102", 80, 24) }
        assertEquals(ErrorCategory.PermissionDenied, error.category)
    }

    @Test
    fun theSshAdapterGivesTheConsoleAndTheFilesAndRefusesTheRest() = session(consoleRouter()) { connection, _ ->
        val ssh = NativeSsh.open({ connection }, "secret")
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { ssh.exec("ls") }.category)
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { ssh.process("ls") }.category)
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { ssh.forward("127.0.0.1", 8728) }.category)
        assertFalse(ssh.canForward)
        assertEquals("banner> ", ssh.shell("xterm", 80, 24).readText(8))
        assertTrue(ssh.sftp() is NativeFiles)
    }

    @Test
    fun aSessionTheRouterDroppedIsOpenedAgainForTheNextConsoleButNotOnceClosed() = runTest {
        val routers = ArrayList<FakeRouter>()
        val ssh = NativeSsh.open(
            {
                val (client, server) = pipe()
                routers += FakeRouter(this, server, "admin", "secret", consoleRouter())
                NativeConnection.open(client, "admin", "secret", this)
            },
            "secret",
        )
        try {
            assertEquals("banner> ", ssh.shell("xterm", 80, 24).readText(8))
            routers.single().close()
            delay(100)
            assertEquals("banner> ", ssh.shell("xterm", 80, 24).readText(8))
            assertEquals(2, routers.size)
            ssh.close()
            assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { ssh.shell("xterm", 80, 24) }.category)
            assertEquals(2, routers.size)
        } finally {
            ssh.close()
            routers.forEach { it.close() }
        }
    }

    @Test
    fun theWindowsGoOverTheConnectionOfTheConsoleWithoutClosingIt() = runTest {
        val routers = ArrayList<FakeRouter>()
        val ssh = NativeSsh.open(
            {
                val (client, server) = pipe()
                routers += FakeRouter(this, server, "admin", "secret", consoleRouter())
                NativeConnection.open(client, "admin", "secret", this)
            },
            "secret",
        )
        try {
            val side = ssh.side(18291)
            assertEquals(18291, side.sessionPort)
            assertEquals("banner> ", ssh.shell("xterm", 80, 24).readText(8))
            side.open(8291).close()
            assertTrue(ssh.connected)
            assertEquals(1, routers.size)
            routers.single().close()
            delay(100)
            side.open(8291).close()
            assertEquals("banner> ", ssh.shell("xterm", 80, 24).readText(8))
            assertEquals(2, routers.size)
        } finally {
            ssh.close()
            routers.forEach { it.close() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aRouterThatDropsEverySessionIsNotLoggedInToForEveryConsole() = runTest {
        val routers = ArrayList<FakeRouter>()
        val ssh = NativeSsh.open(
            {
                val (client, server) = pipe()
                val router = FakeRouter(this, server, "admin", "secret") { }
                routers += router
                NativeConnection.open(client, "admin", "secret", this).also { router.close() }
            },
            "secret",
            testTimeSource,
        )
        try {
            repeat(600) {
                delay(1.seconds)
                assertFailsWith<DeviceException> { ssh.shell("xterm", 80, 24) }
            }
            assertTrue(routers.size < 30, "${routers.size} logins for 600 consoles")
        } finally {
            ssh.close()
            routers.forEach { it.close() }
        }
    }

    private fun agentRouter(inner: suspend FakeRouter.(M2Message) -> Unit, failure: Long? = null): suspend FakeRouter.(M2Message) -> Unit = { message ->
        val to = message.numbers(M2Key.To).orEmpty()
        when {
            to == listOf(2L) && message.number(M2Key.Command) == 2001L ->
                if (failure != null) {
                    reply(message, M2Key.ErrorCode to M2Value.U32(failure), M2Key.Error to M2Value.Text("refused"))
                } else {
                    reply(message, M2Key.SessionId to M2Value.U32(7))
                }
            to.take(2) == listOf(2L, 7L) -> {
                val routed = M2Message(message.fields.map { if (it.key == M2Key.To) M2Field(it.key, M2Value.U32s(to.drop(2).toLongArray())) else it })
                inner(this, routed)
            }
        }
    }

    private val behind = RomonTarget("AA:BB:CC:DD:EE:FF", "admin", "behind")

    @Test
    fun aConsoleBehindTheAgentIsReachedThroughTheLink() = session(agentRouter(consoleRouter())) { connection, router ->
        connection.relay(behind)
        val connect = router.received.first { it.number(M2Key.Command) == 2001L }
        assertContentEquals(byteArrayOf(-86, -69, -52, -35, -18, -1), connect.raw(4))
        assertEquals("admin", connect.text(7))
        assertEquals("behind", connect.text(8))
        val terminal = NativeTerminal.open(connection, "behind", "vt102", 80, 24)
        assertEquals("banner> ", terminal.readText(8))
        terminal.write("ls".encodeToByteArray())
        assertEquals("echo:ls", terminal.readText(7))
        val sent = router.received.filter { it.number(M2Key.Command) != 2001L }
        assertTrue(sent.isNotEmpty() && sent.all { it.numbers(M2Key.To)?.take(2) == listOf(2L, 7L) }, sent.map { it.numbers(M2Key.To) }.toString())
    }

    @Test
    fun anAgentThatCannotReachTheTargetSaysSo() = session(agentRouter(consoleRouter(), failure = 0xFE0006)) { connection, _ ->
        val error = assertFailsWith<DeviceException> { connection.relay(behind) }
        assertEquals(ErrorCategory.ConnectionLost, error.category)
    }

    @Test
    fun anAgentThatTimesOutSaysSoAsWell() = session(agentRouter(consoleRouter(), failure = 0xFE000D)) { connection, _ ->
        val error = assertFailsWith<DeviceException> { connection.relay(behind) }
        assertEquals(ErrorCategory.ConnectionLost, error.category)
    }

    @Test
    fun aTargetThatRefusesTheLoginIsAPermissionError() = session(agentRouter(consoleRouter(), failure = 0xFE0009)) { connection, _ ->
        val error = assertFailsWith<DeviceException> { connection.relay(behind) }
        assertEquals(ErrorCategory.PermissionDenied, error.category)
    }

    @Test
    fun anIdThatIsNoRomonIdIsRefusedBeforeAnythingIsSent() {
        for (id in listOf("nonsense", "AA:BB:CC:DD:EE", "AA:BB:CC:DD:EE:FF:00", "AA:BB:CC:DD:EE:GG", "A:BB:CC:DD:EE:FF")) {
            assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { RomonTarget(id, "admin", "x") }.category, id)
        }
    }

    @Test
    fun theLogoutOfTheLinkEndsTheSessionsButTheLogoutOfAnotherSessionDoesNot() = session(agentRouter(consoleRouter())) { connection, router ->
        connection.relay(behind)
        val terminal = NativeTerminal.open(connection, "behind", "vt102", 80, 24)
        terminal.readText(8)
        val logout = { from: List<Long> ->
            M2Message.of(
                M2Key.To to M2Value.U32s(longArrayOf(0, 0)),
                M2Key.From to M2Value.U32s(from.toLongArray()),
                M2Key.Command to M2Value.U32(0xFE0014),
            )
        }
        router.send(logout(listOf(5)))
        router.send(logout(listOf(0xFF0003, 9)))
        terminal.write("still".encodeToByteArray())
        assertEquals("echo:still", terminal.readText(10))
        val waiting = async { runCatching { terminal.read(ByteArray(1), 0, 1) }.exceptionOrNull() }
        router.send(logout(listOf(0xFF0003, 7)))
        val error = waiting.await()
        assertEquals(ErrorCategory.ConnectionLost, (error as? DeviceException)?.category, error.toString())
    }

    private fun aLogoutNamingTheLink(from: List<Long>, ends: Boolean) = session(agentRouter(consoleRouter())) { connection, router ->
        connection.relay(behind)
        val terminal = NativeTerminal.open(connection, "behind", "vt102", 80, 24)
        terminal.readText(8)
        val waiting = async { runCatching { terminal.read(ByteArray(1), 0, 1) }.exceptionOrNull() }
        router.send(
            M2Message.of(
                M2Key.To to M2Value.U32s(longArrayOf()),
                M2Key.From to M2Value.U32s(from.toLongArray()),
                M2Key.Command to M2Value.U32(0xFE0014),
            ),
        )
        if (ends) {
            val error = waiting.await()
            assertEquals(ErrorCategory.ConnectionLost, (error as? DeviceException)?.category, error.toString())
        } else {
            terminal.write("still".encodeToByteArray())
            assertEquals("echo:still", terminal.readText(10))
            waiting.cancel()
        }
    }

    @Test
    fun aLogoutThatNamesTheLinkThroughTheProxyEndsTheSessionToo() = aLogoutNamingTheLink(listOf(2, 7, 99), ends = true)

    @Test
    fun aLogoutThatNamesTheLinkBehindTheKeyOfATypeAndTheProxyEndsTheSessionToo() = aLogoutNamingTheLink(listOf(0xFF0003, 2, 7), ends = true)

    @Test
    fun aLogoutThatNamesAnotherLinkOrOnlyTheProxyDoesNotEndTheSession() {
        aLogoutNamingTheLink(listOf(2, 9), ends = false)
        aLogoutNamingTheLink(listOf(0xFF0003, 2, 9), ends = false)
        aLogoutNamingTheLink(listOf(2), ends = false)
    }
}
