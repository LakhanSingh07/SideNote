package com.sidedeck.notes
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.*
import java.util.concurrent.Executors

class BackupActivity:Activity(){
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var status:TextView
    private lateinit var exportButton:TextView
    private lateinit var importButton:TextView
    private var busy=false
    override fun onCreate(state:Bundle?){super.onCreate(state);Ui.configure(this)
        val root=Ui.column(this).apply {setPadding(48,48,48,48);setBackgroundColor(Ui.surface);fitsSystemWindows=true}
        root.addView(Ui.outline(this,"Back"){if(!busy)finish()});root.addView(Ui.space(this,24));root.addView(Ui.editorial(this,"Keep a little\npeace of mind.",30f));root.addView(Ui.space(this,16))
        root.addView(Ui.text(this,"One portable ZIP with your notes and the actual attached files. No pairing secrets or purchase credentials are included.",14f,false,Ui.muted));root.addView(Ui.space(this,24))
        exportButton=Ui.button(this,"Save complete backup"){
            if(!busy)startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {type="application/zip";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_TITLE,"SideNote-backup.zip")},1)
        };root.addView(exportButton);root.addView(Ui.space(this,12))
        importButton=Ui.outline(this,"Restore a backup"){
            if(!busy)startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {type="application/zip";addCategory(Intent.CATEGORY_OPENABLE)},2)
        };root.addView(importButton);root.addView(Ui.space(this,20))
        root.addView(Ui.text(this,"Restore keeps existing notes. Different versions are restored as separate copies. Files must be downloaded before a complete backup can be saved.",13f,false,Ui.muted));root.addView(Ui.space(this,24))
        status=Ui.text(this,"",14f).apply {accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE};root.addView(status);setContentView(root)
    }
    @Deprecated("Platform activity result") override fun onActivityResult(code:Int,result:Int,data:Intent?){super.onActivityResult(code,result,data);if(result!=RESULT_OK||data?.data==null)return
        val uri=data.data!!;busy=true;exportButton.isEnabled=false;importButton.isEnabled=false;status.text=if(code==1)"Saving notes and files..." else "Checking backup and file integrity..."
        worker.execute {val message=runCatching {if(code==1)"Backup saved with ${BackupArchive.export(this,uri)} files." else "Restored ${BackupArchive.restore(this,uri)} notes. Your existing notes were kept."}.getOrElse {it.message ?: "Backup failed. Existing notes were preserved."}
            runOnUiThread {if(!isDestroyed){busy=false;exportButton.isEnabled=true;importButton.isEnabled=true;status.text=message}}
        }
    }
    override fun onDestroy(){worker.shutdown();super.onDestroy()}
}
