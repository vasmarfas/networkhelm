package com.vasmarfas.networkhelm.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.appVersion
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.qr.QrCode
import com.vasmarfas.networkhelm.sync.Cloud
import com.vasmarfas.networkhelm.sync.DeviceContent
import com.vasmarfas.networkhelm.sync.Dictionaries
import com.vasmarfas.networkhelm.sync.GitHost
import com.vasmarfas.networkhelm.sync.KeyChangedException
import com.vasmarfas.networkhelm.sync.SECRET
import com.vasmarfas.networkhelm.sync.Sodium
import com.vasmarfas.networkhelm.sync.Strength
import com.vasmarfas.networkhelm.sync.SyncStatus
import com.vasmarfas.networkhelm.sync.Transfer
import com.vasmarfas.networkhelm.sync.Weakness
import com.vasmarfas.networkhelm.sync.passphrase
import com.vasmarfas.networkhelm.sync.secretKeyOf
import com.vasmarfas.networkhelm.sync.secretKeyText
import com.vasmarfas.networkhelm.sync.strength
import com.vasmarfas.networkhelm.sync.thisYear
import com.vasmarfas.networkhelm.sync.transfer
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.IconButton
import com.vasmarfas.networkhelm.ui.kit.Labeled
import com.vasmarfas.networkhelm.ui.kit.QrImage
import com.vasmarfas.networkhelm.ui.kit.ScrollArea
import com.vasmarfas.networkhelm.ui.kit.SecretField
import com.vasmarfas.networkhelm.ui.kit.Select
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.kit.Toggle
import com.vasmarfas.networkhelm.ui.kit.dialogPanel
import com.vasmarfas.networkhelm.ui.kit.scrim
import com.vasmarfas.networkhelm.ui.main.deviceMessage
import com.vasmarfas.networkhelm.ui.theme.Theme
import eu.anifantakis.lib.ksafe.KSafeProtectionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.settings_close
import networkhelm.app.shared.generated.resources.sync_access_key
import networkhelm.app.shared.generated.resources.sync_access_secret
import networkhelm.app.shared.generated.resources.sync_add
import networkhelm.app.shared.generated.resources.sync_add_place
import networkhelm.app.shared.generated.resources.sync_add_place_hint
import networkhelm.app.shared.generated.resources.sync_address
import networkhelm.app.shared.generated.resources.sync_api
import networkhelm.app.shared.generated.resources.sync_app_password
import networkhelm.app.shared.generated.resources.sync_back
import networkhelm.app.shared.generated.resources.sync_branch
import networkhelm.app.shared.generated.resources.sync_bucket
import networkhelm.app.shared.generated.resources.sync_cancel
import networkhelm.app.shared.generated.resources.sync_change_password
import networkhelm.app.shared.generated.resources.sync_change_password_button
import networkhelm.app.shared.generated.resources.sync_change_password_hint
import networkhelm.app.shared.generated.resources.sync_change_service
import networkhelm.app.shared.generated.resources.sync_change_service_hint
import networkhelm.app.shared.generated.resources.sync_code
import networkhelm.app.shared.generated.resources.sync_code_check
import networkhelm.app.shared.generated.resources.sync_code_check_empty
import networkhelm.app.shared.generated.resources.sync_code_hint
import networkhelm.app.shared.generated.resources.sync_code_shown
import networkhelm.app.shared.generated.resources.sync_create
import networkhelm.app.shared.generated.resources.sync_current_password
import networkhelm.app.shared.generated.resources.sync_device
import networkhelm.app.shared.generated.resources.sync_device_about
import networkhelm.app.shared.generated.resources.sync_device_seen
import networkhelm.app.shared.generated.resources.sync_device_unseen
import networkhelm.app.shared.generated.resources.sync_devices
import networkhelm.app.shared.generated.resources.sync_disable
import networkhelm.app.shared.generated.resources.sync_disable_button
import networkhelm.app.shared.generated.resources.sync_disable_hint
import networkhelm.app.shared.generated.resources.sync_dispute_here
import networkhelm.app.shared.generated.resources.sync_dispute_keep
import networkhelm.app.shared.generated.resources.sync_dispute_take
import networkhelm.app.shared.generated.resources.sync_dispute_there
import networkhelm.app.shared.generated.resources.sync_disputes
import networkhelm.app.shared.generated.resources.sync_disputes_hint
import networkhelm.app.shared.generated.resources.sync_done_at
import networkhelm.app.shared.generated.resources.sync_extra
import networkhelm.app.shared.generated.resources.sync_extra_hint
import networkhelm.app.shared.generated.resources.sync_failed_at
import networkhelm.app.shared.generated.resources.sync_forgot
import networkhelm.app.shared.generated.resources.sync_forgot_button
import networkhelm.app.shared.generated.resources.sync_forgot_hint
import networkhelm.app.shared.generated.resources.sync_gateway_code
import networkhelm.app.shared.generated.resources.sync_gateway_password
import networkhelm.app.shared.generated.resources.sync_git_host
import networkhelm.app.shared.generated.resources.sync_have_code
import networkhelm.app.shared.generated.resources.sync_hide
import networkhelm.app.shared.generated.resources.sync_hint_browser
import networkhelm.app.shared.generated.resources.sync_hint_browser_gateway
import networkhelm.app.shared.generated.resources.sync_hint_folder
import networkhelm.app.shared.generated.resources.sync_hint_gateway
import networkhelm.app.shared.generated.resources.sync_hint_git
import networkhelm.app.shared.generated.resources.sync_hint_s3
import networkhelm.app.shared.generated.resources.sync_hint_webdav
import networkhelm.app.shared.generated.resources.sync_intro
import networkhelm.app.shared.generated.resources.sync_intro_quiet
import networkhelm.app.shared.generated.resources.sync_join
import networkhelm.app.shared.generated.resources.sync_join_button
import networkhelm.app.shared.generated.resources.sync_join_code_hint
import networkhelm.app.shared.generated.resources.sync_join_hint
import networkhelm.app.shared.generated.resources.sync_keys_software
import networkhelm.app.shared.generated.resources.sync_keys_unavailable
import networkhelm.app.shared.generated.resources.sync_kind_docker
import networkhelm.app.shared.generated.resources.sync_kind_fingerprint
import networkhelm.app.shared.generated.resources.sync_kind_router
import networkhelm.app.shared.generated.resources.sync_kind_setting
import networkhelm.app.shared.generated.resources.sync_kind_snippet
import networkhelm.app.shared.generated.resources.sync_kind_theme
import networkhelm.app.shared.generated.resources.sync_kind_tunnel
import networkhelm.app.shared.generated.resources.sync_kind_workspace
import networkhelm.app.shared.generated.resources.sync_kinds
import networkhelm.app.shared.generated.resources.sync_made_key
import networkhelm.app.shared.generated.resources.sync_made_key_done
import networkhelm.app.shared.generated.resources.sync_made_key_hint
import networkhelm.app.shared.generated.resources.sync_make_phrase
import networkhelm.app.shared.generated.resources.sync_move
import networkhelm.app.shared.generated.resources.sync_new_key_too
import networkhelm.app.shared.generated.resources.sync_new_key_too_hint
import networkhelm.app.shared.generated.resources.sync_new_password
import networkhelm.app.shared.generated.resources.sync_new_vault
import networkhelm.app.shared.generated.resources.sync_new_vault_hint
import networkhelm.app.shared.generated.resources.sync_next
import networkhelm.app.shared.generated.resources.sync_no_folder
import networkhelm.app.shared.generated.resources.sync_now
import networkhelm.app.shared.generated.resources.sync_on
import networkhelm.app.shared.generated.resources.sync_open
import networkhelm.app.shared.generated.resources.sync_other_copy
import networkhelm.app.shared.generated.resources.sync_owner
import networkhelm.app.shared.generated.resources.sync_owner_check
import networkhelm.app.shared.generated.resources.sync_password
import networkhelm.app.shared.generated.resources.sync_passwords_differ
import networkhelm.app.shared.generated.resources.sync_phrase
import networkhelm.app.shared.generated.resources.sync_pick_folder
import networkhelm.app.shared.generated.resources.sync_place_failed
import networkhelm.app.shared.generated.resources.sync_places
import networkhelm.app.shared.generated.resources.sync_places_hint
import networkhelm.app.shared.generated.resources.sync_preset
import networkhelm.app.shared.generated.resources.sync_preset_mailru
import networkhelm.app.shared.generated.resources.sync_preset_nextcloud
import networkhelm.app.shared.generated.resources.sync_preset_other
import networkhelm.app.shared.generated.resources.sync_preset_yandex
import networkhelm.app.shared.generated.resources.sync_reaching
import networkhelm.app.shared.generated.resources.sync_refused
import networkhelm.app.shared.generated.resources.sync_region
import networkhelm.app.shared.generated.resources.sync_remove_place
import networkhelm.app.shared.generated.resources.sync_remove_vault
import networkhelm.app.shared.generated.resources.sync_remove_vault_hint
import networkhelm.app.shared.generated.resources.sync_rename
import networkhelm.app.shared.generated.resources.sync_repeat
import networkhelm.app.shared.generated.resources.sync_repository
import networkhelm.app.shared.generated.resources.sync_running
import networkhelm.app.shared.generated.resources.sync_save
import networkhelm.app.shared.generated.resources.sync_scan
import networkhelm.app.shared.generated.resources.sync_secret_key
import networkhelm.app.shared.generated.resources.sync_secret_key_wrong
import networkhelm.app.shared.generated.resources.sync_secrets
import networkhelm.app.shared.generated.resources.sync_secrets_hint
import networkhelm.app.shared.generated.resources.sync_service
import networkhelm.app.shared.generated.resources.sync_service_folder
import networkhelm.app.shared.generated.resources.sync_service_gateway
import networkhelm.app.shared.generated.resources.sync_service_git
import networkhelm.app.shared.generated.resources.sync_service_s3
import networkhelm.app.shared.generated.resources.sync_service_webdav
import networkhelm.app.shared.generated.resources.sync_show
import networkhelm.app.shared.generated.resources.sync_show_code
import networkhelm.app.shared.generated.resources.sync_show_code_hint
import networkhelm.app.shared.generated.resources.sync_strength_ok
import networkhelm.app.shared.generated.resources.sync_this_device
import networkhelm.app.shared.generated.resources.sync_title
import networkhelm.app.shared.generated.resources.sync_token
import networkhelm.app.shared.generated.resources.sync_turn_off
import networkhelm.app.shared.generated.resources.sync_turn_off_button
import networkhelm.app.shared.generated.resources.sync_turn_off_question
import networkhelm.app.shared.generated.resources.sync_turn_on
import networkhelm.app.shared.generated.resources.sync_user
import networkhelm.app.shared.generated.resources.sync_waiting
import networkhelm.app.shared.generated.resources.sync_weak_common
import networkhelm.app.shared.generated.resources.sync_weak_date
import networkhelm.app.shared.generated.resources.sync_weak_guessed
import networkhelm.app.shared.generated.resources.sync_weak_keyboard
import networkhelm.app.shared.generated.resources.sync_weak_repeat
import networkhelm.app.shared.generated.resources.sync_weak_sequence
import networkhelm.app.shared.generated.resources.sync_weak_short
import networkhelm.app.shared.generated.resources.sync_weak_word
import networkhelm.app.shared.generated.resources.sync_working
import kotlin.time.Instant

