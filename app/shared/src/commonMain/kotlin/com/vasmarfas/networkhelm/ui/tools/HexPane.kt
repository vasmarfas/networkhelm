package com.vasmarfas.networkhelm.ui.tools

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.nettools.OptionHex
import com.vasmarfas.networkhelm.ui.kit.Labeled
import com.vasmarfas.networkhelm.ui.kit.TextField
import com.vasmarfas.networkhelm.ui.theme.Theme
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.tools_hex_addresses
import networkhelm.app.shared.generated.resources.tools_hex_code
import networkhelm.app.shared.generated.resources.tools_hex_code_hint
import networkhelm.app.shared.generated.resources.tools_hex_input
import networkhelm.app.shared.generated.resources.tools_hex_sub_options
import networkhelm.app.shared.generated.resources.tools_hex_text
import networkhelm.app.shared.generated.resources.tools_hex_value
import networkhelm.app.shared.generated.resources.tools_hex_wrapped

@Composable
internal fun HexPane() {
    val input = remember { TextFieldState("10.103.0.250") }
    val code = remember { TextFieldState() }
    val text = input.text.toString()
    // a dot makes the input addresses, anything else is read as bytes
    val addresses = remember(text) { if ('.' in text) OptionHex.addresses(text) else null }
    val bytes = remember(text) { if ('.' in text) null else OptionHex.bytes(text) }
    Labeled(stringResource(Res.string.tools_hex_input)) {
        TextField(input, Modifier.width(340.dp).testTag("hex-input"), placeholder = "10.103.0.250 · 0x01040A6700FA", mono = true, error = text.isNotBlank() && addresses == null && bytes == null)
    }
    if (addresses != null) {
        val value = OptionHex.bytesOf(addresses)
        Fact(stringResource(Res.string.tools_hex_value), OptionHex.format(value))
        val typed = code.text.toString().trim()
        val number = if (typed.startsWith("0x", ignoreCase = true)) typed.substring(2).toIntOrNull(16) else typed.toIntOrNull()
        val wrapped = number?.let { OptionHex.wrapped(it, value) }
        Labeled(stringResource(Res.string.tools_hex_code)) {
            TextField(code, Modifier.width(NumberWidth).testTag("hex-code"), placeholder = "1", mono = true, error = typed.isNotEmpty() && wrapped == null)
        }
        Labeled("") { Text(stringResource(Res.string.tools_hex_code_hint), color = Theme.palette.muted, style = Theme.type.small) }
        if (wrapped != null) Fact(stringResource(Res.string.tools_hex_wrapped, typed), OptionHex.format(wrapped))
    }
    if (bytes != null) {
        OptionHex.addressesIn(bytes)?.let { Fact(stringResource(Res.string.tools_hex_addresses), it.joinToString(", ")) }
        OptionHex.subOptions(bytes)?.let { options -> Fact(stringResource(Res.string.tools_hex_sub_options), options.joinToString("\n") { "${it.code}: ${readable(it.value)}" }) }
        OptionHex.text(bytes)?.let { Fact(stringResource(Res.string.tools_hex_text), it) }
    }
}

private fun readable(value: List<Int>) = OptionHex.addressesIn(value)?.joinToString(", ") ?: OptionHex.text(value)?.let { "\"$it\"" } ?: OptionHex.format(value)
