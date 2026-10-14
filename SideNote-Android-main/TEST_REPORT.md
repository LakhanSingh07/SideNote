# SideNote v2.3 — Test Report & Verification Matrix

**Execution Date:** 2026-10-12  
**Package:** `com.sidedeck.notes`  
**Version:** 2.3.0 (`versionCode 20300`)  
**Target Environment:**  
- **Device / Runner:** Android Virtual Device `CripCrop_API34` (`emulator-5554`)
- **Android OS:** Android 14 (API level 34, `UPSIDE_DOWN_CAKE`), `x86_64`
- **Display:** 1080 × 2400 px, 420 dpi
- **Java / JDK:** OpenJDK 21.0.10 (Android Studio JBR: `C:\Program Files\Android\Android Studio\jbr`)
- **Build System:** Gradle 8.11.1 + Android Gradle Plugin 8.7.2 + Kotlin 2.0.21
- **Android SDK:** `D:\Android\Sdk`, compileSdk 35, buildToolsVersion 35.0.0

---

## 1. Automated Unit & Acceptance Test Suite

Automated unit and integration acceptance tests were implemented in [`SideNoteAcceptanceTest.kt`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SideNote-Android-v2.3/app/src/test/java/com/sidedeck/notes/SideNoteAcceptanceTest.kt) running on Robolectric 4.14.1 with Android Test Core.

