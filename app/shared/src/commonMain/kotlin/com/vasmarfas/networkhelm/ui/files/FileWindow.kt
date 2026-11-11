package com.vasmarfas.networkhelm.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Hash
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Redo2
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Replace
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.SaveAll
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.TextWrap
import com.composables.icons.lucide.Undo2
import com.composables.icons.lucide.ZoomIn
import com.composables.icons.lucide.ZoomOut
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.editor.EditorControls
import com.vasmarfas.networkhelm.ui.editor.EditorState
import com.vasmarfas.networkhelm.ui.editor.HexViewer
import com.vasmarfas.networkhelm.ui.editor.ImageViewer
import com.vasmarfas.networkhelm.ui.editor.Language
import com.vasmarfas.networkhelm.ui.editor.LineEnding
import com.vasmarfas.networkhelm.ui.editor.LineViewer
import com.vasmarfas.networkhelm.ui.editor.TextEditor
import com.vasmarfas.networkhelm.ui.editor.decodeText
import com.vasmarfas.networkhelm.ui.editor.languageOf
import com.vasmarfas.networkhelm.ui.editor.lineOf
import com.vasmarfas.networkhelm.ui.kit.LocalCompact
import com.vasmarfas.networkhelm.ui.kit.Select
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.kit.ToolbarButton
import com.vasmarfas.networkhelm.ui.kit.rememberCopier
import com.vasmarfas.networkhelm.ui.main.Confirm
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.workspace.WindowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.file_actual_size
import networkhelm.app.shared.generated.resources.file_cannot_encode
import networkhelm.app.shared.generated.resources.file_copy_all
import networkhelm.app.shared.generated.resources.file_discard
import networkhelm.app.shared.generated.resources.file_checking_ssh
import networkhelm.app.shared.generated.resources.file_find
import networkhelm.app.shared.generated.resources.file_goto_line
import networkhelm.app.shared.generated.resources.file_image_size
import networkhelm.app.shared.generated.resources.file_import
import networkhelm.app.shared.generated.resources.file_import_question
import networkhelm.app.shared.generated.resources.file_imported
import networkhelm.app.shared.generated.resources.file_line_col
import networkhelm.app.shared.generated.resources.file_lines
import networkhelm.app.shared.generated.resources.file_modified
import networkhelm.app.shared.generated.resources.file_needs_bulk
import networkhelm.app.shared.generated.resources.file_not_opened
import networkhelm.app.shared.generated.resources.file_read_only
import networkhelm.app.shared.generated.resources.file_read_only_big
import networkhelm.app.shared.generated.resources.file_read_only_rights
import networkhelm.app.shared.generated.resources.file_reading_via
import networkhelm.app.shared.generated.resources.file_reload
import networkhelm.app.shared.generated.resources.file_reload_question
import networkhelm.app.shared.generated.resources.file_replace
import networkhelm.app.shared.generated.resources.file_save
import networkhelm.app.shared.generated.resources.file_save_as
import networkhelm.app.shared.generated.resources.file_saved
import networkhelm.app.shared.generated.resources.file_too_big
import networkhelm.app.shared.generated.resources.file_unsaved_question
import networkhelm.app.shared.generated.resources.file_wrap
import networkhelm.app.shared.generated.resources.file_zoom_in
import networkhelm.app.shared.generated.resources.file_zoom_out
import networkhelm.app.shared.generated.resources.files_download
import networkhelm.app.shared.generated.resources.files_no_ftp
import networkhelm.app.shared.generated.resources.files_replace_question
import networkhelm.app.shared.generated.resources.main_redo
import networkhelm.app.shared.generated.resources.main_undo

internal val FILE_MENU = MenuPath.parse("file")

// the editor colours and measures the whole text on every key press, so it takes far less than the viewer
const val EDIT_LIMIT = 512 * 1024L
const val VIEW_LIMIT = 16 * 1024 * 1024L

@Stable
class FileSpec(path: String, id: String?, val size: Long?) {
    var path by mutableStateOf(path)
    var id by mutableStateOf(id)
    val name: String get() = path.substringAfterLast('/')

    // the text the editor holds and the bytes it was read from: a window that is minimized, under another one on a phone
    // or behind the tab of another router is not drawn, and what was typed into it is not read over from the router
    internal var draft: Pair<ByteArray, EditorState>? = null
}

private sealed interface Opened {
    class Reading(val via: FileVia?) : Opened
    class NoChannel(val why: Why, val port: Int?) : Opened
    class Failed(val message: String) : Opened
    class Refused(val message: String) : Opened
    class Edit(val bytes: ByteArray, val editor: EditorState) : Opened
    class Lines(val bytes: ByteArray, val text: String, val language: Language) : Opened
    class Picture(val bytes: ByteArray) : Opened
    class Dump(val bytes: ByteArray) : Opened
}

