package com.vasmarfas.networkhelm.journal

import com.vasmarfas.networkhelm.keenetic.keeneticCall
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.sensitive
import com.vasmarfas.networkhelm.openwrt.uciCli
import com.vasmarfas.networkhelm.opnsense.opnsenseCall
import com.vasmarfas.networkhelm.pfsense.pfsenseCall
import com.vasmarfas.networkhelm.routeros.cli
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.vyos.vyosCli
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Instant

enum class EditKind { Add, Set, Remove, Move }

// secrets are not masked here: the journal is local and the UI hides them, by their names and by secret, the fields the form of the
// path holds secrets by their type
data class EditRecord(
    val time: Instant,
    val router: String,
    val path: MenuPath,
    val kind: EditKind,
    val ids: List<String>,
    val before: Map<String, String>,
    val after: Map<String, String?>,
    val moveBefore: String? = null,
    val secret: Set<String> = emptySet(),
)

fun command(platform: Platform, path: MenuPath, kind: EditKind, ids: List<String>, values: Map<String, String?>, moveBefore: String? = null): String = when (platform) {
    Platform.OpenWrt -> uciCli(path, kind, ids, values, moveBefore)
    Platform.Opnsense -> opnsenseCall(path, kind, ids, values, moveBefore)
    Platform.Pfsense -> pfsenseCall(path, kind, ids, values)
    Platform.Vyos -> vyosCli(path, kind, ids, values)
    Platform.Keenetic -> keeneticCall(path, kind, ids, values, moveBefore)
    Platform.RouterOs, Platform.Ssh, Platform.Snmp, Platform.Upnp -> cli(path, kind, ids, values, moveBefore)
}

fun EditRecord.command(platform: Platform) = command(platform, path, kind, ids, after, moveBefore)

class EditJournal {
    private val entries = MutableStateFlow<List<EditRecord>>(emptyList())
    val records: StateFlow<List<EditRecord>> = entries

    fun append(record: EditRecord) = entries.update { it + record }
}

class JournalingSession(
    private val delegate: DeviceSession,
    private val journal: EditJournal,
    private val router: String,
    private val clock: Clock = Clock.System,
) : DeviceSession by delegate {
    // the fields of each path that hold secrets by their type, as the schema a form was drawn from gave them
    private val secrets = MutableStateFlow<Map<MenuPath, Set<String>>>(emptyMap())

    override suspend fun describe(path: MenuPath): MenuSchema = delegate.describe(path).also { schema ->
        val names = schema.arguments.filter { it.sensitive() }.map { it.name }.toSet()
        if (names.isNotEmpty()) secrets.update { it + (path to names) }
    }

    override suspend fun add(path: MenuPath, values: Map<String, String>): String {
        val id = delegate.add(path, values)
        journal.append(EditRecord(clock.now(), router, path, EditKind.Add, listOf(id), emptyMap(), values, secret = secrets.value[path].orEmpty()))
        return id
    }

    override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) {
        val old = delegate.get(path, id, changes.keys.toList())?.values.orEmpty().filterKeys { it in changes }
        delegate.set(path, id, changes)
        journal.append(EditRecord(clock.now(), router, path, EditKind.Set, listOfNotNull(id), old, changes, secret = secrets.value[path].orEmpty()))
    }

    override suspend fun remove(path: MenuPath, ids: List<String>) {
        val old = ids.singleOrNull()?.let { delegate.get(path, it)?.values }.orEmpty()
        delegate.remove(path, ids)
        journal.append(EditRecord(clock.now(), router, path, EditKind.Remove, ids, old, emptyMap()))
    }

    override suspend fun move(path: MenuPath, ids: List<String>, before: String?) {
        delegate.move(path, ids, before)
        journal.append(EditRecord(clock.now(), router, path, EditKind.Move, ids, emptyMap(), emptyMap(), before))
    }
}
