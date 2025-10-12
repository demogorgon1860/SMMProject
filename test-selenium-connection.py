#!/usr/bin/env python3
"""
Test Selenium Grid Connection
This script verifies that Selenium Grid is accessible and can create browser sessions
"""

import requests
import json
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
import time

def test_grid_status():
    """Check if Selenium Grid is running and ready"""
    print("=" * 60)
    print("Testing Selenium Grid Status...")
    print("=" * 60)

    try:
        response = requests.get("http://localhost:4444/status")
        status = response.json()

        if status["value"]["ready"]:
            print("[OK] Selenium Grid is READY")
            print(f"   Message: {status['value']['message']}")
            print(f"   Nodes: {len(status['value']['nodes'])}")

            for node in status["value"]["nodes"]:
                print(f"\n   Node ID: {node['id']}")
                print(f"   Max Sessions: {node['maxSessions']}")
                print(f"   Available: {node['availability']}")
                print(f"   Version: {node['version']}")
        else:
            print("[FAIL] Selenium Grid is NOT ready")
            return False

    except Exception as e:
        print(f"[FAIL] Failed to connect to Selenium Grid: {e}")
        return False

    return True

def test_browser_session():
    """Create a browser session and navigate to YouTube"""
    print("\n" + "=" * 60)
    print("Testing Browser Session Creation...")
    print("=" * 60)

    driver = None
    try:
        # Configure Chrome options
        chrome_options = Options()
        chrome_options.add_argument("--no-sandbox")
        chrome_options.add_argument("--disable-dev-shm-usage")
        chrome_options.add_argument("--disable-gpu")
        chrome_options.add_argument("--window-size=1920,1080")
        chrome_options.add_argument("--headless")  # Run in headless mode for testing

        print("[...] Creating WebDriver instance...")
        driver = webdriver.Remote(
            command_executor='http://localhost:4444/wd/hub',
            options=chrome_options
        )

        print("[OK] WebDriver created successfully")

        print("[...] Navigating to YouTube...")
        driver.get("https://www.youtube.com")

        # Wait for page to load
        WebDriverWait(driver, 10).until(
            EC.title_contains("YouTube")
        )

        print(f"[OK] Successfully navigated to: {driver.title}")
        print(f"   Current URL: {driver.current_url}")

        # Try to find the search box as additional verification
        search_box = driver.find_element(By.NAME, "search_query")
        if search_box:
            print("[OK] Found YouTube search box - page loaded correctly")

        return True

    except Exception as e:
        print(f"[FAIL] Browser session test failed: {e}")
        return False

    finally:
        if driver:
            print("[...] Closing browser session...")
            driver.quit()
            print("[OK] Browser session closed")

def test_clip_button_availability():
    """Test if clip functionality is accessible"""
    print("\n" + "=" * 60)
    print("Testing Clip Button Availability...")
    print("=" * 60)

    driver = None
    try:
        chrome_options = Options()
        chrome_options.add_argument("--no-sandbox")
        chrome_options.add_argument("--disable-dev-shm-usage")
        chrome_options.add_argument("--window-size=1920,1080")
        chrome_options.add_argument("--headless")

        driver = webdriver.Remote(
            command_executor='http://localhost:4444/wd/hub',
            options=chrome_options
        )

        # Navigate to a sample YouTube video
        test_video = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        print(f"[...] Navigating to test video: {test_video}")
        driver.get(test_video)

        # Wait for video player to load
        time.sleep(5)

        # Check for the More actions button
        more_button = driver.find_elements(By.CSS_SELECTOR, "button[aria-label*='More actions']")
        if more_button:
            print("[OK] Found 'More actions' button - clip feature may be available")
            print("   Note: Actual clip creation requires YouTube login")
        else:
            print("[WARN] 'More actions' button not found - may require login")

        return True

    except Exception as e:
        print(f"[FAIL] Clip button test failed: {e}")
        return False

    finally:
        if driver:
            driver.quit()

def main():
    print("\n" + "SELENIUM GRID CONNECTION TEST" + "\n")

    results = []

    # Test 1: Grid Status
    results.append(("Grid Status", test_grid_status()))

    # Test 2: Browser Session
    results.append(("Browser Session", test_browser_session()))

    # Test 3: Clip Button
    results.append(("Clip Functionality", test_clip_button_availability()))

    # Summary
    print("\n" + "=" * 60)
    print("TEST SUMMARY")
    print("=" * 60)

    all_passed = True
    for test_name, passed in results:
        status = "[PASSED]" if passed else "[FAILED]"
        print(f"{test_name}: {status}")
        if not passed:
            all_passed = False

    print("\n" + "=" * 60)
    if all_passed:
        print("ALL TESTS PASSED! Selenium Grid is working correctly.")
        print("\nNext Steps:")
        print("1. Restart your Spring Boot application to load the fixes")
        print("2. Try creating a test clip from the admin panel")
        print("3. Monitor logs: docker logs -f smm_selenium_chrome")
    else:
        print("WARNING: Some tests failed. Please check:")
        print("1. Docker containers are running: docker ps")
        print("2. Selenium Grid is accessible on port 4444")
        print("3. Chrome node is connected to the hub")
        print("\nTo restart Selenium Grid:")
        print("docker-compose restart selenium-hub selenium-chrome")
    print("=" * 60)

if __name__ == "__main__":
    main()