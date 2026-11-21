package com.vasmarfas.networkhelm.ui.sync

import com.vasmarfas.networkhelm.app.Storage
import com.vasmarfas.networkhelm.sync.FileRecords
import com.vasmarfas.networkhelm.sync.HlcClock
import com.vasmarfas.networkhelm.sync.Record
import com.vasmarfas.networkhelm.sync.recordId
import com.vasmarfas.networkhelm.transport.TrustKind
import com.vasmarfas.networkhelm.transport.TrustStore
import com.vasmarfas.networkhelm.transport.Trusted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// a fingerprint another device holds for an address, port and kind that this device trusts with another one
internal data class Dispute(val here: Trusted, val there: String)

// the fingerprints of the trust store as records, one for each address, port and kind. A merged fingerprint goes into the store where it
// holds none for them; where it holds another one, neither is chosen silently: the device keeps its own without pushing it over the
// newer one, and the user picks
internal class TrustRecords(private val store: TrustStore, storage: Storage, private val clock: HlcClock = HlcClock()) {
    private val file = FileRecords(storage, "known-routers", "fingerprint", clock)
    private val lock = Mutex()
    private val disputed = MutableStateFlow(emptyList<Dispute>())
    val disputes: StateFlow<List<Dispute>> = disputed.asStateFlow()

    suspend fun records(): List<Record> = lock.withLock {
        val held = match()
        // a dispute is over once the store holds another fingerprint there: the user took the key the router showed
        disputed.update { disputes -> disputes.filter { it.here in held } }
        file.take()
    }

    suspend fun apply(merged: List<Record>) = lock.withLock {
        val held = match().associateBy(::id)
        // a fingerprint taken since the round read the store waits with the merged ones for the next round
        if (file.edited) return@withLock
        merged.maxOfOrNull { it.hlc }?.let(clock::observe)
        val own = file.all.associateBy { it.id }
        val disputes = ArrayList<Dispute>()
        val kept = merged.map { record ->
            val here = held[record.id]
            val there = if (record.deleted) null else trusted(record)
            when {
                here == null -> record.also { if (there != null) store.put(there.host, there.port, there.kind, there.fingerprint) }
                here.fingerprint == there?.fingerprint -> record
                else -> {
                    if (there != null) disputes += Dispute(here, there.fingerprint)
                    own[record.id] ?: record
                }
            }
        }
        disputed.value = disputes
        if (kept != file.all) file.keep(kept)
    }

    // the fingerprint of the other device goes into the store, or the one of this device takes a new stamp and goes over it
    suspend fun settle(dispute: Dispute, take: Boolean) = lock.withLock {
        val here = dispute.here
        if (take) {
            store.put(here.host, here.port, here.kind, dispute.there)
        } else {
            file.keep(file.stored().map { if (it.id == id(here) && !it.deleted) it.copy(hlc = clock.now()) else it })
        }
        disputed.update { it - dispute }
    }

    // the fingerprints the store holds, matched with their records; a record this version cannot put into the store keeps what it holds
    private suspend fun match(): List<Trusted> {
        var held = emptyList<Trusted>()
        file.match {
            val unplaced = file.stored().filter { !it.deleted && trusted(it) == null }.associate { it.id to it.data }
            store.all()?.let { all ->
                held = all
                unplaced + all.associate { id(it) to entry(it) }
            }
        }
        return held
    }

    private fun id(trusted: Trusted) = recordId(trusted.kind.name, trusted.host, trusted.port.toString())

    private fun entry(trusted: Trusted) = JsonObject(
        mapOf("kind" to JsonPrimitive(trusted.kind.name), "host" to JsonPrimitive(trusted.host), "port" to JsonPrimitive(trusted.port), "fingerprint" to JsonPrimitive(trusted.fingerprint)),
    )

    // a record of a kind of a newer version, one that does not belong where its id puts it, or one that would break a line of the store
    // stays out of it
    private fun trusted(record: Record): Trusted? {
        fun text(name: String) = (record.data[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
        val kind = TrustKind.entries.firstOrNull { it.name == text("kind") } ?: return null
        val port = (record.data["port"] as? JsonPrimitive)?.intOrNull ?: return null
        val trusted = Trusted(text("host") ?: return null, port, kind, text("fingerprint") ?: return null)
        return trusted.takeIf { id(it) == record.id }
    }
}
