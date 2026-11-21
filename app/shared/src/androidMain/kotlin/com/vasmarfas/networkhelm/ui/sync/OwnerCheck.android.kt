package com.vasmarfas.networkhelm.ui.sync

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberOwnerCheck(reason: String): (confirmed: () -> Unit) -> Unit {
    val context = LocalContext.current
    var waiting by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) waiting?.invoke()
        waiting = null
    }
    return remember(reason, launcher) {
        { confirmed ->
            // BiometricPrompt would be a library of its own; this call still asks for the lock of the screen, and gives none without one
            @Suppress("DEPRECATION")
            val asking = context.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(null, reason)
            if (asking == null) {
                confirmed()
            } else {
                waiting = confirmed
                launcher.launch(asking)
            }
        }
    }
}
