package com.vasmarfas.networkhelm.sync

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

// a folder picked through the documents of the system: a provider names its documents by ids of its own, so a path is found one name
// at a time, and a file is written in place, since a provider does not move one document over another
class DocumentFolder(private val context: Context, private val tree: Uri) : Folder {
    override suspend fun list(directory: String): List<String> = withContext(Dispatchers.IO) { find(directory)?.let { children(it).keys.toList() }.orEmpty() }

    override suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) {
        find(path)?.let { id -> context.contentResolver.openInputStream(document(id))?.use { it.readBytes() } ?: throw FileNotFoundException(path) }
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val parent = path.substringBeforeLast('/').split('/').fold(DocumentsContract.getTreeDocumentId(tree)) { id, name ->
                children(id)[name] ?: create(id, Document.MIME_TYPE_DIR, name)
            }
            val name = path.substringAfterLast('/')
            val file = children(parent)[name] ?: create(parent, "application/octet-stream", name)
            val descriptor = context.contentResolver.openFileDescriptor(document(file), "wt") ?: throw FileNotFoundException(path)
            // "wt" does not empty a document of every provider
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { stream ->
                stream.channel.truncate(0)
                stream.write(bytes)
            }
        }
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) { find(path)?.let { DocumentsContract.deleteDocument(context.contentResolver, document(it)) } }
    }

    private fun find(path: String): String? =
        path.split('/').fold<String, String?>(DocumentsContract.getTreeDocumentId(tree)) { id, name -> id?.let { children(it)[name] } }

    // the documents under one, by name to id
    private fun children(id: String): Map<String, String> = context.contentResolver.query(
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
        arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> buildMap<String, String> { while (cursor.moveToNext()) put(cursor.getString(1), cursor.getString(0)) } }.orEmpty()

    private fun create(parent: String, type: String, name: String): String =
        DocumentsContract.createDocument(context.contentResolver, document(parent), type, name)?.let(DocumentsContract::getDocumentId) ?: throw FileNotFoundException(name)

    private fun document(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)
}
