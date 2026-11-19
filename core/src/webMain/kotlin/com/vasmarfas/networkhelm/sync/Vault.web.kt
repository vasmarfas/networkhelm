@file:OptIn(ExperimentalWasmJsInterop::class)

package com.vasmarfas.networkhelm.sync

internal actual fun nfc(text: String): String = composed(text)

private fun composed(text: String): String = js("text.normalize('NFC')")
