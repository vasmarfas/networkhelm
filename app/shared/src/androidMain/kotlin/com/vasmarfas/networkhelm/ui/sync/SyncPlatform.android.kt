package com.vasmarfas.networkhelm.ui.sync

import android.os.Build

internal actual val syncPlatform: String = "Android ${Build.VERSION.RELEASE}"

internal actual suspend fun syncDeviceName(): String = Build.MODEL
