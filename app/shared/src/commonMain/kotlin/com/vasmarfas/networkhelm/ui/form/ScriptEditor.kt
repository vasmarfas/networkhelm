package com.vasmarfas.networkhelm.ui.form

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.ui.theme.Palette
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.delay

class ScriptSupport(val highlight: (suspend (String) -> List<String>?)?)

val LocalScripts = staticCompositionLocalOf<ScriptSupport?> { null }

internal val ScriptProperties = setOf(
    "source", "on-event", "up-script", "down-script", "test-script", "lease-script", "binding-script", "on-up", "on-down",
    "on-login", "on-logout", "on-alert", "script",
)

@Composable
internal fun ScriptEditor(value: String, onChange: (String) -> Unit, support: ScriptSupport, modifier: Modifier = Modifier, console: Boolean = true) {
    val palette = Theme.palette
    val shape = RoundedCornerShape(Theme.metrics.radius)
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    if (field.text != value) field = field.copy(text = value)
    var classes by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(value) {
        val highlight = support.highlight?.takeIf { console } ?: return@LaunchedEffect
        delay(400)
        classes = try {
            highlight(value)
        } catch (e: DeviceException) {
            null
        }
    }
    val shown = classes?.takeIf { it.size == value.length }
    DisableSelection {
        BasicTextField(
            value = field,
            onValueChange = {
                field = it
                if (it.text != value) onChange(it.text)
            },
            textStyle = Theme.type.mono.copy(color = palette.text),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            cursorBrush = SolidColor(palette.accent),
            visualTransformation = VisualTransformation { text -> TransformedText(coloured(text.text, shown, palette), OffsetMapping.Identity) },
            modifier = modifier.heightIn(min = 160.dp).background(palette.surface, shape).border(1.dp, palette.line, shape).padding(6.dp),
        )
    }
}

private fun coloured(text: String, classes: List<String>?, palette: Palette): AnnotatedString = buildAnnotatedString {
    append(text)
    if (classes == null) return@buildAnnotatedString
    var start = 0
    for (end in 1..text.length) {
        if (end < text.length && classes[end] == classes[start]) continue
        colour(classes[start], palette)?.let { addStyle(SpanStyle(color = it), start, end) }
        start = end
    }
}

private fun colour(kind: String, palette: Palette): Color? = when {
    kind == "dir" || kind == "cmd" -> palette.accent
    kind == "arg" -> palette.success
    kind.startsWith("variable") || kind == "varname" || kind == "escaped" -> palette.warning
    kind == "comment" || kind.startsWith("syntax") -> palette.muted
    kind == "error" || kind == "obj-inactive" || kind == "ambiguous" -> palette.danger
    else -> null
}
