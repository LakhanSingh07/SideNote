# SideNote — Premium UI/UX Design System (Design approval package)

**Status:** UI specification + interactive web prototype. This is **not an Android app build**, Android code patch, or runtime verification. Apply to existing v2.4 Kotlin source only after visual approval.

## A. Product promise / design principles

> A beautiful tiny note, available from the edge of any supported Android app without taking the screen away.

1. **Small by default.** Edge handle → content-sized Peek Deck → one compact note. Full-page workspace is an intentional separate destination.
2. **Content is hero.** No giant banners, generic feature descriptions, endless settings under the note list, or all controls exposed at once.
3. **Calm like the supplied reference.** Warm off-white canvas, airy editorial typography, floating pastel paper cards, disciplined grid, soft elevation, lively but restrained motion.
4. **Readable at compact sizes.** Distinguish the main app (editorial, airy) from tiny overlays (efficient, high contrast). Do not shrink text below legibility.
5. **Never sacrifice utility to aesthetics.** Keyboard-safe edit, persistent storage, no accidental deletion, stable tap targets, small overlay footprint.

## B. Foundations and design tokens

### Palette

| Token | Light | Dark | Use |
|---|---|---|---|
| `canvas` | `#FAF8F0` | `#14171A` | full app background |
| `surface` | `#FFFFFF` | `#23262B` | sheet, note editor |
| `surface-soft` | `#F5F3EB` | `#2C2F34` | search, neutral cells |
| `ink` | `#202426` | `#F4F3EE` | title/body |
| `ink-secondary` | `#777B7D` | `#ADB0B4` | supporting text |
| `line` | `#ECEBE4` | `#373B40` | quiet dividers |
| `accent` | `#E5C968` | `#E5C968` | focus, active underline, small CTA |
| `accent-ink` | `#242426` | `#242426` | text on accent |
| `mint` | `#E6F1D2` | `#344838` | colored note |
| `sky` | `#D8E6FF` | `#30405B` | colored note |
| `peach` | `#FFE7C2` | `#55442F` | colored note |
| `lavender` | `#EAE5FA` | `#423C57` | colored note |
| `rose` | `#FCE2E7` | `#593C47` | colored note |
| `danger` | `#CE554B` | `#FA877D` | destructive action |

Use **one accent**, not all note colors on buttons. Colored note cards may be layered as a visual reference but real task list always remains readable.

### Typography

- Heading family: **Outfit** (fallback: system sans). Use locally bundled OFL font when shipping Android; verify license before inclusion.
- UI and body: **Inter** (fallback: system sans). Do not rely on live font fetching at runtime.
- Display `38sp / 40sp`, weight 400, tracking -0.8sp; main-app hero only.
- Page title `26sp / 32sp`, weight 600.
- Section title `20sp / 26sp`, weight 600.
- Card title `17sp / 23sp`, weight 600.
- Body `14sp / 21sp`, weight 400.
- Supporting `12sp / 17sp`, weight 400.
- Overlay preview title `14sp / 19sp`, weight 600; text `12sp / 17sp` (support font scaling and TalkBack).
- Buttons `14sp / 20sp`, weight 600, sentence case.
- **Never use 38sp display typography in compact overlays.** Android font scaling requires adaptable caps/scrolling.

### Shape, spacing and elevation

- Grid: 4dp base. Spacing tokens: `4, 8, 12, 16, 20, 24, 32, 40dp`.
- Screen horizontal padding: 22dp (360+dp width), 16dp on narrower viewports.
- Header safe-top: Android status bar inset + 20dp.
- Section separation: 24–32dp. Card vertical gap: 12dp.
- Radius: `10dp` mini, `16dp` search/buttons, `20dp` cards, `26dp` hero/overlay, `30dp` floating card, `999dp` pills.
- Border: `1dp` neutral line at low contrast, use only where useful.
- Shadows (light): overlay `0 14px 36px rgba(32,36,38,.13)`; card `0 6px 20px rgba(32,36,38,.06)`. Dark shadows softer; no bulky black halo.
- 48dp minimum *touch target* for tappable controls. Visual icon glyph may be 18–20dp inside a larger hitbox.
- In-app motion 180–240ms; overlay entry 220–300ms; reduced motion supported. Use spring with slight overshoot only on open/restore, not on every tap.

## C. Information architecture: 8 app states

