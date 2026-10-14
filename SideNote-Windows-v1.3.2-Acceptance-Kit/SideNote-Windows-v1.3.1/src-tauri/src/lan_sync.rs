//! Experimental same-LAN encrypted bundle exchange. Manual 256-bit shared-key setup.
//! Transport authentication relies exclusively on keeping the pairing key secret.
use std::{collections::HashSet,io::{Read,Write},net::{TcpListener,TcpStream,SocketAddr,IpAddr},sync::{Arc,Mutex,atomic::{AtomicUsize,Ordering}},time::Duration,thread};
use chacha20poly1305::{XChaCha20Poly1305,KeyInit,XNonce,aead::{Aead,OsRng,rand_core::RngCore}};
use rusqlite::Connection;
use serde::{Serialize,Deserialize};
use uuid::Uuid;
use crate::{sync_bundle,device_identity};
const PORT:u16=43861;
const MAX_FRAME:usize=5_500_000;
const MAX_SESSION_PAGES:usize=1024;
const MAX_IDLE_PAGES:usize=3;
const MAX_INBOUND_SESSIONS:usize=8;

// Decrement the active-session count even when a peer disconnects or panics.
struct SessionPermit(Arc<AtomicUsize>);
impl Drop for SessionPermit { fn drop(&mut self) { self.0.fetch_sub(1,Ordering::AcqRel); } }
fn try_reserve_session(active:&Arc<AtomicUsize>)->Option<SessionPermit> {
 active.try_update(Ordering::AcqRel, Ordering::Acquire, |count| {
  if count < MAX_INBOUND_SESSIONS { Some(count+1) } else { None }
 }).ok().map(|_| SessionPermit(active.clone()))
}


fn key_from_hex(s:&str)->Result<[u8;32],String>{
 let b=hex::decode(s.trim()).map_err(|_|"Pairing key must contain 64 hexadecimal characters".to_string())?;
 if b.len()!=32{return Err("Pairing key must be exactly 32 bytes".into())}
 let mut k=[0;32];k.copy_from_slice(&b);Ok(k)
}
pub fn make_key()->String{let mut k=[0u8;32];OsRng.fill_bytes(&mut k);hex::encode(k)}
fn cipher(key:&[u8;32])->XChaCha20Poly1305{XChaCha20Poly1305::new(key.into())}
fn write_packet(s:&mut TcpStream,key:&[u8;32],plaintext:&str)->Result<(),String>{
 if plaintext.len()>MAX_FRAME-64{return Err("Sync data too large".into())}
 let mut nonce=[0u8;24];OsRng.fill_bytes(&mut nonce);
 let ciphertext=cipher(key).encrypt(XNonce::from_slice(&nonce),plaintext.as_bytes()).map_err(|_|"Could not encrypt sync data".to_string())?;
 let len=24+ciphertext.len();if len>MAX_FRAME{return Err("Encrypted data too large".into())}
 s.write_all(&(len as u32).to_be_bytes()).map_err(|e|e.to_string())?;
 s.write_all(&nonce).map_err(|e|e.to_string())?;s.write_all(&ciphertext).map_err(|e|e.to_string())?;
 Ok(())
}
fn read_packet(s:&mut TcpStream,key:&[u8;32],seen:&mut HashSet<[u8;24]>)->Result<String,String>{
 let mut len=[0u8;4];s.read_exact(&mut len).map_err(|e|e.to_string())?;
 let n=u32::from_be_bytes(len) as usize;
 if n<40||n>MAX_FRAME{return Err("Invalid encrypted packet length".into())}
 let mut buf=vec![0u8;n];s.read_exact(&mut buf).map_err(|e|e.to_string())?;
 let mut nonce=[0u8;24]; nonce.copy_from_slice(&buf[..24]);
 // Fail closed on duplicate nonces within this TCP session, even if the AEAD tag is valid.
 // Do not insert a nonce unless authentication has succeeded.
 if seen.contains(&nonce) { return Err("Repeated encrypted packet nonce in sync session".into()); }
 let plain=cipher(key).decrypt(XNonce::from_slice(&nonce),&buf[24..]).map_err(|_|"Authentication failed: wrong pairing key or altered packet".to_string())?;
 seen.insert(nonce);
 String::from_utf8(plain).map_err(|_|"Invalid sync payload".into())
}
fn configure(stream:&TcpStream){let _=stream.set_read_timeout(Some(Duration::from_secs(10)));let _=stream.set_write_timeout(Some(Duration::from_secs(10)));}
#[derive(Serialize)]
#[serde(rename_all="camelCase")]
pub struct LanReport{pub sent_bytes:usize,pub received_bytes:usize,pub applied:usize,pub skipped:usize,pub conflicts:usize}
#[derive(Serialize,Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
struct Hello { device_id:String }
#[derive(Serialize,Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
struct Proof { public_key:String, nonce:String, signature:String }
#[derive(Serialize,Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
struct Page { device_id:String, ack_cursor:i64, bundle:String }
#[derive(Serialize,Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
struct PageReply { bundle:String, received_cursor:i64 }
// Never trust a remote acknowledgement beyond what this session actually sent.
// An over-advanced cursor can permanently skip local changes.
fn validate_ack(previous:i64, acknowledged:i64, sent_through:i64)->Result<(),String>{
 if previous<0 || acknowledged<previous || acknowledged>sent_through {
   return Err("Invalid peer acknowledgement cursor".into());
 }
 Ok(())
}
fn require_trusted(c:&Connection,peer:&str)->Result<String,String>{
 let key:Option<String>=c.query_row("SELECT public_key FROM trusted_peers WHERE device_id=?1",[peer],|r|r.get(0)).map_err(|_|"Unapproved peer".to_string())?;
 let key=key.ok_or("Peer has no pinned public key: pair again".to_string())?;
 if hex::decode(&key).map(|b|b.len()!=32).unwrap_or(true){return Err("Peer key invalid".into())}
 Ok(key)
}
fn local_identity(c:&Connection)->Result<device_identity::DeviceIdentity,String>{
 crate::key_protection::load_identity(c)
}

