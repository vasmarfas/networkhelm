package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.session.TextStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private const val THIRD_DEVICE = "3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c"

private class Memory : TextStore {
    val texts = HashMap<String, String>()

    override suspend fun read(name: String) = texts[name]

    override suspend fun write(name: String, text: String) {
        texts[name] = text
    }
}

// the stores of a device in memory: a record a kind and id, an edit stamped by the clock of the device. A kind kept here takes nothing
// merged, as the trust store keeps its fingerprint where another device holds another one
private class Stores(private val clock: HlcClock, override val kinds: Set<String>, private val out: Set<String>, private val kept: Set<String>) : SyncLocal {
    var all = emptyList<Record>()

    override fun leftOut(kind: String) = kind in out

    override suspend fun records() = all.filter { it.kind in kinds && it.kind !in out }

    override suspend fun apply(records: List<Record>) {
        records.maxOfOrNull { it.hlc }?.let(clock::observe)
        all = all.filter { it.kind in out || it.kind in kept } + records.filter { it.kind !in kept }
    }

    fun edit(kind: String, id: String, value: String) = put(Record(kind, id, clock.now(), data = JsonObject(mapOf("value" to JsonPrimitive(value)))))

    fun delete(kind: String, id: String) = put(Record(kind, id, clock.now(), deleted = true))

    fun value(kind: String, id: String) = all.firstOrNull { it.kind == kind && it.id == id && !it.deleted }?.data?.get("value")?.jsonPrimitive?.content

    private fun put(record: Record) {
        all = all.filterNot { it.kind == record.kind && it.id == record.id } + record
    }
}

class SynchronizerTest {
    private var time = 1_800_000_000_000L
    private val clock = object : Clock {
        override fun now() = Instant.fromEpochMilliseconds(time)
    }
    private val folder = MemoryFolder()
    private val service = FolderService(folder)
    private lateinit var keys: Keys

    private inner class Device(
        val id: String,
        kinds: Set<String> = setOf("router", "snippet"),
        out: Set<String> = emptySet(),
        kept: Set<String> = emptySet(),
        private val place: SyncService = service,
    ) {
        val stores = Stores(HlcClock(id.take(16), clock), kinds, out, kept)
        val memory = Memory()
        var sync = synchronizer()

        fun synchronizer() = Synchronizer(place, keys, id, DeviceContent(id.take(4), "test", "1.4.0", 0), stores, memory, clock)

        // the device in several places of the vault, a memory file for each
        fun inPlaces(vararg places: Pair<String, SyncService>): SyncPlaces {
            val files = places.map { (name, _) -> if (name.isEmpty()) SYNC_MEMORY else "sync-memory-$name.json" }
            return SyncPlaces(keys, places.mapIndexed { index, (name, there) ->
                Place(name, there, Synchronizer(there, keys, id, DeviceContent(id.take(4), "test", "1.4.0", 0), stores, memory, clock, files[index], files - files[index]))
            })
        }
    }

    private class Down(private val service: SyncService) : SyncService by service {
        var down = false

        override suspend fun list() = if (down) throw DeviceException(ErrorCategory.Api, "rd-test does not answer") else service.list()

        override suspend fun read(path: String) = if (down) throw DeviceException(ErrorCategory.Api, "rd-test does not answer") else service.read(path)
    }

    private suspend fun vault() {
        val (bytes, made) = createVault("correct horse battery staple", null, FIRST_DEVICE, passes = 1, memory = 8192)
        service.writeIf(VAULT, bytes, null)
        keys = made
    }

    private fun file(path: String) = folder.files.getValue("$SYNC_FOLDER/$path")

    @Test
    fun twoDevicesComeToTheSameRecordsAfterEditsMadeApart() = runTest {
        vault()
        val a = Device(FIRST_DEVICE)
        val b = Device(SECOND_DEVICE)
        a.stores.edit("router", "r1", "a")
        b.stores.edit("router", "r2", "b")
        b.stores.edit("snippet", "s1", "b")
        time += 1000
        a.stores.edit("router", "both", "older")
        time += 1000
        b.stores.edit("router", "both", "newer")
        a.sync.round()
        b.sync.round()
        a.sync.round()
        for (device in listOf(a, b)) {
            assertEquals("a", device.stores.value("router", "r1"), device.id)
            assertEquals("b", device.stores.value("router", "r2"), device.id)
            assertEquals("b", device.stores.value("snippet", "s1"), device.id)
            assertEquals("newer", device.stores.value("router", "both"), device.id)
        }
        assertFalse(a.sync.round().wrote)
        assertFalse(b.sync.round().wrote)
    }

