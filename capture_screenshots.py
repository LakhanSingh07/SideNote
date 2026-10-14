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

        # 1. Desktop with Multiple Floating Notes (Light Mode)
        print("Capturing 4_multiple_floating_notes.png...")
        page.goto("http://localhost:4173/")
        page.wait_for_selector(".dock-rail")
        time.sleep(1)
        page.screenshot(path=os.path.join(OUTPUT_DIR, "4_multiple_floating_notes.png"))

        # 2. Idle Edge Dock Close-up
        print("Capturing 1_edge_dock_idle.png...")
        dock_rail = page.query_selector(".dock-rail")
        if dock_rail:
            dock_rail.screenshot(path=os.path.join(OUTPUT_DIR, "1_edge_dock_idle.png"))

        # 3. Peeking Notes (Expanded Dock State)
        print("Capturing 2_peeking_notes.png...")
        page.click(".dock-header")
        time.sleep(0.8)
        page.wait_for_selector(".dock-rail.is-expanded")
        expanded_rail = page.query_selector(".dock-rail")
        if expanded_rail:
            expanded_rail.screenshot(path=os.path.join(OUTPUT_DIR, "2_peeking_notes.png"))

        # 4. Standalone Floating Note Card
        print("Capturing 3_floating_note.png...")
        card_page = context.new_page()
        card_page.set_viewport_size({"width": 360, "height": 380})
        card_page.goto("http://localhost:4173/?view=note&id=sample-1")
        card_page.wait_for_selector(".floating-sticky-note")
        time.sleep(1)
        card_elem = card_page.query_selector(".floating-sticky-note")
        if card_elem:
            card_elem.screenshot(path=os.path.join(OUTPUT_DIR, "3_floating_note.png"))
        else:
            card_page.screenshot(path=os.path.join(OUTPUT_DIR, "3_floating_note.png"))
        card_page.close()

        # 5. Dark Mode State (Full Desktop)
        print("Capturing 5_dark_mode.png...")
        # Toggle theme in desktop view
        theme_btn = page.query_selector("button[title*='Theme']")
        if theme_btn:
            theme_btn.click()
            time.sleep(0.8)
        else:
            page.evaluate("() => document.documentElement.setAttribute('data-theme', 'dark')")
            time.sleep(0.5)
        page.screenshot(path=os.path.join(OUTPUT_DIR, "5_dark_mode.png"))

        browser.close()
        print("All screenshots successfully captured!")

if __name__ == "__main__":
    run()
