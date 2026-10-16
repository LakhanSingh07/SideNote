use base64::{Engine,engine::general_purpose::{STANDARD,URL_SAFE_NO_PAD}};
use rusqlite::{OptionalExtension,Connection};
use serde::{Serialize,Deserialize};
use tauri::State;
use crate::Database;
#[derive(Serialize,Clone)]#[serde(rename_all="camelCase")]pub struct ProStatus {pub preview:bool,pub configured:bool,pub active:bool,pub plan:String,pub access_until:i64,pub message:String}
#[derive(Deserialize)]#[serde(rename_all="camelCase")]struct Claims {iss:String,aud:String,device_id:String,plan:String,active:bool,iat:i64,exp:i64,access_until:i64}
fn now()->i64{std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs() as i64}
pub fn preview()->bool{option_env!("SIDENOTE_COMMERCIAL_BUILD")!=Some("1")}
fn configured()->bool{option_env!("SIDENOTE_API_BASE").filter(|s|s.starts_with("https://")).is_some()&&option_env!("SIDENOTE_LICENSE_PUBLIC_KEY").is_some()}
fn verify_ticket(token:&str,key:&str,device:&str,time:i64,last_clock:i64)->Result<Claims,String>{
    if token.len()>16000||time+300<last_clock{return Err("Check your device date and refresh Pro access".into())}
    let parts:Vec<_>=token.split('.').collect();if parts.len()!=3{return Err("Invalid entitlement".into())}
    let header:serde_json::Value=serde_json::from_slice(&URL_SAFE_NO_PAD.decode(parts[0]).map_err(|_|"Invalid entitlement")?).map_err(|_|"Invalid entitlement")?;
    if header["alg"]!="ES256"||header["typ"]!="JWT"{return Err("Invalid entitlement algorithm".into())}
    let public=STANDARD.decode(key).map_err(|_|"License verification is not configured")?;let signature=URL_SAFE_NO_PAD.decode(parts[2]).map_err(|_|"Invalid entitlement signature")?;
    ring::signature::UnparsedPublicKey::new(&ring::signature::ECDSA_P256_SHA256_FIXED,public).verify(format!("{}.{}",parts[0],parts[1]).as_bytes(),&signature).map_err(|_|"Entitlement signature is invalid")?;
    let claims:Claims=serde_json::from_slice(&URL_SAFE_NO_PAD.decode(parts[1]).map_err(|_|"Invalid entitlement")?).map_err(|_|"Invalid entitlement claims")?;
    if claims.iss!="sidenote-pro"||claims.aud!="sidenote-apps"||claims.device_id!=device||claims.exp<=time||claims.iat>time+300||claims.exp-claims.iat>72*3600||claims.active&&claims.access_until<claims.exp{return Err("Pro access expired or belongs to another device. Refresh or restore your purchase.".into())}
    Ok(claims)
}
pub fn status(c:&Connection)->ProStatus {
    let mut result=ProStatus{preview:preview(),configured:configured(),active:preview(),plan:if preview(){"preview".into()}else{"free".into()},access_until:0,message:if preview(){"Preview build - Pro features are enabled for testing. No subscription is charged.".into()}else{"Free basic. Your local notes and export remain available.".into()}};
    if !configured(){return result}
    let id:String=c.query_row("SELECT value FROM sync_meta WHERE key='device_id'",[],|r|r.get(0)).unwrap_or_default();let raw:Option<String>=c.query_row("SELECT value FROM sync_meta WHERE key='license_ticket_v1'",[],|r|r.get(0)).optional().ok().flatten();
    let last:i64=c.query_row("SELECT value FROM sync_meta WHERE key='license_last_clock'",[],|r|r.get::<_,String>(0)).ok().and_then(|s|s.parse().ok()).unwrap_or(0);
    if let Some(raw)=raw {match verify_ticket(&raw,option_env!("SIDENOTE_LICENSE_PUBLIC_KEY").unwrap_or(""),&id,now(),last){
        Ok(claims)=>{result.active=claims.active||preview();result.plan=claims.plan.clone();result.access_until=claims.access_until;result.message=if claims.active{if claims.plan=="trial"{"7-day trial active. Refresh online at least every 72 hours.".into()}else{"Windows Pro verified. Refresh online at least every 72 hours.".into()}}else{"Subscription is inactive. Local notes and export remain available.".into()};},Err(error)=>{result.message=error;}
    }}
    let _=c.execute("INSERT INTO sync_meta VALUES ('license_last_clock',?1) ON CONFLICT(key) DO UPDATE SET value=excluded.value",[now().max(last).to_string()]);result
}
pub fn require(c:&Connection)->Result<(),String>{if status(c).active{Ok(())}else{Err("Pro is required for cross-device sync. Open Pro to restore or activate your subscription.".into())}}
#[tauri::command]pub fn pro_status(db:State<'_,Database>)->Result<ProStatus,String>{{let c=db.0.lock().map_err(|e|e.to_string())?;Ok(status(&c))}}
#[tauri::command]pub async fn pro_activate(db:State<'_,Database>,activation_code:Option<String>)->Result<ProStatus,String>{
    let db=db.0.clone();tauri::async_runtime::spawn_blocking(move||{
        if !configured(){return Err("Production subscriptions are not configured in this preview build".into())}
        let (id,code)={let c=db.lock().map_err(|e|e.to_string())?;let id:String=c.query_row("SELECT value FROM sync_meta WHERE key='device_id'",[],|r|r.get(0)).map_err(|e|e.to_string())?;
            let code=if let Some(code)=activation_code {code.trim().to_owned()}else{let protected:String=c.query_row("SELECT value FROM sync_meta WHERE key='license_code_dpapi_v1'",[],|r|r.get(0)).map_err(|_|"Enter your activation code or restore your purchase")?;crate::key_protection::unprotect_hex(&protected)?};(id,code)};
        if code.len()!=64||!code.bytes().all(|b|b.is_ascii_hexdigit()){return Err("Enter a valid activation code".into())}
        let client=reqwest::blocking::Client::builder().timeout(std::time::Duration::from_secs(20)).redirect(reqwest::redirect::Policy::none()).build().map_err(|e|e.to_string())?;
        let mut response=client.post(format!("{}/v1/activate",option_env!("SIDENOTE_API_BASE").unwrap().trim_end_matches('/'))).json(&serde_json::json!({"activationCode":code,"deviceId":id})).send().map_err(|_|"Could not reach subscription verification. Existing offline access is unchanged.")?;
        let success=response.status().is_success();let mut raw=String::new();std::io::Read::read_to_string(&mut std::io::Read::take(&mut response,65537),&mut raw).map_err(|e|e.to_string())?;
        if raw.len()>65536{return Err("Invalid verification response".into())}
        let value:serde_json::Value=serde_json::from_str(&raw).map_err(|_|"Invalid verification response")?;
        if !success{return Err(value["error"].as_str().unwrap_or("Verification unavailable").to_owned())}
        let token=value["ticket"].as_str().ok_or("Missing signed entitlement")?;verify_ticket(token,option_env!("SIDENOTE_LICENSE_PUBLIC_KEY").unwrap(),&id,now(),0)?;
        let c=db.lock().map_err(|e|e.to_string())?;let tx=c.unchecked_transaction().map_err(|e|e.to_string())?;
        tx.execute("INSERT INTO sync_meta VALUES ('license_ticket_v1',?1) ON CONFLICT(key) DO UPDATE SET value=excluded.value",[token]).map_err(|e|e.to_string())?;
        tx.execute("INSERT INTO sync_meta VALUES ('license_code_dpapi_v1',?1) ON CONFLICT(key) DO UPDATE SET value=excluded.value",[crate::key_protection::protect_hex(&code)?]).map_err(|e|e.to_string())?;
        tx.commit().map_err(|e|e.to_string())?;Ok(status(&c))
    }).await.map_err(|e|e.to_string())?
}

