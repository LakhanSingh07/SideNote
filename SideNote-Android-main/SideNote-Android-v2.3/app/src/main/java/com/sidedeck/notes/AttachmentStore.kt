package com.sidedeck.notes

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class NoteAttachment(val id: String, val name: String, val mimeType: String, val size: Long, val sha256: String) {
    fun toJson() = JSONObject().put("id",id).put("name",name).put("mimeType",mimeType).put("size",size).put("sha256",sha256)
    companion object {
        fun fromJson(o: JSONObject): NoteAttachment {
            val file = NoteAttachment(o.getString("id"),o.getString("name"),o.optString("mimeType","application/octet-stream"),o.getLong("size"),o.getString("sha256"))
            require(file.id.length in 1..128 && file.name.length in 1..1024 && file.mimeType.length <= 255 && file.size >= 0 && file.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid attachment" }
            return file
        }
        fun fromArray(a: JSONArray?): List<NoteAttachment> = if(a == null) emptyList() else {
            require(a.length() <= 1000) { "Too many attachments in a note" }
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }.also { require(it.map { f -> f.id }.distinct().size == it.size) }
        }
        fun toArray(files: List<NoteAttachment>) = JSONArray().also { a -> files.forEach { a.put(it.toJson()) } }
    }
}

object AttachmentStore {
    const val CHUNK_SIZE = 512 * 1024
    fun directory(context: Context) = File(context.filesDir,"attachments").apply { check(isDirectory || mkdirs()) }
    fun path(context: Context, hash: String, partial: Boolean = false): File {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return File(directory(context),hash + if(partial) ".part" else ".bin")
    }
    fun available(context: Context, file: NoteAttachment) = path(context,file.sha256).let { it.isFile && it.length() == file.size }
    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input -> val buffer=ByteArray(CHUNK_SIZE); while(true){val n=input.read(buffer); if(n<0)break; digest.update(buffer,0,n)} }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun import(context: Context, uri: Uri, progress: (Long)->Unit = {}): NoteAttachment {
        var name = "File"; var sizeHint = -1L
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c ->
            if(c.moveToFirst()) { name=c.getString(0)?.takeIf { it.isNotBlank() } ?: name; if(!c.isNull(1))sizeHint=c.getLong(1) }
        }
        if(sizeHint > 0) require(directory(context).usableSpace > sizeHint) { "Not enough free storage" }
        val temp = File(directory(context),UUID.randomUUID().toString()+".import")
        val digest=MessageDigest.getInstance("SHA-256");var copied=0L
        try {
            val input=context.contentResolver.openInputStream(uri) ?: error("Cannot read this file")
            input.use { source -> FileOutputStream(temp).use { target ->
                val buffer=ByteArray(CHUNK_SIZE)
                while(true){val count=source.read(buffer);if(count<0)break;target.write(buffer,0,count);digest.update(buffer,0,count);copied+=count;progress(copied)}
                target.fd.sync()
            } }
            val hash=digest.digest().joinToString("") { "%02x".format(it) }
            val destination=path(context,hash)
            if(destination.exists()) { require(destination.length()==copied && this.hash(destination)==hash) { "Existing file failed verification" }; check(temp.delete()) }
            else check(temp.renameTo(destination)) { "Could not store file" }
            return NoteAttachment(UUID.randomUUID().toString(),name.take(1024),context.contentResolver.getType(uri) ?: "application/octet-stream",copied,hash)
        } finally { if(temp.exists())temp.delete() }
    }
    fun finishDownload(context: Context, file: NoteAttachment) {
        val partial=path(context,file.sha256,true)
        require(partial.length()==file.size && hash(partial)==file.sha256) { "File integrity check failed; retry transfer" }
        val destination=path(context,file.sha256)
        check(!destination.exists() || destination.delete())
        check(partial.renameTo(destination)) { "Could not finish download" }
    }
}