private enum class Asking { Close, Reload }

private suspend fun read(backend: FileBackend, spec: FileSpec, report: (Opened) -> Unit): Opened {
    val entry = Entry(spec.id, spec.path, false, spec.size, "", "")
    val size = spec.size
    if (size != null && size > VIEW_LIMIT) return Opened.Refused(getString(Res.string.file_too_big, sizeText(size)))
    if (entry.kind == FileKind.Package || entry.kind == FileKind.Backup || entry.kind == FileKind.Archive) return Opened.Refused(getString(Res.string.file_not_opened))
    report(Opened.Reading(backend.readsOver(entry)))
    val bytes = try {
        when (val route = backend.readRoute(entry)) {
            is Route.Blocked -> return Opened.NoChannel(route.why, route.port)
            is Route.Over -> report(Opened.Reading(route.via))
        }
        backend.read(entry)
    } catch (e: CancellationException) {
        throw e
    } catch (e: NoChannelException) {
        return Opened.NoChannel(e.why, e.port)
    } catch (e: Exception) {
        return Opened.Failed(fileFailure(e).orEmpty())
    }
    return withContext(Dispatchers.Default) {
        if (entry.kind == FileKind.Image) return@withContext Opened.Picture(bytes)
        val document = decodeText(bytes) ?: return@withContext Opened.Dump(bytes)
        val language = languageOf(spec.path)
        if (bytes.size <= EDIT_LIMIT) Opened.Edit(bytes, EditorState(document, language)) else Opened.Lines(bytes, document.text, language)
    }
}

