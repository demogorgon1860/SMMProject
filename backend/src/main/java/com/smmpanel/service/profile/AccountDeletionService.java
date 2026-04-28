package com.smmpanel.service.profile;

import com.smmpanel.entity.AuditLog;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.User;
import com.smmpanel.exception.UserNotFoundException;
import com.smmpanel.repository.jpa.AuditLogRepository;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.auth.RefreshTokenService;
import com.smmpanel.service.email.EmailService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * GDPR Article 17 — right to erasure. Two-phase deletion: a soft delete, followed by a hard delete
 * 30 days later (driven by {@code AccountDeletionScheduler}). The grace window lets a user contact
 * support and recover the account if they regret the click.
 *
 * <p>The deletion path is intentionally narrow:
 *
 * <ol>
 *   <li>Confirmation token must be the literal string {@code "DELETE"} (matches the typed prompt in
 *       the Profile → Danger Zone modal).
 *   <li>Current password must match — re-authentication is required even though the user already
 *       holds a valid session, because the access token may be sitting on a shared machine.
 *   <li>Wallet balance must be zero. We refuse to consume residual funds on the user's behalf.
 *   <li>No orders may be in flight (PENDING / IN_PROGRESS / PROCESSING / ACTIVE / PAUSED / HOLDING
 *       / REFILL) — the user has to cancel or wait for refunds first, or we'd be deleting the only
 *       person who can receive that refund.
 * </ol>
 *
 * <p>Transaction shape (single boundary, all-or-nothing):
 *
 * <pre>
 *   audit_logs INSERT  → if this fails, deletion is aborted (compliance row is mandatory)
 *   users      UPDATE  → identity columns anonymized, deleted_at stamped
 *   refresh_tokens UPDATE → revoke all sessions
 *   ↳ AFTER_COMMIT only: best-effort confirmation email (async, won't fire on rollback)
 * </pre>
 *
 * <p>We deliberately do NOT swallow exceptions inside the transaction. A failed audit save would
 * have poisoned the transaction anyway (Hibernate marks it rollback-only on JDBC error), so
 * propagating gives the operator a clear 500 + stack trace instead of a misleading "deleted but not
 * really" state. The email is registered as a {@link TransactionSynchronization#afterCommit}
 * callback so it never fires for a transaction that ultimately rolls back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    /**
     * Order statuses that block deletion — anything where the bot may still produce work or where a
     * refund is still in flight. The user has to clear these before account closure.
     */
    private static final List<OrderStatus> ACTIVE_ORDER_STATUSES =
            List.of(
                    OrderStatus.PENDING,
                    OrderStatus.IN_PROGRESS,
                    OrderStatus.PROCESSING,
                    OrderStatus.ACTIVE,
                    OrderStatus.PAUSED,
                    OrderStatus.HOLDING,
                    OrderStatus.REFILL);

    private static final String EXPECTED_CONFIRMATION = "DELETE";
    private static final int GRACE_PERIOD_DAYS = 30;
    private static final int RANDOM_PASSWORD_BYTES = 32;

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final RefreshTokenService refreshTokenService;
    private final AuditLogRepository auditLogRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Soft-delete the currently authenticated user. Throws {@link AccountDeletionException} on any
     * validation failure — the controller maps those to 4xx with the user-facing reason.
     */
    @Transactional
    public void deleteCurrentUser(
            String confirmation, String password, HttpServletRequest request) {
        User user = currentUser();

        if (user.isSoftDeleted()) {
            // Idempotent — repeated DELETE on an already-soft-deleted account is a no-op.
            // Should not happen via the normal UI (the JWT would be rejected), but defends
            // against a stale token in flight at the moment of deletion.
            log.info("Account {} already soft-deleted at {}", user.getId(), user.getDeletedAt());
            return;
        }

        validateConfirmation(confirmation);
        validatePassword(user, password);
        validateZeroBalance(user);
        validateNoActiveOrders(user);

        // Capture identity values BEFORE we anonymize — they're needed for the audit row and the
        // post-commit confirmation email.
        final String originalEmail = user.getEmail();
        final String originalUsername = user.getUsername();
        final boolean emailWasVerified = user.isEmailVerified();
        final Long userId = user.getId();

        // Audit FIRST. If the compliance write fails for any reason (column constraint, disk
        // full, …), the transaction rolls back and the user remains intact — better than wiping
        // identity without a paper trail.
        writeAuditLog(userId, originalUsername, originalEmail, request);

        // Anonymize in place. Same row, same id — preserves FK targets in audit_logs and any
        // historical reference, but no PII remains on the live row.
        anonymize(user);
        userRepository.save(user);

        // Revoke every refresh token. Cascade-delete on hard-delete will mop up the rows in 30
        // days; this UPDATE simply makes existing sessions stop working immediately.
        refreshTokenService.revokeAllUserTokens(user, "Account deletion");

        // Confirmation email — only fired on successful commit, only if the user had a verified
        // address (no point emailing an unverified inbox). The async send means we don't block
        // the request thread on Resend's HTTP round trip.
        if (emailWasVerified && originalEmail != null) {
            registerAfterCommit(
                    () ->
                            emailService.sendAccountDeletionConfirmation(
                                    originalEmail, originalUsername));
        }

        log.info(
                "User {} (username={}) soft-deleted; hard-delete after {} days",
                userId,
                originalUsername,
                GRACE_PERIOD_DAYS);
    }

    // =====================================================================
    // Validation
    // =====================================================================

    private void validateConfirmation(String confirmation) {
        if (!EXPECTED_CONFIRMATION.equals(confirmation)) {
            throw new AccountDeletionException(
                    AccountDeletionException.Reason.INVALID_CONFIRMATION,
                    "Type DELETE to confirm.");
        }
    }

    private void validatePassword(User user, String password) {
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AccountDeletionException(
                    AccountDeletionException.Reason.WRONG_PASSWORD, "Password does not match.");
        }
    }

    private void validateZeroBalance(User user) {
        BigDecimal balance = user.getBalance() == null ? BigDecimal.ZERO : user.getBalance();
        if (balance.compareTo(BigDecimal.ZERO) > 0) {
            throw new AccountDeletionException(
                    AccountDeletionException.Reason.POSITIVE_BALANCE,
                    "Wallet balance must be zero. Withdraw or contact support before deleting.");
        }
    }

    private void validateNoActiveOrders(User user) {
        long active = orderRepository.countByUserIdAndStatusIn(user.getId(), ACTIVE_ORDER_STATUSES);
        if (active > 0) {
            throw new AccountDeletionException(
                    AccountDeletionException.Reason.ACTIVE_ORDERS,
                    String.format(
                            "%d order%s still in progress. Wait for completion or cancel them"
                                    + " first.",
                            active, active == 1 ? "" : "s"));
        }
    }

    // =====================================================================
    // Anonymization
    // =====================================================================

    private void anonymize(User user) {
        Long id = user.getId();
        String tombstoneEmail = "deleted-" + id + "@deleted.local";
        String tombstoneUsername = "deleted-" + id;

        user.setEmail(tombstoneEmail);
        user.setUsername(tombstoneUsername);
        user.setPasswordHash(passwordEncoder.encode(randomToken(RANDOM_PASSWORD_BYTES)));
        user.setApiKeyHash(null);
        user.setApiKeySalt(null);
        user.setApiKeyPreview(null);
        user.setApiKeyActive(false);
        user.setApiKeyPausedAt(null);
        user.setEmailVerified(false);
        user.setEmailVerifiedAt(null);
        user.setActive(false);
        user.setTwoFactorEnabled(false);
        user.setDeletedAt(LocalDateTime.now());
    }

    private String randomToken(int len) {
        byte[] bytes = new byte[len];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    // =====================================================================
    // Audit / email
    // =====================================================================

    private void writeAuditLog(
            Long userId, String username, String email, HttpServletRequest request) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("anonymized_email", "deleted-" + userId + "@deleted.local");
        meta.put("anonymized_username", "deleted-" + userId);
        meta.put("hard_delete_after", LocalDateTime.now().plusDays(GRACE_PERIOD_DAYS));

        AuditLog entry =
                AuditLog.builder()
                        .entityType("USER")
                        .entityId(userId)
                        .entityIdentifier(username)
                        .action("ACCOUNT_DELETION_REQUESTED")
                        .category(AuditLog.AuditCategory.USER_MANAGEMENT)
                        .severity(AuditLog.AuditSeverity.WARNING)
                        .userId(userId)
                        .username(username)
                        .description(
                                "User initiated GDPR account deletion (soft-delete; hard-delete in"
                                        + " "
                                        + GRACE_PERIOD_DAYS
                                        + " days)")
                        .ipAddress(extractIp(request))
                        .userAgent(truncate(header(request, "User-Agent"), 500))
                        .metadata(meta)
                        .complianceChecked(true)
                        .retentionDays(2555)
                        .build();
        // Old/new value stored as plain text for fast inspection — JSON `changes` is for richer
        // diffs we don't need here.
        entry.setOldValue(email);
        entry.setNewValue("deleted-" + userId + "@deleted.local");
        auditLogRepository.save(entry);
    }

    /**
     * Register a callback to fire after the surrounding transaction successfully commits. Falls
     * back to running the action immediately if no transaction is active (defensive — the @{@code
     * Transactional} on {@link #deleteCurrentUser} guarantees one in production, but a unit-test
     * call without one shouldn't silently skip the email).
     */
    private static void registerAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private User currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new UserNotFoundException("Not authenticated");
        }
        return userRepository
                .findByUsername(auth.getName())
                .orElseThrow(() -> new UserNotFoundException("User not found"));
    }

    private static String extractIp(HttpServletRequest request) {
        if (request == null) return null;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real;
        return request.getRemoteAddr();
    }

    private static String header(HttpServletRequest request, String name) {
        return request == null ? null : request.getHeader(name);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Distinct rejection reasons so the controller can map each to a clean 4xx response. */
    public static class AccountDeletionException extends RuntimeException {
        public enum Reason {
            INVALID_CONFIRMATION,
            WRONG_PASSWORD,
            POSITIVE_BALANCE,
            ACTIVE_ORDERS,
        }

        private final Reason reason;

        public AccountDeletionException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }
    }
}
