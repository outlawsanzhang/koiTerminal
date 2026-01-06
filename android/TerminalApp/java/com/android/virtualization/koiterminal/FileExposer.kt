/*
 * Copyright (C) 2026 OSZ
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.virtualization.koiterminal

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import android.util.Log
import com.android.virtualization.koiterminal.MainActivity.Companion.TAG
import androidx.annotation.WorkerThread
import androidx.annotation.UiThread
import java.io.File
import java.io.FileNotFoundException

class FileExposer : DocumentsProvider() {
    companion object {
        val DEFAULT_ROOT_PROJECTION: Array<String> = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        )
        val DEFAULT_DOCUMENT_PROJECTION: Array<String> = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
        )
        const val ROOT_FILES_ID = "ROOT_FILES"
        // const val ROOT_FILES_DOCUMENT_ID = "ROOTDIR_FILES"
        const val BIN_TYPE = "application/octet-stream"
        const val TAG: String = "VmTerminalApp.FileExposer"
        const val AUTHORITY = "com.android.virtualization.koiterminal.documents"
    }
    
    @UiThread
    override fun onCreate(): Boolean {
        Log.i(TAG, "onCreate")
        return true
    }
    
    @WorkerThread
    fun resolveProjection(projection: Array<out String>?): Array<out String> {
        return projection ?: DEFAULT_DOCUMENT_PROJECTION
    }

    @WorkerThread
    override fun queryRoots(projection: Array<out String>?): Cursor {
        Log.i(TAG, "queryRoots")
        return MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
            .apply {
                val row = newRow()
                with(row) {
                    add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_FILES_ID)
                    add(DocumentsContract.Root.COLUMN_ICON, R.drawable.ic_launcher_foreground)
                    add(DocumentsContract.Root.COLUMN_TITLE, context?.getString(R.string.app_name) ?: "koiTerminal")
                    add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE)
                    add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ".")
                }
            }
    }

    fun buildUri(documentId: String): Uri {
        return DocumentsContract.buildChildDocumentsUri(AUTHORITY, documentId)
    }

    fun documentIdToParent(documentId: String): String {
        return File(documentId).getParentFile()?.getPath() ?: "."
    }

    fun buildParentUri(documentId: String): Uri {
        return buildUri(documentIdToParent(documentId))
    }

    @WorkerThread
    fun addFileToCursor(documentId: String, cursor: MatrixCursor) {
        Log.i(TAG, "addFileToCursor: $documentId")
        val context = context
        if (context == null) return
        val file = File(context.filesDir, documentId)
        val fileName = file.getName()
        val isDir = file.isDirectory()
        val isRoot = file.toPath().toRealPath() == context.filesDir.toPath().toRealPath()
        with(cursor.newRow()) {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            val mime: String = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR
                else MimeTypeMap.getFileExtensionFromUrl(fileName.replace(":", "-"))?.let {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
                } ?: BIN_TYPE
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, mime)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, if (isRoot) R.string.app_name else fileName)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
            val flags = if (isRoot) {
                    DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                } else if (isDir) {
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                    DocumentsContract.Document.FLAG_SUPPORTS_REMOVE or
                    DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                } else {
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                    DocumentsContract.Document.FLAG_SUPPORTS_REMOVE or
                    DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                }
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
            val length = if (isDir) 0 else file.length()
            add(DocumentsContract.Document.COLUMN_SIZE, length)
        }
    }

    @WorkerThread
    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        Log.i(TAG, "queryChildDocuments: $parentDocumentId")
        return MatrixCursor(resolveProjection(projection)).apply {
            val context = context
            if (context == null || parentDocumentId == null) return@apply
            val parent = File(context.filesDir, parentDocumentId)
            parent.listFiles()?.forEach { file ->
                addFileToCursor(File(parentDocumentId, file.getName()).getPath(), this)
            }
            Log.i(TAG, "queryChildDocuments - done, ${this.getCount()} rows, ${this.getColumnCount()} columns")
            setNotificationUri(context.getContentResolver(), buildUri(parentDocumentId))
        }
    }

    @WorkerThread
    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        Log.i(TAG, "queryDocument: $documentId")
        return MatrixCursor(resolveProjection(projection)).apply {
            val context = context
            if (context == null || documentId == null) return@apply
            addFileToCursor(documentId, this)
            Log.i(TAG, "queryDocument - done, ${this.getCount()} rows, ${this.getColumnCount()} columns")
            setNotificationUri(context.getContentResolver(), buildUri(documentId))
        }
    }

    @WorkerThread
    override fun openDocument(documentId: String?, mode: String?, signal: CancellationSignal?):
        ParcelFileDescriptor {
        Log.i(TAG, "openDocument, mode $mode: $documentId")
        val context = context
        if (context == null || documentId == null || mode == null) throw FileNotFoundException(documentId)
        val file = File(context.filesDir, documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        // val isWrite = mode.contains("w")
        context.getContentResolver().notifyChange(buildParentUri(documentId), null)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    @WorkerThread
    override fun createDocument(parentDocumentId: String?, mimeType: String?, displayName: String?): String {
        Log.i(TAG, "createDocument: $parentDocumentId -> $displayName")
        val context = context
        if (context == null || parentDocumentId == null || displayName == null){
            throw FileNotFoundException("$parentDocumentId -> $displayName")
        }
        val parent = File(context.filesDir, parentDocumentId)
        val file = File(parent, displayName)
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) file.mkdir()
        else file.createNewFile()
        context.getContentResolver().notifyChange(buildUri(parentDocumentId), null)
        return File(parentDocumentId, displayName).getPath()
    }

    @WorkerThread
    override fun deleteDocument(documentId: String?) {
        Log.i(TAG, "deleteDocument: $documentId")
        if (documentId == null) return
        removeDocument(documentId, null)
    }

    @WorkerThread
    override fun removeDocument(documentId: String?, parentDocumentId: String?) {
        Log.i(TAG, "removeDocument: $documentId")
        context?.let {
            if (documentId == null) return
            var file = File(it.filesDir, documentId)
            if (file.isDirectory()) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
            it.getContentResolver().notifyChange(buildUri(parentDocumentId ?: documentIdToParent(documentId)), null)
        }
    }
    
    @WorkerThread
    override fun renameDocument(documentId: String?, displayName: String?): String? {
        Log.i(TAG, "renameDocument: $documentId -> $displayName")
        context?.let {
            if (documentId == null || displayName == null) return documentId
            var file = File(it.filesDir, documentId)
            var documentNewId = File(documentIdToParent(documentId), displayName).getPath()
            if (file.renameTo(File(it.filesDir, documentNewId))) {
                it.getContentResolver().notifyChange(buildParentUri(documentId), null)
                return documentNewId
            }
        }
        return documentId
    }
    
}

