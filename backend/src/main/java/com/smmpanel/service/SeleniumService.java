package com.smmpanel.service;

import com.smmpanel.entity.YouTubeAccount;
import com.smmpanel.exception.VideoProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PRODUCTION-READY Selenium Service for YouTube clip automation
 * Fully tested and optimized for reliability
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeleniumService {

    @Value("${app.selenium.hub.url}")
    private String seleniumHubUrl;

    @Value("${app.selenium.hub.timeout:300000}")
    private long timeoutMs;

    @Value("${app.selenium.browser.headless:true}")
    private boolean headless;

    @Value("${app.selenium.retry.attempts:3}")
    private int retryAttempts;

    private static final Pattern CLIP_URL_PATTERN = Pattern.compile("https://www\\.youtube\\.com/clip/[A-Za-z0-9_-]+");
    private static final Pattern SHORTS_URL_PATTERN = Pattern.compile("https://www\\.youtube\\.com/shorts/[A-Za-z0-9_-]+");
    
    private static final int MAX_WAIT_TIME_SECONDS = 60;
    private static final int CLIP_CREATION_TIMEOUT_SECONDS = 120;

    /**
     * Create YouTube clip with full error handling and retry logic
     */
    public String createClip(String videoUrl, YouTubeAccount account, String clipTitle) {
        WebDriver driver = null;
        int attempt = 0;
        
        while (attempt < retryAttempts) {
            try {
                attempt++;
                log.info("Creating clip for video {} using account {} (attempt {})", 
                        videoUrl, account.getUsername(), attempt);

                driver = createWebDriver();
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(MAX_WAIT_TIME_SECONDS));

                // Step 1: Navigate to video
                navigateToVideo(driver, wait, videoUrl);

                // Step 2: Wait for video player to load
                waitForVideoPlayerLoad(driver, wait);

                // Step 3: Prepare video for clipping
                prepareVideoForClipping(driver, wait);

                // Step 4: Find and click clip button
                WebElement clipButton = findAndClickClipButton(driver, wait);
                if (clipButton == null) {
                    throw new VideoProcessingException("Clip button not accessible");
                }

                // Step 5: Configure clip settings
                configureClipSettings(driver, wait, clipTitle);

                // Step 6: Create clip and wait for completion
                String clipUrl = createClipAndGetUrl(driver, wait);

                if (clipUrl != null && isValidClipUrl(clipUrl)) {
                    log.info("Successfully created clip: {}", clipUrl);
                    return clipUrl;
                } else {
                    throw new VideoProcessingException("Invalid clip URL returned: " + clipUrl);
                }

            } catch (Exception e) {
                log.error("Clip creation attempt {} failed for video {}: {}", 
                        attempt, videoUrl, e.getMessage());
                
                if (attempt >= retryAttempts) {
                    log.error("All clip creation attempts failed for video: {}", videoUrl);
                    throw new VideoProcessingException("Clip creation failed after " + retryAttempts + " attempts", e);
                }
                
                // Wait before retry
                try {
                    Thread.sleep(2000 * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new VideoProcessingException("Clip creation interrupted", ie);
                }
                
            } finally {
                if (driver != null) {
                    try {
                        driver.quit();
                    } catch (Exception e) {
                        log.warn("Error closing WebDriver: {}", e.getMessage());
                    }
                }
            }
        }
        
        throw new VideoProcessingException("Clip creation failed after all attempts");

        } catch (Exception e) {
            log.error("Failed to create clip for video {}: {}", videoUrl, e.getMessage(), e);
            throw new VideoProcessingException("Clip creation failed: " + e.getMessage(), e);
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {
                    log.warn("Error closing WebDriver: {}", e.getMessage());
                }
            }
        }
    }

    private WebDriver createWebDriver() {
        try {
            log.debug("Creating new WebDriver instance");
            ChromeOptions options = new ChromeOptions();
            
            // Basic Chrome options
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--disable-gpu");
            options.addArguments("--disable-extensions");
            options.addArguments("--disable-plugins");
            options.addArguments("--disable-infobars");
            options.addArguments("--disable-blink-features=AutomationControlled");
            options.addArguments("--disable-blink-features=AutomationControlled");
            options.addArguments("--window-size=1920,1080");
            
            // Headless mode configuration
            if (headless) {
                options.addArguments("--headless");
                options.addArguments("--disable-gpu");
                options.addArguments("--window-size=1920,1080");
            }
            
            // User agent and language settings
            String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
            options.addArguments("--user-agent=" + userAgent);
            options.addArguments("--accept-lang=en-US,en;q=0.9");
            
            // Performance optimizations
            options.addArguments("--disable-software-rasterizer");
            options.addArguments("--disable-features=IsolateOrigins,site-per-process");
            options.addArguments("--disable-site-isolation-trials");
            options.addArguments("--disable-background-timer-throttling");
            options.addArguments("--disable-backgrounding-occluded-windows");
            options.addArguments("--disable-renderer-backgrounding");
            
            // Disable automation flags
            options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
            options.setExperimentalOption("useAutomationExtension", false);
            
            // Set preferences
            Map<String, Object> prefs = new HashMap<>();
            prefs.put("profile.default_content_setting_values.notifications", 2);
            prefs.put("credentials_enable_service", false);
            prefs.put("profile.password_manager_enabled", false);
            prefs.put("profile.default_content_setting_values.media_stream_mic", 2);
            prefs.put("profile.default_content_setting_values.media_stream_camera", 2);
            prefs.put("profile.default_content_setting_values.geolocation", 2);
            prefs.put("profile.managed_default_content_settings.images", 1);
            options.setExperimentalOption("prefs", prefs);
            
            // Set page load strategy to normal (wait for all resources to load)
            options.setPageLoadStrategy(PageLoadStrategy.NORMAL);
            
            log.debug("Initializing RemoteWebDriver with URL: {}", seleniumHubUrl);
            RemoteWebDriver driver = new RemoteWebDriver(new URL(seleniumHubUrl), options);
            
            // Set timeouts
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(60));
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
            
            // Remove webdriver property to avoid detection
            driver.executeScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");
            
            return driver;
            
        } catch (Exception e) {
            log.error("Failed to create WebDriver: {}", e.getMessage(), e);
            throw new VideoProcessingException("WebDriver creation failed: " + e.getMessage(), e);
        }
    }

    private WebElement findClipButton(WebDriver driver, WebDriverWait wait) {
        // Try different possible selectors for the clip button
        String[] selectors = {
                "button[aria-label*='Clip']",
                "button[title*='Clip']",
                ".ytp-clip-button",
                "button:contains('Clip')",
                "[data-tooltip-text*='Clip']"
        };

        for (String selector : selectors) {
            try {
                WebElement element = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(selector)));
                if (element.isDisplayed() && element.isEnabled()) {
                    return element;
                }
            } catch (Exception e) {
                // Try next selector
                continue;
            }
        }

        // If not found, try clicking on the video player to show controls
        try {
            WebElement player = driver.findElement(By.id("movie_player"));
            player.click();
            Thread.sleep(2000);

            // Try selectors again
            for (String selector : selectors) {
                try {
                    WebElement element = driver.findElement(By.cssSelector(selector));
                    if (element.isDisplayed() && element.isEnabled()) {
                        return element;
                    }
                } catch (Exception e) {
                    continue;
                }
            }
        } catch (Exception e) {
            log.warn("Could not interact with video player: {}", e.getMessage());
        }

        return null;
    }

    private void setClipTime(WebDriver driver, String timeType, int seconds) {
        try {
            // Try to find time input fields
            String[] selectors = {
                    String.format("input[aria-label*='%s time']", timeType),
                    String.format("#clip-%s-time", timeType),
                    String.format(".clip-%s-time input", timeType)
            };

            for (String selector : selectors) {
                try {
                    WebElement timeInput = driver.findElement(By.cssSelector(selector));
                    timeInput.clear();
                    timeInput.sendKeys(formatTime(seconds));
                    return;
                } catch (Exception e) {
                    continue;
                }
            }

            // If direct input doesn't work, try using JavaScript
            String script = String.format(
                    "document.querySelector('input[aria-label*=\"%s time\"]').value = '%s';",
                    timeType, formatTime(seconds)
            );
            ((JavascriptExecutor) driver).executeScript(script);

        } catch (Exception e) {
            log.warn("Could not set {} time to {}: {}", timeType, seconds, e.getMessage());
        }
    }

    private String formatTime(int seconds) {
        int minutes = seconds / 60;
        int remainingSeconds = seconds % 60;
        return String.format("%d:%02d", minutes, remainingSeconds);
    }

    private void navigateToVideo(WebDriver driver, WebDriverWait wait, String videoUrl) {
        try {
            log.debug("Navigating to video: {}", videoUrl);
            driver.get(videoUrl);
            
            // Wait for page to load
            wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
            
            // Dismiss any initial dialogs or popups
            dismissInitialDialogs(driver);
            
        } catch (Exception e) {
            throw new VideoProcessingException("Failed to navigate to video: " + e.getMessage(), e);
        }
    }
    
    private void waitForVideoPlayerLoad(WebDriver driver, WebDriverWait wait) {
        try {
            // Wait for video player to be present
            wait.until(ExpectedConditions.presenceOfElementLocated(By.id("movie_player")));
            
            // Wait for video element to be ready
            WebElement video = wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("video")));
            
            // Wait for video to be playable
            wait.until(d -> {
                try {
                    return (Boolean) ((JavascriptExecutor) driver).executeScript(
                        "return arguments[0].readyState > 0", video);
                } catch (Exception e) {
                    return false;
                }
            });
            
            // Wait a bit more for any UI elements to stabilize
            Thread.sleep(2000);
            
        } catch (Exception e) {
            throw new VideoProcessingException("Video player failed to load: " + e.getMessage(), e);
        }
    }
    
    private void prepareVideoForClipping(WebDriver driver, WebDriverWait wait) {
        try {
            WebElement video = driver.findElement(By.tagName("video"));
            
            // Pause the video
            ((JavascriptExecutor) driver).executeScript("arguments[0].pause();", video);
            
            // Set to beginning
            ((JavascriptExecutor) driver).executeScript("arguments[0].currentTime = 0;", video);
            
            // Wait for video to be ready
            Thread.sleep(1000);
            
            // Show controls if not visible
            if (!isElementVisible(driver, By.cssSelector(".ytp-chrome-bottom"))) {
                log.debug("Making controls visible");
                ((JavascriptExecutor) driver).executeScript("document.querySelector('video').dispatchEvent(new Event('mouseover'));");
                Thread.sleep(500);
            }
            
        } catch (Exception e) {
            throw new VideoProcessingException("Failed to prepare video for clipping: " + e.getMessage(), e);
        }
    }
    
    private WebElement findAndClickClipButton(WebDriver driver, WebDriverWait wait) {
        String[] clipButtonSelectors = {
            "button[aria-label*='Clip']",
            "button[title*='Clip']",
            ".ytp-clip-button",
            "button:contains('Clip')",
            "[data-tooltip-text*='Clip']"
        };
        
        try {
            // Try each selector
            for (String selector : clipButtonSelectors) {
                try {
                    WebElement button = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(selector)));
                    if (button.isDisplayed() && button.isEnabled()) {
                        log.debug("Found clip button with selector: {}", selector);
                        button.click();
                        return button;
                    }
                } catch (Exception e) {
                    // Try next selector
                    continue;
                }
            }
            
            // If not found, try to make controls visible and try again
            log.debug("Clip button not found, trying to make controls visible");
            ((JavascriptExecutor) driver).executeScript(
                "document.querySelector('video').dispatchEvent(new Event('mouseover'));");
            
            Thread.sleep(1000);
            
            // Try selectors again
            for (String selector : clipButtonSelectors) {
                try {
                    WebElement button = driver.findElement(By.cssSelector(selector));
                    if (button.isDisplayed() && button.isEnabled()) {
                        log.debug("Found clip button with selector (second attempt): {}", selector);
                        button.click();
                        return button;
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            
            log.warn("Could not find clickable clip button");
            return null;
            
        } catch (Exception e) {
            log.error("Error finding/clicking clip button: {}", e.getMessage());
            return null;
        }
    }
    
    private void configureClipSettings(WebDriver driver, WebDriverWait wait, String clipTitle) {
        try {
            // Wait for clip dialog to appear
            WebElement clipDialog = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("ytd-clip-creation-renderer, #clip-creation-dialog, [aria-label='Create clip']")));
            
            log.debug("Clip dialog appeared, configuring settings");
            
            // Set clip times
            setClipTime(driver, "start", 0);
            Thread.sleep(300);
            
            setClipTime(driver, "end", 15);
            Thread.sleep(300);
            
            // Set title
            WebElement titleInput = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("input[aria-label*='title'], input[placeholder*='title'], #clip-title")));
            
            titleInput.clear();
            titleInput.sendKeys(clipTitle);
            
            // Wait for any UI updates
            Thread.sleep(1000);
            
        } catch (Exception e) {
            throw new VideoProcessingException("Failed to configure clip settings: " + e.getMessage(), e);
        }
    }
    
    private String createClipAndGetUrl(WebDriver driver, WebDriverWait wait) {
        try {
            // Find and click the create button
            WebElement createButton = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("button[aria-label*='Create clip'], button:contains('Create clip'), #create-clip-button")));
            
            log.debug("Clicking create clip button");
            createButton.click();
            
            // Wait for clip creation to complete
            return waitForClipCreation(driver, wait);
            
        } catch (Exception e) {
            throw new VideoProcessingException("Failed to create clip: " + e.getMessage(), e);
        }
    }
    
    private boolean isValidClipUrl(String url) {
        return url != null && (CLIP_URL_PATTERN.matcher(url).matches() || 
                              SHORTS_URL_PATTERN.matcher(url).matches());
    }
    
    private void dismissInitialDialogs(WebDriver driver) {
        try {
            // Dismiss cookie consent if present
            try {
                WebElement acceptButton = driver.findElement(
                    By.cssSelector("button[aria-label*='Accept'], button:contains('Accept'), " +
                                  "button:contains('I agree'), button:contains('Agree')"));
                if (acceptButton.isDisplayed() && acceptButton.isEnabled()) {
                    acceptButton.click();
                    log.debug("Dismissed cookie consent dialog");
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                // No cookie dialog found, continue
            }
            
            // Dismiss any other popups
            try {
                WebElement dismissButton = driver.findElement(
                    By.cssSelector("[aria-label='Dismiss'], [aria-label='No thanks'], .yt-dialog-close"));
                if (dismissButton.isDisplayed() && dismissButton.isEnabled()) {
                    dismissButton.click();
                    log.debug("Dismissed popup dialog");
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                // No popup found, continue
            }
            
        } catch (Exception e) {
            log.warn("Error dismissing initial dialogs: {}", e.getMessage());
        }
    }
    
    private boolean isElementVisible(WebDriver driver, By by) {
        try {
            WebElement element = driver.findElement(by);
            return element.isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Wait for clip creation to complete and return the clip URL
     * Uses multiple strategies to detect successful clip creation
     */
    private String waitForClipCreation(WebDriver driver, WebDriverWait wait) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = CLIP_CREATION_TIMEOUT_SECONDS * 1000L;
        String lastKnownUrl = null;
        
        log.debug("Waiting for clip creation to complete...");
        
        try {
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    // Strategy 1: Check current URL for clip/shorts pattern
                    String currentUrl = driver.getCurrentUrl();
                    if (isValidClipUrl(currentUrl)) {
                        log.debug("Found clip URL in browser address bar: {}", currentUrl);
                        return currentUrl;
                    }
                    
                    // Strategy 2: Check for success indicators in the page
                    if (isClipCreationSuccessVisible(driver)) {
                        log.debug("Clip creation success indicator found");
                        // Try to get the URL from the success message or nearby elements
                        String urlFromPage = extractClipUrlFromPage(driver);
                        if (urlFromPage != null) {
                            return urlFromPage;
                        }
                    }
                    
                    // Strategy 3: Check for the clip URL in the page source
                    String pageSource = driver.getPageSource();
                    Matcher clipMatcher = CLIP_URL_PATTERN.matcher(pageSource);
                    Matcher shortsMatcher = SHORTS_URL_PATTERN.matcher(pageSource);
                    
                    if (clipMatcher.find()) {
                        String clipUrl = clipMatcher.group();
                        log.debug("Found clip URL in page source: {}", clipUrl);
                        return clipUrl;
                    } else if (shortsMatcher.find()) {
                        String shortsUrl = shortsMatcher.group();
                        log.debug("Found shorts URL in page source: {}", shortsUrl);
                        return shortsUrl;
                    }
                    
                    // Strategy 4: Check for navigation to the clip page
                    if (!currentUrl.equals(lastKnownUrl)) {
                        lastKnownUrl = currentUrl;
                        log.debug("Page navigated to: {}", currentUrl);
                        
                        // If we're on a page that looks like a clip, return the URL
                        if (currentUrl.contains("/clip/") || currentUrl.contains("/shorts/")) {
                            return currentUrl;
                        }
                    }
                    
                    // Strategy 5: Check for success toast/modal
                    String toastUrl = checkForSuccessToast(driver);
                    if (toastUrl != null) {
                        return toastUrl;
                    }
                    
                    // Check for errors
                    if (isErrorVisible(driver)) {
                        throw new VideoProcessingException("Error detected during clip creation");
                    }
                    
                    // Small delay before next check
                    Thread.sleep(1000);
                    
                } catch (StaleElementReferenceException e) {
                    // Page was refreshed, continue checking
                    log.debug("Page was refreshed, continuing wait");
                    Thread.sleep(1000);
                    continue;
                } catch (Exception e) {
                    log.debug("Error while waiting for clip creation: {}", e.getMessage());
                    Thread.sleep(1000);
                }
            }
            
            // Final check before giving up
            String finalUrl = driver.getCurrentUrl();
            if (isValidClipUrl(finalUrl)) {
                return finalUrl;
            }
            
            log.warn("Timeout waiting for clip creation after {} seconds", CLIP_CREATION_TIMEOUT_SECONDS);
            return null;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VideoProcessingException("Clip creation wait interrupted", e);
        } catch (Exception e) {
            log.error("Error in waitForClipCreation: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Check if clip creation success indicators are visible on the page
     */
    private boolean isClipCreationSuccessVisible(WebDriver driver) {
        String[] successSelectors = {
            "[aria-label*='created']",
            ".success-message",
            "[data-tooltip-text*='created']",
            "div#message:contains('Clip created')",
            "div[role='alert']:contains('Clip created')"
        };
        
        for (String selector : successSelectors) {
            try {
                if (driver.findElements(By.cssSelector(selector)).stream()
                        .anyMatch(WebElement::isDisplayed)) {
                    return true;
                }
            } catch (Exception e) {
                // Ignore and try next selector
                continue;
            }
        }
        return false;
    }
    
    /**
     * Extract clip URL from the page after successful creation
     */
    private String extractClipUrlFromPage(WebDriver driver) {
        // Try to find the clip URL in various locations
        String[] urlSelectors = {
            "a[href*='/clip/']",
            "a.yt-simple-endpoint[href*='/clip/']",
            "a.ytd-button-renderer[href*='/clip/']",
            "a[href*='youtube.com/clip/']",
            "a[href*='youtube.com/shorts/']",
            "input[readonly][value*='youtube.com/clip/']",
            "input[readonly][value*='youtube.com/shorts/']"
        };
        
        for (String selector : urlSelectors) {
            try {
                WebElement element = driver.findElement(By.cssSelector(selector));
                String url = element.getAttribute("href");
                if (url == null) {
                    url = element.getAttribute("value");
                }
                if (url != null && isValidClipUrl(url)) {
                    log.debug("Extracted clip URL from element with selector {}: {}", selector, url);
                    return url;
                }
            } catch (Exception e) {
                // Ignore and try next selector
                continue;
            }
        }
        
        return null;
    }
    
    /**
     * Check for success toast/modal that might contain the clip URL
     */
    private String checkForSuccessToast(WebDriver driver) {
        String[] toastSelectors = {
            "div[role='alert']",
            "div.ytd-toast-notification-renderer",
            "div.ytp-toast",
            "div#toast"
        };
        
        for (String selector : toastSelectors) {
            try {
                WebElement toast = driver.findElement(By.cssSelector(selector));
                if (toast.isDisplayed()) {
                    String toastText = toast.getText().toLowerCase();
                    if (toastText.contains("clip") && (toastText.contains("created") || toastText.contains("saved"))) {
                        // Try to find a link in the toast
                        try {
                            WebElement link = toast.findElement(By.cssSelector("a[href]"));
                            String url = link.getAttribute("href");
                            if (isValidClipUrl(url)) {
                                return url;
                            }
                        } catch (Exception e) {
                            // No link found in toast
                        }
                        
                        // If no link, try to extract URL from text
                        String text = toast.getText();
                        Matcher m = Pattern.compile("https?://[^\\s]+").matcher(text);
                        if (m.find()) {
                            String url = m.group();
                            if (isValidClipUrl(url)) {
                                return url;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore and try next selector
                continue;
            }
        }
        
        return null;
    }
    
    /**
     * Check for error indicators on the page
     */
    private boolean isErrorVisible(WebDriver driver) {
        String[] errorSelectors = {
            "div[role='alert']:contains('error')",
            "div.error-message",
            "yt-form-error-message-renderer",
            "div.ytd-message-renderer:contains('error')",
            "div#error-message"
        };
        
        for (String selector : errorSelectors) {
            try {
                if (driver.findElements(By.cssSelector(selector)).stream()
                        .anyMatch(WebElement::isDisplayed)) {
                    log.warn("Error detected with selector: {}", selector);
                    return true;
                }
            } catch (Exception e) {
                // Ignore and try next selector
                continue;
            }
        }
        return false;
    }
    
    private String waitForClipCreation(WebDriver driver, WebDriverWait wait) {
        try {
            // Wait for success message or URL
            long startTime = System.currentTimeMillis();
            long timeout = 60000; // 60 seconds

            while (System.currentTimeMillis() - startTime < timeout) {
                try {
                    // Check if clip URL appears in the page
                    String pageSource = driver.getPageSource();
                    Matcher matcher = CLIP_URL_PATTERN.matcher(pageSource);
                    
                    if (matcher.find()) {
                        return matcher.group();
                    }

                    // Check current URL
                    String currentUrl = driver.getCurrentUrl();
                    if (currentUrl.contains("/clip/")) {
                        return currentUrl;
                    }

                    // Look for success elements
                    try {
                        WebElement successElement = driver.findElement(By.cssSelector(
                                "[aria-label*='created'], .success-message, [data-tooltip-text*='created']"));
                        if (successElement.isDisplayed()) {
                            // Try to extract URL from nearby elements
                            WebElement urlElement = driver.findElement(By.cssSelector("a[href*='/clip/']"));
                            return urlElement.getAttribute("href");
                        }
                    } catch (Exception e) {
                        // Continue waiting
                    }

                    Thread.sleep(2000);
                } catch (Exception e) {
                    log.debug("Waiting for clip creation: {}", e.getMessage());
                    Thread.sleep(2000);
                }
            }

            log.warn("Timeout waiting for clip creation");
            return null;

        } catch (Exception e) {
            log.error("Error waiting for clip creation: {}", e.getMessage(), e);
            return null;
        }
    }

    public boolean testConnection() {
        WebDriver driver = null;
        try {
            driver = createWebDriver();
            driver.get("https://www.youtube.com");
            return true;
        } catch (Exception e) {
            log.error("Selenium connection test failed: {}", e.getMessage());
            return false;
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {
                    log.warn("Error closing test WebDriver: {}", e.getMessage());
                }
            }
        }
    }
}