# SideNote: paid launch and UX priorities

Audit scope: current Android Kotlin and Windows Tauri source, packaged builds, and physical OnePlus 10T tests. This is a product readiness review, not a comprehensive security audit. Proposed features below are not implemented unless stated otherwise.

## Current verified baseline

- Notes, formatting/checklists and LAN file attachments exist. Physical-device creation, edits, deletion and 12 MiB file transfer passed both ways with matching SHA-256 hashes.
- Android Files screen now has themed cards, file-type badges, note context, size/count summary, fixed Add files action, transfer progress, Open/Share actions and Save as/Remove menus. Light/dark screenshots are in `acceptance-evidence/files-ui`.
- Android: 40 regression tests pass, with one separate Windows interop test opt-in. Windows: 22 Rust tests passed in the preceding sync release. One real Android device has been exercised; this does not establish broad device or long-running reliability.

## Before charging customers

| Priority | Area | Gap and acceptance criterion |
| --- | --- | --- |
| P0 | Pairing, both apps | Broadcast discovery failed on the tested network. Add reliable device selection plus a fallback such as QR pairing; handle IP changes and stale peers. New users should pair without knowing an IP address. |
| P0 | Honest status | Android's green connected label currently uses saved pairing state. Show Paired, Online, Offline, Syncing, Last synced and recoverable errors distinctly. |
| P0 | Transfer reliability | Validate screen-off, process death, reboot, network changes, low storage, interrupted imports and files larger than 4 GB. Expose a resumable transfer queue; do not promise arbitrary sizes in seconds. |
| P0 | Recovery, both apps | JSON backups contain metadata rather than attachment bytes. Add complete backup/restore, Trash with undo, and a visible conflict-review flow. Never silently discard a customer's note. |
| P0 | Transport/security | Current LAN protocol has authenticated pairing but no TLS/end-to-end transport encryption. Encrypt note/file transport, review peer revocation and protect credentials. Review Windows CSP, currently null. |
| P0 | Purchases | No integrated Play Billing or cross-platform license/entitlement flow was found in the reviewed app code. Define Pro access, purchase verification, restoration, refunds/revocation and an offline grace period. Choose store/distribution channels first. |
| P0 | Production distribution | Current Android artifact is a debug APK; create a securely signed release/AAB. Rebuild current Windows installers, arrange code signing, integrate signed updates and test upgrades preserving data. Align version identifiers and remove stale download choices. |
| P0 | Release operations | Publish accurate privacy/support/terms pages, a support contact and release notes. Provide user-controlled diagnostic export with redaction. Test clean installs, upgrades and recovery on more devices/Windows setups. |
| P1 | Android | Check compact screens, keyboard/insets, large fonts, TalkBack, permission denial, battery restrictions and reminder behavior. Verify rich formatting survives repeated Windows round trips. |
| P1 | Windows | Check multiple monitors, DPI changes, sleep/wake, taskbar placement, dock hit regions and repeated launches. Add discoverable keyboard actions and drag/drop file attachment. |

## Useful, cute dock direction

Preserve pastel rectangular notes peeking out of the screen edge. Keep one expanded instance of the selected note. Avoid changing the note silhouette into pills.

1. Offer readable horizontal titles and optional compact vertical titles. Reveal the full title on hover/focus; filenames and note headings should not require guessing.
2. Use one meaningful badge per note: attached-file count, remaining checklist items or a due reminder. Put secondary edit actions in a hover/focus menu.
3. Give the active note a small outward movement and restrained shadow. Keep neighboring notes visible and keep Add/Library aligned with the exposed note edge.
4. Make top/bottom scroll arrows subtle but clear, with wheel/touchpad support and keyboard focus. Avoid changing note order while a user is dragging.
5. Use gentle paper corners, a consistent pastel palette and optional sticker accents. Include a restrained theme for users who prefer less decoration.
6. Use brief feedback for save, sync and checklist completion; respect reduced motion. Engagement should come from successful tasks, rather than streak pressure.

## Shared Files experience

- A single note identity and matching language on both platforms.
- Image thumbnails, recognizable document types and readable long names.
- Clear local/remote availability, progress, retry and last-transfer result.
- File search, type filters, multi-select, batch save/share and a desktop drop target.
- Pair/switch-device entry near the transfer state; explain what stays local when unpairing.
- Templates such as study kit, project handoff, recipes or trip documents that demonstrate notes plus attachments.

Suggested sequence: reliable pairing and truthful status → recovery and encryption → editor/device testing → release/purchase plumbing → measured beta → public paid launch.

## Official distribution references

- [Google Play Billing](https://developer.android.com/google/play/billing): purchase flows and secure backend verification.
- [Tauri Windows signing](https://v2.tauri.app/distribute/sign/windows/): installer signing.
- [Tauri updater](https://v2.tauri.app/plugin/updater/): signed update artifacts and verification. Update signing and Windows code signing serve different purposes.
