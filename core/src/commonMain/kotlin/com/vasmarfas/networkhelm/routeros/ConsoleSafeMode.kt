package com.vasmarfas.networkhelm.routeros

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class ConsoleSafeMode(
    private val ssh: SshSession,
    private val scope: CoroutineScope,
    private val count: suspend () -> Int,
    private val pollInterval: Duration,
) : SafeMode {
    private val held = MutableStateFlow(false)
    private val lock = Mutex()
    private var console: Console? = null

    private class Console(val channel: ShellChannel, val screen: MutableStateFlow<String>, val reader: Job)

    override val active: Flow<Boolean> = held

    override val actions: Flow<Int> = flow {
        while (true) {
            emit(if (held.value) count() else 0)
            delay(pollInterval)
        }
    }

    override suspend fun enter(): Boolean = lock.withLock {
        // a second console is asked whether to take the mode from the first, and its silence would read as the mode not held
        if (held.value) return@withLock true
        val channel = try {
            ssh.shell("xterm", COLUMNS, ROWS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DeviceException(ErrorCategory.ConnectionLost, e.message.orEmpty(), e)
        }
        val screen = MutableStateFlow("")
        val opened = Console(channel, screen, scope.launch { read(channel, screen) })
        val taken = try {
            withTimeoutOrNull(15.seconds) { screen.first { PROMPT in it } } != null && run {
                screen.value = ""
                channel.write(byteArrayOf(CTRL_X))
                withTimeoutOrNull(5.seconds) { screen.first { SAFE in it } } != null
            }
        } catch (e: Throwable) {
            close(opened)
            throw e
        }
        if (taken) console = opened else close(opened)
        held.value = taken
        taken
    }

    override suspend fun commit() = lock.withLock {
        val opened = console ?: return@withLock
        opened.screen.value = ""
        opened.channel.write(byteArrayOf(CTRL_X))
        withTimeoutOrNull(10.seconds) { opened.screen.first { PROMPT in it } }
            ?: throw DeviceException(ErrorCategory.ConnectionLost, "the console did not release Safe Mode")
        close(opened)
    }

    override suspend fun rollback() {
        lock.withLock { console?.let(::close) }
    }

    private fun close(opened: Console) {
        opened.reader.cancel()
        opened.channel.close()
        if (console === opened) console = null
        held.value = false
    }

    private suspend fun read(channel: ShellChannel, screen: MutableStateFlow<String>) {
        val buffer = ByteArray(4096)
        var licence = false
        var password = false
        try {
            while (true) {
                val read = channel.read(buffer, 0, buffer.size)
                if (read < 0) break
                val text = buffer.decodeToString(0, read)
                // the login console waits ten seconds for every unanswered ESC[6n, so each one is answered with the window size
                repeat(text.split(QUERY).size - 1) { channel.write("\u001b[$ROWS;${COLUMNS}R".encodeToByteArray()) }
                screen.value = (screen.value + COLOURS.replace(text.replace(QUERY, ""), "")).takeLast(4096)
                if (!licence && LICENCE in screen.value) {
                    licence = true
                    channel.write("n".encodeToByteArray())
                }
                if (!password && PASSWORD in screen.value) {
                    password = true
                    channel.write(byteArrayOf(CTRL_C))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a console cut off fails the read instead of ending it; for the router it ended either way
        }
        held.value = false
    }

    private companion object {
        const val COLUMNS = 200
        const val ROWS = 50
        const val CTRL_X: Byte = 0x18
        const val CTRL_C: Byte = 0x03
        const val QUERY = "\u001b[6n"
        const val PROMPT = "] > "
        const val SAFE = "<SAFE>"
        const val LICENCE = "software license? [Y/n]"
        const val PASSWORD = "Change your password (Ctrl-C to skip)"
        val COLOURS = Regex("\u001b\\[[0-9;?]*[A-Za-z]")
    }
}
