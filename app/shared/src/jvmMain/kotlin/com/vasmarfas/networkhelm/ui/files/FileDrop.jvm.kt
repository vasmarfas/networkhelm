package com.vasmarfas.networkhelm.ui.files

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.fileDrop(onDrop: (List<LocalFile>) -> Unit, onHover: (Boolean) -> Unit): Modifier = dragAndDropTarget(
    shouldStartDragAndDrop = { it.dragData() is DragData.FilesList },
    target = object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) = onHover(true)

        override fun onExited(event: DragAndDropEvent) = onHover(false)

        override fun onEnded(event: DragAndDropEvent) = onHover(false)

        override fun onDrop(event: DragAndDropEvent): Boolean {
            onHover(false)
            // folders are not sent: the router's SFTP takes files one by one
            val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty().map { File(URI(it)) }.filter { it.isFile }
            if (files.isNotEmpty()) onDrop(files.map { file -> LocalFile(file.name, file.length()) { withContext(Dispatchers.IO) { file.readBytes() } } })
            return files.isNotEmpty()
        }
    },
)

@OptIn(ExperimentalComposeUiApi::class)
actual fun filesOut(names: List<String>, read: suspend (String) -> ByteArray): DragAndDropTransferData? =
    DragAndDropTransferData(DragAndDropTransferable(RouterFiles(names, read = read)), listOf(DragAndDropTransferAction.Copy))

internal actual val filesDragOut = true

// stale drag folders go after an hour: the file manager may still be copying from a newer one
internal class RouterFiles(private val names: List<String>, private val limit: Duration = 30.seconds, private val read: suspend (String) -> ByteArray) : Transferable {
    private val files by lazy {
        val prefix = "networkhelm-drag"
        val folder = Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }
        val hourAgo = System.currentTimeMillis() - 3_600_000
        folder.parentFile.listFiles { file -> file.name.startsWith(prefix) && file.isDirectory && file.lastModified() < hourAgo }?.forEach { it.deleteRecursively() }
        // the drop asks for the files on the thread of the interface, which stands still until they are here
        runBlocking { withTimeout(limit) { names.map { name -> File(folder, File(name).name).apply { deleteOnExit(); writeBytes(read(name)) } } } }
    }

    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor

    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return files
    }
}