| State | UI | Why it exists |
|---|---|---|
| 01 | Onboarding (2 screens max) | Show swipe demo, request overlay permission in context, skip allowed |
| 02 | Notes home | Search, card collection, quick create, filter |
| 03 | Archive | Restore archived notes safely |
| 04 | Settings | Appearance, placement, opacity, storage, reminders |
| 05 | Edge handle | <1% of visible phone area, left/right edge and accessibility hit target |
| 06 | Peek Deck | Compact anchored overlay showing latest/pinned notes |
| 07 | Floating card or minimized chip | Quick read, checklist and edit over host app |
| 08 | Expanded editor | Writing, checklist, reminder, note style; keyboard safe |

### 01 Onboarding
- Warm ivory background, single large display title, short value proposition and an illustrated edge gesture (use simple original in-app vector motion, not heavy photography).
- Two steps: `See it` → `Enable when ready`. Android overlay permission optional and explained clearly.
- Text under 35 words per page; clear skip.

### 02 Notes home
- No technical hero banner. Small top icon + a useful status label; title e.g. “Your little thoughts.”
- Search 44–48dp high with 1px warm gold stroke on focus.
- Segmented text tabs: `All`, `Pinned`, `To-dos` with thin golden underline, **not large filled pills**.
- First featured note can have restrained pastel paper stacking; real notes are separate readable cards below.
- Note cards: 16–20dp padding, title + two-line snippet + date, small category mark; no row of four giant outline buttons. Primary tap opens note; overflow handles other actions.
- FAB: 52dp circular plus, anchored above bottom nav, not floating next to page heading.
- Bottom nav: dark near-black container, three icons `Notes`, `Archive`, `Settings`; modest top curvature, fixed, honor gesture navigation insets.

### 03 Archive
- Same top rhythm; archived notes grouped by date; `Restore` and `Delete permanently` via overflow. Confirmation on permanent delete.

### 04 Settings
- Separate destination; grouped preferences rather than a single scroll after notes.
- `Appearance`: Light / Dark / System, accent previews.
- `Floating`: Edge left/right, trigger location, handle opacity, deck size preset, show/hide per supported app (advanced).
- `Data`: Export / Import with validation and conflict handling.
- `Reminders` and `Help`: notification status, explain Android device limitations.

## D. Core edge overlay geometry (Android `dp` not pixels)

| Overlay | Default | Max / adapt |
|---|---|---|
| Handle visual | 11–14w × 46h | 48dp accessible target using hit slop where permitted; do not intercept system back |
| Peek Deck | 232–248w × content | 3 previews, up to 54% usable screen height; internal scroll |
| Peek note row | 204w × 62–74h | two-line clamp, no full note body |
| Floating note | 260–282w × content | height cap 32% usable window; internal scroll |
| Minimized chip | 116–160w × 44–48h | only title/icon and restore glyph |
| Expanded editor | 300–340w or adaptive sheet | explicitly triggered, keyboard insets respected |

Footprint percentages are **design targets** on a typical 390×844dp screen, not universal guarantees: Android font scale, cutouts, nav insets and small phones take precedence.

### 05 Edge handle
- Visually subtle warm gold/neutral surface with 3-dot indicator (no giant violet tab).
- Tap to show deck, horizontal outward swipe to open, long-press to reposition; drag must not steal host-app scroll or system back gestures.
- Persist position; leave keyboard focus untouched until user intentionally opens editing.

### 06 Peek Deck
- Appears attached to edge at handle Y-coordinate. No black scrim or screen dim.
- `SideNote` 15sp small title, `+` and search icon, close via inward swipe, outside tap when Android overlay mechanism permits safe dismissal.
- Max 3 note previews: colored left dot, title, two lines, compact pin glyph.
- Can grow downward/upward to remain in usable window; never fill screen height by default.

### 07 Floating card + chip
- Paper-like corner 24–28dp, same surface as note color with toned-down variation. Content first, no permanent 5-icon action bar.
- Header: drag grip, title, overflow. Accessible quick minimize from overflow or dedicated compact control.
- Body: 2–5 lines, optional checklist toggles; safe inner scroll when long.
- Minimized chip remembers card position and note ID. Tap restores, long-press repositions.
- Multiple cards: up to 4 recommended visible, cascade rather than fully overlap; all remain within screen bounds.
- Editing from card does not unexpectedly open full app, unless keyboard/system restriction requires explicit fallback.

