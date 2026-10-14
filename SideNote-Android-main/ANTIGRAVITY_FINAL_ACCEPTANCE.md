# SideNote — Final Android Acceptance and Repair Brief

## Input
Use the bundled **SideNote-Android-v2.3** source as the only baseline. Do not restart, replace with mock UI, or lose functionality. The ZIP has not been compilation- or device-verified. Do not assume prior 'source implemented' claims mean features work.

## Mission
Deliver a genuinely compilable, installable, thoroughly tested Android app with premium UI/UX. Treat the current source as an untrusted prototype until verified. Fix ALL compile, runtime, overlay, data, lifecycle, UX, and accessibility defects identified, and do not manufacture PASS reports.

## Mandatory execution order
1. Inspect Gradle settings, dependencies, manifest, source tree and assets. Repair Gradle wrapper/build scripts as needed; pin reproducible versions. Log toolchain versions.
2. Run clean Gradle debug build. Resolve every compile error. Produce debug APK and build log, or explicitly document external/environmental blockers.
3. Add and run automated tests for note parsing, corrupt data behavior, backup import/export, autosave persistence, note IDs, reminder scheduling/cancel, preference persistence, and multi-window card state. Record exact pass/fail.
4. Run Android emulator/connected-device functional journeys with screenshots or screen recording. Don't claim hardware testing when only emulator is available.
5. Match premium approved product intent: quiet minimalist notes workspace, slim edge handle, original high-contrast light/dark visuals, consistent spacing/type, fast non-blocking overlays. Never copy competitor assets or exact UI. Capture screenshot evidence for onboarding, main notes, checklist, sidebar, floating card, minimized card, multiple cards, editor+keyboard, settings, archive and reminders.
6. Fix actual defects and repeat tests. No feature should be marked PASS without traceable evidence.

## Critical test matrix
- First launch and permission denial/regrant; app remains usable without overlay permission.
- Start/stop overlay service; notification, Android 13+ notification permission, Android 14+ foreground service constraints. Respect platform restrictions.
- Left/right edge handle, drag, swipe, snap, touch target and rotation/window insets.
- Open at least 3 independent cards; drag, edit, minimize, restore, resize, opacity, close and reopen without state leak or wrong-note updates.
- Edit short and very long text with soft keyboard; no buttons behind keyboard or unintended dismissal; save before navigating.
- Rapid typing and instant close; background/relaunch; process death; lock/unlock; force-stop (document OS limitations). Note *data* must survive, even where overlay visibility is restricted by OS.
- Search, pin/unpin, archive/restore, checklist completion, create/delete, reminders, cancel on delete.
- Share text from another Android app; copy snippet; JSON backup export, import, malformed input rejection, duplicate IDs, very large payload handling.
- Light, dark, system theme; orientation changes and compact screen sizes; TalkBack labels and contrast; no button clipping.
- Runtime permissions, privacy and Play-policy checks; crashes/ANRs/leaks, startup time, scrolling responsiveness.

## Hard acceptance gates
- `assembleDebug` succeeds with exact command and logs.
- Installation and launch verified on emulator or device; identify Android version and device.
- No critical reproducible note data loss, wrong-note mutation, crash, stuck overlay, blocked interaction, or corrupt-import overwrite.
- Main journeys demonstrated with evidence, not assertions.
- Release blockers listed separately from cosmetic issues.
- Pixel-accurate match to previously approved concept is not assumed: compare actual screenshots, record gaps, and fix major ones.

## Deliverables
1. Single clean Android Studio source ZIP including Gradle wrapper if applicable.
2. Installable APK (debug for acceptance; signed release APK/AAB only if signing is legitimately configured).
3. `TEST_REPORT.md` with exact steps, results, device/os matrix, logs and screenshot paths.
4. `BUGS_FIXED.md` describing fixes and remaining known issues.
5. `SCREENSHOTS/` of each required app state.
6. `RELEASE_GATE.md`: PASS / PARTIAL / FAIL with explanation and outstanding blockers.

Never claim Play Store-ready or 100% working without all critical gates satisfied. If emulator/device unavailable, report that as BLOCKED and do all feasible build/unit/static checks.
