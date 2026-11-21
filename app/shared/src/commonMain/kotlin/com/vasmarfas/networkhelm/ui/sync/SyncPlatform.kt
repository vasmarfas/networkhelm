package com.vasmarfas.networkhelm.ui.sync

// the system of this device as the list of devices of the vault shows it
internal expect val syncPlatform: String

// the name the user knows this device by, for the list of devices: the name of the computer, the model of the phone
internal expect suspend fun syncDeviceName(): String
