package com.smmpanel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.smmpanel.dto.admin.DailyStatPoint;
import com.smmpanel.dto.profile.ChangePasswordRequest;
import com.smmpanel.dto.profile.DeleteAccountRequest;
import com.smmpanel.dto.profile.LifetimeStatsDto;
import com.smmpanel.dto.profile.ProfileMeResponse;
import com.smmpanel.dto.profile.SessionDto;
import com.smmpanel.dto.profile.UpdateProfileRequest;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.admin.AdminService;
import com.smmpanel.service.profile.AccountDeletionService;
import com.smmpanel.service.profile.AccountExportService;
import com.smmpanel.service.profile.ApiKeyPauseService;
import com.smmpanel.service.profile.LifetimeStatsService;
import com.smmpanel.service.profile.ProfileService;
import com.smmpanel.service.profile.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;

/**
 * Self-service profile endpoints under {@code /api/v1/me/*}.
 *
 * <p>Implemented now: GET/PATCH me, change password, notifications, daily stats, lifetime stats,
 * sessions list/revoke/sign-out-others, account-data export, API-key pause/resume.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;
    private final AdminService adminService;
    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final LifetimeStatsService lifetimeStatsService;
    private final AccountExportService accountExportService;
    private final ApiKeyPauseService apiKeyPauseService;
    private final AccountDeletionService accountDeletionService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ResponseEntity<ProfileMeResponse> getMe() {
        return ResponseEntity.ok(profileService.getMe());
    }

    /**
     * Daily order/spend series for the user's own dashboard charts. Returns one entry per day
     * (zero-filled for days with no orders) so the frontend doesn't have to bucket on the client or
     * fetch 100+ orders just to draw a 12-day sparkline.
     */
    @GetMapping("/stats/daily")
    public ResponseEntity<List<DailyStatPoint>> getDailyStats(
            @RequestParam(defaultValue = "30") int days, Principal principal) {
        int safeDays = Math.max(1, Math.min(days, 90));
        User user =
                userRepository
                        .findByUsername(principal.getName())
                        .orElseThrow(() -> new IllegalStateException("User not found"));
        return ResponseEntity.ok(adminService.getDailyStatsForUser(user.getId(), safeDays));
    }

    /**
     * Lifetime activity tile on Profile → Account. 60s cache lives in {@link LifetimeStatsService}
     * (Redis), so this controller just resolves the principal and delegates.
     */
    @GetMapping("/stats/lifetime")
    public ResponseEntity<LifetimeStatsDto> getLifetimeStats(Principal principal) {
        return ResponseEntity.ok(lifetimeStatsService.compute(principal.getName()));
    }

    @PatchMapping
    public ResponseEntity<ProfileMeResponse> patchMe(
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(profileService.updateMe(request));
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        try {
            profileService.changePassword(request);
            return ResponseEntity.noContent().build();
        } catch (BadCredentialsException e) {
            // 422 (not 401) — the user is authenticated, the *current password input* is wrong.
            // Returning 401 here would trip the global axios interceptor and force-logout the
            // session, kicking the user out of the app for a typo.
            log.info("Change-password rejected: bad current password");
            return ResponseEntity.status(422).build();
        } catch (IllegalArgumentException e) {
            log.info("Change-password rejected: {}", e.getMessage());
            return ResponseEntity.status(400).build();
        }
    }

    @GetMapping("/notifications")
    public ResponseEntity<Map<String, Boolean>> getNotifications() {
        return ResponseEntity.ok(profileService.getNotifications());
    }

    @PatchMapping("/notifications")
    public ResponseEntity<Map<String, Boolean>> patchNotifications(
            @RequestBody Map<String, Boolean> patch) {
        return ResponseEntity.ok(profileService.updateNotifications(patch));
    }

    // =====================================================================
    // Sessions
    // =====================================================================

    @GetMapping("/sessions")
    public ResponseEntity<List<SessionDto>> listSessions(HttpServletRequest request) {
        return ResponseEntity.ok(sessionService.listSessions(request));
    }

    /**
     * Revoke a specific session. 204 on success or no-op (already revoked / unknown id —
     * idempotent, so the UI can re-click without error spam). 409 when the caller tries to revoke
     * the session they're currently using; the right call there is {@code POST /v1/auth/logout},
     * which also clears the cookie.
     */
    @DeleteMapping("/sessions/{tokenId}")
    public ResponseEntity<Void> revokeSession(
            @PathVariable Long tokenId, HttpServletRequest request) {
        try {
            SessionService.RevokeResult result = sessionService.revokeSession(tokenId, request);
            return switch (result) {
                case REVOKED, ALREADY_REVOKED, NOT_FOUND -> ResponseEntity.noContent().build();
                case IS_CURRENT -> ResponseEntity.status(409).build();
            };
        } catch (SecurityException e) {
            // Cross-user revoke attempt — surface as 404 (not 403) so we don't leak that the
            // session id exists at all.
            log.warn("Session revoke denied: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/sessions/sign-out-others")
    public ResponseEntity<Map<String, Object>> signOutOthers(HttpServletRequest request) {
        int revoked = sessionService.signOutOthers(request);
        return ResponseEntity.ok(Map.of("revoked", revoked));
    }

    // =====================================================================
    // Account data export
    // =====================================================================

    /**
     * Synchronous JSON export — see Task 03 spec. The async + signed-URL + email path was descoped
     * until export volume warrants it. Stream straight back as a file attachment, so a regular
     * {@code <a href>} click in the browser saves it.
     */
    @PostMapping("/export")
    public ResponseEntity<byte[]> exportData(Principal principal) throws Exception {
        Map<String, Object> bundle = accountExportService.buildExport();
        byte[] body =
                objectMapper
                        .copy()
                        .enable(SerializationFeature.INDENT_OUTPUT)
                        .writeValueAsBytes(bundle);
        String filename =
                "smmworld-account-" + principal.getName() + "-" + LocalDate.now() + ".json";
        log.info(
                "User {} downloaded account data export ({} bytes)",
                principal.getName(),
                body.length);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    // =====================================================================
    // API-key pause / resume
    // =====================================================================

    @GetMapping("/api-key/pause-status")
    public ResponseEntity<Map<String, Object>> apiKeyPauseStatus() {
        return ResponseEntity.ok(apiKeyPauseService.getStatus());
    }

    @PostMapping("/api-key/pause")
    public ResponseEntity<Map<String, Object>> apiKeyPause() {
        return ResponseEntity.ok(apiKeyPauseService.pause());
    }

    @PostMapping("/api-key/resume")
    public ResponseEntity<Map<String, Object>> apiKeyResume() {
        return ResponseEntity.ok(apiKeyPauseService.resume());
    }

    // =====================================================================
    // GDPR account deletion (soft-delete now, hard-delete in 30 days)
    // =====================================================================

    /**
     * Soft-delete the authenticated user. Returns 204 on success. Validation failures map to
     * distinct 4xx codes so the frontend can render the right inline error:
     *
     * <ul>
     *   <li>400 — confirmation token != "DELETE"
     *   <li>409 — wallet balance > 0 or in-flight orders block deletion
     *   <li>422 — wrong password (same shape as change-password to avoid the global 401 redirect)
     * </ul>
     */
    @DeleteMapping("/account")
    public ResponseEntity<Map<String, String>> deleteAccount(
            @Valid @RequestBody DeleteAccountRequest request, HttpServletRequest httpRequest) {
        try {
            accountDeletionService.deleteCurrentUser(
                    request.getConfirmation(), request.getPassword(), httpRequest);
            return ResponseEntity.noContent().build();
        } catch (AccountDeletionService.AccountDeletionException e) {
            int status =
                    switch (e.getReason()) {
                        case INVALID_CONFIRMATION -> 400;
                        case WRONG_PASSWORD -> 422;
                        case POSITIVE_BALANCE, ACTIVE_ORDERS -> 409;
                    };
            log.info("Account deletion rejected ({}): {}", e.getReason(), e.getMessage());
            return ResponseEntity.status(status)
                    .body(Map.of("reason", e.getReason().name(), "message", e.getMessage()));
        }
    }
}
