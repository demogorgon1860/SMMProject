package com.smmpanel.service;

import com.smmpanel.entity.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    public void logOrderCompletion(Order order, String reason) {
        log.info("Order completed: {} - {}", order.getId(), reason);
    }

    public void logOrderCancellation(Order order, String reason) {
        log.info("Order cancelled: {} - {}", order.getId(), reason);
    }

    public void logOrderPause(Order order) {
        log.info("Order paused: {}", order.getId());
    }

    public void logOrderResume(Order order) {
        log.info("Order resumed: {}", order.getId());
    }

    public void logBulkOrderAction(List<Long> orderIds, String action) {
        log.info("Bulk action {} on orders: {}", action, orderIds);
    }

    public void logBinomCampaignCreation(BinomCampaign campaign) {
        log.info("Binom campaign created: {}", campaign.getCampaignId());
    }

    public void logBinomCampaignStop(BinomCampaign campaign) {
        log.info("Binom campaign stopped: {}", campaign.getCampaignId());
    }

    public void logBinomCampaignPause(BinomCampaign campaign) {
        log.info("Binom campaign paused: {}", campaign.getCampaignId());
    }

    public void logBinomCampaignResume(BinomCampaign campaign) {
        log.info("Binom campaign resumed: {}", campaign.getCampaignId());
    }

    public void logUserCreation(User user) {
        log.info("User created: {}", user.getUsername());
    }

    public void logRoleChange(User user, UserRole oldRole, UserRole newRole) {
        log.info("User {} role changed from {} to {}", user.getUsername(), oldRole, newRole);
    }

    public void logUserStatusChange(User user, boolean oldStatus, boolean newStatus) {
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
