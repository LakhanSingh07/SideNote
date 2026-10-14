# SideNote Android v0.2 — Phase 2

## Implemented in source
- In-overlay note editing: title, multiline body and checklist-mode toggle inside a focusable floating window instead of launching a separate Activity.
- Debounced local autosave (450 ms after input stops), explicit flush on Done, back, overlay close and service teardown.
- Save status indicator and inline keyboard-capable overlay window.
- Version bumped to 0.2.0 (versionCode 2).
- Existing v0.1 edge handle, notes list, offline SharedPreferences persistence and main-app editor retained.

## Incomplete / known constraints
- No APK/AAB; native Gradle build not run in this environment (no verified Android SDK/Gradle toolchain).
- Floating window IME and resize behavior must be physically tested on Android 10–16 and OEM variants.
- `SharedPreferences.commit()` is synchronous, suitable only for a small MVP. Migrate to Room, with repository-level background writes, ahead of larger releases.
- No edge-swipe gesture (tab supports tap and vertical drag), no animated transitions, no true close-time undo, search, cloud sync or advanced snippet tools yet.
- Android OS/OEM can hide overlays in protected or restricted screens. Do not claim permanent overlays across all apps.

## Acceptance criteria (must be verified on hardware)
1. Grant overlay access and start service using app; tab appears with foreground notification.
2. Tap tab; create note; edit title and 1000+ character body without leaving currently visible app.
3. Check 'Checklist', wait one second, close panel; reopen; content and mode persist.
4. Type rapidly; immediately tap Done; confirm last keystroke persists; repeat 20 times.
5. Lock/unlock and switch apps; existing notes remain saved even if OEM stops overlay service.
6. Open keyboard, scroll long note, rotate device, attempt close/reopen.
7. Revoke overlay permission and restart app; observe safe failure without crash.
8. Stop sidebar in app; check that edge handle and notification disappear.
9. Test narrow display, font scaling 200%, accessibility TalkBack and battery saver.

## Build
Open SideNote-Android in Android Studio with Android SDK 35 and JDK 17, sync Gradle and run on real phone. Build is not independently verified.
