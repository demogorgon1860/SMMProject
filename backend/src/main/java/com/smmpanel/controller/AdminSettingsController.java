package com.smmpanel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.admin.AppSettingDto;
import com.smmpanel.entity.OperatorLog;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.OperatorLogRepository;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.settings.AppSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * Admin endpoints behind /admin/settings.
 *
 * <p>{@code GET /api/v2/admin/settings} returns the full settings map; {@code PUT
 * /api/v2/admin/settings/{key}} upserts one key. Every PUT writes an entry to {@code operator_logs}
 * so we keep a forensic trail of who changed what (settings drive money + gating — the audit log is
 * non-negotiable).
 *
 * <p>Limited to ADMIN. Operators (read-only role) can list but can't write — settings changes are
 * platform-wide and need full admin authority.
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/admin/settings")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class AdminSettingsController {

    private final AppSettingsService appSettingsService;
    private final OperatorLogRepository operatorLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ResponseEntity<List<AppSettingDto>> list() {
        return ResponseEntity.ok(appSettingsService.listAll());
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> update(
            @PathVariable @NotBlank String key,
            @RequestBody UpdateRequest body,
            HttpServletRequest request) {
        User actor = currentUser();

        AppSettingsService.PutResult result;
        try {
            result = appSettingsService.put(key, body.value(), actor);
        } catch (IllegalArgumentException e) {
            // The service throws this on type mismatch / negative number / unparseable bool —
            // it's user input from the admin form, so 400 with the message is the right answer.
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "INVALID_VALUE", "message", e.getMessage()));
        }

        writeAudit(actor, key, result.oldValue(), result.newValue(), request);

        return ResponseEntity.ok(result.dto());
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) return null;
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }

    private void writeAudit(
            User actor, String key, String oldValue, String newValue, HttpServletRequest request) {
        if (actor == null) {
            // Defensive: if Spring Security let an unauthenticated request through (shouldn't,
            // given @PreAuthorize), log loudly and skip the audit row rather than fail the
            // primary action.
            log.error("Settings write to {} succeeded but actor is null — audit log skipped", key);
            return;
        }

        try {
            OperatorLog entry = new OperatorLog();
            entry.setOperator(actor);
            entry.setAction("UPDATE_APP_SETTING");
            entry.setTargetType("APP_SETTING");
            // target_id is BIGINT NOT NULL — settings are keyed by string, so we use 0 as a
            // placeholder and put the real key in details.
            entry.setTargetId(0L);
            entry.setDetails(
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "key", key,
                                    "oldValue", oldValue == null ? "" : oldValue,
                                    "newValue", newValue == null ? "" : newValue)));
            entry.setIpAddress(extractClientIp(request));
            entry.setUserAgent(request.getHeader("User-Agent"));
            operatorLogRepository.save(entry);
        } catch (Exception e) {
            // Never fail the user's request because audit failed — but make this loud, audit
            // gaps are an incident-response problem.
            log.error("Failed to write operator_log entry for setting {} change", key, e);
        }
    }

    private static String extractClientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return fwd.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real;
        return request.getRemoteAddr();
    }

    public record UpdateRequest(String value) {}
}
