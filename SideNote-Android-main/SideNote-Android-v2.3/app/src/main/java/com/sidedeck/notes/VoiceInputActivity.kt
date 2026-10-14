package com.sidedeck.notes

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import java.util.Locale

class VoiceInputActivity : Activity() {
    companion object {
        const val EXTRA_TARGET_NOTE_ID = "target_note_id"
        const val EXTRA_APPEND_MODE = "append_mode"
        const val EXTRA_EDITOR_ACTIVE = "editor_active"
        const val ACTION_VOICE_RESULT = "com.sidedeck.notes.ACTION_VOICE_RESULT"
        const val EXTRA_RESULT_TEXT = "result_text"
        private const val REQ_SPEECH = 901
    }

    private var targetNoteId: String? = null
    private var isAppend: Boolean = false
    private var isEditorActive: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetNoteId = intent.getStringExtra(EXTRA_TARGET_NOTE_ID)
        isAppend = intent.getBooleanExtra(EXTRA_APPEND_MODE, false)
        isEditorActive = intent.getBooleanExtra(EXTRA_EDITOR_ACTIVE, false)

        try {
            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak in any language (Hindi / English / Any)…")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-IN", "en-US", "hi"))
            }
            startActivityForResult(speechIntent, REQ_SPEECH)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice recognition not available on this device", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK) {
            val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull()?.trim().orEmpty()
            if (spokenText.isNotBlank()) {
                if (isEditorActive) {
                    // Active editor is managing the live document buffer; broadcast directly
                    sendBroadcast(Intent(ACTION_VOICE_RESULT).apply {
                        putExtra(EXTRA_RESULT_TEXT, spokenText)
                        setPackage(packageName)
                    })
                    Toast.makeText(this, "Speech inserted into editor", Toast.LENGTH_SHORT).show()
                } else {
                    val store = NotesStore(this)
                    val noteId = targetNoteId
                    if (!noteId.isNullOrBlank()) {
                        val existing = store.all().firstOrNull { it.id == noteId }
                        if (existing != null) {
                            val space = if (existing.body.isNotBlank() && !existing.body.endsWith(" ") && !existing.body.endsWith("\n")) " " else ""
                            val updated = existing.copy(body = "${existing.body}$space$spokenText")
                            store.save(updated)
                            Toast.makeText(this, "Voice note updated", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val words = spokenText.split(" ")
                        val titleStr = if (words.size <= 5) spokenText else words.take(5).joinToString(" ")
                        val freshNote = store.fresh().copy(title = titleStr, body = spokenText)
                        store.save(freshNote)
                        Toast.makeText(this, "Voice note created: \"$titleStr\"", Toast.LENGTH_SHORT).show()
                    }

                    // Broadcast result so open editors can insert live
                    sendBroadcast(Intent(ACTION_VOICE_RESULT).apply {
                        putExtra(EXTRA_RESULT_TEXT, spokenText)
                        setPackage(packageName)
                    })

                    // Notify OverlayService to reload
                    runCatching {
                        startService(Intent(this, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_REFRESH_APPEARANCE
                        })
                    }
                }
            }
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        overridePendingTransition(0, 0)
    }
}
