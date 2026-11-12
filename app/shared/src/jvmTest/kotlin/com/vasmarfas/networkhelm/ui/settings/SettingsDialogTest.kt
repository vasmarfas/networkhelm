package com.vasmarfas.networkhelm.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.App
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.Shell
import com.vasmarfas.networkhelm.app.AppSettings
import com.vasmarfas.networkhelm.app.BarItem
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.ui.files.FileExchange
import com.vasmarfas.networkhelm.ui.files.LocalFile
import com.vasmarfas.networkhelm.ui.files.LocalFileExchange
import com.vasmarfas.networkhelm.ui.table.CommentMode
import com.vasmarfas.networkhelm.ui.table.TableLayout
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.LegacyPalette
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import com.vasmarfas.networkhelm.ui.theme.ThemeFile
import com.vasmarfas.networkhelm.ui.theme.hex
import com.vasmarfas.networkhelm.ui.theme.materialPalette
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsDialogTest {

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    @Test
    fun themesComeAndGoAsFiles() = runDesktopComposeUiTest(900, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val settings = AppSettings(MemoryStorage())
        var saved: Pair<String, String>? = null
        val shared = AppSettings.json.encodeToString(ThemeFile.serializer(), ThemeFile("Shared", dark = LegacyPalette))
        val exchange = object : FileExchange {
            override suspend fun pick() = listOf(LocalFile("broken.json") { "{".encodeToByteArray() }, LocalFile("shared.json") { shared.encodeToByteArray() })

            override suspend fun save(name: String, bytes: ByteArray): Boolean {
                saved = name to bytes.decodeToString()
                return true
            }
        }
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) { NetworkHelmTheme { SettingsDialog(settings) {} } }
        }
        onNodeWithTag("theme").performClick()
        onNodeWithText("Legacy").performClick()
        waitUntil(timeoutMillis = 5_000) { settings.current.theme == "Legacy" }

        onNodeWithTag("export-theme").performClick()
        waitUntil(timeoutMillis = 5_000) { saved != null }
        assertEquals("Legacy.json", saved?.first)
        assertTrue("\"name\": \"Legacy\"" in saved?.second.orEmpty(), saved?.second)

        onNodeWithTag("import-theme").performClick()
        waitUntil(timeoutMillis = 5_000) { settings.current.theme == "Shared" }
        onNodeWithTag("settings-problem").assertTextContains("broken.json is not a theme", substring = true)
        picture("settings")

        onNodeWithTag("probe").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { !settings.current.probe }

        onNodeWithTag("terminal-lines").performScrollTo().performClick()
        onNodeWithText("50000 lines").performClick()
        waitUntil(timeoutMillis = 5_000) { settings.current.terminalLines == 50000 }

        onNodeWithTag("comments").performScrollTo().performClick()
        onNodeWithText("In a column").performClick()
        waitUntil(timeoutMillis = 5_000) { settings.current.comments == CommentMode.InColumn }
    }

    @Test
    fun aModeOfCommentsPickedHereTakesTheTablesWithAModeOfTheirOwnBackToTheSettings() = runDesktopComposeUiTest(900, 1000) {
        Locale.setDefault(Locale.ENGLISH)
        val settings = AppSettings(MemoryStorage())
        val tables = TableLayouts(MemoryStorage())
        tables["ip/firewall/mangle"] = TableLayout(columns = listOf("chain"), comments = CommentMode.Hidden)
        tables["ip/firewall/raw"] = TableLayout(columns = listOf("action"))
        setContent { NetworkHelmTheme { SettingsDialog(settings, tables = tables) {} } }
        onNodeWithText("A table can keep a mode of its own", substring = true).assertExists()

        onNodeWithTag("comments").performScrollTo().performClick()
        onNodeWithText("In a column").performClick()
        waitUntil(timeoutMillis = 5_000) { tables["ip/firewall/mangle"]?.comments == null }
        assertEquals(CommentMode.InColumn, settings.current.comments)
        assertEquals(listOf("chain"), tables["ip/firewall/mangle"]?.columns, "the rest of the layout stays")
        assertEquals(listOf("action"), tables["ip/firewall/raw"]?.columns)
    }

    @Test
    fun theCheckForNewVersionsOfTheProgramIsOnAndCanBeSwitchedOff() = runDesktopComposeUiTest(900, 1000) {
        Locale.setDefault(Locale.ENGLISH)
        val settings = AppSettings(MemoryStorage())
        setContent { NetworkHelmTheme { SettingsDialog(settings) {} } }
        assertTrue(settings.current.checkUpdates)
        onNodeWithTag("check-updates").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { !settings.current.checkUpdates }
        onNodeWithTag("check-updates").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { settings.current.checkUpdates }
    }

    @Test
    fun aProgramStartedWithTheCheckSwitchedOffAsksGitHubForNothing() = runDesktopComposeUiTest(900, 700) {
        Locale.setDefault(Locale.ENGLISH)
        var asked = 0
        val storage = MemoryStorage().also { runBlocking { it.write("settings.json", """{"checkUpdates": false}""") } }
        val shell = Shell(Services(storage, MemoryTrustStore(), neighbors = null, releases = {
            asked++
            null
        }), TestScope())
        setContent { App(shell) }
        waitUntil(timeoutMillis = 5_000) { shell.settings.loaded }
        waitForIdle()
        assertFalse(shell.settings.current.checkUpdates)
        assertEquals(0, asked)
    }

    @Test
    fun aProgramStartedWithTheDefaultsAsksGitHubOnceTheSettingsAreRead() = runDesktopComposeUiTest(900, 700) {
        Locale.setDefault(Locale.ENGLISH)
        var asked = 0
        val shell = Shell(Services(MemoryStorage(), MemoryTrustStore(), neighbors = null, releases = {
            asked++
            null
        }), TestScope())
        setContent { App(shell) }
        waitUntil(timeoutMillis = 5_000) { asked > 0 }
        waitForIdle()
        assertEquals(1, asked)
    }

    @Test
    fun theItemsOfTheBottomBarAreChosenAndOrdered() = runDesktopComposeUiTest(900, 1000) {
        Locale.setDefault(Locale.ENGLISH)
        val settings = AppSettings(MemoryStorage())
        setContent { NetworkHelmTheme { SettingsDialog(settings) {} } }
        onNodeWithTag("bar-Disk").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { BarItem.Disk in settings.current.barHidden }
        onNodeWithTag("bar-up-Memory").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { settings.current.barOrder.take(3) == listOf(BarItem.Router, BarItem.Memory, BarItem.Cpu) }
        onNodeWithTag("bar-interface").performScrollTo().performTextInput("ether1")
        waitUntil(timeoutMillis = 5_000) { settings.current.barInterface == "ether1" }
        onNodeWithTag("bar-Disk").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { settings.current.barHidden.isEmpty() }
    }

    @Test
    fun theLanguageIsChosenInTheSettings() = runDesktopComposeUiTest(900, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val shell = Shell(Services(MemoryStorage(), MemoryTrustStore(), neighbors = null), TestScope())
        shell.settingsShown = true
        setContent { App(shell) }
        onNodeWithTag("language").performClick()
        onNodeWithText("Русский").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Язык")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("ru", shell.settings.current.language)
        onNodeWithTag("language").performClick()
        onAllNodes(hasText("Как в системе")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Language")).fetchSemanticsNodes().isNotEmpty() }
        assertNull(shell.settings.current.language)
    }

    private fun SkikoComposeUiTest.legacyShell(dark: Boolean?, shots: String) {
        Locale.setDefault(Locale.ENGLISH)
        fun directory(path: String, vararg children: String) = path to MenuSchema(MenuPath.parse(path), NodeKind.Directory, children.toList(), emptyList(), emptyList())
        val addresses = listOf(Item("*1", mapOf("address" to "192.0.2.1/24", "network" to "192.0.2.0", "interface" to "ether1")))
        val router = FakeRouter(mapOf("ip/address" to addresses), schemas = mapOf(directory("", "interface", "ip"), directory("ip", "address", "arp")))
        val shell = Shell(Services(MemoryStorage(), MemoryTrustStore(), neighbors = null), TestScope())
        runBlocking { shell.settings.change { it.copy(theme = "Legacy", dark = dark) } }
        shell.add(router, "192.0.2.1")
        setContent { App(shell) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("IP")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("IP").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Addresses")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Addresses").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("192.0.2.1/24", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        picture("$shots-list")
        shell.routers.first().openItem("Address", MenuPath.parse("ip/address"), "*1")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Interface:")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(onNodeWithText("OK").fetchSemanticsNode().boundsInRoot.width, onNodeWithText("Remove").fetchSemanticsNode().boundsInRoot.width)
        picture("$shots-dialog")
        onNodeWithText("IP").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("ARP")).fetchSemanticsNodes().isNotEmpty() }
        picture("$shots-main")
    }

    @Test
    fun theShellWearsTheThemeChosen() = runDesktopComposeUiTest(1100, 700) { legacyShell(null, "legacy") }

    @Test
    fun theShellWearsTheDarkLookOfTheLegacyTheme() = runDesktopComposeUiTest(1100, 700) { legacyShell(true, "legacy-dark") }

    @Test
    fun legacyIsLightUntilTheSettingsAskForTheDarkLook() = runDesktopComposeUiTest(900, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val settings = AppSettings(MemoryStorage())
        setContent { NetworkHelmTheme { SettingsDialog(settings) {} } }
        fun systems() = onAllNodes(hasText("As the system")).fetchSemanticsNodes().size
        onNodeWithTag("look").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Dark")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(3, systems(), "Modern follows the system: the language, the look and its list")
        onAllNodes(hasText("Light")).onLast().performClick()
        onNodeWithTag("theme").performClick()
        onNodeWithText("Legacy").performClick()
        waitUntil(timeoutMillis = 5_000) { settings.current.theme == "Legacy" }
        onNodeWithTag("look").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Dark")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, systems(), "Legacy has no look of the system, only the language has")
        onAllNodes(hasText("Dark")).onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { settings.current.dark == true }
    }

    @Test
    fun materialTakesTheAccentOfASwatchAndExportsItsColours() = runDesktopComposeUiTest(900, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val settings = AppSettings(MemoryStorage())
        var saved: String? = null
        val exchange = object : FileExchange {
            override suspend fun pick() = emptyList<LocalFile>()

            override suspend fun save(name: String, bytes: ByteArray): Boolean {
                saved = bytes.decodeToString()
                return true
            }
        }
        setContent {
            CompositionLocalProvider(LocalFileExchange provides exchange) { NetworkHelmTheme { SettingsDialog(settings) {} } }
        }
        assertTrue(onAllNodes(hasTestTag("accent-0")).fetchSemanticsNodes().isEmpty(), "Modern has no swatches")
        onNodeWithTag("theme").performClick()
        onNodeWithText("Material").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("accent-0")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasTestTag("system-colours")).fetchSemanticsNodes().isEmpty(), "the desktop has no colours of the system")
        val teal = Color(0xFF006A6A)
        onNodeWithTag("accent-1").performClick()
        waitUntil(timeoutMillis = 5_000) { settings.current.accent == teal }
        onNodeWithTag("export-theme").performClick()
        waitUntil(timeoutMillis = 5_000) { saved != null }
        assertTrue("\"accent\": \"${materialPalette(teal, dark = false).accent.hex()}\"" in saved.orEmpty(), saved)
    }

    @Test
    fun anEditedCopyIsKeptAndChosen() = runDesktopComposeUiTest(1100, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val settings = AppSettings(MemoryStorage())
        setContent { NetworkHelmTheme { SettingsDialog(settings) {} } }
        onNodeWithText("Edit theme").performClick()
        onNodeWithTag("colour-colour_accent").performTextClearance()
        onNodeWithTag("colour-colour_accent").performTextInput("#FF0000")
        waitForIdle()
        picture("theme-editor")
        onNodeWithTag("icon-group").performScrollTo().performClick()
        onNodeWithText("Values in tables").performClick()
        onNodeWithTag("icon-action=drop").performScrollTo().performClick()
        onAllNodesWithContentDescription("flame").onFirst().performClick()
        waitForIdle()
        picture("theme-editor-icons")
        onNodeWithTag("theme-save").performClick()
        waitUntil(timeoutMillis = 5_000) { settings.current.theme == "Modern, mine" }
        assertEquals(Color(0xFFFF0000), settings.theme.light?.accent)
        assertEquals(mapOf("value/action=drop" to "flame:danger"), settings.theme.icons)
        assertEquals(listOf("Modern, mine"), settings.themes.map { it.name })
    }

    @Test
    fun aPictureOfTheUsersBecomesAnIcon() = runDesktopComposeUiTest(1100, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val settings = AppSettings(MemoryStorage())
        var files = listOf(LocalFile("notes.txt") { "not a picture".encodeToByteArray() })
        val exchange = object : FileExchange {
            override suspend fun pick() = files

            override suspend fun save(name: String, bytes: ByteArray) = true
        }
        setContent { CompositionLocalProvider(LocalFileExchange provides exchange) { NetworkHelmTheme { SettingsDialog(settings) {} } } }
        onNodeWithText("Edit theme").performClick()
        onNodeWithTag("icon-group").performScrollTo().performClick()
        onNodeWithText("Values in tables").performClick()
        onNodeWithTag("icon-action=accept").performScrollTo().performClick()
        onNodeWithTag("icon-picture").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("notes.txt is not a picture", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().apply {
                fillOval(8, 8, 48, 48)
                dispose()
            }
        }
        files = listOf(LocalFile("logo.png") { ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray() })
        onNodeWithTag("icon-action=accept").performScrollTo().performClick()
        onNodeWithTag("icon-picture").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("notes.txt is not a picture", substring = true)).fetchSemanticsNodes().isEmpty() }
        waitForIdle()
        picture("theme-editor-picture")
        onNodeWithTag("theme-save").performClick()
        waitUntil(timeoutMillis = 5_000) { settings.current.theme == "Modern, mine" }
        val spec = settings.theme.icons.getValue("value/action=accept")
        assertTrue(spec.startsWith("image:48:") && spec.endsWith(":success"), spec)
    }
}
