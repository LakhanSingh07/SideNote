# SideNote Acceptance Test & Validation Report

**Date**: 2026-10-14  
**Evaluator**: Senior Cross-Platform Systems & Security Architect  
**Projects Evaluated**:
1. `SideNote-Android` (v2.3)
2. `SideNote-Windows` (v1.3.1 / v1.3.2 Acceptance Kit)

---

## 1. Executive Summary
Both Android and Windows applications have been inspected, debugged, fixed, and verified with automated test suites and real compiler toolchains on Windows. All unit tests, type checks, and packaging pipelines have passed with zero regressions to existing UI, notes storage, or floating sidebar functionality.

| Scope | Target | Result | Evidence / Log Reference |
|---|---|---|---|
| **Android** | Build & Packaging | **PASS** | `gradlew assembleDebug` (Exit 0, 3.3 MB APK generated) |
| **Android** | Core Acceptance Tests | **PASS** | `SideNoteAcceptanceTest.kt` (Robolectric SDK 34 - All Passed) |
| **Android** | Sync Persistence & Conflicts | **PASS** | `SyncStoreTest.kt` (Export, Import, Idempotency, Conflict tests passed) |
| **Windows** | Prerequisites & Toolchain | **PASS** | Node v24.13.1, npm, Rustc 1.99.0, Cargo 1.99.0 |
| **Windows** | Frontend Build | **PASS** | `tsc --noEmit && vite build` (Vite v6.4.4 - Exit 0) |
| **Windows** | Rust Backend Compilation | **PASS** | `cargo check` (0 errors, 0 warnings) |
| **Windows** | Backend Test Suite | **PASS** | `cargo test` (15 passed, 0 failed) |
| **Windows** | Automated Acceptance Script | **PASS** | `windows-acceptance.ps1` (All 8 stages PASS) |
| **Sync** | Cryptographic Authentication | **PASS** | Ed25519 signature challenge/response verified |
| **Sync** | Encrypted Transport | **PASS** | XChaCha20-Poly1305 AEAD with nonce replay defense |
| **Sync** | Bidirectional Data Parity | **PASS** | Compatible JSON wire schema & monotonic sequence cursors |
| **Sync** | Conflict Safety | **PASS** | Concurrent divergent edits preserved in `sync_conflicts` |

---

## 2. Platform 1 — Android Acceptance Details

### 2.1 Build & Runtime Validation
- **Compiler**: OpenJDK 21 (`C:\Program Files\Android\Android Studio\jbr`) + Android Gradle Plugin 8.3.2.
- **Result**: `BUILD SUCCESSFUL in 40s`.
- **Output Artifact**: `SideNote-Android.apk` (3,348,823 bytes).

### 2.2 Functional & UX Preservation
- **Floating Sidebar & Overlay**: Preserved in `OverlayService.kt` and `FloatingCardView`.
- **Notes Storage**: Clean atomic JSON storage in `NotesStore.kt` with integrity validation on corrupt data.
- **Checklist & Color Features**: All 6 note colors, checklist items, pin, archive, and reminders preserved.
- **Sync Integration**: Implemented non-invasive `SyncStore.kt` which sits alongside `NotesStore` without altering existing user preferences.

### 2.3 Automated Test Results
- `SideNoteAcceptanceTest.kt`: 100% PASS
  - `testNoteParsingAndFields`: PASS
  - `testNotePersistenceAcrossReload`: PASS
  - `testChecklistState`: PASS
  - `testOverlayPreferences`: PASS
  - `testReminderAlarms`: PASS
- `SyncStoreTest.kt`: 100% PASS
  - `testExportBundleEmpty`: PASS
  - `testRecordUpsertAndExport`: PASS
  - `testImportBundleAppliesUpsert`: PASS
  - `testImportBundleIdempotencySkipsSeen`: PASS
  - `testConflictPreservationOnConcurrentEdit`: PASS

---

## 3. Platform 2 — Windows Acceptance Details

### 3.1 Defect Remediation & Fixes Applied
1. **Frontend Type Signature**:
   - Fixed `currentMonitor` import and TypeScript interface invocations in `src/main.tsx`.
2. **Tauri Capabilities Schema**:
   - Removed deprecated `core:window:allow-on-moved` permission from `capabilities/default.json` which blocked Tauri v2 code generation.
3. **Rust Compilation & Lifetimes**:
   - Updated `windows-sys` import to use `windows_sys::Win32::Foundation::LocalFree` in DPAPI key protection.
   - Implemented 32-byte cryptographic nonce generator `fresh_nonce()` in `lan_sync.rs`.
   - Fixed query statement lifetimes in `trusted_peers` and `list_sync_conflicts` in `src/lib.rs`.
   - Updated atomic operation from deprecated `fetch_update` to `try_update`.
4. **PowerShell Test Runner**:
   - Adjusted `windows-acceptance.ps1` to prevent native stderr output from triggering PowerShell terminating `NativeCommandError`.

### 3.2 Automated Test Results
`cargo test` completed with 15 passed, 0 failed:
```
test fingerprint_tests::fingerprint_rejects_invalid_keys ... ok
test fingerprint_tests::fingerprint_groups_bytes ... ok
test device_identity::tests::valid_proof ... ok
test device_identity::tests::reject_wrong_peer ... ok
test device_identity::tests::reject_corrupt_signature ... ok
test device_identity::tests::reject_replay_with_new_challenge ... ok
test key_protection::tests::create_reload_and_no_plaintext ... ok
test key_protection::tests::legacy_identity_migrates_without_key_change ... ok
test lan_sync::tests::key_validation ... ok
test lan_sync::tests::local_filter ... ok
test lan_sync::tests::transport_limits ... ok
test lan_sync::tests::inbound_sessions_are_bounded_and_released ... ok
test lan_sync::tests::ack_rejects_future_or_rollback ... ok
test lan_sync::tests::encrypted_roundtrip ... ok
test lan_sync::tests::duplicate_packet_rejected ... ok
```

---

## 4. Cross-Platform Sync Verification Matrix

| Test Scenario | Validation Method | Outcome |
|---|---|---|
| **Android creates note** | Sequence incremented, exported in SyncBundle v1 format | **PASS** |
| **Windows creates note** | Inserted in `sync_changes` table with monotonic seq | **PASS** |
| **Remote update applied** | Remote note saved with preserved timestamp; cursor advanced | **PASS** |
| **Tombstone propagation** | Deletion logged as tombstone; peer removes local note | **PASS** |
| **Duplicate packets** | Re-sent sequence IDs detected in seen cache and skipped | **PASS** |
| **Wrong device key** | AEAD authentication or challenge verification fails closed | **PASS** |
| **Concurrent edits** | Preserved in `sync_conflicts` table without overwriting | **PASS** |
| **Offline resilience** | Local notes remain fully editable; sync catches up on reconnection | **PASS** |

---

## 5. Deliverables Status
- `SideNote-Android-Final-Source.zip` (2.08 MB) — Verified
- `SideNote-Android.apk` (3.34 MB) — Verified
- `SideNote-Windows-Final-Source.zip` (136 KB) — Verified
- `SideNote-Cross-Platform-Protocol.md` — Verified
- `SideNote-Acceptance-Report.md` — Verified
- `SideNote-Remaining-Issues.md` — Verified
