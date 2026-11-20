package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.transport.urlEncoded
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.Clock

// the files of a container through the engine: a folder is listed by ls run inside the container, a file comes and goes as a tar
// archive; an image without ls lists nothing, but its files still come and go
internal class DockerFiles(private val api: DockerApi) {
    // with the slash ls lists the folder a link leads to rather than the link
    suspend fun list(id: String, path: String): List<Item> = listing(exec(id, listOf("ls", "-la", path.trimEnd('/') + "/")))

    // a folder, or a link, comes as the archive itself
    suspend fun download(id: String, path: String): Item {
        val archive = api.send("GET", "/containers/${urlEncoded(id)}/archive?path=${urlEncoded(path)}").body
        val file = untar(archive)
        val content = if (file != null) mapOf("name" to file.first, "content" to Base64.encode(file.second))
        else mapOf("name" to path.trimEnd('/').substringAfterLast('/').ifEmpty { "root" } + ".tar", "content" to Base64.encode(archive))
        return Item(path, content)
    }

    suspend fun upload(id: String, folder: String, name: String, content: String) {
        val archive = tar(name, Base64.decode(content), Clock.System.now().epochSeconds)
        api.send("PUT", "/containers/${urlEncoded(id)}/archive?path=${urlEncoded(folder)}", archive, mapOf("Content-Type" to "application/x-tar"))
    }

    // a command run in the container without a terminal: what it printed, or what it said on stderr when it failed
    suspend fun exec(id: String, command: List<String>): String {
        val exec = (api.created("/containers/${urlEncoded(id)}/exec", buildJsonObject {
            put("AttachStdout", true)
            put("AttachStderr", true)
            put("Cmd", JsonArray(command.map(::JsonPrimitive)))
        })["Id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val stream = api.hijack("/exec/$exec/start", buildJsonObject {
            put("Detach", false)
            put("Tty", false)
        })
        val lines = try {
            logLines(stream).toList()
        } finally {
            stream.close()
        }
        val code = ((api.get("/exec/$exec/json") as? JsonObject)?.get("ExitCode") as? JsonPrimitive)?.longOrNull
        if (code != 0L) throw DeviceException(ErrorCategory.Api, lines.filter { it.first == "stderr" }.joinToString("\n") { it.second }.ifEmpty { "exit $code" })
        return lines.filter { it.first == "stdout" }.joinToString("\n") { it.second }
    }
}

// the lines of ls -la, of GNU or of busybox: the mode, links, owner, group, the size (major, minor of a device), three words of the
// date and the name, of a link with its target after ->
internal fun listing(text: String): List<Item> = text.lines().mapNotNull { line ->
    val (mode, owner, group, size, date, rest) = LS.matchEntire(line)?.destructured ?: return@mapNotNull null
    val link = mode.startsWith('l')
    val name = if (link) rest.substringBefore(" -> ") else rest
    if (name == "." || name == "..") return@mapNotNull null
    Item(name, linkedMapOf(
        "name" to name,
        "type" to when (mode[0]) {
            'd' -> "directory"
            'l' -> "link"
            '-' -> "file"
            else -> "other"
        },
        "size" to if (mode[0] == '-') size else "",
        "mode" to mode,
        "owner" to owner,
        "group" to group,
        "modified" to date.split(BLANKS).joinToString(" "),
        "target" to if (link) rest.substringAfter(" -> ", "") else "",
    ))
}

// the first entry of an archive, as the engine sends a path: its name and bytes, or null when it is a folder or a link and no file;
// pax records before it are skipped, and a path among them stands for the name
internal fun untar(archive: ByteArray): Pair<String, ByteArray>? {
    var at = 0
    var long: String? = null
    while (at + BLOCK <= archive.size) {
        val name = field(archive, at, 0, 100)
        if (name.isEmpty()) return null
        val size = field(archive, at, 124, 12).trim().toLongOrNull(8) ?: return null
        val start = at + BLOCK
        if (start + size > archive.size) return null
        when (archive[at + 156].toInt().toChar()) {
            'x', 'g' -> long = archive.decodeToString(start, start + size.toInt()).lines().firstNotNullOfOrNull { record -> record.substringAfter(' ', "").takeIf { it.startsWith("path=") }?.removePrefix("path=") } ?: long
            '0', '\u0000' -> {
                val prefix = field(archive, at, 345, 155)
                return (long ?: if (prefix.isEmpty()) name else "$prefix/$name").substringAfterLast('/') to archive.copyOfRange(start, start + size.toInt())
            }
            else -> return null
        }
        at = start + ((size + BLOCK - 1) / BLOCK * BLOCK).toInt()
    }
    return null
}

// an archive of one file, as the engine takes it to put into a folder of the container: rw-r--r--, owned by root
internal fun tar(name: String, content: ByteArray, modified: Long): ByteArray {
    val bytes = name.encodeToByteArray()
    if (bytes.size > 100) throw DeviceException(ErrorCategory.ArgumentValue, "the name of a file in the archive is longer than 100 bytes")
    val header = ByteArray(BLOCK)
    bytes.copyInto(header)
    octal(header, 100, 8, 420)
    octal(header, 108, 8, 0)
    octal(header, 116, 8, 0)
    octal(header, 124, 12, content.size.toLong())
    octal(header, 136, 12, modified)
    header[156] = '0'.code.toByte()
    "ustar\u000000".encodeToByteArray().copyInto(header, 257)
    octal(header, 329, 8, 0)
    octal(header, 337, 8, 0)
    // the sum counts the field of the sum as spaces
    "        ".encodeToByteArray().copyInto(header, 148)
    "${header.sumOf { it.toInt() and 0xFF }.toString(8).padStart(6, '0')}\u0000 ".encodeToByteArray().copyInto(header, 148)
    val padding = (BLOCK - content.size % BLOCK) % BLOCK
    return header + content + ByteArray(padding + 2 * BLOCK)
}

private fun octal(header: ByteArray, at: Int, length: Int, value: Long) = "${value.toString(8).padStart(length - 1, '0')}\u0000".encodeToByteArray().copyInto(header, at)

private fun field(archive: ByteArray, at: Int, offset: Int, length: Int): String {
    val start = at + offset
    val end = (start until start + length).firstOrNull { archive[it] == 0.toByte() } ?: (start + length)
    return archive.decodeToString(start, end)
}

private const val BLOCK = 512

private val LS = Regex("""([bcdlps-][rwxsStT-]{9})\S*\s+\d+\s+(\S+)\s+(\S+)\s+(\d+|\d+,\s*\d+)\s+(\S+\s+\S+\s+\S+)\s(.*)""")

private val BLANKS = Regex("\\s+")
