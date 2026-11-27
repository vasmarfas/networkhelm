package com.vasmarfas.networkhelm.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.app.LocalSnippets
import com.vasmarfas.networkhelm.app.Snippet
import com.vasmarfas.networkhelm.app.Snippets
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.ScrollArea
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.kit.ToolbarButton
import com.vasmarfas.networkhelm.ui.kit.dialogPanel
import com.vasmarfas.networkhelm.ui.kit.scrim
import com.vasmarfas.networkhelm.ui.main.ActionsMenu
import com.vasmarfas.networkhelm.ui.main.deviceMessage
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.form_ok
import networkhelm.app.shared.generated.resources.link_closed
import networkhelm.app.shared.generated.resources.link_lost
import networkhelm.app.shared.generated.resources.list_cancel
import networkhelm.app.shared.generated.resources.list_remove
import networkhelm.app.shared.generated.resources.snippets_add
import networkhelm.app.shared.generated.resources.snippets_edit
import networkhelm.app.shared.generated.resources.snippets_hint
import networkhelm.app.shared.generated.resources.snippets_name
import networkhelm.app.shared.generated.resources.snippets_title
import networkhelm.app.shared.generated.resources.snippets_unreadable
import networkhelm.app.shared.generated.resources.ssh_denied
import networkhelm.app.shared.generated.resources.ssh_refused
import networkhelm.app.shared.generated.resources.ssh_timeout
import networkhelm.app.shared.generated.resources.telnet_address
import networkhelm.app.shared.generated.resources.telnet_connect
import networkhelm.app.shared.generated.resources.terminal_failed
import networkhelm.app.shared.generated.resources.terminal_key_ctrl_c
import networkhelm.app.shared.generated.resources.terminal_key_down
import networkhelm.app.shared.generated.resources.terminal_key_esc
import networkhelm.app.shared.generated.resources.terminal_key_left
import networkhelm.app.shared.generated.resources.terminal_key_right
import networkhelm.app.shared.generated.resources.terminal_key_tab
import networkhelm.app.shared.generated.resources.terminal_key_up
import networkhelm.app.shared.generated.resources.terminal_opening
import networkhelm.app.shared.generated.resources.terminal_reopen
import networkhelm.app.shared.generated.resources.terminal_root

val LocalTerminal = staticCompositionLocalOf<(@Composable (ShellChannel, Modifier) -> Unit)?> { null }

internal expect val terminalScrollback: Boolean

// the consoles of the terminal windows of a router, by window: a console stays open while the tab of another router is shown
// and goes with its window, with Reopen and with the session
class Consoles {
    private val kept = HashMap<Long, KeptShell>()

    internal fun of(id: Long, ssh: SshSession): KeptShell? = kept[id]?.takeIf { it.ssh === ssh && !it.cut }

    // the console of RouterOS keeps a line typed before its prompt and drops the Enter, so there the line waits for the prompt
    internal suspend fun open(id: Long, ssh: SshSession, startup: String?, atPrompt: Boolean = false): KeptShell {
        of(id, ssh)?.let { return it }
        close(id)
        val opened = KeptShell(ssh.shell("xterm-256color", 120, 40), ssh, startup.takeIf { atPrompt })
        kept[id] = opened
        if (!atPrompt) startup?.let { opened.write("$it\r".encodeToByteArray()) }
        return opened
    }

    fun close(id: Long) {
        kept.remove(id)?.close()
    }

    fun closeAll() {
        kept.keys.toList().forEach(::close)
    }
}

