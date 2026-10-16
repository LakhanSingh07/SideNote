use std::{
    collections::HashSet,
    io::{Read, Write},
    net::{IpAddr, SocketAddr, TcpListener, TcpStream, UdpSocket},
    sync::{Arc, Mutex},
    thread,
    time::{Duration, Instant, SystemTime},
};
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter};
use uuid::Uuid;

use crate::sync_bundle;

pub const TCP_PORT: u16 = 43861;
pub const UDP_PORT: u16 = 43862;
const MAX_FRAME: usize = 6_000_000;

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct PairingPinEvent {
    pub pin: String,
    pub device_name: String,
    pub device_id: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct PairedDeviceEvent {
    pub device_id: String,
    pub device_name: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct SyncStatusReport {
    pub applied: usize,
    pub skipped: usize,
    pub conflicts: usize,
    pub peer_device_id: String,
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "type")]
enum WireMessage {
    #[serde(rename="UNPAIR")]
    Unpair {#[serde(rename="deviceId")] device_id:String,token:String},
    #[serde(rename = "PAIR_REQUEST")]
    PairRequest {
        #[serde(rename = "deviceId")]
        device_id: String,
        #[serde(rename = "deviceName")]
        device_name: String,
    },
    #[serde(rename = "PAIR_VERIFY")]
    PairVerify {
        #[serde(rename = "deviceId")]
        device_id: String,
        #[serde(rename = "deviceName")]
        device_name: String,
        pin: String,
    },
    #[serde(rename = "SYNC_REQUEST")]
    SyncRequest {
        #[serde(rename = "deviceId")]
        device_id: String,
        token: String,
        #[serde(rename = "ackCursor", default)]
        ack_cursor: i64,
        #[serde(default)]
        bundle: Option<String>,
    },
    #[serde(rename = "FILE_REQUEST")]
    FileRequest {
        #[serde(rename="deviceId")] device_id:String,
        token:String, op:String,
        #[serde(rename="noteId")] note_id:String,
        #[serde(rename="attachmentId")] attachment_id:String,
        #[serde(default)] offset:u64,
        #[serde(default)] length:usize,
    },
    #[serde(rename = "PING")]
    Ping,
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "type")]
enum WireResponse {
    #[serde(rename = "PAIR_CHALLENGE")]
    PairChallenge {
        status: String,
        message: String,
    },
    #[serde(rename = "PAIR_SUCCESS")]
    PairSuccess {
        status: String,
        token: String,
        #[serde(rename = "deviceId")]
        device_id: String,
        #[serde(rename = "deviceName")]
        device_name: String,
    },
    #[serde(rename = "PAIR_ERROR")]
    PairError {
        status: String,
        error: String,
    },
    #[serde(rename = "SYNC_REPLY")]
    SyncReply {
        status: String,
        #[serde(rename = "receivedCursor")]
        received_cursor: i64,
        bundle: String,
        #[serde(rename = "appliedCount")]
        applied_count: usize,
    },
    #[serde(rename = "SYNC_ERROR")]
    SyncError {
        status: String,
        error: String,
    },
    #[serde(rename = "PONG")]
    Pong {
        status: String,
        #[serde(rename = "deviceId")]
        device_id: String,
        #[serde(rename = "deviceName")]
        device_name: String,
    },
}

struct PendingPairing {
    pin: String,
    device_id: String,
    device_name: String,
    created_at: Instant,
}

fn pairing_matches(pending:&PendingPairing,device_id:&str,pin:&str)->bool {
    pending.created_at.elapsed()<Duration::from_secs(120)
        && (pending.device_id=="manual" || pending.device_id==device_id)
        && pending.pin==pin.trim()
}

static PENDING_PAIRING: Mutex<Option<PendingPairing>> = Mutex::new(None);
static INVITE: Mutex<Option<(String,Instant)>> = Mutex::new(None);
static PIN_FAILURES: Mutex<u8> = Mutex::new(0);
static LISTENING:std::sync::atomic::AtomicBool=std::sync::atomic::AtomicBool::new(false);
static OWN_ID:std::sync::OnceLock<String>=std::sync::OnceLock::new();
static CONTACTS:std::sync::OnceLock<Mutex<std::collections::HashMap<String,(u64,IpAddr)>>>=std::sync::OnceLock::new();
fn touch(id:&str,ip:IpAddr){if let Ok(mut peers)=CONTACTS.get_or_init(||Mutex::new(std::collections::HashMap::new())).lock(){peers.insert(id.into(),(SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap_or_default().as_secs(),ip));}}
pub fn forget_contact(id:&str){if let Ok(mut peers)=CONTACTS.get_or_init(||Mutex::new(std::collections::HashMap::new())).lock(){peers.remove(id);}}
#[tauri::command]
pub fn lan_service_status()->serde_json::Value {
    let now=SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap_or_default().as_secs();
    let peers=CONTACTS.get_or_init(||Mutex::new(std::collections::HashMap::new())).lock().map(|p|p.iter().map(|(id,(time,_))|serde_json::json!({"deviceId":id,"lastSeen":time*1000,"online":now.saturating_sub(*time)<=45})).collect::<Vec<_>>()).unwrap_or_default();
    serde_json::json!({"listening":LISTENING.load(std::sync::atomic::Ordering::Relaxed),"encrypted":true,"peers":peers})
}

fn consume_pairing(device_id:&str,pin:&str)->bool {
    let mut invite=INVITE.lock().unwrap();
    if invite.as_ref().map(|(secret,time)|time.elapsed()<Duration::from_secs(120) && secret==pin).unwrap_or(false){
        *invite=None;*PENDING_PAIRING.lock().unwrap()=None;return true
    }
    let mut pending=PENDING_PAIRING.lock().unwrap();let mut failures=PIN_FAILURES.lock().unwrap();
    let valid=*failures<8 && pending.as_ref().map(|p|pairing_matches(p,device_id,pin)).unwrap_or(false);
    if valid {*pending=None;*invite=None;*failures=0;}else{*failures=failures.saturating_add(1);}
    valid
}

#[derive(Serialize)]
#[serde(rename_all="camelCase")]
pub struct PairingInvitation {pin:String,code:String,svg:String,fingerprint:String,expires_at:u64}

#[tauri::command]
pub fn pairing_invitation(app:AppHandle,db:tauri::State<'_,crate::Database>)->Result<PairingInvitation,String>{
    {let c=db.0.lock().map_err(|e|e.to_string())?;crate::pro::require(&c)?;}
    let pin=request_pairing_pin(&app,&db.0)?;
    let secret=make_key();let fingerprint=crate::secure_lan::fingerprint()?;
    let hosts:Vec<String>=if_addrs::get_if_addrs().map_err(|e|e.to_string())?.into_iter()
        .filter_map(|a|match a.ip(){IpAddr::V4(ip) if ip.is_private() || ip.is_link_local()=>Some(ip.to_string()),_=>None}).collect();
    if hosts.is_empty(){return Err("Connect this PC to Wi-Fi or a local network first".into())}
    let id={let c=db.0.lock().map_err(|e|e.to_string())?;local_id(&c)?};
    let expires_at=SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap_or_default().as_secs()+120;
    let code=serde_json::json!({"app":"SideNote","version":2,"hosts":hosts,"deviceId":id,"name":local_computer_name(),"fingerprint":fingerprint,"secret":secret,"expiresAt":expires_at}).to_string();
    let qr=qrcode::QrCode::new(code.as_bytes()).map_err(|e|e.to_string())?;
    let svg=qr.render::<qrcode::render::svg::Color>().min_dimensions(256,256).build();
    *INVITE.lock().unwrap()=Some((secret,Instant::now()));
    Ok(PairingInvitation{pin,code,svg,fingerprint,expires_at})
}

#[tauri::command]
pub fn lan_certificate_fingerprint()->Result<String,String>{crate::secure_lan::fingerprint()}

pub fn make_key() -> String {
    let mut k = [0u8; 32];
    chacha20poly1305::aead::rand_core::RngCore::fill_bytes(
        &mut chacha20poly1305::aead::OsRng,
        &mut k,
    );
    hex::encode(k)
}

fn local_computer_name() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "Windows PC".into())
}

