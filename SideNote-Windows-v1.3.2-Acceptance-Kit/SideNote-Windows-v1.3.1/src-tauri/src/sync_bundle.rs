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
    #[serde(default,skip_serializing_if="Option::is_none")] pub base_updated_at:Option<i64>,
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
    let mut stmt=conn.prepare("SELECT seq, entity_id, operation, payload, changed_at, base_updated_at FROM sync_changes WHERE seq > ?1 AND origin_device = ?2 ORDER BY seq LIMIT ?3").map_err(|e|e.to_string())?;
    let all_changes=stmt.query_map(params![after_seq,device,MAX_CHANGES as i64], |r|Ok(BundleChange{seq:r.get(0)?,entity_id:r.get(1)?,operation:r.get(2)?,payload:r.get(3)?,changed_at:r.get(4)?,base_updated_at:r.get(5)?})).map_err(|e|e.to_string())?.collect::<rusqlite::Result<Vec<_>>>().map_err(|e|e.to_string())?;
    let mut bytes=256usize;let mut changes=Vec::new();
    for change in all_changes {let size=serde_json::to_vec(&change).map_err(|e|e.to_string())?.len();if bytes+size>4_500_000 {if changes.is_empty(){return Err("Note metadata exceeds sync page budget".into())}break}bytes+=size;changes.push(change);}
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
            crate::attachments::validate_files(&note.attachments)?;
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
        let based_on_local=if ch.operation=="upsert" {serde_json::from_str::<Note>(ch.payload.as_deref().unwrap_or("")).ok().and_then(|n|n.base_updated_at).map(|base|Some(base)==local_ts).unwrap_or(false)}else{ch.base_updated_at.map(|base|Some(base)==local_ts).unwrap_or(false)};
        let conflict=(local_ts.is_some() && !same_remote_lineage && local_ts!=Some(ch.changed_at) && !based_on_local)
          || (ch.operation=="upsert" && local_tomb.is_some() && same_remote_deletion.as_deref()!=Some(bundle.origin_device.as_str()));
        if conflict {
            tx.execute("INSERT INTO sync_conflicts(note_id,origin_device,origin_seq,operation,payload,changed_at) VALUES (?1,?2,?3,?4,?5,?6)",params![ch.entity_id,bundle.origin_device,ch.seq,ch.operation,ch.payload,ch.changed_at]).map_err(|e|e.to_string())?;
            out.conflicts+=1;
        } else if ch.operation=="upsert" {
            let payload=ch.payload.as_deref().ok_or("Missing upsert payload")?;
            let note:Note=serde_json::from_str(payload).map_err(|e|format!("Invalid note payload: {e}"))?;
            crate::attachments::validate_files(&note.attachments)?;
            if note.id!=ch.entity_id || note.updated_at!=ch.changed_at || note.id.is_empty() || note.id.len()>160 || note.title.len()>2000 || note.body.len()>1_000_000 || !(0..=5).contains(&note.color) {return Err("Invalid remote note".into())}
            crate::store_note(&tx,&note)?;
            tx.execute("DELETE FROM note_tombstones WHERE note_id=?1",[&note.id]).map_err(|e|e.to_string())?;
            tx.execute("INSERT INTO note_origin(note_id,origin_device) VALUES (?1,?2) ON CONFLICT(note_id) DO UPDATE SET origin_device=excluded.origin_device",params![note.id,bundle.origin_device]).map_err(|e|e.to_string())?;
            out.applied+=1;
        } else {
            if ch.payload.is_some(){return Err("Delete must not include payload".into())}
            crate::recovery::remember(&tx,&ch.entity_id)?;
                    tx.execute("DELETE FROM notes WHERE id=?1",params![ch.entity_id]).map_err(|e|e.to_string())?;
            tx.execute("INSERT INTO note_tombstones(note_id,deleted_at,origin_device) VALUES (?1,?2,?3) ON CONFLICT(note_id) DO UPDATE SET deleted_at=excluded.deleted_at,origin_device=excluded.origin_device",params![ch.entity_id,ch.changed_at,bundle.origin_device]).map_err(|e|e.to_string())?;
            out.applied+=1;
        }
        // Windows is the sync hub: relay accepted mutations to other phones and
        // future clients. Android imports never create a new local change, and
        // imported_changes makes retries idempotent, so this cannot loop.
        if !conflict {
            tx.execute("INSERT INTO sync_changes(entity_id,operation,payload,changed_at,origin_device,base_updated_at) VALUES (?1,?2,?3,?4,?5,?6)",params![ch.entity_id,ch.operation,ch.payload,ch.changed_at,self_id,ch.base_updated_at]).map_err(|e|e.to_string())?;
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
#[cfg(test)]
mod regression_tests {
 use super::*;
 fn database()->Connection {
  let c=Connection::open_in_memory().unwrap();
  c.execute_batch("CREATE TABLE notes(id TEXT PRIMARY KEY,title TEXT,body TEXT,color INTEGER,checklist INTEGER,updated_at INTEGER,pinned INTEGER,archived INTEGER,reminder_at INTEGER,attachments TEXT DEFAULT '[]',base_updated_at INTEGER);CREATE TABLE sync_meta(key TEXT PRIMARY KEY,value TEXT);CREATE TABLE sync_changes(seq INTEGER PRIMARY KEY AUTOINCREMENT,entity_id TEXT,operation TEXT,payload TEXT,changed_at INTEGER,origin_device TEXT,base_updated_at INTEGER);CREATE TABLE imported_changes(origin_device TEXT,origin_seq INTEGER,PRIMARY KEY(origin_device,origin_seq));CREATE TABLE note_origin(note_id TEXT PRIMARY KEY,origin_device TEXT);CREATE TABLE note_tombstones(note_id TEXT PRIMARY KEY,deleted_at INTEGER,origin_device TEXT);CREATE TABLE sync_conflicts(id INTEGER PRIMARY KEY AUTOINCREMENT,note_id TEXT,origin_device TEXT,origin_seq INTEGER,operation TEXT,payload TEXT,changed_at INTEGER);CREATE TABLE sync_peer_cursors(peer_device TEXT PRIMARY KEY,last_seq INTEGER);").unwrap();
  c.execute("INSERT INTO sync_meta VALUES('device_id',?1)",[Uuid::new_v4().to_string()]).unwrap();c
 }
 fn note(revision:i64,base:Option<i64>)->Note {serde_json::from_value(serde_json::json!({"id":"sync-test","title":"Test","body":"[ ] Task","color":1,"isChecklist":true,"updatedAt":revision,"pinned":false,"archived":false,"reminderAt":0,"attachments":[],"baseUpdatedAt":base})).unwrap()}
 fn bundle(origin:&str,entries:Vec<serde_json::Value>)->String {serde_json::json!({"protocolVersion":1,"originDevice":origin,"changes":entries}).to_string()}
 fn upsert(seq:i64,note:&Note)->serde_json::Value {serde_json::json!({"seq":seq,"entityId":note.id,"operation":"upsert","payload":serde_json::to_string(note).unwrap(),"changedAt":note.updated_at})}
 #[test] fn sequential_edit_and_delete_use_base_revision(){let mut db=database();crate::store_note(&db,&note(1000,None)).unwrap();let origin=Uuid::new_v4().to_string();let report=import_bundle(&mut db,&bundle(&origin,vec![upsert(1,&note(2000,Some(1000)))])).unwrap();assert_eq!(report.conflicts,0);assert_eq!(report.applied,1);let deletion=serde_json::json!({"seq":2,"entityId":"sync-test","operation":"delete","payload":null,"changedAt":3000,"baseUpdatedAt":2000});let report=import_bundle(&mut db,&bundle(&origin,vec![deletion])).unwrap();assert_eq!(report.applied,1);assert_eq!(db.query_row("SELECT count(*) FROM notes",[],|r|r.get::<_,i64>(0)).unwrap(),0);}
 #[test] fn concurrent_edit_preserves_local_copy(){let mut db=database();crate::store_note(&db,&note(2000,None)).unwrap();let origin=Uuid::new_v4().to_string();let report=import_bundle(&mut db,&bundle(&origin,vec![upsert(1,&note(3000,Some(1000)))])).unwrap();assert_eq!(report.conflicts,1);assert_eq!(db.query_row("SELECT updated_at FROM notes",[],|r|r.get::<_,i64>(0)).unwrap(),2000);}
 #[test] fn malformed_page_rolls_back_every_mutation(){let mut db=database();let origin=Uuid::new_v4().to_string();let mut bad=upsert(2,&note(2000,None));bad["entityId"]=serde_json::json!("wrong-id");assert!(import_bundle(&mut db,&bundle(&origin,vec![upsert(1,&note(1000,None)),bad])).is_err());assert_eq!(db.query_row("SELECT count(*) FROM notes",[],|r|r.get::<_,i64>(0)).unwrap(),0);}
 #[test] fn attachment_metadata_and_large_sizes_round_trip(){let mut db=database();let mut n=note(1000,None);n.attachments.push(crate::attachments::Attachment{id:"file-1".into(),name:"archive.any".into(),mime_type:"application/octet-stream".into(),size:5_000_000_000,sha256:"a".repeat(64)});let origin=Uuid::new_v4().to_string();import_bundle(&mut db,&bundle(&origin,vec![upsert(1,&n)])).unwrap();let stored:Note=db.query_row("SELECT id,title,body,color,checklist,updated_at,pinned,archived,reminder_at,attachments,base_updated_at FROM notes",[],crate::row_to_note).unwrap();assert_eq!(stored.attachments,n.attachments);assert_eq!(import_bundle(&mut db,&bundle(&origin,vec![upsert(1,&n)])).unwrap().skipped,1);}
 #[test] fn accepted_foreign_mutations_relay_to_new_peers(){
  let mut db=database();let origin=Uuid::new_v4().to_string();
  import_bundle(&mut db,&bundle(&origin,vec![upsert(1,&note(1000,None))])).unwrap();
  let deletion=serde_json::json!({"seq":2,"entityId":"sync-test","operation":"delete","payload":null,"changedAt":2000,"baseUpdatedAt":1000});
  let incoming=bundle(&origin,vec![deletion]);import_bundle(&mut db,&incoming).unwrap();
  let exported:SyncBundle=serde_json::from_str(&export_bundle(&db,0).unwrap()).unwrap();
  assert_eq!(exported.changes.len(),2);assert_eq!(exported.changes[0].operation,"upsert");assert_eq!(exported.changes[1].operation,"delete");
  assert_ne!(exported.origin_device,origin);
  assert_eq!(import_bundle(&mut db,&incoming).unwrap().skipped,1);
  assert_eq!(serde_json::from_str::<SyncBundle>(&export_bundle(&db,0).unwrap()).unwrap().changes.len(),2);
 }
 #[test] fn conflicted_foreign_change_is_not_relayed(){
  let mut db=database();crate::store_note(&db,&note(2000,None)).unwrap();let origin=Uuid::new_v4().to_string();
  assert_eq!(import_bundle(&mut db,&bundle(&origin,vec![upsert(1,&note(3000,Some(1000)))])).unwrap().conflicts,1);
  assert!(serde_json::from_str::<SyncBundle>(&export_bundle(&db,0).unwrap()).unwrap().changes.is_empty());
 }

 #[test] fn migration_deletes_obsolete_history_even_without_old_tombstones(){
  let db=database();let mut keep=note(2000,None);keep.id="still-live".into();crate::store_note(&db,&keep).unwrap();
  let old=note(1000,None);let own:String=db.query_row("SELECT value FROM sync_meta WHERE key='device_id'",[],|r|r.get(0)).unwrap();
  db.execute("INSERT INTO sync_changes(entity_id,operation,payload,changed_at,origin_device) VALUES (?1,'upsert',?2,?3,?4)",params![old.id,serde_json::to_string(&old).unwrap(),old.updated_at,own]).unwrap();
  crate::migrate_relay_history(&db).unwrap();
  let exported:SyncBundle=serde_json::from_str(&export_bundle(&db,0).unwrap()).unwrap();
  assert_eq!(exported.changes.iter().filter(|c|c.entity_id==old.id).last().unwrap().operation,"delete");
  assert_eq!(exported.changes.iter().filter(|c|c.entity_id==keep.id).last().unwrap().operation,"upsert");
  let count=exported.changes.len();crate::migrate_relay_history(&db).unwrap();assert_eq!(serde_json::from_str::<SyncBundle>(&export_bundle(&db,0).unwrap()).unwrap().changes.len(),count);
  assert_eq!(db.query_row("SELECT COUNT(*) FROM notes",[],|r|r.get::<_,i64>(0)).unwrap(),1);
 }

}
