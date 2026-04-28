package com.smmpanel.security;

import com.smmpanel.service.settings.AppSettingsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Returns 503 Service Unavailable for non-admin API requests when {@code maintenance.enabled} is
 * on. Admin endpoints, auth endpoints, webhooks, and actuator probes always pass through — an admin
 * must be able to flip the flag back off, and incoming bot webhooks shouldn't be lost because of a
 * panel maintenance window.
 *
 * <p>Registered in {@link com.smmpanel.config.SecurityConfig} as the FIRST filter in the chain so
 * it short-circuits before any auth work happens.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaintenanceFilter extends OncePerRequestFilter {

    private final AppSettingsService appSettingsService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Fault-open on infra failure: if reading the setting throws (e.g. Redis + DB both
        // down), don't make this filter the thing that wedges the platform — let downstream
        // handlers deal with the real outage. Maintenance is rare and gets flipped by an admin
        // who can verify state out-of-band; defaulting to "off" on read failure is the safe call.
        boolean maintenanceOn;
        try {
            maintenanceOn =
                    appSettingsService.getBoolean(
                            AppSettingsService.KEY_MAINTENANCE_ENABLED, false);
        } catch (Exception e) {
            log.error("Failed to read maintenance flag — defaulting to OFF", e);
            maintenanceOn = false;
        }

        if (!maintenanceOn) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();

        if (isAlwaysAllowed(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.debug("Maintenance mode active — rejecting {} {}", request.getMethod(), path);

        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.setHeader("Retry-After", "300");
        response.getWriter()
                .write(
                        "{\"error\":\"MAINTENANCE_MODE\",\"message\":\"The panel is in"
                                + " maintenance mode. New orders are temporarily disabled.\"}");
    }

    /**
     * Paths that must work even during maintenance:
     *
     * <ul>
     *   <li><b>admin/**</b> (with or without API version) — so an admin can disable maintenance.
     *   <li><b>auth/**</b> — admins still need to log in.
     *   <li><b>webhook/**, webhooks/**, payments/cryptomus/callback, telegram/webhook</b> —
     *       incoming events from bots/payment processors must be absorbed; rejecting them would
     *       lose order status updates and deposit confirmations.
     *   <li><b>/actuator/**</b> — health probes for orchestrator and uptime monitors.
     *   <li><b>/api-docs, /swagger-ui</b> — static, harmless.
     *   <li><b>/error</b> — Spring's error dispatch.
     * </ul>
     */
    private boolean isAlwaysAllowed(String path) {
        if (path == null) return true;
        if (path.startsWith("/actuator/")) return true;
        if (path.equals("/error")) return true;
        if (path.startsWith("/v3/api-docs")) return true;
        if (path.startsWith("/swagger-ui")) return true;

        // Strip /api/ prefix and any /v{N}/ version segment so the suffix tests below match
        // both /api/admin/foo and /api/v2/admin/foo without bespoke regex per case.
        String suffix = stripApiAndVersionPrefix(path);
        if (suffix == null) return false;

        return suffix.startsWith("admin/")
                || suffix.startsWith("auth/")
                || suffix.startsWith("webhook/")
                || suffix.startsWith("webhooks/")
                || suffix.startsWith("telegram/webhook")
                || suffix.startsWith("payments/cryptomus/callback");
    }

    /**
     * Returns the path with leading {@code /api/} and an optional {@code v{N}/} version segment
     * removed, or {@code null} if the path is not under {@code /api/}. Examples:
     *
     * <pre>
     *   /api/v2/admin/foo → admin/foo
     *   /api/admin/foo    → admin/foo
     *   /api/v1/auth/me   → auth/me
     *   /healthz          → null
     * </pre>
     */
    private String stripApiAndVersionPrefix(String path) {
        if (!path.startsWith("/api/")) return null;
        String tail = path.substring(5); // drop "/api/"
        // Match optional version segment "v<digits>/" — strip if present.
        if (tail.length() > 1 && tail.charAt(0) == 'v') {
            int end = 1;
            while (end < tail.length() && Character.isDigit(tail.charAt(end))) end++;
            if (end > 1 && end < tail.length() && tail.charAt(end) == '/') {
                tail = tail.substring(end + 1);
            }
        }
        return tail;
    }
}
