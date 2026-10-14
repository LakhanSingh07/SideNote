# SideNote Desktop ↔ Android Sync Contract — Draft v1

## Verified Android source
Source: user-supplied `SideNote-Android-Clean-Source.zip`, `app/src/main/java/com/sidedeck/notes/NotesStore.kt`.
Android currently persists a JSON array under SharedPreferences `side_notes` key `notes`; **not SQLite**. `Note(id, title, body, color, isChecklist, updatedAt, pinned, archived, reminderAt)`. JSON keys: `id,title,body,color,checklist,updatedAt,pinned,archived,reminderAt`. Color is integer 0–5. `updatedAt` and `reminderAt` are epoch milliseconds. Checklist body is presently text; retain its exact bytes in v1, do not invent per-item identifiers.

## Design constraints
- Windows and Android must each remain complete standalone products. No signup, QR or pairing required until user elects sync.
- Local-only data survives absence of a peer. No hosted storage is required on reachable LAN.
- Preserve existing Android note IDs and user data; no destructive migration.
- Windows SQLite stores notes, device-specific UI state and a durable operation log.
- Android may keep its current store initially but should move to Room/SQLite or implement a durable write-ahead change log with atomic state + log recording before enabling sync. Never send uncommitted operations.
- Synced fields: id, title, body, color index, checklist flag, pinned, archived, reminderAt. Platform-local fields: overlay coordinates, window state, display/keyboard settings and theme preference.

## Proposed sync envelope (not current Android format)
```json
{
  "protocolVersion": 1,
  "operationId": "uuid",
  "deviceId": "stable-public-device-identifier",
  "sequence": 57,
  "noteId": "existing-note-uuid",
  "baseRevision": {"device-A": 42, "device-B": 13},
  "kind": "upsert",
  "payload": {
    "id": "existing-note-uuid",
    "title": "Example",
    "body": "Line one\nLine two",
    "color": 2,
    "checklist": false,
    "pinned": true,
    "archived": false,
    "reminderAt": 0
  }
}
```
`kind` may be `upsert` or `delete`. Keep deletion tombstones and never resurrect deleted notes during a later merge. Transport payloads must be size-limited and schema validated.

## First sync
1. Explicitly opt in on either device. Choose QR scan, short pairing code with user verification, or LAN discovery with confirmation on both peers.
2. Authenticated key exchange, with an out-of-band verification step; pin peer identity and encrypt traffic. QR/code alone does not authenticate subsequent packets unless cryptographically bound.
3. Exchange device IDs, schema versions, latest operation cursors and full initial note snapshot.
4. Union distinct note IDs. If the same ID differs and neither revision descends from the other, preserve both versions and surface a conflict UI. Never merge notes based on matching titles.
5. Record per-peer sync cursor *after* durable application of changes; replay safely using operation IDs.
6. Sync both directions, retry after interruptions, and expose last sync and pending changes.

## Reliability gates
- Migrate from existing SharedPreferences without changing original IDs or losing note contents; retain backup and perform rollback test.
- Both apps launch and function without network, pairing or account.
- Two Windows instances can pair and merge without Android.
- Android ↔ Windows round-trip note text/colors/checklist/pin/archive/reminder; keep window coordinates local.
- Repeat/reorder the same message: idempotence. Cut connection halfway through: no data loss/duplicates.
- Concurrent edits: preserve both copies or explicitly merge with user consent. Clock skew must not silently overwrite.
- Delete a note offline: tombstone syncs when reconnecting.
- Unpair prevents future access while preserving local notes.
- Windows auto-discovery must tolerate firewall/mDNS blocking with manual IP pairing.
- Android doze/background limits mean sync is best effort and may run when foreground or approved background conditions permit; do not promise continuous guaranteed sync.

## Recommended sequence
1. Build Windows standalone Tauri 2 + React + TypeScript + SQLite, edge tabs + hover preview + independent floating notes, tray/startup and own QA.
2. Freeze tests for Android↔Windows note format and preserve legacy JSON conversion.
3. Implement signed/verified peer pairing and encrypted LAN sync, test Windows↔Windows.
4. Implement Android sync module and safe migration; test Android↔Windows both ways.
5. Package installer and APK/AAB separately; run end-to-end reliability and compatibility matrix.

This document is an architecture specification, **not** a working sync implementation.