@Composable
fun FileWindow(session: DeviceSession, spec: FileSpec, window: WindowState, onClose: () -> Unit) {
    val palette = Theme.palette
    val compact = LocalCompact.current
    val scope = rememberCoroutineScope()
    val backend = remember(session) { FileBackend(session, FILE_MENU) }
    val exchange = LocalFileExchange.current
    val copier = rememberCopier()
    val controls = remember { EditorControls() }
    var reloads by remember { mutableIntStateOf(0) }
    val bulk by backend.bulk.collectAsState()
    val via by backend.via.collectAsState()
    LaunchedEffect(backend) { backend.probe() }
    val opened by produceState<Opened>(Opened.Reading(backend.readsOver(Entry(spec.id, spec.path, false, spec.size, "", ""))), backend, reloads) {
        val unsaved = spec.draft?.takeIf { it.second.dirty }
        if (unsaved != null) {
            value = Opened.Edit(unsaved.first, unsaved.second)
            return@produceState
        }
        value = Opened.Reading(null)
        value = read(backend, spec) { value = it }.also { spec.draft = (it as? Opened.Edit)?.let { edit -> edit.bytes to edit.editor } }
    }
    var problem by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf<Asking?>(null) }
    var naming by remember { mutableStateOf<TextFieldState?>(null) }
    var replacing by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf<Float?>(null) }
    var picture by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var saving by remember { mutableStateOf(false) }
    val cannotEncode = stringResource(Res.string.file_cannot_encode)
    val editor = (opened as? Opened.Edit)?.editor
    val dirty = editor?.dirty == true
    val entry = Entry(spec.id, spec.path, false, spec.size, "", "")
    val writable = editor != null && backend.canWrite(entry)
    val windowTitle = spec.name + if (dirty) " *" else ""
    LaunchedEffect(windowTitle) { window.title = windowTitle }
    DisposableEffect(window, editor) {
        window.closeGuard = {
            val unsaved = saving || editor?.dirty == true
            if (unsaved) asking = Asking.Close
            !unsaved
        }
        // out of sight the window still holds its text: a close meant for it is refused, and a minimized one comes back to be asked there
        onDispose { window.closeGuard = { spec.draft?.second?.dirty != true } }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2500)
            notice = null
        }
    }

    fun guarded(block: suspend () -> Unit) {
        scope.launch {
            problem = try {
                block()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fileFailure(e)
            }
        }
    }

    fun save(path: String? = null, closeAfter: Boolean = false, replace: Boolean = false) {
        val edit = editor ?: return
        if (saving) return
        val snapshot = try {
            edit.saveSnapshot()
        } catch (e: IllegalArgumentException) {
            problem = cannotEncode
            return
        }
        val target = if (path == null) entry else Entry(null, path, false, null, "", "")
        saving = true
        guarded {
            try {
                if (path != null && !replace && backend.exists(path)) {
                    replacing = path
                    return@guarded
                }
                backend.write(target, snapshot.bytes)
                edit.markSaved(snapshot)
                if (path != null) {
                    spec.id = null
                    spec.path = path
                }
                notice = getString(Res.string.file_saved)
                if (closeAfter) {
                    if (edit.dirty) asking = Asking.Close else onClose()
                }
            } finally {
                saving = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(palette.surface)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(palette.raised).then(if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (editor != null) {
                    if (writable) {
                        ToolbarButton(stringResource(Res.string.file_save), Lucide.Save, { save() }, Modifier.testTag("file-save"), enabled = dirty && !saving, iconOnly = compact)
                        if (bulk is Bulk.Ready) {
                            ToolbarButton(stringResource(Res.string.file_save_as), Lucide.SaveAll, { naming = TextFieldState(spec.path) }, Modifier.testTag("file-save-as"), enabled = !saving, iconOnly = compact)
                        }
                        ToolbarButton(stringResource(Res.string.main_undo), Lucide.Undo2, editor::undo, enabled = editor.canUndo, iconOnly = true)
                        ToolbarButton(stringResource(Res.string.main_redo), Lucide.Redo2, editor::redo, enabled = editor.canRedo, iconOnly = true)
                    }
                    ToolbarButton(stringResource(Res.string.file_find), Lucide.Search, { controls.find.show(false) }, Modifier.testTag("file-find"), iconOnly = compact)
                    if (writable) ToolbarButton(stringResource(Res.string.file_replace), Lucide.Replace, { controls.find.show(true) }, Modifier.testTag("file-replace"), iconOnly = compact)
                    ToolbarButton(stringResource(Res.string.file_goto_line), Lucide.Hash, { controls.goto = true }, iconOnly = true)
                }
                if (opened is Opened.Edit || opened is Opened.Lines || opened is Opened.Dump) {
                    ToolbarButton(stringResource(Res.string.file_wrap), Lucide.TextWrap, { controls.wrap = !controls.wrap }, active = controls.wrap, iconOnly = true)
                    ToolbarButton(stringResource(Res.string.file_zoom_out), Lucide.ZoomOut, { controls.zoomBy(-0.1f) }, iconOnly = true)
                    ToolbarButton(stringResource(Res.string.file_zoom_in), Lucide.ZoomIn, { controls.zoomBy(0.1f) }, iconOnly = true)
                }
                if (opened is Opened.Picture) {
                    ToolbarButton(stringResource(Res.string.file_actual_size), Lucide.ZoomIn, { scale = if (scale == null) 1f else null }, active = scale != null, iconOnly = compact)
                }
                if (spec.name.endsWith(".rsc") && session.device.platform == Platform.RouterOs && session.device.canWrite) {
                    ToolbarButton(stringResource(Res.string.file_import), Lucide.Play, { importing = true }, Modifier.testTag("file-import"), iconOnly = compact)
                }
                val original = when (val now = opened) {
                    is Opened.Edit -> now.bytes
                    is Opened.Lines -> now.bytes
                    is Opened.Picture -> now.bytes
                    is Opened.Dump -> now.bytes
                    else -> null
                }
                if (original != null) {
                    ToolbarButton(stringResource(Res.string.files_download), Lucide.Download, { guarded { exchange.save(spec.name, original) } }, Modifier.testTag("file-download"), iconOnly = compact)
                }
                if (editor != null) {
                    ToolbarButton(stringResource(Res.string.file_copy_all), Lucide.Copy, { copier.copy(editor.snapshot()) }, iconOnly = true)
                }
                ToolbarButton(stringResource(Res.string.file_reload), Lucide.RefreshCw, { if (dirty) asking = Asking.Reload else reloads++ }, Modifier.testTag("file-reload"), enabled = !saving, iconOnly = true)
            }
            problem?.let { Text(it, Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 2.dp).testTag("file-problem"), style = Theme.type.small, color = palette.danger) }
            val missing = bulk as? Bulk.Missing
            if (missing != null && (opened is Opened.NoChannel || problem != null)) {
                BulkNote(session, backend, missing.why, missing.port, onProblem = { problem = it })
            }
            if (editor != null && !writable) {
                val reason = if (!session.device.canWrite) Res.string.file_read_only_rights else Res.string.files_no_ftp
                Text(stringResource(Res.string.file_read_only, stringResource(reason)), Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), style = Theme.type.small, color = palette.muted)
            }
            if (opened is Opened.Lines) {
                Text(stringResource(Res.string.file_read_only_big, sizeText(spec.size)), Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), style = Theme.type.small, color = palette.muted)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val now = opened) {
                    is Opened.Reading -> Text(
                        now.via?.let { stringResource(Res.string.file_reading_via, viaName(it)) } ?: stringResource(Res.string.file_checking_ssh),
                        Modifier.padding(12.dp).testTag("file-reading"),
                        color = palette.muted,
                    )
                    is Opened.NoChannel -> Text(stringResource(Res.string.file_needs_bulk), Modifier.padding(12.dp).testTag("file-needs-bulk"), color = palette.muted)
                    is Opened.Failed -> Text(now.message, Modifier.padding(12.dp).testTag("file-failed"), color = palette.danger)
                    is Opened.Refused -> Text(now.message, Modifier.padding(12.dp).testTag("file-refused"), color = palette.muted)
                    is Opened.Edit -> TextEditor(now.editor, controls, readOnly = !writable, Modifier.fillMaxSize()) { save() }
                    is Opened.Lines -> LineViewer(now.text, now.language, controls, Modifier.fillMaxSize())
                    is Opened.Picture -> ImageViewer(now.bytes, scale, { w, h -> picture = w to h }) {
                        Text(stringResource(Res.string.file_not_opened), Modifier.padding(12.dp), color = palette.muted)
                    }
                    is Opened.Dump -> HexViewer(now.bytes, controls, Modifier.fillMaxSize())
                }
            }
            Row(
                Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("/" + spec.path, Modifier.weight(1f).testTag("file-path"), style = Theme.type.small, color = palette.muted, singleLine = true, overflow = TextOverflow.Ellipsis)
                notice?.let { Text(it, Modifier.testTag("file-notice"), style = Theme.type.small, color = palette.success) }
                via?.let { Text(viaText(it), Modifier.testTag("file-via"), style = Theme.type.small, color = palette.muted) }
                if (editor != null) {
                    val starts = editor.starts
                    val caret = editor.text.selection.start
                    val line = lineOf(starts, caret)
                    if (dirty) Text(stringResource(Res.string.file_modified), Modifier.testTag("file-modified"), style = Theme.type.small, color = palette.warning)
                    Text(stringResource(Res.string.file_line_col, line + 1, caret - starts[line] + 1), Modifier.testTag("file-position"), style = Theme.type.small, color = palette.muted)
                    Text(stringResource(Res.string.file_lines, starts.size), style = Theme.type.small, color = palette.muted)
                    if (writable) {
                        Select(editor.ending, LineEnding.entries, { it.label }, { editor.ending = it }, Modifier.width(76.dp))
                        Select(editor.cp1251, listOf(false, true), { if (it) "cp1251" else "UTF-8" }, { editor.cp1251 = it }, Modifier.width(96.dp))
                    } else {
                        Text(editor.document.ending.label, style = Theme.type.small, color = palette.muted)
                        Text(editor.document.charset, style = Theme.type.small, color = palette.muted)
                    }
                }
                picture?.takeIf { opened is Opened.Picture }?.let { (w, h) -> Text(stringResource(Res.string.file_image_size, w, h), style = Theme.type.small, color = palette.muted) }
                spec.size?.let { Text(sizeText(it), style = Theme.type.small, color = palette.muted) }
            }
        }
        when (asking) {
            Asking.Close -> Confirm(
                stringResource(Res.string.file_unsaved_question, spec.name),
                stringResource(Res.string.file_save),
                onCancel = { asking = null },
                alternative = stringResource(Res.string.file_discard) to {
                    asking = null
                    window.closeGuard = null
                    onClose()
                },
            ) {
                asking = null
                save(closeAfter = true)
            }
            Asking.Reload -> Confirm(stringResource(Res.string.file_reload_question, spec.name), stringResource(Res.string.file_reload), onCancel = { asking = null }) {
                asking = null
                spec.draft = null
                reloads++
            }
            null -> {}
        }
        naming?.let { field ->
            Confirm(stringResource(Res.string.file_save_as), stringResource(Res.string.file_save), onCancel = { naming = null }, typing = true, content = {
                TextField(field, Modifier.width(360.dp).testTag("file-save-as-name"), mono = true)
            }) {
                val path = field.text.toString().trim().trim('/')
                naming = null
                if (path.isNotEmpty()) save(path)
            }
        }
        replacing?.let { path ->
            Confirm(stringResource(Res.string.files_replace_question, "/$path"), stringResource(Res.string.file_replace), onCancel = { replacing = null }) {
                replacing = null
                save(path, replace = true)
            }
        }
        if (importing) {
            Confirm(stringResource(Res.string.file_import_question, spec.name), stringResource(Res.string.file_import), onCancel = { importing = false }) {
                importing = false
                guarded {
                    session.run(MenuPath.Root, "import", mapOf("file-name" to spec.path)).toList()
                    notice = getString(Res.string.file_imported)
                }
            }
        }
    }
}
