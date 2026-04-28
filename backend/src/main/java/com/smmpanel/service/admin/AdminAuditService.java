package com.smmpanel.service.admin;

import com.smmpanel.entity.AdminAuditLog;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.AdminAuditLogRepository;
import com.smmpanel.repository.jpa.UserRepository;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records lightweight admin actions for the {@code /admin/dashboard} sidebar and the {@code GET
 * /v2/admin/audit-log} endpoint. See the migration {@code v2026.04-add-admin-audit-log} for the
 * rationale (vs. the comprehensive {@code audit_logs} compliance trail).
 *
 * <p>All record* methods run in a {@code REQUIRES_NEW} transaction so an audit failure cannot roll
 * back the underlying admin action. The action is the canonical state change; the audit row is
 * observability — losing one audit row is acceptable, losing the action would be a bug.
 *
 * <p>The actor is resolved from the security context. Callers don't need to thread it through.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private final AdminAuditLogRepository repo;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            String action, String targetType, Long targetId, String targetLabel, String summary) {
        recordWithAmount(action, targetType, targetId, targetLabel, summary, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordWithAmount(
            String action,
            String targetType,
            Long targetId,
            String targetLabel,
            String summary,
            BigDecimal amount) {
        try {
            Actor actor = currentActor();
            AdminAuditLog row =
                    AdminAuditLog.builder()
                            .adminId(actor.id)
                            .adminUsername(actor.username)
                            .action(action)
                            .targetType(targetType)
                            .targetId(targetId)
                            .targetLabel(targetLabel)
                            .summary(summary)
                            .amount(amount)
                            .build();
            repo.save(row);
        } catch (Exception e) {
            // Audit must never be the reason an admin action fails — log and swallow.
            log.warn(
                    "Failed to record admin audit: action={} target={}:{} reason={}",
                    action,
                    targetType,
                    targetId,
                    e.toString());
        }
    }

    private Actor currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || !auth.isAuthenticated()
                || auth.getName() == null
                || "anonymousUser".equals(auth.getName())) {
            // System-triggered admin actions (e.g. cancel-decision timeout from the scheduler).
            // Surfaced as "system" in the UI.
            return new Actor(null, "system");
        }
        String name = auth.getName();
        Optional<User> userOpt = userRepository.findByUsername(name);
        return userOpt.map(u -> new Actor(u.getId(), u.getUsername()))
                .orElse(new Actor(null, name));
    }

    private record Actor(Long id, String username) {}
}
