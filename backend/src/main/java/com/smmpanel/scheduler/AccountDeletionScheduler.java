package com.smmpanel.scheduler;

import com.smmpanel.entity.AuditLog;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.AuditLogRepository;
import com.smmpanel.repository.jpa.UserRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hard-delete the row of users whose 30-day soft-delete grace window has elapsed. The Liquibase
 * migration {@code v2026.04-account-deletion.xml} configured the FK cascades so that simply issuing
 * {@code DELETE FROM users WHERE id = ?} causes:
 *
 * <ul>
 *   <li>orders / balance_transactions / balance_deposits / support_tickets / refill_requests —
 *       user_id set to NULL (kept for AML retention)
 *   <li>refresh_tokens / email_verification_tokens / password_reset_tokens /
 *       user_notification_prefs — cascade delete (no value beyond the user)
 * </ul>
 *
 * <p>We process the batch row-by-row in independent transactions so a single failed delete (e.g. a
 * partition catalog issue or a NOT NULL FK we missed) doesn't block the rest of the batch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDeletionScheduler {

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;

    /**
     * Per-user transactional boundary. Plain {@code @Transactional} on {@link #hardDelete} would be
     * silently bypassed because we call it from a method on the same bean (Spring's CGLIB proxy
     * doesn't intercept self-invocation). The template makes the boundary explicit and keeps each
     * user's purge isolated from the rest of the batch.
     */
    private final TransactionTemplate transactionTemplate;

    @Value("${app.account-deletion.grace-period-days:30}")
    private int graceDays;

    @Value("${app.account-deletion.batch-size:100}")
    private int batchSize;

    /**
     * 03:00 daily — quiet window between the refresh-token cleanup (02:00) and the start of EU
     * working hours, so a slow purge has minimum chance of contending with user traffic.
     */
    @Scheduled(cron = "${app.account-deletion.cron:0 0 3 * * *}")
    public void purgeExpiredAccounts() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(graceDays);
        List<User> expired = userRepository.findSoftDeletedBefore(cutoff, batchSize);
        if (expired.isEmpty()) {
            log.debug("No accounts past the {}-day grace window — nothing to purge", graceDays);
            return;
        }
        log.info(
                "Hard-deleting {} accounts whose 30-day grace window expired before {}",
                expired.size(),
                cutoff);

        int success = 0;
        int failed = 0;
        for (User user : expired) {
            try {
                hardDelete(user);
                success++;
            } catch (Exception e) {
                // Log + continue. A persistent failure (e.g. a residual NOT NULL FK we forgot)
                // will keep showing up tomorrow until an operator notices and fixes it.
                failed++;
                log.error(
                        "Hard-delete failed for user id={} (soft-deleted at {}): {}",
                        user.getId(),
                        user.getDeletedAt(),
                        e.toString(),
                        e);
            }
        }
        log.info("Account purge complete: {} hard-deleted, {} failed", success, failed);
    }

    /**
     * Single-user purge in its own transaction so per-user failures don't poison the batch. Writes
     * a final audit row before the user vanishes — once the row is gone, the audit_logs.user_id
     * (nullable, no FK) is the only surviving reference.
     */
    void hardDelete(User user) {
        transactionTemplate.executeWithoutResult(
                status -> {
                    Long userId = user.getId();
                    writeAuditLog(userId, user.getDeletedAt());
                    userRepository.delete(user);
                    log.info(
                            "Hard-deleted user id={} (soft-delete was {})",
                            userId,
                            user.getDeletedAt());
                });
    }

    private void writeAuditLog(Long userId, LocalDateTime softDeletedAt) {
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("soft_deleted_at", softDeletedAt);
            meta.put("hard_deleted_at", LocalDateTime.now());
            meta.put("grace_days", graceDays);

            AuditLog entry =
                    AuditLog.builder()
                            .entityType("USER")
                            .entityId(userId)
                            .action("ACCOUNT_HARD_DELETED")
                            .category(AuditLog.AuditCategory.USER_MANAGEMENT)
                            .severity(AuditLog.AuditSeverity.WARNING)
                            .userId(userId)
                            .username("deleted-" + userId)
                            .description(
                                    "30-day grace window elapsed — user row hard-deleted, related"
                                            + " AML records (orders, transactions, deposits) had"
                                            + " their user_id set to NULL by FK cascade.")
                            .metadata(meta)
                            .complianceChecked(true)
                            .retentionDays(2555)
                            .isPiiRedacted(true)
                            .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.error(
                    "Failed to write hard-delete audit row for user {}: {}", userId, e.toString());
        }
    }
}
