# SideNote v0.4 — validation status

**Not an APK and not device-tested.** The working environment does not contain Android SDK or Gradle.

## Verified in this environment
- ZIP paths and CRC integrity
- Android manifest is valid XML
- project package references and source patch checks
- CI workflow has debug build and APK artifact upload stages

## Not verified
- Gradle compile and runtime
- Samsung/Pixel/OnePlus overlay focus, keyboard, rotation, screen lock, battery-saving behaviour
- Play Store foreground service policy eligibility

## Next action
Upload the extracted project to a GitHub repository and run **Actions → Android debug build → Run workflow**. Download `sidenote-debug-apk` from the completed run if it succeeds. Address any compiler diagnostics before device testing.

## Device acceptance
1. Grant overlay permission and start sidebar.
2. Open from edge, create note, type and immediately close. Reopen and verify content.
3. Lock and unlock; check handle and notification status.
4. Test keyboard opening and long text scroll in a browser overlaid by SideNote.
5. Toggle checklist while switching apps; verify saved state.
6. Test overlay restricted screens and confirm app fails gracefully.
7. Force-stop app; confirm notes remain saved after relaunch.
8. Test accessibility, battery optimizations and launch on Android 13–16 devices.
