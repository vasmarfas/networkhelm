package com.vasmarfas.networkhelm.ui.table

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import com.vasmarfas.networkhelm.app.Storage
import com.vasmarfas.networkhelm.session.keep
import com.vasmarfas.networkhelm.ui.log.LogLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

enum class CommentMode { Above, InColumn, Hidden }

@Serializable
data class TableLayout(
    val columns: List<String> = emptyList(),
    val widths: Map<String, Float> = emptyMap(),
    val sort: List<SavedSort> = emptyList(),
    val detail: Boolean = false,
    val categories: Boolean = false,
    // a table without a mode of its own shows comments as the settings say
    val comments: CommentMode? = null,
    val log: LogLayout? = null,
    val expanded: List<String> = emptyList(),
)

@Serializable
class SavedSort(val column: String, val ascending: Boolean)

class TableLayouts(private val storage: Storage) {
    private val layouts = mutableStateMapOf<String, TableLayout>()

    operator fun get(path: String): TableLayout? = layouts[path]

    operator fun set(path: String, layout: TableLayout) {
        layouts[path] = layout
    }

    // a mode picked in the settings is meant for every table, the ones that kept a mode of their own too
    fun followComments() {
        for ((path, layout) in layouts.toMap()) {
            if (layout.comments != null) layouts[path] = layout.copy(comments = null)
        }
    }

    suspend fun keep() {
        storage.read(FILE)?.let { text ->
            try {
                layouts.putAll(json.decodeFromString(serializer, text))
            } catch (e: IllegalArgumentException) {
                // a file from an older build: tables open with their default columns and the file is rewritten
            }
        }
        snapshotFlow { layouts.toMap() }.drop(1).collectLatest { current ->
            delay(500)
            storage.keep(FILE, json.encodeToString(serializer, current))
        }
    }

    private companion object {
        const val FILE = "tables.json"
        val serializer = MapSerializer(String.serializer(), TableLayout.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }
}