// Never transmit the raw Windows MachineGuid. This app-specific hash survives a normal reinstall.
#[cfg(windows)]fn trial_device_key()->Result<String,String>{
    use windows_sys::Win32::System::Registry::{RegGetValueW,HKEY_LOCAL_MACHINE,RRF_RT_REG_SZ,RRF_SUBKEY_WOW6464KEY};
    use sha2::{Digest,Sha256};
    let path:Vec<u16>="SOFTWARE\\Microsoft\\Cryptography".encode_utf16().chain(Some(0)).collect();let name:Vec<u16>="MachineGuid".encode_utf16().chain(Some(0)).collect();
    let mut data=[0u16;128];let mut bytes=std::mem::size_of_val(&data) as u32;
    let code=unsafe{RegGetValueW(HKEY_LOCAL_MACHINE,path.as_ptr(),name.as_ptr(),RRF_RT_REG_SZ|RRF_SUBKEY_WOW6464KEY,std::ptr::null_mut(),data.as_mut_ptr().cast(),&mut bytes)};
    if code!=0{return Err("Could not identify this Windows installation for the trial".into())}
    let end=data.iter().position(|n|*n==0).unwrap_or(data.len());let guid=String::from_utf16(&data[..end]).map_err(|_|"Invalid Windows installation identifier")?;
    if guid.len()<16{return Err("Windows installation identifier is unavailable".into())}
    Ok(hex::encode(Sha256::digest(format!("sidenote:windows-trial:v1:{}",guid.trim().to_lowercase()).as_bytes())))
}
#[cfg(not(windows))]fn trial_device_key()->Result<String,String>{Err("The Windows trial requires Windows".into())}
#[tauri::command]pub async fn pro_start_trial(db:State<'_,Database>)->Result<ProStatus,String>{
    let db=db.0.clone();tauri::async_runtime::spawn_blocking(move||{
        if !configured(){return Err("The trial verification service is not configured in this internal preview".into())}
        let id:String={let c=db.lock().map_err(|e|e.to_string())?;if status(&c).active&&status(&c).plan!="trial"&& !preview(){return Ok(status(&c))}c.query_row("SELECT value FROM sync_meta WHERE key='device_id'",[],|r|r.get(0)).map_err(|e|e.to_string())?};
        let client=reqwest::blocking::Client::builder().timeout(std::time::Duration::from_secs(20)).redirect(reqwest::redirect::Policy::none()).build().map_err(|e|e.to_string())?;
        let mut response=client.post(format!("{}/v1/trial",option_env!("SIDENOTE_API_BASE").unwrap().trim_end_matches('/'))).json(&serde_json::json!({"deviceId":id,"trialDeviceKey":trial_device_key()?})).send().map_err(|_|"Could not verify the trial online. Your notes remain accessible.")?;
        let success=response.status().is_success();let mut raw=String::new();std::io::Read::read_to_string(&mut std::io::Read::take(&mut response,65537),&mut raw).map_err(|e|e.to_string())?;
        if raw.len()>65536{return Err("Invalid trial response".into())}let value:serde_json::Value=serde_json::from_str(&raw).map_err(|_|"Invalid trial response")?;
        if !success{return Err(value["error"].as_str().unwrap_or("Trial verification unavailable").into())}
        let token=value["ticket"].as_str().ok_or("Missing signed trial")?;let claims=verify_ticket(token,option_env!("SIDENOTE_LICENSE_PUBLIC_KEY").unwrap(),&id,now(),0)?;
        if claims.plan!="trial"{return Err("Invalid trial entitlement".into())}
        let c=db.lock().map_err(|e|e.to_string())?;c.execute("INSERT INTO sync_meta VALUES ('license_ticket_v1',?1) ON CONFLICT(key) DO UPDATE SET value=excluded.value",[token]).map_err(|e|e.to_string())?;Ok(status(&c))
    }).await.map_err(|e|e.to_string())?
}
#[tauri::command]pub fn pro_website()->Result<String,String>{
    let value=option_env!("SIDENOTE_WEBSITE").unwrap_or("");if !value.starts_with("https://"){return Err("The purchase website is not configured in this preview".into())}Ok(value.into())
}

