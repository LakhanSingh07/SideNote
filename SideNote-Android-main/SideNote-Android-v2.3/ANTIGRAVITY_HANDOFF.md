# SideNote v0.8 — build and QA handoff

Open the `SideNote-Android` folder as an Android Studio Gradle project.

1. Restore Android SDK platform 35, AGP 8.9.2, Kotlin 2.1.20, Java 17, Gradle wrapper (not bundled). Do not assume successful compilation.
2. Build `assembleDebug`; fix all compiler errors and missing resources. Capture complete build logs.
3. Install on at least one Android 14+ physical device and one emulator.
4. Exercise: overlay permission flow; start/stop foreground service; side-edge tapping/swipe/docking; scroll/search; edit/title/checklist; debounce save; failed-save handling; floating note drag; minimize-to-chip; chip restore; dismiss; pin/unpin; archive; share; import/export; reminders; opacity control.
5. Test keyboard overlap, long-note scrolling, 200-note performance, lock/unlock, app process kill, notification permission denied, orientation and TalkBack.
6. Do not release or market as production-ready until failures are fixed and tests documented.

**Known limitations**: only one independent floating card at a time; overlay can be restricted by Android; no verified full theme switcher or comprehensive dark-mode styling; no signed APK/AAB; no Play Billing; no verified build.
