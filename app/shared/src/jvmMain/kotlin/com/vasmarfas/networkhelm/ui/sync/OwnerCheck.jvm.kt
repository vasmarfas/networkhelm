package com.vasmarfas.networkhelm.ui.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal actual fun rememberOwnerCheck(reason: String): (confirmed: () -> Unit) -> Unit = remember { { confirmed -> confirmed() } }
