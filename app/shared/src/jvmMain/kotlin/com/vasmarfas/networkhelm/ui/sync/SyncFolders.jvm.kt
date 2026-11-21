package com.vasmarfas.networkhelm.ui.sync

import com.vasmarfas.networkhelm.sync.DirectoryFolder
import com.vasmarfas.networkhelm.sync.Folder
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.exceptions.FileKitException
import io.github.vinceglb.filekit.fromBookmarkData
import kotlin.io.encoding.Base64

// a folder moved or removed since it was picked is not there: the user picks it again
fun bookmarkedFolder(place: String): Folder? = try {
    DirectoryFolder(PlatformFile.fromBookmarkData(Base64.decode(place)).file)
} catch (e: FileKitException) {
    null
}
