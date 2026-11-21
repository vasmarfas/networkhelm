package com.vasmarfas.networkhelm.ui.sync

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.bookmarkData
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.name
import kotlin.io.encoding.Base64

internal actual val syncFolders = true

// on Android the bookmark takes the permission of the tree for good, in the sandbox of macOS a bookmark of security scope
internal actual suspend fun pickSyncFolder(): PickedFolder? = FileKit.openDirectoryPicker()?.let { PickedFolder(Base64.encode(it.bookmarkData().bytes), it.name) }
