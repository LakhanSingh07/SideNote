mod sync_bundle;
mod lan_sync;
mod device_identity;
mod key_protection;
use std::{fs, path::PathBuf, sync::{Mutex,Arc}};
use uuid::Uuid;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tauri::{Manager, State};
use tauri_plugin_autostart::MacosLauncher;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{TrayIconBuilder, TrayIconEvent};

struct Database(Arc<Mutex<Connection>>);

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all="camelCase")]
struct Note {
  id: String, title: String, body: String, color: i64,
  is_checklist: bool, updated_at: i64, pinned: bool, archived: bool, reminder_at: i64,
}

fn row_to_note(row: &rusqlite::Row<'_>) -> rusqlite::Result<Note> {
  Ok(Note { id: row.get(0)?, title: row.get(1)?, body: row.get(2)?, color: row.get(3)?, is_checklist: row.get::<_, i64>(4)? != 0, updated_at: row.get(5)?, pinned: row.get::<_,i64>(6)? != 0, archived: row.get::<_,i64>(7)? != 0, reminder_at: row.get(8)? })
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all="camelCase")]
struct WindowPlacement { label:String, x:f64, y:f64, width:f64, height:f64 }

// Protocol v1 groundwork: durable peer-visible local mutation log.
// These commands do NOT connect devices or authenticate remote peers.
#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all="camelCase")]
struct SyncChange {
  seq: i64,
  entity_id: String,
  operation: String,
  payload: Option<String>,
  changed_at: i64,
  origin_device: String,
}

