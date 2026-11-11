package com.vasmarfas.networkhelm.ssh

import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.TcpListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// the streams block their threads, so close ends them by closing them, not by cancellation
class Forwarding(private val ssh: SshSession, private val listener: TcpListener, val host: String, val port: Int) {
    val localPort: Int get() = listener.port

    private val open = MutableStateFlow(emptySet<ByteStream>())
    private val count = MutableStateFlow(0)
    private val failure = MutableStateFlow<String?>(null)
    private val stopped = MutableStateFlow(false)

    val connections: StateFlow<Int> = count.asStateFlow()

    val refused: StateFlow<String?> = failure.asStateFlow()

    suspend fun serve() = coroutineScope {
        while (true) {
            val local = try {
                listener.accept()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a listener that takes no more connections ends the tunnel for new ones and tells why
                if (!stopped.value) failure.value = e.message ?: e::class.simpleName
                break
            }
            launch { carry(local) }
        }
    }

    fun close() {
        stopped.value = true
        listener.close()
        open.value.forEach { it.close() }
    }

    private suspend fun carry(local: ByteStream) {
        count.update { it + 1 }
        open.update { it + local }
        if (stopped.value) local.close()
        try {
            val remote = try {
                ssh.forward(host, port)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure.value = e.message ?: e::class.simpleName
                return
            }
            open.update { it + remote }
            if (stopped.value) remote.close()
            try {
                coroutineScope {
                    launch { pump(local, remote) }
                    pump(remote, local)
                }
            } finally {
                remote.close()
                open.update { it - remote }
            }
        } finally {
            local.close()
            open.update { it - local }
            count.update { it - 1 }
        }
    }
}

private suspend fun pump(from: ByteStream, to: ByteStream) {
    val buffer = ByteArray(16 * 1024)
    try {
        while (true) {
            val read = from.read(buffer, 0, buffer.size)
            if (read < 0) break
            to.write(buffer.copyOf(read))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // a reset, or a stream closed under the read, ends the connection as a close does
    } finally {
        to.close()
    }
}
