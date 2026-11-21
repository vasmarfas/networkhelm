package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.vasmarfas.networkhelm.ui.chart.byteSize
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.SecretField
import com.vasmarfas.networkhelm.ui.kit.TabStrip
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.docker_history_by
import networkhelm.app.shared.generated.resources.docker_history_created
import networkhelm.app.shared.generated.resources.docker_history_size
import networkhelm.app.shared.generated.resources.docker_load
import networkhelm.app.shared.generated.resources.docker_load_hint
import networkhelm.app.shared.generated.resources.docker_pull
import networkhelm.app.shared.generated.resources.docker_pull_done
import networkhelm.app.shared.generated.resources.docker_pull_image
import networkhelm.app.shared.generated.resources.docker_pull_platform
import networkhelm.app.shared.generated.resources.docker_push
import networkhelm.app.shared.generated.resources.docker_push_done
import networkhelm.app.shared.generated.resources.docker_registry_hint
import networkhelm.app.shared.generated.resources.docker_registry_login
import networkhelm.app.shared.generated.resources.docker_registry_password
import networkhelm.app.shared.generated.resources.docker_save
import networkhelm.app.shared.generated.resources.docker_save_file
import networkhelm.app.shared.generated.resources.docker_save_hint
import networkhelm.app.shared.generated.resources.docker_saved
import networkhelm.app.shared.generated.resources.docker_tab_history
import networkhelm.app.shared.generated.resources.docker_tab_inspect
import networkhelm.app.shared.generated.resources.docker_tab_push
import networkhelm.app.shared.generated.resources.docker_tab_save
import networkhelm.app.shared.generated.resources.docker_tag
import networkhelm.app.shared.generated.resources.docker_tag_done
import networkhelm.app.shared.generated.resources.docker_tag_name
import networkhelm.app.shared.generated.resources.docker_working
import networkhelm.app.shared.generated.resources.window_loading
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val IMAGE = MenuPath.parse("container/image")

// a layer is named by the first twelve digits of its digest; any other ID of a line of progress is the tag the line is about
private val LAYER = Regex("[0-9a-f]{12}")

private val IMAGE_TABS = listOf(
    "history" to Res.string.docker_tab_history,
    "inspect" to Res.string.docker_tab_inspect,
    "push" to Res.string.docker_tab_push,
    "save" to Res.string.docker_tab_save,
)

// one image in a window of its own, opened from its row: the layers it was built of, what the engine tells of it, a new name with
// a push to a registry, and a file of the host it is written to
@Composable
internal fun ImageView(session: DeviceSession, image: String, opened: String) {
    var shown by remember(image) { mutableIntStateOf(IMAGE_TABS.indexOfFirst { it.first == opened }.coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize().testTag("docker-image")) {
        TabStrip(IMAGE_TABS.map { stringResource(it.second) }, shown, { shown = it })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (IMAGE_TABS[shown].first) {
                "history" -> ImageHistory(session, image)
                "inspect" -> DockerInspect(session, IMAGE, image)
                "push" -> ImagePush(session, image)
                else -> ImageSave(session, image)
            }
        }
    }
}

// docker history: a row a layer from the newest, with its size and the instruction that made it
@Composable
private fun ImageHistory(session: DeviceSession, image: String) {
    val palette = Theme.palette
    var layers by remember(image) { mutableStateOf<List<Item>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(image, attempt) {
        problem = try {
            layers = session.run(IMAGE, "history", mapOf("id" to image)).toList()
            null
        } catch (e: DeviceException) {
            e.message
        }
    }
    val shown = layers
    Column(Modifier.fillMaxSize()) {
        problem?.let { Problem(it) { attempt++ } }
        if (shown == null) {
            Text(stringResource(Res.string.window_loading), Modifier.padding(12.dp), color = palette.muted, style = Theme.type.small)
        } else {
            SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    item {
                        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(Res.string.docker_history_created), Modifier.width(150.dp), style = Theme.type.small, color = palette.muted)
                            Text(stringResource(Res.string.docker_history_size), Modifier.width(80.dp), style = Theme.type.small, color = palette.muted)
                            Text(stringResource(Res.string.docker_history_by), Modifier.weight(1f), style = Theme.type.small, color = palette.muted)
                        }
                    }
                    items(shown.size) { index ->
                        val layer = shown[index]
                        Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(layer["created"].orEmpty(), Modifier.width(150.dp), style = Theme.type.mono, color = palette.muted)
                            Text(layer["size"]?.toDoubleOrNull()?.let(::byteSize).orEmpty(), Modifier.width(80.dp), style = Theme.type.mono)
                            Text(layer["created-by"].orEmpty(), Modifier.weight(1f), style = Theme.type.mono)
                        }
                    }
                }
            }
        }
    }
}