internal enum class ServiceKind(val cloud: Cloud? = null) {
    Folder,
    WebDav,
    S3,
    Git,
    Gateway,
    YandexDisk(Cloud.YandexDisk),
    Dropbox(Cloud.Dropbox),
    GoogleDrive(Cloud.GoogleDrive),
    OneDrive(Cloud.OneDrive),
    CloudKit(Cloud.CloudKit),
}

private enum class Step { Intro, Service, Code, Create, Join }

private val KindNames = listOf(
    "router" to Res.string.sync_kind_router,
    "fingerprint" to Res.string.sync_kind_fingerprint,
    "setting" to Res.string.sync_kind_setting,
    "theme" to Res.string.sync_kind_theme,
    "snippet" to Res.string.sync_kind_snippet,
    "docker-template" to Res.string.sync_kind_docker,
    "workspace" to Res.string.sync_kind_workspace,
    "tunnel" to Res.string.sync_kind_tunnel,
)

private val GitApis = mapOf(GitHost.GitHub to "https://api.github.com", GitHost.GitLab to "https://gitlab.com/api/v4", GitHost.Gitea to "")

private class Preset(val name: StringResource, val address: String)

// the WebDAV of Nextcloud lies under the server and the user, which the user puts in place of these
private val WebDavPresets = listOf(
    Preset(Res.string.sync_preset_yandex, "https://webdav.yandex.ru"),
    Preset(Res.string.sync_preset_mailru, "https://webdav.cloud.mail.ru"),
    Preset(Res.string.sync_preset_nextcloud, "https://cloud.example.org/remote.php/dav/files/user/"),
    Preset(Res.string.sync_preset_other, ""),
)

private const val CODE_SECONDS = 60

