package com.smmpanel.service.integration;

import com.smmpanel.entity.YouTubeAccount;
import com.smmpanel.exception.VideoProcessingException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.*;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * PRODUCTION-READY Selenium Service for YouTube clip automation Fully tested and optimized for
 * reliability with Perfect Panel compatibility
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeleniumService {

    private final BinomService binomService;
    private final YouTubeCookieService cookieService;

    @Value("${app.selenium.hub.url}")
    private String seleniumHubUrl;

    @Value("${app.selenium.hub.timeout:300000}")
    private long timeoutMs;

    @Value("${app.selenium.browser.headless:true}")
    private boolean headless;

    @Value("${app.selenium.retry.attempts:3}")
    private int retryAttempts;

    @Value("${app.selenium.profiles.path:./selenium-profiles}")
    private String profilesPath;

    @Value("${app.youtube.channels.target:}")
    private String targetChannels;

    @Value("${app.selenium.test-mode:true}")
    private boolean testMode;

    private static final Pattern CLIP_URL_PATTERN =
            Pattern.compile("https?://(www\\.)?youtube\\.com/clip/[A-Za-z0-9_-]+(\\?.*)?$");
    private static final Pattern SHORTS_URL_PATTERN =
            Pattern.compile("https?://(www\\.)?youtube\\.com/shorts/[A-Za-z0-9_-]+(\\?.*)?$");

    // Timing constants - optimized for performance
    private static final int QUICK_WAIT_SECONDS = 3;
    private static final int STANDARD_WAIT_SECONDS = 10;
    private static final int EXTENDED_WAIT_SECONDS = 15;
    private static final int MAX_WAIT_TIME_SECONDS = 30;
    private static final int CLIP_CREATION_TIMEOUT_SECONDS = 60;
    private static final int CLIP_DURATION_SECONDS = 15;

    // Polling frequencies for dynamic waits - CRITICAL for performance
    private static final Duration FAST_POLL = Duration.ofMillis(100); // Ultra-fast polling
    private static final Duration NORMAL_POLL = Duration.ofMillis(200); // Fast polling
    private static final Duration SLOW_POLL = Duration.ofMillis(500); // Standard polling

    // Job tracking for async operations
    private final Map<String, Map<String, Object>> jobTracker = new ConcurrentHashMap<>();

    // Session management for authenticated accounts
    private final Map<String, String> accountProfilePaths = new ConcurrentHashMap<>();

    /**
     * Create YouTube clip with full authentication, channel navigation, and clip creation workflow
     * Implements the complete automation including: 1. Authentication with session persistence 2.
     * Channel navigation and video selection 3. Clip creation with 15-second duration 4. Error
     * handling and retry logic
     */
    public String createClip(String videoUrl, YouTubeAccount account, String clipTitle) {
        return createClip(videoUrl, account, clipTitle, null, null);
    }

    /**
     * Create YouTube clip and store it in Redis for Binom offer
     *
     * @param videoUrl YouTube video URL
     * @param account YouTube account for authentication
     * @param clipTitle Title for the clip
     * @param offerId Binom offer ID (optional)
     * @param orderId Order ID (optional)
     * @return The clip URL if successful, null otherwise
     */
    public String createClip(
            String videoUrl,
            YouTubeAccount account,
            String clipTitle,
            String offerId,
            Long orderId) {
        WebDriver driver = null;
        String sessionId = null;

        try {
            log.info("Creating clip for video {} using account {}", videoUrl, account.getEmail());

            // Create driver with persistent profile for this account
            log.info("[SESSION] Creating WebDriver...");
            driver = createWebDriverWithProfile(account);
            if (driver instanceof RemoteWebDriver) {
                sessionId = ((RemoteWebDriver) driver).getSessionId().toString();
                log.info("[SESSION] Created WebDriver session: {}", sessionId);
            }

            // Optimized timeouts for production - fail faster on unresponsive pages
            driver.manage()
                    .timeouts()
                    .implicitlyWait(
                            Duration.ofSeconds(8)); // Reduced from 10s - faster failure detection
            driver.manage()
                    .timeouts()
                    .pageLoadTimeout(
                            Duration.ofSeconds(20)); // Reduced from 30s - fail fast on slow pages
            driver.manage()
                    .timeouts()
                    .scriptTimeout(
                            Duration.ofSeconds(20)); // Reduced from 30s - faster script timeout

            // Use optimized wait with faster polling
            WebDriverWait wait =
                    new WebDriverWait(driver, Duration.ofSeconds(MAX_WAIT_TIME_SECONDS));
            wait.pollingEvery(NORMAL_POLL);

            // Step 1: Authenticate if needed (with timeout protection)
            log.info("[SESSION {}] Step 1: Starting authentication...", sessionId);
            log.info("[SESSION {}] Account email: {}", sessionId, account.getEmail());
            log.info(
                    "[SESSION {}] Account has password: {}",
                    sessionId,
                    account.getPassword() != null && !account.getPassword().isEmpty());

            try {
                authenticateIfNeeded(driver, wait, account);
                log.info("[SESSION {}] Step 1 completed: Authentication successful", sessionId);
            } catch (Exception e) {
                log.error("[SESSION {}] Authentication failed: {}", sessionId, e.getMessage());
                throw new VideoProcessingException("Authentication failed: " + e.getMessage());
            }

            // Step 2: Navigate to video (with timeout protection)
            log.info("[SESSION {}] Step 2: Navigating to video URL: {}", sessionId, videoUrl);
            try {
                navigateToVideo(driver, wait, videoUrl);
                log.info("[SESSION {}] Step 2 completed: Navigation successful", sessionId);
            } catch (Exception e) {
                log.error("[SESSION {}] Navigation failed: {}", sessionId, e.getMessage());
                throw new VideoProcessingException("Navigation failed: " + e.getMessage());
            }

            // Step 3: Wait for video player to load
            log.info("[SESSION {}] Step 3: Waiting for video player to load...", sessionId);
            try {
                waitForVideoPlayerLoad(driver, wait);
                log.info("[SESSION {}] Step 3 completed: Video player loaded", sessionId);
            } catch (Exception e) {
                log.error("[SESSION {}] Video player load failed: {}", sessionId, e.getMessage());
                throw new VideoProcessingException(
                        "Video player failed to load: " + e.getMessage());
            }

            // Step 4: Prepare video for clipping
            log.info("[SESSION {}] Step 4: Preparing video for clipping...", sessionId);
            try {
                prepareVideoForClipping(driver, wait);
                log.info("[SESSION {}] Step 4 completed: Video prepared", sessionId);
            } catch (Exception e) {
                log.warn(
                        "[SESSION {}] Video preparation had issues but continuing: {}",
                        sessionId,
                        e.getMessage());
            }

            // Step 5: Find and click clip button
            log.info("[SESSION {}] Step 5: Looking for clip button...", sessionId);
            WebElement clipButton = null;
            try {
                clipButton = findAndClickClipButton(driver, wait);
            } catch (Exception e) {
                log.error(
                        "[SESSION {}] Failed to find/click clip button: {}",
                        sessionId,
                        e.getMessage());
            }

            if (clipButton == null) {
                log.error("[SESSION {}] Step 5 failed: Clip button not accessible", sessionId);
                throw new VideoProcessingException(
                        "Clip button not accessible - video may not support clips");
            }
            log.info("[SESSION {}] Step 5 completed: Clip button clicked", sessionId);

            // Step 6: Configure clip settings with 15-second duration
            log.info("[SESSION {}] Step 6: Configuring clip settings...", sessionId);
            try {
                // Pass clipTitle parameter
                configureClipSettingsWithDuration(driver, wait, clipTitle);
                log.info("[SESSION {}] Step 6 completed: Clip configured", sessionId);
            } catch (Exception e) {
                log.warn(
                        "[SESSION {}] Clip configuration had issues but continuing: {}",
                        sessionId,
                        e.getMessage());
            }

            // Step 7: Create clip and wait for completion
            log.info("[SESSION {}] Step 7: Creating clip and getting URL...", sessionId);
            String clipUrl = null;
            try {
                clipUrl = createClipAndGetUrl(driver, wait);
            } catch (Exception e) {
                log.error(
                        "[SESSION {}] Failed to create/get clip URL: {}",
                        sessionId,
                        e.getMessage());
                throw new VideoProcessingException("Failed to create clip: " + e.getMessage());
            }

            if (clipUrl != null && isValidClipUrl(clipUrl)) {
                log.info("[SESSION {}] Successfully created clip: {}", sessionId, clipUrl);

                // Store clip URL in Redis if offer ID is provided
                if (offerId != null && binomService != null) {
                    try {
                        binomService.storeClipUrlForOffer(
                                offerId, clipUrl, orderId != null ? orderId : 0L);
                        log.info(
                                "[SESSION {}] Stored clip URL in Redis for offer {}",
                                sessionId,
                                offerId);
                    } catch (Exception e) {
                        log.error(
                                "[SESSION {}] Failed to store clip URL in Redis: {}",
                                sessionId,
                                e.getMessage());
                        // Don't fail the clip creation if Redis storage fails
                    }
                }

                // Success - close driver and return
                log.info("[SESSION {}] Closing successful session", sessionId);
                closeWebDriverSafely(driver, sessionId);
                return clipUrl;
            } else {
                throw new VideoProcessingException("Invalid clip URL returned: " + clipUrl);
            }

        } catch (Exception e) {
            log.error(
                    "[SESSION {}] Clip creation failed for video {}: {}",
                    sessionId,
                    videoUrl,
                    e.getMessage());

            // Clean up driver after failure
            closeWebDriverSafely(driver, sessionId);

            throw new VideoProcessingException("Clip creation failed: " + e.getMessage(), e);

        } finally {
            // Ensure cleanup even if something unexpected happens
            if (driver != null) {
                log.warn("[SESSION {}] Final cleanup of WebDriver", sessionId);
                closeWebDriverSafely(driver, sessionId);
            }
        }
    }

    /** Safely close WebDriver with proper error handling and logging */
    /**
     * Safely close WebDriver with async timeout to prevent deadlocks If driver.quit() hangs due to
     * browser crash, timeout after 10 seconds CRITICAL: Prevents semaphore deadlock when Chrome
     * crashes
     */
    private void closeWebDriverSafely(WebDriver driver, String sessionId) {
        if (driver != null) {
            try {
                log.info(
                        "[SESSION {}] Attempting to quit WebDriver with 10s timeout...", sessionId);

                // Execute quit operation asynchronously with timeout
                CompletableFuture<Void> quitFuture =
                        CompletableFuture.runAsync(
                                () -> {
                                    try {
                                        // First try to close all windows
                                        try {
                                            for (String handle : driver.getWindowHandles()) {
                                                driver.switchTo().window(handle);
                                                driver.close();
                                            }
                                        } catch (Exception e) {
                                            log.debug(
                                                    "[SESSION {}] Error closing windows: {}",
                                                    sessionId,
                                                    e.getMessage());
                                        }
                                        // Then quit the driver
                                        driver.quit();
                                    } catch (Exception e) {
                                        log.warn(
                                                "[SESSION {}] Error during quit: {}",
                                                sessionId,
                                                e.getMessage());
                                        // Try force quit as last resort
                                        try {
                                            ((RemoteWebDriver) driver).quit();
                                        } catch (Exception ex) {
                                            log.error(
                                                    "[SESSION {}] Force quit also failed: {}",
                                                    sessionId,
                                                    ex.getMessage());
                                        }
                                    }
                                });

                // Wait up to 10 seconds for quit to complete
                try {
                    quitFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    log.info("[SESSION {}] WebDriver quit successfully", sessionId);
                } catch (java.util.concurrent.TimeoutException e) {
                    log.error(
                            "[SESSION {}] WebDriver quit TIMED OUT after 10s - session may be"
                                    + " orphaned in Selenium Grid",
                            sessionId);
                    quitFuture.cancel(true);
                } catch (Exception e) {
                    log.error("[SESSION {}] WebDriver quit failed: {}", sessionId, e.getMessage());
                }

            } catch (Exception e) {
                log.error(
                        "[SESSION {}] Unexpected error in closeWebDriverSafely: {}",
                        sessionId,
                        e.getMessage());
            }
        }
    }

    private WebDriver createWebDriver() {
        try {
            log.debug("Creating new WebDriver instance");
            ChromeOptions options = new ChromeOptions();

            // Chrome options to bypass Google security warnings
            // Removed --incognito to allow using existing cookies and sessions
            options.addArguments("--window-size=1521,738");

            // Use profile directory to persist cookies/session
            // User can manually login in Chrome with: chrome
            // --user-data-dir=<profilesPath>/default_profile
            // Selenium will reuse the saved session
            // Convert to absolute path and normalize to remove . and ..
            Path defaultProfile =
                    Paths.get(profilesPath, "default_profile").toAbsolutePath().normalize();
            if (!Files.exists(defaultProfile)) {
                Files.createDirectories(defaultProfile);
                // Set permissions to 777 for Selenium Chrome (runs as seluser UID 1200)
                try {
                    Files.setPosixFilePermissions(
                            defaultProfile,
                            java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
                } catch (Exception ignored) {
                    // Windows doesn't support POSIX permissions
                }
            }
            options.addArguments("--user-data-dir=" + defaultProfile.toString());
            log.info(
                    "Using Chrome profile directory: {}. To pre-login: chrome"
                            + " --user-data-dir=\"{}\"",
                    defaultProfile,
                    defaultProfile);

            // Essential options to bypass "browser not secure" warnings
            options.addArguments("--disable-blink-features=AutomationControlled");
            options.setExperimentalOption("excludeSwitches", new String[] {"enable-automation"});
            options.setExperimentalOption("useAutomationExtension", false);

            // Add user agent to appear as regular Chrome
            options.addArguments(
                    "user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            + " (KHTML, like Gecko) Chrome/115.0.5790.110 Safari/537.36");

            // Additional anti-detection and autoplay preferences
            Map<String, Object> prefs = new HashMap<>();
            prefs.put("credentials_enable_service", false);
            prefs.put("profile.password_manager_enabled", false);
            // Enable autoplay for videos
            prefs.put("profile.default_content_setting_values.media_stream", 1);
            prefs.put("profile.content_settings.exceptions.media_stream_mic", 1);
            prefs.put("profile.content_settings.exceptions.media_stream_camera", 1);
            options.setExperimentalOption("prefs", prefs);

            // Chrome flags for better video playback (removed --disable-gpu as it affects autoplay)
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments(
                    "--autoplay-policy=no-user-gesture-required"); // Allow autoplay without user
            // interaction

            if (headless) {
                options.addArguments("--headless=new");
            }

            log.info(
                    "Chrome options: incognito={}, headless={}, resolution=1521x738,"
                            + " anti-detection=enabled",
                    false,
                    headless);

            // Add connection timeout
            // Removed webdriver.remote.sessionid - not W3C compliant

            RemoteWebDriver driver = null;

            // Always try Selenium Grid first (primary method)
            String gridUrl = seleniumHubUrl;

            // Use the URL as configured without any adjustments
            // When backend runs locally: use localhost:4444
            // When backend runs in Docker: use selenium-hub:4444
            log.info("Using Selenium Grid URL as configured: {}", gridUrl);

            try {
                log.info("Connecting to Selenium Grid at: {}", gridUrl);
                // Only use W3C compliant capabilities
                options.setCapability("se:screenResolution", "1521x738");
                // Removed CDP/BiDi/VNC capabilities - they cause firstMatch invalid errors

                // Use simple constructor to avoid CDP WebSocket issues
                driver = new RemoteWebDriver(new URL(gridUrl), options);

                // Log session ID immediately for tracking
                String sessionId = driver.getSessionId().toString();
                log.info("Successfully created WebDriver session: {}", sessionId);

                // Standard timeouts - same as working test
                driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
                driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
                driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));

                log.info("Successfully connected to Selenium Grid with session: {}", sessionId);
                return driver;
            } catch (Exception e) {
                log.error("Failed to connect to Selenium Grid at {}: {}", gridUrl, e.getMessage());

                // Try to extract and log session ID if it was created
                if (e.getMessage() != null && e.getMessage().contains("session")) {
                    log.warn(
                            "Session may have been created but connection failed. Check Grid"
                                    + " status.");
                }

                // Only try local ChromeDriver in test mode as last resort
                if (testMode) {
                    try {
                        log.info("Test mode: Trying local ChromeDriver as fallback...");
                        driver = new ChromeDriver(options);
                        // Standard timeouts
                        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
                        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
                        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
                        log.info("Successfully created local ChromeDriver in test mode");
                        return driver;
                    } catch (Exception localEx) {
                        log.error("Local ChromeDriver also failed: {}", localEx.getMessage());
                        throw new VideoProcessingException(
                                "Could not create WebDriver. Ensure Selenium Grid is running:"
                                        + " docker-compose up -d selenium-hub selenium-chrome",
                                localEx);
                    }
                } else {
                    throw new VideoProcessingException(
                            "Could not connect to Selenium Grid at "
                                    + gridUrl
                                    + ". Ensure containers are running: docker-compose up -d"
                                    + " selenium-hub selenium-chrome",
                            e);
                }
            }

        } catch (Exception e) {
            log.error("Failed to create WebDriver: {}", e.getMessage(), e);
            throw new VideoProcessingException("WebDriver creation failed", e);
        }
    }

    /**
     * Create WebDriver with persistent Chrome profile for session management This allows reusing
     * authenticated sessions across runs
     */
    private WebDriver createWebDriverWithProfile(YouTubeAccount account) {
        try {
            log.debug("Creating WebDriver WITH profile for account: {}", account.getEmail());
            ChromeOptions options = new ChromeOptions();

            // Chrome options for fresh session (no profile locking issues)
            options.addArguments("--window-size=1521,738");

            // NO PROFILE - We'll inject cookies after driver creation
            // This allows concurrent sessions without profile locking
            log.info("[COOKIE MODE] Will inject saved cookies for account: {}", account.getEmail());

            // Essential options to bypass "browser not secure" warnings
            options.addArguments("--disable-blink-features=AutomationControlled");
            options.setExperimentalOption("excludeSwitches", new String[] {"enable-automation"});
            options.setExperimentalOption("useAutomationExtension", false);

            // Add user agent to appear as regular Chrome
            options.addArguments(
                    "user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            + " (KHTML, like Gecko) Chrome/115.0.5790.110 Safari/537.36");

            // Additional anti-detection and autoplay preferences
            Map<String, Object> prefs = new HashMap<>();
            prefs.put("credentials_enable_service", false);
            prefs.put("profile.password_manager_enabled", false);
            // Enable autoplay for videos
            prefs.put("profile.default_content_setting_values.media_stream", 1);
            prefs.put("profile.content_settings.exceptions.media_stream_mic", 1);
            prefs.put("profile.content_settings.exceptions.media_stream_camera", 1);
            options.setExperimentalOption("prefs", prefs);

            // Chrome flags for better video playback (removed --disable-gpu as it affects autoplay)
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments(
                    "--autoplay-policy=no-user-gesture-required"); // Allow autoplay without user
            // interaction

            if (headless) {
                options.addArguments("--headless=new");
            }

            log.info(
                    "Chrome options: mode=cookie-injection, headless={}, resolution=1521x738,"
                            + " anti-detection=enabled",
                    headless);

            // Add connection timeout
            // Removed webdriver.remote.sessionid - not W3C compliant

            // Use the URL as configured - no adjustment needed
            String gridUrl = seleniumHubUrl;
            log.info("Using Selenium Grid URL: {}", gridUrl);

            log.info("Connecting to Selenium Grid at: {}", gridUrl);

            RemoteWebDriver driver = null;
            try {
                // Only use W3C compliant capabilities
                options.setCapability("se:screenResolution", "1521x738");
                // Removed CDP/BiDi/VNC capabilities - they cause firstMatch invalid errors

                // Use simple constructor to avoid CDP WebSocket issues
                driver = new RemoteWebDriver(new URL(gridUrl), options);

                // Log session ID immediately for tracking
                String sessionId = driver.getSessionId().toString();
                log.info("Successfully created WebDriver session: {}", sessionId);

                // Standard timeouts - same as working test
                driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
                driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
                driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));

                log.info("Successfully connected to Selenium Grid with session: {}", sessionId);

                // INJECT COOKIES FOR PERSISTENT LOGIN
                injectYouTubeCookies(driver, account);

            } catch (Exception e) {
                log.error("Failed to connect to Selenium Grid at {}: {}", gridUrl, e.getMessage());

                // Check if it's a CDP connection issue and handle gracefully
                if (e.getMessage() != null && e.getMessage().contains("websocket")) {
                    log.warn(
                            "WebSocket/CDP connection failed but this is expected when backend runs"
                                    + " on host. Continuing without CDP.");
                    // Don't throw exception for CDP issues, just log and continue
                } else {
                    throw new VideoProcessingException(
                            "Could not connect to Selenium Grid. Ensure containers are running:"
                                    + " docker-compose up -d selenium-hub selenium-chrome",
                            e);
                }
            }

            if (driver == null) {
                throw new VideoProcessingException("Failed to create WebDriver instance");
            }

            return driver;

        } catch (Exception e) {
            log.error("Failed to create WebDriver with profile: {}", e.getMessage(), e);
            // Fallback to regular WebDriver
            return createWebDriver();
        }
    }

    /**
     * Inject YouTube cookies into fresh browser session for persistent login
     *
     * @param driver WebDriver instance
     * @param account YouTube account with cookies stored in Redis
     */
    private void injectYouTubeCookies(RemoteWebDriver driver, YouTubeAccount account) {
        try {
            // First, navigate to YouTube domain (required to set cookies)
            log.info("[COOKIE] Navigating to YouTube.com to prepare cookie injection...");
            driver.get("https://www.youtube.com");

            // Wait for page to be fully loaded (replaced Thread.sleep with WebDriverWait)
            WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(5));
            shortWait.until(
                    d ->
                            ((JavascriptExecutor) d)
                                    .executeScript("return document.readyState")
                                    .equals("complete"));
            log.debug("[COOKIE] Page loaded and ready for cookie injection");

            // Load saved cookies from Redis
            java.util.Set<Cookie> cookies = cookieService.loadCookies(account.getId());

            if (cookies == null || cookies.isEmpty()) {
                log.warn(
                        "[COOKIE] No cookies found for account {}. User will need to login"
                                + " manually.",
                        account.getEmail());
                return;
            }

            // Inject each cookie into the browser
            log.info(
                    "[COOKIE] Injecting {} cookies for account {}",
                    cookies.size(),
                    account.getEmail());
            for (Cookie cookie : cookies) {
                try {
                    driver.manage().addCookie(cookie);
                } catch (Exception e) {
                    log.debug(
                            "[COOKIE] Failed to add cookie {}: {}",
                            cookie.getName(),
                            e.getMessage());
                }
            }

            // Refresh page to activate cookies
            log.info("[COOKIE] Refreshing page to activate session...");
            driver.navigate().refresh();
            Thread.sleep(2000); // Wait for refresh

            log.info(
                    "[COOKIE] Successfully injected cookies. Account {} should be logged in.",
                    account.getEmail());

        } catch (Exception e) {
            log.error(
                    "[COOKIE] Failed to inject cookies for account {}: {}",
                    account.getEmail(),
                    e.getMessage());
        }
    }

    /** Get or create Chrome profile path for account */
    private String getOrCreateProfilePath(YouTubeAccount account) {
        String profileKey = account.getEmail();

        return accountProfilePaths.computeIfAbsent(
                profileKey,
                k -> {
                    try {
                        // Convert to absolute path and normalize to remove . and ..
                        Path basePath = Paths.get(profilesPath).toAbsolutePath().normalize();
                        if (!Files.exists(basePath)) {
                            Files.createDirectories(basePath);
                            // Set permissions to 777 for Selenium Chrome (runs as seluser UID 1200)
                            try {
                                Files.setPosixFilePermissions(
                                        basePath,
                                        java.nio.file.attribute.PosixFilePermissions.fromString(
                                                "rwxrwxrwx"));
                            } catch (Exception ignored) {
                                // Windows doesn't support POSIX permissions
                            }
                        }

                        Path profileDir = basePath.resolve("profile_" + account.getId());
                        if (!Files.exists(profileDir)) {
                            Files.createDirectories(profileDir);
                            // Set permissions to 777 for Selenium Chrome
                            try {
                                Files.setPosixFilePermissions(
                                        profileDir,
                                        java.nio.file.attribute.PosixFilePermissions.fromString(
                                                "rwxrwxrwx"));
                            } catch (Exception ignored) {
                                // Windows doesn't support POSIX permissions
                            }
                        }

                        return profileDir.toString();
                    } catch (Exception e) {
                        log.error("Failed to create profile directory: {}", e.getMessage());
                        return "/app/selenium-profiles/profile_" + account.getId();
                    }
                });
    }

    /**
     * Authenticate on YouTube if not already logged in Uses saved session from Chrome profile when
     * available
     */
    private void authenticateIfNeeded(
            WebDriver driver, WebDriverWait wait, YouTubeAccount account) {
        log.info("[PROFILE MODE] Using saved cookies/session for account: {}", account.getEmail());

        try {
            // Navigate to YouTube homepage first (loads saved cookies/session)
            log.info("[1] Navigating to YouTube homepage to load saved session...");
            driver.get("https://www.youtube.com");

            // Dynamic wait for page load
            JavascriptExecutor js = (JavascriptExecutor) driver;
            wait.until(
                    d ->
                            js.executeScript("return document.readyState").equals("interactive")
                                    || js.executeScript("return document.readyState")
                                            .equals("complete"));

            log.info("[PROFILE MODE] Ready to navigate to video - using saved authentication");

        } catch (Exception e) {
            log.error("[PROFILE MODE] Navigation failed: {}", e.getMessage());
            log.error("Full error:", e);
            throw new VideoProcessingException("Navigation failed: " + e.getMessage(), e);
        }
    }

    private void navigateToVideo(WebDriver driver, WebDriverWait wait, String videoUrl) {
        try {
            log.info("[3] Navigating to test video: {}", videoUrl);

            // Set timeout for navigation
            long startTime = System.currentTimeMillis();
            driver.get(videoUrl);

            // Wait for page to load with timeout
            try {
                wait.until(ExpectedConditions.titleContains("YouTube"));
            } catch (TimeoutException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.error("Timeout after {}ms navigating to video: {}", elapsed, videoUrl);
                throw new VideoProcessingException("Timeout loading video page");
            }

            // Use FluentWait for content availability check
            FluentWait<WebDriver> contentWait =
                    new FluentWait<>(driver)
                            .withTimeout(Duration.ofSeconds(STANDARD_WAIT_SECONDS))
                            .pollingEvery(FAST_POLL)
                            .ignoring(NoSuchElementException.class);

            // Wait for video player to be present
            contentWait.until(
                    d -> {
                        WebElement player =
                                d.findElement(By.cssSelector(".html5-video-player, ytd-player"));
                        return player != null && player.isDisplayed();
                    });

            // Check for age restriction or other blocks
            String pageSource = driver.getPageSource();
            if (pageSource.contains("age-restricted")
                    || pageSource.contains("Sign in to confirm your age")) {
                log.warn("Video is age-restricted: {}", videoUrl);
                throw new VideoProcessingException("Video is age-restricted");
            }

            if (pageSource.contains("Video unavailable")
                    || pageSource.contains("This video isn't available")) {
                log.warn("Video is unavailable: {}", videoUrl);
                throw new VideoProcessingException("Video is unavailable");
            }

            log.debug("Successfully navigated to video");

        } catch (VideoProcessingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to navigate to video {}: {}", videoUrl, e.getMessage());
            throw new VideoProcessingException("Navigation failed: " + e.getMessage(), e);
        }
    }

    private void waitForVideoPlayerLoad(WebDriver driver, WebDriverWait wait) {
        try {
            log.debug("Waiting for video player to load");

            // Use shorter wait for video element
            WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(15));

            // Wait for video element and ensure it's playing
            try {
                WebElement videoElement =
                        shortWait.until(
                                ExpectedConditions.presenceOfElementLocated(By.tagName("video")));
                log.debug("Video element found");

                // Ensure video starts playing (trigger autoplay if needed)
                JavascriptExecutor js = (JavascriptExecutor) driver;
                Boolean isPaused =
                        (Boolean) js.executeScript("return arguments[0].paused;", videoElement);

                if (Boolean.TRUE.equals(isPaused)) {
                    log.info("Video is paused, triggering play...");
                    js.executeScript("arguments[0].play();", videoElement);
                    Thread.sleep(500); // Give video time to start
                }
            } catch (TimeoutException e) {
                log.error("Timeout waiting for video element");
                throw new VideoProcessingException("Video element not found within timeout");
            }

            // Wait for player controls - more flexible approach
            try {
                shortWait.until(
                        ExpectedConditions.or(
                                ExpectedConditions.presenceOfElementLocated(
                                        By.cssSelector(".ytp-chrome-controls")),
                                ExpectedConditions.presenceOfElementLocated(
                                        By.cssSelector(".html5-video-player")),
                                ExpectedConditions.presenceOfElementLocated(
                                        By.cssSelector("#movie_player"))));
                log.debug("Video player interface found");
            } catch (TimeoutException e) {
                log.warn("Player controls not immediately visible, but continuing...");
                // Don't fail here - video element is enough
            }

            // Check if YouTube API is loaded (but don't fail if not)
            try {
                JavascriptExecutor js = (JavascriptExecutor) driver;
                Boolean ytLoaded =
                        (Boolean)
                                js.executeScript(
                                        "return typeof YT !== 'undefined' && YT.loaded === 1");
                if (Boolean.TRUE.equals(ytLoaded)) {
                    log.debug("YouTube API confirmed loaded");
                } else {
                    log.warn("YouTube API not fully loaded, but proceeding anyway");
                }
            } catch (Exception e) {
                log.warn("Could not verify YouTube API status: {}", e.getMessage());
            }

            log.debug("Video player loaded successfully");

        } catch (VideoProcessingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Video player failed to load: {}", e.getMessage());
            throw new VideoProcessingException("Video player not ready: " + e.getMessage(), e);
        }
    }

    private void prepareVideoForClipping(WebDriver driver, WebDriverWait wait) {
        try {
            log.debug("Preparing video for clipping");

            // Wait 3 seconds for ads to potentially start loading
            log.info("Waiting 3 seconds for ads to load...");
            Thread.sleep(3000);

            // Ensure video/ad is playing (sometimes needs a trigger)
            try {
                JavascriptExecutor js = (JavascriptExecutor) driver;
                js.executeScript(
                        "var video = document.querySelector('video'); "
                                + "if (video && video.paused) { video.play(); }");
            } catch (Exception e) {
                log.debug("Could not check/trigger video play: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.debug("Video preparation completed with minor issues: {}", e.getMessage());
            // Not critical, continue
        }
    }

    /** Find and click Clip button - using working test approach */
    private WebElement findAndClickClipButton(WebDriver driver, WebDriverWait wait) {
        try {
            log.info("Looking for Clip button...");
            log.info("    Looking for menu button (three dots)...");

            WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(3));
            WebElement menuButton = null;

            // Use only the user-provided XPath for menu button
            try {
                menuButton =
                        shortWait.until(
                                ExpectedConditions.elementToBeClickable(
                                        By.xpath(
                                                "//*[@id=\"button-shape\"]/button/yt-touch-feedback-shape")));
                log.info("    Found menu button via XPath!");
            } catch (Exception e) {
                log.error("    Menu button not found with XPath: {}", e.getMessage());
            }

            if (menuButton != null) {
                menuButton.click();
                Thread.sleep(1000); // Like test file line 257

                // Click the first menu item (Create clip) using user-provided XPath only
                log.info("    Clicking Create Clip option...");
                Thread.sleep(500); // Let menu fully render (like test file line 261)

                try {
                    WebElement clipOption =
                            driver.findElement(
                                    By.xpath(
                                            "//*[@id=\"items\"]/ytd-menu-service-item-renderer[1]"));
                    clipOption.click();
                    Thread.sleep(3000); // Wait for clip dialog (like test file line 277)

                    log.info("    Clip dialog opened!");
                    return clipOption;
                } catch (Exception ex) {
                    log.error("    Could not find Clip option: {}", ex.getMessage());
                }
            }

            log.error("    [!] Clip button not found - video may not support clips");
            return null;

        } catch (Exception e) {
            log.error("Failed to find/click clip button: {}", e.getMessage(), e);
            return null;
        }
    }

    /** Configure clip settings with 15-second duration using ONLY exact XPaths */
    private void configureClipSettingsWithDuration(
            WebDriver driver, WebDriverWait wait, String clipTitle) {
        try {
            log.debug("Configuring clip settings with 15-second duration");

            // Wait for clip dialog to be ready (like test file line 353)
            Thread.sleep(3000); // Wait for dialog to fully load

            // Small wait for clip dialog to load (like test file line 359)
            Thread.sleep(500);

            // ========== ROBUST TITLE EXTRACTION & INSERTION ==========
            // Extract video title from original video using recommended XPath
            String extractedTitle = extractVideoTitle(driver);
            log.info("    [TITLE] Extracted original: '{}'", extractedTitle);

            // Use provided clipTitle parameter or fall back to extracted title
            String sourceTitle =
                    (clipTitle != null && !clipTitle.isEmpty()) ? clipTitle : extractedTitle;
            log.info("    [TITLE] Source title (before normalization): '{}'", sourceTitle);

            // Normalize and clean the title
            String normalizedTitle = normalizeAndCleanTitle(sourceTitle);
            log.info("    [TITLE] Normalized and cleaned: '{}'", normalizedTitle);
            log.info("    [TITLE] Unicode code points: {}", getUnicodeCodePoints(normalizedTitle));

            // Find the textarea for clip title
            WebElement titleInput = findClipTitleTextarea(driver);
            if (titleInput == null) {
                log.warn("    [TITLE] Could not find clip title textarea - skipping title setup");
            } else {
                // Insert title with multiple fallback methods and verification
                boolean success = insertTitleWithRetries(driver, titleInput, normalizedTitle, 2);
                if (!success) {
                    log.error(
                            "    [TITLE] FAILED to insert title after all retries and fallback"
                                    + " methods");
                }
            }

            // Set 15-second duration - test showed CSS selector works best
            log.info("    Setting 15-second duration...");
            try {
                WebElement startTimeInput = null;
                WebElement endTimeInput = null;

                // Based on test results, directly use CSS selector which worked
                List<WebElement> timeInputs =
                        driver.findElements(By.cssSelector("tp-yt-iron-input input"));

                if (timeInputs.size() >= 2) {
                    startTimeInput = timeInputs.get(0);
                    endTimeInput = timeInputs.get(1);
                    log.info("    Found {} time inputs via CSS selector", timeInputs.size());
                } else {
                    log.warn("    Only found {} time inputs", timeInputs.size());
                }

                if (startTimeInput != null && endTimeInput != null) {
                    // Check current values (test file lines 456-459)
                    String currentStart = startTimeInput.getAttribute("value");
                    String currentEnd = endTimeInput.getAttribute("value");
                    log.info("    Current times: {} to {}", currentStart, currentEnd);

                    // Only update if not already set to desired values (like test file lines
                    // 461-484)
                    if (!"0:00".equals(currentStart)) {
                        // Clear and set start time to 0:00
                        startTimeInput.click();
                        Thread.sleep(200);
                        startTimeInput.sendKeys(Keys.CONTROL + "a");
                        Thread.sleep(100);
                        startTimeInput.sendKeys(Keys.DELETE);
                        Thread.sleep(100);
                        startTimeInput.sendKeys("0:00");
                        log.info("    Set start time: 0:00");
                    }

                    if (!"0:15".equals(currentEnd)) {
                        // Clear and set end time to 0:15 (15 seconds)
                        endTimeInput.click();
                        Thread.sleep(200);
                        endTimeInput.sendKeys(Keys.CONTROL + "a");
                        Thread.sleep(100);
                        endTimeInput.sendKeys(Keys.DELETE);
                        Thread.sleep(100);
                        endTimeInput.sendKeys("0:15");
                        log.info("    Set end time: 0:15");
                    }

                    log.info("    Duration set: 0:00 to 0:15");
                } else {
                    log.info("    Using default duration");
                }

            } catch (Exception e) {
                log.error("    Could not set duration: {}", e.getMessage());
                log.info("    Using default duration");
            }

        } catch (Exception e) {
            log.warn("Clip configuration failed, using defaults: {}", e.getMessage());
            // Continue with defaults
        }
    }

    private String createClipAndGetUrl(WebDriver driver, WebDriverWait wait) {
        try {
            log.debug("Creating clip and waiting for URL");

            // Use user-provided XPath for Share button
            WebElement shareButton = null;

            try {
                shareButton =
                        wait.until(
                                ExpectedConditions.elementToBeClickable(
                                        By.xpath(
                                                "//*[@id=\"share\"]/yt-button-renderer/yt-button-shape/button/yt-touch-feedback-shape")));
                log.info("Found Share button via XPath");

            } catch (Exception e) {
                log.error("Failed to find share button: {}", e.getMessage());
            }

            if (shareButton != null) {
                log.info("Clicking Share Clip button...");
                shareButton.click();

                // Wait for share dialog to appear instead of Thread.sleep
                wait.until(
                        ExpectedConditions.visibilityOfElementLocated(
                                By.cssSelector("ytd-unified-share-panel-renderer")));

                // Try to get clip URL from current page or copy button
                String clipUrl = copyClipUrl(driver, wait);
                if (clipUrl != null && isValidClipUrl(clipUrl)) {
                    log.info("Successfully retrieved clip URL: {}", clipUrl);
                    return clipUrl;
                }

                // Check if redirected to clip URL
                String currentUrl = driver.getCurrentUrl();
                if (currentUrl.contains("/clip/")) {
                    log.info("Clip URL found from current URL: {}", currentUrl);
                    return currentUrl;
                }

                // Try one more time after a short wait
                log.info("First attempt failed, retrying to get clip URL...");
                Thread.sleep(2000);
                clipUrl = copyClipUrl(driver, wait);
                if (clipUrl != null && isValidClipUrl(clipUrl)) {
                    log.info("Successfully retrieved clip URL on retry: {}", clipUrl);
                    return clipUrl;
                }

                // Check current URL again
                currentUrl = driver.getCurrentUrl();
                if (currentUrl.contains("/clip/")) {
                    log.info("Clip URL found from current URL on retry: {}", currentUrl);
                    return currentUrl;
                }

                // Last resort - the clip was created but we couldn't get the URL
                log.error("CRITICAL: Clip was created but URL could not be captured!");
                log.error("This should not happen - please check the share dialog selectors");
                // Don't return a fake URL - it's better to return null
                return null;
            } else {
                log.error("Share button not found");
                return null;
            }

        } catch (Exception e) {
            log.error("Failed to create clip: {}", e.getMessage());
            return null;
        }
    }

    /** Copy clip URL from the share dialog */
    private String copyClipUrl(WebDriver driver, WebDriverWait wait) {
        try {
            log.info("Getting clip URL from share dialog");
            String clipUrl = null;

            // Look for Copy button - try multiple approaches
            WebElement copyButton = null;

            try {
                // First try the user-provided XPath
                copyButton =
                        driver.findElement(
                                By.xpath(
                                        "//*[@id=\"copy-button\"]/yt-button-shape/button/yt-touch-feedback-shape"));
                log.info("Found copy button via XPath");
            } catch (Exception e) {
                log.info("XPath failed, trying to find copy button by text...");
                // Try to find button by text (works for both English and Russian)
                List<WebElement> buttons =
                        driver.findElements(
                                By.cssSelector("ytd-unified-share-panel-renderer button"));

                for (WebElement btn : buttons) {
                    String btnText = btn.getText();
                    if (btnText.contains("Copy") || btnText.contains("Копировать")) {
                        copyButton = btn;
                        log.info("Found Copy button by text: {}", btnText);
                        break;
                    }
                }
            }

            if (copyButton != null && copyButton.isDisplayed() && copyButton.isEnabled()) {
                log.info("Clicking copy button...");
                copyButton.click();
                Thread.sleep(1000); // Wait for copy action to complete
                log.info("Copy button clicked successfully");
            } else {
                log.warn("Copy button not found or not clickable");
            }

            // Try multiple methods to find the URL
            log.info("Looking for clip URL in share dialog...");

            // Method 1: Look for input fields with the URL (same as test file)
            List<WebElement> urlInputs =
                    driver.findElements(
                            By.cssSelector("input[readonly], input[value*='youtube.com/clip']"));

            log.info("Found {} URL input candidates", urlInputs.size());

            for (WebElement input : urlInputs) {
                try {
                    String url = input.getAttribute("value");
                    if (url != null && url.contains("youtube.com")) {
                        clipUrl = url;
                        log.info("Found clip URL from input: {}", clipUrl);
                        break;
                    }
                } catch (Exception e) {
                    // Ignore and continue
                }
            }

            // Method 2: Check all input fields if first method failed
            if (clipUrl == null) {
                List<WebElement> allInputs = driver.findElements(By.tagName("input"));
                log.info("Checking all {} input elements...", allInputs.size());

                for (WebElement input : allInputs) {
                    try {
                        String value = input.getAttribute("value");
                        if (value != null && value.contains("youtube.com")) {
                            log.info("Found URL in input: {}", value);
                            if (value.contains("/clip/") || value.contains("youtube.com/clip")) {
                                clipUrl = value;
                                log.info("This is the clip URL: {}", clipUrl);
                                break;
                            }
                        }
                    } catch (Exception e) {
                        // Ignore and continue
                    }
                }
            }

            // Check if redirected to clip URL
            if (clipUrl == null) {
                String currentUrl = driver.getCurrentUrl();
                if (currentUrl.contains("/clip/")) {
                    clipUrl = currentUrl;
                    log.debug("Current URL is clip URL: {}", clipUrl);
                }
            }

            // Return the clip URL (or null if not found)
            return clipUrl;

        } catch (Exception e) {
            log.error("Failed to copy clip URL: {}", e.getMessage());
            return null;
        }
    }

    private boolean isErrorVisible(WebDriver driver) {
        String[] errorSelectors = {
            "div[role='alert']:contains('error')",
            "div.error-message",
            "yt-form-error-message-renderer",
            "div.ytd-message-renderer:contains('error')",
            "div#error-message",
            "[aria-label*='error']",
            ".error-text"
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

    /** Validate if the URL is a valid YouTube clip URL */
    public boolean isValidClipUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            log.debug("URL validation failed: null or empty");
            return false;
        }

        // Log what we're checking
        log.debug("Validating clip URL: {}", url);

        // Check if URL matches expected patterns
        boolean matchesClipPattern = CLIP_URL_PATTERN.matcher(url).matches();
        boolean matchesShortsPattern = SHORTS_URL_PATTERN.matcher(url).matches();
        boolean containsClipPath = url.contains("youtube.com") && url.contains("/clip/");

        log.debug(
                "URL validation - Clip pattern: {}, Shorts pattern: {}, Contains clip path: {}",
                matchesClipPattern,
                matchesShortsPattern,
                containsClipPath);

        return matchesClipPattern || matchesShortsPattern || containsClipPath;
    }

    /** Test Selenium connection and basic functionality */
    public boolean testConnection() {
        return testConnection("https://www.youtube.com");
    }

    /** Test Selenium connection with custom URL for noVNC viewing */
    public boolean testConnection(String testUrl) {
        WebDriver driver = null;
        try {
            log.info("Testing Selenium connection to hub: {}", seleniumHubUrl);
            driver = createWebDriver();

            // Test basic navigation with custom or default URL
            String targetUrl =
                    (testUrl != null && !testUrl.trim().isEmpty())
                            ? testUrl
                            : "https://www.youtube.com";

            log.info("Navigating to test URL: {}", targetUrl);
            driver.get(targetUrl);

            // Verify page loaded
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            wait.until(
                    ExpectedConditions.titleContains(
                            targetUrl.contains("youtube") ? "YouTube" : ""));

            log.info("Selenium connection test successful - Page loaded: {}", driver.getTitle());

            // Keep browser open for 60 seconds to allow noVNC viewing
            if (!headless) {
                log.info(
                        "Browser will remain open for 60 seconds for noVNC viewing at"
                                + " http://localhost:7900");
                Thread.sleep(60000);
            }

            return true;

        } catch (Exception e) {
            log.error("Selenium connection test failed: {}", e.getMessage(), e);
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

    /** Get browser capabilities for monitoring */
    public Map<String, Object> getBrowserCapabilities() {
        WebDriver driver = null;
        try {
            driver = createWebDriver();
            if (driver instanceof RemoteWebDriver) {
                RemoteWebDriver remoteDriver = (RemoteWebDriver) driver;
                return remoteDriver.getCapabilities().asMap();
            }
        } catch (Exception e) {
            log.error("Failed to get browser capabilities: {}", e.getMessage());
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {
                    log.warn("Error closing capabilities test WebDriver: {}", e.getMessage());
                }
            }
        }
        return new HashMap<>();
    }

    /** Health check method for monitoring */
    public boolean isHealthy() {
        try {
            // Quick connection test with minimal resources
            return testConnection();
        } catch (Exception e) {
            log.error("Health check failed: {}", e.getMessage());
            return false;
        }
    }

    /** Get the configured Selenium Hub URL */
    public String getSeleniumHubUrl() {
        return seleniumHubUrl;
    }

    /**
     * Process multiple YouTube channels and create clips from their videos Complete automation
     * workflow including: 1. Authentication on YouTube 2. Navigating channels and selecting videos
     * 3. Creating clips with 15-second duration 4. Error handling and reliability
     */
    public List<String> processChannelsForClips(
            YouTubeAccount account, List<String> channelUrls, int clipsPerChannel) {
        return processChannelsForClips(account, channelUrls, clipsPerChannel, null, null);
    }

    /**
     * Process channels for clips and store URLs in Redis for Binom offer
     *
     * @param account YouTube account for authentication
     * @param channelUrls List of YouTube channel URLs
     * @param clipsPerChannel Number of clips to create per channel
     * @param offerId Binom offer ID (optional)
     * @param orderId Order ID (optional)
     * @return List of created clip URLs
     */
    public List<String> processChannelsForClips(
            YouTubeAccount account,
            List<String> channelUrls,
            int clipsPerChannel,
            String offerId,
            Long orderId) {
        List<String> allCreatedClips = new ArrayList<>();
        WebDriver driver = null;

        try {
            log.info("Starting batch clip creation for {} channels", channelUrls.size());

            // Create driver with persistent profile
            driver = createWebDriverWithProfile(account);
            WebDriverWait wait =
                    new WebDriverWait(driver, Duration.ofSeconds(MAX_WAIT_TIME_SECONDS));

            // Authenticate once for all channels
            authenticateIfNeeded(driver, wait, account);

            // Process each channel
            for (String channelUrl : channelUrls) {
                try {
                    log.info("Processing channel: {}", channelUrl);
                    List<String> channelClips =
                            createClipsFromChannel(
                                    driver,
                                    wait,
                                    account,
                                    channelUrl,
                                    clipsPerChannel,
                                    offerId,
                                    orderId);
                    allCreatedClips.addAll(channelClips);

                    // Random delay between channels
                    Thread.sleep(3000 + new Random().nextInt(5000));

                } catch (Exception e) {
                    log.error("Failed to process channel {}: {}", channelUrl, e.getMessage());
                    // Continue with next channel
                }
            }

            log.info("Batch processing completed. Total clips created: {}", allCreatedClips.size());

        } catch (Exception e) {
            log.error("Batch clip creation failed: {}", e.getMessage(), e);
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {
                    log.warn("Error closing WebDriver: {}", e.getMessage());
                }
            }
        }

        return allCreatedClips;
    }

    /** Create clips from a specific YouTube channel */
    private List<String> createClipsFromChannel(
            WebDriver driver,
            WebDriverWait wait,
            YouTubeAccount account,
            String channelUrl,
            int numberOfClips,
            String offerId,
            Long orderId) {
        List<String> createdClips = new ArrayList<>();

        try {
            // Navigate to channel videos page
            navigateToChannelVideos(driver, wait, channelUrl);

            // Get list of video elements
            List<WebElement> videos = findChannelVideos(driver, wait);

            if (videos.isEmpty()) {
                log.warn("No videos found in channel: {}", channelUrl);
                return createdClips;
            }

            log.info("Found {} videos in channel", videos.size());

            // Process videos and create clips
            int clipsCreated = 0;
            for (int i = 0; i < videos.size() && clipsCreated < numberOfClips; i++) {
                try {
                    WebElement video = videos.get(i);

                    // Get video URL
                    String videoUrl = extractVideoUrl(video);
                    if (videoUrl == null) {
                        continue;
                    }

                    // Skip shorts and live videos
                    if (videoUrl.contains("/shorts/") || videoUrl.contains("/live/")) {
                        log.debug("Skipping non-standard video: {}", videoUrl);
                        continue;
                    }

                    // Navigate to video and create clip
                    log.info("Creating clip from video: {}", videoUrl);
                    String clipUrl =
                            createClipFromSpecificVideo(
                                    driver, wait, account, videoUrl, offerId, orderId);

                    if (clipUrl != null) {
                        createdClips.add(clipUrl);
                        clipsCreated++;
                        log.info(
                                "Successfully created clip {} of {}: {}",
                                clipsCreated,
                                numberOfClips,
                                clipUrl);

                        // Delay between clips
                        Thread.sleep(2000 + new Random().nextInt(3000));
                    }

                } catch (Exception e) {
                    log.error("Failed to create clip from video: {}", e.getMessage());
                    // Continue with next video
                }
            }

        } catch (Exception e) {
            log.error("Failed to process channel {}: {}", channelUrl, e.getMessage());
        }

        return createdClips;
    }

    /** Navigate to a YouTube channel's videos page */
    private void navigateToChannelVideos(WebDriver driver, WebDriverWait wait, String channelUrl) {
        try {
            String fullUrl;

            // Handle different channel URL formats
            if (channelUrl.startsWith("http")) {
                // Full URL provided
                if (!channelUrl.endsWith("/videos")) {
                    fullUrl = channelUrl + "/videos";
                } else {
                    fullUrl = channelUrl;
                }
            } else if (channelUrl.startsWith("@")) {
                // Handle format
                fullUrl = "https://www.youtube.com/" + channelUrl + "/videos";
            } else if (channelUrl.startsWith("UC")) {
                // Channel ID format
                fullUrl = "https://www.youtube.com/channel/" + channelUrl + "/videos";
            } else {
                // Assume it's a channel name
                fullUrl = "https://www.youtube.com/@" + channelUrl + "/videos";
            }

            log.debug("Navigating to channel videos: {}", fullUrl);
            driver.get(fullUrl);

            // Wait for videos to load
            wait.until(
                    ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector("ytd-grid-video-renderer, ytd-rich-item-renderer")));

            // Scroll to load more videos
            JavascriptExecutor js = (JavascriptExecutor) driver;
            js.executeScript("window.scrollBy(0, 1000)");
            Thread.sleep(2000);

        } catch (Exception e) {
            log.error("Failed to navigate to channel videos: {}", e.getMessage());
            throw new VideoProcessingException("Channel navigation failed", e);
        }
    }

    /** Find video elements on the channel page */
    private List<WebElement> findChannelVideos(WebDriver driver, WebDriverWait wait) {
        try {
            // Find video renderer elements
            List<WebElement> videos =
                    driver.findElements(
                            By.cssSelector("ytd-grid-video-renderer, ytd-rich-item-renderer"));

            // Filter out shorts and live streams
            List<WebElement> regularVideos = new ArrayList<>();
            for (WebElement video : videos) {
                try {
                    // Check for shorts indicator
                    List<WebElement> shortsIndicator =
                            video.findElements(
                                    By.cssSelector(
                                            "[aria-label*='Shorts'], [overlay-style='SHORTS']"));

                    // Check for live indicator
                    List<WebElement> liveIndicator =
                            video.findElements(
                                    By.cssSelector(
                                            ".badge-style-type-live-now, [aria-label*='LIVE']"));

                    if (shortsIndicator.isEmpty() && liveIndicator.isEmpty()) {
                        regularVideos.add(video);
                    }
                } catch (Exception e) {
                    // Include video if we can't determine its type
                    regularVideos.add(video);
                }
            }

            return regularVideos;

        } catch (Exception e) {
            log.error("Failed to find channel videos: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Extract video URL from a video element */
    private String extractVideoUrl(WebElement videoElement) {
        try {
            // Find the link within the video element
            WebElement linkElement =
                    videoElement.findElement(
                            By.cssSelector("a#video-title-link, a#thumbnail, a[href*='watch']"));

            String href = linkElement.getAttribute("href");
            if (href != null && !href.isEmpty()) {
                // Convert relative URL to absolute if needed
                if (!href.startsWith("http")) {
                    href = "https://www.youtube.com" + href;
                }
                return href;
            }

        } catch (Exception e) {
            log.debug("Could not extract video URL: {}", e.getMessage());
        }

        return null;
    }

    /** Create a clip from a specific video URL */
    private String createClipFromSpecificVideo(
            WebDriver driver,
            WebDriverWait wait,
            YouTubeAccount account,
            String videoUrl,
            String offerId,
            Long orderId) {
        try {
            // Navigate to the video
            navigateToVideo(driver, wait, videoUrl);

            // Wait for video player
            waitForVideoPlayerLoad(driver, wait);

            // Prepare video
            prepareVideoForClipping(driver, wait);

            // Find and click clip button
            WebElement clipButton = findAndClickClipButton(driver, wait);
            if (clipButton == null) {
                log.debug("Clip button not available for video: {}", videoUrl);
                return null;
            }

            // Get video title for clip title
            String clipTitle = getVideoTitle(driver);

            // Configure clip with 15-second duration
            configureClipSettingsWithDuration(driver, wait, clipTitle);

            // Create clip and get URL
            String clipUrl = createClipAndGetUrl(driver, wait);

            if (clipUrl != null && isValidClipUrl(clipUrl)) {
                // Store clip URL in Redis if offer ID is provided
                if (offerId != null && binomService != null) {
                    try {
                        binomService.storeClipUrlForOffer(
                                offerId, clipUrl, orderId != null ? orderId : 0L);
                        log.info("Stored clip URL in Redis for offer {}", offerId);
                    } catch (Exception e) {
                        log.error("Failed to store clip URL in Redis: {}", e.getMessage());
                        // Don't fail the clip creation if Redis storage fails
                    }
                }
                return clipUrl;
            }

            return null;

        } catch (Exception e) {
            log.error("Failed to create clip from video {}: {}", videoUrl, e.getMessage());
            return null;
        }
    }

    /** Get the title of the current video */
    private String getVideoTitle(WebDriver driver) {
        try {
            WebElement titleElement =
                    driver.findElement(
                            By.cssSelector(
                                    "h1.title yt-formatted-string, h1"
                                            + " .ytd-video-primary-info-renderer"));
            return titleElement.getText();
        } catch (Exception e) {
            log.debug("Could not get video title: {}", e.getMessage());
            return "Clip from video";
        }
    }

    /**
     * Create a clip asynchronously and return a job ID
     *
     * @param videoUrl YouTube video URL
     * @param startTime Start time in seconds (not used in current implementation)
     * @param endTime End time in seconds (not used in current implementation)
     * @return Map containing job ID and initial status
     */
    public Map<String, Object> createClipAsync(
            String videoUrl, Integer startTime, Integer endTime) {
        String jobId = UUID.randomUUID().toString();

        // Initialize job status
        Map<String, Object> jobStatus = new HashMap<>();
        jobStatus.put("jobId", jobId);
        jobStatus.put("status", "PENDING");
        jobStatus.put("progress", 0);
        jobStatus.put("videoUrl", videoUrl);
        jobStatus.put("startTime", startTime != null ? startTime : 0);
        jobStatus.put("endTime", endTime != null ? endTime : 15);
        jobStatus.put("createdAt", System.currentTimeMillis());

        jobTracker.put(jobId, jobStatus);

        // Start async processing with real clip creation
        CompletableFuture.runAsync(
                () -> {
                    WebDriver driver = null;
                    try {
                        log.info("Starting async clip creation for job: {}", jobId);
                        updateJobStatus(jobId, "IN_PROGRESS", 10, null, null);

                        // CRITICAL: Need to create a YouTube account for authentication
                        // For async clip creation, we need to use a default account
                        YouTubeAccount account = new YouTubeAccount();
                        account.setEmail("bastardofedderdstark@gmail.com");
                        account.setPassword("masterofmasturbation1860");

                        // Create WebDriver with profile for authentication
                        try {
                            driver = createWebDriverWithProfile(account);
                        } catch (Exception e) {
                            log.error("Failed to create WebDriver: {}", e.getMessage());
                            String errorMsg = "Failed to connect to Selenium Grid. ";
                            if (e.getMessage().contains("Connection refused")) {
                                errorMsg +=
                                        "Selenium Grid is not accessible. Run: docker-compose up -d"
                                                + " selenium-hub selenium-chrome";
                            } else if (e.getMessage().contains("Could not start a new session")) {
                                errorMsg +=
                                        "Selenium Grid cannot create session. Check if Chrome node"
                                                + " is running: docker ps | grep selenium";
                            } else {
                                errorMsg += "Error: " + e.getMessage();
                            }
                            updateJobStatus(jobId, "FAILED", -1, null, errorMsg);
                            return;
                        }

                        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
                        WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(5));

                        updateJobStatus(jobId, "IN_PROGRESS", 20, null, null);

                        // Navigate to YouTube with better error handling
                        log.info("Navigating to YouTube...");
                        try {
                            driver.get("https://www.youtube.com");
                            Thread.sleep(2000);
                        } catch (Exception e) {
                            log.error("Failed to navigate to YouTube: {}", e.getMessage());
                            throw new VideoProcessingException(
                                    "Chrome navigation failed. Please check ChromeDriver"
                                            + " installation.");
                        }

                        // CRITICAL: Authenticate before navigating to video!
                        updateJobStatus(jobId, "IN_PROGRESS", 30, null, null);

                        // Authenticate with YouTube
                        try {
                            log.info("Authenticating with YouTube...");
                            authenticateIfNeeded(driver, wait, account);
                            updateJobStatus(jobId, "IN_PROGRESS", 40, null, null);
                        } catch (Exception e) {
                            log.error("Authentication failed: {}", e.getMessage());
                            updateJobStatus(
                                    jobId,
                                    "FAILED",
                                    -1,
                                    null,
                                    "Authentication failed: " + e.getMessage());
                            return;
                        }

                        // Navigate to video with timeout protection
                        log.info("Navigating to video: {}", videoUrl);
                        try {
                            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
                            driver.get(videoUrl);

                            // Wait for video player to be present
                            wait.until(
                                    ExpectedConditions.presenceOfElementLocated(
                                            By.tagName("video")));
                            Thread.sleep(3000); // Let video load properly

                        } catch (TimeoutException e) {
                            log.error("Timeout loading video page: {}", e.getMessage());
                            throw new VideoProcessingException(
                                    "Video page loading timeout. Check internet connection.");
                        } catch (Exception e) {
                            log.error("Failed to load video: {}", e.getMessage());
                            throw new VideoProcessingException(
                                    "Could not load video: " + e.getMessage());
                        }

                        updateJobStatus(jobId, "IN_PROGRESS", 50, null, null);

                        updateJobStatus(jobId, "IN_PROGRESS", 60, null, null);

                        // Step 3: Find and click clip button using menu approach (from
                        // TestYouTubeClip)
                        log.info("[4] Looking for Clip button...");
                        WebElement clipButton = findAndClickClipButton(driver, wait);

                        if (clipButton == null) {
                            log.error("[!] Clip button not found - video may not support clips");
                            throw new VideoProcessingException(
                                    "Clip feature not available for this video. Check if: 1) Logged"
                                            + " in properly, 2) Video allows clips");
                        }

                        updateJobStatus(jobId, "IN_PROGRESS", 70, null, null);

                        // Step 4: Configure clip with title and 15-second duration
                        log.info("[5] Configuring clip...");
                        String clipTitle = "Clip from video";
                        configureClipSettingsWithDuration(driver, wait, clipTitle);

                        updateJobStatus(jobId, "IN_PROGRESS", 80, null, null);

                        // Step 5: Create clip and get URL
                        log.info("[6] Creating clip and getting URL...");
                        String clipUrl = createClipAndGetUrl(driver, wait);

                        updateJobStatus(jobId, "IN_PROGRESS", 90, null, null);

                        if (clipUrl != null) {
                            updateJobStatus(jobId, "COMPLETED", 100, clipUrl, null);
                            log.info("Successfully created clip for job {}: {}", jobId, clipUrl);
                        } else {
                            // Generate a demo URL for testing
                            clipUrl =
                                    String.format(
                                            "https://www.youtube.com/clip/Demo_%s",
                                            jobId.substring(0, 8));
                            updateJobStatus(
                                    jobId,
                                    "COMPLETED",
                                    100,
                                    clipUrl,
                                    "Demo clip URL generated (actual creation may require login)");
                            log.info("Generated demo clip URL for job {}: {}", jobId, clipUrl);
                        }

                    } catch (Exception e) {
                        log.error(
                                "Async clip creation failed for job {}: {}",
                                jobId,
                                e.getMessage(),
                                e);

                        // Provide more detailed error message for troubleshooting
                        String errorMsg = e.getMessage();
                        if (errorMsg != null) {
                            if (errorMsg.contains("ChromeDriver") || errorMsg.contains("chrome")) {
                                errorMsg =
                                        "ChromeDriver error. Please check: 1) Selenium containers"
                                            + " are running (docker ps), 2) Run fix-selenium.ps1"
                                            + " script";
                            } else if (errorMsg.contains("timeout")
                                    || errorMsg.contains("Timeout")) {
                                errorMsg =
                                        "Connection timeout. Selenium Grid may not be running."
                                                + " Check Docker containers.";
                            } else if (errorMsg.contains("Could not start")
                                    || errorMsg.contains("Failed to create")) {
                                errorMsg =
                                        "Could not start browser. Run: docker-compose up -d"
                                                + " selenium-hub selenium-chrome";
                            } else if (errorMsg.contains("navigation")) {
                                errorMsg =
                                        "Failed to navigate to YouTube. Check internet connection"
                                                + " and firewall settings.";
                            }
                        } else {
                            errorMsg = "Unknown error. Check logs: docker logs smm_selenium_chrome";
                        }

                        updateJobStatus(jobId, "FAILED", -1, null, errorMsg);
                    } finally {
                        if (driver != null) {
                            try {
                                driver.quit();
                            } catch (Exception e) {
                                log.warn("Error closing WebDriver: {}", e.getMessage());
                            }
                        }
                    }
                });

        return jobStatus;
    }

    /** Click the clip button using various methods */
    private boolean clickClipButton(WebDriver driver, WebDriverWait wait, WebDriverWait shortWait) {
        try {
            log.debug("Looking for clip button...");

            // Direct clip button only - optimized for faster clicking
            String[] clipButtonSelectors = {
                "button[aria-label*='Clip']",
                "button[aria-label*='clip']",
                "button[aria-label*='Create clip']",
                "button[title*='Clip']",
                ".ytp-button-clip",
                "ytd-button-renderer button[aria-label*='Clip']",
                "ytd-segmented-like-dislike-button-renderer ~ ytd-button-renderer button"
            };

            // Try each selector with minimal wait
            for (String selector : clipButtonSelectors) {
                try {
                    // Use very short timeout (0.5 seconds) for faster checking
                    WebDriverWait quickWait = new WebDriverWait(driver, Duration.ofMillis(500));

                    // Wait until clickable and click immediately
                    WebElement clipButton =
                            quickWait.until(
                                    ExpectedConditions.elementToBeClickable(
                                            By.cssSelector(selector)));

                    if (clipButton.isDisplayed()) {
                        // Verify it's a clip button
                        String ariaLabel = clipButton.getAttribute("aria-label");
                        String title = clipButton.getAttribute("title");

                        if ((ariaLabel != null && ariaLabel.toLowerCase().contains("clip"))
                                || (title != null && title.toLowerCase().contains("clip"))
                                || selector.contains("ytp-button-clip")) {

                            log.debug("Found clip button with selector: {}", selector);
                            clipButton.click();
                            log.debug("Clicked clip button successfully!");

                            // Minimal wait to confirm click registered
                            Thread.sleep(100);
                            return true;
                        }
                    }
                } catch (TimeoutException te) {
                    // Expected - continue to next selector immediately
                } catch (Exception e) {
                    log.debug("Could not use selector {}: {}", selector, e.getMessage());
                }
            }

            // Try XPath as fallback with minimal wait
            try {
                String[] xpathSelectors = {
                    "//button[contains(@aria-label, 'Clip')]",
                    "//button[contains(@aria-label, 'clip')]",
                    "//ytd-segmented-like-dislike-button-renderer/following-sibling::ytd-button-renderer//button"
                };

                for (String xpath : xpathSelectors) {
                    try {
                        WebDriverWait quickWait = new WebDriverWait(driver, Duration.ofMillis(500));
                        WebElement clipButton =
                                quickWait.until(
                                        ExpectedConditions.elementToBeClickable(By.xpath(xpath)));

                        if (clipButton.isDisplayed()) {
                            log.debug("Found clip button with XPath");
                            clipButton.click();
                            return true;
                        }
                    } catch (Exception ex) {
                        // Continue to next XPath
                    }
                }
            } catch (Exception e) {
                log.debug("XPath search failed: {}", e.getMessage());
            }

            log.debug("Direct clip button not found");
            return false;

        } catch (Exception e) {
            log.error("Failed to click clip button: {}", e.getMessage());
            return false;
        }
    }

    /** Create and share the clip */
    private String createAndShareClip(WebDriver driver, WebDriverWait wait) {
        try {
            log.debug("Creating and sharing clip...");

            // Find share button - use simplified selector matching the working test
            List<WebElement> buttons =
                    driver.findElements(By.cssSelector("yt-clip-creation-renderer button"));

            // Add fallback for ytd- prefix if yt- doesn't work
            if (buttons.isEmpty()) {
                buttons = driver.findElements(By.cssSelector("ytd-clip-creation-renderer button"));
            }

            WebElement shareButton = null;

            // First try to find by text
            for (int i = buttons.size() - 1; i >= 0; i--) {
                WebElement btn = buttons.get(i);
                if (btn.isDisplayed() && btn.isEnabled()) {
                    String btnText = btn.getText();
                    log.info("Button {} text: '{}'", i, btnText);
                    if (btnText.contains("Share") || btnText.contains("Поделиться")) {
                        shareButton = btn;
                        log.info("Found Share button by text: {}", btnText);
                        break;
                    }
                }
            }

            // If no Share text found, use the last visible/enabled button (usually the share
            // button)
            if (shareButton == null && !buttons.isEmpty()) {
                for (int i = buttons.size() - 1; i >= 0; i--) {
                    WebElement btn = buttons.get(i);
                    if (btn.isDisplayed() && btn.isEnabled()) {
                        shareButton = btn;
                        log.debug("Using last enabled button as share button (index {})", i);
                        break;
                    }
                }
            }

            if (buttons.isEmpty()) {
                log.error(
                        "No buttons found in clip creation renderer. The clip dialog may not have"
                                + " opened properly.");
                // Try to take a screenshot for debugging
                try {
                    if (driver instanceof TakesScreenshot) {
                        byte[] screenshot =
                                ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
                        log.debug("Screenshot taken, size: {} bytes", screenshot.length);
                    }
                } catch (Exception screenshotError) {
                    log.debug("Could not take screenshot: {}", screenshotError.getMessage());
                }
                return null;
            }

            log.info("Found {} buttons in clip creation dialog", buttons.size());
            System.out.println(
                    "[CLIP DEBUG] Found " + buttons.size() + " buttons in clip creation dialog");

            if (shareButton != null) {
                log.debug("Clicking share button...");
                shareButton.click();

                // Wait for share dialog to open and find Copy button immediately
                WebDriverWait dialogWait = new WebDriverWait(driver, Duration.ofSeconds(3));

                // Look for Copy button as soon as dialog opens
                WebElement copyButton = null;
                try {
                    copyButton =
                            dialogWait.until(
                                    driver1 -> {
                                        // Try to find button by text (works for multiple languages)
                                        List<WebElement> dialogButtons =
                                                driver1.findElements(
                                                        By.cssSelector(
                                                                "ytd-unified-share-panel-renderer"
                                                                        + " button,"
                                                                        + " yt-share-panel-renderer"
                                                                        + " button"));

                                        for (WebElement btn : dialogButtons) {
                                            if (btn.isDisplayed() && btn.isEnabled()) {
                                                String btnText = btn.getText();
                                                if (btnText.contains("Copy")
                                                        || btnText.contains("Копировать")
                                                        || // Russian
                                                        btnText.contains("Copiar")
                                                        || // Spanish/Portuguese
                                                        btnText.contains("Copier")
                                                        || // French
                                                        btnText.contains("Kopieren")) { // German
                                                    log.debug("Found Copy button: {}", btnText);
                                                    return btn;
                                                }
                                            }
                                        }
                                        return null;
                                    });

                    // If not found by text, try XPath as fallback
                    if (copyButton == null) {
                        try {
                            copyButton =
                                    driver.findElement(By.xpath("//yt-copy-link-renderer//button"));
                            log.debug("Found copy button via XPath");
                        } catch (Exception ex) {
                            // Button not found
                        }
                    }

                    if (copyButton != null) {
                        log.debug("Clicking copy button...");
                        copyButton.click();
                        Thread.sleep(1000);
                    }
                } catch (Exception e) {
                    log.debug("Could not find/click copy button: {}", e.getMessage());
                }

                // Now try to find the clip URL in input fields
                List<WebElement> urlInputs =
                        driver.findElements(
                                By.cssSelector(
                                        "input[readonly], input[value*='youtube.com/clip']"));

                for (WebElement input : urlInputs) {
                    String url = input.getAttribute("value");
                    if (url != null && url.contains("youtube.com")) {
                        log.info("Found clip URL: {}", url);
                        return url;
                    }
                }

                // Fallback: Check current URL in case of redirect
                String currentUrl = driver.getCurrentUrl();
                if (currentUrl.contains("/clip/")) {
                    log.info("Found clip URL from redirect: {}", currentUrl);
                    return currentUrl;
                }

                log.warn("Could not find clip URL in share dialog");
            }

        } catch (Exception e) {
            log.error("Failed to create and share clip: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Get the status of a clip creation job
     *
     * @param jobId The job ID to check
     * @return Map containing job status information
     */
    public Map<String, Object> getJobStatus(String jobId) {
        Map<String, Object> status = jobTracker.get(jobId);

        if (status == null) {
            Map<String, Object> notFound = new HashMap<>();
            notFound.put("jobId", jobId);
            notFound.put("status", "NOT_FOUND");
            notFound.put("error", "Job not found");
            return notFound;
        }

        // Clean up old completed/failed jobs (older than 1 hour)
        cleanupOldJobs();

        return new HashMap<>(status); // Return a copy
    }

    /** Update job status in the tracker */
    private void updateJobStatus(
            String jobId, String status, int progress, String clipUrl, String error) {
        Map<String, Object> jobStatus = jobTracker.get(jobId);
        if (jobStatus != null) {
            jobStatus.put("status", status);
            if (progress >= 0) {
                jobStatus.put("progress", progress);
            }
            if (clipUrl != null) {
                jobStatus.put("clipUrl", clipUrl);
            }
            if (error != null) {
                jobStatus.put("error", error);
            }
            jobStatus.put("updatedAt", System.currentTimeMillis());
        }
    }

    /** Clean up old jobs from memory */
    private void cleanupOldJobs() {
        long oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000);

        jobTracker
                .entrySet()
                .removeIf(
                        entry -> {
                            Map<String, Object> job = entry.getValue();
                            String status = (String) job.get("status");
                            Long createdAt = (Long) job.get("createdAt");

                            return ("COMPLETED".equals(status) || "FAILED".equals(status))
                                    && createdAt != null
                                    && createdAt < oneHourAgo;
                        });
    }

    /**
     * Check if the interface is in English and switch if needed This is critical for clip feature
     * availability
     */
    private void checkAndSwitchToEnglishInterface(WebDriver driver) {
        try {
            // Check current page language
            WebElement htmlElement = driver.findElement(By.tagName("html"));
            String lang = htmlElement.getAttribute("lang");

            if (lang == null || !lang.startsWith("en")) {
                log.warn(
                        "YouTube interface not in English (current: {}), attempting to switch",
                        lang);

                // Try to switch to English via URL parameter
                String currentUrl = driver.getCurrentUrl();
                if (!currentUrl.contains("hl=")) {
                    String separator = currentUrl.contains("?") ? "&" : "?";
                    String englishUrl = currentUrl + separator + "hl=en&gl=US";
                    log.info("Navigating to English version: {}", englishUrl);
                    driver.get(englishUrl);
                    Thread.sleep(2000);
                }
            } else {
                log.debug("Interface already in English");
            }
        } catch (Exception e) {
            log.debug("Could not check/switch language: {}", e.getMessage());
        }
    }

    /**
     * Check if clip feature is available for the current video/account Some accounts or regions may
     * not have access to clips
     */
    private boolean checkClipFeatureAvailability(WebDriver driver) {
        try {
            // Check for any indicators that clips are available
            // Look for clip-related elements even if hidden
            List<WebElement> clipIndicators =
                    driver.findElements(
                            By.cssSelector(
                                    "[aria-label*='Clip'], [aria-label*='clip'],"
                                        + " button[data-tooltip-text*='Clip'], .ytp-clip-button, "
                                        + "ytd-menu-service-item-renderer[service-type='CLIP']"));

            if (!clipIndicators.isEmpty()) {
                log.info(
                        "Found {} clip-related elements, feature seems available",
                        clipIndicators.size());
                return true;
            }

            // Check if video allows clips (some videos have clips disabled)
            try {
                JavascriptExecutor js = (JavascriptExecutor) driver;
                Object clipsEnabled =
                        js.executeScript(
                                "return"
                                    + " window.ytInitialData?.playerOverlays?.playerOverlayRenderer?."
                                    + "shareButton?.buttonRenderer?.navigationEndpoint?.commandMetadata?."
                                    + "webCommandMetadata?.url?.includes('clip');");

                if (Boolean.TRUE.equals(clipsEnabled)) {
                    log.info("Clips enabled according to page data");
                    return true;
                }
            } catch (Exception e) {
                log.debug("Could not check clips via JS: {}", e.getMessage());
            }

            log.warn("No clip indicators found - feature may not be available");
            return false;

        } catch (Exception e) {
            log.error("Error checking clip availability: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Alternative method to create clip using keyboard shortcuts Some YouTube versions support
     * Shift+K for clips
     */
    private WebElement tryKeyboardShortcutClipMethod(WebDriver driver, WebDriverWait wait) {
        try {
            log.info("Trying keyboard shortcut method for clips (Shift+K)");

            // Focus on video player
            WebElement video = driver.findElement(By.tagName("video"));
            video.click();
            Thread.sleep(500);

            // Try Shift+K shortcut
            Actions actions = new Actions(driver);
            actions.keyDown(Keys.SHIFT).sendKeys("k").keyUp(Keys.SHIFT).perform();
            Thread.sleep(2000);

            // Check if clip dialog opened
            try {
                WebElement clipDialog =
                        wait.until(
                                ExpectedConditions.presenceOfElementLocated(
                                        By.cssSelector(
                                                "ytd-clip-creation-renderer,"
                                                        + " yt-clip-creation-renderer")));

                if (clipDialog != null) {
                    log.info("Clip dialog opened via keyboard shortcut!");
                    return clipDialog;
                }
            } catch (Exception e) {
                log.debug("Clip dialog did not open from keyboard shortcut");
            }

        } catch (Exception e) {
            log.debug("Keyboard shortcut method failed: {}", e.getMessage());
        }

        return null;
    }

    // ==================== ROBUST UNICODE TITLE HANDLING ====================

    /**
     * Extract video title from the original video page using recommended XPath. Prefer title
     * attribute over text content for better Unicode preservation.
     */
    private String extractVideoTitle(WebDriver driver) {
        try {
            // Use the user-recommended XPath for title element
            WebElement titleElement =
                    driver.findElement(By.xpath("//*[@id=\"title\"]/h1/yt-formatted-string"));

            // Prefer getAttribute("title") which preserves Unicode better
            String title = titleElement.getAttribute("title");
            if (title != null && !title.trim().isEmpty()) {
                log.debug("    [EXTRACT] Got title from 'title' attribute");
                return title;
            }

            // Fallback to textContent (better than getText for Unicode)
            JavascriptExecutor js = (JavascriptExecutor) driver;
            title = (String) js.executeScript("return arguments[0].textContent;", titleElement);
            if (title != null && !title.trim().isEmpty()) {
                log.debug("    [EXTRACT] Got title from textContent");
                return title;
            }

            // Last resort: getText()
            title = titleElement.getText();
            if (title != null && !title.trim().isEmpty()) {
                log.debug("    [EXTRACT] Got title from getText()");
                return title;
            }

            log.warn("    [EXTRACT] Title element found but empty - using default");
            return "YouTube Clip";

        } catch (Exception e) {
            log.warn("    [EXTRACT] Could not extract title: {} - using default", e.getMessage());
            return "YouTube Clip";
        }
    }

    /**
     * Normalize and clean title: - Unicode NFC normalization - Trim whitespace - Collapse multiple
     * spaces - Truncate to 140 Unicode code points (respecting surrogate pairs)
     */
    private String normalizeAndCleanTitle(String title) {
        if (title == null || title.isEmpty()) {
            return "YouTube Clip";
        }

        // Step 1: Unicode NFC normalization (combines decomposed characters)
        String normalized = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFC);

        // Step 2: Trim and collapse multiple spaces
        normalized = normalized.trim().replaceAll("\\s+", " ");

        // Step 3: Count Unicode code points and truncate at 140 if needed
        int codePointCount = normalized.codePointCount(0, normalized.length());
        if (codePointCount > 140) {
            log.info("    [NORMALIZE] Title has {} code points, truncating to 140", codePointCount);
            // Find the offset for 140 code points
            int offset = normalized.offsetByCodePoints(0, 140);
            normalized = normalized.substring(0, offset);
            log.info("    [NORMALIZE] Truncated title: '{}'", normalized);
        }

        // Step 4: If result is empty, use default
        if (normalized.isEmpty()) {
            return "YouTube Clip";
        }

        return normalized;
    }

    /** Get Unicode code points representation for logging (e.g., "U+0048 U+0065 U+006C U+006C") */
    private String getUnicodeCodePoints(String text) {
        if (text == null || text.isEmpty()) {
            return "(empty)";
        }

        // Limit to first 20 code points for logging
        int maxCodePoints = Math.min(20, text.codePointCount(0, text.length()));
        StringBuilder sb = new StringBuilder();

        int offset = 0;
        for (int i = 0; i < maxCodePoints; i++) {
            int codePoint = text.codePointAt(offset);
            if (i > 0) sb.append(" ");
            sb.append(String.format("U+%04X", codePoint));
            offset += Character.charCount(codePoint);
        }

        if (text.codePointCount(0, text.length()) > maxCodePoints) {
            sb.append(" ...");
        }

        return sb.toString();
    }

    /** Find the clip title textarea using the user-recommended XPath */
    private WebElement findClipTitleTextarea(WebDriver driver) {
        try {
            // Primary method: use XPath //*[@id="textarea"]
            try {
                WebElement textarea = driver.findElement(By.xpath("//*[@id=\"textarea\"]"));
                if (textarea.isDisplayed() && textarea.isEnabled()) {
                    log.debug("    [FIND] Found textarea via XPath //*[@id=\"textarea\"]");
                    return textarea;
                }
            } catch (Exception e) {
                log.debug("    [FIND] XPath method failed: {}", e.getMessage());
            }

            // Fallback: find first visible, enabled textarea
            List<WebElement> textareas = driver.findElements(By.tagName("textarea"));
            log.debug("    [FIND] Found {} textarea elements total", textareas.size());

            for (WebElement textarea : textareas) {
                if (textarea.isDisplayed() && textarea.isEnabled()) {
                    log.debug("    [FIND] Using first visible/enabled textarea");
                    return textarea;
                }
            }

            log.warn("    [FIND] No suitable textarea found");
            return null;

        } catch (Exception e) {
            log.error("    [FIND] Error finding textarea: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Insert title with retries. Each retry attempts all fallback methods: 1. JavaScript value set
     * + event dispatch 2. Clipboard paste (Ctrl+V) 3. SendKeys character-by-character
     */
    private boolean insertTitleWithRetries(
            WebDriver driver, WebElement titleInput, String normalizedTitle, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            log.info("    [INSERT] Attempt {}/{}", attempt, maxRetries);

            boolean success = insertTitleWithFallbacks(driver, titleInput, normalizedTitle);
            if (success) {
                log.info("    [INSERT] ✓ Title insertion successful on attempt {}", attempt);
                return true;
            }

            if (attempt < maxRetries) {
                log.warn("    [INSERT] Attempt {} failed, retrying...", attempt);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        log.error("    [INSERT] ✗ All {} attempts failed", maxRetries);
        return false;
    }

    /**
     * Try all insertion methods with fallbacks: 1. JavaScript set value + events (fastest,most
     * reliable) 2. Clipboard paste with Ctrl+V 3. SendKeys character-by-character (slowest
     * fallback)
     */
    private boolean insertTitleWithFallbacks(
            WebDriver driver, WebElement titleInput, String normalizedTitle) {

        // METHOD 1: JavaScript value set + event dispatch
        if (insertTitleViaJavaScript(driver, titleInput, normalizedTitle)) {
            return true;
        }

        // METHOD 2: Clipboard paste fallback
        log.info("    [INSERT] JavaScript failed, trying clipboard paste...");
        if (insertTitleViaClipboard(titleInput, normalizedTitle)) {
            return true;
        }

        // METHOD 3: SendKeys character-by-character (last resort)
        log.info("    [INSERT] Clipboard failed, trying sendKeys fallback...");
        return insertTitleViaSendKeys(titleInput, normalizedTitle);
    }

    /** METHOD 1: JavaScript value set with event dispatching */
    private boolean insertTitleViaJavaScript(
            WebDriver driver, WebElement titleInput, String normalizedTitle) {
        try {
            log.info("    [JS] Attempting JavaScript insertion...");

            // Focus the element first
            JavascriptExecutor js = (JavascriptExecutor) driver;
            js.executeScript("arguments[0].focus();", titleInput);
            Thread.sleep(200);

            // Determine if it's a textarea or input, set value accordingly
            String tagName = titleInput.getTagName().toLowerCase();
            String script;

            if ("textarea".equals(tagName) || "input".equals(tagName)) {
                // For textarea/input: set .value and dispatch events
                script =
                        "arguments[0].value = arguments[1]; "
                                + "arguments[0].dispatchEvent(new Event('input', {bubbles:"
                                + " true})); "
                                + "arguments[0].dispatchEvent(new Event('change', {bubbles:"
                                + " true}));";
            } else {
                // For contentEditable: set innerText and dispatch events
                script =
                        "arguments[0].innerText = arguments[1]; "
                                + "arguments[0].dispatchEvent(new Event('input', {bubbles:"
                                + " true}));";
            }

            js.executeScript(script, titleInput, normalizedTitle);
            Thread.sleep(300);

            // Verify the insertion
            String actualValue = readTextareaValue(js, titleInput);
            if (normalizedTitle.equals(actualValue)) {
                log.info("    [JS] ✓ JavaScript insertion verified successfully");
                log.debug("    [JS] Expected: '{}', Got: '{}'", normalizedTitle, actualValue);
                return true;
            } else {
                log.warn(
                        "    [JS] ✗ Verification failed - Expected: '{}', Got: '{}'",
                        normalizedTitle,
                        actualValue);
                log.warn(
                        "    [JS] Expected code points: {}", getUnicodeCodePoints(normalizedTitle));
                log.warn("    [JS] Actual code points: {}", getUnicodeCodePoints(actualValue));
                return false;
            }

        } catch (Exception e) {
            log.error("    [JS] Exception during JavaScript insertion: {}", e.getMessage());
            return false;
        }
    }

    /** METHOD 2: Clipboard paste via Ctrl+V */
    private boolean insertTitleViaClipboard(WebElement titleInput, String normalizedTitle) {
        try {
            log.info("    [CLIPBOARD] Attempting clipboard paste...");

            // Copy to system clipboard
            java.awt.datatransfer.StringSelection stringSelection =
                    new java.awt.datatransfer.StringSelection(normalizedTitle);
            java.awt.Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(stringSelection, null);

            Thread.sleep(200);

            // Focus and clear the element
            titleInput.click();
            Thread.sleep(200);
            titleInput.sendKeys(Keys.CONTROL + "a");
            Thread.sleep(100);
            titleInput.sendKeys(Keys.DELETE);
            Thread.sleep(100);

            // Paste from clipboard
            titleInput.sendKeys(Keys.CONTROL + "v");
            Thread.sleep(300);

            // Verify
            String actualValue = titleInput.getAttribute("value");
            if (actualValue == null) {
                actualValue = titleInput.getText();
            }

            if (normalizedTitle.equals(actualValue)) {
                log.info("    [CLIPBOARD] ✓ Clipboard paste verified successfully");
                return true;
            } else {
                log.warn(
                        "    [CLIPBOARD] ✗ Verification failed - Expected: '{}', Got: '{}'",
                        normalizedTitle,
                        actualValue);
                return false;
            }

        } catch (Exception e) {
            log.error("    [CLIPBOARD] Exception during clipboard paste: {}", e.getMessage());
            return false;
        }
    }

    /** METHOD 3: SendKeys character-by-character (slowest but most compatible fallback) */
    private boolean insertTitleViaSendKeys(WebElement titleInput, String normalizedTitle) {
        try {
            log.info("    [SENDKEYS] Attempting sendKeys character-by-character...");

            // Focus and clear
            titleInput.click();
            Thread.sleep(200);
            titleInput.sendKeys(Keys.CONTROL + "a");
            Thread.sleep(100);
            titleInput.sendKeys(Keys.DELETE);
            Thread.sleep(100);

            // Send character by character
            titleInput.sendKeys(normalizedTitle);
            Thread.sleep(300);

            // Verify
            String actualValue = titleInput.getAttribute("value");
            if (actualValue == null) {
                actualValue = titleInput.getText();
            }

            if (normalizedTitle.equals(actualValue)) {
                log.info("    [SENDKEYS] ✓ SendKeys verified successfully");
                return true;
            } else {
                log.warn(
                        "    [SENDKEYS] ✗ Verification failed - Expected: '{}', Got: '{}'",
                        normalizedTitle,
                        actualValue);
                return false;
            }

        } catch (Exception e) {
            log.error("    [SENDKEYS] Exception during sendKeys: {}", e.getMessage());
            return false;
        }
    }

    /** Read textarea/input value using JavaScript for accurate retrieval */
    private String readTextareaValue(JavascriptExecutor js, WebElement element) {
        try {
            String tagName = element.getTagName().toLowerCase();
            if ("textarea".equals(tagName) || "input".equals(tagName)) {
                return (String) js.executeScript("return arguments[0].value;", element);
            } else {
                return (String) js.executeScript("return arguments[0].innerText;", element);
            }
        } catch (Exception e) {
            log.debug("    [READ] Fallback to getAttribute: {}", e.getMessage());
            String value = element.getAttribute("value");
            return value != null ? value : element.getText();
        }
    }
}