fn local_id(c: &Connection) -> Result<String, String> {
    c.query_row(
        "SELECT value FROM sync_meta WHERE key='device_id'",
        [],
        |r| r.get(0),
    )
    .map_err(|e| e.to_string())
}

fn read_framed_json(stream: &mut impl Read) -> Result<String, String> {
    let mut len_buf = [0u8; 4];
    stream.read_exact(&mut len_buf).map_err(|e| e.to_string())?;
    let len = u32::from_be_bytes(len_buf) as usize;
    if len > MAX_FRAME || len == 0 {
        return Err(format!("Invalid frame length: {len}"));
    }
    let mut payload = vec![0u8; len];
    stream.read_exact(&mut payload).map_err(|e| e.to_string())?;
    String::from_utf8(payload).map_err(|e| format!("Invalid UTF-8 payload: {e}"))
}

fn write_framed_json(stream: &mut impl Write, json: &str) -> Result<(), String> {
    let bytes = json.as_bytes();
    if bytes.len() > MAX_FRAME {
        return Err("Payload exceeds max frame size".into());
    }
    let len_bytes = (bytes.len() as u32).to_be_bytes();
    stream.write_all(&len_bytes).map_err(|e| e.to_string())?;
    stream.write_all(bytes).map_err(|e| e.to_string())?;
    stream.flush().map_err(|e| e.to_string())?;
    Ok(())
}