// the lines of a pull or a push: a row a layer that moves, and under them what the engine says of the whole image
private class Progress {
    val layers = mutableStateListOf<Item>()
    val notes = mutableStateListOf<String>()

    fun add(step: Item) {
        val id = step["id"].orEmpty()
        val status = step["status"].orEmpty()
        if (LAYER.matches(id)) {
            val at = layers.indexOfFirst { it["id"] == id }
            if (at < 0) layers += step else layers[at] = step
        } else if (status.isNotEmpty()) {
            notes += if (id.isEmpty()) status else "$id: $status"
        }
    }

    fun clear() {
        layers.clear()
        notes.clear()
    }
}

@Composable
private fun ProgressList(progress: Progress, modifier: Modifier) {
    val palette = Theme.palette
    LazyColumn(modifier.padding(horizontal = 8.dp)) {
        items(progress.layers.size) { index ->
            val layer = progress.layers[index]
            val current = layer["current"]?.toDoubleOrNull()
            val total = layer["total"]?.toDoubleOrNull()?.takeIf { it > 0 }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(layer["id"].orEmpty(), Modifier.width(110.dp), style = Theme.type.mono, color = palette.muted)
                Text(layer["status"].orEmpty(), Modifier.width(150.dp), style = Theme.type.small)
                if (current != null && total != null) Meter((current / total).toFloat(), Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                Text(amount(layer), Modifier.width(160.dp), style = Theme.type.small, color = palette.muted)
            }
        }
        items(progress.notes.size) { index -> Text(progress.notes[index], Modifier.padding(vertical = 2.dp), style = Theme.type.mono) }
    }
}

// the bytes that came of all there are, or the seconds an extraction took, as the engine counts it
private fun amount(layer: Item): String {
    val current = layer["current"]?.toDoubleOrNull() ?: return ""
    val units = layer["units"].orEmpty()
    if (units.isNotEmpty()) return "${layer["current"]} $units"
    val total = layer["total"]?.toDoubleOrNull()?.takeIf { it > 0 }
    return byteSize(current) + total?.let { " / ${byteSize(it)}" }.orEmpty()
}

// the login typed for this pull or push, else the one the protected store keeps for the registry of the image
private suspend fun registryLogin(registries: DockerRegistries?, image: String, login: TextFieldState, password: TextFieldState): Map<String, String> {
    val typed = login.text.toString().trim()
    val kept = if (typed.isEmpty()) registries?.loginFor(image) else null
    return mapOf("username" to (kept?.username ?: typed), "password" to (kept?.password ?: password.text.toString()))
}

// the login of a private registry for one pull or push; it is not kept
@Composable
private fun RegistryLogin(login: TextFieldState, password: TextFieldState) {
    val palette = Theme.palette
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(login, Modifier.width(180.dp).testTag("docker-registry-login"), placeholder = stringResource(Res.string.docker_registry_login))
        Text(stringResource(Res.string.docker_registry_password), color = palette.muted, style = Theme.type.small)
        SecretField(password, Modifier.width(180.dp).testTag("docker-registry-password"))
        Text(stringResource(Res.string.docker_registry_hint), color = palette.muted, style = Theme.type.small)
    }
}

// docker pull with every layer as it moves
@Composable
internal fun ImagePull(session: DeviceSession, image: String) {
    val palette = Theme.palette
    val name = remember { TextFieldState(image) }
    val platform = remember { TextFieldState() }
    val login = remember { TextFieldState() }
    val password = remember { TextFieldState() }
    val progress = remember { Progress() }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val registries = LocalDockerRegistries.current
    fun pull() = scope.launch {
        progress.clear()
        problem = null
        done = false
        running = true
        try {
            val image = name.text.toString().trim()
            val arguments = mapOf("image" to image, "platform" to platform.text.toString().trim()) + registryLogin(registries, image, login, password)
            session.run(IMAGE, "pull", arguments).collect { progress.add(it) }
            done = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = e.message ?: e.toString()
        } finally {
            running = false
        }
    }
    Column(Modifier.fillMaxSize().testTag("docker-pull")) {
        FlowRow(
            Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(name, Modifier.width(320.dp).testTag("docker-pull-image"), placeholder = stringResource(Res.string.docker_pull_image), mono = true)
            TextField(platform, Modifier.width(170.dp), placeholder = stringResource(Res.string.docker_pull_platform), mono = true)
            Button(stringResource(Res.string.docker_pull), { pull() }, Modifier.testTag("docker-pull-start"), enabled = !running && name.text.isNotBlank(), primary = true)
        }
        RegistryLogin(login, password)
        problem?.let { Text(deviceMessage(it), Modifier.padding(horizontal = 12.dp), color = palette.danger, style = Theme.type.small) }
        if (done) Text(stringResource(Res.string.docker_pull_done), Modifier.padding(horizontal = 12.dp), color = palette.success, style = Theme.type.small)
        ProgressList(progress, Modifier.weight(1f).fillMaxWidth())
    }
}

