# SideNote Windows — UI/UX Overhaul & Redesign Documentation

## Executive Summary

The Windows version of **SideNote** has been completely overhauled from a generic, full-height "notes manager panel" into a true **always-nearby desktop companion** that embodies the visual language, charm, and tactile delight of the Android app.

---

## 1. What Changed & Why

| Aspect | Previous Windows Implementation (Rejected) | New SideNote Redesign (Implemented) | Rationale |
| :--- | :--- | :--- | :--- |
| **Primary Interaction** | Giant full-screen or full-height persistent sidebar | **Magnetic edge dock + peeking note tabs** | Preserves desktop screen real estate; stays out of the way until summoned. |
| **Note Identity** | Generic rectangular flat cards | **Tactile pastel sticky notes** with top accent tape, rounded corners, and soft paper depth | Evokes physical sticky notes; playful, warm, and delightful. |
| **Visual Alignment** | Disconnected monochrome/grey palette | **100% matched with Android `Ui.kt`**: Royal Violet brand accent (`#8A4BFF`) + 6 pastel note hues | Shared cross-platform design grammar and recognizable brand personality. |
| **Dock Behavior** | Heavy open/close panel transition | **Elastic micro-spring peeking slivers** that expand on hover/click | Delightful micro-interactions with immediate preview without heavy window overhead. |
| **Management View** | Persistent default dashboard | **Secondary Library Window** for search, archive, settings, and LAN sync | Management tasks should never clutter the core desktop experience. |

---

## 2. Shared Visual System (Android ↔ Windows)

Both applications now share identical tokens and styling grammar:

### Color Palette Tokens
- **Brand Violet**: `#8A4BFF` (Royal Violet accent, active indicators, brand badges)
- **Canary / Soft Yellow**: `#FFF6B8` (Paper background) / `#F3E692` (Border) / `#3F3714` (Ink)
- **Warm Peach**: `#FFDFC7` / `#F3C4A5` / `#482D1B`
- **Blush Pink**: `#FFD4DE` / `#F0B5C4` / `#4A1E2B`
- **Mint Green**: `#D4F5DE` / `#AEE0BD` / `#1B3D28`
- **Sky Blue**: `#D6EBFC` / `#B2D7F5` / `#1A364E`
- **Lavender**: `#EBE0FD` / `#D1BDF5` / `#372352`

### Typography & Surfaces
- **Font**: Inter / -apple-system / Segoe UI Variable Display
- **Corner Radii**: 18px for floating sticky cards, 24px for dock rails, 12px for interactive chips
- **Elevation**: Soft colored drop shadows (`rgba(138, 75, 255, 0.12)`) simulating floating paper

---

## 3. Core Interaction Architecture

### A. Edge Dock (Idle State)
- Sits unobtrusively on the screen edge (only **58px** wide).
- Displays the brand logo badge (`SideNote.`) with purple glow.
- Renders **3–4 vertical mini chips** representing recent notes with their color identity and pinned status.

### B. Peeking Notes (Hover / Expand State)
- Hovering or clicking the dock rail expands it smoothly (**285px**) with a spring transition.
- Reveals note cards with:
  - Note type badge (`Sticky Note` vs `Checklist`)
  - Note title and clean content preview
  - Pin indicator
- Clicking any peeking sliver instantly opens it as a floating desktop sticky note.
- Quick action footer provides instant note creation (`+ New Note`), Library access (`All Notes`), and Theme toggling.

### C. Floating Sticky Note Cards
- Freeform, draggable floating windows anywhere on the desktop.
- Features:
  - **Top Accent Tape**: Tactile grab-bar for effortless window dragging.
  - **Pin Toggle**: Keeps note always on top above other applications.
  - **Fold / Minimize**: Smoothly folds note back into the edge rail without redundant duplicates.
  - **Color Selector**: 6 one-click pastel swatches dynamically altering background, border, and ink colors.
  - **Checklist Mode**: Clean interactive checklist items with custom animated checkboxes.

### D. Secondary Library & LAN Sync Hub
- Separate dedicated window (`/?view=library` or modal) containing:
  - Real-time search across all active and archived notes
  - Pin, archive, and delete management
  - Cross-platform encrypted LAN synchronization controls (Zero-Conf UDP pairing, TLS 1.3, AES-256-GCM)

---

## 4. Screenshot Deliverables Manifest

All captured screenshots are stored in [`SCREENSHOTS/`](file:///c:/Users/lakha/Videos/SideNote-Android-main/SCREENSHOTS):

1. **Edge Dock Idle State**: [`SCREENSHOTS/1_edge_dock_idle.png`](file:///c:/Users/lakha/Videos/SideNote-Android-main/SCREENSHOTS/1_edge_dock_idle.png)
   - Demonstrates the slim, unobtrusive magnetic rail on the screen edge with brand badge and color-coded note slivers.
2. **Peeking Notes State**: [`SCREENSHOTS/2_peeking_notes.png`](file:///c:/Users/lakha/Videos/SideNote-Android-main/SCREENSHOTS/2_peeking_notes.png)
   - Displays the expanded dock revealing preview cards for top notes, checklist badges, and quick creation controls.
3. **Floating Note Card State**: [`SCREENSHOTS/3_floating_note.png`](file:///c:/Users/lakha/Videos/SideNote-Android-main/SCREENSHOTS/3_floating_note.png)
   - Highlights the tactile sticky-note card with top tape, pin/fold buttons, pastel palette swatches, and rich text/checklist typography.
4. **Multiple Floating Notes State**: [`SCREENSHOTS/4_multiple_floating_notes.png`](file:///c:/Users/lakha/Videos/SideNote-Android-main/SCREENSHOTS/4_multiple_floating_notes.png)
   - Shows multiple colorful notes floating harmoniously across the desktop space alongside the docked edge rail.
5. **Dark Mode State**: [`SCREENSHOTS/5_dark_mode.png`](file:///c:/Users/lakha/Videos/SideNote-Android-main/SCREENSHOTS/5_dark_mode.png)
   - Illustrates the sleek, high-contrast dark theme with glowing violet accents and deep pastel note cards.

---

## 5. Artifacts & Distribution Files

- **Windows NSIS Installer**: [`SideNote-Windows-Setup.exe`](file:///c:/Users/lakha/Videos/SideNote-Android-main/SideNote-Windows-Setup.exe) (3.01 MB)
- **Windows MSI Package**: [`SideNote-Windows.msi`](file:///c:/Users/lakha/Videos/SideNote-Android-main/SideNote-Windows.msi) (5.25 MB)
- **Windows Source Code**: [`SideNote-Windows-Final-Source.zip`](file:///c:/Users/lakha/Videos/SideNote-Android-main/SideNote-Windows-Final-Source.zip) (138 KB)
- **Android APK**: [`SideNote-Android.apk`](file:///c:/Users/lakha/Videos/SideNote-Android-main/SideNote-Android.apk) (3.34 MB)
- **Android Source Code**: [`SideNote-Android-Final-Source.zip`](file:///c:/Users/lakha/Videos/SideNote-Android-main/SideNote-Android-Final-Source.zip) (2.08 MB)