@Composable
internal fun SyncDialog(
    synchronisation: Synchronisation,
    onClose: () -> Unit,
    wizard: () -> SyncWizard = { synchronisation.wizard() },
    pickFolder: suspend () -> PickedFolder? = ::pickSyncFolder,
) {
    val palette = Theme.palette
    val setup by synchronisation.setup.collectAsState()
    // the secret key a new vault was made with, shown until the user wrote it down
    var madeKey by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().scrim(palette.workspace.copy(alpha = 0.6f), onClose), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 640.dp).padding(16.dp).dialogPanel(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.sync_title), Modifier.weight(1f), style = Theme.type.strong)
                IconButton(Lucide.X, stringResource(Res.string.settings_close), onClose, Modifier.testTag("sync-cross"))
            }
            ScrollArea(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val current = setup) {
                    null -> Wizard(remember(synchronisation) { WizardState(wizard(), pickFolder) }) { madeKey = it }
                    else -> Running(synchronisation, current, wizard, pickFolder, madeKey, { madeKey = it }) { madeKey = null }
                }
            }
            Row(Modifier.align(Alignment.End)) { Button(stringResource(Res.string.settings_close), onClose, primary = true) }
        }
    }
}

@Stable
private class WizardState(val wizard: SyncWizard, val pickFolder: suspend () -> PickedFolder?) {
    var step by mutableStateOf(Step.Intro)
    var kind by mutableStateOf(if (syncFolders) ServiceKind.Folder else ServiceKind.WebDav)
    var folder by mutableStateOf<PickedFolder?>(null)
    var preset by mutableStateOf(WebDavPresets.last())
    val address = TextFieldState()
    val user = TextFieldState()
    val secret = TextFieldState()
    val bucket = TextFieldState()
    val region = TextFieldState("us-east-1")
    val access = TextFieldState()
    var host by mutableStateOf(GitHost.GitHub)
    val api = TextFieldState(GitApis.getValue(GitHost.GitHub))
    val owner = TextFieldState()
    val repository = TextFieldState("networkhelm-sync")
    val branch = TextFieldState("main")
    val code = TextFieldState()
    val shown = TextFieldState()
    val password = TextFieldState()
    val repeat = TextFieldState()
    val secretKey = TextFieldState()
    val name = TextFieldState()
    var extra by mutableStateOf(false)
    var secrets by mutableStateOf(false)
    var leftOut by mutableStateOf(emptySet<String>())
    var reached by mutableStateOf<Reached?>(null)
    // the code another device showed, when the device joins with it, and the devices of the vault it opens
    var moved by mutableStateOf<Transfer?>(null)
    var names by mutableStateOf(emptyList<String>())
    var busy by mutableStateOf(false)
    var problem by mutableStateOf<String?>(null)
    var scanning by mutableStateOf(false)
    // the cloud this device signed in to in the wizard, with its tokens
    var signedIn by mutableStateOf<Pair<Cloud, String>?>(null)

    // libsodium that does not load on this computer fails with an Error, which the user is told of rather than the app falling
    fun act(scope: CoroutineScope, action: suspend () -> Unit) {
        busy = true
        problem = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                problem = e.message ?: e::class.simpleName
            } finally {
                busy = false
            }
        }
    }

    fun text(state: TextFieldState) = state.text.toString().trim()

    // the service as the fields describe it, with the password, key or token of its login; null while a field it needs is empty
    fun settings(): Pair<ServiceSettings, String>? {
        val needed = when (kind) {
            ServiceKind.Folder -> return folder?.let { ServiceSettings.Place(it.place, it.name) to "" }
            ServiceKind.WebDav -> listOf(address, user, secret)
            ServiceKind.S3 -> listOf(address, bucket, region, access, secret)
            ServiceKind.Git -> listOf(api, owner, repository, branch, secret)
            ServiceKind.Gateway -> listOf(address, user, secret)
            ServiceKind.YandexDisk, ServiceKind.Dropbox, ServiceKind.GoogleDrive, ServiceKind.OneDrive, ServiceKind.CloudKit ->
                return signedIn?.takeIf { it.first == kind.cloud }?.let { (cloud, tokens) -> ServiceSettings.Account(cloud) to tokens }
        }
        if (needed.any { text(it).isEmpty() }) return null
        return when (kind) {
            ServiceKind.Folder -> null
            ServiceKind.WebDav -> ServiceSettings.WebDav(text(address), text(user)) to text(secret)
            ServiceKind.S3 -> ServiceSettings.S3(text(address), text(bucket), text(region), text(access)) to text(secret)
            ServiceKind.Git -> ServiceSettings.Git(host, text(api), text(owner), text(repository), text(branch)) to text(secret)
            ServiceKind.Gateway -> ServiceSettings.Gateway(text(address)) to text(secret)
            ServiceKind.YandexDisk, ServiceKind.Dropbox, ServiceKind.GoogleDrive, ServiceKind.OneDrive, ServiceKind.CloudKit -> null
        }
    }

    // a vault on the service is joined, an empty service takes a new one; a device that came with a code joins with it
    suspend fun reach() {
        val reached = service() ?: return
        val brought = moved
        if (brought != null) {
            if (reached.vault == null) throw gone()
            names = wizard.devices(reached, brought)
        }
        this.reached = reached
        step = if (reached.vault == null) Step.Create else Step.Join
    }

    // a code without a service is of a folder, which this device picks itself
    suspend fun useCode() {
        val brought = transfer(text(shown))
        moved = brought
        val service = brought.service
        if (service == null) {
            kind = ServiceKind.Folder
            step = Step.Service
            return
        }
        val (settings, secret) = serviceOf(service)
        // a cloud is signed in to on this device itself
        if (settings is ServiceSettings.Account) {
            kind = ServiceKind.entries.first { it.cloud == settings.cloud }
            step = Step.Service
            return
        }
        val reached = wizard.reach(settings, secret)
        if (reached.vault == null) throw gone()
        names = wizard.devices(reached, brought)
        this.reached = reached
        step = Step.Join
    }

    // the vault of this device goes to the service the fields describe
    suspend fun move() {
        wizard.move(service() ?: return)
    }

    suspend fun addPlace() {
        wizard.addPlace(service() ?: return)
    }

    suspend fun signIn(open: (String) -> Unit, page: String) {
        val cloud = kind.cloud ?: return
        signedIn = cloud to wizard.cloudLogin(cloud, open, page)
    }

    fun back(to: Step) {
        problem = null
        if (to == Step.Intro) moved = null
        step = to
    }

    // the gateway takes the login of its page and gives the device a token of its own
    private suspend fun service(): Reached? {
        val (settings, secret) = settings() ?: return null
        val login = if (settings is ServiceSettings.Gateway) wizard.gatewayLogin(settings.address, text(user), secret, text(code)) else secret
        return wizard.reach(settings, login)
    }

    suspend fun create(onMade: (String) -> Unit) {
        val reached = reached ?: return
        val key = if (extra) Sodium.random(SECRET) else null
        wizard.create(reached, password.text.toString(), key, choice())
        key?.let { onMade(secretKeyText(it)) }
    }

    suspend fun join() {
        val reached = reached ?: return
        val brought = moved
        if (brought != null) {
            wizard.join(reached, brought, choice())
        } else {
            wizard.join(reached, password.text.toString(), secretKeyOf(text(secretKey)), choice())
        }
    }

    private fun choice() = Choice(text(name).ifEmpty { syncPlatform }, leftOut, secrets)

    private fun gone() = DeviceException(ErrorCategory.MissingItem, "the vault is no longer on the service")
}

