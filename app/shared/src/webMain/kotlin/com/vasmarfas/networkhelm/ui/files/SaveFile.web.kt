package com.vasmarfas.networkhelm.ui.files

import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.download

internal actual suspend fun saveFile(name: String, bytes: ByteArray): Boolean {
    FileKit.download(bytes, name)
    return true
}

actual fun Modifier.fileDrop(onDrop: (List<LocalFile>) -> Unit, onHover: (Boolean) -> Unit): Modifier = this

actual fun filesOut(names: List<String>, read: suspend (String) -> ByteArray): DragAndDropTransferData? = null

internal actual val filesDragOut = false

// the gateway carries a file as base64 inside one message of 128 MiB at the most
internal actual val transferLimit = 64 * 1024 * 1024L

internal actual val folderDialogs = false

internal actual suspend fun pickFolderDialog(): LocalFolder? = null