fn handle_tcp_client(
    mut stream: TcpStream,
    app: AppHandle,
    db: Arc<Mutex<Connection>>,
) -> Result<(), String> {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(12)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(12)));
    let peer_ip=stream.peer_addr().map_err(|e|e.to_string())?.ip();

    let mut stream=crate::secure_lan::accept(stream)?;
    let raw_req = read_framed_json(&mut stream)?;
    let msg: WireMessage = match serde_json::from_str(&raw_req) {
        Ok(m) => m,
        Err(e) => {
            let err_resp = WireResponse::SyncError {
                status: "error".into(),
                error: format!("Malformed message: {e}"),
            };
            let _ = write_framed_json(&mut stream, &serde_json::to_string(&err_resp).unwrap());
            return Err(e.to_string());
        }
    };

    match msg {
        WireMessage::Unpair{device_id,token}=>{
            let c=db.lock().map_err(|e|e.to_string())?;
            let stored:Option<String>=c.query_row("SELECT public_key FROM trusted_peers WHERE device_id=?1",[&device_id],|r|r.get(0)).ok().flatten();
            if !crate::secure_lan::verify_token(stored.as_deref(),&token)?{return Err("Device not paired".into())}
            c.execute("DELETE FROM trusted_peers WHERE device_id=?1",[&device_id]).map_err(|e|e.to_string())?;forget_contact(&device_id);
            write_framed_json(&mut stream,r#"{"status":"ok"}"#)?;
        }
        WireMessage::FileRequest {device_id,token,op,note_id,attachment_id,offset,length} => {
            let result=(||->Result<(),String>{
                let file={
                    let c=db.lock().map_err(|e|e.to_string())?;
                    let stored:Option<String>=c.query_row("SELECT public_key FROM trusted_peers WHERE device_id=?1",[&device_id],|r|r.get(0)).ok().flatten();
                    if !crate::secure_lan::verify_token(stored.as_deref(),token.trim())?{return Err("Device not paired".into())}
                    touch(&device_id,peer_ip);
                    crate::pro::require(&c)?;
                    let raw:String=c.query_row("SELECT attachments FROM notes WHERE id=?1",[&note_id],|r|r.get(0)).map_err(|_|"Note not found")?;
                    let files:Vec<crate::attachments::Attachment>=serde_json::from_str(&raw).map_err(|e|e.to_string())?;
                    files.into_iter().find(|f|f.id==attachment_id).ok_or("Attachment not found")?
                };
                file.validate()?;
                let dir=crate::attachments::directory(&app)?;
                let state=match op.as_str(){
                    "status"=>crate::attachments::status(&dir,&file)?,
                    "received"=>{crate::attachments::set_transfer(&file.sha256,"Available on paired device".into());crate::attachments::status(&dir,&file)?},
                    "put"=>{
                        if length>crate::attachments::CHUNK_SIZE || offset>file.size || length as u64>file.size-offset{return Err("Invalid file range".into())}
                        let mut bytes=vec![0;length];stream.read_exact(&mut bytes).map_err(|e|e.to_string())?;
                        crate::attachments::receive_chunk(&dir,&file,offset,&bytes)?
                    },
                    "get"=>{
                        let data=crate::attachments::read_chunk(&dir,&file,offset,length)?;
                        write_framed_json(&mut stream,&serde_json::json!({"type":"FILE_REPLY","status":"ok","length":data.len(),"total":file.size,"offset":offset}).to_string())?;
                        stream.write_all(&data).map_err(|e|e.to_string())?;return Ok(())
                    },
                    _=>return Err("Unknown file operation".into())
                };
                write_framed_json(&mut stream,&serde_json::json!({"type":"FILE_REPLY","status":"ok","complete":state.complete,"bytes":state.bytes}).to_string())
            })();
            if let Err(error)=result {write_framed_json(&mut stream,&serde_json::json!({"type":"FILE_REPLY","status":"error","error":error}).to_string())?;}
        }
        WireMessage::Ping => {
            let (own_id, own_name) = {
                let c = db.lock().map_err(|e| e.to_string())?;
                (local_id(&c)?, local_computer_name())
            };
            let resp = WireResponse::Pong {
                status: "ok".into(),
                device_id: own_id,
                device_name: own_name,
            };
            write_framed_json(&mut stream, &serde_json::to_string(&resp).unwrap())?;
        }
        WireMessage::PairRequest {
            device_id,
            device_name,
        } => {
            // Generate a crisp, clean 4-digit numeric PIN: 1000..9999
            let mut rand_bytes = [0u8; 2];
            chacha20poly1305::aead::rand_core::RngCore::fill_bytes(
                &mut chacha20poly1305::aead::OsRng,
                &mut rand_bytes,
            );
            let val = u16::from_be_bytes(rand_bytes) % 9000 + 1000;
            let pin = format!("{:04}", val);

            // Store in pending pairing
            {
                let mut guard = PENDING_PAIRING.lock().unwrap();
                *guard = Some(PendingPairing {
                    pin: pin.clone(),
                    device_id: device_id.clone(),
                    device_name: device_name.clone(),
                    created_at: Instant::now(),
                });
            }

            // Notify Windows UI with PIN modal/toast
            let _ = app.emit(
                "sidenote_pairing_pin",
                PairingPinEvent {
                    pin: pin.clone(),
                    device_name: device_name.clone(),
                    device_id: device_id.clone(),
                },
            );

            let resp = WireResponse::PairChallenge {
                status: "challenge".into(),
                message: format!("Enter 4-digit PIN {} on your Android phone", pin),
            };
            write_framed_json(&mut stream, &serde_json::to_string(&resp).unwrap())?;
        }
        WireMessage::PairVerify {
            device_id,
            device_name,
            pin,
        } => {
            let is_valid = consume_pairing(&device_id,&pin);

            if !is_valid {
                let resp = WireResponse::PairError {
                    status: "error".into(),
                    error: "Invalid or expired pairing PIN. Please try again.".into(),
                };
                write_framed_json(&mut stream, &serde_json::to_string(&resp).unwrap())?;
                return Err("Invalid pairing PIN".into());
            }


            // Generate durable pairing secret token
            let token = make_key();
            let (own_id, own_name) = {
                let c = db.lock().map_err(|e| e.to_string())?;
                let own_id = local_id(&c)?;
                let own_name = local_computer_name();
                c.execute(
                    "INSERT INTO trusted_peers(device_id, label, public_key) VALUES (?1, ?2, ?3) ON CONFLICT(device_id) DO UPDATE SET label=excluded.label, public_key=excluded.public_key",
                    params![device_id, device_name, crate::secure_lan::protect_token(&token)?],
                ).map_err(|e| e.to_string())?;
                (own_id, own_name)
            };

            let _ = app.emit(
                "sidenote_paired",
                PairedDeviceEvent {
                    device_id: device_id.clone(),
                    device_name: device_name.clone(),
                },
            );

            let resp = WireResponse::PairSuccess {
                status: "ok".into(),
                token,
                device_id: own_id,
                device_name: own_name,
            };
            write_framed_json(&mut stream, &serde_json::to_string(&resp).unwrap())?;
        }
        WireMessage::SyncRequest {
            device_id,
            token,
            ack_cursor,
            bundle,
        } => {
            // Verify device is trusted with this token
            let mut applied_count = 0;
            let (outgoing_bundle, received_cursor) = {
                let mut c = db.lock().map_err(|e| e.to_string())?;

                let stored_token: Option<String> = c
                    .query_row(
                        "SELECT public_key FROM trusted_peers WHERE device_id=?1",
                        [&device_id],
                        |r| r.get(0),
                    )
                    .ok()
                    .flatten();

                if !crate::secure_lan::verify_token(stored_token.as_deref(),token.trim())? {
                    let resp = WireResponse::SyncError {
                        status: "unauthorized".into(),
                        error: "Device not paired or pairing token revoked".into(),
                    };
                    write_framed_json(&mut stream, &serde_json::to_string(&resp).unwrap())?;
                    return Err("Unauthorized sync attempt".into());
                }
                touch(&device_id,peer_ip);

                crate::pro::require(&c)?;
                // If remote sent an incoming bundle, import it!
                if let Some(ref incoming_json) = bundle {
                    if !incoming_json.trim().is_empty() {
                        let (source,_,_)=sync_bundle::bundle_meta(incoming_json)?;
                        if source!=device_id {return Err("Bundle origin does not match paired device".into())}
                        match sync_bundle::import_bundle(&mut c, incoming_json) {
                            Ok(report) => {
                                applied_count = report.applied;
                                if let Ok((_, last_seq, _)) =
                                    sync_bundle::bundle_meta(incoming_json)
                                {
                                    if last_seq > 0 {
                                        let _ = sync_bundle::advance_peer_cursor(
                                            &c,
                                            &device_id,
                                            last_seq,
                                        );
                                    }
                                }
                            }
                            Err(e) => {
                                let resp=WireResponse::SyncError{status:"error".into(),error:e};
                                write_framed_json(&mut stream,&serde_json::to_string(&resp).map_err(|e|e.to_string())?)?;
                                return Ok(());
                            }
                        }
                    }
                }

                // Prepare outgoing bundle for the peer
                let outgoing = sync_bundle::export_bundle(&c, ack_cursor.max(0))
                    ?;
                let received = sync_bundle::peer_cursor(&c, &device_id).unwrap_or(0);

                (outgoing, received)
            };

            // If notes were applied, inform frontend to refresh!
            if applied_count > 0 {
                let _ = app.emit("sidenote_sync_updated", applied_count);
            }

            let resp = WireResponse::SyncReply {
                status: "ok".into(),
                received_cursor,
                bundle: outgoing_bundle,
                applied_count,
            };
            write_framed_json(&mut stream, &serde_json::to_string(&resp).unwrap())?;
        }
    }

    Ok(())
}

