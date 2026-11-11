package com.vasmarfas.networkhelm.ui.files

import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.write

internal actual suspend fun saveFile(name: String, bytes: ByteArray): Boolean {
    val target = FileKit.openFileSaver(
        suggestedName = name.substringBeforeLast('.', name),
        defaultExtension = name.substringAfterLast('.', "").ifEmpty { null },
    ) ?: return false
    target.write(bytes)
    return true
}

actual fun Modifier.fileDrop(onDrop: (List<LocalFile>) -> Unit, onHover: (Boolean) -> Unit): Modifier = this

actual fun filesOut(names: List<String>, read: suspend (String) -> ByteArray): DragAndDropTransferData? = null

internal actual val filesDragOut = false

internal actual val transferLimit = 128 * 1024 * 1024L

// a folder of iOS is a security-scoped URL, which the app does not write into
internal actual val folderDialogs = false

internal actual suspend fun pickFolderDialog(): LocalFolder? = null