#[cfg(test)]mod tests {
    use super::*;
    fn fixture()->serde_json::Value{serde_json::from_str(include_str!("../../../../test-fixtures/entitlement.json")).unwrap()}
    #[test]fn accepts_the_backend_es256_format(){let f=fixture();let claims=verify_ticket(f["token"].as_str().unwrap(),f["publicKey"].as_str().unwrap(),f["deviceId"].as_str().unwrap(),f["now"].as_i64().unwrap(),0).unwrap();assert!(claims.active)}
    #[test]fn rejects_expiry_other_devices_and_clock_rollback(){let f=fixture();let token=f["token"].as_str().unwrap();let key=f["publicKey"].as_str().unwrap();assert!(verify_ticket(token,key,"other",2000000001,0).is_err());assert!(verify_ticket(token,key,"synthetic-device",2000003601,0).is_err());assert!(verify_ticket(token,key,"synthetic-device",2000000001,2000000500).is_err());}
    #[test]fn trial_identifier_is_stable_and_pseudonymous(){let first=trial_device_key().unwrap();assert_eq!(first.len(),64);assert_eq!(first,trial_device_key().unwrap());}
    #[test]fn rejects_changed_ticket_bytes(){let f=fixture();let token=f["token"].as_str().unwrap().replace(".",".e");assert!(verify_ticket(&token,f["publicKey"].as_str().unwrap(),"synthetic-device",2000000001,0).is_err());}
}
