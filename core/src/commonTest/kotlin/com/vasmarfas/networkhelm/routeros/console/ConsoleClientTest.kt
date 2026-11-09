package com.vasmarfas.networkhelm.routeros.console

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.routeros.api.ApiConnectionClosedException
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.api.ApiTrapException
import com.vasmarfas.networkhelm.ssh.ShellChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PROMPT_TEXT = "\r\r\r\u001b[9999B\u001b[K[\u001b[m\u001b[36mtester\u001b[m@\u001b[m\u001b[32mCHR\u001b[m] > "
private val MARKERS = Regex("^:put \"(rd[0-9a-f]+-\\d+b)\"; (.*); :put \"(rd[0-9a-f]+-\\d+e)\"$")

private fun bytes(vararg parts: ByteArray) = parts.flatMap { it.asList() }.joinToString("") { (it.toInt() and 0xFF).toChar().toString() }

private fun latin1(text: String) = ByteArray(text.length) { text[it].code.toByte() }

private class FakeConsole(stale: String = "", banner: Boolean = true, private val bytesOf: (String) -> ByteArray = String::encodeToByteArray, probing: Boolean = false, private val asks: Boolean = true, private val handler: suspend (String) -> String) : ShellChannel {
    private val out = Channel<ByteArray>(Channel.UNLIMITED)
    var closed = false

    val answers = mutableListOf<String>()

    val history = mutableListOf<String>()

    private fun typed(line: String) {
        if (history.lastOrNull() != line) history += line
    }

    init {
        if (probing) out.trySend(bytesOf("\r\u001b[9999B\u001b[6n"))
        else if (banner) out.trySend(bytesOf("\r\n  MikroTik RouterOS\r\n$PROMPT_TEXT$stale"))
    }

    private var rest = ByteArray(0)

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (rest.isEmpty()) rest = out.receiveCatching().getOrNull() ?: return -1
        val size = minOf(length, rest.size)
        rest.copyInto(buffer, offset, 0, size)
        rest = rest.copyOfRange(size, rest.size)
        return size
    }

    override suspend fun write(bytes: ByteArray) {
        if (bytes[0] == 0x1B.toByte()) {
            answers += bytes.decodeToString()
            out.trySend(bytesOf("\r\n  MikroTik RouterOS\r\n$PROMPT_TEXT"))
            return
        }
        if (bytes.contentEquals(byteArrayOf(3))) {
            out.trySend(bytesOf("\rScript Error: action cancelled\n\r\r\r$PROMPT_TEXT"))
            return
        }
        if (bytes.contentEquals(byteArrayOf(0x18))) {
            out.trySend(bytesOf("\r\n[tester@CHR] <SAFE> "))
            return
        }
        if (bytes.decodeToString() == "n\r") {
            answers += "n"
            out.trySend(bytesOf("n\r\n$PROMPT_TEXT"))
            return
        }
        var line = bytes.decodeToString().trimEnd('\r')
        var relayed = ""
        if (line.startsWith("$RELAY\r")) {
            line = line.removePrefix("$RELAY\r")
            typed(RELAY)
            relayed = if (asks) "$RELAY\r\n\r$ASKING \r\n\r\r\r" else "$RELAY\r\n\rbad command name terminal (line 1 column 12)\r\n$PROMPT_TEXT"
            if (!asks) typed(line)
        } else {
            typed(line)
        }
        val (begin, command, end) = checkNotNull(MARKERS.matchEntire(line)).destructured
        val text = handler(command)
        val asking = text.startsWith("?")
        if (text == "~echo") {
            out.trySend(bytesOf(line.take(line.length / 2)))
            return
        }
        val silent = text == "~"
        val body = when {
            asking -> text.drop(1) + ": "
            silent -> ""
            text.startsWith("!") -> text.drop(1) + "\r\n"
            else -> "$text\r\n$end\r\n"
        }
        out.trySend(bytesOf("$relayed$line\u001b[12D\r\n\r$begin\r\n$body${if (asking || silent) "" else PROMPT_TEXT}"))
    }

    override fun resize(columns: Int, rows: Int) = Unit

    override fun close() {
        closed = true
        out.close()
    }
}

private val PICK = Regex("\\[:pick \\[[^\\]]*] (\\d+) (\\d+)]")

// answers a line one piece at a time, with a pause before each
private class DrippingConsole(private val chunks: Int, private val millis: Long) : ShellChannel {
    private val out = Channel<ByteArray>(Channel.UNLIMITED)

    init {
        out.trySend("\r\n  MikroTik RouterOS\r\n$PROMPT_TEXT".encodeToByteArray())
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val chunk = out.receiveCatching().getOrNull() ?: return -1
        if (chunk.size == 1) delay(millis)
        chunk.copyInto(buffer, offset)
        return chunk.size
    }

    override suspend fun write(bytes: ByteArray) {
        val line = bytes.decodeToString().trimEnd('\r').removePrefix("$RELAY\r")
        val (begin, _, end) = checkNotNull(MARKERS.matchEntire(line)).destructured
        out.trySend("$line\r\n\r$begin\r\n".encodeToByteArray())
        repeat(chunks) { out.trySend(".".encodeToByteArray()) }
        out.trySend("\r\n*1\r\n$end\r\n$PROMPT_TEXT".encodeToByteArray())
    }

    override fun resize(columns: Int, rows: Int) = Unit

    override fun close() = out.close().let { }
}

// waits for a busy console as RouterOS 7.25 does: the question comes a second after the line, the line runs when the console is back
private class BusyConsole(private val typed: MutableList<String>, private val reply: String = "*4A", private val pauseMillis: Long = 11_000) : ShellChannel {
    private val out = Channel<ByteArray>(Channel.UNLIMITED)

    init {
        out.trySend("\r\n  MikroTik RouterOS\r\n$PROMPT_TEXT".encodeToByteArray())
    }

    private var rest = ByteArray(0)

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (rest.isEmpty()) {
            rest = out.receiveCatching().getOrNull() ?: return -1
            if (rest.isEmpty()) {
                delay(pauseMillis)
                rest = out.receiveCatching().getOrNull() ?: return -1
            }
        }
        val size = minOf(length, rest.size)
        rest.copyInto(buffer, offset, 0, size)
        rest = rest.copyOfRange(size, rest.size)
        return size
    }

    override suspend fun write(bytes: ByteArray) {
        val line = bytes.decodeToString().trimEnd('\r').removePrefix("$RELAY\r")
        typed += line
        val (begin, command, end) = MARKERS.matchEntire(line)?.destructured ?: return
        if (command.startsWith(":global rdrow")) {
            out.trySend("$line\r\n\r$begin\r\n$end\r\n$PROMPT_TEXT".encodeToByteArray())
            return
        }
        out.trySend("$line\r\nConsole does not respond.\r\nRestart console (this will terminate all active sessions)? [y/N] ".encodeToByteArray())
        out.trySend(ByteArray(0))
        out.trySend("\r\nConsole responded, action cancelled\r\n\r$begin\r\n$reply\r\n$end\r\n$PROMPT_TEXT".encodeToByteArray())
    }

    override fun resize(columns: Int, rows: Int) = Unit

    override fun close() = out.close().let { }
}