fn start_tcp_server(app: AppHandle, db: Arc<Mutex<Connection>>) {
    thread::spawn(move || {
        let listener = match TcpListener::bind(("0.0.0.0", TCP_PORT)) {
            Ok(l) => l,
            Err(e) => {
                eprintln!("SideNote TCP sync bind error on port {TCP_PORT}: {e}");
                return;
            }
        };
        LISTENING.store(true,std::sync::atomic::Ordering::Relaxed);
        let active=Arc::new(std::sync::atomic::AtomicUsize::new(0));
        for stream in listener.incoming() {
            if let Ok(s) = stream {
                if active.fetch_add(1,std::sync::atomic::Ordering::Relaxed)>=32{active.fetch_sub(1,std::sync::atomic::Ordering::Relaxed);continue}
                let active=active.clone();
                let app_c = app.clone();
                let db_c = db.clone();
                thread::spawn(move || {
                    struct Slot(Arc<std::sync::atomic::AtomicUsize>);impl Drop for Slot{fn drop(&mut self){self.0.fetch_sub(1,std::sync::atomic::Ordering::Relaxed);}}let _slot=Slot(active);
                    if let Err(e) = handle_tcp_client(s, app_c, db_c) {
                        eprintln!("SideNote sync client error: {e}");
                    }
                });
            }
        }
    });
}

