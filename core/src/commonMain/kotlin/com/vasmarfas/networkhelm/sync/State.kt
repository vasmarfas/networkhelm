package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// all a device knows, written whole; records of kinds and fields this version does not know go through it untouched
@Serializable
data class StateContent(val schema: Schema, val records: List<Record>)

// state: the version of the state file the device wrote last, so that a device that never read it sees one given back from before;
// at: when the device wrote this file, for the list of devices
@Serializable
data class DeviceContent(val name: String, val platform: String, val app: String, val state: Long, val at: Long = 0)

class Read<T>(val header: Header, val content: T)

const val TOMBSTONE_LIFE = 180 * 24 * 60 * 60 * 1000L

suspend fun writeState(keys: Keys, device: String, version: Long, records: List<Record>): ByteArray =
    write(keys, "state", statePath(device), device, version, StateContent(SCHEMA, records), StateContent.serializer(), stateKey(keys.key))

suspend fun readState(keys: Keys, device: String, bytes: ByteArray): Read<StateContent> =
    read(keys, "state", statePath(device), device, bytes, StateContent.serializer(), stateKey(keys.key))

suspend fun writeDevice(keys: Keys, device: String, version: Long, content: DeviceContent): ByteArray =
    write(keys, "device", devicePath(device), device, version, content, DeviceContent.serializer(), deviceKey(keys.key))

suspend fun readDevice(keys: Keys, device: String, bytes: ByteArray): Read<DeviceContent> =
    read(keys, "device", devicePath(device), device, bytes, DeviceContent.serializer(), deviceKey(keys.key))

private suspend fun <T> write(keys: Keys, kind: String, path: String, device: String, version: Long, content: T, serializer: KSerializer<T>, kindKey: ByteArray) =
    seal(Header(kind, keys.vault, keys.epoch, device, version, SCHEMA), path, fileJson.encodeToString(serializer, content).encodeToByteArray(), kindKey)

private suspend fun <T> read(keys: Keys, kind: String, path: String, device: String, bytes: ByteArray, serializer: KSerializer<T>, kindKey: ByteArray): Read<T> {
    val file = sealed(bytes, path)
    val header = file.header
    if (header.vault != keys.vault) throw DeviceException(ErrorCategory.General, "$path belongs to another vault")
    if (header.epoch < keys.epoch) throw DeviceException(ErrorCategory.General, "$path was written with an earlier key of the vault")
    if (header.epoch > keys.epoch) throw KeyChangedException(header.epoch)
    if (header.kind != kind || header.author != device) throw DeviceException(ErrorCategory.General, "$path holds a file written for another place")
    if (header.schema.major > SCHEMA.major) throw newer(path)
    val content = file.open(path, kindKey) ?: throw DeviceException(ErrorCategory.PermissionDenied, "$path was not written with the key of this vault")
    return try {
        Read(header, fileJson.decodeFromString(serializer, content.decodeToString()))
    } catch (e: IllegalArgumentException) {
        throw notAFile(path)
    }
}

internal suspend fun stateKey(key: ByteArray) = Sodium.derive(key, 0, "NHSstate", Sodium.KEY)

internal suspend fun deviceKey(key: ByteArray) = Sodium.derive(key, 0, "NHSdevic", Sodium.KEY)

internal suspend fun writtenKey(key: ByteArray) = Sodium.derive(key, 0, "NHSwrote", Sodium.KEY)

// the last writer wins each field, so two devices that changed different fields of one record keep both; a removal takes every field
// written up to it, and a record changed after it comes back with the fields changed since. Equal stamps fall to the removal and then to
// the value, so neither the order of the states, their grouping nor a repeat of one changes the result
fun merge(states: List<List<Record>>): List<Record> =
    states.flatten().groupBy { it.kind to it.id }.values.map(::merged).sortedWith(compareBy({ it.kind }, { it.id }))

private fun merged(versions: List<Record>): Record {
    val first = versions.first()
    val floor = versions.mapNotNull { if (it.deleted) it.hlc else it.floor }.maxOrNull()
    val alive = versions.filter { !it.deleted && (floor == null || it.hlc > floor) }
    if (alive.isEmpty()) return Record(first.kind, first.id, floor ?: first.hlc, deleted = true)
    val fields = HashMap<String, Pair<Hlc, JsonElement>>()
    for (version in alive) {
        for ((key, value) in version.data) {
            val stamp = version.stamp(key)
            if (floor != null && stamp <= floor) continue
            val held = fields[key]
            if (held == null || stamp > held.first || stamp == held.first && value.toString() > held.second.toString()) fields[key] = stamp to value
        }
    }
    val hlc = alive.maxOf { it.hlc }
    return Record(
        first.kind,
        first.id,
        hlc,
        data = JsonObject(fields.keys.sorted().associateWith { fields.getValue(it).second }),
        stamps = fields.filterValues { it.first != hlc }.mapValues { it.value.first },
        floor = floor,
    )
}

// what this device keeps after it read the states of the others; tombstones older than TOMBSTONE_LIFE go. A device that last merged
// before then keeps of its records from that time only those another device still has: the others were removed while it was away
// and their tombstones are gone, so they are not brought back. Only a kind some state read here carries tells that: without such a
// state, as with no other device, files refused or the kind left out elsewhere, the device keeps its records of the kind whole
fun merged(own: List<Record>, others: List<List<Record>>, lastMerge: Long?, now: Long): List<Record> {
    val horizon = now - TOMBSTONE_LIFE
    val kept = if (lastMerge == null || lastMerge >= horizon) {
        own
    } else {
        val elsewhere = others.flatten()
        val kinds = elsewhere.mapTo(HashSet()) { it.kind }
        val ids = elsewhere.mapTo(HashSet()) { it.kind to it.id }
        own.filter { it.kind !in kinds || it.hlc.time > lastMerge || (it.kind to it.id) in ids }
    }
    return merge(others + listOf(kept)).filterNot { it.deleted && it.hlc.time < horizon }
}

// the highest version read from each file: one that comes back lower, or lower than its device last named, was given back by the
// service from before and is not applied
@Serializable
data class Seen(val versions: Map<String, Long> = emptyMap()) {
    fun check(path: String, version: Long, named: Long? = null) {
        if (version < maxOf(versions[path] ?: 0, named ?: 0)) {
            throw DeviceException(ErrorCategory.General, "$path is older than the copy read before: the service gave back an earlier version")
        }
    }

    fun with(path: String, version: Long) = Seen(versions + (path to maxOf(version, versions[path] ?: 0)))
}
