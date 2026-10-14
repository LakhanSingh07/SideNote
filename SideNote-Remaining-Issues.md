# SideNote Remaining Issues & Future Architecture Recommendations

**Date**: 2026-10-14  
**Status**: Both Android and Windows core applications compile, pass all acceptance tests, and have functional synchronization data stores.

---

## 1. Resolved Issues in This Release
1. **Windows Frontend Build**: Fixed missing TypeScript declarations and window monitor API usage in `src/main.tsx`.
2. **Tauri v2 Permissions Schema**: Removed obsolete `core:window:allow-on-moved` permission which previously prevented Tauri capability compilation.
3. **Rust DPAPI Keystore**: Resolved Windows API `LocalFree` binding from `windows_sys::Win32::Foundation::LocalFree` for secure device identity encryption.
4. **Rust Lan Sync Authenticator**: Implemented missing `fresh_nonce()` cryptographic challenge generator and resolved SQLite query statement lifetime issues in `src/lib.rs`.
5. **Android Syntax & Protocol Invariants**: Fixed Kotlin escape syntax in `SyncStore.kt`, integrated `saveDirect` in `NotesStore.kt` to preserve remote timestamps, and implemented full conflict preservation.
6. **Robolectric & Cargo Test Suites**: All 15 Windows Rust tests and all Android acceptance tests pass with 100% success.

---

## 2. Environment & Network Considerations

### 2.1 Wi-Fi AP Isolation
- **Symptom**: In corporate or hotel Wi-Fi networks with "Access Point Isolation" enabled, devices cannot establish direct TCP connections to peers on the local subnet.
- **Remediation**: The protocol includes a direct IP fallback input in the **Sync & Devices** screen. Alternatively, activating a mobile hotspot allows direct peer-to-peer communication.

### 2.2 WiX / NSIS Toolchain for Custom MSI/EXE Bundling
- **Observation**: Tauri uses NSIS or WiX to generate `.msi` and `.exe` installers. On systems without NSIS/WiX installed globally, `cargo build --release` compiles the standalone Windows executable `sidenote-windows.exe`.
- **Recommendation**: To package into an MSI installer on CI/CD, ensure WiX Toolset v3.14 or NSIS 3.09+ is present in the build pipeline.

### 2.3 Android WorkManager Periodic Background Sync
- **Current State**: Sync operations run when the app is active or when the floating sidebar overlay service is running.
- **Future Enhancement**: An optional Android `CoroutineWorker` scheduled via `WorkManager` (constrained to unmetered network + charging) can periodically poll trusted desktop peers in the background.
