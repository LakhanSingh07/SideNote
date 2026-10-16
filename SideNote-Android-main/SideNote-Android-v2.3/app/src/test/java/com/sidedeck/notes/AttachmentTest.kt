package com.sidedeck.notes
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileOutputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AttachmentTest {
 private lateinit var context:Context
 @Before fun setup(){context=ApplicationProvider.getApplicationContext();context.getSharedPreferences("side_notes",0).edit().clear().commit();context.getSharedPreferences("sidenote_sync",0).edit().clear().commit()}
 @Test fun hashAndCompleteAResumedBinaryDownload(){
  val bytes=ByteArray(AttachmentStore.CHUNK_SIZE){(it%251).toByte()}
  val digest=MessageDigest.getInstance("SHA-256");repeat(3){digest.update(bytes)}
  val hash=digest.digest().joinToString(""){"%02x".format(it)}
  val file=NoteAttachment("binary-test","File.unknown","application/octet-stream",bytes.size*3L,hash)
  val partial=AttachmentStore.path(context,hash,true);FileOutputStream(partial).use{it.write(bytes)}
  FileOutputStream(partial,true).use{out->repeat(2){out.write(bytes)}}
  AttachmentStore.finishDownload(context,file)
  assertTrue(AttachmentStore.available(context,file));assertEquals(hash,AttachmentStore.hash(AttachmentStore.path(context,hash)))
 }
 @Test fun corruptPartialIsNotPublished(){
  val hash="a".repeat(64);val file=NoteAttachment("bad","bad.any","application/octet-stream",3,hash)
  FileOutputStream(AttachmentStore.path(context,hash,true)).use{it.write(byteArrayOf(1,2,3))}
  assertThrows(IllegalArgumentException::class.java){AttachmentStore.finishDownload(context,file)}
  assertFalse(AttachmentStore.available(context,file))
 }
 @Test fun rejectPathsOutsidePrivateBlobDirectory(){assertThrows(IllegalArgumentException::class.java){AttachmentStore.path(context,"../../outside")}}
 @Test fun emptyFileCompletesWithCorrectHash(){
  val hash=MessageDigest.getInstance("SHA-256").digest().joinToString(""){"%02x".format(it)}
  val file=NoteAttachment("empty","empty.any","application/octet-stream",0,hash)
  FileOutputStream(AttachmentStore.path(context,hash,true)).close();AttachmentStore.finishDownload(context,file);assertTrue(AttachmentStore.available(context,file))
 }
 @Test fun attachmentScreenUsesSystemPickerForAllFileTypes(){
  val store=NotesStore(context);val note=store.fresh();store.saveDirect(note)
  val controller=Robolectric.buildActivity(AttachmentActivity::class.java,Intent(context,AttachmentActivity::class.java).putExtra("id",note.id)).create().start().resume()
  val activity=controller.get()
  fun find(view:View):TextView? {if(view is TextView && view.text.toString()=="Add files")return view;if(view is ViewGroup)for(i in 0 until view.childCount){find(view.getChildAt(i))?.let{return it}};return null}
  find(activity.window.decorView)!!.performClick()
  val intent=Shadows.shadowOf(activity).nextStartedActivityForResult.intent
  assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.action);assertEquals("*/*",intent.type);assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false))
  controller.pause().stop().destroy()
 }

 @Test fun unchangedAttachmentRowsKeepTheirViewsBetweenPolls(){
  val store=NotesStore(context);val note=store.fresh();store.saveDirect(note)
  val controller=Robolectric.buildActivity(AttachmentActivity::class.java,Intent(context,AttachmentActivity::class.java).putExtra("id",note.id)).create().start().resume()
  val activity=controller.get();val looper=Shadows.shadowOf(android.os.Looper.getMainLooper());looper.idle()
  fun find(view:View):TextView?{if(view is TextView&&view.text.toString().startsWith("Attach a photo"))return view;if(view is ViewGroup)for(i in 0 until view.childCount){find(view.getChildAt(i))?.let{return it}};return null}
  val original=find(activity.window.decorView);assertNotNull(original)
  looper.idleFor(java.time.Duration.ofSeconds(2));assertSame(original,find(activity.window.decorView));controller.pause().stop().destroy()
 }
 @Test fun attachmentScreenRespectsSystemBarInsets(){
  val store=NotesStore(context);val note=store.fresh();store.saveDirect(note)
  val controller=Robolectric.buildActivity(AttachmentActivity::class.java,Intent(context,AttachmentActivity::class.java).putExtra("id",note.id)).create().start().resume()
  val root=(controller.get().findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
  val insets=android.view.WindowInsets.Builder().setInsets(android.view.WindowInsets.Type.systemBars(),android.graphics.Insets.of(0,90,0,60)).build()
  root.dispatchApplyWindowInsets(insets);assertTrue(root.paddingTop>=90);assertTrue(root.paddingBottom>=60);controller.pause().stop().destroy()
 }
}
