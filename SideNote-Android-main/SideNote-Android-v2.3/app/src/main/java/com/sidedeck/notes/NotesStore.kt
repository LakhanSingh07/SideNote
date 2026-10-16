package com.sidedeck.notes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal val NOTE_STORE_LOCK = Any()

data class DeletedNote(val note:Note,val deletedAt:Long)

data class Note(val id: String, val title: String, val body: String, val color: Int, val isChecklist: Boolean, val updatedAt: Long, val pinned: Boolean = false, val archived: Boolean = false, val reminderAt: Long = 0L, val attachments: List<NoteAttachment> = emptyList(), val baseUpdatedAt: Long? = null)

/** Small, atomic SharedPreferences store. Serialized access is synchronized to prevent lost updates. */
class NotesStore(val context: Context) {
    private val prefs = context.getSharedPreferences("side_notes", Context.MODE_PRIVATE)
    fun all(): List<Note> = synchronized(NOTE_STORE_LOCK) {
        // Never silently reinterpret corrupted local data as an empty database.
        // Otherwise the next save would permanently overwrite the original notes.
        val a = try { JSONArray(prefs.getString("notes", "[]") ?: "[]") }
        catch (error: Exception) { throw IllegalStateException("Notes database is unreadable; original data was preserved", error) }
        // Validate every entry. Never silently discard a damaged note: doing so
        // could erase its original content the next time the array is saved.
        return (0 until a.length()).map { i ->
            try {
                val o = a.getJSONObject(i)
                val id = o.getString("id")
                require(id.isNotBlank() && id.length <= 128) { "Invalid note ID at index $i" }
                require(o.has("body") && !o.isNull("body") && o.get("body") is String) { "Invalid note body at index $i" }
                require(o.has("title") && !o.isNull("title") && o.get("title") is String) { "Invalid note title at index $i" }
                require(o.getString("title").length <= 2000 && o.getString("body").length <= 200_000) { "Oversized note at index $i" }
                Note(id, o.optString("title"), o.getString("body"), o.optInt("color", 0).coerceIn(0, 5), o.optBoolean("checklist", false), o.optLong("updatedAt", 0), o.optBoolean("pinned",false), o.optBoolean("archived",false), o.optLong("reminderAt",0), NoteAttachment.fromArray(o.optJSONArray("attachments")), if(o.isNull("baseUpdatedAt")) null else o.optLong("baseUpdatedAt"))
            } catch (error: Exception) {
                throw IllegalStateException("Notes database contains an invalid entry at index $i; original data was preserved", error)
            }
        }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
    }
    fun save(note: Note): Unit  = synchronized(NOTE_STORE_LOCK) {
        val previous = all().firstOrNull { it.id == note.id }
        val updatedNote = note.copy(updatedAt = maxOf(System.currentTimeMillis(), (previous?.updatedAt ?: 0L) + 1L), baseUpdatedAt = previous?.updatedAt)
        val updated = all().filterNot { it.id == note.id } + updatedNote
        write(updated)
        SyncStore(context).recordUpsert(updatedNote)
        LanSyncManager.triggerSync(context)
    }
    fun saveDirect(note: Note): Unit  = synchronized(NOTE_STORE_LOCK) {
        val updated = all().filterNot { it.id == note.id } + note
        write(updated)
    }
    fun delete(id: String): Unit  = synchronized(NOTE_STORE_LOCK) {
        val previous=all().firstOrNull {it.id==id}
        write(all().filterNot { it.id == id },previous?.let {withDeleted(listOf(it))})
        SyncStore(context).recordDelete(id,previous?.updatedAt)
        LanSyncManager.triggerSync(context)
    }
    fun replaceDirect(notes: List<Note>,removed:List<Note> = emptyList()): Unit = synchronized(NOTE_STORE_LOCK) {write(notes,if(removed.isEmpty())null else withDeleted(removed))}
    fun deleteDirect(id: String): Unit = synchronized(NOTE_STORE_LOCK) {val previous=all().firstOrNull {it.id==id};write(all().filterNot { it.id == id },previous?.let {withDeleted(listOf(it))})}
    fun trash():List<DeletedNote> = synchronized(NOTE_STORE_LOCK) {
        val a=JSONArray(prefs.getString("trash","[]"));(0 until a.length()).map {val o=a.getJSONObject(it);DeletedNote(SyncStore.jsonToNote(o.getJSONObject("note")),o.getLong("deletedAt"))}.sortedByDescending {it.deletedAt}
    }
    private fun withDeleted(notes:List<Note>):JSONArray {
        val ids=notes.map {it.id}.toSet();val kept=trash().filterNot {it.note.id in ids}
        val entries=kept+notes.map {DeletedNote(it,System.currentTimeMillis())}
        return JSONArray().apply {entries.forEach {put(JSONObject().put("note",SyncStore.noteToJson(it.note)).put("deletedAt",it.deletedAt))}}
    }
    fun eraseTrash(id:String):Unit = synchronized(NOTE_STORE_LOCK) {
        val a=JSONArray();trash().filterNot {it.note.id==id}.forEach {a.put(JSONObject().put("note",SyncStore.noteToJson(it.note)).put("deletedAt",it.deletedAt))};check(prefs.edit().putString("trash",a.toString()).commit())
    }
    fun restoreTrash(id:String):Note = synchronized(NOTE_STORE_LOCK) {
        val original=trash().first {it.note.id==id}.note
        val restored=original.copy(id=UUID.randomUUID().toString(),baseUpdatedAt=null,updatedAt=System.currentTimeMillis())
        save(restored);eraseTrash(id);restored
    }
    fun saveResolved(note:Note,remoteBase:Long):Unit = synchronized(NOTE_STORE_LOCK){
        val updated=note.copy(updatedAt=maxOf(System.currentTimeMillis(),remoteBase+1,note.updatedAt+1),baseUpdatedAt=remoteBase)
        write(all().filterNot {it.id==note.id}+updated);SyncStore(context).recordUpsert(updated);LanSyncManager.triggerSync(context)
    }
    fun fresh() = Note(UUID.randomUUID().toString(), "", "", 0, false, System.currentTimeMillis())
    @Synchronized fun exportJson(): String = prefs.getString("notes", "[]") ?: "[]"
    fun importJson(json: String): Unit  = synchronized(NOTE_STORE_LOCK) {
        require(json.length <= 8_000_000) { "Backup exceeds 8 MB limit" }
        val arr = JSONArray(json)
        require(arr.length() <= 10000) { "Too many notes" }
        val imported = mutableListOf<Note>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            require(obj.has("id") && obj.has("body") && obj.has("title")) { "Invalid backup format" }
            require(!obj.isNull("id") && obj.get("id") is String && !obj.isNull("body") && obj.get("body") is String && !obj.isNull("title") && obj.get("title") is String) { "Backup contains invalid text fields" }
            require(obj.getString("id").length in 1..128) { "Invalid note ID" }
            require(obj.optString("title").length <= 2000) { "Note title too long" }
            require(obj.optString("body").length <= 200_000) { "Note body too long" }
            imported.add(Note(obj.getString("id"),obj.optString("title"),obj.optString("body"),obj.optInt("color").coerceIn(0,5),obj.optBoolean("checklist",obj.optBoolean("isChecklist")),obj.optLong("updatedAt"),obj.optBoolean("pinned"),obj.optBoolean("archived"),obj.optLong("reminderAt"),NoteAttachment.fromArray(obj.optJSONArray("attachments")),if(obj.isNull("baseUpdatedAt")) null else obj.optLong("baseUpdatedAt")))
        }
        require(imported.map { it.id }.toSet().size == imported.size) { "Backup contains duplicate note IDs" }
        val importedIds = imported.map { it.id }.toHashSet()
        val current=all().associateBy {it.id}
        val restored=imported.map {n->n.copy(updatedAt=maxOf(System.currentTimeMillis(),(current[n.id]?.updatedAt ?: 0L)+1),baseUpdatedAt=current[n.id]?.updatedAt)}
        write((current.values.filterNot {it.id in importedIds}+restored).distinctBy {it.id})
        val sync=SyncStore(context);restored.forEach {sync.recordUpsert(it)}
        LanSyncManager.triggerSync(context)
    }
    private fun write(notes: List<Note>,trash:JSONArray?=null) {
        val a = JSONArray()
        notes.forEach { n -> a.put(SyncStore.noteToJson(n)) }
        val edit=prefs.edit().putString("notes",a.toString());if(trash!=null)edit.putString("trash",trash.toString())
        check(edit.commit()) { "Local save failed" }
    }
}