fn start_udp_discovery_responder(db: Arc<Mutex<Connection>>) {
    thread::spawn(move || {
        let socket = match UdpSocket::bind(("0.0.0.0", UDP_PORT)) {
            Ok(s) => s,
            Err(e) => {
                eprintln!("SideNote UDP discovery bind error on port {UDP_PORT}: {e}");
                return;
            }
        };
        let _ = socket.set_broadcast(true);
        let mut buf = [0u8; 4096];

        loop {
            match socket.recv_from(&mut buf) {
                Ok((size, src)) => {
                    let msg = String::from_utf8_lossy(&buf[..size]);
                    if let Ok(val) = serde_json::from_str::<serde_json::Value>(&msg) {
                        let cmd = val.get("cmd").and_then(|v| v.as_str()).unwrap_or("");
                        if cmd == "SIDENOTE_DISCOVER" {
                            let (own_id, own_name) = {
                                if let Ok(c) = db.lock() {
                                    (local_id(&c).unwrap_or_default(), local_computer_name())
                                } else {
                                    (String::new(), local_computer_name())
                                }
                            };
                            let reply = serde_json::json!({
                                "cmd": "SIDENOTE_ANNOUNCE",
                                "deviceId": own_id,
                                "deviceName": own_name,
                                "port": TCP_PORT,
                                "platform": "windows", "protocol":2, "fingerprint":crate::secure_lan::fingerprint().unwrap_or_default()
                            });
                            let reply_bytes = serde_json::to_vec(&reply).unwrap_or_default();
                            let _ = socket.send_to(&reply_bytes, src);
                        }
                    }
                }
                Err(e) => {
                    eprintln!("SideNote UDP discovery error: {e}");
                    thread::sleep(Duration::from_millis(500));
                }
            }
        }
    });
}

