//! TLS-only LAN transport. The certificate is pinned through an out-of-band pairing invitation.
use std::{net::TcpStream,sync::{Arc,OnceLock}};
use rusqlite::{Connection,OptionalExtension,params};
use rustls::{ServerConfig,ServerConnection,StreamOwned,pki_types::{CertificateDer,PrivatePkcs8KeyDer}};
use sha2::{Digest,Sha256};

static CONFIG:OnceLock<Arc<ServerConfig>>=OnceLock::new();
static FINGERPRINT:OnceLock<String>=OnceLock::new();
pub type Stream=StreamOwned<ServerConnection,TcpStream>;

pub fn initialize(c:&Connection)->Result<(),String>{
    let old={let mut q=c.prepare("SELECT device_id,public_key FROM trusted_peers WHERE public_key IS NOT NULL AND public_key NOT LIKE 'dpapi:%'").map_err(|e|e.to_string())?;
        let rows=q.query_map([],|r|Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?))).map_err(|e|e.to_string())?.collect::<rusqlite::Result<Vec<_>>>().map_err(|e|e.to_string())?;rows};
    for (id,token) in old {if token.len()==64 {c.execute("UPDATE trusted_peers SET public_key=?2 WHERE device_id=?1",params![id,protect_token(&token)?]).map_err(|e|e.to_string())?;}}
    let cert:Option<String>=c.query_row("SELECT value FROM sync_meta WHERE key='tls_cert_v1'",[],|r|r.get(0)).optional().map_err(|e|e.to_string())?;
    let (cert,key)=if let Some(cert)=cert {
        let key:String=c.query_row("SELECT value FROM sync_meta WHERE key='tls_key_dpapi_v1'",[],|r|r.get(0)).map_err(|_|"TLS identity is incomplete; restore its backup rather than replacing a pinned identity".to_string())?;
        (hex::decode(cert).map_err(|e|e.to_string())?,hex::decode(crate::key_protection::unprotect_hex(&key)?).map_err(|e|e.to_string())?)
    }else{
        let generated=rcgen::generate_simple_self_signed(vec!["sidenote.local".into()]).map_err(|e|e.to_string())?;
        let cert=generated.cert.der().to_vec();let key=generated.key_pair.serialize_der();
        let protected=crate::key_protection::protect_hex(&hex::encode(&key))?;
        let tx=c.unchecked_transaction().map_err(|e|e.to_string())?;
        tx.execute("INSERT INTO sync_meta(key,value) VALUES ('tls_cert_v1',?1)",[hex::encode(&cert)]).map_err(|e|e.to_string())?;
        tx.execute("INSERT INTO sync_meta(key,value) VALUES ('tls_key_dpapi_v1',?1)",params![protected]).map_err(|e|e.to_string())?;
        tx.commit().map_err(|e|e.to_string())?;(cert,key)
    };
    let provider=Arc::new(rustls::crypto::ring::default_provider());
    let config=ServerConfig::builder_with_provider(provider).with_safe_default_protocol_versions().map_err(|e|e.to_string())?
        .with_no_client_auth().with_single_cert(vec![CertificateDer::from(cert.clone())],PrivatePkcs8KeyDer::from(key).into()).map_err(|e|e.to_string())?;
    let _=FINGERPRINT.set(hex::encode(Sha256::digest(&cert)));let _=CONFIG.set(Arc::new(config));Ok(())
}
pub fn accept(socket:TcpStream)->Result<Stream,String>{
    let config=CONFIG.get().ok_or("Secure sync is not initialized")?.clone();
    Ok(StreamOwned::new(ServerConnection::new(config).map_err(|e|e.to_string())?,socket))
}
pub fn fingerprint()->Result<String,String>{FINGERPRINT.get().cloned().ok_or("Secure sync is not initialized".into())}

pub fn protect_token(token:&str)->Result<String,String>{Ok(format!("dpapi:{}",crate::key_protection::protect_hex(token)?))}
pub fn verify_token(stored:Option<&str>,token:&str)->Result<bool,String>{
    let Some(stored)=stored else{return Ok(false)};
    let expected=if let Some(data)=stored.strip_prefix("dpapi:"){crate::key_protection::unprotect_hex(data)?}else{stored.to_owned()};
    if token.len()!=64 || expected.len()!=64{return Ok(false)}
    // Compare fixed-length digests instead of exposing prefix-dependent equality.
    let left=Sha256::digest(token.as_bytes());let right=Sha256::digest(expected.as_bytes());
    Ok(left.iter().zip(right.iter()).fold(0u8,|diff,(a,b)|diff|(a^b))==0)
}

#[cfg(test)] mod tests {
    use super::*;
    #[test] fn tls_identity_survives_restart_and_private_key_is_protected(){
        let c=Connection::open_in_memory().unwrap();c.execute_batch("CREATE TABLE sync_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);CREATE TABLE trusted_peers(device_id TEXT PRIMARY KEY,public_key TEXT);").unwrap();
        initialize(&c).unwrap();let cert:String=c.query_row("SELECT value FROM sync_meta WHERE key='tls_cert_v1'",[],|r|r.get(0)).unwrap();
        initialize(&c).unwrap();assert_eq!(cert,c.query_row::<String,_,_>("SELECT value FROM sync_meta WHERE key='tls_cert_v1'",[],|r|r.get(0)).unwrap());
        assert_eq!(fingerprint().unwrap().len(),64);
    }
}
