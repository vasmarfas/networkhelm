package com.vasmarfas.networkhelm.ui.files

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size

class LocalFile(val name: String, val size: Long? = null, val read: suspend () -> ByteArray)

class LocalFolder(val name: String, val files: List<LocalFile>, val write: suspend (String, ByteArray) -> Unit)

interface FileExchange {
    suspend fun pick(): List<LocalFile>

    suspend fun save(name: String, bytes: ByteArray): Boolean

    val folders: Boolean get() = false

    suspend fun pickFolder(): LocalFolder? = null
}

val LocalFileExchange = staticCompositionLocalOf<FileExchange> { DialogFileExchange }

private object DialogFileExchange : FileExchange {
    override suspend fun pick() = FileKit.openFilePicker(mode = FileKitMode.Multiple()).orEmpty().map { file -> LocalFile(file.name, file.size().takeIf { it >= 0 }) { file.readBytes() } }

    override suspend fun save(name: String, bytes: ByteArray) = saveFile(name, bytes)

    override val folders = folderDialogs

    override suspend fun pickFolder() = pickFolderDialog()
}

internal expect suspend fun saveFile(name: String, bytes: ByteArray): Boolean

internal expect val folderDialogs: Boolean

internal expect suspend fun pickFolderDialog(): LocalFolder?

expect fun Modifier.fileDrop(onDrop: (List<LocalFile>) -> Unit, onHover: (Boolean) -> Unit): Modifier

fun Modifier.fileDrop(onDrop: (List<LocalFile>) -> Unit): Modifier = fileDrop(onDrop) {}

expect fun filesOut(names: List<String>, read: suspend (String) -> ByteArray): DragAndDropTransferData?

// on a touch screen the drag source of Compose takes the press of a finger, and the rows stop answering taps
internal expect val filesDragOut: Boolean

// a transfer holds the whole file in memory, and reading one takes up to three times its size
internal expect val transferLimit: Long
