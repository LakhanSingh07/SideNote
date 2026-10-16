package com.sidedeck.notes
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class BackupArchiveTest {
 private lateinit var c:Context
 @Before fun setup(){c=ApplicationProvider.getApplicationContext();c.getSharedPreferences("side_notes",0).edit().clear().commit();c.getSharedPreferences("sidenote_sync",0).edit().clear().commit()}
 private fun archive(note:Note,bytes:ByteArray?):File {
  val f=File.createTempFile("backup-test-",".zip",c.cacheDir)
  ZipOutputStream(f.outputStream()).use {zip->zip.putNextEntry(ZipEntry("notes.json"));zip.write(JSONObject().put("format","SideNoteBackup").put("version",1).put("notes",JSONArray().put(SyncStore.noteToJson(note))).toString().toByteArray());zip.closeEntry();if(bytes!=null){zip.putNextEntry(ZipEntry("files/${note.attachments.first().sha256}.bin"));zip.write(bytes);zip.closeEntry()}}
  return f
 }
 @Test fun corruptOrMissingFileNeverChangesExistingNotes(){
  val store=NotesStore(c);val original=store.fresh().copy(title="Keep this",body="Untouched");store.saveDirect(original)
  val note=store.fresh().copy(attachments=listOf(NoteAttachment("file","anything.bin","application/octet-stream",3,"b".repeat(64))))
  for(bytes in listOf(null,byteArrayOf(1,2,3))){val zip=archive(note,bytes);try{assertThrows(Exception::class.java){BackupArchive.restore(c,Uri.fromFile(zip))};assertEquals(listOf(original),store.all())}finally{zip.delete()}}
 }
 @Test fun restorePreservesAnExistingNoteWhenOnlyMetadataChanged(){
  val store=NotesStore(c);val original=store.fresh().copy(title="Original",body="Same body",color=1,pinned=false);store.saveDirect(original)
  val zip=archive(original.copy(color=3,pinned=true),null)
  try{assertEquals(1,BackupArchive.restore(c,Uri.fromFile(zip)));val notes=store.all();assertEquals(2,notes.size);assertEquals(original,notes.first {it.id==original.id});assertTrue(notes.any {it.id!=original.id&&it.color==3&&it.pinned})}finally{zip.delete()}
 }
 @Test fun identicalBackupDoesNotDuplicateNotes(){val store=NotesStore(c);val note=store.fresh();store.saveDirect(note);val zip=archive(note,null);try{assertEquals(0,BackupArchive.restore(c,Uri.fromFile(zip)));assertEquals(1,store.all().size)}finally{zip.delete()}}
}
