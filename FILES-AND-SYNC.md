# Files and Android/Windows sync

The current Android build is `SideNote-Android-v3.1.0.apk`. The current portable Windows build is `sidenote-windows.exe`. Use the updated builds on both devices; older builds do not understand file attachments.

## Use

1. Keep both devices on the same Wi-Fi/LAN. On Windows, open the library > Sync and show a pairing PIN. In Android Settings, pair with that PC and enter the PIN.
2. Windows: open any note, expand **Files**, choose **Add**, and select one or more files. The popup and floating editor both support attachments.
3. Android: open a note > **Files**, or choose **Files** from its floating-note menu, then **Add files**. Android's system share sheet can also send any file type or multiple files to SideNote, creating a note.
4. Files travel with the note. Both devices show transfer/readiness state. Android's **Retry sync** and Windows's **Request sync** can retry a paused connection. Automatic retries run while the Android sync process is alive; reopening SideNote resumes saved partial transfers after a restart.
5. Use **Open**, **Save as**, or Android **Share** after a file arrives. Opening requires a suitable installed app for that format. Received files are never opened automatically.

Files are copied into private app storage, so later changes to the originally selected file do not silently alter the attachment. To replace it, remove that attachment from the note and add the updated file. Removing a file unlinks it from that note; it does not delete a copy already saved outside SideNote.

## Transfer behavior

All MIME types are accepted. File contents are streamed separately from note text in bounded 512 KiB raw chunks, with 64-bit file sizes, persisted partial downloads, independent note upload/download cursors and SHA-256 verification before publication. File data is never embedded in note Markdown or loaded as a whole file into memory for transfer. There is no user-facing file-size cap; real limits are available storage, filesystem/provider limits and network speed. Transfer time cannot be guaranteed for an arbitrary file size.

A normal note edit during a large Android transfer queues note sync and lets the file resume afterward. Android does not echo imported notes as fresh local edits. Windows relays accepted changes once so newly paired devices receive the complete current state, including deletions. Genuine concurrent edits remain conflicts instead of silently discarding one copy. Sequential edits carry their prior revision so ordinary back-and-forth edits work without false conflicts. Legacy notes missing from change logs and restored JSON backups join sync.

Pairing authenticates file requests and scopes them to an attachment on an existing note. Content hashes are storage keys; remote filenames are not accepted as filesystem paths. This LAN protocol does not provide TLS/end-to-end transport encryption.

JSON note backup contains attachment metadata, not the attached binary contents. Keep the paired copy or save important files separately using Save as. Attachment bytes remain in each app's private storage and are transferred through LAN sync.

## Verification

See `acceptance-evidence/files-sync` and `SideNote-Windows-v1.3.2-Acceptance-Kit/SideNote-Windows-v1.3.1/acceptance-evidence/files-sync`.

- Android full suite: 40 executed tests passed; the Windows integration test is opt-in in a normal run.
- Windows Rust suite: 22 tests passed.
- Production Android client under Robolectric against the packaged Windows server: 12 MiB arbitrary binary files transferred in each direction, both resumed from existing partial data, both SHA-256 hashes matched, and note edits/deletion passed. Existing Windows notes were unchanged. This is not a physical-phone or Wi-Fi speed test.
- Windows attachment UI: multi-file selection, zero-byte and unknown-type binary files, readiness buttons, removal and note-body preservation passed. Existing rich-editor and dock/drag regression checks passed.
- Physical OnePlus 10T (CPH2413), Android 15: updated APK installed, paired by PIN, 12 MiB arbitrary binary files transferred in both directions over Wi-Fi with independent SHA-256 checks on both devices. Note creation, edits and deletion passed in both directions. Android system file picking and Save as of a received file passed. Pairing, historical-deletion replay, attachment screen insets and unnecessary list rebuilding were fixed during this run.
- Temporary physical-test notes/files were removed; all 17 original note IDs remain and both devices agree on titles, bodies and attachments. One existing checklist body changed during the session; its current value was preserved on both devices. The phone remains paired. This was not a throughput benchmark or an exhaustive background/power-management test. Evidence: `acceptance-evidence/files-sync/physical-device/results.json`.