**Execution Command:**
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --info
```

**Result:** `BUILD SUCCESSFUL` (12 tests, 0 failures, 0 errors, 0 skipped, Execution Time: 13.311s)

| Test Method | Category | Verified Behavior | Status |
|---|---|---|---|
| `testNoteParsingAndFields` | Data Integrity | Validates JSON and model serialization, default IDs, timestamps, pin status, and archive state. | **PASS** |
| `testCorruptNoteEntryPreservesData` | Fault Resilience | Verifies that unparseable/corrupt individual JSON entries in SharedPreferences are gracefully skipped without crashing or wiping intact notes. | **PASS** |
| `testCorruptDatabaseSyntaxPreservesData` | Fault Resilience | Verifies that corrupt JSON syntax in storage does not discard existing notes or cause uncaught exceptions. | **PASS** |
| `testBackupExportAndImportRoundTrip` | Backup & Migration | Full round-trip export of multi-note datasets to schema JSON and clean restoration. | **PASS** |
| `testBackupImportRejectsMissingAndNullFields` | Data Validation | Rejects invalid JSON schemas missing mandatory keys or carrying null fields. | **PASS** |
| `testBackupImportRejectsDuplicateIds` | Data Validation | Rejects backup archives containing duplicate note IDs to prevent data collision. | **PASS** |
| `testOversizedNoteInStoreThrows` | Boundary Checks | Rejects notes exceeding MAX_BODY_LENGTH (60,000 chars) to prevent binder buffer overflow and memory degradation. | **PASS** |
| `testAutosaveUpdateAndDeletion` | Store Lifecycle | Tests atomic save, body modification, and clean note removal. | **PASS** |
| `testNoteOrderingPinnedAndRecency` | Business Logic | Validates that pinned notes strictly rank higher than unpinned notes, sorted secondarily by update timestamp descending. | **PASS** |
| `testReminderSchedulingAndCancel` | Alarms & System | Verifies `AlarmManager` exact/inexact intent scheduling and cancellation upon note deletion. | **PASS** |
| `testOverlayPreferencesPersistence` | Preferences | Tests persistent storage for opacity (65-100%), dock edge (left/right), and edge handle vertical coordinates. | **PASS** |
| `testMultiWindowFloatingCardStateIndependence` | Multi-Window UI | Verifies independent per-card state management (size 0..2, opacity 75..100%, minimized flag) across multiple concurrent cards. | **PASS** |

---

## 2. Interactive Emulator Test Matrix

All test cases were executed directly against package `com.sidedeck.notes` running on `emulator-5554` (API 34).

### Matrix Results

| ID | Test Scenario | Execution Steps & Verification | Status | Screenshot Evidence |
|---|---|---|---|---|
| **M-01** | First Launch & Onboarding | Launched app cold. Verified 3-step feature onboarding card ("A quiet place for what matters", "Always within reach", "Private by design"). Tested permission button states. Regranted permissions. App launches main screen without overlay permission if denied. | **PASS** | [`01_onboarding.png`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SCREENSHOTS/01_onboarding.png) |
| **M-02** | Main Notes Workspace & Tab Filtering | Verified brand header ("SideNote"), search bar, hero card, and filter chips ("Active", "Pinned", "Archived"). Verified tab chips no longer push siblings off-screen (`WRAP_CONTENT`). Notes render correctly with title, preview, and tags. | **PASS** | [`02_main_notes_tabs.png`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SCREENSHOTS/02_main_notes_tabs.png)<br>[`02_main_notes.png`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SCREENSHOTS/02_main_notes.png) |
| **M-03** | Checklist Note Card Rendering | Verified checklist note formatting. Unchecked items display `☐` with regular text; checked items display `☑` with strikethrough styling and muted color. Tapping toggle updates state in atomic store. | **PASS** | [`03_checklist.png`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SCREENSHOTS/03_checklist.png) |
| **M-04** | Edge Handle Overlay on System Home Screen | Started `OverlayService` with `SYSTEM_ALERT_WINDOW` permission. Verified slim violet pill edge handle (`⋮`) docks cleanly to the left edge of Android home screen. Swiping/tapping opens the full sidebar overlay. | **PASS** | [`04_home_with_handle.png`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SCREENSHOTS/04_home_with_handle.png) |
| **M-05** | Floating Overlay Sidebar | Verified floating sidebar panel over Android home screen. Verified search input is pinned at the top above scroll view. Verified notes list, `↗ Float`, `⧉ Copy`, and `Pin` actions work as expected. | **PASS** | [`04_sidebar.png`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SCREENSHOTS/04_sidebar.png) |
| **M-06** | Standalone Floating Note Window | Dispatched floating note window from sidebar. Verified drag grip `⠿`, single-line truncated title, compact control buttons (`✎`, `◱`, `◐`, `−`, `×`), accent color bar, and scrollable body. | **PASS** | [`05_floating_card.png`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SCREENSHOTS/05_floating_card.png) |
| **M-07** | Minimized Floating Note Chip | Tapped minimize (`−`) on floating note. Window smoothly morphs into compact pill chip `⠿ ✦ Mission B... ↗` docked on screen. Window remains touch-responsive and drag-movable. | **PASS** | [`06_minimized_card.png`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SCREENSHOTS/06_minimized_card.png) |
| **M-08** | Concurrent Multi-Card Floating State | Floated two independent notes ("Mission Briefing" and "Product Architecture") concurrently on the Android home screen. Repositioned each independently via touch drag. Restored minimized chip. Both cards render and function without state leakage. | **PASS** | [`07_multiple_cards.png`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SCREENSHOTS/07_multiple_cards.png) |
| **M-09** | Note Editor & Soft Keyboard Insets | Opened note editor modal. Focused body edit text to trigger soft keyboard. Verified editor dialog resizes cleanly using `adjustResize` and window insets; buttons ("Cancel", "Save Note") remain fully visible and clickable above keyboard. | **PASS** | [`08_editor_keyboard.png`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SCREENSHOTS/08_editor_keyboard.png) |
| **M-10** | Settings, Backup & Personalization | Navigated to Settings. Verified high-contrast dark theme, dock side selector (Left / Right), panel opacity slider, backup export, and backup import buttons. Tested backup export and import functionality. | **PASS** | [`09_settings.png`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SCREENSHOTS/09_settings.png)<br>[`09_settings_dark.png`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SCREENSHOTS/09_settings_dark.png) |
| **M-11** | Archive & Note Restoration | Navigated to Archive tab. Verified archived notes list. Tapped restore action to unarchive note back to active list. Verified empty state when no notes archived. | **PASS** | [`10_archive.png`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SCREENSHOTS/10_archive.png) |
| **M-12** | Reminder Scheduling Modal | Opened reminder picker dialog for note. Selected preset reminder interval (1 hour / Tomorrow / Next week). Verified exact alarm registration in system and notification dispatch readiness. | **PASS** | [`11_reminders.png`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SCREENSHOTS/11_reminders.png) |

---

## 3. Platform & Lifecycle Invariant Verification

1. **Process Death & Reboot Resilience**:
   - Note data is stored atomically using atomic JSON persistence in SharedPreferences.
   - Force-stopping the app (`am force-stop com.sidedeck.notes`) and restarting verified 100% data retention.
2. **Overlay Permission Edge Case**:
   - If overlay permission (`SYSTEM_ALERT_WINDOW`) is denied or revoked, the app gracefully hides the overlay toggle with an informative prompt and continues to function as a standalone notes app without crashing.
3. **Android 14 Foreground Service Compliance**:
   - `OverlayService` is declared in `AndroidManifest.xml` with `android:foregroundServiceType="specialUse"` and appropriate notification channel (`SideDeck Note Overlays`).
4. **Window Insets & Soft Keyboard**:
   - Note editor handles `WindowInsetsCompat` and soft keyboard visibility via `SOFT_INPUT_ADJUST_RESIZE`, preventing UI clipping or trapped input.
