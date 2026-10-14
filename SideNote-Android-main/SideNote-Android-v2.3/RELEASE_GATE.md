# SideNote v0.4.1 — build and release gate

## What changed
- Side panel now opens on the edge selected by the user rather than always from the right.
- Checklist save errors surface a message and revert the checkbox.
- Overlay panel height avoids exceeding the available display height on small displays.
- GitHub Actions verifies APK existence and prints SHA-256 after successful compilation.

## Verification status
- Static checks only in this container. No Android SDK, Gradle, emulator or device available.
- **NOT VERIFIED:** Kotlin compilation, manifest merging, actual overlay behavior, keyboard focus, battery restrictions, APK install.

## Run the actual build
1. Create a GitHub repository and upload this project's files (including `.github/workflows/android-build.yml`), without nesting the `SideNote-Android` folder.
2. Open Actions → Android debug build → Run workflow.
3. If the job succeeds, download the `sidenote-debug-apk` build artifact. The CI log contains SHA-256.
4. If it fails, use the complete error log to fix the compile problem; do not publish an untested binary.

## Mandatory phone acceptance checks
1. Android 10, 13, 14, 15 if available, especially Samsung and Xiaomi.
2. Deny and grant overlay permission; stop/restart from app.
3. Change dock edge, swipe handle and restart service.
4. Edit and close notes with soft keyboard displayed; rotate and lock/unlock.
5. Power-save mode and process termination; content should remain stored even if overlay is stopped.
6. Checklists, search and failed-save paths; verify notes persist after force-stop.
7. Confirm Play policy justification for foreground service special-use type before release.
