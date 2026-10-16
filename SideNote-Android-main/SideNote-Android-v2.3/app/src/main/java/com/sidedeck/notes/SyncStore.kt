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

    fun recordUpsert(note: Note): Unit  = synchronized(NOTE_STORE_LOCK) {
        appendChange(note.id, "upsert", noteToJson(note).toString(), note.updatedAt)
        recordOrigin(note.id, deviceId)
        val tombs = JSONObject(prefs.getString("tombstones", "{}") ?: "{}"); tombs.remove(note.id)
        check(prefs.edit().putString("tombstones",tombs.toString()).commit())
    }

    fun recordDelete(noteId: String, baseUpdatedAt: Long? = null): Unit  = synchronized(NOTE_STORE_LOCK) {
        val now = System.currentTimeMillis()
        appendChange(noteId, "delete", null, now, baseUpdatedAt)
        recordTombstone(noteId, deviceId, now)
    }

    fun exportBundle(afterSeq: Long = 0L): String = synchronized(NOTE_STORE_LOCK) {
        val changes = loadChanges().filter { it.seq > afterSeq }.take(500)
        val arr = JSONArray()
        for (ch in changes) {
            val entry = JSONObject().apply {
                put("seq", ch.seq)
                put("entityId", ch.noteId)
                put("operation", ch.operation)
                if (ch.payload != null) put("payload", ch.payload) else put("payload", JSONObject.NULL)
                put("changedAt", ch.changedAt)
                if(ch.baseUpdatedAt != null)put("baseUpdatedAt",ch.baseUpdatedAt)
            }
            if(arr.length() > 0 && arr.toString().toByteArray().size + entry.toString().toByteArray().size > 4_500_000) break
            arr.put(entry)
        }
        return JSONObject().apply {
            put("protocolVersion", 1)
            put("originDevice", deviceId)
            put("changes", arr)
        }.toString()
    }

    data class ImportResult(val applied: Int, val skipped: Int, val conflicts: Int)

    fun importBundle(bundleJson: String): ImportResult = synchronized(NOTE_STORE_LOCK) {
        require(bundleJson.toByteArray(Charsets.UTF_8).size <= 5_000_000) { "Bundle exceeds 5 MB limit" }
        val bundle = JSONObject(bundleJson)
        require(bundle.optInt("protocolVersion", 0) == 1) { "Unsupported protocol version" }
        val origin = bundle.getString("originDevice")
        require(UUID.fromString(origin).toString() == origin && origin != deviceId) { "Invalid origin device" }
        val changes = bundle.getJSONArray("changes")
        require(changes.length() <= 500) { "Bundle too large" }
        // Validate the entire page before writing anything to disk.
        var prevSeq = 0L
        for (i in 0 until changes.length()) {
            val ch = changes.getJSONObject(i)
            val seq = ch.getLong("seq")
            require(seq > prevSeq && seq > 0) { "Non-monotonic seq" }; prevSeq = seq
            val id = ch.getString("entityId")
            require(id.isNotBlank() && id.length <= 128) { "Invalid note ID" }
            when(ch.getString("operation")) {
                "upsert" -> {
                    val n = jsonToNote(JSONObject(ch.getString("payload")))
                    require(n.id == id && n.updatedAt == ch.getLong("changedAt")) { "Invalid note revision" }
                    require(n.title.length <= 2000 && n.body.length <= 200_000 && n.color in 0..5) { "Invalid note fields" }
                }
                "delete" -> require(ch.isNull("payload")) { "Delete must not include a payload" }
                else -> error("Unknown operation")
            }
        }
        val seen = loadSeenChanges(origin)
        val currentNotes = notesStore.all().associateBy { it.id }.toMutableMap()
        val tombstones = JSONObject(prefs.getString("tombstones", "{}") ?: "{}")
        val origins = JSONObject(prefs.getString("origins", "{}") ?: "{}")
        val conflicts = loadConflicts().toMutableList()
        var applied = 0; var skipped = 0; var conflictCount = 0
        for (i in 0 until changes.length()) {
            val ch = changes.getJSONObject(i); val seq = ch.getLong("seq")
            if (seen.contains(seq)) { skipped++; continue }
            val id = ch.getString("entityId"); val operation = ch.getString("operation")
            val local = currentNotes[id]
            val remote = if(operation == "upsert") jsonToNote(JSONObject(ch.getString("payload"))) else null
            val sameLineage = origins.optString(id) == origin
            val sameRevision = local?.updatedAt == ch.getLong("changedAt")
            val base = remote?.baseUpdatedAt ?: if(ch.isNull("baseUpdatedAt")) null else ch.getLong("baseUpdatedAt")
            val basedOnLocal = base != null && base == local?.updatedAt
            val tomb = tombstones.optJSONObject(id)
            val divergent = local != null && !sameLineage && !sameRevision && !basedOnLocal
            val conflictingTomb = remote != null && tomb != null && tomb.optString("origin") != origin
            if (divergent || conflictingTomb) {
                conflicts.add(listOf(id, origin, seq, operation, remote?.let { ch.getString("payload") }, ch.getLong("changedAt")))
                conflictCount++
            } else if(remote != null) {
                currentNotes[id] = remote; origins.put(id, origin); tombstones.remove(id); applied++
            } else {
                currentNotes.remove(id); origins.remove(id)
                tombstones.put(id, JSONObject().put("origin",origin).put("deletedAt",ch.getLong("changedAt"))); applied++
            }
            seen.add(seq)
        }
        notesStore.replaceDirect(currentNotes.values.toList(),notesStore.all().filter {it.id !in currentNotes})
        val conflictArray = JSONArray()
        conflicts.forEach { c -> conflictArray.put(JSONObject().put("noteId",c[0]).put("originDevice",c[1]).put("originSeq",c[2]).put("operation",c[3]).put("payload",c[4] ?: JSONObject.NULL).put("changedAt",c[5])) }
        check(prefs.edit().putString("seen_$origin",JSONArray(seen.sorted()).toString())
            .putString("origins",origins.toString()).putString("tombstones",tombstones.toString())
            .putString("conflicts",conflictArray.toString()).commit()) { "Could not persist sync state" }
        return ImportResult(applied, skipped, conflictCount)
    }

    fun ensureLocalSnapshot(): Unit = synchronized(NOTE_STORE_LOCK) {
        val recorded=loadChanges().map {it.noteId}.toHashSet()
        notesStore.all().filter {it.id !in recorded && loadOrigin(it.id)==null}.forEach {recordUpsert(it)}
    }

    fun ensurePeerSnapshot(peer:String):Unit = synchronized(NOTE_STORE_LOCK) {
        if(prefs.getBoolean("snapshot_v2_$peer",false))return@synchronized
        notesStore.all().forEach {appendChange(it.id,"upsert",noteToJson(it.copy(baseUpdatedAt=it.updatedAt)).toString(),it.updatedAt)}
        check(prefs.edit().putBoolean("snapshot_v2_$peer",true).commit())
    }

    fun uploadCursor(peerDeviceId: String): Long = prefs.getLong("upload_cursor_$peerDeviceId", 0L)
    fun advanceUploadCursor(peerDeviceId: String, seq: Long) {
        require(seq >= 0) { "Invalid upload cursor" }
        if(seq > uploadCursor(peerDeviceId)) check(prefs.edit().putLong("upload_cursor_$peerDeviceId",seq).commit())
    }

    fun peerCursor(peerDeviceId: String): Long = prefs.getLong("cursor_$peerDeviceId", 0L)
    fun advancePeerCursor(peerDeviceId: String, seq: Long) {
        if (seq > peerCursor(peerDeviceId)) {
            check(prefs.edit().putLong("cursor_$peerDeviceId", seq).commit())
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
        val match = conflicts.lastOrNull { (it[0] as String) == noteId } ?: return
        if(keepLocal){
            notesStore.all().firstOrNull {it.id==noteId}?.let {local->
                if(match[3]=="delete") {notesStore.save(local.copy(id=UUID.randomUUID().toString(),baseUpdatedAt=null));notesStore.deleteDirect(noteId)}
                else notesStore.saveResolved(local,(match[5] as Number).toLong())
            }
        }
        if (!keepLocal) {
            val operation = match[3] as String
            val payload = match[4] as? String
            if (operation == "upsert" && payload != null) {
                notesStore.all().firstOrNull {it.id==noteId}?.let {local->notesStore.replaceDirect(notesStore.all(),listOf(local))}
                notesStore.saveDirect(jsonToNote(JSONObject(payload)))
                recordOrigin(noteId,match[1] as String)
            } else if (operation == "delete") {
                notesStore.deleteDirect(noteId)
            }
        }
        conflicts.removeAll { (it[0] as String) == noteId }
        saveConflicts(conflicts)
    }

    private data class ChangeEntry(val seq: Long, val noteId: String, val operation: String, val payload: String?, val changedAt: Long, val baseUpdatedAt: Long? = null)

    private fun loadChanges(): List<ChangeEntry> {
        val arr = try { JSONArray(prefs.getString("changes", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull {
            runCatching {
                val o = arr.getJSONObject(it)
                ChangeEntry(o.getLong("seq"), o.getString("noteId"), o.getString("operation"), if (o.isNull("payload")) null else o.getString("payload"), o.getLong("changedAt"), if(o.isNull("baseUpdatedAt")) null else o.getLong("baseUpdatedAt"))
            }.getOrNull()
        }
    }

    private fun appendChange(noteId: String, operation: String, payload: String?, changedAt: Long, baseUpdatedAt: Long? = null) {
        val entries = loadChanges().toMutableList()
        val seq = (entries.maxOfOrNull { it.seq } ?: 0L) + 1L
        entries.add(ChangeEntry(seq, noteId, operation, payload, changedAt, baseUpdatedAt))
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("seq", e.seq)
                put("noteId", e.noteId)
                put("operation", e.operation)
                if (e.payload != null) put("payload", e.payload) else put("payload", JSONObject.NULL)
                put("changedAt", e.changedAt)
                if(e.baseUpdatedAt != null)put("baseUpdatedAt",e.baseUpdatedAt)
            })
        }
        check(prefs.edit().putString("changes", arr.toString()).commit()) { "Could not persist change log" }
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
        seen.sorted().forEach { arr.put(it) }
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
            put("attachments", NoteAttachment.toArray(note.attachments))
            put("baseUpdatedAt", note.baseUpdatedAt ?: JSONObject.NULL)
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
            reminderAt = o.optLong("reminderAt", 0L),
            attachments = NoteAttachment.fromArray(o.optJSONArray("attachments")),
            baseUpdatedAt = if(o.isNull("baseUpdatedAt")) null else o.getLong("baseUpdatedAt")
        )
    }
}
