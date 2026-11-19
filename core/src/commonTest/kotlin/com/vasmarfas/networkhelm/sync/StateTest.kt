package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class StateTest {
    private val day = 24 * 60 * 60 * 1000L
    private val keys = Keys("v1", 1, ByteArray(32), ByteArray(32) { 3 })

    private fun record(id: String, value: String, time: Long, device: String = "d", deleted: Boolean = false) =
        Record("router", id, Hlc(time, 0, device), deleted, if (deleted) JsonObject(emptyMap()) else JsonObject(mapOf("comment" to JsonPrimitive(value))))

    private class Skewed(private val time: () -> Long, private val skew: Long) : Clock {
        override fun now() = Instant.fromEpochMilliseconds(time() + skew)
    }

    private class Device(val clock: HlcClock) {
        var own = emptyList<Record>()

        fun keep(record: Record) {
            own = merge(listOf(own, listOf(record)))
        }

        fun read(other: Device) {
            other.own.maxOfOrNull { it.hlc }?.let(clock::observe)
            own = merge(listOf(own, other.own))
        }
    }

    @Test
    fun mergingGivesOneResultWhateverTheOrderGroupingAndRepeatsOfTheStates() {
        repeat(300) { seed ->
            val random = Random(seed)
            val states = List(4) { device ->
                List(random.nextInt(6)) { record("r${random.nextInt(5)}", "${random.nextInt(3)}", random.nextLong(4), "d$device", random.nextInt(4) == 0) }
            }
            val all = merge(states)
            assertEquals(all, merge(states.shuffled(random)), "seed $seed")
            assertEquals(all, merge(states + states.take(2)), "seed $seed")
            assertEquals(all, merge(listOf(merge(states.take(2)), merge(states.drop(2)))), "seed $seed")
            assertEquals(all, merge(listOf(all, all)), "seed $seed")
        }
    }

    @Test
    fun devicesThatEditWithoutTheNetworkAgreeOnceEachReadTheOthersAndTheLastEditOfEachRecordStays() {
        repeat(100) { seed ->
            val random = Random(seed)
            var time = 1_000_000L
            // the clocks of the devices are minutes apart, as clocks of real devices are
            val devices = List(3) { Device(HlcClock("d$it", Skewed({ time }, random.nextLong(-300_000, 300_000)))) }
            val edits = mutableListOf<Record>()
            repeat(60) {
                time += random.nextLong(0, 5_000)
                val device = devices[random.nextInt(devices.size)]
                val id = "r${random.nextInt(6)}"
                when (val choice = random.nextInt(5)) {
                    0, 1, 2 -> {
                        val removed = choice == 2
                        val edit = Record("router", id, device.clock.now(), removed, JsonObject(if (removed) emptyMap() else mapOf("comment" to JsonPrimitive("$seed-$it"))))
                        edits += edit
                        device.keep(edit)
                    }
                    else -> device.read(devices[random.nextInt(devices.size)])
                }
            }
            repeat(2) { for (device in devices) for (other in devices) device.read(other) }
            assertEquals(devices[0].own, devices[1].own, "seed $seed")
            assertEquals(devices[0].own, devices[2].own, "seed $seed")
            assertEquals(edits.groupBy { it.id }.values.map { all -> all.maxBy { it.hlc } }.sortedBy { it.id }, devices[0].own.map { it.copy(floor = null) }, "seed $seed")
        }
    }

    // records of several fields, each with a stamp of its own, and records that outlived a removal merge alike in any order and grouping
    @Test
    fun fieldsMergeToOneResultWhateverTheOrderGroupingAndRepeatsOfTheStates() {
        fun random(random: Random, device: Int): Record {
            val id = "r${random.nextInt(3)}"
            val hlc = Hlc(random.nextLong(1, 9), random.nextInt(2), "d$device")
            if (random.nextInt(4) == 0) return Record("router", id, hlc, deleted = true)
            val keys = listOf("address", "comment", "user").filter { random.nextBoolean() }.ifEmpty { listOf("comment") }
            return Record(
                "router",
                id,
                hlc,
                data = JsonObject(keys.associateWith { JsonPrimitive("${random.nextInt(3)}") }),
                stamps = keys.filter { random.nextBoolean() }.associateWith { Hlc(random.nextLong(0, hlc.time), 0, "d$device") },
                floor = if (random.nextInt(5) == 0) Hlc(random.nextLong(0, hlc.time), 0, "x") else null,
            )
        }
        repeat(500) { seed ->
            val random = Random(seed)
            val states = List(4) { device -> List(random.nextInt(5)) { random(random, device) } }
            val all = merge(states)
            assertEquals(all, merge(states.shuffled(random)), "seed $seed")
            assertEquals(all, merge(states + states.take(2)), "seed $seed")
            assertEquals(all, merge(listOf(merge(states.take(1)), merge(states.drop(1).take(2)), merge(states.drop(3)))), "seed $seed")
            assertEquals(all, merge(listOf(all, all)), "seed $seed")
        }
    }

    // two devices that changed different fields of one router both keep their change; the same field keeps the later change
    @Test
    fun twoDevicesThatChangedDifferentFieldsOfARecordKeepBoth() {
        fun entry(address: String, comment: String) = JsonObject(mapOf("address" to JsonPrimitive(address), "comment" to JsonPrimitive(comment)))
        val base = emptyList<Record>().matched("router", mapOf("r" to entry("192.0.2.1", "core")), Hlc(1, 0, "a"))
        val here = base.matched("router", mapOf("r" to entry("192.0.2.1", "edge")), Hlc(2, 0, "a"))
        val there = base.matched("router", mapOf("r" to entry("192.0.2.9", "core")), Hlc(3, 0, "b"))
        val both = merge(listOf(here, there)).single()
        assertEquals(entry("192.0.2.9", "edge"), both.data)
        assertEquals(both, merge(listOf(there, here)).single())
        val later = listOf(both).matched("router", mapOf("r" to entry("192.0.2.9", "lab")), Hlc(4, 0, "b"))
        assertEquals(entry("192.0.2.9", "lab"), merge(listOf(listOf(both), later)).single().data)
    }

    // a removal takes every field written before it; a field changed after it on a device that did not see it comes back alone, and a
    // record put back after the removal comes back whole
    @Test
    fun aRemovalTakesTheFieldsWrittenBeforeItAndARecordPutBackComesBackWhole() {
        fun entry(vararg fields: Pair<String, String>) = JsonObject(fields.associate { (key, value) -> key to JsonPrimitive(value) })
        val base = emptyList<Record>().matched("router", mapOf("r" to entry("address" to "192.0.2.1", "comment" to "core")), Hlc(1, 0, "a"))
        val removed = base.matched("router", emptyMap(), Hlc(2, 0, "a"))
        val edited = base.matched("router", mapOf("r" to entry("address" to "192.0.2.1", "comment" to "edge")), Hlc(3, 0, "b"))
        val merged = merge(listOf(removed, edited)).single()
        assertEquals(entry("comment" to "edge"), merged.data)
        assertEquals(Hlc(2, 0, "a"), merged.floor)
        assertTrue(merge(listOf(removed, base)).single().deleted)
        val back = removed.matched("router", mapOf("r" to entry("address" to "192.0.2.1", "comment" to "back")), Hlc(4, 0, "a"))
        assertEquals(entry("address" to "192.0.2.1", "comment" to "back"), merge(listOf(back, base, edited)).single().data)
    }

    @Test
    fun tombstonesGoAfter180DaysAndADeviceAwayLongerBringsBackNothingRemovedMeanwhile() {
        val now = 400 * day
        val own = listOf(record("gone", "a", 10 * day), record("kept", "b", 10 * day), record("new", "c", 350 * day))
        // removed on another device 300 days ago: its tombstone left the states of the others after 180 days
        val others = listOf(listOf(record("kept", "b", 10 * day), record("fresh", "", 390 * day, deleted = true), record("old", "", 100 * day, deleted = true)))
        assertEquals(listOf("fresh", "kept", "new"), merged(own, others, 20 * day, now).map { it.id })
        assertEquals(listOf("fresh", "gone", "kept", "new"), merged(own, others, 300 * day, now).map { it.id })
        assertEquals(listOf("fresh", "gone", "kept", "new"), merged(own, others, null, now).map { it.id })
    }

    // only a kind another state carries tells what was removed meanwhile: with no state of another device, or a kind left out there,
    // a device away longer than a tombstone lives keeps its records whole
    @Test
    fun aDeviceAwayLongKeepsTheKindsNoStateOfAnotherDeviceCarries() {
        val now = 400 * day
        val snippet = Record("snippet", "s", Hlc(10 * day, 0, "d"), data = JsonObject(mapOf("text" to JsonPrimitive("/ip address print"))))
        val own = listOf(record("gone", "a", 10 * day), record("new", "c", 350 * day), snippet)
        assertEquals(listOf("router/gone", "router/new", "snippet/s"), merged(own, emptyList(), 20 * day, now).map { "${it.kind}/${it.id}" })
        assertEquals(listOf("router/gone", "router/new", "snippet/s"), merged(own, listOf(emptyList()), 20 * day, now).map { "${it.kind}/${it.id}" })
        val others = listOf(listOf(record("kept", "b", 10 * day)))
        assertEquals(listOf("router/kept", "router/new", "snippet/s"), merged(own, others, 20 * day, now).map { "${it.kind}/${it.id}" })
    }

    @Test
    fun aFileGivenBackFromBeforeIsRefused() {
        val seen = Seen().with("state/a", 5)
        seen.check("state/a", 5)
        seen.check("state/a", 6)
        seen.check("state/b", 1)
        val error = assertFailsWith<DeviceException> { seen.check("state/a", 4) }
        assertEquals("state/a is older than the copy read before: the service gave back an earlier version", error.message)
        // a device that never read the state still sees it is older than its device record says
        assertFailsWith<DeviceException> { Seen().check("state/a", 3, named = 4) }
        assertEquals(5L, seen.with("state/a", 3).versions["state/a"])
    }

    @Test
    fun recordsAndFieldsOfANewerVersionGoThroughUntouched() = runTest {
        val newer = Record("future.thing", "x", Hlc(5, 0, "d"), data = JsonObject(mapOf("field" to JsonPrimitive("kept"))))
        val router = record("r1", "core", 6).let { it.copy(data = JsonObject(it.data + ("added" to JsonPrimitive(1)))) }
        val read = readState(keys, "d", writeState(keys, "d", 1, listOf(newer, router)))
        assertEquals(listOf(newer, router), read.content.records)
        assertEquals(listOf(newer, router), merge(listOf(read.content.records, listOf(record("r1", "core", 2)))))
    }

    @Test
    fun aStateOfANewerMinorIsReadAndOneOfANewerMajorAsksForAnUpdate() = runTest {
        val content = "{\"schema\":{\"major\":1,\"minor\":4},\"records\":[],\"added\":true}".encodeToByteArray()
        val minor = seal(Header("state", "v1", 1, "d", 1, Schema(1, 4)), statePath("d"), content, stateKey(keys.key))
        assertEquals(Schema(1, 4), readState(keys, "d", minor).content.schema)
        val major = seal(Header("state", "v1", 1, "d", 1, Schema(2, 0)), statePath("d"), content, stateKey(keys.key))
        val error = assertFailsWith<DeviceException> { readState(keys, "d", major) }
        assertEquals(ErrorCategory.Unsupported, error.category)
        assertEquals("state/d was written by a newer version of the app: update it to synchronise", error.message)
    }

    @Test
    fun aDeviceRecordNamesTheDeviceAndItsLastStateAndStaysInItsPlace() = runTest {
        val content = DeviceContent("Laptop", "Windows", "1.4.0", 12)
        val read = readDevice(keys, "d", writeDevice(keys, "d", 13, content))
        assertEquals(content, read.content)
        assertEquals(13L, read.header.version)
        assertEquals("devices/e holds a file written for another place", assertFailsWith<DeviceException> { readDevice(keys, "e", writeDevice(keys, "d", 1, content)) }.message)
        assertEquals("state/d belongs to another vault", assertFailsWith<DeviceException> { readState(Keys("v2", 1, ByteArray(32), keys.key), "d", writeState(keys, "d", 1, emptyList())) }.message)
        assertEquals("state/d was not written with the key of this vault", assertFailsWith<DeviceException> { readState(Keys("v1", 1, ByteArray(32), ByteArray(32) { 4 }), "d", writeState(keys, "d", 1, emptyList())) }.message)
    }
}
