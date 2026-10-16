# Sync fixes and note file transfer ? verification

Builds: Android 3.1.0-files-sync (versionCode 31), Windows portable executable.

## Passed

- Android normal suite: 38 tests executed, 0 failures. The opt-in live Windows test is skipped in normal runs and was then executed separately: 1 passed.
- Windows Rust: 16 tests, 0 failures. Includes interrupted file transfer/resume, corrupt-content rejection, empty files, path validation, 5 GB metadata, base-revision conflict handling and atomic page rollback.
- Final production interoperability test: real packaged Windows server + actual Android client code under Robolectric. 12 MiB binary file each direction, starting with existing partial data. Both complete SHA-256 values matched. Android and Windows edits, deletion without echoes, and preservation of existing Windows notes passed. The 15-second test harness runtime includes test setup and Gradle/JVM work; it is not a phone Wi-Fi throughput benchmark.
- Windows attachment UI: multiple selected files including an empty file and unknown binary type; both persisted, readiness controls enabled, removing one preserved the other and the note body.
- Existing rich editor native regressions: 7 checks passed.
- Existing dock hover, scrolling/arrows and real mouse drag in both directions: passed. Native blank-space click-through and fixed canvas checks: passed.
- Frontend TypeScript/Vite build and Windows release build: passed. Android debug APK assembled and its signature verified with apksigner.
- Deployed Desktop/installed/workspace Windows copies have matching SHA-256 hashes. Android named APK and aliases match. Double-launch retains one Windows instance.

## Installation and test limits

No Android device was enumerated by ADB. The APK was produced but not installed on a physical phone. Physical Wi-Fi throughput, Android vendor background restrictions and a physical phone-to-PC session remain unverified. Both updated apps must be paired on the same Wi-Fi/LAN. Size and speed are limited by available storage, providers/filesystems and the network; arbitrary sizes cannot be promised to finish in seconds.

The transfer protocol authenticates paired peers and verifies file contents, but does not add TLS/end-to-end encryption. JSON backups include file metadata only; binary files travel through LAN sync or Save as. Historical installers/source archives are not the current builds.

See FILES-AND-SYNC.md for usage and protocol behavior. Test notes and temporary trusted devices were removed, and existing user note contents were preserved.
