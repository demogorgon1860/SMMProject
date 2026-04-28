package com.smmpanel.dto.profile;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One active session row for {@code GET /v1/me/sessions}. The IP is anonymized to {@code /24} (last
 * octet replaced with {@code x}) before leaving the server — the Sessions UI never shows full
 * client IPs to the account holder, both on principle (GDPR data minimization) and to keep the
 * value useful even when the user is behind CGNAT or a corporate proxy.
 *
 * <p>The raw {@code User-Agent} is returned unparsed and the frontend prettifies it. Doing the UA
 * parse here would either ship a UA library on the backend or rebuild a brittle string switch on
 * the server — both worse than a 50-line client-side prettifier.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionDto {
    private Long id;
    private String userAgent;
    private String ipAddress;
    private String location;
    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime expiresAt;
    private boolean current;
}
