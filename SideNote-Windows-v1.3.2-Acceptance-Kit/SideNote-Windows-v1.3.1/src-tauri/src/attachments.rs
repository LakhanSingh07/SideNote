use std::{fs::{self, File, OpenOptions}, io::{Read, Seek, SeekFrom, Write}, path::{Path, PathBuf}, sync::{Arc, Mutex, OnceLock}, collections::HashMap};
use serde::{Serialize, Deserialize};
use sha2::{Digest,Sha256};
use tauri::Manager;
use tauri_plugin_opener::OpenerExt;
use uuid::Uuid;

pub const CHUNK_SIZE: usize = 512 * 1024;
#[derive(Debug,Clone,Serialize,Deserialize,PartialEq)]
#[serde(rename_all="camelCase")]
pub struct Attachment { pub id:String, pub name:String, pub mime_type:String, pub size:u64, pub sha256:String }
impl Attachment {
    pub fn validate(&self)->Result<(),String> {
        if self.id.is_empty() || self.id.len()>128 || self.name.is_empty() || self.name.len()>4096 || self.mime_type.len()>255 || self.size>9_007_199_254_740_991 || !valid_hash(&self.sha256) {return Err("Invalid attachment metadata".into())} Ok(())
    }
}
pub fn validate_files(files:&[Attachment])->Result<(),String>{
    if files.len()>1000 {return Err("Too many attachments in one note".into())}
    let mut ids=std::collections::HashSet::new();
    for file in files {file.validate()?;if !ids.insert(&file.id){return Err("Duplicate attachment ID".into())}} Ok(())
}
fn valid_hash(hash:&str)->bool{hash.len()==64 && hash.bytes().all(|c|c.is_ascii_digit() || (b'a'..=b'f').contains(&c))}
pub fn directory(app:&tauri::AppHandle)->Result<PathBuf,String>{let dir=app.path().app_data_dir().map_err(|e|e.to_string())?.join("attachments");fs::create_dir_all(&dir).map_err(|e|e.to_string())?;Ok(dir)}
pub fn blob(dir:&Path,hash:&str,partial:bool)->Result<PathBuf,String>{if !valid_hash(hash){return Err("Invalid file hash".into())}Ok(dir.join(format!("{hash}.{}",if partial{"part"}else{"bin"})))}
pub fn hash_file(path:&Path)->Result<String,String>{let mut file=File::open(path).map_err(|e|e.to_string())?;let mut digest=Sha256::new();let mut buffer=vec![0;CHUNK_SIZE];loop{let n=file.read(&mut buffer).map_err(|e|e.to_string())?;if n==0{break}digest.update(&buffer[..n]);}Ok(hex::encode(digest.finalize()))}
fn import_path(dir:&Path,id:&str)->Result<PathBuf,String>{Uuid::parse_str(id).map_err(|_|"Invalid import session")?;Ok(dir.join(format!("{id}.import")))}
fn file_lock(hash:&str)->Arc<Mutex<()>>{static LOCKS:OnceLock<Mutex<HashMap<String,Arc<Mutex<()>>>>>=OnceLock::new();let mut locks=LOCKS.get_or_init(||Mutex::new(HashMap::new())).lock().unwrap();locks.entry(hash.into()).or_insert_with(||Arc::new(Mutex::new(()))).clone()}

