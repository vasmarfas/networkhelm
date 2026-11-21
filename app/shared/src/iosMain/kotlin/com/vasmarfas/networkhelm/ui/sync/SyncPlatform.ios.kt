package com.vasmarfas.networkhelm.ui.sync

import platform.UIKit.UIDevice

internal actual val syncPlatform: String = "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}"

internal actual suspend fun syncDeviceName(): String = UIDevice.currentDevice.name
