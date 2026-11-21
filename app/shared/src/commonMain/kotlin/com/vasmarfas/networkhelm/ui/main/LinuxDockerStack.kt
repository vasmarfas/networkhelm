package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.editor.EditorControls
import com.vasmarfas.networkhelm.ui.editor.EditorState
import com.vasmarfas.networkhelm.ui.editor.Language
import com.vasmarfas.networkhelm.ui.editor.LineEnding
import com.vasmarfas.networkhelm.ui.editor.TextDocument
import com.vasmarfas.networkhelm.ui.editor.TextEditor
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.Checkbox
import com.vasmarfas.networkhelm.ui.kit.Select
import com.vasmarfas.networkhelm.ui.kit.TabStrip
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.docker_log_filter
import networkhelm.app.shared.generated.resources.docker_refresh
import networkhelm.app.shared.generated.resources.docker_save
import networkhelm.app.shared.generated.resources.docker_stack_create
import networkhelm.app.shared.generated.resources.docker_stack_created
import networkhelm.app.shared.generated.resources.docker_stack_git
import networkhelm.app.shared.generated.resources.docker_stack_hint
import networkhelm.app.shared.generated.resources.docker_stack_name
import networkhelm.app.shared.generated.resources.docker_stack_none
import networkhelm.app.shared.generated.resources.docker_stack_own
import networkhelm.app.shared.generated.resources.docker_stack_remove_template
import networkhelm.app.shared.generated.resources.docker_stack_save_template
import networkhelm.app.shared.generated.resources.docker_stack_saved
import networkhelm.app.shared.generated.resources.docker_stack_start
import networkhelm.app.shared.generated.resources.docker_stack_template_saved
import networkhelm.app.shared.generated.resources.docker_tab_compose
import networkhelm.app.shared.generated.resources.docker_tab_env
import networkhelm.app.shared.generated.resources.docker_tab_log
import networkhelm.app.shared.generated.resources.docker_tab_services
import networkhelm.app.shared.generated.resources.docker_working
import networkhelm.app.shared.generated.resources.window_loading
import org.jetbrains.compose.resources.stringResource

private val STACK = MenuPath.parse("container/stack")

private val STACK_TABS = listOf(
    "services" to Res.string.docker_tab_services,
    "log" to Res.string.docker_tab_log,
    "compose" to Res.string.docker_tab_compose,
    "env" to Res.string.docker_tab_env,
)

private fun editor(text: String, language: Language) = EditorState(TextDocument(text, LineEnding.Lf, cp1251 = false, bom = false, mixed = false), language)

