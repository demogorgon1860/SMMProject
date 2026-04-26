package com.smmpanel.service.email;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * High-level transactional email sender. All public methods are async — never block the request
 * thread on a third-party HTTP call.
 *
 * <p>If {@code app.email.enabled=false} or the Resend API key is not configured, methods log the
 * intent and return without raising. This is the right behavior for dev and CI.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final ResendClient resendClient;

    @Value("${app.email.enabled:false}")
    private boolean enabled;

    @Value("${app.email.from:hello@smmworld.vip}")
    private String fromAddress;

    @Value("${app.email.from-name:SMMWorld}")
    private String fromName;

    @Value("${app.email.public-base-url:https://smmworld.vip}")
    private String publicBaseUrl;

    // =====================================================================
    // Phase 3 transactional emails (verify / reset / welcome)
    // =====================================================================

    /**
     * Send a 6-digit email verification code. The code is shown plainly in the body (it's a
     * short-lived single-use OTP); no link is required.
     */
    @Async("asyncExecutor")
    public void sendVerificationCode(String toEmail, String username, String code) {
        if (!shouldSend(toEmail)) {
            log.info("Email disabled: would send verification code to {} (code={})", toEmail, code);
            return;
        }
        String subject = "Your SMMWorld verification code";
        String html = EmailTemplates.verificationCode(username, code);
        String text =
                String.format(
                        "Hi %s,%n%nYour SMMWorld verification code is: %s%n%nIt expires in 24"
                                + " hours. If you didn't ask to verify your email, ignore this"
                                + " message.",
                        username, code);
        sendQuietly(toEmail, subject, html, text);
    }

    /**
     * Send a password-reset link. We reveal the same success message regardless of whether the
     * email belongs to a real account, so this method must NEVER throw on unknown email — callers
     * pass the link only when they actually have a token.
     */
    @Async("asyncExecutor")
    public void sendPasswordResetLink(String toEmail, String username, String token) {
        if (!shouldSend(toEmail)) {
            log.info(
                    "Email disabled: would send password reset link to {} (token={})",
                    toEmail,
                    token);
            return;
        }
        String resetUrl = publicBaseUrl + "/reset?token=" + token;
        String subject = "Reset your SMMWorld password";
        String html = EmailTemplates.passwordReset(username, resetUrl);
        String text =
                String.format(
                        "Hi %s,%n%nClick the link to reset your password:%n%s%n%nThe link expires"
                                + " in 1 hour. If you didn't request this, you can safely ignore"
                                + " this email.",
                        username, resetUrl);
        sendQuietly(toEmail, subject, html, text);
    }

    /** One-shot welcome email after a successful verification. */
    @Async("asyncExecutor")
    public void sendWelcome(String toEmail, String username) {
        if (!shouldSend(toEmail)) {
            log.info("Email disabled: would send welcome to {}", toEmail);
            return;
        }
        String subject = "Welcome to SMMWorld";
        String html = EmailTemplates.welcome(username, publicBaseUrl);
        String text =
                String.format(
                        "Welcome %s — your SMMWorld account is ready. Sign in: %s/login",
                        username, publicBaseUrl);
        sendQuietly(toEmail, subject, html, text);
    }

    // =====================================================================
    // Pre-existing low-level API (kept for backwards compatibility)
    // =====================================================================

    /** Send a simple plain-text email. Subject is also used as the inline H1. */
    public void sendEmail(String to, String subject, String body) {
        if (!shouldSend(to)) {
            log.debug("Email disabled: would send '{}' to {}", subject, to);
            return;
        }
        String escapedBody =
                body == null
                        ? ""
                        : "<pre style='font-family:inherit;white-space:pre-wrap;font-size:14px;"
                                + "color:#333;line-height:1.55'>"
                                + body.replace("&", "&amp;")
                                        .replace("<", "&lt;")
                                        .replace(">", "&gt;")
                                + "</pre>";
        sendQuietly(to, subject, escapedBody, body);
    }

    /** Operational alert — always sent (never to end-users). */
    public void sendAlert(String to, String alertTitle, String alertMessage, String level) {
        String subject =
                String.format("[%s] %s", level == null ? "INFO" : level.toUpperCase(), alertTitle);
        String body =
                String.format(
                        "Alert Level: %s%n%nTitle: %s%n%nMessage:%n%s%n%nGenerated at: %s",
                        level, alertTitle, alertMessage, LocalDateTime.now());
        sendEmail(to, subject, body);
    }

    /** Generic user-facing notification. */
    public void sendNotification(String to, String title, String message) {
        sendEmail(to, title, message);
    }

    /** Internal team notification — falls through to {@link #sendEmail} if a target is provided. */
    public void sendToTeam(String subject, String body) {
        log.info("sendToTeam '{}': team email distribution not configured", subject);
        // No-op until app.email.team-recipients is wired (out of Phase-3 scope).
    }

    // =====================================================================
    // Internals
    // =====================================================================

    private boolean shouldSend(String toEmail) {
        if (toEmail == null || toEmail.isBlank()) return false;
        if (!enabled) return false;
        if (!resendClient.isConfigured()) {
            log.warn(
                    "Email is enabled but Resend API key is missing. Configure RESEND_API_KEY or"
                            + " set app.email.enabled=false.");
            return false;
        }
        return true;
    }

    private void sendQuietly(String toEmail, String subject, String html, String text) {
        try {
            resendClient.send(
                    ResendClient.SendEmailRequest.builder()
                            .from(formatFrom())
                            .to(List.of(toEmail))
                            .subject(subject)
                            .html(html)
                            .text(text)
                            .build());
        } catch (EmailDeliveryException e) {
            // Log + drop. Auth flows already treat email delivery as best-effort: a missed
            // verification email is recoverable through "Resend code"; a missed password reset
            // is recoverable through "Forgot password" again.
            log.error("Email delivery failed (subject={}): {}", subject, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unexpected email failure (subject={}): {}", subject, e.toString());
        }
    }

    private String formatFrom() {
        if (fromName == null || fromName.isBlank()) return fromAddress;
        return fromName + " <" + fromAddress + ">";
    }
}
