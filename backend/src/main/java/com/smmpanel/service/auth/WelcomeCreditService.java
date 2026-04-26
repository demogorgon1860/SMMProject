package com.smmpanel.service.auth;

import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.balance.BalanceService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grants the one-time welcome credit upon successful email verification.
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>Caller invokes {@link #grantIfEligible(User)} after flipping {@code emailVerified=true}.
 *   <li>Service no-ops if {@code app.welcome-credit.amount} is zero or unset (feature disabled).
 *   <li>Otherwise: atomic CAS on {@code users.welcome_credit_granted_at} (NULL -> NOW). If 0 rows
 *       updated, another concurrent verify already won — return without crediting. If 1 row
 *       updated, this caller is the unique winner; credit the wallet via {@link
 *       BalanceService#addBalance}, which writes a {@code balance_transactions} audit record under
 *       pessimistic lock.
 * </ol>
 *
 * <p><b>Failure semantics:</b> {@link #grantIfEligible(User)} runs in its own transaction ({@code
 * REQUIRES_NEW}) and propagates exceptions to the caller. Callers who don't want a credit failure
 * to roll back their own transaction should wrap the call in a try-catch and log/swallow. Email
 * verification does this — a credit failure shouldn't undo the user's verification.
 *
 * <p><b>Idempotency:</b> The CAS guarantees at-most-once per user across infinite retries from any
 * source (panel verify, admin force-verify, scheduled batch jobs, etc.).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WelcomeCreditService {

    private final UserRepository userRepository;
    private final BalanceService balanceService;

    /**
     * Welcome credit amount in USD. Set to {@code 0} (or unset) to disable the feature entirely.
     */
    @Value("${app.welcome-credit.amount:0}")
    private BigDecimal amount;

    /**
     * Grants the welcome credit to {@code user} if (a) the feature is enabled, (b) the user has not
     * already received it. No-op otherwise. Runs in its own transaction so a credit failure does
     * not roll back the caller's transaction.
     *
     * @return {@code true} iff this call actually credited the wallet; {@code false} on no-op
     *     (already granted, feature disabled, or invalid input).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean grantIfEligible(User user) {
        if (user == null || user.getId() == null) return false;
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            // Feature disabled — don't even touch the column. If admin re-enables later, only
            // newly-verified users get the credit; existing already-verified users miss out, which
            // matches every other "no retroactive grants" SaaS welcome-bonus implementation.
            return false;
        }

        int updated = userRepository.markWelcomeCreditGranted(user.getId(), LocalDateTime.now());
        if (updated == 0) {
            log.debug("Welcome credit skipped for user {} — already granted", user.getId());
            return false;
        }

        // The CAS won, so we are the unique caller responsible for crediting. addBalance does
        // its own pessimistic-lock + audit-record write; both run in this REQUIRES_NEW tx, so the
        // CAS flag and the wallet credit commit atomically. If addBalance throws, the whole
        // grant is rolled back and a future retry can succeed (welcome_credit_granted_at goes
        // back to NULL on rollback).
        balanceService.addBalance(user, amount, null, "Welcome credit");
        log.info("Welcome credit ${} granted to user {}", amount, user.getId());
        return true;
    }
}
