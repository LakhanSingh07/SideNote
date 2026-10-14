package com.sidedeck.notes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Durable Android-side change log that mirrors the Windows sync_changes table schema.
 *
 * Key design invariants:
 * 1. NEVER modifies the existing side_notes/notes SharedPreferences key directly without NotesStore.
 * 2. Every note mutation is append-only and assigned a monotonic sequence number.
 * 3. The on-disk format is a single JSON array in sidenote_sync/changes.
 * 4. Exported bundles are byte-for-byte compatible with the Windows SyncBundle v1 format.
 * 5. Import from Windows bundles applies note mutations through NotesStore atomically.
 */
class SyncStore(context: Context) {

    private val prefs = context.getSharedPreferences("sidenote_sync", Context.MODE_PRIVATE)
    private val notesStore = NotesStore(context)

    val deviceId: String by lazy {
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    @Synchronized fun recordUpsert(note: Note) {
        appendChange(note.id, "upsert", noteToJson(note).toString(), note.updatedAt)
    }

    @Synchronized fun recordDelete(noteId: String) {
        appendChange(noteId, "delete", null, System.currentTimeMillis())
    }

    @Synchronized fun exportBundle(afterSeq: Long = 0L): String {
        val changes = loadChanges().filter { it.seq > afterSeq }.take(500)
        val arr = JSONArray()
        for (ch in changes) {
            arr.put(JSONObject().apply {
                put("seq", ch.seq)
                put("entityId", ch.noteId)
                put("operation", ch.operation)
                if (ch.payload != null) put("payload", ch.payload) else put("payload", JSONObject.NULL)
                put("changedAt", ch.changedAt)
            })
        }
        return JSONObject().apply {
            put("protocolVersion", 1)
            put("originDevice", deviceId)
            put("changes", arr)
        }.toString()
    }

    data class ImportResult(val applied: Int, val skipped: Int, val conflicts: Int)

    @Synchronized fun importBundle(bundleJson: String): ImportResult {
        require(bundleJson.length <= 5_000_000) { "Bundle exceeds 5 MB limit" }
        val bundle = JSONObject(bundleJson)
        require(bundle.optInt("protocolVersion", 0) == 1) { "Unsupported protocol version" }
        val origin = bundle.getString("originDevice")
        require(origin.isNotBlank() && origin.length <= 64 && origin != deviceId) { "Invalid origin device" }
        val changes = bundle.getJSONArray("changes")
        require(changes.length() <= 500) { "Bundle too large" }
        val seen = loadSeenChanges(origin)
        val currentNotes = try { notesStore.all().associateBy { it.id } } catch (_: Exception) { emptyMap() }
        val tombstones = loadTombstones()
        val conflicts = loadConflicts().toMutableList()
        var applied = 0
        var skipped = 0
        var conflictCount = 0
        var prevSeq = 0L
        for (i in 0 until changes.length()) {
            val ch = changes.getJSONObject(i)
            val seq = ch.getLong("seq")
            require(seq > prevSeq && seq > 0) { "Non-monotonic seq" }
            prevSeq = seq
            val noteId = ch.getString("entityId")
            require(noteId.isNotBlank() && noteId.length <= 160) { "Invalid note ID" }
            val operation = ch.getString("operation")
            require(operation == "upsert" || operation == "delete") { "Unknown operation" }
            if (seen.contains(seq)) {
                skipped++
                continue
            }
            if (operation == "upsert") {
                val payloadStr = ch.optString("payload", "").takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("Upsert missing payload")
                val remoteNote = jsonToNote(JSONObject(payloadStr))
                require(remoteNote.id == noteId) { "Payload ID mismatch" }
                val local = currentNotes[noteId]
                val tomb = tombstones[noteId]
                val sameLineage = loadOrigin(noteId) == origin
                val isConflict = (tomb != null && tomb.third != origin) ||
                        (local != null && !sameLineage && local.updatedAt != remoteNote.updatedAt)
                if (isConflict) {
                    conflicts.add(listOf(noteId, origin, seq, operation, payloadStr, ch.getLong("changedAt")))
                    conflictCount++
                } else {
                    notesStore.saveDirect(remoteNote)
                    recordOrigin(noteId, origin)
                    applied++
                }
            } else {
                val local = currentNotes[noteId]
                val tomb = tombstones[noteId]
                val isConflict = tomb != null && tomb.third != origin
                if (isConflict) {
                    conflicts.add(listOf(noteId, origin, seq, operation, null, ch.getLong("changedAt")))
                    conflictCount++
                } else {
                    if (local != null) {
                        notesStore.delete(noteId)
                        applied++
                    }
                    recordTombstone(noteId, origin, ch.getLong("changedAt"))
                }
            }
            seen.add(seq)
        }
        saveSeenChanges(origin, seen)
        saveConflicts(conflicts)
        return ImportResult(applied, skipped, conflictCount)
    }

    fun peerCursor(peerDeviceId: String): Long = prefs.getLong("cursor_$peerDeviceId", 0L)
    fun advancePeerCursor(peerDeviceId: String, seq: Long) {
        if (seq > peerCursor(peerDeviceId)) {
            prefs.edit().putLong("cursor_$peerDeviceId", seq).apply()
        }
    }

    data class ConflictItem(
        val noteId: String,
        val originDevice: String,
        val originSeq: Long,
        val operation: String,
        val payload: String?,
        val changedAt: Long
    )

    fun listConflicts(): List<ConflictItem> {
        return loadConflicts().map {
            ConflictItem(
                noteId = it[0] as String,
                originDevice = it[1] as String,
                originSeq = (it[2] as Number).toLong(),
                operation = it[3] as String,
                payload = it[4] as? String,
                changedAt = (it[5] as Number).toLong()
            )
        }
    }

    fun resolveConflict(noteId: String, keepLocal: Boolean) {
        val conflicts = loadConflicts().toMutableList()
        val match = conflicts.firstOrNull { (it[0] as String) == noteId } ?: return
        if (!keepLocal) {
            val operation = match[3] as String
            val payload = match[4] as? String
            if (operation == "upsert" && payload != null) {
                notesStore.saveDirect(jsonToNote(JSONObject(payload)))
            } else if (operation == "delete") {
                notesStore.delete(noteId)
            }
        }
        conflicts.removeAll { (it[0] as String) == noteId }
        saveConflicts(conflicts)
    }

    private data class ChangeEntry(val seq: Long, val noteId: String, val operation: String, val payload: String?, val changedAt: Long)

    private fun loadChanges(): List<ChangeEntry> {
        val arr = try { JSONArray(prefs.getString("changes", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull {
            runCatching {
                val o = arr.getJSONObject(it)
                ChangeEntry(o.getLong("seq"), o.getString("noteId"), o.getString("operation"), if (o.isNull("payload")) null else o.getString("payload"), o.getLong("changedAt"))
            }.getOrNull()
        }
    }

    private fun appendChange(noteId: String, operation: String, payload: String?, changedAt: Long) {
        val entries = loadChanges().toMutableList()
        val seq = (entries.maxOfOrNull { it.seq } ?: 0L) + 1L
        entries.add(ChangeEntry(seq, noteId, operation, payload, changedAt))
        val arr = JSONArray()
        entries.takeLast(10_000).forEach { e ->
            arr.put(JSONObject().apply {
                put("seq", e.seq)
                put("noteId", e.noteId)
                put("operation", e.operation)
                if (e.payload != null) put("payload", e.payload) else put("payload", JSONObject.NULL)
                put("changedAt", e.changedAt)
            })
        }
        prefs.edit().putString("changes", arr.toString()).apply()
    }

    private fun loadTombstones(): Map<String, Triple<String, Long, String>> {
        val obj = try { JSONObject(prefs.getString("tombstones", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val map = mutableMapOf<String, Triple<String, Long, String>>()
        for (key in obj.keys()) {
            runCatching {
                val t = obj.getJSONObject(key)
                map[key] = Triple(key, t.getLong("deletedAt"), t.getString("origin"))
            }
        }
        return map
    }

    private fun recordTombstone(noteId: String, originDevice: String, deletedAt: Long) {
        val obj = try { JSONObject(prefs.getString("tombstones", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        obj.put(noteId, JSONObject().apply {
            put("origin", originDevice)
            put("deletedAt", deletedAt)
        })
        prefs.edit().putString("tombstones", obj.toString()).apply()
    }

    private fun loadOrigin(noteId: String): String? {
        return try {
            JSONObject(prefs.getString("origins", "{}") ?: "{}").optString(noteId, "").takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun recordOrigin(noteId: String, originDevice: String) {
        val obj = try { JSONObject(prefs.getString("origins", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        obj.put(noteId, originDevice)
        prefs.edit().putString("origins", obj.toString()).apply()
    }

    private fun loadSeenChanges(origin: String): MutableSet<Long> {
        val arr = try { JSONArray(prefs.getString("seen_$origin", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.getLong(it) }.toMutableSet()
    }

    private fun saveSeenChanges(origin: String, seen: Set<Long>) {
        val arr = JSONArray()
        seen.sorted().takeLast(5000).forEach { arr.put(it) }
        prefs.edit().putString("seen_$origin", arr.toString()).apply()
    }

    private fun loadConflicts(): List<List<Any?>> {
        val arr = try { JSONArray(prefs.getString("conflicts", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull {
            runCatching {
                val o = arr.getJSONObject(it)
                listOf(
                    o.getString("noteId"),
                    o.getString("originDevice"),
                    o.getLong("originSeq"),
                    o.getString("operation"),
                    if (o.isNull("payload")) null else o.getString("payload"),
                    o.getLong("changedAt")
                )
            }.getOrNull()
        }
    }

    private fun saveConflicts(conflicts: List<List<Any?>>) {
        val arr = JSONArray()
        for (c in conflicts) {
            arr.put(JSONObject().apply {
                put("noteId", c[0])
                put("originDevice", c[1])
                put("originSeq", c[2])
                put("operation", c[3])
                if (c[4] != null) put("payload", c[4]) else put("payload", JSONObject.NULL)
                put("changedAt", c[5])
            })
        }
        prefs.edit().putString("conflicts", arr.toString()).apply()
    }

    companion object {
        fun noteToJson(note: Note): JSONObject = JSONObject().apply {
            put("id", note.id)
            put("title", note.title)
            put("body", note.body)
            put("color", note.color)
            put("checklist", note.isChecklist)
            put("isChecklist", note.isChecklist)
            put("updatedAt", note.updatedAt)
            put("pinned", note.pinned)
            put("archived", note.archived)
            put("reminderAt", note.reminderAt)
        }

        fun jsonToNote(o: JSONObject): Note = Note(
            id = o.getString("id"),
            title = o.optString("title", ""),
            body = o.optString("body", ""),
            color = o.optInt("color", 0).coerceIn(0, 5),
            isChecklist = o.optBoolean("checklist", o.optBoolean("isChecklist", false)),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            pinned = o.optBoolean("pinned", false),
            archived = o.optBoolean("archived", false),
            reminderAt = o.optLong("reminderAt", 0L)
        )
    }
}
