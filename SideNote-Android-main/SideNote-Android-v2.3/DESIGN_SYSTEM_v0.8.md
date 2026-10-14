# SideNote — Design system v0.8

**Goal:** original, calm, utility-focused design informed by the usability patterns of Floating Notes and Peeky Notes. No copied assets.

## Tokens
- Brand violet: `#7058E8`; ink: `#191D2C`; muted text: `#747C93`; base canvas: `#F6F7FC`.
- Neutral surfaces use white with faint gray-violet border `#E9E7F3`.
- Note colors are warm yellow, cool blue, blush, mint, lavender.
- Main layout uses 16–22dp corner radii and generous 12–22dp spacing.

## Interaction states
1. Thin **28dp** edge handle; drag vertically to reposition, swipe inward to open.
2. Notes sidebar has search, new-note primary action, colored preview cards, quick actions.
3. Tap note opens editable overlay with debounced autosave; **Done** closes only after successful persistence.
4. `Float` opens independent card. `−` collapses to compact chip, tap chip restores. `×` dismisses card without deleting underlying note.
5. Cards may be moved within screen bounds. On Android protected screens system may hide overlays; stored notes survive.
6. Native share-to-note, pin/archive, JSON backup, reminders, opacity and dock-side preferences retained from previous builds.

## Accessibility and reliability acceptance (device validation required)
- Check touch controls and screen reader names; minimum 44–48dp touch targets still need full audit.
- Verify overlay focus/IME, screen rotate, insets, lock/unlock, notification permissions and app process recreation on real phones.
- Avoid clipped content at display font scale 1.5 and landscape orientation.
- Never suggest `SYSTEM_ALERT_WINDOW` enables unrestricted view on protected apps.

## Next implementation quality gates
Actual APK compilation, emulator/phone interaction testing, accessibility audit, polished dark mode and multiple simultaneously floating windows have **not** been completed.
