//! Windows DPAPI user-scope protection for the device signing seed.
//! Legacy plaintext identity_seed is migrated only after successful DPAPI encryption.
use rusqlite::{Connection,OptionalExtension,params};
use crate::device_identity::DeviceIdentity;

const KEY: &str = "identity_seed_dpapi_v1";

pub fn migrate_or_create(c:&Connection)->Result<(),String>{
    let protected:Option<String>=c.query_row("SELECT value FROM sync_meta WHERE key=?1",[KEY],|r|r.get(0)).optional().map_err(|e|e.to_string())?;
    if let Some(value)=protected {
        let seed=unprotect_hex(&value)?;
        DeviceIdentity::from_seed_hex(&seed)?;
        // Clean up leftover plaintext if a previous migration was interrupted.
        c.execute("DELETE FROM sync_meta WHERE key='identity_seed'",[]).map_err(|e|e.to_string())?;
        return Ok(());
    }
    let legacy:Option<String>=c.query_row("SELECT value FROM sync_meta WHERE key='identity_seed'",[],|r|r.get(0)).optional().map_err(|e|e.to_string())?;
    let seed=legacy.unwrap_or_else(DeviceIdentity::generate_seed_hex);
    DeviceIdentity::from_seed_hex(&seed)?;
    let encrypted=protect_hex(&seed)?;
    // Do not delete old seed until protected value has been committed successfully.
    c.execute("INSERT INTO sync_meta(key,value) VALUES (?1,?2)",params![KEY,encrypted]).map_err(|e|e.to_string())?;
    c.execute("DELETE FROM sync_meta WHERE key='identity_seed'",[]).map_err(|e|e.to_string())?;
    Ok(())
}

pub fn load_identity(c:&Connection)->Result<DeviceIdentity,String>{
    let value:String=c.query_row("SELECT value FROM sync_meta WHERE key=?1",[KEY],|r|r.get(0)).map_err(|_|"Protected device identity missing; restart SideNote to migrate".to_string())?;
    DeviceIdentity::from_seed_hex(&unprotect_hex(&value)?)
}

#[cfg(windows)]
pub(crate) fn protect_hex(seed:&str)->Result<String,String>{
    Ok(hex::encode(dpapi(hex::decode(seed).map_err(|_|"Invalid seed".to_string())?.as_slice(),true)?))
}
#[cfg(windows)]
pub(crate) fn unprotect_hex(data:&str)->Result<String,String>{
    Ok(hex::encode(dpapi(&hex::decode(data).map_err(|_|"Invalid protected identity".to_string())?,false)?))
}

#[cfg(not(windows))]
pub(crate) fn protect_hex(_seed:&str)->Result<String,String>{Err("Windows DPAPI is required for protected identity storage".into())}
#[cfg(not(windows))]
pub(crate) fn unprotect_hex(_data:&str)->Result<String,String>{Err("Windows DPAPI is required for protected identity storage".into())}

#[cfg(windows)]
fn dpapi(data:&[u8],encrypt:bool)->Result<Vec<u8>,String>{
    use windows_sys::Win32::Security::Cryptography::{CRYPT_INTEGER_BLOB,CryptProtectData,CryptUnprotectData,CRYPTPROTECT_UI_FORBIDDEN};
    use windows_sys::Win32::Foundation::LocalFree;
    use std::{ptr, slice};
    if data.is_empty(){return Err("Empty identity data".into())}
    let input=CRYPT_INTEGER_BLOB{cbData:data.len() as u32,pbData:data.as_ptr() as *mut u8};
    let mut output=CRYPT_INTEGER_BLOB{cbData:0,pbData:ptr::null_mut()};
    let success=unsafe{
        if encrypt {CryptProtectData(&input,ptr::null(),ptr::null(),ptr::null(),ptr::null(),CRYPTPROTECT_UI_FORBIDDEN,&mut output)}
        else {CryptUnprotectData(&input,ptr::null_mut(),ptr::null_mut(),ptr::null(),ptr::null(),CRYPTPROTECT_UI_FORBIDDEN,&mut output)}
    };
    if success==0{return Err(format!("Windows DPAPI operation failed: {}",std::io::Error::last_os_error()))}
    let result=unsafe{slice::from_raw_parts(output.pbData,output.cbData as usize).to_vec()};
    unsafe{LocalFree(output.pbData as *mut core::ffi::c_void)};
    Ok(result)
}

#[cfg(test)]
mod tests{
    use super::*;
    #[cfg(windows)]
    #[test]
    fn create_reload_and_no_plaintext(){
        let c=Connection::open_in_memory().unwrap();
        c.execute("CREATE TABLE sync_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)",[]).unwrap();
        migrate_or_create(&c).unwrap();
        let a=load_identity(&c).unwrap().public_key_hex();
        migrate_or_create(&c).unwrap();
        assert_eq!(a,load_identity(&c).unwrap().public_key_hex());
        assert_eq!(c.query_row("SELECT count(*) FROM sync_meta WHERE key='identity_seed'",[],|r|r.get::<_,i64>(0)).unwrap(),0);
    }
    #[cfg(windows)]
    #[test]
    fn legacy_identity_migrates_without_key_change(){
        let c=Connection::open_in_memory().unwrap();
        c.execute("CREATE TABLE sync_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)",[]).unwrap();
        let legacy=DeviceIdentity::generate_seed_hex();
        let expected=DeviceIdentity::from_seed_hex(&legacy).unwrap().public_key_hex();
        c.execute("INSERT INTO sync_meta VALUES ('identity_seed',?1)",[legacy]).unwrap();
        migrate_or_create(&c).unwrap();
        assert_eq!(expected,load_identity(&c).unwrap().public_key_hex());
    }
}
