package com.vasmarfas.networkhelm.ui.files

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

// an eighth of the heap, 128 MiB on the desktop, lets two files of that size be read at once
internal actual val transferLimit = Runtime.getRuntime().maxMemory() / 8
