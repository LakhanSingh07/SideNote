use std::sync::Mutex;
use tauri::{State,Emitter,Manager};
use tauri_plugin_updater::{Update,UpdaterExt};
#[derive(Default)]pub struct PendingUpdate(Mutex<Option<Update>>);
fn configured()->bool{option_env!("SIDENOTE_UPDATER_PUBLIC_KEY").is_some()&&option_env!("SIDENOTE_UPDATER_ENDPOINT").map(|v|v.starts_with("https://")).unwrap_or(false)}
pub fn setup(app:&mut tauri::App)->Result<(),String>{
    app.manage(PendingUpdate::default());
    if configured(){app.handle().plugin(tauri_plugin_updater::Builder::new().pubkey(option_env!("SIDENOTE_UPDATER_PUBLIC_KEY").unwrap()).build()).map_err(|e|e.to_string())?;}Ok(())
}
#[tauri::command]pub async fn check_for_updates(app:tauri::AppHandle,pending:State<'_,PendingUpdate>)->Result<serde_json::Value,String>{
    if !configured(){return Ok(serde_json::json!({"configured":false,"available":false,"message":"Signed updates will be enabled in the production release."}))}
    let endpoint=option_env!("SIDENOTE_UPDATER_ENDPOINT").unwrap().parse().map_err(|_|"Invalid update endpoint")?;
    let update=app.updater_builder().endpoints(vec![endpoint]).map_err(|e|e.to_string())?.build().map_err(|e|e.to_string())?.check().await.map_err(|e|e.to_string())?;
    let result=if let Some(ref item)=update{serde_json::json!({"configured":true,"available":true,"version":item.version,"notes":item.body})}else{serde_json::json!({"configured":true,"available":false,"message":"You are up to date."})};
    *pending.0.lock().map_err(|e|e.to_string())?=update;Ok(result)
}
#[tauri::command]pub async fn install_update(app:tauri::AppHandle,pending:State<'_,PendingUpdate>)->Result<(),String>{
    let update=pending.0.lock().map_err(|e|e.to_string())?.take().ok_or("Check for an update first")?;
    let mut bytes=0u64;let handle=app.clone();
    update.download_and_install(move|chunk,total|{bytes+=chunk as u64;let _=handle.emit("sidenote_update_progress",serde_json::json!({"bytes":bytes,"total":total}));},||{}).await.map_err(|e|e.to_string())?;
    app.restart()
}
