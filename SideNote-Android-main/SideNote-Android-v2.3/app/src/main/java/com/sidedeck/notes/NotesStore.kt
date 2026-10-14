package com.sidedeck.notes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Note(val id: String, val title: String, val body: String, val color: Int, val isChecklist: Boolean, val updatedAt: Long, val pinned: Boolean = false, val archived: Boolean = false, val reminderAt: Long = 0L)

/** Small, atomic SharedPreferences store. Serialized access is synchronized to prevent lost updates. */
class NotesStore(context: Context) {
    private val prefs = context.getSharedPreferences("side_notes", Context.MODE_PRIVATE)
    @Synchronized fun all(): List<Note> {
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
                Note(id, o.optString("title"), o.getString("body"), o.optInt("color", 0).coerceIn(0, 5), o.optBoolean("checklist", false), o.optLong("updatedAt", 0), o.optBoolean("pinned",false), o.optBoolean("archived",false), o.optLong("reminderAt",0))
            } catch (error: Exception) {
                throw IllegalStateException("Notes database contains an invalid entry at index $i; original data was preserved", error)
            }
        }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
    }
    @Synchronized fun save(note: Note) {
        val updated = all().filterNot { it.id == note.id } + note.copy(updatedAt = System.currentTimeMillis())
        write(updated)
    }
    @Synchronized fun saveDirect(note: Note) {
        val updated = all().filterNot { it.id == note.id } + note
        write(updated)
    }
    @Synchronized fun delete(id: String) { write(all().filterNot { it.id == id }) }
    fun fresh() = Note(UUID.randomUUID().toString(), "", "", 0, false, System.currentTimeMillis())
    @Synchronized fun exportJson(): String = prefs.getString("notes", "[]") ?: "[]"
    @Synchronized fun importJson(json: String) {
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
            imported.add(Note(obj.getString("id"),obj.optString("title"),obj.optString("body"),obj.optInt("color").coerceIn(0,5),obj.optBoolean("checklist"),obj.optLong("updatedAt"),obj.optBoolean("pinned"),obj.optBoolean("archived"),obj.optLong("reminderAt")))
        }
        require(imported.map { it.id }.toSet().size == imported.size) { "Backup contains duplicate note IDs" }
        val importedIds = imported.map { it.id }.toHashSet()
        write((all().filterNot { it.id in importedIds } + imported).distinctBy { it.id })
    }
    private fun write(notes: List<Note>) {
        val a = JSONArray()
        notes.forEach { n -> a.put(JSONObject().put("id",n.id).put("title",n.title).put("body",n.body).put("color",n.color).put("checklist",n.isChecklist).put("updatedAt",n.updatedAt).put("pinned",n.pinned).put("archived",n.archived).put("reminderAt",n.reminderAt)) }
        check(prefs.edit().putString("notes", a.toString()).commit()) { "Local save failed" }
    }
}
