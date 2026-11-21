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
import com.vasmarfas.networkhelm.session.peek
import com.vasmarfas.networkhelm.sync.FileRecords
import com.vasmarfas.networkhelm.sync.HlcClock
import com.vasmarfas.networkhelm.sync.Record
import com.vasmarfas.networkhelm.sync.recordJson
import com.vasmarfas.networkhelm.ui.table.CommentMode
import com.vasmarfas.networkhelm.ui.theme.BundledThemes
import com.vasmarfas.networkhelm.ui.theme.DefaultAccent
import com.vasmarfas.networkhelm.ui.theme.HexColor
import com.vasmarfas.networkhelm.ui.theme.Modern
import com.vasmarfas.networkhelm.ui.theme.ThemeFile
import com.vasmarfas.networkhelm.ui.theme.accented
import com.vasmarfas.networkhelm.ui.theme.systemAccent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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
    val menuBelow: Boolean = false,
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

class AppSettings(private val storage: Storage, clock: HlcClock = HlcClock()) {
    var current by mutableStateOf(Settings())
        private set
    val themes = mutableStateListOf<ThemeFile>()
    var failure by mutableStateOf<String?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    private var themesUnread = false
    val settingRecords = FileRecords(storage, SETTINGS, "setting", clock)
    val themeRecords = FileRecords(storage, THEMES, "theme", clock)

    // an edit of the user and the settings of other devices go into the files one after the other
    private val lock = Mutex()

    val all: List<ThemeFile> get() = BundledThemes + themes

    val theme: ThemeFile get() = all.firstOrNull { it.name == current.theme } ?: Modern

    // the chosen theme in the colours it is drawn with, the system's where the settings ask for them and the platform has them
    @Composable
    fun drawnTheme(): ThemeFile {
        val accent = systemAccent().takeIf { current.systemColours } ?: current.accent
        val chosen = theme
        return remember(chosen, accent) { chosen.accented(accent) }
    }

    suspend fun load() = lock.withLock {
        settingRecords.match {
            storage.read(SETTINGS)?.let { text ->
                try {
                    use(json.decodeFromString(Settings.serializer(), text))
                } catch (e: IllegalArgumentException) {
                    failure = "$SETTINGS: ${e.message}"
                    // a file this version cannot read is written over with the next change and matched then
                    return@match null
                }
            }
            settingEntries(current)
        }
        themeRecords.match {
            storage.read(THEMES)?.let { text ->
                try {
                    themes.addAll(json.decodeFromString(serializer, text))
                } catch (e: IllegalArgumentException) {
                    themesUnread = true
                    failure = "$THEMES: ${e.message}"
                    return@match null
                }
            }
            themeEntries(themes)
        }
        loaded = true
    }

    suspend fun change(edit: (Settings) -> Settings) = lock.withLock {
        val before = current
        use(edit(before))
        storage.write(SETTINGS, json.encodeToString(Settings.serializer(), edit(stored(storage.read(SETTINGS)) ?: before)))
        settingRecords.match { stored(storage.read(SETTINGS))?.let { settingEntries(it) } }
    }

    // a part of a screen is moved at once; settings that could not be written go to the file whole with the next change that is,
    // and the records take what was moved with the next change or start
    suspend fun arrange(edit: (Settings) -> Settings) {
        lock.withLock {
            val before = current
            use(edit(before))
            storage.keep(SETTINGS, json.encodeToString(Settings.serializer(), edit(stored(storage.peek(SETTINGS)) ?: before)))
        }
    }

    // the records of the settings and themes as the files hold them now
    suspend fun records(): List<Record> = lock.withLock {
        settingRecords.match { stored(storage.read(SETTINGS))?.let { settingEntries(it) } }
        themeRecords.match { storage.read(THEMES)?.let(::listed)?.let(::themeEntries) }
        settingRecords.take() + themeRecords.take()
    }

