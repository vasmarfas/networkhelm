package com.vasmarfas.networkhelm.ui.sync

import androidx.compose.runtime.Composable

// the owner of the device confirms with what unlocks it, where the system asks for that: the screen lock of Android, Face ID, Touch ID
// or the code of iOS; a device without a lock, the desktop and the browser go on at once
@Composable
internal expect fun rememberOwnerCheck(reason: String): (confirmed: () -> Unit) -> Unit
