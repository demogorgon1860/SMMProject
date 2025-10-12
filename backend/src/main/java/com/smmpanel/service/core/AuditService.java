package com.smmpanel.service.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.entity.AuditLog;
import com.smmpanel.entity.BalanceDeposit;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Comprehensive Audit Service for tracking all system changes Implements async logging to minimize
 * performance impact Based on Stack Overflow best practices for audit logging
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    // ========== Payment Audit Methods ==========

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void auditPaymentInitiated(
            User user, BigDecimal amount, String currency, String paymentMethod, String provider) {
        try {
            AuditLog audit =
                    AuditLog.builder()
                            .entityType("PAYMENT")
                            .action("PAYMENT_INITIATED")
                            .category(AuditLog.AuditCategory.PAYMENT)
                            .severity(AuditLog.AuditSeverity.INFO)
                            .userId(user.getId())
                            .username(user.getUsername())
                            .userRole(user.getRole().name())
                            .paymentAmount(amount)
                            .paymentCurrency(currency)
                            .paymentMethod(paymentMethod)
                            .paymentProvider(provider)
                            .description(
                                    String.format(
                                            "Payment initiated: %s %s via %s",
                                            amount, currency, provider))
                            .build();

            enrichWithRequestInfo(audit);
            auditLogRepository.save(audit);

            log.debug("Audited payment initiation for user: {}", user.getUsername());

        } catch (Exception e) {
            log.error("Failed to audit payment initiation", e);
        }
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void auditPaymentCompleted(BalanceDeposit deposit, String transactionId) {
        try {
            AuditLog audit =
                    AuditLog.builder()
                            .entityType("PAYMENT")
                            .entityId(deposit.getId())
                            .entityIdentifier(deposit.getOrderId())
                            .action("PAYMENT_COMPLETED")
                            .category(AuditLog.AuditCategory.PAYMENT)
                            .severity(AuditLog.AuditSeverity.INFO)
                            .userId(deposit.getUser().getId())
                            .username(deposit.getUser().getUsername())
                            .paymentAmount(deposit.getAmountUsdt())
                            .paymentCurrency("USDT")
                            .paymentProvider("CRYPTOMUS")
                            .transactionId(transactionId)
                            .paymentStatus("COMPLETED")
                            .description(
                                    String.format(
                                            "Payment completed: %s USDT", deposit.getAmountUsdt()))
                            .build();

            enrichWithRequestInfo(audit);
            audit.addMetadata("deposit_id", deposit.getId());
            audit.addMetadata("cryptomus_payment_id", deposit.getCryptomusPaymentId());

            auditLogRepository.save(audit);

            log.debug("Audited payment completion for deposit: {}", deposit.getOrderId());

        } catch (Exception e) {
            log.error("Failed to audit payment completion", e);
        }
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void auditPaymentFailed(BalanceDeposit deposit, String reason) {
        try {
            AuditLog audit =
                    AuditLog.builder()
                            .entityType("PAYMENT")
                            .entityId(deposit.getId())
                            .entityIdentifier(deposit.getOrderId())
                            .action("PAYMENT_FAILED")
                            .category(AuditLog.AuditCategory.PAYMENT)
                            .severity(AuditLog.AuditSeverity.WARNING)
                            .userId(deposit.getUser().getId())
                            .username(deposit.getUser().getUsername())
                            .paymentAmount(deposit.getAmountUsdt())
                            .paymentCurrency("USDT")
                            .paymentProvider("CRYPTOMUS")
                            .paymentStatus("FAILED")
                            .description(String.format("Payment failed: %s", reason))
                            .build();

            enrichWithRequestInfo(audit);
            audit.addMetadata("failure_reason", reason);

            auditLogRepository.save(audit);

            log.debug("Audited payment failure for deposit: {}", deposit.getOrderId());

        } catch (Exception e) {
            log.error("Failed to audit payment failure", e);
        }
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void auditRefundInitiated(Order order, BigDecimal amount, String reason) {
        try {
            AuditLog audit =
                    AuditLog.builder()
                            .entityType("PAYMENT")
                            .entityId(order.getId())
                            .entityIdentifier(order.getOrderId())
                            .action("REFUND_INITIATED")
                            .category(AuditLog.AuditCategory.PAYMENT)
                            .severity(AuditLog.AuditSeverity.INFO)
                            .userId(order.getUser().getId())
                            .username(order.getUser().getUsername())
                            .paymentAmount(amount)
                            .paymentCurrency("USD")
                            .description(
                                    String.format(
                                            "Refund initiated: %s for order %d - %s",
                                            amount, order.getId(), reason))
                            .build();

            enrichWithRequestInfo(audit);
            audit.addMetadata("refund_reason", reason);
            audit.addMetadata("order_status", order.getStatus().name());

            auditLogRepository.save(audit);

            log.debug("Audited refund initiation for order: {}", order.getId());

        } catch (Exception e) {
            log.error("Failed to audit refund initiation", e);
        }
    }

    // ========== Order Audit Methods ==========

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void auditOrderCreated(Order order) {
        try {
            AuditLog audit =
                    AuditLog.builder()
                            .entityType("ORDER")
                            .entityId(order.getId())
                            .action("ORDER_CREATED")
                            .category(AuditLog.AuditCategory.ORDER)
                            .severity(AuditLog.AuditSeverity.INFO)
                            .userId(order.getUser().getId())
                            .username(order.getUser().getUsername())
                            .description(
                                    String.format(
                                            "Order created: Service %s, Quantity %d, Charge %s",
                                            order.getService().getName(),
                                            order.getQuantity(),
                                            order.getCharge()))
                            .build();

            enrichWithRequestInfo(audit);
            audit.addMetadata("service_id", order.getService().getId());
            audit.addMetadata("quantity", order.getQuantity());
            audit.addMetadata("charge", order.getCharge());

            auditLogRepository.save(audit);

        } catch (Exception e) {
            log.error("Failed to audit order creation", e);
        }
    }

    // ========== User Audit Methods ==========

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void auditUserLogin(User user, String authMethod, boolean success) {
        try {
            AuditLog audit =
                    AuditLog.builder()
                            .entityType("USER")
                            .entityId(user.getId())
                            .action(success ? "LOGIN_SUCCESS" : "LOGIN_FAILED")
                            .category(AuditLog.AuditCategory.AUTHENTICATION)
                            .severity(
                                    success
                                            ? AuditLog.AuditSeverity.INFO
                                            : AuditLog.AuditSeverity.WARNING)
                            .userId(user.getId())
                            .username(user.getUsername())
                            .description(
                                    String.format(
                                            "%s login %s via %s",
                                            user.getUsername(),
                                            success ? "successful" : "failed",
                                            authMethod))
                            .build();

            enrichWithRequestInfo(audit);
            audit.addMetadata("auth_method", authMethod);

            auditLogRepository.save(audit);

        } catch (Exception e) {
            log.error("Failed to audit user login", e);
        }
    }

    // ========== Helper Methods ==========

    private void enrichWithRequestInfo(AuditLog audit) {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();

                audit.setIpAddress(getClientIpAddress(request));
                audit.setUserAgent(request.getHeader("User-Agent"));
                audit.setRequestId(request.getHeader("X-Request-Id"));
                audit.setSessionId(
                        request.getSession(false) != null ? request.getSession().getId() : null);

                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.isAuthenticated() && audit.getUserId() == null) {
                    audit.setUsername(auth.getName());
                }
            }

            audit.setExecutionTimeMs(System.currentTimeMillis());

        } catch (Exception e) {
            log.debug("Could not enrich audit log with request info", e);
        }
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String[] headers = {
            "X-Forwarded-For",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_X_FORWARDED_FOR",
            "HTTP_X_FORWARDED",
            "HTTP_X_CLUSTER_CLIENT_IP",
            "HTTP_CLIENT_IP",
            "HTTP_FORWARDED_FOR",
            "HTTP_FORWARDED",
            "HTTP_VIA",
            "REMOTE_ADDR"
        };

        for (String header : headers) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                if (ip.contains(",")) {
                    return ip.split(",")[0].trim();
                }
                return ip;
            }
        }

        return request.getRemoteAddr();
    }

    // ========== Query Methods ==========

    public Page<AuditLog> getAuditLogs(
            String entityType,
            Long userId,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable) {
        return auditLogRepository.findByFilters(entityType, userId, from, to, pageable);
    }

    public Page<AuditLog> getPaymentAuditLogs(
            Long userId, LocalDateTime from, LocalDateTime to, Pageable pageable) {
        return auditLogRepository.findByCategory(
                AuditLog.AuditCategory.PAYMENT, userId, from, to, pageable);
    }

    // Legacy methods for compatibility
    public void logOrderCompletion(Order order, String reason) {
        auditOrderCreated(order);
    }

    public void logOrderCancellation(Order order, String reason) {
        auditOrderCreated(order);
    }

    public void logOrderPause(Order order) {
        auditOrderCreated(order);
    }

    public void logOrderResume(Order order) {
        auditOrderCreated(order);
    }

    public void logBulkOrderAction(List<Long> orderIds, String action) {
        log.info("Bulk action {} on orders: {}", action, orderIds);
    }

    // BinomCampaign methods removed - campaigns are now managed dynamically in Binom
    // without local storage

    public void logUserCreation(User user) {
        auditUserLogin(user, "REGISTRATION", true);
    }

    public void logRoleChange(
            User user, com.smmpanel.entity.UserRole oldRole, com.smmpanel.entity.UserRole newRole) {
        log.info("User {} role changed from {} to {}", user.getUsername(), oldRole, newRole);
    }

    public void logUserStatusChange(User user, boolean oldStatus, Boolean newStatus) {
        log.info("User {} status changed from {} to {}", user.getUsername(), oldStatus, newStatus);
    }

    public void logUserDeletion(User user) {
        log.info("User deleted: {}", user.getUsername());
    }

    public void logApiKeyGeneration(User user) {
        log.info("API key generated for user: {}", user.getUsername());
    }

    public void logApiKeyRevocation(User user) {
        log.info("API key revoked for user: {}", user.getUsername());
    }
}
