package com.vasmarfas.networkhelm.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.vasmarfas.networkhelm.session.keep
import com.vasmarfas.networkhelm.ui.table.CommentMode
import com.vasmarfas.networkhelm.ui.theme.BundledThemes
import com.vasmarfas.networkhelm.ui.theme.DefaultAccent
import com.vasmarfas.networkhelm.ui.theme.HexColor
import com.vasmarfas.networkhelm.ui.theme.Modern
import com.vasmarfas.networkhelm.ui.theme.ThemeFile
import com.vasmarfas.networkhelm.ui.theme.accented
import com.vasmarfas.networkhelm.ui.theme.systemAccent
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class BarItem { Router, Cpu, Memory, Disk, Traffic, Uptime, Clock }

@Serializable
data class Settings(
    val language: String? = null,
    val theme: String = Modern.name,
    val dark: Boolean? = null,
    @Serializable(HexColor::class) val accent: Color = DefaultAccent,
    val systemColours: Boolean = true,
    val scale: Float = 1f,
    val probe: Boolean = true,
    val confirmRemoval: Boolean = true,
    val tablesAsTabs: Boolean = false,
    val rowSpacing: Int = 0,
    val columnLines: Boolean = true,
    val comments: CommentMode = CommentMode.Above,
    val listsBelow: Boolean = false,
    val connectSplit: Float? = null,
    val resourceBar: Boolean = true,
    val barOrder: List<BarItem> = BarItem.entries,
    val barHidden: Set<BarItem> = emptySet(),
    val barInterface: String = "",
    val terminalLines: Int = 10000,
    val hidePasswords: Boolean = true,
    val checkUpdates: Boolean = true,
)

val LocalSettings = staticCompositionLocalOf { Settings() }

val LocalAppSettings = staticCompositionLocalOf<AppSettings?> { null }

const val HiddenValue = "••••••••"

class AppSettings(private val storage: Storage) {
    var current by mutableStateOf(Settings())
        private set
    val themes = mutableStateListOf<ThemeFile>()
    var failure by mutableStateOf<String?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    private var themesUnread = false

    val all: List<ThemeFile> get() = BundledThemes + themes

    val theme: ThemeFile get() = all.firstOrNull { it.name == current.theme } ?: Modern

    // the chosen theme in the colours it is drawn with, the system's where the settings ask for them and the platform has them
    @Composable
    fun drawnTheme(): ThemeFile {
        val accent = systemAccent().takeIf { current.systemColours } ?: current.accent
        val chosen = theme
        return remember(chosen, accent) { chosen.accented(accent) }
    }

    suspend fun load() {
        storage.read(SETTINGS)?.let { text ->
            try {
                use(json.decodeFromString(Settings.serializer(), text))
            } catch (e: IllegalArgumentException) {
                failure = "$SETTINGS: ${e.message}"
            }
        }
        storage.read(THEMES)?.let { text ->
            try {
                themes.addAll(json.decodeFromString(serializer, text))
            } catch (e: IllegalArgumentException) {
                themesUnread = true
                failure = "$THEMES: ${e.message}"
            }
        }
        loaded = true
    }

    suspend fun change(edit: (Settings) -> Settings) {
        use(edit(current))
        storage.write(SETTINGS, json.encodeToString(Settings.serializer(), current))
    }

    // a part of a screen is moved at once; settings that could not be written go to the file whole with the next change that is
    suspend fun arrange(edit: (Settings) -> Settings) {
        use(edit(current))
        storage.keep(SETTINGS, json.encodeToString(Settings.serializer(), current))
    }

    // the language goes to the platform before the shell reads the new settings, so that it composes in it
    private fun use(settings: Settings) {
        setLanguage(settings.language)
        current = settings
    }

    suspend fun keep(theme: ThemeFile): ThemeFile {
        val kept = if (BundledThemes.any { it.name == theme.name }) theme.copy(name = "${theme.name} *") else theme
        update { list -> list.filterNot { it.name == kept.name } + kept }
        return kept
    }

    suspend fun remove(theme: ThemeFile) = update { list -> list.filterNot { it.name == theme.name } }

    private suspend fun update(edit: (List<ThemeFile>) -> List<ThemeFile>) {
        check(!themesUnread) { "$THEMES could not be read, not overwriting it" }
        val changed = edit(themes.toList())
        storage.write(THEMES, json.encodeToString(serializer, changed))
        themes.clear()
        themes.addAll(changed)
    }

    companion object {
        private const val SETTINGS = "settings.json"
        private const val THEMES = "themes.json"
        private val serializer = ListSerializer(ThemeFile.serializer())

        val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
