package com.smmpanel.service;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for auditing system operations and state changes
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    /**
     * Log order state transition for audit purposes
     */
    public void logOrderStateTransition(Order order, String fromState, String toState, String reason) {
        log.info("Order {} state transition: {} -> {} (Reason: {})", 
                order.getId(), fromState, toState, reason);
    }

    /**
     * Log user action for audit purposes
     */
    public void logUserAction(User user, String action, String details) {
        log.info("User {} performed action: {} (Details: {})", 
                user.getUsername(), action, details);
    }

    /**
     * Log admin action for audit purposes
     */
    public void logAdminAction(User admin, String action, String target, String details) {
        log.warn("Admin {} performed action: {} on {} (Details: {})", 
                admin.getUsername(), action, target, details);
    }

    /**
     * Log system event for audit purposes
     */
    public void logSystemEvent(String event, String details) {
        log.info("System event: {} (Details: {})", event, details);
    }
} 