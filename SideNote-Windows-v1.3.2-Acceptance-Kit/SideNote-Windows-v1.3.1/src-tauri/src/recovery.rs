use std::{fs::{self,File},io::{Read,Write},collections::HashSet};
use rusqlite::{Connection,params};
use serde::{Serialize,Deserialize};
use tauri::{State,Emitter};
use uuid::Uuid;
use crate::{Note,Database};

pub fn initialize(c:&Connection)->Result<(),String>{c.execute("CREATE TABLE IF NOT EXISTS note_trash(id TEXT PRIMARY KEY,payload TEXT NOT NULL,deleted_at INTEGER NOT NULL)",[]).map_err(|e|e.to_string())?;Ok(())}
pub fn remember(c:&Connection,id:&str)->Result<(),String>{
    initialize(c)?;
    let note=c.query_row("SELECT id,title,body,color,checklist,updated_at,pinned,archived,reminder_at,attachments,base_updated_at FROM notes WHERE id=?1",[id],crate::row_to_note);
    if let Ok(note)=note {c.execute("INSERT INTO note_trash VALUES (?1,?2,?3) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,deleted_at=excluded.deleted_at",params![id,serde_json::to_string(&note).map_err(|e|e.to_string())?,now()]).map_err(|e|e.to_string())?;}
    Ok(())
}
fn now()->i64{std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as i64}
#[derive(Serialize)]#[serde(rename_all="camelCase")]pub struct TrashEntry {pub note:Note,pub deleted_at:i64}
#[tauri::command]pub fn trash_notes(db:State<'_,Database>)->Result<Vec<TrashEntry>,String>{
    let c=db.0.lock().map_err(|e|e.to_string())?;initialize(&c)?;
    let mut q=c.prepare("SELECT payload,deleted_at FROM note_trash ORDER BY deleted_at DESC").map_err(|e|e.to_string())?;
    let rows=q.query_map([],|r|Ok((r.get::<_,String>(0)?,r.get::<_,i64>(1)?))).map_err(|e|e.to_string())?.collect::<rusqlite::Result<Vec<_>>>().map_err(|e|e.to_string())?;
    rows.into_iter().map(|(raw,time)|Ok(TrashEntry{note:serde_json::from_str(&raw).map_err(|e|e.to_string())?,deleted_at:time})).collect()
}
#[tauri::command]pub fn restore_trash(app:tauri::AppHandle,db:State<'_,Database>,id:String)->Result<String,String>{
    let mut c=db.0.lock().map_err(|e|e.to_string())?;let tx=c.transaction().map_err(|e|e.to_string())?;
    let raw:String=tx.query_row("SELECT payload FROM note_trash WHERE id=?1",[&id],|r|r.get(0)).map_err(|_|"Deleted note not found")?;
    let mut note:Note=serde_json::from_str(&raw).map_err(|e|e.to_string())?;note.id=Uuid::new_v4().to_string();note.updated_at=now();note.base_updated_at=None;
    crate::store_note(&tx,&note)?;log_upsert(&tx,&note)?;tx.execute("DELETE FROM note_trash WHERE id=?1",[id]).map_err(|e|e.to_string())?;
    tx.commit().map_err(|e|e.to_string())?;let _=app.emit("sidenote_sync_updated",1);crate::lan_sync::notify_peers_note_changed();Ok(note.id)
}
#[tauri::command]pub fn permanently_delete_trash(db:State<'_,Database>,id:String)->Result<(),String>{
    db.0.lock().map_err(|e|e.to_string())?.execute("DELETE FROM note_trash WHERE id=?1",[id]).map_err(|e|e.to_string())?;Ok(())
}
fn log_upsert(c:&Connection,note:&Note)->Result<(),String>{
    let origin:String=c.query_row("SELECT value FROM sync_meta WHERE key='device_id'",[],|r|r.get(0)).map_err(|e|e.to_string())?;
    c.execute("INSERT INTO sync_changes(entity_id,operation,payload,changed_at,origin_device) VALUES (?1,'upsert',?2,?3,?4)",params![note.id,serde_json::to_string(note).map_err(|e|e.to_string())?,note.updated_at,origin]).map_err(|e|e.to_string())?;Ok(())
}
#[derive(Serialize,Deserialize)]struct Manifest {format:String,version:u8,notes:Vec<Note>}
fn list(c:&Connection)->Result<Vec<Note>,String>{
    let mut q=c.prepare("SELECT id,title,body,color,checklist,updated_at,pinned,archived,reminder_at,attachments,base_updated_at FROM notes").map_err(|e|e.to_string())?;
    let rows=q.query_map([],crate::row_to_note).map_err(|e|e.to_string())?.collect::<rusqlite::Result<Vec<_>>>().map_err(|e|e.to_string())?;Ok(rows)
}
/// Only manifest-selected hash keys are copied. ZIP entry names never become disk paths.
pub fn export_archive(c:&Connection,dir:&std::path::Path,path:&std::path::Path)->Result<usize,String>{
    export_snapshot(list(c)?,dir,path)
}
fn export_snapshot(notes:Vec<Note>,dir:&std::path::Path,path:&std::path::Path)->Result<usize,String>{
    let files:std::collections::HashMap<String,crate::attachments::Attachment>=notes.iter().flat_map(|n|n.attachments.iter().cloned()).map(|f|(f.sha256.clone(),f)).collect();
    for file in files.values(){if !crate::attachments::status(dir,file)?.complete{return Err(format!("Download {} before making a complete backup",file.name))}}
    let output=File::create(path).map_err(|e|e.to_string())?;let mut zip=zip::ZipWriter::new(output);
    let options=zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored).large_file(true);
    zip.start_file("notes.json",options).map_err(|e|e.to_string())?;
    zip.write_all(&serde_json::to_vec(&Manifest{format:"SideNoteBackup".into(),version:1,notes}).map_err(|e|e.to_string())?).map_err(|e|e.to_string())?;
    for file in files.values(){zip.start_file(format!("files/{}.bin",file.sha256),options).map_err(|e|e.to_string())?;std::io::copy(&mut File::open(crate::attachments::blob(dir,&file.sha256,false)?).map_err(|e|e.to_string())?,&mut zip).map_err(|e|e.to_string())?;}
    zip.finish().map_err(|e|e.to_string())?;Ok(files.len())
}
pub fn import_archive(c:&mut Connection,dir:&std::path::Path,path:&std::path::Path)->Result<usize,String>{restore_notes(c,read_archive(dir,path)?)}
fn read_archive(dir:&std::path::Path,path:&std::path::Path)->Result<Vec<Note>,String>{
    let mut zip=zip::ZipArchive::new(File::open(path).map_err(|e|e.to_string())?).map_err(|e|e.to_string())?;
    if zip.len()>10001{return Err("Too many backup entries".into())}
    let raw={let mut entry=zip.by_name("notes.json").map_err(|_|"Not a SideNote backup")?;if entry.size()>16_000_000{return Err("Backup manifest is too large".into())}let mut raw=String::new();entry.by_ref().take(16_000_001).read_to_string(&mut raw).map_err(|e|e.to_string())?;raw};
    let manifest:Manifest=serde_json::from_str(&raw).map_err(|e|e.to_string())?;
    if manifest.format!="SideNoteBackup"||manifest.version!=1||manifest.notes.len()>10000{return Err("Unsupported backup".into())}
    let mut ids=HashSet::new();let mut files=std::collections::HashMap::new();
    for note in &manifest.notes {
        if !ids.insert(&note.id)||note.id.is_empty()||note.id.len()>128||note.body.len()>200000||note.title.len()>2000||!(0..=5).contains(&note.color){return Err("Invalid backup note".into())}
        crate::attachments::validate_files(&note.attachments)?;
        for file in &note.attachments{if let Some(old)=files.insert(file.sha256.clone(),file.clone()){if old.size!=file.size{return Err("Conflicting file sizes".into())}}}
    }
    fs::create_dir_all(dir).map_err(|e|e.to_string())?;
    let stage=dir.join(format!("backup-{}",Uuid::new_v4()));fs::create_dir(&stage).map_err(|e|e.to_string())?;
    let result=(||{
        for file in files.values(){
            let mut entry=zip.by_name(&format!("files/{}.bin",file.sha256)).map_err(|_|format!("Backup is missing {}",file.name))?;
            if entry.size()!=file.size{return Err("Incorrect backup file size".into())}
            let target=crate::attachments::blob(&stage,&file.sha256,false)?;let mut output=File::create(&target).map_err(|e|e.to_string())?;
            let bytes=std::io::copy(&mut entry.by_ref().take(file.size+1),&mut output).map_err(|e|e.to_string())?;
            if bytes!=file.size||crate::attachments::hash_file(&target)?!=file.sha256{return Err("Backup file integrity check failed".into())}
        }
        // Publish only verified blobs. A database failure leaves harmless, unreferenced blobs.
        for file in files.values(){let target=crate::attachments::blob(dir,&file.sha256,false)?;if !target.exists() || crate::attachments::hash_file(&target)?!=file.sha256 {fs::rename(crate::attachments::blob(&stage,&file.sha256,false)?,target).map_err(|e|e.to_string())?;}}
        Ok(manifest.notes)
    })();
    // This path is an internally-created UUID child of the app attachment directory.
    let _=fs::remove_dir_all(&stage);result
}
fn restore_notes(c:&mut Connection,notes:Vec<Note>)->Result<usize,String>{
    let tx=c.transaction().map_err(|e|e.to_string())?;let mut count=0;
    for mut note in notes {
        let existing=tx.query_row("SELECT id,title,body,color,checklist,updated_at,pinned,archived,reminder_at,attachments,base_updated_at FROM notes WHERE id=?1",[&note.id],crate::row_to_note).ok();
        if let Some(old)=existing {
            if old.title==note.title&&old.body==note.body&&old.attachments==note.attachments&&old.color==note.color&&old.is_checklist==note.is_checklist&&old.pinned==note.pinned&&old.archived==note.archived&&old.reminder_at==note.reminder_at{continue}
            note.id=Uuid::new_v4().to_string();note.title=format!("{} (restored)",note.title.chars().take(1980).collect::<String>());
        }
        note.updated_at=now();note.base_updated_at=None;crate::store_note(&tx,&note)?;log_upsert(&tx,&note)?;count+=1;
    }
    tx.commit().map_err(|e|e.to_string())?;Ok(count)
}

