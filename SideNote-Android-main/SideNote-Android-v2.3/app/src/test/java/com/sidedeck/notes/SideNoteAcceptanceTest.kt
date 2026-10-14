package com.sidedeck.notes

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SideNoteAcceptanceTest {

    private lateinit var context: Context
    private lateinit var store: NotesStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("side_notes", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("overlay_ui", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("floating_card_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        store = NotesStore(context)
    }

    // 1. Note parsing & note IDs
    @Test
    fun testNoteParsingAndFields() {
        val note = Note(
            id = UUID.randomUUID().toString(),
            title = "Acceptance Test Note",
            body = "This is a body of note",
            color = 3,
            isChecklist = true,
            updatedAt = 1000L,
            pinned = true,
            archived = false,
            reminderAt = 5000L
        )
        store.save(note)

        val retrieved = store.all()
        assertEquals(1, retrieved.size)
        val n = retrieved.first()
        assertEquals(note.id, n.id)
        assertEquals("Acceptance Test Note", n.title)
        assertEquals("This is a body of note", n.body)
        assertEquals(3, n.color)
        assertTrue(n.isChecklist)
        assertTrue(n.pinned)
        assertFalse(n.archived)
        assertEquals(5000L, n.reminderAt)
        assertTrue("updatedAt should be refreshed on save", n.updatedAt >= 1000L)
    }

    // 2. Note ordering (pinned first, then updatedAt desc)
    @Test
    fun testNoteOrderingPinnedAndRecency() {
        val note1 = Note("id-1", "Old Normal", "body1", 0, false, 100L, pinned = false)
        val note2 = Note("id-2", "New Normal", "body2", 0, false, 500L, pinned = false)
        val note3 = Note("id-3", "Old Pinned", "body3", 0, false, 200L, pinned = true)

        store.save(note1)
        store.save(note2)
        store.save(note3)

        val list = store.all()
        assertEquals(3, list.size)
        // Pinned note should come first
        assertEquals("id-3", list[0].id)
        assertTrue(list[0].pinned)
        // Then note2 (saved later than note1)
        assertEquals("id-2", list[1].id)
        assertEquals("id-1", list[2].id)
    }

    // 3. Corrupt data handling: unreadable database throws IllegalStateException and preserves data
    @Test
    fun testCorruptDatabaseSyntaxPreservesData() {
        val prefs = context.getSharedPreferences("side_notes", Context.MODE_PRIVATE)
        prefs.edit().putString("notes", "MALFORMED_JSON_STRING{{{").commit()

        val ex = assertThrows(IllegalStateException::class.java) {
            store.all()
        }
        assertTrue(ex.message!!.contains("Notes database is unreadable"))
        assertEquals("MALFORMED_JSON_STRING{{{", prefs.getString("notes", null))
    }

    // 4. Corrupt entry in database: invalid fields throw IllegalStateException without data loss
    @Test
    fun testCorruptNoteEntryPreservesData() {
        val prefs = context.getSharedPreferences("side_notes", Context.MODE_PRIVATE)
        val corruptArray = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "") // blank id
                put("title", "Valid Title")
                put("body", "Valid Body")
            })
        }
        prefs.edit().putString("notes", corruptArray.toString()).commit()

        val ex = assertThrows(IllegalStateException::class.java) {
            store.all()
        }
        assertTrue(ex.message!!.contains("invalid entry"))
    }

    // 5. Oversized note rejection
    @Test
    fun testOversizedNoteInStoreThrows() {
        val prefs = context.getSharedPreferences("side_notes", Context.MODE_PRIVATE)
        val hugeBody = "x".repeat(200_001)
        val corruptArray = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "valid-id")
                put("title", "Title")
                put("body", hugeBody)
            })
        }
        prefs.edit().putString("notes", corruptArray.toString()).commit()

        val ex = assertThrows(IllegalStateException::class.java) {
            store.all()
        }
        assertTrue(ex.message!!.contains("invalid entry"))
    }

    // 6. Backup Export & Import validation
    @Test
    fun testBackupExportAndImportRoundTrip() {
        val note1 = Note("backup-1", "Shopping List", "[ ] Milk\n[ ] Bread", 1, true, 1000L)
        val note2 = Note("backup-2", "Thoughts", "Peace and quiet", 2, false, 2000L)
        store.save(note1)
        store.save(note2)

        val exported = store.exportJson()
        assertTrue(exported.contains("Shopping List"))
        assertTrue(exported.contains("Thoughts"))

        // Clear and re-import
        val freshStore = NotesStore(context)
        context.getSharedPreferences("side_notes", Context.MODE_PRIVATE).edit().clear().commit()
        freshStore.importJson(exported)

        val imported = freshStore.all()
        assertEquals(2, imported.size)
        val map = imported.associateBy { it.id }
        assertEquals("Shopping List", map["backup-1"]?.title)
        assertEquals("Thoughts", map["backup-2"]?.title)
    }

    // 7. Backup import rejection of duplicate IDs
    @Test
    fun testBackupImportRejectsDuplicateIds() {
        val arr = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "duplicate-id")
                put("title", "First")
                put("body", "Body 1")
            })
            put(JSONObject().apply {
                put("id", "duplicate-id")
                put("title", "Second")
                put("body", "Body 2")
            })
        }
        val ex = assertThrows(IllegalArgumentException::class.java) {
            store.importJson(arr.toString())
        }
        assertTrue(ex.message!!.contains("duplicate note IDs"))
    }

    // 8. Backup import rejection of malformed fields
    @Test
    fun testBackupImportRejectsMissingAndNullFields() {
        val arr = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "id-1")
                put("title", JSONObject.NULL)
                put("body", "Body")
            })
        }
        val ex = assertThrows(IllegalArgumentException::class.java) {
            store.importJson(arr.toString())
        }
        assertTrue(ex.message!!.contains("invalid text fields"))
    }

    // 9. Autosave persistence and deletion
    @Test
    fun testAutosaveUpdateAndDeletion() {
        val note = store.fresh().copy(title = "Initial Draft", body = "Draft body")
        store.save(note)
        assertEquals(1, store.all().size)

        // Update body (simulating debounced autosave flush)
        val updated = note.copy(body = "Edited draft body with more details")
        store.save(updated)
        val retrieved = store.all()
        assertEquals(1, retrieved.size)
        assertEquals("Edited draft body with more details", retrieved.first().body)

        // Delete note
        store.delete(note.id)
        assertTrue(store.all().isEmpty())
    }

    // 10. Reminder scheduling and cancellation
    @Test
    fun testReminderSchedulingAndCancel() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarm = shadowOf(alarmManager)

        val futureTime = System.currentTimeMillis() + 60_000L
        val note = store.fresh().copy(title = "Remind me", reminderAt = futureTime)
        store.save(note)

        ReminderScheduler.schedule(context, note)
        val scheduledAlarms = shadowAlarm.scheduledAlarms
        assertEquals(1, scheduledAlarms.size)
        assertEquals(futureTime, scheduledAlarms.first().triggerAtTime)

        // Cancel reminder
        val cleared = note.copy(reminderAt = 0L)
        store.save(cleared)
        ReminderScheduler.schedule(context, cleared)
        // Verify AlarmManager pending intent was cancelled (no future trigger)
        // With AlarmManager shadow, cancelling pending intent removes it or clears it
        val nextAlarms = shadowAlarm.scheduledAlarms.filter { it.triggerAtTime == futureTime }
        assertTrue(nextAlarms.isEmpty())
    }

    // 11. Preference persistence (Theme, Opacity, Dock Edge)
    @Test
    fun testOverlayPreferencesPersistence() {
        val prefs = context.getSharedPreferences("overlay_ui", Context.MODE_PRIVATE)

        // Theme preference
        listOf("Light", "Dark", "System").forEach { mode ->
            prefs.edit().putString("theme_mode", mode).commit()
            assertEquals(mode, prefs.getString("theme_mode", null))
            Ui.configure(context)
            when (mode) {
                "Dark" -> assertTrue(Ui.dark)
                "Light" -> assertFalse(Ui.dark)
            }
        }

        // Opacity preference
        val clampedOpacity = 85.coerceIn(65, 100)
        prefs.edit().putInt("panel_opacity", clampedOpacity).commit()
        assertEquals(85, prefs.getInt("panel_opacity", 100))

        // Dock right preference
        prefs.edit().putBoolean("dock_right", true).commit()
        assertTrue(prefs.getBoolean("dock_right", false))
    }

    // 12. Multi-window floating card preferences & state independence
    @Test
    fun testMultiWindowFloatingCardStateIndependence() {
        val cardPrefs = context.getSharedPreferences("floating_card_preferences", Context.MODE_PRIVATE)

        val id1 = "card-uuid-1"
        val id2 = "card-uuid-2"
        val id3 = "card-uuid-3"

        // Set card 1: size 0 (small), opacity 100
        cardPrefs.edit().putInt("size_$id1", 0).putInt("opacity_$id1", 100).commit()
        // Set card 2: size 1 (medium), opacity 85
        cardPrefs.edit().putInt("size_$id2", 1).putInt("opacity_$id2", 85).commit()
        // Set card 3: size 2 (large), opacity 75
        cardPrefs.edit().putInt("size_$id3", 2).putInt("opacity_$id3", 75).commit()

        // Verify state is completely independent per card ID
        assertEquals(0, cardPrefs.getInt("size_$id1", -1))
        assertEquals(100, cardPrefs.getInt("opacity_$id1", -1))

        assertEquals(1, cardPrefs.getInt("size_$id2", -1))
        assertEquals(85, cardPrefs.getInt("opacity_$id2", -1))

        assertEquals(2, cardPrefs.getInt("size_$id3", -1))
        assertEquals(75, cardPrefs.getInt("opacity_$id3", -1))
    }

    // 13. Local Undo/Redo Engine test
    @Test
    fun testTextHistoryManagerUndoRedo() {
        var canUndo = false
        var canRedo = false
        val manager = TextHistoryManager { u, r ->
            canUndo = u
            canRedo = r
        }

        manager.init("Initial text")
        assertFalse(canUndo)
        assertFalse(canRedo)
        assertNull(manager.undo("Initial text"))
        assertNull(manager.redo("Initial text"))

        // Type "First edit"
        manager.onTextChanged("First edit")
        assertTrue(canUndo)
        assertFalse(canRedo)

        // Type "Second edit"
        manager.onTextChanged("Second edit")
        assertTrue(canUndo)
        assertFalse(canRedo)

        // Undo back to "First edit"
        val undone1 = manager.undo("Second edit")
        assertEquals("First edit", undone1)
        assertTrue(canUndo)
        assertTrue(canRedo)

        // Undo back to "Initial text"
        val undone2 = manager.undo("First edit")
        assertEquals("Initial text", undone2)
        assertFalse(canUndo)
        assertTrue(canRedo)

        // Redo forward to "First edit"
        val redone1 = manager.redo("Initial text")
        assertEquals("First edit", redone1)
        assertTrue(canUndo)
        assertTrue(canRedo)

        // Redo forward to "Second edit"
        val redone2 = manager.redo("First edit")
        assertEquals("Second edit", redone2)
        assertTrue(canUndo)
        assertFalse(canRedo)
    }

    @Test
    fun testBootReschedulesPendingReminders() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarm = shadowOf(alarmManager)

        val future = System.currentTimeMillis() + 120_000L
        val past = System.currentTimeMillis() - 10_000L

        val n1 = store.fresh().copy(title = "Future note", reminderAt = future)
        val n2 = store.fresh().copy(title = "Past note", reminderAt = past)
        store.save(n1)
        store.save(n2)

        // Simulate boot trigger
        val bootReceiver = BootReceiver()
        bootReceiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        // Ensure future reminder is scheduled
        val futureAlarms = shadowAlarm.scheduledAlarms.filter { it.triggerAtTime == future }
        assertEquals(1, futureAlarms.size)

        // Ensure past reminder was not scheduled
        val pastAlarms = shadowAlarm.scheduledAlarms.filter { it.triggerAtTime == past }
        assertTrue(pastAlarms.isEmpty())
    }

    @Test
    fun testRichTextFormattingBoldItalicHeading() {
        val editText = android.widget.EditText(context)
        editText.setText("Hello World")

        // 1. Select "World" and apply Bold
        editText.setSelection(6, 11)
        RichTextFormatter.applyBold(editText)

        val text1 = editText.text as android.text.Spannable
        val boldSpans = text1.getSpans(6, 11, android.text.style.StyleSpan::class.java).filter { it.style == android.graphics.Typeface.BOLD }
        assertEquals(1, boldSpans.size)

        // 2. Select "Hello" and apply Italic
        editText.setSelection(0, 5)
        RichTextFormatter.applyItalic(editText)

        val italicSpans = text1.getSpans(0, 5, android.text.style.StyleSpan::class.java).filter { it.style == android.graphics.Typeface.ITALIC }
        assertEquals(1, italicSpans.size)

        // 3. Convert to storage string and reload
        val stored = RichTextFormatter.toStorageString(editText.text)
        assertFalse("Should not contain raw markdown asterisks", stored.contains("**"))

        val reloaded = RichTextFormatter.fromStorageString(stored)
        val reloadedSpans = (reloaded as android.text.Spanned).getSpans(0, reloaded.length, android.text.style.CharacterStyle::class.java)
        assertTrue("Reloaded text should retain visual formatting spans", reloadedSpans.isNotEmpty())

        // 4. Legacy markdown parsing
        val legacy = RichTextFormatter.fromStorageString("**Legacy Bold** and _Italic_")
        val legacyBold = (legacy as android.text.Spanned).getSpans(0, legacy.length, android.text.style.StyleSpan::class.java).filter { it.style == android.graphics.Typeface.BOLD }
        assertEquals(1, legacyBold.size)
        assertFalse("Markdown markers stripped in display", legacy.toString().contains("**"))
    }
}
