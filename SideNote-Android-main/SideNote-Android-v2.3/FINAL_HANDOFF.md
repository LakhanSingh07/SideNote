# SideNote v0.7 — Design & implementation handoff

## Reference observations
- Floating Notes (424 APPS): always-accessible edge icons, multi-color sticky notes, transparency, and checklists. Its Google Play reviews also expose long-note editor size/scroll issues and overlays disappearing; those are explicit acceptance scenarios.
- StickyHover: movable cards, compact bubble behavior and inline editing.
- Peeky Notes: slim edge tab and instant expansion concept.

All UI is original code, colors and basic platform widgets. No competitor icons, screenshots, copyrighted assets, or source copied.

## Source features included
- Editable offline notes, checklists, pin/archive/search, swipe edge sidebar with left/right docking and drag position memory.
- Color sticky cards, inline floating editor and 450ms autosave, local reminders, share text capture, copy, JSON backup/import.
- v0.7 adds a draggable independent 280dp floating note card opened from the sidebar, editor shortcut, dismiss action, refreshed microcopy and buttons.

## Current gaps (must NOT claim complete)
- No proven Android compilation or device validation.
- Floating mini card shows a snapshot; after external edits reopen to refresh. It supports ONE active card at a time, not multiple concurrent cards.
- No true animation choreography, drag resize, reminder recurrence, snippets manager, widget, full dark theme, paid tier, cloud sync, or verified Play compliance.
- Android system can suppress overlays on security-sensitive screens and stop background processes; don't promise permanence.
- Auto-save failure on system-initiated service termination may still require recovery hardening.

## Antigravity validation gate
1. Compile with JDK 17 and Gradle/Android SDK matching build scripts. Fix errors before visual tasks.
2. Verify on Android 13/14/15/16, Samsung/Xiaomi/OnePlus/Pixel if accessible.
3. Check overlay permission, notification permission, foreground-service behavior, keyboard focus and resize.
4. End-to-end tests: create/edit/relaunch, pin/archive, checklist, search, share from Chrome, copy, JSON export+import, notification reminder.
5. From edge tab launch sidebar, tap Float, drag note, close/restore, edit long note with keyboard, rotate, lock/unlock, deny permissions and verify saved content.
6. Report device/OS, repro steps, screenshots and logs. Produce signed release build only after passing critical gates.