static TRANSFERS:OnceLock<Mutex<HashMap<String,String>>>=OnceLock::new();
pub fn set_transfer(hash:&str,message:String){if let Ok(mut states)=TRANSFERS.get_or_init(||Mutex::new(HashMap::new())).lock(){states.insert(hash.into(),message);}}
#[derive(Serialize)]
#[serde(rename_all="camelCase")]
pub struct FileStatus {pub id:String,pub complete:bool,pub bytes:u64,pub transfer:Option<String>}
pub fn status(dir:&Path,file:&Attachment)->Result<FileStatus,String>{
    let full=blob(dir,&file.sha256,false)?;let partial=blob(dir,&file.sha256,true)?;
    let complete=full.metadata().map(|m|m.is_file() && m.len()==file.size).unwrap_or(false);
    let bytes=if complete{file.size}else{partial.metadata().map(|m|m.len()).unwrap_or(0).min(file.size)};
    Ok(FileStatus{id:file.id.clone(),complete,bytes,transfer:TRANSFERS.get_or_init(||Mutex::new(HashMap::new())).lock().ok().and_then(|states|states.get(&file.sha256).cloned())})
}
/// Each request carries at most one bounded raw chunk. Parts survive disconnection and restart.
pub fn receive_chunk(dir:&Path,file:&Attachment,offset:u64,data:&[u8])->Result<FileStatus,String>{
    file.validate()?;
    if data.len()>CHUNK_SIZE || offset>file.size || data.len() as u64>file.size-offset{return Err("Invalid file chunk".into())}
    let lock=file_lock(&file.sha256);let _guard=lock.lock().map_err(|e|e.to_string())?;
    if status(dir,file)?.complete{return status(dir,file)}
    let path=blob(dir,&file.sha256,true)?;
    let mut target=OpenOptions::new().create(true).append(true).open(&path).map_err(|e|e.to_string())?;
    if target.metadata().map_err(|e|e.to_string())?.len()!=offset{return Err("File offset changed; retry from status".into())}
    target.write_all(data).map_err(|e|e.to_string())?;
    if offset+data.len() as u64==file.size {
        target.sync_all().map_err(|e|e.to_string())?;drop(target);
        if hash_file(&path)?!=file.sha256 {fs::remove_file(&path).map_err(|e|e.to_string())?;return Err("File integrity check failed; retry transfer".into())}
        fs::rename(path,blob(dir,&file.sha256,false)?).map_err(|e|e.to_string())?;
    }
    let state=status(dir,file)?;
    set_transfer(&file.sha256,if state.complete {"Received from paired device".into()}else{format!("Receiving {}%",state.bytes as f64/file.size.max(1) as f64*100.0)});
    status(dir,file)
}
pub fn read_chunk(dir:&Path,file:&Attachment,offset:u64,length:usize)->Result<Vec<u8>,String>{
    if length>CHUNK_SIZE || offset>file.size || !status(dir,file)?.complete{return Err("File unavailable or invalid range".into())}
    let count=(file.size-offset).min(length as u64) as usize;
    let mut source=File::open(blob(dir,&file.sha256,false)?).map_err(|e|e.to_string())?;source.seek(SeekFrom::Start(offset)).map_err(|e|e.to_string())?;
    let mut bytes=vec![0;count];source.read_exact(&mut bytes).map_err(|e|e.to_string())?;set_transfer(&file.sha256,format!("Sending {:.0}%",(offset+count as u64) as f64/file.size.max(1) as f64*100.0));Ok(bytes)
}

