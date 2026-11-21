package com.vasmarfas.networkhelm.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.app.AppSettings
import com.vasmarfas.networkhelm.app.MemorySecrets
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.SavedRouters
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.app.Snippets
import com.vasmarfas.networkhelm.sync.DirectoryFolder
import com.vasmarfas.networkhelm.sync.SYNC_FOLDER
import com.vasmarfas.networkhelm.sync.SyncStatus
import com.vasmarfas.networkhelm.transport.FileTrustStore
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustKind
import com.vasmarfas.networkhelm.transport.TrustStore
import com.vasmarfas.networkhelm.ui.main.DockerTemplates
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct horse battery staple"
private const val FOLDER = "networkhelm-test"

@OptIn(ExperimentalTestApi::class)
class SyncDialogTest {
    private val folder = createTempDirectory("networkhelm-service").toFile()

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "sync-$name.png"))
    }

    private val place = ServiceSettings.Place(folder.path, FOLDER)

    private fun synchronisation(trust: TrustStore = MemoryTrustStore()): Synchronisation {
        val storage = MemoryStorage()
        val services = Services(storage, trust, null, MemorySecrets(), syncFolder = { DirectoryFolder(File(it)) })
        return Synchronisation(services, SavedRouters(storage), Snippets(storage), AppSettings(storage), DockerTemplates(storage), RouterFiles(storage), { false })
    }

    // the folder picker of the platform gives the folder of the test, and Argon2id runs with the least memory; where the test shows
    // one device after another, their rounds run from the start, each once
    @Composable
    private fun Dialog(synchronisation: Synchronisation, runs: Boolean = true, pick: () -> PickedFolder = { PickedFolder(folder.path, FOLDER) }) {
        if (runs) LaunchedEffect(synchronisation) { synchronisation.run() }
        NetworkHelmTheme(dark = false) {
            SyncDialog(synchronisation, {}, { synchronisation.wizard(passes = 1, memory = 8192) }) { pick() }
        }
    }

    private fun SkikoComposeUiTest.shown(tag: String, timeoutMillis: Long = 10_000) =
        waitUntil(timeoutMillis = timeoutMillis) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun SkikoComposeUiTest.text(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun SkikoComposeUiTest.done(synchronisation: Synchronisation) =
        waitUntil(timeoutMillis = 30_000) { synchronisation.running.value?.status?.value is SyncStatus.Done }

    // a round more, after the one the device may be in
    private fun SkikoComposeUiTest.round(synchronisation: Synchronisation) {
        val before = synchronisation.running.value?.status?.value
        synchronisation.now()
        waitUntil(timeoutMillis = 30_000) { synchronisation.running.value?.status?.value.let { it != before && it is SyncStatus.Done } }
    }

    // the vault made on the first device, joined by the second
    private fun vault(first: Synchronisation, second: Synchronisation) = runBlocking {
        val making = first.wizard(passes = 1, memory = 8192)
        making.create(making.reach(place, ""), PASSWORD, null, Choice("desk", emptySet(), false))
        val joining = second.wizard(passes = 1, memory = 8192)
        joining.join(joining.reach(place, ""), PASSWORD, null, Choice("phone", emptySet(), false))
    }

    // a weak password does not pass, a made-up phrase does; with extra protection the secret key is shown once the vault is made, and
    // the code for another device after the password is typed again
    private fun SkikoComposeUiTest.walk(language: String) {
        val synchronisation = synchronisation()
        setContent { Dialog(synchronisation) }
        shown("sync-set-up")
        picture("intro-$language")
        onNodeWithTag("sync-set-up").performClick()
        onNodeWithTag("sync-pick-folder").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(FOLDER)).fetchSemanticsNodes().isNotEmpty() }
        picture("service-$language")
        onNodeWithTag("sync-next").performClick()
        shown("sync-repeat")
        onNodeWithTag("sync-password").performTextInput("qwerty123456")
        shown("sync-strength")
        onNodeWithTag("sync-next").assertIsNotEnabled()
        onNodeWithTag("sync-make-phrase").performClick()
        shown("sync-phrase")
        val phrase = text("sync-phrase")
        onNodeWithTag("sync-extra").performScrollTo().performClick()
        onNodeWithTag("sync-next").assertIsEnabled()
        picture("create-$language")
        onNodeWithTag("sync-next").performScrollTo().performClick()
        shown("sync-made-key", 30_000)
        waitUntil(timeoutMillis = 30_000) { synchronisation.running.value?.status?.value is SyncStatus.Done }
        picture("running-$language")
        onNodeWithTag("sync-key-noted").performClick()
        onNodeWithTag("sync-show-code").performClick()
        onNodeWithTag("sync-code-password").performTextInput(phrase)
        onNodeWithTag("sync-show").performClick()
        shown("sync-qr", 30_000)
        picture("code-$language")
    }

    @Test
    fun aNewVaultIsMadeStepByStepAndShowsTheCodeForAnotherDevice() = runDesktopComposeUiTest(1000, 1200) {
        Locale.setDefault(Locale.ENGLISH)
        walk("en")
    }

    @Test
    fun theWizardInRussian() = runDesktopComposeUiTest(1000, 1200) {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        walk("ru")
    }

    // the devices of the vault and what the user does with it, one panel at a time; the device turned off is asked for the new password
    @Test
    fun theDevicesOfTheVaultAndWhatTheUserDoesWithIt() = runDesktopComposeUiTest(1000, 1500) {
        Locale.setDefault(Locale.ENGLISH)
        val first = synchronisation()
        val second = synchronisation()
        vault(first, second)
        var device by mutableStateOf(first)
        setContent {
            LaunchedEffect(first) { first.run() }
            LaunchedEffect(second) { second.run() }
            Dialog(device, runs = false)
        }
        done(second)
        done(first)
        round(first)
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("phone")).fetchSemanticsNodes().isNotEmpty() }
        picture("devices-en")
        onNodeWithTag("sync-change-password").performClick()
        shown("sync-repeat")
        picture("password-en")
        onNodeWithTag("sync-forgot").performScrollTo().performClick()
        shown("sync-forgot-hint")
        picture("forgot-en")
        onNodeWithTag("sync-change-service").performClick()
        shown("sync-pick-folder")
        picture("move-en")
        val phone = runBlocking { first.devices().keys.single() }
        onNodeWithTag("sync-disable-$phone").performScrollTo().performClick()
        shown("sync-make-phrase")
        onNodeWithTag("sync-make-phrase").performScrollTo().performClick()
        shown("sync-phrase")
        val phrase = text("sync-phrase")
        onNodeWithTag("sync-current").performScrollTo().performTextInput(PASSWORD)
        picture("disable-en")
        onNodeWithTag("sync-next").performScrollTo().performClick()
        waitUntil(timeoutMillis = 30_000) { first.setup.value?.epoch == 2 }

        device = second
        second.now()
        shown("sync-new-key", 30_000)
        // the status line says why the rounds stopped and what to do, and nothing repeats it
        assertEquals(1, onAllNodes(hasText("the key of the vault was changed on another device", substring = true, ignoreCase = true)).fetchSemanticsNodes().size)
        picture("key-changed-en")
        onNodeWithTag("sync-new-key").performTextInput(phrase)
        onNodeWithTag("sync-open").performClick()
        waitUntil(timeoutMillis = 30_000) { second.setup.value?.epoch == 2 }

        device = first
        shown("sync-turn-off")
        onNodeWithTag("sync-turn-off").performClick()
        onNodeWithTag("sync-remove-vault").performClick()
        picture("turn-off-en")
        onNodeWithTag("sync-turn-off-confirm").performClick()
        waitUntil(timeoutMillis = 30_000) { first.setup.value == null }
    }

    // one more folder keeps a copy of the vault; a place the round did not get through is told of beside the one it did, and the device
    // leaves it
    @Test
    fun aPlaceIsAddedToldOfWhenItFailsAndLeft() = runDesktopComposeUiTest(1000, 1500) {
        Locale.setDefault(Locale.ENGLISH)
        val first = synchronisation()
        runBlocking {
            val wizard = first.wizard(passes = 1, memory = 8192)
            wizard.create(wizard.reach(place, ""), PASSWORD, null, Choice("desk", emptySet(), false))
        }
        val second = createTempDirectory("networkhelm-second").toFile()
        var picked = PickedFolder(folder.path, FOLDER)
        setContent { Dialog(first, pick = { picked }) }
        done(first)
        val device = assertNotNull(first.setup.value).device
        onNodeWithTag("sync-add-place").performClick()
        picked = PickedFolder(second.path, "second")
        onNodeWithTag("sync-pick-folder").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("second")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("sync-next").performClick()
        waitUntil(timeoutMillis = 30_000) { first.setup.value?.more?.size == 1 }
        round(first)
        assertTrue(File(second, "$SYNC_FOLDER/state/$device").isFile)
        val id = assertNotNull(first.setup.value).more.single().id
        shown("sync-remove-place-$id")
        picture("places-en")

        File(second, "$SYNC_FOLDER/vault").delete()
        round(first)
        shown("sync-place-failed")
        assertEquals("Not synchronised with second: the vault is no longer on the service", text("sync-place-failed"))
        picture("place-failed-en")
        onNodeWithTag("sync-remove-place-$id").performScrollTo().performClick()
        waitUntil(timeoutMillis = 30_000) { first.setup.value?.more?.isEmpty() == true }
        assertFalse(File(second, "$SYNC_FOLDER/state/$device").exists())
        onNodeWithTag("sync-remove-place-").assertDoesNotExist()
    }

    // both devices trusted a key for one address before the vault: the one whose key lost the merge shows both and takes the other
    @Test
    fun aFingerprintInDisputeIsShownWithBothKeys() = runDesktopComposeUiTest(1000, 1500) {
        Locale.setDefault(Locale.ENGLISH)
        val trusts = List(2) { FileTrustStore(File(createTempDirectory("networkhelm-trust").toFile(), "known-routers")) }
        runBlocking {
            trusts[0].put("192.0.2.1", 8729, TrustKind.Tls, "3f".repeat(32))
            trusts[1].put("192.0.2.1", 8729, TrustKind.Tls, "c4".repeat(32))
        }
        val first = synchronisation(trusts[0])
        val second = synchronisation(trusts[1])
        vault(first, second)
        var device by mutableStateOf(first)
        setContent {
            LaunchedEffect(first) { first.run() }
            LaunchedEffect(second) { second.run() }
            Dialog(device, runs = false)
        }
        done(first)
        done(second)
        round(second)
        round(first)
        round(second)
        if (first.disputes.value.isEmpty()) device = second
        shown("sync-dispute")
        picture("disputes-en")
        onNodeWithTag("sync-dispute-take").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { device.disputes.value.isEmpty() }
    }

    // beside the settings: synchronised, then failed once the vault is gone from the service
    @Test
    fun theMarkTellsHowTheRoundsGo() = runDesktopComposeUiTest(300, 120) {
        Locale.setDefault(Locale.ENGLISH)
        val first = synchronisation()
        val second = synchronisation()
        vault(first, second)
        setContent {
            LaunchedEffect(Unit) { first.run() }
            NetworkHelmTheme(dark = false) {
                CompositionLocalProvider(LocalSync provides SyncAccess(first) {}) {
                    Box(Modifier.fillMaxSize().background(Theme.palette.workspace), contentAlignment = Alignment.Center) { SyncMark() }
                }
            }
        }
        done(first)
        onNodeWithContentDescription("Synchronised").assertExists()
        picture("mark-done-en")
        File(folder, "$SYNC_FOLDER/vault").delete()
        first.now()
        waitUntil(timeoutMillis = 30_000) { first.running.value?.status?.value is SyncStatus.Failed }
        onNodeWithContentDescription("Synchronisation failed").assertExists()
        picture("mark-failed-en")
    }

    // the vault on the service asks for its password; a code of another device opens it without one, the folder picked here
    @Test
    fun aDeviceJoinsWithThePasswordOrWithTheCodeOfAnotherDevice() = runDesktopComposeUiTest(1000, 1200) {
        Locale.setDefault(Locale.ENGLISH)
        val first = synchronisation()
        val code = runBlocking {
            val wizard = first.wizard(passes = 1, memory = 8192)
            wizard.create(wizard.reach(place, ""), PASSWORD, null, Choice("desk", emptySet(), false))
            wizard.shownCode(PASSWORD)
        }
        val byPassword = synchronisation()
        val byCode = synchronisation()
        var device by mutableStateOf(byPassword)
        setContent { Dialog(device) }
        onNodeWithTag("sync-set-up").performClick()
        onNodeWithTag("sync-pick-folder").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(FOLDER)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("sync-next").performClick()
        shown("sync-device")
        onNodeWithTag("sync-password").performTextInput(PASSWORD)
        picture("join-en")
        onNodeWithTag("sync-next").performScrollTo().performClick()
        waitUntil(timeoutMillis = 30_000) { byPassword.running.value?.status?.value is SyncStatus.Done }

        device = byCode
        shown("sync-have-code")
        onNodeWithTag("sync-have-code").performClick()
        // the desktop takes the text under the code: it has no camera to scan with
        onNodeWithTag("sync-scan").assertDoesNotExist()
        picture("code-step-en")
        onNodeWithTag("sync-code").performTextInput(code)
        onNodeWithTag("sync-next").performClick()
        shown("sync-pick-folder")
        onNodeWithTag("sync-pick-folder").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(FOLDER)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("sync-next").performClick()
        shown("sync-device")
        // the service and the devices of the vault tell whose it is before the device joins
        onNodeWithTag("sync-code-check").assertExists()
        picture("join-code-en")
        onNodeWithTag("sync-next").performScrollTo().performClick()
        waitUntil(timeoutMillis = 30_000) { byCode.running.value?.status?.value is SyncStatus.Done }
    }
}
