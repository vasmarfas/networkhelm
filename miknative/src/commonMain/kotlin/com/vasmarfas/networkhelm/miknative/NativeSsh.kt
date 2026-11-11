package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.LINK_LOST
import com.vasmarfas.networkhelm.session.Backoff
import com.vasmarfas.networkhelm.session.NativeObjects
import com.vasmarfas.networkhelm.session.NativeSide
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class NativeSsh private constructor(
    private var connection: NativeConnection,
    private val password: String,
    private val open: suspend () -> NativeConnection,
    private val backoff: Backoff,
) : SshSession {
    private val lock = Mutex()

    @Volatile
    private var closed = false

    override suspend fun exec(command: String): ExecResult = throw unsupported("commands with an exit status")

    override suspend fun process(command: String): ByteStream = throw unsupported("processes")

    override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel =
        NativeTerminal.open(live(), password, term, columns, rows)

    override suspend fun sftp(): SftpClient = NativeFiles(live())

    override suspend fun forward(host: String, port: Int): ByteStream = throw unsupported("tunnels")

    override val canForward = false

    override val fileStore = true

    override val connected get() = connection.alive

    // the windows go over the connection of the console as WinBox keeps one for everything, so the router computes one login; the console closes it
    fun side(port: Int? = null) = object : NativeSide {
        override val sessionPort = port

        override suspend fun open(port: Int): NativeObjects = NativeObjectModel(live(), owned = false)
    }

    override fun close() {
        closed = true
        connection.close()
    }

    private suspend fun live(): NativeConnection = lock.withLock {
        if (!connection.alive && !closed) {
            backoff.lost(DeviceException(ErrorCategory.ConnectionLost, "the router closed the connection"))
            connection = try {
                backoff.opening(open)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceException) {
                throw e
            } catch (e: Exception) {
                // a connection the network does not let through is the link lost for the console, the files and the windows alike
                throw DeviceException(ErrorCategory.ConnectionLost, LINK_LOST, e)
            }
        }
        if (closed) {
            connection.close()
            throw DeviceException(ErrorCategory.ConnectionLost, "the session is closed")
        }
        connection
    }

    private fun unsupported(what: String) = DeviceException(ErrorCategory.Unsupported, "the native channel has no $what")

    companion object {
        suspend fun open(open: suspend () -> NativeConnection, password: String, time: TimeSource = TimeSource.Monotonic): NativeSsh {
            // the console is the session itself, so after a reboot of the router it has to be back within half a minute;
            // only an open that got as far as the login costs the router anything
            val backoff = Backoff(time, 30.seconds) { it is DeviceException }
            return NativeSsh(backoff.opening(open), password, open, backoff)
        }
    }
}

suspend fun connectNative(
    host: String,
    user: String,
    password: String,
    scope: CoroutineScope,
    port: Int = DEFAULT_PORT,
    via: RomonTarget? = null,
): NativeSsh = NativeSsh.open(
    {
        val connection = NativeConnection.open(host, user, password, scope, port)
        try {
            via?.let { connection.relay(it) }
        } catch (e: Exception) {
            connection.close()
            throw e
        }
        connection
    },
    via?.password ?: password,
)