@Composable
private fun Wizard(state: WizardState, onMade: (String) -> Unit) {
    val palette = Theme.palette
    val scope = rememberCoroutineScope()
    when (state.step) {
        Step.Intro -> {
            Text(stringResource(Res.string.sync_intro))
            Text(stringResource(Res.string.sync_intro_quiet), color = palette.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(stringResource(Res.string.sync_turn_on), { state.step = Step.Service }, Modifier.testTag("sync-set-up"), primary = true)
                Button(stringResource(Res.string.sync_have_code), { state.step = Step.Code }, Modifier.testTag("sync-have-code"))
            }
        }
        Step.Service -> ServiceStep(state, stringResource(Res.string.sync_next), { state.back(if (state.moved != null) Step.Code else Step.Intro) }) {
            state.act(scope) { state.reach() }
        }
        Step.Code -> if (state.scanning) {
            CodeScanner { code ->
                state.scanning = false
                state.shown.setTextAndPlaceCursorAtEnd(code)
                state.act(scope) { state.useCode() }
            }
            Button(stringResource(Res.string.sync_cancel), { state.scanning = false })
        } else {
            Labeled(stringResource(Res.string.sync_code)) { TextField(state.shown, Modifier.width(320.dp).testTag("sync-code"), mono = true) }
            Text(stringResource(Res.string.sync_code_hint), color = palette.muted, style = Theme.type.small)
            if (scansCodes) Button(stringResource(Res.string.sync_scan), { state.scanning = true }, Modifier.testTag("sync-scan"))
            Steps(state.busy, stringResource(Res.string.sync_next), state.text(state.shown).isNotEmpty(), { state.back(Step.Intro) }) { state.act(scope) { state.useCode() } }
        }
        Step.Create -> CreateStep(state) { state.act(scope) { state.create(onMade) } }
        Step.Join -> JoinStep(state) { state.act(scope) { state.join() } }
    }
    // a folder is reached at once, and its picker is no wait for the service
    val working = state.step == Step.Create || state.step == Step.Join
    if (state.busy && (working || state.kind != ServiceKind.Folder)) {
        Text(stringResource(if (working) Res.string.sync_working else Res.string.sync_reaching), Modifier.testTag("sync-busy"), color = palette.muted)
    }
    state.problem?.let { Text(deviceMessage(it), Modifier.testTag("sync-problem"), color = palette.danger) }
}

@Composable
private fun Steps(busy: Boolean, next: String, ready: Boolean, onBack: () -> Unit, onNext: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(stringResource(Res.string.sync_back), onBack, enabled = !busy)
        Button(next, onNext, Modifier.testTag("sync-next"), enabled = ready && !busy, primary = true)
    }
}

@Composable
private fun ServiceStep(state: WizardState, next: String, onBack: () -> Unit, onNext: () -> Unit) {
    val palette = Theme.palette
    val scope = rememberCoroutineScope()
    val kinds = (if (syncFolders) ServiceKind.entries else ServiceKind.entries - ServiceKind.Folder).filter { kind -> kind.cloud?.let(::offered) ?: true }
    Labeled(stringResource(Res.string.sync_service)) {
        // a code leaves its own service to pick: a folder, or a cloud to sign in to
        Select(state.kind, if (state.moved != null) listOf(state.kind) else kinds, { stringResource(kindName(it)) }, { state.kind = it }, Modifier.width(260.dp).testTag("sync-kind"))
    }
    Text(stringResource(kindHint(state.kind)), color = palette.muted, style = Theme.type.small)
    when (state.kind) {
        ServiceKind.Folder -> Labeled("") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(stringResource(Res.string.sync_pick_folder), { state.act(scope) { state.pickFolder()?.let { state.folder = it } } }, Modifier.testTag("sync-pick-folder"))
                Text(state.folder?.name ?: stringResource(Res.string.sync_no_folder), color = if (state.folder == null) palette.muted else palette.text)
            }
        }
        ServiceKind.WebDav -> {
            Labeled(stringResource(Res.string.sync_preset)) {
                Select(state.preset, WebDavPresets, { stringResource(it.name) }, { picked ->
                    state.preset = picked
                    state.address.setTextAndPlaceCursorAtEnd(picked.address)
                }, Modifier.width(260.dp).testTag("sync-preset"))
            }
            Field(Res.string.sync_address, state.address, "sync-address")
            Field(Res.string.sync_user, state.user, "sync-user")
            Secret(Res.string.sync_app_password, state.secret, "sync-secret")
        }
        ServiceKind.S3 -> {
            Field(Res.string.sync_address, state.address, "sync-address")
            Field(Res.string.sync_bucket, state.bucket, "sync-bucket")
            Field(Res.string.sync_region, state.region, "sync-region")
            Field(Res.string.sync_access_key, state.access, "sync-access")
            Secret(Res.string.sync_access_secret, state.secret, "sync-secret")
        }
        ServiceKind.Git -> {
            Labeled(stringResource(Res.string.sync_git_host)) {
                Select(state.host, GitHost.entries, { it.name }, { picked ->
                    state.host = picked
                    state.api.setTextAndPlaceCursorAtEnd(GitApis.getValue(picked))
                }, Modifier.width(260.dp))
            }
            Field(Res.string.sync_api, state.api, "sync-api")
            Field(Res.string.sync_owner, state.owner, "sync-owner")
            Field(Res.string.sync_repository, state.repository, "sync-repository")
            Field(Res.string.sync_branch, state.branch, "sync-branch")
            Secret(Res.string.sync_token, state.secret, "sync-secret")
        }
        ServiceKind.Gateway -> {
            Field(Res.string.sync_address, state.address, "sync-address")
            Field(Res.string.sync_user, state.user, "sync-user")
            Secret(Res.string.sync_gateway_password, state.secret, "sync-secret")
            Field(Res.string.sync_gateway_code, state.code, "sync-gateway-code")
        }
        ServiceKind.YandexDisk, ServiceKind.Dropbox, ServiceKind.GoogleDrive, ServiceKind.OneDrive, ServiceKind.CloudKit -> state.kind.cloud?.let { cloud ->
            CloudSignIn(cloud, state.signedIn?.first == cloud, state.busy) { open, page -> state.act(scope) { state.signIn(open, page) } }
        }
    }
    if (!syncFolders && (state.kind == ServiceKind.WebDav || state.kind == ServiceKind.S3)) Text(stringResource(Res.string.sync_hint_browser), color = palette.warning, style = Theme.type.small)
    if (!syncFolders && state.kind == ServiceKind.Gateway) Text(stringResource(Res.string.sync_hint_browser_gateway), color = palette.warning, style = Theme.type.small)
    Steps(state.busy, next, state.settings() != null, onBack, onNext)
}

