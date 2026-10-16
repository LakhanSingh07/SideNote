Overlay design verification - 2026-10-16

- Visual references inspected: https://www.apptorium.com/sidenotes/tips/how-to-mark-notes-with-colors and https://sticky.scribely.site/ . Public reference images retained under src-tauri/target/design-references.
- Replaced folded-corner decoration and separate floating footer buttons with flat pastel paper tabs, a shared neutral edge rail, and matching footer controls.
- All paper tabs protrude 6.48 logical pixels beyond the background rectangle on the tested display. Titles and footer icons remain in one aligned column.
- Restored the plus button to original purple #8B4CFF with a white icon.
- The popup opens to the left of the rail with a 10.8-logical-pixel gutter. Bounding-box checks confirm clearance from every lower note and footer control, and the selected title stays visible.
- An invisible hover bridge preserves input while crossing the popup gutter; verified with a real Windows mouse.
- TypeScript/Vite and release builds passed. Hover, drag, cancel, quick release and five repeat hover cycles recorded zero native resizes. See results.json.
- Native checks passed for click-through, visible-note input, gutter crossing, and fixed native canvas bounds during real dragging.
- Actual Windows screenshots native-hover.png and native-drag.png inspected for alignment, popup separation and stationary sidebar.
- Desktop, installed, root and acceptance-kit executable SHA-256: 4EF45DE6D4D635097FADC009794DB1D181C3E55760E5856BE4F3159F6C75B057.
- Desktop EXE restarted without debugging enabled; second launch exited with code 0 and one process remained. See deployment.json.