// sudo: the password of the login for the button that makes the console root, empty when the host did not get one; null, no button
@Composable
fun TerminalView(ssh: SshSession, consoles: Consoles, id: Long, startup: String? = null, sudo: String? = null, atPrompt: Boolean = false, terminal: @Composable (ShellChannel, Modifier) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    val shell by produceState(consoles.of(id, ssh)?.let { Result.success(it) }, ssh, attempt) {
        value = try {
            Result.success(consoles.open(id, ssh, startup, atPrompt))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    val result = shell
    val snippets = LocalSnippets.current
    var editing by remember { mutableStateOf(false) }
    val reopen: () -> Unit = {
        consoles.close(id)
        attempt++
    }
    when {
        result == null -> Note(stringResource(Res.string.terminal_opening))
        result.isFailure -> {
            val e = result.exceptionOrNull()
            Failure(stringResource(Res.string.terminal_failed, e?.let { sshFailure(it, ssh.canForward) }?.let { stringResource(it) } ?: deviceMessage(e?.message.orEmpty())), reopen)
        }
        result.getOrThrow().cut -> Failure(stringResource(Res.string.link_closed), reopen)
        snippets == null -> key(result.getOrThrow()) { terminal(result.getOrThrow(), Modifier.fillMaxSize()) }
        else -> Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                SnippetBar(snippets, result.getOrThrow(), reopen, sudo) { editing = true }
                if (LocalCompact.current) KeysBar(result.getOrThrow())
                key(result.getOrThrow()) { terminal(result.getOrThrow(), Modifier.weight(1f).fillMaxWidth()) }
            }
            if (editing) SnippetEditor(snippets) { editing = false }
        }
    }
}

private val telnetAddress = Regex("[A-Za-z0-9.:_%\\[\\]-]+")

class TelnetCall(val address: String, val mac: Boolean)

// the router connects: the address goes to its own telnet client, as the Telnet window of WinBox does; a call connects at once
@Composable
fun TelnetView(ssh: SshSession, consoles: Consoles, id: Long, terminal: @Composable (ShellChannel, Modifier) -> Unit, call: TelnetCall? = null) {
    val address = remember { TextFieldState(call?.address.orEmpty()) }
    var target by remember { mutableStateOf(call?.address?.takeIf { telnetAddress.matches(it) }) }
    val started = target
    // a console the window kept is already connected
    if (started != null || consoles.of(id, ssh) != null) {
        TerminalView(ssh, consoles, id, "${if (call?.mac == true) "/tool mac-telnet" else "/system telnet"} ${started.orEmpty()}", atPrompt = true, terminal = terminal)
        return
    }
    val typed = address.text.toString().trim()
    val valid = telnetAddress.matches(typed)
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.telnet_address), style = Theme.type.strong)
            TextField(
                address,
                Modifier.weight(1f).testTag("telnet-address").onPreviewKeyEvent { event ->
                    val enter = event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)
                    if (enter && valid) target = typed
                    enter
                },
                mono = true,
            )
        }
        Button(stringResource(Res.string.telnet_connect), { target = typed }, Modifier.testTag("telnet-connect"), enabled = valid, primary = true)
    }
}

// only SSH opens a connection of its own, and only it forwards ports: the console of the WinBox port and of MAC-Telnet goes over the connection of the session and fails with it
internal fun sshFailure(e: Throwable, own: Boolean = true): StringResource? {
    val causes = generateSequence(e) { it.cause }.mapNotNull { it::class.simpleName }.toList()
    val unreached = "ConnectException" in causes || "SocketTimeoutException" in causes
    return when {
        !own -> Res.string.link_lost.takeIf { unreached || e is DeviceException && e.category == ErrorCategory.ConnectionLost }
        e is DeviceException && e.category == ErrorCategory.PermissionDenied -> Res.string.ssh_denied
        "ConnectException" in causes -> Res.string.ssh_refused
        "SocketTimeoutException" in causes -> Res.string.ssh_timeout
        else -> null
    }
}

