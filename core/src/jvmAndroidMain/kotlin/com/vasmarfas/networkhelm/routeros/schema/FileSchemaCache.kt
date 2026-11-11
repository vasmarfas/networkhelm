package com.vasmarfas.networkhelm.routeros.schema

import com.vasmarfas.networkhelm.routeros.api.md5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

class FileSchemaCache(private val directory: File) : SchemaCache {

    override suspend fun load(build: String): Map<String, List<Map<String, String>>> = withContext(Dispatchers.IO) {
        val file = file(build)
        if (!file.isFile) return@withContext emptyMap()
        try {
            val stored = Json.parseToJsonElement(file.readText()).jsonObject
            if (stored["build"]?.jsonPrimitive?.content != build) return@withContext emptyMap()
            file.setLastModified(System.currentTimeMillis())
            stored.getValue("answers").jsonObject.mapValues { (_, rows) ->
                rows.jsonArray.map { row -> row.jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content } }
            }
        } catch (e: IllegalArgumentException) {
            emptyMap()
        } catch (e: NoSuchElementException) {
            emptyMap()
        } catch (e: IOException) {
            emptyMap()
        }
    }

    override suspend fun store(build: String, answers: Map<String, List<Map<String, String>>>) = withContext(Dispatchers.IO) {
        val json = JsonObject(
            mapOf(
                "build" to JsonPrimitive(build),
                "answers" to JsonObject(answers.mapValues { (_, rows) -> JsonArray(rows.map { row -> JsonObject(row.mapValues { JsonPrimitive(it.value) }) }) }),
            ),
        )
        directory.mkdirs()
        val file = file(build)
        // written aside and renamed, so a crash never leaves a cut file; Windows renames only onto a free name
        val temporary = File(directory, file.name + ".tmp")
        try {
            temporary.writeText(json.toString())
        } catch (e: IOException) {
            // answers that could not be kept are asked of the router again at the next connect
            return@withContext
        }
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
        directory.listFiles { candidate -> candidate.extension == "json" }.orEmpty().sortedByDescending { it.lastModified() }.drop(KEPT).forEach { it.delete() }
    }

    override suspend fun forget(build: String) {
        withContext(Dispatchers.IO) { file(build).delete() }
    }

    private fun file(build: String) =
        File(directory, md5(build.encodeToByteArray()).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } + ".json")

    private companion object {
        const val KEPT = 24
    }
}