    @Test
    fun aDeletionReachesTheOtherDeviceAndAMissingFileTakesNothingAway() = runTest {
        vault()
        val a = Device(FIRST_DEVICE)
        val b = Device(SECOND_DEVICE)
        a.stores.edit("router", "r1", "a")
        a.stores.edit("router", "r2", "a")
        a.sync.round()
        b.sync.round()
        time += 1000
        a.stores.delete("router", "r1")
        a.sync.round()
        b.sync.round()
        assertNull(b.stores.value("router", "r1"))
        assertEquals("a", b.stores.value("router", "r2"))

        folder.files.remove("$SYNC_FOLDER/${statePath(FIRST_DEVICE)}")
        folder.files.remove("$SYNC_FOLDER/${devicePath(FIRST_DEVICE)}")
        b.sync.round()
        assertEquals("a", b.stores.value("router", "r2"))
    }

    @Test
    fun aFileChangedOnTheServiceIsRefusedAndTheOthersAreTaken() = runTest {
        vault()
        val a = Device(FIRST_DEVICE)
        val b = Device(SECOND_DEVICE)
        val c = Device(THIRD_DEVICE)
        b.stores.edit("router", "rb", "b")
        c.stores.edit("router", "rc", "c")
        c.sync.round()
        b.sync.round()
        val changed = file(statePath(SECOND_DEVICE))
        changed[changed.size - 1] = (changed[changed.size - 1] + 1).toByte()
        val round = a.sync.round()
        assertEquals("${statePath(SECOND_DEVICE)} was changed after it was written", round.refused[statePath(SECOND_DEVICE)]?.message)
        assertNull(a.stores.value("router", "rb"))
        assertEquals("c", a.stores.value("router", "rc"))
    }

    @Test
    fun aStateOfAnotherVaultIsRefused() = runTest {
        vault()
        val a = Device(FIRST_DEVICE)
        val (_, other) = createVault("another password altogether", null, SECOND_DEVICE, passes = 1, memory = 8192)
        val foreign = writeState(other, SECOND_DEVICE, 1, listOf(Record("router", "x", Hlc(time, 0, "d"), data = JsonObject(mapOf("value" to JsonPrimitive("x"))))))
        service.write(statePath(SECOND_DEVICE), foreign)
        val round = a.sync.round()
        assertEquals("${statePath(SECOND_DEVICE)} belongs to another vault", round.refused[statePath(SECOND_DEVICE)]?.message)
        assertNull(a.stores.value("router", "x"))
    }

    // the device named its third state, which this one never read; the service gives the first back, with the tag it had then
    @Test
    fun aStateGivenBackFromBeforeIsRefusedThoughItsDeviceNamedANewerOneThatWasNeverRead() = runTest {
        vault()
        val a = Device(FIRST_DEVICE)
        val b = Device(SECOND_DEVICE)
        b.stores.edit("router", "r", "first")
        b.sync.round()
        val first = file(statePath(SECOND_DEVICE)).copyOf()
        a.sync.round()
        for (value in listOf("second", "third")) {
            time += 1000
            b.stores.edit("router", "r", value)
            b.sync.round()
        }
        folder.files["$SYNC_FOLDER/${statePath(SECOND_DEVICE)}"] = first
        val round = a.sync.round()
        val refusal = round.refused[statePath(SECOND_DEVICE)]?.message
        assertEquals("${statePath(SECOND_DEVICE)} is older than the copy read before: the service gave back an earlier version", refusal)
        assertEquals("first", a.stores.value("router", "r"))
    }

    // a device of a newer version shares a kind the older one does not know: the older keeps it, only encrypted on its disk, since such a
    // kind may hold secrets, and passes it on
    @Test
    fun recordsOfAKindThisVersionDoesNotKnowGoThroughItToAnotherDevice() = runTest {
        vault()
        val newer = Device(FIRST_DEVICE, kinds = setOf("router", "future"))
        val older = Device(SECOND_DEVICE, kinds = setOf("router"))
        newer.stores.edit("future", "f", "from a newer version")
        newer.sync.round()
        older.sync.round()
        assertNull(older.stores.value("future", "f"))
        assertTrue(older.memory.texts.values.none { "from a newer version" in it })
        older.sync = older.synchronizer()
        older.stores.edit("router", "r", "older")
        older.sync.round()
        folder.files.remove("$SYNC_FOLDER/${statePath(FIRST_DEVICE)}")
        folder.files.remove("$SYNC_FOLDER/${devicePath(FIRST_DEVICE)}")
        val third = Device(THIRD_DEVICE, kinds = setOf("router", "future"))
        third.sync.round()
        assertEquals("from a newer version", third.stores.value("future", "f"))
    }

