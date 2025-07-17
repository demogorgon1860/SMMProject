package com.smmpanel.service;

import com.smmpanel.entity.YouTubeAccount;
import com.smmpanel.exception.VideoProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class SeleniumService {

    @Value("${app.selenium.hub.url}")
    private String seleniumHubUrl;

    @Value("${app.selenium.hub.timeout:300000}")
    private long timeoutMs;

    private static final Pattern CLIP_URL_PATTERN = Pattern.compile("https://www\\.youtube\\.com/clip/[A-Za-z0-9_-]+");

    public String createClip(String videoUrl, YouTubeAccount account, String clipTitle) {
        WebDriver driver = null;
        try {
            driver = createWebDriver();
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

            log.info("Creating clip for video {} using account {}", videoUrl, account.getUsername());

            // Navigate to video
            driver.get(videoUrl);
            
            // Wait for video to load
            wait.until(ExpectedConditions.presenceOfElementLocated(By.id("movie_player")));
            
            // Wait a bit for the video to initialize
            Thread.sleep(3000);

            // Pause the video first
            WebElement video = driver.findElement(By.tagName("video"));
            ((JavascriptExecutor) driver).executeScript("arguments[0].pause();", video);

            // Set video to the beginning
            ((JavascriptExecutor) driver).executeScript("arguments[0].currentTime = 0;", video);
            Thread.sleep(1000);

            // Try to find and click the clip button
            WebElement clipButton = findClipButton(driver, wait);
            if (clipButton == null) {
                throw new VideoProcessingException("Clip button not found - video may not support clipping");
            }

            clipButton.click();
            log.debug("Clicked clip button");

            // Wait for clip creation dialog
            WebElement clipDialog = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("ytd-clip-creation-renderer, #clip-creation-dialog")));

            // Set clip start time to 0
            setClipTime(driver, "start", 0);
            Thread.sleep(500);

            // Set clip end time to 15 seconds
            setClipTime(driver, "end", 15);
            Thread.sleep(500);

            // Set clip title
            WebElement titleInput = driver.findElement(By.cssSelector(
                    "input[aria-label*='title'], input[placeholder*='title'], #clip-title"));
            titleInput.clear();
            titleInput.sendKeys(clipTitle);
            
            Thread.sleep(1000);

            // Click create clip button
            WebElement createButton = driver.findElement(By.cssSelector(
                    "button[aria-label*='Create clip'], button:contains('Create clip'), #create-clip-button"));
            createButton.click();

            log.debug("Clicked create clip button");

            // Wait for clip creation to complete and get the URL
            String clipUrl = waitForClipCreation(driver, wait);
            
            if (clipUrl != null) {
                log.info("Successfully created clip: {}", clipUrl);
                return clipUrl;
            } else {
                throw new VideoProcessingException("Failed to get clip URL after creation");
            }

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
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--disable-gpu");
            options.addArguments("--window-size=1920,1080");
            options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            
            // Disable notifications
            Map<String, Object> prefs = new HashMap<>();
            prefs.put("profile.default_content_setting_values.notifications", 2);
            options.setExperimentalOption("prefs", prefs);

            return new RemoteWebDriver(new URL(seleniumHubUrl), options);
        } catch (Exception e) {
            log.error("Failed to create WebDriver: {}", e.getMessage(), e);
            throw new VideoProcessingException("WebDriver creation failed", e);
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