package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.keenetic.hex
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.session.TextStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.io.encoding.Base64
import kotlin.time.Clock

// what the stores of this device give to synchronisation and take from it
interface SyncLocal {
    // the kinds of records the stores keep; a record of another kind that is not left out goes through the state untouched
    val kinds: Set<String>

    // a kind the user keeps on this device alone: its records neither leave the device nor come in
    fun leftOut(kind: String): Boolean

    // the records of the stores now, of their kinds that are not left out, each store matched with its file first
    suspend fun records(): List<Record>

    // the merged records of the kinds of the stores, for each store to take where they differ from its own
    suspend fun apply(records: List<Record>)
}

// what a device remembers of the vault between rounds: the versions and tags of the files it read, the version it wrote last, a hash
// of what it wrote and the file itself, encrypted as it went to the service, the devices as each named itself, and the highest epoch of
// the key it saw in a file of the vault
@Serializable
data class SyncMemory(
    val seen: Seen = Seen(),
    val tags: Map<String, String> = emptyMap(),
    val version: Long = 0,
    val written: String = "",
    val lastMerge: Long? = null,
    val state: String = "",
    val devices: Map<String, DeviceContent> = emptyMap(),
    val self: DeviceContent? = null,
    val epoch: Int = 0,
)

// a round read files of other devices, refused those that failed their checks, and may have changed the stores and written the state;
// failed holds the places of the vault, by id, that the round did not get through while others did
class Round(val read: Int, val refused: Map<String, DeviceException>, val applied: Boolean, val wrote: Boolean, val failed: Map<String, Exception> = emptyMap())

const val SYNC_MEMORY = "sync-memory.json"

private const val DAY = 24 * 60 * 60 * 1000L

// the key of the vault changed on another device: this one reads and writes nothing more until the user types the new password
class KeyChangedException(val epoch: Int) : DeviceException(ErrorCategory.PermissionDenied, "the key of the vault was changed on another device: enter the new password")

// the service holds a vault older than a key this device saw: put back from a copy or from the history of the service
fun vaultGivenBack() = DeviceException(ErrorCategory.General, "the service gave back a vault with an earlier key than this device saw")

// the highest epoch of the key this device saw in a place; above the epoch of its own key the device writes nothing until the new
// password opens the vault here
suspend fun seenEpoch(memory: TextStore, file: String = SYNC_MEMORY): Int = remembered(memory, file).epoch

// the id of a new device: 32 hex digits, the only form a list of the service lets through
suspend fun newDevice(): String = hex(Sodium.random(16))