    // the round after reads no file of the others: the state of the device keeps what it merged rather than going back to the record of
    // its store, and passes it on
    @Test
    fun aRecordAStoreDidNotTakeStaysInTheStateOfItsDevice() = runTest {
        vault()
        val a = Device(FIRST_DEVICE, kept = setOf("router"))
        val b = Device(SECOND_DEVICE)
        a.stores.edit("router", "r", "here")
        time += 1000
        b.stores.edit("router", "r", "there")
        b.sync.round()
        a.sync.round()
        assertEquals("here", a.stores.value("router", "r"))
        assertFalse(a.sync.round().wrote)
        a.sync = a.synchronizer()
        assertFalse(a.sync.round().wrote)
        folder.files.remove("$SYNC_FOLDER/${statePath(SECOND_DEVICE)}")
        folder.files.remove("$SYNC_FOLDER/${devicePath(SECOND_DEVICE)}")
        val c = Device(THIRD_DEVICE)
        c.sync.round()
        assertEquals("there", c.stores.value("router", "r"))
    }

    // the file of a device tells the others its name and when it was last there: with each state, after a new name and once a day
    @Test
    fun aDeviceTellsTheOthersItsNameAndWhenItWasLastThere() = runTest {
        vault()
        val a = Device(FIRST_DEVICE)
        val b = Device(SECOND_DEVICE)
        a.stores.edit("router", "r", "a")
        a.sync.round()
        b.sync.round()
        assertEquals(DeviceContent(FIRST_DEVICE.take(4), "test", "1.4.0", 1, time), b.sync.devices().getValue(FIRST_DEVICE))
        val written = file(devicePath(FIRST_DEVICE)).copyOf()
        time += 60_000
        a.sync.round()
        assertContentEquals(written, file(devicePath(FIRST_DEVICE)))

        a.sync = Synchronizer(service, keys, FIRST_DEVICE, DeviceContent("desk", "test", "1.4.0", 0), a.stores, a.memory, clock)
        a.sync.round()
        b.sync.round()
        assertEquals("desk", b.sync.devices().getValue(FIRST_DEVICE).name)
        time += 25 * 60 * 60 * 1000L
        a.sync.round()
        b.sync.round()
        assertEquals(time, b.sync.devices().getValue(FIRST_DEVICE).at)
    }

    @Test
    fun aKindLeftOutNeitherLeavesTheDeviceNorComesIn() = runTest {
        vault()
        val a = Device(FIRST_DEVICE)
        val b = Device(SECOND_DEVICE, out = setOf("snippet"))
        a.stores.edit("snippet", "sa", "a")
        b.stores.edit("snippet", "sb", "b")
        b.stores.edit("router", "rb", "b")
        a.sync.round()
        b.sync.round()
        a.sync.round()
        assertNull(a.stores.value("snippet", "sb"))
        assertNull(b.stores.value("snippet", "sa"))
        assertEquals("b", b.stores.value("snippet", "sb"))
        assertEquals("b", a.stores.value("router", "rb"))
    }

    // a device that lost its memory, reinstalled over the same data, writes on from the version the service holds from it
    @Test
    fun aDeviceThatLostItsMemoryGoesOnFromTheVersionItWrote() = runTest {
        vault()
        val a = Device(FIRST_DEVICE)
        val b = Device(SECOND_DEVICE)
        for (value in listOf("one", "two", "three")) {
            time += 1000
            a.stores.edit("router", "r", value)
            a.sync.round()
        }
        b.sync.round()
        a.memory.texts.clear()
        a.sync = a.synchronizer()
        time += 1000
        a.stores.edit("router", "r", "after")
        a.sync.round()
        val round = b.sync.round()
        assertTrue(round.refused.isEmpty())
        assertEquals("after", b.stores.value("router", "r"))
    }

    @Test
    fun aVaultWithANewKeyStopsTheDeviceBeforeItWrites() = runTest {
        vault()
        val a = Device(FIRST_DEVICE)
        a.sync.round()
        val written = file(statePath(FIRST_DEVICE)).copyOf()
        val header = vaultHeader(assertNotNull(service.read(VAULT)).bytes)
        val (rekeyed, _) = rekey(keys, header, "a new password for every device", null, SECOND_DEVICE, passes = 1, memory = 8192)
        service.write(VAULT, rekeyed)
        a.stores.edit("router", "r", "a")
        val error = assertFailsWith<DeviceException> { a.sync.round() }
        assertEquals("the key of the vault was changed on another device: enter the new password", error.message)
        assertContentEquals(written, file(statePath(FIRST_DEVICE)))
    }