@Composable
private fun CreateStep(state: WizardState, onCreate: () -> Unit) {
    val palette = Theme.palette
    val scope = rememberCoroutineScope()
    Text(stringResource(Res.string.sync_new_vault), style = Theme.type.strong)
    Text(stringResource(Res.string.sync_new_vault_hint), color = palette.muted)
    val taken = NewPassword(state.password, state.repeat) { action -> state.act(scope, action) }
    Toggle(state.extra, stringResource(Res.string.sync_extra), stringResource(Res.string.sync_extra_hint), "sync-extra") { state.extra = it }
    Options(state)
    Steps(state.busy, stringResource(Res.string.sync_create), taken, { state.back(Step.Service) }, onCreate)
}

// a new password with its strength and a phrase the generator makes up; true once the vault takes it and the repeat matches. The lists
// are read only when a new password is asked for: a page in a browser fetches them then
@Composable
private fun NewPassword(password: TextFieldState, repeat: TextFieldState, act: (suspend () -> Unit) -> Unit): Boolean {
    val palette = Theme.palette
    val dictionaries by produceState<Dictionaries?>(null) { value = syncDictionaries() }
    var phrase by remember(password) { mutableStateOf<String?>(null) }
    val typed = password.text.toString()
    val strength = remember(typed, dictionaries) { dictionaries?.let { strength(typed, it, thisYear()) } }
    val differ = repeat.text.isNotEmpty() && repeat.text.toString() != typed
    Labeled(stringResource(Res.string.sync_password)) { SecretField(password, Modifier.width(320.dp).testTag("sync-password")) }
    Labeled(stringResource(Res.string.sync_repeat)) { SecretField(repeat, Modifier.width(320.dp).testTag("sync-repeat")) }
    Labeled("") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (typed.isNotEmpty()) StrengthLine(strength)
            if (differ) Text(stringResource(Res.string.sync_passwords_differ), Modifier.testTag("sync-differ"), color = palette.danger, style = Theme.type.small)
            Button(stringResource(Res.string.sync_make_phrase), {
                dictionaries?.let { lists ->
                    act {
                        val made = passphrase(lists)
                        password.setTextAndPlaceCursorAtEnd(made)
                        repeat.setTextAndPlaceCursorAtEnd(made)
                        phrase = made
                    }
                }
            }, Modifier.testTag("sync-make-phrase"), enabled = dictionaries != null)
            phrase?.takeIf { it == typed }?.let { made ->
                Text(stringResource(Res.string.sync_phrase), color = palette.muted, style = Theme.type.small)
                SelectionContainer { Text(made, Modifier.testTag("sync-phrase"), style = Theme.type.mono) }
            }
        }
    }
    return strength?.accepted == true && repeat.text.toString() == typed
}

@Composable
private fun JoinStep(state: WizardState, onJoin: () -> Unit) {
    val palette = Theme.palette
    val reached = state.reached ?: return
    Text(stringResource(Res.string.sync_join), style = Theme.type.strong)
    val byCode = state.moved != null
    Text(stringResource(if (byCode) Res.string.sync_join_code_hint else Res.string.sync_join_hint), color = palette.muted)
    // a code from someone else would join this device to a vault of theirs: the service and the devices tell whose it is
    if (byCode) {
        val check = if (state.names.isEmpty()) {
            stringResource(Res.string.sync_code_check_empty, described(reached.settings))
        } else {
            stringResource(Res.string.sync_code_check, described(reached.settings), state.names.joinToString(", "))
        }
        Text(check, Modifier.testTag("sync-code-check"), color = palette.warning)
    }
    val key = state.text(state.secretKey)
    val keyRead = !reached.secretKey || secretKeyOf(key) != null
    if (!byCode) {
        Labeled(stringResource(Res.string.sync_password)) { SecretField(state.password, Modifier.width(320.dp).testTag("sync-password")) }
        if (reached.secretKey) {
            Labeled(stringResource(Res.string.sync_secret_key)) { TextField(state.secretKey, Modifier.width(320.dp).testTag("sync-secret-key"), mono = true, error = key.isNotEmpty() && !keyRead) }
            if (key.isNotEmpty() && !keyRead) Labeled("") { Text(stringResource(Res.string.sync_secret_key_wrong), color = palette.danger, style = Theme.type.small) }
        }
    }
    Options(state)
    val ready = byCode || state.password.text.isNotEmpty() && keyRead
    Steps(state.busy, stringResource(Res.string.sync_join_button), ready, { state.back(if (byCode) Step.Code else Step.Service) }, onJoin)
}

// the name of this device, the kinds of data that go and whether the passwords and keys of the routers go with them
@Composable
private fun Options(state: WizardState) {
    LaunchedEffect(state) { if (state.name.text.isEmpty()) state.name.setTextAndPlaceCursorAtEnd(syncDeviceName()) }
    Labeled(stringResource(Res.string.sync_device)) { TextField(state.name, Modifier.width(320.dp).testTag("sync-device")) }
    Text(stringResource(Res.string.sync_kinds), style = Theme.type.strong)
    for ((kind, title) in KindNames) {
        Toggle(kind !in state.leftOut, stringResource(title), null, "sync-kind-$kind") { on -> state.leftOut = if (on) state.leftOut - kind else state.leftOut + kind }
    }
    Toggle(state.secrets, stringResource(Res.string.sync_secrets), stringResource(Res.string.sync_secrets_hint), "sync-secrets") { state.secrets = it }
    // the keys lie where remembered passwords do, and the connection window tells the same of those
    val protection = remember(state) { state.wizard.protection }
    val weak = when {
        protection == null -> null
        !protection.isEncryptionOperational -> Res.string.sync_keys_unavailable
        protection.effectiveLevel == KSafeProtectionLevel.SOFTWARE -> Res.string.sync_keys_software
        else -> null
    }
    weak?.let { Text(stringResource(it), Modifier.testTag("sync-keys-protection"), color = Theme.palette.warning, style = Theme.type.small) }
}

