package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.session.TextStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class FileRecordsTest {

    private class Kept : TextStore {
        val files = HashMap<String, String>()
        var writes = 0

        override suspend fun read(name: String) = files[name]

        override suspend fun write(name: String, text: String) {
            writes++
            files[name] = text
        }
    }

    private class Fixed(private val millis: Long) : Clock {
        override fun now() = Instant.fromEpochMilliseconds(millis)
    }

    private fun entry(vararg fields: Pair<String, String>) = JsonObject(fields.associate { (key, value) -> key to JsonPrimitive(value) })

    @Test
    fun theIdOfAnEntryIsTheSameEverywhereAndForever() {
        // every device keeps the ids it was given; another derivation would part one router into two
        assertEquals("87c1a6b84efe322457265406183fc17e", recordId("192.0.2.1", "admin"))
        assertEquals("d8f0f771d93c90d6ef9eb317b1ee5d07", recordId("Routes", "/ip route print"))
        assertNotEquals(recordId("192.0.2.1/a", "dmin"), recordId("192.0.2.1", "a/dmin"))
        assertNotEquals(recordId("a", ""), recordId("", "a"))
    }

    @Test
    fun anEntryTakesTheStampWhenItIsNewOrChangedAndATombstoneWhenItIsGone() {
        val first = Hlc(1, 0, "w")
        val second = Hlc(2, 0, "w")
        val third = Hlc(3, 0, "w")
        val start = emptyList<Record>().matched("router", mapOf("r1" to entry("comment" to "core"), "r2" to entry("comment" to "")), first)
        assertEquals(listOf(Record("router", "r1", first, data = entry("comment" to "core")), Record("router", "r2", first, data = entry("comment" to ""))), start)

        // a newer version gave r1 a field this one does not know; an edit of r1 keeps it with the stamp of its own last change
        val newer = start.map { if (it.id == "r1") it.copy(data = JsonObject(it.data + ("added" to JsonPrimitive("1.5")))) else it }
        val next = newer.matched("router", mapOf("r1" to entry("comment" to "edge"), "r3" to entry("comment" to "")), second)
        assertEquals(
            listOf(
                Record("router", "r1", second, data = entry("comment" to "edge", "added" to "1.5"), stamps = mapOf("added" to first)),
                Record("router", "r2", second, deleted = true),
                Record("router", "r3", second, data = entry("comment" to "")),
            ),
            next,
        )
        assertEquals(next, next.matched("router", mapOf("r1" to entry("comment" to "edge"), "r3" to entry("comment" to "")), third))

        val back = next.matched("router", mapOf("r1" to entry("comment" to "edge"), "r2" to entry("comment" to "again"), "r3" to entry("comment" to "")), third)
        assertEquals(Record("router", "r2", third, data = entry("comment" to "again"), floor = second), back.single { it.id == "r2" })
    }

    @Test
    fun recordsFollowAnyHistoryOfEditsAndKeepEveryIdTheyHad() {
        repeat(300) { seed ->
            val random = Random(seed)
            val file = LinkedHashMap<String, JsonObject>()
            var records = emptyList<Record>()
            repeat(60) { step ->
                val id = "e${random.nextInt(8)}"
                when (random.nextInt(5)) {
                    0, 1 -> file[id] = entry("value" to "${random.nextInt(3)}")
                    2 -> file.remove(id)
                    3 -> records = records.map { if (it.id == id && !it.deleted) it.copy(data = JsonObject(it.data + ("newer" to JsonPrimitive(step)))) else it }
                }
                val before = records.associateBy { it.id }
                val stamp = Hlc(step + 1L, 0, "w")
                records = records.matched("entry", file, stamp)

                assertEquals(file.keys, records.filterNot { it.deleted }.map { it.id }.toSet(), "seed $seed")
                assertEquals(records.size, records.map { it.id }.toSet().size, "seed $seed")
                assertTrue(before.keys.all { id -> records.any { it.id == id } }, "seed $seed")
                for (record in records) {
                    val old = before[record.id]
                    val entry = file[record.id]
                    if (entry != null) assertTrue(entry.all { (key, value) -> record.data[key] == value }, "seed $seed")
                    val changed = old == null || old.deleted != record.deleted || entry != null && entry.any { (key, value) -> old.data[key] != value }
                    assertEquals(if (changed) stamp else old.hlc, record.hlc, "seed $seed")
                    if (old != null && !old.deleted && !record.deleted) assertEquals(old.data["newer"], record.data["newer"], "seed $seed")
                }
            }
        }
    }

    @Test
    fun theFirstMatchKeepsTheOlderFileBesideAndCountsItsEntriesAsOld() = runTest {
        val store = Kept()
        store.files["routers.json"] = "[older]"
        val records = FileRecords(store, "routers.json", "router", HlcClock("me"))
        records.match { mapOf("r1" to entry("comment" to "core")) }
        assertEquals("[older]", store.files["routers-1.3.json"])
        assertEquals(listOf(Record("router", "r1", Hlc(0, 0, "me"), data = entry("comment" to "core"))), records.all)

        store.files["routers.json"] = "[newer]"
        val writes = store.writes
        records.match { mapOf("r1" to entry("comment" to "core")) }
        assertEquals(writes, store.writes)
        records.match { mapOf("r1" to entry("comment" to "edge")) }
        assertTrue(records.all.single().hlc > Hlc(0, 0, "me"))
        assertEquals("[older]", store.files["routers-1.3.json"])

        val another = FileRecords(store, "routers.json", "router", HlcClock("another"))
        another.match { mapOf("r1" to entry("comment" to "edge")) }
        assertEquals(records.all, another.all)
    }

    @Test
    fun damagedRecordsAreMadeAgainFromTheFileAndTheOlderCopyStays() = runTest {
        val store = Kept()
        store.files["snippets.json"] = "[older]"
        val records = FileRecords(store, "snippets.json", "snippet", HlcClock("me"))
        records.match { mapOf("s1" to entry()) }
        store.files["snippets.json"] = "[newer]"
        store.files["snippets.records.json"] = "{\"records\": [{\"kind\": "
        records.match { mapOf("s1" to entry(), "s2" to entry()) }
        assertEquals(listOf(Record("snippet", "s1", Hlc(0, 0, "me")), Record("snippet", "s2", Hlc(0, 0, "me"))), records.all)
        assertEquals("[older]", store.files["snippets-1.3.json"])
        assertEquals(records.all, FileRecords(store, "snippets.json", "snippet", HlcClock("me")).also { it.match { mapOf("s1" to entry(), "s2" to entry()) } }.all)
    }

    @Test
    fun aCopyWhoseClockIsBehindStampsLaterThanTheCopyThatWroteBefore() = runTest {
        val store = Kept()
        val ahead = FileRecords(store, "snippets.json", "snippet", HlcClock("ahead", Fixed(10_000)))
        ahead.match { emptyMap() }
        ahead.match { mapOf("s1" to entry()) }
        val behind = FileRecords(store, "snippets.json", "snippet", HlcClock("behind", Fixed(1_000)))
        behind.match { mapOf("s1" to entry(), "s2" to entry()) }
        assertEquals(Hlc(10_000, 0, "ahead"), behind.all.single { it.id == "s1" }.hlc)
        assertEquals(Hlc(10_000, 1, "behind"), behind.all.single { it.id == "s2" }.hlc)
    }

    @Test
    fun anEntryAnotherCopyRemovesOrAddsWhileACopyMatchesIsNeitherBroughtBackNorRemoved() = runTest {
        val store = Kept()
        val first = FileRecords(store, "routers.json", "router", HlcClock("first"))
        val second = FileRecords(store, "routers.json", "router", HlcClock("second"))
        first.match { mapOf("r1" to entry(), "r2" to entry()) }

        // the first copy writes its file and records after the second one read the records and before it reads the file
        second.match {
            first.match { mapOf("r1" to entry()) }
            mapOf("r1" to entry())
        }
        assertTrue(second.all.single { it.id == "r2" }.deleted)
        second.match {
            first.match { mapOf("r1" to entry(), "r3" to entry()) }
            mapOf("r1" to entry(), "r3" to entry())
        }
        assertEquals(listOf("r1", "r3"), second.all.filterNot { it.deleted }.map { it.id })
        assertTrue(FileRecords(store, "routers.json", "router", HlcClock("third")).also { it.match { mapOf("r1" to entry(), "r3" to entry()) } }.all.single { it.id == "r2" }.deleted)
    }

    @Test
    fun aFileThatCannotBeReadLeavesTheRecordsAsTheyAre() = runTest {
        val store = Kept()
        val records = FileRecords(store, "routers.json", "router", HlcClock("me"))
        records.match { mapOf("r1" to entry()) }
        val kept = store.files["routers.records.json"]
        records.match { null }
        assertEquals(kept, store.files["routers.records.json"])
        assertEquals(listOf("r1"), records.all.map { it.id })
    }

    // the user added a snippet after the round took the records, whether the store matched it at once or the match before the merged
    // records found it: they do not go over it, and the next round merges both
    @Test
    fun mergedRecordsWaitForTheNextRoundWhereAnEditWasStampedSinceTheRoundTookTheRecords() = runTest {
        val store = Kept()
        val records = FileRecords(store, "snippets.json", "snippet", HlcClock("me"))
        records.match { mapOf("s1" to entry("text" to "a")) }
        val merged = records.take() + Record("snippet", "s2", Hlc(5, 0, "other"), data = entry("text" to "b"))
        val written = ArrayList<List<JsonObject>>()
        records.match { mapOf("s1" to entry("text" to "a"), "s3" to entry("text" to "c")) }
        records.match { mapOf("s1" to entry("text" to "a"), "s3" to entry("text" to "c")) }
        records.put(merged, JsonObject.serializer()) { written += it }
        assertEquals(emptyList<List<JsonObject>>(), written)
        assertEquals(listOf("s1", "s3"), records.all.map { it.id })

        val next = merge(listOf(records.take(), merged))
        records.match { mapOf("s1" to entry("text" to "a"), "s3" to entry("text" to "c")) }
        records.put(next, JsonObject.serializer()) { written += it }
        assertEquals(listOf(listOf(entry("text" to "a"), entry("text" to "b"), entry("text" to "c"))), written)
    }

    // a device with no file yet: the first match, made as the app started, and the file it cannot read hold nothing back
    @Test
    fun aStoreWithoutItsFileTakesMergedRecordsWithTheFirstRound() = runTest {
        val records = FileRecords(Kept(), "settings.json", "setting", HlcClock("me"))
        records.match { mapOf("hidePasswords" to entry("value" to "true")) }
        records.match { null }
        records.take()
        records.match { null }
        assertFalse(records.edited)
    }
}
