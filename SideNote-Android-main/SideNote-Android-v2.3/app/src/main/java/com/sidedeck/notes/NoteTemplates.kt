package com.sidedeck.notes
import android.content.Context
import android.app.AlertDialog
object NoteTemplates {
    data class Template(val title:String,val body:String,val color:Int)
    val all=listOf(
        Template("Study corner","## Today\n- [ ] Pick one topic\n- [ ] Add reading materials in Files\n- [ ] Write three things I learned\n\n## Little discoveries\n",3),
        Template("Project handoff","## A little context\nWhat we are making and why.\n\n## Next steps\n- [ ] Add the latest files\n- [ ] Write the next action\n- [ ] Share with my paired device\n",4),
        Template("Trip pocket","## The plan\nWhere, when, and a few things to look forward to.\n\n## Keep close\n- [ ] Tickets and bookings in Files\n- [ ] Packing list\n- [ ] Places to try\n",1),
        Template("Little wins","## One good thing today\n\n## A tiny next step\n- [ ] Something kind for future me\n",5)
    )
    fun choose(context:Context,store:NotesStore,done:()->Unit){
        AlertDialog.Builder(context).setTitle("A little inspiration").setItems(all.map {it.title}.toTypedArray()){_,index->
            val template=all[index];NoteEditor.show(context,store,store.fresh().copy(title=template.title,body=template.body,color=template.color,isChecklist=true),done)
        }.setNegativeButton("Cancel",null).show()
    }
    fun badge(note:Note):String = when {
        note.attachments.isNotEmpty()->"${note.attachments.size} files"
        note.isChecklist->"${Regex("(?m)^\\s*[-*]?\\s*\\[ \\]").findAll(note.body).count()} tasks left"
        note.reminderAt>0->"Reminder"
        note.pinned->"Pinned"
        else->"Note"
    }
}
