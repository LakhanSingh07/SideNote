# SideNote Windows v0.1 — Standalone source foundation

This is a **source-only, uncompiled development build**, based on the verified Android note schema. It is not a release-ready EXE.

## Implemented in source
- Tauri v2 / React TypeScript desktop notes manager and native SQLite CRUD commands (local storage, no sign-in)
- Six pastel note colors matching Android `color` indices 0–5
- Notes, pinning, archive, search and editor in separate sticky-note windows
- Floating note window with basic color selection and a small minimized chip mode
- Compact 244×450 edge notes deck with independently scrolling previews
- Platform-local note coordinates (not included in future sync payload)

## Current limitations / NOT complete
- Rust/Windows compilation **not run**: this environment lacks Rust toolchain; npm install also did not succeed.
- Window position persistence, tray integration, launch-at-startup and keyboard shortcuts are **not implemented**.
- True mouse-hover peek behavior, polished drag/snap physics, reminder notification scheduler, resize controls and multiple-monitor handling need implementation and real Windows testing.
- Sync & Devices is intentionally disabled/not implemented. No QR pairing or P2P transport is present.
- Browser preview has a localStorage fallback; the Windows app uses SQLite.
- Icons are placeholder originals. Replace before publication.
- Android app source remains unchanged.

## Build on Windows 10/11
Install Node 20+, Rust stable with the MSVC target, Microsoft C++ Build Tools and WebView2 (as required by Tauri 2).

```powershell
npm install
npm run build
npm run tauri dev
npm run tauri build
```

Review/fix compiler and permission-schema errors before using installers. Check `src-tauri/capabilities/default.json` against the Tauri version resolved by npm and Cargo.

## Acceptance gates
1. Compile Rust + frontend, run on Windows 10 and 11.
2. Create/edit/delete/archive/pin/search multiple notes, quit/relaunch, confirm SQLite persistence.
3. Open edge deck over another app, verify it scrolls and remains compact.
4. Open multiple notes, move via header, minimize/restore without duplicate windows; verify no data loss.
5. Check high DPI, multiple monitors, focus, taskbar behavior, keyboard, accessibility and shutdown.
6. Add tray/startup/reminders and hover previews, then complete second QA round.
7. Only after standalone release quality: implement encrypted LAN pairing/sync, conflict tracking and Android data migration.

## Android note field compatibility
`id`, `title`, `body`, `color` (integer 0..5), `isChecklist` (maps to Android `checklist` JSON), `updatedAt` (epoch ms), `pinned`, `archived`, `reminderAt` (epoch ms). Checklist body is stored as raw text in the current Android version, not item records. Preserve note UUIDs in future migration.


## v0.4 desktop integration
See V0.4_HANDOFF.md for startup/tray/global shortcut implementation and runtime caveats.


## v0.8 experimental Windows LAN sync
See `V0.8_LAN_HANDOFF.md`. This source has NOT been compiled or two-PC tested.


**v0.8.1 audit:** See `V0.8.1_SECURITY_AUDIT.md` for a validation patch and release blockers.

## v1.1 pairing candidate
Read `V1.1_PAIRING_HANDSHAKE.md`. This is experimental and uncompiled; legacy UUID-only trust entries require a peer public key. Do not expose LAN synchronization to untrusted networks or ship as production-secure.
