package com.sidedeck.notes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncStoreTest {

    private lateinit var context: Context
    private lateinit var notesStore: NotesStore
    private lateinit var syncStore: SyncStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("side_notes", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("sidenote_sync", Context.MODE_PRIVATE).edit().clear().commit()
        notesStore = NotesStore(context)
        syncStore = SyncStore(context)
    }

    @Test
    fun testExportBundleEmpty() {
        val bundleJson = syncStore.exportBundle()
        val obj = JSONObject(bundleJson)
        assertEquals(1, obj.getInt("protocolVersion"))
        assertEquals(syncStore.deviceId, obj.getString("originDevice"))
        assertEquals(0, obj.getJSONArray("changes").length())
    }

    @Test
    fun testRecordUpsertAndExport() {
        val note = Note(
            id = "note-123",
            title = "Sync Test Note",
            body = "Sync body text",
            color = 2,
            isChecklist = false,
            updatedAt = 1700000000000L,
            pinned = true,
            archived = false,
            reminderAt = 0L
        )
        notesStore.save(note)
        syncStore.recordUpsert(note)

        val bundleJson = syncStore.exportBundle()
        val obj = JSONObject(bundleJson)
        val changes = obj.getJSONArray("changes")
        assertEquals(1, changes.length())

        val ch = changes.getJSONObject(0)
        assertEquals(1L, ch.getLong("seq"))
        assertEquals("note-123", ch.getString("entityId"))
        assertEquals("upsert", ch.getString("operation"))
        assertFalse(ch.isNull("payload"))

        val payload = JSONObject(ch.getString("payload"))
        assertEquals("note-123", payload.getString("id"))
        assertEquals("Sync Test Note", payload.getString("title"))
    }

    @Test
    fun testImportBundleAppliesUpsert() {
        val remoteDeviceId = "remote-dev-" + UUID.randomUUID().toString()
        val remoteNotePayload = JSONObject().apply {
            put("id", "remote-note-1")
            put("title", "Remote Title")
            put("body", "Remote Body")
            put("color", 1)
            put("checklist", false)
            put("updatedAt", 1700000005000L)
            put("pinned", false)
            put("archived", false)
            put("reminderAt", 0L)
        }.toString()

        val bundleJson = JSONObject().apply {
            put("protocolVersion", 1)
            put("originDevice", remoteDeviceId)
            put("changes", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("seq", 1L)
                    put("entityId", "remote-note-1")
                    put("operation", "upsert")
                    put("payload", remoteNotePayload)
                    put("changedAt", 1700000005000L)
                })
            })
        }.toString()

        val result = syncStore.importBundle(bundleJson)
        assertEquals(1, result.applied)
        assertEquals(0, result.skipped)
        assertEquals(0, result.conflicts)

        val notes = notesStore.all()
        assertEquals(1, notes.size)
        val n = notes.first()
        assertEquals("remote-note-1", n.id)
        assertEquals("Remote Title", n.title)
        assertEquals("Remote Body", n.body)
        assertEquals(1700000005000L, n.updatedAt)
    }

    @Test
    fun testImportBundleIdempotencySkipsSeen() {
        val remoteDeviceId = "remote-dev-2"
        val payload = JSONObject().apply {
            put("id", "remote-note-2")
            put("title", "Title 2")
            put("body", "Body 2")
            put("color", 0)
            put("checklist", true)
            put("updatedAt", 1700000010000L)
            put("pinned", false)
            put("archived", false)
            put("reminderAt", 0L)
        }.toString()

        val bundleJson = JSONObject().apply {
            put("protocolVersion", 1)
            put("originDevice", remoteDeviceId)
            put("changes", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("seq", 1L)
                    put("entityId", "remote-note-2")
                    put("operation", "upsert")
                    put("payload", payload)
                    put("changedAt", 1700000010000L)
                })
            })
        }.toString()

        val firstImport = syncStore.importBundle(bundleJson)
        assertEquals(1, firstImport.applied)

        val secondImport = syncStore.importBundle(bundleJson)
        assertEquals(0, secondImport.applied)
        assertEquals(1, secondImport.skipped)
        assertEquals(0, secondImport.conflicts)
    }

    @Test
    fun testConflictPreservationOnConcurrentEdit() {
        // Local note created and updated locally
        val note = Note(
            id = "conflict-note",
            title = "Local Note",
            body = "Local Body",
            color = 1,
            isChecklist = false,
            updatedAt = 1000L
        )
        notesStore.saveDirect(note)
        syncStore.recordUpsert(note)

        // Incoming remote note with different content and timestamp from different device
        val remoteDeviceId = "remote-dev-conflict"
        val payload = JSONObject().apply {
            put("id", "conflict-note")
            put("title", "Remote Edit")
            put("body", "Remote Body")
            put("color", 2)
            put("checklist", false)
            put("updatedAt", 2000L)
            put("pinned", false)
            put("archived", false)
            put("reminderAt", 0L)
        }.toString()

        val bundleJson = JSONObject().apply {
            put("protocolVersion", 1)
            put("originDevice", remoteDeviceId)
            put("changes", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("seq", 1L)
                    put("entityId", "conflict-note")
                    put("operation", "upsert")
                    put("payload", payload)
                    put("changedAt", 2000L)
                })
            })
        }.toString()

        val res = syncStore.importBundle(bundleJson)
        assertEquals(0, res.applied)
        assertEquals(1, res.conflicts)

        // Local note remains untouched
        val localNote = notesStore.all().first { it.id == "conflict-note" }
        assertEquals("Local Note", localNote.title)

        // Conflict recorded for review
        val conflicts = syncStore.listConflicts()
        assertEquals(1, conflicts.size)
        assertEquals("conflict-note", conflicts[0].noteId)
    }
}
