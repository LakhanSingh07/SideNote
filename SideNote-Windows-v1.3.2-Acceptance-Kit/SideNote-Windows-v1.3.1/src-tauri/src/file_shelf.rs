use std::{collections::{HashMap,HashSet},path::PathBuf,sync::Mutex,time::Instant,io::{Read,Write}};
use tauri::{Manager,State};
use crate::{attachments::{self,Attachment},Database};
#[derive(Default)]pub struct DroppedFiles(pub Mutex<HashMap<String,(Instant,HashSet<PathBuf>)>>);
pub fn authorize(app:&tauri::AppHandle,label:&str,paths:&[PathBuf]){
    if let Ok(mut values)=app.state::<DroppedFiles>().0.lock(){values.insert(label.into(),(Instant::now(),paths.iter().cloned().take(1000).collect()));}
}
fn file(db:&Database,note_id:&str,id:&str)->Result<Attachment,String>{
    let c=db.0.lock().map_err(|e|e.to_string())?;let raw:String=c.query_row("SELECT attachments FROM notes WHERE id=?1",[note_id],|r|r.get(0)).map_err(|_|"Note not found")?;
    serde_json::from_str::<Vec<Attachment>>(&raw).map_err(|e|e.to_string())?.into_iter().find(|f|f.id==id).ok_or("Attachment not found".into())
}
#[tauri::command]pub async fn attachment_thumbnail(app:tauri::AppHandle,db:State<'_,Database>,note_id:String,attachment_id:String)->Result<String,String>{
    let file=file(&db,&note_id,&attachment_id)?;
    if !file.mime_type.starts_with("image/")||file.size>20*1024*1024{return Err("Preview unavailable".into())}
    let dir=attachments::directory(&app)?;
    tauri::async_runtime::spawn_blocking(move||{
        use base64::Engine;
        let mut reader=image::ImageReader::open(attachments::blob(&dir,&file.sha256,false)?).map_err(|e|e.to_string())?.with_guessed_format().map_err(|e|e.to_string())?;
        let mut limits=image::Limits::default();limits.max_image_width=Some(16000);limits.max_image_height=Some(16000);limits.max_alloc=Some(64*1024*1024);reader.limits(limits);
        let image=reader.decode().map_err(|e|e.to_string())?.thumbnail(320,180);let mut output=std::io::Cursor::new(Vec::new());image.write_to(&mut output,image::ImageFormat::Png).map_err(|e|e.to_string())?;
        Ok(format!("data:image/png;base64,{}",base64::engine::general_purpose::STANDARD.encode(output.into_inner())))
    }).await.map_err(|e|e.to_string())?
}
#[tauri::command]pub async fn attachment_import_paths(app:tauri::AppHandle,window:tauri::WebviewWindow,allowed:State<'_,DroppedFiles>,paths:Vec<PathBuf>)->Result<Vec<Attachment>,String>{
    if paths.is_empty()||paths.len()>1000{return Err("Choose between 1 and 1000 files".into())}
    {
        let mut values=allowed.0.lock().map_err(|e|e.to_string())?;let (time,files)=values.get_mut(window.label()).ok_or("Drop files onto this note first")?;
        if time.elapsed().as_secs()>30||paths.iter().any(|p|!files.contains(p)){return Err("File drop permission expired or does not match this window".into())}
        for p in &paths {files.remove(p);}
    }
    let dir=attachments::directory(&app)?;
    tauri::async_runtime::spawn_blocking(move||{
        use sha2::{Digest,Sha256};let mut result=Vec::new();
        for path in paths {
            let metadata=path.metadata().map_err(|e|e.to_string())?;if !metadata.is_file(){return Err("Drop files, rather than folders".into())}
            let id=uuid::Uuid::new_v4().to_string();let stage=dir.join(format!("{id}.import"));
            let imported:Result<Attachment,String>=(||{
                let mut source=std::fs::File::open(&path).map_err(|e|e.to_string())?;let mut target=std::fs::File::create(&stage).map_err(|e|e.to_string())?;let mut hash=Sha256::new();let mut buffer=vec![0u8;attachments::CHUNK_SIZE];let mut size=0u64;
                loop{let n=source.read(&mut buffer).map_err(|e|e.to_string())?;if n==0{break}target.write_all(&buffer[..n]).map_err(|e|e.to_string())?;hash.update(&buffer[..n]);size+=n as u64;}
                target.sync_all().map_err(|e|e.to_string())?;drop(target);let sha256=hex::encode(hash.finalize());
                let name=path.file_name().ok_or("Missing file name")?.to_string_lossy().to_string();let mime_type=match path.extension().and_then(|x|x.to_str()).unwrap_or("").to_lowercase().as_str(){"png"=>"image/png","jpg"|"jpeg"=>"image/jpeg","webp"=>"image/webp","gif"=>"image/gif","pdf"=>"application/pdf","zip"=>"application/zip","txt"|"md"=>"text/plain","mp4"=>"video/mp4","mp3"=>"audio/mpeg",_=>"application/octet-stream"}.to_string();
                let file=Attachment{id,name,mime_type,size,sha256};file.validate()?;let target=attachments::blob(&dir,&file.sha256,false)?;
                if target.exists(){std::fs::remove_file(&stage).map_err(|e|e.to_string())?;}else{std::fs::rename(&stage,target).map_err(|e|e.to_string())?;}Ok(file)
            })();if imported.is_err(){let _=std::fs::remove_file(&stage);}result.push(imported?);
        }Ok(result)
    }).await.map_err(|e|e.to_string())?
}
#[tauri::command]pub async fn attachment_save_bundle(app:tauri::AppHandle,db:State<'_,Database>,note_id:String,attachment_ids:Vec<String>)->Result<String,String>{
    if attachment_ids.is_empty()||attachment_ids.len()>1000{return Err("Select files first".into())}
    let files=attachment_ids.iter().map(|id|file(&db,&note_id,id)).collect::<Result<Vec<_>,_>>()?;let dir=attachments::directory(&app)?;
    for file in &files{if !attachments::status(&dir,file)?.complete{return Err("Wait for selected files to finish transferring".into())}}
    tauri::async_runtime::spawn_blocking(move||{
        let Some(path)=crate::file_dialog::choose("SideNote-files.zip",true)? else{return Ok("Cancelled".into())};
        let mut zip=zip::ZipWriter::new(std::fs::File::create(path).map_err(|e|e.to_string())?);
        let options=zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored).large_file(true);
        for (index,file) in files.iter().enumerate(){
            let name:String=file.name.chars().take(200).map(|c|if matches!(c,'/'|'\\'|':'|'\0'){ '_'}else{c}).collect();
            zip.start_file(format!("{}-{name}",index+1),options).map_err(|e|e.to_string())?;
            std::io::copy(&mut std::fs::File::open(attachments::blob(&dir,&file.sha256,false)?).map_err(|e|e.to_string())?,&mut zip).map_err(|e|e.to_string())?;
        }zip.finish().map_err(|e|e.to_string())?;Ok(format!("Saved {} files as ZIP",files.len()))
    }).await.map_err(|e|e.to_string())?
}
