# SideNote v0.3 — Sidebar usability source update

## Added
- Swipe inward from the edge handle to open the notes panel, tap also opens it.
- Swipe outward to dock the handle to the opposite side; dock preference persists.
- Drag vertically to reposition the handle; vertical position persists across service restarts.
- Search notes by title and body with a floating-panel search field.
- Empty search result state and accessible search/handle descriptions.
- Version code 3 / version name 0.3.0.

## Existing
- Inline floating note editor, 450ms debounced save and close-time flush.
- On-device notes, checklists, user-started foreground overlay.

## Limitations
- Not compiled: Android SDK and Gradle are not installed here. An APK is NOT included.
- The overlay and IME behavior must be tested on physical Android devices.
- Floating panel is not yet animated; no resize/opacity slider or calculator yet.
- Drag changes y only while dragging; left/right docking uses a horizontal outward swipe.
- Android system can restrict overlays, especially protected screens; note content stays locally stored.

## Hardware acceptance checks
1. Open with tap and inward swipe on both edges.
2. Dock with outward swipe and verify side choice survives stopping/restarting the service.
3. Drag edge handle, restart service, verify vertical position restored.
4. Search with soft keyboard, filter and clear query.
5. Edit and immediately close long note; verify final text survives restart.
6. Rotate, lock/unlock, revoke permission, background/foreground, low-memory recovery.
7. Check Samsung, Xiaomi, OnePlus and Pixel with multiple Android versions.
8. Verify special-use foreground-service Play policy before public release.
