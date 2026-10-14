# SideNote Cross-Platform Synchronization Protocol Specification (v1.0)

## 1. Overview & Architecture
SideNote implements a peer-to-peer, encrypted, bidirectional synchronization protocol designed for Android and Windows devices communicating over a local area network (LAN) or direct IP connection.

### Core Design Principles
- **No Mandatory Cloud or Account**: All data remains strictly local and peer-to-peer.
- **Independent Offline Operation**: Either device can create, edit, pin, archive, or delete notes while disconnected.
- **Zero Silent Data Loss**: Concurrent edits from divergent devices are preserved in durable conflict tables rather than silently overwritten.
- **Cryptographic Security**: Authenticated Diffie-Hellman / pre-shared 256-bit pairing with Ed25519 device signatures and XChaCha20-Poly1305 authenticated encryption.

---

## 2. Device Identity & Pairing

### 2.1 Device Identity Model
- Every device generates a persistent Ed25519 signing keypair.
- **Windows Identity Storage**: Stored encrypted in the local SQLite `sync_meta` database protected by the Windows Data Protection API (DPAPI) in current-user scope (`CryptProtectData`).
- **Android Identity Storage**: Stored in private Android `SharedPreferences` (`sidenote_sync`) protected by Android OS application sandboxing.
- **Public Key**: 32-byte Ed25519 public key encoded as 64 lowercase hexadecimal characters.
- **Public Key Fingerprint**: SHA-256 digest of the 32-byte public key, formatted as four 4-character uppercase alphanumeric blocks: `XXXX-XXXX-XXXX-XXXX`.

### 2.2 Pairing Handshake & Approval
1. **Pairing Key**: 256-bit (32 bytes) cryptographically random shared key displayed as 64 hex characters or QR code.
2. **Challenge Exchange**:
   - Initiator connects to peer TCP port `43861`.
   - Both devices exchange 32-byte cryptographically secure random nonces (`mine_nonce` / `peer_nonce`).
   - Each peer computes an Ed25519 digital signature over `[expected_peer_public_key || peer_nonce]`.
   - Each peer verifies the incoming signature against the trusted pinned public key.
3. **Session Verification**: Connections presenting invalid keys, expired challenges, altered packets, or duplicate frame nonces are terminated immediately.

---

## 3. Wire Protocol & Framing

### 3.1 Network Transport
- **Default Port**: TCP `43861` (configurable fallback).
- **Session Limits**: Max 8 concurrent inbound sessions with automatic permit cleanup.
- **Connection Timeout**: 10-second read and write timeouts.

### 3.2 Binary Packet Framing
Each packet transmitted over the TCP stream follows strict binary framing:
```
+-----------------------------------------------------------+
| Field             | Type         | Size    | Description  |
+-----------------------------------------------------------+
| Length            | Big-Endian   | 4 bytes | 24 + len(CT) |
| Nonce             | Raw bytes    | 24 bytes| XNonce       |
| Ciphertext (CT)   | AEAD payload | N bytes | Ciphertext   |
| Tag               | Poly1305 Tag | 16 bytes| Appended tag |
+-----------------------------------------------------------+
```
- Maximum frame length: `5,500,000` bytes (5.5 MB).
- Nonce uniqueness: Any repeated nonce inside a TCP session triggers immediate disconnection to prevent replay attacks.

---

## 4. Message Schemas & Wire Payloads

### 4.1 Packet Types
All decrypted payloads are UTF-8 JSON objects conforming to protocol version 1.

#### A. Hello
```json
{
  "deviceId": "c1f7a28e-87bf-4e2a-940b-08f3e0988631"
}
```

#### B. Proof (Authentication)
```json
{
  "publicKey": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "nonce": "a1b2c3d4e5f6...",
  "signature": "..."
}
```

#### C. Page (Sync Request)
```json
{
  "deviceId": "c1f7a28e-87bf-4e2a-940b-08f3e0988631",
  "ackCursor": 42,
  "bundle": "{\"protocolVersion\":1,\"originDevice\":\"...\",\"changes\":[...]}"
}
```

#### D. PageReply (Sync Response)
```json
{
  "bundle": "{\"protocolVersion\":1,\"originDevice\":\"...\",\"changes\":[...]}",
  "receivedCursor": 58
}
```

---

## 5. Note Data Model & Normalization

Both Android and Windows serialize notes to the same JSON dictionary:
```json
{
  "id": "e2f1837a-4ab2-46cc-92e3-6a98dc563e41",
  "title": "Grocery List",
  "body": "[ ] Milk\n[x] Bread",
  "color": 2,
  "checklist": true,
  "isChecklist": true,
  "updatedAt": 1776153600000,
  "pinned": true,
  "archived": false,
  "reminderAt": 0
}
```

| Field | Type | Description |
|---|---|---|
| `id` | String (UUIDv4) | Stable immutable global note identifier |
| `title` | String (max 2000 chars) | Note title |
| `body` | String (max 200,000 chars) | Note body text / markdown / checklist lines |
| `color` | Integer (0..5) | Theme / color index |
| `checklist` / `isChecklist` | Boolean | Checklist mode indicator |
| `updatedAt` | Integer (epoch ms) | Monotonic modification timestamp |
| `pinned` | Boolean | Always-pinned state |
| `archived` | Boolean | Soft-archived state |
| `reminderAt` | Integer (epoch ms) | Optional scheduled reminder epoch |

---

## 6. Change Tracking, Tombstones & Pagination

1. **Change Tracking Log**:
   - Every note mutation is assigned a local monotonically increasing `seq` (sequence number).
   - Operations: `"upsert"` or `"delete"`.
   - On deletion, note is removed from active store and recorded in `tombstones` table with `originDevice` and `deletedAt`.
2. **Pagination & Bundles**:
   - Changes are paginated in chunks of up to 500 changes per bundle.
   - Max bundle size: 5 MB.
3. **Persistent Cursors**:
   - Each peer tracks the highest confirmed sequence number received from every remote device: `cursor_<peerDeviceId>`.
   - Acknowledgement validation prevents cursor rollbacks or advancing beyond sent limits.
4. **Idempotency**:
   - `seen_<originDevice>` set tracks previously applied sequence numbers. Re-sent packets are safely skipped.

---

## 7. Conflict Preservation & Resolution

When an incoming change arrives:
1. **No Local Note**: Applied directly.
2. **Identical Lineage & Timestamp**: Skipped (already in sync).
3. **Conflicting Concurrent Edit**:
   - If local note has been modified since last sync or deleted locally, the incoming note is NOT allowed to overwrite local data.
   - The remote payload is recorded in `sync_conflicts` along with `originDevice`, `originSeq`, `operation`, and `changedAt`.
   - The user is notified and can resolve the conflict in the **Sync & Devices** screen (`Keep Local` or `Save Copy` / `Apply Remote`).
