@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.networkhelm.ui.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication

@Composable
internal actual fun rememberOwnerCheck(reason: String): (confirmed: () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(reason) {
        { confirmed ->
            val asking = LAContext()
            if (!asking.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)) {
                confirmed()
            } else {
                asking.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, reason) { success, _ ->
                    if (success) scope.launch(Dispatchers.Main) { confirmed() }
                }
            }
        }
    }
}
