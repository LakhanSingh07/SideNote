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

static PENDING_PAIRING: Mutex<Option<PendingPairing>> = Mutex::new(None);

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

fn read_framed_json(stream: &mut TcpStream) -> Result<String, String> {
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

fn write_framed_json(stream: &mut TcpStream, json: &str) -> Result<(), String> {
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
            let is_valid = {
                let guard = PENDING_PAIRING.lock().unwrap();
                if let Some(ref pending) = *guard {
                    pending.created_at.elapsed() < Duration::from_secs(120)
                        && pending.device_id == device_id
                        && pending.pin == pin.trim()
                } else {
                    false
                }
            };

            if !is_valid {
                let resp = WireResponse::PairError {
                    status: "error".into(),
                    error: "Invalid or expired pairing PIN. Please try again.".into(),
                };
                write_framed_json(&mut stream, &serde_json::to_string(&resp).unwrap())?;
                return Err("Invalid pairing PIN".into());
            }

            // Clean pending pairing
            {
                let mut guard = PENDING_PAIRING.lock().unwrap();
                *guard = None;
            }

            // Generate durable pairing secret token
            let token = make_key();
            let (own_id, own_name) = {
                let c = db.lock().map_err(|e| e.to_string())?;
                let own_id = local_id(&c)?;
                let own_name = local_computer_name();
                c.execute(
                    "INSERT INTO trusted_peers(device_id, label, public_key) VALUES (?1, ?2, ?3) ON CONFLICT(device_id) DO UPDATE SET label=excluded.label, public_key=excluded.public_key",
                    params![device_id, device_name, token],
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

                if stored_token.as_deref() != Some(token.trim()) {
                    let resp = WireResponse::SyncError {
                        status: "unauthorized".into(),
                        error: "Device not paired or pairing token revoked".into(),
                    };
                    write_framed_json(&mut stream, &serde_json::to_string(&resp).unwrap())?;
                    return Err("Unauthorized sync attempt".into());
                }

                // If remote sent an incoming bundle, import it!
                if let Some(ref incoming_json) = bundle {
                    if !incoming_json.trim().is_empty() {
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
                                eprintln!("Error importing incoming sync bundle: {e}");
                            }
                        }
                    }
                }

                // Prepare outgoing bundle for the peer
                let outgoing = sync_bundle::export_bundle(&c, ack_cursor.max(0))
                    .unwrap_or_else(|_| "{}".into());
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
        for stream in listener.incoming() {
            if let Ok(s) = stream {
                let app_c = app.clone();
                let db_c = db.clone();
                thread::spawn(move || {
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
                                "platform": "windows"
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
    start_tcp_server(app, db.clone());
    start_udp_discovery_responder(db);
}

pub fn notify_peers_note_changed() {
    thread::spawn(|| {
        if let Ok(socket) = UdpSocket::bind("0.0.0.0:0") {
            let _ = socket.set_broadcast(true);
            let packet = serde_json::json!({
                "cmd": "SIDENOTE_NOTIFY",
                "ts": SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap_or_default().as_millis() as u64
            });
            let bytes = serde_json::to_vec(&packet).unwrap_or_default();
            let _ = socket.send_to(&bytes, format!("255.255.255.255:{UDP_PORT}"));
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

    let mut report = LanReport {
        sent_bytes: 0,
        received_bytes: resp_str.len(),
        applied: 0,
        skipped: 0,
        conflicts: 0,
    };

    Ok(report)
}

pub fn request_pairing_pin(app: &AppHandle, db: &Arc<Mutex<Connection>>) -> Result<String, String> {
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
