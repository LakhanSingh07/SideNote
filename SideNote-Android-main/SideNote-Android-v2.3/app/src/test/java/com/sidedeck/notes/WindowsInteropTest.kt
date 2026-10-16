package com.sidedeck.notes

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.*
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/** Opt-in real Windows interoperability test; Android runs under Robolectric, not on a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WindowsInteropTest {
 @Test fun resumedFilesAndEditsTravelBothDirections() {
  val path=System.getenv("SIDENOTE_INTEROP_SESSION");assumeTrue(!path.isNullOrBlank())
  val sessionFile=File(path!!);val config=JSONObject(sessionFile.readText())
  val context=ApplicationProvider.getApplicationContext<Context>()
  context.getSharedPreferences("side_notes",0).edit().clear().commit()
  val prefs=context.getSharedPreferences("sidenote_sync",0);prefs.edit().clear().putString("device_id",config.getString("deviceId")).commit()
  val store=NotesStore(context);val sync=SyncStore(context)
  val fileSize=config.getLong("fileSize");val buffer=ByteArray(AttachmentStore.CHUNK_SIZE){0xa5.toByte()}
  val raw=File(context.cacheDir,"interop.android.any")
  FileOutputStream(raw).use {out->var remaining=fileSize;while(remaining>0){val n=minOf(buffer.size.toLong(),remaining).toInt();out.write(buffer,0,n);remaining-=n}}
  val hash=AttachmentStore.hash(raw);val androidFile=NoteAttachment(config.getString("androidFileId"),"Android archive.anytype","application/octet-stream",fileSize,hash)
  val source=AttachmentStore.path(context,hash);if(source.exists())source.delete();check(raw.renameTo(source))
  store.save(Note(config.getString("androidNoteId"),"Temporary Android transfer test","Android-created",2,false,System.currentTimeMillis(),attachments=listOf(androidFile)))
  Thread.sleep(150);Shadows.shadowOf(Looper.getMainLooper()).idle()
  val deviceId=config.getString("deviceId");val pcId=config.getString("pcId");val token=config.getString("token");val baseline=config.getLong("baselineCursor")
  fun call(request:JSONObject,data:ByteArray?=null):JSONObject=Socket("127.0.0.1",43861).use {socket->socket.soTimeout=60000;val output=DataOutputStream(socket.getOutputStream());val input=DataInputStream(socket.getInputStream());val bytes=request.toString().toByteArray();output.writeInt(bytes.size);output.write(bytes);if(data!=null)output.write(data);output.flush();val length=input.readInt();require(length in 1..6000000);val reply=ByteArray(length);input.readFully(reply);JSONObject(String(reply))}
  val first=call(JSONObject().put("type","SYNC_REQUEST").put("deviceId",deviceId).put("token",token).put("ackCursor",baseline).put("bundle",sync.exportBundle()))
  assertEquals("ok",first.getString("status"));sync.advanceUploadCursor(pcId,first.getLong("receivedCursor"));sync.advancePeerCursor(pcId,baseline)
  val put=JSONObject().put("type","FILE_REQUEST").put("op","put").put("deviceId",deviceId).put("token",token).put("noteId",config.getString("androidNoteId")).put("attachmentId",androidFile.id).put("offset",0).put("length",buffer.size)
  val partialUpload=call(put,buffer);assertEquals(buffer.size.toLong(),partialUpload.getLong("bytes"));assertFalse(partialUpload.getBoolean("complete"))
  val windowsHash=config.getString("windowsHash");val windowsPartial=AttachmentStore.path(context,windowsHash,true)
  FileOutputStream(windowsPartial).use{it.write(ByteArray(AttachmentStore.CHUNK_SIZE){0x3c.toByte()})}
  prefs.edit().putString("auth_token",token).putString("paired_device_id",pcId).putString("last_pc_ip","127.0.0.1").commit()
  fun syncNow(){
   val deadline=System.currentTimeMillis()+120000
   while(true){
    val outcome=AtomicReference<Pair<Boolean,String>?>(null);LanSyncManager.triggerSync(context){ok,message->outcome.set(ok to message)}
    while(outcome.get()==null && System.currentTimeMillis()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(20)}
    val result=outcome.get() ?: error("Sync timed out")
    if(!result.first && result.second=="Sync queued"){Thread.sleep(100);continue}
    assertTrue(result.second,result.first);break
   }
  }
  syncNow()
  val downloaded=store.all().first{it.id==config.getString("windowsNoteId")}.attachments.single()
  assertEquals(fileSize,AttachmentStore.path(context,windowsHash).length());assertEquals(windowsHash,AttachmentStore.hash(AttachmentStore.path(context,windowsHash)))
  assertTrue(AttachmentStore.available(context,downloaded))
  val remote=store.all().first{it.id==config.getString("windowsNoteId")};store.save(remote.copy(body="Edited on Android"));syncNow()
  val stage=File(sessionFile.parentFile,"interop-stage.json");stage.writeText(JSONObject().put("stage","androidEdited").toString())
  val deadline=System.currentTimeMillis()+60000
  while(System.currentTimeMillis()<deadline){if(runCatching{JSONObject(stage.readText()).optString("stage")=="pcEdited"}.getOrDefault(false))break;Thread.sleep(100)}
  assertEquals("pcEdited",JSONObject(stage.readText()).getString("stage"));syncNow()
  assertEquals("Edited on Windows",store.all().first{it.id==remote.id}.body)
  assertTrue(sync.listConflicts().isEmpty())
  store.delete(remote.id);syncNow()
  stage.writeText(JSONObject().put("stage","complete").put("uploadHash",hash).put("downloadHash",windowsHash).put("bytesEachDirection",fileSize).toString())
 }
}
