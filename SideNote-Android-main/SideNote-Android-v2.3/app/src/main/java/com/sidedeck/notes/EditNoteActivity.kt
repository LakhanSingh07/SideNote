package com.sidedeck.notes

import android.app.Activity
import android.os.Bundle

/** Standard Activity hosts the edit dialog so the keyboard works reliably. */
class EditNoteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store=NotesStore(this)
        val id=intent.getStringExtra("id")
        val note=store.all().firstOrNull { it.id==id } ?: store.fresh()
        NoteEditor.show(this,store,note) { finish() }
        // Explicit finish is handled by the dialog's completion; cancellation also returns to the app.
    }
}