// a new stack: the files typed or taken from a template, or a repository of Git; Compose reads it on the host before it starts
@Composable
internal fun StackNew(session: DeviceSession, template: String) {
    val palette = Theme.palette
    val templates = LocalDockerTemplates.current
    val own by (templates?.all ?: NO_TEMPLATES).collectAsState()
    val choices = TEMPLATES + own
    val name = remember { TextFieldState() }
    val git = remember { TextFieldState() }
    var chosen by remember { mutableStateOf(template) }
    var start by remember { mutableStateOf(true) }
    val compose = remember { editor("", Language.Yaml) }
    val env = remember { editor("", Language.Config) }
    val composeControls = remember { EditorControls() }
    val envControls = remember { EditorControls() }
    val said = remember { mutableStateListOf<String>() }
    var made by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun choose(title: String) {
        chosen = title
        val picked = choices.firstOrNull { it.name == title } ?: return
        compose.text.setTextAndPlaceCursorAtEnd(picked.compose)
        env.text.setTextAndPlaceCursorAtEnd(picked.env)
        if (name.text.isEmpty()) name.setTextAndPlaceCursorAtEnd(picked.name.lowercase().replace(' ', '-'))
    }
    LaunchedEffect(template) { if (template.isNotEmpty()) choose(template) }
    fun create() = scope.launch {
        running = true
        problem = null
        made = null
        said.clear()
        try {
            val values = mapOf(
                "name" to name.text.toString().trim(), "git" to git.text.toString().trim(), "compose" to compose.snapshot(), "env" to env.snapshot(), "start" to "$start",
            )
            said += session.run(STACK, "create", values).toList().map { it["line"].orEmpty() }
            made = values["name"]
        } catch (e: DeviceException) {
            problem = e.message
        } finally {
            running = false
        }
    }
    fun forget() = scope.launch {
        problem = try {
            templates?.remove(chosen)
            chosen = ""
            null
        } catch (e: IllegalStateException) {
            e.message
        }
    }
    val ownChosen = own.any { it.name == chosen }
    Column(Modifier.fillMaxSize().testTag("docker-stack-new")) {
        FlowRow(
            Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(name, Modifier.width(180.dp).testTag("docker-stack-name"), placeholder = stringResource(Res.string.docker_stack_name), mono = true)
            val none = stringResource(Res.string.docker_stack_none)
            val owned = choices.drop(TEMPLATES.size).map { it.name }.toSet()
            Select(chosen, listOf("") + choices.map { it.name }.distinct(), { title ->
                when {
                    title.isEmpty() -> none
                    title in owned -> stringResource(Res.string.docker_stack_own, title)
                    else -> title
                }
            }, ::choose, Modifier.width(200.dp))
            if (ownChosen) Button(stringResource(Res.string.docker_stack_remove_template), { forget() })
            TextField(git, Modifier.width(280.dp), placeholder = stringResource(Res.string.docker_stack_git), mono = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(start, { start = it })
                Text(stringResource(Res.string.docker_stack_start), style = Theme.type.small)
            }
            Button(stringResource(Res.string.docker_stack_create), { create() }, Modifier.testTag("docker-stack-create"), enabled = !running && name.text.isNotBlank(), primary = true)
        }
        Text(stringResource(Res.string.docker_stack_hint), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = palette.muted, style = Theme.type.small)
        if (running) Text(stringResource(Res.string.docker_working), Modifier.padding(horizontal = 12.dp), color = palette.muted, style = Theme.type.small)
        problem?.let { Text(deviceMessage(it), Modifier.padding(horizontal = 12.dp), color = palette.danger, style = Theme.type.small) }
        made?.let { Text(stringResource(Res.string.docker_stack_created, it), Modifier.padding(horizontal = 12.dp), color = palette.success, style = Theme.type.small) }
        if (said.isNotEmpty()) SelectionContainer { Column(Modifier.padding(horizontal = 12.dp)) { for (line in said) Text(line, style = Theme.type.mono) } }
        if (git.text.isBlank()) {
            Text(stringResource(Res.string.docker_tab_compose), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = Theme.type.strong)
            TextEditor(compose, composeControls, readOnly = false, modifier = Modifier.weight(2f).fillMaxWidth())
            Text(stringResource(Res.string.docker_tab_env), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = Theme.type.strong)
            TextEditor(env, envControls, readOnly = false, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}

// a stack in a window of its own: its services, their log in one, and its files of Compose, which Compose reads before they are saved
@Composable
internal fun StackView(session: DeviceSession, stack: String, opened: String) {
    var shown by remember(stack) { mutableIntStateOf(STACK_TABS.indexOfFirst { it.first == opened }.coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize().testTag("docker-stack")) {
        TabStrip(STACK_TABS.map { stringResource(it.second) }, shown, { shown = it })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (STACK_TABS[shown].first) {
                "services" -> StackServices(session, stack)
                "log" -> StackLog(session, stack)
                else -> StackFile(session, stack, STACK_TABS[shown].first)
            }
        }
    }
}

@Composable
private fun StackServices(session: DeviceSession, stack: String) {
    val palette = Theme.palette
    var rows by remember(stack) { mutableStateOf<List<Item>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(stack, attempt) {
        problem = try {
            rows = session.run(STACK, "services", mapOf("id" to stack)).toList()
            null
        } catch (e: DeviceException) {
            e.message
        }
    }
    val shown = rows
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Button(stringResource(Res.string.docker_refresh), { attempt++ })
        }
        problem?.let { Problem(it) { attempt++ } }
        if (shown == null) {
            Text(stringResource(Res.string.window_loading), Modifier.padding(12.dp), color = palette.muted, style = Theme.type.small)
        } else {
            SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    items(shown.size) { index ->
                        val row = shown[index]
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(row["service"].orEmpty(), Modifier.width(120.dp), style = Theme.type.mono)
                            Text(row["name"].orEmpty(), Modifier.width(200.dp), style = Theme.type.mono)
                            Text(row["status"].orEmpty(), Modifier.width(150.dp), style = Theme.type.small, color = if (row["state"] == "running") palette.success else palette.muted)
                            Text(row["image"].orEmpty(), Modifier.weight(1f), style = Theme.type.small)
                            Text(row["ports"].orEmpty(), Modifier.width(220.dp), style = Theme.type.small, color = palette.muted)
                        }
                    }
                }
            }
        }
    }
}