#[tauri::command]
fn lan_generate_key()->String{lan_sync::make_key()}
#[tauri::command]
fn lan_start_server(db:State<'_,Database>,key_hex:String)->Result<String,String>{lan_sync::start_server(db.0.clone(),&key_hex)}
#[tauri::command]
fn lan_sync_peer(db:State<'_,Database>,address:String,key_hex:String)->Result<lan_sync::LanReport,String>{
 lan_sync::sync_peer(db.0.clone(),&address,&key_hex)
}

#[derive(Debug, Serialize)]
#[serde(rename_all="camelCase")]
struct TrustedPeer { device_id:String, label:String, public_key:String }

#[tauri::command]
fn trusted_peers(db:State<'_,Database>)->Result<Vec<TrustedPeer>,String>{
 let c=db.0.lock().map_err(|e|e.to_string())?;
 let mut q=c.prepare("SELECT device_id,label,COALESCE(public_key,'') FROM trusted_peers ORDER BY label").map_err(|e|e.to_string())?;
 let res = q.query_map([],|r|Ok(TrustedPeer{device_id:r.get(0)?,label:r.get(1)?,public_key:r.get(2)?})).map_err(|e|e.to_string())?.collect::<rusqlite::Result<Vec<_>>>().map_err(|e|e.to_string())?;
 Ok(res)
}
#[tauri::command]
fn public_key_fingerprint(public_key:String)->Result<String,String>{
 let bytes=hex::decode(public_key.trim()).map_err(|_|"Public key must be hexadecimal".to_string())?;
 if bytes.len()!=32{return Err("Public key must contain exactly 32 bytes".into())}
 let digest=Sha256::digest(&bytes);
 let token=hex::encode(&digest[..16]).to_uppercase();
 Ok(token.as_bytes().chunks(4).map(|c|std::str::from_utf8(c).unwrap_or("")).collect::<Vec<_>>().join("-"))
}

#[tauri::command]
fn trust_peer(db:State<'_,Database>,device_id:String,label:String,public_key:String)->Result<(),String>{
 let device_id=Uuid::parse_str(device_id.trim()).map_err(|_|"Invalid device UUID".to_string())?.to_string();
 let label=label.trim();if label.is_empty()||label.chars().count()>64{return Err("Label must be 1–64 characters".into())}
 let raw=hex::decode(public_key.trim()).map_err(|_|"Public key must be hexadecimal".to_string())?;
 if raw.len()!=32 {return Err("Public key must contain 32 bytes".into())}
 let public_key=hex::encode(raw);
 let c=db.0.lock().map_err(|e|e.to_string())?;
 let existing:Option<String>=c.query_row("SELECT public_key FROM trusted_peers WHERE device_id=?1",[&device_id],|r|r.get(0)).optional().map_err(|e|e.to_string())?;
 if let Some(old)=existing {
  if !old.is_empty() && old != public_key {return Err("Trusted key changed! Remove device first, independently verify the new fingerprint, then pair again.".into())}
 }
 let own:String=c.query_row("SELECT value FROM sync_meta WHERE key='device_id'",[],|r|r.get(0)).map_err(|e|e.to_string())?;
 if own==device_id{return Err("Cannot trust the current device as a peer".into())}
 c.execute("INSERT INTO trusted_peers(device_id,label,public_key) VALUES (?1,?2,?3) ON CONFLICT(device_id) DO UPDATE SET label=excluded.label,public_key=excluded.public_key",params![device_id,label,public_key]).map_err(|e|e.to_string())?;Ok(())
}
#[tauri::command]
fn untrust_peer(db:State<'_,Database>,device_id:String)->Result<(),String>{
 let id=Uuid::parse_str(device_id.trim()).map_err(|_|"Invalid device UUID".to_string())?.to_string();
 let c=db.0.lock().map_err(|e|e.to_string())?;
 c.execute("DELETE FROM trusted_peers WHERE device_id=?1",[id]).map_err(|e|e.to_string())?; Ok(())
}

#[tauri::command]
fn local_device_public_key(db:State<'_,Database>)->Result<String,String>{
 let c=db.0.lock().map_err(|e|e.to_string())?;
 Ok(key_protection::load_identity(&c)?.public_key_hex())
}

#[tauri::command]
fn local_device_id(db:State<'_,Database>) -> Result<String,String> {
 let c=db.0.lock().map_err(|e|e.to_string())?;
 c.query_row("SELECT value FROM sync_meta WHERE key='device_id'", [], |r|r.get(0)).map_err(|e|e.to_string())
}

#[tauri::command]
fn export_local_changes(db:State<'_,Database>, after_seq:i64, limit:Option<i64>) -> Result<Vec<SyncChange>,String> {
 if after_seq<0 {return Err("Invalid cursor".into())}
 let cap=limit.unwrap_or(200).clamp(1,500);
 let c=db.0.lock().map_err(|e|e.to_string())?;
 let mut stmt=c.prepare("SELECT seq,entity_id,operation,payload,changed_at,origin_device FROM sync_changes WHERE seq>?1 ORDER BY seq LIMIT ?2").map_err(|e|e.to_string())?;
 let rows=stmt.query_map(params![after_seq,cap], |r|Ok(SyncChange{seq:r.get(0)?,entity_id:r.get(1)?,operation:r.get(2)?,payload:r.get(3)?,changed_at:r.get(4)?,origin_device:r.get(5)?})).map_err(|e|e.to_string())?;
 rows.collect::<rusqlite::Result<Vec<_>>>().map_err(|e|e.to_string())
}

#[tauri::command]
fn export_sync_bundle(db:State<'_,Database>, after_seq:i64) -> Result<String,String> {
 let c=db.0.lock().map_err(|e|e.to_string())?;
 sync_bundle::export_bundle(&c,after_seq)
}

#[tauri::command]
fn import_sync_bundle(db:State<'_,Database>, bundle_json:String) -> Result<sync_bundle::ImportReport,String> {
 let mut c=db.0.lock().map_err(|e|e.to_string())?;
 sync_bundle::import_bundle(&mut c,&bundle_json)
}


#[derive(Debug, Serialize)]
#[serde(rename_all="camelCase")]
struct ConflictEntry {
  id:i64, note_id:String, origin_device:String, operation:String,
  payload:Option<String>, changed_at:i64,
}

#[tauri::command]
fn list_sync_conflicts(db:State<'_,Database>) -> Result<Vec<ConflictEntry>,String> {
 let c=db.0.lock().map_err(|e|e.to_string())?;
 let mut s=c.prepare("SELECT id,note_id,origin_device,operation,payload,changed_at FROM sync_conflicts ORDER BY id DESC LIMIT 200").map_err(|e|e.to_string())?;
 let res = s.query_map([],|r| Ok(ConflictEntry{id:r.get(0)?,note_id:r.get(1)?,origin_device:r.get(2)?,operation:r.get(3)?,payload:r.get(4)?,changed_at:r.get(5)?})).map_err(|e|e.to_string())?.collect::<rusqlite::Result<Vec<_>>>().map_err(|e|e.to_string())?;
 Ok(res)
}

// Resolve explicitly, never silently overwrite local note content.
// "keep_local" acknowledges and preserves the local version; "save_copy" keeps
// local content intact and creates a new independent note for incoming content.
// Remote deletion conflicts cannot be saved as copies and must be kept locally.
#[tauri::command]
fn resolve_sync_conflict(db:State<'_,Database>, conflict_id:i64, action:String) -> Result<(),String> {
 if action!="keep_local" && action!="save_copy" {return Err("Unsupported conflict action".into())}
 let mut c=db.0.lock().map_err(|e|e.to_string())?;
 let tx=c.transaction().map_err(|e|e.to_string())?;
 let (operation,payload):(String,Option<String>)=tx.query_row("SELECT operation,payload FROM sync_conflicts WHERE id=?1",params![conflict_id],|r|Ok((r.get(0)?,r.get(1)?))).map_err(|_|"Conflict not found".to_string())?;
 if action=="save_copy" {
   if operation!="upsert" {return Err("Deleted note has no content to copy".into())}
   let raw=payload.ok_or("Missing remote note content")?;
   let mut note:Note=serde_json::from_str(&raw).map_err(|e|e.to_string())?;
   if note.title.len()>2000 || note.body.len()>1_000_000 || !(0..=5).contains(&note.color) {return Err("Invalid incoming note".into())}
   note.id=Uuid::new_v4().to_string();
   note.title=format!("{} (conflict copy)",note.title.chars().take(1960).collect::<String>());
   note.updated_at=std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_err(|e|e.to_string())?.as_millis() as i64;
   let origin:String=tx.query_row("SELECT value FROM sync_meta WHERE key='device_id'",[],|r|r.get(0)).map_err(|e|e.to_string())?;
   tx.execute("INSERT INTO notes(id,title,body,color,checklist,updated_at,pinned,archived,reminder_at) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)",params![note.id,note.title,note.body,note.color,note.is_checklist as i32,note.updated_at,note.pinned as i32,note.archived as i32,note.reminder_at]).map_err(|e|e.to_string())?;
   let serialized=serde_json::to_string(&note).map_err(|e|e.to_string())?;
   tx.execute("INSERT INTO sync_changes(entity_id,operation,payload,changed_at,origin_device) VALUES (?1,'upsert',?2,?3,?4)",params![note.id,serialized,note.updated_at,origin]).map_err(|e|e.to_string())?;
 }
 tx.execute("DELETE FROM sync_conflicts WHERE id=?1",params![conflict_id]).map_err(|e|e.to_string())?;
 tx.commit().map_err(|e|e.to_string())
}

#[tauri::command]
fn save_window_placement(db:State<'_,Database>, placement:WindowPlacement)->Result<(),String> {
 if placement.label.len()>160 || !placement.label.starts_with("note-") {return Err("Invalid window label".into())}
 if ![placement.x,placement.y,placement.width,placement.height].iter().all(|v|v.is_finite()) || placement.width<150. || placement.width>2400. || placement.height<100. || placement.height>2000. {return Err("Invalid placement".into())}
 let c=db.0.lock().map_err(|e|e.to_string())?;
 c.execute("INSERT INTO note_windows(label,x,y,width,height) VALUES (?1,?2,?3,?4,?5) ON CONFLICT(label) DO UPDATE SET x=excluded.x,y=excluded.y,width=excluded.width,height=excluded.height",params![placement.label,placement.x,placement.y,placement.width,placement.height]).map_err(|e|e.to_string())?;
 Ok(())
}

#[tauri::command]
fn get_window_placement(db:State<'_,Database>, label:String)->Result<Option<WindowPlacement>,String> {
 let c=db.0.lock().map_err(|e|e.to_string())?;
 let mut stmt=c.prepare("SELECT label,x,y,width,height FROM note_windows WHERE label=?1").map_err(|e|e.to_string())?;
 let mut rows=stmt.query(params![label]).map_err(|e|e.to_string())?;
 if let Some(row)=rows.next().map_err(|e|e.to_string())? {
  Ok(Some(WindowPlacement{label:row.get(0).map_err(|e|e.to_string())?,x:row.get(1).map_err(|e|e.to_string())?,y:row.get(2).map_err(|e|e.to_string())?,width:row.get(3).map_err(|e|e.to_string())?,height:row.get(4).map_err(|e|e.to_string())?}))
 } else {Ok(None)}
}

#[tauri::command]
fn list_notes(db: State<'_,Database>) -> Result<Vec<Note>,String> {
 let connection = db.0.lock().map_err(|e| e.to_string())?;
 let mut stmt = connection.prepare("SELECT id,title,body,color,checklist,updated_at,pinned,archived,reminder_at FROM notes ORDER BY pinned DESC, updated_at DESC").map_err(|e|e.to_string())?;
 let rows = stmt.query_map([],row_to_note).map_err(|e|e.to_string())?;
 rows.collect::<rusqlite::Result<Vec<_>>>().map_err(|e|e.to_string())
}

#[tauri::command]
fn upsert_note(db:State<'_,Database>, note:Note)->Result<(),String> {
 if note.id.is_empty() || note.id.len()>160 || note.title.len()>2000 || note.body.len()>1000000 || !(0..=5).contains(&note.color) {return Err("Invalid note fields".into())}
 let mut c=db.0.lock().map_err(|e|e.to_string())?;
 let tx=c.transaction().map_err(|e|e.to_string())?;
 let origin:String=tx.query_row("SELECT value FROM sync_meta WHERE key='device_id'",[],|r|r.get(0)).map_err(|e|e.to_string())?;
 // Preserve on-disk payload in sync log so future peers can replay this exact revision.
 let payload=serde_json::to_string(&note).map_err(|e|e.to_string())?;
 tx.execute("INSERT INTO notes (id,title,body,color,checklist,updated_at,pinned,archived,reminder_at) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9) ON CONFLICT(id) DO UPDATE SET title=excluded.title,body=excluded.body,color=excluded.color,checklist=excluded.checklist,updated_at=excluded.updated_at,pinned=excluded.pinned,archived=excluded.archived,reminder_at=excluded.reminder_at", params![note.id,note.title,note.body,note.color,note.is_checklist as i32,note.updated_at,note.pinned as i32,note.archived as i32,note.reminder_at]).map_err(|e|e.to_string())?;
 tx.execute("DELETE FROM note_origin WHERE note_id=?1",params![note.id]).map_err(|e|e.to_string())?;
 tx.execute("DELETE FROM note_tombstones WHERE note_id=?1",params![note.id]).map_err(|e|e.to_string())?;
 tx.execute("INSERT INTO sync_changes(entity_id,operation,payload,changed_at,origin_device) VALUES (?1,'upsert',?2,?3,?4)",params![note.id,payload,note.updated_at,origin]).map_err(|e|e.to_string())?;
 tx.commit().map_err(|e|e.to_string())
}

#[tauri::command]
fn delete_note(db:State<'_,Database>, id:String)->Result<(),String> {
 if id.is_empty() || id.len()>160 {return Err("Invalid note ID".into())}
 let mut c=db.0.lock().map_err(|e|e.to_string())?;
 let tx=c.transaction().map_err(|e|e.to_string())?;
 let origin:String=tx.query_row("SELECT value FROM sync_meta WHERE key='device_id'",[],|r|r.get(0)).map_err(|e|e.to_string())?;
 let existed:i64=tx.query_row("SELECT COUNT(*) FROM notes WHERE id=?1",params![id],|r|r.get(0)).map_err(|e|e.to_string())?;
 if existed>0 {
  let now=std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_err(|e|e.to_string())?.as_millis() as i64;
  tx.execute("DELETE FROM notes WHERE id=?1",params![id]).map_err(|e|e.to_string())?;
  tx.execute("DELETE FROM note_origin WHERE note_id=?1",params![id]).map_err(|e|e.to_string())?;
  tx.execute("INSERT INTO note_tombstones(note_id,deleted_at,origin_device) VALUES (?1,?2,?3) ON CONFLICT(note_id) DO UPDATE SET deleted_at=excluded.deleted_at,origin_device=excluded.origin_device",params![id,now,origin]).map_err(|e|e.to_string())?;
  tx.execute("INSERT INTO sync_changes(entity_id,operation,payload,changed_at,origin_device) VALUES (?1,'delete',NULL,?2,?3)",params![id,now,origin]).map_err(|e|e.to_string())?;
 }
 tx.commit().map_err(|e|e.to_string())
}

fn db_path(app:&tauri::App) -> Result<PathBuf,String> {
 let dir=app.path().app_data_dir().map_err(|e|e.to_string())?;
 fs::create_dir_all(&dir).map_err(|e|e.to_string())?;
 Ok(dir.join("sidenote.sqlite3"))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run(){
 tauri::Builder::default()
 .plugin(tauri_plugin_opener::init())
 .plugin(tauri_plugin_global_shortcut::Builder::new().build())
 .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, None))
 .setup(|app| {
  let path=db_path(app).map_err(std::io::Error::other)?;
  let c=Connection::open(path)?;
  c.execute_batch("PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000; CREATE TABLE IF NOT EXISTS notes (id TEXT PRIMARY KEY,title TEXT NOT NULL,body TEXT NOT NULL,color INTEGER NOT NULL,checklist INTEGER NOT NULL,updated_at INTEGER NOT NULL,pinned INTEGER NOT NULL,archived INTEGER NOT NULL,reminder_at INTEGER NOT NULL); CREATE TABLE IF NOT EXISTS note_windows (label TEXT PRIMARY KEY,x REAL NOT NULL,y REAL NOT NULL,width REAL NOT NULL,height REAL NOT NULL);")?;
  c.execute_batch("CREATE TABLE IF NOT EXISTS trusted_peers(device_id TEXT PRIMARY KEY,label TEXT NOT NULL,public_key TEXT); CREATE TABLE IF NOT EXISTS sync_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL); CREATE TABLE IF NOT EXISTS sync_changes(seq INTEGER PRIMARY KEY AUTOINCREMENT,entity_id TEXT NOT NULL,operation TEXT NOT NULL CHECK(operation IN ('upsert','delete')),payload TEXT,changed_at INTEGER NOT NULL,origin_device TEXT NOT NULL); CREATE INDEX IF NOT EXISTS idx_sync_changes_entity ON sync_changes(entity_id); CREATE TABLE IF NOT EXISTS note_tombstones(note_id TEXT PRIMARY KEY,deleted_at INTEGER NOT NULL,origin_device TEXT NOT NULL); CREATE TABLE IF NOT EXISTS imported_changes(origin_device TEXT NOT NULL,origin_seq INTEGER NOT NULL,PRIMARY KEY(origin_device,origin_seq)); CREATE TABLE IF NOT EXISTS note_origin(note_id TEXT PRIMARY KEY,origin_device TEXT NOT NULL); CREATE TABLE IF NOT EXISTS sync_peer_cursors(peer_device TEXT PRIMARY KEY,last_seq INTEGER NOT NULL DEFAULT 0 CHECK(last_seq>=0)); CREATE TABLE IF NOT EXISTS sync_conflicts(id INTEGER PRIMARY KEY AUTOINCREMENT,note_id TEXT NOT NULL,origin_device TEXT NOT NULL,origin_seq INTEGER NOT NULL,operation TEXT NOT NULL,payload TEXT,changed_at INTEGER NOT NULL);")?;
  // Upgrade previous installations: legacy UUID-only allowlist rows have no pinned public key and cannot authenticate.
  let has_key:bool=c.prepare("PRAGMA table_info(trusted_peers)")?.query_map([],|r|r.get::<_,String>(1))?.filter_map(Result::ok).any(|v|v=="public_key");
  if !has_key {c.execute("ALTER TABLE trusted_peers ADD COLUMN public_key TEXT",[])?;}
  c.execute("INSERT OR IGNORE INTO sync_meta(key,value) VALUES ('device_id',?1)",params![Uuid::new_v4().to_string()])?;
  key_protection::migrate_or_create(&c).map_err(std::io::Error::other)?;
  app.manage(Database(Arc::new(Mutex::new(c))));
  let open=MenuItem::with_id(app,"open","Open SideNote Dock",true,None::<&str>)?;
  let library=MenuItem::with_id(app,"library","All Notes & Settings",true,None::<&str>)?;
  let quit=MenuItem::with_id(app,"quit","Quit SideNote",true,None::<&str>)?;
  let menu=Menu::with_items(app,&[&open,&library,&quit])?;
  TrayIconBuilder::new()
    .menu(&menu)
    .show_menu_on_left_click(false)
    .on_menu_event(|app,event| match event.id().as_ref(){
      "open"=>{if let Some(w)=app.get_webview_window("main"){let _=w.show();let _=w.set_focus();}},
      "library"=>{
        if let Some(w)=app.get_webview_window("library"){let _=w.show();let _=w.set_focus();}
        else {
          let _ = tauri::WebviewWindowBuilder::new(app, "library", tauri::WebviewUrl::App("/?view=library".into()))
            .title("SideNote — All Notes & Sync")
            .inner_size(880.0, 620.0)
            .center()
            .build();
        }
      },
      "quit"=>app.exit(0),
      _=>{}
    })
    .on_tray_icon_event(|tray,event|{
      if let TrayIconEvent::DoubleClick {..}=event {
       if let Some(w)=tray.app_handle().get_webview_window("main"){let _=w.show();let _=w.set_focus();}
      }
    }).build(app)?;
  Ok(())
 })
 .on_window_event(|window, event| {
   if window.label() == "main" || window.label() == "library" {
     if let tauri::WindowEvent::CloseRequested { api, .. } = event {
       api.prevent_close();
       let _ = window.hide();
     }
   }
 })
 .invoke_handler(tauri::generate_handler![lan_generate_key,lan_start_server,lan_sync_peer,local_device_public_key,public_key_fingerprint,trusted_peers,trust_peer,untrust_peer,list_notes,upsert_note,delete_note,save_window_placement,get_window_placement,local_device_id,export_local_changes,export_sync_bundle,import_sync_bundle,list_sync_conflicts,resolve_sync_conflict])
 .run(tauri::generate_context!())
 .expect("error while running SideNote");
}

#[cfg(test)]
mod fingerprint_tests {
 use super::*;
 #[test]
 fn fingerprint_groups_bytes(){
  let p="00".repeat(32);
  let f=public_key_fingerprint(p).unwrap();
  assert_eq!(f.split('-').count(),8);
  assert_eq!(f.len(),39);
 }
 #[test]
 fn fingerprint_rejects_invalid_keys(){
  assert!(public_key_fingerprint("abc".into()).is_err());
  assert!(public_key_fingerprint("00".repeat(31)).is_err());
 }
}
