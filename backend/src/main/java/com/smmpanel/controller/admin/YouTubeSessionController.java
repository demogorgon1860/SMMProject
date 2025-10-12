package com.smmpanel.controller.admin;

import com.smmpanel.entity.YouTubeAccount;
import com.smmpanel.repository.jpa.YouTubeAccountRepository;
import com.smmpanel.service.integration.YouTubeCookieService;
import java.net.URL;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Admin controller for managing YouTube session cookies Provides endpoints to capture and store
 * YouTube login sessions
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/admin/youtube-session")
@RequiredArgsConstructor
public class YouTubeSessionController {

    private final YouTubeCookieService cookieService;
    private final YouTubeAccountRepository youTubeAccountRepository;

    @Value("${app.selenium.hub.url}")
    private String seleniumHubUrl;

    /**
     * Initialize YouTube login session for cookie capture Opens browser and waits for manual login,
     * then saves cookies
     *
     * @param accountId YouTube account ID
     * @param waitMinutes How long to wait for manual login (default: 5 minutes)
     * @return VNC URL for manual login
     */
    @PostMapping("/init-login/{accountId}")
    public ResponseEntity<?> initializeLogin(
            @PathVariable Long accountId, @RequestParam(defaultValue = "5") int waitMinutes) {

        try {
            YouTubeAccount account =
                    youTubeAccountRepository
                            .findById(accountId)
                            .orElseThrow(
                                    () -> new RuntimeException("Account not found: " + accountId));

            log.info("Initializing YouTube login session for account: {}", account.getEmail());

            // Create Chrome with VNC enabled for manual login
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--window-size=1521,738");
            options.addArguments("--disable-blink-features=AutomationControlled");
            options.setExperimentalOption("excludeSwitches", new String[] {"enable-automation"});
            options.setExperimentalOption("useAutomationExtension", false);

            // Anti-detection settings
            options.addArguments(
                    "user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/115.0.5790.110 Safari/537.36");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");

            Map<String, Object> prefs = new HashMap<>();
            prefs.put("credentials_enable_service", false);
            prefs.put("profile.password_manager_enabled", false);
            options.setExperimentalOption("prefs", prefs);

            // Set screen resolution for VNC
            options.setCapability("se:screenResolution", "1521x738");

            // Create driver - SIMPLE APPROACH without CDP
            RemoteWebDriver driver = new RemoteWebDriver(new URL(seleniumHubUrl), options);

            String sessionId = driver.getSessionId().toString();
            log.info("Created browser session: {}", sessionId);

            // Navigate to YouTube
            driver.get("https://www.youtube.com");
            log.info("Navigated to YouTube. Please login manually via noVNC.");

            // Wait for manual login
            log.info("Waiting {} minutes for manual login...", waitMinutes);
            Thread.sleep(Duration.ofMinutes(waitMinutes).toMillis());

            // Capture cookies after login
            Set<Cookie> cookies = driver.manage().getCookies();
            log.info("Captured {} cookies from browser session", cookies.size());

            // Save cookies to Redis
            cookieService.saveCookies(accountId, cookies);

            // Clean up
            driver.quit();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Successfully captured and saved YouTube session cookies");
            response.put("cookieCount", cookies.size());
            response.put("accountId", accountId);
            response.put("accountEmail", account.getEmail());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to initialize YouTube login session: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Manual cookie capture - use this AFTER logging in via noVNC Creates a session, navigate to
     * noVNC, login, then call this endpoint
     *
     * @param accountId YouTube account ID
     * @return Response with cookie capture status
     */
    @PostMapping("/capture-cookies/{accountId}")
    public ResponseEntity<?> captureCookiesManual(@PathVariable Long accountId) {
        try {
            YouTubeAccount account =
                    youTubeAccountRepository
                            .findById(accountId)
                            .orElseThrow(
                                    () -> new RuntimeException("Account not found: " + accountId));

            log.info("Starting manual cookie capture for account: {}", account.getEmail());

            // Create browser session
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--window-size=1521,738");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.setCapability("se:screenResolution", "1521x738");

            RemoteWebDriver driver =
                    (RemoteWebDriver)
                            RemoteWebDriver.builder()
                                    .address(new URL(seleniumHubUrl))
                                    .oneOf(options)
                                    .build();

            String sessionId = driver.getSessionId().toString();
            driver.get("https://www.youtube.com");

            Map<String, Object> response = new HashMap<>();
            response.put("sessionId", sessionId);
            response.put(
                    "message",
                    "Browser opened. Login via noVNC, then call /save-cookies/" + sessionId);
            response.put("vncUrl", "http://localhost:7900/?path=websockify");
            response.put(
                    "instructions",
                    "1. Open noVNC URL, 2. Login to YouTube, 3. Call /save-cookies/" + sessionId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to start manual cookie capture: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Check if cookies exist for an account
     *
     * @param accountId YouTube account ID
     * @return Cookie status
     */
    @GetMapping("/status/{accountId}")
    public ResponseEntity<?> getCookieStatus(@PathVariable Long accountId) {
        boolean hasCookies = cookieService.hasCookies(accountId);

        YouTubeAccount account = youTubeAccountRepository.findById(accountId).orElse(null);

        Map<String, Object> response = new HashMap<>();
        response.put("accountId", accountId);
        response.put("accountEmail", account != null ? account.getEmail() : "Unknown");
        response.put("hasCookies", hasCookies);
        response.put(
                "status", hasCookies ? "Cookies available" : "No cookies - manual login required");

        return ResponseEntity.ok(response);
    }

    /**
     * Delete cookies for an account (force re-login)
     *
     * @param accountId YouTube account ID
     * @return Deletion status
     */
    @DeleteMapping("/cookies/{accountId}")
    public ResponseEntity<?> deleteCookies(@PathVariable Long accountId) {
        cookieService.deleteCookies(accountId);

        return ResponseEntity.ok(
                Map.of("success", true, "message", "Cookies deleted for account " + accountId));
    }
}