@Composable
private fun StrengthLine(strength: Strength?) {
    val palette = Theme.palette
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(4) { index ->
            val lit = strength != null && index < strength.score
            val colour = when {
                !lit -> palette.line
                strength.accepted -> palette.success
                else -> palette.warning
            }
            Box(Modifier.size(32.dp, 5.dp).background(colour))
        }
        if (strength != null) {
            Text(
                stringResource(weaknessText(strength)),
                Modifier.padding(start = 6.dp).testTag("sync-strength"),
                color = if (strength.accepted) palette.muted else palette.warning,
                style = Theme.type.small,
            )
        }
    }
}

private fun weaknessText(strength: Strength): StringResource = if (strength.accepted) {
    Res.string.sync_strength_ok
} else {
    when (strength.weakness) {
        Weakness.Short -> Res.string.sync_weak_short
        Weakness.Common -> Res.string.sync_weak_common
        Weakness.Word -> Res.string.sync_weak_word
        Weakness.Keyboard -> Res.string.sync_weak_keyboard
        Weakness.Repeat -> Res.string.sync_weak_repeat
        Weakness.Sequence -> Res.string.sync_weak_sequence
        Weakness.Date -> Res.string.sync_weak_date
        null -> Res.string.sync_weak_guessed
    }
}

private fun kindName(kind: ServiceKind) = when (kind) {
    ServiceKind.Folder -> Res.string.sync_service_folder
    ServiceKind.WebDav -> Res.string.sync_service_webdav
    ServiceKind.S3 -> Res.string.sync_service_s3
    ServiceKind.Git -> Res.string.sync_service_git
    ServiceKind.Gateway -> Res.string.sync_service_gateway
    ServiceKind.YandexDisk -> cloudName(Cloud.YandexDisk)
    ServiceKind.Dropbox -> cloudName(Cloud.Dropbox)
    ServiceKind.GoogleDrive -> cloudName(Cloud.GoogleDrive)
    ServiceKind.OneDrive -> cloudName(Cloud.OneDrive)
    ServiceKind.CloudKit -> cloudName(Cloud.CloudKit)
}

private fun kindHint(kind: ServiceKind) = when (kind) {
    ServiceKind.Folder -> Res.string.sync_hint_folder
    ServiceKind.WebDav -> Res.string.sync_hint_webdav
    ServiceKind.S3 -> Res.string.sync_hint_s3
    ServiceKind.Git -> Res.string.sync_hint_git
    ServiceKind.Gateway -> Res.string.sync_hint_gateway
    ServiceKind.YandexDisk -> cloudHint(Cloud.YandexDisk)
    ServiceKind.Dropbox -> cloudHint(Cloud.Dropbox)
    ServiceKind.GoogleDrive -> cloudHint(Cloud.GoogleDrive)
    ServiceKind.OneDrive -> cloudHint(Cloud.OneDrive)
    ServiceKind.CloudKit -> cloudHint(Cloud.CloudKit)
}

@Composable
private fun Field(label: StringResource, state: TextFieldState, tag: String) {
    Labeled(stringResource(label)) { TextField(state, Modifier.width(320.dp).testTag(tag)) }
}

@Composable
private fun Secret(label: StringResource, state: TextFieldState, tag: String) {
    Labeled(stringResource(label)) { SecretField(state, Modifier.width(320.dp).testTag(tag)) }
}

private sealed interface Panel {
    data object Code : Panel

    data object Password : Panel

    data object Move : Panel

    data object Add : Panel

    data object Off : Panel

    class Disable(val device: String, val name: String) : Panel
}

