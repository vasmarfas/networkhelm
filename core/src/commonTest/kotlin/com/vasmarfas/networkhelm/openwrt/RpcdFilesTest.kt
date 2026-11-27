package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RpcdFilesTest {

    private class Rpcd : Ubus {
        val files = mutableMapOf<String, ByteArray>()
        val folders = mutableSetOf<String>()
        val links = mutableMapOf<String, String>()
        val listed = mutableListOf<String>()
        val writes = mutableListOf<Boolean>()
        val ran = mutableListOf<List<String>>()

        override suspend fun call(obj: String, method: String, args: JsonObject): JsonObject {
            assertEquals("file", obj)
            val path = args["path"]?.jsonPrimitive?.content.orEmpty()
            if (method == "list") listed += path
            return when (method) {
                "write" -> {
                    val append = args.getValue("append").jsonPrimitive.booleanOrNull == true
                    writes += append
                    val data = Base64.decode(args.getValue("data").jsonPrimitive.content)
                    files[path] = if (append) files.getValue(path) + data else data
                    JsonObject(emptyMap())
                }
                "read" -> JsonObject(mapOf("data" to JsonPrimitive(Base64.encode(files[path] ?: throw UbusException(UbusStatus.NOT_FOUND)))))
                "stat" -> when {
                    path in folders -> JsonObject(mapOf("type" to JsonPrimitive("directory")))
                    path in files -> JsonObject(mapOf("type" to JsonPrimitive("file")))
                    else -> throw UbusException(UbusStatus.NOT_FOUND)
                }
                "remove" -> {
                    files.remove(path)
                    JsonObject(emptyMap())
                }
                "list" -> if (path != "/" && (links[path] ?: path) !in folders) throw UbusException(UbusStatus.NOT_FOUND) else JsonObject(mapOf("entries" to JsonArray(files.keys.filter { it.startsWith("$path/") }.map {
                    JsonObject(mapOf("name" to JsonPrimitive(it.substringAfterLast('/')), "type" to JsonPrimitive("file"), "size" to JsonPrimitive(files.getValue(it).size), "mtime" to JsonPrimitive(1_800_000_000)))
                } + links.keys.filter { it.substringBeforeLast('/').ifEmpty { "/" } == path }.map {
                    JsonObject(mapOf("name" to JsonPrimitive(it.substringAfterLast('/')), "type" to JsonPrimitive("symlink"), "size" to JsonPrimitive(3), "mtime" to JsonPrimitive(1_800_000_000)))
                })))
                "exec" -> {
                    val command = args.getValue("command").jsonPrimitive.content
                    val params = args.getValue("params").jsonArray.map { it.jsonPrimitive.content }
                    ran += listOf(command) + params
                    when (command) {
                        "/bin/mkdir" -> folders += params.single()
                        "/bin/rmdir" -> folders -= params.single()
                        "/bin/mv" -> files.remove(params[0])?.let { files[params[1]] = it }
                    }
                    JsonObject(mapOf("code" to JsonPrimitive(0)))
                }
                else -> error("no $method")
            }
        }

        override suspend fun objects(): Set<String> = setOf("file")
        override fun listen(vararg events: String): Flow<JsonObject> = emptyFlow()
        override fun subscribe(obj: String): Flow<JsonObject> = emptyFlow()
    }

    @Test
    fun aFileIsWrittenInPartsAndReadWhole() = runTest {
        val rpcd = Rpcd()
        val channel = RpcdFiles(rpcd)
        val bytes = ByteArray(60_000) { (it % 251).toByte() }
        channel.write("/tmp/a.bin", bytes)
        assertEquals(listOf(false, true, true), rpcd.writes)
        assertTrue(channel.read("/tmp/a.bin").contentEquals(bytes))
        channel.write("/tmp/empty", ByteArray(0))
        assertEquals(0, channel.read("/tmp/empty").size)
    }

    @Test
    fun aFolderIsMadeMovedIntoAndRemovedByTheCommands() = runTest {
        val rpcd = Rpcd()
        val channel = RpcdFiles(rpcd)
        channel.mkdir("/tmp/dir")
        channel.write("/tmp/a.txt", "a".encodeToByteArray())
        channel.rename("/tmp/a.txt", "/tmp/dir/b.txt")
        assertEquals(listOf("b.txt"), channel.list("/tmp/dir").map { it.name })
        channel.remove("/tmp/dir/b.txt")
        channel.remove("/tmp/dir")
        assertEquals(listOf(listOf("/bin/mkdir", "/tmp/dir"), listOf("/bin/mv", "/tmp/a.txt", "/tmp/dir/b.txt"), listOf("/bin/rmdir", "/tmp/dir")), rpcd.ran)
    }

    @Test
    fun aNameInUseIsNotOverwrittenAndAMissingFileIsToldAsMissing() = runTest {
        val rpcd = Rpcd()
        val channel = RpcdFiles(rpcd)
        channel.write("/tmp/a.txt", "a".encodeToByteArray())
        channel.write("/tmp/b.txt", "b".encodeToByteArray())
        assertFailsWith<DeviceException> { channel.rename("/tmp/a.txt", "/tmp/b.txt") }
        assertEquals("b", rpcd.files.getValue("/tmp/b.txt").decodeToString())
        assertEquals(ErrorCategory.MissingItem, assertFailsWith<DeviceException> { channel.read("/tmp/none") }.category)
    }

    // rpcd names a link as a link, as /var and /run in the root of OpenWrt 25.12, and lists the folder a link leads to
    @Test
    fun aLinkToAFolderIsListedAsTheFolderAndABrokenLinkAsAFile() = runTest {
        val rpcd = Rpcd()
        val channel = RpcdFiles(rpcd)
        rpcd.folders += "/tmp"
        rpcd.links["/var"] = "/tmp"
        rpcd.links["/localtime"] = "/tmp/localtime"
        assertEquals(listOf("var" to true, "localtime" to false), channel.list("/").map { it.name to it.directory })
        assertEquals(listOf("/", "/var", "/localtime"), rpcd.listed)
    }
}
