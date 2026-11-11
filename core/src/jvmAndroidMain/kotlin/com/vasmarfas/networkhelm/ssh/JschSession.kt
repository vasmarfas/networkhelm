package com.vasmarfas.networkhelm.ssh

import com.jcraft.jsch.AgentConnector
import com.jcraft.jsch.AgentIdentityRepository
import com.jcraft.jsch.AgentProxyException
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.PageantConnector
import com.jcraft.jsch.SSHAgentConnector
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UserInfo
import com.jcraft.jsch.WindowsSSHAgentConnector
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import com.jcraft.jsch.HostKey as JschHostKey

actual suspend fun connectSsh(
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    acceptHostKey: (HostKey) -> Boolean,
    timeoutMillis: Int,
): SshSession {
    val hostKeys = HostKeyCheck(acceptHostKey)
    val session = withContext(Dispatchers.IO) {
        val jsch = JSch()
        jsch.hostKeyRepository = hostKeys
        if (auth is SshAuth.Key) jsch.addIdentity("key", auth.privateKey, null, auth.passphrase?.encodeToByteArray())
        if (auth is SshAuth.Agent) jsch.identityRepository = AgentIdentityRepository(systemAgent())
        jsch.getSession(user, host, port)
    }
    if (auth is SshAuth.Password) session.setPassword(auth.password.encodeToByteArray())
    // the repository answers OK or CHANGED and never asks, so an unknown key is decided by acceptHostKey alone
    session.setConfig("StrictHostKeyChecking", "yes")
    session.setConfig("PreferredAuthentications", if (auth is SshAuth.Password) "password" else "publickey")
    // RouterOS 6.42 and older sign with SHA-1 alone (ssh-rsa), which JSch leaves out; it goes last
    for (name in listOf("server_host_key", "PubkeyAcceptedAlgorithms")) session.setConfig(name, session.getConfig(name) + ",ssh-rsa")
    // jsch sends nothing over an idle connection, so one that a firewall has dropped stays open until it is used; RouterOS refuses the probe, which counts as an answer
    session.setServerAliveInterval(SERVER_ALIVE_MILLIS)
    session.setServerAliveCountMax(SERVER_ALIVE_MISSES)
    // any failure, cancellation included, closes the session: a withContext result after cancellation is dropped
    try {
        withContext(Dispatchers.IO) { session.connect(timeoutMillis) }
    } catch (e: Throwable) {
        session.disconnect()
        if (e is JSchException) {
            hostKeys.rejected?.let { throw HostKeyRejectedException(it) }
            if (e.message.orEmpty().startsWith("Auth fail")) throw DeviceException(ErrorCategory.PermissionDenied, e.message.orEmpty(), e)
        }
        throw e
    }
    return JschSession(session)
}

private fun systemAgent(): AgentConnector {
    val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    val candidates: List<() -> AgentConnector> = if (windows) listOf({ WindowsSSHAgentConnector() }, { PageantConnector() }) else listOf({ SSHAgentConnector() })
    for (make in candidates) {
        val connector = try {
            make()
        } catch (e: AgentProxyException) {
            continue
        }
        if (connector.isAvailable) return connector
    }
    throw DeviceException(ErrorCategory.Unsupported, "no SSH agent answers on this computer")
}

private const val MAX_EXEC_OUTPUT_BYTES = 64 * 1024 * 1024
private const val SERVER_ALIVE_MILLIS = 15_000
private const val SERVER_ALIVE_MISSES = 3
private const val CHANNEL_OPEN_MILLIS = 10_000

private fun InputStream.readAtMost(limit: Int): ByteArray {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) return bytes.toByteArray()
        if (bytes.size() + count > limit) throw DeviceException(ErrorCategory.Api, "the output of a command exceeds $limit bytes")
        bytes.write(buffer, 0, count)
    }
}

private class JschSession(private val session: Session) : SshSession {
    // jsch sends close and window-size packets on the caller's thread, and Android's main thread refuses the socket;
    // it swallows that after its cipher has moved on, the router drops the session, so they are sent from here
    private val later = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    override suspend fun exec(command: String): ExecResult = withContext(Dispatchers.IO) {
        val channel = open<ChannelExec>("exec")
        channel.setCommand(command)
        val out = channel.inputStream
        val err = channel.errStream
        try {
            channel.connect()
            coroutineScope {
                val output = async { out.readAtMost(MAX_EXEC_OUTPUT_BYTES) }
                val error = async { err.readAtMost(MAX_EXEC_OUTPUT_BYTES) }
                val result = output.await() to error.await()
                while (!channel.isClosed) delay(10)
                ExecResult(result.first.decodeToString(), result.second.decodeToString(), channel.exitStatus)
            }
        } catch (e: JSchException) {
            throw lost(e)
        } catch (e: IOException) {
            throw DeviceException(ErrorCategory.ConnectionLost, e.message.orEmpty(), e)
        } finally {
            channel.disconnect()
        }
    }

    override suspend fun process(command: String): ByteStream {
        val channel = open<ChannelExec>("exec")
        channel.setCommand(command)
        return connected(channel, ChannelStream(channel, channel.inputStream, channel.outputStream, later))
    }