#[tauri::command]pub async fn complete_backup(app:tauri::AppHandle,db:State<'_,Database>,restore:bool)->Result<String,String>{
    let db=db.0.clone();tauri::async_runtime::spawn_blocking(move||{
        let Some(path)=crate::file_dialog::choose(if restore{""}else{"SideNote-backup.zip"},!restore)? else{return Ok("Cancelled".into())};
        let dir=crate::attachments::directory(&app)?;
        if restore{let notes=read_archive(&dir,&path)?;let mut c=db.lock().map_err(|e|e.to_string())?;let count=restore_notes(&mut c,notes)?;let _=app.emit("sidenote_sync_updated",count);crate::lan_sync::notify_peers_note_changed();Ok(format!("Restored {count} notes and verified their files. Existing notes were kept."))}
        else{let notes={let c=db.lock().map_err(|e|e.to_string())?;list(&c)?};let files=export_snapshot(notes,&dir,&path)?;Ok(format!("Complete backup saved with {files} files."))}
    }).await.map_err(|e|e.to_string())?
}

pub fn resolve_conflict(c:&mut Connection,id:i64,action:&str)->Result<(),String>{
    if !["keep_local","save_copy","use_remote"].contains(&action){return Err("Unsupported conflict action".into())}
    let tx=c.transaction().map_err(|e|e.to_string())?;
    let (note_id,origin,op,raw,time):(String,String,String,Option<String>,i64)=tx.query_row("SELECT note_id,origin_device,operation,payload,changed_at FROM sync_conflicts WHERE id=?1",[id],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?,r.get(4)?))).map_err(|_|"Conflict not found")?;
    let local=tx.query_row("SELECT id,title,body,color,checklist,updated_at,pinned,archived,reminder_at,attachments,base_updated_at FROM notes WHERE id=?1",[&note_id],crate::row_to_note).ok();
    if action=="save_copy" {
        let mut note:Note=serde_json::from_str(raw.as_deref().ok_or("Deleted note has no content to copy")?).map_err(|e|e.to_string())?;
        note.id=Uuid::new_v4().to_string();note.title=format!("{} (other copy)",note.title.chars().take(1980).collect::<String>());note.updated_at=now();note.base_updated_at=None;crate::store_note(&tx,&note)?;log_upsert(&tx,&note)?;
    }
    if action=="use_remote" {
        remember(&tx,&note_id)?;
        if op=="upsert" {
            let note:Note=serde_json::from_str(raw.as_deref().ok_or("Missing incoming note")?).map_err(|e|e.to_string())?;
            crate::store_note(&tx,&note)?;tx.execute("DELETE FROM note_tombstones WHERE note_id=?1",[&note_id]).map_err(|e|e.to_string())?;log_upsert(&tx,&note)?;
            tx.execute("INSERT INTO note_origin VALUES (?1,?2) ON CONFLICT(note_id) DO UPDATE SET origin_device=excluded.origin_device",params![note_id,origin]).map_err(|e|e.to_string())?;
        }else{
            tx.execute("DELETE FROM notes WHERE id=?1",[&note_id]).map_err(|e|e.to_string())?;
            tx.execute("INSERT INTO note_tombstones VALUES (?1,?2,?3) ON CONFLICT(note_id) DO UPDATE SET deleted_at=excluded.deleted_at,origin_device=excluded.origin_device",params![note_id,time,origin]).map_err(|e|e.to_string())?;
        }
    }else if let Some(mut note)=local {
        if op=="delete" {
            remember(&tx,&note.id)?;tx.execute("DELETE FROM notes WHERE id=?1",[&note.id]).map_err(|e|e.to_string())?;note.id=Uuid::new_v4().to_string();note.base_updated_at=None;
        }else{note.base_updated_at=Some(time);}
        note.updated_at=now().max(time+1).max(note.updated_at+1);crate::store_note(&tx,&note)?;log_upsert(&tx,&note)?;
        tx.execute("DELETE FROM note_origin WHERE note_id=?1",[&note.id]).map_err(|e|e.to_string())?;
    }
    tx.execute("DELETE FROM sync_conflicts WHERE note_id=?1",[note_id]).map_err(|e|e.to_string())?;tx.commit().map_err(|e|e.to_string())
}

