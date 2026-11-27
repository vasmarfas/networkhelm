package com.vasmarfas.networkhelm.ui.terminal

import ai.rever.bossterm.compose.EmbeddableTerminal
import ai.rever.bossterm.compose.EmbeddableTerminalState
import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.rememberEmbeddableTerminalState
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import com.vasmarfas.networkhelm.app.LocalSettings
import com.vasmarfas.networkhelm.ssh.ShellChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

@Composable
fun BossTerminal(shell: ShellChannel, modifier: Modifier) {
    val services = remember(shell) { SshPlatformServices(shell) }
    val lines = LocalSettings.current.terminalLines
    // the screen of a console its window keeps lives as long as the console, so the window comes back as it was left
    val kept = shell as? KeptShell
    val opened = remember(kept) { kept?.screen == null }
    val state = if (kept == null) {
        rememberEmbeddableTerminalState()
    } else {
        remember(kept) { kept.screen as? EmbeddableTerminalState ?: EmbeddableTerminalState().also { kept.screen = it; kept.release = it::dispose } }
    }
    val focus = remember { FocusRequester() }
    // the screen of a new console comes once its shell is connected, and the keyboard goes to it then. A console that held the
    // keyboard takes it back in the other layout, as the program swaps the layouts of a computer and a phone at 600 by 480 dp
    val connected = state.isConnected
    LaunchedEffect(kept, connected) { if ((opened || kept?.keyboard == true) && connected) focus.requestFocus() }
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    val modes = kept?.modes
    // the console takes the press: left to the workspace behind, it would take the keyboard back on release
    Box(
        modifier.focusRequester(focus)
            // the console a layout drops loses the keyboard without a choice of the person, and its scope ends before the next frame
            .onFocusChanged { change ->
                focused = change.hasFocus
                if (kept != null) scope.launch {
                    withFrameNanos { }
                    kept.keyboard = focused
                }
            }
            .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).consume()
                focus.requestFocus()
            }
        }
            // BossTerm turns its cursor keys back to ESC [ A on ESC >, which mc 4.8 sends after ESC [ ? 1 h as it starts, and mc
            // takes them only as ESC O A then. The keys without modifiers follow the mode the console read
            .onPreviewKeyEvent { event ->
                val final = CURSOR_KEYS[event.key]
                val plain = !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed && !event.isMetaPressed
                if (modes == null || final == null || !plain || !modes.cursorKeys || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                state.writeVerbatim(modes.cursorKey(final))
                true
            },
    ) {
        EmbeddableTerminal(
            state = state,
            modifier = Modifier.fillMaxSize(),
            autoFocus = true,
            platformServices = services,
            settingsOverride = remember(lines) { TerminalSettingsOverride(bufferMaxLines = lines) },
        )
    }
}

internal actual val terminalScrollback = true

private val CURSOR_KEYS = mapOf(
    Key.DirectionUp to 'A', Key.DirectionDown to 'B', Key.DirectionRight to 'C', Key.DirectionLeft to 'D', Key.MoveHome to 'H', Key.MoveEnd to 'F',
)

private class SshPlatformServices(private val shell: ShellChannel, defaults: PlatformServices = getPlatformServices()) : PlatformServices by defaults {
    override fun getProcessService(): PlatformServices.ProcessService = object : PlatformServices.ProcessService {
        override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig): PlatformServices.ProcessService.ProcessHandle =
            SshProcessHandle(shell)
    }
}

internal class SshProcessHandle(private val shell: ShellChannel) : PlatformServices.ProcessService.ProcessHandle {
    private val closed = CompletableDeferred<Int>()

    // a byte that is not UTF-8 left in the buffer would stop the decoding, and the console with it, for good
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val bytes = ByteBuffer.allocate(16 * 1024)
    private val chars = CharBuffer.allocate(16 * 1024)

    override suspend fun write(data: String) = shell.write(data.encodeToByteArray())

    override suspend fun writeBytes(data: ByteArray) = shell.write(data)

    // a UTF-8 sequence split between two reads stays in the buffer until the rest arrives
    override suspend fun read(): String? {
        val count = shell.read(bytes.array(), bytes.position(), bytes.remaining())
        if (count < 0) {
            closed.complete(0)
            return null
        }
        bytes.position(bytes.position() + count)
        bytes.flip()
        chars.clear()
        decoder.decode(bytes, chars, false)
        bytes.compact()
        chars.flip()
        return chars.toString()
    }

    override fun isAlive(): Boolean = !closed.isCompleted

    override suspend fun kill() {
        shell.close()
        closed.complete(0)
    }

    override suspend fun waitFor(): Int = closed.await()

    override suspend fun resize(columns: Int, rows: Int) = shell.resize(columns, rows)

    override fun getExitCode(): Int? = if (closed.isCompleted) 0 else null

    override fun getPid(): Long? = null

    override fun getWorkingDirectory(): String? = null
}
