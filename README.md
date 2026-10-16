# SideNote — Android & Windows Cross-Platform Ecosystem

SideNote is an intelligent, always-nearby notes companion designed with tactile pastel sticky-note aesthetics, smart screen-edge peeking interactions, and paired LAN synchronization between Android and Windows.

## Current files and sync build

Use `SideNote-Android-v3.1.0.apk` and `sidenote-windows.exe` for note attachments and resumable Android/Windows file transfer. See [Files and sync](FILES-AND-SYNC.md) for setup, verified behavior and current limits. The older setup installers and source archives listed below are historical artifacts and do not include this update.

---

## 🚀 Repository Structure

```
├── SideNote-Android-main/
│   └── SideNote-Android-v2.3/          # Full Android Studio project (Kotlin, Room, Jetpack, Material)
│
├── SideNote-Windows-v1.3.2-Acceptance-Kit/
│   └── SideNote-Windows-v1.3.1/         # Full Windows desktop project (Tauri v2 + Rust + React + Vite)
│
├── SCREENSHOTS/                         # UI/UX screenshot suite demonstrating all core states
│   ├── 1_edge_dock_idle.png             # Unobtrusive magnetic dock rail on screen edge
│   ├── 2_peeking_notes.png              # Expanded dock revealing peeking pastel cards
│   ├── 3_floating_note.png              # Tactile floating sticky note card
│   ├── 4_multiple_floating_notes.png    # Multiple floating sticky notes across desktop
│   └── 5_dark_mode.png                  # High-contrast dark mode aesthetic
│
├── SideNote-Android.apk                 # Installable Android debug APK (3.34 MB)
├── SideNote-Windows-Setup.exe           # Windows NSIS setup installer (3.08 MB)
├── SideNote-Windows-Setup.msi           # Windows MSI installation package (4.74 MB)
├── SideNote-Android-Final-Source.zip    # Clean Android source archive
├── SideNote-Windows-Final-Source.zip    # Clean Windows source archive
│
├── SideNote-Windows-UI-Redesign.md      # UI/UX redesign architecture and visual grammar
├── SideNote-Cross-Platform-Protocol.md  # LAN synchronization protocol specification
└── SideNote-Acceptance-Report.md        # Comprehensive verification and test results
```

---

## 🛠️ How to Access & Run on Another PC

### 1. Clone this Repository
```bash
git clone https://github.com/LakhanSingh07/SideNote.git
cd SideNote
```

---

### 2. Android App Setup

#### Quick Install (Device or Emulator)
Directly install the compiled APK:
```bash
adb install SideNote-Android.apk
```

#### Build from Source
1. Open `SideNote-Android-main/SideNote-Android-v2.3` in **Android Studio** (Koala / Ladybug).
2. Or build from command line:
```bash
cd SideNote-Android-main/SideNote-Android-v2.3
./gradlew assembleDebug
```
3. Run unit tests:
```bash
./gradlew test
```

---

### 3. Windows App Setup

#### Quick Install
Run `SideNote-Windows-Setup.exe` or `SideNote-Windows.msi` to install the desktop application.

#### Build from Source
Prerequisites:
- **Node.js** (v18+)
- **Rust & Cargo** (`rustup default stable-x86_64-pc-windows-msvc`)
- Visual Studio C++ Build Tools

Commands:
```bash
cd SideNote-Windows-v1.3.2-Acceptance-Kit/SideNote-Windows-v1.3.1
npm install
npm run build
npm run tauri build
```

To run in development mode with hot-reloading:
```bash
npm run tauri dev
```

---

## 🔄 Cross-Platform LAN Synchronization
SideNote synchronizes notes privately and securely across devices on your local network:
- **Discovery**: Zero-configuration UDP broadcast on port `41829`.
- **Encryption**: TLS 1.3 with AES-256-GCM authenticated encryption using a 256-bit shared key.
- **Conflict Resolution**: Deterministic Last-Write-Wins (LWW) timestamp merge with tombstone tracking.

See [SideNote-Cross-Platform-Protocol.md](./SideNote-Cross-Platform-Protocol.md) for full protocol details.
