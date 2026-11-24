package com.vasmarfas.networkhelm.ui.form

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SegmentsTest {

    @Test
    fun aFewWordsOfTheRouterStandSideBySide() = runDesktopComposeUiTest(600, 120) {
        val mode = Argument("protocol-mode", ValueType.Enumeration(closed = true), listOf("none", "stp", "rstp", "mstp"))
        var value = ""
        setContent {
            var shown by remember { mutableStateOf("rstp") }
            NetworkHelmTheme {
                FieldEditor(mode, shown, {
                    shown = it
                    value = it
                })
            }
        }
        onNodeWithText("RSTP").assertIsSelected()
        ImageIO.write(captureToImage().toAwtImage(), "png", File("build/screenshots").also { it.mkdirs() }.resolve("segments.png"))
        onNodeWithText("None").performClick()
        waitForIdle()
        assertEquals("none", value)
        onNodeWithText("None").assertIsSelected()
    }

    @Test
    fun anEmptyChoiceStandsAsNotSet() = runDesktopComposeUiTest(600, 120) {
        Locale.setDefault(Locale.ENGLISH)
        val schedule = Argument("schedule", ValueType.Enumeration(closed = true), listOf("", "Schedule0", "Schedule1"))
        var value = "Schedule0"
        setContent {
            var shown by remember { mutableStateOf("Schedule0") }
            NetworkHelmTheme {
                FieldEditor(schedule, shown, {
                    shown = it
                    value = it
                })
            }
        }
        onNodeWithText("Not set").performClick()
        waitForIdle()
        assertEquals("", value)
        onNodeWithText("Not set").assertIsSelected()
    }

    @Test
    fun theNamesTheRouterWritesWithCapitalsStandAsItWritesThem() = runDesktopComposeUiTest(600, 120) {
        val acquisition = Argument("address-acquisition", ValueType.Enumeration(closed = true), listOf("Static", "Automatic", "PPPoE"))
        setContent { NetworkHelmTheme { FieldEditor(acquisition, "PPPoE", {}) } }
        onNodeWithText("PPPoE").assertIsSelected()
    }

    @Test
    fun aValueBesidesTheWordsLeavesTheFieldALine() = runDesktopComposeUiTest(600, 120) {
        val timeout = Argument("keepalive-timeout", ValueType.Enumeration(closed = true), listOf("default", "disabled"))
        var value = ""
        setContent {
            var shown by remember { mutableStateOf("60") }
            NetworkHelmTheme {
                FieldEditor(timeout, shown, {
                    shown = it
                    value = it
                })
            }
        }
        assertTrue(onAllNodes(hasText("Default")).fetchSemanticsNodes().isEmpty(), "no segments of the two words")
        onNodeWithText("60").performTextInput("0")
        waitForIdle()
        assertEquals("600", value)
    }

    @Test
    fun namesOfObjectsStayAList() = runDesktopComposeUiTest(600, 120) {
        val bridge = Argument("bridge", ValueType.Enumeration(), listOf("bridge1", "bridge2"))
        setContent { NetworkHelmTheme { FieldEditor(bridge, "bridge1", {}) } }
        assertTrue(onAllNodes(hasText("bridge2")).fetchSemanticsNodes().isEmpty(), "the other choice waits in the closed list")
    }
}
