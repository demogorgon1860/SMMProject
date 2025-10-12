package com.smmpanel.selenium;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * Standalone test for YouTube clip automation
 *
 * <p>This test file allows you to: 1. Test authentication with real Gmail credentials 2. Navigate
 * to channels and select videos 3. Create clips with 15-second duration 4. Save session for future
 * use
 *
 * <p>Run this directly with: ./gradlew test --tests YouTubeClipAutomationTest
 *
 * <p>Or run the main method directly in your IDE
 */
public class YouTubeClipAutomationTest {

    private static final String PROFILE_DIR = "./selenium-profiles/test-profile";
    private static final int WAIT_TIMEOUT = 30;
    private static final int CLIP_DURATION_SECONDS = 15;

    // Test configuration - CHANGE THESE VALUES
    private static final String TEST_EMAIL = "bastardofedderdstark@gmail.com"; // CHANGE THIS
    private static final String TEST_PASSWORD = "masterofmasturbation1860"; // CHANGE THIS
    private static final String TEST_CHANNEL = "@MrBeast"; // Test channel
    private static final boolean HEADLESS = false; // Set to false to see browser

    public static void main(String[] args) {
        YouTubeClipAutomationTest test = new YouTubeClipAutomationTest();
        test.runFullTest();
    }

    public void runFullTest() {
        System.out.println("=== YouTube Clip Automation Test ===");
        System.out.println("Profile directory: " + PROFILE_DIR);
        System.out.println("Test email: " + TEST_EMAIL);
        System.out.println("Test channel: " + TEST_CHANNEL);
        System.out.println("Headless mode: " + HEADLESS);
        System.out.println("=====================================\n");

        WebDriver driver = null;

        try {
            // Step 1: Create WebDriver with persistent profile
            driver = createDriverWithProfile();
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(WAIT_TIMEOUT));

            // Step 2: Test authentication
            System.out.println("[STEP 1] Testing authentication...");
            boolean isAuthenticated = testAuthentication(driver, wait);
            System.out.println(
                    "Authentication result: " + (isAuthenticated ? "SUCCESS" : "FAILED"));

            if (!isAuthenticated) {
                System.out.println("\n[!] Authentication failed. Please check credentials.");
                return;
            }

            // Step 3: Test channel navigation
            System.out.println("\n[STEP 2] Testing channel navigation...");
            boolean channelFound = testChannelNavigation(driver, wait);
            System.out.println(
                    "Channel navigation result: " + (channelFound ? "SUCCESS" : "FAILED"));

            if (!channelFound) {
                System.out.println("\n[!] Could not navigate to channel.");
                return;
            }

            // Step 4: Test video selection
            System.out.println("\n[STEP 3] Testing video selection...");
            String videoUrl = testVideoSelection(driver, wait);
            System.out.println("Selected video: " + (videoUrl != null ? videoUrl : "NONE"));

            if (videoUrl == null) {
                System.out.println("\n[!] No suitable video found.");
                return;
            }

            // Step 5: Test clip creation
            System.out.println("\n[STEP 4] Testing clip creation...");
            String clipUrl = testClipCreation(driver, wait, videoUrl);
            System.out.println("Created clip: " + (clipUrl != null ? clipUrl : "FAILED"));

            // Final results
            System.out.println("\n=== Test Results ===");
            System.out.println("✓ Authentication: " + (isAuthenticated ? "PASS" : "FAIL"));
            System.out.println("✓ Channel Navigation: " + (channelFound ? "PASS" : "FAIL"));
            System.out.println("✓ Video Selection: " + (videoUrl != null ? "PASS" : "FAIL"));
            System.out.println("✓ Clip Creation: " + (clipUrl != null ? "PASS" : "FAIL"));

            if (clipUrl != null) {
                System.out.println("\n🎉 SUCCESS! Clip created: " + clipUrl);
            }

        } catch (Exception e) {
            System.err.println("\n[ERROR] Test failed with exception: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (driver != null) {
                System.out.println("\nPress Enter to close browser...");
                try {
                    new Scanner(System.in).nextLine();
                } catch (Exception e) {
                    // Ignore
                }
                driver.quit();
            }
        }
    }

    private WebDriver createDriverWithProfile() throws Exception {
        System.out.println("Creating Chrome driver with profile: " + PROFILE_DIR);

        // Create profile directory if it doesn't exist
        Path profilePath = Paths.get(PROFILE_DIR);
        if (!Files.exists(profilePath)) {
            Files.createDirectories(profilePath);
            System.out.println("Created new profile directory");
        } else {
            System.out.println("Using existing profile directory");
        }

        ChromeOptions options = new ChromeOptions();

        // Use persistent profile for session storage
        options.addArguments("--user-data-dir=" + profilePath.toAbsolutePath().toString());

        // Basic options
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--window-size=1920,1080");

        // Disable automation indicators
        options.setExperimentalOption("excludeSwitches", new String[] {"enable-automation"});
        options.setExperimentalOption("useAutomationExtension", false);

        // User agent to appear more like regular Chrome
        options.addArguments(
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML,"
                        + " like Gecko) Chrome/120.0.0.0 Safari/537.36");

        if (HEADLESS) {
            options.addArguments("--headless=new");
        }

        // Preferences
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("credentials_enable_service", false);
        prefs.put("profile.password_manager_enabled", false);
        options.setExperimentalOption("prefs", prefs);

        return new ChromeDriver(options);
    }

    private boolean testAuthentication(WebDriver driver, WebDriverWait wait) {
        try {
            // Navigate to YouTube
            System.out.println("Navigating to YouTube...");
            driver.get("https://www.youtube.com");
            Thread.sleep(3000);

            // Check if already logged in
            List<WebElement> avatarButtons =
                    driver.findElements(
                            By.cssSelector("button#avatar-btn, img#img[alt*='Avatar']"));

            if (!avatarButtons.isEmpty()) {
                System.out.println("Already authenticated with saved session!");
                return true;
            }

            // Need to sign in
            System.out.println("Not authenticated, initiating sign in...");

            // Find and click Sign In button
            WebElement signInButton =
                    wait.until(
                            ExpectedConditions.elementToBeClickable(
                                    By.cssSelector(
                                            "tp-yt-paper-button[aria-label*='Sign in'],"
                                                    + " a[aria-label*='Sign in']")));
            signInButton.click();

            // Wait for Google login page
            wait.until(ExpectedConditions.urlContains("accounts.google.com"));
            System.out.println("Redirected to Google login page");

            // Enter email
            WebElement emailInput =
                    wait.until(ExpectedConditions.presenceOfElementLocated(By.id("identifierId")));
            emailInput.clear();
            emailInput.sendKeys(TEST_EMAIL);

            WebElement nextButton = driver.findElement(By.id("identifierNext"));
            nextButton.click();

            Thread.sleep(2000);

            // Enter password
            WebElement passwordInput =
                    wait.until(ExpectedConditions.elementToBeClickable(By.name("password")));
            passwordInput.clear();
            passwordInput.sendKeys(TEST_PASSWORD);

            WebElement passwordNext = driver.findElement(By.id("passwordNext"));
            passwordNext.click();

            System.out.println("Credentials submitted, waiting for redirect...");

            // Handle potential 2FA
            Thread.sleep(3000);
            if (driver.getCurrentUrl().contains("accounts.google.com")) {
                System.out.println("\n[!] 2FA or additional verification may be required.");
                System.out.println("[!] Please complete it manually in the browser.");
                System.out.println("[!] Press Enter when done...");
                new Scanner(System.in).nextLine();
            }

            // Wait for redirect back to YouTube
            wait.until(ExpectedConditions.urlContains("youtube.com"));
            Thread.sleep(3000);

            // Verify login successful
            avatarButtons =
                    driver.findElements(
                            By.cssSelector("button#avatar-btn, img#img[alt*='Avatar']"));

            if (!avatarButtons.isEmpty()) {
                System.out.println("Successfully authenticated!");
                return true;
            } else {
                System.out.println("Authentication verification failed");
                return false;
            }

        } catch (Exception e) {
            System.err.println("Authentication error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean testChannelNavigation(WebDriver driver, WebDriverWait wait) {
        try {
            String channelUrl = "https://www.youtube.com/" + TEST_CHANNEL + "/videos";
            System.out.println("Navigating to channel: " + channelUrl);

            driver.get(channelUrl);

            // Wait for videos to load
            wait.until(
                    ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector("ytd-grid-video-renderer, ytd-rich-item-renderer")));

            // Scroll to load more videos
            JavascriptExecutor js = (JavascriptExecutor) driver;
            js.executeScript("window.scrollBy(0, 500)");
            Thread.sleep(2000);

            List<WebElement> videos =
                    driver.findElements(
                            By.cssSelector("ytd-grid-video-renderer, ytd-rich-item-renderer"));

            System.out.println("Found " + videos.size() + " videos on channel page");
            return !videos.isEmpty();

        } catch (Exception e) {
            System.err.println("Channel navigation error: " + e.getMessage());
            return false;
        }
    }

    private String testVideoSelection(WebDriver driver, WebDriverWait wait) {
        try {
            System.out.println("Selecting a suitable video for clip creation...");

            List<WebElement> videos =
                    driver.findElements(
                            By.cssSelector("ytd-grid-video-renderer, ytd-rich-item-renderer"));

            for (WebElement video : videos) {
                try {
                    // Skip shorts and live videos
                    List<WebElement> shortsIndicator =
                            video.findElements(
                                    By.cssSelector(
                                            "[aria-label*='Shorts'], [overlay-style='SHORTS']"));
                    List<WebElement> liveIndicator =
                            video.findElements(By.cssSelector(".badge-style-type-live-now"));

                    if (!shortsIndicator.isEmpty() || !liveIndicator.isEmpty()) {
                        continue;
                    }

                    // Get video link
                    WebElement linkElement =
                            video.findElement(By.cssSelector("a#video-title-link, a#thumbnail"));
                    String videoUrl = linkElement.getAttribute("href");

                    if (videoUrl != null && !videoUrl.isEmpty()) {
                        if (!videoUrl.startsWith("http")) {
                            videoUrl = "https://www.youtube.com" + videoUrl;
                        }

                        // Get video title
                        WebElement titleElement = video.findElement(By.cssSelector("#video-title"));
                        String title = titleElement.getText();

                        System.out.println("Selected video: " + title);
                        return videoUrl;
                    }

                } catch (Exception e) {
                    // Try next video
                }
            }

            return null;

        } catch (Exception e) {
            System.err.println("Video selection error: " + e.getMessage());
            return null;
        }
    }

    private String testClipCreation(WebDriver driver, WebDriverWait wait, String videoUrl) {
        try {
            System.out.println("Navigating to video: " + videoUrl);
            driver.get(videoUrl);

            // Wait for video player
            wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("video")));
            Thread.sleep(3000);

            // Play video briefly
            WebElement playButton = driver.findElement(By.cssSelector(".ytp-play-button"));
            if (playButton.getAttribute("aria-label") != null
                    && playButton.getAttribute("aria-label").contains("Play")) {
                playButton.click();
                Thread.sleep(2000);
            }

            // Pause video
            playButton = driver.findElement(By.cssSelector(".ytp-play-button"));
            if (playButton.getAttribute("aria-label") != null
                    && playButton.getAttribute("aria-label").contains("Pause")) {
                playButton.click();
            }

            // Find clip button - try multiple methods
            System.out.println("Looking for clip button...");
            WebElement clipButton = findClipButton(driver, wait);

            if (clipButton == null) {
                System.out.println("Clip button not found - video may not support clips");
                return null;
            }

            System.out.println("Found clip button, clicking...");
            clipButton.click();

            // Wait for clip dialog
            Thread.sleep(2000);
            wait.until(
                    ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector(
                                    "ytd-clip-creation-renderer, #clip-creation-container")));

            System.out.println("Clip dialog opened");

            // Set clip title
            String clipTitle = setClipTitle(driver, wait);
            System.out.println("Set clip title: " + clipTitle);

            // Configure 15-second duration
            configureClipDuration(driver, wait);

            // Create clip
            System.out.println("Creating clip...");
            String clipUrl = createAndShareClip(driver, wait);

            return clipUrl;

        } catch (Exception e) {
            System.err.println("Clip creation error: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private WebElement findClipButton(WebDriver driver, WebDriverWait wait) {
        // Try multiple selectors
        String[] selectors = {
            "button[aria-label*='Clip']",
            "button[aria-label*='clip']",
            ".ytp-clip-button",
            "button[title*='Clip']",
            "#button[aria-label*='Clip']"
        };

        for (String selector : selectors) {
            try {
                WebElement button = driver.findElement(By.cssSelector(selector));
                if (button.isDisplayed() && button.isEnabled()) {
                    return button;
                }
            } catch (Exception e) {
                // Try next selector
            }
        }

        // Try right-click menu
        try {
            System.out.println("Trying right-click method...");
            WebElement videoPlayer = driver.findElement(By.tagName("video"));
            Actions actions = new Actions(driver);
            actions.contextClick(videoPlayer).perform();
            Thread.sleep(1000);

            WebElement clipOption =
                    driver.findElement(
                            By.xpath(
                                    "//div[contains(text(), 'Clip') or contains(text(), 'clip')]"));
            return clipOption;

        } catch (Exception e) {
            System.out.println("Right-click method failed");
        }

        return null;
    }

    private String setClipTitle(WebDriver driver, WebDriverWait wait) {
        try {
            // Get original video title
            WebElement videoTitle =
                    driver.findElement(By.cssSelector("h1.title yt-formatted-string"));
            String title = videoTitle.getText();

            // Find title input field
            List<WebElement> inputs =
                    driver.findElements(
                            By.cssSelector("input[placeholder*='title'], input#input-1"));

            for (WebElement input : inputs) {
                if (input.isDisplayed()) {
                    input.clear();
                    input.sendKeys(title);
                    return title;
                }
            }

            return "Test Clip";

        } catch (Exception e) {
            System.out.println("Could not set custom title: " + e.getMessage());
            return "Test Clip";
        }
    }

    private void configureClipDuration(WebDriver driver, WebDriverWait wait) {
        try {
            System.out.println("Setting clip duration to 15 seconds...");

            // Method 1: Try time input fields
            List<WebElement> timeInputs =
                    driver.findElements(By.cssSelector("input[type='text'][aria-label*='time']"));

            if (timeInputs.size() >= 2) {
                timeInputs.get(0).clear();
                timeInputs.get(0).sendKeys("0:00");
                timeInputs.get(1).clear();
                timeInputs.get(1).sendKeys("0:15");
                System.out.println("Set duration using time inputs");
                return;
            }

            // Method 2: Try sliders
            List<WebElement> sliders = driver.findElements(By.cssSelector("div[role='slider']"));

            if (sliders.size() >= 2) {
                Actions actions = new Actions(driver);

                // Move start to beginning
                actions.clickAndHold(sliders.get(0)).moveByOffset(-500, 0).release().perform();

                Thread.sleep(500);

                // Move end to 15 seconds
                actions.clickAndHold(sliders.get(1))
                        .moveByOffset(-400, 0)
                        .moveByOffset(50, 0)
                        .release()
                        .perform();

                System.out.println("Set duration using sliders");
                return;
            }

            System.out.println("Using default clip duration");

        } catch (Exception e) {
            System.out.println("Could not configure duration: " + e.getMessage());
        }
    }

    private String createAndShareClip(WebDriver driver, WebDriverWait wait) {
        try {
            // Find and click share/create button
            String[] buttonSelectors = {
                "button[aria-label*='Share clip']",
                "button[aria-label*='Create']",
                "tp-yt-paper-button#button[aria-label*='Share']",
                "button:contains('Share')"
            };

            WebElement shareButton = null;
            for (String selector : buttonSelectors) {
                try {
                    shareButton = driver.findElement(By.cssSelector(selector));
                    if (shareButton.isDisplayed() && shareButton.isEnabled()) {
                        break;
                    }
                } catch (Exception e) {
                    // Try next
                }
            }

            if (shareButton != null) {
                shareButton.click();
                Thread.sleep(3000);

                // Look for clip URL
                List<WebElement> urlInputs =
                        driver.findElements(By.cssSelector("input[value*='youtube.com/clip']"));

                for (WebElement input : urlInputs) {
                    String clipUrl = input.getAttribute("value");
                    if (clipUrl != null && clipUrl.contains("/clip/")) {
                        return clipUrl;
                    }
                }
            }

            // Check if URL changed to clip URL
            String currentUrl = driver.getCurrentUrl();
            if (currentUrl.contains("/clip/")) {
                return currentUrl;
            }

            return null;

        } catch (Exception e) {
            System.err.println("Failed to create/share clip: " + e.getMessage());
            return null;
        }
    }
}