fn fresh_nonce() -> [u8; 32] {
 let mut n = [0u8; 32];
 OsRng.fill_bytes(&mut n);
 n
}

fn authenticate(stream:&mut TcpStream,key:&[u8;32],seen:&mut HashSet<[u8;24]>,identity:&device_identity::DeviceIdentity,expected_peer_key:&str,initiator:bool)->Result<(),String>{
 let mine=identity.public_key_hex();
 let nonce=fresh_nonce();
 let mine_nonce=hex::encode(nonce);
 // Both sides send independent challenges. Signed responses bind signer, recipient and nonce.
 let peer_nonce:String;
 if initiator {
  write_packet(stream,key,&mine_nonce)?;
  peer_nonce=read_packet(stream,key,seen)?;
 } else {
  peer_nonce=read_packet(stream,key,seen)?;
  write_packet(stream,key,&mine_nonce)?;
 }
 let peer_bytes=hex::decode(&peer_nonce).map_err(|_|"Invalid challenge".to_string())?;
 let peer_array:[u8;32]=peer_bytes.try_into().map_err(|_|"Invalid challenge length".to_string())?;
 let outgoing=Proof{public_key:mine.clone(),nonce:peer_nonce,signature:identity.sign_challenge(expected_peer_key,&peer_array)?};
 let incoming:Proof;
 if initiator {
  write_packet(stream,key,&serde_json::to_string(&outgoing).map_err(|e|e.to_string())?)?;
  incoming=serde_json::from_str(&read_packet(stream,key,seen)?).map_err(|_|"Invalid authentication proof".to_string())?;
 } else {
  incoming=serde_json::from_str(&read_packet(stream,key,seen)?).map_err(|_|"Invalid authentication proof".to_string())?;
  write_packet(stream,key,&serde_json::to_string(&outgoing).map_err(|e|e.to_string())?)?;
 }
 if incoming.public_key!=expected_peer_key || incoming.nonce!=mine_nonce {return Err("Peer identity or challenge mismatch".into())}
 device_identity::verify_challenge(expected_peer_key,&mine,&nonce,&incoming.signature)
}
fn local_id(c:&Connection)->Result<String,String>{
 c.query_row("SELECT value FROM sync_meta WHERE key='device_id'",[],|r|r.get(0)).map_err(|e|e.to_string())
}
fn serve(mut s:TcpStream,db:Arc<Mutex<Connection>>,key:[u8;32])->Result<(),String>{
 configure(&s);let mut seen=HashSet::new();let peer=s.peer_addr().map_err(|e|e.to_string())?;
 if !allowed(peer.ip()){return Err("Only private LAN addresses are permitted".into())}
 let hello:Hello=serde_json::from_str(&read_packet(&mut s,&key,&mut seen)?).map_err(|_|"Invalid sync hello".to_string())?;
 if Uuid::parse_str(&hello.device_id).is_err(){return Err("Invalid peer identity".into())}
 let own={let c=db.lock().map_err(|e|e.to_string())?;local_id(&c)?};
 if own==hello.device_id{return Err("Cannot sync with self".into())}
 let (expected,identity)={let c=db.lock().map_err(|e|e.to_string())?;(require_trusted(&c,&hello.device_id)?,local_identity(&c)?)};
 write_packet(&mut s,&key,&serde_json::to_string(&Hello{device_id:own.clone()}).map_err(|e|e.to_string())?)?;
 authenticate(&mut s,&key,&mut seen,&identity,&expected,false)?;
 let mut last_ack=0_i64;
 // A reconnecting peer may already have acknowledged changes from a prior session.
 // Bound the first acknowledgement by our currently committed local change history.
 let mut last_sent={
   let c=db.lock().map_err(|e|e.to_string())?;
   c.query_row("SELECT COALESCE(MAX(seq),0) FROM sync_changes WHERE origin_device=?1",[own.as_str()],|r|r.get::<_,i64>(0)).map_err(|e|e.to_string())?
 };
 let mut idle_pages=0usize;
 for _ in 0..MAX_SESSION_PAGES {
   let req:Page=serde_json::from_str(&read_packet(&mut s,&key,&mut seen)?).map_err(|_|"Invalid sync page".to_string())?;
   if req.device_id!=hello.device_id{return Err("Peer identity changed".into())}
   validate_ack(last_ack,req.ack_cursor,last_sent)?;
   let ack_advanced=req.ack_cursor>last_ack;
   last_ack=req.ack_cursor;
   let mut c=db.lock().map_err(|e|e.to_string())?;
   let received_before=sync_bundle::peer_cursor(&c,&hello.device_id)?;
   let (origin,last,_count)=sync_bundle::bundle_meta(&req.bundle)?;
   if origin!=hello.device_id{return Err("Bundle origin mismatch".into())}
   // Transactions in import_bundle commit before the received cursor is acknowledged.
   sync_bundle::import_bundle(&mut c,&req.bundle)?;
   if last>0{sync_bundle::advance_peer_cursor(&c,&hello.device_id,last)?;}
   let received=sync_bundle::peer_cursor(&c,&hello.device_id)?;
   if !ack_advanced && last<=received_before { idle_pages+=1; } else {idle_pages=0;}
   if idle_pages>=MAX_IDLE_PAGES{return Err("Peer session made no progress".into())}
   let outgoing=sync_bundle::export_bundle(&c,req.ack_cursor)?;
   let (_,terminal,outgoing_count)=sync_bundle::bundle_meta(&outgoing)?;
   let (_,_,incoming_count)=sync_bundle::bundle_meta(&req.bundle)?;
   last_sent=terminal.max(req.ack_cursor);
   // A completed final exchange must close cleanly instead of waiting for
   // another page after the client has finished and disconnected.
   let session_complete=incoming_count==0 && outgoing_count==0;
   drop(c);
   let resp=PageReply{bundle:outgoing,received_cursor:received};
   write_packet(&mut s,&key,&serde_json::to_string(&resp).map_err(|e|e.to_string())?)?;
   if session_complete { return Ok(()); }
 }
 Err("Sync session exceeded safety page limit".into())
}
fn allowed(ip:IpAddr)->bool{match ip{IpAddr::V4(v)=>v.is_private()||v.is_loopback(),IpAddr::V6(v)=>v.is_loopback()||v.is_unique_local()}}
pub fn start_server(db:Arc<Mutex<Connection>>,key_hex:&str)->Result<String,String>{
 let key=key_from_hex(key_hex)?;
 let listener=TcpListener::bind(("0.0.0.0",PORT)).map_err(|e|format!("Cannot listen on port {PORT}: {e}"))?;
 let active=Arc::new(AtomicUsize::new(0));
 thread::spawn(move||for connection in listener.incoming(){match connection{
   Ok(s)=>{
     if let Some(permit)=try_reserve_session(&active) {
       let database=db.clone();
       thread::spawn(move||{let _permit=permit; if let Err(e)=serve(s,database,key){eprintln!("SideNote LAN session rejected: {e}");}});
     } else {
       // Drop surplus connections without a thread or allocating a frame buffer.
       drop(s);
     }
   },
   Err(e)=>{eprintln!("SideNote LAN listener error: {e}");break;}
 }});
 Ok(format!("Listening for paired Windows devices on TCP port {PORT}"))
}
pub fn sync_peer(db:Arc<Mutex<Connection>>,address:&str,key_hex:&str)->Result<LanReport,String>{
 let key=key_from_hex(key_hex)?;
 let addr:SocketAddr=format!("{}:{}",address.trim(),PORT).parse().map_err(|_|"Enter a numeric IPv4 or bracketed IPv6 LAN address".to_string())?;
 if !allowed(addr.ip()){return Err("Only private LAN addresses are permitted".into())}
 let mut s=TcpStream::connect_timeout(&addr,Duration::from_secs(8)).map_err(|e|format!("LAN connection failed: {e}"))?;
 configure(&s);
 let mut seen=HashSet::new();
 let own={let c=db.lock().map_err(|e|e.to_string())?;local_id(&c)?};
 write_packet(&mut s,&key,&serde_json::to_string(&Hello{device_id:own.clone()}).map_err(|e|e.to_string())?)?;
 let hello:Hello=serde_json::from_str(&read_packet(&mut s,&key,&mut seen)?).map_err(|_|"Invalid server identity".to_string())?;
 if Uuid::parse_str(&hello.device_id).is_err()||hello.device_id==own{return Err("Invalid server device".into())}
 let (expected,identity)={let c=db.lock().map_err(|e|e.to_string())?;(require_trusted(&c,&hello.device_id)?,local_identity(&c)?)};
 authenticate(&mut s,&key,&mut seen,&identity,&expected,true)?;
 let mut server_received=0i64;
 let mut report=LanReport{sent_bytes:0,received_bytes:0,applied:0,skipped:0,conflicts:0};
 let mut idle_pages=0usize;
 for _ in 0..MAX_SESSION_PAGES {
    let (bundle,ack)={let c=db.lock().map_err(|e|e.to_string())?;
       (sync_bundle::export_bundle(&c,server_received)?,sync_bundle::peer_cursor(&c,&hello.device_id)?)
    };
    let (_,sent_through,outcount)=sync_bundle::bundle_meta(&bundle)?;
    let request=Page{device_id:own.clone(),ack_cursor:ack,bundle:bundle.clone()};
    write_packet(&mut s,&key,&serde_json::to_string(&request).map_err(|e|e.to_string())?)?;
    let response:PageReply=serde_json::from_str(&read_packet(&mut s,&key,&mut seen)?).map_err(|_|"Invalid server sync response".to_string())?;
    validate_ack(server_received,response.received_cursor,sent_through.max(server_received))?;
    let outgoing_advanced=response.received_cursor>server_received;
    server_received=response.received_cursor;
    let (origin,last,incount)=sync_bundle::bundle_meta(&response.bundle)?;
    if origin!=hello.device_id{return Err("Unexpected response origin".into())}
    let incoming={let mut c=db.lock().map_err(|e|e.to_string())?;
       let r=sync_bundle::import_bundle(&mut c,&response.bundle)?;
       if last>0 {sync_bundle::advance_peer_cursor(&c,&hello.device_id,last)?;} r
    };
    if !outgoing_advanced && last<=ack {idle_pages+=1;} else {idle_pages=0;}
    if idle_pages>=MAX_IDLE_PAGES{return Err("Sync made no progress; reconnect to retry".into())}
    report.sent_bytes+=bundle.len();report.received_bytes+=response.bundle.len();
    report.applied+=incoming.applied;report.skipped+=incoming.skipped;report.conflicts+=incoming.conflicts;
    // A final empty exchange confirms both sides' progress before disconnecting.
    if outcount==0 && incount==0{return Ok(report)}
 }
 Err("Sync session exceeded safety page limit".into())
}
#[cfg(test)]
mod tests{
 use super::*;
 #[test]fn key_validation(){assert!(key_from_hex(&make_key()).is_ok());assert!(key_from_hex("1234").is_err());}
 #[test]fn local_filter(){assert!(allowed("192.168.1.2".parse().unwrap()));assert!(!allowed("8.8.8.8".parse().unwrap()));}
 #[test]fn ack_rejects_future_or_rollback(){
   assert!(validate_ack(0,5,10).is_ok());
   assert!(validate_ack(5,5,5).is_ok());
   assert!(validate_ack(0,11,10).is_err());
   assert!(validate_ack(7,6,10).is_err());
   assert!(validate_ack(0,-1,10).is_err());
 }
 #[test]fn transport_limits(){assert!(MAX_SESSION_PAGES>1);assert!(MAX_IDLE_PAGES<MAX_SESSION_PAGES);assert!(MAX_INBOUND_SESSIONS>0);}
 #[test]fn inbound_sessions_are_bounded_and_released(){
   let active=Arc::new(AtomicUsize::new(0));
   let permits:Vec<_>=(0..MAX_INBOUND_SESSIONS).map(|_|try_reserve_session(&active).expect("free slot")).collect();
   assert_eq!(active.load(Ordering::Acquire), MAX_INBOUND_SESSIONS);
   assert!(try_reserve_session(&active).is_none());
   drop(permits);
   assert_eq!(active.load(Ordering::Acquire),0);
   assert!(try_reserve_session(&active).is_some());
 }
 #[test]fn duplicate_packet_rejected(){
   let k=key_from_hex(&make_key()).unwrap();
   let listener=TcpListener::bind("127.0.0.1:0").unwrap();
   let addr=listener.local_addr().unwrap();
   let receiver=std::thread::spawn(move||{
     let (mut stream,_)=listener.accept().unwrap();
     let mut seen=HashSet::new();
     assert_eq!(read_packet(&mut stream,&k,&mut seen).unwrap(),"duplicate");
     assert!(read_packet(&mut stream,&k,&mut seen).unwrap_err().contains("Repeated"));
   });
   let mut first=TcpStream::connect(addr).unwrap();
   let mut sender=Vec::new();
   // Build a complete frame using a local TCP connection so the second send reuses the exact nonce.
   let local=TcpListener::bind("127.0.0.1:0").unwrap();
   let local_addr=local.local_addr().unwrap();
   let mut tx=TcpStream::connect(local_addr).unwrap();
   let (mut rx,_)=local.accept().unwrap();
   write_packet(&mut tx,&k,"duplicate").unwrap();
   let mut sz=[0u8;4];rx.read_exact(&mut sz).unwrap();
   let n=u32::from_be_bytes(sz) as usize;
   sender.extend_from_slice(&sz);let mut payload=vec![0u8;n];rx.read_exact(&mut payload).unwrap();sender.extend_from_slice(&payload);
   first.write_all(&sender).unwrap();first.write_all(&sender).unwrap();
   receiver.join().unwrap();
 }
 #[test]fn encrypted_roundtrip(){let k=key_from_hex(&make_key()).unwrap();let listener=TcpListener::bind("127.0.0.1:0").unwrap();let addr=listener.local_addr().unwrap();let thread=std::thread::spawn(move||{let (mut stream,_)=listener.accept().unwrap();read_packet(&mut stream,&k,&mut HashSet::new()).unwrap()});let mut client=TcpStream::connect(addr).unwrap();write_packet(&mut client,&k,"hello secret").unwrap();assert_eq!(thread.join().unwrap(),"hello secret");}
}