    // a vault put back from before the key changed, from a copy or the history of the service, does not set a device going again under the
    // earlier key: the device remembers the newer epoch it saw, in the vault or in a state of another device, and writes nothing
    @Test
    fun aVaultGivenBackFromBeforeANewKeyKeepsTheDeviceStopped() = runTest {
        vault()
        val earlier = keys
        val a = Device(FIRST_DEVICE)
        a.sync.round()
        val before = file(VAULT).copyOf()
        val (rekeyed, newer) = rekey(keys, vaultHeader(before), "a new password for every device", null, SECOND_DEVICE, passes = 1, memory = 8192)
        service.write(VAULT, rekeyed)
        assertFailsWith<KeyChangedException> { a.sync.round() }
        service.write(VAULT, before)
        val written = file(statePath(FIRST_DEVICE)).copyOf()
        a.stores.edit("router", "r", "a")
        assertFailsWith<KeyChangedException> { a.sync.round() }
        a.sync = a.synchronizer()
        assertFailsWith<KeyChangedException> { a.sync.round() }
        assertContentEquals(written, file(statePath(FIRST_DEVICE)))

        // a device that never saw the new vault stops at the state another device wrote under the new key
        keys = newer
        val b = Device(SECOND_DEVICE)
        service.write(VAULT, rekeyed)
        b.sync.round()
        service.write(VAULT, before)
        keys = earlier
        val c = Device(THIRD_DEVICE)
        assertEquals(2, assertFailsWith<KeyChangedException> { c.sync.round() }.epoch)
        assertNull(folder.files["$SYNC_FOLDER/${statePath(THIRD_DEVICE)}"])
    }

    // a device that holds the newest key and finds a vault with an earlier one, or an earlier copy of the vault of its key, tells of it
    // and writes nothing
    @Test
    fun aDeviceWithTheNewestKeyRefusesAVaultGivenBack() = runTest {
        vault()
        val first = file(VAULT).copyOf()
        val (rekeyed, newer) = rekey(keys, vaultHeader(first), "a new password for every device", null, FIRST_DEVICE, passes = 1, memory = 8192)
        service.write(VAULT, rekeyed)
        keys = newer
        val a = Device(FIRST_DEVICE)
        a.sync.round()
        val written = file(statePath(FIRST_DEVICE)).copyOf()
        service.write(VAULT, first)
        a.stores.edit("router", "r", "a")
        assertEquals("the service gave back a vault with an earlier key than this device saw", assertFailsWith<DeviceException> { a.sync.round() }.message)
        assertContentEquals(written, file(statePath(FIRST_DEVICE)))

        val (rewrapped, _) = rewrap(newer, vaultHeader(rekeyed), "yet another password of five words", null, FIRST_DEVICE, passes = 1, memory = 8192)
        service.write(VAULT, rewrapped)
        a.sync.round()
        service.write(VAULT, rekeyed)
        assertEquals("vault is older than the copy read before: the service gave back an earlier version", assertFailsWith<DeviceException> { a.sync.round() }.message)
    }

    // a new key of the data writes the state and the file of the device again, so a device that joins with the new password reads them
    @Test
    fun aNewKeyWritesTheStateAndTheFileOfTheDeviceAgain() = runTest {
        vault()
        val a = Device(FIRST_DEVICE)
        a.stores.edit("router", "r", "a")
        a.sync.round()
        val (rekeyed, newer) = rekey(keys, vaultHeader(file(VAULT)), "a new password for every device", null, FIRST_DEVICE, passes = 1, memory = 8192)
        service.write(VAULT, rekeyed)
        keys = newer
        a.sync = a.synchronizer()
        assertTrue(a.sync.round().wrote)
        assertEquals(2, readState(newer, FIRST_DEVICE, file(statePath(FIRST_DEVICE))).header.epoch)
        assertEquals(2, readDevice(newer, FIRST_DEVICE, file(devicePath(FIRST_DEVICE))).header.epoch)
        val b = Device(SECOND_DEVICE)
        b.sync.round()
        assertEquals("a", b.stores.value("router", "r"))
    }

    // a device whose files left the service, as one turned off, leaves the list of the others
    @Test
    fun aDeviceWhoseFilesLeftTheServiceLeavesTheList() = runTest {
        vault()
        val a = Device(FIRST_DEVICE)
        val b = Device(SECOND_DEVICE)
        b.sync.round()
        a.sync.round()
        assertEquals(setOf(SECOND_DEVICE), a.sync.devices().keys)
        for (path in listOf(statePath(SECOND_DEVICE), devicePath(SECOND_DEVICE))) service.delete(path)
        a.sync.round()
        assertTrue(a.sync.devices().isEmpty())
    }

