package com.sidedeck.notes

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

/** Read-only, explicitly granted file access; only verified attachment blobs are exposed. */
class AttachmentProvider : ContentProvider() {
    override fun onCreate()=true
    override fun getType(uri:Uri):String = context?.let {c->NotesStore(c).all().asSequence().flatMap {it.attachments.asSequence()}.firstOrNull {it.sha256==uri.lastPathSegment}?.mimeType} ?: "application/octet-stream"
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        require(mode=="r");val context=context ?: error("Unavailable")
        val file=AttachmentStore.path(context,uri.lastPathSegment ?: "")
        require(file.isFile)
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        val context=context ?: error("Unavailable")
        val attachment=NotesStore(context).all().flatMap {it.attachments}.firstOrNull {it.sha256==uri.lastPathSegment} ?: error("Unknown file")
        val columns=projection ?: arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {addRow(columns.map {when(it){OpenableColumns.DISPLAY_NAME->attachment.name;OpenableColumns.SIZE->attachment.size;else->null}})}
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
}
