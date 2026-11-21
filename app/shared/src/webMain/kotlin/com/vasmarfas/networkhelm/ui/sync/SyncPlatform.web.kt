package com.vasmarfas.networkhelm.ui.sync

internal actual val syncPlatform: String = "Browser"

internal actual suspend fun syncDeviceName(): String = syncPlatform
