Same-note expansion and overflow verification - 2026-10-16

- TypeScript/Vite production build and release executable build passed.
- Hover expands the same edge-attached note. The compact preview is hidden while expanded; lower tabs, the rail background and footer move down to preserve spacing.
- Collapsed headings wrap into vertical columns, with the full text also available in the title tooltip. Expanded headings wrap horizontally without ellipsis; the tested long heading was fully visible.
- Removed the four-note data and creation limit. Four notes remain visible at a time; wheel navigation moves through the complete ordered list with top/bottom arrow buttons and wheel navigation; the numeric range indicator is removed. Text editor scrolling does not change the note page.
- Existing eleven-note data reached notes beyond the fourth through wheel scrolling.
- Renderer-only seven-note fixtures verified reaching the seventh note, clamping at the end, scrolling back, and showing the complete long heading. No temporary notes were written to the database; a reload restored original state and timers.
- Hover, left drag, cancel, quick release and five repeat hover cycles produced zero resize events. Native mouse checks passed for input, click-through and stable canvas bounds.
- Purple plus button retained. Creating notes has no four-note guard; new notes are revealed at the start of the list.
- Desktop, installed, root and acceptance-kit EXEs have SHA-256 7B5F44A4BFC0E98CF514B782552E9FFBDBC03812A8BE7AFB2C55C0505197DF8A.
- Desktop EXE restarted without debugging enabled; repeated launch left one process. See deployment.json and results.json.

- Reference-style background: off-white #fffefd, soft layered shadow, and a larger lower-left rounded corner.
- Arrow-click fixture checks passed in both directions, including disabled boundaries; native input areas for both arrows verified. Same-note expansion, long titles, wheel navigation, drag and click-through regression checks also passed.

- Downward-drag regression reproduced on the previous executable: first-to-fourth real mouse reorder stopped after its first swap.
- Fixed capture ownership by capturing the stable dock frame instead of a tab/grip that React moves during reordering.
- The same real mouse regression now passes first-to-fourth and fourth-to-first, preserving all off-screen note IDs. Temporary renderer state, cursor position and the exact original stored order are restored after testing.
