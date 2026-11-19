package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.keenetic.hex
import com.vasmarfas.networkhelm.keenetic.sha256
import com.vasmarfas.networkhelm.session.TextStore
import com.vasmarfas.networkhelm.session.keep
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

// an entry of a store as devices exchange it; the fields of data that a newer version added stay in it untouched. Hlc is the last change
// of the record, and stamps hold the last changes of the fields changed before it; floor is the newest removal the record outlived, and a
// field stamped no later than it is gone. A record without either is written as schema 1.0 wrote it
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Record(
    val kind: String,
    val id: String,
    val hlc: Hlc,
    val deleted: Boolean = false,
    val data: JsonObject = JsonObject(emptyMap()),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val stamps: Map<String, Hlc> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val floor: Hlc? = null,
) {
    fun stamp(field: String) = stamps[field] ?: hlc
}

@Serializable
private class RecordList(val records: List<Record>)

val recordJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

// the id of an entry that its store keys by these values, so that every copy of the app and every device gives one entry one id
fun recordId(vararg key: String): String = hex(sha256(JsonArray(key.map(::JsonPrimitive)).toString().encodeToByteArray()).copyOf(16))

fun recordsFile(file: String) = file.removeSuffix(".json") + ".records.json"

// an entry that is new or changed since the records were matched last takes the stamp, each changed field with it, and an entry that is
// gone a tombstone; an entry back after its tombstone starts above it
internal fun List<Record>.matched(kind: String, entries: Map<String, JsonObject>, stamp: Hlc): List<Record> {
    val known = mapTo(HashSet()) { it.id }
    return map { record ->
        val entry = entries[record.id]
        when {
            entry == null -> if (record.deleted) record else Record(kind, record.id, stamp, deleted = true)
            record.deleted -> Record(kind, record.id, stamp, data = entry, floor = record.hlc)
            entry.all { (key, value) -> record.data[key] == value } -> record
            else -> {
                val kept = record.data.keys.filter { entry[it] == null || entry[it] == record.data[it] }
                record.copy(hlc = stamp, data = JsonObject(record.data + entry), stamps = kept.associateWith(record::stamp))
            }
        }
    } + entries.filterKeys { it !in known }.map { (id, data) -> Record(kind, id, stamp, data = data) }
}

// the ids, stamps and tombstones of the entries of a file lie in a file of their own beside it, so that the file keeps the form the
// versions before 1.4.0 read; whoever changed the file, this copy of the app, another one or an older version, is matched here
class FileRecords(private val store: TextStore, private val file: String, private val kind: String, private val clock: HlcClock) {
    private val name = recordsFile(file)

    var all: List<Record> = emptyList()
        private set

    // the edits matches stamped so far, and how many of them there were when the round took the records
    private var stamps = 0
    private var taken = 0

    // whether an edit was stamped since the round took the records, by an edit of this copy or one a match found in the file: the merged
    // records then wait for the next round, which merges that edit too
    val edited get() = stamps != taken

    // read gives what the file holds now, by id, or null where it cannot be read; it is called after the records are read, and a
    // copy of the app writes the file before its records, so the records never hold an edit the file does not: an entry another
    // copy removed or added meanwhile is neither brought back nor removed with a new stamp
    suspend fun match(read: suspend () -> Map<String, JsonObject>?) {
        val kept = store.read(name)?.let(::decode)
        val entries = read() ?: return
        kept?.maxOfOrNull { it.hlc }?.let(clock::observe)
        if (kept == null) keepOlder()
        // what the file held before its records were kept is older than any edit a device has stamped
        val stamp = if (kept == null) Hlc(0, 0, clock.device) else clock.now()
        val matched = kept.orEmpty().matched(kind, entries, stamp)
        if (matched != kept) {
            stamps++
            store.keep(name, recordJson.encodeToString(RecordList.serializer(), RecordList(matched)))
        }
        all = matched
    }

    // the records as a round takes them for its merge
    fun take(): List<Record> {
        taken = stamps
        return all
    }

    // the records as their file holds them, before they are matched
    suspend fun stored(): List<Record> = store.read(name)?.let(::decode).orEmpty()

    // records merged with those of other devices, kept as they are, after the store wrote its file from them
    suspend fun keep(records: List<Record>) {
        store.keep(name, recordJson.encodeToString(RecordList.serializer(), RecordList(records)))
        all = records
    }

    // merged records of a store of entries, after the store matched its file once more under the lock its edits take: write puts the live
    // ones into the file of the store, then the records are kept with their known fields as this platform encodes them, so that matching the file stamps nothing,
    // and with the fields of a newer version
    suspend fun <T> put(merged: List<Record>, serializer: KSerializer<T>, write: suspend (List<T>) -> Unit) {
        if (edited) return
        // a record that lost fields to a removal on another device, as an edit made meanwhile leaves it, makes no entry: the store goes
        // without it, and its next match removes it on every device
        val live = merged.filterNot { it.deleted }.mapNotNull { record ->
            try {
                record to recordJson.decodeFromJsonElement(serializer, record.data)
            } catch (e: IllegalArgumentException) {
                null
            }
        }.toMap()
        write(live.values.toList())
        keep(merged.map { record -> live[record]?.let { record.copy(data = JsonObject(record.data + recordJson.encodeToJsonElement(serializer, it).jsonObject)) } ?: record })
    }

    // damaged records are made again from the file; its entries then count as old as those of a version before
    private fun decode(text: String): List<Record>? = try {
        recordJson.decodeFromString(RecordList.serializer(), text).records
    } catch (e: IllegalArgumentException) {
        null
    }

    // the file as a version before 1.4.0 left it stays beside, once
    private suspend fun keepOlder() {
        val older = file.removeSuffix(".json") + "-1.3.json"
        if (store.read(older) == null) store.read(file)?.let { store.keep(older, it) }
    }
}