// a new name for the image, as a registry and a repository of it take it, and a push of that name with every layer as it moves
@Composable
private fun ImagePush(session: DeviceSession, image: String) {
    val palette = Theme.palette
    val target = remember { TextFieldState(image) }
    val login = remember { TextFieldState() }
    val password = remember { TextFieldState() }
    val progress = remember { Progress() }
    var running by remember { mutableStateOf(false) }
    var tagged by remember { mutableStateOf<String?>(null) }
    var pushed by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val registries = LocalDockerRegistries.current
    fun act(step: suspend () -> Unit) = scope.launch {
        problem = null
        tagged = null
        pushed = false
        running = true
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = e.message ?: e.toString()
        } finally {
            running = false
        }
    }
    val name = target.text.toString().trim()
    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(target, Modifier.width(360.dp).testTag("docker-push-name"), placeholder = stringResource(Res.string.docker_tag_name), mono = true)
            Button(stringResource(Res.string.docker_tag), {
                act {
                    session.run(IMAGE, "tag", mapOf("id" to image, "reference" to name)).collect()
                    tagged = name
                }
            }, enabled = !running && name.isNotEmpty() && name != image)
            Button(stringResource(Res.string.docker_push), {
                act {
                    progress.clear()
                    val arguments = mapOf("image" to name) + registryLogin(registries, name, login, password)
                    session.run(IMAGE, "push", arguments).collect { progress.add(it) }
                    pushed = true
                }
            }, Modifier.testTag("docker-push-start"), enabled = !running && name.isNotEmpty(), primary = true)
        }
        RegistryLogin(login, password)
        problem?.let { Text(deviceMessage(it), Modifier.padding(horizontal = 12.dp), color = palette.danger, style = Theme.type.small) }
        tagged?.let { Text(stringResource(Res.string.docker_tag_done, it), Modifier.padding(horizontal = 12.dp), color = palette.success, style = Theme.type.small) }
        if (pushed) Text(stringResource(Res.string.docker_push_done), Modifier.padding(horizontal = 12.dp), color = palette.success, style = Theme.type.small)
        ProgressList(progress, Modifier.weight(1f).fillMaxWidth())
    }
}

// docker save to a file of the host, which the window of files of the host takes to this computer
@Composable
private fun ImageSave(session: DeviceSession, image: String) {
    val file = remember(image) { TextFieldState(image.replace('/', '_').replace(':', '_') + ".tar") }
    HostFile(file, Res.string.docker_save, Res.string.docker_save_hint, saved = true) { path ->
        session.run(IMAGE, "save", mapOf("id" to image, "file" to path)).toList().map { it["file"].orEmpty() }
    }
}

// docker load from a file of the host, which the window of files of the host puts there from this computer
@Composable
internal fun ImageLoad(session: DeviceSession) {
    val file = remember { TextFieldState() }
    HostFile(file, Res.string.docker_load, Res.string.docker_load_hint, saved = false) { path ->
        session.run(IMAGE, "load", mapOf("file" to path)).toList().map { it["line"].orEmpty() }
    }
}

// the CLI of the engine on a file of the host, which takes minutes for a large image
@Composable
private fun HostFile(file: TextFieldState, action: StringResource, hint: StringResource, saved: Boolean, command: suspend (String) -> List<String>) {
    val palette = Theme.palette
    var running by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun start() = scope.launch {
        running = true
        lines = emptyList()
        problem = try {
            lines = command(file.text.toString().trim())
            null
        } catch (e: DeviceException) {
            e.message
        } finally {
            running = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth().background(palette.raised).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.docker_save_file), color = palette.muted)
            TextField(file, Modifier.width(360.dp).testTag("docker-host-file"), mono = true)
            Button(stringResource(action), { start() }, Modifier.testTag("docker-host-file-run"), enabled = !running && file.text.isNotBlank(), primary = true)
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(hint), color = palette.muted, style = Theme.type.small)
            if (running) Text(stringResource(Res.string.docker_working), color = palette.muted, style = Theme.type.small)
            problem?.let { Text(deviceMessage(it), color = palette.danger, style = Theme.type.small) }
            SelectionContainer {
                Column {
                    for (line in lines) Text(if (saved) stringResource(Res.string.docker_saved, line) else line, style = Theme.type.mono)
                }
            }
        }
    }
}