    @Test
    fun aVaultGoneFromTheServiceStopsTheDevice() = runTest {
        vault()
        val a = Device(FIRST_DEVICE)
        a.sync.round()
        service.delete(VAULT)
        val error = assertFailsWith<DeviceException> { a.sync.round() }
        assertEquals("the vault is no longer on the service", error.message)
    }

    // a device in two places carries the records of each to the other, those of a kind its stores do not keep as well
    @Test
    fun devicesOfTwoPlacesGetTheRecordsOfEachOtherThroughADeviceInBoth() = runTest {
        vault()
        val other = FolderService(MemoryFolder())
        other.writeIf(VAULT, file(VAULT), null)
        val bridge = Device(FIRST_DEVICE, kinds = setOf("router"))
        val here = Device(SECOND_DEVICE, kinds = setOf("router", "future"))
        val there = Device(THIRD_DEVICE, kinds = setOf("router", "future"), place = other)
        here.stores.edit("router", "r1", "here")
        here.stores.edit("future", "f", "from a newer version")
        there.stores.edit("router", "r2", "there")
        here.sync.round()
        there.sync.round()
        val both = bridge.inPlaces("" to service, "second" to other)
        both.round()
        both.round()
        here.sync.round()
        there.sync.round()
        assertEquals("there", here.stores.value("router", "r2"))
        assertEquals("here", there.stores.value("router", "r1"))
        assertEquals("from a newer version", there.stores.value("future", "f"))
        assertNull(bridge.stores.value("future", "f"))
        assertEquals(setOf(SECOND_DEVICE, THIRD_DEVICE), both.devices().keys)
    }

    // a new password or key on a device of one place reaches the vault of the other through the device in both; a newer key stops that
    // device before it writes in either place
    @Test
    fun aPlaceThatLagsBehindTakesTheNewestVaultAndANewerKeyStopsTheDeviceEverywhere() = runTest {
        vault()
        val other = FolderService(MemoryFolder())
        other.writeIf(VAULT, file(VAULT), null)
        val bridge = Device(FIRST_DEVICE)
        var both = bridge.inPlaces("" to service, "second" to other)
        bridge.stores.edit("router", "r", "a")
        both.round()
        val (rewrapped, _) = rewrap(keys, vaultHeader(file(VAULT)), "yet another password of five words", null, SECOND_DEVICE, passes = 1, memory = 8192)
        service.write(VAULT, rewrapped)
        both.round()
        assertContentEquals(rewrapped, assertNotNull(other.read(VAULT)).bytes)

        val written = assertNotNull(other.read(statePath(FIRST_DEVICE))).bytes
        val (rekeyed, newer) = rekey(keys, vaultHeader(rewrapped), "a new password for every device", null, SECOND_DEVICE, passes = 1, memory = 8192)
        service.write(VAULT, rekeyed)
        bridge.stores.edit("router", "r", "b")
        assertFailsWith<KeyChangedException> { both.round() }
        assertFailsWith<KeyChangedException> { both.round() }
        assertContentEquals(written, assertNotNull(other.read(statePath(FIRST_DEVICE))).bytes)

        keys = newer
        both = bridge.inPlaces("" to service, "second" to other)
        both.round()
        assertContentEquals(rekeyed, assertNotNull(other.read(VAULT)).bytes)
        assertEquals(2, readState(newer, FIRST_DEVICE, assertNotNull(other.read(statePath(FIRST_DEVICE))).bytes).header.epoch)
    }

    // a place that does not answer waits for the next round while the other goes on; the round fails only when no place went through
    @Test
    fun aPlaceThatFailsWaitsWhileTheOthersGoOn() = runTest {
        vault()
        val other = Down(FolderService(MemoryFolder()))
        other.writeIf(VAULT, file(VAULT), null)
        val bridge = Device(FIRST_DEVICE)
        val both = bridge.inPlaces("" to service, "second" to other)
        other.down = true
        bridge.stores.edit("router", "r", "a")
        val round = both.round()
        assertTrue(round.wrote)
        assertEquals("rd-test does not answer", round.failed.getValue("second").message)
        assertEquals(1, readState(keys, FIRST_DEVICE, file(statePath(FIRST_DEVICE))).header.version)

        other.down = false
        assertTrue(both.round().failed.isEmpty())
        assertNotNull(other.read(statePath(FIRST_DEVICE)))
        other.down = true
        service.delete(VAULT)
        assertEquals("the vault is no longer on the service", assertFailsWith<DeviceException> { both.round() }.message)
    }
}
