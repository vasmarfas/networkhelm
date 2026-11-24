package com.vasmarfas.networkhelm.ui.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MenuPanelTest {

    private val list = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    @Test
    fun aShortEntryIsAsWideAsTheLongest() = runDesktopComposeUiTest(600, 400) {
        setContent {
            NetworkHelmTheme { Select("Pool", listOf("Pool", "Traffic Flow and more"), { it }, {}) }
        }
        onNodeWithText("Pool").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Traffic Flow and more").fetchSemanticsNodes().isNotEmpty() }
        val short = onAllNodesWithText("Pool").fetchSemanticsNodes().last().boundsInRoot
        val long = onNodeWithText("Traffic Flow and more").fetchSemanticsNode().boundsInRoot
        assertEquals(long.width, short.width)
    }

    @Test
    fun aListLongerThanTheWindowScrollsWithinIt() = runDesktopComposeUiTest(400, 300) {
        var picked = ""
        setContent {
            NetworkHelmTheme { Select("ether1", (1..40).map { "ether$it" }, { it }, { picked = it }) }
        }
        onNodeWithText("ether1").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("ether40").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onNode(list).fetchSemanticsNode().boundsInWindow.bottom <= 300f)
        onNodeWithText("ether40").performScrollTo().performClick()
        waitForIdle()
        assertEquals("ether40", picked)
    }

    @Test
    fun typingNarrowsALongListAndEnterTakesTheMarkedEntry() = runDesktopComposeUiTest(400, 300) {
        var picked = ""
        setContent {
            NetworkHelmTheme { Select("ether1", (1..40).map { "ether$it" }, { it }, { picked = it }) }
        }
        onNodeWithText("ether1").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        val line = onNode(hasSetTextAction())
        line.assertIsFocused()
        line.performTextInput("r3")
        waitForIdle()
        assertTrue(onAllNodesWithText("ether12").fetchSemanticsNodes().isEmpty())
        line.performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertEquals("ether30", picked)
    }

    @Test
    fun aLongListOpensOnItsCurrentChoice() = runDesktopComposeUiTest(400, 300) {
        setContent {
            NetworkHelmTheme { Select("ether35", (1..40).map { "ether$it" }, { it }, {}) }
        }
        onNodeWithText("ether35").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("ether35").fetchSemanticsNodes().size == 2 }
        waitForIdle()
        val panel = onNode(list).fetchSemanticsNode().boundsInWindow
        val current = onAllNodesWithText("ether35").fetchSemanticsNodes().last().boundsInWindow
        assertTrue(current.top >= panel.top && current.bottom <= panel.bottom, "$current is outside $panel")
    }

    @Test
    fun tabsThatDoNotFitArePagedByArrows() = runDesktopComposeUiTest(300, 200) {
        Locale.setDefault(Locale.ENGLISH)
        val titles = listOf("Interface", "Interface List", "Ethernet", "EoIP Tunnel", "IP Tunnel", "GRE Tunnel", "VLAN")
        setContent { NetworkHelmTheme { TabStrip(titles, 0, {}) } }
        onNodeWithContentDescription("Earlier tabs").assertIsNotEnabled()
        onNodeWithContentDescription("Later tabs").assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 5_000) { onNodeWithContentDescription("Earlier tabs").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled).not() }
    }

    @Test
    fun aStripThatOpensOnALaterTabShowsIt() = runDesktopComposeUiTest(300, 200) {
        Locale.setDefault(Locale.ENGLISH)
        val titles = listOf("Interface", "Interface List", "Ethernet", "EoIP Tunnel", "IP Tunnel", "GRE Tunnel", "VLAN")
        setContent { NetworkHelmTheme { TabStrip(titles, titles.lastIndex, {}) } }
        waitUntil(timeoutMillis = 5_000) { onNodeWithContentDescription("Later tabs").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
        onNodeWithText("VLAN").assertIsDisplayed()
    }

    @Test
    fun aClickWithShiftAddsATabAndAPlainOneChoosesIt() = runDesktopComposeUiTest(600, 200) {
        Locale.setDefault(Locale.ENGLISH)
        var selected by mutableIntStateOf(0)
        var also by mutableStateOf(emptySet<Int>())
        setContent {
            NetworkHelmTheme {
                TabStrip(listOf("General", "Advanced", "Action"), selected, { selected = it; also = emptySet() }, also = also, onAdd = { also = also + it })
            }
        }
        onNodeWithText("Action").performKeyInput { keyDown(Key.ShiftLeft) }
        onNodeWithText("Action").performMouseInput { click() }
        onNodeWithText("Action").performKeyInput { keyUp(Key.ShiftLeft) }
        waitForIdle()
        assertEquals(0 to setOf(2), selected to also)
        onNodeWithText("Advanced").performMouseInput { click() }
        waitForIdle()
        assertEquals(1 to emptySet<Int>(), selected to also)
    }

    @Test
    fun tabsThatFitHaveNoArrows() = runDesktopComposeUiTest(600, 200) {
        Locale.setDefault(Locale.ENGLISH)
        setContent { NetworkHelmTheme { TabStrip(listOf("Saved", "Neighbors"), 0, {}) } }
        waitForIdle()
        assertTrue(onAllNodesWithContentDescription("Later tabs").fetchSemanticsNodes().isEmpty())
    }
}
