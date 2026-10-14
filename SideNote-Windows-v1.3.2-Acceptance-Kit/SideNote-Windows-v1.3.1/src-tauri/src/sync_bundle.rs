use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::Note;

const PROTOCOL_VERSION: u32 = 1;
const MAX_BUNDLE_BYTES: usize = 5_000_000;
pub const MAX_CHANGES: usize = 500;

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BundleChange {
    pub seq: i64,
    pub entity_id: String,
    pub operation: String,
    pub payload: Option<String>,
    pub changed_at: i64,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SyncBundle {
    pub protocol_version: u32,
    pub origin_device: String,
    pub changes: Vec<BundleChange>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportReport { pub applied: usize, pub skipped: usize, pub conflicts: usize }

pub fn export_bundle(conn: &Connection, after_seq: i64) -> Result<String, String> {
    if after_seq < 0 { return Err("Invalid sync cursor".into()); }
    let device: String = conn.query_row("SELECT value FROM sync_meta WHERE key='device_id'", [], |r|r.get(0)).map_err(|e|e.to_string())?;
    let mut stmt=conn.prepare("SELECT seq, entity_id, operation, payload, changed_at FROM sync_changes WHERE seq > ?1 AND origin_device = ?2 ORDER BY seq LIMIT ?3").map_err(|e|e.to_string())?;
    let changes=stmt.query_map(params![after_seq,device,MAX_CHANGES as i64], |r|Ok(BundleChange{seq:r.get(0)?,entity_id:r.get(1)?,operation:r.get(2)?,payload:r.get(3)?,changed_at:r.get(4)?})).map_err(|e|e.to_string())?.collect::<rusqlite::Result<Vec<_>>>().map_err(|e|e.to_string())?;
    serde_json::to_string(&SyncBundle{protocol_version:PROTOCOL_VERSION,origin_device:device,changes}).map_err(|e|e.to_string())
}

pub fn import_bundle(conn: &mut Connection, input: &str) -> Result<ImportReport,String> {
    if input.len()>MAX_BUNDLE_BYTES {return Err("Bundle exceeds 5 MB limit".into())}
    let bundle:SyncBundle=serde_json::from_str(input).map_err(|e|format!("Invalid sync bundle: {e}"))?;
    if bundle.protocol_version!=PROTOCOL_VERSION || bundle.changes.len()>MAX_CHANGES || Uuid::parse_str(&bundle.origin_device).is_err() {return Err("Unsupported bundle version, origin or size".into())}
    let tx=conn.transaction().map_err(|e|e.to_string())?;
    let self_id:String=tx.query_row("SELECT value FROM sync_meta WHERE key='device_id'",[],|r|r.get(0)).map_err(|e|e.to_string())?;
    if self_id==bundle.origin_device {return Err("Cannot import a bundle from this same device".into())}
    let mut out=ImportReport{applied:0,skipped:0,conflicts:0};
    let mut prev=0_i64;
    for ch in bundle.changes {
        if ch.seq<=prev || ch.seq<=0 || ch.entity_id.len()>160 || ch.entity_id.is_empty() {return Err("Invalid sequence or note ID".into())}
        prev=ch.seq;
        let seen:bool=tx.query_row("SELECT EXISTS(SELECT 1 FROM imported_changes WHERE origin_device=?1 AND origin_seq=?2)",params![bundle.origin_device,ch.seq],|r|r.get(0)).map_err(|e|e.to_string())?;
        if seen {out.skipped+=1;continue}
        if ch.operation!="upsert" && ch.operation!="delete" {return Err("Unknown operation".into())}
        // Validate the entire incoming mutation before deciding whether to apply or
        // preserve it as a conflict. Invalid payloads must never enter conflict storage.
        if ch.operation=="upsert" {
            let raw=ch.payload.as_deref().ok_or("Missing upsert payload")?;
            let note:Note=serde_json::from_str(raw).map_err(|e|format!("Invalid note payload: {e}"))?;
            if note.id!=ch.entity_id || note.updated_at!=ch.changed_at || note.id.is_empty()
                || note.id.len()>160 || note.title.len()>2000 || note.body.len()>1_000_000
                || !(0..=5).contains(&note.color) {
                return Err("Invalid remote note".into());
            }
        } else if ch.payload.is_some() {
            return Err("Delete must not include payload".into());
        }
        let local_ts:Option<i64>=tx.query_row("SELECT updated_at FROM notes WHERE id=?1",params![ch.entity_id],|r|r.get(0)).optional().map_err(|e|e.to_string())?;
        let local_tomb:Option<i64>=tx.query_row("SELECT deleted_at FROM note_tombstones WHERE note_id=?1",params![ch.entity_id],|r|r.get(0)).optional().map_err(|e|e.to_string())?;
        let local_origin:Option<String>=tx.query_row("SELECT origin_device FROM note_origin WHERE note_id=?1",params![ch.entity_id],|r|r.get(0)).optional().map_err(|e|e.to_string())?;
        // Preserve divergences rather than silently using clock-based last-write-wins.
        // Without a shared base revision, a locally existing foreign note may conflict.
        let same_remote_lineage=local_origin.as_deref()==Some(bundle.origin_device.as_str());
        let same_remote_deletion:Option<String>=tx.query_row("SELECT origin_device FROM note_tombstones WHERE note_id=?1",params![ch.entity_id],|r|r.get(0)).optional().map_err(|e|e.to_string())?;
        let conflict=(local_ts.is_some() && !same_remote_lineage && local_ts!=Some(ch.changed_at))
          || (local_tomb.is_some() && same_remote_deletion.as_deref()!=Some(bundle.origin_device.as_str()));
        if conflict {
            tx.execute("INSERT INTO sync_conflicts(note_id,origin_device,origin_seq,operation,payload,changed_at) VALUES (?1,?2,?3,?4,?5,?6)",params![ch.entity_id,bundle.origin_device,ch.seq,ch.operation,ch.payload,ch.changed_at]).map_err(|e|e.to_string())?;
            out.conflicts+=1;
        } else if ch.operation=="upsert" {
            let payload=ch.payload.as_deref().ok_or("Missing upsert payload")?;
            let note:Note=serde_json::from_str(payload).map_err(|e|format!("Invalid note payload: {e}"))?;
            if note.id!=ch.entity_id || note.updated_at!=ch.changed_at || note.id.is_empty() || note.id.len()>160 || note.title.len()>2000 || note.body.len()>1_000_000 || !(0..=5).contains(&note.color) {return Err("Invalid remote note".into())}
            tx.execute("INSERT INTO notes(id,title,body,color,checklist,updated_at,pinned,archived,reminder_at) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9) ON CONFLICT(id) DO UPDATE SET title=excluded.title,body=excluded.body,color=excluded.color,checklist=excluded.checklist,updated_at=excluded.updated_at,pinned=excluded.pinned,archived=excluded.archived,reminder_at=excluded.reminder_at",params![note.id,note.title,note.body,note.color,note.is_checklist as i32,note.updated_at,note.pinned as i32,note.archived as i32,note.reminder_at]).map_err(|e|e.to_string())?;
            tx.execute("INSERT INTO note_origin(note_id,origin_device) VALUES (?1,?2) ON CONFLICT(note_id) DO UPDATE SET origin_device=excluded.origin_device",params![note.id,bundle.origin_device]).map_err(|e|e.to_string())?;
            out.applied+=1;
        } else {
            if ch.payload.is_some(){return Err("Delete must not include payload".into())}
            tx.execute("DELETE FROM notes WHERE id=?1",params![ch.entity_id]).map_err(|e|e.to_string())?;
            tx.execute("INSERT INTO note_tombstones(note_id,deleted_at,origin_device) VALUES (?1,?2,?3) ON CONFLICT(note_id) DO UPDATE SET deleted_at=excluded.deleted_at,origin_device=excluded.origin_device",params![ch.entity_id,ch.changed_at,bundle.origin_device]).map_err(|e|e.to_string())?;
            out.applied+=1;
        }
        tx.execute("INSERT INTO imported_changes(origin_device,origin_seq) VALUES (?1,?2)",params![bundle.origin_device,ch.seq]).map_err(|e|e.to_string())?;
    }
    tx.commit().map_err(|e|e.to_string())?;
    Ok(out)
}


/// Read the last imported sequence from a particular device.
pub fn peer_cursor(conn:&Connection,peer:&str)->Result<i64,String>{
    conn.query_row("SELECT last_seq FROM sync_peer_cursors WHERE peer_device=?1",[peer],|r|r.get(0))
      .optional().map(|v|v.unwrap_or(0)).map_err(|e|e.to_string())
}
/// A cursor is saved *after* the changes have been committed. Replaying a page is safe.
pub fn advance_peer_cursor(conn:&Connection,peer:&str,seq:i64)->Result<(),String>{
    if Uuid::parse_str(peer).is_err()||seq<0{return Err("Invalid peer cursor".into())}
    conn.execute("INSERT INTO sync_peer_cursors(peer_device,last_seq) VALUES (?1,?2) ON CONFLICT(peer_device) DO UPDATE SET last_seq=MAX(last_seq,excluded.last_seq)",params![peer,seq]).map_err(|e|e.to_string())?;
    Ok(())
}
/// Returns source identity, terminal sequence and number of entries for an already validated page.
pub fn bundle_meta(payload:&str)->Result<(String,i64,usize),String>{
    let b:SyncBundle=serde_json::from_str(payload).map_err(|e|e.to_string())?;
    Ok((b.origin_device,b.changes.last().map(|x|x.seq).unwrap_or(0),b.changes.len()))
}
