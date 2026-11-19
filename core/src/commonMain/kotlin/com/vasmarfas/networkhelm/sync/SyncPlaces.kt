package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import kotlinx.coroutines.CancellationException

// a place of the vault: its service and the rounds of this device there, by the id the setup gives the place
class Place(val id: String, val service: SyncService, val synchronizer: Synchronizer)

// the vault kept in several places at once, each a whole copy with the devices that use it: a round goes through the places one by one,
// and the stores carry what came from one place into the next. A place whose vault lags behind takes the newest one first, as after a
// new password or key on a device that does not use that place. A place that fails waits for the next round while the others go on, and
// a newer key in any place stops the device everywhere before it writes
class SyncPlaces(private val keys: Keys, private val places: List<Place>, private val missing: Map<String, Exception> = emptyMap()) {
    suspend fun round(): Round {
        val seen = places.maxOf { it.synchronizer.seenEpoch() }
        if (seen > keys.epoch) throw KeyChangedException(seen)
        val failed = LinkedHashMap(missing)
        val vaults = HashMap<Place, Pair<Stored, Header>?>()
        if (places.size > 1) {
            for (place in places) attempt(place, failed) { vaults[place] = place.service.read(VAULT)?.let { it to vaultHeader(it.bytes) } }
        }
        val newest = vaults.values.filterNotNull().filter { (_, header) -> header.vault == keys.vault && header.epoch == keys.epoch }.maxByOrNull { (_, header) -> header.version }
        val done = ArrayList<Round>()
        // the place with the vault of the newest key goes first: a key newer than this device holds stops it there
        for (place in places.filter { it.id !in failed }.sortedByDescending { vaults[it]?.second?.epoch ?: 0 }) {
            attempt(place, failed) {
                val held = vaults[place]
                if (newest != null && held != null && held.second.vault == keys.vault && lags(held.second, newest.second)) {
                    place.service.writeIf(VAULT, newest.first.bytes, held.first)
                }
                done += place.synchronizer.round()
            }
        }
        if (done.isEmpty()) throw failed.getValue(places.first().id)
        return Round(done.sumOf { it.read }, done.fold(emptyMap<String, DeviceException>()) { all, round -> all + round.refused }, done.any { it.applied }, done.any { it.wrote }, failed)
    }

    // the devices of every place as each named itself last where it was seen last
    suspend fun devices(): Map<String, DeviceContent> =
        places.flatMap { it.synchronizer.devices().entries }.groupBy({ it.key }, { it.value }).mapValues { (_, seen) -> seen.maxBy { it.at } }

    private fun lags(header: Header, newest: Header) = header.epoch < keys.epoch || header.epoch == keys.epoch && header.version < newest.version

    private inline fun attempt(place: Place, failed: MutableMap<String, Exception>, action: () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: KeyChangedException) {
            throw e
        } catch (e: Exception) {
            failed[place.id] = e
        }
    }
}
