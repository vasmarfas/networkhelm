package com.vasmarfas.networkhelm.uidefs

import com.vasmarfas.networkhelm.offline.gunzip
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.httpRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class UiFile(val name: String, val unique: String, val crc: Long, val size: Int)

fun parseUiList(text: String): List<UiFile> = (parseJg("[$text]") as JgList).items.mapNotNull { item ->
    val fields = (item as? JgObject)?.fields ?: return@mapNotNull null
    fun text(key: String) = (fields[key] as? JgString)?.text
    fun number(key: String) = (fields[key] as? JgNumber)?.value?.toLong()
    UiFile(text("name") ?: return@mapNotNull null, text("unique") ?: return@mapNotNull null, number("crc") ?: 0, (number("size") ?: 0).toInt())
}

class WebAccess(val host: String, val open: suspend (port: Int) -> ByteStream)

// the files of the descriptions by their names: the list, and each description by its unique name
typealias UiFiles = suspend (String) -> ByteArray?

private const val MAIN_FILE = "roteros.jg"

private const val LIST_FILE = "list"

private fun unpacked(body: ByteArray): ByteArray? = try {
    gunzip(body)
} catch (e: Exception) {
    null
}

private suspend fun WebAccess.get(port: Int, target: String): ByteArray? {
    val response = try {
        httpRequest(open(port), host, "GET", target, mapOf("Accept-Encoding" to "gzip"), ByteArray(0))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return null
    }
    if (response.status != 200) return null
    if (response.headers["content-encoding"]?.contains("gzip") != true) return response.body
    return unpacked(response.body)
}

fun WebAccess.files(port: Int): UiFiles = { name -> get(port, "/webfig/$name") }

// the WinBox port serves the list as it is and each description gzipped under its unique name and .gz
fun nativeUiFiles(resource: suspend (String) -> ByteArray?): UiFiles = { name ->
    if (name == LIST_FILE) resource(name) else resource("$name.gz")?.let(::unpacked)
}

suspend fun loadUiList(files: UiFiles): List<UiFile>? {
    val list = files(LIST_FILE)?.decodeToString()?.let { text ->
        try {
            parseUiList(text)
        } catch (e: IllegalArgumentException) {
            null
        }
    } ?: return null
    return list.filter { it.name.endsWith(".jg") && it.size > 0 }.takeIf { found -> found.any { it.name == MAIN_FILE } }
}

fun uiState(files: List<UiFile>): String = files.joinToString(",") { "${it.name}:${it.crc}" }

suspend fun loadUiEntries(files: UiFiles, list: List<UiFile>): List<UiNode> {
    val limit = Semaphore(4)
    return coroutineScope {
        list.sortedBy { it.name != MAIN_FILE }.map { file ->
            async {
                limit.withPermit {
                    val body = files(file.unique) ?: return@withPermit emptyList()
                    try {
                        uiNodes(parseJg(body.decodeToString()))
                    } catch (e: IllegalArgumentException) {
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten()
    }
}
