package com.vasmarfas.networkhelm.app

import androidx.compose.runtime.staticCompositionLocalOf
import com.vasmarfas.networkhelm.sync.FileRecords
import com.vasmarfas.networkhelm.sync.HlcClock
import com.vasmarfas.networkhelm.sync.Record
import com.vasmarfas.networkhelm.sync.recordId
import com.vasmarfas.networkhelm.sync.recordJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Serializable
data class Snippet(val name: String, val text: String) {
    val title: String get() = name.ifEmpty { text.lineSequence().first() }
}

class Snippets(private val storage: Storage, clock: HlcClock = HlcClock()) {
    private val list = MutableStateFlow<List<Snippet>>(emptyList())
    val all: StateFlow<List<Snippet>> = list.asStateFlow()
    val records = FileRecords(storage, FILE, "snippet", clock)
    private val lock = Mutex()

    var failure: Exception? = null
        private set

    suspend fun load() = lock.withLock { read() }

    // the editor changes the list it opened with; what another copy of the app added or removed since stays so
    suspend fun save(opened: List<Snippet>, edited: List<Snippet>) = lock.withLock {
        read()
        failure?.let { throw IllegalStateException("the snippets could not be read, not overwriting them", it) }
        val stored = list.value
        write(edited.filter { it in stored || it !in opened } + stored.filter { it !in opened && it !in edited })
        read()
    }

    // the snippets merged with those of the other devices: those here keep their places, those from elsewhere come after them
    suspend fun apply(merged: List<Record>) = lock.withLock {
        read()
        failure?.let { throw IllegalStateException("the snippets could not be read, not overwriting them", it) }
        records.put(merged, Snippet.serializer()) { snippets ->
            val stored = list.value
            write(stored.filter { it in snippets } + snippets.filter { it !in stored })
        }
        read()
    }

    private suspend fun read() = records.match {
        storage.read(FILE)?.let { text ->
            try {
                list.value = json.decodeFromString(serializer, text)
                failure = null
            } catch (e: IllegalArgumentException) {
                failure = e
                return@match null
            }
        }
        entries(list.value)
    }

    private suspend fun write(snippets: List<Snippet>) {
        storage.write(FILE, json.encodeToString(serializer, snippets))
        list.value = snippets
    }

    companion object {
        const val FILE = "snippets.json"
        private val serializer = ListSerializer(Snippet.serializer())
        private val json = Json { prettyPrint = true }

        // a snippet is its name and text: an edited one is another snippet, so two devices that edit one at once keep both
        private fun entries(snippets: List<Snippet>) = snippets.associate { recordId(it.name, it.text) to recordJson.encodeToJsonElement(Snippet.serializer(), it).jsonObject }
    }
}

val LocalSnippets = staticCompositionLocalOf<Snippets?> { null }
