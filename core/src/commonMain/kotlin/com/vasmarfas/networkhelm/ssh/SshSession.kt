package com.vasmarfas.networkhelm.ssh

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.serialization.Serializable

sealed interface SshAuth {
    class Password(val password: String) : SshAuth

    class Key(val privateKey: ByteArray, val passphrase: String? = null) : SshAuth

    data object Agent : SshAuth

    companion object {
        fun of(password: String, key: ByteArray?, agent: Boolean, passphrase: String?): SshAuth = when {
            agent -> Agent
            key != null -> Key(key, passphrase)
            else -> Password(password)
        }
    }
}

// fingerprint is in the OpenSSH form, SHA256: and unpadded base64, as ssh-keygen -l and RouterOS print it
class HostKey(val type: String, val fingerprint: String)

class HostKeyRejectedException(val key: HostKey) : Exception("host key ${key.type} ${key.fingerprint} rejected")

@Serializable
class ExecResult(val output: String, val error: String, val exitStatus: Int)

@Serializable
class RemoteFile(val name: String, val size: Long, val directory: Boolean, val modifiedEpochSeconds: Long)

interface ShellChannel : ByteStream {
    fun resize(columns: Int, rows: Int)
}

// paths are absolute: the RouterOS SFTP server fails realpath(".") and so never resolves a relative one
interface SftpClient {
    suspend fun list(path: String): List<RemoteFile>

    suspend fun read(path: String): ByteArray

    suspend fun write(path: String, bytes: ByteArray)

    suspend fun remove(path: String)

    suspend fun rename(from: String, to: String): Unit = throw DeviceException(ErrorCategory.Unsupported, "this channel cannot rename files")

    suspend fun mkdir(path: String): Unit = throw DeviceException(ErrorCategory.Unsupported, "this channel cannot make folders")

    fun close()
}

@Serializable
sealed interface SshReach {
    @Serializable
    data object Open : SshReach

    @Serializable
    data object Off : SshReach

    @Serializable
    data class Closed(val port: Int) : SshReach
}

interface SshSession {
    suspend fun exec(command: String): ExecResult

    // a command that reads what it is given, as sudo -S reads the password
    suspend fun exec(command: String, input: String): ExecResult = throw DeviceException(ErrorCategory.Unsupported, "this channel gives a command no input")

    suspend fun process(command: String): ByteStream

    suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel

    suspend fun sftp(): SftpClient

    suspend fun forward(host: String, port: Int): ByteStream

    val canForward: Boolean get() = true

    val connected: Boolean get() = true

    suspend fun reach(fresh: Boolean = false): SshReach = SshReach.Open

    val fileStore: Boolean get() = false

    fun close()
}