#[cfg(test)]mod tests {
 use super::*;
 fn database()->Connection {
  let c=Connection::open_in_memory().unwrap();c.execute_batch("CREATE TABLE notes(id TEXT PRIMARY KEY,title TEXT,body TEXT,color INTEGER,checklist INTEGER,updated_at INTEGER,pinned INTEGER,archived INTEGER,reminder_at INTEGER,attachments TEXT DEFAULT '[]',base_updated_at INTEGER);CREATE TABLE sync_meta(key TEXT PRIMARY KEY,value TEXT);CREATE TABLE sync_changes(seq INTEGER PRIMARY KEY AUTOINCREMENT,entity_id TEXT,operation TEXT,payload TEXT,changed_at INTEGER,origin_device TEXT,base_updated_at INTEGER);INSERT INTO sync_meta VALUES('device_id','backup-test-pc');").unwrap();initialize(&c).unwrap();c
 }
 fn note()->Note{serde_json::from_value(serde_json::json!({"id":"backup-note","title":"Keep this","body":"Original body","color":1,"isChecklist":false,"updatedAt":1000,"pinned":false,"archived":false,"reminderAt":0,"attachments":[]})).unwrap()}
 #[test]fn backup_roundtrip_and_changed_metadata_preserves_both_copies(){
  let root=std::env::temp_dir().join(format!("sidenote-backup-test-{}",Uuid::new_v4()));fs::create_dir_all(&root).unwrap();
  let result=(||{let mut source=database();let original=note();crate::store_note(&source,&original).unwrap();let zip=root.join("backup.zip");export_archive(&source,&root,&zip).unwrap();let mut dest=database();assert_eq!(import_archive(&mut dest,&root,&zip).unwrap(),1);assert_eq!(import_archive(&mut dest,&root,&zip).unwrap(),0);let mut changed=original.clone();changed.color=3;changed.pinned=true;crate::store_note(&source,&changed).unwrap();export_archive(&source,&root,&zip).unwrap();assert_eq!(import_archive(&mut dest,&root,&zip).unwrap(),1);let notes=list(&dest).unwrap();assert_eq!(notes.len(),2);assert!(notes.iter().any(|n|n.id==original.id&&n.color==1&&!n.pinned));assert!(notes.iter().any(|n|n.id!=original.id&&n.color==3&&n.pinned));})();
  fs::remove_dir_all(&root).unwrap();result
 }
 #[test]fn corrupt_or_missing_backup_file_does_not_change_notes(){
  let root=std::env::temp_dir().join(format!("sidenote-backup-test-{}",Uuid::new_v4()));fs::create_dir_all(&root).unwrap();let mut c=database();crate::store_note(&c,&note()).unwrap();
  for present in [false,true]{let mut incoming=note();incoming.id="new-note".into();incoming.attachments.push(crate::attachments::Attachment{id:"file".into(),name:"anything.bin".into(),mime_type:"application/octet-stream".into(),size:3,sha256:"b".repeat(64)});let path=root.join("bad.zip");let mut zip=zip::ZipWriter::new(File::create(&path).unwrap());let options=zip::write::SimpleFileOptions::default();zip.start_file("notes.json",options).unwrap();zip.write_all(&serde_json::to_vec(&Manifest{format:"SideNoteBackup".into(),version:1,notes:vec![incoming]}).unwrap()).unwrap();if present{zip.start_file(format!("files/{}.bin","b".repeat(64)),options).unwrap();zip.write_all(&[1,2,3]).unwrap();}zip.finish().unwrap();assert!(import_archive(&mut c,&root,&path).is_err());assert_eq!(list(&c).unwrap().len(),1);assert_eq!(list(&c).unwrap()[0].id,"backup-note");}
  fs::remove_dir_all(root).unwrap();
 }
 #[test]fn trash_preserves_full_note_before_deletion(){let c=database();let n=note();crate::store_note(&c,&n).unwrap();remember(&c,&n.id).unwrap();c.execute("DELETE FROM notes",[]).unwrap();let raw:String=c.query_row("SELECT payload FROM note_trash",[],|r|r.get(0)).unwrap();let saved:Note=serde_json::from_str(&raw).unwrap();assert_eq!(saved.title,n.title);assert_eq!(saved.body,n.body);assert_eq!(saved.color,n.color);}
}