    override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel {
        val channel = open<ChannelShell>("shell")
        channel.setPtyType(term, columns, rows, 0, 0)
        return connected(channel, ShellStream(channel, later))
    }

    override suspend fun sftp(): SftpClient {
        val channel = open<ChannelSftp>("sftp")
        return connected(channel, JschSftp(channel, later))
    }

    override suspend fun forward(host: String, port: Int): ByteStream {
        val channel = open<ChannelDirectTCPIP>("direct-tcpip")
        channel.setHost(host)
        channel.setPort(port)
        return connected(channel, ChannelStream(channel, channel.inputStream, channel.outputStream, later)) { channel.connect(10_000) }
    }

    private inline fun <reified T : Channel> open(type: String): T = try {
        session.openChannel(type) as T
    } catch (e: JSchException) {
        throw lost(e)
    }

    private fun lost(e: JSchException) = DeviceException(if (session.isConnected) ErrorCategory.General else ErrorCategory.ConnectionLost, e.message.orEmpty(), e)

    private suspend fun <T> connected(channel: Channel, result: T, connect: () -> Unit = { channel.connect(CHANNEL_OPEN_MILLIS) }): T {
        val started = TimeSource.Monotonic.markNow()
        try {
            withContext(Dispatchers.IO) { connect() }
        } catch (e: Throwable) {
            later.launch { channel.disconnect() }
            // a router that has not answered the request to open a channel in time has lost the connection, and the next try needs a new one
            if (e is JSchException && channel !is ChannelDirectTCPIP && started.elapsedNow() >= CHANNEL_OPEN_MILLIS.milliseconds) later.launch { session.disconnect() }
            throw if (e is JSchException) lost(e) else e
        }
        return result
    }

    override val connected get() = session.isConnected

    override fun close() = session.disconnect()
}

private open class ChannelStream(
    private val channel: Channel,
    private val input: InputStream,
    private val output: OutputStream,
    protected val later: CoroutineScope,
) : ByteStream {
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = runInterruptible(Dispatchers.IO) {
        try {
            input.read(buffer, offset, length)
        } catch (e: InterruptedIOException) {
            throw InterruptedException()
        } catch (e: IOException) {
            throw DeviceException(ErrorCategory.ConnectionLost, e.message.orEmpty(), e)
        }
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        try {
            output.write(bytes)
            output.flush()
        } catch (e: IOException) {
            throw DeviceException(ErrorCategory.ConnectionLost, e.message.orEmpty(), e)
        }
    }

    override fun close() {
        later.launch { channel.disconnect() }
    }
}

private class ShellStream(private val channel: ChannelShell, later: CoroutineScope) : ChannelStream(channel, channel.inputStream, channel.outputStream, later), ShellChannel {
    override fun resize(columns: Int, rows: Int) {
        later.launch { channel.setPtySize(columns, rows, 0, 0) }
    }
}

private class JschSftp(private val channel: ChannelSftp, private val later: CoroutineScope) : SftpClient {
    override suspend fun list(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        channel.ls(path)
            .filter { it.filename != "." && it.filename != ".." }
            .map { RemoteFile(it.filename, it.attrs.size, it.attrs.isDir, it.attrs.mTime.toLong()) }
    }

    override suspend fun read(path: String): ByteArray = moving { channel.get(path).use { it.readBytes() } }

    override suspend fun write(path: String, bytes: ByteArray) = moving { channel.put(bytes.inputStream(), path) }

    // jsch moves a file in one blocking call that no cancellation reaches, so the channel is closed under it;
    // the call is left to fail by itself: awaited to its end, it would give the caller its failure in place of the cancellation
    private suspend fun <T> moving(block: () -> T): T {
        val moved = later.async(Dispatchers.IO) { block() }
        try {
            return moved.await()
        } catch (e: CancellationException) {
            close()
            throw e
        }
    }

    override suspend fun remove(path: String) = withContext(Dispatchers.IO) {
        val directory = try {
            channel.stat(path).isDir
        } catch (e: SftpException) {
            false
        }
        if (directory) channel.rmdir(path) else channel.rm(path)
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) { channel.rename(from, to) }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) { channel.mkdir(path) }

    override fun close() {
        later.launch { channel.disconnect() }
    }
}

private class HostKeyCheck(private val accept: (HostKey) -> Boolean) : HostKeyRepository {
    var rejected: HostKey? = null

    override fun check(host: String, key: ByteArray): Int {
        val type = String(key, 4, ByteBuffer.wrap(key, 0, 4).int, Charsets.US_ASCII)
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        val hostKey = HostKey(type, fingerprint)
        if (accept(hostKey)) return HostKeyRepository.OK
        rejected = hostKey
        return HostKeyRepository.CHANGED
    }

    override fun add(hostkey: JschHostKey, ui: UserInfo?) = Unit

    override fun remove(host: String, type: String?) = Unit

    override fun remove(host: String, type: String?, key: ByteArray?) = Unit

    override fun getKnownHostsRepositoryID(): String = "networkhelm"

    override fun getHostKey(): Array<JschHostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<JschHostKey> = emptyArray()
}
