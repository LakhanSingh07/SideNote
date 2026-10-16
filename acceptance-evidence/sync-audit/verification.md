# Android / Windows sync audit — 2026-10-16

Result: sync has reproducible Android-side correctness defects. Live phone round-trip remains unverified.

## Running Windows app

- TCP protocol PING/PONG on localhost:43861: PASS.
- UDP discovery on localhost:43862: PASS. This checks local response only, not reachability from a phone across Wi-Fi/firewall.
- Invalid pairing token: correctly rejected.
- Read-only application database check: 0 paired devices, 0 stored sync conflicts.

## Android source and isolated tests

The existing SyncStoreTest suite ran: 4 passed, 1 failed. Its failing test explicitly calls recordUpsert after NotesStore.save already records that mutation; that outdated test does not establish an automatic duplicate-save product defect.

Four additional Robolectric probes used isolated fake preferences and notes. One baseline passed: saving one local note records one change and preserves checklist/body fields. Three regression probes failed:

1. LanSyncManager.triggerSync reads peerCursor(pairedId), which tracks downloaded Windows sequence numbers, and passes it to exportBundle for Android uploads. After receiving Windows sequence 300, Android's first pending local mutation is skipped. Separate upload acknowledgement and download cursors are needed; the response's receivedCursor is currently ignored.
2. SyncStore.importBundle calls NotesStore.delete for a received deletion. That records and re-exports a local delete instead of applying the remote mutation directly.
3. SyncStore.importBundle snapshots currentNotes before processing the page. A create followed by delete in one page leaves the created note because delete consults the stale snapshot.

The probes are retained here, outside the normal test source set, along with their XML results. Production source and installed executables were not changed during this audit.

## Device limitation

ADB was checked repeatedly, including after the user reconnected USB and after restarting ADB. It enumerated no devices; Windows PnP also exposed no Android/ADB/MTP device. Consequently the installed APK version, phone pairing state, Wi-Fi path, background sync and real two-device create/edit/delete were not verified. No user note contents or pairing credentials were changed.
