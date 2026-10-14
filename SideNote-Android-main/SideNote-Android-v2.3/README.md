# SideNote — Premium Android source implementation

This is the **actual native Kotlin Android project** based on v2.4 Compact Peek and the approved warm-neutral/pastel UI reference, not a browser prototype. Includes Android source, Gradle configuration, overlay foreground service, SQLite-free offline NotesStore, reminder scheduling, backup, editor and automated-build workflow.

## App UI implementation

- Notes home: editorial title, warm ivory canvas, persistent bottom navigation, gold-accented design, slim search, underline tabs (`All`, `Pinned`, `To-dos`), pastel note cards, floating create button, calm empty state. No marketing hero and no developer-demo notes.
- Archive: dedicated destination, open/archive/restore via the card menu.
- Settings: separate organized destination with theme selection, overlay permission/action, docking, panel opacity, JSON export/import and help.
- Onboarding: two-stage illustrated introduction and optional overlay permission.
- Editor: custom rounded bottom-sheet dialog with two native text inputs, checklist option, pastel swatches, 500ms debounced autosave, save-error protection, delete confirmation.
- Edge overlay: 11dp visible handle, 236dp content-sized Peek Deck with maximum three previews, compact draggable note cards, minimized chip, independent note windows, checklist toggles, overflow actions and opacity/size options.
- Theme tokens: original cream/yellow/pastel palette and a dark counterpart shared between app and overlay; no network fonts or third-party design assets.

## Critical caveat

**This ZIP contains a source implementation, NOT a proven compiled or production-ready APK.** Here, we could only run structural/static checks and XML parsing. Android build, screenshot comparisons, keyboard/overlay interaction, accessibility, long-term data retention and phone testing remain unverified. Do not claim these passed until Antigravity/Android Studio executes them.

## Android Studio / Antigravity

1. Unzip; open the **SideNote-Final-Android-Source** directory as an Android project.
2. Use JDK 17, Android SDK 35 and Gradle 8.11.1; the repo has build configuration but no bundled Gradle wrapper. If local Gradle isn't installed, run the included GitHub Actions workflow (`.github/workflows/android-build.yml`) or let your Android environment generate a wrapper.
3. Build `:app:assembleDebug`. Correct compile errors if any and retain error logs.
4. Install on an Android 10–16 device/emulator where supported, grant overlay permission explicitly, and test the four key states: handle, Peek Deck, floating card and minimized chip over Chrome or another host app.
5. Follow `FINAL_ACCEPTANCE.md`; collect screen recordings and compare with the reference design. Always validate data migration using existing old-release notes.

The local `scripts/source_contract_check.py` checks **source markers only**. It is intentionally not an Android compile or device test.
