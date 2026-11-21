package com.vasmarfas.networkhelm.ui.sync

import com.vasmarfas.networkhelm.sync.FilesFolder
import com.vasmarfas.networkhelm.sync.Folder
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.bookmarkData
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.exceptions.FileKitException
import io.github.vinceglb.filekit.fromBookmarkData
import io.github.vinceglb.filekit.name
import kotlin.io.encoding.Base64

internal actual val syncFolders = true

// a folder of Files, iCloud Drive among them, stays open to the app by its bookmark of security scope
internal actual suspend fun pickSyncFolder(): PickedFolder? = FileKit.openDirectoryPicker()?.let { PickedFolder(Base64.encode(it.bookmarkData().bytes), it.name) }

// a folder moved or removed since it was picked is not there: the user picks it again
fun bookmarkedFolder(place: String): Folder? = try {
    FilesFolder(PlatformFile.fromBookmarkData(Base64.decode(place)).nsUrl)
} catch (e: FileKitException) {
    null
}
