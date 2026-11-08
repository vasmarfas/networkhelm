package com.vasmarfas.networkhelm.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaletteTest {

    private val looks = mapOf(
        "Modern light" to LightPalette, "Modern dark" to DarkPalette, "Legacy light" to LegacyPalette, "Legacy dark" to LegacyDarkPalette,
        "Material light" to MaterialPalette, "Material dark" to MaterialDarkPalette,
    )

    @Test
    fun plainTextReadsOnEveryGroundOfEveryLook() {
        val low = looks.flatMap { (name, p) ->
            listOf(p.surface, p.raised, p.selection, p.badge, p.panel).flatMap { ground -> listOf(p.text, p.muted).map { Triple(name, it, ground) } }
        }.filter { (_, fore, back) -> contrast(fore, back) < 4.5f }
        assertTrue(low.isEmpty(), "too little contrast: ${low.map { (name, fore, back) -> "$name ${fore.hex()} on ${back.hex()}" }}")
    }

    @Test
    fun theColoursOfStatesReadOnFieldsFacesAndBadges() {
        val low = looks.flatMap { (name, p) ->
            listOf(p.surface, p.raised, p.badge).flatMap { ground -> listOf(p.accent, p.danger, p.success, p.warning).map { Triple(name, it, ground) } }
        }.filter { (_, fore, back) -> contrast(fore, back) < 4.5f }
        assertTrue(low.isEmpty(), "too little contrast: ${low.map { (name, fore, back) -> "$name ${fore.hex()} on ${back.hex()}" }}")
    }

    @Test
    fun whatIsWrittenOnAFilledShapeReadsInEveryLook() {
        for ((name, p) in looks) for (fill in listOf(p.accent, p.danger, p.success, p.warning)) {
            assertTrue(contrast(p.on(fill), fill) >= 4.5f, "$name ${p.on(fill).hex()} on ${fill.hex()}")
        }
    }

    @Test
    fun theTitleOfAWindowReadsInEveryLook() {
        for ((name, p) in looks) {
            val title = p.title ?: p.accent
            val text = if (p == MaterialPalette || p == MaterialDarkPalette) p.text else p.onAccent
            assertTrue(contrast(text, title) >= 4.5f, "$name ${text.hex()} on ${title.hex()}")
        }
    }

    @Test
    fun aThemeOfItsOwnKeepsItsColourOnAFillThatReads() {
        assertEquals(LightPalette.onAccent, LightPalette.on(LightPalette.accent))
        assertEquals(Color.Black, DarkPalette.copy(accent = Color(0xFFFFD54F)).on(Color(0xFFFFD54F)))
        assertEquals(Color.White, DarkPalette.copy(onAccent = Color.Black).on(Color(0xFF1B3A56)))
    }

    @Test
    fun theDarkLookOfLegacyKeepsItsTextAndItsColoursReadable() {
        val p = LegacyDarkPalette
        val minimums = mapOf(
            "text on fields" to Triple(p.text, p.surface, 7f),
            "text on faces" to Triple(p.text, p.raised, 7f),
            "text on the chosen" to Triple(p.text, p.selection, 4.5f),
            "muted on faces" to Triple(p.muted, p.raised, 4.5f),
            "muted on fields" to Triple(p.muted, p.surface, 4.5f),
            "faint on fields" to Triple(p.faint, p.surface, 4.5f),
            "faint on faces" to Triple(p.faint, p.raised, 3.5f),
            "links on faces" to Triple(p.accent, p.raised, 4.5f),
            "links on fields" to Triple(p.accent, p.surface, 4.5f),
            "errors on fields" to Triple(p.danger, p.surface, 4.5f),
            "errors on faces" to Triple(p.danger, p.raised, 4f),
            "success on fields" to Triple(p.success, p.surface, 4.5f),
            "warnings on fields" to Triple(p.warning, p.surface, 4.5f),
            "the active title" to Triple(p.onAccent, p.title ?: p.accent, 4.5f),
            "the inactive title" to Triple(p.onAccent, p.inactiveTitle ?: p.raised, 4.5f),
            "the band of the menu" to Triple(p.onAccent, p.band ?: p.accent, 4.5f),
            "the frame of a window on the workspace" to Triple(p.frame ?: p.line, p.workspace, 4.5f),
            "the edge of a control on its face" to Triple(p.line, p.raised, 2.5f),
        )
        val low = minimums.filter { (_, pair) -> contrast(pair.first, pair.second) < pair.third }.keys
        assertTrue(low.isEmpty(), "too little contrast: $low")
    }

    @Test
    fun theLitEdgeIsLighterThanTheFaceAndTheShadedOneDarkerInBothLooksOfLegacy() {
        for (p in listOf(LegacyPalette, LegacyDarkPalette)) {
            assertTrue(p.lit.luminance() > p.raised.luminance())
            assertTrue(p.shaded.luminance() < p.raised.luminance())
        }
    }

    @Test
    fun aThemeThatDoesNotFollowTheSystemIsLightUntilTheSettingsAskForDark() {
        assertFalse(Legacy.isDark(null, system = true))
        assertTrue(Legacy.isDark(true, system = false))
        assertFalse(Legacy.isDark(false, system = true))
        assertTrue(Modern.isDark(null, system = true))
        assertFalse(Modern.isDark(null, system = false))
        assertFalse(Modern.isDark(false, system = true))
        assertEquals(LegacyPalette, Legacy.palette(false))
        assertEquals(LegacyDarkPalette, Legacy.palette(true))
    }
}
