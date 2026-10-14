import os
import time
from playwright.sync_api import sync_playwright

OUTPUT_DIR = r"c:\Users\lakha\Videos\SideNote-Android-main\SCREENSHOTS"
os.makedirs(OUTPUT_DIR, exist_ok=True)

def run():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1440, "height": 900})
        page = context.new_page()

        # 1. Desktop with Floating Notes & Edge Dock
        print("Capturing 1_desktop_companion.png...")
        page.goto("http://localhost:4173/")
        page.wait_for_selector(".dock-rail")
        time.sleep(1)
        page.screenshot(path=os.path.join(OUTPUT_DIR, "1_desktop_companion.png"))

        # 2. Expanded Peeking Notes Dock Rail
        print("Capturing 2_peeking_notes_dock.png...")
        page.click(".dock-header")
        time.sleep(0.8)
        page.wait_for_selector(".dock-rail.is-expanded")
        expanded_rail = page.query_selector(".dock-rail")
        if expanded_rail:
            expanded_rail.screenshot(path=os.path.join(OUTPUT_DIR, "2_peeking_notes_dock.png"))

        # 3. Rich Formatting Toolbar & Live Stats on Text Note
        print("Capturing 3_rich_formatting_toolbar.png...")
        note_page = context.new_page()
        note_page.set_viewport_size({"width": 360, "height": 450})
        note_page.goto("http://localhost:4173/?view=note&id=sample-1")
        note_page.wait_for_selector(".sticky-toolbar-row")
        time.sleep(1)
        note_elem = note_page.query_selector(".floating-sticky-note")
        if note_elem:
            note_elem.screenshot(path=os.path.join(OUTPUT_DIR, "3_rich_formatting_toolbar.png"))
        note_page.close()

        # 4. Dual Mode Interactive Checklist Note
        print("Capturing 4_dual_checklist_interactive.png...")
        chk_page = context.new_page()
        chk_page.set_viewport_size({"width": 360, "height": 450})
        chk_page.goto("http://localhost:4173/?view=note&id=sample-2")
        chk_page.wait_for_selector(".checklist-container")
        time.sleep(1)
        chk_elem = chk_page.query_selector(".floating-sticky-note")
        if chk_elem:
            chk_elem.screenshot(path=os.path.join(OUTPUT_DIR, "4_dual_checklist_interactive.png"))
        chk_page.close()

        # 5. Smart Reminder Popup Modal
        print("Capturing 5_smart_reminder_presets.png...")
        rem_page = context.new_page()
        rem_page.set_viewport_size({"width": 360, "height": 450})
        rem_page.goto("http://localhost:4173/?view=note&id=sample-3")
        rem_page.wait_for_selector(".sticky-actions")
        time.sleep(0.8)
        # Click the bell icon button
        bell_btn = rem_page.query_selector("button[title*='reminder']")
        if bell_btn:
            bell_btn.click()
            time.sleep(0.5)
            rem_page.wait_for_selector(".note-popup-card")
            rem_elem = rem_page.query_selector(".floating-sticky-note")
            if rem_elem:
                rem_elem.screenshot(path=os.path.join(OUTPUT_DIR, "5_smart_reminder_presets.png"))
        rem_page.close()

        # 6. Library View with Backup & Tools Tab
        print("Capturing 6_library_backup_tools.png...")
        lib_page = context.new_page()
        lib_page.set_viewport_size({"width": 900, "height": 650})
        lib_page.goto("http://localhost:4173/?view=library")
        lib_page.wait_for_selector(".library-tabs-nav")
        time.sleep(0.8)
        # Click Backup & Tools tab
        backup_tab = lib_page.query_selector("button:has-text('Backup & Tools')")
        if backup_tab:
            backup_tab.click()
            time.sleep(0.6)
        lib_elem = lib_page.query_selector(".library-window")
        if lib_elem:
            lib_elem.screenshot(path=os.path.join(OUTPUT_DIR, "6_library_backup_tools.png"))
        lib_page.close()

        browser.close()
        print("All high-res screenshots successfully captured in SCREENSHOTS folder!")

if __name__ == "__main__":
    run()