    // the settings merged with those of the other devices of the vault; those that stay on each device are left as they are here. A
    // setting changed since the round read the file waits with them for the next round
    suspend fun applySettings(merged: List<Record>) = lock.withLock {
        settingRecords.match { stored(storage.read(SETTINGS))?.let { settingEntries(it) } }
        if (settingRecords.edited) return@withLock
        val fields = recordJson.encodeToJsonElement(Settings.serializer(), stored(storage.read(SETTINGS)) ?: current).jsonObject
        val shared = merged.filterNot { it.id in DEVICE_SETTINGS }
        val values = shared.filter { !it.deleted && it.id in fields }.mapNotNull { record -> record.data["value"]?.let { record.id to it } }
        val settings = recordJson.decodeFromJsonElement(Settings.serializer(), JsonObject(fields + values))
        use(settings)
        storage.write(SETTINGS, json.encodeToString(Settings.serializer(), settings))
        val encoded = recordJson.encodeToJsonElement(Settings.serializer(), settings).jsonObject
        val normalized = shared.map { record -> encoded[record.id]?.takeUnless { record.deleted }?.let { record.copy(data = JsonObject(record.data + ("value" to it))) } ?: record }
        settingRecords.keep(settingRecords.all.filter { it.id in DEVICE_SETTINGS } + normalized)
    }

    // the themes merged with those of the other devices: those here keep their places, those from elsewhere come after them
    suspend fun applyThemes(merged: List<Record>) = lock.withLock {
        check(!themesUnread) { "$THEMES could not be read, not overwriting it" }
        themeRecords.match { storage.read(THEMES)?.let(::listed)?.let(::themeEntries) }
        themeRecords.put(merged, ThemeFile.serializer()) { kept ->
            val stored = storage.read(THEMES)?.let(::listed) ?: themes.toList()
            val ordered = stored.mapNotNull { theme -> kept.firstOrNull { it.name == theme.name } } + kept.filter { theme -> stored.none { it.name == theme.name } }
            storage.write(THEMES, json.encodeToString(serializer, ordered))
            themes.clear()
            themes.addAll(ordered)
        }
    }

    // another copy of the app writes the same file: an edit goes onto the settings as the file has them now, and this copy shows
    // only its own edits until it starts again; a file it cannot read is written over, as before
    private fun stored(text: String?): Settings? = text?.let {
        try {
            json.decodeFromString(Settings.serializer(), it)
        } catch (e: IllegalArgumentException) {
            null
        }
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

    private fun listed(text: String): List<ThemeFile>? = try {
        json.decodeFromString(serializer, text)
    } catch (e: IllegalArgumentException) {
        null
    }

    private suspend fun update(edit: (List<ThemeFile>) -> List<ThemeFile>) = lock.withLock {
        check(!themesUnread) { "$THEMES could not be read, not overwriting it" }
        // another copy of the app may have kept or removed a theme since this one read the list
        val changed = edit(storage.read(THEMES)?.let { json.decodeFromString(serializer, it) } ?: themes.toList())
        storage.write(THEMES, json.encodeToString(serializer, changed))
        themes.clear()
        themes.addAll(changed)
        themeRecords.match { storage.read(THEMES)?.let { themeEntries(json.decodeFromString(serializer, it)) } }
    }

    companion object {
        private const val SETTINGS = "settings.json"
        private const val THEMES = "themes.json"
        private val serializer = ListSerializer(ThemeFile.serializer())

        // the settings that stay on the device, as they follow its screen; the language, the theme and its colours, the comments of
        // tables, the confirmations, the hidden passwords and the check for updates go to the other devices, and so does a setting of a
        // newer version
        val DEVICE_SETTINGS = setOf(
            "scale", "probe", "tablesAsTabs", "rowSpacing", "columnLines", "listsBelow", "menuBelow", "connectSplit", "resourceBar", "barOrder",
            "barHidden", "barInterface", "terminalLines",
        )

        val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        // every setting is an entry of its own, so that two devices changing two settings keep both
        private fun settingEntries(settings: Settings) =
            recordJson.encodeToJsonElement(Settings.serializer(), settings).jsonObject.mapValues { (_, value) -> JsonObject(mapOf("value" to value)) }

        private fun themeEntries(themes: List<ThemeFile>) = themes.distinctBy { it.name }.associate { it.name to recordJson.encodeToJsonElement(ThemeFile.serializer(), it).jsonObject }
    }
}
