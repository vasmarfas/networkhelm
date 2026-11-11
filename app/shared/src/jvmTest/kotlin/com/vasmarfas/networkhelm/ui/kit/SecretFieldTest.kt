package com.vasmarfas.networkhelm.ui.kit

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SecretFieldTest {

    @Test
    fun theTreeGetsDotsUntilTheEyeShowsThePassword() = runDesktopComposeUiTest(400, 120) {
        Locale.setDefault(Locale.ENGLISH)
        val state = TextFieldState()
        setContent { NetworkHelmTheme { SecretField(state, Modifier.testTag("secret")) } }
        onNodeWithTag("secret").performTextInput("rd-test-pw")
        waitForIdle()
        assertEquals("rd-test-pw", state.text.toString())
        fun tree() = onNodeWithTag("secret").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("••••••••••", tree())
        onNodeWithContentDescription("Show the value").performClick()
        waitForIdle()
        assertEquals("rd-test-pw", tree())
    }

    @Test
    fun aTypedCharacterIsMaskedAtOnce() = runDesktopComposeUiTest(400, 120) {
        val state = TextFieldState()
        setContent { NetworkHelmTheme { SecretField(state, Modifier.testTag("secret")) } }
        onNodeWithTag("secret").performTextInput("p")
        val drawn = mutableListOf<TextLayoutResult>()
        onNodeWithTag("secret").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(drawn) }
        assertEquals("•", drawn.single().layoutInput.text.text)
    }
}