// the logs of the containers of the stack in one, each line with its service, as docker compose logs shows them
@Composable
private fun StackLog(session: DeviceSession, stack: String) {
    val palette = Theme.palette
    val lines = remember(stack) { mutableStateListOf<Item>() }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val filter = remember { TextFieldState() }
    val list = rememberLazyListState()
    LaunchedEffect(stack, attempt) {
        lines.clear()
        problem = null
        try {
            session.run(STACK, "log", mapOf("id" to stack, "tail" to "200")).collect { line ->
                lines += line
                if (lines.size > MAX_STACK_LINES + 1_000) lines.subList(0, lines.size - MAX_STACK_LINES).clear()
            }
        } catch (e: DeviceException) {
            problem = e.message
        }
    }
    val text = filter.text.toString()
    val shown = if (text.isEmpty()) lines else lines.filter { it["message"].orEmpty().contains(text, ignoreCase = true) || it["service"].orEmpty() == text }
    val atEnd = !list.canScrollForward
    LaunchedEffect(shown.size) { if (atEnd && shown.isNotEmpty()) list.scrollToItem(shown.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp)) {
            TextField(filter, Modifier.width(240.dp), placeholder = stringResource(Res.string.docker_log_filter))
        }
        problem?.let { Problem(it) { attempt++ } }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = list) {
                items(shown.size) { index ->
                    val line = shown[index]
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(line["time"].orEmpty().take(19).replace('T', ' '), style = Theme.type.mono, color = palette.muted)
                        Text(line["service"].orEmpty(), Modifier.width(100.dp), style = Theme.type.mono, color = palette.accent, maxLines = 1)
                        Text(line["message"].orEmpty(), style = Theme.type.mono, color = if (line["stream"] == "stderr") palette.warning else palette.text)
                    }
                }
            }
        }
    }
}

// compose.yaml or .env of the stack in the editor; Compose reads the stack with the change before the file is saved, and the
// compose.yaml of a stack can become a template of the user
@Composable
private fun StackFile(session: DeviceSession, stack: String, part: String) {
    val palette = Theme.palette
    val templates = LocalDockerTemplates.current
    var editor by remember(stack, part) { mutableStateOf<EditorState?>(null) }
    var other by remember(stack) { mutableStateOf("") }
    val controls = remember { EditorControls() }
    val template = remember(stack) { TextFieldState(stack) }
    var saved by remember(stack, part) { mutableStateOf(false) }
    var kept by remember(stack) { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(stack, part, attempt) {
        problem = try {
            val files = session.run(STACK, "config", mapOf("id" to stack)).toList().associateBy { it.id }
            editor = editor(files[part]?.get("text").orEmpty(), if (part == "compose") Language.Yaml else Language.Config)
            other = files[if (part == "compose") "env" else "compose"]?.get("text").orEmpty()
            null
        } catch (e: DeviceException) {
            e.message
        }
    }
    fun act(step: suspend () -> Unit) = scope.launch {
        running = true
        saved = false
        kept = null
        problem = try {
            step()
            null
        } catch (e: DeviceException) {
            e.message
        } catch (e: IllegalStateException) {
            e.message
        } finally {
            running = false
        }
    }
    val shown = editor
    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Button(stringResource(Res.string.docker_save), {
                act {
                    session.run(STACK, "save-config", mapOf("id" to stack, part to (shown ?: return@act).snapshot())).toList()
                    saved = true
                }
            }, Modifier.testTag("docker-stack-save"), enabled = !running && shown != null, primary = true)
            Button(stringResource(Res.string.docker_refresh), { attempt++ }, enabled = !running)
            if (part == "compose" && templates != null) {
                TextField(template, Modifier.width(180.dp), mono = true)
                Button(stringResource(Res.string.docker_stack_save_template), {
                    act {
                        val title = template.text.toString().trim()
                        templates.put(StackTemplate(title, (shown ?: return@act).snapshot(), envNames(other)))
                        kept = title
                    }
                }, enabled = !running && shown != null && template.text.isNotBlank())
            }
        }
        problem?.let { Text(deviceMessage(it), Modifier.padding(horizontal = 12.dp), color = palette.danger, style = Theme.type.small) }
        if (saved) Text(stringResource(Res.string.docker_stack_saved), Modifier.padding(horizontal = 12.dp), color = palette.success, style = Theme.type.small)
        kept?.let { Text(stringResource(Res.string.docker_stack_template_saved, it), Modifier.padding(horizontal = 12.dp), color = palette.success, style = Theme.type.small) }
        if (shown == null) {
            Text(stringResource(Res.string.window_loading), Modifier.padding(12.dp), color = palette.muted, style = Theme.type.small)
        } else {
            TextEditor(shown, controls, readOnly = false, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}

private const val MAX_STACK_LINES = 20_000

private val NO_TEMPLATES: StateFlow<List<StackTemplate>> = MutableStateFlow(emptyList())
