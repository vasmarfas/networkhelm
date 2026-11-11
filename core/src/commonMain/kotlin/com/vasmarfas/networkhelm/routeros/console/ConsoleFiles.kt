package com.vasmarfas.networkhelm.routeros.console

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.routeros.api.ApiConnectionClosedException
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.api.ApiTrapException
import com.vasmarfas.networkhelm.routeros.api.RouterOsLink
import com.vasmarfas.networkhelm.ssh.RemoteFile
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlin.io.encoding.Base64

private const val WRITE_LIMIT = 60_000

private const val LINE_ROOM = 600

private const val CLASSIC_WRITE_LIMIT = 4_000
private const val CLASSIC_PART = 400

private const val START = "\u0002"
private const val END = "\u0003"

// RouterOS before 7.17 prints and takes files as text, which loses a carriage return,
// and writes only an existing file or a new .txt, for it has no /file add
internal class ConsoleFiles(private val link: RouterOsLink, private val dialect: Dialect, private val encoding: ApiEncoding) : SftpClient {
    override suspend fun list(path: String): List<RemoteFile> {
        val folder = path.trim('/')
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        return calls { link.call(listOf("/file/print")).rows }.mapNotNull { row ->
            val inside = row["name"]?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotEmpty() && '/' !in it } ?: return@mapNotNull null
            RemoteFile(inside, row["size"]?.toLongOrNull() ?: 0, row["type"] == "directory", 0)
        }
    }

    override suspend fun read(path: String): ByteArray {
        val name = named(path.trim('/'))
        val file = ":local f [/file find name=$name]; :put [/file get \$f size]"
        return when (dialect) {
            Dialect.Serialize -> {
                val (size, data) = printed("$file; :put [:convert to=base64 [/file get \$f contents]]").lines().let { it[0] to it.getOrElse(1) { "" } }
                Base64.decode(data).also {
                    if (it.size.toString() != size) throw DeviceException(ErrorCategory.General, "the router gave ${it.size} of the $size bytes of the file; its console carries about 64 KB of a file at most")
                }
            }
            Dialect.Classic -> {
                val answer = printed("$file; :put [:len [/file get \$f contents]]; :put \"\\02\"; :put [/file get \$f contents]; :put \"\\03\"")
                val (size, length) = answer.lines()
                if (size != length) throw DeviceException(ErrorCategory.General, "the router gives $length of the $size bytes of the file")
                encoding.encode(answer.substringAfter("$START\n").substringBeforeLast("\n$END"))
            }
        }
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        val limit = if (dialect == Dialect.Serialize) WRITE_LIMIT else CLASSIC_WRITE_LIMIT
        if (bytes.size > limit) throw DeviceException(ErrorCategory.ArgumentValue, "the router takes no more than $limit bytes of a file through its console")
        when (dialect) {
            Dialect.Serialize -> writeSerialized(path.trim('/'), bytes)
            Dialect.Classic -> writeClassic(path.trim('/'), bytes)
        }
    }

    override suspend fun remove(path: String) {
        calls { link.call(listOf("/file/remove", "=.id=${path.trim('/')}")) }
    }

    override fun close() = Unit

    private suspend fun writeSerialized(file: String, bytes: ByteArray) {
        val name = named(file)
        val found = "[/file find name=$name]"
        val room = (LINE_LIMIT - LINE_ROOM - 3 * name.length) / 4 * 3
        var from = 0
        do {
            val to = minOf(bytes.size, from + room)
            val data = "[:convert from=base64 to=raw \"${Base64.encode(bytes.copyOfRange(from, to))}\"]"
            printed(
                if (from == 0) ":local c $data; :if ([:len $found] = 0) do={ /file add name=$name contents=\$c } else={ /file set $found contents=\$c }"
                else ":local c $data; /file set $found contents=([/file get $found contents] . \$c)",
            )
            from = to
        } while (from < bytes.size)
    }

    private suspend fun writeClassic(file: String, bytes: ByteArray) {
        val name = named(file)
        val found = "[/file find name=$name]"
        if (printed(":put [:len $found]") == "0") {
            if (!file.endsWith(".txt")) throw DeviceException(ErrorCategory.Unsupported, "this router makes only .txt files through its console")
            printed("/file print file=${named(file.removeSuffix(".txt"))}")
        }
        var from = 0
        do {
            var to = from
            var width = 0
            while (to < bytes.size) {
                val next = if (plain(bytes[to])) 1 else 3
                if (width + next > CLASSIC_PART) break
                width += next
                to++
            }
            val part = quoted(bytes.copyOfRange(from, to))
            printed(if (from == 0) ":put [/file set $found contents=$part]" else ":put [/file set $found contents=([/file get $found contents] . $part)]")
            from = to
        } while (from < bytes.size)
        val kept = printed(":put [/file get $found size]")
        if (kept != bytes.size.toString()) throw DeviceException(ErrorCategory.General, "the router keeps $kept of the ${bytes.size} bytes that were written")
    }

    private suspend fun printed(script: String): String = calls { link.call(listOf("/execute", "=script=$script")).done["ret"].orEmpty() }

    private fun named(file: String) = try {
        quoted(file, encoding)
    } catch (e: IllegalArgumentException) {
        throw DeviceException(ErrorCategory.ArgumentValue, e.message.orEmpty(), e)
    }

    private suspend fun <T> calls(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiTrapException) {
        throw DeviceException(ErrorCategory.Api, e.message.orEmpty(), e)
    } catch (e: ApiConnectionClosedException) {
        throw DeviceException(ErrorCategory.ConnectionLost, e.message.orEmpty(), e)
    }
}

internal class ConsoleFilesSsh(private val ssh: SshSession, private val files: SftpClient) : SshSession by ssh {
    override suspend fun sftp(): SftpClient = try {
        ssh.sftp()
    } catch (e: DeviceException) {
        if (e.category == ErrorCategory.Unsupported) files else throw e
    }
}