class ConsoleClientTest {
    private val consoles = mutableListOf<FakeConsole>()
    private val deaf = mutableListOf<Deaf>()

    // answers the login and then nothing: a busy process takes what is typed later
    private class Deaf : ShellChannel {
        private val out = Channel<ByteArray>(Channel.UNLIMITED).also { it.trySend("\r\n  MikroTik RouterOS\r\n$PROMPT_TEXT".encodeToByteArray()) }
        var closed = false

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val bytes = out.receiveCatching().getOrNull() ?: return -1
            bytes.copyInto(buffer, offset)
            return bytes.size
        }

        override suspend fun write(bytes: ByteArray) = Unit

        override fun resize(columns: Int, rows: Int) = Unit

        override fun close() {
            closed = true
            out.close()
        }
    }

    private fun client(size: Int = 4, handler: suspend (String) -> String) = ConsoleClient(size) { FakeConsole(handler = handler).also { consoles += it } }

    @Test
    fun aPrintBecomesRowsAndTheSettingsAreOneRow() = runTest {
        val client = client { command ->
            when {
                "/ip/address/print" in command -> """[{".id":"*1","address":"192.0.2.1/24","disabled":false}]"""
                "/system/identity/print" in command -> """{"name":"CHR"}"""
                else -> "!unexpected $command"
            }
        }
        assertEquals(listOf(mapOf(".id" to "*1", "address" to "192.0.2.1/24", "disabled" to "false")), client.call(listOf("/ip/address/print", "?disabled=false")).rows)
        assertEquals(listOf(mapOf("name" to "CHR")), client.call(listOf("/system/identity/print")).rows)
    }

    private fun router(sent: MutableList<String>, refuses: Set<String>, vararg views: String): suspend (String) -> String = handler@{ command ->
        if ("count-only" in command) return@handler "!bad parameter count-only (line 1 column 25)"
        sent += command
        val parts = command.split("; ")
        val failing = parts.indexOfFirst { part -> part.split(' ', ']').any { it.substringBefore('=') in refuses } }
        if (failing < 0) views.take(parts.size).joinToString("\n")
        else "!" + (views.take(failing) + "bad parameter ${parts[failing].split(' ', ']').first { it.substringBefore('=') in refuses }.substringBefore('=')} (line 1 column 120)").joinToString("\n")
    }

    @Test
    fun theViewsOfARowAreOneRow() = runTest {
        val client = client(handler = router(mutableListOf(), emptySet(),
            "[{\".id\":\"*1\",\"chain\":\"input\"},{\".id\":\"*2\",\"chain\":\"forward\"}]",
            "[{\".id\":\"*1\",\"dst-port\":\"25\"}]",
            "[{\".id\":\"*1\",\"bytes\":10},{\".id\":\"*2\",\"bytes\":0}]",
        ))
        assertEquals(
            listOf(mapOf(".id" to "*1", "chain" to "input", "dst-port" to "25", "bytes" to "10"), mapOf(".id" to "*2", "chain" to "forward", "bytes" to "0")),
            client.call(listOf("/ip/firewall/filter/print")).rows,
        )
    }

    @Test
    fun aMenuIsAskedAgainWithoutTheWordsItRefusesAndRemembersThem() = runTest {
        val sent = mutableListOf<String>()
        val client = client(size = 1, handler = router(sent, setOf("show-sensitive", "stats-detail"),
            "[{\".id\":\"*2\",\"address\":\"192.0.2.1/24\"}]", "[{\".id\":\"*2\",\"actual-interface\":\"ether1\"}]", "[{\".id\":\"*2\",\"bytes\":5}]",
        ))
        val address = listOf("/ip/address/print")
        val row = mapOf(".id" to "*2", "address" to "192.0.2.1/24", "actual-interface" to "ether1", "bytes" to "5")
        assertEquals(listOf(row), client.call(address).rows)
        assertEquals(3, sent.size)
        assertEquals(listOf(row), client.call(address).rows)
        assertEquals(4, sent.size)
        assertEquals(sent[2], sent[3])
    }

    @Test
    fun aMenuThatRefusesEveryWordIsAskedPlain() = runTest {
        val sent = mutableListOf<String>()
        val client = client(size = 1, handler = router(sent, setOf("detail", "show-sensitive", "stats-detail", "stats"), "{\"name\":\"CHR\"}"))
        val identity = listOf("/system/identity/print")
        assertEquals(listOf(mapOf("name" to "CHR")), client.call(identity).rows)
        assertEquals(5, sent.size)
        assertEquals(listOf(mapOf("name" to "CHR")), client.call(identity).rows)
        assertEquals(6, sent.size)
        assertEquals(":put [:serialize to=json options=json.no-string-conversion [/system/identity/print as-value]]", sent.last())
    }

    @Test
    fun aMenuOfSettingsThatRefusesProplistIsAskedWithoutItAndTheRowIsCutToWhatWasAskedFor() = runTest {
        val sent = mutableListOf<String>()
        val client = client(size = 1, handler = router(sent, setOf("proplist", "detail", "show-sensitive", "stats-detail", "stats"), "{\"uptime\":\"1d\",\"cpu-load\":3,\"version\":\"7.26\"}"))
        val resource = listOf("/system/resource/print", "=.proplist=.id,uptime,cpu-load")
        val row = mapOf("uptime" to "1d", "cpu-load" to "3")
        assertEquals(listOf(row), client.call(resource).rows)
        assertTrue("proplist=uptime,cpu-load" in sent.first())
        assertFalse("proplist" in sent.last())
        val asked = sent.size
        assertEquals(listOf(row), client.call(resource).rows)
        assertEquals(asked + 1, sent.size)
    }

    @Test
    fun aRefusalOfAWordThatTheLineDidNotOfferEndsTheAttempts() = runTest {
        val sent = mutableListOf<String>()
        val client = client { command ->
            sent += command
            if ("/ip/address" in command) "!bad parameter foo (line 1 column 112)" else "!expected end of command (line 1 column 20)"
        }
        assertEquals(1, assertFailsWith<ApiTrapException> { client.call(listOf("/ip/address/print", "=foo=1")) }.category)
        assertEquals(0, assertFailsWith<ApiTrapException> { client.call(listOf("/ip/route/print")) }.category)
        assertEquals(2, sent.count { "count-only" !in it })
    }

    @Test
    fun theMessagesOfTheLogThatTheRouterPrintsIntoTheConsoleAreNotTakenForAnAnswer() = runTest {
        val message = "22:10:36 echo: system,error,critical login failure for user admin from 192.0.2.9 via winbox"
        val client = ConsoleClient(1) { FakeConsole("\r\n$message\r\n$PROMPT_TEXT") { "$message\r\n[{\"name\":\"CHR\"}]" }.also { consoles += it } }
        assertEquals(listOf(mapOf("name" to "CHR")), client.call(listOf("/system/identity/print")).rows)
        assertEquals(listOf(mapOf("name" to "CHR")), client.call(listOf("/system/identity/print")).rows)
    }

    @Test
    fun anAddAnswersWithItsIdAndACountWithItsNumber() = runTest {
        val client = client { command -> if ("/add " in command) "*4A" else "3" }
        assertEquals(mapOf("ret" to "*4A"), client.call(listOf("/ip/address/add", "=address=192.0.2.1/24")).done)
        assertEquals(mapOf("ret" to "3"), client.call(listOf("/ip/address/print", "=count-only=")).done)
        assertEquals(emptyMap(), client.call(listOf("/ip/address/remove", "=.id=*4A")).done)
    }

    @Test
    fun theRoutersMessagesBecomeTraps() = runTest {
        val client = client { command -> if ("*FF" in command) "!no such item (4)" else "!syntax error (line 1 column 16)" }
        assertEquals(0, assertFailsWith<ApiTrapException> { client.call(listOf("/ip/address/set", "=.id=*FF", "=comment=x")) }.category)
        assertEquals("syntax error (line 1 column 16)", assertFailsWith<ApiTrapException> { client.call(listOf("/nope/print")) }.message)
    }

    @Test
    fun aLineThatLacksAnArgumentIsGivenUpAndTheConsoleGoesOn() = runTest {
        val client = client(size = 1) { command -> if ("/add " in command) "?address" else "[]" }
        val refusal = assertFailsWith<ApiTrapException> { client.call(listOf("/ip/address/add", "=interface=ether1")) }
        assertEquals("missing value(s) of argument(s) address", refusal.message)
        assertEquals(1, refusal.category)
        assertEquals(emptyList(), client.call(listOf("/ip/address/print")).rows)
        assertEquals(1, consoles.size)
    }

    @Test
    fun theHistoryOfTheRouterGetsOneLineHoweverManyCommandsRun() = runTest {
        val client = client(size = 1) { "[]" }
        repeat(3) { client.call(listOf("/ip/address/print")) }
        client.call(listOf("/ip/route/print"))
        assertEquals(listOf(RELAY), consoles.single().history)
    }

    @Test
    fun aRouterWithoutAskIsTypedTheLinesAndIsTriedOnlyOnce() = runTest {
        val client = ConsoleClient(1) { FakeConsole(asks = false) { "[]" }.also { consoles += it } }
        repeat(3) { client.call(listOf("/ip/address/print")) }
        val history = consoles.single().history
        assertEquals(1, history.count { it == RELAY })
        assertTrue(history.size > 1)
    }

    @Test
    fun aBooleanTheRouterRefusesIsSentAgainAsYesOrNo() = runTest {
        val sent = mutableListOf<String>()
        val client = client { command ->
            sent += command
            when {
                "flag=\"true\"" in command -> "!invalid value of flag, must be either yes or no"
                else -> ""
            }
        }
        client.call(listOf("/ip/x/set", "=.id=*1", "=flag=true"))
        client.call(listOf("/ip/x/set", "=.id=*1", "=flag=false"))
        assertEquals(listOf("/ip/x/set numbers=*1 flag=\"true\"", "/ip/x/set numbers=*1 flag=yes", "/ip/x/set numbers=*1 flag=no"), sent)
    }

    @Test
    fun aCommandThatTakesNoAsValueIsRunPlain() = runTest {
        val sent = mutableListOf<String>()
        val client = client { command ->
            sent += command
            if ("as-value" in command) "!bad parameter as-value (line 1 column 57)" else "done"
        }
        assertEquals(mapOf("ret" to "done"), client.call(listOf("/ip/address/make-static", "=.id=*1")).done)
        assertEquals(2, sent.size)
        assertEquals("/ip/address/make-static numbers=*1", sent.last())
    }

    private fun classic(handler: suspend (String) -> String) = ConsoleClient(1, Dialect.Classic) { FakeConsole(handler = handler).also { consoles += it } }

    @Test
    fun aConsoleOfRouterOs6IsGivenTheFunctionOfTheClientAndItsRowsAreRecords() = runTest {
        val sent = mutableListOf<String>()
        val client = classic { command ->
            sent += command
            when {
                command.startsWith(":global rdrow") -> ""
                "find" in command -> "\u0001.id\u0002id\u0002*1\n\u0001address\u0002str\u0002192.0.2.1/24\n\u0001disabled\u0002bool\u0002false\n\u0003"
                else -> "!unexpected $command"
            }
        }
        assertEquals(listOf(mapOf(".id" to "*1", "address" to "192.0.2.1/24", "disabled" to "false")), client.call(listOf("/ip/address/print")).rows)
        assertEquals(listOf(PRELUDE, "/ip address print count-only"), sent.take(2))
        assertEquals(3, sent.size)
    }

    @Test
    fun aMenuOfSettingsIsRememberedAfterItsRefusalOfFind() = runTest {
        val sent = mutableListOf<String>()
        val client = classic { command ->
            sent += command
            when {
                command.startsWith(":global rdrow") -> ""
                "find" in command -> "!bad command name find (line 1 column 27)"
                else -> "\u0001allow-remote-requests\u0002bool\u0002false\n\u0001servers\u0002array\u0002\n\u0003"
            }
        }
        val dns = listOf("/ip/dns/print")
        assertEquals(listOf(mapOf("allow-remote-requests" to "false", "servers" to "")), client.call(dns).rows)
        assertEquals(1, client.call(dns).rows.size)
        assertEquals(1, sent.count { "find" in it })
        assertEquals(2, sent.count { it.endsWith("[/ip dns get]") })
    }

    @Test
    fun aConsoleOfRouterOs6ThatFallsSilentIsGivenUpAndTheQuickLineIsAskedAgain() = runTest {
        var silent = true
        val client = classic { command ->
            when {
                command.startsWith(":global rdrow") -> ""
                silent -> "~".also { silent = false }
                else -> "\u0001.id\u0002id\u0002*1\n\u0003"
            }
        }
        assertEquals(listOf(mapOf(".id" to "*1")), client.call(listOf("/ip/address/print")).rows)
        assertEquals(2, consoles.size)
        assertTrue(consoles.first().closed)
        assertEquals(LinkState.Up, client.status.first())
    }

    @Test
    fun aConsoleThatSaysNothingWhenItIsOpenedIsGivenUpAndAnotherIsOpened() = runTest {
        var first = true
        val client = ConsoleClient(1, Dialect.Classic) {
            FakeConsole(banner = !first.also { first = false }) { command -> if (command.startsWith(":global rdrow")) "" else "\u0001.id\u0002id\u0002*1\n\u0003" }.also { consoles += it }
        }
        assertEquals(listOf(mapOf(".id" to "*1")), client.call(listOf("/ip/address/print")).rows)
        assertEquals(2, consoles.size)
        assertTrue(consoles.first().closed)
    }

    @Test
    fun aCountOfAMenuWithoutAListIsAnUnknownParameterAsTheApiSaysIt() = runTest {
        val client = classic { command -> if (command.startsWith(":global")) "" else "!expected end of command (line 1 column 27)" }
        assertEquals("unknown parameter", assertFailsWith<ApiTrapException> { client.call(listOf("/ip/dns/print", "=count-only=")) }.message)
    }

    @Test
    fun theFlagsOfRowsThatWereSeenAreSentAsYesAndNo() = runTest {
        val sent = mutableListOf<String>()
        val client = classic { command ->
            sent += command
            if ("find" in command) "\u0001.id\u0002id\u0002*1\n\u0001passive\u0002bool\u0002false\n\u0003" else ""
        }
        client.call(listOf("/ip/x/set", "=.id=*1", "=passive=true"))
        client.call(listOf("/ip/x/print"))
        client.call(listOf("/ip/x/set", "=.id=*1", "=passive=true"))
        assertEquals(listOf(":put [/ip x set numbers=*1 passive=\"true\"]", ":put [/ip x set numbers=*1 passive=yes]"), sent.filter { it.startsWith(":put") })
    }

    @Test
    fun callsRunOnSeveralConsolesAtOnceAndNoMoreThanTheSizeOfThePool() = runTest {
        val client = client(size = 3) { command ->
            delay(50)
            "[{\"n\":\"$command\"}]"
        }
        val answers = (1..9).map { index -> async { client.call(listOf("/menu$index/print")).rows.single()["n"] } }.awaitAll()
        assertEquals(9, answers.toSet().size)
        assertEquals(3, consoles.size)
    }

    @Test
    fun aConsoleThatEndsFailsItsCallAndTheNextCallOpensAnother() = runTest {
        var first = true
        val client = ConsoleClient(1) {
            FakeConsole { "[]" }.also {
                consoles += it
                if (first) {
                    first = false
                    it.close()
                }
            }
        }
        assertFailsWith<ApiConnectionClosedException> { client.call(listOf("/ip/address/print")) }
        assertEquals(LinkState.Reconnecting, client.status.first())
        assertEquals(emptyList(), client.call(listOf("/ip/address/print")).rows)
        assertEquals(LinkState.Up, client.status.first())
        assertEquals(2, consoles.size)
    }

    @Test
    fun aTableThatTheRouterKeepsInCp1251IsReadByTheEncodingOfTheRouter() = runTest {
        val table = bytes("[{\".id\":\"*1\",\"comment\":\"".encodeToByteArray(), ApiEncoding.Windows1251.encode("Привет"), "\"}]".encodeToByteArray())
        val client = ConsoleClient(1, encoding = ApiEncoding.Windows1251) { FakeConsole(bytesOf = ::latin1) { table }.also { consoles += it } }
        assertEquals("Привет", client.call(listOf("/ip/address/print")).rows.single()["comment"])
    }

    @Test
    fun aByteThatIsNotUtf8IsCp1251UnderTheUtf8EncodingWithoutSpoilingTheRestOfTheTable() = runTest {
        val table = bytes("[{\"a\":\"".encodeToByteArray(), "при".encodeToByteArray(), "\",\"b\":\"".encodeToByteArray(), ApiEncoding.Windows1251.encode("при"), "\"}]".encodeToByteArray())
        val client = ConsoleClient(1) { FakeConsole(bytesOf = ::latin1) { table }.also { consoles += it } }
        assertEquals(mapOf("a" to "при", "b" to "при"), client.call(listOf("/ip/address/print")).rows.single())
    }

    @Test
    fun aConsoleThatAsksWhereTheCursorIsGetsTheAnswerBeforeItDrawsItsPrompt() = runTest {
        val client = ConsoleClient(1) { FakeConsole(probing = true) { "{\"name\":\"CHR\"}" }.also { consoles += it } }
        assertEquals("CHR", client.call(listOf("/system/identity/print")).rows.single()["name"])
        assertEquals(listOf("\u001b[100;1R"), consoles.single().answers)
    }

    @Test
    fun aValueIsTypedByTheCodesOfTheEncodingOfTheRouter() = runTest {
        val sent = mutableListOf<String>()
        val client = ConsoleClient(1, encoding = ApiEncoding.Windows1251) { FakeConsole { command -> sent += command; "*1" }.also { consoles += it } }
        assertEquals("*1", client.call(listOf("/ip/address/add", "=comment=Привет")).done["ret"])
        assertTrue("comment=\"\\CF\\F0\\E8\\E2\\E5\\F2\"" in sent.single())
    }

    @Test
    fun aScriptWithACharacterThatTheEncodingHasNoCodeForIsRefusedBeforeItIsTyped() = runTest {
        val client = ConsoleClient(1, encoding = ApiEncoding.Windows1251) { FakeConsole { "" }.also { consoles += it } }
        val refusal = assertFailsWith<DeviceException> { client.call(listOf("/execute", "=script=:put \"😀\"")) }
        assertEquals(ErrorCategory.ArgumentValue, refusal.category)
        assertEquals(LinkState.Up, client.status.first())
    }

    @Test
    fun aCallerThatGivesUpIsNotTakenForTheLossOfTheLink() = runTest {
        val client = client { delay(10_000); "[]" }
        assertFailsWith<TimeoutCancellationException> { withTimeout(1_000) { client.call(listOf("/ip/address/print")) } }
        assertEquals(LinkState.Up, client.status.first())
    }

    @Test
    fun aTransportThatDroppedIsWaitedForAndTheClientIsUpWhenItIsBack() = runTest {
        var back = false
        val client = ConsoleClient(1) {
            if (!back) throw DeviceException(ErrorCategory.ConnectionLost, "connection closed")
            FakeConsole { "[]" }.also { consoles += it }
        }
        assertFailsWith<ApiConnectionClosedException> { client.call(listOf("/ip/address/print")) }
        assertEquals(LinkState.Reconnecting, client.status.first())
        back = true
        assertEquals(emptyList(), client.call(listOf("/ip/address/print")).rows)
        assertEquals(LinkState.Up, client.status.first())
    }

    @Test
    fun aLostLinkComesBackThoughNoWindowAsksForAnything() = runTest {
        var back = false
        val client = ConsoleClient(1) {
            if (!back) error("failed to connect after 10000ms")
            FakeConsole { "{\"name\":\"CHR\"}" }.also { consoles += it }
        }
        client.warm(backgroundScope)
        assertFailsWith<ApiConnectionClosedException> { client.call(listOf("/ip/address/print")) }
        assertEquals(LinkState.Reconnecting, client.status.first())
        back = true
        client.status.first { it == LinkState.Up }
        client.close()
    }

    @Test
    fun aConnectionThatWentDownIsNoticedWithoutAnyCallAndOpenedAgain() = runTest {
        var up = true
        val client = ConsoleClient(1, alive = { up }) { FakeConsole { "{\"name\":\"CHR\"}" }.also { consoles += it } }
        client.warm(backgroundScope)
        client.call(listOf("/system/identity/print"))
        up = false
        client.status.first { it == LinkState.Reconnecting }
        assertTrue(consoles.single().closed)
        up = true
        client.status.first { it == LinkState.Up }
        assertEquals(2, consoles.size)
        client.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theConsolesParkedInThePoolGoWithTheOneThatWasLost() = runTest {
        val client = client(2) { "[]" }
        client.warm(backgroundScope)
        runCurrent()
        client.call(listOf("/ip/address/print"))
        assertEquals(2, consoles.size)
        consoles.forEach { it.close() }
        assertFailsWith<ApiConnectionClosedException> { client.call(listOf("/ip/address/print")) }
        assertEquals(emptyList(), client.call(listOf("/ip/address/print")).rows)
        assertEquals(3, consoles.size)
        client.close()
    }

    @Test
    fun listenIsRefusedSoTheDriverPollsAndAClosedClientRefusesEverything() = runTest {
        val client = client { "[]" }
        assertFailsWith<ApiTrapException> { client.command(listOf("/ip/address/listen")).toList() }
        assertEquals(emptyList(), client.command(listOf("/tool/traceroute", "=address=127.0.0.1")).toList())
        client.call(listOf("/ip/address/print"))
        client.close()
        assertTrue(consoles.all { it.closed })
        assertFailsWith<ApiConnectionClosedException> { client.call(listOf("/ip/address/print")) }
    }

    @Test
    fun aCommandThatNeverEndsIsAskedARoundAtATime() = runTest {
        val sent = mutableListOf<String>()
        val client = client { command ->
            sent += command
            """[{".id":"*FFFFFFFE","name":"total","usage":${listOf(253, 170, 5)[sent.size - 1]}}]"""
        }
        val replies = client.command(listOf("/tool/profile")).take(3).toList()
        assertEquals(listOf("0", "1", "2"), replies.map { it.attributes[".section"] })
        assertEquals(listOf("25.3", "17", "0.5"), replies.map { it.attributes["usage"] })
        assertEquals(List(3) { ":put [:serialize to=json options=json.no-string-conversion [/tool/profile duration=3 as-value]]" }, sent)
    }

    @Test
    fun aPingKeepsTheMicrosecondsTheSerializedRowLoses() = runTest {
        val sent = mutableListOf<String>()
        val client = client { command ->
            sent += command
            ".id=*0;host=127.0.0.1;seq=0;size=56;time=00:00:00.0000${60 + sent.size};ttl=64"
        }
        val replies = client.command(listOf("/tool/ping", "=address=127.0.0.1", "=count=2")).toList()
        assertEquals(List(2) { ":put [/tool/ping address=127.0.0.1 count=1 as-value]" }, sent)
        assertEquals(listOf("61us", "62us"), replies.map { it.attributes["time"] })
        assertEquals(listOf("0", "1"), replies.map { it.attributes["seq"] })
        assertEquals("2", replies.last().attributes["received"])
    }

    @Test
    fun aLineTooLongForTheConsoleIsRefusedBeforeItIsSent() = runTest {
        val client = client { "[]" }
        val refusal = assertFailsWith<DeviceException> { client.call(listOf("/ip/address/add", "=comment=" + "x".repeat(20_000))) }
        assertEquals(ErrorCategory.ArgumentValue, refusal.category)
        assertTrue(consoles.isEmpty())
    }

    @Test
    fun aRouterThatAsksForANewPasswordIsLeftWithCtrlCAndTheClientSaysSo() = runTest {
        val client = ConsoleClient(1) {
            FakeConsole(stale = "\r\nChange your password (Ctrl-C to skip)\r\nnew password> ") { """{"name":"CHR"}""" }.also { consoles += it }
        }
        assertFalse(client.passwordExpired)
        assertEquals(listOf(mapOf("name" to "CHR")), client.call(listOf("/system/identity/print")).rows)
        assertTrue(client.passwordExpired)
    }

    @Test
    fun aRouterThatShowsItsLicenseFirstIsToldNo() = runTest {
        val client = ConsoleClient(1) {
            FakeConsole(stale = "\r\nDo you want to see the software license? [Y/n]: ") { """{"name":"CHR"}""" }.also { consoles += it }
        }
        assertEquals(listOf(mapOf("name" to "CHR")), client.call(listOf("/system/identity/print")).rows)
        assertEquals(listOf("n"), consoles.single().answers)
    }

    // without free memory to tell, the router refuses to print the whole table
    private fun table(size: Int, sent: MutableList<String> = mutableListOf(), refuses: String? = null, free: Long? = null): suspend (String) -> String = handler@{ command ->
        sent += command
        val pick = PICK.find(command)
        fun printed(row: (Int) -> String, range: IntRange) = Regex(":serialize").findAll(command).joinToString("\n") { range.joinToString(",", "[", "]", transform = row) }
        when {
            "/system/resource" in command -> if (free == null) "!bad command name resource (line 1 column 9)" else printed({ "{\"free-memory\":\"$free\"}" }, 0..0)
            refuses != null && refuses in command -> "!bad parameter $refuses (line 1 column 120)"
            "count-only" in command -> "$size"
            pick != null -> {
                val (from, to) = pick.destructured
                printed({ "{\".id\":\"*$it\"}" }, from.toInt() until minOf(to.toInt(), size))
            }
            free != null -> printed({ "{\".id\":\"*$it\"}" }, 0 until size)
            else -> "!not in portions: $command"
        }
    }

    private val addresses = listOf("/ip/firewall/address-list/print")

    @Test
    fun aBigTableComesInPortionsAndTheRouterNeverBuildsItWhole() = runTest {
        val sent = mutableListOf<String>()
        val client = client(handler = table(5_000, sent))
        val progress = mutableListOf<Pair<Int, Int>>()
        val rows = client.table(addresses) { read, total -> progress += read to total }
        assertEquals((0 until 5_000).map { "*$it" }, rows.map { it[".id"] })
        assertEquals(listOf(0 to 5_000, 2_000 to 5_000, 4_000 to 5_000, 5_000 to 5_000), progress)
        assertEquals("/ip/firewall/address-list/print count-only detail show-sensitive stats-detail", sent.first())
        assertTrue(sent.drop(1).filter { "address-list" in it }.all { "[:pick [/ip/firewall/address-list/find] " in it && " from=\$part" in it })
    }

    @Test
    fun aBigTableTheRouterHasTheMemoryForIsReadAtOnceAfterItsFirstPortion() = runTest {
        val sent = mutableListOf<String>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val rows = client(handler = table(5_000, sent, free = 1_000_000_000)).table(addresses) { read, total -> progress += read to total }
        assertEquals((0 until 5_000).map { "*$it" }, rows.map { it[".id"] })
        assertEquals(1, sent.count { ":pick" in it })
        assertTrue("[/ip/firewall/address-list/print as-value detail show-sensitive]" in sent.last())
        assertEquals(listOf(0 to 5_000), progress)
    }

    @Test
    fun aCountOfABigTableIsOneLine() = runTest {
        val sent = mutableListOf<String>()
        assertEquals(mapOf("ret" to "100000"), client(handler = table(100_000, sent)).call(addresses + "=count-only=").done)
        assertEquals(1, sent.size)
    }

    @Test
    fun aBigTableReadAtOnceIsReadAtOnceAgainWithoutAPortion() = runTest {
        val sent = mutableListOf<String>()
        val client = client(handler = table(5_000, sent, free = 1_000_000_000))
        assertEquals(5_000, client.call(addresses).rows.size)
        assertEquals(5_000, client.call(addresses).rows.size)
        assertEquals(1, sent.count { ":pick" in it })
    }

    @Test
    fun aBigTableIsReadWithoutThePlainViewOnceItsFirstPortionShowsThatDetailHasEveryColumnOfIt() = runTest {
        val sent = mutableListOf<String>()
        assertEquals(5_000, client(handler = table(5_000, sent)).call(addresses).rows.size)
        val portions = sent.filter { ":pick" in it }
        assertTrue("/ip/firewall/address-list/print as-value from=\$part" in portions.first())
        assertTrue(portions.drop(1).none { "/ip/firewall/address-list/print as-value from=\$part" in it })
    }

    @Test
    fun aPlainViewWithAColumnDetailLacksStaysInEveryPortion() = runTest {
        val sent = mutableListOf<String>()
        val client = client { command ->
            sent += command
            val pick = PICK.find(command)
            when {
                "count-only" in command -> "5000"
                pick == null -> "!bad command name resource (line 1 column 9)"
                else -> {
                    val (from, to) = pick.destructured
                    Regex(":serialize").findAll(command).withIndex().joinToString("\n") { (view, _) ->
                        (from.toInt() until minOf(to.toInt(), 5_000)).joinToString(",", "[", "]") { if (view == 0) "{\".id\":\"*$it\",\"total\":\"1\"}" else "{\".id\":\"*$it\"}" }
                    }
                }
            }
        }
        val rows = client.call(addresses).rows
        assertEquals(5_000, rows.size)
        assertTrue(rows.all { it["total"] == "1" })
        assertTrue(sent.filter { ":pick" in it }.all { "/ip/firewall/address-list/print as-value from=\$part" in it })
    }

    @Test
    fun aBigTableTheRouterHasNoMemoryForGoesOnInPortions() = runTest {
        val sent = mutableListOf<String>()
        assertEquals(5_000, client(handler = table(5_000, sent, free = 1_000_000)).call(addresses).rows.size)
        assertEquals(3, sent.count { ":pick" in it })
    }

    @Test
    fun theCallOfABigTableGoesInPortionsToo() = runTest {
        val sent = mutableListOf<String>()
        assertEquals(5_000, client(handler = table(5_000, sent)).call(addresses).rows.size)
        assertEquals(5, sent.size)
    }

    @Test
    fun aHugeTableTakesNoMoreThanSeventeenPortions() = runTest {
        val sent = mutableListOf<String>()
        assertEquals(100_000, client(handler = table(100_000, sent)).call(addresses).rows.size)
        assertTrue(sent.count { ":pick" in it } <= 17)
    }

    @Test
    fun aQueryIsCountedAndFoundAsTheRowsAreAsked() = runTest {
        val sent = mutableListOf<String>()
        client(handler = table(3_000, sent)).call(addresses + "?list=rd-test")
        assertEquals("/ip/firewall/address-list/print count-only detail show-sensitive stats-detail where list=\"rd-test\"", sent.first())
        assertTrue("[/ip/firewall/address-list/find where list=\"rd-test\"]" in sent[1])
    }

    @Test
    fun nothingIsTypedInOtherConsolesWhileAPortionRuns() = runTest {
        val sent = mutableListOf<String>()
        val portion = CompletableDeferred<Unit>()
        val rows = table(2_500)
        val client = client(size = 2) { command ->
            if (":pick" in command) portion.await()
            sent += command
            if ("identity" in command) "{\"name\":\"CHR\"}" else rows(command)
        }
        val big = async { client.call(addresses).rows }
        testScheduler.advanceUntilIdle()
        val other = async { client.call(listOf("/system/identity/print", "?name=CHR")).rows }
        testScheduler.advanceUntilIdle()
        assertFalse(sent.any { "identity" in it })
        portion.complete(Unit)
        assertEquals(2_500, big.await().size)
        assertEquals(1, other.await().size)
    }

    @Test
    fun nothingIsTypedInOtherConsolesWhileATableIsReadAtOnce() = runTest {
        val sent = mutableListOf<String>()
        val whole = CompletableDeferred<Unit>()
        val rows = table(2_500, free = 1_000_000_000)
        val client = client(size = 2) { command ->
            if ("address-list/print as-value detail show-sensitive]" in command) whole.await()
            sent += command
            if ("identity" in command) "{\"name\":\"CHR\"}" else rows(command)
        }
        val big = async { client.call(addresses).rows }
        testScheduler.advanceUntilIdle()
        val other = async { client.call(listOf("/system/identity/print", "?name=CHR")).rows }
        testScheduler.advanceUntilIdle()
        assertFalse(sent.any { "identity" in it })
        whole.complete(Unit)
        assertEquals(2_500, big.await().size)
        assertEquals(1, other.await().size)
    }

    @Test
    fun aRowAskedByItsIdIsNotCounted() = runTest {
        val sent = mutableListOf<String>()
        val client = client { command ->
            sent += command
            "[{\".id\":\"*1\"}]"
        }
        client.call(addresses + "?.id=*1")
        assertEquals(1, sent.size)
    }

    @Test
    fun aSmallTableIsOneLineAndCountedOnce() = runTest {
        val sent = mutableListOf<String>()
        val client = client { command ->
            sent += command
            if ("count-only" in command) "3" else "[{\".id\":\"*1\"}]"
        }
        client.call(addresses)
        client.call(addresses)
        assertEquals(1, sent.count { "count-only" in it })
        assertEquals(3, sent.size)
        assertFalse(sent.any { ":pick" in it })
    }

    @Test
    fun aSmallTableThatGrewIsCountedAgain() = runTest {
        val sent = mutableListOf<String>()
        var count = 3
        val client = client { command ->
            sent += command
            when {
                "count-only" in command -> "$count"
                ":pick" in command -> "[{\".id\":\"*1\"}]"
                else -> "[" + (1..count).joinToString(",") { "{\".id\":\"*$it\"}" } + "]"
            }
        }
        client.call(addresses)
        count = 2_500
        client.call(addresses)
        assertEquals(1, sent.count { "count-only" in it })
        client.call(addresses)
        assertEquals(2, sent.count { "count-only" in it })
        assertTrue(":pick" in sent.last())
    }

    @Test
    fun aViewTheMenuRefusesIsLearntOnTheCount() = runTest {
        val sent = mutableListOf<String>()
        val client = client(handler = table(5_000, sent, "show-sensitive"))
        assertEquals(5_000, client.call(addresses).rows.size)
        assertEquals(1, sent.count { "show-sensitive" in it && "address-list" in it })
        assertTrue("count-only" in sent.first())
        assertEquals(6, sent.size)
    }

    @Test
    fun aRowThatWentDuringTheReadingAsksThePortionAgain() = runTest {
        val rows = table(3_000)
        var fails = 2
        val client = client { command -> if (":pick" in command && fails-- > 0) "!interrupted" else rows(command) }
        assertEquals(3_000, client.call(addresses).rows.size)
    }

    @Test
    fun aRowThatWentBetweenTheViewsOfAPortionAsksThePortionAgain() = runTest {
        val rows = table(3_000)
        var fails = 1
        val client = client { command -> if (":pick" in command && fails-- > 0) "![{\".id\":\"*1\"}]\r\ninterrupted" else rows(command) }
        assertEquals(3_000, client.call(addresses).rows.size)
    }

    @Test
    fun aBigTableGoesOnWhileSafeModeHoldsAConsole() = runTest {
        val client = client(handler = table(3_000))
        assertTrue(assertNotNull(client.safeMode).enter())
        assertEquals(3_000, withTimeout(60_000) { client.call(addresses) }.rows.size)
    }

    @Test
    fun theConsoleOfSafeModeThatWentDownLeavesThePoolWhole() = runTest {
        val rows = table(3_000)
        val client = client { command -> if ("comment" in command) "~" else rows(command) }
        assertTrue(assertNotNull(client.safeMode).enter())
        consoles.single().close()
        assertFailsWith<ApiConnectionClosedException> { client.call(listOf("/ip/address/set", "=.id=*1", "=comment=a")) }
        assertEquals(3_000, withTimeout(60_000) { client.call(addresses) }.rows.size)
    }

    @Test
    fun aConsoleThatDidNotTakeSafeModeInTimeIsNotUsedAgain() = runTest {
        val client = ConsoleClient(1) { Deaf().also { deaf += it } }
        assertFalse(assertNotNull(client.safeMode).enter())
        assertTrue(deaf.single().closed)
    }

    @Test
    fun aChangeWhoseEchoStoodStillIsGivenUpInSecondsAndNotTypedAgain() = runTest {
        var runs = 0
        val client = client { command -> if ("comment" in command) "~echo".also { runs++ } else "" }
        withTimeout(30_000) { assertFailsWith<ApiConnectionClosedException> { client.call(listOf("/ip/address/set", "=.id=*1", "=comment=a")) } }
        assertEquals(1, runs)
    }

    @Test
    fun aReadWhoseEchoStoodStillIsAskedAgainOnAnotherConsole() = runTest {
        var stalls = 1
        val client = client { command -> if ("identity" in command && stalls-- > 0) "~echo" else "{\"name\":\"CHR\"}" }
        assertEquals("CHR", withTimeout(30_000) { client.call(listOf("/system/identity/print")) }.rows.single()["name"])
        assertTrue(consoles.first().closed)
    }

    @Test
    fun aScriptIsNotTypedAgainAfterTheConsoleCrashed() = runTest {
        var runs = 0
        val client = client {
            runs++
            "!Console has crashed, attempting to reconnect... success"
        }
        assertFailsWith<ApiTrapException> { client.call(listOf("/execute", "=script=:put 1")) }
        assertEquals(1, runs)
    }

    @Test
    fun aRefusedLoginEndsTheClientInsteadOfTryingForEver() = runTest {
        var refused = false
        val client = ConsoleClient(1) {
            if (refused) throw DeviceException(ErrorCategory.PermissionDenied, "invalid user name or password")
            FakeConsole { "{\"name\":\"CHR\"}" }.also { consoles += it }
        }
        client.warm(backgroundScope)
        client.call(listOf("/system/identity/print"))
        refused = true
        consoles.single().close()
        assertFailsWith<ApiConnectionClosedException> { client.call(listOf("/system/identity/print")) }
        client.status.first { it == LinkState.Closed }
    }

    @Test
    fun aTableThatKeepsChangingEndsWithTheWordsOfTheRouter() = runTest {
        val client = client { command -> if ("count-only" in command) "3000" else "!interrupted" }
        assertEquals("interrupted", assertFailsWith<ApiTrapException> { client.call(addresses) }.message)
    }

    @Test
    fun aRowThatComesTwiceWhenTheTableMovesIsOneRow() = runTest {
        val client = client { command ->
            val from = PICK.find(command)?.groupValues?.get(1)?.toInt()
            when {
                "count-only" in command -> "2500"
                from == 0 -> "[{\".id\":\"*1\"},{\".id\":\"*2\"}]"
                else -> "[{\".id\":\"*2\"},{\".id\":\"*3\"}]"
            }
        }
        assertEquals(listOf("*1", "*2", "*3"), client.call(addresses).rows.map { it[".id"] })
    }

    @Test
    fun aMenuWithoutFromIsReadWholeAndNotCountedAgain() = runTest {
        val sent = mutableListOf<String>()
        val client = client { command ->
            sent += command
            when {
                "count-only" in command -> "3000"
                ":pick" in command -> "!bad parameter from (line 1 column 38)"
                else -> "[{\".id\":\"*1\"},{\".id\":\"*2\"}]"
            }
        }
        val log = listOf("/log/print")
        val progress = mutableListOf<Int>()
        assertEquals(2, client.table(log) { read, _ -> progress += read }.size)
        assertEquals(listOf(0), progress)
        assertEquals(4, sent.size)
        assertEquals(2, client.call(log).rows.size)
        assertEquals(5, sent.size)
    }

    @Test
    fun aReadInterruptedByACrashOfTheConsoleIsAskedOnceMore() = runTest {
        var crashes = 1
        val client = client { command ->
            when {
                "count-only" in command -> "!bad parameter count-only (line 1 column 25)"
                crashes-- > 0 -> "!Console has crashed, attempting to reconnect... success"
                else -> "{\"name\":\"CHR\"}"
            }
        }
        assertEquals(listOf(mapOf("name" to "CHR")), client.call(listOf("/system/identity/print")).rows)
    }

    @Test
    fun aConsoleThatAsksToRestartTheOthersIsLeftWithoutAnAnswerUntilItGoesOnByItself() = runTest {
        val typed = mutableListOf<String>()
        val console = BusyConsole(typed)
        val client = ConsoleClient(1) { console }
        assertEquals(mapOf("ret" to "*4A"), client.call(listOf("/ip/address/add", "=address=192.0.2.1/24")).done)
        assertEquals(emptyList(), typed.filter { it == "y" || it == "n" })
        assertEquals(LinkState.Up, client.status.first())
    }

    @Test
    fun aQuickLineOfRouterOs6WaitsOutTheQuestionOfABusyConsole() = runTest {
        val typed = mutableListOf<String>()
        val client = ConsoleClient(1, Dialect.Classic) { BusyConsole(typed, reply = "\u0001.id\u0002id\u0002*1\n\u0003", pauseMillis = 15_000) }
        assertEquals(listOf(mapOf(".id" to "*1")), client.call(listOf("/ip/address/print", "?.id=*1")).rows)
        assertEquals(emptyList(), typed.filter { it == "y" || it == "n" })
    }

    @Test
    fun aRowsAnswerThatACrashOfTheConsoleCutIsAskedOnceMore() = runTest {
        var crashes = 1
        val client = client { command ->
            when {
                "count-only" in command -> "!bad parameter count-only (line 1 column 25)"
                crashes-- > 0 -> "[{\".id\":\"*1\",\"name\":Console has crashed, attempting to reconnect... success"
                else -> "{\"name\":\"CHR\"}"
            }
        }
        assertEquals(listOf(mapOf("name" to "CHR")), client.call(listOf("/system/identity/print")).rows)
    }

    @Test
    fun aChangeInterruptedByACrashOfTheConsoleIsNotRepeated() = runTest {
        val sent = mutableListOf<String>()
        val client = client { command ->
            sent += command
            "!Console has crashed, attempting to reconnect... success"
        }
        assertFailsWith<ApiTrapException> { client.call(listOf("/ip/address/add", "=address=192.0.2.1/24")) }
        assertEquals(1, sent.size)
    }

    @Test
    fun aBigTableOfRouterOs6ComesInPortionsToo() = runTest {
        val sent = mutableListOf<String>()
        val client = classic { command ->
            sent += command
            val pick = PICK.find(command)
            when {
                command.startsWith(":global rdrow") -> ""
                "count-only" in command -> "3000"
                pick == null -> "!not in portions: $command"
                else -> (pick.groupValues[1].toInt() until minOf(pick.groupValues[2].toInt(), 3_000)).joinToString("\n") { "\u0001.id\u0002id\u0002*$it\n\u0003" }
            }
        }
        val progress = mutableListOf<Int>()
        assertEquals(3_000, client.table(listOf("/ip/address/print")) { read, _ -> progress += read }.size)
        assertEquals(listOf(0, 2_000, 3_000), progress)
        assertTrue(":foreach i in=[:pick [/ip address find] 2000 4000] do={" in sent.last())
    }

    @Test
    fun aRouterThatSaysNothingForTwoMinutesIsLostAndOneThatKeepsTalkingIsNot() = runTest {
        val client = client(size = 1) { "~" }
        assertEquals("the router did not answer in 120 s", assertFailsWith<ApiConnectionClosedException> { client.call(listOf("/ip/address/add", "=address=192.0.2.1/24")) }.message)
        assertEquals(LinkState.Reconnecting, client.status.first())
        val slow = ConsoleClient(1) { DrippingConsole(chunks = 6, millis = 100_000) }
        assertTrue(slow.call(listOf("/ip/address/add", "=address=192.0.2.1/24")).done["ret"].orEmpty().endsWith("*1"))
    }

    @Test
    fun aWatchComesBackAfterALostConsoleAndEndsWithTheClient() = runTest {
        val client = client { "[]" }
        var tries = 0
        val seen = client.each<Int> {
            if (tries++ < 3) throw ApiConnectionClosedException("lost")
            emit(tries)
        }.toList()
        assertEquals(listOf(4), seen)
        client.close()
        assertFailsWith<ApiConnectionClosedException> { client.each<Int> { throw ApiConnectionClosedException("closed") }.toList() }
    }

    @Test
    fun aPasswordIsChangedWithTheCommandOfTheConsole() = runTest {
        val sent = mutableListOf<String>()
        val client = client { command ->
            sent += command
            ""
        }
        client.call(listOf("/password", "=old-password=old", "=new-password=Nw 1", "=confirm-new-password=Nw 1"))
        assertEquals(listOf("/password old-password=old new-password=\"Nw 1\" confirm-new-password=\"Nw 1\""), sent)
    }
}
