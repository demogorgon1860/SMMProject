package com.smmpanel.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

@Slf4j
@Configuration
public class RequestLoggingConfig {

    /**
     * Request logging is intentionally narrow:
     *
     * <ul>
     *   <li>Headers are excluded — Authorization/Cookie would leak the JWT and refresh token.
     *   <li>Payload is excluded — POST /api/v1/auth/login, /register, /forgot-password etc. carry
     *       plaintext passwords and one-time codes; the Cryptomus webhook carries amounts and a
     *       signed body that is fine but not actually useful at request-log granularity.
     *   <li>Auth endpoints are skipped entirely so we never accidentally trace credential flow.
     * </ul>
     *
     * <p>Anything beyond the URL + remote IP belongs in deliberate, redacted log statements at the
     * point of business logic — not in a wide-net request filter.
     */
    @Bean
    public CommonsRequestLoggingFilter requestLoggingFilter() {
        CommonsRequestLoggingFilter loggingFilter =
                new CommonsRequestLoggingFilter() {
                    @Override
                    protected boolean shouldLog(HttpServletRequest request) {
                        String uri = request.getRequestURI();
                        if (uri == null) return false;
                        // Don't log auth/payment-callback bodies even via prefix; protects against
                        // a future change that flips includePayload on.
                        if (uri.contains("/auth/")
                                || uri.contains("/payments/cryptomus/callback")
                                || uri.contains("/telegram/webhook")
                                || uri.contains("/webhook/")) {
                            return false;
                        }
                        return super.shouldLog(request);
                    }
                };
        loggingFilter.setIncludeClientInfo(true);
        loggingFilter.setIncludeQueryString(true);
        // Bodies can carry passwords / OTPs / signed payloads. Never include.
        loggingFilter.setIncludePayload(false);
        loggingFilter.setIncludeHeaders(false);
        loggingFilter.setBeforeMessagePrefix("[REQUEST] ");
        loggingFilter.setAfterMessagePrefix("[RESPONSE] ");
        return loggingFilter;
    }
}
