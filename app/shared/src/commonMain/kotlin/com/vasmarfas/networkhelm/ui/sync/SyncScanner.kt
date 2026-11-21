package com.vasmarfas.networkhelm.ui.sync

import androidx.compose.runtime.Composable

// whether this device reads the code of another one by its camera: a phone, or a browser that recognises QR codes itself; the
// desktop takes the text under the code
internal expect val scansCodes: Boolean

// the camera at work until it reads a code, which goes to onCode; the permission of the camera is asked for only here
@Composable
internal expect fun CodeScanner(onCode: (String) -> Unit)