pub fn init_auto_sync(app: AppHandle, db: Arc<Mutex<Connection>>) {
    if let Ok(c)=db.lock(){if let Ok(id)=local_id(&c){let _=OWN_ID.set(id);}}
    start_tcp_server(app, db.clone());
    start_udp_discovery_responder(db.clone());
    if let (Ok(daemon),Ok(c))=(mdns_sd::ServiceDaemon::new(),db.lock()) {
        if let Ok(id)=local_id(&c) {
            let host=format!("sidenote-{}.local.",id);
            let properties=std::collections::HashMap::from([("deviceId".to_string(),id),("name".to_string(),local_computer_name()),("fingerprint".to_string(),crate::secure_lan::fingerprint().unwrap_or_default()),("protocol".to_string(),"2".to_string())]);
            if let Ok(service)=mdns_sd::ServiceInfo::new("_sidenote._tcp.local.",&local_computer_name(),&host,"",TCP_PORT,properties){let _=daemon.register(service.enable_addr_auto());}
            // Keep the responder alive for the lifetime of the app.
            std::mem::forget(daemon);
        }
    }
}

pub fn notify_peers_note_changed() {
    thread::spawn(|| {
        if let Ok(socket) = UdpSocket::bind("0.0.0.0:0") {
            let _ = socket.set_broadcast(true);
            let packet = serde_json::json!({
                "cmd": "SIDENOTE_NOTIFY",
                "deviceId":OWN_ID.get().cloned().unwrap_or_default(),
                "ts": SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap_or_default().as_millis() as u64
            });
            let bytes = serde_json::to_vec(&packet).unwrap_or_default();
            let _ = socket.send_to(&bytes, format!("255.255.255.255:{UDP_PORT}"));
            if let Ok(peers)=CONTACTS.get_or_init(||Mutex::new(std::collections::HashMap::new())).lock(){for (_,ip) in peers.values(){let _=socket.send_to(&bytes,SocketAddr::new(*ip,UDP_PORT));}}
        }
    });
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LanReport {
    pub sent_bytes: usize,
    pub received_bytes: usize,
    pub applied: usize,
    pub skipped: usize,
    pub conflicts: usize,
}

pub fn start_server(_db: Arc<Mutex<Connection>>, _key_hex: &str) -> Result<String, String> {
    Ok(format!("Sync service actively running on TCP port {TCP_PORT}"))
}

pub fn sync_peer(
    db: Arc<Mutex<Connection>>,
    address: &str,
    _key_hex: &str,
) -> Result<LanReport, String> {
    let addr: SocketAddr = format!("{}:{}", address.trim(), TCP_PORT)
        .parse()
        .map_err(|_| "Enter a valid IP address (e.g. 192.168.1.15)".to_string())?;

    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(5))
        .map_err(|e| format!("Connection failed: {e}"))?;
    let _ = stream.set_read_timeout(Some(Duration::from_secs(10)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(10)));

    let (own_id, ack_cursor) = {
        let c = db.lock().map_err(|e| e.to_string())?;
        (local_id(&c)?, 0i64)
    };

    let ping = WireMessage::Ping;
    write_framed_json(&mut stream, &serde_json::to_string(&ping).unwrap())?;
    let resp_str = read_framed_json(&mut stream)?;

    let report = LanReport {
        sent_bytes: 0,
        received_bytes: resp_str.len(),
        applied: 0,
        skipped: 0,
        conflicts: 0,
    };

    let _=report;
    Err("The phone syncs through its paired connection. Use Request sync or Sync now on Android.".into())
}

