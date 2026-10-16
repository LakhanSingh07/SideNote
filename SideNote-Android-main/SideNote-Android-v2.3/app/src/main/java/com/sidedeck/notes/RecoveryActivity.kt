package com.sidedeck.notes
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import org.json.JSONObject

class RecoveryActivity:Activity(){
    private var conflicts=false
    private lateinit var rows:LinearLayout
    override fun onCreate(state:Bundle?){super.onCreate(state);Ui.configure(this)
        val root=Ui.column(this).apply {setPadding(48,48,48,48);setBackgroundColor(Ui.surface);fitsSystemWindows=true}
        root.addView(Ui.outline(this,"Back"){finish()});root.addView(Ui.space(this,20));root.addView(Ui.editorial(this,"Nothing lost.",30f))
        val tabs=Ui.row(this);tabs.addView(Ui.secondary(this,"Trash"){conflicts=false;render()},LinearLayout.LayoutParams(0,-2,1f));tabs.addView(Ui.secondary(this,"Review sync"){conflicts=true;render()},LinearLayout.LayoutParams(0,-2,1f));root.addView(Ui.space(this,16));root.addView(tabs)
        rows=Ui.column(this);root.addView(ScrollView(this).apply {addView(rows)},LinearLayout.LayoutParams(-1,0,1f));setContentView(root);render()
    }
    private fun render(){rows.removeAllViews();val store=NotesStore(this)
        if(!conflicts){
            val entries=store.trash();if(entries.isEmpty())rows.addView(Ui.emptyState(this,"Trash is empty","Deleted notes can be restored here."))
            for(entry in entries){val box=Ui.surfaceCard(this);box.addView(Ui.text(this,entry.note.title.ifBlank {"Untitled note"},17f,true));box.addView(Ui.text(this,entry.note.body.take(160),13f,false,Ui.muted));box.addView(Ui.space(this,12))
                val buttons=Ui.row(this);buttons.addView(Ui.secondary(this,"Restore"){store.restoreTrash(entry.note.id);render()},LinearLayout.LayoutParams(0,-2,1f))
                buttons.addView(Ui.outline(this,"Delete forever"){
                    AlertDialog.Builder(this).setTitle("Delete forever?").setMessage("This removes the recovery copy of this note.").setNegativeButton("Keep",null).setPositiveButton("Delete"){_,_->store.eraseTrash(entry.note.id);render()}.show()
                });box.addView(buttons);rows.addView(Ui.space(this,12));rows.addView(box)
            }
        }else{
            val sync=SyncStore(this);val entries=sync.listConflicts().groupBy {it.noteId}.values.map {it.last()}
            if(entries.isEmpty())rows.addView(Ui.emptyState(this,"Everything agrees","No sync conflicts need your attention."))
            for(entry in entries){
                val local=store.all().firstOrNull {it.id==entry.noteId};val remote=entry.payload?.let {SyncStore.jsonToNote(JSONObject(it))}
                val box=Ui.surfaceCard(this);box.addView(Ui.text(this,local?.title ?: remote?.title ?: "Deleted note",17f,true))
                box.addView(Ui.text(this,"On this phone",12f,true));box.addView(Ui.text(this,local?.body?.take(300) ?: "Deleted",13f,false,Ui.muted));box.addView(Ui.space(this,12))
                box.addView(Ui.text(this,"From your PC",12f,true));box.addView(Ui.text(this,remote?.body?.take(300) ?: "Deleted",13f,false,Ui.muted));box.addView(Ui.space(this,12))
                box.addView(Ui.secondary(this,"Keep phone version"){sync.resolveConflict(entry.noteId,true);render()})
                box.addView(Ui.outline(this,"Use PC version"){sync.resolveConflict(entry.noteId,false);render()})
                if(remote!=null&&local!=null)box.addView(Ui.outline(this,"Keep both"){
                    store.save(remote.copy(id=java.util.UUID.randomUUID().toString(),title=remote.title.take(1980)+" (PC copy)",baseUpdatedAt=null));sync.resolveConflict(entry.noteId,true);render()
                });rows.addView(Ui.space(this,12));rows.addView(box)
            }
        }
    }
}
