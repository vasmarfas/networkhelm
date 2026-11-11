package com.vasmarfas.networkhelm.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.ui.theme.Palette
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TextFieldTest {

    @Test
    fun selectedTextIsMarkedWithTheColourOfTheTheme() = runDesktopComposeUiTest(400, 120) {
        val state = TextFieldState("192.0.2.1")
        lateinit var palette: Palette
        setContent {
            NetworkHelmTheme(dark = true) {
                palette = Theme.palette
                Box(Modifier.padding(10.dp)) { TextField(state, Modifier.width(300.dp).testTag("field")) }
            }
        }
        onNodeWithTag("field").performClick()
        state.edit { selection = TextRange(0, 7) }
        waitForIdle()
        val image = captureToImage().toPixelMap()
        val marked = (10 until 310).sumOf { x -> (10 until 46).count { y -> image[x, y] == palette.selection } }
        assertTrue(marked > 100, "the selection is drawn on $marked pixels")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun theKeyboardIsToldNotToCorrectWhatIsTyped() = runDesktopComposeUiTest(400, 120) {
        val state = TextFieldState()
        var corrects: Boolean? = null
        setContent {
            InterceptPlatformTextInput({ request, _ ->
                corrects = request.imeOptions.autoCorrect
                awaitCancellation()
            }) { NetworkHelmTheme { TextField(state, Modifier.testTag("field")) } }
        }
        onNodeWithTag("field").performClick()
        waitForIdle()
        assertEquals(false, corrects)
    }
}
