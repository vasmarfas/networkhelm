package com.vasmarfas.networkhelm.ui.theme

import com.vasmarfas.networkhelm.app.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MaterialThemeTest {

    @Test
    fun materialComesAfterTheOtherBundledThemesAndFollowsTheSystem() {
        assertEquals(listOf(Modern, Legacy, Material), BundledThemes)
        assertTrue(Material.material)
        assertFalse(Modern.material || Legacy.material)
        assertTrue(Material.isDark(null, system = true))
        assertFalse(Material.isDark(null, system = false))
        assertEquals(MaterialPalette, Material.palette(false))
        assertEquals(MaterialDarkPalette, Material.palette(true))
        assertNotEquals(MaterialPalette, MaterialDarkPalette)
    }

    @Test
    fun everyTextOfBothMaterialLooksHasAtLeastFourAndAHalfToOneOnItsBackground() {
        for (p in listOf(MaterialPalette, MaterialDarkPalette)) {
            val grounds = listOf(p.workspace, p.surface, p.raised, p.selection, p.badge, p.panel, p.title ?: p.accent, p.inactiveTitle ?: p.raised)
            val texts = listOf(p.text, p.muted, p.accent, p.danger, p.success, p.warning)
            val pairs = grounds.flatMap { ground -> texts.map { it to ground } } + listOf(p.onAccent to p.accent, p.onAccent to p.danger, p.onSnack to p.snack)
            val low = pairs.filter { (fore, back) -> contrast(fore, back) < 4.5f }.map { (fore, back) -> "${fore.hex()} on ${back.hex()}" }
            assertTrue(low.isEmpty(), "too little contrast: $low")
        }
    }

    @Test
    fun aThemeFileKeepsTheMaterialFlagAndItsNewColours() {
        val text = AppSettings.json.encodeToString(ThemeFile.serializer(), Material)
        assertTrue("\"material\": true" in text, text)
        assertTrue("\"inverse\": \"#322F35\"" in text, text)
        assertEquals(Material, AppSettings.json.decodeFromString(ThemeFile.serializer(), text))
    }

    @Test
    fun aThemeFileWrittenBeforeTheFlagStillLoads() {
        val text = """
            {"name": "Old", "light": {"workspace": "#E9ECEF", "surface": "#FFFFFF", "raised": "#F3F5F7", "line": "#D5DAE0", "text": "#1E2329",
            "muted": "#5B646F", "faint": "#A3ABB4", "accent": "#1C64C4", "onAccent": "#FFFFFF", "selection": "#CFE2FC", "badge": "#E3E7EB",
            "danger": "#BF322A", "success": "#1E754D", "warning": "#8D5D0E", "grid": "#DFE3E7"}, "classic": false}
        """
        val old = AppSettings.json.decodeFromString(ThemeFile.serializer(), text)
        assertEquals(ThemeFile("Old", light = LightPalette), old)
        assertFalse(old.material)
        assertEquals(LightPalette.surface, LightPalette.panel)
        assertEquals(LightPalette.text, LightPalette.snack)
    }
}
