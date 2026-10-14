# SideNote v0.6 — Android source handoff

## Implemented in source
- User-operated left/right floating sidebar tab with draggable position memory
- Local note create, edit, delete, search, colors and checklists
- Inline overlay editing and debounced autosave with save failure handling
- Pin/unpin, archive/restore and one-tap copying
- Android text/plain share-to-note intent
- JSON offline backup import and export using system document picker
- Basic opening animation, opacity configuration, edge preference
- Optional local note reminders (15 minutes, 1 hour or 24 hours) using inexact alarm and notification

## Important limitations and technical debt
- Native APK compilation and device execution not performed here: SDK/Gradle unavailable.
- Reminders may be delayed by Doze/battery policies; notification permission needs user approval on Android 13+.
- Alarms are not restored automatically after device reboot; reminder management and scheduling need full acceptance testing.
- Full swipe-to-dismiss, individually floating draggable mini-cards, full accessibility and localisation remain unimplemented.
- Backups are unencrypted JSON selected by user; do not place sensitive text in publicly accessible shared storage.
- Overlay may be hidden by Android on sensitive screens or terminated under memory/power pressure.
- No billing, online account or cross-device sync is implemented.

## Antigravity acceptance
1. Build with JDK 17 / Android SDK 35, resolve Gradle dependencies and compile errors.
2. Install/debug on Pixel and at least one OEM device; collect screen recordings.
3. Verify overlay permission refusal/regrant, app lifecycle, keyboard focus, rotate, reboot, battery saver and lock/unlock.
4. Verify draft persisted after force stop and crash; note deletion and checklist correctness.
5. Verify reminder prompts and alarms with/without notification permission and deep Doze.
6. Verify share sheet, pin/archive, search, clipboard copy, JSON import/export.
7. Run accessibility tests (TalkBack/tap targets/contrast), performance profiling and Play policy review.
8. Produce signed release only after tests pass. Do not claim ready just because source ZIP integrity passes.