pub fn request_pairing_pin(app: &AppHandle, db: &Arc<Mutex<Connection>>) -> Result<String, String> {
    *PIN_FAILURES.lock().unwrap()=0;
    let mut rand_bytes = [0u8; 2];
    chacha20poly1305::aead::rand_core::RngCore::fill_bytes(
        &mut chacha20poly1305::aead::OsRng,
        &mut rand_bytes,
    );
    let val = u16::from_be_bytes(rand_bytes) % 9000 + 1000;
    let pin = format!("{:04}", val);

    let (own_id, own_name) = {
        let c = db.lock().map_err(|e| e.to_string())?;
        (local_id(&c)?, local_computer_name())
    };

    {
        let mut guard = PENDING_PAIRING.lock().unwrap();
        *guard = Some(PendingPairing {
            pin: pin.clone(),
            device_id: "manual".into(),
            device_name: "Phone".into(),
            created_at: Instant::now(),
        });
    }

    let _ = app.emit(
        "sidenote_pairing_pin",
        PairingPinEvent {
            pin: pin.clone(),
            device_name: own_name,
            device_id: own_id,
        },
    );

    Ok(pin)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn manual_pin_accepts_the_phone_device_id() {
        let pending=PendingPairing{pin:"2674".into(),device_id:"manual".into(),device_name:"Phone".into(),created_at:Instant::now()};
        assert!(pairing_matches(&pending,"actual-android-device","2674"));
        assert!(!pairing_matches(&pending,"actual-android-device","9999"));
    }
    #[test]
    fn requested_pin_stays_bound_to_requesting_device() {
        let pending=PendingPairing{pin:"2674".into(),device_id:"phone-a".into(),device_name:"Phone".into(),created_at:Instant::now()};
        assert!(pairing_matches(&pending,"phone-a","2674"));
        assert!(!pairing_matches(&pending,"phone-b","2674"));
    }
    #[test]
    fn manual_pin_expires() {
        let pending=PendingPairing{pin:"2674".into(),device_id:"manual".into(),device_name:"Phone".into(),created_at:Instant::now()-Duration::from_secs(121)};
        assert!(!pairing_matches(&pending,"phone-a","2674"));
    }

    #[test]
    fn frame_json_roundtrip() {
        let payload = r#"{"type":"TEST","data":"hello world"}"#;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();

        let handle = thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            let rec = read_framed_json(&mut s).unwrap();
            assert_eq!(rec, payload);
            write_framed_json(&mut s, r#"{"type":"ACK"}"#).unwrap();
        });

        let mut client = TcpStream::connect(addr).unwrap();
        write_framed_json(&mut client, payload).unwrap();
        let resp = read_framed_json(&mut client).unwrap();
        assert_eq!(resp, r#"{"type":"ACK"}"#);
        handle.join().unwrap();
    }
}