// one device in one vault in one place: a round reads what the other devices wrote since the last one, merges it with the records of
// the stores, gives the stores what changed and writes the state of the device when it differs from what was written last. A file that
// fails a check is refused alone; a service that does not answer, or a vault changed under the device, ends the round before anything
// is written. Each place keeps its memory in a file of its own; besides names the files of the other places of the vault
class Synchronizer(
    private val service: SyncService,
    private val keys: Keys,
    private val device: String,
    private val about: DeviceContent,
    private val local: SyncLocal,
    private val memory: TextStore,
    private val clock: Clock = Clock.System,
    private val memoryFile: String = SYNC_MEMORY,
    private val besides: List<String> = emptyList(),
) {
    private val lock = Mutex()
    private var recovered = false

    suspend fun round(): Round = lock.withLock {
        val remembered = remembered(memory, memoryFile)
        if (remembered.epoch > keys.epoch) throw KeyChangedException(remembered.epoch)
        try {
            exchange(remembered)
        } catch (e: KeyChangedException) {
            remember(remembered.copy(epoch = e.epoch))
            throw e
        }
    }

    // the devices as each named itself last, for the list of devices of the vault
    suspend fun devices(): Map<String, DeviceContent> = remembered(memory, memoryFile).devices

    suspend fun seenEpoch(): Int = seenEpoch(memory, memoryFile)

    private suspend fun exchange(remembered: SyncMemory): Round {
        val now = clock.now().toEpochMilliseconds()
        val listed = service.list().associate { it.path to it.tag }
        var seen = checkVault(remembered.seen)
        var version = if (recovered) remembered.version else maxOf(remembered.version, written(listed))
        recovered = true

        // a device that last merged before the tombstones it missed were collected reads every file again
        val stale = remembered.lastMerge?.let { it < now - TOMBSTONE_LIFE } ?: true
        fun changed(path: String) = path in listed && (stale || listed[path] != remembered.tags[path])
        val tags = remembered.tags.toMutableMap()
        val refused = HashMap<String, DeviceException>()
        val others = ArrayList<List<Record>>()
        val ids = listed.keys.filter { it.startsWith("state/") || it.startsWith("devices/") }.map { it.substringAfter('/') }.filter { it != device }.toSet()
        // a device whose files left the service, as one turned off, leaves the list
        val devices = remembered.devices.filterKeys { it in ids }.toMutableMap()
        // the file of a device first: it names the version of its state, so a state given back from before is seen even unread
        for (id in ids) {
            val path = devicePath(id)
            if (!changed(path)) continue
            val stored = service.read(path) ?: continue
            try {
                val read = readDevice(keys, id, stored.bytes)
                seen.check(path, read.header.version)
                seen = seen.with(path, read.header.version)
                devices[id] = read.content
                listed[path]?.let { tags[path] = it }
            } catch (e: KeyChangedException) {
                throw e
            } catch (e: DeviceException) {
                refused[path] = e
            }
        }
        for (id in ids) {
            val path = statePath(id)
            // a state the device named newer than the one read is read again, though its tag is the one seen: a copy given back from
            // before carries the tag it had then
            val behind = (devices[id]?.state ?: 0) > (seen.versions[path] ?: 0)
            if (!changed(path) && !(behind && path in listed)) continue
            val stored = service.read(path) ?: continue
            try {
                val read = readState(keys, id, stored.bytes)
                seen.check(path, read.header.version, devices[id]?.state)
                seen = seen.with(path, read.header.version)
                others += read.content.records.filterNot { local.leftOut(it.kind) }
                listed[path]?.let { tags[path] = it }
            } catch (e: KeyChangedException) {
                throw e
            } catch (e: DeviceException) {
                refused[path] = e
            }
        }

        val held = local.records()
        // what the device merged last in its other places brings the kinds the stores do not keep from one place to another; a place it
        // has been away from for longer than tombstones live brings nothing, as records removed meanwhile would come back from it
        val elsewhere = besides.map { remembered(memory, it) }.filter { (it.lastMerge ?: 0) >= now - TOMBSTONE_LIFE }.flatMap { kept(it.state) }
        val own = held + kept(remembered.state) + elsewhere
        val merged = merged(own.filterNot { local.leftOut(it.kind) }, others, remembered.lastMerge, now)
        val stores = merged.filter { it.kind in local.kinds }
        val applied = stores != merge(listOf(held.filter { it.kind in local.kinds && !local.leftOut(it.kind) }))
        if (applied) local.apply(stores)
        // a hash under a key of the data: the memory on the disk tells nothing of the values in the state, and a new key writes the state
        // and the file of the device again
        val hash = hex(Sodium.blake2b(fileJson.encodeToString(ListSerializer(Record.serializer()), merged).encodeToByteArray(), writtenKey(keys.key), Sodium.KEY))
        var state = remembered.state
        val wrote = hash != remembered.written
        if (wrote) {
            version += 1
            val sealed = writeState(keys, device, version, merged)
            service.write(statePath(device), sealed)
            state = Base64.encode(sealed)
        }
        // the file of the device tells the others its name and when it was last here: it goes with each state, when the name changed
        // and once a day
        var self = remembered.self
        if (wrote || self == null || self.copy(state = 0, at = 0) != about.copy(state = 0, at = 0) || now - self.at >= DAY) {
            self = about.copy(state = version, at = now)
            service.write(devicePath(device), writeDevice(keys, device, version, self))
        }
        remember(SyncMemory(seen, tags, version, if (wrote) hash else remembered.written, now, state, devices, self, keys.epoch))
        return Round(others.size, refused, applied, wrote)
    }

    private suspend fun remember(next: SyncMemory) = memory.write(memoryFile, fileJson.encodeToString(SyncMemory.serializer(), next))

    // what this device merged last, from the state it wrote then: the files of the others are read only when they change, so a record a
    // store did not take, as a fingerprint where the store holds another one, comes from here, and so do the kinds the stores do not keep.
    // A kind of a newer version may hold secrets, so the state lies on the disk only as encrypted as on the service. After a new key of
    // the vault the states of the others bring it all again
    private suspend fun kept(state: String): List<Record> {
        if (state.isEmpty()) return emptyList()
        return try {
            readState(keys, device, Base64.decode(state)).content.records
        } catch (e: DeviceException) {
            emptyList()
        }
    }

    // the version of the state the service holds from this device: the memory of the device may have lost the writes after it
    private suspend fun written(listed: Map<String, String>): Long {
        if (statePath(device) !in listed) return 0
        val stored = service.read(statePath(device)) ?: return 0
        return try {
            readState(keys, device, stored.bytes).header.version
        } catch (e: KeyChangedException) {
            throw e
        } catch (e: DeviceException) {
            0
        }
    }

    // the vault is read in every round whatever its tag: a copy given back from before carries the tag it had then. The same key goes on
    // after a new password; a newer key stops the device, and an earlier key or an earlier copy is a vault given back from before
    private suspend fun checkVault(seen: Seen): Seen {
        val stored = service.read(VAULT) ?: throw gone()
        val header = vaultHeader(stored.bytes)
        if (header.vault != keys.vault) throw DeviceException(ErrorCategory.General, "$VAULT belongs to another vault")
        if (header.epoch > keys.epoch) throw KeyChangedException(header.epoch)
        if (header.epoch < keys.epoch) throw vaultGivenBack()
        seen.check(VAULT, header.version)
        return seen.with(VAULT, header.version)
    }

    private fun gone() = DeviceException(ErrorCategory.MissingItem, "the vault is no longer on the service")
}

private suspend fun remembered(memory: TextStore, file: String = SYNC_MEMORY): SyncMemory = memory.read(file)?.let {
    try {
        fileJson.decodeFromString(SyncMemory.serializer(), it)
    } catch (e: IllegalArgumentException) {
        null
    }
} ?: SyncMemory()
