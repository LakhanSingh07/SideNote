# SideNote v2.3 — Bug Catalogue & Resolution Report

This document records all defects, architectural oversights, and layout issues identified in the baseline source `SideNote-Android-v2.3`, together with the implemented fixes and verification status.

---

## 1. Resolved Defects

### Bug 1: Compilation Failure in `Ui.kt` (`Unresolved reference: minHeight`)
- **Severity:** Blocker (Build)
- **Location:** [`SideNote-Android-v2.3/app/src/main/java/com/sidedeck/notes/Ui.kt`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SideNote-Android-v2.3/app/src/main/java/com/sidedeck/notes/Ui.kt#L16)
- **Description:** Extension function `fun View.minH(dp: Int)` attempted to assign to `this.minHeight = dp(context, dp)`. In the Android `View` class, minimum height is accessed via `setMinimumHeight(int)` or `minimumHeight`, whereas `minHeight` is not a writable property on general `View`. This prevented the project from compiling with `Unresolved reference: minHeight`.
- **Resolution:** Updated property assignment to `this.minimumHeight = dp(context, dp)`.
- **Verification:** Clean compilation via `./gradlew.bat compileDebugKotlin` and `assembleDebug`.

---

### Bug 2: Corrupted SDK 35 Mockable JAR in Build Environment
- **Severity:** Blocker (Toolchain)
- **Location:** Android SDK Platform 35 (`D:\Android\Sdk\platforms\android-35\android.jar`)
- **Description:** The system SDK 35 `android.jar` archive had invalid zip directory metadata, causing AGP 8.7.2's `MockableJarTransform` task to throw `MissingValueException` during Gradle provider resolution.
- **Resolution:** Removed and cleanly reinstalled `platforms;android-35` via Android CLI (`android sdk remove platforms;android-35` followed by `android sdk install platforms;android-35`).
- **Verification:** AGP `createMockableJar` and `compileDebugJavaWithJavac` succeeded across repeated clean builds.

---

### Bug 3: Lack of Automated Regression & Acceptance Test Harness
- **Severity:** High (Acceptance & Data Safety)
- **Location:** `app/build.gradle.kts`
- **Description:** The baseline codebase had zero unit or integration test dependencies and no test cases verifying storage integrity, atomic updates, or crash-free handling of corrupted JSON payloads.
- **Resolution:** Added `junit:junit:4.13.2`, `org.robolectric:robolectric:4.14.1`, and `androidx.test:core:1.6.1` to `app/build.gradle.kts`. Authored a 12-test comprehensive acceptance suite in [`SideNoteAcceptanceTest.kt`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SideNote-Android-v2.3/app/src/test/java/com/sidedeck/notes/SideNoteAcceptanceTest.kt).
- **Verification:** 12/12 tests passing 100% in 13.3s via `./gradlew.bat testDebugUnitTest`.

---

### Bug 4: Main Workspace Filter Tabs Clipped Off-Screen
- **Severity:** Critical (UI / Usability)
- **Location:** [`SideNote-Android-v2.3/app/src/main/java/com/sidedeck/notes/MainActivity.kt`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SideNote-Android-v2.3/app/src/main/java/com/sidedeck/notes/MainActivity.kt#L107)
- **Description:** In the tab bar containing "Active", "Pinned", and "Archived" filter chips, chip views were attached to the horizontal `LinearLayout` using `Ui.lp(this)` which defaulted to `MATCH_PARENT` width with weight 0. Consequently, the first chip ("Active") expanded across the entire display width, pushing "Pinned" and "Archived" completely off-screen.
- **Resolution:** Changed chip layout parameters to `Ui.lp(this, -2, -2)` (`WRAP_CONTENT, WRAP_CONTENT`) with 6dp horizontal margins.
- **Verification:** Verified all 3 tabs render side-by-side on device (`02_main_notes_tabs.png`).

---

### Bug 5: Checklist Note Feed Cards Displayed Unformatted Raw Markdown
- **Severity:** Medium (UX / Visuals)
- **Location:** [`SideNote-Android-v2.3/app/src/main/java/com/sidedeck/notes/MainActivity.kt`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SideNote-Android-v2.3/app/src/main/java/com/sidedeck/notes/MainActivity.kt#L140)
- **Description:** Notes containing checklist items were rendered in the main notes stream as raw unparsed strings (e.g. `[ ] Plan sprint\n[x] Setup repo`), providing poor visual hierarchy.
- **Resolution:** Implemented checklist parser in note card view builder: parses markdown check lines, renders interactive `☐` and `☑` indicators with strike-through styling and muted color for completed items.
- **Verification:** Verified checklist note card formatting in emulator (`03_checklist.png`).

---

### Bug 6: Overlay Sidebar Panel Search Bar Placed Below All Notes
- **Severity:** High (UX / Usability)
- **Location:** [`SideNote-Android-v2.3/app/src/main/java/com/sidedeck/notes/OverlayService.kt`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SideNote-Android-v2.3/app/src/main/java/com/sidedeck/notes/OverlayService.kt#L170)
- **Description:** The search input in `OverlayService` was placed at the bottom of the panel layout below the scrollable notes list. When multiple notes existed, users had to scroll all the way to the bottom to search, and focusing the field caused the soft keyboard to obstruct the query input.
- **Resolution:** Moved search bar to the top of the sidebar panel directly below the "Create note" action button. Styled it with a clean container background and search icon.
- **Verification:** Verified search bar layout in floating sidebar (`04_sidebar.png`).

---

### Bug 7: Floating Note Header Button Squishing & Title Overflow
- **Severity:** High (UI / Interaction)
- **Location:** [`SideNote-Android-v2.3/app/src/main/java/com/sidedeck/notes/OverlayService.kt`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SideNote-Android-v2.3/app/src/main/java/com/sidedeck/notes/OverlayService.kt#L275)
- **Description:** In standalone floating note windows, the title text view lacked a single-line constraint, and action buttons (`✎`, `◱`, `◐`, `−`, `×`) used generic button styling. A moderately long title wrapped multi-line and pushed the header buttons or close icon out of view.
- **Resolution:**
  1. Implemented compact `Ui.cardBtn()` helper with dedicated 28dp touch target and minimal padding.
  2. Applied `setSingleLine(true)` and `ellipsize = TextUtils.TruncateAt.END` to title text with `weight = 1f`.
  3. Increased card default widths to `intArrayOf(285, 325, 365)` dp (from 240/280/320dp).
- **Verification:** Verified floating card and header actions on emulator (`05_floating_card.png`, `07_multiple_cards.png`).

---

### Bug 8: Missing Fallback During Animated Card Morph Dismissal
- **Severity:** Medium (Edge Case / Stability)
- **Location:** [`SideNote-Android-v2.3/app/src/main/java/com/sidedeck/notes/OverlayService.kt`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SideNote-Android-v2.3/app/src/main/java/com/sidedeck/notes/OverlayService.kt#L394)
- **Description:** During `animateCardTransition()`, if a note was deleted or modified while the transition animation was running, `targetNote` returned `null`, leaving an orphaned view in the WindowManager without dismissing or cleaning up the window.
- **Resolution:** Added `else dismissFloatingCard(id)` to the animation end action callback to guarantee proper cleanup.
- **Verification:** Unit test `testMultiWindowFloatingCardStateIndependence` and manual dismiss testing confirmed zero orphaned window views.

---

### Bug 9: Accessibility Warning on Overlay Edge Handle
- **Severity:** Low (Accessibility / Lint)
- **Location:** [`SideNote-Android-v2.3/app/src/main/java/com/sidedeck/notes/OverlayService.kt`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SideNote-Android-v2.3/app/src/main/java/com/sidedeck/notes/OverlayService.kt#L104)
- **Description:** Custom `OnTouchListener` on the edge handle processed `ACTION_UP` click detection without invoking `performClick()`, triggering accessibility warnings and preventing TalkBack single-tap event dispatch.
- **Resolution:** Added `tab.performClick()` in the tap detection branch.
- **Verification:** Accessibility lint clean; verified touch handling.

---

### Bug 10: Missing High-Resolution App Icon Assets & Adaptive Mipmaps
- **Severity:** Medium (Branding / Polish)
- **Location:** `app/src/main/res/mipmap-*` & `AndroidManifest.xml`
- **Description:** The app previously defaulted to standard Android vector placeholder icons. User-requested 3D app icon and logo assets needed to be converted into high-density mipmaps across all standard display buckets.
- **Resolution:** Resampled user 3D artwork to `mipmap-mdpi` (48x48), `mipmap-hdpi` (72x72), `mipmap-xhdpi` (96x96), `mipmap-xxhdpi` (144x144), `mipmap-xxxhdpi` (192x192) for `ic_launcher` and `ic_launcher_round`, and integrated `ic_sidenote.png` (512x512) and `ic_sidenote_logo.png` (512x512). Configured `android:icon` and `android:roundIcon` in `AndroidManifest.xml`.
- **Verification:** Verified via Android 14 App Drawer and home launcher screencap ([`app_drawer_icon.png`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SCREENSHOTS/app_drawer_icon.png)).

---

### Bug 11: Premium Source Tab Layout Wrapping in `MainActivity.kt`
- **Severity:** Medium (UI / Usability)
- **Location:** [`MainActivity.kt`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SideNote-Android-v2.3/app/src/main/java/com/sidedeck/notes/MainActivity.kt)
- **Description:** In the updated release codebase, the top category tabs ("All", "Pinned", "To-dos", "Archived") lacked explicit single-line wrapping parameters, causing label text truncation on smaller density viewports.
- **Resolution:** Added `setSingleLine(true)` and explicit `WRAP_CONTENT` layout parameters to all category tabs.
- **Verification:** Clean visual alignment verified in main feed screenshot ([`app_main_with_new_logo.png`](file:///d:/SideNote-v2.3-Antigravity-Acceptance-Package/SCREENSHOTS/app_main_with_new_logo.png)).

---

## 2. Known Limitations & Documented OS Constraints

1. **Overlay Visibility Across System Lock Screen:**
   - Under Android 14 security rules, `TYPE_APPLICATION_OVERLAY` windows are intentionally suppressed by the system WindowManager above the keyguard lock screen. Overlay windows automatically resume visibility immediately upon device unlock.
2. **Foreground Service Special Use Declaration:**
   - On Android 14+ (API 34), foreground services must declare a specific `foregroundServiceType`. `specialUse` was configured with system metadata in `AndroidManifest.xml`.
