package com.vasmarfas.networkhelm.app

import androidx.compose.ui.graphics.Color
import com.vasmarfas.networkhelm.ui.theme.Legacy
import com.vasmarfas.networkhelm.ui.theme.LegacyPalette
import com.vasmarfas.networkhelm.ui.theme.Modern
import com.vasmarfas.networkhelm.ui.theme.ThemeFile
import com.vasmarfas.networkhelm.ui.theme.hex
import com.vasmarfas.networkhelm.ui.theme.parseColor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {

    @Test
    fun olderSettingsReadWithTheNewDefaults() {
        val settings = AppSettings.json.decodeFromString(Settings.serializer(), """{"theme": "Legacy", "scale": 1.25}""")
        assertEquals(Settings(theme = "Legacy", scale = 1.25f), settings)
        assertTrue(settings.confirmRemoval && settings.resourceBar && settings.columnLines && settings.rowSpacing == 0 && settings.hidePasswords)
        assertNull(settings.language)
    }

    @Test
    fun aThemeFileComesBackAsItWent() {
        val text = AppSettings.json.encodeToString(ThemeFile.serializer(), Legacy)
        assertTrue("\"workspace\": \"#A0A0A0\"" in text, text)
        assertTrue("\"radius\": 0.0" in text, text)
        assertEquals(Legacy, AppSettings.json.decodeFromString(ThemeFile.serializer(), text))
    }

    @Test
    fun coloursAreWrittenAsHex() {
        assertEquals("#1C2025", Color(0xFF1C2025).hex())
        assertEquals("#801C2025", Color(0x801C2025).hex())
        assertEquals(Color(0xFF1C2025), parseColor("#1c2025"))
        assertEquals(Color(0x801C2025), parseColor("801C2025"))
        assertNull(parseColor("#12345"))
        assertNull(parseColor("blue"))
        assertFailsWith<SerializationException> {
            AppSettings.json.decodeFromString(ThemeFile.serializer(), """{"name": "Bad", "light": {"workspace": "blue"}}""")
        }
    }

    @Test
    fun settingsAndThemesSurvive() = runTest {
        val storage = MemoryStorage()
        val settings = AppSettings(storage)
        val mine = ThemeFile("Mine", light = LegacyPalette)
        settings.keep(mine)
        settings.change { it.copy(theme = "Mine", scale = 1.25f, probe = false) }
        assertEquals("Modern *", settings.keep(Modern).name)

        val reread = AppSettings(storage).also { it.load() }
        assertEquals(Settings(theme = "Mine", scale = 1.25f, probe = false), reread.current)
        assertEquals(mine, reread.theme)
        assertEquals(listOf("Modern", "Legacy", "Material", "Mine", "Modern *"), reread.all.map { it.name })

        reread.remove(mine)
        assertEquals(Modern, reread.theme)
    }

    @Test
    fun twoCopiesOfTheAppKeepWhatTheOtherChangedAndShowTheirOwn() = runTest {
        val storage = MemoryStorage()
        val first = AppSettings(storage).also { it.load() }
        val second = AppSettings(storage).also { it.load() }
        first.change { it.copy(rowSpacing = 4) }
        first.keep(ThemeFile("First"))
        second.change { it.copy(confirmRemoval = false) }
        second.arrange { it.copy(listsBelow = true) }
        second.keep(ThemeFile("Second"))
        assertEquals(0, second.current.rowSpacing)

        val reread = AppSettings(storage).also { it.load() }
        assertEquals(Settings(rowSpacing = 4, confirmRemoval = false, listsBelow = true), reread.current)
        assertEquals(listOf("First", "Second"), reread.themes.map { it.name })
    }

    @Test
    fun unreadableThemesAreNotOverwritten() = runTest {
        val storage = MemoryStorage()
        storage.write("themes.json", "[{\"name\": ")
        val settings = AppSettings(storage).also { it.load() }
        assertNotNull(settings.failure)
        assertFailsWith<IllegalStateException> { settings.keep(ThemeFile("Mine")) }
        assertEquals("[{\"name\": ", storage.read("themes.json"))
    }
}