// a device that synchronises: how the last round went, what the user does with the vault, one thing at a time, the devices of the vault,
// the kinds that go and the fingerprints in dispute
@Composable
private fun Running(
    synchronisation: Synchronisation,
    setup: SyncSetup,
    wizard: () -> SyncWizard,
    pickFolder: suspend () -> PickedFolder?,
    madeKey: String?,
    onMade: (String) -> Unit,
    onKeyNoted: () -> Unit,
) {
    val palette = Theme.palette
    val scope = rememberCoroutineScope()
    val rounds by synchronisation.running.collectAsState()
    val trouble by synchronisation.trouble.collectAsState()
    val held by synchronisation.held.collectAsState()
    val disputes by synchronisation.disputes.collectAsState()
    val status = rounds?.let { it.status.collectAsState().value }
    // the devices as the last round read them
    val devices by produceState(emptyMap<String, DeviceContent>(), status) { value = synchronisation.devices() }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    fun act(action: suspend () -> Unit) {
        problem = null
        busy = true
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                problem = e.message ?: e::class.simpleName
            } finally {
                busy = false
            }
        }
    }
    Text(stringResource(Res.string.sync_on, setup.places.map { described(it.service) }.joinToString(", ")))
    val line = when {
        !held -> stringResource(Res.string.sync_other_copy)
        trouble != null -> deviceMessage(trouble?.message.orEmpty())
        status is SyncStatus.Done -> stringResource(Res.string.sync_done_at, clock(status.at))
        status is SyncStatus.Failed -> stringResource(Res.string.sync_failed_at, clock(status.at), deviceMessage(status.error.message ?: status.error::class.simpleName.orEmpty()))
        status == SyncStatus.Running -> stringResource(Res.string.sync_running)
        else -> stringResource(Res.string.sync_waiting)
    }
    val failed = trouble != null || status is SyncStatus.Failed
    Text(line, Modifier.testTag("sync-status"), color = if (failed) palette.danger else palette.muted)
    if ((status as? SyncStatus.Failed)?.error is KeyChangedException) {
        val password = remember { TextFieldState() }
        val secretKey = remember { TextFieldState() }
        // the device that changed the key made a new secret key as well, where the vault has one
        val asks by produceState(false) { value = wizard().asksSecretKey() }
        val typed = secretKey.text.toString().trim()
        val keyRead = typed.isEmpty() || secretKeyOf(typed) != null
        Labeled(stringResource(Res.string.sync_new_password)) { SecretField(password, Modifier.width(320.dp).testTag("sync-new-key")) }
        if (asks) Labeled(stringResource(Res.string.sync_secret_key)) { TextField(secretKey, Modifier.width(320.dp).testTag("sync-new-secret-key"), mono = true, error = !keyRead) }
        Labeled("") {
            Button(stringResource(Res.string.sync_open), { act { wizard().newKey(password.text.toString(), secretKeyOf(typed)) } }, Modifier.testTag("sync-open"), enabled = password.text.isNotEmpty() && keyRead && !busy, primary = true)
        }
    }
    // places the last round did not get through while others went through
    (status as? SyncStatus.Done)?.round?.failed?.forEach { (id, error) ->
        val place = setup.places.firstOrNull { it.id == id } ?: return@forEach
        Text(stringResource(Res.string.sync_place_failed, described(place.service), deviceMessage(error.message.orEmpty())), Modifier.testTag("sync-place-failed"), color = palette.warning, style = Theme.type.small)
    }
    // files a round did not take: given back by the service from before, written with an earlier key, changed on the way
    (status as? SyncStatus.Done)?.round?.refused?.forEach { (path, error) ->
        val owner = devices[path.substringAfter('/')]?.name ?: path
        Text(stringResource(Res.string.sync_refused, owner, deviceMessage(error.message.orEmpty())), Modifier.testTag("sync-refused"), color = palette.warning, style = Theme.type.small)
    }
    madeKey?.let { key ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.sync_made_key), style = Theme.type.strong)
            SelectionContainer { Text(key, Modifier.testTag("sync-made-key"), style = Theme.type.mono) }
            Text(stringResource(Res.string.sync_made_key_hint), color = palette.muted, style = Theme.type.small)
            Button(stringResource(Res.string.sync_made_key_done), onKeyNoted, Modifier.testTag("sync-key-noted"))
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(stringResource(Res.string.sync_now), synchronisation::now, Modifier.testTag("sync-now"), enabled = rounds != null)
        Button(stringResource(Res.string.sync_show_code), { panel = Panel.Code }, Modifier.testTag("sync-show-code"))
        Button(stringResource(Res.string.sync_change_password), { panel = Panel.Password }, Modifier.testTag("sync-change-password"))
        Button(stringResource(Res.string.sync_change_service), { panel = Panel.Move }, Modifier.testTag("sync-change-service"))
        Button(stringResource(Res.string.sync_add_place), { panel = Panel.Add }, Modifier.testTag("sync-add-place"))
        Button(stringResource(Res.string.sync_turn_off), { panel = Panel.Off }, Modifier.testTag("sync-turn-off"))
    }
    val close = { panel = null }
    when (val shown = panel) {
        Panel.Code -> CodePanel(wizard, ::act, busy, close)
        Panel.Password -> PasswordPanel(wizard, ::act, busy, onMade, close)
        Panel.Move -> ServicePanel(wizard, pickFolder, Res.string.sync_change_service_hint, Res.string.sync_move, close) { move() }
        Panel.Add -> ServicePanel(wizard, pickFolder, Res.string.sync_add_place_hint, Res.string.sync_add, close) { addPlace() }
        Panel.Off -> {
            var remove by remember { mutableStateOf(false) }
            Text(stringResource(Res.string.sync_turn_off_question))
            Toggle(remove, stringResource(Res.string.sync_remove_vault), stringResource(Res.string.sync_remove_vault_hint), "sync-remove-vault") { remove = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(stringResource(Res.string.sync_cancel), close)
                Button(stringResource(Res.string.sync_turn_off_button), { act { wizard().forget(remove) } }, Modifier.testTag("sync-turn-off-confirm"), enabled = !busy, danger = true)
            }
        }
        is Panel.Disable -> {
            val current = remember(shown) { TextFieldState() }
            val password = remember(shown) { TextFieldState() }
            val repeat = remember(shown) { TextFieldState() }
            Text(shown.name, style = Theme.type.strong)
            Text(stringResource(Res.string.sync_disable_hint), color = palette.muted)
            Labeled(stringResource(Res.string.sync_current_password)) { SecretField(current, Modifier.width(320.dp).testTag("sync-current")) }
            val taken = NewPassword(password, repeat, ::act)
            Steps(busy, stringResource(Res.string.sync_disable_button), taken && current.text.isNotEmpty(), close) {
                act {
                    wizard().disable(shown.device, current.text.toString(), password.text.toString())?.let(onMade)
                    close()
                }
            }
        }
        null -> Unit
    }
    problem?.let { Text(deviceMessage(it), Modifier.testTag("sync-problem"), color = palette.danger) }
    if (setup.places.size > 1) Places(setup, busy) { id -> act { wizard().removePlace(id) } }
    Devices(setup, devices, busy, { name -> act { wizard().rename(name) } }) { panel = it }
    Text(stringResource(Res.string.sync_kinds), style = Theme.type.strong)
    for ((kind, title) in KindNames) {
        Toggle(kind !in setup.leftOut, stringResource(title), null, "sync-kind-$kind", enabled = !busy) { on ->
            act { wizard().choose(if (on) setup.leftOut - kind else setup.leftOut + kind, setup.secrets) }
        }
    }
    Toggle(setup.secrets, stringResource(Res.string.sync_secrets), stringResource(Res.string.sync_secrets_hint), "sync-secrets", enabled = !busy) { on ->
        act { wizard().choose(setup.leftOut, on) }
    }
    Disputes(disputes) { dispute, take -> act { synchronisation.settle(dispute, take) } }
}

// the code for another device: the password typed again, then the QR code and its text for a minute
@Composable
private fun CodePanel(wizard: () -> SyncWizard, act: (suspend () -> Unit) -> Unit, busy: Boolean, onClose: () -> Unit) {
    val palette = Theme.palette
    val password = remember { TextFieldState() }
    var code by remember { mutableStateOf<String?>(null) }
    val shown = code
    if (shown == null) {
        Text(stringResource(Res.string.sync_show_code_hint), color = palette.muted)
        Labeled(stringResource(Res.string.sync_password)) { SecretField(password, Modifier.width(320.dp).testTag("sync-code-password")) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(stringResource(Res.string.sync_cancel), onClose)
            Button(stringResource(Res.string.sync_show), { act { code = wizard().shownCode(password.text.toString()) } }, Modifier.testTag("sync-show"), enabled = password.text.isNotEmpty() && !busy, primary = true)
        }
        return
    }
    var left by remember(shown) { mutableIntStateOf(CODE_SECONDS) }
    LaunchedEffect(shown) {
        while (left > 0) {
            delay(1000)
            left--
        }
        onClose()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        QrImage(remember(shown) { QrCode.encode(shown) }, Modifier.size(220.dp).testTag("sync-qr"))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.sync_code_shown, left.toString()), color = palette.warning)
            // typed by hand where there is no camera: a copy would stay in the clipboard and its history
            Text(shown, Modifier.testTag("sync-code-text"), style = Theme.type.mono)
            Button(stringResource(Res.string.sync_hide), onClose)
        }
    }
}

