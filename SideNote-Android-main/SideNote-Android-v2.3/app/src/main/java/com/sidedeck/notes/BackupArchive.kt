package com.sidedeck.notes
import android.content.Context
import android.net.Uri
import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import java.util.UUID
import java.util.zip.*

/** Portable ZIP64 backup: notes.json + files/<sha256>.bin. No credentials are exported. */
object BackupArchive {
    fun export(context:Context,uri:Uri):Int {
        val notes=NotesStore(context).all();val files=notes.flatMap {it.attachments}.distinctBy {it.sha256}
        for(file in files)require(AttachmentStore.available(context,file)){"Download ${file.name} before making a complete backup"}
        val manifest=JSONObject().put("format","SideNoteBackup").put("version",1).put("notes",JSONArray().apply {notes.forEach {put(SyncStore.noteToJson(it))}}).toString().toByteArray()
        require(manifest.size<=16000000){"Backup manifest is too large"}
        ZipOutputStream(BufferedOutputStream(context.contentResolver.openOutputStream(uri) ?: error("Cannot save here"))).use {zip->
            zip.putNextEntry(ZipEntry("notes.json"));zip.write(manifest);zip.closeEntry()
            for(file in files){zip.putNextEntry(ZipEntry("files/${file.sha256}.bin"));AttachmentStore.path(context,file.sha256).inputStream().use {it.copyTo(zip,AttachmentStore.CHUNK_SIZE)};zip.closeEntry()}
        };return files.size
    }
    fun restore(context:Context,uri:Uri):Int {
        val stage=File(context.cacheDir,"restore-${UUID.randomUUID()}").apply {check(mkdir())}
        try {
            val archive=File(stage,"backup.zip")
            context.contentResolver.openInputStream(uri)?.use {input->archive.outputStream().use {input.copyTo(it,AttachmentStore.CHUNK_SIZE)}} ?: error("Cannot read this file")
            ZipFile(archive).use {zip->
                require(zip.size()<=10001){"Too many backup entries"}
                val manifestEntry=zip.getEntry("notes.json") ?: error("Not a SideNote backup")
                require(manifestEntry.size in 0..16000000){"Manifest is too large"}
                val raw=zip.getInputStream(manifestEntry).use {it.readBytesLimited(16000000)}
                val manifest=JSONObject(String(raw,Charsets.UTF_8));require(manifest.getString("format")=="SideNoteBackup"&&manifest.getInt("version")==1){"Unsupported backup"}
                val array=manifest.getJSONArray("notes");require(array.length()<=10000)
                val notes=(0 until array.length()).map {SyncStore.jsonToNote(array.getJSONObject(it))}
                require(notes.map {it.id}.toSet().size==notes.size)
                for(note in notes)require(note.id.length in 1..128&&note.title.length<=2000&&note.body.length<=200000&&note.color in 0..5){"Invalid backup note"}
                val allFiles=notes.flatMap {it.attachments}
                require(allFiles.groupBy {it.sha256}.values.all {group->group.map {it.size}.distinct().size==1}){"Conflicting backup file sizes"}
                val files=allFiles.distinctBy {it.sha256}
                for(file in files){
                    val entry=zip.getEntry("files/${file.sha256}.bin") ?: error("Backup is missing ${file.name}")
                    require(entry.size==file.size){"Incorrect backup file size"}
                    val target=File(stage,file.sha256)
                    zip.getInputStream(entry).use {input->target.outputStream().use {output->
                        var total=0L;val buffer=ByteArray(AttachmentStore.CHUNK_SIZE)
                        while(true){val count=input.read(buffer);if(count<0)break;total+=count;require(total<=file.size){"File exceeds its declared size"};output.write(buffer,0,count)}
                        require(total==file.size)
                    }}
                    require(AttachmentStore.hash(target)==file.sha256){"Backup file integrity check failed"}
                }
                for(file in files){val target=AttachmentStore.path(context,file.sha256);if(!AttachmentStore.available(context,file)||AttachmentStore.hash(target)!=file.sha256)java.nio.file.Files.move(File(stage,file.sha256).toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)}
                val store=NotesStore(context);val current=store.all().associateBy {it.id};val imported=JSONArray()
                for(note in notes){
                    val existing=current[note.id]
                    if(existing!=null&&existing.copy(updatedAt=note.updatedAt,baseUpdatedAt=note.baseUpdatedAt)==note)continue
                    val restored=if(existing==null)note else note.copy(id=UUID.randomUUID().toString(),title=note.title.take(1980)+" (restored)",baseUpdatedAt=null)
                    imported.put(SyncStore.noteToJson(restored))
                }
                store.importJson(imported.toString());return imported.length()
            }
        }finally{stage.deleteRecursively()}
    }
    private fun InputStream.readBytesLimited(limit:Int):ByteArray {
        val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val n=read(buffer);if(n<0)break;require(output.size()+n<=limit){"Manifest too large"};output.write(buffer,0,n)};return output.toByteArray()
    }
}
