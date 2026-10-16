# Unified note editing verification

- `npm.cmd run build`: TypeScript and production frontend build passed.
- `node scripts/editor-regression.mjs`: 24 shared-editor interaction checks passed in the Windows WebView. Covers old Markdown, task toggling and reload, combined formatting, headings, lists, links and editing existing links, undo/redo, Ctrl+B, timestamps and mixed content.
- `node scripts/editor-native-regression.mjs`: 7 integration checks passed against the packaged app and SQLite. A dedicated temporary note was created and removed; existing note contents were not edited. Checks popup persistence, floating editor handoff, rapid title/color/body changes, combined formatting and reopening.
- `node scripts/overlay-regression.mjs`: hover layout, arrows/wheel, fixed drawing canvas and real mouse reorder in both directions passed. One initial real mouse run missed the upward destination; the diagnostic rerun passed without an application change.
- `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/check-overlay-region.ps1`: native click-through, note input and fixed canvas dragging passed.
- `cargo build --release --offline --features tauri/custom-protocol --manifest-path src-tauri/Cargo.toml`: Windows executable built successfully. Existing Rust unused-code warnings remain.

Formatting stays in Markdown for note storage and sync. Legacy checkbox lines and HTML underline round-trip through the editor. Voice typing remains dependent on Windows/WebView speech support; microphone capture was not verified by these automated checks.
