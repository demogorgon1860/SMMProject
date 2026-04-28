package com.smmpanel.service.settings;

import com.smmpanel.dto.admin.AppSettingDto;
import com.smmpanel.entity.AppSetting;
import com.smmpanel.entity.User;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.repository.jpa.AppSettingRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Typed accessor for {@link AppSetting} key/value store.
 *
 * <p>Reads are cached on the {@code app-settings} cache (Redis, 60s TTL). Writes go through {@link
 * #put(String, String, User)} which evicts the cache so enforcement code (rate limits, min charge,
 * maintenance flag) sees the new value within ~60s in any case and immediately on the same node.
 *
 * <p>Defensive defaults: every typed accessor takes a fallback used when the key is missing OR the
 * value cannot be parsed. Settings drive money (fees) and gating (maintenance) — a deserialization
 * bug must NEVER fault open.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppSettingsService {

    public static final String CACHE_NAME = "app-settings";

    // Setting keys (referenced from enforcement code; centralized here so a typo
    // surfaces at compile time instead of as a silently-missing setting at runtime).
    public static final String KEY_MIN_ORDER_CHARGE = "platform.fee.min_order_charge";
    public static final String KEY_MARKUP_PERCENT = "platform.fee.markup_percent";
    public static final String KEY_CRYPTOMUS_PASSTHROUGH_PCT =
            "platform.fee.cryptomus_passthrough_pct";
    public static final String KEY_ORDERS_PER_MINUTE = "rate.orders_per_minute_per_user";
    public static final String KEY_API_PER_MINUTE = "rate.api_per_minute_per_user";
    public static final String KEY_MAX_CONCURRENT_ORDERS = "rate.max_concurrent_orders_per_user";
    public static final String KEY_MAINTENANCE_ENABLED = "maintenance.enabled";

    private final AppSettingRepository repository;
    private final CacheManager cacheManager;

    /**
     * Self-injection so typed getters can call {@link #getValue(String)} through the Spring AOP
     * proxy and actually trigger {@code @Cacheable}. Without this, internal {@code this.getValue()}
     * calls bypass the proxy and the cache silently does nothing — a subtle perf regression that
     * would only show up under load.
     */
    @Autowired @Lazy private AppSettingsService self;

    /**
     * Internal cached read. Returns the raw string value (or {@code null}). Cached on {@code
     * app-settings}; never returns the entity itself so lazy associations can't escape the
     * transaction. Public typed getters route through this via the self-proxy.
     */
    @Cacheable(value = CACHE_NAME, key = "#key", unless = "#result == null")
    @Transactional(readOnly = true)
    public String getValue(String key) {
        return repository.findById(key).map(AppSetting::getValue).orElse(null);
    }

    /**
     * Snapshot of every setting as a DTO list. Uses a JOIN FETCH on {@code updatedBy} so the lazy
     * association is materialized before the session closes — accessing it from the controller
     * layer would otherwise throw LazyInitializationException — and avoids N+1.
     */
    @Transactional(readOnly = true)
    public List<AppSettingDto> listAll() {
        return repository.findAllForListing().stream().map(AppSettingDto::from).toList();
    }

    public String getString(String key, String fallback) {
        String v = self.getValue(key);
        return v == null ? fallback : v;
    }

    public boolean getBoolean(String key, boolean fallback) {
        String raw = self.getValue(key);
        if (raw == null) return fallback;
        String v = raw.trim().toLowerCase();
        if (v.equals("true") || v.equals("1") || v.equals("yes")) return true;
        if (v.equals("false") || v.equals("0") || v.equals("no")) return false;
        log.warn(
                "Setting {} has unparseable boolean value '{}' — using fallback {}",
                key,
                v,
                fallback);
        return fallback;
    }

    public int getInt(String key, int fallback) {
        String raw = self.getValue(key);
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn(
                    "Setting {} has unparseable int value '{}' — using fallback {}",
                    key,
                    raw,
                    fallback);
            return fallback;
        }
    }

    public BigDecimal getDecimal(String key, BigDecimal fallback) {
        String raw = self.getValue(key);
        if (raw == null) return fallback;
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            log.warn(
                    "Setting {} has unparseable decimal value '{}' — using fallback {}",
                    key,
                    raw,
                    fallback);
            return fallback;
        }
    }

    /**
     * Upsert a setting. Validates against the existing row's {@link AppSetting.ValueType} so a
     * BOOLEAN slot can't be overwritten with junk text. Returns a {@link PutResult} carrying the
     * post-save DTO plus the {@code (oldValue, newValue)} tuple so the controller can write a
     * meaningful audit log entry without ever touching JPA-managed state outside the transaction.
     *
     * <p>For unknown keys, throws 404 — settings are explicitly seeded; the API does not allow
     * arbitrary key creation. This keeps the surface area predictable and audit-friendly.
     */
    @CacheEvict(value = CACHE_NAME, key = "#key")
    @Transactional
    public PutResult put(String key, String rawValue, User actor) {
        AppSetting existing =
                repository
                        .findById(key)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Unknown setting key: " + key));

        String normalized = validateAndNormalize(existing.getValueType(), rawValue);
        String oldValue = existing.getValue();

        existing.setValue(normalized);
        existing.setUpdatedAt(LocalDateTime.now());
        existing.setUpdatedBy(actor);
        repository.save(existing);

        // Markup changes the rate the public catalog (/v1/service/services) advertises, but
        // that endpoint is @Cacheable("services", TTL 1h). Without cross-cache eviction here
        // the catalog would keep showing pre-change rates while orders are billed at the new
        // rate — exactly the divergence we want to avoid. Defer the eviction until after commit
        // so a parallel reader doesn't sneak in between method-end and commit and repopulate
        // the cache with the not-yet-committed (== still-old) value.
        if (KEY_MARKUP_PERCENT.equals(key)) {
            evictAfterCommit("services");
        }

        log.info(
                "Setting {} updated by {}: '{}' -> '{}'",
                key,
                actor != null ? actor.getUsername() : "(unknown)",
                oldValue,
                normalized);

        // Build the DTO inside the transaction so updatedBy.username is materialized eagerly.
        return new PutResult(AppSettingDto.from(existing), oldValue, normalized);
    }

    private void evictCache(String name) {
        if (cacheManager == null) return;
        try {
            org.springframework.cache.Cache c = cacheManager.getCache(name);
            if (c != null) c.clear();
        } catch (Exception e) {
            log.warn("Failed to clear cache '{}' on settings update: {}", name, e.getMessage());
        }
    }

    /**
     * Schedule a cache eviction to fire only after the current transaction commits. If no
     * transaction is active (shouldn't happen — caller is {@code @Transactional}), evict
     * immediately so we don't silently drop the eviction.
     */
    private void evictAfterCommit(String name) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            evictCache(name);
                        }
                    });
        } else {
            evictCache(name);
        }
    }

    private String validateAndNormalize(AppSetting.ValueType type, String rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("value is required");
        }
        String trimmed = rawValue.trim();
        switch (type) {
            case BOOLEAN -> {
                String lower = trimmed.toLowerCase();
                if (lower.equals("true") || lower.equals("1") || lower.equals("yes")) return "true";
                if (lower.equals("false") || lower.equals("0") || lower.equals("no"))
                    return "false";
                throw new IllegalArgumentException(
                        "Expected boolean (true/false) but got: " + rawValue);
            }
            case NUMBER -> {
                try {
                    BigDecimal parsed = new BigDecimal(trimmed);
                    if (parsed.signum() < 0) {
                        throw new IllegalArgumentException(
                                "Numeric settings cannot be negative: " + rawValue);
                    }
                    return parsed.toPlainString();
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Expected number but got: " + rawValue);
                }
            }
            case STRING -> {
                return trimmed;
            }
        }
        return trimmed;
    }

    /**
     * Result of a put: returns a DTO snapshot of the saved row plus the old/new value pair so the
     * controller can issue a single response and emit a precise audit-log entry.
     */
    public record PutResult(AppSettingDto dto, String oldValue, String newValue) {}
}
