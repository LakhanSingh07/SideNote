# SideNote compact experience — Antigravity acceptance

Use this source project, not any prior pre-redesign ZIP. Compile and fix any build errors first. Do not substitute generic screenshots or mock data for actual app rendering.

## Actual phone/simulator screenshot requirements

Capture a consistent 360–420dp wide Android device in each state:

1. **Handle over Chrome or YouTube:** visible painted handle ~11dp wide, 50dp tall; no persistent block.
2. **Peek over Chrome or YouTube:** fixed width ~236dp or less on small devices, content-sized window under ~35% screen area with 3 short note previews. Underlying app is clearly visible. With one note, panel must shrink.
3. **One floating note over another app:** compact card, default 258dp wide, short text auto height; no giant action toolbar; no dim overlay.
4. **Minimized note chip:** approx. 150dp wide; tap restores without switching apps.
5. **Two floating notes:** independent drag and close, no offscreen controls and no accidental interactions.
6. **Checklist:** check item in floating card, reopen and confirm persisted state.
7. **Editing with keyboard:** create/edit with no keyboard-clipped controls; autosave survives process restart.
8. **Main Notes, Archive and Settings:** no mixed Settings + Notes endless page, readable light/dark themes.
9. **Onboarding:** short, straightforward permission guidance, normal notes usable if permission denied.

## Must test

- Permission denied/granted/revoked; overlay suppression on protected screens.
- Edge swipe vs system gesture back; right/left side; display scaling and accessibility font size 1.4×.
- Short and very long notes; change size/opacity from overflow; drag toward screen corners and rotate device.
- Multiple rapid minimize/restore taps, open/close during app navigation, background/foreground and lock/unlock.
- Unicode, multiline text, checklist data persistence; valid/corrupt backup restore, notes preservation on failures.
- Reminder notifications, app reboot, background restrictions, API 26/30/33/35 and actual Android handset.
- Keyboard stability, TalkBack accessibility, 48dp target expectations, 60 FPS aim (measure, don't assume).

## Release gates

Deliver updated source, successful Gradle logs, installable APK, actual screenshots, test video/recording if possible, reproducible bug list and PASS/PARTIAL/FAIL verdict per scenario. **A build PASS is not a UX/runtime PASS.**
