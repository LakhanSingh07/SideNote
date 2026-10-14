# SideNote — final Android acceptance tasks

**Unverified until device evidence exists.** Source is native Kotlin/Android Views and retains the old data storage format.

## Visual UX acceptance

1. First install: two onboarding stages only, no development demo content. User can skip overlay permission and still use the notes app.
2. Notes home: clean editorial typography, warm ivory background, uncluttered tab navigation, pastel cards and dark bottom nav. No old oversized hero banner.
3. Test `All`, `Pinned`, `To-dos`, search, Archive and Settings; no unexpected navigation resets or keyboard blocking.
4. Edge collapsed: 11dp visible tab; interactable without intercepting common navigation/scroll gestures.
5. Open over Chrome: compact Peek Deck, 236dp wide, only 3 previews, no screen dimming and no giant page takeover.
6. Tap preview: 256dp compact colored card; drag handle, minimize and overflow work; card remains inside device bounds.
7. Minimize: ~138dp compact chip; tap restores previous note and position. At most 4 independently floating cards.
8. Edit: keyboard-safe rounded editor, title/body/checklist/color/Done, saved states and recovery from failed writes; no rectangular stock note editor.
9. Dark mode: warm charcoal surfaces, legible pastel notes, accessible text contrast, no clipped actions.
10. Test at 1.0× and 1.5× font scaling plus Android system back gesture, narrow 360dp devices and landscape.

## Functional / platform acceptance

- Fresh install, 20 notes, 20 checklist edits, pin/archive/restore, search, share-to-note.
- Atomic persistence across app restart, force-stop, screen lock/unlock and phone reboot. Preserve the data on errors; don't overwrite corrupt originals.
- Export JSON -> delete test note -> import -> verify exact text and checklist.
- Single/multiple floating notes, drag/minimize, service stop removes overlays, no unexpected notes deletion.
- Notification permission denial, overlay permission revoked, Android secure screen, service killed by OEM battery policy.
- Stress: 15 minutes frequent opening/closing; long note text; Android versions and OEMs accessible to the tester.
- Capture real screenshots for app home, search, archive, settings, onboarding, collapsed edge, Peek, card, chip, keyboard-open editor. Compare against `DESIGN_SOURCE_OF_TRUTH.md`.
- Run `:app:assembleDebug` and include build/test logs, APK checksum, defects with reproduction and PASS/PARTIAL/FAIL per case.

**Release gate:** no Play Store launch claim without successful compilation, tested APK, visual verification, keyboard and overlay behavior, data-preservation tests and Play policy review.
