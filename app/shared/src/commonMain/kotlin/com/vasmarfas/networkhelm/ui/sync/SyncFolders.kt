package com.vasmarfas.networkhelm.ui.sync

// a folder the user picked for synchronisation: the bookmark the platform keeps access to it with, and its name
internal class PickedFolder(val place: String, val name: String)

// the desktop, Android and iOS have folders an app keeps access to; a page in a browser has none
internal expect val syncFolders: Boolean

internal expect suspend fun pickSyncFolder(): PickedFolder?
