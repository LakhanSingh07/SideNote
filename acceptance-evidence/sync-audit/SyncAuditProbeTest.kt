package com.sidedeck.notes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Temporary audit probes. Run only in Robolectric; never on user device data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncAuditProbeTest {
    private lateinit var context: Context
    private lateinit var notes: NotesStore
    private lateinit var sync: SyncStore
    private val pcId = "21c7c5d7-9f11-4b7b-8221-f6462279c264"
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("side_notes", 0).edit().clear().commit()
        context.getSharedPreferences("sidenote_sync", 0).edit().clear().commit()
        notes = NotesStore(context)
        sync = SyncStore(context)
    }
    private fun sample() = Note("audit-note", "Audit", "[ ] Task", 2, true, 1700000000000L)
    private fun change(seq: Long, operation: String) = JSONObject().apply {
        put("seq", seq); put("entityId", "audit-note"); put("operation", operation)
        put("changedAt", 1700000000000L + seq)
        put("payload", if(operation == "upsert") SyncStore.noteToJson(sample().copy(updatedAt=1700000000000L+seq)).toString() else JSONObject.NULL)
    }
    private fun bundle(vararg entries: JSONObject) = JSONObject().apply {
        put("protocolVersion", 1); put("originDevice", pcId)
        put("changes", JSONArray().also { array -> entries.forEach { array.put(it) } })
    }.toString()
    @Test fun singleLocalSaveCreatesOneCompatibleChange() {
        notes.save(sample())
        val changes = JSONObject(sync.exportBundle()).getJSONArray("changes")
        assertEquals(1, changes.length())
        val note = SyncStore.jsonToNote(JSONObject(changes.getJSONObject(0).getString("payload")))
        assertTrue(note.isChecklist)
        assertEquals("[ ] Task", note.body)
    }
    @Test fun downloadedWindowsCursorMustNotSkipPendingAndroidUpload() {
        sync.advancePeerCursor(pcId, 300L)
        notes.save(sample())
        // These are the exact cursor/export arguments used by triggerSync.
        val outgoing = JSONObject(sync.exportBundle(sync.peerCursor(pcId))).getJSONArray("changes")
        assertEquals("A Windows download cursor must not acknowledge Android uploads", 1, outgoing.length())
    }
    @Test fun importedRemoteDeletionMustNotCreateLocalEchoChange() {
        sync.importBundle(bundle(change(1, "upsert")))
        sync.importBundle(bundle(change(2, "delete")))
        assertTrue(notes.all().isEmpty())
        assertEquals("Remote imports must not be re-exported as local mutations", 0, JSONObject(sync.exportBundle()).getJSONArray("changes").length())
    }
    @Test fun upsertThenDeleteInSamePageMustLeaveNoNote() {
        sync.importBundle(bundle(change(1, "upsert"), change(2, "delete")))
        assertTrue("Same-page delete must see the preceding upsert", notes.all().isEmpty())
    }
}
