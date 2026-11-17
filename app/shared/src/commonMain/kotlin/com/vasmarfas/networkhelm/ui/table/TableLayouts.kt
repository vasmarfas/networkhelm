package com.vasmarfas.networkhelm.ui.table

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.app.Storage
import com.vasmarfas.networkhelm.session.keep
import com.vasmarfas.networkhelm.session.peek
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

    // the tables this copy of the app changed since it last wrote the file: another copy keeps the layouts of its own tables there too
    private val touched = HashSet<String>()

    operator fun get(path: String): TableLayout? = layouts[path]

    operator fun set(path: String, layout: TableLayout) {
        layouts[path] = layout
        touched += path
    }

    // a mode picked in the settings is meant for every table, the ones that kept a mode of their own too
    fun followComments() {
        for ((path, layout) in layouts.toMap()) {
            if (layout.comments != null) set(path, layout.copy(comments = null))
        }
    }

    suspend fun keep() {
        layouts.putAll(stored(storage.read(FILE)))
        snapshotFlow { layouts.toMap() }.drop(1).collectLatest { current ->
            delay(500)
            val mine = touched.toSet()
            if (storage.keep(FILE, json.encodeToString(serializer, stored(storage.peek(FILE)) + current.filterKeys { it in mine }))) touched -= mine
        }
    }

    // a file from an older build: tables open with their default columns and the file is rewritten
    private fun stored(text: String?): Map<String, TableLayout> = text?.let {
        try {
            json.decodeFromString(serializer, it)
        } catch (e: IllegalArgumentException) {
            null
        }
    }.orEmpty()

    private companion object {
        const val FILE = "tables.json"
        val serializer = MapSerializer(String.serializer(), TableLayout.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }
}

// a table whose sort and widths outlive it, kept under its name; the layouts are read from storage after the first frame,
// so a table shown at the start, as on the connect screen, takes its layout when it comes
@Composable
fun <K> rememberKeptTable(tables: TableLayouts, name: String): TableState<K> {
    val state = remember(name) { TableState<K>() }
    val saved = tables[name]
    var taken by remember(name) { mutableStateOf(false) }
    LaunchedEffect(name, saved != null) {
        if (saved == null || taken) return@LaunchedEffect
        taken = true
        state.sort = saved.sort.map { SortKey(it.column, it.ascending) }
        saved.widths.forEach { (column, width) -> state.widths[column] = width.dp }
    }
    LaunchedEffect(name) {
        snapshotFlow { TableLayout(widths = state.widths.mapValues { it.value.value }, sort = state.sort.map { SavedSort(it.column, it.ascending) }) }
            .drop(1).collect { tables[name] = it }
    }
    return state
}
