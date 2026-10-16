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

/** Sync regressions run in isolated Robolectric preferences. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncRegressionTest {
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
        val outgoing = JSONObject(sync.exportBundle(sync.uploadCursor(pcId))).getJSONArray("changes")
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

    @Test fun invalidPageDoesNotPartiallyWriteNotes() {
        val invalid=change(2,"upsert").put("entityId","different-id")
        assertThrows(IllegalArgumentException::class.java){sync.importBundle(bundle(change(1,"upsert"),invalid))}
        assertTrue(notes.all().isEmpty())
    }
    @Test fun uploadAndDownloadAcknowledgementsAreIndependent() {
        sync.advancePeerCursor(pcId,900)
        sync.advanceUploadCursor(pcId,4)
        assertEquals(900,sync.peerCursor(pcId))
        assertEquals(4,sync.uploadCursor(pcId))
        sync.advanceUploadCursor(pcId,2)
        assertEquals(4,sync.uploadCursor(pcId))
    }
    @Test fun sequentialForeignEditUsesSharedBaseWithoutConflict() {
        val local=sample().copy(updatedAt=1000L)
        notes.saveDirect(local);sync.recordUpsert(local)
        val incoming=change(1,"upsert").put("payload",SyncStore.noteToJson(local.copy(body="Edited on PC",updatedAt=1001,baseUpdatedAt=1000)).toString()).put("changedAt",1001L)
        val result=sync.importBundle(bundle(incoming))
        assertEquals(0,result.conflicts);assertEquals("Edited on PC",notes.all().single().body)
    }
    @Test fun deletingForeignNoteUsesBaseRevision() {
        val local=sample().copy(updatedAt=1000L)
        notes.saveDirect(local);sync.recordUpsert(local)
        val result=sync.importBundle(bundle(change(1,"delete").put("baseUpdatedAt",1000L)))
        assertEquals(0,result.conflicts);assertTrue(notes.all().isEmpty())
    }
    @Test fun concurrentChangesPreserveLocalNoteAsConflict() {
        val local=sample().copy(updatedAt=2000L)
        notes.saveDirect(local);sync.recordUpsert(local)
        val change=change(1,"upsert").put("payload",SyncStore.noteToJson(local.copy(body="Conflicting remote",updatedAt=3000,baseUpdatedAt=1000)).toString()).put("changedAt",3000)
        assertEquals(1,sync.importBundle(bundle(change)).conflicts)
        assertEquals(local.body,notes.all().single().body)
    }
    @Test fun attachmentsSurviveSaveImportAndBackup() {
        val file=NoteAttachment("file-id","archive.unknown","application/octet-stream",5_000_000_000L,"a".repeat(64))
        notes.save(sample().copy(attachments=listOf(file)))
        assertEquals(file,notes.all().single().attachments.single())
        val backup=notes.exportJson()
        notes.deleteDirect("audit-note");notes.importJson(backup)
        assertEquals(file,notes.all().single().attachments.single())
        val payload=JSONObject(sync.exportBundle()).getJSONArray("changes").getJSONObject(0).getString("payload")
        assertEquals(file,SyncStore.jsonToNote(JSONObject(payload)).attachments.single())
    }

    @Test fun oldNotesJoinFirstSyncWithoutDuplicatingImportedNotes() {
        notes.saveDirect(sample())
        sync.ensureLocalSnapshot()
        assertEquals(1,JSONObject(sync.exportBundle()).getJSONArray("changes").length())
        sync.ensureLocalSnapshot()
        assertEquals(1,JSONObject(sync.exportBundle()).getJSONArray("changes").length())
    }
    @Test fun importedNotesAreNotBootstrappedAsLocalChanges() {
        sync.importBundle(bundle(change(1,"upsert")))
        sync.ensureLocalSnapshot()
        assertEquals(0,JSONObject(sync.exportBundle()).getJSONArray("changes").length())
    }
    @Test fun restoredBackupCreatesPendingSyncChanges() {
        notes.saveDirect(sample())
        notes.importJson(notes.exportJson())
        assertEquals(1,JSONObject(sync.exportBundle()).getJSONArray("changes").length())
    }
}