@Composable
private fun SnippetBar(snippets: Snippets, channel: ShellChannel, onReopen: () -> Unit, sudo: String?, onEdit: () -> Unit) {
    val all by snippets.all.collectAsState()
    val scope = rememberCoroutineScope()
    val edit = stringResource(Res.string.snippets_edit)
    Row(
        Modifier.fillMaxWidth().background(Theme.palette.raised).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ActionsMenu(stringResource(Res.string.snippets_title), Lucide.Code, all + null, { true }, { it?.title ?: edit }, Modifier.testTag("snippets")) { snippet ->
            if (snippet == null) onEdit() else scope.launch { channel.write(snippet.text.replace("\r\n", "\n").replace('\n', '\r').encodeToByteArray()) }
        }
        Row {
            if (sudo != null && channel is KeptShell) {
                ToolbarButton(stringResource(Res.string.terminal_root), Lucide.KeyRound, { scope.launch { channel.elevate(sudo) } }, Modifier.testTag("terminal-root"), iconOnly = true)
            }
            ToolbarButton(stringResource(Res.string.terminal_reopen), Lucide.RotateCw, onReopen, Modifier.testTag("terminal-reopen"), iconOnly = true)
        }
    }
}

@Composable
private fun KeysBar(channel: KeptShell) {
    val scope = rememberCoroutineScope()
    fun send(text: String) {
        scope.launch { channel.write(text.encodeToByteArray()) }
    }
    Row(
        Modifier.fillMaxWidth().background(Theme.palette.raised).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        KeyButton("key-tab", Res.string.terminal_key_tab, label = "Tab") { send("\t") }
        KeyButton("key-esc", Res.string.terminal_key_esc, label = "Esc") { send("\u001b") }
        KeyButton("key-up", Res.string.terminal_key_up, icon = Lucide.ArrowUp) { send(channel.modes.cursorKey('A')) }
        KeyButton("key-down", Res.string.terminal_key_down, icon = Lucide.ArrowDown) { send(channel.modes.cursorKey('B')) }
        KeyButton("key-left", Res.string.terminal_key_left, icon = Lucide.ArrowLeft) { send(channel.modes.cursorKey('D')) }
        KeyButton("key-right", Res.string.terminal_key_right, icon = Lucide.ArrowRight) { send(channel.modes.cursorKey('C')) }
        KeyButton("key-ctrl-c", Res.string.terminal_key_ctrl_c, label = "Ctrl+C") { send("\u0003") }
    }
}

@Composable
private fun KeyButton(tag: String, description: StringResource, label: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    val text = stringResource(description)
    val described = if (label != null) Modifier.semantics { contentDescription = text } else Modifier
    ToolbarButton(label ?: text, icon, onClick, Modifier.testTag(tag).then(described), iconOnly = icon != null)
}

@Composable
private fun SnippetEditor(snippets: Snippets, onClose: () -> Unit) {
    val palette = Theme.palette
    val shape = RoundedCornerShape(Theme.metrics.radius)
    val scope = rememberCoroutineScope()
    val opened = remember { snippets.all.value }
    val rows = remember { opened.map { TextFieldState(it.name) to TextFieldState(it.text) }.toMutableStateList() }
    var failure by remember { mutableStateOf<String?>(null) }
    val unreadable = stringResource(Res.string.snippets_unreadable)
    Box(Modifier.fillMaxSize().scrim(palette.workspace.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxHeight(0.9f).padding(12.dp).dialogPanel(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(Res.string.snippets_title), style = Theme.type.strong)
            Text(stringResource(Res.string.snippets_hint), color = palette.muted, style = Theme.type.small)
            ScrollArea(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEachIndexed { index, (name, text) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextField(name, Modifier.fillMaxWidth().testTag("snippet-name"), placeholder = stringResource(Res.string.snippets_name))
                            BasicTextField(
                                text,
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).background(palette.surface, shape).border(1.dp, palette.line, shape).padding(6.dp)
                                    .testTag("snippet-text"),
                                textStyle = Theme.type.mono.copy(color = palette.text),
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                                cursorBrush = SolidColor(palette.accent),
                                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2),
                            )
                        }
                        IconButton(Lucide.X, stringResource(Res.string.list_remove), { rows.removeAt(index) })
                    }
                }
            }
            failure?.let { Text(it, color = palette.danger, style = Theme.type.small) }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(stringResource(Res.string.snippets_add), { rows += TextFieldState() to TextFieldState() }, Modifier.testTag("snippet-add"))
                Button(stringResource(Res.string.list_cancel), onClose)
                Button(stringResource(Res.string.form_ok), {
                    val kept = rows.map { (name, text) -> Snippet(name.text.toString().trim(), text.text.toString()) }.filter { it.name.isNotEmpty() || it.text.isNotBlank() }
                    scope.launch {
                        try {
                            snippets.save(opened, kept)
                            onClose()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IllegalStateException) {
                            failure = unreadable
                        } catch (e: Exception) {
                            failure = e.message ?: e.toString()
                        }
                    }
                }, primary = true)
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Box(Modifier.fillMaxSize().padding(12.dp)) { Text(text, color = Theme.palette.muted) }
}

