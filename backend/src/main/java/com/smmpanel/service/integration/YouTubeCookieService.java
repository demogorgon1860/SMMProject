package com.smmpanel.service.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.Cookie;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Service for managing YouTube authentication cookies in Redis Enables persistent login across
 * concurrent Selenium sessions without profile locking issues
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeCookieService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String COOKIE_KEY_PREFIX = "youtube:cookies:account:";
    private static final Duration COOKIE_TTL = Duration.ofDays(30); // Cookies expire after 30 days

    /**
     * Save YouTube cookies for an account to Redis
     *
     * @param accountId YouTube account ID
     * @param cookies Set of Selenium cookies from logged-in session
     */
    public void saveCookies(Long accountId, Set<Cookie> cookies) {
        try {
            // Convert Cookie objects to serializable Map format
            List<Map<String, Object>> cookieList = new ArrayList<>();
            for (Cookie cookie : cookies) {
                Map<String, Object> cookieMap = new HashMap<>();
                cookieMap.put("name", cookie.getName());
                cookieMap.put("value", cookie.getValue());
                cookieMap.put("domain", cookie.getDomain());
                cookieMap.put("path", cookie.getPath());
                if (cookie.getExpiry() != null) {
                    cookieMap.put("expiry", cookie.getExpiry().getTime());
                }
                cookieMap.put("isSecure", cookie.isSecure());
                cookieMap.put("isHttpOnly", cookie.isHttpOnly());
                if (cookie.getSameSite() != null) {
                    cookieMap.put("sameSite", cookie.getSameSite());
                }
                cookieList.add(cookieMap);
            }

            String cookiesJson = objectMapper.writeValueAsString(cookieList);
            String key = COOKIE_KEY_PREFIX + accountId;

            redisTemplate.opsForValue().set(key, cookiesJson, COOKIE_TTL);

            log.info(
                    "Saved {} YouTube cookies for account {} to Redis (TTL: {} days)",
                    cookies.size(),
                    accountId,
                    COOKIE_TTL.toDays());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize cookies for account {}: {}", accountId, e.getMessage());
            throw new RuntimeException("Failed to save cookies", e);
        }
    }

    /**
     * Load YouTube cookies for an account from Redis
     *
     * @param accountId YouTube account ID
     * @return Set of Selenium cookies, or null if not found
     */
    public Set<Cookie> loadCookies(Long accountId) {
        try {
            String key = COOKIE_KEY_PREFIX + accountId;
            String cookiesJson = redisTemplate.opsForValue().get(key);

            if (cookiesJson == null) {
                log.warn("No cookies found in Redis for account {}", accountId);
                return null;
            }

            // Deserialize from Map format and rebuild Cookie objects
            List<Map<String, Object>> cookieList =
                    objectMapper.readValue(
                            cookiesJson, new TypeReference<List<Map<String, Object>>>() {});

            Set<Cookie> cookies = new HashSet<>();
            for (Map<String, Object> cookieMap : cookieList) {
                Cookie.Builder builder =
                        new Cookie.Builder(
                                (String) cookieMap.get("name"), (String) cookieMap.get("value"));

                if (cookieMap.containsKey("domain")) {
                    builder.domain((String) cookieMap.get("domain"));
                }
                if (cookieMap.containsKey("path")) {
                    builder.path((String) cookieMap.get("path"));
                }
                if (cookieMap.containsKey("expiry")) {
                    Long expiryMillis = ((Number) cookieMap.get("expiry")).longValue();
                    builder.expiresOn(new java.util.Date(expiryMillis));
                }
                if (cookieMap.containsKey("isSecure")
                        && Boolean.TRUE.equals(cookieMap.get("isSecure"))) {
                    builder.isSecure(true);
                }
                if (cookieMap.containsKey("isHttpOnly")
                        && Boolean.TRUE.equals(cookieMap.get("isHttpOnly"))) {
                    builder.isHttpOnly(true);
                }
                if (cookieMap.containsKey("sameSite")) {
                    builder.sameSite((String) cookieMap.get("sameSite"));
                }

                cookies.add(builder.build());
            }

            log.info(
                    "Loaded {} YouTube cookies for account {} from Redis",
                    cookies.size(),
                    accountId);
            return cookies;

        } catch (Exception e) {
            log.error(
                    "Failed to deserialize cookies for account {}: {}", accountId, e.getMessage());
            return null;
        }
    }

    /**
     * Check if cookies exist for an account
     *
     * @param accountId YouTube account ID
     * @return true if cookies exist, false otherwise
     */
    public boolean hasCookies(Long accountId) {
        String key = COOKIE_KEY_PREFIX + accountId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * Delete cookies for an account (force re-login)
     *
     * @param accountId YouTube account ID
     */
    public void deleteCookies(Long accountId) {
        String key = COOKIE_KEY_PREFIX + accountId;
        redisTemplate.delete(key);
        log.info("Deleted YouTube cookies for account {}", accountId);
    }

    /**
     * Refresh cookie TTL (extend expiration)
     *
     * @param accountId YouTube account ID
     */
    public void refreshCookies(Long accountId) {
        String key = COOKIE_KEY_PREFIX + accountId;
        redisTemplate.expire(key, COOKIE_TTL);
        log.debug("Refreshed TTL for account {} cookies", accountId);
    }
}