// a new password after the current one, with a new key of the data where the user asks for it; a forgotten password is a step of its
// own, which changes the key whatever is picked
@Composable
private fun PasswordPanel(wizard: () -> SyncWizard, act: (suspend () -> Unit) -> Unit, busy: Boolean, onMade: (String) -> Unit, onClose: () -> Unit) {
    val palette = Theme.palette
    val current = remember { TextFieldState() }
    val password = remember { TextFieldState() }
    val repeat = remember { TextFieldState() }
    var newKey by remember { mutableStateOf(false) }
    var forgot by remember { mutableStateOf(false) }
    if (forgot) {
        Text(stringResource(Res.string.sync_forgot_hint), Modifier.testTag("sync-forgot-hint"), color = palette.warning)
    } else {
        Text(stringResource(Res.string.sync_change_password_hint), color = palette.muted)
        Labeled(stringResource(Res.string.sync_current_password)) { SecretField(current, Modifier.width(320.dp).testTag("sync-current")) }
        Labeled("") { Button(stringResource(Res.string.sync_forgot), { forgot = true }, Modifier.testTag("sync-forgot")) }
    }
    val taken = NewPassword(password, repeat, act)
    if (!forgot) Toggle(newKey, stringResource(Res.string.sync_new_key_too), stringResource(Res.string.sync_new_key_too_hint), "sync-new-key-too") { newKey = it }
    val button = if (forgot) Res.string.sync_forgot_button else Res.string.sync_change_password_button
    val owner = rememberOwnerCheck(stringResource(Res.string.sync_owner_check))
    Steps(busy, stringResource(button), taken && (forgot || current.text.isNotEmpty()), onClose) {
        val change = {
            act {
                val made = if (forgot) wizard().forgotPassword(password.text.toString()) else wizard().changePassword(current.text.toString(), password.text.toString(), newKey)
                made?.let(onMade)
                onClose()
            }
        }
        if (forgot) owner(change) else change()
    }
}

// another service for the vault, picked as in the wizard: the vault moves there whole and the device goes on there, or one more copy of
// it goes there
@Composable
private fun ServicePanel(
    wizard: () -> SyncWizard,
    pickFolder: suspend () -> PickedFolder?,
    hint: StringResource,
    button: StringResource,
    onClose: () -> Unit,
    action: suspend WizardState.() -> Unit,
) {
    val palette = Theme.palette
    val scope = rememberCoroutineScope()
    val state = remember { WizardState(wizard(), pickFolder) }
    Text(stringResource(hint), color = palette.muted)
    ServiceStep(state, stringResource(button), onClose) {
        state.act(scope) {
            state.action()
            onClose()
        }
    }
    if (state.busy && state.kind != ServiceKind.Folder) Text(stringResource(Res.string.sync_reaching), color = palette.muted)
    state.problem?.let { Text(deviceMessage(it), Modifier.testTag("sync-move-problem"), color = palette.danger) }
}

// the places of the vault, each of which the device leaves while another one stays
@Composable
private fun Places(setup: SyncSetup, busy: Boolean, onRemove: (String) -> Unit) {
    Text(stringResource(Res.string.sync_places), style = Theme.type.strong)
    Text(stringResource(Res.string.sync_places_hint), color = Theme.palette.muted, style = Theme.type.small)
    for (place in setup.places) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(described(place.service), Modifier.weight(1f))
            Button(stringResource(Res.string.sync_remove_place), { onRemove(place.id) }, Modifier.testTag("sync-remove-place-${place.id}"), enabled = !busy)
        }
    }
}

// this device, which the user renames, and the others as each named itself, which the user turns off
@Composable
private fun Devices(setup: SyncSetup, devices: Map<String, DeviceContent>, busy: Boolean, onRename: (String) -> Unit, onDisable: (Panel.Disable) -> Unit) {
    val palette = Theme.palette
    var renaming by remember { mutableStateOf(false) }
    val name = remember(setup.name) { TextFieldState(setup.name) }
    Text(stringResource(Res.string.sync_devices), style = Theme.type.strong)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.sync_this_device, setup.name))
            Text(stringResource(Res.string.sync_device_about, syncPlatform, appVersion), color = palette.muted, style = Theme.type.small)
        }
        if (!renaming) Button(stringResource(Res.string.sync_rename), { renaming = true }, Modifier.testTag("sync-rename"))
    }
    if (renaming) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(name, Modifier.width(260.dp).testTag("sync-name"))
            Button(stringResource(Res.string.sync_save), {
                onRename(name.text.toString().trim())
                renaming = false
            }, Modifier.testTag("sync-save-name"), enabled = name.text.isNotBlank() && !busy, primary = true)
        }
    }
    for ((id, device) in devices.entries.sortedBy { it.value.name }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(device.name)
                val seen = if (device.at > 0) stringResource(Res.string.sync_device_seen, moment(device.at)) else stringResource(Res.string.sync_device_unseen)
                Text("${stringResource(Res.string.sync_device_about, device.platform, device.app)}, $seen", color = palette.muted, style = Theme.type.small)
            }
            Button(stringResource(Res.string.sync_disable), { onDisable(Panel.Disable(id, device.name)) }, Modifier.testTag("sync-disable-$id"), enabled = !busy)
        }
    }
}

// both fingerprints of each address: the user takes the other one, or keeps this one and the question goes to the other devices
@Composable
private fun Disputes(disputes: List<Dispute>, onSettle: (Dispute, Boolean) -> Unit) {
    if (disputes.isEmpty()) return
    val palette = Theme.palette
    Text(stringResource(Res.string.sync_disputes), style = Theme.type.strong)
    Text(stringResource(Res.string.sync_disputes_hint), color = palette.muted, style = Theme.type.small)
    for (dispute in disputes) {
        val here = dispute.here
        Column(Modifier.testTag("sync-dispute"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${here.host}:${here.port}, ${here.kind.name.uppercase()}")
            Text(stringResource(Res.string.sync_dispute_here), color = palette.muted, style = Theme.type.small)
            SelectionContainer { Text(here.fingerprint, style = Theme.type.mono) }
            Text(stringResource(Res.string.sync_dispute_there), color = palette.muted, style = Theme.type.small)
            SelectionContainer { Text(dispute.there, style = Theme.type.mono) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(stringResource(Res.string.sync_dispute_take), { onSettle(dispute, true) }, Modifier.testTag("sync-dispute-take"))
                Button(stringResource(Res.string.sync_dispute_keep), { onSettle(dispute, false) }, Modifier.testTag("sync-dispute-keep"))
            }
        }
    }
}

@Composable
private fun described(service: ServiceSettings) = when (service) {
    is ServiceSettings.Place -> service.name
    is ServiceSettings.WebDav -> service.base
    is ServiceSettings.S3 -> "${service.endpoint}/${service.bucket}"
    is ServiceSettings.Git -> "${service.owner}/${service.repository}"
    is ServiceSettings.Gateway -> service.address
    is ServiceSettings.Account -> stringResource(cloudName(service.cloud))
}

private fun clock(at: Long) = Instant.fromEpochMilliseconds(at).toLocalDateTime(TimeZone.currentSystemDefault()).time.let { "${two(it.hour)}:${two(it.minute)}:${two(it.second)}" }

private fun moment(at: Long) = Instant.fromEpochMilliseconds(at).toLocalDateTime(TimeZone.currentSystemDefault()).let { "${it.date} ${two(it.hour)}:${two(it.minute)}" }

private fun two(value: Int) = value.toString().padStart(2, '0')
