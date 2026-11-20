package com.vasmarfas.networkhelm.journal

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class JournalingSessionTest {

    private class MemorySession : DeviceSession {
        val items = linkedMapOf("*1" to mapOf("address" to "10.0.0.1/24", "comment" to "old"))
        override val device = DeviceInfo(Platform.RouterOs, "lab", "7.26", "x86_64", "CHR", emptySet(), setOf("read", "write"))
        override val capabilities = Capabilities(true, true, true, SafeModeKind.ApiSession, terminal = false, files = false, export = false)
        override suspend fun describe(path: MenuPath) = MenuSchema(
            path, NodeKind.List, emptyList(), listOf("add", "set"),
            listOf(Argument("comment", ValueType.Text()), Argument("secret-env", ValueType.Many(ValueType.Secret, "\n")), Argument("url", ValueType.Secret)),
        )
        override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> = emptyFlow()
        override suspend fun get(path: MenuPath, id: String?, properties: List<String>?) = items[id]?.let { Item(id.orEmpty(), it) }
        override suspend fun count(path: MenuPath, filter: Filter?) = items.size
        override suspend fun add(path: MenuPath, values: Map<String, String>) = "*${items.size + 1}".also { items[it] = values }
        override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) {
            val current = items.getValue(id.orEmpty()).toMutableMap()
            changes.forEach { (k, v) -> if (v == null) current.remove(k) else current[k] = v }
            items[id.orEmpty()] = current
        }
        override suspend fun remove(path: MenuPath, ids: List<String>) = ids.forEach { items.remove(it) }
        override suspend fun move(path: MenuPath, ids: List<String>, before: String?) = Unit
        override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> = emptyFlow()
        override suspend fun export(path: MenuPath): String = error("not used")
        override val safeMode: SafeMode? = null
        override val ssh: SshSession? = null
        override fun close() = Unit
    }

    private val clock = object : Clock {
        override fun now() = Instant.fromEpochSeconds(1_800_000_000)
    }

    private val path = MenuPath.parse("ip/address")

    @Test
    fun recordsOldValuesOfTouchedPropertiesOnly() = runTest {
        val journal = EditJournal()
        val session = JournalingSession(MemorySession(), journal, "lab", clock)
        session.set(path, "*1", mapOf("comment" to null))
        val record = journal.records.value.single()
        assertEquals(EditKind.Set, record.kind)
        assertEquals(mapOf("comment" to "old"), record.before)
        assertEquals(mapOf("comment" to null), record.after)
    }

    @Test
    fun keepsARemovedElementWholeAndTheIdOfAnAddedOne() = runTest {
        val journal = EditJournal()
        val session = JournalingSession(MemorySession(), journal, "lab", clock)
        val id = session.add(path, mapOf("address" to "10.0.0.2/24"))
        session.remove(path, listOf("*1"))
        val (added, removed) = journal.records.value
        assertEquals(listOf(id), added.ids)
        assertEquals(mapOf("address" to "10.0.0.1/24", "comment" to "old"), removed.before)
    }

    @Test
    fun aRecordNamesTheFieldsTheFormTookAsSecretsByTheirType() = runTest {
        val journal = EditJournal()
        val session = JournalingSession(MemorySession(), journal, "lab", clock)
        session.add(path, mapOf("secret-env" to "DB_PASSWORD=rd-test-word"))
        session.describe(path)
        session.add(path, mapOf("secret-env" to "DB_PASSWORD=rd-test-word", "url" to "https://rd-test.example/sub", "comment" to "web"))
        session.set(path, "*1", mapOf("secret-env" to "API_TOKEN=rd-test-token"))
        val (blind, added, changed) = journal.records.value
        assertEquals(emptySet(), blind.secret, "a change made without a form knows no types")
        assertEquals(setOf("secret-env", "url"), added.secret)
        assertEquals(setOf("secret-env", "url"), changed.secret)
    }
}
