package com.vasmarfas.networkhelm.app

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class Snippet(val name: String, val text: String) {
    val title: String get() = name.ifEmpty { text.lineSequence().first() }
}

class Snippets(private val storage: Storage) {
    private val list = MutableStateFlow<List<Snippet>>(emptyList())
    val all: StateFlow<List<Snippet>> = list.asStateFlow()

    var failure: Exception? = null
        private set

    suspend fun load() {
        val text = storage.read(FILE) ?: return
        try {
            list.value = json.decodeFromString(serializer, text)
            failure = null
        } catch (e: IllegalArgumentException) {
            failure = e
        }
    }

    // the editor changes the list it opened with; what another copy of the app added or removed since stays so
    suspend fun save(opened: List<Snippet>, edited: List<Snippet>) {
        load()
        failure?.let { throw IllegalStateException("the snippets could not be read, not overwriting them", it) }
        val stored = list.value
        val merged = edited.filter { it in stored || it !in opened } + stored.filter { it !in opened && it !in edited }
        storage.write(FILE, json.encodeToString(serializer, merged))
        list.value = merged
    }

    companion object {
        const val FILE = "snippets.json"
        private val serializer = ListSerializer(Snippet.serializer())
        private val json = Json { prettyPrint = true }
    }
}

val LocalSnippets = staticCompositionLocalOf<Snippets?> { null }