### 08 Editor
- Two levels: **quick edit in floating card** for body and checklist, **expanded editor** on explicit action.
- No native rectangular AlertDialog. Use custom visual panel with soft rounding, focus states, color row, save status.
- Debounced autosave + save before close; failures keep draft and show retry. Never silently clear content.
- Move UI above keyboard and keep Save/Done reachable; preserve original floating location after editing.

## E. Interaction and motion states

```
Handle --tap/swipe--> Peek Deck --tap preview--> Floating card
       <--swipe-->           |                 |--minimize--> Chip --tap--> Card
                             |                 |--edit--> Quick editor
                             |                 '--expand--> Full editor
                             '--tap +--> Quick create
```

- Handle open: 240ms ease-out/spring; deck width interpolates 14dp→236dp. Do not physically occupy 236dp when collapsed.
- Card open: scale .96→1 + alpha 0→1 in 220ms.
- Minimize: card morph / crossfade to chip ~220ms; preserve position; avoid teleport.
- Hover states do not exist on touch; define pressed, dragged, focused, selected, disabled, loading and error states.
- Gestures must be interruptible and cancel correctly if user touches buttons rapidly.

## F. Android source integration guide (existing v2.4 Kotlin)

1. Replace old main-activity home presentation with `NotesHome`, `Archive`, `Settings` navigation and remove development seed content from release build.
2. Implement shared `SideNoteTheme` tokens in Kotlin (colors, type, shape, dims). Prefer Compose if refactoring deliberately; do **not** mix half-implemented Compose and View code during the same pass. Views are acceptable with reusable factories and resources.
3. Refactor `WindowManager` overlays to use `WRAP_CONTENT` where safe and measured adaptive maximum heights; use bounded `LayoutParams` and distinct hit regions.
4. Make Peek Deck `≤248dp` wide and content-sized (max 3 previews), anchored to edge Y-position, no large dim background.
5. Floating windows: card/mini/editor are distinct stateful views; preserve unique note ID, position, bounds and state.
6. Persist notes separately from overlay window preferences and state; do not force notes reset during migration.
7. Test editing/IME/window flags carefully: Android overlays may need `FLAG_NOT_FOCUSABLE` toggled for keyboard; restore on exit and never strand keyboard.
8. Respect Android foreground-service, overlay permission, notification and restricted-screen behavior; never promise invisible system-level overlays.
9. Bundle licensed fonts into app resources; no runtime CDN dependency.
10. Match screenshots to the approved preview after first Android rendering; adjust on actual device, not CSS equivalence assumptions.

## G. Non-negotiable acceptance cases

- Main app is clean and **doesn't contain a floating feature advertisement hero**.
- Peek Deck covers no more than about a third of a typical phone screen and shows 3 concise previews.
- Opening the deck does not darken, navigate away from, or replace the host app.
- A normal floating note occupies about 25–30% of screen area at most in its initial state, except accessibility/size exceptions.
- All checklist toggles work directly on floating note; changes persist after force-stop/relaunch.
- Minimize restores same note and position; closing does not delete.
- Note title/body can be edited with keyboard without clipping Save/Done; failure preserves data.
- Notes remain readable with Android font scaling up to 1.5×, with internal scrolling or adaptive sizing.
- Settings are separate; light/dark/system render consistently across overlays.
- Demo development content is absent from release build; fresh install empty state is intentional.
- Compare all four key states on device: handle, Peek, card, chip; include screenshot evidence.
- Build and real-device testing remain mandatory before claiming production-ready.

## H. Implementation batches

**Batch 1 — Design system + app navigation:** shared tokens, new Notes/Archive/Settings, no demo content, inline search and tabs.

**Batch 2 — Edge overlay rebuild:** handle geometry, Peek sizing, previews, tap/swipe/outside-dismiss, adaptive bounds.

**Batch 3 — Floating note/editor:** card, chip, multiple cards, inline edit, checklist, keyboard insets, autosave failure behavior.

**Batch 4 — Polishing:** interactions, dark themes, accessibility, performance, screen recordings, real Android screenshot visual diff and bug fixes.

**Exit gate:** no release approval until Kotlin compiles, APK installs, core flows work on a real phone, data is preserved, and four overlay screenshot states visually match the approved design.