#[tauri::command]
pub fn attachment_import_start(app:tauri::AppHandle)->Result<String,String>{let id=Uuid::new_v4().to_string();File::create(import_path(&directory(&app)?,&id)?).map_err(|e|e.to_string())?;Ok(id)}
#[tauri::command]
pub async fn attachment_import_chunk(app:tauri::AppHandle,import_id:String,offset:u64,data:Vec<u8>)->Result<u64,String>{tauri::async_runtime::spawn_blocking(move||{
    if data.len()>CHUNK_SIZE{return Err("Chunk too large".into())}
    let path=import_path(&directory(&app)?,&import_id)?;
    let mut file=OpenOptions::new().append(true).open(path).map_err(|e|e.to_string())?;
    if file.metadata().map_err(|e|e.to_string())?.len()!=offset{return Err("Import offset mismatch".into())}
    file.write_all(&data).map_err(|e|e.to_string())?;Ok(offset+data.len() as u64)
}).await.map_err(|e|e.to_string())?}
#[tauri::command]
pub fn attachment_import_cancel(app:tauri::AppHandle,import_id:String)->Result<(),String>{let path=import_path(&directory(&app)?,&import_id)?;if path.exists(){fs::remove_file(path).map_err(|e|e.to_string())?}Ok(())}
#[tauri::command]
pub async fn attachment_import_finish(app:tauri::AppHandle,import_id:String,name:String,mime_type:String)->Result<Attachment,String>{tauri::async_runtime::spawn_blocking(move||{
    let dir=directory(&app)?;let source=import_path(&dir,&import_id)?;
    let file=Attachment{id:Uuid::new_v4().to_string(),name,mime_type,size:source.metadata().map_err(|e|e.to_string())?.len(),sha256:hash_file(&source)?};file.validate()?;
    let lock=file_lock(&file.sha256);let _guard=lock.lock().map_err(|e|e.to_string())?;
    let destination=blob(&dir,&file.sha256,false)?;
    if destination.exists(){if hash_file(&destination)?!=file.sha256{return Err("Stored file failed verification".into())}fs::remove_file(source).map_err(|e|e.to_string())?}else{fs::rename(source,destination).map_err(|e|e.to_string())?}
    Ok(file)
}).await.map_err(|e|e.to_string())?}
fn find_note_files(db:&crate::Database,note_id:&str)->Result<Vec<Attachment>,String>{let c=db.0.lock().map_err(|e|e.to_string())?;let raw:String=c.query_row("SELECT attachments FROM notes WHERE id=?1",[note_id],|r|r.get(0)).map_err(|_|"Note not found")?;serde_json::from_str(&raw).map_err(|e|e.to_string())}
#[tauri::command]
pub fn attachment_status(app:tauri::AppHandle,db:tauri::State<'_,crate::Database>,note_id:String)->Result<Vec<FileStatus>,String>{let dir=directory(&app)?;find_note_files(&db,&note_id)?.iter().map(|file|status(&dir,file)).collect()}
#[tauri::command]
pub async fn attachment_open(app:tauri::AppHandle,db:tauri::State<'_,crate::Database>,note_id:String,attachment_id:String)->Result<(),String>{
    let file=find_note_files(&db,&note_id)?.into_iter().find(|f|f.id==attachment_id).ok_or("Attachment not found")?;
    tauri::async_runtime::spawn_blocking(move||{
        let dir=directory(&app)?;if !status(&dir,&file)?.complete{return Err("File has not downloaded yet".into())}
        let safe:String=file.name.chars().filter(|c| !c.is_control() && !"\\/:*?\"<>|".contains(*c)).take(120).collect();
        let safe=safe.trim_matches(['.',' ']);let safe=if safe.is_empty(){"File"}else{safe};
        let export=dir.join("open").join(&file.sha256);fs::create_dir_all(&export).map_err(|e|e.to_string())?;let path=export.join(safe);
        if !path.exists(){fs::copy(blob(&dir,&file.sha256,false)?,&path).map_err(|e|e.to_string())?;}
        app.opener().open_path(path.to_string_lossy().to_string(),None::<&str>).map_err(|e|e.to_string())
    }).await.map_err(|e|e.to_string())?
}
#[tauri::command]
pub async fn attachment_save_as(app:tauri::AppHandle,window:tauri::WebviewWindow,db:tauri::State<'_,crate::Database>,note_id:String,attachment_id:String)->Result<bool,String>{
    let file=find_note_files(&db,&note_id)?.into_iter().find(|f|f.id==attachment_id).ok_or("Attachment not found")?;
    #[cfg(windows)] let owner=window.hwnd().map_err(|e|e.to_string())?.0 as isize;
    tauri::async_runtime::spawn_blocking(move||{
        let dir=directory(&app)?;if !status(&dir,&file)?.complete{return Err("File has not downloaded yet".into())}
        #[cfg(windows)] {
            use windows_sys::Win32::UI::Controls::Dialogs::{GetSaveFileNameW,OPENFILENAMEW,OFN_EXPLORER,OFN_OVERWRITEPROMPT,OFN_NOCHANGEDIR,CommDlgExtendedError};
            let mut buffer=vec![0u16;32768];let name:Vec<u16>=file.name.chars().filter(|c| !c.is_control() && !"\\/:*?\"<>|".contains(*c)).take(120).collect::<String>().encode_utf16().collect();buffer[..name.len()].copy_from_slice(&name);
            let filter:Vec<u16>="All files\0*.*\0\0".encode_utf16().collect();let mut dialog:OPENFILENAMEW=unsafe{std::mem::zeroed()};dialog.lStructSize=std::mem::size_of::<OPENFILENAMEW>() as u32;dialog.hwndOwner=owner as _;dialog.lpstrFile=buffer.as_mut_ptr();dialog.nMaxFile=buffer.len() as u32;dialog.lpstrFilter=filter.as_ptr();dialog.Flags=OFN_EXPLORER|OFN_OVERWRITEPROMPT|OFN_NOCHANGEDIR;
            if unsafe{GetSaveFileNameW(&mut dialog)}==0 {let code=unsafe{CommDlgExtendedError()};return if code==0{Ok(false)}else{Err(format!("Save dialog failed: {code}"))}}
            let end=buffer.iter().position(|c|*c==0).ok_or("Invalid destination")?;let target=PathBuf::from(String::from_utf16_lossy(&buffer[..end]));
            fs::copy(blob(&dir,&file.sha256,false)?,target).map_err(|e|e.to_string())?;Ok(true)
        }
        #[cfg(not(windows))] {Err("Save As is supported on Windows".into())}
    }).await.map_err(|e|e.to_string())?
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn chunk_resume_verify_and_read(){
        let dir=std::env::temp_dir().join(format!("sidenote-files-{}",Uuid::new_v4()));fs::create_dir_all(&dir).unwrap();
        let data:Vec<u8>=(0..CHUNK_SIZE*2+71).map(|i|(i%251) as u8).collect();let file=Attachment{id:Uuid::new_v4().to_string(),name:"test.any".into(),mime_type:"application/octet-stream".into(),size:data.len() as u64,sha256:hex::encode(Sha256::digest(&data))};
        let first=receive_chunk(&dir,&file,0,&data[..CHUNK_SIZE]).unwrap();assert!(!first.complete);assert_eq!(first.bytes,CHUNK_SIZE as u64);
        assert!(receive_chunk(&dir,&file,0,&data[..1]).is_err());
        let resumed=status(&dir,&file).unwrap().bytes;receive_chunk(&dir,&file,resumed,&data[CHUNK_SIZE..CHUNK_SIZE*2]).unwrap();
        assert!(receive_chunk(&dir,&file,(CHUNK_SIZE*2) as u64,&data[CHUNK_SIZE*2..]).unwrap().complete);
        assert_eq!(read_chunk(&dir,&file,CHUNK_SIZE as u64,CHUNK_SIZE).unwrap(),data[CHUNK_SIZE..CHUNK_SIZE*2]);fs::remove_dir_all(dir).unwrap();
    }
    #[test] fn reject_corruption_and_paths(){
        assert!(blob(Path::new("."),"../../outside",false).is_err());let dir=std::env::temp_dir().join(format!("sidenote-files-{}",Uuid::new_v4()));fs::create_dir_all(&dir).unwrap();
        let file=Attachment{id:"x".into(),name:"x".into(),mime_type:"a".into(),size:3,sha256:hex::encode(Sha256::digest(b"abc"))};assert!(receive_chunk(&dir,&file,0,b"bad").is_err());assert_eq!(status(&dir,&file).unwrap().bytes,0);fs::remove_dir_all(dir).unwrap();
    }
    #[test] fn empty_file_is_valid(){let dir=std::env::temp_dir().join(format!("sidenote-files-{}",Uuid::new_v4()));fs::create_dir_all(&dir).unwrap();let file=Attachment{id:"empty".into(),name:"empty".into(),mime_type:"application/octet-stream".into(),size:0,sha256:hex::encode(Sha256::digest(b""))};assert!(receive_chunk(&dir,&file,0,&[]).unwrap().complete);fs::remove_dir_all(dir).unwrap();}
}
