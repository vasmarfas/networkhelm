package com.vasmarfas.networkhelm.routeros

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.packs.PackNativeField
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.packs.PackTab
import com.vasmarfas.networkhelm.packs.PackWindow
import com.vasmarfas.networkhelm.session.NativeObjects
import com.vasmarfas.networkhelm.session.NativeSide
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class NativeWindowsTest {

    private val spec = PackNative(listOf(167, 1), listOf(PackNativeField("pairing-status", 0x211c, "string")))

    private class Script(private vararg val answers: () -> List<Item>?) : NativeObjects {
        var reads = 0
        var closed = false

        override suspend fun read(spec: PackNative, limit: Int?): List<Item>? = answers[minOf(reads++, answers.lastIndex)]()

        override fun close() {
            closed = true
        }
    }

    private class Side(vararg objects: NativeObjects, override val sessionPort: Int? = null) : NativeSide {
        private val queue = objects.toMutableList()
        var opened = 0
        var port: Int? = null

        override suspend fun open(port: Int): NativeObjects {
            opened++
            this.port = port
            return queue.removeFirst()
        }
    }

    private class Router(private val answer: (spec: PackNative, served: Int) -> List<Item>?) : NativeSide {
        var opened = 0
        var reading = 0
        var overlap = 0

        override suspend fun open(port: Int): NativeObjects {
            opened++
            var served = 0
            return object : NativeObjects {
                override suspend fun read(spec: PackNative, limit: Int?): List<Item>? {
                    overlap = maxOf(overlap, ++reading)
                    try {
                        delay(100)
                        return answer(spec, served++)
                    } finally {
                        reading--
                    }
                }

                override fun close() = Unit
            }
        }
    }

    private fun other(handler: Long) = PackNative(listOf(167, handler), spec.fields)

    private val dropped = DeviceException(ErrorCategory.ConnectionLost, "dropped")

    private fun row(id: String, status: String) = Item(id, mapOf("pairing-status" to status))

    private fun rows(vararg items: Item): () -> List<Item>? = { items.toList() }

    private fun failure(category: ErrorCategory): () -> List<Item>? = { throw DeviceException(category, "failed") }

    private fun native(side: NativeSide?, port: Int? = 8291, time: TimeSource = TimeSource.Monotonic) = NativeWindows(side, { port }, 1.seconds, time)

    private fun shown(into: MutableMap<String, Item>): (ListEvent) -> Unit = { event ->
        when (event) {
            is ListEvent.Snapshot -> {
                into.clear()
                event.items.forEach { into[it.id] = it }
            }
            is ListEvent.Upsert -> into[event.item.id] = event.item
            is ListEvent.Removed -> into.remove(event.id)
            is ListEvent.Progress -> Unit
        }
    }

    @Test
    fun theWindowsAreMadeOnlyWhereThereIsAChannelAndTheRouterServesIt() = runTest {
        assertFalse(native(null).usable())
        assertFalse(native(Side(), port = null).usable())
        assertTrue(native(Side()).usable())
    }

    @Test
    fun aPathOfTheNativeChannelIsKnownByItsFirstSegmentAndItsSchemaIsAReadOnlyList() = runTest {
        val windows = native(Side())
        assertTrue(windows.stands(MenuPath.parse("native/167/44")))
        assertFalse(windows.stands(MenuPath.parse("cmr/device")))
        val schema = windows.schema(MenuPath.parse("native/167/1"), spec)
        assertEquals(NodeKind.List, schema.kind)
        assertEquals(listOf("pairing-status"), schema.arguments.map { it.name })
        assertTrue(schema.arguments.all { it.readOnly })
        assertFalse(schema.listens)
    }

    @Test
    fun anEnumerationOffersTheNamesTheRouterGivesAndNotTheBlankOfAFieldNotSet() = runTest {
        val mode = PackNative(listOf(120, 6), listOf(PackNativeField("mode", 0x1, "enm", names = mapOf(0L to "", 1L to "Router", 2L to "Bridge"))), single = true, edit = true)
        assertEquals(listOf("Router", "Bridge"), native(Side()).schema(MenuPath.parse("native/120/6"), mode).arguments.single().choices)
    }

    @Test
    fun aWindowOfOneRecordIsSettingsAndNotAList() = runTest {
        val one = PackNative(listOf(24, 2), listOf(PackNativeField("uptime", 0x1, "interval")), single = true)
        assertEquals(NodeKind.Settings, native(Side()).schema(MenuPath.parse("native/24/2"), one).kind)
    }

    @Test
    fun aTabOfTheChannelThatTheRouterRefusesIsNoTabOfThisRouterAndOneThatCannotBeAskedAboutStays() = runTest {
        val live = other(1)
        val dead = other(2)
        fun tab(spec: PackNative) = PackTab(spec.handler.last().toString(), "native/167/${spec.handler.last()}")
        fun pack(menu: Boolean, vararg specs: PackNative) = Pack(
            menu = listOf("Controller", "Things"),
            window = PackWindow("Things", listOfNotNull(PackTab("Devices", "cmr/device").takeIf { menu }) + specs.map(::tab)),
            paths = mapOf("cmr/device" to PackPath(native = spec)) + specs.associate { tab(it).path to PackPath(native = it) },
        )
        fun titles(packs: List<Pack>) = packs.map { it.window.tabs.map { tab -> tab.title } }
        val refusing = Router { read, _ -> if (read === dead) throw DeviceException(ErrorCategory.General, "the router refused the request") else listOf(row("*1", "paired")) }
        assertEquals(listOf(listOf("Devices", "1"), listOf("1")), titles(native(refusing, time = testTimeSource).alive(listOf(pack(true, live, dead), pack(false, dead), pack(false, live)))))
        assertEquals(listOf(listOf("1")), titles(native(Router { _, _ -> throw dropped }, time = testTimeSource).alive(listOf(pack(false, live)))))
    }

    @Test
    fun aHandlerTheRouterIgnoresLosesItsTabAndIsNotAskedAgainWhileTheChannelStays() = runTest {
        val live = other(1)
        val silent = other(2)
        val objects = object : NativeObjects {
            var reads = 0

            override suspend fun read(spec: PackNative, limit: Int?): List<Item>? {
                reads++
                return listOf(row("*1", "paired"))
            }

            override suspend fun answers(spec: PackNative) = spec !== silent

            override fun close() = Unit
        }
        fun tab(spec: PackNative) = PackTab(spec.handler.last().toString(), "native/167/${spec.handler.last()}")
        val pack = Pack(menu = listOf("Controller", "Things"), window = PackWindow("Things", listOf(tab(live), tab(silent)), selector = true), paths = mapOf(tab(live).path to PackPath(native = live), tab(silent).path to PackPath(native = silent)), hidden = true)
        val side = Side(objects)
        val windows = native(side)
        val standing = windows.alive(listOf(pack))
        assertEquals(listOf(listOf("1")), standing.map { it.window.tabs.map { tab -> tab.title } })
        assertEquals(listOf(true to true), standing.map { it.window.selector to it.hidden })
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { windows.count(silent) }.category)
        assertEquals(1, windows.count(live))
        assertEquals(1, objects.reads)
        assertEquals(1, side.opened)
    }

    @Test
    fun aCheckOfTheWindowsMadeBeforeStandsInWithoutAskingAndTheCheckAgainFindsAHandlerThatAnswersNow() = runTest {
        val live = other(1)
        val silent = other(2)
        var answering = false
        val objects = object : NativeObjects {
            var asked = 0

            override suspend fun read(spec: PackNative, limit: Int?): List<Item>? = listOf(row("*1", "paired"))

            override suspend fun answers(spec: PackNative): Boolean {
                asked++
                return spec !== silent || answering
            }

            override fun close() = Unit
        }
        fun tab(spec: PackNative) = PackTab(spec.handler.last().toString(), "native/167/${spec.handler.last()}")
        val pack = Pack(menu = listOf("Controller", "Things"), window = PackWindow("Things", listOf(tab(live), tab(silent))), paths = mapOf(tab(live).path to PackPath(native = live), tab(silent).path to PackPath(native = silent)))
        val found = ArrayList<WindowVerdicts>()
        val first = native(Side(objects)).alive(listOf(pack)) { found += it }
        assertEquals(listOf(listOf("1")), first.map { it.window.tabs.map { tab -> tab.title } })
        val kept = decodeVerdicts(encodeVerdicts(found.single()))
        assertEquals(setOf("167,2/"), kept?.silent)
        assertEquals(2, objects.asked)
        val windows = native(Side(objects))
        val again = windows.alive(listOf(pack), kept) { found += it }
        assertEquals(listOf(listOf("1")), again.map { it.window.tabs.map { tab -> tab.title } })
        assertEquals(2, objects.asked)
        assertEquals(1, found.size)
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { windows.count(silent) }.category)
        answering = true
        assertEquals(mapOf("167,1/" to true, "167,2/" to true), windows.recheck(listOf(pack))?.stands)
        assertEquals(1, windows.count(silent))
    }

    @Test
    fun theButtonsOfAWindowAreItsCommandsAndPressingOneGoesThroughTheChannel() = runTest {
        val buttons = PackNative(listOf(24, 2), listOf(PackNativeField("uptime", 0x1, "interval")), single = true, doits = listOf(3, 4))
        val objects = object : NativeObjects {
            val pressed = ArrayList<Pair<Int, String?>>()

            override suspend fun read(spec: PackNative, limit: Int?): List<Item>? = emptyList()

            override suspend fun act(spec: PackNative, command: Int, id: String?) {
                pressed += command to id
            }

            override fun close() = Unit
        }
        val windows = native(Side(objects))
        assertEquals(listOf("3", "4"), windows.schema(MenuPath.parse("native/24/2"), buttons).commands)
        windows.act(buttons, "3", null)
        windows.act(buttons, "4", "*A")
        assertEquals(listOf(3 to null, 4 to "*A"), objects.pressed)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { windows.act(buttons, "renew", null) }.category)
    }

    @Test
    fun aListIsEmittedAgainOnlyWhenItChangesAndTheChannelIsOpenedOnce() = runTest {
        val objects = Script(rows(row("*1", "paired")), rows(row("*1", "paired")), rows(row("*1", "lost")))
        val side = Side(objects)
        val events = native(side).list(spec).take(2).toList()
        assertEquals(listOf(ListEvent.Snapshot(listOf(row("*1", "paired"))), ListEvent.Snapshot(listOf(row("*1", "lost")))), events)
        assertEquals(1, side.opened)
        assertEquals(8291, side.port)
        assertEquals(3, objects.reads)
    }

    @Test
    fun theWindowOfOneRowSeesOnlyThatRow() = runTest {
        val side = Side(Script(rows(row("*1", "paired"), row("*2", "lost"))))
        val events = native(side).list(spec, Filter.Equals(".id", "*2")).take(1).toList()
        assertEquals(listOf(ListEvent.Snapshot(listOf(row("*2", "lost")))), events)
    }

    @Test
    fun theChannelOfTheSessionItselfNeedsNoServiceOfTheRouter() = runTest {
        val side = Side(Script(rows(row("*1", "paired"))), sessionPort = 18291)
        var asked = 0
        val windows = NativeWindows(side, { asked++; null }, 1.seconds)
        assertTrue(windows.usable())
        assertEquals(listOf(ListEvent.Snapshot(listOf(row("*1", "paired")))), windows.list(spec).take(1).toList())
        assertEquals(18291, side.port)
        assertEquals(0, asked)
    }

    @Test
    fun theChannelOfTheSessionItselfIsTakenAgainAsSoonAsTheSessionHasItBack() = runTest {
        var down = 3
        val side = object : NativeSide {
            override val sessionPort = 18291

            override suspend fun open(port: Int): NativeObjects {
                if (down-- > 0) throw DeviceException(ErrorCategory.ConnectionLost, "lost")
                return Script(rows(row("*1", "paired")))
            }
        }
        val windows = native(side, time = testTimeSource)
        repeat(3) { assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { windows.count(spec) }.category) }
        assertEquals(1, windows.count(spec))
    }

    @Test
    fun aPortThatAFirewallClosedCountsAsAServiceThatIsOff() = runTest {
        val windows = native(NativeSide { throw IllegalStateException("Connection refused: getsockopt") })
        assertEquals(null, windows.facts())
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { windows.create(spec, emptyMap()) }.category)
    }

    @Test
    fun theChannelIsOpenedAgainAfterItIsLost() = runTest {
        val broken = Script(failure(ErrorCategory.ConnectionLost))
        val side = Side(broken, Script(rows(row("*1", "paired"))))
        val windows = native(side)
        assertFailsWith<DeviceException> { windows.count(spec) }
        assertTrue(broken.closed)
        assertEquals(1, windows.count(spec))
        assertEquals(2, side.opened)
    }

    @Test
    fun aChannelThatDropsAtEveryReadIsNotOpenedAtEveryPoll() = runTest {
        val side = Router { _, _ -> throw dropped }
        val windows = native(side, time = testTimeSource)
        repeat(600) { at ->
            assertFailsWith<DeviceException> { windows.count(other(at.toLong())) }
            delay(1.seconds)
        }
        assertTrue(side.opened < 10, "${side.opened} logins for 600 polls")
    }

    @Test
    fun aReadThatDropsTheChannelAgainAfterANewLoginIsRefusedForGood() = runTest {
        val side = Router { _, _ -> throw dropped }
        val windows = native(side, time = testTimeSource)
        repeat(2) { assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { windows.count(spec) }.category) }
        repeat(100) {
            assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { windows.count(spec) }.category)
            delay(10.seconds)
        }
        assertEquals(2, side.opened)
    }

    @Test
    fun aWindowWhoseReadDropsTheChannelTwiceStopsReadingAndKeepsTheConsole() = runTest {
        val side = Router { _, _ -> throw dropped }
        val console = flow {
            emit(ListEvent.Snapshot(listOf(Item("*1", mapOf("identity" to "a")))))
            awaitCancellation()
        }
        val items = HashMap<String, Item>()
        val job = launch { native(side, time = testTimeSource).joined(console, spec).collect(shown(items)) }
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(2, side.opened)
        assertEquals(mapOf("identity" to "a"), items.getValue("*1").values)
        job.cancel()
    }

    @Test
    fun otherWindowsKeepReadingWhenOneReadIsRefused() = runTest {
        val bad = other(5)
        val side = Router { read, _ -> if (read === bad) throw dropped else listOf(row("*1", "paired")) }
        val windows = native(side, time = testTimeSource)
        assertFailsWith<DeviceException> { windows.count(bad) }
        assertEquals(1, windows.count(spec))
        assertFailsWith<DeviceException> { windows.count(bad) }
        delay(6.seconds)
        assertEquals(1, windows.count(spec))
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { windows.count(bad) }.category)
        assertEquals(3, side.opened)
    }

    @Test
    fun aChannelThatLivedLongIsOpenedAgainAtOnceEveryTime() = runTest {
        val side = Router { _, served -> if (served == 0) listOf(row("*1", "paired")) else throw dropped }
        val windows = native(side, time = testTimeSource)
        repeat(5) {
            assertEquals(1, windows.count(spec))
            delay(3.minutes)
            assertFailsWith<DeviceException> { windows.count(spec) }
        }
        assertEquals(5, side.opened)
    }

    @Test
    fun theWindowsOfOneSessionShareOneChannelAndReadAtTheSameTime() = runTest {
        val side = Router { _, _ -> listOf(row("*1", "paired")) }
        val windows = native(side, time = testTimeSource)
        val console = flow {
            emit(ListEvent.Snapshot(listOf(Item("*1", mapOf("identity" to "a")))))
            awaitCancellation()
        }
        val jobs = listOf(
            launch { windows.list(other(1)).collect { } },
            launch { windows.list(other(2)).collect { } },
            launch { windows.joined(console, other(3)).collect { } },
            launch { windows.joined(console, other(4)).collect { } },
        )
        advanceTimeBy(5.minutes)
        runCurrent()
        assertEquals(1, side.opened)
        assertEquals(4, side.overlap)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun aMenuTableIsReadWholeFromItsWindowOnlyUpToTheRowsTheWindowShows() = runTest {
        val limits = ArrayList<Int?>()
        val objects = object : NativeObjects {
            override suspend fun read(spec: PackNative, limit: Int?): List<Item>? {
                limits += limit
                return listOf(row("*1", "paired"))
            }

            override fun close() = Unit
        }
        native(Side(objects)).whole(spec.copy(most = 10000))
        assertEquals(listOf<Int?>(10000), limits)
    }

    @Test
    fun aListIsReadAgainAsOftenAsItsWindowSaysButNoMoreOftenThanEachPoll() = runTest {
        var reads = 0
        val windows = native(Router { _, _ -> listOf(row("*1", "paired")).also { reads++ } }, time = testTimeSource)
        val paths = mapOf("ip/arp" to PackPath(refresh = 5000), "tool/scan" to PackPath(refresh = 100), "ip/pool" to PackPath())
        windows.keep(listOf(Pack(menu = listOf("IP"), window = PackWindow("ARP", emptyList()), paths = paths)))
        assertEquals(5.seconds, windows.refresh(MenuPath.parse("ip/arp")))
        assertEquals(1.seconds, windows.refresh(MenuPath.parse("tool/scan")))
        assertEquals(null, windows.refresh(MenuPath.parse("ip/pool")))
        val job = launch { windows.list(spec, period = 5.seconds).collect { } }
        advanceTimeBy(1.minutes)
        runCurrent()
        job.cancel()
        assertTrue(reads in 11..13, "$reads reads in a minute")
    }

    @Test
    fun aDropUnderReadsSideBySideIsChargedToNoneUntilTheReadThatMakesItDropsAlone() = runTest {
        val bad = other(5)
        val side = object : NativeSide {
            override suspend fun open(port: Int): NativeObjects {
                var dead = false
                return object : NativeObjects {
                    override suspend fun read(spec: PackNative, limit: Int?): List<Item>? {
                        delay(if (spec === bad) 50 else 100)
                        if (spec === bad) dead = true
                        if (dead) throw dropped
                        return listOf(row("*1", "paired"))
                    }

                    override fun close() = Unit
                }
            }
        }
        val windows = native(side, time = testTimeSource)
        val together = listOf(async { runCatching { windows.count(bad) } }, async { runCatching { windows.count(spec) } }).awaitAll()
        assertEquals(listOf(ErrorCategory.ConnectionLost, ErrorCategory.ConnectionLost), together.map { (it.exceptionOrNull() as DeviceException).category })
        assertEquals(1, windows.count(spec))
        repeat(2) {
            delay(1.minutes)
            assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { windows.count(bad) }.category)
        }
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { windows.count(bad) }.category)
        delay(1.minutes)
        assertEquals(1, windows.count(spec))
    }

    @Test
    fun aSessionThatIsClosedOpensNoChannel() = runTest {
        val side = Router { _, _ -> listOf(row("*1", "paired")) }
        val windows = native(side, time = testTimeSource)
        windows.close()
        assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { windows.count(spec) }.category)
        assertEquals(0, side.opened)
    }

    @Test
    fun aChannelOpenedWhileTheSessionClosesIsClosedAtOnce() = runTest {
        val channel = Script(rows(row("*1", "paired")))
        lateinit var windows: NativeWindows
        windows = native(NativeSide { windows.close(); channel }, time = testTimeSource)
        assertFailsWith<DeviceException> { windows.count(spec) }
        assertTrue(channel.closed)
    }

    @Test
    fun theFieldsOfTheChannelAreJoinedToTheElementsOfTheConsoleById() = runTest {
        val side = Side(Script(rows(row("*1", "paired"), row("*2", "pending"), row("*9", "stale"))))
        val console = Channel<ListEvent>(Channel.UNLIMITED)
        val items = HashMap<String, Item>()
        val job = launch { native(side).joined(console.receiveAsFlow(), spec).collect(shown(items)) }
        console.send(ListEvent.Snapshot(listOf(Item("*1", mapOf("identity" to "a")), Item("*2", mapOf("identity" to "b")))))
        runCurrent()
        assertEquals(mapOf("identity" to "a", "pairing-status" to "paired"), items.getValue("*1").values)
        assertEquals(mapOf("identity" to "b", "pairing-status" to "pending"), items.getValue("*2").values)
        assertEquals(setOf("*1", "*2"), items.keys)
        console.send(ListEvent.Upsert(Item("*3", mapOf("identity" to "c"))))
        runCurrent()
        assertEquals(mapOf("identity" to "c"), items.getValue("*3").values)
        console.send(ListEvent.Removed("*2"))
        runCurrent()
        assertEquals(setOf("*1", "*3"), items.keys)
        job.cancel()
    }

    @Test
    fun theOneRecordOfAWindowOfSettingsIsJoinedToTheOneRowOfTheConsoleWhateverIdsTheyHave() = runTest {
        val settings = PackNative(listOf(24, 24), listOf(PackNativeField("changelog", 4, "multilinestring", letter = "s")), single = true)
        fun said(text: String) = rows(Item("#0", mapOf("changelog" to text)))
        val console = flow {
            emit(ListEvent.Snapshot(listOf(Item("", mapOf("status" to "New version is available")))))
            awaitCancellation()
        }
        val items = HashMap<String, Item>()
        val job = launch { native(Side(Script(said("What's new in 7.1"), said("What's new in 7.2")))).joined(console, settings).collect(shown(items)) }
        runCurrent()
        assertEquals(mapOf("changelog" to "What's new in 7.1", "status" to "New version is available"), items.getValue("").values)
        advanceTimeBy(4.seconds)
        runCurrent()
        assertEquals("What's new in 7.2", items.getValue("")["changelog"])
        job.cancel()
        val one = native(Side(Script(said("What's new in 7.1")))).joined(Item("", mapOf("status" to "up to date")), settings)
        assertEquals(mapOf("changelog" to "What's new in 7.1", "status" to "up to date"), one.values)
    }

    @Test
    fun aValueThatChangesInTheChannelReachesTheElementWithoutAnEventOfTheConsole() = runTest {
        val side = Side(Script(rows(row("*1", "paired")), rows(row("*1", "lost"))))
        val console = flow {
            emit(ListEvent.Snapshot(listOf(Item("*1", mapOf("identity" to "a")))))
            awaitCancellation()
        }
        val items = HashMap<String, Item>()
        val job = launch { native(side).joined(console, spec).collect(shown(items)) }
        runCurrent()
        assertEquals("paired", items.getValue("*1")["pairing-status"])
        advanceTimeBy(4.seconds)
        runCurrent()
        assertEquals("lost", items.getValue("*1")["pairing-status"])
        assertEquals("a", items.getValue("*1")["identity"])
        job.cancel()
    }

    @Test
    fun aLostChannelIsOpenedAgainLaterAndTheFieldsComeThen() = runTest {
        val side = Side(Script(failure(ErrorCategory.ConnectionLost)), Script(rows(row("*1", "paired"))))
        val console = flow {
            emit(ListEvent.Snapshot(listOf(Item("*1", mapOf("identity" to "a")))))
            awaitCancellation()
        }
        val items = HashMap<String, Item>()
        val job = launch { native(side).joined(console, spec).collect(shown(items)) }
        runCurrent()
        assertEquals(null, items.getValue("*1")["pairing-status"])
        advanceTimeBy(31.seconds)
        runCurrent()
        assertEquals("paired", items.getValue("*1")["pairing-status"])
        assertEquals(2, side.opened)
        job.cancel()
    }

    @Test
    fun aRouterThatDoesNotServeTheChannelLeavesTheElementsOfTheConsoleAsTheyAre() = runTest {
        val side = Side(Script(failure(ErrorCategory.Unsupported)))
        val only = ListEvent.Snapshot(listOf(Item("*1", mapOf("identity" to "a"))))
        assertEquals(listOf<ListEvent>(only), native(side).joined(flowOf(only), spec).toList())
    }

    @Test
    fun anElementIsJoinedToItsFieldsAndKeptAsItIsWhereTheChannelFails() = runTest {
        val item = Item("*1", mapOf("identity" to "a"))
        assertEquals(Item("*1", mapOf("identity" to "a", "pairing-status" to "paired")), native(Side(Script(rows(row("*1", "paired"))))).joined(item, spec))
        assertEquals(item, native(Side(Script(rows(row("*2", "paired"))))).joined(item, spec))
        assertEquals(item, native(Side(Script(failure(ErrorCategory.ConnectionLost)))).joined(item, spec))
    }

    @Test
    fun theChannelIsClosedWithTheSession() = runTest {
        val objects = Script(rows(row("*1", "paired")))
        val windows = native(Side(objects))
        windows.count(spec)
        windows.close()
        assertTrue(objects.closed)
    }

    @Test
    fun aMenuTableThatChangedUnderItsReadIsReadAgainAndOneTheChannelCannotGiveIsNone() = runTest {
        val changed = failure(ErrorCategory.MissingItem)
        val again = Script(changed, rows(row("*1", "paired")))
        assertEquals(listOf(row("*1", "paired")), native(Side(again)).mirrored(spec, 1) {})
        assertEquals(2, again.reads)
        val restless = Script(changed)
        assertEquals(null, native(Side(restless)).mirrored(spec, 1) {})
        assertEquals(3, restless.reads)
        val refusing = Script(failure(ErrorCategory.General))
        assertEquals(null, native(Side(refusing)).mirrored(spec, 1) {})
        assertEquals(1, refusing.reads)
    }

    @Test
    fun aMenuTableComesPageByPageFromTheSubscriptionAndByGetallWhereTheRouterSendsNone() = runTest {
        val pages = ArrayList<List<Item>>()
        val dumping = object : NativeObjects {
            var reads = 0

            override suspend fun read(spec: PackNative, limit: Int?): List<Item>? {
                reads++
                return listOf(row("*9", "read"))
            }

            override suspend fun dump(spec: PackNative, expected: Int, page: suspend (List<Item>) -> Unit): List<Item>? {
                page(listOf(row("*1", "paired")))
                page(listOf(row("*2", "lost")))
                return listOf(row("*1", "paired"), row("*2", "lost"))
            }

            override fun close() = Unit
        }
        assertEquals(listOf(row("*1", "paired"), row("*2", "lost")), native(Side(dumping)).mirrored(spec, 2) { pages += it })
        assertEquals(listOf(listOf(row("*1", "paired")), listOf(row("*2", "lost"))), pages)
        assertEquals(0, dumping.reads)
        val silent = Script(rows(row("*9", "read")))
        assertEquals(listOf(row("*9", "read")), native(Side(silent)).mirrored(spec, 1) { pages += it })
        assertEquals(1, silent.reads)
    }
}