@Composable
private fun Failure(text: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, color = Theme.palette.muted)
        Button(stringResource(Res.string.terminal_reopen), onRetry, Modifier.testTag("terminal-reopen"))
    }
}

// the terminals read and type in effects that catch nothing, so a console cut off under them would end the app; the window says so instead
internal class KeptShell(private val shell: ShellChannel, val ssh: SshSession, @Volatile private var typed: String? = null) : ShellChannel by shell {
    var cut by mutableStateOf(false)
        private set

    // what the platform keeps to draw the console again when its window comes back, and how it lets it go
    var screen: Any? = null
    var release: () -> Unit = {}

    // the modes the program on the screen set, for the arrows of the keys bar
    val modes = TerminalMouse()

    // whether the console held the keyboard, for its screen in the other layout
    var keyboard = false

    private var closed = false

    // sudo asks with a prompt of its own: the typed line shows it with %u, the question itself with the name of the user
    @Volatile
    private var asking: Pair<String, TimeMark>? = null
    private val heard = StringBuilder()

    suspend fun elevate(password: String) {
        if (password.isNotEmpty()) asking = password to TimeSource.Monotonic.markNow()
        write("sudo -p '$SUDO_PROMPT' -s\r".encodeToByteArray())
    }

    private suspend fun answer(text: String) {
        val (password, since) = asking ?: return
        heard.append(text)
        if (heard.length > 1024) heard.deleteRange(0, heard.length - 256)
        when {
            since.elapsedNow() > 30.seconds -> asking = null
            SUDO_ASKS.containsMatchIn(heard) -> {
                asking = null
                heard.clear()
                write("$password\r".encodeToByteArray())
            }
        }
    }

    private val shown = StringBuilder()

    private suspend fun prompted(text: String) {
        val line = typed ?: return
        shown.append(text)
        if (shown.length > 1024) shown.deleteRange(0, shown.length - 256)
        if (!ROUTEROS_PROMPT.containsMatchIn(shown.replace(ESCAPES, ""))) return
        typed = null
        shown.clear()
        write("$line\r".encodeToByteArray())
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        shell.read(buffer, offset, length).also {
            if (it > 0) {
                val text = buffer.decodeToString(offset, offset + it)
                modes.read(text)
                answer(text)
                prompted(text)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        cut = true
        -1
    }

    override suspend fun write(bytes: ByteArray) {
        try {
            shell.write(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cut = true
        }
    }

    // the screen closes its process, which comes back here
    override fun close() {
        if (closed) return
        closed = true
        release()
        shell.close()
    }
}

private const val SUDO_PROMPT = "NetworkHelm sudo for %u: "
private val SUDO_ASKS = Regex("NetworkHelm sudo for [^%\\s]+: ")
private val ROUTEROS_PROMPT = Regex("\\][^\\n]*> $")
private val ESCAPES = Regex("\u001b\\[[0-9;?]*[A-Za-z]")
