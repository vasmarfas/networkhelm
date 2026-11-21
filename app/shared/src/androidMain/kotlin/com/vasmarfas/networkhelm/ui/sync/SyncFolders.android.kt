package com.vasmarfas.networkhelm.ui.sync

import android.content.Context
import com.vasmarfas.networkhelm.sync.DocumentFolder
import com.vasmarfas.networkhelm.sync.Folder
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.exceptions.FileKitException
import io.github.vinceglb.filekit.fromBookmarkData
import kotlin.io.encoding.Base64

// the tree of documents the user picked, by the bookmark that holds its permission; one moved or removed since is not there, and the
// user picks it again
fun bookmarkedFolder(context: Context, place: String): Folder? = try {
    (PlatformFile.fromBookmarkData(Base64.decode(place)).androidFile as? AndroidFile.UriWrapper)?.let { DocumentFolder(context, it.uri) }
} catch (e: FileKitException) {
    null
}
