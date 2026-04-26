package com.smmpanel.service.auth;

import com.smmpanel.entity.EmailVerificationToken;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.EmailVerificationTokenRepository;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.email.EmailService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email verification flow.
 *
 * <p>Issues a single-use 6-digit code, stores its SHA-256 hash with TTL, and emails it to the user.
 * On verify the hash is matched, marked used, and the user record is flipped to verified.
 *
 * <p>Security properties:
 *
 * <ul>
 *   <li>Codes are generated with {@link SecureRandom}, never sequential.
 *   <li>Only the hash is stored — a DB dump never reveals usable codes.
 *   <li>Used / expired codes are explicitly rejected (no race window after first use).
 *   <li>{@link #issueCodeFor(User)} invalidates any previous unused code so a stolen older code
 *       can't be used after a fresh one is sent.
 *   <li>{@link #resend(String)} uses a per-user cooldown to defeat brute force / send-spam.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final WelcomeCreditService welcomeCreditService;

    @Value("${app.auth.verification.ttl-hours:24}")
    private int ttlHours;

    @Value("${app.auth.verification.resend-cooldown-seconds:60}")
    private int resendCooldownSeconds;

    /**
     * Generate, store and email a fresh code. Any previous unused codes for this user are
     * invalidated. Safe to call repeatedly during signup.
     */
    @Transactional
    public void issueCodeFor(User user) {
        if (user == null) return;
        if (user.isEmailVerified()) {
            log.debug("issueCodeFor: user {} already verified — skipping", user.getId());
            return;
        }

        // Invalidate any existing pending codes so only the latest is valid.
        tokenRepository.markAllUsedForUser(user.getId(), LocalDateTime.now());

        String code = generateCode();
        EmailVerificationToken token =
                EmailVerificationToken.builder()
                        .userId(user.getId())
                        .codeHash(sha256(code))
                        .expiresAt(LocalDateTime.now().plus(Duration.ofHours(ttlHours)))
                        .build();
        tokenRepository.save(token);

        emailService.sendVerificationCode(user.getEmail(), user.getUsername(), code);
        log.info("Issued verification code for user {}", user.getId());
    }

    /** Resend the verification code, honoring a per-user cooldown. */
    @Transactional
    public ResendResult resend(String email) {
        Optional<User> userOpt = userRepository.findByEmail(email.trim().toLowerCase());
        if (userOpt.isEmpty() || userOpt.get().isEmailVerified()) {
            // Don't leak which emails exist. Pretend everything went fine.
            return ResendResult.OK;
        }
        User user = userOpt.get();

        Optional<EmailVerificationToken> latest =
                tokenRepository.findLatestActiveByUserId(user.getId());
        if (latest.isPresent() && !latest.get().isExpired()) {
            LocalDateTime cutoff = latest.get().getCreatedAt().plusSeconds(resendCooldownSeconds);
            if (LocalDateTime.now().isBefore(cutoff)) {
                long secondsLeft = Duration.between(LocalDateTime.now(), cutoff).toSeconds();
                log.info(
                        "Resend cooldown active for user {} ({}s remaining)",
                        user.getId(),
                        secondsLeft);
                return ResendResult.cooldown(Math.max(1, secondsLeft));
            }
        }

        issueCodeFor(user);
        return ResendResult.OK;
    }

    /**
     * Verify a code. Returns {@code true} on success and flips {@link User#setEmailVerified}.
     *
     * <p>Always rejects: missing user, mismatched email/code, used codes, expired codes.
     *
     * <p>The redeem step uses a conditional UPDATE on {@code usedAt IS NULL} so two concurrent
     * verify calls with the same code can't both succeed.
     */
    @Transactional
    public boolean verify(String email, String code) {
        if (email == null || code == null) return false;

        Optional<User> userOpt = userRepository.findByEmail(email.trim().toLowerCase());
        if (userOpt.isEmpty()) {
            // Same response as failed code so we don't leak account existence.
            return false;
        }
        User user = userOpt.get();
        if (user.isEmailVerified()) {
            return true; // idempotent — already done.
        }

        Optional<EmailVerificationToken> tokenOpt =
                tokenRepository.findActiveForUser(user.getId(), sha256(code));
        if (tokenOpt.isEmpty()) return false;

        EmailVerificationToken token = tokenOpt.get();
        if (token.isExpired()) return false;

        // Atomic claim — exactly one of N concurrent verifies flips usedAt.
        int claimed = tokenRepository.markUsedIfUnused(token.getId(), LocalDateTime.now());
        if (claimed == 0) return false;

        user.setEmailVerified(true);
        user.setEmailVerifiedAt(LocalDateTime.now());
        userRepository.save(user);

        // Grant welcome credit on first verification. Runs in its own transaction
        // (REQUIRES_NEW) — a credit-grant failure (e.g. balance service hiccup) must not roll
        // back the email verification itself. Verification is the high-value business state;
        // the credit is best-effort and self-healing on the next admin run.
        try {
            welcomeCreditService.grantIfEligible(user);
        } catch (Exception e) {
            log.warn(
                    "Email verified but welcome credit grant failed for user {}: {}",
                    user.getId(),
                    e.toString());
        }

        emailService.sendWelcome(user.getEmail(), user.getUsername());
        log.info("Email verified for user {}", user.getId());
        return true;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private String generateCode() {
        // Always 6 digits, leading zeros preserved.
        int n = SECURE_RANDOM.nextInt(1_000_000);
        return String.format("%06d", n);
    }

    /** SHA-256 lowercase hex. Constant-time-ish equality is provided by the unique index lookup. */
    static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Result for the "resend" call. */
    public static final class ResendResult {
        public static final ResendResult OK = new ResendResult(true, 0);
        private final boolean ok;
        private final long retryAfterSeconds;

        private ResendResult(boolean ok, long retryAfterSeconds) {
            this.ok = ok;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public static ResendResult cooldown(long seconds) {
            return new ResendResult(false, seconds);
        }

        public boolean isOk() {
            return ok;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
